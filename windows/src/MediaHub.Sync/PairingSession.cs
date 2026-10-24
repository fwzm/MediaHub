using System.Globalization;

namespace MediaHub.Sync;

/// <summary>
/// Where a pairing attempt currently is (ADR-S002).
/// </summary>
/// <remarks>
/// <para>
/// <c>Created → ChallengeIssued → AwaitingPeer → Paired → Closed</c>, plus <c>Failed</c> from anywhere that is
/// not already closed. The shape is intentionally small: every extra state is another pair of transitions to
/// reason about in the one flow where getting it wrong hands a stranger a library.
/// </para>
/// <list type="bullet">
/// <item><description><c>ChallengeIssued</c> means this device showed an invitation; its challenge is live and
/// nobody has answered yet.</description></item>
/// <item><description><c>AwaitingPeer</c> means the two devices have exchanged payloads and the user has not
/// confirmed the fingerprints yet — the point where "pairing succeeded" is still a lie if the user does not
/// look at both screens.</description></item>
/// <item><description><c>Paired</c> is the transport agreeing on an identity; it says nothing about whether the
/// source works, which is why sources still authenticate for real afterwards (W6-2b).</description></item>
/// <item><description><c>Failed</c> carries one of <see cref="PairingReasons.All"/>; it is terminal but
/// closable, so the UI can dismiss it.</description></item>
/// </list>
/// </remarks>
public enum PairingState
{
    /// <summary>Started; no invitation issued and nothing scanned.</summary>
    Created,

    /// <summary>This device issued an invitation and is showing its QR payload.</summary>
    ChallengeIssued,

    /// <summary>The payloads have been exchanged; the user has not confirmed the pairing yet.</summary>
    AwaitingPeer,

    /// <summary>Both sides agreed on the identity. Sources still need their own authentication.</summary>
    Paired,

    /// <summary>Terminal and idempotent; nothing else may happen afterwards.</summary>
    Closed,

    /// <summary>Terminal failure carrying a stable <see cref="PairingReasons"/> code.</summary>
    Failed,
}

/// <summary>One recorded state change, with the reason that caused it and the time the caller supplied.</summary>
/// <param name="From">State before the change.</param>
/// <param name="To">State after the change.</param>
/// <param name="Reason">Readable reason: a transition name such as <c>challenge-issued</c>, or a
/// <see cref="PairingReasons"/> code when the change was a refusal.</param>
/// <param name="AtEpochMs">The caller's clock at the moment of the change.</param>
public sealed record PairingTransition(PairingState From, PairingState To, string Reason, long AtEpochMs);

/// <summary>
/// The state machine of one pairing attempt (ADR-S002): issue an invitation, accept the peer's payload, confirm,
/// or fail with a stable reason.
/// </summary>
/// <remarks>
/// <para>
/// Pure logic, deliberately: no socket, no file, no wall clock. Every method takes <c>nowEpochMs</c> from the
/// caller, which is what lets the expiry and replay rules be tested as functions rather than with sleeps or a
/// fake transport. The transport that actually exchanges these payloads (W6-3b) wraps this type; it does not
/// replace it.
/// </para>
/// <para>
/// Two entry paths, one shape. A device either <em>issues</em> a challenge and waits for the peer's answer
/// (<see cref="IssueChallenge"/>, then <see cref="AcceptPeerPayload"/> from <see cref="PairingState.ChallengeIssued"/>),
/// or it <em>scans</em> the peer's invitation (<see cref="AcceptPeerPayload"/> from
/// <see cref="PairingState.Created"/>). Both end in <see cref="PairingState.AwaitingPeer"/> with the peer's
/// identity recorded, and both require an explicit <see cref="Confirm"/> before
/// <see cref="PairingState.Paired"/>.
/// </para>
/// <para>
/// Contract violations throw <see cref="SyncContractException"/> carrying a <see cref="PairingReasons"/> code —
/// except the ones that are a real answer rather than a programming error (expired, replayed, mismatched),
/// which move the session to <see cref="PairingState.Failed"/> so the UI can show the reason instead of a
/// crash. Calling a method in a state that cannot take it is a caller bug and throws
/// <see cref="InvalidOperationException"/>.
/// </para>
/// <para>
/// No secret is stored here: a challenge is a nonce, a fingerprint is public, and the pairing payload has no
/// field for credentials in the first place.
/// </para>
/// </remarks>
public sealed class PairingSession
{
    /// <summary>Reason recorded when the user confirms the pairing.</summary>
    public const string PairedReason = "paired";

    /// <summary>Reason recorded when the attempt is closed.</summary>
    public const string ClosedReason = "closed";

    private readonly List<PairingTransition> _history = [];
    private readonly PairingChallengeLedger _ledger;
    private bool _challengeCameFromPeer;

    private PairingSession(string deviceId, long createdAtEpochMs, PairingChallengeLedger ledger)
    {
        DeviceId = deviceId;
        CreatedAtEpochMs = createdAtEpochMs;
        _ledger = ledger;
        State = PairingState.Created;
        LastReason = "";
    }

    /// <summary>The local device identifier written into this device's own payload.</summary>
    public string DeviceId { get; }

    /// <summary>The caller's clock when the attempt started.</summary>
    public long CreatedAtEpochMs { get; }

    /// <summary>Current state.</summary>
    public PairingState State { get; private set; }

    /// <summary>Readable reason for the current state; empty until the first transition.</summary>
    public string LastReason { get; private set; }

    /// <summary>Every transition so far, oldest first. The UI's "what happened" line is the last entry.</summary>
    public IReadOnlyList<PairingTransition> History => _history;

    /// <summary>The invitation this device issued, once <see cref="IssueChallenge"/> has run.</summary>
    public PairingPayload? Invitation { get; private set; }

    /// <summary>This device's fingerprint, once <see cref="IssueChallenge"/> has run.</summary>
    public PairingFingerprint? LocalFingerprint { get; private set; }

    /// <summary>The peer's device identifier, once its payload has been accepted.</summary>
    public string? RemoteDeviceId { get; private set; }

    /// <summary>The peer's fingerprint, once its payload has been accepted — show it; comparing it is the point.</summary>
    public PairingFingerprint? RemoteFingerprint { get; private set; }

    /// <summary>
    /// When the current invitation dies, in milliseconds since the Unix epoch: our own on the issuing side, the
    /// peer's on the scanning side.
    /// </summary>
    public long? ExpiresAtEpochMs { get; private set; }

    /// <summary>The challenge this attempt is bound to, once one is known.</summary>
    public PairingChallenge? Challenge { get; private set; }

    /// <summary>Starts an attempt. Nothing has been exchanged yet.</summary>
    /// <param name="deviceId">This device's identifier; must not be empty.</param>
    /// <param name="nowEpochMs">The caller's clock, in milliseconds since the Unix epoch.</param>
    /// <param name="ledger">One-shot store shared by the attempts of this process; a private one is created when
    /// omitted.</param>
    /// <exception cref="SyncContractException">The device identifier is empty (<c>missing-field:deviceId</c>).</exception>
    public static PairingSession Start(string? deviceId, long nowEpochMs, PairingChallengeLedger? ledger = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw PairingReasons.MissingField("deviceId");
        }

        return new PairingSession(deviceId, nowEpochMs, ledger ?? new PairingChallengeLedger());
    }

    /// <summary>
    /// Issues a fresh invitation: draws the one-shot challenge, builds the payload the QR carries, and moves to
    /// <see cref="PairingState.ChallengeIssued"/>.
    /// </summary>
    /// <exception cref="SyncContractException">The address or fingerprint is not acceptable
    /// (<c>bad-url</c>, <c>bad-fingerprint</c>).</exception>
    /// <exception cref="InvalidOperationException">The attempt is not in <see cref="PairingState.Created"/>.</exception>
    public PairingState IssueChallenge(string ephemeralUrl, PairingFingerprint localFingerprint, long nowEpochMs)
    {
        RequireState(PairingState.Created, nameof(IssueChallenge));
        if (localFingerprint.IsEmpty)
        {
            throw new SyncContractException(
                PairingReasons.BadFingerprint,
                "An invitation must carry this device's public-key fingerprint; an empty one would leave the peer nothing to compare.");
        }

        PairingPayload invitation = PairingPayload.Create(
            DeviceId,
            ephemeralUrl,
            PairingChallenge.Create(),
            localFingerprint,
            nowEpochMs);

        Invitation = invitation;
        LocalFingerprint = localFingerprint;
        Challenge = invitation.ParseChallenge();
        ExpiresAtEpochMs = invitation.ExpiresAtEpochMs;
        _challengeCameFromPeer = false;
        return MoveTo(PairingState.ChallengeIssued, "challenge-issued", nowEpochMs);
    }

    /// <summary>
    /// Accepts the peer's payload — either an invitation we scanned (from <see cref="PairingState.Created"/>) or
    /// the answer to one we issued (from <see cref="PairingState.ChallengeIssued"/>).
    /// </summary>
    /// <returns><see cref="PairingState.AwaitingPeer"/> on success, <see cref="PairingState.Failed"/> when the
    /// payload is a well-formed refusal (expired, replayed, mismatched).</returns>
    /// <exception cref="SyncContractException">The payload is malformed
    /// (<c>unsupported-version</c>, <c>bad-url</c>, <c>bad-challenge</c>, <c>bad-fingerprint</c>,
    /// <c>missing-field:*</c>).</exception>
    /// <exception cref="InvalidOperationException">The attempt is already paired, closed, or failed.</exception>
    public PairingState AcceptPeerPayload(PairingPayload payload, long nowEpochMs)
    {
        ArgumentNullException.ThrowIfNull(payload);

        if (State is not (PairingState.Created or PairingState.ChallengeIssued))
        {
            throw new InvalidOperationException(
                "A peer payload cannot be accepted in state " + State.ToString() + ".");
        }

        // A shape we cannot read is a wire problem: throw, and leave the state machine untouched.
        payload.ValidateShape();

        PairingChallenge challenge = payload.ParseChallenge();
        PairingFingerprint fingerprint = payload.ParseFingerprint();

        if (payload.ExpiresAtEpochMs <= nowEpochMs)
        {
            // A stale invitation is a normal refusal, not a bug: the user gets the reason instead of an exception.
            return Fail(PairingReasons.Expired, nowEpochMs);
        }

        if (State == PairingState.ChallengeIssued)
        {
            // The answer must echo the challenge we handed out, or we are talking to a replay of an older code.
            if (Challenge is not { } issued || !issued.Matches(challenge))
            {
                return Fail(PairingReasons.ChallengeMismatch, nowEpochMs);
            }
        }
        else
        {
            // Scanning side: an invitation is worth exactly one pairing, so spend it now.
            if (_ledger.IsConsumed(challenge))
            {
                return Fail(PairingReasons.ChallengeReplayed, nowEpochMs);
            }

            if (!_ledger.TryConsume(challenge))
            {
                return Fail(PairingReasons.ChallengeReplayed, nowEpochMs);
            }

            _challengeCameFromPeer = true;
        }

        RemoteDeviceId = payload.DeviceId;
        RemoteFingerprint = fingerprint;
        Challenge = challenge;
        if (_challengeCameFromPeer)
        {
            // Scanning side: the peer's invitation defines the window.
            ExpiresAtEpochMs = payload.ExpiresAtEpochMs;
        }

        // Issuing side keeps the window we advertised: a peer's longer expiry must not extend our invitation.

        return MoveTo(
            PairingState.AwaitingPeer,
            _challengeCameFromPeer ? "peer-invitation-accepted" : "peer-response-accepted",
            nowEpochMs);
    }

    /// <summary>
    /// Builds the payload this device sends back for an invitation it scanned: same challenge echoed, our own
    /// address, our own fingerprint, a fresh expiry.
    /// </summary>
    /// <exception cref="SyncContractException">The address is not acceptable (<c>bad-url</c>).</exception>
    /// <exception cref="InvalidOperationException">No peer invitation has been accepted.</exception>
    public PairingPayload ComposeResponse(string ephemeralUrl, PairingFingerprint localFingerprint, long nowEpochMs)
    {
        if (State != PairingState.AwaitingPeer || !_challengeCameFromPeer || Challenge is not { } challenge)
        {
            throw new InvalidOperationException(
                "A response can only be composed after accepting a peer invitation; the session is in state "
                    + State.ToString() + ".");
        }

        if (localFingerprint.IsEmpty)
        {
            throw new SyncContractException(
                PairingReasons.BadFingerprint,
                "A response must carry this device's public-key fingerprint.");
        }

        LocalFingerprint = localFingerprint;
        return PairingPayload.Create(DeviceId, ephemeralUrl, challenge, localFingerprint, nowEpochMs);
    }

    /// <summary>
    /// Confirms the pairing after the user has compared the fingerprints on both screens: moves to
    /// <see cref="PairingState.Paired"/>.
    /// </summary>
    /// <exception cref="InvalidOperationException">The attempt is not waiting for confirmation.</exception>
    public PairingState Confirm(long nowEpochMs)
    {
        RequireState(PairingState.AwaitingPeer, nameof(Confirm));

        if (ExpiresAtEpochMs is { } expiresAt && nowEpochMs >= expiresAt)
        {
            // The invitation died while the confirmation was on screen: refuse, do not pair on a stale challenge.
            return Fail(PairingReasons.Expired, nowEpochMs);
        }

        return MoveTo(PairingState.Paired, PairedReason, nowEpochMs);
    }

    /// <summary>Ends the attempt with a stable reason; further transitions are refused.</summary>
    /// <param name="reason">One of <see cref="PairingReasons.All"/>, or a caller-specific readable reason.</param>
    /// <param name="nowEpochMs">The caller's clock.</param>
    /// <exception cref="InvalidOperationException">The attempt already failed or was closed.</exception>
    public PairingState Fail(string reason, long nowEpochMs)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            throw new ArgumentException("A failure needs a reason.", nameof(reason));
        }

        if (State is PairingState.Failed or PairingState.Closed)
        {
            throw new InvalidOperationException("The attempt is already " + State.ToString() + ".");
        }

        return MoveTo(PairingState.Failed, reason, nowEpochMs);
    }

    /// <summary>Closes the attempt. Idempotent: a second call changes nothing, not even the history.</summary>
    public PairingState Close(long nowEpochMs)
    {
        if (State == PairingState.Closed)
        {
            return State;
        }

        return MoveTo(PairingState.Closed, ClosedReason, nowEpochMs);
    }

    /// <summary>Human-readable one-liner for logs: state plus the reason that produced it.</summary>
    public override string ToString()
        => State.ToString()
            + (string.IsNullOrEmpty(LastReason) ? "" : " (" + LastReason + ")")
            + " at " + CreatedAtEpochMs.ToString(CultureInfo.InvariantCulture) + " ms";

    private void RequireState(PairingState expected, string operation)
    {
        if (State != expected)
        {
            throw new InvalidOperationException(
                operation + " requires state " + expected.ToString() + "; the session is in state " + State.ToString() + ".");
        }
    }

    private PairingState MoveTo(PairingState next, string reason, long nowEpochMs)
    {
        PairingState previous = State;
        State = next;
        LastReason = reason;
        _history.Add(new PairingTransition(previous, next, reason, nowEpochMs));
        return State;
    }
}

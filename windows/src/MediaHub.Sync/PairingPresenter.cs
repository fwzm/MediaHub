using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>Where the hosting side of a pairing attempt currently is (W6-3c).</summary>
public enum PairingHostState
{
    /// <summary>Nothing has been started yet.</summary>
    Idle = 0,

    /// <summary>A listener is open and a code is valid; waiting for the peer.</summary>
    Listening,

    /// <summary>The peer connected and its payload was accepted.</summary>
    Paired,

    /// <summary>The attempt failed for a stable reason (see <see cref="PairingStepCodes"/>).</summary>
    Failed,

    /// <summary>The invitation expired before the peer connected; the listener is closed.</summary>
    Expired,

    /// <summary>The user ended the attempt; the listener is closed.</summary>
    Stopped,
}

/// <summary>
/// Stable machine codes for every step of a pairing attempt (W6-3c).
/// </summary>
/// <remarks>
/// The UI shows Chinese text, the tests and the log carry these codes. Keeping them separate means a wording change
/// never breaks a test, and a code change never silently changes what a user reads. The guest side distinguishes
/// three outcomes on purpose: the code was refused before any network traffic, the code was fine but the connection
/// failed, or the pairing succeeded. Only the last one may ever be shown as a success.
/// </remarks>
public static class PairingStepCodes
{
    /// <summary>No attempt has started.</summary>
    public const string HostIdle = "pair-host-idle";

    /// <summary>The listener is open and the countdown is running.</summary>
    public const string HostListening = "pair-host-listening";

    /// <summary>The peer connected and was accepted.</summary>
    public const string HostPaired = "pair-host-paired";

    /// <summary>The attempt failed; <c>Reason</c> carries the cause.</summary>
    public const string HostFailed = "pair-host-failed";

    /// <summary>The invitation expired before anyone connected.</summary>
    public const string HostExpired = "pair-host-expired";

    /// <summary>The user ended the attempt.</summary>
    public const string HostStopped = "pair-host-stopped";

    /// <summary>The guest paired successfully; the source list was only counted.</summary>
    public const string GuestPaired = "pair-guest-paired";

    /// <summary>The pasted code was refused before any network traffic; <c>Reason</c> is a <see cref="PairingCodeReasons"/> value.</summary>
    public const string GuestCodeRefused = "pair-guest-code-refused";

    /// <summary>The code was usable but the connection failed; <c>Reason</c> is a <see cref="PairingTransportReasons"/> value.</summary>
    public const string GuestFailed = "pair-guest-failed";

    /// <summary>Every step code this build can report.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        HostIdle,
        HostListening,
        HostPaired,
        HostFailed,
        HostExpired,
        HostStopped,
        GuestPaired,
        GuestCodeRefused,
        GuestFailed,
    ];
}

/// <summary>
/// What the guest can see of the peer's source list: either an envelope, or the reason there is none.
/// </summary>
/// <remarks>
/// The W6-3b transport carries the six-field invitation only — there is no source-list channel on the wire yet. The
/// envelope in this result therefore comes from the same local read the preview uses (the peer's exported
/// <c>sync-envelope.json</c>), and it is <b>counted, never applied</b>: pairing must not create, update, or delete a
/// single local source.
/// </remarks>
public sealed record PairingPeerEnvelopeResult(SourceSyncEnvelope? Envelope, string? Error)
{
    /// <summary>True when an envelope was read and its sources were counted.</summary>
    public bool HasEnvelope => Envelope is not null;

    /// <summary>How many sources the peer's envelope lists; zero when there is none.</summary>
    public int SourceCount => Envelope?.Sources.Count ?? 0;

    /// <summary>An envelope that was read successfully.</summary>
    public static PairingPeerEnvelopeResult Found(SourceSyncEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        return new PairingPeerEnvelopeResult(envelope, null);
    }

    /// <summary>No envelope is readable, with a sentence saying why.</summary>
    public static PairingPeerEnvelopeResult Missing(string error) => new(null, error);
}

/// <summary>Reads the peer's source envelope on demand; supplied by the App layer so this type stays IO-free.</summary>
public delegate PairingPeerEnvelopeResult PairingPeerEnvelopeReader();

/// <summary>One snapshot of the hosting side: a stable state, a machine code, and the sentence to show next.</summary>
/// <param name="State">The state at the moment of the snapshot.</param>
/// <param name="StepCode">One of <see cref="PairingStepCodes"/>.</param>
/// <param name="NextStep">The Chinese sentence to show the user.</param>
/// <param name="RemainingMilliseconds">Invitation lifetime left; zero once the attempt is over.</param>
/// <param name="FingerprintDisplay">The grouped certificate fingerprint, or empty when no invitation exists.</param>
/// <param name="Reason">The stable cause code for terminal states, or null.</param>
public sealed record PairingHostSnapshot(
    PairingHostState State,
    string StepCode,
    string NextStep,
    long RemainingMilliseconds,
    string FingerprintDisplay,
    string? Reason)
{
    /// <summary>True while the listener is open and the code is still valid.</summary>
    public bool IsListening => State == PairingHostState.Listening;

    /// <summary>True once nothing more will happen without the user starting again.</summary>
    public bool IsTerminal => State is PairingHostState.Paired or PairingHostState.Failed or PairingHostState.Expired or PairingHostState.Stopped;

    /// <summary>The countdown in whole seconds, rounded up so "1" is shown while a second is still usable.</summary>
    public long RemainingSeconds => RemainingMilliseconds <= 0 ? 0 : (RemainingMilliseconds + 999) / 1000;
}

/// <summary>The outcome of the joining side: paired or not, why, and what the peer was carrying.</summary>
/// <param name="Paired">True only when the pinned TLS connection completed and the peer payload verified.</param>
/// <param name="StepCode">One of <see cref="PairingStepCodes"/>.</param>
/// <param name="Message">The Chinese sentence to show; it never claims sources were imported.</param>
/// <param name="Reason">The stable cause code on failure, or null.</param>
/// <param name="PeerDeviceId">The peer's device id, once known.</param>
/// <param name="PeerFingerprintDisplay">The peer's grouped fingerprint, once known.</param>
/// <param name="PeerSourceCount">How many sources the peer's envelope lists; counted only.</param>
/// <param name="HasPeerEnvelope">True when a peer envelope was actually read.</param>
public sealed record PairingGuestResult(
    bool Paired,
    string StepCode,
    string Message,
    string? Reason,
    string? PeerDeviceId,
    string? PeerFingerprintDisplay,
    int PeerSourceCount,
    bool HasPeerEnvelope)
{
    /// <summary>The one-line summary a screen reader announces for a successful pairing.</summary>
    public string PeerSourceSummary => HasPeerEnvelope
        ? "已收到 " + PeerSourceCount + " 个来源的清单（未导入）。"
        : "没有读到来源清单（未导入）。";
}

/// <summary>
/// Drives the pairing handshake for both sides without touching a single UI type (W6-3c).
/// </summary>
/// <remarks>
/// <para>
/// The presenter owns the W6-3b listener on the hosting side and the pinned TLS client on the joining side, and
/// converts every outcome into (a) a stable machine code and (b) the sentence to show. That split is what makes the
/// page thin: <c>SyncPage</c> renders, this class decides.
/// </para>
/// <para>
/// Time is injected (<c>nowEpochMs</c> on every call) rather than read, so the countdown and the expiry boundary are
/// ordinary assertions instead of sleeps. The clock is also handed to the transport server, so the invitation the
/// peer receives expires on the same timeline the page is counting down.
/// </para>
/// <para>
/// Two invariants hold by construction, and the tests pin them:
/// </para>
/// <list type="bullet">
/// <item><description>
/// The listener really closes — on success, on failure, on expiry, on user stop, and on dispose. A pairing port that
/// lingers is a pairing port that can be probed.
/// </description></item>
/// <item><description>
/// The challenge never leaves in a log line or an exception message. <see cref="PairingCode"/> already redacts it in
/// every string it produces; this type never formats the payload, only its device id, its fingerprint display, and
/// the number of sources.
/// </description></item>
/// </list>
/// <para>
/// Pairing is not importing. A successful guest pairing reports how many sources the peer listed and stops there —
/// applying an envelope stays a separate, explicitly confirmed action (W6-4).
/// </para>
/// </remarks>
public sealed class PairingPresenter : IDisposable
{
    private readonly string _deviceId;
    private readonly PairingPeerEnvelopeReader? _envelopeReader;
    private readonly PairedDeviceRegistry? _registry;
    private readonly PairingSelfIdentity _identity;

    private PairingTransportServer? _server;
    private CancellationTokenSource? _acceptCancellation;
    private Task<PairingTransportResult>? _acceptTask;
    private PairingTransportResult? _settled;
    private PairingHostState _state = PairingHostState.Idle;
    private string? _reason;
    private string? _detail;
    private long _expiresAtEpochMs;
    private int _disposed;

    /// <summary>Creates a presenter for this installation.</summary>
    /// <param name="deviceId">The stable device identity carried in the invitation.</param>
    /// <param name="envelopeReader">
    /// Reads the peer's source envelope on demand. Optional: without it the guest side still pairs and still reports
    /// that no source list was read, which is the honest answer when nothing provides one.
    /// </param>
    /// <param name="registry">
    /// Remembers paired devices (W6-12). Optional, and deliberately so: without it the exchange stays one-way and
    /// the host cannot name the peer, which is exactly the pre-W6-12 behaviour. Supplying it turns on the optional
    /// peer-card step — both sides then exchange a small, self-declared card, the first fingerprint seen for a
    /// device is pinned, and a later mismatch refuses the pairing instead of silently re-pinning.
    /// </param>
    /// <exception cref="ArgumentException"><paramref name="deviceId"/> is blank.</exception>
    public PairingPresenter(
        string deviceId,
        PairingPeerEnvelopeReader? envelopeReader = null,
        PairedDeviceRegistry? registry = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A pairing presenter needs a device id.", nameof(deviceId));
        }

        _deviceId = deviceId.Trim();
        _envelopeReader = envelopeReader;
        _registry = registry;
        _identity = PairingSelfIdentity.Create(_deviceId);
    }

    /// <summary>The paired-device registry this presenter writes to, or null when cards are not exchanged.</summary>
    public PairedDeviceRegistry? Registry => _registry;

    /// <summary>This installation's own card (device id plus derived fingerprint); never contains a secret.</summary>
    public PairingPeerCard SelfCard => _identity.ToCard();

    /// <summary>This installation's device id, as carried in the invitation.</summary>
    public string DeviceId => _deviceId;

    /// <summary>The hosting state as of the last call.</summary>
    public PairingHostState State => _state;

    /// <summary>True while the listener is open and the code is still valid.</summary>
    public bool IsListening => _state == PairingHostState.Listening && _server is { IsStopped: false };

    /// <summary>The manual code to show or read out; empty when no invitation is live.</summary>
    /// <remarks>Deliberately not called <c>PairingCode</c>: that is the encoder type, and shadowing it here would
    /// make the property and the type indistinguishable at the call site.</remarks>
    public string PairingCodeText { get; private set; } = "";

    /// <summary>The grouped certificate fingerprint of the live invitation; empty when none is live.</summary>
    public string FingerprintDisplay { get; private set; } = "";

    /// <summary>The peer's device id, once a pairing attempt accepted one.</summary>
    public string? PeerDeviceId { get; private set; }

    /// <summary>The peer's grouped fingerprint, once a pairing attempt accepted one.</summary>
    public string? PeerFingerprintDisplay { get; private set; }

    /// <summary>The live invitation payload, or null. Exposed for the countdown, never for display.</summary>
    public PairingPayload? Invitation => _server?.Invitation;

    /// <summary>The loopback endpoint the listener is bound to, or null when it is closed.</summary>
    public IPEndPoint? ListenEndPoint => _server?.EndPoint;

    /// <summary>The stable cause of the last terminal state, or null.</summary>
    public string? LastReason => _reason;

    /// <summary>
    /// Opens a listener and produces a manual code, a fingerprint, and a countdown.
    /// </summary>
    /// <param name="nowEpochMs">The caller's clock; also the timeline the invitation expiry uses.</param>
    /// <remarks>
    /// Any previous attempt is closed first, so "start again" after a failure never leaves two listeners behind. A
    /// failure to bind is reported as <see cref="PairingStepCodes.HostFailed"/> with
    /// <see cref="PairingTransportReasons.ListenerFailed"/> — not as an exception the page would have to catch.
    /// </remarks>
    public PairingHostSnapshot StartHost(long nowEpochMs)
    {
        ThrowIfDisposed();
        CloseListener();
        ResetSession();

        PairingTransportServerOptions options = new()
        {
            DeviceId = _deviceId,
            Challenge = PairingChallenge.Create(),
            Clock = () => nowEpochMs,
            // Without a registry there is nobody to remember the peer, so the exchange stays one-way: the peer
            // learns the invitation and nothing else, exactly as before W6-12.
            PeerReplyTimeout = _registry is null ? null : TimeSpan.FromSeconds(5),
            SelfCard = _registry is null ? null : _identity.ToCard(),
        };

        PairingTransportServer server;
        try
        {
            server = PairingTransportServer.Start(options);
        }
        catch (SocketException ex)
        {
            return FailToStart(PairingTransportReasons.ListenerFailed, "监听端口绑定失败：" + ex.Message, nowEpochMs);
        }
        catch (IOException ex)
        {
            return FailToStart(PairingTransportReasons.ListenerFailed, "监听端口无法打开：" + ex.Message, nowEpochMs);
        }
        catch (InvalidOperationException ex)
        {
            return FailToStart(PairingTransportReasons.ListenerFailed, "配对监听无法启动：" + ex.Message, nowEpochMs);
        }

        return Adopt(server, nowEpochMs);
    }

    /// <summary>Advances the hosting side: reports what happened since the last poll.</summary>
    /// <param name="nowEpochMs">The caller's clock.</param>
    /// <remarks>
    /// Polling is the whole state machine — the page runs it on a timer and renders the snapshot. Once the attempt
    /// is terminal the listener is already closed by the time this returns.
    /// </remarks>
    public PairingHostSnapshot PollHost(long nowEpochMs)
    {
        ThrowIfDisposed();

        if (_state != PairingHostState.Listening)
        {
            return Snapshot(nowEpochMs);
        }

        if (_acceptTask is { IsCompleted: true } task)
        {
            PairingTransportResult result = task.Status == TaskStatus.RanToCompletion
                ? task.Result
                : PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
            _settled = result;

            if (result.Paired)
            {
                // result.Payload is the host's own invitation, so it must never be adopted as the peer's identity.
                // result.PeerPayload only exists when the optional card exchange ran, and it is self-declared text,
                // not a proof: the first card seen for a device is pinned exactly as a certificate would be.
                PairedDeviceRegistration? registration = null;
                if (result.PeerPayload is { } card)
                {
                    PeerDeviceId = card.DeviceId;
                    PeerFingerprintDisplay = DisplayFingerprintHex(card.DeviceFingerprint);
                    registration = _registry?.Register(PairedDeviceObservation.FromCard(card, nowEpochMs));
                }
                else
                {
                    PeerDeviceId = null;
                    PeerFingerprintDisplay = null;
                }

                if (registration is { IsMismatch: true })
                {
                    _state = PairingHostState.Failed;
                    _reason = registration.Reason;
                    _detail = "对端设备的指纹与首次配对时登记的不一致，已拒绝这次配对。";
                }
                else
                {
                    _state = PairingHostState.Paired;
                    _reason = null;
                    _detail = registration?.Device is { } device ? "已记住对端：" + device.DisplayNameOrId : null;
                }
            }
            else
            {
                _state = PairingHostState.Failed;
                _reason = result.Reason ?? PairingTransportReasons.ListenerFailed;
                _detail = PairingUiText.DescribeTransportFailure(_reason);
            }

            CloseListener();
            RetireInvitation();
        }
        else if (nowEpochMs >= _expiresAtEpochMs)
        {
            _state = PairingHostState.Expired;
            _reason = PairingTransportReasons.LifetimeExceeded;
            _detail = null;
            CloseListener();
            RetireInvitation();
        }

        return Snapshot(nowEpochMs);
    }

    /// <summary>Ends the attempt and closes the listener; a terminal state is left untouched.</summary>
    /// <param name="nowEpochMs">The caller's clock; the wall clock is used when omitted.</param>
    public PairingHostSnapshot StopHost(long? nowEpochMs = null)
    {
        ThrowIfDisposed();

        if (_state is PairingHostState.Listening or PairingHostState.Idle)
        {
            CloseListener();
            _state = PairingHostState.Stopped;
            _reason = null;
            _detail = null;
            // A stopped attempt has no live invitation: leaving the code on screen would invite the user to
            // copy a value that can no longer be accepted.
            PairingCodeText = "";
            FingerprintDisplay = "";
        }

        return Snapshot(nowEpochMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    /// <summary>Joins a pairing attempt with a pasted manual code.</summary>
    /// <param name="code">The text the user pasted; whitespace is tolerated.</param>
    /// <param name="nowEpochMs">The caller's clock, used for the invitation expiry check.</param>
    /// <param name="cancellationToken">Cancels the connection attempt.</param>
    /// <remarks>
    /// A refused code short-circuits before any socket is opened, so a mistyped code cannot be used to probe a host.
    /// The connection itself is pinned to the fingerprint inside the code and requires TLS 1.3 — a mismatch is a
    /// failure, never a fallback. On success the peer's envelope is read and counted; nothing is written.
    /// </remarks>
    public async Task<PairingGuestResult> AcceptGuestAsync(string? code, long nowEpochMs, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();

        PairingCodeResult decoded = PairingCode.Decode(code, nowEpochMs);
        if (!decoded.Succeeded)
        {
            string reason = decoded.Reason ?? PairingCodeReasons.PayloadInvalid;
            return new PairingGuestResult(
                Paired: false,
                StepCode: PairingStepCodes.GuestCodeRefused,
                Message: PairingUiText.DescribeCodeRejection(reason, decoded.Detail),
                Reason: reason,
                PeerDeviceId: null,
                PeerFingerprintDisplay: null,
                PeerSourceCount: 0,
                HasPeerEnvelope: false);
        }

        PairingPayload invitation = decoded.Payload!;
        if (!Uri.TryCreate(invitation.EphemeralUrl, UriKind.Absolute, out Uri? endpoint))
        {
            // bad-url belongs to the payload vocabulary, not to the transport vocabulary: the address was
            // rejected before any socket was opened.
            return GuestFailure(PairingReasons.BadUrl, PairingUiText.DescribeCodeRejection(PairingReasons.BadUrl, null), invitation);
        }

        PairingTransportResult transport;
        try
        {
            transport = await PairingTransportClient.ConnectAsync(
                new PairingTransportClientOptions
                {
                    Endpoint = endpoint,
                    ExpectedFingerprint = invitation.PublicKeyFingerprint,
                    // Only a presenter with a registry announces itself: an older build keeps the one-way wire.
                    Reply = _registry is null ? null : _identity.ToCard(),
                },
                cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return GuestFailure(PairingTransportReasons.PeerTimeout, "连接已取消，对端没有完成响应。", invitation);
        }
        catch (SocketException ex)
        {
            return GuestFailure(PairingTransportReasons.ListenerFailed, "无法连接到对端：" + ex.Message, invitation);
        }
        catch (IOException ex)
        {
            return GuestFailure(PairingTransportReasons.ListenerFailed, "连接中断：" + ex.Message, invitation);
        }
        catch (AuthenticationException ex)
        {
            return GuestFailure(PairingTransportReasons.TlsVersion, "TLS 握手失败，已中止：" + ex.Message, invitation);
        }

        if (!transport.Paired)
        {
            string reason = transport.Reason ?? PairingTransportReasons.PeerTimeout;
            return GuestFailure(reason, PairingUiText.DescribeTransportFailure(reason), invitation);
        }

        // Remembering the host needs the host's own card. The invitation's fingerprint is the ephemeral
        // certificate's and changes every pairing, so registering it would guarantee a false mismatch next time.
        if (_registry is not null && transport.PeerPayload is { } hostCard)
        {
            PairedDeviceRegistration registration =
                _registry.Register(PairedDeviceObservation.FromCard(hostCard, nowEpochMs));
            if (registration.IsMismatch)
            {
                string who = hostCard.DisplayName.Length > 0 ? hostCard.DisplayName : hostCard.DeviceId;
                return new PairingGuestResult(
                    Paired: false,
                    StepCode: PairingStepCodes.GuestFailed,
                    Message: "对端设备 " + who + " 的指纹与首次配对时登记的不一致，已中止配对。",
                    Reason: registration.Reason,
                    PeerDeviceId: hostCard.DeviceId,
                    PeerFingerprintDisplay: DisplayFingerprintHex(hostCard.DeviceFingerprint),
                    PeerSourceCount: 0,
                    HasPeerEnvelope: false);
            }
        }

        PairingPayload peerPayload = transport.Payload ?? invitation;
        PairingPeerEnvelopeResult peer = ReadPeerEnvelope();
        string message = peer.HasEnvelope
            ? "已收到 " + peer.SourceCount + " 个来源的清单（未导入）。"
            : "已与对端配对，但没有读到来源清单（未导入）：" + (peer.Error ?? "没有可读的信封。");

        return new PairingGuestResult(
            Paired: true,
            StepCode: PairingStepCodes.GuestPaired,
            Message: message,
            Reason: null,
            PeerDeviceId: peerPayload.DeviceId,
            PeerFingerprintDisplay: DisplayFingerprint(peerPayload),
            PeerSourceCount: peer.SourceCount,
            HasPeerEnvelope: peer.HasEnvelope);
    }

    /// <inheritdoc />
    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        CloseListener();
        _acceptTask = null;
        _settled = null;
    }

    private PairingHostSnapshot Adopt(PairingTransportServer server, long nowEpochMs)
    {
        _server = server;
        PairingPayload invitation = server.Invitation;
        _expiresAtEpochMs = invitation.ExpiresAtEpochMs;
        PairingCodeText = PairingCode.Encode(invitation);
        FingerprintDisplay = DisplayFingerprint(invitation) ?? "";
        _state = PairingHostState.Listening;
        _reason = null;
        _detail = null;
        PeerDeviceId = null;
        PeerFingerprintDisplay = null;

        CancellationTokenSource cancellation = new();
        _acceptCancellation = cancellation;
        _acceptTask = DrainAsync(server, cancellation.Token);
        return Snapshot(nowEpochMs);
    }

    private PairingHostSnapshot FailToStart(string reason, string detail, long nowEpochMs)
    {
        CloseListener();
        _state = PairingHostState.Failed;
        _reason = reason;
        _detail = detail;
        PairingCodeText = "";
        FingerprintDisplay = "";
        return Snapshot(nowEpochMs);
    }

    private PairingGuestResult GuestFailure(string reason, string message, PairingPayload invitation) => new(
        Paired: false,
        StepCode: PairingStepCodes.GuestFailed,
        Message: message,
        Reason: reason,
        PeerDeviceId: invitation.DeviceId,
        PeerFingerprintDisplay: DisplayFingerprint(invitation),
        PeerSourceCount: 0,
        HasPeerEnvelope: false);

    private PairingHostSnapshot Snapshot(long nowEpochMs)
    {
        long remaining = _state == PairingHostState.Listening ? Math.Max(0, _expiresAtEpochMs - nowEpochMs) : 0;
        return new PairingHostSnapshot(
            State: _state,
            StepCode: StepCodeFor(_state),
            NextStep: PairingUiText.DescribeHostNextStep(_state, _reason, _detail),
            RemainingMilliseconds: remaining,
            FingerprintDisplay: FingerprintDisplay,
            Reason: _reason);
    }

    private static string StepCodeFor(PairingHostState state) => state switch
    {
        PairingHostState.Listening => PairingStepCodes.HostListening,
        PairingHostState.Paired => PairingStepCodes.HostPaired,
        PairingHostState.Failed => PairingStepCodes.HostFailed,
        PairingHostState.Expired => PairingStepCodes.HostExpired,
        PairingHostState.Stopped => PairingStepCodes.HostStopped,
        _ => PairingStepCodes.HostIdle,
    };

    private static string? DisplayFingerprint(PairingPayload? payload)
    {
        if (payload is null)
        {
            return null;
        }

        try
        {
            return payload.ParseFingerprint().ToDisplayString();
        }
        catch (SyncContractException)
        {
            // A payload that reached this point already validated its shape; staying defensive here keeps a display
            // helper from ever being the thing that throws.
            return null;
        }
    }

    private static string? DisplayFingerprintHex(string? hex) =>
        PairingFingerprint.TryParse(hex, out PairingFingerprint fingerprint) ? fingerprint.ToDisplayString() : null;

    private PairingPeerEnvelopeResult ReadPeerEnvelope()
    {
        if (_envelopeReader is null)
        {
            return PairingPeerEnvelopeResult.Missing("本机没有配置来源清单读取器。");
        }

        return _envelopeReader() ?? PairingPeerEnvelopeResult.Missing("读取来源清单时没有返回结果。");
    }

    private void ResetSession()
    {
        _settled = null;
        _reason = null;
        _detail = null;
        _expiresAtEpochMs = 0;
        _state = PairingHostState.Idle;
        PairingCodeText = "";
        FingerprintDisplay = "";
        PeerDeviceId = null;
        PeerFingerprintDisplay = null;
    }

    /// <summary>Drops the invitation text a terminal attempt can no longer honour.</summary>
    /// <remarks>
    /// A stopped, expired, failed, or completed attempt has no live invitation, so leaving the code on screen would
    /// only invite the user to copy a value that can never be accepted again.
    /// </remarks>
    private void RetireInvitation()
    {
        PairingCodeText = "";
        FingerprintDisplay = "";
    }

    /// <summary>Closes the listener and cancels the pending accept; safe to call repeatedly.</summary>
    /// <remarks>
    /// The accept task is drained rather than awaited: it is what closes the socket, and awaiting it from a UI thread
    /// would block the very page that is trying to shut down.
    /// </remarks>
    private void CloseListener()
    {
        PairingTransportServer? server = _server;
        _server = null;

        CancellationTokenSource? cancellation = _acceptCancellation;
        _acceptCancellation = null;

        try
        {
            cancellation?.Cancel();
            server?.Stop();
            server?.Dispose();
        }
        catch (ObjectDisposedException)
        {
            // Already closed by the accept path; nothing left to release.
        }
        finally
        {
            cancellation?.Dispose();
        }
    }

    private static async Task<PairingTransportResult> DrainAsync(PairingTransportServer server, CancellationToken cancellationToken)
    {
        try
        {
            return await server.AcceptOneAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (ObjectDisposedException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (InvalidOperationException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (IOException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (SocketException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (AuthenticationException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
        }
    }

    private void ThrowIfDisposed()
    {
        if (Volatile.Read(ref _disposed) != 0)
        {
            throw new ObjectDisposedException(nameof(PairingPresenter));
        }
    }
}

/// <summary>
/// The Chinese text behind every pairing state and failure code (W6-3c).
/// </summary>
/// <remarks>
/// Two rules decide the wording. First, a state sentence always says what the user can do next, because a pairing
/// panel that only says "失败" is a dead end. Second, no sentence may imply that a pairing imported anything: the
/// strongest claim allowed is that a list was received and counted, and those sentences end with 「未导入」.
/// </remarks>
public static class PairingUiText
{
    /// <summary>The next-step sentence for a hosting state.</summary>
    public static string DescribeHostNextStep(PairingHostState state, string? reason, string? detail) => state switch
    {
        PairingHostState.Listening =>
            "把配对码发给对方，让对方在「配对设备」里粘贴后点「连接」。" + PairingPayload.LifetimeSeconds + " 秒内有效，结束后需要重新生成。",
        PairingHostState.Paired =>
            "已与对端建立可信连接（指纹一致）。来源清单只计数，不会自动导入；需要导入请走「预览合并」并逐项确认。",
        PairingHostState.Failed =>
            "配对未完成：" + (string.IsNullOrWhiteSpace(detail) ? DescribeTransportFailure(reason) : detail) + " 可以点「开始配对」重试。",
        PairingHostState.Expired =>
            "配对码已过期，监听已关闭。请点「开始配对」生成新的配对码。",
        PairingHostState.Stopped =>
            "已结束配对，监听已关闭。需要时请重新点「开始配对」。",
        _ => "还没有开始配对。点「开始配对」会在本机打开监听并生成一次性配对码。",
    };

    /// <summary>A one-line label for the current hosting state.</summary>
    public static string DescribeHostState(PairingHostState state) => state switch
    {
        PairingHostState.Listening => "等待对方连接",
        PairingHostState.Paired => "已配对",
        PairingHostState.Failed => "配对失败",
        PairingHostState.Expired => "配对码已过期",
        PairingHostState.Stopped => "已结束配对",
        _ => "未开始配对",
    };

    /// <summary>The sentence for a code refused before any network traffic.</summary>
    public static string DescribeCodeRejection(string? reason, string? detail)
    {
        if (!string.IsNullOrWhiteSpace(detail))
        {
            return detail;
        }

        return reason switch
        {
            PairingCodeReasons.Empty => "配对码是空的：请粘贴对方显示的配对码。",
            PairingCodeReasons.NotBase64Url => "配对码不是合法的 base64url 文本，请重新复制。",
            PairingCodeReasons.TooLong => "这段文本太长，不像是配对码。",
            PairingCodeReasons.Expired => "配对码已过期，请让对方重新生成。",
            PairingCodeReasons.UnsupportedVersion => "配对码来自更新版本的 MediaHub，本机暂不支持。",
            _ => "配对码里没有可读的配对信息，可能复制不完整。",
        };
    }

    /// <summary>The sentence for a transport-level failure, chosen by its stable reason code.</summary>
    public static string DescribeTransportFailure(string? reason) => reason switch
    {
        PairingTransportReasons.ListenerFailed => "连接被拒绝：对端没有在等待配对（监听可能已关闭或地址不可达）。",
        PairingTransportReasons.TlsVersion => "配对失败：对端没有使用 TLS 1.3，出于安全原因已中止。",
        PairingTransportReasons.FingerprintMismatch => "指纹不匹配：对端出示的证书与配对码里的指纹不一致，已中止。",
        PairingTransportReasons.PayloadInvalid => "对端返回的配对信息无法解析，已中止。",
        PairingTransportReasons.LifetimeExceeded => "配对码已过期，请让对方重新生成。",
        PairingTransportReasons.AlreadyPaired => "这段配对码已经用过了：一次配对只能成功一次。",
        PairingTransportReasons.PeerTimeout => "对端超时没有响应，请确认对方仍停在配对界面后重试。",
        null or "" => "配对失败，但没有给出原因。",
        _ => "配对失败（稳定码：" + reason + "）。",
    };
}

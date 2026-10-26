using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the cross-device pairing contract (ADR-S001 D6, ADR-S002) below the UI: the six-field payload, its
/// codec, the challenge and fingerprint primitives, and the state machine.
/// </summary>
/// <remarks>
/// <para>
/// Everything here is a plain function of its inputs — no socket, no file, no wall clock — so the 120 s window
/// and the one-shot rule are asserted with explicit timestamps rather than sleeps. Every refusal branch of the
/// contract has its own test, because "the QR code was refused, and for this reason" is what a user sees.
/// </para>
/// <para>
/// The "no secrets" rule is asserted structurally: the serialised document has exactly six named scalar fields
/// and <see cref="PairingPayload"/> exposes exactly six public properties. A word-list scan would be the wrong
/// test — a field named <c>canSendSecrets</c> would trip it while proving nothing (ADR-S001 D7).
/// </para>
/// </remarks>
public sealed class PairingContractTests
{
    /// <summary>2026-01-01T00:00:00Z. Fixed so every expiry assertion is arithmetic, not a race with the clock.</summary>
    private const long Now = 1_767_225_600_000L;

    private const string LocalUrl = "http://127.0.0.1:51234/";
    private const string PeerUrl = "http://127.0.0.1:52000/";

    // ---- vocabulary ----------------------------------------------------------------------

    /// <summary>
    /// The seven refusal codes are exactly the documented strings, lowercase and unpunctuated: the UI translates
    /// them, so a reworded code is a broken screen rather than a compile error.
    /// </summary>
    [Fact]
    public void PairingReasons_All_AreTheSevenDocumentedCodes()
    {
        string[] expected =
        [
            "unsupported-version",
            "bad-fingerprint",
            "bad-challenge",
            "bad-url",
            "expired",
            "challenge-replayed",
            "challenge-mismatch",
        ];

        Assert.Equal(expected, PairingReasons.All);

        Assert.All(PairingReasons.All, code =>
        {
            Assert.False(string.IsNullOrWhiteSpace(code));
            Assert.Equal(code.ToLowerInvariant(), code);
            Assert.False(code.Contains(':', StringComparison.Ordinal));
            Assert.False(code.Contains(' ', StringComparison.Ordinal));
        });
    }

    /// <summary>The refusal codes, as theory data.</summary>
    public static TheoryData<string> FailureCodes()
    {
        var data = new TheoryData<string>();
        foreach (string code in PairingReasons.All)
        {
            data.Add(code);
        }

        return data;
    }

    /// <summary>Every refusal code survives a trip through the state machine unchanged.</summary>
    /// <param name="code">One of <see cref="PairingReasons.All"/>.</param>
    [Theory]
    [MemberData(nameof(FailureCodes))]
    public void PairingSession_Fail_KeepsTheStableReason(string code)
    {
        PairingSession session = PairingSession.Start("device-a", Now);

        Assert.Equal(PairingState.Failed, session.Fail(code, Now));

        Assert.Equal(code, session.LastReason);
        Assert.True(session.ToString().Contains(code, StringComparison.Ordinal));
    }

    // ---- payload and codec ---------------------------------------------------------------

    /// <summary>
    /// The wire payload is exactly six scalar fields with the documented names. This is the whole
    /// "no passwords, no tokens in the QR" rule expressed as a shape: there is no property that could carry one,
    /// so the writer cannot smuggle one even by accident.
    /// </summary>
    [Fact]
    public void PairingPayloadCodec_WritesExactlyTheSixContractFields()
    {
        string json = PairingPayloadCodec.Serialize(Payload());

        using JsonDocument document = JsonDocument.Parse(json);
        Assert.Equal(JsonValueKind.Object, document.RootElement.ValueKind);

        string[] expected =
        [
            "schemaVersion",
            "deviceId",
            "ephemeralUrl",
            "challenge",
            "publicKeyFingerprint",
            "expiresAtEpochMs",
        ];
        Assert.Equal(expected, document.RootElement.EnumerateObject().Select(property => property.Name).ToArray());

        // Scalar only: no nested object or array, so there is nowhere for a credential-shaped blob to hide.
        JsonValueKind[] scalars = [JsonValueKind.Number, JsonValueKind.String];
        Assert.All(
            document.RootElement.EnumerateObject(),
            property => Assert.Contains(property.Value.ValueKind, scalars));

        // And the record itself exposes the same six members — an allow-list, not a scan for bad words.
        string[] members = typeof(PairingPayload).GetProperties().Select(property => property.Name).ToArray();
        Assert.Equal(6, members.Length);
        Assert.All(
            new[] { "SchemaVersion", "DeviceId", "EphemeralUrl", "Challenge", "PublicKeyFingerprint", "ExpiresAtEpochMs" },
            member => Assert.Contains(member, members));
    }

    /// <summary>A payload survives serialise/parse without losing a value.</summary>
    [Fact]
    public void PairingPayloadCodec_RoundTripsEveryField()
    {
        PairingPayload payload = Payload(deviceId: "device-with-a-long-id", url: "https://10.0.0.5:51234/pairing");

        PairingPayload parsed = PairingPayloadCodec.Deserialize(PairingPayloadCodec.Serialize(payload));

        Assert.Equal(payload, parsed);
        Assert.Equal(payload.Challenge, parsed.Challenge);
        Assert.Equal(payload.PublicKeyFingerprint, parsed.PublicKeyFingerprint);
        Assert.Equal(payload.ExpiresAtEpochMs, parsed.ExpiresAtEpochMs);
    }

    /// <summary>
    /// Unknown properties are ignored on read — a newer sender must not break an older reader — and the writer
    /// never puts them back, so an injected <c>password</c> dies at the first hop instead of travelling on.
    /// </summary>
    [Fact]
    public void PairingPayloadCodec_IgnoresUnknownPropertiesAndNeverWritesThemBack()
    {
        JsonObject json = PayloadJson();
        json["password"] = "hunter2";
        json["token"] = "opaque";

        PairingPayload payload = PairingPayloadCodec.Deserialize(json.ToJsonString());
        string reserialized = PairingPayloadCodec.Serialize(payload);

        Assert.False(reserialized.Contains("hunter2", StringComparison.Ordinal));
        Assert.False(reserialized.Contains("password", StringComparison.Ordinal));
        Assert.False(reserialized.Contains("opaque", StringComparison.Ordinal));
    }

    /// <summary>A schema this build does not implement is refused, not guessed at.</summary>
    /// <param name="schemaVersion">A version other than the one this build writes.</param>
    [Theory]
    [InlineData(0)]
    [InlineData(2)]
    [InlineData(99)]
    public void PairingPayloadCodec_RejectsUnsupportedVersion(int schemaVersion)
    {
        JsonObject json = PayloadJson();
        json["schemaVersion"] = schemaVersion;

        Assert.Equal(
            PairingReasons.UnsupportedVersion,
            ReasonOf(() => PairingPayloadCodec.Deserialize(json.ToJsonString())));
    }

    /// <summary>Text that is not a JSON object is not a pairing payload.</summary>
    /// <param name="json">A document that cannot be read as a pairing payload.</param>
    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("{")]
    [InlineData("[]")]
    [InlineData("\"device-a\"")]
    [InlineData("null")]
    public void PairingPayloadCodec_RejectsMalformedJson(string json)
    {
        Assert.Equal(PairingReasons.MalformedJson, ReasonOf(() => PairingPayloadCodec.Deserialize(json)));
    }

    /// <summary>Every field of the payload is required; a half-built payload must not reach a comparison.</summary>
    /// <param name="field">The field to drop.</param>
    [Theory]
    [InlineData("schemaVersion")]
    [InlineData("deviceId")]
    [InlineData("ephemeralUrl")]
    [InlineData("challenge")]
    [InlineData("publicKeyFingerprint")]
    [InlineData("expiresAtEpochMs")]
    public void PairingPayloadCodec_RejectsMissingRequiredField(string field)
    {
        JsonObject json = PayloadJson();
        Assert.True(json.Remove(field));

        Assert.Equal(
            "missing-field:" + field,
            ReasonOf(() => PairingPayloadCodec.Deserialize(json.ToJsonString())));
    }

    /// <summary>
    /// The address is a plain http(s) URL. Credentials in the user-info, and anything in a query or fragment,
    /// are refused: that is exactly how a token would be smuggled past D6 while looking like a normal link.
    /// </summary>
    /// <param name="url">A value that must not be accepted.</param>
    [Theory]
    [InlineData("http://user:password@127.0.0.1:51234/")]
    [InlineData("ftp://127.0.0.1:51234/")]
    [InlineData("file:///C:/pairing")]
    [InlineData("/pairing")]
    [InlineData("127.0.0.1:51234")]
    [InlineData("http:///pairing")]
    [InlineData("http://127.0.0.1:51234/?token=abc")]
    [InlineData("http://127.0.0.1:51234/#token")]
    public void PairingPayloadCodec_RejectsBadUrl(string url)
    {
        JsonObject json = PayloadJson();
        json["ephemeralUrl"] = url;

        Assert.Equal(PairingReasons.BadUrl, ReasonOf(() => PairingPayloadCodec.Deserialize(json.ToJsonString())));
    }

    /// <summary>A plain private-network address is what the invitation is for, so it must be accepted.</summary>
    /// <param name="url">An acceptable ephemeral address.</param>
    [Theory]
    [InlineData("http://127.0.0.1:51234/")]
    [InlineData("http://192.168.1.20:51234/")]
    [InlineData("https://10.0.0.5:51234/pairing")]
    public void PairingPayloadCodec_AcceptsPlainPrivateAddresses(string url)
    {
        JsonObject json = PayloadJson();
        json["ephemeralUrl"] = url;

        Assert.Equal(url, PairingPayloadCodec.Deserialize(json.ToJsonString()).EphemeralUrl);
    }

    /// <summary>
    /// The challenge is exactly thirty-two bytes in canonical base64. A second spelling of the same bytes is
    /// refused too, or a ledger would hold two keys for one nonce and let a screenshot through.
    /// </summary>
    [Fact]
    public void PairingPayloadCodec_RejectsBadChallenge()
    {
        Assert.Equal(
            PairingReasons.BadChallenge,
            ReasonOfWithChallenge("not-base64!"));
        Assert.Equal(
            PairingReasons.BadChallenge,
            ReasonOfWithChallenge(Convert.ToBase64String(new byte[31])));
        Assert.Equal(
            PairingReasons.BadChallenge,
            ReasonOfWithChallenge(Convert.ToBase64String(new byte[33])));

        // Same bytes, second spelling (base64 decoders ignore whitespace): not canonical, so not accepted.
        Assert.Equal(
            PairingReasons.BadChallenge,
            ReasonOfWithChallenge(Challenge().Base64.Insert(4, " ")));
    }

    /// <summary>The fingerprint is a SHA-256 digest in hex, sixty-four characters, nothing else.</summary>
    [Fact]
    public void PairingPayloadCodec_RejectsBadFingerprint()
    {
        Assert.Equal(PairingReasons.BadFingerprint, ReasonOfWithFingerprint(new string('2', 63)));
        Assert.Equal(PairingReasons.BadFingerprint, ReasonOfWithFingerprint(new string('2', 65)));
        Assert.Equal(PairingReasons.BadFingerprint, ReasonOfWithFingerprint(new string('z', 64)));
        Assert.Equal(PairingReasons.BadFingerprint, ReasonOfWithFingerprint("0x" + new string('a', 62)));
    }

    /// <summary>
    /// Hex case is an input detail: an uppercase fingerprint is accepted and normalised, so two spellings of the
    /// same key are one value and serialise identically.
    /// </summary>
    [Fact]
    public void PairingPayloadCodec_NormalisesUppercaseFingerprintToLowercase()
    {
        JsonObject json = PayloadJson();
        json["publicKeyFingerprint"] = new string('A', 64);

        PairingPayload payload = PairingPayloadCodec.Deserialize(json.ToJsonString());

        Assert.Equal(new string('a', 64), payload.PublicKeyFingerprint);
        Assert.True(PairingPayloadCodec.Serialize(payload).Contains(new string('a', 64), StringComparison.Ordinal));
    }

    /// <summary>The invitation window is 120 s (ADR-S001 D6), written as a constant rather than a literal.</summary>
    [Fact]
    public void PairingPayload_Lifetime_IsOneHundredTwentySeconds()
    {
        Assert.Equal(120, PairingPayload.LifetimeSeconds);
        Assert.Equal(120_000L, PairingPayload.LifetimeMilliseconds);

        PairingPayload payload = PairingPayload.Create("device-a", LocalUrl, Challenge(), Fingerprint(), Now);

        Assert.Equal(Now + 120_000L, payload.ExpiresAtEpochMs);
        Assert.Equal(120_000L, payload.RemainingMilliseconds(Now));
        Assert.Equal(1L, payload.RemainingMilliseconds(Now + 119_999L));
        Assert.Equal(0L, payload.RemainingMilliseconds(Now + 999_999L));
    }

    /// <summary>Expiry is inclusive at the boundary: the invitation dies on the millisecond it names.</summary>
    [Fact]
    public void PairingPayload_ValidateFor_RejectsTheExpiryMillisecondAndEverythingAfterIt()
    {
        PairingPayload payload = Payload();
        long expiry = payload.ExpiresAtEpochMs;

        payload.ValidateFor(expiry - 1);

        Assert.Equal(PairingReasons.Expired, ReasonOf(() => payload.ValidateFor(expiry)));
        Assert.Equal(PairingReasons.Expired, ReasonOf(() => payload.ValidateFor(expiry + 1)));
    }

    // ---- challenge and fingerprint primitives --------------------------------------------

    /// <summary>A fresh challenge is 256 bits of CSPRNG output, and it never ends up in a log line.</summary>
    [Fact]
    public void PairingChallenge_Create_DrawsThirtyTwoRandomBytes()
    {
        PairingChallenge first = PairingChallenge.Create();
        PairingChallenge second = PairingChallenge.Create();

        Assert.Equal(32, PairingChallenge.ByteLength);
        Assert.Equal(32, first.Bytes.Length);
        Assert.Equal(44, first.Base64.Length);
        Assert.NotEqual(first, second);
        Assert.False(first.ToString().Contains(first.Base64, StringComparison.Ordinal));
    }

    /// <summary>Parsing accepts exactly the canonical spelling and refuses every near-miss.</summary>
    [Fact]
    public void PairingChallenge_TryParse_RequiresCanonicalThirtyTwoByteBase64()
    {
        PairingChallenge challenge = Challenge(0x11);

        Assert.True(PairingChallenge.TryParse(challenge.Base64, out PairingChallenge parsed));
        Assert.Equal(challenge, parsed);

        Assert.False(PairingChallenge.TryParse(Convert.ToBase64String(new byte[31]), out _));
        Assert.False(PairingChallenge.TryParse(Convert.ToBase64String(new byte[33]), out _));
        Assert.False(PairingChallenge.TryParse("not base64!", out _));
        Assert.False(PairingChallenge.TryParse(null, out _));

        // Whitespace inside base64 decodes to the same bytes but re-encodes differently: not canonical.
        string spaced = challenge.Base64.Insert(4, " ");
        Assert.False(PairingChallenge.TryParse(spaced, out _));
        Assert.Equal(PairingReasons.BadChallenge, ReasonOf(() => PairingChallenge.Parse(spaced)));
    }

    /// <summary>Comparison is content-only, and an uninitialised challenge never matches.</summary>
    [Fact]
    public void PairingChallenge_Matches_ComparesContentAndRejectsDefault()
    {
        Assert.True(Challenge(0x11).Matches(Challenge(0x11)));
        Assert.False(Challenge(0x11).Matches(Challenge(0x12)));
        Assert.False(default(PairingChallenge).Matches(Challenge(0x11)));
        Assert.False(Challenge(0x11).Matches(default));
    }

    /// <summary>The fingerprint is 64 lowercase hex characters, displayed in groups of four for the human check.</summary>
    [Fact]
    public void PairingFingerprint_FromBytes_IsLowercaseHexAndDisplaysGrouped()
    {
        PairingFingerprint fingerprint = Fingerprint(0x2A);

        Assert.Equal(64, PairingFingerprint.HexLength);
        Assert.Equal(64, fingerprint.Hex.Length);
        Assert.Equal(fingerprint.Hex.ToLowerInvariant(), fingerprint.Hex);
        Assert.Equal((byte)0x2A, fingerprint.Bytes.Span[0]);

        string display = fingerprint.ToDisplayString();
        Assert.Equal(79, display.Length);
        Assert.Equal(16, display.Split(' ').Length);
        Assert.Equal(display.ToUpperInvariant(), display);
    }

    /// <summary>Parsing normalises case; anything that is not 32 bytes in hex is refused.</summary>
    [Fact]
    public void PairingFingerprint_TryParse_NormalisesCaseAndRejectsNearMisses()
    {
        Assert.True(PairingFingerprint.TryParse(new string('B', 64), out PairingFingerprint parsed));
        Assert.Equal(new string('b', 64), parsed.Hex);

        Assert.False(PairingFingerprint.TryParse(new string('b', 63), out _));
        Assert.False(PairingFingerprint.TryParse(new string('b', 65), out _));
        Assert.False(PairingFingerprint.TryParse(null, out _));
        Assert.Equal(PairingReasons.BadFingerprint, ReasonOf(() => PairingFingerprint.Parse(new string('b', 63))));
    }

    /// <summary>Two fingerprints are equal exactly when their digests are; empty never matches.</summary>
    [Fact]
    public void PairingFingerprint_Matches_ComparesContentAndRejectsDefault()
    {
        Assert.True(Fingerprint(0x2A).Matches(Fingerprint(0x2A)));
        Assert.False(Fingerprint(0x2A).Matches(Fingerprint(0x2B)));
        Assert.False(default(PairingFingerprint).Matches(Fingerprint(0x2A)));
        Assert.False(Fingerprint(0x2A).Matches(default));
    }

    /// <summary>A challenge is spendable exactly once — that is what makes the QR single-use.</summary>
    [Fact]
    public void PairingChallengeLedger_SpendsAChallengeExactlyOnce()
    {
        var ledger = new PairingChallengeLedger();
        PairingChallenge challenge = Challenge(0x33);

        Assert.False(ledger.IsConsumed(challenge));
        Assert.True(ledger.TryConsume(challenge));
        Assert.False(ledger.TryConsume(challenge));
        Assert.True(ledger.IsConsumed(challenge));
        Assert.Equal(1, ledger.Count);

        ledger.Clear();
        Assert.Equal(0, ledger.Count);
        Assert.False(ledger.IsConsumed(challenge));
    }

    // ---- state machine -------------------------------------------------------------------

    /// <summary>A started attempt has done nothing: no history, no invitation, no peer.</summary>
    [Fact]
    public void PairingSession_Start_IsCreatedWithoutHistory()
    {
        PairingSession session = PairingSession.Start("device-a", Now);

        Assert.Equal(PairingState.Created, session.State);
        Assert.Empty(session.History);
        Assert.Equal("", session.LastReason);
        Assert.Null(session.Invitation);
        Assert.Null(session.RemoteDeviceId);
        Assert.Null(session.RemoteFingerprint);
        Assert.True(session.ToString().Contains("Created", StringComparison.Ordinal));

        Assert.Equal("missing-field:deviceId", ReasonOf(() => PairingSession.Start("  ", Now)));
    }

    /// <summary>Issuing an invitation publishes the six-field payload and records the transition.</summary>
    [Fact]
    public void PairingSession_IssueChallenge_PublishesTheInvitation()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        PairingFingerprint fingerprint = Fingerprint(0x2A);

        Assert.Equal(PairingState.ChallengeIssued, session.IssueChallenge(LocalUrl, fingerprint, Now));

        PairingPayload invitation = Assert.IsType<PairingPayload>(session.Invitation);
        Assert.Equal("device-a", invitation.DeviceId);
        Assert.Equal(LocalUrl, invitation.EphemeralUrl);
        Assert.Equal(Now + PairingPayload.LifetimeMilliseconds, invitation.ExpiresAtEpochMs);
        Assert.Equal(32, invitation.ParseChallenge().Bytes.Length);
        Assert.Equal(fingerprint, invitation.ParseFingerprint());
        Assert.Equal(Now + PairingPayload.LifetimeMilliseconds, session.ExpiresAtEpochMs);
        Assert.Equal(fingerprint, session.LocalFingerprint!.Value);

        PairingTransition transition = Assert.Single(session.History);
        Assert.Equal(PairingState.Created, transition.From);
        Assert.Equal(PairingState.ChallengeIssued, transition.To);
        Assert.Equal("challenge-issued", transition.Reason);
        Assert.Equal(Now, transition.AtEpochMs);
    }

    /// <summary>An invitation is issued once; a second call is a caller bug, not a silent second window.</summary>
    [Fact]
    public void PairingSession_IssueChallenge_TwiceIsACallerBug()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(), Now);

        Assert.Throws<InvalidOperationException>(() => session.IssueChallenge(LocalUrl, Fingerprint(), Now));
        Assert.Single(session.History);
    }

    /// <summary>The scanning side: accepting a peer's invitation records its identity and waits for the user.</summary>
    [Fact]
    public void PairingSession_ScanningSide_AcceptsThePeerInvitationAndWaits()
    {
        var ledger = new PairingChallengeLedger();
        PairingSession session = PairingSession.Start("device-a", Now, ledger);
        PairingPayload peerInvitation = Payload(deviceId: "device-b", challengeFill: 0x22, fingerprintFill: 0x3C);

        Assert.Equal(PairingState.AwaitingPeer, session.AcceptPeerPayload(peerInvitation, Now + 1_000));

        Assert.Equal("device-b", session.RemoteDeviceId);
        Assert.Equal(Fingerprint(0x3C), session.RemoteFingerprint!.Value);
        Assert.Equal(peerInvitation.ExpiresAtEpochMs, session.ExpiresAtEpochMs);
        Assert.Equal("peer-invitation-accepted", session.LastReason);
        Assert.True(ledger.IsConsumed(Challenge(0x22)));

        // The answer echoes their challenge and carries our own identity — the point of the round trip.
        PairingPayload response = session.ComposeResponse(PeerUrl, Fingerprint(0x2A), Now + 2_000);
        Assert.Equal("device-a", response.DeviceId);
        Assert.Equal(peerInvitation.Challenge, response.Challenge);
        Assert.Equal(Now + 2_000L + PairingPayload.LifetimeMilliseconds, response.ExpiresAtEpochMs);
        Assert.Equal(Fingerprint(0x2A), response.ParseFingerprint());
    }

    /// <summary>An invitation used after its window closes is refused with the reason the UI shows.</summary>
    [Fact]
    public void PairingSession_AcceptPeerPayload_RefusesAStaleInvitation()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        PairingPayload peerInvitation = Payload(deviceId: "device-b");

        Assert.Equal(
            PairingState.Failed,
            session.AcceptPeerPayload(peerInvitation, Now + PairingPayload.LifetimeMilliseconds));

        Assert.Equal(PairingReasons.Expired, session.LastReason);
        Assert.Null(session.RemoteDeviceId);
    }

    /// <summary>
    /// A photographed invitation is worth nothing: the same challenge, presented to a second attempt, is
    /// refused by the shared ledger.
    /// </summary>
    [Fact]
    public void PairingSession_AcceptPeerPayload_RefusesAReplayedChallenge()
    {
        var ledger = new PairingChallengeLedger();
        PairingPayload peerInvitation = Payload(deviceId: "device-b", challengeFill: 0x44);

        PairingSession first = PairingSession.Start("device-a", Now, ledger);
        Assert.Equal(PairingState.AwaitingPeer, first.AcceptPeerPayload(peerInvitation, Now + 1_000));

        PairingSession second = PairingSession.Start("device-a", Now + 2_000, ledger);
        Assert.Equal(PairingState.Failed, second.AcceptPeerPayload(peerInvitation, Now + 2_000));

        Assert.Equal(PairingReasons.ChallengeReplayed, second.LastReason);
        Assert.Null(second.RemoteDeviceId);
    }

    /// <summary>The answer to an invitation must echo this device's challenge, or it is answering someone else.</summary>
    [Fact]
    public void PairingSession_AcceptPeerPayload_RefusesAnAnswerToADifferentChallenge()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        PairingPayload wrongAnswer = Payload(deviceId: "device-b", challengeFill: 0x55);

        Assert.Equal(PairingState.Failed, session.AcceptPeerPayload(wrongAnswer, Now + 1_000));

        Assert.Equal(PairingReasons.ChallengeMismatch, session.LastReason);
        Assert.Equal(PairingState.ChallengeIssued, session.History[^1].From);
        Assert.Null(session.RemoteDeviceId);
    }

    /// <summary>The issuing side: the echoed challenge is accepted and the peer's identity is recorded.</summary>
    [Fact]
    public void PairingSession_IssuingSide_AcceptsTheEchoedAnswer()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        PairingFingerprint localFingerprint = Fingerprint(0x2A);
        session.IssueChallenge(LocalUrl, localFingerprint, Now);

        PairingPayload answer = PairingPayload.Create(
            "device-b",
            PeerUrl,
            session.Challenge!.Value,
            Fingerprint(0x3C),
            Now + 1_000);

        Assert.Equal(PairingState.AwaitingPeer, session.AcceptPeerPayload(answer, Now + 1_000));

        Assert.Equal("device-b", session.RemoteDeviceId);
        Assert.Equal(Fingerprint(0x3C), session.RemoteFingerprint!.Value);
        Assert.Equal(localFingerprint, session.LocalFingerprint!.Value);
        Assert.Equal("peer-response-accepted", session.LastReason);

        // The peer's own, later expiry must not extend the window we advertised.
        Assert.Equal(Now + PairingPayload.LifetimeMilliseconds, session.ExpiresAtEpochMs);
    }

    /// <summary>Confirming is what pairs, and it only works once both sides have exchanged payloads.</summary>
    [Fact]
    public void PairingSession_Confirm_PairsOnlyAfterThePayloadsAreExchanged()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        Assert.Throws<InvalidOperationException>(() => session.Confirm(Now));

        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        Assert.Throws<InvalidOperationException>(() => session.Confirm(Now));

        session.AcceptPeerPayload(
            PairingPayload.Create("device-b", PeerUrl, session.Challenge!.Value, Fingerprint(0x3C), Now + 1_000),
            Now + 1_000);

        Assert.Equal(PairingState.Paired, session.Confirm(Now + 1_500));
        Assert.Equal(PairingSession.PairedReason, session.LastReason);
        Assert.Equal(3, session.History.Count);
    }

    /// <summary>An invitation that dies while the confirmation is on screen must not pair.</summary>
    [Fact]
    public void PairingSession_Confirm_AfterTheWindowClosesFailsWithExpired()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        session.AcceptPeerPayload(
            PairingPayload.Create("device-b", PeerUrl, session.Challenge!.Value, Fingerprint(0x3C), Now + 1_000),
            Now + 1_000);

        Assert.Equal(
            PairingState.Failed,
            session.Confirm(Now + PairingPayload.LifetimeMilliseconds));

        Assert.Equal(PairingReasons.Expired, session.LastReason);
    }

    /// <summary>Closing is idempotent: a second close changes neither the state nor the history.</summary>
    [Fact]
    public void PairingSession_Close_IsIdempotent()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        session.Close(Now + 1_000);

        Assert.Equal(PairingState.Closed, session.State);
        int recorded = session.History.Count;

        Assert.Equal(PairingState.Closed, session.Close(Now + 2_000));
        Assert.Equal(recorded, session.History.Count);
        Assert.Equal(PairingSession.ClosedReason, session.LastReason);
    }

    /// <summary>A failed attempt is terminal: it can be closed, but not confirmed, reused, or failed again.</summary>
    [Fact]
    public void PairingSession_FailedAttempt_RefusesFurtherWork()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        session.Fail(PairingReasons.ChallengeMismatch, Now + 1_000);

        Assert.Equal(PairingState.Failed, session.State);
        Assert.Throws<InvalidOperationException>(() => session.Confirm(Now + 2_000));
        Assert.Throws<InvalidOperationException>(() => session.Fail(PairingReasons.Expired, Now + 2_000));
        Assert.Throws<InvalidOperationException>(
            () => session.AcceptPeerPayload(Payload(deviceId: "device-b"), Now + 2_000));

        Assert.Equal(PairingState.Closed, session.Close(Now + 3_000));
    }

    /// <summary>A response cannot be composed before a peer invitation has actually been accepted.</summary>
    [Fact]
    public void PairingSession_ComposeResponse_RequiresAPeerInvitation()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        Assert.Throws<InvalidOperationException>(() => session.ComposeResponse(PeerUrl, Fingerprint(0x2A), Now));

        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        Assert.Throws<InvalidOperationException>(() => session.ComposeResponse(PeerUrl, Fingerprint(0x2A), Now + 1_000));
    }

    /// <summary>Every step of a happy path is readable after the fact: state, reason, and the caller's clock.</summary>
    [Fact]
    public void PairingSession_History_RecordsEveryTransition()
    {
        PairingSession session = PairingSession.Start("device-a", Now);
        session.IssueChallenge(LocalUrl, Fingerprint(0x2A), Now);
        session.AcceptPeerPayload(
            PairingPayload.Create("device-b", PeerUrl, session.Challenge!.Value, Fingerprint(0x3C), Now + 1_000),
            Now + 1_000);
        session.Confirm(Now + 1_500);
        session.Close(Now + 2_000);

        (PairingState From, PairingState To, string Reason, long At)[] expected =
        [
            (PairingState.Created, PairingState.ChallengeIssued, "challenge-issued", Now),
            (PairingState.ChallengeIssued, PairingState.AwaitingPeer, "peer-response-accepted", Now + 1_000),
            (PairingState.AwaitingPeer, PairingState.Paired, PairingSession.PairedReason, Now + 1_500),
            (PairingState.Paired, PairingState.Closed, PairingSession.ClosedReason, Now + 2_000),
        ];

        Assert.Equal(
            expected,
            session.History.Select(transition => (transition.From, transition.To, transition.Reason, transition.AtEpochMs)));
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static string ReasonOf(Action action) => Assert.Throws<SyncContractException>(action).Reason;

    private static string ReasonOfWithChallenge(string challenge)
    {
        JsonObject json = PayloadJson();
        json["challenge"] = challenge;
        return ReasonOf(() => PairingPayloadCodec.Deserialize(json.ToJsonString()));
    }

    private static string ReasonOfWithFingerprint(string fingerprint)
    {
        JsonObject json = PayloadJson();
        json["publicKeyFingerprint"] = fingerprint;
        return ReasonOf(() => PairingPayloadCodec.Deserialize(json.ToJsonString()));
    }

    private static PairingPayload Payload(
        string deviceId = "device-a",
        string url = LocalUrl,
        byte challengeFill = 0x11,
        byte fingerprintFill = 0x2A)
        => PairingPayload.Create(deviceId, url, Challenge(challengeFill), Fingerprint(fingerprintFill), Now);

    private static JsonObject PayloadJson() => new()
    {
        ["schemaVersion"] = PairingPayload.CurrentSchemaVersion,
        ["deviceId"] = "device-a",
        ["ephemeralUrl"] = LocalUrl,
        ["challenge"] = Challenge().Base64,
        ["publicKeyFingerprint"] = Fingerprint().Hex,
        ["expiresAtEpochMs"] = Now + PairingPayload.LifetimeMilliseconds,
    };

    private static PairingChallenge Challenge(byte fill = 0x11)
    {
        byte[] bytes = new byte[PairingChallenge.ByteLength];
        Array.Fill(bytes, fill);
        return PairingChallenge.FromBytes(bytes);
    }

    private static PairingFingerprint Fingerprint(byte fill = 0x2A)
    {
        byte[] digest = new byte[32];
        Array.Fill(digest, fill);
        return PairingFingerprint.FromBytes(digest);
    }
}

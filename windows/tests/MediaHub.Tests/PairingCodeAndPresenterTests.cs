using System.Net;
using System.Net.Sockets;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-3c: the manual pairing code and the UI-free presenter that drives a two-device handshake.
/// </summary>
/// <remarks>
/// <para>
/// These tests pin three things the pairing screen depends on but cannot show: that a code is exactly reversible and
/// refuses everything else with a stable reason, that the listener really closes on every terminal path (so the port
/// can be reused and cannot be probed afterwards), and that no wording in this feature may ever imply that pairing
/// imported a source.
/// </para>
/// <para>
/// Time is always passed in. No test sleeps to reach an expiry, and the two loopback tests are the only ones that
/// touch a socket — everything else is pure.
/// </para>
/// </remarks>
public sealed class PairingCodeAndPresenterTests
{
    /// <summary>A fixed instant so expiry arithmetic is exact rather than approximately now.</summary>
    private const long FixedNow = 1_700_000_000_000;

    // ---- code -----------------------------------------------------------------

    [Fact]
    public void Code_RoundTripsEveryField()
    {
        PairingPayload payload = Payload(FixedNow);
        string code = PairingCode.Encode(payload);

        PairingCodeResult result = PairingCode.Decode(code, FixedNow);

        Assert.True(result.Succeeded);
        Assert.Null(result.Reason);
        Assert.Equal(payload.DeviceId, result.Payload!.DeviceId);
        Assert.Equal(payload.EphemeralUrl, result.Payload.EphemeralUrl);
        Assert.Equal(payload.Challenge, result.Payload.Challenge);
        Assert.Equal(payload.PublicKeyFingerprint, result.Payload.PublicKeyFingerprint);
        Assert.Equal(payload.ExpiresAtEpochMs, result.Payload.ExpiresAtEpochMs);
    }

    [Fact]
    public void Code_UsesOnlyBase64UrlCharactersAndNoPadding()
    {
        string code = PairingCode.Encode(Payload(FixedNow));

        Assert.NotEmpty(code);
        Assert.All(code, character => Assert.True(
            char.IsAsciiLetterOrDigit(character) || character is '-' or '_',
            "unexpected character in a pairing code: " + character));
        Assert.False(code.Contains('=', StringComparison.Ordinal));
        Assert.False(code.Contains('+', StringComparison.Ordinal));
        Assert.False(code.Contains('/', StringComparison.Ordinal));
    }

    [Fact]
    public void Code_ToleratesTheWhitespaceAPasteAdds()
    {
        string code = PairingCode.Encode(Payload(FixedNow));
        string wrapped = " " + string.Join("\r\n", Chunk(code, 40)) + "\n";

        PairingCodeResult result = PairingCode.Decode(wrapped, FixedNow);

        Assert.True(result.Succeeded);
        Assert.Equal(PairingCode.Normalize(code), PairingCode.Normalize(wrapped));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("\r\n\t")]
    public void Code_RefusesAnEmptyPaste(string code)
    {
        PairingCodeResult result = PairingCode.Decode(code, FixedNow);

        Assert.False(result.Succeeded);
        Assert.Equal(PairingCodeReasons.Empty, result.Reason);
        Assert.False(string.IsNullOrWhiteSpace(result.Detail));
    }

    [Fact]
    public void Code_RefusesCharactersOutsideTheAlphabet()
    {
        string code = PairingCode.Encode(Payload(FixedNow));

        Assert.Equal(PairingCodeReasons.NotBase64Url, PairingCode.Decode(code + "+", FixedNow).Reason);
        Assert.Equal(PairingCodeReasons.NotBase64Url, PairingCode.Decode(code + "=", FixedNow).Reason);
        Assert.Equal(PairingCodeReasons.NotBase64Url, PairingCode.Decode("这不是配对码", FixedNow).Reason);
        // A single leftover character is not a truncation that padding could repair.
        Assert.Equal(PairingCodeReasons.NotBase64Url, PairingCode.Decode("AAAAA", FixedNow).Reason);
    }

    [Fact]
    public void Code_RefusesTextLongerThanTheLimit()
    {
        string tooLong = new('A', PairingCode.MaximumLength + 1);

        PairingCodeResult result = PairingCode.Decode(tooLong, FixedNow);

        Assert.Equal(PairingCodeReasons.TooLong, result.Reason);
        Assert.Contains("4096", Assert.IsType<string>(result.Detail));
    }

    [Fact]
    public void Code_RefusesPayloadThatIsNotJson()
    {
        PairingCodeResult result = PairingCode.Decode(Base64Url("this is not json"), FixedNow);

        Assert.Equal(PairingCodeReasons.PayloadInvalid, result.Reason);
    }

    [Fact]
    public void Code_RefusesAnUnknownSchemaVersion()
    {
        PairingPayload payload = Payload(FixedNow);
        string json = PairingPayloadCodec.Serialize(payload);
        string bumped = json.Replace("\"schemaVersion\":1", "\"schemaVersion\":2", StringComparison.Ordinal);
        Assert.NotEqual(json, bumped);

        PairingCodeResult result = PairingCode.Decode(Base64Url(bumped), FixedNow);

        Assert.Equal(PairingCodeReasons.UnsupportedVersion, result.Reason);
    }

    [Fact]
    public void Code_RefusesAnExpiredInvitationButAcceptsTheMillisecondBefore()
    {
        PairingPayload payload = Payload(FixedNow);
        string code = PairingCode.Encode(payload);

        Assert.True(PairingCode.Decode(code, payload.ExpiresAtEpochMs - 1).Succeeded);
        PairingCodeResult expired = PairingCode.Decode(code, payload.ExpiresAtEpochMs + 1);
        Assert.Equal(PairingCodeReasons.Expired, expired.Reason);
        Assert.Contains(PairingPayload.LifetimeSeconds.ToString(), Assert.IsType<string>(expired.Detail));
    }

    [Fact]
    public void Code_RefusesAMalformedChallengeWithoutEchoingIt()
    {
        PairingPayload payload = Payload(FixedNow);
        const string BogusChallenge = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
        string json = PairingPayloadCodec.Serialize(payload);
        const string Field = "\"challenge\":\"";
        int start = json.IndexOf(Field, StringComparison.Ordinal) + Field.Length;
        Assert.True(start > Field.Length - 1, "the canonical payload should carry a challenge field");
        int end = json.IndexOf('"', start);
        string tampered = json[..start] + BogusChallenge + json[end..];
        Assert.NotEqual(json, tampered);

        PairingCodeResult result = PairingCode.Decode(Base64Url(tampered), FixedNow);

        Assert.Equal(PairingCodeReasons.BadChallenge, result.Reason);
        // The decoder maps the reason from the contract exception and never forwards its message: a challenge that
        // reached a log line would be a challenge that could be replayed inside the window.
        Assert.False((result.Detail ?? string.Empty).Contains("ZZZZ", StringComparison.Ordinal));
        Assert.False(result.ToString().Contains("ZZZZ", StringComparison.Ordinal));
    }

    [Fact]
    public void Code_ResultNeverPrintsTheChallenge()
    {
        PairingPayload payload = Payload(FixedNow);
        PairingCodeResult ok = PairingCode.Decode(PairingCode.Encode(payload), FixedNow);

        Assert.True(ok.Succeeded);
        Assert.False(ok.ToString().Contains(payload.Challenge, StringComparison.Ordinal));
        Assert.Contains(payload.DeviceId, ok.ToString());
    }

    [Fact]
    public void FingerprintDisplay_GroupsSixtyFourHexCharactersInFours()
    {
        PairingFingerprint fingerprint = PairingFingerprint.FromBytes(FingerprintBytes);

        string display = fingerprint.ToDisplayString();
        string[] groups = display.Split(' ');

        Assert.Equal(16, groups.Length);
        Assert.All(groups, group => Assert.Equal(4, group.Length));
        Assert.Equal(fingerprint.Hex.ToUpperInvariant(), string.Concat(groups));
    }

    // ---- presenter: hardening ------------------------------------------------

    [Fact]
    public void Presenter_RefusesABlankDeviceId()
    {
        Assert.Throws<ArgumentException>(() => new PairingPresenter(" "));
    }

    [Fact]
    public void Presenter_PollBeforeStartIsIdle()
    {
        using PairingPresenter presenter = new("dev-host");

        PairingHostSnapshot snapshot = presenter.PollHost(FixedNow);

        Assert.Equal(PairingHostState.Idle, snapshot.State);
        Assert.Equal(PairingStepCodes.HostIdle, snapshot.StepCode);
        Assert.False(snapshot.IsListening);
        Assert.Equal(0, snapshot.RemainingMilliseconds);
        Assert.False(string.IsNullOrWhiteSpace(snapshot.NextStep));
        Assert.Empty(presenter.PairingCodeText);
    }

    [Fact]
    public void Presenter_StopBeforeStartSettlesIntoStopped()
    {
        using PairingPresenter presenter = new("dev-host");

        PairingHostSnapshot snapshot = presenter.StopHost(FixedNow);

        Assert.Equal(PairingHostState.Stopped, snapshot.State);
        Assert.Equal(PairingStepCodes.HostStopped, snapshot.StepCode);
        Assert.True(snapshot.IsTerminal);
    }

    [Fact]
    public void Presenter_DisposeClosesTheListenerAndRefusesFurtherWork()
    {
        PairingPresenter presenter = new("dev-host");
        presenter.StartHost(FixedNow);
        IPEndPoint endpoint = Assert.IsType<IPEndPoint>(presenter.ListenEndPoint);

        presenter.Dispose();

        Assert.Null(presenter.ListenEndPoint);
        Assert.Throws<ObjectDisposedException>(() => presenter.StartHost(FixedNow));
        AssertPortIsReusable(endpoint.Port);
    }

    // ---- presenter: hosting ---------------------------------------------------

    [Fact]
    public void StartHost_PublishesADecodableCodeAndAGroupedFingerprint()
    {
        using PairingPresenter presenter = new("dev-host");

        PairingHostSnapshot snapshot = presenter.StartHost(FixedNow);

        Assert.Equal(PairingHostState.Listening, snapshot.State);
        Assert.Equal(PairingStepCodes.HostListening, snapshot.StepCode);
        Assert.True(snapshot.IsListening);
        Assert.Equal(PairingPayload.LifetimeMilliseconds, snapshot.RemainingMilliseconds);
        Assert.Equal(120, snapshot.RemainingSeconds);
        Assert.Equal(16, snapshot.FingerprintDisplay.Split(' ').Length);
        Assert.NotNull(presenter.ListenEndPoint);

        PairingCodeResult decoded = PairingCode.Decode(presenter.PairingCodeText, FixedNow);
        Assert.True(decoded.Succeeded);
        Assert.Equal("dev-host", decoded.Payload!.DeviceId);
        Assert.Equal(FixedNow + PairingPayload.LifetimeMilliseconds, decoded.Payload.ExpiresAtEpochMs);
        Assert.Equal(snapshot.FingerprintDisplay, decoded.Payload.ParseFingerprint().ToDisplayString());
        Assert.Contains("127.0.0.1", decoded.Payload.EphemeralUrl, StringComparison.Ordinal);
    }

    [Fact]
    public void StartHost_ReplacesAPreviousAttempt()
    {
        using PairingPresenter presenter = new("dev-host");
        presenter.StartHost(FixedNow);
        IPEndPoint first = Assert.IsType<IPEndPoint>(presenter.ListenEndPoint);

        PairingHostSnapshot second = presenter.StartHost(FixedNow + 1000);

        Assert.Equal(PairingHostState.Listening, second.State);
        Assert.Equal(FixedNow + 1000 + PairingPayload.LifetimeMilliseconds, second.RemainingMilliseconds + FixedNow + 1000);
        AssertPortIsReusable(first.Port);
    }

    [Fact]
    public void StopHost_ClosesTheListenerAndHoldsTheState()
    {
        using PairingPresenter presenter = new("dev-host");
        presenter.StartHost(FixedNow);
        IPEndPoint endpoint = Assert.IsType<IPEndPoint>(presenter.ListenEndPoint);

        PairingHostSnapshot stopped = presenter.StopHost(FixedNow + 1);
        Assert.Equal(PairingHostState.Stopped, stopped.State);
        Assert.Equal(PairingStepCodes.HostStopped, stopped.StepCode);
        Assert.Null(presenter.ListenEndPoint);
        Assert.Empty(presenter.PairingCodeText);
        AssertPortIsReusable(endpoint.Port);

        // A terminal attempt is left alone: polling must not resurrect it or change the code it reported.
        PairingHostSnapshot again = presenter.PollHost(FixedNow + 2);
        Assert.Equal(PairingHostState.Stopped, again.State);
    }

    [Fact]
    public void StartHost_AfterStopListensAgain()
    {
        using PairingPresenter presenter = new("dev-host");
        presenter.StartHost(FixedNow);
        presenter.StopHost(FixedNow);

        PairingHostSnapshot restarted = presenter.StartHost(FixedNow + 5000);

        Assert.Equal(PairingHostState.Listening, restarted.State);
        Assert.NotNull(presenter.ListenEndPoint);
        Assert.NotEmpty(presenter.PairingCodeText);
    }

    [Fact]
    public void PollHost_ExpiresExactlyAtTheInvitationDeadline()
    {
        using PairingPresenter presenter = new("dev-host");
        presenter.StartHost(FixedNow);

        PairingHostSnapshot alive = presenter.PollHost(FixedNow + PairingPayload.LifetimeMilliseconds - 1);
        Assert.True(alive.IsListening);
        Assert.Equal(1, alive.RemainingSeconds);

        PairingHostSnapshot expired = presenter.PollHost(FixedNow + PairingPayload.LifetimeMilliseconds);
        Assert.Equal(PairingHostState.Expired, expired.State);
        Assert.Equal(PairingStepCodes.HostExpired, expired.StepCode);
        Assert.Equal(PairingTransportReasons.LifetimeExceeded, expired.Reason);
        Assert.Null(presenter.ListenEndPoint);
        Assert.Contains("配对码已过期", expired.NextStep, StringComparison.Ordinal);
    }

    // ---- presenter: joining ---------------------------------------------------

    [Fact]
    public async Task Guest_RefusesABadCodeWithoutTouchingTheNetwork()
    {
        using PairingPresenter guest = new("dev-guest", () => PairingPeerEnvelopeResult.Missing("unused"));

        PairingGuestResult result = await guest.AcceptGuestAsync("not-a-code!", FixedNow).WaitAsync(TimeSpan.FromSeconds(10));

        Assert.False(result.Paired);
        Assert.Equal(PairingStepCodes.GuestCodeRefused, result.StepCode);
        Assert.Equal(PairingCodeReasons.NotBase64Url, result.Reason);
        Assert.Null(result.PeerDeviceId);
        Assert.Equal(0, result.PeerSourceCount);
        Assert.False(string.IsNullOrWhiteSpace(result.Message));
        Assert.False(result.Message.Contains("已导入", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Guest_RefusesAnExpiredCode()
    {
        PairingPayload payload = Payload(FixedNow);
        string code = PairingCode.Encode(payload);

        using PairingPresenter guest = new("dev-guest");
        PairingGuestResult result = await guest.AcceptGuestAsync(code, payload.ExpiresAtEpochMs + 1).WaitAsync(TimeSpan.FromSeconds(10));

        Assert.False(result.Paired);
        Assert.Equal(PairingStepCodes.GuestCodeRefused, result.StepCode);
        Assert.Equal(PairingCodeReasons.Expired, result.Reason);
        Assert.Contains("过期", result.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Guest_ReportsAnUnreachablePeerByStableCode()
    {
        int port = ReservePort();
        PairingPayload payload = PairingPayload.Create(
            "dev-peer",
            "https://127.0.0.1:" + port.ToString(System.Globalization.CultureInfo.InvariantCulture) + "/pairing",
            PairingChallenge.Create(),
            PairingFingerprint.FromBytes(FingerprintBytes),
            FixedNow);

        using PairingPresenter guest = new("dev-guest");
        PairingGuestResult result = await guest.AcceptGuestAsync(PairingCode.Encode(payload), FixedNow).WaitAsync(TimeSpan.FromSeconds(20));

        Assert.False(result.Paired);
        Assert.Equal(PairingStepCodes.GuestFailed, result.StepCode);
        Assert.Contains(result.Reason, new[] { PairingTransportReasons.ListenerFailed, PairingTransportReasons.PeerTimeout });
        Assert.Contains("（稳定码：" + result.Reason, "（稳定码：" + result.Reason + "）", StringComparison.Ordinal);
        Assert.False(result.Message.Contains("已导入", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Guest_PairsOverLoopbackAndOnlyCountsThePeersSources()
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        using PairingPresenter host = new("dev-host");
        using PairingPresenter guest = new("dev-guest", () => PairingPeerEnvelopeResult.Found(PeerEnvelope(3)));
        host.StartHost(now);

        PairingGuestResult result = await guest.AcceptGuestAsync(host.PairingCodeText, now).WaitAsync(TimeSpan.FromSeconds(30));

        Assert.True(result.Paired, result.Message);
        Assert.Equal(PairingStepCodes.GuestPaired, result.StepCode);
        Assert.Null(result.Reason);
        Assert.Equal("dev-host", result.PeerDeviceId);
        Assert.NotNull(result.PeerFingerprintDisplay);
        Assert.Equal(3, result.PeerSourceCount);
        Assert.True(result.HasPeerEnvelope);
        Assert.Equal("已收到 3 个来源的清单（未导入）。", result.Message);
        Assert.Equal(result.Message, result.PeerSourceSummary);

        PairingHostSnapshot settled = await WaitForTerminalAsync(host, now);
        Assert.Equal(PairingHostState.Paired, settled.State);
        Assert.Equal(PairingStepCodes.HostPaired, settled.StepCode);
        Assert.Null(host.ListenEndPoint);
        // The wire is one-way, so the host must not report its own device id as the peer's.
        Assert.Null(host.PeerDeviceId);
        Assert.Null(host.PeerFingerprintDisplay);
        Assert.Contains("不会自动导入", settled.NextStep, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Guest_PairsWithoutAnEnvelopeAndStillSaysNotImported()
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        using PairingPresenter host = new("dev-host");
        using PairingPresenter guest = new("dev-guest", () => PairingPeerEnvelopeResult.Missing("没有找到对端信封：sync-envelope.json"));
        host.StartHost(now);

        PairingGuestResult result = await guest.AcceptGuestAsync(host.PairingCodeText, now).WaitAsync(TimeSpan.FromSeconds(30));

        Assert.True(result.Paired, result.Message);
        Assert.False(result.HasPeerEnvelope);
        Assert.Equal(0, result.PeerSourceCount);
        Assert.Contains("（未导入）", result.Message, StringComparison.Ordinal);
        Assert.Contains("没有读到来源清单", result.Message, StringComparison.Ordinal);
        Assert.Equal("没有读到来源清单（未导入）。", result.PeerSourceSummary);
    }

    // ---- wording --------------------------------------------------------------

    [Fact]
    public void StepCodes_AreStableAndUnique()
    {
        string[] expected =
        [
            "pair-host-idle",
            "pair-host-listening",
            "pair-host-paired",
            "pair-host-failed",
            "pair-host-expired",
            "pair-host-stopped",
            "pair-guest-paired",
            "pair-guest-code-refused",
            "pair-guest-failed",
        ];

        Assert.Equal(expected, PairingStepCodes.All);
        Assert.Equal(expected.Length, PairingStepCodes.All.Distinct(StringComparer.Ordinal).Count());
    }

    [Fact]
    public void CodeReasons_AreStableAndEveryOneIsExplained()
    {
        string[] expected =
        [
            "code-empty",
            "not-base64url",
            "code-too-long",
            "payload-invalid",
            "unsupported-version",
            "bad-challenge",
            "bad-fingerprint",
            "bad-url",
            "expired",
        ];

        Assert.Equal(expected, PairingCodeReasons.All);
        Assert.All(PairingCodeReasons.All, reason =>
        {
            string text = PairingUiText.DescribeCodeRejection(reason, null);
            Assert.False(string.IsNullOrWhiteSpace(text));
            Assert.DoesNotContain(reason, text);
        });
    }

    [Fact]
    public void TransportFailures_AllReadAsChineseSentences()
    {
        Assert.All(PairingTransportReasons.All, reason =>
        {
            string text = PairingUiText.DescribeTransportFailure(reason);
            Assert.False(string.IsNullOrWhiteSpace(text));
            Assert.Contains("配对", text, StringComparison.Ordinal);
            // The raw code belongs in the log, not in the sentence a user reads.
            Assert.DoesNotContain(reason, text);
        });

        Assert.Contains("拒绝", PairingUiText.DescribeTransportFailure(PairingTransportReasons.ListenerFailed), StringComparison.Ordinal);
        Assert.Contains("指纹不匹配", PairingUiText.DescribeTransportFailure(PairingTransportReasons.FingerprintMismatch), StringComparison.Ordinal);
        Assert.Contains("过期", PairingUiText.DescribeTransportFailure(PairingTransportReasons.LifetimeExceeded), StringComparison.Ordinal);
        Assert.Contains("超时", PairingUiText.DescribeTransportFailure(PairingTransportReasons.PeerTimeout), StringComparison.Ordinal);
    }

    [Fact]
    public void HostStates_AllHaveALabelAndANextStep()
    {
        PairingHostState[] states =
        [
            PairingHostState.Idle,
            PairingHostState.Listening,
            PairingHostState.Paired,
            PairingHostState.Failed,
            PairingHostState.Expired,
            PairingHostState.Stopped,
        ];

        Assert.All(states, state =>
        {
            Assert.False(string.IsNullOrWhiteSpace(PairingUiText.DescribeHostState(state)));
            Assert.False(string.IsNullOrWhiteSpace(PairingUiText.DescribeHostNextStep(state, null, null)));
        });

        // The only successful outcome may never promise an import.
        string paired = PairingUiText.DescribeHostNextStep(PairingHostState.Paired, null, null);
        Assert.DoesNotContain("已导入", paired);
        Assert.Contains("不会自动导入", paired, StringComparison.Ordinal);
    }

    // ---- helpers --------------------------------------------------------------

    private static readonly byte[] FingerprintBytes =
    [
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32,
    ];

    private static PairingPayload Payload(long now) => PairingPayload.Create(
        "dev-host",
        "https://127.0.0.1:47831/pairing",
        PairingChallenge.Create(),
        PairingFingerprint.FromBytes(FingerprintBytes),
        now);

    /// <summary>A peer envelope with a fixed set of sources; nothing here is ever applied.</summary>
    private static SourceSyncEnvelope PeerEnvelope(int sourceCount)
    {
        var sources = new List<SourceRecord>();
        for (int index = 0; index < sourceCount; index++)
        {
            sources.Add(new SourceRecord
            {
                SyncSourceId = "local-" + index.ToString("D16", System.Globalization.CultureInfo.InvariantCulture),
                SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.WebDav),
                DisplayName = "对端来源 " + (index + 1).ToString(System.Globalization.CultureInfo.InvariantCulture),
                Endpoints = [new SourceEndpoint { Url = "http://peer.local:5005", IsPrimary = true }],
                RecordRevision = 1,
                FieldVersion = 1,
            });
        }

        return new SourceSyncEnvelope
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = 1,
            DeviceId = "dev-peer",
            SnapshotId = "snap-pairing-test",
            CreatedAtEpochMs = FixedNow,
            Capabilities = new SyncCapabilities { CanSendSecrets = false, SourceTypes = [SyncSourceTypes.WebDav] },
            Sources = sources,
        };
    }

    private static async Task<PairingHostSnapshot> WaitForTerminalAsync(PairingPresenter presenter, long now)
    {
        for (int attempt = 0; attempt < 400; attempt++)
        {
            PairingHostSnapshot snapshot = presenter.PollHost(now);
            if (snapshot.IsTerminal)
            {
                return snapshot;
            }

            await Task.Delay(25).ConfigureAwait(false);
        }

        return presenter.PollHost(now);
    }

    /// <summary>Binds a loopback port and releases it, so a test can point at a port nothing listens on.</summary>
    private static int ReservePort()
    {
        TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    /// <summary>Asserts a port really closed: it must be bindable again without waiting for a timeout.</summary>
    private static void AssertPortIsReusable(int port)
    {
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                TcpListener listener = new(IPAddress.Loopback, port);
                listener.Start();
                listener.Stop();
                return;
            }
            catch (SocketException) when (attempt < 20)
            {
                Thread.Sleep(25);
            }
        }
    }

    private static string Base64Url(string text) =>
        Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(text)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static IEnumerable<string> Chunk(string text, int size)
    {
        for (int index = 0; index < text.Length; index += size)
        {
            yield return text.Substring(index, Math.Min(size, text.Length - index));
        }
    }
}

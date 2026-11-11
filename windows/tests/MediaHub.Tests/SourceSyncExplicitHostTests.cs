using System;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-51: the explicit-host gate behind <c>transfer serve --bind</c> and <c>--allow-non-loopback</c>.
/// </summary>
/// <remarks>
/// Nothing here binds or dials a non-loopback address, and that is the point: the gate exists so such an
/// address is decided before a socket exists. A test that opened one would prove less and could raise a
/// Windows firewall prompt on a developer box, so every non-loopback case below asserts the refusal (or the
/// resolved literal) without a single socket, and the one live transfer is loopback. Real two-machine
/// evidence over a LAN card stays NOT_RUN; see docs/cross-device/W6-51-EXPLICIT-HOST.md.
/// </remarks>
public sealed class SourceSyncExplicitHostTests : IDisposable
{
    private const string ListeningMarker = "listening 127.0.0.1:";
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-host-" + Guid.NewGuid().ToString("N"));

    public SourceSyncExplicitHostTests()
    {
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp folder is not a test failure.
        }
        catch (UnauthorizedAccessException)
        {
            // Same.
        }
    }

    // ---- 1. the address gate ----------------------------------------------------------------

    [Theory]
    [InlineData("127.0.0.1")]
    [InlineData("::1")]
    [InlineData("localhost")]
    [InlineData("LOCALHOST")]
    [InlineData(" 127.0.0.1 ")]
    [InlineData("127.0.0.2")]
    [InlineData("")]
    [InlineData(null)]
    public void Consent_DoesNotChangeAnyLoopbackSpelling(string? address)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 0, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.True(accepted, "loopback must stay accepted with consent: " + detail);
        Assert.NotNull(endpoint);
        Assert.True(IPAddress.IsLoopback(endpoint!.Address));
        Assert.Null(detail);
    }

    [Fact]
    public void WithoutConsent_ALanLiteralGetsThePreW651SentenceWordForWord()
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            "192.168.1.5", 8080, allowExplicitHost: false, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.NotNull(detail);
        Assert.Contains("Only 127.0.0.1, ::1 and localhost are accepted", detail!, StringComparison.Ordinal);
        Assert.Contains("before any socket was created", detail, StringComparison.Ordinal);
        Assert.Contains("firewall", detail, StringComparison.Ordinal);

        // The compatibility overload is the pre-W6-51 gate, byte for byte, whatever the flag does.
        Assert.False(SourceSyncTransferAddress.TryResolveLoopback("192.168.1.5", 8080, out _, out string? viaLoopbackOnly));
        Assert.Equal(detail, viaLoopbackOnly);
        Assert.False(SourceSyncTransferAddress.IsLoopbackAddress("192.168.1.5"));
    }

    [Theory]
    [InlineData("192.168.1.5", "192.168.1.5")]
    [InlineData("10.0.0.7", "10.0.0.7")]
    [InlineData("172.16.30.4", "172.16.30.4")]
    [InlineData("fd00::1", "fd00::1")]
    public void WithConsent_OneUnicastLiteralIsAccepted(string address, string expectedText)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 41234, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.True(accepted, "one unicast literal must be accepted with consent: " + detail);
        Assert.NotNull(endpoint);
        Assert.Null(detail);
        Assert.Equal(IPAddress.Parse(expectedText), endpoint!.Address);
        Assert.Equal(41234, endpoint.Port);
    }

    [Theory]
    [InlineData("0.0.0.0")]
    [InlineData("::")]
    [InlineData("0.1.2.3")]
    public void WithConsent_AWildcardIsStillRefusedAndTheSentenceSaysSo(string address)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("wildcard", detail!, StringComparison.Ordinal);
        Assert.Contains("is not one machine's address", detail, StringComparison.Ordinal);
        Assert.Contains("before any socket was created", detail, StringComparison.Ordinal);
    }

    [Fact]
    public void WithConsent_BroadcastIsStillRefused()
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            "255.255.255.255", 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("broadcast", detail!, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("239.1.2.3")]
    [InlineData("224.0.0.1")]
    [InlineData("ff02::1")]
    public void WithConsent_MulticastIsStillRefused(string address)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("multicast", detail!, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("240.0.0.1")]
    [InlineData("255.255.255.254")]
    public void WithConsent_TheReservedRangesAreStillRefused(string address)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("reserved", detail!, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("mediahub-lan.local")]
    [InlineData("nas")]
    [InlineData("example.com")]
    public void WithConsent_AHostNameIsStillRefusedBecauseNothingResolvesIt(string address)
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            address, 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("does not resolve host names", detail!, StringComparison.Ordinal);
    }

    [Fact]
    public void WithConsent_AnOverlongAddressIsStillRefused()
    {
        bool accepted = SourceSyncTransferAddress.TryResolve(
            new string('1', 257), 8080, allowExplicitHost: true, out IPEndPoint? endpoint, out string? detail);

        Assert.False(accepted);
        Assert.Null(endpoint);
        Assert.Contains("at most 256 characters", detail!, StringComparison.Ordinal);
    }

    [Fact]
    public void Listener_RefusesAWildcardBindWhetherOrNotConsentWasGiven()
    {
        SourceSyncTransferStartResult refused = SourceSyncTransferListener.Start("0.0.0.0", 0, tls: false, allowExplicitHost: true);
        Assert.False(refused.Started);
        Assert.Null(refused.Listener);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, refused.Reason);
        Assert.Contains("wildcard", refused.Detail!, StringComparison.Ordinal);

        SourceSyncTransferStartResult unconsented = SourceSyncTransferListener.Start("192.168.1.5", 0);
        Assert.False(unconsented.Started);
        Assert.Null(unconsented.Listener);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, unconsented.Reason);
        Assert.Contains("firewall", unconsented.Detail!, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Client_RefusesABroadcastTargetWithConsentAndOpensNoSocket()
    {
        SourceSyncTransferResult refused = await SourceSyncTransferClient.SendAsync(
            "255.255.255.255", 9000, Payload("dev-a"), allowExplicitHost: true);

        Assert.False(refused.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, refused.Reason);
        Assert.Contains("broadcast", refused.Detail!, StringComparison.Ordinal);
    }

    // ---- 2. the command line ----------------------------------------------------------------

    [Fact]
    public void Parse_ServeTakesBothOptions()
    {
        CliParseResult parsed = CliParser.Parse(
            ["transfer", "serve", "--root", _root, "--device", "dev-b", "--bind", "192.168.1.5", "--allow-non-loopback"]);

        Assert.True(parsed.Succeeded, parsed.Error);
        Assert.Equal("192.168.1.5", parsed.Invocation!.BindAddress);
        Assert.True(parsed.Invocation.AllowExplicitHost);
        Assert.Equal(SyncCliCommand.TransferServe, parsed.Invocation.Command);
    }

    [Fact]
    public void Parse_ServeWithoutTheOptionsIsStillThePreW651Invocation()
    {
        CliParseResult parsed = CliParser.Parse(["transfer", "serve", "--root", _root, "--device", "dev-b"]);

        Assert.True(parsed.Succeeded, parsed.Error);
        Assert.Null(parsed.Invocation!.BindAddress);
        Assert.False(parsed.Invocation.AllowExplicitHost);
    }

    [Fact]
    public void Parse_SendTakesConsentButRefusesBind()
    {
        CliParseResult send = CliParser.Parse(
            ["transfer", "send", "--to", "192.168.1.5:9000", "--root", _root, "--device", "dev-a", "--allow-non-loopback"]);
        Assert.True(send.Succeeded, send.Error);
        Assert.True(send.Invocation!.AllowExplicitHost);
        Assert.Null(send.Invocation.BindAddress);

        CliParseResult withBind = CliParser.Parse(
            ["transfer", "send", "--to", "127.0.0.1:9000", "--root", _root, "--device", "dev-a", "--bind", "127.0.0.1"]);
        Assert.False(withBind.Succeeded);
        Assert.Equal("option-not-allowed-for-command:--bind:transfer-send", withBind.Error);
    }

    [Fact]
    public void Parse_RoundtripRefusesConsentBecauseItInventsBothAddresses()
    {
        CliParseResult parsed = CliParser.Parse(
            ["transfer", "roundtrip", "--root", _root, "--device-a", "a", "--device-b", "b", "--allow-non-loopback"]);

        Assert.False(parsed.Succeeded);
        Assert.Equal("option-not-allowed-for-command:--allow-non-loopback:transfer-roundtrip", parsed.Error);
    }

    [Fact]
    public void Parse_ConsentTakesNoValue()
    {
        CliParseResult parsed = CliParser.Parse(
            ["transfer", "serve", "--root", _root, "--device", "dev-b", "--allow-non-loopback=yes"]);

        Assert.False(parsed.Succeeded);
        Assert.Equal("flag-takes-no-value:--allow-non-loopback", parsed.Error);
    }

    [Fact]
    public void Parse_BindNeedsAValue()
    {
        CliParseResult missing = CliParser.Parse(["transfer", "serve", "--root", _root, "--device", "dev-b", "--bind"]);
        Assert.False(missing.Succeeded);
        Assert.Equal("missing-value:--bind", missing.Error);

        CliParseResult blank = CliParser.Parse(["transfer", "serve", "--root", _root, "--device", "dev-b", "--bind", "  "]);
        Assert.False(blank.Succeeded);
        Assert.Equal("empty-value:--bind", blank.Error);
    }

    [Fact]
    public void Parse_BindIsRefusedOutsideServe()
    {
        Assert.Equal(
            "option-not-allowed-for-command:--bind:baseline-show",
            CliParser.Parse(["baseline", "show", "--root", _root, "--bind", "127.0.0.1"]).Error);

        Assert.Equal(
            "option-not-allowed-for-command:--allow-non-loopback:pair-link",
            CliParser.Parse(["pair", "link", "--allow-non-loopback"]).Error);
    }

    [Fact]
    public void Usage_NamesTheTwoOptionsAndStillPromisesLoopbackByDefault()
    {
        Assert.True(CliParser.Parse(["transfer", "serve", "--help"]).IsHelp);

        (int exitCode, string stdout, string _) = Run("transfer", "serve", "--help");
        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains("--bind ADDR", stdout, StringComparison.Ordinal);
        Assert.Contains("--allow-non-loopback", stdout, StringComparison.Ordinal);
        Assert.Contains("loopback only", stdout, StringComparison.Ordinal);
        Assert.Contains("W6-51", stdout, StringComparison.Ordinal);
    }

    // ---- 3. real commands ---------------------------------------------------------------

    [Fact]
    public void Serve_RefusesAWildcardBindWithConsentBeforeAnnouncingAPort()
    {
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev-b",
            "--bind", "0.0.0.0", "--allow-non-loopback", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.DoesNotContain(ListeningMarker, stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("wildcard", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Serve_RefusesALanBindWithoutConsentWithTheOriginalSentence()
    {
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev-b", "--bind", "192.168.1.5", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.DoesNotContain(ListeningMarker, stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("firewall", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Send_RefusesABroadcastTargetEvenWithConsent()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"));

        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "send", "--to", "255.255.255.255:9000", "--root", Path.Combine(_root, "a"),
            "--device", "dev-a", "--allow-non-loopback", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("before any socket was created", stderr, StringComparison.Ordinal);
        Assert.Contains("broadcast", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Serve_WithConsentStillServesLoopbackExactlyAsBefore()
    {
        string root = Path.Combine(_root, "b");
        CaptureWriter stdout = new();
        CaptureWriter stderr = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--allow-non-loopback", "--json"],
            stdout,
            stderr,
            () => DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L)));
        int port = await WaitForListeningPortAsync(stderr);
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync("127.0.0.1", port, Payload("dev-a"));
        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30));

        Assert.True(sent.Succeeded, "the loopback peer could not deliver: " + sent.Reason);
        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains(ListeningMarker, stderr.ToString(), StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout.ToString());
        Assert.Equal("dev-b", document.RootElement.GetProperty("to").GetString());
        Assert.Equal(1, document.RootElement.GetProperty("counts").GetProperty("apply").GetInt32());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static LocalSourceRecord Source(string syncSourceId, string displayName) => new()
    {
        ServerId = "srv-" + syncSourceId,
        SyncSourceId = syncSourceId,
        Type = ServerType.Emby,
        SourceTypeRaw = SyncSourceTypes.Emby,
        Url = EmbyUrl,
        DisplayName = displayName,
        RecordRevision = 1,
    };

    private static void Seed(string directory, params LocalSourceRecord[] records)
    {
        FileLocalSourceStore store = new(directory);
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }
    }

    private static byte[] Payload(string deviceId)
    {
        InMemoryLocalSourceStore store = new();
        store.Save(Source("src-1", "Home"));
        SourceSyncPeerRound sender = new(deviceId, store);
        return Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(sender.Export()));
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L));
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static JsonDocument ParseSingleDocument(string stdout)
    {
        string trimmed = stdout.Trim();
        Assert.False(trimmed.Length == 0, "stdout carried no JSON document");
        Assert.DoesNotContain("\n", trimmed, StringComparison.Ordinal);
        return JsonDocument.Parse(trimmed);
    }

    private static async Task<int> WaitForListeningPortAsync(CaptureWriter stderr)
    {
        for (int attempt = 0; attempt < 200; attempt++)
        {
            string text = stderr.ToString();
            int index = text.IndexOf(ListeningMarker, StringComparison.Ordinal);
            if (index >= 0)
            {
                int start = index + ListeningMarker.Length;
                int end = start;
                while (end < text.Length && char.IsDigit(text[end]))
                {
                    end++;
                }

                if (end > start)
                {
                    return int.Parse(text[start..end], CultureInfo.InvariantCulture);
                }
            }

            await Task.Delay(25);
        }

        throw new InvalidOperationException("the serve command never announced its port: " + stderr);
    }

    /// <summary>A <see cref="TextWriter"/> the polling test thread may read while the command writes.</summary>
    private sealed class CaptureWriter : TextWriter
    {
        private readonly StringBuilder _text = new();
        private readonly object _gate = new();

        public override Encoding Encoding => Encoding.UTF8;

        public override void Write(char value)
        {
            lock (_gate)
            {
                _text.Append(value);
            }
        }

        public override void Write(string? value)
        {
            lock (_gate)
            {
                _text.Append(value);
            }
        }

        public override void WriteLine(string? value)
        {
            lock (_gate)
            {
                _text.Append(value).Append('\n');
            }
        }

        public override string ToString()
        {
            lock (_gate)
            {
                return _text.ToString();
            }
        }
    }
}

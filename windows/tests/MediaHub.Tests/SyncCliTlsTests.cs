using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-26: <c>--tls</c> / <c>--expect-fingerprint</c> on the <c>transfer</c> verbs. Every test either
/// asserts a stable code with no socket at all, or runs the real command in-process and speaks TLS to
/// it over 127.0.0.1. Nothing here opens a window or writes into the user's real settings folder.
/// </summary>
public sealed class SyncCliTlsTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string ListeningMarker = "listening 127.0.0.1:";
    private const string FingerprintMarker = "certificate fingerprint: ";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-tls-" + Guid.NewGuid().ToString("N"));

    public SyncCliTlsTests()
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

    private string DeviceRoot(string name) => Path.Combine(_root, name);

    // ---- 1. the flag combination rules ------------------------------------------------

    [Fact]
    public void A_pin_without_tls_is_a_usage_error()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("a"), "--device", "dev-a",
            "--expect-fingerprint", new string('a', 64));

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-required-option:--tls", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Tls_on_send_without_a_pin_is_a_usage_error()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("a"), "--device", "dev-a", "--tls");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-required-option:--expect-fingerprint", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Serve_refuses_an_explicit_pin_because_it_is_the_side_that_presents_the_certificate()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--tls",
            "--expect-fingerprint", new string('a', 64));

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--expect-fingerprint:transfer-serve", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Roundtrip_refuses_an_explicit_pin_because_it_pins_the_listener_it_just_bound()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "roundtrip", "--root", DeviceRoot("r"), "--device-a", "dev-a", "--device-b", "dev-b",
            "--tls", "--expect-fingerprint", new string('a', 64));

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--expect-fingerprint:transfer-roundtrip", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Export_rejects_tls()
    {
        (int exitCode, string _, string stderr) = Run("export", "--root", DeviceRoot("a"), "--tls");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--tls:export", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Export_rejects_a_pin()
    {
        (int exitCode, string _, string stderr) = Run(
            "export", "--root", DeviceRoot("a"), "--expect-fingerprint", new string('a', 64));

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--expect-fingerprint:export", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Tls_takes_no_value()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--tls=1");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("flag-takes-no-value:--tls", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void A_pin_must_be_sixty_four_hex_characters()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("a"), "--device", "dev-a",
            "--tls", "--expect-fingerprint", "abc");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("invalid-fingerprint:abc", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void The_help_lists_the_tls_options()
    {
        (int exitCode, string stdout, string _) = Run("transfer", "--help");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains("--tls", stdout, StringComparison.Ordinal);
        Assert.Contains("--expect-fingerprint HEX", stdout, StringComparison.Ordinal);
        Assert.Contains("off by default", stdout, StringComparison.Ordinal);
    }

    // ---- 2. the real TLS chain over loopback -------------------------------------------

    [Fact]
    public async Task Serve_announces_its_fingerprint_and_a_pinned_send_lands()
    {
        string rootB = DeviceRoot("b");
        (int sendExit, string sendStdout, string sendStderr) send = (0, string.Empty, string.Empty);
        (int exitCode, string stdout, string stderr) serve = (0, string.Empty, string.Empty);

        serve = await ServeWithAsync(
            ["transfer", "serve", "--root", rootB, "--device", "dev-b", "--tls", "--json"],
            tls: true,
            (port, fingerprint) =>
            {
                Assert.NotNull(fingerprint);
                Assert.Equal(64, fingerprint!.Length);
                send = Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", DeviceRoot("a"), "--device", "dev-a", "--json",
                    "--tls", "--expect-fingerprint", fingerprint.ToUpperInvariant());
                return Task.CompletedTask;
            });

        Assert.Equal(CliExitCode.Success, serve.exitCode);
        using JsonDocument served = ParseSingleDocument(serve.stdout);
        JsonElement servedRoot = served.RootElement;
        Assert.True(servedRoot.GetProperty("tls").GetBoolean());
        Assert.Equal(64, servedRoot.GetProperty("certificateFingerprint").GetString()!.Length);
        Assert.Equal(JsonValueKind.Null, servedRoot.GetProperty("expectFingerprint").ValueKind);
        Assert.Equal("dev-a", servedRoot.GetProperty("from").GetString());

        Assert.Equal(CliExitCode.Success, send.sendExit);
        using JsonDocument sent = ParseSingleDocument(send.sendStdout);
        JsonElement sentRoot = sent.RootElement;
        Assert.True(sentRoot.GetProperty("tls").GetBoolean());

        // The pin is echoed lower-cased so JSON and text can never disagree about its case.
        string echo = sentRoot.GetProperty("expectFingerprint").GetString()!;
        Assert.Equal(echo.ToLowerInvariant(), echo);

        // A challenge-less envelope is one chunk frame whether TLS wraps it or not.
        Assert.Equal(1, sentRoot.GetProperty("frames").GetInt32());
        Assert.Contains(FingerprintMarker, serve.stderr, StringComparison.Ordinal);

        // A preview writes nothing, so no store file may appear on the TLS path either.
        Assert.False(File.Exists(Path.Combine(rootB, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task Text_mode_prints_the_fingerprint_for_a_human()
    {
        (int exitCode, string stdout, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--tls"],
            tls: true,
            (port, fingerprint) => SendPinnedAsync(port, fingerprint!));

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains(FingerprintMarker, stdout, StringComparison.Ordinal);
        Assert.Contains("served one connection on 127.0.0.1:", stdout, StringComparison.Ordinal);
        Assert.Contains("tls: yes (TLS 1.3, ephemeral certificate)", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_wrong_pin_fails_before_the_envelope_crosses()
    {
        string rootB = DeviceRoot("b");
        (int sendExit, string sendStdout, string sendStderr) send = (0, string.Empty, string.Empty);

        (int exitCode, string serveStdout, string serveStderr) serve = await ServeWithAsync(
            ["transfer", "serve", "--root", rootB, "--device", "dev-b", "--tls", "--json"],
            tls: true,
            (port, _) =>
            {
                send = Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", DeviceRoot("a"), "--device", "dev-a", "--json",
                    "--tls", "--expect-fingerprint", new string('b', 64));
                return Task.CompletedTask;
            });

        Assert.Equal(CliExitCode.InputProblem, send.sendExit);
        using JsonDocument document = ParseSingleDocument(send.sendStdout);
        Assert.Equal(SourceSyncTlsReasons.FingerprintMismatch, document.RootElement.GetProperty("error").GetProperty("code").GetString());

        // The listener saw a client that walked away mid-handshake: a TLS failure, neither success
        // nor an applied envelope.
        Assert.Equal(CliExitCode.InputProblem, serve.exitCode);
        Assert.False(File.Exists(Path.Combine(rootB, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task A_plaintext_send_against_a_tls_serve_is_refused()
    {
        (int exitCode, string stdout, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--tls", "--json"],
            tls: true,
            async (port, _) =>
            {
                // Deliberately the plaintext client: the listener must notice and refuse it.
                await SourceSyncTransferClient.SendAsync("127.0.0.1", port, Payload("dev-a", Source("src-1", "Home")));
            });

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, document.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Tls_and_the_tag_frame_work_together()
    {
        using EnvScope secret = new("correct horse battery staple");
        (int sendExit, string sendStdout, string sendStderr) send = (0, string.Empty, string.Empty);
        (int exitCode, string stdout, string stderr) serve = (0, string.Empty, string.Empty);

        serve = await ServeWithAsync(
            ["transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--tls", "--json",
                "--authenticate", "--secret-env", secret.Name],
            tls: true,
            (port, fingerprint) =>
            {
                send = Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", DeviceRoot("a"), "--device", "dev-a", "--json",
                    "--tls", "--expect-fingerprint", fingerprint!,
                    "--authenticate", "--secret-env", secret.Name);
                return Task.CompletedTask;
            });

        Assert.True(
            serve.exitCode == CliExitCode.Success,
            "serve failed with " + serve.exitCode + " / send " + send.sendExit
                + " | serve stdout: " + serve.stdout + " | serve stderr: " + serve.stderr
                + " | send stdout: " + send.sendStdout + " | send stderr: " + send.sendStderr);
        using JsonDocument served = ParseSingleDocument(serve.stdout);
        Assert.True(served.RootElement.GetProperty("tls").GetBoolean());
        Assert.True(served.RootElement.GetProperty("authenticate").GetBoolean());
        Assert.Equal(secret.Name, served.RootElement.GetProperty("secretEnv").GetString());
        Assert.Equal(3, served.RootElement.GetProperty("frames").GetInt32());

        Assert.Equal(CliExitCode.Success, send.sendExit);
        using JsonDocument sent = ParseSingleDocument(send.sendStdout);
        Assert.Equal(3, sent.RootElement.GetProperty("frames").GetInt32());

        // Neither channel may carry the secret value, only its variable name.
        Assert.DoesNotContain("correct horse battery staple", serve.stdout, StringComparison.Ordinal);
        Assert.DoesNotContain("correct horse battery staple", serve.stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("correct horse battery staple", send.sendStdout, StringComparison.Ordinal);
        Assert.DoesNotContain("correct horse battery staple", send.sendStderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Roundtrip_over_tls_pins_itself()
    {
        string root = DeviceRoot("rt");
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--tls", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.True(root_.GetProperty("tls").GetBoolean());
        JsonElement rounds = root_.GetProperty("rounds");
        Assert.Equal(2, rounds.GetArrayLength());
        Assert.Equal("dev-a", rounds[0].GetProperty("from").GetString());
        Assert.Equal("dev-b", rounds[0].GetProperty("to").GetString());
        Assert.Equal("dev-b", rounds[1].GetProperty("from").GetString());
        Assert.False(rounds[0].TryGetProperty("reason", out _));

        // A roundtrip prints nothing: it pins the listener it bound a moment earlier, so there is no
        // fingerprint an operator has to copy anywhere.
        Assert.DoesNotContain(FingerprintMarker, stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Without_tls_every_tls_field_stays_null_and_false()
    {
        (int exitCode, string stdout, string _) = Run(
            "transfer", "roundtrip", "--root", DeviceRoot("plain"), "--device-a", "dev-a", "--device-b", "dev-b", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.False(root_.GetProperty("tls").GetBoolean());
        Assert.Equal(JsonValueKind.Null, root_.GetProperty("expectFingerprint").ValueKind);
        Assert.Equal(JsonValueKind.Null, root_.GetProperty("certificateFingerprint").ValueKind);
    }

    // ---- helpers -----------------------------------------------------------------------

    private static LocalSourceRecord Source(string syncSourceId, string displayName, long revision = 1) => new()
    {
        ServerId = "srv-" + syncSourceId,
        SyncSourceId = syncSourceId,
        Type = ServerType.Emby,
        SourceTypeRaw = SyncSourceTypes.Emby,
        Url = EmbyUrl,
        DisplayName = displayName,
        RecordRevision = revision,
    };

    private static byte[] Payload(string deviceId, params LocalSourceRecord[] records)
    {
        InMemoryLocalSourceStore store = new();
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }

        SourceSyncPeerRound sender = new(deviceId, store);
        return Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(sender.Export()));
    }

    private static async Task SendPinnedAsync(int port, string fingerprint)
    {
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            port,
            Payload("dev-a", Source("src-1", "Home")),
            challenge: null,
            timeout: TimeSpan.FromSeconds(10),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(fingerprint));
        Assert.True(sent.Succeeded, "the pinned client could not deliver the envelope: " + sent.Reason);
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static JsonDocument ParseSingleDocument(string stdout)
    {
        string trimmed = stdout.Trim();
        Assert.False(trimmed.Length == 0, "stdout carried no JSON document");
        Assert.DoesNotContain("\n", trimmed, StringComparison.Ordinal);
        return JsonDocument.Parse(trimmed);
    }

    private static async Task<(int ExitCode, string Stdout, string Stderr)> ServeWithAsync(
        string[] serveArgs,
        bool tls,
        Func<int, string?, Task> useConnection)
    {
        CaptureWriter stdout = new();
        CaptureWriter stderr = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(serveArgs, stdout, stderr, () => FixedNow));
        (int port, string? fingerprint) = await WaitForStartAsync(stderr, tls);
        try
        {
            await useConnection(port, fingerprint);
        }
        catch
        {
            // Never leave a listener behind for the rest of the run.
            await serving.WaitAsync(TimeSpan.FromSeconds(10)).ConfigureAwait(false);
            throw;
        }

        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30));
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static async Task<(int Port, string? Fingerprint)> WaitForStartAsync(CaptureWriter stderr, bool tls)
    {
        // 200 * 25 ms = 5 s: enough for a cold start on a loaded box, short enough to fail loudly.
        for (int attempt = 0; attempt < 200; attempt++)
        {
            string text = stderr.ToString();
            int port = ParsePort(text);
            string? fingerprint = tls ? ParseFingerprint(text) : null;
            if (port > 0 && (!tls || fingerprint is not null))
            {
                return (port, fingerprint);
            }

            await Task.Delay(25);
        }

        throw new InvalidOperationException("the serve command never announced its port and fingerprint: " + stderr.ToString());
    }

    private static int ParsePort(string text)
    {
        int index = text.IndexOf(ListeningMarker, StringComparison.Ordinal);
        if (index < 0)
        {
            return 0;
        }

        int start = index + ListeningMarker.Length;
        int end = start;
        while (end < text.Length && char.IsDigit(text[end]))
        {
            end++;
        }

        return end > start ? int.Parse(text[start..end], CultureInfo.InvariantCulture) : 0;
    }

    private static string? ParseFingerprint(string text)
    {
        int index = text.IndexOf(FingerprintMarker, StringComparison.Ordinal);
        if (index < 0)
        {
            return null;
        }

        int start = index + FingerprintMarker.Length;
        int end = start;
        while (end < text.Length && Uri.IsHexDigit(text[end]))
        {
            end++;
        }

        string fingerprint = text[start..end];
        return fingerprint.Length == 64 ? fingerprint : null;
    }

    /// <summary>An environment variable that exists only for the duration of one test.</summary>
    private sealed class EnvScope : IDisposable
    {
        private readonly string? _previous;

        public EnvScope(string value)
        {
            Name = "MH_W626_" + Guid.NewGuid().ToString("N");
            _previous = Environment.GetEnvironmentVariable(Name);
            Environment.SetEnvironmentVariable(Name, value);
        }

        public string Name { get; }

        public void Dispose() => Environment.SetEnvironmentVariable(Name, _previous);
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

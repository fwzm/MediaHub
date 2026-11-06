using System;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-37: <c>--send-receipt</c> on <c>transfer serve</c> and <c>--await-receipt</c> on <c>transfer send</c>.
/// Each test either asserts a usage error with no socket at all, or runs the real command in-process and
/// speaks the frame protocol to it over 127.0.0.1. Nothing here opens a window.
/// </summary>
public sealed class SyncCliReceiptTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string ListeningMarker = "listening 127.0.0.1:";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-receipt-" + Guid.NewGuid().ToString("N"));

    public SyncCliReceiptTests()
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

    // ---- 1. which verb may carry which flag --------------------------------------------

    [Fact]
    public void Send_receipt_only_parses_on_transfer_serve()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("a"), "--device", "dev-a", "--send-receipt");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--send-receipt:transfer-send", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Await_receipt_only_parses_on_transfer_send()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("b"), "--device", "dev-b", "--await-receipt");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--await-receipt:transfer-serve", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Receipt_flags_take_no_value()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("c"), "--device", "dev-c", "--send-receipt=1");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("flag-takes-no-value:--send-receipt", stderr, StringComparison.Ordinal);

        (int exitCode2, string _, string stderr2) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("c"), "--device", "dev-c", "--await-receipt=yes");

        Assert.Equal(CliExitCode.UsageError, exitCode2);
        Assert.Contains("flag-takes-no-value:--await-receipt", stderr2, StringComparison.Ordinal);
    }

    [Fact]
    public void Help_names_the_two_receipt_flags_and_says_they_are_off_by_default()
    {
        (int exitCode, string stdout, string _) = Run("transfer", "--help");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains("--send-receipt", stdout, StringComparison.Ordinal);
        Assert.Contains("--await-receipt", stdout, StringComparison.Ordinal);
        Assert.Contains("receipt-missing", stdout, StringComparison.Ordinal);
        Assert.Contains("off by default", stdout, StringComparison.Ordinal);
    }

    // ---- 2. a real serve and a real send over the loopback ------------------------------

    [Fact]
    public async Task Json_mode_reports_the_written_and_the_received_receipt()
    {
        string sender = DeviceRoot("sender");
        string receiver = DeviceRoot("receiver");
        Seed(sender);

        (int serveExit, string serveStdout, string _) = await ServeWithAsync(
            new[]
            {
                "transfer", "serve", "--root", receiver, "--device", "dev-b", "--apply", "--send-receipt", "--json",
            },
            async port =>
            {
                (int sendExit, string sendStdout, string sendStderr) = await Task.Run(() => Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", sender, "--device", "dev-a", "--await-receipt", "--json"));
                Assert.True(sendExit == CliExitCode.Success, "the send failed: " + sendStderr);

                using JsonDocument sentDocument = ParseSingleDocument(sendStdout);
                JsonElement sent = sentDocument.RootElement;
                Assert.True(sent.GetProperty("awaitReceipt").GetBoolean());
                Assert.False(sent.GetProperty("sendReceipt").GetBoolean());
                Assert.True(sent.GetProperty("receiptConfirmed").GetBoolean());
                Assert.Equal(1, sent.GetProperty("frames").GetInt32());
            });

        Assert.Equal(CliExitCode.Success, serveExit);
        using JsonDocument servedDocument = ParseSingleDocument(serveStdout);
        JsonElement served = servedDocument.RootElement;
        Assert.True(served.GetProperty("sendReceipt").GetBoolean());
        Assert.False(served.GetProperty("awaitReceipt").GetBoolean());
        Assert.True(served.GetProperty("receiptWritten").GetBoolean());
        Assert.True(served.GetProperty("applied").GetBoolean());
        Assert.Equal(1, served.GetProperty("report").GetProperty("applied").GetInt32());
    }

    [Fact]
    public async Task Text_mode_prints_both_receipt_lines()
    {
        string sender = DeviceRoot("sender-text");
        string receiver = DeviceRoot("receiver-text");
        Seed(sender);

        (int serveExit, string serveStdout, string _) = await ServeWithAsync(
            new[] { "transfer", "serve", "--root", receiver, "--device", "dev-b", "--apply", "--send-receipt" },
            async port =>
            {
                (int sendExit, string sendStdout, string sendStderr) = await Task.Run(() => Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", sender, "--device", "dev-a", "--await-receipt"));
                Assert.True(sendExit == CliExitCode.Success, "the send failed: " + sendStderr);
                Assert.Contains("receipt: received (0x04) - the peer decoded this envelope", sendStdout, StringComparison.Ordinal);
            });

        Assert.Equal(CliExitCode.Success, serveExit);
        Assert.Contains("receipt: written (0x04) - the sender can tell understood from closed", serveStdout, StringComparison.Ordinal);
    }

    // ---- 3. the three ways the wait fails ------------------------------------------------

    [Fact]
    public async Task A_peer_that_closes_without_a_receipt_is_receipt_missing()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            // Read until the peer stops writing, then close: understood, but not acknowledged.
            await DrainAsync(stream, token);
        });

        string root = DeviceRoot("missing");
        Seed(root);
        (int exitCode, string stdout, string stderr) = await Task.Run(() => Run(
            "transfer", "send", "--to", "127.0.0.1:" + peer.Port.ToString(CultureInfo.InvariantCulture),
            "--root", root, "--device", "dev-a", "--await-receipt", "--json"));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("receipt-missing", stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal("receipt-missing", document.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_peer_that_holds_the_connection_open_is_receipt_timeout()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            await DrainAsync(stream, token);
            await Task.Delay(TimeSpan.FromSeconds(6), token);
        });

        string root = DeviceRoot("timeout");
        Seed(root);
        (int exitCode, string stdout, string stderr) = await Task.Run(() => Run(
            "transfer", "send", "--to", "127.0.0.1:" + peer.Port.ToString(CultureInfo.InvariantCulture),
            "--root", root, "--device", "dev-a", "--await-receipt", "--json"));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("receipt-timeout", stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal("receipt-timeout", document.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task A_peer_that_answers_with_a_wrong_byte_is_receipt_invalid()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            await DrainAsync(stream, token);
            await stream.WriteAsync(new byte[] { 0x05 }, token);
            await stream.FlushAsync(token);
            await Task.Delay(TimeSpan.FromMilliseconds(100), token);
        });

        string root = DeviceRoot("invalid");
        Seed(root);
        (int exitCode, string stdout, string stderr) = await Task.Run(() => Run(
            "transfer", "send", "--to", "127.0.0.1:" + peer.Port.ToString(CultureInfo.InvariantCulture),
            "--root", root, "--device", "dev-a", "--await-receipt", "--json"));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("receipt-invalid", stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal("receipt-invalid", document.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    // ---- 4. composed with the W6-24 tag, and the default -------------------------------

    [Fact]
    public async Task Authentication_and_the_receipt_keep_the_secret_out_of_the_logs()
    {
        string secret = "correct horse battery staple";
        using EnvScope environment = new(secret);
        string sender = DeviceRoot("auth-sender");
        string receiver = DeviceRoot("auth-receiver");
        Seed(sender);

        (int serveExit, string serveStdout, string serveStderr) = await ServeWithAsync(
            new[]
            {
                "transfer", "serve", "--root", receiver, "--device", "dev-b", "--apply",
                "--authenticate", "--secret-env", environment.Name, "--send-receipt", "--json",
            },
            async port =>
            {
                (int sendExit, string sendStdout, string sendStderr) = await Task.Run(() => Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", sender, "--device", "dev-a",
                    "--authenticate", "--secret-env", environment.Name, "--await-receipt", "--json"));
                Assert.True(sendExit == CliExitCode.Success, "the send failed: " + sendStderr);

                using JsonDocument sentDocument = ParseSingleDocument(sendStdout);
                JsonElement sent = sentDocument.RootElement;
                Assert.True(sent.GetProperty("authenticate").GetBoolean());
                Assert.Equal(environment.Name, sent.GetProperty("secretEnv").GetString());
                Assert.True(sent.GetProperty("receiptConfirmed").GetBoolean());
                // challenge + one chunk + tag
                Assert.Equal(3, sent.GetProperty("frames").GetInt32());
                Assert.DoesNotContain(secret, sendStdout, StringComparison.Ordinal);
                Assert.DoesNotContain(secret, sendStderr, StringComparison.Ordinal);
            });

        Assert.Equal(CliExitCode.Success, serveExit);
        using JsonDocument servedDocument = ParseSingleDocument(serveStdout);
        JsonElement served = servedDocument.RootElement;
        Assert.True(served.GetProperty("authenticate").GetBoolean());
        Assert.True(served.GetProperty("receiptWritten").GetBoolean());
        Assert.DoesNotContain(secret, serveStdout, StringComparison.Ordinal);
        Assert.DoesNotContain(secret, serveStderr, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Without_the_flags_the_receipt_fields_stay_false_and_null()
    {
        string sender = DeviceRoot("plain-sender");
        string receiver = DeviceRoot("plain-receiver");
        Seed(sender);

        (int serveExit, string serveStdout, string _) = await ServeWithAsync(
            new[] { "transfer", "serve", "--root", receiver, "--device", "dev-b", "--apply", "--json" },
            async port =>
            {
                (int sendExit, string sendStdout, string sendStderr) = await Task.Run(() => Run(
                    "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--root", sender, "--device", "dev-a", "--json"));
                Assert.True(sendExit == CliExitCode.Success, "the send failed: " + sendStderr);

                using JsonDocument sentDocument = ParseSingleDocument(sendStdout);
                JsonElement sent = sentDocument.RootElement;
                Assert.False(sent.GetProperty("sendReceipt").GetBoolean());
                Assert.False(sent.GetProperty("awaitReceipt").GetBoolean());
                Assert.Equal(JsonValueKind.Null, sent.GetProperty("receiptConfirmed").ValueKind);
            });

        Assert.Equal(CliExitCode.Success, serveExit);
        using JsonDocument servedDocument = ParseSingleDocument(serveStdout);
        JsonElement served = servedDocument.RootElement;
        Assert.False(served.GetProperty("sendReceipt").GetBoolean());
        Assert.False(served.GetProperty("awaitReceipt").GetBoolean());
        Assert.Equal(JsonValueKind.Null, served.GetProperty("receiptWritten").ValueKind);
    }

    // ---- helpers -----------------------------------------------------------------------

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
        Func<int, Task> useConnection)
    {
        CaptureWriter stdout = new();
        CaptureWriter stderr = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(serveArgs, stdout, stderr, () => FixedNow));
        int port = await WaitForPortAsync(stderr);
        try
        {
            await useConnection(port);
        }
        catch
        {
            // Never leave a listener behind for the rest of the run.
            await serving.WaitAsync(TimeSpan.FromSeconds(10)).ConfigureAwait(false);
            throw;
        }

        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30)).ConfigureAwait(false);
        return (exitCode, stdout.Text, stderr.Text);
    }

    private static async Task<int> WaitForPortAsync(CaptureWriter stderr)
    {
        // 200 * 25 ms = 5 s: enough for a cold start, short enough to fail loudly.
        for (int attempt = 0; attempt < 200; attempt++)
        {
            string text = stderr.Text;
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

            await Task.Delay(25).ConfigureAwait(false);
        }

        throw new TimeoutException("the serve command never announced its port: " + stderr.Text);
    }

    private static void Seed(string directory)
    {
        Directory.CreateDirectory(directory);
        // The store file is a JSON array, exactly what FileLocalSourceStore writes.
        const string Json = "[{\"serverId\":\"srv-emby\",\"type\":\"Emby\",\"sourceTypeRaw\":\"emby\",\"url\":\"http://nas-a:8096/emby/\",\"accountScope\":\"alice\",\"displayName\":\"Living Room Emby\",\"recordRevision\":3}]";
        File.WriteAllText(Path.Combine(directory, "sync-sources.json"), Json, new UTF8Encoding(false));
    }

    /// <summary>Reads until the sending side stops for a moment, so a later close is not a mid-frame reset.</summary>
    private static async Task DrainAsync(Stream stream, CancellationToken token)
    {
        byte[] buffer = new byte[4096];
        while (!token.IsCancellationRequested)
        {
            using CancellationTokenSource quiet = CancellationTokenSource.CreateLinkedTokenSource(token);
            quiet.CancelAfter(TimeSpan.FromMilliseconds(300));
            int read;
            try
            {
                read = await stream.ReadAsync(buffer.AsMemory(), quiet.Token);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            if (read <= 0)
            {
                return;
            }
        }
    }

    /// <summary>A one-connection loopback peer that never speaks the CLI's protocol.</summary>
    private sealed class RawPeer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _cts = new();
        private readonly Task _accepted;

        public RawPeer(Func<Stream, CancellationToken, Task> handler)
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            _accepted = AcceptAsync(handler);
        }

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public async ValueTask DisposeAsync()
        {
            _cts.Cancel();
            _listener.Stop();
            try
            {
                await _accepted.WaitAsync(TimeSpan.FromSeconds(5));
            }
            catch (Exception)
            {
                // A handler that ends by closing the socket is the normal path.
            }

            _cts.Dispose();
        }

        private async Task AcceptAsync(Func<Stream, CancellationToken, Task> handler)
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync(_cts.Token);
                using NetworkStream stream = client.GetStream();
                await handler(stream, _cts.Token);
            }
            catch (Exception)
            {
                // The assertions ran against the CLI's own output.
            }
        }
    }

    /// <summary>An environment variable that exists only for the duration of one test.</summary>
    private sealed class EnvScope : IDisposable
    {
        private readonly string? _previous;

        public EnvScope(string value)
        {
            Name = "MH_W637_" + Guid.NewGuid().ToString("N");
            _previous = Environment.GetEnvironmentVariable(Name);
            Environment.SetEnvironmentVariable(Name, value);
        }

        public string Name { get; }

        public void Dispose() => Environment.SetEnvironmentVariable(Name, _previous);
    }

    /// <summary>A <see cref="TextWriter"/> the test may read while the command is still writing.</summary>
    private sealed class CaptureWriter : TextWriter
    {
        private readonly StringBuilder _text = new();

        public override Encoding Encoding => Encoding.UTF8;

        public string Text
        {
            get
            {
                lock (_text)
                {
                    return _text.ToString();
                }
            }
        }

        public override void Write(char value)
        {
            lock (_text)
            {
                _text.Append(value);
            }
        }

        public override void Write(string? value)
        {
            lock (_text)
            {
                _text.Append(value);
            }
        }

        public override void WriteLine(string? value)
        {
            lock (_text)
            {
                _text.Append(value);
                _text.Append('\n');
            }
        }
    }
}

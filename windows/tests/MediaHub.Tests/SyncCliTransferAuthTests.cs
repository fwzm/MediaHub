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
/// W6-24: <c>transfer --authenticate --secret-env NAME</c>. The secret itself is never an argument:
/// every test below puts it in an environment variable whose name is unique to that test, and the
/// leak assertions check the value never reaches stdout or stderr.
/// </summary>
public sealed class SyncCliTransferAuthTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string ListeningMarker = "listening 127.0.0.1:";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-auth-" + Guid.NewGuid().ToString("N"));

    public SyncCliTransferAuthTests()
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

    // ---- 1. the flags -------------------------------------------------------------------------

    [Fact]
    public void Authenticate_WithoutSecretEnv_IsAUsageError()
    {
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev-a", "--authenticate");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-required-option:--secret-env", stderr, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(_root, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public void SecretEnv_WithoutAuthenticate_IsAUsageError()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", _root, "--device", "dev-a", "--secret-env", "X");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-required-option:--authenticate", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Authenticate_IsRefusedByCommandsThatOpenNoSocket()
    {
        (int exitCode, string _, string stderr) = Run("export", "--root", _root, "--authenticate");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--authenticate:export", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void SecretEnv_IsRefusedByCommandsThatOpenNoSocket()
    {
        (int exitCode, string _, string stderr) = Run("export", "--root", _root, "--secret-env", "X");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--secret-env:export", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Authenticate_TakesNoValue()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev-a", "--authenticate=1");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("flag-takes-no-value:--authenticate", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void TheHelpText_DocumentsBothFlags()
    {
        (int exitCode, string stdout, string _) = Run("transfer", "--help");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains("--authenticate", stdout, StringComparison.Ordinal);
        Assert.Contains("--secret-env NAME", stdout, StringComparison.Ordinal);
        Assert.Contains("off by default", stdout, StringComparison.Ordinal);
    }

    // ---- 2. the secret never leaves the environment --------------------------------------------

    [Fact]
    public void AnUnsetEnvironmentVariableIsReportedByNameOnly()
    {
        string name = UniqueName();
        string root = Path.Combine(_root, "a");

        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--root", root, "--device", "dev-a", "--to", "127.0.0.1:1",
            "--authenticate", "--secret-env", name);

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains(SourceSyncTransferAuthReasons.AuthSecretUnavailable, stderr, StringComparison.Ordinal);
        Assert.Contains(name, stderr, StringComparison.Ordinal);

        // The secret is read before any store is opened, so a failed read must not create the root.
        Assert.False(Directory.Exists(root));
    }

    [Fact]
    public void AnInvalidEnvironmentVariableNameIsReportedByNameOnly()
    {
        // Windows rejects '=' inside a variable name, so the read throws; the CLI must turn that into
        // a stable code instead of an unhandled exception.
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev-a", "--authenticate", "--secret-env", "BAD=NAME");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains(SourceSyncTransferAuthReasons.AuthSecretUnavailable, stderr, StringComparison.Ordinal);
        Assert.DoesNotContain(ListeningMarker, stderr, StringComparison.Ordinal);
    }

    // ---- 3. two ends that share the secret -----------------------------------------------------

    [Fact]
    public async Task AnAuthenticatedSendIsAcceptedByAnAuthenticatedServe()
    {
        string serveRoot = Path.Combine(_root, "b");
        string sendRoot = Path.Combine(_root, "a");
        Seed(sendRoot, Source("src-1", "Home"));
        string name = UniqueName();
        string secret = "shared-" + Guid.NewGuid().ToString("N");
        using EnvScope env = new(name, secret);

        (int serveExit, string serveOut, string serveErr) = await ServeWithAsync(
            ["transfer", "serve", "--root", serveRoot, "--device", "dev-b", "--json", "--authenticate", "--secret-env", name],
            port => Task.FromResult(Run(
                "transfer", "send", "--root", sendRoot, "--device", "dev-a",
                "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                "--json", "--authenticate", "--secret-env", name)));

        Assert.Equal(CliExitCode.Success, serveExit);
        using JsonDocument served = ParseSingleDocument(serveOut);
        Assert.Equal("dev-a", served.RootElement.GetProperty("from").GetString());
        Assert.True(served.RootElement.GetProperty("authenticate").GetBoolean());
        Assert.Equal(name, served.RootElement.GetProperty("secretEnv").GetString());
        Assert.Equal(3, served.RootElement.GetProperty("frames").GetInt32());

        // The secret must never reach either stream, in any form.
        Assert.DoesNotContain(secret, serveOut, StringComparison.Ordinal);
        Assert.DoesNotContain(secret, serveErr, StringComparison.Ordinal);
    }

    [Fact]
    public async Task TheSendSideReportsTheSameFrameCountAndNeverPrintsTheSecret()
    {
        string serveRoot = Path.Combine(_root, "b");
        string sendRoot = Path.Combine(_root, "a");
        Seed(sendRoot, Source("src-1", "Home"));
        string name = UniqueName();
        string secret = "shared-" + Guid.NewGuid().ToString("N");
        using EnvScope env = new(name, secret);

        (int ExitCode, string Stdout, string Stderr) client = (CliExitCode.UsageError, string.Empty, string.Empty);
        (int serveExit, string _, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", serveRoot, "--device", "dev-b", "--json", "--authenticate", "--secret-env", name],
            port =>
            {
                client = Run(
                    "transfer", "send", "--root", sendRoot, "--device", "dev-a",
                    "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                    "--json", "--authenticate", "--secret-env", name);
                return Task.FromResult(client);
            });

        Assert.Equal(CliExitCode.Success, serveExit);
        Assert.Equal(CliExitCode.Success, client.ExitCode);
        using JsonDocument document = ParseSingleDocument(client.Stdout);
        Assert.True(document.RootElement.GetProperty("authenticate").GetBoolean());
        Assert.Equal(name, document.RootElement.GetProperty("secretEnv").GetString());
        Assert.Equal(3, document.RootElement.GetProperty("frames").GetInt32());
        Assert.DoesNotContain(secret, client.Stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(secret, client.Stderr, StringComparison.Ordinal);
    }

    [Fact]
    public async Task TheTextModeServeSaysThatThePeerProvedItsSecret()
    {
        string serveRoot = Path.Combine(_root, "b");
        string sendRoot = Path.Combine(_root, "a");
        Seed(sendRoot, Source("src-1", "Home"));
        string name = UniqueName();
        string secret = "shared-" + Guid.NewGuid().ToString("N");
        using EnvScope env = new(name, secret);

        (int serveExit, string serveOut, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", serveRoot, "--device", "dev-b", "--authenticate", "--secret-env", name],
            port => Task.FromResult(Run(
                "transfer", "send", "--root", sendRoot, "--device", "dev-a",
                "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                "--authenticate", "--secret-env", name)));

        Assert.Equal(CliExitCode.Success, serveExit);
        Assert.Contains("authenticated: yes (secret read from " + name + ")", serveOut, StringComparison.Ordinal);
        Assert.DoesNotContain(secret, serveOut, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AClientThatHoldsADifferentSecretIsRefused()
    {
        string serveRoot = Path.Combine(_root, "b");
        string sendRoot = Path.Combine(_root, "a");
        Seed(sendRoot, Source("src-1", "Home"));
        string serveName = UniqueName();
        string sendName = UniqueName();
        using EnvScope serveEnv = new(serveName, "server-" + Guid.NewGuid().ToString("N"));
        using EnvScope sendEnv = new(sendName, "client-" + Guid.NewGuid().ToString("N"));

        (int serveExit, string serveOut, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", serveRoot, "--device", "dev-b", "--json", "--authenticate", "--secret-env", serveName],
            port => Task.FromResult(Run(
                "transfer", "send", "--root", sendRoot, "--device", "dev-a",
                "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                "--json", "--authenticate", "--secret-env", sendName)));

        Assert.Equal(CliExitCode.InputProblem, serveExit);
        using JsonDocument document = ParseSingleDocument(serveOut);
        Assert.False(document.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal(
            SourceSyncTransferAuthReasons.AuthMismatch,
            document.RootElement.GetProperty("error").GetProperty("code").GetString());

        // Nothing was accepted, so the receiver did not even write its store.
        Assert.False(File.Exists(Path.Combine(serveRoot, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public void AnAuthenticatedRoundtripSucceedsInBothDirections()
    {
        string root = Path.Combine(_root, "rt");
        Seed(Path.Combine(root, "a"), Source("src-a", "Home"));
        Seed(Path.Combine(root, "b"), Source("src-b", "Work"));
        string name = UniqueName();
        string secret = "shared-" + Guid.NewGuid().ToString("N");
        using EnvScope env = new(name, secret);

        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b",
            "--apply", "--json", "--authenticate", "--secret-env", name);

        Assert.True(exitCode == CliExitCode.Success, "roundtrip failed: " + stderr);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement rootElement = document.RootElement;
        Assert.True(rootElement.GetProperty("authenticate").GetBoolean());
        Assert.Equal(name, rootElement.GetProperty("secretEnv").GetString());
        Assert.Equal(2, rootElement.GetProperty("rounds").GetArrayLength());
        Assert.Equal(3, rootElement.GetProperty("rounds")[0].GetProperty("frames").GetInt32());
        Assert.Equal(3, rootElement.GetProperty("rounds")[1].GetProperty("frames").GetInt32());
        Assert.DoesNotContain(secret, stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(secret, stderr, StringComparison.Ordinal);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static string UniqueName() => "MH_W624_" + Guid.NewGuid().ToString("N");

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
        Func<int, Task<(int ExitCode, string Stdout, string Stderr)>> useConnection)
    {
        CaptureWriter stdout = new();
        CaptureWriter stderr = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(serveArgs, stdout, stderr, () => FixedNow));
        int port = await WaitForListeningPortAsync(stderr);
        try
        {
            useConnection(port).GetAwaiter().GetResult();
        }
        catch
        {
            await serving.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
            throw;
        }

        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30));
        return (exitCode, stdout.ToString(), stderr.ToString());
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

        throw new InvalidOperationException("the serve command never announced its port: " + stderr.ToString());
    }

    /// <summary>Sets one environment variable for the life of the test and restores the previous value.</summary>
    private sealed class EnvScope : IDisposable
    {
        private readonly string _name;
        private readonly string? _previous;

        public EnvScope(string name, string value)
        {
            _name = name;
            _previous = Environment.GetEnvironmentVariable(name);
            Environment.SetEnvironmentVariable(name, value);
        }

        public void Dispose() => Environment.SetEnvironmentVariable(_name, _previous);
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

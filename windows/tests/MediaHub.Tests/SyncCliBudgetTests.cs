using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-50: <c>--budget-ms</c> on <c>transfer serve</c>, which used to be a hard-coded fifteen seconds.
/// Every test here either parses an argument vector (no socket at all) or runs the real command
/// in-process against 127.0.0.1; nothing opens a window and nothing writes into the user's real
/// settings folder.
/// </summary>
public sealed class SyncCliBudgetTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string ListeningMarker = "listening 127.0.0.1:";
    private const string BudgetMarker = "budget: ";
    private const string TimeoutCode = "transfer-timeout";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-budget-" + Guid.NewGuid().ToString("N"));

    public SyncCliBudgetTests()
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

    private string[] ServeArgs(params string[] extra)
    {
        string[] fixedArgs =
        [
            "transfer", "serve", "--root", DeviceRoot("serve"), "--device", "dev-b",
        ];
        string[] combined = new string[fixedArgs.Length + extra.Length];
        fixedArgs.CopyTo(combined, 0);
        extra.CopyTo(combined, fixedArgs.Length);
        return combined;
    }

    // ---- 1. the default and the bounds --------------------------------------------------

    [Fact]
    public void Serve_defaults_to_fifteen_seconds_when_the_option_is_absent()
    {
        CliParseResult parsed = CliParser.Parse(ServeArgs());

        Assert.Null(parsed.Error);
        Assert.NotNull(parsed.Invocation);

        // Null, not 15000: the "no option given" invocation has to stay byte-for-byte the one the
        // pre-W6-50 CLI built, and the runner substitutes the documented default.
        Assert.Null(parsed.Invocation!.BudgetMilliseconds);
    }

    [Fact]
    public void Serve_accepts_the_floor_and_the_ceiling()
    {
        Assert.Equal(100, ParsedBudget("100"));
        Assert.Equal(600_000, ParsedBudget("600000"));

        // Any value inside the range is a plain number, and the --name=value form is the same thing.
        Assert.Equal(1_500, ParsedBudget("1500"));
        Assert.Equal(1_500, ParsedBudget("=1500"));
    }

    // ---- 2. the values that are refused -------------------------------------------------

    [Theory]
    [InlineData("0", "invalid-budget-ms:0")]
    [InlineData("99", "invalid-budget-ms:99")]
    [InlineData("600001", "invalid-budget-ms:600001")]
    [InlineData("-1", "invalid-budget-ms:-1")]
    [InlineData("abc", "invalid-budget-ms:abc")]
    [InlineData("15s", "invalid-budget-ms:15s")]
    public void Out_of_range_and_non_numeric_budgets_are_usage_errors(string value, string expected)
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("bad"), "--device", "dev-b", "--budget-ms", value);

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains(expected, stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void An_empty_budget_is_refused_by_the_generic_empty_value_rule()
    {
        // "" never reaches the range check: the option loop already refuses a blank value for every
        // option, so the CLI keeps one wording for "you passed nothing" everywhere.
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("empty"), "--device", "dev-b", "--budget-ms", string.Empty);

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("empty-value:--budget-ms", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("invalid-budget-ms", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void A_budget_without_a_value_is_refused()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("missing"), "--device", "dev-b", "--budget-ms");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-value:--budget-ms", stderr, StringComparison.Ordinal);
    }

    // ---- 3. the option belongs to serve and to nothing else -----------------------------

    [Fact]
    public void Send_refuses_the_budget()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", DeviceRoot("a"), "--device", "dev-a",
            "--budget-ms", "1000");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--budget-ms:transfer-send", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Roundtrip_refuses_the_budget()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "roundtrip", "--root", DeviceRoot("rt"), "--device-a", "dev-a", "--device-b", "dev-b",
            "--budget-ms", "1000");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains(
            "option-not-allowed-for-command:--budget-ms:transfer-roundtrip", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Pair_refuses_the_budget()
    {
        (int exitCode, string _, string stderr) = Run("pair", "link", "--budget-ms", "1000");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--budget-ms:pair-link", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Export_refuses_the_budget()
    {
        // The task book calls this verb "library"; this CLI's local verb is export, and it must refuse
        // the option for the same reason: only the half that waits for a peer has a budget to spend.
        (int exitCode, string _, string stderr) = Run("export", "--root", DeviceRoot("ex"), "--budget-ms", "1000");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--budget-ms:export", stderr, StringComparison.Ordinal);
    }

    // ---- 4. what a human sees ------------------------------------------------------------

    [Fact]
    public void Text_mode_prints_the_budget_right_after_the_listening_line()
    {
        int started = Environment.TickCount;
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("text"), "--device", "dev-b", "--budget-ms", "300");
        int elapsed = Environment.TickCount - started;

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains(TimeoutCode, stderr, StringComparison.Ordinal);

        int listening = stderr.IndexOf(ListeningMarker, StringComparison.Ordinal);
        int budget = stderr.IndexOf(BudgetMarker, StringComparison.Ordinal);
        Assert.True(listening >= 0, "the port must still be announced first: " + stderr);
        Assert.True(budget > listening, "the budget line belongs after the listening line: " + stderr);
        Assert.Contains("budget: 300 ms", stderr, StringComparison.Ordinal);

        // A failing serve prints no result document, and text mode never pretends otherwise.
        Assert.DoesNotContain("{", stdout, StringComparison.Ordinal);
        Assert.True(elapsed < 5_000, "a 300 ms budget must not take " + elapsed + " ms to run out");
    }

    [Fact]
    public void Json_mode_carries_the_budget_in_the_document_and_not_on_stderr()
    {
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("jsonfail"), "--device", "dev-b", "--budget-ms", "300",
            "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);

        // json mode: stdout stays exactly one document; the human line is text-mode only, because the
        // number a script needs is budgetMs in the success document.
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(TimeoutCode, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.DoesNotContain(BudgetMarker, stderr, StringComparison.Ordinal);
    }

    // ---- 5. the budget really is a wall clock --------------------------------------------

    [Fact]
    public void A_twelve_hundred_ms_budget_gives_up_after_about_one_second()
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "serve", "--root", DeviceRoot("wait"), "--device", "dev-b", "--budget-ms", "1200", "--json");
        stopwatch.Stop();

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("error " + TimeoutCode + ":", stderr, StringComparison.Ordinal);

        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement error = document.RootElement.GetProperty("error");
        Assert.Equal(TimeoutCode, error.GetProperty("code").GetString());
        Assert.Contains("1.2 s", error.GetProperty("message").GetString()!, StringComparison.Ordinal);

        // The number is load-bearing, in both directions: a listener that gave up instantly would not
        // have waited at all, and one that ignored the flag would sit on the old fifteen seconds.
        Assert.True(stopwatch.ElapsedMilliseconds >= 1_100, "the budget was not waited out: " + stopwatch.ElapsedMilliseconds + " ms");
        Assert.True(stopwatch.ElapsedMilliseconds < 4_000, "the budget was overshot: " + stopwatch.ElapsedMilliseconds + " ms");
    }

    // ---- 6. the number reaches a successful serve ----------------------------------------

    [Fact]
    public async Task A_successful_serve_reports_the_default_fifteen_seconds_in_json()
    {
        (int exitCode, string stdout, string stderr) = await ServeWithAsync(
            ServeArgs("--apply", "--json"),
            port => SourceSyncTransferClient.SendAsync("127.0.0.1", port, Payload("dev-a", Source("src-1", "Home"))));

        Assert.True(
            exitCode == CliExitCode.Success,
            "serve failed with " + exitCode + " | stdout: " + stdout + " | stderr: " + stderr);

        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(15_000, document.RootElement.GetProperty("budgetMs").GetInt32());

        // The additive field sits beside the keys that were already there, unchanged.
        Assert.True(document.RootElement.GetProperty("port").GetInt32() > 0);
        Assert.True(document.RootElement.GetProperty("once").GetBoolean());
        Assert.True(document.RootElement.GetProperty("applied").GetBoolean());
    }

    [Fact]
    public async Task A_successful_serve_reports_the_configured_budget_in_json()
    {
        (int exitCode, string stdout, string stderr) = await ServeWithAsync(
            ServeArgs("--apply", "--json", "--budget-ms", "30000"),
            port => SourceSyncTransferClient.SendAsync("127.0.0.1", port, Payload("dev-a", Source("src-1", "Home"))));

        Assert.True(
            exitCode == CliExitCode.Success,
            "serve failed with " + exitCode + " | stdout: " + stdout + " | stderr: " + stderr);

        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(30_000, document.RootElement.GetProperty("budgetMs").GetInt32());

        // A budget far larger than the transfer needs changes nothing else about the round: the one
        // source in the payload still lands, and the listener still stops after that single connection.
        Assert.Equal(1, document.RootElement.GetProperty("report").GetProperty("applied").GetInt32());
        Assert.True(document.RootElement.GetProperty("applied").GetBoolean());
    }

    // ---- helpers -----------------------------------------------------------------------

    private static int? ParsedBudget(string value)
    {
        // "--budget-ms=1500" has to stay one token, while the separate form needs two.
        string[] tail = value.StartsWith('=') ? ["--budget-ms" + value] : ["--budget-ms", value];
        string[] vector =
        [
            "transfer", "serve",
            "--root", Path.Combine(Path.GetTempPath(), "mhcli-budget-args"),
            "--device", "dev-b",
            .. tail,
        ];

        CliParseResult parsed = CliParser.Parse(vector);
        Assert.Null(parsed.Error);
        Assert.NotNull(parsed.Invocation);
        return parsed.Invocation!.BudgetMilliseconds;
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

        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30));
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static async Task<int> WaitForPortAsync(CaptureWriter stderr)
    {
        // 200 * 25 ms = 5 s: enough for a cold start on a loaded box, short enough to fail loudly.
        for (int attempt = 0; attempt < 200; attempt++)
        {
            int port = ParsePort(stderr.ToString());
            if (port > 0)
            {
                return port;
            }

            await Task.Delay(25).ConfigureAwait(false);
        }

        throw new InvalidOperationException("the serve command never announced its port: " + stderr.ToString());
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

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-18: the <c>transfer</c> group. Every test below either runs the real command in-process and
/// talks to it over a loopback socket, or asserts a stable code without any socket at all. Nothing
/// here opens a window, binds a non-loopback address or touches the user's real settings folder.
/// </summary>
public sealed class SyncCliTransferTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string ListeningMarker = "listening 127.0.0.1:";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-transfer-" + Guid.NewGuid().ToString("N"));

    public SyncCliTransferTests()
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

    // ---- 1. transport over a real loopback socket -------------------------------------------

    [Fact]
    public async Task Serve_PreviewsTheEnvelopeAndWritesNothing()
    {
        string root = DeviceRoot("b");
        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--json"],
            Payload("dev-a", Source("src-1", "Home")));

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains(ListeningMarker, stderr, StringComparison.Ordinal);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.Equal("transfer", root_.GetProperty("command").GetString());
        Assert.Equal("serve", root_.GetProperty("subcommand").GetString());
        Assert.False(root_.GetProperty("applied").GetBoolean());
        Assert.Equal("dev-a", root_.GetProperty("from").GetString());
        Assert.Equal("dev-b", root_.GetProperty("to").GetString());
        Assert.True(root_.GetProperty("port").GetInt32() > 0);
        Assert.Equal(1, root_.GetProperty("counts").GetProperty("apply").GetInt32());
        Assert.Equal(0, root_.GetProperty("counts").GetProperty("withheld").GetInt32());
        JsonElement item = root_.GetProperty("items")[0];
        Assert.Equal("src-1", item.GetProperty("syncSourceId").GetString());
        Assert.Equal("Create", item.GetProperty("action").GetString());
        Assert.Equal(SourceSyncReasons.NewSource, item.GetProperty("reason").GetString());

        // A preview must not leave a single file behind: not the source list, not the baseline.
        Assert.False(File.Exists(Path.Combine(root, FileLocalSourceStore.FileName)));
        Assert.False(File.Exists(Path.Combine(root, FileSourceSyncBaselineStore.FileName)));
    }

    [Fact]
    public async Task Serve_AppliesTheEnvelopeOnlyWithApply()
    {
        string root = DeviceRoot("b");
        (int exitCode, string stdout, string _) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            Payload("dev-a", Source("src-1", "Home"), Source("src-2", "Work")));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.True(root_.GetProperty("applied").GetBoolean());
        Assert.Equal(2, root_.GetProperty("report").GetProperty("applied").GetInt32());
        Assert.True(root_.GetProperty("report").GetProperty("baselineUpdated").GetBoolean());
        Assert.Equal(2, root_.GetProperty("report").GetProperty("baselineEntries").GetInt32());

        FileLocalSourceStore store = new(root);
        AssertCount(2, store.GetAll());
        Assert.Contains(store.GetAll(), record => record.SyncSourceId == "src-1" && record.DisplayName == "Home");
        FileSourceSyncBaselineStore baselines = new(root);
        Assert.True(File.Exists(baselines.FilePath));
        AssertCount(2, baselines.Load("dev-a").Entries);
    }

    [Fact]
    public async Task Serve_KeepsAnUnknownSourceTypeWithheldEvenWithApply()
    {
        string root = DeviceRoot("b");
        SourceRecord mystery = new()
        {
            SyncSourceId = "src-mystery",
            SourceType = SyncSourceTypeValue.Parse("mystery"),
            DisplayName = "Mystery",
            RecordRevision = 1,
            Endpoints = [new SourceEndpoint { Url = EmbyUrl, IsPrimary = true }],
        };

        (int exitCode, string stdout, string _) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            EnvelopeOf("dev-a", mystery));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.Equal(1, root_.GetProperty("counts").GetProperty("withheld").GetInt32());
        Assert.Equal(1, root_.GetProperty("counts").GetProperty("unsupported").GetInt32());
        JsonElement item = root_.GetProperty("items")[0];
        Assert.True(item.GetProperty("withheld").GetBoolean());
        Assert.Equal("Unsupported", item.GetProperty("action").GetString());
        Assert.Equal(0, root_.GetProperty("report").GetProperty("applied").GetInt32());

        // The gate -- not the missing flag -- is what kept this source off the disk.
        Assert.False(File.Exists(Path.Combine(root, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task Serve_ReportsEveryRefusedWriteOnStandardError()
    {
        string root = DeviceRoot("b");

        // W6-108: a directory where the store wants its temp file makes Persist fail. That is a real storage
        // failure, not a mock, and it is exactly the kind the round must report per entry instead of only counting.
        Directory.CreateDirectory(Path.Combine(root, FileLocalSourceStore.FileName + ".tmp"));

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            Payload("dev-a", Source("src-refused", "Refused")));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.Equal(0, root_.GetProperty("report").GetProperty("applied").GetInt32());
        Assert.Equal(1, root_.GetProperty("report").GetProperty("withheld").GetInt32());

        // W6-108: one line per refused entry, on stderr, with its identity and its action. The reason is the
        // writer own message, which contains a machine path here, so the assertion stops before it.
        string[] refusalLines = LinesContaining(stderr, "同步写入被拒：");
        Assert.Single(refusalLines);
        Assert.Contains("同步写入被拒：src-refused · 写入 · ", refusalLines[0], StringComparison.Ordinal);

        // The per-entry line is progress, never part of the JSON contract.
        Assert.DoesNotContain("同步写入被拒：", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Serve_ReportsEveryWithheldEntryOnStandardError()
    {
        string root = DeviceRoot("b");
        SourceRecord mystery = new()
        {
            SyncSourceId = "src-mystery",
            SourceType = SyncSourceTypeValue.Parse("mystery"),
            DisplayName = "Mystery",
            RecordRevision = 1,
            Endpoints = [new SourceEndpoint { Url = EmbyUrl, IsPrimary = true }],
        };

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            EnvelopeOf("dev-a", mystery));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(1, document.RootElement.GetProperty("report").GetProperty("withheld").GetInt32());

        // W6-108: what the gate held back is sayable after the fact: identity plus the gate reason.
        string[] withheldLines = LinesContaining(stderr, "同步保留未应用：");
        Assert.Single(withheldLines);
        string heldBackPrefix = "warning: 同步保留未应用：src-mystery · ";
        Assert.StartsWith(heldBackPrefix, withheldLines[0], StringComparison.Ordinal);
        Assert.True(
            withheldLines[0].Length > heldBackPrefix.Length,
            "the line must say why it was kept back, not only which entry it was");
        Assert.DoesNotContain("同步保留未应用：", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Serve_LeavesNoEntryLineWhenEveryEntryIsWritten()
    {
        string root = DeviceRoot("b");

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            Payload("dev-a", Source("src-1", "Home"), Source("src-2", "Work")));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(2, document.RootElement.GetProperty("report").GetProperty("applied").GetInt32());

        // A clean round stays quiet: no per-entry line, no warning line, nothing to page someone about.
        Assert.DoesNotContain("同步写入被拒：", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("同步保留未应用：", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("warning:", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ApplyJsonNamesEveryRefusedWriteAlongsideTheOtherReportFields()
    {
        string root = DeviceRoot("b");
        Directory.CreateDirectory(Path.Combine(root, FileLocalSourceStore.FileName + ".tmp"));

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            Payload("dev-a", Source("src-refused", "Refused")));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement report = document.RootElement.GetProperty("report");
        Assert.Equal(0, report.GetProperty("applied").GetInt32());

        // W6-109: the count said one write was refused; the array says which one and in the writer own words.
        JsonElement refused = report.GetProperty("refused");
        Assert.Equal(JsonValueKind.Array, refused.ValueKind);
        Assert.Equal(1, refused.GetArrayLength());
        JsonElement entry = refused[0];
        Assert.Equal("src-refused", entry.GetProperty("syncSourceId").GetString());
        Assert.Equal(
            document.RootElement.GetProperty("items")[0].GetProperty("action").GetString(),
            entry.GetProperty("action").GetString());
        Assert.False(string.IsNullOrWhiteSpace(entry.GetProperty("reason").GetString()));

        // Identity and reason only: a JSON log must not start carrying the source title.
        Assert.DoesNotContain("\"Refused\"", stdout, StringComparison.Ordinal);
        Assert.DoesNotContain("displayName", stdout, StringComparison.Ordinal);
    }
    [Fact]
    public async Task ApplyJsonNamesEveryWithheldEntryWithItsGateReason()
    {
        string root = DeviceRoot("b");
        SourceRecord mystery = new()
        {
            SyncSourceId = "src-mystery",
            SourceType = SyncSourceTypeValue.Parse("mystery"),
            DisplayName = "Mystery",
            RecordRevision = 1,
            Endpoints = [new SourceEndpoint { Url = EmbyUrl, IsPrimary = true }],
        };

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            EnvelopeOf("dev-a", mystery));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement report = document.RootElement.GetProperty("report");
        JsonElement heldBack = report.GetProperty("withheldEntries");
        Assert.Equal(1, heldBack.GetArrayLength());
        JsonElement entry = heldBack[0];
        Assert.Equal("src-mystery", entry.GetProperty("syncSourceId").GetString());

        // The array and the per-item preview must carry the same code, so a script can join them.
        Assert.Equal(
            document.RootElement.GetProperty("items")[0].GetProperty("reason").GetString(),
            entry.GetProperty("reason").GetString());
        Assert.False(string.IsNullOrWhiteSpace(entry.GetProperty("reason").GetString()));
        Assert.DoesNotContain("\"Mystery\"", stdout, StringComparison.Ordinal);
    }
    [Fact]
    public async Task ApplyJsonKeepsEmptyEntryArraysSoScriptsSeeOneShape()
    {
        string root = DeviceRoot("b");

        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--apply", "--json"],
            Payload("dev-a", Source("src-1", "Home"), Source("src-2", "Work")));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement report = document.RootElement.GetProperty("report");
        Assert.Equal(2, report.GetProperty("applied").GetInt32());

        // A clean round still ships both arrays: scripts should never have to guess whether they exist.
        Assert.Equal(JsonValueKind.Array, report.GetProperty("refused").ValueKind);
        Assert.Equal(0, report.GetProperty("refused").GetArrayLength());
        Assert.Equal(JsonValueKind.Array, report.GetProperty("withheldEntries").ValueKind);
        Assert.Equal(0, report.GetProperty("withheldEntries").GetArrayLength());
    }
    [Fact]
    public async Task Serve_ReportsAnInvalidEnvelopeAndWritesNothing()
    {
        string root = DeviceRoot("b");
        (int exitCode, string stdout, string stderr) = await ServeOnceAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--json"],
            Encoding.UTF8.GetBytes("this is not a sync envelope"));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("error " + SourceSyncTransferReasons.EnvelopeInvalid, stderr, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(root, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task Serve_ReportsATruncatedFrameWhenThePeerHangsUp()
    {
        string root = DeviceRoot("b");
        (int exitCode, string stdout, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--json"],
            async port =>
            {
                using TcpClient client = new();
                await client.ConnectAsync(IPAddress.Loopback, port);
                NetworkStream stream = client.GetStream();

                // A header that promises ten bytes, followed by three: the peer stopped mid-frame.
                await stream.WriteAsync(new byte[] { 0, 0, 0, 10, 1, 2, 3 });
                await stream.FlushAsync();
                client.Client.Shutdown(SocketShutdown.Both);
            });

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.False(File.Exists(Path.Combine(root, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task Serve_ReportsAFrameInvalidWhenTheKindByteIsUnknown()
    {
        string root = DeviceRoot("b");
        (int exitCode, string stdout, string _) = await ServeWithAsync(
            ["transfer", "serve", "--root", root, "--device", "dev-b", "--json"],
            async port =>
            {
                using TcpClient client = new();
                await client.ConnectAsync(IPAddress.Loopback, port);
                NetworkStream stream = client.GetStream();
                await stream.WriteAsync(Frame(0x09, 1, 2, 3, 4));
                await stream.FlushAsync();
                client.Client.Shutdown(SocketShutdown.Both);
            });

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, document.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Send_DeliversTheEnvelopeToALoopbackListener()
    {
        string root = DeviceRoot("a");
        Seed(root, Source("src-1", "Home"));

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started);
        Assert.NotNull(start.Listener);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<(int ExitCode, string Stdout, string Stderr)> sending = Task.Run(() => Run(
            "transfer", "send", "--to", "127.0.0.1:" + listener.Port.ToString(CultureInfo.InvariantCulture),
            "--root", root, "--device", "dev-a", "--json"));
        SourceSyncTransferResult received = await listener.ReceiveOnceAsync();
        (int exitCode, string stdout, string stderr) = await sending;

        Assert.True(received.Succeeded, "the receiver got nothing: " + received.Reason);
        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Equal("", stderr.Trim());
        Assert.True(SourceSyncInbox.TryDecode(received.Envelope, out SourceSyncEnvelope? envelope, out string? decodeError), decodeError);
        Assert.Equal("dev-a", envelope!.DeviceId);
        AssertCount(1, envelope.Sources);

        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.Equal("transfer", root_.GetProperty("command").GetString());
        Assert.Equal("send", root_.GetProperty("subcommand").GetString());
        Assert.Equal("dev-a", root_.GetProperty("deviceId").GetString());
        Assert.Equal(1, root_.GetProperty("sourceCount").GetInt32());
        Assert.True(root_.GetProperty("bytes").GetInt32() > 0);
        Assert.True(root_.GetProperty("frames").GetInt32() >= 1);
        Assert.Equal(envelope.SnapshotId, root_.GetProperty("snapshotId").GetString());
    }

    [Fact]
    public void Send_RefusesANonLoopbackTargetBeforeAnySocket()
    {
        string root = DeviceRoot("a");
        Seed(root, Source("src-1", "Home"));

        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "send", "--to", "192.168.1.5:8080", "--root", root, "--device", "dev-a", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains("before any socket was created", stderr, StringComparison.Ordinal);
        Assert.Contains("firewall", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Send_RejectsAMalformedTarget()
    {
        string root = DeviceRoot("a");
        Seed(root, Source("src-1", "Home"));

        (int exitCode, string _, string stderr) = Run("transfer", "send", "--to", "127.0.0.1", "--root", root, "--device", "dev-a");
        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("invalid-target", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Send_RejectsAZeroPort()
    {
        (int exitCode, string _, string stderr) = Run("transfer", "send", "--to", "127.0.0.1:0", "--root", _root, "--device", "dev-a");
        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("invalid-target", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Send_RejectsApplyBecauseThereIsNothingToApply()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:9000", "--root", _root, "--device", "dev-a", "--apply");
        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--apply:transfer-send", stderr, StringComparison.Ordinal);
    }

    // ---- 2. roundtrip: two devices, two directions -------------------------------------------

    [Fact]
    public async Task Roundtrip_DryRunWritesNothingOnEitherSide()
    {
        string root = _root;
        Seed(Path.Combine(root, "a"), Source("src-1", "Home"), Source("src-2", "Work"));

        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Equal("", stderr.Trim());
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.Equal("roundtrip", root_.GetProperty("subcommand").GetString());
        Assert.False(root_.GetProperty("applied").GetBoolean());
        JsonElement first = root_.GetProperty("rounds")[0];

        // No report object at all: a dry run never wrote, so it has nothing to report.
        Assert.False(first.TryGetProperty("report", out _), "a dry run must not report a write");
        Assert.Equal(2, first.GetProperty("counts").GetProperty("apply").GetInt32());
        Assert.Equal("dev-a", first.GetProperty("from").GetString());
        Assert.Equal("dev-b", first.GetProperty("to").GetString());

        // Nothing was copied: the receiver has no source list and no baseline on disk.
        Assert.False(File.Exists(Path.Combine(root, "b", FileLocalSourceStore.FileName)));
        Assert.False(File.Exists(Path.Combine(root, "b", FileSourceSyncBaselineStore.FileName)));
        Assert.False(File.Exists(Path.Combine(root, "a", FileSourceSyncBaselineStore.FileName)));
        AssertCount(2, new FileLocalSourceStore(Path.Combine(root, "a")).GetAll());
    }

    [Fact]
    public async Task Roundtrip_AppliesAtoBThenBtoAChangesNothing()
    {
        string root = _root;
        string rootA = Path.Combine(root, "a");
        Seed(rootA, Source("src-1", "Home"), Source("src-2", "Work"));
        byte[] before = File.ReadAllBytes(Path.Combine(rootA, FileLocalSourceStore.FileName));

        (int exitCode, string stdout, string _) = await Task.Run(() => Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply", "--json"));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.True(root_.GetProperty("applied").GetBoolean());
        JsonElement first = root_.GetProperty("rounds")[0];
        Assert.Equal(2, first.GetProperty("report").GetProperty("applied").GetInt32());
        Assert.True(first.GetProperty("report").GetProperty("baselineUpdated").GetBoolean());
        Assert.True(first.GetProperty("bytes").GetInt32() > 0);
        JsonElement second = root_.GetProperty("rounds")[1];
        Assert.True(second.GetProperty("noChange").GetBoolean());
        Assert.Equal(0, second.GetProperty("report").GetProperty("applied").GetInt32());
        Assert.True(root_.GetProperty("secondRoundNoChange").GetBoolean());

        // B now holds A's sources and a baseline that names A as the peer.
        FileLocalSourceStore storeB = new(Path.Combine(root, "b"));
        AssertCount(2, storeB.GetAll());
        FileSourceSyncBaselineStore baselinesB = new(Path.Combine(root, "b"));
        Assert.True(File.Exists(baselinesB.FilePath));
        AssertCount(2, baselinesB.Load("dev-a").Entries);

        // Round 2 changed nothing on A: its own list is byte-identical.
        Assert.Equal(before, File.ReadAllBytes(Path.Combine(rootA, FileLocalSourceStore.FileName)));
    }

    [Fact]
    public async Task Roundtrip_RunTwiceIsANoChangeOnBothSides()
    {
        string root = _root;
        Seed(Path.Combine(root, "a"), Source("src-1", "Home"), Source("src-2", "Work"));

        (int firstExit, string _, string _) = await Task.Run(() => Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply"));
        Assert.Equal(CliExitCode.Success, firstExit);

        byte[] storeB = File.ReadAllBytes(Path.Combine(root, "b", FileLocalSourceStore.FileName));
        byte[] baselineB = File.ReadAllBytes(Path.Combine(root, "b", FileSourceSyncBaselineStore.FileName));

        (int secondExit, string stdout, string _) = await Task.Run(() => Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply", "--json"));
        Assert.Equal(CliExitCode.Success, secondExit);

        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement root_ = document.RootElement;
        Assert.True(root_.GetProperty("rounds")[0].GetProperty("noChange").GetBoolean());
        Assert.Equal(0, root_.GetProperty("rounds")[0].GetProperty("report").GetProperty("applied").GetInt32());
        Assert.True(root_.GetProperty("secondRoundNoChange").GetBoolean());

        // The second run wrote nothing on the receiver: both files are byte-identical.
        Assert.Equal(storeB, File.ReadAllBytes(Path.Combine(root, "b", FileLocalSourceStore.FileName)));
        Assert.Equal(baselineB, File.ReadAllBytes(Path.Combine(root, "b", FileSourceSyncBaselineStore.FileName)));
    }

    [Fact]
    public async Task Roundtrip_ReportsTheReceiversWithheldEntryOnStandardError()
    {
        string root = _root;
        Seed(
            Path.Combine(root, "a"),
            Source("src-known", "Known"),
            new LocalSourceRecord
            {
                ServerId = "srv-mystery",
                SyncSourceId = "src-mystery",
                Type = ServerType.Unknown,
                SourceTypeRaw = "mystery",
                Url = EmbyUrl,
                DisplayName = "Mystery",
                RecordRevision = 1,
            });

        (int exitCode, string stdout, string stderr) = await Task.Run(() => Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply", "--json"));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(1, document.RootElement.GetProperty("rounds")[0].GetProperty("counts").GetProperty("withheld").GetInt32());

        // W6-108: a roundtrip is a serve nobody watches, so the same line has to survive on stderr alone.
        string[] withheldLines = LinesContaining(stderr, "同步保留未应用：src-mystery · ");
        Assert.NotEmpty(withheldLines);
        foreach (string line in withheldLines)
        {
            Assert.StartsWith("warning: 同步保留未应用：src-mystery · ", line, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task RoundtripJsonCarriesTheReceiversWithheldEntry()
    {
        string root = _root;
        Seed(
            Path.Combine(root, "a"),
            Source("src-known", "Known"),
            new LocalSourceRecord
            {
                ServerId = "srv-mystery",
                SyncSourceId = "src-mystery",
                Type = ServerType.Unknown,
                SourceTypeRaw = "mystery",
                Url = EmbyUrl,
                DisplayName = "Mystery",
                RecordRevision = 1,
            });

        (int exitCode, string stdout, string stderr) = await Task.Run(() => Run(
            "transfer", "roundtrip", "--root", root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply", "--json"));

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement rounds = document.RootElement.GetProperty("rounds");
        JsonElement firstRound = rounds[0].GetProperty("report").GetProperty("withheldEntries");
        Assert.Equal(1, firstRound.GetArrayLength());
        Assert.Equal("src-mystery", firstRound[0].GetProperty("syncSourceId").GetString());

        // The second direction had nothing to hold back, but the shape must not change between rounds.
        Assert.Equal(0, rounds[1].GetProperty("report").GetProperty("withheldEntries").GetArrayLength());
    }
    // ---- 3. parser surface -------------------------------------------------------------------

    [Fact]
    public void Roundtrip_RequiresBothDeviceIds()
    {
        (int exitCode, string _, string stderr) = Run("transfer", "roundtrip", "--root", _root);
        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("missing-required-option:--device-a", stderr, StringComparison.Ordinal);

        (int secondExit, string _, string secondStderr) = Run("transfer", "roundtrip", "--root", _root, "--device-a", "dev-a");
        Assert.Equal(CliExitCode.UsageError, secondExit);
        Assert.Contains("missing-required-option:--device-b", secondStderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Transfer_RejectsAMissingOrUnknownVerb()
    {
        (int missingExit, string _, string missingStderr) = Run("transfer");
        Assert.Equal(CliExitCode.UsageError, missingExit);
        Assert.Contains("missing-subcommand:transfer", missingStderr, StringComparison.Ordinal);

        (int unknownExit, string _, string unknownStderr) = Run("transfer", "dance", "--root", _root);
        Assert.Equal(CliExitCode.UsageError, unknownExit);
        Assert.Contains("unknown-subcommand:transfer:dance", unknownStderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Serve_RejectsAnOutOfRangePort()
    {
        (int exitCode, string _, string stderr) = Run("transfer", "serve", "--root", _root, "--device", "dev-b", "--port", "70000");
        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("invalid-port:70000", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Serve_RejectsOnceForAnyOtherCommand()
    {
        (int exitCode, string _, string stderr) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--once");
        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("option-not-allowed-for-command:--once:transfer-roundtrip", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void Transfer_HelpListsTheNewGroup()
    {
        (int exitCode, string stdout, string _) = Run("transfer", "--help");
        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Contains("transfer serve", stdout, StringComparison.Ordinal);
        Assert.Contains("transfer roundtrip", stdout, StringComparison.Ordinal);
        Assert.Contains("loopback only", stdout, StringComparison.Ordinal);
    }

    // ---- helpers -----------------------------------------------------------------------------

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

    private static void Seed(string directory, params LocalSourceRecord[] records)
    {
        FileLocalSourceStore store = new(directory);
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }
    }

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

    private static byte[] EnvelopeOf(string deviceId, params SourceRecord[] records)
    {
        SourceSyncEnvelope envelope = new()
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = 1,
            DeviceId = deviceId,
            SnapshotId = "snap-" + Guid.NewGuid().ToString("N"),
            CreatedAtEpochMs = FixedNow.ToUnixTimeMilliseconds(),
            Sources = records,
        };
        return Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));
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

    private static string[] LinesContaining(string text, string needle) =>
        text.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.TrimEnd('\r'))
            .Where(line => line.Contains(needle, StringComparison.Ordinal))
            .ToArray();

    private static void AssertCount<T>(int expected, IReadOnlyCollection<T> items) => Assert.Equal(expected, items.Count);

    private static byte[] Frame(byte kind, params byte[] body)
    {
        byte[] payload = new byte[1 + body.Length];
        payload[0] = kind;
        body.CopyTo(payload, 1);

        byte[] framed = new byte[4 + payload.Length];
        framed[0] = (byte)((payload.Length >> 24) & 0xFF);
        framed[1] = (byte)((payload.Length >> 16) & 0xFF);
        framed[2] = (byte)((payload.Length >> 8) & 0xFF);
        framed[3] = (byte)(payload.Length & 0xFF);
        payload.CopyTo(framed, 4);
        return framed;
    }

    private static async Task<(int ExitCode, string Stdout, string Stderr)> ServeOnceAsync(string[] serveArgs, byte[] payload)
    {
        return await ServeWithAsync(serveArgs, async port =>
        {
            SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync("127.0.0.1", port, payload);
            Assert.True(sent.Succeeded, "the test client could not deliver the envelope: " + sent.Reason);
        });
    }

    private static async Task<(int ExitCode, string Stdout, string Stderr)> ServeWithAsync(string[] serveArgs, Func<int, Task> useConnection)
    {
        CaptureWriter stdout = new();
        CaptureWriter stderr = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(serveArgs, stdout, stderr, () => FixedNow));
        int port = await WaitForListeningPortAsync(stderr);
        try
        {
            await useConnection(port);
        }
        catch
        {
            // Shut the command down instead of leaving a listener behind for the rest of the run.
            await serving.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
            throw;
        }

        int exitCode = await serving.WaitAsync(TimeSpan.FromSeconds(30));
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static async Task<int> WaitForListeningPortAsync(CaptureWriter stderr)
    {
        // 200 * 25 ms = 5 s: enough for a cold start on a loaded CI box, short enough to fail loudly.
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

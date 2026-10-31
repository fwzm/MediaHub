using System.Globalization;
using System.Text;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-17: one object that runs "export on this device -> loopback transfer -> the peer's import round
/// (preview, approval gate, write, baseline advance)" and reports what happened.
/// </summary>
/// <remarks>
/// <para>
/// Two temporary directories stand in for two devices, each with its own <c>sync-sources.json</c> and
/// <c>sync-baseline.json</c>. Every socket in this file is created by the product code, which refuses any
/// address that is not loopback before a socket exists — the tests assert that refusal rather than widening it,
/// because binding a LAN address makes Windows ask the user to open the firewall.
/// </para>
/// <para>
/// The assertions are deliberately about observable state: the peer's store, the bytes of the files on disk,
/// and the write counter of <see cref="InMemoryLocalSourceStore"/>. A round that "succeeded" but wrote nothing
/// must be distinguishable from one that wrote the same content again.
/// </para>
/// </remarks>
public sealed class SourceSyncPeerRoundTests : IDisposable
{
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string SenderId = "dev-a";
    private const string ReceiverId = "dev-b";
    private const long FixedNow = 1_760_000_000_000L;

    private readonly List<string> _directories = [];

    public void Dispose()
    {
        foreach (string directory in _directories)
        {
            try
            {
                if (Directory.Exists(directory))
                {
                    Directory.Delete(directory, recursive: true);
                }
            }
            catch (IOException)
            {
                // A leftover temp directory is not worth failing a test over.
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    // ---- the round trip ------------------------------------------------------------------------------

    [Fact]
    public async Task Exchange_AppliesEverySourceAndAdvancesTheReceiversBaseline()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore));

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(senderStore.GetAll().Count, result.Applied);
        Assert.Equal(2, result.Applied);
        AssertCount(2, receiverStore.GetAll());
        Assert.NotNull(result.Report);
        Assert.True(result.Report!.BaselineUpdated);
        Assert.Equal(2, result.Report.BaselineEntryCount);
        Assert.True(File.Exists(receiverBaselines.FilePath));
        AssertCount(2, receiverBaselines.Load(SenderId).Entries);
        Assert.Equal(SenderId, result.SenderDeviceId);
        Assert.Equal(ReceiverId, result.ReceiverDeviceId);
    }

    [Fact]
    public async Task Exchange_CarriesTheBytesOverARealLoopbackSocket()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));
        byte[] expected = PayloadOf(sender.Export());

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore));

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(expected.Length, result.Bytes);
        Assert.True(result.FrameCount >= 1, "at least one frame has to cross the socket");
        Assert.NotNull(result.Envelope);
        Assert.Equal(SenderId, result.Envelope!.DeviceId);
        Assert.Equal(SenderId, result.SenderDeviceId);
        Assert.Equal(ReceiverId, result.ReceiverDeviceId);
    }

    [Fact]
    public async Task Exchange_WithNoSourcesSucceedsAndWritesNothing()
    {
        string receiverDirectory = NewDirectory();
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, new InMemoryLocalSourceStore());
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore));

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(0, result.Applied);
        Assert.Empty(result.Items);
        AssertCount(0, receiverStore.GetAll());
        Assert.False(File.Exists(receiverBaselines.FilePath), "an empty round must not create a baseline file");
    }

    [Fact]
    public async Task ReturnTrip_IsNoChangeAndLeavesTheSendersBaselineBytesUntouched()
    {
        string senderDirectory = NewDirectory();
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileSourceSyncBaselineStore senderBaselines = new(senderDirectory);

        // The sender already holds a baseline for the peer from an earlier round; a return trip must not move it.
        SourceSyncPeerRound sender = new(SenderId, senderStore, senderBaselines);
        senderBaselines.Save(ReceiverId, SourceSyncBaseline.FromRecords(sender.Export().Sources, ReceiverId, "snap-old"));
        byte[] baselineBefore = File.ReadAllBytes(senderBaselines.FilePath);

        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));

        SourceSyncPeerRoundResult forward = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore));
        Assert.True(forward.Succeeded, Describe(forward));

        // B -> A immediately: A already holds exactly what B would send back.
        SourceSyncPeerRoundResult back = await SourceSyncPeerRound.ExchangeAsync(receiver, sender, Writer(senderStore));

        Assert.True(back.Succeeded, Describe(back));
        Assert.True(back.IsNoChange, Describe(back));
        Assert.Equal(0, back.Applied);
        Assert.Equal(2, senderStore.WriteCount);
        Assert.Equal(baselineBefore, File.ReadAllBytes(senderBaselines.FilePath));
    }

    [Fact]
    public async Task Update_PropagatesOnceAndTheSameEnvelopeIsThenNoChange()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);

        senderStore.Save(Source("src-1", "Home", revision: 2));
        byte[] payload = PayloadOf(sender.Export());

        SourceSyncPeerRoundResult updated = await SourceSyncPeerRound.ExchangePayloadAsync(sender, receiver, writer, payload);

        Assert.True(updated.Succeeded, Describe(updated));
        Assert.Equal(1, updated.Applied);
        SourceSyncApprovalItem item = Assert.IsType<SourceSyncApprovalItem>(updated.FindItem("src-1"));
        Assert.Equal(SourceSyncAction.Update, item.Action);
        Assert.Equal(SourceSyncReasons.HigherRevision, item.Reason);
        Assert.Equal(2, receiverStore.GetAll().Single().RecordRevision);
        Assert.Equal(2, receiverBaselines.Load(SenderId).Entries.Single().RecordRevision);

        byte[] sourcesBefore = File.ReadAllBytes(receiverStore.FilePath);
        byte[] baselineBefore = File.ReadAllBytes(receiverBaselines.FilePath);

        SourceSyncPeerRoundResult again = await SourceSyncPeerRound.ExchangePayloadAsync(sender, receiver, writer, payload);

        Assert.True(again.IsNoChange, Describe(again));
        Assert.Equal(0, again.Applied);
        Assert.Equal(SourceSyncAction.NoChange, again.FindItem("src-1")!.Action);
        Assert.False(again.Report!.BaselineUpdated);
        Assert.Equal(sourcesBefore, File.ReadAllBytes(receiverStore.FilePath));
        Assert.Equal(baselineBefore, File.ReadAllBytes(receiverBaselines.FilePath));
    }

    [Fact]
    public async Task Tombstone_IsProposedNotAppliedAndTheSourceSurvives()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);
        Assert.True(senderStore.Delete("src-2"));

        SourceSyncPeerRoundResult gone = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            writer,
            deletions: [TombstoneOf("src-2", revision: 1)]);

        Assert.True(gone.Succeeded, Describe(gone));
        AssertCount(2, gone.Items);
        SourceSyncApprovalItem tombstone = Assert.IsType<SourceSyncApprovalItem>(gone.FindItem("src-2"));
        Assert.Equal(SourceSyncAction.Delete, tombstone.Action);
        Assert.Equal(SourceSyncReasons.Tombstone, tombstone.Reason);
        Assert.True(tombstone.RequiresApproval, "a tombstone is never applied silently");
        Assert.Equal(0, gone.Applied);
        Assert.Equal(2, gone.Skipped);

        // The peer keeps the source: its copy may be the last one, and its credentials would go with it.
        AssertCount(2, receiverStore.GetAll());
        Assert.Contains(receiverStore.GetAll(), record => record.SyncSourceId == "src-2");
    }

    [Fact]
    public async Task Tombstone_WithAnExplicitAllowRemovesTheSource()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);
        Assert.True(senderStore.Delete("src-2"));

        SourceSyncPeerRoundResult removed = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            writer,
            deletions: [TombstoneOf("src-2", revision: 1)],
            allowedIds: ["src-2"]);

        Assert.True(removed.Succeeded, Describe(removed));
        Assert.Equal(1, removed.Applied);
        Assert.Single(receiverStore.GetAll());
        Assert.DoesNotContain(receiverStore.GetAll(), record => record.SyncSourceId == "src-2");
    }

    // ---- refusals ------------------------------------------------------------------------------------

    [Fact]
    public async Task NonLoopbackTarget_IsRefusedBeforeAnySocketAndThePeerWritesNothing()
    {
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new(SenderId, SenderStore("Home"));
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        SourceSyncPeerRoundResult result = await sender.SendAsync(sender.Export(), "192.168.1.5", 8080);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, result.Reason);

        // The evidence line: the address is refused, and it is refused before a socket exists.
        string detail = result.Detail ?? "";
        Assert.Contains("before any socket was created", detail, StringComparison.Ordinal);
        Assert.Contains("firewall", detail, StringComparison.Ordinal);
        AssertCount(0, receiverStore.GetAll());
    }

    [Fact]
    public async Task NonLoopbackListener_IsRefusedAndCreatesNoBaseline()
    {
        string receiverDirectory = NewDirectory();
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);

        SourceSyncPeerRoundResult result = await receiver.ReceiveOnceAsync(
            Writer(receiverStore),
            address: "10.0.0.7",
            port: 9000);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, result.Reason);
        Assert.False(File.Exists(receiverStore.FilePath));
        Assert.False(File.Exists(receiverBaselines.FilePath));
        AssertCount(0, receiverStore.GetAll());
    }

    [Fact]
    public async Task TamperedEnvelope_WithBrokenJson_IsRefusedAndThePeerWritesNothing()
    {
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        byte[] payload = PayloadOf(sender.Export());
        payload[0] = (byte)'[';

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangePayloadAsync(
            sender,
            receiver,
            Writer(receiverStore),
            payload);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.StartsWith("malformed-json", result.Detail ?? "", StringComparison.Ordinal);
        AssertCount(0, receiverStore.GetAll());
    }

    [Fact]
    public async Task TamperedEnvelope_WithAnUnknownSchemaVersion_IsRefusedWithTheContractReason()
    {
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        string json = Encoding.UTF8.GetString(PayloadOf(sender.Export()));
        Assert.Contains("\"schemaVersion\":1", json, StringComparison.Ordinal);
        byte[] payload = Encoding.UTF8.GetBytes(
            json.Replace("\"schemaVersion\":1", "\"schemaVersion\":9", StringComparison.Ordinal));

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangePayloadAsync(
            sender,
            receiver,
            Writer(receiverStore),
            payload);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.StartsWith("unsupported-schema-version:9", result.Detail ?? "", StringComparison.Ordinal);
        AssertCount(0, receiverStore.GetAll());
    }

    [Fact]
    public async Task UnsupportedEnvelope_IsRefusedBeforeAnyBytesLeave()
    {
        SourceSyncPeerRound sender = new(SenderId, SenderStore("Home"));
        SourceSyncEnvelope unreadable = sender.Export() with { SchemaVersion = 2 };

        SourceSyncPeerRoundResult result = await sender.SendAsync(unreadable, "127.0.0.1", 65001);

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.StartsWith("unsupported-schema-version", result.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task ChallengeMismatch_WritesNothingAndLeavesNoBaseline()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail ?? start.Reason);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncPeerRoundResult> receiving = receiver.ReceiveOnAsync(
            listener,
            Writer(receiverStore),
            expectedChallenge: "challenge-2");

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            PayloadOf(sender.Export()),
            challenge: "challenge-1");

        SourceSyncPeerRoundResult received = await receiving;

        // The receiver stops at the first frame it cannot accept and drops the connection, so the
        // sender either finishes writing before that close lands or sees io-failed. Both are correct
        // protocol behaviour; anything else is a defect. (Asserting success alone is a race: it only
        // loses when the machine is loaded, e.g. when the whole suite runs in parallel.)
        Assert.True(
            sent.Succeeded || sent.Reason == SourceSyncTransferReasons.IoFailed,
            sent.Reason ?? "");
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, received.Reason);
        AssertCount(0, receiverStore.GetAll());
        Assert.False(File.Exists(receiverBaselines.FilePath));
    }

    [Fact]
    public async Task MatchingChallenge_CompletesTheRound()
    {
        string receiverDirectory = NewDirectory();
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, SenderStore("Home"));
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            Writer(receiverStore),
            challenge: "challenge-1");

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(1, result.Applied);
        Assert.True(result.FrameCount >= 2, "the challenge frame plus at least one chunk frame");
        AssertCount(1, receiverStore.GetAll());
    }

    [Fact]
    public async Task SilentPeer_TimesOutWithTheStableCode()
    {
        string receiverDirectory = NewDirectory();
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        SourceSyncPeerRoundResult result = await receiver.ReceiveOnceAsync(
            Writer(receiverStore),
            timeout: TimeSpan.FromMilliseconds(300));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.TransferTimeout, result.Reason);
        AssertCount(0, receiverStore.GetAll());
    }

    // ---- preview, baselines and idempotence -----------------------------------------------------------

    [Fact]
    public async Task PreviewWithoutTransfer_PlansTheSameRowsTheRoundWouldWrite()
    {
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        SourceSyncEnvelope envelope = sender.Export();
        SourceSyncApplyPreview preview = receiver.Preview(envelope);

        Assert.Single(preview.Items);
        Assert.Equal(SourceSyncAction.Create, preview.Items[0].Action);
        Assert.Equal(SourceSyncReasons.NewSource, preview.Items[0].Reason);
        AssertCount(0, receiverStore.GetAll());

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangePayloadAsync(
            sender,
            receiver,
            Writer(receiverStore),
            PayloadOf(envelope));

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(preview.Items.Count, result.Items.Count);
        Assert.Equal(1, result.Applied);
    }

    [Fact]
    public async Task BaselineOnDiskMakesASecondRoundANoChangeForAFreshRound()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        SourceSyncPeerRound sender = new(SenderId, senderStore);

        FileLocalSourceStore firstStore = new(receiverDirectory);
        SourceSyncPeerRound first = new(ReceiverId, firstStore, new FileSourceSyncBaselineStore(receiverDirectory));
        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, first, Writer(firstStore))).Succeeded);
        byte[] sourcesBefore = File.ReadAllBytes(firstStore.FilePath);

        // A new process: fresh store objects reading the same directory, and a brand new envelope (new snapshot id).
        FileLocalSourceStore freshStore = new(receiverDirectory);
        FileSourceSyncBaselineStore freshBaselines = new(receiverDirectory);
        SourceSyncPeerRound fresh = new(ReceiverId, freshStore, freshBaselines);

        SourceSyncPeerRoundResult again = await SourceSyncPeerRound.ExchangePayloadAsync(
            sender,
            fresh,
            Writer(freshStore),
            PayloadOf(sender.Export()));

        Assert.True(again.Succeeded, Describe(again));
        Assert.True(again.IsNoChange, Describe(again));
        Assert.Equal(0, again.Applied);
        Assert.False(again.Report!.BaselineUpdated);
        Assert.Equal(sourcesBefore, File.ReadAllBytes(freshStore.FilePath));
        AssertCount(2, freshStore.GetAll());
    }

    [Fact]
    public async Task SendingDoesNotWriteToTheSendersOwnStore()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore))).Succeeded);

        Assert.Equal(2, senderStore.WriteCount);
        AssertCount(2, senderStore.GetAll());
    }

    [Fact]
    public async Task Exchange_TwiceKeepsTheReceiversFilesByteIdentical()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);
        byte[] sourcesBefore = File.ReadAllBytes(receiverStore.FilePath);
        byte[] baselineBefore = File.ReadAllBytes(receiverBaselines.FilePath);

        SourceSyncPeerRoundResult second = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer);

        Assert.True(second.IsNoChange, Describe(second));
        Assert.Equal(0, second.Applied);
        Assert.False(second.Report!.BaselineUpdated);
        Assert.Equal(sourcesBefore, File.ReadAllBytes(receiverStore.FilePath));
        Assert.Equal(baselineBefore, File.ReadAllBytes(receiverBaselines.FilePath));
    }

    // ---- tombstones the peer cannot place --------------------------------------------------------------

    [Fact]
    public async Task Tombstone_ForASourceThePeerNeverHad_IsANoChange()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            writer,
            deletions: [TombstoneOf("src-9", revision: 3)]);

        Assert.True(result.Succeeded, Describe(result));
        SourceSyncApprovalItem item = Assert.IsType<SourceSyncApprovalItem>(result.FindItem("src-9"));
        Assert.Equal(SourceSyncAction.NoChange, item.Action);
        Assert.Equal(SourceSyncReasons.NoLocalSource, item.Reason);
        Assert.Equal(0, result.Applied);
        Assert.Single(receiverStore.GetAll());
    }

    [Fact]
    public async Task StaleTombstone_IsIgnoredRatherThanApplied()
    {
        string receiverDirectory = NewDirectory();
        InMemoryLocalSourceStore senderStore = SenderStore("Home", "Office");
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, senderStore);
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, new FileSourceSyncBaselineStore(receiverDirectory));
        SourceSyncRecordWriter writer = Writer(receiverStore);

        Assert.True((await SourceSyncPeerRound.ExchangeAsync(sender, receiver, writer)).Succeeded);
        Assert.True(senderStore.Delete("src-2"));

        // The peer has revision 1 of src-2; a tombstone that only knows revision 0 is older than its copy.
        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            writer,
            deletions: [TombstoneOf("src-2", revision: 0)]);

        Assert.True(result.Succeeded, Describe(result));
        SourceSyncApprovalItem item = Assert.IsType<SourceSyncApprovalItem>(result.FindItem("src-2"));
        Assert.Equal(SourceSyncAction.IgnoreStale, item.Action);
        Assert.Equal(SourceSyncReasons.StaleRevision, item.Reason);
        Assert.Equal(0, result.Applied);
        Assert.Contains(receiverStore.GetAll(), record => record.SyncSourceId == "src-2");
    }

    [Fact]
    public async Task WriterThatThrows_IsReportedInsteadOfEscapingToTheCaller()
    {
        string receiverDirectory = NewDirectory();
        FileLocalSourceStore receiverStore = new(receiverDirectory);
        FileSourceSyncBaselineStore receiverBaselines = new(receiverDirectory);
        SourceSyncPeerRound sender = new(SenderId, SenderStore("Home"));
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore, receiverBaselines);

        SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            (_, _) => throw new IOException("disk is full"));

        Assert.True(result.Succeeded, Describe(result));
        Assert.Equal(0, result.Applied);
        Assert.Equal(1, result.Report!.Withheld);
        AssertCount(0, receiverStore.GetAll());
        Assert.False(File.Exists(receiverBaselines.FilePath), "a refused write must not advance the baseline");
    }

    [Fact]
    public async Task ThePeerRoundForwardsSkippedIdsToTheRound()
    {
        // The app reaches the round through SourceSyncPeerRound; if the skip list stopped at this boundary, a row
        // the page shows as skipped would be written one layer down.
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new(SenderId, SenderStore("Home"));
        SourceSyncPeerRound receiver = new(ReceiverId, receiverStore);
        SourceSyncApplyPreview preview = receiver.Preview(sender.Export());
        Assert.Equal(SourceSyncAction.Create, Assert.Single(preview.Items).Action);

        SourceSyncApplyReport report = await receiver.ApplyAsync(
            preview,
            Writer(receiverStore),
            allowedIds: [],
            skippedIds: ["src-1"]);

        Assert.Equal(0, report.Applied);
        Assert.Equal(1, report.Skipped);
        AssertCount(0, receiverStore.GetAll());
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static void AssertCount<T>(int expected, IReadOnlyCollection<T> items)
        => Assert.Equal(expected, items.Count);

    private static string Describe(SourceSyncPeerRoundResult result)
        => "reason=" + (result.Reason ?? "(none)")
            + " detail=" + (result.Detail ?? "(none)")
            + " applied=" + result.Applied.ToString(CultureInfo.InvariantCulture)
            + " report=" + (result.Report?.Summary ?? "(none)");

    private static InMemoryLocalSourceStore SenderStore(params string[] names)
    {
        InMemoryLocalSourceStore store = new();
        for (int index = 0; index < names.Length; index++)
        {
            store.Save(Source("src-" + (index + 1).ToString(CultureInfo.InvariantCulture), names[index]));
        }

        return store;
    }

    private static LocalSourceRecord Source(string syncSourceId, string name, long revision = 1, string? accountScope = null)
        => new()
        {
            ServerId = "srv-" + syncSourceId,
            SyncSourceId = syncSourceId,
            Type = ServerType.Emby,
            SourceTypeRaw = SyncSourceTypes.Emby,
            Url = EmbyUrl,
            AccountScope = accountScope,
            DisplayName = name,
            RecordRevision = revision,
        };

    private static SourceSyncRecordWriter Writer(ILocalSourceStore store) => (item, record) =>
    {
        if (record is null)
        {
            bool removed = store.Delete(item.SyncSourceId);
            return Task.FromResult(removed
                ? SourceSyncWriteOutcome.Written("removed " + item.SyncSourceId)
                : SourceSyncWriteOutcome.Refused("no local source with id " + item.SyncSourceId));
        }

        store.Save(new LocalSourceRecord
        {
            ServerId = item.ServerId ?? "srv-" + record.SyncSourceId,
            SyncSourceId = record.SyncSourceId,
            Type = WindowsSourceTypeMap.ToServerType(record.SourceType.Wire),
            SourceTypeRaw = record.SourceType.Raw,
            Url = record.Endpoints.FirstOrDefault(endpoint => endpoint.IsPrimary)?.Url
                ?? record.Endpoints.FirstOrDefault()?.Url,
            AccountScope = record.AccountScope,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        });

        return Task.FromResult(SourceSyncWriteOutcome.Written("saved " + record.SyncSourceId));
    };

    private static Tombstone TombstoneOf(string syncSourceId, long revision)
        => new()
        {
            SyncSourceId = syncSourceId,
            DeletedAtEpochMs = FixedNow,
            RecordRevision = revision,
        };

    private static byte[] PayloadOf(SourceSyncEnvelope envelope)
        => Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));

    private string NewDirectory()
    {
        string directory = Path.Combine(
            Path.GetTempPath(),
            "mh-peer-round-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        _directories.Add(directory);
        return directory;
    }
}
using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-16: one sync envelope over a loopback socket, and what the receiving device does with it.
/// </summary>
/// <remarks>
/// The tests use real loopback sockets (127.0.0.1, port 0) rather than a pipe, because the point of W6-16 is that
/// two processes can hand the envelope over without a human copying a file. Loopback needs no firewall rule and
/// leaves nothing listening after the test, which is why it is the only interface the feature is allowed to use.
/// The framing tests run over a MemoryStream instead, so a broken frame can be crafted byte by byte.
/// </remarks>
public sealed class SourceSyncTransferTests : IDisposable
{
    private const string PeerDeviceId = "peer-lan-1";
    private const string SnapshotId = "snapshot-2026-10-24";
    private const long FixedNow = 1_760_000_000_000L;
    private const string Challenge = "challenge-1";

    private readonly string _root;

    public SourceSyncTransferTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mh-transfer-" + Guid.NewGuid().ToString("N"));
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
            // A leftover temp directory is not a test failure.
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    // ---------------------------------------------------------------- framing

    [Fact]
    public async Task SendAndReceive_RoundTripASmallEnvelope()
    {
        byte[] payload = EnvelopeBytes();

        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload);

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(1, sent.FrameCount);
        Assert.Equal(payload.Length, sent.Bytes);

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(payload, received.Envelope);
        Assert.Equal(payload.Length, received.Bytes);
    }

    [Fact]
    public async Task SendAndReceive_ReassemblesAnEnvelopeLargerThanOneFrame()
    {
        // The 8192-byte frame ceiling is smaller than a real envelope, so the payload must be chunked, not cut.
        byte[] payload = EnvelopeBytes(nameLength: 9000);
        Assert.True(payload.Length > SourceSyncTransfer.ChunkPayloadBytes);
        Assert.Equal(2, SourceSyncTransfer.ChunkCount(payload.Length));

        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload);
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(2, sent.FrameCount);

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(payload, received.Envelope);
    }

    [Fact]
    public async Task Send_RefusesAnEmptyPayloadInsteadOfSendingAnEmptyStream()
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, ReadOnlyMemory<byte>.Empty);

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, sent.Reason);
        Assert.Equal(0, stream.Length);
    }

    [Fact]
    public async Task Send_RefusesAnEnvelopeAboveTheCeiling()
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent =
            await SourceSyncTransfer.SendAsync(stream, new byte[SourceSyncTransfer.MaximumEnvelopeBytes + 1]);

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, sent.Reason);
        Assert.Equal(0, stream.Length);
    }

    [Fact]
    public async Task Send_WritesTheChallengeFrameBeforeTheFirstChunk()
    {
        byte[] payload = EnvelopeBytes();
        using MemoryStream stream = new();

        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload, Challenge);
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(2, sent.FrameCount);

        stream.Position = 0;
        byte[]? first = await PairingFrame.ReadAsync(stream);
        Assert.NotNull(first);
        Assert.Equal(SourceSyncTransfer.ChallengeFrameKind, first![0]);
        Assert.Equal(Challenge, Encoding.UTF8.GetString(first, 1, first.Length - 1));

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, Challenge);
        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(Challenge, received.Challenge);
    }

    [Fact]
    public async Task Receive_RejectsAMismatchedChallengeBeforeReadingTheEnvelope()
    {
        byte[] payload = EnvelopeBytes();
        using MemoryStream stream = new();
        await SourceSyncTransfer.SendAsync(stream, payload, "a-different-challenge");

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, Challenge);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receive_RequiresTheChallengeWhenTheInvitationHasOne()
    {
        byte[] payload = EnvelopeBytes();
        using MemoryStream stream = new();
        await SourceSyncTransfer.SendAsync(stream, payload);

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, Challenge);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receive_AcceptsAnUnchallengedTransferWhenNoChallengeWasExpected()
    {
        byte[] payload = EnvelopeBytes();
        using MemoryStream stream = new();
        await SourceSyncTransfer.SendAsync(stream, payload, Challenge);

        stream.Position = 0;
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(Challenge, received.Challenge);
        Assert.Equal(payload, received.Envelope);
    }

    [Fact]
    public async Task Receive_ReportsAnEmptyStreamAsTruncated()
    {
        using MemoryStream stream = new();

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receive_RejectsAnUnknownFrameKind()
    {
        using MemoryStream stream = new(await FrameAsync(0x03, total: 4, offset: 0, payload: new byte[4]));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("0x03", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task Receive_ReportsAPeerThatStopsMidwayAsTruncated()
    {
        // 20 bytes announced, 10 delivered, then the peer closes.
        using MemoryStream stream = new(await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 20, offset: 0, payload: new byte[10]));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, received.Reason);
        Assert.Contains("10 of 20", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task Receive_RejectsAChunkWhoseOffsetSkipsBytes()
    {
        byte[] first = await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 20, offset: 0, payload: new byte[10]);
        byte[] second = await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 20, offset: 15, payload: new byte[5]);
        using MemoryStream stream = new(Combine(first, second));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receive_RejectsATotalThatChangesBetweenChunks()
    {
        byte[] first = await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 20, offset: 0, payload: new byte[10]);
        byte[] second = await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 30, offset: 10, payload: new byte[10]);
        using MemoryStream stream = new(Combine(first, second));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
    }

    [Fact]
    public async Task Receive_RejectsAChunkThatOverrunsTheAnnouncedTotal()
    {
        using MemoryStream stream = new(
            await FrameAsync(SourceSyncTransfer.ChunkFrameKind, total: 10, offset: 0, payload: new byte[20]));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receive_RefusesAnAnnouncedTotalAboveTheCeilingWithoutBufferingIt()
    {
        // A hostile peer must not be able to make the receiver allocate 8 MiB+ on a promise.
        using MemoryStream stream = new(
            await FrameAsync(
                SourceSyncTransfer.ChunkFrameKind,
                total: SourceSyncTransfer.MaximumEnvelopeBytes + 1,
                offset: 0,
                payload: new byte[8]));

        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.PayloadTooLarge, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public void ChunkCount_UsesTheFrameCeiling()
    {
        Assert.Equal(0, SourceSyncTransfer.ChunkCount(0));
        Assert.Equal(1, SourceSyncTransfer.ChunkCount(1));
        Assert.Equal(1, SourceSyncTransfer.ChunkCount(SourceSyncTransfer.ChunkPayloadBytes));
        Assert.Equal(2, SourceSyncTransfer.ChunkCount(SourceSyncTransfer.ChunkPayloadBytes + 1));
        Assert.Equal(PairingFrame.MaximumPayloadBytes, SourceSyncTransfer.ChunkPayloadBytes + 9);
    }

    [Fact]
    public void Reasons_AreAClosedSetWithoutDuplicates()
    {
        Assert.Equal(SourceSyncTransferReasons.All.Count, SourceSyncTransferReasons.All.Distinct(StringComparer.Ordinal).Count());
        Assert.Contains(SourceSyncTransferReasons.NotLoopback, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.FrameInvalid, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.FrameTruncated, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.PayloadTooLarge, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.ChallengeMismatch, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.EnvelopeInvalid, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.TransferTimeout, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.ListenerFailed, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.ConnectFailed, SourceSyncTransferReasons.All);
        Assert.Contains(SourceSyncTransferReasons.IoFailed, SourceSyncTransferReasons.All);
        Assert.Equal(10, SourceSyncTransferReasons.All.Count);
    }

    // ------------------------------------------------------- loopback gate

    [Fact]
    public void Address_AcceptsTheLoopbackSpellingsAndBlank()
    {
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress("127.0.0.1"));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress("::1"));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress("localhost"));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress("LOCALHOST"));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress(" 127.0.0.1 "));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress(""));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress(null));
        Assert.True(SourceSyncTransferAddress.IsLoopbackAddress("127.0.0.2"));
    }

    [Fact]
    public void Address_RefusesEveryOtherAddressBeforeAnySocketExists()
    {
        foreach (string refused in new[] { "0.0.0.0", "::", "192.168.1.5", "10.0.0.1", "example.com", "[::]" })
        {
            Assert.False(SourceSyncTransferAddress.IsLoopbackAddress(refused));
            Assert.False(
                SourceSyncTransferAddress.TryResolveLoopback(refused, 8080, out IPEndPoint? endpoint, out string? detail));
            Assert.Null(endpoint);
            Assert.False(string.IsNullOrWhiteSpace(detail));
        }
    }

    [Fact]
    public void Address_TellsTheUserWhyANonLoopbackAddressIsRefused()
    {
        bool accepted = SourceSyncTransferAddress.TryResolveLoopback(
            "0.0.0.0", 0, out _, out string? detail);

        Assert.False(accepted);
        Assert.Contains("before any socket was created", detail ?? "", StringComparison.Ordinal);
        Assert.Contains("firewall", detail ?? "", StringComparison.Ordinal);
        Assert.Contains("127.0.0.1", detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public void Listener_RefusesANonLoopbackAddressWithoutBindingAnything()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start("0.0.0.0");

        Assert.False(start.Started);
        Assert.Null(start.Listener);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, start.Reason);
        // The refusal is a decision, not a bind that failed: the same verdict comes back every time.
        Assert.NotEqual(SourceSyncTransferReasons.ListenerFailed, start.Reason);
        Assert.Equal(start.Reason, SourceSyncTransferListener.Start("0.0.0.0").Reason);
        Assert.Equal(start.Reason, SourceSyncTransferListener.Start("192.168.1.5").Reason);
    }

    [Fact]
    public async Task Listener_BindsLoopbackOnAFreePort()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();

        Assert.True(start.Started, start.Detail);
        Assert.NotNull(start.Listener);
        await using SourceSyncTransferListener listener = start.Listener!;

        Assert.True(listener.Port > 0);
        Assert.True(IPAddress.IsLoopback(listener.LocalEndPoint.Address));
        Assert.Equal(listener.Port, listener.LocalEndPoint.Port);
    }

    [Fact]
    public async Task Client_RefusesToDialANonLoopbackAddress()
    {
        SourceSyncTransferResult sent =
            await SourceSyncTransferClient.SendAsync("192.168.1.5", 4662, EnvelopeBytes());

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, sent.Reason);
        Assert.Equal(0, sent.Bytes);
    }

    [Fact]
    public async Task Client_ReportsConnectFailedWhenNothingListens()
    {
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            1,
            EnvelopeBytes(),
            timeout: TimeSpan.FromSeconds(10));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ConnectFailed, sent.Reason);
    }

    // ------------------------------------------------- end to end, loopback

    [Fact]
    public async Task LoopbackTransfer_ImportsTheEnvelopeAndAdvancesTheBaseline()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, FileSourceSyncBaselineStore baselines) = NewGuest("guest");
        byte[] payload = EnvelopeBytes(nameLength: 40);

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> sending = listener.SendOnceAsync(payload, Challenge);
        SourceSyncInboxResult received = await inbox.DialAndImportAsync(
            "127.0.0.1",
            listener.Port,
            Writer(sources),
            Challenge,
            timeout: TimeSpan.FromSeconds(15));
        SourceSyncTransferResult sent = await sending;

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(received.Succeeded, received.Detail);
        Assert.NotNull(received.Report);
        Assert.Equal(1, received.Report!.Applied);
        Assert.True(received.Report.BaselineUpdated);
        Assert.Equal(1, received.Report.BaselineEntryCount);
        Assert.Equal(PeerDeviceId, received.Envelope?.DeviceId);
        // One challenge frame plus one chunk: the challenge is the first frame, always.
        Assert.Equal(2, received.FrameCount);
        Assert.Equal(1, SourceSyncTransfer.ChunkCount(payload.Length));
        Assert.Equal(payload.Length, received.Bytes);

        // The source landed in the local store, and the baseline is on disk, not in memory.
        IReadOnlyList<LocalSourceRecord> written = sources.GetAll();
        Assert.Single(written);
        Assert.Equal("src-1", written[0].SyncSourceId);
        Assert.True(File.Exists(baselines.FilePath));

        FileSourceSyncBaselineStore reopened = new(Path.Combine(_root, "guest"));
        Assert.Single(reopened.Load(PeerDeviceId).Entries);
        Assert.Equal("src-1", reopened.Load(PeerDeviceId).Entries[0].SyncSourceId);
    }

    [Fact]
    public async Task LoopbackTransfer_RepeatingTheSameSnapshotWritesNothing()
    {
        string guest = "guest";
        (SourceSyncInbox first, FileLocalSourceStore sources, FileSourceSyncBaselineStore baselines) = NewGuest(guest);
        byte[] payload = EnvelopeBytes(nameLength: 40);

        SourceSyncInboxResult firstRound = await TransferOnceAsync(first, sources, payload);
        Assert.True(firstRound.Succeeded, firstRound.Detail);
        Assert.Equal(1, firstRound.Report!.Applied);

        string sourcesPath = sources.FilePath;
        string baselinePath = baselines.FilePath;
        byte[] sourcesBefore = File.ReadAllBytes(sourcesPath);
        byte[] baselineBefore = File.ReadAllBytes(baselinePath);

        // A brand new inbox over the same directory: the idempotency must live in the files, not in the process.
        (SourceSyncInbox second, FileLocalSourceStore sourcesAgain, FileSourceSyncBaselineStore baselinesAgain) =
            NewGuest(guest, createDirectory: false);
        SourceSyncInboxResult secondRound = await TransferOnceAsync(second, sourcesAgain, payload);

        Assert.True(secondRound.Succeeded, secondRound.Detail);
        Assert.True(secondRound.IsNoChange);
        Assert.Equal(0, secondRound.Report!.Applied);
        Assert.Equal(0, secondRound.Report.Withheld);
        Assert.False(secondRound.Report.BaselineUpdated);
        Assert.Equal(1, secondRound.Report.BaselineEntryCount);
        Assert.Equal(sourcesBefore, File.ReadAllBytes(sourcesPath));
        Assert.Equal(baselineBefore, File.ReadAllBytes(baselinesAgain.FilePath));
        Assert.Single(sourcesAgain.GetAll());
    }

    [Fact]
    public async Task LoopbackTransfer_RejectsTheWrongChallengeWithoutWriting()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, FileSourceSyncBaselineStore baselines) = NewGuest("guest");
        byte[] payload = EnvelopeBytes();

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> sending = listener.SendOnceAsync(payload, "a-different-challenge");
        SourceSyncInboxResult received = await inbox.DialAndImportAsync(
            "127.0.0.1",
            listener.Port,
            Writer(sources),
            Challenge,
            timeout: TimeSpan.FromSeconds(15));
        await sending;

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.ChallengeMismatch, received.Reason);
        Assert.Empty(sources.GetAll());
        Assert.False(File.Exists(baselines.FilePath));
    }

    [Fact]
    public async Task LoopbackTransfer_ReportsAPeerThatVanishesBeforeSendingAnything()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task connecting = Task.Run(async () =>
        {
            using TcpClient client = new();
            await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        });

        SourceSyncTransferResult received = await listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(15));
        await connecting;

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, received.Reason);
        Assert.Empty(received.Envelope);
    }

    // ------------------------------------------------------- import, no socket

    [Fact]
    public async Task Import_RejectsAPayloadThatIsNotAnEnvelope()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("guest");

        SourceSyncInboxResult result = await inbox.ImportAsync(
            Encoding.UTF8.GetBytes("this is not an envelope"), Writer(sources));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.Empty(sources.GetAll());
    }

    [Fact]
    public async Task Import_RejectsANonUtf8Payload()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("guest");

        SourceSyncInboxResult result = await inbox.ImportAsync(new byte[] { 0x80, 0xFF, 0xFE, 0x81 }, Writer(sources));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.Equal("not-utf8", result.Detail);
        Assert.Empty(sources.GetAll());
    }

    [Fact]
    public async Task Import_RejectsAnEmptyPayload()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("guest");

        SourceSyncInboxResult result = await inbox.ImportAsync([], Writer(sources));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.Equal("empty-payload", result.Detail);
    }

    [Fact]
    public async Task Import_RejectsAnEnvelopeWithAMissingField()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("guest");
        const string broken =
            "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"peer-lan-1\",\"snapshotId\":\"\","
            + "\"createdAtEpochMs\":0,\"capabilities\":{\"canSendSecrets\":false,\"sourceTypes\":[\"emby\"]},"
            + "\"sources\":[],\"deletions\":[]}";

        SourceSyncInboxResult result = await inbox.ImportAsync(Encoding.UTF8.GetBytes(broken), Writer(sources));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.Contains("snapshotId", result.Detail ?? "", StringComparison.Ordinal);
        Assert.Empty(sources.GetAll());
    }

    [Fact]
    public async Task Import_RejectsAnUnsupportedSchemaVersion()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("guest");
        // Written by hand: Serialize refuses to write anything but version 1, so a newer peer must be faked.
        const string future =
            """
            {"schemaVersion":2,"minimumReaderVersion":2,"deviceId":"peer-lan-1","snapshotId":"s-1","createdAtEpochMs":0,"capabilities":{"canSendSecrets":false,"sourceTypes":["emby"]},"sources":[],"deletions":[]}
            """;

        SourceSyncInboxResult result = await inbox.ImportAsync(Encoding.UTF8.GetBytes(future), Writer(sources));

        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, result.Reason);
        Assert.Contains("2", result.Detail ?? "", StringComparison.Ordinal);
        Assert.Empty(sources.GetAll());
    }

    [Fact]
    public async Task StreamReceive_ReportsTheFramesTheTransferUsed()
    {
        (SourceSyncInbox inbox, FileLocalSourceStore sources, _) = NewGuest("stream-guest");
        byte[] payload = EnvelopeBytes();
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload, Challenge);
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(2, sent.FrameCount);

        stream.Position = 0;
        SourceSyncInboxResult received = await inbox.ReceiveAsync(stream, Challenge, Writer(sources));

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(sent.FrameCount, received.FrameCount);
        Assert.Equal(payload.Length, received.Bytes);
        Assert.NotEmpty(sources.GetAll());
    }

    // ------------------------------------------------------------- helpers

    private static async Task<SourceSyncInboxResult> TransferOnceAsync(
        SourceSyncInbox inbox,
        FileLocalSourceStore sources,
        byte[] payload)
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> sending = listener.SendOnceAsync(payload, Challenge);
        SourceSyncInboxResult received = await inbox.DialAndImportAsync(
            "127.0.0.1",
            listener.Port,
            Writer(sources),
            Challenge,
            timeout: TimeSpan.FromSeconds(15));
        SourceSyncTransferResult sent = await sending;
        Assert.True(sent.Succeeded, sent.Detail);
        return received;
    }

    private (SourceSyncInbox Inbox, FileLocalSourceStore Sources, FileSourceSyncBaselineStore Baselines) NewGuest(
        string name,
        bool createDirectory = true)
    {
        string directory = Path.Combine(_root, name);
        if (createDirectory)
        {
            Directory.CreateDirectory(directory);
        }

        FileLocalSourceStore sources = new(directory);
        FileSourceSyncBaselineStore baselines = new(directory);
        return (new SourceSyncInbox(sources, baselines), sources, baselines);
    }

    /// <summary>The writer a guest would use: it stores the record and reports what it did.</summary>
    private static SourceSyncRecordWriter Writer(FileLocalSourceStore store) => (item, record) =>
    {
        if (record is null)
        {
            return Task.FromResult(SourceSyncWriteOutcome.Refused("deletions are not imported by this test"));
        }

        string? url = record.Endpoints.FirstOrDefault(endpoint => endpoint.IsPrimary)?.Url
            ?? record.Endpoints.FirstOrDefault()?.Url;

        store.Save(new LocalSourceRecord
        {
            ServerId = item.ServerId ?? "srv-" + item.SyncSourceId,
            SyncSourceId = record.SyncSourceId,
            Type = WindowsSourceTypeMap.ToServerType(record.SourceType.Wire),
            SourceTypeRaw = record.SourceType.Raw,
            Url = url,
            AccountScope = record.AccountScope,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        });

        return Task.FromResult(SourceSyncWriteOutcome.Written("imported " + record.SyncSourceId));
    };

    private static byte[] EnvelopeBytes(int nameLength = 12)
        => Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(Envelope(nameLength)));

    private static SourceSyncEnvelope Envelope(int nameLength = 12)
    {
        return new SourceSyncEnvelope
        {
            DeviceId = PeerDeviceId,
            SnapshotId = SnapshotId,
            CreatedAtEpochMs = FixedNow,
            Capabilities = new SyncCapabilities { SourceTypes = [SyncSourceTypes.Emby] },
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = "src-1",
                    SourceType = SyncSourceTypes.Parse(SyncSourceTypes.Emby),
                    DisplayName = "Home " + new string('x', nameLength),
                    RecordRevision = 1,
                    FieldVersion = 1,
                    Endpoints = [new SourceEndpoint { Url = "http://192.168.5.20:8096/emby", IsPrimary = true }],
                },
            ],
        };
    }

    private static async Task<byte[]> FrameAsync(byte kind, int total, int offset, byte[] payload)
    {
        byte[] frame = new byte[9 + payload.Length];
        frame[0] = kind;
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(1, 4), total);
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(5, 4), offset);
        payload.CopyTo(frame, 9);

        using MemoryStream stream = new();
        await PairingFrame.WriteAsync(stream, frame);
        return stream.ToArray();
    }

    private static byte[] Combine(byte[] first, byte[] second)
    {
        byte[] combined = new byte[first.Length + second.Length];
        first.CopyTo(combined, 0);
        second.CopyTo(combined, first.Length);
        return combined;
    }
}

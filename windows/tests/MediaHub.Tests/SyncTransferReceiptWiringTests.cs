using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-39: the sync page's receipt switches and the roundtrip's receipt exchange.
/// </summary>
/// <remarks>
/// The receipt exists because a close cannot tell "the peer decoded this" apart from "the peer refused it"
/// (W6-30). Everything asserted here therefore has to be evidence the peer actually produced - a byte that
/// arrived, a byte that never did - and never the presenter's own opinion about itself.
/// </remarks>
public sealed class SyncTransferReceiptPresenterTests : IDisposable
{
    private const string LocalDeviceId = "presenter-w639-1";
    private const string PeerDeviceId = "presenter-w639-peer";
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";

    private readonly List<SyncTransferPresenter> _presenters = [];
    private readonly List<RawPeer> _peers = [];
    private readonly List<string> _directories = [];

    /// <summary>All 24 stable codes a transfer can produce are explained, and each one keeps its own code.</summary>
    [Fact]
    public void Describe_reason_knows_every_stable_code_the_transfer_layer_can_produce()
    {
        List<string> codes =
        [
            .. SourceSyncTransferReasons.All,
            .. SourceSyncTransferAuthReasons.All,
            .. SourceSyncTlsReasons.All,
            .. SourceSyncDeliveryReasons.All,
            .. SourceSyncReceiptReasons.All,
        ];

        Assert.Equal(24, codes.Count);
        foreach (string code in codes)
        {
            string text = SyncTransferCopy.DescribeReason(code);
            Assert.DoesNotContain("未识别的结果码", text, StringComparison.Ordinal);
            Assert.Contains(code, text, StringComparison.Ordinal);
        }
    }

    /// <summary>A missing receipt has to read as "no evidence", never as a delivery.</summary>
    [Theory]
    [InlineData(SourceSyncReceiptReasons.ReceiptMissing)]
    [InlineData(SourceSyncReceiptReasons.ReceiptTimeout)]
    [InlineData(SourceSyncReceiptReasons.ReceiptInvalid)]
    [InlineData(SourceSyncReceiptReasons.ReceiptNotEnabled)]
    public void Describe_reason_explains_each_receipt_code(string code)
    {
        string text = SyncTransferCopy.DescribeReason(code);

        Assert.Contains("回执", text, StringComparison.Ordinal);
        Assert.Contains(code, text, StringComparison.Ordinal);
        Assert.DoesNotContain("未识别的结果码", text, StringComparison.Ordinal);
    }

    [Fact]
    public void Describe_reason_still_echoes_an_unknown_code_instead_of_inventing_one()
    {
        string text = SyncTransferCopy.DescribeReason("w6-39-not-a-code");

        Assert.Contains("未识别的结果码", text, StringComparison.Ordinal);
        Assert.Contains("w6-39-not-a-code", text, StringComparison.Ordinal);
    }

    /// <summary>The receipt outranks the close, because the close happens just as readily on a refusal.</summary>
    [Fact]
    public void Describe_sent_prefers_the_receipt_and_says_what_it_proves()
    {
        string withReceipt = SyncTransferCopy.DescribeSent(120, 1, confirmed: true, TimeSpan.FromMilliseconds(8), receiptConfirmed: true);
        string closeOnly = SyncTransferCopy.DescribeSent(120, 1, confirmed: true, TimeSpan.FromMilliseconds(8));
        string neither = SyncTransferCopy.DescribeSent(120, 1, confirmed: null, TimeSpan.Zero);

        Assert.Contains(SyncTransferCopy.ReceiptConfirmedSuffix, withReceipt, StringComparison.Ordinal);
        Assert.DoesNotContain("对端在", withReceipt, StringComparison.Ordinal);
        Assert.Contains("对端在", closeOnly, StringComparison.Ordinal);
        Assert.Contains("没有要求送达确认", neither, StringComparison.Ordinal);
    }

    [Fact]
    public void The_default_forms_do_not_ask_for_a_receipt()
    {
        Assert.False(new SyncTransferReceiveForm().WriteReceipt);
        Assert.False(new SyncTransferSendForm().AwaitReceipt);
        Assert.False(SyncTransferReceiveState.Idle.WritesReceipt);
        Assert.Equal(SourceSyncReceipt.DefaultBudget, new SyncTransferPresenter(LocalDeviceId).ReceiptBudget);
    }

    [Fact]
    public async Task A_prepared_receive_announces_that_it_will_write_a_receipt()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { WriteReceipt = true });

        Assert.True(prepared.Succeeded, prepared.Summary);
        Assert.True(presenter.ReceiveState.WritesReceipt);
        Assert.Contains("回执（0x04）", presenter.ReceiveState.StatusText, StringComparison.Ordinal);

        SyncTransferOutcome stopped = await presenter.StopReceiveAsync();
        Assert.True(stopped.Succeeded, stopped.Summary);
        Assert.False(presenter.ReceiveState.WritesReceipt);
    }

    /// <summary>The switch is only worth anything if the byte really leaves after the envelope decoded.</summary>
    [Fact]
    public async Task A_receiver_that_was_told_to_write_a_receipt_answers_with_the_receipt_byte()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { WriteReceipt = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        Assert.True(prepared.BoundPort is > 0, prepared.Summary);

        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        byte[] answer = await SendEnvelopeAndCollectAnswerAsync(
            prepared.BoundPort!.Value,
            EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby")),
            TimeSpan.FromSeconds(10));
        SyncTransferOutcome received = await waiting;

        Assert.True(received.Succeeded, received.Summary);
        Assert.Equal([SourceSyncReceipt.ReceiptFrameKind], answer);
        Assert.True(received.ReceiptWritten);
        Assert.NotNull(received.EnvelopeJson);
    }

    [Fact]
    public async Task A_receiver_without_the_switch_answers_nothing()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm());
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        byte[] answer = await SendEnvelopeAndCollectAnswerAsync(
            prepared.BoundPort!.Value,
            EnvelopeOf(PeerDeviceId, Source("sync-1", "Living Room Emby")),
            TimeSpan.FromSeconds(10));
        SyncTransferOutcome received = await waiting;

        Assert.True(received.Succeeded, received.Summary);
        Assert.Empty(answer);
        Assert.Null(received.ReceiptWritten);
    }

    /// <summary>Acknowledging a payload this build cannot read would say the exact opposite of the truth.</summary>
    [Fact]
    public async Task A_payload_that_does_not_decode_is_never_acknowledged()
    {
        SyncTransferPresenter presenter = NewPresenter();
        SyncTransferOutcome prepared = presenter.PrepareReceive(new SyncTransferReceiveForm { WriteReceipt = true });
        Task<SyncTransferOutcome> waiting = presenter.ReceiveOnceAsync(TimeSpan.FromSeconds(10));
        byte[] answer = await SendEnvelopeAndCollectAnswerAsync(
            prepared.BoundPort!.Value,
            Encoding.UTF8.GetBytes("{ this is not a source envelope }"),
            TimeSpan.FromSeconds(10));
        SyncTransferOutcome received = await waiting;

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, received.Reason);
        Assert.Empty(answer);
    }

    [Fact]
    public async Task A_send_that_waits_for_a_receipt_reports_it()
    {
        SyncTransferPresenter receiver = NewPresenter(deviceId: "presenter-w639-receiver");
        SyncTransferOutcome prepared = receiver.PrepareReceive(new SyncTransferReceiveForm { WriteReceipt = true });
        Assert.True(prepared.Succeeded, prepared.Summary);
        Task<SyncTransferOutcome> waiting = receiver.ReceiveOnceAsync(TimeSpan.FromSeconds(10));

        SyncTransferPresenter sender = NewPresenter(withEnvelope: true);
        SyncTransferOutcome sent = await sender.SendAsync(SendForm(prepared.BoundPort!.Value, awaitReceipt: true));
        SyncTransferOutcome received = await waiting;

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.True(sent.ReceiptConfirmed);
        Assert.Contains(SyncTransferCopy.ReceiptConfirmedSuffix, sent.Summary, StringComparison.Ordinal);
        Assert.True(received.Succeeded, received.Summary);
        Assert.True(received.ReceiptWritten);
    }

    [Fact]
    public async Task A_peer_that_closes_without_a_receipt_is_receipt_missing()
    {
        RawPeer peer = NewPeer(hold: false);
        SyncTransferPresenter sender = NewPresenter(withEnvelope: true);

        SyncTransferOutcome sent = await sender.SendAsync(SendForm(peer.Port, awaitReceipt: true));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, sent.Reason);
        Assert.Null(sent.ReceiptConfirmed);
        Assert.Contains("回执", sent.Summary, StringComparison.Ordinal);
        peer.Release();
        await peer.Completion;
        Assert.Null(peer.Failure);
    }

    [Fact]
    public async Task A_peer_that_holds_the_connection_is_receipt_timeout()
    {
        RawPeer peer = NewPeer(hold: true);
        SyncTransferPresenter sender = NewPresenter(withEnvelope: true);
        sender.ReceiptBudget = TimeSpan.FromMilliseconds(400);

        (SyncTransferOutcome sent, TimeSpan elapsed) = await TimedAsync(() => sender.SendAsync(SendForm(peer.Port, awaitReceipt: true)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptTimeout, sent.Reason);
        Assert.True(elapsed < TimeSpan.FromSeconds(5), elapsed.ToString());
        peer.Release();
        await peer.Completion;
    }

    [Fact]
    public async Task Without_the_switch_a_send_reports_no_receipt_and_no_wait()
    {
        RawPeer peer = NewPeer(hold: false);
        SyncTransferPresenter sender = NewPresenter(withEnvelope: true);

        SyncTransferOutcome sent = await sender.SendAsync(SendForm(peer.Port, awaitReceipt: false));

        Assert.True(sent.Succeeded, sent.Summary);
        Assert.Null(sent.ReceiptConfirmed);
        Assert.Contains("没有要求送达确认", sent.Summary, StringComparison.Ordinal);
        peer.Release();
        await peer.Completion;
    }

    [Fact]
    public void The_forms_still_redact_the_secret()
    {
        SyncTransferReceiveForm receive = new() { Secret = "correct horse battery staple", WriteReceipt = true };
        SyncTransferSendForm send = new() { Secret = "correct horse battery staple", AwaitReceipt = true };

        Assert.DoesNotContain("correct horse", receive.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("correct horse", send.ToString(), StringComparison.Ordinal);
        Assert.Contains("<redacted>", receive.ToString(), StringComparison.Ordinal);
        Assert.Contains("<redacted>", send.ToString(), StringComparison.Ordinal);
        Assert.Contains("WriteReceipt = True", receive.ToString(), StringComparison.Ordinal);
        Assert.Contains("AwaitReceipt = True", send.ToString(), StringComparison.Ordinal);
    }

    public void Dispose()
    {
        foreach (SyncTransferPresenter presenter in _presenters)
        {
            presenter.Dispose();
        }

        foreach (RawPeer peer in _peers)
        {
            peer.Dispose();
        }

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
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    private SyncTransferPresenter NewPresenter(bool withEnvelope = false, string deviceId = LocalDeviceId)
    {
        SyncTransferPresenter presenter = withEnvelope
            ? new SyncTransferPresenter(deviceId, () => Envelope(deviceId, Source("sync-1", "Living Room Emby")))
            : new SyncTransferPresenter(deviceId);
        _presenters.Add(presenter);
        return presenter;
    }

    private RawPeer NewPeer(bool hold)
    {
        RawPeer peer = new(hold);
        _peers.Add(peer);
        return peer;
    }

    private static SyncTransferSendForm SendForm(int port, bool awaitReceipt) => new()
    {
        Host = "127.0.0.1",
        PortText = port.ToString(CultureInfo.InvariantCulture),
        AwaitReceipt = awaitReceipt,
    };

    private static async Task<byte[]> SendEnvelopeAndCollectAnswerAsync(int port, byte[] payload, TimeSpan budget)
    {
        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, port);
        using NetworkStream stream = client.GetStream();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload).WaitAsync(budget);
        Assert.True(sent.Succeeded, sent.Reason);

        byte[] answer = new byte[1];
        int read = await stream.ReadAsync(answer.AsMemory(0, 1)).AsTask().WaitAsync(budget);
        return read == 0 ? [] : answer[..read];
    }

    private static async Task<(SyncTransferOutcome Outcome, TimeSpan Elapsed)> TimedAsync(Func<Task<SyncTransferOutcome>> body)
    {
        DateTime started = DateTime.UtcNow;
        SyncTransferOutcome outcome = await body();
        return (outcome, DateTime.UtcNow - started);
    }

    private static byte[] EnvelopeOf(string deviceId, params LocalSourceRecord[] records) =>
        Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(Envelope(deviceId, records)));

    private static SourceSyncEnvelope Envelope(string deviceId, params LocalSourceRecord[] records)
    {
        InMemoryLocalSourceStore store = new();
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }

        SourceSyncPeerRound sender = new(deviceId, store);
        return sender.Export();
    }

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

    /// <summary>A peer that reads one envelope and then either closes or keeps the socket open (W6-30's blind spot).</summary>
    private sealed class RawPeer : IDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _abort = new();
        private readonly TaskCompletionSource _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly Task _serving;
        private readonly bool _hold;

        public RawPeer(bool hold)
        {
            _hold = hold;
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _serving = Task.Run(ServeAsync);
        }

        public int Port { get; }

        public Task Completion => _serving;

        public Exception? Failure { get; private set; }

        public void Release() => _release.TrySetResult();

        public void Dispose()
        {
            _release.TrySetResult();
            _abort.Cancel();
            _listener.Stop();
            try
            {
                _serving.Wait(TimeSpan.FromSeconds(10));
            }
            catch (AggregateException)
            {
            }

            _abort.Dispose();
        }

        private async Task ServeAsync()
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync(_abort.Token);
                using NetworkStream stream = client.GetStream();
                SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null, _abort.Token);
                if (!received.Succeeded)
                {
                    Failure = new InvalidOperationException(received.Reason);
                    return;
                }

                if (_hold)
                {
                    await _release.Task.WaitAsync(TimeSpan.FromSeconds(20));
                }
            }
            catch (Exception error) when (error is OperationCanceledException or IOException or SocketException or ObjectDisposedException or TimeoutException)
            {
            }
        }
    }
}

/// <summary>W6-39: one round now exchanges the receipt, and a missing receipt never fails a delivered envelope.</summary>
public sealed class SyncTransferRoundtripReceiptTests
{
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";

    [Fact]
    public async Task A_roundtrip_with_a_receipt_budget_exchanges_evidence_both_ways()
    {
        InMemoryLocalSourceStore senderStore = new();
        senderStore.Save(Source("src-1", "Home"));
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new("dev-a", senderStore);
        SourceSyncPeerRound receiver = new("dev-b", receiverStore);

        SourceSyncPeerRoundResult round = await SourceSyncPeerRound.ExchangeAsync(
            sender,
            receiver,
            Writer(receiverStore),
            receiptBudget: TimeSpan.FromSeconds(10));

        Assert.True(round.Succeeded, round.Reason);
        Assert.True(round.ReceiptConfirmed);
        Assert.True(round.ReceiptWritten);
        Assert.Null(round.ReceiptReason);
        Assert.Equal(1, round.FrameCount);
        Assert.Single(receiverStore.GetAll());
    }

    [Fact]
    public async Task A_roundtrip_without_a_receipt_budget_reports_no_receipt()
    {
        InMemoryLocalSourceStore senderStore = new();
        senderStore.Save(Source("src-1", "Home"));
        InMemoryLocalSourceStore receiverStore = new();
        SourceSyncPeerRound sender = new("dev-a", senderStore);
        SourceSyncPeerRound receiver = new("dev-b", receiverStore);

        SourceSyncPeerRoundResult round = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Writer(receiverStore));

        Assert.True(round.Succeeded, round.Reason);
        Assert.Null(round.ReceiptConfirmed);
        Assert.Null(round.ReceiptWritten);
        Assert.Null(round.ReceiptReason);
        Assert.Single(receiverStore.GetAll());
    }

    [Fact]
    public async Task A_roundtrip_receipt_adds_no_frame_to_the_envelope()
    {
        InMemoryLocalSourceStore senderStore = new();
        senderStore.Save(Source("src-1", "Home"));
        InMemoryLocalSourceStore withoutReceiptStore = new();
        SourceSyncPeerRoundResult withoutReceipt = await SourceSyncPeerRound.ExchangeAsync(
            new SourceSyncPeerRound("dev-a", senderStore),
            new SourceSyncPeerRound("dev-b", withoutReceiptStore),
            Writer(withoutReceiptStore));

        InMemoryLocalSourceStore withReceiptStore = new();
        SourceSyncPeerRoundResult withReceipt = await SourceSyncPeerRound.ExchangeAsync(
            new SourceSyncPeerRound("dev-a", senderStore),
            new SourceSyncPeerRound("dev-b", withReceiptStore),
            Writer(withReceiptStore),
            receiptBudget: TimeSpan.FromSeconds(10));

        Assert.Equal(SourceSyncTransfer.ChunkCount(withReceipt.Bytes), withReceipt.FrameCount);
        Assert.Equal(withoutReceipt.FrameCount, withReceipt.FrameCount);
        Assert.Equal(withoutReceipt.Bytes, withReceipt.Bytes);
    }

    [Fact]
    public void The_receipt_helper_recognises_exactly_its_own_four_codes()
    {
        foreach (string code in SourceSyncReceiptReasons.All)
        {
            Assert.True(SourceSyncReceiptReasons.IsReceiptReason(code), code);
        }

        Assert.False(SourceSyncReceiptReasons.IsReceiptReason(SourceSyncTransferReasons.IoFailed));
        Assert.False(SourceSyncReceiptReasons.IsReceiptReason(SourceSyncTransferReasons.TransferTimeout));
        Assert.False(SourceSyncReceiptReasons.IsReceiptReason(null));
        Assert.False(SourceSyncReceiptReasons.IsReceiptReason(""));
        Assert.False(SourceSyncReceiptReasons.IsReceiptReason("receipt-missing-typo"));
    }

    private static SourceSyncRecordWriter Writer(ILocalSourceStore store) => (item, record) =>
    {
        if (record is null)
        {
            return Task.FromResult(SourceSyncWriteOutcome.Refused("this fixture never writes a deletion"));
        }

        store.Save(new LocalSourceRecord
        {
            ServerId = item.SyncSourceId,
            SyncSourceId = record.SyncSourceId,
            Type = ServerType.Emby,
            SourceTypeRaw = record.SourceType.Raw,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        });
        return Task.FromResult(SourceSyncWriteOutcome.Written("written by the W6-39 fixture"));
    };

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
}

/// <summary>W6-39: <c>transfer roundtrip</c> now reports the receipt of each direction.</summary>
public sealed class SyncCliRoundtripReceiptTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";
    private const string ReceiptLine = "receipt: received (0x04) - the peer decoded this envelope";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-w639-" + Guid.NewGuid().ToString("N"));

    public SyncCliRoundtripReceiptTests()
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
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    [Fact]
    public void Roundtrip_json_reports_a_receipt_for_both_directions()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"), Source("src-2", "Work"));

        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Equal("", stderr.Trim());
        using JsonDocument document = JsonDocument.Parse(stdout.Trim());
        JsonElement rounds = document.RootElement.GetProperty("rounds");
        Assert.Equal(2, rounds.GetArrayLength());
        foreach (JsonElement round in rounds.EnumerateArray())
        {
            Assert.True(round.GetProperty("receiptConfirmed").GetBoolean());
            Assert.Equal(JsonValueKind.Null, round.GetProperty("receiptReason").ValueKind);
        }
    }

    [Fact]
    public void Roundtrip_text_prints_the_receipt_line_once_per_direction()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"));

        (int exitCode, string stdout, string _) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Equal(2, CountOf(stdout, ReceiptLine));
    }

    [Fact]
    public void Roundtrip_refuses_both_receipt_flags()
    {
        (int sendExit, _, string sendError) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--send-receipt");
        (int awaitExit, _, string awaitError) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--await-receipt");

        Assert.Equal(CliExitCode.UsageError, sendExit);
        Assert.Contains("option-not-allowed-for-command:--send-receipt:transfer-roundtrip", sendError, StringComparison.Ordinal);
        Assert.Equal(CliExitCode.UsageError, awaitExit);
        Assert.Contains("option-not-allowed-for-command:--await-receipt:transfer-roundtrip", awaitError, StringComparison.Ordinal);
    }

    [Fact]
    public void Roundtrip_top_level_receipt_flags_stay_false()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"));

        (int exitCode, string stdout, string _) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = JsonDocument.Parse(stdout.Trim());
        JsonElement root = document.RootElement;
        Assert.False(root.GetProperty("sendReceipt").GetBoolean());
        Assert.False(root.GetProperty("awaitReceipt").GetBoolean());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("receiptConfirmed").ValueKind);
        Assert.Equal(JsonValueKind.Null, root.GetProperty("receiptWritten").ValueKind);
    }

    [Fact]
    public void Roundtrip_receipt_adds_no_frame_to_the_envelope()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"), Source("src-2", "Work"));

        (int exitCode, string stdout, string _) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = JsonDocument.Parse(stdout.Trim());
        foreach (JsonElement round in document.RootElement.GetProperty("rounds").EnumerateArray())
        {
            int bytes = round.GetProperty("bytes").GetInt32();
            Assert.True(bytes > 0);
            Assert.Equal(SourceSyncTransfer.ChunkCount(bytes), round.GetProperty("frames").GetInt32());
        }
    }

    [Fact]
    public void Roundtrip_with_apply_still_writes_both_sources()
    {
        Seed(Path.Combine(_root, "a"), Source("src-1", "Home"), Source("src-2", "Work"));

        (int exitCode, string stdout, string _) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "dev-a", "--device-b", "dev-b", "--apply", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        using JsonDocument document = JsonDocument.Parse(stdout.Trim());
        Assert.True(document.RootElement.GetProperty("applied").GetBoolean());
        Assert.Equal(2, document.RootElement.GetProperty("rounds")[0].GetProperty("report").GetProperty("applied").GetInt32());
        Assert.Equal(2, new FileLocalSourceStore(Path.Combine(_root, "b")).GetAll().Count);
    }

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

    private static int CountOf(string text, string needle)
    {
        int count = 0;
        int index = text.IndexOf(needle, StringComparison.Ordinal);
        while (index >= 0)
        {
            count++;
            index = text.IndexOf(needle, index + needle.Length, StringComparison.Ordinal);
        }

        return count;
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }
}

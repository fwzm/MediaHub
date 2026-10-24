using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>What one side of a peer round saw, in one object a test or the App can assert on.</summary>
/// <remarks>
/// <para>
/// The sender only ever fills in <see cref="Succeeded"/>, <see cref="Reason"/>, <see cref="Detail"/>,
/// <see cref="FrameCount"/> and <see cref="Bytes"/>; the receiver additionally carries the decoded
/// <see cref="Envelope"/>, the read-only <see cref="Preview"/> and the <see cref="Report"/>. Both halves exist so
/// that one value describes a whole loopback round: bytes left, bytes arrived, what the gate would write, and
/// what it actually wrote.
/// </para>
/// <para>
/// <see cref="Reason"/> is always a stable code — one of <see cref="SourceSyncTransferReasons"/> for transport
/// and decode failures — so a caller can branch on it without parsing a sentence, and so a test can prove that a
/// refused round wrote nothing rather than merely "reported a problem".
/// </para>
/// </remarks>
public sealed record SourceSyncPeerRoundResult
{
    /// <summary>True only when the payload arrived, decoded, and the import round ran to completion.</summary>
    public bool Succeeded { get; init; }

    /// <summary>
    /// Stable failure code: a <see cref="SourceSyncTransferReasons"/> value, or the transport reason the layer
    /// below reported (the listener, the client or the frame reader).
    /// </summary>
    public string? Reason { get; init; }

    /// <summary>Human-readable context for a failure; never contains an envelope body or a credential.</summary>
    public string? Detail { get; init; }

    /// <summary>The device that sent the payload.</summary>
    public string SenderDeviceId { get; init; } = "";

    /// <summary>The device that received it.</summary>
    public string ReceiverDeviceId { get; init; } = "";

    /// <summary>The envelope that arrived and decoded, when there was one.</summary>
    public SourceSyncEnvelope? Envelope { get; init; }

    /// <summary>The receiver's read-only preview, when the round got that far.</summary>
    public SourceSyncApplyPreview? Preview { get; init; }

    /// <summary>The receiver's apply report, including whether the baseline advanced.</summary>
    public SourceSyncApplyReport? Report { get; init; }

    /// <summary>Frames used, including the optional challenge frame.</summary>
    public int FrameCount { get; init; }

    /// <summary>Envelope bytes, excluding frame headers.</summary>
    public int Bytes { get; init; }

    /// <summary>
    /// What the wait for the peer's close saw, or null when the caller did not ask for confirmation.
    /// </summary>
    /// <remarks>
    /// A sender that asked for confirmation and did not get it is a failed round whose <see cref="Reason"/> is
    /// <see cref="SourceSyncDeliveryReasons.DeliveryUnconfirmed"/>; the frames did leave, so <see cref="Bytes"/> and
    /// <see cref="FrameCount"/> still describe them. Null means nobody asked, and a success then only says the frames
    /// left this device.
    /// </remarks>
    public bool? DeliveryConfirmed { get; init; }

    /// <summary>How long the wait for the peer's close took; zero when no confirmation was requested.</summary>
    public TimeSpan DeliveryElapsed { get; init; }

    /// <summary>
    /// Whether the peer's receipt byte arrived, or null when this send did not wait for one (W6-37).
    /// </summary>
    /// <remarks>
    /// A receipt is the only evidence that the peer understood the envelope: a close happens just as well when the peer
    /// refuses the bytes. False therefore means the frames left this device and nothing came back.
    /// </remarks>
    public bool? ReceiptConfirmed { get; init; }

    /// <summary>
    /// Whether this device wrote the W6-37 receipt byte, or null when the round did not ask for one (W6-39).
    /// </summary>
    public bool? ReceiptWritten { get; init; }

    /// <summary>
    /// The receipt code when the frames left and the receipt did not arrive (W6-39), else null.
    /// </summary>
    /// <remarks>
    /// A receipt that fails never turns a delivered envelope into a failed round: the envelope did arrive and it did
    /// decode, so the round is a success and the missing acknowledgement is carried here. This is the difference
    /// between "the peer did not confirm" and "the round failed", and collapsing the two would make a working transfer
    /// look broken.
    /// </remarks>
    public string? ReceiptReason { get; init; }

    /// <summary>
    /// True when the round succeeded and wrote nothing because this device already matched the envelope.
    /// </summary>
    /// <remarks>
    /// This is the round-trip property: sending the same snapshot a second time must plan every source as up to
    /// date, so the gate applies nothing and the baseline does not move. Zero writes is the claim worth asserting,
    /// therefore it is exposed here rather than left to the caller to re-derive.
    /// </remarks>
    public bool IsNoChange => Succeeded && Report is { Applied: 0 };

    /// <summary>Entries really written on the receiving device.</summary>
    public int Applied => Report?.Applied ?? 0;

    /// <summary>Entries the gate skipped (no change, stale, unsupported).</summary>
    public int Skipped => Report?.Skipped ?? 0;

    /// <summary>Every planned row, whether or not it was written.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> Items => Preview?.Items ?? [];

    /// <summary>The planned rows that need a user decision; empty when the envelope is fully automatic.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> NeedsApproval =>
        Items.Where(item => item.RequiresApproval).ToList();

    /// <summary>The planned row for one sync source id, or null when the envelope did not mention it.</summary>
    public SourceSyncApprovalItem? FindItem(string? syncSourceId)
    {
        string key = syncSourceId?.Trim() ?? "";
        if (key.Length == 0)
        {
            return null;
        }

        return Items.FirstOrDefault(item => string.Equals(item.SyncSourceId, key, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>A refused round: nothing was sent, or nothing was accepted.</summary>
    public static SourceSyncPeerRoundResult Refused(string reason, string? detail = null) => new()
    {
        Reason = reason,
        Detail = detail,
    };

    /// <summary>A payload that left this device.</summary>
    public static SourceSyncPeerRoundResult Sent(SourceSyncTransferResult transfer)
    {
        ArgumentNullException.ThrowIfNull(transfer);
        return new()
        {
            Succeeded = transfer.Succeeded,
            Reason = transfer.Reason,
            Detail = transfer.Detail,
            FrameCount = transfer.FrameCount,
            Bytes = transfer.Bytes,
            DeliveryConfirmed = transfer.DeliveryConfirmed,
            DeliveryElapsed = transfer.DeliveryElapsed,
            ReceiptConfirmed = transfer.ReceiptConfirmed,
        };
    }

    /// <summary>A payload that arrived at this device, mapped from the inbox result.</summary>
    public static SourceSyncPeerRoundResult Received(SourceSyncInboxResult result)
    {
        ArgumentNullException.ThrowIfNull(result);
        return new()
        {
            Succeeded = result.Succeeded,
            Reason = result.Reason,
            Detail = result.Detail,
            SenderDeviceId = result.Envelope?.DeviceId ?? "",
            Envelope = result.Envelope,
            Preview = result.Preview,
            Report = result.Report,
            FrameCount = result.FrameCount,
            Bytes = result.Bytes,
            ReceiptWritten = result.ReceiptWritten,
        };
    }
}

/// <summary>
/// One device in a peer round: its identity, its source list, its baseline, and the import round it runs on
/// whatever arrives.
/// </summary>
/// <remarks>
/// <para>
/// W6-16 delivered the pieces — export, loopback transport, inbox, import round — but each of them on its own.
/// Wiring them by hand is where the mistakes live: exporting from the wrong store, importing into the sender's
/// own list, forgetting to hand the receiver's baseline store to its round (so the baseline never advances and
/// the second transfer writes again). This type owns that wiring once, for the App and for the tests, and
/// <see cref="ExchangeAsync"/> is the whole round in one call: export on the sender, listen on the receiver, send,
/// receive, preview, gate, write, advance the baseline.
/// </para>
/// <para>
/// Two rules are structural here, not conventions. The transport always binds and dials
/// <see cref="SourceSyncTransferAddress.DefaultAddress"/>: a non-loopback address is refused before a socket
/// exists, so this layer can never make Windows ask the user to open the firewall. And importing is delegated to
/// <see cref="SourceSyncImportRound"/>, so nothing reaches the store without passing the planner and the approval
/// gate — a tombstone in particular stays a proposal, because the local copy may be the last one.
/// </para>
/// </remarks>
public sealed class SourceSyncPeerRound
{
    private readonly SourceSyncLocalBridge _outbound;
    private readonly SourceSyncInbox _inbox;

    /// <summary>Builds one device's side of a peer round.</summary>
    /// <param name="deviceId">Stable id of this device; it is written into every envelope it exports.</param>
    /// <param name="localSources">This device's source list.</param>
    /// <param name="baselines">This device's baseline store, or null to import without advancing one.</param>
    /// <param name="baselineError">Reports why the baseline store is unusable, when it is.</param>
    public SourceSyncPeerRound(
        string deviceId,
        ILocalSourceStore localSources,
        ISourceSyncBaselineStore? baselines = null,
        Func<string?>? baselineError = null)
    {
        _outbound = new SourceSyncLocalBridge(localSources, deviceId);
        _inbox = new SourceSyncInbox(localSources, baselines, baselineError);
        DeviceId = _outbound.DeviceId;
        LocalSources = localSources;
        Baselines = baselines;
    }

    /// <summary>Stable id of this device as it appears in envelopes.</summary>
    public string DeviceId { get; }

    /// <summary>This device's source list.</summary>
    public ILocalSourceStore LocalSources { get; }

    /// <summary>This device's baseline store, or null when the round keeps no baseline.</summary>
    public ISourceSyncBaselineStore? Baselines { get; }

    /// <summary>The export half: identity assignment and envelope building.</summary>
    public SourceSyncLocalBridge Outbound => _outbound;

    /// <summary>The import half: decode, plan, gate, write, advance the baseline.</summary>
    public SourceSyncInbox Inbox => _inbox;

    /// <summary>The import round, for callers that want to preview without transferring anything.</summary>
    public SourceSyncImportRound Round => _inbox.Round;

    /// <summary>Builds this device's outbound envelope.</summary>
    /// <param name="deletions">Tombstones to carry; never applied by the peer without a user decision.</param>
    /// <param name="includeSecrets">Whether the envelope advertises that it could carry credentials.</param>
    public SourceSyncEnvelope Export(IReadOnlyList<Tombstone>? deletions = null, bool includeSecrets = false)
        => _outbound.BuildEnvelope(includeSecrets, deletions);

    /// <summary>Plans an inbound envelope against this device without writing anything.</summary>
    public SourceSyncApplyPreview Preview(SourceSyncEnvelope fromPeer)
    {
        ArgumentNullException.ThrowIfNull(fromPeer);
        return _inbox.Round.Preview(fromPeer);
    }

    /// <summary>Applies a preview through the gate; only <paramref name="allowedIds"/> can turn a proposal into a write.</summary>
    /// <param name="skippedIds">Identities the caller explicitly skipped; a skip outranks an allow for the same id.</param>
    public Task<SourceSyncApplyReport> ApplyAsync(
        SourceSyncApplyPreview preview,
        SourceSyncRecordWriter writer,
        IReadOnlyList<string>? allowedIds = null,
        IReadOnlyList<string>? skippedIds = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(preview);
        ArgumentNullException.ThrowIfNull(writer);
        return _inbox.Round.ApplyAsync(preview, allowedIds, writer, skippedIds, cancellationToken);
    }

    /// <summary>Serializes an envelope and sends it to a loopback peer.</summary>
    /// <remarks>
    /// A failure is returned, never thrown: an envelope this build cannot serialize is reported as
    /// <see cref="SourceSyncTransferReasons.EnvelopeInvalid"/> with the contract reason in the detail, and a
    /// forbidden address as <see cref="SourceSyncTransferReasons.NotLoopback"/> before any socket is opened.
    /// </remarks>
    /// <param name="envelope">The envelope to send.</param>
    /// <param name="address">Loopback address; anything else is refused before a socket exists.</param>
    /// <param name="port">The listener's port.</param>
    /// <param name="challenge">The nonce both sides quote, or null for an unauthenticated transfer.</param>
    /// <param name="timeout">How long the peer gets, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels the transfer.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="tls">The optional TLS pin; null or off keeps the W6-16 plaintext path.</param>
    /// <param name="delivery">The optional delivery confirmation; null keeps the fire-and-forget send.</param>
    /// <param name="receipt">The optional receipt wait (W6-37); null or off adds no byte and reads nothing back.</param>
    public async Task<SourceSyncPeerRoundResult> SendAsync(
        SourceSyncEnvelope envelope,
        string? address,
        int port,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        SourceSyncDeliveryOptions? delivery = null,
        SourceSyncReceiptOptions? receipt = null)
    {
        ArgumentNullException.ThrowIfNull(envelope);

        byte[] payload;
        try
        {
            payload = Serialize(envelope);
        }
        catch (SyncContractException invalid)
        {
            return SourceSyncPeerRoundResult
                .Refused(SourceSyncTransferReasons.EnvelopeInvalid, invalid.Reason) with
            {
                SenderDeviceId = DeviceId,
            };
        }

        return await SendPayloadAsync(
                payload,
                address,
                port,
                challenge,
                timeout,
                cancellationToken,
                options,
                tls,
                delivery,
                receipt)
            .ConfigureAwait(false);
    }

    /// <summary>Sends raw payload bytes to a loopback peer, bypassing export.</summary>
    /// <remarks>
    /// Exists so a caller can hand over bytes that are not an envelope this build produced — the tampered-payload
    /// case — and still go through the same address gate, framing and receiving path as a real transfer.
    /// </remarks>
    /// <param name="payload">The exact bytes to put on the wire.</param>
    /// <param name="address">Loopback address; anything else is refused before a socket exists.</param>
    /// <param name="port">The listener's port.</param>
    /// <param name="challenge">The nonce both sides quote, or null for an unauthenticated transfer.</param>
    /// <param name="timeout">How long the peer gets, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels the transfer.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="tls">The optional TLS pin; null or off keeps the W6-16 plaintext path.</param>
    /// <param name="delivery">The optional delivery confirmation; null keeps the fire-and-forget send.</param>
    /// <param name="receipt">The optional receipt wait (W6-37); null or off adds no byte and reads nothing back.</param>
    public async Task<SourceSyncPeerRoundResult> SendPayloadAsync(
        ReadOnlyMemory<byte> payload,
        string? address,
        int port,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        SourceSyncDeliveryOptions? delivery = null,
        SourceSyncReceiptOptions? receipt = null)
    {
        SourceSyncTransferResult sent = await SourceSyncTransferClient
            .SendAsync(address, port, payload, challenge, timeout, cancellationToken, options, tls, delivery, receipt)
            .ConfigureAwait(false);

        SourceSyncPeerRoundResult result = sent.Succeeded
            ? SourceSyncPeerRoundResult.Sent(sent)
            : SourceSyncPeerRoundResult.Refused(
                sent.Reason ?? SourceSyncTransferReasons.IoFailed,
                sent.Detail);

        return result with { SenderDeviceId = DeviceId };
    }

    /// <summary>Binds a loopback listener and imports the one envelope that arrives.</summary>
    /// <param name="writer">How an approved entry becomes a real source on this device.</param>
    /// <param name="address">Loopback address; anything else is refused before a socket exists.</param>
    /// <param name="port">0 to let the OS pick a free port.</param>
    /// <param name="expectedChallenge">The pairing challenge this transfer must quote, or null to accept any.</param>
    /// <param name="allowedIds">Entries the user explicitly allowed; without this, proposals stay proposals.</param>
    /// <param name="timeout">How long the peer gets, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="tls">True to accept a TLS 1.3 peer on this listener instead of plaintext frames.</param>
    /// <param name="receipt">The optional receipt write (W6-37); null or off sends no extra byte to the peer.</param>
    public async Task<SourceSyncPeerRoundResult> ReceiveOnceAsync(
        SourceSyncRecordWriter writer,
        string? address = null,
        int port = 0,
        string? expectedChallenge = null,
        IReadOnlyList<string>? allowedIds = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        SourceSyncReceiptOptions? receipt = null)
    {
        ArgumentNullException.ThrowIfNull(writer);

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(address, port, tls is { Enabled: true });
        if (!start.Started || start.Listener is null)
        {
            return SourceSyncPeerRoundResult
                .Refused(start.Reason ?? SourceSyncTransferReasons.ListenerFailed, start.Detail) with
            {
                ReceiverDeviceId = DeviceId,
            };
        }

        await using SourceSyncTransferListener listener = start.Listener;
        return await ReceiveOnAsync(
                listener,
                writer,
                expectedChallenge,
                allowedIds,
                timeout,
                cancellationToken,
                options,
                receipt)
            .ConfigureAwait(false);
    }

    /// <summary>Imports the one envelope that arrives on an already-bound loopback listener.</summary>
    /// <remarks>
    /// Split from <see cref="ReceiveOnceAsync"/> so two rounds can meet on a port that is already known: the
    /// listener must be bound before the sender learns where to dial, otherwise the dial races the bind.
    /// </remarks>
    /// <param name="listener">An already-bound listener.</param>
    /// <param name="writer">How an approved entry becomes a real source on this device.</param>
    /// <param name="expectedChallenge">The pairing challenge this transfer must quote, or null to accept any.</param>
    /// <param name="allowedIds">Entries the user explicitly allowed; without this, proposals stay proposals.</param>
    /// <param name="timeout">How long the peer gets, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="receipt">The optional receipt write (W6-37), gated on the inbox's decode check; null or off sends
    /// no extra byte to the peer.</param>
    public async Task<SourceSyncPeerRoundResult> ReceiveOnAsync(
        SourceSyncTransferListener listener,
        SourceSyncRecordWriter writer,
        string? expectedChallenge = null,
        IReadOnlyList<string>? allowedIds = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncReceiptOptions? receipt = null)
    {
        ArgumentNullException.ThrowIfNull(listener);
        ArgumentNullException.ThrowIfNull(writer);

        SourceSyncInboxResult imported = await listener
            .ReceiveAndImportAsync(_inbox, writer, expectedChallenge, allowedIds, timeout, cancellationToken, options, receipt)
            .ConfigureAwait(false);

        return SourceSyncPeerRoundResult.Received(imported) with { ReceiverDeviceId = DeviceId };
    }

    /// <summary>
    /// One full round between two devices over loopback: export here, send, receive there, import there.
    /// </summary>
    /// <remarks>
    /// The listener is bound before the sender dials, so the port is real and there is no fixed-port assumption.
    /// The receiver outlives the sender's budget: a refused send cancels the waiting receiver instead of leaving
    /// it to time out, and the send's failure is what the caller sees.
    /// </remarks>
    /// <param name="sender">The device that exports.</param>
    /// <param name="receiver">The device that imports.</param>
    /// <param name="receiverWriter">How an approved entry becomes a real source on the receiver.</param>
    /// <param name="deletions">Tombstones to carry in the exported envelope.</param>
    /// <param name="allowedIds">Entries the receiving user explicitly allowed.</param>
    /// <param name="challenge">Pairing challenge both sides quote, or null.</param>
    /// <param name="timeout">Per-side budget, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels both sides.</param>
    /// <param name="options">The optional authentication settings, applied to both ends; null keeps the W6-16
    /// byte stream exactly. When authentication is on, <paramref name="challenge"/> is the nonce and both sides
    /// must be given the same value.</param>
    /// <param name="tls">The optional TLS pin, applied to both ends; null or off keeps the W6-16 plaintext path.</param>
    /// <param name="receiptBudget">How long the sender waits for the receiver's receipt byte (W6-39); null means the
    /// round exchanges no receipt at all and behaves exactly as it did before the receipt existed.</param>
    public static async Task<SourceSyncPeerRoundResult> ExchangeAsync(
        SourceSyncPeerRound sender,
        SourceSyncPeerRound receiver,
        SourceSyncRecordWriter receiverWriter,
        IReadOnlyList<Tombstone>? deletions = null,
        IReadOnlyList<string>? allowedIds = null,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        TimeSpan? receiptBudget = null)
    {
        ArgumentNullException.ThrowIfNull(sender);

        SourceSyncEnvelope envelope = sender.Export(deletions);
        return await ExchangePayloadAsync(
                sender,
                receiver,
                receiverWriter,
                Serialize(envelope),
                allowedIds,
                challenge,
                timeout,
                cancellationToken,
                options,
                tls,
                receiptBudget)
            .ConfigureAwait(false);
    }

    /// <summary>One full round with the bytes the caller supplies; used to prove a tampered envelope is refused.</summary>
    /// <param name="sender">The device that sends.</param>
    /// <param name="receiver">The device that receives and imports.</param>
    /// <param name="receiverWriter">How an approved entry becomes a real source on the receiver.</param>
    /// <param name="payload">The exact bytes to put on the wire.</param>
    /// <param name="allowedIds">Entries the receiving user explicitly allowed.</param>
    /// <param name="challenge">Pairing challenge both sides quote, or null.</param>
    /// <param name="timeout">Per-side budget, defaulting to 15 seconds.</param>
    /// <param name="cancellationToken">Cancels both sides.</param>
    /// <param name="options">The optional authentication settings, applied to both ends; null keeps the W6-16
    /// byte stream exactly. When authentication is on, <paramref name="challenge"/> is the nonce and both sides
    /// must be given the same value.</param>
    /// <param name="tls">The optional TLS pin, applied to both ends; null or off keeps the W6-16 plaintext path.</param>
    /// <param name="receiptBudget">How long the sender waits for the receiver's receipt byte (W6-39), or null to
    /// exchange none. When given, the receiver writes one 0x04 byte after the envelope decoded and the sender waits
    /// up to this long for it. A receipt that never arrives is recorded in
    /// <see cref="SourceSyncPeerRoundResult.ReceiptReason"/> and does not fail the round.</param>
    public static async Task<SourceSyncPeerRoundResult> ExchangePayloadAsync(
        SourceSyncPeerRound sender,
        SourceSyncPeerRound receiver,
        SourceSyncRecordWriter receiverWriter,
        ReadOnlyMemory<byte> payload,
        IReadOnlyList<string>? allowedIds = null,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        TimeSpan? receiptBudget = null)
    {
        ArgumentNullException.ThrowIfNull(sender);
        ArgumentNullException.ThrowIfNull(receiver);
        ArgumentNullException.ThrowIfNull(receiverWriter);

        // Both halves are this process, so one switch decides both sides: the listener is told to write the byte and
        // the sender is told to wait for it. Null keeps the round byte-for-byte what it was before W6-39.
        SourceSyncReceiptOptions? receiverReceipt = receiptBudget is null
            ? null
            : SourceSyncReceiptOptions.Sending();
        SourceSyncReceiptOptions senderReceipt = receiptBudget is null
            ? SourceSyncReceiptOptions.Off
            : SourceSyncReceiptOptions.Awaiting(receiptBudget);

        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(tls: tls is { Enabled: true });
        if (!start.Started || start.Listener is null)
        {
            return SourceSyncPeerRoundResult
                .Refused(start.Reason ?? SourceSyncTransferReasons.ListenerFailed, start.Detail) with
            {
                SenderDeviceId = sender.DeviceId,
                ReceiverDeviceId = receiver.DeviceId,
            };
        }

        await using SourceSyncTransferListener listener = start.Listener;

        TimeSpan budget = timeout is { } given && given > TimeSpan.Zero ? given : SourceSyncTransferListener.DefaultTimeout;
        using CancellationTokenSource abort = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        // The receiver waits for a peer that may never dial; when the send is refused it must be released at once
        // rather than sit out its whole budget, so the exchange settles in milliseconds instead of seconds.
        abort.CancelAfter(budget + budget);

        Task<SourceSyncPeerRoundResult> receiving = receiver.ReceiveOnAsync(
            listener,
            receiverWriter,
            challenge,
            allowedIds,
            timeout,
            abort.Token,
            options,
            receiverReceipt);

        SourceSyncPeerRoundResult sent = await sender
            .SendPayloadAsync(
                payload,
                SourceSyncTransferAddress.DefaultAddress,
                listener.Port,
                challenge,
                timeout,
                cancellationToken,
                options,
                tls,
                delivery: null,
                receipt: senderReceipt)
            .ConfigureAwait(false);

        bool receiptAdvisory = receiptBudget is not null && SourceSyncReceiptReasons.IsReceiptReason(sent.Reason);
        if (!sent.Succeeded && !receiptAdvisory)
        {
            await abort.CancelAsync().ConfigureAwait(false);
            try
            {
                await receiving.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // The receiver was released rather than finished; the send's reason is the one to report.
            }

            return sent with { ReceiverDeviceId = receiver.DeviceId };
        }

        SourceSyncPeerRoundResult received = await receiving.ConfigureAwait(false);
        return received with
        {
            SenderDeviceId = sender.DeviceId,
            FrameCount = sent.FrameCount,
            Bytes = sent.Bytes,

            // The receipt is evidence about the receiver, so it is reported on the round that produced it - and only
            // as a reason when it was asked for and did not arrive.
            ReceiptConfirmed = receiptBudget is null ? null : sent.ReceiptConfirmed,
            ReceiptReason = receiptAdvisory ? sent.Reason : received.ReceiptReason,
        };
    }

    private static byte[] Serialize(SourceSyncEnvelope envelope)
        => Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));
}

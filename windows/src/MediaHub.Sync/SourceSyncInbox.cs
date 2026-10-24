using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>What one inbound envelope did: transport, decode, then the import round.</summary>
public sealed record SourceSyncInboxResult
{
    /// <summary>True only when the payload arrived, decoded, and the import round ran.</summary>
    public bool Succeeded { get; init; }

    /// <summary>One of <see cref="SourceSyncTransferReasons"/> when <see cref="Succeeded"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Context for a failure, or the contract reason when the envelope itself was rejected.</summary>
    public string? Detail { get; init; }

    /// <summary>The envelope that arrived and decoded.</summary>
    public SourceSyncEnvelope? Envelope { get; init; }

    /// <summary>The read-only preview the import round built.</summary>
    public SourceSyncApplyPreview? Preview { get; init; }

    /// <summary>The import report, including baseline advancement.</summary>
    public SourceSyncApplyReport? Report { get; init; }

    /// <summary>Frames the transfer used, including the challenge frame.</summary>
    public int FrameCount { get; init; }

    /// <summary>Envelope bytes.</summary>
    public int Bytes { get; init; }

    /// <summary>
    /// True when the round wrote nothing because this device already matched the envelope.
    /// </summary>
    /// <remarks>
    /// This is the second-transfer case: the same snapshot arrives again, the planner finds every source
    /// up to date, the gate applies nothing, and the baseline does not move. Zero writes is the property worth
    /// asserting, so it is exposed rather than left to the caller to re-derive from the report.
    /// </remarks>
    public bool IsNoChange => Succeeded && Report is { Applied: 0 };

    /// <summary>A completed round.</summary>
    public static SourceSyncInboxResult Ok(
        SourceSyncEnvelope envelope,
        SourceSyncApplyPreview preview,
        SourceSyncApplyReport report,
        int frameCount,
        int bytes) => new()
    {
        Succeeded = true,
        Envelope = envelope,
        Preview = preview,
        Report = report,
        FrameCount = frameCount,
        Bytes = bytes,
    };

    /// <summary>A transfer or decode failure; nothing was imported.</summary>
    public static SourceSyncInboxResult Failed(string reason, string? detail = null) => new()
    {
        Reason = reason,
        Detail = detail,
    };
}

/// <summary>
/// The receiving device of a transfer: decode the payload, then run it through <see cref="SourceSyncImportRound"/>.
/// </summary>
/// <remarks>
/// <para>
/// This exists so that "received" can never quietly mean "imported". Arriving bytes are decoded first, and only
/// a decodable envelope reaches the planner, the approval gate, the writer and the baseline. A payload that is
/// not an envelope is reported as <c>envelope-invalid</c> with the contract reason in the detail, and nothing is
/// written.
/// </para>
/// <para>
/// It holds no state of its own: the same instance can receive the same snapshot twice, and the second time the
/// planner reports "already up to date" because the first run both wrote the sources and advanced the baseline.
/// A fresh instance over the same directory behaves the same way, which is what makes the idempotency a property
/// of the files rather than of the process.
/// </para>
/// </remarks>
public sealed class SourceSyncInbox
{
    /// <summary>Strict decoding: a byte sequence that is not UTF-8 is rejected, not repaired.</summary>
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    /// <summary>Builds an inbox over the given stores.</summary>
    /// <param name="localSources">The store the approval writer writes into.</param>
    /// <param name="baselines">The baseline store the round advances, or null to import without a baseline.</param>
    /// <param name="baselineError">Reports why the baseline is unusable, when it is.</param>
    public SourceSyncInbox(
        ILocalSourceStore localSources,
        ISourceSyncBaselineStore? baselines = null,
        Func<string?>? baselineError = null)
    {
        Round = new SourceSyncImportRound(localSources, baselines, baselineError);
    }

    /// <summary>The import round this inbox delegates to.</summary>
    public SourceSyncImportRound Round { get; }

    /// <summary>Reads one envelope from a stream, optionally checks the peer's tag, and imports it.</summary>
    /// <param name="stream">An open stream; the caller owns its lifetime.</param>
    /// <param name="expectedChallenge">The invitation's challenge, or null to accept whatever the peer announces.</param>
    /// <param name="writer">Where the gated records are written once the transfer is accepted.</param>
    /// <param name="allowedIds">Restricts which sources may be written, or null for the gate's own decision.</param>
    /// <param name="cancellationToken">Cancels between frames.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    public async Task<SourceSyncInboxResult> ReceiveAsync(
        Stream stream,
        string? expectedChallenge,
        SourceSyncRecordWriter writer,
        IReadOnlyList<string>? allowedIds = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(writer);

        SourceSyncTransferResult transfer =
            await SourceSyncTransfer.ReceiveAsync(stream, expectedChallenge, cancellationToken, options).ConfigureAwait(false);
        if (!transfer.Succeeded)
        {
            return SourceSyncInboxResult.Failed(transfer.Reason ?? SourceSyncTransferReasons.FrameInvalid, transfer.Detail);
        }

        SourceSyncInboxResult imported = await ImportAsync(transfer.Envelope, writer, allowedIds, cancellationToken)
            .ConfigureAwait(false);
        return imported with { FrameCount = transfer.FrameCount, Bytes = transfer.Bytes };
    }

    /// <summary>Decodes a payload, then previews, gates, writes and advances the baseline.</summary>
    public async Task<SourceSyncInboxResult> ImportAsync(
        byte[] payload,
        SourceSyncRecordWriter writer,
        IReadOnlyList<string>? allowedIds = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentNullException.ThrowIfNull(writer);

        if (!TryDecode(payload, out SourceSyncEnvelope? envelope, out string? error) || envelope is null)
        {
            return SourceSyncInboxResult.Failed(
                SourceSyncTransferReasons.EnvelopeInvalid,
                error ?? "malformed-payload");
        }

        try
        {
            SourceSyncApplyPreview preview = Round.Preview(envelope);
            SourceSyncApplyReport report = await Round
                .ApplyAsync(preview, allowedIds, writer, cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            return SourceSyncInboxResult.Ok(envelope, preview, report, 0, payload.Length);
        }
        catch (Exception failure) when (failure is IOException or UnauthorizedAccessException
                                            or InvalidOperationException or ArgumentException)
        {
            // The writer or the baseline store failed on disk. Half an import is not reported as success.
            return SourceSyncInboxResult.Failed(SourceSyncTransferReasons.IoFailed, failure.Message);
        }
    }

    /// <summary>Dials a loopback host, reads one envelope from it, and imports it.</summary>
    /// <remarks>
    /// The guest half of the transfer: the pairing invitation gives the guest the host's ephemeral address, so the
    /// guest is the one that dials. The refusal for a non-loopback address happens inside the client, before any
    /// socket is opened.
    /// </remarks>
    public async Task<SourceSyncInboxResult> DialAndImportAsync(
        string? address,
        int port,
        SourceSyncRecordWriter writer,
        string? expectedChallenge = null,
        IReadOnlyList<string>? allowedIds = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null)
    {
        ArgumentNullException.ThrowIfNull(writer);

        SourceSyncTransferResult transfer = await SourceSyncTransferClient
            .ReceiveAsync(address, port, expectedChallenge, timeout, cancellationToken, options, tls)
            .ConfigureAwait(false);
        if (!transfer.Succeeded)
        {
            return SourceSyncInboxResult.Failed(transfer.Reason ?? SourceSyncTransferReasons.FrameInvalid, transfer.Detail);
        }

        SourceSyncInboxResult imported = await ImportAsync(transfer.Envelope, writer, allowedIds, cancellationToken)
            .ConfigureAwait(false);
        return imported with { FrameCount = transfer.FrameCount, Bytes = transfer.Bytes };
    }

    /// <summary>Decodes an envelope payload without importing it.</summary>
    public static bool TryDecode(byte[]? payload, out SourceSyncEnvelope? envelope, out string? error)
    {
        envelope = null;
        error = null;

        if (payload is null || payload.Length == 0)
        {
            error = "empty-payload";
            return false;
        }

        string json;
        try
        {
            json = StrictUtf8.GetString(payload);
        }
        catch (DecoderFallbackException)
        {
            error = "not-utf8";
            return false;
        }

        if (SourceSyncCodec.TryDeserialize(json, out SourceSyncEnvelope? decoded, out string? contractError)
            && decoded is not null)
        {
            envelope = decoded;
            return true;
        }

        error = contractError ?? "malformed-payload";
        return false;
    }
}

/// <summary>One-call path from a loopback listener to a finished import.</summary>
public static class SourceSyncInboxExtensions
{
    /// <summary>Accepts one peer, reads one envelope from it, imports it, and closes the connection.</summary>
    /// <param name="listener">The listening half; it accepts exactly one peer.</param>
    /// <param name="inbox">The inbox that previews, gates and writes the envelope.</param>
    /// <param name="writer">Where the gated records are written.</param>
    /// <param name="expectedChallenge">The invitation's challenge, or null to accept whatever the peer announces.</param>
    /// <param name="allowedIds">Restricts which sources may be written, or null for the gate's own decision.</param>
    /// <param name="timeout">How long the peer gets.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    public static async Task<SourceSyncInboxResult> ReceiveAndImportAsync(
        this SourceSyncTransferListener listener,
        SourceSyncInbox inbox,
        SourceSyncRecordWriter writer,
        string? expectedChallenge = null,
        IReadOnlyList<string>? allowedIds = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(listener);
        ArgumentNullException.ThrowIfNull(inbox);
        ArgumentNullException.ThrowIfNull(writer);

        SourceSyncTransferResult transfer =
            await listener.ReceiveOnceAsync(expectedChallenge, timeout, cancellationToken, options).ConfigureAwait(false);
        if (!transfer.Succeeded)
        {
            return SourceSyncInboxResult.Failed(transfer.Reason ?? SourceSyncTransferReasons.FrameInvalid, transfer.Detail);
        }

        SourceSyncInboxResult imported = await inbox
            .ImportAsync(transfer.Envelope, writer, allowedIds, cancellationToken)
            .ConfigureAwait(false);

        return imported with { FrameCount = transfer.FrameCount, Bytes = transfer.Bytes };
    }
}

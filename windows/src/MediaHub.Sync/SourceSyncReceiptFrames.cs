using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>Stable reasons for a send that asked the receiver for a receipt frame and did not get one.</summary>
/// <remarks>
/// <para>
/// This is its own closed set, next to <see cref="SourceSyncTransferReasons"/> (ten codes),
/// <see cref="SourceSyncTransferAuthReasons"/> (five codes), <see cref="SourceSyncTlsReasons"/> (four codes) and
/// <see cref="SourceSyncDeliveryReasons"/> (one code). None of those sets changes: "the peer answered with the wrong
/// byte" is not a framing failure of the envelope, not an authentication failure and not a TLS failure, and folding it
/// into one of them would make the acceptance logs say the wrong thing.
/// </para>
/// <para>
/// The receipt exists because a close is not evidence of receipt. W6-28 case F and W6-35 case R6a measured a plaintext
/// sender reporting success, with a confirmed close, against a TLS listener that rejected the very first record and
/// never read a single envelope byte: a peer that refuses and closes and a peer that accepts and closes look identical
/// on TCP. Only a byte the receiver chooses to send back after it decoded the envelope distinguishes them.
/// </para>
/// </remarks>
public static class SourceSyncReceiptReasons
{
    /// <summary>The connection ended before a receipt byte arrived; the peer did not confirm receipt.</summary>
    public const string ReceiptMissing = "receipt-missing";

    /// <summary>The peer kept the connection open past the budget without sending a receipt byte.</summary>
    public const string ReceiptTimeout = "receipt-timeout";

    /// <summary>The peer answered with a byte that is not the receipt kind, so it did not acknowledge this envelope.</summary>
    public const string ReceiptInvalid = "receipt-invalid";

    /// <summary>This side cannot carry a receipt at all: the stream cannot be written, or cannot be read back.</summary>
    /// <remarks>
    /// Named separately from the three peer-side outcomes on purpose. "The peer stayed silent" and "this stream never
    /// had a receipt channel" are different facts, and an operator chasing a failure needs to know which one it is
    /// before touching the other machine.
    /// </remarks>
    public const string ReceiptNotEnabled = "receipt-not-enabled";

    /// <summary>Every receipt reason, in a fixed order.</summary>
    public static IReadOnlyList<string> All { get; } =
        new[] { ReceiptMissing, ReceiptTimeout, ReceiptInvalid, ReceiptNotEnabled };
}

/// <summary>What writing or waiting for a receipt did.</summary>
public sealed record SourceSyncReceiptResult
{
    /// <summary>True when the receipt byte was written, or when the peer's receipt byte arrived and was valid.</summary>
    public bool Confirmed { get; init; }

    /// <summary>One of <see cref="SourceSyncReceiptReasons"/> when <see cref="Confirmed"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Human-readable context; never carries an envelope body, a secret or a certificate key.</summary>
    public string? Detail { get; init; }

    /// <summary>Bytes this side read or wrote for the receipt; 1 on success, 0 when nothing happened.</summary>
    public int BytesRead { get; init; }

    /// <summary>How long the wait took, so a caller can log "receipt after N ms".</summary>
    public TimeSpan Elapsed { get; init; }

    /// <summary>The receipt byte arrived (or left): the envelope is acknowledged.</summary>
    /// <param name="elapsed">How long the wait took.</param>
    public static SourceSyncReceiptResult Ok(TimeSpan elapsed) => new()
    {
        Confirmed = true,
        BytesRead = SourceSyncReceipt.ReceiptFrameBytes,
        Elapsed = elapsed,
    };

    /// <summary>The receipt did not happen, with the reason that names which side failed.</summary>
    /// <param name="reason">One of <see cref="SourceSyncReceiptReasons"/>.</param>
    /// <param name="detail">Human-readable context.</param>
    /// <param name="elapsed">How long the attempt took.</param>
    public static SourceSyncReceiptResult Failed(string reason, string? detail, TimeSpan elapsed = default) => new()
    {
        Reason = reason,
        Detail = detail,
        Elapsed = elapsed,
    };
}

/// <summary>Whether this end writes a receipt, or waits for one, and how long that wait gets.</summary>
/// <remarks>
/// Off by default, and off is byte-for-byte today's protocol: no extra byte on the wire, no extra read, no extra wait.
/// The receiver only writes the receipt after it decoded the envelope, so a receiver that refuses the payload stays
/// silent and the sender's success can no longer be mistaken for delivery.
/// </remarks>
public sealed class SourceSyncReceiptOptions
{
    /// <summary>How long a sender waits for the peer's receipt byte: 2000 ms.</summary>
    /// <remarks>
    /// A loopback receiver writes the byte within milliseconds of decoding. Two seconds covers a loaded machine and
    /// matches the delivery-confirmation budget, so an operator sees one number for "how long does a send wait".
    /// </remarks>
    public const int DefaultBudgetMilliseconds = 2000;

    /// <summary>True when this end takes part in receipts: the receiver writes one, the sender waits for one.</summary>
    public bool Enabled { get; init; }

    /// <summary>How long the sender's wait gets; only read when <see cref="Enabled"/> is true.</summary>
    public TimeSpan Budget { get; init; } = TimeSpan.FromMilliseconds(DefaultBudgetMilliseconds);

    /// <summary>The default: no receipt byte is written and none is waited for.</summary>
    public static SourceSyncReceiptOptions Off { get; } = new();

    /// <summary>The receiving side: write the receipt byte once the envelope decoded.</summary>
    public static SourceSyncReceiptOptions Sending() => new() { Enabled = true };

    /// <summary>The sending side: wait this long for the peer's receipt byte.</summary>
    /// <param name="budget">How long to wait; null or non-positive means <see cref="DefaultBudgetMilliseconds"/>.</param>
    public static SourceSyncReceiptOptions Awaiting(TimeSpan? budget = null) => new()
    {
        Enabled = true,
        Budget = budget is null || budget.Value <= TimeSpan.Zero
            ? TimeSpan.FromMilliseconds(DefaultBudgetMilliseconds)
            : budget.Value,
    };

    /// <inheritdoc />
    public override string ToString() => "SourceSyncReceiptOptions { Enabled = "
        + (Enabled ? "true" : "false")
        + ", BudgetMs = " + ((int)Budget.TotalMilliseconds).ToString(CultureInfo.InvariantCulture)
        + " }";
}

/// <summary>The one place that reads and writes the receipt byte (kind 0x04).</summary>
/// <remarks>
/// <para>
/// The receipt is a bare kind byte, not a <see cref="PairingFrame"/>: W6-37 fixes it at one byte so the other end can
/// wait for "one byte that is 0x04" without a length prefix of its own, and so a peer that sends anything else is
/// reported as <see cref="SourceSyncReceiptReasons.ReceiptInvalid"/> instead of as a frame-length accident. It travels
/// after the last envelope frame (after the tag frame when authentication is on) and only the receiver ever writes it.
/// </para>
/// <para>
/// Both directions live here so the wire format has exactly one implementation. Nothing else in this assembly may
/// write a 0x04 byte on a transfer stream.
/// </para>
/// </remarks>
public static class SourceSyncReceipt
{
    /// <summary>The frame kind that means "I decoded your envelope": 0x04.</summary>
    /// <remarks>
    /// Next to <see cref="SourceSyncTransfer.ChallengeFrameKind"/> (0x01), <see cref="SourceSyncTransfer.ChunkFrameKind"/>
    /// (0x02) and <see cref="SourceSyncTransferAuthenticator.TagFrameKind"/> (0x03). The four kinds never share a byte.
    /// </remarks>
    public const byte ReceiptFrameKind = 0x04;

    /// <summary>How many bytes a receipt takes on the wire: one, the kind byte alone.</summary>
    public const int ReceiptFrameBytes = 1;

    /// <summary>Writes the receipt byte and flushes it, so the sender is not left waiting on a buffer.</summary>
    /// <param name="stream">The stream the envelope was just read from; the caller still owns it.</param>
    /// <param name="cancellationToken">Cancels the write.</param>
    /// <returns>Confirmed when the byte left this machine, or a reason that says why it could not.</returns>
    public static async Task<SourceSyncReceiptResult> WriteAsync(
        Stream stream,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);

        if (!stream.CanWrite)
        {
            // A read-only stream (the plaintext probe wrapper, a receive-only pipe) can never carry the receipt, and
            // saying so is better than throwing NotSupportedException out of a receive path.
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptNotEnabled,
                "This end cannot write a receipt: the stream is read-only, so the peer will see no acknowledgement.");
        }

        Stopwatch clock = Stopwatch.StartNew();
        try
        {
            await stream.WriteAsync(new byte[] { ReceiptFrameKind }, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            clock.Stop();
            return SourceSyncReceiptResult.Ok(clock.Elapsed);
        }
        catch (OperationCanceledException)
        {
            clock.Stop();
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptNotEnabled,
                "The receipt byte was not written: the write was cancelled after " + Milliseconds(clock.Elapsed) + " ms.",
                clock.Elapsed);
        }
        catch (Exception error) when (error is IOException or System.Net.Sockets.SocketException
            or ObjectDisposedException or InvalidOperationException or NotSupportedException)
        {
            clock.Stop();
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptNotEnabled,
                "The receipt byte could not be written, so the peer will see no acknowledgement: " + error.Message,
                clock.Elapsed);
        }
    }

    /// <summary>Waits for the peer's receipt byte after the last frame left this machine.</summary>
    /// <param name="stream">The stream the frames were just written to; the caller still owns it.</param>
    /// <param name="budget">How long the wait gets; null or non-positive means <see cref="DefaultBudget"/>.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <returns>Confirmed with the measured elapsed time, or the reason that names why no receipt arrived.</returns>
    /// <remarks>
    /// Exactly one byte is read, and only when it is <see cref="ReceiptFrameKind"/> is the envelope acknowledged. Anything
    /// else is <see cref="SourceSyncReceiptReasons.ReceiptInvalid"/> rather than "some frame arrived": a receiver that
    /// answers this protocol with a frame is not answering this protocol.
    /// </remarks>
    public static async Task<SourceSyncReceiptResult> AwaitAsync(
        Stream stream,
        TimeSpan? budget = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);

        if (!stream.CanRead)
        {
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptNotEnabled,
                "This end cannot read a receipt: the stream is write-only, so a receipt was asked for but can never arrive.");
        }

        TimeSpan limit = budget is null || budget.Value <= TimeSpan.Zero ? DefaultBudget : budget.Value;

        Stopwatch clock = Stopwatch.StartNew();
        using CancellationTokenSource cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(limit);

        byte[] one = new byte[ReceiptFrameBytes];
        try
        {
            int got = await stream.ReadAsync(one.AsMemory(), cts.Token).ConfigureAwait(false);
            if (got <= 0)
            {
                clock.Stop();
                return SourceSyncReceiptResult.Failed(
                    SourceSyncReceiptReasons.ReceiptMissing,
                    "The peer closed the connection without sending a receipt byte, so this send is not evidence that"
                        + " the envelope was accepted; a receiver that refuses the payload also closes without answering.",
                    clock.Elapsed);
            }

            if (one[0] != ReceiptFrameKind)
            {
                clock.Stop();
                return SourceSyncReceiptResult.Failed(
                    SourceSyncReceiptReasons.ReceiptInvalid,
                    "The peer answered with 0x" + one[0].ToString("x2", CultureInfo.InvariantCulture)
                        + " instead of the receipt kind 0x" + ReceiptFrameKind.ToString("x2", CultureInfo.InvariantCulture)
                        + ", so it did not acknowledge this envelope.",
                    clock.Elapsed);
            }

            clock.Stop();
            return SourceSyncReceiptResult.Ok(clock.Elapsed);
        }
        catch (OperationCanceledException)
        {
            clock.Stop();
            bool operatorCancelled = cancellationToken.IsCancellationRequested
                && clock.Elapsed + TimeSpan.FromMilliseconds(25) < limit;
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptTimeout,
                operatorCancelled
                    ? "The wait for a receipt was cancelled after " + Milliseconds(clock.Elapsed)
                        + " ms; whether the peer accepted the envelope is unknown."
                    : "The peer kept the connection open for " + Milliseconds(limit)
                        + " ms without sending a receipt byte, so it never acknowledged the envelope.",
                clock.Elapsed);
        }
        catch (Exception error) when (error is IOException or System.Net.Sockets.SocketException
            or ObjectDisposedException or InvalidOperationException or NotSupportedException)
        {
            clock.Stop();
            return SourceSyncReceiptResult.Failed(
                SourceSyncReceiptReasons.ReceiptMissing,
                "The connection failed before a receipt byte arrived, so the peer never acknowledged the envelope: "
                    + error.Message,
                clock.Elapsed);
        }
    }

    /// <summary>The default wait, mirroring <see cref="SourceSyncReceiptOptions.DefaultBudgetMilliseconds"/>.</summary>
    public static TimeSpan DefaultBudget { get; } =
        TimeSpan.FromMilliseconds(SourceSyncReceiptOptions.DefaultBudgetMilliseconds);

    private static string Milliseconds(TimeSpan span)
        => ((int)span.TotalMilliseconds).ToString(CultureInfo.InvariantCulture);
}

using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>Stable reasons for a send whose delivery could not be confirmed.</summary>
/// <remarks>
/// <para>
/// This is its own closed set, next to <see cref="SourceSyncTransferReasons"/> (ten codes),
/// <see cref="SourceSyncTransferAuthReasons"/> (five codes) and <see cref="SourceSyncTlsReasons"/> (four codes).
/// The existing sets stay exactly as they are: a confirmation that did not happen is not a transfer failure and not
/// an authentication failure. The frames did leave this machine; the only open question is whether the peer took
/// them, and folding that into "io-failed" would hide which side of the wire actually failed.
/// </para>
/// <para>
/// The code exists because a plain "write returned without throwing" is not evidence of receipt: in a real acceptance
/// run a plaintext sender reported success while a TLS listener rejected the very first record and never read a single
/// envelope byte (W6-28 case F). Callers that need receipt set <see cref="SourceSyncDeliveryOptions.Confirm"/> and only
/// treat a success as delivered when <see cref="SourceSyncTransferResult.DeliveryConfirmed"/> is true.
/// </para>
/// </remarks>
public static class SourceSyncDeliveryReasons
{
    /// <summary>
    /// The frames were written, but the peer did not close the connection within the budget, or the read for that
    /// close failed. Closing is the only receipt signal a one-way frame protocol has.
    /// </summary>
    public const string DeliveryUnconfirmed = "delivery-unconfirmed";

    /// <summary>Every delivery reason, in a fixed order.</summary>
    public static IReadOnlyList<string> All { get; } = new[] { DeliveryUnconfirmed };
}

/// <summary>What the wait after the last frame saw.</summary>
public sealed record SourceSyncDeliveryResult
{
    /// <summary>True only when the peer closed the connection, which is the evidence that it read to the end.</summary>
    public bool Confirmed { get; init; }

    /// <summary>One of <see cref="SourceSyncDeliveryReasons"/> when <see cref="Confirmed"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Human-readable context; carries the underlying I/O message and never an envelope body or a secret.</summary>
    public string? Detail { get; init; }

    /// <summary>Bytes the peer sent after the envelope; normally zero.</summary>
    public long BytesRead { get; init; }

    /// <summary>How long the wait actually took, so a caller can log "confirmed after N ms".</summary>
    public TimeSpan Elapsed { get; init; }

    /// <summary>The peer closed: delivery is confirmed.</summary>
    public static SourceSyncDeliveryResult Ok(long bytesRead, TimeSpan elapsed) => new()
    {
        Confirmed = true,
        BytesRead = bytesRead,
        Elapsed = elapsed,
    };

    /// <summary>The peer did not close within the budget, or the read failed.</summary>
    public static SourceSyncDeliveryResult Failed(string reason, string? detail, TimeSpan elapsed) => new()
    {
        Reason = reason,
        Detail = detail,
        Elapsed = elapsed,
    };
}

/// <summary>Whether a send waits for the peer's close, and for how long.</summary>
/// <remarks>
/// Off by default, and off is byte-for-byte the W6-16 behaviour: no extra write, no extra read, no extra wait.
/// Confirmation rests on the receiver closing the connection after it has the whole envelope, which this repository's
/// listener does; whether the Android receiver closes immediately has not been verified, so the wait is opt-in.
/// </remarks>
public sealed class SourceSyncDeliveryOptions
{
    /// <summary>How long a sender waits for the peer's close: 2000 ms.</summary>
    /// <remarks>
    /// A loopback receiver closes within milliseconds of the last frame. Two seconds is long enough for a loaded
    /// machine and short enough that an operator waiting on a stuck peer is not left guessing.
    /// </remarks>
    public const int DefaultBudgetMilliseconds = 2000;

    /// <summary>True when the sender waits for the peer's close after the last frame.</summary>
    public bool Confirm { get; init; }

    /// <summary>How long the wait gets; only read when <see cref="Confirm"/> is true.</summary>
    public TimeSpan Budget { get; init; } = TimeSpan.FromMilliseconds(DefaultBudgetMilliseconds);

    /// <summary>The default: write the frames, return, never wait.</summary>
    public static SourceSyncDeliveryOptions Off { get; } = new();

    /// <summary>Confirmation with the default budget, or an explicit one.</summary>
    /// <param name="budget">How long to wait for the peer's close; null means <see cref="DefaultBudgetMilliseconds"/>.</param>
    public static SourceSyncDeliveryOptions Confirming(TimeSpan? budget = null) => new()
    {
        Confirm = true,
        Budget = budget is null || budget.Value <= TimeSpan.Zero
            ? TimeSpan.FromMilliseconds(DefaultBudgetMilliseconds)
            : budget.Value,
    };

    /// <inheritdoc />
    public override string ToString() => "SourceSyncDeliveryOptions { Confirm = "
        + (Confirm ? "true" : "false")
        + ", BudgetMs = " + ((int)Budget.TotalMilliseconds).ToString(CultureInfo.InvariantCulture)
        + " }";
}

/// <summary>Reads a written stream until the peer closes it: the receipt a one-way frame protocol can observe.</summary>
public static class SourceSyncDeliveryConfirmation
{
    /// <summary>The default wait, mirroring <see cref="SourceSyncDeliveryOptions.DefaultBudgetMilliseconds"/>.</summary>
    public static readonly TimeSpan DefaultBudget = TimeSpan.FromMilliseconds(SourceSyncDeliveryOptions.DefaultBudgetMilliseconds);

    /// <summary>
    /// How many bytes a non-closing peer may send before the wait gives up. A peer that answers a one-way envelope with
    /// more than this is not acknowledging anything, and reading without a cap lets it hold the sender until the budget.
    /// </summary>
    public const int MaximumDrainBytes = 64 * 1024;

    /// <summary>
    /// Waits for the peer to close the connection, which is the only evidence that it read the whole envelope.
    /// </summary>
    /// <param name="stream">The stream the frames were just written to; the caller still owns it.</param>
    /// <param name="sentBytes">Envelope bytes already written, quoted in the failure detail.</param>
    /// <param name="budget">How long the wait gets; null or non-positive means <see cref="DefaultBudget"/>.</param>
    /// <param name="cancellationToken">Cancels the wait; a cancellation is reported as an unconfirmed delivery.</param>
    /// <returns>Confirmed with the measured elapsed time, or unconfirmed with the reason that names why.</returns>
    public static async Task<SourceSyncDeliveryResult> ConfirmAsync(
        Stream stream,
        long sentBytes,
        TimeSpan? budget = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        TimeSpan limit = budget is null || budget.Value <= TimeSpan.Zero ? DefaultBudget : budget.Value;

        Stopwatch clock = Stopwatch.StartNew();
        using CancellationTokenSource cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(limit);

        byte[] scratch = new byte[256];
        long read = 0;
        try
        {
            while (true)
            {
                int got = await stream.ReadAsync(scratch.AsMemory(), cts.Token).ConfigureAwait(false);
                if (got <= 0)
                {
                    // A zero-length read is the FIN: the peer consumed the frames and closed.
                    clock.Stop();
                    return SourceSyncDeliveryResult.Ok(read, clock.Elapsed);
                }

                read += got;
                if (read > MaximumDrainBytes)
                {
                    clock.Stop();
                    return SourceSyncDeliveryResult.Failed(
                        SourceSyncDeliveryReasons.DeliveryUnconfirmed,
                        "The envelope was written (" + sentBytes.ToString(CultureInfo.InvariantCulture)
                            + " bytes) but the peer kept sending after it instead of closing; a peer that answers a"
                            + " one-way envelope is not confirming receipt, so delivery stays unconfirmed.",
                        clock.Elapsed);
                }
            }
        }
        catch (OperationCanceledException)
        {
            clock.Stop();
            bool operatorCancelled = cancellationToken.IsCancellationRequested
                && clock.Elapsed + TimeSpan.FromMilliseconds(25) < limit;
            return SourceSyncDeliveryResult.Failed(
                SourceSyncDeliveryReasons.DeliveryUnconfirmed,
                operatorCancelled
                    ? "The delivery confirmation was cancelled after " + Milliseconds(clock.Elapsed)
                        + " ms; whether the peer took the envelope is unknown."
                    : "The envelope was written (" + sentBytes.ToString(CultureInfo.InvariantCulture)
                        + " bytes) but the peer did not close the connection within " + Milliseconds(limit)
                        + " ms, so the send is not evidence of delivery.",
                clock.Elapsed);
        }
        catch (Exception error) when (error is IOException or System.Net.Sockets.SocketException
            or ObjectDisposedException or InvalidOperationException or NotSupportedException)
        {
            clock.Stop();
            return SourceSyncDeliveryResult.Failed(
                SourceSyncDeliveryReasons.DeliveryUnconfirmed,
                "The envelope was written (" + sentBytes.ToString(CultureInfo.InvariantCulture)
                    + " bytes) but the peer closed the connection while the sender was waiting for its acknowledgement: "
                    + error.Message,
                clock.Elapsed);
        }
    }

    private static string Milliseconds(TimeSpan span)
        => ((int)span.TotalMilliseconds).ToString(CultureInfo.InvariantCulture);
}

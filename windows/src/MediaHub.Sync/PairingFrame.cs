using System;
using System.Buffers.Binary;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>
/// Length-prefixed framing for a single pairing payload (ADR-S003 D4).
/// </summary>
/// <remarks>
/// <para>
/// A raw JSON document over TLS would work, but reading it "to EOF" makes the exchange depend on the peer
/// closing cleanly and gives an attacker a free way to make the reader allocate without bound. A four-byte
/// big-endian length with a hard ceiling keeps one payload one frame: the reader knows when it is done, and an
/// absurd length is rejected before a single byte of body is buffered.
/// </para>
/// <para>
/// Both ends close the connection right after the frame, and neither sends anything else, so the framing does
/// not need to be extensible or self-describing beyond the length.
/// </para>
/// </remarks>
public static class PairingFrame
{
    /// <summary>The largest payload a peer may send. A six-field payload is a few hundred bytes.</summary>
    public const int MaximumPayloadBytes = 8192;

    private const int HeaderBytes = 4;

    /// <summary>Writes <paramref name="payload"/> as one length-prefixed frame and flushes it.</summary>
    /// <exception cref="ArgumentOutOfRangeException">The payload is empty or larger than <see cref="MaximumPayloadBytes"/>.</exception>
    public static async Task WriteAsync(
        Stream stream,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        if (payload.IsEmpty || payload.Length > MaximumPayloadBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(payload),
                payload.Length,
                "A pairing frame carries between 1 and " + MaximumPayloadBytes + " bytes.");
        }

        byte[] header = new byte[HeaderBytes];
        BinaryPrimitives.WriteInt32BigEndian(header, payload.Length);
        await stream.WriteAsync(header, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Reads one frame, or returns null when the stream ended early or announced a length outside the allowed
    /// range. Null is always "invalid payload"; the caller maps it to <see cref="PairingTransportReasons.PayloadInvalid"/>.
    /// </summary>
    public static async Task<byte[]?> ReadAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);

        byte[] header = new byte[HeaderBytes];
        if (!await ReadExactlyAsync(stream, header, cancellationToken).ConfigureAwait(false))
        {
            return null;
        }

        int length = BinaryPrimitives.ReadInt32BigEndian(header);
        if (length <= 0 || length > MaximumPayloadBytes)
        {
            return null;
        }

        byte[] body = new byte[length];
        return await ReadExactlyAsync(stream, body, cancellationToken).ConfigureAwait(false) ? body : null;
    }

    private static async Task<bool> ReadExactlyAsync(Stream stream, Memory<byte> buffer, CancellationToken cancellationToken)
    {
        int read = 0;
        while (read < buffer.Length)
        {
            int chunk = await stream.ReadAsync(buffer[read..], cancellationToken).ConfigureAwait(false);
            if (chunk <= 0)
            {
                return false;
            }

            read += chunk;
        }

        return true;
    }
}

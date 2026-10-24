using System.Buffers.Binary;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>What one envelope transfer did, whether it ran over a stream or over a socket.</summary>
public sealed record SourceSyncTransferResult
{
    /// <summary>True only when the whole payload arrived (receive) or left (send).</summary>
    public bool Succeeded { get; init; }

    /// <summary>One of <see cref="SourceSyncTransferReasons"/> when <see cref="Succeeded"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Human-readable context for a failure; never contains a credential or an envelope body.</summary>
    public string? Detail { get; init; }

    /// <summary>The payload the receiver reassembled; empty on a failed receive and echoed on a successful send.</summary>
    public byte[] Envelope { get; init; } = [];

    /// <summary>How many frames carried it, including the challenge frame.</summary>
    public int FrameCount { get; init; }

    /// <summary>Payload bytes, excluding frame headers.</summary>
    public int Bytes { get; init; }

    /// <summary>The challenge the sender announced, or the one the receiver accepted.</summary>
    public string? Challenge { get; init; }

    /// <summary>A successful transfer.</summary>
    public static SourceSyncTransferResult Ok(byte[] envelope, int frameCount, string? challenge = null) => new()
    {
        Succeeded = true,
        Envelope = envelope,
        FrameCount = frameCount,
        Bytes = envelope.Length,
        Challenge = challenge,
    };

    /// <summary>A failed transfer; nothing was written by the caller.</summary>
    public static SourceSyncTransferResult Failed(string reason, string? detail = null) => new()
    {
        Reason = reason,
        Detail = detail,
    };
}

/// <summary>
/// Moves one sync envelope as a sequence of <see cref="PairingFrame"/> frames.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="PairingFrame"/> gives a four-byte big-endian length and a hard 8192-byte ceiling per frame, which
/// is right for a pairing payload and too small for an envelope: a hundred sources with endpoints and field
/// hashes pass 8 KiB easily. Silently truncating that would import a half envelope, so this layer chunks instead:
/// every chunk repeats the envelope total and its own offset, and the receiver only reports success once it holds
/// exactly the announced number of bytes. A chunk that overlaps, skips, or changes the total is rejected.
/// </para>
/// <para>
/// The first frame may carry the pairing challenge, because the invitation already gave this device a challenge
/// and comparing it is the only thing that ties a transfer to the pairing that authorised it. The check happens
/// before a single envelope byte is buffered.
/// </para>
/// </remarks>
public static class SourceSyncTransfer
{
    /// <summary>The largest envelope this device will send or accept: 8 MiB.</summary>
    /// <remarks>
    /// A source record is a few hundred bytes, so 8 MiB is thousands of sources — far above any mirrored list,
    /// and small enough that a hostile peer cannot make the receiver allocate without bound.
    /// </remarks>
    public const int MaximumEnvelopeBytes = 8 * 1024 * 1024;

    /// <summary>Frame kind for the optional challenge frame.</summary>
    public const byte ChallengeFrameKind = 0x01;

    /// <summary>Frame kind for an envelope chunk.</summary>
    public const byte ChunkFrameKind = 0x02;

    /// <summary>kind byte + 4-byte total + 4-byte offset.</summary>
    private const int ChunkHeaderBytes = 9;

    /// <summary>Payload bytes per chunk: the frame ceiling minus the chunk header.</summary>
    public const int ChunkPayloadBytes = PairingFrame.MaximumPayloadBytes - ChunkHeaderBytes;

    /// <summary>The longest challenge this layer will carry, in UTF-8 bytes.</summary>
    public const int MaximumChallengeBytes = 512;

    /// <summary>How many frames a payload of <paramref name="envelopeBytes"/> needs, excluding the challenge frame.</summary>
    public static int ChunkCount(int envelopeBytes)
    {
        if (envelopeBytes <= 0)
        {
            return 0;
        }

        return (envelopeBytes + ChunkPayloadBytes - 1) / ChunkPayloadBytes;
    }

    /// <summary>Writes the optional challenge frame, then every chunk, then the optional tag frame, then flushes.</summary>
    /// <param name="stream">An open stream; the caller owns its lifetime.</param>
    /// <param name="envelope">The serialized envelope.</param>
    /// <param name="challenge">The invitation's challenge, or null to send the envelope without one.</param>
    /// <param name="cancellationToken">Cancels between frames.</param>
    /// <param name="options">The optional authentication settings. Null or <see cref="SourceSyncTransferOptions.Off"/>
    /// writes exactly the W6-16 byte stream: no tag frame, no extra flush, unchanged frame count.</param>
    public static async Task<SourceSyncTransferResult> SendAsync(
        Stream stream,
        ReadOnlyMemory<byte> envelope,
        string? challenge = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(stream);

        int total = envelope.Length;
        if (total <= 0)
        {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.PayloadTooLarge,
                "An envelope carries at least one byte; nothing was sent.");
        }

        if (total > MaximumEnvelopeBytes)
        {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.PayloadTooLarge,
                "The envelope is " + total.ToString(CultureInfo.InvariantCulture) + " bytes, above the "
                + MaximumEnvelopeBytes.ToString(CultureInfo.InvariantCulture) + " byte ceiling; nothing was sent.");
        }

        byte[]? challengeBytes = null;
        if (!string.IsNullOrWhiteSpace(challenge))
        {
            challengeBytes = Encoding.UTF8.GetBytes(challenge.Trim());
            if (challengeBytes.Length > MaximumChallengeBytes)
            {
                return SourceSyncTransferResult.Failed(
                    SourceSyncTransferReasons.PayloadTooLarge,
                    "The challenge is longer than " + MaximumChallengeBytes.ToString(CultureInfo.InvariantCulture)
                    + " bytes; nothing was sent.");
            }
        }

        SourceSyncTransferOptions? auth = options is { Authenticate: true } ? options : null;
        byte[]? tagNonce = null;
        if (auth is not null)
        {
            if (!auth.HasSecret)
            {
                return SourceSyncTransferResult.Failed(
                    SourceSyncTransferAuthReasons.AuthSecretUnavailable,
                    "Authentication is on but this process holds no secret; nothing was sent.");
            }

            if (challengeBytes is null)
            {
                return SourceSyncTransferResult.Failed(
                    SourceSyncTransferAuthReasons.AuthRequired,
                    "Authentication is on but no challenge was announced; the challenge is the nonce the tag binds to, so nothing was sent.");
            }

            tagNonce = challengeBytes;
        }

        int frames = 0;
        try
        {
            if (challengeBytes is not null)
            {
                byte[] challengeFrame = new byte[1 + challengeBytes.Length];
                challengeFrame[0] = ChallengeFrameKind;
                challengeBytes.CopyTo(challengeFrame, 1);
                await PairingFrame.WriteAsync(stream, challengeFrame, cancellationToken).ConfigureAwait(false);
                frames++;
            }

            for (int offset = 0; offset < total; offset += ChunkPayloadBytes)
            {
                int length = Math.Min(ChunkPayloadBytes, total - offset);
                byte[] frame = new byte[ChunkHeaderBytes + length];
                frame[0] = ChunkFrameKind;
                BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(1, 4), total);
                BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(5, 4), offset);
                envelope.Slice(offset, length).Span.CopyTo(frame.AsSpan(ChunkHeaderBytes));
                await PairingFrame.WriteAsync(stream, frame, cancellationToken).ConfigureAwait(false);
                frames++;
            }

            if (auth is not null && tagNonce is not null)
            {
                // The tag covers the exact bytes just written and the exact nonce the peer was told. It goes after
                // the last chunk so a receiver missing it can tell "no proof" apart from "cut off mid-envelope".
                byte[] tag = SourceSyncTransferAuthenticator.CreateTag(auth.Secret, tagNonce, envelope.Span);
                byte[] tagFrame = new byte[SourceSyncTransferAuthenticator.TagFrameBytes];
                tagFrame[0] = SourceSyncTransferAuthenticator.TagFrameKind;
                tag.CopyTo(tagFrame, 1);
                await PairingFrame.WriteAsync(stream, tagFrame, cancellationToken).ConfigureAwait(false);
                frames++;
            }

            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidOperationException)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }

        return SourceSyncTransferResult.Ok(envelope.ToArray(), frames, challenge?.Trim());
    }

    /// <summary>
    /// Reads frames until the announced total has arrived, checks the peer's tag frame when authentication is on,
    /// and returns the reassembled payload.
    /// </summary>
    /// <param name="stream">An open stream; the caller owns its lifetime.</param>
    /// <param name="expectedChallenge">The invitation's challenge, or null to accept whatever the peer announces.</param>
    /// <param name="cancellationToken">Cancels between frames.</param>
    /// <param name="options">The optional authentication settings. When on, a complete envelope is only accepted
    /// after a valid tag frame whose nonce this device has not seen before. Null or
    /// <see cref="SourceSyncTransferOptions.Off"/> reads exactly the W6-16 byte stream.</param>
    public static async Task<SourceSyncTransferResult> ReceiveAsync(
        Stream stream,
        string? expectedChallenge = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(stream);

        SourceSyncTransferOptions? auth = options is { Authenticate: true } ? options : null;
        if (auth is not null && !auth.HasSecret)
        {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferAuthReasons.AuthSecretUnavailable,
                "Authentication is on but this process holds no secret to check the peer's tag with; nothing was read.");
        }

        string expected = expectedChallenge?.Trim() ?? "";
        bool challengeRequired = expected.Length > 0;
        byte[]? assembled = null;
        bool complete = false;
        int total = -1;
        int received = 0;
        int frames = 0;
        string? offered = null;

        try
        {
            while (true)
            {
                byte[]? frame = await PairingFrame.ReadAsync(stream, cancellationToken).ConfigureAwait(false);
                if (frame is null)
                {
                    if (auth is not null && complete)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AuthMissing,
                            "The peer sent the whole envelope but no tag frame; nothing was accepted.");
                    }

                    return assembled is null
                        ? SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameTruncated,
                            "The peer closed before sending a frame, or announced a frame length outside 1.."
                            + PairingFrame.MaximumPayloadBytes.ToString(CultureInfo.InvariantCulture) + ".")
                        : SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameTruncated,
                            "The peer stopped after " + received.ToString(CultureInfo.InvariantCulture) + " of "
                            + total.ToString(CultureInfo.InvariantCulture) + " bytes.");
                }

                frames++;
                byte kind = frame[0];

                if (kind == SourceSyncTransferAuthenticator.TagFrameKind)
                {
                    if (auth is null)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "Unknown frame kind 0x03.");
                    }

                    if (assembled is null)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "The tag frame arrived before the first chunk.");
                    }

                    if (!complete)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "The tag frame arrived after " + received.ToString(CultureInfo.InvariantCulture) + " of "
                            + total.ToString(CultureInfo.InvariantCulture) + " bytes; it must follow the last chunk.");
                    }

                    if (frame.Length != SourceSyncTransferAuthenticator.TagFrameBytes)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "A tag frame is " + frame.Length.ToString(CultureInfo.InvariantCulture)
                            + " bytes; an HMAC-SHA256 tag is "
                            + SourceSyncTransferAuthenticator.TagBytes.ToString(CultureInfo.InvariantCulture)
                            + " bytes plus the kind byte.");
                    }

                    byte[] tag = frame.AsSpan(1).ToArray();

                    if (string.IsNullOrEmpty(offered))
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AuthMissing,
                            "The peer sent a tag but announced no challenge for it to bind to; nothing was accepted.");
                    }

                    if (!SourceSyncTransferAuthenticator.VerifyTag(auth.Secret, offered, assembled, tag))
                    {
                        // The nonce is deliberately not recorded here: a forged envelope must not be able to burn
                        // the real nonce and so make the legitimate transfer look like a replay.
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AuthMismatch,
                            "The tag does not match this envelope and nonce under this secret; nothing was accepted and the nonce was not recorded.");
                    }

                    if (!auth.ActiveNonces.Accept(auth.DeviceId, offered))
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AuthReplayed,
                            "This device already accepted an envelope under this nonce; a replayed transfer is ignored and nothing was accepted.");
                    }

                    return SourceSyncTransferResult.Ok(assembled, frames, offered);
                }

                if (kind == ChallengeFrameKind)
                {
                    if (frames != 1)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "The challenge frame must come before the first chunk.");
                    }

                    offered = Encoding.UTF8.GetString(frame, 1, frame.Length - 1).Trim();
                    if (offered.Length == 0)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "The challenge frame carried no challenge.");
                    }

                    if (challengeRequired && !string.Equals(offered, expected, StringComparison.Ordinal))
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.ChallengeMismatch,
                            "The peer offered a challenge that does not match the invitation; nothing was read from the envelope.");
                    }

                    continue;
                }

                if (kind != ChunkFrameKind)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "Unknown frame kind 0x" + kind.ToString("x2", CultureInfo.InvariantCulture) + ".");
                }

                if (complete)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "The peer sent another chunk after the envelope was complete; the tag frame must follow the last chunk.");
                }

                if (frames == 1 && challengeRequired)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.ChallengeMismatch,
                        "The peer did not announce the invitation's challenge; nothing was read from the envelope.");
                }

                if (frame.Length <= ChunkHeaderBytes)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "A chunk frame is " + frame.Length.ToString(CultureInfo.InvariantCulture)
                        + " bytes; a header alone carries no payload.");
                }

                int frameTotal = BinaryPrimitives.ReadInt32BigEndian(frame.AsSpan(1, 4));
                int frameOffset = BinaryPrimitives.ReadInt32BigEndian(frame.AsSpan(5, 4));
                int payloadLength = frame.Length - ChunkHeaderBytes;

                if (frameTotal <= 0)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "A chunk announced a total of " + frameTotal.ToString(CultureInfo.InvariantCulture) + " bytes.");
                }

                if (frameTotal > MaximumEnvelopeBytes)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.PayloadTooLarge,
                        "The peer announced " + frameTotal.ToString(CultureInfo.InvariantCulture)
                        + " bytes, above the " + MaximumEnvelopeBytes.ToString(CultureInfo.InvariantCulture)
                        + " byte ceiling; nothing was buffered.");
                }

                if (frameOffset < 0)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "A chunk announced a negative offset.");
                }

                if (assembled is null)
                {
                    total = frameTotal;
                    if (frameOffset != 0)
                    {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FrameInvalid,
                            "The first chunk starts at " + frameOffset.ToString(CultureInfo.InvariantCulture)
                            + " instead of 0.");
                    }

                    assembled = new byte[frameTotal];
                }
                else if (frameTotal != total)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "The chunk announced " + frameTotal.ToString(CultureInfo.InvariantCulture)
                        + " bytes after " + total.ToString(CultureInfo.InvariantCulture) + ".");
                }

                if (frameOffset != received)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "The chunk starts at " + frameOffset.ToString(CultureInfo.InvariantCulture)
                        + " but " + received.ToString(CultureInfo.InvariantCulture) + " bytes have arrived.");
                }

                if (received + payloadLength > total)
                {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FrameInvalid,
                        "The chunk runs past the announced total of " + total.ToString(CultureInfo.InvariantCulture)
                        + " bytes.");
                }

                Array.Copy(frame, ChunkHeaderBytes, assembled, received, payloadLength);
                received += payloadLength;

                if (received == total)
                {
                    if (auth is null)
                    {
                        return SourceSyncTransferResult.Ok(assembled, frames, offered);
                    }

                    // With authentication on a complete envelope is not yet a successful transfer: keep reading and
                    // wait for the tag frame (or for the peer to close, which is auth-missing).
                    complete = true;
                }
            }
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidOperationException)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
    }
}

using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-24: the optional tag frame that makes both ends of a loopback transfer prove they hold the
/// pairing's shared secret.
/// </summary>
/// <remarks>
/// <para>
/// The framing tests run over a <see cref="MemoryStream"/> so a hostile frame sequence can be written
/// byte by byte; only the two end-to-end CLI tests open a real loopback socket, and they use port 0 so
/// nothing is left listening afterwards.
/// </para>
/// <para>
/// Every negative case asserts both the stable code <em>and</em> that nothing was accepted: a refusal
/// that still handed back an envelope would be worse than no authentication at all.
/// </para>
/// </remarks>
public sealed class SourceSyncTransferAuthTests
{
    private const string Nonce = "nonce-abc-123";
    private const int ChunkPayloadBytes = 8183;

    private static readonly byte[] Secret = Encoding.UTF8.GetBytes("correct horse battery staple");

    // ---- the primitive -----------------------------------------------------------------------

    [Fact]
    public void A_tag_is_the_hmac_of_the_nonce_then_the_envelope()
    {
        byte[] payload = Payload();

        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);

        byte[] nonceBytes = Encoding.UTF8.GetBytes(Nonce);
        byte[] message = new byte[nonceBytes.Length + payload.Length];
        nonceBytes.CopyTo(message, 0);
        payload.CopyTo(message, nonceBytes.Length);
        byte[] expected = HMACSHA256.HashData(Secret, message);

        Assert.Equal(SourceSyncTransferAuthenticator.TagBytes, tag.Length);
        Assert.Equal(expected, tag);
    }

    [Fact]
    public void A_tag_only_verifies_under_the_nonce_it_was_made_for()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);

        Assert.True(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce, payload, tag));
        Assert.False(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce + "x", payload, tag));
    }

    [Fact]
    public void A_tag_only_verifies_under_the_secret_it_was_made_with()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);

        Assert.True(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce, payload, tag));
        Assert.False(SourceSyncTransferAuthenticator.VerifyTag(Encoding.UTF8.GetBytes("another secret"), Nonce, payload, tag));
    }

    [Fact]
    public void A_tag_of_the_wrong_length_never_verifies()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);

        Assert.False(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce, payload, tag[..31]));
        Assert.False(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce, payload, [.. tag, 0x00]));
        Assert.False(SourceSyncTransferAuthenticator.VerifyTag(Secret, Nonce, payload, []));
    }

    [Fact]
    public void An_empty_secret_cannot_make_a_tag_and_verifies_nothing()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);

        Assert.Throws<ArgumentException>(() => SourceSyncTransferAuthenticator.CreateTag([], Nonce, payload));
        Assert.False(SourceSyncTransferAuthenticator.VerifyTag([], Nonce, payload, tag));
    }

    [Fact]
    public void A_generated_nonce_is_twenty_two_url_safe_characters()
    {
        string nonce = SourceSyncTransferAuthenticator.CreateNonce();

        Assert.Equal(22, nonce.Length);
        Assert.All(nonce, character => Assert.True(
            char.IsAsciiLetterOrDigit(character) || character is '-' or '_',
            "a nonce must survive the challenge frame's UTF-8 trim unchanged, so no '+', '/' or '=' may appear"));
    }

    [Fact]
    public void A_generated_nonce_does_not_repeat()
    {
        HashSet<string> seen = new(StringComparer.Ordinal);
        for (int i = 0; i < 1000; i++)
        {
            Assert.True(seen.Add(SourceSyncTransferAuthenticator.CreateNonce()));
        }
    }

    // ---- the replay window -------------------------------------------------------------------

    [Fact]
    public void The_nonce_cache_refuses_the_same_nonce_twice()
    {
        SourceSyncNonceCache cache = new();

        Assert.True(cache.Accept("dev-a", "one"));
        Assert.False(cache.Accept("dev-a", "one"));
        Assert.Equal(1, cache.Count);
        Assert.Equal(SourceSyncNonceCache.DefaultCapacity, cache.Capacity);
    }

    [Fact]
    public void The_nonce_cache_partitions_by_device()
    {
        SourceSyncNonceCache cache = new();

        Assert.True(cache.Accept("dev-a", "one"));
        Assert.True(cache.Accept("dev-b", "one"));
        Assert.False(cache.Accept("dev-a", "one"));
        Assert.False(cache.Accept("dev-b", "one"));
    }

    [Fact]
    public void The_nonce_cache_evicts_the_oldest_nonce_first()
    {
        SourceSyncNonceCache cache = new(capacity: 2);

        Assert.True(cache.Accept("dev-a", "one"));
        Assert.True(cache.Accept("dev-a", "two"));
        Assert.True(cache.Accept("dev-a", "three"));

        // "one" fell out of the window, so it may be seen again; "two" and "three" may not.
        Assert.True(cache.Accept("dev-a", "one"));
        Assert.False(cache.Accept("dev-a", "three"));
        Assert.Equal(2, cache.Count);
    }

    [Fact]
    public void The_nonce_cache_rejects_a_capacity_below_one()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SourceSyncNonceCache(capacity: 0));
    }

    [Fact]
    public void The_options_never_print_the_secret()
    {
        SourceSyncTransferOptions on = SourceSyncTransferOptions.WithSecret(Secret, deviceId: "dev-a");

        string text = on.ToString();

        Assert.DoesNotContain(Encoding.UTF8.GetString(Secret), text, StringComparison.Ordinal);
        Assert.Contains("<redacted 28 bytes>", text, StringComparison.Ordinal);
        Assert.Contains("Authenticate = true", text, StringComparison.Ordinal);
        Assert.Contains("dev-a", text, StringComparison.Ordinal);
        Assert.Contains("Secret = <none>", SourceSyncTransferOptions.Off.ToString(), StringComparison.Ordinal);
        Assert.False(SourceSyncTransferOptions.Off.Authenticate);
    }

    // ---- sending --------------------------------------------------------------------------------

    [Fact]
    public async Task Sending_with_authentication_appends_one_tag_frame()
    {
        byte[] payload = Payload();
        SourceSyncTransferOptions options = SourceSyncTransferOptions.WithSecret(Secret);

        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload, Nonce, default, options);

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(3, sent.FrameCount);
        Assert.Equal(Nonce, sent.Challenge);

        List<byte[]> frames = ReadFrames(stream.ToArray());
        Assert.Equal(3, frames.Count);
        Assert.Equal(0x01, frames[0][0]);
        Assert.Equal(SourceSyncTransferAuthenticator.TagFrameKind, frames[2][0]);
        Assert.Equal(SourceSyncTransferAuthenticator.TagFrameBytes, frames[2].Length);
        Assert.Equal(SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload), frames[2][1..]);
    }

    [Fact]
    public async Task Sending_with_authentication_but_no_secret_writes_nothing()
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(
            stream, Payload(), Nonce, default, SourceSyncTransferOptions.WithSecret(null));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthSecretUnavailable, sent.Reason);
        Assert.Equal(0, stream.Length);
        Assert.Equal(0, sent.FrameCount);
    }

    [Fact]
    public async Task Sending_with_authentication_but_no_challenge_writes_nothing()
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(
            stream, Payload(), null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthRequired, sent.Reason);
        Assert.Equal(0, stream.Length);
        Assert.Equal(0, sent.FrameCount);
    }

    [Fact]
    public async Task With_authentication_off_the_bytes_are_exactly_the_w6_16_frame_stream()
    {
        byte[] payload = Payload();
        byte[] expected = [.. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)), .. ChunkFrame(payload.Length, 0, payload)];

        using MemoryStream omitted = new();
        SourceSyncTransferResult withoutOptions = await SourceSyncTransfer.SendAsync(omitted, payload, Nonce);

        using MemoryStream off = new();
        SourceSyncTransferResult withOff = await SourceSyncTransfer.SendAsync(off, payload, Nonce, default, SourceSyncTransferOptions.Off);

        Assert.True(withoutOptions.Succeeded, withoutOptions.Detail);
        Assert.True(withOff.Succeeded, withOff.Detail);
        Assert.Equal(2, withoutOptions.FrameCount);
        Assert.Equal(2, withOff.FrameCount);
        Assert.Equal(expected, omitted.ToArray());
        Assert.Equal(expected, off.ToArray());
    }

    [Fact]
    public async Task With_authentication_off_the_bytes_match_the_frozen_w6_16_hex_vector()
    {
        // Golden vector frozen from the W6-16 byte stream, recorded before W6-24 existed:
        //   18-byte challenge frame: 0000000e | 01 | "nonce-abc-123"
        //   77-byte chunk frame:     00000049 | 02 | 00000040 (total) | 00000000 (offset) | 64 payload bytes
        // 95 bytes in total. Authentication being off must not move a single one of them.
        const string GoldenHex = "0000000e016e6f6e63652d6162632d313233000000490200000040000000000726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8";

        byte[] golden = Convert.FromHexString(GoldenHex);
        Assert.Equal(95, golden.Length);

        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, Payload(), Nonce);

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(2, sent.FrameCount);
        Assert.Equal(95, (int)stream.Length); // the wire is 95 bytes; Bytes counts the envelope
        Assert.Equal(64, sent.Bytes);
        Assert.Equal(GoldenHex, Convert.ToHexString(stream.ToArray()).ToLowerInvariant());

        // The frozen bytes still read back, so the vector pins both directions of the wire format.
        using MemoryStream replay = new(golden);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(replay, Nonce);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(2, received.FrameCount);
        Assert.Equal(Payload(), received.Envelope);
        Assert.Equal(Nonce, received.Challenge);
    }

    // ---- receiving ------------------------------------------------------------------------------

    [Fact]
    public async Task A_valid_tag_is_accepted_and_the_tag_frame_is_counted()
    {
        byte[] payload = Payload();
        SourceSyncTransferOptions options = SourceSyncTransferOptions.WithSecret(Secret, deviceId: "dev-b");
        byte[] wire = await Wire(payload, Nonce, Secret);

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null, default, options);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(payload, received.Envelope);
        Assert.Equal(Nonce, received.Challenge);
        Assert.Equal(3, received.FrameCount);
        Assert.Equal(1, options.ActiveNonces.Count);
    }

    [Fact]
    public async Task A_complete_envelope_without_a_tag_frame_is_auth_missing()
    {
        byte[] payload = Payload();
        byte[] wire = await Wire(payload, Nonce, secret: null);

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMissing, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task A_tag_from_the_wrong_secret_is_auth_mismatch()
    {
        byte[] payload = Payload();
        byte[] wire = await Wire(payload, Nonce, Encoding.UTF8.GetBytes("not the shared secret"));

        using MemoryStream stream = new(wire);
        SourceSyncTransferOptions options = SourceSyncTransferOptions.WithSecret(Secret);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null, default, options);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, received.Reason);
        Assert.Empty(received.Envelope);
        Assert.Equal(0, options.ActiveNonces.Count);
    }

    [Fact]
    public async Task A_forged_tag_does_not_burn_the_nonce()
    {
        byte[] payload = Payload();
        SourceSyncTransferOptions options = SourceSyncTransferOptions.WithSecret(Secret);

        using MemoryStream forged = new(await Wire(payload, Nonce, Encoding.UTF8.GetBytes("not the shared secret")));
        SourceSyncTransferResult refused = await SourceSyncTransfer.ReceiveAsync(forged, null, default, options);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, refused.Reason);

        // The same nonce, this time with the real tag: the forged attempt must not have poisoned it.
        using MemoryStream genuine = new(await Wire(payload, Nonce, Secret));
        SourceSyncTransferResult accepted = await SourceSyncTransfer.ReceiveAsync(genuine, null, default, options);

        Assert.True(accepted.Succeeded, accepted.Detail);
        Assert.Equal(payload, accepted.Envelope);
    }

    [Fact]
    public async Task A_tampered_envelope_is_auth_mismatch()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);
        byte[] tampered = (byte[])payload.Clone();
        tampered[10] ^= 0xFF;
        byte[] wire =
        [
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, tampered),
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, tag),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task The_same_envelope_under_the_same_nonce_twice_is_auth_replayed()
    {
        byte[] payload = Payload();
        SourceSyncTransferOptions options = SourceSyncTransferOptions.WithSecret(Secret);
        byte[] wire = await Wire(payload, Nonce, Secret);

        using MemoryStream first = new(wire);
        SourceSyncTransferResult accepted = await SourceSyncTransfer.ReceiveAsync(first, null, default, options);
        Assert.True(accepted.Succeeded, accepted.Detail);

        using MemoryStream second = new(wire);
        SourceSyncTransferResult replayed = await SourceSyncTransfer.ReceiveAsync(second, null, default, options);

        Assert.False(replayed.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthReplayed, replayed.Reason);
        Assert.Empty(replayed.Envelope);
    }

    [Fact]
    public async Task A_tag_frame_before_the_last_chunk_is_refused()
    {
        byte[] payload = Payload(9000);
        byte[] firstChunk = payload[..ChunkPayloadBytes];
        byte[] secondChunk = payload[ChunkPayloadBytes..];
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);
        byte[] wire =
        [
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, firstChunk),
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, tag),
            .. ChunkFrame(payload.Length, firstChunk.Length, secondChunk),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("after " + ChunkPayloadBytes.ToString(CultureInfo.InvariantCulture) + " of 9000 bytes", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_tag_frame_before_the_first_chunk_is_refused()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);
        byte[] wire =
        [
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, tag),
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, payload),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("The tag frame arrived before the first chunk.", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_tag_frame_of_the_wrong_length_is_refused()
    {
        byte[] payload = Payload();
        byte[] wire =
        [
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, payload),
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, new byte[31]),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("an HMAC-SHA256 tag is 32 bytes", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task With_authentication_off_a_tag_frame_is_an_unknown_kind()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);
        byte[] wire =
        [
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, tag),
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, payload),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null);

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("Unknown frame kind 0x03.", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task With_authentication_off_a_trailing_tag_is_simply_never_read()
    {
        // W6-16's reader returns as soon as the envelope is complete, so an old receiver still reads an
        // authenticated sender's stream: the extra frame is never looked at and the count stays at W6-16's.
        byte[] payload = Payload();
        byte[] wire = await Wire(payload, Nonce, Secret);

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(stream, null);

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(payload, received.Envelope);
        Assert.Equal(2, received.FrameCount);
    }

    [Fact]
    public async Task A_chunk_after_the_last_chunk_is_refused_while_authenticating()
    {
        byte[] payload = Payload();
        byte[] wire =
        [
            .. Frame(0x01, Encoding.UTF8.GetBytes(Nonce)),
            .. ChunkFrame(payload.Length, 0, payload),
            .. ChunkFrame(payload.Length, payload.Length, [0x42]),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameInvalid, received.Reason);
        Assert.Contains("another chunk after the envelope was complete", received.Detail ?? "", StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_tag_without_an_announced_challenge_is_auth_missing()
    {
        byte[] payload = Payload();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, Nonce, payload);
        byte[] wire =
        [
            .. ChunkFrame(payload.Length, 0, payload),
            .. Frame(SourceSyncTransferAuthenticator.TagFrameKind, tag),
        ];

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(Secret));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMissing, received.Reason);
        Assert.Empty(received.Envelope);
    }

    [Fact]
    public async Task Receiving_with_authentication_but_no_secret_reads_nothing()
    {
        byte[] payload = Payload();
        byte[] wire = await Wire(payload, Nonce, Secret);

        using MemoryStream stream = new(wire);
        SourceSyncTransferResult received = await SourceSyncTransfer.ReceiveAsync(
            stream, null, default, SourceSyncTransferOptions.WithSecret(null));

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthSecretUnavailable, received.Reason);
        Assert.Empty(received.Envelope);
        Assert.Equal(0, received.FrameCount);
    }

    [Fact]
    public async Task Every_auth_code_is_outside_the_closed_w6_16_set()
    {
        Assert.Equal(5, SourceSyncTransferAuthReasons.All.Count);
        Assert.Equal(5, SourceSyncTransferAuthReasons.All.Distinct(StringComparer.Ordinal).Count());
        Assert.Equal(10, SourceSyncTransferReasons.All.Count);
        Assert.Empty(SourceSyncTransferReasons.All.Intersect(SourceSyncTransferAuthReasons.All, StringComparer.Ordinal));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static byte[] Payload(int length = 64)
    {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++)
        {
            payload[i] = (byte)((i * 31) + 7);
        }

        return payload;
    }

    /// <summary>The bytes the real sender writes for this payload, with a tag only when a secret is given.</summary>
    private static async Task<byte[]> Wire(byte[] payload, string nonce, byte[]? secret)
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = secret is null
            ? await SourceSyncTransfer.SendAsync(stream, payload, nonce)
            : await SourceSyncTransfer.SendAsync(stream, payload, nonce, default, SourceSyncTransferOptions.WithSecret(secret));
        Assert.True(sent.Succeeded, sent.Detail);
        return stream.ToArray();
    }

    private static byte[] Frame(byte kind, params byte[] body)
    {
        byte[] payload = new byte[1 + body.Length];
        payload[0] = kind;
        body.CopyTo(payload, 1);

        byte[] framed = new byte[4 + payload.Length];
        BinaryPrimitives.WriteInt32BigEndian(framed.AsSpan(0, 4), payload.Length);
        payload.CopyTo(framed, 4);
        return framed;
    }

    private static byte[] ChunkFrame(int total, int offset, byte[] payload)
    {
        byte[] body = new byte[8 + payload.Length];
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(0, 4), total);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(4, 4), offset);
        payload.CopyTo(body, 8);
        return Frame(0x02, body);
    }

    private static List<byte[]> ReadFrames(byte[] wire)
    {
        List<byte[]> frames = [];
        int position = 0;
        while (position + 4 <= wire.Length)
        {
            int length = BinaryPrimitives.ReadInt32BigEndian(wire.AsSpan(position, 4));
            Assert.True(length is >= 1 and <= 8192, "the wire carried a frame length outside 1..8192");
            frames.Add(wire.AsSpan(position + 4, length).ToArray());
            position += 4 + length;
        }

        Assert.Equal(wire.Length, position);
        return frames;
    }
}

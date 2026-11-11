using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;
using Xunit.Abstractions;

namespace MediaHub.Tests;

/// <summary>
/// W6-31: the hostile-input matrix for the W6-16 transfer frames, driven through a real loopback listener.
/// </summary>
/// <remarks>
/// <para>
/// Every case here is a peer that a well-behaved MediaHub never produces: a length prefix that promises
/// gigabytes, a chunk that rewinds, a tag frame with no authentication, a socket that resets mid-handshake.
/// The properties each case has to satisfy are the same four, and they are the reason this file asserts more
/// than a reason string:
/// </para>
/// <para>
/// 1. The peer is refused with a code from one of the four closed, documented sets (the ten W6-16 transfer
/// codes, the five W6-24 authentication codes, the four W6-26 TLS codes, the one W6-30 delivery code) - never
/// with an exception and never with a code the callers cannot branch on.
/// 2. Nothing is written: neither <c>sync-sources.json</c> nor <c>sync-baseline.json</c> exists afterwards, so
/// "refused" cannot quietly mean "imported first, complained later".
/// 3. The case finishes well inside the fixture budget, i.e. hostile input cannot hold a receiver open.
/// 4. No unhandled exception escapes: the receive call returns a result, it does not throw.
/// </para>
/// <para>
/// The peers are raw sockets on purpose. Building the wire bytes by hand is what lets a case state a frame
/// that no sender in this repository can produce, which is exactly the input a hostile peer would send.
/// </para>
/// </remarks>
public sealed class SourceSyncHostileInputTests : IDisposable
{
    private const string PeerDeviceId = "hostile-peer-1";
    private const string EmbyUrl = "http://192.168.5.20:8096/emby";

    /// <summary>A secret that never leaves this process (only its HMAC is on the wire).</summary>
    private static readonly byte[] Secret = Encoding.UTF8.GetBytes("correct horse battery staple");

    /// <summary>The budget every single case must finish inside (task W6-31: under 5 s each).</summary>
    private static readonly TimeSpan CaseBudget = TimeSpan.FromSeconds(5);

    private static readonly TimeSpan ReceiveBudget = TimeSpan.FromSeconds(10);

    /// <summary>The union of the four closed code sets: any refusal outside this union is a contract break.</summary>
    private static readonly IReadOnlyList<string> KnownCodes =
    [
        .. SourceSyncTransferReasons.All,
        .. SourceSyncTransferAuthReasons.All,
        .. SourceSyncTlsReasons.All,
        .. SourceSyncDeliveryReasons.All,
    ];

    private readonly List<string> _directories = [];
    private readonly ITestOutputHelper _output;

    /// <summary>One instance per case; the output sink is only used to record the measured codes.</summary>
    /// <param name="output">xUnit's per-test output, used for the measured-code evidence lines.</param>
    public SourceSyncHostileInputTests(ITestOutputHelper output) => _output = output;

    // ---- 1. frames whose own structure is wrong -------------------------------------------------

    [Fact]
    public async Task A_frame_length_of_two_gigabytes_then_disconnect_is_frame_truncated()
    {
        // 0x7FFFFFFF is inside the signed range, so it is a claim rather than a parse error. It is also far
        // above PairingFrame.MaximumPayloadBytes, so the framing layer refuses to wait for it at all.
        Case hostile = await RunRawAsync(LengthPrefix(0x7FFFFFFF));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated, "outside 1..8192");
    }

    [Fact]
    public async Task A_zero_length_frame_header_is_frame_truncated()
    {
        Case hostile = await RunRawAsync(LengthPrefix(0));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated, "outside 1..8192");
    }

    [Fact]
    public async Task A_negative_length_frame_header_is_frame_truncated()
    {
        // 0xFFFFFFFF read as a signed length is -1: a claimed length that cannot exist.
        Case hostile = await RunRawAsync(LengthPrefix(-1));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated, "outside 1..8192");
    }

    [Fact]
    public async Task A_chunk_announcing_more_than_the_envelope_ceiling_is_payload_too_large()
    {
        // The chunk header is well formed; only the announced total is impossible. Nothing is buffered.
        Case hostile = await RunRawAsync(ChunkFrame(SourceSyncTransfer.MaximumEnvelopeBytes + 1, 0, [1, 2, 3, 4]));

        AssertRefused(hostile, SourceSyncTransferReasons.PayloadTooLarge, "8388608 byte ceiling");
    }

    [Fact]
    public async Task A_chunk_announcing_zero_bytes_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(ChunkFrame(0, 0, [1, 2, 3, 4]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid);
    }

    [Fact]
    public async Task A_negative_chunk_total_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(ChunkFrame(-5, 0, [1, 2, 3, 4]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid);
    }

    [Fact]
    public async Task A_negative_chunk_offset_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(ChunkFrame(16, -1, [1, 2, 3, 4]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid);
    }

    [Fact]
    public async Task A_first_chunk_that_does_not_start_at_zero_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(ChunkFrame(16, 8, [1, 2, 3, 4, 5, 6, 7, 8]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The first chunk starts at 8 instead of 0.");
    }

    [Fact]
    public async Task A_chunk_that_changes_the_announced_total_mid_envelope_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChunkFrame(16, 0, [1, 2, 3, 4, 5, 6, 7, 8]),
            ChunkFrame(32, 8, [1, 2, 3, 4, 5, 6, 7, 8]));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "announced 32 bytes after 16");
    }

    [Fact]
    public async Task A_chunk_that_skips_bytes_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChunkFrame(16, 0, [1, 2, 3, 4]),
            ChunkFrame(16, 8, [1, 2, 3, 4]));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The chunk starts at 8 but 4 bytes have arrived.");
    }

    [Fact]
    public async Task A_chunk_that_runs_past_the_announced_total_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChunkFrame(8, 0, [1, 2, 3, 4]),
            ChunkFrame(8, 4, [1, 2, 3, 4, 5, 6, 7, 8]));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "runs past the announced total of 8 bytes");
    }

    [Fact]
    public async Task A_repeated_chunk_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChunkFrame(16, 0, [1, 2, 3, 4]),
            ChunkFrame(16, 0, [1, 2, 3, 4]));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The chunk starts at 0 but 4 bytes have arrived.");
    }

    [Fact]
    public async Task A_chunk_frame_without_a_payload_is_frame_invalid()
    {
        // Kind byte plus the eight header bytes and nothing else: a header is not an envelope.
        Case hostile = await RunRawAsync(Frame(0x02, new byte[8]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "a header alone carries no payload");
    }

    [Fact]
    public async Task An_unknown_frame_kind_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(Frame(0x7F, [1, 2, 3]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "Unknown frame kind 0x7f.");
    }

    [Fact]
    public async Task An_unknown_frame_kind_zero_zero_four_is_frame_invalid()
    {
        // 0x04 is one past the highest kind this build knows, so it is a version skew, not a crash.
        Case hostile = await RunRawAsync(Frame(0x04));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "Unknown frame kind 0x04.");
    }

    [Fact]
    public async Task A_tag_frame_without_authentication_is_frame_invalid()
    {
        // A peer that sends a W6-24 tag while the receiver is not authenticating gets the same answer a
        // mistyped kind would: the tag is not silently ignored, because ignoring it would accept a frame
        // this build cannot interpret.
        Case hostile = await RunRawAsync(Frame(0x03, new byte[SourceSyncTransferAuthenticator.TagBytes]));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "Unknown frame kind 0x03.");
    }

    // ---- 2. the challenge frame, out of place ---------------------------------------------------

    [Fact]
    public async Task A_challenge_frame_after_a_data_chunk_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChunkFrame(16, 0, [1, 2, 3, 4, 5, 6, 7, 8]),
            ChallengeFrame("late"));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The challenge frame must come before the first chunk.");
    }

    [Fact]
    public async Task A_second_challenge_frame_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChallengeFrame("first"),
            ChallengeFrame("second"),
            ChunkFrame(3, 0, "abc"u8.ToArray()));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The challenge frame must come before the first chunk.");
    }

    [Fact]
    public async Task A_challenge_frame_with_no_text_is_frame_invalid()
    {
        Case hostile = await RunRawAsync(Frame(0x01));

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The challenge frame carried no challenge.");
    }

    [Fact]
    public async Task A_challenge_that_differs_from_the_invitation_is_challenge_mismatch()
    {
        byte[] wire = Concat(ChallengeFrame("someone-else"), ChunkFrame(3, 0, "abc"u8.ToArray()));

        Case hostile = await RunRawAsync(wire, expectedChallenge: "the-invitations-challenge");

        AssertRefused(hostile, SourceSyncTransferReasons.ChallengeMismatch, "does not match the invitation");
    }

    [Fact]
    public async Task A_data_chunk_without_the_announced_challenge_is_challenge_mismatch()
    {
        // The invitation announced a nonce; a peer that opens with a data chunk never proved it knows it.
        Case hostile = await RunRawAsync(ChunkFrame(3, 0, "abc"u8.ToArray()), expectedChallenge: "the-invitations-challenge");

        AssertRefused(hostile, SourceSyncTransferReasons.ChallengeMismatch, "did not announce the invitation's challenge");
    }

    [Fact]
    public async Task A_challenge_with_nul_and_non_ascii_characters_is_accepted_and_the_payload_is_still_rejected()
    {
        // The challenge is trimmed and compared, not parsed: NUL and CJK characters survive intact. The
        // transfer therefore completes, and the refusal comes from the decoder, which is the layer that
        // owns "is this a sync envelope". Zero writes is still the property under test.
        byte[] wire = Concat(ChallengeFrame("abc\0测试"), ChunkFrame(3, 0, "abc"u8.ToArray()));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.EnvelopeInvalid);
        Assert.Equal(2, hostile.Result.FrameCount);
    }

    [Fact]
    public async Task A_challenge_longer_than_the_sending_ceiling_is_accepted_by_the_receiver()
    {
        // Characterization, and an asymmetry worth naming: SourceSyncTransfer.SendAsync refuses to *send* a
        // challenge longer than SourceSyncTransfer.MaximumChallengeBytes (512), but ReceiveAsync has no such
        // ceiling - the only limit is the 8192-byte frame payload. A 513-byte challenge is therefore accepted
        // here and the round is refused later by the decoder. Tightening the receiver would change what an
        // existing peer may announce, so it is left as documented behaviour (see W6-31 section 6).
        string longChallenge = new('n', SourceSyncTransfer.MaximumChallengeBytes + 1);
        byte[] wire = Concat(ChallengeFrame(longChallenge), ChunkFrame(3, 0, "abc"u8.ToArray()));

        Case hostile = await RunRawAsync(wire);

        AssertRefused(hostile, SourceSyncTransferReasons.EnvelopeInvalid);
    }

    // ---- 3. envelope-size boundaries ------------------------------------------------------------

    [Fact]
    public async Task An_envelope_of_exactly_eight_mebibytes_is_buffered_and_then_rejected_as_envelope_invalid()
    {
        // The ceiling is inclusive, so exactly 8 MiB is a legal transfer: the frames arrive, the buffer is
        // 8 MiB, and the decoder refuses the contents. What must not happen is an out-of-memory, a hang, or a
        // partial write.
        byte[] payload = new byte[SourceSyncTransfer.MaximumEnvelopeBytes];
        Case hostile = await RunRawAsync(Chunked(payload));

        Assert.False(hostile.Result.Succeeded, "eight mebibytes of zeros was imported");
        Assert.Contains(hostile.Result.Reason!, KnownCodes);
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, hostile.Result.Reason);
        Assert.Equal(0, hostile.Writes);
        AssertNoStoreFiles(hostile.Directory);
        Assert.True(hostile.Elapsed < CaseBudget, "the case took " + Seconds(hostile.Elapsed));
    }

    [Fact]
    public async Task An_envelope_one_byte_over_the_ceiling_is_payload_too_large()
    {
        Case hostile = await RunRawAsync(ChunkFrame(SourceSyncTransfer.MaximumEnvelopeBytes + 1, 0, [1]));

        AssertRefused(hostile, SourceSyncTransferReasons.PayloadTooLarge);
    }

    // ---- 4. authentication frames (W6-24) against a hostile peer --------------------------------

    [Fact]
    public async Task A_missing_tag_frame_with_authentication_on_is_auth_missing()
    {
        byte[] wire = Concat(ChallengeFrame("nonce-h1"), ChunkFrame(8, 0, "not-json"u8.ToArray()));

        Case hostile = await RunRawAsync(wire, options: Receiver());

        AssertRefused(hostile, SourceSyncTransferAuthReasons.AuthMissing, "no tag frame");
    }

    [Fact]
    public async Task A_tag_frame_one_byte_short_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChallengeFrame("nonce-h2"),
            ChunkFrame(8, 0, "not-json"u8.ToArray()),
            Frame(0x03, new byte[SourceSyncTransferAuthenticator.TagBytes - 1]));

        Case hostile = await RunRawAsync(wire, options: Receiver());

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "an HMAC-SHA256 tag is 32 bytes");
    }

    [Fact]
    public async Task A_tag_frame_one_byte_long_is_frame_invalid()
    {
        byte[] wire = Concat(
            ChallengeFrame("nonce-h3"),
            ChunkFrame(8, 0, "not-json"u8.ToArray()),
            Frame(0x03, new byte[SourceSyncTransferAuthenticator.TagBytes + 1]));

        Case hostile = await RunRawAsync(wire, options: Receiver());

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "an HMAC-SHA256 tag is 32 bytes");
    }

    [Fact]
    public async Task A_tag_frame_before_the_first_chunk_is_frame_invalid()
    {
        byte[] wire = Concat(ChallengeFrame("nonce-h4"), Frame(0x03, new byte[SourceSyncTransferAuthenticator.TagBytes]));

        Case hostile = await RunRawAsync(wire, options: Receiver());

        AssertRefused(hostile, SourceSyncTransferReasons.FrameInvalid, "The tag frame arrived before the first chunk.");
    }

    [Fact]
    public async Task A_valid_tag_ends_the_transfer_so_a_trailing_chunk_is_never_read()
    {
        // The tag is the last frame the authenticated reader accepts; it returns as soon as it verifies.
        // A chunk smuggled after it is therefore not read at all - the envelope it trails is judged on its
        // own bytes, which here do not decode.
        byte[] payload = "not-json"u8.ToArray();
        byte[] tag = SourceSyncTransferAuthenticator.CreateTag(Secret, "nonce-h5", payload);
        byte[] wire = Concat(
            ChallengeFrame("nonce-h5"),
            ChunkFrame(payload.Length, 0, payload),
            Frame(0x03, tag),
            ChunkFrame(16, 0, new byte[16]));

        Case hostile = await RunRawAsync(wire, options: Receiver());

        Assert.False(hostile.Result.Succeeded, "the authenticated round imported something");
        Assert.Equal(SourceSyncTransferReasons.EnvelopeInvalid, hostile.Result.Reason);
        Assert.Equal(3, hostile.Result.FrameCount);
        Assert.Equal(0, hostile.Writes);
        AssertNoStoreFiles(hostile.Directory);
        Assert.True(hostile.Elapsed < CaseBudget, "the case took " + Seconds(hostile.Elapsed));
    }

    // ---- 5. transport-level hostility -----------------------------------------------------------

    [Fact]
    public async Task A_peer_that_connects_and_closes_without_a_byte_is_frame_truncated()
    {
        Case hostile = await RunAsync((client, cancellationToken) =>
        {
            client.Close();
            return Task.CompletedTask;
        });

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated, "closed before sending a frame");
    }

    [Fact]
    public async Task A_peer_that_resets_the_connection_is_read_as_a_close_and_writes_nothing()
    {
        // Measured on Windows loopback: a peer that writes two bytes and then resets (SO_LINGER 0) leaves the
        // receiver's next read returning 0, exactly as a clean close would, so the code is frame-truncated
        // rather than io-failed. A reset is therefore not distinguishable at this layer - and it does not
        // need to be, because the outcome is identical: nothing is written.
        //
        // SourceSyncTransferListener.ReceiveOnceAsync still maps an IOException from the plaintext probe to
        // io-failed. That guard is hardening rather than a reproduced crash: the probe is the only read in
        // the method that is not already inside SourceSyncTransfer's own catch list, and a stack that reports
        // a reset as an I/O error must produce a code, not an exception that escapes a serve loop.
        Case hostile = await RunAsync(async (client, cancellationToken) =>
        {
            await client.GetStream().WriteAsync(new byte[2], cancellationToken);
            client.Client.LingerState = new LingerOption(enable: true, seconds: 0);
            client.Close();
        });

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated);
    }

    [Fact]
    public async Task A_peer_that_shuts_down_mid_frame_is_frame_truncated()
    {
        // One complete chunk frame that says "100 bytes start here" and then carries ten, followed by a FIN.
        // The frame boundary is honest, the envelope is not, and the framing layer says how far it got
        // instead of pretending the envelope was malformed.
        byte[] partial = ChunkFrame(100, 0, new byte[10]);

        Case hostile = await RunAsync(async (client, cancellationToken) =>
        {
            await client.GetStream().WriteAsync(partial, cancellationToken);
            client.Client.Shutdown(SocketShutdown.Send);
            client.Close();
        });

        AssertRefused(hostile, SourceSyncTransferReasons.FrameTruncated, "stopped after 10 of 100 bytes");
    }

    [Fact]
    public async Task A_slow_drip_peer_runs_out_of_the_receive_budget()
    {
        // A headed frame, then one byte every 150 ms. The receiver must time out on its budget instead of
        // waiting for a frame that a hostile peer can stretch out indefinitely.
        byte[] body = new byte[8 + 100];
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(0, 4), 100);
        byte[] frame = Frame(0x02, body);
        byte[] head = frame.AsSpan(0, 5).ToArray();
        byte[] rest = frame.AsSpan(5).ToArray();

        Case hostile = await RunAsync(
            async (client, cancellationToken) =>
            {
                NetworkStream stream = client.GetStream();
                await stream.WriteAsync(head, cancellationToken);
                foreach (byte value in rest)
                {
                    await Task.Delay(150, cancellationToken);
                    await stream.WriteAsync(new[] { value }, cancellationToken);
                }
            },
            receiveTimeout: TimeSpan.FromSeconds(1.5));

        AssertRefused(hostile, SourceSyncTransferReasons.TransferTimeout, "did not finish within");
        Assert.True(hostile.Elapsed >= TimeSpan.FromSeconds(1.2), "the timeout fired after only " + Seconds(hostile.Elapsed));
    }

    // ---- 6. one listener, eight bad peers and one good one ---------------------------------------

    [Fact]
    public async Task Eight_hostile_peers_and_one_good_peer_on_one_listener_import_only_the_good_one()
    {
        string directory = NewDirectory();
        FileLocalSourceStore sources = new(directory);
        FileSourceSyncBaselineStore baselines = new(directory);
        SourceSyncInbox inbox = new(sources, baselines);
        int writes = 0;
        SourceSyncRecordWriter writer = (item, record) =>
        {
            SourceSyncWriteOutcome outcome = WriteOnce(sources, item, record);
            if (outcome.Applied) writes++;
            return Task.FromResult(outcome);
        };

        SourceSyncTransferStartResult started = SourceSyncTransferListener.Start(SourceSyncTransferAddress.DefaultAddress, 0);
        Assert.True(started.Started, "the loopback listener did not start: " + started.Detail);
        await using SourceSyncTransferListener listener = started.Listener!;

        // Nine accepts are armed before any peer connects, so the order the kernel hands them out does not
        // matter: each connection is served by its own receive call.
        Task<SourceSyncInboxResult>[] receiving = new Task<SourceSyncInboxResult>[9];
        for (int index = 0; index < receiving.Length; index++)
        {
            receiving[index] = listener.ReceiveAndImportAsync(inbox, writer, null, null, ReceiveBudget);
        }

        byte[][] hostileWires =
        [
            LengthPrefix(0x7FFFFFFF),
            LengthPrefix(0),
            ChunkFrame(16, 8, [1, 2, 3, 4]),
            ChunkFrame(SourceSyncTransfer.MaximumEnvelopeBytes + 1, 0, [1]),
            Frame(0x7F, [1, 2]),
            Concat(ChallengeFrame("late"), ChunkFrame(16, 0, new byte[8])),
            Concat(ChunkFrame(16, 0, new byte[8]), ChunkFrame(16, 8, new byte[8])),
            Frame(0x03, new byte[SourceSyncTransferAuthenticator.TagBytes]),
        ];

        byte[] good = await WireAsync(GoodPayload());
        List<Task> senders = [];
        foreach (byte[] wire in hostileWires)
        {
            senders.Add(SendRawAsync(listener.Port, wire));
        }

        senders.Add(SendRawAsync(listener.Port, good));

        SourceSyncInboxResult[] results = await Task.WhenAll(receiving).WaitAsync(CaseBudget);
        foreach (Task sender in senders)
        {
            await ObserveQuietlyAsync(sender);
        }

        SourceSyncInboxResult imported = Assert.Single(results, result => result.Succeeded);
        Assert.Equal(2, imported.Report!.Applied);
        Assert.Equal(2, writes);
        Assert.Equal(2, sources.GetAll().Count);

        foreach (SourceSyncInboxResult refused in results.Where(result => !result.Succeeded))
        {
            _output.WriteLine("refused -> " + refused.Reason);
            Assert.Contains(refused.Reason!, KnownCodes);
        }

        Assert.True(File.Exists(Path.Combine(directory, "sync-sources.json")), "the good peer's sources were not written");
        Assert.True(File.Exists(Path.Combine(directory, "sync-baseline.json")), "the good peer's baseline was not written");
    }

    // ---- 7. the fixed-seed fuzz corpus ----------------------------------------------------------

    [Fact]
    public async Task Thirty_two_fixed_seed_random_streams_are_all_refused_with_a_stable_code()
    {
        // A fixed seed makes the corpus reproducible: the same 32 streams run every time, so a code that
        // changes shape is a diff rather than a coincidence. Lengths stay in 1..600 bytes, which is enough
        // to reach every length-prefix branch without a slow case.
        Random random = new(20261024);
        for (int index = 0; index < 32; index++)
        {
            int length = random.Next(1, 601);
            byte[] wire = new byte[length];
            random.NextBytes(wire);

            Case hostile = await RunRawAsync(wire);
            _output.WriteLine("stream " + index.ToString(CultureInfo.InvariantCulture) + " (" + length.ToString(CultureInfo.InvariantCulture)
                + " bytes) -> " + (hostile.Result.Reason ?? "accepted"));
            Assert.False(hostile.Result.Succeeded, "random stream " + index.ToString(CultureInfo.InvariantCulture) + " was imported");
            Assert.Contains(hostile.Result.Reason!, KnownCodes);
            Assert.Equal(0, hostile.Writes);
            AssertNoStoreFiles(hostile.Directory);
            Assert.True(hostile.Elapsed < CaseBudget, "random stream " + index.ToString(CultureInfo.InvariantCulture) + " took " + Seconds(hostile.Elapsed));
        }
    }

    // ---- fixture --------------------------------------------------------------------------------

    private sealed record Case(SourceSyncInboxResult Result, TimeSpan Elapsed, string Directory, int Writes);

    /// <summary>One receiving loopback endpoint plus one hostile peer, timed.</summary>
    private async Task<Case> RunAsync(
        Func<TcpClient, CancellationToken, Task> peer,
        string? expectedChallenge = null,
        SourceSyncTransferOptions? options = null,
        TimeSpan? receiveTimeout = null)
    {
        string directory = NewDirectory();

        FileLocalSourceStore sources = new(directory);
        FileSourceSyncBaselineStore baselines = new(directory);
        SourceSyncInbox inbox = new(sources, baselines);
        int writes = 0;
        SourceSyncRecordWriter writer = (item, record) =>
        {
            SourceSyncWriteOutcome outcome = WriteOnce(sources, item, record);
            if (outcome.Applied) writes++;
            return Task.FromResult(outcome);
        };

        SourceSyncTransferStartResult started = SourceSyncTransferListener.Start(SourceSyncTransferAddress.DefaultAddress, 0);
        Assert.True(started.Started, "the loopback listener did not start: " + started.Detail);
        await using SourceSyncTransferListener listener = started.Listener!;

        Task<SourceSyncInboxResult> receiving = listener.ReceiveAndImportAsync(
            inbox, writer, expectedChallenge, null, receiveTimeout ?? ReceiveBudget, CancellationToken.None, options);

        using CancellationTokenSource peerAbort = new();
        using TcpClient client = new();
        Stopwatch elapsed = Stopwatch.StartNew();
        await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        Task peerTask = peer(client, peerAbort.Token);

        SourceSyncInboxResult result;
        try
        {
            result = await receiving.WaitAsync(CaseBudget);
        }
        finally
        {
            elapsed.Stop();
            peerAbort.Cancel();
        }

        await ObserveQuietlyAsync(peerTask);
        return new Case(result, elapsed.Elapsed, directory, writes);
    }

    /// <summary>Writes a hand-built byte stream, then closes, which is what a hostile peer does.</summary>
    private Task<Case> RunRawAsync(
        byte[] wire,
        string? expectedChallenge = null,
        SourceSyncTransferOptions? options = null,
        TimeSpan? receiveTimeout = null)
        => RunAsync(
            async (client, cancellationToken) =>
            {
                await client.GetStream().WriteAsync(wire, cancellationToken);
                client.Close();
            },
            expectedChallenge,
            options,
            receiveTimeout);

    private async Task SendRawAsync(int port, byte[] wire)
    {
        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, port);
        await client.GetStream().WriteAsync(wire);
        client.Close();
    }

    /// <summary>
    /// The fixture's writer: a contract record becomes a local record, and nothing else does. A null record is a
    /// tombstone, which this fixture refuses rather than deletes, because a refusal is visible in the report.
    /// </summary>
    private static SourceSyncWriteOutcome WriteOnce(ILocalSourceStore store, SourceSyncApprovalItem item, SourceRecord? record)
    {
        if (record is null)
        {
            return SourceSyncWriteOutcome.Refused("this fixture never writes a deletion");
        }

        LocalSourceRecord local = new()
        {
            ServerId = string.IsNullOrWhiteSpace(item.ServerId) ? "srv-" + item.SyncSourceId : item.ServerId.Trim(),
            SyncSourceId = item.SyncSourceId,
            Type = ServerType.Emby,
            SourceTypeRaw = record.SourceType.Raw,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        };

        store.Save(local);
        return SourceSyncWriteOutcome.Written("written by the hostile-input fixture");
    }

    /// <summary>The receiver side of an authenticated transfer: a secret and a nonce window of its own.</summary>
    private static SourceSyncTransferOptions Receiver()
        => SourceSyncTransferOptions.WithSecret(Secret, deviceId: "hostile-receiver");

    private static void AssertRefused(Case hostile, string expectedReason, string? detailContains = null)
    {
        Assert.False(hostile.Result.Succeeded, "a hostile peer got its envelope imported");
        Assert.Contains(hostile.Result.Reason!, KnownCodes);
        Assert.Equal(expectedReason, hostile.Result.Reason);
        if (detailContains is not null)
        {
            Assert.Contains(detailContains, hostile.Result.Detail ?? string.Empty, StringComparison.Ordinal);
        }

        Assert.Equal(0, hostile.Writes);
        AssertNoStoreFiles(hostile.Directory);
        Assert.True(hostile.Elapsed < CaseBudget, "the case took " + Seconds(hostile.Elapsed) + " (budget " + Seconds(CaseBudget) + ")");
    }

    /// <summary>Both store files are absent: the round did not write, it did not write-and-roll-back.</summary>
    private static void AssertNoStoreFiles(string directory)
    {
        Assert.False(File.Exists(Path.Combine(directory, "sync-sources.json")), "a refused round wrote sync-sources.json");
        Assert.False(File.Exists(Path.Combine(directory, "sync-baseline.json")), "a refused round wrote sync-baseline.json");
    }

    private static async Task ObserveQuietlyAsync(Task task)
    {
        try
        {
            await task.WaitAsync(TimeSpan.FromSeconds(2));
        }
        catch (Exception error) when (error is IOException
            or SocketException
            or ObjectDisposedException
            or InvalidOperationException
            or OperationCanceledException
            or TimeoutException)
        {
            // The peer's own failure is the point of the case; the receiver's result is the evidence.
        }
    }

    private string NewDirectory()
    {
        string directory = Path.Combine(Path.GetTempPath(), "mh-w6-31-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        _directories.Add(directory);
        return directory;
    }

    private static string Seconds(TimeSpan elapsed) => elapsed.TotalSeconds.ToString("0.00", CultureInfo.InvariantCulture) + " s";

    // ---- wire helpers ---------------------------------------------------------------------------

    private static byte[] LengthPrefix(int length)
    {
        byte[] prefix = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(prefix, length);
        return prefix;
    }

    private static byte[] Frame(byte kind, params byte[] body)
    {
        byte[] frame = new byte[4 + 1 + body.Length];
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(0, 4), 1 + body.Length);
        frame[4] = kind;
        body.CopyTo(frame, 5);
        return frame;
    }

    private static byte[] ChunkFrame(int total, int offset, params byte[] payload)
    {
        byte[] body = new byte[8 + payload.Length];
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(0, 4), total);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(4, 4), offset);
        payload.CopyTo(body, 8);
        return Frame(0x02, body);
    }

    private static byte[] ChallengeFrame(string text) => Frame(0x01, Encoding.UTF8.GetBytes(text));

    private static byte[] Concat(params byte[][] parts) => parts.SelectMany(part => part).ToArray();

    /// <summary>Splits a payload into the chunk frames a real sender would write.</summary>
    private static byte[] Chunked(byte[] payload)
    {
        List<byte[]> frames = [];
        for (int offset = 0; offset < payload.Length; offset += SourceSyncTransfer.ChunkPayloadBytes)
        {
            int take = Math.Min(SourceSyncTransfer.ChunkPayloadBytes, payload.Length - offset);
            frames.Add(ChunkFrame(payload.Length, offset, payload.AsSpan(offset, take).ToArray()));
        }

        return Concat(frames.ToArray());
    }

    /// <summary>A valid envelope for two sources, as the real sender would serialize it.</summary>
    private static byte[] GoodPayload()
    {
        InMemoryLocalSourceStore store = new();
        store.Save(Source("living-room", "Living Room Emby"));
        store.Save(Source("gate", "WebDAV Gate"));
        SourceSyncPeerRound round = new(PeerDeviceId, store);
        return Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(round.Export()));
    }

    private static async Task<byte[]> WireAsync(byte[] payload)
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, payload);
        Assert.True(sent.Succeeded, "the fixture could not build a valid wire stream: " + sent.Reason);
        return stream.ToArray();
    }

    private static LocalSourceRecord Source(string syncSourceId, string displayName, long revision = 1) => new()
    {
        ServerId = "srv-" + syncSourceId,
        SyncSourceId = syncSourceId,
        Type = ServerType.Emby,
        SourceTypeRaw = SyncSourceTypes.Emby,
        Url = EmbyUrl,
        DisplayName = displayName,
        RecordRevision = revision,
    };

    /// <inheritdoc />
    public void Dispose()
    {
        foreach (string directory in _directories)
        {
            try
            {
                if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
            }
            catch (IOException)
            {
                // A leftover temporary directory is not worth failing a security test over.
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }
}

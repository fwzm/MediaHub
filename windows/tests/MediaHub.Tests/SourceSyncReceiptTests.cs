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
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-37: the receipt frame (0x04) - the one byte a receiver sends back after it decoded the envelope.
/// Everything here runs in-process over 127.0.0.1; no test binds a non-loopback address, opens a window
/// or writes into the user's real settings folder.
/// </summary>
public sealed class SourceSyncReceiptTests
{
    private const string FrozenW616Hex =
        "0000000e016e6f6e63652d6162632d313233000000490200000040000000000726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8";

    private const string Nonce = "nonce-abc-123";
    private static readonly byte[] Secret = Encoding.UTF8.GetBytes("correct horse battery staple");
    private static readonly byte[] WrongSecret = Encoding.UTF8.GetBytes("a different shared secret");

    // ---- 1. the byte itself ---------------------------------------------------------------

    [Fact]
    public async Task The_receipt_is_one_bare_byte_of_kind_0x04()
    {
        using MemoryStream stream = new();

        SourceSyncReceiptResult written = await SourceSyncReceipt.WriteAsync(stream);

        Assert.True(written.Confirmed, written.Detail);
        Assert.Equal(1, written.BytesRead);
        Assert.Equal(1L, stream.Length);
        Assert.Equal(0x04, stream.ToArray()[0]);
    }

    [Fact]
    public void The_receipt_kind_is_distinct_from_the_other_three_frame_kinds()
    {
        Assert.Equal(1, SourceSyncReceipt.ReceiptFrameBytes);
        Assert.NotEqual(SourceSyncTransfer.ChallengeFrameKind, SourceSyncReceipt.ReceiptFrameKind);
        Assert.NotEqual(SourceSyncTransfer.ChunkFrameKind, SourceSyncReceipt.ReceiptFrameKind);
        Assert.NotEqual(SourceSyncTransferAuthenticator.TagFrameKind, SourceSyncReceipt.ReceiptFrameKind);
    }

    [Fact]
    public async Task A_read_only_stream_cannot_write_a_receipt()
    {
        using MemoryStream stream = new(new byte[] { 1, 2, 3 }, writable: false);

        SourceSyncReceiptResult written = await SourceSyncReceipt.WriteAsync(stream);

        Assert.False(written.Confirmed);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptNotEnabled, written.Reason);
        Assert.Contains("read-only", written.Detail, StringComparison.Ordinal);
    }

    // ---- 2. awaiting the peer's byte ------------------------------------------------------

    [Fact]
    public async Task An_arriving_receipt_byte_confirms()
    {
        using MemoryStream stream = new(new byte[] { 0x04 });

        SourceSyncReceiptResult read = await SourceSyncReceipt.AwaitAsync(stream);

        Assert.True(read.Confirmed, read.Detail);
        Assert.Equal(1, read.BytesRead);
        Assert.Null(read.Reason);
    }

    [Fact]
    public async Task A_close_without_a_receipt_byte_is_receipt_missing()
    {
        using MemoryStream stream = new();

        SourceSyncReceiptResult read = await SourceSyncReceipt.AwaitAsync(stream);

        Assert.False(read.Confirmed);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, read.Reason);
        Assert.Contains("closed the connection without sending a receipt byte", read.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_wrong_byte_is_receipt_invalid()
    {
        using MemoryStream stream = new(new byte[] { 0x05 });

        SourceSyncReceiptResult read = await SourceSyncReceipt.AwaitAsync(stream);

        Assert.False(read.Confirmed);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptInvalid, read.Reason);
        Assert.Contains("0x05 instead of the receipt kind 0x04", read.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_write_only_stream_cannot_read_a_receipt()
    {
        using WriteOnlyStream stream = new();

        SourceSyncReceiptResult read = await SourceSyncReceipt.AwaitAsync(stream);

        Assert.False(read.Confirmed);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptNotEnabled, read.Reason);
        Assert.Contains("write-only", read.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_peer_that_holds_the_connection_is_receipt_timeout()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            await ReadExactlyAsync(stream, ExpectedWireBytes(), token);
            await Task.Delay(TimeSpan.FromSeconds(5), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            peer.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            receipt: SourceSyncReceiptOptions.Awaiting(TimeSpan.FromMilliseconds(400)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptTimeout, sent.Reason);
        Assert.Contains("kept the connection open for 400 ms", sent.Detail, StringComparison.Ordinal);
        Assert.False(sent.ReceiptConfirmed);
    }

    // ---- 3. the sending half --------------------------------------------------------------

    [Fact]
    public async Task Without_the_flag_a_send_reports_no_receipt_and_keeps_the_frozen_w6_16_bytes()
    {
        byte[] seen = new byte[ExpectedWireBytes()];
        await using RawPeer peer = new(async (stream, token) =>
        {
            await ReadExactlyAsync(stream, seen, token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10));

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Null(sent.ReceiptConfirmed);
        Assert.Equal(2, sent.FrameCount);
        // The sender returns once its frames are flushed, so the peer has to finish reading before the bytes are compared.
        await peer.Completion.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(FrozenW616Hex, Convert.ToHexString(seen).ToLowerInvariant());
    }

    [Fact]
    public async Task The_receipt_adds_no_byte_to_the_senders_output()
    {
        byte[] seen = new byte[ExpectedWireBytes()];
        await using RawPeer peer = new(async (stream, token) =>
        {
            await ReadExactlyAsync(stream, seen, token);
            await stream.WriteAsync(new byte[] { 0x04 }, token);
            await stream.FlushAsync(token);
            await Task.Delay(TimeSpan.FromMilliseconds(100), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            peer.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            receipt: SourceSyncReceiptOptions.Awaiting());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.ReceiptConfirmed, sent.Detail);
        Assert.Equal(2, sent.FrameCount);
        // The receipt travels back, so the sender's own bytes are still exactly W6-16's.
        Assert.Equal(FrozenW616Hex, Convert.ToHexString(seen).ToLowerInvariant());
    }

    [Fact]
    public async Task A_peer_that_closes_without_a_receipt_still_reports_the_written_envelope()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            // Read the whole wire, then close without a receipt byte: understood, but not acknowledged.
            await ReadExactlyAsync(stream, ExpectedWireBytes(), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            peer.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            receipt: SourceSyncReceiptOptions.Awaiting(TimeSpan.FromSeconds(2)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, sent.Reason);
        // The frames did leave this machine, so the failure still reports what was written.
        Assert.Equal(64, sent.Envelope.Length);
        Assert.Equal(2, sent.FrameCount);
    }

    [Fact]
    public async Task A_receipt_followed_by_trailing_bytes_still_confirms()
    {
        await using RawPeer peer = new(async (stream, token) =>
        {
            await ReadExactlyAsync(stream, ExpectedWireBytes(), token);
            await stream.WriteAsync(new byte[] { 0x04, 0x02 }, token);
            await stream.FlushAsync(token);
            await Task.Delay(TimeSpan.FromMilliseconds(100), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            peer.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            receipt: SourceSyncReceiptOptions.Awaiting());

        // Exactly one byte is read, so a chatty peer after the receipt cannot turn a confirmation into a failure.
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.ReceiptConfirmed, sent.Detail);
    }

    [Fact]
    public async Task A_real_receiver_confirms_and_the_sender_reports_it()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            listener.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            receipt: SourceSyncReceiptOptions.Awaiting());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.ReceiptConfirmed, sent.Detail);
        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Detail);
        Assert.True(received.ReceiptWritten, received.Detail);
    }

    // ---- 4. the receiving half ------------------------------------------------------------

    [Fact]
    public async Task A_receiver_writes_the_receipt_only_after_the_envelope_decoded()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: payload => payload.Length == 3);

        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = client.GetStream();
        await stream.WriteAsync(ChunkFrame(3, 0, Encoding.UTF8.GetBytes("abc")));
        await stream.FlushAsync();

        byte[] answer = await ReadSomeAsync(stream, 1);
        SourceSyncTransferResult received = await receiving;

        Assert.True(received.Succeeded, received.Detail);
        Assert.True(received.ReceiptWritten, received.Detail);
        Assert.Equal(new byte[] { 0x04 }, answer);
    }

    [Fact]
    public async Task A_receiver_whose_gate_refuses_stays_silent()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => false);

        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = client.GetStream();
        await stream.WriteAsync(ChunkFrame(3, 0, Encoding.UTF8.GetBytes("abc")));
        await stream.FlushAsync();

        byte[] answer = await ReadSomeAsync(stream, 1);
        SourceSyncTransferResult received = await receiving;

        // The frames were fine, so the receive succeeds - but nothing acknowledged them.
        Assert.True(received.Succeeded, received.Detail);
        Assert.False(received.ReceiptWritten);
        Assert.Empty(answer);
    }

    [Fact]
    public async Task A_receiver_that_never_got_the_whole_envelope_writes_nothing()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = client.GetStream();
        // The chunk announces 100 bytes and carries 10, then the socket half-closes.
        await stream.WriteAsync(ChunkFrame(100, 0, new byte[10]));
        await stream.FlushAsync();
        client.Client.Shutdown(SocketShutdown.Send);

        byte[] answer = await ReadSomeAsync(stream, 1);
        SourceSyncTransferResult received = await receiving;

        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.FrameTruncated, received.Reason);
        Assert.Null(received.ReceiptWritten);
        Assert.Empty(answer);
    }

    [Fact]
    public async Task The_receipt_travels_after_every_frame_of_a_multi_chunk_envelope()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        byte[] payload = Payload(9000);
        int chunk = SourceSyncTransfer.ChunkPayloadBytes;
        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = client.GetStream();
        await stream.WriteAsync(ChallengeFrame(Nonce));
        await stream.WriteAsync(ChunkFrame(payload.Length, 0, payload[..chunk]));
        await stream.WriteAsync(ChunkFrame(payload.Length, chunk, payload[chunk..]));
        await stream.FlushAsync();

        byte[] answer = await ReadSomeAsync(stream, 1);
        SourceSyncTransferResult received = await receiving;

        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(3, received.FrameCount);
        Assert.Equal(new byte[] { 0x04 }, answer);
    }

    // ---- 5. composed with TLS and with the W6-24 tag --------------------------------------

    [Fact]
    public async Task Tls_and_the_receipt_work_together()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            listener.Port,
            Payload(),
            Nonce,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint),
            receipt: SourceSyncReceiptOptions.Awaiting());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.ReceiptConfirmed, sent.Detail);
        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Detail);
        Assert.True(received.ReceiptWritten, received.Detail);
    }

    [Fact]
    public async Task Authentication_and_the_receipt_work_together()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: SourceSyncTransferOptions.WithSecret(Secret, deviceId: "receipt-receiver"),
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            listener.Port,
            Payload(),
            SourceSyncTransferAuthenticator.CreateNonce(),
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: SourceSyncTransferOptions.WithSecret(Secret, deviceId: "receipt-sender"),
            receipt: SourceSyncReceiptOptions.Awaiting());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.ReceiptConfirmed, sent.Detail);
        Assert.Equal(3, sent.FrameCount);
        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Detail);
        Assert.True(received.ReceiptWritten, received.Detail);
    }

    [Fact]
    public async Task A_mismatched_secret_is_auth_mismatch_on_the_receiver_and_no_receipt_on_the_sender()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start();
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: SourceSyncTransferOptions.WithSecret(Secret, deviceId: "receipt-receiver"),
            receipt: SourceSyncReceiptOptions.Sending(),
            receiptGate: _ => true);

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null,
            listener.Port,
            Payload(),
            SourceSyncTransferAuthenticator.CreateNonce(),
            TimeSpan.FromSeconds(10),
            CancellationToken.None,
            options: SourceSyncTransferOptions.WithSecret(WrongSecret, deviceId: "receipt-sender"),
            receipt: SourceSyncReceiptOptions.Awaiting(TimeSpan.FromSeconds(2)));

        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, received.Reason);
        Assert.Null(received.ReceiptWritten);

        // A receiver that refuses the tag writes no receipt, so the sender learns about it as silence.
        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncReceiptReasons.ReceiptMissing, sent.Reason);
    }

    // ---- 6. the code set and the options --------------------------------------------------

    [Fact]
    public void The_receipt_codes_are_their_own_closed_set()
    {
        Assert.Equal(4, SourceSyncReceiptReasons.All.Count);
        Assert.Equal(4, SourceSyncReceiptReasons.All.Distinct(StringComparer.Ordinal).Count());
        Assert.Equal(
            new[]
            {
                SourceSyncReceiptReasons.ReceiptMissing,
                SourceSyncReceiptReasons.ReceiptTimeout,
                SourceSyncReceiptReasons.ReceiptInvalid,
                SourceSyncReceiptReasons.ReceiptNotEnabled,
            },
            SourceSyncReceiptReasons.All.ToArray());

        foreach (string code in SourceSyncReceiptReasons.All)
        {
            Assert.DoesNotContain(code, SourceSyncTransferReasons.All);
            Assert.DoesNotContain(code, SourceSyncTransferAuthReasons.All);
            Assert.DoesNotContain(code, SourceSyncTlsReasons.All);
            Assert.DoesNotContain(code, SourceSyncDeliveryReasons.All);
        }
    }

    [Fact]
    public void The_options_to_string_carries_no_secret()
    {
        string text = SourceSyncReceiptOptions.Awaiting().ToString();

        Assert.Contains("Enabled = true", text, StringComparison.Ordinal);
        Assert.Contains("BudgetMs = 2000", text, StringComparison.Ordinal);
        Assert.DoesNotContain("secret", text, StringComparison.OrdinalIgnoreCase);
        Assert.False(SourceSyncReceiptOptions.Off.Enabled);
        Assert.True(SourceSyncReceiptOptions.Sending().Enabled);
        Assert.True(SourceSyncReceiptOptions.Awaiting().Enabled);
        Assert.Equal(TimeSpan.FromMilliseconds(2000), SourceSyncReceiptOptions.Awaiting().Budget);
        Assert.Equal(TimeSpan.FromMilliseconds(400), SourceSyncReceiptOptions.Awaiting(TimeSpan.FromMilliseconds(400)).Budget);
    }

    // ---- helpers --------------------------------------------------------------------------

    private static int ExpectedWireBytes()
        => 4 + 1 + Encoding.UTF8.GetByteCount(Nonce) + 4 + 1 + 8 + 64;

    private static byte[] Payload(int length = 64)
    {
        byte[] payload = new byte[length];
        for (int i = 0; i < payload.Length; i++)
        {
            payload[i] = (byte)((i * 31) + 7);
        }

        return payload;
    }

    private static byte[] Frame(byte kind, params byte[] body)
    {
        byte[] frame = new byte[4 + 1 + body.Length];
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(0, 4), 1 + body.Length);
        frame[4] = kind;
        body.CopyTo(frame, 5);
        return frame;
    }

    private static byte[] ChallengeFrame(string nonce)
        => Frame(SourceSyncTransfer.ChallengeFrameKind, Encoding.UTF8.GetBytes(nonce));

    private static byte[] ChunkFrame(int total, int offset, byte[] payload)
    {
        byte[] body = new byte[8 + payload.Length];
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(0, 4), total);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(4, 4), offset);
        payload.CopyTo(body, 8);
        return Frame(SourceSyncTransfer.ChunkFrameKind, body);
    }

    private static async Task<byte[]> ReadSomeAsync(Stream stream, int count)
    {
        byte[] buffer = new byte[count];
        int read = 0;
        using CancellationTokenSource cts = new(TimeSpan.FromSeconds(5));
        while (read < count)
        {
            int got = await stream.ReadAsync(buffer.AsMemory(read, count - read), cts.Token);
            if (got <= 0)
            {
                break;
            }

            read += got;
        }

        return buffer[..read];
    }

    private static async Task ReadExactlyAsync(Stream stream, int count, CancellationToken token)
    {
        byte[] buffer = new byte[count];
        await ReadExactlyAsync(stream, buffer, token);
    }

    private static async Task ReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken token)
    {
        int read = 0;
        while (read < buffer.Length)
        {
            int got = await stream.ReadAsync(buffer.AsMemory(read, buffer.Length - read), token);
            if (got <= 0)
            {
                throw new IOException("the peer closed after " + read.ToString(CultureInfo.InvariantCulture) + " bytes.");
            }

            read += got;
        }
    }

    private static async Task ReadToEndAsync(Stream stream, CancellationToken token)
    {
        byte[] buffer = new byte[512];
        while (await stream.ReadAsync(buffer.AsMemory(), token) > 0)
        {
        }
    }

    /// <summary>A one-connection loopback peer whose behaviour each test spells out.</summary>
    private sealed class RawPeer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _cts = new();
        private readonly Task _accepted;

        public RawPeer(Func<Stream, CancellationToken, Task> handler)
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            _accepted = AcceptAsync(handler);
        }

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public Task Completion => _accepted;

        public async ValueTask DisposeAsync()
        {
            _cts.Cancel();
            _listener.Stop();
            try
            {
                await _accepted.WaitAsync(TimeSpan.FromSeconds(5));
            }
            catch (Exception)
            {
                // A handler that ends by closing the socket is the normal path; nothing to report here.
            }

            _cts.Dispose();
        }

        private async Task AcceptAsync(Func<Stream, CancellationToken, Task> handler)
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync(_cts.Token);
                using NetworkStream stream = client.GetStream();
                await handler(stream, _cts.Token);
            }
            catch (Exception)
            {
                // The assertions already ran against what the handler did before it stopped.
            }
        }
    }

    /// <summary>A stream that can only be written, for the "this end cannot read a receipt" path.</summary>
    private sealed class WriteOnlyStream : Stream
    {
        public override bool CanRead => false;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => 0;

        public override long Position
        {
            get => 0;
            set => throw new NotSupportedException();
        }

        public override void Flush()
        {
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count)
        {
        }
    }
}

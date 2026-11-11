using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Net.Security;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-30: the delivery confirmation a send can ask for. Everything here runs in-process over 127.0.0.1;
/// no test binds a non-loopback address, opens a window or writes into the user's real settings folder.
/// </summary>
public sealed class SourceSyncDeliveryConfirmTests
{
    private const string FrozenW616Hex =
        "0000000e016e6f6e63652d6162632d313233000000490200000040000000000726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8";

    private const string Nonce = "nonce-abc-123";
    private static readonly byte[] Secret = Encoding.UTF8.GetBytes("correct horse battery staple");

    // ---- 1. confirmed, unconfirmed, and the difference the flag makes -------------------------

    [Fact]
    public async Task A_peer_that_reads_the_frames_and_closes_confirms_the_send()
    {
        await using RawPeer peer = new(async (stream, _, token) => { await ReadToEndAsync(stream, token); });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            delivery: SourceSyncDeliveryOptions.Confirming());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.DeliveryConfirmed, sent.Detail);
        Assert.Equal(2, sent.FrameCount);
        await peer.Completion.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(95, peer.BytesRead);
    }

    [Fact]
    public async Task A_peer_that_keeps_the_connection_open_is_unconfirmed_after_the_budget()
    {
        await using RawPeer peer = new(async (stream, _, token) =>
        {
            await ReadToEndAsync(stream, token);
            // The envelope arrived in full; this peer simply never closes, which is exactly the case the
            // budget exists for.
            await Task.Delay(TimeSpan.FromSeconds(5), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            delivery: SourceSyncDeliveryOptions.Confirming(TimeSpan.FromMilliseconds(600)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, sent.Reason);
        Assert.False(sent.DeliveryConfirmed);
        Assert.Contains("did not close the connection within 600 ms", sent.Detail);

        // Proof that the wait really happened instead of returning at once.
        Assert.True(sent.DeliveryElapsed >= TimeSpan.FromMilliseconds(400), sent.DeliveryElapsed.ToString());
        Assert.Equal(64, sent.Bytes);
    }

    [Fact]
    public async Task A_peer_that_resets_the_connection_is_unconfirmed_and_not_io_failed()
    {
        await using RawPeer peer = new(async (stream, client, token) =>
        {
            await ReadExactlyAsync(stream, 95, token);
            client.Client.LingerState = new LingerOption(true, 0);
            client.Client.Close();
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            delivery: SourceSyncDeliveryOptions.Confirming(TimeSpan.FromMilliseconds(2000)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, sent.Reason);
        Assert.DoesNotContain(SourceSyncTransferReasons.IoFailed, sent.Detail);
        Assert.Contains("while the sender was waiting for its acknowledgement", sent.Detail);
    }

    [Fact]
    public async Task Without_the_flag_a_send_returns_at_once_and_reports_no_confirmation()
    {
        await using RawPeer peer = new(async (_, _, token) =>
        {
            // Never reads, never closes: a fire-and-forget send must not wait for this.
            await Task.Delay(TimeSpan.FromSeconds(5), token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Null(sent.DeliveryConfirmed);
        Assert.Equal(TimeSpan.Zero, sent.DeliveryElapsed);
        Assert.False(peer.Closed.IsCompleted);
        Assert.Equal(0, peer.BytesRead);
    }

    // ---- 2. the wire bytes do not change ------------------------------------------------------

    [Fact]
    public async Task Confirmation_on_still_writes_the_frozen_w6_16_bytes()
    {
        byte[] seen = [];
        await using RawPeer peer = new(async (stream, _, token) =>
        {
            seen = await ReadToEndAsync(stream, token);
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            delivery: SourceSyncDeliveryOptions.Confirming());

        Assert.True(sent.DeliveryConfirmed, sent.Detail);
        Assert.Equal(95, seen.Length);
        Assert.Equal(FrozenW616Hex, Convert.ToHexString(seen).ToLowerInvariant());
    }

    [Fact]
    public async Task Half_closing_adds_no_byte_and_leaves_the_read_side_open()
    {
        using TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;

        Task<long> peerRead = Task.Run(async () =>
        {
            using TcpClient accepted = await listener.AcceptTcpClientAsync();
            using NetworkStream peerStream = accepted.GetStream();
            await ReadExactlyAsync(peerStream, 95, CancellationToken.None);
            // Answering after the peer's half-close proves the sender's read side survived it:
            // Shutdown(Send) sends a FIN, it does not close the socket.
            await peerStream.WriteAsync(new byte[] { 0x42 }, CancellationToken.None);
            await peerStream.FlushAsync(CancellationToken.None);
            await ReadToEndAsync(peerStream, CancellationToken.None);
            return 95L;
        });

        using TcpClient client = new();
        await client.ConnectAsync(IPAddress.Loopback, port, CancellationToken.None);
        using NetworkStream stream = client.GetStream();
        SourceSyncTransferResult written = await SourceSyncTransfer.SendAsync(stream, Payload(), Nonce, CancellationToken.None);
        Assert.True(written.Succeeded, written.Detail);

        client.Client.Shutdown(SocketShutdown.Send);
        SourceSyncDeliveryResult confirmed = await SourceSyncDeliveryConfirmation
            .ConfirmAsync(stream, written.Bytes, TimeSpan.FromSeconds(5))
            ;

        Assert.True(confirmed.Confirmed, confirmed.Detail);
        Assert.Equal(1, confirmed.BytesRead);
        Assert.Equal(95, await peerRead.WaitAsync(TimeSpan.FromSeconds(10)));
        listener.Stop();
    }

    [Fact]
    public async Task A_peer_that_keeps_sending_instead_of_closing_is_unconfirmed()
    {
        using MemoryStream endless = new(new byte[SourceSyncDeliveryConfirmation.MaximumDrainBytes + 1024]);
        SourceSyncDeliveryResult confirmed = await SourceSyncDeliveryConfirmation
            .ConfirmAsync(endless, 64, TimeSpan.FromSeconds(5))
            ;

        Assert.False(confirmed.Confirmed);
        Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, confirmed.Reason);
        Assert.Contains("kept sending after it instead of closing", confirmed.Detail);
    }

    [Fact]
    public async Task A_stream_that_is_already_at_its_end_confirms_at_once()
    {
        using MemoryStream stream = new();
        SourceSyncDeliveryResult confirmed = await SourceSyncDeliveryConfirmation
            .ConfirmAsync(stream, 0, TimeSpan.FromSeconds(5))
            ;

        Assert.True(confirmed.Confirmed);
        Assert.Equal(0, confirmed.BytesRead);
    }

    // ---- 3. TLS and the tag frame -------------------------------------------------------------

    [Fact]
    public async Task Tls_with_confirmation_succeeds()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, tls: true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;
        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, listener.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            tls: SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint),
            delivery: SourceSyncDeliveryOptions.Confirming());

        SourceSyncTransferResult received = await receiving.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.DeliveryConfirmed, sent.Detail);
        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(Payload(), received.Envelope);
    }

    [Fact]
    public async Task Tls_with_a_peer_that_handshakes_and_never_closes_is_unconfirmed()
    {
        using SourceSyncTlsIdentity identity = SourceSyncTlsIdentity.Create("mediahub-delivery-test");
        using TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;

        Task<long> server = Task.Run(async () =>
        {
            using TcpClient accepted = await listener.AcceptTcpClientAsync();
            using NetworkStream transport = accepted.GetStream();
            using SslStream ssl = new(transport, leaveInnerStreamOpen: false);
            await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = identity.Certificate,
                ClientCertificateRequired = false,
                EnabledSslProtocols = SslProtocols.Tls13,
                CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck,
            }, CancellationToken.None);

            // Read the whole envelope, then hold the connection open: the handshake succeeded, so this is
            // a peer that took the bytes and still gives the sender no evidence of it.
            long read = 0;
            byte[] buffer = new byte[256];
            while (read < 95)
            {
                int got = await ssl.ReadAsync(buffer, CancellationToken.None);
                if (got <= 0)
                {
                    break;
                }

                read += got;
            }

            await Task.Delay(TimeSpan.FromSeconds(5), CancellationToken.None);
            return read;
        });

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            tls: SourceSyncTlsOptions.Pinned(identity.Fingerprint),
            delivery: SourceSyncDeliveryOptions.Confirming(TimeSpan.FromMilliseconds(600)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncDeliveryReasons.DeliveryUnconfirmed, sent.Reason);
        Assert.Contains("did not close the connection within 600 ms", sent.Detail);
        Assert.Equal(95, await server.WaitAsync(TimeSpan.FromSeconds(10)));
        listener.Stop();
    }

    [Fact]
    public async Task The_tag_frame_and_confirmation_work_together()
    {
        await using RawPeer peer = new(async (stream, _, token) => { await ReadToEndAsync(stream, token); });
        SourceSyncTransferOptions auth = SourceSyncTransferOptions.WithSecret(Secret);

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            null, peer.Port, Payload(), Nonce, TimeSpan.FromSeconds(10), CancellationToken.None,
            options: auth,
            delivery: SourceSyncDeliveryOptions.Confirming());

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.True(sent.DeliveryConfirmed, sent.Detail);
        Assert.Equal(3, sent.FrameCount);
        await peer.Completion.WaitAsync(TimeSpan.FromSeconds(10));
        // 95 bytes of frames plus the tag frame (4-byte length prefix + kind byte + 32-byte tag = 37),
        // and not one byte more.
        Assert.Equal(132, peer.BytesRead);
    }

    // ---- 4. the reason sets stay separate -----------------------------------------------------

    [Fact]
    public void The_delivery_reason_set_is_closed_and_disjoint_from_the_other_three()
    {
        Assert.Equal(new[] { "delivery-unconfirmed" }, SourceSyncDeliveryReasons.All);
        Assert.Equal(10, SourceSyncTransferReasons.All.Count);
        Assert.Equal(5, SourceSyncTransferAuthReasons.All.Count);
        Assert.Equal(4, SourceSyncTlsReasons.All.Count);

        string[] all = SourceSyncDeliveryReasons.All
            .Concat(SourceSyncTransferReasons.All)
            .Concat(SourceSyncTransferAuthReasons.All)
            .Concat(SourceSyncTlsReasons.All)
            .ToArray();
        Assert.Equal(all.Length, all.Distinct(StringComparer.Ordinal).Count());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static byte[] Payload(int length = 64)
    {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++)
        {
            payload[i] = (byte)((i * 31) + 7);
        }

        return payload;
    }

    private static async Task<byte[]> ReadToEndAsync(Stream stream, CancellationToken token)
    {
        using MemoryStream sink = new();
        byte[] buffer = new byte[512];
        while (true)
        {
            int read = await stream.ReadAsync(buffer, token);
            if (read <= 0)
            {
                return sink.ToArray();
            }

            sink.Write(buffer, 0, read);
        }
    }

    private static async Task ReadExactlyAsync(Stream stream, int count, CancellationToken token)
    {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count)
        {
            int got = await stream.ReadAsync(buffer.AsMemory(read, count - read), token);
            if (got <= 0)
            {
                throw new IOException("the peer closed after " + read + " of " + count + " bytes.");
            }

            read += got;
        }
    }

    /// <summary>A one-connection loopback peer whose behaviour each test spells out.</summary>
    private sealed class RawPeer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _cts = new();
        private readonly TaskCompletionSource _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly Task _accepted;
        private long _bytesRead;

        public RawPeer(Func<Stream, TcpClient, CancellationToken, Task> handler)
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            _accepted = AcceptAsync(handler);
        }

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public long BytesRead => Interlocked.Read(ref _bytesRead);

        public Task Closed => _closed.Task;

        public Task Completion => _accepted;

        internal void Count(long bytes) => Interlocked.Add(ref _bytesRead, bytes);

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
                // The handler is allowed to end in any way; the assertions already ran.
            }

            _cts.Dispose();
        }

        private async Task AcceptAsync(Func<Stream, TcpClient, CancellationToken, Task> handler)
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync(_cts.Token);
                using CountingStream stream = new(client.GetStream(), this);
                await handler(stream, client, _cts.Token);
            }
            catch (Exception)
            {
                // A handler that ends by closing the socket is the normal path; nothing to report here.
            }
            finally
            {
                _closed.TrySetResult();
            }
        }

        /// <summary>Counts what the peer read, so a test can prove the wire bytes did not change.</summary>
        private sealed class CountingStream : Stream
        {
            private readonly Stream _inner;
            private readonly RawPeer _owner;

            public CountingStream(Stream inner, RawPeer owner)
            {
                _inner = inner;
                _owner = owner;
            }

            public override bool CanRead => true;

            public override bool CanSeek => false;

            public override bool CanWrite => true;

            public override long Length => throw new NotSupportedException();

            public override long Position
            {
                get => throw new NotSupportedException();
                set => throw new NotSupportedException();
            }

            public override void Flush() => _inner.Flush();

            public override int Read(byte[] buffer, int offset, int count)
            {
                int read = _inner.Read(buffer, offset, count);
                _owner.Count(read);
                return read;
            }

            public override int Read(Span<byte> buffer)
            {
                int read = _inner.Read(buffer);
                _owner.Count(read);
                return read;
            }

            public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
            {
                int read = await _inner.ReadAsync(buffer, cancellationToken);
                _owner.Count(read);
                return read;
            }

            public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
            {
                int read = await _inner.ReadAsync(buffer.AsMemory(offset, count), cancellationToken);
                _owner.Count(read);
                return read;
            }

            public override void Write(byte[] buffer, int offset, int count) => _inner.Write(buffer, offset, count);

            public override void Write(ReadOnlySpan<byte> buffer) => _inner.Write(buffer);

            public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
                => _inner.WriteAsync(buffer, cancellationToken);

            public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
                => _inner.WriteAsync(buffer, offset, count, cancellationToken);

            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

            public override void SetLength(long value) => throw new NotSupportedException();

            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    _inner.Dispose();
                }

                base.Dispose(disposing);
            }
        }
    }
}
/// <summary>
/// W6-30: the CLI half of the delivery confirmation. The runner is driven in-process, so a serve and a send
/// share one process exactly as two command lines would share one machine.
/// </summary>
public sealed class SyncCliDeliveryConfirmTests : IDisposable
{
    private const string ListeningMarker = "listening 127.0.0.1:";
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);

    private readonly string _root;

    public SyncCliDeliveryConfirmTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mh-w630-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_root, recursive: true);
        }
        catch (IOException)
        {
            // A leaked temp directory is not worth failing a green run over.
        }
    }

    // ---- 1. the three argument rules --------------------------------------------------------

    [Fact]
    public void Confirm_delivery_only_parses_on_transfer_send()
    {
        (int serveExit, _, string serveErr) = Run(
            "transfer", "serve", "--root", _root, "--device", "dev", "--confirm-delivery");
        Assert.Equal(CliExitCode.UsageError, serveExit);
        Assert.Contains("option-not-allowed-for-command:--confirm-delivery:transfer-serve", serveErr);

        (int roundExit, _, string roundErr) = Run(
            "transfer", "roundtrip", "--root", _root, "--device-a", "a", "--device-b", "b", "--confirm-delivery");
        Assert.Equal(CliExitCode.UsageError, roundExit);
        Assert.Contains("option-not-allowed-for-command:--confirm-delivery:transfer-roundtrip", roundErr);
    }

    [Fact]
    public void Confirm_delivery_takes_no_value()
    {
        (int exit, _, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", _root, "--device", "dev", "--confirm-delivery=1");

        Assert.Equal(CliExitCode.UsageError, exit);
        Assert.Contains("flag-takes-no-value:--confirm-delivery", stderr);
    }

    [Fact]
    public void Help_names_the_new_flag_and_its_reason()
    {
        (int exit, string stdout, _) = Run("transfer", "--help");

        Assert.Equal(CliExitCode.Success, exit);
        Assert.Contains("--confirm-delivery", stdout);
        Assert.Contains("delivery-unconfirmed", stdout);
    }

    // ---- 2. the JSON and text shapes --------------------------------------------------------

    [Fact]
    public async Task Json_mode_reports_confirm_delivery_and_the_confirmation()
    {
        string sender = Path.Combine(_root, "sender");
        string receiver = Path.Combine(_root, "receiver");
        Seed(sender);
        Seed(receiver);

        CaptureWriter serveErr = new();
        StringWriter serveOut = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(
            ["transfer", "serve", "--root", receiver, "--device", "receiver-1", "--port", "0", "--apply", "--json"],
            serveOut, serveErr, () => FixedNow));
        int port = await WaitForPortAsync(serveErr);

        (int exit, string stdout, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", sender, "--device", "sender-1", "--json", "--confirm-delivery");

        Assert.Equal(CliExitCode.Success, exit);
        using JsonDocument document = JsonDocument.Parse(stdout);
        Assert.True(document.RootElement.GetProperty("confirmDelivery").GetBoolean());
        Assert.True(document.RootElement.GetProperty("deliveryConfirmed").GetBoolean());
        Assert.Equal(1, document.RootElement.GetProperty("frames").GetInt32());
        Assert.DoesNotContain("delivery-unconfirmed", stderr);

        Assert.Equal(CliExitCode.Success, await serving.WaitAsync(TimeSpan.FromSeconds(30)));
        Assert.Equal(1, ReadRecordCount(Path.Combine(receiver, "sync-sources.json")));
    }

    [Fact]
    public async Task Text_mode_prints_the_confirmation_line()
    {
        string sender = Path.Combine(_root, "sender-text");
        string receiver = Path.Combine(_root, "receiver-text");
        Seed(sender);
        Seed(receiver);

        CaptureWriter serveErr = new();
        StringWriter serveOut = new();
        Task<int> serving = Task.Run(() => SyncCliProgram.Run(
            ["transfer", "serve", "--root", receiver, "--device", "receiver-t", "--port", "0", "--apply"],
            serveOut, serveErr, () => FixedNow));
        int port = await WaitForPortAsync(serveErr);

        (int exit, string stdout, _) = Run(
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", sender, "--device", "sender-t", "--confirm-delivery");

        Assert.Equal(CliExitCode.Success, exit);
        Assert.Contains("delivery: confirmed (peer closed after ", stdout);
        Assert.Equal(CliExitCode.Success, await serving.WaitAsync(TimeSpan.FromSeconds(30)));
    }

    // ---- 3. the case the flag exists for ----------------------------------------------------

    [Fact]
    public async Task A_peer_that_never_closes_is_reported_as_delivery_unconfirmed()
    {
        string sender = Path.Combine(_root, "sender-silent");
        Seed(sender);

        using TcpListener silent = new(IPAddress.Loopback, 0);
        silent.Start();
        int port = ((IPEndPoint)silent.LocalEndpoint).Port;
        Task holding = Task.Run(async () =>
        {
            using TcpClient accepted = await silent.AcceptTcpClientAsync();
            await Task.Delay(TimeSpan.FromSeconds(10));
        });

        Stopwatch clock = Stopwatch.StartNew();
        (int exit, string stdout, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
            "--root", sender, "--device", "sender-silent", "--json", "--confirm-delivery");
        clock.Stop();

        Assert.Equal(CliExitCode.InputProblem, exit);
        using JsonDocument document = JsonDocument.Parse(stdout);
        Assert.False(document.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal(
            "delivery-unconfirmed",
            document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains(
            "did not close the connection within 2000 ms",
            document.RootElement.GetProperty("error").GetProperty("message").GetString());
        Assert.Contains("error delivery-unconfirmed:", stderr);

        // The 2000 ms budget is real, not decorative.
        Assert.True(clock.Elapsed >= TimeSpan.FromSeconds(1.5), clock.Elapsed.ToString());
        silent.Stop();
        try
        {
            await holding.WaitAsync(TimeSpan.FromSeconds(2));
        }
        catch (TimeoutException)
        {
            // The peer is still holding its socket; stopping the listener above ends the test.
        }
    }

    [Fact]
    public async Task Authentication_and_confirmation_together_keep_the_secret_out_of_the_logs()
    {
        string sender = Path.Combine(_root, "sender-auth");
        string receiver = Path.Combine(_root, "receiver-auth");
        Seed(sender);
        Seed(receiver);
        string variable = "MH_W630_" + Guid.NewGuid().ToString("N");
        string secret = "w630-acceptance-secret";
        Environment.SetEnvironmentVariable(variable, secret);
        try
        {
            CaptureWriter serveErr = new();
            StringWriter serveOut = new();
            Task<int> serving = Task.Run(() => SyncCliProgram.Run(
                ["transfer", "serve", "--root", receiver, "--device", "receiver-a", "--port", "0", "--apply",
                 "--json", "--authenticate", "--secret-env", variable],
                serveOut, serveErr, () => FixedNow));
            int port = await WaitForPortAsync(serveErr);

            (int exit, string stdout, string stderr) = Run(
                "transfer", "send", "--to", "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture),
                "--root", sender, "--device", "sender-a", "--json", "--confirm-delivery",
                "--authenticate", "--secret-env", variable);

            Assert.Equal(CliExitCode.Success, exit);
            using JsonDocument document = JsonDocument.Parse(stdout);
            Assert.True(document.RootElement.GetProperty("authenticate").GetBoolean());
            Assert.True(document.RootElement.GetProperty("deliveryConfirmed").GetBoolean());
            Assert.Equal(3, document.RootElement.GetProperty("frames").GetInt32());
            Assert.Equal(variable, document.RootElement.GetProperty("secretEnv").GetString());
            Assert.DoesNotContain(secret, stdout);
            Assert.DoesNotContain(secret, stderr);

            Assert.Equal(CliExitCode.Success, await serving.WaitAsync(TimeSpan.FromSeconds(30)));
            Assert.DoesNotContain(secret, serveOut.ToString());
            Assert.DoesNotContain(secret, serveErr.Text);
        }
        finally
        {
            Environment.SetEnvironmentVariable(variable, null);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        StringWriter stdout = new();
        StringWriter stderr = new();
        int exit = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exit, stdout.ToString(), stderr.ToString());
    }

    private static async Task<int> WaitForPortAsync(CaptureWriter stderr)
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(25);
        while (DateTime.UtcNow < deadline)
        {
            string text = stderr.Text;
            int marker = text.IndexOf(ListeningMarker, StringComparison.Ordinal);
            if (marker >= 0)
            {
                int start = marker + ListeningMarker.Length;
                int end = start;
                while (end < text.Length && char.IsAsciiDigit(text[end]))
                {
                    end++;
                }

                if (end > start)
                {
                    return int.Parse(text[start..end], CultureInfo.InvariantCulture);
                }
            }

            await Task.Delay(25);
        }

        throw new TimeoutException("the in-process listener never printed its port.");
    }

    private static void Seed(string directory)
    {
        Directory.CreateDirectory(directory);
        // The store file is a JSON array, exactly what FileLocalSourceStore writes.
        const string Json = "[{\"serverId\":\"srv-emby\",\"type\":\"Emby\",\"sourceTypeRaw\":\"emby\",\"url\":\"http://nas-a:8096/emby/\",\"accountScope\":\"alice\",\"displayName\":\"Living Room Emby\",\"recordRevision\":3}]";
        File.WriteAllText(Path.Combine(directory, "sync-sources.json"), Json, new UTF8Encoding(false));
    }

    private static int ReadRecordCount(string path)
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(path, Encoding.UTF8));
        return document.RootElement.ValueKind == JsonValueKind.Array ? document.RootElement.GetArrayLength() : 1;
    }

    /// <summary>A <see cref="TextWriter"/> the test can read while the writer is still running.</summary>
    private sealed class CaptureWriter : TextWriter
    {
        private readonly StringBuilder _text = new();

        public override Encoding Encoding => Encoding.UTF8;

        public string Text
        {
            get
            {
                lock (_text)
                {
                    return _text.ToString();
                }
            }
        }

        public override void Write(char value)
        {
            lock (_text)
            {
                _text.Append(value);
            }
        }

        public override void Write(string? value)
        {
            lock (_text)
            {
                _text.Append(value);
            }
        }

        public override void WriteLine(string? value)
        {
            lock (_text)
            {
                _text.AppendLine(value);
            }
        }
    }
}

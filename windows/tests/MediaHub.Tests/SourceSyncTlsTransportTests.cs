using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-26: the TLS 1.3 wrapper around the transfer frames. Everything here runs in-process over
/// 127.0.0.1 and creates ephemeral certificates; no test binds a non-loopback address, opens a
/// window or writes into the user's real settings folder.
/// </summary>
public sealed class SourceSyncTlsTransportTests
{
    /// <summary>The W6-16 wire bytes, unchanged: TLS off must leave the byte stream exactly as it was.</summary>
    private const string FrozenW616Hex =
        "0000000e016e6f6e63652d6162632d313233000000490200000040000000000726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8";

    private const string Nonce = "nonce-abc-123";
    private static readonly byte[] Secret = Encoding.UTF8.GetBytes("correct horse battery staple");
    private static readonly byte[] WrongSecret = Encoding.UTF8.GetBytes("wrong horse battery staple");

    // ---- 1. TLS off is byte-for-byte the W6-16 stream ---------------------------------------

    [Fact]
    public async Task Tls_off_writes_the_same_bytes_as_w6_16()
    {
        using MemoryStream stream = new();
        SourceSyncTransferResult sent = await SourceSyncTransfer.SendAsync(stream, Payload(), Nonce);

        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(2, sent.FrameCount);
        Assert.Equal(95, stream.Length);
        Assert.Equal(FrozenW616Hex, Convert.ToHexString(stream.ToArray()).ToLowerInvariant());
    }

    [Fact]
    public async Task A_listener_without_tls_is_plaintext()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0);
        Assert.True(start.Started, start.Detail);
        Assert.NotNull(start.Listener);
        await using SourceSyncTransferListener listener = start.Listener!;
        Assert.False(listener.TlsEnabled);
        Assert.Null(listener.CertificateFingerprint);
    }

    // ---- 2. the identity ---------------------------------------------------------------

    [Fact]
    public async Task A_tls_listener_reports_a_public_key_fingerprint()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        Assert.NotNull(start.Listener);
        await using SourceSyncTransferListener listener = start.Listener!;
        Assert.True(listener.TlsEnabled);
        string? fingerprint = listener.CertificateFingerprint;
        Assert.NotNull(fingerprint);
        Assert.Equal(64, fingerprint!.Length);
        Assert.Equal(fingerprint.ToLowerInvariant(), fingerprint);
        Assert.True(fingerprint.All(Uri.IsHexDigit));
    }

    [Fact]
    public void The_fingerprint_is_the_sha256_of_the_public_key_not_of_the_certificate()
    {
        using SourceSyncTlsIdentity identity = SourceSyncTlsIdentity.Create("mediahub-test");
        using RSA? rsa = identity.Certificate.GetRSAPublicKey();
        Assert.NotNull(rsa);

        string expected = Convert.ToHexString(SHA256.HashData(rsa!.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
        Assert.Equal(expected, identity.Fingerprint);

        // Proof that it is not the certificate digest: that one is a different value.
        string certificateDigest = Convert.ToHexString(SHA256.HashData(identity.Certificate.RawData)).ToLowerInvariant();
        Assert.NotEqual(certificateDigest, identity.Fingerprint);
    }

    [Fact]
    public void Every_identity_gets_a_fresh_certificate()
    {
        using SourceSyncTlsIdentity first = SourceSyncTlsIdentity.Create("mediahub-test");
        using SourceSyncTlsIdentity second = SourceSyncTlsIdentity.Create("mediahub-test");

        Assert.NotEqual(first.Fingerprint, second.Fingerprint);
        Assert.False(first.Matches(second.Fingerprint));
        Assert.True(first.Matches(first.Fingerprint));
        Assert.False(first.Matches(null));
    }

    [Fact]
    public void The_display_fingerprint_groups_the_hex_in_fours_and_upper_case()
    {
        using SourceSyncTlsIdentity identity = SourceSyncTlsIdentity.Create("mediahub-test");
        string display = identity.DisplayFingerprint;

        // 16 groups of 4 characters plus 15 spaces.
        Assert.Equal(79, display.Length);
        Assert.Equal(display.ToUpperInvariant(), display);
        Assert.Equal(identity.Fingerprint, display.Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant());
    }

    [Fact]
    public async Task A_tls_listener_and_a_pinned_client_exchange_an_envelope()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        byte[] payload = Payload();
        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            payload,
            challenge: null,
            timeout: TimeSpan.FromSeconds(10),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint));
        Assert.True(sent.Succeeded, sent.Detail);

        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(payload, received.Envelope);

        // No challenge was announced, so the wire is one chunk frame: TLS wraps the frames, it does
        // not add any.
        Assert.Equal(1, received.FrameCount);
    }

    [Fact]
    public async Task A_client_refuses_a_certificate_that_does_not_match_its_pin()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        string wrongPin = new('a', 64);
        Assert.NotEqual(wrongPin, listener.CertificateFingerprint);

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            Payload(),
            challenge: null,
            timeout: TimeSpan.FromSeconds(10),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(wrongPin));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.FingerprintMismatch, sent.Reason);

        // The listener never got a usable session either; the important part is that no envelope
        // byte crossed the boundary.
        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
    }

    [Fact]
    public async Task A_client_refuses_a_pin_that_is_not_a_fingerprint()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            Payload(),
            challenge: null,
            timeout: TimeSpan.FromSeconds(5),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned("abc"));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.CertificateUnavailable, sent.Reason);
        Assert.Contains("64 hex", sent.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_plaintext_client_against_a_tls_listener_fails_with_the_stable_code()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));
        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            Payload(),
            challenge: null,
            timeout: TimeSpan.FromSeconds(10),
            cancellationToken: default);

        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, received.Reason);
    }

    [Fact]
    public async Task A_tls_client_against_a_plaintext_listener_is_refused_as_tls_not_enabled()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10));
        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            Payload(),
            challenge: null,
            timeout: TimeSpan.FromSeconds(10),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(new string('a', 64)));

        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.NotEnabled, received.Reason);
        Assert.Contains("TLS handshake record", received.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task The_handshake_gives_up_when_the_peer_never_speaks_tls()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        using TcpClient silent = new();
        await silent.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = silent.GetStream();

        SourceSyncTransferResult received = await listener.ReceiveOnceAsync(null, TimeSpan.FromMilliseconds(700));
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, received.Reason);
        Assert.Contains("did not finish within", received.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task The_server_handshake_refuses_an_advertisement_that_is_not_its_own_certificate()
    {
        using SourceSyncTlsIdentity identity = SourceSyncTlsIdentity.Create("mediahub-test");
        using MemoryStream stream = new();

        SourceSyncTlsHandshakeResult result = await SourceSyncTlsHandshake.AuthenticateServerAsync(
            stream,
            identity.Certificate,
            new string('b', 64),
            TimeSpan.FromSeconds(1));
        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.FingerprintMismatch, result.Reason);
        Assert.Equal(0L, stream.Length);
    }

    [Fact]
    public async Task The_server_handshake_without_a_certificate_is_certificate_unavailable()
    {
        using MemoryStream stream = new();
        SourceSyncTlsHandshakeResult result = await SourceSyncTlsHandshake.AuthenticateServerAsync(
            stream,
            null,
            null,
            TimeSpan.FromSeconds(1));
        Assert.False(result.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.CertificateUnavailable, result.Reason);
    }

    // ---- 3. TLS composed with the W6-24 tag frame ---------------------------------------

    [Fact]
    public async Task Tls_carries_the_w6_24_tag_frame()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        SourceSyncNonceCache nonces = new();
        SourceSyncTransferOptions receiverOptions = SourceSyncTransferOptions.WithSecret(Secret, nonces, "dev-b");
        SourceSyncTransferOptions senderOptions = SourceSyncTransferOptions.WithSecret(Secret);
        byte[] payload = Payload();
        string challenge = SourceSyncTransferAuthenticator.CreateNonce();

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10), default, receiverOptions);
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            payload,
            challenge,
            TimeSpan.FromSeconds(10),
            default,
            senderOptions,
            SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint));
        Assert.True(sent.Succeeded, sent.Detail);
        Assert.Equal(3, sent.FrameCount);

        SourceSyncTransferResult received = await receiving;
        Assert.True(received.Succeeded, received.Detail);
        Assert.Equal(3, received.FrameCount);
        Assert.Equal(payload, received.Envelope);
        Assert.Equal(challenge, received.Challenge);
        Assert.Equal(1, nonces.Count);
    }

    [Fact]
    public async Task Tls_carries_the_rejection_of_a_wrong_secret()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        SourceSyncNonceCache nonces = new();
        SourceSyncTransferOptions receiverOptions = SourceSyncTransferOptions.WithSecret(Secret, nonces, "dev-b");
        SourceSyncTransferOptions senderOptions = SourceSyncTransferOptions.WithSecret(WrongSecret);

        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TimeSpan.FromSeconds(10), default, receiverOptions);
        await SourceSyncTransferClient.SendAsync(
            "127.0.0.1",
            listener.Port,
            Payload(),
            SourceSyncTransferAuthenticator.CreateNonce(),
            TimeSpan.FromSeconds(10),
            default,
            senderOptions,
            SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint));

        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTransferAuthReasons.AuthMismatch, received.Reason);
        Assert.Empty(received.Envelope);

        // A tag that did not verify must not burn the nonce: the honest peer can still deliver.
        Assert.Equal(0, nonces.Count);
    }

    // ---- 4. the plaintext-side probe ------------------------------------------------------

    [Fact]
    public async Task The_probe_passes_plaintext_bytes_through_unchanged()
    {
        byte[] frame = Encoding.UTF8.GetBytes("hello");
        using MemoryStream source = new(frame);
        SourceSyncTlsProbeResult probe = await SourceSyncTlsProbe.ReadAsync(source);

        Assert.True(probe.Accepted, probe.Detail);
        Assert.NotNull(probe.Stream);
        Stream inner = probe.Stream!;
        Assert.False(inner.CanSeek);
        Assert.False(inner.CanWrite);
        Assert.Throws<NotSupportedException>(() => inner.Write(frame, 0, frame.Length));

        byte[] buffer = new byte[frame.Length];
        int read = 0;
        while (read < buffer.Length)
        {
            int step = await inner.ReadAsync(buffer.AsMemory(read));
            Assert.True(step > 0, "the probe stream ended after " + read + " of " + buffer.Length + " bytes");
            read += step;
        }

        Assert.Equal("hello", Encoding.UTF8.GetString(buffer));
    }

    [Fact]
    public async Task The_probe_refuses_a_tls_hello_on_the_plaintext_path()
    {
        using MemoryStream source = new([0x16, 0x03, 0x01, 0x00, 0x05]);
        SourceSyncTlsProbeResult probe = await SourceSyncTlsProbe.ReadAsync(source);

        Assert.False(probe.Accepted);
        Assert.Equal(SourceSyncTlsReasons.NotEnabled, probe.Reason);
        Assert.Null(probe.Stream);
    }

    [Fact]
    public async Task Two_bytes_cannot_be_judged_so_they_are_replayed()
    {
        using MemoryStream source = new([0x16, 0x03]);
        SourceSyncTlsProbeResult probe = await SourceSyncTlsProbe.ReadAsync(source);

        Assert.True(probe.Accepted, probe.Detail);
        byte[] buffer = new byte[2];
        Assert.Equal(2, await probe.Stream!.ReadAsync(buffer.AsMemory()));
        Assert.Equal(new byte[] { 0x16, 0x03 }, buffer);
    }

    // ---- 5. the dialect does not weaken the loopback rule --------------------------------

    [Fact]
    public async Task A_tls_client_still_refuses_a_non_loopback_target()
    {
        SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
            "10.11.12.13",
            9,
            Payload(),
            challenge: null,
            timeout: TimeSpan.FromMilliseconds(250),
            cancellationToken: default,
            options: null,
            tls: SourceSyncTlsOptions.Pinned(new string('a', 64)));

        Assert.False(sent.Succeeded);
        Assert.Equal(SourceSyncTransferReasons.NotLoopback, sent.Reason);
    }

    [Fact]
    public void The_tls_codes_are_a_fourth_closed_set()
    {
        Assert.Equal(4, SourceSyncTlsReasons.All.Count);
        Assert.Equal(4, SourceSyncTlsReasons.All.Distinct(StringComparer.Ordinal).Count());
        foreach (string code in SourceSyncTlsReasons.All)
        {
            Assert.DoesNotContain(code, SourceSyncTransferReasons.All);
            Assert.DoesNotContain(code, SourceSyncTransferAuthReasons.All);
        }
    }

    // ---- 6. the timeout classification is deterministic, not a race ------------------------

    [Fact]
    public async Task A_timeout_is_reported_as_a_timeout_even_when_the_accept_was_slow()
    {
        // W6-26 regression: the endpoint starts the transfer budget *before* it accepts, so a slow accept
        // used to consume budget the handshake still counted on. The handshake then saw its own caller
        // cancelled well before its own deadline and reported "the caller cancelled" for what was really a
        // timeout - the windows-ci failure this test now pins down. The accept is delayed here on purpose,
        // so the race is reproduced every time instead of one run in three.
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        Task<SourceSyncTransferResult> receiving =
            listener.ReceiveOnceAsync(null, TimeSpan.FromMilliseconds(900));
        await Task.Delay(TimeSpan.FromMilliseconds(400));

        using TcpClient silent = new();
        await silent.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = silent.GetStream();

        SourceSyncTransferResult received = await receiving;
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, received.Reason);
        Assert.Contains("did not finish within", received.Detail, StringComparison.Ordinal);
        Assert.DoesNotContain("caller cancelled", received.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_caller_that_cancels_before_the_deadline_is_reported_as_a_cancellation()
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(null, 0, true);
        Assert.True(start.Started, start.Detail);
        await using SourceSyncTransferListener listener = start.Listener!;

        using TcpClient silent = new();
        await silent.ConnectAsync(IPAddress.Loopback, listener.Port);
        await using NetworkStream stream = silent.GetStream();

        // The budget is generous and the operator's token is the only thing that fires: this is the one
        // case that must keep saying "the caller cancelled".
        using CancellationTokenSource cancelled = new(TimeSpan.FromMilliseconds(250));
        SourceSyncTransferResult received = await listener.ReceiveOnceAsync(
            null,
            TimeSpan.FromSeconds(10),
            cancelled.Token);
        Assert.False(received.Succeeded);
        Assert.Equal(SourceSyncTlsReasons.HandshakeFailed, received.Reason);
        Assert.Contains("caller cancelled", received.Detail, StringComparison.Ordinal);
        Assert.DoesNotContain("did not finish within", received.Detail, StringComparison.Ordinal);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static byte[] Payload(int length = 64)
    {
        byte[] payload = new byte[length];
        for (int i = 0; i < payload.Length; i++)
        {
            payload[i] = (byte)((i * 31) + 7);
        }

        return payload;
    }
}

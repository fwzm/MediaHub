using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>
/// Stable, machine-readable reasons the TLS wrapper can report (W6-26).
/// </summary>
/// <remarks>
/// <para>
/// A separate closed set on purpose. <see cref="SourceSyncTransferReasons"/> and
/// <see cref="SourceSyncTransferAuthReasons"/> are both closed and both asserted as such by tests, so a new
/// failure family gets its own family instead of widening someone else's list. The four codes below are what a
/// caller can act on: fix the certificate, fix the pin, enable TLS, or stop expecting TLS.
/// </para>
/// <para>
/// None of these strings ever contains key material: a fingerprint is public by construction, and a handshake
/// message from SChannel names the protocol, never a secret.
/// </para>
/// </remarks>
public static class SourceSyncTlsReasons
{
    /// <summary>The TLS handshake failed, timed out, or negotiated a version below TLS 1.3.</summary>
    public const string HandshakeFailed = "tls-handshake-failed";

    /// <summary>The peer's certificate does not carry the fingerprint the caller pinned.</summary>
    public const string FingerprintMismatch = "tls-fingerprint-mismatch";

    /// <summary>No trust material: the server holds no certificate, or the client has no pin to compare against.</summary>
    public const string CertificateUnavailable = "tls-certificate-unavailable";

    /// <summary>The peer opened with a TLS handshake but this endpoint is plaintext, or TLS was asked of a plaintext transport.</summary>
    public const string NotEnabled = "tls-not-enabled";

    /// <summary>Every documented reason code, in the order this file declares them.</summary>
    public static IReadOnlyList<string> All { get; } = new[]
    {
        HandshakeFailed,
        FingerprintMismatch,
        CertificateUnavailable,
        NotEnabled,
    };
}

/// <summary>
/// One self-signed TLS identity: the certificate a listener presents, and the fingerprint a peer pins.
/// </summary>
/// <remarks>
/// <para>
/// The certificate is ephemeral by design (W6-3b's rule): a fresh RSA-2048 key per listener, valid for two
/// hours, never stored. Its fingerprint is the SHA-256 of the SubjectPublicKeyInfo, the same convention
/// <see cref="PairingCertificate"/> already uses, so a pin printed by a server can be typed into a client
/// without any encoding step in between.
/// </para>
/// <para>
/// The identity is not a secret: the certificate and its fingerprint are exactly what a peer needs to decide
/// whether to talk to us. Only the private key is sensitive, and it never leaves this object.
/// </para>
/// </remarks>
public sealed class SourceSyncTlsIdentity : IDisposable
{
    /// <summary>The common name used when the caller does not name one; also the client's SNI target.</summary>
    public const string DefaultCommonName = "mediahub-sync";

    private readonly X509Certificate2 _certificate;
    private readonly PairingFingerprint _fingerprint;
    private int _disposed;

    private SourceSyncTlsIdentity(X509Certificate2 certificate, PairingFingerprint fingerprint)
    {
        _certificate = certificate;
        _fingerprint = fingerprint;
    }

    /// <summary>The certificate to present, including its private key.</summary>
    /// <exception cref="ObjectDisposedException">The identity was disposed.</exception>
    public X509Certificate2 Certificate
    {
        get
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            return _certificate;
        }
    }

    /// <summary>The lowercase hex SHA-256 of the SubjectPublicKeyInfo — the value a peer pins.</summary>
    public string Fingerprint => _fingerprint.Hex;

    /// <summary>The same fingerprint grouped in fours, for a human comparing two screens.</summary>
    public string DisplayFingerprint => _fingerprint.ToDisplayString();

    /// <summary>Creates a fresh ephemeral identity.</summary>
    /// <param name="commonName">The certificate's common name; blank means <see cref="DefaultCommonName"/>.</param>
    /// <exception cref="CryptographicException">The platform refused to create or import the key.</exception>
    public static SourceSyncTlsIdentity Create(string? commonName = null)
    {
        string name = string.IsNullOrWhiteSpace(commonName) ? DefaultCommonName : commonName.Trim();
        X509Certificate2 certificate = PairingCertificate.CreateEphemeral(name);
        try
        {
            PairingFingerprint fingerprint = PairingFingerprint.Parse(PairingCertificate.ComputeFingerprint(certificate));
            return new SourceSyncTlsIdentity(certificate, fingerprint);
        }
        catch (Exception)
        {
            certificate.Dispose();
            throw;
        }
    }

    /// <summary>Creates an identity, reporting rather than throwing when the platform cannot make one.</summary>
    /// <param name="identity">The new identity, or null when creation failed.</param>
    /// <param name="error">A message naming the failure; never contains key material.</param>
    public static bool TryCreate(out SourceSyncTlsIdentity? identity, out string? error)
    {
        identity = null;
        error = null;
        try
        {
            identity = Create();
            return true;
        }
        catch (Exception exception) when (exception is CryptographicException
            or PlatformNotSupportedException
            or IOException
            or SyncContractException
            or UnauthorizedAccessException)
        {
            error = "A TLS certificate could not be created on this machine, so no TLS listener was started: "
                + exception.Message;
            return false;
        }
    }

    /// <summary>Whether <paramref name="hex"/> names this identity's key (constant-time, empty never matches).</summary>
    public bool Matches(string? hex)
        => PairingFingerprint.TryParse(hex, out PairingFingerprint parsed) && parsed.Matches(_fingerprint);

    /// <inheritdoc />
    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            _certificate.Dispose();
        }
    }

    /// <inheritdoc />
    public override string ToString() => "SourceSyncTlsIdentity(" + _fingerprint.Hex + ")";
}

/// <summary>
/// How a caller wants a transfer protected: on or off, and — on the dialling side — which key to accept.
/// </summary>
/// <remarks>
/// A sealed class rather than a record so the generated <c>ToString</c> cannot leak material by accident; the
/// property it carries is a fingerprint, which is public, so the override below prints it as-is.
/// </remarks>
public sealed class SourceSyncTlsOptions
{
    /// <summary>Whether TLS is required. When false the call behaves exactly like W6-16.</summary>
    public bool Enabled { get; init; }

    /// <summary>
    /// The fingerprint to accept, sixty-four hex characters — the pin. Required on the dialling side; a server
    /// does not pin its own certificate and leaves this null.
    /// </summary>
    public string? ExpectedFingerprint { get; init; }

    /// <summary>TLS off, which is the default everywhere.</summary>
    public static SourceSyncTlsOptions Off { get; } = new();

    /// <summary>TLS on, pinning <paramref name="expectedFingerprint"/>; an unusable value fails with <see cref="SourceSyncTlsReasons.CertificateUnavailable"/>.</summary>
    public static SourceSyncTlsOptions Pinned(string? expectedFingerprint)
        => new() { Enabled = true, ExpectedFingerprint = expectedFingerprint };

    /// <summary>Whether <see cref="ExpectedFingerprint"/> parses as a fingerprint.</summary>
    public bool HasPin => PairingFingerprint.TryParse(ExpectedFingerprint, out _);

    /// <inheritdoc />
    public override string ToString() => Enabled
        ? "SourceSyncTlsOptions { Enabled = true, ExpectedFingerprint = '" + (ExpectedFingerprint ?? "<none>") + "' }"
        : "SourceSyncTlsOptions { Enabled = false }";
}

/// <summary>What a TLS handshake produced.</summary>
public sealed record SourceSyncTlsHandshakeResult
{
    /// <summary>True when the stream is encrypted and (on the client) the pin checked out.</summary>
    public bool Succeeded { get; init; }

    /// <summary>One of <see cref="SourceSyncTlsReasons"/> when <see cref="Succeeded"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Why it failed; never contains key material or a secret.</summary>
    public string? Detail { get; init; }

    /// <summary>The encrypted stream, when the handshake succeeded and the pin matched.</summary>
    public SslStream? Stream { get; init; }

    /// <summary>
    /// The certificate this handshake is about: the peer's on the client side, our own on the server side.
    /// A server sees no client certificate, so this is never "the other end" there.
    /// </summary>
    public string? CertificateFingerprint { get; init; }

    /// <summary>A completed handshake.</summary>
    public static SourceSyncTlsHandshakeResult Ok(SslStream stream, string certificateFingerprint) => new()
    {
        Succeeded = true,
        Stream = stream,
        CertificateFingerprint = certificateFingerprint,
    };

    /// <summary>A refused or failed handshake.</summary>
    public static SourceSyncTlsHandshakeResult Failed(string reason, string? detail = null) => new()
    {
        Reason = reason,
        Detail = detail,
    };
}

/// <summary>
/// Turns a plain loopback stream into a TLS 1.3 stream, and pins the peer's key (W6-26).
/// </summary>
/// <remarks>
/// <para>
/// The framing does not change: the same four-byte length, kind and payload travel inside TLS records, so a
/// plaintext peer and a TLS peer differ only in what wraps them. Nothing here re-implements framing.
/// </para>
/// <para>
/// Chain validation is replaced by pinning, exactly as W6-3b does it for pairing: a self-signed certificate has
/// no chain to check, and a CA-signed one from a key the attacker holds would still match its own fingerprint.
/// The client therefore accepts any certificate inside the handshake callback and makes the real decision by
/// comparing fingerprints afterwards — the check cannot be skipped by a platform that thinks the chain is fine.
/// </para>
/// <para>
/// Refusing to negotiate anything below TLS 1.3 is deliberate (ADR-S003 D2): a downgrade is indistinguishable
/// from a breakage, and both mean "this is not the endpoint we require".
/// </para>
/// </remarks>
public static class SourceSyncTlsHandshake
{
    /// <summary>The SNI target and certificate common name a peer expects.</summary>
    public const string TargetHostName = SourceSyncTlsIdentity.DefaultCommonName;

    /// <summary>The only protocol version accepted.</summary>
    public const SslProtocols RequiredProtocol = SslProtocols.Tls13;

    // A close_notify is not a formality: an abortive close throws away records the receiver has not read yet, so
    // the peer gets a generous two seconds to answer it, and the socket is drained before the fallback close.
    private const int ShutdownBudgetMilliseconds = 2000;

    private const int DrainBudgetMilliseconds = 250;

    /// <summary>Accepts the peer's TLS handshake as the listening side.</summary>
    /// <param name="transport">The connected plaintext stream; the returned stream wraps it.</param>
    /// <param name="certificate">The server certificate, or null to report <see cref="SourceSyncTlsReasons.CertificateUnavailable"/>.</param>
    /// <param name="advertisedFingerprint">The pin this listener published, if any; a stale pin is refused before the handshake.</param>
    /// <param name="timeout">How long the handshake may take.</param>
    /// <param name="cancellationToken">Cancels the handshake.</param>
    public static async Task<SourceSyncTlsHandshakeResult> AuthenticateServerAsync(
        Stream transport,
        X509Certificate2? certificate,
        string? advertisedFingerprint,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(transport);
        if (certificate is null)
        {
            return SourceSyncTlsHandshakeResult.Failed(
                SourceSyncTlsReasons.CertificateUnavailable,
                "TLS is on but this listener holds no server certificate, so nothing was negotiated and no byte was read.");
        }

        string own;
        try
        {
            own = PairingCertificate.ComputeFingerprint(certificate);
        }
        catch (SyncContractException error)
        {
            return SourceSyncTlsHandshakeResult.Failed(SourceSyncTlsReasons.CertificateUnavailable, error.Message);
        }

        if (!string.IsNullOrWhiteSpace(advertisedFingerprint)
            && !(PairingFingerprint.TryParse(advertisedFingerprint, out PairingFingerprint advertised)
                && PairingFingerprint.TryParse(own, out PairingFingerprint actual)
                && advertised.Matches(actual)))
        {
            return SourceSyncTlsHandshakeResult.Failed(
                SourceSyncTlsReasons.FingerprintMismatch,
                "The certificate this listener holds is not the one it advertised, so the handshake was not offered.");
        }

        var ssl = new SslStream(transport, leaveInnerStreamOpen: false);

        // W6-26 fix: the deadline is its own source, so "did my own clock run out" is a state check on that source
        // instead of a guess made from a stopwatch and a 25 ms tolerance. The old guess compared an elapsed time that
        // started after the caller's budget had already been running: a slow accept ate most of it, the linked token
        // fired before the handshake's own clock could reach the same number, and a plain timeout was reported as a
        // caller cancel. A tie goes to the timeout wording, because the timeout is what a user needs to read when a
        // peer never speaks TLS.
        TimeSpan deadlineAfter = timeout > TimeSpan.Zero ? timeout : TimeSpan.FromMilliseconds(1);
        using var deadline = new CancellationTokenSource(deadlineAfter);
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, deadline.Token);
        try
        {
            await ssl.AuthenticateAsServerAsync(
                new SslServerAuthenticationOptions
                {
                    ServerCertificate = certificate,
                    // Pinning replaces chain validation; asking the client for a certificate as well would make
                    // the exchange asymmetric without adding anything the pin does not already provide.
                    ClientCertificateRequired = false,
                    EnabledSslProtocols = RequiredProtocol,
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                },
                budget.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // deadline.IsCancellationRequested is set by the source that fired, before the linked token propagates,
            // so this is a fact about which source cancelled rather than an inference from elapsed time.
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.HandshakeFailed,
                deadline.IsCancellationRequested
                    ? TimeoutDetail(deadlineAfter)
                    : "The caller cancelled the TLS handshake.");
        }
        catch (AuthenticationException error)
        {
            return AfterFailure(ssl, SourceSyncTlsReasons.HandshakeFailed, error.Message);
        }
        catch (IOException error)
        {
            return AfterFailure(ssl, SourceSyncTlsReasons.HandshakeFailed, error.Message);
        }

        return Completed(ssl, own);
    }

    /// <summary>Dials the peer's TLS handshake and checks the pin.</summary>
    /// <param name="transport">The connected plaintext stream; the returned stream wraps it.</param>
    /// <param name="expectedFingerprint">The pin, sixty-four hex characters.</param>
    /// <param name="timeout">How long the handshake may take.</param>
    /// <param name="cancellationToken">Cancels the handshake.</param>
    public static async Task<SourceSyncTlsHandshakeResult> AuthenticateClientAsync(
        Stream transport,
        string? expectedFingerprint,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(transport);
        if (!PairingFingerprint.TryParse(expectedFingerprint, out PairingFingerprint pinned))
        {
            return SourceSyncTlsHandshakeResult.Failed(
                SourceSyncTlsReasons.CertificateUnavailable,
                "TLS is on but no usable pin was given: a fingerprint is 64 hex characters (a SHA-256 digest), "
                + "and without one there is nothing to compare the peer's certificate against, so the connection was not made.");
        }

        var ssl = new SslStream(
            transport,
            leaveInnerStreamOpen: false,
            // Identity is the pin, checked below; the chain is irrelevant for a self-signed peer.
            static (sender, certificate, chain, errors) => true);

        // Same shape as the server direction: an independent deadline source decides "timeout" versus "caller
        // cancelled" as a state check, so the verdict cannot depend on how long the caller spent before dialling.
        TimeSpan deadlineAfter = timeout > TimeSpan.Zero ? timeout : TimeSpan.FromMilliseconds(1);
        using var deadline = new CancellationTokenSource(deadlineAfter);
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, deadline.Token);
        try
        {
            await ssl.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions
                {
                    TargetHost = TargetHostName,
                    EnabledSslProtocols = RequiredProtocol,
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                },
                budget.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.HandshakeFailed,
                deadline.IsCancellationRequested
                    ? TimeoutDetail(deadlineAfter)
                    : "The caller cancelled the TLS handshake.");
        }
        catch (AuthenticationException error)
        {
            return AfterFailure(ssl, SourceSyncTlsReasons.HandshakeFailed, error.Message);
        }
        catch (IOException error)
        {
            return AfterFailure(ssl, SourceSyncTlsReasons.HandshakeFailed, error.Message);
        }

        if (ssl.SslProtocol != SslProtocols.Tls13)
        {
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.HandshakeFailed,
                "The handshake completed with " + ssl.SslProtocol + "; only TLS 1.3 is accepted.");
        }

        if (ssl.RemoteCertificate is null)
        {
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.HandshakeFailed,
                "The peer completed the handshake without presenting a certificate, so there was nothing to pin.");
        }

        string seen;
        try
        {
            seen = PairingCertificate.ComputeFingerprint(ssl.RemoteCertificate);
        }
        catch (SyncContractException error)
        {
            return AfterFailure(ssl, SourceSyncTlsReasons.CertificateUnavailable, error.Message);
        }

        if (!PairingFingerprint.TryParse(seen, out PairingFingerprint actual) || !actual.Matches(pinned))
        {
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.FingerprintMismatch,
                "The certificate the peer presented does not match the pinned fingerprint, so the connection was "
                + "closed before a single envelope byte was read or written.");
        }

        return SourceSyncTlsHandshakeResult.Ok(ssl, seen);
    }

    /// <summary>Closes an encrypted stream the way the transport contract wants it: close_notify first.</summary>
    /// <remarks>
    /// The shutdown is awaited instead of blocked on, because the wait is part of the transfer: a TLS 1.3 server
    /// sends its session tickets right after the handshake, so the sender holds unread inbound bytes when the
    /// payload is complete. Closing a socket that still has unread inbound data is an <em>abortive</em> close: the
    /// peer sees a reset, and records that were queued but not yet delivered are thrown away. Under load the old
    /// synchronous 500 ms wait expired, the fallback disposed the socket, and the receiver reported io-failed for
    /// a transfer the sender had already written in full.
    /// </remarks>
    /// <param name="stream">The encrypted stream, or null when no handshake happened.</param>
    /// <param name="transport">The raw stream underneath, drained before an abortive fallback close.</param>
    /// <param name="timeout">How long the peer gets to answer the close_notify, defaulting to two seconds.</param>
    public static async Task CloseQuietlyAsync(SslStream? stream, Stream? transport = null, TimeSpan? timeout = null)
    {
        if (stream is null)
        {
            return;
        }

        TimeSpan budget = timeout is null || timeout.Value <= TimeSpan.Zero
            ? TimeSpan.FromMilliseconds(ShutdownBudgetMilliseconds)
            : timeout.Value;

        try
        {
            await stream.ShutdownAsync().WaitAsync(budget).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException
            or InvalidOperationException or OperationCanceledException or NotSupportedException
            or TimeoutException)
        {
            // The peer may already be gone; the payload is away, so a missing close_notify changes nothing.
            // Draining the raw socket first keeps the fallback close from resetting a connection whose bytes the
            // receiver has not read yet.
            await DrainQuietlyAsync(transport).ConfigureAwait(false);
        }
        finally
        {
            try
            {
                stream.Dispose();
            }
            catch (Exception exception) when (exception is IOException or ObjectDisposedException)
            {
                // Disposing a socket the peer reset is not an error the caller can act on.
            }
        }
    }

    private static async Task DrainQuietlyAsync(Stream? transport)
    {
        if (transport is null)
        {
            return;
        }

        try
        {
            using CancellationTokenSource cts = new(TimeSpan.FromMilliseconds(DrainBudgetMilliseconds));
            byte[] scratch = new byte[256];
            while (await transport.ReadAsync(scratch, cts.Token).ConfigureAwait(false) > 0)
            {
                // Unread TLS records (session tickets) are discarded so the close below is graceful.
            }
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException
            or InvalidOperationException or OperationCanceledException or NotSupportedException)
        {
            // Nothing left to drain, or the peer is gone: the close below still happens.
        }
    }

    private static SourceSyncTlsHandshakeResult Completed(SslStream ssl, string ownFingerprint)
    {
        if (ssl.SslProtocol != SslProtocols.Tls13)
        {
            // EnabledSslProtocols already restricts the handshake; this second check is what catches a platform
            // that ignores the request, and a version below 1.3 is a hard failure rather than a downgrade.
            return AfterFailure(
                ssl,
                SourceSyncTlsReasons.HandshakeFailed,
                "The handshake completed with " + ssl.SslProtocol + "; only TLS 1.3 is accepted.");
        }

        return SourceSyncTlsHandshakeResult.Ok(ssl, ownFingerprint);
    }

    private static SourceSyncTlsHandshakeResult AfterFailure(SslStream ssl, string reason, string? detail)
    {
        try
        {
            ssl.Dispose();
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException)
        {
            // A failed handshake already owns the diagnosis; a dispose hiccup must not replace it.
        }

        return SourceSyncTlsHandshakeResult.Failed(reason, detail);
    }

    private static string TimeoutDetail(TimeSpan timeout)
        => "The TLS handshake did not finish within "
            + timeout.TotalSeconds.ToString("0.#", CultureInfo.InvariantCulture)
            + " s, so the peer is not a TLS 1.3 endpoint we can pin.";
}

/// <summary>What sniffing a plaintext peer's first bytes produced.</summary>
public sealed record SourceSyncTlsProbeResult
{
    /// <summary>True when the peer is speaking plaintext and <see cref="Stream"/> may be read as frames.</summary>
    public bool Accepted { get; init; }

    /// <summary>One of <see cref="SourceSyncTlsReasons"/> when the peer is not a plaintext peer.</summary>
    public string? Reason { get; init; }

    /// <summary>Why it was refused; never contains key material.</summary>
    public string? Detail { get; init; }

    /// <summary>The stream to read frames from: the probe's bytes are replayed before the live socket.</summary>
    public Stream? Stream { get; init; }

    /// <summary>The peer is plaintext.</summary>
    public static SourceSyncTlsProbeResult Plaintext(Stream stream) => new() { Accepted = true, Stream = stream };

    /// <summary>The peer is not plaintext.</summary>
    public static SourceSyncTlsProbeResult Refused(string reason, string? detail = null) => new() { Reason = reason, Detail = detail };
}

/// <summary>
/// Recognises a TLS peer on a plaintext endpoint, so the failure names the actual mistake (W6-26).
/// </summary>
/// <remarks>
/// <para>
/// Without this, a TLS client dialling a plaintext transfer endpoint looks like a corrupt frame: its first bytes
/// are a handshake record, whose first four bytes a length-prefixed reader reads as an absurd frame length. The
/// bytes are sniffed before they are parsed, and — when they are not a TLS record — replayed to the frame reader
/// unchanged, so a plaintext exchange still sees exactly the byte stream it always saw.
/// </para>
/// <para>
/// The probe is five bytes at most, because a TLS record header is five bytes; a plaintext frame starts with a
/// big-endian length, whose first byte is 0x00 for every allowed envelope (an 8 MiB ceiling means the top byte is
/// at most 0x00), so no valid frame can begin like a handshake record.
/// </para>
/// </remarks>
public static class SourceSyncTlsProbe
{
    /// <summary>How many bytes are inspected before frames are parsed.</summary>
    public const int ProbeBytes = 5;

    /// <summary>Whether the first bytes are a TLS handshake record (content type 22, major version 3).</summary>
    public static bool LooksLikeTlsHandshake(ReadOnlySpan<byte> probe)
        => probe.Length >= 3 && probe[0] == 0x16 && probe[1] == 0x03 && probe[2] <= 0x04;

    /// <summary>Reads the first bytes and decides whether this peer is plaintext.</summary>
    /// <param name="stream">The connected plaintext stream.</param>
    /// <param name="cancellationToken">Cancels the read.</param>
    public static async Task<SourceSyncTlsProbeResult> ReadAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);

        byte[] probe = new byte[ProbeBytes];
        int read = 0;
        while (read < ProbeBytes)
        {
            int chunk = await stream.ReadAsync(probe.AsMemory(read), cancellationToken).ConfigureAwait(false);
            if (chunk <= 0)
            {
                break;
            }

            read += chunk;
        }

        if (LooksLikeTlsHandshake(probe.AsSpan(0, read)))
        {
            return SourceSyncTlsProbeResult.Refused(
                SourceSyncTlsReasons.NotEnabled,
                "The peer opened the connection with a TLS handshake record, but this transfer endpoint is "
                + "plaintext; start it with TLS enabled, or make the peer send plaintext frames.");
        }

        return SourceSyncTlsProbeResult.Plaintext(new SourceSyncTlsPrefixedStream(probe.AsSpan(0, read).ToArray(), stream));
    }
}

/// <summary>Replays the bytes the probe consumed, then reads from the live stream.</summary>
internal sealed class SourceSyncTlsPrefixedStream : Stream
{
    private readonly byte[] _prefix;
    private readonly Stream _inner;
    private int _offset;

    internal SourceSyncTlsPrefixedStream(byte[] prefix, Stream inner)
    {
        _prefix = prefix;
        _inner = inner;
    }

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => false;

    public override long Length => throw new NotSupportedException("A replayed prefix has no known length.");

    public override long Position
    {
        get => throw new NotSupportedException("A replayed prefix is not seekable.");
        set => throw new NotSupportedException("A replayed prefix is not seekable.");
    }

    public override void Flush()
    {
    }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        if (buffer.Length == 0)
        {
            return 0;
        }

        if (_offset < _prefix.Length)
        {
            int take = Math.Min(buffer.Length, _prefix.Length - _offset);
            _prefix.AsSpan(_offset, take).CopyTo(buffer);
            _offset += take;
            return take;
        }

        return _inner.Read(buffer);
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        => ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        if (buffer.Length == 0)
        {
            return ValueTask.FromResult(0);
        }

        if (_offset < _prefix.Length)
        {
            int take = Math.Min(buffer.Length, _prefix.Length - _offset);
            _prefix.AsMemory(_offset, take).CopyTo(buffer);
            _offset += take;
            return ValueTask.FromResult(take);
        }

        return _inner.ReadAsync(buffer, cancellationToken);
    }

    public override void Write(byte[] buffer, int offset, int count)
        => throw new NotSupportedException("A replayed prefix is read-only.");

    public override void Write(ReadOnlySpan<byte> buffer)
        => throw new NotSupportedException("A replayed prefix is read-only.");

    public override long Seek(long offset, SeekOrigin origin)
        => throw new NotSupportedException("A replayed prefix is not seekable.");

    public override void SetLength(long value)
        => throw new NotSupportedException("A replayed prefix is read-only.");

    protected override void Dispose(bool disposing)
    {
        // The socket belongs to the caller that created it; this wrapper only adds a prefix.
        base.Dispose(disposing);
    }
}

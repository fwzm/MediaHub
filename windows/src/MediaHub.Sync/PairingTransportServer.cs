using System;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>
/// Everything the temporary pairing listener needs to exist for one invitation.
/// </summary>
/// <remarks>
/// The defaults are the production ones: a two-minute window (ADR-S001 D6) and a five-second handshake budget.
/// <see cref="Certificate"/>, <see cref="AdvertisedFingerprint"/>, <see cref="Challenge"/> and <see cref="Clock"/>
/// exist so tests can drive the failure branches without waiting for real time or real entropy.
/// </remarks>
public sealed class PairingTransportServerOptions
{
    /// <summary>Identifier advertised in the invitation and shown on both screens.</summary>
    public required string DeviceId { get; init; }

    /// <summary>How long the listener stays alive. Defaults to the invitation lifetime (120 s).</summary>
    public TimeSpan Lifetime { get; init; } = TimeSpan.FromSeconds(PairingPayload.LifetimeSeconds);

    /// <summary>How long a peer may take to complete the TLS handshake before the accept call gives up.</summary>
    public TimeSpan HandshakeTimeout { get; init; } = TimeSpan.FromSeconds(5);

    /// <summary>
    /// Certificate to serve. Left null, the server creates an ephemeral self-signed one. A supplied certificate
    /// stays owned by the caller: the server never disposes it.
    /// </summary>
    public X509Certificate2? Certificate { get; init; }

    /// <summary>
    /// Fingerprint the invitation advertises, when it differs from the certificate's. Only tests set this, to
    /// reproduce a stale QR code whose pin no longer matches the certificate; production always advertises the
    /// fingerprint of the certificate it serves.
    /// </summary>
    public string? AdvertisedFingerprint { get; init; }

    /// <summary>Challenge to advertise. Left null, a fresh random one is drawn.</summary>
    public PairingChallenge? Challenge { get; init; }

    /// <summary>Source of "now" in Unix milliseconds. Only tests set this.</summary>
    public Func<long>? Clock { get; init; }
}

/// <summary>
/// The temporary, loopback-only TLS 1.3 listener one pairing needs (ADR-S003).
/// </summary>
/// <remarks>
/// <para>
/// Lifecycle: <see cref="Start"/> binds <c>127.0.0.1</c> on an ephemeral port and publishes an invitation whose
/// <c>ephemeralUrl</c> and fingerprint point at that socket; <see cref="AcceptOneAsync"/> serves exactly one peer;
/// <see cref="Stop"/> (or disposal) closes everything. The listener never binds a wildcard address, never opens a
/// firewall port and never outlives the invitation: those are the reasons ADR-S001 D6 can promise "pairing is a
/// short, local, user-visible event".
/// </para>
/// <para>
/// One-shot is enforced three ways, because each covers a different race: the socket is closed as soon as a peer
/// has its payload, a second <see cref="AcceptOneAsync"/> returns
/// <see cref="PairingTransportReasons.AlreadyPaired"/> without touching the socket, and two concurrent accept calls
/// cannot both proceed (the second loses an <see cref="Interlocked.CompareExchange(ref int, int, int)"/>).
/// </para>
/// <para>
/// Thread safety: <see cref="Stop"/> may be called from any thread while an accept call is in flight; the accept
/// call then returns a failure result instead of throwing. Nothing runs in the background after
/// <see cref="Stop"/>: no accept task, no timer beyond the lifetime token source, no reading thread.
/// </para>
/// </remarks>
public sealed class PairingTransportServer : IDisposable, IAsyncDisposable
{
    private const int ListenBacklog = 4;

    private readonly object _gate = new();
    private readonly TcpListener _listener;
    private readonly X509Certificate2 _certificate;
    private readonly bool _ownsCertificate;
    private readonly string _certificateFingerprint;
    private readonly bool _certificateMatchesAdvertisement;
    private readonly CancellationTokenSource _lifetime;
    private readonly TimeSpan _handshakeTimeout;
    private TcpClient? _connection;
    private SslStream? _stream;
    private int _pairing;
    private int _paired;
    private int _stopped;
    private bool _disposed;

    private PairingTransportServer(
        TcpListener listener,
        X509Certificate2 certificate,
        bool ownsCertificate,
        string certificateFingerprint,
        PairingPayload invitation,
        TimeSpan lifetime,
        TimeSpan handshakeTimeout)
    {
        _listener = listener;
        _certificate = certificate;
        _ownsCertificate = ownsCertificate;
        _certificateFingerprint = certificateFingerprint;
        _certificateMatchesAdvertisement =
            PairingFingerprint.Parse(certificateFingerprint).Matches(invitation.ParseFingerprint());
        Invitation = invitation;
        _handshakeTimeout = handshakeTimeout;
        _lifetime = new CancellationTokenSource(lifetime);

        var endPoint = (IPEndPoint)listener.LocalEndpoint;
        EndPoint = endPoint;
        Endpoint = new Uri("https://127.0.0.1:" + endPoint.Port.ToString(CultureInfo.InvariantCulture) + "/pairing");
    }

    /// <summary>The address the listener owns; always a loopback address.</summary>
    public IPEndPoint EndPoint { get; }

    /// <summary>The URL the invitation advertises, matching <see cref="EndPoint"/>.</summary>
    public Uri Endpoint { get; }

    /// <summary>Lowercase hex SHA-256 of the served certificate — the value a peer must pin.</summary>
    public string Fingerprint => _certificateFingerprint;

    /// <summary>The invitation to render as a QR code: ephemeral URL, one-time challenge, fingerprint, expiry.</summary>
    public PairingPayload Invitation { get; }

    /// <summary>True once one peer has received the payload.</summary>
    public bool IsPaired => Volatile.Read(ref _paired) != 0;

    /// <summary>True once the listener is closed.</summary>
    public bool IsStopped => Volatile.Read(ref _stopped) != 0;

    /// <summary>Binds the loopback listener and publishes the invitation.</summary>
    /// <exception cref="ArgumentException"><c>DeviceId</c> is empty.</exception>
    /// <exception cref="ArgumentOutOfRangeException">A timeout or the lifetime is not positive.</exception>
    /// <exception cref="SocketException">The loopback listener could not be created.</exception>
    public static PairingTransportServer Start(PairingTransportServerOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        if (string.IsNullOrWhiteSpace(options.DeviceId))
        {
            throw new ArgumentException("A pairing server needs a device id.", nameof(options));
        }

        if (options.Lifetime <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(options), options.Lifetime, "The lifetime must be positive.");
        }

        if (options.HandshakeTimeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(options), options.HandshakeTimeout, "The handshake budget must be positive.");
        }

        bool ownsCertificate = options.Certificate is null;
        X509Certificate2 certificate = options.Certificate ?? PairingCertificate.CreateEphemeral(options.DeviceId);
        var listener = new TcpListener(IPAddress.Loopback, 0);
        try
        {
            listener.Start(ListenBacklog);

            string fingerprint = PairingCertificate.ComputeFingerprint(certificate);
            string advertised = options.AdvertisedFingerprint ?? fingerprint;
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            string url = "https://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/pairing";

            long now = (options.Clock ?? DefaultClock)();
            PairingPayload invitation = PairingPayload.Create(
                options.DeviceId,
                url,
                options.Challenge ?? PairingChallenge.Create(),
                PairingFingerprint.Parse(advertised),
                now);

            return new PairingTransportServer(
                listener,
                certificate,
                ownsCertificate,
                fingerprint,
                invitation,
                options.Lifetime,
                options.HandshakeTimeout);
        }
        catch
        {
            listener.Stop();
            if (ownsCertificate)
            {
                certificate.Dispose();
            }

            throw;
        }
    }

    /// <summary>
    /// Accepts one peer, pins its certificate against the invitation, and sends the payload.
    /// </summary>
    /// <remarks>
    /// Returns instead of throwing for every peer-caused outcome, because "the peer failed to pair" is an
    /// expected result of a pairing attempt, not an exceptional one. The only exceptions are caller bugs
    /// (disposed server, empty reason) and cancellation that the caller itself requested through
    /// <paramref name="cancellationToken"/>, which is reported as <see cref="PairingTransportReasons.PeerTimeout"/>.
    /// </remarks>
    public async Task<PairingTransportResult> AcceptOneAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        if (Volatile.Read(ref _paired) != 0)
        {
            return PairingTransportResult.AlreadyPaired();
        }

        if (Volatile.Read(ref _stopped) != 0)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }

        if (Interlocked.CompareExchange(ref _pairing, 1, 0) != 0)
        {
            // Another accept call is already serving a peer. There is exactly one invitation, so this one is
            // not "busy, try later" — it is the same AlreadyPaired answer a second connection would get.
            return PairingTransportResult.AlreadyPaired();
        }

        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token, cancellationToken);
        TcpClient? client = null;
        try
        {
            if (linked.IsCancellationRequested)
            {
                return PairingTransportResult.Failure(CancellationReason(cancellationToken));
            }

            client = await _listener.AcceptTcpClientAsync(linked.Token).ConfigureAwait(false);
            lock (_gate)
            {
                _connection = client;
            }

            var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
            lock (_gate)
            {
                _stream = ssl;
            }

            SslProtocols protocol;
            using (var handshake = CancellationTokenSource.CreateLinkedTokenSource(linked.Token))
            {
                handshake.CancelAfter(_handshakeTimeout);
                try
                {
                    await ssl.AuthenticateAsServerAsync(
                        new SslServerAuthenticationOptions
                        {
                            ServerCertificate = _certificate,
                            // Pinning replaces chain validation; requiring the client to present a certificate
                            // would make pairing asymmetric for no gain.
                            ClientCertificateRequired = false,
                            // The whole point of ADR-S003 D2: refuse to talk to anything below TLS 1.3.
                            EnabledSslProtocols = SslProtocols.Tls13,
                            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                        },
                        handshake.Token).ConfigureAwait(false);
                }
                catch (AuthenticationException)
                {
                    return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
                }
                catch (IOException)
                {
                    return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
                }
                catch (OperationCanceledException)
                {
                    return FailAfterCancellation(cancellationToken);
                }

                // EnabledSslProtocols already restricts the handshake; this second check is what catches a
                // platform that ignores the request. A negotiated version below 1.3 is a hard failure.
                protocol = ssl.SslProtocol;
                if (protocol != SslProtocols.Tls13)
                {
                    return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
                }
            }

            // Stale QR code: the invitation pins a fingerprint that is not this certificate's. Say so and close
            // before a single payload byte is written — sending the payload anyway would teach the caller that
            // the pin is advisory, which is exactly the downgrade ADR-S001 D6 forbids.
            if (!_certificateMatchesAdvertisement)
            {
                return PairingTransportResult.Failure(PairingTransportReasons.FingerprintMismatch);
            }

            byte[] frame = Encoding.UTF8.GetBytes(PairingPayloadCodec.Serialize(Invitation));
            await PairingFrame.WriteAsync(ssl, frame, linked.Token).ConfigureAwait(false);
            await ssl.FlushAsync(linked.Token).ConfigureAwait(false);

            // One-shot: stop listening first, so a second peer cannot even reach the handshake, then let the
            // peer close cleanly. The payload is already written; nothing below can change that.
            Interlocked.Exchange(ref _paired, 1);
            StopAccepting();

            try
            {
                await ssl.ShutdownAsync().WaitAsync(TimeSpan.FromMilliseconds(500)).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // The peer may already have closed. The payload is away; a missing close_notify changes nothing.
            }

            return PairingTransportResult.Success(Invitation, protocol);
        }
        catch (OperationCanceledException)
        {
            return FailAfterCancellation(cancellationToken);
        }
        catch (IOException)
        {
            // The peer vanished mid-exchange (reset, closed before the frame was fully written).
            return PairingTransportResult.Failure(PairingTransportReasons.PeerTimeout);
        }
        catch (SocketException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (ObjectDisposedException)
        {
            // Stop() won the race against the accept call.
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        finally
        {
            Volatile.Write(ref _pairing, 0);
            if (client is not null)
            {
                lock (_gate)
                {
                    DisposeQuietly(_stream);
                    _stream = null;
                    try
                    {
                        client.Dispose();
                    }
                    catch (ObjectDisposedException)
                    {
                    }

                    _connection = null;
                }
            }
        }
    }

    /// <summary>Closes the listener and any in-flight connection. Idempotent, callable from any thread.</summary>
    public void Stop()
    {
        StopAccepting();

        try
        {
            _lifetime.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }

        lock (_gate)
        {
            DisposeQuietly(_stream);
            _stream = null;
            _connection?.Dispose();
            _connection = null;
        }
    }

    /// <summary>
    /// Closes the listening socket without touching the connection being served. Called once a peer has its
    /// payload (the invitation is spent) and by <see cref="Stop"/>; idempotent.
    /// </summary>
    private void StopAccepting()
    {
        Interlocked.Exchange(ref _stopped, 1);
        try
        {
            _listener.Stop();
        }
        catch (SocketException)
        {
        }
    }

    /// <summary>
    /// Maps a cancellation to its reason. An expired invitation also closes the listener: a dead invitation must
    /// not keep a socket open, and a retry after the lifetime is meaningless.
    /// </summary>
    private PairingTransportResult FailAfterCancellation(CancellationToken callerToken)
    {
        string reason = CancellationReason(callerToken);
        if (string.Equals(reason, PairingTransportReasons.LifetimeExceeded, StringComparison.Ordinal))
        {
            Stop();
        }

        return PairingTransportResult.Failure(reason);
    }

    /// <inheritdoc />
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Stop();
        _lifetime.Dispose();
        if (_ownsCertificate)
        {
            _certificate.Dispose();
        }
    }

    /// <inheritdoc />
    public ValueTask DisposeAsync()
    {
        Dispose();
        return ValueTask.CompletedTask;
    }

    private static long DefaultClock() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static void DisposeQuietly(SslStream? stream)
    {
        if (stream is null)
        {
            return;
        }

        try
        {
            stream.Dispose();
        }
        catch (IOException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    /// <summary>
    /// Why the wait ended. The caller's own cancellation wins over the lifetime, and an explicit
    /// <see cref="Stop"/> is reported as a closed listener rather than as an expired invitation.
    /// </summary>
    private string CancellationReason(CancellationToken callerToken)
    {
        if (callerToken.IsCancellationRequested)
        {
            return PairingTransportReasons.PeerTimeout;
        }

        return Volatile.Read(ref _stopped) != 0
            ? PairingTransportReasons.ListenerFailed
            : PairingTransportReasons.LifetimeExceeded;
    }
}

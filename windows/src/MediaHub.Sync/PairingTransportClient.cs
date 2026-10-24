using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

/// <summary>
/// What a peer needs to reach an invitation's <c>ephemeralUrl</c> and check who answers.
/// </summary>
public sealed class PairingTransportClientOptions
{
    /// <summary>The invitation's <c>ephemeralUrl</c>. Must be https.</summary>
    public required Uri Endpoint { get; init; }

    /// <summary>The invitation's <c>publicKeyFingerprint</c>, lowercase or uppercase hex.</summary>
    public required string ExpectedFingerprint { get; init; }

    /// <summary>Total budget for connect, handshake and payload read.</summary>
    public TimeSpan Timeout { get; init; } = TimeSpan.FromSeconds(10);

    /// <summary>
    /// Protocol versions to offer. The default is the production value; tests lower it to prove the server
    /// refuses a TLS 1.2 peer instead of silently negotiating something weaker.
    /// </summary>
    public SslProtocols EnabledProtocols { get; init; } = SslProtocols.Tls13;

    /// <summary>
    /// Card to send about ourselves after the payload is verified. Left null the exchange stays one-way — the
    /// peer then cannot remember us, which is exactly the pre-W6-12 behaviour.
    /// </summary>
    public PairingPeerCard? Reply { get; init; }

    /// <summary>Budget for the optional card exchange (write ours, read theirs).</summary>
    public TimeSpan ReplyTimeout { get; init; } = TimeSpan.FromSeconds(5);
}

/// <summary>
/// The peer side of a pairing exchange: connect, pin the certificate, read the payload (ADR-S003).
/// </summary>
/// <remarks>
/// <para>
/// Certificate validation is deliberately replaced by pinning. The callback returns true for any certificate and
/// the real decision is the fingerprint comparison right after the handshake — the certificate chain is not
/// consulted at all, because the QR code, not a CA, is the trust anchor. Doing it this way means the check cannot
/// be skipped by a platform that decides the certificate is "fine".
/// </para>
/// <para>
/// A mismatch closes the connection without reading a byte of payload, so a peer that cannot prove its identity
/// cannot even observe what it might have received from us.
/// </para>
/// </remarks>
public static class PairingTransportClient
{
    private const string TargetHostName = "mediahub-pairing";

    /// <summary>Runs one exchange against the endpoint described by <paramref name="options"/>.</summary>
    public static async Task<PairingTransportResult> ConnectAsync(
        PairingTransportClientOptions options,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        if (options.Endpoint is null || !string.Equals(options.Endpoint.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return PairingTransportResult.Failure(PairingReasons.BadUrl);
        }

        if (!PairingFingerprint.TryParse(options.ExpectedFingerprint, out PairingFingerprint pinned))
        {
            return PairingTransportResult.Failure(PairingReasons.BadFingerprint);
        }

        if (options.Timeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(options), options.Timeout, "The timeout must be positive.");
        }

        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(options.Timeout);

        bool handshakeCompleted = false;
        try
        {
            using var tcp = new TcpClient();
            await tcp.ConnectAsync(options.Endpoint.Host, options.Endpoint.Port, budget.Token).ConfigureAwait(false);

            using var ssl = new SslStream(
                tcp.GetStream(),
                leaveInnerStreamOpen: false,
                // Identity is the pinned fingerprint, checked below; the chain is irrelevant here.
                (sender, certificate, chain, errors) => true);

            await ssl.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions
                {
                    TargetHost = TargetHostName,
                    EnabledSslProtocols = options.EnabledProtocols,
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                },
                budget.Token).ConfigureAwait(false);

            handshakeCompleted = true;
            if (ssl.SslProtocol != SslProtocols.Tls13)
            {
                return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
            }

            if (ssl.RemoteCertificate is null)
            {
                return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
            }

            string seen = PairingCertificate.ComputeFingerprint(ssl.RemoteCertificate);
            if (!PairingFingerprint.Parse(seen).Matches(pinned))
            {
                return PairingTransportResult.Failure(PairingTransportReasons.FingerprintMismatch);
            }

            byte[]? frame = await PairingFrame.ReadAsync(ssl, budget.Token).ConfigureAwait(false);
            if (frame is null)
            {
                return PairingTransportResult.Failure(PairingTransportReasons.PayloadInvalid);
            }

            if (!PairingPayloadCodec.TryDeserialize(Encoding.UTF8.GetString(frame), out PairingPayload? payload, out _)
                || payload is null)
            {
                return PairingTransportResult.Failure(PairingTransportReasons.PayloadInvalid);
            }

            // Second, redundant check: the payload repeats the fingerprint we just pinned. A peer that answers
            // with someone else's invitation is not the peer we dialled.
            if (!payload.ParseFingerprint().Matches(pinned))
            {
                return PairingTransportResult.Failure(PairingTransportReasons.FingerprintMismatch);
            }

            // Optional second leg, only after the pin and the payload both checked out: never tell a peer who we
            // are before we know who it is. A card that never arrives is not a failure — the pairing stands, we
            // simply cannot remember the other side.
            PairingPeerCard? peerCard = null;
            if (options.Reply is { } reply)
            {
                peerCard = await ExchangeCardsAsync(ssl, reply, budget.Token, options.ReplyTimeout).ConfigureAwait(false);
            }

            return PairingTransportResult.Success(payload, ssl.SslProtocol, peerCard);
        }
        catch (AuthenticationException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.TlsVersion);
        }
        catch (IOException)
        {
            // Before the handshake an IO error is a protocol failure; afterwards it means the peer closed
            // before a whole frame arrived.
            return PairingTransportResult.Failure(
                handshakeCompleted ? PairingTransportReasons.PayloadInvalid : PairingTransportReasons.TlsVersion);
        }
        catch (SocketException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.ListenerFailed);
        }
        catch (OperationCanceledException)
        {
            return PairingTransportResult.Failure(PairingTransportReasons.PeerTimeout);
        }
    }

    /// <summary>Sends our card and reads the peer's; null when the peer does not answer in time.</summary>
    private static async Task<PairingPeerCard?> ExchangeCardsAsync(
        SslStream ssl,
        PairingPeerCard card,
        CancellationToken token,
        TimeSpan timeout)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(token);
        budget.CancelAfter(timeout);
        try
        {
            byte[] payload = Encoding.UTF8.GetBytes(PairingPeerCardCodec.Serialize(card));
            await PairingFrame.WriteAsync(ssl, payload, budget.Token).ConfigureAwait(false);
            await ssl.FlushAsync(budget.Token).ConfigureAwait(false);
            byte[]? reply = await PairingFrame.ReadAsync(ssl, budget.Token).ConfigureAwait(false);
            if (reply is null)
            {
                return null;
            }

            return PairingPeerCardCodec.TryDeserialize(Encoding.UTF8.GetString(reply), out PairingPeerCard? peerCard, out _)
                ? peerCard
                : null;
        }
        catch (Exception exception) when (exception is IOException or OperationCanceledException or ObjectDisposedException)
        {
            return null;
        }
    }
}

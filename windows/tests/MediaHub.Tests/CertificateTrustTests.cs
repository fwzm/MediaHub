using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the per-server certificate trust mechanism.
/// </summary>
/// <remarks>
/// The mechanism exists so a self-signed server can be reached without ever accepting all certificates:
/// the platform validator still runs, and a pin is only consulted when it has already said no. These
/// tests pin the fingerprint normalisation, the callback's three branches, and — against a real TLS
/// listener — that an unpinned source fails, a pinned source connects, and the inspector never writes a
/// request.
/// </remarks>
public sealed class CertificateTrustTests
{
    private const string Sha = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    // ---------------------------------------------------------------- fingerprint

    [Theory]
    [InlineData(Sha)]
    [InlineData("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")]
    [InlineData("01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF")]
    [InlineData(" 01 23 45 67 89 AB CD EF 01 23 45 67 89 AB CD EF 01 23 45 67 89 AB CD EF 01 23 45 67 89 AB CD EF ")]
    public void Fingerprints_are_normalised_to_upper_hex(string input)
        => Assert.Equal(Sha, CertificateTrust.NormalizeFingerprint(input));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("0123")]
    [InlineData("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDE")]
    [InlineData("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF00")]
    [InlineData("ZZ23456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF")]
    public void Invalid_fingerprints_are_rejected(string? input)
        => Assert.Null(CertificateTrust.NormalizeFingerprint(input));

    // ---------------------------------------------------------------- callback

    [Fact]
    public void A_platform_trusted_certificate_is_accepted_without_reading_the_pin()
    {
        using X509Certificate2 certificate = CreateSelfSigned("mediahub-callback");
        RemoteCertificateValidationCallback callback = CertificateTrust.CreateValidationCallback(Sha);

        Assert.True(callback(this, certificate, null, SslPolicyErrors.None));
    }

    [Fact]
    public void Only_the_pinned_certificate_is_accepted_when_the_chain_is_untrusted()
    {
        using X509Certificate2 certificate = CreateSelfSigned("mediahub-callback");
        string actual = CertificateTrust.ComputeSha256(certificate)!;

        Assert.True(CertificateTrust.CreateValidationCallback(actual)(
            this, certificate, null, SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(CertificateTrust.CreateValidationCallback(Sha)(
            this, certificate, null, SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(CertificateTrust.CreateValidationCallback(actual)(
            this, null, null, SslPolicyErrors.RemoteCertificateChainErrors));
    }

    [Fact]
    public void A_policy_with_a_malformed_pin_is_refused_before_a_handler_is_built()
    {
        var policy = new NetworkPolicy { TrustedCertificateSha256 = "not-a-fingerprint" };
        Assert.Throws<ArgumentException>(policy.Validate);

        var valid = new NetworkPolicy { TrustedCertificateSha256 = Sha };
        valid.Validate(); // must not throw
    }

    [Fact]
    public void A_per_server_exemption_cannot_be_combined_with_a_pin()
    {
        var policy = new NetworkPolicy
        {
            TrustedCertificateSha256 = Sha,
            AllowUntrustedCertificate = true,
        };
        Assert.Throws<ArgumentException>(policy.Validate);
    }

    [Fact]
    public async Task An_explicitly_exempted_source_connects_without_a_pin()
    {
        using X509Certificate2 certificate = CreateSelfSigned("mediahub-test");

        await using var server = new TlsProbeServer(certificate, respond: true);
        using var exempt = new HttpClient(NetworkPolicyHandlerFactory.CreateHandler(
            new NetworkPolicy { Mode = ProxyMode.Direct, AllowUntrustedCertificate = true }));

        Assert.Equal("ok", await exempt.GetStringAsync(server.BaseUri));
    }

    // ---------------------------------------------------------------- live TLS

    [Fact]
    public async Task An_unpinned_source_cannot_connect_and_a_pinned_one_can()
    {
        using X509Certificate2 certificate = CreateSelfSigned("mediahub-test");
        string sha256 = CertificateTrust.ComputeSha256(certificate)!;

        await using (var server = new TlsProbeServer(certificate, respond: true))
        {
            using var pinned = new HttpClient(NetworkPolicyHandlerFactory.CreateHandler(
                new NetworkPolicy { Mode = ProxyMode.Direct, TrustedCertificateSha256 = sha256 }));

            string body;
            try
            {
                body = await pinned.GetStringAsync(server.BaseUri);
            }
            catch (HttpRequestException ex)
            {
                Assert.Fail("pinned request failed: " + ex.Message + " / server: " + server.LastError);
                throw;
            }

            Assert.Equal("ok", body);
        }

        await using (var server = new TlsProbeServer(certificate, respond: true))
        {
            using var unpinned = new HttpClient(NetworkPolicyHandlerFactory.CreateHandler(
                new NetworkPolicy { Mode = ProxyMode.Direct }));

            await Assert.ThrowsAsync<HttpRequestException>(() => unpinned.GetStringAsync(server.BaseUri));
        }
    }

    [Fact]
    public async Task The_inspector_reports_the_certificate_without_sending_a_request()
    {
        using X509Certificate2 certificate = CreateSelfSigned("mediahub-test");
        string sha256 = CertificateTrust.ComputeSha256(certificate)!;

        await using var server = new TlsProbeServer(certificate, respond: true);
        PresentedCertificate? presented = await CertificateInspector.InspectAsync(
            server.BaseUri, TimeSpan.FromSeconds(15));

        Assert.True(presented is not null, "inspector returned null; server error: " + server.LastError);
        Assert.Equal(sha256, presented!.Sha256);
        Assert.True(presented.UntrustedChain);
        // The certificate names "mediahub-test", the connection went to 127.0.0.1.
        Assert.True(presented.NameMismatch);
        Assert.False(server.ReceivedApplicationBytes);
    }

    // ---------------------------------------------------------------- helpers

    /// <summary>
    /// One-shot TLS listener on loopback that serves a canned 200 response.
    /// </summary>
    /// <remarks>
    /// A real listener rather than a mocked handler: the property under test is the TLS handshake
    /// itself, which no mock of <see cref="HttpMessageHandler"/> can exercise.
    /// </remarks>
    private sealed class TlsProbeServer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly X509Certificate2 _certificate;
        private readonly bool _respond;
        private readonly CancellationTokenSource _cts = new();
        private readonly Task _accept;

        public TlsProbeServer(X509Certificate2 certificate, bool respond)
        {
            _certificate = certificate;
            _respond = respond;
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            int port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            BaseUri = new Uri("https://127.0.0.1:" + port + "/");
            _accept = Task.Run(AcceptAsync);
        }

        public Uri BaseUri { get; }

        /// <summary>True once the client wrote anything after a completed handshake.</summary>
        public bool ReceivedApplicationBytes { get; private set; }

        /// <summary>What the server-side handshake failed with, for test diagnostics only.</summary>
        public Exception? LastError { get; private set; }

        private async Task AcceptAsync()
        {
            try
            {
                using TcpClient client = await _listener.AcceptTcpClientAsync(_cts.Token);
                await using var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
                try
                {
                    await ssl.AuthenticateAsServerAsync(_certificate, clientCertificateRequired: false,
                        checkCertificateRevocation: false);
                }
                catch (Exception ex)
                {
                    // Expected for the inspector (it aborts) and for the unpinned client.
                    LastError = ex;
                    return;
                }

                if (!_respond) return;

                var buffer = new byte[4096];
                int read;
                try
                {
                    read = await ssl.ReadAsync(buffer, _cts.Token);
                }
                catch
                {
                    return;
                }

                ReceivedApplicationBytes = read > 0;
                byte[] response = Encoding.ASCII.GetBytes(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
                await ssl.WriteAsync(response, _cts.Token);
                await ssl.FlushAsync(_cts.Token);
            }
            catch
            {
                // Listener teardown; nothing to report.
            }
        }

        public async ValueTask DisposeAsync()
        {
            _cts.Cancel();
            _listener.Stop();
            try
            {
                await _accept.ConfigureAwait(false);
            }
            catch
            {
                // Already observed.
            }
            _cts.Dispose();
        }
    }

    private static X509Certificate2 CreateSelfSigned(string commonName)
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=" + commonName, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));

        using X509Certificate2 generated = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));

        // Reload from PFX so the returned certificate owns its own private key instead of borrowing the
        // RSA instance that is about to be disposed. SChannel — the Windows TLS provider — refuses
        // ephemeral keys when acting as a server, so the key has to live in the user key store for the
        // lifetime of the certificate.
        byte[] pfx = generated.Export(X509ContentType.Pfx, "mediahub-test");
        return X509CertificateLoader.LoadPkcs12(pfx, "mediahub-test", X509KeyStorageFlags.UserKeySet);
    }
}

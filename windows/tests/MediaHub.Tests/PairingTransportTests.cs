using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the W6-3b pairing transport (ADR-S003): loopback-only listener, TLS 1.3, certificate pinning, one-shot
/// lifetime, and the stable reason codes.
/// </summary>
/// <remarks>
/// <para>
/// These tests use real sockets on 127.0.0.1 with kernel-assigned ports, because the properties under test are
/// exactly the ones a fake cannot have: the protocol version that was negotiated, the certificate the peer
/// actually presented, and the fact that the port is gone afterwards. Every test disposes its listener, so no
/// socket or thread outlives it, and no test binds a fixed port.
/// </para>
/// <para>
/// Each refusal test asserts the reason code rather than just "it failed": with a shared code set, a wrong
/// branch is invisible if all the test checks is <c>Paired == false</c>.
/// </para>
/// </remarks>
public sealed class PairingTransportTests
{
    private static readonly TimeSpan Budget = TimeSpan.FromSeconds(10);

    private const string DeviceId = "device-a";

    [Fact]
    public async Task HappyPath_ClientPinsTheCertificateAndReceivesTheSixFieldPayload()
    {
        using PairingTransportServer server = StartServer();
        Assert.False(server.IsPaired);

        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.True(served.Paired, served.Reason);
        Assert.True(client.Paired, client.Reason);
        Assert.Null(client.Reason);

        PairingPayload payload = Assert.IsType<PairingPayload>(client.Payload);
        Assert.Equal(DeviceId, payload.DeviceId);
        Assert.Equal(server.Invitation.Challenge, payload.Challenge);
        Assert.Equal(server.Fingerprint, payload.PublicKeyFingerprint);
        Assert.Equal(server.Endpoint.ToString(), payload.EphemeralUrl);
        payload.ValidateShape();

        Assert.True(server.IsPaired);
    }

    [Fact]
    public async Task HappyPath_BothEndsNegotiateTls13()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();

        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.Equal(SslProtocols.Tls13, client.Protocol);
        Assert.Equal(SslProtocols.Tls13, served.Protocol);
    }

    [Fact]
    public async Task StaleInvitation_ServerRefusesToSendWhenItsCertificateIsNotThePinnedOne()
    {
        // A QR code printed for an older certificate: the invitation advertises a fingerprint the listener does
        // not own. The server must notice before writing a single payload byte.
        string stale = Pin('b');
        using PairingTransportServer server = StartServer(advertisedFingerprint: stale);
        Assert.Equal(stale, server.Invitation.PublicKeyFingerprint);
        Assert.NotEqual(stale, server.Fingerprint);

        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server, stale)).WaitAsync(Budget);
        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.False(served.Paired);
        Assert.Equal(PairingTransportReasons.FingerprintMismatch, served.Reason);

        Assert.False(client.Paired);
        Assert.Equal(PairingTransportReasons.FingerprintMismatch, client.Reason);
        Assert.Null(client.Payload);
    }

    [Fact]
    public async Task Client_RefusesAPeerWhoseCertificateDoesNotMatchThePin()
    {
        using PairingTransportServer server = StartServer();
        string wrongPin = Pin('c');
        Assert.NotEqual(wrongPin, server.Fingerprint);

        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server, wrongPin)).WaitAsync(Budget);

        // The server is self-consistent, so it may well have written its payload into the socket; what matters
        // is that the client refuses it instead of warning and continuing.
        await accept.WaitAsync(Budget);
        Assert.False(client.Paired);
        Assert.Equal(PairingTransportReasons.FingerprintMismatch, client.Reason);
        Assert.Null(client.Payload);
    }

    [Fact]
    public async Task Tls12Client_IsRejectedInsteadOfDowngraded()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();

        PairingTransportResult client = await PairingTransportClient.ConnectAsync(
            ClientOptions(server, protocols: SslProtocols.Tls12)).WaitAsync(Budget);
        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.False(client.Paired);
        Assert.Equal(PairingTransportReasons.TlsVersion, client.Reason);
        Assert.False(served.Paired);
        Assert.Equal(PairingTransportReasons.TlsVersion, served.Reason);
    }

    [Fact]
    public async Task IdleListener_CancelsWithinTheCallerBudgetAndReleasesThePort()
    {
        using PairingTransportServer server = StartServer();
        int port = server.EndPoint.Port;

        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        Stopwatch watch = Stopwatch.StartNew();
        PairingTransportResult result = await server.AcceptOneAsync(cancel.Token).WaitAsync(Budget);
        watch.Stop();

        Assert.False(result.Paired);
        Assert.Equal(PairingTransportReasons.PeerTimeout, result.Reason);
        Assert.True(
            watch.Elapsed < TimeSpan.FromSeconds(2),
            "The accept call took " + watch.Elapsed + " to notice the cancellation, so it is not waking up promptly.");

        server.Stop();
        await AssertCanBindLoopbackAsync(port);
    }

    [Fact]
    public async Task ExpiredInvitation_StopsTheListenerWithLifetimeExceeded()
    {
        using PairingTransportServer server = StartServer(lifetime: TimeSpan.FromMilliseconds(300));
        int port = server.EndPoint.Port;

        Stopwatch watch = Stopwatch.StartNew();
        PairingTransportResult result = await server.AcceptOneAsync().WaitAsync(Budget);
        watch.Stop();

        Assert.False(result.Paired);
        Assert.Equal(PairingTransportReasons.LifetimeExceeded, result.Reason);
        Assert.True(
            watch.Elapsed < TimeSpan.FromSeconds(2),
            "The accept call took " + watch.Elapsed + " to notice the expired invitation.");
        Assert.True(server.IsStopped);

        await AssertCanBindLoopbackAsync(port);
    }

    [Fact]
    public async Task SecondAcceptAfterPairing_ReportsAlreadyPaired()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        Assert.True(client.Paired, client.Reason);
        await accept.WaitAsync(Budget);

        PairingTransportResult second = await server.AcceptOneAsync().WaitAsync(Budget);

        Assert.False(second.Paired);
        Assert.Equal(PairingTransportReasons.AlreadyPaired, second.Reason);
        Assert.Null(second.Payload);
    }

    [Fact]
    public async Task AfterPairing_TheListenerIsClosedAndASecondPeerCannotConnect()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        Assert.True(client.Paired, client.Reason);
        await accept.WaitAsync(Budget);

        await AssertConnectionRefusedAsync(server.EndPoint.Port);
    }

    [Fact]
    public async Task DisposingTheListener_LeavesThePortRebindable()
    {
        int port;
        using (PairingTransportServer server = StartServer())
        {
            port = server.EndPoint.Port;
            server.Dispose();
            server.Dispose();
        }

        // A closed listener must really be closed: binding the same port again is what "no leak" means here.
        await AssertCanBindLoopbackAsync(port);
    }

    [Fact]
    public async Task ConcurrentPeers_OnlyOneIsAdmitted()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();

        Task<PairingTransportResult> first = PairingTransportClient.ConnectAsync(ClientOptions(server));
        Task<PairingTransportResult> second = PairingTransportClient.ConnectAsync(ClientOptions(server));

        PairingTransportResult[] clients = await Task.WhenAll(first, second).WaitAsync(Budget);
        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.True(served.Paired, served.Reason);
        Assert.Equal(1, clients.Count(result => result.Paired));
        Assert.All(clients.Where(result => !result.Paired), result => Assert.NotNull(result.Reason));
    }

    [Fact]
    public async Task PayloadOnTheWire_HasExactlyTheSixContractFieldsAndNoCredentialKeys()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        await accept.WaitAsync(Budget);

        PairingPayload payload = Assert.IsType<PairingPayload>(client.Payload);
        string json = PairingPayloadCodec.Serialize(payload);
        using JsonDocument document = JsonDocument.Parse(json);
        string[] names = document.RootElement.EnumerateObject().Select(property => property.Name).ToArray();

        Assert.Equal(
            new[] { "schemaVersion", "deviceId", "ephemeralUrl", "challenge", "publicKeyFingerprint", "expiresAtEpochMs" },
            names);

        // Structural rather than a word list: every value is a scalar, so no nested object can smuggle a
        // credential bag past the six-field check (ADR-S001 D7).
        Assert.All(
            document.RootElement.EnumerateObject(),
            property => Assert.True(
                property.Value.ValueKind is JsonValueKind.String or JsonValueKind.Number,
                "Field " + property.Name + " is not a scalar."));
    }

    [Fact]
    public void ReasonCodes_AreTheDocumentedStableSet()
    {
        Assert.Equal(
            new[]
            {
                "listener-failed",
                "tls-version",
                "fingerprint-mismatch",
                "payload-invalid",
                "lifetime-exceeded",
                "already-paired",
                "peer-timeout",
            },
            PairingTransportReasons.All);

        Assert.Equal(PairingTransportReasons.All.Count, PairingTransportReasons.All.Distinct().Count());
        foreach (string code in PairingTransportReasons.All)
        {
            Assert.Matches("^[a-z]+(-[a-z]+)*$", code);
            PairingTransportResult result = PairingTransportResult.Failure(code);
            Assert.False(result.Paired);
            Assert.Equal(code, result.Reason);
            Assert.Equal("pairing-transport: failed (" + code + ")", result.ToString());
        }

        Assert.Throws<ArgumentException>(() => PairingTransportResult.Failure(" "));
        Assert.Equal(PairingTransportReasons.AlreadyPaired, PairingTransportResult.AlreadyPaired().Reason);
    }

    [Fact]
    public async Task PlaintextClient_IsRejectedAsATlsVersionFailure()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();

        using (var tcp = new TcpClient())
        {
            await tcp.ConnectAsync(IPAddress.Loopback, server.EndPoint.Port);
            using NetworkStream stream = tcp.GetStream();
            byte[] request = Encoding.ASCII.GetBytes("GET /pairing HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            await stream.WriteAsync(request);
            await stream.FlushAsync();
        }

        PairingTransportResult served = await accept.WaitAsync(Budget);

        Assert.False(served.Paired);
        Assert.Equal(PairingTransportReasons.TlsVersion, served.Reason);
    }

    [Fact]
    public async Task PeerThatSendsAnInvalidPayload_IsReportedAsPayloadInvalid()
    {
        // A peer that speaks TLS correctly and pins correctly but sends nonsense: the transport must blame the
        // payload, not the channel. The fake server is built from the public pieces for exactly this purpose.
        using X509Certificate2 certificate = PairingCertificate.CreateEphemeral("fake-peer");
        string fingerprint = PairingCertificate.ComputeFingerprint(certificate);

        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        try
        {
            Task fake = Task.Run(async () =>
            {
                using TcpClient peer = await listener.AcceptTcpClientAsync();
                using var ssl = new SslStream(peer.GetStream(), leaveInnerStreamOpen: false);
                await ssl.AuthenticateAsServerAsync(
                    new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        ClientCertificateRequired = false,
                        EnabledSslProtocols = SslProtocols.Tls13,
                        CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                    });
                await PairingFrame.WriteAsync(ssl, Encoding.UTF8.GetBytes("{\"schemaVersion\":1}"));
                await ssl.FlushAsync();
            });

            PairingTransportResult client = await PairingTransportClient.ConnectAsync(
                new PairingTransportClientOptions
                {
                    Endpoint = new Uri("https://127.0.0.1:" + port + "/pairing"),
                    ExpectedFingerprint = fingerprint,
                }).WaitAsync(Budget);

            Assert.False(client.Paired);
            Assert.Equal(PairingTransportReasons.PayloadInvalid, client.Reason);
            Assert.Null(client.Payload);
            await fake.WaitAsync(Budget);
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public async Task ResultsNeverPrintTheChallenge()
    {
        using PairingTransportServer server = StartServer();
        Task<PairingTransportResult> accept = server.AcceptOneAsync();
        PairingTransportResult client = await PairingTransportClient.ConnectAsync(ClientOptions(server)).WaitAsync(Budget);
        await accept.WaitAsync(Budget);

        PairingPayload payload = Assert.IsType<PairingPayload>(client.Payload);

        // A record prints every property by default, which is exactly how a one-time challenge ends up in a log.
        Assert.DoesNotContain(payload.Challenge, client.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(payload.Challenge, payload.ToString(), StringComparison.Ordinal);
        Assert.Contains("<redacted>", payload.ToString(), StringComparison.Ordinal);
        Assert.Equal(
            "pairing-transport: failed (tls-version)",
            PairingTransportResult.Failure(PairingTransportReasons.TlsVersion).ToString());
    }

    [Fact]
    public void Listener_BindsLoopbackOnlyOnAnEphemeralPort()
    {
        using PairingTransportServer server = StartServer();

        Assert.Equal(IPAddress.Loopback, server.EndPoint.Address);
        Assert.NotEqual(0, server.EndPoint.Port);
        Assert.Equal(Uri.UriSchemeHttps, server.Endpoint.Scheme);
        Assert.StartsWith(
            "https://127.0.0.1:" + server.EndPoint.Port + "/",
            server.Invitation.EphemeralUrl,
            StringComparison.Ordinal);
        Assert.Equal(server.Fingerprint, server.Invitation.PublicKeyFingerprint);
    }

    private static PairingTransportServer StartServer(
        string deviceId = DeviceId,
        TimeSpan? lifetime = null,
        string? advertisedFingerprint = null)
    {
        return PairingTransportServer.Start(new PairingTransportServerOptions
        {
            DeviceId = deviceId,
            Lifetime = lifetime ?? TimeSpan.FromSeconds(PairingPayload.LifetimeSeconds),
            HandshakeTimeout = TimeSpan.FromSeconds(5),
            AdvertisedFingerprint = advertisedFingerprint,
        });
    }

    private static PairingTransportClientOptions ClientOptions(
        PairingTransportServer server,
        string? fingerprint = null,
        SslProtocols? protocols = null)
    {
        return new PairingTransportClientOptions
        {
            Endpoint = server.Endpoint,
            ExpectedFingerprint = fingerprint ?? server.Fingerprint,
            EnabledProtocols = protocols ?? SslProtocols.Tls13,
        };
    }

    private static string Pin(char hexCharacter) => new(hexCharacter, 64);

    private static async Task AssertConnectionRefusedAsync(int port)
    {
        DateTime deadline = DateTime.UtcNow + TimeSpan.FromSeconds(3);
        while (true)
        {
            using var probe = new TcpClient();
            try
            {
                await probe.ConnectAsync(IPAddress.Loopback, port);
            }
            catch (SocketException)
            {
                return;
            }

            Assert.True(
                DateTime.UtcNow < deadline,
                "The listener on port " + port + " still accepts connections after pairing finished.");
            await Task.Delay(25);
        }
    }

    private static async Task AssertCanBindLoopbackAsync(int port)
    {
        DateTime deadline = DateTime.UtcNow + TimeSpan.FromSeconds(3);
        while (true)
        {
            try
            {
                var listener = new TcpListener(IPAddress.Loopback, port);
                listener.Start();
                listener.Stop();
                return;
            }
            catch (SocketException) when (DateTime.UtcNow < deadline)
            {
                // Windows keeps a closed socket's port unavailable while connections are in TIME_WAIT; retry
                // briefly instead of asserting on a timing detail that has nothing to do with the code.
                await Task.Delay(50);
            }
        }
    }
}

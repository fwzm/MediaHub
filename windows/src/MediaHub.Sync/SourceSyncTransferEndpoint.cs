using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;

namespace MediaHub.Sync;

internal delegate Task<SourceSyncTlsHandshakeResult> SourceSyncServerHandshake(
    Stream transport, X509Certificate2? certificate, string? fingerprint,
    TimeSpan timeout, CancellationToken cancellationToken);

/// <summary>
/// Decides whether an address is a loopback address, before any socket is created.
/// </summary>
/// <remarks>
/// <para>
/// Loopback is the only interface this build will ever touch during a transfer. Binding anything else makes
/// Windows raise a firewall prompt, and a prompt that appears out of nowhere is worse than a refused transfer:
/// the user must never have to answer a security question because the app tried a network feature in the
/// background. So the refusal happens here, in a pure function, before a <see cref="TcpListener"/> exists —
/// the failure is "not-loopback", never "listener-failed", and there is no socket to close afterwards.
/// </para>
/// <para>
/// The accepted spellings are deliberately a closed list: <c>127.0.0.1</c>, <c>::1</c>, <c>localhost</c>, and a
/// blank value (which means "the default loopback address"). Host names are not resolved: a name could point at
/// a real interface, and resolving it would make the refusal depend on DNS.
/// </para>
/// </remarks>
public static class SourceSyncTransferAddress
{
    /// <summary>The address used when the caller does not name one.</summary>
    public const string DefaultAddress = "127.0.0.1";

    /// <summary>The IPv6 loopback spelling.</summary>
    public const string LoopbackV6 = "::1";

    /// <summary>The only host name accepted, resolved to IPv4 loopback without a DNS lookup.</summary>
    public const string LoopbackName = "localhost";

    /// <summary>Whether <paramref name="address"/> names the loopback interface (blank means the default).</summary>
    public static bool IsLoopbackAddress(string? address)
        => TryResolveLoopback(address, 0, out _, out _);

    /// <summary>Resolves a loopback address and port, or explains why it will not be dialled or bound.</summary>
    /// <param name="address">The caller's address; blank means <see cref="DefaultAddress"/>.</param>
    /// <param name="port">0 for a listener that picks its own port; 1..65535 for a dial.</param>
    /// <param name="endpoint">The endpoint to use, when the address is accepted.</param>
    /// <param name="detail">A human-readable refusal; never <c>null</c> when the address is rejected.</param>
    public static bool TryResolveLoopback(string? address, int port, out IPEndPoint? endpoint, out string? detail)
        => TryResolve(address, port, allowExplicitHost: false, out endpoint, out detail);

    /// <summary>Resolves an address when the caller has explicitly widened the gate (W6-51).</summary>
    /// <remarks>
    /// <para>
    /// <paramref name="allowExplicitHost"/> is the only difference between this method and
    /// <see cref="TryResolveLoopback"/>: with it false the two are byte-for-byte the same, refusal sentences
    /// included, so every pre-W6-51 caller and test keeps the behaviour and the text it already had. With it true a
    /// single unicast IP literal outside loopback is accepted too — the caller has said on its own command line that
    /// it wants that interface, which is exactly the consent a firewall prompt would have asked for.
    /// </para>
    /// <para>
    /// Still refused, with <c>not-loopback</c> and a sentence that names the reason: a host name (C3 — no DNS on the
    /// transfer path), a wildcard address (<c>0.0.0.0</c>, <c>::</c>), a broadcast address, and multicast or
    /// reserved ranges. None of those names one machine, so accepting them would either open every interface or
    /// address nobody, which are the two outcomes the gate exists to prevent.
    /// </para>
    /// </remarks>
    public static bool TryResolve(string? address, int port, bool allowExplicitHost, out IPEndPoint? endpoint, out string? detail)
    {
        endpoint = null;
        detail = null;

        string text = (address ?? "").Trim();
        if (text.Length == 0)
        {
            text = DefaultAddress;
        }

        if (text.Length > 256)
        {
            detail = "An address is at most 256 characters; this one was not dialled.";
            return false;
        }

        IPAddress resolved;
        if (string.Equals(text, LoopbackName, StringComparison.OrdinalIgnoreCase))
        {
            resolved = IPAddress.Loopback;
        }
        else if (string.Equals(text, LoopbackV6, StringComparison.Ordinal))
        {
            resolved = IPAddress.IPv6Loopback;
        }
        else if (IPAddress.TryParse(text, out IPAddress? parsed) && parsed is not null)
        {
            if (IPAddress.IsLoopback(parsed))
            {
                resolved = parsed;
            }
            else if (!allowExplicitHost)
            {
                detail = "Only 127.0.0.1, ::1 and localhost are accepted, so '" + text
                    + "' was refused before any socket was created: binding or dialling a non-loopback address "
                    + "would make Windows ask the user to open the firewall.";
                return false;
            }
            else if (!IsUnicastLiteral(parsed))
            {
                // W6-51: the gate is open only for an address that names one machine. A wildcard would listen on
                // every interface and a broadcast/multicast address names a group, so both stay refused - and the
                // sentence says which of the two it was, because that is the only place the diagnosis can live.
                detail = "'" + text + "' is not one machine's address: a wildcard, broadcast, multicast or "
                    + "reserved address was refused before any socket was created. Name one unicast address, "
                    + "for example 192.168.1.20.";
                return false;
            }
            else
            {
                resolved = parsed;
            }
        }
        else
        {
            detail = "'" + text + "' is not an IP literal and this build does not resolve host names for "
                + "transfers; use 127.0.0.1, ::1 or localhost.";
            return false;
        }

        if (port < 0 || port > 65535)
        {
            detail = "A port is 0..65535; " + port.ToString(CultureInfo.InvariantCulture) + " is out of range.";
            return false;
        }

        endpoint = new IPEndPoint(resolved, port);
        return true;
    }

    /// <summary>Whether an address literal names one machine (W6-51).</summary>
    /// <remarks>
    /// Loopback never reaches this method: it is accepted before the check. What is left is either a unicast
    /// address, which is a peer, or something that is not one address at all — <c>0.0.0.0</c>/<c>::</c> (every
    /// interface), <c>255.255.255.255</c> (every host on the local wire), multicast, and the reserved ranges.
    /// </remarks>
    private static bool IsUnicastLiteral(IPAddress address)
    {
        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            return !address.Equals(IPAddress.IPv6Any) && !address.IsIPv6Multicast;
        }

        byte[] bytes = address.GetAddressBytes();
        if (bytes[0] == 0)
        {
            // 0.0.0.0/8 is "this network": 0.0.0.0 itself means every interface.
            return false;
        }

        if (address.Equals(IPAddress.Broadcast))
        {
            return false;
        }

        // 224/4 is multicast, 240/4 is reserved; neither is a peer.
        return bytes[0] < 224;
    }
}

/// <summary>What <see cref="SourceSyncTransferListener.Start"/> did.</summary>
public sealed record SourceSyncTransferStartResult
{
    /// <summary>True when a loopback listener is running and <see cref="Listener"/> is set.</summary>
    public bool Started { get; init; }

    /// <summary>One of <see cref="SourceSyncTransferReasons"/> when <see cref="Started"/> is false.</summary>
    public string? Reason { get; init; }

    /// <summary>Why it failed; never contains a credential.</summary>
    public string? Detail { get; init; }

    /// <summary>The running listener.</summary>
    public SourceSyncTransferListener? Listener { get; init; }

    /// <summary>The loopback listener that is now accepting exactly one connection.</summary>
    public static SourceSyncTransferStartResult Ok(SourceSyncTransferListener listener) => new()
    {
        Started = true,
        Listener = listener,
    };

    /// <summary>A refused or failed start.</summary>
    public static SourceSyncTransferStartResult Failed(string reason, string? detail = null) => new()
    {
        Reason = reason,
        Detail = detail,
    };
}

/// <summary>
/// A loopback TCP listener that accepts exactly one peer and hands it to <see cref="SourceSyncTransfer"/>.
/// </summary>
/// <remarks>
/// One accept per listener keeps the lifetime obvious: the caller starts it, sends or receives once, and disposes
/// it. There is no background loop, so nothing keeps running if the UI goes away.
/// </remarks>
public sealed class SourceSyncTransferListener : IAsyncDisposable
{
    /// <summary>How long a peer gets to connect and finish, unless the caller says otherwise.</summary>
    public static TimeSpan DefaultTimeout { get; } = TimeSpan.FromSeconds(15);

    private readonly TcpListener _listener;
    private readonly SourceSyncTlsIdentity? _identity;
    private readonly Func<Stopwatch, TimeSpan> _elapsedTime;
    private readonly SourceSyncServerHandshake _serverHandshake;
    private int _disposed;

    private SourceSyncTransferListener(TcpListener listener, IPEndPoint endpoint, SourceSyncTlsIdentity? identity)
        : this(listener, endpoint, identity, elapsed => elapsed.Elapsed, SourceSyncTlsHandshake.AuthenticateServerAsync)
    {
    }

    // Instance-scoped dependencies let tests control elapsed accept time without sleeping or replacing TLS globally.
    internal SourceSyncTransferListener(
        TcpListener listener, IPEndPoint endpoint, SourceSyncTlsIdentity? identity,
        Func<Stopwatch, TimeSpan> elapsedTime, SourceSyncServerHandshake serverHandshake)
    {
        ArgumentNullException.ThrowIfNull(elapsedTime);
        ArgumentNullException.ThrowIfNull(serverHandshake);
        _listener = listener;
        LocalEndPoint = endpoint;
        _identity = identity;
        _elapsedTime = elapsedTime;
        _serverHandshake = serverHandshake;
    }

    /// <summary>The endpoint actually bound; the port is real even when 0 was requested.</summary>
    public IPEndPoint LocalEndPoint { get; }

    /// <summary>The bound address as text.</summary>
    public string Address => LocalEndPoint.Address.ToString();

    /// <summary>The bound port.</summary>
    public int Port => LocalEndPoint.Port;

    /// <summary>Whether this listener speaks TLS (W6-26); false means the W6-16 plaintext path.</summary>
    public bool TlsEnabled => _identity is not null;

    /// <summary>
    /// The fingerprint of the certificate this listener presents, or null on a plaintext listener. It is public:
    /// a peer that does not pin it cannot tell this listener from any other process on the machine.
    /// </summary>
    public string? CertificateFingerprint => _identity?.Fingerprint;

    /// <summary>Starts a loopback listener, refusing any other address before a socket exists.</summary>
    /// <param name="address">Loopback address; blank means <see cref="SourceSyncTransferAddress.DefaultAddress"/>.</param>
    /// <param name="port">0 to let the OS pick a free port.</param>
    /// <param name="tls">
    /// True to wrap every accepted connection in TLS 1.3 with a fresh ephemeral certificate; false keeps the
    /// W6-16 plaintext path byte for byte.
    /// </param>
    /// <param name="allowExplicitHost">W6-51: true only when the caller asked, on its own command line, for the one
    /// non-loopback address it also named. False keeps every pre-W6-51 caller byte-for-byte.</param>
    public static SourceSyncTransferStartResult Start(
        string? address = SourceSyncTransferAddress.DefaultAddress,
        int port = 0,
        bool tls = false,
        bool allowExplicitHost = false)
    {
        if (!SourceSyncTransferAddress.TryResolve(address, port, allowExplicitHost, out IPEndPoint? endpoint, out string? detail)
            || endpoint is null)
        {
            return SourceSyncTransferStartResult.Failed(SourceSyncTransferReasons.NotLoopback, detail);
        }

        // The certificate is created before the socket exists: a listener that cannot present a key must fail
        // here rather than accept a connection it can never finish.
        SourceSyncTlsIdentity? identity = null;
        if (tls && !SourceSyncTlsIdentity.TryCreate(out identity, out string? tlsError))
        {
            return SourceSyncTransferStartResult.Failed(SourceSyncTlsReasons.CertificateUnavailable, tlsError);
        }

        TcpListener? listener = null;
        try
        {
            listener = new TcpListener(endpoint);
            listener.Start();
            return SourceSyncTransferStartResult.Ok(
                new SourceSyncTransferListener(listener, (IPEndPoint)listener.LocalEndpoint, identity));
        }
        catch (SocketException error)
        {
            listener?.Stop();
            identity?.Dispose();
            return SourceSyncTransferStartResult.Failed(SourceSyncTransferReasons.ListenerFailed, error.Message);
        }
        catch (ObjectDisposedException error)
        {
            listener?.Stop();
            identity?.Dispose();
            return SourceSyncTransferStartResult.Failed(SourceSyncTransferReasons.ListenerFailed, error.Message);
        }
    }

    /// <summary>Accepts one peer, reads one transfer, and closes the connection.</summary>
    /// <param name="expectedChallenge">The invitation's challenge, or null to accept whatever the peer announces.</param>
    /// <param name="timeout">How long the peer gets, defaulting to <see cref="DefaultTimeout"/>.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="receipt">The optional receipt write (W6-37). Null or <see cref="SourceSyncReceiptOptions.Off"/>
    /// writes no extra byte, so the receiver's output stays exactly today's. When on, one 0x04 byte is written after the
    /// envelope decoded and before the connection closes.</param>
    /// <param name="receiptGate">Asked once with the received envelope bytes, right before the receipt byte would be
    /// written; returning false keeps this end silent. Null accepts every decodable envelope, so pass the decode check
    /// here rather than gating after the connection is already gone.</param>
    public async Task<SourceSyncTransferResult> ReceiveOnceAsync(
        string? expectedChallenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncReceiptOptions? receipt = null,
        Func<byte[], bool>? receiptGate = null)
    {
        TimeSpan budget = ResolveBudget(timeout);
        using CancellationTokenSource cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(budget);
        Stopwatch elapsed = Stopwatch.StartNew();

        TcpClient? client = null;
        try
        {
            client = await _listener.AcceptTcpClientAsync(cts.Token).ConfigureAwait(false);
            using NetworkStream stream = client.GetStream();

            if (_identity is not null)
            {
                // TLS on: the frames are unchanged, they just travel inside TLS records.
                // W6-26 fix: the handshake gets the budget that is genuinely left after the accept, and only the
                // caller's own token as "cancel". Handing it this method's budget token made a timeout look like a
                // caller cancel whenever the accept was slow, because the handshake's own deadline started late.
                SourceSyncTlsHandshakeResult handshake = await _serverHandshake(
                    stream, _identity.Certificate, null, Remaining(budget, _elapsedTime(elapsed)), cancellationToken)
                    .ConfigureAwait(false);
                if (!handshake.Succeeded || handshake.Stream is null)
                {
                    return SourceSyncTransferResult.Failed(
                        handshake.Reason ?? SourceSyncTlsReasons.HandshakeFailed,
                        handshake.Detail);
                }

                try
                {
                    SourceSyncTransferResult received = await SourceSyncTransfer
                        .ReceiveAsync(handshake.Stream, expectedChallenge, cts.Token, options)
                        .ConfigureAwait(false);
                    return await WriteReceiptAsync(handshake.Stream, received, receipt, receiptGate, cts.Token)
                        .ConfigureAwait(false);
                }
                finally
                {
                    await SourceSyncTlsHandshake.CloseQuietlyAsync(handshake.Stream, stream).ConfigureAwait(false);
                }
            }

            // Plaintext: a peer that is actually a TLS client is named as such instead of looking like a frame
            // whose length prefix is nonsense. Every other peer is read exactly as before (the probe is replayed).
            SourceSyncTlsProbeResult probe = await SourceSyncTlsProbe.ReadAsync(stream, cts.Token).ConfigureAwait(false);
            if (!probe.Accepted || probe.Stream is null)
            {
                return SourceSyncTransferResult.Failed(probe.Reason ?? SourceSyncTlsReasons.NotEnabled, probe.Detail);
            }

            SourceSyncTransferResult plaintext = await SourceSyncTransfer
                .ReceiveAsync(probe.Stream, expectedChallenge, cts.Token, options)
                .ConfigureAwait(false);

            // The probe wrapper replays the first bytes and refuses writes, so the receipt goes to the socket itself.
            return await WriteReceiptAsync(stream, plaintext, receipt, receiptGate, cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return Timeout(budget);
        }
        catch (SocketException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        catch (ObjectDisposedException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        catch (IOException error)
        {
            // W6-31: the plaintext probe is the one read in this method that is not inside SourceSyncTransfer,
            // so it is the one read a peer could make throw something other than a SocketException (a stack
            // that reports a reset as an I/O error rather than as EOF). A receive method must answer with a
            // code, never with an exception: same code, same meaning, nothing was written.
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        finally
        {
            client?.Dispose();
        }
    }

    /// <summary>Accepts one peer, writes one transfer, and closes the connection.</summary>
    /// <param name="envelope">The serialized envelope.</param>
    /// <param name="challenge">The invitation's challenge, or null.</param>
    /// <param name="timeout">How long the peer gets, defaulting to <see cref="DefaultTimeout"/>.</param>
    /// <param name="cancellationToken">Cancels the wait.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    public async Task<SourceSyncTransferResult> SendOnceAsync(
        ReadOnlyMemory<byte> envelope,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null)
    {
        TimeSpan budget = ResolveBudget(timeout);
        using CancellationTokenSource cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(budget);
        Stopwatch elapsed = Stopwatch.StartNew();

        TcpClient? client = null;
        try
        {
            client = await _listener.AcceptTcpClientAsync(cts.Token).ConfigureAwait(false);
            using NetworkStream stream = client.GetStream();

            if (_identity is not null)
            {
                SourceSyncTlsHandshakeResult handshake = await SourceSyncTlsHandshake
                    .AuthenticateServerAsync(stream, _identity.Certificate, null, Remaining(budget, elapsed), cancellationToken)
                    .ConfigureAwait(false);
                if (!handshake.Succeeded || handshake.Stream is null)
                {
                    return SourceSyncTransferResult.Failed(
                        handshake.Reason ?? SourceSyncTlsReasons.HandshakeFailed,
                        handshake.Detail);
                }

                try
                {
                    return await SourceSyncTransfer
                        .SendAsync(handshake.Stream, envelope, challenge, cts.Token, options)
                        .ConfigureAwait(false);
                }
                finally
                {
                    await SourceSyncTlsHandshake.CloseQuietlyAsync(handshake.Stream, stream).ConfigureAwait(false);
                }
            }

            return await SourceSyncTransfer.SendAsync(stream, envelope, challenge, cts.Token, options).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return Timeout(budget);
        }
        catch (SocketException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        catch (ObjectDisposedException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        finally
        {
            client?.Dispose();
        }
    }

    /// <inheritdoc />
    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            try
            {
                _listener.Stop();
            }
            catch (SocketException)
            {
                // Stopping a listener that never accepted is not an error the caller can act on.
            }

            // The ephemeral key lives exactly as long as the listener that presents it.
            _identity?.Dispose();
        }

        return ValueTask.CompletedTask;
    }

    /// <summary>What is left of a budget once the steps before the handshake have consumed part of it.</summary>
    /// <param name="budget">The whole budget for the operation.</param>
    /// <param name="elapsed">A clock started when the budget started.</param>
    /// <remarks>
    /// A handshake that is handed the whole budget measures its deadline from its own start, which is strictly later
    /// than the caller's deadline: it can then never reach that deadline and any expiry arrives as a caller cancel.
    /// The floor keeps the value legal for a cancellation timer when nothing is left.
    /// </remarks>
    internal static TimeSpan Remaining(TimeSpan budget, Stopwatch elapsed)
        => Remaining(budget, elapsed.Elapsed);

    internal static TimeSpan Remaining(TimeSpan budget, TimeSpan elapsed)
    {
        TimeSpan left = budget - elapsed;
        return left > TimeSpan.Zero ? left : TimeSpan.FromMilliseconds(1);
    }

    private static TimeSpan ResolveBudget(TimeSpan? timeout)
    {
        if (timeout is null || timeout.Value <= TimeSpan.Zero)
        {
            return DefaultTimeout;
        }

        return timeout.Value;
    }

    /// <summary>Writes the receipt byte for a receive that already decoded, when this end was asked to.</summary>
    /// <param name="writable">The stream that can carry the byte back: the socket, or the TLS stream over it.</param>
    /// <param name="received">What the receive did.</param>
    /// <param name="receipt">The receipt options, or null/off to write nothing.</param>
    /// <param name="receiptGate">The decode check, or null to accept everything that arrived.</param>
    /// <param name="cancellationToken">Cancels the write.</param>
    /// <remarks>
    /// A receipt that cannot be written never turns a good receive into a failure: the envelope did arrive. What it does
    /// do is record <see cref="SourceSyncTransferResult.ReceiptWritten"/> as false, so the CLI can tell an operator that
    /// the peer will wait for an acknowledgement this end could not send.
    /// </remarks>
    private static async Task<SourceSyncTransferResult> WriteReceiptAsync(
        Stream writable,
        SourceSyncTransferResult received,
        SourceSyncReceiptOptions? receipt,
        Func<byte[], bool>? receiptGate,
        CancellationToken cancellationToken)
    {
        if (receipt is not { Enabled: true } || !received.Succeeded)
        {
            return received;
        }

        if (receiptGate is not null && !receiptGate(received.Envelope))
        {
            // W6-37: the byte means "I decoded your envelope". A gate that refuses the payload keeps this end silent, so
            // a sender that awaits a receipt fails instead of being told that unintelligible bytes arrived. It is a
            // deliberate silence, not an absent feature, so it is recorded as false rather than left unknown.
            return received with { ReceiptWritten = false };
        }

        SourceSyncReceiptResult written = await SourceSyncReceipt
            .WriteAsync(writable, cancellationToken)
            .ConfigureAwait(false);
        return received with { ReceiptWritten = written.Confirmed };
    }

    private static SourceSyncTransferResult Timeout(TimeSpan budget)
        => SourceSyncTransferResult.Failed(
            SourceSyncTransferReasons.TransferTimeout,
            "The peer did not finish within " + budget.TotalSeconds.ToString("0.#", CultureInfo.InvariantCulture) + " s.");
}

/// <summary>The dialling half of a loopback transfer.</summary>
public static class SourceSyncTransferClient
{
    // A peer that closes the envelope properly closes the connection within milliseconds; the budget only stops a
    // peer that keeps the connection open from holding the dialling side for the whole transfer budget.
    private static readonly TimeSpan PeerCloseBudget = TimeSpan.FromMilliseconds(1500);

    /// <summary>Connects to a loopback listener and sends one envelope.</summary>
    /// <param name="address">The loopback address to dial; null means <see cref="SourceSyncTransferAddress.DefaultAddress"/>.</param>
    /// <param name="port">The listener's port.</param>
    /// <param name="envelope">The serialized envelope.</param>
    /// <param name="challenge">The invitation's challenge, or null.</param>
    /// <param name="timeout">How long the transfer gets, defaulting to <see cref="SourceSyncTransferListener.DefaultTimeout"/>.</param>
    /// <param name="cancellationToken">Cancels the transfer.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="tls">The optional TLS pin; null or off keeps the W6-16 plaintext path.</param>
    /// <param name="delivery">The optional delivery confirmation. Null or off writes the frames and returns exactly as
    /// before: no extra read, no extra wait, and no extra byte on the wire.</param>
    /// <param name="receipt">The optional receipt wait (W6-37). Null or off adds no byte and reads nothing back. When on,
    /// the send only succeeds once the peer's 0x04 byte arrives; the peer's close is then not used as evidence, because
    /// a receiver that refuses the payload also closes.</param>
    public static async Task<SourceSyncTransferResult> SendAsync(
        string? address,
        int port,
        ReadOnlyMemory<byte> envelope,
        string? challenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        SourceSyncDeliveryOptions? delivery = null,
        SourceSyncReceiptOptions? receipt = null,
        bool allowExplicitHost = false)
    {
        // W6-37: a receipt outranks a close. When both are asked for, the receipt decides the verdict and the close wait
        // is skipped, otherwise a peer that keeps the connection open after acknowledging would be reported as an
        // unconfirmed delivery even though it proved receipt.
        SourceSyncDeliveryOptions? effectiveDelivery = receipt is { Enabled: true } ? null : delivery;

        return await RunAsync(
            address,
            port,
            (stream, token) => SourceSyncTransfer.SendAsync(stream, envelope, challenge, token, options, receipt),
            timeout,
            cancellationToken,
            tls,
            probePlaintext: false,
            delivery: effectiveDelivery,
            allowExplicitHost: allowExplicitHost).ConfigureAwait(false);
    }

    /// <summary>Connects to a loopback listener and reads one envelope.</summary>
    /// <param name="address">The loopback address to dial; null means <see cref="SourceSyncTransferAddress.DefaultAddress"/>.</param>
    /// <param name="port">The listener's port.</param>
    /// <param name="expectedChallenge">The invitation's challenge, or null to accept whatever the peer announces.</param>
    /// <param name="timeout">How long the transfer gets, defaulting to <see cref="SourceSyncTransferListener.DefaultTimeout"/>.</param>
    /// <param name="cancellationToken">Cancels the transfer.</param>
    /// <param name="options">The optional authentication settings; null keeps the W6-16 byte stream exactly.</param>
    /// <param name="tls">The optional TLS pin; null or off keeps the W6-16 plaintext path.</param>
    public static async Task<SourceSyncTransferResult> ReceiveAsync(
        string? address,
        int port,
        string? expectedChallenge = null,
        TimeSpan? timeout = null,
        CancellationToken cancellationToken = default,
        SourceSyncTransferOptions? options = null,
        SourceSyncTlsOptions? tls = null,
        bool allowExplicitHost = false)
    {
        return await RunAsync(
            address,
            port,
            (stream, token) => SourceSyncTransfer.ReceiveAsync(stream, expectedChallenge, token, options),
            timeout,
            cancellationToken,
            tls,
            probePlaintext: true,
            delivery: null,
            allowExplicitHost: allowExplicitHost).ConfigureAwait(false);
    }

    /// <summary>Reads until the peer sends its close_notify, so the close below is graceful.</summary>
    /// <param name="stream">The encrypted stream; nothing is expected to arrive on it any more.</param>
    private static async Task WaitForPeerCloseAsync(SslStream stream)
    {
        try
        {
            using CancellationTokenSource cts = new(PeerCloseBudget);
            byte[] scratch = new byte[64];
            while (await stream.ReadAsync(scratch, cts.Token).ConfigureAwait(false) > 0)
            {
                // Post-handshake records (TLS 1.3 session tickets) are consumed here instead of at close time.
            }
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException
            or InvalidOperationException or OperationCanceledException or NotSupportedException)
        {
            // A peer that is already gone has nothing left to read; the close below still happens.
        }
    }

    /// <summary>Attaches the confirmation outcome to a send that already wrote its frames.</summary>
    /// <param name="sent">The successful write.</param>
    /// <param name="confirmed">What the wait for the peer's close saw.</param>
    /// <remarks>
    /// An unconfirmed delivery is a failed send that still reports how far it got: the frames really did leave, so the
    /// envelope, frame count and byte count stay, and only the reason changes. Reporting zero bytes here would make a
    /// real partial failure look like "nothing happened".
    /// </remarks>
    private static SourceSyncTransferResult Combine(
        SourceSyncTransferResult sent,
        SourceSyncDeliveryResult confirmed)
    {
        if (confirmed.Confirmed)
        {
            return sent with { DeliveryConfirmed = true, DeliveryElapsed = confirmed.Elapsed };
        }

        return SourceSyncTransferResult.Failed(
            confirmed.Reason ?? SourceSyncDeliveryReasons.DeliveryUnconfirmed,
            confirmed.Detail) with
        {
            Envelope = sent.Envelope,
            FrameCount = sent.FrameCount,
            Bytes = sent.Bytes,
            Challenge = sent.Challenge,
            DeliveryConfirmed = false,
            DeliveryElapsed = confirmed.Elapsed,
        };
    }

    /// <summary>Sends a FIN behind the flushed frames without closing the socket.</summary>
    /// <param name="client">The connection the frames were written to.</param>
    /// <remarks>
    /// Plaintext only. A half-close is what lets the peer's reading loop finish, and it is invisible on the wire: a FIN
    /// carries no payload, so the byte stream a receiver sees is unchanged.
    /// </remarks>
    private static void TryHalfClose(TcpClient client)
    {
        try
        {
            client.Client.Shutdown(SocketShutdown.Send);
        }
        catch (SocketException)
        {
            // The peer may already be gone; the confirmation below reports that with its own reason.
        }
        catch (ObjectDisposedException)
        {
            // Same: a closed socket cannot be half-closed, and the confirmation decides the outcome.
        }
    }

    private static async Task<SourceSyncTransferResult> RunAsync(
        string? address,
        int port,
        Func<Stream, CancellationToken, Task<SourceSyncTransferResult>> body,
        TimeSpan? timeout,
        CancellationToken cancellationToken,
        SourceSyncTlsOptions? tls = null,
        bool probePlaintext = false,
        SourceSyncDeliveryOptions? delivery = null,
        bool allowExplicitHost = false)
    {
        if (!SourceSyncTransferAddress.TryResolve(address, port, allowExplicitHost, out IPEndPoint? endpoint, out string? detail)
            || endpoint is null)
        {
            // Same order as the listener: refuse before dialling, so nothing is opened for a forbidden address.
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.NotLoopback, detail);
        }

        if (endpoint.Port == 0)
        {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.ConnectFailed,
                "A dial needs a real port; 0 only means 'pick a free one' for a listener.");
        }

        TimeSpan budget = timeout is null || timeout.Value <= TimeSpan.Zero
            ? SourceSyncTransferListener.DefaultTimeout
            : timeout.Value;

        using CancellationTokenSource cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(budget);
        Stopwatch elapsed = Stopwatch.StartNew();

        TcpClient client = new();
        try
        {
            await client.ConnectAsync(endpoint, cts.Token).ConfigureAwait(false);
            using NetworkStream stream = client.GetStream();

            if (tls is { Enabled: true })
            {
                // Same rule as the listener side: what is left of the budget, and only the caller's token as cancel.
                SourceSyncTlsHandshakeResult handshake = await SourceSyncTlsHandshake
                    .AuthenticateClientAsync(
                        stream,
                        tls.ExpectedFingerprint,
                        SourceSyncTransferListener.Remaining(budget, elapsed),
                        cancellationToken)
                    .ConfigureAwait(false);
                if (!handshake.Succeeded || handshake.Stream is null)
                {
                    return SourceSyncTransferResult.Failed(
                        handshake.Reason ?? SourceSyncTlsReasons.HandshakeFailed,
                        handshake.Detail);
                }

                try
                {
                    SourceSyncTransferResult result = await body(handshake.Stream, cts.Token).ConfigureAwait(false);
                    if (result.Succeeded && delivery is { Confirm: true })
                    {
                        // No half-close on this path: a TLS 1.3 stream ends with close_notify, and a bare FIN would
                        // look like a truncated record sequence. The peer's close_notify arrives as a zero-length
                        // read, which is exactly what the confirmation waits for.
                        SourceSyncDeliveryResult confirmed = await SourceSyncDeliveryConfirmation
                            .ConfirmAsync(handshake.Stream, result.Bytes, delivery.Budget, cts.Token)
                            .ConfigureAwait(false);
                        return Combine(result, confirmed);
                    }

                    if (result.Succeeded)
                    {
                        // The receiver closes first. Waiting for its close_notify here is what keeps the close below
                        // graceful: a TLS 1.3 server sends its session tickets right after the handshake, and closing a
                        // socket that still holds unread inbound records is an abortive close - the peer sees a reset
                        // and the records it had not read yet are thrown away.
                        await WaitForPeerCloseAsync(handshake.Stream).ConfigureAwait(false);
                    }

                    return result;
                }
                finally
                {
                    await SourceSyncTlsHandshake.CloseQuietlyAsync(handshake.Stream, stream).ConfigureAwait(false);
                }
            }

            if (probePlaintext)
            {
                // Reading side only: a TLS listener answers our plaintext frames by not answering at all, and the
                // probe turns "the bytes are not a frame" into the reason that names it.
                SourceSyncTlsProbeResult probe = await SourceSyncTlsProbe.ReadAsync(stream, cts.Token).ConfigureAwait(false);
                if (!probe.Accepted || probe.Stream is null)
                {
                    return SourceSyncTransferResult.Failed(probe.Reason ?? SourceSyncTlsReasons.NotEnabled, probe.Detail);
                }

                return await body(probe.Stream, cts.Token).ConfigureAwait(false);
            }

            SourceSyncTransferResult plain = await body(stream, cts.Token).ConfigureAwait(false);
            if (plain.Succeeded && delivery is { Confirm: true })
            {
                // Half-close, never a close: Shutdown(Send) puts the FIN behind the frames that are already flushed and
                // adds no byte to the wire, while the socket stays readable so the confirmation can see the peer's close.
                TryHalfClose(client);
                SourceSyncDeliveryResult confirmed = await SourceSyncDeliveryConfirmation
                    .ConfirmAsync(stream, plain.Bytes, delivery.Budget, cts.Token)
                    .ConfigureAwait(false);
                return Combine(plain, confirmed);
            }

            return plain;
        }
        catch (OperationCanceledException)
        {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.TransferTimeout,
                "The peer did not answer within " + budget.TotalSeconds.ToString("0.#", CultureInfo.InvariantCulture) + " s.");
        }
        catch (SocketException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.ConnectFailed, error.Message);
        }
        catch (ObjectDisposedException error)
        {
            return SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message);
        }
        finally
        {
            client.Dispose();
        }
    }
}

using System.Net;

namespace MediaHub.Net;

/// <summary>How a source's traffic leaves the machine.</summary>
public enum ProxyMode
{
    /// <summary>Follow the Windows proxy configuration (WinINet/WinHTTP, PAC, auto-detect).</summary>
    System = 0,

    /// <summary>Ignore every proxy setting and connect directly.</summary>
    Direct,

    /// <summary>Use an explicitly configured HTTP or SOCKS proxy for this source only.</summary>
    Explicit,
}

/// <summary>
/// Per-source network routing policy.
/// </summary>
/// <remarks>
/// <para>
/// Windows commonly has several proxy layers active at once — WinINet settings (what most browsers
/// use), WinHTTP settings (what services use), a PAC script, an auto-detect flag, a TUN adapter and a
/// VPN. They do not agree with each other, and they do not all apply to a given HTTP stack.
/// </para>
/// <para>
/// Because of that, a policy is attached to a <i>source</i> and applied to <i>every</i> request that
/// source makes — API, artwork, playback stream, subtitle, heartbeat and progress reporting. Stating
/// that "all requests use one connection pool, therefore they share an exit IP" would be wrong: the
/// engine fetches the stream with its own stack, so its routing is configured separately and the two
/// paths are verified independently.
/// </para>
/// </remarks>
public sealed record NetworkPolicy
{
    public static NetworkPolicy FollowSystem { get; } = new();

    public ProxyMode Mode { get; init; } = ProxyMode.System;

    /// <summary>Proxy URL for <see cref="ProxyMode.Explicit"/>, e.g. <c>http://127.0.0.1:7890</c>.</summary>
    public string? ProxyUrl { get; init; }

    /// <summary>Optional bypass list (host suffixes) applied to the explicit proxy.</summary>
    public IReadOnlyList<string> BypassList { get; init; } = Array.Empty<string>();

    /// <summary>Timeout applied to connection establishment.</summary>
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(15);

    /// <summary>
    /// Refuses to follow a redirect that downgrades HTTPS to plain HTTP.
    /// </summary>
    /// <remarks>
    /// Default-on. A downgrade would send the media request — and, on the first hop, whatever
    /// credentials the origin scoping allowed — over cleartext.
    /// </remarks>
    public bool BlockHttpsDowngrade { get; init; } = true;

    /// <summary>Maximum number of redirect hops; bounds redirect loops and hostile chains.</summary>
    public int MaxRedirects { get; init; } = 10;

    /// <summary>
    /// SHA-256 of the leaf certificate this source is allowed to present, as hex, or null for
    /// "platform trust only".
    /// </summary>
    /// <remarks>
    /// This is the only supported way to reach a server with a self-signed certificate. It is scoped to
    /// one source's handlers: there is deliberately no process-wide "ignore certificate errors" switch,
    /// because that would weaken every other source, including the ones that do not need it. See
    /// <see cref="CertificateTrust"/> for what a pin match does and does not check.
    /// </remarks>
    public string? TrustedCertificateSha256 { get; init; }

    /// <summary>
    /// Skips certificate validation for this source only, when the user explicitly chose that per
    /// server.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The weaker of the two per-server options, and the one that carries a real risk: with no
    /// validation, anything that can intercept the connection can present its own certificate and read
    /// the token this client sends. It exists because some servers rotate self-signed certificates in
    /// ways that make pinning impractical, and refusing them outright contradicts "supports everything".
    /// </para>
    /// <para>
    /// Like the pin, it is scoped to one source's handlers and is never a process-wide switch. A pin
    /// takes precedence when both are set; <see cref="Validate"/> refuses that combination so the
    /// behaviour is never ambiguous.
    /// </para>
    /// </remarks>
    public bool AllowUntrustedCertificate { get; init; }

    /// <summary>Validates the policy before it is used to build a handler.</summary>
    public void Validate()
    {
        if (Mode == ProxyMode.Explicit)
        {
            if (string.IsNullOrWhiteSpace(ProxyUrl))
                throw new ArgumentException("Explicit proxy mode requires a proxy URL.");
            if (!Uri.TryCreate(ProxyUrl, UriKind.Absolute, out _))
                throw new ArgumentException("Proxy URL must be absolute, e.g. http://127.0.0.1:7890");
        }

        if (!string.IsNullOrWhiteSpace(TrustedCertificateSha256)
            && CertificateTrust.NormalizeFingerprint(TrustedCertificateSha256) is null)
        {
            throw new ArgumentException("TrustedCertificateSha256 must be a 64-digit hex SHA-256 fingerprint.");
        }

        if (AllowUntrustedCertificate && CertificateTrust.NormalizeFingerprint(TrustedCertificateSha256) is not null)
            throw new ArgumentException("Choose either a certificate fingerprint or 'do not validate', not both.");
    }

    /// <summary>
    /// Value to configure on the playback engine for this policy, or null to leave it unset.
    /// </summary>
    /// <remarks>
    /// The engine has its own stack and does not read Windows proxy settings; left alone it consults
    /// the ambient <c>http_proxy</c> / <c>NO_PROXY</c> environment variables instead, so routing
    /// would depend on the user's shell environment rather than on the configured policy. Passing an
    /// explicit value — including an empty one, which means "direct" — makes the engine's route a
    /// function of the policy alone.
    /// </remarks>
    public string ToEngineProxyValue(Uri target)
    {
        switch (Mode)
        {
            case ProxyMode.Direct:
                return ""; // explicitly disable, so an ambient http_proxy cannot take effect
            case ProxyMode.Explicit:
                return ProxyUrl ?? "";
            default:
                return ResolveSystemProxy(target) ?? "";
        }
    }

    /// <summary>
    /// Resolves the effective system proxy for <paramref name="target"/>, or null when the system
    /// would connect directly.
    /// </summary>
    private static string? ResolveSystemProxy(Uri target)
    {
        try
        {
            IWebProxy proxy = HttpClient.DefaultProxy;
            if (proxy is null || proxy.IsBypassed(target)) return null;

            Uri? address = proxy.GetProxy(target);
            if (address is null) return null;
            // GetProxy returns the target itself when no proxy applies.
            if (Uri.Compare(address, target, UriComponents.SchemeAndServer, UriFormat.Unescaped,
                    StringComparison.OrdinalIgnoreCase) == 0)
            {
                return null;
            }

            // mpv wants a proxy URL. A SOCKS endpoint must keep its scheme so the engine selects the
            // SOCKS implementation rather than speaking HTTP to it.
            return address.Scheme switch
            {
                "socks5" => "socks5://" + address.Authority,
                "socks4" => "socks4://" + address.Authority,
                _ => "http://" + address.Authority,
            };
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Human-readable description for the connection diagnostics panel.</summary>
    public string Describe()
    {
        string route = Mode switch
        {
            ProxyMode.Direct => "直连（忽略系统代理）",
            ProxyMode.Explicit => "指定代理：" + (ProxyUrl ?? "(未设置)"),
            _ => "跟随系统代理",
        };

        string? pin = CertificateTrust.NormalizeFingerprint(TrustedCertificateSha256);
        if (pin is not null) route += "；已信任该服务器证书 " + pin[..16] + "…";
        else if (AllowUntrustedCertificate) route += "；该服务器不校验证书（令牌可被中间人截获）";
        return route;
    }
}

/// <summary>Builds the HTTP handler chain for a policy.</summary>
public static class NetworkPolicyHandlerFactory
{
    /// <summary>
    /// Creates a handler whose proxy behaviour and redirect semantics are fully determined by
    /// <paramref name="policy"/> rather than by ambient machine configuration.
    /// </summary>
    public static SocketsHttpHandler CreateHandler(NetworkPolicy policy)
    {
        policy.Validate();

        var handler = new SocketsHttpHandler
        {
            // Redirects are followed by OriginScopedCredentialHandler so that every hop is inspected;
            // leaving this on would let the BCL follow a hop we never get to sanitise.
            AllowAutoRedirect = false,
            ConnectTimeout = policy.ConnectTimeout,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            AutomaticDecompression = DecompressionMethods.All,
            UseCookies = false,
        };

        switch (policy.Mode)
        {
            case ProxyMode.Direct:
                handler.UseProxy = false;
                handler.Proxy = null;
                break;

            case ProxyMode.Explicit:
                handler.UseProxy = true;
                var proxy = new WebProxy(policy.ProxyUrl!);
                foreach (string host in policy.BypassList) proxy.BypassList = policy.BypassList.ToArray();
                handler.Proxy = proxy;
                break;

            default:
                // HttpClient.DefaultProxy is the .NET entry point for the Windows system proxy
                // configuration; it honours the same settings the system resolver uses.
                handler.UseProxy = true;
                break;
        }

        // Only a source that was explicitly configured touches SslOptions. Every other source keeps the
        // platform validator untouched, so "supports self-signed servers" never becomes "trusts
        // everything".
        string? pin = CertificateTrust.NormalizeFingerprint(policy.TrustedCertificateSha256);
        if (pin is not null)
            handler.SslOptions.RemoteCertificateValidationCallback = CertificateTrust.CreateValidationCallback(pin);
        else if (policy.AllowUntrustedCertificate)
            handler.SslOptions.RemoteCertificateValidationCallback = static (_, _, _, _) => true;

        return handler;
    }
}

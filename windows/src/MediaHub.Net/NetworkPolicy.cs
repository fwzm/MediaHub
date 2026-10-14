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
    }

    /// <summary>Human-readable description for the connection diagnostics panel.</summary>
    public string Describe() => Mode switch
    {
        ProxyMode.Direct => "直连（忽略系统代理）",
        ProxyMode.Explicit => "指定代理：" + (ProxyUrl ?? "(未设置)"),
        _ => "跟随系统代理",
    };
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

        return handler;
    }
}

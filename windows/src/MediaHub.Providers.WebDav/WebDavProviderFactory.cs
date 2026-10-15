using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.WebDav;

/// <summary>
/// Creates WebDAV source handles.
/// </summary>
/// <remarks>
/// <para>
/// The declared capability set mirrors the Android WebDAV package exactly, translated to the Windows
/// flags: authentication, folder browsing, file detail, playback and external-subtitle discovery.
/// Nothing is declared that has no implementation — no search (PROPFIND has no search semantics and
/// this is a read-only package), no library metadata, no progress reporting, no artwork. The Android
/// client reached the same boundary and documented the same reason (ADR-022: a declared capability
/// without a callable implementation is a defect).
/// </para>
/// <para>
/// Windows has no dedicated subtitle-<i>discovery</i> interface; sibling-file discovery is reached
/// through <see cref="IProviderSubtitle"/>. The declared flag is kept distinct from the runtime flag so
/// the descriptor still says "discovery", not "the server listed tracks for me".
/// </para>
/// </remarks>
public sealed class WebDavProviderFactory : IMediaProviderFactory, IDisposable
{
    private readonly List<MediaSourceHttpClientFactory> _created = new();
    private readonly object _gate = new();
    private bool _disposed;

    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.WebDav,
        "WebDAV",
        "WebDAV / NAS 通用协议（只读：Basic 认证、目录浏览、文件详情、直链播放、同目录字幕）",
        ProviderCapability.Auth | ProviderCapability.Browse | ProviderCapability.Detail |
        ProviderCapability.Playback | ProviderCapability.SubtitleDiscovery);

    /// <summary>Credential keys accepted from the caller, in order of preference.</summary>
    /// <remarks>
    /// <c>userId</c> is accepted as an alias because that is the key the application's source manager
    /// currently forwards; a WebDAV user name has no server-side id, so the distinction the Emby path
    /// makes does not exist here.
    /// </remarks>
    private static readonly string[] UserNameKeys = { "username", "userName", "userId" };

    private const string PasswordKey = "password";

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        ArgumentNullException.ThrowIfNull(server);
        ArgumentNullException.ThrowIfNull(policy);

        if (!WebDavUrl.TryNormalizeBase(server.PrimaryEndpoint?.Url, out Uri? baseUri, out string? error))
        {
            throw ProviderException.Connection(server.ServerId, error ?? "媒体源地址无效");
        }

        // The client comes from the shared factory, never from a locally constructed HttpClient: the
        // source's NetworkPolicy and the origin-scoped redirect handling have to apply to every request
        // this provider makes, and a hand-built client would sit outside both.
        var httpFactory = new MediaSourceHttpClientFactory(policy);
        lock (_gate)
        {
            if (_disposed)
            {
                httpFactory.Dispose();
                throw new ObjectDisposedException(nameof(WebDavProviderFactory));
            }
            _created.Add(httpFactory);
        }

        HttpClient apiClient = httpFactory.CreateApiClient();

        var session = new WebDavSession(server.ServerId);
        if (credentials is not null)
        {
            string? userName = null;
            foreach (string key in UserNameKeys)
            {
                if (credentials.TryGetValue(key, out string? candidate) && !string.IsNullOrEmpty(candidate))
                {
                    userName = candidate;
                    break;
                }
            }
            string? password = credentials.TryGetValue(PasswordKey, out string? stored) ? stored : null;
            if (userName is not null || password is not null) session.Set(userName, password);
        }

        var api = new WebDavApi(apiClient, server.ServerId, session, baseUri!);

        ProviderHandle handle = null!;
        var provider = new WebDavProvider(
            api,
            server.ServerId,
            baseUri!,
            session,
            () => handle.OnSessionExpired?.Invoke(),
            httpFactory);

        handle = new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = Descriptor,
            Session = new SessionMaterial(null, session.UserName, session.UserName),
            Auth = provider,
            Browse = provider,
            Detail = provider,
            Playback = provider,
            Subtitle = provider,
        };

        return handle;
    }

    /// <summary>
    /// Disposes the HTTP clients handed to handles this factory created.
    /// </summary>
    /// <remarks>
    /// A belt-and-braces lifetime hook. The intended route is <c>ProviderHandle.Own</c>, which is
    /// internal to MediaHub.Providers and therefore unavailable here; until that is widened, disposing
    /// the factory (or the provider) is the only way to release these clients.
    /// </remarks>
    public void Dispose()
    {
        List<MediaSourceHttpClientFactory> toDispose;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            toDispose = new List<MediaSourceHttpClientFactory>(_created);
            _created.Clear();
        }
        foreach (MediaSourceHttpClientFactory factory in toDispose) factory.Dispose();
    }
}

/// <summary>
/// Protocol-level connection probe for the "add a media source" flow, before any credential exists.
/// </summary>
/// <remarks>
/// Mirrors <see cref="MediaHub.Providers.MediaBrowser.MediaServerProbe"/>: a WebDAV address is proven
/// by protocol behaviour (an OPTIONS that declares DAV classes, or a PROPFIND that answers a
/// multistatus), not by "something answered below 500". A NAS login page or a reverse-proxy error page
/// would pass an HTTP-status check and fail much later, in a much more confusing place.
/// </remarks>
public static class WebDavServerProbe
{
    public sealed record ProbeResult(
        bool Success,
        string Message,
        IReadOnlyList<string> DavClasses,
        string? ServerHeader,
        string? ErrorCode)
    {
        public static ProbeResult Fail(string message, string? errorCode = null)
            => new(false, message, Array.Empty<string>(), null, errorCode);
    }

    public static async Task<ProbeResult> ProbeAsync(
        Uri baseUri,
        NetworkPolicy policy,
        IReadOnlyDictionary<string, string>? credentials = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(baseUri);
        ArgumentNullException.ThrowIfNull(policy);

        using var factory = new MediaSourceHttpClientFactory(policy);
        HttpClient client = factory.CreateApiClient();
        client.Timeout = TimeSpan.FromSeconds(10);

        var session = new WebDavSession("probe");
        if (credentials is not null)
        {
            string? userName = null;
            foreach (string key in new[] { "username", "userName", "userId" })
            {
                if (credentials.TryGetValue(key, out string? candidate) && !string.IsNullOrEmpty(candidate))
                {
                    userName = candidate;
                    break;
                }
            }
            if (userName is not null) session.Set(userName, credentials.TryGetValue("password", out string? password) ? password : null);
        }

        var api = new WebDavApi(client, "probe", session, baseUri);

        try
        {
            WebDavOptionsResponse options = await api.OptionsAsync(baseUri, ct).ConfigureAwait(false);

            switch (options.StatusCode)
            {
                case 401:
                    return new ProbeResult(false, "需要认证（HTTP 401）", options.DavClasses, options.Server, "AUTH_REQUIRED");

                case 405:
                case 501:
                    // OPTIONS is not mandatory (RFC 7231 §4.3.7). A Depth:0 PROPFIND is the protocol-level
                    // proof, and it is the method this provider actually depends on.
                    await api.PropfindAsync(baseUri, 0, ct).ConfigureAwait(false);
                    return new ProbeResult(true, "WebDAV 可用（服务器未实现 OPTIONS，已用 PROPFIND 验证）", options.DavClasses, options.Server, null);

                case >= 200 and < 400:
                    return options.DavClasses.Count > 0
                        ? new ProbeResult(true, "WebDAV 可用（DAV " + string.Join(",", options.DavClasses) + "）", options.DavClasses, options.Server, null)
                        // Reachable, but nothing proved it speaks DAV: report the fact instead of claiming success.
                        : new ProbeResult(true, "服务器响应正常，但未声明 DAV 头（可能是反向代理）", options.DavClasses, options.Server, null);

                default:
                    return new ProbeResult(false, "HTTP " + options.StatusCode.ToString(System.Globalization.CultureInfo.InvariantCulture),
                        options.DavClasses, options.Server, "HTTP");
            }
        }
        catch (ProviderException ex)
        {
            return new ProbeResult(false, ex.Message, Array.Empty<string>(), null, ex.Code.ToString());
        }
        catch (Exception ex)
        {
            return ProbeResult.Fail("无法连接：" + ex.GetType().Name, "CONNECTION");
        }
    }
}

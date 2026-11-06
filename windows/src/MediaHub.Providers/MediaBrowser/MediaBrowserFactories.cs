using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.MediaBrowser;

/// <summary>
/// Emby provider factory.
/// </summary>
/// <remarks>
/// Connection testing is a protocol-level probe rather than an HTTP status check: the server must
/// answer <c>/System/Info/Public</c> with a parseable identity (<c>Id</c> and <c>Version</c>). A host
/// that merely responds below 500 might be a NAS login page, a reverse proxy error page or an
/// unrelated service, and accepting it produces a confusing failure much later.
/// </remarks>
public sealed class EmbyProviderFactory : IMediaProviderFactory
{
    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.Emby,
        "Emby",
        "Emby 媒体服务器（直接播放与直连串流）",
        ProviderCapability.Auth | ProviderCapability.Library | ProviderCapability.Detail |
        ProviderCapability.Playback | ProviderCapability.Search | ProviderCapability.Progress |
        ProviderCapability.Subtitle | ProviderCapability.Favorite);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        Uri baseUri = ResolveBaseUri(server);
        MediaBrowserDialect dialect = MediaBrowserDialect.Emby(baseUri);
        return MediaBrowserProviderFactorySupport.Build(server, policy, dialect, Descriptor, credentials);
    }

    internal static Uri ResolveBaseUri(MediaServer server)
    {
        string raw = server.PrimaryEndpoint?.Url ?? throw ProviderException.NotFound(server.ServerId, "服务器地址");
        if (!Uri.TryCreate(raw, UriKind.Absolute, out var uri))
            throw ProviderException.Connection(server.ServerId, "服务器地址无效");
        return uri;
    }
}

/// <summary>Jellyfin provider factory.</summary>
public sealed class JellyfinProviderFactory : IMediaProviderFactory
{
    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.Jellyfin,
        "Jellyfin",
        "Jellyfin 媒体服务器（直接播放与直连串流）",
        ProviderCapability.Auth | ProviderCapability.Library | ProviderCapability.Detail |
        ProviderCapability.Playback | ProviderCapability.Search | ProviderCapability.Progress |
        ProviderCapability.Subtitle | ProviderCapability.Favorite);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        Uri baseUri = EmbyProviderFactory.ResolveBaseUri(server);
        MediaBrowserDialect dialect = MediaBrowserDialect.Jellyfin(baseUri);
        return MediaBrowserProviderFactorySupport.Build(server, policy, dialect, Descriptor, credentials);
    }
}

internal static class MediaBrowserProviderFactorySupport
{
    public static ProviderHandle Build(
        MediaServer server,
        NetworkPolicy policy,
        MediaBrowserDialect dialect,
        ProviderDescriptor descriptor,
        IReadOnlyDictionary<string, string>? credentials)
    {
        var httpFactory = new MediaSourceHttpClientFactory(policy);
        HttpClient apiClient = httpFactory.CreateApiClient();
        var api = new MediaBrowserApi(apiClient, dialect, server.ServerId);

        // Credentials are applied to the API client as well as to the provider, because artwork and
        // subtitle fetches go through the same client and must be authenticated the same way.
        var session = new SessionMaterial(
            credentials is not null && credentials.TryGetValue("token", out var token) ? token : null,
            credentials is not null && credentials.TryGetValue("userId", out var uid) ? uid : null,
            null);

        ProviderHandle handle = null!;
        var provider = new MediaBrowserProvider(api, server.ServerId, session, () => handle.OnSessionExpired?.Invoke());
        handle = new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = descriptor,
            Session = session,
            Auth = provider,
            Library = provider,
            Detail = provider,
            Playback = provider,
            Search = provider,
            Progress = provider,
            ProgressWrite = provider,
            Favorites = provider,
            Subtitle = provider,
            Images = provider,
        };
        handle.Own(httpFactory);
        return handle;
    }
}

/// <summary>
/// Connection probe shared by Emby and Jellyfin.
/// </summary>
/// <remarks>
/// Kept separate from the provider so the "add a media source" flow can validate an address before
/// any credentials exist.
/// </remarks>
public static class MediaServerProbe
{
    public sealed record ProbeResult(bool Success, string Message, string? ServerName, string? Version, string? RemoteServerId)
    {
        public static ProbeResult Fail(string message) => new(false, message, null, null, null);
    }

    public static async Task<ProbeResult> ProbeMediaBrowserAsync(Uri baseUri, ServerType type, NetworkPolicy policy, CancellationToken ct = default)
    {
        MediaBrowserDialect dialect = type == ServerType.Emby
            ? MediaBrowserDialect.Emby(baseUri)
            : MediaBrowserDialect.Jellyfin(baseUri);

        using var factory = new MediaSourceHttpClientFactory(policy);
        using var client = factory.CreateApiClient();
        client.Timeout = TimeSpan.FromSeconds(10);
        var api = new MediaBrowserApi(client, dialect, "probe");

        try
        {
            using var doc = await api.GetSystemInfoPublicAsync(ct).ConfigureAwait(false);
            string? id = MediaBrowserMapper.GetString(doc.RootElement, "Id");
            string? version = MediaBrowserMapper.GetString(doc.RootElement, "Version");
            if (string.IsNullOrEmpty(id)) return ProbeResult.Fail("目标不是媒体服务器（缺少服务器标识）");

            return new ProbeResult(true, "连接正常", MediaBrowserMapper.GetString(doc.RootElement, "ServerName"), version, id);
        }
        catch (ProviderException ex)
        {
            return ProbeResult.Fail(ex.Message);
        }
        catch (Exception ex)
        {
            return ProbeResult.Fail("无法连接：" + ex.GetType().Name);
        }
    }
}

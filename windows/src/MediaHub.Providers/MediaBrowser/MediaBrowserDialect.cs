using System.Text;

namespace MediaHub.Providers.MediaBrowser;

/// <summary>Protocol differences between the two MediaBrowser descendants.</summary>
internal sealed class MediaBrowserDialect
{
    private readonly Uri _baseUri;
    private readonly string _rootPath;

    private MediaBrowserDialect(Uri baseUri, string rootPath, string authHeaderName, bool tokenInAuthHeader)
    {
        _baseUri = baseUri;
        _rootPath = rootPath;
        AuthHeaderName = authHeaderName;
        TokenInAuthHeader = tokenInAuthHeader;
    }

    public string AuthHeaderName { get; }
    public bool TokenInAuthHeader { get; }
    public Uri ApiRoot => _rootPath.Length == 0 ? _baseUri : new Uri(_baseUri, _rootPath);

    /// <summary>
    /// Emby serves its API under <c>/emby</c>. A user who already typed <c>/emby</c> must not end up
    /// with <c>/emby/emby</c>, so the suffix is only appended when absent.
    /// </summary>
    public static MediaBrowserDialect Emby(Uri baseUri, string? overrideRoot = null)
    {
        string path = baseUri.AbsolutePath.TrimEnd('/');
        if (!string.IsNullOrWhiteSpace(overrideRoot)) path = overrideRoot!.TrimEnd('/');
        else if (!path.EndsWith("/emby", StringComparison.OrdinalIgnoreCase)) path += "/emby";
        return new MediaBrowserDialect(baseUri, path, "X-Emby-Authorization", tokenInAuthHeader: false);
    }

    /// <summary>Jellyfin serves its API from the server root.</summary>
    public static MediaBrowserDialect Jellyfin(Uri baseUri, string? overrideRoot = null)
    {
        string path = string.IsNullOrWhiteSpace(overrideRoot) ? baseUri.AbsolutePath.TrimEnd('/') : overrideRoot!.TrimEnd('/');
        return new MediaBrowserDialect(baseUri, path, "Authorization", tokenInAuthHeader: true);
    }

    public string BuildUrl(string path, IDictionary<string, string?>? query)
    {
        var sb = new StringBuilder();
        sb.Append(ApiRoot.ToString().TrimEnd('/'));
        sb.Append(path.StartsWith('/') ? path : "/" + path);

        if (query is { Count: > 0 })
        {
            sb.Append('?');
            bool first = true;
            foreach (var kv in query)
            {
                if (kv.Value is null) continue;
                if (!first) sb.Append('&');
                first = false;
                sb.Append(Uri.EscapeDataString(kv.Key)).Append('=').Append(Uri.EscapeDataString(kv.Value));
            }
        }
        return sb.ToString();
    }

    /// <summary>Official MediaBrowser client-identity schema.</summary>
    public string BuildAuthorizationValue(string? userId, string? token)
    {
        var sb = new StringBuilder("MediaBrowser ");
        sb.Append("Client=\"").Append(Net.ProviderIdentity.ProductName).Append('"');
        sb.Append(", Device=\"").Append(Net.ProviderIdentity.DeviceName).Append('"');
        sb.Append(", DeviceId=\"").Append(Net.ProviderIdentity.DeviceId).Append('"');
        sb.Append(", Version=\"").Append(Net.ProviderIdentity.Version).Append('"');
        if (!string.IsNullOrEmpty(token)) sb.Append(", Token=\"").Append(token).Append('"');
        if (!string.IsNullOrEmpty(userId)) sb.Append(", UserId=\"").Append(userId).Append('"');
        return sb.ToString();
    }

    public void ApplyIdentity(System.Net.Http.HttpRequestMessage request, string? userId)
    {
        // The identity header is sent on every request, including the unauthenticated ones, because
        // the server uses DeviceId to attribute sessions and to detect concurrent streams.
        request.Headers.TryAddWithoutValidation(AuthHeaderName, BuildAuthorizationValue(
            TokenInAuthHeader ? null : userId,
            TokenInAuthHeader ? null : null));
    }

    /// <summary>
    /// Attaches the session token.
    /// </summary>
    /// <remarks>
    /// Emby takes a dedicated <c>X-Emby-Token</c> header; Jellyfin carries the token inside the
    /// authorization header. Neither ever puts it in the URL.
    /// </remarks>
    public void ApplyToken(System.Net.Http.HttpRequestMessage request, string token)
    {
        if (TokenInAuthHeader)
        {
            request.Headers.Remove(AuthHeaderName);
            request.Headers.TryAddWithoutValidation(AuthHeaderName, BuildAuthorizationValue(null, token));
        }
        else
        {
            request.Headers.TryAddWithoutValidation("X-Emby-Token", token);
            request.Headers.TryAddWithoutValidation("X-MediaBrowser-Token", token);
        }
    }
}

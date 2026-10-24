namespace MediaHub.Net;

/// <summary>
/// Creates the API and media HTTP clients for one media source.
/// </summary>
/// <remarks>
/// A source gets one factory, and the factory applies exactly one <see cref="NetworkPolicy"/> to both
/// clients. That is what makes the policy per-source rather than per-request: the API call that lists
/// a library, the artwork download, the subtitle fetch and the progress report all leave through the
/// same configured route.
/// </remarks>
public sealed class MediaSourceHttpClientFactory : IDisposable
{
    private readonly NetworkPolicy _policy;
    private readonly SocketsHttpHandler _apiHandler;
    private readonly SocketsHttpHandler _mediaHandler;
    private readonly List<HttpClient> _created = new();
    private readonly object _gate = new();

    public MediaSourceHttpClientFactory(NetworkPolicy policy)
    {
        _policy = policy;
        _apiHandler = NetworkPolicyHandlerFactory.CreateHandler(policy);
        _mediaHandler = NetworkPolicyHandlerFactory.CreateHandler(policy);
    }

    public NetworkPolicy Policy => _policy;

    /// <summary>Client for the provider's JSON API: short timeout, bounded, no streaming.</summary>
    public HttpClient CreateApiClient()
    {
        var handler = new OriginScopedCredentialHandler(_policy, _apiHandler);
        var client = new HttpClient(handler, disposeHandler: false)
        {
            Timeout = TimeSpan.FromSeconds(30),
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(ProviderIdentity.UserAgent);
        Track(client);
        return client;
    }

    /// <summary>
    /// Client for byte streams. No overall timeout: a long download would otherwise be cancelled
    /// mid-stream, which is the classic cause of a stall halfway through a movie.
    /// </summary>
    public HttpClient CreateMediaClient()
    {
        var handler = new OriginScopedCredentialHandler(_policy, _mediaHandler);
        var client = new HttpClient(handler, disposeHandler: false)
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(ProviderIdentity.UserAgent);
        Track(client);
        return client;
    }

    /// <summary>API client with the caller's long-lived credentials attached.</summary>
    public HttpClient CreateApiClientWithHeaders(IReadOnlyDictionary<string, string> headers)
    {
        var client = CreateApiClient();
        foreach (var kv in headers)
        {
            if (string.IsNullOrWhiteSpace(kv.Key)) continue;
            if (OriginScopedCredentialHandler.IsSensitive(kv.Key)) continue; // never a default header
            client.DefaultRequestHeaders.TryAddWithoutValidation(kv.Key, kv.Value);
        }
        return client;
    }

    private void Track(HttpClient client)
    {
        lock (_gate) _created.Add(client);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            foreach (var c in _created) c.Dispose();
            _created.Clear();
        }
        _apiHandler.Dispose();
        _mediaHandler.Dispose();
    }
}

/// <summary>Client identity sent to servers, shared across protocols (mirrors the Android contract).</summary>
public static class ProviderIdentity
{
    public const string ProductName = "MediaHub";
    public const string DeviceName = "MediaHub Windows";
    public const string DeviceId = "mediahub-windows";
    public const string Version = "0.1.0";

    public static string UserAgent => ProductName + "/" + Version + " (Windows)";
}

using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.Local;
using MediaHub.Providers.MediaBrowser;

namespace MediaHub.App.Services;

/// <summary>
/// Central registry of configured media sources, and the single place that decides which providers
/// this build actually contains.
/// </summary>
/// <remarks>
/// Runtime capabilities are read from the constructed handle, never from a static per-vendor table.
/// That is the Windows counterpart of the Android rule (ADR-014/ADR-022): a source is listed as
/// supporting something only when an object implementing it exists.
/// </remarks>
public sealed class SourceRegistry
{
    private readonly SettingsStore _settings;
    private readonly ProviderRegistry _providers = new();
    private readonly List<ProviderHandle> _handles = new();
    private readonly object _gate = new();

    public SourceRegistry(SettingsStore settings)
    {
        _settings = settings;

        // Providers present in this build. Adding one here is the only step needed for it to appear
        // in the "add a source" list.
        _providers.Register(new LocalMediaProviderFactory());
        _providers.Register(new EmbyProviderFactory());
        _providers.Register(new JellyfinProviderFactory());
    }

    public IReadOnlyList<ProviderDescriptor> AvailableProviders => _providers.Descriptors;

    /// <summary>Configured sources, including the always-present local folder source.</summary>
    public IReadOnlyList<ProviderHandle> Handles
    {
        get { lock (_gate) return _handles.ToList(); }
    }

    public ProviderHandle LocalHandle { get; private set; } = null!;

    /// <summary>
    /// Rebuilds the local handle from the configured folders.
    /// </summary>
    /// <remarks>
    /// Local media is modelled as a provider like any other rather than as a special case, so the
    /// browsing UI has one code path and the capability flags stay honest.
    /// </remarks>
    public void RefreshLocal()
    {
        var folders = _settings.Current.LocalFolders;
        var endpoints = folders.Select((f, i) => new ServerEndpoint(f, true, "文件夹 " + (i + 1))).ToList();
        var server = new MediaServer("local", ServerType.Local, "本地存储", endpoints);

        var previous = LocalHandle;
        LocalHandle = _providers.Create(server, NetworkPolicy.FollowSystem)
                      ?? throw new InvalidOperationException("Local provider factory is not registered.");

        lock (_gate)
        {
            _handles.RemoveAll(h => h.ServerId == "local");
            _handles.Insert(0, LocalHandle);
        }
        previous?.Dispose();
    }

    /// <summary>Connect a new media server and add its handle if the probe succeeds.</summary>
    public async Task<(bool Ok, string Message)> AddMediaServerAsync(
        ServerType type,
        string url,
        string username,
        string password,
        NetworkPolicy policy,
        CancellationToken ct = default)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var baseUri))
            return (false, "服务器地址无效，请包含 http:// 或 https://");

        var probe = await MediaServerProbe.ProbeMediaBrowserAsync(baseUri, type, policy, ct).ConfigureAwait(false);
        if (!probe.Success) return (false, probe.Message);

        string serverId = type.ToString().ToLowerInvariant() + ":" + (probe.RemoteServerId ?? baseUri.Host);
        var server = new MediaServer(serverId, type, probe.ServerName ?? baseUri.Host, new[] { new ServerEndpoint(baseUri.ToString()) });

        var handle = _providers.Create(server, policy);
        if (handle?.Auth is null) return (false, "该数据源未提供认证能力");

        try
        {
            AuthResult result = await handle.Auth.AuthenticateAsync(new Credentials(username, password), ct).ConfigureAwait(false);
            handle.Session = new SessionMaterial(ReadToken(handle), result.UserId, result.UserName);
        }
        catch (ProviderException ex)
        {
            handle.Dispose();
            return (false, ex.Message);
        }

        lock (_gate)
        {
            _handles.RemoveAll(h => h.ServerId == serverId);
            _handles.Add(handle);
        }

        _settings.Update(s =>
        {
            s.Servers.RemoveAll(x => x.ServerId == serverId);
            s.Servers.Add(new PersistedServer
            {
                ServerId = serverId,
                Type = type.ToString(),
                DisplayName = server.DisplayName,
                Url = baseUri.ToString(),
                UserName = username,
                RemoteServerId = probe.RemoteServerId,
                // The password is deliberately not persisted here; credential storage is a separate,
                // encrypted concern and is not wired up in this build.
            });
        });

        return (true, "已添加：" + server.DisplayName);
    }

    private static string? ReadToken(ProviderHandle handle)
    {
        // The token only ever lives inside the provider; this reaches it through the same session
        // contract the provider updates, so it never has to be stored on the handle by hand.
        return handle.Session.Token;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            foreach (var h in _handles) h.Dispose();
            _handles.Clear();
        }
    }
}

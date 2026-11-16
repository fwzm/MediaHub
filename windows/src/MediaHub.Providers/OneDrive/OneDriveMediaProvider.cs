using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers.Local;

namespace MediaHub.Providers.OneDrive;

/// <summary>
/// Registers a Microsoft OneDrive account as a browsable, playable source.
/// </summary>
/// <remarks>
/// The client id and the loopback redirect URI are configuration, not secrets, but they are required
/// before anything can happen: an unconfigured build refuses with a sentence naming what to fill in
/// rather than opening a consent page the service will reject.
/// </remarks>
public sealed class OneDriveProviderFactory : IMediaProviderFactory
{
    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.OneDrive,
        "OneDrive",
        "微软 OneDrive（OAuth2 授权码 + PKCE；需先配置 Entra 公共客户端 ID）",
        ProviderCapability.Auth | ProviderCapability.Browse | ProviderCapability.Playback |
        ProviderCapability.Progress);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        credentials ??= new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        OneDriveConfig config = OneDriveConfig.FromSettings(
            credentials.TryGetValue("oneDriveClientId", out string? clientId) ? clientId : null,
            credentials.TryGetValue("oneDriveRedirectUri", out string? redirect) ? redirect : null);

        if (config.Validate() is { } error)
            throw new ProviderException(server.ServerId, ProviderErrorCode.Connection, error);

        credentials.TryGetValue("refreshToken", out string? refreshToken);

        var factory = new MediaSourceHttpClientFactory(policy);
        HttpClient http = factory.CreateApiClient();

        Action<string>? onRefreshToken = null;
        OneDriveProvider? provider = null;
        var api = new OneDriveApi(server.ServerId, config, http, tokens: null, token => onRefreshToken?.Invoke(token));

        if (!string.IsNullOrEmpty(refreshToken))
        {
            // The provider holds the pair the vault gave it; nothing is fetched during construction, so
            // building a handle never touches the network.
            api.Adopt(new OneDriveTokens("", refreshToken, DateTimeOffset.MinValue));
        }

        provider = new OneDriveProvider(server.ServerId, config, api);

        var handle = new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = Descriptor,
            Auth = provider,
            Browse = provider,
            Playback = provider,
            Progress = provider,
        };

        // The provider rotates the refresh token; the owner (ServerManager) is the one holding the vault.
        onRefreshToken = token => handle.OnSessionRenewed?.Invoke(token);

        handle.Own(factory);
        handle.Own(api);
        return handle;
    }
}

/// <summary>
/// Browses one drive and resolves playback through Graph's pre-authenticated download endpoint.
/// </summary>
/// <remarks>
/// Playback is handed to the engine as <c>/content</c> with a bearer header. The HTTP stack answers
/// that with a short-lived signed redirect, which the existing origin-scoped redirect policy follows
/// after stripping the header — so the signature never enters this process' strings, logs or history,
/// and Range requests still reach the storage host because the relay forwards them.
/// </remarks>
internal sealed class OneDriveProvider : IProviderAuth, IProviderBrowse, IProviderPlayback, IProviderProgress
{
    private static readonly IReadOnlyDictionary<string, string> NoHeaders =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

    private readonly string _serverId;
    private readonly OneDriveConfig _config;
    private readonly OneDriveApi _api;
    private string? _accountId;
    private string? _accountName;

    public OneDriveProvider(string serverId, OneDriveConfig config, OneDriveApi api)
    {
        _serverId = serverId;
        _config = config;
        _api = api;
    }

    // ---------------------------------------------------------------- auth

    public SessionMaterial CurrentSession => new(null, _accountId, _accountName);

    public async Task<AuthResult> AuthenticateAsync(Credentials credentials, CancellationToken ct = default)
    {
        if (!string.IsNullOrEmpty(credentials.Token))
            _api.Adopt(new OneDriveTokens("", credentials.Token, DateTimeOffset.MinValue));

        if (!_api.HasSession)
            throw new ProviderException(_serverId, ProviderErrorCode.AuthRequired,
                "需要登录 OneDrive：请在来源页面点击“重新登录”完成浏览器授权。");

        var (id, name) = await _api.GetProfileAsync(ct).ConfigureAwait(false);
        _accountId = id;
        _accountName = name;
        return new AuthResult(id, name);
    }

    public async Task<AuthSessionState> RestoreSessionAsync(CancellationToken ct = default)
    {
        if (!_api.HasSession) return AuthSessionState.NotAuthenticated;

        try
        {
            var (id, name) = await _api.GetProfileAsync(ct).ConfigureAwait(false);
            _accountId = id;
            _accountName = name;
            return AuthSessionState.Authenticated;
        }
        catch (ProviderException ex)
        {
            return ex.Code switch
            {
                ProviderErrorCode.AuthExpired => AuthSessionState.Expired,
                ProviderErrorCode.AuthRequired or ProviderErrorCode.AuthFailed => AuthSessionState.NotAuthenticated,
                ProviderErrorCode.Network or ProviderErrorCode.Connection => AuthSessionState.Unreachable,
                _ => AuthSessionState.NotAuthenticated,
            };
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException)
        {
            return AuthSessionState.Unreachable;
        }
    }

    public Task LogoutAsync(CancellationToken ct = default)
    {
        _api.ClearSession();
        _accountId = null;
        _accountName = null;
        return Task.CompletedTask;
    }

    // ---------------------------------------------------------------- browse

    public async Task<PagedResult<MediaItem>> ListFolderAsync(
        MediaItem? folder, PageRequest page, CancellationToken ct = default)
    {
        // The id is the folder's Graph id: a drive path can be renamed under the user's feet, an id
        // cannot. Root is the only place that is addressed by name.
        string? folderId = folder is null ? null : folder.Path ?? folder.Id;
        int offset = Math.Max(0, page.Offset);
        int limit = Math.Clamp(page.Limit, 1, 500);

        IReadOnlyList<OneDriveEntry> entries = await _api
            .ListChildrenAsync(folderId, offset + limit, ct)
            .ConfigureAwait(false);

        List<MediaItem> mapped = new(entries.Count);
        foreach (OneDriveEntry entry in entries)
        {
            if (entry.IsFolder)
            {
                mapped.Add(new MediaItem
                {
                    ServerId = _serverId,
                    Id = entry.Id,
                    Path = entry.Id,
                    Name = entry.Name,
                    Type = MediaType.Folder,
                });
            }
            else if (IsVideo(entry.Name))
            {
                mapped.Add(new MediaItem
                {
                    ServerId = _serverId,
                    Id = entry.Id,
                    Path = entry.Id,
                    Name = entry.Name,
                    Type = MediaType.Video,
                    FileSizeBytes = entry.Size,
                    RunTimeTicks = entry.DurationMs is { } ms ? ms * TimeSpan.TicksPerMillisecond : null,
                });
            }
        }

        // Folders first, then names: Graph's order is not guaranteed across pages, and the grid is the
        // only thing the user compares two visits by.
        mapped.Sort(static (a, b) =>
        {
            bool aFolder = a.Type == MediaType.Folder;
            bool bFolder = b.Type == MediaType.Folder;
            if (aFolder != bFolder) return aFolder ? -1 : 1;
            return string.Compare(a.Name, b.Name, StringComparison.CurrentCultureIgnoreCase);
        });

        bool hasMore = mapped.Count > offset + limit;
        List<MediaItem> window = offset >= mapped.Count
            ? []
            : mapped.Skip(offset).Take(limit).ToList();

        return new PagedResult<MediaItem>(
            window,
            offset,
            hasMore ? null : mapped.Count);
    }

    private static bool IsVideo(string name)
        => LocalMediaProviderFactory.VideoExtensions.Contains(Path.GetExtension(name));

    // ---------------------------------------------------------------- playback

    /// <summary>
    /// The bearer token is not part of the URL and is only forwarded to the Graph origin; the relay
    /// drops it when the service redirects to the storage host, which is where the signed URL lives.
    /// </summary>
    public IReadOnlyDictionary<string, string> StreamRequestHeaders
    {
        get
        {
            string? token = _api.AccessToken;
            return string.IsNullOrEmpty(token)
                ? NoHeaders
                : new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                {
                    ["Authorization"] = "Bearer " + token,
                };
        }
    }

    public Uri? StreamHeaderOrigin => _config.GraphBase;

    public async Task<PlaybackSource> ResolvePlaybackAsync(
        MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        string itemId = string.IsNullOrEmpty(item.Path) ? item.Id : item.Path;

        // Read the item before handing a URL to the engine. A file that was deleted or closed to this
        // account between listing and clicking would otherwise surface as an engine load failure,
        // which is indistinguishable from a codec problem; this request turns it into the sentence
        // that names the actual cause.
        OneDriveEntry current = await _api.GetItemAsync(itemId, ct).ConfigureAwait(false);
        if (current.IsFolder)
            throw new ProviderException(_serverId, ProviderErrorCode.NotFound,
                "这是一个文件夹，不能直接播放。请先进入该文件夹再选择文件。");

        await _api.EnsureAccessTokenAsync(ct).ConfigureAwait(false);

        string extension = Path.GetExtension(current.Name);
        return new PlaybackSource
        {
            Url = _api.ContentUri(current.Id),
            Headers = NoHeaders,
            Mode = PlaybackMode.DirectPlay,
            Container = extension.Length > 1 ? extension[1..].ToLowerInvariant() : null,
            RunTimeTicks = current.DurationMs is { } ms
                ? ms * TimeSpan.TicksPerMillisecond
                : item.RunTimeTicks,
            StartPositionTicks = options.StartPositionTicks,
        };
    }

    // ---------------------------------------------------------------- progress

    // OneDrive keeps no resume position for this client, so the local snapshot store is the record.
    // Advertising Progress is what makes the coordinator write those snapshots and read them back.
    public Task ReportProgressAsync(PlaybackProgress progress, CancellationToken ct = default)
        => Task.CompletedTask;

    public Task<IReadOnlyList<MediaItem>> GetContinueWatchingAsync(int limit, CancellationToken ct = default)
        => Task.FromResult<IReadOnlyList<MediaItem>>([]);

    public Task<long?> GetResumePositionAsync(string itemId, CancellationToken ct = default)
        => Task.FromResult<long?>(null);
}

using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers;

/// <summary>
/// Self-description published by a provider factory. <see cref="DeclaredCapabilities"/> is what the
/// provider kind is <i>planned</i> to support and is used for display and routing only.
/// </summary>
public sealed record ProviderDescriptor(
    ServerType Type,
    string DisplayName,
    string Description,
    ProviderCapability DeclaredCapabilities);

/// <summary>Result of a successful authentication.</summary>
public sealed record AuthResult(string UserId, string UserName, bool IsAdministrator = false);

/// <summary>Session material kept in memory for the lifetime of a handle.</summary>
public sealed record SessionMaterial(string? Token, string? UserId, string? UserName);

// ---------------------------------------------------------------- capability interfaces
// Optional capabilities, mirroring the Android ADR-014 composition model. A provider implements only
// what it genuinely supports; the handle exposes nullable members and the UI branches on those,
// never on ServerType.

public interface IProviderAuth
{
    /// <summary>
    /// Live session material, read from the provider rather than copied by the caller.
    /// </summary>
    /// <remarks>
    /// A copy kept on the handle goes stale the moment the provider refreshes or revokes its token,
    /// and a stale token is indistinguishable from a wrong one at the call site. Reading through the
    /// provider makes the provider the single source of truth.
    /// </remarks>
    SessionMaterial CurrentSession { get; }

    Task<AuthResult> AuthenticateAsync(Credentials credentials, CancellationToken ct = default);
    Task<AuthSessionState> RestoreSessionAsync(CancellationToken ct = default);
    Task LogoutAsync(CancellationToken ct = default);
}

public interface IProviderLibrary
{
    Task<IReadOnlyList<MediaLibrary>> GetLibrariesAsync(CancellationToken ct = default);
    Task<PagedResult<MediaItem>> GetItemsAsync(string libraryId, PageRequest page, CancellationToken ct = default);

    /// <summary>
    /// Seasons of a series. Implemented in terms of the ordinary item query (parent = series), the
    /// same choice the Android client made: a dedicated endpoint would duplicate paging, filtering
    /// and mapping for no behavioural gain.
    /// </summary>
    Task<IReadOnlyList<Season>> GetSeasonsAsync(string seriesId, CancellationToken ct = default);

    /// <summary>Episodes of a season (parent = season).</summary>
    Task<IReadOnlyList<Episode>> GetEpisodesAsync(string seasonId, CancellationToken ct = default);

    /// <summary>
    /// The episode that follows the given one, across season boundaries, or null when the series ends.
    /// </summary>
    /// <remarks>
    /// Default null means "this source cannot tell me", which disables auto-advance for that source
    /// rather than guessing. Guessing a successor from a name would be worse than not advancing:
    /// a wrong guess plays the wrong file, whereas a null just leaves the user where they are.
    /// </remarks>
    Task<Episode?> GetNextEpisodeAsync(string seriesId, string currentEpisodeId, CancellationToken ct = default)
        => Task.FromResult<Episode?>(null);
}

public interface IProviderDetail
{
    Task<MediaDetail> GetItemDetailAsync(string itemId, CancellationToken ct = default);
}

public interface IProviderBrowse
{
    Task<PagedResult<MediaItem>> ListFolderAsync(MediaItem? folder, PageRequest page, CancellationToken ct = default);
}

public interface IProviderPlayback
{
    Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default);

    /// <summary>
    /// Session headers that authenticate a media stream request, or empty when the protocol needs
    /// none. Memory-only, and only ever sent to this source's own origin.
    /// </summary>
    IReadOnlyDictionary<string, string> StreamRequestHeaders { get; }

    /// <summary>
    /// Origin those headers belong to, or null when there is no such restriction.
    /// </summary>
    /// <remarks>
    /// A server may hand back a stream URL on a different host (object storage, a CDN, a second
    /// node). Sending the session token there would repeat the very leak the bridge exists to
    /// prevent, so the caller compares this against the stream URL before attaching anything.
    /// </remarks>
    Uri? StreamHeaderOrigin { get; }
}

public interface IProviderSearch
{
    Task<PagedResult<MediaItem>> SearchAsync(string query, PageRequest page, CancellationToken ct = default);
}

public interface IProviderProgress
{
    /// <summary>
    /// Playback has begun. Sent once per session, before the first periodic report.
    /// </summary>
    /// <remarks>
    /// A default no-op rather than an abstract member: a protocol that has no start notification
    /// should not be forced to invent one, and a provider that forgets to implement it degrades to
    /// "no start event" instead of failing to compile.
    /// </remarks>
    Task ReportPlaybackStartedAsync(PlaybackProgress progress, CancellationToken ct = default)
        => Task.CompletedTask;

    /// <summary>Throttled periodic report.</summary>
    Task ReportProgressAsync(PlaybackProgress progress, CancellationToken ct = default);

    /// <summary>
    /// Exactly-once end-of-playback report. Default falls through to
    /// <see cref="ReportProgressAsync"/> for protocols without session-lifecycle semantics.
    /// </summary>
    Task ReportFinalProgressAsync(PlaybackProgress progress, CancellationToken ct = default)
        => ReportProgressAsync(progress, ct);

    Task<IReadOnlyList<MediaItem>> GetContinueWatchingAsync(int limit, CancellationToken ct = default);
    Task<long?> GetResumePositionAsync(string itemId, CancellationToken ct = default);

    /// <summary>Minimum interval between remote reports; providers override per protocol guidance.</summary>
    TimeSpan RemoteReportInterval => TimeSpan.FromSeconds(10);
}

/// <summary>
/// Server-side favourites: marking an item as a favourite and listing what is favourited.
/// </summary>
/// <remarks>
/// <para>
/// Separate from <see cref="IProviderDetail"/> because favouriting is a write with a lifetime, not a
/// read: a provider that can show an item but not persist its flag must not claim the capability.
/// </para>
/// <para>
/// Both Emby 4.10.2 and Jellyfin 12.2 answer the write on
/// <c>POST|DELETE /Users/{userId}/FavoriteItems/{itemId}</c> and answer <b>404</b> on
/// <c>/Users/{userId}/Favorites/{itemId}</c> — measured on both servers, not inferred from docs.
/// The write returns no body, so <see cref="SetFavoriteAsync"/> re-reads the item and returns the
/// state the server actually stored rather than echoing the request.
/// </para>
/// </remarks>
public interface IProviderFavorites
{
    /// <summary>
    /// Marks or unmarks one item, returning the favourite state the server reports afterwards.
    /// </summary>
    /// <remarks>
    /// The returned value is the server's answer, not the requested value. If the write is silently
    /// ignored (a read-only library, a permission the account lacks) the caller sees the unchanged
    /// state instead of a UI that lies about having saved it.
    /// </remarks>
    Task<bool> SetFavoriteAsync(string itemId, bool favorite, CancellationToken ct = default);

    /// <summary>Items the current user has favourited, newest first as the server orders them.</summary>
    Task<IReadOnlyList<MediaItem>> GetFavoritesAsync(int limit, CancellationToken ct = default);
}

public interface IProviderSubtitle
{
    Task<IReadOnlyList<SubtitleTrack>> GetSubtitlesAsync(string itemId, CancellationToken ct = default);
}

/// <summary>
/// Artwork retrieval.
/// </summary>
/// <remarks>
/// Bytes rather than a URL, deliberately. A URL would have to carry the token (forbidden) or be
/// fetched by a loader that does not know the source's credential and routing rules. Returning bytes
/// keeps artwork inside exactly the same network boundary as the rest of the source's traffic.
/// </remarks>
public interface IProviderImageFetcher
{
    /// <param name="imageType">Provider image kind, e.g. <c>Primary</c> or <c>Backdrop</c>.</param>
    Task<byte[]> FetchImageAsync(string itemId, string imageType, string? tag, int maxWidth, CancellationToken ct = default);
}

// ---------------------------------------------------------------- handle

/// <summary>
/// A configured source, exposing exactly the capabilities it implements.
/// </summary>
/// <remarks>
/// <see cref="RuntimeCapabilities"/> is derived from which capability objects are present, so it can
/// never drift from the implementation: adding a capability to a provider automatically advertises
/// it, and a provider that throws <see cref="ProviderException"/> NotYetImplemented does not
/// advertise the capability in the first place.
/// </remarks>
public sealed class ProviderHandle : IDisposable
{
    public required string ServerId { get; init; }
    public required MediaServer Server { get; init; }
    public required ProviderDescriptor Descriptor { get; init; }

    private SessionMaterial _session = new(null, null, null);

    /// <summary>Session material for this source; delegates to the auth provider when one exists.</summary>
    public SessionMaterial Session
    {
        get => Auth?.CurrentSession ?? _session;
        set => _session = value;
    }

    public IProviderAuth? Auth { get; init; }
    public IProviderLibrary? Library { get; init; }
    public IProviderDetail? Detail { get; init; }
    public IProviderBrowse? Browse { get; init; }
    public IProviderPlayback? Playback { get; init; }
    public IProviderSearch? Search { get; init; }
    public IProviderProgress? Progress { get; init; }
    public IProviderFavorites? Favorites { get; init; }
    public IProviderSubtitle? Subtitle { get; init; }
    public IProviderImageFetcher? Images { get; init; }

    /// <summary>
    /// Callback invoked when the provider observes that the session is no longer valid.
    /// </summary>
    /// <remarks>Settable rather than init-only: the owner attaches it after the handle is built.</remarks>
    public Action? OnSessionExpired { get; set; }

    private readonly List<IDisposable> _owned = new();

    /// <summary>
    /// Registers something the handle must dispose with it.
    /// </summary>
    /// <remarks>
    /// Public rather than internal because provider factories live in their own assemblies: a factory
    /// that builds an HTTP client per source has to hand its lifetime to the handle, and the
    /// alternative — having the factory implement IDisposable and hoping someone calls it — leaves the
    /// connection pool alive for the process lifetime, since nothing enumerates factories on shutdown.
    /// </remarks>
    public void Own(IDisposable disposable) => _owned.Add(disposable);

    public ProviderCapability RuntimeCapabilities
    {
        get
        {
            ProviderCapability caps = ProviderCapability.None;
            if (Auth is not null) caps |= ProviderCapability.Auth;
            if (Library is not null) caps |= ProviderCapability.Library;
            if (Detail is not null) caps |= ProviderCapability.Detail;
            if (Browse is not null) caps |= ProviderCapability.Browse;
            if (Playback is not null) caps |= ProviderCapability.Playback;
            if (Search is not null) caps |= ProviderCapability.Search;
            if (Progress is not null) caps |= ProviderCapability.Progress;
            if (Favorites is not null) caps |= ProviderCapability.Favorite;
            if (Subtitle is not null) caps |= ProviderCapability.Subtitle;
            return caps;
        }
    }

    public string CapabilitySummary
    {
        get
        {
            var names = Enum.GetValues<ProviderCapability>()
                .Where(c => c != ProviderCapability.None && RuntimeCapabilities.HasFlag(c))
                .Select(c => c.ToString());
            string joined = string.Join(" / ", names);
            return joined.Length == 0 ? "尚无可用能力" : joined;
        }
    }

    public void Dispose()
    {
        foreach (var d in _owned)
        {
            try { d.Dispose(); } catch { /* teardown must not throw */ }
        }
        _owned.Clear();
    }
}

/// <summary>Creates handles for one provider kind.</summary>
public interface IMediaProviderFactory
{
    ProviderDescriptor Descriptor { get; }

    /// <summary>
    /// Builds a handle for a configured server. The factory owns the HTTP client lifetime; the handle
    /// owns it thereafter.
    /// </summary>
    ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null);
}

/// <summary>
/// Registry of available provider kinds.
/// </summary>
/// <remarks>
/// The "add a media source" UI renders from <see cref="Descriptors"/>, so introducing a provider does
/// not require touching the UI — the same property the Android registry provides via DI set injection.
/// </remarks>
public sealed class ProviderRegistry
{
    private readonly List<IMediaProviderFactory> _factories = new();

    public void Register(IMediaProviderFactory factory)
    {
        _factories.RemoveAll(f => f.Descriptor.Type == factory.Descriptor.Type);
        _factories.Add(factory);
    }

    public IReadOnlyList<ProviderDescriptor> Descriptors => _factories.Select(f => f.Descriptor).ToList();

    public IMediaProviderFactory? Find(ServerType type) => _factories.FirstOrDefault(f => f.Descriptor.Type == type);

    public ProviderHandle? Create(MediaServer server, NetworkPolicy? policy = null, IReadOnlyDictionary<string, string>? credentials = null)
        => Find(server.Type)?.Create(server, policy ?? NetworkPolicy.FollowSystem, credentials);
}

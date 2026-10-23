using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.MediaBrowser;

/// <summary>
/// Emby/Jellyfin provider implementation, parameterised by <see cref="MediaBrowserDialect"/>.
/// </summary>
/// <remarks>
/// One implementation serves both servers because their protocol is one protocol. The alternative —
/// two near-identical classes — is what makes a fix in one silently fail to reach the other, which is
/// exactly the failure mode the Android client removed when Emby and Jellyfin were unified behind a
/// shared contract.
/// </remarks>
internal sealed class MediaBrowserProvider :
    IProviderAuth,
    IProviderLibrary,
    IProviderDetail,
    IProviderPlayback,
    IProviderSearch,
    IProviderProgress,
    IProviderFavorites,
    IProviderSubtitle,
    IProviderImageFetcher
{
    private readonly MediaBrowserApi _api;
    private readonly string _serverId;
    private readonly Action? _onSessionExpired;

    public MediaBrowserProvider(MediaBrowserApi api, string serverId, SessionMaterial session, Action? onSessionExpired)
    {
        _api = api;
        _serverId = serverId;
        _onSessionExpired = onSessionExpired;
        _api.Token = session.Token;
        _api.UserId = session.UserId;
    }

    public SessionMaterial CurrentSession => new(_api.Token, _api.UserId, null);

    // ---------------------------------------------------------------- auth

    public async Task<AuthResult> AuthenticateAsync(Credentials credentials, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(credentials.Username))
            throw ProviderException.AuthFailed(_serverId, "必须提供用户名");

        using var doc = await _api.AuthenticateByNameAsync(credentials.Username!, credentials.Password ?? "", ct).ConfigureAwait(false);

        string? token = MediaBrowserMapper.GetString(doc.RootElement, "AccessToken");
        string? userId = MediaBrowserMapper.GetString(doc.RootElement, "User", "Id");
        string? userName = MediaBrowserMapper.GetString(doc.RootElement, "User", "Name");
        if (string.IsNullOrEmpty(token) || string.IsNullOrEmpty(userId))
            throw ProviderException.Parse(_serverId);

        _api.Token = token;
        _api.UserId = userId;
        return new AuthResult(userId!, userName ?? credentials.Username!, MediaBrowserMapper.GetBool(doc.RootElement, "User", "Policy", "IsAdministrator") ?? false);
    }

    public async Task<AuthSessionState> RestoreSessionAsync(CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(_api.Token) || string.IsNullOrEmpty(_api.UserId)) return AuthSessionState.NotAuthenticated;

        try
        {
            // /Users/{id} first, /Users/Me as the fallback - in that order, for a measured reason.
            // Emby 4.10.1.0 answers /Users/Me with HTTP 500 "Unrecognized Guid format.", because its
            // item and user ids are short numerics ("5", "6") rather than GUIDs, while
            // /Users/{userId} returns the full profile on BOTH Emby and Jellyfin. The original probe
            // (/Users/{id}/Items/{id}) was wrong on both; /Users/Me alone fixed Jellyfin and broke
            // Emby, which is exactly the failure mode of applying one server's conventions to
            // another. Fetching the id that was already returned at login is the portable choice.
            bool ok = await TryGetUserAsync(_api.UserId, ct).ConfigureAwait(false);
            if (!ok) ok = await TryGetCurrentUserAsync(ct).ConfigureAwait(false);
            return ok ? AuthSessionState.Authenticated : AuthSessionState.Expired;
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.NotFound)
        {
            // The token is well-formed but names no user on this server: the session cannot be used.
            _onSessionExpired?.Invoke();
            return AuthSessionState.Expired;
        }
        catch (ProviderHttpException ex) when (ex.StatusCode == 401)
        {
            _onSessionExpired?.Invoke();
            return AuthSessionState.Expired;
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.AuthExpired)
        {
            _onSessionExpired?.Invoke();
            return AuthSessionState.Expired;
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.Network or ProviderErrorCode.Connection)
        {
            // Unreachable is not the same as unauthenticated: the token is kept so a later attempt can succeed.
            return AuthSessionState.Unreachable;
        }
    }

    private async Task<bool> TryGetUserAsync(string? userId, CancellationToken ct)
    {
        if (string.IsNullOrEmpty(userId)) return false;
        try
        {
            using var doc = await _api.GetUserAsync(userId!, ct).ConfigureAwait(false);
            return true;
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.NotFound or ProviderErrorCode.Parse)
        {
            return false;
        }
    }

    private async Task<bool> TryGetCurrentUserAsync(CancellationToken ct)
    {
        try
        {
            using var doc = await _api.GetCurrentUserAsync(ct).ConfigureAwait(false);
            return true;
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.NotFound or ProviderErrorCode.Parse)
        {
            return false;
        }
    }

    public async Task LogoutAsync(CancellationToken ct = default)
    {
        await _api.LogoutAsync(ct).ConfigureAwait(false);
        _api.Token = null;
        _api.UserId = null;
    }

    // ---------------------------------------------------------------- library

    public async Task<IReadOnlyList<MediaLibrary>> GetLibrariesAsync(CancellationToken ct = default)
    {
        using var doc = await _api.GetViewsAsync(ct).ConfigureAwait(false);
        return MediaBrowserMapper.Items(doc).Select(MediaBrowserMapper.MapLibrary).ToList();
    }

    public async Task<PagedResult<MediaItem>> GetItemsAsync(string libraryId, PageRequest page, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemsAsync(libraryId, page, null, ct).ConfigureAwait(false);
        var items = MediaBrowserMapper.Items(doc)
            .Select(i => MediaBrowserMapper.MapItem(_serverId, i))
            .Where(i => !IsBluRayIso(i))
            .ToList();
        return new PagedResult<MediaItem>(items, page.Offset, MediaBrowserMapper.TotalRecordCount(doc));
    }

    /// <summary>
    /// Skips Blu-ray ISO images.
    /// </summary>
    /// <remarks>
    /// Emby labels <c>.iso</c> files with <c>Container=mpegts</c>, which makes them look like a
    /// playable transport stream while the engine cannot open them. The path is the only reliable
    /// signal, and the query string is stripped first because the reported path can carry one.
    /// </remarks>
    internal static bool IsBluRayIso(MediaItem item)
    {
        string? path = item.Path ?? item.Id;
        if (string.IsNullOrEmpty(path)) return false;
        string bare = path.Split('?')[0];
        return bare.EndsWith(".iso", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<IReadOnlyList<Season>> GetSeasonsAsync(string seriesId, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemsAsync(seriesId, new PageRequest(0, 200), "Season", ct).ConfigureAwait(false);
        return MediaBrowserMapper.Items(doc).Select(MediaBrowserMapper.MapSeason).ToList();
    }

    public async Task<IReadOnlyList<Episode>> GetEpisodesAsync(string seasonId, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemsAsync(seasonId, new PageRequest(0, 500), "Episode", ct).ConfigureAwait(false);
        return MediaBrowserMapper.Items(doc).Select(MediaBrowserMapper.MapEpisode).ToList();
    }

    /// <summary>
    /// The episode that follows the given one, crossing season boundaries, or null at the end.
    /// </summary>
    /// <remarks>
    /// Ordering is computed here from the real season and episode numbers rather than taken from the
    /// server's response order, because a server is free to return episodes in any order and a
    /// "next episode" that depends on response ordering would silently skip or repeat episodes.
    /// Missing numbers are treated as "unknown" and sorted last within their season instead of being
    /// guessed, so an episode without an index never displaces a real one. The mapper folds a missing
    /// number into 0, so 0 is read as "unknown" here; a genuine season 0 / episode 0 (specials) is
    /// therefore also ordered last, which is the safe direction for "next episode": it is never played
    /// before a numbered episode of the same season.
    /// </remarks>
    public async Task<Episode?> GetNextEpisodeAsync(string seriesId, string currentEpisodeId, CancellationToken ct = default)
    {
        using var doc = await _api.GetAllEpisodesAsync(seriesId, ct).ConfigureAwait(false);
        List<Episode> episodes = MediaBrowserMapper.Items(doc).Select(MediaBrowserMapper.MapEpisode).ToList();
        if (episodes.Count == 0) return null;

        List<Episode> ordered = episodes
            .OrderBy(e => e.SeasonNumber > 0 ? e.SeasonNumber : int.MaxValue)
            .ThenBy(e => e.EpisodeNumber > 0 ? e.EpisodeNumber : int.MaxValue)
            .ThenBy(e => e.Id, StringComparer.Ordinal)
            .ToList();

        int index = ordered.FindIndex(e => string.Equals(e.Id, currentEpisodeId, StringComparison.Ordinal));
        if (index < 0) return null;
        return index + 1 < ordered.Count ? ordered[index + 1] : null;
    }

    // ---------------------------------------------------------------- detail

    public async Task<MediaDetail> GetItemDetailAsync(string itemId, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemAsync(itemId, ct).ConfigureAwait(false);
        var item = MediaBrowserMapper.MapItem(_serverId, doc.RootElement);
        return new MediaDetail
        {
            Item = item,
            Tagline = MediaBrowserMapper.GetString(doc.RootElement, "Taglines", "0"),
            OfficialRating = MediaBrowserMapper.GetString(doc.RootElement, "OfficialRating"),
            Studios = MediaBrowserMapper.GetStringArray(doc.RootElement, "Studios", "0", "Name"),
            Streams = MediaBrowserMapper.MapStreams(doc.RootElement),
        };
    }

    // ---------------------------------------------------------------- favourites

    public async Task<bool> SetFavoriteAsync(string itemId, bool favorite, CancellationToken ct = default)
    {
        await _api.SetFavoriteAsync(itemId, favorite, ct).ConfigureAwait(false);

        // Read back rather than echo the request: the write answers 200 with an empty body on both
        // servers, so "no exception" only proves the request was accepted, not that the flag changed.
        // A library the account can read but not write would otherwise leave the UI showing a star the
        // server never stored.
        using var doc = await _api.GetItemAsync(itemId, ct).ConfigureAwait(false);
        return MediaBrowserMapper.GetBool(doc.RootElement, "UserData", "IsFavorite") ?? favorite;
    }

    public async Task<IReadOnlyList<MediaItem>> GetFavoritesAsync(int limit, CancellationToken ct = default)
    {
        if (limit <= 0) return Array.Empty<MediaItem>();
        using var doc = await _api.GetFavoritesAsync(limit, ct).ConfigureAwait(false);
        return MediaBrowserMapper.Items(doc)
            .Select(i => MediaBrowserMapper.MapItem(_serverId, i))
            .Where(i => !IsBluRayIso(i))
            .ToList();
    }

    // ---------------------------------------------------------------- playback

    public async Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        using var doc = await _api.GetPlaybackInfoAsync(item.Id, options, ct).ConfigureAwait(false);

        if (!doc.RootElement.TryGetProperty("MediaSources", out var sources) || sources.ValueKind != JsonValueKind.Array || sources.GetArrayLength() == 0)
        {
            // An empty MediaSources array is a damaged response, not a request to transcode.
            throw ProviderException.Parse(_serverId);
        }

        JsonElement source = sources[0];
        string? mediaSourceId = MediaBrowserMapper.GetString(source, "Id");
        string? container = MediaBrowserMapper.GetString(source, "Container");

        // PlaySessionId lives at the ROOT of the PlaybackInfo response on Jellyfin 12.2; it is not a
        // field of the media source at all. Reading it only from the source made every real-server
        // playback fail with a parse error while the mock - which mirrored the client's mistaken
        // assumption - kept the suite green. The source is still checked as a fallback because a
        // server version that does nest it must keep working.
        string? playSessionId = MediaBrowserMapper.GetString(doc.RootElement, "PlaySessionId")
                                ?? MediaBrowserMapper.GetString(source, "PlaySessionId");

        if (string.IsNullOrEmpty(mediaSourceId))
            throw ProviderException.Parse(_serverId);

        // A missing PlaySessionId is a broken response rather than something to invent: the
        // direct-stream URL needs one and providers reject progress bodies without it.
        if (string.IsNullOrEmpty(playSessionId))
            throw ProviderException.Parse(_serverId);

        bool supportsDirectStream = MediaBrowserMapper.GetBool(source, "SupportsDirectStream") ?? true;
        Uri url = supportsDirectStream
            ? _api.DirectStreamUrl(item.Id, container ?? "mp4", mediaSourceId!, playSessionId!)
            : _api.DirectStreamUrl(item.Id, container ?? "mp4", mediaSourceId!, playSessionId!);

        long? runTime = MediaBrowserMapper.GetLong(source, "RunTimeTicks") ?? item.RunTimeTicks;
        return new PlaybackSource
        {
            Url = url,
            Mode = PlaybackMode.Stream,
            SessionId = playSessionId,
            Container = container,
            MediaSourceId = mediaSourceId,
            RunTimeTicks = runTime,
            StartPositionTicks = options.StartPositionTicks,
            Streams = MediaBrowserMapper.MapStreams(source),
            Headers = BuildPlaybackHeaders(source),
        };
    }

    /// <summary>
    /// Headers the server explicitly asked for on the media request.
    /// </summary>
    /// <remarks>
    /// Authentication headers are excluded on purpose: they are attached by the client's own
    /// origin-scoped handler, which is the component that knows how to strip them on a cross-origin
    /// redirect. Letting a server-supplied header carry identity would bypass that check.
    /// </remarks>
    private static IReadOnlyDictionary<string, string> BuildPlaybackHeaders(JsonElement source)
    {
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!source.TryGetProperty("RequiredHttpHeaders", out var required) || required.ValueKind != JsonValueKind.Object)
            return headers;

        foreach (var property in required.EnumerateObject())
        {
            if (OriginScopedCredentialHandler.IsSensitive(property.Name)) continue;
            if (property.Value.ValueKind != JsonValueKind.String) continue;
            headers[property.Name] = property.Value.GetString()!;
        }
        return headers;
    }

    public IReadOnlyDictionary<string, string> StreamRequestHeaders => _api.BuildStreamHeaders();

    public Uri? StreamHeaderOrigin => _api.ApiOrigin;

    // ---------------------------------------------------------------- search

    public async Task<PagedResult<MediaItem>> SearchAsync(string query, PageRequest page, CancellationToken ct = default)
    {
        using var doc = await _api.SearchAsync(query, page, ct).ConfigureAwait(false);
        var items = MediaBrowserMapper.Items(doc)
            .Select(i => MediaBrowserMapper.MapItem(_serverId, i))
            .Where(i => !IsBluRayIso(i))
            .ToList();
        return new PagedResult<MediaItem>(items, page.Offset, MediaBrowserMapper.TotalRecordCount(doc));
    }

    // ---------------------------------------------------------------- progress

    public TimeSpan RemoteReportInterval => TimeSpan.FromSeconds(10);

    public Task ReportProgressAsync(PlaybackProgress progress, CancellationToken ct = default)
        => ReportAsync("Progress", progress, ct);

    public Task ReportPlaybackStartedAsync(PlaybackProgress progress, CancellationToken ct = default)
        => ReportAsync("Start", progress, ct);

    public async Task ReportFinalProgressAsync(PlaybackProgress progress, CancellationToken ct = default)
    {
        await ReportAsync("Stopped", progress, ct).ConfigureAwait(false);
    }

    private async Task ReportAsync(string phase, PlaybackProgress progress, CancellationToken ct)
    {
        // The session id is mandatory: without it the server answers 400 with a null-key error.
        // There is no fallback that is not a fabrication, so the report is refused instead.
        if (string.IsNullOrWhiteSpace(progress.SessionId))
            throw ProviderException.Parse(_serverId);

        var payload = new Dictionary<string, object?>
        {
            ["ItemId"] = progress.ItemId,
            ["PlaySessionId"] = progress.SessionId,
            // PositionTicks is always sent, including zero: an omitted value makes the server treat
            // the item as fully watched, which corrupts the user's resume state server-side.
            ["PositionTicks"] = progress.PositionTicks,
            ["IsPaused"] = progress.IsPaused,
            ["IsMuted"] = progress.IsMuted,
            ["PlayMethod"] = progress.Mode switch
            {
                PlaybackMode.DirectPlay => "DirectPlay",
                PlaybackMode.Stream => "DirectStream",
                _ => "Transcode",
            },
        };

        string endpoint = phase switch
        {
            "Stopped" => "/Sessions/Playing/Stopped",
            "Start" => "/Sessions/Playing",
            _ => "/Sessions/Playing/Progress",
        };

        try
        {
            await _api.ReportPlaybackAsync(endpoint, payload, ct).ConfigureAwait(false);
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.AuthExpired or ProviderErrorCode.Http or ProviderErrorCode.Network or ProviderErrorCode.Connection or ProviderErrorCode.Parse)
        {
            // Progress reporting is best effort: losing a sample must never interrupt playback or
            // throw into the player's exit path. 401 is not treated as a session kill here — the
            // restore path owns that decision.
            if (ex.Code == ProviderErrorCode.AuthExpired) _onSessionExpired?.Invoke();
        }
    }

    public async Task<IReadOnlyList<MediaItem>> GetContinueWatchingAsync(int limit, CancellationToken ct = default)
    {
        var query = new PageRequest(0, limit);
        using var doc = await _api.GetItemsAsync(string.Empty, query, "Movie,Episode", ct).ConfigureAwait(false);
        return MediaBrowserMapper.Items(doc)
            .Select(i => MediaBrowserMapper.MapItem(_serverId, i))
            .Where(i => i.UserDataPlaybackPositionTicks is > 0 && i.UserDataPlayed != true)
            .Take(limit)
            .ToList();
    }

    public async Task<long?> GetResumePositionAsync(string itemId, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemAsync(itemId, ct).ConfigureAwait(false);
        return MediaBrowserMapper.GetLong(doc.RootElement, "UserData", "PlaybackPositionTicks");
    }

    // ---------------------------------------------------------------- subtitles

    public async Task<IReadOnlyList<SubtitleTrack>> GetSubtitlesAsync(string itemId, CancellationToken ct = default)
    {
        using var doc = await _api.GetItemAsync(itemId, ct).ConfigureAwait(false);
        var tracks = new List<SubtitleTrack>();
        foreach (var stream in MediaBrowserMapper.MapStreams(doc.RootElement))
        {
            if (stream.Type != "subtitle") continue;
            tracks.Add(new SubtitleTrack(
                stream.Index,
                stream.Language,
                stream.DisplayTitle,
                stream.Codec,
                stream.IsExternal,
                stream.IsDefault,
                IsForced: false,
                DeliveryUrl: _api.SubtitleUrl(itemId, stream.Index, stream.Codec).ToString()));
        }
        return tracks;
    }

    // ---------------------------------------------------------------- artwork

    public async Task<byte[]> FetchImageAsync(string itemId, string imageType, string? tag, int maxWidth, CancellationToken ct = default)
        => await _api.GetImageBytesAsync(itemId, imageType, tag, maxWidth, ct).ConfigureAwait(false);

    // ---------------------------------------------------------------- session

    public void UpdateSession(SessionMaterial session)
    {
        _api.Token = session.Token;
        _api.UserId = session.UserId;
    }
}

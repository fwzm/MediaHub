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
    IProviderSubtitleContent,
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
        List<Episode> ordered = await GetOrderedEpisodesAsync(seriesId, ct).ConfigureAwait(false);
        int index = ordered.FindIndex(e => string.Equals(e.Id, currentEpisodeId, StringComparison.Ordinal));
        if (index < 0) return null;
        return index + 1 < ordered.Count ? ordered[index + 1] : null;
    }

    /// <summary>The episode before <paramref name="currentEpisodeId"/> in the same series, or null.</summary>
    /// <remarks>Mirrors <see cref="GetNextEpisodeAsync"/>; the ordering is shared so both directions agree.</remarks>
    public async Task<Episode?> GetPreviousEpisodeAsync(string seriesId, string currentEpisodeId, CancellationToken ct = default)
    {
        List<Episode> ordered = await GetOrderedEpisodesAsync(seriesId, ct).ConfigureAwait(false);
        int index = ordered.FindIndex(e => string.Equals(e.Id, currentEpisodeId, StringComparison.Ordinal));
        if (index <= 0) return null;
        return ordered[index - 1];
    }

    private async Task<List<Episode>> GetOrderedEpisodesAsync(string seriesId, CancellationToken ct)
    {
        using var doc = await _api.GetAllEpisodesAsync(seriesId, ct).ConfigureAwait(false);
        List<Episode> episodes = MediaBrowserMapper.Items(doc).Select(MediaBrowserMapper.MapEpisode).ToList();

        return episodes
            .OrderBy(e => e.SeasonNumber > 0 ? e.SeasonNumber : int.MaxValue)
            .ThenBy(e => e.EpisodeNumber > 0 ? e.EpisodeNumber : int.MaxValue)
            .ThenBy(e => e.Id, StringComparer.Ordinal)
            .ToList();
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
            Versions = MediaBrowserMapper.MapVersions(doc.RootElement),
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
        // Forced transcode skips the direct-play probe on purpose: the caller has already decided that
        // this playback should be an HLS transcode, so the first and only negotiation carries the HLS
        // profile. Probing direct play first would answer a question the caller did not ask, and
        // returning a direct URL here would contradict the switch. A server that still refuses to
        // transcode produces a readable failure instead (see ResolveTranscodedAsync) rather than a
        // silent direct play.
        if (options.ForceTranscode)
            return await ResolveTranscodedAsync(item, options, ct, forced: true, options.MediaSourceId).ConfigureAwait(false);

        using var doc = await _api.GetPlaybackInfoAsync(item.Id, options, enableTranscoding: false, deviceProfile: null, ct).ConfigureAwait(false);

        if (!doc.RootElement.TryGetProperty("MediaSources", out var sources) || sources.ValueKind != JsonValueKind.Array || sources.GetArrayLength() == 0)
        {
            // An empty MediaSources array is a damaged response, not a request to transcode.
            throw ProviderException.Parse(_serverId);
        }

        // An explicit version is a promise to the caller, not a hint: it is the version the user
        // picked in the picker, so falling back to another one would play the wrong file while the UI
        // still showed the chosen version. A version that the server now reports as transcode-only is
        // handed to the HLS negotiation for that same version, and an id the response no longer
        // carries is a readable failure rather than a silent substitution.
        JsonElement? requested = null;
        if (!string.IsNullOrEmpty(options.MediaSourceId))
        {
            foreach (JsonElement candidate in sources.EnumerateArray())
            {
                if (string.Equals(MediaBrowserMapper.GetString(candidate, "Id"), options.MediaSourceId, StringComparison.Ordinal))
                {
                    requested = candidate;
                    break;
                }
            }
            if (requested is null)
            {
                throw ProviderException.Connection(_serverId,
                    "所选片源版本已不在服务器返回的版本列表中（版本可能已被删除或重新扫描），已停止播放");
            }
        }

        // A server answers with every version it holds — a 4K remux, a 1080p copy, an external subtitle
        // sidecar-only source — and the order is not a promise about playability. Taking the first entry
        // therefore meant "play whatever the server listed first", which for a source the server itself
        // marks unplayable is exactly the wrong choice. Pick the first source that can be played as-is
        // and only fall through to a transcode when the server says none of them can.
        //
        // Measured on real servers: the first request deliberately carries no DeviceProfile, and both
        // Jellyfin 10.x and Emby 4.10.1.0 then answer SupportsDirectPlay=True for every source tried —
        // including mpeg2, VP9, Xvid/AVI, WMV, Theora, a corrupt file and a remote .strm. The flags are
        // computed from the CLIENT's profile: the same item comes back with SupportsDirectPlay=False as
        // soon as any DeviceProfile is attached. Server-side policies that should force a transcode
        // (a bitrate limit, ForceRemoteSourceTranscoding) do not change the flags without one either.
        // So this fallback is reachable only for servers that report false on their own — or for a
        // request that explicitly asked for a transcode (the player's force-transcode control), which
        // bypasses the probe above entirely. It is kept because a direct-play-first client must still
        // have an answer for a server that reports false by itself.
        JsonElement? directPlay = null;
        JsonElement? directStream = null;
        if (requested is { } only)
        {
            bool onlyDirectPlay = MediaBrowserMapper.GetBool(only, "SupportsDirectPlay") ?? true;
            bool onlyDirectStream = MediaBrowserMapper.GetBool(only, "SupportsDirectStream") ?? true;
            if (!onlyDirectPlay && !onlyDirectStream)
            {
                // The server says this exact version has no direct path. Ask for a transcode of it,
                // and if the server cannot produce one the message names the version that failed.
                return await ResolveTranscodedAsync(item, options, ct, forced: false, options.MediaSourceId).ConfigureAwait(false);
            }
            if (onlyDirectPlay) directPlay = only;
            else directStream = only;
        }
        else
        {
            var playable = new List<JsonElement>();
            var streamable = new List<JsonElement>();
            foreach (JsonElement candidate in sources.EnumerateArray())
            {
                bool candidateDirectPlay = MediaBrowserMapper.GetBool(candidate, "SupportsDirectPlay") ?? true;
                bool candidateDirectStream = MediaBrowserMapper.GetBool(candidate, "SupportsDirectStream") ?? true;
                if (candidateDirectPlay) playable.Add(candidate);
                else if (candidateDirectStream) streamable.Add(candidate);
            }

            // Quality preference only reorders versions that can already be played as-is, so the
            // direct-first contract is unchanged; "auto" keeps the server order exactly as before.
            directPlay = PickByPreference(playable, options.QualityPreference);
            directStream = PickByPreference(streamable, options.QualityPreference);
        }

        JsonElement? selected = directPlay ?? directStream;
        if (selected is null)
        {
            // The server rejected both direct play and direct stream for every version, so ask it for an
            // HLS transcode: the engine can play an m3u8 playlist that the relay serves without ever
            // holding a server credential.
            return await ResolveTranscodedAsync(item, options, ct).ConfigureAwait(false);
        }

        JsonElement source = selected.Value;
        bool isDirectPlay = MediaBrowserMapper.GetBool(source, "SupportsDirectPlay") ?? true;
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

        // static=true asks for the file itself, never a transcoded variant, which is what both modes
        // below mean. The chosen mode only changes what is reported back to the server.
        Uri url = _api.DirectStreamUrl(item.Id, container ?? "mp4", mediaSourceId!, playSessionId!);

        long? runTime = MediaBrowserMapper.GetLong(source, "RunTimeTicks") ?? item.RunTimeTicks;
        return new PlaybackSource
        {
            Url = url,
            Mode = isDirectPlay ? PlaybackMode.DirectPlay : PlaybackMode.Stream,
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
    /// Second negotiation: asks for an HLS transcode and returns the server's playlist URL.
    /// </summary>
    /// <remarks>
    /// Called from two places: when the direct-play probe found nothing playable, and when the user
    /// explicitly asked for a transcode (<c>forced</c>). In the first case a source that can be served
    /// unchanged never reaches this method; in the second it is reached directly, with the HLS profile,
    /// and the source's own direct-play flags are ignored. A Jellyfin 12.2 measurement showed that
    /// <c>EnableTranscoding=true</c> without a <see cref="BuildHlsDeviceProfile"/> returns a source with
    /// no <c>TranscodingUrl</c>; the profile is therefore part of the request, not an optional hint.
    /// The playlist URL is kept as returned — it carries the server's own query parameters (including
    /// <c>ApiKey</c>), which is exactly why the engine only ever receives the relay's opaque address.
    /// </remarks>
    private async Task<PlaybackSource> ResolveTranscodedAsync(
        MediaItem item, PlaybackOptions options, CancellationToken ct, bool forced = false, string? preferredMediaSourceId = null)
    {
        using var doc = await _api
            .GetPlaybackInfoAsync(item.Id, options, enableTranscoding: true, BuildHlsDeviceProfile(), ct)
            .ConfigureAwait(false);

        if (!doc.RootElement.TryGetProperty("MediaSources", out var sources)
            || sources.ValueKind != JsonValueKind.Array)
        {
            throw ProviderException.Parse(_serverId);
        }

        JsonElement? chosen = null;
        string? transcodingUrl = null;
        foreach (JsonElement candidate in sources.EnumerateArray())
        {
            // A transcode for a picker selection must be a transcode of THAT version. Taking another
            // version's playlist would play a different file than the one on screen.
            if (!string.IsNullOrEmpty(preferredMediaSourceId)
                && !string.Equals(MediaBrowserMapper.GetString(candidate, "Id"), preferredMediaSourceId, StringComparison.Ordinal))
            {
                continue;
            }

            string? candidateUrl = MediaBrowserMapper.GetString(candidate, "TranscodingUrl");
            if (string.IsNullOrEmpty(candidateUrl)) continue;
            chosen = candidate;
            transcodingUrl = candidateUrl;
            break;
        }

        if (chosen is null || string.IsNullOrEmpty(transcodingUrl))
        {
            // The server accepted the negotiation but produced no HLS URL. Saying so is better than
            // handing the engine a URL that was never offered — and under an explicit transcode request
            // it is the only honest answer: quietly playing the direct stream would look like the
            // switch worked.
            //
            // The policy read separates two failures the user reacts to differently. "This account may
            // not transcode at all" is a server-side setting the client cannot change, while "the
            // transcode did not start" is worth retrying. Measured on both lab servers: their accounts
            // carry EnableVideoPlaybackTranscoding=false, so every transcode request there ends in the
            // first case and the generic wording would have blamed the wrong side.
            bool? transcodingAllowed = await TryGetTranscodingAllowedAsync(ct).ConfigureAwait(false);
            throw ProviderException.Connection(_serverId, DescribeTranscodeFailure(forced, preferredMediaSourceId, transcodingAllowed));
        }

        JsonElement source = chosen.Value;
        string? mediaSourceId = MediaBrowserMapper.GetString(source, "Id");
        if (string.IsNullOrEmpty(mediaSourceId)) throw ProviderException.Parse(_serverId);

        string? playSessionId = MediaBrowserMapper.GetString(doc.RootElement, "PlaySessionId")
                                ?? MediaBrowserMapper.GetString(source, "PlaySessionId");
        if (string.IsNullOrEmpty(playSessionId)) throw ProviderException.Parse(_serverId);

        Uri playlistUrl = Uri.TryCreate(transcodingUrl, UriKind.Absolute, out Uri? absolute)
            ? absolute
            : new Uri(_api.ApiOrigin, transcodingUrl!);

        long? runTime = MediaBrowserMapper.GetLong(source, "RunTimeTicks") ?? item.RunTimeTicks;
        return new PlaybackSource
        {
            Url = playlistUrl,
            Mode = PlaybackMode.Transcode,
            SessionId = playSessionId,
            Container = MediaBrowserMapper.GetString(source, "TranscodingContainer")
                        ?? MediaBrowserMapper.GetString(source, "Container"),
            MediaSourceId = mediaSourceId,
            RunTimeTicks = runTime,
            StartPositionTicks = options.StartPositionTicks,
            Streams = MediaBrowserMapper.MapStreams(source),
            Headers = BuildPlaybackHeaders(source),
        };
    }

    /// <summary>
    /// Whether the account is allowed to transcode, read from its user policy.
    /// </summary>
    /// <remarks>
    /// Only consulted after a transcode negotiation produced no HLS URL, so a normal play never pays for
    /// the extra request. Null means "unknown" — a policy that cannot be read must not be guessed in
    /// either direction, because both guesses blame a side that may be innocent.
    /// </remarks>
    private async Task<bool?> TryGetTranscodingAllowedAsync(CancellationToken ct)
    {
        try
        {
            using JsonDocument doc = await GetUserDocumentAsync(ct).ConfigureAwait(false);
            return MediaBrowserMapper.GetBool(doc.RootElement, "Policy", "EnableVideoPlaybackTranscoding");
        }
        catch (Exception ex) when (ex is ProviderException or ProviderHttpException or HttpRequestException or TaskCanceledException)
        {
            return null;
        }
    }

    /// <summary>
    /// The user profile, addressed the way <see cref="RestoreSessionAsync"/> measured: by id first,
    /// <c>/Users/Me</c> as the fallback for a server that only answers the current-user route.
    /// </summary>
    private async Task<JsonDocument> GetUserDocumentAsync(CancellationToken ct)
    {
        if (!string.IsNullOrEmpty(_api.UserId))
        {
            try
            {
                return await _api.GetUserAsync(_api.UserId!, ct).ConfigureAwait(false);
            }
            catch (ProviderException ex) when (ex.Code is ProviderErrorCode.NotFound or ProviderErrorCode.Parse)
            {
                // Fall through to /Users/Me.
            }
        }

        return await _api.GetCurrentUserAsync(ct).ConfigureAwait(false);
    }

    private static string DescribeTranscodeFailure(bool forced, string? preferredMediaSourceId, bool? transcodingAllowed)
    {
        string reason = transcodingAllowed == false
            ? "服务器已对该账号关闭视频转码（用户策略 EnableVideoPlaybackTranscoding=false）"
            : "服务器没有返回 HLS 转码流（转码启动失败）";

        if (forced) return "已开启强制转码，但" + reason + "，已停止播放";
        if (string.IsNullOrEmpty(preferredMediaSourceId)) return "该片源只提供转码流，但" + reason + "，已停止播放";
        return "所选版本只提供转码流，但" + reason + "，已停止播放";
    }

    /// <summary>
    /// The client capabilities offered for the HLS fallback.
    /// </summary>
    /// <remarks>
    /// Deliberately narrow: H.264 video in MPEG-TS segments, HLS protocol. A broader profile would let
    /// an HDR/HEVC source be "transcoded" to a variant mpv cannot decode, and the point of this pass is
    /// a stream that is guaranteed to open. Direct play and direct stream are not re-offered — the first
    /// probe already established that neither is available for this item, and offering them again would
    /// only re-run a decision that has already been made.
    /// </remarks>
    private static object BuildHlsDeviceProfile() => new
    {
        Name = "MediaHub HLS",
        MaxStreamingBitrate = 120_000_000,
        DirectPlayProfiles = Array.Empty<object>(),
        TranscodingProfiles = new object[]
        {
            new
            {
                Container = "ts",
                Type = "Video",
                VideoCodec = "h264",
                AudioCodec = "aac,mp3",
                Protocol = "hls",
                Context = "Streaming",
                MaxAudioChannels = "2",
            },
        },
        CodecProfiles = Array.Empty<object>(),
        SubtitleProfiles = Array.Empty<object>(),
    };

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
        if (limit <= 0) return Array.Empty<MediaItem>();

        using var doc = await _api.GetResumableItemsAsync(limit, ct).ConfigureAwait(false);
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
        string? mediaSourceId = MediaBrowserMapper.FirstMediaSourceId(doc.RootElement);
        foreach (var stream in MediaBrowserMapper.MapStreams(doc.RootElement))
        {
            if (stream.Type != "subtitle") continue;
            // A codec the client cannot hand to the engine as a file gets no delivery URL at all:
            // advertising one would put an unplayable track in the picker.
            string? deliveryUrl = SubtitleFormats.TryResolve(stream.Codec, out string format, out _)
                ? _api.SubtitleUrl(itemId, mediaSourceId, stream.Index, format).ToString()
                : null;
            tracks.Add(new SubtitleTrack(
                stream.Index,
                stream.Language,
                stream.DisplayTitle,
                stream.Codec,
                stream.IsExternal,
                stream.IsDefault,
                IsForced: false,
                DeliveryUrl: deliveryUrl));
        }
        return tracks;
    }

    public async Task<byte[]> FetchSubtitleAsync(string itemId, SubtitleTrack track, string? mediaSourceId = null, CancellationToken ct = default)
    {
        if (!SubtitleFormats.TryResolve(track.Codec, out string format, out _))
            throw ProviderException.NotYetImplemented(_serverId, "该字幕格式");
        return await _api.GetSubtitleBytesAsync(itemId, mediaSourceId, track.Index, format, ct).ConfigureAwait(false);
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

    /// <summary>
    /// Chooses among versions that are already playable. Unknown quality counts as zero, so a server
    /// that does not report resolution never wins a "highest" comparison by accident.
    /// </summary>
    private static JsonElement? PickByPreference(List<JsonElement> candidates, string? preference)
    {
        if (candidates.Count == 0) return null;
        if (!string.Equals(preference, "highest", StringComparison.OrdinalIgnoreCase)
            && !string.Equals(preference, "lowest", StringComparison.OrdinalIgnoreCase))
        {
            return candidates[0];
        }

        bool highest = string.Equals(preference, "highest", StringComparison.OrdinalIgnoreCase);
        JsonElement best = candidates[0];
        (int Height, long BitRate) bestScore = QualityScore(best);
        foreach (JsonElement candidate in candidates.Skip(1))
        {
            (int Height, long BitRate) score = QualityScore(candidate);
            bool better = highest
                ? score.Height > bestScore.Height
                  || (score.Height == bestScore.Height && score.BitRate > bestScore.BitRate)
                : (score.Height > 0 && (bestScore.Height == 0 || score.Height < bestScore.Height))
                  || (score.Height == bestScore.Height && score.BitRate > 0
                      && (bestScore.BitRate == 0 || score.BitRate < bestScore.BitRate));
            if (!better) continue;
            best = candidate;
            bestScore = score;
        }
        return best;
    }

    private static (int Height, long BitRate) QualityScore(JsonElement source)
    {
        var quality = MediaBrowserMapper.ReadSourceQuality(source);
        return (quality.Height ?? 0, quality.BitRate ?? 0);
    }
}

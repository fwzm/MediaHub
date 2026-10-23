using System.Globalization;
using System.Text;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.MediaBrowser;

/// <summary>
/// HTTP protocol shared by Emby and Jellyfin (both descend from MediaBrowser).
/// </summary>
/// <remarks>
/// <para>
/// The two servers share the request shapes but differ in API root and in how identity is presented:
/// Emby mounts everything under <c>/emby</c> and expects <c>X-Emby-Authorization</c>, Jellyfin serves
/// from the server root and accepts a <c>MediaBrowser</c> authorization header. Everything else —
/// query parameters, field lists, playback negotiation, progress endpoints — is identical, so the
/// differences are isolated in <see cref="MediaBrowserDialect"/> instead of being spread through the
/// call sites.
/// </para>
/// <para>
/// Wire rules carried over from the Android client, each of which was established against a real
/// server and is therefore not up for reinterpretation here:
/// <list type="bullet">
/// <item>the token travels in a header only — never as a query parameter (ADR-026);</item>
/// <item><c>PlaybackInfo</c> is negotiated with direct play and direct stream enabled and transcoding
/// disabled;</item>
/// <item>the <c>PlaySessionId</c> returned by <c>PlaybackInfo</c> is mandatory for the progress
/// endpoints on real Emby 4.x, which answers 400 when it is absent;</item>
/// <item>an empty <c>MediaSources</c> array means a damaged response, not "needs transcoding".</item>
/// </list>
/// </para>
/// </remarks>
internal sealed class MediaBrowserApi
{
    private readonly HttpClient _http;
    private readonly MediaBrowserDialect _dialect;
    private readonly string _serverId;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public MediaBrowserApi(HttpClient http, MediaBrowserDialect dialect, string serverId)
    {
        _http = http;
        _dialect = dialect;
        _serverId = serverId;
    }

    public string? Token { get; set; }
    public string? UserId { get; set; }

    public Uri ApiRoot => _dialect.ApiRoot;

    // ---------------------------------------------------------------- plumbing

    private HttpRequestMessage NewRequest(HttpMethod method, string path, IDictionary<string, string?>? query = null)
    {
        string url = _dialect.BuildUrl(path, query);
        var request = new HttpRequestMessage(method, url);
        _dialect.ApplyIdentity(request, UserId);
        if (!string.IsNullOrEmpty(Token)) _dialect.ApplyToken(request, Token!);
        return request;
    }

    private async Task<JsonDocument> SendJsonAsync(HttpRequestMessage request, CancellationToken ct)
    {
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { throw; }
        catch (HttpRequestException ex)
        {
            throw ProviderException.Network(_serverId, ex);
        }

        using (response)
        {
            if (!response.IsSuccessStatusCode)
            {
                int status = (int)response.StatusCode;
                if (status == 401) throw ProviderException.AuthExpired(_serverId);
                if (status == 404) throw ProviderException.NotFound(_serverId, UrlRedactor.Redact(request.RequestUri));
                if (status == 429) throw ProviderException.RateLimited(_serverId);
                throw new ProviderHttpException(_serverId, status, request.Method.Method, UrlRedactor.Redact(request.RequestUri));
            }

            string body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            try
            {
                return JsonDocument.Parse(body);
            }
            catch (JsonException ex)
            {
                throw ProviderException.Parse(_serverId, ex);
            }
        }
    }

    private async Task SendNoContentAsync(HttpRequestMessage request, CancellationToken ct)
    {
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { throw; }
        catch (HttpRequestException ex)
        {
            throw ProviderException.Network(_serverId, ex);
        }
        using (response)
        {
            if (response.IsSuccessStatusCode) return;
            int status = (int)response.StatusCode;
            if (status == 401) throw ProviderException.AuthExpired(_serverId);
            if (status == 429) throw ProviderException.RateLimited(_serverId);
            throw new ProviderHttpException(_serverId, status, request.Method.Method, UrlRedactor.Redact(request.RequestUri));
        }
    }

    // ---------------------------------------------------------------- endpoints

    /// <summary>Public system information. Requires no token, so it is also the identity probe
    /// used to confirm that a stored token belongs to this server before it is replayed.</summary>
    public async Task<JsonDocument> GetSystemInfoPublicAsync(CancellationToken ct)
        => await SendJsonAsync(NewRequest(HttpMethod.Get, "/System/Info/Public"), ct).ConfigureAwait(false);

    public async Task<JsonDocument> AuthenticateByNameAsync(string username, string password, CancellationToken ct)
    {
        var request = NewRequest(HttpMethod.Post, "/Users/AuthenticateByName");
        var payload = JsonSerializer.Serialize(new Dictionary<string, string?>
        {
            ["Username"] = username,
            ["Pw"] = password,
        });
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");

        try
        {
            return await SendJsonAsync(request, ct).ConfigureAwait(false);
        }
        catch (ProviderException ex) when (ex.Code == ProviderErrorCode.AuthExpired)
        {
            // SendJsonAsync maps every 401 to AuthExpired, so the login route must translate it back:
            // the user typed a wrong password, they are not holding an expired session. Reporting
            // "please log in again" to someone who is literally logging in is a dead end, and the
            // earlier catch on ProviderHttpException could never fire because SendJsonAsync never
            // throws that type for a 401.
            throw ProviderException.AuthFailed(_serverId, "用户名或密码不正确");
        }
    }

    public async Task<JsonDocument> GetViewsAsync(CancellationToken ct)
        => await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Require(UserId) + "/Views"), ct).ConfigureAwait(false);

    public async Task<JsonDocument> GetItemsAsync(string parentId, PageRequest page, string? includeItemTypes, CancellationToken ct)
    {
        var query = new Dictionary<string, string?>
        {
            ["ParentId"] = parentId,
            ["StartIndex"] = page.Offset.ToString(CultureInfo.InvariantCulture),
            ["Limit"] = page.Limit.ToString(CultureInfo.InvariantCulture),
            ["Fields"] = MediaBrowserMapper.ItemFields,
            ["SortBy"] = "SortName",
            ["SortOrder"] = "Ascending",
            ["ImageTypeLimit"] = "1",
        };
        if (!string.IsNullOrEmpty(includeItemTypes)) query["IncludeItemTypes"] = includeItemTypes;
        return await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Require(UserId) + "/Items", query), ct).ConfigureAwait(false);
    }

    public async Task<JsonDocument> SearchAsync(string term, PageRequest page, CancellationToken ct)
    {
        var query = new Dictionary<string, string?>
        {
            ["SearchTerm"] = term,
            ["Recursive"] = "true",
            ["StartIndex"] = page.Offset.ToString(CultureInfo.InvariantCulture),
            ["Limit"] = page.Limit.ToString(CultureInfo.InvariantCulture),
            ["Fields"] = MediaBrowserMapper.SearchFields,
            ["IncludeItemTypes"] = "Movie,Series,Episode,Video",
            ["ImageTypeLimit"] = "1",
        };
        return await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Require(UserId) + "/Items", query), ct).ConfigureAwait(false);
    }

    /// <summary>
    /// The authenticated user, used to validate a restored session.
    /// </summary>
    /// <remarks>
    /// Deliberately <c>/Users/Me</c>. The previous implementation asked for
    /// <c>/Users/{userId}/Items/{userId}</c> — the user id used as an item id — which a mock server
    /// answered with a stub and a real Jellyfin 12.2 answers with 404, so restoring a session against
    /// a real server always failed while every test stayed green.
    /// </remarks>
    public async Task<JsonDocument> GetCurrentUserAsync(CancellationToken ct)
        => await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/Me"), ct).ConfigureAwait(false);

    /// <summary>
    /// A specific user by id.
    /// </summary>
    /// <remarks>
    /// The portable way to confirm a session: both Emby and Jellyfin answer it. <c>/Users/Me</c> is
    /// not portable - Emby 4.10.1.0 returns HTTP 500 for it because it expects a GUID, while its own
    /// user ids are short numerics.
    /// </remarks>
    public async Task<JsonDocument> GetUserAsync(string userId, CancellationToken ct)
        => await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Uri.EscapeDataString(userId)), ct).ConfigureAwait(false);

    public async Task<JsonDocument> GetItemAsync(string itemId, CancellationToken ct)
        => await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Require(UserId) + "/Items/" + Uri.EscapeDataString(itemId)), ct).ConfigureAwait(false);

    /// <summary>
    /// Marks or unmarks an item as a favourite.
    /// </summary>
    /// <remarks>
    /// <c>FavoriteItems</c>, not <c>Favorites</c>. Measured on both lab servers: Emby 4.10.2.0 and
    /// Jellyfin 12.2.0 answer 404 for <c>POST|DELETE /Users/{uid}/Favorites/{itemId}</c> and 200 for
    /// <c>POST|DELETE /Users/{uid}/FavoriteItems/{itemId}</c>. The 200 carries an empty body, so the
    /// caller has to re-read the item to learn the stored state.
    /// </remarks>
    public async Task SetFavoriteAsync(string itemId, bool favorite, CancellationToken ct)
    {
        string path = "/Users/" + Require(UserId) + "/FavoriteItems/" + Uri.EscapeDataString(itemId);
        HttpMethod method = favorite ? HttpMethod.Post : HttpMethod.Delete;
        await SendNoContentAsync(NewRequest(method, path), ct).ConfigureAwait(false);
    }

    /// <summary>Items the current user has favourited, via the IsFavorite server-side filter.</summary>
    public async Task<JsonDocument> GetFavoritesAsync(int limit, CancellationToken ct)
    {
        var query = new Dictionary<string, string?>
        {
            ["Filters"] = "IsFavorite",
            ["Recursive"] = "true",
            ["IncludeItemTypes"] = "Movie,Series,Episode,Video",
            ["StartIndex"] = "0",
            ["Limit"] = limit.ToString(CultureInfo.InvariantCulture),
            ["Fields"] = MediaBrowserMapper.ItemFields,
            ["SortBy"] = "DateCreated",
            ["SortOrder"] = "Descending",
            ["ImageTypeLimit"] = "1",
        };
        return await SendJsonAsync(NewRequest(HttpMethod.Get, "/Users/" + Require(UserId) + "/Items", query), ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Every episode of a series, in one request.
    /// </summary>
    /// <remarks>
    /// Deliberately not <c>/Shows/{id}/Episodes?StartItemId=</c>: the meaning of StartItemId differs
    /// between Emby and Jellyfin and, on some versions, is inclusive of the item passed in. Returning
    /// the full ordered list and selecting the successor locally keeps the "which episode is next"
    /// decision in one place, where it can be tested against recordings rather than depending on a
    /// server-side parameter whose semantics are not guaranteed across both products.
    /// </remarks>
    public async Task<JsonDocument> GetAllEpisodesAsync(string seriesId, CancellationToken ct)
    {
        string query = "?UserId=" + Uri.EscapeDataString(Require(UserId))
            + "&Fields=SeriesId%2CSeasonId%2CIndexNumber%2CParentIndexNumber%2CRunTimeTicks%2COverview"
            + "&IsMissing=false";
        return await SendJsonAsync(NewRequest(HttpMethod.Get,
            "/Shows/" + Uri.EscapeDataString(seriesId) + "/Episodes" + query), ct).ConfigureAwait(false);
    }

    public async Task<JsonDocument> GetPlaybackInfoAsync(string itemId, PlaybackOptions options, CancellationToken ct)
    {
        var query = new Dictionary<string, string?> { ["UserId"] = Require(UserId) };
        var request = NewRequest(HttpMethod.Post, "/Items/" + Uri.EscapeDataString(itemId) + "/PlaybackInfo", query);

        var body = new Dictionary<string, object?>
        {
            ["UserId"] = Require(UserId),
            ["EnableDirectPlay"] = true,
            ["EnableDirectStream"] = true,
            ["EnableTranscoding"] = false,
            ["AutoOpenLiveStream"] = false,
        };
        if (options.StartPositionTicks is > 0) body["StartTimeTicks"] = options.StartPositionTicks;
        request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");

        return await SendJsonAsync(request, ct).ConfigureAwait(false);
    }

    public async Task LogoutAsync(CancellationToken ct)
    {
        try
        {
            await SendNoContentAsync(NewRequest(HttpMethod.Post, "/Sessions/Logout"), ct).ConfigureAwait(false);
        }
        catch (ProviderException)
        {
            // Best effort: a server that refuses to revoke the token must not block the user's logout.
        }
    }

    // ---------------------------------------------------------------- progress

    public async Task ReportPlaybackAsync(string endpoint, object payload, CancellationToken ct)
    {
        var request = NewRequest(HttpMethod.Post, endpoint);
        request.Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");
        await SendNoContentAsync(request, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Headers that authenticate a media stream request against this server.
    /// </summary>
    /// <remarks>Callers must only send these to the server's own origin; see PlaybackCoordinator.</remarks>
    public IReadOnlyDictionary<string, string> BuildStreamHeaders()
        => _dialect.BuildStreamHeaders(UserId, Token);

    /// <summary>Origin of the API, used to decide whether a stream URL is entitled to the session.</summary>
    public Uri ApiOrigin => _dialect.ApiRoot;

    /// <summary>Direct-stream URL for an item.</summary>
    /// <remarks>
    /// The token is deliberately absent: authentication for the stream travels in the injected header,
    /// because a token in a URL ends up in server logs, proxy logs and browser history (ADR-026).
    /// </remarks>
    public Uri DirectStreamUrl(string itemId, string container, string mediaSourceId, string playSessionId)
    {
        var query = new Dictionary<string, string?>
        {
            ["MediaSourceId"] = mediaSourceId,
            ["PlaySessionId"] = playSessionId,
            ["static"] = "true",
        };
        string path = "/Videos/" + Uri.EscapeDataString(itemId) + "/stream." + (string.IsNullOrWhiteSpace(container) ? "mp4" : container);
        return new Uri(_dialect.BuildUrl(path, query));
    }

    /// <summary>
    /// Downloads artwork bytes.
    /// </summary>
    /// <remarks>
    /// Artwork is fetched through the same request builder as every other API call, so it inherits the
    /// source's identity headers, its origin-scoped redirect handling and its proxy policy. Handing an
    /// image URL to an uncontrolled loader would move the request outside all three.
    /// </remarks>
    public async Task<byte[]> GetImageBytesAsync(string itemId, string imageType, string? tag, int maxWidth, CancellationToken ct)
    {
        HttpRequestMessage request = NewRequest(HttpMethod.Get,
            "/Items/" + Uri.EscapeDataString(itemId) + "/Images/" + imageType,
            new Dictionary<string, string?>
            {
                ["maxWidth"] = maxWidth.ToString(CultureInfo.InvariantCulture),
                ["quality"] = "85",
                ["tag"] = string.IsNullOrWhiteSpace(tag) ? null : tag,
            });

        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { throw; }
        catch (HttpRequestException ex) { throw ProviderException.Network(_serverId, ex); }

        using (response)
        {
            if (!response.IsSuccessStatusCode)
                throw new ProviderHttpException(_serverId, (int)response.StatusCode, "GET", UrlRedactor.Redact(request.RequestUri));
            return await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        }
    }

    public Uri ImageUrl(string itemId, string imageType, string? tag, int maxWidth)
    {
        var query = new Dictionary<string, string?>
        {
            ["maxWidth"] = maxWidth.ToString(CultureInfo.InvariantCulture),
            ["quality"] = "85",
        };
        if (!string.IsNullOrWhiteSpace(tag)) query["tag"] = tag;
        return new Uri(_dialect.BuildUrl("/Items/" + Uri.EscapeDataString(itemId) + "/Images/" + imageType, query));
    }

    public Uri SubtitleUrl(string itemId, int streamIndex, string? format)
    {
        var query = new Dictionary<string, string?>
        {
            ["api_key"] = "",
        };
        query.Remove("api_key");
        string path = "/Videos/" + Uri.EscapeDataString(itemId) + "/" + Uri.EscapeDataString(streamIndex.ToString(CultureInfo.InvariantCulture)) + "/Subtitles/" +
                      Uri.EscapeDataString(string.IsNullOrWhiteSpace(format) ? "srt" : format!);
        return new Uri(_dialect.BuildUrl(path, query));
    }

    private string Require(string? value) => !string.IsNullOrEmpty(value) ? value! : throw ProviderException.AuthRequired(_serverId);
}

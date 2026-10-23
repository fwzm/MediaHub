using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Domain;

namespace MediaHub.TestSupport;

/// <summary>What the fake server serves. Every value is deterministic so tests can assert exact bytes.</summary>
public sealed class MockServerOptions
{
    public string ServerId { get; init; } = "mock-server-id";
    public string ServerName { get; init; } = "Mock Media Server";
    public string Version { get; init; } = "4.7.0.0";
    public string UserId { get; init; } = "user-1";
    public string UserName { get; init; } = "tester";
    public string Password { get; init; } = "secret";
    public string Token { get; init; } = "token-abc";
    public int ItemCount { get; init; } = 25;

    /// <summary>Payload of the media route. Default: 64 KiB of deterministic bytes.</summary>
    public byte[]? MediaBytes { get; init; }

    /// <summary>
    /// Leaves <c>UserData.IsFavorite</c> out of every item payload, the shape a server that does not
    /// track favourites answers with, so the client's "server did not say" branch can be exercised.
    /// </summary>
    public bool OmitIsFavoriteFromUserData { get; init; }

    /// <summary>
    /// Playback state the item query reports, keyed by item id, laid on top of the default pattern
    /// (every seventh item holds a resume position). Lets a test build an exact continue-watching set:
    /// resumable items with distinct play times, one watched to the end, one never played.
    /// </summary>
    public IReadOnlyDictionary<string, MockUserData> UserData { get; init; } =
        new Dictionary<string, MockUserData>(StringComparer.Ordinal);

    /// <summary>
    /// Episodes the <c>/Shows/{seriesId}/Episodes</c> route serves, in exactly the order given.
    /// </summary>
    /// <remarks>
    /// The order is preserved, not sorted: a real server answers in whatever order its index produces,
    /// and ordering by season and episode is the client's job. A test can therefore hand the mock a
    /// deliberately jumbled list and still expect the client to pick the right successor.
    /// </remarks>
    public IReadOnlyList<MockEpisode> Episodes { get; init; } = Array.Empty<MockEpisode>();
}

/// <summary>
/// Playback state as a server reports it in <c>UserData</c> — the fields Emby's <c>Filters=IsResumable</c>
/// and <c>SortBy=DatePlayed</c> are defined on.
/// </summary>
public sealed record MockUserData(long PlaybackPositionTicks, bool Played, DateTime? LastPlayedDate = null);

/// <summary>
/// In-process Emby/Jellyfin stand-in, so integration tests can drive the real provider code over real
/// sockets without a media server, a container or network access.
/// </summary>
/// <remarks>
/// <para>
/// Five deliberate fidelity choices, because the tests around them are the reason this exists:
/// </para>
/// <list type="bullet">
/// <item>the API root follows the dialect — Emby under <c>/emby</c>, Jellyfin from the root — and a
/// request outside that root is answered 404, so a dialect mistake cannot pass silently;</item>
/// <item>authentication is checked before routing, so any non-public path without a valid token is
/// 401 even when the path does not exist, which is what a MediaBrowser server does and what makes a
/// "token was stripped by a redirect" fact observable as a status code;</item>
/// <item>favourite state is real state: the write routes change it and every later item payload
/// reflects it, while <c>/Users/{uid}/Favorites/{id}</c> — which Emby and Jellyfin both answer 404 —
/// is deliberately absent, so a client addressing the wrong spelling cannot stay green here;</item>
/// <item>the episode list is scoped to one series and answered in seed order rather than sorted by
/// season and episode, so "next episode" cannot come from the server's ordering; an episode whose
/// season or episode number is unknown omits that field instead of sending a 0 that would look real.</item>
/// <item>an item query is scoped the way the servers scope it: a non-recursive query with an empty
/// <c>ParentId</c> answers with the library folders rather than their contents (Emby 4.10 behaves
/// exactly so, and a client that read that answer as a video list saw "continue watching" stay empty
/// forever while this mock — which returned the whole library — kept the defect invisible), while
/// <c>Recursive=true</c> descends into the libraries; <c>Filters</c> and <c>SortBy=DatePlayed</c> are
/// applied here too, so a client that drops one of them cannot pass by accident.</item>
/// </list>
/// <para>
/// The catalogue is flat and deterministic: <see cref="MockServerOptions.ItemCount"/> movies live in
/// <see cref="MoviesLibraryId"/>, the TV library exists (so the Views mapping can be asserted) and
/// holds the episodes seeded through <see cref="MockServerOptions.Episodes"/> — keeping the paging
/// arithmetic exact rather than approximate.
/// </para>
/// </remarks>
public sealed class MockMediaServer : IAsyncDisposable
{
    /// <summary>Library holding the deterministic items.</summary>
    public const string MoviesLibraryId = "lib-movies";

    /// <summary>Second library, present so the view list has the two expected entries; it has no items.</summary>
    public const string TvShowsLibraryId = "lib-tv";

    private const string JsonContentType = "application/json; charset=utf-8";
    private const string MediaContentType = "video/mp4";
    private const string PngContentType = "image/png";

    /// <summary>A real 1x1 PNG: the artwork route serves image bytes rather than a placeholder string.</summary>
    private static readonly byte[] OnePixelPng = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private readonly HttpListener _listener;
    private readonly MockServerOptions _options;
    private readonly string _rootPath;
    private readonly byte[] _media;
    private readonly List<MockItem> _items;

    /// <summary>Episodes served on <c>/Shows/{seriesId}/Episodes</c>, kept in seed order.</summary>
    private readonly List<MockEpisode> _episodes;
    private readonly List<RecordedRequest> _requests = new();
    private readonly Dictionary<string, RedirectRule> _redirects = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Ids the mock holds as favourited; the single source of truth for every favourite answer.</summary>
    private readonly HashSet<string> _favorites = new(StringComparer.Ordinal);
    private readonly object _gate = new();
    private readonly Task _acceptLoop;

    private volatile bool _tokenValid = true;
    private volatile bool _ignoreFavoriteWrites;
    private long _delayTicks;
    private int _failureStatus;
    private int _failureRemaining;
    private string? _failureRetryAfter;
    private int _stopped;

    private MockMediaServer(ServerType type, MockServerOptions options)
    {
        Type = type;
        _options = options;
        _rootPath = type switch
        {
            ServerType.Emby => "/emby",
            ServerType.Jellyfin => string.Empty,
            _ => throw new NotSupportedException(
                "The MediaBrowser mock emulates Emby (root /emby) and Jellyfin (root /); " + type + " has a different protocol."),
        };
        _media = options.MediaBytes ?? CreateDefaultMediaBytes();
        _items = Enumerable.Range(0, Math.Max(0, options.ItemCount))
            .Select(index => CreateItem(index, _media.Length))
            .ToList();
        _episodes = options.Episodes.ToList();

        (_listener, int port) = BindLoopbackListener();
        BaseUri = new Uri("http://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/");
        _acceptLoop = Task.Run(AcceptLoopAsync);
    }

    /// <summary>Starts a server on a free loopback port. The bind happens before the task completes.</summary>
    public static Task<MockMediaServer> StartAsync(ServerType type, MockServerOptions? options = null)
    {
        try
        {
            return Task.FromResult(new MockMediaServer(type, options ?? new MockServerOptions()));
        }
        catch (Exception ex)
        {
            // Binding can fail after 40 attempts; surfacing it as a faulted task keeps the call shape
            // identical for the caller instead of throwing from a method that returns a Task.
            return Task.FromException<MockMediaServer>(ex);
        }
    }

    /// <summary>Server root, i.e. the address a user would type: <c>http://127.0.0.1:{port}/</c>.</summary>
    public Uri BaseUri { get; }

    public ServerType Type { get; }

    /// <summary>Path the API is mounted under: <c>/emby</c> for Emby, empty for Jellyfin.</summary>
    public string ApiRootPath => _rootPath;

    /// <summary>Exact bytes the media route serves, so tests can compare slices byte for byte.</summary>
    public byte[] MediaBytes => _media;

    /// <summary>Snapshot of every request received so far, including 401/404 answers.</summary>
    public IReadOnlyList<RecordedRequest> Requests
    {
        get { lock (_gate) return _requests.ToArray(); }
    }

    /// <summary>Snapshot of the requests whose absolute path starts with <paramref name="pathPrefix"/>.</summary>
    public IReadOnlyList<RecordedRequest> RequestsFor(string pathPrefix)
    {
        ArgumentNullException.ThrowIfNull(pathPrefix);
        return Requests.Where(r => r.Path.StartsWith(pathPrefix, StringComparison.OrdinalIgnoreCase)).ToList();
    }

    /// <summary>Absolute URL for a route below the API root, e.g. <c>Url("/Users/user-1/Views")</c>.</summary>
    public Uri Url(string route)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(route);
        return new Uri(BaseUri, _rootPath + (route.StartsWith('/') ? route : "/" + route));
    }

    /// <summary>False makes every authenticated route answer 401, whatever token is presented.</summary>
    public void SetTokenValid(bool valid) => _tokenValid = valid;

    /// <summary>
    /// Makes the next <paramref name="times"/> requests answer <paramref name="statusCode"/>.
    /// </summary>
    /// <remarks>Fault injection for retry/backoff paths; the counter is consumed per request, not per route.</remarks>
    public void SetNextFailure(int statusCode, int times = 1, string? retryAfter = null)
    {
        if (times < 1) throw new ArgumentOutOfRangeException(nameof(times), "At least one request has to fail.");
        lock (_gate)
        {
            _failureStatus = statusCode;
            _failureRemaining = times;
            _failureRetryAfter = retryAfter;
        }
    }

    /// <summary>
    /// Registers a redirect for an exact absolute request path (query string ignored).
    /// </summary>
    /// <param name="location">A relative or absolute <c>Location</c>, so both redirect shapes can be exercised.</param>
    public void AddRedirect(string path, string location, int statusCode = 302)
    {
        if (string.IsNullOrWhiteSpace(path) || !path.StartsWith('/'))
            throw new ArgumentException("Redirect paths are absolute request paths, e.g. /emby/Videos/item-000/stream.mp4.", nameof(path));
        if (string.IsNullOrWhiteSpace(location))
            throw new ArgumentException("A redirect needs a Location.", nameof(location));
        lock (_gate) _redirects[path] = new RedirectRule(location, statusCode);
    }

    /// <summary>Delays every response, to simulate a slow or half-dead server.</summary>
    public void SetDelay(TimeSpan delay)
    {
        if (delay < TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(delay));
        Interlocked.Exchange(ref _delayTicks, delay.Ticks);
    }

    /// <summary>Whether the mock currently holds <paramref name="itemId"/> as a favourite.</summary>
    public bool IsFavorite(string itemId)
    {
        ArgumentNullException.ThrowIfNull(itemId);
        lock (_gate) return _favorites.Contains(itemId);
    }

    /// <summary>
    /// Makes favourite writes answer 200 and change nothing.
    /// </summary>
    /// <remarks>
    /// A server will accept a write it cannot apply — a stale session, a policy refusal reported with
    /// 200. With this on, a client that echoes the value it was handed and one that reads the item back
    /// stop looking alike, which is the only way a test can tell them apart.
    /// </remarks>
    public void SetFavoriteWritesIgnored(bool ignored) => _ignoreFavoriteWrites = ignored;

    /// <summary>
    /// Simulates an unreachable server: the listener is torn down, so later requests fail at the
    /// socket level rather than with an HTTP status.
    /// </summary>
    public void Stop()
    {
        if (Interlocked.Exchange(ref _stopped, 1) != 0) return;
        try { _listener.Stop(); } catch (ObjectDisposedException) { }
        try { _listener.Close(); } catch (ObjectDisposedException) { }
    }

    public async ValueTask DisposeAsync()
    {
        Stop();
        // The accept loop exits when the listener goes away; the bound keeps a torn-down http.sys
        // registration from hanging the test run.
        await Task.WhenAny(_acceptLoop, Task.Delay(TimeSpan.FromSeconds(5))).ConfigureAwait(false);
    }

    // ---------------------------------------------------------------- listener plumbing

    private static (HttpListener Listener, int Port) BindLoopbackListener()
    {
        // HttpListener cannot bind port 0, so an ephemeral port has to be found by trying.
        for (int attempt = 0; attempt < 40; attempt++)
        {
            int port = Random.Shared.Next(20000, 60000);
            var listener = new HttpListener();
            listener.Prefixes.Add("http://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/");
            try
            {
                listener.Start();
                return (listener, port);
            }
            catch (HttpListenerException)
            {
                listener.Close();
            }
        }
        throw new InvalidOperationException("could not bind a loopback listener");
    }

    private async Task AcceptLoopAsync()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext context;
            try
            {
                context = await _listener.GetContextAsync().ConfigureAwait(false);
            }
            catch (Exception)
            {
                return; // Stop()/Close() tear the pending wait down; that is the exit path.
            }
            _ = Task.Run(() => HandleAsync(context));
        }
    }

    private async Task HandleAsync(HttpListenerContext context)
    {
        try
        {
            RecordedRequest request = Capture(context.Request);
            string body = ReadBody(context.Request);
            lock (_gate) _requests.Add(request with { Body = body.Length == 0 ? null : body });

            TimeSpan delay = TimeSpan.FromTicks(Interlocked.Read(ref _delayTicks));
            if (delay > TimeSpan.Zero) await Task.Delay(delay).ConfigureAwait(false);

            if (TryTakeInjectedFailure(out int statusCode, out string? retryAfter))
            {
                WriteStatus(context, statusCode, retryAfter);
                return;
            }

            Route(context, request, body);
        }
        catch (Exception)
        {
            // Never take the listener down because one client went away mid-response.
            TryWriteServerError(context);
        }
        finally
        {
            try { context.Response.Close(); } catch { /* already torn down */ }
        }
    }

    private static RecordedRequest Capture(HttpListenerRequest request)
    {
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (string? key in request.Headers.AllKeys)
        {
            if (key is null) continue;
            headers[key] = request.Headers[key] ?? string.Empty;
        }

        Uri url = request.Url!;
        string? range = headers.TryGetValue("Range", out string? rawRange) ? rawRange : null;
        (long? start, long? end) = ParseRange(range);
        return new RecordedRequest(
            request.HttpMethod,
            url.Host,
            url.Port,
            url.PathAndQuery,
            headers,
            start,
            end,
            DateTimeOffset.UtcNow)
        {
            RangeHeader = range,
        };
    }

    private static string ReadBody(HttpListenerRequest request)
    {
        if (!request.HasEntityBody) return string.Empty;
        // The body must be drained even when it is not needed: an unread request body breaks the
        // keep-alive connection the client is about to reuse.
        using var reader = new StreamReader(request.InputStream, request.ContentEncoding ?? Encoding.UTF8);
        return reader.ReadToEnd();
    }

    // ---------------------------------------------------------------- routing

    private void Route(HttpListenerContext context, RecordedRequest request, string body)
    {
        if (!TryStripRoot(request.Path, out string route))
        {
            // The provider derives its API root from the dialect; anything outside that root means the
            // two disagree, which is exactly the Emby/Jellyfin mistake this mock exists to expose.
            WriteJson(context, 404, new JsonObject { ["Error"] = "Not under the configured API root." }.ToJsonString());
            return;
        }

        bool publicRoute = route.Equals("/System/Info/Public", StringComparison.OrdinalIgnoreCase)
            || (request.Method == HttpMethod.Post.Method && route.Equals("/Users/AuthenticateByName", StringComparison.OrdinalIgnoreCase));
        if (!publicRoute && !IsAuthorized(request))
        {
            WriteStatus(context, 401, null);
            return;
        }

        if (_redirects.TryGetValue(request.Path, out RedirectRule? redirect))
        {
            WriteRedirect(context, redirect);
            return;
        }

        string[] segments = route.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (route.Equals("/System/Info/Public", StringComparison.OrdinalIgnoreCase) && request.Method == HttpMethod.Get.Method)
        {
            WriteJson(context, 200, PublicSystemInfo().ToJsonString());
            return;
        }

        if (request.Method == HttpMethod.Post.Method)
        {
            if (segments is ["Users", "AuthenticateByName"]) { ServeAuthenticate(context, body); return; }
            if (segments is ["Items", _, "PlaybackInfo"]) { ServePlaybackInfo(context, segments[1]); return; }
            if (segments is ["Users", _, "FavoriteItems", var favoriteItemId]) { ServeFavorite(context, favoriteItemId, true); return; }
            if (segments is ["Sessions", "Playing"] or ["Sessions", "Progress"] or ["Sessions", "Stopped"] or ["Sessions", "Logout"]
                || (segments is ["Sessions", "Playing", "Progress" or "Stopped"]))
            {
                // Acknowledged and recorded; the request list is where tests read the payload back.
                WriteStatus(context, 204, null);
                return;
            }
        }

        if (request.Method == HttpMethod.Delete.Method
            && segments is ["Users", _, "FavoriteItems", var unfavoriteItemId])
        {
            ServeFavorite(context, unfavoriteItemId, false);
            return;
        }

        if (request.Method == HttpMethod.Get.Method)
        {
            if (segments is ["Users", _, "Views"]) { WriteJson(context, 200, Views().ToJsonString()); return; }
            if (segments is ["Users", _, "Items"]) { WriteJson(context, 200, ItemsPage(request)); return; }
            // /Users/Me is what a real Jellyfin accepts as proof of a live session. Note there is
            // deliberately NO route for /Users/{id}/Items/{id}: Jellyfin 12.2 answers that with 404
            // (a user id is not an item id), and the mock used to answer it with a stub, which is
            // exactly why the client's broken session probe stayed green here and failed on the real
            // server. A mock that is more permissive than the server is worse than no mock.
            if (segments is ["Users", "Me"]) { ServeCurrentUser(context); return; }
            if (segments is ["Users", _, "Items", var itemId]) { ServeItem(context, itemId); return; }
            // One series' episodes. An id the mock has no episodes for is an empty page, which is what
            // both servers answer; the client is the side that has to order them.
            if (segments is ["Shows", var seriesId, "Episodes"]) { WriteJson(context, 200, EpisodesPage(seriesId)); return; }
            if (segments is ["Items", _, "Images", _]) { WriteBytes(context, 200, OnePixelPng, 0, OnePixelPng.Length, null, PngContentType); return; }
            if (segments is ["Videos", _, var streamName] && streamName.StartsWith("stream", StringComparison.OrdinalIgnoreCase))
            {
                ServeMedia(context, request);
                return;
            }
        }

        WriteJson(context, 404, new JsonObject { ["Error"] = "No mock route for " + request.Method + " " + request.Path }.ToJsonString());
    }

    private bool TryStripRoot(string path, out string route)
    {
        if (_rootPath.Length == 0)
        {
            route = path;
            return true;
        }
        if (path.Equals(_rootPath, StringComparison.OrdinalIgnoreCase))
        {
            route = "/";
            return true;
        }
        if (path.StartsWith(_rootPath + "/", StringComparison.OrdinalIgnoreCase))
        {
            route = path[_rootPath.Length..];
            return true;
        }
        route = path;
        return false;
    }

    private bool IsAuthorized(RecordedRequest request)
    {
        if (!_tokenValid) return false;
        string? presented = request.Token;
        return presented is not null && string.Equals(presented, _options.Token, StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- routes

    private JsonObject PublicSystemInfo() => new()
    {
        ["Id"] = _options.ServerId,
        ["ServerName"] = _options.ServerName,
        ["Version"] = _options.Version,
    };

    private static JsonObject Views() => new()
    {
        ["Items"] = new JsonArray(
            LibraryFolderJson(MoviesLibraryId, "Movies", "movies"),
            LibraryFolderJson(TvShowsLibraryId, "TV Shows", "tvshows")),
        ["TotalRecordCount"] = 2,
    };

    private void ServeAuthenticate(HttpListenerContext context, string body)
    {
        string? username = null;
        string? password = null;
        try
        {
            using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
            username = ReadString(document.RootElement, "Username");
            password = ReadString(document.RootElement, "Pw");
        }
        catch (JsonException)
        {
            // An unparsable credential body cannot authenticate anything; fall through to 401.
        }

        if (!string.Equals(username, _options.UserName, StringComparison.Ordinal)
            || !string.Equals(password, _options.Password, StringComparison.Ordinal))
        {
            WriteJson(context, 401, new JsonObject { ["Error"] = "Invalid credentials." }.ToJsonString());
            return;
        }

        WriteJson(context, 200, new JsonObject
        {
            ["AccessToken"] = _options.Token,
            ["ServerId"] = _options.ServerId,
            ["User"] = new JsonObject
            {
                ["Id"] = _options.UserId,
                ["Name"] = _options.UserName,
                ["Policy"] = new JsonObject { ["IsAdministrator"] = false },
            },
        }.ToJsonString());
    }

    /// <summary>One page of <c>/Users/{uid}/Items</c>, scoped the way the real servers scope it.</summary>
    /// <remarks>
    /// Two rules matter for "continue watching". A non-recursive query with an empty ParentId answers
    /// with the library folders, not with their contents — Emby 4.10 does exactly that, and a client
    /// that reads the answer as a video list sees zero videos forever. Recursive=true descends into the
    /// libraries. Filters and SortBy are applied here as well, so a client that drops one of them cannot
    /// stay green by accident.
    /// </remarks>
    private string ItemsPage(RecordedRequest request)
    {
        Dictionary<string, string> query = ParseQuery(request.Query);
        query.TryGetValue("SearchTerm", out string? searchTerm);
        query.TryGetValue("ParentId", out string? parentId);
        query.TryGetValue("IncludeItemTypes", out string? includeItemTypes);
        query.TryGetValue("Filters", out string? filters);
        query.TryGetValue("SortBy", out string? sortBy);
        query.TryGetValue("SortOrder", out string? sortOrder);
        int startIndex = Math.Max(0, ParseInt(query, "StartIndex", 0));
        int limit = ParseInt(query, "Limit", 0);

        List<JsonObject> all = ItemQuery(
            searchTerm: string.IsNullOrWhiteSpace(searchTerm) ? null : searchTerm.Trim(),
            parentId: string.IsNullOrEmpty(parentId) ? null : parentId,
            recursive: IsTrue(query, "Recursive"),
            wantedTypes: SplitTypes(includeItemTypes),
            favoritesOnly: HasFilter(filters, "IsFavorite"),
            resumableOnly: HasFilter(filters, "IsResumable"),
            sortByDatePlayed: HasFilter(sortBy, "DatePlayed"),
            descending: !string.Equals(sortOrder, "Ascending", StringComparison.OrdinalIgnoreCase));

        IEnumerable<JsonObject> page = all.Skip(startIndex);
        // Emby treats an absent or zero Limit as "no limit".
        if (limit > 0) page = page.Take(limit);

        return new JsonObject
        {
            ["Items"] = new JsonArray(page.Select(node => (JsonNode?)node).ToArray()),
            ["TotalRecordCount"] = all.Count,
            ["StartIndex"] = startIndex,
        }.ToJsonString();
    }

    /// <summary>The items one query matches, before paging.</summary>
    private List<JsonObject> ItemQuery(
        string? searchTerm,
        string? parentId,
        bool recursive,
        HashSet<string>? wantedTypes,
        bool favoritesOnly,
        bool resumableOnly,
        bool sortByDatePlayed,
        bool descending)
    {
        // The top of the tree: not one video, but the library folders themselves.
        if (searchTerm is null && parentId is null && !recursive) return LibraryFolders(wantedTypes);

        // A series asks for its seasons; a season asks for its episodes.
        if (searchTerm is null && parentId is not null)
        {
            if (_episodes.Any(episode => string.Equals(episode.SeriesId, parentId, StringComparison.Ordinal)))
            {
                IEnumerable<MockEpisode> ofSeries =
                    _episodes.Where(episode => string.Equals(episode.SeriesId, parentId, StringComparison.Ordinal));
                return wantedTypes is null || wantedTypes.Contains("Season")
                    ? Seasons(parentId)
                    : EpisodesOf(ofSeries, wantedTypes);
            }
            if (_episodes.Any(episode => string.Equals(episode.SeasonId, parentId, StringComparison.Ordinal)))
            {
                IEnumerable<MockEpisode> ofSeason =
                    _episodes.Where(episode => string.Equals(episode.SeasonId, parentId, StringComparison.Ordinal));
                return EpisodesOf(ofSeason, wantedTypes);
            }
        }

        IEnumerable<MockItem> movies = _items;
        IEnumerable<MockEpisode> episodes = _episodes;
        if (searchTerm is null && parentId is not null)
        {
            if (string.Equals(parentId, MoviesLibraryId, StringComparison.OrdinalIgnoreCase))
            {
                episodes = Array.Empty<MockEpisode>();
            }
            else if (string.Equals(parentId, TvShowsLibraryId, StringComparison.OrdinalIgnoreCase))
            {
                movies = Array.Empty<MockItem>();
            }
            else
            {
                // An unknown parent has no children; the server answers an empty page rather than 404.
                movies = Array.Empty<MockItem>();
                episodes = Array.Empty<MockEpisode>();
            }
        }

        if (searchTerm is not null)
        {
            movies = movies.Where(item => item.Name.Contains(searchTerm, StringComparison.OrdinalIgnoreCase));
            episodes = episodes.Where(episode => episode.Name.Contains(searchTerm, StringComparison.OrdinalIgnoreCase));
        }

        if (wantedTypes is not null)
        {
            movies = movies.Where(item => wantedTypes.Contains(item.Type));
            episodes = episodes.Where(_ => wantedTypes.Contains("Episode"));
        }

        if (favoritesOnly)
        {
            // A Filters=IsFavorite query answers exactly what the write routes were told, so a client
            // that drops the filter cannot pass by being handed the whole library instead.
            HashSet<string> favorites;
            lock (_gate) favorites = new HashSet<string>(_favorites, StringComparer.Ordinal);
            movies = movies.Where(item => favorites.Contains(item.Id));
            episodes = Array.Empty<MockEpisode>();
        }

        if (resumableOnly)
        {
            movies = movies.Where(IsResumable);
            // Episodes carry no playback state in this catalogue, so they are never resumable.
            episodes = Array.Empty<MockEpisode>();
        }

        List<MockItem> movieList = movies.ToList();
        if (sortByDatePlayed)
        {
            movieList = descending
                ? movieList.OrderByDescending(PlayedAt).ThenBy(item => item.Index).ToList()
                : movieList.OrderBy(PlayedAt).ThenBy(item => item.Index).ToList();
        }

        List<JsonObject> results = movieList.Select(ItemJson).ToList();
        results.AddRange(episodes.Select(EpisodeJson));
        return results;
    }

    /// <summary>Play time <c>SortBy=DatePlayed</c> sorts on; an item never played sorts as the oldest.</summary>
    private DateTime PlayedAt(MockItem item) => UserDataFor(item).LastPlayedDate ?? DateTime.MinValue;

    private bool IsResumable(MockItem item)
    {
        MockUserData state = UserDataFor(item);
        return state.PlaybackPositionTicks > 0 && !state.Played;
    }

    /// <summary>
    /// Playback state for one movie: a test override when the options gave one, otherwise the default
    /// pattern (every seventh item holds a resume position) so continue-watching paths have something
    /// to find without a test inventing state.
    /// </summary>
    private MockUserData UserDataFor(MockItem item)
    {
        if (_options.UserData.TryGetValue(item.Id, out MockUserData? declared)) return declared;
        return item.Index % 7 == 0
            ? new MockUserData(300_000_000L, Played: false, LastPlayedDate: DefaultPlayedAt(item.Index))
            : new MockUserData(0L, Played: false);
    }

    /// <summary>Deterministic play time, so <c>SortBy=DatePlayed</c> has a total order to sort.</summary>
    private static DateTime DefaultPlayedAt(int index) =>
        new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMinutes(index);

    private static HashSet<string>? SplitTypes(string? includeItemTypes) =>
        string.IsNullOrWhiteSpace(includeItemTypes)
            ? null
            : includeItemTypes.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

    /// <summary>Whether a comma-separated list (<c>Filters</c>, <c>SortBy</c>) holds one value.</summary>
    private static bool HasFilter(string? list, string wanted) =>
        !string.IsNullOrWhiteSpace(list)
        && list.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Any(value => value.Equals(wanted, StringComparison.OrdinalIgnoreCase));

    private static bool IsTrue(Dictionary<string, string> query, string name) =>
        query.TryGetValue(name, out string? raw)
        && (raw.Equals("true", StringComparison.OrdinalIgnoreCase) || raw == "1");

    /// <summary>The library folders, shaped as the item query answers them.</summary>
    /// <remarks>
    /// No <c>UserData</c>, no media streams and no path: a library folder is not a playable item, and a
    /// client that treats it as one has to fail here rather than against a real server.
    /// </remarks>
    private static List<JsonObject> LibraryFolders(HashSet<string>? wantedTypes)
    {
        if (wantedTypes is not null
            && !wantedTypes.Contains("CollectionFolder")
            && !wantedTypes.Contains("Folder"))
        {
            return new List<JsonObject>();
        }

        return new List<JsonObject>
        {
            LibraryFolderJson(MoviesLibraryId, "Movies", "movies"),
            LibraryFolderJson(TvShowsLibraryId, "TV Shows", "tvshows"),
        };
    }

    private static JsonObject LibraryFolderJson(string id, string name, string collectionType) => new()
    {
        ["Id"] = id,
        ["Name"] = name,
        ["Type"] = "CollectionFolder",
        ["CollectionType"] = collectionType,
        ["IsFolder"] = true,
    };

    /// <summary>Seasons of one series, derived from the seeded episodes and kept in seed order.</summary>
    private List<JsonObject> Seasons(string seriesId)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var seasons = new List<JsonObject>();
        foreach (MockEpisode episode in _episodes)
        {
            if (!string.Equals(episode.SeriesId, seriesId, StringComparison.Ordinal)) continue;
            if (!seen.Add(episode.SeasonId)) continue;

            var json = new JsonObject
            {
                ["Id"] = episode.SeasonId,
                ["Name"] = episode.SeasonNumber is { } number
                    ? "Season " + number.ToString(CultureInfo.InvariantCulture)
                    : "Unknown Season",
                ["Type"] = "Season",
                ["SeriesId"] = episode.SeriesId,
                ["IsFolder"] = true,
            };
            if (episode.SeasonNumber is { } index) json["IndexNumber"] = index;
            seasons.Add(json);
        }
        return seasons;
    }

    /// <summary>Episodes of one parent, filtered by <c>IncludeItemTypes</c>.</summary>
    private List<JsonObject> EpisodesOf(IEnumerable<MockEpisode> episodes, HashSet<string>? wantedTypes)
        => wantedTypes is not null && !wantedTypes.Contains("Episode")
            ? new List<JsonObject>()
            : episodes.Select(EpisodeJson).ToList();

    /// <summary>The authenticated user, as a real server reports it.</summary>
    private void ServeCurrentUser(HttpListenerContext context)
    {
        WriteJson(context, 200, new JsonObject
        {
            ["Id"] = _options.UserId,
            ["Name"] = _options.UserName,
            ["ServerId"] = _options.ServerId,
            ["Policy"] = new JsonObject { ["IsAdministrator"] = true },
        }.ToJsonString());
    }

    /// <summary>Episode page for one series, in seed order and scoped to that series.</summary>
    private string EpisodesPage(string seriesId)
    {
        List<MockEpisode> episodes = _episodes
            .Where(episode => string.Equals(episode.SeriesId, seriesId, StringComparison.Ordinal))
            .ToList();

        var items = new JsonArray();
        foreach (MockEpisode episode in episodes) items.Add(EpisodeJson(episode));

        return new JsonObject
        {
            ["Items"] = items,
            ["TotalRecordCount"] = episodes.Count,
            ["StartIndex"] = 0,
        }.ToJsonString();
    }

    /// <summary>Episode payload, as the episode list and the single-item route both serve it.</summary>
    /// <remarks>
    /// An unknown season or episode number is omitted rather than sent as 0: both servers leave the
    /// field out when they do not know it, and a 0 would turn "unknown" into a number the client could
    /// mistake for the first episode.
    /// </remarks>
    private JsonObject EpisodeJson(MockEpisode episode)
    {
        var json = new JsonObject
        {
            ["Id"] = episode.Id,
            ["Name"] = episode.Name,
            ["Type"] = "Episode",
            ["MediaType"] = "Video",
            ["SeriesId"] = episode.SeriesId,
            ["SeasonId"] = episode.SeasonId,
            ["IsFolder"] = false,
            ["RunTimeTicks"] = episode.RunTimeTicks,
            ["Path"] = "/media/tv/" + episode.SeriesId + "/" + episode.Id + ".mp4",
            ["Container"] = "mp4",
        };
        if (episode.SeasonNumber is { } season) json["ParentIndexNumber"] = season;
        if (episode.EpisodeNumber is { } number) json["IndexNumber"] = number;
        return json;
    }

    private void ServeItem(HttpListenerContext context, string itemId)
    {
        MockItem? item = _items.FirstOrDefault(candidate => string.Equals(candidate.Id, itemId, StringComparison.Ordinal));
        if (item is not null)
        {
            WriteJson(context, 200, ItemJson(item).ToJsonString());
            return;
        }

        // An episode is an item too: the detail route has to answer for an episode id, or the
        // auto-advance fallback that reads SeriesId back off the finished item could not be exercised.
        MockEpisode? episode = _episodes.FirstOrDefault(candidate => string.Equals(candidate.Id, itemId, StringComparison.Ordinal));
        if (episode is not null)
        {
            WriteJson(context, 200, EpisodeJson(episode).ToJsonString());
            return;
        }

        WriteJson(context, 404, new JsonObject { ["Error"] = "Unknown item " + itemId }.ToJsonString());
    }

    /// <summary>Item payload with the mock's playback and favourite state folded into <c>UserData</c>.</summary>
    private JsonObject ItemJson(MockItem item)
    {
        MockUserData userData = UserDataFor(item);
        bool favorite;
        lock (_gate) favorite = _favorites.Contains(item.Id);
        return item.ToJson(userData, favorite, includeFavorite: !_options.OmitIsFavoriteFromUserData);
    }

    /// <summary>
    /// Answers a favourite write on <c>/Users/{uid}/FavoriteItems/{itemId}</c>, the route Emby 4.10.2
    /// and Jellyfin 12.2 both accept.
    /// </summary>
    /// <remarks>
    /// There is deliberately no <c>/Users/{uid}/Favorites/{id}</c> route: both servers answer that
    /// spelling with 404, and a mock that accepted it would keep a client addressing the wrong path
    /// green here and broken in production. The write itself is 200 with an empty body, which is what
    /// both servers send.
    /// </remarks>
    private void ServeFavorite(HttpListenerContext context, string itemId, bool favorite)
    {
        if (!_items.Any(item => string.Equals(item.Id, itemId, StringComparison.Ordinal)))
        {
            WriteJson(context, 404, new JsonObject { ["Error"] = "Unknown item " + itemId }.ToJsonString());
            return;
        }

        // Accepted but not applied leaves the stored state alone, so only a client that reads the item
        // back can report what the server actually holds.
        if (!_ignoreFavoriteWrites)
        {
            lock (_gate)
            {
                if (favorite) _favorites.Add(itemId);
                else _favorites.Remove(itemId);
            }
        }

        WriteStatus(context, 200, null);
    }

    private void ServePlaybackInfo(HttpListenerContext context, string itemId)
    {
        MockItem? item = _items.FirstOrDefault(candidate => string.Equals(candidate.Id, itemId, StringComparison.Ordinal));
        if (item is null)
        {
            WriteJson(context, 404, new JsonObject { ["Error"] = "Unknown item " + itemId }.ToJsonString());
            return;
        }

        string mediaSourceId = "mediasource-" + item.Id;
        string playSessionId = "playsession-" + item.Id;
        var source = new JsonObject
        {
            ["Id"] = mediaSourceId,
            ["Name"] = item.Name,
            ["Path"] = item.Path,
            ["Container"] = "mp4",
            ["SupportsDirectPlay"] = true,
            ["SupportsDirectStream"] = true,
            ["SupportsTranscoding"] = false,
            ["IsRemote"] = false,
            ["RunTimeTicks"] = item.RunTimeTicks,
            ["MediaStreams"] = MediaStreams(),
            // Mixed on purpose: the provider must forward the harmless header and drop the
            // credential-shaped one (see MediaBrowserProvider.BuildPlaybackHeaders).
            ["RequiredHttpHeaders"] = new JsonObject
            {
                ["X-Media-Probe"] = "keep-me",
                ["Authorization"] = "must-not-be-forwarded",
            },
        };

        WriteJson(context, 200, new JsonObject
        {
            ["MediaSources"] = new JsonArray(source),
            // Root only, which is where Jellyfin 12.2 actually puts it. The mock previously duplicated
            // it inside the media source as well, and that duplication is precisely why the client's
            // source-only lookup passed here and failed against every real server.
            ["PlaySessionId"] = playSessionId,
        }.ToJsonString());
    }

    private void ServeMedia(HttpListenerContext context, RecordedRequest request)
    {
        long total = _media.Length;

        if (request.RangeHeader is null || (request.RangeStart is null && request.RangeEnd is null))
        {
            // No Range, or a Range this server does not understand: RFC 9110 lets the whole entity be
            // returned, and that is what a client can always consume.
            WriteBytes(context, 200, _media, 0, _media.Length, null, MediaContentType);
            return;
        }

        long from;
        long to;
        if (request.RangeStart is null)
        {
            long suffix = Math.Min(request.RangeEnd!.Value, total);
            from = total - suffix;
            to = total - 1;
        }
        else
        {
            from = request.RangeStart.Value;
            to = request.RangeEnd is null ? total - 1 : Math.Min(request.RangeEnd.Value, total - 1);
        }

        if (from >= total || from > to)
        {
            context.Response.StatusCode = 416;
            context.Response.Headers["Content-Range"] = "bytes */" + total.ToString(CultureInfo.InvariantCulture);
            context.Response.ContentLength64 = 0;
            return;
        }

        int count = (int)(to - from + 1);
        WriteBytes(
            context,
            206,
            _media,
            (int)from,
            count,
            "bytes " + from.ToString(CultureInfo.InvariantCulture) + "-" + to.ToString(CultureInfo.InvariantCulture) + "/" + total.ToString(CultureInfo.InvariantCulture),
            MediaContentType);
    }

    // ---------------------------------------------------------------- response writers

    private static void WriteJson(HttpListenerContext context, int statusCode, string json)
    {
        byte[] payload = Encoding.UTF8.GetBytes(json);
        context.Response.StatusCode = statusCode;
        context.Response.ContentType = JsonContentType;
        context.Response.ContentLength64 = payload.Length;
        context.Response.OutputStream.Write(payload, 0, payload.Length);
    }

    private static void WriteStatus(HttpListenerContext context, int statusCode, string? retryAfter)
    {
        context.Response.StatusCode = statusCode;
        if (!string.IsNullOrEmpty(retryAfter)) context.Response.Headers["Retry-After"] = retryAfter;
        context.Response.ContentLength64 = 0;
    }

    private static void WriteBytes(HttpListenerContext context, int statusCode, byte[] source, int offset, int count, string? contentRange, string contentType)
    {
        context.Response.StatusCode = statusCode;
        context.Response.ContentType = contentType;
        context.Response.Headers["Accept-Ranges"] = "bytes";
        if (contentRange is not null) context.Response.Headers["Content-Range"] = contentRange;
        context.Response.ContentLength64 = count;
        if (count > 0) context.Response.OutputStream.Write(source, offset, count);
    }

    private static void WriteRedirect(HttpListenerContext context, RedirectRule rule)
    {
        context.Response.StatusCode = rule.StatusCode;
        context.Response.Headers["Location"] = rule.Location;
        context.Response.ContentLength64 = 0;
    }

    private static void TryWriteServerError(HttpListenerContext context)
    {
        try
        {
            context.Response.StatusCode = 500;
            context.Response.ContentLength64 = 0;
        }
        catch (Exception)
        {
            // The response was already committed; the client will see the truncated exchange.
        }
    }

    // ---------------------------------------------------------------- helpers

    private static MockItem CreateItem(int index, long mediaSize)
    {
        string id = "item-" + index.ToString("D3", CultureInfo.InvariantCulture);
        return new MockItem(
            Index: index,
            Id: id,
            Name: index % 5 == 0
                ? "Star Wars " + index.ToString("D2", CultureInfo.InvariantCulture)
                : "Movie " + index.ToString("D2", CultureInfo.InvariantCulture),
            Type: "Movie",
            LibraryId: MoviesLibraryId,
            Path: "/media/" + id + ".mp4",
            Year: 1990 + (index % 30),
            RunTimeTicks: TimeSpan.FromMinutes(90 + index).Ticks,
            SizeBytes: mediaSize);
    }

    private static byte[] CreateDefaultMediaBytes()
    {
        var bytes = new byte[64 * 1024];
        for (int i = 0; i < bytes.Length; i++) bytes[i] = (byte)((i * 31 + 7) % 251);
        return bytes;
    }

    private static JsonArray MediaStreams() => new(
        new JsonObject
        {
            ["Type"] = "Video",
            ["Index"] = 0,
            ["Codec"] = "h264",
            ["Profile"] = "High",
            ["Width"] = 1920,
            ["Height"] = 1080,
            ["BitRate"] = 8_000_000,
            ["ColorPrimaries"] = "bt709",
            ["ColorTransfer"] = "bt709",
            ["PixelFormat"] = "yuv420p",
            ["IsDefault"] = true,
        },
        new JsonObject
        {
            ["Type"] = "Audio",
            ["Index"] = 1,
            ["Codec"] = "aac",
            ["Channels"] = 2,
            ["SampleRate"] = 48000,
            ["Language"] = "eng",
            ["DisplayTitle"] = "English AAC stereo",
            ["IsDefault"] = true,
        },
        new JsonObject
        {
            ["Type"] = "Subtitle",
            ["Index"] = 2,
            ["Codec"] = "srt",
            ["Language"] = "eng",
            ["DisplayTitle"] = "English (external)",
            ["IsExternal"] = true,
            ["IsDefault"] = false,
        });

    private static string? ReadString(JsonElement element, string property)
    {
        foreach (JsonProperty candidate in element.EnumerateObject())
        {
            if (string.Equals(candidate.Name, property, StringComparison.OrdinalIgnoreCase)
                && candidate.Value.ValueKind == JsonValueKind.String)
            {
                return candidate.Value.GetString();
            }
        }
        return null;
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (string pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int equals = pair.IndexOf('=');
            string name = equals < 0 ? pair : pair[..equals];
            string value = equals < 0 ? string.Empty : pair[(equals + 1)..];
            values[Uri.UnescapeDataString(name.Replace('+', ' '))] = Uri.UnescapeDataString(value.Replace('+', ' '));
        }
        return values;
    }

    private static int ParseInt(Dictionary<string, string> query, string name, int fallback)
        => query.TryGetValue(name, out string? raw) && int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int value)
            ? value
            : fallback;

    /// <summary>Split of a single-range header into first/last byte. Multi-range and malformed input yield nulls.</summary>
    private static (long? Start, long? End) ParseRange(string? header)
    {
        if (header is null) return (null, null);
        const string prefix = "bytes=";
        string value = header.Trim();
        if (!value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return (null, null);

        string spec = value[prefix.Length..].Trim();
        if (spec.Contains(',')) return (null, null);

        int dash = spec.IndexOf('-');
        if (dash < 0) return (null, null);
        string first = spec[..dash].Trim();
        string second = spec[(dash + 1)..].Trim();

        if (first.Length == 0)
        {
            // Suffix range: bytes=-500 means the last 500 bytes.
            return long.TryParse(second, NumberStyles.None, CultureInfo.InvariantCulture, out long suffix) && suffix > 0
                ? (null, suffix)
                : (null, null);
        }

        if (!long.TryParse(first, NumberStyles.None, CultureInfo.InvariantCulture, out long start)) return (null, null);
        if (second.Length == 0) return (start, null);
        if (!long.TryParse(second, NumberStyles.None, CultureInfo.InvariantCulture, out long end)) return (null, null);
        return end < start ? (null, null) : (start, end);
    }

    private bool TryTakeInjectedFailure(out int statusCode, out string? retryAfter)
    {
        lock (_gate)
        {
            if (_failureRemaining <= 0)
            {
                statusCode = 0;
                retryAfter = null;
                return false;
            }
            _failureRemaining--;
            statusCode = _failureStatus;
            retryAfter = _failureRetryAfter;
            return true;
        }
    }

    private sealed record RedirectRule(string Location, int StatusCode);

    private sealed record MockItem(
        int Index,
        string Id,
        string Name,
        string Type,
        string LibraryId,
        string Path,
        int Year,
        long RunTimeTicks,
        long SizeBytes)
    {
        /// <summary>
        /// Item payload, with <paramref name="userData"/> reported as <c>UserData</c> and
        /// <paramref name="isFavorite"/> as <c>UserData.IsFavorite</c>, unless
        /// <paramref name="includeFavorite"/> is false (a server that does not track favourites simply
        /// omits the field).
        /// </summary>
        public JsonObject ToJson(MockUserData userData, bool isFavorite, bool includeFavorite)
        {
            var playback = new JsonObject
            {
                ["PlaybackPositionTicks"] = userData.PlaybackPositionTicks,
                ["Played"] = userData.Played,
            };
            if (userData.LastPlayedDate is { } playedAt)
            {
                playback["LastPlayedDate"] = playedAt.ToString("o", CultureInfo.InvariantCulture);
            }
            if (includeFavorite) playback["IsFavorite"] = isFavorite;

            return new JsonObject
            {
                ["Id"] = Id,
                ["Name"] = Name,
                ["Type"] = Type,
                ["MediaType"] = "Video",
                ["Path"] = Path,
                ["Container"] = "mp4",
                ["IsFolder"] = false,
                ["ProductionYear"] = Year,
                ["RunTimeTicks"] = RunTimeTicks,
                ["Size"] = SizeBytes,
                ["Overview"] = "Deterministic mock item " + Index.ToString(CultureInfo.InvariantCulture) + ".",
                ["CommunityRating"] = 6.0 + (Index % 5) / 2.0,
                ["ImageTags"] = new JsonObject { ["Primary"] = "primary-" + Id },
                ["BackdropImageTags"] = new JsonArray("backdrop-" + Id),
                ["UserData"] = playback,
                ["MediaStreams"] = MediaStreams(),
            };
        }
    }
}

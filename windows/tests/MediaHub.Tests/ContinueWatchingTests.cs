using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Continue watching over the provider's own sockets: what the request has to say, and which items may
/// come back.
/// </summary>
/// <remarks>
/// A real defect, and one the old mock could not see. The client asked <c>/Users/{uid}/Items</c> with an
/// empty <c>ParentId</c>, without <c>Recursive=true</c> and without <c>Filters=IsResumable</c>; Emby 4.10
/// answers that with the library folders, not with the videos inside them, so "continue watching" was
/// empty against every real server while the suite stayed green — the mock returned the whole library for
/// the same request and hid the missing query parameters.
/// <para>
/// These tests pin the parameters on the wire and the behaviour on top of them, and separately pin the
/// mock's own scoping rules so a future "helpful" mock cannot flatten the distinction a second time.
/// </para>
/// </remarks>
public sealed class ContinueWatchingWireTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string Token = "token-abc";
    private const string UserId = "user-1";

    /// <summary>Direct mode: the loopback mock must never be reached through a machine proxy.</summary>
    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static IMediaProviderFactory FactoryFor(ServerType type)
        => type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, ServerType type)
    {
        ProviderHandle handle = FactoryFor(type).Create(ServerFor(mock, type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    /// <summary>The item query path, with the server's API root prefix (Emby serves under <c>/emby</c>).</summary>
    private static string ItemsPath(MockMediaServer mock) => mock.ApiRootPath + "/Users/" + UserId + "/Items";

    private static async Task<JsonDocument> GetJsonAsync(MockMediaServer mock, string route)
    {
        using var client = new HttpClient();
        client.DefaultRequestHeaders.TryAddWithoutValidation("X-Emby-Token", Token);
        client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", @"MediaBrowser Token=""token-abc""");
        string url = mock.BaseUri.GetLeftPart(UriPartial.Authority) + mock.ApiRootPath + route;
        return JsonDocument.Parse(await client.GetStringAsync(url));
    }

    private static Dictionary<string, string> QueryOf(RecordedRequest request)
    {
        var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (string pair in request.Query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int equals = pair.IndexOf('=');
            if (equals < 0) query[pair] = string.Empty;
            else query[Uri.UnescapeDataString(pair[..equals])] = Uri.UnescapeDataString(pair[(equals + 1)..]);
        }
        return query;
    }

    private static string? Value(Dictionary<string, string> query, string name)
        => query.TryGetValue(name, out string? value) ? value : null;

    private static string?[] Ids(JsonDocument document)
        => document.RootElement.GetProperty("Items").EnumerateArray()
            .Select(item => item.GetProperty("Id").GetString())
            .ToArray();

    /// <summary>
    /// Four items with an exact playback history: two resumable at different times, one watched to the
    /// end, one never started. No default resume pattern, so the expected answer is unambiguous.
    /// </summary>
    private static MockServerOptions ResumableCatalogue()
    {
        static DateTime PlayedAt(int hour) => new(2026, 1, 1, hour, 0, 0, DateTimeKind.Utc);

        return new MockServerOptions
        {
            ItemCount = 4,
            UserData = new Dictionary<string, MockUserData>(StringComparer.Ordinal)
            {
                ["item-000"] = new(300_000_000L, Played: false, PlayedAt(10)),
                ["item-001"] = new(600_000_000L, Played: false, PlayedAt(12)),
                ["item-002"] = new(900_000_000L, Played: true, PlayedAt(13)),
                ["item-003"] = new(0L, Played: false, PlayedAt(14)),
            },
        };
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Continue_watching_asks_for_resumable_items_recursively(ServerType type)
    {
        await using var mock = await MockMediaServer.StartAsync(type);
        using ProviderHandle handle = await ConnectAsync(mock, type);

        await handle.Progress!.GetContinueWatchingAsync(20);

        RecordedRequest request = Assert.Single(mock.RequestsFor(ItemsPath(mock)));
        Assert.Equal("GET", request.Method);
        Dictionary<string, string> query = QueryOf(request);

        // Without Recursive=true an empty ParentId means "the top of the tree" to Emby 4.10, and the
        // answer is the library folders: a client that reads them as videos sees nothing to resume.
        Assert.Equal("true", Value(query, "Recursive"));

        // Without Filters=IsResumable the server returns finished items as well.
        Assert.Equal("IsResumable", Value(query, "Filters"));

        // Resume lists are newest-first by play time; without these two the order is alphabetical.
        Assert.Equal("DatePlayed", Value(query, "SortBy"));
        Assert.Equal("Descending", Value(query, "SortOrder"));

        // Episodes are resumable too, so both types are asked for.
        Assert.Equal("Movie,Episode", Value(query, "IncludeItemTypes"));

        // The query is intentionally not scoped to one library.
        Assert.True(
            string.IsNullOrEmpty(Value(query, "ParentId")),
            "a resume query must not be scoped to a library: resumable items can live in any of them");
    }

    [Fact]
    public async Task Only_unfinished_playback_survives_and_the_server_order_is_kept()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, ResumableCatalogue());
        using ProviderHandle handle = await ConnectAsync(mock, ServerType.Emby);

        IReadOnlyList<MediaItem> items = await handle.Progress!.GetContinueWatchingAsync(20);

        // item-002 is watched to the end and item-003 was never started, so neither is "continue
        // watching"; the survivor order is the server's DatePlayed descending order.
        Assert.Equal(new[] { "item-001", "item-000" }, items.Select(item => item.Id));
        Assert.All(items, item => Assert.True(
            item.UserDataPlaybackPositionTicks is > 0,
            "an item without a resume position was reported as continue-watching"));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public async Task A_non_positive_limit_returns_nothing_and_asks_nothing(int limit)
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, ServerType.Emby);

        IReadOnlyList<MediaItem> items = await handle.Progress!.GetContinueWatchingAsync(limit);

        Assert.Empty(items);
        // Emby reads Limit=0 as "no limit", so sending the request would fetch the whole library.
        Assert.Empty(mock.RequestsFor(ItemsPath(mock)));
    }
}

/// <summary>
/// The mock's own scoping rules for the item query.
/// </summary>
/// <remarks>
/// These are fidelity checks on the harness, not on the client: if the mock ever answers a non-recursive
/// query with the whole library again, the tests above lose their ability to catch the defect, and these
/// fail first.
/// </remarks>
public sealed class ContinueWatchingMockFidelityTests
{
    private const string UserId = "user-1";
    private const string Token = "token-abc";

    private static async Task<JsonDocument> GetJsonAsync(MockMediaServer mock, string route)
    {
        using var client = new HttpClient();
        client.DefaultRequestHeaders.TryAddWithoutValidation("X-Emby-Token", Token);
        client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", @"MediaBrowser Token=""token-abc""");
        string url = mock.BaseUri.GetLeftPart(UriPartial.Authority) + mock.ApiRootPath + route;
        return JsonDocument.Parse(await client.GetStringAsync(url));
    }

    private static string?[] Ids(JsonDocument document)
        => document.RootElement.GetProperty("Items").EnumerateArray()
            .Select(item => item.GetProperty("Id").GetString())
            .ToArray();

    private static MockServerOptions ResumableCatalogue()
    {
        static DateTime PlayedAt(int hour) => new(2026, 1, 1, hour, 0, 0, DateTimeKind.Utc);

        return new MockServerOptions
        {
            ItemCount = 4,
            UserData = new Dictionary<string, MockUserData>(StringComparer.Ordinal)
            {
                ["item-000"] = new(300_000_000L, Played: false, PlayedAt(10)),
                ["item-001"] = new(600_000_000L, Played: false, PlayedAt(12)),
                ["item-002"] = new(900_000_000L, Played: true, PlayedAt(13)),
                ["item-003"] = new(0L, Played: false, PlayedAt(14)),
            },
        };
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_non_recursive_query_with_no_parent_answers_with_the_library_folders(ServerType type)
    {
        await using var mock = await MockMediaServer.StartAsync(type);

        using JsonDocument document = await GetJsonAsync(mock, "/Users/" + UserId + "/Items?StartIndex=0&Limit=10");

        Assert.Equal(2, document.RootElement.GetProperty("TotalRecordCount").GetInt32());
        Assert.Equal(new[] { "lib-movies", "lib-tv" }, Ids(document));
        Assert.All(document.RootElement.GetProperty("Items").EnumerateArray(), item =>
        {
            Assert.Equal("CollectionFolder", item.GetProperty("Type").GetString());
            // A library folder is not a playable item: no playback state and no streams, which is what
            // makes "read the answer as a video list" fail loudly rather than silently on empty fields.
            Assert.False(item.TryGetProperty("UserData", out _));
            Assert.False(item.TryGetProperty("MediaStreams", out _));
        });
    }

    [Fact]
    public async Task The_mock_applies_the_resumable_filter_and_the_play_date_sort_itself()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, ResumableCatalogue());

        using JsonDocument document = await GetJsonAsync(mock,
            "/Users/" + UserId + "/Items?Recursive=true&Filters=IsResumable&SortBy=DatePlayed&SortOrder=Descending&IncludeItemTypes=Movie,Episode");

        Assert.Equal(new[] { "item-001", "item-000" }, Ids(document));
    }

    [Fact]
    public async Task Recursive_queries_descend_into_the_libraries()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        using JsonDocument document = await GetJsonAsync(mock,
            "/Users/" + UserId + "/Items?Recursive=true&IncludeItemTypes=Movie&StartIndex=0&Limit=100");

        Assert.Equal(25, document.RootElement.GetProperty("TotalRecordCount").GetInt32());
        string?[] ids = Ids(document);
        Assert.Equal(25, ids.Length);
        Assert.Equal("item-000", ids[0]);
        Assert.DoesNotContain("lib-movies", ids);
    }

    [Fact]
    public async Task Browsing_one_library_still_returns_that_library_only()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, ServerType.Emby);

        PagedResult<MediaItem> page = await handle.Library!.GetItemsAsync(
            MockMediaServer.MoviesLibraryId, new PageRequest(0, 10));

        Assert.Equal(10, page.Items.Count);
        Assert.Equal(25, page.TotalCount);
        Assert.Equal("item-000", page.Items[0].Id);
        // A library browse returns items, never the library folder that holds them.
        Assert.DoesNotContain(page.Items, item => item.Id == MockMediaServer.MoviesLibraryId);
    }

    [Fact]
    public async Task Series_and_season_browsing_still_answers_with_seasons_and_episodes()
    {
        var options = new MockServerOptions
        {
            Episodes = new[]
            {
                new MockEpisode("ep-s1e1", "series-1", "Pilot", 1, 1),
                new MockEpisode("ep-s1e2", "series-1", "Second", 1, 2),
                new MockEpisode("ep-s2e1", "series-1", "Finale", 2, 1),
            },
        };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);
        using ProviderHandle handle = await ConnectAsync(mock, ServerType.Emby);

        IReadOnlyList<Season> seasons = await handle.Library!.GetSeasonsAsync("series-1");
        Assert.Equal(new[] { "series-1-s1", "series-1-s2" }, seasons.Select(season => season.Id));
        Assert.Equal(new[] { 1, 2 }, seasons.Select(season => season.SeasonNumber));

        IReadOnlyList<Episode> episodes = await handle.Library!.GetEpisodesAsync("series-1-s1");
        Assert.Equal(new[] { "ep-s1e1", "ep-s1e2" }, episodes.Select(episode => episode.Id));
    }

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, ServerType type)
    {
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        MediaServer server = new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
        ProviderHandle handle = factory.Create(server, new NetworkPolicy { Mode = ProxyMode.Direct });
        await handle.Auth!.AuthenticateAsync(new Credentials("tester", "secret"));
        return handle;
    }
}

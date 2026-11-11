using System.Net;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Auto-advance across a series: which episode follows the current one, what a season boundary and the
/// series finale do, and how an episode's identity reaches the client.
/// </summary>
/// <remarks>
/// Every case goes through <see cref="MockMediaServer"/> over a real socket and asks the provider for a
/// successor, so a "next episode" that only works because the server returned the episodes in order
/// cannot stay green: the mock answers in seed order and the tests seed it jumbled.
/// </remarks>
public sealed class EpisodeAutoAdvanceTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string UserId = "user-1";
    private const string SeriesId = "series-1";

    /// <summary>Direct mode: the loopback mock must never be reached through a machine proxy.</summary>
    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, IMediaProviderFactory factory)
    {
        ProviderHandle handle = factory.Create(ServerFor(mock, factory.Descriptor.Type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    private static MockEpisode Episode(string id, int? season, int? number)
        => new(id, SeriesId, "Episode " + id, season, number);

    private static Task<MockMediaServer> StartAsync(ServerType type, params MockEpisode[] episodes)
        => MockMediaServer.StartAsync(type, new MockServerOptions { Episodes = episodes });

    /// <summary>First value of a query parameter as it arrived on the wire, or null.</summary>
    private static string? QueryValue(RecordedRequest request, string name)
    {
        foreach (string pair in request.Query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int equals = pair.IndexOf('=');
            string key = Uri.UnescapeDataString(equals < 0 ? pair : pair[..equals]);
            if (!string.Equals(key, name, StringComparison.OrdinalIgnoreCase)) continue;
            return equals < 0 ? string.Empty : Uri.UnescapeDataString(pair[(equals + 1)..]);
        }
        return null;
    }

    [Fact]
    public async Task The_next_episode_inside_a_season_is_the_one_after_the_current_episode()
    {
        await using var mock = await StartAsync(
            ServerType.Emby, Episode("ep-s1e1", 1, 1), Episode("ep-s1e2", 1, 2), Episode("ep-s1e3", 1, 3));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Episode? next = await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e1");

        Assert.NotNull(next);
        Assert.Equal("ep-s1e2", next!.Id);
        Assert.Equal(1, next.SeasonNumber);
        Assert.Equal(2, next.EpisodeNumber);

        // One request, for this series and this user, and it excludes missing episodes: the episode list
        // is the server's, so the shape of the query is part of what the client has to get right.
        RecordedRequest request = Assert.Single(mock.RequestsFor("/emby/Shows/" + SeriesId + "/Episodes"));
        Assert.Equal("GET", request.Method);
        Assert.Equal(UserId, QueryValue(request, "UserId"));
        Assert.Equal("false", QueryValue(request, "IsMissing"));
        Assert.Contains("ParentIndexNumber", QueryValue(request, "Fields"), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_episode_after_a_season_finale_is_the_first_episode_of_the_next_season(ServerType type)
    {
        await using var mock = await StartAsync(
            type,
            Episode("ep-s1e1", 1, 1),
            Episode("ep-s1e2", 1, 2),
            Episode("ep-s2e1", 2, 1),
            Episode("ep-s2e2", 2, 2));
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        using ProviderHandle handle = await ConnectAsync(mock, factory);

        Episode? next = await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e2");

        Assert.NotNull(next);
        Assert.Equal("ep-s2e1", next!.Id);
        Assert.Equal(2, next.SeasonNumber);
        Assert.Equal(1, next.EpisodeNumber);
    }

    [Fact]
    public async Task The_series_finale_has_no_successor()
    {
        await using var mock = await StartAsync(
            ServerType.Emby, Episode("ep-s1e1", 1, 1), Episode("ep-s1e2", 1, 2), Episode("ep-s2e1", 2, 1));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Null(await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s2e1"));

        // Null because the server said this was the last one, not because the question was never asked.
        Assert.Single(mock.RequestsFor("/emby/Shows/" + SeriesId + "/Episodes"));
    }

    [Fact]
    public async Task An_unnumbered_episode_does_not_take_the_slot_of_a_numbered_one()
    {
        // The server knows this file is in season 2 but not which number it has — what both servers
        // report for a file they could not match. It must not be handed back as "the episode after the
        // season 1 finale": that would skip the real S2E1 for the rest of the series' life.
        await using var mock = await StartAsync(
            ServerType.Emby,
            Episode("ep-s1e1", 1, 1),
            Episode("ep-s1e2", 1, 2),
            Episode("ep-s2e1", 2, 1),
            Episode("ep-s2e2", 2, 2),
            Episode("ep-s2-unnumbered", 2, null));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Episode? next = await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e2");

        Assert.Equal("ep-s2e1", next?.Id);
    }

    [Fact]
    public async Task An_unnumbered_episode_is_reached_only_after_the_numbered_episodes_of_its_season()
    {
        // The other half of the same rule: "unknown" sorts last within its season, so it is reachable
        // once the numbered episodes are exhausted — and it is labelled with the season it belongs to.
        await using var mock = await StartAsync(
            ServerType.Emby,
            Episode("ep-s2e1", 2, 1),
            Episode("ep-s2-unnumbered", 2, null));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Episode? next = await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s2e1");

        Assert.Equal("ep-s2-unnumbered", next?.Id);
        Assert.Equal(2, next?.SeasonNumber);
    }

    [Fact]
    public async Task An_episode_that_is_not_in_the_series_has_no_successor()
    {
        await using var mock = await StartAsync(ServerType.Emby, Episode("ep-s1e1", 1, 1), Episode("ep-s1e2", 1, 2));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Null(await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-from-another-series"));
    }

    [Fact]
    public async Task An_unknown_series_has_no_next_episode()
    {
        await using var mock = await StartAsync(ServerType.Emby, Episode("ep-s1e1", 1, 1));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Null(await handle.Library!.GetNextEpisodeAsync("series-404", "ep-s1e1"));

        // The empty answer came from a real request: an id the server has no episodes for answers 200
        // with no items, and that has to read as "no next episode" rather than as a failure.
        Assert.Single(mock.RequestsFor("/emby/Shows/series-404/Episodes"));
    }

    [Fact]
    public async Task Response_order_does_not_decide_which_episode_comes_next()
    {
        // The order a server's index can produce: season 2 first, seasons interleaved. A client that
        // trusted it would play S2E2 straight after S1E1.
        await using var mock = await StartAsync(
            ServerType.Emby,
            Episode("ep-s2e2", 2, 2),
            Episode("ep-s1e2", 1, 2),
            Episode("ep-s2e1", 2, 1),
            Episode("ep-s1e1", 1, 1));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Equal("ep-s1e2", (await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e1"))?.Id);
        Assert.Equal("ep-s2e1", (await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s1e2"))?.Id);
        Assert.Null(await handle.Library!.GetNextEpisodeAsync(SeriesId, "ep-s2e2"));
    }

    [Fact]
    public async Task An_episode_looked_up_by_id_keeps_its_series_season_and_episode_number()
    {
        // This is the mapping the auto-advance fallback depends on: when a request arrives without a
        // series, the series is read back off the item itself.
        await using var mock = await StartAsync(ServerType.Emby, Episode("ep-s1e2", 1, 2));
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        MediaDetail detail = await handle.Detail!.GetItemDetailAsync("ep-s1e2");

        Assert.Equal(MediaType.Episode, detail.Item.Type);
        Assert.Equal(SeriesId, detail.Item.SeriesId);
        Assert.Equal(1, detail.Item.SeasonNumber);
        Assert.Equal(2, detail.Item.EpisodeNumber);
    }

    [Fact]
    public async Task A_movie_has_no_series_season_or_episode_number()
    {
        // The same mapping has to leave the three fields empty for a movie, or the progress list would
        // start labelling films with episode numbers.
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        MediaDetail detail = await handle.Detail!.GetItemDetailAsync("item-000");

        Assert.Equal(MediaType.Movie, detail.Item.Type);
        Assert.Null(detail.Item.SeriesId);
        Assert.Null(detail.Item.SeasonNumber);
        Assert.Null(detail.Item.EpisodeNumber);
    }
}

/// <summary>
/// The mock's own episode route, asserted directly rather than through the provider: a mock that served
/// every series or sorted by itself would make the provider tests above pass for the wrong reason.
/// </summary>
public sealed class EpisodeRouteFidelityTests
{
    private const string Token = "token-abc";
    private const string UserId = "user-1";

    private static async Task<(HttpStatusCode Status, string Body)> SendAsync(
        MockMediaServer mock, string route, string? token = Token)
    {
        using var factory = new MediaSourceHttpClientFactory(new NetworkPolicy { Mode = ProxyMode.Direct });
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url(route));
        if (token is not null) request.Headers.TryAddWithoutValidation("X-Emby-Token", token);
        using HttpResponseMessage response = await client.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }

    private static MockEpisode[] TwoSeasons() => new[]
    {
        new MockEpisode("ep-s2e1", "series-1", "Season two opener", 2, 1),
        new MockEpisode("ep-s1e1", "series-1", "Pilot", 1, 1),
        new MockEpisode("ep-s2e2", "series-1", "Season two closer", 2, 2),
        new MockEpisode("ep-other-s1e1", "series-2", "Another show", 1, 1),
    };

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_episode_route_is_authenticated_like_every_other_route(ServerType type)
    {
        await using var mock = await MockMediaServer.StartAsync(type, new MockServerOptions { Episodes = TwoSeasons() });

        (HttpStatusCode anonymous, _) = await SendAsync(mock, "/Shows/series-1/Episodes", token: null);
        Assert.Equal(HttpStatusCode.Unauthorized, anonymous);

        (HttpStatusCode wrongToken, _) = await SendAsync(mock, "/Shows/series-1/Episodes", token: "not-the-token");
        Assert.Equal(HttpStatusCode.Unauthorized, wrongToken);

        (HttpStatusCode authorized, _) = await SendAsync(mock, "/Shows/series-1/Episodes");
        Assert.Equal(HttpStatusCode.OK, authorized);
    }

    [Fact]
    public async Task The_episode_route_answers_one_series_in_seed_order()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, new MockServerOptions { Episodes = TwoSeasons() });

        (HttpStatusCode status, string body) = await SendAsync(mock, "/Shows/series-1/Episodes?UserId=" + UserId);
        Assert.Equal(HttpStatusCode.OK, status);

        using JsonDocument document = JsonDocument.Parse(body);
        JsonElement root = document.RootElement;
        Assert.Equal(3, root.GetProperty("TotalRecordCount").GetInt32());
        Assert.Equal(
            new[] { "ep-s2e1", "ep-s1e1", "ep-s2e2" },
            root.GetProperty("Items").EnumerateArray().Select(item => item.GetProperty("Id").GetString()));

        JsonElement first = root.GetProperty("Items")[0];
        Assert.Equal("Episode", first.GetProperty("Type").GetString());
        Assert.Equal("series-1", first.GetProperty("SeriesId").GetString());
        Assert.Equal(2, first.GetProperty("ParentIndexNumber").GetInt32());
        Assert.Equal(1, first.GetProperty("IndexNumber").GetInt32());
        Assert.True(first.GetProperty("RunTimeTicks").GetInt64() > 0);
    }

    [Fact]
    public async Task An_unknown_series_answers_an_empty_page_rather_than_404()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, new MockServerOptions { Episodes = TwoSeasons() });

        (HttpStatusCode status, string body) = await SendAsync(mock, "/Shows/series-404/Episodes");
        Assert.Equal(HttpStatusCode.OK, status);

        using JsonDocument document = JsonDocument.Parse(body);
        Assert.Equal(0, document.RootElement.GetProperty("TotalRecordCount").GetInt32());
        Assert.Empty(document.RootElement.GetProperty("Items").EnumerateArray());
    }

    [Fact]
    public async Task An_unnumbered_episode_omits_the_number_fields_instead_of_sending_zero()
    {
        // A zero would be read as "episode 0 of season 0" — a real, early position — which is exactly
        // the mistake the client has to be protected from.
        var episode = new MockEpisode("ep-unnumbered", "series-1", "Unmatched file", null, null);
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, new MockServerOptions { Episodes = new[] { episode } });

        (_, string listBody) = await SendAsync(mock, "/Shows/series-1/Episodes");
        using JsonDocument list = JsonDocument.Parse(listBody);
        JsonElement listed = list.RootElement.GetProperty("Items")[0];
        Assert.False(listed.TryGetProperty("ParentIndexNumber", out _));
        Assert.False(listed.TryGetProperty("IndexNumber", out _));

        // The single-item route answers the same shape, because that is how the client re-reads the
        // series off an episode it was handed.
        (HttpStatusCode itemStatus, string itemBody) = await SendAsync(mock, "/Users/" + UserId + "/Items/ep-unnumbered");
        Assert.Equal(HttpStatusCode.OK, itemStatus);
        using JsonDocument item = JsonDocument.Parse(itemBody);
        Assert.Equal("Episode", item.RootElement.GetProperty("Type").GetString());
        Assert.False(item.RootElement.TryGetProperty("IndexNumber", out _));
    }
}

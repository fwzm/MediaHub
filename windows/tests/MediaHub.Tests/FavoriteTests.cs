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
/// Favourites over the provider's own sockets: which route and verb a write uses, what the return value
/// means, and what the favourites query asks for and returns.
/// </summary>
/// <remarks>
/// The wire facts here are the ones a wrong implementation would still pass with a green status code:
/// Emby and Jellyfin answer <c>POST /Users/{uid}/FavoriteItems/{id}</c> and 404 on the
/// <c>/Users/{uid}/Favorites/{id}</c> spelling, so both spellings are pinned rather than just the
/// happy path. The boolean result is pinned the same way: a write the server accepts but does not apply
/// has to come back as the server's state, not as an echo of the request.
/// </remarks>
public sealed class FavoriteWireProtocolTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string Token = "token-abc";
    private const string UserId = "user-1";

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

    [Fact]
    public async Task Favourite_writes_use_the_FavoriteItems_route_and_never_the_Favorites_spelling()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        bool result = await handle.Favorites!.SetFavoriteAsync("item-003", true);

        Assert.True(result);
        Assert.True(mock.IsFavorite("item-003"));

        RecordedRequest write = Assert.Single(mock.RequestsFor("/emby/Users/" + UserId + "/FavoriteItems/item-003"));
        Assert.Equal("POST", write.Method);

        // /Users/{uid}/Favorites/{id} is what both servers 404, and the mock answers it 404 on purpose,
        // so this is the assertion that keeps the wrong spelling from coming back.
        Assert.DoesNotContain(mock.Requests, request => request.Path.Contains("/Favorites/", StringComparison.OrdinalIgnoreCase));

        // The state comes from the server, so the item is read back once after the write.
        RecordedRequest read = Assert.Single(mock.RequestsFor("/emby/Users/" + UserId + "/Items/item-003"));
        Assert.Equal("GET", read.Method);
    }

    [Fact]
    public async Task Unfavouriting_uses_DELETE_on_the_same_route()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        await handle.Favorites!.SetFavoriteAsync("item-004", true);
        Assert.True(mock.IsFavorite("item-004"));

        bool result = await handle.Favorites!.SetFavoriteAsync("item-004", false);

        Assert.False(result);
        Assert.False(mock.IsFavorite("item-004"));

        RecordedRequest delete = Assert.Single(mock.Requests, request => request.Method == "DELETE");
        Assert.EndsWith("/Users/" + UserId + "/FavoriteItems/item-004", delete.Path, StringComparison.Ordinal);
    }

    [Fact]
    public async Task The_result_is_the_server_state_rather_than_an_echo_of_the_request()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        // A write the server accepts and does not apply: echoing the request would say true here.
        mock.SetFavoriteWritesIgnored(true);
        Assert.False(await handle.Favorites!.SetFavoriteAsync("item-005", true));
        Assert.False(mock.IsFavorite("item-005"));

        // The mirror image, so "always returns false" is not a way to pass the case above: the server
        // holds the item as favourited while the request asks for the opposite.
        mock.SetFavoriteWritesIgnored(false);
        Assert.True(await handle.Favorites!.SetFavoriteAsync("item-006", true));
        mock.SetFavoriteWritesIgnored(true);
        Assert.True(await handle.Favorites!.SetFavoriteAsync("item-006", false));
        Assert.True(mock.IsFavorite("item-006"));
    }

    [Fact]
    public async Task A_server_that_does_not_report_the_flag_falls_back_to_the_requested_value()
    {
        var options = new MockServerOptions { OmitIsFavoriteFromUserData = true };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.True(await handle.Favorites!.SetFavoriteAsync("item-007", true));
        Assert.False(await handle.Favorites!.SetFavoriteAsync("item-008", false));
    }

    [Fact]
    public async Task GetFavorites_returns_only_the_starred_items_and_asks_for_them_by_filter()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        await handle.Favorites!.SetFavoriteAsync("item-003", true);
        await handle.Favorites!.SetFavoriteAsync("item-017", true);

        IReadOnlyList<MediaItem> favorites = await handle.Favorites!.GetFavoritesAsync(20);

        Assert.Equal(new[] { "item-003", "item-017" }, favorites.Select(item => item.Id));
        Assert.All(favorites, item => Assert.True(item.UserDataFavorite));

        RecordedRequest list = Assert.Single(mock.Requests, request =>
            request.Method == "GET" && request.Path.EndsWith("/Items", StringComparison.Ordinal));
        Assert.Contains("Filters=IsFavorite", list.Query);
        Assert.Contains("Recursive=true", list.Query);
        Assert.Contains("IncludeItemTypes=Movie", list.Query);
        Assert.Contains("Limit=20", list.Query);
    }

    [Fact]
    public async Task A_non_positive_limit_asks_the_server_nothing()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        // Emby reads an absent or zero Limit as "no limit", so sending Limit=0 for a limit of zero would
        // ask for the whole library instead of nothing.
        Assert.Empty(await handle.Favorites!.GetFavoritesAsync(0));
        Assert.Empty(await handle.Favorites!.GetFavoritesAsync(-5));

        Assert.DoesNotContain(mock.Requests, request =>
            request.Path.Equals("/emby/Users/" + UserId + "/Items", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task A_favourite_the_server_drops_is_not_reported_as_favourited()
    {
        // Two reads of the same state, one after the write and one through the list query: the mock
        // keeps a single source of truth, so the two routes can never disagree.
        await using var mock = await MockMediaServer.StartAsync(ServerType.Jellyfin);
        using ProviderHandle handle = await ConnectAsync(mock, new JellyfinProviderFactory());
        await handle.Favorites!.SetFavoriteAsync("item-010", true);

        Assert.True(mock.IsFavorite("item-010"));
        IReadOnlyList<MediaItem> favorites = await handle.Favorites!.GetFavoritesAsync(10);
        Assert.Equal("item-010", Assert.Single(favorites).Id);
    }
}

/// <summary>
/// The mock's own favourite routes, asserted directly rather than through the provider, because a mock
/// that quietly stopped honouring the filter would make every provider test above pass for the wrong
/// reason.
/// </summary>
public sealed class FavoriteMockFidelityTests
{
    private const string Token = "token-abc";
    private const string UserId = "user-1";

    private static async Task<(HttpStatusCode Status, string Body)> SendAsync(MockMediaServer mock, HttpMethod method, string route)
    {
        using var factory = new MediaSourceHttpClientFactory(new NetworkPolicy { Mode = ProxyMode.Direct });
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(method, mock.Url(route));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }

    private static bool? ReadIsFavorite(string body)
    {
        using JsonDocument document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("UserData", out JsonElement userData)) return null;
        if (!userData.TryGetProperty("IsFavorite", out JsonElement favorite)) return null;
        return favorite.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => (bool?)null,
        };
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task The_Favorites_spelling_is_404_because_both_servers_answer_it_alone(ServerType type)
    {
        await using var mock = await MockMediaServer.StartAsync(type);

        (HttpStatusCode status, _) = await SendAsync(mock, HttpMethod.Post, "/Users/" + UserId + "/Favorites/item-000");

        Assert.Equal(HttpStatusCode.NotFound, status);
    }

    [Fact]
    public async Task Item_payloads_and_the_favourite_filter_read_the_same_state()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        (HttpStatusCode before, string beforeBody) = await SendAsync(mock, HttpMethod.Get, "/Users/" + UserId + "/Items/item-000");
        Assert.Equal(HttpStatusCode.OK, before);
        Assert.False(ReadIsFavorite(beforeBody));

        (HttpStatusCode write, string writeBody) = await SendAsync(mock, HttpMethod.Post, "/Users/" + UserId + "/FavoriteItems/item-000");
        Assert.Equal(HttpStatusCode.OK, write);
        Assert.Equal(string.Empty, writeBody);

        (_, string afterBody) = await SendAsync(mock, HttpMethod.Get, "/Users/" + UserId + "/Items/item-000");
        Assert.True(ReadIsFavorite(afterBody));

        (_, string page) = await SendAsync(mock, HttpMethod.Get, "/Users/" + UserId + "/Items?Filters=IsFavorite&Recursive=true");
        using JsonDocument document = JsonDocument.Parse(page);
        Assert.Equal(
            new[] { "item-000" },
            document.RootElement.GetProperty("Items").EnumerateArray().Select(item => item.GetProperty("Id").GetString()));

        // The whole library is still there without the filter, so the filter is doing the work rather
        // than the catalogue having shrunk to one item.
        (_, string all) = await SendAsync(mock, HttpMethod.Get, "/Users/" + UserId + "/Items?Recursive=true");
        using JsonDocument everything = JsonDocument.Parse(all);
        Assert.Equal(25, everything.RootElement.GetProperty("TotalRecordCount").GetInt32());
    }

    [Fact]
    public async Task The_item_payload_can_omit_IsFavorite_when_the_test_asks_it_to()
    {
        var options = new MockServerOptions { OmitIsFavoriteFromUserData = true };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);

        (_, string body) = await SendAsync(mock, HttpMethod.Get, "/Users/" + UserId + "/Items/item-001");

        Assert.Null(ReadIsFavorite(body));
    }
}

/// <summary>How the server's <c>UserData.IsFavorite</c> reaches the domain model.</summary>
public sealed class FavoriteMappingTests
{
    [Theory]
    [InlineData("""{ "Id": "x", "UserData": { "IsFavorite": true } }""", true)]
    [InlineData("""{ "Id": "x", "UserData": { "IsFavorite": false } }""", false)]
    public void IsFavorite_maps_onto_the_domain_flag(string json, bool expected)
    {
        using var document = JsonDocument.Parse(json);
        MediaItem item = MediaBrowserMapper.MapItem("srv", document.RootElement);
        Assert.Equal(expected, item.UserDataFavorite);
    }

    [Fact]
    public void An_absent_IsFavorite_stays_null_rather_than_false()
    {
        // null means "the server did not say", which the UI must be able to render as neither state;
        // collapsing it to false would show a server that has no favourites as "everything unstarred".
        using var document = JsonDocument.Parse("""{ "Id": "x", "UserData": { "Played": false } }""");
        MediaItem item = MediaBrowserMapper.MapItem("srv", document.RootElement);
        Assert.Null(item.UserDataFavorite);
    }

    [Fact]
    public void An_IsFavorite_that_is_not_a_boolean_stays_null()
    {
        using var document = JsonDocument.Parse("""{ "Id": "x", "UserData": { "IsFavorite": "yes" } }""");
        MediaItem item = MediaBrowserMapper.MapItem("srv", document.RootElement);
        Assert.Null(item.UserDataFavorite);
    }
}

/// <summary>Favourites as a declared and a runtime capability.</summary>
public sealed class FavoriteCapabilityTests
{
    [Fact]
    public void Media_browser_factories_declare_and_advertise_favourites()
    {
        foreach (IMediaProviderFactory factory in new IMediaProviderFactory[] { new EmbyProviderFactory(), new JellyfinProviderFactory() })
        {
            var server = new MediaServer("id", factory.Descriptor.Type, "name",
                new[] { new ServerEndpoint("http://127.0.0.1:8096") });
            using ProviderHandle handle = factory.Create(server, Net.NetworkPolicy.FollowSystem);

            Assert.NotNull(handle.Favorites);
            Assert.True(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Favorite));
            Assert.True(handle.Descriptor.DeclaredCapabilities.HasFlag(ProviderCapability.Favorite));

            // Runtime capabilities are derived from the interfaces present, so they may promise less
            // than the declaration but never more.
            Assert.Equal(ProviderCapability.None, handle.RuntimeCapabilities & ~handle.Descriptor.DeclaredCapabilities);
        }
    }

    [Fact]
    public void An_artwork_only_handle_does_not_claim_subtitles_or_progress()
    {
        // Regression pin: the handle used to advertise Subtitle whenever an image fetcher was present,
        // which enabled a subtitle menu on sources that never had one.
        var server = new MediaServer("s", ServerType.Emby, "n", Array.Empty<ServerEndpoint>());
        using var handle = new ProviderHandle
        {
            ServerId = "s",
            Server = server,
            Descriptor = new ProviderDescriptor(ServerType.Emby, "Emby", "description", ProviderCapability.Detail),
            Images = new NoopImages(),
        };

        // An image fetcher is storage for artwork, not a capability in its own right, so a handle that
        // carries nothing else advertises nothing.
        Assert.Equal(ProviderCapability.None, handle.RuntimeCapabilities);
        Assert.False(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Subtitle));
        Assert.False(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Progress));
        Assert.False(handle.RuntimeCapabilities.HasFlag(ProviderCapability.Favorite));
    }

    private sealed class NoopImages : IProviderImageFetcher
    {
        public Task<byte[]> FetchImageAsync(string itemId, string imageType, string? tag, int maxWidth, CancellationToken ct = default)
            => Task.FromResult(Array.Empty<byte>());
    }
}

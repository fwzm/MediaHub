using System.Diagnostics;
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
/// Drives the real Emby/Jellyfin provider stack against the local mock server over real loopback
/// sockets.
/// </summary>
/// <remarks>
/// These tests pin the protocol facts that a wrong implementation would still hide behind a green
/// status code: which root the dialect mounts the API on, which header carries the token, what a
/// Range request returns byte for byte, and where credentials do and do not survive a redirect.
/// </remarks>
public sealed class MockServerIntegrationTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string Token = "token-abc";

    /// <summary>Direct mode: the loopback mock must never be reached through a machine proxy.</summary>
    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    /// <summary>Builds the real provider handle and authenticates it against the mock.</summary>
    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, IMediaProviderFactory factory)
    {
        ProviderHandle handle = factory.Create(ServerFor(mock, factory.Descriptor.Type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    private static async Task<MediaItem> FirstItemAsync(ProviderHandle handle)
    {
        PagedResult<MediaItem> page = await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1));
        return page.Items[0];
    }

    private sealed record MediaResponse(HttpStatusCode Status, string? ContentRange, byte[] Body, string? ContentType, string? AcceptRanges);

    /// <summary>Fetches the media route through the provider HTTP stack and buffers the answer.</summary>
    private static async Task<MediaResponse> GetStreamAsync(MockMediaServer mock, string itemId, string? range)
    {
        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateMediaClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url("/Videos/" + itemId + "/stream.mp4?static=true"));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        if (range is not null) request.Headers.TryAddWithoutValidation("Range", range);

        using HttpResponseMessage response = await client.SendAsync(request);
        return new MediaResponse(
            response.StatusCode,
            response.Content.Headers.ContentRange?.ToString(),
            await response.Content.ReadAsByteArrayAsync(),
            response.Content.Headers.ContentType?.MediaType,
            TryReadHeader(response, "Accept-Ranges"));
    }

    /// <summary>Reads a response header whichever side of the message .NET classified it under.</summary>
    private static string? TryReadHeader(HttpResponseMessage response, string name)
    {
        if (response.Headers.TryGetValues(name, out IEnumerable<string>? values)) return string.Join(",", values);
        if (response.Content.Headers.TryGetValues(name, out IEnumerable<string>? contentValues)) return string.Join(",", contentValues);
        return null;
    }

    /// <summary>Issues a GET against an absolute route with a valid token and returns the status.</summary>
    private static async Task<HttpStatusCode> GetStatusAsync(MockMediaServer mock, string route)
    {
        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(mock.BaseUri, route));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);
        return response.StatusCode;
    }

    // ---------------------------------------------------------------- dialect roots and auth

    [Fact]
    public async Task Emby_authenticates_under_the_emby_root_and_lists_the_two_libraries()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        IReadOnlyList<MediaLibrary> libraries = await handle.Library!.GetLibrariesAsync();

        Assert.Equal(2, libraries.Count);
        Assert.Equal("Movies", libraries[0].Name);
        Assert.Equal(LibraryType.Movies, libraries[0].Type);
        Assert.Equal("TV Shows", libraries[1].Name);
        Assert.Equal(LibraryType.TvShows, libraries[1].Type);

        // The mock answers 404 outside the dialect root, so "everything arrived under /emby" is what
        // proves the Emby root rule end to end rather than by reading the URL builder.
        Assert.DoesNotContain(mock.Requests, r => !r.Path.StartsWith("/emby", StringComparison.OrdinalIgnoreCase));

        RecordedRequest views = Assert.Single(mock.RequestsFor("/emby/Users/user-1/Views"));
        Assert.Equal(Token, views.Token);
        Assert.Equal(Token, views.Header("X-Emby-Token"));
        Assert.False(views.HasHeader("Authorization"));
    }

    [Fact]
    public async Task Jellyfin_authenticates_from_the_server_root_with_the_token_in_the_authorization_header()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Jellyfin);
        using ProviderHandle handle = await ConnectAsync(mock, new JellyfinProviderFactory());

        IReadOnlyList<MediaLibrary> libraries = await handle.Library!.GetLibrariesAsync();

        Assert.Equal(2, libraries.Count);
        Assert.Equal(LibraryType.Movies, libraries[0].Type);
        Assert.Equal(LibraryType.TvShows, libraries[1].Type);
        Assert.DoesNotContain(mock.Requests, r => r.Path.StartsWith("/emby", StringComparison.OrdinalIgnoreCase));

        RecordedRequest views = Assert.Single(mock.RequestsFor("/Users/user-1/Views"));
        Assert.Equal(Token, views.Token);
        // Jellyfin embeds the token in the authorization header; a dedicated token header would mean
        // the Emby dialect had been built for a Jellyfin server.
        Assert.False(views.HasHeader("X-Emby-Token"));
        Assert.Contains(Token, views.Header("Authorization")!);
    }

    [Fact]
    public async Task Wrong_dialect_root_is_not_silently_served()
    {
        // Without this property the two root assertions above would be no evidence at all: a mock that
        // answered both roots would hide exactly the mistake they are meant to catch.
        await using var embyMock = await MockMediaServer.StartAsync(ServerType.Emby);
        await using var jellyfinMock = await MockMediaServer.StartAsync(ServerType.Jellyfin);

        Assert.Equal(HttpStatusCode.NotFound, await GetStatusAsync(embyMock, "/Users/user-1/Views"));
        Assert.Equal(HttpStatusCode.NotFound, await GetStatusAsync(jellyfinMock, "/emby/Users/user-1/Views"));

        Assert.NotEmpty(embyMock.RequestsFor("/Users/user-1/Views"));
        Assert.NotEmpty(jellyfinMock.RequestsFor("/emby/Users/user-1/Views"));
    }

    [Fact]
    public async Task Invalid_token_surfaces_as_AuthExpired()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        mock.SetTokenValid(false);

        ProviderException failure = await Assert.ThrowsAsync<ProviderException>(() => handle.Library!.GetLibrariesAsync());
        Assert.Equal(ProviderErrorCode.AuthExpired, failure.Code);
    }

    [Fact]
    public async Task Wrong_password_surfaces_as_AuthFailed()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = new EmbyProviderFactory().Create(ServerFor(mock, ServerType.Emby), DirectPolicy());

        ProviderException failure = await Assert.ThrowsAsync<ProviderException>(
            () => handle.Auth!.AuthenticateAsync(new Credentials(User, "not-the-password")));

        Assert.Equal(ProviderErrorCode.AuthFailed, failure.Code);
        Assert.NotEmpty(mock.RequestsFor("/emby/Users/AuthenticateByName"));
    }

    [Fact]
    public async Task Restore_session_round_trips_through_the_item_probe()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Equal(AuthSessionState.Authenticated, await handle.Auth!.RestoreSessionAsync());
        Assert.NotEmpty(mock.RequestsFor("/emby/Users/user-1/Items/user-1"));

        mock.SetTokenValid(false);
        Assert.Equal(AuthSessionState.Expired, await handle.Auth!.RestoreSessionAsync());
    }

    // ---------------------------------------------------------------- library, search, playback

    [Fact]
    public async Task GetItemsAsync_paginates_and_reports_the_unpaged_total()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        PagedResult<MediaItem> first = await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 10));

        Assert.Equal(10, first.Items.Count);
        Assert.Equal(25, first.TotalCount);
        Assert.Equal("item-000", first.Items[0].Id);
        Assert.Equal("item-009", first.Items[^1].Id);
        Assert.True(first.HasMore);

        // A later page distinguishes a real StartIndex from a server that ignores it and repeats the
        // first ten items.
        PagedResult<MediaItem> third = await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(20, 10));

        Assert.Equal(5, third.Items.Count);
        Assert.Equal("item-020", third.Items[0].Id);
        Assert.Equal(25, third.TotalCount);
        Assert.False(third.HasMore);
    }

    [Fact]
    public async Task Search_sends_the_term_and_returns_only_matching_items()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        PagedResult<MediaItem> result = await handle.Search!.SearchAsync("Star Wars", new PageRequest(0, 50));

        Assert.Equal(5, result.Items.Count);
        Assert.Equal(5, result.TotalCount);
        Assert.All(result.Items, item => Assert.Contains("Star Wars", item.Name));
        Assert.Contains(result.Items, item => item.Id == "item-000");

        // The mock returns the whole library when SearchTerm is missing, so a filtered answer also
        // proves the term reached the server, escaping included.
        Assert.Contains(mock.RequestsFor("/emby/Users/user-1/Items"), r => r.PathAndQuery.Contains("SearchTerm=Star%20Wars"));
    }

    [Fact]
    public async Task ResolvePlaybackAsync_returns_a_token_free_stream_url_on_the_mock_host()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        MediaItem item = await FirstItemAsync(handle);

        PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());

        Assert.Equal("127.0.0.1", source.Url.Host);
        Assert.Equal(mock.BaseUri.Port, source.Url.Port);
        Assert.Equal(PlaybackMode.Stream, source.Mode);
        Assert.Equal("mp4", source.Container);
        Assert.Equal("/emby/Videos/item-000/stream.mp4", source.Url.AbsolutePath);

        // ADR-026: the stream URL carries resource identity only, never a credential.
        Assert.DoesNotContain(Token, source.Url.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("api_key", source.Url.Query, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("MediaSourceId=mediasource-item-000", source.Url.Query);
        Assert.Contains("static=true", source.Url.Query);
        Assert.NotNull(source.SessionId);
        Assert.Contains("PlaySessionId=" + source.SessionId, source.Url.Query);

        // Server-supplied playback headers pass a sensitivity filter: the harmless one survives, the
        // credential-shaped one does not.
        Assert.Equal("keep-me", source.Headers["X-Media-Probe"]);
        Assert.DoesNotContain(source.Headers.Keys, OriginScopedCredentialHandler.IsSensitive);
        Assert.NotEmpty(mock.RequestsFor("/emby/Items/item-000/PlaybackInfo"));
    }

    [Fact]
    public async Task Progress_reports_reach_the_session_endpoints_with_the_play_session_id()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        MediaItem item = await FirstItemAsync(handle);
        PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());

        await handle.Progress!.ReportProgressAsync(new PlaybackProgress
        {
            ServerId = "mock-server",
            ItemId = item.Id,
            PositionTicks = 12_000_000,
            SessionId = source.SessionId,
            Mode = PlaybackMode.Stream,
        });
        await handle.Progress!.ReportFinalProgressAsync(new PlaybackProgress
        {
            ServerId = "mock-server",
            ItemId = item.Id,
            // ADR-040: an absent PositionTicks makes the server treat the item as fully watched, so
            // zero has to be sent as a value rather than omitted.
            PositionTicks = 0,
            SessionId = source.SessionId,
            Mode = PlaybackMode.Stream,
        });

        // Reporting swallows HTTP failures by design, so the assertion has to be on what the server
        // received rather than on the absence of a thrown exception.
        RecordedRequest progress = Assert.Single(mock.RequestsFor("/emby/Sessions/Playing/Progress"));
        Assert.Equal("POST", progress.Method);
        Assert.Equal(Token, progress.Token);
        using JsonDocument progressBody = JsonDocument.Parse(progress.Body!);
        Assert.Equal(item.Id, progressBody.RootElement.GetProperty("ItemId").GetString());
        Assert.Equal(source.SessionId, progressBody.RootElement.GetProperty("PlaySessionId").GetString());
        Assert.Equal(12_000_000, progressBody.RootElement.GetProperty("PositionTicks").GetInt64());
        Assert.Equal("DirectStream", progressBody.RootElement.GetProperty("PlayMethod").GetString());

        RecordedRequest stopped = Assert.Single(mock.RequestsFor("/emby/Sessions/Playing/Stopped"));
        using JsonDocument stoppedBody = JsonDocument.Parse(stopped.Body!);
        Assert.True(stoppedBody.RootElement.TryGetProperty("PositionTicks", out JsonElement position));
        Assert.Equal(0, position.GetInt64());
    }

    // ---------------------------------------------------------------- media bytes and range

    [Fact]
    public async Task Range_request_returns_206_with_the_requested_slice()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        MediaResponse response = await GetStreamAsync(mock, "item-000", "bytes=0-1023");

        Assert.Equal(HttpStatusCode.PartialContent, response.Status);
        Assert.Equal("bytes 0-1023/" + mock.MediaBytes.Length, response.ContentRange);
        Assert.Equal(1024, response.Body.Length);
        Assert.Equal(mock.MediaBytes.Take(1024).ToArray(), response.Body);
    }

    [Fact]
    public async Task Request_without_range_returns_200_and_the_whole_payload()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        MediaResponse response = await GetStreamAsync(mock, "item-000", null);

        Assert.Equal(HttpStatusCode.OK, response.Status);
        Assert.Null(response.ContentRange);
        Assert.Equal(mock.MediaBytes.Length, response.Body.Length);
        Assert.Equal(mock.MediaBytes, response.Body);
        Assert.Equal("bytes", response.AcceptRanges);
        Assert.Equal("video/mp4", response.ContentType);
    }

    [Fact]
    public async Task Suffix_range_returns_the_last_bytes()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        int total = mock.MediaBytes.Length;

        MediaResponse response = await GetStreamAsync(mock, "item-000", "bytes=-256");

        Assert.Equal(HttpStatusCode.PartialContent, response.Status);
        Assert.Equal("bytes " + (total - 256) + "-" + (total - 1) + "/" + total, response.ContentRange);
        Assert.Equal(mock.MediaBytes.Skip(total - 256).ToArray(), response.Body);
    }

    [Fact]
    public async Task Unsatisfiable_range_returns_416()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        MediaResponse response = await GetStreamAsync(mock, "item-000", "bytes=" + (mock.MediaBytes.Length + 100) + "-");

        Assert.Equal(HttpStatusCode.RequestedRangeNotSatisfiable, response.Status);
        Assert.Equal("bytes */" + mock.MediaBytes.Length, response.ContentRange);
        Assert.Empty(response.Body);
    }

    // ---------------------------------------------------------------- credential scoping on redirect

    [Fact]
    public async Task Same_origin_redirect_forwards_the_token_to_the_followed_request()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        mock.AddRedirect("/emby/Videos/item-000/stream.mp4", "/emby/redirected/stream.mp4", 302);

        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url("/Videos/item-000/stream.mp4"));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);

        // Nothing is routed behind the redirect, so the followed hop is a 404. What is under test is
        // that the hop happened and which headers it carried.
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        RecordedRequest followed = Assert.Single(mock.RequestsFor("/emby/redirected/stream.mp4"));
        Assert.Equal(Token, followed.Token);
        Assert.Equal(Token, followed.Header("X-Emby-Token"));
    }

    [Fact]
    public async Task Cross_origin_redirect_strips_the_token_before_the_target_sees_it()
    {
        await using var origin = await MockMediaServer.StartAsync(ServerType.Emby);
        await using var target = await MockMediaServer.StartAsync(ServerType.Jellyfin);
        origin.AddRedirect("/emby/Videos/item-000/stream.mp4", new Uri(target.BaseUri, "captured").ToString(), 302);

        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, origin.Url("/Videos/item-000/stream.mp4"));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + Token);
        request.Headers.TryAddWithoutValidation("Cookie", "session=secret");
        using HttpResponseMessage response = await client.SendAsync(request);

        // The hop happened: with no credential the target answers 401 on any non-public path.
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);

        RecordedRequest followed = Assert.Single(target.RequestsFor("/captured"));
        Assert.Null(followed.Token);
        Assert.False(followed.HasHeader("X-Emby-Token"));
        Assert.False(followed.HasHeader("Authorization"));
        Assert.False(followed.HasHeader("Cookie"));
        // Form-independent check: no credential value of any shape may appear in any header.
        Assert.DoesNotContain(Token, string.Join(" ", followed.Headers.Values));
    }

    // ---------------------------------------------------------------- other routes and fault injection

    [Fact]
    public async Task Image_route_serves_png_bytes()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateApiClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, mock.Url("/Items/item-000/Images/Primary?maxWidth=300&quality=85"));
        request.Headers.TryAddWithoutValidation("X-Emby-Token", Token);
        using HttpResponseMessage response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("image/png", response.Content.Headers.ContentType!.MediaType);
        byte[] png = await response.Content.ReadAsByteArrayAsync();
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }, png.Take(8).ToArray());
    }

    [Fact]
    public async Task Provider_artwork_fetch_goes_through_the_authenticated_item_route()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        byte[] png = await handle.Images!.FetchImageAsync("item-000", "Primary", "primary-item-000", 300);

        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }, png.Take(8).ToArray());
        RecordedRequest image = Assert.Single(mock.RequestsFor("/emby/Items/item-000/Images/Primary"));
        Assert.Equal(Token, image.Token);
        Assert.Contains("maxWidth=300", image.PathAndQuery);
        Assert.Contains("tag=primary-item-000", image.PathAndQuery);
    }

    [Fact]
    public async Task Probe_reports_the_identity_from_the_public_endpoint()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);

        MediaServerProbe.ProbeResult result = await MediaServerProbe.ProbeMediaBrowserAsync(mock.BaseUri, ServerType.Emby, DirectPolicy());

        Assert.True(result.Success, result.Message);
        Assert.Equal("mock-server-id", result.RemoteServerId);
        Assert.Equal("Mock Media Server", result.ServerName);
        Assert.Equal("4.7.0.0", result.Version);
        Assert.Single(mock.RequestsFor("/emby/System/Info/Public"));
    }

    [Fact]
    public async Task Injected_failure_answers_once_and_then_the_server_recovers()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        mock.SetNextFailure(429, 1, "7");

        ProviderException failure = await Assert.ThrowsAsync<ProviderException>(() => handle.Library!.GetLibrariesAsync());
        Assert.Equal(ProviderErrorCode.RateLimited, failure.Code);

        Assert.Equal(2, (await handle.Library!.GetLibrariesAsync()).Count);
    }
    [Fact]
    public async Task Injected_failure_can_carry_a_retry_after_header()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        mock.SetNextFailure(503, 1, "9");

        using var factory = new MediaSourceHttpClientFactory(DirectPolicy());
        using HttpClient client = factory.CreateApiClient();
        using HttpResponseMessage response = await client.GetAsync(mock.Url("/System/Info/Public"));

        // Whatever reads Retry-After later (backoff, "server busy" UI) needs the injection to produce
        // the header and not only the status code.
        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.Equal("9", response.Headers.RetryAfter!.ToString());
    }

    [Fact]
    public async Task Stopped_server_surfaces_as_a_network_error()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        mock.Stop();

        ProviderException failure = await Assert.ThrowsAsync<ProviderException>(() => handle.Library!.GetLibrariesAsync());
        Assert.Equal(ProviderErrorCode.Network, failure.Code);
    }

    [Fact]
    public async Task SetDelay_delays_the_response()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        mock.SetDelay(TimeSpan.FromMilliseconds(400));

        var stopwatch = Stopwatch.StartNew();
        await handle.Library!.GetLibrariesAsync();
        stopwatch.Stop();

        Assert.True(
            stopwatch.Elapsed >= TimeSpan.FromMilliseconds(300),
            "response returned in " + stopwatch.ElapsedMilliseconds + " ms despite a 400 ms delay");
    }
}

using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Saving a playback position back into a MediaBrowser server: which route and verb the write uses, and
/// what the returned number means.
/// </summary>
/// <remarks>
/// The returned value is the point of these tests. A 200 only proves the request was accepted, so every
/// case here can tell "the server stored this" from "the client repeated what it sent": one write is
/// ignored by the server, one answer omits the field entirely, and both would pass against an echo.
/// </remarks>
public sealed class ProviderPlaybackWriteTests
{
    private const string User = "tester";
    private const string Password = "secret";
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
    public async Task The_write_uses_the_UserData_route_and_stores_the_position_on_the_server()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        Assert.NotNull(handle.ProgressWrite);

        long stored = await handle.ProgressWrite!.SetPlaybackPositionAsync("item-003", 123_456_789L);

        Assert.Equal(123_456_789L, stored);
        Assert.Equal(123_456_789L, mock.UserDataOf("item-003")!.PlaybackPositionTicks);

        RecordedRequest write = Assert.Single(mock.Requests, request =>
            request.Method == "POST" && request.Path.EndsWith("/UserData", StringComparison.Ordinal));
        Assert.Equal("/emby/Users/" + UserId + "/Items/item-003/UserData", write.Path);
        Assert.NotNull(write.Body);
        using (JsonDocument body = JsonDocument.Parse(write.Body!))
        {
            Assert.Equal(123_456_789L, body.RootElement.GetProperty("PlaybackPositionTicks").GetInt64());
        }

        // The position is the only thing sent: no token, no password, and not the favourites route.
        Assert.DoesNotContain("token-abc", write.Body);
        Assert.DoesNotContain("secret", write.Body);
        Assert.DoesNotContain(mock.Requests, request => request.Path.Contains("/FavoriteItems/", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task A_write_the_server_ignores_comes_back_as_the_stored_position_not_the_requested_one()
    {
        var options = new MockServerOptions
        {
            UserData = new Dictionary<string, MockUserData>
            {
                ["item-010"] = new MockUserData(300_000_000L, Played: false),
            },
        };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        // Accepted and dropped: an echo would answer 999 and a restore would believe it.
        mock.SetUserDataWritesIgnored(true);
        Assert.Equal(300_000_000L, await handle.ProgressWrite!.SetPlaybackPositionAsync("item-010", 999L));
        Assert.Equal(300_000_000L, mock.UserDataOf("item-010")!.PlaybackPositionTicks);

        // The mirror image, so "always returns the old value" is not a way to pass the case above.
        mock.SetUserDataWritesIgnored(false);
        Assert.Equal(222L, await handle.ProgressWrite.SetPlaybackPositionAsync("item-010", 222L));
        Assert.Equal(222L, mock.UserDataOf("item-010")!.PlaybackPositionTicks);
    }

    [Fact]
    public async Task An_answer_that_omits_the_position_is_read_back_from_the_item()
    {
        var options = new MockServerOptions { OmitPlaybackPositionFromUserDataResponse = true };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        Assert.Equal(42L, await handle.ProgressWrite!.SetPlaybackPositionAsync("item-011", 42L));

        // The write, then the read that supplies what the write answer withheld.
        IReadOnlyList<RecordedRequest> requests = mock.Requests;
        int writeAt = requests.ToList().FindIndex(request => request.Method == "POST");
        int readAt = requests.ToList().FindIndex(request => request.Method == "GET" && request.Path.EndsWith("/Items/item-011", StringComparison.Ordinal));
        Assert.True(writeAt >= 0, "no write was recorded");
        Assert.True(readAt > writeAt, "the item was not read after the write");
    }

    [Fact]
    public async Task An_unknown_item_is_a_not_found_provider_error()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            handle.ProgressWrite!.SetPlaybackPositionAsync("item-999", 5L));

        Assert.Equal(ProviderErrorCode.NotFound, error.Code);
        Assert.Single(mock.RequestsFor("/emby/Users/" + UserId + "/Items/item-999"));
    }

    [Fact]
    public async Task A_stale_token_is_reported_as_an_expired_session()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        mock.SetTokenValid(false);

        ProviderException error = await Assert.ThrowsAsync<ProviderException>(() =>
            handle.ProgressWrite!.SetPlaybackPositionAsync("item-012", 5L));

        Assert.Equal(ProviderErrorCode.AuthExpired, error.Code);
    }

    [Fact]
    public async Task Jellyfin_serves_the_same_write_from_the_server_root()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Jellyfin);
        using ProviderHandle handle = await ConnectAsync(mock, new JellyfinProviderFactory());

        Assert.Equal(5_000L, await handle.ProgressWrite!.SetPlaybackPositionAsync("item-002", 5_000L));
        Assert.Equal(5_000L, mock.UserDataOf("item-002")!.PlaybackPositionTicks);

        RecordedRequest write = Assert.Single(mock.Requests, request =>
            request.Method == "POST" && request.Path.EndsWith("/UserData", StringComparison.Ordinal));
        Assert.Equal("/Users/" + UserId + "/Items/item-002/UserData", write.Path);
    }
}

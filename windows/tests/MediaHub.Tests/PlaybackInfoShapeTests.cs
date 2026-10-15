using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins where the playback session id is read from.
/// </summary>
/// <remarks>
/// A real defect, found only against a real server. Jellyfin 12.2 returns PlaySessionId at the ROOT of
/// the PlaybackInfo response; it is not a field of the media source. The client read it from the source
/// and threw a parse error when it was absent, so playback failed 100% of the time against a real
/// server while every test passed - because the mock put the value in both places, mirroring the
/// client's assumption instead of the server's behaviour.
/// <para>
/// These tests pin the server's shape, so a mock that is "helpfully" more permissive cannot hide it
/// again.
/// </para>
/// </remarks>
public sealed class PlaybackInfoShapeTests
{
    private const string User = "tester";
    private const string Password = "secret";

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static async Task<(MockMediaServer Mock, ProviderHandle Handle)> ConnectAsync(ServerType type)
    {
        MockMediaServer mock = await MockMediaServer.StartAsync(type,
            new MockServerOptions { ServerId = "playback-shape", UserId = "user-1", UserName = User, Password = Password, Token = "token-abc" });
        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        MediaServer server = new("mock", type, "mock", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
        ProviderHandle? handle = factory.Create(server, DirectPolicy());
        Assert.NotNull(handle);
        await handle!.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return (mock, handle);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task PlaybackInfo_carries_the_session_id_at_the_root(ServerType type)
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type);
        await using (mock)
        using (handle)
        {
            PagedResult<MediaItem> page = await handle.Library!.GetItemsAsync(string.Empty, new PageRequest(0, 1));
            Assert.NotEmpty(page.Items);

            MediaDetail detail = await handle.Detail!.GetItemDetailAsync(page.Items[0].Id);
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                detail.Item, new PlaybackOptions());

            // The value must come from the response root. If the mock ever puts it back inside the
            // media source without the root, this fails - which is the point.
            Assert.False(string.IsNullOrEmpty(source.SessionId));
        }
    }

    [Fact]
    public async Task The_mock_does_not_put_the_session_id_inside_the_media_source()
    {
        // Faithfulness check on the harness itself: a mock that duplicates a field the server does not
        // duplicate cannot detect a client that reads it from the wrong place.
        await using MockMediaServer mock = await MockMediaServer.StartAsync(ServerType.Jellyfin,
            new MockServerOptions { ServerId = "playback-shape", UserId = "user-1" });

        using var client = new HttpClient();
        client.DefaultRequestHeaders.TryAddWithoutValidation("Authorization", @"MediaBrowser Token=""token-abc""");
        string body = await client.GetStringAsync(mock.Url("/Users/user-1/Items?ParentId=&StartIndex=0&Limit=1"));

        using var request = new HttpRequestMessage(HttpMethod.Post, mock.Url("/Items/item-000/PlaybackInfo?UserId=user-1"));
        request.Content = new StringContent("{\"UserId\":\"user-1\"}", System.Text.Encoding.UTF8, "application/json");
        HttpResponseMessage response = await client.SendAsync(request);
        string json = await response.Content.ReadAsStringAsync();

        Assert.Contains("\"PlaySessionId\"", json);
        // Exactly one occurrence: at the root. Two would mean it is also nested in the media source.
        int occurrences = json.Split("PlaySessionId").Length - 1;
        Assert.Equal(1, occurrences);
        Assert.NotEmpty(body);
    }
}

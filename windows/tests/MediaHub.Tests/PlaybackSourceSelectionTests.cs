using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins which media source is chosen when a server lists several versions of one title.
/// </summary>
/// <remarks>
/// Emby and Jellyfin answer <c>PlaybackInfo</c> with every version they hold, and a version marked
/// <c>SupportsDirectPlay=false</c> / <c>SupportsDirectStream=false</c> can only be served by transcoding.
/// The client used to take the first entry unconditionally, so a transcode-only first version produced a
/// URL the engine could not play, with no explanation. These tests pin the selection rule and the
/// failure message.
/// </remarks>
public sealed class PlaybackSourceSelectionTests
{
    private const string User = "tester";
    private const string Password = "secret";

    private static async Task<(MockMediaServer Mock, ProviderHandle Handle)> ConnectAsync(
        ServerType type, params MockMediaSourceCapabilities[] sources)
        => await ConnectAsync(type, videoTranscodingAllowed: null, sources);

    private static async Task<(MockMediaServer Mock, ProviderHandle Handle)> ConnectAsync(
        ServerType type, bool? videoTranscodingAllowed, params MockMediaSourceCapabilities[] sources)
    {
        MockMediaServer mock = await MockMediaServer.StartAsync(type, new MockServerOptions
        {
            ServerId = "playback-selection",
            UserId = "user-1",
            UserName = User,
            Password = Password,
            Token = "token-abc",
            PlaybackSources = sources,
            EnableVideoPlaybackTranscoding = videoTranscodingAllowed,
        });

        IMediaProviderFactory factory = type == ServerType.Emby ? new EmbyProviderFactory() : new JellyfinProviderFactory();
        MediaServer server = new("mock", type, "mock", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });
        ProviderHandle? handle = factory.Create(server, new NetworkPolicy { Mode = ProxyMode.Direct });
        Assert.NotNull(handle);
        await handle!.Auth!.AuthenticateAsync(new Credentials(User, Password));

        PagedResult<MediaItem> page = await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1));
        Assert.NotEmpty(page.Items);
        return (mock, handle);
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_transcode_only_first_version_is_skipped_for_a_playable_one(ServerType type)
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
            Assert.Contains("MediaSourceId=mediasource-item-000-1", source.Url.Query);
            Assert.Contains("static=true", source.Url.Query);
        }
    }

    [Fact]
    public async Task A_direct_stream_only_version_is_used_and_reported_as_stream()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Emby,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false),
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: true));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.Stream, source.Mode);
        }
    }

    [Fact]
    public async Task A_transcode_only_title_falls_back_to_the_server_hls_url()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Jellyfin,
            new MockMediaSourceCapabilities(
                SupportsDirectPlay: false,
                SupportsDirectStream: false,
                TranscodingUrl: "/videos/item-000/master.m3u8?MediaSourceId=mediasource-item-000"
                                + "&PlaySessionId=playsession-item-000&ApiKey=server-secret",
                TranscodingContainer: "ts"));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());

            Assert.Equal(PlaybackMode.Transcode, source.Mode);
            Assert.Equal("ts", source.Container);
            Assert.Equal("playsession-item-000", source.SessionId);
            Assert.EndsWith("/videos/item-000/master.m3u8", source.Url.AbsolutePath, StringComparison.Ordinal);
            // The server's own credential stays in the URL the relay fetches; the engine never sees it.
            Assert.Contains("ApiKey=server-secret", source.Url.Query, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task A_transcode_only_title_without_an_hls_url_still_fails_with_a_readable_reason()
    {        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Emby,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false),
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];

            ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
                () => handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions()));

            Assert.Equal(ProviderErrorCode.Connection, ex.Code);
            Assert.Contains("只提供转码流", ex.Message);
        }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task A_transcode_refusal_separates_a_disabled_policy_from_a_failed_start(bool transcodingAllowed)
    {
        // Both lab servers answer a transcode request with no HLS URL because their accounts carry
        // EnableVideoPlaybackTranscoding=false. The user must read that as a server-side setting rather
        // than as a broken file. When the policy allows transcoding, the same silence is a start
        // failure, which is worth retrying.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Emby, transcodingAllowed,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];

            ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
                () => handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions { ForceTranscode = true }));

            Assert.Contains("强制转码", ex.Message);
            if (transcodingAllowed)
            {
                Assert.Contains("转码启动失败", ex.Message);
                Assert.DoesNotContain("关闭视频转码", ex.Message);
            }
            else
            {
                Assert.Contains("EnableVideoPlaybackTranscoding=false", ex.Message);
            }
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task A_forced_transcode_ignores_a_direct_playable_source(ServerType type)
    {
        // The player's force-transcode switch must reach HLS even for an item this client could play
        // unchanged; that is the whole point of the switch. The mock reports the flags a real server
        // reports for a client with no profile, so a direct URL here would be the silent fallback the
        // switch is supposed to prevent.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(
                SupportsDirectPlay: true,
                SupportsDirectStream: true,
                TranscodingUrl: "/videos/item-000/master.m3u8?MediaSourceId=mediasource-item-000"
                                + "&PlaySessionId=playsession-item-000&ApiKey=server-secret",
                TranscodingContainer: "ts"));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { ForceTranscode = true });

            Assert.Equal(PlaybackMode.Transcode, source.Mode);
            Assert.Equal("ts", source.Container);
            Assert.EndsWith("/videos/item-000/master.m3u8", source.Url.AbsolutePath, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task A_forced_transcode_the_server_refuses_fails_instead_of_playing_direct()
    {
        // A server can answer a transcode request with a plain direct-playable source and no HLS URL.
        // Playing that silently would make a refused switch look like a successful one.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Jellyfin,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];

            ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
                () => handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions { ForceTranscode = true }));

            Assert.Equal(ProviderErrorCode.Connection, ex.Code);
            Assert.Contains("强制转码", ex.Message);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task An_explicit_version_is_played_instead_of_the_first_playable_one(ServerType type)
    {
        // Both versions are direct-playable, so the default rule would take the first. An explicit id
        // must win, otherwise the picker would show a version that is not the one playing.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions
            {
                MediaSourceId = "mediasource-item-000-1",
            });

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
            Assert.Contains("MediaSourceId=mediasource-item-000-1", source.Url.Query);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task An_explicit_version_that_is_transcode_only_uses_that_versions_own_hls_url(ServerType type)
    {
        // The first version is playable, the requested one is not. Falling back to the first would look
        // like the switch succeeded while playing the other file; the HLS URL must name the requested
        // version too, or the server would transcode its preferred one.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true),
            new MockMediaSourceCapabilities(
                SupportsDirectPlay: false,
                SupportsDirectStream: false,
                TranscodingUrl: "/videos/item-000/master.m3u8?MediaSourceId=mediasource-item-000-1"
                                + "&PlaySessionId=playsession-item-000&ApiKey=server-secret",
                TranscodingContainer: "ts"));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions
            {
                MediaSourceId = "mediasource-item-000-1",
            });

            Assert.Equal(PlaybackMode.Transcode, source.Mode);
            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Contains("MediaSourceId=mediasource-item-000-1", source.Url.Query);
        }
    }

    [Fact]
    public async Task An_explicit_transcode_only_version_without_hls_fails_naming_the_version()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Jellyfin,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true),
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];

            ProviderException ex = await Assert.ThrowsAsync<ProviderException>(() => handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { MediaSourceId = "mediasource-item-000-1" }));

            Assert.Equal(ProviderErrorCode.Connection, ex.Code);
            Assert.Contains("所选版本只提供转码流", ex.Message);
        }
    }

    [Fact]
    public async Task An_unknown_version_id_fails_instead_of_playing_another_version()
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(ServerType.Emby,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];

            ProviderException ex = await Assert.ThrowsAsync<ProviderException>(() => handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { MediaSourceId = "mediasource-gone" }));

            Assert.Equal(ProviderErrorCode.Connection, ex.Code);
            Assert.Contains("版本列表", ex.Message);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Highest_quality_preference_picks_the_largest_playable_version(ServerType type)
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 1280, Height: 720, BitRate: 3_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 3840, Height: 2160, BitRate: 20_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 1920, Height: 1080, BitRate: 8_000_000));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { QualityPreference = "highest" });

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Lowest_quality_preference_picks_the_smallest_playable_version(ServerType type)
    {
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 3840, Height: 2160, BitRate: 20_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 1280, Height: 720, BitRate: 3_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 1920, Height: 1080, BitRate: 8_000_000));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { QualityPreference = "lowest" });

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Quality_preference_never_moves_playback_onto_a_transcode(ServerType type)
    {
        // The 4K version would win on resolution, but only the 720p one can be played as-is. Direct
        // play stays the hard rule; quality preference only orders what is already playable.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false,
                Width: 3840, Height: 2160, BitRate: 20_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 1280, Height: 720, BitRate: 3_000_000));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { QualityPreference = "highest" });

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        }
    }

    [Theory]
    [InlineData(ServerType.Emby)]
    [InlineData(ServerType.Jellyfin)]
    public async Task Lowest_quality_preference_never_moves_playback_onto_a_transcode(ServerType type)
    {
        // The mirror boundary: the 720p version is the smallest but is transcode-only, while the 4K
        // one plays as-is. "Lowest" orders the playable versions; it must not pick the cheap one by
        // switching the session to HLS.
        (MockMediaServer mock, ProviderHandle handle) = await ConnectAsync(type,
            new MockMediaSourceCapabilities(SupportsDirectPlay: false, SupportsDirectStream: false,
                Width: 1280, Height: 720, BitRate: 3_000_000),
            new MockMediaSourceCapabilities(SupportsDirectPlay: true, SupportsDirectStream: true,
                Width: 3840, Height: 2160, BitRate: 20_000_000));
        await using (mock)
        using (handle)
        {
            MediaItem item = (await handle.Library!.GetItemsAsync(MockMediaServer.MoviesLibraryId, new PageRequest(0, 1))).Items[0];
            PlaybackSource source = await handle.Playback!.ResolvePlaybackAsync(
                item, new PlaybackOptions { QualityPreference = "lowest" });

            Assert.Equal("mediasource-item-000-1", source.MediaSourceId);
            Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        }
    }
}

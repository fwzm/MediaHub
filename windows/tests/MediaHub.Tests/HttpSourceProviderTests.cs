using System.Net;
using System.Text;
using MediaHub.Domain;
using MediaHub.Providers.Http;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The plain http(s) source: one address, one m3u list, no account.
/// </summary>
/// <remarks>
/// The parsing cases are the shapes hand-edited lists actually contain (BOM, CRLF, no header, relative
/// addresses, an HLS manifest that is not a list at all). The provider cases pin the two decisions the
/// rest of the app depends on: the item id is the playable address, and playback resolves to DirectPlay
/// so the stream stays on the shared loopback-bridge path.
/// </remarks>
public sealed class HttpSourceProviderTests
{
    private const string ListAddress = "http://media.test/list.m3u";

    // ---------------------------------------------------------------- parser

    [Fact]
    public void Titles_and_durations_are_read_from_the_header_and_extinf_lines()
    {
        const string text = "#EXTM3U\n#PLAYLIST:Sample List\n#EXTINF:125.4,First Clip\none.mp4\n";

        Assert.Equal("Sample List", M3uPlaylist.ReadTitle(text));

        IReadOnlyList<M3uEntry> entries = M3uPlaylist.Parse(text, new Uri(ListAddress));
        M3uEntry entry = Assert.Single(entries);
        Assert.Equal("First Clip", entry.Title);
        Assert.Equal(125, entry.DurationSeconds);
        Assert.Equal("http://media.test/one.mp4", entry.Url.AbsoluteUri);
    }

    [Fact]
    public void A_bom_crlf_and_a_missing_header_are_tolerated()
    {
        const string text = "\uFEFF#EXTINF:-1,Windows line\r\nhttp://cdn.test/a.mp4\r\n";

        IReadOnlyList<M3uEntry> entries = M3uPlaylist.Parse(text, new Uri(ListAddress));
        M3uEntry entry = Assert.Single(entries);
        Assert.Equal("Windows line", entry.Title);
        // -1 is the conventional "unknown" duration, not a negative time.
        Assert.Null(entry.DurationSeconds);
        Assert.Equal("http://cdn.test/a.mp4", entry.Url.AbsoluteUri);
    }

    [Fact]
    public void Entries_without_an_http_address_are_dropped()
    {
        const string text = "#EXTM3U\nrtmp://live.test/stream\nudp://@239.0.0.1:1234\nC:\\media\\a.mp4\nhttp://cdn.test/ok.mp4\n";

        IReadOnlyList<M3uEntry> entries = M3uPlaylist.Parse(text, new Uri(ListAddress));
        M3uEntry entry = Assert.Single(entries);
        Assert.Equal("http://cdn.test/ok.mp4", entry.Url.AbsoluteUri);
    }

    [Fact]
    public void A_relative_entry_resolves_against_the_list_address()
    {
        const string text = "#EXTM3U\nSeason 01/ep01.mp4\n";

        IReadOnlyList<M3uEntry> entries = M3uPlaylist.Parse(text, new Uri("http://media.test/shows/list.m3u"));
        Assert.Equal("http://media.test/shows/Season%2001/ep01.mp4", entries[0].Url.AbsoluteUri);
    }

    [Fact]
    public void An_hls_manifest_is_recognised_as_not_a_list()
    {
        Assert.True(M3uPlaylist.IsHls("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg0.ts\n"));
        Assert.False(M3uPlaylist.IsHls("#EXTM3U\n#EXTINF:125,Clip\none.mp4\n"));
    }

    // ---------------------------------------------------------------- browsing

    [Fact]
    public async Task A_media_address_browses_to_itself()
    {
        HttpSourceProvider provider = Provider("http://media.test/movie%20one.mp4", _ => Ok("video/mp4"));

        PagedResult<MediaItem> page = await provider.ListFolderAsync(null, new PageRequest(0, 50));

        MediaItem item = Assert.Single(page.Items);
        Assert.Equal("movie one.mp4", item.Name);
        Assert.Equal("http://media.test/movie%20one.mp4", item.Id);
        Assert.Equal(MediaType.Video, item.Type);
    }

    [Fact]
    public async Task A_list_address_browses_to_its_entries()
    {
        const string text = "#EXTM3U\n#EXTINF:125,First Clip\none.mp4\n#EXTINF:60,Second Clip\nhttp://cdn.test/two.mp4\n";
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl", text));

        PagedResult<MediaItem> page = await provider.ListFolderAsync(null, new PageRequest(0, 50));

        Assert.Equal(2, page.Items.Count);
        Assert.Equal("First Clip", page.Items[0].Name);
        Assert.Equal("http://media.test/one.mp4", page.Items[0].Id);
        Assert.Equal(125 * TimeSpan.TicksPerSecond, page.Items[0].RunTimeTicks);
        Assert.Equal("http://cdn.test/two.mp4", page.Items[1].Id);
    }

    [Fact]
    public async Task An_hls_playlist_is_one_playable_item_whose_id_is_the_playlist()
    {
        const string text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg0.ts\n";
        HttpSourceProvider provider = Provider("http://media.test/live.m3u8", _ => Ok("application/vnd.apple.mpegurl", text));

        PagedResult<MediaItem> page = await provider.ListFolderAsync(null, new PageRequest(0, 50));

        MediaItem item = Assert.Single(page.Items);
        // Not seg0.ts: the HLS demuxer needs the manifest, which is exactly what the engine can play.
        Assert.Equal("http://media.test/live.m3u8", item.Id);
    }

    [Fact]
    public async Task A_subfolder_is_not_invented()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl", "#EXTM3U\none.mp4\n"));
        var folder = new MediaItem { ServerId = "http:media.test", Id = ListAddress, Name = "list.m3u", Type = MediaType.Folder };

        PagedResult<MediaItem> page = await provider.ListFolderAsync(folder, new PageRequest(0, 50));

        Assert.Empty(page.Items);
    }

    [Fact]
    public async Task An_empty_list_is_reported_instead_of_showing_an_empty_folder()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl", "#EXTM3U\nrtmp://live.test/x\n"));

        ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
            () => provider.ListFolderAsync(null, new PageRequest(0, 50)));

        Assert.Equal(ProviderErrorCode.Connection, ex.Code);
        Assert.Contains("没有可播放", ex.Message);
    }

    [Fact]
    public async Task A_list_that_cannot_be_fetched_becomes_a_connection_error()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => new HttpResponseMessage(HttpStatusCode.NotFound));

        ProviderHttpException ex = await Assert.ThrowsAsync<ProviderHttpException>(
            () => provider.ListFolderAsync(null, new PageRequest(0, 50)));

        Assert.Equal(404, ex.StatusCode);
        Assert.Equal(ProviderErrorCode.Http, ex.Code);
    }

    // ---------------------------------------------------------------- playback

    [Fact]
    public async Task Playback_is_direct_and_keeps_the_address_and_duration()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl"));
        var item = new MediaItem
        {
            ServerId = "http:media.test",
            Id = "http://cdn.test/movie.mp4?token=abc",
            Name = "movie.mp4",
            Type = MediaType.Video,
            RunTimeTicks = 90 * TimeSpan.TicksPerSecond,
        };

        PlaybackSource source = await provider.ResolvePlaybackAsync(item, new PlaybackOptions());

        Assert.Equal("http://cdn.test/movie.mp4?token=abc", source.Url.AbsoluteUri);
        Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        Assert.Equal(item.RunTimeTicks, source.RunTimeTicks);
        Assert.Empty(source.Headers);
        Assert.Empty(provider.StreamRequestHeaders);
    }

    [Fact]
    public async Task A_non_http_item_is_refused_at_play_time()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl"));
        var item = new MediaItem { ServerId = "http:media.test", Id = "ftp://cdn.test/a.mp4", Name = "a.mp4" };

        ProviderException ex = await Assert.ThrowsAsync<ProviderException>(
            () => provider.ResolvePlaybackAsync(item, new PlaybackOptions()));

        Assert.Equal(ProviderErrorCode.Connection, ex.Code);
    }

    [Fact]
    public async Task Progress_is_local_only_so_nothing_is_reported_and_resume_is_unknown()
    {
        HttpSourceProvider provider = Provider(ListAddress, _ => Ok("audio/x-mpegurl"));

        await provider.ReportProgressAsync(new PlaybackProgress
        {
            ServerId = "http:media.test",
            ItemId = "http://cdn.test/a.mp4",
            PositionTicks = 10,
        });

        Assert.Null(await provider.GetResumePositionAsync("http://cdn.test/a.mp4"));
        Assert.Empty(await provider.GetContinueWatchingAsync(10));
    }

    // ---------------------------------------------------------------- helpers

    private static HttpSourceProvider Provider(string address, Func<HttpRequestMessage, HttpResponseMessage> script)
        => new("http:media.test", new Uri(address), new HttpClient(new ScriptedHandler(script)));

    private static HttpResponseMessage Ok(string mediaType, string? body = null)
    {
        var response = new HttpResponseMessage(HttpStatusCode.OK);
        if (body is not null)
        {
            response.Content = new StringContent(body, Encoding.UTF8);
            response.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(mediaType);
        }
        return response;
    }

    private sealed class ScriptedHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _script;

        public ScriptedHandler(Func<HttpRequestMessage, HttpResponseMessage> script) => _script = script;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
            => Task.FromResult(_script(request));
    }
}

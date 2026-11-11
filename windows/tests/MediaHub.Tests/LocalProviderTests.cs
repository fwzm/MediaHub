using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.Local;
using Xunit;

namespace MediaHub.Tests;

public sealed class LocalProviderTests : IDisposable
{
    private readonly string _root;

    public LocalProviderTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mediahub-tests-" + Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(Path.Combine(_root, "Season 01"));
        File.WriteAllBytes(Path.Combine(_root, "alpha.mp4"), new byte[16]);
        File.WriteAllBytes(Path.Combine(_root, "beta.mkv"), new byte[32]);
        File.WriteAllBytes(Path.Combine(_root, "notes.txt"), new byte[4]);
        File.WriteAllBytes(Path.Combine(_root, "alpha.srt"), new byte[2]);
        File.WriteAllBytes(Path.Combine(_root, "alpha.chi.ass"), new byte[2]);
        File.WriteAllBytes(Path.Combine(_root, "alpha.forced.srt"), new byte[2]);
        File.WriteAllBytes(Path.Combine(_root, "unrelated.srt"), new byte[2]);
        File.WriteAllBytes(Path.Combine(_root, "Season 01", "ep01.mp4"), new byte[8]);
    }

    private ProviderHandle CreateHandle()
    {
        var factory = new LocalMediaProviderFactory();
        var server = new MediaServer("local", ServerType.Local, "本地", new[] { new ServerEndpoint(_root) });
        return factory.Create(server, NetworkPolicy.FollowSystem);
    }

    [Fact]
    public async Task Folders_are_listed_before_files_and_non_media_is_excluded()
    {
        using var handle = CreateHandle();
        var page = await handle.Browse!.ListFolderAsync(null, new PageRequest(0, 50));

        Assert.Equal(MediaType.Folder, page.Items[0].Type);
        Assert.Equal("Season 01", page.Items[0].Name);
        Assert.DoesNotContain(page.Items, i => i.Name == "notes");
        Assert.DoesNotContain(page.Items, i => i.Name == "alpha.srt" || i.Name == "alpha.chi");
        Assert.Contains(page.Items, i => i.Name == "alpha");
        Assert.Contains(page.Items, i => i.Name == "beta");
    }

    [Fact]
    public async Task Total_count_reflects_the_whole_folder_not_the_page()
    {
        using var handle = CreateHandle();
        var page = await handle.Browse!.ListFolderAsync(null, new PageRequest(0, 1));
        Assert.Single(page.Items);
        Assert.Equal(3, page.TotalCount);
        Assert.True(page.HasMore);
    }

    [Fact]
    public async Task Paging_walks_the_whole_folder_without_repeats()
    {
        using var handle = CreateHandle();
        var seen = new List<string>();
        for (int offset = 0; offset < 10; offset += 2)
        {
            var page = await handle.Browse!.ListFolderAsync(null, new PageRequest(offset, 2));
            if (page.Items.Count == 0) break;
            seen.AddRange(page.Items.Select(i => i.Name));
        }
        Assert.Equal(3, seen.Distinct().Count());
    }

    [Fact]
    public async Task Missing_folder_is_reported_as_not_found_rather_than_thrown_raw()
    {
        using var handle = CreateHandle();
        var missing = new MediaItem { ServerId = "local", Id = "x", Name = "x", Path = Path.Combine(_root, "nope") };
        var ex = await Assert.ThrowsAsync<ProviderException>(() => handle.Browse!.ListFolderAsync(missing, PageRequest.First()));
        Assert.Equal(ProviderErrorCode.NotFound, ex.Code);
    }

    [Fact]
    public async Task Sibling_subtitles_are_discovered_by_the_naming_convention()
    {
        using var handle = CreateHandle();
        string video = Path.Combine(_root, "alpha.mp4");
        var subs = await handle.Subtitle!.GetSubtitlesAsync(video);

        var names = subs.Select(s => s.Title).ToList();
        Assert.Contains("alpha.srt", names);
        Assert.Contains("alpha.chi.ass", names);
        Assert.Contains("alpha.forced.srt", names);
        Assert.DoesNotContain("unrelated.srt", names);
        Assert.All(subs, s => Assert.True(s.IsExternal));
    }

    [Fact]
    public async Task Subtitle_language_is_read_from_the_filename_suffix()
    {
        using var handle = CreateHandle();
        var subs = await handle.Subtitle!.GetSubtitlesAsync(Path.Combine(_root, "alpha.mp4"));
        var chinese = subs.Single(s => s.Title == "alpha.chi.ass");
        Assert.Equal("chi", chinese.Language);
    }

    [Fact]
    public async Task Forced_subtitles_are_flagged()
    {
        using var handle = CreateHandle();
        var subs = await handle.Subtitle!.GetSubtitlesAsync(Path.Combine(_root, "alpha.mp4"));
        Assert.True(subs.Single(s => s.Title == "alpha.forced.srt").IsForced);
    }

    [Fact]
    public async Task Resolving_a_local_file_produces_a_direct_play_source_with_no_headers()
    {
        using var handle = CreateHandle();
        string video = Path.Combine(_root, "beta.mkv");
        var item = new MediaItem { ServerId = "local", Id = video, Name = "beta", Path = video };

        var source = await handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions());
        Assert.Equal(PlaybackMode.DirectPlay, source.Mode);
        Assert.Empty(source.Headers);
        Assert.True(source.Url.IsFile);
    }

    [Fact]
    public async Task Resolving_a_missing_file_reports_not_found()
    {
        using var handle = CreateHandle();
        var item = new MediaItem { ServerId = "local", Id = "gone", Name = "gone", Path = Path.Combine(_root, "gone.mkv") };
        var ex = await Assert.ThrowsAsync<ProviderException>(() => handle.Playback!.ResolvePlaybackAsync(item, new PlaybackOptions()));
        Assert.Equal(ProviderErrorCode.NotFound, ex.Code);
    }

    [Theory]
    [InlineData(@"\\server\share\movie.mkv", true)]
    [InlineData(@"D:\media\movie.mkv", false)]
    public void Unc_paths_are_detected_as_remote(string path, bool expected)
        => Assert.Equal(expected, LocalMediaProviderFactory.IsLikelyRemote(path));

    [Fact]
    public void Unc_paths_are_classified_as_unc()
        => Assert.Equal(StorageKind.Unc, LocalMediaProviderFactory.Classify(@"\\nas\media\film.mkv"));

    [Fact]
    public void Unknown_paths_do_not_throw()
        => Assert.Equal(StorageKind.Unknown, LocalMediaProviderFactory.Classify(""));

    [Theory]
    // Input is the file name with the subtitle extension already removed, matching the call site.
    [InlineData("alpha.chi", "alpha", "chi")]
    [InlineData("alpha.eng.forced", "alpha", "eng")]
    [InlineData("alpha", "alpha", null)]
    [InlineData("alpha_zh-Hans", "alpha", "zh-Hans")]
    [InlineData("alpha.forced", "alpha", null)]
    [InlineData("alpha.sdh", "alpha", null)]
    public void Language_suffix_parsing(string fileNameWithoutExtension, string stem, string? expected)
        => Assert.Equal(expected, LocalMediaProvider.LanguageFromSuffix(fileNameWithoutExtension, stem));

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }
}

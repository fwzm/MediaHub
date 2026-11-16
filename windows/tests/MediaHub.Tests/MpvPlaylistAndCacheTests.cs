using System.Net;
using MediaHub.Player;
using MediaHub.Player.Mpv;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Engine-level evidence for the two player features that are otherwise only visible as behaviour:
/// the appended "next episode" entry and the on-disk packet cache.
/// </summary>
/// <remarks>
/// Both run the real libmpv with <c>vo=null</c>/<c>ao=null</c> and no window, so they neither draw
/// anything nor take focus. Every assertion reads state back from the engine rather than trusting
/// that a void API call did what it was told; a misspelled option name is silently dropped by mpv,
/// which is exactly the failure mode these tests must not hide.
/// </remarks>
public sealed class MpvPlaylistAndCacheTests
{
    private static string FixturePath =>
        Path.Combine(AppContext.BaseDirectory, "assets", "sample-av.mp4");

    private static MpvPlayerOptions Headless() => new()
    {
        Wid = IntPtr.Zero,
        VideoOutput = "null",
        AudioOutput = "null",
        HwDec = "no",
    };

    private static async Task WaitUntil(Func<bool> condition, int attempts = 60)
    {
        for (int i = 0; i < attempts && !condition(); i++) await Task.Delay(200);
    }

    [Fact]
    public async Task An_appended_entry_is_prefetched_and_playlist_next_switches_to_it()
    {
        using var player = new MpvPlayer(Headless());

        // mpv 0.41 ships prefetch-playlist=no; the append path must turn it on, otherwise the
        // appended entry is opened only when it becomes current — the wait preloading exists to remove.
        Assert.Equal("no", player.GetOptionValue("prefetch-playlist"));

        await player.LoadAsync(FixturePath, new PlaybackSettings { Volume = 0 });
        await WaitUntil(() => player.Snapshot.DurationSeconds > 0);
        Assert.True(player.Snapshot.DurationSeconds > 0, "the fixture must reach a playable state first");
        Assert.Equal(1, player.PlaylistCount);

        player.AppendToPlaylist(FixturePath);
        Assert.Equal("yes", player.GetOptionValue("prefetch-playlist"));
        await WaitUntil(() => player.PlaylistCount == 2);
        Assert.Equal(2, player.PlaylistCount);
        Assert.Equal("0", player.GetProperty("playlist-pos"));

        player.PlaylistNext(new PlaybackSettings { Volume = 0 });
        await WaitUntil(() => player.GetProperty("playlist-pos") == "1" && player.Snapshot.DurationSeconds > 0);

        Assert.Equal("1", player.GetProperty("playlist-pos"));
        Assert.True(player.Snapshot.DurationSeconds > 0, "the appended entry must become playable");
        Console.WriteLine(
            "ENGINE: prefetch-playlist=" + player.GetOptionValue("prefetch-playlist")
            + " playlist-count=" + player.PlaylistCount
            + " playlist-pos=" + player.GetProperty("playlist-pos")
            + " duration=" + player.Snapshot.DurationSeconds.ToString("0.00") + "s");
    }

    [Fact]
    public async Task Cache_options_reach_the_engine_and_land_in_the_configured_directory()
    {
        string cacheDir = Path.Combine(Path.GetTempPath(), "mediahub-mpv-cache-" + Guid.NewGuid().ToString("N"));
        string logPath = Path.Combine(Path.GetTempPath(), "mediahub-mpv-cache-" + Guid.NewGuid().ToString("N") + ".log");
        // The engine opens its cache files in this directory but does not create it; the app's
        // PlayerCacheStore.DirectoryPath is what makes it exist in production.
        Directory.CreateDirectory(cacheDir);
        try
        {
            byte[] media = await File.ReadAllBytesAsync(FixturePath);
            await using var origin = await BridgeTestOrigin.StartAsync();
            // The fixture is 27 KiB, so an unthrottled origin is read to completion before the cache
            // can be observed. Holding the response open keeps the engine streaming long enough for
            // the on-disk packet cache to exist while the test reads the directory.
            origin.Map("/media", async ctx =>
            {
                ctx.Response.ContentType = "video/mp4";
                // Chunked, no Length and no Accept-Ranges: an unseekable stream, which is the case the
                // on-disk cache exists for. A range-capable response is seekable and mpv keeps it in
                // memory only.
                ctx.Response.SendChunked = true;
                await ctx.Response.OutputStream.WriteAsync(media.AsMemory(0, 2048));
                await ctx.Response.OutputStream.FlushAsync();
                await Task.Delay(4000);
                try
                {
                    await ctx.Response.OutputStream.WriteAsync(media.AsMemory(2048));
                    ctx.Response.Close();
                }
                catch (IOException) { /* the engine closed the stream when it had enough */ }
                catch (HttpListenerException) { }
            });

            var player = new MpvPlayer(new MpvPlayerOptions
            {
                Wid = IntPtr.Zero,
                VideoOutput = "null",
                AudioOutput = "null",
                HwDec = "no",
                LogFile = logPath,
            });
            try
            {
                await player.LoadAsync(origin.BaseUri + "media", new PlaybackSettings
                {
                    Volume = 0,
                    // The options PlayerHostService builds for every load, applied at runtime so a
                    // capacity change takes effect on the next item instead of on restart.
                    EngineOptions = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                    {
                        ["demuxer-cache-dir"] = cacheDir,
                        ["cache-on-disk"] = "yes",
                        ["demuxer-cache-unlink-files"] = "whendone",
                        ["demuxer-max-bytes"] = "8192",
                        ["demuxer-max-back-bytes"] = "8192",
                    },
                });
                await WaitUntil(() => player.Snapshot.DurationSeconds > 0);
                Assert.True(player.Snapshot.DurationSeconds > 0, "the streamed fixture must load");

                // An unknown option name would leave these reads empty; that is the silent failure
                // this assertion exists to catch.
                Assert.Empty(player.OptionErrors);
                Assert.Equal(cacheDir, player.GetOptionValue("demuxer-cache-dir"));
                Assert.Equal("yes", player.GetOptionValue("cache-on-disk"));
                Assert.Equal("whendone", player.GetOptionValue("demuxer-cache-unlink-files"));

                var store = new PlayerCacheStore(cacheDir);
                string[] files = Array.Empty<string>();
                for (int i = 0; i < 25 && files.Length == 0; i++)
                {
                    await Task.Delay(200);
                    files = Directory.Exists(cacheDir)
                        ? Directory.GetFiles(cacheDir, "*", SearchOption.AllDirectories)
                        : Array.Empty<string>();
                }

                long bytes = store.GetUsageBytes();
                Console.WriteLine("ENGINE: demuxer-cache-dir=" + player.GetOptionValue("demuxer-cache-dir")
                    + " cache-on-disk=" + player.GetOptionValue("cache-on-disk")
                    + " demuxer-cache-unlink-files=" + player.GetOptionValue("demuxer-cache-unlink-files")
                    + " demuxer-max-bytes=" + player.GetOptionValue("demuxer-max-bytes")
                    + " files=" + files.Length + " bytes=" + bytes);
                foreach (string file in files)
                {
                    Console.WriteLine("CACHE FILE: " + file + " (" + new FileInfo(file).Length + " bytes)");
                }

                // The settings page reports this directory and can delete it; both are only meaningful
                // if the engine really writes there. A bogus option name would leave it empty.
                Assert.NotEmpty(files);
                Assert.All(files, f => Assert.StartsWith(cacheDir, f, StringComparison.OrdinalIgnoreCase));

                // Closing the engine is what "playback end" means for the cache; whendone must leave
                // nothing behind, which is what the settings page's usage figure will then show.
                await player.DisposeAsync();
                Assert.Equal(0, store.GetUsageBytes());
                Console.WriteLine("CACHE AFTER PLAYBACK END: " + store.GetUsageBytes() + " bytes");
            }
            finally
            {
                player.Dispose();
            }
        }
        finally
        {
            try { Directory.Delete(cacheDir, recursive: true); } catch { }
            try { File.Delete(logPath); } catch { }
        }
    }
}

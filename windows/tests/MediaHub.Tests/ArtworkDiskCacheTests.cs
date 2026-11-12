using MediaHub.Storage;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Covers the disk half of the artwork cache: the size cap, the entry TTL and the accounting the
/// cap relies on. These rules used to live inside the XAML image loader, where they could only be
/// observed by looking at a running window.
/// </summary>
public sealed class ArtworkDiskCacheTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        "mediahub-artwork-cache-tests",
        Guid.NewGuid().ToString("N"));

    private DateTimeOffset _now = DateTimeOffset.UtcNow;

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    [Fact]
    public void Writing_then_reading_returns_the_same_bytes()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024);

        cache.Write("poster", new byte[] { 1, 2, 3, 4 });

        Assert.Equal(new byte[] { 1, 2, 3, 4 }, cache.TryRead("poster"));
        Assert.Equal(4, cache.SizeBytes);
        Assert.True(cache.Contains("poster"));
    }

    [Fact]
    public void An_entry_older_than_the_ttl_is_not_returned_and_is_deleted()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024, entryTtl: TimeSpan.FromDays(30), clock: () => _now);
        cache.Write("poster", new byte[] { 1, 2, 3, 4 });

        _now = _now.AddDays(31);

        Assert.Null(cache.TryRead("poster"));
        Assert.False(cache.Contains("poster"));
    }

    [Fact]
    public void Reading_an_expired_entry_releases_its_bytes()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024, entryTtl: TimeSpan.FromDays(30), clock: () => _now);
        cache.Write("poster", new byte[] { 1, 2, 3, 4 });

        _now = _now.AddDays(31);
        Assert.Null(cache.TryRead("poster"));

        Assert.Equal(0, cache.SizeBytes);
    }

    [Fact]
    public void Contains_treats_an_expired_entry_as_absent()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024, entryTtl: TimeSpan.FromDays(30), clock: () => _now);
        cache.Write("poster", new byte[] { 1, 2, 3, 4 });

        _now = _now.AddDays(31);

        Assert.False(cache.Contains("poster"));
        Assert.Equal(0, cache.SizeBytes);
    }

    [Fact]
    public void Clear_resets_the_tracked_size_to_zero()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024);
        cache.Write("poster", new byte[] { 1, 2, 3, 4 });

        cache.Clear();

        Assert.Equal(0, cache.SizeBytes);
        Assert.False(cache.Contains("poster"));
    }

    [Fact]
    public void Clear_removes_orphan_temp_files()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024);
        string orphan = Path.Combine(_root, "orphan.tmp");
        File.WriteAllBytes(orphan, new byte[] { 1 });

        cache.Clear();

        Assert.False(File.Exists(orphan));
    }

    [Fact]
    public void Rewriting_a_key_does_not_inflate_the_tracked_size()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024);

        cache.Write("poster", new byte[] { 1, 2, 3, 4 });
        cache.Write("poster", new byte[] { 5, 6, 7, 8 });

        Assert.Equal(4, cache.SizeBytes);
        Assert.Equal(new byte[] { 5, 6, 7, 8 }, cache.TryRead("poster"));
    }

    [Fact]
    public void Trim_removes_orphan_temp_files()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 1024);
        string orphan = Path.Combine(_root, "orphan.tmp");
        File.WriteAllBytes(orphan, new byte[] { 1 });

        cache.Trim();

        Assert.False(File.Exists(orphan));
    }

    [Fact]
    public void Writing_when_over_the_cap_evicts_the_oldest_entry()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 8, entryTtl: TimeSpan.FromDays(30), clock: () => _now);

        cache.Write("oldest", new byte[] { 1, 2, 3, 4 });
        File.SetLastWriteTimeUtc(cache.PathFor("oldest"), _now.UtcDateTime.AddMinutes(-3));

        cache.Write("middle", new byte[] { 1, 2, 3, 4 });
        File.SetLastWriteTimeUtc(cache.PathFor("middle"), _now.UtcDateTime.AddMinutes(-2));

        cache.Write("newest", new byte[] { 1, 2, 3, 4 });

        Assert.False(cache.Contains("oldest"));
        Assert.True(cache.Contains("middle"));
        Assert.True(cache.Contains("newest"));
        Assert.Equal(8, cache.SizeBytes);
    }

    [Fact]
    public void An_entry_larger_than_the_whole_cap_is_not_kept()
    {
        var cache = new ArtworkDiskCache(_root, maxBytes: 8);

        cache.Write("huge", new byte[9]);

        Assert.False(cache.Contains("huge"));
        Assert.Equal(0, cache.SizeBytes);
    }
}

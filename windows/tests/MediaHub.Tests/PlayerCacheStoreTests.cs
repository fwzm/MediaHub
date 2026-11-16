using MediaHub.Player;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The engine cache directory is plain files, so the size shown in settings and the cleanup button
/// are only trustworthy if this bookkeeping is exact.
/// </summary>
public sealed class PlayerCacheStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "mediahub-cache-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private string Write(string name, int bytes, DateTime? lastWrite = null)
    {
        Directory.CreateDirectory(_root);
        string path = Path.Combine(_root, name);
        File.WriteAllBytes(path, new byte[bytes]);
        if (lastWrite is { } stamp) File.SetLastWriteTimeUtc(path, stamp);
        return path;
    }

    [Fact]
    public void Usage_sums_every_file_and_is_zero_for_a_missing_directory()
    {
        var store = new PlayerCacheStore(_root);
        Assert.Equal(0, store.GetUsageBytes());

        Write("a.part", 1000);
        Write("b.part", 2500);

        Assert.Equal(3500, store.GetUsageBytes());
    }

    [Fact]
    public void Clear_deletes_files_and_reports_the_bytes_removed()
    {
        var store = new PlayerCacheStore(_root);
        Write("a.part", 1000);
        Write("b.part", 2500);

        long removed = store.Clear();

        Assert.Equal(3500, removed);
        Assert.Equal(0, store.GetUsageBytes());
        Assert.Empty(Directory.EnumerateFiles(_root));
    }

    [Fact]
    public void EnforceCapacity_removes_the_oldest_files_until_the_budget_fits()
    {
        var store = new PlayerCacheStore(_root);
        DateTime now = DateTime.UtcNow;
        Write("oldest.part", 4000, now.AddMinutes(-30));
        Write("middle.part", 4000, now.AddMinutes(-20));
        string newest = Write("newest.part", 4000, now);

        long removed = store.EnforceCapacity(5000);

        // 3 x 4000 = 12000 bytes over a 5000 byte cap: one deletion still leaves 8000 in the
        // directory, so the policy has to remove two entries before the total fits.
        Assert.Equal(8000, removed);
        Assert.Equal(4000, store.GetUsageBytes());
        Assert.True(File.Exists(newest));
        Assert.False(File.Exists(Path.Combine(_root, "oldest.part")));
        Assert.False(File.Exists(Path.Combine(_root, "middle.part")));
    }

    [Fact]
    public void EnforceCapacity_leaves_a_directory_that_already_fits_untouched()
    {
        var store = new PlayerCacheStore(_root);
        Write("a.part", 1000);
        Write("b.part", 2000);

        Assert.Equal(0, store.EnforceCapacity(10_000));
        Assert.Equal(3000, store.GetUsageBytes());
    }
}

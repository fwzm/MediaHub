namespace MediaHub.Player;

/// <summary>
/// Owns the directory the engine writes its on-disk packet cache into.
/// </summary>
/// <remarks>
/// mpv does not cap the directory itself: <c>cache-dir</c> only decides where the files go, while the
/// byte budget applies to memory. Without an owner the folder grows until the user finds it. This
/// type is deliberately independent of the engine so the numbers shown in settings can be read and
/// the folder emptied while no playback is running.
/// </remarks>
public sealed class PlayerCacheStore
{
    private readonly Func<string> _directoryProvider;

    public PlayerCacheStore(string? directory = null)
    {
        _directoryProvider = directory is null ? () => DefaultDirectory : () => directory;
    }

    /// <summary>Cache root under the per-user local app data, next to logs but separate from them.</summary>
    public static string DefaultDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "MediaHub", "cache", "mpv");

    public string DirectoryPath
    {
        get
        {
            string path = _directoryProvider();
            Directory.CreateDirectory(path);
            return path;
        }
    }

    /// <summary>Bytes currently held on disk. Unreadable files contribute zero instead of failing.</summary>
    public long GetUsageBytes()
    {
        try
        {
            string path = _directoryProvider();
            if (!Directory.Exists(path)) return 0;
            long total = 0;
            foreach (string file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            {
                try { total += new FileInfo(file).Length; }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
            return total;
        }
        catch (IOException) { return 0; }
        catch (UnauthorizedAccessException) { return 0; }
    }

    /// <summary>
    /// Deletes every cache file the process is allowed to delete and returns the bytes removed.
    /// Files still held open by a running engine are skipped, so this is safe to expose as a button.
    /// </summary>
    public long Clear()
    {
        string path;
        try
        {
            path = _directoryProvider();
            if (!Directory.Exists(path)) return 0;
        }
        catch (IOException) { return 0; }
        catch (UnauthorizedAccessException) { return 0; }

        long removed = 0;
        foreach (string file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
        {
            try
            {
                long size = new FileInfo(file).Length;
                File.Delete(file);
                removed += size;
            }
            catch (IOException) { /* still open by the engine */ }
            catch (UnauthorizedAccessException) { }
        }
        return removed;
    }

    /// <summary>
    /// Enforces the configured capacity by deleting the oldest files first. Returns bytes removed.
    /// </summary>
    public long EnforceCapacity(long maxBytes)
    {
        if (maxBytes <= 0)
        {
            return Clear();
        }

        try
        {
            string path = _directoryProvider();
            if (!Directory.Exists(path)) return 0;

            var files = new List<(string Path, long Size, DateTime LastWrite)>();
            foreach (string file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            {
                try
                {
                    var info = new FileInfo(file);
                    files.Add((file, info.Length, info.LastWriteTimeUtc));
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }

            long total = files.Sum(f => f.Size);
            if (total <= maxBytes) return 0;

            long removed = 0;
            foreach (var entry in files.OrderBy(f => f.LastWrite))
            {
                if (total <= maxBytes) break;
                try
                {
                    File.Delete(entry.Path);
                    total -= entry.Size;
                    removed += entry.Size;
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
            return removed;
        }
        catch (IOException) { return 0; }
        catch (UnauthorizedAccessException) { return 0; }
    }
}

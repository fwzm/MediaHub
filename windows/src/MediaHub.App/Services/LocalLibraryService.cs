using MediaHub.Domain;

namespace MediaHub.App.Services;

/// <summary>
/// Enumerates video files under the user's configured local folders.
/// </summary>
/// <remarks>
/// Scanning is streaming and cancellable so a large library never blocks the UI thread, and results
/// are emitted incrementally. Only extensions the bundled engine can actually demux are listed;
/// claiming support for a container we cannot open would be worse than omitting it.
/// </remarks>
public sealed class LocalLibraryService : IDisposable
{
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg",
        ".ts", ".m2ts", ".mts", ".vob", ".rmvb", ".rm", ".3gp", ".ogv", ".divx", ".asf", ".iso",
    };

    private static readonly HashSet<string> SubtitleExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".srt", ".ass", ".ssa", ".sub", ".idx", ".vtt", ".smi", ".sup",
    };

    public static IReadOnlyCollection<string> SupportedVideoExtensions => VideoExtensions;
    public static IReadOnlyCollection<string> SupportedSubtitleExtensions => SubtitleExtensions;

    private readonly SettingsStore _settings;
    private readonly List<LocalMediaEntry> _items = new();
    private CancellationTokenSource? _scanCts;

    public LocalLibraryService(SettingsStore settings) => _settings = settings;

    public IReadOnlyList<LocalMediaEntry> Items
    {
        get { lock (_items) return _items.ToList(); }
    }

    public event EventHandler<LocalMediaEntry>? ItemDiscovered;
    public event EventHandler? ScanCompleted;

    public bool IsScanning { get; private set; }

    public void CancelScan()
    {
        _scanCts?.Cancel();
    }

    /// <summary>Rescans every configured folder. Safe to call repeatedly; each call supersedes the last.</summary>
    public async Task ScanAsync(CancellationToken ct = default)
    {
        CancelScan();
        var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _scanCts = cts;
        var token = cts.Token;

        var folders = _settings.Current.LocalFolders.ToList();
        bool recursive = _settings.Current.RecursiveScan;

        lock (_items) _items.Clear();
        IsScanning = true;
        try
        {
            foreach (string folder in folders)
            {
                token.ThrowIfCancellationRequested();
                if (!Directory.Exists(folder)) continue;

                await Task.Run(() =>
                {
                    foreach (string file in EnumerateVideos(folder, recursive, token))
                    {
                        token.ThrowIfCancellationRequested();
                        var entry = LocalMediaEntry.FromPath(file);
                        lock (_items) _items.Add(entry);
                        ItemDiscovered?.Invoke(this, entry);
                    }
                }, token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Superseded by a newer scan; partial results stay visible.
        }
        finally
        {
            IsScanning = false;
            ScanCompleted?.Invoke(this, EventArgs.Empty);
        }
    }

    private static IEnumerable<string> EnumerateVideos(string root, bool recursive, CancellationToken ct)
    {
        var options = new EnumerationOptions
        {
            RecurseSubdirectories = recursive,
            IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.System | FileAttributes.Temporary,
        };

        foreach (string file in Directory.EnumerateFiles(root, "*", options))
        {
            ct.ThrowIfCancellationRequested();
            if (VideoExtensions.Contains(Path.GetExtension(file))) yield return file;
        }
    }

    /// <summary>Finds sibling subtitle files for a video, matching the common naming conventions.</summary>
    public static IReadOnlyList<string> FindSiblingSubtitles(string videoPath)
    {
        try
        {
            string? dir = Path.GetDirectoryName(videoPath);
            if (dir is null || !Directory.Exists(dir)) return Array.Empty<string>();

            string stem = Path.GetFileNameWithoutExtension(videoPath);
            return Directory.EnumerateFiles(dir)
                .Where(f => SubtitleExtensions.Contains(Path.GetExtension(f)))
                .Where(f =>
                {
                    string name = Path.GetFileNameWithoutExtension(f);
                    return name.Equals(stem, StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith(stem + ".", StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith(stem + "_", StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith(stem + "-", StringComparison.OrdinalIgnoreCase);
                })
                .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
                .ToList();
        }
        catch
        {
            return Array.Empty<string>();
        }
    }

    public void Dispose() => CancelScan();
}

public sealed record LocalMediaEntry
{
    public required string Path { get; init; }
    public required string Name { get; init; }
    public required string FileName { get; init; }
    public long SizeBytes { get; init; }
    public DateTime ModifiedUtc { get; init; }
    public MediaType Type { get; init; } = MediaType.Video;

    public string SizeDisplay => SizeBytes switch
    {
        >= 1L << 30 => $"{SizeBytes / (double)(1L << 30):0.00} GB",
        >= 1L << 20 => $"{SizeBytes / (double)(1L << 20):0.0} MB",
        >= 1L << 10 => $"{SizeBytes / 1024.0:0} KB",
        _ => $"{SizeBytes} B",
    };

    public static LocalMediaEntry FromPath(string path)
    {
        var info = new FileInfo(path);
        return new LocalMediaEntry
        {
            Path = path,
            Name = System.IO.Path.GetFileNameWithoutExtension(path),
            FileName = info.Name,
            SizeBytes = info.Exists ? info.Length : 0,
            ModifiedUtc = info.Exists ? info.LastWriteTimeUtc : DateTime.MinValue,
        };
    }
}

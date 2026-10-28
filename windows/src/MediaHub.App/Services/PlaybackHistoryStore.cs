using System.Text.Json;

namespace MediaHub.App.Services;

public sealed record PlaybackHistoryEntry
{
    public required string MediaPath { get; init; }
    public required string Title { get; init; }
    public long PositionTicks { get; init; }
    public long DurationTicks { get; init; }
    public DateTime LastPlayedUtc { get; init; } = DateTime.UtcNow;

    public double ProgressFraction => DurationTicks > 0 ? Math.Clamp((double)PositionTicks / DurationTicks, 0, 1) : 0;
    public bool IsFinished => ProgressFraction >= 0.95;
    public TimeSpan Position => TimeSpan.FromTicks(PositionTicks);
    public TimeSpan Duration => TimeSpan.FromTicks(DurationTicks);
}

/// <summary>
/// Local watch-position store backing "Continue watching" (ADR-003 carried over: only resource
/// identity and position are persisted — never a resolved playback URL).
/// </summary>
public sealed class PlaybackHistoryStore : IDisposable
{
    private static readonly string Directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub");
    private static readonly string Path_ = Path.Combine(Directory, "history.json");
    private static readonly TimeSpan FlushInterval = TimeSpan.FromSeconds(5);

    private readonly object _gate = new();
    private readonly Dictionary<string, PlaybackHistoryEntry> _entries = new(StringComparer.OrdinalIgnoreCase);
    private readonly Timer _flushTimer;
    private bool _dirty;

    public PlaybackHistoryStore()
    {
        Load();
        _flushTimer = new Timer(_ => FlushIfDirty(), null, FlushInterval, FlushInterval);
    }

    public event EventHandler? Changed;

    public IReadOnlyList<PlaybackHistoryEntry> ContinueWatching(int limit = 20)
    {
        lock (_gate)
        {
            return _entries.Values
                .Where(e => !e.IsFinished && e.PositionTicks > TimeSpan.TicksPerSecond * 15)
                .OrderByDescending(e => e.LastPlayedUtc)
                .Take(limit)
                .ToList();
        }
    }

    public PlaybackHistoryEntry? Find(string mediaPath)
    {
        lock (_gate) return _entries.TryGetValue(mediaPath, out var e) ? e : null;
    }

    /// <summary>Records progress. Called on the local sampling tier (ADR-017).</summary>
    public void Record(string mediaPath, string title, long positionTicks, long durationTicks)
    {
        if (string.IsNullOrWhiteSpace(mediaPath)) return;
        lock (_gate)
        {
            _entries[mediaPath] = new PlaybackHistoryEntry
            {
                MediaPath = mediaPath,
                Title = title,
                PositionTicks = Math.Max(0, positionTicks),
                DurationTicks = Math.Max(0, durationTicks),
                LastPlayedUtc = DateTime.UtcNow,
            };
            _dirty = true;
        }
    }

    public void Remove(string mediaPath)
    {
        lock (_gate)
        {
            if (_entries.Remove(mediaPath)) _dirty = true;
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void FlushIfDirty()
    {
        lock (_gate)
        {
            if (!_dirty) return;
            _dirty = false;
            try
            {
                System.IO.Directory.CreateDirectory(Directory);
                string tmp = Path_ + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(_entries.Values.ToList()));
                File.Move(tmp, Path_, overwrite: true);
            }
            catch
            {
                _dirty = true; // retry on the next tick
            }
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(Path_)) return;
            var list = JsonSerializer.Deserialize<List<PlaybackHistoryEntry>>(File.ReadAllText(Path_));
            if (list is null) return;
            lock (_gate)
            {
                foreach (var e in list)
                {
                    if (!string.IsNullOrWhiteSpace(e.MediaPath)) _entries[e.MediaPath] = e;
                }
            }
        }
        catch
        {
            // Corrupt history degrades to "no resume data" rather than blocking startup.
        }
    }

    public void Dispose()
    {
        _flushTimer.Dispose();
        FlushIfDirty();
    }
}

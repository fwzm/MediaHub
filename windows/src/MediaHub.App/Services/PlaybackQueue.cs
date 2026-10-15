namespace MediaHub.App.Services;

/// <summary>
/// A temporary playback queue, created by opening or dropping several files at once.
/// </summary>
/// <remarks>
/// Deliberately not a saved playlist. This is the "I selected twelve files and pressed Enter" case,
/// and it exists only for the lifetime of the session: persisting it would create a second, competing
/// notion of a playlist that the library pages would then have to reconcile with. Order is the order
/// the shell handed the files over, which is the order the user saw in Explorer.
/// </remarks>
public sealed class PlaybackQueue
{
    private readonly List<PlaybackRequest> _items = new();
    private int _index = -1;

    public IReadOnlyList<PlaybackRequest> Items => _items;
    public int Count => _items.Count;
    public int Index => _index;
    public bool HasNext => _index >= 0 && _index + 1 < _items.Count;
    public bool HasPrevious => _index > 0;
    public PlaybackRequest? Current => _index >= 0 && _index < _items.Count ? _items[_index] : null;

    /// <summary>Replaces the queue and selects the first entry.</summary>
    public PlaybackRequest? Reset(IEnumerable<PlaybackRequest> requests)
    {
        _items.Clear();
        _items.AddRange(requests);
        _index = _items.Count > 0 ? 0 : -1;
        return Current;
    }

    /// <summary>Advances to the next entry, or null at the end.</summary>
    public PlaybackRequest? Next()
    {
        if (!HasNext) return null;
        _index++;
        return Current;
    }

    /// <summary>Moves back one entry, or null at the start.</summary>
    public PlaybackRequest? Previous()
    {
        if (!HasPrevious) return null;
        _index--;
        return Current;
    }

    /// <summary>Points the queue at an entry that was started from somewhere else.</summary>
    public void Select(string? localPath, string? serverId, string? itemId)
    {
        _index = _items.FindIndex(i =>
            (localPath is not null && string.Equals(i.LocalPath, localPath, StringComparison.OrdinalIgnoreCase))
            || (itemId is not null && i.ItemId == itemId && i.ServerId == serverId));
    }

    public void Clear()
    {
        _items.Clear();
        _index = -1;
    }
}

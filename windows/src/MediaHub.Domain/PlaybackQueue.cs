namespace MediaHub.Domain;

/// <summary>How a set of requests enters the temporary queue.</summary>
public enum QueueIntake
{
    /// <summary>Discard what was queued and make the first new entry current.</summary>
    Replace,

    /// <summary>Add to what is queued, leaving the current entry playing.</summary>
    Append,
}

/// <summary>What an intake actually did, so the UI can report it instead of guessing.</summary>
public readonly record struct QueueIntakeResult(QueueIntake Mode, int Requested, int Added, int Duplicates)
{
    public bool NothingAdded => Added == 0;
}

/// <summary>
/// The temporary playback queue: "I selected twelve files and pressed play".
/// </summary>
/// <remarks>
/// <para>
/// Deliberately not a saved playlist. Persisting it would create a second, competing notion of a
/// playlist that the library pages would then have to reconcile with, and the task it serves - play
/// these files, in this order, now - ends with the session.
/// </para>
/// <para>
/// Order is the order the shell handed the files over, which is the order the user saw in Explorer,
/// and no sorting is applied anywhere: reordering what the user selected would make "next" mean
/// something they never asked for.
/// </para>
/// <para>
/// Duplicates are dropped by identity rather than by reference, so the same file picked twice (or a
/// folder dropped on top of an already-queued file) does not play twice. The first occurrence wins,
/// which keeps the earlier position.
/// </para>
/// </remarks>
public sealed class PlaybackQueue
{
    private readonly List<PlaybackRequest> _items = new();
    private int _index = -1;

    /// <summary>Every entry, in play order, as an immutable snapshot of the list.</summary>
    public IReadOnlyList<PlaybackRequest> Items => _items;

    public int Count => _items.Count;

    /// <summary>Index of the current entry, or -1 when the queue is empty or nothing is current.</summary>
    public int Index => _index;

    public PlaybackRequest? Current => _index >= 0 && _index < _items.Count ? _items[_index] : null;

    public PlaybackRequest? NextItem => _index >= 0 && _index + 1 < _items.Count ? _items[_index + 1] : null;

    public PlaybackRequest? PreviousItem => _index > 0 && _index <= _items.Count ? _items[_index - 1] : null;

    public bool HasNext => NextItem is not null;

    public bool HasPrevious => PreviousItem is not null;

    /// <summary>Incremented by every mutation, so a view can skip work when nothing changed.</summary>
    public long Revision { get; private set; }

    /// <summary>
    /// Adds requests under the given policy and reports what happened.
    /// </summary>
    public QueueIntakeResult Intake(IReadOnlyList<PlaybackRequest> requests, QueueIntake mode)
    {
        ArgumentNullException.ThrowIfNull(requests);

        if (mode == QueueIntake.Replace)
        {
            _items.Clear();
            _index = -1;
        }

        int added = 0;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (PlaybackRequest request in requests)
        {
            ArgumentNullException.ThrowIfNull(request);
            string identity = IdentityOf(request);
            if (!seen.Add(identity)) continue;
            if (_items.Any(existing => IdentityOf(existing) == identity)) continue;
            _items.Add(request);
            added++;
        }

        if (mode == QueueIntake.Replace || _index < 0)
        {
            _index = _items.Count > 0 ? 0 : -1;
        }

        if (added > 0) Revision++;
        return new QueueIntakeResult(mode, requests.Count, added, requests.Count - added);
    }

    /// <summary>Steps forward or back by one, or returns null at either end without wrapping.</summary>
    public PlaybackRequest? MoveBy(int delta)
    {
        if (delta == 0) return Current;
        int target = _index + delta;
        if (target < 0 || target >= _items.Count) return null;
        _index = target;
        return Current;
    }

    /// <summary>Makes the entry at <paramref name="index"/> current.</summary>
    public PlaybackRequest? SelectAt(int index)
    {
        if (index < 0 || index >= _items.Count) return null;
        _index = index;
        return Current;
    }

    /// <summary>
    /// Removes one entry, keeping the currently playing entry current where possible.
    /// </summary>
    /// <remarks>
    /// Removing an entry above the current one shifts the index so the same entry stays current;
    /// removing the current one promotes the following entry, which is what "skip this" means when
    /// the user removes the item they are watching.
    /// </remarks>
    public bool RemoveAt(int index)
    {
        if (index < 0 || index >= _items.Count) return false;
        _items.RemoveAt(index);
        if (index < _index) _index--;
        if (_index >= _items.Count) _index = _items.Count - 1;
        if (_items.Count == 0) _index = -1;
        Revision++;
        return true;
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
        if (_items.Count == 0 && _index < 0) return;
        _items.Clear();
        _index = -1;
        Revision++;
    }

    /// <summary>
    /// A stable identity for an entry, used for duplicate suppression and for matching the current
    /// entry after a reload.
    /// </summary>
    public static string IdentityOf(PlaybackRequest request)
    {
        if (!string.IsNullOrEmpty(request.LocalPath))
            return "local:" + request.LocalPath.ToUpperInvariant();
        return "item:" + (request.ServerId ?? "") + "/" + (request.ItemId ?? "");
    }
}

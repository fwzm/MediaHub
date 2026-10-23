namespace MediaHub.Sync;

/// <summary>
/// In-memory <see cref="ILocalSourceStore"/> for tests and for W6-2 previews that must not touch disk.
/// </summary>
/// <remarks>
/// Thread-safe, and it counts real mutations in <see cref="WriteCount"/>: that counter is how the bridge's
/// "second apply writes nothing" guarantee is asserted instead of trusted.
/// </remarks>
public sealed class InMemoryLocalSourceStore : ILocalSourceStore
{
    private readonly object _gate = new();
    private readonly List<LocalSourceRecord> _records = [];

    /// <summary>Number of <see cref="Save"/>/<see cref="Delete"/> calls that actually changed something.</summary>
    public int WriteCount { get; private set; }

    /// <inheritdoc />
    public IReadOnlyList<LocalSourceRecord> GetAll()
    {
        lock (_gate)
        {
            return _records.ToArray();
        }
    }

    /// <inheritdoc />
    public void Save(LocalSourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        if (string.IsNullOrWhiteSpace(record.ServerId))
        {
            throw new ArgumentException("A local source must have a server id.", nameof(record));
        }

        lock (_gate)
        {
            for (int index = 0; index < _records.Count; index++)
            {
                if (!string.Equals(_records[index].ServerId, record.ServerId, StringComparison.Ordinal))
                {
                    continue;
                }

                if (!_records[index].Equals(record))
                {
                    _records[index] = record;
                    WriteCount++;
                }

                return;
            }

            _records.Add(record);
            WriteCount++;
        }
    }

    /// <inheritdoc />
    public bool Delete(string syncSourceId)
    {
        string? wanted = string.IsNullOrWhiteSpace(syncSourceId) ? null : syncSourceId.Trim();
        if (wanted is null)
        {
            return false;
        }

        lock (_gate)
        {
            int index = _records.FindIndex(record =>
                string.Equals(record.SyncSourceId?.Trim(), wanted, StringComparison.OrdinalIgnoreCase));
            if (index < 0)
            {
                return false;
            }

            _records.RemoveAt(index);
            WriteCount++;
            return true;
        }
    }
}

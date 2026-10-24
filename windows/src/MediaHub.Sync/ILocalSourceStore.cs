namespace MediaHub.Sync;

/// <summary>
/// Where the sync bridge reads and writes this device's own sources.
/// </summary>
/// <remarks>
/// The contract layer deliberately does not know where records live. W6-2b will bind this to the App's own
/// persisted server list; the interface is what lets the bridge be exercised without touching
/// <c>%LOCALAPPDATA%</c> and keeps file-format decisions in the layer that owns the user's data.
/// </remarks>
public interface ILocalSourceStore
{
    /// <summary>Every local source, in the store's own stable order.</summary>
    IReadOnlyList<LocalSourceRecord> GetAll();

    /// <summary>
    /// Inserts or replaces one source, keyed by <see cref="LocalSourceRecord.ServerId"/>. An update must keep
    /// the record's position: reordering the user's sources is not a sync side effect.
    /// </summary>
    void Save(LocalSourceRecord record);

    /// <summary>
    /// Removes the source carrying <paramref name="syncSourceId"/>. Returns <c>false</c> when nothing matched,
    /// so "already gone" is not an error.
    /// </summary>
    bool Delete(string syncSourceId);
}

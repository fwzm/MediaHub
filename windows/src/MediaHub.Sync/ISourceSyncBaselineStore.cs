namespace MediaHub.Sync;

/// <summary>Stable reason codes produced by baseline stores.</summary>
public static class SourceSyncBaselineStoreReasons
{
    /// <summary>An incoming entry carried a lower <c>recordRevision</c> than the stored one; the stored one wins.</summary>
    public const string StaleRevision = "baseline-stale-revision";

    /// <summary>Two entries claim the same revision with different content; the stored one is kept.</summary>
    public const string UnresolvedConflict = "baseline-unresolved-conflict";

    /// <summary>The file exists but could not be read (locked, no permission); nothing was quarantined.</summary>
    public const string StoreUnreadable = "store-unreadable";

    /// <summary>The baseline could not be persisted after an otherwise successful import.</summary>
    public const string WriteFailed = "baseline-write-failed";
}

/// <summary>What a save did to the stored baseline.</summary>
/// <remarks>
/// A save is a merge, not a replacement: the store already knows what the peer had, and a round that learned less
/// must never move the baseline backwards. Entries rejected by that guard are reported by id so a caller can log
/// them instead of silently believing the round was persisted.
/// </remarks>
public sealed record SourceSyncBaselineSaveResult
{
    /// <summary>True when the stored baseline changed.</summary>
    public bool Saved { get; init; }

    /// <summary>True when the incoming baseline added nothing and nothing was written to the medium.</summary>
    public bool Unchanged { get; init; }

    /// <summary>Entries whose incoming revision was not allowed to replace what was stored.</summary>
    public IReadOnlyList<string> RejectedSyncSourceIds { get; init; } = [];

    /// <summary>How many entries were rejected by the revision guard.</summary>
    public int RejectedCount => RejectedSyncSourceIds.Count;

    /// <summary>First rejection reason, when any entry was rejected.</summary>
    public string? Reason { get; init; }

    /// <summary>Result for a baseline that added nothing: no write happened.</summary>
    public static SourceSyncBaselineSaveResult NoChange { get; } = new() { Unchanged = true };

    /// <summary>Result for a baseline that did change the store.</summary>
    public static SourceSyncBaselineSaveResult Written(IReadOnlyList<string>? rejected = null, string? reason = null) => new()
    {
        Saved = true,
        RejectedSyncSourceIds = rejected ?? [],
        Reason = reason,
    };
}

/// <summary>Persists what this device knew about each peer at the end of the last round.</summary>
/// <remarks>
/// <para>
/// Keyed by peer <c>deviceId</c>, and inside that by <c>syncSourceId</c>: two peers never share a baseline, so a
/// round with device B cannot make device A look up to date. The baseline is a fact about the peer, never about
/// this device, which is why a missing store entry degrades to "know nothing" instead of guessing.
/// </para>
/// <para>
/// <see cref="Load"/> never throws: a missing file, a corrupt file and a file written by a newer build all
/// produce <see cref="SourceSyncBaseline.Empty"/> so the next round simply sends a full snapshot. <see cref="Save"/>
/// may throw <see cref="System.IO.IOException"/> when the medium cannot be written; swallowing that would let the
/// caller believe a round was persisted when it was not.
/// </para>
/// </remarks>
public interface ISourceSyncBaselineStore
{
    /// <summary>The baseline stored for one peer, or <see cref="SourceSyncBaseline.Empty"/> when nothing is known.</summary>
    SourceSyncBaseline Load(string peerDeviceId);

    /// <summary>Merges a new baseline in, refusing to move any entry backwards; reports what was rejected.</summary>
    SourceSyncBaselineSaveResult Save(string peerDeviceId, SourceSyncBaseline baseline);

    /// <summary>Drops everything the store knows about one peer; true when an entry was removed.</summary>
    /// <param name="peerDeviceId">Peer device id; stores compare it trimmed and case-insensitively.</param>
    /// <returns>True when a stored peer was dropped and the medium rewritten; false when nothing matched.</returns>
    /// <remarks>
    /// <para>
    /// Forgetting is a deliberate deletion, not a merge: <see cref="Save"/> can only move a baseline forward, so a
    /// peer the user unpaired would otherwise stay in the document forever and make the next round treat a
    /// re-paired device as already applied — stale knowledge silently skipping real changes.
    /// </para>
    /// <para>
    /// It is local bookkeeping: nothing is said to the peer, no source is touched, and a blank id is a caller bug
    /// rather than an empty answer. A store that cannot persist the deletion must throw instead of returning
    /// false, because a baseline that survived a user's "forget" is worse than a failed forget.
    /// </para>
    /// </remarks>
    bool Forget(string peerDeviceId);
}

/// <summary>The outcome of merging an incoming baseline into the stored one.</summary>
public sealed record SourceSyncBaselineMergeResult
{
    /// <summary>The baseline that should be stored.</summary>
    public SourceSyncBaseline Merged { get; init; } = SourceSyncBaseline.Empty;

    /// <summary>Entries the incoming baseline tried to move backwards or sideways.</summary>
    public IReadOnlyList<string> RejectedSyncSourceIds { get; init; } = [];

    /// <summary>Reason of the first rejection.</summary>
    public string? Reason { get; init; }

    /// <summary>True when <see cref="Merged"/> differs from the baseline that was already stored.</summary>
    public bool Changed { get; init; }
}

/// <summary>Revision-monotonic merge shared by every baseline store.</summary>
/// <remarks>
/// The guard is deliberately conservative: a higher revision replaces, an equal revision with identical hashes is a
/// no-op, and everything else is refused with a reason. Because ordering is by revision and never by a wall clock
/// (D5), "refused" here means "the two devices need another round", not "the later editor lost".
/// </remarks>
public static class SourceSyncBaselineMerge
{
    /// <summary>Merges <paramref name="incoming"/> into <paramref name="existing"/>.</summary>
    public static SourceSyncBaselineMergeResult Merge(SourceSyncBaseline? existing, SourceSyncBaseline? incoming)
    {
        var entries = new List<SourceSyncBaselineEntry>();
        var index = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (SourceSyncBaselineEntry entry in existing?.Entries ?? [])
        {
            if (entry is null || string.IsNullOrWhiteSpace(entry.SyncSourceId))
            {
                continue;
            }

            string key = entry.SyncSourceId.Trim();
            if (index.ContainsKey(key))
            {
                continue;
            }

            index[key] = entries.Count;
            entries.Add(entry with { SyncSourceId = key });
        }

        var rejected = new List<string>();
        string? reason = null;

        foreach (SourceSyncBaselineEntry entry in incoming?.Entries ?? [])
        {
            if (entry is null || string.IsNullOrWhiteSpace(entry.SyncSourceId))
            {
                continue;
            }

            string key = entry.SyncSourceId.Trim();
            SourceSyncBaselineEntry candidate = entry with { SyncSourceId = key };
            if (!index.TryGetValue(key, out int position))
            {
                index[key] = entries.Count;
                entries.Add(candidate);
                continue;
            }

            SourceSyncBaselineEntry stored = entries[position];
            if (candidate.RecordRevision > stored.RecordRevision)
            {
                entries[position] = candidate;
                continue;
            }

            if (candidate.RecordRevision == stored.RecordRevision && SameContent(stored, candidate))
            {
                continue;
            }

            rejected.Add(key);
            reason ??= candidate.RecordRevision < stored.RecordRevision
                ? SourceSyncBaselineStoreReasons.StaleRevision
                : SourceSyncBaselineStoreReasons.UnresolvedConflict;
        }

        var merged = new SourceSyncBaseline
        {
            Version = SourceSyncBaseline.CurrentVersion,
            DeviceId = string.IsNullOrWhiteSpace(incoming?.DeviceId) ? existing?.DeviceId : incoming?.DeviceId,
            SnapshotId = string.IsNullOrWhiteSpace(incoming?.SnapshotId) ? existing?.SnapshotId : incoming?.SnapshotId,
            Entries = entries,
        };

        return new SourceSyncBaselineMergeResult
        {
            Merged = merged,
            RejectedSyncSourceIds = rejected,
            Reason = reason,
            Changed = !SameEntries(existing?.Entries, entries),
        };
    }

    /// <summary>Whether two baselines describe exactly the same peer state, ignoring labels.</summary>
    public static bool SameEntries(
        IReadOnlyList<SourceSyncBaselineEntry>? left,
        IReadOnlyList<SourceSyncBaselineEntry>? right)
    {
        IReadOnlyList<SourceSyncBaselineEntry> first = left ?? [];
        IReadOnlyList<SourceSyncBaselineEntry> second = right ?? [];
        if (first.Count != second.Count)
        {
            return false;
        }

        for (int position = 0; position < first.Count; position++)
        {
            if (!SameContent(first[position], second[position]))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>Whether two entries describe the same identity, revision and content.</summary>
    public static bool SameContent(SourceSyncBaselineEntry? left, SourceSyncBaselineEntry? right)
    {
        if (left is null || right is null)
        {
            return false;
        }

        if (!string.Equals(left.SyncSourceId.Trim(), right.SyncSourceId.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (left.RecordRevision != right.RecordRevision || left.FieldVersion != right.FieldVersion)
        {
            return false;
        }

        if (!string.Equals(left.ContentHash ?? "", right.ContentHash ?? "", StringComparison.Ordinal))
        {
            return false;
        }

        IReadOnlyDictionary<string, string> first = left.FieldHashes ?? SourceSyncBaselineEntry.EmptyFieldHashes;
        IReadOnlyDictionary<string, string> second = right.FieldHashes ?? SourceSyncBaselineEntry.EmptyFieldHashes;
        if (first.Count != second.Count)
        {
            return false;
        }

        foreach ((string field, string hash) in first)
        {
            if (!second.TryGetValue(field, out string? other)
                || !string.Equals(hash, other, StringComparison.Ordinal))
            {
                return false;
            }
        }

        return true;
    }
}

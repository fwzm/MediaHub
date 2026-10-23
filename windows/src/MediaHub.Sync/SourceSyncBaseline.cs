namespace MediaHub.Sync;

/// <summary>
/// What this device knew about one peer's copy of one source at the end of the last round.
/// </summary>
/// <remarks>
/// The revision decides who wins, the field version decides whether two edits are comparable, and the hashes
/// answer "did anything change" without shipping the record. <see cref="ContentHash"/> is a whole-record hash;
/// <see cref="FieldHashes"/> splits it per field so the digest can name the fields that moved instead of
/// proposing a blind overwrite.
/// </remarks>
public sealed record SourceSyncBaselineEntry
{
    /// <summary>Cross-device identity this entry describes.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Revision the peer had for the source.</summary>
    public long RecordRevision { get; init; }

    /// <summary>Field-set version the peer had for the source.</summary>
    public int FieldVersion { get; init; }

    /// <summary>Hash of the whole record as the peer had it.</summary>
    public string ContentHash { get; init; } = "";

    /// <summary>Hash per <see cref="SourceSyncFields"/> field. Empty means "unknown", not "unchanged".</summary>
    public IReadOnlyDictionary<string, string> FieldHashes { get; init; } = EmptyFieldHashes;

    /// <summary>Shared empty map, so a baseline entry costs nothing until it has hashes.</summary>
    internal static readonly IReadOnlyDictionary<string, string> EmptyFieldHashes
        = new Dictionary<string, string>(StringComparer.Ordinal);

    /// <summary>Builds an entry from a record exactly as it is held (or was received).</summary>
    public static SourceSyncBaselineEntry FromRecord(SourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

        return new SourceSyncBaselineEntry
        {
            SyncSourceId = record.SyncSourceId.Trim(),
            RecordRevision = record.RecordRevision,
            FieldVersion = record.FieldVersion,
            ContentHash = SourceSyncContentHash.Compute(record),
            FieldHashes = SourceSyncContentHash.ComputeFields(record),
        };
    }
}

/// <summary>
/// The snapshot of a peer this device last converged with: one <see cref="SourceSyncBaselineEntry"/> per
/// <c>syncSourceId</c>.
/// </summary>
/// <remarks>
/// <para>
/// A baseline is a fact about the peer, not about this device: it says "the peer had this revision with this
/// content". The digest is the difference between the current local records and that fact, which is why a
/// missing baseline degrades to a full snapshot instead of guessing.
/// </para>
/// <para>
/// Nothing here reads a clock. <see cref="DeviceId"/> and <see cref="SnapshotId"/> are labels for explaining a
/// round to the user; ordering is carried by revisions only (D5).
/// </para>
/// </remarks>
public sealed record SourceSyncBaseline
{
    /// <summary>Document version this build writes and reads.</summary>
    public const int CurrentVersion = 1;

    /// <summary>Document version.</summary>
    public int Version { get; init; } = CurrentVersion;

    /// <summary>Peer the baseline describes, when known.</summary>
    public string? DeviceId { get; init; }

    /// <summary>Snapshot the baseline was last advanced from, when known. Informational.</summary>
    public string? SnapshotId { get; init; }

    /// <summary>One entry per known source, in the order they were first learned.</summary>
    public IReadOnlyList<SourceSyncBaselineEntry> Entries { get; init; } = [];

    /// <summary>A baseline that knows nothing: any local record is new.</summary>
    public static SourceSyncBaseline Empty { get; } = new();

    /// <summary>Whether this baseline knows about any source at all.</summary>
    public bool IsEmpty => Entries.Count == 0;

    /// <summary>Finds the entry for a sync identity; ids are compared trimmed and case-insensitively.</summary>
    public SourceSyncBaselineEntry? Find(string? syncSourceId)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return null;
        }

        string key = syncSourceId.Trim();
        return Entries.FirstOrDefault(
            entry => entry is not null && string.Equals(entry.SyncSourceId, key, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Captures every record of a received envelope as the new baseline.</summary>
    public static SourceSyncBaseline FromEnvelope(SourceSyncEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);

        return new SourceSyncBaseline
        {
            DeviceId = envelope.DeviceId,
            SnapshotId = envelope.SnapshotId,
            Entries = Deduplicate(envelope.Sources),
        };
    }

    /// <summary>Captures a set of records as a baseline.</summary>
    public static SourceSyncBaseline FromRecords(
        IEnumerable<SourceRecord> records,
        string? deviceId = null,
        string? snapshotId = null)
    {
        ArgumentNullException.ThrowIfNull(records);

        return new SourceSyncBaseline
        {
            DeviceId = deviceId,
            SnapshotId = snapshotId,
            Entries = Deduplicate(records),
        };
    }

    /// <summary>
    /// The baseline after the peer has seen <paramref name="digest"/>: changed entries are replaced, removals are
    /// dropped and everything the digest did not mention is kept as it was.
    /// </summary>
    /// <remarks>
    /// This is what makes a second round a no-op. Advancing with a digest and rebuilding that same digest must
    /// yield an empty change set; if it does not, the hashes and the digest disagree and the protocol would ping
    /// pong forever.
    /// </remarks>
    public static SourceSyncBaseline Advance(
        SourceSyncBaseline? baseline,
        SourceSyncDigest digest,
        string? snapshotId = null)
    {
        ArgumentNullException.ThrowIfNull(digest);

        var entries = new List<SourceSyncBaselineEntry>();
        var index = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        foreach (SourceSyncBaselineEntry entry in baseline?.Entries ?? [])
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

        foreach (SourceSyncDigestItem item in digest.Items)
        {
            string key = item.SyncSourceId.Trim();
            switch (item.Kind)
            {
                case SourceSyncChangeKind.Removed:
                    if (index.Remove(key, out int removedAt))
                    {
                        entries.RemoveAt(removedAt);
                        index.Clear();
                        for (int position = 0; position < entries.Count; position++)
                        {
                            index[entries[position].SyncSourceId] = position;
                        }
                    }

                    break;

                case SourceSyncChangeKind.Added:
                case SourceSyncChangeKind.Updated:
                    if (item.Record is not { } record)
                    {
                        break;
                    }

                    SourceSyncBaselineEntry replacement = SourceSyncBaselineEntry.FromRecord(record) with
                    {
                        SyncSourceId = key,
                    };

                    if (index.TryGetValue(key, out int existingAt))
                    {
                        entries[existingAt] = replacement;
                    }
                    else
                    {
                        index[key] = entries.Count;
                        entries.Add(replacement);
                    }

                    break;
            }
        }

        return new SourceSyncBaseline
        {
            DeviceId = baseline?.DeviceId,
            SnapshotId = snapshotId ?? baseline?.SnapshotId,
            Entries = entries,
        };
    }

    private static IReadOnlyList<SourceSyncBaselineEntry> Deduplicate(IEnumerable<SourceRecord> records)
    {
        var entries = new List<SourceSyncBaselineEntry>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (SourceRecord record in records)
        {
            if (record is null || string.IsNullOrWhiteSpace(record.SyncSourceId))
            {
                continue;
            }

            if (!seen.Add(record.SyncSourceId.Trim()))
            {
                // One identity, one entry: a duplicated id in a payload is malformed input, and keeping the
                // first occurrence is the only deterministic choice that does not depend on arrival order.
                continue;
            }

            entries.Add(SourceSyncBaselineEntry.FromRecord(record));
        }

        return entries;
    }
}

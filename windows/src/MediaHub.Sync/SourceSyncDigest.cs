namespace MediaHub.Sync;

/// <summary>What happened to one source since the baseline.</summary>
public enum SourceSyncChangeKind
{
    /// <summary>The peer has never seen this identity.</summary>
    Added = 0,

    /// <summary>The identity exists on both sides but something in the record moved.</summary>
    Updated,

    /// <summary>The source was deleted on this device.</summary>
    Removed,
}

/// <summary>
/// A local record the digest deliberately left out, so the UI can say why it does not travel.
/// </summary>
public sealed record SourceSyncDigestIssue
{
    /// <summary>Identity as it appears locally, or empty when the record had none.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>One of the <see cref="SourceSyncDigestReasons"/> constants.</summary>
    public required string Reason { get; init; }
}

/// <summary>Stable reason codes for <see cref="SourceSyncDigestIssue.Reason"/>.</summary>
public static class SourceSyncDigestReasons
{
    /// <summary>The record has no cross-device identity, so nothing could reference it on the peer.</summary>
    public const string MissingSyncSourceId = "missing-sync-source-id";

    /// <summary>Neither a mapped kind nor a verbatim wire value: there is no <c>sourceType</c> to send.</summary>
    public const string NoWireType = "no-wire-type";

    /// <summary>Two local records claim the same identity; only the first is sent.</summary>
    public const string DuplicateLocalSyncId = "duplicate-local-sync-id";

    /// <summary>A tombstone names a source this device still holds: the record wins and the tombstone is dropped.</summary>
    public const string TombstoneForExistingRecord = "tombstone-for-existing-record";
}

/// <summary>One entry of the outbound change set.</summary>
/// <remarks>
/// <see cref="ChangedFields"/> is the whole point of keeping per-field hashes: it says <em>what</em> moved, so the
/// merge can tell a field-level conflict from two independent edits, and so the preview can say
/// "name changed" instead of "record changed".
/// </remarks>
public sealed record SourceSyncDigestItem
{
    /// <summary>Identity this entry is about.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Added, updated or removed.</summary>
    public SourceSyncChangeKind Kind { get; init; }

    /// <summary>The record to send, for <see cref="SourceSyncChangeKind.Added"/> and <see cref="SourceSyncChangeKind.Updated"/>.</summary>
    public SourceRecord? Record { get; init; }

    /// <summary>The tombstone to send, for <see cref="SourceSyncChangeKind.Removed"/>.</summary>
    public Tombstone? Tombstone { get; init; }

    /// <summary>Fields whose content hash differs from the baseline, alphabetically. All fields for an addition.</summary>
    public IReadOnlyList<string> ChangedFields { get; init; } = [];

    /// <summary>
    /// Whether the canonical content actually differs. False when only the revision (or the field-set version)
    /// moved; the entry is still sent so the peer's revision catches up, but no field is claimed to have changed.
    /// </summary>
    public bool ContentChanged { get; init; }

    /// <summary>Revision being sent: the record's, or the tombstone's for a removal.</summary>
    public long RecordRevision { get; init; }

    /// <summary>Field-set version being sent.</summary>
    public int FieldVersion { get; init; }
}

/// <summary>
/// The incremental change set to push: everything this device changed since the baseline the peer gave us.
/// </summary>
/// <remarks>
/// <para>
/// A digest is not a plan and not a write. It answers one question — "what does the peer not know about this
/// device yet" — and leaves every decision to <see cref="SyncRoundMerger"/>.
/// </para>
/// <para>
/// A missing or empty baseline degrades to a full snapshot (<see cref="IsFullSnapshot"/>): with nothing known
/// about the peer, every local source is new. Guessing a baseline is how a first sync silently loses data.
/// </para>
/// <para>
/// Records without a cross-device identity or without any wire type are reported in
/// <see cref="Issues"/> instead of being dropped: a state the user can see is a state they can fix.
/// </para>
/// </remarks>
public sealed record SourceSyncDigest
{
    /// <summary>Added, updated and removed entries, in a stable order (records in input order, then removals).</summary>
    public IReadOnlyList<SourceSyncDigestItem> Items { get; init; } = [];

    /// <summary>Local records that could not be represented on the wire.</summary>
    public IReadOnlyList<SourceSyncDigestIssue> Issues { get; init; } = [];

    /// <summary>True when no usable baseline was given, so every record in the digest is an addition.</summary>
    public bool IsFullSnapshot { get; init; }

    /// <summary>Whether there is anything to send.</summary>
    public bool IsEmpty => Items.Count == 0;

    /// <summary>Additions only.</summary>
    public IReadOnlyList<SourceSyncDigestItem> Added
        => Items.Where(item => item.Kind == SourceSyncChangeKind.Added).ToList();

    /// <summary>Updates only.</summary>
    public IReadOnlyList<SourceSyncDigestItem> Updated
        => Items.Where(item => item.Kind == SourceSyncChangeKind.Updated).ToList();

    /// <summary>Removals (tombstones) only.</summary>
    public IReadOnlyList<SourceSyncDigestItem> Removed
        => Items.Where(item => item.Kind == SourceSyncChangeKind.Removed).ToList();

    /// <summary>Builds the change set of these local records against <paramref name="baseline"/>.</summary>
    /// <param name="localRecords">
    /// Local sources in the contract's own shape — normally <see cref="SourceSyncLocalBridge.BuildEnvelope"/>, whose
    /// records are already normalised (addresses through <see cref="SyncUrlNormalizer"/>, identity assigned).
    /// </param>
    /// <param name="baseline">What the peer had last round; <c>null</c> means nothing is known.</param>
    /// <param name="localDeletions">Sources deleted on this device since the baseline.</param>
    public static SourceSyncDigest Build(
        IReadOnlyList<SourceRecord> localRecords,
        SourceSyncBaseline? baseline,
        IReadOnlyList<Tombstone>? localDeletions = null)
    {
        ArgumentNullException.ThrowIfNull(localRecords);

        bool fullSnapshot = baseline is null || baseline.IsEmpty;
        var items = new List<SourceSyncDigestItem>(localRecords.Count);
        var issues = new List<SourceSyncDigestIssue>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (SourceRecord record in localRecords)
        {
            if (record is null)
            {
                continue;
            }

            string? identity = Trimmed(record.SyncSourceId);
            if (identity is null)
            {
                issues.Add(new SourceSyncDigestIssue { Reason = SourceSyncDigestReasons.MissingSyncSourceId });
                continue;
            }

            if (!seen.Add(identity))
            {
                issues.Add(new SourceSyncDigestIssue
                {
                    SyncSourceId = identity,
                    Reason = SourceSyncDigestReasons.DuplicateLocalSyncId,
                });
                continue;
            }

            if (record.SourceType.Wire.Length == 0)
            {
                issues.Add(new SourceSyncDigestIssue
                {
                    SyncSourceId = identity,
                    Reason = SourceSyncDigestReasons.NoWireType,
                });
                continue;
            }

            SourceSyncBaselineEntry? known = baseline?.Find(identity);
            if (known is null)
            {
                items.Add(new SourceSyncDigestItem
                {
                    SyncSourceId = identity,
                    Kind = SourceSyncChangeKind.Added,
                    Record = record,
                    ChangedFields = SourceSyncFields.All,
                    ContentChanged = true,
                    RecordRevision = record.RecordRevision,
                    FieldVersion = record.FieldVersion,
                });
                continue;
            }

            IReadOnlyDictionary<string, string> fieldHashes = SourceSyncContentHash.ComputeFields(record);
            string contentHash = SourceSyncContentHash.Compute(record);
            List<string> changedFields = ChangedFields(known, fieldHashes);
            bool contentChanged = known.ContentHash.Length == 0
                || !string.Equals(contentHash, known.ContentHash, StringComparison.Ordinal);

            if (!contentChanged
                && record.RecordRevision == known.RecordRevision
                && record.FieldVersion == known.FieldVersion)
            {
                continue;
            }

            if (record.RecordRevision < known.RecordRevision)
            {
                // The peer is ahead of us; pushing an older revision would only bounce back. The peer's side of
                // the round is what fills the gap, so this is not a change to send.
                continue;
            }

            items.Add(new SourceSyncDigestItem
            {
                SyncSourceId = identity,
                Kind = SourceSyncChangeKind.Updated,
                Record = record,
                ChangedFields = changedFields,
                ContentChanged = contentChanged,
                RecordRevision = record.RecordRevision,
                FieldVersion = record.FieldVersion,
            });
        }

        foreach (Tombstone tombstone in localDeletions ?? [])
        {
            if (tombstone is null)
            {
                continue;
            }

            string? identity = Trimmed(tombstone.SyncSourceId);
            if (identity is null)
            {
                issues.Add(new SourceSyncDigestIssue { Reason = SourceSyncDigestReasons.MissingSyncSourceId });
                continue;
            }

            if (seen.Contains(identity))
            {
                issues.Add(new SourceSyncDigestIssue
                {
                    SyncSourceId = identity,
                    Reason = SourceSyncDigestReasons.TombstoneForExistingRecord,
                });
                continue;
            }

            if (!seen.Add(identity))
            {
                issues.Add(new SourceSyncDigestIssue
                {
                    SyncSourceId = identity,
                    Reason = SourceSyncDigestReasons.DuplicateLocalSyncId,
                });
                continue;
            }

            items.Add(new SourceSyncDigestItem
            {
                SyncSourceId = identity,
                Kind = SourceSyncChangeKind.Removed,
                Tombstone = tombstone,
                RecordRevision = tombstone.RecordRevision,
            });
        }

        return new SourceSyncDigest
        {
            Items = items,
            Issues = issues,
            IsFullSnapshot = fullSnapshot,
        };
    }

    /// <summary>Builds the change set for a local envelope produced by <see cref="SourceSyncLocalBridge"/>.</summary>
    public static SourceSyncDigest FromEnvelope(SourceSyncEnvelope envelope, SourceSyncBaseline? baseline)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        return Build(envelope.Sources, baseline, envelope.Deletions);
    }

    /// <summary>
    /// Turns the change set into the envelope to hand to the transport: records for additions and updates,
    /// tombstones for removals, capabilities listing the types actually present.
    /// </summary>
    /// <remarks>
    /// <paramref name="snapshotId"/> and <paramref name="createdAtEpochMs"/> are parameters rather than values
    /// read inside: this layer never calls the clock, which is what keeps it testable and keeps two devices'
    /// clocks out of every comparison (D5).
    /// </remarks>
    public SourceSyncEnvelope ToEnvelope(
        string deviceId,
        string snapshotId,
        long createdAtEpochMs,
        bool canSendSecrets = false)
    {
        ArgumentNullException.ThrowIfNull(deviceId);
        ArgumentNullException.ThrowIfNull(snapshotId);

        var sources = new List<SourceRecord>();
        var deletions = new List<Tombstone>();
        var sourceTypes = new List<string>();
        var seenTypes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (SourceSyncDigestItem item in Items)
        {
            switch (item.Kind)
            {
                case SourceSyncChangeKind.Removed:
                    if (item.Tombstone is { } tombstone)
                    {
                        deletions.Add(tombstone);
                    }

                    break;

                default:
                    if (item.Record is { } record)
                    {
                        sources.Add(record);
                        if (seenTypes.Add(record.SourceType.Wire))
                        {
                            sourceTypes.Add(record.SourceType.Wire);
                        }
                    }

                    break;
            }
        }

        return new SourceSyncEnvelope
        {
            DeviceId = deviceId.Trim(),
            SnapshotId = snapshotId,
            CreatedAtEpochMs = createdAtEpochMs,
            Capabilities = new SyncCapabilities
            {
                CanSendSecrets = canSendSecrets,
                SourceTypes = sourceTypes,
            },
            Sources = sources,
            Deletions = deletions,
        };
    }

    private static List<string> ChangedFields(
        SourceSyncBaselineEntry known,
        IReadOnlyDictionary<string, string> fieldHashes)
    {
        var changed = new List<string>();
        foreach (string field in SourceSyncFields.All)
        {
            if (!known.FieldHashes.TryGetValue(field, out string? knownHash)
                || !fieldHashes.TryGetValue(field, out string? currentHash)
                || !string.Equals(knownHash, currentHash, StringComparison.Ordinal))
            {
                changed.Add(field);
            }
        }

        return changed;
    }

    private static string? Trimmed(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

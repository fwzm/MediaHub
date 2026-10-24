namespace MediaHub.Sync;

/// <summary>
/// Two-way merge of a local and a remote digest into one round result.
/// </summary>
/// <remarks>
/// <para>
/// The merger never writes and never calls the clock. It turns "what each side changed since the baseline"
/// into a partition of decisions, and it is deliberately allowed to refuse: a deletion, a field conflict, an
/// unknown source type or a credential payload can only be <see cref="SyncRoundResult.RequiresApproval"/>.
/// Everything else — an addition, a one-sided edit the peer has not seen — is <see cref="SyncRoundResult.Apply"/>.
/// </para>
/// <para>
/// Ordering of the output is by <c>syncSourceId</c> (ordinal), not by input order: two devices running the
/// same round must produce the same list, and a preview that reshuffles between runs is a preview nobody
/// trusts.
/// </para>
/// <para>
/// Revisions order two versions of the same source. Wall-clock timestamps are never compared (D5): clocks
/// disagree across devices, and a skew of two minutes must not decide which source survives.
/// </para>
/// </remarks>
public static class SyncRoundMerger
{
    /// <summary>Merges one round.</summary>
    /// <param name="local">What this device changed since <paramref name="baseline"/>.</param>
    /// <param name="remote">What the peer changed since the same <paramref name="baseline"/>.</param>
    /// <param name="baseline">The last state both sides agreed on; <c>null</c> means the first contact.</param>
    public static SyncRoundResult Merge(
        SourceSyncDigest local,
        SourceSyncDigest remote,
        SourceSyncBaseline? baseline = null)
    {
        ArgumentNullException.ThrowIfNull(local);
        ArgumentNullException.ThrowIfNull(remote);

        var localById = Index(local);
        var remoteById = Index(remote);
        var keys = new List<string>(localById.Keys);
        foreach (string key in remoteById.Keys)
        {
            if (!localById.ContainsKey(key))
            {
                keys.Add(key);
            }
        }

        keys.Sort(StringComparer.Ordinal);

        var apply = new List<SyncRoundEntry>();
        var approval = new List<SyncRoundEntry>();
        var ignored = new List<SyncRoundEntry>();

        foreach (string key in keys)
        {
            localById.TryGetValue(key, out SourceSyncDigestItem? localItem);
            remoteById.TryGetValue(key, out SourceSyncDigestItem? remoteItem);

            SyncRoundDecision decision = Decide(key, localItem, remoteItem, baseline?.Find(key));
            switch (decision.Bucket)
            {
                case SyncRoundBucket.Apply:
                    apply.Add(decision.Entry);
                    break;

                case SyncRoundBucket.Approval:
                    approval.Add(decision.Entry);
                    break;

                default:
                    ignored.Add(decision.Entry);
                    break;
            }
        }

        return SyncRoundResult.Create(apply, approval, ignored);
    }

    private static SyncRoundDecision Decide(
        string key,
        SourceSyncDigestItem? local,
        SourceSyncDigestItem? remote,
        SourceSyncBaselineEntry? known)
    {
        // The peer's record has to be representable here before any revision comparison can mean anything:
        // a source type this build cannot resolve must never be written as a guess.
        if (remote is not null
            && remote.Kind != SourceSyncChangeKind.Removed
            && remote.Record is { } remoteRecord
            && DescribeUnsupported(remoteRecord) is { } unsupported)
        {
            return Approval(
                key,
                SyncMergeDirection.Pull,
                remote.Kind,
                unsupported,
                remote: remote,
                record: remoteRecord);
        }

        if (local is null)
        {
            return remote is null
                ? Ignore(key, SyncMergeDirection.Both, SourceSyncChangeKind.Updated, SyncRoundReasons.AlreadyApplied)
                : DecideRemoteOnly(key, remote, known);
        }

        if (remote is null)
        {
            return DecideLocalOnly(key, local, known);
        }

        return DecideBoth(key, local, remote);
    }

    private static SyncRoundDecision DecideRemoteOnly(
        string key,
        SourceSyncDigestItem remote,
        SourceSyncBaselineEntry? known)
    {
        if (remote.Kind == SourceSyncChangeKind.Removed)
        {
            Tombstone tombstone = TombstoneOf(remote, key);
            if (known is null)
            {
                return Ignore(
                    key,
                    SyncMergeDirection.Pull,
                    remote.Kind,
                    SyncRoundReasons.TombstoneUnknown,
                    remote: remote,
                    tombstone: tombstone);
            }

            if (remote.RecordRevision < known.RecordRevision)
            {
                return Ignore(
                    key,
                    SyncMergeDirection.Pull,
                    remote.Kind,
                    SyncRoundReasons.StaleRemote,
                    remote: remote,
                    tombstone: tombstone);
            }

            // Deleting a local source is never automatic, even when the peer is clearly ahead.
            return Approval(
                key,
                SyncMergeDirection.Pull,
                remote.Kind,
                SyncRoundReasons.TombstoneRequiresApproval,
                remote: remote,
                tombstone: tombstone);
        }

        if (known is not null && remote.RecordRevision <= known.RecordRevision)
        {
            return Ignore(
                key,
                SyncMergeDirection.Pull,
                remote.Kind,
                SyncRoundReasons.AlreadyApplied,
                remote: remote,
                record: remote.Record);
        }

        return Apply(
            key,
            SyncMergeDirection.Pull,
            remote.Kind,
            SyncRoundReasons.ApplyRemoteChange,
            remote: remote,
            record: remote.Record);
    }

    private static SyncRoundDecision DecideLocalOnly(
        string key,
        SourceSyncDigestItem local,
        SourceSyncBaselineEntry? known)
    {
        if (local.Kind == SourceSyncChangeKind.Removed)
        {
            Tombstone tombstone = TombstoneOf(local, key);
            if (known is not null && local.RecordRevision < known.RecordRevision)
            {
                return Ignore(
                    key,
                    SyncMergeDirection.Push,
                    local.Kind,
                    SyncRoundReasons.StaleLocal,
                    local: local,
                    tombstone: tombstone);
            }

            return Apply(
                key,
                SyncMergeDirection.Push,
                local.Kind,
                SyncRoundReasons.PushLocalDelete,
                local: local,
                tombstone: tombstone);
        }

        return Apply(
            key,
            SyncMergeDirection.Push,
            local.Kind,
            SyncRoundReasons.PushLocalChange,
            local: local,
            record: local.Record);
    }

    private static SyncRoundDecision DecideBoth(
        string key,
        SourceSyncDigestItem local,
        SourceSyncDigestItem remote)
    {
        if (local.Kind == SourceSyncChangeKind.Removed && remote.Kind == SourceSyncChangeKind.Removed)
        {
            return Ignore(
                key,
                SyncMergeDirection.Both,
                SourceSyncChangeKind.Removed,
                SyncRoundReasons.BothDeleted,
                local: local,
                remote: remote,
                tombstone: TombstoneOf(local, key));
        }

        if (local.Kind == SourceSyncChangeKind.Removed)
        {
            return Approval(
                key,
                SyncMergeDirection.Both,
                remote.Kind,
                SyncRoundReasons.DeleteVsUpdate,
                local: local,
                remote: remote,
                tombstone: TombstoneOf(local, key),
                record: remote.Record);
        }

        if (remote.Kind == SourceSyncChangeKind.Removed)
        {
            return Approval(
                key,
                SyncMergeDirection.Both,
                local.Kind,
                SyncRoundReasons.UpdateVsDelete,
                local: local,
                remote: remote,
                record: local.Record,
                tombstone: TombstoneOf(remote, key));
        }

        if (local.Record is null || remote.Record is null)
        {
            // A record-kind item without a record is a malformed digest rather than a decision; treating it
            // as a conflict keeps it out of the apply bucket.
            return Approval(
                key,
                SyncMergeDirection.Both,
                remote.Kind,
                SyncRoundReasons.SameRevisionDifferentContent,
                local: local,
                remote: remote,
                record: remote.Record);
        }

        string localHash = SourceSyncContentHash.Compute(local.Record);
        string remoteHash = SourceSyncContentHash.Compute(remote.Record);

        if (string.Equals(localHash, remoteHash, StringComparison.Ordinal))
        {
            if (remote.RecordRevision > local.RecordRevision)
            {
                return Apply(
                    key,
                    SyncMergeDirection.Pull,
                    remote.Kind,
                    SyncRoundReasons.RemoteNewer,
                    local: local,
                    remote: remote,
                    record: remote.Record);
            }

            if (local.RecordRevision > remote.RecordRevision)
            {
                return Apply(
                    key,
                    SyncMergeDirection.Push,
                    local.Kind,
                    SyncRoundReasons.LocalNewer,
                    local: local,
                    remote: remote,
                    record: local.Record);
            }

            // Same content, same revision: both sides converged independently.
            return Ignore(
                key,
                SyncMergeDirection.Both,
                remote.Kind,
                SyncRoundReasons.AlreadyApplied,
                local: local,
                remote: remote,
                record: remote.Record);
        }

        // Different content. Which fields both sides touched decides whether a human is needed: overlapping
        // fields at the same field-set version is a real conflict, disjoint edits are not.
        List<string> conflictingFields = local.ChangedFields
            .Intersect(remote.ChangedFields, StringComparer.Ordinal)
            .OrderBy(field => field, StringComparer.Ordinal)
            .ToList();

        if (conflictingFields.Count > 0 && local.Record.FieldVersion == remote.Record.FieldVersion)
        {
            return Approval(
                key,
                SyncMergeDirection.Both,
                remote.Kind,
                SyncRoundReasons.FieldConflict,
                local: local,
                remote: remote,
                record: remote.Record,
                conflictingFields: conflictingFields);
        }

        if (remote.RecordRevision > local.RecordRevision)
        {
            return Apply(
                key,
                SyncMergeDirection.Pull,
                remote.Kind,
                SyncRoundReasons.RemoteNewer,
                local: local,
                remote: remote,
                record: remote.Record);
        }

        if (local.RecordRevision > remote.RecordRevision)
        {
            return Apply(
                key,
                SyncMergeDirection.Push,
                local.Kind,
                SyncRoundReasons.LocalNewer,
                local: local,
                remote: remote,
                record: local.Record);
        }

        return Approval(
            key,
            SyncMergeDirection.Both,
            remote.Kind,
            SyncRoundReasons.SameRevisionDifferentContent,
            local: local,
            remote: remote,
            record: remote.Record);
    }

    /// <summary>Why the peer's record cannot be written here, or null when it can be.</summary>
    private static string? DescribeUnsupported(SourceRecord record)
    {
        if (record.SourceType is null || record.SourceType.Wire.Length == 0)
        {
            return SyncRoundReasons.UnknownSourceType;
        }

        WindowsSourceTypeResolution resolution = WindowsSourceTypeMap.Resolve(record.SourceType.Wire);
        if (!resolution.IsKnown)
        {
            return SyncRoundReasons.UnknownSourceType;
        }

        if (!resolution.IsSupported)
        {
            return SyncRoundReasons.NotSupportedOnWindows;
        }

        if (record.Secrets is { } secrets && secrets.ValueKind != System.Text.Json.JsonValueKind.Null)
        {
            return SyncRoundReasons.SecretsRequireApproval;
        }

        return null;
    }

    /// <summary>Digest lookup keyed the way identities are compared everywhere else.</summary>
    private static Dictionary<string, SourceSyncDigestItem> Index(SourceSyncDigest digest)
    {
        var index = new Dictionary<string, SourceSyncDigestItem>(StringComparer.OrdinalIgnoreCase);
        foreach (SourceSyncDigestItem item in digest.Items)
        {
            string? key = string.IsNullOrWhiteSpace(item.SyncSourceId) ? null : item.SyncSourceId.Trim();
            if (key is null)
            {
                continue;
            }

            if (!index.TryGetValue(key, out SourceSyncDigestItem? existing)
                || item.RecordRevision > existing.RecordRevision)
            {
                index[key] = item;
            }
        }

        return index;
    }

    /// <summary>
    /// The tombstone a removal item stands for; synthesised from the item's revision when the optional
    /// tombstone block was omitted, so the proposal still says which revision it deletes up to.
    /// </summary>
    private static Tombstone TombstoneOf(SourceSyncDigestItem item, string key)
        => item.Tombstone ?? new Tombstone
        {
            SyncSourceId = key,
            RecordRevision = item.RecordRevision,
        };

    private static SyncRoundDecision Apply(
        string key,
        SyncMergeDirection direction,
        SourceSyncChangeKind kind,
        string reason,
        SourceSyncDigestItem? local = null,
        SourceSyncDigestItem? remote = null,
        SourceRecord? record = null,
        Tombstone? tombstone = null,
        IReadOnlyList<string>? conflictingFields = null)
        => new(
            SyncRoundBucket.Apply,
            Build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval: false));

    private static SyncRoundDecision Approval(
        string key,
        SyncMergeDirection direction,
        SourceSyncChangeKind kind,
        string reason,
        SourceSyncDigestItem? local = null,
        SourceSyncDigestItem? remote = null,
        SourceRecord? record = null,
        Tombstone? tombstone = null,
        IReadOnlyList<string>? conflictingFields = null)
        => new(
            SyncRoundBucket.Approval,
            Build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval: true));

    private static SyncRoundDecision Ignore(
        string key,
        SyncMergeDirection direction,
        SourceSyncChangeKind kind,
        string reason,
        SourceSyncDigestItem? local = null,
        SourceSyncDigestItem? remote = null,
        SourceRecord? record = null,
        Tombstone? tombstone = null,
        IReadOnlyList<string>? conflictingFields = null)
        => new(
            SyncRoundBucket.Ignore,
            Build(key, direction, kind, reason, local, remote, record, tombstone, conflictingFields, requiresApproval: false));

    private static SyncRoundEntry Build(
        string key,
        SyncMergeDirection direction,
        SourceSyncChangeKind kind,
        string reason,
        SourceSyncDigestItem? local,
        SourceSyncDigestItem? remote,
        SourceRecord? record,
        Tombstone? tombstone,
        IReadOnlyList<string>? conflictingFields,
        bool requiresApproval)
        => new()
        {
            SyncSourceId = key,
            Direction = direction,
            Kind = kind,
            Reason = reason,
            Record = record,
            Tombstone = tombstone,
            ConflictingFields = conflictingFields ?? [],
            LocalRevision = local?.RecordRevision ?? 0,
            RemoteRevision = remote?.RecordRevision ?? 0,
            RequiresApproval = requiresApproval,
        };
}

internal enum SyncRoundBucket
{
    Apply = 0,
    Approval,
    Ignore,
}

internal readonly record struct SyncRoundDecision(SyncRoundBucket Bucket, SyncRoundEntry Entry);

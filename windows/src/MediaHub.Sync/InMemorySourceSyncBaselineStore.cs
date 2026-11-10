namespace MediaHub.Sync;

/// <summary>In-memory baseline store: the reference behaviour the file store must match, and a test double.</summary>
/// <remarks>
/// <see cref="WriteCount"/> counts only saves that actually changed a stored baseline, so a test can assert that a
/// repeated round wrote nothing instead of inferring it from getters.
/// </remarks>
public sealed class InMemorySourceSyncBaselineStore : ISourceSyncBaselineStore
{
    private readonly object _gate = new();
    private readonly Dictionary<string, SourceSyncBaseline> _peers = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Number of saves or deletions that changed a stored baseline.</summary>
    /// <remarks>
    /// Deletions count: the file store rewrites its document on a successful <see cref="Forget"/> too, and a test
    /// that asserts "forgetting an absent peer wrote nothing" needs both stores to count the same events.
    /// </remarks>
    public int WriteCount { get; private set; }

    /// <summary>Peers this store knows about, in insertion order.</summary>
    public IReadOnlyList<string> KnownPeerDeviceIds
    {
        get
        {
            lock (_gate)
            {
                return _peers.Keys.ToList();
            }
        }
    }

    /// <inheritdoc />
    public SourceSyncBaseline Load(string peerDeviceId)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            return SourceSyncBaseline.Empty;
        }

        lock (_gate)
        {
            return _peers.TryGetValue(peerDeviceId.Trim(), out SourceSyncBaseline? baseline)
                ? baseline
                : SourceSyncBaseline.Empty;
        }
    }

    /// <inheritdoc />
    public SourceSyncBaselineSaveResult Save(string peerDeviceId, SourceSyncBaseline baseline)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            throw new ArgumentException("A peer device id is required.", nameof(peerDeviceId));
        }

        ArgumentNullException.ThrowIfNull(baseline);

        lock (_gate)
        {
            string key = peerDeviceId.Trim();
            _peers.TryGetValue(key, out SourceSyncBaseline? existing);
            SourceSyncBaselineMergeResult merge = SourceSyncBaselineMerge.Merge(existing, baseline);
            if (!merge.Changed)
            {
                return merge.RejectedSyncSourceIds.Count == 0
                    ? SourceSyncBaselineSaveResult.NoChange
                    : new SourceSyncBaselineSaveResult
                    {
                        Unchanged = true,
                        RejectedSyncSourceIds = merge.RejectedSyncSourceIds,
                        Reason = merge.Reason,
                    };
            }

            _peers[key] = merge.Merged;
            WriteCount++;
            return SourceSyncBaselineSaveResult.Written(merge.RejectedSyncSourceIds, merge.Reason);
        }
    }

    /// <inheritdoc />
    public bool Forget(string peerDeviceId)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            throw new ArgumentException("A peer device id is required.", nameof(peerDeviceId));
        }

        lock (_gate)
        {
            if (!_peers.Remove(peerDeviceId.Trim()))
            {
                return false;
            }

            WriteCount++;
            return true;
        }
    }
}

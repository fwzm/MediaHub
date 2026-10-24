namespace MediaHub.Sync;

/// <summary>
/// The outcome of one two-way round: what may be written, what needs the user, and what needs nothing.
/// </summary>
/// <remarks>
/// <para>
/// The three buckets partition the round. An identity appears in exactly one of them — a rule expressed once,
/// in <see cref="Create"/>, rather than repeated at every call site. That is what stops a source from being
/// written and simultaneously offered for approval, which is how a "review" screen silently applies changes.
/// </para>
/// <para>
/// <see cref="Apply"/> is what the caller may write <em>without asking</em>; it is deliberately the smallest of
/// the three. Deletions, field conflicts, unknown types and credential payloads all land in
/// <see cref="RequiresApproval"/> or <see cref="Ignored"/> instead.
/// </para>
/// </remarks>
public sealed record SyncRoundResult
{
    /// <summary>Entries that can be written immediately.</summary>
    public IReadOnlyList<SyncRoundEntry> Apply { get; init; } = [];

    /// <summary>Entries that stay proposals until the user confirms them.</summary>
    public IReadOnlyList<SyncRoundEntry> RequiresApproval { get; init; } = [];

    /// <summary>Entries that need no action: stale, already applied, or deleted on both sides.</summary>
    public IReadOnlyList<SyncRoundEntry> Ignored { get; init; } = [];

    /// <summary>Every entry, in bucket order.</summary>
    public IReadOnlyList<SyncRoundEntry> Entries => [.. Apply, .. RequiresApproval, .. Ignored];

    /// <summary>Whether the round has anything to write without asking.</summary>
    public bool HasWork => Apply.Count > 0;

    /// <summary>Whether the round produced no entries at all.</summary>
    public bool IsEmpty => Entries.Count == 0;

    /// <summary>Finds the entry for an identity in any bucket; ids are compared trimmed and case-insensitively.</summary>
    public SyncRoundEntry? Find(string? syncSourceId)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return null;
        }

        string key = syncSourceId.Trim();
        return Entries.FirstOrDefault(
            entry => string.Equals(entry.SyncSourceId, key, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Entries with <paramref name="reason"/> in any bucket.</summary>
    public IReadOnlyList<SyncRoundEntry> WithReason(string reason)
    {
        ArgumentNullException.ThrowIfNull(reason);
        return Entries.Where(entry => string.Equals(entry.Reason, reason, StringComparison.Ordinal)).ToList();
    }

    /// <summary>Builds a result, refusing to let one identity sit in two buckets.</summary>
    /// <exception cref="InvalidOperationException">An identity appears more than once across the buckets.</exception>
    public static SyncRoundResult Create(
        IReadOnlyList<SyncRoundEntry> apply,
        IReadOnlyList<SyncRoundEntry> requiresApproval,
        IReadOnlyList<SyncRoundEntry> ignored)
    {
        ArgumentNullException.ThrowIfNull(apply);
        ArgumentNullException.ThrowIfNull(requiresApproval);
        ArgumentNullException.ThrowIfNull(ignored);

        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (SyncRoundEntry entry in apply.Concat(requiresApproval).Concat(ignored))
        {
            ArgumentNullException.ThrowIfNull(entry);
            if (!seen.Add(entry.SyncSourceId.Trim()))
            {
                throw new InvalidOperationException(
                    "Sync round entry for '" + entry.SyncSourceId + "' appears in more than one bucket.");
            }
        }

        return new SyncRoundResult
        {
            Apply = apply,
            RequiresApproval = requiresApproval,
            Ignored = ignored,
        };
    }
}

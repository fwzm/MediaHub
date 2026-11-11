namespace MediaHub.Sync;

/// <summary>
/// The per-source tallies a merge preview shows before anything is written.
/// </summary>
/// <remarks>
/// <para>
/// Counting is separated from <see cref="SourceSyncPlanner"/> so the preview's numbers can be asserted
/// without a network round trip, and so the UI never has to re-implement the grouping: two places counting
/// the same plan is how a preview ends up disagreeing with what the apply step would do.
/// </para>
/// <para>
/// Every bucket maps to exactly one <see cref="SourceSyncAction"/>, so the buckets always add up to
/// <see cref="Total"/>. <see cref="Ambiguous"/> is a subset of <see cref="Conflict"/>: an ambiguous legacy
/// match is one way to conflict, and showing it separately is what tells the user two sources look identical
/// instead of merely disagreeing.
/// </para>
/// </remarks>
public sealed record SourceSyncPreviewSummary
{
    /// <summary>How many candidates the plan contains (records plus tombstones).</summary>
    public int Total { get; init; }

    /// <summary>Sources the peer has and this device does not.</summary>
    public int Create { get; init; }

    /// <summary>Sources this device has with an older revision.</summary>
    public int Update { get; init; }

    /// <summary>Sources that disagree and may not overwrite each other.</summary>
    public int Conflict { get; init; }

    /// <summary>Conflicts caused by several local sources matching one record equally well.</summary>
    public int Ambiguous { get; init; }

    /// <summary>Sources whose kind this build cannot represent.</summary>
    public int Unsupported { get; init; }

    /// <summary>Tombstones proposing a local deletion.</summary>
    public int Delete { get; init; }

    /// <summary>Sources already in step (or older than the local copy).</summary>
    public int Unchanged { get; init; }

    /// <summary>Pre-W6 local sources the planner thinks this record probably is.</summary>
    public int Adopt { get; init; }

    /// <summary>Decisions that stay proposals until the user confirms them.</summary>
    public int RequiresConfirmation { get; init; }

    /// <summary>Whether the plan would create, update or delete anything at all.</summary>
    public bool HasChanges => Create + Update + Delete > 0;

    /// <summary>Counts a plan's items.</summary>
    public static SourceSyncPreviewSummary FromPlan(SourceSyncPlan plan)
    {
        ArgumentNullException.ThrowIfNull(plan);
        return FromCandidates(plan.Items);
    }

    /// <summary>Counts candidates directly, for callers that already hold the list.</summary>
    public static SourceSyncPreviewSummary FromCandidates(IEnumerable<SourceSyncCandidate> candidates)
    {
        ArgumentNullException.ThrowIfNull(candidates);

        int total = 0;
        int create = 0;
        int update = 0;
        int conflict = 0;
        int ambiguous = 0;
        int unsupported = 0;
        int delete = 0;
        int unchanged = 0;
        int adopt = 0;
        int confirmation = 0;

        foreach (SourceSyncCandidate candidate in candidates)
        {
            ArgumentNullException.ThrowIfNull(candidate);
            total++;

            switch (candidate.Action)
            {
                case SourceSyncAction.Create: create++; break;
                case SourceSyncAction.Update: update++; break;
                case SourceSyncAction.Conflict:
                    conflict++;
                    if (string.Equals(candidate.Reason, SourceSyncReasons.AmbiguousLegacyIdentity, StringComparison.Ordinal))
                    {
                        ambiguous++;
                    }

                    break;
                case SourceSyncAction.Unsupported: unsupported++; break;
                case SourceSyncAction.Delete: delete++; break;
                case SourceSyncAction.Adopt: adopt++; break;
                case SourceSyncAction.NoChange:
                case SourceSyncAction.IgnoreStale:
                    unchanged++;
                    break;
            }

            if (candidate.RequiresUserApproval)
            {
                confirmation++;
            }
        }

        return new SourceSyncPreviewSummary
        {
            Total = total,
            Create = create,
            Update = update,
            Conflict = conflict,
            Ambiguous = ambiguous,
            Unsupported = unsupported,
            Delete = delete,
            Unchanged = unchanged,
            Adopt = adopt,
            RequiresConfirmation = confirmation,
        };
    }
}

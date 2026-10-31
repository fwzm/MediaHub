namespace MediaHub.Sync;

/// <summary>What <see cref="SourceSyncLocalBridge.Apply"/> did with one planner candidate.</summary>
public sealed record SourceSyncApplyOutcome
{
    /// <summary>The proposal this line reports on, unchanged.</summary>
    public SourceSyncCandidate Candidate { get; init; } = new();

    /// <summary>Whether the store was written for this candidate.</summary>
    public bool Applied { get; init; }
}

/// <summary>The result of applying one incoming envelope.</summary>
/// <remarks>
/// Everything that was not applied is still here, as a proposal: conflicts, deletions and adoptions are shown to
/// the user rather than resolved silently (D5). <see cref="AppliedCount"/> is the number of writes, not the
/// number of differences.
/// </remarks>
public sealed record SourceSyncApplyResult
{
    /// <summary>The full plan, including entries that were deliberately not applied.</summary>
    public SourceSyncPlan Plan { get; init; } = new();

    /// <summary>One line per planner candidate, in plan order.</summary>
    public IReadOnlyList<SourceSyncApplyOutcome> Outcomes { get; init; } = [];

    /// <summary>How many candidates actually changed the store.</summary>
    public int AppliedCount => Outcomes.Count(outcome => outcome.Applied);

    /// <summary>Entries the user has to decide on; none of them were applied.</summary>
    public IReadOnlyList<SourceSyncCandidate> Proposals => Plan.RequiringApproval;

    /// <summary>Entries where the two devices disagree; none of them were applied.</summary>
    public IReadOnlyList<SourceSyncCandidate> Conflicts => Plan.Conflicts;
}

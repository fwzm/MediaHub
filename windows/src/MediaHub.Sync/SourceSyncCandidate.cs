namespace MediaHub.Sync;

/// <summary>
/// One suggested decision: what to do with one incoming record or tombstone, and why.
/// </summary>
/// <remarks>
/// A candidate is a proposal, never an applied change. Nothing here mutates a source; W6-2 renders the list,
/// lets the user approve the entries that need approval, and only then writes. That split is what keeps
/// <see cref="RequiresUserApproval"/> from being decorative.
/// </remarks>
public sealed record SourceSyncCandidate
{
    /// <summary>Suggested action.</summary>
    public SourceSyncAction Action { get; init; }

    /// <summary>Stable reason code from <see cref="SourceSyncReasons"/>.</summary>
    public string Reason { get; init; } = "";

    /// <summary>Incoming <c>syncSourceId</c>, when the incoming thing was a record.</summary>
    public string? SyncSourceId { get; init; }

    /// <summary>Local <c>ServerId</c> this candidate applies to, when one was matched.</summary>
    public string? ServerId { get; init; }

    /// <summary>Incoming source type exactly as it arrived, including values this build does not know.</summary>
    public string? SourceType { get; init; }

    /// <summary>Whether <see cref="SourceType"/> is a value of <see cref="SyncSourceTypes"/>.</summary>
    public bool IsKnownSourceType { get; init; }

    /// <summary>Incoming display name.</summary>
    public string? DisplayName { get; init; }

    /// <summary>The primary endpoint after <see cref="SyncUrlNormalizer"/>, or null when it does not normalise.</summary>
    public string? NormalizedUrl { get; init; }

    /// <summary>Incoming revision, when the incoming thing carried one.</summary>
    public long? RemoteRevision { get; init; }

    /// <summary>Local revision of the matched source, when one was matched.</summary>
    public long? LocalRevision { get; init; }

    /// <summary>
    /// Whether applying this candidate needs an explicit user decision. Conflicts, deletions, adoptions and
    /// ambiguous identities always do; a stale record never does (there is nothing to decide — it is ignored).
    /// </summary>
    public bool RequiresUserApproval { get; init; }

    /// <summary>
    /// For ambiguous candidates: the local sources that matched equally well. Never contains a single entry —
    /// a unique match is an <see cref="SourceSyncAction.Adopt"/> instead.
    /// </summary>
    public IReadOnlyList<string> CandidateServerIds { get; init; } = [];
}

/// <summary>The whole proposal for one incoming envelope.</summary>
public sealed record SourceSyncPlan
{
    /// <summary>Record candidates first, in envelope order, then tombstone candidates.</summary>
    public IReadOnlyList<SourceSyncCandidate> Items { get; init; } = [];

    /// <summary>
    /// Addresses the inbound records carried that this client will not import, each with a stable reason. Windows
    /// stores one address per source, so a multi-endpoint record loses the others — listed here instead of dropped.
    /// </summary>
    public IReadOnlyList<SourceSyncNotImportedEndpoint> NotImportedEndpoints { get; init; } = [];

    /// <summary>How many addresses will not be imported; the number a plan summary shows.</summary>
    public int NotImportedEndpointCount => NotImportedEndpoints.Count;

    /// <summary>Entries the user has to decide on before anything is written.</summary>
    public IReadOnlyList<SourceSyncCandidate> RequiringApproval
        => Items.Where(item => item.RequiresUserApproval).ToList();

    /// <summary>Entries where the two devices disagree.</summary>
    public IReadOnlyList<SourceSyncCandidate> Conflicts
        => Items.Where(item => item.Action == SourceSyncAction.Conflict).ToList();

    /// <summary>How many entries carry <paramref name="action"/>.</summary>
    public int CountOf(SourceSyncAction action) => Items.Count(item => item.Action == action);
}

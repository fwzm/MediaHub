namespace MediaHub.Sync;

/// <summary>What the user decided about one entry of a plan or a round.</summary>
public enum SourceSyncApprovalChoice
{
    /// <summary>Leave the entry alone. This is the default for anything that needs approval.</summary>
    Skip = 0,

    /// <summary>Write the entry. The user has to say this explicitly for anything that needs approval.</summary>
    Allow = 1,
}

/// <summary>One decision the user made about one source, keyed by <c>syncSourceId</c> only.</summary>
/// <remarks>
/// Never keyed by display name or by row index: a name is not an identity and an index changes when a row is
/// filtered out, which is exactly how an approval screen ends up approving something the user never saw.
/// </remarks>
public sealed record SourceSyncApprovalDecision
{
    /// <summary>Identity the decision is about; compared trimmed and case-insensitively.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Whether the entry may be written.</summary>
    public SourceSyncApprovalChoice Choice { get; init; }

    /// <summary>Convenience factory for an allow decision.</summary>
    public static SourceSyncApprovalDecision Allow(string syncSourceId) => new()
    {
        SyncSourceId = syncSourceId,
        Choice = SourceSyncApprovalChoice.Allow,
    };

    /// <summary>Convenience factory for a skip decision.</summary>
    public static SourceSyncApprovalDecision Skip(string syncSourceId) => new()
    {
        SyncSourceId = syncSourceId,
        Choice = SourceSyncApprovalChoice.Skip,
    };
}

/// <summary>
/// One entry as the approval screen sees it: identity, action, reason, and whether a human must decide.
/// </summary>
/// <remarks>
/// Deliberately carries no prose: the UI localises <see cref="Reason"/> through its own wording table, so the
/// gate and its tests stay free of display strings.
/// </remarks>
public sealed record SourceSyncApprovalItem
{
    /// <summary>Identity of the source this entry is about.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Stable reason code from <see cref="SourceSyncReasons"/> or <see cref="SyncRoundReasons"/>.</summary>
    public string Reason { get; init; } = "";

    /// <summary>What the planner or merger proposed to do.</summary>
    public SourceSyncAction Action { get; init; }

    /// <summary>Whether the source needs an explicit decision before it may be written.</summary>
    public bool RequiresApproval { get; init; }

    /// <summary>Display name of the source, for the row label only.</summary>
    public string? DisplayName { get; init; }

    /// <summary>Local server id the entry maps onto, when one matched.</summary>
    public string? ServerId { get; init; }

    /// <summary>Incoming source type verbatim, including values this build does not know.</summary>
    public string? SourceType { get; init; }

    /// <summary>Primary endpoint after normalisation, when the entry carried one.</summary>
    public string? NormalizedUrl { get; init; }

    /// <summary>Revision this device holds, when the plan knew it.</summary>
    /// <remarks>
    /// Carried for the row text only: the gate never compares revisions itself, because deciding which side is
    /// newer is the planner's job and doing it twice would let the two disagree.
    /// </remarks>
    public long? LocalRevision { get; init; }

    /// <summary>Revision the peer claims, when the plan knew it.</summary>
    public long? RemoteRevision { get; init; }

    /// <summary>Adapts a single-direction planner candidate.</summary>
    public static SourceSyncApprovalItem FromCandidate(SourceSyncCandidate candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        return new SourceSyncApprovalItem
        {
            SyncSourceId = candidate.SyncSourceId?.Trim() ?? "",
            Reason = candidate.Reason,
            Action = candidate.Action,
            RequiresApproval = candidate.RequiresUserApproval,
            DisplayName = candidate.DisplayName,
            ServerId = candidate.ServerId,
            SourceType = candidate.SourceType,
            NormalizedUrl = candidate.NormalizedUrl,
            LocalRevision = candidate.LocalRevision,
            RemoteRevision = candidate.RemoteRevision,
        };
    }

    /// <summary>Adapts one entry of a bidirectional round.</summary>
    public static SourceSyncApprovalItem FromRoundEntry(SyncRoundEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        return new SourceSyncApprovalItem
        {
            SyncSourceId = entry.SyncSourceId?.Trim() ?? "",
            Reason = entry.Reason,
            Action = ActionOf(entry.Kind, entry.Reason),
            RequiresApproval = entry.RequiresApproval,
            DisplayName = entry.Record?.DisplayName,
            NormalizedUrl = PrimaryUrl(entry.Record),
            SourceType = entry.Record?.SourceType.Raw,
            LocalRevision = entry.LocalRevision,
            RemoteRevision = entry.RemoteRevision,
        };
    }

    private static SourceSyncAction ActionOf(SourceSyncChangeKind kind, string reason) => kind switch
    {
        SourceSyncChangeKind.Added => SourceSyncAction.Create,
        SourceSyncChangeKind.Updated => SourceSyncAction.Update,
        SourceSyncChangeKind.Removed => SourceSyncAction.Delete,
        _ => string.Equals(reason, SyncRoundReasons.UnknownSourceType, StringComparison.Ordinal) ||
             string.Equals(reason, SyncRoundReasons.NotSupportedOnWindows, StringComparison.Ordinal)
            ? SourceSyncAction.Unsupported
            : SourceSyncAction.NoChange,
    };

    private static string? PrimaryUrl(SourceRecord? record)
    {
        if (record is null || record.Endpoints.Count == 0)
        {
            return null;
        }

        SourceEndpoint endpoint = record.Endpoints.FirstOrDefault(candidate => candidate.IsPrimary)
            ?? record.Endpoints.OrderBy(candidate => candidate.SortOrder).First();
        return endpoint.Url;
    }
}

/// <summary>What the gate decided: what may be written, what is dropped, and what can never be written.</summary>
/// <remarks>
/// The three buckets partition the input, so a source cannot be both written and offered for approval. A
/// withheld entry is one the client must not write at all, whatever the user clicked; its reason is repeated in
/// <see cref="BlockingReasons"/> so the screen can explain why the button will not move.
/// </remarks>
public sealed record SourceSyncApprovalResult
{
    /// <summary>Entries the user allowed (or that needed no approval) and that may be written.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> Applied { get; init; } = [];

    /// <summary>Entries left alone, either by the user or because there is nothing to do.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> Skipped { get; init; } = [];

    /// <summary>Entries this client must never write, whatever the user answered.</summary>
    public IReadOnlyList<SourceSyncApprovalItem> Withheld { get; init; } = [];

    /// <summary>Distinct reasons behind <see cref="Withheld"/>, in first-appearance order.</summary>
    public IReadOnlyList<string> BlockingReasons { get; init; } = [];

    /// <summary>True when the user allowed at least one entry that carried a known risk.</summary>
    public bool AcknowledgedRisk { get; init; }

    /// <summary>How many applied entries needed an explicit decision.</summary>
    public int AppliedWithRisk { get; init; }

    /// <summary>Whether the apply button may be enabled: something is left to write.</summary>
    public bool CanApply => Applied.Count > 0;

    /// <summary>Whether anything was withheld; the screen must show the reasons even when other entries apply.</summary>
    public bool IsBlocked => Withheld.Count > 0;

    /// <summary>Number of entries that would be written.</summary>
    public int AppliedCount => Applied.Count;

    /// <summary>Number of entries left alone.</summary>
    public int SkippedCount => Skipped.Count;

    /// <summary>Number of entries this client refuses to write.</summary>
    public int WithheldCount => Withheld.Count;

    /// <summary>True when nothing was applied, skipped or withheld.</summary>
    public bool IsEmpty => Applied.Count == 0 && Skipped.Count == 0 && Withheld.Count == 0;

    /// <summary>Finds the bucket of one identity, or null when the gate never saw it.</summary>
    public SourceSyncApprovalItem? Find(string? syncSourceId)
    {
        string? key = syncSourceId?.Trim();
        if (string.IsNullOrEmpty(key))
        {
            return null;
        }

        return Applied.Concat(Skipped).Concat(Withheld).FirstOrDefault(
            item => string.Equals(item.SyncSourceId, key, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>True when the identity ended up in <see cref="Withheld"/>.</summary>
    public bool IsWithheld(string? syncSourceId) => Withheld.Any(
        item => string.Equals(item.SyncSourceId, syncSourceId?.Trim(), StringComparison.OrdinalIgnoreCase));

    /// <summary>True when the identity ended up in <see cref="Applied"/>.</summary>
    public bool IsApplied(string? syncSourceId) => Applied.Any(
        item => string.Equals(item.SyncSourceId, syncSourceId?.Trim(), StringComparison.OrdinalIgnoreCase));

    /// <summary>Empty result, used before the user has answered anything.</summary>
    public static SourceSyncApprovalResult None { get; } = new();

    /// <inheritdoc />
    public override string ToString() => string.Create(
        System.Globalization.CultureInfo.InvariantCulture,
        $"Approval(applied={Applied.Count}, skipped={Skipped.Count}, withheld={Withheld.Count}, canApply={CanApply}, risk={AcknowledgedRisk})");
}

/// <summary>
/// The gate between a plan and a write: everything that reaches the store passes through here, once.
/// </summary>
/// <remarks>
/// <para>
/// Three reason codes are never allow-able, because the client cannot honour them without doing something it
/// must not do: <c>secrets-require-approval</c> would mean importing a credential that belongs to another
/// machine's DPAPI scope, and <c>unknown-source-type</c>/<c>not-supported-on-windows</c> would mean writing a
/// source this build cannot connect, address or verify. Clicking "allow" cannot create that capability, so the
/// answer is a refusal with a reason rather than a button that silently writes a broken row.
/// </para>
/// <para>
/// Everything else that needs approval stays allow-able: deletions, field conflicts, same-revision conflicts and
/// ambiguous legacy identities are all decisions a human may legitimately make, so they are applied with
/// <see cref="SourceSyncApprovalResult.AcknowledgedRisk"/> set instead of being forbidden.
/// </para>
/// <para>
/// The gate is pure and deterministic: the same items and decisions produce the same result, in input order,
/// keyed by <c>syncSourceId</c> alone.
/// </para>
/// </remarks>
public static class SourceSyncApprovalGate
{
    /// <summary>Prefix that marks ambiguous legacy-identity reasons; they are allow-able with an acknowledged risk.</summary>
    public const string AmbiguousReasonPrefix = "ambiguous-";

    private static readonly string[] NeverAllowable = 
    [
        SyncRoundReasons.SecretsRequireApproval,
        SyncRoundReasons.UnknownSourceType,
        SyncRoundReasons.NotSupportedOnWindows,
    ];

    private static readonly string[] Risky =
    [
        SyncRoundReasons.DeleteVsUpdate,
        SyncRoundReasons.UpdateVsDelete,
        SyncRoundReasons.FieldConflict,
        SyncRoundReasons.SameRevisionDifferentContent,
    ];

    /// <summary>Reasons the gate refuses to write no matter what the user answered.</summary>
    public static IReadOnlyList<string> NeverAllowableReasons => NeverAllowable;

    /// <summary>Reasons that may be allowed, but only with <see cref="SourceSyncApprovalResult.AcknowledgedRisk"/>.</summary>
    public static IReadOnlyList<string> RiskyReasons => Risky;

    /// <summary>Whether a reason can never be allowed.</summary>
    public static bool IsNeverAllowable(string? reason)
    {
        string key = reason?.Trim() ?? "";
        return NeverAllowable.Any(code => string.Equals(code, key, StringComparison.Ordinal));
    }

    /// <summary>Whether a reason is allow-able but known to be risky; ambiguous identities count as risky.</summary>
    public static bool IsRisky(string? reason)
    {
        string key = reason?.Trim() ?? "";
        if (key.StartsWith(AmbiguousReasonPrefix, StringComparison.Ordinal))
        {
            return true;
        }

        return Risky.Any(code => string.Equals(code, key, StringComparison.Ordinal));
    }

    /// <summary>Whether an action is a write at all; stale and unchanged entries never are.</summary>
    public static bool IsApplicable(SourceSyncAction action) =>
        action is SourceSyncAction.Create or SourceSyncAction.Update or SourceSyncAction.Delete
            or SourceSyncAction.Adopt or SourceSyncAction.Conflict;

    /// <summary>Runs the gate over one plan.</summary>
    public static SourceSyncApprovalResult Decide(
        SourceSyncPlan plan,
        IReadOnlyList<SourceSyncApprovalDecision>? decisions = null)
    {
        ArgumentNullException.ThrowIfNull(plan);
        return Decide(plan.Items.Select(SourceSyncApprovalItem.FromCandidate).ToList(), decisions);
    }

    /// <summary>Runs the gate over one two-way round; ignored entries are reported as skipped, never written.</summary>
    public static SourceSyncApprovalResult Decide(
        SyncRoundResult round,
        IReadOnlyList<SourceSyncApprovalDecision>? decisions = null)
    {
        ArgumentNullException.ThrowIfNull(round);
        var items = round.Apply.Concat(round.RequiresApproval)
            .Select(SourceSyncApprovalItem.FromRoundEntry)
            .ToList();
        return Decide(items, decisions);
    }

    /// <summary>Runs the gate over explicit items and the user's per-entry choices.</summary>
    /// <exception cref="ArgumentException">An item has a blank <see cref="SourceSyncApprovalItem.SyncSourceId"/>.</exception>
    public static SourceSyncApprovalResult Decide(
        IReadOnlyList<SourceSyncApprovalItem> items,
        IReadOnlyList<SourceSyncApprovalDecision>? decisions = null)
    {
        ArgumentNullException.ThrowIfNull(items);

        // Last decision wins: the screen sends one row per identity, and a stray duplicate must not make the
        // result depend on dictionary iteration order.
        var choices = new Dictionary<string, SourceSyncApprovalChoice>(StringComparer.OrdinalIgnoreCase);
        if (decisions is not null)
        {
            foreach (SourceSyncApprovalDecision decision in decisions)
            {
                ArgumentNullException.ThrowIfNull(decision);
                string id = decision.SyncSourceId?.Trim() ?? "";
                if (id.Length == 0)
                {
                    throw new ArgumentException("An approval decision needs a syncSourceId.", nameof(decisions));
                }

                choices[id] = decision.Choice;
            }
        }

        var applied = new List<SourceSyncApprovalItem>();
        var skipped = new List<SourceSyncApprovalItem>();
        var withheld = new List<SourceSyncApprovalItem>();
        var blocking = new List<string>();
        bool honouredRisk = false;
        int appliedWithRisk = 0;

        foreach (SourceSyncApprovalItem item in items)
        {
            ArgumentNullException.ThrowIfNull(item);
            string id = item.SyncSourceId?.Trim() ?? "";
            if (id.Length == 0)
            {
                throw new ArgumentException("An approval item needs a syncSourceId.", nameof(items));
            }

            string reason = item.Reason?.Trim() ?? "";

            // The refusal comes first: a never-allow-able entry must be withheld even if a caller passes an
            // explicit Allow for it, so a stale decision list cannot smuggle a credential or an unknown type in.
            if (IsNeverAllowable(reason))
            {
                withheld.Add(item);
                if (!blocking.Contains(reason, StringComparer.Ordinal))
                {
                    blocking.Add(reason);
                }

                continue;
            }

            if (!IsApplicable(item.Action))
            {
                skipped.Add(item);
                continue;
            }

            bool allow;
            if (choices.TryGetValue(id, out SourceSyncApprovalChoice choice))
            {
                allow = choice == SourceSyncApprovalChoice.Allow;
            }
            else
            {
                // Anything marked as needing a decision defaults to Skip; everything else may proceed.
                allow = !item.RequiresApproval;
            }

            if (!allow)
            {
                skipped.Add(item);
                continue;
            }

            applied.Add(item);
            if (item.RequiresApproval)
            {
                appliedWithRisk++;
                honouredRisk |= IsRisky(reason);
            }
        }

        return new SourceSyncApprovalResult
        {
            Applied = applied,
            Skipped = skipped,
            Withheld = withheld,
            BlockingReasons = blocking,
            AcknowledgedRisk = honouredRisk,
            AppliedWithRisk = appliedWithRisk,
        };
    }
}

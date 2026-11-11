namespace MediaHub.Tests;

using MediaHub.Sync;
using Xunit;

/// <summary>
/// The approval gate is the only path from "the peer sent this" to "this device wrote it", so every rule it
/// enforces is pinned here: defaults, refusals, risk acknowledgement and identity handling.
/// </summary>
public sealed class SourceSyncApprovalGateTests
{
    private const string SecretsId = "local-secret0001";
    private const string UnknownId = "local-unknown001";
    private const string UnsupportedId = "local-nas0000001";
    private const string SafeId = "local-safe000001";
    private const string ConflictId = "local-conflict01";

    [Fact]
    public void EmptyPlan_OffersNothingToApply()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(Plan());

        Assert.True(result.IsEmpty);
        Assert.False(result.CanApply);
        Assert.False(result.IsBlocked);
        Assert.Empty(result.Applied);
        Assert.Empty(result.Skipped);
        Assert.Empty(result.Withheld);
        Assert.Empty(result.BlockingReasons);
    }

    [Fact]
    public void EntryNeedingApprovalDefaultsToSkip()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.DeleteVsUpdate, approval: true)));

        Assert.Empty(result.Applied);
        SourceSyncApprovalItem skipped = Assert.Single(result.Skipped);
        Assert.Equal(ConflictId, skipped.SyncSourceId);
        Assert.False(result.CanApply);
        Assert.False(result.AcknowledgedRisk);
    }

    [Fact]
    public void ExplicitAllowMovesAnApprovalEntryIntoAppliedAndFlagsTheRisk()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true)),
            [SourceSyncApprovalDecision.Allow(ConflictId)]);

        SourceSyncApprovalItem applied = Assert.Single(result.Applied);
        Assert.Equal(ConflictId, applied.SyncSourceId);
        Assert.True(result.CanApply);
        Assert.True(result.AcknowledgedRisk);
        Assert.Equal(1, result.AppliedWithRisk);
        Assert.Empty(result.Withheld);
    }

    [Fact]
    public void SafeEntryAppliesWithoutADecision()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange)));

        Assert.True(result.CanApply);
        Assert.Single(result.Applied);
        Assert.False(result.AcknowledgedRisk);
        Assert.Equal(0, result.AppliedWithRisk);
    }

    [Fact]
    public void SkippingEverythingLeavesNothingToApply()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(
                Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange),
                Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true)),
            [
                SourceSyncApprovalDecision.Skip(SafeId),
                SourceSyncApprovalDecision.Skip(ConflictId),
            ]);

        Assert.Empty(result.Applied);
        Assert.Equal(2, result.SkippedCount);
        Assert.False(result.CanApply);
        Assert.True(result.IsEmpty is false);
    }

    [Fact]
    public void AllowingEverythingAppliesEveryAllowableEntry()
    {
        SourceSyncPlan plan = Plan(
            Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange),
            Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.UpdateVsDelete, approval: true),
            Candidate("local-adopt00001", SourceSyncAction.Adopt, SourceSyncReasons.LegacyIdentityMatch, approval: true));

        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            plan,
            [
                SourceSyncApprovalDecision.Allow(SafeId),
                SourceSyncApprovalDecision.Allow(ConflictId),
                SourceSyncApprovalDecision.Allow("local-adopt00001"),
            ]);

        Assert.Equal(3, result.AppliedCount);
        Assert.Empty(result.Skipped);
        Assert.True(result.CanApply);
        Assert.True(result.AcknowledgedRisk);
    }

    [Fact]
    public void MixedDecisionsKeepTheThreeBucketsDisjoint()
    {
        SourceSyncPlan plan = Plan(
            Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange),
            Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true),
            Candidate(SecretsId, SourceSyncAction.Update, SyncRoundReasons.SecretsRequireApproval, approval: true));

        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            plan,
            [
                SourceSyncApprovalDecision.Allow(ConflictId),
                SourceSyncApprovalDecision.Allow(SecretsId),
            ]);

        Assert.Equal([SafeId, ConflictId], result.Applied.Select(item => item.SyncSourceId));
        Assert.Equal([SecretsId], result.Withheld.Select(item => item.SyncSourceId));
        Assert.Empty(result.Skipped);
        Assert.Equal(3, result.AppliedCount + result.SkippedCount + result.WithheldCount);
        Assert.Equal([SyncRoundReasons.SecretsRequireApproval], result.BlockingReasons);
        Assert.True(result.AcknowledgedRisk);
    }

    [Theory]
    [InlineData(SyncRoundReasons.SecretsRequireApproval)]
    [InlineData(SyncRoundReasons.UnknownSourceType)]
    [InlineData(SyncRoundReasons.NotSupportedOnWindows)]
    public void NeverAllowableReasonsAreWithheldEvenWhenAllowed(string reason)
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(SecretsId, SourceSyncAction.Update, reason, approval: true)),
            [SourceSyncApprovalDecision.Allow(SecretsId)]);

        Assert.Empty(result.Applied);
        Assert.Empty(result.Skipped);
        SourceSyncApprovalItem withheld = Assert.Single(result.Withheld);
        Assert.Equal(reason, withheld.Reason);
        Assert.False(result.CanApply);
        Assert.True(result.IsBlocked);
        Assert.Equal([reason], result.BlockingReasons);
        Assert.True(result.IsWithheld(SecretsId));
        Assert.False(result.IsApplied(SecretsId));
    }

    [Fact]
    public void NeverAllowableReasonsAreListedWithStableCodes()
    {
        Assert.Equal(
            [SyncRoundReasons.SecretsRequireApproval, SyncRoundReasons.UnknownSourceType, SyncRoundReasons.NotSupportedOnWindows],
            SourceSyncApprovalGate.NeverAllowableReasons);

        Assert.True(SourceSyncApprovalGate.IsNeverAllowable(SyncRoundReasons.SecretsRequireApproval));
        Assert.True(SourceSyncApprovalGate.IsNeverAllowable("  " + SyncRoundReasons.NotSupportedOnWindows + " "));
        Assert.False(SourceSyncApprovalGate.IsNeverAllowable(SyncRoundReasons.FieldConflict));
        Assert.False(SourceSyncApprovalGate.IsNeverAllowable(null));
    }

    [Theory]
    [InlineData(SyncRoundReasons.DeleteVsUpdate)]
    [InlineData(SyncRoundReasons.UpdateVsDelete)]
    [InlineData(SyncRoundReasons.FieldConflict)]
    [InlineData(SyncRoundReasons.SameRevisionDifferentContent)]
    [InlineData(SourceSyncReasons.AmbiguousLegacyIdentity)]
    [InlineData("ambiguous-something-new")]
    public void RiskyReasonsAreAllowableWithAnAcknowledgedRisk(string reason)
    {
        Assert.False(SourceSyncApprovalGate.IsNeverAllowable(reason));
        Assert.True(SourceSyncApprovalGate.IsRisky(reason));

        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(ConflictId, SourceSyncAction.Conflict, reason, approval: true)),
            [SourceSyncApprovalDecision.Allow(ConflictId)]);

        Assert.Single(result.Applied);
        Assert.True(result.AcknowledgedRisk);
        Assert.Equal(1, result.AppliedWithRisk);
    }

    [Fact]
    public void NothingIsWithheldWithoutANeverAllowableReason()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(
                Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange),
                Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true)),
            [SourceSyncApprovalDecision.Allow(ConflictId)]);

        Assert.Empty(result.Withheld);
        Assert.Empty(result.BlockingReasons);
        Assert.False(result.IsBlocked);
    }

    [Theory]
    [InlineData(SourceSyncAction.NoChange)]
    [InlineData(SourceSyncAction.IgnoreStale)]
    public void ActionsThatWriteNothingStaySkippedEvenWhenAllowed(SourceSyncAction action)
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(SafeId, action, SyncRoundReasons.AlreadyApplied)),
            [SourceSyncApprovalDecision.Allow(SafeId)]);

        Assert.Empty(result.Applied);
        Assert.Single(result.Skipped);
        Assert.False(result.CanApply);
    }

    [Fact]
    public void DecisionsMatchIdentityTrimmedAndCaseInsensitively()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate("Local-ABC0000001", SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true)),
            [SourceSyncApprovalDecision.Allow("  local-abc0000001 ")]);

        Assert.Single(result.Applied);
        Assert.True(result.CanApply);
    }

    [Fact]
    public void DuplicateDecisionsUseTheLastOneSoOrderCannotChangeTheAnswer()
    {
        SourceSyncPlan plan = Plan(
            Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true));

        SourceSyncApprovalResult lastAllow = SourceSyncApprovalGate.Decide(
            plan,
            [SourceSyncApprovalDecision.Skip(ConflictId), SourceSyncApprovalDecision.Allow(ConflictId)]);
        SourceSyncApprovalResult lastSkip = SourceSyncApprovalGate.Decide(
            plan,
            [SourceSyncApprovalDecision.Allow(ConflictId), SourceSyncApprovalDecision.Skip(ConflictId)]);

        Assert.Single(lastAllow.Applied);
        Assert.Empty(lastSkip.Applied);
    }

    [Fact]
    public void BlankIdentitiesAreRefusedInsteadOfSilentlyApplied()
    {
        Assert.Throws<ArgumentException>(() => SourceSyncApprovalGate.Decide(
            Plan(Candidate("   ", SourceSyncAction.Update, SyncRoundReasons.PushLocalChange))));

        Assert.Throws<ArgumentException>(() => SourceSyncApprovalGate.Decide(
            Plan(Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange)),
            [SourceSyncApprovalDecision.Allow("")]));
    }

    [Fact]
    public void SameInputProducesTheSameOutput()
    {
        SourceSyncPlan plan = Plan(
            Candidate(SafeId, SourceSyncAction.Update, SyncRoundReasons.PushLocalChange),
            Candidate(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, approval: true),
            Candidate(SecretsId, SourceSyncAction.Update, SyncRoundReasons.SecretsRequireApproval, approval: true));
        SourceSyncApprovalDecision[] decisions =
        [
            SourceSyncApprovalDecision.Allow(ConflictId),
            SourceSyncApprovalDecision.Skip(SafeId),
        ];

        SourceSyncApprovalResult first = SourceSyncApprovalGate.Decide(plan, decisions);
        SourceSyncApprovalResult second = SourceSyncApprovalGate.Decide(plan, decisions);

        Assert.Equal(first.ToString(), second.ToString());
        Assert.Equal(first.Applied.Select(item => item.SyncSourceId), second.Applied.Select(item => item.SyncSourceId));
        Assert.Equal(first.Skipped.Select(item => item.SyncSourceId), second.Skipped.Select(item => item.SyncSourceId));
        Assert.Equal(first.BlockingReasons, second.BlockingReasons);
        Assert.Equal(first.CanApply, second.CanApply);
    }

    [Fact]
    public void RoundResultIgnoresTheIgnoredBucketAndNeverAppliesIt()
    {
        SyncRoundResult round = SyncRoundResult.Create(
            [RoundEntry(SafeId, SourceSyncAction.Update, SyncRoundReasons.ApplyRemoteChange, SourceSyncChangeKind.Updated)],
            [RoundEntry(ConflictId, SourceSyncAction.Conflict, SyncRoundReasons.FieldConflict, SourceSyncChangeKind.Updated, approval: true)],
            [RoundEntry("local-ignored001", SourceSyncAction.NoChange, SyncRoundReasons.StaleRemote, SourceSyncChangeKind.Updated)]);

        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            round,
            [SourceSyncApprovalDecision.Allow(ConflictId)]);

        Assert.Equal([SafeId, ConflictId], result.Applied.Select(item => item.SyncSourceId));
        Assert.Equal(2, result.AppliedCount + result.SkippedCount + result.WithheldCount);
        Assert.Null(result.Find("local-ignored001"));
        Assert.False(result.IsWithheld("local-ignored001"));
    }

    [Fact]
    public void RoundResultWithheldSecretsCannotBeAllowed()
    {
        SyncRoundResult round = SyncRoundResult.Create(
            [],
            [RoundEntry(SecretsId, SourceSyncAction.Update, SyncRoundReasons.SecretsRequireApproval, SourceSyncChangeKind.Added, approval: true)],
            []);

        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            round,
            [SourceSyncApprovalDecision.Allow(SecretsId)]);

        Assert.Empty(result.Applied);
        Assert.Single(result.Withheld);
        Assert.False(result.CanApply);
        Assert.Equal([SyncRoundReasons.SecretsRequireApproval], result.BlockingReasons);
    }

    [Fact]
    public void BlockingReasonsAreDistinctAndKeepFirstAppearanceOrder()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(Plan(
            Candidate("local-unknown001", SourceSyncAction.Unsupported, SyncRoundReasons.UnknownSourceType),
            Candidate("local-unknown002", SourceSyncAction.Unsupported, SyncRoundReasons.NotSupportedOnWindows),
            Candidate("local-unknown003", SourceSyncAction.Unsupported, SyncRoundReasons.UnknownSourceType)));

        Assert.Equal(3, result.WithheldCount);
        Assert.Equal(
            [SyncRoundReasons.UnknownSourceType, SyncRoundReasons.NotSupportedOnWindows],
            result.BlockingReasons);
    }

    [Fact]
    public void UnsupportedEntriesAreWithheldWithoutNeedingADecision()
    {
        SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(
            Plan(Candidate(UnsupportedId, SourceSyncAction.Unsupported, SyncRoundReasons.NotSupportedOnWindows)));

        Assert.Single(result.Withheld);
        Assert.False(result.CanApply);
        Assert.True(result.IsBlocked);
    }

    [Fact]
    public void ApplicableActionsIsTheWriteSet()
    {
        Assert.True(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Create));
        Assert.True(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Update));
        Assert.True(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Delete));
        Assert.True(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Adopt));
        Assert.True(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Conflict));
        Assert.False(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.NoChange));
        Assert.False(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.IgnoreStale));
        Assert.False(SourceSyncApprovalGate.IsApplicable(SourceSyncAction.Unsupported));
    }

    private static SourceSyncPlan Plan(params SourceSyncCandidate[] items) => new() { Items = items };

    private static SourceSyncCandidate Candidate(
        string syncSourceId,
        SourceSyncAction action,
        string reason,
        bool approval = false,
        string? displayName = null) => new()
        {
            SyncSourceId = syncSourceId,
            Action = action,
            Reason = reason,
            RequiresUserApproval = approval,
            DisplayName = displayName,
        };

    private static SyncRoundEntry RoundEntry(
        string syncSourceId,
        SourceSyncAction action,
        string reason,
        SourceSyncChangeKind kind,
        bool approval = false) => new()
        {
            SyncSourceId = syncSourceId,
            Reason = reason,
            Kind = kind,
            RequiresApproval = approval,
            Record = new SourceRecord
            {
                SyncSourceId = syncSourceId,
                DisplayName = syncSourceId,
                SourceType = SyncSourceTypeValue.Parse("webdav"),
                Endpoints = [new SourceEndpoint { Url = "http://nas.local:5005", IsPrimary = true }],
                RecordRevision = 3,
            },
            LocalRevision = action == SourceSyncAction.Conflict ? 3 : 0,
            RemoteRevision = 3,
        };
}

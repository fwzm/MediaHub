using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Locks down the delta contract (W6-4): what a digest may omit, what a merge may apply without asking,
/// and what it must hand to the user instead. Every rule here is one a silent overwrite would violate.
/// </summary>
public sealed class SourceSyncDeltaTests
{
    // ------------------------------------------------------------------ digest

    /// <summary>A missing baseline degrades to a full snapshot: nothing is assumed to be known by the peer.</summary>
    [Fact]
    public void Build_WithoutABaselineIsAFullSnapshot()
    {
        SourceSyncDigest digest = SourceSyncDigest.Build([Record("a"), Record("b")], baseline: null);

        Assert.True(digest.IsFullSnapshot);
        Assert.Equal(2, digest.Items.Count);
        Assert.All(digest.Items, item =>
        {
            Assert.Equal(SourceSyncChangeKind.Added, item.Kind);
            Assert.Equal(SourceSyncFields.All, item.ChangedFields);
            Assert.True(item.ContentChanged);
        });
    }

    /// <summary>Rebuilding against a baseline captured from the same records has nothing to say.</summary>
    [Fact]
    public void Build_WithAMatchingBaselineIsEmpty()
    {
        SourceRecord[] records = [Record("a"), Record("b")];
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords(records);

        SourceSyncDigest digest = SourceSyncDigest.Build(records, baseline);

        Assert.False(digest.IsFullSnapshot);
        Assert.True(digest.IsEmpty);
        Assert.Empty(digest.Issues);
    }

    /// <summary>An edited name is reported as exactly that field, not as an opaque "record changed".</summary>
    [Fact]
    public void Build_ReportsOnlyTheFieldsThatChanged()
    {
        SourceRecord before = Record("a", displayName: "Living Room");
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([before]);
        SourceRecord after = Record("a", displayName: "Bedroom", revision: 2);

        SourceSyncDigest digest = SourceSyncDigest.Build([after], baseline);

        SourceSyncDigestItem item = Assert.Single(digest.Items);
        Assert.Equal(SourceSyncChangeKind.Updated, item.Kind);
        Assert.Equal(new[] { SourceSyncFields.DisplayName }, item.ChangedFields);
        Assert.True(item.ContentChanged);
        Assert.Equal(2, item.RecordRevision);
    }

    /// <summary>A revision bump with identical content still travels, but claims no field changed.</summary>
    [Fact]
    public void Build_ARevisionOnlyChangeCarriesNoChangedFields()
    {
        SourceRecord before = Record("a");
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([before]);
        SourceRecord after = Record("a", revision: 7);

        SourceSyncDigestItem item = Assert.Single(SourceSyncDigest.Build([after], baseline).Items);

        Assert.Equal(SourceSyncChangeKind.Updated, item.Kind);
        Assert.Empty(item.ChangedFields);
        Assert.False(item.ContentChanged);
        Assert.Equal(7, item.RecordRevision);
    }

    /// <summary>Endpoint order on the wire is not content: the canonical form sorts it away.</summary>
    [Fact]
    public void Build_EndpointOrderDoesNotLookLikeAChange()
    {
        SourceRecord before = Record("a", endpoints: [Endpoint("http://host:8096", sortOrder: 0), Endpoint("http://host:8097", sortOrder: 1)]);
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([before]);
        SourceRecord after = Record("a", endpoints: [Endpoint("http://host:8097", sortOrder: 1), Endpoint("http://host:8096", sortOrder: 0)]);

        Assert.Empty(SourceSyncDigest.Build([after], baseline).Items);
    }

    /// <summary>Addresses are compared after normalisation, so a trailing slash is not a change to push.</summary>
    [Fact]
    public void Build_ComparesAddressesAfterNormalisation()
    {
        SourceRecord before = Record("a", url: "http://host:8096/emby");
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([before]);
        SourceRecord after = Record("a", url: "HTTP://HOST:8096/emby/");

        Assert.Empty(SourceSyncDigest.Build([after], baseline).Items);
    }

    /// <summary>A deletion is sent as a tombstone, never as an absent record the peer has to notice.</summary>
    [Fact]
    public void Build_TurnsADeletionIntoATombstone()
    {
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([Record("a")]);
        var tombstone = new Tombstone { SyncSourceId = "a", RecordRevision = 3 };

        SourceSyncDigestItem item = Assert.Single(SourceSyncDigest.Build([], baseline, [tombstone]).Items);

        Assert.Equal(SourceSyncChangeKind.Removed, item.Kind);
        Assert.Same(tombstone, item.Tombstone);
        Assert.Null(item.Record);
        Assert.Equal(3, item.RecordRevision);
    }

    /// <summary>A record with no wire type is reported, not silently dropped: the user can still see it.</summary>
    [Fact]
    public void Build_ReportsARecordWithoutAWireType()
    {
        SourceRecord record = Record("a") with { SourceType = SyncSourceTypeValue.Parse(null) };

        SourceSyncDigest digest = SourceSyncDigest.Build([record], baseline: null);

        Assert.Empty(digest.Items);
        SourceSyncDigestIssue issue = Assert.Single(digest.Issues);
        Assert.Equal(SourceSyncDigestReasons.NoWireType, issue.Reason);
        Assert.Equal("a", issue.SyncSourceId);
    }

    /// <summary>A local record without an identity cannot be referenced by the peer, so it stays home.</summary>
    [Fact]
    public void Build_ReportsARecordWithoutAnIdentity()
    {
        SourceRecord record = Record("a") with { SyncSourceId = "  " };

        SourceSyncDigest digest = SourceSyncDigest.Build([record], baseline: null);

        Assert.Empty(digest.Items);
        Assert.Equal(SourceSyncDigestReasons.MissingSyncSourceId, Assert.Single(digest.Issues).Reason);
    }

    /// <summary>Two records claiming one identity is a defect to report, not a race to pick a winner from.</summary>
    [Fact]
    public void Build_ReportsADuplicatedIdentity()
    {
        SourceSyncDigest digest = SourceSyncDigest.Build([Record("a"), Record("A", revision: 4)], baseline: null);

        Assert.Single(digest.Items);
        Assert.Equal(SourceSyncDigestReasons.DuplicateLocalSyncId, Assert.Single(digest.Issues).Reason);
    }

    /// <summary>A tombstone for a source this device still has is contradictory; the record wins and it is reported.</summary>
    [Fact]
    public void Build_ReportsATombstoneForALivingRecord()
    {
        var tombstone = new Tombstone { SyncSourceId = "a", RecordRevision = 9 };

        SourceSyncDigest digest = SourceSyncDigest.Build([Record("a")], baseline: null, [tombstone]);

        Assert.Equal(SourceSyncChangeKind.Added, Assert.Single(digest.Items).Kind);
        Assert.Equal(SourceSyncDigestReasons.TombstoneForExistingRecord, Assert.Single(digest.Issues).Reason);
    }

    /// <summary>Advancing the baseline with a digest must empty that digest: otherwise the protocol pings forever.</summary>
    [Fact]
    public void Build_AfterAdvancingWithTheDigestIsEmpty()
    {
        SourceRecord[] records = [Record("a"), Record("b", revision: 5)];
        SourceSyncBaseline baseline = SourceSyncBaseline.Empty;
        SourceSyncDigest first = SourceSyncDigest.Build(records, baseline);

        SourceSyncBaseline advanced = SourceSyncBaseline.Advance(baseline, first, "snap-1");
        SourceSyncDigest second = SourceSyncDigest.Build(records, advanced);

        Assert.Equal(2, first.Items.Count);
        Assert.True(second.IsEmpty);
        Assert.Equal("snap-1", advanced.SnapshotId);
    }

    /// <summary>Advancing drops what was removed and keeps what the digest never mentioned.</summary>
    [Fact]
    public void Advance_DropsRemovalsAndKeepsUnmentionedEntries()
    {
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords([Record("a"), Record("b")]);
        var tombstone = new Tombstone { SyncSourceId = "a", RecordRevision = 2 };
        SourceSyncDigest digest = SourceSyncDigest.Build([], baseline, [tombstone]);

        SourceSyncBaseline advanced = SourceSyncBaseline.Advance(baseline, digest);

        Assert.Null(advanced.Find("a"));
        Assert.NotNull(advanced.Find("b"));
    }

    /// <summary>The envelope carries the changes only, and lists the types actually present.</summary>
    [Fact]
    public void ToEnvelope_CarriesOnlyTheChanges()
    {
        var tombstone = new Tombstone { SyncSourceId = "gone", RecordRevision = 2 };
        SourceSyncDigest digest = SourceSyncDigest.Build(
            [Record("a"), Record("b", wire: SyncSourceTypes.Plex), Record("c", wire: SyncSourceTypes.Emby)],
            baseline: null,
            [tombstone]);

        SourceSyncEnvelope envelope = digest.ToEnvelope(" dev-1 ", "snap-9", 1234);

        Assert.Equal("dev-1", envelope.DeviceId);
        Assert.Equal("snap-9", envelope.SnapshotId);
        Assert.Equal(1234, envelope.CreatedAtEpochMs);
        Assert.Equal(3, envelope.Sources.Count);
        Assert.Equal(new[] { SyncSourceTypes.Emby, SyncSourceTypes.Plex }, envelope.Capabilities.SourceTypes);
        Assert.False(envelope.Capabilities.CanSendSecrets);
        Assert.Equal("gone", Assert.Single(envelope.Deletions).SyncSourceId);
        Assert.Equal(SourceSyncEnvelope.CurrentSchemaVersion, envelope.SchemaVersion);
    }

    /// <summary>A local envelope round-trips through the digest, including its tombstones.</summary>
    [Fact]
    public void FromEnvelope_UsesTheEnvelopeTombstones()
    {
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "dev",
            SnapshotId = "s",
            Sources = [Record("a")],
            Deletions = [new Tombstone { SyncSourceId = "gone", RecordRevision = 4 }],
        };

        SourceSyncDigest digest = SourceSyncDigest.FromEnvelope(envelope, baseline: null);

        Assert.Equal(2, digest.Items.Count);
        Assert.Equal(SourceSyncChangeKind.Removed, digest.Removed.Single().Kind);
    }
    // ------------------------------------------------------------------ merge

    /// <summary>A change only the peer made is safe to apply: nothing local is overwritten.</summary>
    [Fact]
    public void Merge_AppliesAOneSidedRemoteChange()
    {
        SyncRoundResult round = Merge(DigestOf(), DigestOf(AddedItem(Record("a"))));

        SyncRoundEntry entry = Assert.Single(round.Apply);
        Assert.Equal(SyncRoundReasons.ApplyRemoteChange, entry.Reason);
        Assert.Equal(SyncMergeDirection.Pull, entry.Direction);
        Assert.False(entry.RequiresApproval);
        Assert.True(round.HasWork);
        Assert.Empty(round.RequiresApproval);
    }

    /// <summary>A change only this device made is pushed, since the peer has nothing to lose.</summary>
    [Fact]
    public void Merge_PushesAOneSidedLocalChange()
    {
        SyncRoundEntry entry = Assert.Single(Merge(DigestOf(UpdatedItem(Record("a"), SourceSyncFields.DisplayName)), DigestOf()).Apply);

        Assert.Equal(SyncRoundReasons.PushLocalChange, entry.Reason);
        Assert.Equal(SyncMergeDirection.Push, entry.Direction);
        Assert.Equal("a", entry.Record!.SyncSourceId);
    }

    /// <summary>A peer change this device already recorded at that revision is not applied twice.</summary>
    [Fact]
    public void Merge_IgnoresARemoteChangeAlreadyApplied()
    {
        SourceRecord known = Record("a", revision: 5);
        SyncRoundEntry entry = Assert.Single(Merge(DigestOf(), DigestOf(AddedItem(Record("a", revision: 5))), BaselineOf(known)).Ignored);

        Assert.Equal(SyncRoundReasons.AlreadyApplied, entry.Reason);
    }

    /// <summary>Deleting a local source on the peer's word always needs the user.</summary>
    [Fact]
    public void Merge_RequiresApprovalForARemoteTombstone()
    {
        SyncRoundResult round = Merge(DigestOf(), DigestOf(RemovedItem("a", 2)), BaselineOf(Record("a")));

        Assert.Empty(round.Apply);
        SyncRoundEntry entry = Assert.Single(round.RequiresApproval);
        Assert.Equal(SyncRoundReasons.TombstoneRequiresApproval, entry.Reason);
        Assert.True(entry.RequiresApproval);
        Assert.Equal("a", entry.Tombstone!.SyncSourceId);
    }

    /// <summary>A tombstone for a source this device never had is nothing to act on.</summary>
    [Fact]
    public void Merge_IgnoresATombstoneForAnUnknownSource()
    {
        SyncRoundEntry entry = Assert.Single(Merge(DigestOf(), DigestOf(RemovedItem("a", 2))).Ignored);

        Assert.Equal(SyncRoundReasons.TombstoneUnknown, entry.Reason);
    }

    /// <summary>An old tombstone does not delete a source the peer already moved past it.</summary>
    [Fact]
    public void Merge_IgnoresAStaleRemoteTombstone()
    {
        SyncRoundEntry entry = Assert.Single(
            Merge(DigestOf(), DigestOf(RemovedItem("a", 3)), BaselineOf(Record("a", revision: 5))).Ignored);

        Assert.Equal(SyncRoundReasons.StaleRemote, entry.Reason);
    }

    /// <summary>This device's own old deletion is not pushed over a newer remote revision.</summary>
    [Fact]
    public void Merge_IgnoresAStaleLocalTombstone()
    {
        SyncRoundEntry entry = Assert.Single(
            Merge(DigestOf(RemovedItem("a", 2)), DigestOf(), BaselineOf(Record("a", revision: 5))).Ignored);

        Assert.Equal(SyncRoundReasons.StaleLocal, entry.Reason);
    }

    /// <summary>Both sides deleted the same source: there is nothing left to decide.</summary>
    [Fact]
    public void Merge_IgnoresADeletionMadeByBothSides()
    {
        SyncRoundEntry entry = Assert.Single(
            Merge(DigestOf(RemovedItem("a", 2)), DigestOf(RemovedItem("a", 2))).Ignored);

        Assert.Equal(SyncRoundReasons.BothDeleted, entry.Reason);
    }

    /// <summary>A deletion on one side against an edit on the other is never resolved automatically.</summary>
    [Fact]
    public void Merge_RequiresApprovalWhenADeletionMeetsAnEdit()
    {
        SyncRoundResult round = Merge(
            DigestOf(RemovedItem("a", 3)),
            DigestOf(UpdatedItem(Record("a", revision: 3), SourceSyncFields.DisplayName)));

        Assert.Empty(round.Apply);
        Assert.Equal(SyncRoundReasons.DeleteVsUpdate, Assert.Single(round.RequiresApproval).Reason);
    }

    /// <summary>The mirror case: an edit here against a deletion on the peer is also the user's call.</summary>
    [Fact]
    public void Merge_RequiresApprovalWhenAnEditMeetsADeletion()
    {
        SyncRoundResult round = Merge(
            DigestOf(UpdatedItem(Record("a", revision: 3), SourceSyncFields.DisplayName)),
            DigestOf(RemovedItem("a", 4)));

        Assert.Empty(round.Apply);
        Assert.Equal(SyncRoundReasons.UpdateVsDelete, Assert.Single(round.RequiresApproval).Reason);
    }

    /// <summary>Both sides editing the same field is a conflict, and it names the field.</summary>
    [Fact]
    public void Merge_RequiresApprovalForAFieldConflict()
    {
        SyncRoundResult round = Merge(
            DigestOf(UpdatedItem(Record("a", displayName: "Desk", revision: 3), SourceSyncFields.DisplayName)),
            DigestOf(UpdatedItem(Record("a", displayName: "Cupboard", revision: 3), SourceSyncFields.DisplayName)));

        Assert.Empty(round.Apply);
        SyncRoundEntry entry = Assert.Single(round.RequiresApproval);
        Assert.Equal(SyncRoundReasons.FieldConflict, entry.Reason);
        Assert.Equal(new[] { SourceSyncFields.DisplayName }, entry.ConflictingFields);
    }

    /// <summary>Two edits to different fields are not a conflict: the higher revision wins.</summary>
    [Fact]
    public void Merge_LetsTheHigherRevisionWinWhenTheEditsDoNotOverlap()
    {
        SyncRoundResult round = Merge(
            DigestOf(UpdatedItem(Record("a", accountScope: "alice", revision: 2), SourceSyncFields.AccountScope)),
            DigestOf(UpdatedItem(Record("a", displayName: "Cupboard", revision: 5), SourceSyncFields.DisplayName)));

        SyncRoundEntry entry = Assert.Single(round.Apply);
        Assert.Equal(SyncRoundReasons.RemoteNewer, entry.Reason);
        Assert.Equal(SyncMergeDirection.Pull, entry.Direction);
        Assert.Empty(round.RequiresApproval);
    }

    /// <summary>Identical content from a higher revision is adopted quietly, since nothing is at stake.</summary>
    [Fact]
    public void Merge_AppliesIdenticalContentFromTheHigherRevision()
    {
        SyncRoundEntry entry = Assert.Single(
            Merge(DigestOf(UpdatedItem(Record("a", revision: 3), SourceSyncFields.DisplayName)),
                  DigestOf(UpdatedItem(Record("a", revision: 5), SourceSyncFields.DisplayName))).Apply);

        Assert.Equal(SyncRoundReasons.RemoteNewer, entry.Reason);
    }

    /// <summary>Two sides converging on the same content at the same revision need no write at all.</summary>
    [Fact]
    public void Merge_IgnoresIdenticalContentAtTheSameRevision()
    {
        SyncRoundEntry entry = Assert.Single(
            Merge(DigestOf(UpdatedItem(Record("a", revision: 4), SourceSyncFields.DisplayName)),
                  DigestOf(UpdatedItem(Record("a", revision: 4), SourceSyncFields.DisplayName))).Ignored);

        Assert.Equal(SyncRoundReasons.AlreadyApplied, entry.Reason);
    }

    /// <summary>Same revision, different content, nothing in common to compare: ask the user.</summary>
    [Fact]
    public void Merge_RequiresApprovalForASameRevisionDisagreement()
    {
        SyncRoundResult round = Merge(
            DigestOf(UpdatedItem(Record("a", displayName: "Desk", revision: 4), SourceSyncFields.DisplayName)),
            DigestOf(UpdatedItem(Record("a", displayName: "Cupboard", revision: 4), SourceSyncFields.AccountScope)));

        Assert.Empty(round.Apply);
        Assert.Equal(SyncRoundReasons.SameRevisionDifferentContent, Assert.Single(round.RequiresApproval).Reason);
    }

    /// <summary>A source type this build has never heard of is preserved and handed to the user, never guessed.</summary>
    [Fact]
    public void Merge_RequiresApprovalForAnUnknownRemoteSourceType()
    {
        SourceRecord remote = Record("a") with { SourceType = SyncSourceTypeValue.Parse("quantum-nas") };
        SyncRoundEntry entry = Assert.Single(Merge(DigestOf(), DigestOf(AddedItem(remote))).RequiresApproval);

        Assert.Equal(SyncRoundReasons.UnknownSourceType, entry.Reason);
        Assert.Equal("quantum-nas", entry.Record!.SourceType.Raw);
    }

    /// <summary>A type the protocol knows but this client cannot use is explained, not attempted.</summary>
    [Fact]
    public void Merge_RequiresApprovalForATypeWindowsCannotUse()
    {
        SourceRecord remote = Record("a") with { SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.QuarkDrive) };
        SyncRoundEntry entry = Assert.Single(Merge(DigestOf(), DigestOf(AddedItem(remote))).RequiresApproval);

        Assert.Equal(SyncRoundReasons.NotSupportedOnWindows, entry.Reason);
    }

    /// <summary>Credentials never cross the wire into storage without an explicit decision.</summary>
    [Fact]
    public void Merge_RequiresApprovalWhenTheRemoteRecordCarriesSecrets()
    {
        using var document = JsonDocument.Parse("{\"token\":\"secret\"}");
        SourceRecord remote = Record("a") with { Secrets = document.RootElement.Clone() };

        SyncRoundResult round = Merge(DigestOf(), DigestOf(AddedItem(remote)));

        Assert.Empty(round.Apply);
        Assert.Equal(SyncRoundReasons.SecretsRequireApproval, Assert.Single(round.RequiresApproval).Reason);
    }

    /// <summary>Input order must not change the round, or two devices would disagree about the same round.</summary>
    [Fact]
    public void Merge_KeepsTheOrderStableWhateverTheInputOrder()
    {
        SyncRoundResult forward = Merge(
            DigestOf(AddedItem(Record("a")), AddedItem(Record("b")), AddedItem(Record("c"))),
            DigestOf(AddedItem(Record("b", revision: 2))));
        SyncRoundResult reversed = Merge(
            DigestOf(AddedItem(Record("c")), AddedItem(Record("b")), AddedItem(Record("a"))),
            DigestOf(AddedItem(Record("b", revision: 2))));

        Assert.Equal(new[] { "a", "b", "c" }, forward.Entries.Select(entry => entry.SyncSourceId));
        Assert.Equal(
            forward.Entries.Select(entry => entry.SyncSourceId + "|" + entry.Reason),
            reversed.Entries.Select(entry => entry.SyncSourceId + "|" + entry.Reason));
    }

    /// <summary>One identity belongs to exactly one bucket; a second claim is a programming error, not a write.</summary>
    [Fact]
    public void Merge_NeverPutsAnEntryInTwoBuckets()
    {
        SyncRoundResult round = Merge(
            DigestOf(AddedItem(Record("a")), RemovedItem("b", 2), UpdatedItem(Record("c", revision: 2), SourceSyncFields.DisplayName)),
            DigestOf(AddedItem(Record("c", revision: 3)), RemovedItem("d", 3)));

        string[] ids = round.Entries.Select(entry => entry.SyncSourceId).ToArray();
        Assert.Equal(ids.Length, ids.Distinct(StringComparer.OrdinalIgnoreCase).Count());

        SyncRoundEntry duplicated = round.Entries[0];
        Assert.Throws<InvalidOperationException>(() => SyncRoundResult.Create([duplicated], [duplicated], []));
    }

    /// <summary>Two empty digests produce an empty round rather than an empty bucket that looks like work.</summary>
    [Fact]
    public void Merge_EmptyDigestsProduceAnEmptyRound()
    {
        SyncRoundResult round = Merge(DigestOf(), DigestOf());

        Assert.True(round.IsEmpty);
        Assert.False(round.HasWork);
        Assert.Null(round.Find("a"));
    }

    // ------------------------------------------------------------------ helpers

    private static SyncRoundResult Merge(SourceSyncDigest local, SourceSyncDigest remote, SourceSyncBaseline? baseline = null)
        => SyncRoundMerger.Merge(local, remote, baseline);

    private static SourceSyncDigest DigestOf(params SourceSyncDigestItem[] items) => new() { Items = items };

    private static SourceSyncDigestItem AddedItem(SourceRecord record)
        => new()
        {
            SyncSourceId = record.SyncSourceId,
            Kind = SourceSyncChangeKind.Added,
            Record = record,
            ChangedFields = SourceSyncFields.All,
            ContentChanged = true,
            RecordRevision = record.RecordRevision,
            FieldVersion = record.FieldVersion,
        };

    private static SourceSyncDigestItem UpdatedItem(SourceRecord record, params string[] fields)
        => new()
        {
            SyncSourceId = record.SyncSourceId,
            Kind = SourceSyncChangeKind.Updated,
            Record = record,
            ChangedFields = fields,
            ContentChanged = fields.Length > 0,
            RecordRevision = record.RecordRevision,
            FieldVersion = record.FieldVersion,
        };

    private static SourceSyncDigestItem RemovedItem(string id, long revision)
        => new()
        {
            SyncSourceId = id,
            Kind = SourceSyncChangeKind.Removed,
            Tombstone = new Tombstone { SyncSourceId = id, RecordRevision = revision },
            RecordRevision = revision,
        };

    private static SourceSyncBaseline BaselineOf(params SourceRecord[] records)
        => SourceSyncBaseline.FromRecords(records);

    private static SourceRecord Record(
        string id,
        string displayName = "Home NAS",
        long revision = 1,
        int fieldVersion = 0,
        string wire = SyncSourceTypes.Emby,
        string url = "http://host:8096",
        string? accountScope = null,
        IReadOnlyList<SourceEndpoint>? endpoints = null)
        => new()
        {
            SyncSourceId = id,
            SourceType = SyncSourceTypeValue.Parse(wire),
            DisplayName = displayName,
            AccountScope = accountScope,
            Endpoints = endpoints ?? [Endpoint(url)],
            RecordRevision = revision,
            FieldVersion = fieldVersion,
        };

    private static SourceEndpoint Endpoint(string url, bool isPrimary = true, int sortOrder = 0)
        => new() { Url = url, IsPrimary = isPrimary, SortOrder = sortOrder };
}

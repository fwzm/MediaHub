using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the W6-2a local source bridge: how this device's own sources get stable sync identities, what the
/// outbound envelope may contain, and what <see cref="SourceSyncLocalBridge.Apply"/> is allowed to write.
/// Every rule is asserted against an <see cref="InMemoryLocalSourceStore"/>, so nothing here touches the real
/// profile on disk or the network.
/// </summary>
public sealed class SourceSyncLocalBridgeTests
{
    // ---- identity ------------------------------------------------------------------------

    /// <summary>
    /// A source configured before W6 has no sync identity; the bridge derives one deterministically from type,
    /// normalised address and account, writes it to the store, and reports the write.
    /// </summary>
    [Fact]
    public void EnsureSourceIds_DerivesStableIdentityForLegacySource()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceIdAssignment assignment = Assert.Single(bridge.EnsureSourceIds());

        Assert.True(assignment.Assigned);
        Assert.Equal(SourceIdAssignmentReasons.Assigned, assignment.Reason);
        Assert.Equal("emby:host", assignment.ServerId);
        Assert.NotNull(assignment.SyncSourceId);
        Assert.StartsWith("local-", assignment.SyncSourceId);
        Assert.Equal(22, assignment.SyncSourceId!.Length);
        Assert.All(assignment.SyncSourceId[6..], character => Assert.True(Uri.IsHexDigit(character)));
        Assert.Equal(assignment.SyncSourceId, Assert.Single(store.GetAll()).SyncSourceId);
        Assert.Equal(writesBefore + 1, store.WriteCount);
    }

    /// <summary>
    /// Identity assignment is idempotent: a second pass changes nothing, keeps the order, and never proposes a
    /// different identity for a source that already has one.
    /// </summary>
    [Fact]
    public void EnsureSourceIds_IsIdempotentAndKeepsOrder()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        store.Save(Local(serverId: "webdav:nas", syncSourceId: null, type: ServerType.WebDav, url: "http://nas:5005"));
        SourceSyncLocalBridge bridge = Bridge(store);

        bridge.EnsureSourceIds();
        string[] ids = store.GetAll().Select(record => record.SyncSourceId!).ToArray();
        int writesAfterFirstPass = store.WriteCount;

        IReadOnlyList<SourceIdAssignment> second = bridge.EnsureSourceIds();

        Assert.Equal([SourceIdAssignmentReasons.AlreadyHasId, SourceIdAssignmentReasons.AlreadyHasId], second.Select(item => item.Reason));
        Assert.All(second, item => Assert.False(item.Assigned));
        Assert.Equal(writesAfterFirstPass, store.WriteCount);
        Assert.Equal(ids, store.GetAll().Select(record => record.SyncSourceId!));
        Assert.Equal(["emby:host", "webdav:nas"], store.GetAll().Select(record => record.ServerId));
    }

    /// <summary>
    /// The derived identity is a function of type, address and account only: renaming a source must not create
    /// a second identity, and host/scheme case must not split one source in two.
    /// </summary>
    [Fact]
    public void DeriveSyncSourceId_IgnoresDisplayNameAndAddressCase()
    {
        string? first = SourceSyncLocalBridge.DeriveSyncSourceId(Local(syncSourceId: null, displayName: "Home"));
        string? renamed = SourceSyncLocalBridge.DeriveSyncSourceId(Local(syncSourceId: null, displayName: "Casa"));
        string? uppercase = SourceSyncLocalBridge.DeriveSyncSourceId(
            Local(syncSourceId: null, url: "HTTP://HOST:8096/emby/"));
        string? otherServer = SourceSyncLocalBridge.DeriveSyncSourceId(
            Local(syncSourceId: null, url: "http://other:8096/emby"));

        Assert.Equal(first, renamed);
        Assert.Equal(first, uppercase);
        Assert.NotEqual(first, otherServer);
    }

    /// <summary>The account is part of the identity: the same server under two accounts gets two identities.</summary>
    [Fact]
    public void DeriveSyncSourceId_IsCaseInsensitiveForAccountButSeparatesAccounts()
    {
        string? alice = SourceSyncLocalBridge.DeriveSyncSourceId(Local(syncSourceId: null, accountScope: "Alice"));
        string? aliceAgain = SourceSyncLocalBridge.DeriveSyncSourceId(Local(syncSourceId: null, accountScope: "  alice  "));
        string? bob = SourceSyncLocalBridge.DeriveSyncSourceId(Local(syncSourceId: null, accountScope: "bob"));

        Assert.Equal(alice, aliceAgain);
        Assert.NotEqual(alice, bob);
    }

    /// <summary>
    /// An address that cannot be normalised gets no identity at all: deriving one from an unparseable string
    /// would create an identity no other device can reproduce.
    /// </summary>
    [Fact]
    public void EnsureSourceIds_SkipsAddressWithoutUsableEndpoint()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:pasted", syncSourceId: null, url: "alice@host:8096"));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceIdAssignment assignment = Assert.Single(bridge.EnsureSourceIds());

        Assert.False(assignment.Assigned);
        Assert.Equal(SourceIdAssignmentReasons.NoUsableEndpoint, assignment.Reason);
        Assert.Null(assignment.SyncSourceId);
        Assert.Null(Assert.Single(store.GetAll()).SyncSourceId);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Empty(bridge.BuildEnvelope().Sources);
    }

    /// <summary>A source whose type this build cannot even name cannot get an honest identity.</summary>
    [Fact]
    public void EnsureSourceIds_ReportsUnknownSourceType()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "unknown:host", syncSourceId: null, type: ServerType.Unknown));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceIdAssignment assignment = Assert.Single(bridge.EnsureSourceIds());

        Assert.Equal(SourceIdAssignmentReasons.UnknownSourceType, assignment.Reason);
        Assert.Null(assignment.SyncSourceId);
        Assert.Equal(writesBefore, store.WriteCount);
    }

    /// <summary>
    /// Two local records that derive the same identity are not merged by assigning both the same id: the second
    /// one is reported instead, because silently collapsing two sources is unrecoverable for the user.
    /// </summary>
    [Fact]
    public void EnsureSourceIds_RefusesToShareOneIdentity()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        store.Save(Local(serverId: "emby:host-copy", syncSourceId: null));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        IReadOnlyList<SourceIdAssignment> assignments = bridge.EnsureSourceIds();

        Assert.True(assignments[0].Assigned);
        Assert.Equal(SourceIdAssignmentReasons.DuplicateIdentity, assignments[1].Reason);
        Assert.False(assignments[1].Assigned);
        Assert.Null(assignments[1].SyncSourceId);
        Assert.Null(store.GetAll()[1].SyncSourceId);
        Assert.Equal(writesBefore + 1, store.WriteCount);
    }

    // ---- envelope ------------------------------------------------------------------------

    /// <summary>The default envelope advertises no secrets and carries none; asking for secrets still has none to send.</summary>
    [Fact]
    public void BuildEnvelope_CarriesNoSecretsByDefault()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncEnvelope byDefault = bridge.BuildEnvelope();
        SourceSyncEnvelope asked = bridge.BuildEnvelope(includeSecrets: true);

        Assert.False(byDefault.Capabilities.CanSendSecrets);
        Assert.All(byDefault.Sources, source => Assert.Null(source.Secrets));
        Assert.True(asked.Capabilities.CanSendSecrets);
        Assert.All(asked.Sources, source => Assert.Null(source.Secrets));
    }

    /// <summary>Capabilities list the types this device actually holds, deduplicated, in source order.</summary>
    [Fact]
    public void BuildEnvelope_ListsDistinctActualSourceTypes()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        store.Save(Local(serverId: "emby:other", syncSourceId: null, url: "http://other:8096"));
        store.Save(Local(serverId: "webdav:nas", syncSourceId: null, type: ServerType.WebDav, url: "http://nas:5005"));
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncEnvelope envelope = bridge.BuildEnvelope();

        Assert.Equal([SyncSourceTypes.Emby, SyncSourceTypes.WebDav], envelope.Capabilities.SourceTypes);
        Assert.Equal(3, envelope.Sources.Count);
        Assert.Equal("device-a", envelope.DeviceId);
        Assert.Equal(SourceSyncEnvelope.CurrentSchemaVersion, envelope.SchemaVersion);
        Assert.Equal(1, envelope.MinimumReaderVersion);
        Assert.Equal(32, envelope.SnapshotId.Length);
        Assert.True(Guid.TryParseExact(envelope.SnapshotId, "N", out _));
    }

    /// <summary>Addresses leave this device normalised, so the same source has one address on every device.</summary>
    [Fact]
    public void BuildEnvelope_NormalisesAddressAndKeepsRevision()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null, url: "HTTP://HOST:8096/emby/", revision: 4));
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceRecord source = Assert.Single(bridge.BuildEnvelope().Sources);

        Assert.Equal("http://host:8096/emby", Assert.Single(source.Endpoints).Url);
        Assert.True(source.Endpoints[0].IsPrimary);
        Assert.True(source.Endpoints[0].Enabled);
        Assert.Equal(4, source.RecordRevision);
        Assert.Equal("alice", source.AccountScope);
    }

    /// <summary>A source with no dialable address is left out of the envelope rather than sent unusable.</summary>
    [Fact]
    public void BuildEnvelope_OmitsSourceWithoutAddress()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null, url: null));
        SourceSyncLocalBridge bridge = Bridge(store);

        Assert.Empty(bridge.BuildEnvelope().Sources);
    }

    /// <summary>Tombstones are carried, not invented: the caller owns deletion tracking, the codec keeps them.</summary>
    [Fact]
    public void BuildEnvelope_CarriesExplicitTombstones()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        SourceSyncLocalBridge bridge = Bridge(store);
        var tombstone = new Tombstone { SyncSourceId = "sync-gone", DeletedAtEpochMs = 1_700_000_000_000, RecordRevision = 3 };

        SourceSyncEnvelope envelope = bridge.BuildEnvelope(deletions: [tombstone]);

        Tombstone carried = Assert.Single(envelope.Deletions);
        Assert.Equal("sync-gone", carried.SyncSourceId);
        Assert.Equal(3, carried.RecordRevision);
        Assert.True(SourceSyncCodec.TryDeserialize(SourceSyncCodec.Serialize(envelope), out SourceSyncEnvelope? decoded, out string? error), error);
        Assert.Equal("sync-gone", Assert.Single(decoded!.Deletions).SyncSourceId);
    }

    /// <summary>
    /// What survives a restart is the identity itself: rebuilding a store from its persisted records yields the
    /// same ids and the same envelope, so a restart does not look like a new source to the other device.
    /// </summary>
    [Fact]
    public void BuildEnvelope_KeepsIdentitiesAcrossRestart()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: null));
        store.Save(Local(serverId: "webdav:nas", syncSourceId: null, type: ServerType.WebDav, url: "http://nas:5005"));
        SourceSyncEnvelope first = Bridge(store).BuildEnvelope();

        var restarted = new InMemoryLocalSourceStore();
        foreach (LocalSourceRecord record in store.GetAll())
        {
            restarted.Save(record);
        }

        int writesAfterReload = restarted.WriteCount;
        SourceSyncEnvelope second = Bridge(restarted).BuildEnvelope();

        Assert.Equal(
            first.Sources.Select(source => (source.SyncSourceId, source.SourceType.Raw, Assert.Single(source.Endpoints).Url)),
            second.Sources.Select(source => (source.SyncSourceId, source.SourceType.Raw, Assert.Single(source.Endpoints).Url)));
        Assert.Equal(writesAfterReload, restarted.WriteCount);
    }

    /// <summary>
    /// A type this build does not know travels through unchanged: it is stored as "saved but not supported yet",
    /// never rewritten to something the peer would mistake for support (D3).
    /// </summary>
    [Fact]
    public void BuildEnvelope_PreservesUnknownSourceTypeVerbatim()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(new LocalSourceRecord
        {
            ServerId = "quantum:nas",
            Type = ServerType.Unknown,
            SourceTypeRaw = "Quantum-NAS",
            Url = "http://nas:8080",
            DisplayName = "NAS",
        });
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceRecord source = Assert.Single(bridge.BuildEnvelope().Sources);

        Assert.Equal("Quantum-NAS", source.SourceType.Raw);
        Assert.False(source.SourceType.IsKnown);

        Assert.True(SourceSyncCodec.TryDeserialize(SourceSyncCodec.Serialize(bridge.BuildEnvelope()), out SourceSyncEnvelope? decoded, out _));
        Assert.Equal("Quantum-NAS", Assert.Single(decoded!.Sources).SourceType.Raw);

        SourceSyncApplyResult applied = Bridge(new InMemoryLocalSourceStore()).Apply(decoded);
        SourceSyncCandidate candidate = Assert.Single(applied.Plan.Items);
        Assert.Equal(SourceSyncAction.Unsupported, candidate.Action);
        Assert.Equal("Quantum-NAS", candidate.SourceType);
        Assert.False(candidate.IsKnownSourceType);
    }

    // ---- apply ---------------------------------------------------------------------------

    /// <summary>A source the peer has and this device does not is created once — and the same envelope applied again writes nothing.</summary>
    [Fact]
    public void Apply_CreatesPeerSourceExactlyOnce()
    {
        var peer = new InMemoryLocalSourceStore();
        peer.Save(new LocalSourceRecord
        {
            ServerId = "emby:peer",
            Type = ServerType.Emby,
            Url = "http://peer:8096",
            AccountScope = "alice",
            DisplayName = "Peer",
            RecordRevision = 1,
        });
        SourceSyncEnvelope envelope = Bridge(peer, "device-b").BuildEnvelope();

        var local = new InMemoryLocalSourceStore();
        SourceSyncLocalBridge bridge = Bridge(local);
        SourceSyncApplyResult first = bridge.Apply(envelope);

        Assert.Equal(1, first.AppliedCount);
        SourceSyncCandidate candidate = Assert.Single(first.Plan.Items);
        Assert.Equal(SourceSyncAction.Create, candidate.Action);
        Assert.Equal(SourceSyncReasons.NewSource, candidate.Reason);
        LocalSourceRecord created = Assert.Single(local.GetAll());
        Assert.Equal(candidate.SyncSourceId, created.SyncSourceId);
        Assert.Equal(created.SyncSourceId, created.ServerId);
        Assert.Equal(ServerType.Emby, created.Type);
        Assert.Equal("http://peer:8096", created.Url);
        Assert.Equal("alice", created.AccountScope);
        Assert.Equal("Peer", created.DisplayName);
        Assert.Equal(1, created.RecordRevision);

        int writes = local.WriteCount;
        SourceSyncApplyResult second = bridge.Apply(envelope);

        Assert.Equal(0, second.AppliedCount);
        Assert.Equal(SourceSyncAction.NoChange, Assert.Single(second.Plan.Items).Action);
        Assert.Equal(writes, local.WriteCount);
        Assert.Single(local.GetAll());
        Assert.Equal(1, Assert.Single(local.GetAll()).RecordRevision);
    }

    /// <summary>A higher revision updates the matched record in place; the local server id is never replaced.</summary>
    [Fact]
    public void Apply_UpdatesMatchedSourceInPlace()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", revision: 1, displayName: "Home"));
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncApplyResult result = bridge.Apply(EnvelopeWith(IncomingRecord(
            revision: 2,
            displayName: "Home 2",
            url: "HTTP://HOST:8096/emby/")));

        Assert.Equal(1, result.AppliedCount);
        LocalSourceRecord updated = Assert.Single(store.GetAll());
        Assert.Equal("emby:host", updated.ServerId);
        Assert.Equal("sync-1", updated.SyncSourceId);
        Assert.Equal(2, updated.RecordRevision);
        Assert.Equal("Home 2", updated.DisplayName);
        Assert.Equal("http://host:8096/emby", updated.Url);

        int writes = store.WriteCount;
        Assert.Equal(0, bridge.Apply(EnvelopeWith(IncomingRecord(revision: 2, displayName: "Home 2", url: "http://host:8096/emby"))).AppliedCount);
        Assert.Equal(writes, store.WriteCount);
        Assert.Equal(2, Assert.Single(store.GetAll()).RecordRevision);
    }

    /// <summary>Same revision, different content: the bridge proposes and writes nothing (D5).</summary>
    [Fact]
    public void Apply_DoesNotResolveSameRevisionConflict()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", revision: 5, displayName: "Home"));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncApplyResult result = bridge.Apply(EnvelopeWith(IncomingRecord(revision: 5, displayName: "Other")));

        Assert.Equal(0, result.AppliedCount);
        SourceSyncCandidate conflict = Assert.Single(result.Conflicts);
        Assert.Equal(SourceSyncReasons.SameRevisionDifferentContent, conflict.Reason);
        Assert.True(conflict.RequiresUserApproval);
        Assert.Contains(conflict, result.Proposals);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Equal("Home", Assert.Single(store.GetAll()).DisplayName);
        Assert.Equal(5, Assert.Single(store.GetAll()).RecordRevision);
    }

    /// <summary>Two equally good legacy matches: the bridge refuses to choose, and writes nothing.</summary>
    [Fact]
    public void Apply_DoesNotResolveAmbiguousLegacyMatch()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "legacy-1", syncSourceId: null));
        store.Save(Local(serverId: "legacy-2", syncSourceId: null));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncApplyResult result = bridge.Apply(EnvelopeWith(IncomingRecord(syncSourceId: "sync-new", revision: 2)));

        Assert.Equal(0, result.AppliedCount);
        SourceSyncCandidate conflict = Assert.Single(result.Conflicts);
        Assert.Equal(SourceSyncReasons.AmbiguousLegacyIdentity, conflict.Reason);
        Assert.Equal(["legacy-1", "legacy-2"], conflict.CandidateServerIds);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.All(store.GetAll(), record => Assert.Null(record.SyncSourceId));
    }

    /// <summary>An unsupported type is reported with its raw value and never written as a working source.</summary>
    [Fact]
    public void Apply_ReportsUnsupportedTypeWithoutWriting()
    {
        var store = new InMemoryLocalSourceStore();
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncApplyResult result = bridge.Apply(EnvelopeWith(IncomingRecord(
            syncSourceId: "sync-quark",
            sourceType: SyncSourceTypes.QuarkDrive)));

        Assert.Equal(0, result.AppliedCount);
        SourceSyncCandidate candidate = Assert.Single(result.Plan.Items);
        Assert.Equal(SourceSyncAction.Unsupported, candidate.Action);
        Assert.Equal(SourceSyncReasons.NotSupportedOnWindows, candidate.Reason);
        Assert.Equal(SyncSourceTypes.QuarkDrive, candidate.SourceType);
        Assert.True(candidate.IsKnownSourceType);
        Assert.Equal(0, store.WriteCount);
        Assert.Empty(store.GetAll());
    }

    /// <summary>A tombstone becomes a deletion proposal; deleting a source and its credentials needs approval.</summary>
    [Fact]
    public void Apply_ProposesTombstoneInsteadOfDeleting()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", revision: 1));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-b",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            Deletions = [new Tombstone { SyncSourceId = "sync-1", DeletedAtEpochMs = 1_700_000_000_000, RecordRevision = 1 }],
        };

        SourceSyncApplyResult result = bridge.Apply(envelope);

        Assert.Equal(0, result.AppliedCount);
        SourceSyncCandidate candidate = Assert.Single(result.Plan.Items);
        Assert.Equal(SourceSyncAction.Delete, candidate.Action);
        Assert.Equal(SourceSyncReasons.Tombstone, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);
        Assert.Contains(candidate, result.Proposals);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Single(store.GetAll());
    }

    /// <summary>A tombstone older than what this device holds is stale and changes nothing.</summary>
    [Fact]
    public void Apply_IgnoresStaleTombstone()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", revision: 5));
        int writesBefore = store.WriteCount;
        SourceSyncLocalBridge bridge = Bridge(store);
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-b",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            Deletions = [new Tombstone { SyncSourceId = "sync-1", DeletedAtEpochMs = long.MaxValue, RecordRevision = 3 }],
        };

        SourceSyncApplyResult result = bridge.Apply(envelope);

        Assert.Equal(0, result.AppliedCount);
        Assert.Equal(SourceSyncAction.IgnoreStale, Assert.Single(result.Plan.Items).Action);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Equal(5, Assert.Single(store.GetAll()).RecordRevision);
    }

    /// <summary>The same server under two accounts stays two sources, and an update touches only its own account.</summary>
    [Fact]
    public void Apply_KeepsAccountsSeparate()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:alice", syncSourceId: "sync-alice", accountScope: "alice", revision: 1));
        store.Save(Local(serverId: "emby:bob", syncSourceId: "sync-bob", accountScope: "bob", revision: 1));
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncApplyResult result = bridge.Apply(EnvelopeWith(IncomingRecord(
            syncSourceId: "sync-alice",
            revision: 2,
            accountScope: "alice",
            displayName: "Alice Home")));

        Assert.Equal(1, result.AppliedCount);
        LocalSourceRecord alice = store.GetAll().Single(record => record.ServerId == "emby:alice");
        LocalSourceRecord bob = store.GetAll().Single(record => record.ServerId == "emby:bob");
        Assert.Equal(2, alice.RecordRevision);
        Assert.Equal("Alice Home", alice.DisplayName);
        Assert.Equal(1, bob.RecordRevision);
        Assert.Equal("Home", bob.DisplayName);
        Assert.Equal("bob", bob.AccountScope);
    }

    /// <summary>
    /// Decisions come from revisions, not clocks: an envelope stamped at the epoch and one stamped far in the
    /// future produce the same plan (D5).
    /// </summary>
    [Fact]
    public void Apply_DoesNotUseWallClocks()
    {
        SourceSyncPlan early = PlanAgainstFreshStore(createdAtEpochMs: 0);
        SourceSyncPlan late = PlanAgainstFreshStore(createdAtEpochMs: long.MaxValue);

        Assert.Equal(SourceSyncAction.Update, Assert.Single(early.Items).Action);
        Assert.Equal(
            early.Items.Select(item => (item.Action, item.Reason)),
            late.Items.Select(item => (item.Action, item.Reason)));
    }

    /// <summary>Nothing to sync is not an error: an empty store yields an empty envelope and an empty plan.</summary>
    [Fact]
    public void EmptyStore_ProducesEmptyEnvelopeAndPlan()
    {
        var store = new InMemoryLocalSourceStore();
        SourceSyncLocalBridge bridge = Bridge(store);

        SourceSyncEnvelope envelope = bridge.BuildEnvelope();
        SourceSyncApplyResult applied = bridge.Apply(new SourceSyncEnvelope
        {
            DeviceId = "device-b",
            SnapshotId = "0123456789abcdef0123456789abcdef",
        });

        Assert.Empty(envelope.Sources);
        Assert.Empty(envelope.Capabilities.SourceTypes);
        Assert.Empty(applied.Plan.Items);
        Assert.Equal(0, applied.AppliedCount);
        Assert.Equal(0, store.WriteCount);
    }

    // ---- store contract ------------------------------------------------------------------

    /// <summary>The in-memory store upserts by server id without reordering, and deletes by sync identity.</summary>
    [Fact]
    public void InMemoryStore_UpsertsInPlaceAndDeletesByIdentity()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1"));
        store.Save(Local(serverId: "webdav:nas", syncSourceId: "sync-2", type: ServerType.WebDav, url: "http://nas:5005"));

        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", displayName: "Renamed"));

        Assert.Equal(["emby:host", "webdav:nas"], store.GetAll().Select(record => record.ServerId));
        Assert.Equal("Renamed", store.GetAll()[0].DisplayName);
        Assert.Equal(3, store.WriteCount);

        Assert.True(store.Delete("  SYNC-1  "));
        Assert.False(store.Delete("sync-1"));
        Assert.False(store.Delete("  "));
        Assert.Equal(4, store.WriteCount);
        Assert.Equal("webdav:nas", Assert.Single(store.GetAll()).ServerId);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static SourceSyncLocalBridge Bridge(ILocalSourceStore store, string deviceId = "device-a")
        => new(store, deviceId);

    private static SourceSyncPlan PlanAgainstFreshStore(long createdAtEpochMs)
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(serverId: "emby:host", syncSourceId: "sync-1", revision: 1));
        return Bridge(store).Apply(EnvelopeWith(createdAtEpochMs: createdAtEpochMs, IncomingRecord(revision: 2))).Plan;
    }

    private static SourceSyncEnvelope EnvelopeWith(params SourceRecord[] sources)
        => EnvelopeWith(createdAtEpochMs: 1_700_000_000_000, sources);

    private static SourceSyncEnvelope EnvelopeWith(long createdAtEpochMs, params SourceRecord[] sources)
        => new()
        {
            DeviceId = "device-b",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            CreatedAtEpochMs = createdAtEpochMs,
            Capabilities = new SyncCapabilities { SourceTypes = [SyncSourceTypes.Emby] },
            Sources = sources,
        };

    private static SourceRecord IncomingRecord(
        string syncSourceId = "sync-1",
        string sourceType = SyncSourceTypes.Emby,
        string url = "http://host:8096/emby",
        long revision = 1,
        string? accountScope = "alice",
        string displayName = "Home")
        => new()
        {
            SyncSourceId = syncSourceId,
            SourceType = SyncSourceTypeValue.Parse(sourceType),
            DisplayName = displayName,
            AccountScope = accountScope,
            Endpoints = [new SourceEndpoint { Url = url, IsPrimary = true }],
            RecordRevision = revision,
        };

    private static LocalSourceRecord Local(
        string serverId = "server-1",
        string? syncSourceId = "sync-1",
        ServerType type = ServerType.Emby,
        string? url = "http://host:8096/emby",
        string? accountScope = "alice",
        string displayName = "Home",
        long revision = 1)
        => new()
        {
            ServerId = serverId,
            SyncSourceId = syncSourceId,
            Type = type,
            Url = url,
            AccountScope = accountScope,
            DisplayName = displayName,
            RecordRevision = revision,
        };
}

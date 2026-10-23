using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the W6-2b App wiring that does not need the WinUI process: how a locally configured source becomes a
/// <see cref="LocalSourceRecord"/>, what the exported envelope may contain, and how a peer envelope is tallied
/// before anything is written.
/// </summary>
/// <remarks>
/// The App project is deliberately not referenced here: the mapper, the bridge and the preview summary all live
/// below the UI, so these rules can be asserted with no window, no profile on disk and no network.
/// </remarks>
public sealed class SourceSyncAppMappingTests
{
    // ---- local source mapping ------------------------------------------------------------

    /// <summary>
    /// A supported kind is mapped to the contract's wire type and its address is replaced by the sync layer's
    /// normal form, so the same server reached as "HOST:8096" and "http://host:8096" has one identity.
    /// </summary>
    [Fact]
    public void Map_NormalisesAddressAndMapsSupportedKind()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "webdav:nas", type: ServerType.WebDav, url: "HTTP://NAS.local:5005/"),
        ]);

        LocalSourceRecord record = Assert.Single(result.Records);
        Assert.Empty(result.Issues);
        Assert.Equal(ServerType.WebDav, record.Type);
        Assert.Equal("webdav", WindowsSourceTypeMap.ToWire(record.Type));
        Assert.Null(record.SourceTypeRaw);
        Assert.Equal("http://nas.local:5005", record.Url);
    }

    /// <summary>
    /// A kind this build knows but cannot represent keeps its wire string verbatim and is stored as
    /// <see cref="ServerType.Unknown"/>, so the record survives a round trip instead of being re-spelled (D3).
    /// </summary>
    [Fact]
    public void Map_KeepsUnsupportedKindVerbatim()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "fn-nas", type: ServerType.Unknown, sourceTypeRaw: "  fn-nas  ", url: "http://nas.local:5666"),
        ]);

        LocalSourceRecord record = Assert.Single(result.Records);
        Assert.Equal(ServerType.Unknown, record.Type);
        Assert.Equal("fn-nas", record.SourceTypeRaw);
        Assert.NotNull(record.Url);
    }

    /// <summary>A wire string from a newer build is preserved exactly, not lowercased or trimmed into a guess.</summary>
    [Fact]
    public void Map_KeepsUnknownKindVerbatim()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "future", type: ServerType.Unknown, sourceTypeRaw: "FUTURE-NAS", url: "https://nas.local"),
        ]);

        LocalSourceRecord record = Assert.Single(result.Records);
        Assert.Equal("FUTURE-NAS", record.SourceTypeRaw);
        Assert.Null(WindowsSourceTypeMap.ToWire(record.Type));
    }

    /// <summary>
    /// A scheme-less address containing "@" is a pasted credential, so it is skipped and reported rather than
    /// reinterpreted — and the report must not echo the credential back (D6).
    /// </summary>
    [Fact]
    public void Map_SkipsPastedCredentialWithoutEchoingIt()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "emby:typo", type: ServerType.Emby, url: "alice:hunter2@nas.local:8096"),
        ]);

        LocalSourceMappingIssue issue = Assert.Single(result.Issues);
        Assert.Empty(result.Records);
        Assert.Equal(LocalSourceMappingReasons.NoUsableEndpoint, issue.Reason);
        Assert.Equal("emby:typo", issue.ServerId);
        Assert.DoesNotContain("hunter2", System.Text.Json.JsonSerializer.Serialize(issue));
    }

    /// <summary>A Windows folder is device state, not a source: exporting it would hand the peer an unusable path.</summary>
    [Fact]
    public void Map_SkipsLocalFolderPath()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "local", type: ServerType.Local, url: @"D:\Videos"),
        ]);

        LocalSourceMappingIssue issue = Assert.Single(result.Issues);
        Assert.Empty(result.Records);
        Assert.Equal(LocalSourceMappingReasons.LocalDevicePath, issue.Reason);
    }

    /// <summary>An unmapped kind without a wire string has nothing to send, and says so instead of guessing.</summary>
    [Fact]
    public void Map_ReportsMissingWireType()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "mystery", type: ServerType.Unknown, sourceTypeRaw: "   ", url: "http://nas.local:8096"),
        ]);

        LocalSourceMappingIssue issue = Assert.Single(result.Issues);
        Assert.Equal(LocalSourceMappingReasons.NoWireType, issue.Reason);
    }

    /// <summary>A source with no local id is never mapped: nothing could reference it afterwards.</summary>
    [Fact]
    public void Map_ReportsMissingServerId()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "   ", type: ServerType.Emby, url: "http://nas.local:8096"),
        ]);

        Assert.Empty(result.Records);
        Assert.Equal(LocalSourceMappingReasons.NoServerId, Assert.Single(result.Issues).Reason);
    }

    /// <summary>
    /// Mapping preserves order, duplicates and the local revision: the store is written in list order, and a
    /// second run must not reorder the user's sources or reset their revision.
    /// </summary>
    [Fact]
    public void Map_KeepsOrderDuplicatesAndRevision()
    {
        LocalSourceMappingResult result = LocalSourceMapper.Map(
        [
            Input(serverId: "emby:a", type: ServerType.Emby, url: "http://a.local:8096", recordRevision: 7),
            Input(serverId: "emby:a", type: ServerType.Emby, url: "http://a.local:8096", recordRevision: 7),
            Input(serverId: "plex:b", type: ServerType.Plex, url: "http://b.local:32400", recordRevision: 2),
        ]);

        Assert.Equal(["emby:a", "emby:a", "plex:b"], result.Records.Select(record => record.ServerId));
        Assert.Equal([7L, 7L, 2L], result.Records.Select(record => record.RecordRevision));
        Assert.Equal(ServerType.Plex, result.Records[2].Type);
    }

    // ---- identity across a restart ------------------------------------------------------

    /// <summary>
    /// The App persists the assigned id and merges it back into the mapped record on the next start; that
    /// round trip must be a no-op for the bridge, or every launch would write the profile again.
    /// </summary>
    [Fact]
    public void EnsureSourceIds_SurvivesRestartThroughStoredIdentity()
    {
        var inputs = new[]
        {
            Input(serverId: "emby:a", type: ServerType.Emby, url: "http://a.local:8096"),
            Input(serverId: "webdav:b", type: ServerType.WebDav, url: "http://b.local:5005", accountScope: "Alice"),
        };

        var first = new InMemoryLocalSourceStore();
        foreach (LocalSourceRecord record in LocalSourceMapper.Map(inputs).Records)
        {
            first.Save(record);
        }

        new SourceSyncLocalBridge(first, "dev-test").EnsureSourceIds();
        IReadOnlyList<LocalSourceRecord> stored = first.GetAll();
        string[] identities = stored.Select(record => record.SyncSourceId!).ToArray();
        Assert.All(identities, identity => Assert.StartsWith("local-", identity));

        // Restart: the same configured sources are mapped again, and the stored identity is merged back in.
        var second = new InMemoryLocalSourceStore();
        foreach (LocalSourceRecord record in LocalSourceMapper.Map(inputs).Records)
        {
            string? identity = stored
                .FirstOrDefault(saved => saved.ServerId == record.ServerId)?.SyncSourceId;
            second.Save(record with { SyncSourceId = identity });
        }

        int writesBefore = second.WriteCount;
        IReadOnlyList<SourceIdAssignment> assignments = new SourceSyncLocalBridge(second, "dev-test").EnsureSourceIds();

        Assert.All(assignments, assignment => Assert.Equal(SourceIdAssignmentReasons.AlreadyHasId, assignment.Reason));
        Assert.Equal(writesBefore, second.WriteCount);
        Assert.Equal(identities, second.GetAll().Select(record => record.SyncSourceId!));
    }

    // ---- envelope content ----------------------------------------------------------------

    /// <summary>
    /// The default export carries no credentials and says so in its capabilities; the serialised form has no
    /// field a password or token could hide in.
    /// </summary>
    [Fact]
    public void Export_CarriesNoSecretsAndAdmitsIt()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(new LocalSourceRecord
        {
            ServerId = "webdav:nas",
            SyncSourceId = "local-1111111111111111",
            Type = ServerType.WebDav,
            Url = "http://nas.local:5005",
            AccountScope = "alice",
            DisplayName = "客厅 NAS",
            RecordRevision = 4,
        });

        SourceSyncEnvelope envelope = new SourceSyncLocalBridge(store, "dev-test").BuildEnvelope(includeSecrets: false);
        string json = SourceSyncCodec.Serialize(envelope);

        Assert.False(envelope.Capabilities.CanSendSecrets);
        Assert.Equal(["webdav"], envelope.Capabilities.SourceTypes);
        Assert.False(json.Contains("password", StringComparison.OrdinalIgnoreCase));
        Assert.False(json.Contains("token", StringComparison.OrdinalIgnoreCase));
        // "canSendSecrets" is the capability flag, not a payload: match the property name, not the word.
        Assert.False(json.Contains("\"secrets\"", StringComparison.Ordinal));
        Assert.All(envelope.Sources, record => Assert.Null(record.Secrets));
        // The account name is part of identity and travels; a credential must not.
        Assert.Contains("alice", json);
        Assert.Contains("local-1111111111111111", json);
    }

    /// <summary>
    /// Only sources the mapper accepted reach the envelope: a folder on this device has no address a peer could
    /// dial, so it is reported locally and left out instead of being exported as an invented URL.
    /// </summary>
    [Fact]
    public void Export_LeavesOutUnmappableSources()
    {
        LocalSourceMappingResult mapped = LocalSourceMapper.Map(
        [
            Input(serverId: "local", type: ServerType.Local, url: @"D:\Videos"),
            Input(serverId: "emby:a", type: ServerType.Emby, url: "http://a.local:8096"),
        ]);

        var store = new InMemoryLocalSourceStore();
        foreach (LocalSourceRecord record in mapped.Records)
        {
            store.Save(record);
        }

        SourceSyncEnvelope envelope = new SourceSyncLocalBridge(store, "dev-test").BuildEnvelope(includeSecrets: false);

        // Exactly the Emby source travels; the folder was reported as an issue instead.
        SourceRecord only = Assert.Single(envelope.Sources);
        Assert.Equal("emby", only.SourceType.Wire);
        Assert.Equal("http://a.local:8096", Assert.Single(only.Endpoints).Url);
        Assert.Single(mapped.Issues);
        Assert.Equal(LocalSourceMappingReasons.LocalDevicePath, mapped.Issues[0].Reason);
        Assert.Empty(envelope.Deletions);
    }

    // ---- merge preview -------------------------------------------------------------------

    /// <summary>
    /// Equal revisions with different content are a conflict, never a silent overwrite: the candidate requires
    /// approval and the tallies show one conflict that needs confirming.
    /// </summary>
    [Fact]
    public void Preview_ConflictAtSameRevisionRequiresConfirmation()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(displayName: "本机名字"));

        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            Envelope(Peer("local-2222222222222222", "webdav", "http://nas.local:5005", displayName: "对端名字")),
            store.GetAll());

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Conflict, candidate.Action);
        Assert.Equal(SourceSyncReasons.SameRevisionDifferentContent, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        Assert.Equal(1, summary.Conflict);
        Assert.Equal(1, summary.RequiresConfirmation);
        Assert.False(summary.HasChanges);
    }

    /// <summary>
    /// A newer record proposes an update and a peer-only source proposes a create; planning writes nothing, so
    /// the local list is byte-for-byte what it was before the preview.
    /// </summary>
    [Fact]
    public void Preview_CountsUpdatesAndCreatesWithoutWriting()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local(displayName: "本机名字"));
        int writesBefore = store.WriteCount;

        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            Envelope(
                Peer("local-2222222222222222", "webdav", "http://nas.local:5005", displayName: "改名了", revision: 9),
                Peer("local-3333333333333333", "emby", "http://new.local:8096", displayName: "新服务器")),
            store.GetAll());

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        Assert.Equal(2, summary.Total);
        Assert.Equal(1, summary.Update);
        Assert.Equal(1, summary.Create);
        Assert.Equal(0, summary.Conflict);
        Assert.True(summary.HasChanges);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Single(store.GetAll());
    }

    /// <summary>A kind this build cannot represent is counted separately and kept, not dropped (D3).</summary>
    [Fact]
    public void Preview_CountsUnsupportedPeerKind()
    {
        var store = new InMemoryLocalSourceStore();

        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            Envelope(Peer("local-4444444444444444", "fn-nas", "http://nas.local:5666")),
            store.GetAll());

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Unsupported, candidate.Action);
        Assert.Equal(SourceSyncReasons.NotSupportedOnWindows, candidate.Reason);
        Assert.Equal("fn-nas", candidate.SourceType);

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        Assert.Equal(1, summary.Unsupported);
        Assert.Equal(0, summary.Create);
    }

    /// <summary>A deletion from the peer stays a proposal: removing a source also removes its credentials.</summary>
    [Fact]
    public void Preview_DeletionRequiresConfirmation()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Local());

        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            Envelope(
                [],
                [new Tombstone { SyncSourceId = "local-2222222222222222", DeletedAtEpochMs = 1_700_000_000_000, RecordRevision = 5 }]),
            store.GetAll());

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Delete, candidate.Action);
        Assert.True(candidate.RequiresUserApproval);

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        Assert.Equal(1, summary.Delete);
        Assert.Equal(1, summary.RequiresConfirmation);
        Assert.Single(store.GetAll());
    }

    // ---- helpers -------------------------------------------------------------------------

    private static LocalSourceMappingInput Input(
        string serverId,
        ServerType type = ServerType.Unknown,
        string? sourceTypeRaw = null,
        string? url = null,
        string? accountScope = null,
        long recordRevision = 0) => new()
        {
            ServerId = serverId,
            Type = type,
            SourceTypeRaw = sourceTypeRaw,
            Url = url,
            AccountScope = accountScope,
            DisplayName = "本机名字",
            RecordRevision = recordRevision,
        };

    private static LocalSourceRecord Local(string displayName = "本机名字") => new()
    {
        ServerId = "webdav:nas",
        SyncSourceId = "local-2222222222222222",
        Type = ServerType.WebDav,
        Url = "http://nas.local:5005",
        DisplayName = displayName,
        RecordRevision = 4,
    };

    private static SourceSyncEnvelope Envelope(params SourceRecord[] sources) => Envelope(sources, []);

    private static SourceSyncEnvelope Envelope(SourceRecord[] sources, Tombstone[] deletions) => new()
    {
        DeviceId = "dev-peer",
        SnapshotId = "snap-test",
        CreatedAtEpochMs = 1_700_000_000_000,
        Capabilities = new SyncCapabilities { CanSendSecrets = false, SourceTypes = ["webdav"] },
        Sources = sources,
        Deletions = deletions,
    };

    private static SourceRecord Peer(
        string syncSourceId,
        string wireType,
        string url,
        string displayName = "对端名字",
        long revision = 4) => new()
        {
            SyncSourceId = syncSourceId,
            SourceType = SyncSourceTypeValue.Parse(wireType),
            DisplayName = displayName,
            Endpoints = [new SourceEndpoint { Url = url, IsPrimary = true, SortOrder = 0 }],
            RecordRevision = revision,
            FieldVersion = 1,
        };
}

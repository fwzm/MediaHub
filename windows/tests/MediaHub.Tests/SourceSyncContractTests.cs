using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the cross-device source sync contract (ADR-S001 D2–D5) below the UI: vocabulary, address
/// normalisation, the wire codec and the conflict rules. Nothing here opens a socket or touches a real
/// profile, so every rule is asserted as a plain function of its inputs.
/// </summary>
public sealed class SourceSyncContractTests
{
    // ---- vocabulary ----------------------------------------------------------------------

    /// <summary>
    /// The contract vocabulary is twelve lowercase strings, distinct, and not borrowed from either client's
    /// enum — a rename on either platform must break this test rather than the wire.
    /// </summary>
    [Fact]
    public void SyncSourceTypes_All_IsTheTwelveValueContractVocabulary()
    {
        IReadOnlyList<string> values = SyncSourceTypes.All;

        Assert.Equal(12, values.Count);
        Assert.Equal(values.Count, values.Distinct(StringComparer.Ordinal).Count());
        Assert.All(values, value => Assert.Equal(value.ToLowerInvariant(), value));
    }

    /// <summary>Known values are matched leniently (trimmed, case-insensitive) but never invented.</summary>
    [Theory]
    [InlineData("emby", true)]
    [InlineData("  EMBY  ", true)]
    [InlineData("webdav", true)]
    [InlineData("unknown", false)]
    [InlineData("contoso-drive", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void SyncSourceTypes_IsKnown_TrimsAndIgnoresCase(string? wire, bool expected)
        => Assert.Equal(expected, SyncSourceTypes.IsKnown(wire));

    /// <summary>
    /// The six shared values map both ways through an explicit table. Iterating the table (instead of trusting
    /// it) is the point: an accidental rename on either side fails here instead of on a user's device.
    /// </summary>
    [Fact]
    public void WindowsSourceTypeMap_MapsSixSharedValuesBothWays()
    {
        Assert.Equal(6, WindowsSourceTypeMap.Mappings.Count);
        Assert.All(WindowsSourceTypeMap.Mappings, row =>
        {
            WindowsSourceTypeResolution resolved = WindowsSourceTypeMap.Resolve(row.Wire);
            Assert.Equal(row.Type, resolved.ServerType);
            Assert.True(resolved.IsSupported);
            Assert.Equal(row.Wire, WindowsSourceTypeMap.ToWire(row.Type));
        });
    }

    /// <summary>Windows has seven enum values; only the six with a wire value are mappable, Unknown is not.</summary>
    [Fact]
    public void WindowsSourceTypeMap_SixOfSevenEnumValuesAreMappable()
    {
        ServerType[] all = Enum.GetValues<ServerType>();
        Assert.Equal(7, all.Length);
        Assert.Null(WindowsSourceTypeMap.ToWire(ServerType.Unknown));
        Assert.Equal(6, all.Count(type => WindowsSourceTypeMap.ToWire(type) is not null));
        Assert.False(WindowsSourceTypeMap.TryToServerType("unknown", out _));
    }

    /// <summary>A value this build has never heard of survives verbatim, flagged as unknown, never dropped.</summary>
    [Fact]
    public void WindowsSourceTypeMap_PreservesUnknownWireValues()
    {
        WindowsSourceTypeResolution resolved = WindowsSourceTypeMap.Resolve("Contoso-Drive");

        Assert.False(resolved.IsKnown);
        Assert.False(resolved.IsSupported);
        Assert.Equal("Contoso-Drive", resolved.SourceType.Raw);
        Assert.Equal("contoso-drive", resolved.SourceType.Wire);
        Assert.Equal(ServerType.Unknown, WindowsSourceTypeMap.ToServerType("Contoso-Drive"));
    }

    /// <summary>
    /// The Android-only kinds stay known but unsupported on Windows: they must be importable as
    /// "saved, not supported yet", not silently converted into something else and not rejected.
    /// </summary>
    [Theory]
    [InlineData(SyncSourceTypes.FnNas)]
    [InlineData(SyncSourceTypes.AliyunDrive)]
    [InlineData(SyncSourceTypes.BaiduDrive)]
    [InlineData(SyncSourceTypes.QuarkDrive)]
    [InlineData(SyncSourceTypes.ChinaMobileCloud)]
    [InlineData(SyncSourceTypes.TianyiCloud)]
    public void WindowsSourceTypeMap_AndroidOnlyValues_AreKnownButNotSupported(string wire)
    {
        WindowsSourceTypeResolution resolved = WindowsSourceTypeMap.Resolve(wire);

        Assert.True(resolved.IsKnown);
        Assert.False(resolved.IsSupported);
        Assert.Null(resolved.ServerType);
        Assert.Equal(wire, resolved.SourceType.Raw);
    }

    // ---- address normalisation -----------------------------------------------------------

    /// <summary>Ports are always written, including the scheme default — the rule Android already follows.</summary>
    [Theory]
    [InlineData("http://host/x", "http://host:80/x")]
    [InlineData("https://host/x", "https://host:443/x")]
    [InlineData("https://host", "https://host:443")]
    [InlineData("//host/path", "http://host:80/path")]
    [InlineData("host:8096/emby", "http://host:8096/emby")]
    public void SyncUrlNormalizer_AddsDefaultPorts(string input, string expected)
        => Assert.Equal(expected, SyncUrlNormalizer.Normalize(input));

    /// <summary>Scheme and host are case-insensitive; the path is not, so it keeps its case.</summary>
    [Theory]
    [InlineData("HTTPS://Example.COM/Emby/Library", "https://example.com:443/Emby/Library")]
    [InlineData("HTTP://HOST:8096", "http://host:8096")]
    public void SyncUrlNormalizer_LowercasesSchemeAndHostButKeepsPath(string input, string expected)
        => Assert.Equal(expected, SyncUrlNormalizer.Normalize(input));

    /// <summary>A trailing slash is spelling, not identity, so it never survives normalisation.</summary>
    [Theory]
    [InlineData("https://host/a/b/", "https://host:443/a/b")]
    [InlineData("https://host/", "https://host:443")]
    [InlineData("http://host/emby/", "http://host:80/emby")]
    public void SyncUrlNormalizer_StripsTrailingSlashes(string input, string expected)
        => Assert.Equal(expected, SyncUrlNormalizer.Normalize(input));

    /// <summary>Query, fragment and user info are not identity; dropping the credentials is also a safety rule.</summary>
    [Theory]
    [InlineData("https://alice:secret@host:8920/a/?token=1#frag", "https://host:8920/a")]
    [InlineData("http://host:8096/emby?api_key=abc", "http://host:8096/emby")]
    public void SyncUrlNormalizer_DropsQueryFragmentAndUserInfo(string input, string expected)
        => Assert.Equal(expected, SyncUrlNormalizer.Normalize(input));

    /// <summary>
    /// Audit §5.2: the two clients spell the same server differently. Both spellings must normalise to one
    /// identity, otherwise one server becomes two on every sync round trip.
    /// </summary>
    [Theory]
    [InlineData("http://host:80/", "http://host")]
    [InlineData("https://host:443", "https://HOST/")]
    [InlineData("http://host:8096/emby/", "http://host:8096/emby")]
    [InlineData("http://host:80/?x=1", "http://host:80")]
    public void SyncUrlNormalizer_UnifiesWindowsAndAndroidSpellings(string left, string right)
    {
        string? normalizedLeft = SyncUrlNormalizer.Normalize(left);
        string? normalizedRight = SyncUrlNormalizer.Normalize(right);

        Assert.NotNull(normalizedLeft);
        Assert.Equal(normalizedLeft, normalizedRight);
    }

    /// <summary>Anything this client cannot dial yields null instead of a half-normalised string.</summary>
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("ftp://host/share")]
    [InlineData("not a url")]
    [InlineData("mailto:alice@example.com")]
    [InlineData("alice:hunter2@host:8096")]
    [InlineData("https://")]
    public void SyncUrlNormalizer_RejectsWhatItCannotDial(string? input)
    {
        Assert.Null(SyncUrlNormalizer.Normalize(input));
        Assert.False(SyncUrlNormalizer.TryNormalize(input, out string? normalized));
        Assert.Null(normalized);
    }

    /// <summary>Normalising an already normalised address must not change it: identity has to be stable.</summary>
    [Theory]
    [InlineData("http://host")]
    [InlineData("https://HOST/emby/")]
    [InlineData("smb://nas/share")]
    [InlineData("http://host:8096/emby")]
    public void SyncUrlNormalizer_IsIdempotent(string input)
    {
        string? once = SyncUrlNormalizer.Normalize(input);
        Assert.NotNull(once);
        Assert.Equal(once, SyncUrlNormalizer.Normalize(once));
    }

    /// <summary>Schemes without a default port keep an explicit one and never get one invented.</summary>
    [Theory]
    [InlineData("smb://NAS/share/", "smb://nas/share")]
    [InlineData("smb://nas:445/share", "smb://nas:445/share")]
    public void SyncUrlNormalizer_KeepsNonHttpSchemesWithoutInventingPorts(string input, string expected)
        => Assert.Equal(expected, SyncUrlNormalizer.Normalize(input));

    // ---- codec ---------------------------------------------------------------------------

    /// <summary>Every documented field survives a write/read cycle, secrets included and untouched.</summary>
    [Fact]
    public void SourceSyncCodec_RoundTripsEveryField()
    {
        using JsonDocument secretsDocument = JsonDocument.Parse("""{"dpapi":"AAAA"}""");
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-a",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            CreatedAtEpochMs = 1_700_000_000_000,
            Capabilities = new SyncCapabilities
            {
                CanSendSecrets = true,
                SourceTypes = [SyncSourceTypes.Emby, SyncSourceTypes.WebDav],
            },
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                    SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.Emby),
                    DisplayName = "客厅 Emby",
                    AccountScope = "alice",
                    Endpoints =
                    [
                        new SourceEndpoint { Url = "http://host:8096/emby", IsPrimary = true, SortOrder = 0 },
                        new SourceEndpoint { Url = "https://host/emby", Enabled = false, SortOrder = 2 },
                    ],
                    RecordRevision = 7,
                    FieldVersion = 1,
                    Secrets = secretsDocument.RootElement.Clone(),
                },
            ],
            Deletions = [new Tombstone { SyncSourceId = "3f2504e0-4f89-41d3-9a0c-0305e82c3302", DeletedAtEpochMs = 42, RecordRevision = 3 }],
        };

        SourceSyncEnvelope read = SourceSyncCodec.Deserialize(SourceSyncCodec.Serialize(envelope));

        Assert.Equal(SourceSyncEnvelope.CurrentSchemaVersion, read.SchemaVersion);
        Assert.Equal(SourceSyncEnvelope.CurrentSchemaVersion, read.MinimumReaderVersion);
        Assert.Equal("device-a", read.DeviceId);
        Assert.Equal("0123456789abcdef0123456789abcdef", read.SnapshotId);
        Assert.Equal(1_700_000_000_000, read.CreatedAtEpochMs);
        Assert.True(read.Capabilities.CanSendSecrets);
        Assert.Equal(new[] { "emby", "webdav" }, read.Capabilities.SourceTypes);

        SourceRecord record = Assert.Single(read.Sources);
        Assert.Equal("3f2504e0-4f89-41d3-9a0c-0305e82c3301", record.SyncSourceId);
        Assert.Equal(SyncSourceTypes.Emby, record.SourceType.Raw);
        Assert.Equal("客厅 Emby", record.DisplayName);
        Assert.Equal("alice", record.AccountScope);
        Assert.Equal(7, record.RecordRevision);
        Assert.Equal(1, record.FieldVersion);
        Assert.Equal(2, record.Endpoints.Count);
        Assert.Equal("http://host:8096/emby", record.Endpoints[0].Url);
        Assert.True(record.Endpoints[0].IsPrimary);
        Assert.True(record.Endpoints[0].Enabled);
        Assert.False(record.Endpoints[1].IsPrimary);
        Assert.False(record.Endpoints[1].Enabled);
        Assert.Equal(2, record.Endpoints[1].SortOrder);
        Assert.NotNull(record.Secrets);
        Assert.Equal("""{"dpapi":"AAAA"}""", record.Secrets!.Value.GetRawText());

        Tombstone tombstone = Assert.Single(read.Deletions);
        Assert.Equal("3f2504e0-4f89-41d3-9a0c-0305e82c3302", tombstone.SyncSourceId);
        Assert.Equal(42, tombstone.DeletedAtEpochMs);
        Assert.Equal(3, tombstone.RecordRevision);
    }

    /// <summary>
    /// The wire uses the D2 camelCase names at every level. Asserted by name so a renamed property cannot
    /// silently become a protocol change.
    /// </summary>
    [Fact]
    public void SourceSyncCodec_WritesContractFieldNames()
    {
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-a",
            SnapshotId = "snap",
            CreatedAtEpochMs = 5,
            Capabilities = new SyncCapabilities { CanSendSecrets = false, SourceTypes = [SyncSourceTypes.Emby] },
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = "sync-1",
                    SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.Emby),
                    DisplayName = "Home",
                    AccountScope = "alice",
                    Endpoints = [new SourceEndpoint { Url = "http://host:8096", IsPrimary = true }],
                    RecordRevision = 2,
                },
            ],
            Deletions = [new Tombstone { SyncSourceId = "sync-2", RecordRevision = 1 }],
        };

        using JsonDocument document = JsonDocument.Parse(SourceSyncCodec.Serialize(envelope));
        JsonElement root = document.RootElement;

        AssertHasProperties(
            root,
            "schemaVersion",
            "minimumReaderVersion",
            "deviceId",
            "snapshotId",
            "createdAtEpochMs",
            "capabilities",
            "sources",
            "deletions");
        AssertHasProperties(root.GetProperty("capabilities"), "canSendSecrets", "sourceTypes");
        AssertHasProperties(
            root.GetProperty("sources")[0],
            "syncSourceId",
            "sourceType",
            "displayName",
            "accountScope",
            "endpoints",
            "recordRevision",
            "fieldVersion");
        AssertHasProperties(root.GetProperty("sources")[0].GetProperty("endpoints")[0], "url", "isPrimary", "enabled", "sortOrder");
        AssertHasProperties(root.GetProperty("deletions")[0], "syncSourceId", "deletedAtEpochMs", "recordRevision");

        // Enum-name casing leaking onto the wire is exactly the defect D3 forbids.
        Assert.False(root.TryGetProperty("DeviceId", out _));
        Assert.Equal("emby", root.GetProperty("sources")[0].GetProperty("sourceType").GetString());
    }

    /// <summary>A newer sender may add fields; an older reader must ignore them instead of failing.</summary>
    [Fact]
    public void SourceSyncCodec_ToleratesUnknownProperties()
    {
        const string json = """
            {
              "schemaVersion": 1,
              "minimumReaderVersion": 1,
              "deviceId": "device-a",
              "snapshotId": "snap",
              "createdAtEpochMs": 9,
              "futureTopLevel": { "anything": 1 },
              "capabilities": { "canSendSecrets": true, "sourceTypes": ["emby"], "futureCapability": "x" },
              "sources": [
                {
                  "syncSourceId": "sync-1",
                  "sourceType": "emby",
                  "displayName": "Home",
                  "recordRevision": 3,
                  "futureRecordField": true,
                  "endpoints": [{ "url": "http://host:8096", "isPrimary": true, "futureEndpointField": [] }]
                }
              ],
              "deletions": []
            }
            """;

        SourceSyncEnvelope read = SourceSyncCodec.Deserialize(json);

        Assert.Equal("device-a", read.DeviceId);
        Assert.True(read.Capabilities.CanSendSecrets);
        SourceRecord record = Assert.Single(read.Sources);
        Assert.Equal("sync-1", record.SyncSourceId);
        Assert.Equal("http://host:8096", Assert.Single(record.Endpoints).Url);
        Assert.Empty(read.Deletions);
    }

    /// <summary>Optional fields take their documented defaults instead of producing nulls.</summary>
    [Fact]
    public void SourceSyncCodec_AppliesDefaultsForOptionalFields()
    {
        const string json = """
            {
              "schemaVersion": 1,
              "minimumReaderVersion": 1,
              "deviceId": "device-a",
              "snapshotId": "snap",
              "createdAtEpochMs": 7,
              "sources": [
                { "syncSourceId": "sync-1", "sourceType": "emby", "recordRevision": 2, "endpoints": [{ "url": "http://host" }] }
              ]
            }
            """;

        SourceSyncEnvelope read = SourceSyncCodec.Deserialize(json);

        Assert.False(read.Capabilities.CanSendSecrets);
        Assert.Empty(read.Capabilities.SourceTypes);
        Assert.Empty(read.Deletions);
        SourceRecord record = Assert.Single(read.Sources);
        Assert.Equal("", record.DisplayName);
        Assert.Null(record.AccountScope);
        Assert.Null(record.Secrets);
        Assert.Equal(0, record.FieldVersion);
        SourceEndpoint endpoint = Assert.Single(record.Endpoints);
        Assert.False(endpoint.IsPrimary);
        Assert.True(endpoint.Enabled);
        Assert.Equal(0, endpoint.SortOrder);
    }

    /// <summary>
    /// A document that demands a newer reader is refused rather than half-understood — the one place where
    /// tolerance would be worse than failing.
    /// </summary>
    [Fact]
    public void SourceSyncCodec_RejectsNewerMinimumReaderVersion()
    {
        const string json = """
            { "schemaVersion": 1, "minimumReaderVersion": 2, "deviceId": "d", "snapshotId": "s", "createdAtEpochMs": 1, "sources": [] }
            """;

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize(json));

        Assert.Equal("unsupported-minimum-reader-version:2", error.Reason);
        Assert.False(SourceSyncCodec.TryDeserialize(json, out _, out string? reason));
        Assert.StartsWith("unsupported-minimum-reader-version:2", reason);
    }

    /// <summary>An unknown schema version is not guessed at either.</summary>
    [Fact]
    public void SourceSyncCodec_RejectsUnsupportedSchemaVersion()
    {
        const string json = """
            { "schemaVersion": 2, "minimumReaderVersion": 1, "deviceId": "d", "snapshotId": "s", "createdAtEpochMs": 1, "sources": [] }
            """;

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize(json));

        Assert.Equal("unsupported-schema-version:2", error.Reason);
    }

    /// <summary>A missing required field is reported by name, not as a null later on.</summary>
    [Theory]
    [InlineData("schemaVersion")]
    [InlineData("minimumReaderVersion")]
    [InlineData("deviceId")]
    [InlineData("snapshotId")]
    [InlineData("createdAtEpochMs")]
    public void SourceSyncCodec_ReportsMissingTopLevelField(string field)
    {
        string json = WithoutTopLevelProperty(ValidDocument(), field);

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize(json));

        Assert.Equal("missing-field:" + field, error.Reason);
    }

    /// <summary>Required record fields are enforced per record, not per document.</summary>
    [Theory]
    [InlineData("syncSourceId")]
    [InlineData("sourceType")]
    [InlineData("recordRevision")]
    public void SourceSyncCodec_ReportsMissingSourceField(string field)
    {
        string json = WithoutFirstSourceProperty(ValidDocument(), field);

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize(json));

        Assert.Equal("missing-field:" + field, error.Reason);
    }

    /// <summary>An endpoint without an address is a broken endpoint, not an endpoint with an empty address.</summary>
    [Fact]
    public void SourceSyncCodec_ReportsMissingEndpointUrl()
    {
        string json = WithoutFirstEndpointProperty(ValidDocument(), "url");

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize(json));

        Assert.Equal("missing-field:url", error.Reason);
    }

    /// <summary>Malformed JSON is reported with its own stable reason and never escapes as a raw exception.</summary>
    [Fact]
    public void SourceSyncCodec_ReportsMalformedJson()
    {
        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Deserialize("{"));

        Assert.Equal("malformed-json", error.Reason);
        Assert.False(SourceSyncCodec.TryDeserialize("not json at all", out SourceSyncEnvelope? envelope, out string? reason));
        Assert.Null(envelope);
        Assert.StartsWith("malformed-json", reason);
    }

    /// <summary>A source type this build does not know keeps its exact spelling through a round trip (D3).</summary>
    [Fact]
    public void SourceSyncCodec_PreservesUnknownSourceTypeVerbatim()
    {
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-a",
            SnapshotId = "snap",
            CreatedAtEpochMs = 3,
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = "sync-1",
                    SourceType = SyncSourceTypeValue.Parse("Contoso-Drive"),
                    DisplayName = "Future",
                    RecordRevision = 1,
                },
            ],
        };

        SourceSyncEnvelope read = SourceSyncCodec.Deserialize(SourceSyncCodec.Serialize(envelope));

        SourceRecord record = Assert.Single(read.Sources);
        Assert.Equal("Contoso-Drive", record.SourceType.Raw);
        Assert.False(record.SourceType.IsKnown);
    }

    /// <summary>Writing an envelope without an identity would produce unreadable documents, so it is refused.</summary>
    [Fact]
    public void SourceSyncCodec_RefusesToWriteInvalidEnvelope()
    {
        var envelope = new SourceSyncEnvelope { DeviceId = "", SnapshotId = "snap" };

        SyncContractException error = Assert.Throws<SyncContractException>(() => SourceSyncCodec.Serialize(envelope));

        Assert.Equal("missing-field:deviceId", error.Reason);
    }

    // ---- planner -------------------------------------------------------------------------

    /// <summary>Nothing local matches: the source is new and imports without a prompt.</summary>
    [Fact]
    public void Planner_CreatesWhenNothingMatches()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(EnvelopeWith(IncomingRecord()), []);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Create, candidate.Action);
        Assert.Equal(SourceSyncReasons.NewSource, candidate.Reason);
        Assert.Equal("http://host:8096/emby", candidate.NormalizedUrl);
        Assert.Equal(1, candidate.RemoteRevision);
        Assert.False(candidate.RequiresUserApproval);
        Assert.False(candidate.IsKnownSourceType is false && candidate.Action == SourceSyncAction.Unsupported);
    }

    /// <summary>A higher revision wins: revisions, not clocks, decide (D5).</summary>
    [Fact]
    public void Planner_UpdatesOnHigherRevision()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(revision: 4)),
            [Local(revision: 3)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Update, candidate.Action);
        Assert.Equal(SourceSyncReasons.HigherRevision, candidate.Reason);
        Assert.Equal("server-1", candidate.ServerId);
        Assert.Equal(3, candidate.LocalRevision);
        Assert.False(candidate.RequiresUserApproval);
    }

    /// <summary>
    /// Same revision, same content — even when the two devices spell the address differently. This is the
    /// audit §5.2 case: without the shared normaliser it would become a conflict on every round trip.
    /// </summary>
    [Fact]
    public void Planner_NoChangeWhenOnlyTheSpellingDiffers()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(url: "http://host:80/emby", revision: 2)),
            [Local(url: "http://HOST/emby/", revision: 2)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.NoChange, candidate.Action);
        Assert.Equal(SourceSyncReasons.UpToDate, candidate.Reason);
        Assert.False(candidate.RequiresUserApproval);
        Assert.Empty(plan.Conflicts);
    }

    /// <summary>Same revision but different content is a real disagreement: never resolved silently.</summary>
    [Fact]
    public void Planner_ConflictsOnSameRevisionDifferentContent()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(url: "http://host:9096/emby", revision: 2)),
            [Local(url: "http://host:8096/emby", revision: 2)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Conflict, candidate.Action);
        Assert.Equal(SourceSyncReasons.SameRevisionDifferentContent, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);
        Assert.Single(plan.Conflicts);
        Assert.Single(plan.RequiringApproval);
    }

    /// <summary>An older record is ignored, not treated as a conflict: being stale is not a disagreement.</summary>
    [Fact]
    public void Planner_IgnoresStaleRevision()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(revision: 2)),
            [Local(revision: 5)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.IgnoreStale, candidate.Action);
        Assert.Equal(SourceSyncReasons.StaleRevision, candidate.Reason);
        Assert.False(candidate.RequiresUserApproval);
        Assert.Empty(plan.Conflicts);
    }

    /// <summary>A deletion is proposed, never applied: the local copy may hold the only credentials left.</summary>
    [Fact]
    public void Planner_DeletesOnlyWithApproval()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            new SourceSyncEnvelope
            {
                DeviceId = "device-a",
                SnapshotId = "snap",
                CreatedAtEpochMs = 10,
                Deletions = [new Tombstone { SyncSourceId = "sync-1", DeletedAtEpochMs = 99, RecordRevision = 3 }],
            },
            [Local(revision: 3)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Delete, candidate.Action);
        Assert.Equal(SourceSyncReasons.Tombstone, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);
        Assert.Equal("server-1", candidate.ServerId);
        Assert.Equal(3, candidate.LocalRevision);
    }

    /// <summary>A deletion older than the local edit must not propose removing live data.</summary>
    [Fact]
    public void Planner_IgnoresStaleTombstone()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            new SourceSyncEnvelope
            {
                DeviceId = "device-a",
                SnapshotId = "snap",
                Deletions = [new Tombstone { SyncSourceId = "sync-1", RecordRevision = 4 }],
            },
            [Local(revision: 5)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.IgnoreStale, candidate.Action);
        Assert.Equal(SourceSyncReasons.StaleRevision, candidate.Reason);
        Assert.False(candidate.RequiresUserApproval);
    }

    /// <summary>Deleting something this device never had is neither an error nor a decision.</summary>
    [Fact]
    public void Planner_NoChangeForTombstoneOfUnknownSource()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            new SourceSyncEnvelope
            {
                DeviceId = "device-a",
                SnapshotId = "snap",
                Deletions = [new Tombstone { SyncSourceId = "sync-9", RecordRevision = 5 }],
            },
            [Local(syncSourceId: "sync-1")]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.NoChange, candidate.Action);
        Assert.Equal(SourceSyncReasons.NoLocalSource, candidate.Reason);
        Assert.False(candidate.RequiresUserApproval);
    }

    /// <summary>
    /// <c>deletedAtEpochMs</c> must not decide anything: a future timestamp on a low revision is still stale.
    /// </summary>
    [Fact]
    public void Planner_IgnoresDeletedAtTimestamp()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            new SourceSyncEnvelope
            {
                DeviceId = "device-a",
                SnapshotId = "snap",
                Deletions = [new Tombstone { SyncSourceId = "sync-1", DeletedAtEpochMs = long.MaxValue, RecordRevision = 1 }],
            },
            [Local(revision: 2)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.IgnoreStale, candidate.Action);
    }

    /// <summary>
    /// <c>createdAtEpochMs</c> must not decide anything either: two envelopes differing only in that field
    /// produce the same plan.
    /// </summary>
    [Fact]
    public void Planner_IgnoresCreatedAtClock()
    {
        SourceSyncPlan early = SourceSyncPlanner.Plan(
            EnvelopeWith(createdAtEpochMs: 0, IncomingRecord(revision: 2)),
            [Local(revision: 3)]);

        SourceSyncPlan late = SourceSyncPlanner.Plan(
            EnvelopeWith(createdAtEpochMs: long.MaxValue, IncomingRecord(revision: 2)),
            [Local(revision: 3)]);

        Assert.Equal(
            early.Items.Select(item => (item.Action, item.Reason)),
            late.Items.Select(item => (item.Action, item.Reason)));
    }

    /// <summary>A pre-W6 local source that matches on type, address and account is proposed for adoption.</summary>
    [Fact]
    public void Planner_AdoptsUniqueLegacyMatch()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord()),
            [Local(serverId: "legacy-1", syncSourceId: null, url: "http://HOST:8096/emby/", revision: 2)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Adopt, candidate.Action);
        Assert.Equal(SourceSyncReasons.LegacyIdentityMatch, candidate.Reason);
        Assert.Equal("legacy-1", candidate.ServerId);
        Assert.True(candidate.RequiresUserApproval);
    }

    /// <summary>Several equally good legacy matches: the contract refuses to guess.</summary>
    [Fact]
    public void Planner_ConflictsOnAmbiguousLegacyMatch()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord()),
            [
                Local(serverId: "legacy-1", syncSourceId: null),
                Local(serverId: "legacy-2", syncSourceId: null),
            ]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Conflict, candidate.Action);
        Assert.Equal(SourceSyncReasons.AmbiguousLegacyIdentity, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);
        Assert.Equal(new[] { "legacy-1", "legacy-2" }, candidate.CandidateServerIds);
    }

    /// <summary>The same server under another account is another source: never merged (D5).</summary>
    [Fact]
    public void Planner_DoesNotMergeAcrossAccounts()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(accountScope: "alice")),
            [Local(syncSourceId: null, accountScope: "bob")]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Create, candidate.Action);
        Assert.Equal(SourceSyncReasons.NewSource, candidate.Reason);
    }

    /// <summary>Different kinds never merge, even at the same address.</summary>
    [Fact]
    public void Planner_DoesNotMergeAcrossTypes()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(url: "http://host:8096/emby")),
            [Local(syncSourceId: null, type: ServerType.WebDav, url: "http://host:8096/emby")]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Create, candidate.Action);
    }

    /// <summary>
    /// Unsupported types are reported, not dropped: the raw value stays on the candidate so a later build
    /// (or the preview UI) can say "saved, not supported yet" instead of "sync succeeded" (D3).
    /// </summary>
    [Theory]
    [InlineData(SyncSourceTypes.FnNas, true)]
    [InlineData("Contoso-Drive", false)]
    public void Planner_KeepsUnsupportedTypes(string wire, bool known)
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(EnvelopeWith(IncomingRecord(sourceType: wire)), []);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Unsupported, candidate.Action);
        Assert.Equal(known ? SourceSyncReasons.NotSupportedOnWindows : SourceSyncReasons.UnknownSourceType, candidate.Reason);
        Assert.Equal(known, candidate.IsKnownSourceType);
        Assert.Equal(wire, candidate.SourceType);
        Assert.False(candidate.RequiresUserApproval);
    }

    /// <summary>Two local sources claiming one sync identity is a local inconsistency, not a win for either.</summary>
    [Fact]
    public void Planner_ConflictsOnDuplicateLocalSyncIds()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(revision: 9)),
            [Local(serverId: "server-1", revision: 1), Local(serverId: "server-2", revision: 2)]);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Conflict, candidate.Action);
        Assert.Equal(SourceSyncReasons.DuplicateLocalSyncId, candidate.Reason);
        Assert.True(candidate.RequiresUserApproval);
        Assert.Equal(new[] { "server-1", "server-2" }, candidate.CandidateServerIds);
    }

    /// <summary>
    /// A record with no dialable address must not be imported as a working source: the user has to supply
    /// the address, so the candidate asks for approval instead of reporting success.
    /// </summary>
    [Fact]
    public void Planner_RequiresApprovalWhenNoUsableEndpoint()
    {
        SourceSyncPlan plan = SourceSyncPlanner.Plan(
            EnvelopeWith(IncomingRecord(url: "ftp://host/share")),
            []);

        SourceSyncCandidate candidate = Assert.Single(plan.Items);
        Assert.Equal(SourceSyncAction.Create, candidate.Action);
        Assert.Equal(SourceSyncReasons.NoUsableEndpoint, candidate.Reason);
        Assert.Null(candidate.NormalizedUrl);
        Assert.True(candidate.RequiresUserApproval);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static SourceSyncEnvelope EnvelopeWith(params SourceRecord[] sources)
        => EnvelopeWith(createdAtEpochMs: 1_700_000_000_000, sources);

    private static SourceSyncEnvelope EnvelopeWith(long createdAtEpochMs, params SourceRecord[] sources)
        => new()
        {
            DeviceId = "device-a",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            CreatedAtEpochMs = createdAtEpochMs,
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
            FieldVersion = 1,
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

    private static string ValidDocument()
        => SourceSyncCodec.Serialize(new SourceSyncEnvelope
        {
            DeviceId = "device-a",
            SnapshotId = "snap",
            CreatedAtEpochMs = 5,
            Capabilities = new SyncCapabilities { SourceTypes = [SyncSourceTypes.Emby] },
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = "sync-1",
                    SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.Emby),
                    DisplayName = "Home",
                    Endpoints = [new SourceEndpoint { Url = "http://host:8096", IsPrimary = true }],
                    RecordRevision = 2,
                },
            ],
        });

    private static string WithoutTopLevelProperty(string json, string name)
    {
        JsonObject root = JsonNode.Parse(json)!.AsObject();
        root.Remove(name);
        return root.ToJsonString();
    }

    private static string WithoutFirstSourceProperty(string json, string name)
    {
        JsonObject root = JsonNode.Parse(json)!.AsObject();
        root["sources"]!.AsArray()[0]!.AsObject().Remove(name);
        return root.ToJsonString();
    }

    private static string WithoutFirstEndpointProperty(string json, string name)
    {
        JsonObject root = JsonNode.Parse(json)!.AsObject();
        root["sources"]!.AsArray()[0]!.AsObject()["endpoints"]!.AsArray()[0]!.AsObject().Remove(name);
        return root.ToJsonString();
    }

    private static void AssertHasProperties(JsonElement element, params string[] names)
    {
        foreach (string name in names)
        {
            Assert.True(element.TryGetProperty(name, out _), "expected property '" + name + "'");
        }
    }
}

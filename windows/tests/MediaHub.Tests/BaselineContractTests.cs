using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Cross-language contract for the sync baseline (W6-72): the canonical content hash, the baseline document
/// bytes, the codec decision order, merge semantics, both stores and the endpoint policy.
/// </summary>
/// <remarks>
/// <para>
/// Every expectation comes from the vector file rather than from a hand-written literal: the same JSON is
/// consumed by the Android twin <c>com.mediahub.core.common.sync.SourceSyncBaselineContractTest</c>, so a
/// deliberate divergence on either side fails one of the two suites instead of shipping.
/// </para>
/// <para>
/// The file is committed at <c>core/common/src/test/resources/sync/baseline-vectors.json</c> (Android) and at
/// <c>windows/tests/assets/baseline-vectors.json</c> (here); the two copies are byte-identical and are produced
/// by <c>D:/mh-verify/baseline-vectors</c>.
/// </para>
/// <para>
/// This side owns the <c>Advance</c> cases: the digest type they need lives only in MediaHub.Sync, so the
/// Android twin checks the constructors and skips advance while this suite pins it.
/// </para>
/// </remarks>
public sealed class BaselineContractTests
{
    private static readonly JsonDocument Vectors = JsonDocument.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "assets", "baseline-vectors.json")));

    private static readonly JsonElement Root = Vectors.RootElement;

    // ------------------------------------------------------------- element helpers

    private static JsonElement[] Entries(string name) => Root.GetProperty(name).EnumerateArray().ToArray();

    private static string Text(JsonElement element) => element.GetString()!;

    private static string Text(JsonElement entry, string name) => Text(entry.GetProperty(name));

    private static string? NullableText(JsonElement element) =>
        element.ValueKind == JsonValueKind.Null ? null : element.GetString();

    private static string? NullableText(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement element) ? NullableText(element) : null;

    private static bool Bool(JsonElement entry, string name) => entry.GetProperty(name).GetBoolean();

    private static int Int(JsonElement entry, string name) => entry.GetProperty(name).GetInt32();

    private static long Long(JsonElement entry, string name) => entry.GetProperty(name).GetInt64();

    private static SourceEndpoint EndpointOf(JsonElement element) => new()
    {
        Url = Text(element, "url"),
        IsPrimary = Bool(element, "isPrimary"),
        Enabled = Bool(element, "enabled"),
        SortOrder = Int(element, "sortOrder"),
    };

    private static SourceRecord RecordOf(JsonElement element) => new()
    {
        SyncSourceId = Text(element, "syncSourceId"),
        SourceType = SyncSourceTypeValue.Parse(NullableText(element, "sourceTypeRaw")),
        DisplayName = Text(element, "displayName"),
        AccountScope = NullableText(element, "accountScope"),
        Endpoints = element.GetProperty("endpoints").EnumerateArray().Select(EndpointOf).ToArray(),
        RecordRevision = Long(element, "recordRevision"),
        FieldVersion = Int(element, "fieldVersion"),

        // The opaque block is not part of the content hash, but the merger refuses to write a record that
        // carries one: dropping it here would silently turn "secrets-require-approval" into a direct write.
        Secrets = element.TryGetProperty("secrets", out JsonElement secrets) && secrets.ValueKind != JsonValueKind.Null
            ? secrets.Clone()
            : null,
    };

    private static SourceRecord[] RecordsOf(JsonElement? element) =>
        element is null || element.Value.ValueKind == JsonValueKind.Null
            ? []
            : element.Value.EnumerateArray().Select(RecordOf).ToArray();

    private static SourceSyncBaselineEntry EntryOf(JsonElement element) => new()
    {
        SyncSourceId = Text(element, "syncSourceId"),
        RecordRevision = Long(element, "recordRevision"),
        FieldVersion = Int(element, "fieldVersion"),
        ContentHash = Text(element, "contentHash"),
        FieldHashes = element.GetProperty("fieldHashes").EnumerateObject()
            .ToDictionary(property => property.Name, property => property.Value.GetString()!, StringComparer.Ordinal),
    };

    private static SourceSyncBaseline BaselineOf(JsonElement element) => new()
    {
        Version = Int(element, "version"),
        DeviceId = NullableText(element, "deviceId"),
        SnapshotId = NullableText(element, "snapshotId"),
        Entries = element.GetProperty("entries").EnumerateArray().Select(EntryOf).ToArray(),
    };

    private static JsonElement? Optional(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement element) ? element : null;

    private static SourceSyncBaseline? NullableBaselineOf(JsonElement entry, string name)
    {
        JsonElement? element = Optional(entry, name);
        if (element is null || element.Value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return BaselineOf(element.Value);
    }

    // ------------------------------------------------------------- digest and round helpers

    /// <summary>
    /// Reads a digest from the vector. Older sections (<c>baselineCases</c>, <c>mergeCases</c>) carry only the
    /// identity, the record and the tombstone of an item, so every optional member falls back to its default
    /// instead of throwing; the sections that do pin the full shape are the ones that assert it.
    /// </summary>
    private static SourceSyncDigest DigestOf(JsonElement element) => new()
    {
        Items = ArrayOf(element, "items", DigestItemOf),
        Issues = ArrayOf(element, "issues", IssueOf),
        IsFullSnapshot = OptionalBool(element, "isFullSnapshot"),
    };

    private static SourceSyncDigestItem DigestItemOf(JsonElement item) => new()
    {
        SyncSourceId = Text(item, "syncSourceId"),
        Kind = Enum.Parse<SourceSyncChangeKind>(Text(item, "kind"), ignoreCase: false),
        Record = OptionalObject(item, "record") is { } record ? RecordOf(record) : null,
        Tombstone = OptionalObject(item, "tombstone") is { } tombstone ? TombstoneOf(tombstone) : null,
        ChangedFields = Strings(item, "changedFields"),
        ContentChanged = OptionalBool(item, "contentChanged"),
        RecordRevision = OptionalLong(item, "recordRevision"),
        FieldVersion = OptionalInt(item, "fieldVersion"),
    };

    private static SourceSyncDigestIssue IssueOf(JsonElement issue) => new()
    {
        SyncSourceId = issue.TryGetProperty("syncSourceId", out JsonElement id) && id.ValueKind == JsonValueKind.String
            ? id.GetString()!
            : "",
        Reason = Text(issue, "reason"),
    };

    private static Tombstone TombstoneOf(JsonElement element) => new()
    {
        SyncSourceId = Text(element, "syncSourceId"),
        DeletedAtEpochMs = Long(element, "deletedAtEpochMs"),
        RecordRevision = Long(element, "recordRevision"),
    };

    private static Tombstone[] TombsOf(JsonElement entry) =>
        entry.TryGetProperty("deletions", out JsonElement deletions) && deletions.ValueKind == JsonValueKind.Array
            ? deletions.EnumerateArray().Select(TombstoneOf).ToArray()
            : [];

    private static SyncRoundEntry RoundEntryOf(JsonElement entry) => new()
    {
        SyncSourceId = Text(entry, "syncSourceId"),
        Direction = Enum.Parse<SyncMergeDirection>(Text(entry, "direction"), ignoreCase: false),
        Kind = Enum.Parse<SourceSyncChangeKind>(Text(entry, "kind"), ignoreCase: false),
        Reason = Text(entry, "reason"),
        Record = entry.GetProperty("record") is { ValueKind: JsonValueKind.Object } record ? RecordOf(record) : null,
        Tombstone = entry.GetProperty("tombstone") is { ValueKind: JsonValueKind.Object } tombstone
            ? TombstoneOf(tombstone)
            : null,
        ConflictingFields = Strings(entry, "conflictingFields"),
        LocalRevision = Long(entry, "localRevision"),
        RemoteRevision = Long(entry, "remoteRevision"),
        RequiresApproval = Bool(entry, "requiresApproval"),
    };

    private static SyncRoundEntry[] RoundEntriesOf(JsonElement result, string bucket) =>
        result.GetProperty(bucket).EnumerateArray().Select(RoundEntryOf).ToArray();

    private static T[] ArrayOf<T>(JsonElement entry, string name, Func<JsonElement, T> read) =>
        entry.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray().Select(read).ToArray()
            : [];

    private static string[] Strings(JsonElement entry, string name) => ArrayOf(entry, name, Text);

    private static JsonElement? OptionalObject(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Object ? value : null;

    private static bool OptionalBool(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.True;

    private static int OptionalInt(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Number ? value.GetInt32() : 0;

    private static long OptionalLong(JsonElement entry, string name) =>
        entry.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Number ? value.GetInt64() : 0;

    /// <summary>
    /// Everything a digest entry claims, on one line, so a divergence prints as a readable diff instead of
    /// "expected SourceSyncDigestItem, actual SourceSyncDigestItem".
    /// </summary>
    private static string Describe(SourceSyncDigestItem item)
    {
        string record = item.Record is { } value
            ? $"{value.SyncSourceId}/{value.SourceType.Wire}/{value.DisplayName}/{value.RecordRevision}/{value.FieldVersion}/{(value.Secrets is null ? "no-secrets" : "secrets")}"
            : "-";
        string tombstone = item.Tombstone is { } deleted
            ? $"{deleted.SyncSourceId}/{deleted.DeletedAtEpochMs}/{deleted.RecordRevision}"
            : "-";
        return $"{item.SyncSourceId}|{item.Kind}|{string.Join(",", item.ChangedFields)}|{item.ContentChanged}|{item.RecordRevision}|{item.FieldVersion}|{record}|{tombstone}";
    }

    private static string Describe(SyncRoundEntry entry)
    {
        string record = entry.Record is { } value
            ? $"{value.SyncSourceId}/{value.SourceType.Wire}/{value.DisplayName}/{value.RecordRevision}/{value.FieldVersion}/{(value.Secrets is null ? "no-secrets" : "secrets")}"
            : "-";
        string tombstone = entry.Tombstone is { } deleted
            ? $"{deleted.SyncSourceId}/{deleted.DeletedAtEpochMs}/{deleted.RecordRevision}"
            : "-";
        return $"{entry.SyncSourceId}|{entry.Direction}|{entry.Kind}|{entry.Reason}|{string.Join(",", entry.ConflictingFields)}|{entry.LocalRevision}|{entry.RemoteRevision}|{entry.RequiresApproval}|{record}|{tombstone}";
    }

    // ------------------------------------------------------------- planner contract (W6-75)

    /// <remarks>
    /// The planner is what decides whether an import may write anything at all, and until W6-75 it was pinned
    /// only by hand written tests on each side. This contract reads the same cases Android reads. Every case
    /// also declares the intent it is supposed to prove, so a planner that quietly changes a rule fails here
    /// even when the vector was regenerated from that same planner.
    /// Windows deliberately does not skip the windowsOnly cases: they are the ones that must produce
    /// "not-supported-on-windows". NotImportedEndpoints is out of scope, Android reads it on its own.
    /// </remarks>
    [Fact]
    public void PlannerCasesMatchTheWindowsContract()
    {
        int compared = 0;
        foreach (JsonElement entry in Entries("plannerCases"))
        {
            string name = Text(entry, "name");
            LocalSourceRecord[] locals = ArrayOf(entry, "locals", LocalOf);
            JsonElement envelopeElement = entry.GetProperty("envelope");
            var envelope = new SourceSyncEnvelope
            {
                Sources = ArrayOf(envelopeElement, "sources", RecordOf),
                Deletions = TombsOf(envelopeElement),
            };

            SourceSyncPlan plan = SourceSyncPlanner.Plan(envelope, locals);

            // 1) the declared intent: which rule this case is supposed to exercise.
            string[] expectedIntent = Strings(entry, "expect")
                .Select((item, index) => $"{name}|intent {index}|{item}")
                .ToArray();
            string[] actualIntent = plan.Items
                .Select((item, index) => $"{name}|intent {index}|{item.Action}/{item.Reason}")
                .ToArray();
            Assert.Equal(expectedIntent, actualIntent);

            // 2) the whole candidate, so a change in serverId, revisions or approval also fails.
            string[] expectedItems = ArrayOf(entry.GetProperty("plan"), "items", DescribeCandidate)
                .Select((item, index) => $"{name}|item {index}|{item}")
                .ToArray();
            string[] actualItems = plan.Items
                .Select((item, index) => $"{name}|item {index}|{Describe(item)}")
                .ToArray();
            Assert.Equal(expectedItems, actualItems);

            compared++;
        }

        Assert.Equal(Entries("plannerCases").Length, compared);
    }

    private static LocalSourceRecord LocalOf(JsonElement element) => new()
    {
        ServerId = Text(element, "serverId"),
        SyncSourceId = NullableText(element, "syncSourceId"),
        Type = WindowsSourceTypeMap.ToServerType(NullableText(element, "typeWire")),
        SourceTypeRaw = NullableText(element, "typeWire"),
        Url = NullableText(element, "url"),
        AccountScope = NullableText(element, "accountScope"),
        DisplayName = NullableText(element, "displayName"),
        RecordRevision = Long(element, "recordRevision"),
    };

    /// <summary>One candidate field as it prints in a diff: JSON null becomes a dash, empty string stays empty.</summary>
    private static string CandidateField(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            return "-";
        }

        return value.ValueKind == JsonValueKind.String ? value.GetString()! : value.GetRawText();
    }

    private static string Describe(SourceSyncCandidate item) => string.Join("|",
        item.SyncSourceId ?? "",
        item.Action,
        item.Reason,
        item.ServerId ?? "-",
        item.SourceType ?? "-",
        item.IsKnownSourceType,
        item.NormalizedUrl ?? "-",
        item.LocalRevision?.ToString() ?? "-",
        item.RemoteRevision?.ToString() ?? "-",
        item.RequiresUserApproval,
        string.Join(",", item.CandidateServerIds));

    private static string DescribeCandidate(JsonElement item) => string.Join("|",
        NullableText(item, "syncSourceId") ?? "",
        Text(item, "action"),
        Text(item, "reason"),
        CandidateField(item, "serverId"),
        CandidateField(item, "sourceType"),
        Bool(item, "isKnownSourceType"),
        CandidateField(item, "normalizedUrl"),
        CandidateField(item, "localRevision"),
        CandidateField(item, "remoteRevision"),
        Bool(item, "requiresUserApproval"),
        string.Join(",", Strings(item, "candidateServerIds")));

    [Fact]
    public void ApprovalCasesMatchTheWindowsContract()
    {
        JsonElement codes = Root.GetProperty("approvalCodes");
        Assert.Equal(
            new[] { "secrets-require-approval", "unknown-source-type", "not-supported-on-windows" },
            Strings(codes, "neverAllowable"));
        Assert.Equal(
            new[] { "delete-vs-update", "update-vs-delete", "field-conflict", "same-revision-different-content" },
            Strings(codes, "risky"));
        Assert.Equal("ambiguous-", Text(codes, "ambiguousPrefix"));

        int compared = 0;
        foreach (JsonElement entry in Entries("approvalCases"))
        {
            string name = Text(entry, "name");
            SourceSyncApprovalItem[] items = ArrayOf(entry, "items", ApprovalItemOf);
            SourceSyncApprovalDecision[] decisions = ArrayOf(entry, "decisions", DecisionOf);

            string? error = NullableText(entry, "error");
            if (error is not null)
            {
                // Assert.Throws, not Record.Exception: this file has its own Record(...) helper, and the
                // xUnit Record class is shadowed by it.
                ArgumentException failure = Assert.Throws<ArgumentException>(() => SourceSyncApprovalGate.Decide(items, decisions));
                string fragment = error == "blank-decision-identity"
                    ? "decision needs a syncSourceId"
                    : "item needs a syncSourceId";
                Assert.Contains(fragment, failure.Message, StringComparison.Ordinal);
                compared++;
                continue;
            }

            SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(items, decisions);
            AssertApproval(name, entry.GetProperty("expect"), result);
            Assert.Equal(items.Length, result.AppliedCount + result.SkippedCount + result.WithheldCount);
            compared++;
        }

        Assert.Equal(Entries("approvalCases").Length, compared);

        int comparedRounds = 0;
        foreach (JsonElement entry in Entries("approvalRoundCases"))
        {
            string name = Text(entry, "name");
            JsonElement roundJson = entry.GetProperty("round");
            SyncRoundResult round = new()
            {
                Apply = RoundEntriesOf(roundJson, "apply"),
                RequiresApproval = RoundEntriesOf(roundJson, "requiresApproval"),
                Ignored = RoundEntriesOf(roundJson, "ignored"),
            };
            SourceSyncApprovalDecision[] decisions = ArrayOf(entry, "decisions", DecisionOf);

            // The adapter is what these cases really test: a round entry has kind + reason, not an action, and the
            // row must show the primary endpoint verbatim instead of a normalised one.
            SourceSyncApprovalItem[] rows = round.Apply
                .Concat(round.RequiresApproval)
                .Select(SourceSyncApprovalItem.FromRoundEntry)
                .ToArray();
            Assert.Equal(ArrayOf(entry, "items", DescribeApprovalItemJson), rows.Select(DescribeApprovalItem).ToArray());

            SourceSyncApprovalResult result = SourceSyncApprovalGate.Decide(round, decisions);
            AssertApproval(name, entry.GetProperty("expect"), result);
            comparedRounds++;
        }

        Assert.Equal(Entries("approvalRoundCases").Length, comparedRounds);
    }

    private static void AssertApproval(string name, JsonElement expect, SourceSyncApprovalResult result)
    {
        Assert.Equal(Strings(expect, "applied"), result.Applied.Select(item => item.SyncSourceId).ToArray());
        Assert.Equal(Strings(expect, "skipped"), result.Skipped.Select(item => item.SyncSourceId).ToArray());
        Assert.Equal(Strings(expect, "withheld"), result.Withheld.Select(item => item.SyncSourceId).ToArray());
        Assert.Equal(Strings(expect, "blockingReasons"), result.BlockingReasons.ToArray());
        Assert.Equal(Bool(expect, "acknowledgedRisk"), result.AcknowledgedRisk);
        Assert.Equal(Int(expect, "appliedWithRisk"), result.AppliedWithRisk);
        Assert.Equal(Bool(expect, "canApply"), result.CanApply);
        Assert.Equal(Bool(expect, "isBlocked"), result.IsBlocked);
        Assert.Equal(result.AppliedCount + result.SkippedCount + result.WithheldCount == 0, result.IsEmpty);

        SourceSyncApprovalItem? first = result.Applied.FirstOrDefault();
        if (first is not null)
        {
            Assert.True(result.IsApplied(first.SyncSourceId), name + " should report " + first.SyncSourceId + " as applied");
            Assert.Same(first, result.Find(first.SyncSourceId));
        }

        SourceSyncApprovalItem? blocked = result.Withheld.FirstOrDefault();
        if (blocked is not null)
        {
            Assert.True(result.IsWithheld(blocked.SyncSourceId), name + " should report " + blocked.SyncSourceId + " as withheld");
        }
    }

    private static SourceSyncApprovalItem ApprovalItemOf(JsonElement entry) => new()
    {
        SyncSourceId = Text(entry, "syncSourceId"),
        Reason = Text(entry, "reason"),
        Action = Enum.Parse<SourceSyncAction>(Text(entry, "action"), ignoreCase: false),
        RequiresApproval = Bool(entry, "requiresApproval"),
        DisplayName = NullableText(entry, "displayName"),
        ServerId = NullableText(entry, "serverId"),
        SourceType = NullableText(entry, "sourceType"),
        NormalizedUrl = NullableText(entry, "normalizedUrl"),
        LocalRevision = NumberOrNull(entry, "localRevision"),
        RemoteRevision = NumberOrNull(entry, "remoteRevision"),
    };

    private static SourceSyncApprovalDecision DecisionOf(JsonElement entry) =>
        Text(entry, "choice") == "Allow"
            ? SourceSyncApprovalDecision.Allow(Text(entry, "syncSourceId"))
            : SourceSyncApprovalDecision.Skip(Text(entry, "syncSourceId"));

    private static long? NumberOrNull(JsonElement entry, string name) =>
        entry.GetProperty(name) is { ValueKind: JsonValueKind.Number } value ? value.GetInt64() : null;

    private static string DescribeApprovalItem(SourceSyncApprovalItem item) => string.Join(
        "|",
        item.SyncSourceId,
        item.Action.ToString(),
        item.Reason,
        item.RequiresApproval,
        item.DisplayName ?? "-",
        item.ServerId ?? "-",
        item.SourceType ?? "-",
        item.NormalizedUrl ?? "-",
        item.LocalRevision?.ToString() ?? "-",
        item.RemoteRevision?.ToString() ?? "-");

    private static string DescribeApprovalItemJson(JsonElement item) => string.Join(
        "|",
        Text(item, "syncSourceId"),
        Text(item, "action"),
        Text(item, "reason"),
        Bool(item, "requiresApproval"),
        NullableText(item, "displayName") ?? "-",
        NullableText(item, "serverId") ?? "-",
        NullableText(item, "sourceType") ?? "-",
        NullableText(item, "normalizedUrl") ?? "-",
        NumberOrNull(item, "localRevision")?.ToString() ?? "-",
        NumberOrNull(item, "remoteRevision")?.ToString() ?? "-");

    private static string Sha256Hex(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    // ------------------------------------------------------------- structural assertions

    /// <remarks>
    /// The C# record equality of a baseline compares the entry list by reference, so the contract compares
    /// field by field; the Android twin gets structural equality from the data class for free.
    /// </remarks>
    private static void AssertBaselineEqual(SourceSyncBaseline expected, SourceSyncBaseline actual, string label)
    {
        Assert.Equal(expected.Version, actual.Version);
        Assert.Equal(expected.DeviceId, actual.DeviceId);
        Assert.Equal(expected.SnapshotId, actual.SnapshotId);
        Assert.Equal(expected.Entries.Count, actual.Entries.Count);
        for (int index = 0; index < expected.Entries.Count; index++)
        {
            SourceSyncBaselineEntry want = expected.Entries[index];
            SourceSyncBaselineEntry got = actual.Entries[index];
            Assert.Equal(want.SyncSourceId, got.SyncSourceId);
            Assert.Equal(want.RecordRevision, got.RecordRevision);
            Assert.Equal(want.FieldVersion, got.FieldVersion);
            Assert.Equal(want.ContentHash, got.ContentHash);
            Assert.Equal(want.FieldHashes.Count, got.FieldHashes.Count);
            foreach (KeyValuePair<string, string> pair in want.FieldHashes)
            {
                Assert.True(got.FieldHashes.TryGetValue(pair.Key, out string? value), label + ": field hash " + pair.Key);
                Assert.Equal(pair.Value, value);
            }
        }
    }

    // ------------------------------------------------------------- constants and reason codes

    [Fact]
    public void ConstantsAndReasonCodesMatchTheWindowsContract()
    {
        JsonElement constants = Root.GetProperty("constants");
        Assert.Equal(
            constants.GetProperty("contentHashFields").EnumerateArray().Select(Text).ToArray(),
            SourceSyncFields.All.ToArray());
        Assert.Equal(Int(constants, "baselineCurrentVersion"), SourceSyncBaseline.CurrentVersion);
        Assert.Equal(Int(constants, "codecSchemaVersion"), SourceSyncBaselineCodec.CurrentSchemaVersion);
        Assert.Equal(Int(constants, "codecMaxEntries"), SourceSyncBaselineCodec.MaxEntries);
        Assert.Equal(Int(constants, "codecMaxFieldHashes"), SourceSyncBaselineCodec.MaxFieldHashes);
        Assert.Equal(Int(constants, "codecMaxDepth"), SourceSyncBaselineCodec.MaxDepth);
        Assert.Equal(Long(constants, "codecMaxDocumentChars"), SourceSyncBaselineCodec.MaxDocumentChars);
        Assert.Equal(Int(constants, "fileDocumentVersion"), FileSourceSyncBaselineStore.CurrentDocumentVersion);
        Assert.Equal(Int(constants, "fileMaxPeers"), FileSourceSyncBaselineStore.MaxPeers);
        Assert.Equal(Long(constants, "fileMaxDocumentChars"), FileSourceSyncBaselineStore.MaxDocumentChars);
        Assert.Equal(Text(constants, "fileName"), FileSourceSyncBaselineStore.FileName);
        Assert.Equal(Text(constants, "corruptPrefix"), FileSourceSyncBaselineStore.CorruptPrefix);

        JsonElement reasons = Root.GetProperty("reasons");
        Assert.Equal(
            reasons.GetProperty("codec").EnumerateArray().Select(Text).ToArray(),
            new[]
            {
                SourceSyncBaselineCodecReasons.MalformedJson,
                SourceSyncBaselineCodecReasons.TooLarge,
                SourceSyncBaselineCodecReasons.TooDeep,
            });
        Assert.Equal(
            reasons.GetProperty("store").EnumerateArray().Select(Text).ToArray(),
            new[]
            {
                SourceSyncBaselineStoreReasons.StaleRevision,
                SourceSyncBaselineStoreReasons.UnresolvedConflict,
                SourceSyncBaselineStoreReasons.StoreUnreadable,
                SourceSyncBaselineStoreReasons.WriteFailed,
            });
        Assert.Equal(
            reasons.GetProperty("endpoint").EnumerateArray().Select(Text).ToArray(),
            new[] { SourceSyncEndpointPolicy.SecondaryNotImported });
    }

    // ------------------------------------------------------------- content hash

    [Fact]
    public void ContentHashCasesMatchTheWindowsContract()
    {
        JsonElement[] cases = Entries("contentHashCases");
        Assert.Equal(11, cases.Length);
        foreach (JsonElement entry in cases)
        {
            string name = Text(entry, "name");
            SourceRecord record = RecordOf(entry.GetProperty("record"));
            Assert.Equal(Text(entry, "canonical"), SourceSyncContentHash.Canonicalize(record));
            Assert.Equal(Text(entry, "contentHash"), SourceSyncContentHash.Compute(record));
            Dictionary<string, string> expectedFields = entry.GetProperty("fieldHashes").EnumerateObject()
                .ToDictionary(property => property.Name, property => property.Value.GetString()!, StringComparer.Ordinal);
            Dictionary<string, string> actualFields = SourceSyncContentHash.ComputeFields(record)
                .ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
            Assert.Equal(expectedFields.Count, actualFields.Count);
            foreach (KeyValuePair<string, string> pair in expectedFields)
            {
                Assert.True(actualFields.TryGetValue(pair.Key, out string? value), name + ": " + pair.Key);
                Assert.Equal(pair.Value, value);
            }
        }
    }

    // ------------------------------------------------------------- baseline construction and advance

    [Fact]
    public void BaselineCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("baselineCases"))
        {
            string name = Text(entry, "name");
            SourceSyncBaseline expected = BaselineOf(entry.GetProperty("baseline"));
            switch (Text(entry, "source"))
            {
                case "records":
                {
                    SourceSyncBaseline produced = SourceSyncBaseline.FromRecords(
                        RecordsOf(Optional(entry, "records")),
                        NullableText(entry, "deviceId"),
                        NullableText(entry, "snapshotId"));
                    AssertBaselineEqual(expected, produced, name);
                    Assert.Equal(Bool(entry.GetProperty("baseline"), "isEmpty"), produced.IsEmpty);
                    break;
                }

                case "envelope":
                {
                    SourceSyncEnvelope envelope = new()
                    {
                        DeviceId = NullableText(entry, "deviceId") ?? "",
                        SnapshotId = NullableText(entry, "snapshotId") ?? "",
                        Sources = RecordsOf(Optional(entry, "records")),
                    };
                    SourceSyncBaseline produced = SourceSyncBaseline.FromEnvelope(envelope);
                    AssertBaselineEqual(expected, produced, name);
                    break;
                }

                case "empty":
                    AssertBaselineEqual(expected, SourceSyncBaseline.Empty, name);
                    break;

                case "advance":
                {
                    SourceSyncBaseline? initial = NullableBaselineOf(entry, "initial");
                    SourceSyncDigest digest = DigestOf(entry.GetProperty("digest"));
                    SourceSyncBaseline produced = SourceSyncBaseline.Advance(initial, digest, NullableText(entry, "snapshotIdArg"));
                    AssertBaselineEqual(expected, produced, name);
                    break;
                }

                default:
                    throw new InvalidOperationException("unknown source " + Text(entry, "source"));
            }
        }
    }

    // ------------------------------------------------------------- find

    [Fact]
    public void FindCasesMatchTheWindowsContract()
    {
        SourceSyncBaseline baseline = SourceSyncBaseline.FromRecords(new[]
        {
            Record("find-a", "A", 1),
            Record("find-b", "B", 2),
        });
        foreach (JsonElement entry in Entries("findCases"))
        {
            string? key = NullableText(entry, "key");
            SourceSyncBaselineEntry? found = baseline.Find(key);
            Assert.Equal(Bool(entry, "found"), found is not null);
            if (found is not null)
            {
                Assert.Equal(Text(entry, "syncSourceId"), found.SyncSourceId);
                Assert.Equal(Long(entry, "recordRevision"), found.RecordRevision);
            }
        }
    }

    private static SourceRecord Record(string syncSourceId, string displayName, long recordRevision) => new()
    {
        SyncSourceId = syncSourceId,
        SourceType = SyncSourceTypeValue.Parse("webdav"),
        DisplayName = displayName,
        RecordRevision = recordRevision,
        FieldVersion = 1,
    };

    // ------------------------------------------------------------- codec: write

    [Fact]
    public void CodecWriteCasesMatchTheWindowsContractByteForByte()
    {
        foreach (JsonElement entry in Entries("codecWriteCases"))
        {
            string name = Text(entry, "name");
            SourceSyncBaseline baseline = BaselineOf(entry.GetProperty("baseline"));
            string indented = SourceSyncBaselineCodec.Serialize(baseline);
            byte[] indentedBytes = SourceSyncBaselineCodec.SerializeToBytes(baseline);
            string compact = SourceSyncBaselineCodec.Serialize(baseline, indented: false);
            byte[] compactBytes = SourceSyncBaselineCodec.SerializeToBytes(baseline, indented: false);
            Assert.Equal(Text(entry, "indentedText"), indented);
            Assert.Equal(Text(entry, "indentedBase64"), Convert.ToBase64String(indentedBytes));
            Assert.Equal(Int(entry, "indentedByteLength"), indentedBytes.Length);
            Assert.Equal(Text(entry, "indentedSha256"), Sha256Hex(indentedBytes));
            Assert.Equal(Text(entry, "compactText"), compact);
            Assert.Equal(Text(entry, "compactBase64"), Convert.ToBase64String(compactBytes));
            Assert.Equal(Int(entry, "compactByteLength"), compactBytes.Length);
            Assert.Equal(Text(entry, "compactSha256"), Sha256Hex(compactBytes));
            Assert.Equal(Bool(entry, "indentedHasCarriageReturn"), indented.Contains((char)13));
            Assert.Equal(Bool(entry, "indentedEndsWithNewline"), indented.EndsWith((char)10));
        }
    }

    // ------------------------------------------------------------- codec: read

    [Fact]
    public void CodecLoadCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("codecLoadCases"))
        {
            string name = Text(entry, "name");
            string? json = NullableText(entry, "json");
            bool ok = SourceSyncBaselineCodec.TryDeserialize(json, out SourceSyncBaseline baseline, out string? error);
            Assert.Equal(Bool(entry, "ok"), ok);
            if (ok)
            {
                Assert.Null(error);
                AssertBaselineEqual(BaselineOf(entry.GetProperty("baseline")), baseline, name);
            }
            else
            {
                Assert.Equal(NullableText(entry, "error"), error);
            }
        }
    }

    [Fact]
    public void CodecLoadCasesCarryTheIgnoredFieldCount()
    {
        foreach (JsonElement entry in Entries("codecLoadCases"))
        {
            if (!Bool(entry, "ok"))
            {
                continue;
            }

            string name = Text(entry, "name");
            bool ok = SourceSyncBaselineCodec.TryDeserialize(
                NullableText(entry, "json"), out _, out int ignoredFieldCount, out string? error);
            Assert.True(ok, name + ": " + error);
            Assert.Equal(Int(entry, "ignoredFieldCount"), ignoredFieldCount);
        }
    }

    // ------------------------------------------------------------- merge

    [Fact]
    public void MergeCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("mergeCases"))
        {
            string name = Text(entry, "name");
            SourceSyncBaseline? existing = NullableBaselineOf(entry, "existing");
            SourceSyncBaseline? incoming = NullableBaselineOf(entry, "incoming");
            SourceSyncBaselineMergeResult result = SourceSyncBaselineMerge.Merge(existing, incoming);
            Assert.Equal(Bool(entry, "changed"), result.Changed);
            Assert.Equal(NullableText(entry, "reason"), result.Reason);
            Assert.Equal(
                entry.GetProperty("rejectedSyncSourceIds").EnumerateArray().Select(Text).ToArray(),
                result.RejectedSyncSourceIds.ToArray());
            AssertBaselineEqual(BaselineOf(entry.GetProperty("merged")), result.Merged, name);
        }
    }

    // ------------------------------------------------------------- in-memory store

    [Fact]
    public void InMemoryStoreCasesMatchTheWindowsContract()
    {
        InMemorySourceSyncBaselineStore store = new();
        foreach (JsonElement entry in Entries("inMemoryCases"))
        {
            string name = Text(entry, "name");
            if (name == "final-state")
            {
                Assert.Equal(Int(entry, "writeCount"), store.WriteCount);
                Assert.Equal(entry.GetProperty("knownPeers").EnumerateArray().Select(Text).ToArray(), store.KnownPeerDeviceIds.ToArray());
                AssertBaselineEqual(BaselineOf(entry.GetProperty("peerA")), store.Load("peer-a"), name + ":peerA");
                AssertBaselineEqual(BaselineOf(entry.GetProperty("peerB")), store.Load("peer-b"), name + ":peerB");
                continue;
            }

            if (name == "load-unknown")
            {
                Assert.Equal(Bool(entry, "isEmpty"), store.Load(Text(entry, "peerDeviceId")).IsEmpty);
                Assert.Equal(Int(entry, "writeCount"), store.WriteCount);
                Assert.Equal(entry.GetProperty("knownPeers").EnumerateArray().Select(Text).ToArray(), store.KnownPeerDeviceIds.ToArray());
                continue;
            }

            SourceSyncBaselineSaveResult result = store.Save(
                Text(entry, "peerDeviceId"), BaselineOf(entry.GetProperty("baseline")));
            Assert.Equal(Bool(entry, "saved"), result.Saved);
            Assert.Equal(Bool(entry, "unchanged"), result.Unchanged);
            Assert.Equal(
                entry.GetProperty("rejectedSyncSourceIds").EnumerateArray().Select(Text).ToArray(),
                result.RejectedSyncSourceIds.ToArray());
            Assert.Equal(NullableText(entry, "reason"), result.Reason);
            Assert.Equal(Int(entry, "writeCount"), store.WriteCount);
        }
    }

    // ------------------------------------------------------------- file store: document bytes

    [Fact]
    public void FileStoreDocumentCasesMatchTheWindowsContractByteForByte()
    {
        foreach (JsonElement entry in Entries("fileDocumentCases"))
        {
            string name = Text(entry, "name");
            string directory = NewTempDirectory();
            try
            {
                FileSourceSyncBaselineStore store = new(directory);
                foreach (JsonElement write in entry.GetProperty("writes").EnumerateArray())
                {
                    write.TryGetProperty("baseline", out JsonElement baselineElement);
                    store.Save(Text(write, "peerDeviceId"), BaselineOf(baselineElement));
                }

                byte[] bytes = File.ReadAllBytes(store.FilePath);
                string text = Encoding.UTF8.GetString(bytes);
                Assert.Equal(Text(entry, "fileName"), Path.GetFileName(store.FilePath));
                Assert.Equal(Text(entry, "base64"), Convert.ToBase64String(bytes));
                Assert.Equal(Text(entry, "text"), text);
                Assert.Equal(Int(entry, "byteLength"), bytes.Length);
                Assert.Equal(Text(entry, "sha256"), Sha256Hex(bytes));
                Assert.Equal(Bool(entry, "hasCarriageReturn"), text.Contains((char)13));
                Assert.Equal(Int(entry, "writeCount"), store.WriteCount);
                Assert.Equal(entry.GetProperty("peerDeviceIds").EnumerateArray().Select(Text).ToArray(), store.PeerDeviceIds.ToArray());
                Assert.Equal(Bool(entry, "backupExists"), File.Exists(store.BackupPath));
            }
            finally
            {
                Directory.Delete(directory, recursive: true);
            }
        }
    }

    private static string NewTempDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), "mh-sync-baseline-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }

    // ------------------------------------------------------------- file store: load and quarantine

    [Fact]
    public void FileStoreLoadCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("fileLoadCases"))
        {
            string name = Text(entry, "name");
            string? content = NullableText(entry, "content");
            string directory = NewTempDirectory();
            try
            {
                if (content is not null)
                {
                    File.WriteAllText(
                        Path.Combine(directory, FileSourceSyncBaselineStore.FileName),
                        content,
                        new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
                }

                FileSourceSyncBaselineStore store = new(directory);
                SourceSyncBaseline peerB = store.Load("p-b");
                string[] corrupt = Directory.GetFiles(directory, FileSourceSyncBaselineStore.CorruptPrefix + "*");
                Assert.Equal(NullableText(entry, "loadError"), store.LoadError);
                Assert.Equal(entry.GetProperty("peerDeviceIds").EnumerateArray().Select(Text).ToArray(), store.PeerDeviceIds.ToArray());
                AssertBaselineEqual(BaselineOf(entry.GetProperty("peerB")), peerB, name + ":peerB");
                AssertBaselineEqual(BaselineOf(entry.GetProperty("peerA")), store.Load("p-a"), name + ":peerA");
                Assert.Equal(Int(entry, "corruptFileCount"), corrupt.Length);
                Assert.Equal(Bool(entry, "quarantined"), store.LastQuarantinedPath is not null);
                Assert.Equal(Bool(entry, "fileStillExists"), File.Exists(store.FilePath));
            }
            finally
            {
                Directory.Delete(directory, recursive: true);
            }
        }
    }

    // ------------------------------------------------------------- endpoint policy

    [Fact]
    public void EndpointPolicyCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("endpointPolicyCases"))
        {
            string name = Text(entry, "name");
            SourceRecord record = new()
            {
                SyncSourceId = "ep-" + name,
                SourceType = SyncSourceTypeValue.Parse("webdav"),
                DisplayName = "Endpoints",
                Endpoints = entry.GetProperty("endpoints").EnumerateArray().Select(EndpointOf).ToArray(),
                RecordRevision = 1,
                FieldVersion = 1,
            };
            SourceEndpoint? primary = SourceSyncEndpointPolicy.SelectPrimary(record);
            Assert.Equal(NullableText(entry, "primaryUrl"), primary?.Url);
            int? expectedSortOrder = entry.TryGetProperty("primarySortOrder", out JsonElement sortOrderElement)
                && sortOrderElement.ValueKind == JsonValueKind.Number
                    ? sortOrderElement.GetInt32()
                    : null;
            Assert.Equal(expectedSortOrder, primary?.SortOrder);
            SourceSyncNotImportedEndpoint[] notImported = SourceSyncEndpointPolicy.NotImported(record).ToArray();
            Assert.Equal(Int(entry, "notImportedCount"), notImported.Length);
            JsonElement[] expected = entry.GetProperty("notImported").EnumerateArray().ToArray();
            for (int index = 0; index < expected.Length; index++)
            {
                SourceSyncNotImportedEndpoint want = new()
                {
                    SyncSourceId = Text(expected[index], "syncSourceId"),
                    DisplayName = Text(expected[index], "displayName"),
                    Url = Text(expected[index], "url"),
                    NormalizedUrl = NullableText(expected[index], "normalizedUrl"),
                    SortOrder = Int(expected[index], "sortOrder"),
                    IsPrimary = Bool(expected[index], "isPrimary"),
                    Enabled = Bool(expected[index], "enabled"),
                    Reason = Text(expected[index], "reason"),
                };
                Assert.Equal(want, notImported[index]);
            }
        }
    }

    [Fact]
    public void EndpointSummaryCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("endpointSummaryCases"))
        {
            string name = Text(entry, "name");
            SourceSyncEnvelope envelope = new()
            {
                DeviceId = "dev",
                SnapshotId = "snap",
                Sources = RecordsOf(Optional(entry, "sources")),
            };
            SourceSyncEndpointSummary summary = SourceSyncEndpointPolicy.Summarize(envelope);
            Assert.Equal(Int(entry, "notImportedCount"), summary.NotImportedCount);
            Assert.Equal(Int(entry, "recordsWithAlternatives"), summary.RecordsWithAlternatives);
            Assert.Equal(Int(entry, "recordsWithoutEndpoint"), summary.RecordsWithoutEndpoint);
            Assert.Equal(Bool(entry, "isEmpty"), summary.IsEmpty);
            JsonElement[] expected = entry.GetProperty("items").EnumerateArray().ToArray();
            Assert.Equal(expected.Length, summary.Items.Count);
            for (int index = 0; index < expected.Length; index++)
            {
                Assert.Equal(Text(expected[index], "syncSourceId"), summary.Items[index].SyncSourceId);
                Assert.Equal(Text(expected[index], "url"), summary.Items[index].Url);
                Assert.Equal(NullableText(expected[index], "normalizedUrl"), summary.Items[index].NormalizedUrl);
                Assert.Equal(Int(expected[index], "sortOrder"), summary.Items[index].SortOrder);
                Assert.Equal(Bool(expected[index], "isPrimary"), summary.Items[index].IsPrimary);
            }
        }
    }

    // ------------------------------------------------------------- digest and round

    /// <summary>
    /// The change set a push would carry: what the peer does not know yet, given a baseline and the tombstones.
    /// </summary>
    /// <remarks>
    /// The expectations pin three things a plausible implementation gets wrong: a record whose revision moved
    /// but whose content did not is still sent (so the peer's revision catches up) yet claims no changed field;
    /// a record behind the baseline is dropped rather than pushed; and a record that cannot be represented on
    /// the wire becomes an issue instead of vanishing.
    /// </remarks>
    [Fact]
    public void DigestCasesMatchTheWindowsContract()
    {
        int compared = 0;
        foreach (JsonElement entry in Entries("digestCases"))
        {
            string name = Text(entry, "name");
            SourceRecord[] records = RecordsOf(entry.GetProperty("records"));
            SourceSyncBaseline? baseline = NullableBaselineOf(entry, "baseline");

            SourceSyncDigest actual = SourceSyncDigest.Build(records, baseline, TombsOf(entry));
            JsonElement expected = entry.GetProperty("digest");

            Assert.Equal(Bool(expected, "isFullSnapshot"), actual.IsFullSnapshot);
            Assert.Equal(Bool(expected, "isEmpty"), actual.IsEmpty);
            Assert.Equal(Int(expected, "addedCount"), actual.Added.Count);
            Assert.Equal(Int(expected, "updatedCount"), actual.Updated.Count);
            Assert.Equal(Int(expected, "removedCount"), actual.Removed.Count);

            JsonElement[] expectedItems = expected.GetProperty("items").EnumerateArray().ToArray();
            Assert.Equal(expectedItems.Length, actual.Items.Count);
            for (int index = 0; index < expectedItems.Length; index++)
            {
                string expectedLine = $"{name}|item {index}|{Describe(DigestItemOf(expectedItems[index]))}";
                Assert.Equal(expectedLine, $"{name}|item {index}|{Describe(actual.Items[index])}");
            }

            string[] expectedIssues = expected.GetProperty("issues").EnumerateArray()
                .Select(issue => $"{IssueOf(issue).SyncSourceId}:{IssueOf(issue).Reason}")
                .ToArray();
            Assert.Equal(
                $"{name}|issues|{string.Join(";", expectedIssues)}",
                $"{name}|issues|{string.Join(";", actual.Issues.Select(issue => $"{issue.SyncSourceId}:{issue.Reason}"))}");
            compared++;
        }

        Assert.Equal(Entries("digestCases").Length, compared);
    }

    /// <summary>
    /// The two-way round: which decisions may be written, which wait for the user, and which need nothing.
    /// </summary>
    /// <remarks>
    /// This suite owns every case, including <c>windows-does-not-support-cloud-types</c>: the Android twin cannot
    /// assert it (its type map knows the cloud types), while here it must produce a
    /// <c>not-supported-on-windows</c> approval rather than a write. Skipping it would leave the one case that
    /// protects the Windows provider list unasserted on Windows.
    /// </remarks>
    [Fact]
    public void MergerCasesMatchTheWindowsContract()
    {
        int compared = 0;
        foreach (JsonElement entry in Entries("mergerCases"))
        {
            string name = Text(entry, "name");
            SourceSyncDigest local = DigestOf(entry.GetProperty("local"));
            SourceSyncDigest remote = DigestOf(entry.GetProperty("remote"));
            SourceSyncBaseline? baseline = NullableBaselineOf(entry, "baseline");

            SyncRoundResult actual = SyncRoundMerger.Merge(local, remote, baseline);
            JsonElement expected = entry.GetProperty("result");

            foreach (string bucket in new[] { "apply", "requiresApproval", "ignored" })
            {
                SyncRoundEntry[] expectedEntries = RoundEntriesOf(expected, bucket);
                IReadOnlyList<SyncRoundEntry> actualEntries = bucket switch
                {
                    "apply" => actual.Apply,
                    "requiresApproval" => actual.RequiresApproval,
                    _ => actual.Ignored,
                };

                Assert.Equal(expectedEntries.Length, actualEntries.Count);
                for (int index = 0; index < expectedEntries.Length; index++)
                {
                    string expectedLine = $"{name}|{bucket}|{index}|{Describe(expectedEntries[index])}";
                    Assert.Equal(expectedLine, $"{name}|{bucket}|{index}|{Describe(actualEntries[index])}");
                }
            }

            Assert.Equal(Bool(expected, "hasWork"), actual.HasWork);

            // The three buckets partition the round: an identity in two buckets is how a review screen
            // silently applies a change it also offers to cancel.
            Assert.Equal(actual.Entries.Count, actual.Apply.Count + actual.RequiresApproval.Count + actual.Ignored.Count);
            compared++;
        }

        Assert.Equal(Entries("mergerCases").Length, compared);
    }
}

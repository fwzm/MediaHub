using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the baseline document: deterministic bytes, neutral defaults, bounded parsing and a version gate.
/// </summary>
/// <remarks>
/// The baseline decides whether a round is incremental or a full snapshot, so a codec that silently drops or
/// reorders an entry would make two devices ping-pong forever.
/// </remarks>
public sealed class SourceSyncBaselineCodecTests
{
    private static SourceSyncBaselineEntry Entry(
        string id,
        long revision = 3,
        int fieldVersion = 0,
        string contentHash = "hash-a",
        params (string Field, string Hash)[] fields)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach ((string field, string hash) in fields)
        {
            hashes[field] = hash;
        }

        return new SourceSyncBaselineEntry
        {
            SyncSourceId = id,
            RecordRevision = revision,
            FieldVersion = fieldVersion,
            ContentHash = contentHash,
            FieldHashes = hashes,
        };
    }

    private static SourceSyncBaseline Baseline(params SourceSyncBaselineEntry[] entries) => new()
    {
        DeviceId = "device-a",
        SnapshotId = "snapshot-1",
        Entries = entries,
    };

    /// <summary>Every field survives a round trip, including a per-field hash map.</summary>
    [Fact]
    public void RoundTrip_PreservesEveryField()
    {
        SourceSyncBaseline original = Baseline(
            Entry("sync-1", 7, 2, "abc", ("displayName", "h1"), ("sourceType", "h2")),
            Entry("sync-2", revision: 1));

        SourceSyncBaseline parsed = SourceSyncBaselineCodec.Deserialize(SourceSyncBaselineCodec.Serialize(original));

        Assert.Equal("device-a", parsed.DeviceId);
        Assert.Equal("snapshot-1", parsed.SnapshotId);
        Assert.Equal(2, parsed.Entries.Count);
        SourceSyncBaselineEntry first = parsed.Entries[0];
        Assert.Equal("sync-1", first.SyncSourceId);
        Assert.Equal(7, first.RecordRevision);
        Assert.Equal(2, first.FieldVersion);
        Assert.Equal("abc", first.ContentHash);
        Assert.Equal("h1", first.FieldHashes["displayName"]);
        Assert.Equal("h2", first.FieldHashes["sourceType"]);
    }

    /// <summary>The same baseline always serialises to the same bytes, whatever order the caller built it in.</summary>
    [Fact]
    public void Serialize_IsDeterministicAndOrderIndependent()
    {
        SourceSyncBaseline first = new()
        {
            DeviceId = "device-a",
            Entries =
            [
                Entry("sync-b", 2, 0, "h-b", ("sourceType", "1"), ("displayName", "2")),
                Entry("sync-a", revision: 1),
            ],
        };

        SourceSyncBaseline second = new()
        {
            DeviceId = "device-a",
            Entries =
            [
                Entry("sync-a", revision: 1),
                Entry("sync-b", 2, 0, "h-b", ("displayName", "2"), ("sourceType", "1")),
            ],
        };

        string left = SourceSyncBaselineCodec.Serialize(first);
        string right = SourceSyncBaselineCodec.Serialize(second);
        Assert.Equal(left, right);

        // And a parse/serialise cycle is a fixed point, which is what lets the file store compare documents.
        Assert.Equal(left, SourceSyncBaselineCodec.Serialize(SourceSyncBaselineCodec.Deserialize(left)));
    }

    /// <summary>Field order is part of the contract: version first, entries sorted by syncSourceId.</summary>
    [Fact]
    public void Serialize_WritesStableKeyAndEntryOrder()
    {
        SourceSyncBaseline baseline = Baseline(Entry("sync-b", contentHash: "b"), Entry("sync-a", contentHash: "a"));

        using JsonDocument document = JsonDocument.Parse(SourceSyncBaselineCodec.Serialize(baseline));
        JsonElement root = document.RootElement;
        Assert.Equal(
            new[] { "version", "deviceId", "snapshotId", "entries" },
            root.EnumerateObject().Select(property => property.Name).ToArray());
        Assert.Equal(
            new[] { "sync-a", "sync-b" },
            root.GetProperty("entries").EnumerateArray().Select(element => element.GetProperty("syncSourceId").GetString()).ToArray());
        Assert.Equal(
            new[] { "syncSourceId", "recordRevision", "fieldVersion", "contentHash", "fieldHashes" },
            root.GetProperty("entries")[0].EnumerateObject().Select(property => property.Name).ToArray());
    }

    /// <summary>The file is UTF-8 without a byte order mark; a BOM would break the first-byte stability check.</summary>
    [Fact]
    public void SerializeToBytes_StartsWithAnObjectBrace()
    {
        byte[] bytes = SourceSyncBaselineCodec.SerializeToBytes(Baseline(Entry("sync-1")));

        Assert.Equal((byte)(char)'{', bytes[0]);
        Assert.Equal(bytes.Length, Encoding.UTF8.GetByteCount(Encoding.UTF8.GetString(bytes)));
    }

    /// <summary>Missing optional fields default to neutral values; an absent hash map means "unknown", not "unchanged".</summary>
    [Fact]
    public void Deserialize_DefaultsMissingOptionalFields()
    {
        const string json = "{\"version\":1,\"entries\":[{\"syncSourceId\":\" sync-1 \"}]}";

        SourceSyncBaseline baseline = SourceSyncBaselineCodec.Deserialize(json);

        Assert.Null(baseline.DeviceId);
        Assert.Null(baseline.SnapshotId);
        SourceSyncBaselineEntry entry = Assert.Single(baseline.Entries);
        Assert.Equal("sync-1", entry.SyncSourceId);
        Assert.Equal(0, entry.RecordRevision);
        Assert.Equal(0, entry.FieldVersion);
        Assert.Equal("", entry.ContentHash);
        Assert.Empty(entry.FieldHashes);
    }

    /// <summary>Unknown fields are ignored but counted, so a newer writer can add one without breaking this reader.</summary>
    [Fact]
    public void Deserialize_CountsUnknownFieldsInsteadOfFailing()
    {
        const string json =
            "{\"version\":1,\"deviceId\":\"d\",\"future\":true,\"entries\":[{\"syncSourceId\":\"s\",\"recordRevision\":2,\"futureToo\":1}]}";

        Assert.True(SourceSyncBaselineCodec.TryDeserialize(json, out SourceSyncBaseline baseline, out int ignored, out string? error));
        Assert.Null(error);
        Assert.Equal(2, ignored);
        Assert.Equal("d", baseline.DeviceId);
        Assert.Equal(2, Assert.Single(baseline.Entries).RecordRevision);
    }

    /// <summary>A newer document is refused by version, never parsed field by field on a guess.</summary>
    [Fact]
    public void Deserialize_RefusesANewerSchema()
    {
        Assert.False(SourceSyncBaselineCodec.TryDeserialize("{\"version\":2,\"entries\":[]}", out _, out string? error));
        Assert.Equal("unsupported-schema-version:2", error);
    }

    /// <summary>Broken JSON and an empty document are the same failure to a caller: nothing usable.</summary>
    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("{")]
    [InlineData("[]")]
    [InlineData("{\"entries\":[]}")]
    [InlineData("{\"version\":1,\"entries\":{}}")]
    [InlineData("{\"version\":1,\"entries\":[{\"recordRevision\":1}]}")]
    public void Deserialize_RejectsUnusableDocuments(string json)
    {
        Assert.False(SourceSyncBaselineCodec.TryDeserialize(json, out _, out string? error));
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    /// <summary>Deep nesting is refused before it can exhaust the stack: damage, not data.</summary>
    [Fact]
    public void Deserialize_RefusesDeeplyNestedJson()
    {
        string deep = "{\"version\":1,\"entries\":[],\"junk\":" + new string('[', 40) + new string(']', 40) + "}";

        Assert.False(SourceSyncBaselineCodec.TryDeserialize(deep, out _, out string? error));
        Assert.Equal(SourceSyncBaselineCodecReasons.TooDeep, error);
    }

    /// <summary>An oversized document is refused by length, before any array is materialised.</summary>
    [Fact]
    public void Deserialize_RefusesAnOversizedDocument()
    {
        string huge = new string('x', (int)SourceSyncBaselineCodec.MaxDocumentChars + 1);

        Assert.False(SourceSyncBaselineCodec.TryDeserialize(huge, out _, out string? error));
        Assert.Equal(SourceSyncBaselineCodecReasons.TooLarge, error);
    }

    /// <summary>A duplicate identity is a hand-edited file, not a protocol state: the last writer wins deterministically.</summary>
    [Fact]
    public void Deserialize_KeepsTheLastDuplicateEntry()
    {
        const string json =
            "{\"version\":1,\"entries\":[{\"syncSourceId\":\"s\",\"recordRevision\":1},{\"syncSourceId\":\"S\",\"recordRevision\":9}]}";

        SourceSyncBaseline baseline = SourceSyncBaselineCodec.Deserialize(json);

        SourceSyncBaselineEntry entry = Assert.Single(baseline.Entries);
        Assert.Equal(9, entry.RecordRevision);
    }

    /// <summary>An empty baseline is a legal document: "we know nothing", not a corrupt one.</summary>
    [Fact]
    public void Deserialize_AcceptsAnEmptyBaseline()
    {
        SourceSyncBaseline baseline = SourceSyncBaselineCodec.Deserialize("{\"version\":1,\"entries\":[]}");

        Assert.True(baseline.IsEmpty);
        Assert.Empty(baseline.Entries);
    }
}

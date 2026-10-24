using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>Stable reason codes produced when a sync baseline document is rejected.</summary>
public static class SourceSyncBaselineCodecReasons
{
    /// <summary>The document is not JSON, or not the JSON object this codec writes.</summary>
    public const string MalformedJson = "malformed-json";

    /// <summary>The document is longer than <see cref="SourceSyncBaselineCodec.MaxDocumentChars"/>.</summary>
    public const string TooLarge = "baseline-too-large";

    /// <summary>The document nests deeper than <see cref="SourceSyncBaselineCodec.MaxDepth"/>.</summary>
    public const string TooDeep = "baseline-too-deep";
}

/// <summary>
/// JSON codec for <see cref="SourceSyncBaseline"/>: the peer-side fact that makes an incremental round
/// incremental in the first place.
/// </summary>
/// <remarks>
/// <para>
/// Two properties matter more than the shape. <b>Determinism</b>: entries are written sorted by
/// <c>syncSourceId</c> and field hashes sorted by field name, so the same baseline always serialises to the same
/// bytes — a file store can therefore compare two documents instead of diffing them semantically, and a test can
/// pin the exact bytes. <b>Bounded work</b>: a hand-edited or hostile file is refused by length and nesting depth
/// before it can exhaust the stack, because a baseline that crashes the app on load is worse than one that is
/// treated as missing.
/// </para>
/// <para>
/// Missing optional fields default to neutral values (<c>deviceId</c>/<c>snapshotId</c> null, revisions 0, an
/// absent <c>fieldHashes</c> map empty — which the digest reads as "unknown", never as "unchanged"). Unknown
/// fields are ignored and counted, so a newer writer can add one without breaking this reader, and a test can
/// prove the count instead of guessing.
/// </para>
/// </remarks>
public static class SourceSyncBaselineCodec
{
    /// <summary>Document version this build writes and the only one it reads.</summary>
    public const int CurrentSchemaVersion = SourceSyncBaseline.CurrentVersion;

    /// <summary>Upper bound on entries in one document; beyond this the file is treated as hostile, not as data.</summary>
    public const int MaxEntries = 50_000;

    /// <summary>Upper bound on per-field hashes in one entry.</summary>
    public const int MaxFieldHashes = 64;

    /// <summary>Maximum JSON nesting this reader accepts. The document itself never nests deeper than three.</summary>
    public const int MaxDepth = 16;

    /// <summary>Maximum document length in characters, checked before parsing.</summary>
    public const long MaxDocumentChars = 8L * 1024 * 1024;

    /// <summary>Serialises a baseline as UTF-8 JSON text.</summary>
    public static string Serialize(SourceSyncBaseline baseline, bool indented = true)
    {
        ArgumentNullException.ThrowIfNull(baseline);
        return Encoding.UTF8.GetString(SerializeToBytes(baseline, indented));
    }

    /// <summary>Serialises a baseline as UTF-8 bytes, without a byte order mark.</summary>
    public static byte[] SerializeToBytes(SourceSyncBaseline baseline, bool indented = true)
    {
        ArgumentNullException.ThrowIfNull(baseline);

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = indented }))
        {
            WriteBaseline(writer, baseline);
        }

        return buffer.ToArray();
    }

    /// <summary>Writes one baseline object; used by the file store to embed baselines in its own document.</summary>
    internal static void WriteBaseline(Utf8JsonWriter writer, SourceSyncBaseline baseline)
    {
        ArgumentNullException.ThrowIfNull(writer);
        ArgumentNullException.ThrowIfNull(baseline);

        writer.WriteStartObject();
        writer.WriteNumber("version", CurrentSchemaVersion);
        WriteNullableString(writer, "deviceId", baseline.DeviceId);
        WriteNullableString(writer, "snapshotId", baseline.SnapshotId);
        writer.WriteStartArray("entries");

        foreach (SourceSyncBaselineEntry entry in OrderEntries(baseline.Entries))
        {
            writer.WriteStartObject();
            writer.WriteString("syncSourceId", entry.SyncSourceId);
            writer.WriteNumber("recordRevision", entry.RecordRevision);
            writer.WriteNumber("fieldVersion", entry.FieldVersion);
            writer.WriteString("contentHash", entry.ContentHash ?? "");
            writer.WriteStartObject("fieldHashes");
            foreach ((string field, string hash) in OrderFields(entry.FieldHashes))
            {
                writer.WriteString(field, hash ?? "");
            }

            writer.WriteEndObject();
            writer.WriteEndObject();
        }

        writer.WriteEndArray();
        writer.WriteEndObject();
    }

    /// <summary>Reads a baseline document.</summary>
    /// <exception cref="SyncContractException">The document is malformed, too large, too deep or a newer version.</exception>
    public static SourceSyncBaseline Deserialize(string json) => Deserialize(json, out _);

    /// <summary>Reads a baseline document and reports how many unknown fields were ignored.</summary>
    /// <exception cref="SyncContractException">The document is malformed, too large, too deep or a newer version.</exception>
    public static SourceSyncBaseline Deserialize(string json, out int ignoredFieldCount)
    {
        if (!TryDeserialize(json, out SourceSyncBaseline baseline, out ignoredFieldCount, out string? error))
        {
            throw new SyncContractException(
                error ?? SourceSyncBaselineCodecReasons.MalformedJson,
                "The sync baseline document was rejected with reason " + (error ?? "unknown") + ".");
        }

        return baseline;
    }

    /// <summary>Reads a baseline document without throwing; the error carries the same reason code as the exception.</summary>
    public static bool TryDeserialize(string? json, out SourceSyncBaseline baseline, out string? error)
        => TryDeserialize(json, out baseline, out _, out error);

    /// <summary>Reads a baseline document without throwing, reporting ignored unknown fields.</summary>
    public static bool TryDeserialize(
        string? json,
        out SourceSyncBaseline baseline,
        out int ignoredFieldCount,
        out string? error)
    {
        baseline = SourceSyncBaseline.Empty;
        ignoredFieldCount = 0;
        error = null;

        if (string.IsNullOrWhiteSpace(json))
        {
            error = SourceSyncBaselineCodecReasons.MalformedJson;
            return false;
        }

        if (json.Length > MaxDocumentChars)
        {
            error = SourceSyncBaselineCodecReasons.TooLarge;
            return false;
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = MaxDepth });
        }
        catch (JsonException exception)
        {
            error = exception.Message.Contains("depth", StringComparison.OrdinalIgnoreCase)
                ? SourceSyncBaselineCodecReasons.TooDeep
                : SourceSyncBaselineCodecReasons.MalformedJson;
            return false;
        }

        using (document)
        {
            return TryRead(document.RootElement, out baseline, out ignoredFieldCount, out error);
        }
    }

    private static bool TryRead(
        JsonElement root,
        out SourceSyncBaseline baseline,
        out int ignoredFieldCount,
        out string? error)
    {
        baseline = SourceSyncBaseline.Empty;
        ignoredFieldCount = 0;
        error = null;

        if (root.ValueKind != JsonValueKind.Object)
        {
            error = SourceSyncBaselineCodecReasons.MalformedJson;
            return false;
        }

        int ignored = 0;
        if (!root.TryGetProperty("version", out JsonElement versionElement))
        {
            error = "missing-field:version";
            return false;
        }

        if (versionElement.ValueKind != JsonValueKind.Number || !versionElement.TryGetInt32(out int version))
        {
            error = "invalid-field:version";
            return false;
        }

        if (version != CurrentSchemaVersion)
        {
            error = "unsupported-schema-version:" + version.ToString(CultureInfo.InvariantCulture);
            return false;
        }

        if (!TryReadNullableString(root, "deviceId", out string? deviceId, out error)
            || !TryReadNullableString(root, "snapshotId", out string? snapshotId, out error))
        {
            return false;
        }

        var entries = new List<SourceSyncBaselineEntry>();
        var index = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        if (root.TryGetProperty("entries", out JsonElement entriesElement)
            && entriesElement.ValueKind != JsonValueKind.Null)
        {
            if (entriesElement.ValueKind != JsonValueKind.Array)
            {
                error = "invalid-field:entries";
                return false;
            }

            if (entriesElement.GetArrayLength() > MaxEntries)
            {
                error = SourceSyncBaselineCodecReasons.TooLarge;
                return false;
            }

            foreach (JsonElement element in entriesElement.EnumerateArray())
            {
                if (!TryReadEntry(element, ref ignored, out SourceSyncBaselineEntry? entry, out error) || entry is null)
                {
                    return false;
                }

                // Last writer wins, exactly like the paired-device document: a hand-edited duplicate must not make
                // the result depend on enumeration order.
                if (index.TryGetValue(entry.SyncSourceId, out int existingAt))
                {
                    entries[existingAt] = entry;
                }
                else
                {
                    index[entry.SyncSourceId] = entries.Count;
                    entries.Add(entry);
                }
            }
        }

        foreach (JsonProperty property in root.EnumerateObject())
        {
            if (!IsKnownRootField(property.Name))
            {
                ignored++;
            }
        }

        baseline = new SourceSyncBaseline
        {
            Version = CurrentSchemaVersion,
            DeviceId = deviceId,
            SnapshotId = snapshotId,
            Entries = entries,
        };
        ignoredFieldCount = ignored;
        return true;
    }

    private static bool TryReadEntry(
        JsonElement element,
        ref int ignored,
        out SourceSyncBaselineEntry? entry,
        out string? error)
    {
        entry = null;
        error = null;

        if (element.ValueKind != JsonValueKind.Object)
        {
            error = "invalid-field:entries";
            return false;
        }

        if (!element.TryGetProperty("syncSourceId", out JsonElement idElement)
            || idElement.ValueKind != JsonValueKind.String
            || string.IsNullOrWhiteSpace(idElement.GetString()))
        {
            error = "invalid-field:syncSourceId";
            return false;
        }

        if (!TryReadInt64(element, "recordRevision", out long recordRevision, out error)
            || !TryReadInt32(element, "fieldVersion", out int fieldVersion, out error))
        {
            return false;
        }

        string contentHash = "";
        if (element.TryGetProperty("contentHash", out JsonElement hashElement)
            && hashElement.ValueKind != JsonValueKind.Null)
        {
            if (hashElement.ValueKind != JsonValueKind.String)
            {
                error = "invalid-field:contentHash";
                return false;
            }

            contentHash = hashElement.GetString() ?? "";
        }

        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        if (element.TryGetProperty("fieldHashes", out JsonElement hashesElement)
            && hashesElement.ValueKind != JsonValueKind.Null)
        {
            if (hashesElement.ValueKind != JsonValueKind.Object)
            {
                error = "invalid-field:fieldHashes";
                return false;
            }

            if (hashesElement.EnumerateObject().Count() > MaxFieldHashes)
            {
                error = SourceSyncBaselineCodecReasons.TooLarge;
                return false;
            }

            foreach (JsonProperty property in hashesElement.EnumerateObject())
            {
                if (property.Value.ValueKind != JsonValueKind.String)
                {
                    error = "invalid-field:fieldHashes";
                    return false;
                }

                hashes[property.Name] = property.Value.GetString() ?? "";
            }
        }

        foreach (JsonProperty property in element.EnumerateObject())
        {
            if (!IsKnownEntryField(property.Name))
            {
                ignored++;
            }
        }

        entry = new SourceSyncBaselineEntry
        {
            SyncSourceId = idElement.GetString()!.Trim(),
            RecordRevision = recordRevision,
            FieldVersion = fieldVersion,
            ContentHash = contentHash,
            FieldHashes = hashes,
        };
        return true;
    }

    private static bool TryReadNullableString(
        JsonElement parent,
        string name,
        out string? value,
        out string? error)
    {
        value = null;
        error = null;
        if (!parent.TryGetProperty(name, out JsonElement element) || element.ValueKind == JsonValueKind.Null)
        {
            return true;
        }

        if (element.ValueKind != JsonValueKind.String)
        {
            error = "invalid-field:" + name;
            return false;
        }

        value = element.GetString();
        return true;
    }

    private static bool TryReadInt64(JsonElement parent, string name, out long value, out string? error)
    {
        value = 0;
        error = null;
        if (!parent.TryGetProperty(name, out JsonElement element) || element.ValueKind == JsonValueKind.Null)
        {
            return true;
        }

        if (element.ValueKind != JsonValueKind.Number || !element.TryGetInt64(out value))
        {
            error = "invalid-field:" + name;
            return false;
        }

        return true;
    }

    private static bool TryReadInt32(JsonElement parent, string name, out int value, out string? error)
    {
        value = 0;
        error = null;
        if (!parent.TryGetProperty(name, out JsonElement element) || element.ValueKind == JsonValueKind.Null)
        {
            return true;
        }

        if (element.ValueKind != JsonValueKind.Number || !element.TryGetInt32(out value))
        {
            error = "invalid-field:" + name;
            return false;
        }

        return true;
    }

    private static bool IsKnownRootField(string name)
        => name is "version" or "deviceId" or "snapshotId" or "entries";

    private static bool IsKnownEntryField(string name)
        => name is "syncSourceId" or "recordRevision" or "fieldVersion" or "contentHash" or "fieldHashes";

    private static void WriteNullableString(Utf8JsonWriter writer, string name, string? value)
    {
        if (value is null)
        {
            writer.WriteNull(name);
        }
        else
        {
            writer.WriteString(name, value);
        }
    }

    private static IReadOnlyList<SourceSyncBaselineEntry> OrderEntries(IReadOnlyList<SourceSyncBaselineEntry> entries)
        => entries
            .Where(entry => entry is not null && !string.IsNullOrWhiteSpace(entry.SyncSourceId))
            .OrderBy(entry => entry.SyncSourceId, StringComparer.OrdinalIgnoreCase)
            .ThenBy(entry => entry.SyncSourceId, StringComparer.Ordinal)
            .ToList();

    private static IReadOnlyList<KeyValuePair<string, string>> OrderFields(IReadOnlyDictionary<string, string> hashes)
        => hashes
            .Where(pair => !string.IsNullOrWhiteSpace(pair.Key))
            .OrderBy(pair => pair.Key, StringComparer.Ordinal)
            .ToList();
}

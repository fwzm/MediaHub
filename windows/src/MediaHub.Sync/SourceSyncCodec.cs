using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// Reads and writes <see cref="SourceSyncEnvelope"/> as JSON (ADR-S001 D2).
/// </summary>
/// <remarks>
/// <para>
/// The reader is tolerant where the wire can be, strict where a mistake would be silent:
/// </para>
/// <list type="bullet">
/// <item><description>unknown properties are ignored, so a newer sender does not break an older reader;</description></item>
/// <item><description>absent optional properties take their documented default;</description></item>
/// <item><description><c>minimumReaderVersion</c> above <see cref="SourceSyncEnvelope.CurrentSchemaVersion"/>
/// throws instead of guessing — that is the one case where ignoring is worse than refusing;</description></item>
/// <item><description>a missing or empty required field throws <see cref="SyncContractException"/> with a
/// <c>missing-field:</c> or <c>invalid-field:</c> reason rather than producing a half-built record that would
/// later be compared against real data.</description></item>
/// </list>
/// <para>
/// Serialisation always writes the canonical field names and the canonical shape, including defaults; the output
/// is therefore stable and diffable. Secrets are copied through untouched — this layer never inspects them.
/// </para>
/// </remarks>
public static class SourceSyncCodec
{
    private const string MalformedJsonReason = "malformed-json";

    /// <summary>Serialises <paramref name="envelope"/> to compact UTF-8 JSON.</summary>
    /// <exception cref="SyncContractException">The envelope does not satisfy the contract.</exception>
    public static string Serialize(SourceSyncEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        Validate(envelope);

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            WriteEnvelope(writer, envelope);
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>Parses a sync document.</summary>
    /// <exception cref="SyncContractException">The document is not valid, or not readable by this build.</exception>
    public static SourceSyncEnvelope Deserialize(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new SyncContractException(MalformedJsonReason, "The sync document is empty.");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException ex)
        {
            throw new SyncContractException(MalformedJsonReason, "The sync document is not valid JSON: " + ex.Message);
        }

        using (document)
        {
            // Kotlin rejects the whole document up front; do the same here so an unknown field carrying a lone
            // surrogate cannot make the two ends disagree ("ignored" on one end, "malformed" on the other).
            if (WireText.ContainsLoneSurrogate(document.RootElement))
            {
                throw new SyncContractException(
                    MalformedJsonReason,
                    "The sync document contains text that is not well-formed Unicode.");
            }

            try
            {
                return ReadEnvelope(document.RootElement);
            }
            catch (InvalidOperationException ex)
            {
                // JsonDocument.Parse accepts an escaped lone surrogate ("\ud800"), but reading the string back
                // throws InvalidOperationException from GetString(). That is a wire text problem, not a bug in the
                // caller, so it has to surface as the same stable reason the Kotlin end reports.
                throw new SyncContractException(
                    MalformedJsonReason,
                    "The sync document contains text that is not well-formed Unicode: " + ex.Message);
            }
        }
    }

    /// <summary>Parses a sync document, returning false and the reason instead of throwing.</summary>
    public static bool TryDeserialize(string? json, out SourceSyncEnvelope? envelope, out string? error)
    {
        envelope = null;
        error = null;
        try
        {
            envelope = Deserialize(json!);
            return true;
        }
        catch (SyncContractException ex)
        {
            error = ex.Reason + ": " + ex.Message;
            return false;
        }
    }

    // ---- writing -----------------------------------------------------------------------------

    private static void WriteEnvelope(Utf8JsonWriter writer, SourceSyncEnvelope envelope)
    {
        writer.WriteStartObject();
        writer.WriteNumber("schemaVersion", envelope.SchemaVersion);
        writer.WriteNumber("minimumReaderVersion", envelope.MinimumReaderVersion);
        writer.WriteString("deviceId", envelope.DeviceId);
        writer.WriteString("snapshotId", envelope.SnapshotId);
        writer.WriteNumber("createdAtEpochMs", envelope.CreatedAtEpochMs);

        writer.WritePropertyName("capabilities");
        writer.WriteStartObject();
        writer.WriteBoolean("canSendSecrets", envelope.Capabilities.CanSendSecrets);
        writer.WritePropertyName("sourceTypes");
        writer.WriteStartArray();
        foreach (string sourceType in envelope.Capabilities.SourceTypes)
        {
            writer.WriteStringValue(sourceType);
        }

        writer.WriteEndArray();
        writer.WriteEndObject();

        writer.WritePropertyName("sources");
        writer.WriteStartArray();
        foreach (SourceRecord source in envelope.Sources)
        {
            WriteRecord(writer, source);
        }

        writer.WriteEndArray();

        writer.WritePropertyName("deletions");
        writer.WriteStartArray();
        foreach (Tombstone deletion in envelope.Deletions)
        {
            writer.WriteStartObject();
            writer.WriteString("syncSourceId", deletion.SyncSourceId);
            writer.WriteNumber("deletedAtEpochMs", deletion.DeletedAtEpochMs);
            writer.WriteNumber("recordRevision", deletion.RecordRevision);
            writer.WriteEndObject();
        }

        writer.WriteEndArray();
        writer.WriteEndObject();
    }

    private static void WriteRecord(Utf8JsonWriter writer, SourceRecord record)
    {
        writer.WriteStartObject();
        writer.WriteString("syncSourceId", record.SyncSourceId);
        // Raw, not Wire: a value this build does not understand must be handed on unchanged (D3).
        writer.WriteString("sourceType", record.SourceType.Raw);
        writer.WriteString("displayName", record.DisplayName);
        if (record.AccountScope is { } accountScope)
        {
            writer.WriteString("accountScope", accountScope);
        }

        writer.WritePropertyName("endpoints");
        writer.WriteStartArray();
        foreach (SourceEndpoint endpoint in record.Endpoints)
        {
            writer.WriteStartObject();
            writer.WriteString("url", endpoint.Url);
            writer.WriteBoolean("isPrimary", endpoint.IsPrimary);
            writer.WriteBoolean("enabled", endpoint.Enabled);
            writer.WriteNumber("sortOrder", endpoint.SortOrder);
            writer.WriteEndObject();
        }

        writer.WriteEndArray();

        writer.WriteNumber("recordRevision", record.RecordRevision);
        writer.WriteNumber("fieldVersion", record.FieldVersion);
        if (record.Secrets is { } secrets)
        {
            writer.WritePropertyName("secrets");
            secrets.WriteTo(writer);
        }

        writer.WriteEndObject();
    }

    // ---- reading -----------------------------------------------------------------------------

    private static SourceSyncEnvelope ReadEnvelope(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new SyncContractException(MalformedJsonReason, "The sync document must be a JSON object.");
        }

        int schemaVersion = ReadRequiredInt32(root, "schemaVersion");
        if (schemaVersion != SourceSyncEnvelope.CurrentSchemaVersion)
        {
            // Refuse, do not guess: a shape we do not know could drop fields that carry meaning.
            throw new SyncContractException(
                "unsupported-schema-version:" + schemaVersion.ToString(CultureInfo.InvariantCulture),
                "The sync document uses schema version " + schemaVersion.ToString(CultureInfo.InvariantCulture)
                    + "; this build reads version " + SourceSyncEnvelope.CurrentSchemaVersion.ToString(CultureInfo.InvariantCulture) + ".");
        }

        int minimumReaderVersion = ReadRequiredInt32(root, "minimumReaderVersion");
        if (minimumReaderVersion < 1)
        {
            throw InvalidField("minimumReaderVersion", "must be at least 1.");
        }

        if (minimumReaderVersion > SourceSyncEnvelope.CurrentSchemaVersion)
        {
            throw new SyncContractException(
                "unsupported-minimum-reader-version:" + minimumReaderVersion.ToString(CultureInfo.InvariantCulture),
                "The sender requires a reader of at least version " + minimumReaderVersion.ToString(CultureInfo.InvariantCulture)
                    + "; this build implements version " + SourceSyncEnvelope.CurrentSchemaVersion.ToString(CultureInfo.InvariantCulture) + ".");
        }

        return new SourceSyncEnvelope
        {
            SchemaVersion = schemaVersion,
            MinimumReaderVersion = minimumReaderVersion,
            DeviceId = ReadRequiredString(root, "deviceId"),
            SnapshotId = ReadRequiredString(root, "snapshotId"),
            CreatedAtEpochMs = ReadRequiredInt64(root, "createdAtEpochMs"),
            Capabilities = TryGetObject(root, "capabilities", out JsonElement capabilities)
                ? ReadCapabilities(capabilities)
                : new SyncCapabilities(),
            Sources = TryGetObject(root, "sources", out JsonElement sources) ? ReadSources(sources) : [],
            Deletions = TryGetObject(root, "deletions", out JsonElement deletions) ? ReadDeletions(deletions) : [],
        };
    }

    private static SyncCapabilities ReadCapabilities(JsonElement element)
    {
        return new SyncCapabilities
        {
            CanSendSecrets = ReadOptionalBoolean(element, "canSendSecrets") ?? false,
            SourceTypes = TryGetObject(element, "sourceTypes", out JsonElement sourceTypes)
                ? ReadStringArray(sourceTypes, "capabilities.sourceTypes")
                : [],
        };
    }

    private static IReadOnlyList<SourceRecord> ReadSources(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Array)
        {
            throw InvalidField("sources", "must be an array.");
        }

        var records = new List<SourceRecord>();
        foreach (JsonElement item in element.EnumerateArray())
        {
            records.Add(ReadSource(item));
        }

        return records;
    }

    private static SourceRecord ReadSource(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object)
        {
            throw InvalidField("sources", "must contain objects.");
        }

        JsonElement? secrets = null;
        if (item.TryGetProperty("secrets", out JsonElement secretsElement) && secretsElement.ValueKind != JsonValueKind.Null)
        {
            // Copied verbatim: the contract says this layer does not interpret secret material.
            secrets = secretsElement.Clone();
        }

        return new SourceRecord
        {
            SyncSourceId = ReadRequiredString(item, "syncSourceId"),
            SourceType = SyncSourceTypeValue.Parse(ReadRequiredString(item, "sourceType")),
            DisplayName = ReadOptionalString(item, "displayName") ?? "",
            AccountScope = ReadOptionalString(item, "accountScope"),
            Endpoints = TryGetObject(item, "endpoints", out JsonElement endpoints) ? ReadEndpoints(endpoints) : [],
            RecordRevision = ReadRequiredInt64(item, "recordRevision"),
            // FieldVersion is optional for v1 senders that predate per-record field sets.
            FieldVersion = ReadOptionalInt32(item, "fieldVersion") ?? 0,
            Secrets = secrets,
        };
    }

    private static IReadOnlyList<SourceEndpoint> ReadEndpoints(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Array)
        {
            throw InvalidField("endpoints", "must be an array.");
        }

        var endpoints = new List<SourceEndpoint>();
        int index = 0;
        foreach (JsonElement item in element.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
            {
                throw InvalidField("endpoints[" + index.ToString(CultureInfo.InvariantCulture) + "]", "must be an object.");
            }

            endpoints.Add(new SourceEndpoint
            {
                Url = ReadRequiredString(item, "url"),
                IsPrimary = ReadOptionalBoolean(item, "isPrimary") ?? false,
                Enabled = ReadOptionalBoolean(item, "enabled") ?? true,
                SortOrder = ReadOptionalInt32(item, "sortOrder") ?? 0,
            });
            index++;
        }

        return endpoints;
    }

    private static IReadOnlyList<Tombstone> ReadDeletions(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Array)
        {
            throw InvalidField("deletions", "must be an array.");
        }

        var tombstones = new List<Tombstone>();
        foreach (JsonElement item in element.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
            {
                throw InvalidField("deletions", "must contain objects.");
            }

            tombstones.Add(new Tombstone
            {
                SyncSourceId = ReadRequiredString(item, "syncSourceId"),
                DeletedAtEpochMs = ReadOptionalInt64(item, "deletedAtEpochMs") ?? 0,
                RecordRevision = ReadOptionalInt64(item, "recordRevision") ?? 0,
            });
        }

        return tombstones;
    }

    // ---- validation --------------------------------------------------------------------------

    private static void Validate(SourceSyncEnvelope envelope)
    {
        if (envelope.SchemaVersion != SourceSyncEnvelope.CurrentSchemaVersion)
        {
            throw new SyncContractException(
                "unsupported-schema-version:" + envelope.SchemaVersion.ToString(CultureInfo.InvariantCulture),
                "Only schema version " + SourceSyncEnvelope.CurrentSchemaVersion.ToString(CultureInfo.InvariantCulture) + " can be written.");
        }

        if (envelope.MinimumReaderVersion < 1)
        {
            throw InvalidField("minimumReaderVersion", "must be at least 1.");
        }

        if (envelope.MinimumReaderVersion > SourceSyncEnvelope.CurrentSchemaVersion)
        {
            throw new SyncContractException(
                "unsupported-minimum-reader-version:" + envelope.MinimumReaderVersion.ToString(CultureInfo.InvariantCulture),
                "This build cannot announce a required reader version above its own.");
        }

        RequireText(envelope.DeviceId, "deviceId");
        RequireText(envelope.SnapshotId, "snapshotId");
        ArgumentNullException.ThrowIfNull(envelope.Capabilities);
        ArgumentNullException.ThrowIfNull(envelope.Sources);
        ArgumentNullException.ThrowIfNull(envelope.Deletions);
        ArgumentNullException.ThrowIfNull(envelope.Capabilities.SourceTypes);

        foreach (SourceRecord record in envelope.Sources)
        {
            if (record is null)
            {
                throw new SyncContractException("invalid-field:sources", "must not contain null entries.");
            }

            RequireText(record.SyncSourceId, "syncSourceId");
            RequireText(record.SourceType.Raw, "sourceType");
            RequireWellFormed(record.DisplayName, "displayName");
            RequireWellFormed(record.AccountScope, "accountScope");
            ArgumentNullException.ThrowIfNull(record.Endpoints);
            foreach (SourceEndpoint endpoint in record.Endpoints)
            {
                if (endpoint is null)
                {
                    throw new SyncContractException("invalid-field:endpoints", "must not contain null entries.");
                }

                RequireText(endpoint.Url, "url");
            }
        }

        foreach (Tombstone deletion in envelope.Deletions)
        {
            if (deletion is null)
            {
                throw new SyncContractException("invalid-field:deletions", "must not contain null entries.");
            }

            RequireText(deletion.SyncSourceId, "syncSourceId");
        }
    }

    private static string RequireText(string? value, string field)
    {
        // WireText pins the same whitespace set as the Kotlin end; IsNullOrWhiteSpace alone would let the two
        // ends disagree on U+001C-U+001F (blank on the JVM, ordinary text here).
        if (value is null || WireText.IsBlank(value))
        {
            throw new SyncContractException("missing-field:" + field, "The required field '" + field + "' is missing or empty.");
        }

        // A lone surrogate cannot be written as UTF-8: Utf8JsonWriter silently substitutes U+FFFD, so the
        // receiver would get text the sender never had. Refuse instead of corrupting.
        if (WireText.HasLoneSurrogate(value))
        {
            throw InvalidField(field, "must be well-formed Unicode text.");
        }

        return value;
    }

    /// <summary>Rejects optional text this layer could only write back as mangled UTF-8.</summary>
    private static void RequireWellFormed(string? value, string field)
    {
        if (value is null)
        {
            return;
        }

        if (WireText.HasLoneSurrogate(value))
        {
            throw InvalidField(field, "must be well-formed Unicode text.");
        }
    }

    // ---- json helpers ------------------------------------------------------------------------

    private static SyncContractException InvalidField(string field, string detail)
        => new("invalid-field:" + field, "The field '" + field + "' " + detail);

    private static bool TryGetObject(JsonElement owner, string name, out JsonElement value)
        => owner.TryGetProperty(name, out value) && value.ValueKind != JsonValueKind.Null;

    private static JsonElement ReadRequiredProperty(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            throw new SyncContractException("missing-field:" + name, "The required field '" + name + "' is missing.");
        }

        return value;
    }

    private static string ReadRequiredString(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.String)
        {
            throw InvalidField(name, "must be a string.");
        }

        string? text = value.GetString();
        if (text is null || WireText.IsBlank(text))
        {
            throw new SyncContractException("missing-field:" + name, "The required field '" + name + "' is missing or empty.");
        }

        return text;
    }

    private static string? ReadOptionalString(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        if (value.ValueKind != JsonValueKind.String)
        {
            throw InvalidField(name, "must be a string.");
        }

        return value.GetString();
    }

    private static int ReadRequiredInt32(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt32(out int number))
        {
            throw InvalidField(name, "must be a 32-bit integer.");
        }

        return number;
    }

    private static int? ReadOptionalInt32(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt32(out int number))
        {
            throw InvalidField(name, "must be a 32-bit integer.");
        }

        return number;
    }

    private static long ReadRequiredInt64(JsonElement owner, string name)
    {
        JsonElement value = ReadRequiredProperty(owner, name);
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out long number))
        {
            throw InvalidField(name, "must be a 64-bit integer.");
        }

        return number;
    }

    private static long? ReadOptionalInt64(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out long number))
        {
            throw InvalidField(name, "must be a 64-bit integer.");
        }

        return number;
    }

    private static bool? ReadOptionalBoolean(JsonElement owner, string name)
    {
        if (!owner.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => throw InvalidField(name, "must be a boolean."),
        };
    }

    private static IReadOnlyList<string> ReadStringArray(JsonElement element, string field)
    {
        if (element.ValueKind != JsonValueKind.Array)
        {
            throw InvalidField(field, "must be an array.");
        }

        var values = new List<string>();
        foreach (JsonElement item in element.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String)
            {
                throw InvalidField(field, "must contain strings.");
            }

            values.Add(item.GetString() ?? "");
        }

        return values;
    }
}

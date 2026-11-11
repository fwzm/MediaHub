using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// Document codec for the paired-device list: <c>{ "version": 1, "devices": [ … ] }</c>.
/// </summary>
/// <remarks>
/// <para>
/// Same shape rules as the source contract: camelCase field names, unknown fields ignored, a version newer
/// than this build refused rather than guessed at, and a duplicate device id collapsed to the last writer so
/// a hand-edited file still loads.
/// </para>
/// <para>
/// The document holds display metadata only. Nothing here is a credential, a key or a trust decision, so a
/// truncated or corrupted file costs names and resume points — never access.
/// </para>
/// </remarks>
public static class SyncDeviceRegistryCodec
{
    /// <summary>Document version this build writes and the only one it reads.</summary>
    public const int CurrentSchemaVersion = 1;

    /// <summary>Serialises a device list.</summary>
    public static string Serialize(IReadOnlyList<SyncDeviceRecord> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = true }))
        {
            writer.WriteStartObject();
            writer.WriteNumber("version", CurrentSchemaVersion);
            writer.WriteStartArray("devices");
            foreach (SyncDeviceRecord device in devices)
            {
                ArgumentNullException.ThrowIfNull(device);
                writer.WriteStartObject();
                writer.WriteString("deviceId", device.DeviceId);
                writer.WriteString("displayName", device.DisplayName);
                writer.WriteNumber("lastSeenEpochMs", device.LastSeenEpochMs);
                writer.WriteString("lastAppliedSnapshotId", device.LastAppliedSnapshotId);
                writer.WriteBoolean("canSendSecrets", device.CanSendSecrets);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>Serialises a registry's current view.</summary>
    public static string Serialize(SyncDeviceRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(registry);
        return Serialize(registry.GetAll());
    }

    /// <summary>Reads a device document.</summary>
    /// <exception cref="SyncContractException">The document is malformed or written by a newer schema.</exception>
    public static IReadOnlyList<SyncDeviceRecord> Deserialize(string json)
    {
        ArgumentNullException.ThrowIfNull(json);
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new SyncContractException("malformed-json", "The device document is empty.");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException ex)
        {
            throw new SyncContractException("malformed-json", "The device document is not valid JSON: " + ex.Message);
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                throw new SyncContractException("malformed-json", "The device document root must be an object.");
            }

            int version = ReadVersion(root);

            if (!root.TryGetProperty("devices", out JsonElement devicesElement))
            {
                throw new SyncContractException("missing-field:devices", "The device document has no devices array.");
            }

            if (devicesElement.ValueKind != JsonValueKind.Array)
            {
                throw new SyncContractException("invalid-field:devices", "The device document's devices field must be an array.");
            }

            var result = new List<SyncDeviceRecord>();
            foreach (JsonElement element in devicesElement.EnumerateArray())
            {
                SyncDeviceRecord device = ReadDevice(element);
                int existing = result.FindIndex(
                    item => string.Equals(item.DeviceId, device.DeviceId, StringComparison.OrdinalIgnoreCase));
                if (existing >= 0)
                {
                    result[existing] = device;
                }
                else
                {
                    result.Add(device);
                }
            }

            return result;
        }
    }

    /// <summary>Reads a document without throwing; the error carries the same reason code as the exception.</summary>
    public static bool TryDeserialize(
        string? json,
        out IReadOnlyList<SyncDeviceRecord> devices,
        out string? error)
    {
        devices = [];
        error = null;
        try
        {
            devices = Deserialize(json!);
            return true;
        }
        catch (SyncContractException ex)
        {
            error = ex.Reason;
            devices = [];
            return false;
        }
    }

    private static int ReadVersion(JsonElement root)
    {
        if (!root.TryGetProperty("version", out JsonElement versionElement))
        {
            throw new SyncContractException("missing-field:version", "The device document has no version.");
        }

        if (versionElement.ValueKind != JsonValueKind.Number || !versionElement.TryGetInt32(out int version))
        {
            throw new SyncContractException("invalid-field:version", "The device document's version must be an integer.");
        }

        if (version != CurrentSchemaVersion)
        {
            throw new SyncContractException(
                "unsupported-schema-version:" + version.ToString(CultureInfo.InvariantCulture),
                "The device document uses schema version " + version.ToString(CultureInfo.InvariantCulture)
                + ", which this build does not read.");
        }

        return version;
    }

    private static SyncDeviceRecord ReadDevice(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            throw new SyncContractException("invalid-field:devices", "Every device entry must be an object.");
        }

        string? deviceId = null;
        if (element.TryGetProperty("deviceId", out JsonElement deviceIdElement))
        {
            if (deviceIdElement.ValueKind != JsonValueKind.String)
            {
                throw new SyncContractException("invalid-field:deviceId", "The device id must be a string.");
            }

            deviceId = deviceIdElement.GetString();
        }

        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new SyncContractException("missing-field:deviceId", "A device entry has no device id.");
        }

        string displayName = "";
        if (element.TryGetProperty("displayName", out JsonElement displayNameElement)
            && displayNameElement.ValueKind != JsonValueKind.Null)
        {
            if (displayNameElement.ValueKind != JsonValueKind.String)
            {
                throw new SyncContractException("invalid-field:displayName", "The display name must be a string.");
            }

            displayName = displayNameElement.GetString() ?? "";
        }

        long lastSeenEpochMs = 0;
        if (element.TryGetProperty("lastSeenEpochMs", out JsonElement lastSeenElement)
            && lastSeenElement.ValueKind != JsonValueKind.Null)
        {
            if (lastSeenElement.ValueKind != JsonValueKind.Number || !lastSeenElement.TryGetInt64(out lastSeenEpochMs))
            {
                throw new SyncContractException("invalid-field:lastSeenEpochMs", "The last-seen time must be an integer.");
            }
        }

        string? lastAppliedSnapshotId = null;
        if (element.TryGetProperty("lastAppliedSnapshotId", out JsonElement snapshotElement)
            && snapshotElement.ValueKind != JsonValueKind.Null)
        {
            if (snapshotElement.ValueKind != JsonValueKind.String)
            {
                throw new SyncContractException("invalid-field:lastAppliedSnapshotId", "The snapshot id must be a string.");
            }

            lastAppliedSnapshotId = snapshotElement.GetString();
        }

        bool canSendSecrets = false;
        if (element.TryGetProperty("canSendSecrets", out JsonElement canSendElement)
            && canSendElement.ValueKind != JsonValueKind.Null)
        {
            if (canSendElement.ValueKind != JsonValueKind.True && canSendElement.ValueKind != JsonValueKind.False)
            {
                throw new SyncContractException("invalid-field:canSendSecrets", "The secrets flag must be a boolean.");
            }

            canSendSecrets = canSendElement.GetBoolean();
        }

        return new SyncDeviceRecord
        {
            DeviceId = deviceId!,
            DisplayName = displayName,
            LastSeenEpochMs = lastSeenEpochMs,
            LastAppliedSnapshotId = lastAppliedSnapshotId,
            CanSendSecrets = canSendSecrets,
        };
    }
}

using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>The paired devices of this installation, kept in one small JSON document.</summary>
/// <remarks>
/// The document is written atomically (temporary file, then a replace that keeps the previous copy as .bak) so a
/// crash mid-write leaves either the old list or the new one. A document that cannot be understood is moved aside
/// under a .corrupt name and the store degrades to an empty, still writable list: losing the list is recoverable,
/// silently overwriting the evidence is not. Reading never throws.
/// </remarks>
public sealed class FilePairedDeviceStore : IPairedDeviceStore
{
    /// <summary>Document name inside <see cref="DirectoryPath"/>.</summary>
    public const string FileName = "paired-devices.json";

    /// <summary>Prefix of the quarantined copies. They are moved, never deleted.</summary>
    public const string CorruptPrefix = "paired-devices.corrupt-";

    /// <summary>Document version this build writes and accepts.</summary>
    public const int CurrentDocumentVersion = 1;

    /// <summary>How many devices are remembered before a new pairing is refused.</summary>
    public const int DefaultCapacity = 32;

    /// <summary>A paired-device document larger than this is not one; it is quarantined unread.</summary>
    public const long MaxDocumentChars = 1L * 1024 * 1024;

    private readonly object _gate = new();
    private readonly List<PairedDevice> _devices = [];
    private bool _loaded;
    private bool _writable = true;

    /// <summary>Creates a store over a directory; the default is the app's own data directory.</summary>
    public FilePairedDeviceStore(string? directory = null)
    {
        DirectoryPath = string.IsNullOrWhiteSpace(directory)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub")
            : directory!;
        FilePath = Path.Combine(DirectoryPath, FileName);
        BackupPath = FilePath + ".bak";
    }

    /// <summary>Folder holding the document.</summary>
    public string DirectoryPath { get; }

    /// <summary>Full path of the document.</summary>
    public string FilePath { get; }

    /// <summary>Previous document, kept by the atomic replace.</summary>
    public string BackupPath { get; }

    /// <inheritdoc />
    public string? LoadError { get; private set; }

    /// <summary>Where a damaged document was moved to, when that happened in this session.</summary>
    public string? LastQuarantinedPath { get; private set; }

    /// <summary>How many writes actually reached the file.</summary>
    public int WriteCount { get; private set; }

    /// <summary>Backstop for the registry's own limit; a direct caller cannot grow the file past this.</summary>
    public int Capacity { get; init; } = DefaultCapacity;

    /// <inheritdoc />
    public IReadOnlyList<PairedDevice> GetAll()
    {
        lock (_gate)
        {
            EnsureLoadedLocked();
            return _devices
                .OrderBy(device => device.DeviceId, StringComparer.OrdinalIgnoreCase)
                .ThenBy(device => device.DeviceId, StringComparer.Ordinal)
                .ToArray();
        }
    }

    /// <inheritdoc />
    public void Save(PairedDevice device)
    {
        ArgumentNullException.ThrowIfNull(device);
        if (string.IsNullOrWhiteSpace(device.DeviceId))
        {
            throw new ArgumentException("设备标识不能为空。", nameof(device));
        }

        lock (_gate)
        {
            EnsureLoadedLocked();
            EnsureWritableLocked();

            int index = IndexOfLocked(device.DeviceId);
            if (index >= 0)
            {
                if (Equals(_devices[index], device))
                {
                    return;
                }

                PairedDevice previous = _devices[index];
                _devices[index] = device;
                try
                {
                    PersistLocked();
                    WriteCount++;
                }
                catch
                {
                    _devices[index] = previous;
                    throw;
                }

                return;
            }

            if (Capacity > 0 && _devices.Count >= Capacity)
            {
                throw new IOException(PairedDeviceStoreReasons.LimitReached);
            }

            _devices.Add(device);
            try
            {
                PersistLocked();
                WriteCount++;
            }
            catch
            {
                _devices.RemoveAt(_devices.Count - 1);
                throw;
            }
        }
    }

    /// <inheritdoc />
    public bool Forget(string deviceId)
    {
        string key = (deviceId ?? string.Empty).Trim();
        if (key.Length == 0)
        {
            return false;
        }

        lock (_gate)
        {
            EnsureLoadedLocked();
            EnsureWritableLocked();

            int index = IndexOfLocked(key);
            if (index < 0)
            {
                return false;
            }

            PairedDevice removed = _devices[index];
            _devices.RemoveAt(index);
            try
            {
                PersistLocked();
                WriteCount++;
            }
            catch
            {
                _devices.Insert(index, removed);
                throw;
            }

            return true;
        }
    }

    private void EnsureLoadedLocked()
    {
        if (_loaded)
        {
            return;
        }

        _loaded = true;
        LoadLocked();
    }

    private void EnsureWritableLocked()
    {
        if (!_writable)
        {
            throw new IOException(LoadError ?? PairedDeviceStoreReasons.StoreUnreadable);
        }
    }

    private void LoadLocked()
    {
        string text;
        try
        {
            if (!File.Exists(FilePath))
            {
                return;
            }

            text = File.ReadAllText(FilePath);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            LoadError = PairedDeviceStoreReasons.StoreUnreadable;
            _writable = false;
            _devices.Clear();
            return;
        }

        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }

        if (text.Length > MaxDocumentChars)
        {
            Quarantine(PairedDeviceStoreReasons.MalformedJson);
            return;
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(text);
        }
        catch (JsonException)
        {
            Quarantine(PairedDeviceStoreReasons.MalformedJson);
            return;
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            // TryGetInt32 throws rather than returning false when ValueKind is not Number, so the kind has to be
            // checked first: a document with "version": "1" must be quarantined as malformed, not crash GetAll().
            if (root.ValueKind != JsonValueKind.Object
                || !root.TryGetProperty("version", out JsonElement versionElement)
                || versionElement.ValueKind != JsonValueKind.Number
                || !versionElement.TryGetInt32(out int version))
            {
                Quarantine(PairedDeviceStoreReasons.MalformedJson);
                return;
            }

            if (version != CurrentDocumentVersion)
            {
                Quarantine(PairedDeviceStoreReasons.UnsupportedVersion + ":" + version.ToString(CultureInfo.InvariantCulture));
                return;
            }

            if (!root.TryGetProperty("devices", out JsonElement devicesElement) || devicesElement.ValueKind != JsonValueKind.Array)
            {
                Quarantine(PairedDeviceStoreReasons.MalformedJson);
                return;
            }

            foreach (JsonElement element in devicesElement.EnumerateArray())
            {
                PairedDevice? device = ReadDevice(element);
                if (device is null || IndexOfLocked(device.DeviceId) >= 0)
                {
                    // A single unreadable row is skipped, not fatal: the other devices are still worth keeping.
                    continue;
                }

                _devices.Add(device);
            }
        }
    }

    private static PairedDevice? ReadDevice(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        if (!element.TryGetProperty("deviceId", out JsonElement idElement) || idElement.ValueKind != JsonValueKind.String)
        {
            return null;
        }

        string deviceId = (idElement.GetString() ?? string.Empty).Trim();
        if (deviceId.Length == 0)
        {
            return null;
        }

        if (!element.TryGetProperty("fingerprint", out JsonElement fingerprintElement)
            || fingerprintElement.ValueKind != JsonValueKind.String
            || !PairingFingerprint.TryParse(fingerprintElement.GetString(), out PairingFingerprint fingerprint))
        {
            return null;
        }

        string displayName = string.Empty;
        if (element.TryGetProperty("displayName", out JsonElement nameElement) && nameElement.ValueKind == JsonValueKind.String)
        {
            displayName = (nameElement.GetString() ?? string.Empty).Trim();
        }

        return new PairedDevice
        {
            DeviceId = deviceId,
            DisplayName = displayName,
            Fingerprint = fingerprint.Hex,
            PairedAtEpochMs = ReadInt64(element, "pairedAtEpochMs"),
            LastSeenEpochMs = ReadInt64(element, "lastSeenEpochMs"),
            SourceCount = ReadInt32(element, "sourceCount"),
        };
    }

    private static long ReadInt64(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.Number
            && value.TryGetInt64(out long parsed)
            ? parsed
            : 0;

    private static int ReadInt32(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value)
            && value.ValueKind == JsonValueKind.Number
            && value.TryGetInt32(out int parsed)
            && parsed > 0
            ? parsed
            : 0;

    private void Quarantine(string reason)
    {
        LoadError = reason;
        _writable = false;
        _devices.Clear();

        try
        {
            string stamp = DateTime.UtcNow.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
            string candidate = Path.Combine(DirectoryPath, CorruptPrefix + stamp + ".json");
            for (int attempt = 1; File.Exists(candidate) && attempt < 100; attempt++)
            {
                candidate = Path.Combine(
                    DirectoryPath,
                    CorruptPrefix + stamp + "-" + attempt.ToString(CultureInfo.InvariantCulture) + ".json");
            }

            File.Move(FilePath, candidate);
            LastQuarantinedPath = candidate;
            _writable = true;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // The document stays where it is; this session keeps refusing to write over it.
        }
    }

    private void PersistLocked()
    {
        Directory.CreateDirectory(DirectoryPath);

        byte[] bytes;
        using (var stream = new MemoryStream())
        {
            using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
            {
                writer.WriteStartObject();
                writer.WriteNumber("version", CurrentDocumentVersion);
                writer.WriteStartArray("devices");
                foreach (PairedDevice device in _devices
                    .OrderBy(item => item.DeviceId, StringComparer.OrdinalIgnoreCase)
                    .ThenBy(item => item.DeviceId, StringComparer.Ordinal))
                {
                    writer.WriteStartObject();
                    writer.WriteString("deviceId", device.DeviceId);
                    writer.WriteString("displayName", device.DisplayName);
                    writer.WriteString("fingerprint", device.Fingerprint);
                    writer.WriteNumber("pairedAtEpochMs", device.PairedAtEpochMs);
                    writer.WriteNumber("lastSeenEpochMs", device.LastSeenEpochMs);
                    writer.WriteNumber("sourceCount", device.SourceCount);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }

            bytes = stream.ToArray();
        }

        string tempPath = FilePath + ".tmp";
        try
        {
            using (var file = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                file.Write(bytes, 0, bytes.Length);
                file.Flush(flushToDisk: true);
            }

            if (File.Exists(FilePath))
            {
                File.Replace(tempPath, FilePath, BackupPath, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(tempPath, FilePath, overwrite: true);
            }
        }
        finally
        {
            TryDelete(tempPath);
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
        }
    }

    private int IndexOfLocked(string deviceId)
    {
        for (int index = 0; index < _devices.Count; index++)
        {
            if (string.Equals(_devices[index].DeviceId, deviceId.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                return index;
            }
        }

        return -1;
    }
}

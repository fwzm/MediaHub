using System.Globalization;
using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>
/// File-backed baseline store: <c>%LOCALAPPDATA%\MediaHub\sync-baseline.json</c>.
/// </summary>
/// <remarks>
/// <para>
/// One document holds every peer's baseline, keyed by the peer <c>deviceId</c>. Writes are atomic — a same-directory
/// temporary file is flushed to disk and then swapped in with <see cref="File.Replace(string, string, string, bool)"/>
/// — so a process kill in the middle of a save leaves either the previous document or the new one, never half of
/// each. The previous version is kept as <c>sync-baseline.json.bak</c>: the baseline is cheap to rebuild but not
/// free, and the recovery path for a bad document should not be "resync everything".
/// </para>
/// <para>
/// A document that cannot be parsed, or was written by a newer build, is never deleted and never overwritten in
/// place: it is renamed to <c>sync-baseline.corrupt-yyyyMMddHHmmss.json</c> and this store behaves as if it knew
/// nothing. That degrades to a full snapshot — noisy, but the peer re-sends its records instead of the user losing
/// them. Only a read that fails for I/O reasons (a locked file) blocks writing, because there the bytes are still
/// intact and overwriting them would destroy the only copy.
/// </para>
/// <para>
/// The revision guard lives in <see cref="SourceSyncBaselineMerge"/> and applies here exactly as it does in memory:
/// a round that learned less may not move the stored baseline backwards.
/// </para>
/// </remarks>
public sealed class FileSourceSyncBaselineStore : ISourceSyncBaselineStore
{
    /// <summary>File name inside the root directory.</summary>
    public const string FileName = "sync-baseline.json";

    /// <summary>Prefix of a quarantined document, followed by a timestamp and <c>.json</c>.</summary>
    public const string CorruptPrefix = "sync-baseline.corrupt-";

    /// <summary>Document version this build writes and the only one it reads.</summary>
    public const int CurrentDocumentVersion = 1;

    /// <summary>Upper bound on peers in one document, checked before parsing an entry list.</summary>
    public const int MaxPeers = 64;

    /// <summary>Upper bound on document length; a bigger file is treated as damage, not as data.</summary>
    public const long MaxDocumentChars = 16L * 1024 * 1024;

    private readonly object _gate = new();
    private readonly Dictionary<string, SourceSyncBaseline> _peers = new(StringComparer.OrdinalIgnoreCase);
    private bool _loaded;
    private bool _writable = true;

    /// <summary>Creates the store; the directory is created on first save.</summary>
    /// <param name="directory">Root directory; defaults to <c>%LOCALAPPDATA%\MediaHub</c>.</param>
    public FileSourceSyncBaselineStore(string? directory = null)
    {
        DirectoryPath = string.IsNullOrWhiteSpace(directory)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub")
            : Path.GetFullPath(directory.Trim());
        FilePath = Path.Combine(DirectoryPath, FileName);
        BackupPath = FilePath + ".bak";
    }

    /// <summary>Root directory of the document.</summary>
    public string DirectoryPath { get; }

    /// <summary>Absolute path of the document.</summary>
    public string FilePath { get; }

    /// <summary>Absolute path of the previous document kept by the atomic swap.</summary>
    public string BackupPath { get; }

    /// <summary>
    /// Why the last load did not produce usable data: a codec reason, or
    /// <see cref="SourceSyncBaselineStoreReasons.StoreUnreadable"/>. Null when the document was readable (or absent).
    /// </summary>
    public string? LoadError { get; private set; }

    /// <summary>Path the damaged document was moved to, when quarantine succeeded.</summary>
    public string? LastQuarantinedPath { get; private set; }

    /// <summary>Number of saves or deletions that actually rewrote the document.</summary>
    public int WriteCount { get; private set; }

    /// <inheritdoc />
    public SourceSyncBaseline Load(string peerDeviceId)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            return SourceSyncBaseline.Empty;
        }

        lock (_gate)
        {
            EnsureLoaded();
            return _peers.TryGetValue(peerDeviceId.Trim(), out SourceSyncBaseline? baseline)
                ? baseline
                : SourceSyncBaseline.Empty;
        }
    }

    /// <inheritdoc />
    /// <exception cref="ArgumentException"><paramref name="peerDeviceId"/> is blank.</exception>
    /// <exception cref="System.IO.IOException">The document could not be written; the store state is unchanged.</exception>
    public SourceSyncBaselineSaveResult Save(string peerDeviceId, SourceSyncBaseline baseline)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            throw new ArgumentException("A peer device id is required.", nameof(peerDeviceId));
        }

        ArgumentNullException.ThrowIfNull(baseline);

        lock (_gate)
        {
            EnsureLoaded();
            EnsureWritable();

            string key = peerDeviceId.Trim();
            _peers.TryGetValue(key, out SourceSyncBaseline? existing);
            SourceSyncBaselineMergeResult merge = SourceSyncBaselineMerge.Merge(existing, baseline);
            if (!merge.Changed)
            {
                return merge.RejectedSyncSourceIds.Count == 0
                    ? SourceSyncBaselineSaveResult.NoChange
                    : new SourceSyncBaselineSaveResult
                    {
                        Unchanged = true,
                        RejectedSyncSourceIds = merge.RejectedSyncSourceIds,
                        Reason = merge.Reason,
                    };
            }

            bool hadPrevious = _peers.ContainsKey(key);
            _peers[key] = merge.Merged;
            try
            {
                Persist();
            }
            catch
            {
                if (hadPrevious && existing is not null)
                {
                    _peers[key] = existing;
                }
                else
                {
                    _peers.Remove(key);
                }

                throw;
            }

            WriteCount++;
            return SourceSyncBaselineSaveResult.Written(merge.RejectedSyncSourceIds, merge.Reason);
        }
    }

    /// <summary>
    /// Peer device ids the document knows about, sorted the same way the document is written.
    /// </summary>
    /// <remarks>
    /// Reading this property loads the document. Unlike <see cref="Load"/> it answers "which peers are
    /// there at all", which a store keyed by peer id otherwise cannot: the document is the index.
    /// </remarks>
    public IReadOnlyList<string> PeerDeviceIds
    {
        get
        {
            lock (_gate)
            {
                EnsureLoaded();
                return _peers.Keys
                    .OrderBy(key => key, StringComparer.OrdinalIgnoreCase)
                    .ToArray();
            }
        }
    }

    /// <summary>Forgets everything the document holds about one peer.</summary>
    /// <param name="peerDeviceId">Peer device id; compared trimmed and case-insensitively.</param>
    /// <returns>True when a peer entry was removed and the document rewritten; false when the peer was absent.</returns>
    /// <exception cref="ArgumentException"><paramref name="peerDeviceId"/> is blank.</exception>
    /// <exception cref="IOException">
    /// The document exists but could not be read (so it is not overwritten), or the write itself failed;
    /// the in-memory state is unchanged in both cases.
    /// </exception>
    /// <remarks>
    /// This is a deliberate deletion, not a merge: <see cref="Save"/> can only move a baseline forward, so
    /// a peer that should be forgotten would otherwise stay forever. Forgetting is local bookkeeping — it
    /// says nothing to the peer and touches no source.
    /// </remarks>
    public bool Forget(string peerDeviceId)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            throw new ArgumentException("A peer device id is required.", nameof(peerDeviceId));
        }

        lock (_gate)
        {
            EnsureLoaded();
            EnsureWritable();

            string key = peerDeviceId.Trim();
            if (!_peers.TryGetValue(key, out SourceSyncBaseline? existing))
            {
                return false;
            }

            _peers.Remove(key);
            try
            {
                Persist();
            }
            catch
            {
                _peers[key] = existing;
                throw;
            }

            WriteCount++;
            return true;
        }
    }

    /// <summary>Alias of <see cref="Forget"/> kept for callers written before forgetting was on the interface.</summary>
    public bool ForgetPeer(string peerDeviceId) => Forget(peerDeviceId);

    /// <summary>Forgets one source inside one peer's baseline.</summary>
    /// <param name="peerDeviceId">Peer device id; compared trimmed and case-insensitively.</param>
    /// <param name="syncSourceId">Sync identity to drop; compared trimmed and case-insensitively.</param>
    /// <returns>True when an entry was removed and the document rewritten; false when nothing matched.</returns>
    /// <exception cref="ArgumentException">Either id is blank.</exception>
    /// <exception cref="IOException">
    /// The document exists but could not be read (so it is not overwritten), or the write itself failed;
    /// the in-memory state is unchanged in both cases.
    /// </exception>
    /// <remarks>
    /// Dropping the last entry drops the peer node with it: an empty baseline and no baseline are the
    /// same fact, and <see cref="Load"/> returns <see cref="SourceSyncBaseline.Empty"/> either way.
    /// </remarks>
    public bool ForgetEntry(string peerDeviceId, string syncSourceId)
    {
        if (string.IsNullOrWhiteSpace(peerDeviceId))
        {
            throw new ArgumentException("A peer device id is required.", nameof(peerDeviceId));
        }

        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            throw new ArgumentException("A sync source id is required.", nameof(syncSourceId));
        }

        lock (_gate)
        {
            EnsureLoaded();
            EnsureWritable();

            string key = peerDeviceId.Trim();
            if (!_peers.TryGetValue(key, out SourceSyncBaseline? existing))
            {
                return false;
            }

            string wanted = syncSourceId.Trim();
            List<SourceSyncBaselineEntry> remaining = [];
            bool removed = false;
            foreach (SourceSyncBaselineEntry entry in existing.Entries)
            {
                if (entry is not null && string.Equals(entry.SyncSourceId.Trim(), wanted, StringComparison.OrdinalIgnoreCase))
                {
                    removed = true;
                    continue;
                }

                if (entry is not null)
                {
                    remaining.Add(entry);
                }
            }

            if (!removed)
            {
                return false;
            }

            if (remaining.Count == 0)
            {
                _peers.Remove(key);
            }
            else
            {
                _peers[key] = existing with { Entries = remaining };
            }

            try
            {
                Persist();
            }
            catch
            {
                _peers[key] = existing;
                throw;
            }

            WriteCount++;
            return true;
        }
    }

    /// <summary>Refuses every write while the stored document is unreadable but still intact.</summary>
    /// <exception cref="IOException">The last load failed for an I/O reason.</exception>
    private void EnsureWritable()
    {
        if (!_writable)
        {
            throw new IOException(
                "The stored sync baseline could not be read (" + (LoadError ?? "unknown")
                + "); refusing to overwrite it.");
        }
    }

    private void EnsureLoaded()
    {
        if (_loaded)
        {
            return;
        }

        _loaded = true;
        if (!File.Exists(FilePath))
        {
            return;
        }

        string text;
        try
        {
            text = File.ReadAllText(FilePath, Encoding.UTF8);
        }
        catch (IOException)
        {
            _writable = false;
            LoadError = SourceSyncBaselineStoreReasons.StoreUnreadable;
            return;
        }
        catch (UnauthorizedAccessException)
        {
            _writable = false;
            LoadError = SourceSyncBaselineStoreReasons.StoreUnreadable;
            return;
        }

        try
        {
            ReadDocument(text);
        }
        catch (SyncContractException error)
        {
            Quarantine(error.Reason);
        }
    }

    private void ReadDocument(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            throw new SyncContractException(
                SourceSyncBaselineCodecReasons.MalformedJson,
                "The sync baseline document is empty.");
        }

        if (text.Length > MaxDocumentChars)
        {
            throw new SyncContractException(
                SourceSyncBaselineCodecReasons.TooLarge,
                "The sync baseline document is too large to read.");
        }

        if (WireText.ExceedsDepth(text, SourceSyncBaselineCodec.MaxDepth))
        {
            throw new SyncContractException(
                SourceSyncBaselineCodecReasons.TooDeep,
                "The sync baseline document is nested too deeply to read.");
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(text, new JsonDocumentOptions { MaxDepth = SourceSyncBaselineCodec.MaxDepth });
        }
        catch (JsonException exception)
        {
            throw new SyncContractException(
                SourceSyncBaselineCodecReasons.MalformedJson,
                "The sync baseline document is not valid JSON: " + exception.Message);
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                throw new SyncContractException(
                    SourceSyncBaselineCodecReasons.MalformedJson,
                    "The sync baseline document root must be an object.");
            }

            ReadVersion(root);
            if (!root.TryGetProperty("peers", out JsonElement peersElement)
                || peersElement.ValueKind == JsonValueKind.Null)
            {
                throw new SyncContractException("missing-field:peers", "The sync baseline document has no peers array.");
            }

            if (peersElement.ValueKind != JsonValueKind.Array)
            {
                throw new SyncContractException("invalid-field:peers", "The peers field must be an array.");
            }

            if (peersElement.GetArrayLength() > MaxPeers)
            {
                throw new SyncContractException(
                    SourceSyncBaselineCodecReasons.TooLarge,
                    "The sync baseline document names too many peers.");
            }

            foreach (JsonElement element in peersElement.EnumerateArray())
            {
                ReadPeer(element);
            }
        }
    }

    private static void ReadVersion(JsonElement root)
    {
        if (!root.TryGetProperty("version", out JsonElement versionElement))
        {
            throw new SyncContractException("missing-field:version", "The sync baseline document has no version.");
        }

        if (versionElement.ValueKind != JsonValueKind.Number || !versionElement.TryGetInt32(out int version))
        {
            throw new SyncContractException("invalid-field:version", "The document version must be an integer.");
        }

        if (version != CurrentDocumentVersion)
        {
            throw new SyncContractException(
                "unsupported-schema-version:" + version.ToString(CultureInfo.InvariantCulture),
                "The sync baseline document uses version " + version.ToString(CultureInfo.InvariantCulture)
                + ", which this build does not read.");
        }
    }

    private void ReadPeer(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            throw new SyncContractException("invalid-field:peers", "Every peer entry must be an object.");
        }

        if (!element.TryGetProperty("deviceId", out JsonElement idElement)
            || idElement.ValueKind != JsonValueKind.String
            || string.IsNullOrWhiteSpace(idElement.GetString()))
        {
            throw new SyncContractException("invalid-field:deviceId", "Every peer entry needs a device id.");
        }

        if (!element.TryGetProperty("baseline", out JsonElement baselineElement)
            || baselineElement.ValueKind != JsonValueKind.Object)
        {
            throw new SyncContractException("invalid-field:baseline", "Every peer entry needs a baseline object.");
        }

        SourceSyncBaseline baseline = SourceSyncBaselineCodec.Deserialize(baselineElement.GetRawText());
        _peers[idElement.GetString()!.Trim()] = baseline;
    }

    private void Quarantine(string? reason)
    {
        _peers.Clear();
        LoadError = reason ?? SourceSyncBaselineCodecReasons.MalformedJson;
        _writable = false;

        try
        {
            string stamp = DateTime.UtcNow.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
            string candidate = Path.Combine(DirectoryPath, CorruptPrefix + stamp + ".json");
            int suffix = 1;
            while (File.Exists(candidate))
            {
                candidate = Path.Combine(
                    DirectoryPath,
                    CorruptPrefix + stamp + "-" + suffix.ToString(CultureInfo.InvariantCulture) + ".json");
                suffix++;
            }

            File.Move(FilePath, candidate);
            LastQuarantinedPath = candidate;
            // The damaged bytes are now preserved under their own name, so a fresh document may be written.
            _writable = true;
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private void Persist()
    {
        Directory.CreateDirectory(DirectoryPath);

        byte[] bytes = SerializeDocument();
        string tempPath = FilePath + ".tmp";
        try
        {
            using (var stream = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(flushToDisk: true);
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

    private byte[] SerializeDocument()
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = true }))
        {
            writer.WriteStartObject();
            writer.WriteNumber("version", CurrentDocumentVersion);
            writer.WriteStartArray("peers");
            foreach ((string deviceId, SourceSyncBaseline baseline) in _peers.OrderBy(pair => pair.Key, StringComparer.OrdinalIgnoreCase))
            {
                writer.WriteStartObject();
                writer.WriteString("deviceId", deviceId);
                writer.WritePropertyName("baseline");
                SourceSyncBaselineCodec.WriteBaseline(writer, baseline);
                writer.WriteEndObject();
            }

            writer.WriteEndArray();
            writer.WriteEndObject();
        }

        return buffer.ToArray();
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
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}

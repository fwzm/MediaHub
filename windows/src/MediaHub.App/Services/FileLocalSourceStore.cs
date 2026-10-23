using System.Text.Json;
using System.Text.Json.Serialization;
using MediaHub.Sync;

namespace MediaHub.App.Services;

/// <summary>
/// This client's own source records, persisted next to settings.json as sync-sources.json.
/// </summary>
/// <remarks>
/// <para>
/// This is the mirror the sync contract layer reads and writes, not the user's source list: settings.json
/// stays authoritative for what the app restores at startup, while this file holds what the contract needs
/// per source (normalised address, wire type, cross-device identity).
/// </para>
/// <para>
/// A file that cannot be parsed is reported and then left untouched. Rewriting it would throw away the
/// identities the other device already knows, and nothing else can recreate them.
/// </para>
/// </remarks>
public sealed class FileLocalSourceStore : ILocalSourceStore
{
    /// <summary>File name inside the settings directory.</summary>
    public const string FileName = "sync-sources.json";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly object _gate = new();
    private readonly List<LocalSourceRecord> _records = [];
    private bool _writable = true;

    /// <param name="directory">Defaults to <c>%LOCALAPPDATA%\MediaHub</c>; tests pass a temporary folder.</param>
    public FileLocalSourceStore(string? directory = null)
    {
        DirectoryPath = string.IsNullOrWhiteSpace(directory)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub")
            : directory!;
        FilePath = Path.Combine(DirectoryPath, FileName);
        Load();
    }

    /// <summary>Directory the file lives in.</summary>
    public string DirectoryPath { get; }

    /// <summary>Full path of the JSON file.</summary>
    public string FilePath { get; }

    /// <summary>
    /// Set when the file exists but could not be read. The page shows it and writes are refused so the
    /// existing bytes stay exactly as they were.
    /// </summary>
    public string? LoadError { get; private set; }

    public IReadOnlyList<LocalSourceRecord> GetAll()
    {
        lock (_gate) return _records.ToArray();
    }

    public void Save(LocalSourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        if (string.IsNullOrWhiteSpace(record.ServerId))
            throw new ArgumentException("A local source record needs a server id.", nameof(record));

        lock (_gate)
        {
            EnsureWritable();
            int index = IndexOf(record.ServerId);
            LocalSourceRecord? previous = index >= 0 ? _records[index] : null;
            if (previous is not null && previous == record) return;

            if (index >= 0) _records[index] = record;
            else _records.Add(record);

            try
            {
                Persist();
            }
            catch
            {
                if (index >= 0) _records[index] = previous!;
                else _records.RemoveAt(_records.Count - 1);
                throw;
            }
        }
    }

    public bool Delete(string syncSourceId)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId)) return false;

        lock (_gate)
        {
            EnsureWritable();
            int index = _records.FindIndex(record =>
                record.SyncSourceId is { Length: > 0 } id &&
                string.Equals(id.Trim(), syncSourceId.Trim(), StringComparison.OrdinalIgnoreCase));
            if (index < 0) return false;

            LocalSourceRecord removed = _records[index];
            _records.RemoveAt(index);
            try
            {
                Persist();
            }
            catch
            {
                _records.Insert(index, removed);
                throw;
            }
            return true;
        }
    }

    private int IndexOf(string serverId)
    {
        for (int i = 0; i < _records.Count; i++)
        {
            if (string.Equals(_records[i].ServerId, serverId, StringComparison.Ordinal)) return i;
        }
        return -1;
    }

    private void EnsureWritable()
    {
        if (_writable) return;
        throw new IOException(LoadError ?? "同步源清单不可写");
    }

    private void Persist()
    {
        System.IO.Directory.CreateDirectory(DirectoryPath);
        string temp = FilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(_records, JsonOptions));
        File.Move(temp, FilePath, overwrite: true);
    }

    private void Load()
    {
        if (!File.Exists(FilePath)) return;

        try
        {
            string json = File.ReadAllText(FilePath);
            List<LocalSourceRecord>? loaded = JsonSerializer.Deserialize<List<LocalSourceRecord>>(json, JsonOptions);
            if (loaded is null) throw new JsonException("文件内容为空");
            foreach (LocalSourceRecord record in loaded)
            {
                if (record is null || string.IsNullOrWhiteSpace(record.ServerId)) continue;
                if (IndexOf(record.ServerId) >= 0) continue;
                _records.Add(record);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or NotSupportedException)
        {
            // Empty in memory, untouched on disk. The next export tells the user what is wrong instead of
            // silently exporting nothing as if the machine simply had no sources.
            _records.Clear();
            _writable = false;
            LoadError = "同步源清单无法读取（" + ex.GetType().Name + "）：" + FilePath + "。为避免覆盖已损坏的文件，本次会话不会写入它。";
        }
    }
}

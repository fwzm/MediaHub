using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Unicode;

namespace MediaHub.Storage;

/// <summary>
/// Durable store for the user's own playlists, kept in
/// <c>%LOCALAPPDATA%\MediaHub\playlists.json</c> next to <c>history.json</c>.
/// </summary>
/// <remarks>
/// <para>
/// <b>A saved playlist is not <c>MediaHub.Domain.PlaybackQueue</c>.</b> The queue is a temporary,
/// in-memory "play these next" list scoped to one session and gone on restart; a saved playlist is
/// user data that survives restarts and is only changed by explicit user action. The two are kept
/// separate on purpose: nothing here enqueues anything, nothing here reads the queue, and a playlist
/// is never auto-played. Items enter the queue only when the user explicitly asks to queue or play
/// them, which is the App layer's decision to make.
/// </para>
/// <para>
/// Thread safety: every member takes one lock, and every mutation is written to disk before it
/// returns, so a caller that has seen a <c>true</c> result can rely on the change surviving a crash.
/// Reads hand out immutable snapshots, never live state.
/// </para>
/// <para>
/// Failure policy: "not found" and "invalid input" are reported as <c>false</c>, not exceptions. A
/// damaged or future-versioned document is moved aside as <c>playlists.corrupt-&lt;timestamp&gt;.json</c>
/// and treated as empty, so the app keeps running and the user's bytes are still on disk to recover by
/// hand. Only I/O failures that would otherwise lose data (cannot read, cannot write) surface as
/// <see cref="IOException"/>.
/// </para>
/// </remarks>
public sealed class PlaylistStore
{
    /// <summary>Document version written by this build; anything else is quarantined as unreadable.</summary>
    public const int CurrentVersion = 1;

    private const string FileName = "playlists.json";
    private const string TemporarySuffix = ".tmp";
    private const string CorruptInfix = ".corrupt-";

    // No BOM: the file is meant to be diffable and editable by hand.
    private static readonly UTF8Encoding Utf8NoBom = new(encoderShouldEmitUTF8Identifier: false);

    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        // Keep CJK titles readable in the file instead of "\u4e2d\u6587" noise; this is a local
        // data file, never embedded in HTML, so relaxing the encoder costs nothing.
        Encoder = JavaScriptEncoder.Create(UnicodeRanges.All),
    };

    private readonly object _gate = new();
    private readonly string _rootDirectory;

    // Ascending CreatedAt, which is also the order written to disk.
    private List<SavedPlaylist> _playlists;

    // Guarantees a strictly increasing CreatedAt even when several playlists are created inside one
    // clock tick, so GetAll's ordering (and the order the user sees) stays stable across restarts.
    private DateTimeOffset _lastCreatedAt;

    /// <summary>
    /// Opens the store rooted at <paramref name="rootDirectory"/>, or at
    /// <c>%LOCALAPPDATA%\MediaHub</c> when null. Tests pass a temporary directory.
    /// </summary>
    /// <remarks>
    /// Nothing is created on disk here; the directory and file appear on the first write. An
    /// unreadable or damaged file is handled as described on the type, never by throwing.
    /// </remarks>
    public PlaylistStore(string? rootDirectory = null)
    {
        _rootDirectory = rootDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "MediaHub");
        FilePath = Path.Combine(_rootDirectory, FileName);
        _playlists = Load();
    }

    /// <summary>Full path of <c>playlists.json</c>. Exposed for diagnostics.</summary>
    public string FilePath { get; }

    /// <summary>All playlists, oldest first.</summary>
    public IReadOnlyList<SavedPlaylist> GetAll()
    {
        lock (_gate)
        {
            return _playlists.ToArray();
        }
    }

    /// <summary>The playlist with that id, or null.</summary>
    public SavedPlaylist? Find(string playlistId)
    {
        ArgumentNullException.ThrowIfNull(playlistId);
        lock (_gate)
        {
            int index = IndexOf(playlistId);
            return index < 0 ? null : _playlists[index];
        }
    }

    /// <summary>
    /// Creates an empty playlist. The name must not be blank; duplicates of existing names are
    /// allowed because the user may legitimately want two "Later" lists.
    /// </summary>
    /// <exception cref="ArgumentException"><paramref name="name"/> is empty or whitespace.</exception>
    public SavedPlaylist Create(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (string.IsNullOrWhiteSpace(name))
        {
            throw new ArgumentException("A playlist needs a name.", nameof(name));
        }

        lock (_gate)
        {
            DateTimeOffset now = NextTimestamp();
            var created = new SavedPlaylist
            {
                Id = Guid.NewGuid().ToString("N"),
                Name = name,
                CreatedAt = now,
                UpdatedAt = now,
                Items = Array.Empty<SavedPlaylistItem>(),
            };

            var next = new List<SavedPlaylist>(_playlists) { created };
            Persist(next);
            _playlists = next;
            return created;
        }
    }

    /// <summary>
    /// Renames a playlist. Returns <c>false</c> for an unknown id; a blank name is rejected the same
    /// way (the playlist is left untouched rather than renamed to nothing).
    /// </summary>
    public bool Rename(string playlistId, string newName)
    {
        ArgumentNullException.ThrowIfNull(playlistId);
        ArgumentNullException.ThrowIfNull(newName);

        lock (_gate)
        {
            int index = IndexOf(playlistId);
            if (index < 0 || string.IsNullOrWhiteSpace(newName))
            {
                return false;
            }

            SavedPlaylist current = _playlists[index];
            if (string.Equals(current.Name, newName, StringComparison.Ordinal))
            {
                // Nothing to write: the document already says exactly this.
                return true;
            }

            Commit(index, current with { Name = newName, UpdatedAt = NextTimestamp() });
            return true;
        }
    }

    /// <summary>Deletes a playlist and its items. Returns <c>false</c> for an unknown id.</summary>
    public bool Delete(string playlistId)
    {
        ArgumentNullException.ThrowIfNull(playlistId);

        lock (_gate)
        {
            int index = IndexOf(playlistId);
            if (index < 0)
            {
                return false;
            }

            var next = new List<SavedPlaylist>(_playlists);
            next.RemoveAt(index);
            Persist(next);
            _playlists = next;
            return true;
        }
    }

    /// <summary>
    /// Appends an item to the end of a playlist. Returns <c>false</c> when the playlist is unknown or
    /// when it already holds an item with the same <see cref="SavedPlaylistItem.ServerId"/> and
    /// <see cref="SavedPlaylistItem.ItemId"/>; in both cases nothing is written, so a playlist can
    /// never hold the same library item twice.
    /// </summary>
    public bool AddItem(string playlistId, SavedPlaylistItem item)
    {
        ArgumentNullException.ThrowIfNull(playlistId);
        ArgumentNullException.ThrowIfNull(item);

        lock (_gate)
        {
            int index = IndexOf(playlistId);
            if (index < 0)
            {
                return false;
            }

            SavedPlaylist current = _playlists[index];
            foreach (SavedPlaylistItem existing in current.Items)
            {
                if (existing.HasSameIdentityAs(item))
                {
                    return false;
                }
            }

            var items = new List<SavedPlaylistItem>(current.Items.Count + 1);
            items.AddRange(current.Items);
            items.Add(item);

            Commit(index, current with
            {
                Items = items.AsReadOnly(),
                UpdatedAt = NextTimestamp(),
            });
            return true;
        }
    }

    /// <summary>Removes one item by identity. Returns <c>false</c> when either the playlist or the item is unknown.</summary>
    public bool RemoveItem(string playlistId, string serverId, string itemId)
    {
        ArgumentNullException.ThrowIfNull(playlistId);
        ArgumentNullException.ThrowIfNull(serverId);
        ArgumentNullException.ThrowIfNull(itemId);

        lock (_gate)
        {
            int index = IndexOf(playlistId);
            if (index < 0)
            {
                return false;
            }

            SavedPlaylist current = _playlists[index];
            int itemIndex = IndexOfItem(current, serverId, itemId);
            if (itemIndex < 0)
            {
                return false;
            }

            var items = new List<SavedPlaylistItem>(current.Items);
            items.RemoveAt(itemIndex);

            Commit(index, current with
            {
                Items = items.AsReadOnly(),
                UpdatedAt = NextTimestamp(),
            });
            return true;
        }
    }

    /// <summary>
    /// Moves the item at <paramref name="fromIndex"/> to <paramref name="toIndex"/> within the
    /// playlist. Returns <c>false</c> when the playlist is unknown or either index is outside
    /// <c>[0, Items.Count)</c>; a move onto itself succeeds without writing.
    /// </summary>
    public bool MoveItem(string playlistId, int fromIndex, int toIndex)
    {
        ArgumentNullException.ThrowIfNull(playlistId);

        lock (_gate)
        {
            int index = IndexOf(playlistId);
            if (index < 0)
            {
                return false;
            }

            SavedPlaylist current = _playlists[index];
            int count = current.Items.Count;
            if (fromIndex < 0 || fromIndex >= count || toIndex < 0 || toIndex >= count)
            {
                return false;
            }

            if (fromIndex == toIndex)
            {
                return true;
            }

            var items = new List<SavedPlaylistItem>(current.Items);
            SavedPlaylistItem moved = items[fromIndex];
            items.RemoveAt(fromIndex);
            items.Insert(toIndex, moved);

            Commit(index, current with
            {
                Items = items.AsReadOnly(),
                UpdatedAt = NextTimestamp(),
            });
            return true;
        }
    }

    /// <summary>
    /// Replaces the playlist at <paramref name="index"/> with <paramref name="updated"/>, writing to
    /// disk first: memory only advances once the new document is durable.
    /// </summary>
    private void Commit(int index, SavedPlaylist updated)
    {
        var next = new List<SavedPlaylist>(_playlists) { [index] = updated };
        Persist(next);
        _playlists = next;
    }

    private int IndexOf(string playlistId)
    {
        for (int i = 0; i < _playlists.Count; i++)
        {
            if (string.Equals(_playlists[i].Id, playlistId, StringComparison.Ordinal))
            {
                return i;
            }
        }

        return -1;
    }

    private static int IndexOfItem(SavedPlaylist playlist, string serverId, string itemId)
    {
        for (int i = 0; i < playlist.Items.Count; i++)
        {
            SavedPlaylistItem item = playlist.Items[i];
            if (string.Equals(item.ServerId, serverId, StringComparison.Ordinal)
                && string.Equals(item.ItemId, itemId, StringComparison.Ordinal))
            {
                return i;
            }
        }

        return -1;
    }

    private DateTimeOffset NextTimestamp()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        if (now <= _lastCreatedAt)
        {
            now = _lastCreatedAt.AddTicks(1);
        }

        _lastCreatedAt = now;
        return now;
    }

    /// <summary>Writes the whole document atomically: same-directory temp file, flushed, then renamed over.</summary>
    private void Persist(List<SavedPlaylist> playlists)
    {
        string temporaryPath = FilePath + TemporarySuffix;
        try
        {
            // Created lazily so simply opening a store has no side effects.
            Directory.CreateDirectory(_rootDirectory);

            string json = JsonSerializer.Serialize(ToDocument(playlists), SerializerOptions);

            using (var stream = new FileStream(
                       temporaryPath,
                       FileMode.Create,
                       FileAccess.Write,
                       FileShare.None))
            using (var writer = new StreamWriter(stream, Utf8NoBom, bufferSize: 4096, leaveOpen: true))
            {
                writer.Write(json);
                writer.Flush();
                stream.Flush(flushToDisk: true);
            }

            // A rename within one directory is atomic on NTFS: a reader sees the old document or the
            // new one, never a half-written file.
            File.Move(temporaryPath, FilePath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            TryDeleteTemporaryFile(temporaryPath);
            throw new IOException($"Could not write the playlist file '{FilePath}'.", ex);
        }
    }

    /// <summary>
    /// Reads the document. A missing file is simply an empty library; a damaged one is quarantined.
    /// </summary>
    private List<SavedPlaylist> Load()
    {
        var empty = new List<SavedPlaylist>();
        if (!File.Exists(FilePath))
        {
            return empty;
        }

        string json;
        try
        {
            json = File.ReadAllText(FilePath, Utf8NoBom);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // The file is there but its contents are unknown. Treating that as "empty" would let the
            // next write overwrite playlists that are probably still perfectly good, so this one is
            // surfaced instead of swallowed.
            throw new IOException($"Could not read the playlist file '{FilePath}'.", ex);
        }

        PlaylistDocument? document;
        try
        {
            document = JsonSerializer.Deserialize<PlaylistDocument>(json, SerializerOptions);
        }
        catch (JsonException)
        {
            QuarantineCorruptFile();
            return empty;
        }

        if (document is null || document.Version != CurrentVersion)
        {
            // Unparseable or written by a future build: do not guess at its shape.
            QuarantineCorruptFile();
            return empty;
        }

        return MapDocument(document);
    }

    private static List<SavedPlaylist> MapDocument(PlaylistDocument document)
    {
        var playlists = new List<SavedPlaylist>();
        if (document.Playlists is null)
        {
            return playlists;
        }

        foreach (PlaylistRecord? record in document.Playlists)
        {
            if (record is null || string.IsNullOrWhiteSpace(record.Id))
            {
                continue;
            }

            var items = new List<SavedPlaylistItem>();
            if (record.Items is not null)
            {
                foreach (ItemRecord? item in record.Items)
                {
                    if (item is null || item.ServerId is null || item.ItemId is null)
                    {
                        continue;
                    }

                    items.Add(new SavedPlaylistItem
                    {
                        ServerId = item.ServerId,
                        ItemId = item.ItemId,
                        Title = item.Title ?? string.Empty,
                        RunTimeTicks = item.RunTimeTicks,
                    });
                }
            }

            playlists.Add(new SavedPlaylist
            {
                Id = record.Id,
                Name = record.Name ?? string.Empty,
                CreatedAt = record.CreatedAt,
                UpdatedAt = record.UpdatedAt,
                Items = items.AsReadOnly(),
            });
        }

        return playlists
            .OrderBy(playlist => playlist.CreatedAt)
            .ThenBy(playlist => playlist.Id, StringComparer.Ordinal)
            .ToList();
    }

    private static PlaylistDocument ToDocument(List<SavedPlaylist> playlists)
        => new()
        {
            Version = CurrentVersion,
            Playlists = playlists
                .Select(playlist => new PlaylistRecord
                {
                    Id = playlist.Id,
                    Name = playlist.Name,
                    CreatedAt = playlist.CreatedAt,
                    UpdatedAt = playlist.UpdatedAt,
                    Items = playlist.Items
                        .Select(item => new ItemRecord
                        {
                            ServerId = item.ServerId,
                            ItemId = item.ItemId,
                            Title = item.Title,
                            RunTimeTicks = item.RunTimeTicks,
                        })
                        .ToArray(),
                })
                .ToArray(),
        };

    /// <summary>
    /// Moves the unreadable file aside as <c>playlists.corrupt-&lt;timestamp&gt;.json</c>. The bytes
    /// are never deleted: if the store guessed wrong about "corrupt", the user still has the data.
    /// </summary>
    private void QuarantineCorruptFile()
    {
        string stamp = DateTimeOffset.UtcNow.ToString("yyyyMMdd'T'HHmmssfff'Z'", CultureInfo.InvariantCulture);
        string quarantinePath = Path.Combine(
            _rootDirectory,
            Path.GetFileNameWithoutExtension(FileName) + CorruptInfix + stamp + Path.GetExtension(FileName));

        try
        {
            File.Move(FilePath, quarantinePath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Best effort: if the file cannot be moved (locked by an antivirus scan, say), starting
            // empty still lets the app run, and the next successful write will replace it.
        }
    }

    private static void TryDeleteTemporaryFile(string temporaryPath)
    {
        try
        {
            File.Delete(temporaryPath);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // The leftover temp file is inert; the next successful write overwrites it.
        }
    }
}

using System.Globalization;
using System.Text;
using System.Text.Json;
using MediaHub.Storage;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins <see cref="PlaylistStore"/>: playlists are permanent user data in
/// <c>playlists.json</c>, written atomically and isolated from the transient
/// <c>MediaHub.Domain.PlaybackQueue</c>.
/// </summary>
public sealed class PlaylistStoreTests : IDisposable
{
    private readonly string _root;

    public PlaylistStoreTests()
    {
        _root = Path.Combine(
            Path.GetTempPath(),
            "mediahub-playlists-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp directory must never fail the test that just passed.
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private string FilePath => Path.Combine(_root, "playlists.json");

    private PlaylistStore NewStore() => new(_root);

    private static SavedPlaylistItem Item(string serverId, string itemId, string title = "标题")
        => new()
        {
            ServerId = serverId,
            ItemId = itemId,
            Title = title,
        };

    [Fact]
    public void ReopeningTheStoreReadsBackWhatWasWritten()
    {
        PlaylistStore first = NewStore();
        SavedPlaylist created = first.Create("周末看");
        Assert.True(first.AddItem(created.Id, new SavedPlaylistItem
        {
            ServerId = "server-a",
            ItemId = "item-1",
            Title = "第一集",
            RunTimeTicks = 1_234_567,
        }));

        // A second instance over the same directory stands in for "the app was restarted".
        PlaylistStore reopened = NewStore();

        SavedPlaylist reloaded = Assert.Single(reopened.GetAll());
        Assert.Equal(created.Id, reloaded.Id);
        Assert.Equal("周末看", reloaded.Name);
        Assert.Equal(created.CreatedAt, reloaded.CreatedAt);

        SavedPlaylistItem item = Assert.Single(reloaded.Items);
        Assert.Equal("server-a", item.ServerId);
        Assert.Equal("item-1", item.ItemId);
        Assert.Equal("第一集", item.Title);
        Assert.Equal(1_234_567, item.RunTimeTicks);
    }

    [Fact]
    public void CreateAssignsDistinctGuidsAndGetAllOrdersByCreation()
    {
        PlaylistStore store = NewStore();

        var created = new List<SavedPlaylist>();
        for (int index = 0; index < 3; index++)
        {
            created.Add(store.Create($"列表 {index}"));
        }

        Assert.All(created, playlist => Assert.Matches("^[0-9a-f]{32}$", playlist.Id));
        Assert.Equal(3, created.Select(playlist => playlist.Id).Distinct().Count());

        Assert.Equal(
            created.Select(playlist => playlist.Id),
            store.GetAll().Select(playlist => playlist.Id));

        // Strictly increasing even when several are created within one clock tick: the order the
        // user sees must not shuffle on the next restart.
        Assert.True(created[0].CreatedAt < created[1].CreatedAt);
        Assert.True(created[1].CreatedAt < created[2].CreatedAt);

        Assert.Equal(
            created.Select(playlist => playlist.Id),
            NewStore().GetAll().Select(playlist => playlist.Id));
    }

    [Fact]
    public void DuplicateNamesAreAllowedBecauseIdsAreTheIdentity()
    {
        PlaylistStore store = NewStore();

        SavedPlaylist first = store.Create("稍后");
        SavedPlaylist second = store.Create("稍后");

        Assert.NotEqual(first.Id, second.Id);
        Assert.Equal(2, store.GetAll().Count);
        Assert.All(store.GetAll(), playlist => Assert.Equal("稍后", playlist.Name));
    }

    [Fact]
    public void RenameChangesTheNameAndUpdatesTheTimestamp()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist created = store.Create("旧名");

        Assert.True(store.Rename(created.Id, "新名"));

        SavedPlaylist renamed = store.Find(created.Id)!;
        Assert.Equal("新名", renamed.Name);
        Assert.True(renamed.UpdatedAt > created.UpdatedAt);
        Assert.Equal("新名", NewStore().Find(created.Id)!.Name);

        // Unknown ids are a plain "no", not an exception.
        Assert.False(store.Rename(Guid.NewGuid().ToString("N"), "随便"));
    }

    [Fact]
    public void RenameRejectsBlankNamesWithoutTouchingThePlaylist()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("保留");

        Assert.False(store.Rename(playlist.Id, "   "));
        Assert.Equal("保留", store.Find(playlist.Id)!.Name);
    }

    [Fact]
    public void DeleteRemovesThePlaylistAndReportsUnknownIds()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist first = store.Create("一");
        SavedPlaylist second = store.Create("二");

        Assert.True(store.Delete(first.Id));
        Assert.Null(store.Find(first.Id));
        SavedPlaylist remaining = Assert.Single(store.GetAll());
        Assert.Equal(second.Id, remaining.Id);
        Assert.False(File.ReadAllText(FilePath).Contains(first.Id, StringComparison.Ordinal));

        Assert.False(store.Delete(first.Id));
        Assert.False(store.Delete(Guid.NewGuid().ToString("N")));
    }

    [Fact]
    public void AddItemIgnoresDuplicatesAndLeavesTheDocumentUntouched()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("追剧");
        Assert.True(store.AddItem(playlist.Id, new SavedPlaylistItem
        {
            ServerId = "srv",
            ItemId = "abc",
            Title = "第一版标题",
            RunTimeTicks = null,
        }));

        string before = File.ReadAllText(FilePath);

        // Same identity, different display snapshot: still the same entry.
        Assert.False(store.AddItem(playlist.Id, new SavedPlaylistItem
        {
            ServerId = "srv",
            ItemId = "abc",
            Title = "改了标题",
            RunTimeTicks = 42,
        }));

        SavedPlaylist stored = store.Find(playlist.Id)!;
        SavedPlaylistItem entry = Assert.Single(stored.Items);
        Assert.Equal("第一版标题", entry.Title);
        Assert.Null(entry.RunTimeTicks);
        Assert.Equal(before, File.ReadAllText(FilePath));
    }

    [Fact]
    public void AddItemReportsUnknownPlaylists()
    {
        PlaylistStore store = NewStore();

        Assert.False(store.AddItem(Guid.NewGuid().ToString("N"), Item("srv", "abc")));

        // A rejected write must not even create an empty document.
        Assert.False(File.Exists(FilePath));
    }

    [Fact]
    public void RemoveItemRemovesByIdentityAndReportsMissingEntries()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("稍后");
        Assert.True(store.AddItem(playlist.Id, Item("srv", "a", "甲")));
        Assert.True(store.AddItem(playlist.Id, Item("srv", "b", "乙")));

        Assert.True(store.RemoveItem(playlist.Id, "srv", "a"));
        Assert.Equal(
            new[] { "b" },
            store.Find(playlist.Id)!.Items.Select(item => item.ItemId));

        // Identity comparison is ordinal, so a different case is a different item.
        Assert.False(store.RemoveItem(playlist.Id, "srv", "A"));
        Assert.False(store.RemoveItem(playlist.Id, "server-other", "b"));
        Assert.False(store.RemoveItem(Guid.NewGuid().ToString("N"), "srv", "b"));
        Assert.Single(store.Find(playlist.Id)!.Items);
    }

    [Fact]
    public void MoveItemReordersItemsAndRejectsOutOfRangeIndexes()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("顺序");
        foreach (string itemId in new[] { "a", "b", "c" })
        {
            Assert.True(store.AddItem(playlist.Id, Item("srv", itemId, itemId.ToUpperInvariant())));
        }

        Assert.True(store.MoveItem(playlist.Id, 0, 2));

        // The order is what the document says, not just what memory says.
        string[] expected = { "b", "c", "a" };
        Assert.Equal(expected, store.Find(playlist.Id)!.Items.Select(item => item.ItemId));
        Assert.Equal(expected, NewStore().Find(playlist.Id)!.Items.Select(item => item.ItemId));

        Assert.False(store.MoveItem(playlist.Id, -1, 0));
        Assert.False(store.MoveItem(playlist.Id, 0, 3));
        Assert.False(store.MoveItem(playlist.Id, 3, 0));
        Assert.False(store.MoveItem(Guid.NewGuid().ToString("N"), 0, 1));
        Assert.Equal(expected, store.Find(playlist.Id)!.Items.Select(item => item.ItemId));
    }

    [Fact]
    public void CreateRejectsBlankNames()
    {
        PlaylistStore store = NewStore();

        Assert.Throws<ArgumentException>(() => store.Create(string.Empty));
        Assert.Throws<ArgumentException>(() => store.Create("   "));
        Assert.Throws<ArgumentNullException>(() => store.Create(null!));

        Assert.Empty(store.GetAll());
        Assert.False(File.Exists(FilePath));
    }

    [Fact]
    public void MissingFileIsAnEmptyLibrary()
    {
        PlaylistStore store = NewStore();

        Assert.Empty(store.GetAll());
        Assert.Null(store.Find(Guid.NewGuid().ToString("N")));
        Assert.False(File.Exists(FilePath));
    }

    [Fact]
    public void DamagedDocumentIsQuarantinedAndTheLibraryStartsEmpty()
    {
        File.WriteAllText(FilePath, "{ this is not json");

        PlaylistStore store = NewStore();

        Assert.Empty(store.GetAll());
        string quarantine = Assert.Single(Directory.GetFiles(_root, "playlists.corrupt-*.json"));
        Assert.Contains("this is not json", File.ReadAllText(quarantine), StringComparison.Ordinal);
        Assert.False(File.Exists(FilePath));

        // The store keeps working and the quarantined bytes stay recoverable by hand.
        Assert.NotNull(store.Create("新的"));
        Assert.Single(NewStore().GetAll());
        Assert.Single(Directory.GetFiles(_root, "playlists.corrupt-*.json"));
    }

    [Fact]
    public void UnknownDocumentVersionIsQuarantinedInsteadOfGuessedAt()
    {
        File.WriteAllText(FilePath, """{"version": 99, "playlists": []}""");

        PlaylistStore store = NewStore();

        Assert.Empty(store.GetAll());
        Assert.Single(Directory.GetFiles(_root, "playlists.corrupt-*.json"));
    }

    [Fact]
    public void NonAsciiNamesAndTitlesSurviveTheRoundTrip()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("中文 列表 · 周末");
        Assert.True(store.AddItem(playlist.Id, Item("服务器一", "条目一", "我的名字叫「红」")));

        SavedPlaylist reloaded = Assert.Single(NewStore().GetAll());
        Assert.Equal("中文 列表 · 周末", reloaded.Name);
        Assert.Equal("我的名字叫「红」", Assert.Single(reloaded.Items).Title);

        // The file is UTF-8 without BOM and carries the characters themselves, not \uXXXX escapes:
        // it is meant to stay readable when the user opens it.
        byte[] bytes = File.ReadAllBytes(FilePath);
        bool hasBom = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
        Assert.False(hasBom);

        string text = Encoding.UTF8.GetString(bytes);
        Assert.Contains("中文 列表 · 周末", text, StringComparison.Ordinal);
        Assert.DoesNotContain(@"\u4e2d", text, StringComparison.Ordinal);
    }

    [Fact]
    public void DocumentIsVersionedAndIndentedForHumans()
    {
        PlaylistStore store = NewStore();
        store.Create("格式");

        string text = File.ReadAllText(FilePath);
        using JsonDocument document = JsonDocument.Parse(text);

        Assert.Equal(1, document.RootElement.GetProperty("version").GetInt32());
        JsonElement playlists = document.RootElement.GetProperty("playlists");
        Assert.Equal(JsonValueKind.Array, playlists.ValueKind);
        Assert.Equal("格式", playlists[0].GetProperty("name").GetString());

        Assert.Contains("\n", text, StringComparison.Ordinal);
        Assert.DoesNotContain(@"\u683c", text, StringComparison.Ordinal);
    }

    [Fact]
    public void ParallelCreatesAndAddsKeepTheDocumentConsistent()
    {
        PlaylistStore store = NewStore();
        const int playlistCount = 20;

        Parallel.For(0, playlistCount, index =>
        {
            SavedPlaylist playlist = store.Create($"并行 {index}");
            Assert.True(store.AddItem(playlist.Id, Item("srv", $"item-{index}", $"标题 {index}")));
            Assert.True(store.AddItem(playlist.Id, Item("srv", $"extra-{index}", $"额外 {index}")));
        });

        Assert.Equal(playlistCount, store.GetAll().Count);
        Assert.All(store.GetAll(), playlist => Assert.Equal(2, playlist.Items.Count));

        // After 60 interleaved writes the document is still exactly one well-formed version-1 file.
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(FilePath));
        Assert.Equal(1, document.RootElement.GetProperty("version").GetInt32());
        Assert.Equal(
            playlistCount,
            document.RootElement.GetProperty("playlists").GetArrayLength());

        // And a fresh reader agrees with memory.
        Assert.Equal(playlistCount, NewStore().GetAll().Count);
        Assert.All(NewStore().GetAll(), playlist => Assert.Equal(2, playlist.Items.Count));
    }

    [Fact]
    public void SavingLeavesNoTemporaryFilesBehind()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("原子");
        Assert.True(store.AddItem(playlist.Id, Item("srv", "a")));
        Assert.True(store.MoveItem(playlist.Id, 0, 0));

        Assert.True(File.Exists(FilePath));
        Assert.Empty(Directory.GetFiles(_root, "*.tmp"));
        Assert.False(File.Exists(FilePath + ".tmp"));
    }

    [Fact]
    public void SaveFailureSurfacesAsIOExceptionAndKeepsThePreviousDocument()
    {
        PlaylistStore store = NewStore();
        SavedPlaylist playlist = store.Create("可写");

        // A directory sitting where the temp file must go makes the write fail.
        Directory.CreateDirectory(FilePath + ".tmp");

        IOException error = Assert.Throws<IOException>(() => store.Create("写不进去"));
        Assert.Contains("playlists.json", error.Message, StringComparison.Ordinal);

        // Memory did not advance and the previous document is still readable.
        SavedPlaylist only = Assert.Single(store.GetAll());
        Assert.Equal(playlist.Id, only.Id);
        Assert.NotNull(NewStore().Find(playlist.Id));
    }
}

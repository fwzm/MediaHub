using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the on-disk behaviour of <see cref="FileLocalSourceStore"/>: what survives a restart, what is
/// refused, and what happens to a file that cannot be read.
/// </summary>
/// <remarks>
/// These tests exist because the store used to live in the App project, which the test project cannot
/// reference, so its invariants (atomic replacement, refusing to overwrite a corrupt file) were only
/// defended by reading the code. Each test below fails if the corresponding guarantee regresses.
/// </remarks>
public sealed class FileLocalSourceStoreTests : IDisposable
{
    private readonly string _root;

    public FileLocalSourceStoreTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mh-sync-sources-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
        }
        catch (IOException)
        {
            // A leaked temp directory is preferable to a failed test run.
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private string DirectoryPath => Path.Combine(_root, "MediaHub");

    private string FilePath => Path.Combine(DirectoryPath, FileLocalSourceStore.FileName);

    private static LocalSourceRecord Record(
        string serverId,
        string? syncSourceId = null,
        string? displayName = "客厅 Jellyfin",
        string? url = "http://host:8096",
        string? accountScope = "alice",
        ServerType type = ServerType.Jellyfin,
        long recordRevision = 1) => new()
        {
            ServerId = serverId,
            SyncSourceId = syncSourceId,
            Type = type,
            Url = url,
            AccountScope = accountScope,
            DisplayName = displayName,
            RecordRevision = recordRevision,
        };

    private static byte[] ReadBytes(string path) => File.ReadAllBytes(path);

    [Fact]
    public void Save_WritesToDiskAndSurvivesANewStoreInstance()
    {
        LocalSourceRecord record = Record("jellyfin:host", "local-0123456789abcdef");
        var first = new FileLocalSourceStore(DirectoryPath);
        first.Save(record);

        Assert.True(File.Exists(FilePath));

        var second = new FileLocalSourceStore(DirectoryPath);
        Assert.Null(second.LoadError);
        LocalSourceRecord reloaded = Assert.Single(second.GetAll());
        Assert.Equal(record, reloaded);
    }

    [Fact]
    public void Save_LeavesNoTemporaryFileBehind()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("a"));
        store.Save(Record("b"));
        store.Save(Record("a", recordRevision: 2));

        Assert.Empty(Directory.GetFiles(DirectoryPath, "*.tmp"));
        Assert.False(File.Exists(FilePath + ".tmp"));
    }

    [Fact]
    public void Save_WithTheSameServerIdReplacesInsteadOfAppending()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("jellyfin:host", recordRevision: 1));
        store.Save(Record("jellyfin:host", recordRevision: 7));

        LocalSourceRecord only = Assert.Single(store.GetAll());
        Assert.Equal(7, only.RecordRevision);
        Assert.Equal(7, Assert.Single(new FileLocalSourceStore(DirectoryPath).GetAll()).RecordRevision);
    }

    [Fact]
    public void Save_IdenticalRecordDoesNotTouchTheFile()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        LocalSourceRecord record = Record("jellyfin:host");
        store.Save(record);

        byte[] before = ReadBytes(FilePath);
        DateTime beforeWrite = File.GetLastWriteTimeUtc(FilePath);

        store.Save(record);

        Assert.Equal(before, ReadBytes(FilePath));
        Assert.Equal(beforeWrite, File.GetLastWriteTimeUtc(FilePath));
    }

    [Fact]
    public void Save_CreatesTheDirectoryWhenItIsMissing()
    {
        Assert.False(Directory.Exists(DirectoryPath));

        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("jellyfin:host"));

        Assert.True(File.Exists(FilePath));
        Assert.Single(new FileLocalSourceStore(DirectoryPath).GetAll());
    }

    [Fact]
    public void Delete_RemovesTheRecordAndPersistsImmediately()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("a", "local-aaaa"));
        store.Save(Record("b", "local-bbbb"));

        Assert.True(store.Delete("local-aaaa"));

        LocalSourceRecord remaining = Assert.Single(store.GetAll());
        Assert.Equal("b", remaining.ServerId);
        Assert.Single(new FileLocalSourceStore(DirectoryPath).GetAll());
    }

    [Fact]
    public void Delete_MatchesTheSyncIdCaseInsensitivelyAndIgnoresPadding()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("a", "local-AAAA"));

        Assert.True(store.Delete("  LOCAL-aaaa "));
        Assert.Empty(store.GetAll());
    }

    [Fact]
    public void Delete_UnknownSyncIdReturnsFalseWithoutRewritingTheFile()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("a", "local-aaaa"));

        byte[] before = ReadBytes(FilePath);
        DateTime beforeWrite = File.GetLastWriteTimeUtc(FilePath);

        Assert.False(store.Delete("local-does-not-exist"));
        Assert.False(store.Delete(""));

        Assert.Equal(before, ReadBytes(FilePath));
        Assert.Equal(beforeWrite, File.GetLastWriteTimeUtc(FilePath));
    }

    [Fact]
    public void Load_MissingFileIsAnEmptyWritableStore()
    {
        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Null(store.LoadError);
        Assert.Empty(store.GetAll());

        store.Save(Record("a"));
        Assert.Single(store.GetAll());
    }

    [Fact]
    public void Load_EmptyArrayIsWritable()
    {
        Directory.CreateDirectory(DirectoryPath);
        File.WriteAllText(FilePath, "[]");

        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Null(store.LoadError);
        Assert.Empty(store.GetAll());
        store.Save(Record("a"));
        Assert.Single(new FileLocalSourceStore(DirectoryPath).GetAll());
    }

    [Theory]
    [InlineData("")]
    [InlineData("null")]
    [InlineData("{ \"sources\": [")]
    [InlineData("[ { \"serverId\": 3 } ]")]
    public void Load_CorruptFileIsReportedAndNeverOverwritten(string payload)
    {
        Directory.CreateDirectory(DirectoryPath);
        File.WriteAllText(FilePath, payload);
        byte[] before = ReadBytes(FilePath);

        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.NotNull(store.LoadError);
        Assert.Empty(store.GetAll());
        Assert.Throws<IOException>(() => store.Save(Record("a")));
        Assert.Throws<IOException>(() => store.Delete("local-aaaa"));

        // The identities the other device knows cannot be recreated, so the bytes must survive untouched.
        Assert.Equal(before, ReadBytes(FilePath));
        Assert.False(File.Exists(FilePath + ".tmp"));
    }

    [Fact]
    public void Load_KeepsTheFirstRecordForADuplicatedServerId()
    {
        Directory.CreateDirectory(DirectoryPath);
        File.WriteAllText(FilePath, """
            [
              { "serverId": "dup", "displayName": "first", "type": "Jellyfin", "recordRevision": 1 },
              { "serverId": "dup", "displayName": "second", "type": "Jellyfin", "recordRevision": 2 }
            ]
            """);

        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Null(store.LoadError);
        LocalSourceRecord only = Assert.Single(store.GetAll());
        Assert.Equal("first", only.DisplayName);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void Save_BlankServerIdIsRejected(string serverId)
    {
        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Throws<ArgumentException>(() => store.Save(Record(serverId)));
        Assert.Empty(store.GetAll());
    }

    [Fact]
    public void Save_NullRecordIsRejected()
    {
        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Throws<ArgumentNullException>(() => store.Save(null!));
    }

    [Fact]
    public void GetAll_KeepsInsertionOrderAcrossAReload()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(Record("zulu", displayName: "Z"));
        store.Save(Record("alpha", displayName: "A"));
        store.Save(Record("mike", displayName: "M"));

        Assert.Equal(new[] { "zulu", "alpha", "mike" }, store.GetAll().Select(record => record.ServerId));

        var reloaded = new FileLocalSourceStore(DirectoryPath);
        Assert.Equal(new[] { "zulu", "alpha", "mike" }, reloaded.GetAll().Select(record => record.ServerId));
    }

    [Fact]
    public void Save_PersistsTheSyncContractFieldsIncludingAnUnknownWireType()
    {
        var store = new FileLocalSourceStore(DirectoryPath);
        store.Save(new LocalSourceRecord
        {
            ServerId = "quantum:host",
            SyncSourceId = "local-ffffffffffffffff",
            Type = ServerType.Unknown,
            SourceTypeRaw = "Quantum-NAS",
            Url = "http://host:9000",
            AccountScope = null,
            DisplayName = "未支持但保留",
            RecordRevision = 4,
        });

        var reloaded = new FileLocalSourceStore(DirectoryPath);
        LocalSourceRecord record = Assert.Single(reloaded.GetAll());
        Assert.Equal(ServerType.Unknown, record.Type);
        Assert.Equal("Quantum-NAS", record.SourceTypeRaw);
        Assert.Null(record.AccountScope);
        Assert.Equal(4, record.RecordRevision);

        // The file is readable JSON with the wire value kept verbatim, not an enum name.
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(FilePath));
        JsonElement only = Assert.Single(document.RootElement.EnumerateArray());
        Assert.Equal("Quantum-NAS", only.GetProperty("sourceTypeRaw").GetString());
    }

    [Fact]
    public void Store_ReportsItsOwnPaths()
    {
        var store = new FileLocalSourceStore(DirectoryPath);

        Assert.Equal(DirectoryPath, store.DirectoryPath);
        Assert.Equal(FilePath, store.FilePath);
        Assert.Equal("sync-sources.json", FileLocalSourceStore.FileName);
    }
}

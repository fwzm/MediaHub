using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the persisted baseline: atomic writes, quarantine instead of deletion, and a revision guard that never
/// lets a later round move the peer backwards.
/// </summary>
/// <remarks>
/// Every test uses a private temporary directory. A baseline file that survives a crash wrongly is worse than one
/// that is missing, so the damage paths are tested as carefully as the happy path.
/// </remarks>
public sealed class SourceSyncBaselineStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        "mh-baseline-" + Guid.NewGuid().ToString("N"));

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
        }
    }

    private static SourceSyncBaseline Baseline(string id, long revision, string contentHash = "hash") => new()
    {
        DeviceId = "device-a",
        SnapshotId = "snapshot-1",
        Entries =
        [
            new SourceSyncBaselineEntry
            {
                SyncSourceId = id,
                RecordRevision = revision,
                ContentHash = contentHash,
                FieldHashes = new Dictionary<string, string>(StringComparer.Ordinal) { ["displayName"] = "h-" + contentHash },
            },
        ],
    };

    private static string[] TemporaryFiles(string root)
        => Directory.Exists(root) ? Directory.GetFiles(root, "*.tmp", SearchOption.AllDirectories) : [];

    /// <summary>What was saved is what a brand new store instance reads: the restart path, in one assertion.</summary>
    [Fact]
    public void Save_ThenLoadFromANewInstance()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        Assert.True(store.Save("device-a", Baseline("sync-1", 3)).Saved);

        var reopened = new FileSourceSyncBaselineStore(_root);
        SourceSyncBaseline loaded = reopened.Load("device-a");

        Assert.Null(reopened.LoadError);
        SourceSyncBaselineEntry entry = Assert.Single(loaded.Entries);
        Assert.Equal("sync-1", entry.SyncSourceId);
        Assert.Equal(3, entry.RecordRevision);
        Assert.Equal("hash", entry.ContentHash);
        Assert.Equal("h-hash", entry.FieldHashes["displayName"]);
    }

    /// <summary>Two peers never share a baseline: knowing B says nothing about A.</summary>
    [Fact]
    public void PeersAreIsolated()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-a", 4));
        store.Save("device-b", Baseline("sync-b", 9));

        var reopened = new FileSourceSyncBaselineStore(_root);

        Assert.Equal("sync-a", Assert.Single(reopened.Load("device-a").Entries).SyncSourceId);
        Assert.Equal("sync-b", Assert.Single(reopened.Load("device-b").Entries).SyncSourceId);
        Assert.True(reopened.Load("device-c").IsEmpty);
        Assert.True(reopened.Load("  ").IsEmpty);
    }

    /// <summary>An atomic write leaves no temporary file behind, whether or not a previous document existed.</summary>
    [Fact]
    public void Save_LeavesNoTemporaryFileBehind()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 1));
        Assert.Empty(TemporaryFiles(_root));

        store.Save("device-a", Baseline("sync-1", 2));
        Assert.Empty(TemporaryFiles(_root));
        Assert.True(File.Exists(store.FilePath));
    }

    /// <summary>The previous document is kept as a .bak, so a bad swap has a recovery path that is not a full resync.</summary>
    [Fact]
    public void Save_KeepsThePreviousDocumentAsBak()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 1));
        Assert.False(File.Exists(store.BackupPath));

        store.Save("device-a", Baseline("sync-1", 2));

        Assert.True(File.Exists(store.BackupPath));
        using JsonDocument backup = JsonDocument.Parse(File.ReadAllText(store.BackupPath));
        JsonElement entry = backup.RootElement.GetProperty("peers")[0].GetProperty("baseline").GetProperty("entries")[0];
        Assert.Equal(1, entry.GetProperty("recordRevision").GetInt64());
    }

    /// <summary>A damaged document is preserved under a .corrupt name, never deleted, and the store reads as empty.</summary>
    [Fact]
    public void CorruptDocument_IsQuarantinedAndTreatedAsEmpty()
    {
        Directory.CreateDirectory(_root);
        string path = Path.Combine(_root, FileSourceSyncBaselineStore.FileName);
        byte[] original = "this is not json"u8.ToArray();
        File.WriteAllBytes(path, original);

        var store = new FileSourceSyncBaselineStore(_root);
        SourceSyncBaseline loaded = store.Load("device-a");

        Assert.True(loaded.IsEmpty);
        Assert.Equal(SourceSyncBaselineCodecReasons.MalformedJson, store.LoadError);
        Assert.NotNull(store.LastQuarantinedPath);
        Assert.True(File.Exists(store.LastQuarantinedPath));
        Assert.Equal(original, File.ReadAllBytes(store.LastQuarantinedPath!));
        Assert.False(File.Exists(path));
        Assert.Single(Directory.GetFiles(_root, FileSourceSyncBaselineStore.CorruptPrefix + "*"));
    }

    /// <summary>A document written by a newer build is quarantined by version, not guessed at field by field.</summary>
    [Fact]
    public void NewerDocumentVersion_IsQuarantined()
    {
        Directory.CreateDirectory(_root);
        string path = Path.Combine(_root, FileSourceSyncBaselineStore.FileName);
        File.WriteAllText(path, "{\"version\":2,\"peers\":[]}");

        var store = new FileSourceSyncBaselineStore(_root);

        Assert.True(store.Load("device-a").IsEmpty);
        Assert.Equal("unsupported-schema-version:2", store.LoadError);
        Assert.NotNull(store.LastQuarantinedPath);
    }

    /// <summary>No file is a normal first run: empty, readable and writable.</summary>
    [Fact]
    public void MissingFile_IsAnEmptyWritableStore()
    {
        var store = new FileSourceSyncBaselineStore(_root);

        Assert.True(store.Load("device-a").IsEmpty);
        Assert.Null(store.LoadError);
        Assert.True(store.Save("device-a", Baseline("sync-1", 1)).Saved);
        Assert.True(File.Exists(store.FilePath));
    }

    /// <summary>A round that learned less may not move a stored entry backwards, and the file is not touched.</summary>
    [Fact]
    public void StaleRevision_IsRejectedWithoutRewritingTheFile()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 5));
        byte[] before = File.ReadAllBytes(store.FilePath);
        int writesBefore = store.WriteCount;

        SourceSyncBaselineSaveResult result = store.Save("device-a", Baseline("sync-1", 3, "older"));

        Assert.True(result.Unchanged);
        Assert.False(result.Saved);
        Assert.Equal(SourceSyncBaselineStoreReasons.StaleRevision, result.Reason);
        Assert.Equal("sync-1", Assert.Single(result.RejectedSyncSourceIds));
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Equal(before, File.ReadAllBytes(store.FilePath));
        Assert.Equal(5, Assert.Single(new FileSourceSyncBaselineStore(_root).Load("device-a").Entries).RecordRevision);
    }

    /// <summary>Same revision with different content is a conflict, not an update: the stored one is kept.</summary>
    [Fact]
    public void SameRevisionDifferentContent_IsRejected()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 5, "hash-a"));

        SourceSyncBaselineSaveResult result = store.Save("device-a", Baseline("sync-1", 5, "hash-b"));

        Assert.True(result.Unchanged);
        Assert.Equal(SourceSyncBaselineStoreReasons.UnresolvedConflict, result.Reason);
        Assert.Equal("hash-a", Assert.Single(store.Load("device-a").Entries).ContentHash);
    }

    /// <summary>Re-saving what is already stored is not a write: a repeated round leaves the file untouched.</summary>
    [Fact]
    public void UnchangedSaveDoesNotRewriteTheFile()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 5));
        byte[] before = File.ReadAllBytes(store.FilePath);
        int writesBefore = store.WriteCount;

        SourceSyncBaselineSaveResult result = store.Save("device-a", Baseline("sync-1", 5));

        Assert.True(result.Unchanged);
        Assert.Equal(writesBefore, store.WriteCount);
        Assert.Equal(before, File.ReadAllBytes(store.FilePath));
    }

    /// <summary>A newer revision replaces the entry and keeps the peers that the round did not mention.</summary>
    [Fact]
    public void HigherRevision_ReplacesOnlyThatEntry()
    {
        var store = new FileSourceSyncBaselineStore(_root);
        store.Save("device-a", Baseline("sync-1", 2));
        store.Save("device-b", Baseline("sync-2", 4));

        Assert.True(store.Save("device-a", Baseline("sync-1", 6)).Saved);

        var reopened = new FileSourceSyncBaselineStore(_root);
        Assert.Equal(6, Assert.Single(reopened.Load("device-a").Entries).RecordRevision);
        Assert.Equal("sync-2", Assert.Single(reopened.Load("device-b").Entries).SyncSourceId);
    }

    /// <summary>A store shared by threads still produces exactly one parseable document, with every peer in it.</summary>
    [Fact]
    public void ConcurrentSaves_ProduceOneParseableDocument()
    {
        var store = new FileSourceSyncBaselineStore(_root);

        Parallel.For(0, 12, index => store.Save("peer-" + index, Baseline("sync-" + index, index + 1)));

        Assert.Empty(TemporaryFiles(_root));
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(store.FilePath));
        Assert.Equal(12, document.RootElement.GetProperty("peers").GetArrayLength());

        var reopened = new FileSourceSyncBaselineStore(_root);
        for (int index = 0; index < 12; index++)
        {
            Assert.Equal("sync-" + index, Assert.Single(reopened.Load("peer-" + index).Entries).SyncSourceId);
        }
    }

    /// <summary>The in-memory store behaves the same way and counts only real writes, which tests rely on.</summary>
    [Fact]
    public void InMemoryStore_CountsWritesAndProtectsRevisions()
    {
        var store = new InMemorySourceSyncBaselineStore();
        Assert.Equal(0, store.WriteCount);

        Assert.True(store.Save("device-a", Baseline("sync-1", 5)).Saved);
        Assert.Equal(1, store.WriteCount);
        Assert.True(store.Save("device-a", Baseline("sync-1", 5)).Unchanged);
        Assert.Equal(1, store.WriteCount);

        SourceSyncBaselineSaveResult stale = store.Save("device-a", Baseline("sync-1", 4));
        Assert.Equal(SourceSyncBaselineStoreReasons.StaleRevision, stale.Reason);
        Assert.Equal(1, store.WriteCount);
        Assert.Equal(5, Assert.Single(store.Load("device-a").Entries).RecordRevision);
        Assert.Single(store.KnownPeerDeviceIds);
        Assert.True(store.Load("device-b").IsEmpty);
    }
}

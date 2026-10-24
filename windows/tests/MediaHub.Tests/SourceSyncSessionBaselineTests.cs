using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the round-trip that W6-9 exists for: a baseline that survives a restart, so the second round is still a
/// no-op, and a baseline that only learns what the round actually wrote.
/// </summary>
/// <remarks>
/// Two sessions over the same directory simulate an app restart: the first writes sources and a baseline, the
/// second is created from scratch and must still answer "up to date" without touching either file.
/// </remarks>
public sealed class SourceSyncSessionBaselineTests : IDisposable
{
    private const long FixedNowMs = 1_760_000_000_000L;
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(FixedNowMs);

    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        "mh-w6-baseline-" + Guid.NewGuid().ToString("N"));

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

    private static SourceRecord Record(
        string id,
        string wire = SyncSourceTypes.Emby,
        string displayName = "客厅 NAS",
        string url = "http://nas-a:8096/emby",
        long revision = 3,
        string? accountScope = "alice") => new()
    {
        SyncSourceId = id,
        SourceType = SyncSourceTypeValue.Parse(wire),
        DisplayName = displayName,
        AccountScope = accountScope,
        Endpoints = [new SourceEndpoint { Url = url, IsPrimary = true, SortOrder = 0 }],
        RecordRevision = revision,
    };

    private static SourceSyncEnvelope Envelope(
        string deviceId,
        SourceRecord[] sources,
        Tombstone[]? deletions = null) => new()
    {
        DeviceId = deviceId,
        SnapshotId = "snapshot-" + deviceId,
        CreatedAtEpochMs = FixedNowMs,
        Sources = sources,
        Deletions = deletions ?? [],
    };

    private SourceSyncSession Session(
        FileLocalSourceStore store,
        FileSourceSyncBaselineStore baseline)
        => new(store, "device-b", () => FixedNow, baseline);

    /// <summary>Restarting must not turn an incremental round back into a full re-proposal, and must not write.</summary>
    [Fact]
    public void Restart_KeepsTheSecondRoundIdempotent()
    {
        SourceSyncEnvelope envelope = Envelope("device-a", [Record("sync-1"), Record("sync-2", url: "http://nas-b:8096")]);

        var firstStore = new FileLocalSourceStore(_root);
        var firstBaseline = new FileSourceSyncBaselineStore(_root);
        ImportResult first = Session(firstStore, firstBaseline).ImportPlain(envelope);

        Assert.True(first.Succeeded);
        Assert.Equal(2, first.AppliedCount);
        Assert.True(first.BaselineUpdated);
        Assert.True(File.Exists(firstBaseline.FilePath));
        byte[] baselineBytes = File.ReadAllBytes(firstBaseline.FilePath);
        byte[] sourceBytes = File.ReadAllBytes(firstStore.FilePath);

        // Restart: brand new store instances over the same directory, exactly what the app does on launch.
        var secondStore = new FileLocalSourceStore(_root);
        var secondBaseline = new FileSourceSyncBaselineStore(_root);
        ImportResult second = Session(secondStore, secondBaseline).ImportPlain(envelope);

        Assert.True(second.Succeeded);
        Assert.Equal(0, second.AppliedCount);
        Assert.Equal(2, second.Skipped.Count);
        Assert.All(second.Skipped, entry => Assert.Equal(SourceSyncReasons.UpToDate, entry.Reason));
        Assert.False(second.BaselineUpdated);
        Assert.Equal(2, secondStore.GetAll().Count);
        Assert.Equal(baselineBytes, File.ReadAllBytes(firstBaseline.FilePath));
        Assert.Equal(sourceBytes, File.ReadAllBytes(secondStore.FilePath));
    }

    /// <summary>Two peers never share a baseline: B's round cannot make A look up to date.</summary>
    [Fact]
    public void BaselineIsKeyedByPeerDevice()
    {
        var store = new FileLocalSourceStore(_root);
        var baseline = new FileSourceSyncBaselineStore(_root);
        SourceSyncSession session = Session(store, baseline);

        Assert.Equal(1, session.ImportPlain(Envelope("device-a", [Record("sync-1")])).AppliedCount);
        Assert.Equal(1, session.ImportPlain(Envelope("device-c", [Record("sync-9", url: "http://nas-c:8096")])).AppliedCount);

        var reopened = new FileSourceSyncBaselineStore(_root);
        Assert.Equal("sync-1", Assert.Single(reopened.Load("device-a").Entries).SyncSourceId);
        Assert.Equal("sync-9", Assert.Single(reopened.Load("device-c").Entries).SyncSourceId);

        // And each peer round stays a no-op on its own turn.
        Assert.Equal(0, Session(new FileLocalSourceStore(_root), new FileSourceSyncBaselineStore(_root))
            .ImportPlain(Envelope("device-a", [Record("sync-1")])).AppliedCount);
    }

    /// <summary>The stored baseline names the peer it describes and the revision it saw, not this device's state.</summary>
    [Fact]
    public void BaselineDocument_RecordsThePeerIdentityAndRevision()
    {
        var baseline = new FileSourceSyncBaselineStore(_root);
        Session(new FileLocalSourceStore(_root), baseline).ImportPlain(Envelope("device-a", [Record("sync-1", revision: 7)]));

        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(baseline.FilePath));
        JsonElement peer = document.RootElement.GetProperty("peers")[0];
        Assert.Equal("device-a", peer.GetProperty("deviceId").GetString());
        JsonElement entry = peer.GetProperty("baseline").GetProperty("entries")[0];
        Assert.Equal("sync-1", entry.GetProperty("syncSourceId").GetString());
        Assert.Equal(7, entry.GetProperty("recordRevision").GetInt64());
    }

    /// <summary>A round that failed before writing anything must not advance the baseline.</summary>
    [Fact]
    public void FailedRound_DoesNotWriteABaseline()
    {
        byte[] archive = EncryptedArchiveWriter.Write(
            Envelope("device-a", [Record("sync-1")]),
            "correct horse battery staple");
        string directory = Path.Combine(_root, "b");
        var baseline = new FileSourceSyncBaselineStore(directory);
        var session = new SourceSyncSession(new FileLocalSourceStore(directory), "device-b", () => FixedNow, baseline);

        ImportResult result = session.Import(archive, "definitely not the passphrase");

        Assert.False(result.Succeeded);
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, result.FailureReason);
        Assert.True(baseline.Load("device-a").IsEmpty);
        Assert.False(File.Exists(baseline.FilePath));
    }

    /// <summary>A conflict writes nothing and therefore records nothing; the next round must still propose it.</summary>
    [Fact]
    public void Conflict_IsNotRecordedAsApplied()
    {
        var store = new FileLocalSourceStore(_root);
        store.Save(new LocalSourceRecord
        {
            ServerId = "local-1",
            SyncSourceId = "sync-1",
            Type = ServerType.Emby,
            Url = "http://nas-a:8096/emby",
            AccountScope = "alice",
            DisplayName = "旧名字",
            RecordRevision = 3,
        });
        var baseline = new FileSourceSyncBaselineStore(_root);

        ImportResult result = Session(store, baseline)
            .ImportPlain(Envelope("device-a", [Record("sync-1", displayName: "新名字")]));

        Assert.True(result.Succeeded);
        Assert.Equal(0, result.AppliedCount);
        Assert.Equal(SourceSyncReasons.SameRevisionDifferentContent, result.Find("sync-1")?.Reason);
        Assert.Equal("旧名字", Assert.Single(store.GetAll()).DisplayName);
        Assert.True(baseline.Load("device-a").IsEmpty);
        Assert.False(File.Exists(baseline.FilePath));
    }

    /// <summary>Without a store the session behaves exactly as before: nothing persisted, no exception.</summary>
    [Fact]
    public void WithoutAStore_NothingIsPersisted()
    {
        var session = new SourceSyncSession(new InMemoryLocalSourceStore(), "device-b", () => FixedNow);
        SourceSyncEnvelope envelope = Envelope("device-a", [Record("sync-1")]);

        Assert.Equal(1, session.ImportPlain(envelope).AppliedCount);
        Assert.Null(session.BaselineStore);
        Assert.Equal(0, session.ImportPlain(envelope).AppliedCount);
        Assert.False(File.Exists(Path.Combine(_root, FileSourceSyncBaselineStore.FileName)));
    }

    /// <summary>A tombstone is never applied, so it is never remembered as done; the peer keeps proposing it.</summary>
    [Fact]
    public void Tombstone_IsProposedButNotRecorded()
    {
        SourceSyncEnvelope envelope = Envelope(
            "device-a",
            [Record("sync-1")],
            [new Tombstone { SyncSourceId = "sync-ghost", RecordRevision = 2, DeletedAtEpochMs = FixedNowMs }]);
        var store = new FileLocalSourceStore(_root);
        var baseline = new FileSourceSyncBaselineStore(_root);
        SourceSyncSession session = Session(store, baseline);

        ImportResult first = session.ImportPlain(envelope);
        Assert.True(first.Succeeded);
        Assert.Equal(1, first.AppliedCount);
        SourceSyncBaseline stored = baseline.Load("device-a");
        SourceSyncBaselineEntry entry = Assert.Single(stored.Entries);
        Assert.Equal("sync-1", entry.SyncSourceId);

        ImportResult second = Session(new FileLocalSourceStore(_root), new FileSourceSyncBaselineStore(_root))
            .ImportPlain(envelope);
        Assert.Equal(0, second.AppliedCount);
        Assert.Equal(SourceSyncReasons.NoLocalSource, second.Find("sync-ghost")?.Reason);
    }

    /// <summary>An unsupported source type is reported every round; it is never recorded as applied.</summary>
    [Fact]
    public void UnsupportedType_IsNotRecorded()
    {
        SourceSyncEnvelope envelope = Envelope("device-a", [Record("sync-q", wire: "quantum-nas")]);
        var baseline = new FileSourceSyncBaselineStore(_root);
        SourceSyncSession session = Session(new FileLocalSourceStore(_root), baseline);

        ImportResult first = session.ImportPlain(envelope);

        Assert.True(first.Succeeded);
        Assert.Equal(0, first.AppliedCount);
        Assert.Equal(SourceSyncReasons.UnknownSourceType, first.Find("sync-q")?.Reason);
        Assert.True(baseline.Load("device-a").IsEmpty);
        Assert.False(File.Exists(baseline.FilePath));

        ImportResult second = Session(new FileLocalSourceStore(_root), new FileSourceSyncBaselineStore(_root))
            .ImportPlain(envelope);
        Assert.Equal(SourceSyncReasons.UnknownSourceType, second.Find("sync-q")?.Reason);
    }

    /// <summary>The baseline learns the applied record only — never the peer's credential block.</summary>
    [Fact]
    public void Baseline_RecordsAppliedRecordsOnly()
    {
        SourceSyncEnvelope envelope = Envelope(
            "device-a",
            [Record("sync-1"), Record("sync-q", wire: "quantum-nas")]);
        var baseline = new FileSourceSyncBaselineStore(_root);

        Session(new FileLocalSourceStore(_root), baseline).ImportPlain(envelope);

        SourceSyncBaseline stored = baseline.Load("device-a");
        SourceSyncBaselineEntry entry = Assert.Single(stored.Entries);
        Assert.Equal("sync-1", entry.SyncSourceId);
        Assert.DoesNotContain("quantum-nas", File.ReadAllText(baseline.FilePath), StringComparison.Ordinal);
    }
}

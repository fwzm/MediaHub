using System.Text;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the end-to-end offline round trip: device A store to envelope to archive to device B store and back.
/// Every test drives the real layers (bridge, planner, codec, archive) and asserts the invariants that make the
/// round trip safe: no secrets leave, a conflict writes nothing, a repeat import is a no-op, and no file appears
/// that the orchestrator did not have to write.
/// </summary>
public sealed class SourceSyncSessionTests
{
    private const string Passphrase = "correct horse battery staple";
    private const long FixedNowMs = 1_760_000_000_000L;

    [Fact]
    public void Export_PlainEnvelopeCarriesNoSecrets()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        ExportSession export = session.Export();

        Assert.False(export.IsEncrypted);
        Assert.NotEmpty(export.EnvelopeJson);
        Assert.False(export.Envelope.Capabilities.CanSendSecrets);
        Assert.False(export.EnvelopeJson.Contains("\"secrets\"", StringComparison.OrdinalIgnoreCase));
        SourceRecord record = Assert.Single(export.Envelope.Sources);
        Assert.Null(record.Secrets);
        Assert.Equal("emby", record.SourceType.Raw);
        Assert.Equal(new[] { "emby" }, export.Envelope.Capabilities.SourceTypes);
    }

    [Fact]
    public void Export_WithPassphraseProducesArchiveInsteadOfJson()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        ExportSession export = session.Export(passphrase: Passphrase);

        Assert.True(export.IsEncrypted);
        Assert.Empty(export.EnvelopeJson);
        Assert.True(export.Archive.Length > 0);
        Assert.Equal("MHSYNCA1", Encoding.ASCII.GetString(export.Archive, 0, 8));
    }

    [Fact]
    public void Export_AskingForSecretsIsRefusedLoudly()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        SyncContractException error = Assert.Throws<SyncContractException>(() => session.Export(includeSecrets: true));

        Assert.Equal(SourceSyncSessionReasons.SecretsNotSupported, error.Reason);
    }

    [Fact]
    public void Export_WarnsAboutASourceItCannotSend()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record(url: "user@host:8096"));
        SourceSyncSession session = Session(store);

        ExportSession export = session.Export();

        Assert.Empty(export.Envelope.Sources);
        Assert.Contains("source-skipped:a1:no-usable-endpoint", export.Warnings);
        Assert.Contains(SourceSyncSessionReasons.EmptyEnvelope, export.Warnings);
    }

    [Fact]
    public void Export_AssignsOneStableIdentityAndStopsWarning()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        ExportSession first = session.Export();
        int writesAfterFirst = store.WriteCount;
        ExportSession second = session.Export();

        string identity = Assert.Single(first.Envelope.Sources).SyncSourceId;
        Assert.StartsWith("local-", identity);
        Assert.Equal(16, identity.Length - "local-".Length);
        Assert.Equal(identity, Assert.Single(second.Envelope.Sources).SyncSourceId);
        Assert.Empty(second.Warnings);
        Assert.Equal(writesAfterFirst, store.WriteCount);
    }

    [Fact]
    public void Export_StampsTheInjectedClockAndADistinctSnapshotId()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        ExportSession first = session.Export();
        ExportSession second = session.Export();

        Assert.Equal(FixedNowMs, first.Envelope.CreatedAtEpochMs);
        Assert.Equal("device-a", first.Envelope.DeviceId);
        Assert.NotEqual(first.Envelope.SnapshotId, second.Envelope.SnapshotId);
    }

    [Fact]
    public void Export_PassesCallerTombstonesThrough()
    {
        SourceSyncSession session = Session(new InMemoryLocalSourceStore());

        ExportSession export = session.Export(deletions:
            [new Tombstone { SyncSourceId = "local-0123456789abcdef", RecordRevision = 3 }]);

        Tombstone tombstone = Assert.Single(export.Envelope.Deletions);
        Assert.Equal(3, tombstone.RecordRevision);
        Assert.DoesNotContain(SourceSyncSessionReasons.EmptyEnvelope, export.Warnings);
    }

    [Fact]
    public void Import_PlainCreatesTheSourceOnTheOtherDevice()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "客厅 NAS"));
        ExportSession export = Session(a, "device-a").Export();
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(export.EnvelopeJson);
        var b = new InMemoryLocalSourceStore();

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        Assert.True(result.Succeeded);
        Assert.Null(result.FailureReason);
        SourceSyncImportEntry applied = Assert.Single(result.Applied);
        Assert.Equal(SourceSyncAction.Create, applied.Action);
        Assert.Empty(result.Skipped);
        Assert.Empty(result.RequiresApproval);
        LocalSourceRecord stored = Assert.Single(b.GetAll());
        Assert.Equal("客厅 NAS", stored.DisplayName);
        Assert.Equal(ServerType.Emby, stored.Type);
        Assert.Equal("http://host:8096", stored.Url);
        Assert.Equal(1, stored.RecordRevision);
    }

    [Fact]
    public void Import_EncryptedArchiveRoundTrips()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "客厅 NAS"));
        ExportSession export = Session(a, "device-a").Export(passphrase: Passphrase);
        var b = new InMemoryLocalSourceStore();

        ImportResult result = Session(b, "device-b").Import(export.Archive, Passphrase);

        Assert.True(result.Succeeded);
        Assert.Single(result.Applied);
        Assert.Equal("客厅 NAS", Assert.Single(b.GetAll()).DisplayName);
    }

    [Fact]
    public void Import_WrongPassphraseFailsAndWritesNothing()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record());
        byte[] archive = Session(a, "device-a").Export(passphrase: Passphrase).Archive;
        var b = new InMemoryLocalSourceStore();
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").Import(archive, "not the right passphrase");

        Assert.False(result.Succeeded);
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, result.FailureReason);
        Assert.Empty(result.Applied);
        Assert.Empty(result.RequiresApproval);
        Assert.Empty(b.GetAll());
        Assert.Equal(writesBefore, b.WriteCount);
    }

    [Fact]
    public void Import_NonArchiveBytesAreRejected()
    {
        var b = new InMemoryLocalSourceStore();
        ImportResult result = Session(b, "device-b").Import([1, 2, 3, 4], Passphrase);

        Assert.False(result.Succeeded);
        Assert.Equal(ArchiveError.MagicMismatch, result.FailureReason);
        Assert.Empty(b.GetAll());
    }

    [Fact]
    public void Import_SecondRoundIsAnIdempotentNoOp()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record());
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession session = Session(b, "device-b");
        Assert.Single(session.ImportPlain(inbound).Applied);
        int writesAfterFirst = b.WriteCount;

        ImportResult second = session.ImportPlain(inbound);

        Assert.True(second.Succeeded);
        Assert.Empty(second.Applied);
        Assert.Empty(second.RequiresApproval);
        SourceSyncImportEntry skipped = Assert.Single(second.Skipped);
        Assert.Equal(SourceSyncReasons.UpToDate, skipped.Reason);
        Assert.Equal(writesAfterFirst, b.WriteCount);
        Assert.Equal(1, Assert.Single(b.GetAll()).RecordRevision);
    }

    [Fact]
    public void Import_HigherRevisionWinsAndKeepsTheLocalServerId()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncSession sessionA = Session(a, "device-a");
        SourceSyncEnvelope first = SourceSyncCodec.Deserialize(sessionA.Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(first);
        string serverIdOnB = Assert.Single(b.GetAll()).ServerId;

        a.Save(Assert.Single(a.GetAll()) with { DisplayName = "Home NAS 2", RecordRevision = 2 });
        SourceSyncEnvelope second = SourceSyncCodec.Deserialize(sessionA.Export().EnvelopeJson);
        ImportResult result = sessionB.ImportPlain(second);

        Assert.Single(result.Applied);
        LocalSourceRecord stored = Assert.Single(b.GetAll());
        Assert.Equal(serverIdOnB, stored.ServerId);
        Assert.Equal("Home NAS 2", stored.DisplayName);
        Assert.Equal(2, stored.RecordRevision);
    }

    [Fact]
    public void Import_SameRevisionDifferentContentNeedsApprovalAndWritesNothing()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncSession sessionA = Session(a, "device-a");
        SourceSyncEnvelope first = SourceSyncCodec.Deserialize(sessionA.Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(first);

        a.Save(Assert.Single(a.GetAll()) with { DisplayName = "A renamed", RecordRevision = 2 });
        b.Save(Assert.Single(b.GetAll()) with { DisplayName = "B renamed", RecordRevision = 2 });
        SourceSyncEnvelope second = SourceSyncCodec.Deserialize(sessionA.Export().EnvelopeJson);
        int writesBefore = b.WriteCount;

        ImportResult result = sessionB.ImportPlain(second);

        Assert.Empty(result.Applied);
        SourceSyncImportEntry conflict = Assert.Single(result.RequiresApproval);
        Assert.Equal(SourceSyncReasons.SameRevisionDifferentContent, conflict.Reason);
        Assert.True(conflict.RequiresUserApproval);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Equal("B renamed", Assert.Single(b.GetAll()).DisplayName);
    }

    [Fact]
    public void Import_AmbiguousLegacyIdentityNeedsApprovalAndWritesNothing()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        b.Save(Record(serverId: "b1"));
        b.Save(Record(serverId: "b2"));
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        Assert.Empty(result.Applied);
        SourceSyncImportEntry conflict = Assert.Single(result.RequiresApproval);
        Assert.Equal(SourceSyncReasons.AmbiguousLegacyIdentity, conflict.Reason);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Equal(2, b.GetAll().Count);
    }

    [Fact]
    public void Import_UniqueLegacyIdentityIsProposedNotSilentlyAdopted()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        b.Save(Record(serverId: "b1"));
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        Assert.Empty(result.Applied);
        Assert.Equal(SourceSyncReasons.LegacyIdentityMatch, Assert.Single(result.RequiresApproval).Reason);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Null(Assert.Single(b.GetAll()).SyncSourceId);
    }

    [Fact]
    public void Import_TombstoneIsAProposalAndNeverADelete()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record());
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson));
        string identity = Assert.Single(b.GetAll()).SyncSourceId!;
        int writesBefore = b.WriteCount;
        var envelope = new SourceSyncEnvelope
        {
            DeviceId = "device-a",
            Sources = [],
            Deletions = [new Tombstone { SyncSourceId = identity, RecordRevision = 5, DeletedAtEpochMs = 42 }],
        };

        ImportResult result = sessionB.ImportPlain(envelope);

        Assert.Empty(result.Applied);
        Assert.Equal(SourceSyncReasons.Tombstone, Assert.Single(result.RequiresApproval).Reason);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Single(b.GetAll());
    }

    [Fact]
    public void Import_UnknownSourceTypeIsKeptOnExportAndRefusedOnImport()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(serverId: "nas1", type: ServerType.Unknown, rawType: "Quantum-NAS", url: "http://nas:5000", displayName: "Quantum"));
        SourceSyncSession sessionA = Session(a, "device-a");
        ExportSession export = sessionA.Export();
        SourceRecord exported = Assert.Single(export.Envelope.Sources);
        Assert.Equal("Quantum-NAS", exported.SourceType.Raw);
        Assert.Equal("quantum-nas", exported.SourceType.Wire);
        Assert.False(exported.SourceType.IsKnown);
        var b = new InMemoryLocalSourceStore();
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").ImportPlain(SourceSyncCodec.Deserialize(export.EnvelopeJson));

        Assert.Empty(result.Applied);
        Assert.Equal(SourceSyncReasons.UnknownSourceType, Assert.Single(result.Skipped).Reason);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Empty(b.GetAll());
        Assert.Equal("Quantum-NAS", Assert.Single(a.GetAll()).SourceTypeRaw);
    }

    [Fact]
    public void Import_KnownButUnsupportedTypeIsReportedNotWritten()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(serverId: "cloud1", type: ServerType.Unknown, rawType: "quark-drive", url: "https://pan.quark.cn", displayName: "Quark"));
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        Assert.Empty(result.Applied);
        Assert.Equal(SourceSyncReasons.NotSupportedOnWindows, Assert.Single(result.Skipped).Reason);
        Assert.Equal(writesBefore, b.WriteCount);
    }

    [Fact]
    public void Import_EmptyEnvelopeHasNoSideEffects()
    {
        var b = new InMemoryLocalSourceStore();
        int writesBefore = b.WriteCount;

        ImportResult result = Session(b, "device-b").ImportPlain(new SourceSyncEnvelope { DeviceId = "device-a" });

        Assert.True(result.Succeeded);
        Assert.True(result.IsEmpty);
        Assert.False(result.HasWork);
        Assert.Empty(b.GetAll());
        Assert.Equal(writesBefore, b.WriteCount);
    }

    [Fact]
    public void Import_DoesNotWriteTheRemoteDeviceIdOrTimestamp()
    {
        using var temp = new TempDirectory();
        var a = new InMemoryLocalSourceStore();
        a.Save(Record());
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new FileLocalSourceStore(temp.DirectoryPath);

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        Assert.Single(result.Applied);
        string text = File.ReadAllText(b.FilePath);
        Assert.False(text.Contains("device-a", StringComparison.Ordinal));
        Assert.False(text.Contains("device-b", StringComparison.Ordinal));
        Assert.False(text.Contains(inbound.CreatedAtEpochMs.ToString(System.Globalization.CultureInfo.InvariantCulture), StringComparison.Ordinal));
    }

    [Fact]
    public void Import_LeavesNoArchiveOnDisk()
    {
        using var aTemp = new TempDirectory();
        using var bTemp = new TempDirectory();
        var a = new FileLocalSourceStore(aTemp.DirectoryPath);
        a.Save(Record());
        ExportSession export = Session(a, "device-a").Export(passphrase: Passphrase);
        string[] filesAfterExport = Directory.GetFiles(aTemp.DirectoryPath);
        Assert.Single(filesAfterExport);
        Assert.EndsWith(FileLocalSourceStore.FileName, filesAfterExport[0], StringComparison.Ordinal);
        var b = new FileLocalSourceStore(bTemp.DirectoryPath);

        ImportResult result = Session(b, "device-b").Import(export.Archive, Passphrase);

        Assert.Single(result.Applied);
        string[] filesAfterImport = Directory.GetFiles(bTemp.DirectoryPath);
        Assert.Single(filesAfterImport);
        Assert.EndsWith(FileLocalSourceStore.FileName, filesAfterImport[0], StringComparison.Ordinal);
        Assert.False(File.ReadAllText(filesAfterImport[0]).Contains("MHSYNCA1", StringComparison.Ordinal));
    }

    [Fact]
    public void Import_OnAFileStoreSurvivesAReload()
    {
        using var aTemp = new TempDirectory();
        using var bTemp = new TempDirectory();
        var a = new FileLocalSourceStore(aTemp.DirectoryPath);
        a.Save(Record(displayName: "客厅 NAS"));
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new FileLocalSourceStore(bTemp.DirectoryPath);
        Session(b, "device-b").ImportPlain(inbound);

        var reloaded = new FileLocalSourceStore(bTemp.DirectoryPath);

        Assert.Null(reloaded.LoadError);
        LocalSourceRecord stored = Assert.Single(reloaded.GetAll());
        Assert.Equal("客厅 NAS", stored.DisplayName);
        Assert.Equal("http://host:8096", stored.Url);
        Assert.NotNull(stored.SyncSourceId);
    }

    [Fact]
    public void Import_ADifferentAccountIsANewSourceNotAMerge()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(accountScope: "alice"));
        SourceSyncEnvelope inbound = SourceSyncCodec.Deserialize(Session(a, "device-a").Export().EnvelopeJson);
        var b = new InMemoryLocalSourceStore();
        b.Save(Record(serverId: "b1", accountScope: "bob"));

        ImportResult result = Session(b, "device-b").ImportPlain(inbound);

        SourceSyncImportEntry applied = Assert.Single(result.Applied);
        Assert.Equal(SourceSyncAction.Create, applied.Action);
        Assert.Equal(2, b.GetAll().Count);
    }

    [Fact]
    public void Import_WithBaselineWithholdsAFieldConflict()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncSession sessionA = Session(a, "device-a");
        SourceSyncEnvelope first = sessionA.Export().Envelope;
        SourceSyncBaseline baseline = SourceSyncBaseline.FromEnvelope(first);
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(first);

        a.Save(Assert.Single(a.GetAll()) with { DisplayName = "A renamed", RecordRevision = 2 });
        b.Save(Assert.Single(b.GetAll()) with { DisplayName = "B renamed", RecordRevision = 2 });
        SourceSyncEnvelope second = sessionA.Export().Envelope;
        int writesBefore = b.WriteCount;

        ImportResult result = sessionB.ImportPlain(second, baseline);

        Assert.Empty(result.Applied);
        SourceSyncImportEntry conflict = Assert.Single(result.RequiresApproval);
        Assert.Equal(SyncRoundReasons.FieldConflict, conflict.Reason);
        Assert.True(conflict.RequiresUserApproval);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Equal("B renamed", Assert.Single(b.GetAll()).DisplayName);
    }

    [Fact]
    public void PlanRound_ReportsTheRemoteChangeWithoutWriting()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(displayName: "Home NAS"));
        SourceSyncSession sessionA = Session(a, "device-a");
        SourceSyncEnvelope first = sessionA.Export().Envelope;
        SourceSyncBaseline baseline = SourceSyncBaseline.FromEnvelope(first);
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(first);
        a.Save(Assert.Single(a.GetAll()) with { DisplayName = "Home NAS 2", RecordRevision = 2 });
        SourceSyncEnvelope second = sessionA.Export().Envelope;
        int writesBefore = b.WriteCount;

        SyncRoundResult round = sessionB.PlanRound(second, baseline);

        SyncRoundEntry change = Assert.Single(round.Apply);
        Assert.Equal(SyncRoundReasons.ApplyRemoteChange, change.Reason);
        Assert.Equal(SyncMergeDirection.Pull, change.Direction);
        Assert.Empty(round.RequiresApproval);
        Assert.Equal(writesBefore, b.WriteCount);
        Assert.Equal("Home NAS", Assert.Single(b.GetAll()).DisplayName);
    }

    [Fact]
    public void RoundTrip_AtoBtoA_KeepsBothSidesOwnSources()
    {
        var a = new InMemoryLocalSourceStore();
        a.Save(Record(serverId: "a1", displayName: "A source"));
        SourceSyncSession sessionA = Session(a, "device-a");
        var b = new InMemoryLocalSourceStore();
        SourceSyncSession sessionB = Session(b, "device-b");
        sessionB.ImportPlain(SourceSyncCodec.Deserialize(sessionA.Export().EnvelopeJson));
        b.Save(Record(serverId: "b1", url: "http://nas-b:8096", displayName: "B source"));

        ImportResult back = sessionA.ImportPlain(SourceSyncCodec.Deserialize(sessionB.Export().EnvelopeJson));

        SourceSyncImportEntry applied = Assert.Single(back.Applied);
        Assert.Equal(SourceSyncAction.Create, applied.Action);
        Assert.Equal(2, a.GetAll().Count);
        LocalSourceRecord own = Assert.Single(a.GetAll(), record => record.ServerId == "a1");
        Assert.Equal("A source", own.DisplayName);
        Assert.Equal(1, own.RecordRevision);
        LocalSourceRecord arrived = Assert.Single(a.GetAll(), record => record.DisplayName == "B source");
        Assert.Equal("http://nas-b:8096", arrived.Url);
    }

    [Fact]
    public void Session_RejectsMissingInputs()
    {
        var store = new InMemoryLocalSourceStore();
        SourceSyncSession session = Session(store);

        Assert.Throws<ArgumentNullException>("archive", () => session.Import(null!, Passphrase));
        Assert.Throws<ArgumentNullException>("remote", () => session.ImportPlain(null!));
        Assert.Throws<ArgumentNullException>("store", () => new SourceSyncSession(null!, "device-a"));
        Assert.Throws<ArgumentException>("deviceId", () => new SourceSyncSession(store, "  "));
    }

    private static SourceSyncSession Session(ILocalSourceStore store, string deviceId = "device-a") =>
        new(store, deviceId, () => DateTimeOffset.FromUnixTimeMilliseconds(FixedNowMs));

    private static LocalSourceRecord Record(
        string serverId = "a1",
        string? url = "http://host:8096",
        ServerType type = ServerType.Emby,
        string displayName = "Home NAS",
        string? accountScope = null,
        long revision = 1,
        string? rawType = null) => new()
        {
            ServerId = serverId,
            Type = type,
            SourceTypeRaw = rawType,
            Url = url,
            DisplayName = displayName,
            AccountScope = accountScope,
            RecordRevision = revision,
        };

    private sealed class TempDirectory : IDisposable
    {
        public TempDirectory()
        {
            DirectoryPath = Path.Combine(Path.GetTempPath(), "mh-sync-session-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(DirectoryPath);
        }

        public string DirectoryPath { get; }

        public void Dispose()
        {
            try
            {
                if (Directory.Exists(DirectoryPath))
                {
                    Directory.Delete(DirectoryPath, recursive: true);
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
}
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The one place both the app and these tests turn a peer envelope into local writes: plan, gate, write,
/// advance the stored baseline. The cases below pin the property the app depends on — importing the same
/// envelope twice writes nothing the second time, even across a restart — and the wording the page shows for
/// addresses this client cannot store.
/// </summary>
public sealed class SourceSyncImportRoundTests
{
    private const long FixedNow = 1_700_000_000_000;

    [Fact]
    public void PreviewOfAnEmptyEnvelopeOffersNothing()
    {
        WithTempDirectory(dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);

            SourceSyncApplyPreview preview = round.Preview(Envelope());

            Assert.Empty(preview.Items);
            Assert.False(preview.CanApply);
            Assert.True(preview.IsFullSnapshot);
            Assert.Equal(0, preview.Total);
            Assert.Empty(preview.NotImportedEndpoints);
        });
    }

    [Fact]
    public async Task FirstApplyWritesEveryCreateAndAdvancesTheBaseline()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"));

            SourceSyncApplyPreview preview = round.Preview(peer);
            Assert.Equal(SourceSyncAction.Create, Assert.Single(preview.Items).Action);
            Assert.True(preview.CanApply);

            var seen = new List<SourceRecord?>();
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-aaaa"], Writer(store, seen));

            Assert.Equal(1, result.Applied);
            Assert.Equal(0, result.Skipped);
            Assert.Equal(0, result.Withheld);
            Assert.True(result.BaselineUpdated);
            Assert.Equal(1, result.BaselineEntryCount);
            Assert.True(File.Exists(baselines.FilePath));
            Assert.Equal(1, baselines.WriteCount);
            Assert.Equal("NAS", Assert.Single(store.GetAll()).DisplayName);
            Assert.Single(seen);
        });
    }

    [Fact]
    public async Task SecondApplyOfTheSameEnvelopeWritesNothing()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "smb", "smb://share.local/media", "Share"));

            SourceSyncApplyReport first = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa", "local-bbbb"],
                Writer(store));

            Assert.Equal(2, first.Applied);
            int writesAfterFirst = baselines.WriteCount;

            SourceSyncApplyPreview second = round.Preview(peer);

            Assert.False(second.CanApply);
            Assert.Equal(2, second.Total);
            Assert.All(second.Items, item => Assert.Equal(SourceSyncReasons.UpToDate, item.Reason));
            Assert.True(second.BaselineKnown);
            Assert.Equal(2, second.BaselineEntryCount);

            SourceSyncApplyReport again = await round.ApplyAsync(second, ["local-aaaa", "local-bbbb"], Writer(store));

            Assert.Equal(0, again.Applied);
            Assert.Equal(2, again.Skipped);
            Assert.Equal(0, again.NotApplied - again.Skipped);
            Assert.False(again.BaselineUpdated);
            Assert.Equal(writesAfterFirst, baselines.WriteCount);
            Assert.Equal(2, store.GetAll().Count);
        });
    }

    [Fact]
    public async Task SecondApplyAfterARestartStillWritesNothing()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "emby", "http://emby.local:8096", "Emby"));

            Assert.Equal(2, (await round.ApplyAsync(round.Preview(peer), ["local-aaaa", "local-bbbb"], Writer(store))).Applied);

            // Everything the app keeps about a source lives in files: the mirrored rows and the baseline.
            // A restart re-reads both, so the round must be constructed from scratch here.
            var restartedSources = new InMemoryLocalSourceStore();
            foreach (LocalSourceRecord row in store.GetAll())
            {
                restartedSources.Save(row);
            }

            var restartedBaselines = new FileSourceSyncBaselineStore(dir);
            var restartedRound = new SourceSyncImportRound(restartedSources, restartedBaselines, () => restartedBaselines.LoadError);

            SourceSyncApplyPreview preview = restartedRound.Preview(peer);

            Assert.True(preview.BaselineKnown);
            Assert.Equal(2, preview.BaselineEntryCount);
            Assert.False(preview.CanApply);
            Assert.Equal(0, restartedBaselines.WriteCount);

            SourceSyncApplyReport result = await restartedRound.ApplyAsync(preview, ["local-aaaa", "local-bbbb"], Writer(restartedSources));

            Assert.Equal(0, result.Applied);
            Assert.Equal(2, restartedSources.GetAll().Count);
        });
    }

    [Fact]
    public async Task MultiEndpointEnvelopeImportsThePrimaryAndReportsTheRest()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            var record = new SourceRecord
            {
                SyncSourceId = "local-cccc",
                SourceType = SyncSourceTypeValue.Parse("webdav"),
                DisplayName = "NAS",
                Endpoints =
                [
                    new SourceEndpoint { Url = "http://nas.local:5005", IsPrimary = true, SortOrder = 1 },
                    new SourceEndpoint { Url = "http://nas.lan:5005", IsPrimary = false, SortOrder = 2 },
                    new SourceEndpoint { Url = "smb://nas.local/media", IsPrimary = false, SortOrder = 3 },
                ],
                RecordRevision = 7,
            };

            SourceSyncApplyPreview preview = round.Preview(Envelope(record));

            Assert.Equal(2, preview.NotImportedEndpoints.Count);
            Assert.All(preview.NotImportedEndpoints,
                endpoint => Assert.Equal(SourceSyncEndpointPolicy.SecondaryNotImported, endpoint.Reason));
            Assert.Equal(2, preview.EndpointSummary.NotImportedCount);
            Assert.Contains(preview.NotImportedEndpoints, endpoint => endpoint.Url == "http://nas.lan:5005");

            await round.ApplyAsync(preview, ["local-cccc"], Writer(store));

            LocalSourceRecord stored = Assert.Single(store.GetAll());
            Assert.Equal("http://nas.local:5005", stored.Url);
        });
    }

    [Fact]
    public async Task DamagedBaselineDegradesToAFullPlanWithoutCrashing()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            Directory.CreateDirectory(dir);
            await File.WriteAllTextAsync(Path.Combine(dir, FileSourceSyncBaselineStore.FileName), "{ this is not a baseline }");

            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);

            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));

            Assert.NotNull(preview.BaselineError);
            Assert.False(preview.BaselineKnown);
            Assert.True(preview.IsFullSnapshot);
            Assert.Equal(0, preview.BaselineEntryCount);
            Assert.Equal(SourceSyncAction.Create, Assert.Single(preview.Items).Action);

            // The damaged file is moved aside, so the round can still record a fresh baseline.
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-aaaa"], Writer(store));

            Assert.Equal(1, result.Applied);
            Assert.True(result.BaselineUpdated);
            Assert.NotEmpty(baselines.LastQuarantinedPath ?? string.Empty);
        });
    }

    [Fact]
    public void NotImportedSummaryNamesTheReasonCode()
    {
        var summary = SourceSyncEndpointPolicy.Summarize(
            Envelope(new SourceRecord
            {
                SyncSourceId = "local-cccc",
                SourceType = SyncSourceTypeValue.Parse("webdav"),
                DisplayName = "NAS",
                Endpoints =
                [
                    new SourceEndpoint { Url = "http://nas.local:5005", IsPrimary = true, SortOrder = 1 },
                    new SourceEndpoint { Url = "http://nas.lan:5005", IsPrimary = false, SortOrder = 2 },
                ],
                RecordRevision = 7,
            }));

        string sentence = SourceSyncNotImportedText.Summarize(3, summary);

        Assert.Contains("3 个来源已导入", sentence);
        Assert.Contains("1 个次要地址未导入", sentence);
        Assert.Contains(SourceSyncEndpointPolicy.SecondaryNotImported, sentence);

        string row = SourceSyncNotImportedText.Describe(Assert.Single(summary.Items));

        Assert.Contains("NAS", row);
        Assert.Contains("http://nas.lan:5005", row);
        Assert.Contains(SourceSyncEndpointPolicy.SecondaryNotImported, row);
        Assert.Equal("没有未导入的次要地址。", SourceSyncNotImportedText.DescribeCount(new SourceSyncEndpointSummary()));
    }

    [Fact]
    public async Task SecretsAreWithheldEvenWhenTheUserAllowsThem()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            using JsonDocument document = JsonDocument.Parse("""{"token":"sealed-on-the-other-machine"}""");
            var record = new SourceRecord
            {
                SyncSourceId = "local-dddd",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = "Emby",
                Endpoints = [new SourceEndpoint { Url = "http://emby.local:8096", IsPrimary = true }],
                RecordRevision = 2,
                Secrets = document.RootElement.Clone(),
            };

            SourceSyncApplyPreview preview = round.Preview(Envelope(record));

            Assert.Equal(1, preview.SecretCount);
            SourceSyncApprovalItem item = Assert.Single(preview.Items);
            Assert.Equal(SyncRoundReasons.SecretsRequireApproval, item.Reason);
            Assert.True(item.RequiresApproval);
            Assert.False(preview.CanApply);
            Assert.True(preview.Gate.IsBlocked);

            var seen = new List<SourceRecord?>();
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-dddd"], Writer(store, seen));

            Assert.Equal(0, result.Applied);
            Assert.Equal(1, result.Withheld);
            Assert.Contains(SyncRoundReasons.SecretsRequireApproval, result.BlockingReasons);
            Assert.Empty(seen);
            Assert.Empty(store.GetAll());
        });
    }

    [Fact]
    public async Task AWriterThatRefusesLeavesTheBaselineAlone()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-eeee", "webdav", "http://nas.local:5005", "NAS")));

            SourceSyncApplyReport result = await round.ApplyAsync(
                preview,
                ["local-eeee"],
                (_, _) => Task.FromResult(SourceSyncWriteOutcome.Refused("该来源已被其他窗口占用")));

            Assert.Equal(0, result.Applied);
            Assert.Equal(1, result.Withheld);
            Assert.False(result.BaselineUpdated);
            Assert.Equal(0, baselines.WriteCount);
            Assert.Empty(store.GetAll());
        });
    }

    [Fact]
    public async Task DeletionsReachTheWriterAsANullRecord()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            store.Save(new LocalSourceRecord
            {
                ServerId = "webdav:nas",
                SyncSourceId = "local-ffff",
                Type = ServerType.WebDav,
                Url = "http://nas.local:5005",
                DisplayName = "NAS",
                RecordRevision = 4,
            });

            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(
                [],
                [new Tombstone { SyncSourceId = "local-ffff", DeletedAtEpochMs = FixedNow, RecordRevision = 5 }]);

            SourceSyncApplyPreview preview = round.Preview(peer);
            SourceSyncApprovalItem item = Assert.Single(preview.Items);
            Assert.Equal(SourceSyncAction.Delete, item.Action);
            Assert.True(item.RequiresApproval);

            var seen = new List<SourceRecord?>();
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-ffff"], Writer(store, seen));

            Assert.Equal(1, result.Applied);
            Assert.Single(seen);
            Assert.Null(seen[0]);
            Assert.Empty(store.GetAll());

            // A deletion removes a record, so there is nothing to store in the baseline for it.
            Assert.False(result.BaselineUpdated);
        });
    }

    [Fact]
    public async Task NoAllowedIdentitiesMeansNoWrites()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            // A new source is written by default, so the only way to prove that nothing happens without the
            // user's word is an entry the gate keeps as a proposal: here, the peer deleting a source.
            var store = new InMemoryLocalSourceStore();
            store.Save(new LocalSourceRecord
            {
                ServerId = "webdav:nas",
                SyncSourceId = "local-ffff",
                Type = ServerType.WebDav,
                Url = "http://nas.local:5005",
                DisplayName = "NAS",
                RecordRevision = 4,
            });

            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(
                [],
                [new Tombstone { SyncSourceId = "local-ffff", DeletedAtEpochMs = FixedNow, RecordRevision = 5 }]));

            Assert.True(Assert.Single(preview.Items).RequiresApproval);
            Assert.False(preview.CanApply);

            SourceSyncApplyReport result = await round.ApplyAsync(preview, [], Writer(store));

            Assert.Equal(0, result.Applied);
            Assert.Equal(1, result.Skipped);
            Assert.Equal(0, result.Withheld);
            Assert.Equal(1, result.NotApplied);
            Assert.Single(store.GetAll());
            Assert.False(result.BaselineUpdated);
        });
    }

    [Fact]
    public void PreviewIsIdempotentAndNeverWrites()
    {
        WithTempDirectory(dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-zzzz", "fn-nas", "http://fn.local:9000", "FN"));

            SourceSyncApplyPreview first = round.Preview(peer);
            SourceSyncApplyPreview second = round.Preview(peer);

            Assert.Equal(
                first.Items.Select(item => item.SyncSourceId + "|" + item.Action + "|" + item.Reason),
                second.Items.Select(item => item.SyncSourceId + "|" + item.Action + "|" + item.Reason));
            Assert.Empty(store.GetAll());
            Assert.Equal(0, baselines.WriteCount);
            Assert.False(File.Exists(baselines.FilePath));
            Assert.Equal(SourceSyncAction.Unsupported, Assert.Single(first.Items, item => item.SyncSourceId == "local-zzzz").Action);
        });
    }

    [Fact]
    public async Task CountsAreDisjointAndAddUpToThePreviewedEntries()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            using JsonDocument document = JsonDocument.Parse("""{"token":"sealed"}""");
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            var withSecrets = new SourceRecord
            {
                SyncSourceId = "local-secret",
                SourceType = SyncSourceTypeValue.Parse("webdav"),
                DisplayName = "Secret",
                Endpoints = [new SourceEndpoint { Url = "http://secret.local:5005", IsPrimary = true }],
                RecordRevision = 1,
                Secrets = document.RootElement.Clone(),
            };
            SourceSyncApplyPreview preview = round.Preview(Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "webdav", "http://nas2.local:5005", "NAS 2"),
                withSecrets));

            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-aaaa", "local-bbbb", "local-secret"], Writer(store));

            Assert.Equal(2, result.Applied);
            Assert.Equal(0, result.Skipped);
            Assert.Equal(1, result.Withheld);
            Assert.Equal(1, result.NotApplied);
            Assert.Equal(preview.Total, result.Applied + result.Skipped + result.Withheld);
            Assert.Equal(result.Skipped + result.Withheld, result.NotApplied);
            Assert.Equal(2, result.BaselineEntryCount);
            Assert.Contains("已写入本地 2 条", result.Summary);
            Assert.Contains("保留未应用 1 条", result.Summary);
        });
    }

    [Fact]
    public async Task ApplyResultExplainsWhetherTheBaselineMoved()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"));

            SourceSyncApplyReport first = await round.ApplyAsync(round.Preview(peer), ["local-aaaa"], Writer(store));
            SourceSyncApplyReport second = await round.ApplyAsync(round.Preview(peer), ["local-aaaa"], Writer(store));

            Assert.True(first.BaselineUpdated);
            Assert.Contains("本机基线已更新", first.BaselineLabel);
            Assert.False(second.BaselineUpdated);
            Assert.Contains("本机基线未变化", second.BaselineLabel);
        });
    }

    // ---- explicit skips: a row the page shows as skipped must never be written -------------------------

    [Fact]
    public async Task AnExplicitSkipIsHonouredEvenWithoutAnAllowList()
    {
        // A create needs no approval, so its default verdict is "apply". If only the allow list travelled, a row
        // the page showed as skipped would still be written; the skip list is what makes that verdict real.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));
            Assert.Equal(SourceSyncAction.Create, Assert.Single(preview.Items).Action);

            var seen = new List<SourceRecord?>();
            SourceSyncApplyReport result = await round.ApplyAsync(preview, [], Writer(store, seen), ["local-aaaa"]);

            Assert.Empty(seen);
            Assert.Equal(0, result.Applied);
            Assert.Equal(1, result.Skipped);
            Assert.Empty(store.GetAll());
            Assert.Equal(0, store.WriteCount);
        });
    }

    [Fact]
    public async Task ASkipOutranksAnAllowForTheSameIdentity()
    {
        // Both lists can name the same id after a double click. The gate keeps the last decision per identity and
        // the round appends the skips last, so "do not write this" wins over "write this".
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));

            var seen = new List<SourceRecord?>();
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-aaaa"], Writer(store, seen), ["local-aaaa"]);

            Assert.Empty(seen);
            Assert.Equal(0, result.Applied);
            Assert.Equal(1, result.Skipped);
            Assert.Empty(store.GetAll());
        });
    }

    [Fact]
    public async Task WithoutSkippedIdsTheDefaultVerdictStillApplies()
    {
        // CLI callers pass no skip list and keep the previous behaviour: an entry that needs no approval is applied.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));

            SourceSyncApplyReport result = await round.ApplyAsync(preview, [], Writer(store));

            Assert.Equal(1, result.Applied);
            Assert.Equal(0, result.Skipped);
            Assert.Equal("NAS", Assert.Single(store.GetAll()).DisplayName);
        });
    }

    [Fact]
    public async Task ASkippedEntryIsCountedAsSkippedNotAsFailed()
    {
        // Skipped is the user's choice, withheld is a refusal: the report must not blur the two, and a skipped
        // entry may not advance the baseline (otherwise the next import would treat it as already imported).
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var baselines = new FileSourceSyncBaselineStore(dir);
            var round = new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));

            SourceSyncApplyReport result = await round.ApplyAsync(preview, [], Writer(store), ["local-aaaa"]);

            Assert.Equal(0, result.Applied);
            Assert.Equal(0, result.Withheld);
            Assert.Equal(1, result.Skipped);
            Assert.Equal(result.Skipped + result.Withheld, result.NotApplied);
            Assert.Contains("跳过 1 条", result.Summary);
            Assert.False(result.BaselineUpdated);
            Assert.False(File.Exists(baselines.FilePath));
        });
    }

    [Fact]
    public async Task OnlyTheEntriesTheUserKeptAreWritten()
    {
        // The real shape of the screen: several rows, the user keeps one and skips the other.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "smb", "smb://share.local/media", "Share")));
            Assert.Equal(2, preview.Total);

            SourceSyncApplyReport result = await round.ApplyAsync(
                preview,
                ["local-aaaa"],
                Writer(store),
                ["local-bbbb"]);

            Assert.Equal(1, result.Applied);
            Assert.Equal(1, result.Skipped);
            LocalSourceRecord written = Assert.Single(store.GetAll());
            Assert.Equal("local-aaaa", written.SyncSourceId);
        });
    }

    [Fact]
    public async Task ASkippedIdThatIsNotInTheEnvelopeChangesNothing()
    {
        // A stale skip list must not disturb the entries it does not mention.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncApplyPreview preview = round.Preview(Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS")));

            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-aaaa"], Writer(store), ["local-zzzz"]);

            Assert.Equal(1, result.Applied);
            Assert.Equal(0, result.Skipped);
            Assert.Single(store.GetAll());
        });
    }

    // ---------------------------------------------------------------- helpers

    private static SourceSyncImportRound Round(ILocalSourceStore store, string directory)
    {
        var baselines = new FileSourceSyncBaselineStore(directory);
        return new SourceSyncImportRound(store, baselines, () => baselines.LoadError);
    }

    /// <summary>Stands in for the app's writer: one address per source, revision kept, identity kept.</summary>
    private static SourceSyncRecordWriter Writer(ILocalSourceStore store, List<SourceRecord?>? seen = null)
        => (item, record) =>
        {
            seen?.Add(record);

            if (record is null)
            {
                return Task.FromResult(store.Delete(item.SyncSourceId)
                    ? SourceSyncWriteOutcome.Written("已删除 " + item.SyncSourceId)
                    : SourceSyncWriteOutcome.Refused("本机没有该来源"));
            }

            SourceEndpoint primary = SourceSyncEndpointPolicy.SelectPrimary(record) ?? record.Endpoints[0];
            store.Save(new LocalSourceRecord
            {
                ServerId = item.SyncSourceId,
                SyncSourceId = record.SyncSourceId,
                Type = WindowsSourceTypeMap.ToServerType(record.SourceType.Wire),
                Url = primary.Url,
                AccountScope = record.AccountScope,
                DisplayName = record.DisplayName,
                RecordRevision = record.RecordRevision,
            });
            return Task.FromResult(SourceSyncWriteOutcome.Written("已写入 " + record.DisplayName));
        };

    private static SourceRecord Record(string syncSourceId, string wire, string url, string displayName)
        => new()
        {
            SyncSourceId = syncSourceId,
            SourceType = SyncSourceTypeValue.Parse(wire),
            DisplayName = displayName,
            Endpoints = [new SourceEndpoint { Url = url, IsPrimary = true, SortOrder = 0 }],
            RecordRevision = 3,
        };

    private static SourceSyncEnvelope Envelope(params SourceRecord[] sources)
        => Envelope(sources, []);

    private static SourceSyncEnvelope Envelope(SourceRecord[] sources, Tombstone[] deletions)
        => new()
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = 1,
            DeviceId = "dev-peer",
            SnapshotId = "snap-round-1",
            CreatedAtEpochMs = FixedNow,
            Capabilities = new SyncCapabilities { CanSendSecrets = false, SourceTypes = ["webdav", "smb", "emby"] },
            Sources = sources,
            Deletions = deletions,
        };

    private static void WithTempDirectory(Action<string> body)
    {
        string directory = NewTempDirectory();
        try
        {
            body(directory);
        }
        finally
        {
            DeleteQuietly(directory);
        }
    }

    private static async Task WithTempDirectoryAsync(Func<string, Task> body)
    {
        string directory = NewTempDirectory();
        try
        {
            await body(directory);
        }
        finally
        {
            DeleteQuietly(directory);
        }
    }

    private static string NewTempDirectory()
    {
        string directory = Path.Combine(Path.GetTempPath(), "mh-baseline", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    private static void DeleteQuietly(string directory)
    {
        try
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp folder is not a test failure.
        }
        catch (UnauthorizedAccessException)
        {
            // Same: the file may still be held by the file system for a moment.
        }
    }
}

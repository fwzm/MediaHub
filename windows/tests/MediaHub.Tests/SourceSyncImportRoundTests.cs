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

    // ---------------------------------------------------------------- written list (W6-90)

    [Fact]
    public async Task AppliedEntriesAreListedWithTheirPeerIdentityAndTitle()
    {
        // The counts say how many rows landed; the list says which ones, in write order, so 「已写入 2 条」 can be
        // checked row by row. Without it the result page is a number nobody can verify.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "emby", "http://emby.local:8096", "Emby"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa", "local-bbbb"],
                Writer(store));

            Assert.Equal(2, result.Applied);
            Assert.Equal(2, result.Written.Count);
            Assert.Equal(["local-aaaa", "local-bbbb"], result.Written.Select(entry => entry.SyncSourceId));
            Assert.Equal(["NAS", "Emby"], result.Written.Select(entry => entry.DisplayName));
            Assert.All(result.Written, entry => Assert.Equal(SourceSyncAction.Create, entry.Action));
            Assert.Equal(
                ["- NAS（local-aaaa）", "- Emby（local-bbbb）"],
                SourceSyncWrittenText.Lines(result.Written));
            Assert.Equal("已写入 2 条（按对端身份，可逐条核对）", SourceSyncWrittenText.Header(result.Written.Count));
        });
    }

    [Fact]
    public void WrittenHeaderStaysNullWhenNothingWasWritten()
    {
        // Zero rows must not leave a heading over an empty list.
        Assert.Null(SourceSyncWrittenText.Header(0));
        Assert.Equal(
            "- NAS（local-aaaa）",
            SourceSyncWrittenText.Line(new SourceSyncWrittenSource { SyncSourceId = "local-aaaa", DisplayName = "NAS" }));
    }

    [Fact]
    public void WrittenLineNeverInventsTheHalfItDoesNotHave()
    {
        // A peer that states no title still writes: the row shows the identity alone, and vice versa.
        Assert.Equal(
            "- local-aaaa",
            SourceSyncWrittenText.Line(new SourceSyncWrittenSource { SyncSourceId = " local-aaaa " }));
        Assert.Equal(
            "- NAS",
            SourceSyncWrittenText.Line(new SourceSyncWrittenSource { SyncSourceId = "  ", DisplayName = " NAS " }));

        // A deletion is a real local change; saying so keeps the row from reading like a create.
        Assert.Equal(
            "- NAS（local-aaaa） · 已从本机移除",
            SourceSyncWrittenText.Line(new SourceSyncWrittenSource
            {
                SyncSourceId = "local-aaaa",
                DisplayName = "NAS",
                Action = SourceSyncAction.Delete,
            }));
    }

    [Fact]
    public async Task RefusedWritesStayOutOfTheWrittenList()
    {
        // 保留未应用 means the write did not happen, so it must not appear in a list of things that did.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa"],
                (_, _) => Task.FromResult(SourceSyncWriteOutcome.Refused("该来源已被其他窗口占用")));

            Assert.Equal(0, result.Applied);
            Assert.Empty(result.Written);
            Assert.Null(SourceSyncWrittenText.Header(result.Written.Count));
            Assert.Equal(1, result.Withheld);
            Assert.Equal(1, result.NotApplied);
        });
    }

    [Fact]
    public async Task TheLocalIdTheWriterProducedTravelsIntoTheRow()
    {
        // The App layer mints a local id for a create; the row is the only place the user can see which local source
        // the entry became, so the writer's id must win over the one the plan happened to carry.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa"],
                (item, _) => Task.FromResult(SourceSyncWriteOutcome.Written("written", "sync:" + item.SyncSourceId)));

            SourceSyncWrittenSource written = Assert.Single(result.Written);
            Assert.Equal("sync:local-aaaa", written.ServerId);
            Assert.Equal("local-aaaa", written.SyncSourceId);
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

    // ---------------------------------------------------------------- refused list (W6-91)

    [Fact]
    public async Task RefusedWritesAreListedWithTheirReasonRowByRow()
    {
        // 保留未应用 used to be a number: a user could see that something was kept back, not which entry or why.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "smb", "smb://share.local/media", "Share"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa"],
                (_, _) => Task.FromResult(SourceSyncWriteOutcome.Refused("Windows 暂不支持该来源类型：smb")));

            SourceSyncRefusedSource refused = Assert.Single(result.Refused);
            Assert.Equal("local-aaaa", refused.SyncSourceId);
            Assert.Equal("Share", refused.DisplayName);
            Assert.Equal(SourceSyncAction.Create, refused.Action);
            Assert.Equal("Windows 暂不支持该来源类型：smb", refused.Reason);
            Assert.Equal(
                new[] { "- Share（local-aaaa） · 写入 · Windows 暂不支持该来源类型：smb" },
                SourceSyncRefusedText.Rows(result.Refused).Select(row => row.Text));
            Assert.Equal("未写入 1 条（逐条原因）", SourceSyncRefusedText.Header(result.Refused.Count));
            Assert.Empty(result.Written);
            Assert.Equal(1, result.Withheld);
        });
    }
    // ---------------------------------------------------------------- failure log line (W6-103)

    [Fact]
    public async Task EveryRefusedWriteIsHandedToTheFailureSinkTheMomentTheRoundRefusesIt()
    {
        // The page showed a refused row; the log only carried a count. The sink is where the round says which
        // entry it refused, and it hands over the very row the report keeps, so the two cannot drift.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "smb", "smb://share.local/media", "Share"),
                Record("local-bbbb", "emby", "http://nas-b:8096/emby", "客厅 Emby"));
            var sink = new List<SourceSyncRefusedSource>();

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa", "local-bbbb"],
                (item, _) => Task.FromResult(item.SyncSourceId == "local-aaaa"
                    ? SourceSyncWriteOutcome.Refused("Windows 暂不支持该来源类型：smb")
                    : SourceSyncWriteOutcome.Written("written")),
                refusedSink: sink.Add);

            SourceSyncRefusedSource watched = Assert.Single(sink);
            Assert.Same(Assert.Single(result.Refused), watched);
            Assert.Equal("local-aaaa", watched.SyncSourceId);
            Assert.Equal("Share", watched.DisplayName);
            Assert.Equal(SourceSyncAction.Create, watched.Action);
            Assert.Equal("Windows 暂不支持该来源类型：smb", watched.Reason);
            Assert.Equal(1, result.Applied);
        });
    }

    [Fact]
    public async Task AWriterThatThrowsIsHandedToTheFailureSinkWithTheExceptionMessage()
    {
        // A throw and a Refused outcome reach the page the same way; the log line must not depend on which one
        // the writer used.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "emby", "http://nas-a:8096/emby", "客厅 Emby"));
            var sink = new List<SourceSyncRefusedSource>();

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa"],
                (_, _) => throw new IOException("本机存储只读"),
                refusedSink: sink.Add);

            Assert.Equal("本机存储只读", Assert.Single(sink).Reason);
            Assert.Same(Assert.Single(result.Refused), sink[0]);
        });
    }

    [Fact]
    public async Task TheFailureSinkStaysQuietWhenEverythingWroteOrNothingWasAllowed()
    {
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "emby", "http://nas-a:8096/emby", "客厅 Emby"));
            var sink = new List<SourceSyncRefusedSource>();

            await round.ApplyAsync(round.Preview(peer), ["local-aaaa"], Writer(store), refusedSink: sink.Add);
            await round.ApplyAsync(round.Preview(peer), [], Writer(store), refusedSink: sink.Add);

            Assert.Empty(sink);
        });
    }

    [Fact]
    public void TheFailureLogLineNamesTheIdentityAndTheActionWithoutQuotingTheTitle()
    {
        // W6-103: the line is read next to the peer's log, so it carries the identity a person greps for, the
        // action, and the writer's own sentence — never a title or an address, which can quote a credential.
        Assert.Equal(
            "同步写入被拒：local-aaaa · 写入 · Windows 暂不支持该来源类型：smb",
            SourceSyncRefusedText.LogLine(new SourceSyncRefusedSource
            {
                SyncSourceId = " local-aaaa ",
                DisplayName = "Share",
                Action = SourceSyncAction.Create,
                Reason = "Windows 暂不支持该来源类型：smb",
            }));
        Assert.Equal(
            "同步写入被拒：（未知身份） · 删除 · 写入器没有说明原因",
            SourceSyncRefusedText.LogLine(new SourceSyncRefusedSource
            {
                SyncSourceId = "  ",
                DisplayName = "无身份来源",
                Action = SourceSyncAction.Delete,
                Reason = "",
            }));
        string withAddress = SourceSyncRefusedText.LogLine(new SourceSyncRefusedSource
        {
            SyncSourceId = "local-dddd",
            DisplayName = "http://nas-a:8096/emby?api_key=AAAA",
            Action = SourceSyncAction.Update,
            Reason = "该来源没有可用地址，已跳过",
        });
        Assert.Equal("同步写入被拒：local-dddd · 更新 · 该来源没有可用地址，已跳过", withAddress);
        Assert.DoesNotContain("api_key", withAddress);
        Assert.DoesNotContain("http://", withAddress);
    }

    [Fact]
    public void EveryRefusedRowCarriesTheIdentityTheSameButtonCopiesAndInventsNoWayOut()
    {
        // W6-95: the two kept-back lists bind one row shape, so the copy button means one thing. This is the list with
        // no way out to offer: the reason is already the writer own sentence, and Android adds a next step only to the
        // withheld rows.
        IReadOnlyList<SourceSyncCopyRow> rows = SourceSyncRefusedText.Rows(
        [
            new SourceSyncRefusedSource
            {
                SyncSourceId = " local-aaaa ",
                DisplayName = "Share",
                Action = SourceSyncAction.Create,
                Reason = "Windows 暂不支持该来源类型：smb",
            },
            new SourceSyncRefusedSource
            {
                SyncSourceId = "  ",
                DisplayName = "无身份来源",
                Action = SourceSyncAction.Delete,
                Reason = "",
            },
        ]);

        Assert.Equal(2, rows.Count);
        SourceSyncCopyRow first = rows[0];
        Assert.Equal("- Share（local-aaaa） · 写入 · Windows 暂不支持该来源类型：smb", first.Text);
        Assert.Equal("local-aaaa", first.CopyText);
        Assert.True(first.CanCopy);
        Assert.Equal(SourceSyncCopyText.CopyIdentity, first.CopyLabel);
        Assert.Equal(string.Empty, first.NextStep);
        SourceSyncCopyRow second = rows[1];
        Assert.Equal("- 无身份来源 · 删除 · 写入器没有说明原因", second.Text);
        Assert.Equal(string.Empty, second.CopyText);
        Assert.False(second.CanCopy);
        Assert.Equal(SourceSyncCopyText.NoCopyableIdentity, second.CopyLabel);
        Assert.Empty(SourceSyncRefusedText.Rows([]));
    }

    [Fact]
    public void TheTwoKeptBackListsBindTheSameRowShapeSoTheCopyButtonCannotDiverge()
    {
        // One row type, one payload rule, one pair of captions. A second record that merely looked the same is exactly
        // how the two lists would drift apart, and the button would then mean two different things.
        SourceSyncCopyRow withheld = Assert.Single(SourceSyncWithheldText.Rows(
            [new SourceSyncWithheldSource { SyncSourceId = " local-dddd ", DisplayName = "卧室 Emby", Reason = SyncRoundReasons.SecretsRequireApproval }]));
        SourceSyncCopyRow refused = Assert.Single(SourceSyncRefusedText.Rows(
            [new SourceSyncRefusedSource { SyncSourceId = " local-dddd ", DisplayName = "卧室 Emby", Action = SourceSyncAction.Create, Reason = "本机存储只读" }]));

        Assert.Equal("local-dddd", withheld.CopyText);
        Assert.Equal("local-dddd", refused.CopyText);
        Assert.True(withheld.CanCopy);
        Assert.True(refused.CanCopy);
        Assert.Equal(withheld.CopyLabel, refused.CopyLabel);
        Assert.Equal(SourceSyncCopyText.CopyIdentity, refused.CopyLabel);
        Assert.Contains("带凭据，需人工迁移（本次不会写入）", withheld.Text, StringComparison.Ordinal);
        Assert.Contains("本机存储只读", refused.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void RefusedHeaderStaysNullWhenNothingWasRefusedAndTheLineNeverInventsTheHalfItDoesNotHave()
    {
        // Zero rows must not leave a heading over an empty list.
        Assert.Null(SourceSyncRefusedText.Header(0));

        // A writer that says nothing still gets a whole sentence: a dangling separator reads like a broken page.
        Assert.Equal(
            "- Share · 写入 · 写入器没有说明原因",
            SourceSyncRefusedText.Line(new SourceSyncRefusedSource
            {
                SyncSourceId = "  ",
                DisplayName = " Share ",
                Reason = "   ",
            }));

        // A refusal on a deletion is not the same change as a refused write, so the action is stated.
        Assert.Equal(
            "- Share（local-aaaa） · 删除 · 本地媒体源不参与跨设备删除",
            SourceSyncRefusedText.Line(new SourceSyncRefusedSource
            {
                SyncSourceId = " local-aaaa ",
                DisplayName = " Share ",
                Action = SourceSyncAction.Delete,
                Reason = " 本地媒体源不参与跨设备删除 ",
            }));

        // Neither title nor identity is still one row, never an empty line.
        Assert.Equal(
            "- （未知身份） · 写入 · 磁盘只读",
            SourceSyncRefusedText.Line(new SourceSyncRefusedSource { Reason = "磁盘只读" }));
    }

    [Fact]
    public async Task AWriterThatThrowsIsListedWithTheExceptionMessage()
    {
        // A refused write is a kept entry, not a crashed round: the thrown message reaches the same list as a Refused outcome.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa"],
                (_, _) => throw new IOException("本机存储只读"));

            SourceSyncRefusedSource refused = Assert.Single(result.Refused);
            Assert.Equal("本机存储只读", refused.Reason);
            Assert.Equal(1, result.Refused.Count);
            Assert.Equal(1, result.Withheld);
            Assert.Equal(1, result.NotApplied);
        });
    }

    [Fact]
    public async Task WrittenAndRefusedListsStayDisjoint()
    {
        // The two lists answer different questions: what landed, and what was kept back. Neither may leak into the other.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            SourceSyncEnvelope peer = Envelope(
                Record("local-aaaa", "webdav", "http://nas.local:5005", "NAS"),
                Record("local-bbbb", "smb", "smb://share.local/media", "Share"));

            SourceSyncApplyReport result = await round.ApplyAsync(
                round.Preview(peer),
                ["local-aaaa", "local-bbbb"],
                (item, _) => Task.FromResult(item.SyncSourceId == "local-bbbb"
                    ? SourceSyncWriteOutcome.Refused("Windows 暂不支持该来源类型：smb")
                    : SourceSyncWriteOutcome.Written("written")));

            Assert.Equal(1, result.Applied);
            Assert.Equal(["local-aaaa"], result.Written.Select(entry => entry.SyncSourceId));
            Assert.Equal(["local-bbbb"], result.Refused.Select(entry => entry.SyncSourceId));
            Assert.Equal(1, result.Withheld);
            Assert.Equal(1, result.NotApplied);
        });
    }
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


    // ---------------------------------------------------------------- withheld list (W6-92)

    [Fact]
    public async Task WithheldEntriesAreListedWithTheirReasonRowByRow()
    {
        // 保留未应用 counts a gate-withheld entry but used to hide which one it was. These rows are the ones an
        // explicit 允许 cannot save, so the list has to say which entry and why, in the words the page already uses.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            using JsonDocument document = JsonDocument.Parse("""{"token":"sealed-on-the-other-machine"}""");
            var record = new SourceRecord
            {
                SyncSourceId = "local-dddd",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = "卧室 Emby",
                Endpoints = [new SourceEndpoint { Url = "http://emby.local:8096", IsPrimary = true }],
                RecordRevision = 2,
                Secrets = document.RootElement.Clone(),
            };

            SourceSyncApplyPreview preview = round.Preview(Envelope(record));
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-dddd"], Writer(store));

            SourceSyncWithheldSource entry = Assert.Single(result.WithheldEntries);
            Assert.Equal("local-dddd", entry.SyncSourceId);
            Assert.Equal("卧室 Emby", entry.DisplayName);
            Assert.Equal(SyncRoundReasons.SecretsRequireApproval, entry.Reason);
            Assert.Empty(result.Refused);
            Assert.Empty(result.Written);
            Assert.Equal(1, result.Withheld);
            Assert.Equal("保留未应用 1 条（扣住，点允许也不会写）", SourceSyncWithheldText.Header(result.WithheldEntries.Count));
            Assert.Equal("- 卧室 Emby（local-dddd） · 带凭据，需人工迁移（本次不会写入）", SourceSyncWithheldText.Line(entry));
            Assert.Empty(store.GetAll());
        });
    }

    [Fact]
    public void TheWithheldHeadingStaysNullAtZeroAndALineNeverInventsTheHalfItDoesNotHave()
    {
        Assert.Null(SourceSyncWithheldText.Header(0));
        Assert.Equal("保留未应用 2 条（扣住，点允许也不会写）", SourceSyncWithheldText.Header(2));

        Assert.Equal(
            "- local-x · 未知来源类型，Windows 无法应用",
            SourceSyncWithheldText.Line(new SourceSyncWithheldSource
            {
                SyncSourceId = "local-x",
                DisplayName = null,
                Reason = SyncRoundReasons.UnknownSourceType,
            }));

        Assert.Equal(
            "- 书房 Emby · Windows 暂不支持该来源类型",
            SourceSyncWithheldText.Line(new SourceSyncWithheldSource
            {
                SyncSourceId = "  ",
                DisplayName = " 书房 Emby ",
                Reason = SyncRoundReasons.NotSupportedOnWindows,
            }));

        // A blank reason becomes an explicit sentence; an unknown code is shown verbatim, never replaced by one
        // this build made up.
        Assert.Equal(
            "- （未知身份） · 对端没有说明原因",
            SourceSyncWithheldText.Line(new SourceSyncWithheldSource { SyncSourceId = "", DisplayName = null, Reason = "  " }));
        Assert.Equal(
            "- （未知身份） · something-new",
            SourceSyncWithheldText.Line(new SourceSyncWithheldSource { Reason = "something-new" }));
    }

    [Fact]
    public async Task WithheldAndRefusedListsAddUpToTheKeptCountWithoutOverlapping()
    {
        // 保留未应用 is one number with two halves: what the gate held back and what the writer refused. An entry
        // appears in exactly one list, and the two counts add up to the number the sentence shows.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            using JsonDocument document = JsonDocument.Parse("""{"token":"sealed"}""");
            var secret = new SourceRecord
            {
                SyncSourceId = "local-secret",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = "带凭据",
                Endpoints = [new SourceEndpoint { Url = "http://emby.local:8096", IsPrimary = true }],
                RecordRevision = 4,
                Secrets = document.RootElement.Clone(),
            };
            var plain = new SourceRecord
            {
                SyncSourceId = "local-plain",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = "普通来源",
                Endpoints = [new SourceEndpoint { Url = "http://plain.local:8096", IsPrimary = true }],
                RecordRevision = 2,
            };

            SourceSyncApplyPreview preview = round.Preview(Envelope(secret, plain));
            SourceSyncApplyReport result = await round.ApplyAsync(
                preview,
                [],
                (_, _) => Task.FromResult(SourceSyncWriteOutcome.Refused("本机存储只读")));

            Assert.Equal(1, result.WithheldEntries.Count);
            Assert.Equal("local-secret", result.WithheldEntries[0].SyncSourceId);
            Assert.Equal(1, result.Refused.Count);
            Assert.Equal("local-plain", result.Refused[0].SyncSourceId);
            Assert.Equal(2, result.Withheld);
            Assert.Empty(result.Written);
            Assert.DoesNotContain(result.Refused, entry => entry.SyncSourceId == "local-secret");
            Assert.DoesNotContain(result.WithheldEntries, entry => entry.SyncSourceId == "local-plain");
        });
    }
    [Fact]
    public void TheWayOutTableCoversEveryCodeTheGateCanWithholdWithAndInventsNothing()
    {
        // W6-93: the reason sentence says what happened, this one says where the way out is. The words are the ones
        // Android already uses for the same codes, so one code is never explained differently on the two clients.
        Assert.Equal(
            "下一步：在对端把这条来源的凭据清空后重新导出信封，或在本机手动新建一条同样的来源。",
            SourceSyncWithheldText.NextStep(SyncRoundReasons.SecretsRequireApproval));
        Assert.Equal(
            "下一步：等本机版本支持这种来源类型；再点几次允许也不会进来。",
            SourceSyncWithheldText.NextStep(SyncRoundReasons.NotSupportedOnWindows));
        Assert.Equal(
            "下一步：把两端升到同一版本后重新导出信封。",
            SourceSyncWithheldText.NextStep(SyncRoundReasons.UnknownSourceType));
        Assert.Equal(
            "下一步：这条两端内容不一致（版本号相同），本页一律不放行；请在来源页手工对齐后再导出。",
            SourceSyncWithheldText.NextStep(SyncRoundReasons.SameRevisionDifferentContent));
        Assert.Equal(
            "下一步：本机有多条来源指向同一身份，先在来源页删掉多余的再重新导出。",
            SourceSyncWithheldText.NextStep(SourceSyncApprovalGate.AmbiguousReasonPrefix + "legacy-identity"));

        // A code this build does not know gets no invented advice, and neither does a blank one.
        Assert.Null(SourceSyncWithheldText.NextStep("something-new"));
        Assert.Null(SourceSyncWithheldText.NextStep(""));
        Assert.Null(SourceSyncWithheldText.NextStep("   "));
        Assert.Null(SourceSyncWithheldText.NextStep(null));
    }

    [Fact]
    public async Task EveryWithheldRowCarriesTheWayOutAndTheIdentityTheButtonCopies()
    {
        // The row is one record with three facts; the page binds it directly and spells none of them.
        await WithTempDirectoryAsync(async dir =>
        {
            var store = new InMemoryLocalSourceStore();
            var round = Round(store, dir);
            using JsonDocument document = JsonDocument.Parse("""{"token":"sealed-on-the-other-machine"}""");
            var record = new SourceRecord
            {
                SyncSourceId = " local-dddd ",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = " 卧室 Emby ",
                Endpoints = [new SourceEndpoint { Url = "http://emby.local:8096", IsPrimary = true }],
                RecordRevision = 2,
                Secrets = document.RootElement.Clone(),
            };

            SourceSyncApplyPreview preview = round.Preview(Envelope(record));
            SourceSyncApplyReport result = await round.ApplyAsync(preview, ["local-dddd"], Writer(store));

            IReadOnlyList<SourceSyncCopyRow> rows = SourceSyncWithheldText.Rows(result.WithheldEntries);
            SourceSyncCopyRow row = Assert.Single(rows);
            Assert.Equal("- 卧室 Emby（local-dddd） · 带凭据，需人工迁移（本次不会写入）", row.Text);
            Assert.Contains("凭据清空", row.NextStep, StringComparison.Ordinal);
            // The payload is the identity as the peer stated it, trimmed; a title would be useless on the other machine.
            Assert.Equal("local-dddd", row.CopyText);
            Assert.True(row.CanCopy);
            Assert.Equal(SourceSyncWithheldText.CopyIdentity, row.CopyLabel);
        });
    }

    [Fact]
    public void ARowWithoutAnIdentitySaysSoOnItsButtonInsteadOfCopyingAnInvention()
    {
        IReadOnlyList<SourceSyncCopyRow> rows = SourceSyncWithheldText.Rows(
        [
            new SourceSyncWithheldSource { SyncSourceId = "local-x", DisplayName = "有身份", Reason = "something-new" },
            new SourceSyncWithheldSource { SyncSourceId = "  ", DisplayName = "无身份", Reason = "something-new" },
        ]);

        Assert.Equal(2, rows.Count);
        Assert.True(rows[0].CanCopy);
        Assert.Equal("local-x", rows[0].CopyText);
        Assert.Equal(string.Empty, rows[0].NextStep);
        Assert.False(rows[1].CanCopy);
        Assert.Equal(string.Empty, rows[1].CopyText);
        Assert.Equal(SourceSyncWithheldText.NoCopyableIdentity, rows[1].CopyLabel);
        Assert.Empty(SourceSyncWithheldText.Rows([]));
    }
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

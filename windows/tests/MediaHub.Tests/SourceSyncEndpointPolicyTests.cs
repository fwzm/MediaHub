using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins what a single-endpoint client does with a multi-endpoint record: import the primary, list the rest.
/// </summary>
/// <remarks>
/// The audit (§5.4) found Windows stores one address per source while Android stores several. Silently keeping the
/// first address would make a preview claim "nothing else to import" while the peer really configured more, so every
/// non-primary endpoint has to appear with a stable reason code.
/// </remarks>
public sealed class SourceSyncEndpointPolicyTests
{
    private static SourceEndpoint Endpoint(string url, bool primary = false, int sortOrder = 0, bool enabled = true) => new()
    {
        Url = url,
        IsPrimary = primary,
        Enabled = enabled,
        SortOrder = sortOrder,
    };

    private static SourceRecord Record(params SourceEndpoint[] endpoints) => new()
    {
        SyncSourceId = "sync-1",
        SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.Emby),
        DisplayName = "客厅 NAS",
        AccountScope = "alice",
        Endpoints = endpoints,
        RecordRevision = 3,
    };

    private static SourceSyncEnvelope Envelope(params SourceRecord[] records) => new()
    {
        DeviceId = "device-a",
        SnapshotId = "snapshot-1",
        CreatedAtEpochMs = 1L,
        Sources = records,
    };

    /// <summary>A record with no address has no primary; the summary counts it separately instead of failing.</summary>
    [Fact]
    public void SelectPrimary_NoEndpointsIsNull()
    {
        Assert.Null(SourceSyncEndpointPolicy.SelectPrimary(Record()));
        Assert.Null(SourceSyncEndpointPolicy.SelectPrimary(null));
        Assert.Empty(SourceSyncEndpointPolicy.NotImported(Record()));
    }

    /// <summary>The marked endpoint wins even when its sortOrder is larger: the mark is the sender saying "use this".</summary>
    [Fact]
    public void SelectPrimary_PrefersTheMarkedEndpointEvenWhenItSortsLater()
    {
        SourceEndpoint chosen = Assert.IsType<SourceEndpoint>(SourceSyncEndpointPolicy.SelectPrimary(
            Record(Endpoint("http://edge:8096", sortOrder: 0), Endpoint("http://nas:8096", primary: true, sortOrder: 5))));

        Assert.Equal("http://nas:8096", chosen.Url);
    }

    /// <summary>When nobody is marked, the smallest sortOrder wins — the sender's preference order, not list order.</summary>
    [Fact]
    public void SelectPrimary_FallsBackToTheSmallestSortOrder()
    {
        SourceEndpoint chosen = Assert.IsType<SourceEndpoint>(SourceSyncEndpointPolicy.SelectPrimary(
            Record(Endpoint("http://second:8096", sortOrder: 7), Endpoint("http://first:8096", sortOrder: 2))));

        Assert.Equal("http://first:8096", chosen.Url);
    }

    /// <summary>An exact tie keeps the sender's list order rather than picking arbitrarily.</summary>
    [Fact]
    public void SelectPrimary_TieKeepsTheSendersOrder()
    {
        SourceRecord record = Record(
            Endpoint("http://a:8096", primary: true, sortOrder: 0),
            Endpoint("http://b:8096", primary: true, sortOrder: 0));

        Assert.Equal("http://a:8096", SourceSyncEndpointPolicy.SelectPrimary(record)?.Url);
        SourceSyncNotImportedEndpoint notImported = Assert.Single(SourceSyncEndpointPolicy.NotImported(record));
        Assert.Equal("http://b:8096", notImported.Url);
    }

    /// <summary>Every non-primary endpoint is listed with a stable reason, its raw and normalised address.</summary>
    [Fact]
    public void NotImported_ListsEveryOtherEndpointWithAReason()
    {
        SourceRecord record = Record(
            Endpoint("http://nas:8096", primary: true, sortOrder: 0),
            Endpoint("HTTP://BACKUP:8096/emby/", sortOrder: 1),
            Endpoint("http://third:8096", sortOrder: 2, enabled: false));

        IReadOnlyList<SourceSyncNotImportedEndpoint> items = SourceSyncEndpointPolicy.NotImported(record);

        Assert.Equal(2, items.Count);
        Assert.All(items, item => Assert.Equal(SourceSyncEndpointPolicy.SecondaryNotImported, item.Reason));
        Assert.Equal("http://backup:8096/emby", items[0].NormalizedUrl);
        Assert.Equal("HTTP://BACKUP:8096/emby/", items[0].Url);
        Assert.False(items[1].Enabled);
        Assert.Equal("sync-1", items[0].SyncSourceId);
        Assert.Equal("客厅 NAS", items[0].DisplayName);
    }

    /// <summary>A single address is the common case and must produce no noise at all.</summary>
    [Fact]
    public void NotImported_SingleEndpointHasNothingToReport()
    {
        SourceRecord record = Record(Endpoint("http://nas:8096", primary: true));

        Assert.Empty(SourceSyncEndpointPolicy.NotImported(record));
        SourceSyncEndpointSummary summary = SourceSyncEndpointPolicy.Summarize(Envelope(record));
        Assert.True(summary.IsEmpty);
        Assert.Equal(0, summary.NotImportedCount);
        Assert.Equal(0, summary.RecordsWithAlternatives);
    }

    /// <summary>The summary separates "offered several addresses" from "offered none".</summary>
    [Fact]
    public void Summarize_CountsRecordsWithAndWithoutAlternatives()
    {
        SourceRecord multi = Record(Endpoint("http://nas:8096", primary: true), Endpoint("http://backup:8096", sortOrder: 1));
        SourceRecord none = Record() with { SyncSourceId = "sync-2" };

        SourceSyncEndpointSummary summary = SourceSyncEndpointPolicy.Summarize(Envelope(multi, none));

        Assert.Equal(1, summary.RecordsWithAlternatives);
        Assert.Equal(1, summary.RecordsWithoutEndpoint);
        Assert.Equal(1, summary.NotImportedCount);
        Assert.False(summary.IsEmpty);
    }

    /// <summary>The plan summary carries the count, so a preview can say "one more address was not imported".</summary>
    [Fact]
    public void Plan_SurfacesTheNotImportedCount()
    {
        SourceRecord record = Record(
            Endpoint("http://nas:8096", primary: true),
            Endpoint("http://backup:8096", sortOrder: 1),
            Endpoint("http://third:8096", sortOrder: 2));

        SourceSyncPlan plan = SourceSyncPlanner.Plan(Envelope(record), []);

        Assert.Equal(2, plan.NotImportedEndpointCount);
        Assert.All(plan.NotImportedEndpoints, item => Assert.Equal("sync-1", item.SyncSourceId));
        // The bytes the planner would write come from the same endpoint the policy reports as primary.
        Assert.Equal("http://nas:8096", plan.Items[0].NormalizedUrl);
    }

    /// <summary>An import result reports the same list, so the UI can explain what was left behind.</summary>
    [Fact]
    public void Import_ReportsNotImportedEndpoints()
    {
        SourceRecord record = Record(
            Endpoint("http://nas:8096", primary: true),
            Endpoint("http://backup:8096", sortOrder: 1));
        var session = new SourceSyncSession(new InMemoryLocalSourceStore(), "device-b", () => DateTimeOffset.UnixEpoch);

        ImportResult result = session.ImportPlain(Envelope(record));

        Assert.True(result.Succeeded);
        Assert.Equal(1, result.AppliedCount);
        Assert.Equal(1, result.NotImportedEndpointCount);
        Assert.Equal(SourceSyncEndpointPolicy.SecondaryNotImported, Assert.Single(result.NotImportedEndpoints).Reason);
    }

    /// <summary>An empty envelope has nothing to leave behind.</summary>
    [Fact]
    public void NotImported_EmptyEnvelopeIsEmpty()
    {
        Assert.Empty(SourceSyncEndpointPolicy.NotImported(Envelope()));
        Assert.Empty(SourceSyncEndpointPolicy.NotImported((SourceSyncEnvelope?)null));
        Assert.Equal(0, SourceSyncEndpointPolicy.Summarize(Envelope()).NotImportedCount);
    }
}

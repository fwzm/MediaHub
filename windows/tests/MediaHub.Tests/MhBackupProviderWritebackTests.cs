using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using MediaHub.Backup;
using MediaHub.Backup.Providers;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Providers.MediaBrowser;
using MediaHub.TestSupport;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 写回执行层接真实 provider 句柄的全链证据（W30）：一条备份记录 → 分类 → 执行 → 真 HTTP 写 → 服务端答案。
/// </summary>
/// <remarks>
/// 这里不伪造服务端：每个用例都起一个 loopback mock，用真的 Emby/Jellyfin provider 说话。
/// 于是「已经写进去了」「服务端把写入丢掉了」「根本没有会话」三种处境是三个不同的结果，
/// 而不是同一句「已恢复」。
/// </remarks>
public sealed class MhBackupProviderWritebackTests
{
    private const string User = "tester";
    private const string Password = "secret";
    private const string BackupId = "srv-1";

    // ------------------------------------------------------------ 样本构造

    private static MhBackupServerDto Server(string backupId, string type) => new()
    {
        BackupId = backupId,
        Name = backupId,
        Type = type,
        Username = "mh",
        IsDefault = false,
        Endpoints = new List<MhBackupEndpointDto>
        {
            new() { Name = "主线路", Url = "https://" + backupId + ".example", IsPrimary = true, Enabled = true, SortOrder = 0 },
        },
    };

    private static MhBackupProgressDto Progress(string serverBackupId, string itemId) => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        PositionMs = 120_000,
        DurationMs = 600_000,
        IsPaused = false,
        UpdatedAtEpochMs = 1_790_000_001_000,
        ItemTitle = "Long Resume",
        ItemType = "Movie",
    };

    private static MhBackupFavoriteDto Favorite(string serverBackupId, string itemId) => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        Title = "Long Resume",
        AddedAtEpochMs = 1_790_000_002_000,
    };

    private static MhBackupPayload Payload(
        IEnumerable<MhBackupProgressDto>? progress = null,
        IEnumerable<MhBackupFavoriteDto>? favorites = null) => new()
    {
        Servers = new List<MhBackupServerDto> { Server(BackupId, "EMBY") },
        Progress = progress?.ToList() ?? new List<MhBackupProgressDto>(),
        Favorites = favorites?.ToList() ?? new List<MhBackupFavoriteDto>(),
    };

    private static (MhBackupRestorePlan Plan, MhBackupWriteOutlook Outlook) PlanOf(MhBackupPayload payload)
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.Merge);
        return (plan, MhBackupWriteOutlooks.Classify(payload, plan));
    }

    private static NetworkPolicy DirectPolicy() => new() { Mode = ProxyMode.Direct };

    private static MediaServer ServerFor(MockMediaServer mock, ServerType type)
        => new("mock-server", type, "Mock source", new[] { new ServerEndpoint(mock.BaseUri.GetLeftPart(UriPartial.Authority)) });

    private static async Task<ProviderHandle> ConnectAsync(MockMediaServer mock, IMediaProviderFactory factory)
    {
        ProviderHandle handle = factory.Create(ServerFor(mock, factory.Descriptor.Type), DirectPolicy());
        await handle.Auth!.AuthenticateAsync(new Credentials(User, Password));
        return handle;
    }

    private static MhBackupWriteAttempt Only(MhBackupWritebackReport report, MhBackupRecordKind record)
        => Assert.Single(report.Attempts, attempt => attempt.Record == record);

    // ------------------------------------------------------------ 进度

    [Fact]
    public async Task AStoredPositionIsWrittenThroughTheLoggedInHandleAndTheServerKeepsIt()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        var writers = new MhBackupProviderWriters(id => id == BackupId ? handle : null);

        MhBackupPayload payload = Payload(new[] { Progress(BackupId, "item-003") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.Written, attempt.Outcome);
        Assert.Equal(1_200_000_000L, attempt.RequestedPositionTicks);
        Assert.Equal(1, report.WrittenCount);
        Assert.Equal(1_200_000_000L, mock.UserDataOf("item-003")!.PlaybackPositionTicks);

        RecordedRequest write = Assert.Single(mock.Requests, request =>
            request.Method == "POST" && request.Path.EndsWith("/UserData", StringComparison.Ordinal));
        Assert.Equal("/emby/Users/user-1/Items/item-003/UserData", write.Path);
        Assert.DoesNotContain("secret", write.Body);
    }

    [Fact]
    public async Task AWriteTheServerDropsIsReportedAsNotStoredInsteadOfWritten()
    {
        var options = new MockServerOptions
        {
            UserData = new Dictionary<string, MockUserData>
            {
                ["item-010"] = new MockUserData(300_000_000L, Played: false),
            },
        };
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby, options);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        mock.SetUserDataWritesIgnored(true);
        var writers = new MhBackupProviderWriters(id => id == BackupId ? handle : null);

        MhBackupPayload payload = Payload(new[] { Progress(BackupId, "item-010") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.ServerDidNotStore, attempt.Outcome);
        Assert.Contains("与请求的 1200000000 ticks 不同", attempt.Reason, StringComparison.Ordinal);
        Assert.Equal(300_000_000L, mock.UserDataOf("item-010")!.PlaybackPositionTicks);
        Assert.Equal(0, report.WrittenCount);
    }

    // ------------------------------------------------------------ 收藏

    [Fact]
    public async Task AFavoriteGoesThroughTheFavoritesRouteAndTheServerReportsItFavorited()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        var writers = new MhBackupProviderWriters(id => id == BackupId ? handle : null);

        MhBackupPayload payload = Payload(favorites: new[] { Favorite(BackupId, "item-004") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Favorite);
        Assert.Equal(MhBackupWriteOutcome.Written, attempt.Outcome);
        Assert.True(mock.IsFavorite("item-004"));
        Assert.Contains(mock.Requests, request =>
            request.Path.Contains("/FavoriteItems/item-004", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AFavoriteTheServerIgnoresIsReportedAsNotStored()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        using ProviderHandle handle = await ConnectAsync(mock, new EmbyProviderFactory());
        mock.SetFavoriteWritesIgnored(true);
        var writers = new MhBackupProviderWriters(id => id == BackupId ? handle : null);

        MhBackupPayload payload = Payload(favorites: new[] { Favorite(BackupId, "item-005") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Favorite);
        Assert.Equal(MhBackupWriteOutcome.ServerDidNotStore, attempt.Outcome);
        Assert.False(mock.IsFavorite("item-005"));
    }

    // ------------------------------------------------------------ 没有会话 / 没有写口

    [Fact]
    public async Task WithoutALoggedInHandleTheRecordNeedsLoginAndNothingIsSent()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        var writers = new MhBackupProviderWriters(_ => null);

        MhBackupPayload payload = Payload(new[] { Progress(BackupId, "item-006") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress);
        Assert.Equal(MhBackupWriteOutcome.NeedsLogin, attempt.Outcome);
        Assert.Equal(1, report.NeedsLoginCount);
        Assert.Contains("请求没有发出", attempt.Reason, StringComparison.Ordinal);
        Assert.Empty(mock.Requests);
    }

    [Fact]
    public async Task AHandleWithoutWriteCapabilitiesSaysSoInsteadOfClaimingALoginProblem()
    {
        await using var mock = await MockMediaServer.StartAsync(ServerType.Emby);
        var capLess = new ProviderHandle
        {
            ServerId = "mock-server",
            Server = ServerFor(mock, ServerType.Emby),
            Descriptor = new EmbyProviderFactory().Descriptor,
        };
        var writers = new MhBackupProviderWriters(_ => capLess);

        MhBackupPayload payload = Payload(
            new[] { Progress(BackupId, "item-007") },
            new[] { Favorite(BackupId, "item-008") });
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(plan, outlook, writers);

        Assert.Equal(MhBackupWriteOutcome.Failed, Only(report, MhBackupRecordKind.Progress).Outcome);
        Assert.Contains("没有保存播放位置的接口", Only(report, MhBackupRecordKind.Progress).Reason, StringComparison.Ordinal);
        Assert.Equal(MhBackupWriteOutcome.Failed, Only(report, MhBackupRecordKind.Favorite).Outcome);
        Assert.Contains("没有收藏接口", Only(report, MhBackupRecordKind.Favorite).Reason, StringComparison.Ordinal);
        Assert.Equal(0, report.NeedsLoginCount);
        Assert.Equal(2, report.FailedCount);
        Assert.Empty(mock.Requests);
    }
}

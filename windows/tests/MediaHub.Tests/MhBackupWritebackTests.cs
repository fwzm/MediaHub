using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 写回执行层（W29）：只有服务端回报才算数，没有会话就逐条说需登录。
/// </summary>
/// <remarks>
/// 这一层是纯逻辑：它把「写回去向」变成请求，再按服务端的答案记账。
/// 请求发出去、服务端回了 200 都不等于保存了 —— 所以这里钉住的是
/// 「回报与请求一致才算 written」「回报不一致 / 没回报算 server-did-not-store」
/// 「没有会话算 needs-login（请求根本没发）」「其余条目原样保留分类原因」。
/// </remarks>
public sealed class MhBackupWritebackTests
{
    // ------------------------------------------------------------ 样本构造

    private static MhBackupServerDto Server(string backupId, string type) => new()
    {
        BackupId = backupId,
        Name = backupId,
        Type = type,
        Username = "mh",
        IsDefault = false,
        Endpoints = type == "LOCAL"
            ? new List<MhBackupEndpointDto>()
            : new List<MhBackupEndpointDto>
            {
                new() { Name = "主线路", Url = "https://" + backupId + ".example", IsPrimary = true, Enabled = true, SortOrder = 0 },
            },
    };

    private static MhBackupProgressDto Progress(string serverBackupId, string itemId = "item-1", long positionMs = 120_000) => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        PositionMs = positionMs,
        DurationMs = 600_000,
        IsPaused = false,
        UpdatedAtEpochMs = 1_790_000_001_000,
        ItemTitle = "Long Resume",
        ItemType = "Movie",
    };

    private static MhBackupFavoriteDto Favorite(string serverBackupId, string itemId = "item-1") => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        Title = "Long Resume",
        AddedAtEpochMs = 1_790_000_002_000,
    };

    private static MhBackupPayload Payload(
        IEnumerable<MhBackupServerDto> servers,
        IEnumerable<MhBackupProgressDto>? progress = null,
        IEnumerable<MhBackupFavoriteDto>? favorites = null) => new()
    {
        Servers = servers.ToList(),
        Progress = progress?.ToList() ?? new List<MhBackupProgressDto>(),
        Favorites = favorites?.ToList() ?? new List<MhBackupFavoriteDto>(),
    };

    private static (MhBackupRestorePlan Plan, MhBackupWriteOutlook Outlook) PlanOf(MhBackupPayload payload)
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            payload,
            Array.Empty<MhBackupLocalSource>(),
            MhBackupRestoreStrategy.Merge);
        return (plan, MhBackupWriteOutlooks.Classify(payload, plan));
    }

    private static Task<MhBackupWritebackReport> RunAsync(
        MhBackupPayload payload,
        IMhBackupServerWriters? writers)
    {
        (MhBackupRestorePlan plan, MhBackupWriteOutlook outlook) = PlanOf(payload);
        return MhBackupWritebacks.RunAsync(plan, outlook, writers);
    }

    private static MhBackupWriteAttempt Only(MhBackupWritebackReport report, MhBackupRecordKind record, string itemId)
        => Assert.Single(report.Attempts, a => a.Record == record && a.ItemId == itemId);

    private sealed class FakeWriter : IMhBackupServerWriter
    {
        private readonly Func<string, long, MhBackupWriteAnswer>? _position;
        private readonly Func<string, MhBackupWriteAnswer>? _favorite;

        public FakeWriter(
            Func<string, long, MhBackupWriteAnswer>? position = null,
            Func<string, MhBackupWriteAnswer>? favorite = null)
        {
            _position = position;
            _favorite = favorite;
        }

        public List<(string ItemId, long Ticks)> PositionCalls { get; } = new();

        public List<string> FavoriteCalls { get; } = new();

        public Task<MhBackupWriteAnswer> SavePlaybackPositionAsync(string itemId, long positionTicks, CancellationToken ct)
        {
            PositionCalls.Add((itemId, positionTicks));
            return Task.FromResult(_position!(itemId, positionTicks));
        }

        public Task<MhBackupWriteAnswer> SetFavoriteAsync(string itemId, CancellationToken ct)
        {
            FavoriteCalls.Add(itemId);
            return Task.FromResult(_favorite!(itemId));
        }
    }

    private sealed class FakeWriters : IMhBackupServerWriters
    {
        private readonly Dictionary<string, IMhBackupServerWriter?> _byServer;

        public FakeWriters(params (string ServerBackupId, IMhBackupServerWriter? Writer)[] servers)
        {
            _byServer = servers.ToDictionary(pair => pair.ServerBackupId, pair => pair.Writer, StringComparer.Ordinal);
        }

        public IMhBackupServerWriter? ForServer(string serverBackupId)
            => _byServer.TryGetValue(serverBackupId, out IMhBackupServerWriter? writer) ? writer : null;
    }

    // ------------------------------------------------------------ 服务端回报决定结果

    [Fact]
    public async Task AProgressWriteIsWrittenOnlyWhenTheServerReportsTheSamePosition()
    {
        var writer = new FakeWriter(position: (_, _) => MhBackupWriteAnswer.Position(1_200_000_000L));
        var writers = new FakeWriters(("srv-emby", writer));

        MhBackupWritebackReport report = await RunAsync(
            Payload(new[] { Server("srv-emby", "EMBY") }, new[] { Progress("srv-emby", "item-1") }),
            writers);

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteOutcome.Written, attempt.Outcome);
        Assert.Equal("written", attempt.OutcomeCode);
        Assert.Equal(1_200_000_000L, attempt.RequestedPositionTicks);
        Assert.Contains("与请求一致", attempt.Reason);
        Assert.Contains("1200000000", attempt.Reason);
        Assert.Equal(("item-1", 1_200_000_000L), writer.PositionCalls.Single());
        Assert.Equal(1, report.WrittenCount);
        Assert.Equal(0, report.ServerDidNotStoreCount);
    }

    [Fact]
    public async Task AWriteTheServerIgnoresIsReportedAsNotStored()
    {
        var writer = new FakeWriter(position: (_, _) => MhBackupWriteAnswer.Position(300_000_000L));

        MhBackupWritebackReport report = await RunAsync(
            Payload(new[] { Server("srv-emby", "EMBY") }, new[] { Progress("srv-emby", "item-1") }),
            new FakeWriters(("srv-emby", writer)));

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteOutcome.ServerDidNotStore, attempt.Outcome);
        Assert.Equal("server-did-not-store", attempt.OutcomeCode);
        Assert.Contains("300000000", attempt.Reason);
        Assert.Contains("1200000000", attempt.Reason);
        Assert.Contains("没有被采纳", attempt.Reason);
        Assert.Equal(1, report.ServerDidNotStoreCount);
        Assert.Equal(0, report.WrittenCount);
    }

    [Fact]
    public async Task AWriterThatReportsFailureKeepsTheServerReason()
    {
        var writer = new FakeWriter(position: (_, _) => MhBackupWriteAnswer.Fail("服务端拒绝：目录只读"));

        MhBackupWritebackReport report = await RunAsync(
            Payload(new[] { Server("srv-emby", "EMBY") }, new[] { Progress("srv-emby", "item-1") }),
            new FakeWriters(("srv-emby", writer)));

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteOutcome.Failed, attempt.Outcome);
        Assert.Equal("服务端拒绝：目录只读", attempt.Reason);
        Assert.Equal(1, report.FailedCount);
    }

    [Fact]
    public async Task AWriterThatThrowsIsCaughtAndCollapsedToASingleShortLine()
    {
        string noise = "boom" + Environment.NewLine + Environment.NewLine + "  细节  " + new string('x', 400);
        var writer = new FakeWriter(position: (_, _) => throw new InvalidOperationException(noise));

        MhBackupWritebackReport report = await RunAsync(
            Payload(new[] { Server("srv-emby", "EMBY") }, new[] { Progress("srv-emby", "item-1") }),
            new FakeWriters(("srv-emby", writer)));

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteOutcome.Failed, attempt.Outcome);
        Assert.StartsWith("写回失败：", attempt.Reason);
        Assert.Contains("boom", attempt.Reason);
        Assert.DoesNotContain(Environment.NewLine, attempt.Reason);
        Assert.DoesNotContain("   ", attempt.Reason);
        Assert.DoesNotContain("InvalidOperationException", attempt.Reason);
        Assert.True(attempt.Reason.Length <= 176, "原因应被截断成一句：" + attempt.Reason.Length);
    }

    // ------------------------------------------------------------ 没有会话 / 不适用

    [Fact]
    public async Task WithoutASessionEveryServerApiRecordNeedsLoginAndTheRestKeepTheirOutlookReason()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-emby", "EMBY"), Server("srv-webdav", "WEBDAV"), Server("srv-local", "LOCAL") },
            new[] { Progress("srv-emby", "api-1"), Progress("srv-webdav", "dav-1"), Progress("srv-local", "path-1") },
            new[] { Favorite("srv-webdav", "dav-fav") });
        (_, MhBackupWriteOutlook outlook) = PlanOf(payload);

        MhBackupWritebackReport report = await MhBackupWritebacks.RunAsync(PlanOf(payload).Plan, outlook, writers: null);

        MhBackupWriteAttempt api = Only(report, MhBackupRecordKind.Progress, "api-1");
        Assert.Equal(MhBackupWriteOutcome.NeedsLogin, api.Outcome);
        Assert.Equal("needs-login", api.OutcomeCode);
        Assert.Contains("需要先登录", api.Reason);
        Assert.Contains("请求没有发出", api.Reason);
        Assert.Equal(1, report.NeedsLoginCount);

        MhBackupWriteAttempt path = Only(report, MhBackupRecordKind.Progress, "path-1");
        Assert.Equal(MhBackupWriteOutcome.NotApplicable, path.Outcome);
        Assert.Equal("not-applicable", path.OutcomeCode);
        Assert.Equal(
            outlook.Targets.Single(t => t.ItemId == "path-1").Reason,
            path.Reason);
        Assert.Contains("不是本机文件路径", path.Reason);

        Assert.Equal(MhBackupWriteOutcome.NotApplicable, Only(report, MhBackupRecordKind.Favorite, "dav-fav").Outcome);
        Assert.Equal(3, report.NotApplicableCount);
        Assert.Equal(0, report.WrittenCount);
    }

    // ------------------------------------------------------------ 收藏

    [Fact]
    public async Task AFavoriteIsWrittenOnlyWhenTheServerReportsItAsFavorite()
    {
        var writer = new FakeWriter(
            favorite: itemId => itemId == "fav-yes"
                ? MhBackupWriteAnswer.Favorite(true)
                : MhBackupWriteAnswer.Favorite(false));

        MhBackupWritebackReport report = await RunAsync(
            Payload(
                new[] { Server("srv-jellyfin", "JELLYFIN") },
                favorites: new[] { Favorite("srv-jellyfin", "fav-yes"), Favorite("srv-jellyfin", "fav-no") }),
            new FakeWriters(("srv-jellyfin", writer)));

        MhBackupWriteAttempt yes = Only(report, MhBackupRecordKind.Favorite, "fav-yes");
        Assert.Equal(MhBackupWriteOutcome.Written, yes.Outcome);
        Assert.Contains("已收藏", yes.Reason);
        Assert.Null(yes.RequestedPositionTicks);

        MhBackupWriteAttempt no = Only(report, MhBackupRecordKind.Favorite, "fav-no");
        Assert.Equal(MhBackupWriteOutcome.ServerDidNotStore, no.Outcome);
        Assert.Contains("仍未收藏", no.Reason);
        Assert.Equal(new[] { "fav-yes", "fav-no" }, writer.FavoriteCalls);
        Assert.Empty(writer.PositionCalls);
    }

    // ------------------------------------------------------------ 备份里的值不足以往外写

    [Fact]
    public async Task ANegativePositionInTheBackupIsRefusedWithoutTalkingToTheServer()
    {
        var writer = new FakeWriter(position: (_, _) => MhBackupWriteAnswer.Position(0));

        MhBackupWritebackReport report = await RunAsync(
            Payload(new[] { Server("srv-emby", "EMBY") }, new[] { Progress("srv-emby", "item-1", positionMs: -5) }),
            new FakeWriters(("srv-emby", writer)));

        MhBackupWriteAttempt attempt = Only(report, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteOutcome.Failed, attempt.Outcome);
        Assert.Contains("负数", attempt.Reason);
        Assert.Contains("-5", attempt.Reason);
        Assert.Empty(writer.PositionCalls);
        Assert.Equal(-50_000L, attempt.RequestedPositionTicks);
    }

    // ------------------------------------------------------------ 汇总文案

    [Fact]
    public async Task TheReportLinesCountEachOutcomeWithFixedSeparators()
    {
        var writer = new FakeWriter(
            position: (_, _) => MhBackupWriteAnswer.Position(1_200_000_000L),
            favorite: _ => MhBackupWriteAnswer.Favorite(true));

        MhBackupPayload payload = Payload(
            new[] { Server("srv-emby", "EMBY"), Server("srv-emby-2", "EMBY"), Server("srv-local", "LOCAL") },
            new[] { Progress("srv-emby", "p-written"), Progress("srv-emby-2", "p-login"), Progress("srv-local", "p-path") },
            new[] { Favorite("srv-emby", "f-written"), Favorite("srv-emby-2", "f-login"), Favorite("srv-local", "f-unsupported") });

        MhBackupWritebackReport report = await RunAsync(
            payload,
            new FakeWriters(("srv-emby", writer), ("srv-emby-2", null)));

        Assert.Equal(2, report.Lines.Count);
        Assert.Equal(
            "播放记录写回：已保存 1 条 · 服务端未保存 0 条 · 需登录 1 条 · 失败 0 条 · 不适用 1 条",
            report.Lines[0]);
        Assert.Equal(
            "收藏写回：已保存 1 条 · 服务端未保存 0 条 · 需登录 1 条 · 失败 0 条 · 不适用 1 条",
            report.Lines[1]);
    }

    [Fact]
    public async Task AnEmptyPayloadProducesNoAttemptsAndNoLines()
    {
        MhBackupWritebackReport report = await RunAsync(
            Payload(Array.Empty<MhBackupServerDto>()),
            writers: null);

        Assert.Empty(report.Attempts);
        Assert.Empty(report.Lines);
        Assert.Equal(0, report.NotApplicableCount);
    }
}

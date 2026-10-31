using System;
using System.Collections.Generic;
using System.Linq;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 播放记录与收藏的「写回去向」分类（S3 阶段 A）。
/// </summary>
/// <remarks>
/// Windows 侧没有按条目的播放进度库（观看记录是文件路径键），收藏也只有服务端接口；
/// 所以恢复回执不能说「已恢复」或只丢一句「不落库」，必须逐条说明去向。
/// 这里钉住分类本身：哪些走服务端接口、哪些因为 id 不是路径而无法映射、哪些来源类型根本没有接口。
/// </remarks>
public sealed class MhBackupWriteOutlookTests
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

    private static MhBackupProgressDto Progress(string serverBackupId, string itemId = "item-1", long updatedAtEpochMs = 1_790_000_001_000) => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        PositionMs = 120_000,
        DurationMs = 600_000,
        IsPaused = false,
        UpdatedAtEpochMs = updatedAtEpochMs,
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

    private static MhBackupWriteOutlook Classify(
        MhBackupPayload payload,
        MhBackupRestoreStrategy strategy = MhBackupRestoreStrategy.Merge,
        IReadOnlyList<MhBackupLocalSource>? local = null,
        IReadOnlyList<MhBackupLocalProgress>? localProgress = null)
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, local ?? Array.Empty<MhBackupLocalSource>(), strategy, localProgress);
        return MhBackupWriteOutlooks.Classify(payload, plan);
    }

    private static MhBackupWriteTarget One(MhBackupWriteOutlook outlook, MhBackupRecordKind record, string itemId)
        => Assert.Single(outlook.Targets, t => t.Record == record && t.ItemId == itemId);

    // ------------------------------------------------------------ 服务端接口

    [Fact]
    public void EmbyAndJellyfinRecordsGoThroughTheServerApiAfterLoggingIn()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-emby", "EMBY"), Server("srv-jelly", "jellyfin") },
            new[] { Progress("srv-emby", "emby-1"), Progress("srv-jelly", "jelly-1") },
            new[] { Favorite("srv-jelly", "jelly-fav") });

        MhBackupWriteOutlook outlook = Classify(payload);

        Assert.Equal(3, outlook.Targets.Count);
        Assert.Equal(2, outlook.ProgressServerApi);
        Assert.Equal(1, outlook.FavoriteServerApi);
        Assert.Equal(0, outlook.NotSelected);
        Assert.Equal(0, outlook.Orphan);

        MhBackupWriteTarget progress = One(outlook, MhBackupRecordKind.Progress, "emby-1");
        Assert.Equal(MhBackupWriteKind.ServerApi, progress.Kind);
        Assert.Equal("server-api", progress.KindCode);
        Assert.Equal("EMBY", progress.ServerType);
        Assert.Contains("由服务端保存", progress.Reason);
        Assert.Contains("需要登录后调用服务端接口", progress.Reason);
        Assert.Contains("本命令不执行写回", progress.Reason);

        MhBackupWriteTarget favorite = One(outlook, MhBackupRecordKind.Favorite, "jelly-fav");
        Assert.Equal(MhBackupWriteKind.ServerApi, favorite.Kind);
        Assert.Equal("JELLYFIN", favorite.ServerType);
        Assert.Contains("收藏", favorite.Reason);
    }

    // ------------------------------------------------------------ 无法映射

    [Fact]
    public void ALocalFolderRecordIsNotAPathKeyAndSaysSo()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-local", "LOCAL") },
            new[] { Progress("srv-local", "local-1") });

        MhBackupWriteOutlook outlook = Classify(payload);

        MhBackupWriteTarget target = One(outlook, MhBackupRecordKind.Progress, "local-1");
        Assert.Equal(MhBackupWriteKind.LocalPathKey, target.Kind);
        Assert.Equal("local-path-key", target.KindCode);
        Assert.Contains("不是本机文件路径", target.Reason);
        Assert.Contains("按路径记账", target.Reason);
        Assert.Equal(1, outlook.ProgressLocalPathKey);
        Assert.Equal(0, outlook.ProgressServerApi);
    }

    // ------------------------------------------------------------ 没有接口

    [Fact]
    public void TypesWithoutAWriteApiAreNamedInsteadOfSilentlyDropped()
    {
        var types = new[] { "WEBDAV", "SMB", "PLEX", "FN_NAS", "CLOUD" };
        MhBackupPayload payload = Payload(
            types.Select((type, index) => Server("srv-" + index, type)),
            types.Select((type, index) => Progress("srv-" + index, "item-" + index)));

        MhBackupWriteOutlook outlook = Classify(payload);

        Assert.Equal(types.Length, outlook.ProgressUnsupportedType);
        Assert.Equal(0, outlook.ProgressServerApi);
        for (int index = 0; index < types.Length; index++)
        {
            MhBackupWriteTarget target = One(outlook, MhBackupRecordKind.Progress, "item-" + index);
            Assert.Equal(MhBackupWriteKind.UnsupportedType, target.Kind);
            Assert.Equal("unsupported-type", target.KindCode);
            Assert.Equal(types[index], target.ServerType);
            Assert.Contains("来源类型 " + types[index] + " 没有播放进度写回接口", target.Reason);
        }
    }

    [Fact]
    public void FavoritesOnTypesWithoutAnApiAlsoGetTheirOwnReason()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-local", "LOCAL"), Server("srv-webdav", "WEBDAV") },
            favorites: new[] { Favorite("srv-local", "local-fav"), Favorite("srv-webdav", "webdav-fav") });

        MhBackupWriteOutlook outlook = Classify(payload);

        Assert.Equal(2, outlook.FavoriteUnsupportedType);
        Assert.Equal(0, outlook.FavoriteServerApi);
        Assert.Contains("没有收藏写回接口", One(outlook, MhBackupRecordKind.Favorite, "local-fav").Reason);
        Assert.Contains("没有收藏写回接口", One(outlook, MhBackupRecordKind.Favorite, "webdav-fav").Reason);
    }

    // ------------------------------------------------------------ 未选中 / 孤立

    [Fact]
    public void ARecordThePlanSkippedOnConflictIsNotWrittenAndKeepsThePlanReason()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-1", "EMBY") },
            new[] { Progress("srv-1") },
            new[] { Favorite("srv-1", "fav-1") });
        // 同 ID 不同身份：合并模式判冲突，来源与它的记录都不导入。
        var local = new[]
        {
            new MhBackupLocalSource { BackupId = "srv-1", Type = "Emby", PrimaryUrl = "https://other.example", UserName = "mh" },
        };

        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, local, MhBackupRestoreStrategy.Merge);
        MhBackupWriteOutlook outlook = MhBackupWriteOutlooks.Classify(payload, plan);

        Assert.Equal(2, outlook.NotSelected);
        Assert.Equal(0, outlook.ProgressServerApi);
        Assert.Equal(0, outlook.FavoriteServerApi);

        MhBackupWriteTarget progress = One(outlook, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteKind.NotSelected, progress.Kind);
        Assert.Equal("not-selected", progress.KindCode);
        Assert.Equal(plan.Progress.Single().Reason, progress.Reason);
        Assert.Contains("冲突", progress.Reason);
    }

    [Fact]
    public void ARecordTheLocalSideAlreadyHasNewerIsReportedAsNotSelected()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-1", "EMBY") },
            new[] { Progress("srv-1", updatedAtEpochMs: 1_000) });
        var local = new[]
        {
            new MhBackupLocalSource { BackupId = "srv-1", Type = "Emby", PrimaryUrl = "https://srv-1.example", UserName = "mh" },
        };
        var localProgress = new[] { new MhBackupLocalProgress("srv-1", "item-1", 2_000) };

        MhBackupWriteOutlook outlook = Classify(payload, local: local, localProgress: localProgress);

        MhBackupWriteTarget target = One(outlook, MhBackupRecordKind.Progress, "item-1");
        Assert.Equal(MhBackupWriteKind.NotSelected, target.Kind);
        Assert.Contains("保留本机", target.Reason);
        Assert.Equal(1, outlook.NotSelected);
    }

    [Fact]
    public void ARecordThatPointsAtASourceTheBackupDoesNotCarryIsOrphan()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-1", "EMBY") },
            new[] { Progress("srv-1", "ok-1"), Progress("srv-missing", "lost-1") },
            new[] { Favorite("srv-missing", "lost-fav") });

        MhBackupWriteOutlook outlook = Classify(payload);

        Assert.Equal(2, outlook.Orphan);
        MhBackupWriteTarget lost = One(outlook, MhBackupRecordKind.Progress, "lost-1");
        Assert.Equal(MhBackupWriteKind.Orphan, lost.Kind);
        Assert.Equal("orphan", lost.KindCode);
        Assert.Contains("不存在", lost.Reason);
        Assert.Null(lost.ServerType);
        Assert.Equal(MhBackupWriteKind.ServerApi, One(outlook, MhBackupRecordKind.Progress, "ok-1").Kind);
    }

    // ------------------------------------------------------------ 汇总文案

    [Fact]
    public void TheSummaryLinesSpellOutEveryBucketWithFixedSeparators()
    {
        MhBackupPayload payload = Payload(
            new[] { Server("srv-emby", "EMBY"), Server("srv-local", "LOCAL"), Server("srv-webdav", "WEBDAV") },
            new[] { Progress("srv-emby", "a"), Progress("srv-local", "b"), Progress("srv-webdav", "c"), Progress("srv-missing", "d") },
            new[] { Favorite("srv-emby", "fav"), Favorite("srv-missing", "fav-lost") });

        MhBackupWriteOutlook outlook = Classify(payload);

        Assert.Equal(4, outlook.ProgressTotal);
        Assert.Equal(2, outlook.FavoriteTotal);
        Assert.Equal(2, outlook.Lines.Count);
        Assert.Equal(
            "播放记录 4 条写回方式：服务端接口 1 条（需登录）· 本机路径键无法映射 1 条 · 该类型没有接口 1 条 · 未选中 0 条 · 孤立引用 1 条",
            outlook.Lines[0]);
        Assert.Equal(
            "收藏 2 条写回方式：服务端接口 1 条（需登录）· 该类型没有接口 0 条 · 未选中 0 条 · 孤立引用 1 条",
            outlook.Lines[1]);
    }

    [Fact]
    public void AnEmptyPayloadHasNoTargetsAndNoLines()
    {
        MhBackupWriteOutlook outlook = Classify(Payload(Array.Empty<MhBackupServerDto>()));

        Assert.Empty(outlook.Targets);
        Assert.Empty(outlook.Lines);
        Assert.Equal(0, outlook.ProgressTotal);
        Assert.Equal(0, outlook.FavoriteTotal);
        Assert.Equal(0, outlook.NotSelected);
        Assert.Equal(0, outlook.Orphan);
    }
}

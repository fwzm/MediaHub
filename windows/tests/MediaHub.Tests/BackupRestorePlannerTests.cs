using System;
using System.Collections.Generic;
using System.Linq;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 恢复预览（S3 阶段 A）：把一份已解密备份与本机现状比出「新增 / 同源 / 同 ID 冲突 / 孤立引用」，
/// 且必须零写入。语义对齐 Android BackupRepository.buildPreview / buildPlanRecord / resolveProgress；
/// 这里钉住三点：身份裁决三元组、播放记录的 newer-wins、以及冲突来源的记录绝不被顺手带进本机。
/// </summary>
public sealed class BackupRestorePlannerTests
{
    // ------------------------------------------------------------ 样本构造

    private static MhBackupServerDto Server(
        string backupId,
        string name = "客厅 Emby",
        string type = "EMBY",
        string? url = "https://emby.example.com:8920",
        string? username = "mh",
        int sortOrder = 0,
        bool isDefault = true) => new()
    {
        BackupId = backupId,
        Name = name,
        Type = type,
        Username = username,
        IsDefault = isDefault,
        SortOrder = sortOrder,
        Endpoints = type == "LOCAL" || url is null
            ? new List<MhBackupEndpointDto>()
            : new List<MhBackupEndpointDto>
            {
                new() { Name = "主线路", Url = url, IsPrimary = true, Enabled = true, SortOrder = 0 },
            },
    };

    private static MhBackupProgressDto Progress(
        string serverBackupId, string itemId = "item-1", long positionMs = 120_000, long durationMs = 600_000,
        long updatedAtEpochMs = 1_790_000_001_000, string? title = "Long Resume") => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        PositionMs = positionMs,
        DurationMs = durationMs,
        IsPaused = false,
        UpdatedAtEpochMs = updatedAtEpochMs,
        ItemTitle = title,
        ItemType = "Movie",
    };

    private static MhBackupFavoriteDto Favorite(
        string serverBackupId, string itemId = "item-1", string? title = "Long Resume") => new()
    {
        ServerBackupId = serverBackupId,
        ItemId = itemId,
        Title = title,
        AddedAtEpochMs = 1_790_000_002_000,
    };

    private static MhBackupPayload Payload(
        List<MhBackupServerDto>? servers = null,
        List<MhBackupProgressDto>? progress = null,
        List<MhBackupFavoriteDto>? favorites = null,
        MhBackupPreferencesDto? preferences = null) => new()
    {
        Manifest = new MhBackupManifest(
            FormatVersion: 2,
            MinimumReaderVersion: 2,
            AppVersion: "1.0.0-test",
            CreatedAtEpochMs: 1_790_000_000_000,
            IncludedSections: new List<string> { "favorites", "preferences", "progress", "servers" },
            RecordCounts: new Dictionary<string, int> { ["servers"] = 1, ["progress"] = 0, ["preferences"] = 1, ["favorites"] = 0 }),
        Servers = servers ?? new List<MhBackupServerDto>(),
        Progress = progress ?? new List<MhBackupProgressDto>(),
        Favorites = favorites ?? new List<MhBackupFavoriteDto>(),
        Preferences = preferences,
    };

    private static MhBackupLocalSource Local(
        string backupId,
        string type = "EMBY",
        string primaryUrl = "https://emby.example.com:8920",
        string? userName = "mh") => new()
    {
        BackupId = backupId,
        Type = type,
        PrimaryUrl = primaryUrl,
        UserName = userName,
    };

    private static MhBackupPayload OneServerProgressAndFavorite() => Payload(
        servers: new List<MhBackupServerDto> { Server("srv-emby") },
        progress: new List<MhBackupProgressDto> { Progress("srv-emby") },
        favorites: new List<MhBackupFavoriteDto> { Favorite("srv-emby") });

    // ------------------------------------------------------------ 空与新增

    [Fact]
    public void An_empty_payload_and_no_local_sources_plan_nothing()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(Payload(), Array.Empty<MhBackupLocalSource>());

        Assert.Empty(plan.Servers);
        Assert.Empty(plan.Progress);
        Assert.Empty(plan.Favorites);
        Assert.Equal(0, plan.NewCount);
        Assert.False(plan.HasWrites);
        Assert.False(plan.PreferencesContained);
    }

    [Fact]
    public void A_backup_source_with_no_local_match_is_new_and_its_records_will_be_written()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(OneServerProgressAndFavorite(), Array.Empty<MhBackupLocalSource>());

        MhBackupServerDecision server = Assert.Single(plan.Servers);
        Assert.Equal(MhBackupServerVerdict.New, server.Verdict);
        Assert.Equal("srv-emby", plan.IdRemapping["srv-emby"]);
        Assert.Equal(1, plan.NewCount);
        Assert.Equal(MhBackupRecordVerdict.Apply, Assert.Single(plan.Progress).Verdict);
        Assert.Equal(MhBackupRecordVerdict.Apply, Assert.Single(plan.Favorites).Verdict);
        Assert.True(plan.HasWrites);
    }

    // ------------------------------------------------------------ 身份裁决

    [Fact]
    public void A_local_source_with_the_same_id_type_address_and_account_is_identical()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-emby") }),
            new[] { Local("srv-emby") });

        Assert.Equal(MhBackupServerVerdict.Identical, Assert.Single(plan.Servers).Verdict);
        Assert.Equal(1, plan.IdenticalCount);
        Assert.Empty(plan.ConflictServerNames);
        Assert.Equal(0, plan.ServersToWriteCount);
    }

    [Fact]
    public void Windows_type_spelling_and_address_case_or_trailing_slash_do_not_fake_a_conflict()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-emby", url: "https://EMBY.example.com:8920/base/") }),
            new[] { Local("srv-emby", type: "Emby", primaryUrl: "https://emby.example.com:8920/base") });

        Assert.Equal(MhBackupServerVerdict.Identical, Assert.Single(plan.Servers).Verdict);
    }

    [Fact]
    public void A_different_address_under_the_same_id_is_a_conflict_and_is_never_written()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-emby") }),
            new[] { Local("srv-emby", primaryUrl: "https://other.example.com:8920") });

        MhBackupServerDecision server = Assert.Single(plan.Servers);
        Assert.Equal(MhBackupServerVerdict.Conflict, server.Verdict);
        Assert.Contains("主地址不同", server.Reason);
        Assert.Null(plan.IdRemapping["srv-emby"]);
        Assert.Equal(0, plan.ServersToWriteCount);
        Assert.Equal(new[] { "客厅 Emby" }, plan.ConflictServerNames);
    }

    [Fact]
    public void A_different_account_under_the_same_id_is_a_conflict()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-emby", username: "mh") }),
            new[] { Local("srv-emby", userName: "someone-else") });

        MhBackupServerDecision server = Assert.Single(plan.Servers);
        Assert.Equal(MhBackupServerVerdict.Conflict, server.Verdict);
        Assert.Contains("账号不同", server.Reason);
    }

    [Fact]
    public void A_different_source_type_under_the_same_id_is_a_conflict()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-1", type: "EMBY") }),
            new[] { Local("srv-1", type: "Jellyfin") });

        MhBackupServerDecision server = Assert.Single(plan.Servers);
        Assert.Equal(MhBackupServerVerdict.Conflict, server.Verdict);
        Assert.Contains("类型不同", server.Reason);
    }

    [Fact]
    public void A_null_account_and_an_empty_account_are_not_the_same_account()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-emby", username: "") }),
            new[] { Local("srv-emby", userName: null) });

        Assert.Equal(MhBackupServerVerdict.Conflict, Assert.Single(plan.Servers).Verdict);
    }

    [Fact]
    public void A_local_source_type_local_uses_its_empty_endpoint_list_without_a_false_conflict()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("srv-local", name: "本地文件", type: "LOCAL", url: null, username: null) }),
            new[] { Local("srv-local", type: "Local", primaryUrl: "", userName: null) });

        Assert.Equal(MhBackupServerVerdict.Identical, Assert.Single(plan.Servers).Verdict);
    }

    // ------------------------------------------------------------ 播放记录

    [Fact]
    public void An_older_local_progress_row_is_replaced_by_the_backup_row()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                progress: new List<MhBackupProgressDto> { Progress("srv-emby", updatedAtEpochMs: 2_000) }),
            new[] { Local("srv-emby") },
            localProgress: new[] { new MhBackupLocalProgress("srv-emby", "item-1", 1_000) });

        MhBackupProgressDecision row = Assert.Single(plan.Progress);
        Assert.Equal(MhBackupRecordVerdict.Apply, row.Verdict);
        Assert.Equal("srv-emby", row.LocalServerBackupId);
    }

    [Theory]
    [InlineData(2_000)]
    [InlineData(3_000)]
    public void A_local_progress_row_at_least_as_new_as_the_backup_row_wins(long localUpdated)
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                progress: new List<MhBackupProgressDto> { Progress("srv-emby", updatedAtEpochMs: 2_000) }),
            new[] { Local("srv-emby") },
            localProgress: new[] { new MhBackupLocalProgress("srv-emby", "item-1", localUpdated) });

        Assert.Equal(MhBackupRecordVerdict.SkipNewerLocal, Assert.Single(plan.Progress).Verdict);
        Assert.Equal(0, plan.ProgressToWriteCount);
    }

    [Fact]
    public void Progress_of_a_conflicting_source_is_skipped_not_merely_counted()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                progress: new List<MhBackupProgressDto> { Progress("srv-emby") }),
            new[] { Local("srv-emby", primaryUrl: "https://other.example.com") });

        MhBackupProgressDecision row = Assert.Single(plan.Progress);
        Assert.Equal(MhBackupRecordVerdict.SkipConflict, row.Verdict);
        Assert.Null(row.LocalServerBackupId);
        Assert.Equal(0, plan.ProgressToWriteCount);
    }

    [Fact]
    public void Progress_referencing_a_server_missing_from_the_backup_is_orphaned_and_disclosed()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                progress: new List<MhBackupProgressDto> { Progress("srv-emby"), Progress("srv-ghost", itemId: "item-2") }),
            Array.Empty<MhBackupLocalSource>());

        Assert.Equal(2, plan.Progress.Count);
        Assert.Equal(MhBackupRecordVerdict.Orphan, plan.Progress[1].Verdict);
        Assert.Equal(1, plan.OrphanProgressCount);
        Assert.Equal(1, plan.ProgressToWriteCount);
        Assert.Contains(plan.SummaryLines, line => line.Contains("无法关联") && line.Contains("1"));
    }

    // ------------------------------------------------------------ 收藏与设置

    [Fact]
    public void Favorites_of_a_conflicting_source_are_skipped_while_a_new_source_favorite_applies()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby"), Server("srv-new", name: "书房 Emby", sortOrder: 1) },
                favorites: new List<MhBackupFavoriteDto> { Favorite("srv-emby"), Favorite("srv-new", itemId: "item-2") }),
            new[] { Local("srv-emby", primaryUrl: "https://other.example.com") });

        Assert.Equal(MhBackupRecordVerdict.SkipConflict, plan.Favorites[0].Verdict);
        Assert.Equal(MhBackupRecordVerdict.Apply, plan.Favorites[1].Verdict);
        Assert.Equal(1, plan.FavoritesToWriteCount);
    }

    [Fact]
    public void Favorites_referencing_a_missing_server_are_orphaned_and_counted()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                favorites: new List<MhBackupFavoriteDto> { Favorite("srv-ghost") }),
            Array.Empty<MhBackupLocalSource>());

        Assert.Equal(MhBackupRecordVerdict.Orphan, Assert.Single(plan.Favorites).Verdict);
        Assert.Equal(1, plan.OrphanFavoriteCount);
    }

    [Fact]
    public void Preferences_are_only_restored_in_replace_selected_mode()
    {
        var payload = Payload(
            servers: new List<MhBackupServerDto> { Server("srv-emby") },
            preferences: new MhBackupPreferencesDto { DefaultPlaybackSpeed = 1.5f });

        MhBackupRestorePlan merge = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());
        Assert.True(merge.PreferencesContained);
        Assert.False(merge.PreferencesWillRestore);

        MhBackupRestorePlan replace = MhBackupRestorePlanner.Plan(
            payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.ReplaceSelected);
        Assert.True(replace.PreferencesWillRestore);
        Assert.True(replace.HasWrites);
    }

    // ------------------------------------------------------------ 策略与计划记录

    [Fact]
    public void Replace_selected_overwrites_an_identical_source_and_skips_no_progress_as_newer_local()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby") },
                progress: new List<MhBackupProgressDto> { Progress("srv-emby", updatedAtEpochMs: 2_000) }),
            new[] { Local("srv-emby") },
            MhBackupRestoreStrategy.ReplaceSelected,
            new[] { new MhBackupLocalProgress("srv-emby", "item-1", 9_000) });

        Assert.Equal(MhBackupServerVerdict.Identical, Assert.Single(plan.Servers).Verdict);
        Assert.Equal(1, plan.ServersToWriteCount);
        Assert.Equal(MhBackupRecordVerdict.Apply, Assert.Single(plan.Progress).Verdict);
        Assert.Empty(plan.ConflictServerNames);
    }

    [Fact]
    public void The_plan_record_uses_the_android_field_shape()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby"), Server("srv-new", name: "书房 Emby", sortOrder: 1) },
                progress: new List<MhBackupProgressDto> { Progress("srv-new") }),
            new[] { Local("srv-emby", primaryUrl: "https://other.example.com") });

        MhBackupRestorePlanRecord record = plan.ToRecord();
        Assert.Equal("MERGE", record.Strategy);
        Assert.Empty(record.SkipExistingServerIds);
        Assert.Equal(new[] { "srv-new" }, record.OverwriteServerIds);
        Assert.Equal(new[] { "srv-emby" }, record.ConflictSkippedServerIds);
        Assert.Equal("srv-new", record.IdRemapping["srv-new"]);
        Assert.False(record.IdRemapping.ContainsKey("srv-emby"));
        Assert.False(record.IncludePreferences);
        Assert.Equal(1, record.RestoredProgressCount);
    }

    [Fact]
    public void Planning_does_not_mutate_the_payload_or_the_local_snapshot()
    {
        var payload = OneServerProgressAndFavorite();
        var local = new[] { Local("srv-emby", primaryUrl: "https://other.example.com") };
        var progress = new[] { new MhBackupLocalProgress("srv-emby", "item-1", 5_000) };
        string beforePayload = MhBackupPayloadCodec.Encode(payload);
        string beforeLocal = local[0].BackupId + "|" + local[0].Type + "|" + local[0].PrimaryUrl + "|" + local[0].UserName;

        _ = MhBackupRestorePlanner.Plan(payload, local, MhBackupRestoreStrategy.Merge, progress);

        Assert.Equal(beforePayload, MhBackupPayloadCodec.Encode(payload));
        Assert.Equal(beforeLocal, local[0].BackupId + "|" + local[0].Type + "|" + local[0].PrimaryUrl + "|" + local[0].UserName);
    }

    [Fact]
    public void A_backup_entry_without_a_backup_id_is_a_conflict_and_never_written()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(servers: new List<MhBackupServerDto> { Server("") }),
            Array.Empty<MhBackupLocalSource>());

        MhBackupServerDecision server = Assert.Single(plan.Servers);
        Assert.Equal(MhBackupServerVerdict.Conflict, server.Verdict);
        Assert.Contains("缺少 backupId", server.Reason);
        Assert.Equal(0, plan.ServersToWriteCount);
    }

    [Fact]
    public void Summary_lines_disclose_conflicts_orphans_and_what_preferences_will_do()
    {
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            Payload(
                servers: new List<MhBackupServerDto> { Server("srv-emby"), Server("srv-new", name: "书房 Emby", sortOrder: 1) },
                progress: new List<MhBackupProgressDto> { Progress("srv-new"), Progress("srv-ghost", itemId: "item-2") },
                preferences: new MhBackupPreferencesDto()),
            new[] { Local("srv-emby", primaryUrl: "https://other.example.com") });

        string text = string.Join("\n", plan.SummaryLines);
        Assert.Contains("新增媒体源：1", text);
        Assert.Contains("冲突：1", text);
        Assert.Contains("已跳过", text);
        Assert.Contains("无法关联到备份内媒体源的播放记录：1", text);
        Assert.Contains("合并模式保留本机设置", text);
    }
}
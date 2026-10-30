using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W28「手机导出、电脑读得懂」的 Windows 侧证据：同一份由 Android 生产导出路径
/// （<c>BackupRepository.exportBackup</c>，真 Robolectric Room 库）写出的字节，
/// 这里用 Windows 的生产解析器读回来，断言载荷与 Android 侧
/// <c>BackupAndroidExportRoundTripTest</c> 完全一致；再走一遍恢复规划与写回去向分类，
/// 证明电脑不会假装自己会写服务端能力。</summary>
public sealed class BackupAndroidExportArtifactTests
{
    private const string Password = "跨平台备份-测试口令-2026";

    private static byte[] Artifact() => File.ReadAllBytes(
        Path.Combine(AppContext.BaseDirectory, "assets", "android-export.mhbackup"));

    private static MhBackupPayload Load()
    {
        MhBackupImportResult result = MhBackupSerializer.Import(Artifact(), Password);
        Assert.Equal(MhBackupImportStatus.Ok, result.Status);
        return result.Payload!;
    }

    [Fact]
    public void The_android_export_round_trips_with_its_manifest_intact()
    {
        MhBackupManifest manifest = Load().Manifest!;
        Assert.Equal(MhBackupCrypto.FormatVersion, manifest.FormatVersion);
        Assert.Equal(MhBackupCrypto.MinimumReaderVersion, manifest.MinimumReaderVersion);
        Assert.Equal("android-0.1.0", manifest.AppVersion);
        Assert.Equal(new[] { "servers", "progress", "preferences" }, manifest.IncludedSections);
        Assert.Equal(4, manifest.RecordCounts["servers"]);
        Assert.Equal(2, manifest.RecordCounts["progress"]);
        Assert.Equal(1, manifest.RecordCounts["preferences"]);
        Assert.True(manifest.CreatedAtEpochMs > 0);
    }

    [Fact]
    public void The_servers_arrive_with_android_identities_and_no_device_bound_endpoint()
    {
        IReadOnlyList<MhBackupServerDto> servers = Load().Servers;
        Assert.Equal(4, servers.Count);

        MhBackupServerDto emby = servers.Single(s => s.Type == "EMBY");
        Assert.Equal("srv-emby", emby.BackupId);
        Assert.Equal("Home Emby", emby.Name);
        Assert.Equal("user-A", emby.Username);
        Assert.Equal("客厅", emby.Note);
        Assert.True(emby.IsDefault);
        Assert.Equal(0, emby.SortOrder);
        Assert.Equal("https://media.example:8920/emby/", Assert.Single(emby.Endpoints).Url);

        MhBackupServerDto jellyfin = servers.Single(s => s.Type == "JELLYFIN");
        Assert.Equal("srv-jellyfin", jellyfin.BackupId);
        Assert.Null(jellyfin.Username);
        Assert.False(jellyfin.IsDefault);
        Assert.Equal(1, jellyfin.SortOrder);

        Assert.Equal("Team DAV", servers.Single(s => s.Type == "WEBDAV").Name);

        MhBackupServerDto local = servers.Single(s => s.Type == "LOCAL");
        Assert.Equal("我的本地文件夹", local.Name);
        Assert.Empty(local.Endpoints);
        // 手机上的本地来源 id 不是电脑上的文件路径（Windows 观看记录按路径记账），
        // 所以这份备份里它只是「一个有意义的本地来源」，不是可直接使用的路径。
        Assert.Equal("srv-local", local.BackupId);
    }

    [Fact]
    public void The_playback_records_keep_positions_titles_and_the_owning_source()
    {
        IReadOnlyList<MhBackupProgressDto> progress = Load().Progress;
        Assert.Equal(2, progress.Count);

        MhBackupProgressDto first = progress.Single(p => p.ItemId == "item-001");
        Assert.Equal("srv-emby", first.ServerBackupId);
        Assert.Equal(540_000, first.PositionMs);
        Assert.Equal(1_440_000, first.DurationMs);
        Assert.True(first.IsPaused);
        Assert.Equal(1_790_000_000_000, first.UpdatedAtEpochMs);
        Assert.Equal("第一集", first.ItemTitle);
        Assert.Equal("EPISODE", first.ItemType);

        MhBackupProgressDto second = progress.Single(p => p.ItemId == "item-002");
        Assert.Equal(1_200_000, second.PositionMs);
        Assert.False(second.IsPaused);
    }

    [Fact]
    public void The_preferences_carry_the_playback_choices_the_phone_actually_saved()
    {
        MhBackupPreferencesDto preferences = Load().Preferences!;
        Assert.Equal("MEDIA3", preferences.PlaybackEngineMode);
        Assert.Equal(1.5f, preferences.DefaultPlaybackSpeed, 4);
        Assert.Equal(22, preferences.SubtitleSizeSp);
        Assert.False(preferences.AutoPlayNextEpisode);
        Assert.True(preferences.ShowPlayerInfoOverlay);
        Assert.True(preferences.EnableHardwareDecoding);
        Assert.True(preferences.PreferDirectPlay);
        Assert.Null(preferences.MaxBitrateBps);
        Assert.Equal(unchecked((int)0xFF00FF00), preferences.SubtitleStyle.TextColor);
    }

    [Fact]
    public void The_planner_treats_every_android_source_as_new_and_applies_both_records()
    {
        MhBackupPayload payload = Load();
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.ReplaceSelected);

        Assert.Equal(4, plan.Servers.Count);
        Assert.All(plan.Servers, decision => Assert.Equal(MhBackupServerVerdict.New, decision.Verdict));
        Assert.Equal(2, plan.ProgressToWriteCount);
        Assert.Equal(0, plan.OrphanProgressCount);
        Assert.All(plan.Progress, decision =>
        {
            Assert.Equal(MhBackupRecordVerdict.Apply, decision.Verdict);
            Assert.Equal("srv-emby", decision.LocalServerBackupId);
        });
        Assert.Empty(plan.Favorites);
        Assert.True(plan.PreferencesContained);
        Assert.True(plan.PreferencesWillRestore);
        Assert.Equal("srv-local", plan.IdRemapping["srv-local"]);
        Assert.Equal("srv-emby", plan.IdRemapping["srv-emby"]);
    }

    [Fact]
    public void The_write_outlook_hands_both_records_to_the_server_api_not_to_a_local_path_key()
    {
        MhBackupPayload payload = Load();
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(
            payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.ReplaceSelected);
        MhBackupWriteOutlook outlook = MhBackupWriteOutlooks.Classify(payload, plan);

        Assert.Equal(2, outlook.ProgressTotal);
        Assert.Equal(2, outlook.ProgressServerApi);
        Assert.Equal(0, outlook.ProgressLocalPathKey);
        Assert.Equal(0, outlook.ProgressUnsupportedType);
        Assert.Equal(0, outlook.FavoriteTotal);
        Assert.Equal(0, outlook.Orphan);
        Assert.All(outlook.Targets, target =>
        {
            Assert.Equal(MhBackupRecordKind.Progress, target.Record);
            Assert.Equal(MhBackupWriteKind.ServerApi, target.Kind);
            Assert.Equal("server-api", target.KindCode);
            Assert.Equal("srv-emby", target.ServerBackupId);
            Assert.Contains("需要登录后调用服务端接口", target.Reason);
        });
        string line = Assert.Single(outlook.Lines);
        Assert.Contains("播放记录 2 条写回方式：服务端接口 2 条（需登录）", line);
        Assert.Contains("本机路径键无法映射 0 条", line);
    }

    [Fact]
    public void The_same_file_with_another_password_does_not_open()
    {
        MhBackupImportResult result = MhBackupSerializer.Import(Artifact(), "另一个口令");
        Assert.Equal(MhBackupImportStatus.AuthenticationFailed, result.Status);
    }
}

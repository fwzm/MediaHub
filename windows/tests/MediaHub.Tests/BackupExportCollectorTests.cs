using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Windows 导出采集层：settings.json 的本地来源/偏好 → 与 Android 一致的备份白名单载荷。
/// 这里钉住的是「导出什么、跳过什么、为什么」，不是加密往返（那由容器套件覆盖）。
/// </summary>
public sealed class BackupExportCollectorTests
{
    private static MhBackupLocalServer Server(
        string? name = "客厅 Emby",
        string? type = "Emby",
        string? url = "https://media.example:8921",
        string? user = "user-A",
        string? syncSourceId = null,
        string? displayName = null) =>
        new()
        {
            ServerId = "local-" + name,
            Type = type,
            DisplayName = displayName ?? name,
            Url = url,
            UserName = user,
            SyncSourceId = syncSourceId,
        };

    private static MhBackupLocalPreferences Prefs(
        double speed = 1.0,
        bool autoPlayNext = true,
        bool showInfo = false) =>
        new() { DefaultSpeed = speed, AutoPlayNext = autoPlayNext, ShowDetailedMediaInfo = showInfo };

    [Fact]
    public void TwoServersAreMappedToTheBackupWhitelist()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[]
            {
                Server(name: "客厅 Emby", type: "Emby", url: "https://media.example:8921", user: "user-A", syncSourceId: "sync-1"),
                Server(name: "书房 Jellyfin", type: "Jellyfin", url: "https://jelly.example", user: "user-B", syncSourceId: "sync-2"),
            },
            Prefs(),
            nowEpochMs: 1700000000000);

        Assert.Equal(2, result.Payload.Servers.Count);
        MhBackupServerDto first = result.Payload.Servers[0];
        Assert.Equal("sync-1", first.BackupId);
        Assert.Equal("客厅 Emby", first.Name);
        Assert.Equal("EMBY", first.Type);
        Assert.Equal("user-A", first.Username);
        Assert.True(first.IsDefault);
        Assert.Equal(0, first.SortOrder);
        MhBackupEndpointDto endpoint = Assert.Single(first.Endpoints);
        Assert.Equal("https://media.example:8921", endpoint.Url);
        Assert.True(endpoint.IsPrimary);
        Assert.True(endpoint.Enabled);
        Assert.Equal(0, endpoint.SortOrder);

        MhBackupServerDto second = result.Payload.Servers[1];
        Assert.Equal("JELLYFIN", second.Type);
        Assert.False(second.IsDefault);
        Assert.Equal(1, second.SortOrder);

        Assert.Equal(1700000000000, result.Payload.Manifest!.CreatedAtEpochMs);
        Assert.Equal(new[] { "servers", "preferences" }, result.Payload.Manifest.IncludedSections);
        Assert.Equal(2, result.Payload.Manifest.RecordCounts["servers"]);
        Assert.Equal(1, result.Payload.Manifest.RecordCounts["preferences"]);
        Assert.Empty(result.SkippedServers);
    }

    [Fact]
    public void ABackupIdIsDerivedWhenSyncSourceIdIsMissing()
    {
        MhBackupExportCollectResult first = MhBackupExportCollector.Collect(new[] { Server(syncSourceId: "  ") }, null);
        MhBackupExportCollectResult again = MhBackupExportCollector.Collect(new[] { Server(syncSourceId: "  ") }, null);
        MhBackupExportCollectResult otherAccount = MhBackupExportCollector.Collect(new[] { Server(user: "user-B") }, null);

        string id = first.Payload.Servers[0].BackupId!;
        Assert.StartsWith("win-", id);
        Assert.Equal(20, id.Length);
        Assert.Equal(id, again.Payload.Servers[0].BackupId);
        Assert.NotEqual(id, otherAccount.Payload.Servers[0].BackupId);
    }

    [Fact]
    public void ASourceWithCredentialsInTheUrlIsSkippedWithAReason()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[]
            {
                Server(name: "带凭据", url: "https://media.example/api?api_key=secret"),
                Server(name: "正常", url: "https://ok.example"),
            },
            null);

        Assert.Single(result.Payload.Servers);
        Assert.Equal("正常", result.Payload.Servers[0].Name);
        MhBackupSkippedServer skipped = Assert.Single(result.SkippedServers);
        Assert.Equal("带凭据", skipped.Name);
        Assert.Contains("api_key", skipped.Reason);
        Assert.Contains("不导出", skipped.Reason);
        Assert.Contains(result.Notes, n => n.Contains("1 个来源"));
    }

    [Fact]
    public void ASourceWithUserInfoInTheUrlIsSkipped()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server(name: "带账号", url: "https://user:pass@media.example") },
            null);

        Assert.Empty(result.Payload.Servers);
        MhBackupSkippedServer skipped = Assert.Single(result.SkippedServers);
        Assert.Contains("userinfo", skipped.Reason);
    }

    [Fact]
    public void ALocalSourceIsExportedWithoutAnEndpoint()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server(name: "本机影片", type: "local", url: null, user: null) },
            null);

        MhBackupServerDto server = Assert.Single(result.Payload.Servers);
        Assert.Equal("LOCAL", server.Type);
        Assert.Null(server.Username);
        Assert.Empty(server.Endpoints);
    }

    [Fact]
    public void AnUnknownTypeIsSkipped()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(new[] { Server(type: "PlexX") }, null);

        Assert.Empty(result.Payload.Servers);
        Assert.Contains("PlexX", Assert.Single(result.SkippedServers).Reason);
    }

    [Fact]
    public void ADuplicateBackupIdIsSkipped()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[]
            {
                Server(name: "甲", url: "https://a.example", syncSourceId: "dup"),
                Server(name: "乙", url: "https://b.example", syncSourceId: "dup"),
            },
            null);

        Assert.Equal("甲", Assert.Single(result.Payload.Servers).Name);
        Assert.Contains("dup", Assert.Single(result.SkippedServers).Reason);
    }

    [Fact]
    public void ABlankNameFallsBackToTheHost()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server(name: null, displayName: "   ", url: "https://media.example:8920") },
            null);

        Assert.Equal("media.example", Assert.Single(result.Payload.Servers).Name);
    }

    [Fact]
    public void ABlankUrlWithNoNameIsSkipped()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server(name: null, displayName: "  ", url: "  ") },
            null);

        Assert.Empty(result.Payload.Servers);
        Assert.Contains("名称", Assert.Single(result.SkippedServers).Reason);
    }

    [Fact]
    public void AnEmptyUrlForANetworkSourceIsSkipped()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(new[] { Server(url: "") }, null);

        Assert.Empty(result.Payload.Servers);
        Assert.Contains("地址", Assert.Single(result.SkippedServers).Reason);
    }

    [Fact]
    public void PreferencesMapOnlyTheEquivalentFields()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server() },
            Prefs(speed: 1.5, autoPlayNext: false, showInfo: true));

        MhBackupPreferencesDto prefs = result.Payload.Preferences!;
        Assert.Equal(1.5f, prefs.DefaultPlaybackSpeed);
        Assert.False(prefs.AutoPlayNextEpisode);
        Assert.True(prefs.ShowPlayerInfoOverlay);
        // 单位不同的字段不猜测：保持 Android 默认值而不是把 Windows 像素值塞进 sp。
        Assert.Equal(18, prefs.SubtitleSizeSp);
        Assert.Equal(1f, prefs.SubtitleStyle.TextScale);
        Assert.Contains(result.Notes, n => n.Contains("单位不同"));
    }

    [Fact]
    public void AnInvalidSpeedFallsBackToOneAndIsNoted()
    {
        MhBackupExportCollectResult zero = MhBackupExportCollector.Collect(new[] { Server() }, Prefs(speed: 0));
        MhBackupExportCollectResult nan = MhBackupExportCollector.Collect(new[] { Server() }, Prefs(speed: double.NaN));

        Assert.Equal(1f, zero.Payload.Preferences!.DefaultPlaybackSpeed);
        Assert.Equal(1f, nan.Payload.Preferences!.DefaultPlaybackSpeed);
        Assert.Contains(zero.Notes, n => n.Contains("默认倍速"));
        Assert.Contains(nan.Notes, n => n.Contains("默认倍速"));
    }

    [Fact]
    public void NoPreferencesMeansNoPreferencesSection()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(new[] { Server() }, null);

        Assert.Null(result.Payload.Preferences);
        Assert.Equal(new[] { "servers" }, result.Payload.Manifest!.IncludedSections);
        Assert.Equal(new[] { "servers" }, result.Payload.Manifest.RecordCounts.Keys);
    }

    [Fact]
    public void TheCollectedPayloadSurvivesTheContainerRoundTrip()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server(syncSourceId: "sync-1") },
            Prefs(speed: 1.25));
        var password = "导出-测试口令-2026".ToCharArray();

        byte[] file = MhBackupSerializer.Export(result.Payload, password);
        string text = System.Text.Encoding.UTF8.GetString(file);
        Assert.DoesNotContain("password", text, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("token", text, StringComparison.OrdinalIgnoreCase);

        MhBackupImportResult imported = MhBackupSerializer.Import(file, password);
        Assert.True(imported.IsOk, imported.Reason);
        MhBackupServerDto server = Assert.Single(imported.Payload!.Servers);
        Assert.Equal("sync-1", server.BackupId);
        Assert.Equal("https://media.example:8921", Assert.Single(server.Endpoints).Url);
        Assert.Equal(1.25f, imported.Payload.Preferences!.DefaultPlaybackSpeed);
        Assert.Equal(result.Payload.Manifest!.RecordCounts, imported.Payload.Manifest!.RecordCounts);
    }

    [Fact]
    public void HistoryEntriesAreCountedButNeverGuessedIntoThePayload()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server() },
            Prefs(),
            skippedHistoryEntries: 7);

        Assert.Equal(7, result.SkippedHistoryEntries);
        Assert.Empty(result.Payload.Progress);
        Assert.Contains(result.Notes, n => n.Contains("7 条"));
    }

    [Fact]
    public void MoreThanTheServerLimitIsRejectedInsteadOfTruncated()
    {
        var servers = new List<MhBackupLocalServer>();
        for (int i = 0; i <= MhBackupExportCollector.MaxLocalServers; i++)
        {
            servers.Add(Server(name: "来源 " + i, url: "https://s" + i + ".example", syncSourceId: "sync-" + i, user: null));
        }

        MhBackupExportException error = Assert.Throws<MhBackupExportException>(
            () => MhBackupExportCollector.Collect(servers, null));
        Assert.Contains("1000", error.Reason);
    }

    [Fact]
    public void TheCollectorNeverInventsCredentialsOrDeviceIdentity()
    {
        MhBackupExportCollectResult result = MhBackupExportCollector.Collect(
            new[] { Server() }, Prefs());
        string json = MhBackupPayloadCodec.Encode(result.Payload);

        Assert.DoesNotContain("deviceId", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("password", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("token", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("cookie", json, StringComparison.OrdinalIgnoreCase);
    }
}

using System.Text.Json;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 还原落库（W19）：把计划写成 settings.json，但只写本机能表达的部分。
/// </summary>
public sealed class BackupRestoreApplierTests
{
    private const string Password = "还原落库-测试口令-2026";

    [Fact]
    public void ANewSourceBecomesAPersistedServerWithItsBackupIdentity()
    {
        MhBackupPayload payload = Payload();
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, "{\n  \"Servers\": []\n}\n");

        Assert.True(result.Changed);
        Assert.Equal(1, result.AddedCount);
        Assert.Equal(0, result.SkippedCount);
        using JsonDocument doc = JsonDocument.Parse(result.SettingsJson);
        JsonElement server = doc.RootElement.GetProperty("Servers")[0];
        Assert.Equal("srv-1", server.GetProperty("ServerId").GetString());
        Assert.Equal("Emby", server.GetProperty("Type").GetString());
        Assert.Equal("Test Emby", server.GetProperty("DisplayName").GetString());
        Assert.Equal("https://media.example:8920", server.GetProperty("Url").GetString());
        Assert.Equal("user-A", server.GetProperty("UserName").GetString());
        Assert.Equal("srv-1", server.GetProperty("SyncSourceId").GetString());
        Assert.Equal(0, server.GetProperty("SyncRecordRevision").GetInt64());
    }

    [Fact]
    public void UnknownSettingsKeysAndExistingSourcesSurviveTheWrite()
    {
        const string settings = "{\n  \"Theme\": \"Dark\",\n  \"DefaultVolume\": 0.4,\n  \"Servers\": [\n    {\n      \"ServerId\": \"keep-me\",\n      \"Type\": \"Jellyfin\",\n      \"DisplayName\": \"旧来源\",\n      \"Url\": \"http://old:8096\"\n    }\n  ]\n}\n";
        MhBackupPayload payload = Payload();
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, settings);

        using JsonDocument doc = JsonDocument.Parse(result.SettingsJson);
        Assert.Equal("Dark", doc.RootElement.GetProperty("Theme").GetString());
        Assert.Equal(0.4, doc.RootElement.GetProperty("DefaultVolume").GetDouble(), 6);
        JsonElement servers = doc.RootElement.GetProperty("Servers");
        Assert.Equal(2, servers.GetArrayLength());
        Assert.Equal("keep-me", servers[0].GetProperty("ServerId").GetString());
        Assert.Equal("旧来源", servers[0].GetProperty("DisplayName").GetString());
    }

    [Fact]
    public void AnIdAlreadyTakenOnThisMachineIsNotStolen()
    {
        const string settings = "{\"Servers\":[{\"ServerId\":\"srv-1\",\"Type\":\"Jellyfin\",\"DisplayName\":\"本机同名 id\",\"Url\":\"http://elsewhere:8096\"}]}";
        MhBackupPayload payload = Payload();
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, settings);

        using JsonDocument doc = JsonDocument.Parse(result.SettingsJson);
        JsonElement servers = doc.RootElement.GetProperty("Servers");
        Assert.Equal(2, servers.GetArrayLength());
        Assert.Equal("srv-1", servers[0].GetProperty("ServerId").GetString());
        Assert.Equal("sync:srv-1#2", servers[1].GetProperty("ServerId").GetString());
        Assert.Equal("http://elsewhere:8096", servers[0].GetProperty("Url").GetString());
    }

    [Fact]
    public void MergeModeLeavesAnIdenticalLocalSourceAloneAndSaysSo()
    {
        MhBackupPayload payload = Payload();
        var local = new[] { new MhBackupLocalSource { BackupId = "srv-1", Type = "EMBY", PrimaryUrl = "https://media.example:8920", UserName = "user-A" } };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, local, MhBackupRestoreStrategy.Merge);

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, "{\"Servers\":[{\"ServerId\":\"srv-1\",\"Type\":\"Emby\",\"DisplayName\":\"本机\",\"Url\":\"https://media.example:8920\"}]}");

        Assert.False(result.Changed);
        Assert.Equal(0, result.AddedCount);
        Assert.Contains(result.Notes, x => x.Contains("保留本机来源", StringComparison.Ordinal));
    }

    [Fact]
    public void MergeModeSkipsAConflictingSourceInsteadOfOverwritingIt()
    {
        MhBackupPayload payload = Payload();
        var local = new[] { new MhBackupLocalSource { BackupId = "srv-1", Type = "EMBY", PrimaryUrl = "https://other.example:8920", UserName = "user-A" } };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, local, MhBackupRestoreStrategy.Merge);
        const string settings = "{\"Servers\":[{\"ServerId\":\"srv-1\",\"Type\":\"Emby\",\"DisplayName\":\"本机\",\"Url\":\"https://other.example:8920\"}]}";

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, settings);

        Assert.False(result.Changed);
        Assert.Equal(0, result.AddedCount);
        Assert.Equal(0, result.SkippedCount);
        Assert.Contains(result.Notes, x => x.Contains("冲突已按合并模式跳过", StringComparison.Ordinal));
        Assert.Contains("other.example", result.SettingsJson, StringComparison.Ordinal);
    }

    [Fact]
    public void ReplaceSelectedUpdatesTheExistingEntryAndKeepsItsId()
    {
        MhBackupPayload payload = Payload();
        var local = new[] { new MhBackupLocalSource { BackupId = "srv-1", Type = "EMBY", PrimaryUrl = "https://media.example:8920", UserName = "user-A" } };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, local, MhBackupRestoreStrategy.ReplaceSelected);
        const string settings = "{\"Servers\":[{\"ServerId\":\"srv-1\",\"Type\":\"Emby\",\"DisplayName\":\"旧名字\",\"Url\":\"https://media.example:8920\",\"UserName\":\"user-A\"}]}";

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, settings);

        Assert.True(result.Changed);
        Assert.Equal(0, result.AddedCount);
        Assert.Equal(1, result.ReplacedCount);
        using JsonDocument doc = JsonDocument.Parse(result.SettingsJson);
        JsonElement server = doc.RootElement.GetProperty("Servers")[0];
        Assert.Equal("srv-1", server.GetProperty("ServerId").GetString());
        Assert.Equal("Test Emby", server.GetProperty("DisplayName").GetString());
        Assert.Equal("srv-1", server.GetProperty("SyncSourceId").GetString());
        Assert.Equal(1, doc.RootElement.GetProperty("Servers").GetArrayLength());
    }

    [Fact]
    public void ASourceTypeThisBuildCannotOpenIsSkippedWithItsReason()
    {
        var payload = new MhBackupPayload
        {
            Manifest = Manifest(1, "servers"),
            Servers =
            {
                new MhBackupServerDto
                {
                    BackupId = "srv-1", Name = "Plex 来源", Type = "PLEX", SortOrder = 0,
                    Endpoints = { new MhBackupEndpointDto { Url = "https://plex.example:32400", IsPrimary = true, Enabled = true } },
                },
            },
        };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, "{\"Servers\":[]}");

        Assert.False(result.Changed);
        Assert.Equal(0, result.AddedCount);
        Assert.Single(result.SkippedServers);
        Assert.Contains("Plex", result.SkippedServers[0].Reason, StringComparison.Ordinal);
        Assert.DoesNotContain("plex.example", result.SettingsJson, StringComparison.Ordinal);
    }

    [Fact]
    public void ALocalFolderSourceFromAnotherMachineIsNeverWritten()
    {
        var payload = new MhBackupPayload
        {
            Manifest = Manifest(1, "servers"),
            Servers =
            {
                new MhBackupServerDto
                {
                    BackupId = "local-1", Name = "本机文件夹", Type = "LOCAL", IsDefault = true, SortOrder = 0,
                    Endpoints = { },
                },
            },
        };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, "{\"Servers\":[]}");

        Assert.False(result.Changed);
        Assert.Single(result.SkippedServers);
        Assert.Contains("本地文件夹", result.SkippedServers[0].Reason, StringComparison.Ordinal);
    }

    [Fact]
    public void PreferencesAreRestoredOnlyWhenTheStrategySaysReplaceSelected()
    {
        MhBackupPayload payload = Payload(withPreferences: true);
        MhBackupRestorePlan merge = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.Merge);
        MhBackupRestorePlan replace = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>(), MhBackupRestoreStrategy.ReplaceSelected);
        const string settings = "{\"Servers\":[],\"DefaultVolume\":0.7,\"AutoPlayNext\":true,\"ShowDetailedMediaInfo\":false}";

        MhBackupRestoreApplyResult merged = MhBackupRestoreApplier.Apply(payload, merge, settings);
        MhBackupRestoreApplyResult replaced = MhBackupRestoreApplier.Apply(payload, replace, settings);

        Assert.False(merged.PreferencesRestored);
        Assert.True(replaced.PreferencesRestored);
        using JsonDocument doc = JsonDocument.Parse(replaced.SettingsJson);
        Assert.Equal(1.5, doc.RootElement.GetProperty("DefaultSpeed").GetDouble(), 6);
        Assert.False(doc.RootElement.GetProperty("AutoPlayNext").GetBoolean());
        Assert.True(doc.RootElement.GetProperty("ShowDetailedMediaInfo").GetBoolean());
        Assert.Equal(0.7, doc.RootElement.GetProperty("DefaultVolume").GetDouble(), 6);
    }

    [Fact]
    public void WhenNothingChangesTheOriginalBytesAreReturnedUntouched()
    {
        const string settings = "{ \"Servers\" : [ ] , \"Theme\" : \"Dark\" }";
        var payload = new MhBackupPayload { Manifest = Manifest(0) };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        MhBackupRestoreApplyResult result = MhBackupRestoreApplier.Apply(payload, plan, settings);

        Assert.False(result.Changed);
        Assert.Equal(settings, result.SettingsJson);
    }

    [Fact]
    public void ASecondApplyOfTheSameBackupWritesNothing()
    {
        MhBackupPayload payload = Payload();
        MhBackupRestorePlan first = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());
        MhBackupRestoreApplyResult applied = MhBackupRestoreApplier.Apply(payload, first, "{\"Servers\":[]}");

        var local = MhBackupLocalData.ReadServers(applied.SettingsJson)
            .Select(x => new MhBackupLocalSource { BackupId = MhBackupExportCollector.DeriveBackupId(x), Type = x.Type, PrimaryUrl = x.Url, UserName = x.UserName })
            .ToList();
        MhBackupRestorePlan second = MhBackupRestorePlanner.Plan(payload, local);
        MhBackupRestoreApplyResult again = MhBackupRestoreApplier.Apply(payload, second, applied.SettingsJson);

        Assert.False(again.Changed);
        Assert.Equal(0, again.AddedCount);
    }

    [Fact]
    public void SettingsThatAreNotAnObjectAreRejectedInsteadOfBeingReplaced()
    {
        var payload = new MhBackupPayload { Manifest = Manifest(0) };
        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, Array.Empty<MhBackupLocalSource>());

        Assert.Throws<FormatException>(() => MhBackupRestoreApplier.Apply(payload, plan, "[1,2,3]"));
        Assert.Throws<FormatException>(() => MhBackupRestoreApplier.Apply(payload, plan, "{\"Servers\":42}"));
        Assert.Throws<FormatException>(() => MhBackupRestoreApplier.Apply(payload, plan, "not json"));
    }

    internal static MhBackupManifest Manifest(int recordCount, params string[] sections)
    {
        List<string> included = sections.Length == 0 ? new List<string> { "servers" } : sections.ToList();
        var counts = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (string section in included)
        {
            counts[section] = section == "servers" ? recordCount : 1;
        }

        return new(
            FormatVersion: 2,
            MinimumReaderVersion: 2,
            AppVersion: "0.1.0-alpha.1",
            CreatedAtEpochMs: 1700000000000,
            IncludedSections: included,
            RecordCounts: counts);
    }

    internal static MhBackupPayload Payload(bool withPreferences = false)
    {
        string[] sections = withPreferences ? new[] { "servers", "preferences" } : new[] { "servers" };
        return new MhBackupPayload
        {
            Manifest = Manifest(1, sections),
            Servers =
            {
                new MhBackupServerDto
                {
                    BackupId = "srv-1",
                    Name = "Test Emby",
                    Type = "EMBY",
                    Username = "user-A",
                    IsDefault = true,
                    SortOrder = 0,
                    Endpoints =
                    {
                        new MhBackupEndpointDto { Name = "主线路", Url = "https://media.example:8920", IsPrimary = true, Enabled = true, SortOrder = 0 },
                    },
                },
            },
            Preferences = withPreferences
                ? new MhBackupPreferencesDto
                {
                    DefaultPlaybackSpeed = 1.5f,
                    AutoPlayNextEpisode = false,
                    ShowPlayerInfoOverlay = true,
                }
                : null,
        };
    }

}

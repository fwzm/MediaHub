using System.Diagnostics;
using System.Text;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W25「电脑导出、手机导入」的 Windows 侧证据：同一份由 <c>MediaHub.BackupCli export</c> 真写出的字节，
/// 这里再读回来，断言载荷语义与 Android 侧 <c>BackupWindowsCliFileTest</c> 完全一致。
/// 另加一条进程级断言：命令行的 stdout 在被重定向时必须是 UTF-8，否则脚本读不了 --json。
/// </summary>
public sealed class BackupWindowsCliArtifactTests
{
    private const string Password = "跨平台备份-测试口令-2026";

    private static byte[] Artifact() => File.ReadAllBytes(
        Path.Combine(AppContext.BaseDirectory, "assets", "windows-cli-export.mhbackup"));

    private static MhBackupPayload Load()
    {
        MhBackupImportResult result = MhBackupSerializer.Import(Artifact(), Password);
        Assert.Equal(MhBackupImportStatus.Ok, result.Status);
        return result.Payload!;
    }

    [Fact]
    public void The_computer_export_round_trips_with_its_manifest_intact()
    {
        MhBackupPayload payload = Load();
        MhBackupManifest manifest = payload.Manifest!;
        Assert.Equal(MhBackupCrypto.FormatVersion, manifest.FormatVersion);
        Assert.Equal(MhBackupCrypto.MinimumReaderVersion, manifest.MinimumReaderVersion);
        Assert.Equal("windows-0.1.0", manifest.AppVersion);
        Assert.Equal(new[] { "servers", "preferences" }, manifest.IncludedSections);
        Assert.Equal(4, manifest.RecordCounts["servers"]);
        Assert.Equal(1, manifest.RecordCounts["preferences"]);
        Assert.True(manifest.CreatedAtEpochMs > 0);
    }

    [Fact]
    public void The_servers_keep_their_identities_and_the_blank_account_stays_no_account()
    {
        IReadOnlyList<MhBackupServerDto> servers = Load().Servers!;
        Assert.Equal(4, servers.Count);

        MhBackupServerDto emby = servers.Single(s => s.Type == "EMBY");
        Assert.Equal("sync-abc", emby.BackupId);
        Assert.Equal("Home Emby", emby.Name);
        Assert.Equal("user-A", emby.Username);
        Assert.True(emby.IsDefault);
        Assert.Equal(0, emby.SortOrder);
        MhBackupEndpointDto endpoint = Assert.Single(emby.Endpoints!);
        Assert.Equal("https://media.example:8920/emby/", endpoint.Url);
        Assert.True(endpoint.IsPrimary);
        Assert.True(endpoint.Enabled);

        MhBackupServerDto jellyfin = servers.Single(s => s.Type == "JELLYFIN");
        Assert.Null(jellyfin.Username);
        Assert.Equal("Jelly", jellyfin.Name);
        Assert.Equal(20, jellyfin.BackupId!.Length);
        Assert.StartsWith("win-", jellyfin.BackupId);

        MhBackupServerDto webdav = servers.Single(s => s.Type == "WEBDAV");
        Assert.Equal("Team DAV", webdav.Name);
        Assert.Null(webdav.Username);
        Assert.Equal("https://dav.example/dav/films", Assert.Single(webdav.Endpoints!).Url);
    }

    [Fact]
    public void The_local_folder_source_carries_no_endpoint()
    {
        MhBackupServerDto local = Load().Servers!.Single(s => s.Type == "LOCAL");
        Assert.Equal("我的本地文件夹", local.Name);
        Assert.Empty(local.Endpoints!);
        Assert.Null(local.Username);
    }

    [Fact]
    public void The_preferences_keep_only_the_fields_with_real_equivalents()
    {
        MhBackupPreferencesDto? preferences = Load().Preferences;
        Assert.NotNull(preferences);
        Assert.Equal(1.5f, preferences!.DefaultPlaybackSpeed, 4);
        Assert.False(preferences.AutoPlayNextEpisode);
        Assert.True(preferences.ShowPlayerInfoOverlay);
        Assert.Equal(18, preferences.SubtitleSizeSp);
    }

    [Fact]
    public void The_computer_export_does_not_smuggle_progress_or_favorites()
    {
        MhBackupPayload payload = Load();
        Assert.Empty(payload.Progress!);
        Assert.Empty(payload.Favorites!);
    }

    [Fact]
    public void The_same_file_with_another_password_does_not_open()
    {
        MhBackupImportResult result = MhBackupSerializer.Import(Artifact(), "另一个口令");
        Assert.Equal(MhBackupImportStatus.AuthenticationFailed, result.Status);
    }

    [Fact]
    public void The_command_line_writes_utf8_when_its_output_is_redirected()
    {
        (int exitCode, string stdout, _) = RunCli("--help");
        Assert.Equal(0, exitCode);
        Assert.DoesNotContain('\uFFFD', stdout);
        Assert.Contains("用法：", stdout);
        Assert.Contains("export --settings", stdout);
    }

    private static (int ExitCode, string StdOut, string StdErr) RunCli(params string[] arguments)
    {
        var psi = new ProcessStartInfo(ResolveDotnet())
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            StandardOutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
            StandardErrorEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
        };
        psi.ArgumentList.Add(ResolveCliDll());
        foreach (string argument in arguments) psi.ArgumentList.Add(argument);

        using var process = Process.Start(psi)!;
        string stdout = process.StandardOutput.ReadToEnd();
        string stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();
        return (process.ExitCode, stdout, stderr);
    }

    private static string ResolveDotnet()
    {
        string? host = Environment.GetEnvironmentVariable("DOTNET_HOST_PATH");
        return !string.IsNullOrWhiteSpace(host) && File.Exists(host) ? host : "dotnet";
    }

    private static string ResolveCliDll()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (int depth = 0; depth < 8 && directory is not null; depth++, directory = directory.Parent)
        {
            string candidate = Path.Combine(directory.FullName, "src", "MediaHub.BackupCli");
            if (!Directory.Exists(candidate)) continue;
            string bin = Path.Combine(candidate, "bin");
            Assert.True(Directory.Exists(bin), "还没有构建过 MediaHub.BackupCli：" + bin);
            string[] dlls = Directory.GetFiles(bin, "MediaHub.BackupCli.dll", SearchOption.AllDirectories);
            Assert.True(dlls.Length > 0, "没有找到 MediaHub.BackupCli.dll：" + bin);
            return dlls.OrderByDescending(File.GetLastWriteTimeUtc).First();
        }
        throw new InvalidOperationException("从测试输出目录找不到 windows 仓库根：" + AppContext.BaseDirectory);
    }
}

using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Backup;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// 备份命令行（export / import 预览）的契约测试。
/// </summary>
/// <remarks>
/// 直接调用 MhBackupCliCommand.Run，用 StringWriter 收输出、用字典当环境变量，因此不需要真起进程；
/// 文件都放在每例独立的临时目录里，跑完删除。
/// </remarks>
public sealed class BackupCliTests : IDisposable
{
    private const string Password = "跨平台备份-命令行口令-2026";
    private const string PasswordVariable = "MH_TEST_BACKUP_PASSWORD";

    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "mh-backup-cli-" + Guid.NewGuid().ToString("N"));

    public BackupCliTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // 临时目录清理失败不影响断言结论。
        }
    }

    [Fact]
    public void ExportWritesAContainerThatImportCanPreview()
    {
        string settings = WriteSettings(
            DefaultServer(),
            new { ServerId = "srv-2", Type = "Jellyfin", DisplayName = "书房", Url = "https://jelly.example", UserName = (string?)"user-B", SyncSourceId = (string?)"sync-9" },
            new { ServerId = "srv-3", Type = "Kodi", DisplayName = "Kodi", Url = "https://kodi.example", UserName = (string?)null, SyncSourceId = (string?)null });
        string history = Path.Combine(_directory, "history.json");
        File.WriteAllText(history, JsonSerializer.Serialize(new object[]
        {
            new { MediaPath = "D:/media/a.mkv", Title = "A", PositionTicks = 0L, DurationTicks = 100L },
            new { MediaPath = "D:/media/b.mkv", Title = "B", PositionTicks = 60_000_000L, DurationTicks = 600_000_000L },
        }));

        string backup = Path.Combine(_directory, "windows.mhbackup");
        int exit = Run(
            out string output,
            out string error,
            "export", "--settings", settings, "--history", history,
            "--out", backup, "--password-env", PasswordVariable);

        Assert.Equal(0, exit);
        Assert.True(File.Exists(backup));
        Assert.Contains("已导出 2 个来源", output);
        Assert.Contains("跳过 Kodi", output);
        Assert.Contains("不在备份格式的已知类型里", output);
        Assert.Contains("已跳过 1 条本机观看进度", output);
        Assert.Equal(string.Empty, error);

        int preview = Run(out string previewOutput, out _, "import", "--file", backup, "--settings", settings);

        Assert.Equal(0, preview);
        Assert.Contains("预览：不会写入任何数据", previewOutput);
        // 同一台机器导出再预览：两个来源都已在 settings.json 里，应判为「已有同源」而不是「新增」，
        // 否则还原会重复写入。这一条同时钉住导出与预览用同一个身份函数。
        Assert.Contains("新增媒体源：0", previewOutput);
        Assert.Contains("已有同源：2（保留本机）", previewOutput);
    }

    [Fact]
    public void ExportWithoutAPasswordIsAUsageErrorAndWritesNothing()
    {
        string settings = WriteSettings(DefaultServer());
        string backup = Path.Combine(_directory, "windows.mhbackup");

        int exit = RunWithoutPassword(out _, out string error, "export", "--settings", settings, "--out", backup);

        Assert.Equal(MhBackupCliCommand.ExitUsage, exit);
        Assert.Contains("没有提供备份口令", error);
        Assert.False(File.Exists(backup));
    }

    [Fact]
    public void ExportRefusesWhenNothingCanBeExported()
    {
        string settings = WriteSettings(
            new { ServerId = "srv-3", Type = "Kodi", DisplayName = "Kodi", Url = "https://kodi.example", UserName = (string?)null, SyncSourceId = (string?)null });

        int exit = Run(out _, out string error, "export", "--settings", settings, "--out", Path.Combine(_directory, "windows.mhbackup"));

        Assert.Equal(MhBackupCliCommand.ExitExportRejected, exit);
        Assert.Contains("没有可导出的来源", error);
    }

    [Fact]
    public void ExportSkipsACredentialBearingAddressInsteadOfRedactingIt()
    {
        string settings = WriteSettings(
            new { ServerId = "srv-1", Type = "Emby", DisplayName = "带密钥", Url = "https://media.example?api_key=abc", UserName = (string?)"user-A", SyncSourceId = (string?)null },
            new { ServerId = "srv-2", Type = "Emby", DisplayName = "正常", Url = "https://ok.example", UserName = (string?)"user-B", SyncSourceId = (string?)null });
        string backup = Path.Combine(_directory, "windows.mhbackup");

        int exit = Run(out string output, out _, "export", "--settings", settings, "--out", backup);

        Assert.Equal(0, exit);
        Assert.Contains("已导出 1 个来源", output);
        Assert.Contains("带密钥", output);
        Assert.Contains("查询参数", output);
    }

    [Fact]
    public void ImportPreviewTouchesNeitherSettingsNorTheDirectory()
    {
        string settings = WriteSettings(DefaultServer());
        string backup = Path.Combine(_directory, "windows.mhbackup");
        Assert.Equal(0, Run(out _, out _, "export", "--settings", settings, "--out", backup));

        byte[] before = File.ReadAllBytes(settings);
        string[] filesBefore = SortedFiles();

        int exit = Run(out string output, out _, "import", "--file", backup, "--settings", settings);

        Assert.Equal(0, exit);
        Assert.Contains("不会写入任何数据", output);
        Assert.Equal(before, File.ReadAllBytes(settings));
        Assert.Equal(filesBefore, SortedFiles());
    }

    [Fact]
    public void ImportWithAWrongPasswordFailsWithItsOwnExitCode()
    {
        string backup = ExportDefault();

        int exit = RunWithPassword("完全不同的口令", out _, out string error, "import", "--file", backup);

        Assert.Equal(MhBackupCliCommand.ExitAuthenticationFailed, exit);
        Assert.Contains("密码不正确", error);
    }

    [Fact]
    public void ImportOfAPlainTextFileIsNotABackup()
    {
        string file = Path.Combine(_directory, "notes.txt");
        File.WriteAllText(file, "这不是一个 MediaHub 备份文件");

        int exit = Run(out _, out string error, "import", "--file", file);

        Assert.Equal(MhBackupCliCommand.ExitNotABackupFile, exit);
        Assert.Contains("找不到 MHBK 标记", error);
    }

    [Fact]
    public void ImportOfATamperedContainerIsCorrupted()
    {
        string backup = ExportDefault();
        JsonNode root = JsonNode.Parse(File.ReadAllText(backup))!;
        root["ciphertextB64"] = "AAAA";
        File.WriteAllText(backup, root.ToJsonString());

        int exit = Run(out _, out string error, "import", "--file", backup);

        Assert.Equal(MhBackupCliCommand.ExitCorrupted, exit);
        Assert.Contains("备份", error);
    }

    [Fact]
    public void ImportOfANewerOuterVersionIsVersionTooNew()
    {
        string backup = ExportDefault();
        JsonNode root = JsonNode.Parse(File.ReadAllText(backup))!;
        root["formatVersion"] = 3;
        File.WriteAllText(backup, root.ToJsonString());

        int exit = Run(out _, out string error, "import", "--file", backup);

        Assert.Equal(MhBackupCliCommand.ExitVersionTooNew, exit);
        Assert.Contains("版本", error);
    }

    [Fact]
    public void ImportApplyIsExplicitlyUnsupported()
    {
        string backup = ExportDefault();

        int exit = Run(out _, out string error, "import", "--file", backup, "--apply");

        Assert.Equal(MhBackupCliCommand.ExitUsage, exit);
        Assert.Contains("--apply 尚未实现", error);
    }

    [Fact]
    public void AJsonExportIsMachineReadableAndContainsNoSecret()
    {
        string settings = WriteSettings(DefaultServer());
        string backup = Path.Combine(_directory, "windows.mhbackup");

        int exit = Run(out string output, out _, "export", "--settings", settings, "--out", backup, "--json");

        Assert.Equal(0, exit);
        JsonNode report = JsonNode.Parse(output.Trim())!;
        Assert.True(report["ok"]!.GetValue<bool>());
        Assert.Equal(1, report["servers"]!.GetValue<int>());
        Assert.Equal(new FileInfo(backup).Length, report["bytes"]!.GetValue<long>());
        Assert.Equal(64, report["sha256"]!.GetValue<string>().Length);
        Assert.DoesNotContain(Password, File.ReadAllText(backup));
    }

    [Fact]
    public void ImportWithoutSettingsPreviewsEverySourceAsNew()
    {
        string backup = ExportDefault();

        int exit = Run(out string output, out _, "import", "--file", backup, "--json");

        Assert.Equal(0, exit);
        JsonNode report = JsonNode.Parse(output.Trim())!;
        Assert.Equal(1, report["newServers"]!.GetValue<int>());
        Assert.Equal("MERGE", report["strategy"]!.GetValue<string>());
        // 本机没有任何来源时，备份里的 1 个来源确实会被写入。
        Assert.True(report["hasWrites"]!.GetValue<bool>());
    }

    [Fact]
    public void UnknownCommandAndMissingArgumentsAreUsageErrors()
    {
        Assert.Equal(MhBackupCliCommand.ExitUsage, Run(out _, out string unknown, "frobnicate"));
        Assert.Contains("未知命令", unknown);

        Assert.Equal(MhBackupCliCommand.ExitUsage, Run(out _, out string missing, "export", "--settings", "x"));
        Assert.Contains("--out", missing);

        Assert.Equal(0, Run(out string help, out _, "--help"));
        Assert.Contains("--password-env", help);
        Assert.Contains("不接受命令行参数", help);
    }

    private string ExportDefault()
    {
        string settings = WriteSettings(DefaultServer());
        string backup = Path.Combine(_directory, "windows.mhbackup");
        Assert.Equal(0, Run(out _, out _, "export", "--settings", settings, "--out", backup));
        return backup;
    }

    private string[] SortedFiles() => Directory.GetFiles(_directory)
        .OrderBy(path => path, StringComparer.Ordinal)
        .ToArray();

    private static object DefaultServer() => new
    {
        ServerId = "srv-1",
        Type = "Emby",
        DisplayName = "客厅",
        Url = "https://media.example:8920",
        UserName = (string?)"user-A",
        SyncSourceId = (string?)null,
    };

    private string WriteSettings(params object[] servers)
    {
        string path = Path.Combine(_directory, "settings.json");
        File.WriteAllText(path, JsonSerializer.Serialize(new
        {
            DefaultSpeed = 1.5,
            AutoPlayNext = false,
            ShowDetailedMediaInfo = true,
            Servers = servers,
        }));
        return path;
    }

    private int Run(out string output, out string error, params string[] args)
        => Execute(Password, out output, out error, args);

    private int RunWithPassword(string password, out string output, out string error, params string[] args)
        => Execute(password, out output, out error, args);

    private int RunWithoutPassword(out string output, out string error, params string[] args)
        => Execute(null, out output, out error, args);

    private static int Execute(string? password, out string output, out string error, string[] args)
    {
        var outWriter = new StringWriter();
        var errWriter = new StringWriter();
        int exit = MhBackupCliCommand.Run(args, outWriter, errWriter, name =>
            string.Equals(name, PasswordVariable, StringComparison.Ordinal)
            || string.Equals(name, MhBackupCliCommand.DefaultPasswordVariable, StringComparison.Ordinal)
                ? password
                : null);
        output = outWriter.ToString();
        error = errWriter.ToString();
        return exit;
    }
}

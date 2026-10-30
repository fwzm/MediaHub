using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace MediaHub.Backup;

/// <summary>
/// 备份命令行的可测试入口：export 导出、import 只做预览。
/// </summary>
/// <remarks>
/// 与 MediaHub.SyncCli 一样按退出码区分处境，方便脚本判断：0 成功、2 参数错误、3 不是备份文件、
/// 4 口令错误、5 版本过新、6 内容损坏、7 导出被拒、8 读写文件失败。
/// 口令只从环境变量取，不接受命令行参数：命令行会进进程列表、命令历史和日志。
/// import 默认只输出预览计划；带 --apply 时才把「本机能表达的来源」与「有明确等价的偏好」写进
/// settings.json，写前留一份 .bak-<时间戳>。播放进度与收藏不落库，理由随回执给出。
/// </remarks>
public static class MhBackupCliCommand
{
    public const int ExitOk = 0;
    public const int ExitUsage = 2;
    public const int ExitNotABackupFile = 3;
    public const int ExitAuthenticationFailed = 4;
    public const int ExitVersionTooNew = 5;
    public const int ExitCorrupted = 6;
    public const int ExitExportRejected = 7;
    public const int ExitIo = 8;

    public const string DefaultPasswordVariable = "MH_BACKUP_PASSWORD";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = false,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    /// <summary>执行一条命令并返回退出码；输出写入调用方给的 TextWriter，便于测试断言。</summary>
    public static int Run(
        string[] args,
        TextWriter output,
        TextWriter error,
        Func<string, string?>? environment = null)
    {
        ArgumentNullException.ThrowIfNull(output);
        ArgumentNullException.ThrowIfNull(error);
        environment ??= Environment.GetEnvironmentVariable;

        if (args is null || args.Length == 0)
        {
            WriteUsage(output);
            return ExitUsage;
        }

        string command = args[0];
        if (IsHelp(command))
        {
            WriteUsage(output);
            return ExitOk;
        }

        var options = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
        for (int index = 1; index < args.Length; index++)
        {
            string token = args[index];
            if (!token.StartsWith("--", StringComparison.Ordinal))
            {
                return Fail(error, "无法识别的参数：" + token);
            }

            string name = token.Substring(2);
            if (index + 1 < args.Length && !args[index + 1].StartsWith("--", StringComparison.Ordinal))
            {
                options[name] = args[++index];
            }
            else
            {
                options[name] = null;
            }
        }

        return command.ToLowerInvariant() switch
        {
            "export" => RunExport(options, output, error, environment),
            "import" => RunImport(options, output, error, environment),
            _ => UnknownCommand(command, output, error),
        };
    }

    private static int UnknownCommand(string command, TextWriter output, TextWriter error)
    {
        WriteUsage(output);
        error.WriteLine("未知命令：" + command);
        return ExitUsage;
    }

    private static int RunExport(
        Dictionary<string, string?> options,
        TextWriter output,
        TextWriter error,
        Func<string, string?> environment)
    {
        bool asJson = Has(options, "json");
        string? settingsPath = Value(options, "settings");
        string? outPath = Value(options, "out");
        if (settingsPath is null || outPath is null)
        {
            return Fail(error, "export 需要 --settings <settings.json> 与 --out <备份文件>");
        }

        string settingsJson;
        try
        {
            settingsJson = File.ReadAllText(settingsPath);
        }
        catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return IoFailure(error, settingsPath, failure);
        }

        MhBackupLocalSettings settings;
        try
        {
            settings = MhBackupLocalData.Read(settingsJson);
        }
        catch (FormatException failure)
        {
            return Fail(error, "设置文件无法解析：" + settingsPath + "（" + failure.Message + "）");
        }

        int skippedHistory = 0;
        string? historyPath = Value(options, "history");
        if (historyPath is not null)
        {
            try
            {
                skippedHistory = MhBackupLocalData.CountProgressEntries(File.ReadAllText(historyPath));
            }
            catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or FormatException or ArgumentException or NotSupportedException)
            {
                // 观看进度只是附注：读不出来不影响导出，但要如实告诉用户为什么少了一段统计。
                error.WriteLine("注意：" + historyPath + " 无法读取，本次不统计本机观看进度（" + failure.Message + "）");
            }
        }

        MhBackupExportCollectResult collected;
        try
        {
            collected = MhBackupExportCollector.Collect(
                settings.Servers,
                settings.Preferences,
                skippedHistoryEntries: skippedHistory);
        }
        catch (MhBackupExportException rejection)
        {
            return ExportRejected(error, rejection.Reason);
        }

        if (collected.Payload.Servers.Count == 0)
        {
            string reasons = collected.SkippedServers.Count == 0
                ? "设置文件里没有任何媒体源"
                : string.Join("；", collected.SkippedServers.Select(item => item.Name + "：" + item.Reason));
            return ExportRejected(error, "没有可导出的来源（" + reasons + "）");
        }

        string? password = ResolvePassword(options, environment);
        if (string.IsNullOrEmpty(password))
        {
            return UsageFailure(error, "没有提供备份口令：请设置环境变量 " + PasswordVariable(options) + "（不接受命令行传口令）");
        }

        byte[] bytes;
        try
        {
            bytes = MhBackupSerializer.Export(collected.Payload, password.AsSpan());
        }
        catch (MhBackupExportException rejection)
        {
            return ExportRejected(error, rejection.Reason);
        }

        try
        {
            File.WriteAllBytes(outPath, bytes);
        }
        catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return IoFailure(error, outPath, failure);
        }

        string digest = Convert.ToHexStringLower(SHA256.HashData(bytes));
        // 采集层总会写 manifest；这里用 ! 表明该不变量，而不是把 null 一路传到断言里。
        IReadOnlyList<string> sections = collected.Payload.Manifest!.IncludedSections;
        if (asJson)
        {
            WriteJson(output, new JsonObject
            {
                ["command"] = "export",
                ["ok"] = true,
                ["path"] = outPath,
                ["bytes"] = bytes.Length,
                ["sha256"] = digest,
                ["servers"] = collected.Payload.Servers.Count,
                ["sections"] = new JsonArray(sections.Select(section => (JsonNode?)JsonValue.Create(section)).ToArray()),
                ["skipped"] = new JsonArray(collected.SkippedServers
                    .Select(item => (JsonNode?)new JsonObject { ["name"] = item.Name, ["reason"] = item.Reason })
                    .ToArray()),
                ["notes"] = new JsonArray(collected.Notes.Select(note => (JsonNode?)JsonValue.Create(note)).ToArray()),
                ["skippedHistoryEntries"] = collected.SkippedHistoryEntries,
            });
            return ExitOk;
        }

        output.WriteLine("已导出 " + collected.Payload.Servers.Count + " 个来源");
        foreach (MhBackupSkippedServer skipped in collected.SkippedServers)
        {
            output.WriteLine("· 跳过 " + skipped.Name + "：" + skipped.Reason);
        }

        foreach (string note in collected.Notes)
        {
            output.WriteLine("· " + note);
        }

        output.WriteLine("备份文件：" + outPath);
        output.WriteLine("文件大小：" + bytes.Length + " 字节");
        output.WriteLine("SHA-256：" + digest);
        output.WriteLine("包含段：" + (sections.Count == 0 ? "（无）" : string.Join("、", sections)));
        output.WriteLine("提醒：备份不含密码或令牌，还原后需要重新登录。");
        return ExitOk;
    }

    private static int RunImport(
        Dictionary<string, string?> options,
        TextWriter output,
        TextWriter error,
        Func<string, string?> environment)
    {
        bool apply = Has(options, "apply");
        bool asJson = Has(options, "json");
        string? filePath = Value(options, "file");
        string? settingsPath = Value(options, "settings");
        if (filePath is null)
        {
            return Fail(error, "import 需要 --file <备份文件>；--settings 可选（预览时缺省按本机没有任何来源）");
        }

        if (apply && settingsPath is null)
        {
            return UsageFailure(error, "写库必须给 --settings <settings.json>，否则不知道该改哪一份本机设置");
        }

        string strategyName = (Value(options, "strategy") ?? "merge").ToLowerInvariant();
        MhBackupRestoreStrategy strategy;
        switch (strategyName)
        {
            case "merge":
                strategy = MhBackupRestoreStrategy.Merge;
                break;
            case "replace":
            case "replace-selected":
                strategy = MhBackupRestoreStrategy.ReplaceSelected;
                break;
            default:
                return UsageFailure(error, "无法识别的 --strategy：" + strategyName + "（可用 merge 或 replace）");
        }

        byte[] bytes;
        try
        {
            bytes = File.ReadAllBytes(filePath);
        }
        catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return IoFailure(error, filePath, failure);
        }

        string? password = ResolvePassword(options, environment);
        if (string.IsNullOrEmpty(password))
        {
            return UsageFailure(error, "没有提供备份口令：请设置环境变量 " + PasswordVariable(options) + "（不接受命令行传口令）");
        }

        MhBackupImportResult imported = MhBackupSerializer.Import(bytes, password.AsSpan());
        switch (imported.Status)
        {
            case MhBackupImportStatus.Ok:
                break;
            case MhBackupImportStatus.NotABackupFile:
                return Fail(error, "这不是 MediaHub 备份文件（文件里找不到 MHBK 标记）：" + filePath, ExitNotABackupFile);
            case MhBackupImportStatus.AuthenticationFailed:
                return Fail(error, imported.Reason ?? "无法解密备份：密码不正确，或文件已损坏、被修改。", ExitAuthenticationFailed);
            case MhBackupImportStatus.VersionTooNew:
                return Fail(error, imported.Reason ?? "备份由更新版本的应用写出，本版本读不懂。", ExitVersionTooNew);
            default:
                return Fail(error, imported.Reason ?? "备份内容无法解析。", ExitCorrupted);
        }

        MhBackupPayload payload = imported.Payload!;
        // import 返回 Ok 时 manifest 必然存在（解码阶段已校验），下面直接用。
        MhBackupManifest manifest = payload.Manifest!;
        IReadOnlyList<MhBackupLocalSource> localSources;
        string? settingsWarning = null;
        string? settingsText = null;
        if (settingsPath is null)
        {
            localSources = Array.Empty<MhBackupLocalSource>();
            settingsWarning = "没有给 --settings：按本机没有任何来源预览，所有来源都会显示为新增";
        }
        else
        {
            try
            {
                settingsText = File.ReadAllText(settingsPath);
                localSources = MhBackupLocalData.ReadServers(settingsText)
                    .Select(server => new MhBackupLocalSource
                    {
                        // 必须与导出用同一个身份函数，否则本机导出再本机预览会把自己判成新增。
                        BackupId = MhBackupExportCollector.DeriveBackupId(server),
                        Type = server.Type ?? string.Empty,
                        PrimaryUrl = (server.Url ?? string.Empty).Trim(),
                        UserName = string.IsNullOrWhiteSpace(server.UserName) ? null : server.UserName.Trim(),
                    })
                    .Where(source => source.BackupId.Length > 0)
                    .ToList();
            }
            catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or FormatException or ArgumentException or NotSupportedException)
            {
                localSources = Array.Empty<MhBackupLocalSource>();
                settingsWarning = "设置文件读不出来（" + failure.Message + "）：按本机没有任何来源预览";
            }
        }

        MhBackupRestorePlan plan = MhBackupRestorePlanner.Plan(payload, localSources, strategy);
        if (apply)
        {
            return ApplyImport(payload, plan, manifest, settingsText, settingsPath!, asJson, output, error);
        }

        if (asJson)
        {
            WriteJson(output, new JsonObject
            {
                ["command"] = "import",
                ["mode"] = "preview",
                ["ok"] = true,
                ["strategy"] = strategy == MhBackupRestoreStrategy.Merge ? "MERGE" : "REPLACE_SELECTED",
                ["appVersion"] = manifest.AppVersion,
                ["createdAtEpochMs"] = manifest.CreatedAtEpochMs,
                ["newServers"] = plan.NewCount,
                ["identicalServers"] = plan.IdenticalCount,
                ["conflictingServers"] = plan.ConflictCount,
                ["progressToWrite"] = plan.ProgressToWriteCount,
                ["orphanProgress"] = plan.OrphanProgressCount,
                ["favoritesToWrite"] = plan.FavoritesToWriteCount,
                ["orphanFavorites"] = plan.OrphanFavoriteCount,
                ["preferencesContained"] = plan.PreferencesContained,
                ["preferencesWillRestore"] = plan.PreferencesWillRestore,
                ["hasWrites"] = plan.HasWrites,
                ["lines"] = new JsonArray(plan.SummaryLines.Select(line => (JsonNode?)JsonValue.Create(line)).ToArray()),
            });
            if (settingsWarning is not null)
            {
                error.WriteLine("注意：" + settingsWarning);
            }

            return ExitOk;
        }

        if (settingsWarning is not null)
        {
            output.WriteLine("注意：" + settingsWarning);
        }

        output.WriteLine("预览：不会写入任何数据");
        output.WriteLine("备份文件：" + filePath + "（容器版本 " + imported.FormatVersion + "）");
        output.WriteLine("备份应用版本：" + (manifest.AppVersion ?? "（未记录）"));
        foreach (string line in plan.SummaryLines)
        {
            output.WriteLine(line);
        }

        output.WriteLine("结论：" + (plan.HasWrites ? "有内容可恢复" : "本次预览没有会写入的内容"));
        return ExitOk;
    }


    private static int ApplyImport(
        MhBackupPayload payload,
        MhBackupRestorePlan plan,
        MhBackupManifest manifest,
        string? settingsText,
        string settingsPath,
        bool asJson,
        TextWriter output,
        TextWriter error)
    {
        MhBackupRestoreApplyResult result;
        try
        {
            result = MhBackupRestoreApplier.Apply(payload, plan, settingsText);
        }
        catch (FormatException failure)
        {
            return Fail(error, "settings.json 结构不对，没有写入：" + failure.Message, ExitIo);
        }

        string? backupPath = null;
        if (result.Changed)
        {
            try
            {
                if (File.Exists(settingsPath))
                {
                    backupPath = NextBackupPath(settingsPath);
                    File.Copy(settingsPath, backupPath, overwrite: false);
                }

                WriteSettingsAtomically(settingsPath, result.SettingsJson);
            }
            catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
            {
                return IoFailure(error, settingsPath, failure);
            }
        }

        if (asJson)
        {
            WriteJson(output, new JsonObject
            {
                ["command"] = "import",
                ["mode"] = "apply",
                ["ok"] = true,
                ["applied"] = true,
                ["changed"] = result.Changed,
                ["strategy"] = plan.Strategy == MhBackupRestoreStrategy.Merge ? "MERGE" : "REPLACE_SELECTED",
                ["appVersion"] = manifest.AppVersion,
                ["settings"] = settingsPath,
                ["backupPath"] = backupPath,
                ["added"] = new JsonArray(result.AddedServers.Select(server => (JsonNode?)new JsonObject
                {
                    ["backupId"] = server.BackupId,
                    ["serverId"] = server.ServerId,
                    ["displayName"] = server.DisplayName,
                    ["type"] = server.Type,
                    ["replaced"] = server.Replaced,
                }).ToArray()),
                ["skipped"] = new JsonArray(result.SkippedServers.Select(server => (JsonNode?)new JsonObject
                {
                    ["backupId"] = server.BackupId,
                    ["displayName"] = server.DisplayName,
                    ["reason"] = server.Reason,
                }).ToArray()),
                ["preferencesRestored"] = result.PreferencesRestored,
                ["notes"] = new JsonArray(result.Notes.Select(note => (JsonNode?)JsonValue.Create(note)).ToArray()),
                ["newServers"] = plan.NewCount,
                ["identicalServers"] = plan.IdenticalCount,
                ["conflictingServers"] = plan.ConflictCount,
            });
            return ExitOk;
        }

        if (!result.Changed)
        {
            output.WriteLine("没有需要写入的变更：settings.json 逐字节未动");
            output.WriteLine("本机设置：" + settingsPath);
            foreach (string note in result.Notes)
            {
                output.WriteLine("说明：" + note);
            }

            return ExitOk;
        }

        output.WriteLine("已写入本机设置：" + settingsPath + "（容器版本 " + MhBackupCrypto.FormatVersion + "）");
        if (backupPath is not null) output.WriteLine("写前备份：" + backupPath);
        foreach (MhBackupAppliedServer server in result.AddedServers)
        {
            output.WriteLine((server.Replaced ? "  替换 " : "  新增 ") + server.DisplayName + "（ServerId " + server.ServerId + " · " + server.Type + "）");
        }

        if (result.SkippedCount > 0)
        {
            output.WriteLine("未写入 " + result.SkippedCount + " 条（逐条原因）：");
            foreach (MhBackupApplySkippedServer server in result.SkippedServers)
            {
                output.WriteLine("  - " + server.DisplayName + "（" + server.BackupId + "）· " + server.Reason);
            }
        }

        output.WriteLine(result.PreferencesRestored ? "偏好：已恢复到本机设置" : "偏好：没有改动");
        foreach (string note in result.Notes)
        {
            output.WriteLine("说明：" + note);
        }

        output.WriteLine("提醒：备份不含密码或令牌，写入的来源需要登录后才能使用；播放进度与收藏没有落库。");
        return ExitOk;
    }

    private static string NextBackupPath(string settingsPath)
    {
        string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        string candidate = settingsPath + ".bak-" + stamp;
        int suffix = 1;
        while (File.Exists(candidate))
        {
            suffix++;
            candidate = settingsPath + ".bak-" + stamp + "-" + suffix;
        }

        return candidate;
    }

    private static void WriteSettingsAtomically(string settingsPath, string text)
    {
        string directory = Path.GetDirectoryName(Path.GetFullPath(settingsPath)) ?? ".";
        string temporary = Path.Combine(directory, Path.GetFileName(settingsPath) + ".tmp-" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            File.WriteAllText(temporary, text, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.Move(temporary, settingsPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }
    private static string? ResolvePassword(Dictionary<string, string?> options, Func<string, string?> environment)
    {
        string? value = environment(PasswordVariable(options));
        return string.IsNullOrEmpty(value) ? environment(DefaultPasswordVariable) : value;
    }

    private static string PasswordVariable(Dictionary<string, string?> options)
        => Value(options, "password-env") ?? DefaultPasswordVariable;

    private static string? Value(Dictionary<string, string?> options, string name)
        => options.TryGetValue(name, out string? value) && !string.IsNullOrWhiteSpace(value) ? value : null;

    private static bool Has(Dictionary<string, string?> options, string name)
        => options.ContainsKey(name);

    private static bool IsHelp(string command)
        => command is "--help" or "-h" or "help";

    private static int Fail(TextWriter error, string message, int exitCode = ExitUsage)
    {
        error.WriteLine(message);
        return exitCode;
    }

    private static int UsageFailure(TextWriter error, string message) => Fail(error, message, ExitUsage);

    private static int ExportRejected(TextWriter error, string reason) => Fail(error, "导出被拒：" + reason, ExitExportRejected);

    private static int IoFailure(TextWriter error, string path, Exception failure)
        => Fail(error, "读写文件失败：" + path + "（" + failure.Message + "）", ExitIo);

    private static void WriteJson(TextWriter output, JsonObject payload)
        => output.WriteLine(payload.ToJsonString(JsonOptions));

    private static void WriteUsage(TextWriter output)
    {
        output.WriteLine("MediaHub 备份工具（Windows 端）");
        output.WriteLine("用法：");
        output.WriteLine("  export --settings <settings.json> --out <备份文件> [--history <history.json>] [--password-env <变量名>] [--json]");
        output.WriteLine("  import --file <备份文件> [--settings <settings.json>] [--apply] [--strategy merge|replace] [--password-env <变量名>] [--json]");
        output.WriteLine("说明：");
        output.WriteLine("  口令只从环境变量读取（缺省 " + DefaultPasswordVariable + "），不接受命令行参数。");
        output.WriteLine("  import 默认只输出预览；--apply 才写入 settings.json（必须同时给 --settings）。");
        output.WriteLine("  --apply 只写本机能表达的来源与有等价的偏好；播放进度与收藏不落库，回执会给出原因。");
        output.WriteLine("  退出码：0 成功 / 2 参数错误 / 3 不是备份文件 / 4 口令错误 / 5 版本过新 / 6 内容损坏 / 7 导出被拒 / 8 读写失败。");
    }
}

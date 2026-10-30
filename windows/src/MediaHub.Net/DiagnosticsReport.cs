using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace MediaHub.Net;

/// <summary>
/// Renders the redacted Windows diagnostic report and writes it next to the settings file.
/// </summary>
/// <remarks>
/// Redaction already happens at write time (<see cref="LogService.Redact"/>), so every entry in memory
/// is safe. This renderer redacts a second time anyway: it is the last step before the text leaves the
/// process, and a caller can hand it entries that never passed the log service. The file write lives
/// here rather than in the settings page so that the whole path is covered by tests.
/// </remarks>
public static class DiagnosticsReport
{
    public const int MaxCauseChars = 160;

    public const string Title = "# MediaHub Windows 诊断日志（已脱敏）";
    public const string FilePrefix = "mediahub-diagnostics-";
    public const string FileSuffix = ".log";
    public const string WriteFailed = "diagnostics-write-failed";
    public const string LogSeparator = "---- 日志 ----";
    public const string NoteSeparator = "---- 说明 ----";

    /// <summary>Header line prefix for the rolling-directory pointer, as shipped by W14.</summary>
    public const string RollingDirectoryPrefix = "# 滚动文件目录：";

    public const string Disclaimer =
        "本报告在写入日志时已脱敏，导出时再逐行脱敏一次；密码、令牌与 Cookie 不会出现在这里。";

    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.Compiled);

    public static string FileName(DateTime now)
        => FilePrefix + now.ToString("yyyyMMdd-HHmmss") + FileSuffix;

    public static string Render(
        IReadOnlyList<LogEntry> entries, DateTime now, string? appVersion = null, string? device = null,
        string? rollingDirectory = null)
    {
        var lines = new List<string> { Title };
        // Position and shape come straight from W14 (windows/docs/W14-DIAGNOSTIC-LOG-FILES.md §3):
        // the pointer sits directly under the title, before the generation timestamp. The W6-112 merge
        // would otherwise have changed the header format without anyone noticing.
        // The memory buffer keeps the newest lines only; the rolling files keep the ones it pushed out,
        // so point at them rather than implying the export is the complete history.
        if (!string.IsNullOrWhiteSpace(rollingDirectory))
            // Not redacted: this is the local log folder the operator chose, not a request URL. Running
            // LogService.Redact over it would eat a 32-hex segment (LongHexRegex) and leave a pointer
            // nobody can follow. Nothing secret can hide in a directory path we wrote ourselves.
            lines.Add(RollingDirectoryPrefix + rollingDirectory);
        lines.Add("生成时间：" + now.ToString("yyyy-MM-dd HH:mm:ss"));
        if (!string.IsNullOrWhiteSpace(appVersion)) lines.Add("应用版本：" + LogService.Redact(appVersion));
        if (!string.IsNullOrWhiteSpace(device)) lines.Add("设备：" + LogService.Redact(device));
        lines.Add("日志条目：" + entries.Count);
        lines.Add(LogSeparator);
        foreach (LogEntry entry in entries)
        {
            lines.Add(entry.Timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff")
                + " [" + entry.Level + "] " + entry.Category + " " + LogService.Redact(entry.Message));
        }
        lines.Add(NoteSeparator);
        lines.Add(Disclaimer);
        return string.Join(Environment.NewLine, lines);
    }

    public static WriteResult Write(
        string directory, IReadOnlyList<LogEntry> entries, DateTime now,
        string? appVersion = null, string? device = null, string? rollingDirectory = null)
    {
        try
        {
            string text = Render(entries, now, appVersion, device, rollingDirectory);
            Directory.CreateDirectory(directory);
            string path = Path.Combine(directory, FileName(now));
            File.WriteAllText(path, text, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            return new WriteResult.Written(path, Encoding.UTF8.GetByteCount(text), entries.Count);
        }
        catch (Exception failure)
        {
            return new WriteResult.Failed(WriteFailed, Describe(failure));
        }
    }

    public static WriteResult Write(
        string directory, LogService log, DateTime now, string? appVersion = null, string? device = null)
        => Write(directory, log.Snapshot(), now, appVersion, device, log.FileSink?.DirectoryPath);

    public static string SuccessLine(string path) => "已导出（已脱敏）：" + path;

    public static string FailureLine(WriteResult.Failed failure)
        => failure.CauseMessage is null
            ? "导出失败：" + failure.Reason
            : "导出失败：" + failure.Reason + "（原因：" + failure.CauseMessage + "）";

    public static string? Describe(Exception? failure)
    {
        string? raw = failure?.Message;
        if (string.IsNullOrWhiteSpace(raw)) return null;
        string cleaned = Whitespace.Replace(LogService.Redact(raw), " ").Trim();
        if (cleaned.Length == 0) return null;
        if (cleaned.Length > MaxCauseChars)
        {
            cleaned = cleaned.Substring(0, MaxCauseChars);
            if (char.IsHighSurrogate(cleaned[cleaned.Length - 1])) cleaned = cleaned.Substring(0, cleaned.Length - 1);
        }
        return cleaned;
    }

    public abstract record WriteResult
    {
        /// <summary>The report is on disk.</summary>
        public sealed record Written(string Path, int Bytes, int Entries) : WriteResult;

        /// <summary>Nothing was written; the cause message is already cleaned.</summary>
        public sealed record Failed(string Reason, string? CauseMessage) : WriteResult;
    }
}

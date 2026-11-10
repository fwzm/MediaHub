using System;
using System.IO;
using System.Text;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The Windows export path used to live entirely in the settings page, so nothing could prove that a
/// file was written, that it was redacted again on the way out, or that a failure produced a readable
/// message instead of a raw exception. These tests pin those three things.
/// </summary>
public sealed class DiagnosticsReportTests
{
    private static readonly DateTime Now = new DateTime(2026, 10, 29, 11, 45, 9);

    private static LogEntry Entry(string message, LogLevel level = LogLevel.Info, string category = "sync")
        => new LogEntry(Now, level, category, message);

    [Fact]
    public void The_report_has_the_title_the_header_the_entries_and_the_note_in_that_order()
    {
        var entries = new[] { Entry("第一条"), Entry("第二条", LogLevel.Warn) };

        string[] lines = DiagnosticsReport.Render(entries, Now, "1.7.0", "Pixel").Split(Environment.NewLine);

        Assert.Equal(10, lines.Length);
        Assert.Equal(DiagnosticsReport.Title, lines[0]);
        Assert.Equal("生成时间：2026-10-29 11:45:09", lines[1]);
        Assert.Equal("应用版本：1.7.0", lines[2]);
        Assert.Equal("设备：Pixel", lines[3]);
        Assert.Equal("日志条目：2", lines[4]);
        Assert.Equal(DiagnosticsReport.LogSeparator, lines[5]);
        Assert.Contains("第一条", lines[6]);
        Assert.Contains("第二条", lines[7]);
        Assert.Equal(DiagnosticsReport.NoteSeparator, lines[8]);
        Assert.Equal(DiagnosticsReport.Disclaimer, lines[9]);
    }

    [Fact]
    public void Every_line_is_redacted_again_even_when_it_never_passed_the_log_service()
    {
        var entries = new[] { Entry("raw password=hunter2 https://user:pw@nas-a:8096/emby?api_key=abc123") };

        string text = DiagnosticsReport.Render(entries, Now);

        Assert.DoesNotContain("hunter2", text);
        Assert.DoesNotContain("abc123", text);
        Assert.Contains("***", text);
        Assert.Contains("nas-a", text);
    }

    [Fact]
    public void The_header_values_are_redacted_too()
    {
        string text = DiagnosticsReport.Render(
            Array.Empty<LogEntry>(), Now, "1.7.0 token=abc123", "Pixel password=hunter2");

        Assert.DoesNotContain("abc123", text);
        Assert.DoesNotContain("hunter2", text);
    }

    [Fact]
    public void The_file_name_follows_the_timestamp()
    {
        Assert.Equal("mediahub-diagnostics-20261029-114509.log", DiagnosticsReport.FileName(Now));
    }

    [Fact]
    public void Write_creates_a_missing_directory_and_reports_bytes_and_entries()
    {
        string root = Path.Combine(Path.GetTempPath(), "mh-diagnostics-" + Guid.NewGuid().ToString("N"));
        string dir = Path.Combine(root, "nested", "out");
        try
        {
            var entries = new[] { Entry("ok") };

            DiagnosticsReport.WriteResult result = DiagnosticsReport.Write(dir, entries, Now, "1.7.0", "Pixel");

            var written = Assert.IsType<DiagnosticsReport.WriteResult.Written>(result);
            string content = File.ReadAllText(written.Path);
            Assert.Equal(Path.Combine(dir, "mediahub-diagnostics-20261029-114509.log"), written.Path);
            Assert.Equal(DiagnosticsReport.Render(entries, Now, "1.7.0", "Pixel"), content);
            Assert.Equal(Encoding.UTF8.GetByteCount(content), written.Bytes);
            Assert.Equal(1, written.Entries);
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void A_blocked_target_reports_the_stable_code_and_a_clean_message()
    {
        string root = Path.Combine(Path.GetTempPath(), "mh-diagnostics-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        string blocked = Path.Combine(root, "blocked");
        File.WriteAllText(blocked, "in the way");
        try
        {
            DiagnosticsReport.WriteResult result = DiagnosticsReport.Write(
                Path.Combine(blocked, "diagnostics"), new[] { Entry("ok") }, Now);

            var failed = Assert.IsType<DiagnosticsReport.WriteResult.Failed>(result);
            Assert.Equal(DiagnosticsReport.WriteFailed, failed.Reason);
            Assert.NotNull(failed.CauseMessage);
            Assert.True(failed.CauseMessage!.Length <= DiagnosticsReport.MaxCauseChars);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void An_empty_log_still_produces_a_report_with_the_note()
    {
        string text = DiagnosticsReport.Render(Array.Empty<LogEntry>(), Now);

        Assert.Contains("日志条目：0", text);
        Assert.Contains(DiagnosticsReport.LogSeparator, text);
        Assert.Contains(DiagnosticsReport.NoteSeparator, text);
        Assert.Contains(DiagnosticsReport.Disclaimer, text);
    }

    [Fact]
    public void Describe_cleans_secrets_whitespace_and_length()
    {
        string? cleaned = DiagnosticsReport.Describe(new InvalidOperationException("第一行\n第二行 token=abc123"));

        Assert.NotNull(cleaned);
        Assert.DoesNotContain("abc123", cleaned!);
        Assert.DoesNotContain("\n", cleaned!);
        Assert.StartsWith("第一行 第二行", cleaned);
        Assert.Null(DiagnosticsReport.Describe(null));
        Assert.Null(DiagnosticsReport.Describe(new InvalidOperationException("")));
        Assert.Equal(
            DiagnosticsReport.MaxCauseChars,
            DiagnosticsReport.Describe(new InvalidOperationException(new string((char)120, 400)))!.Length);
    }

    // ---- W6-113: the rolling-directory contract (memory snapshot + a pointer, not the file text) ----

    private const string HexDir =
        "C:/Users/x/AppData/Local/Temp/mh-log-sink-7390dd42d33640f085f3e88e26f4fecb/export";

    [Fact]
    public void The_rolling_directory_line_keeps_the_prefix_w14_shipped()
    {
        // W14 shipped "# 滚动文件目录：<目录>" (windows/docs/W14-DIAGNOSTIC-LOG-FILES.md §3).
        // The W6-111 renderer must not quietly change that shape while both live in one tree.
        string[] lines = DiagnosticsReport.Render(
            Array.Empty<LogEntry>(), Now, rollingDirectory: HexDir).Split(Environment.NewLine);

        Assert.Equal(DiagnosticsReport.Title, lines[0]);
        Assert.Equal("# 滚动文件目录：" + HexDir, lines[1]);
        Assert.Equal("生成时间：2026-10-29 11:45:09", lines[2]);
    }

    [Fact]
    public void A_missing_rolling_directory_leaves_the_header_byte_for_byte_as_before()
    {
        // Legacy call shapes (2 / 3 / 4 arguments) must keep producing exactly what they produced
        // before the parameter existed -- otherwise every existing caller's report changes silently.
        string[] lines = DiagnosticsReport.Render(
            Array.Empty<LogEntry>(), Now, "1.7.0", "Pixel").Split(Environment.NewLine);

        Assert.Equal(DiagnosticsReport.Title, lines[0]);
        Assert.Equal("生成时间：2026-10-29 11:45:09", lines[1]);
        Assert.Equal("应用版本：1.7.0", lines[2]);
        Assert.Equal("设备：Pixel", lines[3]);
        Assert.Equal("日志条目：0", lines[4]);
        Assert.DoesNotContain("滚动文件目录", string.Join(Environment.NewLine, lines));
    }

    [Fact]
    public void An_empty_rolling_directory_is_treated_as_absent()
    {
        Assert.DoesNotContain("滚动文件目录", DiagnosticsReport.Render(Array.Empty<LogEntry>(), Now, rollingDirectory: ""));
        Assert.DoesNotContain("滚动文件目录", DiagnosticsReport.Render(Array.Empty<LogEntry>(), Now, rollingDirectory: "   "));
        Assert.DoesNotContain("滚动文件目录", DiagnosticsReport.Render(Array.Empty<LogEntry>(), Now, rollingDirectory: null));
    }

    [Fact]
    public void The_hexadecimal_segment_of_the_directory_survives_redaction_untouched()
    {
        // LogService scrubs 32+ hex characters. A temp directory carries a 32-hex GUID, so redacting
        // this line would turn the pointer into ".../mh-log-sink-***" -- un-followable.
        string text = DiagnosticsReport.Render(Array.Empty<LogEntry>(), Now, rollingDirectory: HexDir);

        Assert.Contains(HexDir, text);
        Assert.DoesNotContain("***", text);
    }

    [Fact]
    public void The_directory_is_echoed_verbatim_while_entries_in_the_same_report_are_still_redacted()
    {
        // The directory is a local pointer: whatever a caller passes is echoed as-is. Entries in the
        // same report still go through redaction -- the two rules must not blur into each other.
        var entries = new[] { Entry("登录 https://nas-a:8096/emby?api_key=abc123&u=bob") };
        string text = DiagnosticsReport.Render(entries, Now, rollingDirectory: "C:/logs/token=abc123");

        Assert.Contains("滚动文件目录：C:/logs/token=abc123", text);
        Assert.DoesNotContain("api_key=abc123", text);
        Assert.Contains("api_key=***", text);
    }

    [Fact]
    public void The_report_points_at_the_rolling_directory_instead_of_inlining_the_file()
    {
        // Contract: the export is the in-memory transcript plus a pointer. The rolling file lives on
        // disk; its text must not be pasted into the report (that would duplicate every line).
        string directory = Path.Combine(Path.GetTempPath(), "mh-roll-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            File.WriteAllText(Path.Combine(directory, "mediahub-0.log"), "ON-DISK-ONLY-LINE" + Environment.NewLine);
            var log = new LogService(new LogFileSink(directory));
            log.Info("net", "in-memory-line");

            string exported = log.Export();

            Assert.Contains("滚动文件目录：" + directory, exported);
            Assert.Contains("in-memory-line", exported);
            Assert.DoesNotContain("ON-DISK-ONLY-LINE", exported);
            Assert.Contains("已脱敏", exported);
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Write_carries_the_rolling_directory_into_the_file_it_writes()
    {
        string root = Path.Combine(Path.GetTempPath(), "mh-diagnostics-" + Guid.NewGuid().ToString("N"));
        string target = Path.Combine(root, "out");
        try
        {
            var log = new LogService(new LogFileSink(Path.Combine(root, "logs")));
            log.Info("sync", "已应用对端信封");

            DiagnosticsReport.WriteResult result = DiagnosticsReport.Write(target, log, Now);

            var written = Assert.IsType<DiagnosticsReport.WriteResult.Written>(result);
            string content = File.ReadAllText(written.Path);
            Assert.Contains("滚动文件目录：" + log.FileSink!.DirectoryPath, content);
            Assert.Equal(
                DiagnosticsReport.Render(log.Snapshot(), Now, rollingDirectory: log.FileSink.DirectoryPath), content);
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void Export_and_the_file_report_share_one_renderer()
    {
        var log = new LogService();
        log.Info("sync", "已应用对端信封");

        string exported = log.Export();

        Assert.Contains(DiagnosticsReport.Title, exported);
        Assert.Contains("日志条目：1", exported);
        Assert.Contains(DiagnosticsReport.NoteSeparator, exported);
        Assert.Single(log.Snapshot());
    }
}

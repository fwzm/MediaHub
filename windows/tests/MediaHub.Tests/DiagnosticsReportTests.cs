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

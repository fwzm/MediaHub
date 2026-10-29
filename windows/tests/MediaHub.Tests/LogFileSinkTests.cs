using System;
using System.Globalization;
using System.IO;
using System.Linq;
using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The rolling file sink is the only diagnostics surface that survives a restart, so its bounds
/// (size, file count, one complete entry per append) are behaviour, not implementation detail.
/// </summary>
public sealed class LogFileSinkTests : IDisposable
{
    private readonly string _root;

    public LogFileSinkTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mh-log-sink-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private string DirectoryFor(string name) => Path.Combine(_root, name);

    private static LogEntry Entry(string message, LogLevel level = LogLevel.Info) =>
        new LogEntry(new DateTime(2026, 10, 30, 0, 0, 0, DateTimeKind.Local), level, "test", message);

    private static string Number(int value) => value.ToString("D4", CultureInfo.InvariantCulture);

    [Fact]
    public void Every_appended_entry_reaches_the_newest_file()
    {
        var sink = new LogFileSink(DirectoryFor("basic"));
        sink.Append(Entry("first line"));
        sink.Append(Entry("second line", LogLevel.Warn));

        string[] lines = File.ReadAllLines(sink.CurrentPath);
        Assert.Equal(2, lines.Length);
        Assert.Contains("[Info] test first line", lines[0]);
        Assert.Contains("[Warn] test second line", lines[1]);
        Assert.Null(sink.LastError);
    }

    [Fact]
    public void Rotation_drops_the_oldest_file_and_keeps_entries_whole()
    {
        var sink = new LogFileSink(DirectoryFor("rotate"), maxBytesPerFile: 64, maxFiles: 3);
        for (int i = 1; i <= 5; i++) sink.Append(Entry("entry-" + Number(i)));

        Assert.Equal(3, Directory.GetFiles(DirectoryFor("rotate"), "*.log").Length);
        Assert.Contains("entry-0005", File.ReadAllText(sink.PathFor(0)));
        Assert.Contains("entry-0004", File.ReadAllText(sink.PathFor(1)));
        Assert.Contains("entry-0003", File.ReadAllText(sink.PathFor(2)));
        Assert.DoesNotContain("entry-0001", File.ReadAllText(sink.PathFor(2)));

        // One entry cannot straddle two files: everything retained is a full line with its stamp.
        foreach (string file in new[] { sink.PathFor(0), sink.PathFor(1), sink.PathFor(2) })
        {
            foreach (string line in File.ReadAllLines(file))
                Assert.StartsWith("2026-10-30 00:00:00.000 [Info] test entry-", line);
        }
    }

    [Fact]
    public void An_entry_larger_than_the_limit_is_written_whole()
    {
        var sink = new LogFileSink(DirectoryFor("oversize"), maxBytesPerFile: 64, maxFiles: 2);
        string longMessage = new string((char)120, 120);
        sink.Append(Entry(longMessage));
        sink.Append(Entry("short"));

        string older = File.ReadAllText(sink.PathFor(1));
        Assert.Contains(longMessage, older);
        Assert.DoesNotContain("short", older);
        Assert.Contains("short", File.ReadAllText(sink.PathFor(0)));
    }

    [Fact]
    public void The_number_of_files_never_exceeds_the_configured_cap()
    {
        var sink = new LogFileSink(DirectoryFor("cap"), maxBytesPerFile: 64, maxFiles: 3);
        for (int i = 0; i < 40; i++) sink.Append(Entry("entry-" + Number(i)));

        string[] files = Directory.GetFiles(DirectoryFor("cap"), "*.log");
        Assert.Equal(3, files.Length);
        foreach (string file in files)
        {
            foreach (string line in File.ReadAllLines(file))
                Assert.StartsWith("2026-10-30 00:00:00.000 [Info] test entry-", line);
        }
    }

    [Fact]
    public void An_unwritable_location_is_reported_without_breaking_the_log()
    {
        string blocker = Path.Combine(_root, "blocker");
        File.WriteAllText(blocker, "not a directory");
        var sink = new LogFileSink(Path.Combine(blocker, "logs"));
        var log = new LogService(sink);

        log.Info("test", "still recorded");

        Assert.NotNull(sink.LastError);
        Assert.Single(log.Entries);
        Assert.Equal("still recorded", log.Entries[0].Message);
    }

    [Fact]
    public void The_file_keeps_the_lines_that_the_memory_ring_pushes_out()
    {
        var sink = new LogFileSink(DirectoryFor("history"));
        var log = new LogService(sink);
        for (int i = 0; i < 2005; i++) log.Info("test", "entry-" + Number(i));

        Assert.Equal(2000, log.Entries.Count);
        Assert.Equal(2005, File.ReadLines(sink.CurrentPath).Count());
        string onDisk = File.ReadAllText(sink.CurrentPath);
        Assert.Contains("entry-0000", onDisk);
        Assert.Contains("entry-2004", onDisk);
    }

    [Fact]
    public void Credentials_are_already_gone_before_the_line_reaches_the_disk()
    {
        const string Secret = "SUPERSECRET-ON-DISK";
        var sink = new LogFileSink(DirectoryFor("redact"));
        var log = new LogService(sink);
        log.Info("net", "GET http://127.0.0.1:8096/Items?api_key=" + Secret);
        log.Error("net", "request failed", new InvalidOperationException("Authorization: Bearer " + Secret));

        string onDisk = File.ReadAllText(sink.CurrentPath);
        Assert.DoesNotContain(Secret, onDisk);
        Assert.Contains("api_key=***", onDisk);
        Assert.Contains("Authorization: ***", onDisk);
    }

    [Fact]
    public void The_export_points_at_the_rolling_directory_only_when_one_is_configured()
    {
        var plain = new LogService();
        Assert.Null(plain.FileSink);
        Assert.DoesNotContain("滚动文件目录", plain.Export());

        string directory = DirectoryFor("export");
        var withSink = new LogService(new LogFileSink(directory));
        withSink.Info("test", "hello");
        string exported = withSink.Export();
        Assert.Contains("已脱敏", exported);
        Assert.Contains("滚动文件目录：" + directory, exported);
        Assert.Contains("hello", exported);
    }

    [Fact]
    public void Constructor_rejects_impossible_settings()
    {
        Assert.Throws<ArgumentException>(() => new LogFileSink(""));
        Assert.Throws<ArgumentOutOfRangeException>(() => new LogFileSink(DirectoryFor("x"), maxBytesPerFile: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new LogFileSink(DirectoryFor("x"), maxFiles: 0));
    }
}

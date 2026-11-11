// The in-memory ring (LogService.MaxEntries) is enough for the settings page, but not for a bug
// report: by the time a long playback session misbehaves, the interesting early lines have already
// been pushed out. This sink adds a bounded, size-rotated file trail so the newest few megabytes
// always survive a restart.
using System;
using System.IO;
using System.Text;

namespace MediaHub.Net;

/// <summary>
/// Appends already-redacted diagnostic lines to a bounded set of rolling files on disk.
/// </summary>
/// <remarks>
/// <para>
/// Redaction is not this type business: it only ever receives text that already left
/// <see cref="LogService.Redact"/>. Keeping it that way matters, because a second redaction pass
/// here would create the illusion that callers may hand it raw text.
/// </para>
/// <para>
/// File names are <c>mediahub-0.log</c> (newest) through <c>mediahub-(MaxFiles-1).log</c> (oldest).
/// Rotation happens between entries, never inside one, so every line in every file is one complete
/// entry even when a single entry is larger than the size limit.
/// </para>
/// <para>
/// Disk problems must never take the app down: a failing write is swallowed and remembered in
/// <see cref="LastError"/>, while the caller keeps working from the in-memory log.
/// </para>
/// </remarks>
public sealed class LogFileSink
{
    public const long DefaultMaxBytesPerFile = 1024 * 1024;
    public const int DefaultMaxFiles = 5;

    private readonly object _gate = new();
    private readonly string _directory;
    private readonly string _fileNamePrefix;
    private readonly long _maxBytesPerFile;
    private readonly int _maxFiles;

    /// <param name="directory">Where the files live; created on first write.</param>
    /// <param name="fileNamePrefix">Prefix for generated names, for example <c>mediahub-</c>.</param>
    /// <param name="maxBytesPerFile">Rotate before appending an entry that would exceed this.</param>
    /// <param name="maxFiles">How many files to keep; older ones are deleted on rotation.</param>
    public LogFileSink(string directory, string fileNamePrefix = "mediahub-",
        long maxBytesPerFile = DefaultMaxBytesPerFile, int maxFiles = DefaultMaxFiles)
    {
        if (string.IsNullOrWhiteSpace(directory))
            throw new ArgumentException("A log directory is required.", nameof(directory));
        if (maxBytesPerFile <= 0)
            throw new ArgumentOutOfRangeException(nameof(maxBytesPerFile), "The per-file limit must be positive.");
        if (maxFiles <= 0)
            throw new ArgumentOutOfRangeException(nameof(maxFiles), "At least one file must be kept.");

        _directory = directory;
        _fileNamePrefix = string.IsNullOrEmpty(fileNamePrefix) ? "mediahub-" : fileNamePrefix;
        _maxBytesPerFile = maxBytesPerFile;
        _maxFiles = maxFiles;

        try { Directory.CreateDirectory(_directory); }
        catch (Exception ex) when (IsRecoverableDiskFailure(ex)) { LastError = Describe(ex); }
    }

    public string DirectoryPath => _directory;

    public long MaxBytesPerFile => _maxBytesPerFile;

    public int MaxFiles => _maxFiles;

    /// <summary>The most recent write failure, or <c>null</c> when the last append succeeded.</summary>
    public string? LastError { get; private set; }

    /// <summary>Path of the file new entries are appended to; index 0 is always the newest.</summary>
    public string PathFor(int index) => Path.Combine(_directory, _fileNamePrefix + index + ".log");

    /// <summary>Path of the newest file.</summary>
    public string CurrentPath => PathFor(0);

    /// <summary>Appends one already-redacted entry; never throws.</summary>
    public void Append(LogEntry entry) => Append(Format(entry));

    /// <summary>Appends one already-redacted line; never throws.</summary>
    public void Append(string? line)
    {
        if (line is null) return;
        byte[] bytes = Encoding.UTF8.GetBytes(line + "\n");
        lock (_gate)
        {
            try
            {
                Directory.CreateDirectory(_directory);
                string current = PathFor(0);
                long currentLength = File.Exists(current) ? new FileInfo(current).Length : 0;
                // An entry is appended whole even when it alone exceeds the limit; the next entry
                // then starts a new file. Splitting inside an entry would corrupt the transcript.
                if (currentLength > 0 && currentLength + bytes.Length > _maxBytesPerFile) Rotate();
                using (var stream = new FileStream(current, FileMode.Append, FileAccess.Write, FileShare.Read))
                {
                    stream.Write(bytes, 0, bytes.Length);
                    stream.Flush();
                }
                LastError = null;
            }
            catch (Exception ex) when (IsRecoverableDiskFailure(ex))
            {
                LastError = Describe(ex);
            }
        }
    }

    internal static string Format(LogEntry entry) =>
        entry.Timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff")
        + " [" + entry.Level + "] " + entry.Category + " " + entry.Message;

    /// <summary>Drops the oldest file, shifts the rest one index older, and starts index 0 empty.</summary>
    private void Rotate()
    {
        if (_maxFiles > 1)
        {
            string oldest = PathFor(_maxFiles - 1);
            if (File.Exists(oldest)) File.Delete(oldest);
            for (int i = _maxFiles - 2; i >= 0; i--)
            {
                string source = PathFor(i);
                if (File.Exists(source)) File.Move(source, PathFor(i + 1), overwrite: true);
            }
        }
        else
        {
            string only = PathFor(0);
            if (File.Exists(only)) File.Delete(only);
        }
    }

    private static bool IsRecoverableDiskFailure(Exception ex) =>
        ex is IOException or UnauthorizedAccessException or System.Security.SecurityException
            or ArgumentException or NotSupportedException;

    private static string Describe(Exception ex) => ex.GetType().Name + ": " + ex.Message;
}

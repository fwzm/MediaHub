using System;
using System.IO;
using System.Text;

namespace MediaHub.Net;

/// <summary>
/// Appends already-redacted diagnostic lines to a bounded set of rolling files on disk.
/// </summary>
public sealed class LogFileSink
{
    public const long DefaultMaxBytesPerFile = 1024 * 1024;
    public const int DefaultMaxFiles = 5;

    private readonly object _gate = new();
    private readonly string _directory;
    private readonly string _fileNamePrefix;
    private readonly long _maxBytesPerFile;
    private readonly int _maxFiles;

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
    public string? LastError { get; private set; }

    public string PathFor(int index) => Path.Combine(_directory, _fileNamePrefix + index + ".log");
    public string CurrentPath => PathFor(0);

    public void Append(LogEntry entry) => Append(Format(entry));

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

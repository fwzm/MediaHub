// Moved from the app project into the network/security library so that redaction is covered by the
// same test suite that covers redirect credential scoping — the two are one security surface.
using System.Collections.ObjectModel;
using System.Text.RegularExpressions;

namespace MediaHub.Net;

public enum LogLevel { Debug, Info, Warn, Error }

public sealed record LogEntry(DateTime Timestamp, LogLevel Level, string Category, string Message);

/// <summary>
/// In-memory diagnostic log with mandatory redaction.
/// </summary>
/// <remarks>
/// Every line passes through <see cref="Redact"/> before it is stored. Media-server URLs routinely
/// carry tokens in query parameters, and exception messages routinely embed full request URLs, so
/// redacting at write time — rather than at export time — is the only way to guarantee that no
/// diagnostic surface can leak a credential. Export is therefore safe by construction.
/// </remarks>
public sealed class LogService
{
    private const int MaxEntries = 2000;
    private readonly object _gate = new();
    private readonly ObservableCollection<LogEntry> _entries = new();

    public ReadOnlyObservableCollection<LogEntry> Entries { get; }

    /// <summary>
    /// Optional bounded rolling-file trail. Null for callers that only want the memory buffer; the
    /// app passes one so the earliest lines survive a restart. The sink receives entries already
    /// redacted here, never raw text.
    /// </summary>
    public LogFileSink? FileSink { get; }

    public LogService(LogFileSink? fileSink = null)
    {
        FileSink = fileSink;
        Entries = new ReadOnlyObservableCollection<LogEntry>(_entries);
    }

    public event EventHandler<LogEntry>? EntryAdded;

    public void Debug(string category, string message) => Write(LogLevel.Debug, category, message);
    public void Info(string category, string message) => Write(LogLevel.Info, category, message);
    public void Warn(string category, string message) => Write(LogLevel.Warn, category, message);

    public void Error(string category, string message, Exception? ex = null)
    {
        // Only the exception type and a redacted message are recorded. The full ToString() is
        // deliberately not used: it can embed request URLs that carry credentials.
        string suffix = ex is null ? "" : " [" + ex.GetType().Name + ": " + Redact(ex.Message) + "]";
        Write(LogLevel.Error, category, message + suffix);
    }

    private void Write(LogLevel level, string category, string message)
    {
        var entry = new LogEntry(DateTime.Now, level, category, Redact(message));
        lock (_gate)
        {
            _entries.Add(entry);
            while (_entries.Count > MaxEntries) _entries.RemoveAt(0);
        }
        // Durable trail first: it is the only copy that outlives the process, and a UI subscriber
        // must not be able to prevent it. The sink swallows disk failures by design.
        FileSink?.Append(entry);
        EntryAdded?.Invoke(this, entry);
    }

    /// <summary>Builds a redacted diagnostic report suitable for attaching to a bug report.</summary>
    public string Export() => DiagnosticsReport.Render(Snapshot(), DateTime.Now, rollingDirectory: FileSink?.DirectoryPath);

    /// <summary>A copy of the entries, taken under the write lock.</summary>
    public List<LogEntry> Snapshot()
    {
        lock (_gate) return _entries.ToList();
    }

    // ------------------------------------------------------------------ redaction

    /// <summary>Query-string keys whose value must never be printed.</summary>
    internal static readonly string[] SensitiveQueryKeys =
    {
        "api_key", "apikey", "api-key", "token", "access_token", "accesstoken",
        "x-emby-token", "x-mediabrowser-token", "playsessionid", "play_session_id",
        "password", "pwd", "passwd", "session", "auth", "authorization", "signature", "sig",
        "apikey", "deviceid", "accesstoken",
    };

    /// <summary>Header names whose value must never be printed.</summary>
    internal static readonly string[] SensitiveHeaders =
    {
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-emby-token", "x-mediabrowser-token",
        "x-emby-authorization", "x-mediabrowser-authorization",
    };

    private const RegexOptions BaseOptions = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;

    // Header lines: "Authorization: Bearer abc" / "X-Emby-Token: abc" / "Cookie: a=b".
    private static readonly Regex HeaderLineRegex = new(
        @"^(?<indent>[ \t]*)(?<name>[A-Za-z0-9\-]+)(?<sep>[ \t]*:[ \t]*)(?<value>.*)$",
        BaseOptions | RegexOptions.Multiline | RegexOptions.Compiled);

    // URL query pairs: keeps the delimiter so the surrounding URL stays readable.
    private static readonly Regex QueryPairRegex = new(
        @"(?<lead>[?&])(?<key>[A-Za-z0-9_.\-]*)(?<eq>=)(?<value>[^&\s""']*)",
        BaseOptions | RegexOptions.Compiled);

    // URL user-info: https://user:pass@host -> https://***@host
    private static readonly Regex UserInfoRegex = new(
        @"(https?://)[^/\s@]+@",
        BaseOptions | RegexOptions.Compiled);

    // Bare key=value / key: value pairs (header builders, option dumps, query fragments).
    private static readonly Regex BareKeyValueRegex = new(
        @"(?<key>[A-Za-z0-9_.\-]+)(?<sep>[ \t]*[:=][ \t]*)(?<value>[^\s,;""']+)",
        BaseOptions | RegexOptions.Compiled);

    // Token-shaped values that appear with no key at all.
    private static readonly Regex JwtRegex = new(
        @"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{4,}",
        RegexOptions.Compiled);

    private static readonly Regex LongHexRegex = new(
        @"\b[0-9a-f]{32,}\b",
        BaseOptions | RegexOptions.Compiled);

    private static bool IsSensitiveKey(string key)
    {
        foreach (string k in SensitiveQueryKeys)
        {
            if (string.Equals(k, key, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private static bool IsSensitiveHeader(string name)
    {
        foreach (string h in SensitiveHeaders)
        {
            if (string.Equals(h, name, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    /// <summary>
    /// Removes credentials from a free-form string. Safe to call on arbitrary text, including
    /// strings that contain no credentials at all.
    /// </summary>
    public static string Redact(string? input)
    {
        if (string.IsNullOrEmpty(input)) return input ?? "";

        string s = input;

        // 1. Header lines ("Authorization: Bearer ...", "Cookie: ...").
        s = HeaderLineRegex.Replace(s, m =>
            IsSensitiveHeader(m.Groups["name"].Value)
                ? m.Groups["indent"].Value + m.Groups["name"].Value + m.Groups["sep"].Value + "***"
                : m.Value);

        // 2. URL query parameters with credential-bearing keys.
        s = QueryPairRegex.Replace(s, m =>
            IsSensitiveKey(m.Groups["key"].Value)
                ? m.Groups["lead"].Value + m.Groups["key"].Value + m.Groups["eq"].Value + "***"
                : m.Value);

        // 3. URL user-info (https://user:pass@host).
        s = UserInfoRegex.Replace(s, "$1***@");

        // 4. Bare key=value / key: value pairs.
        s = BareKeyValueRegex.Replace(s, m =>
            IsSensitiveKey(m.Groups["key"].Value)
                ? m.Groups["key"].Value + m.Groups["sep"].Value + "***"
                : m.Value);

        // 5. Token-shaped values that carry no key at all.
        s = JwtRegex.Replace(s, "***jwt***");
        s = LongHexRegex.Replace(s, "***");

        return s;
    }
}
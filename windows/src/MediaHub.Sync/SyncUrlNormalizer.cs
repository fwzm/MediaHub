using System.Globalization;
using System.Text.RegularExpressions;

namespace MediaHub.Sync;

/// <summary>
/// The sync layer's own address normalisation (ADR-S001 D4), deliberately not shared with either client.
/// </summary>
/// <remarks>
/// <para>
/// The two clients normalise differently: Android always writes the port, including the scheme default;
/// Windows omits a default port. Reusing either implementation would give one server two identities — the
/// exact defect the audit found in §5.2 — and turn one source into two on every sync round trip. The
/// contract therefore defines its own canonical form:
/// </para>
/// <list type="bullet">
/// <item><description>scheme and host lowercased;</description></item>
/// <item><description>port always written for http/https, defaulted to 80/443 when the input had none;</description></item>
/// <item><description>trailing slashes removed (the root path becomes empty), the rest of the path kept as the input escaped it;</description></item>
/// <item><description>query and fragment dropped — they carry session parameters, not identity;</description></item>
/// <item><description>user info stripped, so <c>https://alice:hunter2@host</c> can never become part of an identity
/// (and is never echoed back in a message).</description></item>
/// </list>
/// <para>
/// The result is idempotent: normalising it again returns it unchanged. Anything that is not an absolute
/// address this client could dial (http, https, smb, file) returns <c>null</c> instead of a half-normalised
/// string; callers read "no identity" as "this source cannot be matched by address".
/// </para>
/// </remarks>
public static class SyncUrlNormalizer
{
    private static readonly Regex ExplicitScheme = new(
        "^[a-zA-Z][a-zA-Z0-9+.-]*://",
        RegexOptions.CultureInvariant);

    // Only the schemes a Windows source can actually carry. A scheme outside this set is refused rather
    // than guessed at: inventing a form for it would silently become part of the protocol.
    private static readonly HashSet<string> SupportedSchemes = new(StringComparer.OrdinalIgnoreCase)
    {
        "http",
        "https",
        "smb",
        "file",
    };

    /// <summary>
    /// Normalises <paramref name="url"/>, or returns null when it is not an address this client could dial.
    /// </summary>
    public static string? Normalize(string? url) => TryNormalize(url, out string? normalized) ? normalized : null;

    /// <summary>
    /// Normalises <paramref name="url"/>; <paramref name="normalized"/> is only meaningful when true is returned.
    /// </summary>
    public static bool TryNormalize(string? url, out string? normalized)
    {
        // Try-pattern convention: the out value is only meaningful when true is returned.
        normalized = null;

        if (string.IsNullOrWhiteSpace(url)) return false;

        string text = url.Trim();

        if (text.StartsWith("//", StringComparison.Ordinal))
        {
            // Protocol-relative input ("//host/path") is what a pasted address often looks like.
            text = "http:" + text;
        }
        else if (!ExplicitScheme.IsMatch(text))
        {
            // A bare host is the shape users type most often, and reading it as http keeps
            // "host:8096" and "http://host:8096" the same identity. A scheme-less value carrying "@"
            // is a pasted credential (or a non-URI such as "mailto:…"), never a source address, so it
            // is refused instead of being reinterpreted with a host invented around the "@".
            if (text.Contains('@')) return false;
            text = "http://" + text;
        }

        if (!Uri.TryCreate(text, UriKind.Absolute, out Uri? uri)) return false;

        string scheme = uri.Scheme.ToLowerInvariant();
        if (!SupportedSchemes.Contains(scheme)) return false;

        bool isHttp = scheme is "http" or "https";

        // "file:///C:/media" is a legal absolute address without a host; every other supported scheme
        // has to name one.
        if (scheme != "file" && uri.Host.Length == 0) return false;

        string authority;
        if (isHttp)
        {
            // Uri reports IsDefaultPort for exactly the pairs that have one, so an omitted port is
            // completed here — the opposite of the Windows client, on purpose (D4).
            int port = uri.IsDefaultPort ? (scheme == "https" ? 443 : 80) : uri.Port;
            if (port <= 0) return false;
            authority = uri.Host.ToLowerInvariant() + ":" + port.ToString(CultureInfo.InvariantCulture);
        }
        else if (!uri.IsDefaultPort && uri.Port > 0)
        {
            // No default port is defined for smb/file, so none is invented; an explicit one is kept.
            authority = uri.Host.ToLowerInvariant() + ":" + uri.Port.ToString(CultureInfo.InvariantCulture);
        }
        else
        {
            authority = uri.Host.ToLowerInvariant();
        }

        // AbsolutePath keeps the escaping the input implied, so a path can be compared without being
        // re-encoded by hand. "/" is the absence of a path.
        string path = uri.AbsolutePath;
        while (path.Length > 1 && path.EndsWith('/')) path = path[..^1];
        if (path == "/") path = "";

        normalized = scheme + "://" + authority + path;
        return true;
    }
}

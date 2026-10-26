using System.Text;
using MediaHub.Net;

namespace MediaHub.Providers.WebDav;

/// <summary>
/// Why a server-supplied reference (href) was refused.
/// </summary>
/// <remarks>
/// Every rejection is fail-closed: the caller drops the entry and never issues a request for it.
/// The reasons are enumerated rather than collapsed into a single "invalid" value because the
/// incidents differ — a hostile href and a server that returns references it should not are not the
/// same problem, and the diagnostics panel has to be able to tell them apart.
/// </remarks>
public enum WebDavHrefRejection
{
    None = 0,
    Empty,
    ControlCharacter,
    Unparseable,
    NonHttpScheme,
    UserInfo,
    Traversal,
    DifferentOrigin,
    OutsideRoot,
}

/// <summary>Outcome of resolving one href against a request URI.</summary>
public readonly record struct WebDavHrefResult(Uri? Url, WebDavHrefRejection Rejection)
{
    public bool Ok => Rejection == WebDavHrefRejection.None && Url is not null;

    public static WebDavHrefResult Refused(WebDavHrefRejection reason) => new(null, reason);

    public static WebDavHrefResult Resolved(Uri url) => new(url, WebDavHrefRejection.None);
}

/// <summary>
/// WebDAV URL handling: the single place where a server-supplied string becomes a request target.
/// </summary>
/// <remarks>
/// <para>
/// Ported from the Android client's <c>WebDavUrls</c> and tightened where the Windows contract asks
/// for more than same-origin. The Android implementation enforced origin equality and rejected
/// user-info; on Windows the configured source also has a <i>root</i>, and a reference that leaves
/// that root is refused as well. A source is a subtree the user configured, not every path the host
/// happens to serve.
/// </para>
/// <para>
/// Threat model. Everything here is untrusted server output: a misconfigured reverse proxy, a
/// compromised NAS or a hostile server can return any href it likes. The concrete hazards are
/// (a) Basic credentials delivered to a third-party host, (b) a request steered outside the
/// configured subtree, and (c) request/header shaping through control characters. All three are
/// decided here, before any request exists.
/// </para>
/// <para>
/// Note on <c>..</c>: a reference containing a dot-dot segment is refused outright rather than
/// normalised. The BCL already normalises it <i>and</i> percent-decoded dot segments, so the
/// containment check alone would catch an escape — except for a source mounted at <c>/</c>, where
/// "outside the root" does not exist and normalisation would happily hand back <c>/etc/passwd</c>.
/// Refusing up front removes that asymmetry; the cost is rejecting the exotic but legal
/// <c>dir/../file.mkv</c> form, which no real PROPFIND response uses.
/// </para>
/// </remarks>
public static class WebDavUrl
{
    /// <summary>Upper bound on a reference we are willing to parse; longer input is refused unread.</summary>
    private const int MaxReferenceLength = 8192;

    private static readonly string[] HttpSchemes = { Uri.UriSchemeHttp, Uri.UriSchemeHttps };

    /// <summary>
    /// Turns a configured source address into an absolute http(s) collection URL.
    /// </summary>
    /// <remarks>
    /// User-info in the address is refused rather than silently stripped: credentials in a URL end up
    /// in logs, in diagnostics exports and in the redirect chain, which is exactly what the
    /// header-only credential rule exists to prevent.
    /// </remarks>
    public static bool TryNormalizeBase(string? raw, out Uri? baseUri, out string? error)
    {
        baseUri = null;
        error = null;

        string value = (raw ?? string.Empty).Trim();
        if (value.Length == 0)
        {
            error = "媒体源地址为空";
            return false;
        }
        if (value.Length > MaxReferenceLength)
        {
            error = "媒体源地址过长";
            return false;
        }
        if (HasControlCharacters(value))
        {
            error = "媒体源地址包含控制字符";
            return false;
        }
        if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? parsed))
        {
            error = "媒体源地址无法解析";
            return false;
        }
        if (!IsHttpScheme(parsed))
        {
            error = "仅支持 http/https 的 WebDAV 地址";
            return false;
        }
        if (string.IsNullOrEmpty(parsed.Host))
        {
            error = "媒体源地址缺少主机名";
            return false;
        }
        if (!string.IsNullOrEmpty(parsed.UserInfo))
        {
            error = "媒体源地址不得内嵌用户名/密码（凭据只通过认证头传递）";
            return false;
        }

        baseUri = AsCollection(parsed);
        return true;
    }

    /// <summary>
    /// Resolves one PROPFIND <c>href</c> against the URI it was returned for, or refuses it.
    /// </summary>
    /// <param name="requestUri">The URI the PROPFIND was issued to; RFC 3986 resolution base.</param>
    /// <param name="href">Raw, untrusted server output. Never assumed to be encoded.</param>
    /// <param name="sourceRoot">The configured source root; the containment boundary.</param>
    public static WebDavHrefResult Resolve(Uri requestUri, string? href, Uri sourceRoot)
    {
        ArgumentNullException.ThrowIfNull(requestUri);
        ArgumentNullException.ThrowIfNull(sourceRoot);

        string raw = (href ?? string.Empty).Trim();
        if (raw.Length == 0) return WebDavHrefResult.Refused(WebDavHrefRejection.Empty);
        if (raw.Length > MaxReferenceLength) return WebDavHrefResult.Refused(WebDavHrefRejection.Unparseable);
        if (HasControlCharacters(raw)) return WebDavHrefResult.Refused(WebDavHrefRejection.ControlCharacter);

        // Scanned before parsing so a traversal is refused even if resolution would have moved it
        // inside the root again ("./a/../b" is normalised away, and that normalisation is the trap).
        if (ContainsTraversalSegment(StripQueryAndFragment(raw)))
        {
            return WebDavHrefResult.Refused(WebDavHrefRejection.Traversal);
        }

        if (!TryResolveTolerant(requestUri, raw, out Uri? resolved) || resolved is null)
        {
            return WebDavHrefResult.Refused(WebDavHrefRejection.Unparseable);
        }

        if (!IsHttpScheme(resolved)) return WebDavHrefResult.Refused(WebDavHrefRejection.NonHttpScheme);
        if (!string.IsNullOrEmpty(resolved.UserInfo)) return WebDavHrefResult.Refused(WebDavHrefRejection.UserInfo);

        // Percent-decoded once, then scanned again: "%2e%2e/" never contains a literal ".." in the
        // encoded form, and a NUL can only be seen after decoding.
        string decodedPath = PercentDecode(resolved.AbsolutePath);
        if (HasControlCharacters(decodedPath)) return WebDavHrefResult.Refused(WebDavHrefRejection.ControlCharacter);
        if (ContainsTraversalSegment(decodedPath)) return WebDavHrefResult.Refused(WebDavHrefRejection.Traversal);

        if (!string.Equals(OriginOf(resolved), OriginOf(sourceRoot), StringComparison.OrdinalIgnoreCase))
        {
            return WebDavHrefResult.Refused(WebDavHrefRejection.DifferentOrigin);
        }
        if (!IsUnderRoot(sourceRoot, resolved))
        {
            return WebDavHrefResult.Refused(WebDavHrefRejection.OutsideRoot);
        }

        return WebDavHrefResult.Resolved(resolved);
    }

    /// <summary>Origin key (<c>scheme://host:port</c>) of a URI — the credential boundary.</summary>
    public static string OriginOf(Uri uri) => UrlRedactor.OriginOf(uri);

    /// <summary>True when the URI's scheme is http or https.</summary>
    public static bool IsHttpScheme(Uri uri)
        => HttpSchemes.Contains(uri.Scheme, StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// True when <paramref name="candidate"/> is the source root itself or lives beneath it.
    /// </summary>
    /// <remarks>
    /// Path comparison is case-insensitive: WebDAV servers on Windows and on case-insensitive NAS
    /// filesystems answer <c>/DAV/</c> for a configured <c>/dav/</c>, and refusing those would break
    /// working sources without buying anything — an attacker who can make the server lie about the
    /// casing is the server, and the origin check is what actually stops credentials moving.
    /// </remarks>
    public static bool IsUnderRoot(Uri root, Uri candidate)
    {
        string rootPath = root.AbsolutePath;
        if (!rootPath.EndsWith('/')) rootPath += "/";

        // A source mounted at the host root has nothing to escape to: every path on this origin is
        // inside it. Traversal is still refused above, which is the check that matters there.
        if (rootPath == "/") return true;

        string path = candidate.AbsolutePath;
        if (!path.EndsWith('/')) path += "/";
        return path.StartsWith(rootPath, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// True when any path segment is literally <c>..</c> or percent-encodes to it.
    /// </summary>
    /// <remarks>Both separators are scanned: a proxy that maps <c>\</c> onto <c>/</c> would otherwise
    /// turn <c>..\..\x</c> into a traversal after our check.</remarks>
    public static bool ContainsTraversalSegment(string pathOrReference)
    {
        foreach (string segment in pathOrReference.Split('/', '\\'))
        {
            if (segment.Length == 0) continue;
            if (PercentDecode(segment) == "..") return true;
        }
        return false;
    }

    /// <summary>True when the value contains a control character (including NUL and DEL).</summary>
    public static bool HasControlCharacters(string value)
    {
        foreach (char c in value)
        {
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    /// <summary>
    /// Percent-decodes <c>%XX</c> sequences, decoding consecutive escapes as one UTF-8 run.
    /// </summary>
    /// <remarks>
    /// <c>+</c> is left alone on purpose: <see cref="Uri.UnescapeDataString"/> and
    /// <c>URLDecoder</c>-style form decoding both turn it into a space, which corrupts a legal file
    /// name such as <c>a+b.mkv</c>. Malformed escapes are preserved verbatim rather than dropped, so
    /// a decoded name never claims a character the href did not contain.
    /// </remarks>
    public static string PercentDecode(string value)
    {
        if (value.IndexOf('%') < 0) return value;

        var result = new StringBuilder(value.Length);
        var pending = new List<byte>(4);

        void Flush()
        {
            if (pending.Count == 0) return;
            result.Append(Encoding.UTF8.GetString(pending.ToArray()));
            pending.Clear();
        }

        for (int i = 0; i < value.Length;)
        {
            if (value[i] == '%' && i + 2 < value.Length && TryParseHexByte(value, i + 1, out int b))
            {
                pending.Add((byte)b);
                i += 3;
                continue;
            }
            Flush();
            result.Append(value[i]);
            i++;
        }
        Flush();
        return result.ToString();
    }

    /// <summary>
    /// Percent-encodes the characters a URI cannot carry raw (space, control, non-ASCII), leaving
    /// valid existing escapes untouched.
    /// </summary>
    /// <remarks>
    /// RFC 4918 requires href to be a URI, but real servers return raw spaces and CJK paths. The BCL
    /// tolerates most of that already, so this is the second attempt: it runs only when the first
    /// parse failed, and it never rewrites a <c>%XX</c> triple that is already well formed.
    /// </remarks>
    public static string EncodeIllegalUrlChars(string value)
    {
        var output = new StringBuilder(value.Length + 16);
        for (int i = 0; i < value.Length;)
        {
            if (value[i] == '%' && i + 2 < value.Length && TryParseHexByte(value, i + 1, out _))
            {
                output.Append(value, i, 3);
                i += 3;
                continue;
            }

            int codePoint = char.ConvertToUtf32(value, i);
            int charCount = char.IsSurrogatePair(value, i) ? 2 : 1;
            bool legal = codePoint is >= 0x21 and <= 0x7E && codePoint != '%';
            if (legal)
            {
                output.Append(value, i, charCount);
            }
            else
            {
                foreach (byte b in Encoding.UTF8.GetBytes(char.ConvertFromUtf32(codePoint)))
                {
                    output.Append('%').Append(b.ToString("X2", System.Globalization.CultureInfo.InvariantCulture));
                }
            }
            i += charCount;
        }
        return output.ToString();
    }

    /// <summary>Display name of a reference: last path segment, percent-decoded.</summary>
    public static string DisplayNameOf(string href)
    {
        string path = StripQueryAndFragment(href).TrimEnd('/');
        int slash = path.LastIndexOf('/');
        string last = slash >= 0 ? path[(slash + 1)..] : path;
        return PercentDecode(last);
    }

    /// <summary>
    /// Key used to recognise "the collection itself" in a Depth:1 response.
    /// </summary>
    /// <remarks>
    /// Origin is deliberately not part of the key: callers only compare entries that already passed
    /// <see cref="Resolve"/>, so both sides are guaranteed to share the source origin.
    /// </remarks>
    public static string CanonicalKey(Uri uri) => uri.AbsolutePath.TrimEnd('/').ToLowerInvariant();

    /// <summary>Same URI with a trailing slash on the path (collection semantics for PROPFIND).</summary>
    public static Uri AsCollection(Uri uri)
    {
        string path = uri.AbsolutePath;
        return path.EndsWith('/') ? WithoutFragment(uri) : Rebuild(uri, path + "/");
    }

    /// <summary>Parent collection of a resource URI.</summary>
    public static Uri ParentCollection(Uri uri)
    {
        string path = uri.AbsolutePath;
        int slash = path.LastIndexOf('/');
        if (slash < 0) return Rebuild(uri, "/");
        return Rebuild(uri, path[..(slash + 1)]);
    }

    /// <summary>Human-readable reason, for provider error messages and the diagnostics panel.</summary>
    public static string Describe(WebDavHrefRejection rejection) => rejection switch
    {
        WebDavHrefRejection.None => "OK",
        WebDavHrefRejection.Empty => "空引用",
        WebDavHrefRejection.ControlCharacter => "引用包含控制字符",
        WebDavHrefRejection.Unparseable => "引用无法解析",
        WebDavHrefRejection.NonHttpScheme => "引用不是 http/https",
        WebDavHrefRejection.UserInfo => "引用内嵌用户名/密码",
        WebDavHrefRejection.Traversal => "引用包含目录穿越",
        WebDavHrefRejection.DifferentOrigin => "引用指向其它来源",
        WebDavHrefRejection.OutsideRoot => "引用超出媒体源根目录",
        _ => "未知原因",
    };

    private static bool TryResolveTolerant(Uri baseUri, string raw, out Uri? resolved)
    {
        if (Uri.TryCreate(baseUri, raw, out resolved) && resolved is not null) return true;
        return Uri.TryCreate(baseUri, EncodeIllegalUrlChars(raw), out resolved) && resolved is not null;
    }

    private static bool TryParseHexByte(string value, int start, out int parsed)
    {
        parsed = 0;
        if (start + 1 >= value.Length) return false;
        int high = HexValue(value[start]);
        int low = HexValue(value[start + 1]);
        if (high < 0 || low < 0) return false;
        parsed = (high << 4) | low;
        return true;
    }

    private static int HexValue(char c) => c switch
    {
        >= '0' and <= '9' => c - '0',
        >= 'a' and <= 'f' => c - 'a' + 10,
        >= 'A' and <= 'F' => c - 'A' + 10,
        _ => -1,
    };

    private static string StripQueryAndFragment(string reference)
    {
        int cut = reference.IndexOfAny(new[] { '?', '#' });
        return cut < 0 ? reference : reference[..cut];
    }

    private static Uri WithoutFragment(Uri uri)
        => string.IsNullOrEmpty(uri.Fragment) ? uri : Rebuild(uri, uri.AbsolutePath);

    /// <summary>
    /// Rebuilds the URI from the already-escaped absolute path, never through <c>UriBuilder.Path</c>.
    /// </summary>
    /// <remarks>
    /// <c>UriBuilder.Path</c> treats its input as unescaped and would escape the <c>%</c> of an
    /// existing <c>%E4</c> escape into <c>%25E4</c>, silently breaking every non-ASCII file name.
    /// </remarks>
    private static Uri Rebuild(Uri uri, string escapedPath)
        => new(uri.Scheme + "://" + uri.Authority + escapedPath + uri.Query, UriKind.Absolute);
}

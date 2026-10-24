using System.Globalization;
using System.Text.RegularExpressions;
using MediaHub.Domain;

namespace MediaHub.Net.Security;

/// <summary>A server address after normalisation.</summary>
/// <param name="BaseUri">Canonical base address: lowercase scheme, host, an explicit non-default port, and
/// any reverse-proxy sub-path, with no trailing slash.</param>
/// <param name="ApiRoot">Where the dialect's API lives. Emby adds <c>/emby</c>; Jellyfin serves its API at the
/// base address itself, sub-path included. It carries no trailing slash, so request paths are composed by
/// concatenation (which is what the dialect layer does) rather than by Uri relative resolution, which would
/// silently drop the last segment of a reverse-proxy sub-path.</param>
/// <param name="DisplayText">User-facing rendering of <paramref name="BaseUri"/>. The scheme default port
/// (80/443) is omitted so that one origin has exactly one spelling in the UI.</param>
/// <param name="HadExplicitPort">True when the input carried a port token, including one equal to the scheme
/// default, which <paramref name="DisplayText"/> then renders without. It describes what the user typed, not
/// what the normalised address happens to carry.</param>
/// <param name="HadSubPath">True when the input carried a path beyond "/", i.e. the server sits behind a
/// reverse proxy. It describes the user's input rather than <paramref name="ApiRoot"/>, which may gain a
/// dialect suffix on its own.</param>
public sealed record NormalizedServerUrl(
    Uri BaseUri,
    Uri ApiRoot,
    string DisplayText,
    bool HadExplicitPort,
    bool HadSubPath);

/// <summary>
/// Turns what a user types into the one canonical address the client stores and dials.
/// </summary>
/// <remarks>
/// <para>
/// The point of normalising rather than storing the raw text is that the same server must produce one
/// identity: origin-scoped credential rules, the settings list and the connection diagnostics all compare
/// origins by string. Two spellings of one host (a trailing slash, an uppercase scheme, a default port) would
/// otherwise look like two origins, and a token issued for one would not be sent to the other.
/// </para>
/// <para>
/// Only http and https are accepted, and the address must be a bare base: a query or a fragment is refused
/// because Emby and Jellyfin both accept credentials as query parameters, and a base address is stored in
/// clear text next to the vault (ADR-026, tokens never travel in a URL).
/// </para>
/// </remarks>
public static class ServerUrlNormalizer
{
    private static readonly Regex ExplicitScheme = new(
        "^[a-zA-Z][a-zA-Z0-9+.-]*://",
        RegexOptions.CultureInvariant);

    /// <summary>
    /// Normalises <paramref name="input"/> for a source of the given <paramref name="type"/>.
    /// </summary>
    /// <param name="input">What the user typed: a bare host, an optional port, an optional scheme and an
    /// optional reverse-proxy sub-path.</param>
    /// <param name="type">Dialect of the source, which decides where its API root is.</param>
    /// <param name="result">The normalised address; null when false is returned.</param>
    /// <param name="error">A specific, user-facing reason for a rejection; null when true is returned.</param>
    /// <returns>True when the input is a usable http(s) server address.</returns>
    public static bool TryNormalize(string input, ServerType type, out NormalizedServerUrl result, out string? error)
    {
        // Try-pattern convention: the out value is only meaningful when true is returned.
        result = null!;

        if (string.IsNullOrWhiteSpace(input))
        {
            error = "请输入服务器地址。";
            return false;
        }

        string text = input.Trim();

        // Refused before parsing so that each rejection has one reason. Both shapes change which resource is
        // addressed, and neither can be part of a stored base address.
        if (text.Contains('#'))
        {
            error = "服务器地址不能包含片段（#…），请只填写基地址。";
            return false;
        }

        if (text.Contains('?'))
        {
            error = "服务器地址不能包含查询参数（?…），请只填写基地址。";
            return false;
        }

        // Without a scheme, "media.example.com:8096" parses as a URI whose scheme is "media.example.com". A
        // bare host is the shape users type most often, so it is given one explicitly.
        if (text.StartsWith("//", StringComparison.Ordinal))
            text = "http:" + text;
        else if (!ExplicitScheme.IsMatch(text))
            text = "http://" + text;

        if (!Uri.TryCreate(text, UriKind.Absolute, out Uri? uri))
        {
            error = "无法解析服务器地址，请检查主机名与端口。";
            return false;
        }

        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase)
            && !string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            // Only the scheme is echoed back. The rest of the input is never repeated in an error: a message
            // reaches logs and support bundles, and users paste addresses with credentials in them.
            error = "只支持 http 或 https，当前为 " + uri.Scheme + "。";
            return false;
        }

        if (uri.UserInfo.Length > 0)
        {
            error = "服务器地址不能内嵌用户名或密码，请在登录信息中单独填写。";
            return false;
        }

        if (string.IsNullOrEmpty(uri.Host))
        {
            error = "服务器地址缺少主机名。";
            return false;
        }

        // AbsolutePath keeps the escaping the input implied ("/media x" becomes "/media%20x"), so a sub-path
        // can be compared and rendered without re-encoding it by hand. "/" is the absence of a path.
        string path = uri.AbsolutePath;
        while (path.Length > 1 && path.EndsWith('/')) path = path[..^1];
        if (path == "/") path = "";

        // Uri already reports IsDefaultPort for exactly the pairs that have one (80 on http, 443 on https), so
        // the default port never reaches the rendered address.
        string scheme = uri.Scheme.ToLowerInvariant();
        string authority = uri.IsDefaultPort
            ? uri.Host
            : uri.Host + ":" + uri.Port.ToString(CultureInfo.InvariantCulture);
        string baseText = scheme + "://" + authority + path;
        var baseUri = new Uri(baseText, UriKind.Absolute);

        Uri apiRoot = type switch
        {
            // Emby serves its API under a fixed /emby prefix, and a user who already typed it must not end up
            // with /emby/emby.
            ServerType.Emby => EndsWithSegment(path, "emby")
                ? baseUri
                : new Uri(baseText + "/emby", UriKind.Absolute),

            // Jellyfin answers at the base address itself, so a reverse-proxy sub-path is already part of the
            // API root and nothing is appended.
            _ => baseUri,
        };

        result = new NormalizedServerUrl(baseUri, apiRoot, baseText, HasPortToken(uri), path.Length > 1);
        error = null;
        return true;
    }

    private static bool EndsWithSegment(string path, string segment)
        => path.EndsWith("/" + segment, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Whether the input carried a port token.
    /// </summary>
    /// <remarks>
    /// Read from the original string because <see cref="Uri"/> hides a port equal to the scheme default:
    /// "http://host:80" and "http://host" are the same URI to it, while the user typed two different things and
    /// the diagnostics panel may want to say so.
    /// </remarks>
    private static bool HasPortToken(Uri uri)
    {
        string original = uri.OriginalString;
        int schemeEnd = original.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd < 0) return false;

        int start = schemeEnd + 3;
        int end = original.IndexOfAny(['/', '?', '#'], start);
        ReadOnlySpan<char> authority = end < 0
            ? original.AsSpan(start)
            : original.AsSpan(start, end - start);

        int colon = authority.LastIndexOf(':');
        if (colon < 0) return false;

        // An IPv6 literal brings colons of its own, so only a colon after the closing bracket can introduce a
        // port ("http://[::1]:8096"); the character after it must start the digits.
        return authority.LastIndexOf(']') < colon
            && colon + 1 < authority.Length
            && char.IsAsciiDigit(authority[colon + 1]);
    }
}

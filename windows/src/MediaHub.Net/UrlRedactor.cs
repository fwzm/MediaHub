namespace MediaHub.Net;

/// <summary>
/// Produces a safe, still-diagnostic rendering of a URL.
/// </summary>
/// <remarks>
/// Media-server URLs routinely carry credentials: Emby/Jellyfin accept <c>api_key</c> as a query
/// parameter, cloud-drive play URLs carry signed tokens, and WebDAV URLs may embed user-info. A URL
/// must therefore never reach a log, an exception message or a diagnostics export unredacted.
/// Only the query keys are dropped, not the whole query, so the shape of a failing request stays
/// diagnosable.
/// </remarks>
public static class UrlRedactor
{
    private static readonly HashSet<string> SensitiveKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "api_key", "apikey", "api-key", "token", "access_token", "accesstoken",
        "x-emby-token", "x-mediabrowser-token", "playsessionid", "play_session_id",
        "password", "pwd", "passwd", "session", "auth", "authorization", "signature", "sig",
    };

    /// <summary>Renders <paramref name="uri"/> without user-info and with credential query values masked.</summary>
    public static string Redact(Uri? uri)
    {
        if (uri is null) return "";

        string authority = uri.IsDefaultPort ? uri.Host : uri.Host + ":" + uri.Port;
        string path = uri.AbsolutePath;

        if (string.IsNullOrEmpty(uri.Query) || uri.Query == "?") return uri.Scheme + "://" + authority + path;

        var parts = new List<string>();
        foreach (string pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = pair.IndexOf('=');
            string key = eq >= 0 ? pair[..eq] : pair;
            parts.Add(SensitiveKeys.Contains(Uri.UnescapeDataString(key)) ? key + "=***" : pair);
        }
        return uri.Scheme + "://" + authority + path + "?" + string.Join("&", parts);
    }

    /// <summary>Builds the origin key (scheme + host + port) used to decide whether credentials may travel.</summary>
    public static string OriginOf(Uri uri)
        => uri.Scheme + "://" + uri.Host + ":" + uri.Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
}

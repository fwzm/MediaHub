namespace MediaHub.TestSupport;

/// <summary>
/// One HTTP request exactly as the mock server received it.
/// </summary>
/// <remarks>
/// Capturing headers and bodies is the point of this type rather than an extra: credential scoping
/// (ADR-030) and progress payload rules (ADR-040) are invisible in a status code, so tests assert on
/// what actually crossed the socket. Nothing here is normalised or reconstructed — a header that did
/// not arrive is absent from <see cref="Headers"/>.
/// </remarks>
/// <param name="Method">HTTP method as sent.</param>
/// <param name="Host">Host the client addressed (from the request URL, so it carries the real port).</param>
/// <param name="Port">Port the client addressed.</param>
/// <param name="PathAndQuery">Absolute path plus query string, e.g. <c>/emby/Users/user-1/Items?Limit=10</c>.</param>
/// <param name="Headers">All request headers, case-insensitive keys, raw values.</param>
/// <param name="RangeStart">
/// First byte requested by <c>bytes=a-b</c> or <c>bytes=a-</c>. Null when the header was absent,
/// unparsable, or a suffix range (<c>bytes=-n</c>), in which case only <see cref="RangeEnd"/> is set.
/// </param>
/// <param name="RangeEnd">
/// Last byte requested by <c>bytes=a-b</c>, null for an open-ended <c>bytes=a-</c>, or the suffix
/// length for <c>bytes=-n</c>.
/// </param>
/// <param name="AtUtc">When the server received the request.</param>
public sealed record RecordedRequest(
    string Method,
    string Host,
    int Port,
    string PathAndQuery,
    IReadOnlyDictionary<string, string> Headers,
    long? RangeStart,
    long? RangeEnd,
    DateTimeOffset AtUtc)
{
    /// <summary>Absolute path without the query string.</summary>
    public string Path
    {
        get
        {
            int separator = PathAndQuery.IndexOf('?');
            return separator < 0 ? PathAndQuery : PathAndQuery[..separator];
        }
    }

    /// <summary>Raw query string without the leading <c>?</c>, or an empty string.</summary>
    public string Query
    {
        get
        {
            int separator = PathAndQuery.IndexOf('?');
            return separator < 0 ? string.Empty : PathAndQuery[(separator + 1)..];
        }
    }

    /// <summary>Raw <c>Range</c> header, or null when the client sent none.</summary>
    public string? RangeHeader { get; init; }

    /// <summary>
    /// Request body as text, or null for a request without one. Recorded so tests can assert body
    /// rules (for example that <c>PositionTicks</c> is present even when it is zero).
    /// </summary>
    public string? Body { get; init; }

    /// <summary>Raw value of <paramref name="name"/>, or null when the header did not arrive.</summary>
    public string? Header(string name) => Headers.TryGetValue(name, out string? value) ? value : null;

    public bool HasHeader(string name) => Headers.ContainsKey(name);

    /// <summary>The session token this request presented, or null when it presented none.</summary>
    public string? Token => ExtractToken(Headers);

    /// <summary>
    /// Extracts a MediaBrowser session token from the header forms the two dialects use.
    /// </summary>
    /// <remarks>
    /// Emby sends a dedicated <c>X-Emby-Token</c>/<c>X-MediaBrowser-Token</c>; Jellyfin embeds
    /// <c>Token="..."</c> in the authorization header. Both server-side authentication and the tests'
    /// assertions go through this one method, so "is this request authenticated" cannot drift from
    /// "did the token header arrive".
    /// </remarks>
    public static string? ExtractToken(IReadOnlyDictionary<string, string>? headers)
    {
        if (headers is null) return null;

        foreach (string name in new[] { "X-Emby-Token", "X-MediaBrowser-Token" })
        {
            if (headers.TryGetValue(name, out string? direct) && !string.IsNullOrWhiteSpace(direct)) return direct;
        }

        foreach (string name in new[] { "Authorization", "X-Emby-Authorization", "X-MediaBrowser-Authorization" })
        {
            if (headers.TryGetValue(name, out string? value) && TryReadQuoted(value, "Token") is { Length: > 0 } token)
                return token;
        }

        return null;
    }

    /// <summary>Reads <c>key="value"</c> out of an authorization header, or null when absent.</summary>
    private static string? TryReadQuoted(string header, string key)
    {
        int at = header.IndexOf(key + "=\"", StringComparison.OrdinalIgnoreCase);
        if (at < 0) return null;
        int start = at + key.Length + 2;
        int end = header.IndexOf('"', start);
        return end < 0 ? null : header[start..end];
    }

    public override string ToString()
        => Body is null
            ? $"{Method} {Host}:{Port}{PathAndQuery}"
            : $"{Method} {Host}:{Port}{PathAndQuery} ({Body.Length} byte body)";
}

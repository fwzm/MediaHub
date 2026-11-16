using System.Globalization;

namespace MediaHub.Providers.Http;

/// <summary>One playable entry of an m3u list, with its address already made absolute.</summary>
public sealed record M3uEntry(string? Title, Uri Url, long? DurationSeconds);

/// <summary>
/// Reads the small line-oriented m3u dialect that IPTV lists and download tools emit.
/// </summary>
/// <remarks>
/// Deliberately lenient, because these files are hand-edited: a missing <c>#EXTM3U</c> header, CRLF
/// endings, a BOM, unknown directives and relative addresses are all accepted. A file that turns out
/// to be an HLS manifest is not a list of titles - its own address is the playable item - which the
/// caller decides using <see cref="IsHls"/>.
/// </remarks>
public static class M3uPlaylist
{
    /// <summary>Title declared by <c>#PLAYLIST:</c>, used to name a source instead of its file name.</summary>
    public static string? ReadTitle(string text)
    {
        foreach (string rawLine in text.Split('\n'))
        {
            string line = Clean(rawLine);
            if (line.StartsWith("#PLAYLIST:", StringComparison.OrdinalIgnoreCase))
            {
                string title = line["#PLAYLIST:".Length..].Trim();
                return title.Length == 0 ? null : title;
            }
        }
        return null;
    }

    /// <summary>True when the text is an HLS manifest rather than a list of media files.</summary>
    public static bool IsHls(string text)
        => text.Contains("#EXT-X-", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Parses the list. Entries without an <c>http</c>/<c>https</c> address are dropped: a plain media
    /// source cannot play <c>rtmp://</c>, <c>udp://</c> or a filesystem path.
    /// </summary>
    public static IReadOnlyList<M3uEntry> Parse(string text, Uri? baseUri)
    {
        var entries = new List<M3uEntry>();
        string? title = null;
        long? seconds = null;

        foreach (string rawLine in text.Split('\n'))
        {
            string line = Clean(rawLine);
            if (line.Length == 0) continue;

            if (line.StartsWith("#EXTINF:", StringComparison.OrdinalIgnoreCase))
            {
                string rest = line["#EXTINF:".Length..];
                int comma = rest.IndexOf(',');
                string head = comma >= 0 ? rest[..comma] : rest;
                // The head may be "123.5" or "123.5 tvg-id=..."; only the leading number is the duration.
                int space = head.IndexOf(' ');
                if (space >= 0) head = head[..space];
                seconds = double.TryParse(head, NumberStyles.Float, CultureInfo.InvariantCulture, out double parsed) && parsed >= 0
                    ? (long)Math.Round(parsed)
                    : null;
                title = comma >= 0 ? NullIfBlank(rest[(comma + 1)..]) : null;
                continue;
            }

            // Every other directive (#EXTGRP, #EXTVLCOPT, #KODIPROP, #EXTM3U) is metadata we do not use.
            if (line[0] == '#') continue;

            if (Resolve(line, baseUri) is { } url)
            {
                entries.Add(new M3uEntry(title, url, seconds));
            }
            title = null;
            seconds = null;
        }

        return entries;
    }

    private static Uri? Resolve(string value, Uri? baseUri)
    {
        Uri? url = null;
        if (baseUri is not null)
        {
            Uri.TryCreate(baseUri, value, out url);
        }
        else if (value.Length > 0 && Uri.TryCreate(value, UriKind.Absolute, out Uri? absolute))
        {
            url = absolute;
        }

        if (url is null) return null;
        return url.Scheme is "http" or "https" ? url : null;
    }

    private static string Clean(string line) => line.Trim().TrimStart('\uFEFF').Trim();

    private static string? NullIfBlank(string value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

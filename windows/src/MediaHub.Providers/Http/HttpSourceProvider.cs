using System.Globalization;
using System.Text;
using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.Http;

/// <summary>
/// A plain http(s) media address, or an m3u/m3u8 list of them. No server API, no account, no session.
/// </summary>
/// <remarks>
/// <para>
/// The source is configured once (one URL) and everything below is derived from it: a media address
/// browses to itself, a list browses to its entries. Playback hands the address straight to the
/// coordinator, which proxies it through the same loopback bridge as every other provider, so seeking,
/// resume, subtitles and speed stay on one code path instead of growing a second one here.
/// </para>
/// <para>
/// Progress is deliberately local-only. A static file server has nowhere to put a resume point, so
/// the handle reports nothing upstream and the coordinator's own snapshot store remains the record;
/// <see cref="GetResumePositionAsync"/> therefore answers "unknown" rather than inventing a position.
/// </para>
/// </remarks>
public sealed class HttpSourceProvider : IProviderBrowse, IProviderPlayback, IProviderProgress
{
    /// <summary>A list is text; a mislabelled video must not be pulled into memory to "parse" it.</summary>
    private const int MaxPlaylistBytes = 4 * 1024 * 1024;

    private readonly string _serverId;
    private readonly Uri _source;
    private readonly HttpClient _http;

    public HttpSourceProvider(string serverId, Uri source, HttpClient http)
    {
        _serverId = serverId;
        _source = source;
        _http = http;
    }

    public IReadOnlyDictionary<string, string> StreamRequestHeaders { get; } =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

    public Uri? StreamHeaderOrigin => null;

    public async Task<PagedResult<MediaItem>> ListFolderAsync(MediaItem? folder, PageRequest page, CancellationToken ct = default)
    {
        // There is no tree beneath the configured address: anything that asks for a subfolder is
        // asking for a shape this source does not have.
        if (folder is not null) return PagedResult<MediaItem>.Empty;

        if (!IsPlaylist(_source)) return Page(new[] { ItemFor(_source, null, null) }, page);

        string text = await ReadPlaylistAsync(ct).ConfigureAwait(false);

        // An HLS manifest lists segments, not titles. Its own address is the one playable item, and the
        // player's HLS demuxer is what turns it into video.
        if (M3uPlaylist.IsHls(text)) return Page(new[] { ItemFor(_source, null, null) }, page);

        IReadOnlyList<M3uEntry> entries = M3uPlaylist.Parse(text, _source);
        if (entries.Count == 0)
        {
            throw ProviderException.Connection(_serverId, "播放列表里没有可播放的 http/https 地址");
        }

        var items = new List<MediaItem>(entries.Count);
        foreach (M3uEntry entry in entries)
        {
            items.Add(ItemFor(entry.Url, entry.Title, entry.DurationSeconds));
        }
        return Page(items, page);
    }

    public Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        // The item identity *is* its address, so it is re-validated here instead of trusting whatever
        // reached the queue: a stale hand-edited entry must fail loudly, not hand the engine junk.
        if (!Uri.TryCreate(item.Id, UriKind.Absolute, out Uri? url) || url.Scheme is not ("http" or "https"))
        {
            throw ProviderException.Connection(_serverId, "该条目不是有效的 http/https 地址，已停止播放");
        }

        return Task.FromResult(new PlaybackSource
        {
            Url = url,
            Mode = PlaybackMode.DirectPlay,
            RunTimeTicks = item.RunTimeTicks,
        });
    }

    public Task ReportProgressAsync(PlaybackProgress progress, CancellationToken ct = default) => Task.CompletedTask;

    public Task<IReadOnlyList<MediaItem>> GetContinueWatchingAsync(int limit, CancellationToken ct = default)
        => Task.FromResult<IReadOnlyList<MediaItem>>(Array.Empty<MediaItem>());

    public Task<long?> GetResumePositionAsync(string itemId, CancellationToken ct = default)
        => Task.FromResult<long?>(null);

    // ---------------------------------------------------------------- internals

    private static bool IsPlaylist(Uri uri)
    {
        string path = uri.AbsolutePath;
        return path.EndsWith(".m3u", StringComparison.OrdinalIgnoreCase)
            || path.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase);
    }

    private async Task<string> ReadPlaylistAsync(CancellationToken ct)
    {
        try
        {
            using HttpResponseMessage response = await _http
                .GetAsync(_source, HttpCompletionOption.ResponseHeadersRead, ct)
                .ConfigureAwait(false);

            if (!response.IsSuccessStatusCode)
            {
                throw new ProviderHttpException(_serverId, (int)response.StatusCode, "GET", UrlRedactor.Redact(_source));
            }

            await using Stream stream = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            var buffer = new byte[MaxPlaylistBytes];
            int total = 0;
            while (total < buffer.Length)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(total, buffer.Length - total), ct).ConfigureAwait(false);
                if (read == 0) break;
                total += read;
            }
            return Encoding.UTF8.GetString(buffer, 0, total);
        }
        catch (ProviderException)
        {
            throw;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException)
        {
            throw ProviderException.Connection(_serverId, "无法读取播放列表，请检查地址是否可访问", ex);
        }
    }

    private MediaItem ItemFor(Uri url, string? title, long? durationSeconds)
    {
        string name = !string.IsNullOrWhiteSpace(title) ? title! : NameOf(url);
        return new MediaItem
        {
            ServerId = _serverId,
            Id = url.AbsoluteUri,
            Name = name,
            Type = MediaType.Video,
            Path = url.AbsoluteUri,
            RunTimeTicks = durationSeconds is { } seconds ? seconds * TimeSpan.TicksPerSecond : null,
        };
    }

    private static PagedResult<MediaItem> Page(IReadOnlyList<MediaItem> items, PageRequest page)
    {
        if (items.Count <= page.Offset) return new PagedResult<MediaItem>(Array.Empty<MediaItem>(), page.Offset, items.Count);
        var slice = items.Skip(page.Offset).Take(page.Limit).ToList();
        return new PagedResult<MediaItem>(slice, page.Offset, items.Count);
    }

    private static string NameOf(Uri url)
    {
        string last = url.Segments.Length > 0 ? url.Segments[^1] : "";
        string decoded = Uri.UnescapeDataString(last).Trim('/');
        if (decoded.Length == 0) return url.Host;
        return decoded;
    }
}

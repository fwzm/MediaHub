using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;

namespace MediaHub.App.Services;

/// <summary>A search hit together with the source that produced it.</summary>
public sealed record SearchHit(MediaItem Item, string ServerId, string ServerName);

/// <summary>Per-source outcome, so a partial result is reported as partial.</summary>
public sealed record SearchSourceOutcome(string ServerId, string ServerName, int Count, string? Error)
{
    public bool Failed => Error is not null;
}

public sealed record SearchResult(IReadOnlyList<SearchHit> Hits, IReadOnlyList<SearchSourceOutcome> Outcomes)
{
    public int FailedSources => Outcomes.Count(o => o.Failed);

    /// <summary>True when every source that was asked returned an error.</summary>
    public bool AllFailed => Outcomes.Count > 0 && Outcomes.All(o => o.Failed);
}

/// <summary>
/// Fans a query out across the connected sources.
/// </summary>
/// <remarks>
/// <para>
/// Each source is queried through its own search capability; nothing is crawled and cached locally.
/// Pulling entire libraries into memory to search them client-side would be unbounded memory growth
/// proportional to the user's library size, and it would silently ignore the server-side sort and
/// filter semantics that the source actually implements.
/// </para>
/// <para>
/// Sources are independent. One server being offline must not remove another server's results, so
/// failures are collected per source and surfaced alongside the hits rather than thrown. A source
/// without a search capability is reported as not supporting search instead of being given a
/// client-side filter that pretends to work.
/// </para>
/// <para>
/// The caller's cancellation token is honoured, which is what discards a superseded query when the
/// user keeps typing.
/// </para>
/// </remarks>
public sealed class SearchService
{
    private readonly ServerManager _servers;
    private readonly LogService _log;
    private static readonly TimeSpan PerSourceTimeout = TimeSpan.FromSeconds(12);

    public SearchService(ServerManager servers, LogService log)
    {
        _servers = servers;
        _log = log;
    }

    public async Task<SearchResult> SearchAsync(string query, int limitPerSource = 40, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(query)) return new SearchResult(Array.Empty<SearchHit>(), Array.Empty<SearchSourceOutcome>());

        var handles = _servers.OnlineHandles.Where(h => h.Search is not null).ToList();
        var skipped = _servers.OnlineHandles.Where(h => h.Search is null && h.Descriptor.Type != ServerType.Local).ToList();

        var outcomes = new List<SearchSourceOutcome>();
        foreach (ProviderHandle handle in skipped)
        {
            outcomes.Add(new SearchSourceOutcome(handle.ServerId, handle.Server.DisplayName, 0, "该数据源不支持搜索"));
        }

        var tasks = handles.Select(handle => SearchOneAsync(handle, query, limitPerSource, ct)).ToList();
        var hits = new List<SearchHit>();
        foreach (var task in tasks)
        {
            (ProviderHandle source, IReadOnlyList<MediaItem> items, string? error) = await task.ConfigureAwait(false);
            if (error is null)
            {
                hits.AddRange(items.Select(i => new SearchHit(i, source.ServerId, source.Server.DisplayName)));
            }
            else
            {
                outcomes.Add(new SearchSourceOutcome(source.ServerId, source.Server.DisplayName, 0, error));
            }
        }

        // Deterministic ordering independent of which server answered first, so the list does not
        // reshuffle between identical searches.
        var ordered = hits
            .OrderByDescending(h => h.Item.UserDataPlaybackPositionTicks is > 0)
            .ThenBy(h => h.Item.Type)
            .ThenBy(h => h.Item.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        return new SearchResult(ordered, outcomes);
    }

    private async Task<(ProviderHandle Source, IReadOnlyList<MediaItem> Items, string? Error)> SearchOneAsync(
        ProviderHandle handle, string query, int limit, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(PerSourceTimeout);
        try
        {
            PagedResult<MediaItem> page = await handle.Search!
                .SearchAsync(query, new PageRequest(0, limit), timeout.Token)
                .ConfigureAwait(false);
            return (handle, page.Items, null);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // caller cancelled: propagate so the whole search stops
        }
        catch (OperationCanceledException)
        {
            return (handle, Array.Empty<MediaItem>(), "查询超时");
        }
        catch (ProviderException ex)
        {
            _log.Debug("search", handle.Server.DisplayName + " 搜索失败：" + ex.Code);
            return (handle, Array.Empty<MediaItem>(), ex.Message);
        }
        catch (Exception ex)
        {
            _log.Warn("search", handle.Server.DisplayName + " 搜索异常：" + ex.GetType().Name);
            return (handle, Array.Empty<MediaItem>(), "未知错误");
        }
    }
}

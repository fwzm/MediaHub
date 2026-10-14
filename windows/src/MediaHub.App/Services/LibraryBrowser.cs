using MediaHub.Domain;
using MediaHub.Providers;

namespace MediaHub.App.Services;

/// <summary>One page of items plus the state needed to request the next one.</summary>
public sealed record LibraryPageResult(
    IReadOnlyList<MediaItem> Items,
    int Offset,
    int? TotalCount,
    bool HasMore,
    string? ServerId);

/// <summary>
/// Drives paged browsing of one library, with cancellation and stale-response suppression.
/// </summary>
/// <remarks>
/// <para>
/// A media grid issues a request, the user navigates away or opens another library, and the first
/// request completes afterwards. Without a guard its results are appended to a list the user is no
/// longer looking at — the classic symptom being posters from the previous library appearing at the
/// bottom of the new one. Every request therefore captures the generation and the scope it was
/// issued for, and a response is dropped unless both still match.
/// </para>
/// <para>
/// The scope check is separate from the generation check on purpose: re-entering the same library
/// bumps the generation, but a page that was requested for a <i>different</i> library must never be
/// merged even if the generation accidentally matches.
/// </para>
/// </remarks>
public sealed class LibraryBrowser
{
    private readonly List<MediaItem> _items = new();
    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private int _generation;
    private int _offset;
    private int? _totalCount;

    public string? ServerId { get; private set; }
    public string? LibraryId { get; private set; }
    public bool IsLoading { get; private set; }
    public bool HasMore { get; private set; } = true;
    public string? LastError { get; private set; }

    public IReadOnlyList<MediaItem> Items { get { lock (_gate) return _items.ToList(); } }
    public int? TotalCount { get { lock (_gate) return _totalCount; } }

    public event EventHandler? Changed;

    /// <summary>Switches to a different library and discards everything from the previous one.</summary>
    public void Open(string serverId, string libraryId, int pageSize = 60)
    {
        lock (_gate)
        {
            _cts?.Cancel();
            _cts?.Dispose();
            _cts = new CancellationTokenSource();
            _generation++;
            _items.Clear();
            _offset = 0;
            _totalCount = null;
            HasMore = true;
            LastError = null;
            ServerId = serverId;
            LibraryId = libraryId;
            PageSize = pageSize;
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public int PageSize { get; private set; } = 60;

    /// <summary>Cancels any in-flight page and invalidates pending responses.</summary>
    public void Cancel()
    {
        lock (_gate)
        {
            _cts?.Cancel();
            _generation++;
            IsLoading = false;
        }
    }

    public async Task<LibraryPageResult?> LoadNextPageAsync(ProviderHandle handle, CancellationToken ct = default)
    {
        int generation;
        int offset;
        int pageSize;
        string? libraryId;
        string? serverId;
        CancellationTokenSource linked;

        lock (_gate)
        {
            if (!HasMore || IsLoading) return null;
            IsLoading = true;
            generation = _generation;
            offset = _offset;
            pageSize = PageSize;
            libraryId = LibraryId;
            serverId = ServerId;
            _cts ??= new CancellationTokenSource();
            linked = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token, ct);
        }

        Changed?.Invoke(this, EventArgs.Empty);

        try
        {
            if (handle.Library is null) throw ProviderException.NotYetImplemented(handle.ServerId, "媒体库浏览");
            if (libraryId is null) return null;

            PagedResult<MediaItem> page = await handle.Library
                .GetItemsAsync(libraryId, new PageRequest(offset, pageSize), linked.Token)
                .ConfigureAwait(false);

            lock (_gate)
            {
                // Drop the response unless this is still the same navigation *and* the same library.
                if (generation != _generation || libraryId != LibraryId || serverId != ServerId)
                {
                    return null;
                }

                _items.AddRange(page.Items);
                _offset = offset + page.Items.Count;
                _totalCount = page.TotalCount;
                HasMore = page.Items.Count > 0 && (page.TotalCount is null || _offset < page.TotalCount.Value);
                LastError = null;
            }

            return new LibraryPageResult(page.Items.ToList(), offset, page.TotalCount, HasMore, serverId);
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch (ProviderException ex)
        {
            lock (_gate)
            {
                if (generation == _generation) LastError = ex.Message;
            }
            return null;
        }
        finally
        {
            linked.Dispose();
            lock (_gate) IsLoading = false;
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }
}

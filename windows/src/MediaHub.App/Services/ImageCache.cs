using System.Collections.Concurrent;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Storage;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;

namespace MediaHub.App.Services;

/// <summary>
/// Artwork loader with a bounded cache, in-flight de-duplication and per-source authentication.
/// </summary>
/// <remarks>
/// <para>
/// Artwork is fetched as bytes through the owning source's <see cref="IProviderImageFetcher"/>, never
/// by handing a URL to an image control. A URL-based loader would need the token embedded in the URL
/// (forbidden) or would bypass the source's credential and proxy rules entirely, and it would put the
/// request outside the origin-scoped redirect handling that protects the rest of the traffic.
/// </para>
/// <para>
/// Both caches are bounded. Media libraries routinely contain tens of thousands of posters, so an
/// unbounded cache is a memory leak with a delay, and an unbounded disk cache is a disk leak.
/// </para>
/// </remarks>
public sealed class ImageCache : IDisposable
{
    private const int MaxMemoryEntries = 220;

    private readonly ConcurrentDictionary<string, Task<BitmapImage?>> _inFlight = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, LinkedListNode<CacheEntry>> _memoryIndex = new(StringComparer.Ordinal);
    private readonly LinkedList<CacheEntry> _memoryOrder = new();
    private readonly object _memoryGate = new();
    private readonly ArtworkDiskCache _disk;
    private readonly LogService _log;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcher;
    private int _memoryBytes;

    public ImageCache(LogService log, Microsoft.UI.Dispatching.DispatcherQueue dispatcher)
    {
        _log = log;
        _dispatcher = dispatcher;
        _disk = new ArtworkDiskCache(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "artwork"));
        _ = Task.Run(_disk.Trim);
    }

    private sealed record CacheEntry(string Key, BitmapImage Image, int ApproxBytes);

    /// <summary>Cache key. Includes every input that changes the returned bytes.</summary>
    private static string KeyFor(string serverId, string itemId, string imageType, string? tag, int maxWidth)
        => serverId + "|" + itemId + "|" + imageType + "|" + (tag ?? "-") + "|" + maxWidth;

    /// <summary>
    /// Returns artwork, or null when it is unavailable. Never throws: a missing poster must degrade to
    /// a placeholder, not take down the page it appears on.
    /// </summary>
    public async Task<BitmapImage?> GetAsync(
        ProviderHandle handle, string itemId, string imageType, string? tag, int maxWidth, CancellationToken ct)
    {
        if (handle.Images is null) return null;

        string key = KeyFor(handle.ServerId, itemId, imageType, tag, maxWidth);

        if (_memoryIndex.TryGetValue(key, out var node))
        {
            lock (_memoryGate)
            {
                _memoryOrder.Remove(node);
                _memoryOrder.AddFirst(node);
            }
            return node.Value.Image;
        }

        // Two cells showing the same poster must produce one request, not two.
        Task<BitmapImage?> task = _inFlight.GetOrAdd(key, _ => LoadAsync(handle, itemId, imageType, tag, maxWidth, key, ct));
        try
        {
            return await task.ConfigureAwait(false);
        }
        finally
        {
            _inFlight.TryRemove(key, out _);
        }
    }

    private async Task<BitmapImage?> LoadAsync(
        ProviderHandle handle, string itemId, string imageType, string? tag, int maxWidth, string key, CancellationToken ct)
    {
        try
        {
            byte[]? bytes = await Task.Run(() => _disk.TryRead(key, ct), ct).ConfigureAwait(false);

            if (bytes is null)
            {
                bytes = await handle.Images!.FetchImageAsync(itemId, imageType, tag, maxWidth, ct).ConfigureAwait(false);
                if (bytes.Length == 0) return null;
                _ = Task.Run(() => _disk.Write(key, bytes), CancellationToken.None);
            }

            BitmapImage? image = await ToBitmapAsync(bytes, ct).ConfigureAwait(false);
            if (image is not null)
            {
                // Positive trace into the same file the acceptance harness reads. A successful poster
                // load is otherwise invisible, and "the card is there but the artwork is blank" is
                // exactly the failure this line rules out.
                StartupTrace.Write("image:loaded item=" + itemId + " type=" + imageType + " w=" + maxWidth);
            }
            return image;
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch (Exception ex)
        {
            // A broken poster is cosmetic. Log at debug level and let the UI show its placeholder.
            _log.Debug("image", "封面加载失败：" + ex.GetType().Name);
            return null;
        }
    }

    /// <summary>
    /// Decodes bytes into a bitmap.
    /// </summary>
    /// <remarks>
    /// BitmapImage is a XAML object, so it can only be created and populated on the UI thread. The
    /// download completes on a worker thread, so the decode is marshalled here rather than being left
    /// to whichever thread happened to resume — doing it off-thread throws an RPC wrong-thread error
    /// that surfaces as "some posters never load".
    /// </remarks>
    private async Task<BitmapImage?> ToBitmapAsync(byte[] bytes, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();

        var completion = new TaskCompletionSource<BitmapImage?>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_dispatcher.TryEnqueue(() => _ = DecodeOnUiThreadAsync(bytes, completion)))
        {
            return null;
        }
        return await completion.Task.ConfigureAwait(false);
    }

    private static async Task DecodeOnUiThreadAsync(byte[] bytes, TaskCompletionSource<BitmapImage?> completion)
    {
        try
        {
            var stream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
            {
                writer.WriteBytes(bytes);
                await writer.StoreAsync();
                await writer.FlushAsync();
                writer.DetachStream();
            }
            stream.Seek(0);

            var image = new BitmapImage();
            await image.SetSourceAsync(stream);
            completion.TrySetResult(image);
        }
        catch (Exception ex)
        {
            completion.TrySetResult(null);
            _ = ex;
        }
    }

    // ---------------------------------------------------------------- memory cache

    private void Remember(string key, BitmapImage image, int approxBytes)
    {
        lock (_memoryGate)
        {
            if (_memoryIndex.TryGetValue(key, out var existing))
            {
                _memoryOrder.Remove(existing);
                _memoryBytes -= existing.Value.ApproxBytes;
                _memoryIndex.TryRemove(key, out _);
            }

            var node = _memoryOrder.AddFirst(new CacheEntry(key, image, approxBytes));
            _memoryIndex[key] = node;
            _memoryBytes += approxBytes;

            while (_memoryOrder.Count > MaxMemoryEntries && _memoryOrder.Last is { } last)
            {
                _memoryOrder.RemoveLast();
                _memoryIndex.TryRemove(last.Value.Key, out _);
                _memoryBytes -= last.Value.ApproxBytes;
            }
        }
    }

    // ---------------------------------------------------------------- disk cache

    public void Clear()
    {
        lock (_memoryGate)
        {
            _memoryOrder.Clear();
            _memoryIndex.Clear();
            _memoryBytes = 0;
        }
        _disk.Clear();
    }

    public void Dispose()
    {
        Clear();
        _inFlight.Clear();
    }
}

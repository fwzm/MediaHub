using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Storage;

/// <summary>
/// Bounded on-disk cache for artwork bytes.
/// </summary>
/// <remarks>
/// <para>
/// Entries older than the TTL are discarded on read and during a trim; a trim also evicts the
/// oldest entries until the byte cap is met. The cache is best effort: a failed read or write
/// degrades to a cache miss and never surfaces to the caller.
/// </para>
/// <para>
/// The cache is deliberately independent of XAML so the size cap and TTL rules can be exercised
/// without a UI thread. <see cref="PathFor"/> is public for diagnostics and tests.
/// </para>
/// </remarks>
public sealed class ArtworkDiskCache
{
    /// <summary>Default disk cap (256 MiB).</summary>
    public const long DefaultMaxBytes = 256L * 1024 * 1024;

    /// <summary>Default entry lifetime (30 days).</summary>
    public static readonly TimeSpan DefaultEntryTtl = TimeSpan.FromDays(30);

    private readonly string _root;
    private readonly long _maxBytes;
    private readonly TimeSpan _entryTtl;
    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();
    private long _bytes;

    public ArtworkDiskCache(
        string rootDirectory,
        long maxBytes = DefaultMaxBytes,
        TimeSpan? entryTtl = null,
        Func<DateTimeOffset>? clock = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxBytes);

        TimeSpan ttl = entryTtl ?? DefaultEntryTtl;
        if (ttl <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(entryTtl));
        }

        _root = rootDirectory;
        _maxBytes = maxBytes;
        _entryTtl = ttl;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        Directory.CreateDirectory(_root);
    }

    /// <summary>Tracked size of the live entries in bytes.</summary>
    public long SizeBytes
    {
        get { lock (_gate) { return _bytes; } }
    }

    /// <summary>Resolves the cache file path for a key.</summary>
    public string PathFor(string key)
    {
        ArgumentNullException.ThrowIfNull(key);
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(key));
        return Path.Combine(_root, Convert.ToHexString(hash).ToLowerInvariant() + ".img");
    }

    /// <summary>True when a live entry exists for the key. Expired entries count as absent.</summary>
    public bool Contains(string key)
    {
        lock (_gate)
        {
            string path = PathFor(key);
            try
            {
                if (!File.Exists(path))
                {
                    return false;
                }

                var info = new FileInfo(path);
                if (_clock().UtcDateTime - info.LastWriteTimeUtc > _entryTtl)
                {
                    long expiredBytes = info.Length;
                    info.Delete();
                    _bytes = Math.Max(0, _bytes - expiredBytes);
                    return false;
                }

                return true;
            }
            catch
            {
                return false;
            }
        }
    }

    /// <summary>Returns the cached bytes, or null when absent, expired or unreadable.</summary>
    public byte[]? TryRead(string key, CancellationToken ct = default)
    {
        lock (_gate)
        {
            string path = PathFor(key);
            try
            {
                if (!File.Exists(path))
                {
                    return null;
                }

                var info = new FileInfo(path);
                if (_clock().UtcDateTime - info.LastWriteTimeUtc > _entryTtl)
                {
                    long expiredBytes = info.Length;
                    info.Delete();
                    _bytes = Math.Max(0, _bytes - expiredBytes);
                    return null;
                }

                ct.ThrowIfCancellationRequested();
                return File.ReadAllBytes(path);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                return null;
            }
        }
    }

    /// <summary>Stores bytes for a key, replacing any previous entry.</summary>
    public void Write(string key, byte[] bytes)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (bytes.Length == 0 || bytes.Length > _maxBytes)
        {
            return;
        }

        lock (_gate)
        {
            string path = PathFor(key);
            string temp = path + ".tmp";
            try
            {
                long previous = File.Exists(path) ? new FileInfo(path).Length : 0;
                File.WriteAllBytes(temp, bytes);
                File.Move(temp, path, overwrite: true);
                _bytes = Math.Max(0, _bytes + bytes.Length - previous);

                if (_bytes > _maxBytes)
                {
                    Trim();
                }
            }
            catch
            {
                TryDelete(temp);
            }
        }
    }

    /// <summary>Drops expired entries, then evicts oldest-first until the cap is met.</summary>
    public long Trim()
    {
        lock (_gate)
        {
            try
            {
                var directory = new DirectoryInfo(_root);
                foreach (var temp in directory.GetFiles("*.tmp"))
                {
                    TryDelete(temp);
                }

                var files = directory.GetFiles("*.img");
                long total = 0;
                var live = new List<FileInfo>(files.Length);
                foreach (var file in files)
                {
                    if (_clock().UtcDateTime - file.LastWriteTimeUtc > _entryTtl)
                    {
                        TryDelete(file);
                        continue;
                    }

                    total += file.Length;
                    live.Add(file);
                }

                _bytes = total;
                if (total <= _maxBytes)
                {
                    return total;
                }

                foreach (var file in live.OrderBy(f => f.LastWriteTimeUtc))
                {
                    if (total <= _maxBytes)
                    {
                        break;
                    }

                    total -= file.Length;
                    TryDelete(file);
                }

                _bytes = total;
                return total;
            }
            catch
            {
                return _bytes;
            }
        }
    }

    /// <summary>Removes every cached entry.</summary>
    public void Clear()
    {
        lock (_gate)
        {
            _bytes = 0;
            try
            {
                var directory = new DirectoryInfo(_root);
                foreach (var file in directory.GetFiles("*.img"))
                {
                    TryDelete(file);
                }

                foreach (var file in directory.GetFiles("*.tmp"))
                {
                    TryDelete(file);
                }
            }
            catch
            {
                // Clearing a cache is best effort.
            }
        }
    }

    private static void TryDelete(FileInfo file)
    {
        try { file.Delete(); } catch { }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
    }
}

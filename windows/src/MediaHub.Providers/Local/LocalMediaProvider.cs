using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.Local;

/// <summary>
/// Local filesystem and mapped-drive provider.
/// </summary>
/// <remarks>
/// Covers ordinary folders, mapped SMB drives and UNC paths. The provider deliberately reports only
/// <see cref="ProviderCapability.Browse"/> and <see cref="ProviderCapability.Playback"/>: there is no
/// library metadata to query, so claiming Library or Search would be a false capability.
/// </remarks>
public sealed class LocalMediaProviderFactory : IMediaProviderFactory
{
    public static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg",
        ".ts", ".m2ts", ".mts", ".vob", ".rmvb", ".rm", ".3gp", ".ogv", ".divx", ".asf",
    };

    public static readonly HashSet<string> SubtitleExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".srt", ".ass", ".ssa", ".sub", ".idx", ".vtt", ".smi", ".sup",
    };

    public static IReadOnlyCollection<string> SupportedVideoExtensions => VideoExtensions;
    public static IReadOnlyCollection<string> SupportedSubtitleExtensions => SubtitleExtensions;

    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.Local,
        "本地存储",
        "本地磁盘、映射网络驱动器与 UNC 路径",
        ProviderCapability.Browse | ProviderCapability.Playback | ProviderCapability.SubtitleDiscovery);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        string root = server.PrimaryEndpoint?.Url ?? "";
        var provider = new LocalMediaProvider(server.ServerId, root);
        return new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = Descriptor,
            Browse = provider,
            Playback = provider,
            Subtitle = provider,
        };
    }

    /// <summary>Classifies a path so the UI can show whether it is local, mapped or remote.</summary>
    public static StorageKind Classify(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return StorageKind.Unknown;
        if (path.StartsWith(@"\\", StringComparison.Ordinal)) return StorageKind.Unc;

        try
        {
            if (path.Length >= 2 && path[1] == ':')
            {
                string root = char.ToUpperInvariant(path[0]) + ":\\";
                var info = new DriveInfo(root);
                return info.DriveType switch
                {
                    DriveType.Network => StorageKind.MappedNetworkDrive,
                    DriveType.Fixed => StorageKind.Fixed,
                    DriveType.Removable => StorageKind.Removable,
                    DriveType.CDRom => StorageKind.Optical,
                    DriveType.Ram => StorageKind.RamDisk,
                    _ => StorageKind.Unknown,
                };
            }
        }
        catch
        {
            // An unavailable drive letter must degrade to Unknown rather than throw.
        }
        return StorageKind.Unknown;
    }

    /// <summary>
    /// True when the path is served over a network.
    /// </summary>
    /// <remarks>
    /// DriveType alone is not sufficient: a drive can be reclassified after a reconnect, and a
    /// WebDAV mount can present itself as a fixed drive. The UNC prefix, the drive type and a mapped
    /// remote target are therefore all consulted, and the caller treats the result as a hint rather
    /// than a guarantee.
    /// </remarks>
    public static bool IsLikelyRemote(string path)
    {
        StorageKind kind = Classify(path);
        return kind is StorageKind.Unc or StorageKind.MappedNetworkDrive or StorageKind.WebDavMount;
    }
}

public enum StorageKind
{
    Unknown = 0,
    Fixed,
    Removable,
    Optical,
    RamDisk,
    MappedNetworkDrive,
    Unc,
    WebDavMount,
}

internal sealed class LocalMediaProvider : IProviderBrowse, IProviderPlayback, IProviderSubtitle
{
    private readonly string _serverId;
    private readonly string _root;

    public LocalMediaProvider(string serverId, string root)
    {
        _serverId = serverId;
        _root = root;
    }

    public Task<PagedResult<MediaItem>> ListFolderAsync(MediaItem? folder, PageRequest page, CancellationToken ct = default)
    {
        string path = folder?.Path ?? _root;
        if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
            throw ProviderException.NotFound(_serverId, path);

        var items = new List<MediaItem>();
        try
        {
            foreach (string dir in Directory.EnumerateDirectories(path))
            {
                ct.ThrowIfCancellationRequested();
                var info = new DirectoryInfo(dir);
                if ((info.Attributes & FileAttributes.Hidden) != 0) continue;
                items.Add(new MediaItem
                {
                    ServerId = _serverId,
                    Id = dir,
                    Name = info.Name,
                    Type = MediaType.Folder,
                    Path = dir,
                    IsFolder = true,
                });
            }

            foreach (string file in Directory.EnumerateFiles(path))
            {
                ct.ThrowIfCancellationRequested();
                string ext = System.IO.Path.GetExtension(file);
                if (!LocalMediaProviderFactory.SupportedVideoExtensions.Contains(ext)) continue;
                var info = new FileInfo(file);
                items.Add(new MediaItem
                {
                    ServerId = _serverId,
                    Id = file,
                    Name = System.IO.Path.GetFileNameWithoutExtension(file),
                    Type = MediaType.Video,
                    Path = file,
                    FileSizeBytes = info.Length,
                });
            }
        }
        catch (OperationCanceledException) { throw; }
        catch (UnauthorizedAccessException ex)
        {
            throw ProviderException.Connection(_serverId, "没有访问该目录的权限", ex);
        }
        catch (IOException ex)
        {
            throw ProviderException.Network(_serverId, ex);
        }

        var ordered = items
            .OrderByDescending(i => i.IsFolder)
            .ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var window = ordered.Skip(page.Offset).Take(page.Limit).ToList();
        return Task.FromResult(new PagedResult<MediaItem>(window, page.Offset, ordered.Count));
    }

    public Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        string path = item.Path ?? item.Id;
        if (!File.Exists(path)) throw ProviderException.NotFound(_serverId, path);

        // A local file needs no credentials and no headers; the URL is a file URI so the engine can
        // consume it uniformly with remote sources.
        var source = new PlaybackSource
        {
            Url = new Uri(path),
            Mode = PlaybackMode.DirectPlay,
            StartPositionTicks = options.StartPositionTicks,
        };
        return Task.FromResult(source);
    }

    public Task<IReadOnlyList<SubtitleTrack>> GetSubtitlesAsync(string itemId, CancellationToken ct = default)
    {
        string? dir = System.IO.Path.GetDirectoryName(itemId);
        if (dir is null || !Directory.Exists(dir)) return Task.FromResult<IReadOnlyList<SubtitleTrack>>(Array.Empty<SubtitleTrack>());

        string stem = System.IO.Path.GetFileNameWithoutExtension(itemId);
        var tracks = new List<SubtitleTrack>();
        int index = 0;
        foreach (string file in Directory.EnumerateFiles(dir))
        {
            ct.ThrowIfCancellationRequested();
            if (!LocalMediaProviderFactory.SubtitleExtensions.Contains(System.IO.Path.GetExtension(file))) continue;
            string name = System.IO.Path.GetFileNameWithoutExtension(file);
            if (!(name.Equals(stem, StringComparison.OrdinalIgnoreCase)
                  || name.StartsWith(stem + ".", StringComparison.OrdinalIgnoreCase)
                  || name.StartsWith(stem + "_", StringComparison.OrdinalIgnoreCase)
                  || name.StartsWith(stem + "-", StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            tracks.Add(new SubtitleTrack(
                index++,
                LanguageFromSuffix(name, stem),
                System.IO.Path.GetFileName(file),
                System.IO.Path.GetExtension(file).TrimStart('.').ToLowerInvariant(),
                IsExternal: true,
                IsDefault: index == 1,
                IsForced: name.Contains("forced", StringComparison.OrdinalIgnoreCase),
                DeliveryUrl: file));
        }
        return Task.FromResult<IReadOnlyList<SubtitleTrack>>(tracks);
    }

    /// <summary>Markers that describe a subtitle's role rather than its language.</summary>
    private static readonly HashSet<string> NonLanguageMarkers = new(StringComparer.OrdinalIgnoreCase)
    {
        "forced", "default", "sdh", "cc", "full", "signs", "songs",
    };

    /// <summary>
    /// Derives a language hint from the conventional suffix, e.g. <c>movie.chi.srt</c> or
    /// <c>movie_zh-Hans.srt</c>.
    /// </summary>
    /// <param name="fileNameWithoutExtension">File name with the subtitle extension already removed.</param>
    /// <remarks>
    /// Splitting stops at <c>.</c> and <c>_</c> but not at <c>-</c>, because a hyphen is part of a
    /// BCP-47 tag: splitting on it would turn <c>zh-Hans</c> into <c>zh</c> and lose the script, which
    /// is exactly the distinction a Chinese-language viewer needs. Role markers such as
    /// <c>forced</c> are rejected rather than reported as a language.
    /// </remarks>
    internal static string? LanguageFromSuffix(string fileNameWithoutExtension, string stem)
    {
        string rest = fileNameWithoutExtension.Length > stem.Length
            ? fileNameWithoutExtension[stem.Length..].TrimStart('.', '_', '-')
            : "";
        if (string.IsNullOrWhiteSpace(rest)) return null;

        string token = rest.Split('.', '_')[0];
        if (token.Length is < 2 or > 20) return null;
        if (NonLanguageMarkers.Contains(token)) return null;
        return token;
    }
}

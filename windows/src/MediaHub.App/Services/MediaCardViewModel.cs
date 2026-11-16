using System.ComponentModel;
using System.Runtime.CompilerServices;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml.Media.Imaging;

namespace MediaHub.App.Services;

/// <summary>
/// One tile in a media grid.
/// </summary>
/// <remarks>
/// Posters load lazily and never block the list: a grid of several hundred items must be usable while
/// artwork is still arriving, and an item whose artwork fails keeps its title and a placeholder rather
/// than collapsing or showing a broken image.
/// </remarks>
public sealed class MediaCardViewModel : INotifyPropertyChanged
{
    private BitmapImage? _poster;
    private bool _posterRequested;

    public required MediaItem Item { get; init; }
    public required ProviderHandle Source { get; init; }

    public string Title => Item.Name;

    /// <summary>
    /// Stable automation identifier for this card.
    /// </summary>
    /// <remarks>
    /// Composed from the source id and the media id, both of which are stable server-side, rather
    /// than from the display name or the position in the grid. Two servers can legitimately hold
    /// items with the same name, and a UI test that selects "the third tile" fails whenever a library
    /// is rescanned - neither is a fact about the client's behaviour.
    /// </remarks>
    public string AutomationId => "media:" + Source.ServerId + ":" + Item.Id;

    public string Subtitle
    {
        get
        {
            var parts = new List<string>();
            if (Item.Type == MediaType.Episode && Item.SeasonNumber is { } s && Item.EpisodeNumber is { } e)
                parts.Add("S" + s.ToString("00") + "E" + e.ToString("00"));
            else if (Item.Year is { } year && year > 0) parts.Add(year.ToString());
            if (Item.RunTime is { } runtime && runtime.TotalMinutes >= 1) parts.Add(((int)runtime.TotalMinutes) + " 分钟");
            return string.Join("  ·  ", parts);
        }
    }

    /// <summary>Which source this tile came from. Shown only when more than one source is in play.</summary>
    public string SourceLabel => Source.Server.DisplayName;

    /// <summary>
    /// Optional technical badges (4K, HDR, DDP 5.1 …). Empty when the user turned them off or when
    /// the provider did not report the facts, so the tile never shows a guessed value.
    /// </summary>
    public IReadOnlyList<string> Badges
    {
        get
        {
            var settings = App.Services.Settings.Current;
            return settings.ShowMediaBadges
                ? MediaBadgeFormatter.Build(Item, settings.MediaBadgeItems)
                : Array.Empty<string>();
        }
    }

    /// <summary>Posters are portrait for movies and series, landscape for episodes and videos.</summary>
    public bool UseLandscapePoster => Item.Type is MediaType.Episode or MediaType.Video;

    /// <summary>A folder is a container rather than something that can be played or opened as a title.</summary>
    public bool IsFolder => Item.IsFolder || Item.Type == MediaType.Folder;

    /// <summary>Right-click label: opening a folder and opening a title are different promises.</summary>
    public string OpenLabel => IsFolder ? "打开文件夹" : "打开详情";

    public double TileWidth => UseLandscapePoster ? 300 : 168;
    public double TileHeight => UseLandscapePoster ? 169 : 252;

    public BitmapImage? Poster
    {
        get => _poster;
        private set { _poster = value; Raise(); Raise(nameof(HasPoster)); }
    }

    public bool HasPoster => _poster is not null;

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    /// <summary>Requests artwork once. Safe to call from every container-realisation callback.</summary>
    public void RequestPoster(ImageCache cache, CancellationToken ct)
    {
        if (_posterRequested || Item.PrimaryImageTag is null) return;
        _posterRequested = true;

        string type = UseLandscapePoster ? "Primary" : "Primary";
        int width = UseLandscapePoster ? 400 : 240;

        _ = LoadAsync(cache, type, width, ct);
    }

    private async Task LoadAsync(ImageCache cache, string imageType, int width, CancellationToken ct)
    {
        BitmapImage? image = await cache
            .GetAsync(Source, Item.Id, imageType, Item.PrimaryImageTag, width, ct)
            .ConfigureAwait(true);
        if (image is not null) Poster = image;
    }

    /// <summary>Detail-page backdrop. Separate from the tile so the grid stays cheap.</summary>
    public async Task<BitmapImage?> LoadBackdropAsync(ImageCache cache, CancellationToken ct)
        => await cache.GetAsync(Source, Item.Id, "Backdrop", Item.BackdropImageTag, 1280, ct).ConfigureAwait(true);

    /// <summary>Large portrait poster for the detail page.</summary>
    public async Task<BitmapImage?> LoadPosterLargeAsync(ImageCache cache, CancellationToken ct)
        => await cache.GetAsync(Source, Item.Id, "Primary", Item.PrimaryImageTag, 480, ct).ConfigureAwait(true);
}

/// <summary>
/// Turns the technical facts a provider reported into the short labels shown on a poster.
/// </summary>
public static class MediaBadgeFormatter
{
    private static readonly Dictionary<string, string> VideoCodecs = new(StringComparer.OrdinalIgnoreCase)
    {
        ["hevc"] = "HEVC", ["h265"] = "HEVC", ["h264"] = "H.264", ["avc"] = "H.264",
        ["av1"] = "AV1", ["vp9"] = "VP9", ["vc1"] = "VC-1", ["mpeg2video"] = "MPEG-2",
    };

    private static readonly Dictionary<string, string> AudioCodecs = new(StringComparer.OrdinalIgnoreCase)
    {
        ["eac3"] = "DDP", ["ac3"] = "DD", ["truehd"] = "TrueHD", ["dts"] = "DTS",
        ["aac"] = "AAC", ["flac"] = "FLAC", ["opus"] = "Opus", ["mp3"] = "MP3",
        ["vorbis"] = "Vorbis",
    };

    /// <summary>At most this many labels fit on a poster without clipping the artwork.</summary>
    private const int MaxBadges = 3;

    public static IReadOnlyList<string> Build(MediaItem item, IReadOnlyList<string> selectedKinds)
    {
        var badges = new List<string>(MaxBadges);
        foreach (string kind in selectedKinds)
        {
            if (badges.Count >= MaxBadges) break;
            string? label = kind switch
            {
                "resolution" => Resolution(item),
                "hdr" => Hdr(item),
                "video" => Video(item),
                "audio" => Audio(item),
                "container" => Container(item),
                _ => null,
            };
            if (label is not null) badges.Add(label);
        }
        return badges;
    }

    private static string? Resolution(MediaItem item)
    {
        int height = item.Height ?? 0;
        int width = item.Width ?? 0;
        if (height <= 0 && width <= 0) return null;
        int longest = Math.Max(height, width);
        if (longest >= 2000) return "4K";
        if (longest >= 1400) return "1440P";
        if (longest >= 1000) return "1080P";
        if (longest >= 700) return "720P";
        if (longest >= 500) return "576P";
        return "480P";
    }

    private static string? Hdr(MediaItem item)
    {
        string? range = item.VideoRange;
        if (string.IsNullOrWhiteSpace(range)) return null;
        if (range.Contains("Dolby", StringComparison.OrdinalIgnoreCase)) return "DV";
        if (range.Contains("HDR", StringComparison.OrdinalIgnoreCase)) return "HDR";
        return null;
    }

    private static string? Video(MediaItem item)
    {
        if (string.IsNullOrWhiteSpace(item.VideoCodec)) return null;
        return VideoCodecs.TryGetValue(item.VideoCodec, out string? label)
            ? label
            : item.VideoCodec.ToUpperInvariant();
    }

    private static string? Audio(MediaItem item)
    {
        if (string.IsNullOrWhiteSpace(item.AudioCodec)) return null;
        string codec = AudioCodecs.TryGetValue(item.AudioCodec, out string? label)
            ? label
            : item.AudioCodec.ToUpperInvariant();
        string? channels = item.AudioChannels switch
        {
            8 => "7.1",
            6 => "5.1",
            2 => "2.0",
            1 => "1.0",
            _ => null,
        };
        return channels is null ? codec : codec + " " + channels;
    }

    private static string? Container(MediaItem item)
        => string.IsNullOrWhiteSpace(item.Container) ? null : item.Container.ToUpperInvariant();
}

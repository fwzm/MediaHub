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

    /// <summary>Posters are portrait for movies and series, landscape for episodes and videos.</summary>
    public bool UseLandscapePoster => Item.Type is MediaType.Episode or MediaType.Video;

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

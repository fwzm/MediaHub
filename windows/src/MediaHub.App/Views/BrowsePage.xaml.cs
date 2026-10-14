using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace MediaHub.App.Views;

/// <summary>One row in the folder listing.</summary>
public sealed record BrowseRow(
    string Name,
    string Detail,
    string Glyph,
    string TrailingGlyph,
    string AutomationId,
    MediaItem Item);

/// <summary>
/// Folder browsing for sources that expose <see cref="IProviderBrowse"/>.
/// </summary>
/// <remarks>
/// <para>
/// A separate page rather than a reuse of LibraryPage, deliberately. A library page is built on
/// <see cref="IProviderLibrary"/>, whose model is "named collections of catalogued media". A file
/// browser's model is "a tree of folders and files with no catalogue at all". Running a WebDAV
/// endpoint through the library abstraction would mean inventing a synthetic library that the source
/// never reported, and every library-specific behaviour (library types, paging by catalogue,
/// poster lookup) would then be quietly wrong rather than absent.
/// </para>
/// <para>
/// Until this page existed, the WebDAV provider declared and implemented browsing and no part of the
/// shell ever called it: the source showed as connected and its contents were unreachable.
/// </para>
/// </remarks>
public sealed partial class BrowsePage : Page
{
    private BrowseNavigation? _navigation;
    private ProviderHandle? _handle;
    private MediaItem? _currentFolder;
    private string _currentTitle = "";
    private bool _busy;

    // Bumped on every navigation. A response whose generation is stale is dropped, so clicking into
    // three folders quickly cannot end up rendering the first one's contents last.
    private int _generation;

    public BrowsePage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _navigation = e.Parameter as BrowseNavigation;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_navigation is null)
        {
            Fail("未指定要浏览的来源。");
            return;
        }

        _handle = App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Browse is null)
        {
            Fail("该来源不支持文件夹浏览。");
            return;
        }

        _currentTitle = _navigation.Title;
        // A null folder id means "the source root": the provider decides what that is, and it is the
        // only place that knows the configured root path.
        _currentFolder = string.IsNullOrEmpty(_navigation.FolderId)
            ? null
            : new MediaItem { ServerId = _handle.ServerId, Id = _navigation.FolderId!, Name = _navigation.Title };
        if (_currentFolder is not null && !string.IsNullOrEmpty(_navigation.FolderPath))
        {
            _currentFolder = _currentFolder with { Path = _navigation.FolderPath };
        }

        await LoadAsync();
    }

    private async Task LoadAsync()
    {
        if (_busy) return;
        _busy = true;
        int generation = ++_generation;

        LoadingRing.IsActive = true;
        EmptyPanel.Visibility = Visibility.Visible;
        EmptyText.Text = "正在读取目录…";
        StatusBar.IsOpen = false;

        try
        {
            PagedResult<MediaItem> page = await _handle!.Browse!
                .ListFolderAsync(_currentFolder, new PageRequest(0, 500))
                .ConfigureAwait(true);

            // Drop the response unless this is still the navigation that asked for it.
            if (generation != _generation) return;

            List<BrowseRow> rows = page.Items.Select(ToRow).ToList();
            ItemList.ItemsSource = rows;

            TitleText.Text = _currentTitle;
            SourceText.Text = "来源：" + _handle.Server.DisplayName + "  ·  " + _handle.Server.Type;
            BreadcrumbText.Text = string.IsNullOrEmpty(_currentFolder?.Path) && _currentFolder is null
                ? "根目录"
                : (_currentFolder?.Path ?? "根目录");
            UpButton.IsEnabled = _currentFolder is not null;

            bool empty = rows.Count == 0;
            EmptyPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            if (empty) EmptyText.Text = "这个目录是空的。";
        }
        catch (ProviderException ex)
        {
            if (generation == _generation) Fail(ex.Message);
        }
        catch (Exception ex)
        {
            if (generation == _generation) Fail(ex.Message);
        }
        finally
        {
            if (generation == _generation)
            {
                LoadingRing.IsActive = false;
                _busy = false;
            }
        }
    }

    private static BrowseRow ToRow(MediaItem item)
    {
        bool folder = item.IsFolder || item.Type == MediaType.Folder;
        string detail = folder
            ? "文件夹"
            : Describe(item);
        return new BrowseRow(
            item.Name,
            detail,
            folder ? "\uE8B7" : "\uE8A5",
            folder ? "\uE76C" : "\uE768",
            // Composed from the driver id and the item id, both stable, so an automation script can
            // reach an entry by identity rather than by its position in the listing.
            "browse:" + item.ServerId + ":" + item.Id,
            item);
    }

    /// <summary>
    /// A short, honest description of a file.
    /// </summary>
    /// <remarks>
    /// A WebDAV server reports a size and sometimes a content type, and nothing else - there is no
    /// metadata service behind it. When a field is absent it is left out rather than filled with a
    /// guess, because a wrong size or type is worse than an absent one.
    /// </remarks>
    private static string Describe(MediaItem item)
    {
        var parts = new List<string>();
        if (item.FileSizeBytes is { } size && size > 0)
        {
            parts.Add(size >= 1L << 30
                ? (size / (double)(1L << 30)).ToString("0.0") + " GB"
                : (size / (double)(1L << 20)).ToString("0.0") + " MB");
        }
        if (item.RunTimeTicks is { } ticks && ticks > 0)
        {
            parts.Add(TimeSpan.FromTicks(ticks).ToString(@"h\:mm\:ss"));
        }
        if (!string.IsNullOrEmpty(item.Path))
        {
            string name = item.Path!;
            int dot = name.LastIndexOf('.');
            if (dot > 0 && dot < name.Length - 1) parts.Add(name[(dot + 1)..].ToUpperInvariant());
        }
        return parts.Count == 0 ? "文件" : string.Join("  ·  ", parts);
    }

    private async void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not BrowseRow row) return;

        MediaItem item = row.Item;
        if (item.IsFolder || item.Type == MediaType.Folder)
        {
            _currentFolder = item;
            _currentTitle = item.Name;
            await LoadAsync();
            return;
        }

        MainWindow? shell = App.MainWindow;
        if (shell is null) return;

        shell.EnterPlayer(new PlaybackRequest
        {
            ServerId = item.ServerId,
            ItemId = item.Id,
            Title = item.Name,
            DisplayUrl = item.Path ?? item.Id,
            SeriesId = null,
        });
    }

    private async void OnUpClick(object sender, RoutedEventArgs e)
    {
        if (_currentFolder is null) return;

        // Walk to the parent by trimming the last path segment. The provider validates that any folder
        // it is given is inside the configured root, so an over-trimmed path is refused there rather
        // than silently escaping the source.
        string? path = _currentFolder.Path ?? _currentFolder.Id;
        if (string.IsNullOrEmpty(path)) return;

        var uri = new Uri(path, UriKind.Absolute);
        string parent = uri.AbsoluteUri.TrimEnd('/');
        int slash = parent.LastIndexOf('/');
        if (slash <= uri.Scheme.Length + 2)
        {
            _currentFolder = null;
            _currentTitle = _navigation?.Title ?? "根目录";
        }
        else
        {
            string parentUrl = parent[..(slash + 1)];
            string name = Uri.UnescapeDataString(parent[(slash + 1)..]);
            _currentFolder = new MediaItem
            {
                ServerId = _currentFolder.ServerId,
                Id = parentUrl,
                Name = name,
                Path = parentUrl,
                IsFolder = true,
                Type = MediaType.Folder,
            };
            _currentTitle = name;
        }

        await LoadAsync();
    }

    private void Fail(string message)
    {
        LoadingRing.IsActive = false;
        _busy = false;
        ItemList.ItemsSource = null;
        EmptyPanel.Visibility = Visibility.Visible;
        EmptyText.Text = message;
        StatusBar.Severity = InfoBarSeverity.Error;
        StatusBar.Title = "无法读取目录";
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}

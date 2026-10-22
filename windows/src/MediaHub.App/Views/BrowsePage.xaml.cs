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
    private string _currentTitle = "";
    private bool _busy;
    private bool _pendingReload;

    // Where the user actually is, as a stack of visited folders. Index 0 is the source root.
    //
    // This is a trail rather than a parent URL derived on demand, for two measured reasons:
    //   * a WebDAV href is an absolute percent-encoded URL and a MediaBrowser id is opaque, so
    //     neither is a readable breadcrumb - "http://127.0.0.1:8181/Season%2001/" was what the
    //     previous version displayed to the user;
    //   * trimming a trailing path segment stopped one level short of the root, because the root
    //     URL came back as an ordinary folder object. "Up" then stayed enabled at the root and a
    //     second press was needed to reach it.
    // The trail is per page instance; re-entering the page from the library list starts at the root,
    // which is what the navigation argument asks for anyway.
    private readonly List<BrowseCrumb> _trail = new();
    private string _rootTitle = "根目录";

    /// <summary>One step of the breadcrumb trail; <see cref="Folder"/> is null for the source root.</summary>
    private sealed record BrowseCrumb(string Title, MediaItem? Folder);

    private MediaItem? CurrentFolder => _trail.Count == 0 ? null : _trail[^1].Folder;

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
            Fail("未指定要浏览的来源。", "根目录", "根目录", false);
            return;
        }

        _handle = App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Browse is null)
        {
            Fail("该来源不支持文件夹浏览。", "根目录", "根目录", false);
            return;
        }

        // A null folder id means "the source root": the provider decides what that is, and it is the
        // only place that knows the configured root path.
        _rootTitle = string.IsNullOrEmpty(_navigation.Title) ? "根目录" : _navigation.Title;
        _trail.Clear();
        _trail.Add(new BrowseCrumb("根目录", null));
        _currentTitle = _rootTitle;

        if (!string.IsNullOrEmpty(_navigation.FolderId))
        {
            MediaItem folder = new() { ServerId = _handle.ServerId, Id = _navigation.FolderId!, Name = _navigation.Title };
            if (!string.IsNullOrEmpty(_navigation.FolderPath))
            {
                folder = folder with { Path = _navigation.FolderPath };
            }
            _trail.Add(new BrowseCrumb(_navigation.Title, folder));
            _currentTitle = _navigation.Title;
        }

        await LoadAsync();
    }

    private async Task LoadAsync()
    {
        // Only one read is allowed in flight. A second request while one is running is remembered and
        // re-run when the first finishes, rather than dropped: on a slow server a user who clicks a
        // second folder expects to end up there, and losing the click looks like the app ignored them.
        if (_busy)
        {
            _pendingReload = true;
            StartupTrace.Write("browse:load defer title=" + _currentTitle);
            return;
        }

        _busy = true;
        _pendingReload = false;
        int generation = ++_generation;

        // Everything the render depends on is captured before the await. Reading _trail again after
        // the response arrived would label slow1's contents with slow2's breadcrumb, because the user
        // can navigate while a request is outstanding.
        MediaItem? folder = CurrentFolder;
        string title = _currentTitle;
        string breadcrumb = string.Join("  /  ", _trail.Select(step => step.Title));
        bool canGoUp = _trail.Count > 1;
        StartupTrace.Write("browse:load gen=" + generation + " title=" + title
            + " folder=" + (folder?.Id ?? "<root>"));

        LoadingRing.IsActive = true;
        EmptyPanel.Visibility = Visibility.Visible;
        RetryButton.Visibility = Visibility.Collapsed;
        EmptyText.Text = "正在读取目录…";
        StatusBar.IsOpen = false;

        try
        {
            PagedResult<MediaItem> page = await _handle!.Browse!
                .ListFolderAsync(folder, new PageRequest(0, 500))
                .ConfigureAwait(true);

            // Second line of defence: the serialisation above already prevents two reads from
            // overlapping, so this only fires if some future caller starts one directly.
            if (generation != _generation)
            {
                StartupTrace.Write("browse:load stale-discard gen=" + generation);
                return;
            }

            List<BrowseRow> rows = page.Items.Select(ToRow).ToList();
            ItemList.ItemsSource = rows;

            TitleText.Text = title;
            SourceText.Text = "来源：" + _handle.Server.DisplayName + "  ·  " + _handle.Server.Type;
            BreadcrumbText.Text = breadcrumb;
            // Root is the bottom of this source: there is nowhere above it to go.
            UpButton.IsEnabled = canGoUp;

            // A failed listing must never look like an empty folder: only a successful response that
            // genuinely carried no entries may say so.
            bool empty = rows.Count == 0;
            EmptyPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            if (empty) EmptyText.Text = "这个目录是空的。";
        }
        catch (ProviderException ex)
        {
            if (generation == _generation) Fail(ex.Message, title, breadcrumb, canGoUp);
        }
        catch (Exception ex)
        {
            if (generation == _generation) Fail(ex.Message, title, breadcrumb, canGoUp);
        }
        finally
        {
            if (generation == _generation)
            {
                LoadingRing.IsActive = false;
                _busy = false;
                if (_pendingReload)
                {
                    _pendingReload = false;
                    _ = LoadAsync();
                }
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
            _trail.Add(new BrowseCrumb(item.Name, item));
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

    private void OnRootsClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(LibrariesPage));

    private async void OnUpClick(object sender, RoutedEventArgs e)
    {
        // Popping the trail returns to the folder the user came from, which is the only definition of
        // "up" that cannot disagree with what they saw.
        if (_trail.Count <= 1) return;

        _trail.RemoveAt(_trail.Count - 1);
        _currentTitle = CurrentFolder is null ? _rootTitle : _trail[^1].Title;
        await LoadAsync();
    }

    private async void OnRetryClick(object sender, RoutedEventArgs e)
    {
        StartupTrace.Write("browse:retry title=" + _currentTitle);
        await LoadAsync();
    }

    // The header is part of the failure, not decoration. Measured defect: navigating from the source
    // root into a folder whose listing failed left the header still reading the root and "up" still
    // disabled, because only the success path ever rendered them. The user was then stranded in a
    // folder that shows an error, with retry as the only control - and retry cannot fix a missing
    // folder. The title, breadcrumb and up-state are therefore passed in from the same snapshot the
    // request was made with, so a failed read still tells the user where they are and lets them leave.
    private void Fail(string message, string title, string breadcrumb, bool canGoUp)
    {
        StartupTrace.Write("browse:load fail title=" + title + " message=" + message);
        LoadingRing.IsActive = false;
        _busy = false;
        ItemList.ItemsSource = null;
        EmptyPanel.Visibility = Visibility.Visible;
        RetryButton.Visibility = Visibility.Visible;
        EmptyText.Text = message;
        TitleText.Text = title;
        BreadcrumbText.Text = breadcrumb;
        UpButton.IsEnabled = canGoUp;
        if (_handle is not null)
        {
            SourceText.Text = "来源：" + _handle.Server.DisplayName + "  ·  " + _handle.Server.Type;
        }
        StatusBar.Severity = InfoBarSeverity.Error;
        StatusBar.Title = "无法读取目录";
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}

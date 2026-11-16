using System.Collections.ObjectModel;
using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;

namespace MediaHub.App.Views;

public sealed partial class LibraryPage : Page
{
    private readonly ObservableCollection<MediaCardViewModel> _cards = new();
    private readonly LibraryBrowser _browser = new();
    private LibraryNavigation? _navigation;
    private ProviderHandle? _handle;
    private bool _loaded;
    private bool _pageLoading;
    private bool _autoLoadBlocked;
    private bool _firstPageRendered;
    private bool _scrollHooked;
    private ScrollViewer? _gridScroll;

    public LibraryPage()
    {
        InitializeComponent();
        ItemGrid.ItemsSource = _cards;
        // One skeleton grid, reused: filling it in code keeps the placeholder count out of XAML and
        // out of the page's real item collection.
        SkeletonPanel.ItemsSource = new object[12];
        ItemGrid.Loaded += (_, _) => HookScroll();
        ItemGrid.ContainerContentChanging += OnContainerContentChanging;
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _navigation = e.Parameter as LibraryNavigation;
        Services.StartupTrace.Write("LibraryPage:OnNavigatedTo param=" + (e.Parameter?.GetType().Name ?? "null")
            + " nav=" + (_navigation is null ? "null" : _navigation.LibraryName));
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        _browser.Cancel();
        if (_scrollHooked && _gridScroll is not null)
        {
            _gridScroll.ViewChanged -= OnGridScrollViewChanged;
        }
        _scrollHooked = false;
        _gridScroll = null;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Services.StartupTrace.Write("LibraryPage:OnLoaded nav=" + (_navigation is null ? "null" : _navigation.LibraryName));
        try
        {
            await LoadAsync();
        }
        catch (Exception ex)
        {
            // An async void handler that throws would otherwise surface only as a blank page.
            Services.StartupTrace.Write("LibraryPage:LoadAsync FAILED " + ex.GetType().Name + ": " + ex.Message);
        }
    }

    private async Task LoadAsync()
    {
        if (_loaded || _navigation is null) return;
        _loaded = true;

        // A folder inside the library shows "Library / Folder" so the way back is obvious from the title.
        TitleText.Text = _navigation.FolderTitle is null
            ? _navigation.LibraryName
            : _navigation.LibraryName + " / " + _navigation.FolderTitle;
        _handle = App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Library is null)
        {
            ShowError("媒体源不可用", "该媒体源当前不可用，请在“媒体源”页面重新登录。");
            return;
        }

        // Emby keeps one physical folder per title at the library root, so the page has to be able to
        // list a folder's children; Jellyfin flattens the same library and never sets FolderId.
        _browser.Open(_navigation.ServerId, _navigation.LibraryId, pageSize: 60, parentId: _navigation.FolderId);
        ShowSkeleton();
        await LoadNextPageAsync();
    }

    private async Task LoadNextPageAsync()
    {
        if (_handle is null || _pageLoading) return;
        _pageLoading = true;

        if (_cards.Count == 0) ShowSkeleton();
        UpdateFooter(loading: true, error: false);

        LibraryPageResult? result;
        try
        {
            result = await _browser.LoadNextPageAsync(_handle);
        }
        finally
        {
            _pageLoading = false;
        }

        if (result is not null)
        {
            foreach (MediaItem item in result.Items)
            {
                _cards.Add(new MediaCardViewModel { Item = item, Source = _handle });
            }
            _autoLoadBlocked = false;

            if (!_firstPageRendered && _cards.Count > 0)
            {
                _firstPageRendered = true;
                UiMotion.FadeIn(ItemGrid);
            }
        }

        int? total = _browser.TotalCount;
        CountText.Text = total is { } t
            ? "已加载 " + _cards.Count + " / " + t + " 项"
            : "已加载 " + _cards.Count + " 项";

        HideSkeleton();

        bool failed = _browser.LastError is not null;
        if (failed)
        {
            // A failed page must not be retried by the scroll handler over and over: a server that is
            // refusing reads would otherwise be hammered for as long as the user keeps the page open.
            _autoLoadBlocked = true;
            StatusBar.Severity = InfoBarSeverity.Error;
            StatusBar.Title = "加载失败";
            StatusBar.Message = _browser.LastError!;
            StatusBar.IsOpen = true;
        }
        else
        {
            StatusBar.IsOpen = false;
        }

        if (_cards.Count == 0)
        {
            if (failed)
            {
                ShowError("无法加载媒体库", _browser.LastError!);
            }
            else if (!_browser.HasMore)
            {
                ShowEmpty("\uE714", "这里还没有内容", "该媒体库中没有可显示的条目。");
            }
        }
        else
        {
            EmptyPanel.Visibility = Visibility.Collapsed;
        }

        UpdateFooter(loading: false, error: failed);
    }

    private void UpdateFooter(bool loading, bool error)
    {
        bool hasCards = _cards.Count > 0;
        bool showProgress = loading && hasCards;

        MoreRing.IsActive = showProgress;
        MoreRing.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        MoreText.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;

        // The button remains the explicit path; reaching the end of the list is the implicit one.
        LoadMoreButton.Visibility = hasCards && _browser.HasMore ? Visibility.Visible : Visibility.Collapsed;
        LoadMoreButton.IsEnabled = !loading;
        LoadMoreButton.Content = error ? "重试加载" : "加载更多";

        EndText.Visibility = hasCards && !_browser.HasMore && !loading
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void ShowSkeleton()
    {
        EmptyPanel.Visibility = Visibility.Collapsed;
        SkeletonPanel.Visibility = Visibility.Visible;
        UiMotion.Pulse(SkeletonPanel);
    }

    private void HideSkeleton()
    {
        if (SkeletonPanel.Visibility != Visibility.Visible) return;
        UiMotion.Settle(SkeletonPanel);
        SkeletonPanel.Visibility = Visibility.Collapsed;
    }

    private void ShowEmpty(string glyph, string title, string message)
    {
        StateIcon.Style = (Style)Application.Current.Resources["MediaHubStateIcon"];
        StateGlyph.Style = (Style)Application.Current.Resources["MediaHubStateGlyph"];
        StateGlyph.Glyph = glyph;
        StateTitle.Text = title;
        EmptyText.Text = message;
        RetryButton.Visibility = Visibility.Collapsed;
        EmptyPanel.Visibility = Visibility.Visible;
    }

    private void ShowError(string title, string message)
    {
        StateIcon.Style = (Style)Application.Current.Resources["MediaHubStateIconError"];
        StateGlyph.Style = (Style)Application.Current.Resources["MediaHubStateGlyphError"];
        StateGlyph.Glyph = "\uE783";
        StateTitle.Text = title;
        EmptyText.Text = message;
        RetryButton.Visibility = Visibility.Visible;
        EmptyPanel.Visibility = Visibility.Visible;
    }

    private async void OnRetryClick(object sender, RoutedEventArgs e) => await ReloadAsync();

    private async Task ReloadAsync()
    {
        if (_navigation is null) return;

        _handle ??= App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Library is null)
        {
            ShowError("媒体源不可用", "该媒体源当前不可用，请在“媒体源”页面重新登录。");
            return;
        }

        _browser.Open(_navigation.ServerId, _navigation.LibraryId, pageSize: 60, parentId: _navigation.FolderId);
        _cards.Clear();
        _autoLoadBlocked = false;
        _firstPageRendered = false;
        StatusBar.IsOpen = false;
        ShowSkeleton();
        await LoadNextPageAsync();
    }

    private async void OnLoadMoreClick(object sender, RoutedEventArgs e)
    {
        _autoLoadBlocked = false;
        await LoadNextPageAsync();
    }

    private void OnContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.Item is not MediaCardViewModel card) return;

        // Artwork is requested as containers are realised, which is what keeps this cheap for a
        // library of thousands of items: only what the user has scrolled to is ever fetched.
        card.RequestPoster(App.Services.Images!, CancellationToken.None);
    }

    private void HookScroll()
    {
        if (_scrollHooked) return;
        _gridScroll = FindDescendant<ScrollViewer>(ItemGrid);
        if (_gridScroll is null) return;

        // Reaching the bottom is the second paging entry: the button stays for people who want to
        // ask explicitly, but a user who simply keeps scrolling never has to hunt for it.
        _gridScroll.ViewChanged += OnGridScrollViewChanged;
        _scrollHooked = true;
    }

    private void OnGridScrollViewChanged(object? sender, ScrollViewerViewChangedEventArgs e)
    {
        if (_gridScroll is null || _autoLoadBlocked || !_browser.HasMore || _pageLoading) return;

        double remaining = _gridScroll.ScrollableHeight - _gridScroll.VerticalOffset;
        if (remaining < 480) _ = LoadNextPageAsync();
    }

    private static T? FindDescendant<T>(DependencyObject root) where T : DependencyObject
    {
        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < count; i++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, i);
            if (child is T match) return match;
            if (FindDescendant<T>(child) is { } nested) return nested;
        }
        return null;
    }

    private void OnBackClick(object sender, RoutedEventArgs e)
    {
        // Inside a folder "back" means one level up (the library root), not all the way to the source list.
        if (_navigation is { FolderId: not null })
        {
            App.MainWindow?.NavigateShell(typeof(LibraryPage),
                new LibraryNavigation(_navigation.ServerId, _navigation.LibraryId, _navigation.LibraryName));
            return;
        }

        App.MainWindow?.NavigateShell(typeof(LibrariesPage));
    }

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not MediaCardViewModel card) return;
        OpenItem(card);
    }

    /// <summary>Click and right-click "open" must not diverge, so both end up here.</summary>
    private void OpenItem(MediaCardViewModel card)
    {
        if (_navigation is null) return;

        // A folder has nothing to play: sending it to the detail page produced a play button that
        // answered POST /Items/{folderId}/PlaybackInfo with 500 on Emby.
        if (card.IsFolder)
        {
            App.MainWindow?.NavigateShell(typeof(LibraryPage),
                new LibraryNavigation(card.Source.ServerId, _navigation.LibraryId, _navigation.LibraryName,
                    card.Item.Id, card.Item.Name));
            return;
        }

        App.MainWindow?.NavigateShell(typeof(DetailPage),
            new DetailNavigation(card.Source.ServerId, card.Item.Id, card.Item.Name));
    }

    private void OnOpenMenuClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is MediaCardViewModel card) OpenItem(card);
    }

    private void OnCopyTitleClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not MediaCardViewModel card) return;
        try
        {
            var package = new DataPackage();
            package.SetText(card.Title);
            Clipboard.SetContent(package);
        }
        catch (Exception ex)
        {
            // The clipboard is a shared system resource and can be held by another process. Failing to
            // copy a title is not worth interrupting the page for.
            Services.StartupTrace.Write("LibraryPage:clipboard FAILED " + ex.GetType().Name);
        }
    }
}

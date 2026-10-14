using System.Collections.ObjectModel;
using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace MediaHub.App.Views;

public sealed partial class LibraryPage : Page
{
    private readonly ObservableCollection<MediaCardViewModel> _cards = new();
    private readonly LibraryBrowser _browser = new();
    private LibraryNavigation? _navigation;
    private ProviderHandle? _handle;
    private bool _loaded;

    public LibraryPage()
    {
        InitializeComponent();
        ItemGrid.ItemsSource = _cards;
        ItemGrid.ContainerContentChanging += OnContainerContentChanging;
        Loaded += OnLoaded;
        Unloaded += (_, _) => _browser.Cancel();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _navigation = e.Parameter as LibraryNavigation;
        Services.StartupTrace.Write("LibraryPage:OnNavigatedTo param=" + (e.Parameter?.GetType().Name ?? "null")
            + " nav=" + (_navigation is null ? "null" : _navigation.LibraryName));
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

        TitleText.Text = _navigation.LibraryName;
        _handle = App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Library is null)
        {
            StatusBar.Severity = InfoBarSeverity.Error;
            StatusBar.Title = "媒体源不可用";
            StatusBar.Message = "该媒体源当前不可用，请在“媒体源”页面重新登录。";
            StatusBar.IsOpen = true;
            return;
        }

        _browser.Open(_navigation.ServerId, _navigation.LibraryId, pageSize: 60);
        await LoadNextPageAsync();
    }

    private async Task LoadNextPageAsync()
    {
        if (_handle is null) return;

        LoadingRing.IsActive = true;
        EmptyPanel.Visibility = _cards.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyText.Text = _cards.Count == 0 ? "正在加载媒体…" : "";

        LibraryPageResult? result = await _browser.LoadNextPageAsync(_handle);

        LoadingRing.IsActive = false;

        if (result is not null)
        {
            foreach (MediaItem item in result.Items)
            {
                _cards.Add(new MediaCardViewModel { Item = item, Source = _handle });
            }
        }

        int? total = _browser.TotalCount;
        CountText.Text = total is { } t
            ? "已加载 " + _cards.Count + " / " + t + " 项"
            : "已加载 " + _cards.Count + " 项";

        if (_browser.LastError is { } error)
        {
            StatusBar.Severity = InfoBarSeverity.Error;
            StatusBar.Title = "加载失败";
            StatusBar.Message = error;
            StatusBar.IsOpen = true;
        }

        LoadMoreButton.Visibility = _browser.HasMore ? Visibility.Visible : Visibility.Collapsed;
        EmptyPanel.Visibility = _cards.Count == 0 && !_browser.HasMore ? Visibility.Visible : Visibility.Collapsed;
        if (_cards.Count == 0 && !_browser.HasMore) EmptyText.Text = "该媒体库中没有可显示的条目。";
    }

    private async void OnLoadMoreClick(object sender, RoutedEventArgs e) => await LoadNextPageAsync();

    private void OnContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.Item is not MediaCardViewModel card) return;

        // Artwork is requested as containers are realised, which is what keeps this cheap for a
        // library of thousands of items: only what the user has scrolled to is ever fetched.
        card.RequestPoster(App.Services.Images!, CancellationToken.None);

        // Prefetch the next page slightly before the end so scrolling does not stall on the network.
        if (_browser.HasMore && args.ItemIndex >= _cards.Count - 12 && !_browser.IsLoading)
        {
            _ = LoadNextPageAsync();
        }
    }

    private void OnBackClick(object sender, RoutedEventArgs e)
        => MainWindow.Current?.NavigateShell(typeof(LibrariesPage));

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not MediaCardViewModel card) return;
        MainWindow.Current?.NavigateShell(typeof(DetailPage),
            new DetailNavigation(card.Source.ServerId, card.Item.Id, card.Item.Name));
    }
}

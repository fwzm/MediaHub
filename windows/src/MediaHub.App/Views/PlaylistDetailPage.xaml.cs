using System.Collections.ObjectModel;
using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Storage;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace MediaHub.App.Views;

/// <summary>
/// One entry of a saved playlist.
/// </summary>
/// <remarks>
/// The source id is shown as-is: the playlist stores a reference to a provider item, and the store's
/// snapshot stays readable while that source is offline, so there is nothing more reliable to show.
/// </remarks>
public sealed record PlaylistItemRow(
    string ServerId,
    string ItemId,
    string Title,
    string SourceLabel,
    string PlayLabel,
    string RemoveLabel);

/// <summary>
/// Shows the entries of one saved playlist and plays or removes a single entry.
/// </summary>
/// <remarks>
/// Playback is one <see cref="PlaybackRequest"/> per click. The playlist is never poured into
/// <see cref="AppServices.Queue"/> and the queue is never written back into the playlist: the two are
/// different things and only an explicit user action may touch either.
/// </remarks>
public sealed partial class PlaylistDetailPage : Page
{
    private PlaylistNavigation? _navigation;

    public PlaylistDetailPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Reload();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _navigation = e.Parameter as PlaylistNavigation;
    }

    /// <summary>Re-reads the playlist from the store and re-renders it.</summary>
    private void Reload()
    {
        StatusBar.IsOpen = false;

        if (_navigation is null)
        {
            ShowEmpty("播放列表不存在", "没有指定要显示的播放列表，请从“播放列表”页面进入。");
            return;
        }

        SavedPlaylist? playlist = App.Services.Playlists.Find(_navigation.PlaylistId);
        if (playlist is null)
        {
            ShowEmpty("播放列表不存在", "该播放列表可能已被删除，请返回“播放列表”页面。");
            return;
        }

        TitleText.Text = playlist.Name;

        var rows = playlist.Items
            .Select(item => new PlaylistItemRow(
                item.ServerId,
                item.ItemId,
                item.Title,
                "来源：" + item.ServerId,
                "播放 " + item.Title,
                "从播放列表移除 " + item.Title))
            .ToList();

        bool empty = rows.Count == 0;
        ItemList.ItemsSource = new ObservableCollection<PlaylistItemRow>(rows);
        ItemList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        EmptyPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>
    /// Persists the order the user dropped the rows into. The store rejects anything that is not a
    /// complete permutation, so a refresh always lands on a real saved order.
    /// </summary>
    private void OnItemsReordered(ListViewBase sender, DragItemsCompletedEventArgs args)
    {
        if (_navigation is null) return;

        SavedPlaylist? playlist = App.Services.Playlists.Find(_navigation.PlaylistId);
        if (playlist is null)
        {
            Reload();
            return;
        }

        var ordered = ItemList.Items.OfType<PlaylistItemRow>().ToList();
        if (ordered.Count != playlist.Items.Count)
        {
            Reload();
            return;
        }

        var desired = new List<SavedPlaylistItem>(ordered.Count);
        foreach (PlaylistItemRow row in ordered)
        {
            SavedPlaylistItem? match = playlist.Items.FirstOrDefault(item =>
                string.Equals(item.ServerId, row.ServerId, StringComparison.Ordinal)
                && string.Equals(item.ItemId, row.ItemId, StringComparison.Ordinal));
            if (match is null)
            {
                Reload();
                return;
            }

            desired.Add(match);
        }

        try
        {
            bool reordered = App.Services.Playlists.ReorderItems(_navigation.PlaylistId, desired);
            Reload();
            if (!reordered)
            {
                ShowStatus(InfoBarSeverity.Warning, "未保存排序", "播放列表在拖拽期间发生变化，已刷新为最新顺序。");
            }
        }
        catch (IOException ex)
        {
            Reload();
            ShowStatus(InfoBarSeverity.Error, "无法保存排序", ex.Message);
        }
    }

    private void ShowEmpty(string title, string message)
    {
        TitleText.Text = "播放列表";
        ItemList.ItemsSource = null;
        ItemList.Visibility = Visibility.Collapsed;
        EmptyPanel.Visibility = Visibility.Collapsed;
        ShowStatus(InfoBarSeverity.Warning, title, message);
    }

    private void OnPlayItemClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PlaylistItemRow row) return;

        App.MainWindow?.EnterPlayer(new PlaybackRequest
        {
            ServerId = row.ServerId,
            ItemId = row.ItemId,
            Title = row.Title,
        });
    }

    private void OnRemoveItemClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PlaylistItemRow row) return;
        if (_navigation is null) return;

        try
        {
            bool removed = App.Services.Playlists.RemoveItem(_navigation.PlaylistId, row.ServerId, row.ItemId);
            Reload();
            ShowStatus(
                removed ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                removed ? "已移除" : "未移除",
                removed ? "“" + row.Title + "”已从播放列表移除。" : "该条目已不在播放列表中，页面已刷新。");
        }
        catch (IOException ex)
        {
            ShowStatus(InfoBarSeverity.Error, "无法移除条目", ex.Message);
        }
    }

    private void OnBackClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(PlaylistsPage));

    /// <summary>Surfaces a store failure instead of swallowing it.</summary>
    private void ShowStatus(InfoBarSeverity severity, string title, string message)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}

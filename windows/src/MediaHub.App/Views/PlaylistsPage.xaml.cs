using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Storage;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

/// <summary>
/// One row of <see cref="PlaylistsPage"/>.
/// </summary>
/// <remarks>
/// The accessible labels are computed here rather than in the template, so every row action announces
/// which playlist it acts on instead of three identical "播放 / 重命名 / 删除" buttons.
/// </remarks>
public sealed record PlaylistRow(string Id, string Name, int ItemCount)
{
    public string CountLabel => ItemCount + " 项";
    public string PlayLabel => "播放 " + Name;
    public string RenameLabel => "重命名 " + Name;
    public string DeleteLabel => "删除 " + Name;
}

/// <summary>
/// Lists the user's durable playlists and offers create / play / rename / delete for them.
/// </summary>
/// <remarks>
/// The page talks to <see cref="PlaylistStore"/> only: it never writes the JSON file itself, and it
/// never turns a playlist into queue content. Opening an entry belongs to the detail page, which sends
/// one explicit playback request.
/// </remarks>
public sealed partial class PlaylistsPage : Page
{
    public PlaylistsPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Reload();
    }

    /// <summary>Re-reads the store snapshot and re-renders; called after every mutation.</summary>
    private void Reload()
    {
        StatusBar.IsOpen = false;

        var rows = App.Services.Playlists.GetAll()
            .Select(playlist => new PlaylistRow(playlist.Id, playlist.Name, playlist.Items.Count))
            .ToList();

        bool empty = rows.Count == 0;
        PlaylistList.ItemsSource = rows;
        PlaylistList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        EmptyPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void OnCreateClick(object sender, RoutedEventArgs e)
    {
        string? name = await PlaylistNameDialog.PromptAsync(XamlRoot, "新建播放列表", "播放列表名称", "");
        if (name is null) return;
        if (name.Length == 0)
        {
            ShowStatus(InfoBarSeverity.Warning, "未创建播放列表", "播放列表名称不能为空。");
            return;
        }

        try
        {
            SavedPlaylist created = App.Services.Playlists.Create(name);
            Reload();
            ShowStatus(InfoBarSeverity.Success, "已创建播放列表", "“" + created.Name + "”已创建。");
        }
        catch (IOException ex)
        {
            ShowStatus(InfoBarSeverity.Error, "无法创建播放列表", ex.Message);
        }
    }

    private void OnPlaylistClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not PlaylistRow row) return;
        App.MainWindow?.NavigateShell(typeof(PlaylistDetailPage), new PlaylistNavigation(row.Id));
    }

    /// <summary>Starts playback at the first entry of the list.</summary>
    /// <remarks>
    /// One <see cref="PlaybackRequest"/> is sent, which is the whole point of the separation between a
    /// saved playlist and the temporary queue: pressing play here must not silently load the rest of
    /// the list into <see cref="AppServices.Queue"/>.
    /// </remarks>
    private void OnPlayPlaylistClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PlaylistRow row) return;

        SavedPlaylist? playlist = App.Services.Playlists.Find(row.Id);
        if (playlist is null)
        {
            ShowStatus(InfoBarSeverity.Warning, "播放列表不存在", "该播放列表可能已被删除，列表已刷新。");
            Reload();
            return;
        }

        if (playlist.Items.Count == 0)
        {
            ShowStatus(InfoBarSeverity.Informational, "这个播放列表还是空的",
                "先从媒体详情页把条目加入该列表，再从这里开始播放。");
            return;
        }

        SavedPlaylistItem first = playlist.Items[0];
        App.MainWindow?.EnterPlayer(new PlaybackRequest
        {
            ServerId = first.ServerId,
            ItemId = first.ItemId,
            Title = first.Title,
        });
    }

    private async void OnRenameClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PlaylistRow row) return;

        string? name = await PlaylistNameDialog.PromptAsync(XamlRoot, "重命名播放列表", "新的名称", row.Name);
        if (name is null) return;
        if (name.Length == 0)
        {
            ShowStatus(InfoBarSeverity.Warning, "未重命名", "播放列表名称不能为空。");
            return;
        }

        try
        {
            bool renamed = App.Services.Playlists.Rename(row.Id, name);
            Reload();
            ShowStatus(
                renamed ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                renamed ? "已重命名" : "未重命名",
                renamed ? "“" + row.Name + "”已重命名为“" + name + "”。" : "该播放列表已不存在，列表已刷新。");
        }
        catch (IOException ex)
        {
            ShowStatus(InfoBarSeverity.Error, "无法重命名播放列表", ex.Message);
        }
    }

    private async void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PlaylistRow row) return;

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "删除播放列表",
            Content = "将删除“" + row.Name + "”及其中的 " + row.ItemCount + " 个条目。媒体本身不会被删除，此操作不可撤销。",
            PrimaryButtonText = "删除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        try
        {
            bool deleted = App.Services.Playlists.Delete(row.Id);
            Reload();
            ShowStatus(
                deleted ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                deleted ? "已删除播放列表" : "未删除",
                deleted ? "“" + row.Name + "”已删除。" : "该播放列表已不存在，列表已刷新。");
        }
        catch (IOException ex)
        {
            ShowStatus(InfoBarSeverity.Error, "无法删除播放列表", ex.Message);
        }
    }

    /// <summary>Surfaces a store failure instead of swallowing it.</summary>
    private void ShowStatus(InfoBarSeverity severity, string title, string message)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}

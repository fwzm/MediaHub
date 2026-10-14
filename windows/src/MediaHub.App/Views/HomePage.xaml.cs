using MediaHub.App.Services;
using MediaHub.Domain;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

public sealed partial class HomePage : Page
{
    public HomePage()
    {
        InitializeComponent();
        Loaded += (_, _) => Refresh();
    }

    private void Refresh()
    {
        var entries = App.Services.PlaybackHistory.ContinueWatching()
            .Select(e => new ContinueItem(
                e.Title,
                e.MediaPath,
                e.ProgressFraction,
                Format(e.Position),
                Format(e.Duration)))
            .ToList();

        ContinueGrid.ItemsSource = entries;
        ContinueEmptyText.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        // Counted over servers only. Including the local source in the same sentence produced
        // "1 configured, 2 available", which reads as a bug because it is one.
        var remote = App.Services.Servers.Servers.Where(s => s.Type != ServerType.Local).ToList();
        int online = remote.Count(s => s.Status == ServerStatus.Online);
        SourceSummaryText.Text = remote.Count == 0
            ? "尚未添加媒体服务器。前往“媒体源”添加 Emby 或 Jellyfin 后即可浏览媒体库。"
            : "已配置 " + remote.Count + " 个媒体服务器，其中 " + online + " 个当前可用。";
    }

    private static string Format(TimeSpan t)
        => t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");

    private void OnContinueItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not ContinueItem item) return;
        if (!System.IO.File.Exists(item.Path)) return;

        PlaybackHistoryEntry? entry = App.Services.PlaybackHistory.Find(item.Path);
        App.MainWindow?.EnterPlayer(new PlaybackRequest
        {
            LocalPath = item.Path,
            Title = item.Title,
            DisplayUrl = item.Path,
            StartPositionTicks = App.Services.Settings.Current.ResumePlayback ? (entry?.PositionTicks ?? 0) : 0,
            DeclaredDuration = entry is { DurationTicks: > 0 } ? TimeSpan.FromTicks(entry.DurationTicks) : null,
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(item.Path),
        });
    }

    private void OnLibrariesClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(LibrariesPage));

    private void OnSearchClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(SearchPage));

    private void OnSourcesClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(SourcesPage));

    private void OnLocalClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(LocalLibraryPage));

    private sealed record ContinueItem(
        string Title, string Path, double ProgressFraction, string PositionDisplay, string DurationDisplay);
}

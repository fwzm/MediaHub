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
            .Select(e =>
            {
                // Server playback keeps only a local "recently played" record keyed by
                // "<itemId>@<serverId>" (see PlaybackCoordinator); it is not a file on disk, so the
                // local-file branch must not swallow it. Parsing here keeps the click path honest
                // instead of silently doing nothing for those rows.
                bool isServer = TryParseServerIdentity(e.MediaPath, out string serverId, out string itemId)
                    && !System.IO.File.Exists(e.MediaPath);
                return new ContinueItem(
                    e.Title,
                    e.MediaPath,
                    e.ProgressFraction,
                    Format(e.Position),
                    Format(e.Duration),
                    isServer,
                    isServer ? serverId : null,
                    isServer ? itemId : null,
                    isServer ? ServerLabelOf(serverId) : null);
            })
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

    private static string? ServerLabelOf(string serverId)
    {
        ServerEntry? server = App.Services.Servers.Servers.FirstOrDefault(s => s.ServerId == serverId);
        if (server is null) return null;
        return string.IsNullOrWhiteSpace(server.DisplayName) ? server.TypeName : server.DisplayName;
    }

    private static string Format(TimeSpan t)
        => t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");

    private void OnContinueItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not ContinueItem item) return;

        if (item.IsServer)
        {
            PlaybackHistoryEntry? serverEntry = App.Services.PlaybackHistory.Find(item.Path);
            App.MainWindow?.EnterPlayer(new PlaybackRequest
            {
                ServerId = item.ServerId,
                ItemId = item.ItemId,
                Title = item.Title,
                StartPositionTicks = App.Services.Settings.Current.ResumePlayback ? (serverEntry?.PositionTicks ?? 0) : 0,
            });
            return;
        }

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

    /// <summary>
    /// Splits the "<itemId>@<serverId>" identity that server playback writes into the local history
    /// store. Local paths never use that shape (they carry a directory separator), and the caller
    /// still prefers a real file when one exists.
    /// </summary>
    private static bool TryParseServerIdentity(string value, out string serverId, out string itemId)
    {
        serverId = string.Empty;
        itemId = string.Empty;
        if (string.IsNullOrWhiteSpace(value)) return false;

        int at = value.LastIndexOf('@');
        if (at <= 0 || at == value.Length - 1) return false;

        string candidateItem = value[..at];
        string candidateServer = value[(at + 1)..];
        if (candidateItem.Contains(Path.DirectorySeparatorChar) || candidateItem.Contains(Path.AltDirectorySeparatorChar)) return false;
        if (candidateServer.Contains(Path.DirectorySeparatorChar) || candidateServer.Contains(Path.AltDirectorySeparatorChar)) return false;
        if (candidateServer.Contains(':')) return false;

        itemId = candidateItem;
        serverId = candidateServer;
        return true;
    }

    private sealed record ContinueItem(
        string Title,
        string Path,
        double ProgressFraction,
        string PositionDisplay,
        string DurationDisplay,
        bool IsServer,
        string? ServerId,
        string? ItemId,
        string? SourceLabel);
}

using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

/// <summary>A resume-able item, from either a media server or the local history.</summary>
public sealed record ContinueRow(
    MediaCardViewModel Card,
    string ServerId,
    string ItemId,
    string Title,
    long PositionTicks,
    double Fraction,
    string ProgressLabel,
    string SourceLabel);

public sealed partial class ContinueWatchingPage : Page
{
    private bool _loading;

    public ContinueWatchingPage()
    {
        InitializeComponent();
        Loaded += (_, _) => _ = LoadAsync();
    }

    private void OnReloadClick(object sender, RoutedEventArgs e) => _ = LoadAsync();

    private async Task LoadAsync()
    {
        if (_loading) return;
        _loading = true;
        BusyRing.IsActive = true;
        StatusBar.IsOpen = false;

        var rows = new List<ContinueRow>();
        var failures = new List<string>();

        try
        {
            // Server-side resume state is authoritative when a source provides it.
            foreach (ProviderHandle handle in App.Services.Servers.OnlineHandles.Where(h => h.Progress is not null))
            {
                try
                {
                    IReadOnlyList<MediaItem> items = await handle.Progress!.GetContinueWatchingAsync(24);
                    foreach (MediaItem item in items)
                    {
                        long position = item.UserDataPlaybackPositionTicks ?? 0;
                        long duration = item.RunTimeTicks ?? 0;
                        rows.Add(new ContinueRow(
                            new MediaCardViewModel { Item = item, Source = handle },
                            handle.ServerId,
                            item.Id,
                            item.Name,
                            position,
                            duration > 0 ? Math.Clamp((double)position / duration, 0, 1) : 0,
                            Format(position) + (duration > 0 ? " / " + Format(duration) : ""),
                            handle.Server.DisplayName));
                    }
                }
                catch (ProviderException ex)
                {
                    failures.Add(handle.Server.DisplayName + "：" + ex.Message);
                }
            }

            if (failures.Count > 0)
            {
                StatusBar.Severity = InfoBarSeverity.Warning;
                StatusBar.Title = failures.Count + " 个媒体源未能返回继续观看列表";
                StatusBar.Message = string.Join("；", failures);
                StatusBar.IsOpen = true;
            }

            ItemGrid.ItemsSource = rows;
            EmptyPanel.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        finally
        {
            BusyRing.IsActive = false;
            _loading = false;
        }
    }

    private static string Format(long ticks)
    {
        TimeSpan t = TimeSpan.FromTicks(Math.Max(0, ticks));
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not ContinueRow row) return;
        App.MainWindow?.EnterPlayer(new PlaybackRequest
        {
            ServerId = row.ServerId,
            ItemId = row.ItemId,
            Title = row.Title,
            StartPositionTicks = row.PositionTicks,
        });
    }
}

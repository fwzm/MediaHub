using MediaHub.App.Services;
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
    }

    private static string Format(TimeSpan t)
        => t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");

    private void OnContinueItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not ContinueItem item) return;
        if (!System.IO.File.Exists(item.Path)) return;

        var entry = App.Services.PlaybackHistory.Find(item.Path);
        var request = new PlaybackRequest
        {
            Path = item.Path,
            Title = item.Title,
            StartPositionTicks = App.Services.Settings.Current.ResumePlayback ? (entry?.PositionTicks ?? 0) : 0,
            DeclaredDuration = entry is { DurationTicks: > 0 } ? TimeSpan.FromTicks(entry.DurationTicks) : null,
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(item.Path),
        };

        (App.MainWindow as MainWindow)?.EnterPlayer(request);
    }

    private sealed record ContinueItem(string Title, string Path, double ProgressFraction, string PositionDisplay, string DurationDisplay);
}

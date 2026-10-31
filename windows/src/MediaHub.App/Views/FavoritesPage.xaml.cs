using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

/// <summary>One favourited item, from any source that implements favourites.</summary>
public sealed record FavoriteRow(
    MediaCardViewModel Card,
    string ServerId,
    string ItemId,
    string Title,
    string SourceLabel);

public sealed partial class FavoritesPage : Page
{
    private bool _loading;

    public FavoritesPage()
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

        var rows = new List<FavoriteRow>();
        var failures = new List<string>();

        try
        {
            // Only sources that declare favourites are asked: a source without the capability has
            // nothing to fail at, and reporting it as a failure would blame it for a feature it never claimed.
            foreach (ProviderHandle handle in App.Services.Servers.OnlineHandles.Where(h => h.Favorites is not null))
            {
                try
                {
                    IReadOnlyList<MediaItem> items = await handle.Favorites!.GetFavoritesAsync(50);
                    foreach (MediaItem item in items)
                    {
                        rows.Add(new FavoriteRow(
                            new MediaCardViewModel { Item = item, Source = handle },
                            handle.ServerId,
                            item.Id,
                            item.Name,
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
                StatusBar.Title = failures.Count + " 个媒体源未能返回收藏列表";
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

    /// <summary>Requests artwork as tiles are realised, so a long favourite list stays cheap.</summary>
    private void OnContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.Item is not FavoriteRow row) return;
        row.Card.RequestPoster(App.Services.Images!, CancellationToken.None);
    }

    private void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not FavoriteRow row) return;
        App.MainWindow?.NavigateShell(typeof(DetailPage),
            new DetailNavigation(row.ServerId, row.ItemId, row.Title));
    }
}

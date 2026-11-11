using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace MediaHub.App.Views;

/// <summary>A search hit paired with its card view-model and the source it came from.</summary>
public sealed record SearchResultRow(MediaCardViewModel Card, string ServerId, string ItemId, string Title);

public sealed partial class SearchPage : Page
{
    private CancellationTokenSource? _queryCts;

    public SearchPage()
    {
        InitializeComponent();
        Unloaded += (_, _) => _queryCts?.Cancel();
    }

    private async void OnQueryKeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        await RunSearchAsync();
    }

    private async void OnSearchClick(object sender, RoutedEventArgs e) => await RunSearchAsync();

    private async Task RunSearchAsync()
    {
        string query = QueryBox.Text?.Trim() ?? "";
        if (query.Length == 0) return;

        // A new query cancels the previous one, so results always correspond to what is in the box.
        _queryCts?.Cancel();
        _queryCts?.Dispose();
        _queryCts = new CancellationTokenSource();
        CancellationToken ct = _queryCts.Token;

        BusyRing.IsActive = true;
        SearchButton.IsEnabled = false;
        StatusBar.IsOpen = false;
        ResultSummary.Text = "正在搜索…";

        try
        {
            SearchResult result = await App.Services.Search.SearchAsync(query, 40, ct);
            ct.ThrowIfCancellationRequested();

            var rows = new List<SearchResultRow>();
            foreach (SearchHit hit in result.Hits)
            {
                ProviderHandle? handle = App.Services.Servers.FindHandle(hit.ServerId);
                if (handle is null) continue;
                rows.Add(new SearchResultRow(
                    new MediaCardViewModel { Item = hit.Item, Source = handle },
                    hit.ServerId,
                    hit.Item.Id,
                    hit.Item.Name));
            }

            ResultGrid.ItemsSource = rows;
            EmptyPanel.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            if (rows.Count == 0) EmptyText.Text = "没有找到匹配的媒体。";

            var summary = new List<string> { "共 " + rows.Count + " 个结果" };
            if (result.FailedSources > 0) summary.Add(result.FailedSources + " 个媒体源未返回结果");
            ResultSummary.Text = string.Join("  ·  ", summary);

            if (result.FailedSources > 0)
            {
                StatusBar.Severity = result.AllFailed ? InfoBarSeverity.Error : InfoBarSeverity.Warning;
                StatusBar.Title = result.AllFailed ? "所有媒体源都未能返回结果" : "部分媒体源未能返回结果";
                StatusBar.Message = string.Join("；", result.Outcomes
                    .Where(o => o.Failed)
                    .Select(o => o.ServerName + "：" + o.Error));
                StatusBar.IsOpen = true;
            }
        }
        catch (OperationCanceledException)
        {
            // Superseded by a newer query; the newer one owns the UI.
        }
        finally
        {
            BusyRing.IsActive = false;
            SearchButton.IsEnabled = true;
        }
    }

    private void OnResultClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not SearchResultRow row) return;
        App.MainWindow?.NavigateShell(typeof(DetailPage),
            new DetailNavigation(row.ServerId, row.ItemId, row.Title));
    }
}

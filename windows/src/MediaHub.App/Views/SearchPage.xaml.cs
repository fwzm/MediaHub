using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;
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
        SkeletonPanel.ItemsSource = new object[8];
        Unloaded += (_, _) => _queryCts?.Cancel();
    }

    private async void OnQueryKeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        await RunSearchAsync();
    }

    private async void OnSearchClick(object sender, RoutedEventArgs e) => await RunSearchAsync();

    private async void OnRetryClick(object sender, RoutedEventArgs e) => await RunSearchAsync();

    private async Task RunSearchAsync()
    {
        string query = QueryBox.Text?.Trim() ?? "";
        if (query.Length == 0)
        {
            ShowState("\uE721", "搜索媒体库", "输入关键词开始搜索；每个已连接的媒体源会独立返回结果。",
                error: false, retry: false);
            ResultSummary.Text = "";
            return;
        }

        // A new query cancels the previous one, so results always correspond to what is in the box.
        _queryCts?.Cancel();
        _queryCts?.Dispose();
        _queryCts = new CancellationTokenSource();
        CancellationToken ct = _queryCts.Token;

        BusyRing.IsActive = true;
        SearchButton.IsEnabled = false;
        StatusBar.IsOpen = false;
        ResultSummary.Text = "正在搜索…";
        EmptyPanel.Visibility = Visibility.Collapsed;

        // A first search has nothing to keep on screen, so the grid gets placeholders instead of a
        // blank area; a re-search keeps the previous results visible until the new ones arrive.
        bool showSkeleton = ResultGrid.ItemsSource is null;
        if (showSkeleton)
        {
            SkeletonPanel.Visibility = Visibility.Visible;
            UiMotion.Pulse(SkeletonPanel);
        }

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

            var summary = new List<string> { "共 " + rows.Count + " 个结果" };
            if (result.FailedSources > 0) summary.Add(result.FailedSources + " 个媒体源未返回结果");
            ResultSummary.Text = string.Join("  ·  ", summary);

            if (rows.Count == 0)
            {
                if (result.AllFailed)
                {
                    // "No results" and "nothing answered" are different facts; showing the empty
                    // message for a failed search would tell the user their library lacks the title.
                    ShowState("\uE783", "搜索失败",
                        "所有媒体源都没有返回结果。请检查网络或“媒体源”页面的登录状态，然后重试。",
                        error: true, retry: true);
                }
                else
                {
                    ShowState("\uE721", "没有找到匹配的媒体",
                        "没有媒体源返回“" + query + "”相关的结果。可以换个关键词，或直接在媒体库中浏览。",
                        error: false, retry: false);
                }
            }
            else
            {
                EmptyPanel.Visibility = Visibility.Collapsed;
            }

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
        catch (Exception ex)
        {
            ResultSummary.Text = "搜索失败";
            ShowState("\uE783", "搜索失败", ex.Message, error: true, retry: true);
        }
        finally
        {
            if (!ct.IsCancellationRequested)
            {
                HideSkeleton();
                BusyRing.IsActive = false;
                SearchButton.IsEnabled = true;
            }
        }
    }

    private void HideSkeleton()
    {
        if (SkeletonPanel.Visibility != Visibility.Visible) return;
        UiMotion.Settle(SkeletonPanel);
        SkeletonPanel.Visibility = Visibility.Collapsed;
    }

    private void ShowState(string glyph, string title, string description, bool error, bool retry)
    {
        StateIcon.Style = (Style)Application.Current.Resources[
            error ? "MediaHubStateIconError" : "MediaHubStateIcon"];
        StateGlyph.Style = (Style)Application.Current.Resources[
            error ? "MediaHubStateGlyphError" : "MediaHubStateGlyph"];
        StateGlyph.Glyph = glyph;
        StateTitle.Text = title;
        EmptyText.Text = description;
        RetryButton.Visibility = retry ? Visibility.Visible : Visibility.Collapsed;
        EmptyPanel.Visibility = Visibility.Visible;
    }

    private void OnResultClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not SearchResultRow row) return;
        OpenResult(row);
    }

    private static void OpenResult(SearchResultRow row)
        => App.MainWindow?.NavigateShell(typeof(DetailPage),
            new DetailNavigation(row.ServerId, row.ItemId, row.Title));

    private void OnOpenMenuClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is SearchResultRow row) OpenResult(row);
    }

    private void OnCopyTitleClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not SearchResultRow row) return;
        try
        {
            var package = new DataPackage();
            package.SetText(row.Title);
            Clipboard.SetContent(package);
        }
        catch (Exception ex)
        {
            // The clipboard is shared with other processes; losing a copy is not worth an error.
            StartupTrace.Write("SearchPage:clipboard FAILED " + ex.GetType().Name);
        }
    }
}

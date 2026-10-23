using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace MediaHub.App.Views;

public sealed partial class DetailPage : Page
{
    private DetailNavigation? _navigation;
    private ProviderHandle? _handle;
    private MediaDetail? _detail;
    private MediaCardViewModel? _card;
    private IReadOnlyList<Season> _seasons = Array.Empty<Season>();
    private bool _loaded;

    private sealed record StreamRow(string Label, string Value);
    private sealed record EpisodeRow(string Id, string EpisodeLabel, string Title, string ProgressLabel);

    public DetailPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _navigation = e.Parameter as DetailNavigation;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e) => await LoadAsync();

    private async Task LoadAsync()
    {
        if (_loaded || _navigation is null) return;
        _loaded = true;

        HeaderText.Text = _navigation.Title;
        _handle = App.Services.Servers.FindHandle(_navigation.ServerId);
        if (_handle?.Detail is null)
        {
            Fail("媒体源不可用", "该媒体源当前不可用，请在“媒体源”页面重新登录。");
            return;
        }

        BusyRing.IsActive = true;
        try
        {
            _detail = await _handle.Detail.GetItemDetailAsync(_navigation.ItemId);
        }
        catch (ProviderException ex)
        {
            Fail("无法加载媒体详情", ex.Message);
            return;
        }
        finally
        {
            BusyRing.IsActive = false;
        }

        Render(_detail);
        await LoadArtworkAsync(_detail);

        if (_detail.Item.Type == MediaType.Series && _handle.Library is not null)
        {
            await LoadSeasonsAsync();
        }
    }

    private void Render(MediaDetail detail)
    {
        MediaItem item = detail.Item;
        TitleText.Text = item.Name;

        // Folders are containers, not playable items: Emby answers POST /Items/{folderId}/PlaybackInfo
        // with 500, so offering the play button here only produces an error the user cannot act on.
        bool isFolder = item.IsFolder || item.Type == MediaType.Folder;
        PlayButton.Visibility = isFolder ? Visibility.Collapsed : Visibility.Visible;
        if (isFolder)
        {
            ResumeButton.Visibility = Visibility.Collapsed;
            StatusBar.Severity = InfoBarSeverity.Informational;
            StatusBar.Title = "这是一个文件夹";
            StatusBar.Message = "该条目是文件夹，不是可播放的媒体。请返回媒体库并进入文件夹后选择其中的条目。";
            StatusBar.IsOpen = true;
        }

        var meta = new List<string>();
        if (item.Year is { } year && year > 0) meta.Add(year.ToString());
        if (item.RunTime is { } runtime) meta.Add(((int)runtime.TotalMinutes) + " 分钟");
        if (item.CommunityRating is { } rating) meta.Add("评分 " + rating.ToString("0.0"));
        if (!string.IsNullOrWhiteSpace(detail.OfficialRating)) meta.Add(detail.OfficialRating!);
        if (item.Genres.Count > 0) meta.Add(string.Join(" / ", item.Genres));
        MetaText.Text = string.Join("  ·  ", meta);

        SourceText.Text = "来源：" + _handle!.Server.DisplayName + "  ·  " + _handle.Server.Type;
        string? overview = item.Overview;
        OverviewText.Text = string.IsNullOrWhiteSpace(overview) ? "（服务器未提供简介）" : overview;

        var streams = detail.Streams.Count > 0
            ? detail.Streams
            : _detail?.Streams ?? (IReadOnlyList<MediaStreamInfo>)Array.Empty<MediaStreamInfo>();

        var rows = new List<StreamRow>();
        MediaStreamInfo? video = streams.FirstOrDefault(s => s.Type == "video");
        MediaStreamInfo? audio = streams.FirstOrDefault(s => s.Type == "audio");
        rows.Add(new StreamRow("视频", video is null
            ? "未知"
            : Join(video.Codec, video.Width is { } w && video.Height is { } h ? w + "×" + h : null, video.ColorTransfer)));
        rows.Add(new StreamRow("音频", audio is null
            ? "未知"
            : Join(audio.Codec, audio.Channels is { } c ? c + " 声道" : null, audio.SampleRate is { } sr ? sr + " Hz" : null)));
        rows.Add(new StreamRow("字幕", streams.Count(s => s.Type == "subtitle") + " 条"));
        rows.Add(new StreamRow("容器", item.FileSizeBytes is { } size ? (size / (double)(1L << 20)).ToString("0.0") + " MB" : "未知"));
        StreamList.ItemsSource = rows;

        // A folder carries no media streams; showing "视频 未知 / 音频 未知" would read as a broken file.
        VersionsSection.Visibility = isFolder ? Visibility.Collapsed : Visibility.Visible;

        if (item.UserDataPlaybackPositionTicks is { } position && position > TimeSpan.TicksPerSecond * 15)
        {
            ResumeButton.Visibility = Visibility.Visible;
            ResumeButtonText.Text = "继续观看（" + FormatTicks(position) + "）";
        }
    }

    private static string Join(params string?[] parts)
        => string.Join("  ·  ", parts.Where(p => !string.IsNullOrWhiteSpace(p)).Select(p => p!));

    private static string FormatTicks(long ticks)
    {
        TimeSpan t = TimeSpan.FromTicks(ticks);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    private async Task LoadArtworkAsync(MediaDetail detail)
    {
        if (App.Services.Images is null) return;
        _card = new MediaCardViewModel { Item = detail.Item, Source = _handle! };

        var image = await _card.LoadPosterLargeAsync(App.Services.Images, CancellationToken.None);
        if (image is not null) PosterImage.Source = image;

        var backdrop = await _card.LoadBackdropAsync(App.Services.Images, CancellationToken.None);
        _ = backdrop; // backdrop is available for the future hero layout; the poster carries the page today
    }

    private async Task LoadSeasonsAsync()
    {
        try
        {
            _seasons = await _handle!.Library!.GetSeasonsAsync(_navigation!.ItemId);
        }
        catch (ProviderException ex)
        {
            Fail("无法加载季列表", ex.Message);
            return;
        }

        if (_seasons.Count == 0) return;

        EpisodesSection.Visibility = Visibility.Visible;
        SeasonCombo.ItemsSource = _seasons
            .OrderBy(s => s.SeasonNumber)
            .Select(s => s.SeasonNumber == 0 ? "特别篇" : "第 " + s.SeasonNumber + " 季")
            .ToList();
        SeasonCombo.SelectedIndex = 0;
    }

    private async void OnSeasonChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SeasonCombo.SelectedIndex < 0 || SeasonCombo.SelectedIndex >= _seasons.Count) return;
        Season season = _seasons.OrderBy(s => s.SeasonNumber).ElementAt(SeasonCombo.SelectedIndex);

        BusyRing.IsActive = true;
        try
        {
            IReadOnlyList<Episode> episodes = await _handle!.Library!.GetEpisodesAsync(season.Id);
            EpisodeList.ItemsSource = episodes
                .OrderBy(ep => ep.EpisodeNumber)
                .Select(ep => new EpisodeRow(ep.Id, "E" + ep.EpisodeNumber.ToString("00"), ep.Name, ""))
                .ToList();
        }
        catch (ProviderException ex)
        {
            StatusBar.Severity = InfoBarSeverity.Error;
            StatusBar.Title = "无法加载剧集";
            StatusBar.Message = ex.Message;
            StatusBar.IsOpen = true;
        }
        finally
        {
            BusyRing.IsActive = false;
        }
    }

    private void OnEpisodeClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not EpisodeRow row) return;
        PlayItem(row.Id, row.Title);
    }

    private void OnPlayClick(object sender, RoutedEventArgs e)
        => PlayItem(_navigation!.ItemId, _detail?.Item.Name ?? _navigation!.Title, 0);

    private void OnResumeClick(object sender, RoutedEventArgs e)
        => PlayItem(_navigation!.ItemId, _detail?.Item.Name ?? _navigation!.Title,
            _detail?.Item.UserDataPlaybackPositionTicks ?? 0);

    private void PlayItem(string itemId, string title, long startTicks = 0)
    {
        App.MainWindow?.EnterPlayer(new PlaybackRequest
        {
            ServerId = _handle!.ServerId,
            ItemId = itemId,
            Title = title,
            StartPositionTicks = startTicks,
        });
    }

    private void OnBackClick(object sender, RoutedEventArgs e)
        => App.MainWindow?.NavigateShell(typeof(LibrariesPage));

    private void Fail(string title, string message)
    {
        StatusBar.Severity = InfoBarSeverity.Error;
        StatusBar.Title = title;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
        BusyRing.IsActive = false;
    }
}

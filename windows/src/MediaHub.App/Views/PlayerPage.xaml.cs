using MediaHub.App.Interop;
using MediaHub.Net;
using MediaHub.App.Services;
using MediaHub.Domain;
using MediaHub.Player;
using MediaHub.Providers;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.Foundation;
using Windows.Storage.Pickers;
using Windows.System;

namespace MediaHub.App.Views;

public sealed partial class PlayerPage : Page
{
    private readonly DispatcherTimer _hideTimer = new();
    private readonly DispatcherTimer _historyTimer = new();
    private readonly DispatcherTimer _boundsTimer = new();

    private PointerActivityWatcher? _pointerWatcher;
    private PlaybackRequest? _request;
    private PlaybackSession? _session;
    private bool _autoAdvanceArmed;
    private bool _scrubbing;
    private bool _controlsVisible = true;
    private bool _isFullScreen;
    private bool _suppressSeekEvent;
    private bool _suppressVolumeEvent;
    private bool _suppressTrackEvents;
    private bool _initialized;

    public PlayerPage()
    {
        InitializeComponent();

        SpeedCombo.ItemsSource = new[] { "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x" };
        SpeedCombo.SelectedIndex = 2;

        var settings = App.Services.Settings.Current;
        _suppressVolumeEvent = true;
        VolumeSlider.Value = settings.DefaultVolume;
        _suppressVolumeEvent = false;
        InfoToggle.IsChecked = settings.ShowDetailedMediaInfo;

        _hideTimer.Interval = TimeSpan.FromSeconds(3);
        _hideTimer.Tick += (_, _) => { _hideTimer.Stop(); HideControlsIfAllowed(); };

        _historyTimer.Interval = ProgressSyncPolicy.LocalSnapshotInterval;
        _historyTimer.Tick += (_, _) => OnProgressTick();

        // A short timer re-syncs the native surface bounds. Relying on layout events alone misses
        // DPI changes and presenter transitions (windowed <-> fullscreen), which do not raise them.
        _boundsTimer.Interval = TimeSpan.FromMilliseconds(250);
        _boundsTimer.Tick += (_, _) => UpdateSurfaceBounds();

        Loaded += OnLoaded;
        Unloaded += OnUnloaded;

        var host = App.Services.PlayerHost;
        host.SnapshotChanged += OnSnapshotChanged;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_initialized) return;
        _initialized = true;

        if (_request is null)
        {
            ShowStatus("未提供播放目标。");
            return;
        }

        TitleText.Text = _request.Title;
        // DisplayUrl, never the engine URL: for a server source the engine URL is a loopback bridge
        // address carrying a per-session token, which must not be shown or logged.
        SubtitleText.Text = _request.DisplayUrl;
        App.Services.Log.Info("player", "进入播放页：" + LogService.Redact(_request.Title));

        var host = App.Services.PlayerHost;

        // A stale tail from the previous item must not leak into this one's first snapshot.
        ApplySnapshot(host.Player?.Snapshot ?? PlayerSnapshot.Empty);

        // Reveal the surface before handing the file to the engine: the video output initialises
        // against a visible window, which avoids a first-frame stall on some D3D11 drivers.
        UpdateSurfaceBounds();
        host.ShowSurface();

        try
        {
            // Resolving a server stream also establishes the loopback relay, so the engine never
            // receives server credentials.
            _session = await App.Services.Playback.PrepareAsync(_request);
            _session.Changed += OnSessionChanged;
            SubtitleText.Text = _session.Active?.DisplayUrl ?? _request.DisplayUrl;
            await host.PlayAsync(_session.Active!);
        }
        catch (ProviderException ex)
        {
            ShowStatus("播放失败：" + ex.Message);
            App.Services.Log.Error("player", "播放启动失败", ex);
            return;
        }
        catch (Exception ex)
        {
            ShowStatus("播放失败：" + ex.Message);
            App.Services.Log.Error("player", "播放启动失败", ex);
            return;
        }

        UpdateSurfaceBounds();

        _pointerWatcher = new PointerActivityWatcher(host.WindowHandle, () => host.PhysicalBounds);
        _pointerWatcher.Activity += OnPointerActivity;

        _boundsTimer.Start();
        _historyTimer.Start();
        RestartHideTimer();
        RefreshTrackLists();

        // Focus the video area rather than leaving it wherever it landed. XAML focus falls on the
        // first focusable descendant, which here is a transport button - and a focused button eats
        // Space before the page sees it. The tooltip and the documentation both advertise Space as
        // play/pause, so with focus on 返回 the advertised shortcut instead left the player. Space
        // activating a focused button is correct behaviour; starting with a button focused is not.
        VideoHost.Focus(FocusState.Programmatic);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _request = e.Parameter as PlaybackRequest;
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        App.Services.PlayerHost.SnapshotChanged -= OnSnapshotChanged;
        if (_session is not null) _session.Changed -= OnSessionChanged;
        _pointerWatcher?.Dispose();
        _pointerWatcher = null;
        _boundsTimer.Stop();
        _historyTimer.Stop();
        _hideTimer.Stop();
        FlushFinalProgress();
        try { App.Services.PlayerHost.Player?.Pause(); } catch { /* engine may already be gone */ }
        try { App.Services.PlayerHost.HideSurface(); } catch { /* surface may already be gone */ }
    }

    /// <summary>
    /// Sends the last progress sample and releases the relay session.
    /// </summary>
    /// <remarks>
    /// The final report has to be issued before the session is torn down, and the relay must be
    /// disposed even when reporting fails, otherwise a cancelled playback leaves a listener and an
    /// upstream connection pool alive for the rest of the process lifetime.
    /// </remarks>
    private void FlushFinalProgress()
    {
        PlaybackSession? session = _session;
        _session = null;
        if (session is null) return;

        try
        {
            PlayerSnapshot snapshot = App.Services.PlayerHost.Player?.Snapshot ?? PlayerSnapshot.Empty;
            if (snapshot.DurationSeconds > 0)
            {
                long position = (long)(snapshot.PositionSeconds * TimeSpan.TicksPerSecond);
                long duration = (long)(snapshot.DurationSeconds * TimeSpan.TicksPerSecond);
                session.LastPositionTicks = position;
                session.LastDurationTicks = duration;
            }
        }
        catch { }

        // The coordinator owns the stop report and the relay lifetime, so leaving the page is a
        // session stop rather than a collection of things this page has to remember to undo. It is
        // bounded so a hanging report cannot delay the navigation that is already in progress.
        _ = Task.Run(async () =>
        {
            try
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(6));
                await App.Services.Playback.StopAsync(PlaybackStopReasons.UserExited).WaitAsync(timeout.Token);
            }
            catch { }
        });
    }

    /// <summary>Mirrors the session's stage into the status line and drives auto-advance.</summary>
    private void OnSessionChanged(PlaybackSession session)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() => OnSessionChanged(session));
            return;
        }
        if (session.Stage == PlaybackStage.Error && session.Error is { Length: > 0 } error)
        {
            ShowStatus("播放失败：" + error);
        }
    }

    // ---------------------------------------------------------------- surface geometry

    private void UpdateSurfaceBounds()
    {
        var host = App.Services.PlayerHost;
        if (host.Surface is null) return;

        var windowRoot = (App.MainWindow as MainWindow)?.Content as UIElement;
        if (windowRoot is null) return;

        try
        {
            GeneralTransform transform = VideoHost.TransformToVisual(windowRoot);
            Point origin = transform.TransformPoint(new Point(0, 0));
            // ActualSize is a System.Numerics.Vector2 in WinUI 3, not a Windows.Foundation.Size.
            System.Numerics.Vector2 size = VideoHost.ActualSize;
            host.SetSurfaceBounds(origin.X, origin.Y, size.X, size.Y);
            TraceLayout(origin, size, windowRoot);
        }
        catch
        {
            // The visual tree can be mid-teardown; the next tick will retry.
        }
    }

    private string _lastLayoutTrace = "";

    /// <summary>
    /// Records the computed layout once per distinct value.
    /// </summary>
    /// <remarks>
    /// The video rectangle is produced by XAML layout but consumed by a native child window, so a
    /// mismatch between the two is invisible in the UI and cannot be diagnosed from a screenshot.
    /// This trace makes the boundary explicit for manual verification runs.
    /// </remarks>
    private void TraceLayout(Point origin, System.Numerics.Vector2 size, UIElement windowRoot)
    {
        var rootSize = (windowRoot as FrameworkElement)?.ActualSize ?? default;
        string trace = $"layout: root={rootSize.X:0}x{rootSize.Y:0}"
            + $" video=({origin.X:0},{origin.Y:0}) {size.X:0}x{size.Y:0}"
            + $" topBar={TopBar.ActualHeight:0} bottomBar={BottomBar.ActualHeight:0}"
            + $" topVis={TopBar.Visibility} bottomVis={BottomBar.Visibility}"
            + $" panel={PanelColumn.ActualWidth:0}";
        if (trace == _lastLayoutTrace) return;
        _lastLayoutTrace = trace;
        Services.StartupTrace.Write(trace);
    }

    // ---------------------------------------------------------------- snapshot -> UI

    private void OnSnapshotChanged(object? sender, PlayerSnapshot snapshot)
    {
        // Snapshots are raised from the engine's event thread.
        DispatcherQueue.TryEnqueue(() => ApplySnapshot(snapshot));
    }

    private void ApplySnapshot(PlayerSnapshot s)
    {
        if (s.State == TransportState.Error)
        {
            ShowStatus(s.Error ?? "播放失败。");
        }
        else if (s.State == TransportState.Ended)
        {
            LoadingRing.IsActive = false;
            StatusPanel.Visibility = Visibility.Collapsed;
        }
        else if (s.DurationSeconds <= 0 && s.State == TransportState.Loading)
        {
            LoadingRing.IsActive = true;
            StatusPanel.Visibility = Visibility.Visible;
            StatusText.Text = "正在打开媒体…";
        }
        else
        {
            LoadingRing.IsActive = false;
            StatusPanel.Visibility = Visibility.Collapsed;
        }

        PositionText.Text = Format(s.PositionSeconds);
        DurationText.Text = Format(s.DurationSeconds);

        if (!_scrubbing)
        {
            _suppressSeekEvent = true;
            SeekSlider.Value = s.DurationSeconds > 0 ? Math.Clamp(s.PositionSeconds / s.DurationSeconds, 0, 1) : 0;
            _suppressSeekEvent = false;
        }

        PlayPauseIcon.Glyph = s.State == TransportState.Playing ? "\uE769" : "\uE768";
        VolumeIcon.Glyph = s.Muted || s.Volume <= 0.5 ? "\uE74F" : "\uE767";

        if (Math.Abs(VolumeSlider.Value - s.Volume) > 0.5 && !_suppressVolumeEvent)
        {
            _suppressVolumeEvent = true;
            VolumeSlider.Value = Math.Clamp(s.Volume, 0, 200);
            _suppressVolumeEvent = false;
        }

        TechnicalSummary.Text = BuildTechnicalSummary(s);
        if (InfoToggle.IsChecked == true) PopulateInfo(s);

        if (s.AudioTracks.Count != AudioList.Items.Count || s.SubtitleTracks.Count != SubtitleList.Items.Count)
        {
            RefreshTrackLists();
        }
    }

    private static string BuildTechnicalSummary(PlayerSnapshot s)
    {
        var parts = new List<string>();
        if (s.Width is { } w && s.Height is { } h) parts.Add($"{w}×{h}");
        if (s.Technical.VideoCodec is { } vc) parts.Add(vc);
        if (s.Technical.HwDecCurrent is { } hw) parts.Add($"hwdec={hw}");
        if (s.Technical.ColorTransfer is { } ct) parts.Add(ct);
        if (s.Technical.AudioCodec is { } ac) parts.Add(ac);
        if (s.Fps is { } fps) parts.Add($"{fps:0.###}fps");
        if (s.Technical.FrameDrops is > 0 and var drops) parts.Add($"掉帧 {drops}");
        return parts.Count == 0 ? "" : string.Join("  ·  ", parts);
    }

    private void PopulateInfo(PlayerSnapshot s)
    {
        var t = s.Technical;
        var rows = new List<InfoRow>
        {
            new("文件名", s.FileName ?? Unknown),
            new("标题", s.MediaTitle ?? Unknown),
            new("状态", Translate(s.State)),
            new("分辨率", s.Width is { } w && s.Height is { } h ? $"{w} × {h}" : Unknown),
            new("帧率", s.Fps is { } f ? $"{f:0.###} fps" : Unknown),
            new("渲染帧率", t.RenderFps is { } rf ? $"{rf:0.##} fps" : Unknown),
            new("视频编码", t.VideoCodec ?? Unknown),
            new("像素格式", t.PixelFormat ?? Unknown),
            new("硬件解码", t.HwDecCurrent ?? Unknown),
            new("色彩原色", t.ColorPrimaries ?? Unknown),
            new("传输特性", t.ColorTransfer ?? Unknown),
            new("色彩矩阵", t.ColorMatrix ?? Unknown),
            new("色彩范围", t.ColorLevels ?? Unknown),
            new("信号峰值", t.SignalPeak is { } sp ? $"{sp:0.###}" : Unknown),
            new("HDR", t.IsHdr ? "是（" + t.ColorTransfer + "）" : "否"),
            new("音频编码", t.AudioCodec ?? Unknown),
            new("声道数", t.AudioChannels?.ToString() ?? Unknown),
            new("采样率", t.AudioSampleRate is { } sr ? $"{sr} Hz" : Unknown),
            new("容器", t.Container ?? Unknown),
            new("码率", t.BitRate is { } br ? $"{br / 1000.0:0} kbps" : Unknown),
            new("文件大小", t.FileSizeBytes is { } fs ? $"{fs / (double)(1L << 20):0.0} MB" : Unknown),
            new("视频输出", t.VideoOutput ?? Unknown),
            new("GPU 上下文", t.GpuContext ?? Unknown),
            new("丢帧", t.FrameDrops?.ToString() ?? Unknown),
            new("播放速度", $"{s.Speed:0.##}x"),
        };
        InfoList.ItemsSource = rows;
    }

    private const string Unknown = "未知";

    private static string Translate(TransportState state) => state switch
    {
        TransportState.Idle => "空闲",
        TransportState.Loading => "缓冲中",
        TransportState.Playing => "播放中",
        TransportState.Paused => "已暂停",
        TransportState.Ended => "已结束",
        TransportState.Error => "错误",
        _ => state.ToString(),
    };

    private static string Format(double seconds)
    {
        if (double.IsNaN(seconds) || seconds < 0) seconds = 0;
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    // ---------------------------------------------------------------- commands

    private void OnPlayPauseClick(object sender, RoutedEventArgs e)
    {
        App.Services.PlayerHost.Player?.TogglePause();
        ShowControls();
    }

    private void OnSeekBackClick(object sender, RoutedEventArgs e)
    {
        App.Services.PlayerHost.Player?.SeekRelative(-App.Services.Settings.Current.SeekStepSeconds);
        ShowControls();
    }

    private void OnSeekForwardClick(object sender, RoutedEventArgs e)
    {
        App.Services.PlayerHost.Player?.SeekRelative(App.Services.Settings.Current.SeekStepSeconds);
        ShowControls();
    }

    private void OnMuteClick(object sender, RoutedEventArgs e)
    {
        var player = App.Services.PlayerHost.Player;
        if (player is null) return;
        player.SetMuted(!(player.Snapshot.Muted));
        ShowControls();
    }

    private void OnVolumeValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_suppressVolumeEvent) return;
        App.Services.PlayerHost.Player?.SetVolume(e.NewValue);
    }

    private void OnSeekValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_suppressSeekEvent) return;
        var player = App.Services.PlayerHost.Player;
        if (player is null) return;

        double duration = player.Snapshot.DurationSeconds;
        if (duration <= 0) return;

        if (_scrubbing) PositionText.Text = Format(e.NewValue * duration);
        else player.SeekAbsolute(e.NewValue * duration);
    }

    private void OnSpeedComboChanged(object sender, SelectionChangedEventArgs e)
    {
        var player = App.Services.PlayerHost.Player;
        if (player is null || SpeedCombo.SelectedItem is not string label) return;
        string numeric = label.TrimEnd('x');
        if (double.TryParse(numeric, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double speed))
            player.SetSpeed(speed);
    }

    private void OnBackClick(object sender, RoutedEventArgs e) => ExitToShell();

    private void ExitToShell()
    {
        if (_isFullScreen) ToggleFullScreen();
        (App.MainWindow as MainWindow)?.ExitPlayer();
    }

    private void OnFullscreenClick(object sender, RoutedEventArgs e) => ToggleFullScreen();

    /// <summary>
    /// Double-clicking the video area toggles full screen.
    /// </summary>
    /// <remarks>
    /// Handled on the container rather than on the video surface: the video is a child HWND owned by
    /// the engine, so it does not participate in XAML input at all and a handler attached to it would
    /// never fire.
    /// </remarks>
    private void OnVideoHostDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        ToggleFullScreen();
        e.Handled = true;
    }

    /// <summary>Toggles full screen. Public so the shell menu can reach it.</summary>
    public void ToggleFullScreen()
    {
        var window = App.MainWindow as MainWindow;
        if (window is null) return;

        var appWindow = window.AppWindow;
        if (!_isFullScreen)
        {
            appWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
            _isFullScreen = true;
            FullscreenIcon.Glyph = "\uE73F";
        }
        else
        {
            appWindow.SetPresenter(AppWindowPresenterKind.Overlapped);
            _isFullScreen = false;
            FullscreenIcon.Glyph = "\uE740";
        }

        // Presenter changes do not raise a layout pass on the page; re-place the surface explicitly.
        DispatcherQueue.TryEnqueue(() =>
        {
            UpdateSurfaceBounds();
            ShowControls();
        });
    }

    private void OnInfoToggleClick(object sender, RoutedEventArgs e)
    {
        if (InfoToggle.IsChecked == true)
        {
            ShowPanel("媒体信息", showInfo: true, showAudio: false, showSubtitle: false);
            PopulateInfo(App.Services.PlayerHost.Player?.Snapshot ?? PlayerSnapshot.Empty);
        }
        else
        {
            HidePanel();
        }
    }

    private void OnAudioPanelClick(object sender, RoutedEventArgs e)
    {
        RefreshTrackLists();
        ShowPanel("音轨", showInfo: false, showAudio: true, showSubtitle: false);
    }

    private void OnSubtitlePanelClick(object sender, RoutedEventArgs e)
    {
        RefreshTrackLists();
        ShowPanel("字幕", showInfo: false, showAudio: false, showSubtitle: true);
    }

    private void ShowPanel(string header, bool showInfo, bool showAudio, bool showSubtitle)
    {
        PanelHeader.Text = header;
        InfoSection.Visibility = showInfo ? Visibility.Visible : Visibility.Collapsed;
        AudioSection.Visibility = showAudio ? Visibility.Visible : Visibility.Collapsed;
        SubtitleSection.Visibility = showSubtitle ? Visibility.Visible : Visibility.Collapsed;
        PanelColumn.Width = new GridLength(340);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    private void HidePanel()
    {
        PanelColumn.Width = new GridLength(0);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    private void RefreshTrackLists()
    {
        var snapshot = App.Services.PlayerHost.Player?.Snapshot;
        if (snapshot is null) return;

        _suppressTrackEvents = true;
        try
        {
            var audio = snapshot.AudioTracks
                .Select(t => new TrackRow(
                    t.Id,
                    $"{(string.IsNullOrWhiteSpace(t.Language) ? "未知语言" : t.Language)} · {(t.Codec ?? "未知编码")}" +
                    (t.Channels is { } c ? $" · {c}ch" : "") +
                    (string.IsNullOrWhiteSpace(t.Title) ? "" : $" · {t.Title}"),
                    t.Selected))
                .ToList();

            AudioList.ItemsSource = audio;
            AudioList.SelectedIndex = audio.FindIndex(a => a.Selected);

            var subs = new List<TrackRow> { new(-1, "关闭字幕", snapshot.SelectedSubtitleId is null) };
            subs.AddRange(snapshot.SubtitleTracks.Select(t => new TrackRow(
                t.Id,
                $"{(string.IsNullOrWhiteSpace(t.Language) ? "未知语言" : t.Language)} · {(t.Codec ?? "未知编码")}" +
                (t.IsExternal ? " · 外挂" : "") +
                (t.IsForced ? " · 强制" : "") +
                (string.IsNullOrWhiteSpace(t.Title) ? "" : $" · {t.Title}"),
                t.Selected)));

            SubtitleList.ItemsSource = subs;
            SubtitleList.SelectedIndex = subs.FindIndex(s => s.Selected);

            SubtitleHint.Text = snapshot.SubtitleTracks.Count == 0
                ? "当前媒体没有可用的字幕轨道。可用“加载外挂字幕…”选择 SRT / ASS / SSA / VTT 文件。"
                : $"共 {snapshot.SubtitleTracks.Count} 条字幕轨道。";
        }
        finally
        {
            _suppressTrackEvents = false;
        }
    }

    private void OnAudioSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressTrackEvents || AudioList.SelectedItem is not TrackRow row) return;
        App.Services.PlayerHost.Player?.SelectAudioTrack(row.Id);
    }

    private void OnSubtitleSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressTrackEvents || SubtitleList.SelectedItem is not TrackRow row) return;
        App.Services.PlayerHost.Player?.SelectSubtitleTrack(row.Id < 0 ? null : row.Id);
    }

    private async void OnLoadSubtitleClick(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        foreach (string ext in LocalLibraryService.SupportedSubtitleExtensions) picker.FileTypeFilter.Add(ext);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, (App.MainWindow as MainWindow)!.WindowHandle);

        var file = await picker.PickSingleFileAsync();
        if (file is null) return;

        try
        {
            App.Services.PlayerHost.Player?.AddExternalSubtitle(file.Path);
            SubtitleHint.Text = $"已加载：{System.IO.Path.GetFileName(file.Path)}";
        }
        catch (Exception ex)
        {
            SubtitleHint.Text = $"加载失败：{ex.Message}";
        }
    }

    private void OnReloadSubtitleClick(object sender, RoutedEventArgs e)
    {
        if (_request?.LocalPath is not { } path) return;
        int added = 0;
        foreach (string sub in LocalLibraryService.FindSiblingSubtitles(path))
        {
            try { App.Services.PlayerHost.Player?.AddExternalSubtitle(sub); added++; }
            catch { /* a single unreadable subtitle file must not abort the rest */ }
        }
        SubtitleHint.Text = added == 0 ? "未在同目录找到匹配的外挂字幕文件。" : $"已重新加载 {added} 个字幕文件。";
    }

    // ---------------------------------------------------------------- control visibility

    private void OnPointerActivity() => DispatcherQueue.TryEnqueue(ShowControls);

    private void ShowControls()
    {
        if (!_controlsVisible)
        {
            _controlsVisible = true;
            TopBar.Visibility = Visibility.Visible;
            BottomBar.Visibility = Visibility.Visible;
            DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
        }
        RestartHideTimer();
    }

    private void HideControlsIfAllowed()
    {
        var player = App.Services.PlayerHost.Player;
        if (player is null) return;
        if (!App.Services.Settings.Current.AutoHideControls) return;
        if (player.Snapshot.State != TransportState.Playing) return;
        if (_isFullScreen is false && TopBar.Visibility == Visibility.Collapsed) return;

        _controlsVisible = false;
        TopBar.Visibility = Visibility.Collapsed;
        BottomBar.Visibility = Visibility.Collapsed;
        UpdateSurfaceBounds();
    }

    private void RestartHideTimer()
    {
        _hideTimer.Stop();
        if (App.Services.Settings.Current.AutoHideControls) _hideTimer.Start();
    }

    /// <summary>
    /// Local snapshot tier plus the remote reporting tier.
    /// </summary>
    /// <remarks>
    /// Local playback is recorded locally and never reported to a server. Server playback is reported
    /// by the coordinator, which owns the provider's throttling interval and session lifecycle; the
    /// local history is not used for it because the server is the authority on resume position.
    /// </remarks>
    private void OnProgressTick()
    {
        PlaybackRequest? request = _request;
        PlayerSnapshot? snapshot = App.Services.PlayerHost.Player?.Snapshot;
        if (request is null || snapshot is null || snapshot.DurationSeconds <= 0) return;

        if (request.LocalPath is { } localPath)
        {
            App.Services.PlaybackHistory.Record(
                localPath,
                request.Title,
                (long)(snapshot.PositionSeconds * TimeSpan.TicksPerSecond),
                (long)(snapshot.DurationSeconds * TimeSpan.TicksPerSecond));
            return;
        }

        PlaybackSession? session = _session;
        if (session is null) return;

        var sample = new PlayerProgressSample(
            (long)(snapshot.PositionSeconds * TimeSpan.TicksPerSecond),
            (long)(snapshot.DurationSeconds * TimeSpan.TicksPerSecond),
            snapshot.State == TransportState.Paused,
            snapshot.Muted);

        // Start is sent exactly once, the first time the engine reports real playback. Sending it
        // when the page opens would tell the server playback had begun before it had, and sending it
        // on every tick would create a session storm.
        if (!session.StartReported && snapshot.State == TransportState.Playing)
        {
            session.TryAdvance(PlaybackStage.Playing);
            _ = App.Services.Playback.NotifyStartedAsync(session, sample);
        }
        else
        {
            session.TryAdvance(snapshot.State switch
            {
                TransportState.Playing => PlaybackStage.Playing,
                TransportState.Paused => PlaybackStage.Paused,
                TransportState.Loading => PlaybackStage.Buffering,
                TransportState.Ended => PlaybackStage.Ended,
                TransportState.Error => PlaybackStage.Error,
                _ => session.Stage,
            });
        }

        _ = App.Services.Playback.OnProgressAsync(session, sample, force: false);

        if (snapshot.EofReached || snapshot.State == TransportState.Ended) _ = OnPlaybackFinishedAsync();
    }

    /// <summary>
    /// End-of-item handling: mark the item watched and, when enabled, continue to the next episode.
    /// </summary>
    /// <remarks>
    /// Guarded by a one-shot flag. The engine reports EofReached on every snapshot once it is
    /// reached, so without the guard this would advance through the whole series in seconds.
    /// </remarks>
    private async Task OnPlaybackFinishedAsync()
    {
        if (_autoAdvanceArmed) return;
        _autoAdvanceArmed = true;

        PlaybackSession? session = _session;
        if (session is null) return;
        session.TryAdvance(PlaybackStage.Ended);

        if (!App.Services.Settings.Current.AutoPlayNext) return;
        if (_request?.ServerId is not { } serverId || _request.SeriesId is not { } seriesId || _request.ItemId is not { } itemId)
            return;

        try
        {
            ProviderHandle? handle = App.Services.Servers.FindHandle(serverId);
            if (handle?.Library is null) return;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            Episode? next = await handle.Library.GetNextEpisodeAsync(seriesId, itemId, timeout.Token);
            if (next is null) return;

            App.Services.Log.Info("playback", "自动连播下一集：" + LogService.Redact(next.Name));
            DispatcherQueue.TryEnqueue(() =>
            {
                if (App.MainWindow is not MainWindow shell) return;
                shell.EnterPlayer(new PlaybackRequest
                {
                    ServerId = serverId,
                    ItemId = next.Id,
                    Title = next.Name,
                    SeriesId = next.SeriesId,
                    SeasonNumber = next.SeasonNumber,
                    EpisodeNumber = next.EpisodeNumber,
                });
            });
        }
        catch (OperationCanceledException) { }
        catch (ProviderException ex)
        {
            // Auto-advance is a convenience; failing it must leave the user on the finished item.
            App.Services.Log.Debug("playback", "自动连播查询失败：" + ex.Code);
        }
    }

    private void ShowStatus(string message)
    {
        LoadingRing.IsActive = false;
        StatusPanel.Visibility = Visibility.Visible;
        StatusText.Text = message;
        App.Services.PlayerHost.HideSurface();
    }

    // ---------------------------------------------------------------- keyboard

    protected override void OnKeyDown(KeyRoutedEventArgs e)
    {
        var player = App.Services.PlayerHost.Player;
        switch (e.Key)
        {
            case VirtualKey.Escape:
                if (_isFullScreen) ToggleFullScreen(); else ExitToShell();
                e.Handled = true;
                break;
            case VirtualKey.F11:
            case VirtualKey.F:
                ToggleFullScreen();
                e.Handled = true;
                break;
            case VirtualKey.Space:
                player?.TogglePause();
                e.Handled = true;
                break;
            case VirtualKey.Left:
                player?.SeekRelative(-App.Services.Settings.Current.SeekStepSeconds);
                e.Handled = true;
                break;
            case VirtualKey.Right:
                player?.SeekRelative(App.Services.Settings.Current.SeekStepSeconds);
                e.Handled = true;
                break;
            case VirtualKey.Up:
                player?.SetVolume((player.Snapshot.Volume) + 5);
                e.Handled = true;
                break;
            case VirtualKey.Down:
                player?.SetVolume((player.Snapshot.Volume) - 5);
                e.Handled = true;
                break;
            case VirtualKey.M:
                if (player is not null) player.SetMuted(!player.Snapshot.Muted);
                e.Handled = true;
                break;
            case VirtualKey.I:
                InfoToggle.IsChecked = InfoToggle.IsChecked != true;
                OnInfoToggleClick(InfoToggle, new RoutedEventArgs());
                e.Handled = true;
                break;
        }
        if (!e.Handled) base.OnKeyDown(e);
        else ShowControls();
    }

    // ---------------------------------------------------------------- slider scrubbing

    protected override void OnPointerPressed(PointerRoutedEventArgs e)
    {
        // Slider scrubbing must not commit a seek on every intermediate value; the seek is issued
        // once on release, which keeps high-frequency drags from flooding the engine.
        if (e.OriginalSource is FrameworkElement fe && IsWithinSlider(fe))
        {
            _scrubbing = true;
        }
        base.OnPointerPressed(e);
    }

    protected override void OnPointerReleased(PointerRoutedEventArgs e)
    {
        if (_scrubbing)
        {
            _scrubbing = false;
            var player = App.Services.PlayerHost.Player;
            if (player is not null && player.Snapshot.DurationSeconds > 0)
                player.SeekAbsolute(SeekSlider.Value * player.Snapshot.DurationSeconds);
        }
        base.OnPointerReleased(e);
    }

    private bool IsWithinSlider(DependencyObject? source)
    {
        while (source is not null)
        {
            if (ReferenceEquals(source, SeekSlider)) return true;
            source = VisualTreeHelper.GetParent(source);
        }
        return false;
    }

    private sealed record TrackRow(long Id, string Label, bool Selected)
    {
        public override string ToString() => Label;
    }

    private sealed record InfoRow(string Label, string Value);
}

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
    /// <summary>Set while a switch is in flight so the outgoing item's dying snapshots are ignored.</summary>
    private bool _suppressFailureHandling;

    /// <summary>One-shot per item: end-of-stream and errors must not each advance the queue twice.</summary>
    private bool _advanceHandled;

    /// <summary>Consecutive items that failed to start. Reset once anything reaches Playing.</summary>
    private int _consecutiveFailures;

    /// <summary>How many unplayable items a queue may skip before the player stops trying.</summary>
    private const int MaxConsecutiveFailures = 3;

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

        var host = App.Services.PlayerHost;

        // A stale tail from the previous item must not leak into this one's first snapshot.
        ApplySnapshot(host.Player?.Snapshot ?? PlayerSnapshot.Empty);

        // Reveal the surface before handing the file to the engine: the video output initialises
        // against a visible window, which avoids a first-frame stall on some D3D11 drivers.
        UpdateSurfaceBounds();
        host.ShowSurface();

        _pointerWatcher = new PointerActivityWatcher(host.WindowHandle, () => host.PhysicalBounds);
        _pointerWatcher.Activity += OnPointerActivity;

        _boundsTimer.Start();
        _historyTimer.Start();
        RestartHideTimer();

        // Focus the video area rather than leaving it wherever it landed. XAML focus falls on the
        // first focusable descendant, which here is a transport button - and a focused button eats
        // Space before the page sees it. The tooltip and the documentation both advertise Space as
        // play/pause, so with focus on 返回 the advertised shortcut instead left the player. Space
        // activating a focused button is correct behaviour; starting with a button focused is not.
        VideoHost.Focus(FocusState.Programmatic);

        await StartRequestAsync(_request);
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
        // The coordinator owns the stop report and the relay lifetime, so leaving the page is a
        // session stop rather than a collection of things this page has to remember to undo. It is
        // bounded so a hanging report cannot delay the navigation that is already in progress.
        _ = StopCurrentAsync(PlaybackStopReasons.UserExited);
        try { App.Services.PlayerHost.HideSurface(); } catch { /* surface may already be gone */ }
    }

    // ---------------------------------------------------------------- playback lifecycle

    /// <summary>Resolves and starts one request, replacing whatever this page was playing.</summary>
    /// <returns>True when the engine accepted the media.</returns>
    /// <remarks>
    /// Failures here are handled, not thrown: a broken file must behave the same whether the user
    /// picked it as the first item or as the fifth, and both paths end up in
    /// <see cref="HandlePlaybackFailure"/> so the skip policy is applied in exactly one place.
    /// </remarks>
    private async Task<bool> StartRequestAsync(PlaybackRequest request)
    {
        _request = request;
        _advanceHandled = false;
        TitleText.Text = request.Title;
        // DisplayUrl, never the engine URL: for a server source the engine URL is a loopback bridge
        // address carrying a per-session token, which must not be shown or logged.
        SubtitleText.Text = request.DisplayUrl;
        UpdateQueueView();
        App.Services.Log.Info("player", "进入播放页：" + LogService.Redact(request.Title));
        Services.StartupTrace.Write("player:start " + request.Title + " local=" + (request.LocalPath is not null));

        _suppressFailureHandling = true;
        try
        {
            // Resolving a server stream also establishes the loopback relay, so the engine never
            // receives server credentials.
            _session = await App.Services.Playback.PrepareAsync(request);
            _session.Changed += OnSessionChanged;
            SubtitleText.Text = _session.Active?.DisplayUrl ?? request.DisplayUrl;
            await App.Services.PlayerHost.PlayAsync(_session.Active!);
        }
        catch (Exception ex)
        {
            _suppressFailureHandling = false;
            App.Services.Log.Error("player", "播放启动失败", ex);
            HandlePlaybackFailure(ex.Message, requireActiveSession: false);
            return false;
        }

        _suppressFailureHandling = false;
        // ShowStatus hid the surface when the previous item failed; a new item starts clean.
        App.Services.PlayerHost.ShowSurface();
        UpdateSurfaceBounds();
        return true;
    }

    /// <summary>Stops the current session, reporting its final position, and releases the relay.</summary>
    private async Task StopCurrentAsync(string reason)
    {
        PlaybackSession? session = _session;
        _session = null;
        if (session is not null)
        {
            session.Changed -= OnSessionChanged;
            CaptureLastPosition(session);
        }

        try { App.Services.PlayerHost.Player?.Pause(); } catch { /* engine may already be gone */ }

        try
        {
            // Bounded: a hanging report must not hold up the item the user already asked for.
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(6));
            await App.Services.Playback.StopAsync(reason).WaitAsync(timeout.Token);
        }
        catch { }
    }

    /// <summary>Copies the engine's current position onto the session so the stop report carries it.</summary>
    private static void CaptureLastPosition(PlaybackSession session)
    {
        try
        {
            PlayerSnapshot snapshot = App.Services.PlayerHost.Player?.Snapshot ?? PlayerSnapshot.Empty;
            if (snapshot.DurationSeconds <= 0) return;
            session.LastPositionTicks = (long)(snapshot.PositionSeconds * TimeSpan.TicksPerSecond);
            session.LastDurationTicks = (long)(snapshot.DurationSeconds * TimeSpan.TicksPerSecond);
        }
        catch { }
    }

    /// <summary>Replaces what is playing with another queue entry, without leaving the page.</summary>
    /// <remarks>
    /// Switching in place, rather than navigating to a fresh page, is what makes "the previous
    /// session's relay and progress report are released before the next item starts" a fact instead
    /// of a race: the old session is stopped, reported and its relay disposed while the new one is
    /// being resolved, and the coordinator refuses any write that belongs to the superseded
    /// generation. The suppression flag keeps the old item's dying snapshots from being read as the
    /// new item's failure.
    /// </remarks>
    private async Task SwitchToAsync(PlaybackRequest request, string stopReason)
    {
        Services.StartupTrace.Write("queue:switch reason=" + stopReason + " title=" + request.Title);
        _suppressFailureHandling = true;
        try
        {
            await StopCurrentAsync(stopReason);
            await StartRequestAsync(request);
        }
        finally
        {
            _suppressFailureHandling = false;
        }
    }

    /// <summary>Says one item failed, then moves on under a bounded skip policy.</summary>
    /// <remarks>
    /// Skip-on-failure is capped. Without a cap, a queue of unplayable files would be walked through
    /// in a second or two with each message visible for a single frame - indistinguishable from a
    /// crash, and leaving nothing on screen to explain it. At the cap the player stays put and keeps
    /// the last error visible.
    /// </remarks>
    private void HandlePlaybackFailure(string message, bool requireActiveSession = true)
    {
        if (_suppressFailureHandling || _advanceHandled) return;
        if (requireActiveSession && _session is null) return;

        _advanceHandled = true;
        _consecutiveFailures++;

        ShowStatus("播放失败：" + message);
        App.Services.Log.Error("player", "播放失败：" + message);
        Services.StartupTrace.Write("queue:item-failed " + message.Replace('\n', ' ').Replace('\r', ' '));

        if (!App.Services.Settings.Current.AutoPlayNext)
        {
            Services.StartupTrace.Write("queue:skip-disabled");
            return;
        }

        if (_consecutiveFailures >= MaxConsecutiveFailures)
        {
            ShowStatus("播放失败：" + message
                + "（已连续跳过 " + _consecutiveFailures + " 个无法播放的项目，停止继续跳过）");
            Services.StartupTrace.Write("queue:skip-capped");
            return;
        }

        PlaybackQueue queue = App.Services.Queue;
        if (queue.NextItem is null)
        {
            Services.StartupTrace.Write("queue:no-next-item");
            return;
        }

        if (queue.MoveBy(1) is { } next) _ = SwitchToAsync(next, PlaybackStopReasons.Replaced);
    }

    /// <summary>Mirrors the session's stage into the status line.</summary>
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
            // One place decides what a failure means, whether it arrived from the engine or from
            // resolving the request: report it, then apply the bounded skip policy.
            HandlePlaybackFailure(s.Error ?? "播放失败。");
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
            // Anything that reaches normal transport proves the item is playable, which is what the
            // skip cap counts.
            if (s.State == TransportState.Playing) _consecutiveFailures = 0;
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

        // Compared against the tracks the list was built FROM, not against the number of rows it
        // holds. The subtitle list carries an extra "关闭字幕" row, so comparing it to
        // SubtitleTracks.Count was never equal - and this runs on every sampling tick (about ten
        // times a second), so the list was torn down and rebuilt continuously. On an audio-only file,
        // where there are no subtitle tracks at all, it flickered every single tick.
        if (s.AudioTracks.Count != _renderedAudioCount || s.SubtitleTracks.Count != _renderedSubtitleCount)
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
            ShowPanel(PanelKind.Info, "媒体信息");
            PopulateInfo(App.Services.PlayerHost.Player?.Snapshot ?? PlayerSnapshot.Empty);
        }
        else if (_openPanel == PanelKind.Info)
        {
            // Only close the panel if the info panel is the one showing; unticking the info toggle
            // must not dismiss an audio or subtitle list the user just opened.
            HidePanel();
        }
    }

    // The transport buttons toggle. Before this they only ever opened the panel, and the sole way to
    // close it was the separate info toggle - so once the audio list was open there was no control on
    // the page that would put it away again.
    private void OnAudioPanelClick(object sender, RoutedEventArgs e)
    {
        if (_openPanel == PanelKind.Audio) { HidePanel(); return; }
        RefreshTrackLists();
        ShowPanel(PanelKind.Audio, "音轨");
    }

    private void OnSubtitlePanelClick(object sender, RoutedEventArgs e)
    {
        if (_openPanel == PanelKind.Subtitle) { HidePanel(); return; }
        RefreshTrackLists();
        ShowPanel(PanelKind.Subtitle, "字幕");
    }

    /// <summary>Which side panel is showing, or <see cref="PanelKind.None"/>.</summary>
    private enum PanelKind { None, Info, Audio, Subtitle, Queue }

    private PanelKind _openPanel = PanelKind.None;

    private void ShowPanel(PanelKind kind, string header)
    {
        _openPanel = kind;
        PanelHeader.Text = header;
        InfoSection.Visibility = kind == PanelKind.Info ? Visibility.Visible : Visibility.Collapsed;
        AudioSection.Visibility = kind == PanelKind.Audio ? Visibility.Visible : Visibility.Collapsed;
        SubtitleSection.Visibility = kind == PanelKind.Subtitle ? Visibility.Visible : Visibility.Collapsed;
        QueueSection.Visibility = kind == PanelKind.Queue ? Visibility.Visible : Visibility.Collapsed;
        PanelColumn.Width = new GridLength(340);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    private void HidePanel()
    {
        _openPanel = PanelKind.None;
        PanelColumn.Width = new GridLength(0);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    /// <summary>Track counts the lists were last built from, used to avoid rebuilding them per tick.</summary>
    private int _renderedAudioCount = -1;
    private int _renderedSubtitleCount = -1;

    private void RefreshTrackLists()
    {
        var snapshot = App.Services.PlayerHost.Player?.Snapshot;
        if (snapshot is null) return;

        _renderedAudioCount = snapshot.AudioTracks.Count;
        _renderedSubtitleCount = snapshot.SubtitleTracks.Count;

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

            SubtitleHint.Text = ComposeSubtitleHint(snapshot.SubtitleTracks.Count);
        }
        finally
        {
            _suppressTrackEvents = false;
        }
    }

    // 一次性结果（已加载 / 加载失败）必须活得比一帧久：本方法由 10 Hz 快照驱动，
    // 如果每次刷新都无条件重写提示文本，用户永远看不到"加载了哪个文件"，失败原因更是瞬间消失。
    private static readonly TimeSpan SubtitleNoticeLifetime = TimeSpan.FromSeconds(8);
    private string? _subtitleNotice;
    private DateTime _subtitleNoticeAt;

    private void SetSubtitleNotice(string text)
    {
        _subtitleNotice = text;
        _subtitleNoticeAt = DateTime.UtcNow;
        SubtitleHint.Text = ComposeSubtitleHint(_renderedSubtitleCount);
    }

    private string ComposeSubtitleHint(int trackCount)
    {
        string baseline = trackCount == 0
            ? "当前媒体没有可用的字幕轨道。可用“加载外挂字幕…”选择 SRT / ASS / SSA / VTT 文件。"
            : $"共 {trackCount} 条字幕轨道。";
        if (_subtitleNotice is { Length: > 0 } notice && DateTime.UtcNow - _subtitleNoticeAt < SubtitleNoticeLifetime)
        {
            return trackCount == 0 ? notice : notice + " " + baseline;
        }
        return baseline;
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
        string? file = await FilePickerService.PickFileAsync(LocalLibraryService.SupportedSubtitleExtensions);
        if (file is null) return;

        try
        {
            App.Services.PlayerHost.Player?.AddExternalSubtitle(file);
            SetSubtitleNotice($"已加载：{System.IO.Path.GetFileName(file)}");
        }
        catch (Exception ex)
        {
            SetSubtitleNotice($"加载失败：{ex.Message}");
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
        SetSubtitleNotice(added == 0 ? "未在同目录找到匹配的外挂字幕文件。" : $"已重新加载 {added} 个字幕文件。");
    }

    // ---------------------------------------------------------------- temporary queue

    /// <summary>One row in the queue panel.</summary>
    /// <summary>One row of the on-screen queue list.</summary>
    /// <remarks>
    /// <see cref="ToString"/> is overridden because a ListViewItem exposes the item's ToString as its
    /// accessibility name: without this a screen reader (and UI Automation) reads the raw record
    /// dump ("QueueRow { Index = 0, ... }") instead of the visible row text.
    /// </remarks>
    private sealed record QueueRow(int Index, string Label, bool IsCurrent)
    {
        public override string ToString() => Label;
    }

    /// <summary>Re-reads the queue and mirrors it into the title bar, the next-item line and the list.</summary>
    /// <remarks>
    /// Called by the shell after an intake, so adding files while something is playing updates the
    /// on-screen queue without interrupting playback.
    /// </remarks>
    public void RefreshQueueView() => UpdateQueueView();

    /// <summary>Called by the shell when a replace-intake arrives while this page is already open.</summary>
    public Task PlayQueueEntryAsync(PlaybackRequest request) => SwitchToAsync(request, PlaybackStopReasons.Replaced);

    private void UpdateQueueView()
    {
        PlaybackQueue queue = App.Services.Queue;
        bool hasQueue = queue.Count > 0;

        QueueCountText.Visibility = hasQueue ? Visibility.Visible : Visibility.Collapsed;
        QueueCountText.Text = hasQueue ? $"队列 {queue.Index + 1}/{queue.Count}" : "";
        QueuePrevButton.IsEnabled = queue.HasPrevious;
        QueueNextButton.IsEnabled = queue.HasNext;

        PlaybackRequest? next = queue.NextItem;
        QueueNextTitle.Visibility = next is not null ? Visibility.Visible : Visibility.Collapsed;
        QueueNextTitle.Text = next is not null ? "下一项：" + next.Title : "";

        var rows = new List<QueueRow>(queue.Count);
        for (int i = 0; i < queue.Count; i++)
        {
            PlaybackRequest item = queue.Items[i];
            rows.Add(new QueueRow(i, (i == queue.Index ? "▶ " : "") + (i + 1) + ". " + item.Title, i == queue.Index));
        }

        QueueList.ItemsSource = rows;
        QueueList.SelectedIndex = queue.Index;
        QueuePlayButton.IsEnabled = rows.Count > 0;
        QueueRemoveButton.IsEnabled = rows.Count > 0;
        QueueClearButton.IsEnabled = hasQueue;
    }

    private void OnQueuePanelClick(object sender, RoutedEventArgs e)
    {
        if (_openPanel == PanelKind.Queue) { HidePanel(); return; }
        UpdateQueueView();
        ShowPanel(PanelKind.Queue, "播放队列");
    }

    private void OnQueuePrevClick(object sender, RoutedEventArgs e)
    {
        if (App.Services.Queue.MoveBy(-1) is { } previous)
        {
            _ = SwitchToAsync(previous, PlaybackStopReasons.Replaced);
        }
    }

    private void OnQueueNextClick(object sender, RoutedEventArgs e)
    {
        if (App.Services.Queue.MoveBy(1) is { } next)
        {
            _ = SwitchToAsync(next, PlaybackStopReasons.Replaced);
        }
    }

    private void OnQueueSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Selecting a row is not a command. A single click must never interrupt what is playing -
        // "播放所选" is the explicit action, and it is the only one that switches.
    }

    private void OnQueuePlaySelectedClick(object sender, RoutedEventArgs e)
    {
        if (QueueList.SelectedItem is not QueueRow row) return;

        PlaybackQueue queue = App.Services.Queue;
        // Asking to play the item that is already playing must not restart it.
        if (row.Index == queue.Index && _session is not null) return;

        if (queue.SelectAt(row.Index) is { } request)
        {
            _ = SwitchToAsync(request, PlaybackStopReasons.Replaced);
        }
    }

    private void OnQueueRemoveClick(object sender, RoutedEventArgs e)
    {
        if (QueueList.SelectedItem is not QueueRow row) return;

        PlaybackQueue queue = App.Services.Queue;
        bool removingCurrent = row.Index == queue.Index;
        if (!queue.RemoveAt(row.Index)) return;

        Services.StartupTrace.Write("queue:remove index=" + row.Index + " remaining=" + queue.Count);
        UpdateQueueView();

        if (!removingCurrent) return;

        // The playing entry itself was removed: stop it, then either continue with what is left or
        // leave the player, because "the current item is gone and nothing else is queued" means the
        // page has no subject any more.
        if (queue.Current is { } next)
        {
            _ = SwitchToAsync(next, PlaybackStopReasons.Replaced);
            return;
        }

        _ = StopCurrentAsync(PlaybackStopReasons.UserExited);
        (App.MainWindow as MainWindow)?.ExitPlayer();
    }

    private void OnQueueClearClick(object sender, RoutedEventArgs e)
    {
        PlaybackQueue queue = App.Services.Queue;
        if (queue.Count == 0) return;

        queue.Clear();
        Services.StartupTrace.Write("queue:clear");
        UpdateQueueView();
        // Clearing the queue ends the queue, not the film the user is watching: playback continues,
        // and only auto-advance has nowhere left to go.
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

        bool finished = snapshot.EofReached || snapshot.State == TransportState.Ended;

        if (request.LocalPath is { } localPath)
        {
            App.Services.PlaybackHistory.Record(
                localPath,
                request.Title,
                (long)(snapshot.PositionSeconds * TimeSpan.TicksPerSecond),
                (long)(snapshot.DurationSeconds * TimeSpan.TicksPerSecond));

            // End-of-item handling is engine-level, not server-level: the local branch returns here,
            // so without this a queue made of local files never advanced past its first entry.
            if (finished) _ = OnPlaybackFinishedAsync();
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

        if (finished) _ = OnPlaybackFinishedAsync();
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
        if (_advanceHandled || _suppressFailureHandling) return;
        _advanceHandled = true;

        PlaybackSession? session = _session;
        session?.TryAdvance(PlaybackStage.Ended);

        if (!App.Services.Settings.Current.AutoPlayNext)
        {
            Services.StartupTrace.Write("queue:auto-advance-disabled");
            return;
        }

        // The temporary queue is the nearer answer to "what comes next": the user put those files
        // there in that order, and a server episode is only consulted when the queue has nothing
        // left after the current item.
        PlaybackQueue queue = App.Services.Queue;
        if (queue.NextItem is { } queuedNext && queue.MoveBy(1) is { } nextInQueue)
        {
            App.Services.Log.Info("playback", "队列下一项：" + LogService.Redact(queuedNext.Title));
            Services.StartupTrace.Write("queue:auto-advance title=" + queuedNext.Title);
            await SwitchToAsync(nextInQueue, PlaybackStopReasons.Completed);
            return;
        }

        if (_request?.ServerId is not { } serverId || _request.SeriesId is not { } seriesId || _request.ItemId is not { } itemId)
            return;

        try
        {
            ProviderHandle? handle = App.Services.Servers.FindHandle(serverId);
            if (handle?.Library is null) return;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            Episode? next = await handle.Library.GetNextEpisodeAsync(seriesId, itemId, timeout.Token);
            if (next is null)
            {
                Services.StartupTrace.Write("queue:no-next-episode");
                return;
            }

            App.Services.Log.Info("playback", "自动连播下一集：" + LogService.Redact(next.Name));
            Services.StartupTrace.Write("queue:auto-advance episode=" + next.Id);

            // In-place, not a new navigation: the finished item has to be stopped and reported
            // before the next one starts, exactly like a queue switch.
            await SwitchToAsync(
                new PlaybackRequest
                {
                    ServerId = serverId,
                    ItemId = next.Id,
                    Title = next.Name,
                    SeriesId = next.SeriesId,
                    SeasonNumber = next.SeasonNumber,
                    EpisodeNumber = next.EpisodeNumber,
                },
                PlaybackStopReasons.Completed);
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
                if (_isFullScreen) { ToggleFullScreen(); break; }
                if (_openPanel != PanelKind.None) { HidePanel(); break; }
                ExitToShell();
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

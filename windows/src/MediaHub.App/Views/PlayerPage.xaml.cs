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
    private readonly DispatcherTimer _screenshotHintTimer = new();
    private readonly DispatcherTimer _settingsSaveTimer = new();

    private PointerActivityWatcher? _pointerWatcher;
    private MouseWheelWatcher? _wheelWatcher;
    private PlaybackRequest? _request;
    private PlaybackSession? _session;

    /// <summary>Picture brightness on the 0-100 scale; 50 is neutral and the value survives restarts.</summary>
    private double _brightness = 50;

    /// <summary>Pending values from keyboard/wheel gestures, written once the user stops adjusting.</summary>
    private double? _pendingVolume;
    private double? _pendingBrightness;

    /// <summary>Neighbouring episodes of the item on screen; both null for anything that is not an episode.</summary>
    private Episode? _previousEpisode;
    private Episode? _nextEpisode;

    /// <summary>
    /// Whether the item on screen belongs to a series. Kept separate from the neighbours because a
    /// one-episode series has neither neighbour yet still deserves its (disabled) buttons, while a film
    /// must not show them at all.
    /// </summary>
    private bool _isEpisodeItem;
    /// <summary>Set while a switch is in flight so the outgoing item's dying snapshots are ignored.</summary>
    private bool _suppressFailureHandling;

    /// <summary>One-shot per item: end-of-stream and errors must not each advance the queue twice.</summary>
    private bool _advanceHandled;

    /// <summary>Serializes in-place switches so two of them cannot stop and start different items at once.</summary>
    private readonly SemaphoreSlim _switchGate = new(1, 1);

    /// <summary>Ticket of the most recently requested switch; earlier tickets are dropped before they start.</summary>
    private int _switchTicket;

    /// <summary>
    /// Client-pixel rectangle in which a wheel notch is a player gesture. Read from the input hook's
    /// thread, so it is swapped as a whole via <see cref="Volatile"/> rather than mutated.
    /// </summary>
    private WheelRegion? _wheelRegion;

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

    /// <summary>The resolved next episode that is already appended to the engine playlist.</summary>
    private PreloadedPlayback? _preloaded;

    /// <summary>Guards against resolving the same next episode twice while the first resolve runs.</summary>
    private bool _preloadInFlight;

    /// <summary>One-shot per item: true once the remembered video track was looked up.</summary>
    private bool _videoTrackMemoryApplied;

    /// <summary>
    /// Set by pause/seek handlers so the next progress tick bypasses the throttle. Without it a pause
    /// or a jump right before leaving the page could lose up to one full report interval.
    /// </summary>
    private bool _flushProgressNextTick;
    private bool _hasProgressBaseline;
    private double _lastProgressPositionSeconds;
    private bool _lastProgressPaused;

    private sealed record PreloadedPlayback(PlaybackRequest Request, ActivePlayback Active);

    /// <summary>
    /// Set while the status panel carries a message the user must keep seeing. The engine keeps
    /// raising snapshots while a failed attempt is torn down, and an idle one used to collapse the
    /// panel - which turned "the server refused to transcode" into an unexplained black screen.
    /// </summary>
    private bool _statusSticky;

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

        // The screenshot entry is opt-in, so it has to follow the setting while this page is open
        // rather than only at construction time.
        ApplyScreenshotAvailability(settings);
        App.Services.Settings.Changed += OnSettingsChanged;

        _screenshotHintTimer.Interval = TimeSpan.FromSeconds(6);
        _screenshotHintTimer.Tick += (_, _) => { _screenshotHintTimer.Stop(); ScreenshotToast.Visibility = Visibility.Collapsed; };

        _brightness = Math.Clamp(settings.PlayerBrightness, MinBrightness, 100);
        _volume = settings.DefaultVolume;

        // Key repeat can fire dozens of times a second; writing the settings file on every notch would
        // hammer the disk for no benefit, so both values are flushed once the gesture settles.
        _settingsSaveTimer.Interval = TimeSpan.FromMilliseconds(800);
        _settingsSaveTimer.Tick += (_, _) => FlushPendingSettings();

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

        // The engine's child window swallows wheel messages over the picture, so the split-half wheel
        // rule needs an observer outside XAML. Installed only while this page is alive.
        _wheelWatcher = new MouseWheelWatcher(host.WindowHandle, WheelRect, OnWheel);

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
        App.Services.Settings.Changed -= OnSettingsChanged;
        _screenshotHintTimer.Stop();
        App.Services.PlayerHost.SnapshotChanged -= OnSnapshotChanged;
        if (_session is not null) _session.Changed -= OnSessionChanged;
        _pointerWatcher?.Dispose();
        _pointerWatcher = null;
        _wheelWatcher?.Dispose();
        _wheelWatcher = null;
        _boundsTimer.Stop();
        _historyTimer.Stop();
        _hideTimer.Stop();
        _settingsSaveTimer.Stop();
        FlushPendingSettings();
        // The coordinator owns the stop report and the relay lifetime, so leaving the page is a
        // session stop rather than a collection of things this page has to remember to undo. It is
        // bounded so a hanging report cannot delay the navigation that is already in progress.
        _ = StopCurrentAsync(PlaybackStopReasons.UserExited);
        if (_preloaded is { } pending)
        {
            _preloaded = null;
            _ = pending.Active.DisposeAsync().AsTask();
        }
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
    private async Task<bool> StartRequestAsync(PlaybackRequest request, ActivePlayback? preloaded = null)
    {
        _request = request;
        _videoTrackMemoryApplied = false;
        _hasProgressBaseline = false;
        _flushProgressNextTick = false;
        if (!string.Equals(_versionsItemId, request.ItemId, StringComparison.Ordinal))
        {
            _versions = Array.Empty<MediaVersion>();
            _versionsItemId = null;
        }
        _advanceHandled = false;
        // A new attempt clears the previous item's message; if this one fails, ShowStatus re-arms it.
        _statusSticky = false;
        _previousEpisode = null;
        _nextEpisode = null;
        _isEpisodeItem = IsEpisodeRequest(request);
        UpdateEpisodeButtons();
        TitleText.Text = request.Title;
        // The force-transcode control only means something for a server item, and it mirrors the
        // request rather than a stored preference: a new item starts in the default (direct) mode, so
        // the switch can never be "on" while the current stream is a direct play.
        TranscodeToggle.Visibility = request.IsLocal ? Visibility.Collapsed : Visibility.Visible;
        TranscodeToggle.IsChecked = request.ForceTranscode;
        UpdateTranscodeBadge();
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
            // receives server credentials. A prefetched episode already went through that path and is
            // sitting on the engine playlist, so adopting it must not resolve (or open) anything again.
            _session = preloaded is not null
                ? await App.Services.Playback.AdoptPreparedAsync(request, preloaded)
                : await App.Services.Playback.PrepareAsync(request);
            _session.Changed += OnSessionChanged;
            SubtitleText.Text = _session.Active?.DisplayUrl ?? request.DisplayUrl;
            if (preloaded is not null)
            {
                await App.Services.PlayerHost.PlayPreloadedAsync(_session.Active!);
            }
            else
            {
                await App.Services.PlayerHost.PlayAsync(_session.Active!);
            }
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
        UpdateTranscodeBadge();
        RenderVersions();
        _ = LoadVersionsAsync(request);
        _ = ResolveEpisodeNeighboursAsync(request);
        return true;
    }

    /// <summary>Mirrors "the stream the server is sending is a transcode" into the top bar.</summary>
    /// <remarks>
    /// Read from the resolved source, never from the toggle: the toggle is a request, and a server can
    /// refuse it, or transcode for reasons of its own. The badge is what the stream actually is.
    /// </remarks>
    private void UpdateTranscodeBadge()
        => TranscodeBadge.Visibility = _session?.Active?.Source?.Mode == PlaybackMode.Transcode
            ? Visibility.Visible
            : Visibility.Collapsed;

    // ---------------------------------------------------------------- versions

    private IReadOnlyList<MediaVersion> _versions = Array.Empty<MediaVersion>();
    private string? _versionsItemId;
    private bool _suppressVersionEvents;

    /// <summary>
    /// Loads the version list for the item on screen.
    /// </summary>
    /// <remarks>
    /// A single-version item costs one call and then stays collapsed, and a failure leaves the picker
    /// out of the way instead of surfacing an error: the version list is navigation, never a reason to
    /// interrupt the film that is already playing.
    /// </remarks>
    private async Task LoadVersionsAsync(PlaybackRequest request)
    {
        if (request.IsLocal || request.ServerId is not { } serverId || request.ItemId is not { } itemId)
        {
            _versions = Array.Empty<MediaVersion>();
            _versionsItemId = null;
            RenderVersions();
            return;
        }

        try
        {
            ProviderHandle? handle = App.Services.Servers.FindHandle(serverId);
            if (handle?.Detail is null) return;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            MediaDetail detail = await handle.Detail.GetItemDetailAsync(itemId, timeout.Token);

            // The user may have moved on while the lookup was in flight; the list belongs to the
            // request that asked for it, not to whatever is playing now.
            if (!ReferenceEquals(_request, request)) return;

            _versions = detail.Versions;
            _versionsItemId = itemId;
            RenderVersions();
        }
        catch (OperationCanceledException) { }
        catch (ProviderException ex)
        {
            App.Services.Log.Debug("playback", "版本列表查询失败：" + ex.Code);
        }
    }

    /// <summary>Reflects the version list, and which entry is actually playing, into the info panel.</summary>
    private void RenderVersions()
    {
        _suppressVersionEvents = true;
        try
        {
            if (_versions.Count <= 1)
            {
                VersionSection.Visibility = Visibility.Collapsed;
                VersionList.ItemsSource = null;
                VersionHint.Text = "";
                return;
            }

            VersionSection.Visibility = Visibility.Visible;
            VersionList.ItemsSource = _versions
                .Select(v => new VersionRow(v.MediaSourceId, v.Label, v.TranscodeOnly))
                .ToList();

            string? playing = _session?.Active?.Source?.MediaSourceId;
            int index = string.IsNullOrEmpty(playing)
                ? -1
                : _versions.ToList().FindIndex(v => string.Equals(v.MediaSourceId, playing, StringComparison.Ordinal));
            VersionList.SelectedIndex = index;
            VersionHint.Text = ComposeVersionHint(index);
        }
        finally
        {
            _suppressVersionEvents = false;
        }
    }

    private string ComposeVersionHint(int index)
    {
        if (index < 0 || index >= _versions.Count) return "";
        MediaVersion version = _versions[index];
        if (version.TranscodeOnly)
        {
            return _session?.Active?.Source?.Mode == PlaybackMode.Transcode
                ? "该版本只能由服务器转码播放：当前正在播放 HLS 转码流。"
                : "该版本只提供转码流：服务器没有为它提供直连地址。";
        }
        return "共 " + _versions.Count + " 个版本；切换会从所选版本的开头重新播放，进度与字幕按该版本重新解析。";
    }

    /// <summary>Switches to the version the user picked, starting it from its own beginning.</summary>
    /// <remarks>
    /// The position is deliberately not carried over: different versions can be different cuts, and a
    /// timestamp from one is meaningless in the other. The subtitle track list is rebuilt from the
    /// newly resolved source, so a track index from the previous version cannot select a different
    /// stream in this one.
    /// </remarks>
    private void OnVersionSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressVersionEvents) return;
        if (VersionList.SelectedItem is not VersionRow row) return;
        if (_request is not { } current || current.IsLocal || current.ServerId is null) return;
        if (string.Equals(row.MediaSourceId, _session?.Active?.Source?.MediaSourceId, StringComparison.Ordinal)) return;

        Services.StartupTrace.Write("playback:version-switch id=" + row.MediaSourceId);
        VersionHint.Text = "正在切换到 " + row.Label + "…";
        _ = SwitchToAsync(
            current with
            {
                MediaSourceId = row.MediaSourceId,
                StartPositionTicks = 0,
                StartFromBeginning = true,
                ForceTranscode = false,
            },
            PlaybackStopReasons.Replaced);
    }

    /// <summary>Position on the item's own timeline.</summary>
    private long CurrentPositionTicks()
    {
        if (_session?.Active is null) return 0;
        double seconds = App.Services.PlayerHost.Player?.Snapshot.PositionSeconds ?? 0;
        if (double.IsNaN(seconds) || seconds <= 0) return 0;
        return (long)(seconds * TimeSpan.TicksPerSecond);
    }

    /// <summary>
    /// Reloads the item on screen in the requested mode, keeping the current position.
    /// </summary>
    /// <remarks>
    /// Per-item, not persisted: the switch exists to answer "can this client play this server's HLS",
    /// and a stored preference would change the default playback path for every later item — which the
    /// product rule forbids. Changing modes is a real session replacement, so the old session is
    /// stopped and reported like any other switch, which is also what keeps the server's encoder from
    /// outliving it.
    /// </remarks>
    private void OnTranscodeToggleClick(object sender, RoutedEventArgs e)
    {
        ShowControls();
        if (_request is not { } current || current.IsLocal || current.ServerId is null) return;

        bool wantsTranscode = TranscodeToggle.IsChecked == true;
        long position = CurrentPositionTicks();
        Services.StartupTrace.Write("playback:transcode-toggle on=" + wantsTranscode + " pos=" + position);
        _ = SwitchToAsync(
            current with { ForceTranscode = wantsTranscode, StartPositionTicks = position },
            PlaybackStopReasons.Replaced);
    }

    /// <summary>
    /// True when the request describes exactly the episode that was prefetched: same server, item and
    /// version, with the same forced-transcode flag.
    /// </summary>
    private static bool MatchesPreload(PlaybackRequest prepared, PlaybackRequest requested)
        => string.Equals(prepared.ServerId, requested.ServerId, StringComparison.Ordinal)
           && string.Equals(prepared.ItemId, requested.ItemId, StringComparison.Ordinal)
           && string.Equals(prepared.MediaSourceId, requested.MediaSourceId, StringComparison.Ordinal)
           && prepared.ForceTranscode == requested.ForceTranscode
           && string.Equals(prepared.LocalPath, requested.LocalPath, StringComparison.Ordinal);

    /// <summary>Stops the current session, reporting its final position, and releases the relay.</summary>
    private async Task StopCurrentAsync(string reason)
    {
        PlaybackSession? session = _session;
        _session = null;
        UpdateTranscodeBadge();
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

        // The engine released its cache handles with the session, so the end-of-playback policy can
        // now actually delete files instead of silently skipping the open ones.
        try { App.Services.ApplyDiskCachePolicy(); } catch { }
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
    /// <para>
    /// Switches are serialized and the newest request wins. Auto-advance at the end of a short item
    /// and a user's "previous episode" click can land in the same second; unserialized, both would
    /// stop the old session and both would start theirs, and whichever engine call finished last
    /// decided what played — which is how a click on "previous" once landed on the next episode. A
    /// switch that is no longer the newest is dropped before it starts anything, so an in-flight
    /// auto-advance can never override the item the user asked for after it.
    /// </para>
    /// </remarks>
    private async Task SwitchToAsync(PlaybackRequest request, string stopReason)
    {
        int ticket = Interlocked.Increment(ref _switchTicket);
        Services.StartupTrace.Write("queue:switch reason=" + stopReason + " title=" + request.Title);
        _suppressFailureHandling = true;
        try
        {
            await _switchGate.WaitAsync();
            try
            {
                if (ticket != Volatile.Read(ref _switchTicket))
                {
                    Services.StartupTrace.Write("queue:switch-dropped reason=" + stopReason);
                    return;
                }

                await StopCurrentAsync(stopReason);

                // The stop report can take seconds; a newer intent may have arrived meanwhile.
                if (ticket != Volatile.Read(ref _switchTicket))
                {
                    Services.StartupTrace.Write("queue:switch-dropped-after-stop reason=" + stopReason);
                    return;
                }

                ActivePlayback? prepared = null;
                PreloadedPlayback? preloaded = _preloaded;
                _preloaded = null;
                if (preloaded is not null)
                {
                    if (MatchesPreload(preloaded.Request, request)) prepared = preloaded.Active;
                    else await preloaded.Active.DisposeAsync();
                }

                await StartRequestAsync(request, prepared);
            }
            finally
            {
                _switchGate.Release();
            }
        }
        finally
        {
            // Only the newest switch may lower the guard. A superseded one doing it would let the
            // item still being torn down report its end as a failure of the newer item.
            if (ticket == Volatile.Read(ref _switchTicket)) _suppressFailureHandling = false;
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
            _ = StopCurrentAsync(PlaybackStopReasons.Failed);
            return;
        }

        if (_consecutiveFailures >= MaxConsecutiveFailures)
        {
            ShowStatus("播放失败：" + message
                + "（已连续跳过 " + _consecutiveFailures + " 个无法播放的项目，停止继续跳过）");
            Services.StartupTrace.Write("queue:skip-capped");
            _ = StopCurrentAsync(PlaybackStopReasons.Failed);
            return;
        }

        PlaybackQueue queue = App.Services.Queue;
        if (queue.NextItem is null)
        {
            Services.StartupTrace.Write("queue:no-next-item");
            _ = StopCurrentAsync(PlaybackStopReasons.Failed);
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
        UpdateTranscodeBadge();
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
            UpdateWheelGestureRegion();
            TraceLayout(origin, size, windowRoot);
        }
        catch
        {
            // The visual tree can be mid-teardown; the next tick will retry.
        }
    }

    /// <summary>
    /// Whether a wheel notch at the cursor is a player gesture, and over which rectangle its halves
    /// are measured.
    /// </summary>
    /// <remarks>
    /// The whole client area, not just the picture. The keyboard already means "brightness in the
    /// window's left half, volume in its right half", and the wheel has to mean the same thing
    /// wherever the pointer sits; a side panel that consumed the notch without doing anything visible
    /// was the inconsistency. So the notch is always the player's: volume or brightness plus the OSD,
    /// and the panel scrolls with its scrollbar and the keyboard instead of with the wheel.
    /// </remarks>
    private (int X, int Y, int Width, int Height) WheelRect()
    {
        if (Volatile.Read(ref _wheelRegion) is { } region)
            return (region.X, region.Y, region.Width, region.Height);
        return App.Services.PlayerHost.PhysicalBounds;
    }

    /// <summary>Recomputes <see cref="_wheelRegion"/> from the current window size.</summary>
    private void UpdateWheelGestureRegion()
    {
        var host = App.Services.PlayerHost;

        WheelRegion region;
        if (WindowInterop.GetClientRect(host.WindowHandle, out var client)
            && client.Width > 0 && client.Height > 0)
        {
            region = new WheelRegion(0, 0, client.Width, client.Height);
        }
        else
        {
            (int x, int y, int width, int height) = host.PhysicalBounds;
            region = new WheelRegion(x, y, width, height);
        }

        Volatile.Write(ref _wheelRegion, region);
    }

    /// <summary>An immutable rectangle so the input-hook thread can read it without touching XAML.</summary>
    private sealed record WheelRegion(int X, int Y, int Width, int Height);

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
            if (!_statusSticky) StatusPanel.Visibility = Visibility.Collapsed;
        }
        else if (s.DurationSeconds <= 0 && s.State == TransportState.Loading)
        {
            if (!_statusSticky)
            {
                LoadingRing.IsActive = true;
                StatusPanel.Visibility = Visibility.Visible;
                StatusText.Text = "正在打开媒体…";
            }
        }
        else
        {
            LoadingRing.IsActive = false;
            // Only real playback earns the panel back; an idle snapshot from a failed attempt must
            // not erase the reason it failed.
            if (s.State == TransportState.Playing) _statusSticky = false;
            if (!_statusSticky) StatusPanel.Visibility = Visibility.Collapsed;
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
            // Nothing of ours is mid-gesture, so this is the engine reporting a value we did not set.
            if (_pendingVolume is null) _volume = VolumeSlider.Value;
        }

        TechnicalSummary.Text = BuildTechnicalSummary(s);
        if (InfoToggle.IsChecked == true) PopulateInfo(s);
        ApplyRememberedVideoTrack(s);
        MaybePreloadNext(s);

        // Compared against the tracks the list was built FROM, not against the number of rows it
        // holds. The subtitle list carries an extra "关闭字幕" row, so comparing it to
        // SubtitleTracks.Count was never equal - and this runs on every sampling tick (about ten
        // times a second), so the list was torn down and rebuilt continuously. On an audio-only file,
        // where there are no subtitle tracks at all, it flickered every single tick.
        // The comparison is on the CONTENT of the track lists, not on their sizes. Counting was
        // enough to stop the per-tick flicker, but it misses the case that matters most: replacing one
        // external subtitle with another leaves the count unchanged (two tracks before, two after)
        // while the list on screen still names the file that is no longer loaded. Signatures include
        // the track ids, labels and selection, so a same-size replacement is still a change.
        if (TrackSignature(s.AudioTracks, static t => t.Id + ":" + t.Language + ":" + t.Codec + ":" + t.Title + ":" + t.Selected) != _renderedAudioSignature
            || TrackSignature(s.SubtitleTracks, static t => t.Id + ":" + t.Language + ":" + t.Codec + ":" + t.Title + ":" + t.IsExternal + ":" + t.IsForced + ":" + t.Selected) != _renderedSubtitleSignature
            || TrackSignature(s.VideoTracks, static t => t.Id + ":" + t.Codec + ":" + t.Width + "x" + t.Height + ":" + t.Selected) != _renderedVideoSignature)
        {
            RefreshTrackLists();
        }

        // The expiry of a one-shot result is not a track-list change, so nothing above would redraw it:
        // once the lists settled, "已加载：xxx" stayed on screen indefinitely. Measured in Gate C (C3.5):
        // nine seconds after loading a subtitle the hint still read the file name.
        ExpireSubtitleNotice(s.SubtitleTracks.Count);
    }

    /// <summary>Drops an expired one-shot result and repaints the hint from the baseline text.</summary>
    private void ExpireSubtitleNotice(int trackCount)
    {
        if (_subtitleNotice is null) return;
        if (DateTime.UtcNow - _subtitleNoticeAt < SubtitleNoticeLifetime) return;
        _subtitleNotice = null;
        SubtitleHint.Text = ComposeSubtitleHint(trackCount);
    }

    private static string BuildTechnicalSummary(PlayerSnapshot s)
    {
        var parts = new List<string>();
        if (s.Width is { } w && s.Height is { } h) parts.Add($"{w}×{h}");
        if (s.Technical.VideoCodec is { } vc) parts.Add(vc);
        if (s.Technical.HwDecCurrent is { } hw) parts.Add($"hwdec={hw}");
        if (s.Technical.ColorTransfer is { } ct) parts.Add(ct);
        if (s.Technical.IsDolbyVision) parts.Add("Dolby Vision");
        if (s.Technical.AudioCodec is { } ac) parts.Add(ac);
        if (s.Fps is { } fps) parts.Add($"{fps:0.###}fps");
        if (s.Technical.CacheSeconds is > 0 and var cache) parts.Add($"缓冲 {cache:0.#}s");
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
            new("杜比视界", t.IsDolbyVision ? "是（元数据已识别）" : "否"),
            new("音频编码", t.AudioCodec ?? Unknown),
            new("声道数", t.AudioChannels?.ToString() ?? Unknown),
            new("采样率", t.AudioSampleRate is { } sr ? $"{sr} Hz" : Unknown),
            new("容器", t.Container ?? Unknown),
            new("码率", t.BitRate is { } br ? $"{br / 1000.0:0} kbps" : Unknown),
            new("文件大小", t.FileSizeBytes is { } fs ? $"{fs / (double)(1L << 20):0.0} MB" : Unknown),
            new("视频输出", t.VideoOutput ?? Unknown),
            new("GPU 上下文", t.GpuContext ?? Unknown),
            new("缓冲时长", t.CacheSeconds is { } cache ? $"{cache:0.#} 秒" : Unknown),
            new("缓冲速度", t.CacheSpeedBytesPerSecond is { } speed ? $"{speed / (1024.0 * 1024):0.0} MB/s" : Unknown),
            new("丢帧", t.FrameDrops?.ToString() ?? Unknown),
            new("错位帧", t.MistimedFrames?.ToString() ?? Unknown),
            new("音轨", s.AudioTracks.FirstOrDefault(a => a.Selected)?.Title
                          ?? s.AudioTracks.FirstOrDefault(a => a.Selected)?.Language ?? Unknown),
            new("视频轨", s.VideoTracks.FirstOrDefault(v => v.Selected) is { } vt
                ? (vt.Title ?? vt.Language ?? vt.Codec ?? ("轨 " + vt.Id))
                  + (vt.Width is { } vw && vt.Height is { } vh ? $" · {vw}×{vh}" : "")
                : Unknown),
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
        _flushProgressNextTick = true;
        ShowControls();
    }

    private void OnPreviousEpisodeClick(object sender, RoutedEventArgs e)
    {
        if (_previousEpisode is { } episode && _request is { } current)
        {
            App.Services.Log.Info("playback", "切换到上一集：" + LogService.Redact(episode.Name));
            _ = SwitchToAsync(EpisodeRequest(episode, current), PlaybackStopReasons.Replaced);
        }
        ShowControls();
    }

    private void OnSettingsChanged(object? sender, MediaHubSettings settings)
        => DispatcherQueue.TryEnqueue(() => ApplyScreenshotAvailability(settings));

    private void ApplyScreenshotAvailability(MediaHubSettings settings)
        => ScreenshotButton.Visibility = settings.AllowPlayerScreenshots ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>
    /// Saves the frame the engine is currently rendering, subtitles included.
    /// </summary>
    /// <remarks>
    /// This exists for verification: a normal window capture cannot show the video surface (the
    /// separate HWND is composited outside the XAML island), so "did the subtitle really reach the
    /// screen" was previously unanswerable. The engine's own screenshot writes the decoded frame with
    /// the subtitle overlay applied, next to the picture, in the same file.
    /// </remarks>
    private async void OnScreenshotClick(object sender, RoutedEventArgs e)
    {
        if (!App.Services.Settings.Current.AllowPlayerScreenshots) return;

        var player = App.Services.PlayerHost.Player;
        if (player is null)
        {
            ShowScreenshotHint("截图失败：播放器尚未初始化");
            return;
        }

        string directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "MediaHub 截图");
        string name = SafeFileName(TitleText.Text) + " " + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".png";
        string path = Path.Combine(directory, name);

        try
        {
            Directory.CreateDirectory(directory);
        }
        catch (Exception ex)
        {
            ShowScreenshotHint("截图失败：" + ex.Message);
            return;
        }

        if (!player.TakeScreenshot(path))
        {
            ShowScreenshotHint("截图失败：引擎没有接受截图命令（可能还没有画面）");
            return;
        }

        // Accepting the command only means it was queued; the file is written from the render thread.
        // Reporting success on the command alone would claim evidence that may not exist, so wait for
        // the file itself and record what landed.
        long size = 0;
        for (int attempt = 0; attempt < 50; attempt++)
        {
            await Task.Delay(100);
            try
            {
                var file = new FileInfo(path);
                if (file.Exists && file.Length > 0)
                {
                    size = file.Length;
                    break;
                }
            }
            catch (IOException) { /* still being written */ }
        }

        if (size == 0)
        {
            App.Services.Log.Error("player", "截图命令被接受但文件未写入：" + path);
            ShowScreenshotHint("截图失败：命令被接受但文件没有写入");
            return;
        }

        App.Services.Log.Info("player", $"截图已保存：{path}（{size / 1024} KB）");
        ShowScreenshotHint($"截图已保存：{path}（{size / 1024} KB）");
    }

    private static string SafeFileName(string name)
    {
        foreach (char invalid in Path.GetInvalidFileNameChars()) name = name.Replace(invalid, '_');
        name = name.Trim();
        if (name.Length > 60) name = name[..60];
        return name.Length == 0 ? "screenshot" : name;
    }

    private void ShowScreenshotHint(string message)
    {
        ScreenshotToast.Text = message;
        ScreenshotToast.Visibility = Visibility.Visible;
        _screenshotHintTimer.Stop();
        _screenshotHintTimer.Start();
    }

    private void OnNextEpisodeClick(object sender, RoutedEventArgs e)
    {
        if (_nextEpisode is { } episode && _request is { } current)
        {
            App.Services.Log.Info("playback", "切换到下一集：" + LogService.Redact(episode.Name));
            _ = SwitchToAsync(EpisodeRequest(episode, current), PlaybackStopReasons.Replaced);
        }
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
        _volume = e.NewValue;
        App.Services.PlayerHost.Player?.SetVolume(e.NewValue);
        QueueSettingsSave(volume: e.NewValue);
    }

    private void OnSeekValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_suppressSeekEvent) return;
        var player = App.Services.PlayerHost.Player;
        if (player is null) return;

        double duration = player.Snapshot.DurationSeconds;
        if (duration <= 0) return;

        if (_scrubbing) PositionText.Text = Format(e.NewValue * duration);
        else
        {
            player.SeekAbsolute(e.NewValue * duration);
            _flushProgressNextTick = true;
        }
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

    private void OnVideoPanelClick(object sender, RoutedEventArgs e)
    {
        if (_openPanel == PanelKind.Video) { HidePanel(); return; }
        RefreshTrackLists();
        ShowPanel(PanelKind.Video, "视频轨");
    }

    /// <summary>Which side panel is showing, or <see cref="PanelKind.None"/>.</summary>
    private enum PanelKind { None, Info, Video, Audio, Subtitle, Queue, Shortcuts }

    private PanelKind _openPanel = PanelKind.None;

    private void ShowPanel(PanelKind kind, string header)
    {
        _openPanel = kind;
        PanelHeader.Text = header;
        InfoSection.Visibility = kind == PanelKind.Info ? Visibility.Visible : Visibility.Collapsed;
        VideoSection.Visibility = kind == PanelKind.Video ? Visibility.Visible : Visibility.Collapsed;
        AudioSection.Visibility = kind == PanelKind.Audio ? Visibility.Visible : Visibility.Collapsed;
        SubtitleSection.Visibility = kind == PanelKind.Subtitle ? Visibility.Visible : Visibility.Collapsed;
        QueueSection.Visibility = kind == PanelKind.Queue ? Visibility.Visible : Visibility.Collapsed;
        ShortcutsSection.Visibility = kind == PanelKind.Shortcuts ? Visibility.Visible : Visibility.Collapsed;
        if (kind == PanelKind.Shortcuts) BuildShortcutRows();
        if (kind == PanelKind.Audio) RefreshPlayerAudioDevices();
        if (kind == PanelKind.Subtitle) SyncSubtitleStyleControls();
        PanelColumn.Width = new GridLength(340);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    /// <summary>
    /// Renders the shortcut sheet from the catalog. Built once and cached: the catalog is static, and
    /// rebuilding on every open would throw away the scroll position for no gain.
    /// </summary>
    private void BuildShortcutRows()
    {
        if (ShortcutsStack.Children.Count > 0) return;
        foreach (var group in PlayerShortcutCatalog.All.GroupBy(s => s.Group))
        {
            ShortcutsStack.Children.Add(new TextBlock
            {
                Text = group.Key,
                Margin = new Thickness(0, 4, 0, 0),
                Style = (Style)Application.Current.Resources["MediaHubCaption"],
            });
            foreach (var shortcut in group)
            {
                var row = new Grid { ColumnSpacing = 10 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                var keys = new TextBlock { Text = shortcut.Keys, FontSize = 12, TextWrapping = TextWrapping.Wrap, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
                var action = new TextBlock { Text = shortcut.Action, FontSize = 12, TextWrapping = TextWrapping.Wrap };
                Grid.SetColumn(action, 1);
                row.Children.Add(keys);
                row.Children.Add(action);
                ShortcutsStack.Children.Add(row);
            }
        }
    }

    private void OnShortcutsClick(object sender, RoutedEventArgs e)
    {
        if (_openPanel == PanelKind.Shortcuts) { HidePanel(); return; }
        ShowPanel(PanelKind.Shortcuts, "快捷键与操作");
    }

    // ---------------------------------------------------------------- subtitle style (from the player)

    /// <summary>
    /// True while the panel's controls are being filled from settings.
    /// </summary>
    /// <remarks>
    /// Starts true because a slider raises ValueChanged while XAML is still loading it, when its value
    /// is clamped from zero to the minimum. Without this the page would write the minimum size and
    /// position over the user's saved style just by being constructed, before the panel was ever opened.
    /// The first <see cref="SyncSubtitleStyleControls"/> clears it, and that only happens once the panel
    /// is on screen and its values are real.
    /// </remarks>
    private bool _syncingPlayerStyle = true;

    /// <summary>
    /// Pulls the persisted style into the panel's controls. The guard matters: assigning Slider.Value
    /// raises ValueChanged, which would write the same value back to the engine and the settings file
    /// on every panel open.
    /// </summary>
    private void SyncSubtitleStyleControls()
    {
        var s = App.Services.Settings.Current;
        _syncingPlayerStyle = true;
        try
        {
            PlayerSubtitleSizeSlider.Value = s.SubtitleFontSize;
            PlayerSubtitleColorCombo.ItemsSource = SubtitleStyleOptions.Colors.Select(c => c.Label).ToList();
            int index = -1;
            for (int i = 0; i < SubtitleStyleOptions.Colors.Count; i++)
            {
                if (SubtitleStyleOptions.Colors[i].Hex == s.SubtitleColor) { index = i; break; }
            }
            PlayerSubtitleColorCombo.SelectedIndex = index;
            PlayerSubtitleBoldToggle.IsChecked = s.SubtitleBold;
            PlayerSubtitleBorderSlider.Value = s.SubtitleBorderSize;
            PlayerSubtitlePositionSlider.Value = s.SubtitlePosition;
        }
        finally
        {
            _syncingPlayerStyle = false;
        }
    }

    private void OnPlayerSubtitleSizeChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_syncingPlayerStyle) return;
        App.Services.Settings.Update(s => s.SubtitleFontSize = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitleFontSize(e.NewValue);
    }

    private void OnPlayerSubtitleColorChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingPlayerStyle) return;
        int index = PlayerSubtitleColorCombo.SelectedIndex;
        if (index < 0 || index >= SubtitleStyleOptions.Colors.Count) return;
        string hex = SubtitleStyleOptions.Colors[index].Hex;
        App.Services.Settings.Update(s => s.SubtitleColor = hex);
        App.Services.PlayerHost.Player?.SetSubtitleColor(hex);
    }

    private void OnPlayerSubtitleBoldClick(object sender, RoutedEventArgs e)
    {
        if (_syncingPlayerStyle) return;
        bool bold = PlayerSubtitleBoldToggle.IsChecked == true;
        App.Services.Settings.Update(s => s.SubtitleBold = bold);
        App.Services.PlayerHost.Player?.SetSubtitleBold(bold);
    }

    private void OnPlayerSubtitleBorderChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_syncingPlayerStyle) return;
        App.Services.Settings.Update(s => s.SubtitleBorderSize = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitleBorderSize(e.NewValue);
    }

    private void OnPlayerSubtitlePositionChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_syncingPlayerStyle) return;
        App.Services.Settings.Update(s => s.SubtitlePosition = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitlePosition(e.NewValue);
    }

    private void OnResetSubtitleStyleClick(object sender, RoutedEventArgs e)
    {
        App.Services.Settings.Update(s =>
        {
            s.SubtitleFontSize = SubtitleStyleOptions.DefaultSize;
            s.SubtitleFont = SubtitleStyleOptions.DefaultFont;
            s.SubtitleColor = SubtitleStyleOptions.DefaultColor;
            s.SubtitleBorderSize = SubtitleStyleOptions.DefaultBorderSize;
            s.SubtitlePosition = SubtitleStyleOptions.DefaultPosition;
            s.SubtitleBold = false;
        });
        App.Services.PlayerHost.ApplySubtitleSettings();
        SyncSubtitleStyleControls();
    }

    // ---------------------------------------------------------------- audio output (from the player)

    private static readonly EngineAudioDevice AutoAudioDevice = new("", "自动（跟随系统默认）", true);

    private bool _syncingPlayerAudio;

    private void RefreshPlayerAudioDevices()
    {
        var devices = new List<EngineAudioDevice> { AutoAudioDevice };
        devices.AddRange(App.Services.PlayerHost.GetAudioDevices()
            .Where(d => !string.Equals(d.Name, "auto", StringComparison.OrdinalIgnoreCase)));

        string preferred = App.Services.Settings.Current.AudioDevice ?? "";
        _syncingPlayerAudio = true;
        try
        {
            PlayerAudioDeviceCombo.ItemsSource = devices;
            PlayerAudioDeviceCombo.SelectedItem =
                devices.FirstOrDefault(d => string.Equals(d.Name, preferred, StringComparison.OrdinalIgnoreCase))
                ?? AutoAudioDevice;
        }
        finally
        {
            _syncingPlayerAudio = false;
        }
        UpdatePlayerAudioDeviceHint(devices.Count > 1);
    }

    private void UpdatePlayerAudioDeviceHint(bool engineReportedDevices)
    {
        string? fallback = App.Services.PlayerHost.AudioDeviceFallback;
        if (!string.IsNullOrEmpty(fallback)) { PlayerAudioDeviceHint.Text = fallback; return; }
        if (!engineReportedDevices) { PlayerAudioDeviceHint.Text = "引擎尚未初始化，暂时只能选择自动。"; return; }

        string? current = App.Services.PlayerHost.CurrentAudioDevice;
        PlayerAudioDeviceHint.Text = "当前输出：" + App.Services.PlayerHost.DescribeAudioDevice(current) + "。";
    }

    private void OnPlayerAudioDeviceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingPlayerAudio) return;
        if (PlayerAudioDeviceCombo.SelectedItem is not EngineAudioDevice device) return;
        string? name = string.IsNullOrWhiteSpace(device.Name) ? null : device.Name;
        App.Services.Settings.Update(s => s.AudioDevice = name);
        App.Services.PlayerHost.TrySetAudioDevice(name);
        UpdatePlayerAudioDeviceHint(engineReportedDevices: true);
    }

    private void OnRefreshAudioDevicesClick(object sender, RoutedEventArgs e) => RefreshPlayerAudioDevices();

    private void HidePanel()
    {
        _openPanel = PanelKind.None;
        PanelColumn.Width = new GridLength(0);
        DispatcherQueue.TryEnqueue(UpdateSurfaceBounds);
    }

    /// <summary>Signatures of the lists the track rows were last built from.</summary>
    private string _renderedAudioSignature = "";
    private string _renderedSubtitleSignature = "";
    private string _renderedVideoSignature = "";

    private int _renderedSubtitleCount => App.Services.PlayerHost.Player?.Snapshot?.SubtitleTracks.Count ?? 0;

    private static string TrackSignature<T>(IReadOnlyList<T> tracks, Func<T, string> project)
        => tracks.Count == 0 ? "" : string.Join(";", tracks.Select(project));

    private void RefreshTrackLists()
    {
        var snapshot = App.Services.PlayerHost.Player?.Snapshot;
        if (snapshot is null) return;

        _renderedAudioSignature = TrackSignature(snapshot.AudioTracks, static t => t.Id + ":" + t.Language + ":" + t.Codec + ":" + t.Title + ":" + t.Selected);
        _renderedSubtitleSignature = TrackSignature(snapshot.SubtitleTracks, static t => t.Id + ":" + t.Language + ":" + t.Codec + ":" + t.Title + ":" + t.IsExternal + ":" + t.IsForced + ":" + t.Selected);
        _renderedVideoSignature = TrackSignature(snapshot.VideoTracks, static t => t.Id + ":" + t.Codec + ":" + t.Width + "x" + t.Height + ":" + t.Selected);

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

            var videos = snapshot.VideoTracks
                .Select(t => new TrackRow(
                    t.Id,
                    $"{(t.Width is { } w && t.Height is { } h ? $"{w}×{h}" : "未知分辨率")} · {(t.Codec ?? "未知编码")}" +
                    (t.Fps is { } fps ? $" · {fps:0.###}fps" : "") +
                    (string.IsNullOrWhiteSpace(t.Title) ? "" : $" · {t.Title}"),
                    t.Selected))
                .ToList();
            VideoList.ItemsSource = videos;
            VideoList.SelectedIndex = videos.FindIndex(v => v.Selected);

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

    private void OnVideoSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressTrackEvents || VideoList.SelectedItem is not TrackRow row) return;
        App.Services.PlayerHost.Player?.SelectVideoTrack(row.Id < 0 ? null : row.Id);

        // Remember the explicit choice for this series so the next episode opens the same way.
        if (_request is not { } request) return;
        var track = App.Services.PlayerHost.Player?.Snapshot?.VideoTracks.FirstOrDefault(t => t.Id == row.Id);
        if (track is null) return;
        string key = TrackMemoryKey(request);
        string signature = VideoTrackSignature(track);
        App.Services.Settings.Update(s => s.VideoTrackMemory[key] = signature);
        App.Services.Log.Info("player", "记住视频轨：" + signature);
    }

    /// <summary>How close to the end the next episode starts being prefetched.</summary>
    private static readonly TimeSpan NextEpisodePreloadLead = TimeSpan.FromSeconds(90);

    /// <summary>
    /// Appends the next episode to the engine playlist shortly before the current one ends. The check
    /// runs from the snapshot tick, so it needs to be cheap and idempotent.
    /// </summary>
    private void MaybePreloadNext(PlayerSnapshot snapshot)
    {
        if (_preloadInFlight || _preloaded is not null) return;
        if (_request is not { } current || _nextEpisode is not { } next) return;
        if (!App.Services.Settings.Current.AutoPlayNext) return;
        if (snapshot.State != TransportState.Playing || snapshot.DurationSeconds <= 0) return;

        double remaining = snapshot.DurationSeconds - snapshot.PositionSeconds;
        if (remaining < 0 || remaining > NextEpisodePreloadLead.TotalSeconds) return;
        if (string.Equals(next.Id, current.ItemId, StringComparison.Ordinal)) return;

        var request = EpisodeRequest(next, current);
        _preloadInFlight = true;
        _ = PreloadNextAsync(request, current.ItemId ?? "");
    }

    private async Task PreloadNextAsync(PlaybackRequest request, string currentItemId)
    {
        ActivePlayback? resolved = null;
        try
        {
            resolved = await App.Services.Playback.ResolveForPreloadAsync(request, CancellationToken.None);

            // The user may have moved on while the resolve was in flight; never leave an orphan relay.
            if (_request is null || _session is null
                || !string.Equals(_request.ItemId, currentItemId, StringComparison.Ordinal))
            {
                return;
            }

            App.Services.PlayerHost.PreloadNext(resolved);
            _preloaded = new PreloadedPlayback(request, resolved);
            resolved = null;
        }
        catch (Exception ex)
        {
            App.Services.Log.Info("player", "下一集预加载失败：" + ex.Message);
        }
        finally
        {
            if (resolved is not null)
            {
                try { await resolved.DisposeAsync(); } catch { }
            }
            _preloadInFlight = false;
        }
    }

    private static string VideoTrackSignature(EngineVideoTrack track)
        => string.Join('|',
            track.Language ?? "",
            track.Title ?? "",
            track.Codec ?? "",
            track.Width?.ToString() ?? "",
            track.Height?.ToString() ?? "");

    private static string TrackMemoryKey(PlaybackRequest request)
        => (request.ServerId ?? "local") + ":" + (request.SeriesId ?? request.ItemId ?? request.Title);

    /// <summary>
    /// Re-applies the remembered video track once per item, after the engine has reported its tracks.
    /// It never overrides a track the user already toggled in this session: the flag is one-shot.
    /// </summary>
    private void ApplyRememberedVideoTrack(PlayerSnapshot snapshot)
    {
        if (_videoTrackMemoryApplied || _request is not { } request) return;
        if (snapshot.VideoTracks.Count == 0) return;
        _videoTrackMemoryApplied = true;

        string key = TrackMemoryKey(request);
        if (!App.Services.Settings.Current.VideoTrackMemory.TryGetValue(key, out string? wanted)
            || string.IsNullOrWhiteSpace(wanted))
        {
            return;
        }

        var match = snapshot.VideoTracks.FirstOrDefault(t => VideoTrackSignature(t) == wanted);
        if (match is null || match.Selected) return;
        try
        {
            App.Services.PlayerHost.Player?.SelectVideoTrack(match.Id);
            App.Services.Log.Info("player", "应用记忆的视频轨：" + wanted);
        }
        catch (Exception ex)
        {
            App.Services.Log.Info("player", "应用视频轨记忆失败：" + ex.Message);
        }
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
        QueuePrevButton.IsEnabled = queue.HasPrevious || _previousEpisode is not null;
        QueueNextButton.IsEnabled = queue.HasNext || _nextEpisode is not null;

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
            return;
        }

        // The top bar keeps its "previous/next item" meaning for queue playback, and falls back to
        // the neighbouring episode so the control is not permanently grey while a series plays.
        if (_previousEpisode is { } episode && _request is { } current)
            _ = SwitchToAsync(EpisodeRequest(episode, current), PlaybackStopReasons.Replaced);
    }

    private void OnQueueNextClick(object sender, RoutedEventArgs e)
    {
        if (App.Services.Queue.MoveBy(1) is { } next)
        {
            _ = SwitchToAsync(next, PlaybackStopReasons.Replaced);
            return;
        }

        if (_nextEpisode is { } episode && _request is { } current)
            _ = SwitchToAsync(EpisodeRequest(episode, current), PlaybackStopReasons.Replaced);
    }

    private static PlaybackRequest EpisodeRequest(Episode episode, PlaybackRequest current) => new()
    {
        ServerId = current.ServerId,
        ItemId = episode.Id,
        Title = episode.Name,
        SeriesId = episode.SeriesId,
        SeasonNumber = episode.SeasonNumber,
        EpisodeNumber = episode.EpisodeNumber,
    };

    /// <summary>Shows the previous/next episode controls only when this item really has neighbours.</summary>
    private void UpdateEpisodeButtons()
    {
        PrevEpisodeButton.Visibility = _isEpisodeItem ? Visibility.Visible : Visibility.Collapsed;
        NextEpisodeButton.Visibility = _isEpisodeItem ? Visibility.Visible : Visibility.Collapsed;
        PrevEpisodeButton.IsEnabled = _previousEpisode is not null;
        NextEpisodeButton.IsEnabled = _nextEpisode is not null;
        UpdateQueueView();
    }

    /// <summary>
    /// Whether a request describes an episode. Read off the request itself because the neighbours are
    /// only known after a round trip, and the buttons must already be the right ones (disabled, not
    /// absent) while that lookup is in flight.
    /// </summary>
    private static bool IsEpisodeRequest(PlaybackRequest request)
        => request.SeriesId is not null || request.SeasonNumber is not null || request.EpisodeNumber is not null;

    /// <summary>
    /// Resolves the neighbouring episodes for the item now playing.
    /// </summary>
    /// <remarks>
    /// A movie has no neighbours, so both buttons stay hidden rather than sitting there permanently
    /// disabled. Failures are silent by design: this only drives two optional buttons, and a slow or
    /// broken lookup must never delay or fail playback itself.
    /// </remarks>
    private async Task ResolveEpisodeNeighboursAsync(PlaybackRequest request)
    {
        if (request.ServerId is not { } serverId || request.ItemId is not { } itemId)
        {
            UpdateEpisodeButtons();
            return;
        }

        try
        {
            ProviderHandle? handle = App.Services.Servers.FindHandle(serverId);
            if (handle?.Library is null) return;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));

            string? seriesId = request.SeriesId;
            if (string.IsNullOrEmpty(seriesId) && handle.Detail is not null)
            {
                MediaDetail detail = await handle.Detail.GetItemDetailAsync(itemId, timeout.Token);
                seriesId = detail.Item.SeriesId;
            }

            // The request did not say "episode", but the server just did: a film has no series, so an
            // item that does belong to one gets its buttons.
            if (!string.IsNullOrEmpty(seriesId) && !_isEpisodeItem)
            {
                if (ReferenceEquals(_request, request))
                {
                    _isEpisodeItem = true;
                    UpdateEpisodeButtons();
                }
            }
            if (string.IsNullOrEmpty(seriesId)) return;

            Task<Episode?> previousTask = handle.Library.GetPreviousEpisodeAsync(seriesId, itemId, timeout.Token);
            Task<Episode?> nextTask = handle.Library.GetNextEpisodeAsync(seriesId, itemId, timeout.Token);
            Episode? previous = await previousTask;
            Episode? next = await nextTask;

            // The user may have switched items while the lookup was in flight; stale neighbours would
            // then switch to an episode of the item that is no longer playing.
            if (!ReferenceEquals(_request, request)) return;

            _previousEpisode = previous;
            _nextEpisode = next;
            UpdateEpisodeButtons();
        }
        catch (OperationCanceledException) { }
        catch (ProviderException ex)
        {
            App.Services.Log.Debug("playback", "相邻集查询失败：" + ex.Code);
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

        // A pause or a jump changes what the server should see immediately; the ordinary tick is
        // throttled by the session policy. Detecting the transition here also covers seeks that are
        // larger than one tick's worth of normal playback.
        bool paused = snapshot.State == TransportState.Paused;
        bool force = PlaybackProgressReporting.ShouldForce(
            _flushProgressNextTick, _hasProgressBaseline, paused, _lastProgressPaused,
            snapshot.PositionSeconds, _lastProgressPositionSeconds);
        _flushProgressNextTick = false;
        _hasProgressBaseline = true;
        _lastProgressPositionSeconds = snapshot.PositionSeconds;
        _lastProgressPaused = paused;

        // Start is sent exactly once, the first time the engine reports real playback. Sending it
        // when the page opens would tell the server playback had begun before it had, and sending it
        // on every tick would create a session storm.
        if (!session.StartReported && snapshot.State == TransportState.Playing)
        {
            session.TryAdvance(PlaybackStage.Playing);
            _ = StartThenReportAsync(session, sample, force);
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

            _ = App.Services.Playback.OnProgressAsync(session, sample, force);
        }

        if (finished) _ = OnPlaybackFinishedAsync();
    }

    /// <summary>
    /// Sends start before the first progress report. Both used to be fire-and-forget, so progress
    /// could reach the server before the start message and be ignored until the next tick.
    /// </summary>
    private static async Task StartThenReportAsync(
        PlaybackSession session, PlayerProgressSample sample, bool force)
    {
        await PlaybackProgressReporting.StartThenProgressAsync(
            () => App.Services.Playback.NotifyStartedAsync(session, sample),
            () => App.Services.Playback.OnProgressAsync(session, sample, force));
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

        // The item that finished, captured before any await: the user can switch items while the
        // series lookup is in flight, and a lookup that answers after that must not take the player
        // to an episode of a title it is no longer playing.
        PlaybackRequest? finishedRequest = _request;

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

        if (finishedRequest?.ServerId is not { } serverId || finishedRequest.ItemId is not { } itemId)
            return;

        try
        {
            ProviderHandle? handle = App.Services.Servers.FindHandle(serverId);
            if (handle?.Library is null) return;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));

            // Most requests now carry the series, but the player must not turn "this request predates
            // that field" into "this item has no next episode": the series is looked up from the item
            // itself before auto-advance is given up on.
            string? seriesId = finishedRequest.SeriesId;
            if (string.IsNullOrEmpty(seriesId) && handle.Detail is not null)
            {
                MediaDetail detail = await handle.Detail.GetItemDetailAsync(itemId, timeout.Token);
                seriesId = detail.Item.SeriesId;
            }
            if (!ReferenceEquals(_request, finishedRequest))
            {
                Services.StartupTrace.Write("queue:auto-advance-abandoned reason=item-changed");
                return;
            }
            if (string.IsNullOrEmpty(seriesId))
            {
                Services.StartupTrace.Write("queue:no-series");
                return;
            }

            Episode? next = await handle.Library.GetNextEpisodeAsync(seriesId, itemId, timeout.Token);
            if (!ReferenceEquals(_request, finishedRequest))
            {
                Services.StartupTrace.Write("queue:auto-advance-abandoned reason=item-changed");
                return;
            }
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
        _statusSticky = true;
        LoadingRing.IsActive = false;
        StatusPanel.Visibility = Visibility.Visible;
        StatusText.Text = message;
        App.Services.PlayerHost.HideSurface();
    }

    // ---------------------------------------------------------------- keyboard

    // ---------------------------------------------------------------- brightness / volume

    /// <summary>Lowest picture brightness the user can reach, so the screen can never go full black.</summary>
    private const double MinBrightness = 15;

    private const double BrightnessStep = 5;
    private const double VolumeStep = 5;
    private const double KeySeekSeconds = 5;

    /// <summary>Last value applied, kept here so key repeat does not read a stale engine snapshot.</summary>
    private double _volume = 100;

    /// <summary>
    /// Up/Down go to the half of the picture the pointer is over. Mirrors the wheel, so one gesture
    /// means the same thing whichever way the user reached for it.
    /// </summary>
    private void AdjustByPointerHalf(double direction)
    {
        // Off-window gestures are ignored rather than falling back to volume: the pointer half is the
        // only thing that decides what this key means, so with the cursor parked on another screen
        // there is no correct target. Doing nothing is also what the wheel does outside the picture.
        if (!TryGetPointerHalf(out bool leftHalf))
        {
            App.Services.Log.Debug("player", "方向键调整：指针不在播放器窗口内 → 忽略");
            return;
        }

        App.Services.Log.Debug("player", "方向键调整：" + (leftHalf ? "指针在左半屏 → 亮度" : "指针在右半屏 → 音量"));
        if (leftHalf) AdjustBrightness(direction);
        else AdjustVolume(direction);
    }

    private void OnWheel(bool leftHalf, double direction)
    {
        App.Services.Log.Debug("player", "滚轮：" + (leftHalf ? "左半屏 → 亮度" : "右半屏 → 音量")
            + (direction > 0 ? " 向上" : " 向下"));
        if (leftHalf) AdjustBrightness(direction);
        else AdjustVolume(direction);
    }

    /// <summary>True when the cursor is over the player window, with which half it is on.</summary>
    /// <remarks>
    /// The whole client area decides this, not just the video rectangle: the pointer over the control
    /// bar still counts as "in the player", and the halves are the window's halves. That keeps the
    /// keyboard rule usable in a windowed layout where the picture does not span the full width.
    /// </remarks>
    private bool TryGetPointerHalf(out bool leftHalf)
    {
        leftHalf = true;
        if (!WindowInterop.GetCursorPos(out WindowInterop.POINT point)) return false;

        IntPtr window = App.Services.PlayerHost.WindowHandle;
        if (!WindowInterop.ScreenToClient(window, ref point)) return false;
        if (!WindowInterop.GetClientRect(window, out var client) || client.Width <= 0 || client.Height <= 0) return false;

        bool inside = point.X >= 0 && point.X < client.Width
            && point.Y >= 0 && point.Y < client.Height;
        if (!inside) return false;

        leftHalf = point.X < client.Width / 2;
        return true;
    }

    private void AdjustBrightness(double direction)
    {
        double target = Math.Clamp(_brightness + direction * BrightnessStep, MinBrightness, 100);
        if (Math.Abs(target - _brightness) < 0.001) return;

        _brightness = target;
        App.Services.PlayerHost.Player?.SetBrightness(target);
        App.Services.Log.Info("player", "亮度 " + (int)Math.Round(target));
        ShowOsd("亮度 " + (int)Math.Round(target));
        QueueSettingsSave(brightness: target);
    }

    private void AdjustVolume(double direction)
    {
        var player = App.Services.PlayerHost.Player;
        if (player is null) return;

        double target = Math.Clamp(_volume + direction * VolumeStep, 0, 200);
        if (Math.Abs(target - _volume) < 0.001) return;

        _volume = target;
        player.SetVolume(target);
        App.Services.Log.Info("player", "音量 " + (int)Math.Round(target));
        ShowOsd("音量 " + (int)Math.Round(target));
        QueueSettingsSave(volume: target);
    }

    /// <summary>Shows the value as engine-drawn text over the picture.</summary>
    /// <remarks>
    /// The shell cannot draw this itself: the embedded engine owns a native child window over the
    /// picture rectangle, and the XAML island is composited below it, so a border or text block placed
    /// there is never visible. The engine's own OSD is rendered into the video output, which is why it
    /// works with the control bar hidden and in fullscreen.
    /// </remarks>
    private void ShowOsd(string text) => App.Services.PlayerHost.Player?.ShowMessage(text);

    private void QueueSettingsSave(double? volume = null, double? brightness = null)
    {
        if (volume is { } v) _pendingVolume = v;
        if (brightness is { } b) _pendingBrightness = b;
        _settingsSaveTimer.Stop();
        _settingsSaveTimer.Start();
    }

    private void FlushPendingSettings()
    {
        _settingsSaveTimer.Stop();
        if (_pendingVolume is null && _pendingBrightness is null) return;

        double? volume = _pendingVolume;
        double? brightness = _pendingBrightness;
        _pendingVolume = null;
        _pendingBrightness = null;

        App.Services.Settings.Update(s =>
        {
            if (volume is { } v) s.DefaultVolume = v;
            if (brightness is { } b) s.PlayerBrightness = b;
        });
    }

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
                // The position is logged with the step: one line per press proves the handler ran once,
                // which is what tells a real double-seek apart from a measurement error.
                App.Services.Log.Debug("player", "方向键定位 -5 秒（" + PositionText.Text + "）");
                player?.SeekRelative(-KeySeekSeconds);
                _flushProgressNextTick = true;
                e.Handled = true;
                break;
            case VirtualKey.Right:
                App.Services.Log.Debug("player", "方向键定位 +5 秒（" + PositionText.Text + "）");
                player?.SeekRelative(KeySeekSeconds);
                _flushProgressNextTick = true;
                e.Handled = true;
                break;
            case VirtualKey.Up:
                AdjustByPointerHalf(1);
                e.Handled = true;
                break;
            case VirtualKey.Down:
                AdjustByPointerHalf(-1);
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
            case VirtualKey.F1:
            // "?" is Shift+Oem2 on a US layout; the virtual-key value is layout-dependent, so both
            // the raw OEM key and F1 are accepted and the sheet says so.
            case (VirtualKey)191:
                OnShortcutsClick(ShortcutsButton, new RoutedEventArgs());
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
            {
                player.SeekAbsolute(SeekSlider.Value * player.Snapshot.DurationSeconds);
                _flushProgressNextTick = true;
            }
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

    private sealed record VersionRow(string MediaSourceId, string Label, bool TranscodeOnly)
    {
        public override string ToString() => Label;
    }

    private sealed record InfoRow(string Label, string Value);
}

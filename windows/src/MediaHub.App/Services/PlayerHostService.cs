using MediaHub.App.Interop;
using MediaHub.Net;
using MediaHub.Player;
using MediaHub.Player.Mpv;

namespace MediaHub.App.Services;

/// <summary>
/// Owns the single engine instance and the native video surface for the process lifetime.
/// </summary>
/// <remarks>
/// <para>
/// The engine is intentionally not per-page: re-creating it on every navigation would drop the
/// hardware-decoder and swapchain state, and would add hundreds of milliseconds to each entry into
/// the player. The page binds to this host and detaches on unload.
/// </para>
/// <para>
/// The engine is configured with an explicit proxy value derived from the active
/// <see cref="NetworkPolicy"/> on every load. Left unset it consults the ambient <c>http_proxy</c> and
/// <c>NO_PROXY</c> variables, which would make the media route depend on the user's shell environment
/// rather than on the configured policy.
/// </para>
/// </remarks>
public sealed class PlayerHostService : IAsyncDisposable
{
    private readonly SettingsStore _settings;
    private readonly LogService _log;

    private MpvVideoSurface? _surface;
    private MpvPlayer? _player;
    private IntPtr _windowHandle;

    public PlayerHostService(SettingsStore settings, LogService log)
    {
        _settings = settings;
        _log = log;
    }

    public MpvPlayer? Player => _player;
    public MpvVideoSurface? Surface => _surface;
    public bool IsInitialized => _player is not null;
    public ActivePlayback? Current { get; private set; }

    public event EventHandler<PlayerSnapshot>? SnapshotChanged;

    /// <summary>Creates the surface and engine. Must run on the UI thread; the window must exist.</summary>
    public void Initialize(IntPtr windowHandle)
    {
        if (_player is not null) return;
        _windowHandle = windowHandle;

        _surface = new MpvVideoSurface(windowHandle);

        string inputConf = Path.Combine(AppContext.BaseDirectory, "Assets", "input.conf");
        var options = new MpvPlayerOptions
        {
            Wid = _surface.HostHandle,
            InputConf = File.Exists(inputConf) ? inputConf : null,
            HttpProxy = NetworkPolicy.FollowSystem.ToEngineProxyValue(new Uri("http://localhost")),
        };

        var extra = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (k, v) in _settings.Current.EngineOptions) extra[k] = v;

        _player = new MpvPlayer(options with { ExtraOptions = extra });
        _player.SnapshotChanged += (_, snap) => SnapshotChanged?.Invoke(this, snap);

        _log.Info("player", "播放引擎已初始化：libmpv client API " + MpvPlayer.ClientApiVersion
            + "，输出 " + options.VideoOutput + "/" + options.GpuContext + "，硬解 " + options.HwDec
            + "，代理 " + (string.IsNullOrEmpty(options.HttpProxy) ? "直连" : options.HttpProxy));

        foreach (string problem in _player.OptionErrors)
        {
            // A rejected option is silent at runtime; surfacing it here is the only way to notice that
            // the engine is not configured the way it was asked to be.
            _log.Error("player", "引擎拒绝了选项：" + problem);
        }
    }

    /// <summary>Applies subtitle preferences that can change without restarting playback.</summary>
    public void ApplySubtitleSettings()
    {
        if (_player is null) return;
        var s = _settings.Current;
        _player.SetSubtitleVisible(s.SubtitlesEnabled);
        _player.SetSubtitleFontSize(s.SubtitleFontSize);
        _player.SetSubtitleFont(s.SubtitleFont);
        _player.SetSubtitleColor(s.SubtitleColor);
        _player.SetSubtitleBorderSize(s.SubtitleBorderSize);
        _player.SetSubtitlePosition(s.SubtitlePosition);
        _player.SetSubtitleDelay(s.SubtitleDelaySeconds);
        _player.SetSubtitleAssOverride(s.SubtitleAssOverride switch
        {
            "yes" => SubtitleAssOverride.Yes,
            "force" => SubtitleAssOverride.Force,
            "scale" => SubtitleAssOverride.Scale,
            _ => SubtitleAssOverride.No,
        });
    }

    /// <summary>Loads a resolved playback session into the engine.</summary>
    public async Task PlayAsync(ActivePlayback playback, CancellationToken ct = default)
    {
        if (_player is null) throw new InvalidOperationException("PlayerHostService.Initialize must be called first.");
        Current = playback;

        _player.SetDeclaredDuration(playback.DeclaredDuration);
        var s = _settings.Current;

        // Headers are deliberately empty. Credentials live with the bridge that fetches the stream, so
        // the engine holds nothing that could be forwarded to a redirect target.
        var settings = new PlaybackSettings
        {
            StartPositionTicks = playback.StartPositionTicks,
            Headers = new Dictionary<string, string>(),
            Volume = s.DefaultVolume,
            Speed = s.DefaultSpeed,
            SubtitleVisible = s.SubtitlesEnabled,
        };

        await _player.LoadAsync(playback.EngineUrl.ToString(), settings, ct).ConfigureAwait(true);
        ApplySubtitleSettings();

        foreach (string sub in playback.ExternalSubtitles)
        {
            try { _player.AddExternalSubtitle(sub); }
            catch (Exception ex) { _log.Warn("player", "外挂字幕加载失败：" + Path.GetFileName(sub) + " — " + ex.GetType().Name); }
        }

        _log.Info("player", "开始播放：" + LogService.Redact(playback.DisplayUrl));
    }

    /// <summary>Repositions the video surface. Coordinates are DIPs in the window's client space.</summary>
    public void SetSurfaceBounds(double x, double y, double width, double height)
        => _surface?.SetBounds(x, y, width, height);

    public void ShowSurface() => _surface?.Show();
    public void HideSurface() => _surface?.Hide();

    public (int X, int Y, int Width, int Height) PhysicalBounds => _surface?.PhysicalBounds ?? (0, 0, 0, 0);

    public IntPtr WindowHandle => _windowHandle;

    public async ValueTask DisposeAsync()
    {
        if (_player is not null)
        {
            await _player.DisposeAsync().ConfigureAwait(false);
            _player = null;
        }
        _surface?.Dispose();
        _surface = null;
    }
}

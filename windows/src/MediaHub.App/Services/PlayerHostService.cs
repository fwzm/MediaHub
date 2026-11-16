using MediaHub.App.Interop;
using MediaHub.Domain;
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
    private readonly PlayerCacheStore _cache;

    private MpvVideoSurface? _surface;
    private MpvPlayer? _player;
    private IntPtr _windowHandle;
    private int _colorGeneration;

    public PlayerHostService(SettingsStore settings, LogService log, PlayerCacheStore cache)
    {
        _settings = settings;
        _log = log;
        _cache = cache;
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
        // Measured against a real Jellyfin server (16 s source whose remux cannot be split): the HLS
        // demuxer opens the first two segments over two connections at once (http_multiple=auto). The
        // server remuxes on demand, so the first response delivers headers and then no body while the
        // second connection waits on the same encoder - the engine gives up after 20 s with "Error when
        // loading first segment" while a sequential request for the same URL completes in ~0.4 s. One
        // connection at a time removes the deadlock; the option is ignored by every other demuxer.
        extra["demuxer-lavf-o"] = extra.TryGetValue("demuxer-lavf-o", out var lavf) && !string.IsNullOrWhiteSpace(lavf)
            ? lavf + ",http_multiple=0"
            : "http_multiple=0";

        // The engine's own log is where the video output records the swapchain colour space it
        // actually configured. That line is the difference between "HDR looks right" and "HDR is
        // being passed through", and it cannot be observed from the UI, so it is always captured.
        if (!extra.ContainsKey("log-file"))
        {
            string logDirectory = _log.FileSink?.DirectoryPath
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "logs");
            Directory.CreateDirectory(logDirectory);
            extra["log-file"] = Path.Combine(logDirectory, "mpv.log");
        }
        // Verbose only for the video output; the rest of the engine stays at its normal level.
        if (!extra.ContainsKey("msg-level"))
        {
            extra["msg-level"] = "vo/gpu-next/d3d11=v,vo=v";
        }

        _player = new MpvPlayer(options with
        {
            ExtraOptions = extra,
            HdrDynamicMetadata = MpvColorPipeline.Parse(_settings.Current.HdrDynamicMetadata),
        });
        _player.SnapshotChanged += (_, snap) => SnapshotChanged?.Invoke(this, snap);
        // Brightness is a property of the engine, not of the file, so it is restored once here rather
        // than per item: switching episodes must not reset what the user dialled in.
        _player.SetBrightness(_settings.Current.PlayerBrightness);
        ApplyPreferredAudioDevice();

        _log.Info("player", "播放引擎已初始化：libmpv client API " + MpvPlayer.ClientApiVersion
            + "，输出 " + options.VideoOutput + "/" + options.GpuContext + "，硬解 " + options.HwDec
            + "，HDR 动态元数据 " + MpvColorPipeline.Describe(MpvColorPipeline.Parse(_settings.Current.HdrDynamicMetadata))
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
        _player.SetSubtitleBold(s.SubtitleBold);
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

    // ---------------------------------------------------------------- audio output device

    /// <summary>Devices the engine can see right now; empty before the engine exists.</summary>
    public IReadOnlyList<EngineAudioDevice> GetAudioDevices()
        => _player?.GetAudioDevices() ?? Array.Empty<EngineAudioDevice>();

    /// <summary>The device the engine reports using ("auto" means the system default).</summary>
    public string? CurrentAudioDevice => _player?.CurrentAudioDevice;

    /// <summary>
    /// A label for a device name that a person can read. Engine names are endpoint ids, so the
    /// description is what belongs in a status line; an unknown id is shown as-is rather than hidden.
    /// </summary>
    public string DescribeAudioDevice(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || string.Equals(name, "auto", StringComparison.OrdinalIgnoreCase))
            return "系统默认";
        return GetAudioDevices()
            .FirstOrDefault(d => string.Equals(d.Name, name, StringComparison.OrdinalIgnoreCase))?.Description ?? name;
    }

    /// <summary>Last reason a preferred device could not be used, for the settings page to show.</summary>
    public string? AudioDeviceFallback { get; private set; }

    /// <summary>
    /// Switches the output and keeps the preference. A device that has been unplugged is a normal
    /// condition, not an exception: the engine is put back on the system default and the caller is
    /// told, so the UI can explain what happened instead of pretending the switch worked.
    /// </summary>
    public bool TrySetAudioDevice(string? name)
    {
        if (_player is null) return false;
        string? previous = _player.CurrentAudioDevice;
        if (_player.TrySetAudioDevice(name)) { AudioDeviceFallback = null; return true; }

        _player.TrySetAudioDevice(previous);
        AudioDeviceFallback = string.IsNullOrWhiteSpace(name)
            ? "切换音频设备失败，仍使用系统默认输出。"
            : "音频设备“" + name + "”当前不可用，已回落到原来的输出。";
        _log.Warn("player", AudioDeviceFallback);
        return false;
    }

    private void ApplyPreferredAudioDevice()
    {
        if (_player is null) return;
        string? preferred = _settings.Current.AudioDevice;
        if (string.IsNullOrWhiteSpace(preferred)) return;
        if (_player.TrySetAudioDevice(preferred)) { AudioDeviceFallback = null; return; }
        _player.TrySetAudioDevice(null);
        AudioDeviceFallback = "音频设备“" + preferred + "”不可用，已回落为系统默认输出。";
        _log.Warn("player", AudioDeviceFallback);
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
            EngineOptions = BuildCacheOptions(),
        };

        await _player.LoadAsync(EngineTarget(playback.EngineUrl), settings, ct).ConfigureAwait(true);
        ApplySubtitleSettings();

        foreach (string sub in playback.ExternalSubtitles)
        {
            try { _player.AddExternalSubtitle(sub); }
            catch (Exception ex) { _log.Warn("player", "外挂字幕加载失败：" + Path.GetFileName(sub) + " — " + ex.GetType().Name); }
        }

        // Tracks the source offered for this item. They join the picker without overriding a track
        // the user already chose or the container's own default.
        foreach (string sub in playback.ServerSubtitles)
        {
            try { _player.AddExternalSubtitle(sub, select: false); }
            catch (Exception ex) { _log.Warn("player", "服务端字幕加载失败：" + Path.GetFileName(sub) + " — " + ex.GetType().Name); }
        }

        _log.Info("player", "色彩管线：" + DescribeColorPipeline(_player));
        ReportColorPipelineSoon(playback.DisplayUrl);
        // Printable per-session identity: which version was resolved and which subtitle tracks belong
        // to it. A version switch that silently kept the previous subtitle list would show up here as
        // a language that is not in the new source.
        PlaybackSource? resolved = playback.Source;
        _log.Info("player", "播放源：mediaSource=" + (resolved?.MediaSourceId ?? "-")
            + " 字幕轨道=" + DescribeSubtitleStreams(resolved));
        _log.Info("player", "开始播放：" + LogService.Redact(playback.DisplayUrl));
        if (s.DiskCacheEnabled)
        {
            _log.Info("player", "磁盘缓存：目录=" + _cache.DirectoryPath
                + " 预算=" + s.DiskCacheCapacityMB + "MB 当前占用=" + FormatBytes(_cache.GetUsageBytes()));
        }
    }

    /// <summary>
    /// Options that put the engine's readahead on disk. They are applied per load because the
    /// capacity is a user setting: changing it must take effect on the next item, not on restart.
    /// </summary>
    private Dictionary<string, string> BuildCacheOptions()
    {
        var s = _settings.Current;
        var options = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            // mpv 0.41 names this option demuxer-cache-dir; the legacy cache-dir name is an
            // unknown option here and would be dropped without an error, sending the files to the
            // engine's default temp location instead of the directory the settings page reports.
            ["demuxer-cache-dir"] = _cache.DirectoryPath,
            ["cache-on-disk"] = s.DiskCacheEnabled ? "yes" : "no",
            // mpv's default is "immediate": the cache file is deleted straight after creation, so
            // the directory the settings page measures is always empty and the cleanup policy has
            // nothing to act on. "whendone" keeps the file for the length of the playback and
            // removes it when the file is closed; "no" leaves it for the manual cleanup.
            ["demuxer-cache-unlink-files"] = s.ClearDiskCacheOnPlaybackEnd ? "whendone" : "no",
        };
        if (!s.DiskCacheEnabled) return options;

        long budget = Math.Max(64, s.DiskCacheCapacityMB) * 1024L * 1024L;
        options["demuxer-max-bytes"] = budget.ToString(System.Globalization.CultureInfo.InvariantCulture);
        // Keep a quarter of the budget behind the playhead so a short back-seek is served locally.
        options["demuxer-max-back-bytes"] = (budget / 4).ToString(System.Globalization.CultureInfo.InvariantCulture);
        return options;
    }

    private static string FormatBytes(long bytes)
        => bytes >= 1024L * 1024 * 1024
            ? (bytes / 1024.0 / 1024 / 1024).ToString("0.0") + " GB"
            : (bytes / 1024.0 / 1024).ToString("0.0") + " MB";

    /// <summary>
    /// Appends the next episode to the engine playlist without interrupting the current one. mpv
    /// prefetches appended entries, so the actual switch reuses what was already read.
    /// </summary>
    public void PreloadNext(ActivePlayback playback)
    {
        if (_player is null) throw new InvalidOperationException("PlayerHostService.Initialize must be called first.");
        _player.AppendToPlaylist(EngineTarget(playback.EngineUrl));
        _log.Info("player", "下一集预加载：playlist=" + _player.PlaylistCount
            + " url=" + LogService.Redact(playback.DisplayUrl));
    }

    /// <summary>
    /// Switches to the entry <see cref="PreloadNext"/> appended, queueing its sidecars for the file
    /// that is about to load rather than for the one still playing.
    /// </summary>
    public async Task PlayPreloadedAsync(ActivePlayback playback, CancellationToken ct = default)
    {
        if (_player is null) throw new InvalidOperationException("PlayerHostService.Initialize must be called first.");
        Current = playback;
        _player.SetDeclaredDuration(playback.DeclaredDuration);
        var s = _settings.Current;
        var settings = new PlaybackSettings
        {
            StartPositionTicks = playback.StartPositionTicks,
            Headers = new Dictionary<string, string>(),
            Volume = s.DefaultVolume,
            Speed = s.DefaultSpeed,
            SubtitleVisible = s.SubtitlesEnabled,
            EngineOptions = BuildCacheOptions(),
        };

        _player.PreparePlaylistNext(playback.ExternalSubtitles, playback.ServerSubtitles);
        _player.PlaylistNext(settings);
        ApplySubtitleSettings();
        _log.Info("player", "切换到预加载条目：playlist=" + _player.PlaylistCount
            + " 源=" + (playback.Source?.MediaSourceId ?? "-"));
        await Task.CompletedTask.ConfigureAwait(true);
    }

    /// <summary>
    /// The string handed to the engine for a resolved source.
    /// </summary>
    /// <remarks>
    /// A UNC path turns into <c>file://host/share/...</c> when parsed as a <see cref="Uri"/>, and the
    /// engine refuses that authority form on Windows ("loading failed" while the same file opens from
    /// Explorer). The redirector speaks raw UNC, so the authority is folded back into the path. A local
    /// drive path has no authority and is passed through unchanged.
    /// </remarks>
    private static string EngineTarget(Uri url)
    {
        if (!url.IsFile || string.IsNullOrEmpty(url.Host)) return url.ToString();
        return @"\\" + url.Host + Uri.UnescapeDataString(url.AbsolutePath).Replace('/', '\\');
    }

    private static string DescribeSubtitleStreams(PlaybackSource? source)
    {
        if (source is null) return "-";
        List<string> tracks = source.Streams
            .Where(s => s.Type == "subtitle")
            .Select(s => (s.Language ?? "?") + "/" + (s.Codec ?? "?") + (s.IsExternal ? "/外挂" : ""))
            .ToList();
        return tracks.Count == 0 ? "无" : string.Join(",", tracks);
    }

    /// <summary>
    /// Logs the colour pipeline again once the first frames have been rendered.
    /// </summary>
    /// <remarks>
    /// Immediately after a load the engine has the source parameters but has not yet configured an
    /// output, so the first line reports <c>out=-</c>. The output parameters and the tone-mapping
    /// decision only exist after the first frame reaches the video output, which is also the moment
    /// they are worth recording.
    /// </remarks>
    private void ReportColorPipelineSoon(string displayUrl)
    {
        int generation = Interlocked.Increment(ref _colorGeneration);
        _ = Task.Run(async () =>
        {
            await Task.Delay(1500).ConfigureAwait(false);
            if (Volatile.Read(ref _colorGeneration) != generation || _player is null) return;
            _log.Info("player", "色彩管线(已出画)：" + DescribeColorPipeline(_player) + " url=" + LogService.Redact(displayUrl));
        });
    }

    /// <summary>
    /// One line describing how the engine is translating the source colours to the output.
    /// </summary>
    /// <remarks>
    /// "HDR works" is not observable from the picture alone: a display that passes HDR through and
    /// one that silently clips it look different only to a colorimeter. The source parameters, the
    /// output parameters and the tone-mapping mode together are the printable evidence, and they are
    /// also the only way to tell whether a regression comes from the file, the decoder or the display.
    /// </remarks>
    private static string DescribeColorPipeline(MpvPlayer player)
    {
        string V(string property) => player.GetProperty(property) ?? "-";
        string matrix = V("video-params/colormatrix");
        // Dolby Vision is not a separate property in this engine: profile 5/8 surfaces as the
        // "dolbyvision" colour matrix, and the per-frame L1 values are what prove the RPU was parsed
        // rather than the stream being treated as plain HDR10.
        string dovi = string.Equals(matrix, "dolbyvision", StringComparison.OrdinalIgnoreCase)
            ? "dolby-vision"
            : "no";
        return "src=" + V("video-params/primaries") + "/" + V("video-params/gamma") + "/"
            + matrix + "/" + V("video-params/colorlevels")
            + " sig-peak=" + V("video-params/sig-peak")
            + " dv=" + dovi
            + " max-pq-y=" + V("video-params/max-pq-y") + " avg-pq-y=" + V("video-params/avg-pq-y")
            + " | out=" + V("video-out-params/primaries") + "/" + V("video-out-params/gamma")
            + " sig-peak=" + V("video-out-params/sig-peak")
            + " | tone-mapping=" + V("tone-mapping") + " target-peak=" + V("target-peak")
            + " hdr-compute-peak=" + V("hdr-compute-peak")
            + " hint=" + V("target-colorspace-hint")
            + " hwdec=" + V("hwdec-current") + " vo=" + V("current-vo") + "/" + V("gpu-context");
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

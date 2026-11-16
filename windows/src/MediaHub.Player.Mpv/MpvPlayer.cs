using System.Diagnostics;
using System.Runtime.InteropServices;
using MediaHub.Player;

namespace MediaHub.Player.Mpv;

/// <summary>
/// libmpv-backed playback engine and track controller for Windows.
/// </summary>
/// <remarks>
/// <para><b>Threading contract.</b> Two rules drive the whole design, both from the libmpv client
/// contract:</para>
/// <list type="number">
/// <item>Exactly one thread may sit in <c>mpv_wait_event</c>. That is the event thread.</item>
/// <item>No mpv API call may be made while handling an event. mpv delivers events while holding
/// internal state, and calling back into the library from there can deadlock — which is silent and
/// looks exactly like "playback works but the UI never updates".</item>
/// </list>
/// <para>
/// The event thread therefore does nothing except decode the payload already handed to it in the
/// event struct, latch primitive fields, and set flags. A separate publisher tick performs all
/// property reads, issues any deferred command, and raises <see cref="SnapshotChanged"/>.
/// </para>
/// <para><b>Single source of truth.</b> The UI renders from <see cref="Snapshot"/> only. Because mpv
/// also accepts its own keyboard and mouse bindings on the video window, reflecting state back
/// through property observation is what keeps the XAML chrome correct in both input paths.</para>
/// </remarks>
public sealed class MpvPlayer : IPlaybackEngine, ITrackController, IAudioDeviceController, IDisposable
{
    private readonly MpvPlayerOptions _options;
    private readonly object _stateLock = new();
    private readonly object _commandLock = new();
    private readonly Thread _eventThread;
    private readonly CancellationTokenSource _shutdown = new();

    private IntPtr _ctx;
    private volatile bool _disposed;
    private volatile bool _initialized;
    private PlayerSnapshot _snapshot = PlayerSnapshot.Empty;

    private long _pendingStartPositionTicks;
    private long _pendingDurationTicks;
    private int _observedTrackCount = -1;

    // ---- flags written by the event thread, consumed by the publisher tick ----
    private int _dirty = 1;
    private int _tracksDirty = 1;
    private int _fileLoaded;
    private bool _fileReady;
    private readonly List<(string Path, bool Select)> _pendingExternalSubtitles = new();
    private readonly object _subtitleQueueLock = new();
    private int _endFileReason = -1;
    private int _endFileError;
    private string? _errorText;
    private volatile bool _loaded;

    // ---- engine log tail, written by the event thread, read by the publisher tick ----
    private readonly object _logTailLock = new();
    private readonly Queue<string> _logTail = new();
    private const int LogTailCapacity = 8;

    // ---- cached values written by the event thread from the event payload ----
    private double _positionSeconds;
    private double _durationSeconds;
    private bool _paused;
    private double _volume = 100;
    private bool _muted;
    private double _speed = 1.0;

    /// <summary>Picture brightness on the 0-100 UI scale; 50 is neutral.</summary>
    private double _brightness = 50;
    private bool _eof;
    private bool _buffering;
    private bool _subtitleVisible = true;

    // ---- track / state caches written only by the publisher tick ----
    private IReadOnlyList<EngineAudioTrack> _audioTracks = Array.Empty<EngineAudioTrack>();
    private IReadOnlyList<EngineSubtitleTrack> _subtitleTracks = Array.Empty<EngineSubtitleTrack>();
    private IReadOnlyList<EngineVideoTrack> _videoTracks = Array.Empty<EngineVideoTrack>();
    private long? _selectedAudioId;
    private long? _selectedSubtitleId;
    private long? _selectedVideoId;
    private int? _width;
    private int? _height;
    private double? _fps;
    private TechnicalInfo _technical = new();

    private readonly Timer _publisher;

    public MpvPlayer(MpvPlayerOptions options)
    {
        _options = options;

        _ctx = MpvNative.mpv_create();
        if (_ctx == IntPtr.Zero)
            throw new InvalidOperationException("mpv_create() returned NULL; libmpv-2.dll could not be initialised.");

        ApplyOptions();
        int rc = MpvNative.mpv_initialize(_ctx);
        if (rc < 0)
        {
            string err = MpvNative.ErrorString(rc);
            MpvNative.mpv_terminate_destroy(_ctx);
            _ctx = IntPtr.Zero;
            throw new InvalidOperationException($"mpv_initialize() failed: {err}");
        }
        _initialized = true;

        // The end-file error is a single generic string ("loading failed") for almost every failure
        // mode, so the real reason (HTTP 401, a decoder the GPU cannot initialise, a video output that
        // could not open) has to be read from the engine's own log. Errors and warnings are few, and
        // the last few lines are kept only to explain a failure; they are never written to the app log
        // verbatim because they can contain the stream URL.
        MpvNative.mpv_request_log_messages(_ctx, "warn");

        // Two traps live here.
        //
        // 1. The format passed to mpv_observe_property is not the same concept as the one passed to
        //    mpv_set_property. For observation, MPV_FORMAT_NONE means "tell me that it changed but
        //    give me no value" — the event carries a NULL data pointer. Observing with NONE looks
        //    like it works and silently yields nothing, so each property is declared with its real
        //    native format instead.
        // 2. Observing the same name twice with the same reply_userdata *removes* the observation,
        //    because mpv treats a repeat as a toggle. Hence the set below is deduplicated.
        var observed = new HashSet<string>(StringComparer.Ordinal);
        foreach (var (name, format) in ObservedProperties)
        {
            if (!observed.Add(name)) continue;
            int observeResult = MpvNative.mpv_observe_property(_ctx, 0, name, format);
            if (observeResult < 0) _observeErrors.Add(name + ": " + MpvNative.ErrorString(observeResult));
        }

        _eventThread = new Thread(EventLoop)
        {
            IsBackground = true,
            Name = "MediaHub.Mpv.EventLoop",
        };
        _eventThread.Start();

        // 10 Hz keeps the transport clock smooth without flooding the UI thread; the publisher is
        // also the only place that touches the mpv API, so a slow call can never stall event
        // delivery for the engine.
        _publisher = new Timer(_ => PublisherTick(), null, 120, 100);
    }

    /// <summary>Loaded libmpv client API version, for the diagnostics panel.</summary>
    public static string ClientApiVersion
    {
        get
        {
            try
            {
                long raw = MpvNative.mpv_client_api_version().ToInt64();
                return $"{(int)(raw >> 32)}.{(int)(raw & 0xFFFFFFFF)}";
            }
            catch (DllNotFoundException) { return "unavailable"; }
            catch (EntryPointNotFoundException) { return "unavailable"; }
        }
    }

    public PlayerSnapshot Snapshot { get { lock (_stateLock) return _snapshot; } }

    public event EventHandler<PlayerSnapshot>? SnapshotChanged;

    /// <summary>Property name paired with the format the event must be decoded as.</summary>
    private static readonly (string Name, int Format)[] ObservedProperties =
    {
        ("time-pos", MpvNative.MPV_FORMAT_DOUBLE),
        ("duration", MpvNative.MPV_FORMAT_DOUBLE),
        ("pause", MpvNative.MPV_FORMAT_FLAG),
        ("eof-reached", MpvNative.MPV_FORMAT_FLAG),
        ("paused-for-cache", MpvNative.MPV_FORMAT_FLAG),
        ("sub-visibility", MpvNative.MPV_FORMAT_FLAG),
        ("track-list/count", MpvNative.MPV_FORMAT_INT64),
        ("volume", MpvNative.MPV_FORMAT_DOUBLE),
        ("speed", MpvNative.MPV_FORMAT_DOUBLE),
    };

    private readonly List<string> _optionErrors = new();
    private readonly List<string> _observeErrors = new();

    /// <summary>Options the engine refused. Non-empty means the engine is not configured as intended.</summary>
    public IReadOnlyList<string> OptionErrors => _optionErrors;

    /// <summary>Non-empty when a property could not be observed; surfaced by the diagnostics panel.</summary>
    public IReadOnlyList<string> ObserveErrors => _observeErrors;

    private void ApplyOptions()
    {
        // The return code matters: an unknown or misspelled option is accepted silently by mpv and
        // simply does nothing. A typo in a network option would therefore disable a security control
        // without any visible symptom, so every rejected option is recorded and surfaced.
        void Opt(string k, string v)
        {
            int result = MpvNative.mpv_set_option_string(_ctx, k, v);
            if (result < 0) _optionErrors.Add(k + ": " + MpvNative.ErrorString(result));
        }

        // Do not read the user's mpv.conf: MediaHub behaviour must be reproducible.
        Opt("config", "no");
        Opt("terminal", "no");
        Opt("input-default-bindings", "yes");
        Opt("input-vo-keyboard", "yes");
        // OSC is a Lua script compiled into mpv, not a C option. The LGPL engine we ship is built with
        // -Dlua=disabled (no liblua without adding a dependency), so the option does not exist there.
        // A build without OSC has nothing to disable, so "option not found" is not a configuration
        // defect; every other error is still reported like any other rejected option.
        int oscResult = MpvNative.mpv_set_option_string(_ctx, "osc", _options.Osc ? "yes" : "no");
        if (oscResult < 0 && oscResult != MpvNative.MPV_ERROR_OPTION_NOT_FOUND)
        {
            _optionErrors.Add("osc: " + MpvNative.ErrorString(oscResult));
        }
        Opt("osd-level", _options.OsdLevel.ToString());
        Opt("keep-open", _options.KeepOpen ? "yes" : "no");
        Opt("idle", _options.Idle ? "yes" : "no");
        Opt("force-window", "no");
        Opt("vo", _options.VideoOutput);
        Opt("gpu-api", "d3d11");
        Opt("gpu-context", _options.GpuContext);
        Opt("hwdec", _options.HwDec);
        // Dolby Vision reshaping, HDR10+ scene metadata and the HDR/SDR decision all happen inside
        // libplacebo before this stage. Pin the pipeline explicitly so a future engine default cannot
        // silently change how a DV title is mapped; the user's "HDR 动态元数据处理" choice is the only
        // value that differs from the validated defaults.
        foreach (var (key, value) in MpvColorPipeline.BuildOptions(_options.HdrDynamicMetadata)) Opt(key, value);
        Opt("cursor-autohide", "1000");
        Opt("stop-screensaver", "yes");
        Opt("audio-exclusive", "no");
        // Sidecar subtitles are resolved and added by the app, which also decides which one to select.
        // The engine's own exact-match loader would add the same file a second time, so a single .srt
        // next to the video used to appear twice in the track picker.
        Opt("sub-auto", "no");
        if (!string.IsNullOrEmpty(_options.AudioOutput)) Opt("ao", _options.AudioOutput);
        Opt("volume-max", "200");
        Opt("msg-level", "all=warn");

        // Transient feedback (volume/brightness) has to be drawn by the engine, not by the shell: the
        // embedded engine owns a native child window over the picture, so anything the XAML island
        // draws there is covered. These options give that text the same restrained look as the shell.
        Opt("osd-font", "Microsoft YaHei UI");
        Opt("osd-font-size", "26");
        Opt("osd-color", "#FFFFFFFF");
        // A filled box rather than a hard outline: the shell uses dark translucent capsules for
        // transient feedback, and an outline-only OSD reads as an engine message instead.
        Opt("osd-border-style", "background-box");
        Opt("osd-back-color", "#E60A0C10");
        Opt("osd-border-color", "#00000000");
        Opt("osd-border-size", "2");
        Opt("osd-align-x", "center");
        Opt("osd-align-y", "bottom");
        Opt("osd-margin-y", "48");
        if (!string.IsNullOrEmpty(_options.LogFile)) Opt("log-file", _options.LogFile!);
        if (!string.IsNullOrEmpty(_options.InputConf)) Opt("input-conf", _options.InputConf!);
        // Verified against this engine build: "http-proxy" is the accepted option name ("proxy" and
        // "http_proxy" are rejected), and the engine also honours the ambient http_proxy/NO_PROXY
        // variables, so the value is always set explicitly — including to empty for direct routing.
        if (_options.HttpProxy is not null) Opt("http-proxy", _options.HttpProxy);

        if (_options.Wid != IntPtr.Zero)
        {
            // Documented embedding contract: on Windows the value is an HWND as a decimal number.
            Opt("wid", _options.Wid.ToInt64().ToString());
        }

        foreach (var (k, v) in _options.ExtraOptions) Opt(k, v);
    }

    // ---------------------------------------------------------------- event thread

    private void EventLoop()
    {
        while (!_shutdown.IsCancellationRequested)
        {
            IntPtr evPtr;
            try
            {
                evPtr = MpvNative.mpv_wait_event(_ctx, 0.25);
            }
            catch (Exception)
            {
                return;
            }
            if (evPtr == IntPtr.Zero) continue;

            try
            {
                var ev = Marshal.PtrToStructure<MpvNative.MpvEvent>(evPtr);
                switch (ev.EventId)
                {
                    case MpvNative.MPV_EVENT_NONE:
                        break;
                    case MpvNative.MPV_EVENT_SHUTDOWN:
                        return;
                    case MpvNative.MPV_EVENT_PROPERTY_CHANGE:
                        DecodeProperty(ev);
                        break;
                    case MpvNative.MPV_EVENT_LOG_MESSAGE:
                        DecodeLogMessage(ev);
                        break;
                    case MpvNative.MPV_EVENT_END_FILE:
                        DecodeEndFile(ev);
                        break;
                    case MpvNative.MPV_EVENT_FILE_LOADED:
                        _loaded = true;
                        Volatile.Write(ref _fileLoaded, 1);
                        Volatile.Write(ref _tracksDirty, 1);
                        Volatile.Write(ref _dirty, 1);
                        break;
                    case MpvNative.MPV_EVENT_AUDIO_RECONFIG:
                    case MpvNative.MPV_EVENT_VIDEO_RECONFIG:
                        Volatile.Write(ref _tracksDirty, 1);
                        Volatile.Write(ref _dirty, 1);
                        break;
                    default:
                        break;
                }
            }
            catch (Exception)
            {
                // A malformed event must never kill the loop; the publisher tick keeps the UI alive.
            }
        }
    }

    /// <summary>
    /// Keeps the last few engine log lines. The end-file error string alone is almost always
    /// "loading failed", so these lines are what make a failure explainable; the log tail is cleared on
    /// every load so a stale reason can never be attached to a new title.
    /// </summary>
    private void DecodeLogMessage(MpvNative.MpvEvent ev)
    {
        if (ev.Data == IntPtr.Zero) return;
        var msg = Marshal.PtrToStructure<MpvNative.MpvEventLogMessage>(ev.Data);
        string? text = Marshal.PtrToStringUTF8(msg.Text);
        if (string.IsNullOrWhiteSpace(text)) return;

        string prefix = msg.Prefix == IntPtr.Zero ? string.Empty : (Marshal.PtrToStringUTF8(msg.Prefix) ?? string.Empty);
        string level = msg.Level == IntPtr.Zero ? string.Empty : (Marshal.PtrToStringUTF8(msg.Level) ?? string.Empty);
        string line = (prefix.Length == 0 ? text : prefix + ": " + text).Trim();
        if (level.Length > 0 && level is not ("error" or "fatal" or "warn")) return;

        lock (_logTailLock)
        {
            _logTail.Enqueue(line);
            while (_logTail.Count > LogTailCapacity) _logTail.Dequeue();
        }
    }

    /// <summary>A snapshot of the engine log tail; used to explain a failure to the viewer.</summary>
    private string[] LogTailSnapshot()
    {
        lock (_logTailLock) return _logTail.ToArray();
    }

    /// <summary>Reads a property value straight out of the event payload — no API calls.</summary>
    private void DecodeProperty(MpvNative.MpvEvent ev)
    {
        if (ev.Data == IntPtr.Zero) return;
        var prop = Marshal.PtrToStructure<MpvNative.MpvEventProperty>(ev.Data);
        if (prop.Data == IntPtr.Zero) return;

        string? name = Marshal.PtrToStringUTF8(prop.Name);
        if (string.IsNullOrEmpty(name)) return;

        switch (name)
        {
            case "time-pos":
                if (prop.Format == MpvNative.MPV_FORMAT_DOUBLE)
                {
                    Volatile.Write(ref _positionSeconds, Marshal.PtrToStructure<double>(prop.Data));
                    Volatile.Write(ref _dirty, 1);
                }
                return;
            case "duration":
                if (prop.Format == MpvNative.MPV_FORMAT_DOUBLE)
                {
                    Volatile.Write(ref _durationSeconds, Marshal.PtrToStructure<double>(prop.Data));
                    Volatile.Write(ref _dirty, 1);
                }
                return;
            case "pause":
                if (prop.Format == MpvNative.MPV_FORMAT_FLAG)
                {
                    _paused = Marshal.ReadInt32(prop.Data) != 0;
                    Volatile.Write(ref _dirty, 1);
                }
                return;
            case "volume":
                if (prop.Format == MpvNative.MPV_FORMAT_DOUBLE)
                    _volume = Marshal.PtrToStructure<double>(prop.Data);
                return;
            case "speed":
                if (prop.Format == MpvNative.MPV_FORMAT_DOUBLE)
                    _speed = Marshal.PtrToStructure<double>(prop.Data);
                return;
            case "mute":
                if (prop.Format == MpvNative.MPV_FORMAT_FLAG)
                    _muted = Marshal.ReadInt32(prop.Data) != 0;
                return;
            case "sub-visibility":
                if (prop.Format == MpvNative.MPV_FORMAT_FLAG)
                    _subtitleVisible = Marshal.ReadInt32(prop.Data) != 0;
                return;
            case "eof-reached":
                _eof = prop.Format == MpvNative.MPV_FORMAT_FLAG && Marshal.ReadInt32(prop.Data) != 0;
                return;
            case "paused-for-cache":
                _buffering = prop.Format == MpvNative.MPV_FORMAT_FLAG && Marshal.ReadInt32(prop.Data) != 0;
                Volatile.Write(ref _dirty, 1);
                return;
            case "track-list/count":
                if (prop.Format == MpvNative.MPV_FORMAT_INT64)
                {
                    int count = (int)Marshal.ReadInt64(prop.Data);
                    if (count != _observedTrackCount)
                    {
                        _observedTrackCount = count;
                        Volatile.Write(ref _tracksDirty, 1);
                    }
                }
                return;
            default:
                return;
        }
    }

    private void DecodeEndFile(MpvNative.MpvEvent ev)
    {
        if (ev.Data == IntPtr.Zero) return;
        var end = Marshal.PtrToStructure<MpvNative.MpvEventEndFile>(ev.Data);
        Volatile.Write(ref _endFileReason, end.Reason);
        Volatile.Write(ref _endFileError, end.Error);
        if (end.Reason == MpvNative.MPV_END_FILE_REASON_ERROR)
        {
            _errorText = MpvNative.ErrorString(end.Error);
        }
        else if (end.Reason == MpvNative.MPV_END_FILE_REASON_EOF)
        {
            // end-file is the authoritative end-of-stream signal. The observed "eof-reached"
            // property is only true while the file is still loaded, and mpv unloads it immediately
            // when keep-open is off, so polling that property alone silently loses the end of every
            // file - the UI then never reports Ended and auto-advance never runs. The latch is
            // cleared by the next LoadAsync.
            _eof = true;
        }
        Volatile.Write(ref _dirty, 1);
    }

    // ---------------------------------------------------------------- publisher tick

    /// <summary>
    /// The only place that (a) calls into the mpv API and (b) raises <see cref="SnapshotChanged"/>.
    /// </summary>
    private void PublisherTick()
    {
        if (_disposed || _ctx == IntPtr.Zero || !_initialized) return;

        try
        {
            if (Interlocked.Exchange(ref _tracksDirty, 0) == 1) RefreshTracks();

            // A start-position seek has to wait for the file to be loaded, and must not be issued
            // from the event thread, so it is deferred here.
            if (Interlocked.Exchange(ref _fileLoaded, 0) == 1)
            {
                long startTicks = Interlocked.Read(ref _pendingStartPositionTicks);
                if (startTicks > 0)
                {
                    Interlocked.Exchange(ref _pendingStartPositionTicks, 0);
                    double seconds = startTicks / (double)TimeSpan.TicksPerSecond;
                    if (seconds > 0) Run("seek", seconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture), "absolute");
                }

                List<(string Path, bool Select)> queued;
                lock (_subtitleQueueLock)
                {
                    _fileReady = true;
                    queued = new List<(string Path, bool Select)>(_pendingExternalSubtitles);
                    _pendingExternalSubtitles.Clear();
                }
                foreach ((string path, bool select) in queued)
                {
                    try { TryAddSubtitle(path, select); }
                    catch (Exception) { /* a sidecar the engine rejects is skipped, never fatal */ }
                }
            }

            bool force = Interlocked.Exchange(ref _dirty, 0) == 1;
            Publish(force);
        }
        catch (Exception)
        {
            // Publishing must never take the process down; the next tick retries.
        }
    }

    private void RefreshTracks()
    {
        if (_disposed || _ctx == IntPtr.Zero) return;

        var audio = new List<EngineAudioTrack>();
        var subs = new List<EngineSubtitleTrack>();
        var video = new List<EngineVideoTrack>();
        long? selAudio = null, selSub = null, selVideo = null;

        long count = MpvNative.GetInt64(_ctx, "track-list/count") ?? 0;
        for (long i = 0; i < count; i++)
        {
            string prefix = "track-list/" + i;
            string? type = MpvNative.GetString(_ctx, prefix + "/type");
            long id = MpvNative.GetInt64(_ctx, prefix + "/id") ?? -1;
            if (id < 0) continue;

            bool selected = MpvNative.GetFlag(_ctx, prefix + "/selected") ?? false;
            bool def = MpvNative.GetFlag(_ctx, prefix + "/default") ?? false;
            bool external = MpvNative.GetFlag(_ctx, prefix + "/external") ?? false;
            string? lang = MpvNative.GetString(_ctx, prefix + "/lang");
            string? title = MpvNative.GetString(_ctx, prefix + "/title");
            string? codec = MpvNative.GetString(_ctx, prefix + "/codec");

            if (type == "audio")
            {
                audio.Add(new EngineAudioTrack(
                    id, lang, title, codec,
                    ToPositiveInt(MpvNative.GetInt64(_ctx, prefix + "/demux-channel-count")),
                    ToPositiveInt(MpvNative.GetInt64(_ctx, prefix + "/demux-samplerate")),
                    selected, def, external));
                if (selected) selAudio = id;
            }
            else if (type == "sub")
            {
                subs.Add(new EngineSubtitleTrack(
                    id, lang, title, codec, selected, def,
                    MpvNative.GetFlag(_ctx, prefix + "/forced") ?? false, external));
                if (selected) selSub = id;
            }
            else if (type == "video")
            {
                double? trackFps = MpvNative.GetDouble(_ctx, prefix + "/demux-fps");
                if (trackFps is <= 0) trackFps = null;
                video.Add(new EngineVideoTrack(
                    id, lang, title, codec,
                    ToPositiveInt(MpvNative.GetInt64(_ctx, prefix + "/demux-w")),
                    ToPositiveInt(MpvNative.GetInt64(_ctx, prefix + "/demux-h")),
                    trackFps, selected, def));
                if (selected) selVideo = id;
            }
        }

        lock (_stateLock)
        {
            _audioTracks = audio;
            _subtitleTracks = subs;
            _videoTracks = video;
            _selectedAudioId = selAudio;
            _selectedSubtitleId = selSub;
            _selectedVideoId = selVideo;
        }
    }

    private static int? ToPositiveInt(long? v) => v is > 0 and <= int.MaxValue ? (int)v.Value : null;

    private void Publish(bool force)
    {
        PlayerSnapshot snap = BuildSnapshot();
        lock (_stateLock) { _snapshot = snap; }
        SnapshotChanged?.Invoke(this, snap);
    }

    private PlayerSnapshot BuildSnapshot()
    {
        int? width = null, height = null;
        double? fps = null;
        var technical = new TechnicalInfo();

        if (_ctx != IntPtr.Zero && _initialized && !_disposed)
        {
            width = ToPositiveInt(MpvNative.GetInt64(_ctx, "width"));
            height = ToPositiveInt(MpvNative.GetInt64(_ctx, "height"));
            fps = MpvNative.GetDouble(_ctx, "container-fps");
            if (fps is <= 0) fps = null;

            long? vbr = MpvNative.GetInt64(_ctx, "video-bitrate");
            long? abr = MpvNative.GetInt64(_ctx, "audio-bitrate");
            long? bitRate = (vbr is > 0 || abr is > 0) ? (vbr ?? 0) + (abr ?? 0) : null;

            double? sigPeak = MpvNative.GetDouble(_ctx, "video-params/sig-peak");
            if (sigPeak is <= 0) sigPeak = null;

            double? renderFps = MpvNative.GetDouble(_ctx, "estimated-vf-fps");
            if (renderFps is <= 0) renderFps = null;

            string? hwdec = MpvNative.GetString(_ctx, "hwdec-current");
            if (hwdec == "no") hwdec = "no (software)";

            technical = new TechnicalInfo
            {
                VideoCodec = MpvNative.GetString(_ctx, "video-codec"),
                PixelFormat = MpvNative.GetString(_ctx, "video-params/pixelformat"),
                HwDecCurrent = hwdec,
                ColorPrimaries = MpvNative.GetString(_ctx, "video-params/primaries"),
                ColorTransfer = MpvNative.GetString(_ctx, "video-params/gamma"),
                ColorMatrix = MpvNative.GetString(_ctx, "video-params/colormatrix"),
                ColorLevels = MpvNative.GetString(_ctx, "video-params/colorlevels"),
                SignalPeak = sigPeak,
                AudioCodec = MpvNative.GetString(_ctx, "audio-codec-name"),
                AudioChannels = ToPositiveInt(MpvNative.GetInt64(_ctx, "audio-params/channel-count")),
                AudioSampleRate = ToPositiveInt(MpvNative.GetInt64(_ctx, "audio-params/samplerate")),
                Container = MpvNative.GetString(_ctx, "file-format"),
                VideoOutput = MpvNative.GetString(_ctx, "current-vo"),
                GpuContext = MpvNative.GetString(_ctx, "gpu-context"),
                FileSizeBytes = MpvNative.GetInt64(_ctx, "file-size") is > 0 and var fs ? fs : null,
                BitRate = bitRate,
                FrameDrops = ToPositiveInt(MpvNative.GetInt64(_ctx, "frame-drop-count")),
                RenderFps = renderFps,
                CacheSeconds = MpvNative.GetDouble(_ctx, "demuxer-cache-duration") is > 0 and var cs ? cs : null,
                MistimedFrames = ToPositiveInt(MpvNative.GetInt64(_ctx, "mistimed-frame-count")),
                CacheSpeedBytesPerSecond = MpvNative.GetDouble(_ctx, "cache-speed") is > 0 and var cspeed ? cspeed : null,
            };

            lock (_stateLock)
            {
                _width = width; _height = height; _fps = fps; _technical = technical;
            }
        }
        else
        {
            lock (_stateLock)
            {
                width = _width; height = _height; fps = _fps; technical = _technical;
            }
        }

        double position = Volatile.Read(ref _positionSeconds);
        double duration = Volatile.Read(ref _durationSeconds);
        bool paused = _paused, muted = _muted, eof = _eof, buffering = _buffering, subVisible = _subtitleVisible;
        IReadOnlyList<EngineAudioTrack> audioTracks;
        IReadOnlyList<EngineSubtitleTrack> subTracks;
        IReadOnlyList<EngineVideoTrack> videoTracks;
        long? selAudio, selSub, selVideo;
        lock (_stateLock)
        {
            audioTracks = _audioTracks; subTracks = _subtitleTracks; videoTracks = _videoTracks;
            selAudio = _selectedAudioId; selSub = _selectedSubtitleId; selVideo = _selectedVideoId;
        }

        // Some containers report no duration until playback advances. The provider-declared runtime
        // is a client-side fallback only; engine-reported timing always wins when available.
        double effectiveDuration = duration;
        if (effectiveDuration <= 0 && Interlocked.Read(ref _pendingDurationTicks) > 0)
            effectiveDuration = Interlocked.Read(ref _pendingDurationTicks) / (double)TimeSpan.TicksPerSecond;

        int endReason = Volatile.Read(ref _endFileReason);
        // The engine's own error string is "loading failed" for nearly every cause (a 401, a decoder the
        // GPU cannot initialise, an unopenable video output). The log tail captured by the event thread
        // is the only place the real reason exists, so it is what the viewer is told.
        string? error = _errorText is null
            ? null
            : MpvPlaybackErrors.Describe(_errorText, LogTailSnapshot(), technical.IsDolbyVision);

        TransportState state;
        if (!string.IsNullOrEmpty(error)) state = TransportState.Error;
        else if (!_loaded) state = TransportState.Idle;
        else if (eof) state = TransportState.Ended;
        else if (effectiveDuration <= 0 && !buffering && position <= 0) state = TransportState.Loading;
        else if (paused) state = TransportState.Paused;
        else state = TransportState.Playing;

        return new PlayerSnapshot
        {
            State = state,
            MediaTitle = MpvNative.GetString(_ctx, "media-title"),
            FileName = MpvNative.GetString(_ctx, "filename"),
            MediaPath = MpvNative.GetString(_ctx, "path"),
            PositionSeconds = position,
            DurationSeconds = effectiveDuration,
            Volume = _volume,
            Muted = muted,
            Speed = _speed,
            Buffering = buffering,
            EofReached = eof,
            SubtitleVisible = subVisible,
            Width = width,
            Height = height,
            Fps = fps,
            AudioTracks = audioTracks,
            SubtitleTracks = subTracks,
            VideoTracks = videoTracks,
            SelectedAudioId = selAudio,
            SelectedSubtitleId = selSub,
            SelectedVideoId = selVideo,
            Error = error,
            Technical = technical,
        };
    }

    // ---------------------------------------------------------------- commands

    private int Run(params string[] args)
    {
        if (_disposed || _ctx == IntPtr.Zero) return -1;
        lock (_commandLock) { return MpvNative.Command(_ctx, args); }
    }

    private void SetProperty(string name, string value)
    {
        if (_disposed || _ctx == IntPtr.Zero) return;
        lock (_commandLock) { MpvNative.mpv_set_property_string(_ctx, name, value); }
    }

    public Task LoadAsync(string path, PlaybackSettings settings, CancellationToken ct = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("path must not be empty", nameof(path));
        ct.ThrowIfCancellationRequested();

        Interlocked.Exchange(ref _pendingStartPositionTicks, settings.StartPositionTicks);
        Interlocked.Exchange(ref _pendingDurationTicks, 0);
        _errorText = null;
        // A failure from the previous title must not be quoted when explaining the next one.
        lock (_logTailLock) _logTail.Clear();
        Volatile.Write(ref _endFileReason, -1);
        _loaded = true;
        _eof = false;
        _speed = settings.Speed > 0 ? settings.Speed : 1.0;
        Volatile.Write(ref _positionSeconds, 0);
        Volatile.Write(ref _durationSeconds, 0);
        lock (_stateLock) { _audioTracks = Array.Empty<EngineAudioTrack>(); _subtitleTracks = Array.Empty<EngineSubtitleTrack>(); _videoTracks = Array.Empty<EngineVideoTrack>(); _selectedVideoId = null; }
        // Sidecar subtitles added before this file finished loading were queued for the previous
        // media (or for this one); either way they must not survive a load.
        lock (_subtitleQueueLock)
        {
            _fileReady = false;
            _pendingExternalSubtitles.Clear();
        }
        _observedTrackCount = -1;

        // Header injection: memory-only, applied immediately before the load so a previous source's
        // credentials can never leak into this request.
        SetProperty("http-header-fields", MpvHeaders.Format(settings.Headers));
        SetProperty("volume", settings.Volume.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        SetProperty("speed", _speed.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        SetProperty("sub-visibility", settings.SubtitleVisible ? "yes" : "no");

        // Engine options come from the playback settings so a change in the shell (for example the
        // disk-cache budget) takes effect on the next load without recreating the engine.
        foreach (var option in settings.EngineOptions)
        {
            SetProperty(option.Key, option.Value);
        }

        // mpv keeps the pause property across loadfile, so an item that was paused (or stopped by the
        // shell right before the next queue entry) would leave the new file frozen at 0:00. Loading a
        // file always means "start it"; pausing is an explicit user command afterwards.
        SetProperty("pause", "no");

        int rc = Run("loadfile", path, "replace");
        if (rc < 0) throw new InvalidOperationException($"loadfile failed: {MpvNative.ErrorString(rc)}");

        Volatile.Write(ref _dirty, 1);
        return Task.CompletedTask;
    }

    /// <summary>Supplies the provider-declared runtime as a fallback when the container has none.</summary>
    public void SetDeclaredDuration(TimeSpan? duration)
        => Interlocked.Exchange(ref _pendingDurationTicks, duration is { } d ? d.Ticks : 0);

    /// <summary>
    /// Appends the next item without interrupting the current one. mpv prefetches the appended entry
    /// (<c>prefetch-playlist</c>), which is what makes the handover at the end of an episode fast.
    /// </summary>
    public void AppendToPlaylist(string path)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("path must not be empty", nameof(path));
        // mpv 0.41 ships prefetch-playlist=no, so the appended entry would otherwise be opened only
        // when it becomes current — which is the wait the preload exists to remove.
        SetProperty("prefetch-playlist", "yes");
        int rc = Run("loadfile", path, "append");
        if (rc < 0) throw new InvalidOperationException($"loadfile append failed: {MpvNative.ErrorString(rc)}");
    }

    /// <summary>Number of entries the engine holds, including the one playing; -1 when unavailable.</summary>
    public long PlaylistCount => _ctx == IntPtr.Zero ? -1 : (MpvNative.GetInt64(_ctx, "playlist-count") ?? -1);

    /// <summary>
    /// Queues sidecars for the playlist entry that is about to become current. They must be queued
    /// before the switch: adding them while the old entry is current would attach them to that file.
    /// </summary>
    public void PreparePlaylistNext(
        IReadOnlyList<string> selectedSubtitles,
        IReadOnlyList<string> unselectedSubtitles)
    {
        lock (_subtitleQueueLock)
        {
            _fileReady = false;
            _pendingExternalSubtitles.Clear();
        }
        foreach (string path in selectedSubtitles)
        {
            try { AddExternalSubtitle(path, select: true); }
            catch (Exception) { /* a sidecar the engine rejects is skipped, never fatal */ }
        }
        foreach (string path in unselectedSubtitles)
        {
            try { AddExternalSubtitle(path, select: false); }
            catch (Exception) { /* a sidecar the engine rejects is skipped, never fatal */ }
        }
    }

    /// <summary>Switches to the appended entry, reusing whatever mpv already prefetched for it.</summary>
    public void PlaylistNext(PlaybackSettings settings)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        Interlocked.Exchange(ref _pendingStartPositionTicks, settings.StartPositionTicks);
        Interlocked.Exchange(ref _pendingDurationTicks, 0);
        _errorText = null;
        // Same reason as LoadAsync: an error from the finished item must not be quoted for the next one.
        lock (_logTailLock) _logTail.Clear();
        Volatile.Write(ref _endFileReason, -1);
        _loaded = true;
        _eof = false;
        _paused = false;
        _speed = settings.Speed > 0 ? settings.Speed : 1.0;
        Volatile.Write(ref _positionSeconds, 0);
        Volatile.Write(ref _durationSeconds, 0);
        _observedTrackCount = -1;

        // The appended entry was opened while the previous item was playing, with whatever header set
        // LoadAsync applied. Re-apply it so a long playlist cannot inherit an expired credential, and so
        // an empty set is explicitly cleared rather than silently kept. Best effort: an entry mpv has
        // already prefetched may have its connection open. (The app currently passes no headers at all:
        // credentials stay inside the loopback bridge.)
        SetProperty("http-header-fields", MpvHeaders.Format(settings.Headers));

        SetProperty("volume", settings.Volume.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        SetProperty("speed", _speed.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        SetProperty("sub-visibility", settings.SubtitleVisible ? "yes" : "no");
        SetProperty("pause", "no");

        int rc = Run("playlist-next", "force");
        if (rc < 0) throw new InvalidOperationException($"playlist-next failed: {MpvNative.ErrorString(rc)}");
        Volatile.Write(ref _dirty, 1);
    }

    public void Play() => SetPaused(false);
    public void Pause() => SetPaused(true);
    public void TogglePause() => Run("cycle", "pause");

    /// <summary>
    /// Draws a short-lived message over the picture using the engine's own OSD renderer.
    /// </summary>
    /// <remarks>
    /// This is the only place text can appear on top of the video: the engine's native child window
    /// is composited above the shell, so a XAML overlay drawn over the picture is invisible.
    /// </remarks>
    public void ShowMessage(string text, int durationMs = 2500)
    {
        if (_ctx == IntPtr.Zero || string.IsNullOrEmpty(text)) return;
        Run("show-text", text, durationMs.ToString(System.Globalization.CultureInfo.InvariantCulture));
    }

    private void SetPaused(bool paused)
    {
        SetProperty("pause", paused ? "yes" : "no");
        Volatile.Write(ref _dirty, 1);
    }

    public void SeekAbsolute(double seconds)
    {
        if (double.IsNaN(seconds) || double.IsInfinity(seconds)) return;
        Run("seek", seconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture), "absolute");
    }

    public void SeekRelative(double seconds)
        => Run("seek", seconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture), "relative");

    public void SetVolume(double volume)
    {
        double clamped = Math.Clamp(volume, 0, 200);
        _volume = clamped;
        SetProperty("volume", clamped.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        Volatile.Write(ref _dirty, 1);
    }

    public void SetBrightness(double brightness)
    {
        // The engine's picture equalizer is -100..100 with 0 neutral; the UI exposes 0..100 with 50
        // neutral. The 0.8 factor keeps the extremes useful: at full scale the engine's equalizer
        // washes the picture out to pure white, which is not a brightness setting anyone wants.
        double clamped = Math.Clamp(brightness, 0, 100);
        _brightness = clamped;
        SetProperty("brightness", ((clamped - 50) * 0.8).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        Volatile.Write(ref _dirty, 1);
    }

    public void SetMuted(bool muted)
    {
        _muted = muted;
        SetProperty("mute", muted ? "yes" : "no");
        Volatile.Write(ref _dirty, 1);
    }

    public void SetSpeed(double speed)
    {
        double clamped = Math.Clamp(speed, 0.25, 4.0);
        _speed = clamped;
        SetProperty("speed", clamped.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        Volatile.Write(ref _dirty, 1);
    }

    public void Stop()
    {
        Run("stop");
        _loaded = false;
        Volatile.Write(ref _dirty, 1);
    }

    // ---------------------------------------------------------------- track control

    public void SelectAudioTrack(long? engineTrackId)
    {
        SetProperty("aid", engineTrackId is { } id ? id.ToString() : "no");
        Volatile.Write(ref _tracksDirty, 1);
    }

    public void SelectSubtitleTrack(long? engineTrackId)
    {
        SetProperty("sid", engineTrackId is { } id ? id.ToString() : "no");
        Volatile.Write(ref _tracksDirty, 1);
    }

    public void SelectVideoTrack(long? engineTrackId)
    {
        SetProperty("vid", engineTrackId is { } id ? id.ToString() : "no");
        Volatile.Write(ref _tracksDirty, 1);
    }

    public void AddExternalSubtitle(string filePath, bool select = true)
    {
        if (string.IsNullOrWhiteSpace(filePath)) return;
        if (!File.Exists(filePath)) throw new FileNotFoundException("字幕文件不存在", filePath);
        // "auto" lets the engine pick the track only when nothing is selected, which is what a
        // discovered sidecar needs: it must not override the user's chosen track or the embedded
        // default, but it should be visible when the file has no subtitle at all.
        //
        // sub-add is only accepted once the file it belongs to has finished loading. Callers add
        // sidecars immediately after issuing the load, so on a slow source the command used to arrive
        // too early: mpv answered with an error, the command's return code was ignored, and the track
        // silently never appeared even though the subtitle had been downloaded successfully.
        lock (_subtitleQueueLock)
        {
            if (!_fileReady)
            {
                _pendingExternalSubtitles.Add((filePath, select));
                return;
            }
        }
        TryAddSubtitle(filePath, select);
    }

    private void TryAddSubtitle(string filePath, bool select)
    {
        int rc = Run("sub-add", filePath, select ? "select" : "auto");
        if (rc < 0) throw new InvalidOperationException("sub-add failed: " + MpvNative.ErrorString(rc));
        Volatile.Write(ref _tracksDirty, 1);
    }

    public void SetSubtitleVisible(bool visible)
    {
        _subtitleVisible = visible;
        SetProperty("sub-visibility", visible ? "yes" : "no");
    }

    public void SetSubtitleDelay(double seconds)
        => SetProperty("sub-delay", seconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));

    public void SetSubtitlePosition(double position)
        => SetProperty("sub-pos", Math.Clamp(position, 0, 150).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));

    public void SetSubtitleFontSize(double size)
        => SetProperty("sub-font-size", Math.Clamp(size, 8, 200).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));

    public void SetSubtitleFont(string fontFamily)
    {
        if (!string.IsNullOrWhiteSpace(fontFamily)) SetProperty("sub-font", fontFamily);
    }

    public void SetSubtitleColor(string rgbHex)
    {
        if (string.IsNullOrWhiteSpace(rgbHex)) return;
        // mpv expects #AARRGGBB.
        string v = rgbHex.TrimStart('#');
        SetProperty("sub-color", v.Length == 6 ? "#FF" + v : "#" + v);
    }

    public void SetSubtitleBorderSize(double size)
        => SetProperty("sub-border-size", Math.Clamp(size, 0, 20).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));

    /// <summary>Bold subtitle glyphs. The engine renders both external and (with ASS override) embedded tracks.</summary>
    public void SetSubtitleBold(bool bold)
        => SetProperty("sub-bold", bold ? "yes" : "no");

    public void SetSubtitleAssOverride(SubtitleAssOverride mode)
        => SetProperty("sub-ass-override", mode switch
        {
            SubtitleAssOverride.Yes => "yes",
            SubtitleAssOverride.Force => "force",
            SubtitleAssOverride.Scale => "scale",
            _ => "no",
        });

    // ---------------------------------------------------------------- audio output device

    /// <summary>
    /// Outputs the engine can switch to, in the engine's own order. The list is read from the engine
    /// rather than composed by the shell so the names written back are exactly the ones it accepts;
    /// it reflects the machine as it is now, which is why it is re-read instead of cached.
    /// </summary>
    public IReadOnlyList<EngineAudioDevice> GetAudioDevices()
    {
        if (_ctx == IntPtr.Zero) return Array.Empty<EngineAudioDevice>();
        var devices = new List<EngineAudioDevice>();
        foreach (var entry in MpvNative.GetNodeMapList(_ctx, "audio-device-list"))
        {
            if (!entry.TryGetValue("name", out string? name) || string.IsNullOrWhiteSpace(name)) continue;
            entry.TryGetValue("description", out string? description);
            devices.Add(new EngineAudioDevice(
                name,
                string.IsNullOrWhiteSpace(description) ? name : description,
                string.Equals(name, "auto", StringComparison.OrdinalIgnoreCase)));
        }
        return devices;
    }

    /// <summary>The device in use as the engine reports it; "auto" when following the system default.</summary>
    public string? CurrentAudioDevice => _ctx == IntPtr.Zero ? null : MpvNative.GetString(_ctx, "audio-device");

    /// <summary>
    /// Switches the output only to a device the engine currently lists, then still reads the property
    /// back before reporting success.
    /// </summary>
    /// <remarks>
    /// The read-back alone is not enough: the engine stores an unknown name and echoes it back, so it
    /// would confirm a device that cannot be opened. The list check is what rejects an endpoint that has
    /// been unplugged or never existed.
    /// </remarks>
    public bool TrySetAudioDevice(string? name)
    {
        if (_ctx == IntPtr.Zero) return false;
        string target = string.IsNullOrWhiteSpace(name) ? "auto" : name;

        // The engine stores whatever string it is given and only notices an unknown endpoint when the
        // next audio track opens, so a write that "succeeds" here would leave the UI claiming a device
        // that does not exist. Checking the engine's own device list first is what makes an unplugged
        // or nonexistent device a reportable failure instead of a silent lie.
        if (!string.Equals(target, "auto", StringComparison.OrdinalIgnoreCase)
            && !GetAudioDevices().Any(d => string.Equals(d.Name, target, StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }

        int rc = MpvNative.mpv_set_property_string(_ctx, "audio-device", target);
        if (rc < 0) return false;
        return string.Equals(CurrentAudioDevice, target, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Hardware-decoding mode actually in use; null when nothing is playing.</summary>
    public string? CurrentHwDec => _ctx == IntPtr.Zero ? null : MpvNative.GetString(_ctx, "hwdec-current");

    /// <summary>
    /// Reads back an engine option after initialisation.
    /// </summary>
    /// <remarks>
    /// The engine exposes its options as properties, which makes "the engine is configured the way we
    /// asked" an observable fact rather than an assumption about an API call that returned void.
    /// </remarks>
    public string? GetOptionValue(string optionName)
        => _ctx == IntPtr.Zero ? null : MpvNative.GetString(_ctx, "options/" + optionName);

    /// <summary>Reads a plain engine property, e.g. <c>mpv-version</c> or <c>mpv-configuration</c>.</summary>
    public string? GetProperty(string propertyName)
        => _ctx == IntPtr.Zero ? null : MpvNative.GetString(_ctx, propertyName);

    /// <summary>Writes the frame the engine is rendering to <paramref name="filePath"/>.</summary>
    /// <remarks>
    /// The <c>subtitles</c> flag is deliberate: the default class of screenshot commands omits the
    /// subtitle overlay, which would make the file useless for checking whether a subtitle actually
    /// reached the screen. What lands in the PNG is exactly what the engine composited, so the file is
    /// evidence rather than a re-rendering.
    /// </remarks>
    public bool TakeScreenshot(string filePath)
        => !string.IsNullOrWhiteSpace(filePath) && Run("screenshot-to-file", filePath, "subtitles") >= 0;

    /// <summary>True while the event thread is still delivering events.</summary>
    public bool IsEventLoopRunning => _eventThread.IsAlive;

    // ---------------------------------------------------------------- disposal

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;

        _publisher.Dispose();
        _shutdown.Cancel();

        if (_ctx != IntPtr.Zero)
        {
            // Ask mpv to quit so the event loop leaves mpv_wait_event cleanly; the handle is only
            // destroyed after the thread has joined, otherwise it is a use-after-free.
            try { lock (_commandLock) MpvNative.mpv_command_string(_ctx, "quit"); }
            catch { /* engine already gone */ }
        }

        await Task.Run(() =>
        {
            if (!_eventThread.Join(TimeSpan.FromSeconds(3))) return;
            if (_ctx != IntPtr.Zero)
            {
                MpvNative.mpv_terminate_destroy(_ctx);
                _ctx = IntPtr.Zero;
            }
        }).ConfigureAwait(false);
    }

    public void Dispose() => DisposeAsync().AsTask().GetAwaiter().GetResult();
}

/// <summary>
/// Formats request headers into mpv's <c>http-header-fields</c> list syntax.
/// </summary>
/// <remarks>
/// mpv separates fields with commas and escapes literal commas with a backslash; getting this wrong
/// silently merges headers, so the escaping lives here and is covered by unit tests.
/// </remarks>
public static class MpvHeaders
{
    public static string Format(IReadOnlyDictionary<string, string>? headers)
    {
        if (headers is null || headers.Count == 0) return "";

        var parts = new List<string>(headers.Count);
        foreach (var kv in headers)
        {
            string key = kv.Key;
            string? value = kv.Value;
            if (string.IsNullOrWhiteSpace(key) || value is null) continue;
            string field = key.Trim() + ": " + value;
            parts.Add(field.Replace(@"\", @"\\").Replace(",", @"\,"));
        }
        return string.Join(",", parts);
    }
}

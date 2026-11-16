namespace MediaHub.Player;

/// <summary>Transport state of the player engine.</summary>
public enum TransportState
{
    Idle = 0,
    Loading,
    Playing,
    Paused,
    Ended,
    Error,
}

/// <summary>
/// One audio track as the engine actually sees it. Index is the engine's own track id and is the
/// only value that may be handed back to the engine — it is not a server stream index.
/// </summary>
public sealed record EngineAudioTrack(
    long Id,
    string? Language,
    string? Title,
    string? Codec,
    int? Channels,
    int? SampleRate,
    bool Selected,
    bool IsDefault,
    bool IsExternal);

/// <summary>
/// One video track as the engine sees it. Kept alongside audio/subtitle so the player can show the
/// real codec and resolution and remember the user's choice across episodes.
/// </summary>
public sealed record EngineVideoTrack(
    long Id,
    string? Language,
    string? Title,
    string? Codec,
    int? Width,
    int? Height,
    double? Fps,
    bool Selected,
    bool IsDefault);

/// <summary>One subtitle track as the engine sees it.</summary>
public sealed record EngineSubtitleTrack(
    long Id,
    string? Language,
    string? Title,
    string? Codec,
    bool Selected,
    bool IsDefault,
    bool IsForced,
    bool IsExternal);

/// <summary>
/// Immutable view of the engine at one instant. The UI renders exclusively from this, so the engine
/// remains the single source of truth and no optimistic UI state can drift out of sync.
/// </summary>
public sealed record PlayerSnapshot
{
    public static PlayerSnapshot Empty { get; } = new();

    public TransportState State { get; init; } = TransportState.Idle;
    public string? MediaTitle { get; init; }
    public string? FileName { get; init; }
    public string? MediaPath { get; init; }

    public double PositionSeconds { get; init; }
    public double DurationSeconds { get; init; }
    public double Volume { get; init; } = 100;
    public bool Muted { get; init; }
    public double Speed { get; init; } = 1.0;

    public bool Seekable { get; init; } = true;
    public bool Buffering { get; init; }
    public bool EofReached { get; init; }
    public bool SubtitleVisible { get; init; }

    public int? Width { get; init; }
    public int? Height { get; init; }
    public double? Fps { get; init; }

    /// <summary>Decoder diagnostics reported by the engine; null when unknown — never fabricated.</summary>
    public TechnicalInfo Technical { get; init; } = new();

    public IReadOnlyList<EngineAudioTrack> AudioTracks { get; init; } = Array.Empty<EngineAudioTrack>();
    public IReadOnlyList<EngineSubtitleTrack> SubtitleTracks { get; init; } = Array.Empty<EngineSubtitleTrack>();
    public IReadOnlyList<EngineVideoTrack> VideoTracks { get; init; } = Array.Empty<EngineVideoTrack>();

    public long? SelectedAudioId { get; init; }
    public long? SelectedSubtitleId { get; init; }
    public long? SelectedVideoId { get; init; }

    public string? Error { get; init; }

    public double ProgressFraction => DurationSeconds > 0 ? Math.Clamp(PositionSeconds / DurationSeconds, 0, 1) : 0;
}

/// <summary>
/// Decoder / renderer facts read back from the engine. Every field is nullable: a value the engine
/// did not report stays null and is rendered as "unknown" rather than invented.
/// </summary>
public sealed record TechnicalInfo
{
    public string? VideoCodec { get; init; }
    public string? PixelFormat { get; init; }
    public string? HwDecCurrent { get; init; }
    public string? ColorPrimaries { get; init; }
    public string? ColorTransfer { get; init; }
    public string? ColorMatrix { get; init; }
    public string? ColorLevels { get; init; }
    public double? SignalPeak { get; init; }

    /// <summary>
    /// True when the engine recognised Dolby Vision metadata. Profile 5 and profile 8 both surface as
    /// the "dolbyvision" colour matrix, which is what tells the renderer to undo the DV reshaping.
    /// </summary>
    public bool IsDolbyVision => string.Equals(ColorMatrix, "dolbyvision", StringComparison.OrdinalIgnoreCase);
    public string? AudioCodec { get; init; }
    public int? AudioChannels { get; init; }
    public int? AudioSampleRate { get; init; }
    public string? Container { get; init; }
    public string? VideoOutput { get; init; }
    public string? GpuContext { get; init; }
    public long? FileSizeBytes { get; init; }
    public long? BitRate { get; init; }
    public int? FrameDrops { get; init; }
    public double? RenderFps { get; init; }

    /// <summary>How much of the source is buffered ahead, in seconds; null when the engine does not say.</summary>
    public double? CacheSeconds { get; init; }

    /// <summary>Frames the engine delivered late (A/V sync), not the same as dropped frames.</summary>
    public int? MistimedFrames { get; init; }

    /// <summary>Current demuxer readahead speed in bytes per second, when the engine reports it.</summary>
    public double? CacheSpeedBytesPerSecond { get; init; }

    /// <summary>True when the engine reports a PQ/HLG transfer, i.e. the source is HDR.</summary>
    public bool IsHdr => ColorTransfer is "pq" or "smpte2084" or "hlg" or "arib-std-b67";
}

/// <summary>
/// Playback engine port. Two implementations are planned: the mpv engine (Windows) and a future
/// platform engine; the UI only ever sees this interface.
/// </summary>
public interface IPlaybackEngine : IAsyncDisposable
{
    PlayerSnapshot Snapshot { get; }
    event EventHandler<PlayerSnapshot>? SnapshotChanged;

    Task LoadAsync(string path, PlaybackSettings settings, CancellationToken ct = default);
    void Play();
    void Pause();
    void TogglePause();
    void SeekAbsolute(double seconds);
    void SeekRelative(double seconds);
    void SetVolume(double volume);
    void SetMuted(bool muted);
    void SetSpeed(double speed);
    void Stop();
}

/// <summary>Audio/subtitle selection and subtitle styling.</summary>
public interface ITrackController
{
    void SelectAudioTrack(long? engineTrackId);
    void SelectSubtitleTrack(long? engineTrackId);
    void SelectVideoTrack(long? engineTrackId);
    /// <param name="select">
    /// True for a subtitle the user picked, which must become active. False for tracks discovered for
    /// the item: the engine selects one of those only when nothing is selected yet.
    /// </param>
    void AddExternalSubtitle(string filePath, bool select = true);
    void SetSubtitleVisible(bool visible);
    void SetSubtitleDelay(double seconds);
    void SetSubtitlePosition(double position);
    void SetSubtitleFontSize(double size);
    void SetSubtitleFont(string fontFamily);
    void SetSubtitleColor(string rgbHex);
    void SetSubtitleBorderSize(double size);
    void SetSubtitleBold(bool bold);
    void SetSubtitleAssOverride(SubtitleAssOverride mode);
}

/// <summary>
/// An audio output the engine can switch to. <see cref="Name"/> is the engine's own identifier and
/// the value that must be written back to select it; <see cref="Description"/> is the human label.
/// </summary>
public sealed record EngineAudioDevice(string Name, string Description, bool IsDefault);

/// <summary>Audio output selection. Separate from track selection: it is a device, not a stream.</summary>
public interface IAudioDeviceController
{
    /// <summary>Devices the engine can see right now. Empty when the engine has no context yet.</summary>
    IReadOnlyList<EngineAudioDevice> GetAudioDevices();

    /// <summary>The device the engine is actually using, or null when nothing is open.</summary>
    string? CurrentAudioDevice { get; }

    /// <summary>
    /// Switches the output. Null or empty means "follow the system default". Returns false when the
    /// engine rejected the name or did not end up on it; the caller then falls back to the default.
    /// </summary>
    bool TrySetAudioDevice(string? name);
}

/// <summary>
/// How ASS/SSA styling is treated: engine-provided styling, or forced plain styling so user font
/// preferences win. Mirrors the intent of the Android subtitle-style contract.
/// </summary>
public enum SubtitleAssOverride
{
    No = 0,
    Yes,
    Force,
    Scale,
}

/// <summary>Player configuration applied at load time.</summary>
public sealed record PlaybackSettings
{
    public long StartPositionTicks { get; init; }
    /// <summary>Headers injected into the engine's HTTP requests. Memory-only; never logged.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; init; } =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    public double Volume { get; init; } = 100;
    public double Speed { get; init; } = 1.0;
    public string? PreferredAudioLanguage { get; init; }
    public string? PreferredSubtitleLanguage { get; init; }
    public bool SubtitleVisible { get; init; } = true;
    /// <summary>Extra engine options; used to translate MediaHub settings into engine configuration.</summary>
    public IReadOnlyDictionary<string, string> EngineOptions { get; init; } =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
}

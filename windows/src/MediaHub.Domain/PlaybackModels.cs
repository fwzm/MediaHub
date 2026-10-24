namespace MediaHub.Domain;

/// <summary>
/// A playable source resolved for exactly one playback attempt.
/// </summary>
/// <remarks>
/// ADR-003 carried over to Windows: the <see cref="Url"/> is temporary and must never be persisted to
/// the database or written to logs. <see cref="Headers"/> may contain session credentials and are
/// memory-only; <c>Redactor</c> must be used before any logging.
/// </remarks>
public sealed record PlaybackSource
{
    public required Uri Url { get; init; }
    public IReadOnlyDictionary<string, string> Headers { get; init; } =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    public PlaybackMode Mode { get; init; } = PlaybackMode.DirectPlay;
    public string? SessionId { get; init; }
    public string? Container { get; init; }
    public string? MediaSourceId { get; init; }
    public long? RunTimeTicks { get; init; }

    /// <summary>Resume position resolved at play time, if the server reported one.</summary>
    public long? StartPositionTicks { get; init; }

    /// <summary>Subtitle/audio streams the server declares for this source.</summary>
    public IReadOnlyList<MediaStreamInfo> Streams { get; init; } = Array.Empty<MediaStreamInfo>();
}

public sealed record PlaybackOptions
{
    /// <summary>Preferred start position (ticks). Null = resume from server state or start.</summary>
    public long? StartPositionTicks { get; init; }
    public int? AudioStreamIndex { get; init; }
    public int? SubtitleStreamIndex { get; init; }
    public bool ForceTranscode { get; init; }
    public double PlaybackRate { get; init; } = 1.0;
    public string? MediaSourceId { get; init; }
}

/// <summary>
/// Progress sample. Used both for the local snapshot store and for server reporting; mirrors the
/// Android <c>PlaybackProgress</c> contract including its field discipline.
/// </summary>
/// <remarks>
/// Android ADR-040 correction: the server session id travels on every progress sample because Emby
/// rejects progress bodies without a PlaySessionId (400). <see cref="SessionId"/> is provider-neutral,
/// memory-only and is not a credential.
/// </remarks>
public sealed record PlaybackProgress
{
    public required string ServerId { get; init; }
    public required string ItemId { get; init; }
    public required long PositionTicks { get; init; }
    public long? DurationTicks { get; init; }
    public bool IsPaused { get; init; }
    public bool IsMuted { get; init; }
    public string? SessionId { get; init; }
    public PlaybackMode Mode { get; init; } = PlaybackMode.DirectPlay;
}

/// <summary>
/// The plan produced before playback starts: which URL, with which headers, in which mode,
/// plus the resolved track selection. Windows analogue of the Android "playback pipeline".
/// </summary>
public sealed record PlaybackPlan
{
    public required MediaItem Item { get; init; }
    public required PlaybackSource Source { get; init; }
    public int? AudioStreamIndex { get; init; }
    public int? SubtitleStreamIndex { get; init; }
    public long StartPositionTicks { get; init; }
    public IReadOnlyList<string> ExternalSubtitlePaths { get; init; } = Array.Empty<string>();
}

/// <summary>Progress reporting throttle tiers (ADR-017 semantics).</summary>
public static class ProgressSyncPolicy
{
    /// <summary>Local snapshot sampling interval.</summary>
    public static readonly TimeSpan LocalSnapshotInterval = TimeSpan.FromSeconds(5);

    /// <summary>Default remote reporting interval; providers may shorten/lengthen it.</summary>
    public static readonly TimeSpan DefaultRemoteInterval = TimeSpan.FromSeconds(10);

    /// <summary>Position delta that forces an immediate flush regardless of interval.</summary>
    public const long ImmediateFlushThresholdTicks = 30L * TimeSpan.TicksPerSecond;

    /// <summary>Trailing window used to decide "finished" vs "resume".</summary>
    public static readonly TimeSpan CompletionThreshold = TimeSpan.FromSeconds(30);
}

namespace MediaHub.Domain;

/// <summary>
/// What the player was asked to play: one local file, or one item on a configured source.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="DisplayUrl"/> exists because the URL handed to the engine may be a loopback bridge
/// address carrying a per-session token. That token is a secret and must never reach a title bar, a
/// tooltip or a log, so the UI displays this value instead.
/// </para>
/// <para>
/// This lives in the domain assembly, next to <see cref="PlaybackQueue"/>, because the queue rules are
/// tested without a window and a request is what a queue holds.
/// </para>
/// </remarks>
public sealed record PlaybackRequest
{
    public required string Title { get; init; }
    public string? ServerId { get; init; }
    public string? ItemId { get; init; }

    /// <summary>Direct local file. Mutually exclusive with <see cref="ServerId"/>.</summary>
    public string? LocalPath { get; init; }

    /// <summary>Human-readable, credential-free URL shown in the UI.</summary>
    public string DisplayUrl { get; init; } = "";

    public long StartPositionTicks { get; init; }
    public TimeSpan? DeclaredDuration { get; init; }
    public IReadOnlyList<string> ExternalSubtitles { get; init; } = Array.Empty<string>();

    /// <summary>Series this item belongs to, when it is an episode. Enables auto-advance.</summary>
    public string? SeriesId { get; init; }

    public int? SeasonNumber { get; init; }
    public int? EpisodeNumber { get; init; }

    /// <summary>True when the request names a local file rather than a source item.</summary>
    public bool IsLocal => !string.IsNullOrEmpty(LocalPath);
}

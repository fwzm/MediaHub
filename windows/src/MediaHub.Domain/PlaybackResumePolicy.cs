namespace MediaHub.Domain;

/// <summary>
/// What counts as "watched" and where a replay should start.
/// </summary>
/// <remarks>
/// One rule in one place. The local history, the resume decision on every play path and the
/// "continue watching" list all ask this type, so "finished" cannot mean 95 % on one screen and
/// something else in the player. A finished title resumes at zero instead of at its final second:
/// resuming there starts playback already over, the engine reports an immediate end, and the player
/// then treats that as a completed item — which looks exactly like the item skipping itself.
/// </remarks>
public static class PlaybackResumePolicy
{
    /// <summary>Fraction of the runtime at or beyond which a title counts as watched.</summary>
    public const double FinishedFraction = 0.95;

    public static bool IsFinished(long positionTicks, long durationTicks)
        => durationTicks > 0 && (double)positionTicks / durationTicks >= FinishedFraction;

    public static bool ShouldResume(long positionTicks, long durationTicks)
        => positionTicks > 0 && !IsFinished(positionTicks, durationTicks);

    /// <summary>Position to start a new playback at; zero when there is nothing to resume.</summary>
    public static long ResumePositionTicks(long positionTicks, long durationTicks)
        => ShouldResume(positionTicks, durationTicks) ? positionTicks : 0;
}

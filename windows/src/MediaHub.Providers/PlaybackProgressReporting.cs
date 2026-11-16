namespace MediaHub.Providers;

/// <summary>
/// The two reporting decisions the player page makes on every sampling tick, kept out of the page so
/// they can be pinned by tests.
/// </summary>
/// <remarks>
/// Both were defects found by hand, not by a compiler: start and progress used to be fired without
/// awaiting, so progress could reach the server first and be dropped, and pause/seek never forced a
/// report, so the server kept the pre-pause position until the next throttle window elapsed.
/// </remarks>
public static class PlaybackProgressReporting
{
    /// <summary>A position change beyond this is a seek, not a sampling error.</summary>
    public const double JumpThresholdSeconds = 30;

    /// <summary>
    /// True when this tick must bypass the report throttle. Pause/resume and seeks are user actions:
    /// losing them for up to one interval is what made the server's resume point lag the engine.
    /// </summary>
    /// <param name="flushRequested">A pause/seek handler already asked for the next tick to be sent.</param>
    /// <param name="hasBaseline">
    /// False on the first tick of a session, where "the state changed" would otherwise be true for
    /// every title because there is nothing to compare against.
    /// </param>
    public static bool ShouldForce(
        bool flushRequested, bool hasBaseline, bool pausedNow, bool pausedBefore,
        double positionSeconds, double lastPositionSeconds)
    {
        if (flushRequested) return true;
        if (!hasBaseline) return false;
        if (pausedNow != pausedBefore) return true;
        return Math.Abs(positionSeconds - lastPositionSeconds) > JumpThresholdSeconds;
    }

    /// <summary>
    /// Sends the start report and waits for it before the first progress report.
    /// </summary>
    /// <remarks>
    /// The ordering matters to MediaBrowser servers: a progress report that arrives before
    /// <c>/Sessions/Playing</c> belongs to no open session and is discarded, which is why the
    /// server's resume position could stay at the old value for a whole interval.
    /// </remarks>
    public static async Task StartThenProgressAsync(Func<Task> notifyStarted, Func<Task> reportProgress)
    {
        ArgumentNullException.ThrowIfNull(notifyStarted);
        ArgumentNullException.ThrowIfNull(reportProgress);
        await notifyStarted().ConfigureAwait(false);
        await reportProgress().ConfigureAwait(false);
    }
}

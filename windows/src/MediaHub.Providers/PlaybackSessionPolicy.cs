using MediaHub.Domain;

namespace MediaHub.Providers;

/// <summary>Lifecycle stage of one foreground playback session.</summary>
public enum PlaybackStage
{
    Idle = 0,
    Preparing,
    Buffering,
    Playing,
    Paused,
    Seeking,
    Ended,
    Error,
}

/// <summary>
/// The legal playback stage transitions.
/// </summary>
/// <remarks>
/// Kept as an explicit table rather than a set of ifs because the interesting cases are the illegal
/// ones. A late event from a torn-down session must not be able to walk a finished session back into
/// Playing, and a session that already reported Error must not silently become Playing again — both
/// are exactly the sort of thing a network hiccup or a race produces in practice.
/// </remarks>
public static class PlaybackStages
{
    private static readonly Dictionary<PlaybackStage, PlaybackStage[]> Allowed = new()
    {
        [PlaybackStage.Idle] = new[] { PlaybackStage.Preparing, PlaybackStage.Ended, PlaybackStage.Error },
        [PlaybackStage.Preparing] = new[] { PlaybackStage.Buffering, PlaybackStage.Playing, PlaybackStage.Paused, PlaybackStage.Ended, PlaybackStage.Error },
        [PlaybackStage.Buffering] = new[] { PlaybackStage.Playing, PlaybackStage.Paused, PlaybackStage.Seeking, PlaybackStage.Ended, PlaybackStage.Error },
        [PlaybackStage.Playing] = new[] { PlaybackStage.Paused, PlaybackStage.Buffering, PlaybackStage.Seeking, PlaybackStage.Ended, PlaybackStage.Error },
        [PlaybackStage.Paused] = new[] { PlaybackStage.Playing, PlaybackStage.Seeking, PlaybackStage.Buffering, PlaybackStage.Ended, PlaybackStage.Error },
        [PlaybackStage.Seeking] = new[] { PlaybackStage.Playing, PlaybackStage.Paused, PlaybackStage.Buffering, PlaybackStage.Ended, PlaybackStage.Error },
        // Terminal. A new playback is a new session, never a resurrection of this one.
        [PlaybackStage.Ended] = Array.Empty<PlaybackStage>(),
        [PlaybackStage.Error] = Array.Empty<PlaybackStage>(),
    };

    public static bool IsTerminal(PlaybackStage stage) => stage is PlaybackStage.Ended or PlaybackStage.Error;

    public static bool CanTransition(PlaybackStage from, PlaybackStage to)
    {
        if (from == to) return true;
        return Allowed.TryGetValue(from, out PlaybackStage[]? targets) && Array.IndexOf(targets, to) >= 0;
    }
}

/// <summary>
/// Decides when a session may still touch shared state, and how progress reporting behaves.
/// </summary>
/// <remarks>
/// <para>
/// Two problems are solved here, and both are about time rather than about networking.
/// </para>
/// <para>
/// The first is attribution: resolving a stream, opening the relay and reporting progress are all
/// asynchronous, so the result of a request that started for one item can arrive after the user has
/// already started another. Each session carries the generation it was created with, and every write
/// is checked against the current generation first. Without that check, stopping an old playback can
/// cancel a new one's reporting, and a late "position 12:34" from the previous film can overwrite
/// the resume point of the one now playing.
/// </para>
/// <para>
/// The second is volume: a report every sampling tick would hammer the server, and an unbounded retry
/// would keep hammering it while it is failing. Reporting is throttled to the provider's interval and
/// retried a bounded number of times with a growing delay.
/// </para>
/// </remarks>
public sealed class PlaybackSessionPolicy
{
    private long _generation;

    public PlaybackSessionPolicy(TimeSpan reportInterval, int maxAttempts = 3, TimeSpan? initialBackoff = null)
    {
        ReportInterval = reportInterval;
        MaxAttempts = Math.Max(1, maxAttempts);
        InitialBackoff = initialBackoff ?? TimeSpan.FromMilliseconds(250);
    }

    public TimeSpan ReportInterval { get; }
    public int MaxAttempts { get; }
    public TimeSpan InitialBackoff { get; }

    /// <summary>Current generation. Incremented by <see cref="Begin"/>.</summary>
    public long CurrentGeneration => Volatile.Read(ref _generation);

    /// <summary>Starts a new session and invalidates every earlier one.</summary>
    public long Begin() => Interlocked.Increment(ref _generation);

    /// <summary>True when the given session is still the foreground one.</summary>
    public bool IsCurrent(long generation) => Volatile.Read(ref _generation) == generation;

    /// <summary>
    /// Whether a report should be sent now.
    /// </summary>
    /// <param name="lastReportedAt">When the previous accepted report was sent, or null for none.</param>
    /// <param name="force">True for lifecycle events (start, pause, seek, stop), which are never throttled.</param>
    public bool ShouldReport(DateTimeOffset now, DateTimeOffset? lastReportedAt, bool force)
    {
        if (force) return true;
        if (lastReportedAt is null) return true;
        return now - lastReportedAt.Value >= ReportInterval;
    }

    /// <summary>
    /// Whether a failure is worth retrying.
    /// </summary>
    /// <remarks>
    /// Only transient conditions are retried. A rejected credential or a missing item will fail the
    /// same way on the next attempt, so retrying them just adds noise to the server log and delay to
    /// the shutdown path.
    /// </remarks>
    public static bool IsTransient(ProviderErrorCode code)
        => code is ProviderErrorCode.Network or ProviderErrorCode.Connection or ProviderErrorCode.RateLimited;

    /// <summary>Backoff before the given attempt, growing linearly and capped.</summary>
    public TimeSpan BackoffFor(int attempt)
    {
        if (attempt <= 0) return TimeSpan.Zero;
        double factor = Math.Min(attempt, 5);
        return TimeSpan.FromMilliseconds(Math.Min(InitialBackoff.TotalMilliseconds * factor, 5000));
    }

    /// <summary>
    /// Runs a reporting action with the generation guard and bounded retry applied.
    /// </summary>
    /// <returns>True when the action completed; false when it was superseded or gave up.</returns>
    public async Task<bool> ExecuteAsync(long generation, Func<CancellationToken, Task> action,
        CancellationToken ct = default)
    {
        for (int attempt = 1; attempt <= MaxAttempts; attempt++)
        {
            // Re-checked on every attempt: the point of the guard is that a superseded session stops
            // immediately, including between retries.
            if (!IsCurrent(generation)) return false;

            try
            {
                await action(ct).ConfigureAwait(false);
                return true;
            }
            catch (OperationCanceledException)
            {
                return false;
            }
            catch (ProviderException ex) when (IsTransient(ex.Code) && attempt < MaxAttempts)
            {
                TimeSpan delay = BackoffFor(attempt);
                if (delay > TimeSpan.Zero)
                {
                    try { await Task.Delay(delay, ct).ConfigureAwait(false); }
                    catch (OperationCanceledException) { return false; }
                }
            }
            catch (ProviderException)
            {
                return false;
            }
        }
        return false;
    }
}

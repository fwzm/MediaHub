using MediaHub.Domain;
using MediaHub.Providers;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Tests for the playback session policy: stage legality, generation attribution and reporting rules.
/// </summary>
/// <remarks>
/// These are the parts of playback lifecycle that are pure decisions rather than I/O, which is why
/// they live in the provider layer where they can be tested without a window, an engine or a server.
/// The behaviour they pin is the kind that only shows up as a rare glitch in production — a stale
/// report overwriting a new film's position, or a finished session being walked back to Playing.
/// </remarks>
public sealed class PlaybackSessionPolicyTests
{
    // ------------------------------------------------------------ stage machine

    [Theory]
    [InlineData(PlaybackStage.Idle, PlaybackStage.Preparing)]
    [InlineData(PlaybackStage.Preparing, PlaybackStage.Buffering)]
    [InlineData(PlaybackStage.Buffering, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Playing, PlaybackStage.Paused)]
    [InlineData(PlaybackStage.Paused, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Playing, PlaybackStage.Seeking)]
    [InlineData(PlaybackStage.Seeking, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Playing, PlaybackStage.Ended)]
    [InlineData(PlaybackStage.Playing, PlaybackStage.Error)]
    public void Legal_transitions_are_allowed(PlaybackStage from, PlaybackStage to)
        => Assert.True(PlaybackStages.CanTransition(from, to), from + " -> " + to);

    [Theory]
    [InlineData(PlaybackStage.Ended, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Ended, PlaybackStage.Paused)]
    [InlineData(PlaybackStage.Error, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Error, PlaybackStage.Buffering)]
    [InlineData(PlaybackStage.Idle, PlaybackStage.Playing)]
    [InlineData(PlaybackStage.Idle, PlaybackStage.Paused)]
    [InlineData(PlaybackStage.Idle, PlaybackStage.Buffering)]
    public void Illegal_transitions_are_refused(PlaybackStage from, PlaybackStage to)
        => Assert.False(PlaybackStages.CanTransition(from, to));

    [Fact]
    public void A_finished_session_is_terminal_and_cannot_be_revived()
    {
        // A late event from a torn-down session must not make a finished playback look alive again,
        // because everything downstream treats the stage as truth.
        Assert.True(PlaybackStages.IsTerminal(PlaybackStage.Ended));
        Assert.True(PlaybackStages.IsTerminal(PlaybackStage.Error));
        Assert.False(PlaybackStages.IsTerminal(PlaybackStage.Idle));
        Assert.False(PlaybackStages.IsTerminal(PlaybackStage.Playing));

        foreach (PlaybackStage target in Enum.GetValues<PlaybackStage>())
        {
            if (target == PlaybackStage.Ended || target == PlaybackStage.Error) continue;
            Assert.False(PlaybackStages.CanTransition(PlaybackStage.Ended, target));
            Assert.False(PlaybackStages.CanTransition(PlaybackStage.Error, target));
        }
    }

    [Fact]
    public void Staying_in_the_same_stage_is_not_a_transition_failure()
        => Assert.True(PlaybackStages.CanTransition(PlaybackStage.Playing, PlaybackStage.Playing));

    // ------------------------------------------------------------ generation attribution

    [Fact]
    public void Starting_a_new_session_invalidates_the_previous_one()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10));
        long first = policy.Begin();
        Assert.True(policy.IsCurrent(first));

        long second = policy.Begin();
        Assert.False(policy.IsCurrent(first));
        Assert.True(policy.IsCurrent(second));
    }

    [Fact]
    public async Task A_superseded_session_cannot_write_anything()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10));
        long stale = policy.Begin();
        long current = policy.Begin();

        int staleWrites = 0;
        int currentWrites = 0;

        bool staleRan = await policy.ExecuteAsync(stale, _ => { staleWrites++; return Task.CompletedTask; });
        bool currentRan = await policy.ExecuteAsync(current, _ => { currentWrites++; return Task.CompletedTask; });

        Assert.False(staleRan);
        Assert.Equal(0, staleWrites);
        Assert.True(currentRan);
        Assert.Equal(1, currentWrites);
    }

    [Fact]
    public async Task A_session_superseded_between_retries_stops_retrying()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), maxAttempts: 5,
            initialBackoff: TimeSpan.FromMilliseconds(20));
        long generation = policy.Begin();

        int attempts = 0;
        long other = 0;

        bool ran = await policy.ExecuteAsync(generation, _ =>
        {
            attempts++;
            // The user starts another film while this one is retrying.
            if (attempts == 1) other = policy.Begin();
            throw ProviderException.Network("s1");
        });

        Assert.False(ran);
        Assert.True(other > generation);
        // Only the first attempt may run: the guard is re-checked before every retry, not once at the
        // start, otherwise a superseded session would keep hammering the server for its whole backoff.
        Assert.Equal(1, attempts);
    }

    // ------------------------------------------------------------ reporting rules

    [Fact]
    public void Periodic_reports_are_throttled_to_the_interval()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10));
        var start = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);

        Assert.True(policy.ShouldReport(start, null, force: false));
        Assert.False(policy.ShouldReport(start.AddSeconds(9), start, force: false));
        Assert.True(policy.ShouldReport(start.AddSeconds(10), start, force: false));
        Assert.True(policy.ShouldReport(start.AddSeconds(60), start, force: false));
    }

    [Fact]
    public void Lifecycle_events_are_never_throttled()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10));
        var start = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);

        // Pause, seek and stop have to reach the server immediately; delaying a pause would leave the
        // user marked as still watching at a position they have already left.
        Assert.True(policy.ShouldReport(start, start, force: true));
    }

    [Theory]
    [InlineData(ProviderErrorCode.Network, true)]
    [InlineData(ProviderErrorCode.Connection, true)]
    [InlineData(ProviderErrorCode.RateLimited, true)]
    [InlineData(ProviderErrorCode.AuthExpired, false)]
    [InlineData(ProviderErrorCode.AuthFailed, false)]
    [InlineData(ProviderErrorCode.NotFound, false)]
    [InlineData(ProviderErrorCode.Parse, false)]
    public void Only_transient_failures_are_retried(ProviderErrorCode code, bool expected)
        => Assert.Equal(expected, PlaybackSessionPolicy.IsTransient(code));

    [Fact]
    public async Task A_non_transient_failure_is_not_retried()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), maxAttempts: 4,
            initialBackoff: TimeSpan.FromMilliseconds(10));
        long generation = policy.Begin();

        int attempts = 0;
        bool ran = await policy.ExecuteAsync(generation, _ =>
        {
            attempts++;
            throw ProviderException.AuthExpired("s1");
        });

        Assert.False(ran);
        // Retrying a rejected credential cannot succeed and would keep a failing request in the
        // player's exit path.
        Assert.Equal(1, attempts);
    }

    [Fact]
    public async Task A_transient_failure_is_retried_up_to_the_bound()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), maxAttempts: 3,
            initialBackoff: TimeSpan.FromMilliseconds(10));
        long generation = policy.Begin();

        int attempts = 0;
        bool ran = await policy.ExecuteAsync(generation, _ =>
        {
            attempts++;
            throw ProviderException.Network("s1");
        });

        Assert.False(ran);
        Assert.Equal(3, attempts);
    }

    [Fact]
    public async Task A_transient_failure_that_recovers_reports_success()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), maxAttempts: 3,
            initialBackoff: TimeSpan.FromMilliseconds(10));
        long generation = policy.Begin();

        int attempts = 0;
        bool ran = await policy.ExecuteAsync(generation, _ =>
        {
            attempts++;
            if (attempts < 2) throw ProviderException.Network("s1");
            return Task.CompletedTask;
        });

        Assert.True(ran);
        Assert.Equal(2, attempts);
    }

    [Fact]
    public void Backoff_grows_and_is_capped()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), initialBackoff: TimeSpan.FromMilliseconds(200));

        Assert.Equal(TimeSpan.Zero, policy.BackoffFor(0));
        Assert.Equal(TimeSpan.FromMilliseconds(200), policy.BackoffFor(1));
        Assert.True(policy.BackoffFor(3) > policy.BackoffFor(2));
        // Capped, so a long retry chain cannot park a thread for an unbounded time.
        Assert.True(policy.BackoffFor(50) <= TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task Cancellation_stops_a_report_without_reporting_failure()
    {
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10), maxAttempts: 3);
        long generation = policy.Begin();
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        int attempts = 0;
        bool ran = await policy.ExecuteAsync(generation, _ =>
        {
            attempts++;
            cts.Token.ThrowIfCancellationRequested();
            return Task.CompletedTask;
        }, cts.Token);

        Assert.False(ran);
        Assert.Equal(1, attempts);
    }
}

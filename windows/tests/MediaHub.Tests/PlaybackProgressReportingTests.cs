using MediaHub.Providers;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the two reporting decisions the player page makes, both of which were silent defects before.
/// </summary>
/// <remarks>
/// No window or engine is involved: the page feeds the tick's state into
/// <see cref="PlaybackProgressReporting"/> and awaits the start report before the first progress
/// report, so the decisions and the ordering can be checked headlessly.
/// </remarks>
public sealed class PlaybackProgressReportingTests
{
    [Fact]
    public void The_first_tick_does_not_invent_a_pause_or_seek_force()
    {
        // With no baseline, "paused differs" and "position moved" are both vacuously true; forcing
        // there would send a redundant report on every title.
        Assert.False(PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: false,
            pausedNow: true, pausedBefore: false,
            positionSeconds: 500, lastPositionSeconds: 0));
    }

    [Theory]
    [InlineData(false, true)] // pause
    [InlineData(true, false)] // resume
    public void Pause_and_resume_force_an_immediate_report(bool pausedBefore, bool pausedNow)
    {
        Assert.True(PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow, pausedBefore,
            positionSeconds: 42, lastPositionSeconds: 41));
    }

    [Fact]
    public void An_unchanged_pause_state_does_not_force()
    {
        Assert.False(PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow: true, pausedBefore: true,
            positionSeconds: 42, lastPositionSeconds: 41));
    }

    [Theory]
    [InlineData(100, 40, true)]   // forward seek
    [InlineData(40, 100, true)]   // backward seek
    [InlineData(70, 40, false)]   // exactly the threshold is one tick's worth of playback, not a seek
    public void A_seek_beyond_the_threshold_forces(double position, double lastPosition, bool expected)
    {
        Assert.Equal(expected, PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow: false, pausedBefore: false,
            positionSeconds: position, lastPositionSeconds: lastPosition));
    }

    [Fact]
    public void An_explicit_flush_forces_even_on_the_first_tick()
    {
        // The pause and seek handlers set this flag before the engine has a baseline, e.g. a seek
        // issued from a resume prompt.
        Assert.True(PlaybackProgressReporting.ShouldForce(
            flushRequested: true, hasBaseline: false,
            pausedNow: false, pausedBefore: false,
            positionSeconds: 0, lastPositionSeconds: 0));
    }

    [Fact]
    public async Task Start_is_awaited_before_the_first_progress_report()
    {
        var order = new List<string>();
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        Task flow = PlaybackProgressReporting.StartThenProgressAsync(
            async () =>
            {
                order.Add("start:enter");
                await release.Task;
                order.Add("start:exit");
            },
            () =>
            {
                order.Add("progress");
                return Task.CompletedTask;
            });

        // Start has begun but has not answered; progress must still be waiting.
        Assert.Equal(new[] { "start:enter" }, order);

        release.SetResult();
        await flow;

        Assert.Equal(new[] { "start:enter", "start:exit", "progress" }, order);
    }

    [Fact]
    public void A_pause_or_seek_force_gets_through_the_report_throttle()
    {
        // The force flag only matters if it reaches the policy: without it the same tick is throttled,
        // with it the report is sent now instead of up to one interval later.
        var policy = new PlaybackSessionPolicy(TimeSpan.FromSeconds(10));
        DateTimeOffset now = DateTimeOffset.UtcNow;
        DateTimeOffset lastReport = now.AddSeconds(-1);

        bool pauseForce = PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow: true, pausedBefore: false,
            positionSeconds: 42, lastPositionSeconds: 41);
        bool seekForce = PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow: false, pausedBefore: false,
            positionSeconds: 142, lastPositionSeconds: 42);
        bool ordinary = PlaybackProgressReporting.ShouldForce(
            flushRequested: false, hasBaseline: true,
            pausedNow: false, pausedBefore: false,
            positionSeconds: 43, lastPositionSeconds: 42);

        Assert.True(policy.ShouldReport(now, lastReport, pauseForce));
        Assert.True(policy.ShouldReport(now, lastReport, seekForce));
        Assert.False(policy.ShouldReport(now, lastReport, ordinary));
    }
}

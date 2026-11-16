using MediaHub.Domain;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The finished-watched rule and the resume position derived from it. Both are read by the history
/// list and by every play entry point, so they are pinned here rather than only through the UI.
/// </summary>
public sealed class PlaybackResumePolicyTests
{
    private const long Second = TimeSpan.TicksPerSecond;

    [Fact]
    public void A_title_watched_to_the_end_is_finished_and_resumes_from_zero()
    {
        // The measured case: a 180 s clip left at 2:59.999 in the local history.
        long position = 180 * Second - 1;
        long duration = 180 * Second;

        Assert.True(PlaybackResumePolicy.IsFinished(position, duration));
        Assert.False(PlaybackResumePolicy.ShouldResume(position, duration));
        Assert.Equal(0, PlaybackResumePolicy.ResumePositionTicks(position, duration));
    }

    [Fact]
    public void A_title_stopped_in_the_middle_resumes_where_it_stopped()
    {
        long position = 60 * Second;
        long duration = 180 * Second;

        Assert.False(PlaybackResumePolicy.IsFinished(position, duration));
        Assert.True(PlaybackResumePolicy.ShouldResume(position, duration));
        Assert.Equal(position, PlaybackResumePolicy.ResumePositionTicks(position, duration));
    }

    [Fact]
    public void The_finished_threshold_is_inclusive()
    {
        long duration = 200 * Second;

        Assert.True(PlaybackResumePolicy.IsFinished(190 * Second, duration));
        Assert.False(PlaybackResumePolicy.IsFinished(189 * Second, duration));
    }

    [Fact]
    public void A_position_beyond_the_runtime_is_finished_rather_than_clamped_into_a_resume()
    {
        // A stale entry whose file was re-encoded shorter: resuming there would seek past the end.
        Assert.True(PlaybackResumePolicy.IsFinished(300 * Second, 180 * Second));
        Assert.Equal(0, PlaybackResumePolicy.ResumePositionTicks(300 * Second, 180 * Second));
    }

    [Fact]
    public void Nothing_watched_has_nothing_to_resume()
    {
        Assert.False(PlaybackResumePolicy.IsFinished(0, 180 * Second));
        Assert.False(PlaybackResumePolicy.ShouldResume(0, 180 * Second));
        Assert.Equal(0, PlaybackResumePolicy.ResumePositionTicks(0, 180 * Second));
    }

    [Fact]
    public void An_unknown_runtime_never_marks_a_title_finished()
    {
        // Duration 0 is "the engine has not reported one"; calling it finished would throw away a
        // real position, so the rule stays silent instead of guessing.
        Assert.False(PlaybackResumePolicy.IsFinished(30 * Second, 0));
        Assert.Equal(30 * Second, PlaybackResumePolicy.ResumePositionTicks(30 * Second, 0));
    }
}

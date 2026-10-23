using MediaHub.Domain;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Contract tests for the temporary playback queue.
/// </summary>
/// <remarks>
/// The queue decides what "next" means, and every one of these rules is visible to the user as a
/// behaviour: whether their selection order survives, whether a duplicated file plays twice, and
/// what happens when they delete the item they are watching. Each test names the rule it pins so a
/// failure says which promise was broken rather than which line moved.
/// </remarks>
public sealed class PlaybackQueueTests
{
    private static PlaybackRequest Local(string path)
        => new() { LocalPath = path, Title = Path.GetFileNameWithoutExtension(path), DisplayUrl = path };

    private static PlaybackRequest Remote(string serverId, string itemId, string title)
        => new() { ServerId = serverId, ItemId = itemId, Title = title };

    [Fact]
    public void Replace_discards_the_previous_queue_and_starts_at_the_first_entry()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4") }, QueueIntake.Replace);

        QueueIntakeResult result = queue.Intake(new[] { Local("D:\\c.mp4") }, QueueIntake.Replace);

        Assert.Equal(new[] { "c" }, queue.Items.Select(i => i.Title));
        Assert.Equal(0, queue.Index);
        Assert.Equal(1, result.Added);
        Assert.Equal(QueueIntake.Replace, result.Mode);
    }

    [Fact]
    public void Append_keeps_the_selection_order_and_does_not_move_the_current_entry()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4") }, QueueIntake.Replace);
        queue.MoveBy(1);

        queue.Intake(new[] { Local("D:\\c.mp4"), Local("D:\\d.mp4") }, QueueIntake.Append);

        Assert.Equal(new[] { "a", "b", "c", "d" }, queue.Items.Select(i => i.Title));
        Assert.Equal("b", queue.Current?.Title);
    }

    [Fact]
    public void Appending_to_an_empty_queue_makes_the_first_new_entry_current()
    {
        var queue = new PlaybackQueue();

        queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Append);

        Assert.Equal("a", queue.Current?.Title);
    }

    [Fact]
    public void Duplicate_paths_are_dropped_and_the_first_occurrence_wins()
    {
        var queue = new PlaybackQueue();

        QueueIntakeResult result = queue.Intake(
            new[] { Local("D:\\a.mp4"), Local("d:\\A.MP4"), Local("D:\\b.mp4") },
            QueueIntake.Replace);

        Assert.Equal(new[] { "a", "b" }, queue.Items.Select(i => i.Title));
        Assert.Equal(3, result.Requested);
        Assert.Equal(2, result.Added);
        Assert.Equal(1, result.Duplicates);
    }

    [Fact]
    public void Appending_a_file_that_is_already_queued_reports_a_duplicate_and_adds_nothing()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Replace);

        QueueIntakeResult result = queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Append);

        Assert.Single(queue.Items);
        Assert.Equal(0, result.Added);
        Assert.Equal(1, result.Duplicates);
        Assert.True(result.NothingAdded);
    }

    [Fact]
    public void Server_entries_are_identified_by_server_and_item_not_by_title()
    {
        var queue = new PlaybackQueue();

        queue.Intake(
            new[] { Remote("jf:1", "100", "第一集"), Remote("jf:2", "100", "第一集") },
            QueueIntake.Replace);

        Assert.Equal(2, queue.Count);
    }

    [Fact]
    public void MoveBy_does_not_wrap_at_either_end()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4") }, QueueIntake.Replace);

        Assert.Null(queue.MoveBy(-1));
        Assert.Equal("a", queue.Current?.Title);

        Assert.NotNull(queue.MoveBy(1));
        Assert.Null(queue.MoveBy(1));
        Assert.Equal("b", queue.Current?.Title);
    }

    [Fact]
    public void Removing_an_entry_above_the_current_one_keeps_the_current_entry()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4"), Local("D:\\c.mp4") }, QueueIntake.Replace);
        queue.SelectAt(2);

        queue.RemoveAt(0);

        Assert.Equal("c", queue.Current?.Title);
        Assert.Equal(1, queue.Index);
    }

    [Fact]
    public void Removing_the_current_entry_promotes_the_following_one()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4"), Local("D:\\c.mp4") }, QueueIntake.Replace);

        queue.RemoveAt(0);

        Assert.Equal("b", queue.Current?.Title);
        Assert.Equal(0, queue.Index);
    }

    [Fact]
    public void Removing_the_last_entry_makes_the_previous_one_current_and_an_empty_queue_has_no_current()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4") }, QueueIntake.Replace);
        queue.SelectAt(1);

        queue.RemoveAt(1);
        Assert.Equal("a", queue.Current?.Title);
        Assert.False(queue.HasNext);

        queue.RemoveAt(0);
        Assert.Equal(0, queue.Count);
        Assert.Equal(-1, queue.Index);
        Assert.Null(queue.Current);
    }

    [Fact]
    public void Removing_an_out_of_range_index_changes_nothing()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Replace);

        Assert.False(queue.RemoveAt(3));
        Assert.False(queue.RemoveAt(-1));
        Assert.Single(queue.Items);
    }

    [Fact]
    public void Clear_empties_the_queue_and_reports_no_current_entry()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4") }, QueueIntake.Replace);

        queue.Clear();

        Assert.Empty(queue.Items);
        Assert.Null(queue.Current);
        Assert.Equal(-1, queue.Index);
        Assert.False(queue.HasNext);
        Assert.False(queue.HasPrevious);
    }

    [Fact]
    public void Select_resolves_an_entry_reached_through_another_route()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4"), Local("D:\\b.mp4"), Remote("jf", "77", "剧集") }, QueueIntake.Replace);

        queue.Select(serverId: "jf", itemId: "77", localPath: null);

        Assert.Equal("剧集", queue.Current?.Title);
        Assert.True(queue.HasPrevious);
        Assert.False(queue.HasNext);
    }

    [Fact]
    public void Revision_only_changes_when_the_queue_actually_changes()
    {
        var queue = new PlaybackQueue();
        queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Replace);
        long afterIntake = queue.Revision;

        queue.Intake(new[] { Local("D:\\a.mp4") }, QueueIntake.Append);
        Assert.Equal(afterIntake, queue.Revision);

        queue.Intake(new[] { Local("D:\\b.mp4") }, QueueIntake.Append);
        Assert.True(queue.Revision > afterIntake);
    }
}

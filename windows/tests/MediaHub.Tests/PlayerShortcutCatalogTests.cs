using MediaHub.Player;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The shortcut sheet is user-facing documentation of input that lives in the player page. These
/// tests keep it from silently losing the bindings that actually exist, which is the failure mode a
/// help list has: nothing crashes, it just stops being true.
/// </summary>
public sealed class PlayerShortcutCatalogTests
{
    [Fact]
    public void Every_entry_has_keys_an_action_and_a_known_group()
    {
        Assert.NotEmpty(PlayerShortcutCatalog.All);
        foreach (var shortcut in PlayerShortcutCatalog.All)
        {
            Assert.False(string.IsNullOrWhiteSpace(shortcut.Keys));
            Assert.False(string.IsNullOrWhiteSpace(shortcut.Action));
            Assert.Contains(shortcut.Group, new[]
            {
                PlayerShortcutCatalog.Keyboard,
                PlayerShortcutCatalog.Mouse,
                PlayerShortcutCatalog.Buttons,
            });
        }
    }

    [Fact]
    public void Keys_are_unique_within_a_group()
    {
        foreach (var group in PlayerShortcutCatalog.All.GroupBy(s => s.Group))
        {
            var duplicates = group.GroupBy(s => s.Keys).Where(g => g.Count() > 1).Select(g => g.Key).ToArray();
            Assert.Empty(duplicates);
        }
    }

    [Theory]
    [InlineData("播放 / 暂停")]
    [InlineData("后退 / 前进 5 秒")]
    [InlineData("指针在左半屏调亮度，右半屏调音量")]
    [InlineData("全屏切换")]
    [InlineData("在剧集队列中切换（底栏按钮）")]
    [InlineData("调节音量")]
    [InlineData("调节亮度")]
    public void Documented_bindings_cover_the_behaviour_the_page_implements(string action)
    {
        Assert.Contains(PlayerShortcutCatalog.All, s => s.Action == action);
    }

    [Fact]
    public void The_help_sheet_advertises_the_key_that_opens_it()
    {
        var entry = Assert.Single(PlayerShortcutCatalog.All, s => s.Action.Contains("操作说明"));
        // The page handles F1 and the OEM key that produces "?" on a US layout; the sheet must name
        // both, otherwise the only way to discover the sheet is a button in a hidden control bar.
        Assert.Contains("?", entry.Keys);
        Assert.Contains("F1", entry.Keys);
    }
}

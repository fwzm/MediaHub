using System.Globalization;
using MediaHub.Player;
using MediaHub.Player.Mpv;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Drives the real libmpv instance headlessly and reads every subtitle style property back. This
/// closes the "implemented but never exercised" gap without needing a window, a video file or a
/// second machine: a rejected value leaves the previous/default value in place, so the read-back
/// assertions fail instead of the engine silently dropping the request.
/// </summary>
public sealed class MpvSubtitleStyleTests
{
    [Fact]
    public void Every_subtitle_style_value_reaches_libmpv()
    {
        using var player = new MpvPlayer(Headless());

        player.SetSubtitleVisible(false);
        player.SetSubtitleFontSize(47);
        player.SetSubtitleFont("Segoe UI");
        player.SetSubtitleColor("#12AB34");
        player.SetSubtitleBorderSize(1.5);
        player.SetSubtitlePosition(80);
        player.SetSubtitleDelay(-1.25);
        player.SetSubtitleAssOverride(SubtitleAssOverride.Force);

        Assert.Equal("no", player.ReadPropertyForTests("sub-visibility"));
        Assert.Equal(47.0, Number(player, "sub-font-size"), precision: 3);
        Assert.Equal("Segoe UI", player.ReadPropertyForTests("sub-font"));
        Assert.EndsWith("12AB34", player.ReadPropertyForTests("sub-color")!.TrimStart('#').ToUpperInvariant());
        Assert.Equal(1.5, Number(player, "sub-border-size"), precision: 3);
        Assert.Equal(80.0, Number(player, "sub-pos"), precision: 3);
        Assert.Equal(-1.25, Number(player, "sub-delay"), precision: 3);
        Assert.Equal("force", player.ReadPropertyForTests("sub-ass-override"));
    }

    [Fact]
    public void Out_of_range_style_values_are_clamped_before_they_reach_libmpv()
    {
        using var player = new MpvPlayer(Headless());

        player.SetSubtitleFontSize(5);
        player.SetSubtitleBorderSize(99);
        player.SetSubtitlePosition(200);

        Assert.Equal(8.0, Number(player, "sub-font-size"), precision: 3);
        Assert.Equal(20.0, Number(player, "sub-border-size"), precision: 3);
        Assert.Equal(150.0, Number(player, "sub-pos"), precision: 3);
    }

    private static double Number(MpvPlayer player, string property)
        => double.Parse(player.ReadPropertyForTests(property)!, CultureInfo.InvariantCulture);

    private static MpvPlayerOptions Headless() => new()
    {
        Wid = IntPtr.Zero,
        VideoOutput = "null",
        AudioOutput = "null",
        HwDec = "no",
        HttpProxy = "",
    };
}

using MediaHub.Domain;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// The arithmetic behind the main window's first rectangle and behind restoring a remembered one.
/// Both are pure functions of the work area, so the cases that matter — a second monitor that is gone,
/// a resolution that shrank, a screen smaller than the minimum window — are pinned here instead of
/// only being observed by hand.
/// </summary>
public sealed class WindowGeometryTests
{
    private static readonly WindowRect FullHdWorkArea = new(0, 0, 1920, 1040);

    [Fact]
    public void The_default_covers_about_half_the_work_area_and_is_centred()
    {
        WindowRect rect = WindowGeometry.DefaultFor(FullHdWorkArea);

        Assert.Equal(1358, rect.Width);
        Assert.Equal(735, rect.Height);
        Assert.Equal(281, rect.X);
        Assert.Equal(152, rect.Y);

        double share = (double)rect.Width * rect.Height / ((double)FullHdWorkArea.Width * FullHdWorkArea.Height);
        Assert.InRange(share, 0.49, 0.51);
    }

    [Fact]
    public void The_default_leaves_a_visible_margin_on_every_side()
    {
        WindowRect rect = WindowGeometry.DefaultFor(FullHdWorkArea);

        Assert.True(rect.X >= WindowGeometry.EdgeMargin);
        Assert.True(rect.Y >= WindowGeometry.EdgeMargin);
        Assert.True(FullHdWorkArea.Width - (rect.X + rect.Width) >= WindowGeometry.EdgeMargin);
        Assert.True(FullHdWorkArea.Height - (rect.Y + rect.Height) >= WindowGeometry.EdgeMargin);
    }

    [Fact]
    public void The_default_scales_with_high_dpi_physical_work_areas()
    {
        // A 4K panel at 150 % still reports physical pixels; the default must scale with them rather
        // than being a fixed logical size that ends up tiny or huge on one of the two.
        var workArea = new WindowRect(0, 0, 3840, 2160);
        WindowRect rect = WindowGeometry.DefaultFor(workArea);

        Assert.Equal(2715, rect.Width);
        Assert.Equal(1527, rect.Height);
        double share = (double)rect.Width * rect.Height / ((double)workArea.Width * workArea.Height);
        Assert.InRange(share, 0.49, 0.51);
    }

    [Fact]
    public void The_default_never_exceeds_a_small_work_area()
    {
        var workArea = new WindowRect(0, 0, 800, 600);
        WindowRect rect = WindowGeometry.DefaultFor(workArea);

        Assert.Equal(640, rect.Width);
        Assert.Equal(480, rect.Height);
        Assert.True(rect.X >= 0 && rect.Y >= 0);
        Assert.True(rect.X + rect.Width <= workArea.Width);
        Assert.True(rect.Y + rect.Height <= workArea.Height);
    }

    [Fact]
    public void The_default_fits_a_work_area_smaller_than_the_minimum_window()
    {
        // 640x480 is the old minimum. The window has to give way, otherwise it would open larger than
        // the screen it is on.
        var workArea = new WindowRect(0, 0, 640, 480);
        WindowRect rect = WindowGeometry.DefaultFor(workArea);

        Assert.Equal(592, rect.Width);
        Assert.Equal(432, rect.Height);
        Assert.True(rect.X + rect.Width <= workArea.Width);
        Assert.True(rect.Y + rect.Height <= workArea.Height);
    }

    [Fact]
    public void The_default_respects_a_work_area_that_does_not_start_at_zero()
    {
        // The taskbar can be on the left, and a secondary display can start at a negative origin.
        var workArea = new WindowRect(60, 0, 1860, 1000);
        WindowRect rect = WindowGeometry.DefaultFor(workArea);

        Assert.True(rect.X >= workArea.X);
        Assert.True(rect.Y >= workArea.Y);
        Assert.True(rect.X + rect.Width <= workArea.X + workArea.Width);
        Assert.True(rect.Y + rect.Height <= workArea.Y + workArea.Height);
    }

    [Fact]
    public void A_rectangle_from_a_removed_display_is_brought_onto_the_remaining_one()
    {
        // Saved on a second monitor to the right; that monitor is gone, so the nearest work area is
        // the primary one. Size is kept because it still fits.
        var saved = new WindowRect(3000, 200, 1200, 800);

        WindowRect rect = WindowGeometry.ClampTo(saved, FullHdWorkArea);

        Assert.Equal(1200, rect.Width);
        Assert.Equal(800, rect.Height);
        Assert.Equal(720, rect.X);
        Assert.Equal(200, rect.Y);
        Assert.True(rect.X + rect.Width <= FullHdWorkArea.Width);
    }

    [Fact]
    public void A_rectangle_larger_than_the_current_display_is_shrunk_into_it()
    {
        var saved = new WindowRect(0, 0, 2500, 1500);

        WindowRect rect = WindowGeometry.ClampTo(saved, FullHdWorkArea);

        Assert.Equal(1920, rect.Width);
        Assert.Equal(1040, rect.Height);
        Assert.Equal(0, rect.X);
        Assert.Equal(0, rect.Y);
    }

    [Fact]
    public void A_rectangle_below_the_minimum_size_is_grown_back_to_a_usable_one()
    {
        var saved = new WindowRect(100, 100, 300, 200);

        WindowRect rect = WindowGeometry.ClampTo(saved, FullHdWorkArea);

        Assert.Equal(WindowGeometry.MinimumWidth, rect.Width);
        Assert.Equal(WindowGeometry.MinimumHeight, rect.Height);
        Assert.Equal(100, rect.X);
        Assert.Equal(100, rect.Y);
    }

    [Fact]
    public void A_rectangle_off_the_left_of_a_secondary_display_is_pulled_in()
    {
        var workArea = new WindowRect(-1920, 0, 1920, 1040);
        var saved = new WindowRect(-3000, 200, 1200, 800);

        WindowRect rect = WindowGeometry.ClampTo(saved, workArea);

        Assert.Equal(-1920, rect.X);
        Assert.Equal(200, rect.Y);
        Assert.True(rect.X >= workArea.X);
    }

    [Fact]
    public void A_rectangle_off_the_bottom_right_is_pulled_in_on_both_axes()
    {
        var saved = new WindowRect(5000, -500, 800, 600);

        WindowRect rect = WindowGeometry.ClampTo(saved, FullHdWorkArea);

        Assert.Equal(1120, rect.X);
        Assert.Equal(0, rect.Y);
        Assert.True(rect.X + rect.Width <= FullHdWorkArea.Width);
        Assert.True(rect.Y + rect.Height <= FullHdWorkArea.Height);
    }

    [Fact]
    public void A_work_area_smaller_than_the_minimum_clamps_the_size_down_rather_than_up()
    {
        // Both bounds cannot hold at once on a very small screen; fitting on-screen wins, because a
        // window that overflows is worse than one that is cramped.
        var workArea = new WindowRect(0, 0, 500, 400);
        var saved = new WindowRect(-100, -100, 1200, 900);

        WindowRect rect = WindowGeometry.ClampTo(saved, workArea);

        Assert.Equal(500, rect.Width);
        Assert.Equal(400, rect.Height);
        Assert.Equal(0, rect.X);
        Assert.Equal(0, rect.Y);
    }
}

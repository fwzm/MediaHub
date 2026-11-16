namespace MediaHub.Domain;

/// <summary>A window rectangle in physical pixels, the unit the window APIs use.</summary>
public readonly record struct WindowRect(int X, int Y, int Width, int Height);

/// <summary>
/// Where the main window should open, and how a remembered rectangle is brought back onto the
/// displays that exist now.
/// </summary>
/// <remarks>
/// Kept free of windowing types so the arithmetic can be pinned by tests: the part that goes wrong
/// when a monitor is unplugged is the arithmetic, not the API call.
/// </remarks>
public static class WindowGeometry
{
    /// <summary>Smallest window that still shows the shell's lists and navigation without clipping.</summary>
    public const int MinimumWidth = 640;
    public const int MinimumHeight = 480;

    /// <summary>Gap kept between a default-sized window and the edges of the work area.</summary>
    public const int EdgeMargin = 24;

    /// <summary>
    /// Share of the work area's <i>area</i> the default window covers.
    /// </summary>
    /// <remarks>
    /// Half the area — about 71 % of each dimension — leaves room for another window beside it, which
    /// is what the default is for, while staying large enough to watch video. Sizing by area rather
    /// than by width keeps the proportions the same on 16:9 and 16:10 screens.
    /// </remarks>
    public const double DefaultAreaShare = 0.5;

    /// <summary>The rectangle a fresh profile opens with: about half the work area, centred, with margins.</summary>
    public static WindowRect DefaultFor(WindowRect workArea)
    {
        if (workArea.Width <= 0 || workArea.Height <= 0)
            return new WindowRect(workArea.X, workArea.Y, MinimumWidth, MinimumHeight);

        // The margin only applies where the screen is big enough to spare it; on a display smaller
        // than the minimum window the window still has to fit, so the margin gives way first.
        int maxWidth = Math.Max(1, workArea.Width - EdgeMargin * 2);
        int maxHeight = Math.Max(1, workArea.Height - EdgeMargin * 2);

        double scale = Math.Sqrt(DefaultAreaShare);
        int width = Math.Clamp((int)Math.Round(workArea.Width * scale), Math.Min(MinimumWidth, maxWidth), maxWidth);
        int height = Math.Clamp((int)Math.Round(workArea.Height * scale), Math.Min(MinimumHeight, maxHeight), maxHeight);

        return new WindowRect(
            workArea.X + (workArea.Width - width) / 2,
            workArea.Y + (workArea.Height - height) / 2,
            width,
            height);
    }

    /// <summary>
    /// Pulls a remembered rectangle back inside the work area it is about to be shown on.
    /// </summary>
    /// <remarks>
    /// Shrinking to the work area and then clamping each origin is what makes an unplugged monitor or
    /// a lower resolution safe: the window keeps as much of its remembered size and position as still
    /// fits, and the part that does not fit is moved inwards rather than left off-screen. The minimum
    /// size is raised only up to what the work area can hold, so a tiny display still gets a window
    /// that fits instead of one that overflows.
    /// </remarks>
    public static WindowRect ClampTo(WindowRect desired, WindowRect workArea)
    {
        if (workArea.Width <= 0 || workArea.Height <= 0) return desired;

        int minWidth = Math.Min(MinimumWidth, workArea.Width);
        int minHeight = Math.Min(MinimumHeight, workArea.Height);
        int width = Math.Clamp(desired.Width, minWidth, workArea.Width);
        int height = Math.Clamp(desired.Height, minHeight, workArea.Height);

        return new WindowRect(
            Math.Clamp(desired.X, workArea.X, workArea.X + workArea.Width - width),
            Math.Clamp(desired.Y, workArea.Y, workArea.Y + workArea.Height - height),
            width,
            height);
    }
}

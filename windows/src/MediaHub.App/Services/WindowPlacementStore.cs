using System.Text.Json;
using MediaHub.Domain;
using Microsoft.UI.Windowing;
using Windows.Graphics;

namespace MediaHub.App.Services;

/// <summary>
/// Chooses the main window's first rectangle, remembers the one the user left it at, and puts it
/// back somewhere they can actually see.
/// </summary>
/// <remarks>
/// <para>
/// A fresh profile opens at roughly half the work area with visible margins instead of filling the
/// screen, so the app can sit beside whatever else is in use.
/// </para>
/// <para>
/// Restoring a saved rectangle verbatim is the classic way to lose a window: unplug the second monitor
/// it was on and the window opens off-screen with no way to reach it. The saved rectangle is therefore
/// clamped into the work area of the display that is nearest to it <i>now</i>; the arithmetic lives in
/// <see cref="WindowGeometry"/> so it is covered by tests.
/// </para>
/// <para>
/// Placement lives in the same settings file as everything else, so a portable installation keeps its
/// window position with its other settings and an uninstall takes it away.
/// </para>
/// </remarks>
public static class WindowPlacementStore
{
    /// <summary>The rectangle the window occupies while it is not maximised.</summary>
    public readonly record struct Placement(int X, int Y, int Width, int Height, bool Maximized);

    /// <summary>
    /// Applies the remembered placement, or the default one when nothing usable was saved.
    /// </summary>
    /// <returns>
    /// The normal-state rectangle that was applied, so the caller can persist it even when the window
    /// is immediately maximised and therefore never reports a normal size of its own.
    /// </returns>
    public static WindowRect? RestoreOrApplyDefault(AppWindow window, SettingsStore settings)
    {
        try
        {
            Placement? saved = Read(settings);
            WindowRect applied;

            if (saved is { } placement)
            {
                var desired = new WindowRect(placement.X, placement.Y, placement.Width, placement.Height);
                // The saved rectangle may belong to a monitor that no longer exists. Asking for the
                // nearest work area turns "nowhere" into "the closest screen that does exist".
                WindowRect? workArea = WorkAreaFor(desired);
                applied = workArea is { } area ? WindowGeometry.ClampTo(desired, area) : desired;
            }
            else
            {
                WindowRect? workArea = WorkAreaForWindow(window);
                if (workArea is not { } area) return null;
                applied = WindowGeometry.DefaultFor(area);
            }

            window.MoveAndResize(new RectInt32(applied.X, applied.Y, applied.Width, applied.Height));
            // Maximize() is a show-and-activate call. A quiet test run parks the window off-screen
            // instead, so the saved maximised bit is deliberately not applied there.
            if (saved is { Maximized: true } && !App.QuietStartRequested &&
                window.Presenter is OverlappedPresenter presenter)
                presenter.Maximize();

            return applied;
        }
        catch (Exception)
        {
            // An unusable rectangle must never prevent the window from appearing.
            return null;
        }
    }

    /// <summary>Persists the rectangle to return to, and whether the window was maximised.</summary>
    public static void Save(SettingsStore settings, Placement placement)
    {
        try
        {
            settings.Update(s => s.WindowPlacement = JsonSerializer.Serialize(new SavedPlacement
            {
                X = placement.X,
                Y = placement.Y,
                Width = placement.Width,
                Height = placement.Height,
                Maximized = placement.Maximized,
            }));
        }
        catch (Exception)
        {
            // Losing the window position is not worth failing a shutdown over.
        }
    }

    private static Placement? Read(SettingsStore settings)
    {
        string? json = settings.Current.WindowPlacement;
        if (string.IsNullOrWhiteSpace(json)) return null;

        SavedPlacement? saved;
        try { saved = JsonSerializer.Deserialize<SavedPlacement>(json); }
        catch (JsonException) { return null; }

        if (saved is null) return null;
        if (saved.Width <= 0 || saved.Height <= 0) return null;
        // Wider than any plausible display means a hand-edited or foreign settings file; falling back
        // to the default is better than clamping nonsense into a visible but unusable window.
        if (saved.Width > 20000 || saved.Height > 20000) return null;

        return new Placement(saved.X, saved.Y, saved.Width, saved.Height, saved.Maximized);
    }

    /// <summary>The work area of the display the saved rectangle belongs to, or of the nearest one.</summary>
    private static WindowRect? WorkAreaFor(WindowRect saved)
    {
        try
        {
            var centre = new PointInt32(saved.X + saved.Width / 2, saved.Y + saved.Height / 2);
            DisplayArea? area = DisplayArea.GetFromPoint(centre, DisplayAreaFallback.Nearest);
            return area is null ? null : ToWindowRect(area.WorkArea);
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>The work area of the display the window is currently on.</summary>
    private static WindowRect? WorkAreaForWindow(AppWindow window)
    {
        try
        {
            DisplayArea? area = DisplayArea.GetFromWindowId(window.Id, DisplayAreaFallback.Primary);
            return area is null ? null : ToWindowRect(area.WorkArea);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static WindowRect ToWindowRect(RectInt32 rect)
        => new(rect.X, rect.Y, rect.Width, rect.Height);

    private sealed record SavedPlacement
    {
        public int X { get; init; }
        public int Y { get; init; }
        public int Width { get; init; }
        public int Height { get; init; }
        public bool Maximized { get; init; }
    }
}

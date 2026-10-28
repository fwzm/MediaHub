using System.Text.Json;
using Microsoft.UI.Windowing;
using Windows.Graphics;

namespace MediaHub.App.Services;

/// <summary>
/// Remembers where the window was, and puts it back somewhere the user can actually see it.
/// </summary>
/// <remarks>
/// <para>
/// Restoring a saved rectangle verbatim is the classic way to lose a window: unplug the second monitor
/// it was on and the window opens off-screen with no way to reach it. The saved rectangle is therefore
/// validated against the displays that exist <i>now</i> and discarded if it no longer overlaps any of
/// them.
/// </para>
/// <para>
/// Placement lives in the same settings file as everything else, so a portable installation keeps its
/// window position with its other settings and an uninstall takes it away.
/// </para>
/// </remarks>
public static class WindowPlacementStore
{
    private const int MinimumVisiblePixels = 120;
    private const int MinimumWidth = 640;
    private const int MinimumHeight = 480;

    /// <summary>The rectangle a maximised window should return to.</summary>
    public readonly record struct Placement(int X, int Y, int Width, int Height, bool Maximized);

    public static void Restore(AppWindow window, SettingsStore settings)
    {
        Placement? saved = Read(settings);
        if (saved is not { } placement) return;

        try
        {
            window.MoveAndResize(new RectInt32(placement.X, placement.Y, placement.Width, placement.Height));
            if (placement.Maximized && window.Presenter is OverlappedPresenter presenter) presenter.Maximize();
        }
        catch (Exception)
        {
            // An unusable rectangle must never prevent the window from appearing.
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
        if (saved.Width < MinimumWidth || saved.Height < MinimumHeight) return null;
        // Wider than any plausible display means a hand-edited or foreign settings file.
        if (saved.Width > 20000 || saved.Height > 20000) return null;
        if (!IsReachable(saved)) return null;

        return new Placement(saved.X, saved.Y, saved.Width, saved.Height, saved.Maximized);
    }

    /// <summary>
    /// True when the saved rectangle still lands on a display that exists now.
    /// </summary>
    /// <remarks>
    /// Probes the rectangle's corners and centre rather than enumerating displays, because
    /// <see cref="DisplayArea.GetFromPoint"/> is the API that answers "is there a screen here" for a
    /// point, and three well-chosen points cover the realistic failures: the monitor was removed, the
    /// resolution shrank, or the window was dragged mostly off the edge. A window that merely starts
    /// off-screen but still overlaps a display is accepted on purpose — that is a legitimate position.
    /// </remarks>
    private static bool IsReachable(SavedPlacement saved)
    {
        var probes = new[]
        {
            new PointInt32(saved.X + MinimumVisiblePixels, saved.Y + MinimumVisiblePixels),
            new PointInt32(saved.X + saved.Width / 2, saved.Y + saved.Height / 2),
            new PointInt32(saved.X + saved.Width - MinimumVisiblePixels, saved.Y + saved.Height - MinimumVisiblePixels),
        };

        foreach (PointInt32 probe in probes)
        {
            try
            {
                if (DisplayArea.GetFromPoint(probe, DisplayAreaFallback.None) is not null) return true;
            }
            catch (Exception)
            {
                // The topology could not be queried. Restoring is the safer default: the window is at
                // least where the user last had it.
                return true;
            }
        }
        return false;
    }

    private sealed record SavedPlacement
    {
        public int X { get; init; }
        public int Y { get; init; }
        public int Width { get; init; }
        public int Height { get; init; }
        public bool Maximized { get; init; }
    }
}

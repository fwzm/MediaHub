using MediaHub.App.Interop;
using MediaHub.App.Services;
using MediaHub.Net;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Windows.Graphics;
using WinRT.Interop;

namespace MediaHub.App;

public partial class App : Application
{
    public App()
    {
        // The app is dark by design, and the glass backdrop takes its tint from the app theme. If
        // this were left to follow Windows, a light-mode machine would get a light acrylic sheet
        // under a dark shell - the washed-out grey that makes frosted glass look like a bug.
        RequestedTheme = ApplicationTheme.Dark;
        InitializeComponent();
        UnhandledException += OnUnhandledException;
    }

    /// <summary>Process-wide composition root; see <see cref="AppServices"/>.</summary>
    public static AppServices Services { get; private set; } = null!;

    /// <summary>The single application window. Set during launch; pages use it to enter the player layer.</summary>
    public static MainWindow? MainWindow { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        StartupTrace.Reset();
        StartupTrace.Write("OnLaunched:enter");
        try
        {
            Services = new AppServices();
            StartupTrace.Write("OnLaunched:services-ready");
            ApplyButtonScheme();
            MainWindow = new MainWindow();
            StartupTrace.Write("OnLaunched:window-created");

            // Acceptance harness hook. A GUI test run must be able to put the window on screen
            // without taking the foreground away from whoever is using the machine; Activate()
            // always does take it. AppWindow.Show(false) shows the window and leaves keyboard focus
            // where it was, and MEDIAHUB_QUIET_RECT parks it off the visible desktop. Nothing in the
            // product sets either variable.
            if (QuietStartRequested)
            {
                IntPtr hwnd = WindowNative.GetWindowHandle(MainWindow);
                StartupTrace.Write($"quiet:pre visible={WindowInterop.IsWindowVisible(hwnd)} foreground={WindowInterop.GetForegroundWindow() == hwnd}");
                ApplyQuietPlacement(MainWindow);
                StartupTrace.Write($"quiet:placed visible={WindowInterop.IsWindowVisible(hwnd)} foreground={WindowInterop.GetForegroundWindow() == hwnd}");
                // SW_SHOWNOACTIVATE alone was measured to still hand this window the foreground:
                // the framework activates it as it becomes visible. WS_EX_NOACTIVATE is the style
                // that makes the window ineligible for activation, so the desktop keeps its focus.
                long style = (long)WindowInterop.GetWindowLongPtr(hwnd, WindowInterop.GWL_EXSTYLE);
                WindowInterop.SetWindowLongPtr(hwnd, WindowInterop.GWL_EXSTYLE,
                    (IntPtr)(style | WindowInterop.WS_EX_NOACTIVATE));
                StartupTrace.Write($"quiet:styled visible={WindowInterop.IsWindowVisible(hwnd)} foreground={WindowInterop.GetForegroundWindow() == hwnd}");
                WindowInterop.ShowWindow(hwnd, WindowInterop.SW_SHOWNOACTIVATE);
                StartupTrace.Write($"quiet:post visible={WindowInterop.IsWindowVisible(hwnd)} foreground={WindowInterop.GetForegroundWindow() == hwnd}");
            }
            else
            {
                MainWindow.Activate();
                StartupTrace.Write("OnLaunched:activated");
            }
        }
        catch (Exception ex)
        {
            StartupTrace.Write($"OnLaunched:FAILED {ex.GetType().Name}: {ex.Message}");
            throw;
        }
    }

    /// <summary>
    /// Overlays one of the candidate button dictionaries on top of the shell theme.
    /// </summary>
    /// <remarks>
    /// The dictionaries define the same five style keys as the theme, and a dictionary merged later
    /// wins the lookup, so pages pick the candidate up without any page XAML changing. It is a
    /// diagnostic switch only: nothing in the product sets MEDIAHUB_BUTTON_SCHEME.
    /// </remarks>
    private static void ApplyButtonScheme()
    {
        string scheme = (Environment.GetEnvironmentVariable("MEDIAHUB_BUTTON_SCHEME") ?? "").ToLowerInvariant();
        string file = scheme switch
        {
            "prism" => "Buttons.Prism.xaml",
            "satin" => "Buttons.Satin.xaml",
            "filigree" => "Buttons.Filigree.xaml",
            _ => "",
        };

        if (file.Length == 0)
        {
            StartupTrace.Write("buttons:current");
            return;
        }

        Current.Resources.MergedDictionaries.Add(new ResourceDictionary
        {
            Source = new Uri("ms-appx:///Themes/" + file),
        });
        StartupTrace.Write("buttons:" + scheme);
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        // Surface the failure instead of letting the process die silently mid-playback.
        try { Services?.Log.Error("app", $"未处理异常：{e.Exception.GetType().Name}: {e.Exception.Message}", e.Exception); }
        catch { /* logging must never mask the original failure */ }
    }

    /// <summary>
    /// True when the acceptance harness asked for a window that must not take the foreground.
    /// Nothing in the product sets MEDIAHUB_QUIET_START.
    /// </summary>
    internal static bool QuietStartRequested
        => Environment.GetEnvironmentVariable("MEDIAHUB_QUIET_START") == "1";

    /// <summary>
    /// Parks the window at "x,y,w,h" (physical pixels) before it is shown, when the acceptance
    /// harness asks for a quiet start. A saved maximised placement would otherwise cover the
    /// desktop regardless of the rectangle, so the presenter is restored first.
    /// </summary>
    private static void ApplyQuietPlacement(MainWindow window)
    {
        string? spec = Environment.GetEnvironmentVariable("MEDIAHUB_QUIET_RECT");
        if (string.IsNullOrWhiteSpace(spec)) return;
        string[] parts = spec.Split(',');
        if (parts.Length != 4) return;
        if (!int.TryParse(parts[0], out int x) || !int.TryParse(parts[1], out int y) ||
            !int.TryParse(parts[2], out int width) || !int.TryParse(parts[3], out int height)) return;
        if (width <= 0 || height <= 0) return;
        try
        {
            // Restore() is itself a show-and-activate call: calling it on a window that is already
            // in the restored state was measured to make the window visible and take the foreground
            // before ShowWindow(SW_SHOWNOACTIVATE) ever ran. Only a genuinely maximised placement
            // needs it, and the harness parks a maximised window by normalising the saved placement.
            if (window.AppWindow.Presenter is OverlappedPresenter presenter &&
                presenter.State != OverlappedPresenterState.Restored) presenter.Restore();
            window.AppWindow.MoveAndResize(new RectInt32(x, y, width, height));
        }
        catch (Exception ex)
        {
            StartupTrace.Write("quiet-placement:FAILED " + ex.GetType().Name);
        }
    }
}

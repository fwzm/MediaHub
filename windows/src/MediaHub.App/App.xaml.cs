using MediaHub.App.Services;
using MediaHub.Net;
using Microsoft.UI.Xaml;

namespace MediaHub.App;

public partial class App : Application
{
    public App()
    {
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
            MainWindow = new MainWindow();
            StartupTrace.Write("OnLaunched:window-created");
            MainWindow.Activate();
            StartupTrace.Write("OnLaunched:activated");
        }
        catch (Exception ex)
        {
            StartupTrace.Write($"OnLaunched:FAILED {ex.GetType().Name}: {ex.Message}");
            throw;
        }
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        // Surface the failure instead of letting the process die silently mid-playback.
        try { Services?.Log.Error("app", $"未处理异常：{e.Exception.GetType().Name}: {e.Exception.Message}", e.Exception); }
        catch { /* logging must never mask the original failure */ }
    }
}

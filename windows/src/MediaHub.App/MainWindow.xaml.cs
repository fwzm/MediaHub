using MediaHub.App.Services;
using MediaHub.App.Views;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WinRT.Interop;

namespace MediaHub.App;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        Services.StartupTrace.Write("MainWindow:ctor-enter");
        InitializeComponent();
        Services.StartupTrace.Write("MainWindow:xaml-loaded");

        // The engine's native video surface is parented to this window, so the handle must be
        // captured once the window exists and before the first playback attempt.
        IntPtr hwnd = WindowNative.GetWindowHandle(this);
        Services.StartupTrace.Write("MainWindow:hwnd=0x" + hwnd.ToInt64().ToString("X"));
        App.Services.PlayerHost.Initialize(hwnd);
        App.Services.InitializeArtwork(DispatcherQueue);
        Services.StartupTrace.Write("MainWindow:player-ready");

        Title = "MediaHub";
        ShellFrame.Navigate(typeof(HomePage));
        Nav.SelectedItem = Nav.MenuItems[0];

        // Restoring configured sources hits the network, so it runs after the shell is on screen
        // rather than delaying first paint.
        _ = RestoreSourcesAsync();

        TryOpenFromCommandLine();
        Services.StartupTrace.Write("MainWindow:ctor-exit");
    }

    public IntPtr WindowHandle => WindowNative.GetWindowHandle(this);

    /// <summary>Used by pages to reach the shell for navigation and for entering the player layer.</summary>
    public static MainWindow? Current => App.MainWindow;

    private async Task RestoreSourcesAsync()
    {
        try
        {
            await App.Services.Servers.InitializeAsync();
            Services.StartupTrace.Write("MainWindow:sources-restored");
        }
        catch (Exception ex)
        {
            Services.StartupTrace.Write("MainWindow:sources-restore-failed " + ex.GetType().Name);
            App.Services.Log.Error("sources", "恢复媒体源失败", ex);
        }
    }

    private void OnNavSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem item) return;
        switch (item.Tag as string)
        {
            case "home": ShellFrame.Navigate(typeof(HomePage)); break;
            case "libraries": ShellFrame.Navigate(typeof(LibrariesPage)); break;
            case "search": ShellFrame.Navigate(typeof(SearchPage)); break;
            case "continue": ShellFrame.Navigate(typeof(ContinueWatchingPage)); break;
            case "sources": ShellFrame.Navigate(typeof(SourcesPage)); break;
            case "settings": ShellFrame.Navigate(typeof(SettingsPage)); break;
        }
    }

    /// <summary>Navigates the shell frame and keeps the navigation highlight in step.</summary>
    public void NavigateShell(Type pageType, object? parameter = null)
    {
        Services.StartupTrace.Write("nav: " + pageType.Name + " param=" + (parameter?.GetType().Name ?? "null"));
        ShellFrame.Navigate(pageType, parameter);
        string? tag = pageType switch
        {
            _ when pageType == typeof(HomePage) => "home",
            _ when pageType == typeof(LibrariesPage) => "libraries",
            _ when pageType == typeof(SearchPage) => "search",
            _ when pageType == typeof(ContinueWatchingPage) => "continue",
            _ when pageType == typeof(SourcesPage) => "sources",
            _ when pageType == typeof(SettingsPage) => "settings",
            _ => null,
        };
        if (tag is null) return;
        foreach (var menuItem in Nav.MenuItems.OfType<NavigationViewItem>())
        {
            if ((menuItem.Tag as string) == tag) { Nav.SelectedItem = menuItem; break; }
        }
    }

    /// <summary>Enters the player layer with a fully opaque full-window surface.</summary>
    public void EnterPlayer(PlaybackRequest request)
    {
        Nav.Visibility = Visibility.Collapsed;
        PlayerLayer.Visibility = Visibility.Visible;
        PlayerFrame.Navigate(typeof(PlayerPage), request);
    }

    /// <summary>Returns to the shell and tears down the player page instance.</summary>
    public void ExitPlayer()
    {
        PlayerFrame.Content = null;
        PlayerLayer.Visibility = Visibility.Collapsed;
        Nav.Visibility = Visibility.Visible;
    }

    /// <summary>
    /// Opens a media file passed on the command line — the same entry point a shell
    /// "Open with" hand-off uses, which is what makes double-clicking a video land in the player.
    /// </summary>
    private void TryOpenFromCommandLine()
    {
        string[] args = Environment.GetCommandLineArgs();
        if (args.Length < 2) return;

        string candidate = args[1];
        if (!File.Exists(candidate)) return;
        if (!LocalLibraryService.SupportedVideoExtensions.Contains(Path.GetExtension(candidate))) return;

        EnterPlayer(new PlaybackRequest
        {
            LocalPath = candidate,
            Title = Path.GetFileNameWithoutExtension(candidate),
            DisplayUrl = candidate,
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(candidate),
        });
    }
}

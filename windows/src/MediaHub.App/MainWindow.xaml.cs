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
        Services.StartupTrace.Write($"MainWindow:hwnd=0x{hwnd.ToInt64():X}");
        App.Services.PlayerHost.Initialize(hwnd);
        Services.StartupTrace.Write("MainWindow:player-ready");

        Title = "MediaHub";
        ShellFrame.Navigate(typeof(HomePage));
        Nav.SelectedItem = Nav.MenuItems[0];

        TryOpenFromCommandLine();
        Services.StartupTrace.Write("MainWindow:ctor-exit");
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
            Path = candidate,
            Title = Path.GetFileNameWithoutExtension(candidate),
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(candidate),
        });
    }

    public IntPtr WindowHandle => WindowNative.GetWindowHandle(this);

    private void OnNavSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem item) return;
        switch (item.Tag as string)
        {
            case "home": ShellFrame.Navigate(typeof(HomePage)); break;
            case "local": ShellFrame.Navigate(typeof(LocalLibraryPage)); break;
            case "sources": ShellFrame.Navigate(typeof(SourcesPage)); break;
            case "settings": ShellFrame.Navigate(typeof(SettingsPage)); break;
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
}

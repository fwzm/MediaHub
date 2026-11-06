using MediaHub.Domain;
using MediaHub.App.Services;
using MediaHub.App.Views;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Graphics;
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

        WindowPlacementStore.Restore(AppWindow, App.Services.Settings);
        AttachWindowPlacementTracking();
        AttachFileDrop();

        // Restoring configured sources hits the network, so it runs after the shell is on screen
        // rather than delaying first paint.
        _ = RestoreSourcesAsync();

        TryOpenFromCommandLine();
        Services.StartupTrace.Write("MainWindow:ctor-exit");
    }

    public IntPtr WindowHandle => WindowNative.GetWindowHandle(this);

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
            case "favorites": ShellFrame.Navigate(typeof(FavoritesPage)); break;
            case "playlists": ShellFrame.Navigate(typeof(PlaylistsPage)); break;
            case "sources": ShellFrame.Navigate(typeof(SourcesPage)); break;
            case "sync": ShellFrame.Navigate(typeof(SyncPage)); break;
            case "settings": ShellFrame.Navigate(typeof(SettingsPage)); break;
        }
    }

    // ---------------------------------------------------------------- window placement

    private RectInt32 _restoredBounds;
    private bool _hasRestoredBounds;
    private bool _closingHandled;

    /// <summary>
    /// Tracks the rectangle to return to, and persists it on close.
    /// </summary>
    /// <remarks>
    /// The bounds are captured while the window is in its normal state. Reading them at shutdown
    /// instead would store the maximised size as the "restored" size, so un-maximising after a restart
    /// would leave a full-screen-sized window.
    /// </remarks>
    private void AttachWindowPlacementTracking()
    {
        void CaptureIfNormal()
        {
            try
            {
                // OverlappedPresenter exposes its window state, not booleans. Reading the state is
                // what makes "capture only while normal" correct: a maximised window's size is the
                // screen, and storing that as the restore size would leave the window un-maximisable
                // to anything smaller after a restart.
                if (AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Restored })
                {
                    _restoredBounds = new RectInt32(AppWindow.Position.X, AppWindow.Position.Y,
                        AppWindow.Size.Width, AppWindow.Size.Height);
                    _hasRestoredBounds = true;
                }
            }
            catch (Exception) { }
        }

        CaptureIfNormal();
        AppWindow.Changed += (_, args) => { if (args.DidSizeChange || args.DidPositionChange) CaptureIfNormal(); };

        AppWindow.Closing += (sender, args) =>
        {
            if (_closingHandled) return;
            _closingHandled = true;
            CaptureIfNormal();

            bool maximized = AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Maximized };
            RectInt32 bounds = _hasRestoredBounds
                ? _restoredBounds
                : new RectInt32(AppWindow.Position.X, AppWindow.Position.Y, AppWindow.Size.Width, AppWindow.Size.Height);
            WindowPlacementStore.Save(App.Services.Settings, new WindowPlacementStore.Placement(
                bounds.X, bounds.Y, bounds.Width, bounds.Height, maximized));

            // The engine and the player host own native resources. Disposing them on close is what
            // makes "no leftover process after exit" true rather than hopeful.
            args.Cancel = true;
            _ = ShutdownAsync();
        };
    }

    private async Task ShutdownAsync()
    {
        try
        {
            await App.Services.ShutdownAsync();
        }
        catch (Exception ex)
        {
            Services.StartupTrace.Write("MainWindow:shutdown-failed " + ex.GetType().Name);
        }
        Close();
    }

    // ---------------------------------------------------------------- file drop

    private void AttachFileDrop()
    {
        if (Content is not UIElement root) return;
        root.AllowDrop = true;

        root.DragOver += (_, args) =>
        {
            args.AcceptedOperation = Windows.ApplicationModel.DataTransfer.DataPackageOperation.Copy;
            args.DragUIOverride.Caption = "播放";
            args.DragUIOverride.IsCaptionVisible = true;
            args.Handled = true;
        };

        root.Drop += async (_, args) =>
        {
            try
            {
                if (!args.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems)) return;
                var items = await args.DataView.GetStorageItemsAsync();
                OpenPaths(items.Select(i => i.Path).ToList());
            }
            catch (Exception ex)
            {
                Services.StartupTrace.Write("drop:failed " + ex.GetType().Name);
            }
        };
    }

    /// <summary>
    /// Puts dropped, picked or command-line files into the temporary queue and shows the player.
    /// </summary>
    /// <remarks>
    /// Every entry point — drag and drop, 文件 &gt; 打开视频, 文件 &gt; 加入播放队列, a shell "Open with"
    /// hand-off — funnels through here, so the queue has exactly one intake that can be reasoned
    /// about and tested. Replace and append are the only two behaviours, and the caller states which
    /// one it wants instead of it being inferred from how many files happened to arrive.
    /// </remarks>
    public void OpenPaths(IReadOnlyList<string> rawPaths, QueueIntake mode = QueueIntake.Replace)
    {
        List<string> paths = DroppedMedia.Expand(rawPaths);
        Services.StartupTrace.Write("queue:open mode=" + mode + " paths=" + paths.Count);
        if (paths.Count == 0)
        {
            Services.StartupTrace.Write("queue:empty");
            return;
        }

        List<PlaybackRequest> requests = paths.Select(DroppedMedia.ToRequest).ToList();
        QueueIntakeResult intake = App.Services.Queue.Intake(requests, mode);
        Services.StartupTrace.Write(
            "queue:intake mode=" + intake.Mode
            + " requested=" + intake.Requested
            + " added=" + intake.Added
            + " duplicates=" + intake.Duplicates
            + " count=" + App.Services.Queue.Count);

        PlaybackRequest? current = App.Services.Queue.Current;
        if (current is null) return;

        if (PlayerLayer.Visibility == Visibility.Visible && PlayerFrame.Content is PlayerPage page)
        {
            page.RefreshQueueView();
            // Appending while something plays must not interrupt it: the new entries are visible in
            // the queue and start only when the current item ends or the user asks for them.
            if (intake.Mode == QueueIntake.Append) return;

            _ = page.PlayQueueEntryAsync(current);
            return;
        }

        EnterPlayer(current);
    }

    // ---------------------------------------------------------------- file menu

    private async void OnOpenVideoClick(object sender, RoutedEventArgs e)
    {
        // Recorded before anything can fail: the difference between "the menu command never ran" and
        // "it ran and the picker or the file let it down" is the first thing an automated check needs,
        // and it cannot be recovered after the fact from a transient dialog.
        Services.StartupTrace.Write("menu:openvideo-invoked");
        try
        {
            // Windows App SDK 1.8 desktop picker. The previous code used the legacy
            // Windows.Storage.Pickers picker plus InitializeWithWindow, and against an unpackaged
            // WinUI 3 window that combination never returned and never threw: the menu command ran,
            // PickMultipleFilesAsync hung, and no dialog window ever appeared on the desktop. Both
            // were confirmed by instrumentation rather than assumed - see windows/docs/W5-1-R4-GATE-B1.md.
            //
            // The two APIs are deliberately not mixed: this one takes a WindowId and returns a
            // PickFileResult carrying Path, and no StorageFile or InitializeWithWindow is involved.
            Services.StartupTrace.Write("menu:openvideo-picker-created");
            // Multi-select, because "open some videos" is one gesture for the user and one queue for
            // the player. The picker returns them in the order the shell lists them; that order is
            // preserved and becomes the queue order.
            IReadOnlyList<string> picked = await FilePickerService.PickFilesAsync(LocalLibraryService.SupportedMediaExtensions);
            if (picked.Count == 0)
            {
                // Cancelling must leave the current playback session untouched.
                Services.StartupTrace.Write("menu:openvideo-cancelled");
                return;
            }

            Services.StartupTrace.Write("menu:openvideo-selected count=" + picked.Count);
            OpenPaths(picked);
        }
        catch (Exception ex)
        {
            Services.StartupTrace.Write("menu:openvideo-failed " + ex.GetType().Name + ": " + ex.Message);
        }
    }

    /// <summary>
    /// Adds files to the queue of the item already playing, without interrupting it.
    /// </summary>
    /// <remarks>
    /// This is the "加入" half of the replace/append pair: 打开视频 replaces what is playing, this one
    /// leaves it alone. Having both as separate commands is what removes the guesswork - the user
    /// never has to reason about whether the current film will be interrupted.
    /// </remarks>
    private async void OnAddToQueueClick(object sender, RoutedEventArgs e)
    {
        Services.StartupTrace.Write("menu:addtoqueue-invoked");
        try
        {
            Services.StartupTrace.Write("menu:addtoqueue-picker-created");
            IReadOnlyList<string> picked = await FilePickerService.PickFilesAsync(LocalLibraryService.SupportedMediaExtensions);
            if (picked.Count == 0)
            {
                Services.StartupTrace.Write("menu:addtoqueue-cancelled");
                return;
            }

            Services.StartupTrace.Write("menu:addtoqueue-selected count=" + picked.Count);
            OpenPaths(picked, QueueIntake.Append);
        }
        catch (Exception ex)
        {
            Services.StartupTrace.Write("menu:addtoqueue-failed " + ex.GetType().Name + ": " + ex.Message);
        }
    }

    private async void OnOpenFolderClick(object sender, RoutedEventArgs e)
    {
        Services.StartupTrace.Write("open-folder:invoked");
        try
        {
            string? folder = await FilePickerService.PickFolderAsync();
            if (folder is null)
            {
                Services.StartupTrace.Write("open-folder:cancelled");
                return;
            }
            Services.StartupTrace.Write("open-folder:selected");

            // Opening a folder adds it as a local library root and shows it, rather than playing a
            // file: a folder is a place, and the user asked to see what is in it.
            AddLocalFolder(folder);
            // The library page rescans on load; it needs no invalidation hook, only to be shown.
            NavigateShell(typeof(LocalLibraryPage));
        }
        catch (Exception ex)
        {
            Services.StartupTrace.Write("open-folder:failed " + ex.GetType().Name + ": " + ex.Message);
        }
    }

    /// <summary>Adds a folder to the local library roots, keeping the list free of duplicates.</summary>
    public void AddLocalFolder(string path)
    {
        App.Services.Settings.Update(s =>
        {
            if (!s.LocalFolders.Contains(path, StringComparer.OrdinalIgnoreCase)) s.LocalFolders.Add(path);
        });
    }

    private void OnPlayPauseMenuClick(object sender, RoutedEventArgs e)
    {
        try { App.Services.PlayerHost.Player?.TogglePause(); } catch (Exception) { }
    }

    private void OnFullscreenMenuClick(object sender, RoutedEventArgs e)
    {
        if (PlayerLayer.Visibility != Visibility.Visible) return;
        if (PlayerFrame.Content is PlayerPage page) page.ToggleFullScreen();
    }

    private void OnAboutClick(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = "关于 MediaHub",
            Content = "MediaHub Windows 客户端\n版本 " + AppVersion() + "\n\n播放引擎：libmpv（GPL 构建，详见 THIRD-PARTY-NOTICES.md）",
            CloseButtonText = "关闭",
        };
        _ = dialog.ShowAsync();
    }

    private static string AppVersion()
    {
        try
        {
            return typeof(MainWindow).Assembly.GetName().Version?.ToString() ?? "未知";
        }
        catch { return "未知"; }
    }

    private void OnExitClick(object sender, RoutedEventArgs e) => Close();

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
            _ when pageType == typeof(FavoritesPage) => "favorites",
            _ when pageType == typeof(PlaylistsPage) => "playlists",
            _ when pageType == typeof(PlaylistDetailPage) => "playlists",
            _ when pageType == typeof(SourcesPage) => "sources",
            _ when pageType == typeof(SyncPage) => "sync",
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
        // A queue left over from an earlier session would auto-advance into items the user is no
        // longer watching, so entering the player with an unrelated request starts a clean queue.
        if (App.Services.Queue.Current is not { } current
            || !string.Equals(PlaybackQueue.IdentityOf(current), PlaybackQueue.IdentityOf(request), StringComparison.Ordinal))
        {
            App.Services.Queue.Clear();
        }

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

        // Traced even in the do-nothing case: without a record of "the shell handed us this and we
        // dropped it", a file association that opens nothing is indistinguishable from one that was
        // never invoked, and the previous version of this method had exactly that blind spot.
        Services.StartupTrace.Write("cli:args=" + (args.Length - 1));
        if (args.Length < 2)
        {
            Services.StartupTrace.Write("cli:no-argument");
            return;
        }

        // Every argument is considered, not just the first: dropping three videos on the executable
        // hands over three paths, and they belong in the same queue as if they had been picked.
        var accepted = new List<string>();
        foreach (string candidate in args.Skip(1))
        {
            if (!File.Exists(candidate))
            {
                Services.StartupTrace.Write("cli:not-a-file " + Path.GetFileName(candidate));
                continue;
            }
            // Audio counts here: double-clicking an .aac must reach the player, and the engine plays it.
            if (!LocalLibraryService.IsSupportedMediaFile(candidate))
            {
                Services.StartupTrace.Write("cli:unsupported-extension " + Path.GetExtension(candidate));
                continue;
            }
            accepted.Add(candidate);
        }

        if (accepted.Count == 0)
        {
            Services.StartupTrace.Write("cli:no-supported-file");
            return;
        }

        Services.StartupTrace.Write("cli:opening count=" + accepted.Count);
        OpenPaths(accepted);
    }
}

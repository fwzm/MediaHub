using MediaHub.Domain;
using MediaHub.App.Services;
using MediaHub.Net;
using MediaHub.Net.Security;
using MediaHub.Player;
using MediaHub.Storage;

namespace MediaHub.App;

/// <summary>
/// Hand-rolled composition root. Deliberately explicit rather than container-driven: the object
/// graph is small, and an explicit root makes the lifetime of the engine and of the credential
/// stores auditable at a glance.
/// </summary>
public sealed class AppServices : IDisposable
{
    public AppServices()
    {
        // Rolling files, not just the memory ring: the earliest lines are exactly the ones a bug
        // report needs after a long session. Redaction happens in LogService before the sink sees
        // anything.
        Log = new LogService(new LogFileSink(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "logs")));
        Settings = new SettingsStore();
        PlaybackHistory = new PlaybackHistoryStore();
        // Saved playlists are local user data: no credentials, no network, no UI thread needed.
        Playlists = new PlaylistStore();
        LocalLibrary = new LocalLibraryService(Settings);
        // The engine writes its own cache files; this owns the directory so settings can show and
        // clear it without reaching into mpv.
        DiskCache = new PlayerCacheStore();
        PlayerHost = new PlayerHostService(Settings, Log, DiskCache);
        ApplyDiskCacheCapacity();

        // The vault is the only component that can decrypt stored passwords and session tokens. It is
        // created here so nothing else has a reason to reach for DPAPI directly.
        Vault = new DpapiCredentialStore();
        Servers = new ServerManager(Settings, Log, Vault);
        Search = new SearchService(Servers, Log);
        Playback = new PlaybackCoordinator(Servers, Log, PlaybackHistory, Settings);
        Queue = new PlaybackQueue();
        Update = new UpdateService(Settings, Log);
    }

    public LogService Log { get; }
    public SettingsStore Settings { get; }
    public PlaybackHistoryStore PlaybackHistory { get; }
    public LocalLibraryService LocalLibrary { get; }
    public PlayerHostService PlayerHost { get; }
    public PlayerCacheStore DiskCache { get; }
    public ICredentialStore Vault { get; }
    public ServerManager Servers { get; }
    public SearchService Search { get; }
    public PlaybackCoordinator Playback { get; }

    /// <summary>Checks the configured source, verifies the package, and hands the swap to update.ps1.</summary>
    public UpdateService Update { get; }

    /// <summary>Temporary queue produced by opening or dropping several files at once.</summary>
    public PlaybackQueue Queue { get; }

    /// <summary>
    /// The user's durable playlists. Deliberately separate from <see cref="Queue"/>: a playlist is
    /// never auto-enqueued, and a queue is never written into a playlist. Not <see cref="IDisposable"/>:
    /// every mutation is already on disk when the call returns.
    /// </summary>
    public PlaylistStore Playlists { get; }

    /// <summary>Artwork cache. Needs the UI thread, so it is created when the window exists.</summary>
    public ImageCache? Images { get; private set; }

    public void InitializeArtwork(Microsoft.UI.Dispatching.DispatcherQueue dispatcher)
        => Images ??= new ImageCache(Log, dispatcher);

    /// <summary>Trims the engine cache directory to the configured budget (called at startup).</summary>
    public void ApplyDiskCacheCapacity()
    {
        var s = Settings.Current;
        if (!s.DiskCacheEnabled)
        {
            DiskCache.Clear();
            return;
        }
        DiskCache.EnforceCapacity(s.DiskCacheCapacityMB * 1024L * 1024L);
    }

    /// <summary>
    /// Applies the end-of-playback policy: either empty the directory or keep it within the budget.
    /// </summary>
    public void ApplyDiskCachePolicy()
    {
        var s = Settings.Current;
        if (!s.DiskCacheEnabled || s.ClearDiskCacheOnPlaybackEnd)
        {
            DiskCache.Clear();
            return;
        }
        DiskCache.EnforceCapacity(s.DiskCacheCapacityMB * 1024L * 1024L);
    }

    /// <summary>
    /// Releases everything owned by the process, in dependency order.
    /// </summary>
    /// <remarks>
    /// Called from the window's closing handler. Without it the engine keeps its decoder threads and
    /// the relay keeps its listener, and the process can outlive its window — which is exactly the
    /// "leftover process after exit" symptom.
    /// </remarks>
    public async Task ShutdownAsync()
    {
        try { await Playback.DisposeAsync(); } catch { }
        try { PlayerHost.DisposeAsync().AsTask().GetAwaiter().GetResult(); } catch { }
        try { ApplyDiskCachePolicy(); } catch { }
        try { Servers.Dispose(); } catch { }
    }

    public void Dispose()
    {
        Playback.Dispose();
        Servers.Dispose();
        Images?.Dispose();
        PlayerHost.DisposeAsync().AsTask().GetAwaiter().GetResult();
        LocalLibrary.Dispose();
        PlaybackHistory.Dispose();
        Update.Dispose();
    }
}

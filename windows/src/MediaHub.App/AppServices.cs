using MediaHub.Domain;
using MediaHub.App.Services;
using MediaHub.Net;
using MediaHub.Net.Security;
using MediaHub.Storage;
using MediaHub.Sync;

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
        // Cross-device sync keeps its own mirror of the source list. One instance is shared by the source
        // manager (which assigns identities) and the sync page (which exports and previews), so both see
        // the same records and neither can overwrite the other's view.
        SyncSources = new FileLocalSourceStore();
        LocalLibrary = new LocalLibraryService(Settings);
        PlayerHost = new PlayerHostService(Settings, Log);

        // The vault is the only component that can decrypt stored passwords and session tokens. It is
        // created here so nothing else has a reason to reach for DPAPI directly.
        Vault = new DpapiCredentialStore();
        Servers = new ServerManager(Settings, Log, Vault, SyncSources);
        Search = new SearchService(Servers, Log);
        Playback = new PlaybackCoordinator(Servers, Log, PlaybackHistory);
        Queue = new PlaybackQueue();
        Sync = new SourceSyncService(Settings, Log, Servers, SyncSources);
    }

    public LogService Log { get; }
    public SettingsStore Settings { get; }
    public PlaybackHistoryStore PlaybackHistory { get; }
    public LocalLibraryService LocalLibrary { get; }
    public PlayerHostService PlayerHost { get; }
    public ICredentialStore Vault { get; }
    public ServerManager Servers { get; }
    public SearchService Search { get; }
    public PlaybackCoordinator Playback { get; }

    /// <summary>Temporary queue produced by opening or dropping several files at once.</summary>
    public PlaybackQueue Queue { get; }

    /// <summary>
    /// The user's durable playlists. Deliberately separate from <see cref="Queue"/>: a playlist is
    /// never auto-enqueued, and a queue is never written into a playlist. Not <see cref="IDisposable"/>:
    /// every mutation is already on disk when the call returns.
    /// </summary>
    public PlaylistStore Playlists { get; }

    /// <summary>
    /// The mirror of this device's sources that cross-device sync reads and writes
    /// (<c>%LOCALAPPDATA%\MediaHub\sync-sources.json</c>). Not the same thing as <see cref="Servers"/>:
    /// settings.json stays authoritative for what the app restores, this file carries what the sync
    /// contract needs per source.
    /// </summary>
    public FileLocalSourceStore SyncSources { get; }

    /// <summary>
    /// Cross-device source sync: export this device's envelope, preview a peer's, verify a source.
    /// Never applies a plan — that needs explicit per-source approval and lands in W6-3.
    /// </summary>
    public SourceSyncService Sync { get; }

    /// <summary>Artwork cache. Needs the UI thread, so it is created when the window exists.</summary>
    public ImageCache? Images { get; private set; }

    public void InitializeArtwork(Microsoft.UI.Dispatching.DispatcherQueue dispatcher)
        => Images ??= new ImageCache(Log, dispatcher);

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
    }
}

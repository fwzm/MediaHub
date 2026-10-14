using MediaHub.App.Services;
using MediaHub.Net;
using MediaHub.Net.Security;

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
        Log = new LogService();
        Settings = new SettingsStore();
        PlaybackHistory = new PlaybackHistoryStore();
        LocalLibrary = new LocalLibraryService(Settings);
        PlayerHost = new PlayerHostService(Settings, Log);

        // The vault is the only component that can decrypt stored passwords and session tokens. It is
        // created here so nothing else has a reason to reach for DPAPI directly.
        Vault = new DpapiCredentialStore();
        Servers = new ServerManager(Settings, Log, Vault);
        Search = new SearchService(Servers, Log);
        Playback = new PlaybackCoordinator(Servers, Log, PlaybackHistory);
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

    /// <summary>Artwork cache. Needs the UI thread, so it is created when the window exists.</summary>
    public ImageCache? Images { get; private set; }

    public void InitializeArtwork(Microsoft.UI.Dispatching.DispatcherQueue dispatcher)
        => Images ??= new ImageCache(Log, dispatcher);

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

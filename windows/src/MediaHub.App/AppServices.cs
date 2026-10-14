using MediaHub.App.Services;
using MediaHub.Net;

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
        SourceRegistry = new SourceRegistry(Settings);
        SourceRegistry.RefreshLocal();
    }

    public LogService Log { get; }
    public SettingsStore Settings { get; }
    public PlaybackHistoryStore PlaybackHistory { get; }
    public LocalLibraryService LocalLibrary { get; }
    public PlayerHostService PlayerHost { get; }
    public SourceRegistry SourceRegistry { get; }

    public void Dispose()
    {
        SourceRegistry.Dispose();
        PlayerHost.DisposeAsync().AsTask().GetAwaiter().GetResult();
        LocalLibrary.Dispose();
        PlaybackHistory.Dispose();
    }
}

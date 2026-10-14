using MediaHub.Domain;

namespace MediaHub.App.Services;

/// <summary>A configured media source plus the capabilities it actually has at runtime.</summary>
public sealed record ConfiguredSource(
    string ServerId,
    ServerType Type,
    string DisplayName,
    ProviderCapability RuntimeCapabilities)
{
    public string CapabilitySummary
    {
        get
        {
            if (RuntimeCapabilities == ProviderCapability.None) return "尚无可用能力";
            var names = Enum.GetValues<ProviderCapability>()
                .Where(c => c != ProviderCapability.None && RuntimeCapabilities.HasFlag(c))
                .Select(c => c.ToString());
            return string.Join(" / ", names);
        }
    }

    /// <summary>Local folders are the only source type wired up in the current build.</summary>
    public static ConfiguredSource FromLocalFolders(IEnumerable<string> folders)
    {
        var list = folders.ToList();
        return new ConfiguredSource(
            "local",
            ServerType.Local,
            "本地存储",
            list.Count == 0
                ? ProviderCapability.None
                : ProviderCapability.Browse | ProviderCapability.SubtitleDiscovery);
    }
}

/// <summary>
/// Central registry of configured media sources.
/// </summary>
/// <remarks>
/// Runtime capabilities are derived from what is actually constructed, never from a static
/// per-vendor table. That is the Windows counterpart of the Android rule (ADR-014/ADR-022): the UI
/// must not enable a feature because a vendor "usually" supports it.
/// </remarks>
public sealed class SourceRegistry
{
    private readonly SettingsStore _settings;
    private readonly List<ConfiguredSource> _sources = new();
    private readonly List<ConfiguredSource> _configured = new();

    public SourceRegistry(SettingsStore settings) => _settings = settings;

    public IReadOnlyList<ConfiguredSource> Sources
    {
        get
        {
            lock (_configured)
            {
                var all = _configured.ToList();
                all.Add(ConfiguredSource.FromLocalFolders(_settings.Current.LocalFolders));
                return all;
            }
        }
    }

    public void Register(ConfiguredSource source)
    {
        lock (_configured)
        {
            _configured.RemoveAll(s => s.ServerId == source.ServerId);
            _configured.Add(source);
        }
    }

    public void Unregister(string serverId)
    {
        lock (_configured) _configured.RemoveAll(s => s.ServerId == serverId);
    }
}

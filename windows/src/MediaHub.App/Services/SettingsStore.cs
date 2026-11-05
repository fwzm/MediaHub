using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediaHub.App.Services;

/// <summary>
/// Persisted user settings. Grouped the way the settings page presents them: playback, subtitles,
/// network, appearance. Advanced engine knobs stay in <see cref="EngineOptions"/> so the default
/// surface is not a wall of decoder flags.
/// </summary>
public sealed class SettingsStore
{
    private static readonly string SettingsDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub");

    private static readonly string SettingsPath = Path.Combine(SettingsDirectory, "settings.json");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly object _gate = new();
    private MediaHubSettings _current;

    public SettingsStore()
    {
        _current = Load();
    }

    public MediaHubSettings Current { get { lock (_gate) return _current; } }

    public event EventHandler<MediaHubSettings>? Changed;

    public void Update(Action<MediaHubSettings> mutate)
    {
        lock (_gate)
        {
            mutate(_current);
            SaveLocked();
        }
        Changed?.Invoke(this, Current);
    }

    public void Reset()
    {
        lock (_gate)
        {
            _current = new MediaHubSettings();
            SaveLocked();
        }
        Changed?.Invoke(this, Current);
    }

    private MediaHubSettings Load()
    {
        try
        {
            if (File.Exists(SettingsPath))
            {
                string json = File.ReadAllText(SettingsPath);
                var loaded = JsonSerializer.Deserialize<MediaHubSettings>(json, JsonOptions);
                if (loaded is not null) return loaded;
            }
        }
        catch
        {
            // A corrupt or unreadable settings file must not prevent the app from starting; the
            // defaults below are the documented fall-back and the file is rewritten on next save.
        }
        return new MediaHubSettings();
    }

    private void SaveLocked()
    {
        try
        {
            Directory.CreateDirectory(SettingsDirectory);
            string tmp = SettingsPath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_current, JsonOptions));
            File.Move(tmp, SettingsPath, overwrite: true);
        }
        catch
        {
            // Persisting preferences is best-effort; failing it must never break playback.
        }
    }
}

/// <summary>
/// A configured media server as persisted to disk.
/// </summary>
/// <remarks>
/// Deliberately excludes the password and the session token: credentials belong in encrypted storage,
/// and persisting them in a plain settings file would silently downgrade the security model that the
/// Android client established. Wiring the encrypted vault is a later phase; until then a saved server
/// requires re-authentication, which is the safe direction to fail.
/// </remarks>
public sealed class PersistedServer
{
    public string ServerId { get; set; } = "";
    public string Type { get; set; } = "Emby";
    public string DisplayName { get; set; } = "";
    public string Url { get; set; } = "";
    public string? UserName { get; set; }
    public string? RemoteServerId { get; set; }
}

public sealed class MediaHubSettings
{
    // ---- playback ----
    public double DefaultVolume { get; set; } = 100;
    public double DefaultSpeed { get; set; } = 1.0;
    public bool AutoPlayNext { get; set; } = true;
    public bool ResumePlayback { get; set; } = true;
    public int SeekStepSeconds { get; set; } = 10;
    public string? PreferredAudioLanguage { get; set; }
    public string? PreferredSubtitleLanguage { get; set; }

    // ---- subtitles ----
    public bool SubtitlesEnabled { get; set; } = true;
    public double SubtitleFontSize { get; set; } = 55;
    public string SubtitleFont { get; set; } = "Microsoft YaHei UI";
    public string SubtitleColor { get; set; } = "#FFFFFF";
    public double SubtitleBorderSize { get; set; } = 2.5;
    public double SubtitlePosition { get; set; } = 100;
    public double SubtitleDelaySeconds { get; set; }
    public string SubtitleAssOverride { get; set; } = "no";

    // ---- appearance ----
    public bool FollowSystemTheme { get; set; } = true;
    public string Theme { get; set; } = "Dark";
    public bool ShowDetailedMediaInfo { get; set; }
    public bool AutoHideControls { get; set; } = true;

    /// <summary>
    /// Serialised window position and size, restored on the next start when it still lands on a
    /// display that exists.
    /// </summary>
    public string? WindowPlacement { get; set; }

    /// <summary>Set once the user has been offered file association, so the prompt is not repeated.</summary>
    public bool FileAssociationOffered { get; set; }

    // ---- configured media servers ----
    public List<PersistedServer> Servers { get; set; } = new();

    // ---- local library ----
    public List<string> LocalFolders { get; set; } = new();
    public bool RecursiveScan { get; set; } = true;

    // ---- network (populated in the provider phase) ----
    public string ProxyMode { get; set; } = "System";
    public string? ProxyUrl { get; set; }
    public bool StrictRedirectCredentialIsolation { get; set; } = true;

    // ---- advanced engine options (name -> value passed verbatim to the engine) ----
    public Dictionary<string, string> EngineOptions { get; set; } = new(StringComparer.OrdinalIgnoreCase)
    {
        ["hwdec"] = "auto-safe",
        ["vo"] = "gpu-next",
        ["gpu-context"] = "d3d11",
    };
}

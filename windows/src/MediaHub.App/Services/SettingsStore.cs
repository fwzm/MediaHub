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

    /// <summary>
    /// SHA-256 of the server's leaf certificate, when the user explicitly trusted a certificate the
    /// platform does not. Null for every ordinary server, and for settings files written before this
    /// field existed.
    /// </summary>
    public string? TrustedCertificateSha256 { get; set; }

    /// <summary>
    /// True when the user chose "do not validate this server's certificate". Stored per server, and
    /// mutually exclusive with <see cref="TrustedCertificateSha256"/>.
    /// </summary>
    public bool AllowUntrustedCertificate { get; set; }
}

public sealed class MediaHubSettings
{
    // ---- playback ----
    public double DefaultVolume { get; set; } = 100;
    public double DefaultSpeed { get; set; } = 1.0;
    public bool AutoPlayNext { get; set; } = true;
    public bool ResumePlayback { get; set; } = true;
    public int SeekStepSeconds { get; set; } = 10;

    /// <summary>
    /// Picture brightness in the player, 0-100 with 50 neutral. Applied to the engine's own picture
    /// equalizer, so the desktop is never touched, and remembered so a restart does not flash the
    /// default brightness.
    /// </summary>
    public double PlayerBrightness { get; set; } = 50;

    /// <summary>
    /// How per-frame HDR metadata (HDR10+ scene data / Dolby Vision RPU) is used: "auto" follows the
    /// source (the validated default), "metadata" always trusts the stream's own metadata, "compute"
    /// measures the peak from the picture. Read at engine start, so a change applies on next launch.
    /// </summary>
    public string HdrDynamicMetadata { get; set; } = "auto";

    public string? PreferredAudioLanguage { get; set; }
    public string? PreferredSubtitleLanguage { get; set; }

    /// <summary>
    /// Version ordering when an item offers several sources and the user did not pick one:
    /// "auto" (server order, direct-first), "highest" or "lowest". It only reorders playable
    /// versions, so it can never push playback onto a transcode.
    /// </summary>
    public string QualityPreference { get; set; } = "auto";

    /// <summary>
    /// Remembers the video track the user picked, keyed by series (or item when there is no series),
    /// so the next episode opens with the same track instead of the container default.
    /// </summary>
    public Dictionary<string, string> VideoTrackMemory { get; set; } = new(StringComparer.Ordinal);

    // ---- disk cache ----
    /// <summary>
    /// Writes the engine's packet cache to disk. Off is the engine default; on keeps the readahead
    /// across seeks and short network stalls, at the cost of disk writes.
    /// </summary>
    public bool DiskCacheEnabled { get; set; } = true;

    /// <summary>Byte budget for that cache, in megabytes. Enforced after playback and at startup.</summary>
    public int DiskCacheCapacityMB { get; set; } = 2048;

    /// <summary>Empties the cache directory when playback stops, so it never accumulates silently.</summary>
    public bool ClearDiskCacheOnPlaybackEnd { get; set; } = true;

    // ---- poster badges ----
    public bool ShowMediaBadges { get; set; } = true;

    /// <summary>Badge kinds to draw, in display order: resolution, hdr, video, audio, container.</summary>
    public List<string> MediaBadgeItems { get; set; } = new() { "resolution", "hdr", "audio" };

    // ---- subtitles ----
    public bool SubtitlesEnabled { get; set; } = true;
    public double SubtitleFontSize { get; set; } = 55;
    public string SubtitleFont { get; set; } = "Microsoft YaHei UI";
    public string SubtitleColor { get; set; } = "#FFFFFF";
    public double SubtitleBorderSize { get; set; } = 2.5;
    public double SubtitlePosition { get; set; } = 100;
    public bool SubtitleBold { get; set; }
    public double SubtitleDelaySeconds { get; set; }

    /// <summary>
    /// Defaults to "force" so the styling above also applies to embedded ASS/SSA tracks. With "no"
    /// the track's own styling wins, which makes every style control silently ineffective for the
    /// most common subtitle format in anime and film releases. A profile saved before this default
    /// keeps whatever it stored; the settings page exposes the choice either way.
    /// </summary>
    public string SubtitleAssOverride { get; set; } = "force";

    // ---- audio ----
    /// <summary>
    /// Engine audio-device name to prefer; null follows the system default. Stored rather than
    /// applied once so a device that is temporarily absent is picked up again when it returns.
    /// </summary>
    public string? AudioDevice { get; set; }

    // ---- appearance ----
    public bool FollowSystemTheme { get; set; } = true;
    public string Theme { get; set; } = "Dark";
    public bool ShowDetailedMediaInfo { get; set; }
    public bool AutoHideControls { get; set; } = true;

    /// <summary>
    /// Shows a screenshot button in the player. Off by default: it exists for capturing what the
    /// player actually renders (subtitles included), which is a diagnostic tool rather than part of
    /// ordinary playback, so no screenshot command is reachable until the user opts in.
    /// </summary>
    public bool AllowPlayerScreenshots { get; set; }

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

    // ---- updates ----
    /// <summary>
    /// Directory that holds update.json and its package: a local path or a plain http(s) directory.
    /// Empty means the build has no update source and the settings page says so instead of guessing.
    /// </summary>
    public string? UpdateSource { get; set; }

    // ---- OneDrive (public-client registration; neither value is a secret) ----
    /// <summary>
    /// Entra application (client) id for the OneDrive sign-in. Empty by default: a repository must not
    /// carry anybody's tenant identity, and an unconfigured build says so instead of failing later.
    /// </summary>
    public string? OneDriveClientId { get; set; }

    /// <summary>Loopback callback registered for that client. Empty means http://localhost:53682/.</summary>
    public string? OneDriveRedirectUri { get; set; }

    // ---- advanced engine options (name -> value passed verbatim to the engine) ----
    public Dictionary<string, string> EngineOptions { get; set; } = new(StringComparer.OrdinalIgnoreCase)
    {
        ["hwdec"] = "auto-safe",
        ["vo"] = "gpu-next",
        ["gpu-context"] = "d3d11",
    };
}

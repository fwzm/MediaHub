using System.Diagnostics;
using MediaHub.Net;
using MediaHub.Update;

namespace MediaHub.App.Services;

/// <summary>
/// In-app half of the update: read the configured source, download and verify the package, then hand
/// the actual file swap to <c>update.ps1</c> and let the window close through its normal shutdown.
/// </summary>
/// <remarks>
/// The split is deliberate. A running process cannot replace its own image or the engine DLL it has
/// loaded, so the only safe point to touch the install directory is after this process has exited.
/// Nothing here renames or deletes a program file; the agent does that, and it is the agent that
/// restores the previous tree when a step after the swap fails.
/// </remarks>
public sealed class UpdateService : IDisposable
{
    private const string AgentResourceName = "MediaHub.App.Updates.update.ps1";
    private const string AgentFileName = "update.ps1";

    private readonly SettingsStore _settings;
    private readonly LogService _log;
    private readonly UpdateClient _client;

    public UpdateService(SettingsStore settings, LogService log)
    {
        _settings = settings;
        _log = log;
        _client = new UpdateClient(userAgent: "MediaHub/" + RawVersion + " (Windows)");
    }

    /// <summary>Version of the running executable, without the SDK's <c>+commit</c> suffix.</summary>
    public static string RawVersion
    {
        get
        {
            try
            {
                string? path = Environment.ProcessPath;
                if (path is null) return "0.0.0";
                string? product = FileVersionInfo.GetVersionInfo(path).ProductVersion;
                if (!string.IsNullOrWhiteSpace(product)) return product.Split('+')[0].Trim();
            }
            catch { }
            return "0.0.0";
        }
    }

    /// <summary>Directory the running executable lives in; this is the tree the agent replaces.</summary>
    public static string InstallDirectory => Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory);

    /// <summary>
    /// True when the install directory carries the uninstaller the per-user installer writes. A tree
    /// without it is a development/portable copy: replacing it would destroy a build directory that
    /// has nothing to do with an installed product, so the update button refuses.
    /// </summary>
    public bool CanSelfUpdate => File.Exists(Path.Combine(InstallDirectory, "uninstall.ps1"));

    public static string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub", "update");

    /// <summary>Reads and validates update.json, and says whether it is newer than this build.</summary>
    public async Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        var source = ParseConfiguredSource();
        var manifest = await _client.GetManifestAsync(source, cancellationToken).ConfigureAwait(false);
        bool available = UpdateVersions.IsNewer(manifest.Version, RawVersion);
        _log.Info("update", $"manifest version {manifest.Version} (installed {RawVersion}), available={available}");
        return new UpdateCheckResult(available, manifest, source);
    }

    /// <summary>Downloads the package next to the other update state and verifies its hash.</summary>
    public async Task<string> DownloadAsync(
        UpdateCheckResult check, IProgress<double>? progress = null, CancellationToken cancellationToken = default)
    {
        string directory = Path.Combine(DataDirectory, "downloads", UpdateVersions.Normalize(check.Manifest.Version));
        _log.Info("update", $"downloading {check.Manifest.Package} ({check.Manifest.Size} bytes)");
        return await _client.DownloadPackageAsync(check.Source, check.Manifest, directory, progress, cancellationToken)
            .ConfigureAwait(false);
    }

    /// <summary>
    /// Extracts the embedded agent to <c>%LOCALAPPDATA%\MediaHub\update\agent</c> and starts it hidden.
    /// The caller closes the window afterwards; the agent waits for this PID before touching files.
    /// </summary>
    public Process? StartAgent(UpdateManifest manifest, string packagePath, bool restart)
    {
        if (!CanSelfUpdate)
        {
            throw new InvalidOperationException(
                "当前运行目录不是通过安装器安装的版本（缺少 uninstall.ps1），一键更新不可用。");
        }

        string agentDirectory = Path.Combine(DataDirectory, "agent");
        Directory.CreateDirectory(agentDirectory);
        string agentPath = Path.Combine(agentDirectory, AgentFileName);

        using (Stream? resource = typeof(UpdateService).Assembly.GetManifestResourceStream(AgentResourceName))
        {
            if (resource is null) throw new InvalidOperationException("更新代理脚本未嵌入当前构建。");
            using var file = new FileStream(agentPath, FileMode.Create, FileAccess.Write, FileShare.Read);
            resource.CopyTo(file);
        }

        var start = new ProcessStartInfo
        {
            FileName = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.System),
                "WindowsPowerShell", "v1.0", "powershell.exe"),
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            WorkingDirectory = DataDirectory,
            Arguments = string.Join(" ", new[]
            {
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-File", Quote(agentPath),
                "-InstallDir", Quote(InstallDirectory),
                "-PackageZip", Quote(packagePath),
                "-ExpectedSha256", manifest.Sha256,
                "-NewVersion", manifest.Version,
                "-ParentPid", Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture),
                "-WaitSeconds", "30",
                "-StateDir", Quote(DataDirectory),
                restart ? "-Restart" : "",
            }.Where(part => part.Length != 0)),
        };

        _log.Info("update", $"starting agent for {manifest.Version}; install={InstallDirectory}");
        return Process.Start(start);
    }

    private UpdateSource ParseConfiguredSource()
    {
        string? raw = _settings.Current.UpdateSource;
        if (!UpdateSource.TryParse(raw, out var source, out string error))
        {
            throw new InvalidOperationException("更新源未配置或格式不正确：" + error);
        }
        return source;
    }

    private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";

    public void Dispose() => _client.Dispose();
}

/// <summary>Result of one manifest check, kept so "立即更新" uses the exact source that was checked.</summary>
public sealed record UpdateCheckResult(bool Available, UpdateManifest Manifest, UpdateSource Source);

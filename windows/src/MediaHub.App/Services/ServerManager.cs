using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Net.Security;
using MediaHub.Providers;
using MediaHub.Providers.WebDav;
using MediaHub.Providers.Local;
using MediaHub.Providers.MediaBrowser;

namespace MediaHub.App.Services;

/// <summary>Connection state of a configured source, as shown in the sources list.</summary>
public enum ServerStatus
{
    Unknown = 0,
    Checking,
    Online,
    AuthRequired,
    AuthFailed,
    Unreachable,
}

/// <summary>One configured media source.</summary>
public sealed class ServerEntry : INotifyPropertyChanged
{
    public required string ServerId { get; init; }
    public required ServerType Type { get; init; }
    public required string Url { get; init; }
    public string DisplayName { get; set; } = "";
    public string? UserName { get; set; }
    public ProviderHandle? Handle { get; set; }

    private ServerStatus _status = ServerStatus.Unknown;
    public ServerStatus Status
    {
        get => _status;
        set { if (_status != value) { _status = value; Raise(); Raise(nameof(StatusText)); Raise(nameof(StatusBrush)); } }
    }

    private string? _detail;
    public string? StatusDetail
    {
        get => _detail;
        set { if (_detail != value) { _detail = value; Raise(); Raise(nameof(StatusText)); } }
    }

    public string TypeName => Type.ToString();

    public string StatusText => Status switch
    {
        ServerStatus.Online => string.IsNullOrEmpty(UserName) ? "已连接" : "已连接：" + UserName,
        ServerStatus.Checking => "正在连接…",
        ServerStatus.AuthRequired => "需要登录",
        ServerStatus.AuthFailed => "认证失败" + (string.IsNullOrEmpty(StatusDetail) ? "" : "：" + StatusDetail),
        ServerStatus.Unreachable => "服务器不可达" + (string.IsNullOrEmpty(StatusDetail) ? "" : "：" + StatusDetail),
        _ => "未连接",
    };

    /// <summary>
    /// Colour for the status dot.
    /// </summary>
    /// <remarks>
    /// A concrete brush rather than a resource key: binding a key would need a converter on every
    /// template, and the palette here mirrors the Fluent status colours the rest of the app uses.
    /// </remarks>
    public Microsoft.UI.Xaml.Media.SolidColorBrush StatusBrush => new(Status switch
    {
        ServerStatus.Online => Windows.UI.Color.FromArgb(255, 0x6C, 0xC0, 0x4A),
        ServerStatus.Checking => Windows.UI.Color.FromArgb(255, 0x00, 0x9C, 0xE8),
        ServerStatus.AuthFailed or ServerStatus.AuthRequired => Windows.UI.Color.FromArgb(255, 0xF7, 0x9B, 0x00),
        ServerStatus.Unreachable => Windows.UI.Color.FromArgb(255, 0xE8, 0x11, 0x23),
        _ => Windows.UI.Color.FromArgb(255, 0x9E, 0x9E, 0x9E),
    });

    public string CapabilitySummary => Handle?.CapabilitySummary ?? "尚无可用能力";

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>
/// Owns the configured media sources: credentials, handles, connection state and persistence.
/// </summary>
/// <remarks>
/// <para>
/// This is the only component that touches both the credential vault and the provider registry, so
/// the rule "a deleted source leaves no credential behind" and the rule "a token is never written to
/// plain settings" are enforced in one place instead of at each call site.
/// </para>
/// <para>
/// Non-secret server metadata lives in settings.json; the password and session token live in the
/// DPAPI vault, keyed by server id. The two are written and removed together.
/// </para>
/// </remarks>
public sealed class ServerManager : IDisposable
{
    private readonly SettingsStore _settings;
    private readonly LogService _log;
    private readonly ICredentialStore _vault;
    private readonly ProviderRegistry _providers = new();
    private readonly ObservableCollection<ServerEntry> _servers = new();
    private readonly object _gate = new();

    public ServerManager(SettingsStore settings, LogService log, ICredentialStore vault)
    {
        _settings = settings;
        _log = log;
        _vault = vault;

        _providers.Register(new LocalMediaProviderFactory());
        _providers.Register(new EmbyProviderFactory());
        _providers.Register(new JellyfinProviderFactory());
        _providers.Register(new WebDavProviderFactory());
    }

    public ReadOnlyObservableCollection<ServerEntry> Servers => new(_servers);

    /// <summary>Provider kinds this build actually contains, for the "add source" dialog.</summary>
    public IReadOnlyList<ProviderDescriptor> AvailableProviders =>
        _providers.Descriptors.Where(d => d.Type != ServerType.Local).ToList();

    public event EventHandler? Changed;

    public ProviderHandle? FindHandle(string serverId)
    {
        lock (_gate) return _servers.FirstOrDefault(s => s.ServerId == serverId)?.Handle;
    }

    public ServerEntry? Find(string serverId)
    {
        lock (_gate) return _servers.FirstOrDefault(s => s.ServerId == serverId);
    }

    /// <summary>Handles for sources that are usable right now.</summary>
    public IReadOnlyList<ProviderHandle> OnlineHandles
    {
        get
        {
            lock (_gate)
            {
                return _servers.Where(s => s.Status == ServerStatus.Online && s.Handle is not null)
                    .Select(s => s.Handle!).ToList();
            }
        }
    }

    // ---------------------------------------------------------------- startup

    /// <summary>
    /// Rebuilds every configured source from persisted metadata plus the vault, then validates sessions.
    /// </summary>
    /// <remarks>
    /// A source whose credential cannot be read is surfaced as needing a login rather than being
    /// dropped: silently forgetting a server because its vault entry failed to decrypt would look like
    /// data loss, and the user's recovery is the same in both cases.
    /// </remarks>
    public async Task InitializeAsync(CancellationToken ct = default)
    {
        RefreshLocal();
        foreach (PersistedServer persisted in _settings.Current.Servers.ToList())
        {
            ct.ThrowIfCancellationRequested();
            if (!Enum.TryParse(persisted.Type, out ServerType type)) continue;
            if (!Uri.TryCreate(persisted.Url, UriKind.Absolute, out Uri? url)) continue;

            var entry = new ServerEntry
            {
                ServerId = persisted.ServerId,
                Type = type,
                Url = persisted.Url,
                DisplayName = string.IsNullOrWhiteSpace(persisted.DisplayName) ? url.Host : persisted.DisplayName,
                UserName = persisted.UserName,
                Status = ServerStatus.Checking,
            };
            AddEntry(entry);

            await RestoreAsync(entry, ct).ConfigureAwait(true);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private async Task RestoreAsync(ServerEntry entry, CancellationToken ct)
    {
        VaultLoadResult loaded = await _vault.LoadAsync(entry.ServerId, ct).ConfigureAwait(true);

        if (loaded.Status is VaultStatus.Corrupt or VaultStatus.ForeignUser or VaultStatus.Undecryptable)
        {
            // The server definition survives; only the secret is gone. Say so rather than pretending
            // the source is healthy.
            entry.Status = ServerStatus.AuthRequired;
            entry.StatusDetail = loaded.Status == VaultStatus.ForeignUser
                ? "凭据无法解密（可能来自其他 Windows 账户或设备）"
                : "凭据已损坏，需要重新登录";
            _log.Warn("sources", entry.DisplayName + "：" + entry.StatusDetail);
            return;
        }

        ServerCredential? credential = loaded.Credential;
        if (credential is null || string.IsNullOrEmpty(credential.Token))
        {
            entry.Status = ServerStatus.AuthRequired;
            return;
        }

        ProviderHandle? handle = BuildHandle(entry, credential);
        if (handle?.Auth is null)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = "该数据源不支持认证";
            return;
        }

        entry.Handle = handle;
        try
        {
            AuthSessionState state = await handle.Auth.RestoreSessionAsync(ct).ConfigureAwait(true);
            ApplySessionState(entry, state);
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
        }
    }

    private void ApplySessionState(ServerEntry entry, AuthSessionState state)
    {
        switch (state)
        {
            case AuthSessionState.Authenticated:
                entry.Status = ServerStatus.Online;
                entry.UserName = entry.Handle?.Session.UserName ?? entry.UserName;
                entry.StatusDetail = null;
                break;
            case AuthSessionState.Expired:
                entry.Status = ServerStatus.AuthRequired;
                entry.StatusDetail = "登录状态已过期";
                break;
            case AuthSessionState.Unreachable:
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = "连接超时或服务器不可达";
                break;
            default:
                entry.Status = ServerStatus.AuthRequired;
                break;
        }
    }

    // ---------------------------------------------------------------- local source

    public void RefreshLocal()
    {
        lock (_gate)
        {
            ServerEntry? existing = _servers.FirstOrDefault(s => s.ServerId == "local");
            if (existing is not null) _servers.Remove(existing);
        }
        var folders = _settings.Current.LocalFolders;
        var endpoints = folders.Select((f, i) => new ServerEndpoint(f, true, "文件夹 " + (i + 1))).ToList();
        var server = new MediaServer("local", ServerType.Local, "本地存储", endpoints);

        var entry = new ServerEntry
        {
            ServerId = "local",
            Type = ServerType.Local,
            Url = folders.Count > 0 ? folders[0] : "",
            DisplayName = "本地存储",
            Status = folders.Count > 0 ? ServerStatus.Online : ServerStatus.Unknown,
            StatusDetail = folders.Count > 0 ? null : "尚未添加文件夹",
        };
        entry.Handle = _providers.Create(server, NetworkPolicy.FollowSystem);
        lock (_gate) _servers.Insert(0, entry);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    // ---------------------------------------------------------------- add / edit / delete

    /// <summary>Validates an address without contacting it. Used for live feedback in the dialog.</summary>
    public static bool TryNormalizeUrl(string input, ServerType type, out NormalizedServerUrl normalized, out string? error)
        => ServerUrlNormalizer.TryNormalize(input, type, out normalized, out error);

    /// <summary>Tests reachability and protocol identity, without authenticating.</summary>
    public async Task<(bool Ok, string Message)> TestConnectionAsync(string input, ServerType type, NetworkPolicy policy, CancellationToken ct = default)
    {
        if (!ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl normalized, out string? error))
            return (false, error ?? "地址无效");

        if (type == ServerType.WebDav)
        {
            WebDavServerProbe.ProbeResult dav =
                await WebDavServerProbe.ProbeAsync(normalized.BaseUri, policy, null, ct).ConfigureAwait(true);
            return dav.Success
                ? (true, "连接正常：" + (dav.ServerHeader ?? normalized.DisplayText)
                         + (dav.DavClasses.Count > 0 ? "（DAV: " + string.Join(", ", dav.DavClasses) + "）" : ""))
                : (false, dav.Message);
        }

        MediaServerProbe.ProbeResult probe =
            await MediaServerProbe.ProbeMediaBrowserAsync(normalized.BaseUri, type, policy, ct).ConfigureAwait(true);
        return probe.Success
            ? (true, "连接正常：" + (probe.ServerName ?? normalized.DisplayText)
                     + (string.IsNullOrEmpty(probe.Version) ? "" : "（版本 " + probe.Version + "）"))
            : (false, probe.Message);
    }

    /// <summary>Adds a source: probe, authenticate, then persist the definition and the secret.</summary>
    public async Task<(bool Ok, string Message)> AddAsync(
        string input, ServerType type, string userName, string password, NetworkPolicy policy, CancellationToken ct = default)
    {
        if (!ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl normalized, out string? error))
            return (false, error ?? "地址无效");

        // The probe must match the protocol the user picked. Probing a WebDAV endpoint with the
        // MediaBrowser probe made "add a WebDAV source" impossible: the request went out as a
        // Jellyfin /System/Info/Public call, the server answered 401, and the dialog reported
        // "session expired" for a source that had never been contacted correctly at all.
        string? probeServerName;
        string? probeRemoteId;
        if (type == ServerType.WebDav)
        {
            // Probed WITH the credentials: a WebDAV server that requires authentication answers 401 to
            // an anonymous OPTIONS, and a probe that always failed would make secure servers unaddable.
            var davCredentials = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["username"] = userName,
                ["password"] = password,
            };
            WebDavServerProbe.ProbeResult dav = await WebDavServerProbe
                .ProbeAsync(normalized.BaseUri, policy, davCredentials, ct).ConfigureAwait(true);
            if (!dav.Success) return (false, dav.Message);
            probeServerName = dav.ServerHeader;
            // WebDAV has no server identity to report, so the endpoint itself is the identity. That is
            // weaker than an Emby/Jellyfin id, which is exactly why the credential is also bound to the
            // normalised URL rather than to a name the server chose.
            probeRemoteId = normalized.BaseUri.Host + ":" + normalized.BaseUri.Port;
        }
        else
        {
            MediaServerProbe.ProbeResult probe = await MediaServerProbe
                .ProbeMediaBrowserAsync(normalized.BaseUri, type, policy, ct).ConfigureAwait(true);
            if (!probe.Success) return (false, probe.Message);
            probeServerName = probe.ServerName;
            probeRemoteId = probe.RemoteServerId;
        }

        // The remote server id binds the credential to one physical server, so a token can never be
        // replayed against a different server that happens to reuse the same host name.
        string serverId = type.ToString().ToLowerInvariant() + ":" + (probeRemoteId ?? normalized.BaseUri.Host);
        if (Find(serverId) is not null) return (false, "该服务器已经添加过了");

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = type,
            Url = normalized.BaseUri.ToString(),
            DisplayName = probeServerName ?? normalized.BaseUri.Host,
            UserName = userName,
            Status = ServerStatus.Checking,
        };

        var handle = BuildHandle(entry, null, policy);
        if (handle?.Auth is null) return (false, "该数据源不支持认证");
        entry.Handle = handle;
        AddEntry(entry);

        try
        {
            AuthResult result = await handle.Auth
                .AuthenticateAsync(new Credentials(userName, password), ct).ConfigureAwait(true);
            entry.UserName = result.UserName;
            entry.Status = ServerStatus.Online;
            entry.StatusDetail = null;
        }
        catch (ProviderException ex)
        {
            entry.Status = ex.Code == ProviderErrorCode.AuthFailed ? ServerStatus.AuthFailed : ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            RemoveEntry(serverId, disposeHandle: true);
            return (false, ex.Message);
        }

        await PersistAsync(entry, probeRemoteId, password, ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return (true, "已添加：" + entry.DisplayName);
    }

    /// <summary>Re-authenticates an existing source, typically after its session expired.</summary>
    public async Task<(bool Ok, string Message)> ReconnectAsync(string serverId, string password, CancellationToken ct = default)
    {
        ServerEntry? entry = Find(serverId);
        if (entry?.Handle?.Auth is null) return (false, "该媒体源不存在");

        string userName = entry.UserName ?? (await _vault.LoadAsync(serverId, ct).ConfigureAwait(true)).Credential?.UserName ?? "";
        if (string.IsNullOrEmpty(userName)) return (false, "缺少用户名，请重新添加该媒体源");

        entry.Status = ServerStatus.Checking;
        try
        {
            AuthResult result = await entry.Handle.Auth
                .AuthenticateAsync(new Credentials(userName, password), ct).ConfigureAwait(true);
            entry.UserName = result.UserName;
            entry.Status = ServerStatus.Online;
            entry.StatusDetail = null;
        }
        catch (ProviderException ex)
        {
            entry.Status = ex.Code == ProviderErrorCode.AuthFailed ? ServerStatus.AuthFailed : ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            return (false, ex.Message);
        }

        VaultLoadResult previous = await _vault.LoadAsync(serverId, ct).ConfigureAwait(true);
        await _vault.SaveAsync(new ServerCredential
        {
            ServerId = serverId,
            UserName = userName,
            Password = password,
            Token = entry.Handle.Session.Token,
            UserId = entry.Handle.Session.UserId,
            RemoteServerId = previous.Credential?.RemoteServerId,
            SavedAtUtc = DateTimeOffset.UtcNow,
        }, ct).ConfigureAwait(true);

        Changed?.Invoke(this, EventArgs.Empty);
        return (true, "已重新连接：" + entry.DisplayName);
    }

    /// <summary>Removes a source and everything stored for it.</summary>
    public async Task DeleteAsync(string serverId, CancellationToken ct = default)
    {
        if (serverId == "local")
        {
            _settings.Update(s => s.LocalFolders.Clear());
            RefreshLocal();
            return;
        }

        RemoveEntry(serverId, disposeHandle: true);
        // Vault first: leaving a secret behind for a source the user deleted would make "delete"
        // a lie, and the orphan is unreachable from the UI.
        await _vault.DeleteAsync(serverId, ct).ConfigureAwait(true);
        _settings.Update(s => s.Servers.RemoveAll(x => x.ServerId == serverId));
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Re-runs the connection check for a source, including its stored session.</summary>
    public async Task<(bool Ok, string Message)> RetestAsync(string serverId, CancellationToken ct = default)
    {
        ServerEntry? entry = Find(serverId);
        if (entry is null) return (false, "该媒体源不存在");
        if (entry.Type == ServerType.Local) return (true, "本地媒体源无需连接检查");

        var policy = NetworkPolicy.FollowSystem;
        var (ok, message) = await TestConnectionAsync(entry.Url, entry.Type, policy, ct).ConfigureAwait(true);
        if (!ok)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = message;
            return (false, message);
        }

        if (entry.Handle?.Auth is not null)
        {
            AuthSessionState state;
            try
            {
                state = await entry.Handle.Auth.RestoreSessionAsync(ct).ConfigureAwait(true);
            }
            catch (ProviderException ex)
            {
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = ex.Message;
                return (false, ex.Message);
            }
            ApplySessionState(entry, state);
            return (entry.Status == ServerStatus.Online, entry.StatusText);
        }

        entry.Status = ServerStatus.AuthRequired;
        return (false, "需要登录");
    }

    // ---------------------------------------------------------------- helpers

    private ProviderHandle? BuildHandle(ServerEntry entry, ServerCredential? credential, NetworkPolicy? policy = null)
    {
        var server = new MediaServer(entry.ServerId, entry.Type, entry.DisplayName,
            new[] { new ServerEndpoint(entry.Url) });

        var credentials = credential is null
            ? null
            : new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["token"] = credential.Token ?? "",
                ["userId"] = credential.UserId ?? "",
                // Basic-authenticated sources need the password itself; a token-only dictionary makes
                // adding a WebDAV source appear to succeed and then fail with 401 on every request.
                ["password"] = credential.Password ?? "",
                ["username"] = credential.UserName ?? "",
                ["userName"] = credential.UserName ?? "",
            };

        ProviderHandle? handle = _providers.Create(server, policy ?? NetworkPolicy.FollowSystem, credentials);
        if (handle is not null)
        {
            handle.OnSessionExpired = () =>
            {
                entry.Status = ServerStatus.AuthRequired;
                entry.StatusDetail = "登录状态已过期";
            };
        }
        return handle;
    }

    private void AddEntry(ServerEntry entry)
    {
        lock (_gate)
        {
            ServerEntry? duplicate = _servers.FirstOrDefault(s => s.ServerId == entry.ServerId);
            if (duplicate is not null)
            {
                // The displaced handle owns an HTTP client factory; dropping it without disposing
                // would leak the connection pool for a source the user has just replaced.
                _servers.Remove(duplicate);
                duplicate.Handle?.Dispose();
            }
            _servers.Add(entry);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void RemoveEntry(string serverId, bool disposeHandle)
    {
        ServerEntry? existing;
        lock (_gate)
        {
            existing = _servers.FirstOrDefault(s => s.ServerId == serverId);
            if (existing is not null) _servers.Remove(existing);
        }
        if (disposeHandle) existing?.Handle?.Dispose();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private async Task PersistAsync(ServerEntry entry, string? remoteServerId, string password, CancellationToken ct)
    {
        // Plain settings carry only what is not a secret.
        _settings.Update(s =>
        {
            s.Servers.RemoveAll(x => x.ServerId == entry.ServerId);
            s.Servers.Add(new PersistedServer
            {
                ServerId = entry.ServerId,
                Type = entry.Type.ToString(),
                DisplayName = entry.DisplayName,
                Url = entry.Url,
                UserName = entry.UserName,
                RemoteServerId = remoteServerId,
            });
        });

        await _vault.SaveAsync(new ServerCredential
        {
            ServerId = entry.ServerId,
            UserName = entry.UserName,
            Password = password,
            Token = entry.Handle?.Session.Token,
            UserId = entry.Handle?.Session.UserId,
            RemoteServerId = remoteServerId,
            SavedAtUtc = DateTimeOffset.UtcNow,
        }, ct).ConfigureAwait(true);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            foreach (ServerEntry entry in _servers) entry.Handle?.Dispose();
            _servers.Clear();
        }
        if (_vault is IDisposable disposable) disposable.Dispose();
    }
}

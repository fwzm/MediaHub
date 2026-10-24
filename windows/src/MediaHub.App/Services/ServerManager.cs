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
using MediaHub.Sync;

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

    /// <summary>Cross-device identity of this source, or null when it cannot travel to another device.</summary>
    /// <remarks>
    /// Persisted next to the source (ADR-S001 D5): the identity has to survive a restart, because a
    /// re-derived id would look like a second source to the other device. A local folder never gets one.
    /// </remarks>
    public string? SyncSourceId { get; set; }

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
    private readonly ILocalSourceStore? _syncSources;

    /// <param name="syncSources">
    /// Where cross-device source identities are mirrored. Optional so that a headless host or a test
    /// that only needs connections can leave cross-device sync out entirely; null means "no sync".
    /// </param>
    public ServerManager(SettingsStore settings, LogService log, ICredentialStore vault, ILocalSourceStore? syncSources = null)
    {
        _settings = settings;
        _log = log;
        _vault = vault;
        _syncSources = syncSources;

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
                SyncSourceId = persisted.SyncSourceId,
                Status = ServerStatus.Checking,
            };
            AddEntry(entry);

            await RestoreAsync(entry, ct).ConfigureAwait(true);
        }

        // First load of a build that predates cross-device sync has no stored identity yet; assigning it
        // here means every later export already carries the ids the peer will remember.
        EnsureSyncSourceIds();
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

        // A session token is not the only usable credential. WebDAV authenticates with Basic on every
        // request and has no token at all, so requiring one here meant a WebDAV source restored as
        // "login required" and stayed unusable until it was deleted and added again - a restart was
        // enough to lose the source even though the password was sitting in the vault. A secret is
        // present if there is a token OR a password.
        bool hasSecret = credential is not null
                         && (!string.IsNullOrEmpty(credential.Token) || !string.IsNullOrEmpty(credential.Password));
        if (credential is null || !hasSecret)
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
        // The new source gets its cross-device identity while its address is still in hand.
        EnsureSyncSourceIds();
        Changed?.Invoke(this, EventArgs.Empty);
        return (true, "已添加：" + entry.DisplayName);
    }

    /// <summary>Re-authenticates an existing source, typically after its session expired.</summary>
    public async Task<(bool Ok, string Message)> ReconnectAsync(string serverId, string password, CancellationToken ct = default)
    {
        ServerEntry? entry = Find(serverId);
        if (entry is null) return (false, "该媒体源不存在");

        VaultLoadResult loaded = await _vault.LoadAsync(serverId, ct).ConfigureAwait(true);

        // Rebuild the handle when restore did not manage to create one. Reporting "该媒体源不存在" for a
        // source that is plainly listed in the UI sends the user to delete and re-add it; the entry,
        // the URL and the credential are all still here, so reconnecting is what was actually asked
        // for. This is also the path that makes a WebDAV source recoverable after a restart.
        if (entry.Handle?.Auth is null)
        {
            if (loaded.Credential is null) return (false, "凭据已丢失，请重新添加该媒体源");
            ProviderHandle? rebuilt = BuildHandle(entry, loaded.Credential);
            if (rebuilt?.Auth is null) return (false, "该媒体源不支持认证");
            entry.Handle = rebuilt;
        }

        string userName = entry.UserName
                          ?? loaded.Credential?.UserName
                          ?? "";
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
        // Mirrored rows follow the live list: a deleted source must stop being advertised to peers.
        EnsureSyncSourceIds();
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

    // ------------------------------------------------------- cross-device apply (W6-8)

    /// <summary>What one approved cross-device change did to the local source list.</summary>
    /// <remarks>
    /// Carries no URL and no credential: an apply message is shown in the UI and written to the log, and a
    /// record that arrived from another device may contain an address the user does not want echoed.
    /// </remarks>
    public sealed record RemoteSourceOutcome(string SyncSourceId, bool Applied, string Message);

    /// <summary>
    /// Writes one remote source record into the local list: creates the source, or replaces the source that
    /// already carries this cross-device identity.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Offline by design. The record describes a source on another device; this machine has no credential
    /// for it, so the source is added in <see cref="ServerStatus.AuthRequired"/> and the user signs in from
    /// the sources page. Pretending it is connected would be the one thing this method cannot know.
    /// </para>
    /// <para>
    /// Called only after the approval gate has accepted the entry, so an unsupported kind or an unusable
    /// address here is a second line of defence rather than the primary check.
    /// </para>
    /// </remarks>
    public RemoteSourceOutcome ApplyRemoteSource(SourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);

        if (string.IsNullOrWhiteSpace(record.SyncSourceId))
        {
            return new RemoteSourceOutcome("", false, "对端记录缺少来源标识，已跳过");
        }

        string syncSourceId = record.SyncSourceId.Trim();
        if (!WindowsSourceTypeMap.TryToServerType(record.SourceType.Wire, out ServerType type))
        {
            return new RemoteSourceOutcome(syncSourceId, false, "Windows 暂不支持该来源类型：" + record.SourceType.Wire);
        }

        string? url = SyncUrlNormalizer.Normalize(PrimaryUrl(record));
        if (url is null)
        {
            return new RemoteSourceOutcome(syncSourceId, false, "该来源没有可用地址，已跳过");
        }

        ServerEntry? existing;
        string serverId;
        lock (_gate)
        {
            existing = _servers.FirstOrDefault(s => string.Equals(s.SyncSourceId, syncSourceId, StringComparison.OrdinalIgnoreCase));
            // The bridge's own convention is "ServerId == syncSourceId"; it is reused only when nothing
            // else already owns that id, so an applied source can never displace an unrelated one.
            bool idFree = _servers.All(s => s.ServerId != syncSourceId);
            serverId = existing?.ServerId ?? (idFree ? syncSourceId : "sync:" + syncSourceId);
        }

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = type,
            Url = url,
            DisplayName = string.IsNullOrWhiteSpace(record.DisplayName) ? url : record.DisplayName.Trim(),
            UserName = string.IsNullOrWhiteSpace(record.AccountScope) ? null : record.AccountScope.Trim(),
            SyncSourceId = syncSourceId,
            Status = ServerStatus.AuthRequired,
            StatusDetail = "来自对端信封，需要登录后使用",
        };

        AddEntry(entry);

        _settings.Update(state =>
        {
            state.Servers.RemoveAll(x => x.ServerId == serverId);
            state.Servers.Add(new PersistedServer
            {
                ServerId = serverId,
                Type = entry.Type.ToString(),
                DisplayName = entry.DisplayName,
                Url = entry.Url,
                UserName = entry.UserName,
                SyncSourceId = syncSourceId,
                SyncRecordRevision = record.RecordRevision,
            });
        });

        // The mirror row follows the live list, and now carries the applied revision, so the next preview
        // of the same envelope reaches "already applied" instead of proposing the same write again.
        EnsureSyncSourceIds();
        _log.Info("sync", "已按对端信封写入媒体源：" + entry.DisplayName);
        return new RemoteSourceOutcome(syncSourceId, true, "已写入本地：" + entry.DisplayName + "（需要登录）");
    }

    /// <summary>
    /// Binds a source that predates cross-device identities to the identity the peer matched it with.
    /// </summary>
    /// <remarks>
    /// Adopting is the one apply path that does not touch address or account: the local source keeps its
    /// working configuration and only gains the identity, so a user who has been using it keeps using it.
    /// </remarks>
    public RemoteSourceOutcome AdoptRemoteSource(string serverId, string syncSourceId, long recordRevision)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return new RemoteSourceOutcome("", false, "对端记录缺少来源标识，已跳过");
        }

        string id = syncSourceId.Trim();
        ServerEntry? entry;
        lock (_gate)
        {
            entry = _servers.FirstOrDefault(s => s.ServerId == serverId);
        }

        if (entry is null) return new RemoteSourceOutcome(id, false, "本机没有找到要接管的来源，已跳过");

        entry.SyncSourceId = id;
        _settings.Update(state =>
        {
            PersistedServer? persisted = state.Servers.FirstOrDefault(x => x.ServerId == serverId);
            if (persisted is null) return;
            persisted.SyncSourceId = id;
            persisted.SyncRecordRevision = recordRevision;
        });

        EnsureSyncSourceIds();
        Changed?.Invoke(this, EventArgs.Empty);
        _log.Info("sync", "已接管来源标识：" + entry.DisplayName);
        return new RemoteSourceOutcome(id, true, "已接管已有来源：" + entry.DisplayName + "（需要登录核对）");
    }

    /// <summary>Removes the local source that carries this cross-device identity.</summary>
    /// <remarks>
    /// Goes through <see cref="DeleteAsync"/> so the credential in the vault is removed with the source;
    /// a "deleted" source whose token is still on disk is not deleted.
    /// </remarks>
    public async Task<RemoteSourceOutcome> RemoveRemoteSourceAsync(string syncSourceId, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return new RemoteSourceOutcome("", false, "对端记录缺少来源标识，已跳过");
        }

        string id = syncSourceId.Trim();
        ServerEntry? entry;
        lock (_gate)
        {
            entry = _servers.FirstOrDefault(s => string.Equals(s.SyncSourceId, id, StringComparison.OrdinalIgnoreCase));
        }

        if (entry is null) return new RemoteSourceOutcome(id, false, "本机没有这条来源，已跳过");
        if (entry.Type == ServerType.Local) return new RemoteSourceOutcome(id, false, "本地媒体源不参与跨设备删除");

        string name = entry.DisplayName;
        await DeleteAsync(entry.ServerId, ct).ConfigureAwait(true);
        _log.Info("sync", "已按对端信封移除媒体源：" + name);
        return new RemoteSourceOutcome(id, true, "已从本机移除：" + name);
    }

    /// <summary>Revision this device last stored for a source, or 0 when it never applied one.</summary>
    private long RevisionOf(string serverId)
        => _settings.Current.Servers.FirstOrDefault(x => x.ServerId == serverId)?.SyncRecordRevision ?? 0;

    /// <summary>The address the planner matched on: the primary endpoint, else the first by sort order.</summary>
    private static string? PrimaryUrl(SourceRecord record)
    {
        if (record.Endpoints.Count == 0) return null;
        SourceEndpoint? primary = record.Endpoints
            .Where(endpoint => endpoint.IsPrimary)
            .OrderBy(endpoint => endpoint.SortOrder)
            .FirstOrDefault();
        primary ??= record.Endpoints.OrderBy(endpoint => endpoint.SortOrder).First();
        return primary.Url;
    }

    // ------------------------------------------------------- cross-device identity

    /// <summary>
    /// Gives every source that can travel a stable cross-device identity and mirrors the source list
    /// into the sync store.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Idempotent: an identity that is already known locally (in settings or in the store) is kept
    /// untouched. Re-deriving it would change the identity of a source whose address or account was
    /// edited, and the peer would then show two sources where the user has one.
    /// </para>
    /// <para>
    /// The store mirrors the live list instead of accumulating. A row left behind by a deleted source
    /// would keep telling the other device that this installation still has that source, which is
    /// exactly the kind of thing a user cannot see and cannot fix.
    /// </para>
    /// </remarks>
    /// <returns>How many sources received a new identity.</returns>
    public int EnsureSyncSourceIds()
    {
        if (_syncSources is null) return 0;

        try
        {
            LocalSourceMappingResult mapped = DescribeSyncSources();
            PruneSyncSourceRows(mapped);
            MergeSyncSourceRows(mapped);

            var bridge = new SourceSyncLocalBridge(_syncSources, SyncIdentity.EnsureDeviceId(_settings));
            int assigned = ApplySyncSourceAssignments(bridge.EnsureSourceIds());
            if (assigned > 0) PersistSyncSourceIds();
            return assigned;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            // Cross-device sync is an extra. A sync store that cannot be written must never stop the
            // app from restoring the sources the user actually wants to browse.
            _log.Warn("sync", "无法写入跨设备来源清单：" + ex.Message);
            return 0;
        }
    }

    /// <summary>
    /// Maps the configured sources into the shape the sync contract works with. Read-only.
    /// </summary>
    /// <remarks>
    /// Sources that cannot travel come back as issues instead of records: a local folder has a device path
    /// rather than an address, a pasted credential is not a URL, and an unparseable address cannot be
    /// matched to anything on the other device. Reporting them is what keeps "why is this source missing
    /// from the export" answerable.
    /// </remarks>
    public LocalSourceMappingResult DescribeSyncSources()
    {
        List<LocalSourceMappingInput> inputs;
        lock (_gate)
        {
            inputs = _servers.Select(entry => new LocalSourceMappingInput
            {
                ServerId = entry.ServerId,
                SyncSourceId = entry.SyncSourceId,
                Type = entry.Type,
                Url = entry.Url,
                AccountScope = entry.UserName,
                DisplayName = entry.DisplayName,
                RecordRevision = RevisionOf(entry.ServerId),
            }).ToList();
        }
        return LocalSourceMapper.Map(inputs);
    }

    private void PruneSyncSourceRows(LocalSourceMappingResult mapped)
    {
        if (_syncSources is null) return;

        var present = new HashSet<string>(mapped.Records.Select(record => record.ServerId), StringComparer.Ordinal);
        foreach (LocalSourceRecord stored in _syncSources.GetAll())
        {
            if (present.Contains(stored.ServerId)) continue;
            // Rows without an identity cannot be addressed by the store's delete; they are also never
            // exported (the bridge drops id-less records), so leaving them costs nothing but a line.
            if (stored.SyncSourceId is { Length: > 0 } id) _syncSources.Delete(id);
        }
    }

    private void MergeSyncSourceRows(LocalSourceMappingResult mapped)
    {
        if (_syncSources is null) return;

        var known = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (LocalSourceRecord stored in _syncSources.GetAll()) known[stored.ServerId] = stored.SyncSourceId;

        foreach (LocalSourceRecord record in mapped.Records)
        {
            string? identity = record.SyncSourceId is { Length: > 0 } own
                ? own
                : known.TryGetValue(record.ServerId, out string? storedId) ? storedId : null;
            _syncSources.Save(record with { SyncSourceId = identity });
        }
    }

    private int ApplySyncSourceAssignments(IReadOnlyList<SourceIdAssignment> assignments)
    {
        int assigned = 0;
        foreach (SourceIdAssignment assignment in assignments)
        {
            if (!assignment.Assigned) continue;
            if (assignment.SyncSourceId is not { Length: > 0 } identity) continue;

            ServerEntry? entry = Find(assignment.ServerId);
            if (entry is null || entry.SyncSourceId == identity) continue;
            entry.SyncSourceId = identity;
            assigned++;
        }
        return assigned;
    }

    private void PersistSyncSourceIds()
    {
        var identities = new List<(string ServerId, string SyncSourceId)>();
        lock (_gate)
        {
            foreach (ServerEntry entry in _servers)
            {
                if (entry.SyncSourceId is { Length: > 0 } identity) identities.Add((entry.ServerId, identity));
            }
        }
        if (identities.Count == 0) return;

        _settings.Update(settings =>
        {
            foreach ((string serverId, string syncSourceId) in identities)
            {
                PersistedServer? persisted = settings.Servers.FirstOrDefault(x => x.ServerId == serverId);
                if (persisted is not null) persisted.SyncSourceId = syncSourceId;
            }
        });
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
                SyncSourceId = entry.SyncSourceId,
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

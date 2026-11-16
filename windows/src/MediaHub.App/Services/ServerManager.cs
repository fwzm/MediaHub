using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Net.Security;
using MediaHub.Providers;
using MediaHub.Providers.WebDav;
using MediaHub.Providers.Local;
using MediaHub.Providers.Http;
using System.Diagnostics;
using MediaHub.Providers.Smb;
using MediaHub.Providers.OneDrive;
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
    /// <summary>The server answered, but this account may not open the location.</summary>
    Forbidden,
    /// <summary>A required configuration value is missing (an OAuth client id, for example).</summary>
    NotConfigured,
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

    /// <summary>
    /// OAuth refresh token for this source, held in memory only; the vault is the durable copy.
    /// </summary>
    /// <remarks>
    /// Never written to settings and never logged. A provider that rotates it reports the new value
    /// through <see cref="ProviderHandle.OnSessionRenewed"/>, and only the vault write keeps it.
    /// </remarks>
    public string? RefreshToken { get; set; }

    /// <summary>
    /// SHA-256 of the certificate the user chose to trust for this server, or null for platform trust.
    /// </summary>
    public string? TrustedCertificateSha256 { get; set; }

    /// <summary>
    /// True when this server was explicitly exempted from certificate validation. Shown in diagnostics
    /// so an insecure source is never indistinguishable from a verified one.
    /// </summary>
    public bool AllowUntrustedCertificate { get; set; }

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

    /// <summary>
    /// False for sources that have no login step. "重新登录" opens a password prompt, and offering it
    /// for a plain address would ask for a credential that does not exist and cannot work.
    /// </summary>
    public Microsoft.UI.Xaml.Visibility ReconnectVisibility
        => Type == ServerType.Http ? Microsoft.UI.Xaml.Visibility.Collapsed : Microsoft.UI.Xaml.Visibility.Visible;

    public string StatusText => Status switch
    {
        ServerStatus.Online => string.IsNullOrEmpty(UserName) ? "已连接" : "已连接：" + UserName,
        ServerStatus.Checking => "正在连接…",
        ServerStatus.AuthRequired => "需要登录",
        ServerStatus.AuthFailed => "认证失败" + (string.IsNullOrEmpty(StatusDetail) ? "" : "：" + StatusDetail),
        // The detail is already a full sentence ("没有访问权限：…"), so it is shown as-is.
        ServerStatus.Forbidden => string.IsNullOrEmpty(StatusDetail) ? "没有访问权限" : StatusDetail,
        ServerStatus.NotConfigured => string.IsNullOrEmpty(StatusDetail) ? "未配置" : "未配置：" + StatusDetail,
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
        ServerStatus.AuthFailed or ServerStatus.AuthRequired or ServerStatus.Forbidden
            or ServerStatus.NotConfigured =>
            Windows.UI.Color.FromArgb(255, 0xF7, 0x9B, 0x00),
        ServerStatus.Unreachable => Windows.UI.Color.FromArgb(255, 0xE8, 0x11, 0x23),
        _ => Windows.UI.Color.FromArgb(255, 0x9E, 0x9E, 0x9E),
    });

    public string CapabilitySummary => Handle?.CapabilitySummary ?? "尚无可用能力";

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>
/// Outcome of adding a source.
/// </summary>
/// <remarks>
/// <see cref="UntrustedCertificate"/> is not an error to report and forget: it is the one case where the
/// caller must ask the user a question before any credential is sent, so the certificate travels with
/// the result instead of being flattened into a message string.
/// </remarks>
public sealed record ServerAddResult(bool Ok, string Message, PresentedCertificate? UntrustedCertificate = null);

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
        _providers.Register(new HttpSourceProviderFactory());
        _providers.Register(new SmbMediaProviderFactory());
        _providers.Register(new OneDriveProviderFactory());
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
                TrustedCertificateSha256 = CertificateTrust.NormalizeFingerprint(persisted.TrustedCertificateSha256),
                AllowUntrustedCertificate = persisted.AllowUntrustedCertificate,
                Status = ServerStatus.Checking,
            };
            AddEntry(entry);

            await RestoreAsync(entry, ct).ConfigureAwait(true);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private async Task RestoreAsync(ServerEntry entry, CancellationToken ct)
    {
        // A plain address has no account and no session, so there is nothing to read from the vault and
        // nothing to restore. Treating it like an authenticated source would have marked it "需要登录"
        // forever, because no secret for it was ever written.
        if (entry.Type == ServerType.Http)
        {
            try
            {
                ProviderHandle? plain = BuildHandle(entry, credential: null, PolicyFor(entry));
                if (plain?.Playback is null || plain.Browse is null)
                {
                    entry.Status = ServerStatus.Unreachable;
                    entry.StatusDetail = "该地址无法作为直链来源使用";
                    return;
                }
                entry.Handle = plain;
                entry.Status = ServerStatus.Online;
                entry.StatusDetail = null;
            }
            catch (ProviderException ex)
            {
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = ex.Message;
            }
            return;
        }

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

        // A share is opened with stored credentials when there are any, and with the Windows identity
        // when there are none. "No secret" is therefore a valid state for this type rather than an
        // unfinished login, which is why it is handled before the hasSecret gate below.
        if (entry.Type == ServerType.Smb)
        {
            await RestoreSmbAsync(entry, credential, ct).ConfigureAwait(true);
            return;
        }

        if (entry.Type == ServerType.OneDrive)
        {
            await RestoreOneDriveAsync(entry, credential, ct).ConfigureAwait(true);
            return;
        }

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

        ProviderHandle? handle = BuildHandle(entry, credential, PolicyFor(entry));
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

    /// <summary>
    /// Rebuilds a share source and checks whether it can be read right now.
    /// </summary>
    /// <remarks>
    /// Unlike the HTTP source this one does probe on restore, because a share has two distinct failure
    /// modes the user must be able to tell apart at a glance: the share is gone (offline) and the share
    /// now demands a password nobody stored (needs login). Both offer a retry from the same card.
    /// </remarks>
    private async Task RestoreSmbAsync(ServerEntry entry, ServerCredential? credential, CancellationToken ct)
    {
        if (!SmbAddress.TryParse(entry.Url, out SmbAddress address, out string error))
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = "共享地址无效：" + error;
            return;
        }

        try
        {
            ProviderHandle? handle = BuildHandle(entry, credential, PolicyFor(entry));
            if (handle?.Browse is null || handle.Playback is null)
            {
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = "该共享地址无法作为来源使用";
                return;
            }

            entry.Handle = handle;
            entry.UserName ??= credential?.UserName;
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            return;
        }

        SmbProbeResult probe = await SmbProbe
            .ProbeAsync(address, entry.UserName, credential?.Password, ct)
            .ConfigureAwait(true);
        ApplySmbProbe(entry, probe);
    }

    /// <summary>
    /// Rebuilds a OneDrive source and renews its session without prompting.
    /// </summary>
    /// <remarks>
    /// The refresh token in the vault is the whole login. A revoked or expired grant is reported as
    /// "needs login" (the browser step is the only recovery), a network problem as offline, and a
    /// missing client id as a configuration problem - three different fixes that must not collapse
    /// into one "server unreachable" card.
    /// </remarks>
    private async Task RestoreOneDriveAsync(ServerEntry entry, ServerCredential? credential, CancellationToken ct)
    {
        entry.RefreshToken = credential?.Token;
        entry.UserName = credential?.UserName;

        OneDriveConfig config = OneDriveConfiguration();
        if (config.Validate() is not null)
        {
            entry.Status = ServerStatus.NotConfigured;
            entry.StatusDetail = "缺少 OneDrive 客户端 ID";
            return;
        }

        if (string.IsNullOrEmpty(entry.RefreshToken))
        {
            entry.Status = ServerStatus.AuthRequired;
            entry.StatusDetail = "需要浏览器授权";
            return;
        }

        ProviderHandle? handle;
        try
        {
            handle = BuildHandle(entry, credential, PolicyFor(entry));
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            return;
        }

        if (handle?.Auth is null || handle.Browse is null || handle.Playback is null)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = "该 OneDrive 来源无法作为媒体来源使用";
            return;
        }

        entry.Handle = handle;
        try
        {
            AuthSessionState state = await handle.Auth.RestoreSessionAsync(ct).ConfigureAwait(true);
            ApplySessionState(entry, state);
            if (state == AuthSessionState.Expired)
                entry.StatusDetail = "OneDrive 授权已过期，请重新登录";
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
        }
    }

    private static void ApplySmbProbe(ServerEntry entry, SmbProbeResult probe)
    {
        if (probe.Ok)
        {
            entry.Status = ServerStatus.Online;
            entry.StatusDetail = null;
            return;
        }

        entry.Status = probe.Failure switch
        {
            SmbFailure.CredentialsRequired => ServerStatus.AuthRequired,
            SmbFailure.Authentication => ServerStatus.AuthFailed,
            // A permission is not a connectivity problem: the host answered and the share opened.
            SmbFailure.Permission => ServerStatus.Forbidden,
            _ => ServerStatus.Unreachable,
        };
        entry.StatusDetail = probe.Message;
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
    /// <remarks>
    /// <paramref name="userName"/> and <paramref name="password"/> exist for the share type, which cannot
    /// be tested without the account the user just typed; every other type validates its address only.
    /// </remarks>
    public async Task<(bool Ok, string Message)> TestConnectionAsync(
        string input, ServerType type, NetworkPolicy policy, CancellationToken ct = default,
        string? userName = null, string? password = null)
    {
        // An OAuth source has no address to probe. What can be checked before the browser opens is
        // whether the public-client registration has been filled in; saying "connected" here would be
        // a claim about an account nobody has signed into yet.
        if (type == ServerType.OneDrive)
        {
            OneDriveConfig config = OneDriveConfiguration();
            if (config.Validate() is { } configError) return (false, configError);
            return (false, "客户端 ID 已配置。点击“登录并添加”会在系统浏览器打开 Microsoft 登录页。");
        }

        if (type == ServerType.Smb)
        {
            if (!SmbAddress.TryParse(input, out SmbAddress address, out string shareError))
                return (false, shareError);

            SmbProbeResult share = await SmbProbe.ProbeAsync(address, userName, password, ct).ConfigureAwait(true);
            return (share.Ok, share.Message);
        }

        if (!ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl normalized, out string? error))
            return (false, error ?? "地址无效");

        // A plain address has no server API to interrogate: reachability is the whole check, and it is
        // what tells a typo apart from a link that is merely slow.
        if (type == ServerType.Http)
            return await HttpSourceProbe.ProbeAsync(normalized.BaseUri, policy, ct).ConfigureAwait(true);

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
    public async Task<ServerAddResult> AddAsync(
        string input, ServerType type, string userName, string password, NetworkPolicy policy, CancellationToken ct = default)
    {
        if (type == ServerType.Smb)
            return await AddSmbAsync(input, userName, password, ct).ConfigureAwait(true);

        if (type == ServerType.OneDrive)
            return await AddOneDriveAsync(ct).ConfigureAwait(true);

        if (!ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl normalized, out string? error))
            return new(false, error ?? "地址无效");

        if (type == ServerType.Http)
            return await AddHttpAsync(normalized, policy, ct).ConfigureAwait(true);

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
            if (!dav.Success)
            {
                // The handshake fails before the Basic header is ever written, so this probe cannot leak
                // the password even though it was handed the credentials.
                PresentedCertificate? untrusted = await InspectUntrustedAsync(normalized.BaseUri, policy, ct).ConfigureAwait(true);
                return untrusted is not null ? UntrustedResult(untrusted) : new(false, dav.Message);
            }
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
            if (!probe.Success)
            {
                PresentedCertificate? untrusted = await InspectUntrustedAsync(normalized.BaseUri, policy, ct).ConfigureAwait(true);
                return untrusted is not null ? UntrustedResult(untrusted) : new(false, probe.Message);
            }
            probeServerName = probe.ServerName;
            probeRemoteId = probe.RemoteServerId;
        }

        // The remote server id binds the credential to one physical server, so a token can never be
        // replayed against a different server that happens to reuse the same host name.
        string serverId = type.ToString().ToLowerInvariant() + ":" + (probeRemoteId ?? normalized.BaseUri.Host);
        if (Find(serverId) is not null) return new(false, "该服务器已经添加过了");

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = type,
            Url = normalized.BaseUri.ToString(),
            DisplayName = probeServerName ?? normalized.BaseUri.Host,
            UserName = userName,
            // Only the pin that actually let this probe through is stored: the successful retry carries
            // the fingerprint the user just confirmed.
            TrustedCertificateSha256 = CertificateTrust.NormalizeFingerprint(policy.TrustedCertificateSha256),
            // Both options are per server and persisted with it; the pair is validated as mutually
            // exclusive by NetworkPolicy.Validate.
            AllowUntrustedCertificate = policy.AllowUntrustedCertificate,
            Status = ServerStatus.Checking,
        };

        var handle = BuildHandle(entry, null, policy);
        if (handle?.Auth is null) return new(false, "该数据源不支持认证");
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
            return new(false, ex.Message);
        }

        await PersistAsync(entry, probeRemoteId, password, ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return new(true, "已添加：" + entry.DisplayName);
    }

    /// <summary>
    /// Adds a plain http(s) address or playlist: no login, one reachability check, no secret stored.
    /// </summary>
    /// <remarks>
    /// The address is the identity. Two different links are two sources, and the same link pasted twice
    /// is refused rather than silently duplicated. Nothing is written to the vault: there is no
    /// credential, and saving an empty one would leave a decryptable record that reads like a login.
    /// </remarks>
    private async Task<ServerAddResult> AddHttpAsync(NormalizedServerUrl normalized, NetworkPolicy policy, CancellationToken ct)
    {
        (bool ok, string message) = await HttpSourceProbe
            .ProbeAsync(normalized.BaseUri, policy, ct).ConfigureAwait(true);
        if (!ok) return new(false, message);

        // One address, one source. The full address (query included) is what the user typed and what the
        // server must receive again at play time; a host-only identity would merge two links on one host.
        string serverId = "http:" + normalized.BaseUri.AbsoluteUri;
        if (Find(serverId) is not null) return new(false, "该地址已经添加过了");

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = ServerType.Http,
            Url = normalized.BaseUri.AbsoluteUri,
            DisplayName = HttpDisplayName(normalized.BaseUri),
            // No account exists, so the status line stays the plain "已连接" shape.
            UserName = null,
            TrustedCertificateSha256 = CertificateTrust.NormalizeFingerprint(policy.TrustedCertificateSha256),
            AllowUntrustedCertificate = policy.AllowUntrustedCertificate,
            Status = ServerStatus.Online,
        };

        ProviderHandle? handle = BuildHandle(entry, credential: null, policy);
        if (handle?.Playback is null) return new(false, "该地址无法作为直链来源使用");

        entry.Handle = handle;
        AddEntry(entry);
        await PersistAsync(entry, remoteServerId: null, password: "", ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return new(true, "已添加：" + entry.DisplayName);
    }

    /// <summary>Adds a share after proving the account can read it, then stores an optional secret.</summary>
    /// <remarks>
    /// The probe runs with the credentials from the form because a share that requires an account
    /// answers every read with "access denied" until one is presented; testing anonymously first would
    /// make every protected share unaddable, which is the same mistake the WebDAV probe was fixed for.
    /// </remarks>
    private async Task<ServerAddResult> AddSmbAsync(string input, string userName, string password, CancellationToken ct)
    {
        if (!SmbAddress.TryParse(input, out SmbAddress address, out string error))
            return new(false, error);

        string? account = string.IsNullOrWhiteSpace(userName) ? null : userName.Trim();
        if (account is null && !string.IsNullOrEmpty(password))
            return new(false, "填写密码时请同时填写账号");

        SmbProbeResult probe = await SmbProbe.ProbeAsync(address, account, password, ct).ConfigureAwait(true);
        if (!probe.Ok) return new(false, probe.Message);

        // One share, one source; two entries for the same share would open two connections and two
        // separate local histories for the same files.
        string serverId = "smb:" + address.UncRoot;
        if (Find(serverId) is not null) return new(false, "该共享已经添加过了");

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = ServerType.Smb,
            Url = address.UncRoot,
            DisplayName = address.DisplayName,
            UserName = account,
            TrustedCertificateSha256 = null,
            AllowUntrustedCertificate = false,
            Status = ServerStatus.Online,
        };

        ServerCredential? credential = account is null
            ? null
            : new ServerCredential { ServerId = serverId, UserName = account, Password = password };

        ProviderHandle? handle;
        try
        {
            handle = BuildHandle(entry, credential, PolicyFor(entry));
        }
        catch (ProviderException ex)
        {
            return new(false, ex.Message);
        }

        if (handle?.Browse is null || handle.Playback is null)
            return new(false, "该共享地址无法作为来源使用");

        entry.Handle = handle;
        AddEntry(entry);
        // An anonymous share writes nothing: a vault record with no account would be a secret that is
        // not a secret, and it would make the source look like it had credentials to renew.
        await PersistAsync(entry, remoteServerId: null, password: account is null ? "" : password, ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return new(true, "已添加：" + entry.DisplayName);
    }

    // ---------------------------------------------------------------- OneDrive

    /// <summary>Client-id / redirect-URI pair the sign-in will use, read from settings each time.</summary>
    private OneDriveConfig OneDriveConfiguration()
        => OneDriveConfig.FromSettings(_settings.Current.OneDriveClientId, _settings.Current.OneDriveRedirectUri);

    /// <summary>False when the public-client registration has not been filled in.</summary>
    public bool OneDriveConfigured => OneDriveConfiguration().Validate() is null;

    /// <summary>The two-step Entra setup, shown when the client id is missing.</summary>
    public string OneDriveConfigHelp => OneDriveConfig.MissingClientIdHelp;

    /// <summary>
    /// Signs in through the system browser, then stores the definition and the refresh token.
    /// </summary>
    /// <remarks>
    /// The account id is the source identity, so signing in again after a token is revoked replaces the
    /// session of the same source instead of creating a second card for the same drive. Nothing about
    /// the tokens is logged; the browser step is the only place a password is typed and this process
    /// never sees it.
    /// </remarks>
    private async Task<ServerAddResult> AddOneDriveAsync(CancellationToken ct)
    {
        OneDriveConfig config = OneDriveConfiguration();
        if (config.Validate() is { } configError) return new(false, configError);

        OneDriveTokens tokens;
        string accountId;
        string accountName;
        try
        {
            (tokens, accountId, accountName) = await OneDriveOAuth.SignInAndVerifyAsync(
                config, NetworkPolicy.FollowSystem, LaunchSystemBrowser, OneDriveOAuth.DefaultTimeout, ct)
                .ConfigureAwait(true);
        }
        catch (OneDriveAuthException ex)
        {
            _log.Warn("sources", "OneDrive 授权未完成：" + ex.GetType().Name);
            return new(false, ex.Message);
        }
        catch (OperationCanceledException)
        {
            return new(false, "已取消 OneDrive 授权，没有添加任何内容。");
        }
        catch (ProviderException ex)
        {
            return new(false, "已登录，但这个账号无法读取 OneDrive 文件：" + ex.Message);
        }

        string serverId = "onedrive:" + accountId;
        if (Find(serverId) is not null) return new(false, "该 OneDrive 账号已经添加过了");

        var entry = new ServerEntry
        {
            ServerId = serverId,
            Type = ServerType.OneDrive,
            Url = config.GraphBase.ToString(),
            DisplayName = "OneDrive（" + accountName + "）",
            UserName = accountName,
            RefreshToken = tokens.RefreshToken,
            Status = ServerStatus.Checking,
        };

        ProviderHandle? handle;
        try
        {
            handle = BuildHandle(entry, new ServerCredential
            {
                ServerId = serverId,
                UserName = accountName,
                Token = tokens.RefreshToken,
                UserId = accountId,
            }, PolicyFor(entry));
        }
        catch (ProviderException ex)
        {
            return new(false, ex.Message);
        }

        if (handle?.Auth is null || handle.Browse is null || handle.Playback is null)
            return new(false, "该 OneDrive 来源无法作为媒体来源使用");

        entry.Handle = handle;
        AddEntry(entry);

        try
        {
            // The exchange above proves consent; this proves the handle can renew on its own, which is
            // the path every later launch takes.
            AuthResult result = await handle.Auth.AuthenticateAsync(new Credentials("", ""), ct).ConfigureAwait(true);
            entry.UserName = result.UserName;
            entry.Status = ServerStatus.Online;
            entry.StatusDetail = null;
        }
        catch (ProviderException ex)
        {
            RemoveEntry(serverId, disposeHandle: true);
            return new(false, ex.Message);
        }

        await PersistAsync(entry, accountId, "", ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return new(true, "已添加：" + entry.DisplayName);
    }

    /// <summary>Runs the browser flow again for an existing OneDrive source.</summary>
    private async Task<(bool Ok, string Message)> ReconnectOneDriveAsync(ServerEntry entry, CancellationToken ct)
    {
        OneDriveConfig config = OneDriveConfiguration();
        if (config.Validate() is { } configError)
        {
            entry.Status = ServerStatus.NotConfigured;
            entry.StatusDetail = "缺少 OneDrive 客户端 ID";
            return (false, configError);
        }

        entry.Status = ServerStatus.Checking;
        try
        {
            var (tokens, accountId, accountName) = await OneDriveOAuth.SignInAndVerifyAsync(
                config, PolicyFor(entry), LaunchSystemBrowser, OneDriveOAuth.DefaultTimeout, ct)
                .ConfigureAwait(true);

            // Switching accounts under the same card would silently re-point a source, and with it the
            // local history, at somebody else's drive.
            if (!string.Equals("onedrive:" + accountId, entry.ServerId, StringComparison.Ordinal))
            {
                entry.Status = ServerStatus.AuthRequired;
                entry.StatusDetail = "这次授权的是另一个 Microsoft 账号（" + accountName + "）";
                return (false, entry.StatusDetail);
            }

            entry.RefreshToken = tokens.RefreshToken;
            entry.UserName = accountName;
            entry.Handle?.Dispose();
            entry.Handle = BuildHandle(entry, new ServerCredential
            {
                ServerId = entry.ServerId,
                UserName = accountName,
                Token = tokens.RefreshToken,
                UserId = accountId,
            }, PolicyFor(entry));

            if (entry.Handle?.Auth is null)
            {
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = "该 OneDrive 来源无法作为媒体来源使用";
                return (false, entry.StatusDetail);
            }

            entry.Status = ServerStatus.Online;
            entry.StatusDetail = null;
        }
        catch (OneDriveAuthException ex)
        {
            entry.Status = ServerStatus.AuthRequired;
            entry.StatusDetail = ex.Message;
            return (false, ex.Message);
        }
        catch (OperationCanceledException)
        {
            entry.Status = ServerStatus.AuthRequired;
            entry.StatusDetail = "已取消 OneDrive 授权";
            return (false, entry.StatusDetail);
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            return (false, ex.Message);
        }

        await PersistAsync(entry, entry.ServerId["onedrive:".Length..], "", ct).ConfigureAwait(true);
        Changed?.Invoke(this, EventArgs.Empty);
        return (true, "已重新连接：" + entry.DisplayName);
    }

    /// <summary>Renews the session of an OneDrive source without opening the browser.</summary>
    private async Task<(bool Ok, string Message)> RetestOneDriveAsync(ServerEntry entry, CancellationToken ct)
    {
        OneDriveConfig config = OneDriveConfiguration();
        if (config.Validate() is not null)
        {
            entry.Status = ServerStatus.NotConfigured;
            entry.StatusDetail = "缺少 OneDrive 客户端 ID";
            return (false, OneDriveConfig.MissingClientIdHelp);
        }

        if (entry.RefreshToken is null || entry.Handle?.Auth is null)
        {
            VaultLoadResult loaded = await _vault.LoadAsync(entry.ServerId, ct).ConfigureAwait(true);
            entry.RefreshToken ??= loaded.Credential?.Token;
            if (entry.RefreshToken is null)
            {
                entry.Status = ServerStatus.AuthRequired;
                entry.StatusDetail = "需要浏览器授权";
                return (false, "需要重新登录 OneDrive");
            }

            entry.Handle?.Dispose();
            entry.Handle = BuildHandle(entry, loaded.Credential, PolicyFor(entry));
        }

        if (entry.Handle?.Auth is null)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = "该 OneDrive 来源无法作为媒体来源使用";
            return (false, entry.StatusDetail);
        }

        try
        {
            AuthSessionState state = await entry.Handle.Auth.RestoreSessionAsync(ct).ConfigureAwait(true);
            ApplySessionState(entry, state);
            if (state == AuthSessionState.Expired)
                entry.StatusDetail = "OneDrive 授权已过期，请重新登录";
        }
        catch (ProviderException ex)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = ex.Message;
            return (false, ex.Message);
        }

        return (entry.Status == ServerStatus.Online, entry.StatusText);
    }

    /// <summary>Hands the consent URL to the system browser; credentials are never typed into this app.</summary>
    private static void LaunchSystemBrowser(Uri url)
        => Process.Start(new ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true });

    /// <summary>Names a plain address by its last path segment, falling back to the host.</summary>
    private static string HttpDisplayName(Uri uri)
    {
        string last = uri.Segments.Length > 0 ? uri.Segments[^1] : "";
        string decoded = Uri.UnescapeDataString(last).Trim('/');
        return decoded.Length > 0 ? decoded : uri.Host;
    }

    /// <summary>Re-authenticates an existing source, typically after its session expired.</summary>
    public async Task<(bool Ok, string Message)> ReconnectAsync(string serverId, string password, CancellationToken ct = default)
    {
        ServerEntry? entry = Find(serverId);
        if (entry is null) return (false, "该媒体源不存在");

        // Nothing to log in to: for a plain address the only meaningful recovery is the shared
        // reachability check, so expose exactly that instead of an authentication it does not have.
        if (entry.Type == ServerType.Http)
            return await RetestAsync(serverId, ct).ConfigureAwait(true);

        // A share logs in by opening the connection again, not by exchanging a session token, so the
        // password typed in the reconnect dialog is verified with the same probe the add dialog uses.
        if (entry.Type == ServerType.Smb)
            return await ReconnectSmbAsync(entry, password, ct).ConfigureAwait(true);

        // "Re-login" for an OAuth source is the browser flow again; the password dialog is not shown
        // for this type because there is no password to type.
        if (entry.Type == ServerType.OneDrive)
            return await ReconnectOneDriveAsync(entry, ct).ConfigureAwait(true);

        VaultLoadResult loaded = await _vault.LoadAsync(serverId, ct).ConfigureAwait(true);

        // Rebuild the handle when restore did not manage to create one. Reporting "该媒体源不存在" for a
        // source that is plainly listed in the UI sends the user to delete and re-add it; the entry,
        // the URL and the credential are all still here, so reconnecting is what was actually asked
        // for. This is also the path that makes a WebDAV source recoverable after a restart.
        if (entry.Handle?.Auth is null)
        {
            if (loaded.Credential is null) return (false, "凭据已丢失，请重新添加该媒体源");
            ProviderHandle? rebuilt = BuildHandle(entry, loaded.Credential, PolicyFor(entry));
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

    /// <summary>
    /// Re-opens a share with the password the user just typed and replaces the handle built with the
    /// old one.
    /// </summary>
    /// <remarks>
    /// The handle is rebuilt rather than reused: a provider caches the account it was constructed with,
    /// so keeping the old instance would leave browsing authenticated with the password that failed.
    /// </remarks>
    private async Task<(bool Ok, string Message)> ReconnectSmbAsync(ServerEntry entry, string password, CancellationToken ct)
    {
        if (!SmbAddress.TryParse(entry.Url, out SmbAddress address, out string error))
            return (false, "共享地址无效：" + error);

        string? account = string.IsNullOrWhiteSpace(entry.UserName) ? null : entry.UserName;
        entry.Status = ServerStatus.Checking;

        SmbProbeResult probe = await SmbProbe.ProbeAsync(address, account, password, ct).ConfigureAwait(true);
        ApplySmbProbe(entry, probe);
        if (!probe.Ok) return (false, probe.Message);

        var credential = account is null
            ? null
            : new ServerCredential { ServerId = entry.ServerId, UserName = account, Password = password };

        ProviderHandle? rebuilt = BuildHandle(entry, credential, PolicyFor(entry));
        if (rebuilt?.Browse is null || rebuilt.Playback is null) return (false, "该共享地址无法作为来源使用");
        entry.Handle?.Dispose();
        entry.Handle = rebuilt;

        if (account is not null)
        {
            VaultLoadResult previous = await _vault.LoadAsync(entry.ServerId, ct).ConfigureAwait(true);
            await _vault.SaveAsync(new ServerCredential
            {
                ServerId = entry.ServerId,
                UserName = account,
                Password = password,
                RemoteServerId = previous.Credential?.RemoteServerId,
                SavedAtUtc = DateTimeOffset.UtcNow,
            }, ct).ConfigureAwait(true);
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return (true, "已重新连接：" + entry.DisplayName);
    }

    /// <summary>Re-checks a share with whatever account it already has, and reports what to fix.</summary>
    private async Task<(bool Ok, string Message)> RetestSmbAsync(ServerEntry entry, CancellationToken ct)
    {
        if (!SmbAddress.TryParse(entry.Url, out SmbAddress address, out string error))
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = "共享地址无效：" + error;
            return (false, entry.StatusDetail);
        }

        string? account = string.IsNullOrWhiteSpace(entry.UserName) ? null : entry.UserName;
        string? password = null;
        if (account is not null)
        {
            VaultLoadResult loaded = await _vault.LoadAsync(entry.ServerId, ct).ConfigureAwait(true);
            password = loaded.Credential?.Password;
        }

        SmbProbeResult probe = await SmbProbe.ProbeAsync(address, account, password, ct).ConfigureAwait(true);
        ApplySmbProbe(entry, probe);
        if (!probe.Ok) return (false, probe.Message);

        if (entry.Handle is null)
        {
            var credential = account is null
                ? null
                : new ServerCredential { ServerId = entry.ServerId, UserName = account, Password = password };
            ProviderHandle? rebuilt = BuildHandle(entry, credential, PolicyFor(entry));
            if (rebuilt?.Browse is null || rebuilt.Playback is null)
            {
                entry.Status = ServerStatus.Unreachable;
                entry.StatusDetail = "该共享地址无法作为来源使用";
                return (false, entry.StatusDetail);
            }
            entry.Handle = rebuilt;
        }

        return (true, probe.Message);
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

        if (entry.Type == ServerType.Smb) return await RetestSmbAsync(entry, ct).ConfigureAwait(true);

        if (entry.Type == ServerType.OneDrive) return await RetestOneDriveAsync(entry, ct).ConfigureAwait(true);

        var policy = PolicyFor(entry);
        var (ok, message) = await TestConnectionAsync(entry.Url, entry.Type, policy, ct).ConfigureAwait(true);
        if (!ok)
        {
            entry.Status = ServerStatus.Unreachable;
            entry.StatusDetail = message;
            return (false, message);
        }

        if (entry.Type == ServerType.Http)
        {
            // Reachability is the whole health check, so a successful probe is the online state. The
            // handle is rebuilt when restore could not create one (a definition that survived while its
            // provider factory was missing), which is the same recovery the authenticated path offers.
            entry.Handle ??= BuildHandle(entry, credential: null, policy);
            entry.Status = entry.Handle?.Playback is null ? ServerStatus.Unreachable : ServerStatus.Online;
            entry.StatusDetail = entry.Handle?.Playback is null ? "该地址无法作为直链来源使用" : null;
            return (entry.Status == ServerStatus.Online, message);
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

    /// <summary>
    /// The network policy this source must use, including its pinned certificate when it has one.
    /// </summary>
    /// <remarks>
    /// Every path that builds a handle, probes the server or relays its stream goes through here. Before
    /// this existed, the restore, reconnect and retest paths each wrote <c>NetworkPolicy.FollowSystem</c>
    /// by hand, so a pinned server would have connected when it was added and then failed to reconnect
    /// after a restart. Playback forwarding had the same gap: browsing a pinned server worked while
    /// playing from it failed the handshake.
    /// </remarks>
    public static NetworkPolicy PolicyFor(ServerEntry entry)
        => NetworkPolicy.FollowSystem with
        {
            TrustedCertificateSha256 = entry.TrustedCertificateSha256,
            AllowUntrustedCertificate = entry.AllowUntrustedCertificate,
        };

    /// <summary>
    /// Reads the certificate of a server the platform does not trust, for the "trust this?" prompt.
    /// </summary>
    /// <remarks>
    /// Returns null when there is nothing to ask about: the address is plain HTTP, the platform already
    /// trusts the certificate, the server is unreachable, or this policy already carries a pin (a retry
    /// with the same pin that just failed must not loop the user back to the same question).
    /// </remarks>
    private static async Task<PresentedCertificate?> InspectUntrustedAsync(Uri baseUri, NetworkPolicy policy, CancellationToken ct)
    {
        if (CertificateTrust.NormalizeFingerprint(policy.TrustedCertificateSha256) is not null) return null;
        if (policy.AllowUntrustedCertificate) return null;
        return await CertificateInspector.InspectAsync(baseUri, policy.ConnectTimeout, ct).ConfigureAwait(false);
    }

    private static ServerAddResult UntrustedResult(PresentedCertificate certificate) => new(
        false,
        "服务器使用了本机不信任的证书。确认该证书指纹后才能继续登录；确认之前不会发送账号、密码或令牌。",
        certificate);

    /// <summary>
    /// Inspects the certificate a server presents, for the dialog's "test connection" feedback.
    /// </summary>
    /// <remarks>
    /// Static and side-effect free on purpose: the hint in the add dialog must be able to say "this
    /// server's certificate is not trusted, fingerprint X" without creating an entry or storing a pin.
    /// </remarks>
    public static async Task<PresentedCertificate?> InspectCertificateAsync(
        string input, ServerType type, TimeSpan timeout, CancellationToken ct = default)
    {
        if (!ServerUrlNormalizer.TryNormalize(input, type, out NormalizedServerUrl normalized, out _)) return null;
        return await CertificateInspector.InspectAsync(normalized.BaseUri, timeout, ct).ConfigureAwait(false);
    }

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
                // The OAuth pair: a refresh token that must be renewed by the provider, plus the two
                // public-client registration values (neither is a secret).
                ["refreshToken"] = credential.Token ?? "",
                ["oneDriveClientId"] = _settings.Current.OneDriveClientId ?? "",
                ["oneDriveRedirectUri"] = _settings.Current.OneDriveRedirectUri ?? "",
            };

        ProviderHandle? handle = _providers.Create(server, policy ?? NetworkPolicy.FollowSystem, credentials);
        if (handle is not null)
        {
            handle.OnSessionExpired = () =>
            {
                entry.Status = ServerStatus.AuthRequired;
                entry.StatusDetail = "登录状态已过期";
            };

            // Entra rotates the refresh token every time it is used, so the value in the vault is
            // stale the moment it works. Only the vault write keeps the new one; it is never logged.
            handle.OnSessionRenewed = token => OnOneDriveTokenRenewed(entry, token);
        }
        return handle;
    }

    private void OnOneDriveTokenRenewed(ServerEntry entry, string refreshToken)
    {
        entry.RefreshToken = refreshToken;
        _ = SaveOneDriveTokenAsync(entry, refreshToken);
    }

    private async Task SaveOneDriveTokenAsync(ServerEntry entry, string refreshToken)
    {
        try
        {
            VaultLoadResult previous = await _vault.LoadAsync(entry.ServerId).ConfigureAwait(false);
            await _vault.SaveAsync(new ServerCredential
            {
                ServerId = entry.ServerId,
                UserName = entry.UserName,
                Token = refreshToken,
                UserId = previous.Credential?.UserId,
                RemoteServerId = previous.Credential?.RemoteServerId,
                SavedAtUtc = DateTimeOffset.UtcNow,
            }).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            // A failed rotation is recoverable by signing in again; it must not take down playback.
            _log.Warn("sources", "OneDrive 令牌续期未能写入保险库：" + ex.GetType().Name);
        }
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
                // Not a secret: a certificate fingerprint is public material, and persisting it is what
                // lets a pinned server reconnect after a restart without asking the user again.
                TrustedCertificateSha256 = entry.TrustedCertificateSha256,
                AllowUntrustedCertificate = entry.AllowUntrustedCertificate,
            });
        });

        // A plain address has no secret. Writing an empty credential would make the vault claim a login
        // exists for a source that never had one, and every later restore would try to decrypt it. The
        // same holds for an anonymous share, which is browsed with the Windows identity.
        if (entry.Type == ServerType.Http) return;
        if (entry.Type == ServerType.Smb && string.IsNullOrEmpty(entry.UserName) && string.IsNullOrEmpty(password)) return;

        // OneDrive has no password and no session token: the refresh token in the entry is the login.
        // It goes to the vault, never to settings, and the vault entry is the only durable copy.
        if (entry.Type == ServerType.OneDrive)
        {
            if (string.IsNullOrEmpty(entry.RefreshToken)) return;
            await _vault.SaveAsync(new ServerCredential
            {
                ServerId = entry.ServerId,
                UserName = entry.UserName,
                Token = entry.RefreshToken,
                UserId = remoteServerId,
                RemoteServerId = remoteServerId,
                SavedAtUtc = DateTimeOffset.UtcNow,
            }, ct).ConfigureAwait(true);
            return;
        }

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

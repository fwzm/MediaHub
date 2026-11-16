using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers.Local;

namespace MediaHub.Providers.Smb;

/// <summary>
/// Registers a Windows file share as a source that can be browsed and played directly.
/// </summary>
/// <remarks>
/// Deliberately built on the operating system's redirector: the share is opened with
/// <c>WNetAddConnection2</c> and read through ordinary <see cref="System.IO"/> calls, so no SMB client
/// library ships with the app. Playback reuses the local file path chain, which is why the URL handed
/// to the engine is a plain <c>file:</c> URI for a UNC path.
/// </remarks>
public sealed class SmbMediaProviderFactory : IMediaProviderFactory
{
    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.Smb,
        "SMB 共享",
        @"Windows 网络共享（\\主机\共享），账号密码可选",
        ProviderCapability.Browse | ProviderCapability.Playback | ProviderCapability.Progress);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        string raw = server.PrimaryEndpoint?.Url ?? "";
        if (!SmbAddress.TryParse(raw, out SmbAddress address, out string error))
            throw new ProviderException(server.ServerId, ProviderErrorCode.Connection, "共享地址无效：" + error);

        string? userName = null;
        string? password = null;
        credentials?.TryGetValue("username", out userName);
        credentials?.TryGetValue("password", out password);

        var provider = new SmbMediaProvider(server.ServerId, address, userName, password);
        return new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = Descriptor,
            Browse = provider,
            Playback = provider,
            Progress = provider,
        };
    }
}

/// <summary>
/// Browses and plays one share, translating redirector failures into readable sentences.
/// </summary>
/// <remarks>
/// Listing and playback are delegated to <see cref="LocalMediaProvider"/> so a UNC file goes through
/// exactly the same code as a file on <c>C:</c> - one playback chain, no SMB-specific player path.
/// What this type adds is the share connection and the error vocabulary: the shared path provider
/// can only say "network error" for an authentication failure, and a user who typed the wrong
/// password needs to read "账号或密码不正确".
/// </remarks>
internal sealed class SmbMediaProvider : IProviderBrowse, IProviderPlayback, IProviderProgress
{
    private static readonly IReadOnlyDictionary<string, string> NoHeaders =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

    private readonly string _serverId;
    private readonly SmbAddress _address;
    private readonly SmbShareConnection _share;
    private readonly LocalMediaProvider _files;

    public SmbMediaProvider(string serverId, SmbAddress address, string? userName, string? password)
    {
        _serverId = serverId;
        _address = address;
        _share = new SmbShareConnection(address, userName, password, SmbProbe.Timeout);
        _files = new LocalMediaProvider(serverId, address.UncRoot);
    }

    public async Task<PagedResult<MediaItem>> ListFolderAsync(MediaItem? folder, PageRequest page, CancellationToken ct = default)
    {
        string path = folder?.Path ?? _address.UncRoot;
        await EnsureReachableAsync(path, isFolder: true, ct).ConfigureAwait(false);
        return await _files.ListFolderAsync(folder, page, ct).ConfigureAwait(false);
    }

    /// <summary>A share file needs no request headers; the engine opens a file URI.</summary>
    public IReadOnlyDictionary<string, string> StreamRequestHeaders => NoHeaders;

    /// <summary>No headers means no origin to restrict them to.</summary>
    public Uri? StreamHeaderOrigin => null;

    public async Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        string path = item.Path ?? item.Id;
        await EnsureReachableAsync(path, isFolder: false, ct).ConfigureAwait(false);
        return await _files.ResolvePlaybackAsync(item, options, ct).ConfigureAwait(false);
    }

    // A share has nowhere to report progress: there is no server-side resume position, so the local
    // snapshot store is the only record. Progress is still advertised, because that presence is what
    // makes the coordinator write those local snapshots and the resume fallback pick them up.
    public Task ReportProgressAsync(PlaybackProgress progress, CancellationToken ct = default) => Task.CompletedTask;

    public Task<IReadOnlyList<MediaItem>> GetContinueWatchingAsync(int limit, CancellationToken ct = default)
        => Task.FromResult<IReadOnlyList<MediaItem>>(Array.Empty<MediaItem>());

    public Task<long?> GetResumePositionAsync(string itemId, CancellationToken ct = default)
        => Task.FromResult<long?>(null);

    private async Task EnsureReachableAsync(string path, bool isFolder, CancellationToken ct)
    {
        (SmbFailure Failure, string Message) connected = await _share.EnsureAsync(ct).ConfigureAwait(false);
        if (connected.Failure != SmbFailure.None) throw Failure(connected.Failure, connected.Message);

        // Checked per call rather than once: a laptop that leaves the network, or a share that starts
        // demanding a password after a policy change, fails here with the right sentence instead of
        // surfacing an empty listing that looks like a deleted folder.
        (SmbFailure Failure, string Message) readable = isFolder
            ? await SmbConnectivity.ProbeDirectoryAsync(path, _share.HasCredentials, SmbProbe.Timeout, ct).ConfigureAwait(false)
            : await SmbConnectivity.ProbeFileAsync(path, _share.HasCredentials, SmbProbe.Timeout, ct).ConfigureAwait(false);
        if (readable.Failure != SmbFailure.None)
        {
            // "Access denied" means two different things. With the share root readable the identity is
            // fine and only this folder is closed to it, so the answer must be a permission rather than
            // "fill in a password" — a user holding a valid account cannot act on the latter.
            if (readable.Failure == SmbFailure.CredentialsRequired && !IsShareRoot(path))
            {
                (SmbFailure Failure, string Message) root = await SmbConnectivity
                    .ProbeDirectoryAsync(_address.UncRoot, _share.HasCredentials, SmbProbe.Timeout, ct)
                    .ConfigureAwait(false);
                if (root.Failure == SmbFailure.None)
                {
                    (SmbFailure Failure, string Message) denied = SmbErrors.PermissionDenied(path);
                    throw Failure(denied.Failure, denied.Message);
                }
            }
            throw Failure(readable.Failure, readable.Message);
        }
    }

    private bool IsShareRoot(string path)
        => string.Equals(path.TrimEnd('\\', '/'), _address.UncRoot.TrimEnd('\\', '/'), StringComparison.OrdinalIgnoreCase);

    private ProviderException Failure(SmbFailure failure, string message) => new(
        _serverId,
        failure switch
        {
            SmbFailure.Authentication => ProviderErrorCode.AuthFailed,
            SmbFailure.CredentialsRequired => ProviderErrorCode.AuthRequired,
            SmbFailure.NotFound => ProviderErrorCode.NotFound,
            SmbFailure.Network or SmbFailure.Timeout => ProviderErrorCode.Network,
            _ => ProviderErrorCode.Connection,
        },
        message);
}

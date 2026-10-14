using System.Collections.Concurrent;
using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.WebDav;

/// <summary>An href the provider refused, with the reason. Diagnostics only; never leaves the process.</summary>
internal sealed record WebDavRejectedHref(string Href, WebDavHrefRejection Rejection);

/// <summary>
/// Read-only WebDAV provider: Basic authentication, PROPFIND browsing, file detail, direct playback
/// and sibling-file subtitle discovery.
/// </summary>
/// <remarks>
/// <para>
/// Shaped after the repository's established provider rather than after a parallel design: like
/// Emby/Jellyfin it is one class exposing exactly the capability interfaces it genuinely implements,
/// and like the local provider it handles a bare file tree in which nothing is known beyond name,
/// size and content type. Metadata that a WebDAV server does not have (poster, rating, synopsis,
/// streams, resume position) is left absent instead of being fabricated — the Android client's
/// read-only WebDAV package declares exactly the same boundary and for the same reason.
/// </para>
/// <para>
/// Deliberate divergences from the Android implementation, each forced by the Windows media path:
/// </para>
/// <list type="bullet">
/// <item>
/// Android refuses to follow redirects at all. Here the redirect chain is owned by
/// <see cref="OriginScopedCredentialHandler"/> (the same component every other Windows provider uses),
/// which follows a hop but strips identity headers when the origin changes. The provider keeps its own
/// guard on top: a response whose final request URI left the source origin is refused rather than
/// parsed, so a cross-origin redirect cannot masquerade as an empty directory.
/// </item>
/// <item>
/// Android returns the <c>Authorization</c> header inside the playback source. That is exactly the
/// handing-credentials-to-the-engine pattern ADR-W006 recorded as a real leak on Windows, so identity
/// travels through <see cref="StreamRequestHeaders"/> plus <see cref="StreamHeaderOrigin"/> instead,
/// and the loopback bridge attaches it only for the source's own origin.
/// </item>
/// <item>
/// Seek support is probed (<c>Range</c> → 206 vs 200) because the Windows playback contract has no
/// "supports seeking" field to carry the Android flag. See <see cref="IWebDavSeekCapability"/>.
/// </item>
/// </list>
/// </remarks>
internal sealed class WebDavProvider :
    IProviderAuth,
    IProviderBrowse,
    IProviderDetail,
    IProviderPlayback,
    IProviderSubtitle,
    IWebDavSeekCapability,
    IDisposable
{
    /// <summary>Bounds the diagnostics list so a hostile directory cannot grow it without limit.</summary>
    private const int MaxRecordedRejections = 256;

    private readonly WebDavApi _api;
    private readonly string _serverId;
    private readonly Uri _sourceRoot;
    private readonly WebDavSession _session;
    private readonly Action? _onSessionExpired;
    private readonly MediaSourceHttpClientFactory? _clientOwner;
    private readonly ConcurrentDictionary<string, WebDavRangeSupport> _rangeByUrl = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, WebDavRangeSupport> _rangeByOrigin = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<WebDavRejectedHref> _rejected = new();
    private readonly object _rejectedGate = new();
    private bool _disposed;

    public WebDavProvider(
        WebDavApi api,
        string serverId,
        Uri sourceRoot,
        WebDavSession session,
        Action? onSessionExpired,
        MediaSourceHttpClientFactory? clientOwner = null)
    {
        _api = api;
        _serverId = serverId;
        _sourceRoot = sourceRoot;
        _session = session;
        _onSessionExpired = onSessionExpired;
        _clientOwner = clientOwner;
    }

    /// <summary>Hrefs refused during this handle's lifetime, newest last (diagnostics).</summary>
    internal IReadOnlyList<WebDavRejectedHref> LastRejectedHrefs
    {
        get { lock (_rejectedGate) return _rejected.ToList(); }
    }

    internal bool IsDisposed => _disposed;

    // ---------------------------------------------------------------- authentication

    public SessionMaterial CurrentSession => new(null, _session.UserName, _session.UserName);

    /// <summary>
    /// Verifies a credential pair against the server with an OPTIONS probe.
    /// </summary>
    /// <remarks>
    /// An empty user name means "anonymous": that is a legitimate WebDAV configuration (a public
    /// share), so it is probed rather than refused. A 401 here is reported as
    /// <see cref="ProviderErrorCode.AuthFailed"/>, not AuthExpired — telling someone who is literally
    /// typing their password that their session expired is a dead end, and it is the same correction
    /// the Emby path already carries. A failed attempt restores the previously working credentials
    /// instead of leaving the handle broken.
    /// </remarks>
    public async Task<AuthResult> AuthenticateAsync(Credentials credentials, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(credentials);

        if (WebDavBasicAuth.HasUnsafeCharacters(credentials.Username) || WebDavBasicAuth.HasUnsafeCharacters(credentials.Password))
        {
            throw ProviderException.AuthFailed(_serverId, "用户名或密码包含非法控制字符");
        }

        bool anonymous = string.IsNullOrWhiteSpace(credentials.Username);
        (string? previousUser, string? previousPassword) = _session.Snapshot();
        _session.Set(anonymous ? null : credentials.Username, credentials.Password);

        try
        {
            WebDavOptionsResponse probe = await _api.OptionsAsync(_sourceRoot, ct).ConfigureAwait(false);

            if (probe.StatusCode == 401)
            {
                throw WebDavAuthChallenge.BasicIsNotOffered(probe.AuthenticateSchemes)
                    ? ProviderException.AuthFailed(_serverId, WebDavAuthChallenge.DescribeUnsupported(probe.AuthenticateSchemes))
                    : ProviderException.AuthFailed(_serverId, anonymous ? "服务器需要认证" : "用户名或密码不正确");
            }

            if (probe.StatusCode is 405 or 501)
            {
                // OPTIONS is not mandatory; a PROPFIND is the protocol-level proof and exercises the
                // path the provider actually uses.
                await _api.PropfindAsync(_sourceRoot, 0, ct).ConfigureAwait(false);
            }
            else if (probe.StatusCode >= 400)
            {
                throw ProviderException.AuthFailed(_serverId, "服务器返回 HTTP " + probe.StatusCode.ToString(System.Globalization.CultureInfo.InvariantCulture));
            }

            return anonymous
                ? new AuthResult("anonymous", "匿名")
                : new AuthResult(credentials.Username!, credentials.Username!);
        }
        catch (ProviderException ex) when (ex.Code == ProviderErrorCode.AuthExpired)
        {
            _session.Set(previousUser, previousPassword);
            throw ProviderException.AuthFailed(_serverId, anonymous ? "服务器需要认证" : "用户名或密码不正确");
        }
        catch
        {
            _session.Set(previousUser, previousPassword);
            throw;
        }
    }

    /// <summary>
    /// Re-probes the stored credentials after a gap: the reconnect path.
    /// </summary>
    /// <remarks>
    /// An unreachable server keeps its credentials and is reported as
    /// <see cref="AuthSessionState.Unreachable"/> rather than as an authentication failure, so a NAS
    /// that is still booting does not force the user to re-enter a working password.
    /// </remarks>
    public async Task<AuthSessionState> RestoreSessionAsync(CancellationToken ct = default)
    {
        if (!_session.HasCredentials) return AuthSessionState.NotAuthenticated;

        try
        {
            WebDavOptionsResponse probe = await _api.OptionsAsync(_sourceRoot, ct).ConfigureAwait(false);
            if (probe.StatusCode is 405 or 501)
            {
                await _api.PropfindAsync(_sourceRoot, 0, ct).ConfigureAwait(false);
                return AuthSessionState.Authenticated;
            }

            return probe.StatusCode switch
            {
                401 => Expire(),
                // 403 means the credential was accepted and the permission was not: the session is
                // healthy, so this must not be reported as a login problem.
                403 => AuthSessionState.Authenticated,
                >= 200 and < 400 => AuthSessionState.Authenticated,
                // A 5xx is the server's problem, not the credential's.
                _ => AuthSessionState.Unreachable,
            };
        }
        catch (ProviderException ex) when (ex.Code == ProviderErrorCode.AuthExpired)
        {
            return Expire();
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.Network or ProviderErrorCode.Connection)
        {
            return AuthSessionState.Unreachable;
        }
    }

    public Task LogoutAsync(CancellationToken ct = default)
    {
        _session.Clear();
        return Task.CompletedTask;
    }

    private AuthSessionState Expire()
    {
        _onSessionExpired?.Invoke();
        return AuthSessionState.Expired;
    }

    // ---------------------------------------------------------------- browse

    /// <summary>
    /// Lists a collection with <c>PROPFIND Depth: 1</c>.
    /// </summary>
    /// <remarks>
    /// Paging is local, exactly as on Android: PROPFIND has no paging semantics, so slicing the result
    /// is honest while advertising server-side paging would not be. The collection's own entry is
    /// removed because RFC 4918 requires Depth:1 to include it.
    /// </remarks>
    public async Task<PagedResult<MediaItem>> ListFolderAsync(MediaItem? folder, PageRequest page, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(page);

        // The configured root is trusted as configured; a folder item's own URL is not, because it may
        // come from a cache or a database written by an earlier, possibly hostile, session.
        Uri collection = folder is null
            ? _sourceRoot
            : WebDavUrl.AsCollection(RequireItemUrl(folder.Path ?? folder.Id, "目录"));

        IReadOnlyList<WebDavResource> resources = await _api.PropfindAsync(collection, 1, ct).ConfigureAwait(false);
        string selfKey = WebDavUrl.CanonicalKey(collection);

        var items = new List<MediaItem>(resources.Count);
        foreach (WebDavResource resource in resources)
        {
            MediaItem? child = MapChild(resource, collection, selfKey);
            if (child is not null) items.Add(child);
        }

        var ordered = items
            .OrderByDescending(i => i.IsFolder)
            .ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        int offset = Math.Max(page.Offset, 0);
        int limit = Math.Max(page.Limit, 1);
        var window = ordered.Skip(offset).Take(limit).ToList();
        return new PagedResult<MediaItem>(window, offset, ordered.Count);
    }

    private MediaItem? MapChild(WebDavResource resource, Uri requestUri, string selfKey)
    {
        WebDavHrefResult resolved = WebDavUrl.Resolve(requestUri, resource.Href, _sourceRoot);
        if (!resolved.Ok)
        {
            Record(resource.Href, resolved.Rejection);
            return null;
        }

        Uri url = resolved.Url!;
        if (string.Equals(WebDavUrl.CanonicalKey(url), selfKey, StringComparison.Ordinal)) return null;

        string name = FirstNonEmpty(resource.DisplayName, WebDavUrl.DisplayNameOf(url.AbsolutePath));
        if (name.Length == 0) return null;

        bool collection = resource.IsCollection || url.AbsolutePath.EndsWith('/');
        return new MediaItem
        {
            ServerId = _serverId,
            Id = url.AbsoluteUri,
            Name = name,
            Type = WebDavMediaTypes.Classify(name, resource.ContentType, collection),
            Path = url.AbsoluteUri,
            FileSizeBytes = collection ? null : resource.ContentLength,
            IsFolder = collection,
        };
    }

    // ---------------------------------------------------------------- detail

    public async Task<MediaDetail> GetItemDetailAsync(string itemId, CancellationToken ct = default)
    {
        Uri target = RequireItemUrl(itemId, "文件");
        IReadOnlyList<WebDavResource> resources = await _api.PropfindAsync(target, 0, ct).ConfigureAwait(false);

        WebDavResource? self = null;
        foreach (WebDavResource resource in resources)
        {
            WebDavHrefResult resolved = WebDavUrl.Resolve(target, resource.Href, _sourceRoot);
            if (!resolved.Ok)
            {
                Record(resource.Href, resolved.Rejection);
                continue;
            }
            self = resource;
            break;
        }

        // A Depth:0 response that carries no usable entry is a damaged response, not an empty file.
        if (self is null) throw ProviderException.Parse(_serverId);

        if (self.IsCollection || target.AbsolutePath.EndsWith('/'))
        {
            throw ProviderException.NotFound(_serverId, "目录没有影片详情");
        }

        string name = FirstNonEmpty(self.DisplayName, WebDavUrl.DisplayNameOf(target.AbsolutePath));
        if (name.Length == 0) throw ProviderException.Parse(_serverId);

        return new MediaDetail
        {
            // Everything a bare file system does not have keeps its default. No poster tag, no rating,
            // no synopsis, no stream list: absent is the truth here, and inventing a value would
            // outlive this call in "continue watching" metadata.
            Item = new MediaItem
            {
                ServerId = _serverId,
                Id = target.AbsoluteUri,
                Name = name,
                Type = WebDavMediaTypes.Classify(name, self.ContentType, isCollection: false),
                Path = target.AbsoluteUri,
                FileSizeBytes = self.ContentLength,
            },
        };
    }

    // ---------------------------------------------------------------- playback

    public async Task<PlaybackSource> ResolvePlaybackAsync(MediaItem item, PlaybackOptions options, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(item);
        ArgumentNullException.ThrowIfNull(options);

        if (item.Type == MediaType.Folder || item.IsFolder)
        {
            throw ProviderException.NotYetImplemented(_serverId, "WebDAV 目录播放");
        }

        Uri target = RequireItemUrl(item.Path ?? item.Id, "媒体");

        // Detect before promising. A server that answers a range request with 200 cannot seek, and
        // handing the engine a resume position it will ignore produces a player that silently starts
        // from zero while the UI insists it resumed.
        WebDavRangeSupport support = await GetSeekSupportAsync(target, ct).ConfigureAwait(false);
        LastSeekSupport = support;

        return new PlaybackSource
        {
            Url = target,
            Mode = PlaybackMode.DirectPlay,
            Container = WebDavMediaTypes.ContainerOf(item.Name),
            // Identity is deliberately absent from this record. The Windows media path goes through
            // LoopbackMediaBridge, which attaches StreamRequestHeaders only when the stream URL is on
            // StreamHeaderOrigin; a credential here would instead travel to the playback engine, which
            // is the leak ADR-W006 documented.
            Headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase),
            StartPositionTicks = support == WebDavRangeSupport.Supported ? options.StartPositionTicks : null,
            SessionId = null,
        };
    }

    public IReadOnlyDictionary<string, string> StreamRequestHeaders
    {
        get
        {
            string? header = _session.AuthorizationHeader;
            return header is null
                ? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                : new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) { ["Authorization"] = header };
        }
    }

    public Uri? StreamHeaderOrigin => new(_sourceRoot.GetLeftPart(UriPartial.Authority) + "/");

    // ---------------------------------------------------------------- seek capability

    public WebDavRangeSupport LastSeekSupport { get; private set; } = WebDavRangeSupport.Unknown;

    public Task<WebDavRangeSupport> GetSeekSupportAsync(MediaItem item, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(item);
        return GetSeekSupportAsync(RequireItemUrl(item.Path ?? item.Id, "媒体"), ct);
    }

    /// <summary>
    /// Probes range support once per URL, and once per origin for the negative answer.
    /// </summary>
    /// <remarks>
    /// The per-origin negative cache matters: without it, a server that ignores Range costs one wasted
    /// request per file — and every one of those requests starts a full-entity transfer that has to be
    /// aborted. Support is cached per URL only, because a server may serve a container of files from a
    /// backend that behaves differently for different paths, and a positive answer is cheap to trust.
    /// </remarks>
    internal async Task<WebDavRangeSupport> GetSeekSupportAsync(Uri url, CancellationToken ct)
    {
        string key = url.AbsoluteUri;
        if (_rangeByUrl.TryGetValue(key, out WebDavRangeSupport cached)) return cached;

        string origin = WebDavUrl.OriginOf(url);
        if (_rangeByOrigin.TryGetValue(origin, out WebDavRangeSupport originCached)
            && originCached == WebDavRangeSupport.NotSupported)
        {
            _rangeByUrl[key] = WebDavRangeSupport.NotSupported;
            return WebDavRangeSupport.NotSupported;
        }

        WebDavRangeProbe probe = await _api.ProbeRangeAsync(url, ct).ConfigureAwait(false);
        _rangeByUrl[key] = probe.Support;
        if (probe.Support == WebDavRangeSupport.NotSupported) _rangeByOrigin[origin] = WebDavRangeSupport.NotSupported;
        return probe.Support;
    }

    // ---------------------------------------------------------------- subtitles

    /// <summary>
    /// External subtitles sitting next to the video, found with one Depth:1 PROPFIND of its collection.
    /// </summary>
    /// <remarks>
    /// Matches the Android discovery provider: same protocol path, same origin boundary, same
    /// "language from the file name suffix, unknown when unrecognised" rule. No online subtitle service
    /// is contacted and nothing is uploaded. A foreign-origin href is dropped before any request is
    /// made — the credentials must not follow a subtitle either.
    /// </remarks>
    public async Task<IReadOnlyList<SubtitleTrack>> GetSubtitlesAsync(string itemId, CancellationToken ct = default)
    {
        WebDavHrefResult video = WebDavUrl.Resolve(_sourceRoot, itemId, _sourceRoot);
        if (!video.Ok)
        {
            Record(itemId ?? string.Empty, video.Rejection);
            return Array.Empty<SubtitleTrack>();
        }

        Uri videoUrl = video.Url!;
        Uri folder = WebDavUrl.AsCollection(WebDavUrl.ParentCollection(videoUrl));
        IReadOnlyList<WebDavResource> resources = await _api.PropfindAsync(folder, 1, ct).ConfigureAwait(false);
        string videoKey = WebDavUrl.CanonicalKey(videoUrl);

        var candidates = new List<(string Name, string Extension, bool Forced, Uri Url)>();
        foreach (WebDavResource resource in resources)
        {
            WebDavHrefResult resolved = WebDavUrl.Resolve(folder, resource.Href, _sourceRoot);
            if (!resolved.Ok)
            {
                Record(resource.Href, resolved.Rejection);
                continue;
            }

            Uri url = resolved.Url!;
            if (string.Equals(WebDavUrl.CanonicalKey(url), videoKey, StringComparison.Ordinal)) continue;
            if (resource.IsCollection || url.AbsolutePath.EndsWith('/')) continue;

            string name = FirstNonEmpty(resource.DisplayName, WebDavUrl.DisplayNameOf(url.AbsolutePath));
            if (!WebDavMediaTypes.IsSubtitleExtension(name)) continue;

            candidates.Add((
                name,
                WebDavMediaTypes.ExtensionOf(name),
                name.Contains("forced", StringComparison.OrdinalIgnoreCase),
                url));
        }

        return candidates
            .OrderBy(c => c.Name, StringComparer.OrdinalIgnoreCase)
            .ThenBy(c => c.Extension, StringComparer.Ordinal)
            .Select((c, index) => new SubtitleTrack(
                Index: index,
                Language: WebDavMediaTypes.LanguageFromFileName(c.Name),
                Title: c.Name[..^c.Extension.Length],
                Codec: c.Extension.Length > 1 ? c.Extension[1..] : null,
                IsExternal: true,
                IsDefault: index == 0,
                IsForced: c.Forced,
                DeliveryUrl: c.Url.AbsoluteUri))
            .ToList();
    }

    // ---------------------------------------------------------------- disposal

    /// <summary>
    /// Releases the HTTP clients created for this handle.
    /// </summary>
    /// <remarks>
    /// Kept here as well as on the factory because <c>ProviderHandle.Own</c> is internal to
    /// MediaHub.Providers, so this provider cannot register itself for disposal the way the Emby
    /// builder does. Disposing the provider (or its factory) is therefore the only route available to
    /// this assembly today; see the handover note in the task report.
    /// </remarks>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _clientOwner?.Dispose();
    }

    // ---------------------------------------------------------------- helpers

    /// <summary>Resolves a reference that must already belong to this source, or refuses it loudly.</summary>
    private Uri RequireItemUrl(string? reference, string what)
    {
        WebDavHrefResult result = WebDavUrl.Resolve(_sourceRoot, reference, _sourceRoot);
        if (result.Ok) return result.Url!;

        Record(reference ?? string.Empty, result.Rejection);
        throw ProviderException.NotFound(_serverId, what + "地址无效（" + WebDavUrl.Describe(result.Rejection) + "）");
    }

    private static string FirstNonEmpty(string? preferred, string fallback)
        => preferred is { Length: > 0 } ? preferred : fallback;

    private void Record(string href, WebDavHrefRejection rejection)
    {
        lock (_rejectedGate)
        {
            if (_rejected.Count >= MaxRecordedRejections) return;
            _rejected.Add(new WebDavRejectedHref(href, rejection));
        }
    }
}

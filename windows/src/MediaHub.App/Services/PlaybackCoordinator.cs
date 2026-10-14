using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;

namespace MediaHub.App.Services;

/// <summary>A playback attempt that has been resolved and is ready to hand to the engine.</summary>
public sealed record ActivePlayback
{
    /// <summary>URL handed to the engine. For server sources this is a loopback bridge address.</summary>
    public required Uri EngineUrl { get; init; }

    /// <summary>Credential-free URL for display.</summary>
    public required string DisplayUrl { get; init; }

    public MediaItem? Item { get; init; }
    public PlaybackSource? Source { get; init; }
    public LoopbackMediaBridge? Bridge { get; init; }
    public string? ServerId { get; init; }
    public long StartPositionTicks { get; init; }
    public TimeSpan? DeclaredDuration { get; init; }
    public IReadOnlyList<string> ExternalSubtitles { get; init; } = Array.Empty<string>();

    public async ValueTask DisposeAsync()
    {
        if (Bridge is not null) await Bridge.DisposeAsync().ConfigureAwait(false);
    }
}

/// <summary>
/// Turns a UI request into something the engine can play, and keeps server progress in step.
/// </summary>
/// <remarks>
/// <para>
/// This is the single place where a resolved <see cref="PlaybackSource"/> becomes an engine URL. The
/// important decision it encodes is that a server-backed stream is never handed to the engine with the
/// server's credentials attached: the stream is relayed through a
/// <see cref="LoopbackMediaBridge"/>, which fetches it with the same HTTP stack, proxy policy and
/// origin-scoped redirect handling as every other request to that source.
/// </para>
/// <para>
/// Progress is reported on the tiers established for the Android client: a local snapshot every five
/// seconds, remote reports at the provider's own interval, and an immediate flush on pause, seek and
/// exit. Reporting is best effort and never blocks playback or its teardown.
/// </para>
/// </remarks>
public sealed class PlaybackCoordinator : IDisposable
{
    private readonly ServerManager _servers;
    private readonly LogService _log;
    private readonly PlaybackHistoryStore _localHistory;

    private CancellationTokenSource? _session;
    private DateTimeOffset _lastRemoteReport = DateTimeOffset.MinValue;
    private PlaybackProgress? _lastProgress;

    public PlaybackCoordinator(ServerManager servers, LogService log, PlaybackHistoryStore localHistory)
    {
        _servers = servers;
        _log = log;
        _localHistory = localHistory;
    }

    /// <summary>Resolves a request into something the engine can open.</summary>
    public async Task<ActivePlayback> PrepareAsync(PlaybackRequest request, CancellationToken ct = default)
    {
        if (!string.IsNullOrEmpty(request.LocalPath))
        {
            string path = request.LocalPath!;
            if (!File.Exists(path)) throw new FileNotFoundException("媒体文件不存在", path);
            return new ActivePlayback
            {
                EngineUrl = new Uri(path),
                DisplayUrl = path,
                StartPositionTicks = request.StartPositionTicks,
                DeclaredDuration = request.DeclaredDuration,
                ExternalSubtitles = request.ExternalSubtitles,
            };
        }

        if (string.IsNullOrEmpty(request.ServerId) || string.IsNullOrEmpty(request.ItemId))
            throw new InvalidOperationException("播放请求缺少媒体源或条目标识");

        ProviderHandle handle = _servers.FindHandle(request.ServerId!)
            ?? throw new InvalidOperationException("该媒体源当前不可用");
        if (handle.Playback is null)
            throw ProviderException.NotYetImplemented(handle.ServerId, "播放");

        NetworkPolicy policy = NetworkPolicy.FollowSystem;
        var mediaItem = new MediaItem
        {
            ServerId = handle.ServerId,
            Id = request.ItemId!,
            Name = request.Title,
        };

        long? resumeTicks = request.StartPositionTicks > 0 ? request.StartPositionTicks : null;
        if (resumeTicks is null && handle.Progress is not null)
        {
            try { resumeTicks = await handle.Progress.GetResumePositionAsync(request.ItemId!, ct).ConfigureAwait(false); }
            catch (ProviderException) { /* resume is a convenience, never a blocker */ }
        }

        PlaybackSource source = await handle.Playback
            .ResolvePlaybackAsync(mediaItem, new PlaybackOptions { StartPositionTicks = resumeTicks }, ct)
            .ConfigureAwait(false);

        string displayUrl = UrlRedactor.Redact(source.Url);

        // The stream endpoint authenticates like the API does, so the session headers have to be
        // attached — but only when the stream really is served by this source's own origin. A server
        // is free to hand back a URL on object storage or a CDN, and attaching the session there would
        // reproduce exactly the credential leak the relay exists to prevent.
        var streamHeaders = new Dictionary<string, string>(source.Headers, StringComparer.OrdinalIgnoreCase);
        IReadOnlyDictionary<string, string> sessionHeaders = handle.Playback.StreamRequestHeaders;
        if (sessionHeaders.Count > 0)
        {
            Uri? entitledOrigin = handle.Playback.StreamHeaderOrigin;
            bool entitled = entitledOrigin is null
                || string.Equals(UrlRedactor.OriginOf(source.Url), UrlRedactor.OriginOf(entitledOrigin),
                    StringComparison.OrdinalIgnoreCase);

            if (entitled)
            {
                foreach (var kv in sessionHeaders) streamHeaders[kv.Key] = kv.Value;
            }
            else
            {
                _log.Warn("playback", "媒体流指向其它来源（" + UrlRedactor.OriginOf(source.Url) + "），未附加会话凭据");
            }
        }

        // Server credentials stay here. The engine only ever sees a loopback address with no identity
        // attached, so it cannot forward a token to a redirect target.
        LoopbackMediaBridge bridge = await LoopbackMediaBridge
            .StartAsync(new BridgeTarget(source.Url, streamHeaders, policy), ct)
            .ConfigureAwait(false);

        _log.Info("playback", "已为媒体流建立本地转发会话（引擎不持有服务器凭据）");

        return new ActivePlayback
        {
            EngineUrl = bridge.BridgeUri,
            DisplayUrl = displayUrl,
            Item = mediaItem,
            Source = source,
            Bridge = bridge,
            ServerId = handle.ServerId,
            StartPositionTicks = resumeTicks ?? 0,
            DeclaredDuration = source.RunTimeTicks is { } ticks ? TimeSpan.FromTicks(ticks) : request.DeclaredDuration,
            ExternalSubtitles = request.ExternalSubtitles,
        };
    }

    // ---------------------------------------------------------------- progress

    /// <summary>Called by the player page on its sampling tick.</summary>
    public async Task OnProgressAsync(ActivePlayback playback, PlayerProgressSample sample, bool final)
    {
        if (playback.ServerId is null || playback.Item is null) return;

        _session ??= new CancellationTokenSource();
        var progress = new PlaybackProgress
        {
            ServerId = playback.ServerId,
            ItemId = playback.Item.Id,
            PositionTicks = sample.PositionTicks,
            DurationTicks = sample.DurationTicks,
            IsPaused = sample.IsPaused,
            IsMuted = sample.IsMuted,
            SessionId = playback.Source?.SessionId,
            Mode = playback.Source?.Mode ?? PlaybackMode.DirectPlay,
        };
        _lastProgress = progress;

        ProviderHandle? handle = _servers.FindHandle(playback.ServerId);
        if (handle?.Progress is null) return;

        if (final)
        {
            try
            {
                await handle.Progress.ReportFinalProgressAsync(progress, _session.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { }
            catch (ProviderException ex)
            {
                // Losing the final sample must not throw into the player's exit path.
                _log.Debug("playback", "最终进度上报失败：" + ex.Code);
            }
            _lastRemoteReport = DateTimeOffset.MinValue;
            return;
        }

        TimeSpan interval = handle.Progress.RemoteReportInterval;
        if (DateTimeOffset.UtcNow - _lastRemoteReport < interval) return;

        _lastRemoteReport = DateTimeOffset.UtcNow;
        try
        {
            await handle.Progress.ReportProgressAsync(progress, _session.Token).ConfigureAwait(false);
            _localHistory.Record(playback.Item.Id + "@" + playback.ServerId, playback.Item.Name,
                sample.PositionTicks, sample.DurationTicks);
        }
        catch (OperationCanceledException) { }
        catch (ProviderException ex)
        {
            _log.Debug("playback", "进度上报失败：" + ex.Code);
        }
    }

    /// <summary>Stops reporting. Called when the player page is left.</summary>
    public void EndSession()
    {
        try { _session?.Cancel(); } catch { }
        _session?.Dispose();
        _session = null;
        _lastRemoteReport = DateTimeOffset.MinValue;
        _lastProgress = null;
    }

    public void Dispose() => EndSession();
}

/// <summary>Transport sample handed to the progress tiers.</summary>
public sealed record PlayerProgressSample(long PositionTicks, long DurationTicks, bool IsPaused, bool IsMuted);

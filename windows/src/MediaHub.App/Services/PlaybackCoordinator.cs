using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;

namespace MediaHub.App.Services;

/// <summary>Why a playback session ended. Kept as constants so the stop report and the log agree.</summary>
public static class PlaybackStopReasons
{
    public const string UserExited = "user-exited";
    public const string Replaced = "replaced";
    public const string Superseded = "superseded";
    public const string Completed = "completed";
    public const string Shutdown = "shutdown";
}

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
/// One user-visible playback attempt, with the lifecycle the UI and the server both observe.
/// </summary>
/// <remarks>
/// The session is the unit of attribution. Everything asynchronous about playback — resolving a
/// stream, opening the relay, reporting progress, advancing to the next episode — belongs to a
/// session, and a session that has been superseded may not write anything. That is what stops a slow
/// request for the previous film from landing on the item now playing.
/// </remarks>
public sealed class PlaybackSession
{
    public PlaybackSession(long generation, PlaybackRequest request)
    {
        Generation = generation;
        Request = request;
        Title = request.Title;
    }

    public long Generation { get; }
    public PlaybackRequest Request { get; }

    public string Title { get; private set; }
    public PlaybackStage Stage { get; private set; } = PlaybackStage.Idle;
    public string? Error { get; private set; }

    /// <summary>Resolved media. Null until <see cref="PlaybackStage.Preparing"/> completes.</summary>
    public ActivePlayback? Active { get; internal set; }

    /// <summary>True once the server has been told playback started.</summary>
    public bool StartReported { get; internal set; }

    /// <summary>Why the session ended, for the stop report and the log.</summary>
    public string? StopReason { get; internal set; }

    internal DateTimeOffset? LastReportedAt { get; set; }

    /// <summary>Last position the UI sampled, used for the stop report.</summary>
    internal long LastPositionTicks { get; set; }

    internal long LastDurationTicks { get; set; }

    /// <summary>Raised whenever the stage or the error changes.</summary>
    public event Action<PlaybackSession>? Changed;

    /// <summary>
    /// Moves the session to a new stage, refusing illegal transitions.
    /// </summary>
    /// <remarks>
    /// A refused transition is not an error to surface: it means a late event arrived after the
    /// session had already finished. It returns false so the caller can decide, rather than throwing
    /// inside an event handler where there is nothing sensible to do with the exception.
    /// </remarks>
    public bool TryAdvance(PlaybackStage next, string? error = null)
    {
        lock (this)
        {
            if (!PlaybackStages.CanTransition(Stage, next)) return false;
            if (Stage == next && error == Error) return true;
            Stage = next;
            Error = error ?? (next == PlaybackStage.Error ? Error : null);
        }
        Changed?.Invoke(this);
        return true;
    }

    internal void Retitle(string title)
    {
        Title = title;
        Changed?.Invoke(this);
    }

    public bool IsFinished => PlaybackStages.IsTerminal(Stage);
}

/// <summary>
/// Turns a UI request into something the engine can play, and keeps server progress in step.
/// </summary>
/// <remarks>
/// <para>
/// This is the single place where a resolved <see cref="PlaybackSource"/> becomes an engine URL. The
/// decision it encodes is that a server-backed stream is never handed to the engine with the server's
/// credentials attached: the stream is relayed through a <see cref="LoopbackMediaBridge"/>, which
/// fetches it with the same HTTP stack, proxy policy and origin-scoped redirect handling as every
/// other request to that source.
/// </para>
/// <para>
/// Exactly one session is foreground at a time. Starting a new one invalidates the previous
/// generation, so a late resolution or a retried report from the old session is discarded instead of
/// overwriting the new one's state.
/// </para>
/// </remarks>
public sealed class PlaybackCoordinator : IAsyncDisposable
{
    private readonly ServerManager _servers;
    private readonly LogService _log;
    private readonly PlaybackHistoryStore _localHistory;
    private readonly PlaybackSessionPolicy _policy = new(TimeSpan.FromSeconds(10));
    private readonly SemaphoreSlim _lifecycle = new(1, 1);

    /// <summary>The foreground session, or null when nothing is playing.</summary>
    public PlaybackSession? Current { get; private set; }

    /// <summary>Raised when the foreground session changes, including to null.</summary>
    public event Action<PlaybackSession?>? CurrentChanged;

    public PlaybackCoordinator(ServerManager servers, LogService log, PlaybackHistoryStore localHistory)
    {
        _servers = servers;
        _log = log;
        _localHistory = localHistory;
    }

    /// <summary>True when the session still owns the foreground.</summary>
    public bool IsCurrent(PlaybackSession session)
        => ReferenceEquals(Current, session) && _policy.IsCurrent(session.Generation);

    // ---------------------------------------------------------------- lifecycle

    /// <summary>
    /// Starts a new foreground session, superseding and tearing down any previous one.
    /// </summary>
    public async Task<PlaybackSession> PrepareAsync(PlaybackRequest request, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(request);

        await _lifecycle.WaitAsync(ct).ConfigureAwait(false);
        PlaybackSession session;
        try
        {
            // Releasing the previous session happens before the new generation is taken, so its stop
            // report is still attributed to it and cannot be mistaken for the new session's.
            await ReleaseCurrentAsync("superseded").ConfigureAwait(false);

            session = new PlaybackSession(_policy.Begin(), request);
            Current = session;
        }
        finally
        {
            _lifecycle.Release();
        }

        CurrentChanged?.Invoke(session);
        session.TryAdvance(PlaybackStage.Preparing);

        try
        {
            ActivePlayback active = await ResolveAsync(request, ct).ConfigureAwait(false);
            if (!IsCurrent(session))
            {
                // The user moved on while this was resolving. The relay that was just created must be
                // torn down here, or it would outlive the session that owns it.
                await active.DisposeAsync().ConfigureAwait(false);
                throw new OperationCanceledException("播放会话已被新的请求取代");
            }
            session.Active = active;
            session.TryAdvance(PlaybackStage.Buffering);
        }
        catch (OperationCanceledException)
        {
            session.TryAdvance(PlaybackStage.Ended, "cancelled");
            throw;
        }
        catch (Exception ex)
        {
            session.TryAdvance(PlaybackStage.Error, ex.Message);
            _log.Error("playback", "播放准备失败", ex);
            throw;
        }

        return session;
    }

    /// <summary>Ends the foreground session, reporting a stop if it had started.</summary>
    public async Task StopAsync(string reason)
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try { await ReleaseCurrentAsync(reason).ConfigureAwait(false); }
        finally { _lifecycle.Release(); }
        CurrentChanged?.Invoke(null);
    }

    private async Task ReleaseCurrentAsync(string reason)
    {
        PlaybackSession? session = Current;
        if (session is null) return;
        Current = null;

        // The generation is left alone here: the session being released is still the current one for
        // the duration of its own stop report, otherwise the report would be discarded by its own
        // session's guard.
        if (session.StartReported && session.Active is { } active)
        {
            session.StopReason = reason;
            var progress = new PlaybackProgress
            {
                ServerId = active.ServerId ?? "",
                ItemId = active.Item?.Id ?? "",
                PositionTicks = session.LastPositionTicks,
                DurationTicks = session.LastDurationTicks,
                IsPaused = session.Stage == PlaybackStage.Paused,
                IsMuted = false,
                SessionId = active.Source?.SessionId,
                Mode = active.Source?.Mode ?? PlaybackMode.DirectPlay,
            };
            await ReportAsync(session, p => p.ReportPlaybackStoppedCompatAsync(progress), "stop").ConfigureAwait(false);
        }

        if (session.Active is { } resolved) await resolved.DisposeAsync().ConfigureAwait(false);
        session.Active = null;
        session.TryAdvance(PlaybackStage.Ended, reason);

        // The session is no longer reachable; invalidate its generation so anything still in flight
        // from it is dropped even if a new session has not started yet.
        _policy.Begin();
    }

    // ---------------------------------------------------------------- resolution

    private async Task<ActivePlayback> ResolveAsync(PlaybackRequest request, CancellationToken ct)
    {
        if (!string.IsNullOrEmpty(request.LocalPath))
        {
            string path = request.LocalPath!;
            if (!File.Exists(path)) throw new FileNotFoundException("媒体文件不存在", path);
            return new ActivePlayback
            {
                EngineUrl = new Uri(path),
                DisplayUrl = string.IsNullOrEmpty(request.DisplayUrl) ? path : request.DisplayUrl,
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
            SeriesId = request.SeriesId,
            SeasonNumber = request.SeasonNumber,
            EpisodeNumber = request.EpisodeNumber,
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

    /// <summary>Reports that playback has begun. Sent once per session.</summary>
    public async Task NotifyStartedAsync(PlaybackSession session, PlayerProgressSample sample)
    {
        if (session.StartReported || session.Active is not { } active) return;
        if (!IsCurrent(session)) return;

        ProviderHandle? handle = active.ServerId is null ? null : _servers.FindHandle(active.ServerId);
        if (handle?.Progress is null) return;

        session.StartReported = true;
        PlaybackProgress progress = BuildProgress(session, sample);
        await ReportAsync(session, p => p.ReportPlaybackStartedAsync(progress), "start").ConfigureAwait(false);
    }

    /// <summary>Called by the player page on its sampling tick, and on lifecycle events.</summary>
    /// <param name="force">True for pause, seek and exit, which are never throttled.</param>
    public async Task OnProgressAsync(PlaybackSession session, PlayerProgressSample sample, bool force)
    {
        if (session.Active is not { } active || active.ServerId is null) return;
        if (!IsCurrent(session)) return;

        session.LastPositionTicks = sample.PositionTicks;
        session.LastDurationTicks = sample.DurationTicks;

        ProviderHandle? handle = _servers.FindHandle(active.ServerId);
        if (handle?.Progress is null) return;

        // A local record is kept for this client's own "recently played" list; it is not a substitute
        // for the server's resume state and is not read back from the server.
        if (sample.DurationTicks > 0)
        {
            _localHistory.Record(active.Item!.Id + "@" + active.ServerId, active.Item.Name,
                sample.PositionTicks, sample.DurationTicks);
        }

        if (!_policy.ShouldReport(DateTimeOffset.UtcNow, session.LastReportedAt, force)) return;

        PlaybackProgress progress = BuildProgress(session, sample);
        bool reported = await ReportAsync(session, p => p.ReportProgressAsync(progress), "progress").ConfigureAwait(false);
        if (reported) session.LastReportedAt = DateTimeOffset.UtcNow;
    }

    private PlaybackProgress BuildProgress(PlaybackSession session, PlayerProgressSample sample)
    {
        ActivePlayback active = session.Active!;
        return new PlaybackProgress
        {
            ServerId = active.ServerId ?? "",
            ItemId = active.Item?.Id ?? "",
            PositionTicks = sample.PositionTicks,
            DurationTicks = sample.DurationTicks,
            IsPaused = sample.IsPaused,
            IsMuted = sample.IsMuted,
            SessionId = active.Source?.SessionId,
            Mode = active.Source?.Mode ?? PlaybackMode.DirectPlay,
        };
    }

    /// <summary>
    /// Sends one report with the generation guard and bounded retry applied.
    /// </summary>
    /// <remarks>
    /// Deliberately swallows failures. Reporting is best effort by contract: an unreachable server
    /// must not interrupt playback, and must not throw into the player's exit path either.
    /// </remarks>
    private async Task<bool> ReportAsync(PlaybackSession session, Func<IProviderProgress, Task> send, string phase)
    {
        ActivePlayback? active = session.Active;
        ProviderHandle? handle = active?.ServerId is null ? null : _servers.FindHandle(active!.ServerId!);
        if (handle?.Progress is null) return false;

        bool ok = await _policy.ExecuteAsync(session.Generation, _ => send(handle.Progress)).ConfigureAwait(false);
        if (!ok && IsCurrent(session))
        {
            _log.Debug("playback", "进度上报未完成（" + phase + "）");
        }
        return ok;
    }

    /// <summary>Compatibility helper for callers that only have the active playback.</summary>
    public void EndSession() => _ = StopAsync("ended");

    public async ValueTask DisposeAsync()
    {
        await StopAsync("shutdown").ConfigureAwait(false);
        _lifecycle.Dispose();
    }

    public void Dispose() => DisposeAsync().AsTask().GetAwaiter().GetResult();
}

internal static class ProgressCompat
{
    /// <summary>
    /// Named wrapper so the stop call site reads as what it is. The interface member is
    /// ReportFinalProgressAsync because it must be reported exactly once; this is just an alias that
    /// makes the intent legible where it is called.
    /// </summary>
    public static Task ReportPlaybackStoppedCompatAsync(this IProviderProgress progress, PlaybackProgress value)
        => progress.ReportFinalProgressAsync(value);
}

/// <summary>Transport sample handed to the progress tiers.</summary>
public sealed record PlayerProgressSample(long PositionTicks, long DurationTicks, bool IsPaused, bool IsMuted);

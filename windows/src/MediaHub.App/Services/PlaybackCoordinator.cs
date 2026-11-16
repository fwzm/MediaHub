using System.Globalization;
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
    public const string Failed = "failed";
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

    /// <summary>Sidecar subtitles downloaded from the source; added without stealing the selection.</summary>
    public IReadOnlyList<string> ServerSubtitles { get; init; } = Array.Empty<string>();

    /// <summary>Temporary directory holding <see cref="ServerSubtitles"/>, removed when the session ends.</summary>
    public string? SubtitleDirectory { get; init; }

    public async ValueTask DisposeAsync()
    {
        if (Bridge is not null) await Bridge.DisposeAsync().ConfigureAwait(false);
        if (SubtitleDirectory is not null)
        {
            try { Directory.Delete(SubtitleDirectory, recursive: true); }
            catch (Exception) { /* the engine may still hold a handle; the OS temp cleanup is the fallback */ }
        }
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
    private readonly SettingsStore _settings;
    private readonly PlaybackSessionPolicy _policy = new(TimeSpan.FromSeconds(10));
    private readonly SemaphoreSlim _lifecycle = new(1, 1);

    /// <summary>The foreground session, or null when nothing is playing.</summary>
    public PlaybackSession? Current { get; private set; }

    /// <summary>Raised when the foreground session changes, including to null.</summary>
    public event Action<PlaybackSession?>? CurrentChanged;

    public PlaybackCoordinator(ServerManager servers, LogService log, PlaybackHistoryStore localHistory,
        SettingsStore settings)
    {
        _servers = servers;
        _log = log;
        _localHistory = localHistory;
        _settings = settings;
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
        StartupTrace.Write("playback:prepare-start item=" + request.ItemId + " server=" + request.ServerId);

        try
        {
            ActivePlayback active = await ResolveAsync(request, ct).ConfigureAwait(false);
            StartupTrace.Write("playback:resolved url=" + active.DisplayUrl
                                              + " mode=" + (active.Source?.Mode.ToString() ?? "Local"));
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
            // Written to the persistent startup trace as well as the in-memory log: a playback failure
            // that only exists in memory is invisible after the fact, which is exactly when it matters.
            StartupTrace.Write("playback:prepare-failed " + ex.GetType().Name + ": " + ex.Message);
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

    /// <summary>
    /// Resolves the next episode without touching the foreground session. The caller owns the result:
    /// it must either hand it to <see cref="AdoptPreparedAsync"/> or dispose it.
    /// </summary>
    public Task<ActivePlayback> ResolveForPreloadAsync(PlaybackRequest request, CancellationToken ct)
        => ResolveAsync(request, ct);

    /// <summary>
    /// Makes an already-resolved playback the foreground session. Used for the prefetched next
    /// episode: the stream is already open on the engine playlist, so no resolve happens here.
    /// </summary>
    public async Task<PlaybackSession> AdoptPreparedAsync(PlaybackRequest request, ActivePlayback prepared)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(prepared);

        await _lifecycle.WaitAsync().ConfigureAwait(false);
        PlaybackSession session;
        try
        {
            await ReleaseCurrentAsync("superseded").ConfigureAwait(false);
            session = new PlaybackSession(_policy.Begin(), request) { Active = prepared };
            Current = session;
        }
        finally
        {
            _lifecycle.Release();
        }

        CurrentChanged?.Invoke(session);
        session.TryAdvance(PlaybackStage.Preparing);
        session.TryAdvance(PlaybackStage.Buffering);
        StartupTrace.Write("playback:preload-adopted item=" + request.ItemId);
        return session;
    }

    private async Task ReleaseCurrentAsync(string reason)
    {
        PlaybackSession? session = Current;
        if (session is null) return;
        Current = null;

        // The generation is left alone here: the session being released is still the current one for
        // the duration of its own stop report, otherwise the report would be discarded by its own
        // session's guard.
        // A transcoded session owns a server-side PlaySessionId — and possibly a running encoder —
        // from the moment the stream was resolved, so it must be released even when the first
        // progress tick never arrived: exiting during buffering would otherwise leave the server
        // transcoding with nobody reading it.
        bool serverOwnsStream = session.Active?.Source?.Mode == PlaybackMode.Transcode;
        if ((session.StartReported || serverOwnsStream) && session.Active is { } active)
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

        if (serverOwnsStream && session.Active?.Bridge is { } bridge)
        {
            // The relay is the only component that knows how much of the transcode was actually
            // consumed; without this line, "the server was asked to transcode" and "the engine played
            // the transcode" look identical in the log.
            _log.Info("playback", "转码会话结束（" + reason + "）：桥请求 " + bridge.RequestCount
                + " 次 / " + bridge.BytesServed + " 字节");
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

        // The relay fetches the stream itself, so it must trust exactly what this source's own requests
        // trust. Falling back to the system policy here would let a pinned server be browsed but never
        // played: the API handshake succeeds and the stream handshake fails.
        NetworkPolicy policy = _servers.Find(handle.ServerId) is { } entry
            ? ServerManager.PolicyFor(entry)
            : NetworkPolicy.FollowSystem;
        var mediaItem = new MediaItem
        {
            ServerId = handle.ServerId,
            Id = request.ItemId!,
            Name = request.Title,
            SeriesId = request.SeriesId,
            SeasonNumber = request.SeasonNumber,
            EpisodeNumber = request.EpisodeNumber,
        };

        long? resumeTicks = null;
        if (!request.StartFromBeginning)
        {
            resumeTicks = request.StartPositionTicks > 0 ? request.StartPositionTicks : null;
            if (resumeTicks is null && handle.Progress is not null)
            {
                try
                {
                    resumeTicks = await handle.Progress.GetResumePositionAsync(request.ItemId!, ct).ConfigureAwait(false);
                    StartupTrace.Write("playback:resume=" + (resumeTicks ?? 0));
                }
                catch (ProviderException ex)
                {
                    // Resume is a convenience, never a blocker - but it must be visible when it fails,
                    // otherwise a source that cannot report resume looks like one that hangs.
                    StartupTrace.Write("playback:resume-failed " + ex.Code);
                }
            }

            // A static file server, a file share and OneDrive all have nowhere to keep a play position,
            // so the snapshot this coordinator records on every tick is the only resume source. Without
            // this fallback, a direct link replayed from 0:00 while the home page plainly showed the
            // saved position.
            if (resumeTicks is null && _servers.Find(handle.ServerId)?.Type is ServerType.Http or ServerType.Smb or ServerType.OneDrive)
            {
                long? recorded = _localHistory
                    .Find(request.ItemId + "@" + handle.ServerId)?.ResumePositionTicks;
                if (recorded is > 0)
                {
                    resumeTicks = recorded;
                    StartupTrace.Write("playback:resume=local:" + recorded);
                }
            }
        }

        PlaybackSource source = await handle.Playback
            .ResolvePlaybackAsync(mediaItem, new PlaybackOptions
            {
                StartPositionTicks = resumeTicks,
                ForceTranscode = request.ForceTranscode,
                MediaSourceId = request.MediaSourceId,
                QualityPreference = _settings.Current.QualityPreference,
            }, ct)
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

        // Sidecar subtitles are fetched here, never by the engine: the fetch needs the session token,
        // and the engine must not hold one. Failure is logged and skipped — no subtitle is not a
        // reason to refuse to play the film.
        ServerSubtitleFetch subtitles = await FetchServerSubtitlesAsync(handle, mediaItem, source, ct).ConfigureAwait(false);

        // A source that resolves to a local file needs neither credentials nor a relay, and it must
        // not be relayed: the bridge fetches its target with HttpClient, which has no file: handler,
        // so wrapping a file URI makes the engine report "loading failed" for a perfectly playable
        // file (measured on both engine flavours - see LICENCE-DECISION.md §9.6). Hand the file URI
        // to the engine unchanged, which is the shape the 文件 -> 打开视频 path already uses.
        if (source.Url.IsFile)
        {
            return new ActivePlayback
            {
                EngineUrl = source.Url,
                DisplayUrl = displayUrl,
                Item = mediaItem,
                Source = source,
                ServerId = handle.ServerId,
                StartPositionTicks = resumeTicks ?? 0,
                DeclaredDuration = source.RunTimeTicks is { } localTicks ? TimeSpan.FromTicks(localTicks) : request.DeclaredDuration,
                ExternalSubtitles = request.ExternalSubtitles,
                ServerSubtitles = subtitles.Files,
                SubtitleDirectory = subtitles.Directory,
            };
        }

        // Server credentials stay here. The engine only ever sees a loopback address with no identity
        // attached, so it cannot forward a token to a redirect target.
        LoopbackMediaBridge bridge;
        try
        {
            bridge = await LoopbackMediaBridge
                .StartAsync(new BridgeTarget(source.Url, streamHeaders, policy), ct)
                .ConfigureAwait(false);
        }
        catch
        {
            // No playback will own the downloaded files, so do not leave them in temp.
            if (subtitles.Directory is not null) TryDeleteDirectory(subtitles.Directory);
            throw;
        }

        _log.Info("playback", "已为媒体流建立本地转发会话（引擎不持有服务器凭据）");

        // Measured on Jellyfin 12.2: an HLS transcode does NOT start at StartTimeTicks. The master
        // playlist lists the item's whole timeline from segment 0, the response URL carries no
        // StartTimeTicks parameter, and requesting segment N is what makes the server run ffmpeg with
        // "-ss N*6s". So the engine, not the server, applies the resume position - in every mode.
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
            ServerSubtitles = subtitles.Files,
            SubtitleDirectory = subtitles.Directory,
        };
    }

    private static readonly TimeSpan ServerSubtitleBudget = TimeSpan.FromSeconds(4);
    private const int MaxServerSubtitleTracks = 8;

    private sealed record ServerSubtitleFetch(IReadOnlyList<string> Files, string? Directory);

    /// <summary>
    /// Materialises the source's sidecar subtitles as local files the engine can open.
    /// </summary>
    /// <remarks>
    /// Bounded and best effort by design: a slow or partly broken server must delay the start of the
    /// film by at most <see cref="ServerSubtitleBudget"/>, and a failed track is dropped rather than
    /// surfaced as a playback error. Only external tracks are downloaded — embedded ones are already
    /// in the container the engine opens, and asking the server to extract them would duplicate them.
    /// The stream list is taken from the resolved source, not from a second item fetch, so a
    /// multi-version item cannot download the subtitles of the version it is not playing.
    /// </remarks>
    private async Task<ServerSubtitleFetch> FetchServerSubtitlesAsync(
        ProviderHandle handle, MediaItem item, PlaybackSource source, CancellationToken ct)
    {
        if (handle.SubtitleContent is null)
            return new ServerSubtitleFetch(Array.Empty<string>(), null);

        List<SubtitleTrack> external = source.Streams
            .Where(stream => string.Equals(stream.Type, "subtitle", StringComparison.Ordinal)
                             && stream.IsExternal
                             && SubtitleFormats.TryResolve(stream.Codec, out _, out _))
            .Take(MaxServerSubtitleTracks)
            .Select(stream => new SubtitleTrack(
                stream.Index, stream.Language, stream.DisplayTitle, stream.Codec, stream.IsExternal, stream.IsDefault, IsForced: false))
            .ToList();
        if (external.Count == 0) return new ServerSubtitleFetch(Array.Empty<string>(), null);

        using var budget = CancellationTokenSource.CreateLinkedTokenSource(ct);
        budget.CancelAfter(ServerSubtitleBudget);
        string? directory = null;
        try
        {
            directory = Path.Combine(Path.GetTempPath(), "MediaHub", "subtitles", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(directory);

            var files = new List<string>(external.Count);
            foreach (SubtitleTrack track in external)
            {
                try
                {
                    byte[] bytes = await handle.SubtitleContent.FetchSubtitleAsync(item.Id, track, source.MediaSourceId, budget.Token).ConfigureAwait(false);
                    SubtitleFormats.TryResolve(track.Codec, out _, out string extension);
                    string path = Path.Combine(directory,
                        "track-" + track.Index.ToString(CultureInfo.InvariantCulture) + "." + extension);
                    await File.WriteAllBytesAsync(path, bytes, budget.Token).ConfigureAwait(false);
                    files.Add(path);
                }
                catch (Exception ex) when (!ct.IsCancellationRequested)
                {
                    _log.Warn("playback", "外挂字幕获取失败：" + track.Index.ToString(CultureInfo.InvariantCulture) + " — " + ex.GetType().Name);
                    if (budget.IsCancellationRequested) break;
                }
            }

            StartupTrace.Write("playback:server-subtitles=" + files.Count.ToString(CultureInfo.InvariantCulture));
            if (files.Count == 0)
            {
                TryDeleteDirectory(directory);
                return new ServerSubtitleFetch(Array.Empty<string>(), null);
            }
            return new ServerSubtitleFetch(files, directory);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            if (directory is not null) TryDeleteDirectory(directory);
            throw;
        }
        catch (Exception ex)
        {
            _log.Warn("playback", "外挂字幕发现失败：" + ex.GetType().Name);
            if (directory is not null) TryDeleteDirectory(directory);
            return new ServerSubtitleFetch(Array.Empty<string>(), null);
        }
    }

    private static void TryDeleteDirectory(string directory)
    {
        try { Directory.Delete(directory, recursive: true); }
        catch (Exception) { /* best effort */ }
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
        // The engine's clock is the item's timeline in every mode: the HLS playlist is absolute (see
        // ResolveAsync), so no offset has to be folded back in.
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

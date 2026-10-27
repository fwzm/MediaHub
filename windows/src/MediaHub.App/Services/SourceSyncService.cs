using System.Text;
using MediaHub.Domain;
using MediaHub.Net;
using MediaHub.Providers;
using MediaHub.Sync;

namespace MediaHub.App.Services;

/// <summary>Outcome of verifying one source against its real server.</summary>
/// <remarks>
/// Deliberately only three states. "Exported an envelope" and "wrote a file" are not evidence that a
/// source works, so they can never be reported as a verification success (ADR-S001 D7).
/// </remarks>
public enum SourceVerificationState
{
    /// <summary>Not checked, or the check could not complete. Never presented as success.</summary>
    NotVerified = 0,
    Verified,
    AuthenticationFailed,
}

/// <summary>Result of one verification attempt, with a sentence that can be shown as-is.</summary>
public readonly record struct SourceVerification(SourceVerificationState State, string Detail)
{
    /// <summary>The only three labels this app is allowed to show for a source check.</summary>
    public string Label => State switch
    {
        SourceVerificationState.Verified => "已验证",
        SourceVerificationState.AuthenticationFailed => "认证失败",
        _ => "未验证",
    };

    public static SourceVerification Verified(string detail) => new(SourceVerificationState.Verified, detail);
    public static SourceVerification AuthenticationFailed(string detail) => new(SourceVerificationState.AuthenticationFailed, detail);
    public static SourceVerification NotVerified(string detail) => new(SourceVerificationState.NotVerified, detail);
}

/// <summary>What one export wrote.</summary>
public sealed record EnvelopeExportResult(string Path, int SourceCount, int DeletionCount);

/// <summary>
/// Cross-device source sync for the App layer: export this device's envelope, preview a peer's envelope,
/// and verify that a source really works.
/// </summary>
/// <remarks>
/// <para>
/// Export and preview are the only two operations, and neither of them changes a source: exporting writes
/// one file, previewing only computes a plan. Applying a plan needs an explicit per-source approval and is
/// not reachable from this service (W6-3), which is why a user can look at the preview without any risk of
/// finding their sources reordered afterwards.
/// </para>
/// <para>
/// Envelopes never carry credentials: the export always calls the bridge with
/// <c>includeSecrets: false</c>, and the resulting JSON has no field a password or token could hide in.
/// </para>
/// </remarks>
public sealed class SourceSyncService
{
    /// <summary>File the peer's envelope is read from, and this device's envelope is written to.</summary>
    public const string EnvelopeFileName = "sync-envelope.json";

    private const string LogCategory = "sync";

    private readonly LogService _log;
    private readonly ServerManager _servers;
    private readonly FileLocalSourceStore _store;
    private readonly FileSourceSyncBaselineStore _baselines;
    private readonly SourceSyncImportRound _round;
    private readonly string _deviceId;

    // W6-13: one registry for the whole process. Two would mean two pins for the same device — the presenters and
    // the page must therefore share this instance instead of each constructing their own.
    private readonly Lazy<PairedDeviceRegistry> _pairedDevices;

    /// <param name="store">Mirrored local source records.</param>
    /// <param name="baselines">
    /// Per-peer baseline file. Defaults to <c>sync-baseline.json</c> in the same folder as the mirrored
    /// records, which is what makes "import the same envelope twice" a no-op even after a restart.
    /// </param>
    public SourceSyncService(
        SettingsStore settings,
        LogService log,
        ServerManager servers,
        FileLocalSourceStore store,
        FileSourceSyncBaselineStore? baselines = null)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(log);
        ArgumentNullException.ThrowIfNull(servers);
        ArgumentNullException.ThrowIfNull(store);

        _log = log;
        _servers = servers;
        _store = store;
        _baselines = baselines ?? new FileSourceSyncBaselineStore(store.DirectoryPath);
        _round = new SourceSyncImportRound(store, _baselines, () => _baselines.LoadError);
        _deviceId = SyncIdentity.EnsureDeviceId(settings);

        // Lazily: a page that never pairs never reads the file, and the first use decides the directory once.
        _pairedDevices = new Lazy<PairedDeviceRegistry>(
            () => new PairedDeviceRegistry(new FilePairedDeviceStore(store.DirectoryPath)));
    }

    /// <summary>Identity of this installation, as it appears in every envelope.</summary>
    public string DeviceId => _deviceId;

    /// <summary>Where the mirrored source records live.</summary>
    public string StorePath => _store.FilePath;

    /// <summary>Set when the stored records could not be read; shown instead of pretending there are none.</summary>
    public string? StoreError => _store.LoadError;

    /// <summary>Where the envelope is written to and read from (W6-3 replaces the file with the network).</summary>
    public string EnvelopePath => Path.Combine(_store.DirectoryPath, EnvelopeFileName);

    /// <summary>
    /// Maps the configured sources for display: which ones can travel, and why the others cannot.
    /// </summary>
    public LocalSourceMappingResult DescribeLocalSources() => _servers.DescribeSyncSources();

    /// <summary>Gives every source that can travel a stable identity. Idempotent.</summary>
    public int EnsureSourceIds() => _servers.EnsureSyncSourceIds();

    /// <summary>Builds this device's envelope. Contains no secrets by construction.</summary>
    public SourceSyncEnvelope BuildEnvelope()
        => new SourceSyncLocalBridge(_store, _deviceId).BuildEnvelope(includeSecrets: false);

    /// <summary>
    /// The peer baselines themselves (W6-80). Forgetting a paired device has to clear its baseline through this same
    /// instance: a second store over the same path would read the file once and then disagree with the session.
    /// </summary>
    public ISourceSyncBaselineStore Baselines => _baselines;

    /// <summary>The stored baseline that makes a repeated import a no-op.</summary>
    public string BaselinePath => _baselines.FilePath;

    /// <summary>
    /// Set when the stored baseline could not be read. A damaged file degrades to a full plan instead of
    /// crashing the page, and is reported here rather than silently ignored.
    /// </summary>
    public string? BaselineError => _baselines.LoadError;

    /// <summary>
    /// The sync session this service speaks through: one device identity, the mirrored records, and the
    /// stored baseline. Exporting through it is what proves the baseline store is really wired in.
    /// </summary>
    public SourceSyncSession CreateSession() => new(_store, _deviceId, baselineStore: _baselines);

    /// <summary>
    /// Devices this machine has paired with (W6-12 registry). The single instance every pairing presenter and the
    /// page share, so a fingerprint is pinned exactly once.
    /// </summary>
    public PairedDeviceRegistry PairedDevices => _pairedDevices.Value;

    /// <summary>Where paired devices are remembered: <c>paired-devices.json</c> next to the baseline.</summary>
    public string PairedDevicesPath
        => Path.Combine(_store.DirectoryPath, FilePairedDeviceStore.FileName);

    /// <summary>Set when the paired-device file could not be read; shown instead of pretending nothing is paired.</summary>
    public string? PairedDevicesError
    {
        get
        {
            try
            {
                return _pairedDevices.Value.LoadError;
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                return exception.Message;
            }
        }
    }

    /// <summary>Writes the envelope next to the mirrored source records and reports what it contains.</summary>
    public EnvelopeExportResult ExportEnvelope()
    {
        ExportSession export = CreateSession().Export(includeSecrets: false);
        SourceSyncEnvelope envelope = export.Envelope;

        System.IO.Directory.CreateDirectory(_store.DirectoryPath);
        string temp = EnvelopePath + ".tmp";
        File.WriteAllText(temp, export.EnvelopeJson);
        File.Move(temp, EnvelopePath, overwrite: true);

        _log.Info(LogCategory, "已导出同步信封，共 " + envelope.Sources.Count + " 个媒体源（不含任何密钥）");
        return new EnvelopeExportResult(EnvelopePath, envelope.Sources.Count, envelope.Deletions.Count);
    }

    /// <summary>
    /// Reads a peer's envelope from <see cref="EnvelopePath"/>. A failure is returned as a message, never
    /// as an exception the page would have to translate.
    /// </summary>
    public bool TryReadEnvelope(out SourceSyncEnvelope? envelope, out string? error)
    {
        envelope = null;
        error = null;

        try
        {
            if (!File.Exists(EnvelopePath))
            {
                error = "没有找到对端信封：请先把对端导出的 " + EnvelopeFileName + " 放到 " + EnvelopePath;
                return false;
            }

            string json = File.ReadAllText(EnvelopePath);
            if (!SourceSyncCodec.TryDeserialize(json, out envelope, out string? decodeError))
            {
                error = "对端信封无法解析：" + decodeError;
                return false;
            }
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            error = "无法读取对端信封（" + ex.GetType().Name + "）：" + ex.Message;
            return false;
        }
    }

    /// <summary>
    /// Computes what the peer's envelope would do to this device. Pure: nothing is written or changed.
    /// </summary>
    public SourceSyncPlan Preview(SourceSyncEnvelope peer)
    {
        ArgumentNullException.ThrowIfNull(peer);
        return SourceSyncPlanner.Plan(peer, _store.GetAll());
    }

    /// <summary>
    /// Checks a source by really authenticating and really listing its libraries.
    /// </summary>
    /// <remarks>
    /// A session check alone would prove nothing: a stored token can restore and still be refused by the
    /// server. The library call is what makes "已验证" mean the source can actually be used.
    /// </remarks>
    public async Task<SourceVerification> VerifyAsync(string serverId, CancellationToken ct = default)
    {
        ServerEntry? entry = _servers.Find(serverId);
        if (entry is null) return SourceVerification.NotVerified("该媒体源已不存在");
        if (entry.Handle is null) return SourceVerification.NotVerified("该媒体源当前没有可用的连接");
        if (entry.Handle.Auth is null) return SourceVerification.NotVerified("该数据源不支持认证，无法验证");
        if (entry.Handle.Library is null) return SourceVerification.NotVerified("该数据源不支持列出媒体库，无法验证读取权限");

        try
        {
            AuthSessionState state = await entry.Handle.Auth.RestoreSessionAsync(ct).ConfigureAwait(true);
            if (state == AuthSessionState.Authenticated)
            {
                var libraries = await entry.Handle.Library.GetLibrariesAsync(ct).ConfigureAwait(true);
                _log.Info(LogCategory, "媒体源验证通过：" + entry.DisplayName + "，媒体库 " + libraries.Count + " 个");
                return SourceVerification.Verified("认证通过，并成功读取 " + libraries.Count + " 个媒体库");
            }

            if (state is AuthSessionState.Expired or AuthSessionState.NotAuthenticated)
            {
                return SourceVerification.AuthenticationFailed("登录状态已失效，请在「媒体源」页面重新登录");
            }

            return state == AuthSessionState.Unreachable
                ? SourceVerification.NotVerified("服务器不可达，本次没有完成验证")
                : SourceVerification.NotVerified("未能确认登录状态（" + state + "）");
        }
        catch (ProviderException ex) when (ex.Code is ProviderErrorCode.AuthFailed or ProviderErrorCode.AuthRequired or ProviderErrorCode.AuthExpired)
        {
            return SourceVerification.AuthenticationFailed(ex.Message);
        }
        catch (ProviderException ex)
        {
            return SourceVerification.NotVerified("验证请求失败：" + ex.Message);
        }
        catch (OperationCanceledException)
        {
            return SourceVerification.NotVerified("验证已取消");
        }
        catch (Exception ex)
        {
            return SourceVerification.NotVerified("验证过程中出现错误（" + ex.GetType().Name + "）：" + ex.Message);
        }
    }

    // ---------------------------------------------------------------- apply (W6-8)

    /// <summary>
    /// A fixed sentence shown after every apply. Nothing in this feature proves a sign-in, so the app says
    /// so instead of letting 「已写入」 read as 「已同步」.
    /// </summary>
    public const string ApplyDisclaimer = "导入不代表已登录；请对每个来源点『验证』。";

    /// <summary>
    /// Reads the file the user picked: either a plain envelope or an encrypted archive.
    /// </summary>
    /// <remarks>
    /// The passphrase is used for one call and never stored, logged, or copied into an error message: a
    /// wrong passphrase and an edited archive are the same observable event in AES-GCM, and the reader's
    /// error code is the only thing that leaves this method.
    /// </remarks>
    public PeerFileResult ReadPeerFile(string? path, string? passphrase)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return new PeerFileResult(PeerFileKind.None, null, "没有选择信封文件。", false);
        }

        byte[] bytes;
        try
        {
            bytes = File.ReadAllBytes(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new PeerFileResult(PeerFileKind.None, null, "无法读取信封文件（" + ex.GetType().Name + "）。", false);
        }

        if (EncryptedArchiveReader.TryReadHeader(bytes, out _, out _))
        {
            if (string.IsNullOrEmpty(passphrase))
            {
                return new PeerFileResult(PeerFileKind.EncryptedArchive, null, "这个信封是加密的，需要输入口令。", true);
            }

            if (!EncryptedArchiveReader.TryRead(bytes, passphrase, out SourceSyncEnvelope? decrypted, out string? reason))
            {
                // The reader deliberately collapses "wrong passphrase" and "tampered" into one code; the
                // message says so without echoing anything the user typed.
                return new PeerFileResult(
                    PeerFileKind.EncryptedArchive,
                    null,
                    "无法解密信封（" + reason + "）。口令错误或文件被修改过；口令不会写入日志。",
                    true);
            }

            return new PeerFileResult(PeerFileKind.EncryptedArchive, decrypted, null, false);
        }

        string text = Encoding.UTF8.GetString(bytes).TrimStart('\uFEFF');
        if (!SourceSyncCodec.TryDeserialize(text, out SourceSyncEnvelope? envelope, out string? decodeError))
        {
            return new PeerFileResult(PeerFileKind.PlainEnvelope, null, "信封无法解析：" + decodeError, false);
        }

        return new PeerFileResult(PeerFileKind.PlainEnvelope, envelope, null, false);
    }

    /// <summary>
    /// Computes what applying this envelope would do, including the gate's default verdict per entry.
    /// </summary>
    /// <remarks>
    /// Pure. The returned preview is what the page binds to; a row only becomes a write when the user
    /// clicks 应用, and <see cref="ApplyApprovedAsync"/> is the only thing in the app that writes sources
    /// from an envelope. The round lives in MediaHub.Sync so that "plan against the stored baseline, then
    /// write only what the user allowed" has one implementation, shared by the app and its tests.
    /// </remarks>
    public SourceSyncApplyPreview PreviewApply(SourceSyncEnvelope peer)
    {
        ArgumentNullException.ThrowIfNull(peer);
        return _round.Preview(peer);
    }

    /// <summary>
    /// Writes the entries the user allowed, then reports counts. The only write path for an envelope.
    /// </summary>
    /// <remarks>
    /// Entries the gate keeps as proposals (withheld) and entries whose write failed are reported together
    /// as 保留未应用, because from the user's side both mean "this did not take effect".
    /// </remarks>
    public async Task<SourceSyncApplyReport> ApplyApprovedAsync(
        SourceSyncApplyPreview preview,
        IReadOnlyList<string>? allowedIds,
        IReadOnlyList<string>? skippedIds = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(preview);

        // The round writes in plan order, advances the stored baseline with exactly the records it wrote, and
        // reports the baseline as part of the result, so "the same envelope applied twice is a no-op" can be
        // proven from the app layer and not only from a test double.
        // The skipped list is what makes "the user unticked this row" mean something: without it, a create that
        // needs no approval is applied even though the page showed it as skipped.
        SourceSyncApplyReport result = await _round
            .ApplyAsync(preview, allowedIds, WriteApprovedRecordAsync, skippedIds, ct)
            .ConfigureAwait(true);

        if (result.Withheld > 0)
        {
            _log.Warn(LogCategory, "应用对端信封时有 " + result.Withheld + " 条保留未应用（含凭据或写入被拒）");
        }

        _log.Info(
            LogCategory,
            "已应用对端信封：写入 " + result.Applied + " 条，跳过 " + result.Skipped + " 条，保留未应用 "
            + result.Withheld + " 条；本机基线" + (result.BaselineUpdated ? "已更新" : "未变化") + "（不含任何凭据）");

        return result;
    }

    /// <summary>Turns one approved entry into a real source change; the round owns ordering and the baseline.</summary>
    private async Task<SourceSyncWriteOutcome> WriteApprovedRecordAsync(
        SourceSyncApprovalItem item,
        SourceRecord? record)
    {
        if (record is null)
        {
            // No record with that identity in the envelope means the peer stated a deletion.
            return Describe(await _servers.RemoveRemoteSourceAsync(item.SyncSourceId).ConfigureAwait(true));
        }

        if (item.Action == SourceSyncAction.Adopt && item.ServerId is { Length: > 0 } adoptServerId)
        {
            return Describe(_servers.AdoptRemoteSource(adoptServerId, item.SyncSourceId, record.RecordRevision));
        }

        return Describe(_servers.ApplyRemoteSource(record));
    }

    private static SourceSyncWriteOutcome Describe(ServerManager.RemoteSourceOutcome outcome)
        => outcome.Applied
            ? SourceSyncWriteOutcome.Written(outcome.Message)
            : SourceSyncWriteOutcome.Refused(outcome.Message);
}

/// <summary>Which kind of file the peer sent.</summary>
public enum PeerFileKind
{
    None = 0,
    PlainEnvelope,
    EncryptedArchive,
}

/// <summary>What reading the picked file produced; <see cref="NeedsPassphrase"/> drives the dialog.</summary>
public sealed record PeerFileResult(PeerFileKind Kind, SourceSyncEnvelope? Envelope, string? Error, bool NeedsPassphrase)
{
    public bool Succeeded => Envelope is not null;
}

/// <summary>One row of the merge preview, pre-formatted for the page.</summary>
/// <remarks>
/// <see cref="ConfirmLabel"/> exists so a conflict says 「需用户确认」 in words: the preview never writes
/// anything, and the row should not leave the user guessing whether it already did.
/// </remarks>
public sealed record SyncPreviewRow(string Name, string ActionLabel, string DetailLabel, bool RequiresConfirmation)
{
    /// <summary>Empty unless the plan keeps this candidate as a proposal.</summary>
    public string ConfirmLabel => RequiresConfirmation ? "需用户确认" : string.Empty;
}

/// <summary>Vocabulary for the merge preview, kept out of the page so it can be read in one place.</summary>
public static class SyncPreviewText
{
    /// <summary>Builds a bindable row for one plan candidate.</summary>
    public static SyncPreviewRow Describe(SourceSyncCandidate candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);

        string name = FirstNonBlank(candidate.DisplayName, candidate.SyncSourceId, candidate.ServerId, "（未命名来源）");
        string action = DescribeAction(candidate.Action);

        var parts = new List<string>();
        if (!string.IsNullOrWhiteSpace(candidate.SourceType))
        {
            parts.Add("类型 " + candidate.SourceType.Trim());
        }

        if (!string.IsNullOrWhiteSpace(candidate.NormalizedUrl))
        {
            parts.Add(candidate.NormalizedUrl.Trim());
        }

        if (candidate.LocalRevision is not null || candidate.RemoteRevision is not null)
        {
            parts.Add("本机版本 " + DescribeRevision(candidate.LocalRevision) + " · 对端版本 " + DescribeRevision(candidate.RemoteRevision));
        }

        parts.Add(DescribeReason(candidate.Reason));

        if (candidate.CandidateServerIds.Count > 1)
        {
            parts.Add("候选 " + string.Join(" / ", candidate.CandidateServerIds));
        }

        return new SyncPreviewRow(name, action, string.Join(" · ", parts), candidate.RequiresUserApproval);
    }

    /// <summary>Localised label of one planned action.</summary>
    public static string DescribeAction(SourceSyncAction action) => action switch
    {
        SourceSyncAction.Create => "新建",
        SourceSyncAction.Update => "更新",
        SourceSyncAction.NoChange => "无变化",
        SourceSyncAction.Conflict => "冲突",
        SourceSyncAction.IgnoreStale => "忽略（对端较旧）",
        SourceSyncAction.Delete => "删除",
        SourceSyncAction.Adopt => "接管已有来源",
        SourceSyncAction.Unsupported => "不支持",
        _ => action.ToString(),
    };

    /// <summary>Localised label of a planner reason code; unknown codes are shown verbatim.</summary>
    public static string DescribeReason(string? reason) => reason switch
    {
        SourceSyncReasons.NewSource => "本机没有该来源",
        SourceSyncReasons.HigherRevision => "对端版本更高",
        SourceSyncReasons.UpToDate => "已是最新",
        SourceSyncReasons.SameRevisionDifferentContent => "版本相同但内容不同",
        SourceSyncReasons.StaleRevision => "对端版本较低",
        SourceSyncReasons.Tombstone => "对端已删除该来源",
        SourceSyncReasons.NoLocalSource => "本机没有该来源",
        SourceSyncReasons.UnknownSourceType => "未知来源类型",
        SourceSyncReasons.NotSupportedOnWindows => "Windows 暂不支持该类型",
        SourceSyncReasons.LegacyIdentityMatch => "与已有来源匹配（旧版没有标识）",
        SourceSyncReasons.AmbiguousLegacyIdentity => "有多个来源都像是它，无法自动确定",
        SourceSyncReasons.DuplicateLocalSyncId => "本机存在重复的同步标识",
        SourceSyncReasons.NoUsableEndpoint => "没有可用地址",
        null or "" => "未说明原因",
        _ => reason,
    };

    /// <summary>One sentence summarising a preview, with every count the task requires.</summary>
    public static string DescribeSummary(SourceSyncPreviewSummary summary)
    {
        ArgumentNullException.ThrowIfNull(summary);

        string counts = "共 " + summary.Total + " 项：新建 " + summary.Create
            + "，更新 " + summary.Update
            + "，冲突 " + summary.Conflict + "（其中地址重名无法确定 " + summary.Ambiguous + "）"
            + "，不支持 " + summary.Unsupported
            + "，删除 " + summary.Delete
            + "，无变化 " + summary.Unchanged
            + "，接管 " + summary.Adopt
            + "；需用户确认 " + summary.RequiresConfirmation + " 项。";

        return summary.Total == 0
            ? "对端信封里没有媒体源。"
            : counts + (summary.HasChanges ? string.Empty : "对端与本机一致，没有需要写入的变更。");
    }

    private static string DescribeRevision(long? revision) =>
        revision is long value ? value.ToString(System.Globalization.CultureInfo.InvariantCulture) : "无";

    private static string FirstNonBlank(params string?[] values)
    {
        foreach (string? value in values)
        {
            if (!string.IsNullOrWhiteSpace(value))
            {
                return value.Trim();
            }
        }

        return string.Empty;
    }
}

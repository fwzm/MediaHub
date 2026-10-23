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
    private readonly string _deviceId;

    public SourceSyncService(SettingsStore settings, LogService log, ServerManager servers, FileLocalSourceStore store)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(log);
        ArgumentNullException.ThrowIfNull(servers);
        ArgumentNullException.ThrowIfNull(store);

        _log = log;
        _servers = servers;
        _store = store;
        _deviceId = SyncIdentity.EnsureDeviceId(settings);
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

    /// <summary>Writes the envelope next to the mirrored source records and reports what it contains.</summary>
    public EnvelopeExportResult ExportEnvelope()
    {
        SourceSyncEnvelope envelope = BuildEnvelope();
        string json = SourceSyncCodec.Serialize(envelope);

        System.IO.Directory.CreateDirectory(_store.DirectoryPath);
        string temp = EnvelopePath + ".tmp";
        File.WriteAllText(temp, json);
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

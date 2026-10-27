using System.Globalization;

namespace MediaHub.Sync;

/// <summary>
/// Every fixed sentence the sync page shows, in one place.
/// </summary>
/// <remarks>
/// <para>
/// The sentences live here rather than in XAML or in the page's code-behind so that a test can assert the exact
/// words a user reads, and so that one edit cannot leave two screens disagreeing. The apply section's vocabulary
/// mirrors the Android import screen (<c>SourceSyncImportCopy</c>): the same three states and the same disclaimer,
/// because the two clients import the same envelopes and must not describe them differently.
/// </para>
/// <para>
/// The disclaimer is load-bearing: importing writes a source record, never a login session, so no sentence here
/// may call an import "已登录" or "已同步".
/// </para>
/// </remarks>
public static class SyncPageCopy
{
    /// <summary>The fixed sentence that must survive every edit: a write is not a sign-in.</summary>
    public const string ApplyDisclaimer = "导入不代表已登录；请对每个来源点『验证』。";

    /// <summary>Visible receipt after the user allows one entry.</summary>
    public const string AllowedState = "已允许：应用时会写入本地来源记录（不含登录凭据）。";

    /// <summary>Visible receipt after the user skips one entry.</summary>
    public const string SkippedState = "已跳过：本条不会被导入。";

    /// <summary>What allowing one approval-gated entry would do, shown before the click.</summary>
    public const string AllowRisk = "允许导入会在本机新增/更新该来源记录（不含登录凭据）。";

    /// <summary>Fixed explanation for a type this build cannot import; the button stays disabled.</summary>
    public const string NeverAllowUnsupported = "本机暂不支持该来源类型，无法允许导入。";

    /// <summary>Fixed explanation for conflicts/deletions/ambiguous identities: allowed nowhere near this page.</summary>
    public const string NeverAllowApproval = "冲突、删除与歧义身份需在来源页逐条处理，此处不能自动允许。";

    /// <summary>Shown when the apply button is disabled because nothing was allowed.</summary>
    public const string NothingAllowed = "请至少允许一个条目；未允许的条目不会被导入。";

    /// <summary>Confirmation dialog title before writing.</summary>
    public const string ConfirmTitle = "应用远端变更";

    /// <summary>Confirmation sentence for a round that touches a risky entry.</summary>
    public const string ConfirmRiskNote =
        "其中包含需要你确认的冲突变更（删除与修改相撞、字段冲突或版本相同但内容不同）；允许它们会覆盖本机当前配置。";

    /// <summary>Confirmation dialog accept label.</summary>
    public const string ConfirmLabel = "应用";

    /// <summary>Confirmation dialog cancel label.</summary>
    public const string CancelLabel = "取消";

    /// <summary>Prefix of the line that counts the addresses this client cannot store.</summary>
    public const string NotImportedHeading = "未导入的次要地址：";

    /// <summary>Paired-device section: forget confirmation title.</summary>
    public const string ForgetTitle = "忘记这台设备？";

    /// <summary>What forgetting keeps, verbatim: local bookkeeping only.</summary>
    /// <remarks>
    /// W6-80: the peer's sync baseline goes with the registration. Leaving it behind means a later pairing of the
    /// same device id starts with "already known", so the peer's real changes would be skipped instead of pulled.
    /// </remarks>
    public const string ForgetKeepsNote =
        "只删除本机的配对登记与该对端的同步基线，来源、凭据和对端设备都不会被改动；下次配对会重新按首次配对处理。";

    /// <summary>Forget confirmation accept label.</summary>
    public const string ForgetConfirmLabel = "忘记";

    /// <summary>Forget confirmation cancel label.</summary>
    public const string ForgetCancelLabel = "取消";

    /// <summary>Accessible name of the forget confirmation button.</summary>
    public const string ForgetConfirmName = "确认忘记该设备";

    /// <summary>Accessible name of the forget cancel button.</summary>
    public const string ForgetCancelName = "取消忘记该设备";

    /// <summary>Result title after a device was forgotten.</summary>
    public const string ForgetDoneTitle = "已忘记该设备";

    /// <summary>Result message after a device was forgotten.</summary>
    public const string ForgetDoneMessage =
        "只删除了本机的配对登记与该对端的同步基线：来源、凭据和对端设备都没有变化；下次配对会重新按首次配对处理。";

    /// <summary>Result message when the registration went but no baseline store was wired, so a baseline may remain.</summary>
    public const string ForgetDoneMessageWithoutBaseline =
        "只删除了本机的配对登记：这个页面没有可用的同步基线存储，对端基线可能还留在盘上；来源、凭据和对端设备都没有变化。";

    /// <summary>Result title when the registry holds nothing for the requested device.</summary>
    public const string ForgetUnknownTitle = "本机没有这台设备的登记";

    /// <summary>Result message when nothing was pending, so a confirm cannot delete the wrong row.</summary>
    public const string ForgetNothingPending = "没有待确认的忘记操作。";

    /// <summary>Result message after cancel: the cancel is itself a receipt.</summary>
    public const string ForgetCancelled = "没有删除任何登记。";

    /// <summary>
    /// Why the candidate addresses are shown at all, and why nothing listens on them.
    /// </summary>
    public const string AddressNote =
        "这些地址只用于告诉对方本机在局域网里的位置；本机不会在配对时监听它们（Windows 会因此弹出防火墙提示）。";

    /// <summary>Shown when no LAN address could be enumerated.</summary>
    public const string AddressEmpty = "没有找到可用的局域网地址";

    /// <summary>What an offline pairing link is, and what it is not.</summary>
    public static readonly string InvitationNote =
        "这条链接 " + PairingPayload.LifetimeSeconds + " 秒内有效，且只能成功连接一次。请把链接和指纹一起发给对方核对：链接只描述本机，本机不会为它监听任何非回环地址。";
}

/// <summary>One row of the apply list: what the entry would do, what the user decided, and why it is blocked.</summary>
/// <remarks>
/// <see cref="StateLabel"/> is what the list shows after a click; it is the visible receipt that stops a user from
/// clicking twice or believing nothing happened. <see cref="BlockReason"/> is set instead for entries the gate can
/// never honour, so the disabled button always has a sentence next to it.
/// </remarks>
public sealed record SyncApplyRow
{
    /// <summary>Identity of the entry; a click is resolved by this and never by list position.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Best display name the entry carries.</summary>
    public string Name { get; init; } = "";

    /// <summary>Localised planned action (新建/更新/删除/冲突/…).</summary>
    public string ActionLabel { get; init; } = "";

    /// <summary>Type, address, both revisions and the stable reason, on one line.</summary>
    public string DetailLabel { get; init; } = "";

    /// <summary>Visible receipt of the current decision; empty for a blocked row.</summary>
    public string StateLabel { get; init; } = "";

    /// <summary>The same receipt under the task's vocabulary (stateText).</summary>
    public string StateText => StateLabel;

    /// <summary>「需用户确认」 only for entries the gate keeps as proposals.</summary>
    public string ConfirmLabel => RequiresApproval ? "需用户确认" : string.Empty;

    /// <summary>True when the planned entry needs an explicit decision.</summary>
    public bool RequiresApproval { get; init; }

    /// <summary>False for reasons the gate can never honour, which is why the button is disabled.</summary>
    public bool Allowable { get; init; }

    /// <summary>True when applying would write this entry.</summary>
    public bool Allowed { get; init; }

    /// <summary>Fixed sentence explaining why this row can never be allowed; empty when it can.</summary>
    public string BlockReason { get; init; } = "";

    /// <summary>True when <see cref="BlockReason"/> carries a sentence.</summary>
    public bool IsBlocked => BlockReason.Length > 0;

    /// <summary>Accessible name of the allow button.</summary>
    public string AllowLabel { get; init; } = "";

    /// <summary>Accessible name of the skip button.</summary>
    public string SkipLabel { get; init; } = "";
}

/// <summary>Everything the apply section renders for one preview and one set of decisions.</summary>
public sealed record SyncApplyView
{
    /// <summary>One row per entry of the preview.</summary>
    public required IReadOnlyList<SyncApplyRow> Rows { get; init; }

    /// <summary>True when a preview has been loaded.</summary>
    public bool HasPreview { get; init; }

    /// <summary>True when the gate would write at least one entry.</summary>
    public bool CanApply { get; init; }

    /// <summary>True when the gate withheld entries that can never be applied.</summary>
    public bool IsBlocked { get; init; }

    /// <summary>Sentence above the list: which entries cannot be applied, or why the button is disabled.</summary>
    public string BlockedText { get; init; } = "";

    /// <summary>The fixed disclaimer; the page must not spell its own.</summary>
    public string Disclaimer { get; init; } = SyncPageCopy.ApplyDisclaimer;

    /// <summary>One sentence describing the preview's counts.</summary>
    public string SummaryText { get; init; } = "";

    /// <summary>How many secondary addresses will not be imported.</summary>
    public string NotImportedCountText { get; init; } = "";

    /// <summary>One row per secondary address that will not be imported.</summary>
    public IReadOnlyList<string> NotImportedRows { get; init; } = [];

    /// <summary>Entries the gate would apply.</summary>
    public int AppliedCount { get; init; }

    /// <summary>Entries the gate would skip (all of them when the user allowed nothing).</summary>
    public int SkippedCount { get; init; }

    /// <summary>Entries the gate withholds because no decision can ever apply them.</summary>
    public int WithheldCount { get; init; }

    /// <summary>Entries explicitly allowed so far.</summary>
    public int AllowedCount { get; init; }

    /// <summary>The "nothing previewed yet" state.</summary>
    public static SyncApplyView Empty { get; } = new() { Rows = [] };
}

/// <summary>The confirmation dialog's text, built from the gate so the numbers match what will be written.</summary>
public sealed record SyncApplyConfirm
{
    /// <summary>Dialog title.</summary>
    public string Title { get; init; } = SyncPageCopy.ConfirmTitle;

    /// <summary>How many entries will be written, skipped and withheld.</summary>
    public string CountsText { get; init; } = "";

    /// <summary>Risk sentence, present only when a risky change is about to be allowed.</summary>
    public string RiskText { get; init; } = "";

    /// <summary>True when <see cref="RiskText"/> carries a sentence.</summary>
    public bool HasRisk => RiskText.Length > 0;

    /// <summary>The fixed disclaimer, repeated in the dialog.</summary>
    public string Disclaimer { get; init; } = SyncPageCopy.ApplyDisclaimer;

    /// <summary>Accept button text.</summary>
    public string ConfirmLabel { get; init; } = SyncPageCopy.ConfirmLabel;

    /// <summary>Cancel button text.</summary>
    public string CancelLabel { get; init; } = SyncPageCopy.CancelLabel;
}

/// <summary>The result line after a round, with the counts, the baseline movement and the disclaimer.</summary>
public sealed record SyncApplyResult
{
    /// <summary>The whole sentence the page shows, disclaimer included.</summary>
    public string Message { get; init; } = "";

    /// <summary>How many sources were written and how many secondary addresses were refused.</summary>
    public string EndpointSentence { get; init; } = "";

    /// <summary>True when at least one entry was written.</summary>
    public bool WroteChanges { get; init; }

    /// <summary>InfoBar title for this outcome; never says "已同步".</summary>
    public string InfoTitle { get; init; } = "";

    /// <summary>Localised reasons the gate withheld, kept for an assertion or a log line.</summary>
    public IReadOnlyList<string> BlockingLabels { get; init; } = [];

    /// <summary>Localised baseline warnings, if any.</summary>
    public IReadOnlyList<string> WarningLabels { get; init; } = [];
}

/// <summary>
/// The apply section's state machine: one preview, the user's per-entry decisions, the gate, and the sentences.
/// </summary>
/// <remarks>
/// <para>
/// This type deliberately has no UI dependency. The page keeps controls and nothing else; everything a test can
/// decide — which rows exist, which of them can ever be allowed, what the receipt says, when the apply button is
/// enabled, and what the result line claims — is computed here.
/// </para>
/// <para>
/// The gate is recomputed from the current decisions on every read instead of being cached: a stale gate is how a
/// page ends up writing an entry the user has just skipped.
/// </para>
/// </remarks>
public sealed class SyncApplyPresenter
{
    private readonly Dictionary<string, bool> _decisions = new(StringComparer.OrdinalIgnoreCase);
    private SourceSyncApplyPreview? _preview;

    /// <summary>The preview currently loaded, or null.</summary>
    public SourceSyncApplyPreview? Preview => _preview;

    /// <summary>True when a preview is loaded.</summary>
    public bool HasPreview => _preview is not null;

    /// <summary>How many entries carry an explicit decision.</summary>
    public int DecisionCount => _decisions.Count;

    /// <summary>Loads a preview and drops every previous decision.</summary>
    public bool Load(SourceSyncApplyPreview? preview)
    {
        _preview = preview;
        _decisions.Clear();
        return preview is not null;
    }

    /// <summary>Drops the preview and the decisions; a pending plan must not survive a page.</summary>
    public void Clear()
    {
        _preview = null;
        _decisions.Clear();
    }

    /// <summary>Records an explicit allow for one entry; false when the id is blank.</summary>
    public bool Allow(string? syncSourceId) => Decide(syncSourceId, allow: true);

    /// <summary>Records an explicit skip for one entry; false when the id is blank.</summary>
    public bool Skip(string? syncSourceId) => Decide(syncSourceId, allow: false);

    /// <summary>What the user decided for one entry, or null when nothing was clicked.</summary>
    public bool? DecisionOf(string? syncSourceId)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return null;
        }

        return _decisions.TryGetValue(syncSourceId.Trim(), out bool decision) ? decision : null;
    }

    /// <summary>Runs the gate with the decisions made so far; an undecided approval entry is a skip.</summary>
    public SourceSyncApprovalResult Gate()
    {
        if (_preview is null)
        {
            return SourceSyncApprovalResult.None;
        }

        var decisions = new List<SourceSyncApprovalDecision>(_decisions.Count);
        foreach (KeyValuePair<string, bool> decision in _decisions)
        {
            decisions.Add(decision.Value
                ? SourceSyncApprovalDecision.Allow(decision.Key)
                : SourceSyncApprovalDecision.Skip(decision.Key));
        }

        return SourceSyncApprovalGate.Decide(_preview.Items, decisions);
    }

    /// <summary>The ids the apply call may write, in gate order.</summary>
    public IReadOnlyList<string> AllowedIds()
    {
        SourceSyncApprovalResult gate = Gate();
        var ids = new List<string>(gate.Applied.Count);
        foreach (SourceSyncApprovalItem item in gate.Applied)
        {
            ids.Add(item.SyncSourceId);
        }

        return ids;
    }

    /// <summary>The ids the apply call must not write, in gate order.</summary>
    /// <remarks>
    /// An allow list alone cannot express a skip: a create that needs no approval is applied even when the user
    /// never allowed it, so the rows the page shows as skipped have to travel separately and be honoured.
    /// Entries the gate withholds are not listed here — they are refused before the decision table is consulted,
    /// and a decision list must never be able to smuggle one of them in.
    /// </remarks>
    public IReadOnlyList<string> SkippedIds()
    {
        SourceSyncApprovalResult gate = Gate();
        var ids = new List<string>(gate.Skipped.Count);
        foreach (SourceSyncApprovalItem item in gate.Skipped)
        {
            ids.Add(item.SyncSourceId);
        }

        return ids;
    }

    /// <summary>Builds the bindable state of the section.</summary>
    public SyncApplyView View()
    {
        if (_preview is not { } preview)
        {
            return SyncApplyView.Empty;
        }

        var rows = new List<SyncApplyRow>(preview.Items.Count);
        foreach (SourceSyncApprovalItem item in preview.Items)
        {
            rows.Add(BuildRow(item));
        }

        SourceSyncApprovalResult gate = Gate();

        string blocked = gate.IsBlocked
            ? "不会被应用的条目：" + string.Join("、", gate.BlockingReasons.Select(DescribeGateReason))
            : gate.CanApply ? string.Empty : SyncPageCopy.NothingAllowed;

        var notImported = new List<string>(preview.NotImportedEndpoints.Count);
        foreach (SourceSyncNotImportedEndpoint endpoint in preview.NotImportedEndpoints)
        {
            notImported.Add(SourceSyncNotImportedText.Describe(endpoint));
        }

        return new SyncApplyView
        {
            Rows = rows,
            HasPreview = true,
            CanApply = gate.CanApply,
            IsBlocked = gate.IsBlocked,
            BlockedText = blocked,
            SummaryText = DescribeSummary(preview.Summary),
            NotImportedCountText = SourceSyncNotImportedText.DescribeCount(preview.EndpointSummary),
            NotImportedRows = notImported,
            AppliedCount = gate.AppliedCount,
            SkippedCount = gate.SkippedCount,
            WithheldCount = gate.WithheldCount,
            AllowedCount = gate.AppliedCount,
        };
    }

    /// <summary>The confirmation dialog's text, or null when there is nothing to confirm.</summary>
    public SyncApplyConfirm? Confirm()
    {
        if (_preview is null)
        {
            return null;
        }

        SourceSyncApprovalResult gate = Gate();
        if (!gate.CanApply)
        {
            return null;
        }

        return new SyncApplyConfirm
        {
            CountsText = "将写入本机 " + gate.AppliedCount.ToString(CultureInfo.InvariantCulture)
                + " 条来源，跳过 " + gate.SkippedCount.ToString(CultureInfo.InvariantCulture)
                + " 条，保留未应用 " + gate.WithheldCount.ToString(CultureInfo.InvariantCulture) + " 条。",
            RiskText = gate.AcknowledgedRisk ? SyncPageCopy.ConfirmRiskNote : string.Empty,
        };
    }

    /// <summary>Builds the result line; the disclaimer is appended here, never by the page.</summary>
    public SyncApplyResult DescribeResult(SourceSyncApplyReport report)
    {
        ArgumentNullException.ThrowIfNull(report);

        var blocking = new List<string>(report.BlockingReasons.Count);
        foreach (string reason in report.BlockingReasons)
        {
            blocking.Add(DescribeGateReason(reason));
        }

        var warnings = new List<string>(report.Warnings.Count);
        foreach (string warning in report.Warnings)
        {
            warnings.Add(DescribeBaselineWarning(warning));
        }

        string message = report.Summary + "。" + report.BaselineLabel + SyncPageCopy.ApplyDisclaimer;
        if (blocking.Count > 0)
        {
            message += " 不可应用：" + string.Join("、", blocking) + "。";
        }

        if (warnings.Count > 0)
        {
            message += " 提示：" + string.Join("、", warnings) + "。";
        }

        return new SyncApplyResult
        {
            Message = message,
            EndpointSentence = SourceSyncNotImportedText.Summarize(report.Applied, _preview?.EndpointSummary),
            WroteChanges = report.Applied > 0,
            InfoTitle = report.Applied > 0 ? "已写入远端变更" : "没有写入任何变更",
            BlockingLabels = blocking,
            WarningLabels = warnings,
        };
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

    /// <summary>Localises a gate reason; the gate itself carries codes only.</summary>
    public static string DescribeGateReason(string? reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return "对端没有说明原因";
        }

        string code = reason.Trim();
        if (code.StartsWith(SourceSyncApprovalGate.AmbiguousReasonPrefix, StringComparison.Ordinal))
        {
            return "有多个本机来源都像是它，无法确定";
        }

        return code switch
        {
            SyncRoundReasons.SecretsRequireApproval => "带凭据，需人工迁移（本次不会写入）",
            SyncRoundReasons.UnknownSourceType => "未知来源类型，Windows 无法应用",
            SyncRoundReasons.NotSupportedOnWindows => "Windows 暂不支持该来源类型",
            SyncRoundReasons.DeleteVsUpdate => "对端删除与本机修改相撞，需人工判断",
            SyncRoundReasons.UpdateVsDelete => "本机删除与对端修改相撞，需人工判断",
            SyncRoundReasons.FieldConflict => "两边把同一个字段改成了不同的值",
            SyncRoundReasons.SameRevisionDifferentContent => "版本相同但内容不同",
            SyncRoundReasons.TombstoneRequiresApproval => "对端删除了该来源，需人工确认",
            SyncRoundReasons.TombstoneUnknown => "对端删除了一个本机没有的来源",
            SyncRoundReasons.ApplyRemoteChange => "对端较新，可写入本机",
            SyncRoundReasons.PushLocalChange => "本机较新，请到对端应用",
            SyncRoundReasons.PushLocalDelete => "本机已删除，请到对端应用",
            SyncRoundReasons.RemoteNewer => "对端较新",
            SyncRoundReasons.LocalNewer => "本机较新",
            SyncRoundReasons.AlreadyApplied => "已经应用过",
            SyncRoundReasons.StaleRemote => "对端版本较旧",
            SyncRoundReasons.StaleLocal => "本机版本较旧",
            SyncRoundReasons.BothDeleted => "两边都已删除",
            _ => DescribeReason(code),
        };
    }

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

        string counts = "共 " + summary.Total.ToString(CultureInfo.InvariantCulture)
            + " 项：新建 " + summary.Create.ToString(CultureInfo.InvariantCulture)
            + "，更新 " + summary.Update.ToString(CultureInfo.InvariantCulture)
            + "，冲突 " + summary.Conflict.ToString(CultureInfo.InvariantCulture)
            + "（其中地址重名无法确定 " + summary.Ambiguous.ToString(CultureInfo.InvariantCulture) + "）"
            + "，不支持 " + summary.Unsupported.ToString(CultureInfo.InvariantCulture)
            + "，删除 " + summary.Delete.ToString(CultureInfo.InvariantCulture)
            + "，无变化 " + summary.Unchanged.ToString(CultureInfo.InvariantCulture)
            + "，接管 " + summary.Adopt.ToString(CultureInfo.InvariantCulture)
            + "；需用户确认 " + summary.RequiresConfirmation.ToString(CultureInfo.InvariantCulture) + " 项。";

        return summary.Total == 0
            ? "对端信封里没有媒体源。"
            : counts + (summary.HasChanges ? string.Empty : "对端与本机一致，没有需要写入的变更。");
    }

    /// <summary>Baseline problems arrive as stable codes; the page says what they mean for the next import.</summary>
    public static string DescribeBaselineWarning(string warning)
    {
        if (string.IsNullOrWhiteSpace(warning))
        {
            return "未说明的基线问题";
        }

        if (warning.StartsWith(SourceSyncSessionReasons.BaselineRejectedPrefix, StringComparison.Ordinal))
        {
            return "本机基线拒绝了部分条目（"
                + warning[SourceSyncSessionReasons.BaselineRejectedPrefix.Length..].TrimStart(':')
                + "）";
        }

        return warning == SourceSyncSessionReasons.BaselineWriteFailed
            ? "本机基线写入失败，下次导入会重复本次判断"
            : warning;
    }

    /// <summary>Why one row can never be allowed, in a sentence that keeps the stable code.</summary>
    public static string DescribeBlockReason(string? reason)
    {
        string code = string.IsNullOrWhiteSpace(reason) ? "unspecified" : reason.Trim();
        return code switch
        {
            SyncRoundReasons.SecretsRequireApproval =>
                "带凭据，需人工迁移（本次不会写入；稳定码：" + code + "）",
            SyncRoundReasons.UnknownSourceType =>
                SyncPageCopy.NeverAllowUnsupported + "（稳定码：" + code + "）",
            SyncRoundReasons.NotSupportedOnWindows =>
                SyncPageCopy.NeverAllowUnsupported + "（稳定码：" + code + "）",
            _ => SyncPageCopy.NeverAllowApproval + "（稳定码：" + code + "）",
        };
    }

    private SyncApplyRow BuildRow(SourceSyncApprovalItem item)
    {
        ArgumentNullException.ThrowIfNull(item);

        bool allowable = SourceSyncApprovalGate.IsApplicable(item.Action)
            && !SourceSyncApprovalGate.IsNeverAllowable(item.Reason);
        bool decided = _decisions.TryGetValue(item.SyncSourceId, out bool decision);

        // Undecided entries fall back to what applying would do right now: an applicable entry without an approval
        // requirement is allowed by default, while an approval-gated one is a proposal and says what allowing means.
        bool allowed = decided ? decision : allowable && !item.RequiresApproval;

        string name = FirstNonBlank(item.DisplayName, item.SyncSourceId, item.ServerId, "（未命名来源）");
        string state = !allowable
            ? string.Empty
            : decided
                ? (decision ? SyncPageCopy.AllowedState : SyncPageCopy.SkippedState)
                : item.RequiresApproval ? SyncPageCopy.AllowRisk : SyncPageCopy.AllowedState;

        return new SyncApplyRow
        {
            SyncSourceId = item.SyncSourceId,
            Name = name,
            ActionLabel = DescribeAction(item.Action),
            DetailLabel = DescribeApplyDetail(item),
            StateLabel = state,
            RequiresApproval = item.RequiresApproval,
            Allowable = allowable,
            Allowed = allowable && allowed,
            BlockReason = allowable ? string.Empty : DescribeBlockReason(item.Reason),
            AllowLabel = allowable ? "允许 " + name : "不可允许 " + name,
            SkipLabel = "跳过 " + name,
        };
    }

    private static string DescribeApplyDetail(SourceSyncApprovalItem item)
    {
        var parts = new List<string>(4);
        if (!string.IsNullOrWhiteSpace(item.SourceType))
        {
            parts.Add("类型 " + item.SourceType.Trim());
        }

        if (!string.IsNullOrWhiteSpace(item.NormalizedUrl))
        {
            parts.Add(item.NormalizedUrl.Trim());
        }

        if (item.LocalRevision is not null || item.RemoteRevision is not null)
        {
            parts.Add("本机版本 " + DescribeRevision(item.LocalRevision)
                + " · 对端版本 " + DescribeRevision(item.RemoteRevision));
        }

        parts.Add(DescribeGateReason(item.Reason));
        return string.Join(" · ", parts);
    }

    private static string DescribeRevision(long? revision) =>
        revision is long value ? value.ToString(CultureInfo.InvariantCulture) : "无";

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

    private bool Decide(string? syncSourceId, bool allow)
    {
        if (string.IsNullOrWhiteSpace(syncSourceId))
        {
            return false;
        }

        string key = syncSourceId.Trim();
        bool changed = !_decisions.TryGetValue(key, out bool current) || current != allow;
        _decisions[key] = allow;
        return changed;
    }
}

/// <summary>How a pasted pairing link compared with what this machine pinned last time.</summary>
public enum SyncPairingReplyState
{
    /// <summary>The link itself is unusable; nothing was compared.</summary>
    Invalid = 0,

    /// <summary>The peer is unknown here: safe to pin on first sight.</summary>
    New = 1,

    /// <summary>The fingerprint equals the pinned one: this is the device from last time.</summary>
    Match = 2,

    /// <summary>The fingerprint differs from the pinned one: never overwrite it.</summary>
    Mismatch = 3,
}

/// <summary>One offline invitation: the link, the identity behind it, and the addresses shown beside it.</summary>
public sealed record SyncPairingInvitation
{
    /// <summary>The <c>mediahub://pair?...</c> link a peer can paste into this page.</summary>
    public string Link { get; init; } = "";

    /// <summary>This device's id, as it will appear to the peer.</summary>
    public string DeviceId { get; init; } = "";

    /// <summary>The full fingerprint hex that the link carries.</summary>
    public string FingerprintHex { get; init; } = "";

    /// <summary>The same fingerprint, grouped for reading.</summary>
    public string FingerprintDisplay { get; init; } = "";

    /// <summary>When the link stops working.</summary>
    public long ExpiresAtEpochMs { get; init; }

    /// <summary>True only when a socket is actually listening; offline links never are.</summary>
    public bool Listens { get; init; }

    /// <summary>LAN addresses this machine could be reached on, best first; display only.</summary>
    public IReadOnlyList<string> AddressLines { get; init; } = [];

    /// <summary>Why the addresses are shown and why nothing binds them.</summary>
    public string AddressNote { get; init; } = SyncPageCopy.AddressNote;

    /// <summary>The sentence shown beside the link.</summary>
    public string Message { get; init; } = SyncPageCopy.InvitationNote;
}

/// <summary>The result of comparing one pasted link's fingerprint with the pinned one.</summary>
public sealed record SyncPairingReply
{
    /// <summary>Which of the three states the comparison ended in.</summary>
    public SyncPairingReplyState State { get; init; }

    /// <summary>Stable code of the link or fingerprint problem; null on success.</summary>
    public string? Reason { get; init; }

    /// <summary>The sentence the page shows.</summary>
    public string Message { get; init; } = "";

    /// <summary>The device id the link declared.</summary>
    public string? PeerDeviceId { get; init; }

    /// <summary>The fingerprint the link declared, full hex.</summary>
    public string? PeerFingerprint { get; init; }

    /// <summary>The fingerprint this machine pinned earlier, full hex.</summary>
    public string? PinnedFingerprint { get; init; }

    /// <summary>True when pinning is safe (new device, or a match that refreshes last-seen).</summary>
    public bool CanRegister { get; init; }

    /// <summary>The registry's stable reason for the comparison or the registration.</summary>
    public string? RegistrationReason { get; init; }

    /// <summary>True when this call wrote the registry.</summary>
    public bool Registered { get; init; }
}

/// <summary>One forget confirmation: what the dialog says and which device it is about.</summary>
public sealed record SyncForgetPrompt
{
    /// <summary>Dialog title.</summary>
    public string Title { get; init; } = SyncPageCopy.ForgetTitle;

    /// <summary>The sentence naming the device and what forgetting keeps.</summary>
    public string Text { get; init; } = "";

    /// <summary>Accept button text.</summary>
    public string ConfirmLabel { get; init; } = SyncPageCopy.ForgetConfirmLabel;

    /// <summary>Cancel button text.</summary>
    public string CancelLabel { get; init; } = SyncPageCopy.ForgetCancelLabel;

    /// <summary>Accessible name of the accept button.</summary>
    public string ConfirmName { get; init; } = SyncPageCopy.ForgetConfirmName;

    /// <summary>Accessible name of the cancel button.</summary>
    public string CancelName { get; init; } = SyncPageCopy.ForgetCancelName;

    /// <summary>The device the confirmation is about.</summary>
    public string DeviceId { get; init; } = "";
}

/// <summary>The outcome of a forget confirmation, ready to become an InfoBar.</summary>
public sealed record SyncForgetOutcome
{
    /// <summary>True when a row was actually deleted.</summary>
    public bool Forgotten { get; init; }

    /// <summary>True when the peer's sync baseline was cleared too (false without a store or when the write failed).</summary>
    public bool BaselineForgotten { get; init; }

    /// <summary>InfoBar title.</summary>
    public string Title { get; init; } = "";

    /// <summary>InfoBar message, saying what was and was not touched.</summary>
    public string Message { get; init; } = "";

    /// <summary>Stable reason when nothing was forgotten.</summary>
    public string? Reason { get; init; }
}

/// <summary>
/// The pairing section's decidable logic: an offline invitation, the three-state comparison, and forgetting.
/// </summary>
/// <remarks>
/// <para>
/// Nothing here opens a socket. An invitation created by this type is an offline link: it names this device and its
/// fingerprint, and the candidate LAN addresses are shown beside it so a user can tell a peer where this machine
/// lives. Binding one of those addresses would make Windows ask the user to open the firewall, and the whole point
/// of the loopback-only transport is that it never does.
/// </para>
/// <para>
/// The three states exist so that a fingerprint mismatch is loud: the registry never overwrites a pinned
/// fingerprint, and this presenter never calls it in a way that could — a mismatch is reported and left alone.
/// </para>
/// </remarks>
public sealed class SyncPairingPresenter
{
    private readonly PairedDeviceRegistry? _registry;
    private readonly ISourceSyncBaselineStore? _baselines;
    private PairedDeviceRow? _pendingForget;
    private PairingAddressReport? _addresses;

    /// <summary>Creates the presenter for one device; the registry and baseline store are optional only so tests can omit them.</summary>
    /// <param name="deviceId">This device's id.</param>
    /// <param name="registry">Paired-device bookkeeping; null means the forget confirmation cannot delete anything.</param>
    /// <param name="baselines">
    /// Peer sync baselines (W6-80). Forgetting a device must drop its baseline as well: a leftover baseline makes the
    /// next pairing of the same device id start at "already known" and skip the peer's real changes.
    /// </param>
    public SyncPairingPresenter(
        string deviceId,
        PairedDeviceRegistry? registry = null,
        ISourceSyncBaselineStore? baselines = null)
    {
        DeviceId = string.IsNullOrWhiteSpace(deviceId) ? "" : deviceId.Trim();
        _registry = registry;
        _baselines = baselines;
    }

    /// <summary>The baseline store this presenter clears on forget; null when the caller supplied none.</summary>
    public ISourceSyncBaselineStore? Baselines => _baselines;

    /// <summary>This device's id, as it appears in an invitation.</summary>
    public string DeviceId { get; }

    /// <summary>The registry this presenter pins into; null when the caller supplied none.</summary>
    public PairedDeviceRegistry? Registry => _registry;

    /// <summary>The last enumerated addresses, or null.</summary>
    public PairingAddressReport? Addresses => _addresses;

    /// <summary>True while a forget confirmation is waiting for an answer.</summary>
    public bool IsForgetPending => _pendingForget is not null;

    /// <summary>The row the pending confirmation is about.</summary>
    public PairedDeviceRow? PendingForget => _pendingForget;

    /// <summary>Replaces the addresses shown beside the invitation; display only.</summary>
    public void SetAddresses(PairingAddressReport? report) => _addresses = report;

    /// <summary>
    /// Builds an offline invitation. The link's URL is always the loopback placeholder, because this machine is not
    /// listening on anything else; the LAN addresses appear in <see cref="SyncPairingInvitation.AddressLines"/> only.
    /// </summary>
    public SyncPairingInvitation CreateInvitation(long nowEpochMs, string? ephemeralUrl = null)
    {
        PairingSelfIdentity identity = PairingSelfIdentity.Create(DeviceId);
        PairingPayload payload = PairingPayload.Create(
            DeviceId,
            string.IsNullOrWhiteSpace(ephemeralUrl) ? LoopbackPlaceholderUrl : ephemeralUrl.Trim(),
            PairingChallenge.Create(),
            identity.Fingerprint,
            nowEpochMs);

        return new SyncPairingInvitation
        {
            Link = PairingLink.Create(payload),
            DeviceId = payload.DeviceId,
            FingerprintHex = payload.PublicKeyFingerprint,
            FingerprintDisplay = identity.Fingerprint.ToDisplayString(),
            ExpiresAtEpochMs = payload.ExpiresAtEpochMs,
            Listens = false,
            AddressLines = AddressLines(),
        };
    }

    /// <summary>The addresses shown beside an invitation, best first, with the interface that owns each.</summary>
    public IReadOnlyList<string> AddressLines()
    {
        if (_addresses is null)
        {
            return [];
        }

        var lines = new List<string>(_addresses.Candidates.Count);
        foreach (PairingAddressCandidate candidate in _addresses.Candidates)
        {
            lines.Add(candidate.Address + "（" + candidate.InterfaceName + " · "
                + DescribeAddressReason(candidate.Reason) + "）");
        }

        if (lines.Count == 0)
        {
            lines.Add(SyncPageCopy.AddressEmpty
                + "（" + (_addresses.Reason ?? PairingAddressReasons.NoUsableAddress) + "）");
        }

        return lines;
    }

    /// <summary>Compares one pasted link against what this machine pinned; optionally pins a new device.</summary>
    public SyncPairingReply VerifyReply(string? link, long nowEpochMs, bool register = false)
    {
        PairingLinkResult parsed = PairingLink.Parse(link, nowEpochMs);
        if (!parsed.Succeeded || parsed.Payload is not { } payload)
        {
            string reason = parsed.Reason ?? PairingCodeReasons.PayloadInvalid;
            return new SyncPairingReply
            {
                State = SyncPairingReplyState.Invalid,
                Reason = reason,
                Message = "配对链接无法识别（稳定码：" + reason + "）："
                    + (parsed.Detail ?? "链接不是本机能读的配对邀请。"),
            };
        }

        if (_registry is null)
        {
            return new SyncPairingReply
            {
                State = SyncPairingReplyState.Invalid,
                Reason = "registry-unavailable",
                Message = "本机没有可用的已配对设备登记，无法比对指纹。",
                PeerDeviceId = payload.DeviceId,
                PeerFingerprint = payload.PublicKeyFingerprint,
            };
        }

        PairedDeviceVerification verification = _registry.Verify(payload.DeviceId, payload.PublicKeyFingerprint);
        string presented = DescribeFingerprint(payload.PublicKeyFingerprint);
        string pinned = DescribeFingerprint(verification.PinnedFingerprint);

        if (verification.IsMismatch)
        {
            // Never register on a mismatch: overwriting the pin would silently accept an impostor.
            return new SyncPairingReply
            {
                State = SyncPairingReplyState.Mismatch,
                Reason = verification.Reason,
                Message = "对方声明的指纹与已登记的不同（已登记 " + pinned + "，本次 " + presented
                    + "）；本机不会覆盖已登记的指纹，请当面核对。",
                PeerDeviceId = payload.DeviceId,
                PeerFingerprint = payload.PublicKeyFingerprint,
                PinnedFingerprint = verification.PinnedFingerprint,
                CanRegister = false,
                RegistrationReason = verification.Reason,
            };
        }

        bool pin = register && verification.CanPin;
        PairedDeviceRegistration? registration = pin
            ? _registry.Register(new PairedDeviceObservation
            {
                DeviceId = payload.DeviceId,
                Fingerprint = payload.PublicKeyFingerprint,
                NowEpochMs = nowEpochMs,
            })
            : null;

        bool isNew = verification.IsNew;
        return new SyncPairingReply
        {
            State = isNew ? SyncPairingReplyState.New : SyncPairingReplyState.Match,
            Reason = verification.Reason,
            Message = isNew
                ? "本机没有登记过这台设备；确认后会按首次配对记录它的指纹（" + presented + "）。"
                : "与上次登记的指纹一致（" + pinned + "）：这台设备就是上次那台。",
            PeerDeviceId = payload.DeviceId,
            PeerFingerprint = payload.PublicKeyFingerprint,
            PinnedFingerprint = verification.PinnedFingerprint,
            CanRegister = verification.CanPin,
            Registered = registration?.Accepted ?? false,
            RegistrationReason = registration?.Reason ?? verification.Reason,
        };
    }

    /// <summary>Builds the paired-device list section; every sentence comes from the list presenter.</summary>
    public PairedDeviceListView BuildPairedDevices(long nowEpochMs) => PairedDeviceListPresenter.Build(
        _registry?.GetAll() ?? [],
        _registry?.Capacity ?? PairedDeviceRegistry.DefaultCapacity,
        nowEpochMs);

    /// <summary>Opens a forget confirmation for one row; null when no row was given.</summary>
    public SyncForgetPrompt? AskForget(PairedDeviceRow? row)
    {
        if (row is null)
        {
            return null;
        }

        _pendingForget = row;
        return new SyncForgetPrompt
        {
            Text = "将忘记「" + row.DisplayName + "」（" + row.FingerprintLabel + "）。" + SyncPageCopy.ForgetKeepsNote,
            DeviceId = row.DeviceId,
        };
    }

    /// <summary>Cancels a pending confirmation; the cancel is itself a receipt.</summary>
    public SyncForgetOutcome CancelForget()
    {
        bool had = _pendingForget is not null;
        _pendingForget = null;
        return new SyncForgetOutcome
        {
            Forgotten = false,
            Title = SyncPageCopy.CancelLabel,
            Message = had ? SyncPageCopy.ForgetCancelled : SyncPageCopy.ForgetNothingPending,
        };
    }

    /// <summary>
    /// Confirms the pending forget. Without a pending row nothing is deleted, so a stray click cannot remove a
    /// device the user is not looking at.
    /// </summary>
    public SyncForgetOutcome ConfirmForget()
    {
        if (_pendingForget is not { } row)
        {
            return new SyncForgetOutcome
            {
                Forgotten = false,
                Title = SyncPageCopy.ForgetUnknownTitle,
                Message = SyncPageCopy.ForgetNothingPending,
            };
        }

        _pendingForget = null;
        bool forgotten = _registry?.Forget(row.DeviceId) ?? false;
        if (!forgotten)
        {
            return new SyncForgetOutcome
            {
                Forgotten = false,
                Title = SyncPageCopy.ForgetUnknownTitle,
                Message = PairedDeviceRegistry.DescribeReason(PairedDeviceReasons.UnknownDevice),
                Reason = PairedDeviceReasons.UnknownDevice,
            };
        }

        // Registration first, baseline second. The other order would leave a window where the row is still listed
        // while its baseline is already gone - the next round would then look like a first sync.
        bool baselineForgotten = ForgetBaseline(row.DeviceId);
        return new SyncForgetOutcome
        {
            Forgotten = true,
            Title = SyncPageCopy.ForgetDoneTitle,
            Message = baselineForgotten
                ? SyncPageCopy.ForgetDoneMessage
                : SyncPageCopy.ForgetDoneMessageWithoutBaseline,
            BaselineForgotten = baselineForgotten,
        };
    }

    /// <summary>
    /// Drops the peer's sync baseline after its registration is gone. A store that throws (or is absent) only costs
    /// the baseline: the device stays forgotten and the receipt says the baseline was not cleared.
    /// </summary>
    private bool ForgetBaseline(string deviceId)
    {
        if (_baselines is null)
        {
            return false;
        }

        try
        {
            return _baselines.Forget(deviceId);
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Friendly label for an address-ranking reason.</summary>
    public static string DescribeAddressReason(string? reason) => reason switch
    {
        PairingAddressReasons.Wired => "有线",
        PairingAddressReasons.Wireless => "无线",
        PairingAddressReasons.Other => "其他接口",
        null or "" => "未说明",
        _ => reason,
    };

    /// <summary>The loopback placeholder a link carries when this machine is not reachable on the LAN.</summary>
    public const string LoopbackPlaceholderUrl = "http://127.0.0.1:0/";

    private static string DescribeFingerprint(string? fingerprint) =>
        string.IsNullOrWhiteSpace(fingerprint)
            ? "无指纹"
            : PairedDeviceListPresenter.FingerprintText(fingerprint);
}

/// <summary>
/// The sync page's presenter: the apply state machine and the pairing state machine behind one object.
/// </summary>
/// <remarks>
/// The page keeps controls, the picked file path and the transport it already had; every sentence, every state and
/// every decision belongs here, which is why the behaviour can be tested without a window.
/// </remarks>
public sealed class SyncPagePresenter
{
    /// <summary>Creates the presenter for one device.</summary>
    public SyncPagePresenter(
        string deviceId,
        PairedDeviceRegistry? registry = null,
        ISourceSyncBaselineStore? baselines = null)
    {
        DeviceId = string.IsNullOrWhiteSpace(deviceId) ? "" : deviceId.Trim();
        Pairing = new SyncPairingPresenter(DeviceId, registry, baselines);
    }

    /// <summary>This device's id.</summary>
    public string DeviceId { get; }

    /// <summary>The apply section's state machine.</summary>
    public SyncApplyPresenter Apply { get; } = new();

    /// <summary>The pairing section's state machine.</summary>
    public SyncPairingPresenter Pairing { get; }

    /// <summary>Builds the paired-device list section.</summary>
    public PairedDeviceListView BuildPairedDevices(long nowEpochMs) => Pairing.BuildPairedDevices(nowEpochMs);
}

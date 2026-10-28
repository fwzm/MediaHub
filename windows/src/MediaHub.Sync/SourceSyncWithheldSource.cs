using System.Globalization;

namespace MediaHub.Sync;

/// <summary>One entry the approval gate will never write, together with the reason it is held back.</summary>
/// <remarks>
/// The counts in <see cref="SourceSyncApplyReport"/> fold a gate-withheld entry into 保留未应用, which tells a user
/// that something was kept back but not what or why. Unlike <see cref="SourceSyncRefusedSource"/> the reason here is
/// the gate's own code — the gate decides before any writer runs, so there is no writer sentence to quote — and the
/// sentence is the same one the 「不可应用」 line already shows.
/// </remarks>
public sealed record SourceSyncWithheldSource
{
    /// <summary>Identity the peer sent; never blank for a planned entry.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>Title the peer stated, when it stated one.</summary>
    public string? DisplayName { get; init; }

    /// <summary>Stable reason code the gate withheld this entry for.</summary>
    public string Reason { get; init; } = "";

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"withheld-source(id={SyncSourceId}, reason={Reason})");
}

/// <summary>The one place the withheld list's words live, so the page and the tests assert the same sentence.</summary>
public static class SourceSyncWithheldText
{
    /// <summary>Sentence above the list; null when nothing was withheld, so an empty round keeps no heading.</summary>
    /// <remarks>
    /// The bracket is not decoration: these rows are exactly the ones an explicit 允许 cannot save, and a user who is
    /// not told that reads the button as broken.
    /// </remarks>
    public static string? Header(int count) => count <= 0
        ? null
        : "保留未应用 " + count.ToString(CultureInfo.InvariantCulture) + " 条（扣住，点允许也不会写）";

    /// <summary>One row: title and identity when both are known, and the reason; never invents a part.</summary>
    public static string Line(SourceSyncWithheldSource entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        string identity = entry.SyncSourceId.Trim();
        string title = entry.DisplayName?.Trim() ?? "";
        string subject = title.Length == 0
            ? identity.Length == 0 ? "（未知身份）" : identity
            : identity.Length == 0 ? title : title + "（" + identity + "）";
        return "- " + subject + " · " + Reason(entry.Reason);
    }

    /// <summary>
    /// The reason as the sentence the 「不可应用」 line already uses, so one round never explains the same code in two
    /// different words. Unknown codes come back verbatim and blank ones become an explicit sentence, never an empty tail.
    /// </summary>
    public static string Reason(string? reason) => SyncApplyPresenter.DescribeGateReason(reason);

    /// <summary>Every row, in the order the gate withheld them.</summary>
    public static IReadOnlyList<string> Lines(IReadOnlyList<SourceSyncWithheldSource> entries)
    {
        ArgumentNullException.ThrowIfNull(entries);
        var lines = new List<string>(entries.Count);
        foreach (SourceSyncWithheldSource entry in entries)
        {
            lines.Add(Line(entry));
        }

        return lines;
    }

    /// <summary>W6-93: label on the row button that puts the peer's identity on the clipboard; the same sentence Android uses.</summary>
    public const string CopyIdentity = "复制身份";

    /// <summary>Label a row shows when the peer stated no identity, so a disabled button explains itself.</summary>
    public const string NoCopyableIdentity = "无身份可复制";

    /// <summary>
    /// W6-93: what a user can still do about a withheld entry. The reason sentence says what happened; this one says
    /// where the way out is — a user who reads only 「扣住」 cannot tell whether the button is broken.
    /// </summary>
    /// <remarks>
    /// Unknown codes return null: inventing advice is worse than saying nothing. The wording is the one Android's
    /// SourceSyncImportCopy.nextStepOfCode already uses, so one code is never explained in two different words.
    /// </remarks>
    public static string? NextStep(string? code)
    {
        string value = (code ?? "").Trim();
        if (value.Length == 0)
        {
            return null;
        }

        if (value.StartsWith(SourceSyncApprovalGate.AmbiguousReasonPrefix, StringComparison.Ordinal))
        {
            return "下一步：本机有多条来源指向同一身份，先在来源页删掉多余的再重新导出。";
        }

        return value switch
        {
            SyncRoundReasons.SecretsRequireApproval =>
                "下一步：在对端把这条来源的凭据清空后重新导出信封，或在本机手动新建一条同样的来源。",
            SyncRoundReasons.NotSupportedOnWindows =>
                "下一步：等本机版本支持这种来源类型；再点几次允许也不会进来。",
            SyncRoundReasons.UnknownSourceType =>
                "下一步：把两端升到同一版本后重新导出信封。",
            SyncRoundReasons.SameRevisionDifferentContent =>
                "下一步：这条两端内容不一致（版本号相同），本页一律不放行；请在来源页手工对齐后再导出。",
            _ => null,
        };
    }

    /// <summary>
    /// W6-93: every withheld entry as the page needs it, in gate order — one list with three facts per row.
    /// </summary>
    /// <remarks>
    /// W6-92 shipped a list of ready-made sentences and the page bound it directly; a copy button needs the identity
    /// as a payload, not as text the user cannot select. Rather than keep a second parallel list (which drifts), the
    /// row now carries the sentence, the way out and the payload together — same reason Android retired appliedTitles.
    /// </remarks>
    public static IReadOnlyList<SourceSyncWithheldRow> Rows(IReadOnlyList<SourceSyncWithheldSource> entries)
    {
        ArgumentNullException.ThrowIfNull(entries);
        var rows = new List<SourceSyncWithheldRow>(entries.Count);
        foreach (SourceSyncWithheldSource entry in entries)
        {
            string identity = entry.SyncSourceId.Trim();
            bool canCopy = identity.Length > 0;
            rows.Add(new SourceSyncWithheldRow
            {
                Text = Line(entry),
                NextStep = NextStep(entry.Reason) ?? "",
                CopyText = canCopy ? identity : "",
                CanCopy = canCopy,
            });
        }

        return rows;
    }
}

/// <summary>One row of the withheld list, ready to bind: the sentence, the way out and the identity to copy.</summary>
public sealed record SourceSyncWithheldRow
{
    /// <summary>The 「- 标题（身份） · 原因」 line.</summary>
    public required string Text { get; init; }

    /// <summary>Sentence shown under <see cref="Text"/>; empty when the code has no known way out.</summary>
    public string NextStep { get; init; } = "";

    /// <summary>Payload the copy button writes to the clipboard; empty when the peer stated no identity.</summary>
    public string CopyText { get; init; } = "";

    /// <summary>False when there is no identity, so the button is disabled rather than copying an invention.</summary>
    public bool CanCopy { get; init; }

    /// <summary>Button label; names why it cannot be used instead of leaving a dead button unexplained.</summary>
    public string CopyLabel => CanCopy
        ? SourceSyncWithheldText.CopyIdentity
        : SourceSyncWithheldText.NoCopyableIdentity;
}

using System.Globalization;

namespace MediaHub.Sync;

/// <summary>One entry an apply round deliberately did not write, together with what the writer said.</summary>
/// <remarks>
/// The counts in <see cref="SourceSyncApplyReport"/> fold a refused write into 保留未应用, which tells a user that
/// something was kept back but not what or why. This record carries the row, and the reason is the writer's own
/// sentence (an unsupported source type, an unusable address, a storage failure) rather than a code, because that
/// sentence is already written for a person to read. It never carries a credential: a refusal message is a log line too.
/// </remarks>
public sealed record SourceSyncRefusedSource
{
    /// <summary>Identity the peer sent; never blank for a planned entry.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>Title the peer stated, when it stated one.</summary>
    public string? DisplayName { get; init; }

    /// <summary>What the write would have done: create, update, adopt or delete.</summary>
    public SourceSyncAction Action { get; init; } = SourceSyncAction.Create;

    /// <summary>What the writer said when it refused; blank means it said nothing.</summary>
    public string Reason { get; init; } = "";

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"refused-source(id={SyncSourceId}, action={Action})");
}

/// <summary>The one place the refused list's words live, so the page and the tests assert the same sentence.</summary>
public static class SourceSyncRefusedText
{
    /// <summary>Sentence above the list; null when nothing was refused, so an empty round keeps no heading.</summary>
    public static string? Header(int count) => count <= 0
        ? null
        : "未写入 " + count.ToString(CultureInfo.InvariantCulture) + " 条（逐条原因）";

    /// <summary>Human word for what the refused entry would have done.</summary>
    public static string DescribeAction(SourceSyncAction action) => action switch
    {
        SourceSyncAction.Delete => "删除",
        SourceSyncAction.Adopt => "接管",
        SourceSyncAction.Update => "更新",
        _ => "写入",
    };

    /// <summary>One row: title and identity when both are known, the action, and the reason; never invents a part.</summary>
    public static string Line(SourceSyncRefusedSource entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        string identity = entry.SyncSourceId.Trim();
        string title = entry.DisplayName?.Trim() ?? "";
        string subject = title.Length == 0
            ? identity.Length == 0 ? "（未知身份）" : identity
            : identity.Length == 0 ? title : title + "（" + identity + "）";
        return "- " + subject + " · " + DescribeAction(entry.Action) + " · " + Reason(entry.Reason);
    }

    /// <summary>
    /// The reason as a whole sentence. A writer that refused without saying why gets an explicit sentence instead of
    /// a dangling separator: an empty tail reads like a broken page and loses the one fact the row exists for.
    /// </summary>
    public static string Reason(string? reason) => string.IsNullOrWhiteSpace(reason)
        ? "写入器没有说明原因"
        : reason.Trim();

    /// <summary>
    /// W6-95: every refused entry as the page needs it, in the order the round was refused — one shape for the row.
    /// </summary>
    /// <remarks>
    /// The copy button needs the peer identity as a payload, and the withheld list binds the same button, so both
    /// lists hand back <see cref="SourceSyncCopyRow"/> instead of strings. No way out is invented here: the reason is
    /// already the writer's own whole sentence, and a refusal is not something a user can clear from this page.
    /// </remarks>
    public static IReadOnlyList<SourceSyncCopyRow> Rows(IReadOnlyList<SourceSyncRefusedSource> entries)
    {
        ArgumentNullException.ThrowIfNull(entries);
        var rows = new List<SourceSyncCopyRow>(entries.Count);
        foreach (SourceSyncRefusedSource entry in entries)
        {
            ArgumentNullException.ThrowIfNull(entry);
            string identity = entry.SyncSourceId.Trim();
            bool canCopy = identity.Length > 0;
            rows.Add(new SourceSyncCopyRow
            {
                Text = Line(entry),
                CopyText = canCopy ? identity : "",
                CanCopy = canCopy,
            });
        }

        return rows;
    }
}

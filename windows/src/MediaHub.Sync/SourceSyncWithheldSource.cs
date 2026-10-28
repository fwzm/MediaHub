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
}

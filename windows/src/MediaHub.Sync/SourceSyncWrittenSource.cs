using System.Globalization;

namespace MediaHub.Sync;

/// <summary>One entry a round really wrote, with the identity the peer knows it by.</summary>
/// <remarks>
/// The counts in <see cref="SourceSyncApplyReport"/> say how many rows landed; this record says which ones, so
/// 「已写入 2 条」 can be checked row by row instead of trusted. The local id is present only when the write path
/// knew it (an adopted source, or an update of one the mirror already carried); a create that mints a new local
/// id leaves it null rather than inventing one.
/// </remarks>
public sealed record SourceSyncWrittenSource
{
    /// <summary>Identity the peer sent; never blank for a written entry.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>Local server id when the write path knew it, else null.</summary>
    public string? ServerId { get; init; }

    /// <summary>Title the peer stated, when it stated one.</summary>
    public string? DisplayName { get; init; }

    /// <summary>What the write did: create, update, adopt or delete.</summary>
    public SourceSyncAction Action { get; init; } = SourceSyncAction.Create;

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"written-source(id={SyncSourceId}, server={ServerId ?? "unknown"}, action={Action})");
}

/// <summary>The one place the written list's words live, so the page and the tests assert the same sentence.</summary>
public static class SourceSyncWrittenText
{
    /// <summary>Sentence above the list; null when nothing was written, so an empty round keeps no heading.</summary>
    public static string? Header(int count) => count <= 0
        ? null
        : "已写入 " + count.ToString(CultureInfo.InvariantCulture) + " 条（按对端身份，可逐条核对）";

    /// <summary>One row: title and identity when both are known; never invents the half that is missing.</summary>
    public static string Line(SourceSyncWrittenSource entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        string identity = entry.SyncSourceId.Trim();
        string title = entry.DisplayName?.Trim() ?? "";
        string subject = title.Length == 0
            ? identity
            : identity.Length == 0 ? title : title + "（" + identity + "）";
        return entry.Action == SourceSyncAction.Delete
            ? "- " + subject + " · 已从本机移除"
            : "- " + subject;
    }

    /// <summary>Every row, in the order the round wrote them.</summary>
    public static IReadOnlyList<string> Lines(IReadOnlyList<SourceSyncWrittenSource> entries)
    {
        ArgumentNullException.ThrowIfNull(entries);
        var lines = new List<string>(entries.Count);
        foreach (SourceSyncWrittenSource entry in entries)
        {
            lines.Add(Line(entry));
        }

        return lines;
    }
}

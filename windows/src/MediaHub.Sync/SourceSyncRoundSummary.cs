using System.Globalization;
using System.Linq;

namespace MediaHub.Sync;

/// <summary>The one place the round-summary log line's words live (W6-102).</summary>
/// <remarks>
/// The page already shows the counts, and W6-90/91/92 turned each of them into rows a user can compare. What a
/// diagnostic log still lacked was the single line that answers "which peer, which snapshot, and how much of it
/// landed" — the Android side got that in W6-101. This line therefore carries identifiers and counts only: a
/// summary is read on a bug report, where a display name or an address is a leak the redactor would have to undo.
/// </remarks>
public static class SourceSyncRoundSummaryText
{
    /// <summary>Identity segment; only the halves that are present, and it never invents an id.</summary>
    public static string Identity(string? deviceId, string? snapshotId)
    {
        string device = (deviceId ?? string.Empty).Trim();
        string snapshot = (snapshotId ?? string.Empty).Trim();
        if (device.Length == 0 && snapshot.Length == 0)
        {
            return "对端与快照未知";
        }

        var parts = new List<string>(2);
        if (device.Length > 0)
        {
            parts.Add("对端 " + device);
        }

        if (snapshot.Length > 0)
        {
            parts.Add("快照 " + snapshot);
        }

        return string.Join(" · ", parts);
    }

    /// <summary>The one info line a finished round leaves behind; never null, never carries a name or an address.</summary>
    public static string InfoLine(string? deviceId, string? snapshotId, SourceSyncApplyReport report)
    {
        ArgumentNullException.ThrowIfNull(report);
        int removed = report.Written.Count(entry => entry.Action == SourceSyncAction.Delete);
        return "已应用对端信封：" + Identity(deviceId, snapshotId)
            + " · 写入 " + report.Applied.ToString(CultureInfo.InvariantCulture) + " 条"
            + " · 移除 " + removed.ToString(CultureInfo.InvariantCulture) + " 条"
            + " · 跳过 " + report.Skipped.ToString(CultureInfo.InvariantCulture) + " 条"
            + " · 保留未应用 " + report.Withheld.ToString(CultureInfo.InvariantCulture) + " 条（扣住 "
            + report.WithheldEntries.Count.ToString(CultureInfo.InvariantCulture) + " · 写入被拒 "
            + report.Refused.Count.ToString(CultureInfo.InvariantCulture) + "）"
            + " · 本机基线" + (report.BaselineUpdated ? "已更新" : "未变化")
            + "（不含任何凭据）";
    }

    /// <summary>The warning that precedes the info line when something was kept back; null when nothing was.</summary>
    public static string? WarnLine(SourceSyncApplyReport report)
    {
        ArgumentNullException.ThrowIfNull(report);
        return report.Withheld <= 0
            ? null
            : "应用对端信封时有 " + report.Withheld.ToString(CultureInfo.InvariantCulture)
                + " 条保留未应用（含凭据或写入被拒）";
    }
}

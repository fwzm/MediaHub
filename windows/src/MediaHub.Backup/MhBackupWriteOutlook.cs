namespace MediaHub.Backup;

/// <summary>写回目标属于哪一类记录。</summary>
public enum MhBackupRecordKind
{
    Progress,
    Favorite,
}

/// <summary>
/// 一条记录最终能落到哪里。
/// </summary>
/// <remarks>
/// Windows 侧没有「按条目」的播放进度库（PlaybackHistoryStore 是文件路径键），收藏也只有服务端接口，
/// 所以「播放进度/收藏落库」不是一句「写本机」能交代的：必须逐条说明去向，否则用户会以为恢复成功了。
/// 这里只做分类，不发起任何请求，也不写任何文件。
/// </remarks>
public enum MhBackupWriteKind
{
    /// <summary>服务端保存这条记录；写回需要登录后调用来源接口。</summary>
    ServerApi,

    /// <summary>本机观看记录按文件路径记账，而备份里的条目 id 不是本机路径。</summary>
    LocalPathKey,

    /// <summary>该来源类型没有这类记录的写回接口。</summary>
    UnsupportedType,

    /// <summary>恢复计划裁决为不导入（冲突、本机更新），因此没有写回目标。</summary>
    NotSelected,

    /// <summary>引用了备份内不存在的来源。</summary>
    Orphan,
}

public sealed record MhBackupWriteTarget(
    MhBackupRecordKind Record,
    MhBackupWriteKind Kind,
    string ServerBackupId,
    string? ServerType,
    string ItemId,
    string Reason)
{
    /// <summary>稳定代码，供 JSON 回执与脚本判断，不随文案变化。</summary>
    public string KindCode => Kind switch
    {
        MhBackupWriteKind.ServerApi => "server-api",
        MhBackupWriteKind.LocalPathKey => "local-path-key",
        MhBackupWriteKind.UnsupportedType => "unsupported-type",
        MhBackupWriteKind.NotSelected => "not-selected",
        _ => "orphan",
    };
}

/// <summary>一份备份里播放记录与收藏的写回去向汇总。</summary>
public sealed class MhBackupWriteOutlook
{
    public required IReadOnlyList<MhBackupWriteTarget> Targets { get; init; }

    public IReadOnlyList<string> Lines { get; init; } = Array.Empty<string>();

    public int ProgressTotal => Targets.Count(t => t.Record == MhBackupRecordKind.Progress);

    public int FavoriteTotal => Targets.Count(t => t.Record == MhBackupRecordKind.Favorite);

    public int ProgressServerApi => Count(MhBackupRecordKind.Progress, MhBackupWriteKind.ServerApi);

    public int ProgressLocalPathKey => Count(MhBackupRecordKind.Progress, MhBackupWriteKind.LocalPathKey);

    public int ProgressUnsupportedType => Count(MhBackupRecordKind.Progress, MhBackupWriteKind.UnsupportedType);

    public int FavoriteServerApi => Count(MhBackupRecordKind.Favorite, MhBackupWriteKind.ServerApi);

    public int FavoriteUnsupportedType => Count(MhBackupRecordKind.Favorite, MhBackupWriteKind.UnsupportedType);

    public int NotSelected => Count(MhBackupRecordKind.Progress, MhBackupWriteKind.NotSelected)
        + Count(MhBackupRecordKind.Favorite, MhBackupWriteKind.NotSelected);

    public int Orphan => Count(MhBackupRecordKind.Progress, MhBackupWriteKind.Orphan)
        + Count(MhBackupRecordKind.Favorite, MhBackupWriteKind.Orphan);

    private int Count(MhBackupRecordKind record, MhBackupWriteKind kind)
        => Targets.Count(t => t.Record == record && t.Kind == kind);
}

/// <summary>把恢复计划的逐条裁决翻成「这条记录最终去哪」的分类。</summary>
public static class MhBackupWriteOutlooks
{
    private static readonly HashSet<string> ServerApiTypes = new(StringComparer.Ordinal)
    {
        "EMBY",
        "JELLYFIN",
    };

    private const string LocalPathReason =
        "本地文件夹条目的 id 不是本机文件路径（Windows 观看记录按路径记账），无法映射";

    private const string ProgressServerApiReason =
        "这条播放进度由服务端保存，写回需要登录后调用服务端接口（本命令不执行写回）";

    private const string FavoriteServerApiReason =
        "这条收藏由服务端保存，写回需要登录后调用服务端接口（本命令不执行写回）";

    public static MhBackupWriteOutlook Classify(MhBackupPayload payload, MhBackupRestorePlan plan)
    {
        var typeByBackupId = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (MhBackupServerDto server in payload.Servers ?? new List<MhBackupServerDto>())
        {
            if (!string.IsNullOrEmpty(server.BackupId)) typeByBackupId[server.BackupId!] = NormalizeType(server.Type);
        }

        var targets = new List<MhBackupWriteTarget>(plan.Progress.Count + plan.Favorites.Count);
        foreach (MhBackupProgressDecision decision in plan.Progress)
        {
            targets.Add(Target(MhBackupRecordKind.Progress, decision.Progress.ServerBackupId,
                decision.Progress.ItemId, decision.Verdict, decision.Reason, typeByBackupId));
        }

        foreach (MhBackupFavoriteDecision decision in plan.Favorites)
        {
            targets.Add(Target(MhBackupRecordKind.Favorite, decision.Favorite.ServerBackupId,
                decision.Favorite.ItemId, decision.Verdict, decision.Reason, typeByBackupId));
        }

        return new MhBackupWriteOutlook { Targets = targets, Lines = BuildLines(targets) };
    }

    private static MhBackupWriteTarget Target(
        MhBackupRecordKind record,
        string? serverBackupId,
        string? itemId,
        MhBackupRecordVerdict verdict,
        string planReason,
        Dictionary<string, string?> typeByBackupId)
    {
        string backupId = serverBackupId ?? string.Empty;
        string item = itemId ?? string.Empty;
        typeByBackupId.TryGetValue(backupId, out string? type);

        if (verdict == MhBackupRecordVerdict.Orphan)
            return new(record, MhBackupWriteKind.Orphan, backupId, type, item, planReason);
        if (verdict != MhBackupRecordVerdict.Apply)
            return new(record, MhBackupWriteKind.NotSelected, backupId, type, item, planReason);

        if (record == MhBackupRecordKind.Progress && type == "LOCAL")
            return new(record, MhBackupWriteKind.LocalPathKey, backupId, type, item, LocalPathReason);

        if (type is not null && ServerApiTypes.Contains(type))
        {
            string reason = record == MhBackupRecordKind.Progress ? ProgressServerApiReason : FavoriteServerApiReason;
            return new(record, MhBackupWriteKind.ServerApi, backupId, type, item, reason);
        }

        string label = string.IsNullOrEmpty(type) ? "（类型未知）" : type!;
        string unsupported = record == MhBackupRecordKind.Progress
            ? "来源类型 " + label + " 没有播放进度写回接口"
            : "来源类型 " + label + " 没有收藏写回接口";
        return new(record, MhBackupWriteKind.UnsupportedType, backupId, type, item, unsupported);
    }

    private static string? NormalizeType(string? type)
    {
        if (string.IsNullOrWhiteSpace(type)) return null;
        return type.Trim().ToUpperInvariant();
    }

    private static IReadOnlyList<string> BuildLines(List<MhBackupWriteTarget> targets)
    {
        var lines = new List<string>(2);
        int progress = targets.Count(t => t.Record == MhBackupRecordKind.Progress);
        if (progress > 0)
        {
            lines.Add("播放记录 " + progress + " 条写回方式：服务端接口 "
                + Count(targets, MhBackupRecordKind.Progress, MhBackupWriteKind.ServerApi) + " 条（需登录）· "
                + "本机路径键无法映射 " + Count(targets, MhBackupRecordKind.Progress, MhBackupWriteKind.LocalPathKey) + " 条 · "
                + "该类型没有接口 " + Count(targets, MhBackupRecordKind.Progress, MhBackupWriteKind.UnsupportedType) + " 条 · "
                + "未选中 " + Count(targets, MhBackupRecordKind.Progress, MhBackupWriteKind.NotSelected) + " 条 · "
                + "孤立引用 " + Count(targets, MhBackupRecordKind.Progress, MhBackupWriteKind.Orphan) + " 条");
        }

        int favorites = targets.Count(t => t.Record == MhBackupRecordKind.Favorite);
        if (favorites > 0)
        {
            lines.Add("收藏 " + favorites + " 条写回方式：服务端接口 "
                + Count(targets, MhBackupRecordKind.Favorite, MhBackupWriteKind.ServerApi) + " 条（需登录）· "
                + "该类型没有接口 " + Count(targets, MhBackupRecordKind.Favorite, MhBackupWriteKind.UnsupportedType) + " 条 · "
                + "未选中 " + Count(targets, MhBackupRecordKind.Favorite, MhBackupWriteKind.NotSelected) + " 条 · "
                + "孤立引用 " + Count(targets, MhBackupRecordKind.Favorite, MhBackupWriteKind.Orphan) + " 条");
        }

        return lines;
    }

    private static int Count(List<MhBackupWriteTarget> targets, MhBackupRecordKind record, MhBackupWriteKind kind)
        => targets.Count(t => t.Record == record && t.Kind == kind);
}

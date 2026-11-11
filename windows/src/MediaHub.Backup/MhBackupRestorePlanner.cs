using System.Text;

namespace MediaHub.Backup;

/// <summary>恢复策略（与 Android RestoreStrategy 同义）。</summary>
public enum MhBackupRestoreStrategy
{
    /// <summary>合并：新来源写入，同 ID 同身份来源保留本机，同 ID 不同身份列为冲突并跳过。</summary>
    Merge,

    /// <summary>替换所选：备份里出现的同 ID 来源一律用备份内容覆盖（本机其余来源不动）。</summary>
    ReplaceSelected,
}

/// <summary>
/// 本机已有的一个媒体源，只取身份裁决需要的字段（不读凭据、不读数据库）。
/// </summary>
public sealed class MhBackupLocalSource
{
    /// <summary>本机来源 id；恢复时它就是备份里的 backupId（Android 的 idRemapping 恒为恒等映射）。</summary>
    public required string BackupId { get; init; }

    /// <summary>来源类型；Windows 存 "Emby"/"Jellyfin"，线上是 "EMBY"/"JELLYFIN"，比较时按大小写不敏感处理。</summary>
    public required string Type { get; init; }

    /// <summary>本机当前生效线路的地址（可空串）。</summary>
    public string PrimaryUrl { get; init; } = string.Empty;

    /// <summary>登录账号，可为空；null 与空串不是同一账号（与 Android 一致）。</summary>
    public string? UserName { get; init; }
}

/// <summary>本机已有的一条播放记录，只取 newer-wins 需要的三个字段。</summary>
public sealed record MhBackupLocalProgress(string ServerBackupId, string ItemId, long UpdatedAtEpochMs);

/// <summary>备份内一个来源相对本机的裁决。</summary>
public enum MhBackupServerVerdict
{
    /// <summary>本机没有同 ID 来源：需要新增。</summary>
    New,

    /// <summary>本机有同 ID 且身份（类型 + 规范化主地址 + 账号）一致：合并模式保留本机。</summary>
    Identical,

    /// <summary>本机有同 ID 但身份不同：合并模式跳过并披露，绝不静默覆盖。</summary>
    Conflict,
}

/// <summary>一条播放记录 / 收藏记录的裁决。</summary>
public enum MhBackupRecordVerdict
{
    /// <summary>会写入本机。</summary>
    Apply,

    /// <summary>本机已有同键记录且不旧于备份：保留本机（newer-wins）。</summary>
    SkipNewerLocal,

    /// <summary>所属来源在同 ID 冲突里被跳过：记录也不导入。</summary>
    SkipConflict,

    /// <summary>引用了备份内不存在的来源（孤立引用）：跳过并计数。</summary>
    Orphan,
}

/// <summary>备份内一个来源的裁决结果。</summary>
public sealed record MhBackupServerDecision(MhBackupServerDto Server, MhBackupServerVerdict Verdict, string Reason)
{
    public string BackupId => Server.BackupId ?? string.Empty;

    public string Name => Server.Name ?? string.Empty;
}

/// <summary>一条播放记录的裁决结果；LocalServerBackupId 为写入目标（恒等于 backupId）。</summary>
public sealed record MhBackupProgressDecision(
    MhBackupProgressDto Progress,
    MhBackupRecordVerdict Verdict,
    string? LocalServerBackupId,
    string Reason);

/// <summary>一条收藏记录的裁决结果。</summary>
public sealed record MhBackupFavoriteDecision(
    MhBackupFavoriteDto Favorite,
    MhBackupRecordVerdict Verdict,
    string? LocalServerBackupId,
    string Reason);

/// <summary>
/// 恢复预览：把一份已解密的载荷与本机现状比出「新增 / 同源 / 冲突 / 孤立引用」，
/// 并给出每条记录落到哪里。纯函数、零写入 —— 只有调用方按此计划落库才会改变本机。
/// </summary>
/// <remarks>
/// 语义照 Android BackupRepository.buildPreview / buildPlanRecord / resolveProgress；
/// 差异只有一处并显式记录：Android 的 idRemapping 对所有来源都给恒等映射，
/// 本类型的 <see cref="IdRemapping"/> 对合并模式里被跳过的冲突来源给 null，
/// 让调用方在写进度/收藏时不可能绕过「冲突不导入」的裁决。
/// </remarks>
public sealed class MhBackupRestorePlan
{
    public required MhBackupRestoreStrategy Strategy { get; init; }

    public required IReadOnlyList<MhBackupServerDecision> Servers { get; init; }

    public required IReadOnlyList<MhBackupProgressDecision> Progress { get; init; }

    public required IReadOnlyList<MhBackupFavoriteDecision> Favorites { get; init; }

    /// <summary>备份里是否带设置段。</summary>
    public required bool PreferencesContained { get; init; }

    /// <summary>是否会写设置：照 Android，只有替换所选模式才恢复设置。</summary>
    public required bool PreferencesWillRestore { get; init; }

    /// <summary>backupId → 写入目标本机 id；合并模式里被跳过的冲突来源为 null。</summary>
    public required IReadOnlyDictionary<string, string?> IdRemapping { get; init; }

    public int NewCount => Servers.Count(s => s.Verdict == MhBackupServerVerdict.New);

    public int IdenticalCount => Servers.Count(s => s.Verdict == MhBackupServerVerdict.Identical);

    public int ConflictCount => Servers.Count(s => s.Verdict == MhBackupServerVerdict.Conflict);

    /// <summary>合并模式下被跳过的冲突来源名字（顺序同备份）。替换所选模式没有冲突名。</summary>
    public IReadOnlyList<string> ConflictServerNames => Strategy == MhBackupRestoreStrategy.Merge
        ? Servers.Where(s => s.Verdict == MhBackupServerVerdict.Conflict).Select(s => s.Name).ToList()
        : Array.Empty<string>();

    public int ProgressToWriteCount => Progress.Count(p => p.Verdict == MhBackupRecordVerdict.Apply);

    public int OrphanProgressCount => Progress.Count(p => p.Verdict == MhBackupRecordVerdict.Orphan);

    public int FavoritesToWriteCount => Favorites.Count(f => f.Verdict == MhBackupRecordVerdict.Apply);

    public int OrphanFavoriteCount => Favorites.Count(f => f.Verdict == MhBackupRecordVerdict.Orphan);

    /// <summary>会被新增或覆盖的来源数。</summary>
    public int ServersToWriteCount => Strategy == MhBackupRestoreStrategy.ReplaceSelected
        ? Servers.Count
        : NewCount;

    public bool HasWrites =>
        ServersToWriteCount > 0 || ProgressToWriteCount > 0 || FavoritesToWriteCount > 0 || PreferencesWillRestore;

    /// <summary>转成与 Android 同字段的 RestorePlanRecord（供日志、PR 证据与后续 CLI 使用）。</summary>
    public MhBackupRestorePlanRecord ToRecord() => new()
    {
        Strategy = Strategy == MhBackupRestoreStrategy.Merge ? "MERGE" : "REPLACE_SELECTED",
        SkipExistingServerIds = Servers
            .Where(s => Strategy == MhBackupRestoreStrategy.Merge && s.Verdict == MhBackupServerVerdict.Identical)
            .Select(s => s.BackupId).ToList(),
        OverwriteServerIds = Servers
            .Where(s => s.Verdict == MhBackupServerVerdict.New || Strategy == MhBackupRestoreStrategy.ReplaceSelected)
            .Select(s => s.BackupId).ToList(),
        ConflictSkippedServerIds = Servers
            .Where(s => Strategy == MhBackupRestoreStrategy.Merge && s.Verdict == MhBackupServerVerdict.Conflict)
            .Select(s => s.BackupId).ToList(),
        IdRemapping = IdRemapping
            .Where(pair => pair.Value is not null)
            .ToDictionary(pair => pair.Key, pair => pair.Value!, StringComparer.Ordinal),
        IncludePreferences = PreferencesWillRestore,
        RestoredProgressCount = ProgressToWriteCount,
    };

    /// <summary>给人看的逐条预览（顺序同备份），不用于机器解析。</summary>
    public IReadOnlyList<string> SummaryLines
    {
        get
        {
            var lines = new List<string>
            {
                "恢复策略：" + (Strategy == MhBackupRestoreStrategy.Merge ? "合并（保留本机同源）" : "替换所选"),
                "新增媒体源：" + NewCount,
            };
            if (Strategy == MhBackupRestoreStrategy.Merge)
            {
                lines.Add("已有同源：" + IdenticalCount + "（保留本机）");
                lines.Add("同 ID 不同来源冲突：" + ConflictCount
                    + (ConflictCount > 0 ? "（" + string.Join("、", ConflictServerNames) + "，已跳过）" : ""));
            }
            lines.Add("播放记录可写入：" + ProgressToWriteCount + " 条");
            if (OrphanProgressCount > 0)
                lines.Add("无法关联到备份内媒体源的播放记录：" + OrphanProgressCount + " 条（将跳过）");
            if (FavoritesToWriteCount > 0) lines.Add("收藏可写入：" + FavoritesToWriteCount + " 条");
            if (OrphanFavoriteCount > 0)
                lines.Add("无法关联到备份内媒体源的收藏：" + OrphanFavoriteCount + " 条（将跳过）");
            lines.Add(PreferencesContained
                ? (PreferencesWillRestore ? "设置：将恢复（仅替换所选模式恢复设置）" : "设置：备份包含设置，合并模式保留本机设置")
                : "设置：备份不含设置");
            return lines;
        }
    }
}

/// <summary>把备份载荷与本机现状比出恢复计划；纯函数，不读写任何文件。</summary>
public static class MhBackupRestorePlanner
{
    public static MhBackupRestorePlan Plan(
        MhBackupPayload payload,
        IReadOnlyList<MhBackupLocalSource> localSources,
        MhBackupRestoreStrategy strategy = MhBackupRestoreStrategy.Merge,
        IReadOnlyList<MhBackupLocalProgress>? localProgress = null)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentNullException.ThrowIfNull(localSources);

        var localById = new Dictionary<string, MhBackupLocalSource>(StringComparer.Ordinal);
        foreach (MhBackupLocalSource local in localSources)
        {
            if (local is null || string.IsNullOrEmpty(local.BackupId)) continue;
            if (!localById.TryAdd(local.BackupId, local)) continue; // 重复的本机 id 只有第一条参与裁决
        }

        var localProgressByKey = new Dictionary<(string, string), long>();
        foreach (MhBackupLocalProgress row in localProgress ?? Array.Empty<MhBackupLocalProgress>())
        {
            if (row is null || string.IsNullOrEmpty(row.ServerBackupId) || string.IsNullOrEmpty(row.ItemId)) continue;
            var key = (row.ServerBackupId, row.ItemId);
            if (!localProgressByKey.TryGetValue(key, out long existing) || existing < row.UpdatedAtEpochMs)
                localProgressByKey[key] = row.UpdatedAtEpochMs;
        }

        var serverDecisions = new List<MhBackupServerDecision>(payload.Servers.Count);
        var backupById = new Dictionary<string, MhBackupServerDto>(StringComparer.Ordinal);
        var remap = new Dictionary<string, string?>(StringComparer.Ordinal);

        foreach (MhBackupServerDto server in payload.Servers)
        {
            string? backupId = server.BackupId;
            if (string.IsNullOrEmpty(backupId))
            {
                serverDecisions.Add(new MhBackupServerDecision(server, MhBackupServerVerdict.Conflict,
                    "备份条目缺少 backupId，无法判断是否与本机同源"));
                continue;
            }

            if (!backupById.TryAdd(backupId, server))
            {
                serverDecisions.Add(new MhBackupServerDecision(server, MhBackupServerVerdict.Conflict,
                    "备份里有重复的 backupId，无法确定身份"));
                remap[backupId] = remap.TryGetValue(backupId, out string? first) ? first : null;
                continue;
            }

            if (!localById.TryGetValue(backupId, out MhBackupLocalSource? local))
            {
                serverDecisions.Add(new MhBackupServerDecision(server, MhBackupServerVerdict.New,
                    "本机没有同 ID 来源，将新增"));
                remap[backupId] = backupId;
                continue;
            }

            string? difference = DescribeIdentityDifference(local, server);
            if (difference is null)
            {
                serverDecisions.Add(new MhBackupServerDecision(server, MhBackupServerVerdict.Identical,
                    strategy == MhBackupRestoreStrategy.Merge
                        ? "本机已有同 ID 同身份来源，合并模式保留本机"
                        : "本机已有同 ID 同身份来源，替换所选模式将覆盖"));
                remap[backupId] = backupId;
            }
            else
            {
                serverDecisions.Add(new MhBackupServerDecision(server, MhBackupServerVerdict.Conflict, difference));
                remap[backupId] = strategy == MhBackupRestoreStrategy.Merge ? null : backupId;
            }
        }

        var progressDecisions = new List<MhBackupProgressDecision>(payload.Progress.Count);
        foreach (MhBackupProgressDto row in payload.Progress)
        {
            progressDecisions.Add(DecideProgress(row, serverDecisions, backupById, remap, strategy, localProgressByKey));
        }

        var favoriteDecisions = new List<MhBackupFavoriteDecision>(payload.Favorites.Count);
        foreach (MhBackupFavoriteDto row in payload.Favorites)
        {
            favoriteDecisions.Add(DecideFavorite(row, serverDecisions, backupById, remap, strategy));
        }

        return new MhBackupRestorePlan
        {
            Strategy = strategy,
            Servers = serverDecisions,
            Progress = progressDecisions,
            Favorites = favoriteDecisions,
            PreferencesContained = payload.Preferences is not null,
            PreferencesWillRestore = strategy == MhBackupRestoreStrategy.ReplaceSelected && payload.Preferences is not null,
            IdRemapping = remap,
        };
    }

    private static MhBackupProgressDecision DecideProgress(
        MhBackupProgressDto row,
        List<MhBackupServerDecision> servers,
        Dictionary<string, MhBackupServerDto> backupById,
        Dictionary<string, string?> remap,
        MhBackupRestoreStrategy strategy,
        Dictionary<(string, string), long> localProgressByKey)
    {
        string? serverBackupId = row.ServerBackupId;
        string? itemId = row.ItemId;
        if (string.IsNullOrEmpty(serverBackupId) || string.IsNullOrEmpty(itemId) || !backupById.ContainsKey(serverBackupId))
            return new MhBackupProgressDecision(row, MhBackupRecordVerdict.Orphan, null,
                "播放记录引用了备份内不存在的媒体源");

        if (!remap.TryGetValue(serverBackupId, out string? target) || target is null)
            return new MhBackupProgressDecision(row, MhBackupRecordVerdict.SkipConflict, null,
                "所属来源是同 ID 不同身份冲突，合并模式不导入该来源的播放记录");

        if (strategy == MhBackupRestoreStrategy.Merge && IsIdentical(servers, serverBackupId)
            && localProgressByKey.TryGetValue((target, itemId), out long localUpdated)
            && localUpdated >= row.UpdatedAtEpochMs)
        {
            return new MhBackupProgressDecision(row, MhBackupRecordVerdict.SkipNewerLocal, target,
                "本机已有更或同样新的播放记录，保留本机");
        }

        return new MhBackupProgressDecision(row, MhBackupRecordVerdict.Apply, target,
            "将写入本机播放记录");
    }

    private static MhBackupFavoriteDecision DecideFavorite(
        MhBackupFavoriteDto row,
        List<MhBackupServerDecision> servers,
        Dictionary<string, MhBackupServerDto> backupById,
        Dictionary<string, string?> remap,
        MhBackupRestoreStrategy strategy)
    {
        string? serverBackupId = row.ServerBackupId;
        if (string.IsNullOrEmpty(serverBackupId) || string.IsNullOrEmpty(row.ItemId)
            || !backupById.ContainsKey(serverBackupId))
            return new MhBackupFavoriteDecision(row, MhBackupRecordVerdict.Orphan, null,
                "收藏引用了备份内不存在的媒体源");

        if (!remap.TryGetValue(serverBackupId, out string? target) || target is null)
            return new MhBackupFavoriteDecision(row, MhBackupRecordVerdict.SkipConflict, null,
                "所属来源是同 ID 不同身份冲突，合并模式不导入该来源的收藏");

        return new MhBackupFavoriteDecision(row, MhBackupRecordVerdict.Apply, target,
            "将写入本机收藏（登录该来源后写回服务端）");
    }

    private static bool IsIdentical(List<MhBackupServerDecision> servers, string backupId) =>
        servers.Any(s => s.Verdict == MhBackupServerVerdict.Identical && s.BackupId == backupId);

    /// <summary>身份一致返回 null，否则返回具体差异（用于预览披露）。</summary>
    private static string? DescribeIdentityDifference(MhBackupLocalSource local, MhBackupServerDto incoming)
    {
        if (!SameType(local.Type, incoming.Type))
            return "同 ID 来源类型不同：本机 " + DescribeType(local.Type) + "，备份 " + DescribeType(incoming.Type);

        string localUrl = MhBackupUrlGuard.NormalizeForIdentity(local.PrimaryUrl);
        string backupUrl = MhBackupUrlGuard.NormalizeForIdentity(ActiveEndpointUrl(incoming));
        if (!string.Equals(localUrl, backupUrl, StringComparison.Ordinal))
            return "同 ID 来源主地址不同：本机 " + Display(localUrl) + "，备份 " + Display(backupUrl);

        if (!string.Equals(local.UserName, incoming.Username, StringComparison.Ordinal))
            return "同 ID 来源账号不同：本机 " + Display(local.UserName) + "，备份 " + Display(incoming.Username);

        return null;
    }

    /// <summary>生效线路地址：优先启用且主的线路，否则所有启用线路里排序最前的那条；全未启用返回空串。</summary>
    private static string ActiveEndpointUrl(MhBackupServerDto server)
    {
        List<MhBackupEndpointDto> enabled = server.Endpoints.Where(e => e.Enabled).ToList();
        if (enabled.Count == 0) return string.Empty;
        List<MhBackupEndpointDto> primaries = enabled.Where(e => e.IsPrimary).ToList();
        List<MhBackupEndpointDto> candidates = primaries.Count > 0 ? primaries : enabled;
        int minOrder = candidates.Min(e => e.SortOrder);
        return candidates.First(e => e.SortOrder == minOrder).Url ?? string.Empty;
    }

    private static bool SameType(string? local, string? incoming) =>
        string.Equals(NormalizeType(local), NormalizeType(incoming), StringComparison.Ordinal);

    /// <summary>Windows 存 "Emby"，线上写 "EMBY"：只按大小写与首尾空白归一，不做别名猜测。</summary>
    private static string NormalizeType(string? value) => (value ?? string.Empty).Trim().ToUpperInvariant();

    private static string DescribeType(string? value)
    {
        string normalized = NormalizeType(value);
        return normalized.Length == 0 ? "（未知类型）" : normalized;
    }

    private static string Display(string? value) => string.IsNullOrEmpty(value) ? "（空）" : value;
}

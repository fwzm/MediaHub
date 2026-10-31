namespace MediaHub.Backup;

/// <summary>一条写回请求最终怎么了。</summary>
/// <remarks>
/// 只有服务端的回报才算数：请求发出去不等于保存了。服务端忽略这次写入（回报的位置仍是旧值）、
/// 报错、干脆没有回报，三种情况分别落到这里的不同取值，调用方才能如实告诉用户。
/// </remarks>
public enum MhBackupWriteOutcome
{
    /// <summary>服务端回报已保存：进度位置与请求一致，或收藏回报为真。</summary>
    Written,

    /// <summary>请求发出去了，但服务端回报的状态与请求不一致（这次写入没有被采纳）。</summary>
    ServerDidNotStore,

    /// <summary>没有这个来源的登录会话，写回没有发出。</summary>
    NeedsLogin,

    /// <summary>写回过程报错，或备份里的值本身不足以往外写。</summary>
    Failed,

    /// <summary>这条记录本来就不属于服务端接口写回（原因见写回去向分类）。</summary>
    NotApplicable,
}

/// <summary>一次写回的服务端回报；三个字段各自可空，含义由记录类型决定。</summary>
public sealed record MhBackupWriteAnswer(long? ReportedPositionTicks, bool? ReportedFavorite, string? Failure)
{
    /// <summary>进度写回：服务端回报的保存后位置（ticks）。</summary>
    public static MhBackupWriteAnswer Position(long ticks) => new(ticks, null, null);

    /// <summary>收藏写回：服务端回报的收藏状态。</summary>
    public static MhBackupWriteAnswer Favorite(bool favorite) => new(null, favorite, null);

    /// <summary>请求没有做成，给出原因（例如 401、404 或服务端拒写）。</summary>
    public static MhBackupWriteAnswer Fail(string reason) => new(null, null, reason);
}

/// <summary>一个已登录来源的写回通道。</summary>
/// <remarks>
/// 抽象放在 MediaHub.Backup 里，是因为该程序集不引用 provider（BCL-only）；
/// 由上层（App 的设置页、或将来带凭据的入口）用 ProviderHandle.ProgressWrite / IProviderFavorites 适配。
/// 实现方必须返回服务端随后报告的答案，不得把请求值原样回声 —— 回声会把「接受后丢弃」虚报成已保存（见 W27）。
/// </remarks>
public interface IMhBackupServerWriter
{
    /// <summary>写入播放位置，返回服务端随后报告的位置。</summary>
    Task<MhBackupWriteAnswer> SavePlaybackPositionAsync(string itemId, long positionTicks, CancellationToken ct);

    /// <summary>把条目标为收藏，返回服务端随后报告的收藏状态。</summary>
    Task<MhBackupWriteAnswer> SetFavoriteAsync(string itemId, CancellationToken ct);
}

/// <summary>按备份里的来源 id 取已登录的写回通道；没有会话就返回 null。</summary>
public interface IMhBackupServerWriters
{
    IMhBackupServerWriter? ForServer(string serverBackupId);
}

/// <summary>一条记录写回的逐条结果。</summary>
public sealed record MhBackupWriteAttempt(
    MhBackupRecordKind Record,
    MhBackupWriteKind Outlook,
    string ServerBackupId,
    string ItemId,
    long? RequestedPositionTicks,
    MhBackupWriteOutcome Outcome,
    string Reason)
{
    /// <summary>记录类型的稳定代码（JSON 回执用）。</summary>
    public string RecordCode => Record == MhBackupRecordKind.Progress ? "progress" : "favorite";

    /// <summary>结果的稳定代码（JSON 回执与脚本判断用，不随文案变化）。</summary>
    public string OutcomeCode => Outcome switch
    {
        MhBackupWriteOutcome.Written => "written",
        MhBackupWriteOutcome.ServerDidNotStore => "server-did-not-store",
        MhBackupWriteOutcome.NeedsLogin => "needs-login",
        MhBackupWriteOutcome.Failed => "failed",
        _ => "not-applicable",
    };
}

/// <summary>一轮写回的汇总。</summary>
public sealed class MhBackupWritebackReport
{
    public required IReadOnlyList<MhBackupWriteAttempt> Attempts { get; init; }

    public IReadOnlyList<string> Lines { get; init; } = Array.Empty<string>();

    public int WrittenCount => Count(MhBackupWriteOutcome.Written);

    public int ServerDidNotStoreCount => Count(MhBackupWriteOutcome.ServerDidNotStore);

    public int NeedsLoginCount => Count(MhBackupWriteOutcome.NeedsLogin);

    public int FailedCount => Count(MhBackupWriteOutcome.Failed);

    public int NotApplicableCount => Count(MhBackupWriteOutcome.NotApplicable);

    private int Count(MhBackupWriteOutcome outcome) => Attempts.Count(a => a.Outcome == outcome);
}

/// <summary>
/// 把写回去向变成真正发出的写回请求，并只按服务端回报记账。
/// </summary>
/// <remarks>
/// 纯逻辑：不读文件、不读凭据、不构造 HTTP 客户端；所有网络动作都在 <see cref="IMhBackupServerWriter"/> 之后。
/// 写回去向里不是 server-api 的条目一律 NotApplicable，并原样保留分类给出的原因 ——
/// 「本机路径键无法映射」「该类型没有接口」「未选中」「孤立引用」不能被这一层悄悄改写成「已写」。
/// </remarks>
public static class MhBackupWritebacks
{
    private const int MaxReasonChars = 160;

    public static async Task<MhBackupWritebackReport> RunAsync(
        MhBackupRestorePlan plan,
        MhBackupWriteOutlook outlook,
        IMhBackupServerWriters? writers,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(plan);
        ArgumentNullException.ThrowIfNull(outlook);
        if (outlook.Targets.Count != plan.Progress.Count + plan.Favorites.Count)
        {
            throw new ArgumentException("写回去向与恢复计划的条数不一致", nameof(outlook));
        }

        var attempts = new List<MhBackupWriteAttempt>(outlook.Targets.Count);
        int index = 0;

        foreach (MhBackupProgressDecision decision in plan.Progress)
        {
            MhBackupWriteTarget target = outlook.Targets[index++];
            attempts.Add(await ProgressAsync(decision, target, writers, cancellationToken).ConfigureAwait(false));
        }

        foreach (MhBackupFavoriteDecision decision in plan.Favorites)
        {
            MhBackupWriteTarget target = outlook.Targets[index++];
            attempts.Add(await FavoriteAsync(decision, target, writers, cancellationToken).ConfigureAwait(false));
        }

        return new MhBackupWritebackReport { Attempts = attempts, Lines = BuildLines(attempts) };
    }

    private static async Task<MhBackupWriteAttempt> ProgressAsync(
        MhBackupProgressDecision decision,
        MhBackupWriteTarget target,
        IMhBackupServerWriters? writers,
        CancellationToken cancellationToken)
    {
        long positionMs = decision.Progress.PositionMs;
        long ticks = positionMs * 10_000L;

        MhBackupWriteAttempt NotApplicable(string reason) =>
            new(MhBackupRecordKind.Progress, target.Kind, target.ServerBackupId, target.ItemId, null,
                MhBackupWriteOutcome.NotApplicable, reason);

        if (target.Kind != MhBackupWriteKind.ServerApi) return NotApplicable(target.Reason);

        MhBackupWriteAttempt Make(MhBackupWriteOutcome outcome, string reason) =>
            new(MhBackupRecordKind.Progress, target.Kind, target.ServerBackupId, target.ItemId, ticks, outcome, reason);

        if (string.IsNullOrEmpty(target.ItemId))
            return Make(MhBackupWriteOutcome.Failed, "备份里这条播放记录没有条目 id，无法写回");
        if (positionMs < 0)
            return Make(MhBackupWriteOutcome.Failed, "备份里的播放位置是负数（" + positionMs + " 毫秒），不写回");

        IMhBackupServerWriter? writer = writers?.ForServer(target.ServerBackupId);
        if (writer is null)
            return Make(MhBackupWriteOutcome.NeedsLogin, "需要先登录这个来源才能写回；这次没有会话，请求没有发出");

        MhBackupWriteAnswer answer;
        try
        {
            answer = await writer.SavePlaybackPositionAsync(target.ItemId, ticks, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception failure)
        {
            return Make(MhBackupWriteOutcome.Failed, Describe(failure));
        }

        if (!string.IsNullOrWhiteSpace(answer.Failure))
            return Make(MhBackupWriteOutcome.Failed, Clip(answer.Failure!));
        if (answer.ReportedPositionTicks is not { } reported)
            return Make(MhBackupWriteOutcome.ServerDidNotStore, "服务端没有回报保存后的位置，无法确认这次写入");
        if (reported == ticks)
            return Make(MhBackupWriteOutcome.Written, "服务端回报的位置与请求一致（" + ticks + " ticks）");

        return Make(MhBackupWriteOutcome.ServerDidNotStore,
            "服务端回报的位置是 " + reported + " ticks，与请求的 " + ticks + " ticks 不同：这次写入没有被采纳");
    }

    private static async Task<MhBackupWriteAttempt> FavoriteAsync(
        MhBackupFavoriteDecision decision,
        MhBackupWriteTarget target,
        IMhBackupServerWriters? writers,
        CancellationToken cancellationToken)
    {
        MhBackupWriteAttempt NotApplicable(string reason) =>
            new(MhBackupRecordKind.Favorite, target.Kind, target.ServerBackupId, target.ItemId, null,
                MhBackupWriteOutcome.NotApplicable, reason);

        if (target.Kind != MhBackupWriteKind.ServerApi) return NotApplicable(target.Reason);

        MhBackupWriteAttempt Make(MhBackupWriteOutcome outcome, string reason) =>
            new(MhBackupRecordKind.Favorite, target.Kind, target.ServerBackupId, target.ItemId, null, outcome, reason);

        if (string.IsNullOrEmpty(target.ItemId))
            return Make(MhBackupWriteOutcome.Failed, "备份里这条收藏记录没有条目 id，无法写回");

        IMhBackupServerWriter? writer = writers?.ForServer(target.ServerBackupId);
        if (writer is null)
            return Make(MhBackupWriteOutcome.NeedsLogin, "需要先登录这个来源才能写回；这次没有会话，请求没有发出");

        MhBackupWriteAnswer answer;
        try
        {
            answer = await writer.SetFavoriteAsync(target.ItemId, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception failure)
        {
            return Make(MhBackupWriteOutcome.Failed, Describe(failure));
        }

        if (!string.IsNullOrWhiteSpace(answer.Failure))
            return Make(MhBackupWriteOutcome.Failed, Clip(answer.Failure!));
        if (answer.ReportedFavorite is not { } favorite)
            return Make(MhBackupWriteOutcome.ServerDidNotStore, "服务端没有回报收藏状态，无法确认这次写入");
        if (favorite) return Make(MhBackupWriteOutcome.Written, "服务端回报该条目已收藏");

        return Make(MhBackupWriteOutcome.ServerDidNotStore, "服务端回报该条目仍未收藏：这次写入没有被采纳");
    }

    /// <summary>把异常折成一句可读原因：折叠空白、去掉首尾、限长，绝不带堆栈。</summary>
    private static string Describe(Exception failure)
    {
        string name = failure.GetType().Name;
        string message = Clip(failure.Message);
        return message.Length == 0 ? "写回失败（" + name + "）" : "写回失败：" + message;
    }

    private static string Clip(string text)
    {
        string collapsed = System.Text.RegularExpressions.Regex.Replace(text ?? string.Empty, "\\s+", " ").Trim();
        if (collapsed.Length <= MaxReasonChars) return collapsed;
        return collapsed.Substring(0, MaxReasonChars) + "…";
    }

    private static IReadOnlyList<string> BuildLines(List<MhBackupWriteAttempt> attempts)
    {
        var lines = new List<string>(2);
        Line(MhBackupRecordKind.Progress, "播放记录写回：", attempts, lines);
        Line(MhBackupRecordKind.Favorite, "收藏写回：", attempts, lines);
        return lines;
    }

    private static void Line(MhBackupRecordKind record, string prefix, List<MhBackupWriteAttempt> attempts, List<string> lines)
    {
        List<MhBackupWriteAttempt> mine = attempts.Where(a => a.Record == record).ToList();
        if (mine.Count == 0) return;

        lines.Add(prefix
            + "已保存 " + mine.Count(a => a.Outcome == MhBackupWriteOutcome.Written) + " 条 · "
            + "服务端未保存 " + mine.Count(a => a.Outcome == MhBackupWriteOutcome.ServerDidNotStore) + " 条 · "
            + "需登录 " + mine.Count(a => a.Outcome == MhBackupWriteOutcome.NeedsLogin) + " 条 · "
            + "失败 " + mine.Count(a => a.Outcome == MhBackupWriteOutcome.Failed) + " 条 · "
            + "不适用 " + mine.Count(a => a.Outcome == MhBackupWriteOutcome.NotApplicable) + " 条");
    }
}

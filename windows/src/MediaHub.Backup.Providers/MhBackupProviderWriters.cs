using MediaHub.Providers;

namespace MediaHub.Backup.Providers;

/// <summary>
/// 把备份写回执行的抽象接到真实的 provider 句柄上。
/// </summary>
/// <remarks>
/// <para>
/// 这一层只做两件事：按备份里的来源 id 找到已登录的 `ProviderHandle`，以及把 handle 暴露的写口
/// 适配成 `IMhBackupServerWriter`。它不决定「这条记录该不该写」——那是 `MhBackupWriteOutlooks` 与
/// `MhBackupWritebacks` 的事；也不登录——没有会话就返回 null，由执行层如实报「需登录」。
/// </para>
/// <para>
/// 服务端的答案原样上交：句柄返回的是服务端随后报告的位置/收藏状态，这一层不回声、不改写。
/// 合成 handle（有句柄但没有写口）不会被当成「需登录」：那会把「这个来源没有接口」说成「还没登录」，
/// 所以这种句柄依然交出通道，写口缺失时回 `MhBackupWriteAnswer.Fail` 的明确原因。
/// </para>
/// </remarks>
public sealed class MhBackupProviderWriters : IMhBackupServerWriters
{
    private readonly Func<string, ProviderHandle?> _resolve;

    /// <summary>
    /// 用「备份来源 id → 已登录句柄」的解析器构造。
    /// </summary>
    /// <remarks>
    /// 解析器自己决定什么算「已登录」：返回 null 即没有会话，执行层会记为需登录且不发请求。
    /// 显式传函数而不是字典，是因为真实的来源 id 与 provider 句柄 id 未必同名，映射要由调用方负责。
    /// </remarks>
    public MhBackupProviderWriters(Func<string, ProviderHandle?> resolve)
        => _resolve = resolve ?? throw new ArgumentNullException(nameof(resolve));

    /// <inheritdoc />
    public IMhBackupServerWriter? ForServer(string serverBackupId)
    {
        ArgumentException.ThrowIfNullOrEmpty(serverBackupId);
        ProviderHandle? handle = _resolve(serverBackupId);
        return handle is null ? null : new MhBackupProviderServerWriter(handle);
    }
}

/// <summary>一个已登录句柄上的写回通道。</summary>
internal sealed class MhBackupProviderServerWriter : IMhBackupServerWriter
{
    private readonly ProviderHandle _handle;

    internal MhBackupProviderServerWriter(ProviderHandle handle) => _handle = handle;

    /// <inheritdoc />
    public async Task<MhBackupWriteAnswer> SavePlaybackPositionAsync(
        string itemId,
        long positionTicks,
        CancellationToken ct)
    {
        IProviderProgressWrite? write = _handle.ProgressWrite;
        if (write is null)
        {
            return MhBackupWriteAnswer.Fail(
                "这个来源没有保存播放位置的接口（provider 没有声明 ProgressWrite）");
        }

        long reported = await write.SetPlaybackPositionAsync(itemId, positionTicks, ct).ConfigureAwait(false);
        return MhBackupWriteAnswer.Position(reported);
    }

    /// <inheritdoc />
    public async Task<MhBackupWriteAnswer> SetFavoriteAsync(string itemId, CancellationToken ct)
    {
        IProviderFavorites? favorites = _handle.Favorites;
        if (favorites is null)
        {
            return MhBackupWriteAnswer.Fail(
                "这个来源没有收藏接口（provider 没有声明 Favorites）");
        }

        bool reported = await favorites.SetFavoriteAsync(itemId, favorite: true, ct).ConfigureAwait(false);
        return MhBackupWriteAnswer.Favorite(reported);
    }
}

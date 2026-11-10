using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Backup;

/// <summary>
/// 本机来源（对应 settings.json 的 PersistedServer，字段一一对应，不含任何凭据）。
/// </summary>
public sealed class MhBackupLocalServer
{
    public string? ServerId { get; init; }
    public string? Type { get; init; }
    public string? DisplayName { get; init; }
    public string? Url { get; init; }
    public string? UserName { get; init; }
    public string? SyncSourceId { get; init; }
}

/// <summary>
/// 本机偏好中与 Android 语义完全等价的子集。
/// </summary>
/// <remarks>
/// 只收三个字段：默认倍速、自动下一集、播放信息浮层。字幕字号等字段两端单位不同
/// （Windows 是像素级字号，Android 是 sp），填一个数字看起来像映射成功、实际会得到错误字号，
/// 因此明确不导出，由对端保持自身默认值。
/// </remarks>
public sealed class MhBackupLocalPreferences
{
    public double DefaultSpeed { get; init; } = 1.0;
    public bool AutoPlayNext { get; init; } = true;
    public bool ShowDetailedMediaInfo { get; init; }
}

/// <summary>被跳过的来源与其可读原因。</summary>
public sealed record MhBackupSkippedServer(string Name, string Reason);

/// <summary>采集结果：载荷 + 跳过项 + 给用户看的中文说明。</summary>
public sealed class MhBackupExportCollectResult
{
    public required MhBackupPayload Payload { get; init; }
    public required IReadOnlyList<MhBackupSkippedServer> SkippedServers { get; init; }
    /// <summary>本机观看进度条数（按路径记录，备份格式无法承载，见类注释）。</summary>
    public required int SkippedHistoryEntries { get; init; }
    public required IReadOnlyList<string> Notes { get; init; }
}

/// <summary>
/// Windows 侧的备份导出采集层：把本机来源与有明确等价关系的偏好映射成与 Android 一致的白名单载荷。
/// </summary>
/// <remarks>
/// 本层不读文件、不碰凭据、不加密；调用方读取 settings.json 后把载荷交给 MhBackupSerializer.Export。
/// 明确不导出：密码、令牌、Cookie、设备 id、临时播放地址。
/// 也明确不导出本机观看进度：history.json 以文件路径为键，备份格式以（来源 backupId、条目 id）为键，
/// 两者没有可靠对应关系，猜测映射会把进度写到错误的条目上。
/// </remarks>
public static class MhBackupExportCollector
{
    public const string DefaultAppVersion = "windows-0.1.0";

    /// <summary>与备份格式一致的上限；超出时导出被拒绝而不是静默截断。</summary>
    public const int MaxLocalServers = MhBackupSerializer.MaxServers;

    public const int ManifestFormatVersion = MhBackupCrypto.FormatVersion;
    public const int ManifestMinimumReaderVersion = MhBackupCrypto.MinimumReaderVersion;

    private const int BackupIdHashBytes = 8;

    private static readonly Dictionary<string, string> TypeNames = new(StringComparer.OrdinalIgnoreCase)
    {
        ["emby"] = "EMBY",
        ["jellyfin"] = "JELLYFIN",
        ["plex"] = "PLEX",
        ["fn_nas"] = "FN_NAS",
        ["fnnas"] = "FN_NAS",
        ["fn-nas"] = "FN_NAS",
        ["webdav"] = "WEBDAV",
        ["smb"] = "SMB",
        ["local"] = "LOCAL",
        ["localfolder"] = "LOCAL",
        ["local_folder"] = "LOCAL",
        ["local-folder"] = "LOCAL",
    };

    public static MhBackupExportCollectResult Collect(
        IReadOnlyList<MhBackupLocalServer>? servers,
        MhBackupLocalPreferences? preferences,
        int skippedHistoryEntries = 0,
        long? nowEpochMs = null,
        string appVersion = DefaultAppVersion)
    {
        if (skippedHistoryEntries < 0) throw new ArgumentOutOfRangeException(nameof(skippedHistoryEntries));
        if (string.IsNullOrWhiteSpace(appVersion)) throw new ArgumentException("应用版本不能为空", nameof(appVersion));

        var payloadServers = new List<MhBackupServerDto>();
        var skipped = new List<MhBackupSkippedServer>();
        var usedIds = new HashSet<string>(StringComparer.Ordinal);

        foreach (MhBackupLocalServer server in servers ?? Array.Empty<MhBackupLocalServer>())
        {
            string displayName = (server.DisplayName ?? string.Empty).Trim();
            string typeName = (server.Type ?? string.Empty).Trim();
            string? type = TypeNames.TryGetValue(typeName, out string? mapped) ? mapped : null;

            if (type is null)
            {
                skipped.Add(new MhBackupSkippedServer(
                    displayName.Length > 0 ? displayName : (server.ServerId ?? "(未命名来源)"),
                    typeName.Length == 0 ? "来源类型为空，不知道该怎么在另一台设备上表示" : $"来源类型 {typeName} 不在备份格式的已知类型里"));
                continue;
            }

            string url = (server.Url ?? string.Empty).Trim();
            string? username = (server.UserName ?? string.Empty).Trim();
            if (username is null || username.Length == 0) username = null;

            if (displayName.Length == 0)
            {
                string? host = HostOf(url);
                if (host is null)
                {
                    skipped.Add(new MhBackupSkippedServer(server.ServerId ?? "(未命名来源)", "来源既没有名称也没有可用地址，无法在另一台设备上辨认"));
                    continue;
                }
                displayName = host;
            }

            if (type != "LOCAL")
            {
                if (url.Length == 0)
                {
                    skipped.Add(new MhBackupSkippedServer(displayName, "来源没有地址，另一台设备无法重新连接"));
                    continue;
                }
                MhBackupUrlViolation? violation = MhBackupUrlGuard.Inspect(url);
                if (violation is not null)
                {
                    skipped.Add(new MhBackupSkippedServer(displayName, DescribeViolation(violation)));
                    continue;
                }
            }

            string backupId = ResolveBackupId(server, type, url, username);
            if (!usedIds.Add(backupId))
            {
                skipped.Add(new MhBackupSkippedServer(displayName, $"身份 {backupId} 与前面的来源重复，只能保留一个"));
                continue;
            }

            if (payloadServers.Count >= MaxLocalServers)
            {
                throw new MhBackupExportException($"来源数量超过备份上限 {MaxLocalServers}");
            }

            var endpoints = new List<MhBackupEndpointDto>();
            if (type != "LOCAL")
            {
                endpoints.Add(new MhBackupEndpointDto
                {
                    Name = displayName,
                    Url = url,
                    IsPrimary = true,
                    Enabled = true,
                    SortOrder = 0,
                });
            }

            payloadServers.Add(new MhBackupServerDto
            {
                BackupId = backupId,
                Name = displayName,
                Type = type,
                Username = username,
                Note = null,
                IsDefault = payloadServers.Count == 0,
                SortOrder = payloadServers.Count,
                Endpoints = endpoints,
            });
        }

        MhBackupPreferencesDto? preferencesDto = null;
        var notes = new List<string>();
        if (preferences is not null)
        {
            double speed = preferences.DefaultSpeed;
            if (!double.IsFinite(speed) || speed <= 0)
            {
                notes.Add("默认倍速不是正的有限值，备份里按 1.0 写出");
                speed = 1.0;
            }

            preferencesDto = new MhBackupPreferencesDto
            {
                DefaultPlaybackSpeed = (float)speed,
                AutoPlayNextEpisode = preferences.AutoPlayNext,
                ShowPlayerInfoOverlay = preferences.ShowDetailedMediaInfo,
            };
            notes.Add("偏好只导出有明确等价的字段：默认倍速、自动下一集、播放信息浮层；字幕字号等因两端单位不同未导出，对端保持自身默认值。");
        }

        if (skippedHistoryEntries > 0)
        {
            notes.Add($"已跳过 {skippedHistoryEntries} 条本机观看进度：Windows 按文件路径记录，备份格式按（来源、条目）记录，两者没有可靠对应关系，不做猜测性映射。");
        }

        if (skipped.Count > 0)
        {
            notes.Add($"有 {skipped.Count} 个来源没有被写进备份，逐条原因见下方。");
        }

        var includedSections = new List<string> { "servers" };
        var recordCounts = new Dictionary<string, int>(StringComparer.Ordinal) { ["servers"] = payloadServers.Count };
        if (preferencesDto is not null)
        {
            includedSections.Add("preferences");
            recordCounts["preferences"] = 1;
        }

        var payload = new MhBackupPayload
        {
            Manifest = new MhBackupManifest(
                FormatVersion: ManifestFormatVersion,
                MinimumReaderVersion: ManifestMinimumReaderVersion,
                AppVersion: appVersion,
                CreatedAtEpochMs: nowEpochMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                IncludedSections: includedSections,
                RecordCounts: recordCounts),
            Servers = payloadServers,
            Progress = new List<MhBackupProgressDto>(),
            Favorites = new List<MhBackupFavoriteDto>(),
            Preferences = preferencesDto,
        };

        return new MhBackupExportCollectResult
        {
            Payload = payload,
            SkippedServers = skipped,
            SkippedHistoryEntries = skippedHistoryEntries,
            Notes = notes,
        };
    }

    /// <summary>
    /// 本机来源在备份里的身份 id：优先 SyncSourceId，否则按（类型|规范化地址|账号）派生。
    /// </summary>
    /// <remarks>
    /// 导出与还原预览必须用同一个函数算 id：同一台机器导出再还原时，没有 SyncSourceId 的来源若在备份里叫
    /// win-xxxx、在本机叫 serverId，就会被判成「新增」并重复写入。
    /// </remarks>
    public static string DeriveBackupId(MhBackupLocalServer server)
    {
        ArgumentNullException.ThrowIfNull(server);
        string syncSourceId = (server.SyncSourceId ?? string.Empty).Trim();
        if (syncSourceId.Length > 0) return syncSourceId;

        string typeName = (server.Type ?? string.Empty).Trim();
        string type = TypeNames.TryGetValue(typeName, out string? mapped) ? mapped : typeName.ToUpperInvariant();
        string url = (server.Url ?? string.Empty).Trim();
        string? username = (server.UserName ?? string.Empty).Trim();
        if (username.Length == 0) username = null;

        string identity = type + "|" + MhBackupUrlGuard.NormalizeForIdentity(url) + "|" + (username ?? string.Empty);
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(identity));
        return "win-" + Convert.ToHexStringLower(hash, 0, BackupIdHashBytes);
    }

    private static string ResolveBackupId(MhBackupLocalServer server, string type, string url, string? username)
        => DeriveBackupId(new MhBackupLocalServer
        {
            SyncSourceId = server.SyncSourceId,
            Type = type,
            Url = url,
            UserName = username,
        });

    private static string? HostOf(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        return Uri.TryCreate(url, UriKind.Absolute, out Uri? uri) && !string.IsNullOrWhiteSpace(uri.Host) ? uri.Host : null;
    }

    private static string DescribeViolation(MhBackupUrlViolation violation) => violation switch
    {
        MhBackupUrlViolation.UserInfo => "地址里带着用户名口令（userinfo），备份不保存凭据，因此不导出",
        MhBackupUrlViolation.SensitiveQuery query => $"地址的查询参数 {query.Key} 像是凭据，备份不保存凭据，因此不导出",
        _ => "地址没有通过凭据检查",
    };
}

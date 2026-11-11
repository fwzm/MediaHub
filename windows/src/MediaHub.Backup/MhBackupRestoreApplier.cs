using System.Text.Json;
using System.Text.Json.Nodes;

namespace MediaHub.Backup;

/// <summary>来源落库结果：哪一个备份来源变成了哪一个本机来源。</summary>
public sealed record MhBackupAppliedServer(string BackupId, string ServerId, string DisplayName, string Type, bool Replaced);

/// <summary>没有落库的备份来源与原因（不允许静默跳过）。</summary>
public sealed record MhBackupApplySkippedServer(string BackupId, string DisplayName, string Reason);

/// <summary>还原落库的产物：新的 settings.json 文本与逐条结果。</summary>
public sealed class MhBackupRestoreApplyResult
{
    public required string SettingsJson { get; init; }

    /// <summary>false 时 <see cref="SettingsJson"/> 就是调用方给的原文（逐字节不动）。</summary>
    public required bool Changed { get; init; }

    public required IReadOnlyList<MhBackupAppliedServer> AddedServers { get; init; }

    public required IReadOnlyList<MhBackupApplySkippedServer> SkippedServers { get; init; }

    public required bool PreferencesRestored { get; init; }

    public required IReadOnlyList<string> Notes { get; init; }

    public int AddedCount => AddedServers.Count(x => !x.Replaced);

    public int ReplacedCount => AddedServers.Count(x => x.Replaced);

    public int SkippedCount => SkippedServers.Count;

    public bool HasWrites => Changed;
}

/// <summary>
/// 把还原计划真正落到 Windows 的 settings.json。
/// </summary>
/// <remarks>
/// 本层只处理「本机能表达的来源」与「有明确等价的偏好」：
/// <list type="bullet">
/// <item>本机构建只有 Local / Emby / Jellyfin / WebDav 四个提供方，Plex、SMB、FN_NAS 落库后无法使用，所以跳过并给原因，而不是写一条点不开的来源。</item>
/// <item>LOCAL 来源的地址是另一台机器的路径，写进本机设置只会得到一条坏来源，同样跳过。</item>
/// <item>播放进度与收藏不在这里写：进度在 Windows 按本地文件路径记账，收藏是服务端能力，两者都需要登录后的语义，交给上层的下一步。</item>
/// </list>
/// 保留 settings.json 里的未知字段（应用后续版本新增的键不会被这条路径擦掉）。
/// </remarks>
public static class MhBackupRestoreApplier
{
    public const string SettingsFileName = "settings.json";

    private static readonly JsonSerializerOptions WriteOptions = new() { WriteIndented = true };

    /// <summary>本机构建能真正打开的备份来源类型。</summary>
    private static readonly Dictionary<string, string> SupportedTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["EMBY"] = "Emby",
        ["JELLYFIN"] = "Jellyfin",
        ["WEBDAV"] = "WebDav",
    };

    /// <summary>备份里有、但本机无法使用的来源类型（要说明为什么，而不是当作成功）。</summary>
    private static readonly Dictionary<string, string> UnsupportedTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["PLEX"] = "Windows 客户端的这一版没有 Plex 提供方",
        ["SMB"] = "Windows 客户端的这一版没有 SMB 提供方",
        ["FN_NAS"] = "Windows 客户端的这一版没有 fn_nas 提供方",
        ["LOCAL"] = "本地文件夹是那台机器上的路径，不能来自备份",
    };

    public static MhBackupRestoreApplyResult Apply(
        MhBackupPayload payload,
        MhBackupRestorePlan plan,
        string? settingsJson)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentNullException.ThrowIfNull(plan);

        string original = settingsJson ?? string.Empty;
        JsonObject root = ParseSettings(original);
        JsonArray servers = EnsureServersArray(root);

        var usedIds = new HashSet<string>(StringComparer.Ordinal);
        var byId = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        foreach (JsonNode? node in servers)
        {
            if (node is not JsonObject entry) continue;
            string? id = entry["ServerId"]?.GetValue<string>();
            if (string.IsNullOrEmpty(id)) continue;
            usedIds.Add(id);
            byId.TryAdd(id, entry);
        }

        var applied = new List<MhBackupAppliedServer>();
        var skipped = new List<MhBackupApplySkippedServer>();
        var notes = new List<string>();
        bool changed = false;

        foreach (MhBackupServerDecision decision in plan.Servers)
        {
            MhBackupServerDto server = decision.Server;
            string backupId = server.BackupId ?? string.Empty;
            string name = string.IsNullOrWhiteSpace(server.Name) ? backupId : server.Name.Trim();
            string type = (server.Type ?? string.Empty).Trim().ToUpperInvariant();

            if (decision.Verdict != MhBackupServerVerdict.New &&
                !(decision.Verdict == MhBackupServerVerdict.Identical && plan.Strategy == MhBackupRestoreStrategy.ReplaceSelected) &&
                !(decision.Verdict == MhBackupServerVerdict.Conflict && plan.Strategy == MhBackupRestoreStrategy.ReplaceSelected))
            {
                if (decision.Verdict == MhBackupServerVerdict.Identical)
                    notes.Add("保留本机来源（已有同源）：" + name);
                else if (decision.Verdict == MhBackupServerVerdict.Conflict)
                    notes.Add("冲突已按合并模式跳过：" + name + " · " + decision.Reason);
                continue;
            }

            if (!SupportedTypes.TryGetValue(type, out string? localType))
            {
                string reason = UnsupportedTypes.TryGetValue(type, out string? known)
                    ? known
                    : "来源类型 " + (string.IsNullOrEmpty(type) ? "(空)" : type) + " 本机无法表达";
                skipped.Add(new MhBackupApplySkippedServer(backupId, name, reason));
                continue;
            }

            string? url = ActiveEndpointUrl(server);
            if (string.IsNullOrWhiteSpace(url))
            {
                skipped.Add(new MhBackupApplySkippedServer(backupId, name, "该来源没有可用地址，写进去也打不开"));
                continue;
            }

            string? userName = string.IsNullOrWhiteSpace(server.Username) ? null : server.Username.Trim();
            bool replace = decision.Verdict is MhBackupServerVerdict.Identical or MhBackupServerVerdict.Conflict;

            if (replace)
            {
                JsonObject? existing = byId.GetValueOrDefault(backupId);
                existing ??= byId.Values.FirstOrDefault(x => string.Equals(
                    x["SyncSourceId"]?.GetValue<string>(), backupId, StringComparison.Ordinal));
                if (existing is null)
                {
                    skipped.Add(new MhBackupApplySkippedServer(backupId, name, "替换模式没有找到本机同 ID 来源，未写入"));
                    continue;
                }

                string beforeJson = existing.ToJsonString();
                existing["Type"] = localType;
                existing["DisplayName"] = name;
                existing["Url"] = url;
                if (userName is null) existing.Remove("UserName"); else existing["UserName"] = userName;
                existing["SyncSourceId"] = backupId;
                changed |= beforeJson != existing.ToJsonString();
                applied.Add(new MhBackupAppliedServer(backupId, existing["ServerId"]?.GetValue<string>() ?? backupId, name, localType, true));
                continue;
            }

            string serverId = UniqueId(backupId, usedIds);
            var entryNode = new JsonObject
            {
                ["ServerId"] = serverId,
                ["Type"] = localType,
                ["DisplayName"] = name,
                ["Url"] = url,
            };
            if (userName is not null) entryNode["UserName"] = userName;
            entryNode["SyncSourceId"] = backupId;
            entryNode["SyncRecordRevision"] = 0;
            servers.Add(entryNode);
            byId[serverId] = entryNode;
            changed = true;
            applied.Add(new MhBackupAppliedServer(backupId, serverId, name, localType, false));
        }

        bool preferencesRestored = false;
        if (plan.PreferencesWillRestore && payload.Preferences is MhBackupPreferencesDto preferences)
        {
            preferencesRestored = ApplyPreferences(root, preferences);
            changed |= preferencesRestored;
            if (!preferencesRestored) notes.Add("备份里的偏好与本机相同，未改动");
        }

        foreach (MhBackupProgressDecision row in plan.Progress)
        {
            if (row.Verdict == MhBackupRecordVerdict.Apply)
            {
                notes.Add("播放进度未落库：Windows 按本地文件路径记账，备份按「来源+条目」记账，缺少条目到文件的映射");
                break;
            }
        }

        foreach (MhBackupFavoriteDecision row in plan.Favorites)
        {
            if (row.Verdict == MhBackupRecordVerdict.Apply)
            {
                notes.Add("收藏未落库：收藏是服务端能力，需要登录之后写回服务器");
                break;
            }
        }

        string resultJson = changed
            ? root.ToJsonString(WriteOptions) + "\n"
            : original;

        return new MhBackupRestoreApplyResult
        {
            SettingsJson = resultJson,
            Changed = changed,
            AddedServers = applied,
            SkippedServers = skipped,
            PreferencesRestored = preferencesRestored,
            Notes = notes,
        };
    }

    private static JsonObject ParseSettings(string json)
    {
        if (string.IsNullOrWhiteSpace(json)) return new JsonObject();
        JsonNode? root;
        try
        {
            root = JsonNode.Parse(json);
        }
        catch (JsonException ex)
        {
            throw new FormatException(SettingsFileName + " 不是合法的 JSON", ex);
        }

        return root as JsonObject
               ?? throw new FormatException(SettingsFileName + " 的顶层不是对象");
    }

    private static JsonArray EnsureServersArray(JsonObject root)
    {
        if (root.TryGetPropertyValue("Servers", out JsonNode? node) && node is not null)
        {
            return node as JsonArray ?? throw new FormatException(SettingsFileName + " 的 Servers 不是数组");
        }

        var created = new JsonArray();
        root["Servers"] = created;
        return created;
    }

    private static string UniqueId(string backupId, HashSet<string> used)
    {
        string baseId = string.IsNullOrEmpty(backupId) ? "backup:" + Guid.NewGuid().ToString("N")[..8] : backupId;
        string candidate = baseId;
        int suffix = 1;
        while (!used.Add(candidate))
        {
            suffix++;
            candidate = "sync:" + baseId + "#" + suffix;
        }

        return candidate;
    }

    /// <summary>与计划器同口径：启用线路里优先 isPrimary，再按 SortOrder 最小。</summary>
    private static string? ActiveEndpointUrl(MhBackupServerDto server)
    {
        var enabled = server.Endpoints.Where(x => x.Enabled).ToList();
        if (enabled.Count == 0) return null;
        var primaries = enabled.Where(x => x.IsPrimary).ToList();
        var candidates = primaries.Count > 0 ? primaries : enabled;
        return candidates.OrderBy(x => x.SortOrder).ThenBy(x => x.Url, StringComparer.Ordinal).First().Url;
    }

    private static bool ApplyPreferences(JsonObject root, MhBackupPreferencesDto preferences)
    {
        bool changed = false;
        changed |= SetNumber(root, "DefaultSpeed", preferences.DefaultPlaybackSpeed);
        changed |= SetFlag(root, "AutoPlayNext", preferences.AutoPlayNextEpisode);
        changed |= SetFlag(root, "ShowDetailedMediaInfo", preferences.ShowPlayerInfoOverlay);
        return changed;
    }

    private static bool SetNumber(JsonObject root, string key, double value)
    {
        double current = root[key] is JsonValue v && v.TryGetValue(out double number) ? number : double.NaN;
        if (!double.IsNaN(current) && Math.Abs(current - value) < 1e-9) return false;
        root[key] = value;
        return true;
    }

    private static bool SetFlag(JsonObject root, string key, bool value)
    {
        bool current = root[key] is JsonValue v && v.TryGetValue(out bool flag) && flag;
        bool present = root[key] is JsonValue;
        if (present && current == value) return false;
        root[key] = value;
        return true;
    }
}

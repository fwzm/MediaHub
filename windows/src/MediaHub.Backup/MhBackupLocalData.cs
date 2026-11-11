using System.Text.Json;

namespace MediaHub.Backup;

/// <summary>
/// 本机数据读取层：把 settings.json 与 history.json 读成备份层需要的纯数据。
/// </summary>
/// <remarks>
/// 只读这里需要的字段；缺字段按默认值处理（settings.json 是产品自己的文件，缺字段说明该来源没有这个值，
/// 不是错误）。整个 JSON 形状不对时抛 FormatException，由调用方转成可读提示。
/// 不读凭据目录，也不读数据库。
/// </remarks>
public static class MhBackupLocalData
{
    private static readonly JsonDocumentOptions DocumentOptions = new()
    {
        AllowTrailingCommas = true,
        CommentHandling = JsonCommentHandling.Skip,
    };

    /// <summary>读取 settings.json 里的来源列表（PersistedServer 的字段一一对应）。</summary>
    public static IReadOnlyList<MhBackupLocalServer> ReadServers(string settingsJson)
    {
        using JsonDocument document = Parse(settingsJson, "settings.json");
        if (!TryGetProperty(document.RootElement, "servers", out JsonElement array))
        {
            return Array.Empty<MhBackupLocalServer>();
        }

        if (array.ValueKind != JsonValueKind.Array)
        {
            throw new FormatException("settings.json 的 servers 不是数组");
        }

        var servers = new List<MhBackupLocalServer>();
        foreach (JsonElement item in array.EnumerateArray())
        {
            servers.Add(new MhBackupLocalServer
            {
                ServerId = Text(item, "serverId"),
                Type = Text(item, "type"),
                DisplayName = Text(item, "displayName"),
                Url = Text(item, "url"),
                UserName = Text(item, "userName"),
                SyncSourceId = Text(item, "syncSourceId"),
            });
        }

        return servers;
    }

    /// <summary>读取 settings.json 里与备份格式有明确等价的偏好字段。</summary>
    public static MhBackupLocalPreferences ReadPreferences(string settingsJson)
    {
        using JsonDocument document = Parse(settingsJson, "settings.json");
        JsonElement root = document.RootElement;
        return new MhBackupLocalPreferences
        {
            DefaultSpeed = Number(root, "defaultSpeed") ?? 1.0,
            AutoPlayNext = Flag(root, "autoPlayNext", true),
            ShowDetailedMediaInfo = Flag(root, "showDetailedMediaInfo", false),
        };
    }

    /// <summary>一次读完来源与偏好（同一份 JSON 只解析一次）。</summary>
    public static MhBackupLocalSettings Read(string settingsJson)
        => new(ReadServers(settingsJson), ReadPreferences(settingsJson));

    /// <summary>统计本机观看进度条数；position 为 0 的记录不是进度，不计入。</summary>
    public static int CountProgressEntries(string historyJson)
    {
        using JsonDocument document = Parse(historyJson, "history.json");
        if (document.RootElement.ValueKind != JsonValueKind.Array)
        {
            throw new FormatException("history.json 的顶层不是数组");
        }

        int count = 0;
        foreach (JsonElement item in document.RootElement.EnumerateArray())
        {
            if ((Long(item, "positionTicks") ?? 0) > 0)
            {
                count++;
            }
        }

        return count;
    }

    private static JsonDocument Parse(string json, string fileName)
    {
        try
        {
            return JsonDocument.Parse(json, DocumentOptions);
        }
        catch (JsonException failure)
        {
            throw new FormatException(fileName + " 不是合法的 JSON", failure);
        }
    }

    private static bool TryGetProperty(JsonElement element, string name, out JsonElement value)
    {
        value = default;
        if (element.ValueKind != JsonValueKind.Object)
        {
            return false;
        }

        // JsonElement.TryGetProperty 区分大小写，而产品文件由 System.Text.Json 按属性名写出；
        // 这里按大小写不敏感匹配，免得手工改过的文件直接整份读不出来。
        foreach (JsonProperty property in element.EnumerateObject())
        {
            if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                value = property.Value;
                return true;
            }
        }

        return false;
    }

    private static string? Text(JsonElement element, string name)
    {
        if (!TryGetProperty(element, name, out JsonElement value))
        {
            return null;
        }

        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static long? Long(JsonElement element, string name)
    {
        if (!TryGetProperty(element, name, out JsonElement value) || value.ValueKind != JsonValueKind.Number)
        {
            return null;
        }

        return value.TryGetInt64(out long parsed) ? parsed : null;
    }

    private static double? Number(JsonElement element, string name)
    {
        if (!TryGetProperty(element, name, out JsonElement value) || value.ValueKind != JsonValueKind.Number)
        {
            return null;
        }

        return value.TryGetDouble(out double parsed) ? parsed : null;
    }

    private static bool Flag(JsonElement element, string name, bool fallback)
    {
        if (!TryGetProperty(element, name, out JsonElement value))
        {
            return fallback;
        }

        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => fallback,
        };
    }
}

/// <summary>settings.json 的一次性读取结果。</summary>
public sealed record MhBackupLocalSettings(
    IReadOnlyList<MhBackupLocalServer> Servers,
    MhBackupLocalPreferences Preferences);

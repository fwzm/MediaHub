using System.Text.Json.Serialization;

namespace MediaHub.Backup;

// 备份内容白名单（与 Android core/common BackupDtos.kt 字段名、默认值一致）。
// 明确不含：密码、Token、Cookie、Authorization、DeviceId、PlaySessionId、临时播放 URL、线路测速结果。

public sealed class MhBackupEndpointDto
{
    [JsonPropertyName("name")] public string? Name { get; init; }
    [JsonPropertyName("url")] public string? Url { get; init; }
    [JsonPropertyName("isPrimary")] public bool IsPrimary { get; init; }
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("sortOrder")] public int SortOrder { get; init; }
}

public sealed class MhBackupServerDto
{
    [JsonPropertyName("backupId")] public string? BackupId { get; init; }
    [JsonPropertyName("name")] public string? Name { get; init; }
    [JsonPropertyName("type")] public string? Type { get; init; }
    [JsonPropertyName("username")] public string? Username { get; init; }
    [JsonPropertyName("note")] public string? Note { get; init; }
    [JsonPropertyName("isDefault")] public bool IsDefault { get; init; }
    [JsonPropertyName("sortOrder")] public int SortOrder { get; init; }
    [JsonPropertyName("endpoints")] public List<MhBackupEndpointDto> Endpoints { get; init; } = new();
}

public sealed class MhBackupProgressDto
{
    [JsonPropertyName("serverBackupId")] public string? ServerBackupId { get; init; }
    [JsonPropertyName("itemId")] public string? ItemId { get; init; }
    [JsonPropertyName("positionMs")] public long PositionMs { get; init; }
    [JsonPropertyName("durationMs")] public long DurationMs { get; init; }
    [JsonPropertyName("isPaused")] public bool IsPaused { get; init; }
    [JsonPropertyName("updatedAtEpochMs")] public long UpdatedAtEpochMs { get; init; }
    [JsonPropertyName("itemTitle")] public string? ItemTitle { get; init; }
    [JsonPropertyName("itemType")] public string? ItemType { get; init; }
}

public sealed class MhBackupFavoriteDto
{
    [JsonPropertyName("serverBackupId")] public string? ServerBackupId { get; init; }
    [JsonPropertyName("itemId")] public string? ItemId { get; init; }
    [JsonPropertyName("title")] public string? Title { get; init; }
    [JsonPropertyName("addedAtEpochMs")] public long AddedAtEpochMs { get; init; }
}

public sealed class MhBackupSubtitleStyleDto
{
    [JsonPropertyName("textColor")] public int TextColor { get; init; } = unchecked((int)0xFFFFFFFF);
    [JsonPropertyName("backgroundColor")] public int BackgroundColor { get; init; }
    [JsonPropertyName("edgeType")] public int EdgeType { get; init; } = 1;
    [JsonPropertyName("edgeColor")] public int EdgeColor { get; init; } = unchecked((int)0xFF000000);
    [JsonPropertyName("textScale")] public float TextScale { get; init; } = 1f;
    [JsonPropertyName("bottomPaddingFraction")] public float BottomPaddingFraction { get; init; } = 0.08f;
    [JsonPropertyName("applyEmbeddedStyles")] public bool ApplyEmbeddedStyles { get; init; } = true;
}

public sealed class MhBackupPlayerGesturesDto
{
    [JsonPropertyName("scrubEnabled")] public bool ScrubEnabled { get; init; } = true;
    [JsonPropertyName("doubleTapSeekBackwardEnabled")] public bool DoubleTapSeekBackwardEnabled { get; init; }
    [JsonPropertyName("doubleTapSeekBackwardSeconds")] public int DoubleTapSeekBackwardSeconds { get; init; } = 10;
    [JsonPropertyName("doubleTapSeekForwardEnabled")] public bool DoubleTapSeekForwardEnabled { get; init; }
    [JsonPropertyName("doubleTapSeekForwardSeconds")] public int DoubleTapSeekForwardSeconds { get; init; } = 10;
    [JsonPropertyName("longPressSpeedEnabled")] public bool LongPressSpeedEnabled { get; init; } = true;
    [JsonPropertyName("longPressSpeedMin")] public float LongPressSpeedMin { get; init; } = 0.5f;
    [JsonPropertyName("longPressSpeedMax")] public float LongPressSpeedMax { get; init; } = 5.0f;
    [JsonPropertyName("longPressDirectionalEnabled")] public bool LongPressDirectionalEnabled { get; init; } = true;
    [JsonPropertyName("longPressDefaultSpeed")] public float LongPressDefaultSpeed { get; init; } = 2.0f;
}

public sealed class MhBackupPreferencesDto
{
    [JsonPropertyName("playbackEngineMode")] public string PlaybackEngineMode { get; init; } = "AUTO";
    [JsonPropertyName("defaultPlaybackSpeed")] public float DefaultPlaybackSpeed { get; init; } = 1f;
    [JsonPropertyName("subtitleSizeSp")] public int SubtitleSizeSp { get; init; } = 18;
    [JsonPropertyName("enableHardwareDecoding")] public bool EnableHardwareDecoding { get; init; } = true;
    [JsonPropertyName("preferDirectPlay")] public bool PreferDirectPlay { get; init; } = true;
    [JsonPropertyName("autoPlayNextEpisode")] public bool AutoPlayNextEpisode { get; init; } = true;
    [JsonPropertyName("maxBitrateBps")] public long? MaxBitrateBps { get; init; }
    [JsonPropertyName("showPlayerInfoOverlay")] public bool ShowPlayerInfoOverlay { get; init; }
    [JsonPropertyName("autoLandscape")] public bool AutoLandscape { get; init; } = true;
    [JsonPropertyName("immersiveBars")] public bool ImmersiveBars { get; init; } = true;
    [JsonPropertyName("subtitleStyle")] public MhBackupSubtitleStyleDto SubtitleStyle { get; init; } = new();
    [JsonPropertyName("gestures")] public MhBackupPlayerGesturesDto Gestures { get; init; } = new();
}

/// <summary>解密后的完整载荷（与 Android BackupDtos.BackupPayload 同结构）。</summary>
public sealed class MhBackupPayload
{
    [JsonPropertyName("manifest")] public MhBackupManifest? Manifest { get; init; }
    [JsonPropertyName("servers")] public List<MhBackupServerDto> Servers { get; init; } = new();
    [JsonPropertyName("progress")] public List<MhBackupProgressDto> Progress { get; init; } = new();
    /** v1 备份无该段 → 反序列化得空列表（与 Android 的向后兼容一致）。 */
    [JsonPropertyName("favorites")] public List<MhBackupFavoriteDto> Favorites { get; init; } = new();
    [JsonPropertyName("preferences")] public MhBackupPreferencesDto? Preferences { get; init; }
}

/// <summary>恢复计划决策记录（与 Android RestorePlanRecord 同字段；策略 MERGE | REPLACE_SELECTED）。</summary>
public sealed class MhBackupRestorePlanRecord
{
    [JsonPropertyName("strategy")] public string Strategy { get; init; } = "MERGE";
    [JsonPropertyName("skipExistingServerIds")] public List<string> SkipExistingServerIds { get; init; } = new();
    [JsonPropertyName("overwriteServerIds")] public List<string> OverwriteServerIds { get; init; } = new();
    [JsonPropertyName("conflictSkippedServerIds")] public List<string> ConflictSkippedServerIds { get; init; } = new();
    [JsonPropertyName("idRemapping")] public Dictionary<string, string> IdRemapping { get; init; } = new();
    [JsonPropertyName("includePreferences")] public bool IncludePreferences { get; init; }
    [JsonPropertyName("baselineFormatVersion")] public int BaselineFormatVersion { get; init; } = 1;
    [JsonPropertyName("restoredProgressCount")] public int? RestoredProgressCount { get; init; }
}

public static class MhBackupPayloadCodec
{
    public static string Encode(MhBackupPayload payload) =>
        System.Text.Json.JsonSerializer.Serialize(payload, MhBackupJson.Options);

    public static MhBackupPayload Decode(string text)
    {
        MhBackupPayload payload = System.Text.Json.JsonSerializer.Deserialize<MhBackupPayload>(text, MhBackupJson.Options)
            ?? throw new System.Text.Json.JsonException("payload is null");
        if (payload.Manifest is null) throw new System.Text.Json.JsonException("manifest missing");
        return payload;
    }
}

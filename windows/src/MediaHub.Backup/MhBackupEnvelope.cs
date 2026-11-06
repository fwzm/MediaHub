using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediaHub.Backup;

/// <summary>外层 JSON（与 Android BackupFileFormat.Envelope 字段名一致）。</summary>
public sealed record MhBackupEnvelope(
    [property: JsonPropertyName("magic")] string? Magic,
    [property: JsonPropertyName("formatVersion")] int FormatVersion,
    [property: JsonPropertyName("kdfAlgorithm")] string? KdfAlgorithm,
    [property: JsonPropertyName("kdfSaltB64")] string? KdfSaltB64,
    [property: JsonPropertyName("kdfIterations")] int KdfIterations,
    [property: JsonPropertyName("kdfKeyLengthBits")] int KdfKeyLengthBits,
    [property: JsonPropertyName("nonceB64")] string? NonceB64,
    [property: JsonPropertyName("ciphertextB64")] string? CiphertextB64);

/// <summary>内层 manifest（与 Android BackupFileFormat.Manifest 字段名一致）。</summary>
public sealed record MhBackupManifest(
    [property: JsonPropertyName("formatVersion")] int FormatVersion,
    [property: JsonPropertyName("minimumReaderVersion")] int MinimumReaderVersion,
    [property: JsonPropertyName("appVersion")] string AppVersion,
    [property: JsonPropertyName("createdAtEpochMs")] long CreatedAtEpochMs,
    [property: JsonPropertyName("includedSections")] List<string> IncludedSections,
    [property: JsonPropertyName("recordCounts")] Dictionary<string, int> RecordCounts);

/// <summary>容器用 JSON 设置：字段名由 attribute 钉死，不依赖命名策略。</summary>
public static class MhBackupJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = false,
        // Android 用 kotlinx 原样写出 UTF-8（中文标题不转义、base64 的 + 不转义）；
        // 默认编码器会把它们转成 \uXXXX 转义，值仍然等价，但可读性与跨端 diff 会变形。
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static string EncodeEnvelope(MhBackupEnvelope envelope) =>
        JsonSerializer.Serialize(envelope, Options);

    public static MhBackupEnvelope DecodeEnvelope(string text) =>
        JsonSerializer.Deserialize<MhBackupEnvelope>(text, Options)
        ?? throw new JsonException("envelope is null");

    public static string EncodeManifest(MhBackupManifest manifest) =>
        JsonSerializer.Serialize(manifest, Options);

    public static MhBackupManifest DecodeManifest(string text) =>
        JsonSerializer.Deserialize<MhBackupManifest>(text, Options)
        ?? throw new JsonException("manifest is null");
}

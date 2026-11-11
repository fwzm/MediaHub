using System.Text;
using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>The one-shot self-description a pairing peer sends after the handshake.</summary>
/// <remarks>
/// It exists because the transport frame carries only the invitation: without a card the host learns nothing about
/// who just connected and can therefore remember nobody. The fingerprint inside is self-declared — it is a
/// first-use-pinned trust anchor, not a cryptographic proof of identity.
/// </remarks>
public sealed record PairingPeerCard
{
    /// <summary>Version of this card shape.</summary>
    public const int CurrentSchemaVersion = 1;

    /// <summary>Longest identifier or display name accepted, in characters.</summary>
    public const int MaximumTextLength = 128;

    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    /// <summary>Stable identifier the peer declares for itself.</summary>
    public required string DeviceId { get; init; }

    /// <summary>Lowercase hex fingerprint the peer wants pinned for it.</summary>
    public required string DeviceFingerprint { get; init; }

    /// <summary>Human-readable name; empty when the peer has none.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>How many sources the peer says it holds. Informational only.</summary>
    public int SourceCount { get; init; }

    /// <summary>The card as a paired-device observation, using the caller's clock.</summary>
    public PairedDeviceObservation ToObservation(long nowEpochMs) => new()
    {
        DeviceId = DeviceId,
        Fingerprint = DeviceFingerprint,
        DisplayName = DisplayName,
        SourceCount = SourceCount,
        NowEpochMs = nowEpochMs,
    };
}

/// <summary>Stable reasons a peer card can be refused.</summary>
public static class PairingPeerCardReasons
{
    public const string MalformedJson = "card-malformed-json";
    public const string TooLong = "card-too-long";
    public const string UnsupportedVersion = "card-unsupported-version";
    public const string MissingDeviceId = "card-missing-device-id";
    public const string MissingFingerprint = "card-missing-fingerprint";
    public const string BadFingerprint = "card-bad-fingerprint";

    public static IReadOnlyList<string> All { get; } =
    [
        MalformedJson,
        TooLong,
        UnsupportedVersion,
        MissingDeviceId,
        MissingFingerprint,
        BadFingerprint,
    ];
}

/// <summary>Compact JSON for <see cref="PairingPeerCard"/>.</summary>
/// <remarks>
/// Validation is manual and total: every failure returns a stable reason code, and no parse error ever carries the
/// offending text, because the payload may contain a peer-chosen name that is not ours to echo into a log.
/// </remarks>
public static class PairingPeerCardCodec
{
    /// <summary>Cards are tiny; anything larger is not a card and is refused before parsing.</summary>
    public const int MaximumLength = 2048;

    /// <summary>Serializes a card in a fixed field order.</summary>
    public static string Serialize(PairingPeerCard card)
    {
        ArgumentNullException.ThrowIfNull(card);
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteNumber("schemaVersion", card.SchemaVersion);
            writer.WriteString("deviceId", card.DeviceId);
            writer.WriteString("deviceFingerprint", card.DeviceFingerprint);
            writer.WriteString("displayName", card.DisplayName);
            writer.WriteNumber("sourceCount", card.SourceCount);
            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(stream.ToArray());
    }

    /// <summary>Parses and validates one card; false with a stable reason when it is not acceptable.</summary>
    public static bool TryDeserialize(string? json, out PairingPeerCard? card, out string? error)
    {
        card = null;
        error = null;

        if (string.IsNullOrWhiteSpace(json))
        {
            error = PairingPeerCardReasons.MalformedJson;
            return false;
        }

        if (json.Length > MaximumLength)
        {
            error = PairingPeerCardReasons.TooLong;
            return false;
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            error = PairingPeerCardReasons.MalformedJson;
            return false;
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                error = PairingPeerCardReasons.MalformedJson;
                return false;
            }

            // 转义出来的孤立代理项会让 GetString() 抛 InvalidOperationException（不是契约异常），
            // 所以在读任何字段之前按文档级判成 malformed-json。与 Kotlin 侧同一判定点（W6-71）。
            if (WireText.ContainsLoneSurrogate(root))
            {
                error = PairingPeerCardReasons.MalformedJson;
                return false;
            }

            // TryGetInt32 在 ValueKind 不是 Number 时抛 InvalidOperationException（不是返回 false），
            // 所以必须先判类型；否则一张 {"schemaVersion":"1"} 的卡片会把读端打崩（W6-71 修掉的第三个 Windows 缺陷）。
            if (!root.TryGetProperty("schemaVersion", out JsonElement versionElement)
                || versionElement.ValueKind != JsonValueKind.Number
                || !versionElement.TryGetInt32(out int version))
            {
                error = PairingPeerCardReasons.MalformedJson;
                return false;
            }

            if (version != PairingPeerCard.CurrentSchemaVersion)
            {
                error = PairingPeerCardReasons.UnsupportedVersion;
                return false;
            }

            if (!root.TryGetProperty("deviceId", out JsonElement idElement)
                || idElement.ValueKind != JsonValueKind.String)
            {
                error = PairingPeerCardReasons.MissingDeviceId;
                return false;
            }

            string deviceId = (idElement.GetString() ?? string.Empty).Trim();
            if (deviceId.Length == 0)
            {
                error = PairingPeerCardReasons.MissingDeviceId;
                return false;
            }

            if (deviceId.Length > PairingPeerCard.MaximumTextLength)
            {
                error = PairingPeerCardReasons.MissingDeviceId;
                return false;
            }

            if (!root.TryGetProperty("deviceFingerprint", out JsonElement fingerprintElement)
                || fingerprintElement.ValueKind != JsonValueKind.String)
            {
                error = PairingPeerCardReasons.MissingFingerprint;
                return false;
            }

            string fingerprintText = (fingerprintElement.GetString() ?? string.Empty).Trim();
            if (fingerprintText.Length == 0)
            {
                error = PairingPeerCardReasons.MissingFingerprint;
                return false;
            }

            if (!PairingFingerprint.TryParse(fingerprintText, out PairingFingerprint fingerprint))
            {
                error = PairingPeerCardReasons.BadFingerprint;
                return false;
            }

            string displayName = string.Empty;
            if (root.TryGetProperty("displayName", out JsonElement nameElement) && nameElement.ValueKind == JsonValueKind.String)
            {
                displayName = (nameElement.GetString() ?? string.Empty).Trim();
                if (displayName.Length > PairingPeerCard.MaximumTextLength)
                {
                    displayName = displayName[..PairingPeerCard.MaximumTextLength];
                }
            }

            int sourceCount = 0;
            if (root.TryGetProperty("sourceCount", out JsonElement countElement)
                && countElement.ValueKind == JsonValueKind.Number
                && countElement.TryGetInt32(out int parsedCount)
                && parsedCount > 0)
            {
                sourceCount = parsedCount;
            }

            card = new PairingPeerCard
            {
                DeviceId = deviceId,
                DeviceFingerprint = fingerprint.Hex,
                DisplayName = displayName,
                SourceCount = sourceCount,
            };
            return true;
        }
    }
}

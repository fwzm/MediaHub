using System.Globalization;

namespace MediaHub.Sync;

/// <summary>One device this installation has already paired with.</summary>
/// <remarks>
/// A paired device is not a synced device: the record only says "we once completed a trusted handshake and we
/// remember the fingerprint it presented". It carries no credential, no token and no source list, and writing it
/// never contacts anybody.
/// </remarks>
public sealed record PairedDevice
{
    /// <summary>Stable identifier the peer declared for itself. Never a path, address or account.</summary>
    public required string DeviceId { get; init; }

    /// <summary>Name the peer showed when it was paired; empty when it offered none.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>Lowercase hex fingerprint pinned at the first pairing: the trust anchor for the next meeting.</summary>
    public string Fingerprint { get; init; } = "";

    /// <summary>When the pairing was first accepted, in epoch milliseconds (UTC).</summary>
    public long PairedAtEpochMs { get; init; }

    /// <summary>When the device last completed a pairing handshake, in epoch milliseconds (UTC).</summary>
    public long LastSeenEpochMs { get; init; }

    /// <summary>How many sources the peer said it had. Informational only; it is never used for identity.</summary>
    public int SourceCount { get; init; }

    /// <summary>Fingerprint grouped in fours for display; empty when the stored value cannot be parsed.</summary>
    public string FingerprintDisplay =>
        PairingFingerprint.TryParse(Fingerprint, out PairingFingerprint parsed) ? parsed.ToDisplayString() : string.Empty;

    /// <summary>Name to show before falling back to the identifier.</summary>
    public string DisplayNameOrId => string.IsNullOrWhiteSpace(DisplayName) ? DeviceId : DisplayName;

    /// <summary>Builds a record with normalized fields; the caller owns the clock.</summary>
    public static PairedDevice Create(
        string deviceId,
        string fingerprint,
        string? displayName,
        long nowEpochMs,
        int sourceCount = 0,
        long? pairedAtEpochMs = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("设备标识不能为空。", nameof(deviceId));
        }

        string normalizedDisplay = (displayName ?? string.Empty).Trim();
        return new PairedDevice
        {
            DeviceId = deviceId.Trim(),
            DisplayName = normalizedDisplay.Length > 128 ? normalizedDisplay[..128] : normalizedDisplay,
            Fingerprint = (fingerprint ?? string.Empty).Trim().ToLowerInvariant(),
            PairedAtEpochMs = pairedAtEpochMs ?? nowEpochMs,
            LastSeenEpochMs = nowEpochMs,
            SourceCount = sourceCount < 0 ? 0 : sourceCount,
        };
    }

    /// <summary>A one-line summary that never claims anything was synced or signed in.</summary>
    public string Describe(long? nowEpochMs = null)
    {
        string pairedAt = FormatEpoch(PairedAtEpochMs);
        string seenAt = FormatEpoch(LastSeenEpochMs);
        string suffix = nowEpochMs is { } now && LastSeenEpochMs > now
            ? "（时间来自对端，晚于本机时钟）"
            : string.Empty;
        return string.Create(
            CultureInfo.InvariantCulture,
            $"{DisplayNameOrId} · 首次配对 {pairedAt} · 最近配对 {seenAt}{suffix} · 来源 {SourceCount} 个");
    }

    private static string FormatEpoch(long epochMs)
    {
        if (epochMs <= 0)
        {
            return "未知";
        }

        try
        {
            return DateTimeOffset.FromUnixTimeMilliseconds(epochMs).ToLocalTime()
                .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
        }
        catch (ArgumentOutOfRangeException)
        {
            return "未知";
        }
    }
}

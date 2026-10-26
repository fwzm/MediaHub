using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>What this installation tells a peer about itself during pairing.</summary>
/// <remarks>
/// The fingerprint is derived from the device identifier by default, so it is stable across restarts without a
/// second piece of state. It is self-declared by design: the peer pins it on first use and can only detect a
/// change afterwards, not prove an origin. Anything that needs proof belongs to the TLS layer, not to this card.
/// </remarks>
public sealed record PairingSelfIdentity
{
    /// <summary>Prefix mixed into the derivation so a device id can never collide with another hash use.</summary>
    public const string DerivationPrefix = "mediahub-device:";

    public required string DeviceId { get; init; }

    public required PairingFingerprint Fingerprint { get; init; }

    public string DisplayName { get; init; } = "";

    /// <summary>How many sources this installation would offer. Informational only.</summary>
    public int SourceCount { get; init; }

    /// <summary>Builds the identity with a fingerprint derived from the identifier.</summary>
    public static PairingSelfIdentity Create(string deviceId, string? displayName = null, int sourceCount = 0)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("设备标识不能为空。", nameof(deviceId));
        }

        string trimmedId = deviceId.Trim();
        return new PairingSelfIdentity
        {
            DeviceId = trimmedId,
            Fingerprint = DeriveFingerprint(trimmedId),
            DisplayName = (displayName ?? string.Empty).Trim(),
            SourceCount = sourceCount < 0 ? 0 : sourceCount,
        };
    }

    /// <summary>SHA-256 over a domain-separated identifier: 32 deterministic bytes, never a secret.</summary>
    public static PairingFingerprint DeriveFingerprint(string deviceId)
    {
        string trimmed = (deviceId ?? string.Empty).Trim();
        if (trimmed.Length == 0)
        {
            throw new ArgumentException("设备标识不能为空。", nameof(deviceId));
        }

        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes(DerivationPrefix + trimmed));
        return PairingFingerprint.FromBytes(digest);
    }

    /// <summary>The card to send over the wire.</summary>
    public PairingPeerCard ToCard() => new()
    {
        DeviceId = DeviceId,
        DeviceFingerprint = Fingerprint.Hex,
        DisplayName = DisplayName,
        SourceCount = SourceCount,
    };
}

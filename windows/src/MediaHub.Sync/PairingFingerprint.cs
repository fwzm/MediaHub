using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// The public-key fingerprint of one device, as it travels in a pairing payload (ADR-S001 D6, ADR-S002).
/// </summary>
/// <remarks>
/// <para>
/// A fingerprint is sixty-four lowercase hex characters — a SHA-256 digest, nothing else. It is not a secret:
/// its whole purpose is to be shown on both screens so a human can compare them, and to be pinned by the
/// transport layer later (W6-3b). Comparison therefore uses
/// <see cref="CryptographicOperations.FixedTimeEquals(ReadOnlySpan{byte}, ReadOnlySpan{byte})"/>: the value is
/// public, but a comparison that returns early still leaks how much of a wrong fingerprint was guessed, and a
/// pairing decision is exactly the wrong place to hand out that oracle.
/// </para>
/// <para>
/// Case is an input detail, never an output one: parsing accepts either case and normalises to lowercase, so
/// two fingerprints that denote the same key are always equal as values and always serialise identically.
/// </para>
/// </remarks>
public readonly struct PairingFingerprint : IEquatable<PairingFingerprint>
{
    /// <summary>Number of hex characters in a fingerprint (SHA-256, 32 bytes).</summary>
    public const int HexLength = 64;

    private const int ByteLength = HexLength / 2;

    private readonly string? _hex;

    private PairingFingerprint(string lowercaseHex) => _hex = lowercaseHex;

    /// <summary>The canonical lowercase hex form, or the empty string when the value was never initialised.</summary>
    public string Hex => _hex ?? "";

    /// <summary>True for the default value, which carries no fingerprint and must never match anything.</summary>
    public bool IsEmpty => string.IsNullOrEmpty(_hex);

    /// <summary>The raw digest bytes.</summary>
    public ReadOnlyMemory<byte> Bytes
        => IsEmpty ? ReadOnlyMemory<byte>.Empty : Convert.FromHexString(Hex);

    /// <summary>Wraps a SHA-256 digest.</summary>
    /// <exception cref="ArgumentException">The digest is not 32 bytes.</exception>
    public static PairingFingerprint FromBytes(ReadOnlySpan<byte> digest)
    {
        if (digest.Length != ByteLength)
        {
            throw new ArgumentException(
                "A pairing fingerprint is a SHA-256 digest (" + ByteLength.ToString(CultureInfo.InvariantCulture)
                    + " bytes); got " + digest.Length.ToString(CultureInfo.InvariantCulture) + ".",
                nameof(digest));
        }

        return new PairingFingerprint(Convert.ToHexStringLower(digest));
    }

    /// <summary>Parses the wire form: sixty-four hex characters, either case, normalised to lowercase.</summary>
    public static bool TryParse(string? hex, out PairingFingerprint fingerprint)
    {
        fingerprint = default;
        if (WireText.IsBlank(hex) || hex!.Length != HexLength)
        {
            return false;
        }

        foreach (char character in hex)
        {
            if (!char.IsAsciiHexDigit(character))
            {
                return false;
            }
        }

        byte[] digest = Convert.FromHexString(hex);
        if (digest.Length != ByteLength)
        {
            return false;
        }

        fingerprint = new PairingFingerprint(Convert.ToHexStringLower(digest));
        return true;
    }

    /// <summary>Parses the wire form.</summary>
    /// <exception cref="SyncContractException">The value is not a fingerprint (<c>bad-fingerprint</c>).</exception>
    public static PairingFingerprint Parse(string? hex)
        => TryParse(hex, out PairingFingerprint fingerprint)
            ? fingerprint
            : throw new SyncContractException(
                PairingReasons.BadFingerprint,
                "A public-key fingerprint is " + HexLength.ToString(CultureInfo.InvariantCulture)
                    + " hex characters (a SHA-256 digest); '" + (hex ?? "null") + "' is not.");

    /// <summary>
    /// Compares two fingerprints in constant time. Empty values never match — an absent fingerprint must not
    /// silently authorise a pairing.
    /// </summary>
    public bool Matches(PairingFingerprint other)
        => !IsEmpty
            && !other.IsEmpty
            && CryptographicOperations.FixedTimeEquals(Bytes.Span, other.Bytes.Span);

    /// <summary>
    /// The form shown to the user for the "do these two screens agree?" check: uppercase, grouped in fours,
    /// because sixty-four unbroken characters are exactly the wall of noise people agree to without reading.
    /// </summary>
    public string ToDisplayString()
    {
        if (IsEmpty)
        {
            return "";
        }

        string upper = Hex.ToUpperInvariant();
        var builder = new StringBuilder(HexLength + (HexLength / 4) - 1);
        for (int index = 0; index < upper.Length; index += 4)
        {
            if (index > 0)
            {
                builder.Append(' ');
            }

            builder.Append(upper, index, 4);
        }

        return builder.ToString();
    }

    /// <inheritdoc />
    public bool Equals(PairingFingerprint other) => string.Equals(Hex, other.Hex, StringComparison.Ordinal);

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is PairingFingerprint other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => StringComparer.Ordinal.GetHashCode(Hex);

    /// <inheritdoc />
    public override string ToString() => IsEmpty ? "PairingFingerprint(empty)" : "PairingFingerprint(" + Hex + ")";

    /// <summary>Value equality over the canonical lowercase hex form.</summary>
    public static bool operator ==(PairingFingerprint left, PairingFingerprint right) => left.Equals(right);

    /// <summary>Value inequality over the canonical lowercase hex form.</summary>
    public static bool operator !=(PairingFingerprint left, PairingFingerprint right) => !left.Equals(right);
}

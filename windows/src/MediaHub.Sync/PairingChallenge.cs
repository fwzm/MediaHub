using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// The one-shot challenge a pairing invitation carries (ADR-S001 D6, ADR-S002).
/// </summary>
/// <remarks>
/// <para>
/// Thirty-two random bytes, written as canonical base64. The length is not decoration: the challenge is the
/// only thing that proves the device on the other end saw this invitation and not a screenshot of an older
/// one, so it has to be wide enough that guessing is hopeless — 256 bits, the same order as the key it stands
/// in for.
/// </para>
/// <para>
/// A challenge is a nonce, not a secret, but it is still compared with
/// <see cref="CryptographicOperations.FixedTimeEquals(ReadOnlySpan{byte}, ReadOnlySpan{byte})"/>: an early
/// return would tell an attacker how much of the nonce they had counted right. Equality (<see cref="Equals"/>)
/// stays an exact string comparison for dictionaries and ledger keys; <see cref="Matches"/> is what a pairing
/// decision uses.
/// </para>
/// <para>
/// Only the canonical base64 spelling parses. Non-canonical encodings of the same bytes would otherwise be two
/// distinct dictionary keys, and a replayed screenshot would slip past a ledger that had already seen the
/// first spelling.
/// </para>
/// </remarks>
public readonly struct PairingChallenge : IEquatable<PairingChallenge>
{
    /// <summary>Number of random bytes in a challenge.</summary>
    public const int ByteLength = 32;

    private readonly string? _base64;

    private PairingChallenge(string base64) => _base64 = base64;

    /// <summary>The canonical base64 form, or the empty string when the value was never initialised.</summary>
    public string Base64 => _base64 ?? "";

    /// <summary>True for the default value, which carries no challenge and must never match anything.</summary>
    public bool IsEmpty => string.IsNullOrEmpty(_base64);

    /// <summary>The raw challenge bytes.</summary>
    public ReadOnlyMemory<byte> Bytes
        => IsEmpty ? ReadOnlyMemory<byte>.Empty : Convert.FromBase64String(Base64);

    /// <summary>Draws a fresh challenge from the system CSPRNG.</summary>
    public static PairingChallenge Create()
    {
        Span<byte> bytes = stackalloc byte[ByteLength];
        RandomNumberGenerator.Fill(bytes);
        return FromBytes(bytes);
    }

    /// <summary>Wraps exactly thirty-two bytes.</summary>
    /// <exception cref="ArgumentException">The buffer is not <see cref="ByteLength"/> bytes.</exception>
    public static PairingChallenge FromBytes(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != ByteLength)
        {
            throw new ArgumentException(
                "A pairing challenge is exactly " + ByteLength.ToString(CultureInfo.InvariantCulture)
                    + " bytes; got " + bytes.Length.ToString(CultureInfo.InvariantCulture) + ".",
                nameof(bytes));
        }

        return new PairingChallenge(Convert.ToBase64String(bytes));
    }

    /// <summary>Parses the canonical base64 form; anything else is refused rather than repaired.</summary>
    public static bool TryParse(string? base64, out PairingChallenge challenge)
    {
        challenge = default;
        if (string.IsNullOrWhiteSpace(base64))
        {
            return false;
        }

        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(base64);
        }
        catch (FormatException)
        {
            return false;
        }

        if (bytes.Length != ByteLength)
        {
            return false;
        }

        if (!string.Equals(Convert.ToBase64String(bytes), base64, StringComparison.Ordinal))
        {
            // Same bytes, different spelling: refuse, so a ledger has exactly one key per challenge.
            return false;
        }

        challenge = new PairingChallenge(base64);
        return true;
    }

    /// <summary>Parses the canonical base64 form.</summary>
    /// <exception cref="SyncContractException">The value is not a challenge (<c>bad-challenge</c>).</exception>
    public static PairingChallenge Parse(string? base64)
        => TryParse(base64, out PairingChallenge challenge)
            ? challenge
            : throw new SyncContractException(
                PairingReasons.BadChallenge,
                "A pairing challenge is " + ByteLength.ToString(CultureInfo.InvariantCulture)
                    + " random bytes in canonical base64; '" + (base64 ?? "null") + "' is not.");

    /// <summary>Compares two challenges in constant time. Empty values never match.</summary>
    public bool Matches(PairingChallenge other)
        => !IsEmpty
            && !other.IsEmpty
            && CryptographicOperations.FixedTimeEquals(Bytes.Span, other.Bytes.Span);

    /// <inheritdoc />
    public bool Equals(PairingChallenge other) => string.Equals(Base64, other.Base64, StringComparison.Ordinal);

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is PairingChallenge other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => StringComparer.Ordinal.GetHashCode(Base64);

    /// <summary>Never prints the value: a challenge that ends up in a log is a challenge that can be replayed.</summary>
    public override string ToString() => IsEmpty ? "PairingChallenge(empty)" : "PairingChallenge(<redacted>)";

    /// <summary>Value equality over the canonical base64 form.</summary>
    public static bool operator ==(PairingChallenge left, PairingChallenge right) => left.Equals(right);

    /// <summary>Value inequality over the canonical base64 form.</summary>
    public static bool operator !=(PairingChallenge left, PairingChallenge right) => !left.Equals(right);
}

/// <summary>
/// Remembers which challenges have already been spent, so an invitation is worth exactly one pairing.
/// </summary>
/// <remarks>
/// <para>
/// One-shot consumption is the rule that turns "the QR code is short-lived" into something that actually holds
/// when someone photographs the screen: expiry bounds the window, the ledger bounds the number of uses to one.
/// The ledger is in-memory on purpose — it has to live exactly as long as the pairing attempt it protects, and
/// persisting it would outlive the invitation it describes.
/// </para>
/// <para>
/// The caller owns retention. There is no clock here: a ledger belongs to one pairing attempt (or one process
/// session), and a challenge older than its invitation is already refused by the expiry check before it ever
/// reaches <see cref="TryConsume"/>.
/// </para>
/// </remarks>
public sealed class PairingChallengeLedger
{
    private readonly Dictionary<string, byte> _consumed = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    /// <summary>How many distinct challenges have been spent.</summary>
    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _consumed.Count;
            }
        }
    }

    /// <summary>Whether <paramref name="challenge"/> has already been spent.</summary>
    public bool IsConsumed(PairingChallenge challenge)
    {
        if (challenge.IsEmpty)
        {
            return false;
        }

        lock (_gate)
        {
            return _consumed.ContainsKey(challenge.Base64);
        }
    }

    /// <summary>
    /// Spends <paramref name="challenge"/>. Returns false when it was already spent — the caller turns that
    /// into <c>challenge-replayed</c>; it must not treat "already seen" as success.
    /// </summary>
    public bool TryConsume(PairingChallenge challenge)
    {
        if (challenge.IsEmpty)
        {
            return false;
        }

        lock (_gate)
        {
            return _consumed.TryAdd(challenge.Base64, 0);
        }
    }

    /// <summary>Forgets every challenge, for a caller that starts a brand-new pairing attempt.</summary>
    public void Clear()
    {
        lock (_gate)
        {
            _consumed.Clear();
        }
    }
}

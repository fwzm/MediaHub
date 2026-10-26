using System.Security.Cryptography;

namespace MediaHub.Sync;

/// <summary>
/// The key-derivation function an archive header names (ADR-S005 D2).
/// </summary>
public enum KdfId
{
    /// <summary>PBKDF2 with HMAC-SHA256 — the only kdf this build derives.</summary>
    Pbkdf2Sha256 = 1,
}

/// <summary>
/// Key derivation for the offline archive: PBKDF2-HMAC-SHA256, 16-byte salt, 32-byte key, 600 000 rounds.
/// </summary>
/// <remarks>
/// <para>
/// ADR-S001 D6 asked for AES-256-GCM plus Argon2id. AES-256-GCM is what this build uses; Argon2id needs a
/// third-party package and this project is zero-NuGet, so the fallback is the strongest option in
/// <c>System.Security.Cryptography</c>: PBKDF2-HMAC-SHA256 at 600 000 iterations (the current OWASP
/// recommendation for this PRF). The deviation is registered in ADR-S005 D2 together with its consequences:
/// PBKDF2 is memory-cheap, so a GPU or ASIC attacker gets more guesses per second than with Argon2id. That is
/// compensated by, not hidden behind, a 12-character minimum and a weak-passphrase screen
/// (<see cref="ArchivePassphrase"/>), and by the archive being a short-lived transfer file rather than a
/// long-term backup.
/// </para>
/// <para>
/// The kdf is named in the header and dispatched on read, so adding Argon2id later is a new <see cref="KdfId"/>
/// plus a new branch — old archives keep reading, new ones are written with the stronger kdf, and no version
/// bump is needed (ADR-S005 D5).
/// </para>
/// </remarks>
public static class ArchiveKdf
{
    /// <summary>The wire name written into an archive header for <see cref="KdfId.Pbkdf2Sha256"/>.</summary>
    public const string Pbkdf2Sha256Name = "pbkdf2-sha256";

    /// <summary>Salt length in bytes.</summary>
    public const int SaltSize = 16;

    /// <summary>Derived key length in bytes (AES-256).</summary>
    public const int KeySize = 32;

    /// <summary>Lower bound accepted from a header, and the value this build writes.</summary>
    public const int MinIterations = 600_000;

    /// <summary>Iteration count used when the caller does not pick one.</summary>
    public const int DefaultIterations = MinIterations;

    /// <summary>The wire name of a kdf id.</summary>
    /// <exception cref="ArgumentOutOfRangeException">The id has no wire name in this build.</exception>
    public static string NameOf(KdfId id) => id switch
    {
        KdfId.Pbkdf2Sha256 => Pbkdf2Sha256Name,
        _ => throw new ArgumentOutOfRangeException(nameof(id), id, "Unknown key-derivation function."),
    };

    /// <summary>Parses a wire name; unknown names return false rather than being coerced to a default.</summary>
    public static bool TryParse(string? name, out KdfId id)
    {
        if (string.Equals(name?.Trim(), Pbkdf2Sha256Name, StringComparison.OrdinalIgnoreCase))
        {
            id = KdfId.Pbkdf2Sha256;
            return true;
        }

        id = default;
        return false;
    }

    /// <summary>Derives the 32-byte archive key.</summary>
    /// <exception cref="ArgumentNullException"><paramref name="passphrase"/> or <paramref name="salt"/> is null.</exception>
    /// <exception cref="ArgumentException">The salt is not <see cref="SaltSize"/> bytes.</exception>
    /// <exception cref="ArgumentOutOfRangeException">The iteration count is below <see cref="MinIterations"/>.</exception>
    public static byte[] DeriveKey(string passphrase, byte[] salt, int iterations)
    {
        ArgumentNullException.ThrowIfNull(passphrase);
        ArgumentNullException.ThrowIfNull(salt);

        if (salt.Length != SaltSize)
        {
            throw new ArgumentException("The salt must be " + SaltSize + " bytes.", nameof(salt));
        }

        if (iterations < MinIterations)
        {
            throw new ArgumentOutOfRangeException(
                nameof(iterations),
                iterations,
                "The iteration count must be at least " + MinIterations + ".");
        }

        return Rfc2898DeriveBytes.Pbkdf2(
            passphrase.AsSpan(),
            salt,
            iterations,
            HashAlgorithmName.SHA256,
            KeySize);
    }
}

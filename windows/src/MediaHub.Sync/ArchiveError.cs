using System.Globalization;

namespace MediaHub.Sync;

/// <summary>
/// Stable reason codes for the encrypted offline archive (ADR-S005).
/// </summary>
/// <remarks>
/// <para>
/// These codes are part of the contract, not of the exception text: they are what the UI shows, what a
/// diagnostic records, and what a test asserts. They never change spelling, and none of them carries data
/// from the decrypted payload — an error must not become the leak the archive exists to prevent.
/// </para>
/// <para>
/// <see cref="WrongPassphraseOrTampered"/> is one code for two causes on purpose. AES-GCM authenticates
/// before it decrypts, so a wrong passphrase and a modified ciphertext are indistinguishable to the reader;
/// two separate codes would claim a distinction the crypto cannot make. Everything that is decidable without
/// the key — bad magic, a malformed header, an unknown kdf, a version from the future — gets its own code, so
/// "the file is broken" is never reported as "your passphrase is wrong", and vice versa.
/// </para>
/// </remarks>
public static class ArchiveError
{
    /// <summary>The file does not start with <see cref="ArchiveHeader.CurrentMagic"/>.</summary>
    public const string MagicMismatch = "magic-mismatch";

    /// <summary>The cleartext header is missing, truncated, or not the documented JSON shape.</summary>
    public const string CorruptHeader = "corrupt-header";

    /// <summary>Prefix of <see cref="UnsupportedVersion(int)"/>.</summary>
    public const string UnsupportedVersionPrefix = "unsupported-version";

    /// <summary>Prefix of <see cref="UnknownKdf(string)"/>.</summary>
    public const string UnknownKdfPrefix = "unknown-kdf";

    /// <summary>The header names a kdf this build can read but with parameters outside the accepted range.</summary>
    public const string InvalidIterations = "invalid-iterations";

    /// <summary>No passphrase was supplied.</summary>
    public const string PassphraseEmpty = "passphrase-empty";

    /// <summary>The passphrase is shorter than <see cref="ArchivePassphrase.MinLength"/> or is a known weak one.</summary>
    public const string PassphraseTooWeak = "passphrase-too-weak";

    /// <summary>The envelope asks to carry secrets; an archive never does (ADR-S001 D6).</summary>
    public const string SecretsNotAllowed = "secrets-not-allowed";

    /// <summary>The plaintext payload is larger than <see cref="ArchiveHeader.MaxPayloadBytes"/>.</summary>
    public const string PayloadTooLarge = "payload-too-large";

    /// <summary>The ciphertext or tag is truncated, empty, or of an impossible length.</summary>
    public const string CorruptCiphertext = "corrupt-ciphertext";

    /// <summary>
    /// The GCM tag did not verify. Covers both a wrong passphrase and a modified ciphertext, header, or tag —
    /// the reader cannot tell them apart, and must not pretend it can.
    /// </summary>
    public const string WrongPassphraseOrTampered = "wrong-passphrase-or-tampered";

    /// <summary>The decrypted bytes are not a readable source envelope.</summary>
    public const string MalformedPayload = "malformed-payload";

    /// <summary>Builds the version-specific code, e.g. <c>unsupported-version:2</c>.</summary>
    public static string UnsupportedVersion(int version) =>
        UnsupportedVersionPrefix + ":" + version.ToString(CultureInfo.InvariantCulture);

    /// <summary>Builds the kdf-specific code, e.g. <c>unknown-kdf:argon2id</c>.</summary>
    public static string UnknownKdf(string kdf) => UnknownKdfPrefix + ":" + (kdf ?? "");
}

/// <summary>
/// Thrown when an archive cannot be written or read for a reason the caller can act on.
/// </summary>
/// <remarks>
/// <see cref="Reason"/> is the stable code from <see cref="ArchiveError"/>; <see cref="Exception.Message"/> is
/// for logs only and is never shown to the user verbatim.
/// </remarks>
public sealed class ArchiveException : Exception
{
    /// <summary>Creates an exception carrying a stable reason code.</summary>
    public ArchiveException(string reason, string message)
        : base(message)
    {
        Reason = reason;
    }

    /// <summary>The stable code, e.g. <c>wrong-passphrase-or-tampered</c>.</summary>
    public string Reason { get; }
}

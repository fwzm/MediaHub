using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// Reads the encrypted offline archive (.mharchive) — ADR-S005.
/// </summary>
/// <remarks>
/// <para>
/// The reader is a pure in-memory API: it takes bytes and returns an envelope, touches no file, and never
/// mutates its input. Callers decide where an archive lives, which keeps the format testable and keeps the
/// "did the app just write something" question out of the crypto layer.
/// </para>
/// <para>
/// Failures are split on purpose. Anything decidable without the key — bad magic, a malformed header, an
/// unknown kdf, a version from the future, an iteration count below the floor — gets its own code, so a broken
/// file is never reported as a wrong passphrase. Anything that requires the key collapses into
/// <see cref="ArchiveError.WrongPassphraseOrTampered"/>: AES-GCM verifies the tag before it decrypts, so a wrong
/// passphrase and an edited ciphertext, tag, or header are the same observable event. Merging them is the
/// honest answer and also the safe one — separate codes would hand an attacker an oracle for guessing which
/// part of a file they got right, and the error text never contains plaintext or its length.
/// </para>
/// <para>
/// GCM is not a solution to a weak passphrase: an attacker with the file can guess offline. That is why the
/// passphrase policy exists at write time and why ADR-S005 states the brute-force cost in the threat model
/// rather than implying the tag makes it impossible.
/// </para>
/// </remarks>
public static class EncryptedArchiveReader
{
    /// <summary>Reads the cleartext header without touching the passphrase.</summary>
    /// <exception cref="ArchiveException">The file is not a readable archive of a supported version and kdf.</exception>
    public static ArchiveHeader ReadHeader(byte[] archive)
    {
        if (!TryReadHeader(archive, out ArchiveHeader? header, out string? reason))
        {
            throw new ArchiveException(reason!, "The archive header could not be read: " + reason + ".");
        }

        return header!;
    }

    /// <summary>Reads and validates the header; the error is an <see cref="ArchiveError"/> code.</summary>
    /// <exception cref="ArgumentNullException"><paramref name="archive"/> is null.</exception>
    public static bool TryReadHeader(byte[] archive, out ArchiveHeader? header, out string? reason)
    {
        ArgumentNullException.ThrowIfNull(archive);
        return TryReadLayout(archive, out header, out _, out _, out reason);
    }

    /// <summary>Decrypts an archive back into the envelope it holds.</summary>
    /// <exception cref="ArgumentNullException"><paramref name="archive"/> is null.</exception>
    /// <exception cref="ArchiveException">
    /// Any <see cref="ArchiveError"/> code: a broken file, an unsupported version or kdf, a weak or wrong
    /// passphrase (see <see cref="ArchiveError.WrongPassphraseOrTampered"/>), an oversized payload, or a payload
    /// that is not a readable envelope or that claims to carry secrets.
    /// </exception>
    public static SourceSyncEnvelope Read(byte[] archive, string passphrase)
    {
        if (!TryRead(archive, passphrase, out SourceSyncEnvelope? envelope, out string? reason))
        {
            throw new ArchiveException(reason!, Describe(reason!));
        }

        return envelope!;
    }

    /// <summary>Non-throwing read; the error is an <see cref="ArchiveError"/> code.</summary>
    /// <exception cref="ArgumentNullException"><paramref name="archive"/> is null.</exception>
    public static bool TryRead(byte[] archive, string? passphrase, out SourceSyncEnvelope? envelope, out string? reason)
    {
        ArgumentNullException.ThrowIfNull(archive);
        envelope = null;

        if (!TryReadLayout(archive, out ArchiveHeader? header, out byte[]? aad, out int cipherLength, out reason))
        {
            return false;
        }

        // The policy is checked after the framing so "this file is from a newer version" wins over "you typed
        // nothing", which is the more useful answer for the two different mistakes.
        if (!ArchivePassphrase.TryValidate(passphrase, out reason))
        {
            return false;
        }

        int cipherOffset = aad!.Length;
        int tagOffset = cipherOffset + cipherLength;
        byte[] salt = Convert.FromBase64String(header!.SaltBase64);
        byte[] nonce = Convert.FromBase64String(header.NonceBase64);
        byte[] key = ArchiveKdf.DeriveKey(passphrase!, salt, header.Iterations);
        byte[] plaintext = new byte[cipherLength];

        try
        {
            using var aes = new AesGcm(key, ArchiveHeader.TagSize);
            try
            {
                aes.Decrypt(
                    nonce,
                    archive.AsSpan(cipherOffset, cipherLength),
                    archive.AsSpan(tagOffset, ArchiveHeader.TagSize),
                    plaintext,
                    aad);
            }
            catch (AuthenticationTagMismatchException)
            {
                reason = ArchiveError.WrongPassphraseOrTampered;
                return false;
            }

            return TryDecodePayload(plaintext, out envelope, out reason);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    private static bool TryDecodePayload(byte[] plaintext, out SourceSyncEnvelope? envelope, out string? reason)
    {
        envelope = null;
        reason = null;
        string json;
        try
        {
            json = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true)
                .GetString(plaintext);
        }
        catch (DecoderFallbackException)
        {
            reason = ArchiveError.MalformedPayload;
            return false;
        }

        if (!SourceSyncCodec.TryDeserialize(json, out SourceSyncEnvelope? decoded, out _))
        {
            reason = ArchiveError.MalformedPayload;
            return false;
        }

        // Defence in depth: a writer that never emits secrets plus a reader that refuses them means a doctored
        // archive cannot smuggle credentials into an import path that later starts trusting this format.
        if (decoded!.Capabilities.CanSendSecrets)
        {
            reason = ArchiveError.SecretsNotAllowed;
            return false;
        }

        envelope = decoded;
        return true;
    }

    /// <summary>
    /// Validates magic, framing, header, version, kdf, iteration floor and key material lengths, and returns the
    /// authenticated prefix plus the ciphertext length.
    /// </summary>
    private static bool TryReadLayout(
        byte[] archive,
        out ArchiveHeader? header,
        out byte[]? aad,
        out int cipherLength,
        out string? reason)
    {
        header = null;
        aad = null;
        cipherLength = 0;
        reason = null;

        byte[] magic = Encoding.ASCII.GetBytes(ArchiveHeader.CurrentMagic);
        if (archive.Length < magic.Length)
        {
            reason = ArchiveError.MagicMismatch;
            return false;
        }

        if (!archive.AsSpan(0, magic.Length).SequenceEqual(magic))
        {
            reason = ArchiveError.MagicMismatch;
            return false;
        }

        if (archive.Length < magic.Length + sizeof(int))
        {
            reason = ArchiveError.CorruptHeader;
            return false;
        }

        int headerLength = BinaryPrimitives.ReadInt32BigEndian(archive.AsSpan(magic.Length, sizeof(int)));
        int headerOffset = magic.Length + sizeof(int);
        if (headerLength <= 0
            || headerLength > ArchiveHeader.MaxHeaderBytes
            || headerLength > archive.Length - headerOffset)
        {
            reason = ArchiveError.CorruptHeader;
            return false;
        }

        byte[] headerBytes = archive.AsSpan(headerOffset, headerLength).ToArray();
        if (!ArchiveHeaderCodec.TryParse(headerBytes, out ArchiveHeader? parsed, out reason))
        {
            return false;
        }

        if (parsed!.Version != ArchiveHeader.CurrentVersion)
        {
            reason = ArchiveError.UnsupportedVersion(parsed.Version);
            return false;
        }

        if (!ArchiveKdf.TryParse(parsed.Kdf, out _))
        {
            reason = ArchiveError.UnknownKdf(parsed.Kdf);
            return false;
        }

        if (parsed.Iterations < ArchiveKdf.MinIterations)
        {
            reason = ArchiveError.InvalidIterations;
            return false;
        }

        if (!TryFromBase64(parsed.SaltBase64, ArchiveKdf.SaltSize) || !TryFromBase64(parsed.NonceBase64, ArchiveHeader.NonceSize))
        {
            reason = ArchiveError.CorruptHeader;
            return false;
        }

        int trailer = headerOffset + headerLength;
        cipherLength = archive.Length - trailer - ArchiveHeader.TagSize;
        if (cipherLength <= 0)
        {
            // Empty ciphertext is legal in GCM but never legal here: an archive always holds at least "{}".
            reason = ArchiveError.CorruptCiphertext;
            return false;
        }

        if (cipherLength > ArchiveHeader.MaxPayloadBytes)
        {
            reason = ArchiveError.PayloadTooLarge;
            return false;
        }

        header = parsed;
        aad = archive.AsSpan(0, trailer).ToArray();
        return true;
    }

    private static bool TryFromBase64(string value, int expectedLength)
    {
        try
        {
            return Convert.FromBase64String(value).Length == expectedLength;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    /// <summary>Maps a reason code to a message that never quotes payload bytes.</summary>
    private static string Describe(string reason)
    {
        if (reason == ArchiveError.WrongPassphraseOrTampered)
        {
            return "The archive could not be decrypted: the passphrase is wrong or the file has been modified.";
        }

        if (reason == ArchiveError.SecretsNotAllowed)
        {
            return "The archive claims to carry credentials; refusing to import it.";
        }

        if (reason.StartsWith(ArchiveError.MalformedPayload, StringComparison.Ordinal))
        {
            return "The archive decrypted but does not hold a readable source envelope.";
        }

        return "The archive could not be read: " + reason + ".";
    }
}

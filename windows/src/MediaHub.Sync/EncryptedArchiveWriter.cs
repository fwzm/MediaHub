using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// Writes the encrypted offline archive (.mharchive) — ADR-S005.
/// </summary>
/// <remarks>
/// <para>
/// Layout, and why it is this shape: <c>magic(8) | headerLength(4, big-endian) | headerJson | ciphertext | tag(16)</c>.
/// The magic and length come first so a reader can reject a foreign file without parsing anything; the header
/// is cleartext JSON because "which kdf, which version, which iterations" must be answerable without the
/// passphrase; the header's exact bytes are the AES-GCM associated data, so the tag covers them. Nothing is
/// encrypted that a support conversation may need, and nothing the attacker can edit is left unauthenticated.
/// </para>
/// <para>
/// The plaintext is the existing <see cref="SourceSyncEnvelope"/> JSON, which keeps one wire format for
/// transports and archives. Secrets are never in it: ADR-S001 D6 keeps credentials off the wire and out of
/// files written for transfer, so an envelope that even claims it can send secrets is refused rather than
/// silently stripped — a caller that believes it exported credentials must be told that it did not.
/// </para>
/// <para>
/// Every archive gets a fresh 16-byte salt and a fresh 12-byte nonce, and a nonce is never reused under a key:
/// AES-GCM loses all confidentiality and authenticity guarantees on nonce reuse, so the nonce is generated per
/// call and never derived from the passphrase or the clock.
/// </para>
/// </remarks>
public static class EncryptedArchiveWriter
{
    /// <summary>Writes an envelope as an encrypted archive.</summary>
    /// <param name="envelope">The source envelope; must not carry secrets.</param>
    /// <param name="passphrase">The passphrase protecting the archive.</param>
    /// <param name="iterations">PBKDF2 iterations; at least <see cref="ArchiveKdf.MinIterations"/>.</param>
    /// <param name="createdAt">Timestamp written into the header; defaults to now. Informational only.</param>
    /// <returns>The complete archive bytes, ready to be written to disk by the caller.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="envelope"/> is null.</exception>
    /// <exception cref="ArchiveException">
    /// The passphrase is empty or weak, the envelope carries or claims secrets, the iteration count is below the
    /// floor, or the serialised payload exceeds <see cref="ArchiveHeader.MaxPayloadBytes"/>.
    /// </exception>
    public static byte[] Write(
        SourceSyncEnvelope envelope,
        string passphrase,
        int iterations = ArchiveKdf.DefaultIterations,
        DateTimeOffset? createdAt = null)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        ArchivePassphrase.Validate(passphrase);

        if (envelope.Capabilities.CanSendSecrets || CarriesSecrets(envelope))
        {
            throw new ArchiveException(
                ArchiveError.SecretsNotAllowed,
                "An archive never carries credentials; write an envelope with secrets disabled.");
        }

        if (iterations < ArchiveKdf.MinIterations)
        {
            throw new ArchiveException(
                ArchiveError.InvalidIterations,
                "The iteration count must be at least " + ArchiveKdf.MinIterations + ".");
        }

        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));
        if (payload.Length > ArchiveHeader.MaxPayloadBytes)
        {
            throw new ArchiveException(
                ArchiveError.PayloadTooLarge,
                "The payload exceeds " + ArchiveHeader.MaxPayloadBytes + " bytes.");
        }

        byte[] salt = RandomNumberGenerator.GetBytes(ArchiveKdf.SaltSize);
        byte[] nonce = RandomNumberGenerator.GetBytes(ArchiveHeader.NonceSize);
        long createdAtEpochMs = (createdAt ?? DateTimeOffset.UtcNow).ToUnixTimeMilliseconds();

        var header = new ArchiveHeader
        {
            Version = ArchiveHeader.CurrentVersion,
            Kdf = ArchiveKdf.Pbkdf2Sha256Name,
            Iterations = iterations,
            SaltBase64 = Convert.ToBase64String(salt),
            NonceBase64 = Convert.ToBase64String(nonce),
            CreatedAtEpochMs = createdAtEpochMs,
        };

        byte[] headerJson = ArchiveHeaderCodec.Serialize(header);
        byte[] prefix = BuildPrefix(headerJson);
        byte[] ciphertext = new byte[payload.Length];
        byte[] tag = new byte[ArchiveHeader.TagSize];
        byte[] key = ArchiveKdf.DeriveKey(passphrase, salt, iterations);

        try
        {
            using var aes = new AesGcm(key, ArchiveHeader.TagSize);
            aes.Encrypt(nonce, payload, ciphertext, tag, prefix);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(payload);
        }

        byte[] archive = new byte[prefix.Length + ciphertext.Length + tag.Length];
        prefix.CopyTo(archive, 0);
        ciphertext.CopyTo(archive, prefix.Length);
        tag.CopyTo(archive, prefix.Length + ciphertext.Length);
        return archive;
    }

    /// <summary>Builds the authenticated cleartext prefix: magic, big-endian header length, header JSON.</summary>
    internal static byte[] BuildPrefix(byte[] headerJson)
    {
        byte[] magic = Encoding.ASCII.GetBytes(ArchiveHeader.CurrentMagic);
        byte[] prefix = new byte[magic.Length + sizeof(int) + headerJson.Length];
        magic.CopyTo(prefix, 0);
        BinaryPrimitives.WriteInt32BigEndian(prefix.AsSpan(magic.Length, sizeof(int)), headerJson.Length);
        headerJson.CopyTo(prefix, magic.Length + sizeof(int));
        return prefix;
    }

    private static bool CarriesSecrets(SourceSyncEnvelope envelope)
    {
        foreach (SourceRecord source in envelope.Sources)
        {
            if (source.Secrets is { } secrets && secrets.ValueKind != System.Text.Json.JsonValueKind.Null)
            {
                return true;
            }
        }

        return false;
    }
}

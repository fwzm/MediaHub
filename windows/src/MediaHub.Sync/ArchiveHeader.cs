using System.Globalization;
using System.Text.Json;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// The cleartext header of an <c>.mharchive</c> file (ADR-S005 D4).
/// </summary>
/// <remarks>
/// <para>
/// The header is JSON on purpose: it is the one part of the file a person may have to look at when a support
/// question starts with "which version wrote this", and a four-field binary struct would buy a few hundred
/// bytes at the cost of that answer. It carries nothing secret — kdf name, iteration count, salt, nonce, and
/// the creation time. The salt and nonce are public by construction: their job is uniqueness, not secrecy.
/// </para>
/// <para>
/// These bytes are the AES-GCM associated data, so any edit here — the iteration count, the timestamp, the
/// kdf name — is detected by the tag even though nothing in the header is encrypted. Binding the header is
/// what stops an attacker from lowering the iteration count and handing the file back.
/// </para>
/// </remarks>
public sealed record ArchiveHeader
{
    /// <summary>File signature, ASCII, first eight bytes of every archive.</summary>
    public const string CurrentMagic = "MHSYNCA1";

    /// <summary>Format version this build writes and the only one it reads.</summary>
    public const int CurrentVersion = 1;

    /// <summary>Nonce length in bytes (AES-GCM standard size).</summary>
    public const int NonceSize = 12;

    /// <summary>Authentication tag length in bytes.</summary>
    public const int TagSize = 16;

    /// <summary>Largest header accepted or written; a header bigger than this is corrupt, not interesting.</summary>
    public const int MaxHeaderBytes = 4096;

    /// <summary>Largest plaintext accepted; keeps a hand-edited header from asking for a gigabyte of work.</summary>
    public const int MaxPayloadBytes = 8 * 1024 * 1024;

    /// <summary>Format version (<c>1</c>).</summary>
    public int Version { get; init; } = CurrentVersion;

    /// <summary>Kdf wire name, e.g. <c>pbkdf2-sha256</c> (see <see cref="ArchiveKdf"/>).</summary>
    public string Kdf { get; init; } = ArchiveKdf.Pbkdf2Sha256Name;

    /// <summary>PBKDF2 iteration count used for this file.</summary>
    public int Iterations { get; init; } = ArchiveKdf.DefaultIterations;

    /// <summary>Base64 of the 16-byte salt.</summary>
    public string SaltBase64 { get; init; } = "";

    /// <summary>Base64 of the 12-byte nonce this file was encrypted with.</summary>
    public string NonceBase64 { get; init; } = "";

    /// <summary>Wall-clock creation time in milliseconds since the Unix epoch. Informational.</summary>
    public long CreatedAtEpochMs { get; init; }
}

/// <summary>
/// JSON codec for <see cref="ArchiveHeader"/>: camelCase names, fixed field order, no whitespace.
/// </summary>
/// <remarks>
/// Parsing never reorders or normalises anything: the reader authenticates the exact bytes it found on disk,
/// so a re-serialised header would not match the associated data even when the values are identical.
/// </remarks>
public static class ArchiveHeaderCodec
{
    /// <summary>Serialises a header to the canonical bytes written into the file.</summary>
    public static byte[] Serialize(ArchiveHeader header)
    {
        ArgumentNullException.ThrowIfNull(header);

        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteNumber("version", header.Version);
            writer.WriteString("kdf", header.Kdf);
            writer.WriteNumber("iterations", header.Iterations);
            writer.WriteString("saltBase64", header.SaltBase64);
            writer.WriteString("nonceBase64", header.NonceBase64);
            writer.WriteNumber("createdAtEpochMs", header.CreatedAtEpochMs);
            writer.WriteEndObject();
        }

        return buffer.ToArray();
    }

    /// <summary>Parses header bytes; the error is an <see cref="ArchiveError"/> code.</summary>
    public static bool TryParse(byte[] headerBytes, out ArchiveHeader? header, out string? error)
    {
        header = null;
        error = null;
        if (headerBytes is null || headerBytes.Length == 0)
        {
            error = ArchiveError.CorruptHeader;
            return false;
        }

        JsonDocument document;
        try
        {
            // JsonDocument decodes some string tokens lazily. Reject invalid bytes
            // here so TryParse cannot throw later while extracting a known field.
            _ = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true)
                .GetCharCount(headerBytes);
            document = JsonDocument.Parse(headerBytes);
        }
        catch (DecoderFallbackException)
        {
            error = ArchiveError.CorruptHeader;
            return false;
        }
        catch (JsonException)
        {
            error = ArchiveError.CorruptHeader;
            return false;
        }

        using (document)
        {
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                error = ArchiveError.CorruptHeader;
                return false;
            }

            if (!TryReadInt32(root, "version", out int version)
                || !TryReadInt32(root, "iterations", out int iterations)
                || !TryReadInt64(root, "createdAtEpochMs", out long createdAt)
                || !TryReadString(root, "kdf", out string? kdf)
                || !TryReadString(root, "saltBase64", out string? salt)
                || !TryReadString(root, "nonceBase64", out string? nonce))
            {
                error = ArchiveError.CorruptHeader;
                return false;
            }

            header = new ArchiveHeader
            {
                Version = version,
                Kdf = kdf!,
                Iterations = iterations,
                SaltBase64 = salt!,
                NonceBase64 = nonce!,
                CreatedAtEpochMs = createdAt,
            };
            return true;
        }
    }

    private static bool TryReadInt32(JsonElement root, string name, out int value)
    {
        value = 0;
        return root.TryGetProperty(name, out JsonElement element)
            && element.ValueKind == JsonValueKind.Number
            && element.TryGetInt32(out value);
    }

    private static bool TryReadInt64(JsonElement root, string name, out long value)
    {
        value = 0;
        return root.TryGetProperty(name, out JsonElement element)
            && element.ValueKind == JsonValueKind.Number
            && element.TryGetInt64(out value);
    }

    private static bool TryReadString(JsonElement root, string name, out string? value)
    {
        value = null;
        if (!root.TryGetProperty(name, out JsonElement element) || element.ValueKind != JsonValueKind.String)
        {
            return false;
        }

        value = element.GetString();
        return value is not null;
    }
}

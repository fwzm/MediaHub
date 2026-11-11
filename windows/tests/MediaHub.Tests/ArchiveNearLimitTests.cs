using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-68: a real payload of exactly the 8 MiB archive ceiling has to survive AES-256-GCM, the writer, the
/// reader and a field-by-field round trip. The older tests only pinned the two refusals (writer refuses
/// MaxPayloadBytes + 1, reader refuses a MaxPayloadBytes + 1 ciphertext before decrypting); nothing proved
/// that the boundary itself still works, and a payload of exactly the ceiling is the one an operator is most
/// likely to hit with a large library.
///
/// The answer key is the nearLimit section of windows-archive-vectors.json, produced by the Windows
/// implementation (temporary generator, not in the repo). The recipe stores a display name length, not the
/// 8 MiB itself: it only records the payload length and sha256, the header bytes, the archive length and
/// sha256, plus the two refusals.
/// </summary>
public sealed class ArchiveNearLimitTests
{
    private const string Passphrase = "correct horse battery staple";

    private static readonly JsonNode Vectors = JsonNode.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "assets", "windows-archive-vectors.json")))!;

    private static JsonNode NearLimit => Vectors["nearLimit"]!;

    private static JsonNode Recipe => NearLimit["recipe"]!;

    private static string Text(JsonNode node) => node.GetValue<string>();

    private static SourceSyncEnvelope EnvelopeWithDisplayName(int displayNameLength)
    {
        JsonNode recipe = Recipe;
        JsonNode capabilities = recipe["capabilities"]!;
        return new SourceSyncEnvelope
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            DeviceId = Text(recipe["deviceId"]!),
            SnapshotId = Text(recipe["snapshotId"]!),
            CreatedAtEpochMs = recipe["createdAtEpochMs"]!.GetValue<long>(),
            Capabilities = new SyncCapabilities
            {
                CanSendSecrets = capabilities["canSendSecrets"]!.GetValue<bool>(),
                SourceTypes = [Text(capabilities["sourceTypes"]![0]!)],
            },
            Sources =
            [
                new SourceRecord
                {
                    SyncSourceId = Text(recipe["syncSourceId"]!),
                    SourceType = SyncSourceTypeValue.Parse(Text(recipe["sourceType"]!)),
                    DisplayName = new string(Text(recipe["displayNameUnit"]!)[0], displayNameLength),
                    AccountScope = null,
                    Endpoints = [],
                    RecordRevision = recipe["recordRevision"]!.GetValue<long>(),
                    FieldVersion = recipe["fieldVersion"]!.GetValue<int>(),
                    Secrets = null,
                },
            ],
            Deletions = [],
        };
    }

    private static SourceSyncEnvelope Envelope() =>
        EnvelopeWithDisplayName(Recipe["displayNameLength"]!.GetValue<int>());

    private static ArchiveHeader Header() => new()
    {
        Version = ArchiveHeader.CurrentVersion,
        Kdf = ArchiveKdf.Pbkdf2Sha256Name,
        Iterations = Recipe["iterations"]!.GetValue<int>(),
        SaltBase64 = Text(Recipe["saltBase64"]!),
        NonceBase64 = Text(Recipe["nonceBase64"]!),
        CreatedAtEpochMs = Recipe["createdAtEpochMs"]!.GetValue<long>(),
    };

    /// <summary>Encrypts with the pinned salt/nonce so the bytes can be compared with the Windows archive.</summary>
    private static byte[] Craft(string payloadJson)
    {
        ArchiveHeader header = Header();
        byte[] prefix = Prefix(ArchiveHeaderCodec.Serialize(header));
        byte[] salt = Convert.FromBase64String(header.SaltBase64);
        byte[] nonce = Convert.FromBase64String(header.NonceBase64);
        byte[] payload = Encoding.UTF8.GetBytes(payloadJson);
        byte[] key = ArchiveKdf.DeriveKey(Passphrase, salt, header.Iterations);
        byte[] ciphertext = new byte[payload.Length];
        byte[] tag = new byte[ArchiveHeader.TagSize];
        try
        {
            using var aes = new AesGcm(key, ArchiveHeader.TagSize);
            aes.Encrypt(nonce, payload, ciphertext, tag, prefix);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }
        return [.. prefix, .. ciphertext, .. tag];
    }

    private static byte[] Prefix(byte[] headerJson)
    {
        byte[] magic = Encoding.ASCII.GetBytes(ArchiveHeader.CurrentMagic);
        byte[] prefix = new byte[magic.Length + sizeof(int) + headerJson.Length];
        magic.CopyTo(prefix, 0);
        BinaryPrimitives.WriteInt32BigEndian(prefix.AsSpan(magic.Length, sizeof(int)), headerJson.Length);
        headerJson.CopyTo(prefix, magic.Length + sizeof(int));
        return prefix;
    }

    private static string Sha256Hex(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    // ------------------------------------------------------------ the recipe and the payload

    [Fact]
    public void TheRecipeReproducesTheWindowsPayloadByteForByte()
    {
        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(Envelope()));

        Assert.Equal(ArchiveHeader.MaxPayloadBytes, payload.Length);
        Assert.Equal(NearLimit["payloadByteLength"]!.GetValue<int>(), payload.Length);
        Assert.Equal(Text(NearLimit["payloadSha256"]!), Sha256Hex(payload));
    }

    [Fact]
    public void TheHeaderFromTheRecipeIsTheCanonicalAadWindowsUsed()
    {
        byte[] headerJson = ArchiveHeaderCodec.Serialize(Header());
        byte[] expected = Convert.FromBase64String(Text(NearLimit["headerBase64"]!));

        Assert.True(headerJson.SequenceEqual(expected), "the header JSON must be the exact AAD Windows used");
        Assert.Equal(NearLimit["prefixByteLength"]!.GetValue<int>(), Prefix(headerJson).Length);
        Assert.Equal(
            Encoding.ASCII.GetByteCount(ArchiveHeader.CurrentMagic) + sizeof(int) + headerJson.Length,
            Prefix(headerJson).Length);
    }

    // ------------------------------------------------------------ 8 MiB through AES-256-GCM

    [Fact]
    public void APayloadOfExactlyTheCeilingEncryptsToTheWindowsArchiveHashAndReadsBack()
    {
        SourceSyncEnvelope envelope = Envelope();
        string payloadJson = SourceSyncCodec.Serialize(envelope);
        byte[] archive = Craft(payloadJson);

        Assert.Equal(NearLimit["archiveByteLength"]!.GetValue<int>(), archive.Length);
        Assert.Equal(Text(NearLimit["archiveSha256"]!), Sha256Hex(archive));

        SourceSyncEnvelope decoded = EncryptedArchiveReader.Read(archive, Passphrase);
        Assert.Equal(payloadJson, SourceSyncCodec.Serialize(decoded));
        Assert.Equal(envelope.Sources[0].DisplayName, decoded.Sources[0].DisplayName);
        Assert.Equal(ArchiveHeader.MaxPayloadBytes, Encoding.UTF8.GetByteCount(SourceSyncCodec.Serialize(decoded)));
    }

    [Fact]
    public void TheWriterAcceptsExactlyTheCeilingAndRefusesOneByteMore()
    {
        JsonNode tooLargeNode = NearLimit["tooLarge"]!;
        SourceSyncEnvelope envelope = Envelope();

        Assert.Equal(ArchiveHeader.MaxPayloadBytes, Encoding.UTF8.GetByteCount(SourceSyncCodec.Serialize(envelope)));

        // The real writer picks its own random salt/nonce, so the bytes cannot be pinned; the round trip can.
        byte[] written = EncryptedArchiveWriter.Write(
            envelope,
            Passphrase,
            ArchiveKdf.MinIterations,
            DateTimeOffset.FromUnixTimeMilliseconds(Recipe["createdAtEpochMs"]!.GetValue<long>()));
        SourceSyncEnvelope decoded = EncryptedArchiveReader.Read(written, Passphrase);
        Assert.Equal(SourceSyncCodec.Serialize(envelope), SourceSyncCodec.Serialize(decoded));

        SourceSyncEnvelope tooLarge = EnvelopeWithDisplayName(tooLargeNode["displayNameLength"]!.GetValue<int>());
        Assert.Equal(tooLargeNode["payloadByteLength"]!.GetValue<int>(), Encoding.UTF8.GetByteCount(SourceSyncCodec.Serialize(tooLarge)));
        Assert.Equal(ArchiveHeader.MaxPayloadBytes + 1, Encoding.UTF8.GetByteCount(SourceSyncCodec.Serialize(tooLarge)));
        ArchiveException error = Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(tooLarge, Passphrase));
        Assert.Equal(Text(tooLargeNode["writeReason"]!), error.Reason);
        Assert.Equal(ArchiveError.PayloadTooLarge, error.Reason);
    }

    [Fact]
    public void TheReaderRefusesAnOversizedCiphertextBeforeDecryption()
    {
        JsonNode oversized = NearLimit["oversizedCiphertext"]!;
        int cipherLength = oversized["cipherLength"]!.GetValue<int>();
        Assert.Equal(ArchiveHeader.MaxPayloadBytes + 1, cipherLength);

        byte[] prefix = Prefix(ArchiveHeaderCodec.Serialize(Header()));
        byte[] candidate = [.. prefix, .. new byte[cipherLength], .. new byte[ArchiveHeader.TagSize]];
    
        Assert.False(EncryptedArchiveReader.TryRead(candidate, Passphrase, out SourceSyncEnvelope? decoded, out string? reason));
        Assert.Null(decoded);
        Assert.Equal(Text(oversized["readReason"]!), reason);
        Assert.Equal(ArchiveError.PayloadTooLarge, reason);
    }
}

using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the encrypted offline archive contract (ADR-S005): layout, header, AES-256-GCM usage, error codes, and
/// the passphrase policy. The tests are also the reference implementation of the format — several of them
/// assemble an archive byte by byte so that a malformed file can be produced on purpose.
/// </summary>
public sealed class ArchiveEncryptionTests
{
    [Fact]
    public void ReaderRejectsActualSecretsWhenCapabilityFlagIsFalse()
    {
        using var secret = JsonDocument.Parse("{\"opaque\":\"fixture-only\"}");
        SourceSyncEnvelope envelope = Envelope(Record("source-1", secrets: secret.RootElement.Clone()));
        Assert.False(envelope.Capabilities.CanSendSecrets);
        byte[] archive = CraftArchive(NewHeader(), Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope)), Passphrase);
        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.SecretsNotAllowed, reason);
    }

    [Fact]
    public void ReaderAllowsAnExplicitJsonNullSecretsBlock()
    {
        using var secret = JsonDocument.Parse("null");
        SourceSyncEnvelope envelope = Envelope(Record("source-1", secrets: secret.RootElement.Clone()));
        string json = SourceSyncCodec.Serialize(envelope);
        Assert.Contains("\"secrets\":null", json);
        byte[] archive = CraftArchive(NewHeader(), Encoding.UTF8.GetBytes(json), Passphrase);
        Assert.True(EncryptedArchiveReader.TryRead(archive, Passphrase, out SourceSyncEnvelope? result, out string? reason));
        Assert.Null(reason);
        Assert.Null(Assert.Single(result!.Sources).Secrets);
    }

    [Fact]
    public void InvalidUtf8HeaderIsRejectedBeforePassphraseValidation()
    {
        byte[] header = ArchiveHeaderCodec.Serialize(NewHeader());
        int offset = Encoding.UTF8.GetString(header).IndexOf(ArchiveKdf.Pbkdf2Sha256Name, StringComparison.Ordinal);
        Assert.True(offset >= 0);
        header[offset] = 0xff;
        byte[] archive = [.. Prefix(header), .. new byte[1 + ArchiveHeader.TagSize]];
        Assert.False(EncryptedArchiveReader.TryRead(archive, null, out _, out string? reason));
        Assert.Equal(ArchiveError.CorruptHeader, reason);
    }

    private const string Passphrase = "correct horse battery staple";

    private const string OtherPassphrase = "a different long passphrase";

    [Fact]
    public void Roundtrip_PreservesTheEnvelopeFieldByField()
    {
        SourceSyncEnvelope envelope = Envelope(
            Record(
                "source-1",
                displayName: "Home NAS",
                revision: 7,
                fieldVersion: 2,
                accountScope: "alex",
                endpoints:
                [
                    new SourceEndpoint { Url = "http://nas.local:8096", IsPrimary = false, SortOrder = 1 },
                    new SourceEndpoint { Url = "https://nas.example.com", IsPrimary = true, SortOrder = 0 },
                ]),
            Record("source-2", displayName: "Attic", url: "http://attic:8096", revision: 1)) with
        {
            Deletions = [new Tombstone { SyncSourceId = "gone-1", DeletedAtEpochMs = 1_700_000_000_500, RecordRevision = 3 }],
        };

        SourceSyncEnvelope decoded = EncryptedArchiveReader.Read(
            EncryptedArchiveWriter.Write(envelope, Passphrase),
            Passphrase);

        Assert.Equal(envelope.DeviceId, decoded.DeviceId);
        Assert.Equal(envelope.SnapshotId, decoded.SnapshotId);
        Assert.Equal(envelope.CreatedAtEpochMs, decoded.CreatedAtEpochMs);
        Assert.Equal(envelope.SchemaVersion, decoded.SchemaVersion);
        Assert.Equal(envelope.MinimumReaderVersion, decoded.MinimumReaderVersion);
        Assert.False(decoded.Capabilities.CanSendSecrets);
        Assert.Equal(envelope.Capabilities.SourceTypes, decoded.Capabilities.SourceTypes);
        Assert.Equal(2, decoded.Sources.Count);

        SourceRecord first = decoded.Sources[0];
        Assert.Equal("source-1", first.SyncSourceId);
        Assert.Equal(SyncSourceTypes.Emby, first.SourceType.Wire);
        Assert.True(first.SourceType.IsKnown);
        Assert.Equal("Home NAS", first.DisplayName);
        Assert.Equal("alex", first.AccountScope);
        Assert.Equal(7, first.RecordRevision);
        Assert.Equal(2, first.FieldVersion);
        Assert.Null(first.Secrets);
        Assert.Equal(2, first.Endpoints.Count);
        Assert.Equal("http://nas.local:8096", first.Endpoints[0].Url);
        Assert.Equal(1, first.Endpoints[0].SortOrder);
        Assert.False(first.Endpoints[0].IsPrimary);
        Assert.True(first.Endpoints[0].Enabled);
        Assert.Equal("https://nas.example.com", first.Endpoints[1].Url);
        Assert.True(first.Endpoints[1].IsPrimary);

        Tombstone deletion = Assert.Single(decoded.Deletions);
        Assert.Equal("gone-1", deletion.SyncSourceId);
        Assert.Equal(1_700_000_000_500, deletion.DeletedAtEpochMs);
        Assert.Equal(3, deletion.RecordRevision);
    }

    [Fact]
    public void Roundtrip_PreservesChineseNamesAndAccountScopes()
    {
        SourceSyncEnvelope envelope = Envelope(Record("source-cn", displayName: "家庭 NAS（客厅）", accountScope: "张三"));

        byte[] archive = EncryptedArchiveWriter.Write(envelope, Passphrase);
        SourceSyncEnvelope decoded = EncryptedArchiveReader.Read(archive, Passphrase);

        SourceRecord record = Assert.Single(decoded.Sources);
        Assert.Equal("家庭 NAS（客厅）", record.DisplayName);
        Assert.Equal("张三", record.AccountScope);
        Assert.Equal(SourceSyncCodec.Serialize(envelope), SourceSyncCodec.Serialize(decoded));
    }

    [Fact]
    public void Roundtrip_OfAnEmptyEnvelopeStaysEmpty()
    {
        SourceSyncEnvelope decoded = EncryptedArchiveReader.Read(
            EncryptedArchiveWriter.Write(Envelope(), Passphrase),
            Passphrase);

        Assert.Empty(decoded.Sources);
        Assert.Empty(decoded.Deletions);
        Assert.Equal("device-a", decoded.DeviceId);
    }

    [Fact]
    public void Header_IsCleartextAndPinnedByTheLayout()
    {
        byte[] archive = EncryptedArchiveWriter.Write(
            Envelope(Record("source-1")),
            Passphrase,
            createdAt: DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_123_456));

        Assert.Equal("MHSYNCA1", Encoding.ASCII.GetString(archive, 0, 8));

        int headerLength = BinaryPrimitives.ReadInt32BigEndian(archive.AsSpan(8, 4));
        string json = Encoding.UTF8.GetString(archive, 12, headerLength);
        Assert.StartsWith("{\"version\":1,\"kdf\":\"pbkdf2-sha256\",\"iterations\":600000,", json);

        using var document = JsonDocument.Parse(archive.AsSpan(12, headerLength).ToArray());
        JsonElement root = document.RootElement;
        Assert.Equal(1, root.GetProperty("version").GetInt32());
        Assert.Equal("pbkdf2-sha256", root.GetProperty("kdf").GetString());
        Assert.Equal(ArchiveKdf.MinIterations, root.GetProperty("iterations").GetInt32());
        Assert.Equal(1_700_000_123_456, root.GetProperty("createdAtEpochMs").GetInt64());
        Assert.Equal(ArchiveKdf.SaltSize, Convert.FromBase64String(root.GetProperty("saltBase64").GetString()!).Length);
        Assert.Equal(ArchiveHeader.NonceSize, Convert.FromBase64String(root.GetProperty("nonceBase64").GetString()!).Length);

        ArchiveHeader header = EncryptedArchiveReader.ReadHeader(archive);
        Assert.Equal(ArchiveHeader.CurrentVersion, header.Version);
        Assert.Equal(1_700_000_123_456, header.CreatedAtEpochMs);
    }

    [Fact]
    public void WritingTwice_NeverReusesTheNonceOrTheCiphertext()
    {
        SourceSyncEnvelope envelope = Envelope(Record("source-1"));

        byte[] first = EncryptedArchiveWriter.Write(envelope, Passphrase);
        byte[] second = EncryptedArchiveWriter.Write(envelope, Passphrase);

        ArchiveHeader firstHeader = EncryptedArchiveReader.ReadHeader(first);
        ArchiveHeader secondHeader = EncryptedArchiveReader.ReadHeader(second);
        Assert.NotEqual(firstHeader.NonceBase64, secondHeader.NonceBase64);
        Assert.NotEqual(firstHeader.SaltBase64, secondHeader.SaltBase64);
        Assert.NotEqual(Convert.ToBase64String(first), Convert.ToBase64String(second));

        // Both still open with the same passphrase: the nonce changed, not the contract.
        Assert.Equal(
            SourceSyncCodec.Serialize(envelope),
            SourceSyncCodec.Serialize(EncryptedArchiveReader.Read(second, Passphrase)));
    }

    [Fact]
    public void WrongPassphrase_YieldsTheSameCodeAsTampering()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1")), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, OtherPassphrase, out _, out string? wrongPassphrase));
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, wrongPassphrase);

        byte[] tampered = (byte[])archive.Clone();
        tampered[^20] ^= 0xFF;
        Assert.False(EncryptedArchiveReader.TryRead(tampered, Passphrase, out _, out string? tamper));
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, tamper);
    }

    [Fact]
    public void TamperedCiphertext_IsRejected()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1", displayName: "Home NAS")), Passphrase);

        // A handful of offsets rather than every byte: each attempt costs a key derivation, and the point is
        // that no single flipped bit passes, not that the reader was exercised a thousand times.
        int[] offsets = [0, 9, 13, 18, archive.Length / 2, archive.Length - ArchiveHeader.TagSize - 1];
        foreach (int offset in offsets)
        {
            byte[] tampered = (byte[])archive.Clone();
            tampered[offset] ^= 0x01;
            Assert.False(EncryptedArchiveReader.TryRead(tampered, Passphrase, out _, out string? reason), "offset " + offset);
            Assert.NotNull(reason);
        }
    }

    [Fact]
    public void TamperedTag_IsRejected()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1")), Passphrase);
        byte[] tampered = (byte[])archive.Clone();
        tampered[^1] ^= 0x80;

        ArchiveException error = Assert.Throws<ArchiveException>(() => EncryptedArchiveReader.Read(tampered, Passphrase));
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, error.Reason);
    }

    [Fact]
    public void TamperedHeaderTimestamp_IsRejectedBecauseTheHeaderIsAuthenticated()
    {
        byte[] archive = EncryptedArchiveWriter.Write(
            Envelope(Record("source-1")),
            Passphrase,
            createdAt: DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000));

        string json = Encoding.UTF8.GetString(archive, 12, BinaryPrimitives.ReadInt32BigEndian(archive.AsSpan(8, 4)));
        Assert.Contains("1700000000000", json);
        byte[] forgedJson = Encoding.UTF8.GetBytes(json.Replace("1700000000000", "1700000000001", StringComparison.Ordinal));
        Assert.Equal(json.Length, forgedJson.Length);

        byte[] forged = [.. Prefix(forgedJson), .. archive.AsSpan(12 + json.Length)];

        // The header still parses and the structure is intact — only the tag knows.
        Assert.Equal(1_700_000_000_001, EncryptedArchiveReader.ReadHeader(forged).CreatedAtEpochMs);
        Assert.False(EncryptedArchiveReader.TryRead(forged, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, reason);
    }

    [Fact]
    public void ForeignFile_IsRejectedByTheMagic()
    {
        byte[] notAnArchive = Encoding.ASCII.GetBytes("PK\u0003\u0004 this is a zip, not an archive");

        Assert.False(EncryptedArchiveReader.TryRead(notAnArchive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.MagicMismatch, reason);
    }

    [Fact]
    public void TruncatedArchiveWithoutCiphertext_IsRejected()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1")), Passphrase);
        int headerEnd = 12 + BinaryPrimitives.ReadInt32BigEndian(archive.AsSpan(8, 4));

        Assert.False(EncryptedArchiveReader.TryRead(archive[..headerEnd], Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.CorruptCiphertext, reason);
    }

    [Fact]
    public void ArchiveTruncatedInsideTheTag_IsRejected()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1")), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive[..^1], Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.WrongPassphraseOrTampered, reason);
    }

    [Fact]
    public void EmptyInput_IsRejected()
    {
        Assert.False(EncryptedArchiveReader.TryRead([], Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.MagicMismatch, reason);
        Assert.ThrowsAny<ArchiveException>(() => EncryptedArchiveReader.ReadHeader([]));
    }

    [Fact]
    public void UnknownKdf_IsRejectedWithoutGuessingAnAlternative()
    {
        byte[] archive = CraftArchive(NewHeader(kdf: "argon2id"), Encoding.UTF8.GetBytes("{}"), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal("unknown-kdf:argon2id", reason);
        Assert.Equal(ArchiveError.UnknownKdf("argon2id"), reason);
    }

    [Fact]
    public void FutureVersion_IsRejected()
    {
        byte[] archive = CraftArchive(NewHeader(version: 2), Encoding.UTF8.GetBytes("{}"), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal("unsupported-version:2", reason);
        Assert.Throws<ArchiveException>(() => EncryptedArchiveReader.ReadHeader(archive));
    }

    [Fact]
    public void IterationsBelowTheFloor_AreRejectedWithoutKeyDerivation()
    {
        byte[] archive = CraftArchive(NewHeader(iterations: 1_000), Encoding.UTF8.GetBytes("{}"), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.InvalidIterations, reason);

        // Refused by the header reader too, i.e. before the passphrase is even considered.
        Assert.False(EncryptedArchiveReader.TryReadHeader(archive, out _, out string? headerReason));
        Assert.Equal(ArchiveError.InvalidIterations, headerReason);
    }

    [Fact]
    public void WriterRefusesAnEnvelopeThatClaimsToSendSecrets()
    {
        SourceSyncEnvelope envelope = Envelope(Record("source-1")) with
        {
            Capabilities = new SyncCapabilities { CanSendSecrets = true, SourceTypes = [SyncSourceTypes.Emby] },
        };

        ArchiveException error = Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(envelope, Passphrase));
        Assert.Equal(ArchiveError.SecretsNotAllowed, error.Reason);
    }

    [Fact]
    public void WriterRefusesAnEnvelopeThatCarriesASecretBlockEvenWhenTheFlagIsOff()
    {
        using JsonDocument secret = JsonDocument.Parse("{\"password\":\"hunter2\"}");
        SourceSyncEnvelope envelope = Envelope(Record("source-1", secrets: secret.RootElement.Clone()));

        ArchiveException error = Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(envelope, Passphrase));
        Assert.Equal(ArchiveError.SecretsNotAllowed, error.Reason);
    }

    [Fact]
    public void WriterRefusesAnIterationCountBelowTheFloor()
    {
        ArchiveException error = Assert.Throws<ArchiveException>(
            () => EncryptedArchiveWriter.Write(Envelope(Record("source-1")), Passphrase, iterations: 1_000));
        Assert.Equal(ArchiveError.InvalidIterations, error.Reason);
    }

    [Fact]
    public void ReaderRefusesAPayloadThatClaimsToSendSecrets()
    {
        SourceSyncEnvelope envelope = Envelope(Record("source-1")) with
        {
            Capabilities = new SyncCapabilities { CanSendSecrets = true, SourceTypes = [SyncSourceTypes.Emby] },
        };
        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));
        byte[] archive = CraftArchive(NewHeader(), payload, Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.SecretsNotAllowed, reason);
    }

    [Fact]
    public void ReaderRefusesAPayloadThatIsNotAnEnvelope()
    {
        byte[] archive = CraftArchive(NewHeader(), Encoding.UTF8.GetBytes("{\"hello\":\"world\"}"), Passphrase);

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.MalformedPayload, reason);
    }

    [Theory]
    [InlineData("password1234")]
    [InlineData("123456789012")]
    [InlineData("aaaaaaaaaaaa")]
    [InlineData("Qwertyuiop123")]
    public void WeakPassphrases_AreRejected(string passphrase)
    {
        Assert.False(ArchivePassphrase.TryValidate(passphrase, out string? reason));
        Assert.Equal(ArchiveError.PassphraseTooWeak, reason);
        Assert.Equal(
            ArchiveError.PassphraseTooWeak,
            Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(Envelope(), passphrase)).Reason);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("            ")]
    public void EmptyPassphrases_AreRejected(string? passphrase)
    {
        Assert.False(ArchivePassphrase.TryValidate(passphrase, out string? reason));
        Assert.Equal(ArchiveError.PassphraseEmpty, reason);
        Assert.Equal(
            ArchiveError.PassphraseEmpty,
            Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(Envelope(), passphrase!)).Reason);
    }

    [Fact]
    public void PassphrasePolicy_AcceptsALongUncommonSentence()
    {
        Assert.True(ArchivePassphrase.TryValidate(Passphrase, out string? reason));
        Assert.Null(reason);
        Assert.True(ArchivePassphrase.TryValidate("我的 NAS 放在阁楼最里面", out _));
        Assert.Equal(12, ArchivePassphrase.MinLength);

        // The boundary itself: eleven characters are refused, twelve are not, so the sample above cannot drift.
        Assert.False(ArchivePassphrase.TryValidate("我的 NAS 在阁楼里", out string? shortReason));
        Assert.Equal(ArchiveError.PassphraseTooWeak, shortReason);
        Assert.True(ArchivePassphrase.TryValidate("我的 NAS 放进了阁楼", out _));
    }

    [Fact]
    public void ArchiveBytesContainNoPlaintext()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1", displayName: "Home NAS")), Passphrase);

        // Latin1 maps one byte to one char, so this is a byte search, not a decoding.
        string asBytes = Encoding.Latin1.GetString(archive);
        Assert.DoesNotContain("Home NAS", asBytes, StringComparison.Ordinal);
        Assert.DoesNotContain("displayName", asBytes, StringComparison.Ordinal);
        Assert.DoesNotContain("device-a", asBytes, StringComparison.Ordinal);
        Assert.DoesNotContain("sources", asBytes, StringComparison.Ordinal);
        Assert.Contains("MHSYNCA1", asBytes, StringComparison.Ordinal);
    }

    [Fact]
    public void OversizedCiphertext_IsRejectedBeforeDecryption()
    {
        byte[] ciphertext = new byte[ArchiveHeader.MaxPayloadBytes + 1];
        byte[] archive = [.. Prefix(ArchiveHeaderCodec.Serialize(NewHeader())), .. ciphertext, .. new byte[ArchiveHeader.TagSize]];

        Assert.False(EncryptedArchiveReader.TryRead(archive, Passphrase, out _, out string? reason));
        Assert.Equal(ArchiveError.PayloadTooLarge, reason);
    }

    [Fact]
    public void OversizedPayload_IsRejectedWhenWriting()
    {
        SourceSyncEnvelope envelope = Envelope(Record("source-1", displayName: new string('x', 9_000_000)));

        ArchiveException error = Assert.Throws<ArchiveException>(() => EncryptedArchiveWriter.Write(envelope, Passphrase));
        Assert.Equal(ArchiveError.PayloadTooLarge, error.Reason);
    }

    [Fact]
    public void ReaderIsPureMemory_InputIsUntouchedAndReadingIsRepeatable()
    {
        byte[] archive = EncryptedArchiveWriter.Write(Envelope(Record("source-1", displayName: "Home NAS")), Passphrase);
        byte[] before = (byte[])archive.Clone();

        SourceSyncEnvelope first = EncryptedArchiveReader.Read(archive, Passphrase);
        SourceSyncEnvelope second = EncryptedArchiveReader.Read(archive, Passphrase);

        Assert.Equal(before, archive);
        Assert.Equal(SourceSyncCodec.Serialize(first), SourceSyncCodec.Serialize(second));
        Assert.Equal("Home NAS", Assert.Single(second.Sources).DisplayName);
    }

    [Fact]
    public void DeriveKey_IsDeterministicAndSaltSensitive()
    {
        byte[] salt = RandomNumberGenerator.GetBytes(ArchiveKdf.SaltSize);
        byte[] otherSalt = RandomNumberGenerator.GetBytes(ArchiveKdf.SaltSize);

        Assert.Equal(
            ArchiveKdf.DeriveKey(Passphrase, salt, ArchiveKdf.MinIterations),
            ArchiveKdf.DeriveKey(Passphrase, salt, ArchiveKdf.MinIterations));
        Assert.NotEqual(
            ArchiveKdf.DeriveKey(Passphrase, salt, ArchiveKdf.MinIterations),
            ArchiveKdf.DeriveKey(Passphrase, otherSalt, ArchiveKdf.MinIterations));
        Assert.NotEqual(
            ArchiveKdf.DeriveKey(Passphrase, salt, ArchiveKdf.MinIterations),
            ArchiveKdf.DeriveKey(OtherPassphrase, salt, ArchiveKdf.MinIterations));
        Assert.Equal(ArchiveKdf.KeySize, ArchiveKdf.DeriveKey(Passphrase, salt, ArchiveKdf.MinIterations).Length);
    }

    [Fact]
    public void DeriveKey_RejectsAShortSaltOrAWeakIterationCount()
    {
        Assert.Throws<ArgumentException>(() => ArchiveKdf.DeriveKey(Passphrase, new byte[8], ArchiveKdf.MinIterations));
        Assert.Throws<ArgumentOutOfRangeException>(() => ArchiveKdf.DeriveKey(Passphrase, new byte[ArchiveKdf.SaltSize], 1_000));
        Assert.Throws<ArgumentNullException>(() => ArchiveKdf.DeriveKey(null!, new byte[ArchiveKdf.SaltSize], ArchiveKdf.MinIterations));
        Assert.True(ArchiveKdf.MinIterations >= 600_000);
    }

    [Fact]
    public void HeaderCodec_RejectsAMalformedHeader()
    {
        Assert.False(ArchiveHeaderCodec.TryParse(Encoding.UTF8.GetBytes("[1,2,3]"), out _, out string? reason));
        Assert.Equal(ArchiveError.CorruptHeader, reason);

        Assert.False(ArchiveHeaderCodec.TryParse([], out _, out reason));
        Assert.Equal(ArchiveError.CorruptHeader, reason);

        Assert.False(ArchiveHeaderCodec.TryParse(Encoding.UTF8.GetBytes("{\"version\":1}"), out _, out reason));
        Assert.Equal(ArchiveError.CorruptHeader, reason);
    }

    [Fact]
    public void KdfNames_RoundTripAndUnknownNamesAreNotCoerced()
    {
        Assert.Equal("pbkdf2-sha256", ArchiveKdf.NameOf(KdfId.Pbkdf2Sha256));
        Assert.True(ArchiveKdf.TryParse("  PBKDF2-SHA256 ", out KdfId parsed));
        Assert.Equal(KdfId.Pbkdf2Sha256, parsed);
        Assert.False(ArchiveKdf.TryParse("argon2id", out _));
        Assert.False(ArchiveKdf.TryParse(null, out _));
        Assert.Throws<ArgumentOutOfRangeException>(() => ArchiveKdf.NameOf((KdfId)99));
    }

    [Fact]
    public void NullArguments_AreRejected()
    {
        Assert.Throws<ArgumentNullException>(() => EncryptedArchiveWriter.Write(null!, Passphrase));
        Assert.Throws<ArgumentNullException>(() => EncryptedArchiveReader.Read(null!, Passphrase));
        Assert.Throws<ArgumentNullException>(() => EncryptedArchiveReader.TryReadHeader(null!, out _, out _));
    }

    private static SourceSyncEnvelope Envelope(params SourceRecord[] sources)
    {
        return new SourceSyncEnvelope
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            DeviceId = "device-a",
            SnapshotId = "0123456789abcdef0123456789abcdef",
            CreatedAtEpochMs = 1_700_000_000_000,
            Capabilities = new SyncCapabilities { CanSendSecrets = false, SourceTypes = [SyncSourceTypes.Emby] },
            Sources = sources,
            Deletions = [],
        };
    }

    private static SourceRecord Record(
        string syncSourceId,
        string displayName = "Home NAS",
        string url = "http://host:8096",
        long revision = 1,
        int fieldVersion = 0,
        string? accountScope = null,
        IReadOnlyList<SourceEndpoint>? endpoints = null,
        JsonElement? secrets = null)
    {
        return new SourceRecord
        {
            SyncSourceId = syncSourceId,
            SourceType = SyncSourceTypeValue.Parse(SyncSourceTypes.Emby),
            DisplayName = displayName,
            AccountScope = accountScope,
            Endpoints = endpoints ?? [new SourceEndpoint { Url = url, IsPrimary = true, SortOrder = 0 }],
            RecordRevision = revision,
            FieldVersion = fieldVersion,
            Secrets = secrets,
        };
    }

    private static ArchiveHeader NewHeader(
        string kdf = ArchiveKdf.Pbkdf2Sha256Name,
        int version = ArchiveHeader.CurrentVersion,
        int iterations = ArchiveKdf.DefaultIterations)
    {
        return new ArchiveHeader
        {
            Version = version,
            Kdf = kdf,
            Iterations = iterations,
            SaltBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(ArchiveKdf.SaltSize)),
            NonceBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(ArchiveHeader.NonceSize)),
            CreatedAtEpochMs = 1_700_000_000_000,
        };
    }

    /// <summary>
    /// Assembles an archive from a header and an arbitrary payload, independently of the writer, so that files
    /// the writer would refuse to produce (unknown kdf, future version, secret payload) can be tested.
    /// </summary>
    private static byte[] CraftArchive(ArchiveHeader header, byte[] payload, string passphrase)
    {
        byte[] prefix = Prefix(ArchiveHeaderCodec.Serialize(header));

        // A header with an iteration count below the floor is rejected before any key is derived, so the file
        // only needs to be structurally valid — there is no key that could produce a readable ciphertext.
        if (header.Iterations < ArchiveKdf.MinIterations)
        {
            return [.. prefix, .. new byte[payload.Length], .. new byte[ArchiveHeader.TagSize]];
        }

        byte[] salt = Convert.FromBase64String(header.SaltBase64);
        byte[] nonce = Convert.FromBase64String(header.NonceBase64);
        byte[] key = ArchiveKdf.DeriveKey(passphrase, salt, header.Iterations);
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
}

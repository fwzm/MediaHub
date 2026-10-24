using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins credential migration across the session boundary: plaintext only ever exists for the duration of one
/// call, a block sealed on another machine stops the whole import, and nothing that lands in a store can be read
/// as a password. Each test drives the real protector, not a stand-in.
/// </summary>
public sealed class SourceSyncSessionSecretTests : IDisposable
{
    private const string Plaintext = "webdav-password-42-do-not-leak";
    private const string PeerScope = "peer-user";

    private readonly string _directory;

    public SourceSyncSessionSecretTests()
    {
        _directory = Path.Combine(Path.GetTempPath(), "mh-w6-secrets-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_directory))
            {
                Directory.Delete(_directory, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    [Fact]
    public void Export_SecretsWithoutAProtectorAreRefused()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        SyncContractException error = Assert.Throws<SyncContractException>(
            () => session.Export(includeSecrets: true, secrets: PlaintextFor("src-1")));

        Assert.Equal(SecretError.SecretsRequireProtector, error.Reason);
        Assert.DoesNotContain(store.GetAll(), record => record.Secret is not null);
    }

    [Fact]
    public void Export_SecretsWithAPassphraseAreRefused()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        SyncContractException error = Assert.Throws<SyncContractException>(() => session.Export(
            includeSecrets: true,
            passphrase: "correct horse battery staple",
            protector: new WindowsDpapiProtector(),
            secrets: PlaintextFor("src-1")));

        Assert.Equal(SourceSyncSessionReasons.SecretsNotSupported, error.Reason);
    }

    [Fact]
    public void Export_AskingForSecretsWithoutAnyPlaintextIsRefused()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        SyncContractException error = Assert.Throws<SyncContractException>(
            () => session.Export(includeSecrets: true, protector: new WindowsDpapiProtector()));

        Assert.Equal(SecretError.SecretsMissing, error.Reason);
    }

    [Fact]
    public void Export_SupplyingSecretsNobodyAskedForIsRefused()
    {
        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        SyncContractException error = Assert.Throws<SyncContractException>(
            () => session.Export(secrets: PlaintextFor("src-1")));

        Assert.Equal(SecretError.SecretsNotRequested, error.Reason);
    }

    [Fact]
    public void Export_SealsTheCredentialAndKeepsPlaintextOffTheWire()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);
        var protector = new WindowsDpapiProtector();
        byte[] plaintext = Encoding.UTF8.GetBytes(Plaintext);
        string base64Plaintext = Convert.ToBase64String(plaintext);

        ExportSession export = session.Export(
            includeSecrets: true,
            protector: protector,
            secrets: PlaintextFor("src-1"));

        Assert.True(export.Envelope.Capabilities.CanSendSecrets);
        SourceRecord record = Assert.Single(export.Envelope.Sources);
        Assert.NotNull(record.Secrets);
        Assert.DoesNotContain(Plaintext, export.EnvelopeJson, StringComparison.Ordinal);
        Assert.DoesNotContain(base64Plaintext, export.EnvelopeJson, StringComparison.Ordinal);

        SecretEnvelope block = SecretEnvelopeCodec.Deserialize(record.Secrets!.Value.GetRawText());
        Assert.Equal(SecretEnvelope.SchemeDpapiUser, block.Scheme);
        Assert.Equal(Environment.UserName, block.Scope);
        Assert.Equal(plaintext, protector.Unprotect(block));
    }

    [Fact]
    public void Export_WarnsAboutACredentialForAnUnknownSource()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var store = new InMemoryLocalSourceStore();
        store.Save(Record());
        SourceSyncSession session = Session(store);

        ExportSession export = session.Export(
            includeSecrets: true,
            protector: new WindowsDpapiProtector(),
            secrets: PlaintextFor("src-nope"));

        Assert.Contains("secret-skipped:src-nope:no-such-source", export.Warnings);
        Assert.Null(Assert.Single(export.Envelope.Sources).Secrets);
    }

    [Fact]
    public void Import_SealedCredentialsWithoutAProtectorFailClosed()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        SourceSyncEnvelope peer = SealedPeerEnvelope(new WindowsDpapiProtector());
        var store = new InMemoryLocalSourceStore();

        ImportResult result = Session(store, "device-b").ImportPlain(peer);

        Assert.False(result.Succeeded);
        Assert.Equal(SecretError.SecretsRequireProtector, result.FailureReason);
        Assert.Equal(0, store.WriteCount);
        Assert.Empty(store.GetAll());
    }

    [Fact]
    public void Import_ForeignSealedBlobFailsTheWholeRound()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        string foreign = ForeignDpapi.SealWithEntropy(
            Encoding.UTF8.GetBytes(Plaintext),
            Encoding.UTF8.GetBytes("SomeOtherApp.v1"));
        SourceSyncEnvelope peer = PeerEnvelope(new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeDpapiUser,
            Scope = "someone-else",
            PayloadBase64 = foreign,
            ProtectedAtEpochMs = 1,
        });
        var store = new InMemoryLocalSourceStore();

        ImportResult result = Session(store, "device-b").ImportPlain(peer, protector: new WindowsDpapiProtector());

        Assert.False(result.Succeeded);
        Assert.Equal(SecretError.DpapiUnprotectFailed, result.FailureReason);
        Assert.Equal(0, store.WriteCount);
        Assert.Empty(store.GetAll());
    }

    [Fact]
    public void Import_APasswordWearingABase64CoatFailsClosed()
    {
        // Hand-built on purpose: the codec refuses to serialise this block, which is exactly the claim a
        // hostile peer would make, so the envelope is assembled from raw JSON.
        string disguised = Convert.ToBase64String(Encoding.UTF8.GetBytes("password1234"));
        using JsonDocument document = JsonDocument.Parse(
            "{\"scheme\":\"dpapi-user\",\"scope\":\"alice\",\"payloadBase64\":\"" + disguised + "\"}");
        SourceSyncEnvelope peer = PeerEnvelopeRaw(document.RootElement.Clone());
        var store = new InMemoryLocalSourceStore();

        ImportResult result = Session(store, "device-b").ImportPlain(peer, protector: new StubProtector());

        Assert.False(result.Succeeded);
        Assert.Equal(SecretError.PayloadTooShort, result.FailureReason);
        Assert.Equal(0, store.WriteCount);
    }

    [Fact]
    public void Import_ReSealsForThisMachineAndNeverStoresPlaintext()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var protector = new WindowsDpapiProtector();
        SourceSyncEnvelope peer = SealedPeerEnvelope(protector);
        string inboundPayload = SecretEnvelopeCodec
            .Deserialize(Assert.Single(peer.Sources).Secrets!.Value.GetRawText())
            .PayloadBase64;
        var store = new FileLocalSourceStore(_directory);

        ImportResult result = Session(store, "device-b").ImportPlain(peer, protector: protector);

        Assert.True(result.Succeeded, result.FailureReason);
        Assert.Equal(1, result.AppliedCount);
        LocalSourceRecord stored = Assert.Single(store.GetAll());
        Assert.Equal("src-1", stored.SyncSourceId);
        Assert.Equal("alice", stored.AccountScope);
        Assert.Equal("http://nas:8096", stored.Url);
        Assert.NotNull(stored.Secret);
        Assert.NotEqual(inboundPayload, stored.Secret!.PayloadBase64);
        Assert.Equal(Encoding.UTF8.GetBytes(Plaintext), protector.Unprotect(stored.Secret));

        string file = File.ReadAllText(store.FilePath);
        Assert.Contains(SecretEnvelope.SchemeDpapiUser, file, StringComparison.Ordinal);
        Assert.DoesNotContain(Plaintext, file, StringComparison.Ordinal);
        Assert.DoesNotContain(Convert.ToBase64String(Encoding.UTF8.GetBytes(Plaintext)), file, StringComparison.Ordinal);

        LocalSourceRecord reloaded = Assert.Single(new FileLocalSourceStore(_directory).GetAll());
        Assert.Equal(Encoding.UTF8.GetBytes(Plaintext), protector.Unprotect(reloaded.Secret!));
    }

    [Fact]
    public void Import_RepeatingTheSameRoundDoesNotRewriteTheCredential()
    {
        if (!OperatingSystem.IsWindows()) { return; }

        var protector = new WindowsDpapiProtector();
        SourceSyncEnvelope peer = SealedPeerEnvelope(protector);
        var store = new FileLocalSourceStore(_directory);
        SourceSyncSession session = Session(store, "device-b");

        Assert.True(session.ImportPlain(peer, protector: protector).Succeeded);
        string storedPayload = Assert.Single(store.GetAll()).Secret!.PayloadBase64;

        ImportResult second = session.ImportPlain(peer, protector: protector);

        Assert.True(second.Succeeded, second.FailureReason);
        Assert.Equal(0, second.AppliedCount);
        Assert.Equal(storedPayload, Assert.Single(store.GetAll()).Secret!.PayloadBase64);
    }

    [Fact]
    public void Import_WithoutCredentialsNeedsNoProtector()
    {
        var store = new InMemoryLocalSourceStore();
        SourceSyncSession session = Session(store, "device-b");

        ImportResult result = session.ImportPlain(PeerEnvelopeRaw(secrets: null));

        Assert.True(result.Succeeded, result.FailureReason);
        Assert.Equal(1, result.AppliedCount);
        Assert.Null(Assert.Single(store.GetAll()).Secret);
    }

    private static SourceSyncSession Session(ILocalSourceStore store, string deviceId = "device-a")
        => new(store, deviceId, () => DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L));

    private static IReadOnlyDictionary<string, byte[]> PlaintextFor(string syncSourceId)
        => new Dictionary<string, byte[]>(StringComparer.Ordinal)
        {
            [syncSourceId] = Encoding.UTF8.GetBytes(Plaintext),
        };

    private static LocalSourceRecord Record() => new()
    {
        ServerId = "a1",
        SyncSourceId = "src-1",
        Type = ServerType.Emby,
        Url = "http://nas:8096",
        DisplayName = "NAS",
        AccountScope = "alice",
        RecordRevision = 1,
    };

    /// <summary>A peer envelope whose sealed block is serialised by the codec (so it must be valid).</summary>
    private static SourceSyncEnvelope PeerEnvelope(SecretEnvelope? secrets)
        => PeerEnvelopeRaw(secrets is null ? null : SecretEnvelopeCodec.SerializeToElement(secrets));

    /// <summary>A peer envelope carrying whatever JSON block the caller wants, valid or not.</summary>
    private static SourceSyncEnvelope PeerEnvelopeRaw(JsonElement? secrets) => new()
    {
        DeviceId = "device-peer",
        SnapshotId = Guid.NewGuid().ToString("N"),
        CreatedAtEpochMs = 123L,
        Capabilities = new SyncCapabilities
        {
            CanSendSecrets = secrets is not null,
            SourceTypes = [SyncSourceTypes.Emby],
        },
        Sources =
        [
            new SourceRecord
            {
                SyncSourceId = "src-1",
                SourceType = SyncSourceTypeValue.Parse("emby"),
                DisplayName = "NAS",
                AccountScope = "alice",
                Endpoints = [new SourceEndpoint { Url = "http://nas:8096", IsPrimary = true, SortOrder = 0 }],
                RecordRevision = 1,
                Secrets = secrets,
            },
        ],
    };

    private static SourceSyncEnvelope SealedPeerEnvelope(ISecretProtector protector)
    {
        SecretEnvelope block = protector.Protect(Encoding.UTF8.GetBytes(Plaintext));
        return PeerEnvelope(block with { Scope = PeerScope });
    }

    /// <summary>A protector stub that never unseals anything, so only block validation can reject the input.</summary>
    private sealed class StubProtector : ISecretProtector
    {
        public SecretEnvelope Protect(ReadOnlySpan<byte> plaintext) => throw new NotSupportedException();

        public byte[] Unprotect(SecretEnvelope envelope) => throw new NotSupportedException();
    }

    /// <summary>A second, deliberately foreign DPAPI caller: a blob it seals is not ours to open.</summary>
    private static class ForeignDpapi
    {
        public static string SealWithEntropy(byte[] plaintext, byte[] entropy)
        {
            GCHandle inputHandle = GCHandle.Alloc(plaintext, GCHandleType.Pinned);
            GCHandle entropyHandle = GCHandle.Alloc(entropy, GCHandleType.Pinned);
            try
            {
                var input = new DATA_BLOB { cbData = plaintext.Length, pbData = inputHandle.AddrOfPinnedObject() };
                var entropyBlob = new DATA_BLOB { cbData = entropy.Length, pbData = entropyHandle.AddrOfPinnedObject() };
                if (!CryptProtectData(ref input, IntPtr.Zero, ref entropyBlob, IntPtr.Zero, IntPtr.Zero, 0x1, out DATA_BLOB output))
                {
                    throw new InvalidOperationException("foreign CryptProtectData failed: " + Marshal.GetLastPInvokeError());
                }

                try
                {
                    byte[] blob = new byte[output.cbData];
                    Marshal.Copy(output.pbData, blob, 0, output.cbData);
                    return Convert.ToBase64String(blob);
                }
                finally
                {
                    LocalFree(output.pbData);
                }
            }
            finally
            {
                inputHandle.Free();
                entropyHandle.Free();
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct DATA_BLOB
        {
            public int cbData;
            public IntPtr pbData;
        }

        [DllImport("crypt32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CryptProtectData(
            ref DATA_BLOB pDataIn,
            IntPtr szDataDescr,
            ref DATA_BLOB pOptionalEntropy,
            IntPtr pvReserved,
            IntPtr pPromptStruct,
            int dwFlags,
            out DATA_BLOB pDataOut);

        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr hMem);
    }
}

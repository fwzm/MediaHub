using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the W6-15 command surface of <c>MediaHub.SyncCli</c>: the <c>baseline</c> group that inspects and
/// forgets what was learned from a peer, and the <c>pair</c> group that describes this device and checks a
/// pairing link offline.
/// </summary>
/// <remarks>
/// Everything runs in-process with injected writers and a fixed clock, so a "dry run wrote nothing" claim is
/// checked by comparing the bytes of the file instead of trusting a log line. No test binds a socket: the pair
/// commands only build and decode text, which is exactly the property the CLI promises.
/// </remarks>
public sealed class SyncCliBaselinePairTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const long NowMs = 1_760_000_000_000L;
    private const string PeerId = "android-pixel-8";
    private const string OtherPeerId = "android-tablet";

    private readonly string _root;

    public SyncCliBaselinePairTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mhcli-bp-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
            // A leftover temp directory is not worth failing a green test over.
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private string DeviceRoot(string name)
    {
        string path = Path.Combine(_root, name);
        Directory.CreateDirectory(path);
        return path;
    }

    private static (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static JsonElement ParseSingleDocument(string stdout)
    {
        string trimmed = stdout.Trim();
        Assert.Single(trimmed.Split((char)10));
        using JsonDocument document = JsonDocument.Parse(trimmed);
        return document.RootElement.Clone();
    }

    private static string ErrorCode(JsonElement document) =>
        document.GetProperty("error").GetProperty("code").GetString() ?? "";

    private static string BaselinePath(string root) =>
        Path.Combine(root, FileSourceSyncBaselineStore.FileName);

    private static string PairedPath(string root) =>
        Path.Combine(root, FilePairedDeviceStore.FileName);

    private static string HashFor(string text) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

    private static void SeedBaseline(string root, string peerId, params string[] syncSourceIds)
    {
        FileSourceSyncBaselineStore store = new(root);
        Assert.Null(store.LoadError);
        store.Save(
            peerId,
            new SourceSyncBaseline
            {
                DeviceId = peerId,
                SnapshotId = "snap-" + peerId,
                Entries = syncSourceIds
                    .Select((id, index) => new SourceSyncBaselineEntry
                    {
                        SyncSourceId = id,
                        RecordRevision = index + 1,
                        FieldVersion = 1,
                        ContentHash = HashFor(id),
                    })
                    .ToArray(),
            });
    }

    private static string FingerprintOf(string deviceId) =>
        PairingSelfIdentity.Create(deviceId).Fingerprint.Hex;

    private static void SeedPairedDevice(string root, string deviceId, string? displayName = null, int sourceCount = 0)
    {
        FilePairedDeviceStore store = new(root);
        Assert.Null(store.LoadError);
        store.Save(PairedDevice.Create(deviceId, FingerprintOf(deviceId), displayName, NowMs, sourceCount));
    }

    private static byte[]? BytesOf(string path) => File.Exists(path) ? File.ReadAllBytes(path) : null;

    private static void AssertUnchanged(byte[]? before, string path)
    {
        byte[]? after = BytesOf(path);
        if (before is null)
        {
            Assert.Null(after);
            return;
        }

        Assert.NotNull(after);
        Assert.Equal(before, after);
    }

    private static string ToBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string text)
    {
        string standard = text.Replace('-', '+').Replace('_', '/');
        int padding = (4 - (standard.Length % 4)) % 4;
        return Convert.FromBase64String(standard.PadRight(standard.Length + padding, '='));
    }

    private static string LinkAround(string code) => PairingLink.Prefix + "code=" + code;

    private static string CodeOf(string link) => PairingLink.ExtractCode(link) ?? "";

    private static PairingPayload PayloadFor(string deviceId, string url, long nowMs) =>
        PairingPayload.Create(deviceId, url, PairingChallenge.Create(), PairingSelfIdentity.Create(deviceId).Fingerprint, nowMs);

    // ---- baseline show ----------------------------------------------------------------------

    [Fact]
    public void BaselineShow_MissingDocumentSucceedsAndSaysNothingWasImported()
    {
        string root = DeviceRoot("show-missing");

        (int exitCode, string stdout, string stderr) = Run("baseline", "show", "--root", root, "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("exists").GetBoolean());
        Assert.Equal(0, document.GetProperty("peerCount").GetInt32());
        Assert.Equal(0, document.GetProperty("entryCount").GetInt32());
        Assert.Equal(SourceSyncBaseline.CurrentVersion, document.GetProperty("documentVersion").GetInt32());
        Assert.Equal(BaselinePath(root), document.GetProperty("path").GetString());
        Assert.False(File.Exists(BaselinePath(root)));
    }

    [Fact]
    public void BaselineShow_ListsEveryPeerAndEntryWithATwelveCharacterHashPrefix()
    {
        string root = DeviceRoot("show-two-peers");
        SeedBaseline(root, PeerId, "src-film", "src-music");
        SeedBaseline(root, OtherPeerId, "src-photo");

        (int exitCode, string stdout, string stderr) = Run("baseline", "show", "--root", root, "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("exists").GetBoolean());
        Assert.Equal(2, document.GetProperty("peerCount").GetInt32());
        Assert.Equal(3, document.GetProperty("entryCount").GetInt32());

        JsonElement entries = document.GetProperty("peers")[0].GetProperty("entries");
        Assert.Equal(2, entries.GetArrayLength());
        string fullHash = entries[0].GetProperty("contentHash").GetString() ?? "";
        string prefix = entries[0].GetProperty("contentHashPrefix").GetString() ?? "";
        Assert.Equal(64, fullHash.Length);
        Assert.Equal(12, prefix.Length);
        Assert.StartsWith(prefix, fullHash, StringComparison.Ordinal);
        Assert.Equal("src-film", entries[0].GetProperty("syncSourceId").GetString());
    }

    [Fact]
    public void BaselineShow_TextModePrintsThePathAndTheCounts()
    {
        string root = DeviceRoot("show-text");
        SeedBaseline(root, PeerId, "src-film");

        (int exitCode, string stdout, _) = Run("baseline", "show", "--root", root);

        Assert.Equal(0, exitCode);
        Assert.Contains("baseline: " + BaselinePath(root), stdout, StringComparison.Ordinal);
        Assert.Contains("exists: true", stdout, StringComparison.Ordinal);
        Assert.Contains("peers: 1", stdout, StringComparison.Ordinal);
        Assert.Contains("entries: 1", stdout, StringComparison.Ordinal);
        Assert.Contains("peer " + PeerId + " (1 entries)", stdout, StringComparison.Ordinal);
        Assert.DoesNotContain("nothing has been imported", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public void BaselineShow_CorruptDocumentIsAnInputProblemThatNamesTheQuarantinePath()
    {
        string root = DeviceRoot("show-corrupt");
        Directory.CreateDirectory(root);
        File.WriteAllText(BaselinePath(root), "{ this is not a baseline");

        (int exitCode, string stdout, string stderr) = Run("baseline", "show", "--root", root, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("ok").GetBoolean());
        Assert.NotEqual("", ErrorCode(document));
        string message = document.GetProperty("error").GetProperty("message").GetString() ?? "";
        Assert.Contains("the damaged document was kept at ", message, StringComparison.Ordinal);
        Assert.Contains("error " + ErrorCode(document) + ": ", stderr, StringComparison.Ordinal);
        Assert.Single(Directory.GetFiles(root, "sync-baseline.corrupt-*"));
    }

    [Fact]
    public void BaselineShow_RefusesTheApplyFlag()
    {
        string root = DeviceRoot("show-apply");

        (int exitCode, _, _) = Run("baseline", "show", "--root", root, "--apply");

        Assert.Equal(CliExitCode.UsageError, exitCode);
    }

    // ---- baseline forget --------------------------------------------------------------------

    [Fact]
    public void BaselineForget_DryRunReportsTheRemovalButWritesNothing()
    {
        string root = DeviceRoot("forget-dry");
        SeedBaseline(root, PeerId, "src-film", "src-music");
        byte[]? before = BytesOf(BaselinePath(root));
        Assert.NotNull(before);

        (int exitCode, string stdout, string stderr) = Run("baseline", "forget", "--root", root, "--device", PeerId, "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("dryRun").GetBoolean());
        Assert.False(document.GetProperty("applied").GetBoolean());
        Assert.Equal(2, document.GetProperty("removedCount").GetInt32());
        Assert.True(document.GetProperty("peerRemoved").GetBoolean());
        AssertUnchanged(before, BaselinePath(root));
    }

    [Fact]
    public void BaselineForget_ApplyRemovesTheWholePeer()
    {
        string root = DeviceRoot("forget-peer");
        SeedBaseline(root, PeerId, "src-film", "src-music");
        SeedBaseline(root, OtherPeerId, "src-photo");

        (int exitCode, string stdout, string stderr) = Run("baseline", "forget", "--root", root, "--device", PeerId, "--apply", "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("dryRun").GetBoolean());
        Assert.True(document.GetProperty("applied").GetBoolean());
        Assert.Equal(2, document.GetProperty("removedCount").GetInt32());
        Assert.Equal(0, document.GetProperty("remainingEntries").GetInt32());
        Assert.True(document.GetProperty("peerRemoved").GetBoolean());

        (int showExit, string showOut, _) = Run("baseline", "show", "--root", root, "--json");
        Assert.Equal(0, showExit);
        JsonElement shown = ParseSingleDocument(showOut);
        Assert.Equal(1, shown.GetProperty("peerCount").GetInt32());
        Assert.Equal(OtherPeerId, shown.GetProperty("peers")[0].GetProperty("deviceId").GetString());
    }

    [Fact]
    public void BaselineForget_ApplyWithASourceRemovesOnlyThatEntry()
    {
        string root = DeviceRoot("forget-source");
        SeedBaseline(root, PeerId, "src-film", "src-music");

        (int exitCode, string stdout, _) = Run(
            "baseline", "forget", "--root", root, "--device", PeerId, "--source", "src-music", "--apply", "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("applied").GetBoolean());
        Assert.Equal(1, document.GetProperty("removedCount").GetInt32());
        Assert.Equal(1, document.GetProperty("remainingEntries").GetInt32());
        Assert.False(document.GetProperty("peerRemoved").GetBoolean());
        Assert.Equal("src-music", document.GetProperty("sourceId").GetString());
        Assert.Equal("src-music", document.GetProperty("removed")[0].GetProperty("syncSourceId").GetString());

        (int showExit, string showOut, _) = Run("baseline", "show", "--root", root, "--json");
        Assert.Equal(0, showExit);
        JsonElement shown = ParseSingleDocument(showOut);
        Assert.Equal(1, shown.GetProperty("peers")[0].GetProperty("entries").GetArrayLength());
        Assert.Equal("src-film", shown.GetProperty("peers")[0].GetProperty("entries")[0].GetProperty("syncSourceId").GetString());
    }

    [Fact]
    public void BaselineForget_AnUnknownDeviceIsAnInputProblemAndWritesNothing()
    {
        string root = DeviceRoot("forget-unknown-device");
        SeedBaseline(root, PeerId, "src-film");
        byte[]? before = BytesOf(BaselinePath(root));

        (int exitCode, string stdout, _) = Run("baseline", "forget", "--root", root, "--device", "android-nobody", "--apply", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal("baseline-device-unknown", ErrorCode(ParseSingleDocument(stdout)));
        AssertUnchanged(before, BaselinePath(root));
    }

    [Fact]
    public void BaselineForget_AnUnknownSourceIsAnInputProblem()
    {
        string root = DeviceRoot("forget-unknown-source");
        SeedBaseline(root, PeerId, "src-film");
        byte[]? before = BytesOf(BaselinePath(root));

        (int exitCode, string stdout, _) = Run(
            "baseline", "forget", "--root", root, "--device", PeerId, "--source", "src-nope", "--apply", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal("baseline-source-unknown", ErrorCode(ParseSingleDocument(stdout)));
        AssertUnchanged(before, BaselinePath(root));
    }

    [Fact]
    public void BaselineForget_MatchesTheDeviceIdCaseInsensitively()
    {
        string root = DeviceRoot("forget-case");
        SeedBaseline(root, "android-PIXEL", "src-film");

        (int exitCode, string stdout, _) = Run("baseline", "forget", "--root", root, "--device", "android-pixel", "--apply", "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("applied").GetBoolean());
        Assert.Equal("android-PIXEL", document.GetProperty("deviceId").GetString());
    }

    [Fact]
    public void BaselineForget_TextModeAnnouncesThePreviewOnStandardError()
    {
        string root = DeviceRoot("forget-text");
        SeedBaseline(root, PeerId, "src-film", "src-music");

        (int exitCode, string stdout, string stderr) = Run("baseline", "forget", "--root", root, "--device", PeerId);

        Assert.Equal(0, exitCode);
        Assert.Contains("preview: nothing was written; pass --apply to forget", stderr, StringComparison.Ordinal);
        Assert.Contains("would forget 2 entries for " + PeerId, stdout, StringComparison.Ordinal);
        Assert.Contains("remaining: 0 entries", stdout, StringComparison.Ordinal);
    }

    // ---- pair link / verify -----------------------------------------------------------------

    [Fact]
    public void PairLink_RoundTripsThroughVerify()
    {
        (int linkExit, string linkOut, string linkErr) = Run("pair", "link", "--device", PeerId, "--json");

        Assert.Equal(0, linkExit);
        Assert.Equal("", linkErr);
        JsonElement linkDocument = ParseSingleDocument(linkOut);
        string link = linkDocument.GetProperty("link").GetString() ?? "";
        Assert.StartsWith(PairingLink.Prefix, link, StringComparison.Ordinal);

        (int verifyExit, string verifyOut, string verifyErr) = Run("pair", "verify", "--link", link, "--json");

        Assert.Equal(0, verifyExit);
        Assert.Equal("", verifyErr);
        JsonElement verifyDocument = ParseSingleDocument(verifyOut);
        Assert.Equal(PeerId, verifyDocument.GetProperty("deviceId").GetString());
        Assert.Equal(linkDocument.GetProperty("fingerprintPrefix").GetString(), verifyDocument.GetProperty("fingerprintPrefix").GetString());
        Assert.True(verifyDocument.GetProperty("remainingSeconds").GetInt32() > 0);
    }

    [Fact]
    public void PairLink_JsonSaysItDoesNotListen()
    {
        (int exitCode, string stdout, _) = Run("pair", "link", "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("listens").GetBoolean());
        Assert.Equal(PairingPayload.LifetimeSeconds, document.GetProperty("lifetimeSeconds").GetInt32());
        Assert.Equal(8, (document.GetProperty("fingerprintPrefix").GetString() ?? "").Length);
    }

    [Fact]
    public void PairLink_NeverPrintsTheChallengeOrTheWholeFingerprint()
    {
        (int exitCode, string stdout, _) = Run("pair", "link", "--device", PeerId, "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        string link = document.GetProperty("link").GetString() ?? "";
        PairingLinkResult parsed = PairingLink.Parse(link, NowMs);
        Assert.True(parsed.Succeeded);
        string challenge = parsed.Payload!.Challenge;
        Assert.NotEqual("", challenge);
        Assert.DoesNotContain(challenge, stdout, StringComparison.Ordinal);
        Assert.DoesNotContain(parsed.Payload.PublicKeyFingerprint, stdout, StringComparison.Ordinal);
    }

    [Fact]
    public void PairLink_TextModeExplainsThatTheLinkIsCheckedOffline()
    {
        (int exitCode, string stdout, _) = Run("pair", "link", "--device", PeerId);

        Assert.Equal(0, exitCode);
        Assert.Contains("device: " + PeerId, stdout, StringComparison.Ordinal);
        Assert.Contains("link: " + PairingLink.Prefix, stdout, StringComparison.Ordinal);
        Assert.Contains("this CLI does not listen", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public void PairLink_NamesTheSameDeviceAndFingerprintOnEveryRun()
    {
        (_, string firstOut, _) = Run("pair", "link", "--device", PeerId, "--json");
        (_, string secondOut, _) = Run("pair", "link", "--device", PeerId, "--json");

        JsonElement first = ParseSingleDocument(firstOut);
        JsonElement second = ParseSingleDocument(secondOut);
        Assert.Equal(first.GetProperty("deviceId").GetString(), second.GetProperty("deviceId").GetString());
        Assert.Equal(first.GetProperty("fingerprintPrefix").GetString(), second.GetProperty("fingerprintPrefix").GetString());
        Assert.NotEqual(first.GetProperty("link").GetString(), second.GetProperty("link").GetString());
    }

    [Fact]
    public void PairVerify_RefusesTextThatIsNotAPairingLink()
    {
        (int exitCode, string stdout, _) = Run("pair", "verify", "--link", "https://example.invalid/pair?code=abc", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal(PairingLinkReasons.NotALink, ErrorCode(ParseSingleDocument(stdout)));
    }

    [Fact]
    public void PairVerify_RefusesTextThatIsNotBase64Url()
    {
        (int exitCode, string stdout, _) = Run("pair", "verify", "--link", LinkAround("not base64url!"), "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal(PairingCodeReasons.NotBase64Url, ErrorCode(ParseSingleDocument(stdout)));
    }

    [Fact]
    public void PairVerify_RefusesAPayloadFromANewerSchema()
    {
        string code = CodeOf(PairingLink.Create(PayloadFor("android-future", "http://127.0.0.1:9/", NowMs)));
        string json = Encoding.UTF8.GetString(FromBase64Url(code));
        string mutated = ToBase64Url(Encoding.UTF8.GetBytes(json.Replace("\"schemaVersion\":1", "\"schemaVersion\":2", StringComparison.Ordinal)));

        (int exitCode, string stdout, _) = Run("pair", "verify", "--link", LinkAround(mutated), "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal(PairingCodeReasons.UnsupportedVersion, ErrorCode(ParseSingleDocument(stdout)));
    }

    [Fact]
    public void PairVerify_RefusesAnExpiredInvitation()
    {
        long expiredAt = NowMs - ((PairingPayload.LifetimeSeconds + 5L) * 1000L);
        string link = PairingLink.Create(PayloadFor("android-late", "http://127.0.0.1:9/", expiredAt));

        (int exitCode, string stdout, _) = Run("pair", "verify", "--link", link, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal(PairingCodeReasons.Expired, ErrorCode(ParseSingleDocument(stdout)));
    }

    [Fact]
    public void PairVerify_RefusesAnEmptyLinkValue()
    {
        (int exitCode, string stdout, _) = Run("pair", "verify", "--link", " ", "--json");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.StartsWith("empty-value", ErrorCode(ParseSingleDocument(stdout)), StringComparison.Ordinal);
    }

    // ---- pair list / forget -----------------------------------------------------------------

    [Fact]
    public void PairList_AnEmptyRootHasNoPairedDevices()
    {
        string root = DeviceRoot("pair-list-empty");

        (int exitCode, string stdout, string stderr) = Run("pair", "list", "--root", root, "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(0, document.GetProperty("count").GetInt32());
        Assert.Empty(document.GetProperty("devices").EnumerateArray());

        (int textExit, string textOut, _) = Run("pair", "list", "--root", root);
        Assert.Equal(0, textExit);
        Assert.Contains("no devices are paired on this machine", textOut, StringComparison.Ordinal);
    }

    [Fact]
    public void PairList_ShowsOnlyTheTailOfTheFingerprint()
    {
        string root = DeviceRoot("pair-list-one");
        SeedPairedDevice(root, PeerId, "Pixel 8", sourceCount: 2);
        string fullFingerprint = FingerprintOf(PeerId);

        (int exitCode, string stdout, _) = Run("pair", "list", "--root", root, "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(1, document.GetProperty("count").GetInt32());
        JsonElement device = document.GetProperty("devices")[0];
        Assert.Equal(PeerId, device.GetProperty("deviceId").GetString());
        Assert.Equal("Pixel 8", device.GetProperty("displayName").GetString());
        Assert.Equal(2, device.GetProperty("sourceCount").GetInt32());
        Assert.Equal(fullFingerprint[^8..], device.GetProperty("fingerprintSuffix").GetString());
        Assert.DoesNotContain(fullFingerprint, stdout, StringComparison.Ordinal);

        (int textExit, string textOut, _) = Run("pair", "list", "--root", root);
        Assert.Equal(0, textExit);
        Assert.Contains("paired devices: 1", textOut, StringComparison.Ordinal);
        Assert.Contains("name=Pixel 8", textOut, StringComparison.Ordinal);
        Assert.DoesNotContain(fullFingerprint, textOut, StringComparison.Ordinal);
    }

    [Fact]
    public void PairList_CorruptDocumentIsAnInputProblemThatNamesTheQuarantinePath()
    {
        string root = DeviceRoot("pair-list-corrupt");
        File.WriteAllText(PairedPath(root), "{ not a paired-device document");

        (int exitCode, string stdout, string stderr) = Run("pair", "list", "--root", root, "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.NotEqual("", ErrorCode(document));
        string message = document.GetProperty("error").GetProperty("message").GetString() ?? "";
        Assert.Contains("the damaged document was kept at ", message, StringComparison.Ordinal);
        Assert.Contains("error ", stderr, StringComparison.Ordinal);
        Assert.Single(Directory.GetFiles(root, "paired-devices.corrupt-*"));
    }

    [Fact]
    public void PairForget_DryRunKeepsTheEntryAndSaysWhatWouldGo()
    {
        string root = DeviceRoot("pair-forget-dry");
        SeedPairedDevice(root, PeerId, "Pixel 8");
        SeedPairedDevice(root, OtherPeerId, "Tablet");
        byte[]? before = BytesOf(PairedPath(root));

        (int exitCode, string stdout, string stderr) = Run("pair", "forget", "--root", root, "--device", PeerId, "--json");

        Assert.Equal(0, exitCode);
        Assert.Equal("", stderr);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("dryRun").GetBoolean());
        Assert.False(document.GetProperty("applied").GetBoolean());
        Assert.Equal(PeerId, document.GetProperty("deviceId").GetString());
        Assert.Equal(1, document.GetProperty("remainingCount").GetInt32());
        AssertUnchanged(before, PairedPath(root));
    }

    [Fact]
    public void PairForget_ApplyRemovesOnlyThatDevice()
    {
        string root = DeviceRoot("pair-forget-apply");
        SeedPairedDevice(root, PeerId, "Pixel 8");
        SeedPairedDevice(root, OtherPeerId, "Tablet");

        (int exitCode, string stdout, _) = Run("pair", "forget", "--root", root, "--device", PeerId, "--apply", "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("applied").GetBoolean());
        Assert.Equal(1, document.GetProperty("remainingCount").GetInt32());

        (int listExit, string listOut, _) = Run("pair", "list", "--root", root, "--json");
        Assert.Equal(0, listExit);
        JsonElement listed = ParseSingleDocument(listOut);
        Assert.Equal(1, listed.GetProperty("count").GetInt32());
        Assert.Equal(OtherPeerId, listed.GetProperty("devices")[0].GetProperty("deviceId").GetString());
    }

    [Fact]
    public void PairForget_TextModeAnnouncesThePreviewAndWhatIsKeptUntouched()
    {
        string root = DeviceRoot("pair-forget-text");
        SeedPairedDevice(root, PeerId, "Pixel 8");

        (int exitCode, string stdout, string stderr) = Run("pair", "forget", "--root", root, "--device", PeerId);

        Assert.Equal(0, exitCode);
        Assert.Contains("preview: nothing was written; pass --apply to forget", stderr, StringComparison.Ordinal);
        Assert.Contains("would forget " + PeerId, stdout, StringComparison.Ordinal);
        Assert.Contains("this removes the local pin only", stdout, StringComparison.Ordinal);
        Assert.Contains("remaining: 0 paired devices", stdout, StringComparison.Ordinal);
    }

    [Fact]
    public void PairForget_AnUnknownDeviceIsAnInputProblemAndWritesNothing()
    {
        string root = DeviceRoot("pair-forget-unknown");
        SeedPairedDevice(root, PeerId, "Pixel 8");
        byte[]? before = BytesOf(PairedPath(root));

        (int exitCode, string stdout, _) = Run("pair", "forget", "--root", root, "--device", "android-nobody", "--apply", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Equal("device-unknown", ErrorCode(ParseSingleDocument(stdout)));
        AssertUnchanged(before, PairedPath(root));
    }

    [Fact]
    public void PairForget_MatchesTheDeviceIdCaseInsensitively()
    {
        string root = DeviceRoot("pair-forget-case");
        SeedPairedDevice(root, "android-PIXEL", "Pixel 8");

        (int exitCode, string stdout, _) = Run("pair", "forget", "--root", root, "--device", "android-pixel", "--apply", "--json");

        Assert.Equal(0, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("applied").GetBoolean());
        Assert.Equal("android-PIXEL", document.GetProperty("deviceId").GetString());
    }

    // ---- output discipline ------------------------------------------------------------------

    [Fact]
    public void JsonMode_WritesExactlyOneDocumentOnStandardOutputEvenWhenItFails()
    {
        string root = DeviceRoot("json-failure");

        (int exitCode, string stdout, string stderr) = Run("pair", "forget", "--root", root, "--device", "android-nobody", "--apply", "--json");

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("ok").GetBoolean());
        Assert.Equal("device-unknown", ErrorCode(document));
        Assert.Contains("error device-unknown: ", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("{", stderr, StringComparison.Ordinal);
    }

    [Fact]
    public void PairCommands_DoNotNeedARootAndBaselineCommandsDo()
    {
        (int linkExit, _, _) = Run("pair", "link", "--json");
        Assert.Equal(0, linkExit);

        (int showExit, string showOut, _) = Run("baseline", "show", "--json");
        Assert.Equal(CliExitCode.UsageError, showExit);
        Assert.Equal("missing-required-option:--root", ErrorCode(ParseSingleDocument(showOut)));
    }
}

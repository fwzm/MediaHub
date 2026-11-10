using System.Text;
using System.Text.Json;
using MediaHub.Domain;
using MediaHub.Sync;
using MediaHub.SyncCli;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the command-line contract of <c>MediaHub.SyncCli</c>: what the parser accepts, which exit
/// code every failure maps to, and the two behaviours the W6 acceptance script depends on
/// (a dry run never writes, machine-readable output never carries a credential in clear text).
/// </summary>
/// <remarks>
/// The runner is exercised in-process with injected writers and a fixed clock, so the assertions
/// describe the real process behaviour without spawning one. Every device root lives in a temp
/// directory owned by the test.
/// </remarks>
public sealed class SyncCliTests : IDisposable
{
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);
    private const string Passphrase = "correct horse battery staple";

    private readonly string _root;

    public SyncCliTests()
    {
        _root = Path.Combine(Path.GetTempPath(), "mhcli-tests-" + Guid.NewGuid().ToString("N"));
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

    private static IReadOnlyList<LocalSourceRecord> ReadStore(string root)
    {
        return new FileLocalSourceStore(root).GetAll();
    }

    private static void WriteStore(string root, params LocalSourceRecord[] records)
    {
        FileLocalSourceStore store = new(root);
        Assert.Null(store.LoadError);
        foreach (LocalSourceRecord record in records)
        {
            store.Save(record);
        }
    }

    private static LocalSourceRecord EmbyRecord() => new()
    {
        ServerId = "srv-emby",
        Type = ServerType.Emby,
        Url = "http://nas-a:8096/emby/",
        AccountScope = "alice",
        DisplayName = "客厅 Emby",
        RecordRevision = 3,
    };

    private static LocalSourceRecord UnknownRecord() => new()
    {
        ServerId = "srv-quantum",
        Type = ServerType.Unknown,
        SourceTypeRaw = "quantum-nas",
        Url = "https://quantum.example:9443/v1",
        DisplayName = "Quantum NAS",
        RecordRevision = 2,
    };

    private static string EnvelopePath(string root) =>
        Path.Combine(root, SyncCliRunner.DefaultEnvelopeFileName);

    private static string ArchivePath(string root) =>
        Path.Combine(root, SyncCliRunner.DefaultArchiveFileName);

    private static string SealedPayloadInEnvelope(string envelopeJson, string syncSourceId)
    {
        using JsonDocument document = JsonDocument.Parse(envelopeJson);
        foreach (JsonElement source in document.RootElement.GetProperty("sources").EnumerateArray())
        {
            if (string.Equals(source.GetProperty("syncSourceId").GetString(), syncSourceId, StringComparison.OrdinalIgnoreCase))
            {
                return source.GetProperty("secrets").GetProperty("payloadBase64").GetString() ?? "";
            }
        }

        throw new InvalidOperationException("no source " + syncSourceId + " in the envelope");
    }

    private static string SealedPayloadInStore(string storeJson, string syncSourceId)
    {
        using JsonDocument document = JsonDocument.Parse(storeJson);
        foreach (JsonElement record in document.RootElement.EnumerateArray())
        {
            if (string.Equals(record.GetProperty("syncSourceId").GetString(), syncSourceId, StringComparison.OrdinalIgnoreCase))
            {
                return record.GetProperty("secret").GetProperty("payloadBase64").GetString() ?? "";
            }
        }

        throw new InvalidOperationException("no record " + syncSourceId + " in the store");
    }

    // ---- parser -------------------------------------------------------------------------

    /// <summary>A bare <c>export</c> run is a what-if: nothing is applied and no JSON is forced.</summary>
    [Fact]
    public void Parse_ExportDefaultsToAPlainWhatIfRun()
    {
        CliParseResult result = CliParser.Parse(["export", "--root", "D:/tmp/root"]);

        Assert.True(result.Succeeded);
        CliInvocation invocation = result.Invocation!;
        Assert.Equal(SyncCliCommand.Export, invocation.Command);
        Assert.Equal("D:/tmp/root", invocation.Root);
        Assert.False(invocation.Apply);
        Assert.False(invocation.Json);
        Assert.Null(invocation.Passphrase);
        Assert.Null(invocation.SecretsJson);
        Assert.Null(invocation.OutPath);
    }

    /// <summary>Both "option value" and "option=value" are accepted; the result must be identical.</summary>
    [Fact]
    public void Parse_AcceptsBothSpacedAndEqualsForms()
    {
        CliParseResult spaced = CliParser.Parse(["plan", "--root", "D:/tmp/r", "--in", "D:/tmp/i", "--json"]);
        CliParseResult equals = CliParser.Parse(["plan", "--root=D:/tmp/r", "--in=D:/tmp/i", "--json"]);

        Assert.True(spaced.Succeeded);
        Assert.True(equals.Succeeded);
        Assert.Equal(spaced.Invocation!.Command, equals.Invocation!.Command);
        Assert.Equal(spaced.Invocation.Root, equals.Invocation.Root);
        Assert.Equal(spaced.Invocation.InPath, equals.Invocation.InPath);
        Assert.True(equals.Invocation.Json);
    }

    /// <summary>Every malformed command line fails with a stable code, never with a stack trace.</summary>
    [Theory]
    [InlineData(new string[] { }, "missing-command")]
    [InlineData(new[] { "frobnicate" }, "unknown-command:frobnicate")]
    [InlineData(new[] { "export" }, "missing-required-option:--root")]
    [InlineData(new[] { "export", "--root", "D:/tmp/r", "--bogus" }, "unknown-option:--bogus")]
    [InlineData(new[] { "export", "--root", "D:/tmp/r", "--json=true" }, "flag-takes-no-value:--json")]
    [InlineData(new[] { "export", "--root", "D:/tmp/r", "--out" }, "missing-value:--out")]
    [InlineData(new[] { "export", "--root", "a", "--root", "b" }, "duplicate-option:--root")]
    [InlineData(new[] { "plan", "--root", "D:/tmp/r", "--in", "x", "--passphrase", "p" }, "option-not-allowed-for-command:--passphrase:plan")]
    [InlineData(new[] { "secret-check", "--root", "D:/tmp/r", "--id", "a" }, "missing-required-option:--expect")]
    public void Parse_RejectsMalformedCommandLinesWithStableCodes(string[] args, string expectedCode)
    {
        CliParseResult result = CliParser.Parse(args);

        Assert.False(result.Succeeded);
        Assert.Equal(expectedCode, result.Error);
    }

    /// <summary>Help and version are successful outcomes, not usage errors.</summary>
    [Fact]
    public void Run_HelpAndVersionExitSuccessfully()
    {
        (int helpExit, string helpOut, _) = Run("--help");
        (int versionExit, string versionOut, _) = Run("--version");

        Assert.Equal(CliExitCode.Success, helpExit);
        Assert.Contains("export", helpOut, StringComparison.Ordinal);
        Assert.True(CliParser.Parse(["--help"]).IsHelp);
        Assert.Equal(CliExitCode.Success, versionExit);
        Assert.Contains(SyncCliProgram.Version, versionOut, StringComparison.Ordinal);
        Assert.True(CliParser.Parse(["--version"]).IsVersion);
    }

    // ---- export -------------------------------------------------------------------------

    /// <summary>In JSON mode stdout carries exactly one document and nothing else.</summary>
    [Fact]
    public void Run_JsonModeLeavesExactlyOneDocumentOnStdout()
    {
        string root = DeviceRoot("a");
        WriteStore(root, EmbyRecord());

        (int exitCode, string stdout, _) = Run("export", "--root", root, "--device", "device-a", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal("export", document.GetProperty("command").GetString());
        Assert.Equal(1, document.GetProperty("sourceCount").GetInt32());
        Assert.False(document.GetProperty("encrypted").GetBoolean());
        Assert.False(document.GetProperty("canSendSecrets").GetBoolean());
        Assert.True(File.Exists(EnvelopePath(root)));
    }

    /// <summary>Identity is derived once and then kept: a second export must not renumber the source.</summary>
    [Fact]
    public void Run_ExportDerivesAStableSyncSourceId()
    {
        string root = DeviceRoot("a");
        WriteStore(root, EmbyRecord());

        (int firstExit, string firstOut, _) = Run("export", "--root", root, "--device", "device-a", "--json");
        string id = Assert.Single(ReadStore(root)).SyncSourceId!;
        (int secondExit, string secondOut, _) = Run("export", "--root", root, "--device", "device-a", "--json");

        Assert.Equal(CliExitCode.Success, firstExit);
        Assert.Equal(CliExitCode.Success, secondExit);
        Assert.StartsWith("local-", id);
        Assert.Equal(22, id.Length);
        Assert.Equal(id, Assert.Single(ReadStore(root)).SyncSourceId);
        Assert.NotEqual(
            ParseSingleDocument(firstOut).GetProperty("snapshotId").GetString(),
            ParseSingleDocument(secondOut).GetProperty("snapshotId").GetString());
    }

    /// <summary>Without <c>--apply</c> an import is a preview: the target store is never created.</summary>
    [Fact]
    public void Run_ImportWithoutApplyTouchesNothing()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a");

        (int exitCode, string stdout, _) = Run(
            "import", "--root", target, "--in", EnvelopePath(source), "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.True(document.GetProperty("dryRun").GetBoolean());
        Assert.False(document.GetProperty("applied").GetBoolean());
        Assert.Equal(1, document.GetProperty("counts").GetProperty("apply").GetInt32());
        Assert.False(File.Exists(Path.Combine(target, FileLocalSourceStore.FileName)));
    }

    /// <summary>A second <c>--apply</c> of the same envelope is a no-op: byte-identical store, nothing applied.</summary>
    [Fact]
    public void Run_ImportApplyIsIdempotent()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a");
        Run("import", "--root", target, "--in", EnvelopePath(source), "--apply");
        string storePath = Path.Combine(target, FileLocalSourceStore.FileName);
        byte[] before = File.ReadAllBytes(storePath);

        (int exitCode, string stdout, _) = Run(
            "import", "--root", target, "--in", EnvelopePath(source), "--apply", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(0, document.GetProperty("appliedCount").GetInt32());
        Assert.Equal(1, document.GetProperty("skippedCount").GetInt32());
        Assert.Equal(before, File.ReadAllBytes(storePath));
    }

    /// <summary>The receiver keeps the sender identity verbatim and normalises the address.</summary>
    [Fact]
    public void Run_ImportKeepsThePeerIdentityAndReportsUnknownTypes()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord(), UnknownRecord());
        Run("export", "--root", source, "--device", "device-a");
        string embyId = ReadStore(source).Single(record => record.ServerId == "srv-emby").SyncSourceId!;

        (int applyExit, string applyOut, _) = Run(
            "import", "--root", target, "--in", EnvelopePath(source), "--apply", "--json");

        Assert.Equal(CliExitCode.Success, applyExit);
        JsonElement document = ParseSingleDocument(applyOut);
        Assert.Equal(1, document.GetProperty("appliedCount").GetInt32());
        LocalSourceRecord imported = Assert.Single(ReadStore(target));
        Assert.Equal(embyId, imported.ServerId);
        Assert.Equal(embyId, imported.SyncSourceId);
        Assert.Equal("http://nas-a:8096/emby", imported.Url);

        JsonElement unknown = document.GetProperty("entries").EnumerateArray()
            .Single(entry => entry.GetProperty("reason").GetString() == "unknown-source-type");
        Assert.Equal("Unsupported", unknown.GetProperty("action").GetString());
        Assert.Contains("quantum-nas", File.ReadAllText(EnvelopePath(source)), StringComparison.Ordinal);
    }

    /// <summary>An unknown source type asks for a decision instead of being dropped or faked.</summary>
    [Fact]
    public void Run_PlanSendsUnknownTypesToApproval()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, UnknownRecord());
        Run("export", "--root", source, "--device", "device-a");

        (int exitCode, string stdout, _) = Run(
            "plan", "--root", target, "--in", EnvelopePath(source), "--json");

        Assert.Equal(CliExitCode.NeedsApproval, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(0, document.GetProperty("counts").GetProperty("apply").GetInt32());
        JsonElement approval = Assert.Single(document.GetProperty("requiresApproval").EnumerateArray().ToList());
        Assert.Equal("unknown-source-type", approval.GetProperty("reason").GetString());
    }

    /// <summary>Warnings are diagnostics: they must never pollute the machine-readable stream.</summary>
    [Fact]
    public void Run_WarningsGoToStderr()
    {
        string root = DeviceRoot("a");
        WriteStore(root, new LocalSourceRecord
        {
            ServerId = "srv-bad",
            Type = ServerType.WebDav,
            Url = "alice:pw@dav.example/gate",
            DisplayName = "no scheme",
            RecordRevision = 1,
        });

        (int exitCode, string stdout, string stderr) = Run(
            "export", "--root", root, "--device", "device-a", "--json");

        Assert.Equal(CliExitCode.Success, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.Equal(0, document.GetProperty("sourceCount").GetInt32());
        Assert.True(document.GetProperty("warningCount").GetInt32() >= 1);
        Assert.DoesNotContain("warning:", stdout, StringComparison.Ordinal);
        Assert.Contains("source-skipped", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain("pw@dav.example", stdout, StringComparison.Ordinal);
    }

    // ---- archives -----------------------------------------------------------------------

    /// <summary>An archive is written with the documented magic and read back with the passphrase only.</summary>
    [Fact]
    public void Run_EncryptedArchiveRoundTrips()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("c");
        WriteStore(source, EmbyRecord());

        (int exportExit, _, _) = Run(
            "export", "--root", source, "--device", "device-a", "--passphrase", Passphrase);
        byte[] archive = File.ReadAllBytes(ArchivePath(source));
        (int importExit, _, _) = Run(
            "import", "--root", target, "--in", ArchivePath(source), "--passphrase", Passphrase, "--apply");

        Assert.Equal(CliExitCode.Success, exportExit);
        Assert.Equal("MHSYNCA1", Encoding.ASCII.GetString(archive, 0, 8));
        Assert.Equal(CliExitCode.Success, importExit);
        Assert.Single(ReadStore(target));
    }

    /// <summary>A wrong passphrase is a credential failure (exit 4) and leaves the device untouched.</summary>
    [Fact]
    public void Run_WrongPassphraseStopsBeforeWriting()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a", "--passphrase", Passphrase);

        (int exitCode, string stdout, string stderr) = Run(
            "import", "--root", target, "--in", ArchivePath(source),
            "--passphrase", "wrong horse battery staple", "--apply", "--json");

        Assert.Equal(CliExitCode.ArchiveOrCredential, exitCode);
        JsonElement document = ParseSingleDocument(stdout);
        Assert.False(document.GetProperty("ok").GetBoolean());
        Assert.Equal(
            ArchiveError.WrongPassphraseOrTampered,
            document.GetProperty("error").GetProperty("code").GetString());
        Assert.Contains(ArchiveError.WrongPassphraseOrTampered, stderr, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(target, FileLocalSourceStore.FileName)));
    }

    /// <summary>A <c>plan</c> reads plain envelopes only; an archive is an input problem.</summary>
    [Fact]
    public void Run_PlanRefusesAnArchive()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a", "--passphrase", Passphrase);

        (int exitCode, _, string stderr) = Run(
            "plan", "--root", target, "--in", ArchivePath(source));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("archive-not-supported-by-plan", stderr, StringComparison.Ordinal);
    }

    // ---- credentials --------------------------------------------------------------------

    /// <summary>
    /// A secret crosses the wire sealed: the plaintext appears in no output stream, in the envelope,
    /// or in the receiving store, and the receiver can prove it holds the same plaintext.
    /// </summary>
    [Fact]
    public void Run_SecretsNeverAppearInClearText()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a");
        string id = Assert.Single(ReadStore(source)).SyncSourceId!;
        string plaintext = "s3cret-from-device-a";
        string expected = Convert.ToBase64String(Encoding.UTF8.GetBytes(plaintext));
        string secretsJson = "{\"" + id + "\":\"" + expected + "\"}";

        (int exportExit, string exportOut, string exportErr) = Run(
            "export", "--root", source, "--device", "device-a", "--secrets", secretsJson, "--json");
        string envelope = File.ReadAllText(EnvelopePath(source));

        Assert.Equal(CliExitCode.Success, exportExit);
        Assert.DoesNotContain(plaintext, exportOut, StringComparison.Ordinal);
        Assert.DoesNotContain(plaintext, exportErr, StringComparison.Ordinal);
        Assert.DoesNotContain(plaintext, envelope, StringComparison.Ordinal);
        Assert.DoesNotContain(expected, envelope, StringComparison.Ordinal);
        Assert.Contains(SecretEnvelope.SchemeDpapiUser, envelope, StringComparison.Ordinal);

        (int importExit, string importOut, _) = Run(
            "import", "--root", target, "--in", EnvelopePath(source), "--apply", "--json");
        string storeText = File.ReadAllText(Path.Combine(target, FileLocalSourceStore.FileName));

        Assert.Equal(CliExitCode.Success, importExit);
        Assert.DoesNotContain(plaintext, importOut, StringComparison.Ordinal);
        Assert.DoesNotContain(plaintext, storeText, StringComparison.Ordinal);
        Assert.Contains(SecretEnvelope.SchemeDpapiUser, storeText, StringComparison.Ordinal);
        Assert.NotEqual(
            SealedPayloadInEnvelope(envelope, id),
            SealedPayloadInStore(storeText, id));

        (int matchedExit, string matchedOut, _) = Run(
            "secret-check", "--root", target, "--id", id, "--expect", expected, "--json");
        (int mismatchExit, string mismatchOut, _) = Run(
            "secret-check", "--root", target, "--id", id,
            "--expect", Convert.ToBase64String(Encoding.UTF8.GetBytes("another-password")), "--json");

        Assert.Equal(CliExitCode.Success, matchedExit);
        Assert.True(ParseSingleDocument(matchedOut).GetProperty("matched").GetBoolean());
        Assert.Equal(CliExitCode.NeedsApproval, mismatchExit);
        Assert.False(ParseSingleDocument(mismatchOut).GetProperty("matched").GetBoolean());
        Assert.DoesNotContain(plaintext, matchedOut, StringComparison.Ordinal);
    }

    /// <summary>A value that is not base64 is refused without echoing what the caller typed.</summary>
    [Fact]
    public void Run_PlainTextSecretIsRefusedWithoutEcho()
    {
        string root = DeviceRoot("a");
        WriteStore(root, EmbyRecord());
        string careless = "p@ssw0rd-in-the-clear";

        (int exitCode, _, string stderr) = Run(
            "export", "--root", root, "--secrets", "{\"srv-emby\":\"" + careless + "\"}");

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("invalid-secrets", stderr, StringComparison.Ordinal);
        Assert.DoesNotContain(careless, stderr, StringComparison.Ordinal);
    }

    /// <summary>The archive layer refuses credentials, so the two options can never be combined.</summary>
    [Fact]
    public void Run_SecretsAndPassphraseAreMutuallyExclusive()
    {
        string root = DeviceRoot("a");
        WriteStore(root, EmbyRecord());

        (int exitCode, _, string stderr) = Run(
            "export", "--root", root, "--secrets", "{\"a\":\"cGFzcw==\"}",
            "--passphrase", Passphrase);

        Assert.Equal(CliExitCode.UsageError, exitCode);
        Assert.Contains("secrets-and-passphrase-are-mutually-exclusive", stderr, StringComparison.Ordinal);
    }

    // ---- exit code mapping --------------------------------------------------------------

    /// <summary>Automation switches on the number, so the mapping is part of the contract.</summary>
    [Theory]
    [InlineData(ArchiveError.WrongPassphraseOrTampered, CliExitCode.ArchiveOrCredential)]
    [InlineData(ArchiveError.CorruptHeader, CliExitCode.ArchiveOrCredential)]
    [InlineData(SecretError.DpapiUnprotectFailed, CliExitCode.ArchiveOrCredential)]
    [InlineData(SecretError.SecretsRequireProtector, CliExitCode.ArchiveOrCredential)]
    [InlineData("unsupported-version:2", CliExitCode.ArchiveOrCredential)]
    [InlineData("unknown-kdf:argon2id", CliExitCode.ArchiveOrCredential)]
    [InlineData("missing-field:deviceId", CliExitCode.InputProblem)]
    [InlineData("new-source", CliExitCode.InputProblem)]
    [InlineData("", CliExitCode.InputProblem)]
    public void ClassifyReason_MapsFailuresToDocumentedExitCodes(string reason, int expected)
    {
        Assert.Equal(expected, CliExitCode.ClassifyReason(reason));
    }

    // ---- input problems -----------------------------------------------------------------

    /// <summary>An unreadable store is reported; treating it as empty would let an import overwrite user data.</summary>
    [Fact]
    public void Run_DamagedStoreIsReportedNotTreatedAsEmpty()
    {
        string root = DeviceRoot("a");
        string storePath = Path.Combine(root, FileLocalSourceStore.FileName);
        File.WriteAllText(storePath, "{ this is not a source list", new UTF8Encoding(false));

        (int exitCode, _, string stderr) = Run("export", "--root", root);

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("store-unreadable", stderr, StringComparison.Ordinal);
        Assert.Equal("{ this is not a source list", File.ReadAllText(storePath));
    }

    /// <summary>A missing input file is an input problem and never a silent empty envelope.</summary>
    [Fact]
    public void Run_MissingInputFileIsAnInputProblem()
    {
        string root = DeviceRoot("a");

        (int exitCode, _, string stderr) = Run(
            "import", "--root", root, "--in", Path.Combine(root, "nope.json"));

        Assert.Equal(CliExitCode.InputProblem, exitCode);
        Assert.Contains("input-file-missing", stderr, StringComparison.Ordinal);
    }

    // ---- archive file names (W6-66) -------------------------------------------------------

    /// <summary>An encrypted export lands on the canonical .mharchive name and not the legacy one.</summary>
    [Fact]
    public void Run_EncryptedExportUsesTheCanonicalArchiveSuffix()
    {
        string source = DeviceRoot("a");
        WriteStore(source, EmbyRecord());

        (int exitCode, _, _) = Run(
            "export", "--root", source, "--device", "device-a", "--passphrase", Passphrase);

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.Equal("sync-envelope.mharchive", SyncCliRunner.DefaultArchiveFileName);
        Assert.True(File.Exists(Path.Combine(source, "sync-envelope.mharchive")));
        Assert.False(File.Exists(Path.Combine(source, "sync-envelope.mhsync")));
    }

    /// <summary>An archive renamed to the legacy .mhsync suffix is still read: the reader sniffs the magic.</summary>
    [Fact]
    public void Run_ArchiveUnderTheLegacySuffixIsStillRead()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a", "--passphrase", Passphrase);
        string renamed = Path.Combine(source, SyncCliRunner.LegacyArchiveFileName);
        File.Move(ArchivePath(source), renamed);

        (int exitCode, _, string stderr) = Run(
            "import", "--root", target, "--in", renamed, "--passphrase", Passphrase, "--apply");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.DoesNotContain("malformed-payload", stderr, StringComparison.Ordinal);
        Assert.Single(ReadStore(target));
    }

    /// <summary>A plain envelope renamed to .mharchive is still plaintext: extension is never the discriminator.</summary>
    [Fact]
    public void Run_PlainEnvelopeNamedLikeAnArchiveIsStillPlaintext()
    {
        string source = DeviceRoot("a");
        string target = DeviceRoot("b");
        WriteStore(source, EmbyRecord());
        Run("export", "--root", source, "--device", "device-a");
        string renamed = Path.Combine(source, SyncCliRunner.DefaultArchiveFileName);
        File.Move(EnvelopePath(source), renamed);

        (int exitCode, _, string stderr) = Run(
            "import", "--root", target, "--in", renamed, "--apply");

        Assert.Equal(CliExitCode.Success, exitCode);
        Assert.DoesNotContain("passphrase-required", stderr, StringComparison.Ordinal);
        Assert.Single(ReadStore(target));
    }
}

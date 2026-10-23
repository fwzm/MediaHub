namespace MediaHub.SyncCli;

using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;

/// <summary>
/// Executes one parsed command against a device's store and a peer envelope. All state lives in
/// files under <c>--root</c>; nothing here opens a window, a socket or a media server.
/// </summary>
/// <remarks>
/// Two rules shape every method below. First, a preview must not write: the read-only commands run
/// against an in-memory copy of the store, because even <c>EnsureSourceIds</c> (invoked while the
/// local envelope is built) persists identities and would otherwise turn <c>plan</c> into a write.
/// Second, credentials never reach a log: the only place a plaintext exists is a local byte array
/// that is zeroed before the method returns.
/// </remarks>
public sealed class SyncCliRunner
{
    /// <summary>Default file name written by <c>export</c> when <c>--out</c> is absent.</summary>
    public const string DefaultEnvelopeFileName = "sync-envelope.json";

    /// <summary>Default file name written by an encrypted <c>export</c>.</summary>
    public const string DefaultArchiveFileName = "sync-envelope.mhsync";

    private readonly TextWriter _out;
    private readonly TextWriter _err;
    private readonly Func<DateTimeOffset> _clock;

    /// <summary>Creates a runner that reports to the given writers.</summary>
    public SyncCliRunner(TextWriter stdout, TextWriter stderr, Func<DateTimeOffset>? clock = null)
    {
        ArgumentNullException.ThrowIfNull(stdout);
        ArgumentNullException.ThrowIfNull(stderr);
        _out = stdout;
        _err = stderr;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    /// <summary>Runs a parsed command and returns its process exit code.</summary>
    public int Execute(CliInvocation invocation)
    {
        ArgumentNullException.ThrowIfNull(invocation);
        return invocation.Command switch
        {
            SyncCliCommand.Export => RunExport(invocation),
            SyncCliCommand.Import => RunImport(invocation),
            SyncCliCommand.Plan => RunPlan(invocation),
            SyncCliCommand.SecretCheck => RunSecretCheck(invocation),
            _ => Fail(invocation, CliExitCode.UsageError, "unknown-command", "nothing to run"),
        };
    }

    private int RunExport(CliInvocation invocation)
    {
        bool wantsSecrets = invocation.SecretsJson is not null;
        if (wantsSecrets && invocation.Passphrase is not null)
        {
            return Fail(
                invocation,
                CliExitCode.UsageError,
                "secrets-and-passphrase-are-mutually-exclusive",
                "the archive layer refuses envelopes that carry credentials; export them in a separate round");
        }

        IReadOnlyList<Tombstone>? deletions = null;
        if (invocation.DeletionsJson is not null &&
            !TryParseDeletions(invocation.DeletionsJson, out deletions, out string? deletionsError))
        {
            return Fail(invocation, CliExitCode.UsageError, "invalid-deletions", deletionsError ?? "invalid deletions");
        }

        IReadOnlyDictionary<string, byte[]>? secrets = null;
        ISecretProtector? protector = null;
        if (wantsSecrets)
        {
            if (!TryParseSecrets(invocation.SecretsJson!, out secrets, out string? secretsError))
            {
                return Fail(invocation, CliExitCode.UsageError, "invalid-secrets", secretsError ?? "invalid secrets");
            }

            if (secrets is null || secrets.Count == 0)
            {
                return Fail(invocation, CliExitCode.UsageError, "invalid-secrets", "the --secrets map is empty");
            }

            ISecretProtector? created = TryCreateProtector(invocation, out int protectorExit);
            if (created is null)
            {
                return protectorExit;
            }

            protector = created;
        }

        if (!TryOpenStore(invocation, out FileLocalSourceStore store, out int storeExit))
        {
            return storeExit;
        }

        SourceSyncSession session = new(store, DeviceIdOf(invocation), _clock);
        ExportSession export;
        try
        {
            export = session.Export(wantsSecrets, invocation.Passphrase, deletions, protector, secrets);
        }
        catch (SyncContractException error)
        {
            return Fail(invocation, CliExitCode.ClassifyReason(error.Reason), error.Reason, error.Message);
        }
        catch (SecretException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
        }
        catch (ArchiveException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
        }
        catch (IOException error)
        {
            return Fail(invocation, CliExitCode.InputProblem, "store-write-failed", error.Message);
        }

        string path = ResolveExportPath(invocation, export.IsEncrypted);
        byte[] bytes = export.IsEncrypted ? export.Archive : Encoding.UTF8.GetBytes(export.EnvelopeJson);
        try
        {
            string? directory = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            File.WriteAllBytes(path, bytes);
        }
        catch (IOException error)
        {
            return Fail(invocation, CliExitCode.InputProblem, "write-failed", error.Message);
        }
        catch (UnauthorizedAccessException error)
        {
            return Fail(invocation, CliExitCode.InputProblem, "write-failed", error.Message);
        }

        foreach (string warning in export.Warnings)
        {
            _err.WriteLine("warning: " + warning);
        }

        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "export");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("path", path);
                writer.WriteBoolean("encrypted", export.IsEncrypted);
                writer.WriteString("deviceId", session.DeviceId);
                writer.WriteString("snapshotId", export.Envelope.SnapshotId);
                writer.WriteNumber("sourceCount", export.Envelope.Sources.Count);
                writer.WriteNumber("deletionCount", export.Envelope.Deletions.Count);
                writer.WriteBoolean("canSendSecrets", export.Envelope.Capabilities.CanSendSecrets);
                writer.WriteNumber("sealedSecretCount", CountSealedSecrets(export.Envelope));
                writer.WriteNumber("warningCount", export.Warnings.Count);
                writer.WriteStartArray("warnings");
                foreach (string warning in export.Warnings)
                {
                    writer.WriteStringValue(warning);
                }

                writer.WriteEndArray();
                writer.WriteNumber("bytes", bytes.Length);
                writer.WriteEndObject();
            }));
        }
        else
        {
            _out.WriteLine(
                "exported " + export.Envelope.Sources.Count + " source(s) and " +
                export.Envelope.Deletions.Count + " deletion(s) to " + path +
                (export.IsEncrypted ? " (encrypted)" : " (plain)"));
        }

        return CliExitCode.Success;
    }

    private int RunImport(CliInvocation invocation)
    {
        if (!TryReadInput(invocation, out byte[]? bytes, out int readExit))
        {
            return readExit;
        }

        if (!TryResolveEnvelope(invocation, bytes!, out SourceSyncEnvelope? envelope, out int envelopeExit))
        {
            return envelopeExit;
        }

        if (!TryLoadBaseline(invocation, out SourceSyncBaseline baseline, out int baselineExit))
        {
            return baselineExit;
        }

        if (!invocation.Apply)
        {
            if (!TryOpenStore(invocation, out FileLocalSourceStore previewStore, out int previewExit))
            {
                return previewExit;
            }

            // Preview runs against a copy: even identity assignment must not touch the device.
            SourceSyncSession preview = new(ReadOnlyCopy(previewStore), DeviceIdOf(invocation), _clock);
            SyncRoundResult round = preview.PlanRound(envelope!, baseline);
            WriteRound(invocation, "import", round, dryRun: true);
            return round.RequiresApproval.Count > 0 ? CliExitCode.NeedsApproval : CliExitCode.Success;
        }

        if (!TryOpenStore(invocation, out FileLocalSourceStore store, out int storeExit))
        {
            return storeExit;
        }

        ISecretProtector protector;
        try
        {
            protector = new WindowsDpapiProtector();
        }
        catch (PlatformNotSupportedException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, "dpapi-unavailable", error.Message);
        }

        SourceSyncSession session = new(store, DeviceIdOf(invocation), _clock);
        ImportResult result;
        try
        {
            result = session.ImportPlain(envelope!, baseline, protector);
        }
        catch (SecretException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
        }
        catch (ArchiveException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
        }
        catch (SyncContractException error)
        {
            return Fail(invocation, CliExitCode.ClassifyReason(error.Reason), error.Reason, error.Message);
        }
        catch (IOException error)
        {
            return Fail(invocation, CliExitCode.InputProblem, "store-write-failed", error.Message);
        }

        if (!result.Succeeded)
        {
            string reason = result.FailureReason ?? "import-refused";
            return Fail(
                invocation,
                CliExitCode.ClassifyReason(reason),
                reason,
                "the import was refused; nothing was written");
        }

        WriteImportResult(invocation, result);
        return result.RequiresApproval.Count > 0 ? CliExitCode.NeedsApproval : CliExitCode.Success;
    }

    private int RunPlan(CliInvocation invocation)
    {
        if (!TryReadInput(invocation, out byte[]? bytes, out int readExit))
        {
            return readExit;
        }

        if (LooksLikeArchive(bytes!))
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                "archive-not-supported-by-plan",
                "plan reads plain envelopes only; use import --passphrase to preview an archive");
        }

        if (!TryResolveEnvelope(invocation, bytes!, out SourceSyncEnvelope? envelope, out int envelopeExit))
        {
            return envelopeExit;
        }

        if (!TryLoadBaseline(invocation, out SourceSyncBaseline baseline, out int baselineExit))
        {
            return baselineExit;
        }

        if (!TryOpenStore(invocation, out FileLocalSourceStore store, out int storeExit))
        {
            return storeExit;
        }

        SourceSyncSession session = new(ReadOnlyCopy(store), DeviceIdOf(invocation), _clock);
        SyncRoundResult round = session.PlanRound(envelope!, baseline);
        WriteRound(invocation, "plan", round, dryRun: true);
        return round.RequiresApproval.Count > 0 ? CliExitCode.NeedsApproval : CliExitCode.Success;
    }

    private int RunSecretCheck(CliInvocation invocation)
    {
        if (!TryOpenStore(invocation, out FileLocalSourceStore store, out int storeExit))
        {
            return storeExit;
        }

        string wanted = invocation.SyncSourceId!.Trim();
        LocalSourceRecord? record = store.GetAll().FirstOrDefault(candidate =>
            candidate.SyncSourceId is not null &&
            string.Equals(candidate.SyncSourceId.Trim(), wanted, StringComparison.OrdinalIgnoreCase));
        if (record is null)
        {
            return Fail(invocation, CliExitCode.InputProblem, "source-not-found", "no source has syncSourceId " + wanted);
        }

        SecretEnvelope? sealedSecret = record.Secret;
        if (sealedSecret is null || sealedSecret.IsEmpty)
        {
            return Fail(invocation, CliExitCode.InputProblem, "no-secret", "this source has no sealed credential");
        }

        byte[] expected;
        try
        {
            expected = Convert.FromBase64String(invocation.ExpectPlaintextBase64!);
        }
        catch (FormatException)
        {
            return Fail(invocation, CliExitCode.UsageError, "invalid-expect", "--expect must be base64");
        }

        ISecretProtector? protector = TryCreateProtector(invocation, out int protectorExit);
        if (protector is null)
        {
            return protectorExit;
        }

        byte[] plaintext;
        try
        {
            plaintext = protector.Unprotect(sealedSecret);
        }
        catch (SecretException error)
        {
            return Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
        }

        bool matches;
        try
        {
            matches = plaintext.Length == expected.Length &&
                CryptographicOperations.FixedTimeEquals(plaintext, expected);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(expected);
        }

        string blobHash = Convert.ToHexStringLower(SHA256.HashData(sealedSecret.ToPayloadBytes()));
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "secret-check");
                writer.WriteString("syncSourceId", wanted);
                writer.WriteString("scheme", sealedSecret.Scheme);
                writer.WriteString("scope", sealedSecret.Scope);
                writer.WriteBoolean("matched", matches);
                writer.WriteString("blobSha256", blobHash);
                writer.WriteEndObject();
            }));
        }
        else
        {
            _out.WriteLine((matches ? "matched" : "did NOT match") + " " + wanted + " (" + sealedSecret.Scheme + ", blob " + blobHash[..16] + ")");
        }

        return matches ? CliExitCode.Success : CliExitCode.NeedsApproval;
    }
    private void WriteRound(CliInvocation invocation, string command, SyncRoundResult round, bool dryRun)
    {
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", command);
                writer.WriteBoolean("dryRun", dryRun);
                writer.WriteBoolean("applied", false);
                writer.WriteString("root", invocation.Root);
                writer.WriteString("in", invocation.InPath ?? "");
                writer.WriteBoolean("hasWork", round.HasWork);
                WriteCounts(writer, round.Apply.Count, round.RequiresApproval.Count, round.Ignored.Count);
                WriteRoundEntries(writer, "apply", round.Apply);
                WriteRoundEntries(writer, "requiresApproval", round.RequiresApproval);
                WriteRoundEntries(writer, "ignored", round.Ignored);
                writer.WriteEndObject();
            }));
            return;
        }

        _err.WriteLine(
            "preview: " + round.Apply.Count + " to apply, " + round.RequiresApproval.Count +
            " requiring approval, " + round.Ignored.Count + " ignored");
        WriteRoundText("apply", round.Apply);
        WriteRoundText("needs approval", round.RequiresApproval);
        WriteRoundText("ignored", round.Ignored);
    }

    private void WriteImportResult(CliInvocation invocation, ImportResult result)
    {
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "import");
                writer.WriteBoolean("dryRun", false);
                writer.WriteBoolean("applied", true);
                writer.WriteBoolean("succeeded", result.Succeeded);
                writer.WriteString("root", invocation.Root);
                writer.WriteString("in", invocation.InPath ?? "");
                writer.WriteNumber("appliedCount", result.Applied.Count);
                writer.WriteNumber("requiresApprovalCount", result.RequiresApproval.Count);
                writer.WriteNumber("skippedCount", result.Skipped.Count);
                WriteCounts(writer, result.Applied.Count, result.RequiresApproval.Count, result.Skipped.Count);
                writer.WriteStartArray("entries");
                foreach (SourceSyncImportEntry entry in result.Entries)
                {
                    WriteImportEntry(writer, entry);
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }));
            return;
        }

        _err.WriteLine(
            "applied " + result.Applied.Count + ", needs approval " + result.RequiresApproval.Count +
            ", skipped " + result.Skipped.Count);
        foreach (SourceSyncImportEntry entry in result.Applied)
        {
            _out.WriteLine("applied " + entry.SyncSourceId + " " + entry.Reason);
        }

        foreach (SourceSyncImportEntry entry in result.RequiresApproval)
        {
            _out.WriteLine("needs approval " + entry.SyncSourceId + " " + entry.Reason);
        }

        foreach (SourceSyncImportEntry entry in result.Skipped)
        {
            _out.WriteLine("skipped " + entry.SyncSourceId + " " + entry.Reason);
        }
    }

    private void WriteRoundText(string bucket, IReadOnlyList<SyncRoundEntry> entries)
    {
        foreach (SyncRoundEntry entry in entries)
        {
            _out.WriteLine(bucket + " " + entry.SyncSourceId + " " + entry.Reason + " (" + entry.Kind + ")");
        }
    }

    private static void WriteCounts(Utf8JsonWriter writer, int apply, int requiresApproval, int ignored)
    {
        writer.WriteStartObject("counts");
        writer.WriteNumber("apply", apply);
        writer.WriteNumber("requiresApproval", requiresApproval);
        writer.WriteNumber("ignored", ignored);
        writer.WriteEndObject();
    }

    private static void WriteRoundEntries(Utf8JsonWriter writer, string name, IReadOnlyList<SyncRoundEntry> entries)
    {
        writer.WriteStartArray(name);
        foreach (SyncRoundEntry entry in entries)
        {
            writer.WriteStartObject();
            writer.WriteString("syncSourceId", entry.SyncSourceId);
            writer.WriteString("kind", entry.Kind.ToString());
            writer.WriteString("direction", entry.Direction.ToString());
            writer.WriteString("reason", entry.Reason);
            writer.WriteNumber("localRevision", entry.LocalRevision);
            writer.WriteNumber("remoteRevision", entry.RemoteRevision);
            writer.WriteStartArray("conflictingFields");
            foreach (string field in entry.ConflictingFields)
            {
                writer.WriteStringValue(field);
            }

            writer.WriteEndArray();
            writer.WriteBoolean("requiresApproval", entry.RequiresApproval);
            writer.WriteEndObject();
        }

        writer.WriteEndArray();
    }

    private static void WriteImportEntry(Utf8JsonWriter writer, SourceSyncImportEntry entry)
    {
        writer.WriteStartObject();
        writer.WriteString("syncSourceId", entry.SyncSourceId);
        writer.WriteString("action", entry.Action.ToString());
        writer.WriteString("reason", entry.Reason);
        if (entry.ServerId is null)
        {
            writer.WriteNull("serverId");
        }
        else
        {
            writer.WriteString("serverId", entry.ServerId);
        }

        if (entry.DisplayName is null)
        {
            writer.WriteNull("displayName");
        }
        else
        {
            writer.WriteString("displayName", entry.DisplayName);
        }

        writer.WriteBoolean("requiresUserApproval", entry.RequiresUserApproval);
        writer.WriteEndObject();
    }

    private static int CountSealedSecrets(SourceSyncEnvelope envelope)
    {
        int count = 0;
        foreach (SourceRecord record in envelope.Sources)
        {
            if (record.Secrets is { } block && block.ValueKind != JsonValueKind.Null)
            {
                count++;
            }
        }

        return count;
    }

    private static string ResolveExportPath(CliInvocation invocation, bool encrypted)
    {
        if (invocation.OutPath is not null)
        {
            return invocation.OutPath;
        }

        return Path.Combine(
            invocation.Root,
            encrypted ? DefaultArchiveFileName : DefaultEnvelopeFileName);
    }

    private bool TryReadInput(CliInvocation invocation, out byte[]? bytes, out int exitCode)
    {
        bytes = null;
        exitCode = 0;
        string path = invocation.InPath!;
        if (!File.Exists(path))
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "input-file-missing", "no such file: " + path);
            return false;
        }

        try
        {
            bytes = File.ReadAllBytes(path);
            return true;
        }
        catch (IOException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "input-unreadable", error.Message);
            return false;
        }
        catch (UnauthorizedAccessException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "input-unreadable", error.Message);
            return false;
        }
    }

    private bool TryResolveEnvelope(
        CliInvocation invocation,
        byte[] bytes,
        out SourceSyncEnvelope? envelope,
        out int exitCode)
    {
        envelope = null;
        exitCode = 0;
        if (LooksLikeArchive(bytes))
        {
            if (invocation.Passphrase is null)
            {
                exitCode = Fail(
                    invocation,
                    CliExitCode.InputProblem,
                    "passphrase-required",
                    "the input is an encrypted archive; pass --passphrase");
                return false;
            }

            try
            {
                envelope = EncryptedArchiveReader.Read(bytes, invocation.Passphrase);
                return true;
            }
            catch (ArchiveException error)
            {
                exitCode = Fail(invocation, CliExitCode.ArchiveOrCredential, error.Reason, error.Message);
                return false;
            }
        }

        string text;
        try
        {
            text = new UTF8Encoding(false, true).GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "malformed-payload", "the input is not UTF-8 text");
            return false;
        }

        try
        {
            envelope = SourceSyncCodec.Deserialize(text);
            return true;
        }
        catch (SyncContractException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, error.Reason, error.Message);
            return false;
        }
        catch (JsonException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "malformed-payload", error.Message);
            return false;
        }
    }

    private static bool LooksLikeArchive(byte[] bytes)
    {
        if (bytes.Length < ArchiveHeader.CurrentMagic.Length)
        {
            return false;
        }

        return Encoding.ASCII.GetString(bytes, 0, ArchiveHeader.CurrentMagic.Length) == ArchiveHeader.CurrentMagic;
    }

    private bool TryLoadBaseline(CliInvocation invocation, out SourceSyncBaseline baseline, out int exitCode)
    {
        baseline = SourceSyncBaseline.Empty;
        exitCode = 0;
        if (invocation.BaselinePath is null)
        {
            return true;
        }

        if (!File.Exists(invocation.BaselinePath))
        {
            exitCode = Fail(
                invocation,
                CliExitCode.InputProblem,
                "baseline-file-missing",
                "no such file: " + invocation.BaselinePath);
            return false;
        }

        try
        {
            string text = File.ReadAllText(invocation.BaselinePath, Encoding.UTF8);
            baseline = SourceSyncBaseline.FromEnvelope(SourceSyncCodec.Deserialize(text));
            return true;
        }
        catch (IOException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "baseline-unreadable", error.Message);
            return false;
        }
        catch (SyncContractException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, error.Reason, error.Message);
            return false;
        }
        catch (JsonException error)
        {
            exitCode = Fail(invocation, CliExitCode.InputProblem, "malformed-payload", error.Message);
            return false;
        }
    }

    private bool TryOpenStore(CliInvocation invocation, out FileLocalSourceStore store, out int exitCode)
    {
        exitCode = 0;
        store = new FileLocalSourceStore(invocation.Root);
        if (store.LoadError is null)
        {
            return true;
        }

        // A damaged store is never silently treated as empty: that would let an import overwrite
        // sources the user still has on disk (the store itself refuses to write in this state).
        exitCode = Fail(invocation, CliExitCode.InputProblem, "store-unreadable", store.LoadError);
        return false;
    }

    private static InMemoryLocalSourceStore ReadOnlyCopy(ILocalSourceStore source)
    {
        InMemoryLocalSourceStore copy = new();
        foreach (LocalSourceRecord record in source.GetAll())
        {
            copy.Save(record);
        }

        return copy;
    }

    private static string DeviceIdOf(CliInvocation invocation)
    {
        if (!string.IsNullOrWhiteSpace(invocation.DeviceId))
        {
            return invocation.DeviceId.Trim();
        }

        string machine = Environment.MachineName.Trim().ToLowerInvariant();
        StringBuilder builder = new("cli-");
        foreach (char character in machine)
        {
            builder.Append(char.IsLetterOrDigit(character) || character is '-' or '_' or '.' ? character : '-');
        }

        return builder.ToString();
    }

    private ISecretProtector? TryCreateProtector(CliInvocation invocation, out int exitCode)
    {
        exitCode = 0;
        if (!OperatingSystem.IsWindows())
        {
            exitCode = Fail(
                invocation,
                CliExitCode.ArchiveOrCredential,
                "dpapi-unavailable",
                "DPAPI is only available on Windows");
            return null;
        }

        return new WindowsDpapiProtector();
    }

    private static bool TryParseSecrets(
        string json,
        out IReadOnlyDictionary<string, byte[]>? secrets,
        out string? error)
    {
        secrets = null;
        error = null;
        try
        {
            using JsonDocument document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Object)
            {
                error = "--secrets must be a JSON object of syncSourceId to base64 plaintext";
                return false;
            }

            Dictionary<string, byte[]> map = new(StringComparer.OrdinalIgnoreCase);
            foreach (JsonProperty property in document.RootElement.EnumerateObject())
            {
                if (string.IsNullOrWhiteSpace(property.Name))
                {
                    error = "a --secrets key is empty";
                    return false;
                }

                if (property.Value.ValueKind != JsonValueKind.String)
                {
                    // The value is never echoed: only the key (a syncSourceId) is safe to print.
                    error = "the value for " + property.Name + " must be a base64 string";
                    return false;
                }

                byte[] plaintext;
                try
                {
                    plaintext = Convert.FromBase64String(property.Value.GetString() ?? "");
                }
                catch (FormatException)
                {
                    error = "the value for " + property.Name + " is not base64";
                    return false;
                }

                if (plaintext.Length == 0)
                {
                    error = "the value for " + property.Name + " decodes to nothing";
                    return false;
                }

                map[property.Name.Trim()] = plaintext;
            }

            secrets = map;
            return true;
        }
        catch (JsonException exception)
        {
            error = "--secrets is not valid JSON: " + exception.Message;
            return false;
        }
    }

    private static bool TryParseDeletions(
        string json,
        out IReadOnlyList<Tombstone>? deletions,
        out string? error)
    {
        deletions = null;
        error = null;
        try
        {
            using JsonDocument document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                error = "--deletions must be a JSON array";
                return false;
            }

            List<Tombstone> list = [];
            foreach (JsonElement element in document.RootElement.EnumerateArray())
            {
                if (element.ValueKind != JsonValueKind.Object)
                {
                    error = "each --deletions entry must be an object";
                    return false;
                }

                string? syncSourceId = null;
                if (element.TryGetProperty("syncSourceId", out JsonElement idElement) &&
                    idElement.ValueKind == JsonValueKind.String)
                {
                    syncSourceId = idElement.GetString();
                }

                if (string.IsNullOrWhiteSpace(syncSourceId))
                {
                    error = "a --deletions entry is missing syncSourceId";
                    return false;
                }

                list.Add(new Tombstone
                {
                    SyncSourceId = syncSourceId.Trim(),
                    RecordRevision = ReadLong(element, "recordRevision"),
                    DeletedAtEpochMs = ReadLong(element, "deletedAtEpochMs"),
                });
            }

            deletions = list;
            return true;
        }
        catch (JsonException exception)
        {
            error = "--deletions is not valid JSON: " + exception.Message;
            return false;
        }
    }

    private static long ReadLong(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out JsonElement value))
        {
            return 0;
        }

        return value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out long number) ? number : 0;
    }

    private int Fail(CliInvocation invocation, int exitCode, string code, string message)
    {
        CliOutput.WriteError(_out, _err, invocation.Json, code, message);
        return exitCode;
    }
}

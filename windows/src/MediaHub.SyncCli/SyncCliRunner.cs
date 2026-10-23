namespace MediaHub.SyncCli;

using System.Globalization;
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
            SyncCliCommand.BaselineShow => RunBaselineShow(invocation),
            SyncCliCommand.BaselineForget => RunBaselineForget(invocation),
            SyncCliCommand.PairLink => RunPairLink(invocation),
            SyncCliCommand.PairVerify => RunPairVerify(invocation),
            SyncCliCommand.PairList => RunPairList(invocation),
            SyncCliCommand.PairForget => RunPairForget(invocation),
            SyncCliCommand.TransferServe => RunTransferServe(invocation),
            SyncCliCommand.TransferSend => RunTransferSend(invocation),
            SyncCliCommand.TransferRoundtrip => RunTransferRoundtrip(invocation),
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

    // ---- baseline group (W6-15) -------------------------------------------------------------
    // The baseline store merges on Save, so it can never drop an entry; the CLI forget verbs go
    // through the store's own atomic write (temporary file + File.Replace) instead of editing JSON.

    private int RunBaselineShow(CliInvocation invocation)
    {
        if (!TryLoadBaselineStore(invocation, out FileSourceSyncBaselineStore store, out int storeExit))
        {
            return storeExit;
        }

        IReadOnlyList<string> peers = store.PeerDeviceIds;
        bool exists = File.Exists(store.FilePath);
        List<(string DeviceId, SourceSyncBaseline Baseline)> rows = [];
        int entryCount = 0;
        foreach (string peer in peers)
        {
            SourceSyncBaseline baseline = store.Load(peer);
            rows.Add((peer, baseline));
            entryCount += baseline.Entries.Count;
        }

        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "baseline");
                writer.WriteString("subcommand", "show");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("path", store.FilePath);
                writer.WriteBoolean("exists", exists);
                writer.WriteNumber("documentVersion", FileSourceSyncBaselineStore.CurrentDocumentVersion);
                writer.WriteNumber("peerCount", rows.Count);
                writer.WriteNumber("entryCount", entryCount);
                writer.WriteStartArray("peers");
                foreach ((string deviceId, SourceSyncBaseline baseline) in rows)
                {
                    writer.WriteStartObject();
                    writer.WriteString("deviceId", deviceId);
                    writer.WriteString("snapshotId", baseline.SnapshotId ?? "");
                    writer.WriteNumber("entryCount", baseline.Entries.Count);
                    writer.WriteStartArray("entries");
                    foreach (SourceSyncBaselineEntry entry in baseline.Entries)
                    {
                        writer.WriteStartObject();
                        writer.WriteString("syncSourceId", entry.SyncSourceId);
                        writer.WriteNumber("recordRevision", entry.RecordRevision);
                        writer.WriteNumber("fieldVersion", entry.FieldVersion);
                        writer.WriteString("contentHash", entry.ContentHash);
                        writer.WriteString("contentHashPrefix", Prefix(entry.ContentHash, 12));
                        writer.WriteEndObject();
                    }

                    writer.WriteEndArray();
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("baseline: " + store.FilePath);
        _out.WriteLine("exists: " + (exists ? "true" : "false"));
        _out.WriteLine("version: " + FileSourceSyncBaselineStore.CurrentDocumentVersion.ToString(CultureInfo.InvariantCulture));
        _out.WriteLine("peers: " + rows.Count.ToString(CultureInfo.InvariantCulture));
        _out.WriteLine("entries: " + entryCount.ToString(CultureInfo.InvariantCulture));
        foreach ((string deviceId, SourceSyncBaseline baseline) in rows)
        {
            _out.WriteLine("peer " + deviceId + " (" + baseline.Entries.Count.ToString(CultureInfo.InvariantCulture) + " entries)");
            foreach (SourceSyncBaselineEntry entry in baseline.Entries)
            {
                _out.WriteLine("  " + entry.SyncSourceId + "  rev " + entry.RecordRevision.ToString(CultureInfo.InvariantCulture) + "  " + Prefix(entry.ContentHash, 12));
            }
        }

        if (!exists)
        {
            _out.WriteLine("nothing has been imported from a peer yet");
        }

        return CliExitCode.Success;
    }

    private int RunBaselineForget(CliInvocation invocation)
    {
        if (!TryLoadBaselineStore(invocation, out FileSourceSyncBaselineStore store, out int storeExit))
        {
            return storeExit;
        }

        string deviceId = invocation.DeviceId!.Trim();
        string? known = store.PeerDeviceIds.FirstOrDefault(peer => string.Equals(peer, deviceId, StringComparison.OrdinalIgnoreCase));
        if (known is null)
        {
            return Fail(invocation, CliExitCode.InputProblem, "baseline-device-unknown", "the sync baseline holds nothing for device " + deviceId);
        }

        SourceSyncBaseline baseline = store.Load(known);
        string? sourceId = invocation.SourceId?.Trim();
        SourceSyncBaselineEntry? target = null;
        if (!string.IsNullOrEmpty(sourceId))
        {
            target = baseline.Find(sourceId);
            if (target is null)
            {
                return Fail(invocation, CliExitCode.InputProblem, "baseline-source-unknown", "device " + known + " has no baseline entry for source " + sourceId);
            }
        }

        IReadOnlyList<SourceSyncBaselineEntry> removed = target is null ? baseline.Entries : [target];
        int remainingEntries = baseline.Entries.Count - removed.Count;
        bool applied = false;
        if (invocation.Apply)
        {
            bool written;
            try
            {
                written = target is null ? store.ForgetPeer(known) : store.ForgetEntry(known, target.SyncSourceId);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                return Fail(invocation, CliExitCode.InputProblem, "baseline-write-failed", error.Message);
            }

            if (!written)
            {
                return Fail(invocation, CliExitCode.InputProblem, "baseline-nothing-forgotten", "the sync baseline did not change; nothing was written");
            }

            applied = true;
            remainingEntries = store.Load(known).Entries.Count;
        }

        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "baseline");
                writer.WriteString("subcommand", "forget");
                writer.WriteBoolean("dryRun", !invocation.Apply);
                writer.WriteBoolean("applied", applied);
                writer.WriteString("root", invocation.Root);
                writer.WriteString("path", store.FilePath);
                writer.WriteString("deviceId", known);
                writer.WriteString("sourceId", target?.SyncSourceId ?? "");
                writer.WriteNumber("removedCount", removed.Count);
                writer.WriteNumber("remainingEntries", remainingEntries);
                writer.WriteBoolean("peerRemoved", remainingEntries == 0);
                writer.WriteStartArray("removed");
                foreach (SourceSyncBaselineEntry entry in removed)
                {
                    writer.WriteStartObject();
                    writer.WriteString("syncSourceId", entry.SyncSourceId);
                    writer.WriteNumber("recordRevision", entry.RecordRevision);
                    writer.WriteString("contentHashPrefix", Prefix(entry.ContentHash, 12));
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        if (!invocation.Apply)
        {
            _err.WriteLine("preview: nothing was written; pass --apply to forget");
        }

        _out.WriteLine((invocation.Apply ? "forgotten " : "would forget ")
            + removed.Count.ToString(CultureInfo.InvariantCulture) + " entries for " + known);
        foreach (SourceSyncBaselineEntry entry in removed)
        {
            _out.WriteLine("  " + entry.SyncSourceId + "  rev " + entry.RecordRevision.ToString(CultureInfo.InvariantCulture));
        }

        _out.WriteLine("remaining: " + remainingEntries.ToString(CultureInfo.InvariantCulture) + " entries");
        return CliExitCode.Success;
    }

    // ---- pair group (W6-15) -----------------------------------------------------------------
    // Nothing here opens a socket: "pair link" only describes this machine and "pair verify" only
    // decodes a link, so no CLI invocation can trip the Windows Firewall prompt.

    private int RunPairLink(CliInvocation invocation)
    {
        long now = _clock().ToUnixTimeMilliseconds();
        PairingPayload payload;
        string link;
        try
        {
            PairingSelfIdentity identity = PairingSelfIdentity.Create(DeviceIdOf(invocation));
            payload = PairingPayload.Create(
                identity.DeviceId,
                LoopbackPlaceholderUrl,
                PairingChallenge.Create(),
                identity.Fingerprint,
                now);
            link = PairingLink.Create(payload);
        }
        catch (SyncContractException error)
        {
            return Fail(invocation, CliExitCode.InputProblem, error.Reason, error.Message);
        }

        string fingerprintPrefix = Prefix(payload.PublicKeyFingerprint, 8);
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "pair");
                writer.WriteString("subcommand", "link");
                writer.WriteString("deviceId", payload.DeviceId);
                writer.WriteString("fingerprintPrefix", fingerprintPrefix);
                writer.WriteString("link", link);
                writer.WriteNumber("expiresAtEpochMs", payload.ExpiresAtEpochMs);
                writer.WriteNumber("lifetimeSeconds", PairingPayload.LifetimeSeconds);
                writer.WriteBoolean("listens", false);
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("device: " + payload.DeviceId);
        _out.WriteLine("fingerprint: " + fingerprintPrefix + "...");
        _out.WriteLine("expires: " + FormatEpoch(payload.ExpiresAtEpochMs));
        _out.WriteLine("link: " + link);
        _out.WriteLine("note: this CLI does not listen; the link identifies this device and is checked offline (pair verify).");
        return CliExitCode.Success;
    }

    private int RunPairVerify(CliInvocation invocation)
    {
        long now = _clock().ToUnixTimeMilliseconds();
        PairingLinkResult result = PairingLink.Parse(invocation.Link, now);
        if (!result.Succeeded || result.Payload is null)
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                result.Reason ?? PairingCodeReasons.PayloadInvalid,
                result.Detail ?? "the pairing link could not be read");
        }

        PairingPayload payload = result.Payload;
        string fingerprintPrefix = Prefix(payload.PublicKeyFingerprint, 8);
        long remainingSeconds = Math.Max(0, (payload.ExpiresAtEpochMs - now) / 1000);
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "pair");
                writer.WriteString("subcommand", "verify");
                writer.WriteString("deviceId", payload.DeviceId);
                writer.WriteString("fingerprintPrefix", fingerprintPrefix);
                writer.WriteString("ephemeralUrl", payload.EphemeralUrl);
                writer.WriteNumber("expiresAtEpochMs", payload.ExpiresAtEpochMs);
                writer.WriteNumber("remainingSeconds", remainingSeconds);
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("pairing link ok");
        _out.WriteLine("device: " + payload.DeviceId);
        _out.WriteLine("fingerprint: " + fingerprintPrefix + "...");
        _out.WriteLine("expires: " + FormatEpoch(payload.ExpiresAtEpochMs) + " (" + remainingSeconds.ToString(CultureInfo.InvariantCulture) + "s left)");
        _out.WriteLine("note: the fingerprint is self-declared, so compare it on both devices before trusting the link.");
        return CliExitCode.Success;
    }

    private int RunPairList(CliInvocation invocation)
    {
        if (!TryLoadPairedStore(invocation, out FilePairedDeviceStore store, out int storeExit))
        {
            return storeExit;
        }

        IReadOnlyList<PairedDevice> devices = store.GetAll();
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "pair");
                writer.WriteString("subcommand", "list");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("path", store.FilePath);
                writer.WriteNumber("count", devices.Count);
                writer.WriteStartArray("devices");
                foreach (PairedDevice device in devices)
                {
                    writer.WriteStartObject();
                    writer.WriteString("deviceId", device.DeviceId);
                    writer.WriteString("displayName", device.DisplayName);
                    writer.WriteString("fingerprintSuffix", Suffix(device.Fingerprint, 8));
                    writer.WriteNumber("sourceCount", device.SourceCount);
                    writer.WriteNumber("pairedAtEpochMs", device.PairedAtEpochMs);
                    writer.WriteNumber("lastSeenEpochMs", device.LastSeenEpochMs);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("paired devices: " + devices.Count.ToString(CultureInfo.InvariantCulture));
        if (devices.Count == 0)
        {
            _out.WriteLine("no devices are paired on this machine");
        }

        foreach (PairedDevice device in devices)
        {
            _out.WriteLine(device.DeviceId + "  name=" + device.DisplayNameOrId
                + "  fp=..." + Suffix(device.Fingerprint, 8)
                + "  sources " + device.SourceCount.ToString(CultureInfo.InvariantCulture)
                + "  last " + FormatEpoch(device.LastSeenEpochMs));
        }

        return CliExitCode.Success;
    }

    private int RunPairForget(CliInvocation invocation)
    {
        if (!TryLoadPairedStore(invocation, out FilePairedDeviceStore store, out int storeExit))
        {
            return storeExit;
        }

        string deviceId = invocation.DeviceId!.Trim();
        PairedDevice? device = store.GetAll().FirstOrDefault(candidate =>
            string.Equals(candidate.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
        if (device is null)
        {
            return Fail(invocation, CliExitCode.InputProblem, "device-unknown", "no paired device has id " + deviceId);
        }

        bool applied = false;
        if (invocation.Apply)
        {
            bool forgotten;
            try
            {
                forgotten = store.Forget(device.DeviceId);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                return Fail(invocation, CliExitCode.InputProblem, "store-write-failed", error.Message);
            }

            if (!forgotten)
            {
                return Fail(invocation, CliExitCode.InputProblem, "device-unknown", "the paired-device list did not contain " + device.DeviceId);
            }

            applied = true;
        }

        int remainingCount = store.GetAll().Count - (applied ? 0 : 1);
        string fingerprintSuffix = Suffix(device.Fingerprint, 8);
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "pair");
                writer.WriteString("subcommand", "forget");
                writer.WriteBoolean("dryRun", !invocation.Apply);
                writer.WriteBoolean("applied", applied);
                writer.WriteString("root", invocation.Root);
                writer.WriteString("path", store.FilePath);
                writer.WriteString("deviceId", device.DeviceId);
                writer.WriteString("displayName", device.DisplayName);
                writer.WriteString("fingerprintSuffix", fingerprintSuffix);
                writer.WriteNumber("remainingCount", remainingCount);
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        if (!invocation.Apply)
        {
            _err.WriteLine("preview: nothing was written; pass --apply to forget");
        }

        _out.WriteLine((invocation.Apply ? "forgotten " : "would forget ") + device.DeviceId
            + " (" + device.DisplayNameOrId + ", fp ..." + fingerprintSuffix + ")");
        _out.WriteLine("this removes the local pin only: sources, credentials and the peer are untouched.");
        _out.WriteLine("remaining: " + remainingCount.ToString(CultureInfo.InvariantCulture) + " paired devices");
        return CliExitCode.Success;
    }

    // ---- transfer group (W6-18) -------------------------------------------------------------
    // The socket layer is W6-16's: 4-byte big-endian length frames, loopback only, and a non-loopback
    // address is refused before any socket exists (binding a LAN address would make Windows ask the
    // user to open the firewall). This group only wires that layer to the import round so the whole
    // hand-over can be rehearsed from a terminal, and it reuses the round's gate for every write.

    private static readonly TimeSpan TransferBudget = TimeSpan.FromSeconds(15);

    private int RunTransferServe(CliInvocation invocation) => RunTransferServeAsync(invocation).GetAwaiter().GetResult();

    private int RunTransferSend(CliInvocation invocation) => RunTransferSendAsync(invocation).GetAwaiter().GetResult();

    private int RunTransferRoundtrip(CliInvocation invocation) => RunTransferRoundtripAsync(invocation).GetAwaiter().GetResult();

    private async Task<int> RunTransferServeAsync(CliInvocation invocation)
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(
            SourceSyncTransferAddress.DefaultAddress,
            invocation.Port ?? 0);
        SourceSyncTransferListener? bound = start.Listener;
        if (!start.Started || bound is null)
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                start.Reason ?? SourceSyncTransferReasons.ListenerFailed,
                start.Detail ?? "the loopback listener could not be bound");
        }

        await using SourceSyncTransferListener listener = bound;
        if (!TryOpenStoresAt(invocation, invocation.Root, out FileLocalSourceStore sources, out FileSourceSyncBaselineStore baselines, out int storeExit))
        {
            return storeExit;
        }

        // The port is announced before anything is accepted. With --json stdout carries exactly one
        // document, written after the import finishes, so a script that waited for the port there
        // would wait forever; stderr is the progress channel and always carries it.
        _err.WriteLine("listening " + listener.Address + ":" + listener.Port.ToString(CultureInfo.InvariantCulture));
        _err.Flush();

        SourceSyncTransferResult received = await listener
            .ReceiveOnceAsync(null, TransferBudget, CancellationToken.None)
            .ConfigureAwait(false);
        if (!received.Succeeded)
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                received.Reason ?? SourceSyncTransferReasons.IoFailed,
                received.Detail ?? "the peer did not deliver an envelope");
        }

        if (!SourceSyncInbox.TryDecode(received.Envelope, out SourceSyncEnvelope? envelope, out string? decodeError) || envelope is null)
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                SourceSyncTransferReasons.EnvelopeInvalid,
                decodeError ?? "the payload is not a sync envelope");
        }

        // A preview runs against a read-only copy: building a local envelope can persist identities,
        // so a dry run must never hold the real store.
        SourceSyncPeerRound receiver = new(DeviceIdOf(invocation), invocation.Apply ? sources : ReadOnlyCopy(sources), baselines);
        SourceSyncApplyPreview preview = receiver.Preview(envelope);
        SourceSyncApplyReport? report = invocation.Apply
            ? await receiver.ApplyAsync(preview, TransferWriter(sources), null, cancellationToken: CancellationToken.None).ConfigureAwait(false)
            : null;

        TransferOutcome outcome = new(envelope.DeviceId, receiver.DeviceId, received.Bytes, received.FrameCount, preview, report, null, null);
        return WriteServeResult(invocation, listener.Port, listener.Address, outcome);
    }

    private async Task<int> RunTransferSendAsync(CliInvocation invocation)
    {
        if (!TryParseTarget(invocation.Target, out string host, out int port, out string? targetError))
        {
            return Fail(invocation, CliExitCode.InputProblem, "invalid-target", targetError ?? "the --to value must look like host:port");
        }

        if (!TryOpenStore(invocation, out FileLocalSourceStore sources, out int storeExit))
        {
            return storeExit;
        }

        // Sending writes nothing: the envelope is built from a copy, so even a missing syncSourceId
        // cannot turn this command into a write on the sender's own disk.
        SourceSyncPeerRound sender = new(DeviceIdOf(invocation), ReadOnlyCopy(sources));
        SourceSyncEnvelope envelope = sender.Export();
        SourceSyncPeerRoundResult sent = await sender
            .SendAsync(envelope, host, port, null, TransferBudget, CancellationToken.None)
            .ConfigureAwait(false);
        if (!sent.Succeeded)
        {
            return Fail(
                invocation,
                CliExitCode.InputProblem,
                sent.Reason ?? SourceSyncTransferReasons.IoFailed,
                sent.Detail ?? "the envelope could not be sent");
        }

        return WriteSendResult(invocation, host, port, envelope, sent);
    }

    private async Task<int> RunTransferRoundtripAsync(CliInvocation invocation)
    {
        string deviceA = invocation.DeviceA!.Trim();
        string deviceB = invocation.DeviceB!.Trim();
        string rootA = Path.Combine(invocation.Root, "a");
        string rootB = Path.Combine(invocation.Root, "b");
        if (!TryOpenStoresAt(invocation, rootA, out FileLocalSourceStore sourcesA, out FileSourceSyncBaselineStore baselinesA, out int exitA))
        {
            return exitA;
        }

        if (!TryOpenStoresAt(invocation, rootB, out FileLocalSourceStore sourcesB, out FileSourceSyncBaselineStore baselinesB, out int exitB))
        {
            return exitB;
        }

        bool apply = invocation.Apply;

        // --apply is the promise that this command may write, so only then do the two sides hold the
        // real stores; without it every store in play is an in-memory copy and no file appears.
        SourceSyncPeerRound senderA = new(deviceA, apply ? sourcesA : ReadOnlyCopy(sourcesA));
        SourceSyncPeerRound receiverB = new(deviceB, apply ? sourcesB : ReadOnlyCopy(sourcesB), baselinesB);
        TransferOutcome first = await TransferOnceAsync(senderA, receiverB, TransferWriter(sourcesB), apply).ConfigureAwait(false);
        if (!first.Succeeded)
        {
            return Fail(invocation, CliExitCode.InputProblem, first.Reason ?? SourceSyncTransferReasons.IoFailed,
                "round 1 (a -> b) failed: " + (first.Detail ?? "no detail"));
        }

        SourceSyncPeerRound senderB = new(deviceB, apply ? sourcesB : ReadOnlyCopy(sourcesB));
        SourceSyncPeerRound receiverA = new(deviceA, apply ? sourcesA : ReadOnlyCopy(sourcesA), baselinesA);
        TransferOutcome second = await TransferOnceAsync(senderB, receiverA, TransferWriter(sourcesA), apply).ConfigureAwait(false);
        if (!second.Succeeded)
        {
            return Fail(invocation, CliExitCode.InputProblem, second.Reason ?? SourceSyncTransferReasons.IoFailed,
                "round 2 (b -> a) failed: " + (second.Detail ?? "no detail"));
        }

        return WriteRoundtripResult(invocation, rootA, rootB, deviceA, deviceB, first, second);
    }

    /// <summary>One direction of a transfer, in the shape both the JSON and the text output need.</summary>
    private sealed record TransferOutcome(
        string From,
        string To,
        int Bytes,
        int Frames,
        SourceSyncApplyPreview? Preview,
        SourceSyncApplyReport? Report,
        string? Reason,
        string? Detail)
    {
        public bool Succeeded => Reason is null;

        public bool NoChange => Report is { Applied: 0 };
    }

    private async Task<TransferOutcome> TransferOnceAsync(
        SourceSyncPeerRound sender,
        SourceSyncPeerRound receiver,
        SourceSyncRecordWriter writer,
        bool apply)
    {
        SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(SourceSyncTransferAddress.DefaultAddress, 0);
        SourceSyncTransferListener? bound = start.Listener;
        if (!start.Started || bound is null)
        {
            return new TransferOutcome(sender.DeviceId, receiver.DeviceId, 0, 0, null, null, start.Reason ?? SourceSyncTransferReasons.ListenerFailed, start.Detail);
        }

        await using SourceSyncTransferListener listener = bound;
        byte[] payload = EnvelopeBytes(sender.Export());

        // If the send fails there is no connection coming, so the receive side is cancelled rather
        // than left to wait out the whole budget: a failed round must fail fast.
        using CancellationTokenSource abort = new();
        Task<SourceSyncTransferResult> receiving = listener.ReceiveOnceAsync(null, TransferBudget, abort.Token);
        SourceSyncPeerRoundResult sent = await sender
            .SendPayloadAsync(payload, SourceSyncTransferAddress.DefaultAddress, listener.Port, null, TransferBudget, CancellationToken.None)
            .ConfigureAwait(false);
        if (!sent.Succeeded)
        {
            abort.Cancel();
            await ObserveAsync(receiving).ConfigureAwait(false);
            return new TransferOutcome(sender.DeviceId, receiver.DeviceId, 0, 0, null, null, sent.Reason, sent.Detail);
        }

        SourceSyncTransferResult received = await receiving.ConfigureAwait(false);
        if (!received.Succeeded)
        {
            return new TransferOutcome(sender.DeviceId, receiver.DeviceId, sent.Bytes, sent.FrameCount, null, null, received.Reason, received.Detail);
        }

        if (!SourceSyncInbox.TryDecode(received.Envelope, out SourceSyncEnvelope? envelope, out string? decodeError) || envelope is null)
        {
            return new TransferOutcome(sender.DeviceId, receiver.DeviceId, received.Bytes, received.FrameCount, null, null,
                SourceSyncTransferReasons.EnvelopeInvalid, decodeError);
        }

        SourceSyncApplyPreview preview = receiver.Preview(envelope);
        SourceSyncApplyReport? report = apply
            ? await receiver.ApplyAsync(preview, writer, null, cancellationToken: CancellationToken.None).ConfigureAwait(false)
            : null;
        return new TransferOutcome(envelope.DeviceId, receiver.DeviceId, received.Bytes, received.FrameCount, preview, report, null, null);
    }

    private static async Task ObserveAsync(Task task)
    {
        try
        {
            await task.ConfigureAwait(false);
        }
        catch (Exception)
        {
            // The round already reported its own failure; this task exists only to be shut down.
        }
    }

    private static byte[] EnvelopeBytes(SourceSyncEnvelope envelope) =>
        Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));

    private static SourceSyncRecordWriter TransferWriter(ILocalSourceStore store) =>
        (item, record) => Task.FromResult(WriteTransferredSource(store, item, record));

    private static SourceSyncWriteOutcome WriteTransferredSource(ILocalSourceStore store, SourceSyncApprovalItem item, SourceRecord? record)
    {
        if (record is null)
        {
            // A tombstone. The gate asks the user first, so reaching this line means it was allowed.
            return store.Delete(item.SyncSourceId)
                ? SourceSyncWriteOutcome.Written("removed " + item.SyncSourceId)
                : SourceSyncWriteOutcome.Refused("nothing matched " + item.SyncSourceId);
        }

        LocalSourceRecord local = new()
        {
            ServerId = string.IsNullOrWhiteSpace(item.ServerId) ? "srv-" + item.SyncSourceId : item.ServerId.Trim(),
            SyncSourceId = item.SyncSourceId,
            Type = WindowsSourceTypeMap.ToServerType(record.SourceType.Wire),
            SourceTypeRaw = record.SourceType.Raw,
            Url = PrimaryUrl(record),
            AccountScope = record.AccountScope,
            DisplayName = record.DisplayName,
            RecordRevision = record.RecordRevision,
        };

        try
        {
            store.Save(local);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or ArgumentException)
        {
            return SourceSyncWriteOutcome.Refused(error.Message);
        }

        return SourceSyncWriteOutcome.Written("saved " + item.SyncSourceId);
    }

    private static string? PrimaryUrl(SourceRecord record)
    {
        SourceEndpoint? primary = record.Endpoints.FirstOrDefault(endpoint => endpoint.IsPrimary && endpoint.Enabled)
            ?? record.Endpoints.FirstOrDefault(endpoint => endpoint.IsPrimary)
            ?? record.Endpoints.FirstOrDefault();
        return string.IsNullOrWhiteSpace(primary?.Url) ? null : primary.Url;
    }

    private bool TryOpenStoresAt(
        CliInvocation invocation,
        string directory,
        out FileLocalSourceStore sources,
        out FileSourceSyncBaselineStore baselines,
        out int exitCode)
    {
        sources = new FileLocalSourceStore(directory);
        baselines = new FileSourceSyncBaselineStore(directory);
        if (sources.LoadError is not null)
        {
            // A damaged store is never silently treated as empty: an import would then overwrite
            // sources the user still has (the store itself refuses to write in this state).
            exitCode = Fail(invocation, CliExitCode.InputProblem, "store-unreadable", sources.LoadError);
            return false;
        }

        _ = baselines.PeerDeviceIds;
        return TryCheckLoadError(invocation, baselines.LoadError, baselines.LastQuarantinedPath, "the sync baseline could not be read", out exitCode);
    }

    private static bool TryParseTarget(string? target, out string host, out int port, out string? error)
    {
        host = "";
        port = 0;
        error = null;
        string text = (target ?? "").Trim();
        if (text.Length == 0)
        {
            error = "the --to value must look like 127.0.0.1:9000";
            return false;
        }

        int colon = text.LastIndexOf(':');
        if (colon <= 0 || colon == text.Length - 1)
        {
            error = "the --to value must look like host:port, got '" + text + "'";
            return false;
        }

        host = text[..colon].Trim();
        if (host.Length > 1 && host[0] == '[' && host[^1] == ']')
        {
            host = host[1..^1];
        }

        string portText = text[(colon + 1)..].Trim();
        if (!int.TryParse(portText, NumberStyles.None, CultureInfo.InvariantCulture, out port) || port is < 1 or > 65535)
        {
            error = "the port in --to must be 1..65535, got '" + portText + "'";
            return false;
        }

        return true;
    }

    private static (int Apply, int RequiresApproval, int Unsupported, int Ignored, int Withheld) TransferBuckets(SourceSyncApplyPreview preview)
    {
        int requiresApproval = 0;
        int unsupported = 0;
        int ignored = 0;
        foreach (SourceSyncApprovalItem item in preview.Items)
        {
            if (item.RequiresApproval)
            {
                requiresApproval++;
            }

            if (item.Action == SourceSyncAction.Unsupported)
            {
                unsupported++;
            }

            if (item.Action is SourceSyncAction.NoChange or SourceSyncAction.IgnoreStale)
            {
                ignored++;
            }
        }

        return (preview.Gate.AppliedCount, requiresApproval, unsupported, ignored, preview.Gate.WithheldCount);
    }

    private int WriteServeResult(CliInvocation invocation, int port, string address, TransferOutcome outcome)
    {
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "transfer");
                writer.WriteString("subcommand", "serve");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("address", address);
                writer.WriteNumber("port", port);
                writer.WriteBoolean("once", true);
                writer.WriteBoolean("applied", outcome.Report is not null);
                WriteTransferFieldsJson(writer, outcome);
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("served one connection on " + address + ":" + port.ToString(CultureInfo.InvariantCulture));
        WriteTransferText(outcome, invocation.Apply);
        return CliExitCode.Success;
    }

    private int WriteSendResult(CliInvocation invocation, string address, int port, SourceSyncEnvelope envelope, SourceSyncPeerRoundResult sent)
    {
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "transfer");
                writer.WriteString("subcommand", "send");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("deviceId", envelope.DeviceId);
                writer.WriteString("address", address);
                writer.WriteNumber("port", port);
                writer.WriteString("snapshotId", envelope.SnapshotId);
                writer.WriteNumber("sourceCount", envelope.Sources.Count);
                writer.WriteNumber("bytes", sent.Bytes);
                writer.WriteNumber("frames", sent.FrameCount);
                writer.WriteString("idempotency", "the receiver decides: this snapshot applies once, and a second delivery of it is a no-change round");
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("sent " + sent.FrameCount.ToString(CultureInfo.InvariantCulture) + " frames, " +
            sent.Bytes.ToString(CultureInfo.InvariantCulture) + " bytes to " + address + ":" + port.ToString(CultureInfo.InvariantCulture));
        _out.WriteLine("device: " + envelope.DeviceId + "  snapshot: " + envelope.SnapshotId +
            "  sources: " + envelope.Sources.Count.ToString(CultureInfo.InvariantCulture));
        _out.WriteLine("the receiver decides idempotency: this snapshot applies once; delivering it again is a no-change round.");
        return CliExitCode.Success;
    }

    private int WriteRoundtripResult(
        CliInvocation invocation,
        string rootA,
        string rootB,
        string deviceA,
        string deviceB,
        TransferOutcome first,
        TransferOutcome second)
    {
        if (invocation.Json)
        {
            _out.WriteLine(CliJson.Write(writer =>
            {
                writer.WriteStartObject();
                writer.WriteString("command", "transfer");
                writer.WriteString("subcommand", "roundtrip");
                writer.WriteString("root", invocation.Root);
                writer.WriteString("rootA", rootA);
                writer.WriteString("rootB", rootB);
                writer.WriteString("deviceA", deviceA);
                writer.WriteString("deviceB", deviceB);
                writer.WriteBoolean("applied", invocation.Apply);
                writer.WriteBoolean("secondRoundNoChange", second.NoChange);
                writer.WriteStartArray("rounds");
                foreach (TransferOutcome outcome in new[] { first, second })
                {
                    writer.WriteStartObject();
                    WriteTransferFieldsJson(writer, outcome);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                writer.WriteEndObject();
            }));
            return CliExitCode.Success;
        }

        _out.WriteLine("round 1 (" + deviceA + " -> " + deviceB + ")");
        WriteTransferText(first, invocation.Apply);
        _out.WriteLine("round 2 (" + deviceB + " -> " + deviceA + ")");
        WriteTransferText(second, invocation.Apply);
        _out.WriteLine("second round noChange: " + (second.NoChange ? "true" : "false"));
        return CliExitCode.Success;
    }

    private static void WriteTransferFieldsJson(Utf8JsonWriter writer, TransferOutcome outcome)
    {
        writer.WriteString("from", outcome.From);
        writer.WriteString("to", outcome.To);
        writer.WriteNumber("bytes", outcome.Bytes);
        writer.WriteNumber("frames", outcome.Frames);
        writer.WriteBoolean("noChange", outcome.NoChange);
        if (outcome.Preview is null)
        {
            writer.WriteString("reason", outcome.Reason ?? "");
            return;
        }

        (int apply, int requiresApproval, int unsupported, int ignored, int withheld) = TransferBuckets(outcome.Preview);
        writer.WriteStartObject("counts");
        writer.WriteNumber("apply", apply);
        writer.WriteNumber("requiresApproval", requiresApproval);
        writer.WriteNumber("unsupported", unsupported);
        writer.WriteNumber("ignored", ignored);
        writer.WriteNumber("withheld", withheld);
        writer.WriteEndObject();

        writer.WriteStartArray("items");
        foreach (SourceSyncApprovalItem item in outcome.Preview.Items)
        {
            writer.WriteStartObject();
            writer.WriteString("syncSourceId", item.SyncSourceId);
            writer.WriteString("action", item.Action.ToString());
            writer.WriteString("reason", item.Reason);
            writer.WriteBoolean("requiresApproval", item.RequiresApproval);
            writer.WriteBoolean("withheld", outcome.Preview.Gate.IsWithheld(item.SyncSourceId));
            writer.WriteEndObject();
        }

        writer.WriteEndArray();

        if (outcome.Report is not null)
        {
            writer.WriteStartObject("report");
            writer.WriteNumber("applied", outcome.Report.Applied);
            writer.WriteNumber("skipped", outcome.Report.Skipped);
            writer.WriteNumber("withheld", outcome.Report.Withheld);
            writer.WriteNumber("notApplied", outcome.Report.NotApplied);
            writer.WriteBoolean("baselineUpdated", outcome.Report.BaselineUpdated);
            writer.WriteNumber("baselineEntries", outcome.Report.BaselineEntryCount);
            writer.WriteString("summary", outcome.Report.Summary);
            writer.WriteString("baseline", outcome.Report.BaselineLabel);
            writer.WriteEndObject();
        }
    }

    private void WriteTransferText(TransferOutcome outcome, bool apply)
    {
        _out.WriteLine(outcome.From + " -> " + outcome.To + ": " + outcome.Frames.ToString(CultureInfo.InvariantCulture) +
            " frames, " + outcome.Bytes.ToString(CultureInfo.InvariantCulture) + " bytes");
        if (outcome.Preview is null)
        {
            _out.WriteLine("nothing arrived: " + (outcome.Reason ?? SourceSyncTransferReasons.IoFailed));
            return;
        }

        (int bucketsApply, int requiresApproval, int unsupported, int ignored, int withheld) = TransferBuckets(outcome.Preview);
        _out.WriteLine("preview: " + bucketsApply.ToString(CultureInfo.InvariantCulture) + " to apply, " +
            requiresApproval.ToString(CultureInfo.InvariantCulture) + " requiring approval, " +
            unsupported.ToString(CultureInfo.InvariantCulture) + " unsupported, " +
            ignored.ToString(CultureInfo.InvariantCulture) + " ignored, " +
            withheld.ToString(CultureInfo.InvariantCulture) + " withheld");
        foreach (SourceSyncApprovalItem item in outcome.Preview.Items)
        {
            _out.WriteLine("  " + item.SyncSourceId + "  " + item.Action.ToString().ToLowerInvariant() + "  " + item.Reason +
                (item.RequiresApproval ? "  (needs approval)" : "") +
                (outcome.Preview.Gate.IsWithheld(item.SyncSourceId) ? "  (withheld)" : ""));
        }

        if (outcome.Report is null)
        {
            _out.WriteLine(apply ? "nothing was written: the gate allowed nothing" : "nothing was written; pass --apply to import");
            return;
        }

        _out.WriteLine(outcome.Report.Summary);
        _out.WriteLine(outcome.Report.BaselineLabel);
    }

    // ---- shared helpers (W6-15) -------------------------------------------------------------

    private const string LoopbackPlaceholderUrl = "http://127.0.0.1:0/";

    private bool TryLoadBaselineStore(CliInvocation invocation, out FileSourceSyncBaselineStore store, out int exitCode)
    {
        store = new FileSourceSyncBaselineStore(invocation.Root);
        _ = store.PeerDeviceIds;
        return TryCheckLoadError(invocation, store.LoadError, store.LastQuarantinedPath, "the sync baseline could not be read", out exitCode);
    }

    private bool TryLoadPairedStore(CliInvocation invocation, out FilePairedDeviceStore store, out int exitCode)
    {
        store = new FilePairedDeviceStore(invocation.Root);
        _ = store.GetAll();
        return TryCheckLoadError(invocation, store.LoadError, store.LastQuarantinedPath, "the paired-device list could not be read", out exitCode);
    }

    private bool TryCheckLoadError(CliInvocation invocation, string? loadError, string? quarantinedPath, string message, out int exitCode)
    {
        exitCode = CliExitCode.Success;
        if (loadError is null)
        {
            return true;
        }

        exitCode = Fail(
            invocation,
            CliExitCode.InputProblem,
            loadError,
            message + (quarantinedPath is null ? "" : "; the damaged document was kept at " + quarantinedPath));
        return false;
    }

    private static string Prefix(string? value, int length) =>
        string.IsNullOrEmpty(value) ? "" : value.Length <= length ? value : value[..length];

    private static string Suffix(string? value, int length) =>
        string.IsNullOrEmpty(value) ? "" : value.Length <= length ? value : value[^length..];

    private static string FormatEpoch(long epochMs)
    {
        if (epochMs <= 0)
        {
            return "unknown";
        }

        try
        {
            return DateTimeOffset.FromUnixTimeMilliseconds(epochMs)
                .ToLocalTime()
                .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
        }
        catch (ArgumentOutOfRangeException)
        {
            return "unknown";
        }
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

using System.Security.Cryptography;
using System.Text.Json;
using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>
/// Orchestrates one device's side of the offline round trip across the three W6 layers.
/// </summary>
/// <remarks>
/// <para>
/// Export is <see cref="SourceSyncLocalBridge"/> (identity assignment plus contract shape) followed, when a
/// passphrase is supplied, by <see cref="EncryptedArchiveWriter"/>. Import is <see cref="EncryptedArchiveReader"/>
/// (bytes only, never the file system) followed by the same bridge, which reuses <see cref="SourceSyncPlanner"/>
/// for every write decision. <see cref="PlanRound"/> additionally runs <see cref="SyncRoundMerger"/> against a
/// shared baseline so a field-level conflict is spotted before it is applied, while the write path stays single:
/// <see cref="SourceSyncLocalBridge.Apply"/>.
/// </para>
/// <para>Invariants this type pins (each one has tests):</para>
/// <list type="bullet">
/// <item>An export carries no secrets unless it is explicitly asked to, and then only sealed blocks: capabilities
/// report canSendSecrets false by default, and asking for secrets without an <see cref="ISecretProtector"/> throws
/// <see cref="SecretError.SecretsRequireProtector"/> rather than silently stripping them.</item>
/// <item>A sealed credential is bound to the machine that produced it. Import therefore unseals with the local
/// protector and re-seals before anything is written; if that fails the whole round fails with zero writes, so a
/// half-imported device can never look logged in when it is not (ADR-S006).</item>
/// <item>An import that meets a conflict, an ambiguous legacy identity or an unsupported source type writes
/// nothing: the store keeps its records and its write counter, and the proposal is reported verbatim.</item>
/// <item>Importing the same envelope twice is idempotent: the second round reports every record as up to date and
/// writes nothing.</item>
/// <item>Archives stay in memory. This class never opens, reads or writes a file, so where a .mharchive lands
/// remains an App-layer decision.</item>
/// </list>
/// </remarks>
public sealed class SourceSyncSession
{
    private readonly Func<DateTimeOffset> _clock;

    /// <param name="store">Local source store; the only thing this class ever writes to.</param>
    /// <param name="deviceId">Wire identity of this device, stamped on every envelope it exports.</param>
    /// <param name="clock">Injected so tests can pin createdAtEpochMs; the default reads the wall clock once per export.</param>
    public SourceSyncSession(ILocalSourceStore store, string deviceId, Func<DateTimeOffset>? clock = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        Store = store;
        DeviceId = deviceId.Trim();
        Bridge = new SourceSyncLocalBridge(store, DeviceId);
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    /// <summary>The local source store this session reads and writes.</summary>
    public ILocalSourceStore Store { get; }

    /// <summary>This device's wire identity.</summary>
    public string DeviceId { get; }

    /// <summary>The bridge underneath, exposed for callers that need the lower-level apply result.</summary>
    public SourceSyncLocalBridge Bridge { get; }

    /// <summary>Builds this device's envelope, plain or encrypted.
    /// </summary>
    /// <param name="includeSecrets">
    /// Opt in to credential migration. The plaintext comes from <paramref name="secrets"/> and leaves this
    /// process only as a sealed <see cref="SecretEnvelope"/>; <paramref name="protector"/> is then mandatory.
    /// </param>
    /// <param name="passphrase">When given, the envelope is encrypted and returned as archive bytes instead of JSON.</param>
    /// <param name="deletions">Tombstones to advertise; tracking when a source is deleted belongs to the caller.</param>
    /// <param name="protector">Seals each plaintext credential for this machine; required when <paramref name="includeSecrets"/> is true.</param>
    /// <param name="secrets">Plaintext credentials keyed by sync source id; used only to seal, never stored here.</param>
    /// <exception cref="SyncContractException">
    /// When secrets are requested without a protector, without any plaintext, or together with a passphrase (the
    /// archive layer never carries credentials).
    /// </exception>
    public ExportSession Export(
        bool includeSecrets = false,
        string? passphrase = null,
        IReadOnlyList<Tombstone>? deletions = null,
        ISecretProtector? protector = null,
        IReadOnlyDictionary<string, byte[]>? secrets = null)
    {
        if (!includeSecrets && secrets is { Count: > 0 })
        {
            throw new SyncContractException(
                SecretError.SecretsNotRequested,
                "Secrets were supplied to an export that did not ask for them; pass includeSecrets: true or drop them.");
        }

        if (includeSecrets)
        {
            if (protector is null)
            {
                throw new SyncContractException(
                    SecretError.SecretsRequireProtector,
                    "Exporting credentials requires an ISecretProtector; plaintext never goes on the wire.");
            }

            if (passphrase is not null)
            {
                // The archive layer (W6-5) refuses envelopes that claim to carry secrets, on purpose: a file that
                // travels by clipboard must not become a credential carrier.
                throw new SyncContractException(
                    SourceSyncSessionReasons.SecretsNotSupported,
                    "The offline archive layer refuses envelopes that carry secrets; export credentials in the clear contract instead.");
            }

            if (secrets is null || secrets.Count == 0)
            {
                throw new SyncContractException(
                    SecretError.SecretsMissing,
                    "includeSecrets was requested but no plaintext was supplied for any source.");
            }
        }

        var warnings = new List<string>();
        foreach (SourceIdAssignment assignment in Bridge.EnsureSourceIds())
        {
            if (!assignment.Assigned &&
                !string.Equals(assignment.Reason, SourceIdAssignmentReasons.AlreadyHasId, StringComparison.Ordinal))
            {
                warnings.Add(
                    SourceSyncSessionReasons.SkippedSourcePrefix + ":" + assignment.ServerId + ":" + assignment.Reason);
            }
        }

        // The session clock, not the bridge's wall clock, decides createdAtEpochMs: that keeps exports pinnable in
        // tests and makes the timestamp of the envelope and of the archive header the same instant.
        DateTimeOffset now = _clock();
        SourceSyncEnvelope envelope = Bridge.BuildEnvelope(includeSecrets: includeSecrets, deletions: deletions) with
        {
            CreatedAtEpochMs = now.ToUnixTimeMilliseconds(),
        };
        if (includeSecrets)
        {
            envelope = AttachSecrets(envelope, protector!, secrets!, warnings);
        }

        if (envelope.Sources.Count == 0 && envelope.Deletions.Count == 0)
        {
            warnings.Add(SourceSyncSessionReasons.EmptyEnvelope);
        }

        if (passphrase is null)
        {
            return new ExportSession
            {
                Envelope = envelope,
                EnvelopeJson = SourceSyncCodec.Serialize(envelope),
                Warnings = warnings,
            };
        }

        byte[] archive = EncryptedArchiveWriter.Write(
            envelope,
            passphrase,
            ArchiveKdf.DefaultIterations,
            now);

        return new ExportSession { Envelope = envelope, Archive = archive, Warnings = warnings };
    }

    /// <summary>Decrypts an archive and imports it; bytes in, bytes out, no file system involved.</summary>
    /// <param name="archive">Archive produced by <see cref="Export"/> on the peer.</param>
    /// <param name="passphrase">The passphrase the peer used; a wrong one fails instead of writing.</param>
    /// <param name="baseline">Last state both sides agreed on; when given, field conflicts are withheld for approval.</param>
    /// <param name="protector">Required only when the payload claims to carry credentials; archives never do.</param>
    public ImportResult Import(
        byte[] archive,
        string? passphrase,
        SourceSyncBaseline? baseline = null,
        ISecretProtector? protector = null)
    {
        ArgumentNullException.ThrowIfNull(archive);

        if (!EncryptedArchiveReader.TryRead(archive, passphrase, out SourceSyncEnvelope? envelope, out string? reason) ||
            envelope is null)
        {
            return ImportResult.Failed(reason ?? ArchiveError.MalformedPayload);
        }

        return ImportPlain(envelope, baseline, protector);
    }

    /// <summary>Imports an envelope that already arrived in the clear (same device, direct transport, tests).</summary>
    /// <param name="remote">Inbound envelope.</param>
    /// <param name="baseline">Last state both sides agreed on; when given, field conflicts are withheld for approval.</param>
    /// <param name="protector">The local protector; a sealed block from another machine cannot be unsealed without it.</param>
    public ImportResult ImportPlain(
        SourceSyncEnvelope remote,
        SourceSyncBaseline? baseline = null,
        ISecretProtector? protector = null)
    {
        ArgumentNullException.ThrowIfNull(remote);

        // Credentials are re-sealed before anything is planned or written. A blob from another machine cannot be
        // opened here, and a half-imported round is worse than a refused one, so this either fully succeeds or the
        // whole import fails with zero writes.
        Dictionary<string, SecretEnvelope>? resealedSecrets;
        ImportResult? secretsFailure = TryResealSecrets(remote, protector, out resealedSecrets);
        if (secretsFailure is not null)
        {
            return secretsFailure;
        }

        // Keys the merger flags for approval are withheld before the planner runs, so the write path stays single:
        // everything that is allowed to land goes through the bridge, and everything else never reaches it.
        var withheld = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (baseline is not null && !baseline.IsEmpty)
        {
            foreach (SyncRoundEntry entry in PlanRound(remote, baseline).RequiresApproval)
            {
                string? key = entry.SyncSourceId?.Trim();
                if (!string.IsNullOrEmpty(key))
                {
                    withheld[key] = entry.Reason;
                }
            }
        }

        SourceSyncEnvelope target = withheld.Count == 0 ? remote : WithoutKeys(remote, withheld.Keys);
        SourceSyncApplyResult result = Bridge.Apply(target, resealedSecrets);

        var appliedEntries = new List<SourceSyncImportEntry>();
        var skippedEntries = new List<SourceSyncImportEntry>();
        var approvalEntries = new List<SourceSyncImportEntry>();

        foreach (SourceSyncApplyOutcome outcome in result.Outcomes)
        {
            SourceSyncImportEntry entry = ToEntry(outcome.Candidate);
            if (outcome.Applied)
            {
                appliedEntries.Add(entry);
                continue;
            }

            if (withheld.TryGetValue(entry.SyncSourceId, out string? withheldReason))
            {
                approvalEntries.Add(entry with { Reason = withheldReason, RequiresUserApproval = true });
                withheld.Remove(entry.SyncSourceId);
                continue;
            }

            if (entry.RequiresUserApproval)
            {
                approvalEntries.Add(entry);
            }
            else
            {
                skippedEntries.Add(entry);
            }
        }

        // A withheld key was removed from the inbound envelope, so the planner produced no row for it at all.
        foreach ((string key, string reason) in withheld)
        {
            approvalEntries.Add(new SourceSyncImportEntry
            {
                SyncSourceId = key,
                Action = SourceSyncAction.Conflict,
                Reason = reason,
                RequiresUserApproval = true,
            });
        }

        return new ImportResult
        {
            Envelope = remote,
            Applied = appliedEntries,
            Skipped = skippedEntries,
            RequiresApproval = approvalEntries,
        };
    }

    /// <summary>Read-only preview of a bidirectional round against a shared baseline.</summary>
    /// <remarks>
    /// Writes nothing about sources; only identities that are still missing get assigned, which
    /// <see cref="SourceSyncLocalBridge.BuildEnvelope"/> does on any export as well.
    /// </remarks>
    public SyncRoundResult PlanRound(SourceSyncEnvelope remote, SourceSyncBaseline baseline)
    {
        ArgumentNullException.ThrowIfNull(remote);
        ArgumentNullException.ThrowIfNull(baseline);

        SourceSyncEnvelope local = Bridge.BuildEnvelope();
        SourceSyncDigest localDigest = SourceSyncDigest.FromEnvelope(local, baseline);
        SourceSyncDigest remoteDigest = SourceSyncDigest.FromEnvelope(remote, baseline);
        return SyncRoundMerger.Merge(localDigest, remoteDigest, baseline);
    }

    /// <summary>
    /// Turns every inbound sealed block into one sealed for this machine, or explains why the round must fail.
    /// </summary>
    /// <remarks>
    /// Returns <c>null</c> when nothing carried a credential, or when all of them were re-sealed; the out
    /// dictionary then holds the fresh blocks keyed by sync source id. Plaintext exists only for the duration of
    /// this method and is zeroed on the way out — including on every failure path.
    /// </remarks>
    private static ImportResult? TryResealSecrets(
        SourceSyncEnvelope remote,
        ISecretProtector? protector,
        out Dictionary<string, SecretEnvelope>? resealed)
    {
        resealed = null;
        var carriers = new List<JsonElement>();
        var carrierIds = new List<string>();
        foreach (SourceRecord record in remote.Sources)
        {
            if (record.Secrets is { } block && block.ValueKind != JsonValueKind.Null)
            {
                carriers.Add(block);
                carrierIds.Add(record.SyncSourceId?.Trim() ?? "");
            }
        }

        if (carriers.Count == 0)
        {
            return null;
        }

        if (protector is null)
        {
            return ImportResult.Failed(SecretError.SecretsRequireProtector);
        }

        var plaintexts = new List<byte[]>(carriers.Count);
        try
        {
            var blocks = new List<SecretEnvelope>(carriers.Count);
            for (int index = 0; index < carriers.Count; index++)
            {
                if (!SecretEnvelopeCodec.TryParse(carriers[index], out SecretEnvelope? envelope, out string? reason) ||
                    envelope is null)
                {
                    return ImportResult.Failed(reason ?? SecretError.MalformedJson);
                }

                if (carrierIds[index].Length == 0)
                {
                    return ImportResult.Failed(SecretError.MalformedJson);
                }

                byte[] plaintext;
                try
                {
                    plaintext = protector.Unprotect(envelope);
                }
                catch (SecretException error)
                {
                    // A foreign machine's blob lands here. Refusing the whole round is the point: the source row
                    // must not be imported while its credential silently stays behind.
                    return ImportResult.Failed(error.Reason);
                }

                plaintexts.Add(plaintext);
                blocks.Add(protector.Protect(plaintext));
            }

            var fresh = new Dictionary<string, SecretEnvelope>(StringComparer.OrdinalIgnoreCase);
            for (int index = 0; index < carrierIds.Count; index++)
            {
                fresh[carrierIds[index]] = blocks[index];
            }

            resealed = fresh;
            return null;
        }
        finally
        {
            foreach (byte[] plaintext in plaintexts)
            {
                CryptographicOperations.ZeroMemory(plaintext);
            }
        }
    }

    /// <summary>Seals the supplied plaintext onto the matching records and reports ids that matched nothing.</summary>
    private static SourceSyncEnvelope AttachSecrets(
        SourceSyncEnvelope envelope,
        ISecretProtector protector,
        IReadOnlyDictionary<string, byte[]> secrets,
        List<string> warnings)
    {
        var wanted = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        foreach ((string key, byte[] plaintext) in secrets)
        {
            if (!string.IsNullOrWhiteSpace(key) && plaintext is { Length: > 0 })
            {
                wanted[key.Trim()] = plaintext;
            }
        }

        var matched = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var sources = new List<SourceRecord>(envelope.Sources.Count);
        foreach (SourceRecord record in envelope.Sources)
        {
            string key = record.SyncSourceId?.Trim() ?? "";
            if (key.Length > 0 && wanted.TryGetValue(key, out byte[]? plaintext))
            {
                SecretEnvelope sealedEnvelope = protector.Protect(plaintext);
                sources.Add(record with { Secrets = SecretEnvelopeCodec.SerializeToElement(sealedEnvelope) });
                matched.Add(key);
            }
            else
            {
                sources.Add(record);
            }
        }

        foreach (string key in wanted.Keys)
        {
            if (!matched.Contains(key))
            {
                warnings.Add(SourceSyncSessionReasons.SkippedSecretPrefix + ":" + key + ":no-such-source");
            }
        }

        return envelope with { Sources = sources };
    }

    private static SourceSyncImportEntry ToEntry(SourceSyncCandidate candidate) => new()
    {
        SyncSourceId = candidate.SyncSourceId?.Trim() ?? "",
        Action = candidate.Action,
        Reason = candidate.Reason,
        ServerId = candidate.ServerId,
        DisplayName = candidate.DisplayName,
        RequiresUserApproval = candidate.RequiresUserApproval,
    };

    private static SourceSyncEnvelope WithoutKeys(SourceSyncEnvelope envelope, IEnumerable<string> keys)
    {
        var excluded = new HashSet<string>(keys, StringComparer.OrdinalIgnoreCase);
        return envelope with
        {
            Sources = envelope.Sources.Where(record => !excluded.Contains(record.SyncSourceId.Trim())).ToList(),
            Deletions = envelope.Deletions.Where(tombstone => !excluded.Contains(tombstone.SyncSourceId.Trim())).ToList(),
        };
    }
}
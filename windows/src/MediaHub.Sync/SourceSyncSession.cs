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
/// <item>An export never carries secrets: capabilities report canSendSecrets false and no record has a secrets
/// block. Asking for secrets throws <see cref="SyncContractException"/> with
/// <see cref="SourceSyncSessionReasons.SecretsNotSupported"/> rather than silently stripping them.</item>
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
    /// <param name="includeSecrets">Must be false: credential migration is a later stage, so true is refused loudly.</param>
    /// <param name="passphrase">When given, the envelope is encrypted and returned as archive bytes instead of JSON.</param>
    /// <param name="deletions">Tombstones to advertise; tracking when a source is deleted belongs to the caller.</param>
    /// <exception cref="SyncContractException">When <paramref name="includeSecrets"/> is true.</exception>
    public ExportSession Export(
        bool includeSecrets = false,
        string? passphrase = null,
        IReadOnlyList<Tombstone>? deletions = null)
    {
        if (includeSecrets)
        {
            throw new SyncContractException(
                SourceSyncSessionReasons.SecretsNotSupported,
                "Credential migration is not part of the offline archive layer; export the envelope without secrets.");
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
        SourceSyncEnvelope envelope = Bridge.BuildEnvelope(includeSecrets: false, deletions: deletions) with
        {
            CreatedAtEpochMs = now.ToUnixTimeMilliseconds(),
        };
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
    public ImportResult Import(byte[] archive, string? passphrase, SourceSyncBaseline? baseline = null)
    {
        ArgumentNullException.ThrowIfNull(archive);

        if (!EncryptedArchiveReader.TryRead(archive, passphrase, out SourceSyncEnvelope? envelope, out string? reason) ||
            envelope is null)
        {
            return ImportResult.Failed(reason ?? ArchiveError.MalformedPayload);
        }

        return ImportPlain(envelope, baseline);
    }

    /// <summary>Imports an envelope that already arrived in the clear (same device, direct transport, tests).</summary>
    /// <param name="remote">Inbound envelope.</param>
    /// <param name="baseline">Last state both sides agreed on; when given, field conflicts are withheld for approval.</param>
    public ImportResult ImportPlain(SourceSyncEnvelope remote, SourceSyncBaseline? baseline = null)
    {
        ArgumentNullException.ThrowIfNull(remote);

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
        SourceSyncApplyResult result = Bridge.Apply(target);

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
using System.Security.Cryptography;
using System.Text;
using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>
/// Connects this device's own source list to the cross-device sync contract: assigns stable sync identities,
/// builds the outbound envelope, and applies an inbound one without ever deciding a conflict on its own.
/// </summary>
/// <remarks>
/// <para>
/// Scope: permanent, user-managed sources only. This is not the playback queue — <c>PlaybackQueue</c> is a
/// transient in-memory list that exists for one session and is never persisted, while these sources live on
/// disk, survive restarts and are only changed by an explicit user action or an approved sync. Nothing here
/// touches the queue.
/// </para>
/// <para>
/// Three rules shape the implementation:
/// identity is derived, not invented (no address, no identity); the outbound envelope carries no secrets unless
/// the caller asks for them; and <see cref="Apply"/> only writes the decisions the planner already proved safe —
/// conflicts, ambiguous matches, unsupported types and deletions stay proposals for the user.
/// </para>
/// </remarks>
public sealed class SourceSyncLocalBridge
{
    private const int DerivedIdHashBytes = 8;
    private const string DerivedIdPrefix = "local-";

    /// <summary>Creates a bridge over one store for one device.</summary>
    /// <param name="store">Where this device's sources live.</param>
    /// <param name="deviceId">Stable id of this device; carried in every envelope.</param>
    public SourceSyncLocalBridge(ILocalSourceStore store, string deviceId)
    {
        Store = store ?? throw new ArgumentNullException(nameof(store));
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id must not be blank.", nameof(deviceId));
        }

        DeviceId = deviceId.Trim();
    }

    /// <summary>The store this bridge reads and writes.</summary>
    public ILocalSourceStore Store { get; }

    /// <summary>Stable id of this device as it appears in envelopes.</summary>
    public string DeviceId { get; }

    /// <summary>
    /// Derives the sync identity a source would get from its type, normalised address and account scope, or
    /// <c>null</c> when it cannot be derived.
    /// </summary>
    /// <remarks>
    /// Deterministic on purpose: the same source yields the same identity on every device, so two devices that
    /// configured the same server before W6 end up with one identity instead of two. Display name is not part of
    /// the input — renaming a source must not create a second identity — and the account scope is, because the
    /// same server under two accounts is two sources (D5).
    /// </remarks>
    public static string? DeriveSyncSourceId(LocalSourceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        string? wire = ResolveWire(record);
        string? normalizedUrl = SyncUrlNormalizer.Normalize(record.Url);
        return wire is null || normalizedUrl is null ? null : DeriveFrom(wire, normalizedUrl, record.AccountScope);
    }

    /// <summary>
    /// Gives every local source without an identity a stable one, and reports what it did with each source.
    /// </summary>
    /// <remarks>
    /// Idempotent: existing identities are never rewritten, records keep their order, and a second call writes
    /// nothing. A source whose derived identity is already held by another record is left alone and reported as
    /// <see cref="SourceIdAssignmentReasons.DuplicateIdentity"/> — merging two sources silently would be worse
    /// than asking the user.
    /// </remarks>
    public IReadOnlyList<SourceIdAssignment> EnsureSourceIds()
    {
        IReadOnlyList<LocalSourceRecord> records = Store.GetAll();
        var taken = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (LocalSourceRecord record in records)
        {
            if (Trimmed(record.SyncSourceId) is { } existing)
            {
                taken.Add(existing);
            }
        }

        var assignments = new List<SourceIdAssignment>(records.Count);
        foreach (LocalSourceRecord record in records)
        {
            string? existing = Trimmed(record.SyncSourceId);
            if (existing is not null)
            {
                assignments.Add(new SourceIdAssignment
                {
                    ServerId = record.ServerId,
                    SyncSourceId = existing,
                    Assigned = false,
                    Reason = SourceIdAssignmentReasons.AlreadyHasId,
                });
                continue;
            }

            string? wire = ResolveWire(record);
            if (wire is null)
            {
                assignments.Add(Skipped(record, SourceIdAssignmentReasons.UnknownSourceType));
                continue;
            }

            string? normalizedUrl = SyncUrlNormalizer.Normalize(record.Url);
            if (normalizedUrl is null)
            {
                assignments.Add(Skipped(record, SourceIdAssignmentReasons.NoUsableEndpoint));
                continue;
            }

            string derived = DeriveFrom(wire, normalizedUrl, record.AccountScope);
            if (taken.Contains(derived))
            {
                assignments.Add(Skipped(record, SourceIdAssignmentReasons.DuplicateIdentity));
                continue;
            }

            Store.Save(record with { SyncSourceId = derived });
            taken.Add(derived);
            assignments.Add(new SourceIdAssignment
            {
                ServerId = record.ServerId,
                SyncSourceId = derived,
                Assigned = true,
                Reason = SourceIdAssignmentReasons.Assigned,
            });
        }

        return assignments;
    }

    /// <summary>Builds this device's outbound envelope.</summary>
    /// <param name="includeSecrets">
    /// Whether the envelope advertises that it can carry credentials. Even when <c>true</c> no secret is written
    /// here: this layer has none, and credential migration is NOT_IMPLEMENTED (see W6-1 implementation notes).
    /// </param>
    /// <param name="deletions">Tombstones to send. Tracking deletions is W6-2b's job; the bridge only carries them.</param>
    /// <remarks>
    /// Records without a usable address are omitted: the contract requires an identity, and an identity without
    /// an address would be a source the peer cannot dial. <see cref="EnsureSourceIds"/> reports them so the UI can
    /// explain why a source does not travel.
    /// </remarks>
    public SourceSyncEnvelope BuildEnvelope(bool includeSecrets = false, IReadOnlyList<Tombstone>? deletions = null)
    {
        EnsureSourceIds();

        var sources = new List<SourceRecord>();
        var sourceTypes = new List<string>();
        foreach (LocalSourceRecord local in Store.GetAll())
        {
            string? wire = ResolveWire(local);
            string? syncSourceId = Trimmed(local.SyncSourceId);
            if (wire is null || syncSourceId is null)
            {
                continue;
            }

            var endpoints = new List<SourceEndpoint>();
            if (SyncUrlNormalizer.Normalize(local.Url) is { } normalizedUrl)
            {
                endpoints.Add(new SourceEndpoint
                {
                    Url = normalizedUrl,
                    IsPrimary = true,
                    Enabled = true,
                    SortOrder = 0,
                });
            }

            sources.Add(new SourceRecord
            {
                SyncSourceId = syncSourceId,
                SourceType = SyncSourceTypeValue.Parse(wire),
                DisplayName = local.DisplayName ?? "",
                AccountScope = Trimmed(local.AccountScope),
                Endpoints = endpoints,
                RecordRevision = local.RecordRevision,
                FieldVersion = 0,
                Secrets = null,
            });

            if (!sourceTypes.Contains(wire, StringComparer.OrdinalIgnoreCase))
            {
                sourceTypes.Add(wire);
            }
        }

        return new SourceSyncEnvelope
        {
            SchemaVersion = SourceSyncEnvelope.CurrentSchemaVersion,
            MinimumReaderVersion = 1,
            DeviceId = DeviceId,
            SnapshotId = Guid.NewGuid().ToString("N"),
            CreatedAtEpochMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Capabilities = new SyncCapabilities
            {
                CanSendSecrets = includeSecrets,
                SourceTypes = sourceTypes,
            },
            Sources = sources,
            Deletions = deletions is null ? [] : deletions.ToList(),
        };
    }

    /// <summary>
    /// Plans an incoming envelope against this device and writes only the decisions the planner proved safe.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Applied: <see cref="SourceSyncAction.Update"/> and unattended <see cref="SourceSyncAction.Create"/>.
    /// Not applied, reported verbatim: conflicts, ambiguous legacy matches, adoptions, unsupported source types
    /// and tombstones — every one of them needs a user decision, so writing them here would be the silent
    /// overwrite D5 forbids.
    /// </para>
    /// <para>
    /// Applying the same envelope twice is a no-op: the fields written are exactly the fields the planner
    /// compares, so the second pass plans <see cref="SourceSyncAction.NoChange"/>.
    /// </para>
    /// </remarks>
    /// <param name="remote">Inbound envelope.</param>
    /// <param name="secrets">
    /// Credentials already re-sealed on this machine, keyed by sync source id. The bridge never sees plaintext
    /// and never unseals anything: the session does that before planning, so a credential that cannot be
    /// re-sealed stops the whole round before a single write happens.
    /// </param>
    public SourceSyncApplyResult Apply(
        SourceSyncEnvelope remote,
        IReadOnlyDictionary<string, SecretEnvelope>? secrets = null)
    {
        ArgumentNullException.ThrowIfNull(remote);

        var sealedSecrets = new Dictionary<string, SecretEnvelope>(StringComparer.OrdinalIgnoreCase);
        if (secrets is not null)
        {
            foreach ((string key, SecretEnvelope? value) in secrets)
            {
                if (!string.IsNullOrWhiteSpace(key) && value is not null)
                {
                    sealedSecrets[key.Trim()] = value;
                }
            }
        }

        SourceSyncPlan plan = SourceSyncPlanner.Plan(remote, Store.GetAll());
        var outcomes = new List<SourceSyncApplyOutcome>(plan.Items.Count);
        foreach (SourceSyncCandidate candidate in plan.Items)
        {
            bool applied = candidate.Action switch
            {
                SourceSyncAction.Update => ApplyUpdate(remote, candidate, sealedSecrets),
                SourceSyncAction.Create => ApplyCreate(remote, candidate, sealedSecrets),
                _ => false,
            };

            outcomes.Add(new SourceSyncApplyOutcome { Candidate = candidate, Applied = applied });
        }

        return new SourceSyncApplyResult { Plan = plan, Outcomes = outcomes };
    }

    private bool ApplyUpdate(
        SourceSyncEnvelope remote,
        SourceSyncCandidate candidate,
        IReadOnlyDictionary<string, SecretEnvelope> sealedSecrets)
    {
        SourceRecord? incoming = FindIncoming(remote, candidate.SyncSourceId);
        LocalSourceRecord? local = FindLocal(candidate.ServerId);
        if (incoming is null || local is null)
        {
            return false;
        }

        if (ResolveLocalType(incoming) is not { } type)
        {
            return false;
        }

        SourceEndpoint? endpoint = SelectPrimaryEndpoint(incoming);
        if (endpoint is null || string.IsNullOrWhiteSpace(endpoint.Url))
        {
            // Nothing to point at. Writing would either wipe the user's address or leave a record the planner
            // keeps re-proposing, so the proposal stays a proposal.
            return false;
        }

        Store.Save(local with
        {
            SyncSourceId = incoming.SyncSourceId.Trim(),
            Type = type,
            SourceTypeRaw = null,
            Url = StoredUrl(endpoint),
            AccountScope = Trimmed(incoming.AccountScope),
            DisplayName = incoming.DisplayName ?? "",
            RecordRevision = incoming.RecordRevision,
            // An import that carries no credential for this source keeps the one already stored; silently
            // dropping a working credential because a peer omitted it would be a downgrade, not a merge.
            Secret = LookupSecret(sealedSecrets, incoming.SyncSourceId) ?? local.Secret,
        });
        return true;
    }

    private bool ApplyCreate(
        SourceSyncEnvelope remote,
        SourceSyncCandidate candidate,
        IReadOnlyDictionary<string, SecretEnvelope> sealedSecrets)
    {
        if (candidate.RequiresUserApproval)
        {
            return false;
        }

        SourceRecord? incoming = FindIncoming(remote, candidate.SyncSourceId);
        if (incoming is null)
        {
            return false;
        }

        if (ResolveLocalType(incoming) is not { } type)
        {
            return false;
        }

        SourceEndpoint? endpoint = SelectPrimaryEndpoint(incoming);
        if (endpoint is null || string.IsNullOrWhiteSpace(endpoint.Url))
        {
            return false;
        }

        string serverId = incoming.SyncSourceId.Trim();
        if (FindLocal(serverId) is not null)
        {
            // The local id space already owns this key; upserting would overwrite an unrelated source.
            return false;
        }

        Store.Save(new LocalSourceRecord
        {
            ServerId = serverId,
            SyncSourceId = serverId,
            Type = type,
            Url = StoredUrl(endpoint),
            AccountScope = Trimmed(incoming.AccountScope),
            DisplayName = incoming.DisplayName ?? "",
            RecordRevision = incoming.RecordRevision,
            Secret = LookupSecret(sealedSecrets, serverId),
        });
        return true;
    }

    private static SecretEnvelope? LookupSecret(
        IReadOnlyDictionary<string, SecretEnvelope> sealedSecrets,
        string? syncSourceId)
    {
        string? key = Trimmed(syncSourceId);
        return key is not null && sealedSecrets.TryGetValue(key, out SecretEnvelope? secret) ? secret : null;
    }

    private static string DeriveFrom(string wire, string normalizedUrl, string? accountScope)
    {
        string payload = string.Concat(
            wire.ToLowerInvariant(),
            "\n",
            normalizedUrl,
            "\n",
            Trimmed(accountScope)?.ToLowerInvariant() ?? "");
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(payload));
        return DerivedIdPrefix + Convert.ToHexString(hash.AsSpan(0, DerivedIdHashBytes)).ToLowerInvariant();
    }

    private static ServerType? ResolveLocalType(SourceRecord incoming)
    {
        ServerType type = WindowsSourceTypeMap.ToServerType(incoming.SourceType.Wire);
        return type == ServerType.Unknown ? null : type;
    }

    private static string StoredUrl(SourceEndpoint endpoint)
        => SyncUrlNormalizer.Normalize(endpoint.Url) ?? endpoint.Url.Trim();

    // Shared with the planner and the plan summary: one rule for "which address wins", so a record can never be
    // planned against one endpoint and written with another.
    private static SourceEndpoint? SelectPrimaryEndpoint(SourceRecord record)
        => SourceSyncEndpointPolicy.SelectPrimary(record);

    private static SourceRecord? FindIncoming(SourceSyncEnvelope remote, string? syncSourceId)
    {
        string? wanted = Trimmed(syncSourceId);
        if (wanted is null)
        {
            return null;
        }

        foreach (SourceRecord source in remote.Sources)
        {
            if (string.Equals(source.SyncSourceId?.Trim(), wanted, StringComparison.OrdinalIgnoreCase))
            {
                return source;
            }
        }

        return null;
    }

    private LocalSourceRecord? FindLocal(string? serverId)
    {
        if (serverId is null)
        {
            return null;
        }

        foreach (LocalSourceRecord record in Store.GetAll())
        {
            if (string.Equals(record.ServerId, serverId, StringComparison.Ordinal))
            {
                return record;
            }
        }

        return null;
    }

    private static string? ResolveWire(LocalSourceRecord record)
    {
        string? mapped = WindowsSourceTypeMap.ToWire(record.Type);
        if (mapped is not null)
        {
            return mapped;
        }

        if (record.Type == ServerType.Unknown && !string.IsNullOrWhiteSpace(record.SourceTypeRaw))
        {
            // Verbatim, not lowercased: an unknown value is evidence, and rewriting it would make this device
            // the author of a type it does not understand.
            return record.SourceTypeRaw.Trim();
        }

        return null;
    }

    private static SourceIdAssignment Skipped(LocalSourceRecord record, string reason) => new()
    {
        ServerId = record.ServerId,
        SyncSourceId = null,
        Assigned = false,
        Reason = reason,
    };

    private static string? Trimmed(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

using System.Text.Json;

namespace MediaHub.Sync;

/// <summary>One configured source as it travels between devices (ADR-S001 D2).</summary>
/// <remarks>
/// <para>
/// <see cref="SyncSourceId"/> is the whole point: a UUID that is stable across devices and restarts, unlike the
/// Windows <c>ServerId</c> which is derived from type and address and therefore changes when the address does
/// (audit §5.3). Matching by <c>syncSourceId</c> is idempotent; matching by anything else is a suggestion that
/// needs user approval.
/// </para>
/// <para>
/// <see cref="Endpoints"/> may hold several addresses because Android does. Windows stores one, so the first
/// version imports the primary endpoint and lists the rest as "not imported" rather than dropping them
/// silently (audit §5.4) — that decision belongs to the import UI, not to this record.
/// </para>
/// </remarks>
public sealed record SourceRecord
{
    /// <summary>Cross-device identity, UUID in the "N" format. Records written before W6 may have none.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>The wire source type, preserved verbatim even when this build does not know it.</summary>
    public SyncSourceTypeValue SourceType { get; init; } = SyncSourceTypeValue.Parse(null);

    /// <summary>Name as shown to the user on the sending device. A snapshot, not an identity.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>
    /// Account the source is logged in as (user name or account domain). Part of the identity: the same
    /// server under two accounts is two sources and must never be merged (D5).
    /// </summary>
    public string? AccountScope { get; init; }

    /// <summary>Addresses of the source. Empty is legal but means it cannot be matched by address.</summary>
    public IReadOnlyList<SourceEndpoint> Endpoints { get; init; } = [];

    /// <summary>
    /// Record-level version used to decide who wins. Monotonic per record; never compared with a wall clock
    /// from another device (D5).
    /// </summary>
    public long RecordRevision { get; init; }

    /// <summary>Version of the field set this record was written with. Lets a later schema add fields per record.</summary>
    public int FieldVersion { get; init; }

    /// <summary>
    /// Opaque secret block, only present when the user explicitly chose to migrate credentials over the audited
    /// channel. This layer never reads, validates, re-encrypts, logs or displays it; credential migration is
    /// NOT_IMPLEMENTED here (see docs/cross-device/W6-1-IMPLEMENTATION.md).
    /// </summary>
    public JsonElement? Secrets { get; init; }
}

/// <summary>One address of a source (ADR-S001 D2).</summary>
public sealed record SourceEndpoint
{
    /// <summary>Address as stored by the sender; compare only through <see cref="SyncUrlNormalizer"/>.</summary>
    public string Url { get; init; } = "";

    /// <summary>Whether the sender considers this the address to dial first.</summary>
    public bool IsPrimary { get; init; }

    /// <summary>Whether the sender currently has this endpoint enabled.</summary>
    public bool Enabled { get; init; } = true;

    /// <summary>Sender-side preference order among endpoints; smaller is preferred.</summary>
    public int SortOrder { get; init; }
}

namespace MediaHub.Sync;

/// <summary>
/// The document one device sends to another (ADR-S001 D2, schema v1).
/// </summary>
/// <remarks>
/// <para>
/// Deliberately not the Android <c>.mhbackup</c> format: that one is a single-device disaster restore with a
/// one-shot <c>backupId</c>, this one is a two-way, incremental exchange whose identity is
/// <c>syncSourceId</c> and whose changes are expressed as records plus tombstones. Sharing a format would put
/// "restore" and "sync" in one version number, and any future change to either would break the other.
/// </para>
/// <para>
/// The security rules of the backup format are inherited rather than reinvented: the document carries no
/// passwords or tokens in clear text, addresses are normalised before they are written
/// (<see cref="SyncUrlNormalizer"/>), and <c>secrets</c> is only ever an opaque block that a user explicitly
/// chose to migrate.
/// </para>
/// <para>
/// <c>createdAtEpochMs</c> is informational. Nothing in the contract — writer, reader, or
/// <see cref="SourceSyncPlanner"/> — orders records by it: clocks on two devices disagree, so ordering is
/// done with <see cref="SourceRecord.RecordRevision"/> and explicit conflicts (D5).
/// </para>
/// </remarks>
public sealed record SourceSyncEnvelope
{
    /// <summary>Schema version this build writes and the only version it reads.</summary>
    public const int CurrentSchemaVersion = 1;

    /// <summary>Version of the document shape (D2: <c>1</c>).</summary>
    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    /// <summary>
    /// The oldest reader that can still make sense of this document. A reader older than the writer must
    /// refuse rather than silently ignore fields it does not understand (<see cref="SourceSyncCodec"/> does).
    /// </summary>
    public int MinimumReaderVersion { get; init; } = CurrentSchemaVersion;

    /// <summary>Sending device identifier. Not a secret; used to explain where a record came from.</summary>
    public string DeviceId { get; init; } = "";

    /// <summary>UUID of this snapshot, written with the "N" format. The idempotency key for re-delivery.</summary>
    public string SnapshotId { get; init; } = "";

    /// <summary>Wall-clock send time, in milliseconds since the Unix epoch. Informational only.</summary>
    public long CreatedAtEpochMs { get; init; }

    /// <summary>What the sender can do, so the receiver can refuse before transferring anything.</summary>
    public SyncCapabilities Capabilities { get; init; } = new();

    /// <summary>The sources being offered. Order is meaningful to the UI and is preserved by the codec.</summary>
    public IReadOnlyList<SourceRecord> Sources { get; init; } = [];

    /// <summary>Records the sender deleted. Applied only with explicit user approval (D5, D6).</summary>
    public IReadOnlyList<Tombstone> Deletions { get; init; } = [];
}

/// <summary>What the sending device is able to do (ADR-S001 D2).</summary>
public sealed record SyncCapabilities
{
    /// <summary>
    /// Whether the sender is willing to move secrets at all. Even when true, a secret travels only if the user
    /// explicitly asked for it and the channel is the audited one — never in a QR code (D6).
    /// </summary>
    public bool CanSendSecrets { get; init; }

    /// <summary>The <c>sourceType</c> values the sender can export, as <see cref="SyncSourceTypes"/> strings.</summary>
    public IReadOnlyList<string> SourceTypes { get; init; } = [];
}

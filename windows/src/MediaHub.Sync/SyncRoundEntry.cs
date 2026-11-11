namespace MediaHub.Sync;

/// <summary>Which side a round entry moves data towards.</summary>
public enum SyncMergeDirection
{
    /// <summary>This device's change would be written to the peer.</summary>
    Push = 0,

    /// <summary>The peer's change would be written here.</summary>
    Pull = 1,

    /// <summary>Both sides changed the same source, so neither direction alone is enough.</summary>
    Both = 2,
}

/// <summary>
/// One decision of a sync round: what would happen to one source, and the stable code that explains why.
/// </summary>
/// <remarks>
/// An entry is a description of a decision, not the decision being made. <see cref="SyncRoundResult.Apply"/>
/// lists the entries the caller may write; <see cref="SyncRoundResult.RequiresApproval"/> lists the ones that
/// stay proposals until the user answers; <see cref="SyncRoundResult.Ignored"/> lists the ones that need no
/// action at all. Every entry names exactly one <c>syncSourceId</c> and one reason code, never prose.
/// </remarks>
public sealed record SyncRoundEntry
{
    /// <summary>Identity the decision is about.</summary>
    public required string SyncSourceId { get; init; }

    /// <summary>Direction of the data movement this entry describes.</summary>
    public SyncMergeDirection Direction { get; init; }

    /// <summary>Added, updated or removed, as seen on the side the entry moves data from.</summary>
    public SourceSyncChangeKind Kind { get; init; }

    /// <summary>Stable reason code from <see cref="SyncRoundReasons"/>.</summary>
    public string Reason { get; init; } = "";

    /// <summary>Record that would be written, when the entry carries one.</summary>
    public SourceRecord? Record { get; init; }

    /// <summary>Tombstone that would be applied, when the entry carries one.</summary>
    public Tombstone? Tombstone { get; init; }

    /// <summary>Fields both sides changed; non-empty only for a field-level conflict.</summary>
    public IReadOnlyList<string> ConflictingFields { get; init; } = [];

    /// <summary>Revision this device has for the source (0 when it has none).</summary>
    public long LocalRevision { get; init; }

    /// <summary>Revision the peer has for the source (0 when it has none).</summary>
    public long RemoteRevision { get; init; }

    /// <summary>Whether the entry must be confirmed by the user before anything is written.</summary>
    public bool RequiresApproval { get; init; }
}

/// <summary>
/// Stable reason codes for <see cref="SyncRoundEntry.Reason"/>.
/// </summary>
/// <remarks>
/// Codes, not sentences: the preview localises them and the tests assert on them, so wording stays free to
/// change. The names describe the rule that fired, not the UI wording.
/// </remarks>
public static class SyncRoundReasons
{
    /// <summary>Only this device changed the source; the change can be pushed.</summary>
    public const string PushLocalChange = "push-local-change";

    /// <summary>This device deleted a source the peer still has; the tombstone can be pushed.</summary>
    public const string PushLocalDelete = "push-local-delete";

    /// <summary>Only the peer changed the source and this device can represent it; the change can be pulled.</summary>
    public const string ApplyRemoteChange = "apply-remote-change";

    /// <summary>The peer sent a change this device has already seen at that revision or later.</summary>
    public const string AlreadyApplied = "already-applied";

    /// <summary>The peer's change is older than what this device already knows; it is ignored, not applied.</summary>
    public const string StaleRemote = "stale-remote";

    /// <summary>This device's deletion is older than the revision the peer knows; it is not pushed.</summary>
    public const string StaleLocal = "stale-local";

    /// <summary>Both devices deleted the source; there is nothing left to do.</summary>
    public const string BothDeleted = "both-deleted";

    /// <summary>This device deleted the source while the peer edited it: the user decides.</summary>
    public const string DeleteVsUpdate = "delete-vs-update";

    /// <summary>The peer deleted the source while this device edited it: the user decides.</summary>
    public const string UpdateVsDelete = "update-vs-delete";

    /// <summary>Both sides changed the same field at the same field version: a genuine conflict.</summary>
    public const string FieldConflict = "field-conflict";

    /// <summary>Both sides changed different fields at the same revision, so neither revision wins.</summary>
    public const string SameRevisionDifferentContent = "same-revision-different-content";

    /// <summary>Both sides changed the source; the peer's revision is higher, so it wins.</summary>
    public const string RemoteNewer = "remote-newer";

    /// <summary>Both sides changed the source; this device's revision is higher, so it wins.</summary>
    public const string LocalNewer = "local-newer";

    /// <summary>The peer's source type is not one this build knows; the raw value is preserved.</summary>
    public const string UnknownSourceType = "unknown-source-type";

    /// <summary>The peer's source type is known to the protocol but not usable by this client.</summary>
    public const string NotSupportedOnWindows = "not-supported-on-windows";

    /// <summary>The peer's record carries a secret block; credentials are never imported without consent.</summary>
    public const string SecretsRequireApproval = "secrets-require-approval";

    /// <summary>The peer's tombstone would delete a local source; deletion always needs confirmation.</summary>
    public const string TombstoneRequiresApproval = "tombstone-requires-approval";

    /// <summary>The peer announced a deletion of a source this device never had. Nothing to decide.</summary>
    public const string TombstoneUnknown = "tombstone-unknown";
}

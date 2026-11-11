namespace MediaHub.Sync;

/// <summary>What the planner suggests doing with one incoming record or tombstone.</summary>
public enum SourceSyncAction
{
    /// <summary>No local source matches; add it.</summary>
    Create = 0,

    /// <summary>A local source with the same <c>syncSourceId</c> has an older revision; take the incoming fields.</summary>
    Update,

    /// <summary>The incoming record already matches the local source; nothing to do.</summary>
    NoChange,

    /// <summary>Two sources disagree and neither may overwrite the other; the user decides.</summary>
    Conflict,

    /// <summary>The incoming record is older than the local one; ignore it (not a conflict).</summary>
    IgnoreStale,

    /// <summary>An incoming tombstone proposes deleting a local source. Always needs explicit approval.</summary>
    Delete,

    /// <summary>A local source without a <c>syncSourceId</c> probably is this record; promote it after approval.</summary>
    Adopt,

    /// <summary>This build cannot represent the source type; keep the record as-is and say so.</summary>
    Unsupported,
}

/// <summary>
/// Stable reason codes for <see cref="SourceSyncCandidate.Reason"/>.
/// </summary>
/// <remarks>
/// Codes, not sentences: the UI localises them and the tests assert on them, so wording can change without
/// breaking either.
/// </remarks>
public static class SourceSyncReasons
{
    /// <summary>No local source carries this identity.</summary>
    public const string NewSource = "new-source";

    /// <summary>Incoming revision is higher than the local one.</summary>
    public const string HigherRevision = "higher-revision";

    /// <summary>Same revision and same content.</summary>
    public const string UpToDate = "up-to-date";

    /// <summary>Same revision, different content — the two devices edited independently.</summary>
    public const string SameRevisionDifferentContent = "same-revision-different-content";

    /// <summary>Incoming revision is lower than the local one.</summary>
    public const string StaleRevision = "stale-revision";

    /// <summary>An incoming tombstone for a locally known source.</summary>
    public const string Tombstone = "tombstone";

    /// <summary>A tombstone for a source this device does not have.</summary>
    public const string NoLocalSource = "no-local-source";

    /// <summary>The wire type is not one this build knows; the string is preserved verbatim.</summary>
    public const string UnknownSourceType = "unknown-source-type";

    /// <summary>The wire type is known but the Windows client cannot use it.</summary>
    public const string NotSupportedOnWindows = "not-supported-on-windows";

    /// <summary>A pre-W6 local source matches on type, normalised address and account.</summary>
    public const string LegacyIdentityMatch = "legacy-identity-match";

    /// <summary>Several pre-W6 local sources match equally well.</summary>
    public const string AmbiguousLegacyIdentity = "ambiguous-legacy-identity";

    /// <summary>This device already holds more than one source claiming the same <c>syncSourceId</c>.</summary>
    public const string DuplicateLocalSyncId = "duplicate-local-sync-id";

    /// <summary>Nothing in the incoming record normalises to an address this client could dial.</summary>
    public const string NoUsableEndpoint = "no-usable-endpoint";
}

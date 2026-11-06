namespace MediaHub.Sync;

/// <summary>
/// Outcome of <see cref="SourceSyncLocalBridge.EnsureSourceIds"/> for one local source.
/// </summary>
/// <remarks>
/// Reported rather than thrown: a source that cannot get an identity (unknown type, unparseable address) is a
/// normal state the UI has to explain, not a failure of the whole sync.
/// </remarks>
public sealed record SourceIdAssignment
{
    /// <summary>Local identity of the source this line is about.</summary>
    public string ServerId { get; init; } = "";

    /// <summary>Identity after the call; <c>null</c> when none could be assigned.</summary>
    public string? SyncSourceId { get; init; }

    /// <summary>Whether this call wrote a new identity.</summary>
    public bool Assigned { get; init; }

    /// <summary>Stable reason code from <see cref="SourceIdAssignmentReasons"/>.</summary>
    public string Reason { get; init; } = "";
}

/// <summary>Stable reason codes for <see cref="SourceIdAssignment"/>.</summary>
public static class SourceIdAssignmentReasons
{
    /// <summary>A derived identity was written to the store.</summary>
    public const string Assigned = "assigned";

    /// <summary>The source already carried an identity; nothing was written.</summary>
    public const string AlreadyHasId = "already-has-id";

    /// <summary>No address that <see cref="SyncUrlNormalizer"/> accepts, so no stable identity can be derived.</summary>
    public const string NoUsableEndpoint = "no-usable-endpoint";

    /// <summary>Neither a known type nor a preserved raw wire value, so the identity would be ambiguous.</summary>
    public const string UnknownSourceType = "unknown-source-type";

    /// <summary>Another source already holds the derived identity; assigning it would merge two sources.</summary>
    public const string DuplicateIdentity = "duplicate-identity";
}

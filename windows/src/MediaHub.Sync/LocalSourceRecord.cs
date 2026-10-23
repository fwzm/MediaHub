using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>
/// What this device currently holds for one source, in the shape the planner needs.
/// </summary>
/// <remarks>
/// Deliberately not <c>ServerEntry</c>: the App type carries handles, live status and UI state that the contract
/// must not depend on, and it lives in the App layer which owns its own lifetime. W6-2 adapts one to the other;
/// the adapter is the only place that knows both types.
/// </remarks>
public sealed record LocalSourceRecord
{
    /// <summary>Local, device-owned identity (Windows derives it from type and address). Not a sync identity.</summary>
    public string ServerId { get; init; } = "";

    /// <summary>
    /// Cross-device identity. <c>null</c> for sources configured before W6: those can only be matched by
    /// (type, address, account), and only as a suggestion the user approves.
    /// </summary>
    public string? SyncSourceId { get; init; }

    /// <summary>Local source type as the Windows client models it.</summary>
    public ServerType Type { get; init; } = ServerType.Unknown;

    /// <summary>
    /// Wire value kept verbatim when <see cref="Type"/> is <see cref="ServerType.Unknown"/> because this build
    /// cannot map it. Without it a source imported from a peer would be downgraded or dropped on the way back
    /// ("saved but not supported yet", audit §5.1) — preserving it is what makes the type set lossless.
    /// </summary>
    public string? SourceTypeRaw { get; init; }

    /// <summary>Address as the Windows client stores it; compared only through <see cref="SyncUrlNormalizer"/>.</summary>
    public string? Url { get; init; }

    /// <summary>Account this source is logged in as, if the client tracks one.</summary>
    public string? AccountScope { get; init; }

    /// <summary>Name shown locally.</summary>
    public string? DisplayName { get; init; }

    /// <summary>Revision this device last recorded for the source.</summary>
    public long RecordRevision { get; init; }
}

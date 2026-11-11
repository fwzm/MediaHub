namespace MediaHub.Sync;

/// <summary>
/// Announcement that a source was deleted on the sending device (ADR-S001 D2).
/// </summary>
/// <remarks>
/// <para>
/// A deletion is a proposal, never an instruction: the receiver turns it into a candidate that always requires
/// explicit user approval, because the same <c>syncSourceId</c> on this device may be the only remaining copy of
/// a credential and its settings (D5, D6).
/// </para>
/// <para>
/// <see cref="DeletedAtEpochMs"/> is informational. Ordering against the local record uses
/// <see cref="RecordRevision"/>, because two devices' clocks cannot be compared.
/// </para>
/// </remarks>
public sealed record Tombstone
{
    /// <summary>Identity of the deleted source.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>Wall-clock deletion time on the sending device, milliseconds since the Unix epoch. Informational.</summary>
    public long DeletedAtEpochMs { get; init; }

    /// <summary>Revision the deletion was made at; a lower revision than the local record means a stale deletion.</summary>
    public long RecordRevision { get; init; }
}

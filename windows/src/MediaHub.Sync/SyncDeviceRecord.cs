namespace MediaHub.Sync;

/// <summary>
/// What this device remembers about one paired peer.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="DeviceId"/> is the only key. Display names are user-editable text and are never used for
/// identity: two phones called "Phone" stay two devices, and a renamed device stays the same device.
/// </para>
/// <para>
/// The record is metadata only — it holds no credentials, no endpoints and no address book. Pairing state,
/// keys and trust live in the pairing store; this is the list the UI shows and the point the next round
/// resumes from.
/// </para>
/// </remarks>
public sealed record SyncDeviceRecord
{
    /// <summary>Stable identifier the peer reported during pairing.</summary>
    public required string DeviceId { get; init; }

    /// <summary>Name to show in the UI; may be empty, in which case the last known name is kept.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>When this device was last heard from, in Unix epoch milliseconds (display only).</summary>
    public long LastSeenEpochMs { get; init; }

    /// <summary>Snapshot most recently received from the peer, used as the next round's baseline.</summary>
    public string? LastAppliedSnapshotId { get; init; }

    /// <summary>Whether the peer said it can send credentials; consent is still asked per source.</summary>
    public bool CanSendSecrets { get; init; }
}

/// <summary>Result of a registration: the stored record plus what changed.</summary>
public sealed record SyncDeviceRegistration
{
    /// <summary>The record as stored after the call.</summary>
    public required SyncDeviceRecord Device { get; init; }

    /// <summary>True when this call added the device to the registry.</summary>
    public bool Added { get; init; }

    /// <summary>True when anything was written; false means the call was a no-op.</summary>
    public bool Changed { get; init; }
}

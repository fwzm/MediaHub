namespace MediaHub.Sync;

/// <summary>
/// In-memory registry of paired devices, keyed by <see cref="SyncDeviceRecord.DeviceId"/>.
/// </summary>
/// <remarks>
/// <para>
/// The registry is a view, not a source of trust: it cannot pair anything, and losing it costs display names
/// and resume points, never access. It is deliberately free of IO — persistence belongs to the same document
/// codec as everything else, so tests can exercise the rules without a file system.
/// </para>
/// <para>
/// Registration is idempotent: registering the same device twice with the same values writes nothing, and a
/// device is never duplicated by a rename. <see cref="WriteCount"/> exposes that difference so callers (and
/// tests) can insist on "no writes" rather than "same contents".
/// </para>
/// </remarks>
public sealed class SyncDeviceRegistry
{
    private readonly object gate = new();
    private readonly List<SyncDeviceRecord> devices = [];

    /// <summary>Number of writes that actually changed the registry.</summary>
    public int WriteCount { get; private set; }

    /// <summary>Devices in registration order.</summary>
    public IReadOnlyList<SyncDeviceRecord> GetAll()
    {
        lock (gate)
        {
            return devices.ToList();
        }
    }

    /// <summary>Finds a device by id; null when it was never registered.</summary>
    public SyncDeviceRecord? Find(string? deviceId)
    {
        string? key = Trimmed(deviceId);
        if (key is null)
        {
            return null;
        }

        lock (gate)
        {
            return devices.FirstOrDefault(
                device => string.Equals(device.DeviceId, key, StringComparison.OrdinalIgnoreCase));
        }
    }

    /// <summary>
    /// Registers or refreshes a device.
    /// </summary>
    /// <remarks>
    /// Merge rules are one-way on purpose: a blank display name never erases a known one, a timestamp never
    /// moves backwards, and an older snapshot id never replaces a newer one. Everything else comes from the
    /// caller, since the peer is the authority on what it can do.
    /// </remarks>
    public SyncDeviceRegistration Register(SyncDeviceRecord device)
    {
        ArgumentNullException.ThrowIfNull(device);
        if (string.IsNullOrWhiteSpace(device.DeviceId))
        {
            throw new ArgumentException("A device must have a non-blank device id.", nameof(device));
        }

        lock (gate)
        {
            int index = IndexOf(device.DeviceId);
            if (index < 0)
            {
                SyncDeviceRecord added = Normalize(device, device.DeviceId.Trim());
                devices.Add(added);
                WriteCount++;
                return new SyncDeviceRegistration { Device = added, Added = true, Changed = true };
            }

            SyncDeviceRecord existing = devices[index];
            SyncDeviceRecord merged = Normalize(device, existing.DeviceId);
            if (string.IsNullOrWhiteSpace(device.DisplayName))
            {
                merged = merged with { DisplayName = existing.DisplayName };
            }

            merged = merged with
            {
                LastSeenEpochMs = Math.Max(existing.LastSeenEpochMs, device.LastSeenEpochMs),
                LastAppliedSnapshotId = Trimmed(device.LastAppliedSnapshotId) ?? existing.LastAppliedSnapshotId,
            };

            if (merged == existing)
            {
                return new SyncDeviceRegistration { Device = existing, Added = false, Changed = false };
            }

            devices[index] = merged;
            WriteCount++;
            return new SyncDeviceRegistration { Device = merged, Added = false, Changed = true };
        }
    }

    /// <summary>
    /// Records that a snapshot was applied, so the next round resumes from it.
    /// </summary>
    /// <returns>The stored record, or null when the device is unknown.</returns>
    public SyncDeviceRegistration? MarkSnapshotApplied(string deviceId, string snapshotId, long atEpochMs)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        if (string.IsNullOrWhiteSpace(snapshotId))
        {
            throw new ArgumentException("A snapshot id is required.", nameof(snapshotId));
        }

        string snapshot = snapshotId.Trim();
        lock (gate)
        {
            int index = IndexOf(deviceId);
            if (index < 0)
            {
                return null;
            }

            SyncDeviceRecord existing = devices[index];
            SyncDeviceRecord merged = existing with
            {
                LastAppliedSnapshotId = snapshot,
                LastSeenEpochMs = Math.Max(existing.LastSeenEpochMs, atEpochMs),
            };

            if (merged == existing)
            {
                return new SyncDeviceRegistration { Device = existing, Added = false, Changed = false };
            }

            devices[index] = merged;
            WriteCount++;
            return new SyncDeviceRegistration { Device = merged, Added = false, Changed = true };
        }
    }

    /// <summary>
    /// Forgets a device locally. Nothing is revoked on the peer: unpaired state is removed by the pairing
    /// store, this only drops the view.
    /// </summary>
    public bool Remove(string? deviceId)
    {
        string? key = Trimmed(deviceId);
        if (key is null)
        {
            return false;
        }

        lock (gate)
        {
            int index = IndexOf(key);
            if (index < 0)
            {
                return false;
            }

            devices.RemoveAt(index);
            WriteCount++;
            return true;
        }
    }

    private int IndexOf(string deviceId)
    {
        string key = deviceId.Trim();
        return devices.FindIndex(
            device => string.Equals(device.DeviceId, key, StringComparison.OrdinalIgnoreCase));
    }

    private static SyncDeviceRecord Normalize(SyncDeviceRecord device, string deviceId)
        => device with
        {
            DeviceId = deviceId,
            DisplayName = device.DisplayName?.Trim() ?? "",
            LastAppliedSnapshotId = Trimmed(device.LastAppliedSnapshotId),
        };

    private static string? Trimmed(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

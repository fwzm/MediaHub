namespace MediaHub.Sync;

/// <summary>An <see cref="IPairedDeviceStore"/> that keeps the list in memory, for tests and for a device with no profile yet.</summary>
/// <remarks>
/// It follows the same rules as the file store on purpose: an upsert keeps its position, an identical write is not
/// counted, and a blank identifier is refused instead of being stored under an empty key.
/// </remarks>
public sealed class InMemoryPairedDeviceStore : IPairedDeviceStore
{
    private readonly object _gate = new();
    private readonly List<PairedDevice> _devices = [];

    /// <summary>Number of writes that actually changed the list.</summary>
    public int WriteCount { get; private set; }

    /// <summary>Lets a test simulate an unreadable store; the value is surfaced verbatim.</summary>
    public string? LoadError { get; set; }

    /// <summary>Optional capacity, mirroring the file store's backstop. Zero means unlimited.</summary>
    public int Capacity { get; init; }

    /// <summary>Identifiers currently remembered, in storage order.</summary>
    public IReadOnlyList<string> KnownDeviceIds
    {
        get
        {
            lock (_gate)
            {
                return _devices.Select(device => device.DeviceId).ToArray();
            }
        }
    }

    /// <inheritdoc />
    public IReadOnlyList<PairedDevice> GetAll()
    {
        lock (_gate)
        {
            return _devices.ToArray();
        }
    }

    /// <inheritdoc />
    public void Save(PairedDevice device)
    {
        ArgumentNullException.ThrowIfNull(device);
        if (string.IsNullOrWhiteSpace(device.DeviceId))
        {
            throw new ArgumentException("设备标识不能为空。", nameof(device));
        }

        lock (_gate)
        {
            int index = IndexOf(device.DeviceId);
            if (index >= 0)
            {
                if (Equals(_devices[index], device))
                {
                    return;
                }

                _devices[index] = device;
                WriteCount++;
                return;
            }

            if (Capacity > 0 && _devices.Count >= Capacity)
            {
                throw new IOException(PairedDeviceStoreReasons.LimitReached);
            }

            _devices.Add(device);
            WriteCount++;
        }
    }

    /// <inheritdoc />
    public bool Forget(string deviceId)
    {
        string key = (deviceId ?? string.Empty).Trim();
        if (key.Length == 0)
        {
            return false;
        }

        lock (_gate)
        {
            int index = IndexOf(key);
            if (index < 0)
            {
                return false;
            }

            _devices.RemoveAt(index);
            WriteCount++;
            return true;
        }
    }

    private int IndexOf(string deviceId)
    {
        for (int index = 0; index < _devices.Count; index++)
        {
            if (string.Equals(_devices[index].DeviceId, deviceId.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                return index;
            }
        }

        return -1;
    }
}

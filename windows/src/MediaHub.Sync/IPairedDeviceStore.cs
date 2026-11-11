namespace MediaHub.Sync;

/// <summary>Stable reasons a paired-device document can be refused, quarantined or capped.</summary>
public static class PairedDeviceStoreReasons
{
    /// <summary>The file exists but is not the document this build understands.</summary>
    public const string MalformedJson = "paired-malformed-json";

    /// <summary>The document was written by a newer build; guessing field by field would lose devices.</summary>
    public const string UnsupportedVersion = "paired-unsupported-version";

    /// <summary>The file exists but could not be read at all, so this session refuses to write over it.</summary>
    public const string StoreUnreadable = "paired-store-unreadable";

    /// <summary>The registry is full: a new device is refused instead of silently evicting an old one.</summary>
    public const string LimitReached = "paired-device-limit-reached";

    /// <summary>A write started and did not finish; the in-memory list was rolled back.</summary>
    public const string WriteFailed = "paired-store-write-failed";

    /// <summary>Every reason above, in a stable order, so tests and docs can enumerate them.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        MalformedJson,
        UnsupportedVersion,
        StoreUnreadable,
        LimitReached,
        WriteFailed,
    ];
}

/// <summary>The persisted list of paired devices, as seen by <see cref="PairedDeviceRegistry"/>.</summary>
/// <remarks>
/// A store never talks to the network and never invents identity: it only remembers what the registry handed it.
/// Reading must never throw — a damaged file has to degrade to an empty list so the app still starts, and the
/// damage must stay visible through <see cref="LoadError"/> instead of being hidden.
/// </remarks>
public interface IPairedDeviceStore
{
    /// <summary>All remembered devices. The order is the store's own; the registry sorts for display.</summary>
    IReadOnlyList<PairedDevice> GetAll();

    /// <summary>Inserts or replaces one device, matched by <see cref="PairedDevice.DeviceId"/>.</summary>
    /// <exception cref="ArgumentException">The device identifier is blank.</exception>
    /// <exception cref="System.IO.IOException">The list could not be persisted.</exception>
    /// <exception cref="System.UnauthorizedAccessException">
    /// The target file or its directory is read-only. The store still uses an atomic replace, so a read-only target
    /// surfaces as this instead of <see cref="System.IO.IOException"/>; callers must treat both as "nothing was written".
    /// </exception>
    void Save(PairedDevice device);

    /// <summary>Removes one device by identifier; false when it was not remembered.</summary>
    bool Forget(string deviceId);

    /// <summary>Why the last load degraded, or null when the store read cleanly.</summary>
    string? LoadError { get; }
}

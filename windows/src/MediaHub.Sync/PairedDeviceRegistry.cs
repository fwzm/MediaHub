using System.Globalization;

namespace MediaHub.Sync;

/// <summary>What the registry thinks of an identity presented at pairing time.</summary>
public enum PairedFingerprintState
{
    /// <summary>Nothing is remembered for this device identifier.</summary>
    New = 0,

    /// <summary>The presented fingerprint equals the pinned one.</summary>
    Match,

    /// <summary>The device is remembered with a different fingerprint.</summary>
    Mismatch,
}

/// <summary>Stable reason codes for every paired-device decision.</summary>
public static class PairedDeviceReasons
{
    /// <summary>The presented fingerprint is not the pinned one: the pairing must fail.</summary>
    public const string FingerprintMismatch = "device-fingerprint-mismatch";

    /// <summary>First sighting of a fingerprint for this identifier (trust on first use).</summary>
    public const string FingerprintNew = "device-fingerprint-new";

    /// <summary>The presented fingerprint equals the pinned one.</summary>
    public const string FingerprintMatch = "device-fingerprint-match";

    /// <summary>The peer offered no identifier at all.</summary>
    public const string MissingDeviceId = "device-identity-missing";

    /// <summary>The peer offered an identifier but no usable fingerprint.</summary>
    public const string MissingFingerprint = "device-fingerprint-missing";

    /// <summary>Device remembered for the first time.</summary>
    public const string Registered = "paired-device-registered";

    /// <summary>Device seen again; its stored details moved forward.</summary>
    public const string Updated = "paired-device-updated";

    /// <summary>Device seen again with nothing new to store.</summary>
    public const string Unchanged = "paired-device-unchanged";

    /// <summary>The list is full; the new device is refused rather than an old one being evicted.</summary>
    public const string LimitReached = PairedDeviceStoreReasons.LimitReached;

    /// <summary>The store could not be written.</summary>
    public const string StoreUnreadable = PairedDeviceStoreReasons.StoreUnreadable;

    /// <summary>Successfully forgotten.</summary>
    public const string Forgotten = "paired-device-forgotten";

    /// <summary>Nothing to forget under that identifier.</summary>
    public const string UnknownDevice = "paired-device-unknown";

    /// <summary>Every code above, in a stable order.</summary>
    public static IReadOnlyList<string> All { get; } =
    [
        FingerprintNew,
        FingerprintMatch,
        FingerprintMismatch,
        MissingDeviceId,
        MissingFingerprint,
        Registered,
        Updated,
        Unchanged,
        LimitReached,
        StoreUnreadable,
        Forgotten,
        UnknownDevice,
    ];
}

/// <summary>What one pairing attempt claimed about the peer.</summary>
public sealed record PairedDeviceObservation
{
    public required string DeviceId { get; init; }

    public required string Fingerprint { get; init; }

    public string DisplayName { get; init; } = "";

    public int SourceCount { get; init; }

    /// <summary>The caller's clock; the registry never reads the wall clock itself.</summary>
    public long NowEpochMs { get; init; }

    /// <summary>Builds an observation from the card a peer sent.</summary>
    public static PairedDeviceObservation FromCard(PairingPeerCard card, long nowEpochMs)
    {
        ArgumentNullException.ThrowIfNull(card);
        return card.ToObservation(nowEpochMs);
    }
}

/// <summary>The answer to "is this the device we pinned?".</summary>
public sealed record PairedDeviceVerification
{
    public required string DeviceId { get; init; }

    public PairedFingerprintState State { get; init; }

    public string Reason { get; init; } = "";

    /// <summary>Fingerprint the peer presented, normalized; empty when it was unusable.</summary>
    public string PresentedFingerprint { get; init; } = "";

    /// <summary>Fingerprint remembered for that identifier, when there is one.</summary>
    public string? PinnedFingerprint { get; init; }

    public bool IsMatch => State == PairedFingerprintState.Match;

    public bool IsNew => State == PairedFingerprintState.New;

    public bool IsMismatch => State == PairedFingerprintState.Mismatch;

    /// <summary>True when a first-use pin would be acceptable (never when the fingerprint changed).</summary>
    public bool CanPin => State is PairedFingerprintState.New or PairedFingerprintState.Match;
}

/// <summary>The outcome of remembering one device.</summary>
public sealed record PairedDeviceRegistration
{
    public PairedDevice? Device { get; init; }

    public PairedFingerprintState State { get; init; }

    /// <summary>True when the pairing may proceed.</summary>
    public bool Accepted { get; init; }

    public bool Added { get; init; }

    public string Reason { get; init; } = "";

    public bool IsMismatch => State == PairedFingerprintState.Mismatch;

    /// <summary>A refusal that changed nothing on disk.</summary>
    public static PairedDeviceRegistration Rejected(PairedFingerprintState state, string reason, PairedDevice? device = null) => new()
    {
        Device = device,
        State = state,
        Accepted = false,
        Added = false,
        Reason = reason,
    };
}

/// <summary>The list of devices this installation trusts, with first-use pinning.</summary>
/// <remarks>
/// Identity is the device identifier plus the pinned fingerprint; display names, ordering and source counts are
/// never used to decide anything. A mismatching fingerprint can never overwrite a pinned one — the only way past a
/// mismatch is to forget the device first, which is a deliberate user action.
/// </remarks>
public sealed class PairedDeviceRegistry
{
    /// <summary>How many devices are remembered before a new one is refused.</summary>
    public const int DefaultCapacity = FilePairedDeviceStore.DefaultCapacity;

    private readonly IPairedDeviceStore _store;
    private readonly int _capacity;

    public PairedDeviceRegistry(IPairedDeviceStore store, int capacity = DefaultCapacity)
    {
        ArgumentNullException.ThrowIfNull(store);
        if (capacity <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(capacity), "容量必须为正数。");
        }

        _store = store;
        _capacity = capacity;
    }

    /// <summary>The store behind this registry.</summary>
    public IPairedDeviceStore Store => _store;

    /// <summary>How many devices this registry will remember.</summary>
    public int Capacity => _capacity;

    /// <summary>Why the store degraded, or null.</summary>
    public string? LoadError => _store.LoadError;

    /// <summary>Sorted by identifier, so two runs of the app show the same order.</summary>
    public IReadOnlyList<PairedDevice> GetAll() => _store
        .GetAll()
        .OrderBy(device => device.DeviceId, StringComparer.OrdinalIgnoreCase)
        .ThenBy(device => device.DeviceId, StringComparer.Ordinal)
        .ToArray();

    /// <summary>Number of remembered devices.</summary>
    public int Count => _store.GetAll().Count;

    /// <summary>True when the list is at capacity: a further new device would be refused.</summary>
    public bool IsFull => Count >= _capacity;

    /// <summary>Finds a remembered device by identifier, ignoring case and surrounding whitespace.</summary>
    public PairedDevice? Find(string? deviceId)
    {
        string key = (deviceId ?? string.Empty).Trim();
        if (key.Length == 0)
        {
            return null;
        }

        foreach (PairedDevice device in _store.GetAll())
        {
            if (string.Equals(device.DeviceId, key, StringComparison.OrdinalIgnoreCase))
            {
                return device;
            }
        }

        return null;
    }

    /// <summary>Classifies a presented identity as pinned, new or changed.</summary>
    public PairedDeviceVerification Verify(string? deviceId, string? fingerprint)
    {
        string key = (deviceId ?? string.Empty).Trim();
        if (key.Length == 0)
        {
            return new PairedDeviceVerification
            {
                DeviceId = string.Empty,
                State = PairedFingerprintState.New,
                Reason = PairedDeviceReasons.MissingDeviceId,
            };
        }

        bool parsed = PairingFingerprint.TryParse(fingerprint, out PairingFingerprint presented);
        PairedDevice? existing = Find(key);
        if (existing is null)
        {
            return new PairedDeviceVerification
            {
                DeviceId = key,
                State = PairedFingerprintState.New,
                Reason = parsed ? PairedDeviceReasons.FingerprintNew : PairedDeviceReasons.MissingFingerprint,
                PresentedFingerprint = parsed ? presented.Hex : string.Empty,
            };
        }

        if (!parsed)
        {
            return new PairedDeviceVerification
            {
                DeviceId = key,
                State = PairedFingerprintState.Mismatch,
                Reason = PairedDeviceReasons.MissingFingerprint,
                PinnedFingerprint = existing.Fingerprint,
            };
        }

        bool same = PairingFingerprint.TryParse(existing.Fingerprint, out PairingFingerprint pinned) && pinned.Matches(presented);
        return new PairedDeviceVerification
        {
            DeviceId = existing.DeviceId,
            State = same ? PairedFingerprintState.Match : PairedFingerprintState.Mismatch,
            Reason = same ? PairedDeviceReasons.FingerprintMatch : PairedDeviceReasons.FingerprintMismatch,
            PresentedFingerprint = presented.Hex,
            PinnedFingerprint = existing.Fingerprint,
        };
    }

    /// <summary>Remembers a device, or refuses when the fingerprint changed or the list is full.</summary>
    public PairedDeviceRegistration Register(PairedDeviceObservation observation)
    {
        ArgumentNullException.ThrowIfNull(observation);

        string deviceId = (observation.DeviceId ?? string.Empty).Trim();
        if (deviceId.Length == 0)
        {
            return PairedDeviceRegistration.Rejected(PairedFingerprintState.New, PairedDeviceReasons.MissingDeviceId);
        }

        if (!PairingFingerprint.TryParse(observation.Fingerprint, out PairingFingerprint presented))
        {
            return PairedDeviceRegistration.Rejected(PairedFingerprintState.New, PairedDeviceReasons.MissingFingerprint);
        }

        long now = observation.NowEpochMs;
        PairedDevice? existing = Find(deviceId);
        if (existing is not null)
        {
            PairedDeviceVerification verification = Verify(existing.DeviceId, presented.Hex);
            if (verification.IsMismatch)
            {
                // A pinned fingerprint is never overwritten; forgetting the device is a separate user action.
                return PairedDeviceRegistration.Rejected(PairedFingerprintState.Mismatch, verification.Reason, existing);
            }

            PairedDevice updated = existing with
            {
                DisplayName = string.IsNullOrWhiteSpace(observation.DisplayName) ? existing.DisplayName : observation.DisplayName.Trim(),
                Fingerprint = presented.Hex,
                PairedAtEpochMs = existing.PairedAtEpochMs > 0 ? existing.PairedAtEpochMs : now,
                LastSeenEpochMs = Math.Max(existing.LastSeenEpochMs, now),
                SourceCount = observation.SourceCount < 0 ? 0 : observation.SourceCount,
            };

            if (Equals(updated, existing))
            {
                return new PairedDeviceRegistration
                {
                    Device = existing,
                    State = PairedFingerprintState.Match,
                    Accepted = true,
                    Added = false,
                    Reason = PairedDeviceReasons.Unchanged,
                };
            }

            try
            {
                _store.Save(updated);
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                return PairedDeviceRegistration.Rejected(PairedFingerprintState.Match, DescribeStoreFailure(exception), existing);
            }

            return new PairedDeviceRegistration
            {
                Device = updated,
                State = PairedFingerprintState.Match,
                Accepted = true,
                Added = false,
                Reason = PairedDeviceReasons.Updated,
            };
        }

        if (Count >= _capacity)
        {
            return PairedDeviceRegistration.Rejected(PairedFingerprintState.New, PairedDeviceReasons.LimitReached);
        }

        PairedDevice created = PairedDevice.Create(deviceId, presented.Hex, observation.DisplayName, now, observation.SourceCount);
        try
        {
            _store.Save(created);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return PairedDeviceRegistration.Rejected(PairedFingerprintState.New, DescribeStoreFailure(exception));
        }

        return new PairedDeviceRegistration
        {
            Device = created,
            State = PairedFingerprintState.New,
            Accepted = true,
            Added = true,
            Reason = PairedDeviceReasons.Registered,
        };
    }

    /// <summary>Forgets a device so that a later pairing may pin its fingerprint again.</summary>
    public bool Forget(string? deviceId)
    {
        string key = (deviceId ?? string.Empty).Trim();
        if (key.Length == 0)
        {
            return false;
        }

        try
        {
            return _store.Forget(key);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static string DescribeStoreFailure(Exception exception)
        => exception is IOException { Message.Length: > 0 } io && io.Message.Contains(PairedDeviceStoreReasons.LimitReached, StringComparison.Ordinal)
            ? PairedDeviceReasons.LimitReached
            : PairedDeviceReasons.StoreUnreadable;

    /// <summary>One line per device for a log or a UI list; never claims syncing or signing in.</summary>
    public static string Describe(PairedDevice device, long? nowEpochMs = null)
    {
        ArgumentNullException.ThrowIfNull(device);
        return device.Describe(nowEpochMs);
    }

    /// <summary>Human label for a verification state; the raw code stays in the log.</summary>
    public static string DescribeState(PairedFingerprintState state) => state switch
    {
        PairedFingerprintState.Match => "指纹一致",
        PairedFingerprintState.Mismatch => "指纹不一致",
        _ => "新设备",
    };

    /// <summary>Human sentence for a reason code, used by UI and logs.</summary>
    public static string DescribeReason(string? reason) => reason switch
    {
        PairedDeviceReasons.FingerprintMatch => "指纹与本机记录一致。",
        PairedDeviceReasons.FingerprintNew => "本机第一次见到这个设备，将按首次配对记住它的指纹。",
        PairedDeviceReasons.FingerprintMismatch => "该设备这次出示的指纹与本机记录不一致，配对已中止；如需重新信任，请先在已配对设备里移除它。",
        PairedDeviceReasons.MissingDeviceId => "对端没有提供设备标识，无法登记。",
        PairedDeviceReasons.MissingFingerprint => "对端没有提供可用的设备指纹，无法登记。",
        PairedDeviceReasons.Registered => "已记住该设备（配对成功不等于已同步）。",
        PairedDeviceReasons.Updated => "已更新该设备的最近配对时间。",
        PairedDeviceReasons.Unchanged => "该设备没有变化。",
        PairedDeviceReasons.LimitReached => "已配对设备数量已达上限，新设备不会被登记；请先移除一台。",
        PairedDeviceReasons.StoreUnreadable => "已配对设备清单当前不可写，这次配对没有被记住。",
        PairedDeviceReasons.Forgotten => "已移除该设备。",
        PairedDeviceReasons.UnknownDevice => "本机没有这个设备的记录。",
        string other when !string.IsNullOrWhiteSpace(other) => other,
        _ => "未说明原因。",
    };
}

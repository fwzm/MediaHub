namespace MediaHub.App.Services;

/// <summary>
/// The identity this installation presents to other devices.
/// </summary>
/// <remarks>
/// A stable device id is what lets the peer tell "the same device came back" from "a new device appeared".
/// It is therefore created once and then only read back: regenerating it would make every source look
/// newly discovered and re-open decisions the user already answered.
/// </remarks>
internal static class SyncIdentity
{
    private const string Prefix = "dev-";

    /// <summary>Returns the stored device id, creating and persisting one on first use.</summary>
    public static string EnsureDeviceId(SettingsStore settings)
    {
        ArgumentNullException.ThrowIfNull(settings);

        string? existing = settings.Current.DeviceId;
        if (!string.IsNullOrWhiteSpace(existing)) return existing.Trim();

        string created = Prefix + Guid.NewGuid().ToString("N")[..12];
        settings.Update(state =>
        {
            if (string.IsNullOrWhiteSpace(state.DeviceId)) state.DeviceId = created;
        });

        // Update serialises the write. If a concurrent caller won the race, its id is the one this
        // process has to use, otherwise the peer sees one device that keeps changing its name.
        string? stored = settings.Current.DeviceId;
        return string.IsNullOrWhiteSpace(stored) ? created : stored.Trim();
    }
}

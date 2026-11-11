using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Locks down the paired-device view (W6-4): device id is the only identity, registration is idempotent,
/// and the document codec refuses what it cannot understand instead of guessing.
/// </summary>
public sealed class SyncDeviceRegistryTests
{
    /// <summary>Registering a new device stores it once.</summary>
    [Fact]
    public void Register_AddsANewDevice()
    {
        var registry = new SyncDeviceRegistry();

        SyncDeviceRegistration registration = registry.Register(Device("android-1", "Pixel"));

        Assert.True(registration.Added);
        Assert.True(registration.Changed);
        Assert.Equal(1, registry.WriteCount);
        Assert.Equal("Pixel", Assert.Single(registry.GetAll()).DisplayName);
    }

    /// <summary>Reporting the same device twice must not duplicate it or touch the store.</summary>
    [Fact]
    public void Register_IsIdempotent()
    {
        var registry = new SyncDeviceRegistry();
        SyncDeviceRecord device = Device("android-1", "Pixel");
        registry.Register(device);

        SyncDeviceRegistration second = registry.Register(device);

        Assert.False(second.Added);
        Assert.False(second.Changed);
        Assert.Equal(1, registry.WriteCount);
        Assert.Single(registry.GetAll());
    }

    /// <summary>The id is matched case-insensitively but the first spelling is what gets stored.</summary>
    [Fact]
    public void Register_KeepsTheFirstSpellingOfTheDeviceId()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("ANDROID-1", "Pixel"));

        SyncDeviceRegistration second = registry.Register(Device("android-1", "Pixel 9"));

        Assert.Equal("ANDROID-1", second.Device.DeviceId);
        Assert.Equal("Pixel 9", second.Device.DisplayName);
        Assert.Single(registry.GetAll());
    }

    /// <summary>A blank name from the wire never erases a name the user already knows.</summary>
    [Fact]
    public void Register_KeepsAKnownDisplayNameWhenTheNewOneIsBlank()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel"));

        SyncDeviceRegistration second = registry.Register(Device("android-1", "   ") with { LastSeenEpochMs = 500 });

        Assert.Equal("Pixel", second.Device.DisplayName);
        Assert.Equal(500, second.Device.LastSeenEpochMs);
    }

    /// <summary>Last-seen only moves forward; an out-of-order report is not a regression.</summary>
    [Fact]
    public void Register_NeverMovesTheLastSeenTimeBackwards()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel") with { LastSeenEpochMs = 900 });

        SyncDeviceRegistration second = registry.Register(Device("android-1", "Pixel") with { LastSeenEpochMs = 100 });

        Assert.False(second.Changed);
        Assert.Equal(900, second.Device.LastSeenEpochMs);
    }

    /// <summary>Two devices may share a display name; names are labels, ids are identity.</summary>
    [Fact]
    public void Register_TreatsTheDisplayNameAsALabelNotAnIdentity()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Phone"));
        registry.Register(Device("android-2", "Phone"));

        Assert.Equal(2, registry.GetAll().Count);
        Assert.Equal(2, registry.WriteCount);
    }

    /// <summary>A device without an id cannot be registered at all.</summary>
    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void Register_RejectsABlankDeviceId(string deviceId)
    {
        var registry = new SyncDeviceRegistry();

        Assert.Throws<ArgumentException>(() => registry.Register(Device(deviceId, "Pixel")));
        Assert.Empty(registry.GetAll());
    }

    /// <summary>A null record is a programming error, not an empty registration.</summary>
    [Fact]
    public void Register_RejectsANullRecord()
    {
        var registry = new SyncDeviceRegistry();

        Assert.Throws<ArgumentNullException>(() => registry.Register(null!));
    }

    /// <summary>An applied snapshot becomes the resume point for the next round.</summary>
    [Fact]
    public void MarkSnapshotApplied_MovesTheResumePoint()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel"));

        SyncDeviceRegistration? registration = registry.MarkSnapshotApplied("android-1", "snap-7", 1000);

        Assert.NotNull(registration);
        Assert.True(registration!.Changed);
        Assert.Equal("snap-7", registration.Device.LastAppliedSnapshotId);
        Assert.Equal(1000, registration.Device.LastSeenEpochMs);
    }

    /// <summary>Applying the same snapshot again writes nothing.</summary>
    [Fact]
    public void MarkSnapshotApplied_RepeatedIsANoOp()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel"));
        registry.MarkSnapshotApplied("android-1", "snap-7", 1000);
        int writesBefore = registry.WriteCount;

        SyncDeviceRegistration? second = registry.MarkSnapshotApplied("android-1", "snap-7", 900);

        Assert.NotNull(second);
        Assert.False(second!.Changed);
        Assert.Equal(writesBefore, registry.WriteCount);
    }

    /// <summary>An unknown device has no resume point to move, and inventing one would resurrect a removed peer.</summary>
    [Fact]
    public void MarkSnapshotApplied_ReturnsNullForAnUnknownDevice()
    {
        var registry = new SyncDeviceRegistry();

        Assert.Null(registry.MarkSnapshotApplied("android-9", "snap-1", 1000));
        Assert.Equal(0, registry.WriteCount);
    }

    /// <summary>Removing a device only clears this device's view; it is not a revocation.</summary>
    [Fact]
    public void Remove_ForgetsOnlyTheLocalView()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel"));
        registry.Register(Device("android-2", "Tablet"));

        Assert.True(registry.Remove("ANDROID-1"));
        Assert.False(registry.Remove("android-1"));
        Assert.Null(registry.Find("android-1"));
        Assert.Single(registry.GetAll());
    }

    /// <summary>Order is registration order, so a device list does not reshuffle between views.</summary>
    [Fact]
    public void GetAll_KeepsRegistrationOrder()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-2", "Tablet"));
        registry.Register(Device("android-1", "Pixel"));

        Assert.Equal(new[] { "android-2", "android-1" }, registry.GetAll().Select(device => device.DeviceId));
    }

    /// <summary>A device list survives a write/read cycle unchanged, resume points included.</summary>
    [Fact]
    public void Codec_RoundTripsTheDeviceList()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel") with { LastSeenEpochMs = 1234, CanSendSecrets = true });
        registry.MarkSnapshotApplied("android-1", "snap-3", 1240);

        string json = SyncDeviceRegistryCodec.Serialize(registry);
        IReadOnlyList<SyncDeviceRecord> devices = SyncDeviceRegistryCodec.Deserialize(json);

        SyncDeviceRecord restored = Assert.Single(devices);
        Assert.Equal(registry.GetAll()[0], restored);
        Assert.Equal("snap-3", restored.LastAppliedSnapshotId);
        Assert.True(restored.CanSendSecrets);
    }

    /// <summary>Fields written by a later build are ignored rather than rejected.</summary>
    [Fact]
    public void Codec_IgnoresUnknownFields()
    {
        const string json = """
            {
              "version": 1,
              "pinned": true,
              "devices": [
                { "deviceId": "android-1", "displayName": "Pixel", "nickname": "living room" }
              ]
            }
            """;

        SyncDeviceRecord device = Assert.Single(SyncDeviceRegistryCodec.Deserialize(json));

        Assert.Equal("android-1", device.DeviceId);
        Assert.Equal("Pixel", device.DisplayName);
        Assert.Equal(0, device.LastSeenEpochMs);
        Assert.False(device.CanSendSecrets);
    }

    /// <summary>A document this build does not understand is refused with the version, never half-read.</summary>
    [Fact]
    public void Codec_RefusesAFutureSchemaVersion()
    {
        const string json = """{ "version": 2, "devices": [] }""";

        SyncContractException error = Assert.Throws<SyncContractException>(
            () => SyncDeviceRegistryCodec.Deserialize(json));

        Assert.Equal("unsupported-schema-version:2", error.Reason);
    }

    /// <summary>A device without an id cannot be keyed, so the document is rejected whole.</summary>
    [Fact]
    public void Codec_ReportsAMissingDeviceId()
    {
        const string json = """{ "version": 1, "devices": [ { "displayName": "Pixel" } ] }""";

        SyncContractException error = Assert.Throws<SyncContractException>(
            () => SyncDeviceRegistryCodec.Deserialize(json));

        Assert.Equal("missing-field:deviceId", error.Reason);
    }

    /// <summary>Truncated JSON is reported, not treated as an empty list that the next save would overwrite.</summary>
    [Fact]
    public void Codec_ReportsMalformedJson()
    {
        const string json = """{ "version": 1, "devices": [ """;

        Assert.False(SyncDeviceRegistryCodec.TryDeserialize(json, out IReadOnlyList<SyncDeviceRecord> devices, out string? error));
        Assert.Equal("malformed-json", error);
        Assert.Empty(devices);
    }

    /// <summary>A hand-edited duplicate collapses to the last entry instead of failing the whole document.</summary>
    [Fact]
    public void Codec_KeepsTheLastEntryForADuplicatedDeviceId()
    {
        const string json = """
            {
              "version": 1,
              "devices": [
                { "deviceId": "android-1", "displayName": "Old" },
                { "deviceId": "ANDROID-1", "displayName": "New" }
              ]
            }
            """;

        SyncDeviceRecord device = Assert.Single(SyncDeviceRegistryCodec.Deserialize(json));

        Assert.Equal("New", device.DisplayName);
    }

    /// <summary>The document is camelCase with an explicit version, so other tools can read it.</summary>
    [Fact]
    public void Codec_WritesCamelCaseFieldsAndAVersion()
    {
        var registry = new SyncDeviceRegistry();
        registry.Register(Device("android-1", "Pixel"));

        string json = SyncDeviceRegistryCodec.Serialize(registry);

        Assert.Contains("\"version\": 1", json, StringComparison.Ordinal);
        Assert.Contains("\"deviceId\": \"android-1\"", json, StringComparison.Ordinal);
        Assert.Contains("\"displayName\": \"Pixel\"", json, StringComparison.Ordinal);
        Assert.Contains("\"lastSeenEpochMs\"", json, StringComparison.Ordinal);
        Assert.Contains("\"lastAppliedSnapshotId\"", json, StringComparison.Ordinal);
        Assert.Contains("\"canSendSecrets\"", json, StringComparison.Ordinal);
    }

    private static SyncDeviceRecord Device(string deviceId, string displayName)
        => new() { DeviceId = deviceId, DisplayName = displayName };
}

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// W6-12: the paired-device registry, the shareable pairing link and the address candidate list.
/// </summary>
/// <remarks>
/// Everything here is loopback-only. No test opens a non-loopback socket, and the address candidate tests feed
/// synthetic adapters instead of asking the machine, so a CI box with a real LAN card cannot influence the result.
/// </remarks>
public sealed class PairedDeviceRegistryTests : IDisposable
{
    private const long FixedNow = 1_700_000_000_000;

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mh-paired-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root))
            {
                Directory.Delete(_root, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    // ---------------------------------------------------------------- file store

    [Fact]
    public void FileStore_WritesAtomicallyAndLeavesNoTemporaryFileBehind()
    {
        var store = new FilePairedDeviceStore(_root);
        store.Save(PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow));
        store.Save(PairedDevice.Create("dev-b", Hex(2), "客厅", FixedNow));

        Assert.Equal(2, store.WriteCount);
        Assert.True(File.Exists(store.FilePath));
        Assert.True(File.Exists(store.BackupPath));
        Assert.Empty(TemporaryFiles());

        string json = File.ReadAllText(store.FilePath, Encoding.UTF8);
        using JsonDocument document = JsonDocument.Parse(json);
        Assert.Equal(FilePairedDeviceStore.CurrentDocumentVersion, document.RootElement.GetProperty("version").GetInt32());
        Assert.Equal(2, document.RootElement.GetProperty("devices").GetArrayLength());
    }

    [Fact]
    public void FileStore_SurvivesACorruptDocumentWithoutOverwritingIt()
    {
        Directory.CreateDirectory(_root);
        byte[] junk = Encoding.UTF8.GetBytes("{ this is not a paired device list }");
        string path = Path.Combine(_root, FilePairedDeviceStore.FileName);
        File.WriteAllBytes(path, junk);

        var store = new FilePairedDeviceStore(_root);
        Assert.Empty(store.GetAll());
        Assert.NotNull(store.LoadError);
        Assert.NotNull(store.LastQuarantinedPath);
        Assert.Equal(junk, File.ReadAllBytes(store.LastQuarantinedPath!));
        Assert.False(File.Exists(path));
        Assert.Contains("corrupt", Path.GetFileName(store.LastQuarantinedPath!), StringComparison.OrdinalIgnoreCase);

        // The quarantined file stays readable evidence, and the store starts a fresh document next to it.
        store.Save(PairedDevice.Create("dev-a", Hex(1), "桌面", FixedNow));
        Assert.Single(store.GetAll());
        Assert.Equal(junk, File.ReadAllBytes(store.LastQuarantinedPath!));
    }

    [Fact]
    public void FileStore_MissingFileIsAnEmptyStoreAndNotAnError()
    {
        var store = new FilePairedDeviceStore(_root);
        Assert.Empty(store.GetAll());
        Assert.Null(store.LoadError);
        Assert.Equal(0, store.WriteCount);
    }

    [Fact]
    public void FileStore_OutputOrderIsDeterministicWhateverTheInsertionOrder()
    {
        var first = new FilePairedDeviceStore(_root);
        first.Save(PairedDevice.Create("dev-c", Hex(3), "", FixedNow));
        first.Save(PairedDevice.Create("dev-a", Hex(1), "", FixedNow));
        first.Save(PairedDevice.Create("dev-b", Hex(2), "", FixedNow));

        var second = new FilePairedDeviceStore(_root);
        Assert.Equal(["dev-a", "dev-b", "dev-c"], second.GetAll().Select(device => device.DeviceId));
        Assert.Equal(["dev-a", "dev-b", "dev-c"], new PairedDeviceRegistry(second).GetAll().Select(device => device.DeviceId));
    }

    [Fact]
    public void FileStore_RefusesToGrowPastItsCapacityInsteadOfEvictingSilently()
    {
        var store = new FilePairedDeviceStore(_root) { Capacity = 2 };
        var registry = new PairedDeviceRegistry(store, capacity: 2);
        Assert.True(registry.Register(Observe("dev-a", Hex(1))).Accepted);
        Assert.True(registry.Register(Observe("dev-b", Hex(2))).Accepted);

        PairedDeviceRegistration refused = registry.Register(Observe("dev-c", Hex(3)));
        Assert.False(refused.Accepted);
        Assert.Equal(PairedDeviceReasons.LimitReached, refused.Reason);
        Assert.Equal(2, registry.Count);
        Assert.DoesNotContain(registry.GetAll(), device => device.DeviceId == "dev-c");
    }

    // ---------------------------------------------------------------- registry

    [Fact]
    public void Registry_VerifyReportsNewThenMatchThenMismatch()
    {
        var registry = new PairedDeviceRegistry(new InMemoryPairedDeviceStore());
        Assert.Equal(PairedFingerprintState.New, registry.Verify("dev-a", Hex(1)).State);

        Assert.True(registry.Register(Observe("dev-a", Hex(1))).Accepted);
        PairedDeviceVerification match = registry.Verify("dev-a", Hex(1));
        Assert.True(match.IsMatch);
        Assert.Equal(PairedDeviceReasons.FingerprintMatch, match.Reason);

        PairedDeviceVerification changed = registry.Verify("dev-a", Hex(9));
        Assert.True(changed.IsMismatch);
        Assert.Equal(PairedDeviceReasons.FingerprintMismatch, changed.Reason);
        Assert.Equal(Hex(1), changed.PinnedFingerprint);
    }

    [Fact]
    public void Registry_RefusesToOverwriteAPinnedFingerprint()
    {
        var store = new InMemoryPairedDeviceStore();
        var registry = new PairedDeviceRegistry(store);
        registry.Register(Observe("dev-a", Hex(1), name: "桌面"));

        PairedDeviceRegistration refused = registry.Register(Observe("dev-a", Hex(9), name: "冒名者"));
        Assert.False(refused.Accepted);
        Assert.True(refused.IsMismatch);
        Assert.Equal(PairedDeviceReasons.FingerprintMismatch, refused.Reason);

        PairedDevice stored = Assert.Single(registry.GetAll());
        Assert.Equal(Hex(1), stored.Fingerprint);
        Assert.Equal("桌面", stored.DisplayName);
        Assert.Equal(1, store.WriteCount);
    }

    [Fact]
    public void Registry_UpdatesLastSeenButKeepsTheFirstPairedTime()
    {
        var registry = new PairedDeviceRegistry(new InMemoryPairedDeviceStore());
        registry.Register(Observe("dev-a", Hex(1), now: FixedNow, name: "桌面"));
        PairedDeviceRegistration again = registry.Register(Observe("dev-a", Hex(1), now: FixedNow + 60_000));

        Assert.True(again.Accepted);
        Assert.False(again.Added);
        Assert.Equal(PairedDeviceReasons.Updated, again.Reason);
        Assert.Equal(FixedNow, again.Device!.PairedAtEpochMs);
        Assert.Equal(FixedNow + 60_000, again.Device.LastSeenEpochMs);
        Assert.Equal("桌面", again.Device.DisplayName);
    }

    [Fact]
    public void Registry_SecondIdenticalObservationWritesNothing()
    {
        var store = new InMemoryPairedDeviceStore();
        var registry = new PairedDeviceRegistry(store);
        registry.Register(Observe("dev-a", Hex(1)));
        int after = store.WriteCount;

        PairedDeviceRegistration repeat = registry.Register(Observe("dev-a", Hex(1)));
        Assert.Equal(PairedDeviceReasons.Unchanged, repeat.Reason);
        Assert.Equal(after, store.WriteCount);
    }

    [Fact]
    public void Registry_ForgetMakesTheDeviceNewAgain()
    {
        var registry = new PairedDeviceRegistry(new InMemoryPairedDeviceStore());
        registry.Register(Observe("dev-a", Hex(1)));

        Assert.True(registry.Forget(" DEV-A "));
        Assert.False(registry.Forget("dev-a"));
        Assert.Empty(registry.GetAll());
        Assert.Equal(PairedFingerprintState.New, registry.Verify("dev-a", Hex(1)).State);
    }

    [Fact]
    public void Registry_RefusesBlankIdentityOrFingerprint()
    {
        var store = new InMemoryPairedDeviceStore();
        var registry = new PairedDeviceRegistry(store);

        PairedDeviceRegistration noId = registry.Register(Observe("   ", Hex(1)));
        Assert.False(noId.Accepted);
        Assert.Equal(PairedDeviceReasons.MissingDeviceId, noId.Reason);

        PairedDeviceRegistration noFingerprint = registry.Register(Observe("dev-a", "not-a-fingerprint"));
        Assert.False(noFingerprint.Accepted);
        Assert.Equal(PairedDeviceReasons.MissingFingerprint, noFingerprint.Reason);

        Assert.Empty(registry.GetAll());
        Assert.Equal(PairedDeviceReasons.MissingDeviceId, registry.Verify(null, Hex(1)).Reason);
    }

    [Fact]
    public void Registry_ReportsAStoreFailureInsteadOfThrowing()
    {
        var registry = new PairedDeviceRegistry(new ThrowingPairedDeviceStore());
        PairedDeviceRegistration refused = registry.Register(Observe("dev-a", Hex(1)));

        Assert.False(refused.Accepted);
        Assert.Equal(PairedDeviceReasons.StoreUnreadable, refused.Reason);
    }

    [Fact]
    public void Registry_ReasonCodesAreStableAndEveryOneReadsAsChinese()
    {
        Assert.Equal(
            [
                PairedDeviceStoreReasons.MalformedJson,
                PairedDeviceStoreReasons.UnsupportedVersion,
                PairedDeviceStoreReasons.StoreUnreadable,
                PairedDeviceStoreReasons.LimitReached,
                PairedDeviceStoreReasons.WriteFailed,
            ],
            PairedDeviceStoreReasons.All);

        Assert.All(PairedDeviceReasons.All, reason =>
        {
            string text = PairedDeviceRegistry.DescribeReason(reason);
            Assert.False(string.IsNullOrWhiteSpace(text));
            Assert.DoesNotContain(reason, text, StringComparison.Ordinal);
        });
    }

    // ---------------------------------------------------------------- pairing link

    [Fact]
    public void Link_RoundTripsTheInvitation()
    {
        PairingPayload payload = Payload();
        string link = PairingLink.Create(payload);

        Assert.StartsWith(PairingLink.Prefix, link, StringComparison.Ordinal);
        Assert.DoesNotContain(payload.Challenge, link, StringComparison.Ordinal);

        PairingLinkResult parsed = PairingLink.Parse(link, FixedNow);
        Assert.True(parsed.Succeeded);
        Assert.Equal(payload.DeviceId, parsed.Payload!.DeviceId);
        Assert.Equal(payload.Challenge, parsed.Payload.Challenge);
        Assert.Equal(payload.EphemeralUrl, parsed.Payload.EphemeralUrl);
    }

    [Fact]
    public void Link_RefusesAnythingThatIsNotALink()
    {
        Assert.Equal(PairingLinkReasons.Empty, PairingLink.Parse("   ", FixedNow).Reason);
        Assert.Equal(PairingLinkReasons.NotALink, PairingLink.Parse(Payload().DeviceId, FixedNow).Reason);
        Assert.Equal(PairingLinkReasons.NotALink, PairingLink.Parse("https://example.invalid/?code=abc", FixedNow).Reason);
        Assert.Equal(PairingLinkReasons.TooLong, PairingLink.Parse(PairingLink.Prefix + new string('a', PairingLink.MaximumLength), FixedNow).Reason);
    }

    [Fact]
    public void Link_RefusesAnExpiredInvitationWithoutEchoingIt()
    {
        string link = PairingLink.Create(Payload());
        PairingLinkResult expired = PairingLink.Parse(link, FixedNow + PairingPayload.LifetimeMilliseconds + 1);

        Assert.False(expired.Succeeded);
        Assert.Equal(PairingReasons.Expired, expired.Reason);
        Assert.DoesNotContain(Payload().Challenge, expired.ToString(), StringComparison.Ordinal);
    }

    // ---------------------------------------------------------------- address candidates

    [Fact]
    public void Candidates_PreferWiredAndDropLoopbackAndApipa()
    {
        PairingAddressReport report = PairingAddressCandidates.Describe(
        [
            new PairingAddressInput
            {
                InterfaceName = "Wi-Fi",
                Type = NetworkInterfaceType.Wireless80211,
                Addresses = ["192.168.1.50", "169.254.10.10", "127.0.0.1"],
            },
            new PairingAddressInput
            {
                InterfaceName = "以太网",
                Type = NetworkInterfaceType.Ethernet,
                Addresses = ["192.168.1.20", "fe80::1"],
            },
            new PairingAddressInput
            {
                InterfaceName = "已断开",
                Type = NetworkInterfaceType.Ethernet,
                Status = OperationalStatus.Down,
                Addresses = ["10.0.0.5"],
            },
        ]);

        Assert.Equal(["192.168.1.20", "192.168.1.50"], report.Candidates.Select(candidate => candidate.Address));
        Assert.Equal(PairingAddressReasons.Wired, report.Candidates[0].Reason);
        Assert.Equal(PairingAddressReasons.Wireless, report.Candidates[1].Reason);
        Assert.Equal("https://192.168.1.20:47831/pairing", report.Candidates[0].ToUrl(47831));
        Assert.Null(report.Reason);

        Assert.Contains(report.Rejected, candidate => candidate.Address == "127.0.0.1" && candidate.Reason == PairingAddressReasons.Loopback);
        Assert.Contains(report.Rejected, candidate => candidate.Address == "169.254.10.10" && candidate.Reason == PairingAddressReasons.Apipa);
        Assert.Contains(report.Rejected, candidate => candidate.InterfaceName == "已断开" && candidate.Reason == PairingAddressReasons.InterfaceDown);
        Assert.Contains(report.Rejected, candidate => candidate.Reason == PairingAddressReasons.NotIpv4);
    }

    [Fact]
    public void Candidates_ReportNoUsableAddressWhenNothingQualifies()
    {
        PairingAddressReport report = PairingAddressCandidates.Describe(
        [
            new PairingAddressInput { InterfaceName = "Loopback", Type = NetworkInterfaceType.Loopback, Addresses = ["127.0.0.1"] },
        ]);

        Assert.True(report.IsEmpty);
        Assert.Equal(PairingAddressReasons.NoUsableAddress, report.Reason);
        Assert.Null(report.Primary);
    }

    // ---------------------------------------------------------------- presenter integration

    [Fact]
    public async Task Presenter_PairingWithRegistriesRemembersBothSides()
    {
        var hostStore = new InMemoryPairedDeviceStore();
        var guestStore = new InMemoryPairedDeviceStore();
        using var host = new PairingPresenter("dev-host", registry: new PairedDeviceRegistry(hostStore));
        using var guest = new PairingPresenter("dev-guest", registry: new PairedDeviceRegistry(guestStore));

        (PairingHostSnapshot snapshot, PairingGuestResult result) = await PairAsync(host, guest);

        Assert.Equal(PairingHostState.Paired, snapshot.State);
        Assert.Equal("dev-guest", host.PeerDeviceId);
        Assert.NotNull(host.PeerFingerprintDisplay);
        Assert.True(result.Paired);
        Assert.Equal(PairingStepCodes.GuestPaired, result.StepCode);
        Assert.Equal("dev-guest", Assert.Single(hostStore.GetAll()).DeviceId);
        Assert.Equal("dev-host", Assert.Single(guestStore.GetAll()).DeviceId);
        Assert.Equal(PairedFingerprintState.Match, new PairedDeviceRegistry(hostStore).Verify("dev-guest", PairingSelfIdentity.DeriveFingerprint("dev-guest").Hex).State);
    }

    [Fact]
    public async Task Presenter_SecondPairingOfTheSameDevicesIsAMatchAndKeepsOneRow()
    {
        var hostStore = new InMemoryPairedDeviceStore();
        var guestStore = new InMemoryPairedDeviceStore();

        for (int attempt = 0; attempt < 2; attempt++)
        {
            using var host = new PairingPresenter("dev-host", registry: new PairedDeviceRegistry(hostStore));
            using var guest = new PairingPresenter("dev-guest", registry: new PairedDeviceRegistry(guestStore));
            (PairingHostSnapshot snapshot, PairingGuestResult result) = await PairAsync(host, guest, FixedNow + (attempt * 1_000));
            Assert.Equal(PairingHostState.Paired, snapshot.State);
            Assert.True(result.Paired);
        }

        Assert.Single(hostStore.GetAll());
        Assert.Single(guestStore.GetAll());
        // Two pairings, one row per side, and both sides still verify against the self-declared identity fingerprint.
        PairedDeviceVerification hostView = new PairedDeviceRegistry(hostStore).Verify("dev-guest", PairingSelfIdentity.DeriveFingerprint("dev-guest").Hex);
        Assert.True(hostView.IsMatch);
        Assert.Equal(PairedDeviceReasons.FingerprintMatch, hostView.Reason);
        Assert.True(new PairedDeviceRegistry(guestStore).Verify("dev-host", PairingSelfIdentity.DeriveFingerprint("dev-host").Hex).IsMatch);
    }

    [Fact]
    public async Task Presenter_HostRefusesAPeerWhosePinnedFingerprintChanged()
    {
        var hostStore = new InMemoryPairedDeviceStore();
        hostStore.Save(PairedDevice.Create("dev-guest", Hex(9), "旧设备", FixedNow - 86_400_000));
        var guestStore = new InMemoryPairedDeviceStore();
        using var host = new PairingPresenter("dev-host", registry: new PairedDeviceRegistry(hostStore));
        using var guest = new PairingPresenter("dev-guest", registry: new PairedDeviceRegistry(guestStore));

        (PairingHostSnapshot snapshot, _) = await PairAsync(host, guest);

        Assert.Equal(PairingHostState.Failed, snapshot.State);
        Assert.Equal(PairedDeviceReasons.FingerprintMismatch, snapshot.Reason);
        Assert.Equal(PairingStepCodes.HostFailed, snapshot.StepCode);
        Assert.Contains("指纹", snapshot.NextStep);
        PairedDevice stored = Assert.Single(hostStore.GetAll());
        Assert.Equal(Hex(9), stored.Fingerprint);
        Assert.Equal("旧设备", stored.DisplayName);
    }

    [Fact]
    public async Task Presenter_WithoutARegistryKeepsTheOneWayExchange()
    {
        using var host = new PairingPresenter("dev-host");
        using var guest = new PairingPresenter("dev-guest");

        (PairingHostSnapshot snapshot, PairingGuestResult result) = await PairAsync(host, guest);

        Assert.Equal(PairingHostState.Paired, snapshot.State);
        Assert.Null(host.PeerDeviceId);
        Assert.Null(host.PeerFingerprintDisplay);
        Assert.Null(host.Registry);
        Assert.True(result.Paired);
        Assert.Equal("dev-host", result.PeerDeviceId);
        Assert.False(Directory.Exists(_root) && Directory.GetFiles(_root).Length > 0);
    }

    // ---------------------------------------------------------------- helpers

    private static PairedDeviceObservation Observe(string deviceId, string fingerprint, long? now = null, string name = "", int sources = 0) => new()
    {
        DeviceId = deviceId,
        Fingerprint = fingerprint,
        DisplayName = name,
        SourceCount = sources,
        NowEpochMs = now ?? FixedNow,
    };

    private IReadOnlyList<string> TemporaryFiles() => Directory.Exists(_root)
        ? Directory.GetFiles(_root, "*.tmp", SearchOption.AllDirectories)
        : [];

    /// <summary>32 deterministic bytes rendered as lowercase hex — a well-formed fingerprint.</summary>
    private static string Hex(int seed)
    {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.Length; index++)
        {
            bytes[index] = (byte)(seed + index);
        }

        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    private static PairingPayload Payload() => PairingPayload.Create(
        "dev-host",
        "https://127.0.0.1:47831/pairing",
        PairingChallenge.Create(),
        PairingFingerprint.FromBytes(Convert.FromHexString(Hex(7))),
        FixedNow);

    private static async Task<(PairingHostSnapshot Snapshot, PairingGuestResult Result)> PairAsync(
        PairingPresenter host,
        PairingPresenter guest,
        long? now = null)
    {
        long clock = now ?? FixedNow;
        PairingHostSnapshot snapshot = host.StartHost(clock);
        Assert.True(snapshot.IsListening, snapshot.NextStep);
        string code = host.PairingCodeText;
        Assert.NotEmpty(code);

        Task<PairingGuestResult> guestTask = guest.AcceptGuestAsync(code, clock);
        for (int attempt = 0; attempt < 400 && !snapshot.IsTerminal; attempt++)
        {
            await Task.Delay(25);
            snapshot = host.PollHost(clock);
        }

        PairingGuestResult result = await guestTask;
        return (snapshot, result);
    }

    /// <summary>A store that fails every write, standing in for a read-only or locked profile folder.</summary>
    private sealed class ThrowingPairedDeviceStore : IPairedDeviceStore
    {
        public string? LoadError => null;

        public IReadOnlyList<PairedDevice> GetAll() => [];

        public void Save(PairedDevice device) => throw new IOException("disk is full");

        public bool Forget(string deviceId) => throw new IOException("disk is full");
    }
}

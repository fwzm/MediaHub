using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Cross-language contract for paired device records (W6-70): the 12 reason codes, the 5 store codes,
/// the capacity and forget semantics, the list presentation strings and the exact bytes of paired-devices.json.
/// </summary>
/// <remarks>
/// <para>
/// Every expectation comes from the vector file rather than from a hand-written literal: the same JSON is
/// consumed by the Android twin <c>com.mediahub.core.common.pairing.PairedDeviceContractTest</c>, so a
/// deliberate divergence on either side fails one of the two suites instead of shipping.
/// </para>
/// <para>
/// The file is committed at <c>core/common/src/test/resources/pairing/paired-device-vectors.json</c>
/// (Android) and at <c>windows/tests/assets/paired-device-vectors.json</c> (here); the two copies are
/// byte-identical and are produced by <c>D:/mh-verify/paired-device-vectors</c>.
/// </para>
/// </remarks>
public sealed class PairedDeviceContractTests
{
    private static readonly JsonDocument Vectors = JsonDocument.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "assets", "paired-device-vectors.json")));

    private static readonly JsonElement Root = Vectors.RootElement;

    private static readonly long FrozenNow = Root.GetProperty("frozenNowEpochMs").GetInt64();

    private static readonly string FingerprintA = new('a', 64);

    private static readonly string FingerprintB = new('b', 64);

    private static readonly string FingerprintC = new('c', 64);

    // ------------------------------------------------------------- helpers

    private static JsonElement Entries(string name) => Root.GetProperty(name);

    private static string TextOf(JsonElement element) => element.GetString()!;

    private static string TextOf(JsonElement entry, string name) => TextOf(entry.GetProperty(name));

    private static string? NullableTextOf(JsonElement entry, string name)
    {
        if (!entry.TryGetProperty(name, out JsonElement element) || element.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return element.GetString();
    }

    private static bool BoolOf(JsonElement entry, string name) => entry.GetProperty(name).GetBoolean();

    private static int IntOf(JsonElement entry, string name) => entry.GetProperty(name).GetInt32();

    private static long LongOf(JsonElement entry, string name) => entry.GetProperty(name).GetInt64();

    /// <summary>
    /// Fills the two local-time tokens the vector file uses instead of pinning a rendered local time.
    /// The same epoch renders differently on a UTC CI runner than on the authoring machine, so the contract
    /// is "this label followed by the platform's own local rendering", and every other byte stays pinned.
    /// </summary>
    private static string ExpandTokens(string text, PairedDevice device)
    {
        if (text.Contains("{pairedAt}", StringComparison.Ordinal))
        {
            text = text.Replace("{pairedAt}", PairedDeviceListPresenter.FormatEpoch(device.PairedAtEpochMs), StringComparison.Ordinal);
        }

        if (text.Contains("{lastSeen}", StringComparison.Ordinal))
        {
            text = text.Replace("{lastSeen}", PairedDeviceListPresenter.FormatEpoch(device.LastSeenEpochMs), StringComparison.Ordinal);
        }

        return text;
    }

    private static PairedDevice DeviceOf(JsonElement element) => new()
    {
        DeviceId = TextOf(element, "deviceId"),
        DisplayName = TextOf(element, "displayName"),
        Fingerprint = TextOf(element, "fingerprint"),
        PairedAtEpochMs = LongOf(element, "pairedAtEpochMs"),
        LastSeenEpochMs = LongOf(element, "lastSeenEpochMs"),
        SourceCount = IntOf(element, "sourceCount"),
    };

    private static PairedFingerprintState StateOf(string name) => name switch
    {
        "new" => PairedFingerprintState.New,
        "match" => PairedFingerprintState.Match,
        "mismatch" => PairedFingerprintState.Mismatch,
        _ => throw new InvalidOperationException("unknown state " + name),
    };

    private static string StateName(PairedFingerprintState state) => state switch
    {
        PairedFingerprintState.New => "new",
        PairedFingerprintState.Match => "match",
        PairedFingerprintState.Mismatch => "mismatch",
        _ => "unknown",
    };

    private static PairedDeviceObservation ObservationOf(JsonElement element) => new()
    {
        DeviceId = TextOf(element, "deviceId"),
        Fingerprint = TextOf(element, "fingerprint"),
        DisplayName = TextOf(element, "displayName"),
        SourceCount = IntOf(element, "sourceCount"),
        NowEpochMs = LongOf(element, "nowEpochMs"),
    };

    private static PairedDeviceRegistry SeededRegistry(out InMemoryPairedDeviceStore store)
    {
        store = new InMemoryPairedDeviceStore();
        store.Save(PairedDevice.Create("device-a", FingerprintA, "家庭 NAS（客厅）", FrozenNow, 3));
        store.Save(PairedDevice.Create("device-b", FingerprintB, "", FrozenNow, 0));
        return new PairedDeviceRegistry(store);
    }

    private static void WithTempDirectory(Action<string> block)
    {
        string directory = Path.Combine(Path.GetTempPath(), "mh-paired-device-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            block(directory);
        }
        finally
        {
            Directory.Delete(directory, true);
        }
    }

    private static string Sha256(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    // ------------------------------------------------------------- codes

    [Fact]
    public void ReasonCodesAndTheirDescriptionsMatchTheWindowsContract()
    {
        JsonElement cases = Entries("reasonCodes");
        Assert.Equal(12, cases.GetArrayLength());
        Assert.Equal(
            cases.EnumerateArray().Select(entry => TextOf(entry, "code")).ToArray(),
            PairedDeviceReasons.All);
        foreach (JsonElement entry in cases.EnumerateArray())
        {
            string code = TextOf(entry, "code");
            Assert.True(TextOf(entry, "description") == PairedDeviceRegistry.DescribeReason(code), code + " description");
        }
    }

    [Fact]
    public void StoreReasonCodesMatchTheWindowsContract()
    {
        JsonElement codes = Entries("storeReasonCodes");
        Assert.Equal(5, codes.GetArrayLength());
        Assert.Equal(
            codes.EnumerateArray().Select(TextOf).ToArray(),
            PairedDeviceStoreReasons.All);
    }

    [Fact]
    public void StateLabelsMatchTheWindowsContract()
    {
        JsonElement labels = Root.GetProperty("stateLabels");
        Assert.True(TextOf(labels, "new") == PairedDeviceRegistry.DescribeState(PairedFingerprintState.New), "new");
        Assert.True(TextOf(labels, "match") == PairedDeviceRegistry.DescribeState(PairedFingerprintState.Match), "match");
        Assert.True(TextOf(labels, "mismatch") == PairedDeviceRegistry.DescribeState(PairedFingerprintState.Mismatch), "mismatch");
    }

    [Fact]
    public void DescribeReasonCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("describeReasonCases").EnumerateArray())
        {
            string? reason = NullableTextOf(entry, "reason");
            Assert.True(
                TextOf(entry, "text") == PairedDeviceRegistry.DescribeReason(reason),
                (reason ?? "<null>") + " expected " + TextOf(entry, "text") + " but got " + PairedDeviceRegistry.DescribeReason(reason));
        }
    }

    // ------------------------------------------------------------- create

    [Fact]
    public void CreateCasesMatchTheWindowsContract()
    {
        var produced = new Dictionary<string, PairedDevice>(StringComparer.Ordinal)
        {
            ["trims-and-lowercases"] = PairedDevice.Create(" device-a ", FingerprintA.ToUpperInvariant(), " 家庭 NAS ", FrozenNow, 3),
            ["truncates-long-name"] = PairedDevice.Create("device-b", FingerprintB, new string('x', 200), FrozenNow, 0),
            ["negative-source-count"] = PairedDevice.Create("device-c", FingerprintC, "", FrozenNow, -5),
            ["keeps-explicit-paired-at"] = PairedDevice.Create("device-d", FingerprintA, null, FrozenNow, 1, FrozenNow - 5000),
        };
        foreach (JsonElement entry in Entries("createCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            Assert.True(DeviceOf(entry.GetProperty("device")) == produced[name], name + " device");
        }
    }

    // ------------------------------------------------------------- verify

    [Fact]
    public void VerifyCasesMatchTheWindowsContract()
    {
        PairedDeviceRegistry registry = SeededRegistry(out _);
        foreach (JsonElement entry in Entries("verifyCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            PairedDeviceVerification actual = registry.Verify(NullableTextOf(entry, "deviceId"), NullableTextOf(entry, "fingerprint"));
            Assert.True(TextOf(entry, "state") == StateName(actual.State), name + " state");
            Assert.True(TextOf(entry, "reason") == actual.Reason, name + " reason");
            Assert.True(TextOf(entry, "presentedFingerprint") == actual.PresentedFingerprint, name + " presented");
            Assert.True(NullableTextOf(entry, "pinnedFingerprint") == actual.PinnedFingerprint, name + " pinned");
            Assert.True(BoolOf(entry, "canPin") == actual.CanPin, name + " canPin");
        }
    }

    // ------------------------------------------------------------- register

    [Fact]
    public void RegisterCasesMatchTheWindowsContract()
    {
        var store = new InMemoryPairedDeviceStore();
        var registry = new PairedDeviceRegistry(store);
        foreach (JsonElement entry in Entries("registerCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            if (TextOf(entry, "kind") == "final")
            {
                Assert.Equal(
                    entry.GetProperty("deviceIds").EnumerateArray().Select(TextOf).ToArray(),
                    registry.GetAll().Select(device => device.DeviceId).ToArray());
                Assert.True(IntOf(entry, "count") == registry.Count, name + " count");
                Assert.True(BoolOf(entry, "isFull") == registry.IsFull, name + " isFull");
                continue;
            }

            PairedDeviceRegistration actual = registry.Register(ObservationOf(entry.GetProperty("observation")));
            Assert.True(BoolOf(entry, "accepted") == actual.Accepted, name + " accepted");
            Assert.True(BoolOf(entry, "added") == actual.Added, name + " added");
            Assert.True(TextOf(entry, "state") == StateName(actual.State), name + " state");
            Assert.True(TextOf(entry, "reason") == actual.Reason, name + " reason");
            if (!entry.TryGetProperty("device", out JsonElement expectedDevice) || expectedDevice.ValueKind == JsonValueKind.Null)
            {
                Assert.Null(actual.Device);
            }
            else
            {
                Assert.True(DeviceOf(expectedDevice) == actual.Device, name + " device");
            }
        }
    }

    [Fact]
    public void CapacityCasesMatchTheWindowsContract()
    {
        var store = new InMemoryPairedDeviceStore();
        var registry = new PairedDeviceRegistry(store, capacity: 2);
        foreach (JsonElement entry in Entries("capacityCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            switch (TextOf(entry, "kind"))
            {
                case "register":
                    PairedDeviceRegistration actual = registry.Register(ObservationOf(entry.GetProperty("observation")));
                    Assert.True(BoolOf(entry, "accepted") == actual.Accepted, name + " accepted");
                    Assert.True(BoolOf(entry, "added") == actual.Added, name + " added");
                    Assert.True(TextOf(entry, "state") == StateName(actual.State), name + " state");
                    Assert.True(TextOf(entry, "reason") == actual.Reason, name + " reason");
                    break;
                case "full":
                    Assert.True(BoolOf(entry, "isFull") == registry.IsFull, name + " isFull");
                    break;
                case "forget":
                    Assert.True(BoolOf(entry, "result") == registry.Forget(TextOf(entry, "deviceId")), name + " result");
                    break;
                case "final":
                    Assert.Equal(
                        entry.GetProperty("deviceIds").EnumerateArray().Select(TextOf).ToArray(),
                        registry.GetAll().Select(device => device.DeviceId).ToArray());
                    break;
                default:
                    throw new InvalidOperationException("unknown case kind");
            }
        }
    }

    [Fact]
    public void ForgetCasesMatchTheWindowsContract()
    {
        PairedDeviceRegistry registry = SeededRegistry(out InMemoryPairedDeviceStore store);
        foreach (JsonElement entry in Entries("forgetCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            switch (TextOf(entry, "kind"))
            {
                case "forget":
                    Assert.True(BoolOf(entry, "result") == registry.Forget(NullableTextOf(entry, "deviceId")), name + " result");
                    break;
                case "verify":
                    PairedDeviceVerification verification = registry.Verify("device-a", FingerprintA);
                    Assert.True(TextOf(entry, "state") == StateName(verification.State), name + " state");
                    Assert.True(TextOf(entry, "reason") == verification.Reason, name + " reason");
                    break;
                case "final":
                    Assert.Equal(
                        entry.GetProperty("deviceIds").EnumerateArray().Select(TextOf).ToArray(),
                        registry.GetAll().Select(device => device.DeviceId).ToArray());
                    Assert.True(IntOf(entry, "writeCount") == store.WriteCount, name + " writeCount");
                    break;
                default:
                    throw new InvalidOperationException("unknown case kind");
            }
        }
    }

    // ------------------------------------------------------------- describe

    [Fact]
    public void DescribeCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("describeCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            PairedDevice device = DeviceOf(entry.GetProperty("device"));
            long now = LongOf(entry, "nowEpochMs");
            string actual = device.Describe(now);
            Assert.True(actual == PairedDeviceRegistry.Describe(device, now), name + " registry describe");
            string? expected = NullableTextOf(entry, "describe");
            if (expected is not null)
            {
                Assert.True(ExpandTokens(expected, device) == actual, name + " describe");
            }

            string? suffix = NullableTextOf(entry, "describeSuffix");
            if (suffix is not null)
            {
                // The clock-skew note sits before the trailing " · 来源 N 个" segment.
                Assert.True(actual.Contains(suffix, StringComparison.Ordinal), name + " suffix");
            }

            string? displayNameOrId = NullableTextOf(entry, "displayNameOrId");
            if (displayNameOrId is not null)
            {
                Assert.True(displayNameOrId == device.DisplayNameOrId, name + " displayNameOrId");
                Assert.True(TextOf(entry, "fingerprintDisplay") == device.FingerprintDisplay, name + " fingerprintDisplay");
            }
        }
    }

    // ------------------------------------------------------------- presenter

    [Fact]
    public void PresenterCasesMatchTheWindowsContract()
    {
        PairedDevice[] two =
        {
            PairedDevice.Create("device-android-01", FingerprintA, "家庭 NAS（客厅）", FrozenNow, 3, pairedAtEpochMs: 0L),
            PairedDevice.Create("abc", FingerprintB, "", FrozenNow, 0, pairedAtEpochMs: 0L),
        };
        PairedDevice[] full =
        {
            PairedDevice.Create("device-a", FingerprintA, "", FrozenNow, 0, pairedAtEpochMs: 0L),
            PairedDevice.Create("device-b", FingerprintB, "", FrozenNow, 0, pairedAtEpochMs: 0L),
        };
        var devices = new Dictionary<string, PairedDevice[]>(StringComparer.Ordinal)
        {
            ["empty"] = Array.Empty<PairedDevice>(),
            ["two-devices"] = two,
            ["full-at-two"] = full,
            ["null-is-empty"] = Array.Empty<PairedDevice>(),
        };
        var produced = new Dictionary<string, PairedDeviceListView>(StringComparer.Ordinal)
        {
            ["empty"] = PairedDeviceListPresenter.Build(Array.Empty<PairedDevice>(), PairedDeviceRegistry.DefaultCapacity, FrozenNow),
            ["two-devices"] = PairedDeviceListPresenter.Build(two, PairedDeviceRegistry.DefaultCapacity, FrozenNow),
            ["full-at-two"] = PairedDeviceListPresenter.Build(full, 2, FrozenNow),
            ["null-is-empty"] = PairedDeviceListPresenter.Build(null, 0, FrozenNow),
        };

        foreach (JsonElement entry in Entries("presenterCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            PairedDeviceListView actual = produced[name];
            Assert.True(TextOf(entry, "countLabel") == actual.CountLabel, name + " countLabel");
            Assert.True(TextOf(entry, "emptyLabel") == actual.EmptyLabel, name + " emptyLabel");
            Assert.True(TextOf(entry, "tofuLabel") == actual.TofuLabel, name + " tofuLabel");
            Assert.True(TextOf(entry, "capacityLabel") == actual.CapacityLabel, name + " capacityLabel");
            Assert.True(BoolOf(entry, "isEmpty") == actual.IsEmpty, name + " isEmpty");
            Assert.True(BoolOf(entry, "isFull") == actual.IsFull, name + " isFull");
            Assert.True(IntOf(entry, "count") == actual.Count, name + " count");
            Assert.True(IntOf(entry, "capacity") == actual.Capacity, name + " capacity");
            JsonElement expectedRows = entry.GetProperty("rows");
            Assert.Equal(expectedRows.GetArrayLength(), actual.Rows.Count);
            for (int index = 0; index < expectedRows.GetArrayLength(); index++)
            {
                JsonElement expected = expectedRows[index];
                PairedDeviceRow row = actual.Rows[index];
                PairedDevice device = devices[name][index];
                string label = name + " row " + index;
                Assert.True(TextOf(expected, "deviceId") == row.DeviceId, label + " deviceId");
                Assert.True(TextOf(expected, "displayName") == row.DisplayName, label + " displayName");
                Assert.True(TextOf(expected, "fingerprintLabel") == row.FingerprintLabel, label + " fingerprintLabel");
                Assert.True(ExpandTokens(TextOf(expected, "pairedLabel"), device) == row.PairedLabel, label + " pairedLabel");
                Assert.True(ExpandTokens(TextOf(expected, "lastSeenLabel"), device) == row.LastSeenLabel, label + " lastSeenLabel");
                Assert.True(TextOf(expected, "sourceLabel") == row.SourceLabel, label + " sourceLabel");
                Assert.True(ExpandTokens(TextOf(expected, "summaryLine"), device) == row.SummaryLine, label + " summaryLine");
                Assert.True(ExpandTokens(TextOf(expected, "detailLine"), device) == row.DetailLine, label + " detailLine");
                Assert.True(TextOf(expected, "forgetLabel") == row.ForgetLabel, label + " forgetLabel");
                Assert.True(LongOf(expected, "lastSeenEpochMs") == row.LastSeenEpochMs, label + " lastSeenEpochMs");
            }
        }
    }

    // ------------------------------------------------------------- document bytes

    [Fact]
    public void DocumentCasesAreWrittenByteIdentically()
    {
        foreach (JsonElement entry in Entries("documentCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            PairedDevice[] devices = entry.GetProperty("devices").EnumerateArray().Select(DeviceOf).ToArray();
            WithTempDirectory(directory =>
            {
                var store = new FilePairedDeviceStore(directory);
                if (devices.Length == 0)
                {
                    store.Save(PairedDevice.Create("placeholder", FingerprintA, "", FrozenNow, 0));
                    store.Forget("placeholder");
                }
                else
                {
                    foreach (PairedDevice device in devices)
                    {
                        store.Save(device);
                    }
                }

                byte[] bytes = File.ReadAllBytes(store.FilePath);
                Assert.True(IntOf(entry, "utf8ByteLength") == bytes.Length, name + " byte length");
                Assert.True(TextOf(entry, "sha256") == Sha256(bytes), name + " sha256");
                Assert.True(TextOf(entry, "base64") == Convert.ToBase64String(bytes), name + " base64");

                // The writer stores insertion order, but GetAll() returns the canonical order
                // (deviceId OrdinalIgnoreCase, then Ordinal); compare against that canonical order.
                PairedDevice[] canonical = devices
                    .OrderBy(device => device.DeviceId, StringComparer.OrdinalIgnoreCase)
                    .ThenBy(device => device.DeviceId, StringComparer.Ordinal)
                    .ToArray();
                var reopened = new FilePairedDeviceStore(directory);
                Assert.Equal(canonical, reopened.GetAll());
                Assert.Null(reopened.LoadError);
            });
        }
    }

    [Fact]
    public void LoadCasesMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("loadCases").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            WithTempDirectory(directory =>
            {
                File.WriteAllText(
                    Path.Combine(directory, FilePairedDeviceStore.FileName),
                    TextOf(entry, "text"),
                    new UTF8Encoding(false));
                var store = new FilePairedDeviceStore(directory);
                IReadOnlyList<PairedDevice> devices = store.GetAll();
                Assert.True(NullableTextOf(entry, "loadError") == store.LoadError, name + " loadError");
                Assert.True(IntOf(entry, "deviceCount") == devices.Count, name + " count");
                Assert.Equal(entry.GetProperty("devices").EnumerateArray().Select(DeviceOf).ToArray(), devices);
            });
        }
    }

    [Fact]
    public void WriteRejectsMatchTheWindowsContract()
    {
        foreach (JsonElement entry in Entries("writeRejects").EnumerateArray())
        {
            string name = TextOf(entry, "name");
            string outcome = "accepted";
            WithTempDirectory(directory =>
            {
                try
                {
                    switch (name)
                    {
                        case "blank-identity":
                            new FilePairedDeviceStore(directory)
                                .Save(PairedDevice.Create("device-a", FingerprintA, "", FrozenNow, 0) with { DeviceId = "   " });
                            break;
                        case "capacity-limit":
                            var store = new FilePairedDeviceStore(directory) { Capacity = 1 };
                            store.Save(PairedDevice.Create("device-a", FingerprintA, "", FrozenNow, 0));
                            store.Save(PairedDevice.Create("device-b", FingerprintB, "", FrozenNow, 0));
                            break;
                    }
                }
                catch (ArgumentException)
                {
                    outcome = "argument-exception";
                }
                catch (IOException exception)
                {
                    outcome = exception.Message;
                }
            });
            Assert.True(TextOf(entry, "outcome") == outcome, name + " expected " + TextOf(entry, "outcome") + " but got " + outcome);
        }
    }
}

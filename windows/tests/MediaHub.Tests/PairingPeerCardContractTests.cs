using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Cross-language contract for the pairing peer card and the self-declared identity (W6-71).
/// </summary>
/// <remarks>
/// <para>
/// Every expectation comes from the vector file rather than from a hand-written literal: the same JSON is
/// consumed by the Android twin <c>com.mediahub.core.common.pairing.PairingPeerCardContractTest</c>, so a
/// deliberate divergence on either side fails one of the two suites instead of shipping.
/// </para>
/// <para>
/// The section lives in <c>windows-pairing-vectors.json</c> (key <c>peerCard</c>), which is committed at
/// <c>core/common/src/test/resources/pairing/</c> (Android) and at <c>windows/tests/assets/</c> (here); the two
/// copies are byte-identical and are produced by <c>D:/mh-verify/pairing-vectors</c>.
/// </para>
/// </remarks>
public sealed class PairingPeerCardContractTests
{
    private static readonly JsonDocument Vectors = JsonDocument.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "assets", "windows-pairing-vectors.json")));

    private static readonly JsonElement Card = Vectors.RootElement.GetProperty("peerCard");

    // ------------------------------------------------------------- helpers

    private static JsonElement Entries(string name) => Card.GetProperty(name);

    private static string TextOf(JsonElement element) => element.GetString()!;

    private static string TextOf(JsonElement entry, string name) => TextOf(entry.GetProperty(name));

    private static int IntOf(JsonElement entry, string name) => entry.GetProperty(name).GetInt32();

    private static long LongOf(JsonElement entry, string name) => entry.GetProperty(name).GetInt64();

    private static PairingPeerCard CardOf(JsonElement entry) => new()
    {
        DeviceId = TextOf(entry, "deviceId"),
        DeviceFingerprint = TextOf(entry, "deviceFingerprint"),
        DisplayName = TextOf(entry, "displayName"),
        SourceCount = IntOf(entry, "sourceCount"),
    };

    private static string Sha256(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    // ------------------------------------------------------------- codes and limits

    [Fact]
    public void ReasonCodesAndLimitsMatchTheWindowsContract()
    {
        string[] reasons = Entries("reasons").EnumerateArray().Select(TextOf).ToArray();
        Assert.Equal(PairingPeerCardReasons.All, reasons);
        Assert.Equal(6, PairingPeerCardReasons.All.Count);
        Assert.Equal(IntOf(Card, "currentSchemaVersion"), PairingPeerCard.CurrentSchemaVersion);
        Assert.Equal(IntOf(Card, "maximumTextLength"), PairingPeerCard.MaximumTextLength);
        Assert.Equal(IntOf(Card, "maximumLength"), PairingPeerCardCodec.MaximumLength);
        Assert.Equal(TextOf(Card, "derivationPrefix"), PairingSelfIdentity.DerivationPrefix);
    }

    // ------------------------------------------------------------- writing

    [Fact]
    public void SerializedCardsMatchTheWindowsBytes()
    {
        JsonElement[] cases = Entries("serializationCases").EnumerateArray().ToArray();
        Assert.True(cases.Length >= 6, "expected at least six serialization cases");
        foreach (JsonElement entry in cases)
        {
            string name = TextOf(entry, "name");
            string encoded = PairingPeerCardCodec.Serialize(CardOf(entry));
            Assert.True(TextOf(entry, "json") == encoded, name + " json expected " + TextOf(entry, "json") + " but got " + encoded);
            byte[] bytes = Encoding.UTF8.GetBytes(encoded);
            Assert.True(IntOf(entry, "utf8ByteLength") == bytes.Length, name + " byte length");
            Assert.True(TextOf(entry, "sha256") == Sha256(bytes), name + " sha256");
        }
    }

    // ------------------------------------------------------------- reading

    [Fact]
    public void DecodedCardsMatchTheWindowsContract()
    {
        JsonElement[] cases = Entries("decodeCases").EnumerateArray().ToArray();
        Assert.True(cases.Length >= 20, "expected at least twenty decode cases");
        var seenReasons = new HashSet<string>(StringComparer.Ordinal);
        foreach (JsonElement entry in cases)
        {
            string name = TextOf(entry, "name");
            bool accepted = entry.GetProperty("accepted").GetBoolean();
            bool ok = PairingPeerCardCodec.TryDeserialize(TextOf(entry, "json"), out PairingPeerCard? card, out string? error);
            if (!accepted)
            {
                Assert.True(!ok, name + " should have been rejected");
                Assert.True(TextOf(entry, "reason") == error, name + " expected " + TextOf(entry, "reason") + " but got " + error);
                seenReasons.Add(error!);
                continue;
            }

            Assert.True(ok, name + " should have been accepted, got " + error);
            JsonElement expected = entry.GetProperty("card");
            Assert.True(TextOf(expected, "deviceId") == card!.DeviceId, name + " deviceId");
            Assert.True(TextOf(expected, "deviceFingerprint") == card.DeviceFingerprint, name + " fingerprint");
            Assert.True(TextOf(expected, "displayName") == card.DisplayName, name + " displayName");
            Assert.True(IntOf(expected, "sourceCount") == card.SourceCount, name + " sourceCount");
            Assert.True(PairingPeerCard.CurrentSchemaVersion == card.SchemaVersion, name + " schemaVersion");
        }

        // All six codes must be exercised, otherwise one refusal path is unpinned.
        Assert.True(PairingPeerCardReasons.All.ToHashSet(StringComparer.Ordinal).SetEquals(seenReasons), "not every refusal code is covered");
    }

    // ------------------------------------------------------------- identity

    [Fact]
    public void SelfIdentityCasesMatchTheWindowsDerivation()
    {
        JsonElement[] cases = Entries("selfIdentityCases").EnumerateArray().ToArray();
        Assert.True(cases.Length >= 5, "expected at least five identity cases");
        foreach (JsonElement entry in cases)
        {
            string name = TextOf(entry, "name");
            string deviceId = TextOf(entry, "deviceId");
            Assert.True(TextOf(entry, "fingerprintHex") == PairingSelfIdentity.DeriveFingerprint(deviceId).Hex, name + " derived fingerprint");
            PairingSelfIdentity identity = PairingSelfIdentity.Create(deviceId, "  安卓平板  ", -2);
            Assert.True(TextOf(entry, "trimmedDeviceId") == identity.DeviceId, name + " trimmed id");
            Assert.True(TextOf(entry, "displayName") == identity.DisplayName, name + " displayName trimmed");
            Assert.True(IntOf(entry, "sourceCount") == identity.SourceCount, name + " sourceCount clamped");
            Assert.True(TextOf(entry, "cardJson") == PairingPeerCardCodec.Serialize(identity.ToCard()), name + " card json");
        }
    }

    [Fact]
    public void BlankIdentityIsRejectedLikeTheWindowsContract()
    {
        Assert.True(Entries("rejectsBlankIdentity").GetBoolean(), "the vector must declare that a blank identity is refused");
        Assert.Throws<ArgumentException>(() => PairingSelfIdentity.Create("   "));
        Assert.Throws<ArgumentException>(() => PairingSelfIdentity.DeriveFingerprint(""));
    }

    // ------------------------------------------------------------- observation

    [Fact]
    public void ObservationCaseCarriesTheCallersClock()
    {
        JsonElement expected = Card.GetProperty("observationCase");
        PairingPeerCard card = new()
        {
            DeviceId = TextOf(expected, "deviceId"),
            DeviceFingerprint = TextOf(expected, "fingerprint"),
            DisplayName = TextOf(expected, "displayName"),
            SourceCount = IntOf(expected, "sourceCount"),
        };
        PairedDeviceObservation observation = card.ToObservation(LongOf(expected, "nowEpochMs"));
        Assert.True(TextOf(expected, "deviceId") == observation.DeviceId, "observation deviceId");
        Assert.True(TextOf(expected, "fingerprint") == observation.Fingerprint, "observation fingerprint");
        Assert.True(TextOf(expected, "displayName") == observation.DisplayName, "observation displayName");
        Assert.True(IntOf(expected, "sourceCount") == observation.SourceCount, "observation sourceCount");
        Assert.True(LongOf(expected, "nowEpochMs") == observation.NowEpochMs, "observation clock");
    }

    // ------------------------------------------------------------- hostile input

    [Fact]
    public void DecodeNeverThrowsOnHostileText()
    {
        string good = new string('a', 64);
        string[] hostile =
        [
            "",
            "   ",
            "{",
            "[]",
            "{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"zz\"}",
            "{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"" + good + "\",\"displayName\":\"\\uD800\"}",
        ];
        foreach (string json in hostile)
        {
            bool ok = PairingPeerCardCodec.TryDeserialize(json, out PairingPeerCard? card, out string? error);
            Assert.True(!ok && card is null && error is not null, "should have been refused: " + json);
        }

        Assert.True(!PairingPeerCardCodec.TryDeserialize(null, out _, out string? nullError), "null should have been refused");
        Assert.True(PairingPeerCardReasons.MalformedJson == nullError, "null reason");

        string longName = new string((char)0x78, PairingPeerCard.MaximumTextLength + 5);
        string truncation = "{\"schemaVersion\":1,\"deviceId\":\"d\",\"deviceFingerprint\":\"" + good + "\",\"displayName\":\"" + longName + "\"}";
        Assert.True(PairingPeerCardCodec.TryDeserialize(truncation, out PairingPeerCard? truncated, out string? truncationError), "an over-long name must be truncated, not refused: " + truncationError);
        Assert.True(PairingPeerCard.MaximumTextLength == truncated!.DisplayName.Length, "truncated name length");
    }
}

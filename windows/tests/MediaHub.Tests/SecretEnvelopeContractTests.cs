using System.Text;
using System.Text.Json;
using MediaHub.Sync;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Pins the shape of a sealed credential block: what a valid one looks like, and every route a plaintext
/// secret could take to pass as one. All of it is validation of a claim, so a rejection must never depend on
/// being able to decrypt anything.
/// </summary>
public sealed class SecretEnvelopeContractTests
{
    [Fact]
    public void SchemeNames_AreStableWireValues()
    {
        Assert.Equal("dpapi-user", SecretEnvelope.SchemeDpapiUser);
        Assert.Equal("dpapi-machine", SecretEnvelope.SchemeDpapiMachine);
        Assert.Equal("none", SecretEnvelope.SchemeNone);
        Assert.Equal(new[] { "dpapi-user", "dpapi-machine", "none" }, SecretEnvelope.Schemes);
    }

    [Fact]
    public void Validate_RejectsASchemeThisBuildDoesNotKnow()
    {
        var envelope = new SecretEnvelope
        {
            Scheme = "dpapi-service",
            Scope = "svc",
            PayloadBase64 = SealedLookingPayload(),
            ProtectedAtEpochMs = 1,
        };

        SecretException error = Assert.Throws<SecretException>(() => envelope.Validate());

        Assert.Equal(SecretError.SchemeUnknown, error.Reason);
    }

    [Fact]
    public void Validate_RejectsASealedBlockWithoutAScope()
    {
        var envelope = new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeDpapiUser,
            Scope = "   ",
            PayloadBase64 = SealedLookingPayload(),
        };

        Assert.Equal(SecretError.ScopeEmpty, Assert.Throws<SecretException>(() => envelope.Validate()).Reason);
    }

    [Fact]
    public void Validate_RejectsASealedBlockWithoutAPayload()
    {
        var envelope = new SecretEnvelope { Scheme = SecretEnvelope.SchemeDpapiUser, Scope = "alice" };

        Assert.Equal(SecretError.PayloadEmpty, Assert.Throws<SecretException>(() => envelope.Validate()).Reason);
    }

    [Fact]
    public void Validate_RejectsAPayloadOnTheEmptyScheme()
    {
        var envelope = new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeNone,
            PayloadBase64 = SealedLookingPayload(),
        };

        Assert.Equal(SecretError.PayloadNotEmpty, Assert.Throws<SecretException>(() => envelope.Validate()).Reason);
    }

    [Fact]
    public void Validate_RejectsAPayloadThatIsNotBase64()
    {
        var envelope = new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeDpapiUser,
            Scope = "alice",
            PayloadBase64 = "this is not base64 at all!",
        };

        Assert.Equal(SecretError.PayloadNotBase64, Assert.Throws<SecretException>(() => envelope.Validate()).Reason);
    }

    [Fact]
    public void Validate_RejectsAPasswordWearingABase64Coat()
    {
        // "password1234" happens to be valid base64; it decodes to 12 bytes, which is not a DPAPI blob.
        string disguised = Convert.ToBase64String(Encoding.UTF8.GetBytes("password1234"));
        var envelope = new SecretEnvelope
        {
            Scheme = SecretEnvelope.SchemeDpapiUser,
            Scope = "alice",
            PayloadBase64 = disguised,
        };

        Assert.Equal(SecretError.PayloadTooShort, Assert.Throws<SecretException>(() => envelope.Validate()).Reason);
    }

    [Fact]
    public void Validate_AcceptsASealedLookingPayload()
    {
        SecretEnvelope envelope = ValidEnvelope();

        Assert.Same(envelope, envelope.Validate());
        Assert.True(envelope.IsSealed);
        Assert.False(envelope.IsEmpty);
        Assert.True(envelope.IsKnownScheme);
        Assert.Equal(SecretEnvelope.MinSealedBytes, Math.Min(SecretEnvelope.MinSealedBytes, envelope.ToPayloadBytes().Length));
    }

    [Fact]
    public void FromPlaintext_AlwaysRefuses()
    {
        SecretException error = Assert.Throws<SecretException>(() => SecretEnvelope.FromPlaintext("hunter2"));

        Assert.Equal(SecretError.PlaintextRejected, error.Reason);
    }

    [Fact]
    public void Empty_IsTheExplicitNoCredentialBlock()
    {
        SecretEnvelope empty = SecretEnvelope.Empty("alice");

        Assert.True(empty.IsEmpty);
        Assert.False(empty.IsSealed);
        Assert.Same(empty, empty.Validate());
        Assert.Equal("none", SecretEnvelopeCodec.Deserialize(SecretEnvelopeCodec.Serialize(empty)).Scheme);
    }

    [Fact]
    public void Codec_RoundTripsEveryField()
    {
        SecretEnvelope original = ValidEnvelope();

        SecretEnvelope read = SecretEnvelopeCodec.Deserialize(SecretEnvelopeCodec.Serialize(original));

        Assert.Equal(original.Scheme, read.Scheme);
        Assert.Equal(original.Scope, read.Scope);
        Assert.Equal(original.PayloadBase64, read.PayloadBase64);
        Assert.Equal(original.ProtectedAtEpochMs, read.ProtectedAtEpochMs);
    }

    [Fact]
    public void Codec_WritesTheContractFieldNamesInCamelCase()
    {
        string json = SecretEnvelopeCodec.Serialize(ValidEnvelope());

        Assert.Contains("\"scheme\"", json, StringComparison.Ordinal);
        Assert.Contains("\"scope\"", json, StringComparison.Ordinal);
        Assert.Contains("\"payloadBase64\"", json, StringComparison.Ordinal);
        Assert.Contains("\"protectedAtEpochMs\"", json, StringComparison.Ordinal);
    }

    [Fact]
    public void Codec_ToleratesUnknownFields()
    {
        string json = "{\"scheme\":\"dpapi-user\",\"scope\":\"alice\",\"payloadBase64\":\"" + SealedLookingPayload() + "\",\"protectedAtEpochMs\":5,\"futureField\":\"x\"}";

        Assert.True(SecretEnvelopeCodec.TryParse(json, out SecretEnvelope? envelope, out string? reason), reason);
        Assert.NotNull(envelope);
        Assert.Equal("alice", envelope!.Scope);
    }

    [Fact]
    public void Codec_RejectsMalformedOrIncompleteJson()
    {
        Assert.False(SecretEnvelopeCodec.TryParse("not json", out _, out string? malformed));
        Assert.Equal(SecretError.MalformedJson, malformed);

        Assert.False(SecretEnvelopeCodec.TryParse("{}", out _, out string? blank));
        Assert.Equal(SecretError.SchemeUnknown, blank);
    }

    [Fact]
    public void ToString_NeverEchoesThePayload()
    {
        SecretEnvelope envelope = ValidEnvelope();

        string text = envelope.ToString();

        Assert.DoesNotContain(envelope.PayloadBase64, text, StringComparison.Ordinal);
        Assert.Contains("PayloadLength=", text, StringComparison.Ordinal);
    }

    private static SecretEnvelope ValidEnvelope() => new()
    {
        Scheme = SecretEnvelope.SchemeDpapiUser,
        Scope = "alice",
        PayloadBase64 = SealedLookingPayload(),
        ProtectedAtEpochMs = 1_760_000_000_000L,
    };

    private static string SealedLookingPayload()
        => Convert.ToBase64String(System.Security.Cryptography.RandomNumberGenerator.GetBytes(160));
}

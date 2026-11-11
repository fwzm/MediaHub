using System.Text.Json.Serialization;

namespace MediaHub.Sync;

/// <summary>
/// The only shape a credential may travel in: a sealed blob plus the facts needed to recognise it.
/// </summary>
/// <remarks>
/// <para>
/// A credential is never part of a source record's identity or content: changing a password does not
/// change <see cref="SourceRecord.RecordRevision"/>, and <see cref="SourceSyncContentHash"/> deliberately
/// ignores this block. It rides along with the source row and is re-sealed on the importing machine.
/// </para>
/// <para>
/// There is deliberately no constructor that accepts a plaintext secret. The only way to obtain a sealed
/// envelope is <see cref="ISecretProtector.Protect"/>, and the only way back is
/// <see cref="ISecretProtector.Unprotect"/> on a machine that can actually unseal it. Everything here is
/// validation of a claim, not decryption.
/// </para>
/// </remarks>
public sealed record SecretEnvelope
{
    /// <summary>Sealed for the current Windows user (DPAPI user scope).</summary>
    public const string SchemeDpapiUser = "dpapi-user";

    /// <summary>Sealed for this machine, readable by any account on it (DPAPI machine scope).</summary>
    public const string SchemeDpapiMachine = "dpapi-machine";

    /// <summary>Explicitly "there is no credential here". Carries no payload.</summary>
    public const string SchemeNone = "none";

    /// <summary>Every scheme this build understands, in contract order.</summary>
    public static IReadOnlyList<string> Schemes { get; } = Array.AsReadOnly([SchemeDpapiUser, SchemeDpapiMachine, SchemeNone]);

    /// <summary>
    /// Shortest believable sealed blob. A DPAPI blob is hundreds of bytes, so a short "payload" is a
    /// plaintext secret with a base64 coat on; rejecting it here is the last line of defence.
    /// </summary>
    public const int MinSealedBytes = 128;

    /// <summary>One of <see cref="SchemeDpapiUser"/>, <see cref="SchemeDpapiMachine"/> or <see cref="SchemeNone"/>.</summary>
    public string Scheme { get; init; } = SchemeNone;

    /// <summary>Who can unseal the blob: a user name for user scope, a machine name for machine scope.</summary>
    public string Scope { get; init; } = "";

    /// <summary>The sealed bytes, base64. Never plaintext, never empty for a sealed scheme.</summary>
    public string PayloadBase64 { get; init; } = "";

    /// <summary>When the blob was sealed, for diagnostics only. Never used for conflict resolution.</summary>
    public long ProtectedAtEpochMs { get; init; }

    /// <summary>True when this block explicitly says "no credential".</summary>
    [JsonIgnore]
    public bool IsEmpty => string.Equals(Scheme, SchemeNone, StringComparison.Ordinal);

    /// <summary>True when this block claims to carry a sealed blob.</summary>
    [JsonIgnore]
    public bool IsSealed => !IsEmpty;

    /// <summary>True when <see cref="Scheme"/> is one this build understands (case-sensitive, wire form).</summary>
    [JsonIgnore]
    public bool IsKnownScheme => Schemes.Contains(Scheme, StringComparer.Ordinal);

    /// <summary>The explicit "no credential" block.</summary>
    public static SecretEnvelope Empty(string? scope = null)
        => new() { Scheme = SchemeNone, Scope = scope ?? "", PayloadBase64 = "", ProtectedAtEpochMs = 0 };

    /// <summary>
    /// Always throws <see cref="SecretError.PlaintextRejected"/>. It exists so that a call site which tries
    /// to pass a password where an envelope belongs fails loudly instead of storing the password.
    /// </summary>
    public static SecretEnvelope FromPlaintext(string? plaintext)
        => throw new SecretException(
            SecretError.PlaintextRejected,
            "A plaintext secret cannot become a SecretEnvelope; seal it with an ISecretProtector first.");

    /// <summary>Validates the claim and returns this instance for chaining.</summary>
    /// <exception cref="SecretException">With one of the <see cref="SecretError"/> codes.</exception>
    public SecretEnvelope Validate()
    {
        if (string.IsNullOrEmpty(Scheme) || !IsKnownScheme)
        {
            throw new SecretException(SecretError.SchemeUnknown, "Unknown secret scheme '" + Scheme + "'.");
        }

        if (IsEmpty)
        {
            if (!string.IsNullOrEmpty(PayloadBase64))
            {
                throw new SecretException(SecretError.PayloadNotEmpty, "An empty secret block must not carry a payload.");
            }

            return this;
        }

        if (string.IsNullOrWhiteSpace(Scope))
        {
            throw new SecretException(SecretError.ScopeEmpty, "A sealed secret must name the scope it was sealed for.");
        }

        if (string.IsNullOrEmpty(PayloadBase64))
        {
            throw new SecretException(SecretError.PayloadEmpty, "A sealed secret must carry a payload.");
        }

        if (DecodePayload().Length < MinSealedBytes)
        {
            throw new SecretException(
                SecretError.PayloadTooShort,
                "The payload is shorter than a sealed blob; it looks like plaintext encoded as base64.");
        }

        return this;
    }

    /// <summary>The sealed bytes. Validates first, so this never returns plaintext.</summary>
    public byte[] ToPayloadBytes()
    {
        Validate();
        return DecodePayload();
    }

    /// <summary>Diagnostics-safe text: scheme, scope and payload length, never the payload itself.</summary>
    public override string ToString()
        => "SecretEnvelope(Scheme=" + Scheme + ", Scope=" + Scope + ", ProtectedAtEpochMs=" + ProtectedAtEpochMs + ", PayloadLength=" + PayloadBase64.Length + ")";

    private byte[] DecodePayload()
    {
        try
        {
            return Convert.FromBase64String(PayloadBase64);
        }
        catch (FormatException)
        {
            throw new SecretException(SecretError.PayloadNotBase64, "The secret payload is not valid base64.");
        }
    }
}

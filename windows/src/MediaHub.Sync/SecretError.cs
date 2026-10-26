namespace MediaHub.Sync;

/// <summary>
/// Stable reason codes for everything that can go wrong with a sealed credential block.
/// </summary>
/// <remarks>
/// <para>
/// These strings cross the wire and surface in UI text and logs, so they are part of the contract:
/// tests pin them and callers must not reword them. A reason is allowed to say "the payload is not
/// base64"; it is never allowed to say what the payload contained.
/// </para>
/// </remarks>
public static class SecretError
{
    /// <summary>The <c>scheme</c> is not one of <c>dpapi-user</c>, <c>dpapi-machine</c> or <c>none</c>.</summary>
    public const string SchemeUnknown = "secret-scheme-unknown";

    /// <summary>A sealed block must name the scope it was sealed for.</summary>
    public const string ScopeEmpty = "secret-scope-empty";

    /// <summary>A sealed scheme arrived without a payload.</summary>
    public const string PayloadEmpty = "secret-payload-empty";

    /// <summary><c>scheme=none</c> must carry no payload at all; a "none" block with bytes is a plaintext smuggle.</summary>
    public const string PayloadNotEmpty = "secret-payload-not-empty";

    /// <summary>The payload is not valid base64, so it is not a sealed blob.</summary>
    public const string PayloadNotBase64 = "secret-payload-not-base64";

    /// <summary>
    /// The decoded payload is shorter than <see cref="SecretEnvelope.MinSealedBytes"/>. A DPAPI blob is
    /// hundreds of bytes, so anything shorter is a plaintext secret wearing a blob costume.
    /// </summary>
    public const string PayloadTooShort = "secret-payload-too-short";

    /// <summary>Someone tried to build an envelope from a plaintext secret instead of from a protector.</summary>
    public const string PlaintextRejected = "plaintext-secret-rejected";

    /// <summary>Secrets were requested but no <see cref="ISecretProtector"/> was supplied.</summary>
    public const string SecretsRequireProtector = "secrets-require-protector";

    /// <summary>Secrets were handed to an export that did not ask for them.</summary>
    public const string SecretsNotRequested = "secrets-not-requested";

    /// <summary>Secrets were requested but no plaintext was supplied for any source.</summary>
    public const string SecretsMissing = "secrets-missing";

    /// <summary>The block is valid JSON but is not a sealed envelope (for example <c>scheme=none</c>).</summary>
    public const string EnvelopeNotSealed = "secret-envelope-not-sealed";

    /// <summary>The block is not JSON at all.</summary>
    public const string MalformedJson = "secret-malformed-json";

    /// <summary>DPAPI refused to protect the payload.</summary>
    public const string DpapiProtectFailed = "dpapi-protect-failed";

    /// <summary>DPAPI refused to unprotect the payload: wrong user, wrong machine, or tampered bytes.</summary>
    public const string DpapiUnprotectFailed = "dpapi-unprotect-failed";

    /// <summary>The payload handed to DPAPI (or returned by it) was empty.</summary>
    public const string DpapiBlobEmpty = "dpapi-blob-empty";
}

/// <summary>A credential-layer failure carrying one of the stable <see cref="SecretError"/> codes.</summary>
public sealed class SecretException : Exception
{
    public SecretException(string reason, string message)
        : base(message)
    {
        Reason = reason;
    }

    public SecretException(string reason, string message, Exception? innerException)
        : base(message, innerException)
    {
        Reason = reason;
    }

    /// <summary>The stable code; see <see cref="SecretError"/>.</summary>
    public string Reason { get; }
}

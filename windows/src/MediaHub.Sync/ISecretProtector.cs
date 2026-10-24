namespace MediaHub.Sync;

/// <summary>Seals a plaintext secret so it can be stored or carried, and unseals it where it can be read.</summary>
/// <remarks>
/// <para>
/// Implementations must never return the plaintext on failure, must never log it, and must clear any
/// intermediate buffer they allocate. A protector is bound to a machine (or a machine and a user): a blob
/// produced on device A is expected to fail <see cref="Unprotect"/> on device B. That is exactly why import
/// always re-seals instead of copying the blob through unchanged.
/// </para>
/// </remarks>
public interface ISecretProtector
{
    /// <summary>Seals the plaintext for this protector's scope. The plaintext must not be empty.</summary>
    SecretEnvelope Protect(ReadOnlySpan<byte> plaintext);

    /// <summary>Unseals a sealed envelope. Throws <see cref="SecretException"/> when this machine cannot.</summary>
    byte[] Unprotect(SecretEnvelope envelope);
}

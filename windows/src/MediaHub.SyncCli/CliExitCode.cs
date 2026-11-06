namespace MediaHub.SyncCli;

using MediaHub.Sync;

/// <summary>
/// The console tool has exactly five outcomes, and they are part of its contract:
/// automation (the W6 acceptance script, CI probes) switches on the number, not on the text.
/// </summary>
/// <remarks>
/// The mapping is deliberately coarse. Anything that means "a human has to look at this" is
/// <see cref="NeedsApproval"/>; anything a second invocation with better input could fix is
/// <see cref="InputProblem"/>; anything that means "this machine cannot read the secret material"
/// is <see cref="ArchiveOrCredential"/> so a caller can never mistake a credential failure for a
/// plain input typo.
/// </remarks>
public static class CliExitCode
{
    /// <summary>The command ran and nothing needed a decision.</summary>
    public const int Success = 0;

    /// <summary>Wrong command line: unknown option, missing value, two mutually exclusive options.</summary>
    public const int UsageError = 1;

    /// <summary>The input could not be used: missing file, unreadable store, malformed envelope.</summary>
    public const int InputProblem = 2;

    /// <summary>The round has work that a human must confirm (conflicts, deletes, unknown types).</summary>
    public const int NeedsApproval = 3;

    /// <summary>Archive or credential material could not be handled (wrong passphrase, foreign DPAPI blob).</summary>
    public const int ArchiveOrCredential = 4;

    /// <summary>
    /// Maps a stable reason code to its exit code. Unknown codes stay <see cref="InputProblem"/>:
    /// a code this build does not know must never be reported as success.
    /// </summary>
    public static int ClassifyReason(string? reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return InputProblem;
        }

        string value = reason.Trim();

        // Both prefixed codes carry a payload ("unsupported-version:2", "unknown-kdf:argon2id").
        if (value.StartsWith(ArchiveError.UnsupportedVersionPrefix, StringComparison.Ordinal) ||
            value.StartsWith(ArchiveError.UnknownKdfPrefix, StringComparison.Ordinal))
        {
            return ArchiveOrCredential;
        }

        switch (value)
        {
            case ArchiveError.MagicMismatch:
            case ArchiveError.CorruptHeader:
            case ArchiveError.InvalidIterations:
            case ArchiveError.PassphraseEmpty:
            case ArchiveError.PassphraseTooWeak:
            case ArchiveError.SecretsNotAllowed:
            case ArchiveError.PayloadTooLarge:
            case ArchiveError.CorruptCiphertext:
            case ArchiveError.WrongPassphraseOrTampered:
            case ArchiveError.MalformedPayload:
            case SecretError.SchemeUnknown:
            case SecretError.ScopeEmpty:
            case SecretError.PayloadEmpty:
            case SecretError.PayloadNotEmpty:
            case SecretError.PayloadNotBase64:
            case SecretError.PayloadTooShort:
            case SecretError.PlaintextRejected:
            case SecretError.SecretsRequireProtector:
            case SecretError.SecretsNotRequested:
            case SecretError.SecretsMissing:
            case SecretError.EnvelopeNotSealed:
            case SecretError.MalformedJson:
            case SecretError.DpapiProtectFailed:
            case SecretError.DpapiUnprotectFailed:
            case SecretError.DpapiBlobEmpty:
                return ArchiveOrCredential;
            default:
                return InputProblem;
        }
    }
}

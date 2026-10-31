namespace MediaHub.Sync;

/// <summary>
/// Passphrase policy for the offline archive (ADR-S005 D3).
/// </summary>
/// <remarks>
/// <para>
/// PBKDF2 is memory-cheap: an attacker with a GPU gets many more guesses per second than Argon2id would
/// allow, so the passphrase is the part of the threat model that has to carry its weight. The rules are
/// deliberately blunt and explainable — length first, a short list of passwords that appear at the top of
/// every breach corpus, a single repeated character, all digits — because a strength meter the user cannot
/// predict is a strength meter they will route around.
/// </para>
/// <para>
/// This is not a password generator and not a dictionary test: twelve characters of anything uncommon is
/// accepted, including a sentence. The list exists to stop the four answers people actually type into a
/// "protect this file" dialog, not to score entropy.
/// </para>
/// </remarks>
public static class ArchivePassphrase
{
    /// <summary>Shortest passphrase the archive accepts.</summary>
    public const int MinLength = 12;

    // The usual suspects: each one is a top-of-the-list password plus padding, or a keyboard walk. Stored
    // lower-cased; comparison is ordinal-ignore-case after trimming.
    private static readonly string[] WeakPassphrases =
    [
        "password",
        "password1",
        "password123",
        "password1234",
        "passw0rd",
        "123456",
        "1234567",
        "12345678",
        "123456789",
        "1234567890",
        "12345678901",
        "123456789012",
        "qwertyuiop",
        "qwerty123456",
        "qwertyuiop123",
        "1q2w3e4r5t6y",
        "letmein12345",
        "iloveyou1234",
        "administrator",
        "trustno1trustno1",
        "aaaaaaaaaaaa",
        "abcabcabcabc",
        "changeme1234",
        "welcome12345",
    ];

    /// <summary>Checks a passphrase without throwing; <paramref name="reason"/> is an <see cref="ArchiveError"/> code.</summary>
    public static bool TryValidate(string? passphrase, out string? reason)
    {
        // Every textual judgement goes through WireText, which pins the Unicode White_Space set and an
        // ASCII-only case fold. char.IsWhiteSpace happens to agree with it today, but the Kotlin end does not,
        // and the two ends must return the same stable code for the same bytes.
        if (WireText.IsBlank(passphrase))
        {
            reason = ArchiveError.PassphraseEmpty;
            return false;
        }

        string text = passphrase!;
        if (text.Length < MinLength)
        {
            reason = ArchiveError.PassphraseTooWeak;
            return false;
        }

        // A lone surrogate cannot be encoded to UTF-8 by the PBKDF2 input here (EncoderFallbackException) and
        // is silently replaced by '?' on the JVM, so the two ends would derive different keys from the same
        // text - or crash mid-write. Reject it at the policy layer instead of adding a new code.
        if (WireText.HasLoneSurrogate(text))
        {
            reason = ArchiveError.PassphraseTooWeak;
            return false;
        }

        string trimmed = WireText.Trim(text);
        foreach (string weak in WeakPassphrases)
        {
            if (WireText.EqualsAsciiIgnoreCase(trimmed, weak))
            {
                reason = ArchiveError.PassphraseTooWeak;
                return false;
            }
        }

        if (IsSingleRepeatedCharacter(trimmed) || IsAllDigits(trimmed))
        {
            reason = ArchiveError.PassphraseTooWeak;
            return false;
        }

        reason = null;
        return true;
    }

    /// <summary>Throws <see cref="ArchiveException"/> when the passphrase is not acceptable.</summary>
    /// <exception cref="ArchiveException">The passphrase is empty or too weak.</exception>
    public static void Validate(string? passphrase)
    {
        if (!TryValidate(passphrase, out string? reason))
        {
            string message = reason == ArchiveError.PassphraseEmpty
                ? "A passphrase is required to write or read an archive."
                : "The passphrase must be at least " + MinLength + " characters and must not be a common one.";
            throw new ArchiveException(reason!, message);
        }
    }

    private static bool IsSingleRepeatedCharacter(string value)
    {
        for (int index = 1; index < value.Length; index++)
        {
            if (value[index] != value[0])
            {
                return false;
            }
        }

        return value.Length > 0;
    }

    private static bool IsAllDigits(string value)
    {
        foreach (char character in value)
        {
            if (!char.IsAsciiDigit(character))
            {
                return false;
            }
        }

        return value.Length > 0;
    }
}

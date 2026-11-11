namespace MediaHub.Sync;

using System.Text.Json;

/// <summary>
/// Text rules shared by the envelope wire format and the archive header.
/// </summary>
/// <remarks>
/// <para>
/// The rules are pinned here instead of inherited from the runtime on purpose. The JVM and .NET do not agree
/// on what whitespace is - the JVM set contains U+001C-U+001F and lacks U+0085, while .NET
/// <see cref="char.IsWhiteSpace(char)"/> is exactly the Unicode White_Space property - and the JVM
/// <c>String.trim()</c> does not even follow its own whitespace set. Case folding diverges the same way: the
/// JVM <c>equalsIgnoreCase</c> folds U+017F (long s) and U+212A (Kelvin sign) onto ASCII, .NET ordinal
/// comparison does not. Two ends that must report the same stable code for the same bytes cannot both defer
/// to their runtime defaults.
/// </para>
/// <para>
/// So: whitespace is the Unicode White_Space property, listed explicitly (25 BMP code points, and the tests
/// assert this table equals <see cref="char.IsWhiteSpace(char)"/> for every BMP char); case-insensitivity
/// folds ASCII A-Z only. The Kotlin twin is <c>com.mediahub.core.common.sync.WireText</c>; the golden vector
/// file pins both to the same answers.
/// </para>
/// </remarks>
public static class WireText
{
    /// <summary>Unicode White_Space property, BMP code points, ascending.</summary>
    public static readonly int[] WhitespaceCodePoints =
    [
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
        0x0020, 0x0085, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004,
        0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    ];

    /// <summary>True when the character is Unicode White_Space.</summary>
    public static bool IsWhitespace(char character) => Array.BinarySearch(WhitespaceCodePoints, (int)character) >= 0;

    /// <summary>True when the value is null, empty, or only Unicode White_Space.</summary>
    public static bool IsBlank([System.Diagnostics.CodeAnalysis.NotNullWhen(false)] string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return true;
        }

        foreach (char character in value)
        {
            if (!IsWhitespace(character))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>Strips leading and trailing Unicode White_Space; returns the same instance when there is none.</summary>
    public static string Trim(string value)
    {
        int start = 0;
        int end = value.Length;
        while (start < end && IsWhitespace(value[start]))
        {
            start++;
        }

        while (end > start && IsWhitespace(value[end - 1]))
        {
            end--;
        }

        return start == 0 && end == value.Length ? value : value[start..end];
    }

    /// <summary>Equality that folds ASCII A-Z only; every other code unit must match exactly.</summary>
    public static bool EqualsAsciiIgnoreCase(string left, string right)
    {
        if (left.Length != right.Length)
        {
            return false;
        }

        for (int index = 0; index < left.Length; index++)
        {
            if (FoldAscii(left[index]) != FoldAscii(right[index]))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>True when the string contains an unpaired surrogate (a valid pair, such as an emoji, is fine).</summary>
    public static bool HasLoneSurrogate(string value)
    {
        for (int index = 0; index < value.Length; index++)
        {
            char character = value[index];
            if (char.IsHighSurrogate(character))
            {
                if (index + 1 >= value.Length || !char.IsLowSurrogate(value[index + 1]))
                {
                    return true;
                }

                index++;
            }
            else if (char.IsLowSurrogate(character))
            {
                return true;
            }
        }

        return false;
    }

    /// <summary>True when any string value anywhere in the document contains a lone surrogate.</summary>
    /// <remarks>
    /// <see cref="JsonElement.GetString"/> throws <see cref="InvalidOperationException"/> for such text, and
    /// <c>Utf8JsonWriter</c> silently substitutes U+FFFD when writing it, so both directions have to refuse the
    /// document before any field is interpreted. This is the twin of the Kotlin <c>WireText.containsLoneSurrogate</c>.
    /// </remarks>
    public static bool ContainsLoneSurrogate(JsonElement element)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                foreach (JsonProperty property in element.EnumerateObject())
                {
                    if (ContainsLoneSurrogate(property.Value))
                    {
                        return true;
                    }
                }

                return false;
            case JsonValueKind.Array:
                foreach (JsonElement item in element.EnumerateArray())
                {
                    if (ContainsLoneSurrogate(item))
                    {
                        return true;
                    }
                }

                return false;
            case JsonValueKind.String:
                try
                {
                    return HasLoneSurrogate(element.GetString() ?? "");
                }
                catch (InvalidOperationException)
                {
                    return true;
                }

            default:
                return false;
        }
    }

    /// <summary>Case-insensitive prefix test that folds ASCII A-Z only.</summary>
    public static bool StartsWithAsciiIgnoreCase(string value, string prefix)
    {
        if (prefix.Length > value.Length)
        {
            return false;
        }

        for (int index = 0; index < prefix.Length; index++)
        {
            if (FoldAscii(value[index]) != FoldAscii(prefix[index]))
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>Lowercases ASCII A-Z only (interface-name classification must not fold non-ASCII).</summary>
    public static string LowercaseAscii(string value)
    {
        bool changed = false;
        foreach (char character in value)
        {
            if (character is >= 'A' and <= 'Z')
            {
                changed = true;
                break;
            }
        }

        if (!changed)
        {
            return value;
        }

        var builder = new System.Text.StringBuilder(value.Length);
        foreach (char character in value)
        {
            builder.Append(FoldAscii(character));
        }

        return builder.ToString();
    }

    /// <summary>Uppercases ASCII a-z only (fingerprint display; must not touch non-ASCII).</summary>
    public static string UppercaseAscii(string value)
    {
        bool changed = false;
        foreach (char character in value)
        {
            if (character is >= 'a' and <= 'z')
            {
                changed = true;
                break;
            }
        }

        if (!changed)
        {
            return value;
        }

        var builder = new System.Text.StringBuilder(value.Length);
        foreach (char character in value)
        {
            builder.Append(FoldAsciiUpper(character));
        }

        return builder.ToString();
    }

    /// <summary>Ordering that folds ASCII A-Z only; a stable total order for interface names.</summary>
    public static int CompareAsciiIgnoreCase(string left, string right)
    {
        int shared = Math.Min(left.Length, right.Length);
        for (int index = 0; index < shared; index++)
        {
            int delta = FoldAscii(left[index]) - FoldAscii(right[index]);
            if (delta != 0)
            {
                return delta;
            }
        }

        return left.Length - right.Length;
    }

    /// <summary>
    /// True when a JSON text nests deeper than <paramref name="maxDepth"/> containers; string content is ignored.
    /// </summary>
    /// <remarks>
    /// Equivalent to <c>JsonDocumentOptions.MaxDepth</c> but decided before the parser runs, so a hostile or
    /// truncated document produces the same reason on both ends: the JVM parser has no depth option, and
    /// .NET reports a plain truncation as depth damage because its message happens to contain the word
    /// "depth". Scanning first makes the classification depend on the bytes, not on a parser message.
    /// </remarks>
    public static bool ExceedsDepth(string? text, int maxDepth)
    {
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        const char quote = (char)34;
        const char escape = (char)92;
        int depth = 0;
        bool inString = false;
        bool escaped = false;
        foreach (char character in text)
        {
            if (inString)
            {
                if (escaped)
                {
                    escaped = false;
                }
                else if (character == escape)
                {
                    escaped = true;
                }
                else if (character == quote)
                {
                    inString = false;
                }

                continue;
            }

            if (character == quote)
            {
                inString = true;
                continue;
            }

            if (character is (char)123 or (char)91)
            {
                depth += 1;
                if (depth > maxDepth)
                {
                    return true;
                }
            }
            else if (character is (char)125 or (char)93)
            {
                if (depth > 0)
                {
                    depth -= 1;
                }
            }
        }

        return false;
    }
    private static char FoldAscii(char character) =>
        character is >= 'A' and <= 'Z' ? (char)(character + ('a' - 'A')) : character;

    private static char FoldAsciiUpper(char character) =>
        character is >= 'a' and <= 'z' ? (char)(character - ('a' - 'A')) : character;
}

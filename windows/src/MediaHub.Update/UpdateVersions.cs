namespace MediaHub.Update;

/// <summary>
/// Version rules for the update manifest. The manifest carries the same dotted number the installer
/// reads from <c>MediaHub.exe</c> (ProductVersion without the build-metadata suffix), so both sides
/// of the update have to compare it the same way.
/// </summary>
public static class UpdateVersions
{
    /// <summary>
    /// Parses a manifest or file version. A trailing <c>+metadata</c> (the SDK writes the commit hash
    /// there) is ignored, and a two- or three-part version is padded so <c>1.1</c> and <c>1.1.0</c>
    /// compare equal instead of one sorting below the other.
    /// </summary>
    public static bool TryParse(string? text, out Version version)
    {
        version = new Version(0, 0, 0, 0);
        if (string.IsNullOrWhiteSpace(text)) return false;

        string value = text.Trim();
        int plus = value.IndexOf('+');
        if (plus >= 0) value = value[..plus];
        if (value.Length == 0) return false;

        var parts = value.Split('.');
        if (parts.Length is < 1 or > 4) return false;

        var numbers = new int[4];
        for (int i = 0; i < parts.Length; i++)
        {
            if (!int.TryParse(parts[i], System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out int parsed) || parsed < 0)
            {
                return false;
            }
            numbers[i] = parsed;
        }

        version = new Version(numbers[0], numbers[1], numbers[2], numbers[3]);
        return true;
    }

    /// <summary>True when the candidate is strictly newer than the installed version.</summary>
    public static bool IsNewer(string? candidate, string? installed)
    {
        if (!TryParse(candidate, out var other)) return false;
        if (!TryParse(installed, out var current)) return true;
        return other > current;
    }

    /// <summary>Returns a four-part, dot-separated version, or the trimmed input when unparseable.</summary>
    public static string Normalize(string? text)
    {
        return TryParse(text, out var version)
            ? version.ToString(4)
            : (text ?? "").Trim();
    }
}

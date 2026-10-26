namespace MediaHub.Domain;

/// <summary>
/// Validation and expansion of file paths supplied from outside the application.
/// </summary>
/// <remarks>
/// <para>
/// Every path handled here comes from a drop, a command line, a file association or a folder picker.
/// None of it is chosen by the application, so none of it is trusted. Nothing is executed, but a path
/// still decides which file is read and which directory is walked, so it is normalised, required to
/// be absolute, and required to exist before anything else sees it.
/// </para>
/// <para>
/// This lives in the domain assembly rather than in the UI so the rules can be tested without a window,
/// which is the only way they stay enforced as the number of callers grows.
/// </para>
/// </remarks>
public static class MediaPathInput
{
    /// <summary>Upper bound Windows accepts for a path; longer values are rejected, not truncated.</summary>
    public const int MaxPathLength = 32767;

    /// <summary>Extensions the client will accept from an external path.</summary>
    public static readonly string[] SupportedExtensions =
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm", ".flv", ".ts", ".mpg", ".mpeg",
        ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav",
    };

    public static bool IsSupported(string path)
        => SupportedExtensions.Contains(Path.GetExtension(path), StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Canonicalises a caller-supplied path, or returns null when it must not be used.
    /// </summary>
    /// <remarks>
    /// Rejected on purpose: empty input; a relative path, whose meaning depends on a working directory
    /// the user never chose; a path longer than the platform limit, rather than silently truncating it
    /// to something that may name a different file; and anything the OS refuses to canonicalise.
    /// Surrounding quotes are stripped, because Explorer's "Copy as path" produces them.
    /// </remarks>
    public static string? Normalize(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;

        string candidate = path.Trim();
        if (candidate.Length >= 2 && candidate[0] == '"' && candidate[^1] == '"')
        {
            candidate = candidate[1..^1].Trim();
        }
        if (candidate.Length == 0) return null;

        try
        {
            if (!Path.IsPathFullyQualified(candidate)) return null;
            string full = Path.GetFullPath(candidate);
            return full.Length > MaxPathLength ? null : full;
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Expands dropped items into a deduplicated, ordered list of playable files.
    /// </summary>
    /// <remarks>
    /// A dropped folder is expanded one level rather than recursively. A user who drops a folder wants
    /// the videos in it; walking the whole subtree could queue thousands of files from a directory that
    /// merely happened to be selected, with no way to cancel.
    /// </remarks>
    public static List<string> Expand(IEnumerable<string> paths, Func<string, IEnumerable<string>>? enumerateFiles = null)
    {
        ArgumentNullException.ThrowIfNull(paths);
        var result = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        Func<string, IEnumerable<string>> lister = enumerateFiles ?? (directory => Directory.EnumerateFiles(directory));

        foreach (string raw in paths)
        {
            string? full = Normalize(raw);
            if (full is null) continue;

            if (Directory.Exists(full))
            {
                try
                {
                    foreach (string file in lister(full).Where(IsSupported).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
                    {
                        if (seen.Add(file)) result.Add(file);
                    }
                }
                catch (Exception)
                {
                    // An unreadable directory must not abort a drop that also contains usable files.
                }
                continue;
            }

            if (File.Exists(full) && IsSupported(full) && seen.Add(full)) result.Add(full);
        }

        return result;
    }
}

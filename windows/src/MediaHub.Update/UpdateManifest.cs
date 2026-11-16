using System.Text.Json;

namespace MediaHub.Update;

/// <summary>
/// One update descriptor published as <c>update.json</c> in the update source.
/// </summary>
/// <remarks>
/// The package is the same zip the install kit produces, and <see cref="Sha256"/> is the hash of that
/// zip, not of an inner file. The hash is the only identity the client trusts: a version string and a
/// file name are claims, while the digest is checked before anything is unpacked or replaced.
/// </remarks>
public sealed class UpdateManifest
{
    /// <summary>Dotted version of the payload, e.g. <c>1.1.0</c>. Must match MediaHub.exe after unpacking.</summary>
    public string Version { get; init; } = "";

    /// <summary>Bare file name of the zip inside the same directory as this manifest.</summary>
    public string Package { get; init; } = "";

    /// <summary>Lower-case hex SHA-256 of the zip.</summary>
    public string Sha256 { get; init; } = "";

    /// <summary>Expected zip size in bytes. Zero means "not published"; a non-zero value is enforced.</summary>
    public long Size { get; init; }

    /// <summary>Optional user-facing notes; shown verbatim in the settings page.</summary>
    public string? Notes { get; init; }

    /// <summary>Optional ISO-8601 publication timestamp.</summary>
    public string? Published { get; init; }

    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    /// <summary>Parses and validates a manifest document.</summary>
    public static bool TryParse(string? json, out UpdateManifest manifest, out string error)
    {
        manifest = new UpdateManifest();
        error = "";

        if (string.IsNullOrWhiteSpace(json))
        {
            error = "update.json is empty";
            return false;
        }

        UpdateManifest? parsed;
        try
        {
            parsed = JsonSerializer.Deserialize<UpdateManifest>(json, Options);
        }
        catch (JsonException ex)
        {
            error = "update.json is not valid JSON: " + ex.Message;
            return false;
        }

        if (parsed is null)
        {
            error = "update.json contains no object";
            return false;
        }

        error = parsed.Validate();
        if (error.Length != 0) return false;

        manifest = new UpdateManifest
        {
            Version = UpdateVersions.Normalize(parsed.Version),
            Package = parsed.Package.Trim(),
            Sha256 = parsed.Sha256.Trim().ToLowerInvariant(),
            Size = parsed.Size,
            Notes = parsed.Notes,
            Published = parsed.Published,
        };
        return true;
    }

    private string Validate()
    {
        if (!UpdateVersions.TryParse(Version, out _)) return "version must be a dotted number such as 1.1.0";

        string package = (Package ?? "").Trim();
        if (package.Length == 0) return "package is required";
        if (package is "." or "..") return "package must be a file name";
        if (package.IndexOfAny(new[] { '/', '\\', ':' }) >= 0) return "package must be a bare file name";
        if (!package.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) return "package must be a .zip";

        string hash = (Sha256 ?? "").Trim();
        if (hash.Length != 64 || !hash.All(Uri.IsHexDigit)) return "sha256 must be 64 hexadecimal characters";

        if (Size < 0) return "size cannot be negative";
        return "";
    }
}

namespace MediaHub.Update;

public enum UpdateSourceKind
{
    /// <summary>A directory on this machine, or a file:// URI pointing at one.</summary>
    LocalDirectory,

    /// <summary>
    /// An https static directory served as plain files. Plain http is accepted only for a loopback
    /// mirror: the manifest and the package come from the same place, so cleartext to a remote host
    /// would let whoever is on the path rewrite both.
    /// </summary>
    Http,
}

/// <summary>
/// A configured place to look for <c>update.json</c>. Deliberately limited to two shapes: a local
/// directory (the offline/mirror case) and a plain HTTP static directory. No feed protocol, no
/// authentication, no registry service is involved.
/// </summary>
public sealed class UpdateSource
{
    public const string ManifestFileName = "update.json";

    private UpdateSource(UpdateSourceKind kind, string root)
    {
        Kind = kind;
        Root = root;
    }

    public UpdateSourceKind Kind { get; }

    /// <summary>Directory path or absolute http(s) URL with a trailing slash.</summary>
    public string Root { get; }

    /// <summary>True when this is an HTTP source on the loopback interface (a local mirror served for tests).</summary>
    public bool IsLoopback => Kind == UpdateSourceKind.Http && IsLoopbackHost(new Uri(Root).Host);

    /// <summary>
    /// True when the package must prove its publisher before it may be installed.
    /// </summary>
    /// <remarks>
    /// A remote manifest carries the package's hash, but both arrive from the same server: the digest
    /// proves transport integrity, not authorship. Loopback and local-directory sources are under the
    /// operator's own control and are exempt.
    /// </remarks>
    public bool RequiresSignedPackage => Kind == UpdateSourceKind.Http && !IsLoopback;

    /// <summary>True for the loopback host names a local mirror is reachable under.</summary>
    public static bool IsLoopbackHost(string? host)
    {
        if (string.IsNullOrWhiteSpace(host)) return false;
        string value = host.Trim().Trim('[', ']');
        return value.Equals("localhost", StringComparison.OrdinalIgnoreCase)
               || (System.Net.IPAddress.TryParse(value, out var address) && System.Net.IPAddress.IsLoopback(address));
    }

    public string ManifestLocation => Kind == UpdateSourceKind.Http
        ? new Uri(new Uri(Root), ManifestFileName).AbsoluteUri
        : Path.Combine(Root, ManifestFileName);

    /// <summary>Resolves a manifest package name to a local path or an absolute URL.</summary>
    public string PackageLocation(string packageName)
    {
        if (packageName.IndexOfAny(new[] { '/', '\\' }) >= 0 || packageName is "." or "..")
        {
            throw new ArgumentException("package name must be a bare file name", nameof(packageName));
        }

        return Kind == UpdateSourceKind.Http
            ? new Uri(new Uri(Root), packageName).AbsoluteUri
            : Path.Combine(Root, packageName);
    }

    /// <summary>
    /// Parses the configured string. Empty input is an error, because "no source" and "the default
    /// source" must never be the same thing: the product has no server it could default to.
    /// </summary>
    public static bool TryParse(string? raw, out UpdateSource source, out string error)
    {
        source = null!;
        error = "";
        if (string.IsNullOrWhiteSpace(raw))
        {
            error = "update source is empty";
            return false;
        }

        string value = raw.Trim();

        if (Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
            (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
        {
            if (uri.Scheme == Uri.UriSchemeHttp && !IsLoopbackHost(uri.Host))
            {
                error = "an update source must be https or a local directory; plain http is accepted only for loopback mirrors";
                return false;
            }

            string root = uri.AbsoluteUri;
            if (!root.EndsWith("/", StringComparison.Ordinal)) root += "/";
            source = new UpdateSource(UpdateSourceKind.Http, root);
            return true;
        }

        if (Uri.TryCreate(value, UriKind.Absolute, out uri) && uri.Scheme == Uri.UriSchemeFile)
        {
            value = uri.LocalPath;
        }
        else if (value.Contains("://", StringComparison.Ordinal))
        {
            error = "only http(s) and local directory sources are supported";
            return false;
        }

        try
        {
            string full = Path.GetFullPath(value);
            if (Path.HasExtension(full) && string.Equals(Path.GetExtension(full), ".json", StringComparison.OrdinalIgnoreCase))
            {
                full = Path.GetDirectoryName(full) ?? full;
            }
            source = new UpdateSource(UpdateSourceKind.LocalDirectory, full);
            return true;
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            error = "invalid local directory: " + ex.Message;
            return false;
        }
    }
}

using System.IO.Compression;

namespace MediaHub.Update;

/// <summary>
/// Checks that the payload of a downloaded update package is signed, using only the package file.
/// </summary>
/// <remarks>
/// The package is a zip of the install kit. Only <c>MediaHub.exe</c> is extracted, into a temporary
/// file that is deleted immediately: nothing is unpacked over the installation, and nothing from the
/// archive is executed. The signature is what makes an update attributable — the manifest's digest
/// travels with the package and therefore cannot establish authorship on its own.
/// </remarks>
public static class UpdatePackageVerifier
{
    public const string ExecutableName = "MediaHub.exe";

    /// <summary>
    /// Extracts the packaged <c>MediaHub.exe</c> and verifies its Authenticode signature.
    /// </summary>
    /// <exception cref="InvalidDataException">
    /// The package has no single <see cref="ExecutableName"/>, or that file is unsigned or signed by
    /// something this machine does not trust.
    /// </exception>
    public static void VerifySignedPackage(string packagePath)
    {
        using ZipArchive archive = ZipFile.OpenRead(packagePath);
        ZipArchiveEntry entry = FindExecutable(archive);

        string temp = Path.Combine(
            Path.GetTempPath(), "mediahub-update-verify-" + Guid.NewGuid().ToString("N") + ".exe");
        try
        {
            entry.ExtractToFile(temp, overwrite: true);
            if (!Authenticode.Verify(temp, out string reason))
            {
                throw new InvalidDataException(
                    "update package is not signed by a publisher this machine trusts (" + reason
                    + "): " + entry.FullName);
            }
        }
        finally
        {
            try { File.Delete(temp); } catch { }
        }
    }

    private static ZipArchiveEntry FindExecutable(ZipArchive archive)
    {
        ZipArchiveEntry? best = null;
        int bestDepth = int.MaxValue;
        foreach (ZipArchiveEntry entry in archive.Entries)
        {
            if (entry.FullName.Length == 0) continue;
            string name = entry.FullName.Replace('\\', '/');
            int slash = name.LastIndexOf('/');
            if (!string.Equals(slash >= 0 ? name[(slash + 1)..] : name, ExecutableName, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            int depth = name.Count(c => c == '/');
            if (depth > bestDepth) continue;
            if (depth == bestDepth && best is not null
                && !string.Equals(best.FullName, entry.FullName, StringComparison.Ordinal))
            {
                throw new InvalidDataException(
                    "update package contains more than one " + ExecutableName + " at the same depth");
            }

            best = entry;
            bestDepth = depth;
        }

        return best ?? throw new InvalidDataException("update package does not contain " + ExecutableName);
    }
}

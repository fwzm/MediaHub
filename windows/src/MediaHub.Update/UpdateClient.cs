using System.Security.Cryptography;

namespace MediaHub.Update;

/// <summary>
/// Fetches the manifest and downloads the update package from a <see cref="UpdateSource"/>.
/// </summary>
/// <remarks>
/// Nothing here touches the installed application: it reads a file or an HTTP URL and writes the
/// package into a caller-supplied directory. Verification is deliberately part of the download, so
/// there is no code path that hands an unverified zip to the agent that swaps files.
/// </remarks>
public sealed class UpdateClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly bool _ownsHttp;

    public UpdateClient(HttpClient? http = null, string userAgent = "MediaHub (Windows)")
    {
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        _ownsHttp = http is null;
        if (_ownsHttp && !string.IsNullOrWhiteSpace(userAgent))
        {
            // Honest identity, never a borrowed client's. The update source is a static directory and
            // needs no credentials, so no default headers other than the product string are added.
            _http.DefaultRequestHeaders.UserAgent.ParseAdd(userAgent);
        }
    }

    /// <summary>Reads and validates <c>update.json</c> from the source.</summary>
    public async Task<UpdateManifest> GetManifestAsync(UpdateSource source, CancellationToken cancellationToken = default)
    {
        string json;
        if (source.Kind == UpdateSourceKind.LocalDirectory)
        {
            string path = source.ManifestLocation;
            if (!File.Exists(path)) throw new FileNotFoundException("update.json not found in the update source", path);
            json = await File.ReadAllTextAsync(path, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            using var response = await _http.GetAsync(source.ManifestLocation, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw new HttpRequestException(
                    $"update.json returned {(int)response.StatusCode} {response.ReasonPhrase}", null, response.StatusCode);
            }
            json = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        }

        if (!UpdateManifest.TryParse(json, out var manifest, out string error))
        {
            throw new InvalidDataException(error);
        }
        return manifest;
    }

    /// <summary>
    /// Downloads the package into <paramref name="destinationDirectory"/> and verifies its SHA-256.
    /// A file already present with the right digest is reused, which makes a retried update cheap.
    /// </summary>
    public async Task<string> DownloadPackageAsync(
        UpdateSource source,
        UpdateManifest manifest,
        string destinationDirectory,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(destinationDirectory);
        string finalPath = Path.Combine(destinationDirectory, manifest.Package);
        string partPath = finalPath + ".part";

        if (File.Exists(finalPath))
        {
            if ((manifest.Size <= 0 || new FileInfo(finalPath).Length == manifest.Size) &&
                string.Equals(ComputeSha256(finalPath), manifest.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                progress?.Report(1);
                return finalPath;
            }
            File.Delete(finalPath);
        }

        if (File.Exists(partPath)) File.Delete(partPath);

        try
        {
            if (source.Kind == UpdateSourceKind.LocalDirectory)
            {
                string from = source.PackageLocation(manifest.Package);
                if (!File.Exists(from)) throw new FileNotFoundException("update package not found in the update source", from);
                File.Copy(from, partPath, overwrite: true);
                progress?.Report(1);
            }
            else
            {
                string url = source.PackageLocation(manifest.Package);
                using var response = await _http
                    .GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                    .ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    throw new HttpRequestException(
                        $"update package returned {(int)response.StatusCode} {response.ReasonPhrase}", null, response.StatusCode);
                }

                long total = response.Content.Headers.ContentLength ?? manifest.Size;
                await using var input = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
                await using var output = new FileStream(partPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
                await CopyWithProgressAsync(input, output, total, progress, cancellationToken).ConfigureAwait(false);
            }

            long size = new FileInfo(partPath).Length;
            if (manifest.Size > 0 && size != manifest.Size)
            {
                throw new InvalidDataException(
                    $"update package size mismatch: expected {manifest.Size} bytes, got {size}");
            }

            string actual = ComputeSha256(partPath);
            if (!string.Equals(actual, manifest.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException(
                    $"update package failed SHA-256 verification: expected {manifest.Sha256}, got {actual}");
            }

            // The digest proves the download was not corrupted in transit; it cannot prove who built
            // the package, because the manifest carrying it came from the same server. A remote source
            // therefore has to hand over a payload Windows itself trusts, checked here — on the same
            // path that would otherwise pass the zip to the file-swapping agent.
            if (source.RequiresSignedPackage) UpdatePackageVerifier.VerifySignedPackage(partPath);

            File.Move(partPath, finalPath, overwrite: true);
            return finalPath;
        }
        catch
        {
            // A half-written package must never be left where a retry could mistake it for complete.
            try { if (File.Exists(partPath)) File.Delete(partPath); } catch { }
            throw;
        }
    }

    private static async Task CopyWithProgressAsync(
        Stream input, Stream output, long total, IProgress<double>? progress, CancellationToken cancellationToken)
    {
        byte[] buffer = new byte[128 * 1024];
        long written = 0;
        while (true)
        {
            int read = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0) break;
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            written += read;
            if (total > 0) progress?.Report(Math.Min(1d, (double)written / total));
        }
        await output.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>SHA-256 of a file, lower-case hex.</summary>
    public static string ComputeSha256(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024,
            FileOptions.SequentialScan);
        using var sha = SHA256.Create();
        return Convert.ToHexString(sha.ComputeHash(stream)).ToLowerInvariant();
    }

    public void Dispose()
    {
        if (_ownsHttp) _http.Dispose();
    }
}

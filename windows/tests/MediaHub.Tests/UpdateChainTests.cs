using System.Net;
using System.Text;
using MediaHub.Update;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Contract tests for the update manifest, source resolution and package verification. They do not
/// touch an installation: the file swap itself is exercised by windows/tools/verify-update-chain.ps1.
/// </summary>
public sealed class UpdateChainTests
{
    [Theory]
    [InlineData("1.0.0", "1.0.0.0")]
    [InlineData("1.1", "1.1.0.0")]
    [InlineData("2.0.1.7", "2.0.1.7")]
    [InlineData("1.10.0+abcdef", "1.10.0.0")]
    public void NormalizePadsMissingComponents(string input, string expected)
    {
        Assert.Equal(expected, UpdateVersions.Normalize(input));
    }

    [Theory]
    [InlineData("1.1.0", "1.0.0", true)]
    [InlineData("1.0.0", "1.0.0", false)]
    [InlineData("1.0.0", "1.1.0", false)]
    [InlineData("1.1", "1.1.0", false)]
    [InlineData("1.2", "1.1.9", true)]
    [InlineData("nonsense", "1.0.0", false)]
    public void IsNewerComparesNumerically(string candidate, string installed, bool expected)
    {
        Assert.Equal(expected, UpdateVersions.IsNewer(candidate, installed));
    }

    [Fact]
    public void ManifestParsesThePublishedShape()
    {
        string json = """
        {
          "version": "1.1.0",
          "package": "MediaHub-Setup-win-x64-1.1.0.zip",
          "sha256": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "size": 12345,
          "notes": "test",
          "published": "2026-11-14T12:00:00Z"
        }
        """;

        Assert.True(UpdateManifest.TryParse(json, out var manifest, out string error), error);
        Assert.Equal("1.1.0.0", manifest.Version);
        Assert.Equal("MediaHub-Setup-win-x64-1.1.0.zip", manifest.Package);
        Assert.Equal(new string('a', 64), manifest.Sha256);
        Assert.Equal(12345, manifest.Size);
        Assert.Equal("test", manifest.Notes);
    }

    [Theory]
    [InlineData("1.1.0", "../evil.zip")]
    [InlineData("1.1.0", "sub/evil.zip")]
    [InlineData("1.1.0", "payload.7z")]
    [InlineData("not-a-version", "a.zip")]
    public void ManifestRejectsUnsafeOrMalformedFields(string version, string package)
    {
        string json = $$"""
        {
          "version": "{{version}}",
          "package": "{{package}}",
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "size": 1
        }
        """;

        Assert.False(UpdateManifest.TryParse(json, out _, out string error));
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    [Fact]
    public void ManifestRejectsBadDigest()
    {
        string json = """
        {
          "version": "1.1.0",
          "package": "a.zip",
          "sha256": "abc",
          "size": 1
        }
        """;

        Assert.False(UpdateManifest.TryParse(json, out _, out _));
    }

    [Fact]
    public void SourceSupportsLocalDirectoryAndHttp()
    {
        Assert.True(UpdateSource.TryParse(@"D:\MediaHub-Update", out var local, out _));
        Assert.Equal(UpdateSourceKind.LocalDirectory, local.Kind);
        Assert.Equal(Path.Combine(@"D:\MediaHub-Update", "update.json"), local.ManifestLocation);

        Assert.True(UpdateSource.TryParse("https://example.com/mediahub", out var http, out _));
        Assert.Equal(UpdateSourceKind.Http, http.Kind);
        Assert.Equal("https://example.com/mediahub/update.json", http.ManifestLocation);
        Assert.Equal("https://example.com/mediahub/a.zip", http.PackageLocation("a.zip"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("ftp://example.com/updates")]
    public void SourceRejectsUnsupportedInput(string value)
    {
        Assert.False(UpdateSource.TryParse(value, out _, out _));
    }

    [Fact]
    public async Task LocalDownloadIsVerifiedAndReused()
    {
        using var dir = new TempDirectory();
        byte[] payload = Encoding.UTF8.GetBytes("not really a zip, but bytes are enough for hashing");
        string packagePath = Path.Combine(dir.Path, "a.zip");
        await File.WriteAllBytesAsync(packagePath, payload);
        string hash = UpdateClient.ComputeSha256(packagePath);

        var manifest = new UpdateManifest { Version = "1.1.0.0", Package = "a.zip", Sha256 = hash, Size = payload.Length };
        Assert.True(UpdateSource.TryParse(dir.Path, out var source, out _));

        using var client = new UpdateClient();
        string downloaded = await client.DownloadPackageAsync(source, manifest, Path.Combine(dir.Path, "out"));

        Assert.True(File.Exists(downloaded));
        Assert.Equal(hash, UpdateClient.ComputeSha256(downloaded));

        // A second call reuses the verified file instead of failing because the destination exists.
        string again = await client.DownloadPackageAsync(source, manifest, Path.Combine(dir.Path, "out"));
        Assert.Equal(downloaded, again);
    }

    [Fact]
    public async Task DownloadRejectsHashMismatchAndLeavesNoPartFile()
    {
        using var dir = new TempDirectory();
        await File.WriteAllBytesAsync(Path.Combine(dir.Path, "a.zip"), new byte[] { 1, 2, 3 });
        var manifest = new UpdateManifest
        {
            Version = "1.1.0.0",
            Package = "a.zip",
            Sha256 = new string('0', 64),
            Size = 3,
        };
        Assert.True(UpdateSource.TryParse(dir.Path, out var source, out _));

        using var client = new UpdateClient();
        await Assert.ThrowsAsync<InvalidDataException>(
            () => client.DownloadPackageAsync(source, manifest, Path.Combine(dir.Path, "out")));

        Assert.False(File.Exists(Path.Combine(dir.Path, "out", "a.zip")));
        Assert.False(File.Exists(Path.Combine(dir.Path, "out", "a.zip.part")));
    }

    [Fact]
    public async Task HttpManifestIsReadFromTheStaticDirectory()
    {
        using var handler = new StubHandler(new Dictionary<string, string>
        {
            ["https://example.com/mediahub/update.json"] = """
            {
              "version": "1.2.0",
              "package": "MediaHub-Setup-win-x64-1.2.0.zip",
              "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
              "size": 42
            }
            """,
        });
        using var http = new HttpClient(handler);
        using var client = new UpdateClient(http);
        Assert.True(UpdateSource.TryParse("https://example.com/mediahub/", out var source, out _));

        var manifest = await client.GetManifestAsync(source);

        Assert.Equal("1.2.0.0", manifest.Version);
        Assert.Equal("MediaHub-Setup-win-x64-1.2.0.zip", manifest.Package);
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Dictionary<string, string> _responses;

        public StubHandler(Dictionary<string, string> responses) => _responses = responses;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            string key = request.RequestUri!.AbsoluteUri;
            if (!_responses.TryGetValue(key, out string? body))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
        }
    }

    private sealed class TempDirectory : IDisposable
    {
        public TempDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "mediahub-update-tests-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            try { Directory.Delete(Path, recursive: true); } catch { }
        }
    }
}

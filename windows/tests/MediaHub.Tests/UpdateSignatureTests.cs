using System.IO.Compression;
using MediaHub.Update;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Contract tests for the update-path trust rules added after review: a cleared remote channel is
/// refused, a loopback mirror stays usable, and a remote package must carry a publisher signature
/// the machine trusts before it is allowed to replace the installation.
/// </summary>
public sealed class UpdateSignatureTests
{
    private static string NewTempDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), "mediahub-update-sig-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }

    private static string WritePackage(string root, params (string Name, byte[] Bytes)[] entries)
    {
        string path = Path.Combine(root, "MediaHub-Setup-win-x64-1.2.0.zip");
        using var archive = ZipFile.Open(path, ZipArchiveMode.Create);
        foreach (var (name, bytes) in entries)
        {
            var entry = archive.CreateEntry(name, CompressionLevel.NoCompression);
            using var stream = entry.Open();
            stream.Write(bytes);
        }
        return path;
    }

    [Theory]
    [InlineData("http://updates.example.com/mediahub/")]
    [InlineData("http://203.0.113.10/mediahub/")]
    public void PlainHttpToARemoteHostIsRefused(string raw)
    {
        Assert.False(UpdateSource.TryParse(raw, out _, out string error));
        Assert.Contains("loopback", error, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("http://127.0.0.1:8099/mediahub/")]
    [InlineData("http://localhost:8099/mediahub/")]
    [InlineData("http://[::1]:8099/mediahub/")]
    public void LoopbackMirrorsStayAccepted(string raw)
    {
        Assert.True(UpdateSource.TryParse(raw, out var source, out string error), error);
        Assert.Equal(UpdateSourceKind.Http, source.Kind);
        Assert.True(source.IsLoopback);
        Assert.False(source.RequiresSignedPackage);
    }

    [Theory]
    [InlineData("https://updates.example.com/mediahub/")]
    [InlineData("https://media.example.com:8443/mediahub/")]
    public void HttpsRequiresAPublisherSignature(string raw)
    {
        Assert.True(UpdateSource.TryParse(raw, out var source, out string error), error);
        Assert.False(source.IsLoopback);
        Assert.True(source.RequiresSignedPackage);
    }

    [Fact]
    public void ALocalDirectoryDoesNotRequireASignature()
    {
        string root = NewTempDirectory();
        try
        {
            Assert.True(UpdateSource.TryParse(root, out var source, out string error), error);
            Assert.Equal(UpdateSourceKind.LocalDirectory, source.Kind);
            Assert.False(source.RequiresSignedPackage);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void APackageWithoutTheApplicationIsRefused()
    {
        string root = NewTempDirectory();
        try
        {
            string package = WritePackage(root, ("README.txt", "no application here"u8.ToArray()));
            var error = Assert.Throws<InvalidDataException>(() => UpdatePackageVerifier.VerifySignedPackage(package));
            Assert.Contains("does not contain MediaHub.exe", error.Message);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void AnUnsignedApplicationIsRefused()
    {
        string root = NewTempDirectory();
        try
        {
            // A real MediaHub.exe from a debug build is unsigned too; a stub keeps the test independent
            // of which build happened to run last. What matters is that the check runs on the extracted
            // file rather than on the digest.
            byte[] stub = new byte[66];
            stub[0] = (byte)'M';
            stub[1] = (byte)'Z';
            string package = WritePackage(root, ("app/MediaHub.exe", stub), ("app/README.txt", "x"u8.ToArray()));
            var error = Assert.Throws<InvalidDataException>(() => UpdatePackageVerifier.VerifySignedPackage(package));
            Assert.Contains("publisher", error.Message);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}

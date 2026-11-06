namespace MediaHub.Tests;

using System;
using System.IO;
using System.Text.Json;
using MediaHub.SyncCli;
using Xunit;

/// <summary>
/// W6-20: the two input-handling defects the W12b adversarial pass found in the CLI.
/// A --root that names a regular file used to be read as an empty store (counts of 0, exit 0), and
/// --out was neither normalized nor refused when it named a directory. Both are covered here from
/// the outside: every test drives SyncCliProgram.Run and reads the process-facing strings only.
/// </summary>
public sealed class SyncCliRootAndOutputPathTests : IDisposable
{
    private const string RootNotADirectory = "root-not-a-directory";
    private const string OutputIsDirectory = "output-is-directory";
    private static readonly DateTimeOffset FixedNow = DateTimeOffset.FromUnixTimeMilliseconds(1_760_000_000_000L);

    private readonly string _root = Path.Combine(Path.GetTempPath(), "mhcli-root-" + Guid.NewGuid().ToString("N"));

    public SyncCliRootAndOutputPathTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try
        {
            Directory.Delete(_root, true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    // ---- a --root that is a regular file -----------------------------------------------------

    [Fact]
    public void Export_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run("export", "--root", rootFile, "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void Import_RefusesARootThatIsAFile()
    {
        string envelope = MakeEnvelope();
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) =
            Run("import", "--root", rootFile, "--in", envelope, "--apply", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void Plan_RefusesARootThatIsAFile()
    {
        string envelope = MakeEnvelope();
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run("plan", "--root", rootFile, "--in", envelope, "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void SecretCheck_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run(
            "secret-check", "--root", rootFile, "--id", "src-1", "--expect", "AAAA", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void BaselineShow_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run("baseline", "show", "--root", rootFile, "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void BaselineForget_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) =
            Run("baseline", "forget", "--root", rootFile, "--device", "peer-1", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void PairList_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run("pair", "list", "--root", rootFile, "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void PairForget_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) =
            Run("pair", "forget", "--root", rootFile, "--device", "dev-x", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void TransferSend_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "send", "--to", "127.0.0.1:1", "--root", rootFile, "--device", "dev-a", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void TransferRoundtrip_RefusesARootThatIsAFile()
    {
        string rootFile = MakeRootFile();
        (int exitCode, string stdout, string stderr) = Run(
            "transfer", "roundtrip", "--root", rootFile, "--device-a", "dev-a", "--device-b", "dev-b", "--json");
        AssertRootNotADirectory(exitCode, stdout, stderr, rootFile);
    }

    [Fact]
    public void RefusedRoot_IsLeftAloneAndCreatesNoStoreFiles()
    {
        string rootFile = MakeRootFile();
        byte[] before = File.ReadAllBytes(rootFile);

        (int exitCode, _, _) = Run("export", "--root", rootFile, "--json");

        Assert.Equal(2, exitCode);
        Assert.Equal(before, File.ReadAllBytes(rootFile));
        Assert.True(File.Exists(rootFile));
        // Nothing else appeared next to it: no sync-sources.json, no baseline, no envelope.
        Assert.Single(Directory.GetFileSystemEntries(_root));
    }

    // ---- rootExisted ---------------------------------------------------------------------------

    [Fact]
    public void Export_ReportsRootExistedForADirectoryThatIsAlreadyThere()
    {
        string store = Path.Combine(_root, "store");
        Directory.CreateDirectory(store);

        (int exitCode, string stdout, _) = Run("export", "--root", store, "--json");

        Assert.Equal(0, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.True(document.RootElement.GetProperty("rootExisted").GetBoolean());
        Assert.Equal(store, document.RootElement.GetProperty("root").GetString());
    }

    [Fact]
    public void Export_ReportsThatANewRootDidNotExistYet()
    {
        string missing = Path.Combine(_root, "brand-new-root");

        (int exitCode, string stdout, _) = Run("export", "--root", missing, "--json");

        Assert.Equal(0, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.False(document.RootElement.GetProperty("rootExisted").GetBoolean());
        string path = document.RootElement.GetProperty("path").GetString() ?? "";
        Assert.Equal(Path.Combine(missing, SyncCliRunner.DefaultEnvelopeFileName), path);
        Assert.True(File.Exists(path));
    }

    [Fact]
    public void PairList_ReportsRootExistedAsWell()
    {
        string store = Path.Combine(_root, "store");
        Directory.CreateDirectory(store);

        (int exitCode, string stdout, _) = Run("pair", "list", "--root", store, "--json");

        Assert.Equal(0, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.True(document.RootElement.GetProperty("rootExisted").GetBoolean());
    }

    // ---- --out normalization and directory refusal ---------------------------------------------

    [Fact]
    public void Export_NormalizesDotDotSegmentsInTheOutPath()
    {
        string store = Path.Combine(_root, "store");
        Directory.CreateDirectory(store);
        string dotted = Path.Combine(_root, "a", "..", "b", "x.json");
        string expected = Path.GetFullPath(dotted);

        (int exitCode, string stdout, _) = Run("export", "--root", store, "--out", dotted, "--json");

        Assert.Equal(0, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        string reported = document.RootElement.GetProperty("path").GetString() ?? "";
        Assert.Equal(expected, reported);
        Assert.DoesNotContain("..", reported);
        Assert.True(File.Exists(expected));
        Assert.True(File.Exists(Path.Combine(_root, "b", "x.json")));
    }

    [Fact]
    public void Export_NormalizesTheSamePathInTextMode()
    {
        string store = Path.Combine(_root, "store");
        Directory.CreateDirectory(store);
        string dotted = Path.Combine(_root, "a", "..", "b", "y.json");
        string expected = Path.GetFullPath(dotted);

        (int exitCode, string stdout, _) = Run("export", "--root", store, "--out", dotted);

        Assert.Equal(0, exitCode);
        Assert.Contains(expected, stdout);
        Assert.DoesNotContain("..", stdout);
        Assert.True(File.Exists(expected));
    }

    [Fact]
    public void Export_RefusesAnOutThatNamesAnExistingDirectory()
    {
        string store = Path.Combine(_root, "store");
        Directory.CreateDirectory(store);
        string outDirectory = Path.Combine(_root, "outdir");
        Directory.CreateDirectory(outDirectory);

        (int exitCode, string stdout, string stderr) = Run("export", "--root", store, "--out", outDirectory, "--json");

        Assert.Equal(2, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement error = document.RootElement.GetProperty("error");
        Assert.Equal(OutputIsDirectory, error.GetProperty("code").GetString());
        Assert.Contains(outDirectory, error.GetProperty("message").GetString() ?? "");
        Assert.Contains("error " + OutputIsDirectory, stderr);
        Assert.Empty(Directory.GetFileSystemEntries(outDirectory));
    }

    [Fact]
    public void Export_RefusesADirectoryOutBeforeTheRootIsEvenRead()
    {
        // The root check comes first: a valid --out must not turn a broken --root into a success.
        string rootFile = MakeRootFile();
        string outDirectory = Path.Combine(_root, "outdir");
        Directory.CreateDirectory(outDirectory);

        (int exitCode, string stdout, _) = Run("export", "--root", rootFile, "--out", outDirectory, "--json");

        Assert.Equal(2, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        Assert.Equal(RootNotADirectory, document.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Empty(Directory.GetFileSystemEntries(outDirectory));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private (int ExitCode, string Stdout, string Stderr) Run(params string[] args)
    {
        using StringWriter stdout = new();
        using StringWriter stderr = new();
        int exitCode = SyncCliProgram.Run(args, stdout, stderr, () => FixedNow);
        return (exitCode, stdout.ToString(), stderr.ToString());
    }

    private static JsonDocument ParseSingleDocument(string stdout)
    {
        string text = stdout.Trim();
        Assert.False(text.Length == 0, "expected exactly one JSON document on stdout");
        Assert.DoesNotContain("\n", text);
        return JsonDocument.Parse(text);
    }

    private static void AssertRootNotADirectory(int exitCode, string stdout, string stderr, string rootPath)
    {
        Assert.Equal(2, exitCode);
        using JsonDocument document = ParseSingleDocument(stdout);
        JsonElement error = document.RootElement.GetProperty("error");
        Assert.Equal(RootNotADirectory, error.GetProperty("code").GetString());
        Assert.Contains(rootPath, error.GetProperty("message").GetString() ?? "");
        Assert.Contains("error " + RootNotADirectory, stderr);
        Assert.Contains(rootPath, stderr);
    }

    private string MakeRootFile()
    {
        string path = Path.Combine(_root, "root-is-a-file.txt");
        File.WriteAllText(path, "not a store directory");
        return path;
    }

    private string MakeEnvelope()
    {
        string source = Path.Combine(_root, "source");
        Directory.CreateDirectory(source);
        string envelope = Path.Combine(_root, "peer-envelope.json");
        (int exitCode, _, string stderr) = Run("export", "--root", source, "--out", envelope);
        Assert.Equal(0, exitCode);
        Assert.True(File.Exists(envelope), stderr);
        return envelope;
    }
}

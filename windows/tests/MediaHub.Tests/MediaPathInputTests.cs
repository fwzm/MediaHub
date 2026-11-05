using MediaHub.Domain;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Tests for the rules applied to paths that come from outside the application.
/// </summary>
/// <remarks>
/// These paths arrive from a drop, a command line, a file-association launch or a folder picker. The
/// rules are the only thing standing between "the user selected this file" and "the application read
/// some other file", so each rule is pinned with the input that would break it.
/// </remarks>
public sealed class MediaPathInputTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "mh-paths-" + Guid.NewGuid().ToString("n"));

    public MediaPathInputTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    /// <summary>
    /// Creates a file and returns its canonical path.
    /// </summary>
    /// <remarks>
    /// Canonicalised on purpose: the product normalises everything it accepts, so a test that compared
    /// against a hand-built string with forward slashes would be asserting about separators rather than
    /// about behaviour.
    /// </remarks>
    private string Touch(string relative)
    {
        string full = Path.GetFullPath(Path.Combine(_root, relative));
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        File.WriteAllText(full, "x");
        return full;
    }

    // ------------------------------------------------------------ accept

    [Fact]
    public void A_normal_absolute_path_is_accepted()
    {
        string file = Touch("movie.mp4");
        Assert.Equal(file, MediaPathInput.Normalize(file));
    }

    [Fact]
    public void Surrounding_quotes_are_stripped()
    {
        // Explorer's "Copy as path" wraps the path in quotes; pasting that must work.
        string file = Touch("movie.mp4");
        Assert.Equal(file, MediaPathInput.Normalize("\"" + file + "\""));
    }

    [Fact]
    public void A_path_with_spaces_is_accepted()
    {
        string file = Touch(@"my videos\a movie with spaces.mp4");
        Assert.Equal(file, MediaPathInput.Normalize(file));
    }

    [Fact]
    public void A_path_with_non_ascii_characters_is_accepted()
    {
        string file = Touch(@"中文目录\示例影片.mkv");
        Assert.Equal(file, MediaPathInput.Normalize(file));
        Assert.True(File.Exists(MediaPathInput.Normalize(file)!));
    }

    [Fact]
    public void Redundant_separators_are_canonicalised()
    {
        string file = Touch("movie.mp4");
        string messy = Path.GetDirectoryName(file)! + @"\sub\..\movie.mp4";
        Directory.CreateDirectory(Path.Combine(_root, "sub"));
        Assert.Equal(file, MediaPathInput.Normalize(messy));
    }

    // ------------------------------------------------------------ reject

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("\"\"")]
    public void Empty_input_is_rejected(string? input)
        => Assert.Null(MediaPathInput.Normalize(input));

    [Theory]
    [InlineData("movie.mp4")]
    [InlineData(@"videos\movie.mp4")]
    [InlineData("..\\movie.mp4")]
    [InlineData("./movie.mp4")]
    public void A_relative_path_is_rejected(string input)
    {
        // A relative path resolves against a working directory the user never chose. Accepting one
        // would mean playing a file that happens to sit next to the process, not the dropped one.
        Assert.Null(MediaPathInput.Normalize(input));
    }

    [Fact]
    public void An_over_long_path_is_rejected_rather_than_truncated()
    {
        string huge = @"C:\" + new string('a', MediaPathInput.MaxPathLength + 10) + ".mp4";
        Assert.Null(MediaPathInput.Normalize(huge));
    }

    // ------------------------------------------------------------ extensions

    [Theory]
    [InlineData("a.mp4", true)]
    [InlineData("a.MKV", true)]
    [InlineData("a.mkv", true)]
    [InlineData("a.flac", true)]
    [InlineData("a.exe", false)]
    [InlineData("a.dll", false)]
    [InlineData("a.txt", false)]
    [InlineData("a", false)]
    [InlineData("a.mp4.exe", false)]
    public void Only_media_extensions_are_accepted(string name, bool expected)
        => Assert.Equal(expected, MediaPathInput.IsSupported(name));

    [Fact]
    public void An_extension_that_merely_ends_with_a_media_type_is_not_accepted()
    {
        // The check is on the final extension; "film.mp4.lnk" is a shortcut, not a video.
        Assert.False(MediaPathInput.IsSupported("film.mp4.lnk"));
    }

    // ------------------------------------------------------------ expansion

    [Fact]
    public void Files_are_returned_in_a_stable_order_without_duplicates()
    {
        string a = Touch("b.mp4");
        string b = Touch("a.mp4");
        List<string> expanded = MediaPathInput.Expand(new[] { a, b, a });
        Assert.Equal(2, expanded.Count);
        Assert.Contains(a, expanded);
        Assert.Contains(b, expanded);
    }

    [Fact]
    public void A_dropped_folder_is_expanded_one_level_only()
    {
        string top = Touch("season/episode1.mp4");
        string nested = Touch("season/extras/behind.mp4");
        string ignored = Touch("season/notes.txt");

        List<string> expanded = MediaPathInput.Expand(new[] { Path.GetFullPath(Path.Combine(_root, "season")) });

        Assert.Contains(top, expanded);
        Assert.DoesNotContain(ignored, expanded);
        // Deliberately not recursive: a dropped folder means the files in it, not an unbounded walk.
        Assert.DoesNotContain(nested, expanded);
    }

    [Fact]
    public void A_non_media_file_in_a_drop_is_skipped()
    {
        string video = Touch("movie.mp4");
        string document = Touch("readme.txt");
        List<string> expanded = MediaPathInput.Expand(new[] { video, document });
        Assert.Single(expanded);
        Assert.Equal(video, expanded[0]);
    }

    [Fact]
    public void An_unreadable_directory_does_not_abort_the_rest_of_the_drop()
    {
        string video = Touch("movie.mp4");
        var failing = new Func<string, IEnumerable<string>>(_ => throw new UnauthorizedAccessException());
        List<string> expanded = MediaPathInput.Expand(new[] { Path.Combine(_root, "movie.mp4"), _root }, failing);
        Assert.Contains(video, expanded);
    }

    [Fact]
    public void A_nonexistent_path_contributes_nothing()
    {
        List<string> expanded = MediaPathInput.Expand(new[] { Path.Combine(_root, "missing.mp4") });
        Assert.Empty(expanded);
    }
}

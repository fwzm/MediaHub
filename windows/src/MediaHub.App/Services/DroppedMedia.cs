using MediaHub.Domain;

namespace MediaHub.App.Services;

/// <summary>Turns shell-supplied paths into playback requests.</summary>
/// <remarks>
/// The validation rules live in <see cref="MediaHub.Domain.MediaPathInput"/> so they can be tested
/// without a window. This type only adapts them to the application's playback request.
/// </remarks>
public static class DroppedMedia
{
    public static string[] SupportedExtensions => MediaHub.Domain.MediaPathInput.SupportedExtensions;

    public static bool IsSupported(string path) => MediaHub.Domain.MediaPathInput.IsSupported(path);

    public static List<string> Expand(IEnumerable<string> paths) => MediaHub.Domain.MediaPathInput.Expand(paths);

    public static string? Normalize(string? path) => MediaHub.Domain.MediaPathInput.Normalize(path);

    public static PlaybackRequest ToRequest(string path)
        => new()
        {
            LocalPath = path,
            Title = Path.GetFileNameWithoutExtension(path),
            DisplayUrl = path,
            ExternalSubtitles = LocalLibraryService.FindSiblingSubtitles(path),
        };
}

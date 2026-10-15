using MediaHub.Domain;

namespace MediaHub.App.Services;

/// <summary>
/// Navigation parameters for the folder browser.
/// </summary>
/// <remarks>
/// <see cref="FolderId"/> and <see cref="FolderPath"/> are both carried because a browse source
/// identifies a folder by URL while the provider needs the path to re-validate containment. A null
/// id means "the source root", which only the provider knows.
/// </remarks>
public sealed record BrowseNavigation(string ServerId, string? FolderId, string? FolderPath, string Title);

/// <summary>Navigation parameters for the library page.</summary>
public sealed record LibraryNavigation(string ServerId, string LibraryId, string LibraryName);

/// <summary>Navigation parameters for the detail page.</summary>
public sealed record DetailNavigation(string ServerId, string ItemId, string Title);

/// <summary>
/// What the player page was asked to play.
/// </summary>
/// <remarks>
/// <see cref="DisplayUrl"/> exists because the URL handed to the engine may be a loopback bridge
/// address carrying a per-session token. That token is a secret and must never reach a title bar, a
/// tooltip or a log, so the page displays this value instead.
/// </remarks>
public sealed record PlaybackRequest
{
    public required string Title { get; init; }
    public string? ServerId { get; init; }
    public string? ItemId { get; init; }

    /// <summary>Direct local file. Mutually exclusive with <see cref="ServerId"/>.</summary>
    public string? LocalPath { get; init; }

    /// <summary>Human-readable, credential-free URL shown in the UI.</summary>
    public string DisplayUrl { get; init; } = "";

    public long StartPositionTicks { get; init; }
    public TimeSpan? DeclaredDuration { get; init; }
    public IReadOnlyList<string> ExternalSubtitles { get; init; } = Array.Empty<string>();

    /// <summary>Series this item belongs to, when it is an episode. Enables auto-advance.</summary>
    public string? SeriesId { get; init; }

    public int? SeasonNumber { get; init; }
    public int? EpisodeNumber { get; init; }
}

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
/// <remarks>
/// <para>
/// <see cref="FolderId"/> is set when the page shows the contents of a folder inside the library
/// rather than the library root. Emby keeps one physical folder per title, so its library root lists
/// folders (and a folder cannot be played); Jellyfin flattens the same library into titles. Without
/// this the Emby path dead-ends on a folder whose "play" is a 500 from PlaybackInfo.
/// </para>
/// <para>
/// <see cref="LibraryId"/> always names the library itself so going back up stays inside it.
/// </para>
/// </remarks>
public sealed record LibraryNavigation(
    string ServerId,
    string LibraryId,
    string LibraryName,
    string? FolderId = null,
    string? FolderTitle = null);

/// <summary>Navigation parameters for the detail page.</summary>
public sealed record DetailNavigation(string ServerId, string ItemId, string Title);

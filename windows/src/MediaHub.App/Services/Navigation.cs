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

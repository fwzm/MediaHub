using Microsoft.UI;
using Microsoft.Windows.Storage.Pickers;

namespace MediaHub.App.Services;

/// <summary>Opens the Windows file and folder pickers on behalf of the current window.</summary>
/// <remarks>
/// <para>
/// Only the Windows App SDK pickers are used. The legacy <c>Windows.Storage.Pickers</c> types need
/// <c>InitializeWithWindow</c> and, against this unpackaged window, they create no dialog at all: the
/// awaited call neither returns nor throws, so the command that opened it silently does nothing. That
/// behaviour was measured on the "open video" command; every other command that used the same API
/// family was equally broken and was moved here rather than left as a minefield.
/// </para>
/// <para>
/// Funnelling every call site through this type means the window id is passed to a picker in exactly
/// one place, and the accepted extensions come from the service that scans for those files instead of
/// being retyped per caller.
/// </para>
/// </remarks>
public static class FilePickerService
{
    /// <summary>The window the dialog should be owned by, or null before the window exists.</summary>
    private static WindowId? OwnerWindowId => (App.MainWindow as MainWindow)?.AppWindow.Id;

    /// <summary>Returns the chosen file, or null when the user cancelled.</summary>
    public static async Task<string?> PickFileAsync(
        IEnumerable<string> extensions,
        PickerLocationId start = PickerLocationId.VideosLibrary)
    {
        if (OwnerWindowId is not { } windowId) return null;

        var picker = new FileOpenPicker(windowId) { SuggestedStartLocation = start };
        AddFilters(picker, extensions);

        PickFileResult? result = await picker.PickSingleFileAsync();
        return string.IsNullOrEmpty(result?.Path) ? null : result.Path;
    }

    /// <summary>Returns the chosen files in the order the user picked them; empty when cancelled.</summary>
    public static async Task<IReadOnlyList<string>> PickFilesAsync(
        IEnumerable<string> extensions,
        PickerLocationId start = PickerLocationId.VideosLibrary)
    {
        if (OwnerWindowId is not { } windowId) return Array.Empty<string>();

        var picker = new FileOpenPicker(windowId) { SuggestedStartLocation = start };
        AddFilters(picker, extensions);

        IReadOnlyList<PickFileResult> results = await picker.PickMultipleFilesAsync();
        if (results is null) return Array.Empty<string>();

        var paths = new List<string>(results.Count);
        foreach (PickFileResult result in results)
        {
            if (!string.IsNullOrEmpty(result?.Path)) paths.Add(result.Path);
        }
        return paths;
    }

    /// <summary>Returns the chosen folder, or null when the user cancelled.</summary>
    public static async Task<string?> PickFolderAsync(
        PickerLocationId start = PickerLocationId.VideosLibrary)
    {
        if (OwnerWindowId is not { } windowId) return null;

        var picker = new FolderPicker(windowId) { SuggestedStartLocation = start };
        PickFolderResult? result = await picker.PickSingleFolderAsync();
        return string.IsNullOrEmpty(result?.Path) ? null : result.Path;
    }

    private static void AddFilters(FileOpenPicker picker, IEnumerable<string> extensions)
    {
        // A picker with no filter throws instead of showing everything, so a caller that passes an
        // empty set gets a wildcard rather than an exception on the user's click.
        int added = 0;
        foreach (string extension in extensions)
        {
            if (string.IsNullOrWhiteSpace(extension)) continue;
            picker.FileTypeFilter.Add(extension);
            added++;
        }
        if (added == 0) picker.FileTypeFilter.Add("*");
    }
}

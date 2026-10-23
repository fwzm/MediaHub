using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

/// <summary>
/// The one-question "name this playlist" dialog, shared by the playlist page and the detail page's
/// "add to playlist" menu.
/// </summary>
/// <remarks>
/// Kept in one place so both entry points reject a blank name identically: a playlist is created only
/// by an explicit, non-empty name, and a cancelled dialog creates nothing.
/// </remarks>
internal static class PlaylistNameDialog
{
    /// <summary>
    /// Shows the dialog. Returns the trimmed name, <see cref="string.Empty"/> when the user confirmed
    /// a blank name, or <c>null</c> when the dialog was cancelled.
    /// </summary>
    public static async Task<string?> PromptAsync(XamlRoot xamlRoot, string title, string label, string initialName)
    {
        var nameBox = new TextBox
        {
            Text = initialName,
            PlaceholderText = "例如：以后再看",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };

        // Stable hooks for the GUI acceptance script: it types a name here and confirms.
        AutomationProperties.SetAutomationId(nameBox, "playlist.name.input");
        AutomationProperties.SetName(nameBox, "播放列表名称");

        var panel = new StackPanel { Spacing = 8, Width = 360 };
        panel.Children.Add(new TextBlock { Text = label });
        panel.Children.Add(nameBox);

        var dialog = new ContentDialog
        {
            XamlRoot = xamlRoot,
            Title = title,
            Content = panel,
            PrimaryButtonText = "确定",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };

        AutomationProperties.SetAutomationId(dialog, "playlist.name.dialog");

        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return null;
        return nameBox.Text.Trim();
    }
}

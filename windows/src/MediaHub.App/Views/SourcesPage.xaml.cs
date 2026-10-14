using MediaHub.App.Services;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Views;

public sealed partial class SourcesPage : Page
{
    public SourcesPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Refresh();
    }

    private void Refresh()
    {
        var rows = App.Services.SourceRegistry.Handles
            .Select(h => new SourceRow(h.Server.DisplayName, h.Descriptor.DisplayName, h.CapabilitySummary))
            .ToList();
        SourceList.ItemsSource = rows;
        EmptyText.Visibility = rows.Count == 0 ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
    }

    private sealed record SourceRow(string Name, string Kind, string Capabilities);
}

using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace MediaHub.App.Controls;

/// <summary>
/// Drives the custom focus ring that the button templates draw.
/// </summary>
/// <remarks>
/// WinUI only runs a template's FocusStates when the control opts out of the system focus visual,
/// and the built-in Button does not do that on our behalf. Attaching this property is the smallest
/// way to keep the ring in the template, where it can follow the button's corner radius, instead of
/// falling back to a rectangle drawn by the system.
/// </remarks>
public static class ButtonChrome
{
    public static readonly DependencyProperty TrackFocusProperty =
        DependencyProperty.RegisterAttached(
            "TrackFocus", typeof(bool), typeof(ButtonChrome),
            new PropertyMetadata(false, OnTrackFocusChanged));

    public static void SetTrackFocus(DependencyObject element, bool value)
        => element.SetValue(TrackFocusProperty, value);

    public static bool GetTrackFocus(DependencyObject element)
        => (bool)element.GetValue(TrackFocusProperty);

    private static void OnTrackFocusChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Control control || e.NewValue is not true) return;

        // Keyboard focus is the only thing that should show the ring. Pointer clicks do not, which
        // is why this listens to GotFocus instead of to the common state group.
        control.GotFocus += (_, _) => VisualStateManager.GoToState(control, "Focused", true);
        control.LostFocus += (_, _) => VisualStateManager.GoToState(control, "Unfocused", true);
    }
}

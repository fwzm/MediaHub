using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Hosting;

namespace MediaHub.App.Services;

/// <summary>
/// The two motions the shell uses: a slow skeleton pulse while a read is in flight, and a short
/// fade when a panel replaces the skeleton.
/// </summary>
/// <remarks>
/// Both run on the compositor rather than the XAML timeline, so they keep their cadence while the UI
/// thread is busy parsing a page of results. Neither animates layout: only opacity is touched, which
/// is what keeps a grid of hundreds of tiles from re-measuring on every frame.
/// </remarks>
internal static class UiMotion
{
    public static void Pulse(FrameworkElement element)
    {
        Visual visual = ElementVisual(element);
        ScalarKeyFrameAnimation animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(0f, 1f);
        animation.InsertKeyFrame(1f, 0.55f);
        animation.Duration = TimeSpan.FromMilliseconds(1100);
        animation.IterationBehavior = AnimationIterationBehavior.Forever;
        animation.Direction = AnimationDirection.Alternate;
        visual.StartAnimation("Opacity", animation);
    }

    /// <summary>Stops the pulse and restores full opacity before the element is hidden.</summary>
    public static void Settle(FrameworkElement element)
    {
        Visual visual = ElementVisual(element);
        visual.StopAnimation("Opacity");
        visual.Opacity = 1f;
    }

    public static void FadeIn(FrameworkElement element, int durationMs = 220)
    {
        Visual visual = ElementVisual(element);
        visual.Opacity = 0f;

        ScalarKeyFrameAnimation animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(1f, 1f, visual.Compositor.CreateCubicBezierEasingFunction(
            new System.Numerics.Vector2(0.2f, 0.7f),
            new System.Numerics.Vector2(0.2f, 1f)));
        animation.Duration = TimeSpan.FromMilliseconds(durationMs);
        visual.StartAnimation("Opacity", animation);
    }

    private static Visual ElementVisual(FrameworkElement element)
        => ElementCompositionPreview.GetElementVisual(element);
}

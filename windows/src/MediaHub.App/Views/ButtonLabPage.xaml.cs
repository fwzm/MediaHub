using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using System.Collections.Generic;

namespace MediaHub.App.Views;

/// <summary>
/// Renders every button role in every visual state on one page so a background capture can show
/// hover, press and focus without any pointer or keyboard input reaching the desktop.
/// </summary>
public sealed partial class ButtonLabPage : Page
{
    public ButtonLabPage()
    {
        InitializeComponent();

        string scheme = Environment.GetEnvironmentVariable("MEDIAHUB_BUTTON_SCHEME") ?? "current";
        (SchemeName.Text, SchemeNote.Text) = scheme.ToLowerInvariant() switch
        {
            "prism" => ("方案 A · 棱镜 Prism Rim",
                        "双层内沿高光 + 顶部弧形光穹 + 底缘接触暗边。悬停放大 1.2%，按下 1.6%，是三者里最接近液态玻璃的一次。"),
            "satin" => ("方案 B · 缎面 Satin Alloy",
                        "宽幅哑光受光 + 机加工 1px 实边 + 底缘接触阴影。悬停上移 1px，按下回落半像素，最克制、最像工具。"),
            "filigree" => ("方案 C · 光边 Filigree Edge",
                           "近平面板 + 顶白底品牌色的 1px 发丝边 + 外圈低透明度光晕。悬停上移 1px，纯色降级下最稳。"),
            _ => ("按钮现状（基线）",
                  "纵向渐变 + 顶沿 1px 高光 + 四段描边。此页用于横向对照，不随方案切换。"),
        };

        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        // The templates are applied as part of layout, so the state call has to wait for the first
        // pass to finish. GoToState with transitions on is what makes the scale/travel legible.
        DispatcherQueue.TryEnqueue(() =>
        {
            UpdateLayout();
            foreach (Button button in FindButtons(this))
            {
                button.ApplyTemplate();
                if (button.Tag is string state && state is "PointerOver" or "Pressed" or "Focused")
                {
                    VisualStateManager.GoToState(button, state, true);
                }
            }
        });
    }

    private static IEnumerable<Button> FindButtons(DependencyObject root)
    {
        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < count; i++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, i);
            if (child is Button button) yield return button;
            foreach (Button nested in FindButtons(child)) yield return nested;
        }
    }
}

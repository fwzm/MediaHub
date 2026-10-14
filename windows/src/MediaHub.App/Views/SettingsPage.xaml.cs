using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using MediaHub.Player.Mpv;

namespace MediaHub.App.Views;

public sealed partial class SettingsPage : Page
{
    private static readonly string[] Fonts =
    {
        "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "SimHei", "SimSun",
        "Source Han Sans SC", "Noto Sans CJK SC", "Arial",
    };

    private static readonly (string Hex, string Label)[] Colors =
    {
        ("#FFFFFF", "白色"), ("#FFF3C4", "暖黄"), ("#C8F0FF", "浅蓝"),
        ("#FFE066", "黄色"), ("#B9F6CA", "浅绿"),
    };

    private static readonly string[] AssOverrides = { "no", "yes", "force", "scale" };

    private static readonly (string Value, string Label)[] HwDecModes =
    {
        ("auto-safe", "自动（推荐，失败回退软件）"),
        ("d3d11va", "D3D11VA（硬件）"),
        ("d3d11va-copy", "D3D11VA 拷贝模式"),
        ("no", "软件解码"),
    };

    private bool _loading = true;

    public SettingsPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Load();
    }

    private void Load()
    {
        _loading = true;
        var s = App.Services.Settings.Current;

        SpeedCombo.ItemsSource = new[] { "0.5", "0.75", "1", "1.25", "1.5", "2" };
        SpeedCombo.SelectedItem = s.DefaultSpeed.ToString("0.##");
        VolumeSlider.Value = s.DefaultVolume;
        ResumeSwitch.IsOn = s.ResumePlayback;
        AutoPlayNextSwitch.IsOn = s.AutoPlayNext;

        SubtitleSwitch.IsOn = s.SubtitlesEnabled;
        SubtitleSizeSlider.Value = s.SubtitleFontSize;
        SubtitleFontCombo.ItemsSource = Fonts;
        SubtitleFontCombo.Text = s.SubtitleFont;
        SubtitleColorCombo.ItemsSource = Colors.Select(c => c.Label).ToList();
        SubtitleColorCombo.SelectedIndex = Math.Max(0, Array.FindIndex(Colors, c => c.Hex == s.SubtitleColor));
        SubtitleBorderSlider.Value = s.SubtitleBorderSize;
        SubtitlePosSlider.Value = s.SubtitlePosition;
        AssOverrideCombo.ItemsSource = AssOverrides;
        AssOverrideCombo.SelectedItem = s.SubtitleAssOverride;

        AutoHideSwitch.IsOn = s.AutoHideControls;
        DetailInfoSwitch.IsOn = s.ShowDetailedMediaInfo;

        HwDecCombo.ItemsSource = HwDecModes.Select(m => m.Label).ToList();
        int hwIdx = Array.FindIndex(HwDecModes, m => m.Value == (s.EngineOptions.TryGetValue("hwdec", out var v) ? v : "auto-safe"));
        HwDecCombo.SelectedIndex = hwIdx >= 0 ? hwIdx : 0;

        DiagnosticsText.Text = BuildDiagnostics();
        _loading = false;
    }

    private static string BuildDiagnostics()
    {
        var host = App.Services.PlayerHost;
        var s = App.Services.Settings.Current;
        string dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub");
        return string.Join(Environment.NewLine, new[]
        {
            $"libmpv client API: {MpvPlayer.ClientApiVersion}",
            $"引擎已初始化: {(host.IsInitialized ? "是" : "否")}",
            $"视频输出: {EngineOption(s, "vo")} / 上下文: {EngineOption(s, "gpu-context")}",
            $"硬件解码模式: {EngineOption(s, "hwdec")}",
            $"当前实际硬解: {host.Player?.CurrentHwDec ?? "未开始播放"}",
            $"设置目录: {dir}",
        });
    }

    private static string EngineOption(Services.MediaHubSettings s, string key)
        => s.EngineOptions.TryGetValue(key, out var v) ? v : "(未设置)";

    // ---------------------------------------------------------------- handlers

    private void OnVolumeChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.DefaultVolume = e.NewValue);
        App.Services.PlayerHost.Player?.SetVolume(e.NewValue);
    }

    private void OnSpeedChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || SpeedCombo.SelectedItem is not string v) return;
        if (double.TryParse(v, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double speed))
            App.Services.Settings.Update(s => s.DefaultSpeed = speed);
    }

    private void OnResumeToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.ResumePlayback = ResumeSwitch.IsOn);
    }

    private void OnAutoPlayNextToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.AutoPlayNext = AutoPlayNextSwitch.IsOn);
    }

    private void OnSubtitleToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.SubtitlesEnabled = SubtitleSwitch.IsOn);
        App.Services.PlayerHost.ApplySubtitleSettings();
    }

    private void OnSubtitleSizeChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.SubtitleFontSize = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitleFontSize(e.NewValue);
    }

    private void OnSubtitleFontChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        string? font = SubtitleFontCombo.SelectedItem as string ?? SubtitleFontCombo.Text;
        if (string.IsNullOrWhiteSpace(font)) return;
        App.Services.Settings.Update(s => s.SubtitleFont = font);
        App.Services.PlayerHost.Player?.SetSubtitleFont(font);
    }

    private void OnSubtitleColorChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        int idx = SubtitleColorCombo.SelectedIndex;
        if (idx < 0 || idx >= Colors.Length) return;
        App.Services.Settings.Update(s => s.SubtitleColor = Colors[idx].Hex);
        App.Services.PlayerHost.Player?.SetSubtitleColor(Colors[idx].Hex);
    }

    private void OnSubtitleBorderChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.SubtitleBorderSize = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitleBorderSize(e.NewValue);
    }

    private void OnSubtitlePosChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.SubtitlePosition = e.NewValue);
        App.Services.PlayerHost.Player?.SetSubtitlePosition(e.NewValue);
    }

    private void OnAssOverrideChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || AssOverrideCombo.SelectedItem is not string v) return;
        App.Services.Settings.Update(s => s.SubtitleAssOverride = v);
        App.Services.PlayerHost.ApplySubtitleSettings();
    }

    private void OnAutoHideToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.AutoHideControls = AutoHideSwitch.IsOn);
    }

    private void OnDetailInfoToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.ShowDetailedMediaInfo = DetailInfoSwitch.IsOn);
    }

    private void OnHwDecChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        int idx = HwDecCombo.SelectedIndex;
        if (idx < 0) return;
        App.Services.Settings.Update(s => s.EngineOptions["hwdec"] = HwDecModes[idx].Value);
        DiagnosticsText.Text = BuildDiagnostics();
    }

    private void OnExportLogClick(object sender, RoutedEventArgs e)
    {
        try
        {
            string dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub");
            System.IO.Directory.CreateDirectory(dir);
            string path = System.IO.Path.Combine(dir, $"diagnostics-{DateTime.Now:yyyyMMdd-HHmmss}.log");
            System.IO.File.WriteAllText(path, App.Services.Log.Export());
            DiagnosticsText.Text = $"已导出（已脱敏）：{path}";
        }
        catch (Exception ex)
        {
            DiagnosticsText.Text = $"导出失败：{ex.Message}";
        }
    }

    private void OnOpenLogFolderClick(object sender, RoutedEventArgs e)
    {
        try
        {
            string dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaHub");
            System.IO.Directory.CreateDirectory(dir);
            Process.Start(new ProcessStartInfo("explorer.exe", dir) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            DiagnosticsText.Text = $"打开失败：{ex.Message}";
        }
    }
}

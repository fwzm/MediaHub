using System.Diagnostics;
using MediaHub.App.Services;
using MediaHub.Net;
using MediaHub.Player;
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

    private static readonly (string Hex, string Label)[] Colors = SubtitleStyleOptions.Colors.ToArray();

    private static readonly string[] AssOverrides = { "no", "yes", "force", "scale" };

    private static readonly (string Value, string Label)[] HwDecModes =
    {
        ("auto-safe", "自动（推荐，失败回退软件）"),
        ("d3d11va", "D3D11VA（硬件）"),
        ("d3d11va-copy", "D3D11VA 拷贝模式"),
        ("no", "软件解码"),
    };

    private static readonly (string Value, string Label)[] HdrDynamicMetadataModes =
    {
        (MpvColorPipeline.FollowSourceValue, "跟随片源（默认）"),
        (MpvColorPipeline.SourceMetadataValue, "仅用片源元数据"),
        (MpvColorPipeline.ComputePeakValue, "引擎实时计算峰值"),
    };

    private static readonly (string Value, string Label)[] QualityPreferences =
    {
        ("auto", "自动（服务器顺序，直连优先）"),
        ("highest", "最高画质"),
        ("lowest", "最低画质 / 省带宽"),
    };

    private static readonly int[] DiskCacheCapacitiesMB = { 512, 1024, 2048, 4096, 8192 };

    private bool _loading = true;
    private bool _updateBusy;
    private UpdateCheckResult? _pendingUpdate;

    public SettingsPage()
    {
        InitializeComponent();
        Loaded += (_, _) => { Load(); RefreshAssociationStatus(); };
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

        QualityPreferenceCombo.ItemsSource = QualityPreferences.Select(q => q.Label).ToList();
        int qualityIndex = Array.FindIndex(QualityPreferences, q => q.Value == s.QualityPreference);
        QualityPreferenceCombo.SelectedIndex = qualityIndex >= 0 ? qualityIndex : 0;

        DiskCacheSwitch.IsOn = s.DiskCacheEnabled;
        DiskCacheCapacityCombo.ItemsSource = DiskCacheCapacitiesMB.Select(mb => $"{mb} MB").ToList();
        int capacityIndex = Array.FindIndex(DiskCacheCapacitiesMB, mb => mb == s.DiskCacheCapacityMB);
        DiskCacheCapacityCombo.SelectedIndex = capacityIndex >= 0 ? capacityIndex : 2;
        ClearDiskCacheSwitch.IsOn = s.ClearDiskCacheOnPlaybackEnd;
        RefreshDiskCacheUsage();

        MediaBadgesSwitch.IsOn = s.ShowMediaBadges;
        BadgeResolutionCheck.IsChecked = s.MediaBadgeItems.Contains("resolution");
        BadgeHdrCheck.IsChecked = s.MediaBadgeItems.Contains("hdr");
        BadgeVideoCheck.IsChecked = s.MediaBadgeItems.Contains("video");
        BadgeAudioCheck.IsChecked = s.MediaBadgeItems.Contains("audio");
        BadgeContainerCheck.IsChecked = s.MediaBadgeItems.Contains("container");

        SubtitleSwitch.IsOn = s.SubtitlesEnabled;
        SubtitleSizeSlider.Value = s.SubtitleFontSize;
        SubtitleFontCombo.ItemsSource = Fonts;
        SubtitleFontCombo.Text = s.SubtitleFont;
        SubtitleColorCombo.ItemsSource = Colors.Select(c => c.Label).ToList();
        SubtitleColorCombo.SelectedIndex = Math.Max(0, Array.FindIndex(Colors, c => c.Hex == s.SubtitleColor));
        SubtitleBorderSlider.Value = s.SubtitleBorderSize;
        SubtitlePosSlider.Value = s.SubtitlePosition;
        SubtitleBoldSwitch.IsOn = s.SubtitleBold;
        AssOverrideCombo.ItemsSource = AssOverrides;
        AssOverrideCombo.SelectedItem = s.SubtitleAssOverride;

        RefreshAudioDevices();

        ShortcutList.ItemsSource = PlayerShortcutCatalog.All;

        AutoHideSwitch.IsOn = s.AutoHideControls;
        DetailInfoSwitch.IsOn = s.ShowDetailedMediaInfo;
        ScreenshotSwitch.IsOn = s.AllowPlayerScreenshots;

        UpdateSourceBox.Text = s.UpdateSource ?? "";
        if (!App.Services.Update.CanSelfUpdate)
        {
            UpdateStatusText.Text = "当前运行目录不是通过安装器安装的版本（缺少 uninstall.ps1），一键更新不可用。";
        }
        else if (string.IsNullOrWhiteSpace(s.UpdateSource))
        {
            UpdateStatusText.Text = "尚未配置更新源。填写本地目录或 HTTP 静态目录后点“检查更新”。";
        }
        else
        {
            UpdateStatusText.Text = $"当前版本 {UpdateService.RawVersion}。点“检查更新”读取更新源。";
        }

        HwDecCombo.ItemsSource = HwDecModes.Select(m => m.Label).ToList();
        int hwIdx = Array.FindIndex(HwDecModes, m => m.Value == (s.EngineOptions.TryGetValue("hwdec", out var v) ? v : "auto-safe"));
        HwDecCombo.SelectedIndex = hwIdx >= 0 ? hwIdx : 0;

        HdrDynamicMetadataCombo.ItemsSource = HdrDynamicMetadataModes.Select(m => m.Label).ToList();
        int hdrIdx = Array.FindIndex(HdrDynamicMetadataModes, m => m.Value == s.HdrDynamicMetadata);
        HdrDynamicMetadataCombo.SelectedIndex = hdrIdx >= 0 ? hdrIdx : 0;

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

    private void OnQualityPreferenceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || QualityPreferenceCombo.SelectedIndex < 0) return;
        string value = QualityPreferences[QualityPreferenceCombo.SelectedIndex].Value;
        App.Services.Settings.Update(s => s.QualityPreference = value);
    }

    private void OnDiskCacheToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        bool enabled = DiskCacheSwitch.IsOn;
        App.Services.Settings.Update(s => s.DiskCacheEnabled = enabled);
        if (!enabled) App.Services.DiskCache.Clear();
        RefreshDiskCacheUsage();
    }

    private void OnDiskCacheCapacityChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || DiskCacheCapacityCombo.SelectedIndex < 0) return;
        int mb = DiskCacheCapacitiesMB[DiskCacheCapacityCombo.SelectedIndex];
        App.Services.Settings.Update(s => s.DiskCacheCapacityMB = mb);
        App.Services.ApplyDiskCacheCapacity();
        RefreshDiskCacheUsage();
    }

    private void OnClearDiskCacheToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.ClearDiskCacheOnPlaybackEnd = ClearDiskCacheSwitch.IsOn);
    }

    private void OnClearDiskCacheClick(object sender, RoutedEventArgs e)
    {
        long removed = App.Services.DiskCache.Clear();
        DiskCacheUsageText.Text = $"已清理 {FormatBytes(removed)}；当前占用 {FormatBytes(App.Services.DiskCache.GetUsageBytes())}。";
    }

    private void RefreshDiskCacheUsage()
    {
        long used = App.Services.DiskCache.GetUsageBytes();
        var s = App.Services.Settings.Current;
        DiskCacheUsageText.Text = s.DiskCacheEnabled
            ? $"当前占用 {FormatBytes(used)} / 上限 {s.DiskCacheCapacityMB} MB，目录：{App.Services.DiskCache.DirectoryPath}"
            : "磁盘缓存已关闭，目录为空。";
    }

    private static string FormatBytes(long bytes)
        => bytes >= 1024L * 1024 * 1024
            ? (bytes / 1024.0 / 1024 / 1024).ToString("0.0") + " GB"
            : (bytes / 1024.0 / 1024).ToString("0.0") + " MB";

    private void OnMediaBadgesToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.ShowMediaBadges = MediaBadgesSwitch.IsOn);
    }

    private void OnBadgeItemToggled(object sender, RoutedEventArgs e)
    {
        if (_loading || sender is not CheckBox box || box.Tag is not string tag) return;
        bool selected = box.IsChecked == true;
        App.Services.Settings.Update(s =>
        {
            s.MediaBadgeItems.Remove(tag);
            if (selected) s.MediaBadgeItems.Add(tag);
        });
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

    private void OnSubtitleBoldToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.SubtitleBold = SubtitleBoldSwitch.IsOn);
        App.Services.PlayerHost.Player?.SetSubtitleBold(SubtitleBoldSwitch.IsOn);
    }

    // ---------------------------------------------------------------- audio output

    /// <summary>Sentinel entry for "follow the system default"; the engine calls that "auto".</summary>
    private static readonly EngineAudioDevice AutoAudioDevice = new("", "自动（跟随系统默认）", true);

    /// <summary>
    /// Re-reads the device list from the engine. The engine is the source of truth for both names and
    /// current selection, so nothing here is cached across page visits: a headset plugged in while the
    /// app was running must show up without a restart.
    /// </summary>
    private void RefreshAudioDevices()
    {
        var devices = new List<EngineAudioDevice> { AutoAudioDevice };
        devices.AddRange(App.Services.PlayerHost.GetAudioDevices()
            .Where(d => !string.Equals(d.Name, "auto", StringComparison.OrdinalIgnoreCase)));

        string preferred = App.Services.Settings.Current.AudioDevice ?? "";
        AudioDeviceCombo.ItemsSource = devices;
        AudioDeviceCombo.SelectedItem =
            devices.FirstOrDefault(d => string.Equals(d.Name, preferred, StringComparison.OrdinalIgnoreCase))
            ?? AutoAudioDevice;
        UpdateAudioDeviceStatus(devices.Count > 1);
    }

    private void UpdateAudioDeviceStatus(bool engineReportedDevices)
    {
        string? fallback = App.Services.PlayerHost.AudioDeviceFallback;
        if (!string.IsNullOrEmpty(fallback))
        {
            AudioDeviceStatus.Text = fallback + "（可点“刷新设备”重新尝试）";
            return;
        }

        if (!engineReportedDevices)
        {
            AudioDeviceStatus.Text = "引擎尚未初始化，暂时只能选择自动；开始播放后点“刷新设备”可看到全部输出。";
            return;
        }

        string? current = App.Services.PlayerHost.CurrentAudioDevice;
        string description = App.Services.PlayerHost.DescribeAudioDevice(current);
        AudioDeviceStatus.Text = "当前输出：" + description + "。切换会立即生效，正在播放的音频也会转到新设备。";
    }

    private void OnAudioDeviceChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading || AudioDeviceCombo.SelectedItem is not EngineAudioDevice device) return;

        string? name = string.IsNullOrWhiteSpace(device.Name) ? null : device.Name;
        App.Services.Settings.Update(s => s.AudioDevice = name);
        App.Services.PlayerHost.TrySetAudioDevice(name);
        UpdateAudioDeviceStatus(engineReportedDevices: true);
    }

    private void OnRefreshAudioDevicesClick(object sender, RoutedEventArgs e)
    {
        _loading = true;
        try { RefreshAudioDevices(); }
        finally { _loading = false; }
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

    private void OnScreenshotToggled(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        App.Services.Settings.Update(s => s.AllowPlayerScreenshots = ScreenshotSwitch.IsOn);
    }

    private void OnHwDecChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        int idx = HwDecCombo.SelectedIndex;
        if (idx < 0) return;
        App.Services.Settings.Update(s => s.EngineOptions["hwdec"] = HwDecModes[idx].Value);
        DiagnosticsText.Text = BuildDiagnostics();
    }

    private void OnHdrDynamicMetadataChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        int idx = HdrDynamicMetadataCombo.SelectedIndex;
        if (idx < 0) return;
        App.Services.Settings.Update(s => s.HdrDynamicMetadata = HdrDynamicMetadataModes[idx].Value);
        DiagnosticsText.Text = BuildDiagnostics();
    }

    // ---------------------------------------------------------------- updates

    private void OnUpdateSourceChanged(object sender, TextChangedEventArgs e)
    {
        if (_loading) return;
        string value = UpdateSourceBox.Text.Trim();
        App.Services.Settings.Update(s => s.UpdateSource = value.Length == 0 ? null : value);
        _pendingUpdate = null;
        UpdateStatusText.Text = value.Length == 0 ? "尚未配置更新源。" : "更新源已保存。点“检查更新”。";
        UpdateApplyState();
    }

    private void UpdateApplyState()
    {
        ApplyUpdateButton.IsEnabled = !_updateBusy && _pendingUpdate is { Available: true } && App.Services.Update.CanSelfUpdate;
    }

    private async void OnCheckUpdateClick(object sender, RoutedEventArgs e)
    {
        if (_updateBusy) return;
        _updateBusy = true;
        UpdateRing.IsActive = true;
        UpdateApplyState();
        UpdateStatusText.Text = "正在检查更新…";
        try
        {
            var result = await App.Services.Update.CheckAsync();
            _pendingUpdate = result;
            if (result.Available)
            {
                string notes = string.IsNullOrWhiteSpace(result.Manifest.Notes) ? "" : " " + result.Manifest.Notes;
                UpdateStatusText.Text = App.Services.Update.CanSelfUpdate
                    ? $"发现新版本 {result.Manifest.Version}（当前 {UpdateService.RawVersion}）。{notes}"
                    : $"发现新版本 {result.Manifest.Version}，但当前目录缺少 uninstall.ps1，无法一键更新。";
            }
            else
            {
                UpdateStatusText.Text = $"当前已是最新版本 {UpdateService.RawVersion}。";
            }
        }
        catch (Exception ex)
        {
            _pendingUpdate = null;
            UpdateStatusText.Text = "检查失败：" + ex.Message;
        }
        finally
        {
            _updateBusy = false;
            UpdateRing.IsActive = false;
            UpdateApplyState();
        }
    }

    private async void OnApplyUpdateClick(object sender, RoutedEventArgs e)
    {
        if (_updateBusy || _pendingUpdate is not { Available: true } check) return;

        _updateBusy = true;
        UpdateRing.IsActive = true;
        UpdateApplyState();
        bool handedOff = false;
        try
        {
            UpdateStatusText.Text = "正在下载更新包… 0%";
            var progress = new Progress<double>(p =>
                UpdateStatusText.Text = $"正在下载更新包… {(int)Math.Round(p * 100)}%");
            string package = await App.Services.Update.DownloadAsync(check, progress);

            UpdateStatusText.Text = "校验通过，正在退出并替换文件…";
            var agent = App.Services.Update.StartAgent(check.Manifest, package, restart: true);
            if (agent is null) throw new InvalidOperationException("更新代理未能启动。");

            // The agent waits for this process, so a quick exit means it failed to start rather than
            // finished. Do not close the window over a failed agent: leave the old app usable.
            await Task.Delay(600);
            agent.Refresh();
            if (agent.HasExited)
            {
                throw new InvalidOperationException(
                    $"更新代理提前退出（代码 {agent.ExitCode}），详见 {Path.Combine(UpdateService.DataDirectory, "logs")}。");
            }

            handedOff = true;
            UpdateStatusText.Text = "正在应用更新，完成后会自动重新打开 MediaHub。";
            App.MainWindow?.RequestClose();
        }
        catch (Exception ex)
        {
            UpdateStatusText.Text = "更新失败：" + ex.Message;
        }
        finally
        {
            if (!handedOff)
            {
                _updateBusy = false;
                UpdateRing.IsActive = false;
                UpdateApplyState();
            }
        }
    }

    // ---------------------------------------------------------------- system integration

    private void RefreshAssociationStatus()
    {
        bool registered = FileAssociationService.IsRegistered();
        AssocStatusText.Text = registered
            ? "当前状态：已登记（" + FileAssociationService.Extensions.Length + " 种扩展名）。"
            : "当前状态：未登记。可播放格式不会出现在“打开方式”列表中。";
        UnregisterAssocButton.IsEnabled = registered;
        RegisterAssocButton.IsEnabled = !registered;
    }

    private void OnRegisterAssociationClick(object sender, RoutedEventArgs e)
    {
        AssocRing.IsActive = true;
        try
        {
            string exe = Environment.ProcessPath ?? "";
            int count = FileAssociationService.Register(exe);
            AssocStatusText.Text = "已为 " + count + " 种扩展名登记。现在可在“打开方式”中选择 MediaHub。";
        }
        catch (Exception ex)
        {
            // Surfaced rather than swallowed: a silent failure here looks like the feature is broken.
            AssocStatusText.Text = "登记失败：" + ex.Message;
        }
        finally
        {
            AssocRing.IsActive = false;
            RefreshAssociationStatus();
        }
    }

    private void OnUnregisterAssociationClick(object sender, RoutedEventArgs e)
    {
        AssocRing.IsActive = true;
        try
        {
            int count = FileAssociationService.Unregister();
            AssocStatusText.Text = "已移除 " + count + " 种扩展名的登记，并清理了本应用创建的注册表项。";
        }
        catch (Exception ex)
        {
            AssocStatusText.Text = "移除失败：" + ex.Message;
        }
        finally
        {
            AssocRing.IsActive = false;
            RefreshAssociationStatus();
        }
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

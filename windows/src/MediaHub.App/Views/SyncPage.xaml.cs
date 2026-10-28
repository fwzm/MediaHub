using MediaHub.App.Services;
using MediaHub.Sync;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;

namespace MediaHub.App.Views;

/// <summary>
/// One row of the local-source list on <see cref="SyncPage"/>.
/// </summary>
/// <remarks>
/// Labels are computed here so every row announces which source it verifies instead of a column of
/// identical 「验证」 buttons. The normalised address comes from <see cref="LocalSourceMapper"/>, which
/// drops user info, so a pasted credential can never be echoed back into the UI.
/// </remarks>
public sealed record SyncSourceRow(
    string ServerId,
    string DisplayName,
    string DetailLabel,
    string IdentityLabel,
    string VerificationLabel,
    string VerifyLabel);

/// <summary>
/// Cross-device source sync: which sources this device has, what a peer envelope would do, and whether
/// each source really answers.
/// </summary>
/// <remarks>
/// <para>
/// Three capabilities live here and they are deliberately separate: exporting writes one file, previewing
/// only computes, and applying requires the user to pick a file, look at every entry and press 应用. No
/// code path writes a source from an envelope without that click, and a source is written without its
/// credentials, so the page can never claim that anything is "signed in".
/// </para>
/// </remarks>
public sealed partial class SyncPage : Page
{
    private readonly Dictionary<string, SourceVerification> _verifications = new(StringComparer.Ordinal);
    private readonly DispatcherTimer _pairTimer = new();
    private PairingPresenter? _host;
    private PairingPresenter? _guest;
    private bool _busy;
    private bool _pairBusy;

    // Interaction state (W6-19). Every sentence and every pending decision lives in SyncPagePresenter in the Sync
    // layer, so this page keeps only controls and the picked path; nothing here is persisted, and the passphrase is
    // never stored at all.
    private SyncPagePresenter? _presenter;
    private string? _applyPath;

    // Transport state (W6-33). SyncTransferPresenter owns the listener, the pending receive and the in-memory secret;
    // this page only renders what it returns, so a secret never lands in a field that could outlive the click.
    private SyncTransferPresenter? _transfer;
    private bool _transferReceiving;
    private bool _transferSending;

    /// <summary>
    /// The page's presenter: one instance per page so the pending decisions cannot outlive the page, sharing the
    /// service's paired-device registry so a fingerprint is pinned exactly once.
    /// </summary>
    private SyncPagePresenter Presenter => _presenter ??= new SyncPagePresenter(
        App.Services.Sync.DeviceId,
        App.Services.Sync.PairedDevices,
        App.Services.Sync.Baselines);

    public SyncPage()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;

        // The hosting side advances only when someone polls it; a timer keeps the countdown honest and notices the
        // peer arriving while the page is idle.
        _pairTimer.Interval = TimeSpan.FromMilliseconds(500);
        _pairTimer.Tick += OnPairTick;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        App.Services.Servers.Changed += OnServersChanged;
        ReloadSources();
        ReloadPreview(null);

        ReloadPairedDevices();
        RenderTransferState();

        if (App.Services.Sync.StoreError is { Length: > 0 } storeError)
        {
            ShowStatus(InfoBarSeverity.Error, "同步源清单不可用", storeError);
        }
        else if (App.Services.Sync.PairedDevicesError is { Length: > 0 } pairedError)
        {
            // A damaged paired-device file degrades to an empty list; saying so beats showing "nothing paired".
            ShowStatus(InfoBarSeverity.Warning, "已配对设备清单不可用", pairedError);
        }
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        App.Services.Servers.Changed -= OnServersChanged;

        // Leaving the page must never leave a pairing listener open: the port is a door, and the invitation is a
        // one-shot credential.
        StopPairTimer();
        _host?.Dispose();
        _host = null;
        _guest?.Dispose();
        _guest = null;

        // A preview is a list of pending writes; it must not survive the page that showed it.
        Presenter.Apply.Clear();
        _applyPath = null;

        // W6-33: leaving the page closes the transport listener and zeroes the secret with it. Dispose is safe to
        // call synchronously because SourceSyncTransferListener.DisposeAsync completes inline.
        _transfer?.Dispose();
        _transfer = null;
        _transferReceiving = false;
        _transferSending = false;
        TransferReceiveSecretBox.Password = string.Empty;
        TransferSendSecretBox.Password = string.Empty;
    }

    private void OnServersChanged(object? sender, EventArgs e)
    {
        // The event arrives on a provider thread; list updates must run on the UI thread.
        DispatcherQueue.TryEnqueue(ReloadSources);
    }

    /// <summary>Rebuilds the local-source list from the live registry.</summary>
    private void ReloadSources()
    {
        LocalSourceMappingResult mapped = App.Services.Sync.DescribeLocalSources();

        var records = new Dictionary<string, LocalSourceRecord>(StringComparer.Ordinal);
        foreach (LocalSourceRecord record in mapped.Records)
        {
            records.TryAdd(record.ServerId, record);
        }

        var issues = new Dictionary<string, LocalSourceMappingIssue>(StringComparer.Ordinal);
        foreach (LocalSourceMappingIssue issue in mapped.Issues)
        {
            issues.TryAdd(issue.ServerId, issue);
        }

        var rows = new List<SyncSourceRow>();
        foreach (ServerEntry entry in App.Services.Servers.Servers)
        {
            rows.Add(Describe(entry, records, issues));
        }

        bool empty = rows.Count == 0;
        SourceList.ItemsSource = rows;
        SourceList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        EmptyPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
    }

    private SyncSourceRow Describe(
        ServerEntry entry,
        IReadOnlyDictionary<string, LocalSourceRecord> records,
        IReadOnlyDictionary<string, LocalSourceMappingIssue> issues)
    {
        string displayName = string.IsNullOrWhiteSpace(entry.DisplayName) ? entry.ServerId : entry.DisplayName;

        string detail;
        if (records.TryGetValue(entry.ServerId, out LocalSourceRecord? record))
        {
            detail = entry.TypeName + " · " + (record.Url ?? "（没有地址）");
        }
        else if (issues.TryGetValue(entry.ServerId, out LocalSourceMappingIssue? issue))
        {
            detail = entry.TypeName + " · " + DescribeIssue(issue.Reason);
        }
        else
        {
            detail = entry.TypeName;
        }

        string identity = string.IsNullOrWhiteSpace(entry.SyncSourceId)
            ? "标识：未分配（该来源暂不参与跨设备同步）"
            : "标识：" + entry.SyncSourceId;

        _verifications.TryGetValue(entry.ServerId, out SourceVerification verification);
        string verificationLabel = verification.Detail is { Length: > 0 } verificationDetail
            ? verification.Label + " · " + verificationDetail
            : "未验证";

        return new SyncSourceRow(
            entry.ServerId,
            displayName,
            detail,
            identity,
            verificationLabel,
            "验证 " + displayName);
    }

    /// <summary>Why a local source has no cross-device identity, in the user's words.</summary>
    private static string DescribeIssue(string reason) => reason switch
    {
        LocalSourceMappingReasons.LocalDevicePath => "本机路径，不参与跨设备同步",
        LocalSourceMappingReasons.NoUsableEndpoint => "没有可用地址，无法跨设备识别（地址可能不完整或含有账号信息）",
        LocalSourceMappingReasons.NoWireType => "来源类型无法跨设备表达",
        LocalSourceMappingReasons.NoServerId => "缺少来源标识",
        _ => reason,
    };

    /// <summary>Shows a plan, or restores the "nothing previewed yet" state.</summary>
    private void ReloadPreview(SourceSyncPlan? plan)
    {
        if (plan is null)
        {
            PreviewList.ItemsSource = null;
            PreviewSummaryText.Text = "尚未预览。预览只读取对端信封，不会写入任何内容。";
            return;
        }

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        PreviewSummaryText.Text = SyncPreviewText.DescribeSummary(summary);
        PreviewList.ItemsSource = plan.Items.Select(SyncPreviewText.Describe).ToList();
    }

    /// <summary>Writes this device's envelope; success here says "exported", never "synced".</summary>
    private void OnExportClick(object sender, RoutedEventArgs e)
    {
        int assigned;
        try
        {
            assigned = App.Services.Sync.EnsureSourceIds();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            ShowStatus(InfoBarSeverity.Error, "无法准备媒体源标识", ex.Message);
            return;
        }

        try
        {
            EnvelopeExportResult result = App.Services.Sync.ExportEnvelope();
            ReloadSources();

            string message = "共 " + result.SourceCount + " 个媒体源"
                + (result.DeletionCount > 0 ? "，" + result.DeletionCount + " 条删除记录" : string.Empty)
                + "已写入 " + result.Path
                + "。信封不含密码或令牌，导出成功也不代表任何来源已同步。"
                + (assigned > 0 ? "本次为新来源分配了 " + assigned + " 个标识。" : string.Empty);

            ShowStatus(InfoBarSeverity.Success, "已导出同步信封", message);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            ShowStatus(InfoBarSeverity.Error, "无法导出同步信封", ex.Message);
        }
    }

    /// <summary>Reads the peer envelope and shows what a merge would do; writes nothing.</summary>
    private void OnPreviewClick(object sender, RoutedEventArgs e)
    {
        if (!App.Services.Sync.TryReadEnvelope(out SourceSyncEnvelope? envelope, out string? error) || envelope is null)
        {
            ReloadPreview(null);
            ShowStatus(InfoBarSeverity.Warning, "无法预览合并", error ?? "对端信封不可用。");
            return;
        }

        SourceSyncPlan plan = App.Services.Sync.Preview(envelope);
        ReloadPreview(plan);

        SourceSyncPreviewSummary summary = SourceSyncPreviewSummary.FromPlan(plan);
        ShowStatus(
            InfoBarSeverity.Informational,
            "已生成合并预览",
            "预览只计算，没有写入任何媒体源。"
                + (summary.RequiresConfirmation > 0 ? "其中 " + summary.RequiresConfirmation + " 项需用户确认。" : string.Empty));
    }

    private void OnRefreshClick(object sender, RoutedEventArgs e)
    {
        try
        {
            int assigned = App.Services.Sync.EnsureSourceIds();
            ReloadSources();
            ShowStatus(
                InfoBarSeverity.Informational,
                "已刷新媒体源清单",
                assigned > 0 ? "本次为新来源分配了 " + assigned + " 个标识。" : "没有需要变更的标识。");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            ShowStatus(InfoBarSeverity.Error, "无法刷新媒体源清单", ex.Message);
        }
    }

    /// <summary>Verifies one source with a real authentication and list request.</summary>
    private async void OnVerifyClick(object sender, RoutedEventArgs e)
    {
        if (_busy || sender is not Button { Tag: SyncSourceRow row })
        {
            return;
        }

        _busy = true;
        BusyRing.IsActive = true;
        ExportButton.IsEnabled = false;
        PreviewButton.IsEnabled = false;
        RefreshButton.IsEnabled = false;

        try
        {
            SourceVerification verification = await App.Services.Sync.VerifyAsync(row.ServerId);
            _verifications[row.ServerId] = verification;
            ReloadSources();
            ShowStatus(ToSeverity(verification.State), verification.Label, verification.Detail);
        }
        finally
        {
            _busy = false;
            BusyRing.IsActive = false;
            ExportButton.IsEnabled = true;
            PreviewButton.IsEnabled = true;
            RefreshButton.IsEnabled = true;
        }
    }

    private static InfoBarSeverity ToSeverity(SourceVerificationState state) => state switch
    {
        SourceVerificationState.Verified => InfoBarSeverity.Success,
        SourceVerificationState.AuthenticationFailed => InfoBarSeverity.Error,
        _ => InfoBarSeverity.Warning,
    };

    /// <summary>Surfaces a failure instead of swallowing it.</summary>
    private void ShowStatus(InfoBarSeverity severity, string title, string message)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    // ---- pairing (W6-3c) begin -------------------------------------------------
    // Both directions live on this page so one screen can drive a two-device handshake. The presenter decides every
    // state and sentence; the page only renders and never inspects the payload (the challenge must not reach a log
    // line, a message, or an exception that the UI shows).

    // W6-13: both presenters receive the service-owned registry. A second registry would read the same file but pin
    // fingerprints independently, so two code paths could disagree about "the same device".
    private PairingPresenter HostPresenter => _host ??=
        new PairingPresenter(App.Services.Sync.DeviceId, ReadPeerEnvelope, App.Services.Sync.PairedDevices);

    private PairingPresenter GuestPresenter => _guest ??=
        new PairingPresenter(App.Services.Sync.DeviceId, ReadPeerEnvelope, App.Services.Sync.PairedDevices);

    /// <summary>Reads the peer's exported envelope on demand; pairing counts it and never applies it.</summary>
    private static PairingPeerEnvelopeResult ReadPeerEnvelope()
    {
        if (App.Services.Sync.TryReadEnvelope(out SourceSyncEnvelope? envelope, out string? error) && envelope is not null)
        {
            return PairingPeerEnvelopeResult.Found(envelope);
        }

        return PairingPeerEnvelopeResult.Missing(error ?? "没有可读的对端来源清单。");
    }

    private static long NowMilliseconds() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private void OnStartPairClick(object sender, RoutedEventArgs e)
    {
        PairingHostSnapshot snapshot = HostPresenter.StartHost(NowMilliseconds());
        RenderHost(snapshot);

        if (snapshot.State == PairingHostState.Failed)
        {
            ShowStatus(InfoBarSeverity.Error, "无法开始配对", snapshot.NextStep);
            return;
        }

        StopPairTimer();
        _pairTimer.Start();
        PairResultText.Text = "尚未连接。";
        ShowStatus(
            InfoBarSeverity.Informational,
            "已开始配对",
            "配对码 " + PairingPayload.LifetimeSeconds + " 秒内有效，且只能成功连接一次。请把配对码和证书指纹一起发给对方核对。");
    }

    private void OnStopPairClick(object sender, RoutedEventArgs e)
    {
        PairingHostSnapshot snapshot = HostPresenter.StopHost(NowMilliseconds());
        StopPairTimer();
        RenderHost(snapshot);
        ShowStatus(InfoBarSeverity.Informational, "已结束配对", snapshot.NextStep);
    }

    private void OnCopyCodeClick(object sender, RoutedEventArgs e)
    {
        string code = HostPresenter.PairingCodeText;
        if (code.Length == 0)
        {
            ShowStatus(InfoBarSeverity.Warning, "没有可复制的配对码", "请先点「开始配对」。");
            return;
        }

        try
        {
            DataPackage package = new();
            package.SetText(code);
            Clipboard.SetContent(package);
            ShowStatus(InfoBarSeverity.Informational, "配对码已复制", "配对码会随倒计时失效；请与证书指纹一起核对。");
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            ShowStatus(InfoBarSeverity.Warning, "无法复制配对码", "剪贴板不可用：" + ex.Message);
        }
    }

    private void OnPairTick(object? sender, object e)
    {
        PairingHostSnapshot snapshot = HostPresenter.PollHost(NowMilliseconds());
        RenderHost(snapshot);

        if (snapshot.IsListening)
        {
            return;
        }

        StopPairTimer();

        if (snapshot.State == PairingHostState.Paired)
        {
            PairResultText.Text = "对端 " + (HostPresenter.PeerDeviceId ?? "（未知设备）") + " 已连接。来源清单仍需逐项确认，不会自动导入。";
            ShowStatus(
                InfoBarSeverity.Success,
                "配对成功",
                "已与对端建立可信连接（指纹一致：" + (HostPresenter.PeerFingerprintDisplay ?? "未知") + "）。配对不会导入任何来源。");
        }
        else if (snapshot.State == PairingHostState.Expired)
        {
            ShowStatus(InfoBarSeverity.Warning, "配对码已过期", snapshot.NextStep);
        }
        else if (snapshot.State == PairingHostState.Failed)
        {
            ShowStatus(InfoBarSeverity.Error, "配对失败", snapshot.NextStep);
        }

        // A finished attempt may have registered or refused a card; the list must not keep showing the old state.
        ReloadPairedDevices();
    }

    /// <summary>Joins with a pasted code; success reports a count, never an import.</summary>
    private async void OnJoinPairClick(object sender, RoutedEventArgs e)
    {
        if (_pairBusy)
        {
            return;
        }

        _pairBusy = true;
        JoinPairButton.IsEnabled = false;
        BusyRing.IsActive = true;
        PairResultText.Text = "正在连接对端…";

        try
        {
            PairingGuestResult result = await GuestPresenter.AcceptGuestAsync(PairInputBox.Text, NowMilliseconds());
            PairResultText.Text = result.Message
                + (result.Paired && result.PeerDeviceId is { Length: > 0 } device ? "\n对端设备：" + device : string.Empty);

            string title = result.Paired
                ? "已配对（未导入任何来源）"
                : result.StepCode == PairingStepCodes.GuestCodeRefused ? "配对码无法使用" : "连接失败";
            // A failure names its stable code so a user can quote it and a test can pin it; a success has none.
            string message = result.Message
                + (result.Paired || result.Reason is not { Length: > 0 } reason ? string.Empty : "（稳定码：" + reason + "）");

            ShowStatus(result.Paired ? InfoBarSeverity.Success : InfoBarSeverity.Error, title, message);

            // The guest registers the host card; a failure may still have recorded a first-seen fingerprint.
            ReloadPairedDevices();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or ObjectDisposedException)
        {
            // An async void handler must not let anything reach the dispatcher, or the app dies instead of showing a
            // sentence.
            PairResultText.Text = "配对没有完成：" + ex.Message;
            ShowStatus(InfoBarSeverity.Error, "配对没有完成", ex.Message);
        }
        finally
        {
            _pairBusy = false;
            JoinPairButton.IsEnabled = true;
            BusyRing.IsActive = false;
        }
    }

    private void RenderHost(PairingHostSnapshot snapshot)
    {
        bool listening = snapshot.IsListening;

        PairCodeBox.Text = listening ? HostPresenter.PairingCodeText : string.Empty;
        PairFingerprintText.Text = snapshot.FingerprintDisplay;
        StartPairButton.IsEnabled = !listening;
        StopPairButton.IsEnabled = listening;
        CopyCodeButton.IsEnabled = listening && PairCodeBox.Text.Length > 0;

        string countdown = listening ? " · 剩余 " + snapshot.RemainingSeconds + " 秒" : string.Empty;
        PairStatusText.Text = PairingUiText.DescribeHostState(snapshot.State) + countdown + "\n" + snapshot.NextStep;
    }

    private void StopPairTimer()
    {
        if (_pairTimer.IsEnabled)
        {
            _pairTimer.Stop();
        }
    }

    // ---- pairing (W6-3c) end ---------------------------------------------------

    // ---- paired devices (W6-13) begin ------------------------------------------
    // The registry belongs to the service, not to this page: both presenters and every render read the same instance,
    // which is what makes a fingerprint get pinned exactly once. Forgetting is deliberately local-only — it deletes one
    // row from paired-devices.json and touches neither the sources, nor the credentials, nor the peer.

    /// <summary>Rebuilds the section from the registry; every sentence comes from the Sync layer.</summary>
    private void ReloadPairedDevices()
    {
        try
        {
            PairedDeviceListView view = Presenter.BuildPairedDevices(NowMilliseconds());

            PairedList.ItemsSource = view.Rows;
            PairedList.Visibility = view.IsEmpty ? Visibility.Collapsed : Visibility.Visible;
            PairedEmptyPanel.Visibility = view.IsEmpty ? Visibility.Visible : Visibility.Collapsed;
            PairedCountText.Text = view.CountLabel;
            PairedEmptyText.Text = view.EmptyLabel;
            PairedTofuText.Text = view.TofuLabel;
            PairedCapacityText.Text = view.CapacityLabel;
            PairedCapacityText.Visibility = view.CapacityLabel.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            ShowStatus(InfoBarSeverity.Error, "已配对设备清单不可用", ex.Message);
        }
    }

    private async void OnForgetPairedDeviceClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: PairedDeviceRow row })
        {
            return;
        }

        try
        {
            if (Presenter.Pairing.AskForget(row) is not { } prompt)
            {
                return;
            }

            if (!await ConfirmForgetAsync(prompt))
            {
                // Cancelling is itself a receipt: it says nothing was deleted.
                SyncForgetOutcome cancelled = Presenter.Pairing.CancelForget();
                ShowStatus(InfoBarSeverity.Informational, cancelled.Title, cancelled.Message);
                return;
            }

            SyncForgetOutcome outcome = Presenter.Pairing.ConfirmForget();
            ReloadPairedDevices();
            ShowStatus(
                outcome.Forgotten ? InfoBarSeverity.Informational : InfoBarSeverity.Warning,
                outcome.Title,
                outcome.Message);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            ShowStatus(InfoBarSeverity.Error, "无法忘记该设备", ex.Message);
        }
    }

    /// <summary>Confirms forgetting one device; the buttons carry the automation ids because a ContentDialog's own
    /// buttons cannot be given one. Every sentence comes from the presenter's prompt.</summary>
    private async Task<bool> ConfirmForgetAsync(SyncForgetPrompt prompt)
    {
        var panel = new StackPanel { Width = 420, Spacing = 12 };
        AutomationProperties.SetAutomationId(panel, "sync.paired.forget.dialog");

        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = prompt.Text,
        });

        var cancel = new Button { Content = prompt.CancelLabel };
        AutomationProperties.SetAutomationId(cancel, "sync.paired.forget.cancel");
        AutomationProperties.SetName(cancel, prompt.CancelName);

        var confirm = new Button { Content = prompt.ConfirmLabel };
        AutomationProperties.SetAutomationId(confirm, "sync.paired.forget.confirm");
        AutomationProperties.SetName(confirm, prompt.ConfirmName);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        buttons.Children.Add(cancel);
        buttons.Children.Add(confirm);
        panel.Children.Add(buttons);

        // No Primary/Close buttons on purpose: the two buttons above are the dialog's answers and they carry the ids.
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = prompt.Title,
            Content = panel,
        };

        bool forget = false;
        cancel.Click += (_, _) => dialog.Hide();
        confirm.Click += (_, _) =>
        {
            forget = true;
            dialog.Hide();
        };

        await dialog.ShowAsync();
        return forget;
    }

    // ---- paired devices (W6-13) end --------------------------------------------

    // ---- apply (W6-8) begin ----------------------------------------------------

    /// <summary>Picks a peer envelope; the picked path is remembered so 预览 can be repeated.</summary>
    private async void OnPickApplyFileClick(object sender, RoutedEventArgs e)
    {
        if (_busy)
        {
            return;
        }

        string? path;
        try
        {
            // Both shapes of a peer export: the canonical JSON and the passphrase-protected archive.
            // .mharchive is the canonical archive suffix (W6-66); .mhsync stays selectable for older files.
            path = await FilePickerService.PickFileAsync([".json", ".mharchive", ".mhsync"]);
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            ShowStatus(InfoBarSeverity.Error, "无法打开文件选择器", ex.Message);
            return;
        }

        if (path is not { Length: > 0 })
        {
            return;
        }

        _applyPath = path;
        Presenter.Apply.Clear();
        ApplyFileText.Text = path;
        ApplyPreviewButton.IsEnabled = true;
        ApplyConfirmButton.IsEnabled = false;
        await LoadAndPreviewAsync();
    }

    private async void OnPreviewApplyClick(object sender, RoutedEventArgs e) => await LoadAndPreviewAsync();

    /// <summary>
    /// Reads the picked envelope and builds the pending decision list. This is a pure preview: the only thing it
    /// writes is <see cref="ApplyFileText"/>.
    /// </summary>
    private async Task LoadAndPreviewAsync()
    {
        if (_applyPath is not { Length: > 0 })
        {
            ShowStatus(InfoBarSeverity.Warning, "还没有选择信封", "请先点「选择信封文件」。");
            return;
        }

        _busy = true;
        ApplyBusy.IsActive = true;
        ApplyConfirmButton.IsEnabled = false;

        try
        {
            string? passphrase = null;
            PeerFileResult read = App.Services.Sync.ReadPeerFile(_applyPath, null);
            if (read.NeedsPassphrase)
            {
                // The passphrase never leaves this method: it is not stored on the page, not logged, and not put
                // into an InfoBar message.
                passphrase = await PromptPassphraseAsync();
                if (passphrase is null)
                {
                    ShowStatus(InfoBarSeverity.Warning, "已取消", "加密信封需要口令才能预览；没有读取任何内容。");
                    return;
                }

                read = App.Services.Sync.ReadPeerFile(_applyPath, passphrase);
                passphrase = null;
            }

            if (!read.Succeeded || read.Envelope is not { } envelope)
            {
                ClearApplyPreview();
                ApplyFileText.Text = _applyPath;
                ShowStatus(InfoBarSeverity.Error, "无法读取信封", read.Error ?? "信封无法读取。");
                return;
            }

            SourceSyncApplyPreview preview = App.Services.Sync.PreviewApply(envelope);
            Presenter.Apply.Load(preview);

            string kind = read.Kind == PeerFileKind.EncryptedArchive ? "加密存档" : "明文信封";
            ApplyFileText.Text = kind + "：" + _applyPath + " · " + preview.Total + " 条";
            ReloadApplyRows();

            if (preview.BaselineError is { Length: > 0 } baselineError)
            {
                // A damaged baseline file must never be presented as an ordinary first import.
                ShowStatus(
                    InfoBarSeverity.Warning,
                    "本机基线不可用",
                    baselineError + " 本次按全量计划预览；写入成功后会重建基线。");
            }
            else
            {
                ShowStatus(
                    InfoBarSeverity.Informational,
                    "已生成应用预览",
                    SyncPreviewText.DescribeSummary(preview.Summary) + " 预览只计算，没有写入任何内容。");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            ClearApplyPreview();
            ShowStatus(InfoBarSeverity.Error, "无法预览信封", ex.Message);
        }
        finally
        {
            _busy = false;
            ApplyBusy.IsActive = false;
            ApplyConfirmButton.IsEnabled = ComputeGate().CanApply;
        }
    }

    private void OnAllowEntryClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ApplyRow row })
        {
            return;
        }

        Presenter.Apply.Allow(row.SyncSourceId);
        ReloadApplyRows();
    }

    private void OnSkipEntryClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ApplyRow row })
        {
            return;
        }

        Presenter.Apply.Skip(row.SyncSourceId);
        ReloadApplyRows();
    }

    /// <summary>Writes only the allowed entries, and only after the confirmation dialog.</summary>
    private async void OnApplyClick(object sender, RoutedEventArgs e)
    {
        if (_busy || Presenter.Apply.Preview is not { } preview)
        {
            return;
        }

        if (!Presenter.Apply.Gate().CanApply)
        {
            ShowStatus(InfoBarSeverity.Warning, "没有可应用的条目", "所有条目都被跳过或不可应用。");
            return;
        }

        try
        {
            if (await ConfirmApplyAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            _busy = true;
            ApplyBusy.IsActive = true;
            ApplyConfirmButton.IsEnabled = false;

            // Both lists travel: the allowed ids are what may be written, and the skipped ids are the rows the
            // user unticked. The round lets a skip win over an allow, so a double click cannot write a skipped row.
            IReadOnlyList<string> allowed = Presenter.Apply.AllowedIds();
            IReadOnlyList<string> skipped = Presenter.Apply.SkippedIds();
            SourceSyncApplyReport result = await App.Services.Sync.ApplyApprovedAsync(preview, allowed, skipped);

            // The result line carries three things: the three counts, whether the stored baseline moved (that is what
            // makes a second import of the same envelope a no-op), and the fixed sentence that a write is not a sign-in.
            // All of it is composed by the presenter, which also still sees the preview at this point.
            SyncApplyResult applied = Presenter.Apply.DescribeResult(result);

            // The preview described writes that have now happened; keeping it on screen would invite a second apply
            // against a stale plan, so it is retired while the result line and the endpoint sentence stay.
            ClearApplyPreview(keepResult: true);
            ApplyResultText.Text = applied.Message;

            // The counts sentence says how many rows landed; this list lets the user check which ones became which
            // local source. Both come from the presenter, so the page spells no words of its own.
            ApplyWrittenHeader.Text = applied.WrittenHeader;
            ApplyWrittenHeader.Visibility = applied.WrittenHeader.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            ApplyWrittenList.ItemsSource = applied.WrittenRows;
            ApplyWrittenList.Visibility = applied.WrittenRows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

            // Refused entries are part of 保留未应用; the counts sentence folds them in, this list separates them
            // again so the reason the writer gave is readable one row at a time.
            ApplyRefusedHeader.Text = applied.RefusedHeader;
            ApplyRefusedHeader.Visibility = applied.RefusedHeader.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            ApplyRefusedList.ItemsSource = applied.RefusedRows;
            ApplyRefusedList.Visibility = applied.RefusedRows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            ApplyNotImportedCount.Text = applied.EndpointSentence;
            ApplyNotImportedCount.Visibility = Visibility.Visible;
            ReloadSources();

            ShowStatus(
                applied.WroteChanges ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                applied.InfoTitle,
                applied.Message);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            ShowStatus(InfoBarSeverity.Error, "应用远端信封失败", ex.Message);
        }
        finally
        {
            _busy = false;
            ApplyBusy.IsActive = false;
            ApplyConfirmButton.IsEnabled = ComputeGate().CanApply;
        }
    }

    /// <summary>The gate as it stands right now; the presenter owns the decisions.</summary>
    private SourceSyncApprovalResult ComputeGate() => Presenter.Apply.Gate();

    /// <summary>Renders the section from the presenter's view; the page makes no decisions of its own.</summary>
    private void ReloadApplyRows()
    {
        SyncApplyView view = Presenter.Apply.View();
        if (!view.HasPreview)
        {
            ApplyList.ItemsSource = null;
            ApplyBlockedText.Visibility = Visibility.Collapsed;
            ApplyBlockedText.Text = string.Empty;
            ApplyConfirmButton.IsEnabled = false;
            ClearNotImported();
            return;
        }

        var rows = new List<ApplyRow>(view.Rows.Count);
        foreach (SyncApplyRow row in view.Rows)
        {
            rows.Add(new ApplyRow(
                row.SyncSourceId,
                row.Name,
                row.ActionLabel,
                row.DetailLabel,
                row.StateLabel,
                row.RequiresApproval,
                row.Allowable,
                row.AllowLabel,
                row.SkipLabel));
        }

        ApplyList.ItemsSource = rows;
        ApplyConfirmButton.IsEnabled = view.CanApply;
        ApplyBlockedText.Text = view.BlockedText;
        ApplyBlockedText.Visibility = view.BlockedText.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        ShowNotImported(view);
    }

    /// <summary>
    /// Shows the addresses of the peer envelope this client will not store. Windows keeps one address per
    /// source, so the extra ones are counted and named rather than dropped without a trace.
    /// </summary>
    private void ShowNotImported(SyncApplyView view)
    {
        ApplyNotImportedCount.Text = view.NotImportedCountText;
        ApplyNotImportedCount.Visibility = Visibility.Visible;

        ApplyNotImportedList.ItemsSource = view.NotImportedRows;
        ApplyNotImportedList.Visibility = view.NotImportedRows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ClearNotImported()
    {
        ApplyNotImportedCount.Text = string.Empty;
        ApplyNotImportedCount.Visibility = Visibility.Collapsed;
        ApplyNotImportedList.ItemsSource = null;
        ApplyNotImportedList.Visibility = Visibility.Collapsed;
    }

    /// <summary>Drops the pending plan; <paramref name="keepResult"/> keeps the last result line readable.</summary>
    private void ClearApplyPreview(bool keepResult = false)
    {
        Presenter.Apply.Clear();
        ApplyList.ItemsSource = null;
        ApplyBlockedText.Visibility = Visibility.Collapsed;
        ApplyBlockedText.Text = string.Empty;
        ApplyConfirmButton.IsEnabled = false;
        ClearNotImported();
        if (!keepResult)
        {
            ApplyResultText.Text = string.Empty;
            ApplyWrittenHeader.Text = string.Empty;
            ApplyWrittenHeader.Visibility = Visibility.Collapsed;
            ApplyWrittenList.ItemsSource = null;
            ApplyWrittenList.Visibility = Visibility.Collapsed;
            ApplyRefusedHeader.Text = string.Empty;
            ApplyRefusedHeader.Visibility = Visibility.Collapsed;
            ApplyRefusedList.ItemsSource = null;
            ApplyRefusedList.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>Asks before writing; the counts, the risk note and the disclaimer all come from the presenter.</summary>
    private async Task<ContentDialogResult> ConfirmApplyAsync()
    {
        if (Presenter.Apply.Confirm() is not { } confirm)
        {
            return ContentDialogResult.None;
        }

        var panel = new StackPanel { Width = 420, Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = confirm.CountsText,
        });

        if (confirm.HasRisk)
        {
            panel.Children.Add(new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                Text = confirm.RiskText,
            });
        }

        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = confirm.Disclaimer,
        });

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = confirm.Title,
            Content = panel,
            PrimaryButtonText = confirm.ConfirmLabel,
            CloseButtonText = confirm.CancelLabel,
            DefaultButton = ContentDialogButton.Close,
        };

        return await dialog.ShowAsync();
    }

    /// <summary>Collects the archive passphrase; returns null when the user cancels.</summary>
    /// <remarks>
    /// The value is handed straight back to the caller and never stored on a field. A WinRT string cannot be zeroed
    /// in place, so the reference is cleared as soon as it has been read and the dialog is left with an empty box.
    /// </remarks>
    private async Task<string?> PromptPassphraseAsync()
    {
        var box = new PasswordBox { PlaceholderText = "信封口令", Width = 360 };
        AutomationProperties.SetAutomationId(box, "sync.passphrase.input");
        AutomationProperties.SetName(box, "信封口令");

        var panel = new StackPanel { Width = 380, Spacing = 8 };
        AutomationProperties.SetAutomationId(panel, "sync.passphrase.dialog");
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = "这个信封是加密存档。口令只用于这一次解密，不会保存，也不会写入日志。",
        });
        panel.Children.Add(box);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "输入信封口令",
            Content = panel,
            PrimaryButtonText = "解密",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return null;
        }

        string passphrase = box.Password;
        box.Password = string.Empty;
        return passphrase;
    }

    // ---- apply (W6-8) end ------------------------------------------------------

    // ---- transport (W6-33) begin ------------------------------------------------
    // The presenter owns the listener, the pending receive and the in-memory secret; the page only renders what it
    // returns. A received envelope goes through the same apply gate as a file import and stops there, so a peer can
    // never write a source: the 应用 click above is still the only writer on this page.

    /// <summary>The transport presenter: one per page, so a half-finished receive cannot outlive it.</summary>
    private SyncTransferPresenter Transfer => _transfer ??= new SyncTransferPresenter(
        App.Services.Sync.DeviceId,
        () => App.Services.Sync.BuildEnvelope());

    private async void OnTransferStartReceiveClick(object sender, RoutedEventArgs e)
    {
        if (_transferReceiving)
        {
            return;
        }

        _transferReceiving = true;
        TransferStartReceiveButton.IsEnabled = false;
        TransferReceiveBusy.IsActive = true;
        try
        {
            SyncTransferReceiveForm form = new()
            {
                PortText = TransferReceivePortBox.Text ?? string.Empty,
                UseTls = TransferReceiveTlsCheck.IsChecked == true,
                Secret = TransferReceiveSecretBox.Password,
                // W6-43: the presenter decides when to write the byte; the page only says whether the user asked.
                WriteReceipt = TransferWriteReceiptCheck.IsChecked == true,
            };

            SyncTransferOutcome prepared = Transfer.PrepareReceive(form);
            RenderTransferState();
            if (!prepared.Succeeded)
            {
                ShowStatus(InfoBarSeverity.Error, "无法开始接收", prepared.Summary);
                return;
            }

            ShowStatus(InfoBarSeverity.Informational, "正在等待对端连接", prepared.Summary);

            SyncTransferOutcome received = await Transfer.ReceiveOnceAsync();
            RenderTransferState();
            RenderReceiveResult(received, form.WriteReceipt);
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or ObjectDisposedException or ArgumentException)
        {
            ShowStatus(InfoBarSeverity.Error, "接收失败", error.Message);
        }
        finally
        {
            _transferReceiving = false;
            TransferReceiveBusy.IsActive = false;
            TransferStartReceiveButton.IsEnabled = true;
            TransferReceiveSecretBox.Password = string.Empty;
            RenderTransferState();
        }
    }

    private async void OnTransferStopReceiveClick(object sender, RoutedEventArgs e)
    {
        TransferStopReceiveButton.IsEnabled = false;

        // Cancelling is the only way out of a receive that is still waiting; the listener is released either way.
        SyncTransferOutcome stopped = await Transfer.StopReceiveAsync();
        RenderTransferState();
        ShowStatus(InfoBarSeverity.Informational, "已停止接收", stopped.Summary);
    }

    private async void OnTransferSendClick(object sender, RoutedEventArgs e)
    {
        if (_transferSending)
        {
            return;
        }

        _transferSending = true;
        TransferSendButton.IsEnabled = false;
        TransferSendBusy.IsActive = true;
        try
        {
            SyncTransferSendForm form = new()
            {
                Host = TransferSendHostBox.Text ?? string.Empty,
                PortText = TransferSendPortBox.Text ?? string.Empty,
                UseTls = TransferSendTlsCheck.IsChecked == true,
                ExpectedFingerprint = TransferPinBox.Text,
                Secret = TransferSendSecretBox.Password,
                ConfirmDelivery = TransferConfirmDeliveryCheck.IsChecked == true,
                // W6-43: a receipt is the only proof the peer decoded the envelope; a close proves neither way.
                AwaitReceipt = TransferAwaitReceiptCheck.IsChecked == true,
            };

            SyncTransferOutcome sent = await Transfer.SendAsync(form);

            // A send that asked for delivery and did not see the peer close is a failure, not a soft warning: the
            // presenter already words it that way and this page must not soften it.
            string sentence = sent.Summary;
            if (!sent.Succeeded && sent.Detail is { Length: > 0 } detail)
            {
                sentence = sentence + " 详情：" + detail;
            }

            TransferSendResultText.Text = sentence;
            TransferReceiptResultText.Text = FormatSendReceipt(sent, form.AwaitReceipt);
            if (sent.Succeeded)
            {
                ShowStatus(InfoBarSeverity.Success, "已发送", sentence);
            }
            else
            {
                ShowStatus(
                    sent.IsFormIssue ? InfoBarSeverity.Warning : InfoBarSeverity.Error,
                    sent.IsFormIssue ? "发送前检查未通过" : "发送失败",
                    sentence);
            }
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or ObjectDisposedException or ArgumentException)
        {
            ShowStatus(InfoBarSeverity.Error, "发送失败", error.Message);
        }
        finally
        {
            _transferSending = false;
            TransferSendBusy.IsActive = false;
            TransferSendButton.IsEnabled = true;
            TransferSendSecretBox.Password = string.Empty;
        }
    }

    /// <summary>Renders the listener state; the sentence (including the TLS fingerprint) comes from the presenter.</summary>
    private void RenderTransferState()
    {
        SyncTransferReceiveState state = Transfer.ReceiveState;
        TransferStatusText.Text = state.StatusText;
        TransferReceiveInfoText.Text = state.StatusText;
        TransferStopReceiveButton.IsEnabled = state.Listening && !_transferReceiving;

        // W6-9 keeps the exact wording: an idle page must never look like it is listening.
        if (!state.Listening)
        {
            TransferStartReceiveButton.IsEnabled = !_transferReceiving;
        }
    }

    /// <summary>Shows what one receive did, and hands a received envelope to the apply preview only.</summary>
    /// <param name="outcome">The finished receive.</param>
    /// <param name="writesReceipt">Whether the user asked for a receipt; the presenter's state is Idle again by now.</param>
    private void RenderReceiveResult(SyncTransferOutcome outcome, bool writesReceipt)
    {
        string sentence = outcome.Summary;
        if (!outcome.Succeeded && !outcome.Cancelled && outcome.Detail is { Length: > 0 } detail)
        {
            sentence = sentence + " 详情：" + detail;
        }

        // W6-43: the receipt byte is the only "decoded" evidence this card can produce, so the sentence says it out loud.
        sentence = sentence + " " + FormatReceiveReceipt(outcome, writesReceipt);
        TransferStatusText.Text = sentence;

        if (outcome.Cancelled)
        {
            ShowStatus(InfoBarSeverity.Informational, "已取消接收", sentence);
            return;
        }

        if (!outcome.Succeeded)
        {
            ShowStatus(
                outcome.IsFormIssue ? InfoBarSeverity.Warning : InfoBarSeverity.Error,
                outcome.IsFormIssue ? "接收前检查未通过" : "接收失败",
                sentence);
            return;
        }

        if (outcome.Envelope is not null)
        {
            // Preview only: the same gate the file import uses. Nothing is written, and nothing is applied until the
            // user allows the entries and presses 应用.
            SourceSyncApplyPreview preview = App.Services.Sync.PreviewApply(outcome.Envelope);
            Presenter.Apply.Load(preview);
            _applyPath = null;
            ApplyFileText.Text = "传输（本机回环）· " + preview.Total + " 条";
            ReloadApplyRows();
        }

        ShowStatus(InfoBarSeverity.Success, "已接收一个信封（只生成预览）", sentence);
    }

    /// <summary>
    /// W6-43: the receive-side receipt verdict. Only the presenter's <c>ReceiptWritten</c> decides it; an unchecked
    /// switch and a receive that never decoded look different to the user, so they must not share one sentence.
    /// </summary>
    private static string FormatReceiveReceipt(SyncTransferOutcome outcome, bool writesReceipt)
    {
        if (!writesReceipt)
        {
            return "回执：没有要求（未勾选「接收后写回执」）。";
        }

        if (outcome.ReceiptWritten is true)
        {
            return "回执：已回写 0x04——对端可以据此确认信封被读懂（不代表已应用）。";
        }

        if (outcome.ReceiptWritten is false)
        {
            return "回执：要求了但没有写出——这一端不能写回执。";
        }

        return outcome.Succeeded
            ? "回执：没有写出——这次没有解出信封，回执只在解出之后才写。"
            : "回执：没有写出——" + SyncTransferCopy.DescribeReason(outcome.Reason);
    }

    /// <summary>
    /// W6-43: the send-side receipt verdict, rendered into its own line. A receipt that never arrived stays a failure:
    /// <c>receipt-missing</c> is not softened into "sent", because a close proves neither decoding nor refusal.
    /// </summary>
    private static string FormatSendReceipt(SyncTransferOutcome sent, bool awaitReceipt)
    {
        if (!awaitReceipt)
        {
            return "回执：没有要求（未勾选「等待对端回执」）。";
        }

        if (sent.FormIssue != SyncTransferFormIssue.None)
        {
            return "回执：发送前检查没通过，这次没有连到对端。";
        }

        if (sent.Cancelled)
        {
            return "回执：已取消，没有等到对端的回执。";
        }

        if (sent.ReceiptConfirmed is true)
        {
            return "回执：已收到 0x04——对端读懂了这份信封（不代表已应用）。";
        }

        if (SourceSyncReceiptReasons.IsReceiptReason(sent.Reason))
        {
            return "回执：未收到——" + SyncTransferCopy.DescribeReason(sent.Reason);
        }

        return sent.Reason is { Length: > 0 } reason
            ? "回执：没有走到等回执这一步（" + reason + "）。"
            : "回执：未收到——这一端无法承载回执。";
    }

    // ---- transport (W6-33) end --------------------------------------------------
}

/// <summary>
/// One row of the apply list: what the entry would do, and the two buttons that decide it.
/// </summary>
/// <remarks>
/// The row carries the identity so a click is resolved by <c>syncSourceId</c> rather than by position in a list
/// that the user may be re-sorting in their head. <see cref="Allowable"/> is false for reasons the gate can never
/// honour, which is why the button is disabled instead of merely discouraged.
/// </remarks>
public sealed record ApplyRow(
    string SyncSourceId,
    string Name,
    string ActionLabel,
    string DetailLabel,
    string StateLabel,
    bool RequiresApproval,
    bool Allowable,
    string AllowLabel,
    string SkipLabel)
{
    /// <summary>「需用户确认」 only for entries the gate keeps as proposals; empty otherwise.</summary>
    public string ConfirmLabel => RequiresApproval ? "需用户确认" : string.Empty;
}


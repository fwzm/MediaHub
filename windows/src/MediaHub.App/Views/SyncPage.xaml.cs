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

    // Apply state (W6-8). The envelope, the pending file and the per-entry decisions live only while the
    // page is loaded; nothing here is persisted, and the passphrase is never stored at all.
    private readonly Dictionary<string, bool> _applyDecisions = new(StringComparer.OrdinalIgnoreCase);
    private SyncApplyPreview? _applyPreview;
    private string? _applyPath;

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

        if (App.Services.Sync.StoreError is { Length: > 0 } storeError)
        {
            ShowStatus(InfoBarSeverity.Error, "同步源清单不可用", storeError);
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
        _applyPreview = null;
        _applyDecisions.Clear();
        _applyPath = null;
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

    private PairingPresenter HostPresenter => _host ??= new PairingPresenter(App.Services.Sync.DeviceId, ReadPeerEnvelope);

    private PairingPresenter GuestPresenter => _guest ??= new PairingPresenter(App.Services.Sync.DeviceId, ReadPeerEnvelope);

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
            path = await FilePickerService.PickFileAsync([".json", ".mhsync"]);
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
        _applyDecisions.Clear();
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

            SyncApplyPreview preview = App.Services.Sync.PreviewApply(envelope);
            _applyPreview = preview;
            _applyDecisions.Clear();

            string kind = read.Kind == PeerFileKind.EncryptedArchive ? "加密存档" : "明文信封";
            ApplyFileText.Text = kind + "：" + _applyPath + " · " + preview.Total + " 条";
            ReloadApplyRows();

            ShowStatus(
                InfoBarSeverity.Informational,
                "已生成应用预览",
                SyncPreviewText.DescribeSummary(preview.Summary) + " 预览只计算，没有写入任何内容。");
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

        _applyDecisions[row.SyncSourceId] = true;
        ReloadApplyRows();
    }

    private void OnSkipEntryClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ApplyRow row })
        {
            return;
        }

        _applyDecisions[row.SyncSourceId] = false;
        ReloadApplyRows();
    }

    /// <summary>Writes only the allowed entries, and only after the confirmation dialog.</summary>
    private async void OnApplyClick(object sender, RoutedEventArgs e)
    {
        if (_busy || _applyPreview is not { } preview)
        {
            return;
        }

        SourceSyncApprovalResult gate = ComputeGate();
        if (!gate.CanApply)
        {
            ShowStatus(InfoBarSeverity.Warning, "没有可应用的条目", "所有条目都被跳过或不可应用。");
            return;
        }

        try
        {
            if (await ConfirmApplyAsync(gate) != ContentDialogResult.Primary)
            {
                return;
            }

            _busy = true;
            ApplyBusy.IsActive = true;
            ApplyConfirmButton.IsEnabled = false;

            var allowed = new List<string>();
            foreach (SourceSyncApprovalItem item in gate.Applied)
            {
                allowed.Add(item.SyncSourceId);
            }

            SyncApplyResult result = await App.Services.Sync.ApplyApprovedAsync(preview, allowed);
            string message = result.Summary + "。" + SourceSyncService.ApplyDisclaimer;
            if (result.BlockingReasons.Count > 0)
            {
                message += " 不可应用：" + string.Join("、", result.BlockingReasons.Select(DescribeGateReason)) + "。";
            }

            // The preview described writes that have now happened; keeping it on screen would invite a second apply
            // against a stale plan, so it is retired while the result line stays.
            ClearApplyPreview(keepResult: true);
            ApplyResultText.Text = message;
            ReloadSources();

            ShowStatus(
                result.Applied > 0 ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                result.Applied > 0 ? "已写入远端变更" : "没有写入任何变更",
                message);
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

    /// <summary>Runs the gate with the decisions made so far; an undecided approval entry is a skip.</summary>
    private SourceSyncApprovalResult ComputeGate()
    {
        if (_applyPreview is not { } preview)
        {
            return SourceSyncApprovalResult.None;
        }

        var decisions = new List<SourceSyncApprovalDecision>(_applyDecisions.Count);
        foreach (KeyValuePair<string, bool> decision in _applyDecisions)
        {
            decisions.Add(decision.Value
                ? SourceSyncApprovalDecision.Allow(decision.Key)
                : SourceSyncApprovalDecision.Skip(decision.Key));
        }

        return SourceSyncApprovalGate.Decide(preview.Items, decisions);
    }

    private void ReloadApplyRows()
    {
        if (_applyPreview is not { } preview)
        {
            ApplyList.ItemsSource = null;
            ApplyBlockedText.Visibility = Visibility.Collapsed;
            ApplyBlockedText.Text = string.Empty;
            ApplyConfirmButton.IsEnabled = false;
            return;
        }

        var rows = new List<ApplyRow>(preview.Items.Count);
        foreach (SourceSyncApprovalItem item in preview.Items)
        {
            bool allowable = SourceSyncApprovalGate.IsApplicable(item.Action)
                && !SourceSyncApprovalGate.IsNeverAllowable(item.Reason);
            bool allowed = _applyDecisions.TryGetValue(item.SyncSourceId, out bool decision)
                ? decision
                : allowable && !item.RequiresApproval;
            string name = FirstNonBlank(item.DisplayName, item.SyncSourceId, item.ServerId, "（未命名来源）");

            rows.Add(new ApplyRow(
                item.SyncSourceId,
                name,
                SyncPreviewText.DescribeAction(item.Action),
                DescribeApplyDetail(item),
                allowed ? "· 已允许" : "· 已跳过",
                item.RequiresApproval,
                allowable,
                allowable ? "允许 " + name : "不可允许 " + name,
                "跳过 " + name));
        }

        ApplyList.ItemsSource = rows;

        SourceSyncApprovalResult gate = ComputeGate();
        ApplyConfirmButton.IsEnabled = gate.CanApply;
        ApplyBlockedText.Visibility = gate.IsBlocked ? Visibility.Visible : Visibility.Collapsed;
        ApplyBlockedText.Text = gate.IsBlocked
            ? "不会被应用的条目：" + string.Join("、", gate.BlockingReasons.Select(DescribeGateReason))
            : string.Empty;
    }

    /// <summary>Drops the pending plan; <paramref name="keepResult"/> keeps the last result line readable.</summary>
    private void ClearApplyPreview(bool keepResult = false)
    {
        _applyPreview = null;
        _applyDecisions.Clear();
        ApplyList.ItemsSource = null;
        ApplyBlockedText.Visibility = Visibility.Collapsed;
        ApplyBlockedText.Text = string.Empty;
        ApplyConfirmButton.IsEnabled = false;
        if (!keepResult)
        {
            ApplyResultText.Text = string.Empty;
        }
    }

    private async Task<ContentDialogResult> ConfirmApplyAsync(SourceSyncApprovalResult gate)
    {
        var panel = new StackPanel { Width = 420, Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = "将写入本机 " + gate.AppliedCount + " 条来源，跳过 " + gate.SkippedCount
                + " 条，保留未应用 " + gate.WithheldCount + " 条。",
        });

        if (gate.AcknowledgedRisk)
        {
            panel.Children.Add(new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                Text = "其中包含需要你确认的冲突变更（删除与修改相撞、字段冲突或版本相同但内容不同）；允许它们会覆盖本机当前配置。",
            });
        }

        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = SourceSyncService.ApplyDisclaimer,
        });

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "应用远端变更",
            Content = panel,
            PrimaryButtonText = "应用",
            CloseButtonText = "取消",
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

    private static string DescribeApplyDetail(SourceSyncApprovalItem item)
    {
        var parts = new List<string>(4);
        if (!string.IsNullOrWhiteSpace(item.SourceType))
        {
            parts.Add("类型 " + item.SourceType);
        }

        if (!string.IsNullOrWhiteSpace(item.NormalizedUrl))
        {
            parts.Add(item.NormalizedUrl);
        }

        if (item.LocalRevision is not null || item.RemoteRevision is not null)
        {
            parts.Add("本机版本 " + (item.LocalRevision?.ToString() ?? "无")
                + " · 对端版本 " + (item.RemoteRevision?.ToString() ?? "无"));
        }

        parts.Add(DescribeGateReason(item.Reason));
        return string.Join(" · ", parts);
    }

    /// <summary>Localises a gate reason; the gate itself carries codes only.</summary>
    private static string DescribeGateReason(string? reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            return "对端没有说明原因";
        }

        if (reason.StartsWith(SourceSyncApprovalGate.AmbiguousReasonPrefix, StringComparison.Ordinal))
        {
            return "有多个本机来源都像是它，无法确定";
        }

        return reason switch
        {
            SyncRoundReasons.SecretsRequireApproval => "带凭据，需人工迁移（本次不会写入）",
            SyncRoundReasons.UnknownSourceType => "未知来源类型，Windows 无法应用",
            SyncRoundReasons.NotSupportedOnWindows => "Windows 暂不支持该来源类型",
            SyncRoundReasons.DeleteVsUpdate => "对端删除与本机修改相撞，需人工判断",
            SyncRoundReasons.UpdateVsDelete => "本机删除与对端修改相撞，需人工判断",
            SyncRoundReasons.FieldConflict => "两边把同一个字段改成了不同的值",
            SyncRoundReasons.SameRevisionDifferentContent => "版本相同但内容不同",
            SyncRoundReasons.TombstoneRequiresApproval => "对端删除了该来源，需人工确认",
            SyncRoundReasons.TombstoneUnknown => "对端删除了一个本机没有的来源",
            SyncRoundReasons.ApplyRemoteChange => "对端较新，可写入本机",
            SyncRoundReasons.PushLocalChange => "本机较新，请到对端应用",
            SyncRoundReasons.PushLocalDelete => "本机已删除，请到对端应用",
            SyncRoundReasons.RemoteNewer => "对端较新",
            SyncRoundReasons.LocalNewer => "本机较新",
            SyncRoundReasons.AlreadyApplied => "已经应用过",
            SyncRoundReasons.StaleRemote => "对端版本较旧",
            SyncRoundReasons.StaleLocal => "本机版本较旧",
            SyncRoundReasons.BothDeleted => "两边都已删除",
            _ => SyncPreviewText.DescribeReason(reason),
        };
    }

    private static string FirstNonBlank(params string?[] values)
    {
        foreach (string? value in values)
        {
            if (!string.IsNullOrWhiteSpace(value))
            {
                return value.Trim();
            }
        }

        return string.Empty;
    }

    // ---- apply (W6-8) end ------------------------------------------------------
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


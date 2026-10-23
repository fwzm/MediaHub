using MediaHub.App.Services;
using MediaHub.Sync;
using Microsoft.UI.Xaml;
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
/// The page only exports and previews. Nothing here applies a plan to the live source list: applying
/// needs a per-source approval and is a later step, so a user can look at a preview without any risk of
/// finding their sources renamed or removed afterwards.
/// </remarks>
public sealed partial class SyncPage : Page
{
    private readonly Dictionary<string, SourceVerification> _verifications = new(StringComparer.Ordinal);
    private readonly DispatcherTimer _pairTimer = new();
    private PairingPresenter? _host;
    private PairingPresenter? _guest;
    private bool _busy;
    private bool _pairBusy;

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
}

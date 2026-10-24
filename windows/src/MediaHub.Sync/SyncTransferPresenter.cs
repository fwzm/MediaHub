using System.Globalization;
using System.Text;

namespace MediaHub.Sync;

/// <summary>
/// Every fixed sentence the sync page's transfer section shows, in one place.
/// </summary>
/// <remarks>
/// <para>
/// The sentences live here rather than in XAML or in the page's code-behind so that a test can assert the exact
/// words a user reads, and so that one edit cannot leave two screens disagreeing.
/// </para>
/// <para>
/// Two sentences are load-bearing and must not soften:
/// <see cref="AutoApplyNote"/> says a received envelope only becomes a preview (this section can never write a
/// source record), and <see cref="DescribeReason"/> reports <c>delivery-unconfirmed</c> as a failure: a peer closing
/// proves only that it closed, so claiming a delivery this side did not see would be a lie. W6-39 adds the one piece of
/// evidence that does distinguish the two - the W6-37 receipt byte - and still reports its absence as a failure.
/// </para>
/// </remarks>
public static class SyncTransferCopy
{
    /// <summary>Heading of the receive card.</summary>
    public const string ReceiveHeading = "接收一个信封（本机回环，一次连接）";

    /// <summary>Heading of the send card.</summary>
    public const string SendHeading = "发送本机信封（本机回环）";

    /// <summary>What receiving does, and what it refuses to do next.</summary>
    public const string ReceiveNote =
        "接收只等一次连接：信封到达后只生成导入预览，本机不写入任何内容，也不会自动应用。";

    /// <summary>The rule that keeps this section read-only.</summary>
    public const string AutoApplyNote =
        "收到的信封只进入预览；在你逐条允许并点『应用』之前，本机不会写入任何内容。";

    /// <summary>How long the shared secret lives.</summary>
    public const string SecretNote =
        "口令只留在内存里，本次传输结束或取消后立即清零；它不会进日志、JSON 或任何可见文本。";

    /// <summary>What sending does and does not prove.</summary>
    public const string SendNote =
        "发送把本机信封交给你填写的回环地址；是否接收由对端自己决定，本机不代替它做决定。";

    /// <summary>Shown when nothing is listening.</summary>
    public const string IdleStatus = "没有在接收。点「开始接收」会打开回环监听并等待一次连接。";

    /// <summary>Shown after a stop that actually closed a listener.</summary>
    public const string StoppedStatus = "已停止接收：监听已关闭，端口已释放。";

    /// <summary>Shown when a stop found nothing to stop.</summary>
    public const string NothingListening = "没有正在进行的接收，无需停止。";

    /// <summary>Prefix of the line that names the bound endpoint.</summary>
    public const string WaitPrefix = "正在等待对端连接：";

    /// <summary>Hover text for the port box.</summary>
    public const string PortHint = "0 = 自动分配端口";

    /// <summary>The one sentence that keeps a transfer from looking like a sign-in.</summary>
    public const string NotALoginNote = "传输只搬运来源记录，不代表已登录；验证仍需在来源页逐个点。";

    /// <summary>Visible text for a cancelled wait.</summary>
    public const string CancelledStatus = "已取消：没有收到信封，也没有写入任何内容。";

    /// <summary>Visible text for a cancelled send.</summary>
    public const string SendCancelledStatus = "已取消发送；对端是否收到这个信封无法确定。";

    /// <summary>What the receive-side receipt switch promises (W6-39).</summary>
    public const string ReceiptReceiveNote =
        "打开回执后，只有信封被解出来才会回写一个 0x04 字节；对端因此能区分『读懂了』和『只是关掉了连接』。解不出信封时一个字节都不回。";

    /// <summary>What the send-side receipt switch promises (W6-39).</summary>
    public const string ReceiptSendNote =
        "打开等回执后，发送端会等对端回写一个 0x04 字节；等不到就按 receipt-missing / receipt-timeout / receipt-invalid 失败，而不是靠对端关闭来猜。";

    /// <summary>Sentence appended to a send that saw the peer's receipt byte.</summary>
    public const string ReceiptConfirmedSuffix = "，并收到回执：对端读懂了这份信封（0x04）。";

    /// <summary>Describes one form-validation problem in words a user can act on.</summary>
    /// <param name="issue">The issue a form check returned.</param>
    /// <returns>A Chinese sentence; the empty issue returns an empty string.</returns>
    public static string DescribeFormIssue(SyncTransferFormIssue issue) => issue switch
    {
        SyncTransferFormIssue.None => "",
        SyncTransferFormIssue.HostEmpty => "请填写对端地址：本机回环传输只接受 127.0.0.1 或 localhost。",
        SyncTransferFormIssue.PortNotNumber => "端口必须是 0 到 65535 之间的整数；留 0 由系统自动分配。",
        SyncTransferFormIssue.PortOutOfRange => "端口必须在 0 到 65535 之间；0 表示自动分配。",
        SyncTransferFormIssue.FingerprintNotSixtyFourHex => "指纹必须是 64 个十六进制字符（SHA-256 公钥指纹）。",
        SyncTransferFormIssue.AlreadyListening => "已经在接收：这次没有重复绑定端口。",
        SyncTransferFormIssue.NotListening => "当前没有在接收，无法等待对端连接。",
        SyncTransferFormIssue.NoLocalEnvelope => "本机信封没能生成，无法发送。",
        _ => "输入不被接受。",
    };

    /// <summary>
    /// Describes one stable reason code in Chinese, quoting the code itself.
    /// </summary>
    /// <param name="reason">One of the twenty-four reason codes the transfer stack can produce.</param>
    /// <returns>A sentence that ends with the code in parentheses; an unknown code is echoed verbatim.</returns>
    /// <remarks>A new code is never invented here: an unrecognised value is shown exactly as it arrived.</remarks>
    public static string DescribeReason(string? reason)
    {
        string label = reason switch
        {
            null or "" => "未说明原因",
            SourceSyncTransferReasons.NotLoopback => "只允许连接本机回环地址（127.0.0.1 / ::1 / localhost），这个地址不是回环地址。",
            SourceSyncTransferReasons.ListenerFailed => "回环监听没能建立：端口被占用，或系统拒绝了这个端点。",
            SourceSyncTransferReasons.ConnectFailed => "连不上对端的回环端口：对端没有在监听，或端口填错了。",
            SourceSyncTransferReasons.FrameInvalid => "帧格式不合法：对端不是本传输端点，或数据被改动过。",
            SourceSyncTransferReasons.FrameTruncated => "对端在帧传完之前就关闭了连接。",
            SourceSyncTransferReasons.PayloadTooLarge => "信封超过 8 MiB 上限，本次拒绝，且没有缓冲任何内容。",
            SourceSyncTransferReasons.ChallengeMismatch => "对端声明的挑战与预期不符：这可能是另一个连接。",
            SourceSyncTransferReasons.EnvelopeInvalid => "帧是完整的，但信封内容不是合法的同步信封（JSON 或契约校验失败）。",
            SourceSyncTransferReasons.TransferTimeout => "在期限内没有等到对端完成这次传输。",
            SourceSyncTransferReasons.IoFailed => "底层连接出错：读写时操作系统报告了异常。",
            SourceSyncTransferAuthReasons.AuthRequired => "开了口令认证，但这一端没有可用的挑战（nonce）。",
            SourceSyncTransferAuthReasons.AuthMissing => "开了口令认证，但对端没有送来 tag 帧。",
            SourceSyncTransferAuthReasons.AuthMismatch => "tag 与本地口令或信封不匹配：两端口令不一致，或信封被改动。",
            SourceSyncTransferAuthReasons.AuthReplayed => "同一个 nonce 之前已经接受过，这次重放被忽略。",
            SourceSyncTransferAuthReasons.AuthSecretUnavailable => "开了口令认证，但本进程没有持有口令。",
            SourceSyncTlsReasons.HandshakeFailed => "TLS 1.3 握手失败：对端不是 TLS 端点，或握手中断。",
            SourceSyncTlsReasons.FingerprintMismatch => "对端证书的公钥指纹与钉扎值不符；一个信封字节都没有读或写。",
            SourceSyncTlsReasons.CertificateUnavailable => "本机无法生成临时证书，或没有给出可用的 64 位十六进制指纹。",
            SourceSyncTlsReasons.NotEnabled => "对端用 TLS 握手开头，但这个端点只接受明文传输。",
            SourceSyncDeliveryReasons.DeliveryUnconfirmed =>
                "全部帧已写出，但对端没有在期限内关闭连接：这不算送达（delivery-unconfirmed）。",
            SourceSyncReceiptReasons.ReceiptMissing =>
                "对端在回写回执之前就关闭了连接：这次没有拿到回执，不能认定对端读懂了信封。",
            SourceSyncReceiptReasons.ReceiptTimeout =>
                "对端一直保持连接，却在期限内没有回写回执字节。",
            SourceSyncReceiptReasons.ReceiptInvalid =>
                "对端回写的字节不是回执帧类型 0x04：这次没有拿到有效回执。",
            SourceSyncReceiptReasons.ReceiptNotEnabled =>
                "这一端无法承载回执：流不可写，或不可读回。",
            _ => "未识别的结果码。",
        };

        return reason is null or "" ? label : label + "（" + reason + "）";
    }

    /// <summary>Receipt shown after one envelope arrived.</summary>
    /// <param name="bytes">Payload bytes, excluding frame headers.</param>
    /// <param name="frames">How many frames carried it.</param>
    public static string DescribeReceived(int bytes, int frames) =>
        "已接收 1 个信封（" + bytes.ToString(CultureInfo.InvariantCulture) + " 字节，"
        + frames.ToString(CultureInfo.InvariantCulture) + " 帧）；只生成了预览，没有写入任何内容。";

    /// <summary>Receipt shown after one envelope left.</summary>
    /// <param name="bytes">Payload bytes, excluding frame headers.</param>
    /// <param name="frames">How many frames carried it.</param>
    /// <param name="confirmed">What the wait for the peer's close saw, or null when none was requested.</param>
    /// <param name="elapsed">How long that wait took.</param>
    /// <param name="receiptConfirmed">
    /// What the wait for the peer's receipt byte saw (W6-39), or null when no receipt was requested. A receipt is the
    /// only evidence that the peer decoded the envelope, so when it arrived it is the sentence a user reads.
    /// </param>
    public static string DescribeSent(
        int bytes,
        int frames,
        bool? confirmed,
        TimeSpan elapsed,
        bool? receiptConfirmed = null)
    {
        string head = "已发送（" + bytes.ToString(CultureInfo.InvariantCulture) + " 字节，"
            + frames.ToString(CultureInfo.InvariantCulture) + " 帧）";
        if (receiptConfirmed is true)
        {
            return head + ReceiptConfirmedSuffix;
        }

        return confirmed switch
        {
            true => head + "，并确认送达：对端在 "
                + Math.Round(elapsed.TotalMilliseconds).ToString(CultureInfo.InvariantCulture) + " ms 内关闭了连接。",
            false => head + "，但没有等到对端关闭：这次不算送达。",
            null => head + "；没有要求送达确认，是否接收由对端决定。",
        };
    }

    /// <summary>One failure sentence, with the stable code kept verbatim.</summary>
    /// <param name="reason">The stable reason code, or null.</param>
    /// <param name="detail">Optional detail from the transport; never a credential.</param>
    public static string DescribeFailure(string? reason, string? detail)
    {
        string text = "传输失败：" + DescribeReason(reason);
        return string.IsNullOrEmpty(detail) ? text : text + " 细节：" + detail;
    }
}

/// <summary>What a form check rejected, so the page can point at the right box.</summary>
public enum SyncTransferFormIssue
{
    /// <summary>The form was accepted.</summary>
    None = 0,

    /// <summary>The host box is empty.</summary>
    HostEmpty,

    /// <summary>The port box is not an integer.</summary>
    PortNotNumber,

    /// <summary>The port is outside 0..65535.</summary>
    PortOutOfRange,

    /// <summary>The pinned fingerprint is not 64 hex characters.</summary>
    FingerprintNotSixtyFourHex,

    /// <summary>A listener is already bound; the second start did not bind again.</summary>
    AlreadyListening,

    /// <summary>No listener is bound, so there is nothing to wait on.</summary>
    NotListening,

    /// <summary>This presenter cannot produce a local envelope to send.</summary>
    NoLocalEnvelope,
}

/// <summary>The receive form as the page reads it off its controls.</summary>
public sealed record SyncTransferReceiveForm
{
    /// <summary>The port to bind, as text; "0" asks the system for a free port.</summary>
    public string PortText { get; init; } = "0";

    /// <summary>True to wrap the connection in TLS 1.3 with a fresh ephemeral certificate.</summary>
    public bool UseTls { get; init; }

    /// <summary>
    /// The shared secret, or null/empty for no authentication. Only the presenter's byte copy survives the call;
    /// this string is never stored, logged, or formatted.
    /// </summary>
    public string? Secret { get; init; }

    /// <summary>
    /// True to answer with the W6-37 receipt byte after the envelope decoded (W6-39). Off by default, so the bytes on
    /// the wire and the shape of the connection stay exactly what they were before this switch existed.
    /// </summary>
    public bool WriteReceipt { get; init; }

    /// <summary>Never prints the secret.</summary>
    public override string ToString() =>
        "SyncTransferReceiveForm { PortText = " + PortText + ", UseTls = " + UseTls
        + ", WriteReceipt = " + WriteReceipt
        + ", Secret = " + (string.IsNullOrEmpty(Secret) ? "<none>" : "<redacted>") + " }";
}

/// <summary>The send form as the page reads it off its controls.</summary>
public sealed record SyncTransferSendForm
{
    /// <summary>The peer's host; only a loopback name is accepted by the transport.</summary>
    public string Host { get; init; } = SourceSyncTransferAddress.DefaultAddress;

    /// <summary>The peer's port, as text.</summary>
    public string PortText { get; init; } = "";

    /// <summary>True to require TLS 1.3 with the pinned certificate.</summary>
    public bool UseTls { get; init; }

    /// <summary>The peer's SHA-256 public-key fingerprint, 64 hex characters; required when TLS is on.</summary>
    public string? ExpectedFingerprint { get; init; }

    /// <summary>The shared secret, or null/empty for no authentication.</summary>
    public string? Secret { get; init; }

    /// <summary>True to wait for the peer's close and fail with delivery-unconfirmed when it does not come.</summary>
    public bool ConfirmDelivery { get; init; }

    /// <summary>
    /// True to wait for the peer's W6-37 receipt byte after the last frame (W6-39); the wait is bounded by
    /// <see cref="SyncTransferPresenter.ReceiptBudget"/>. A receipt outranks the close evidence: when both switches are
    /// on, the presenter reports the receipt, because a close happens just as readily when the peer refuses the bytes.
    /// </summary>
    public bool AwaitReceipt { get; init; }

    /// <summary>Never prints the secret.</summary>
    public override string ToString() =>
        "SyncTransferSendForm { Host = " + Host + ", PortText = " + PortText + ", UseTls = " + UseTls
        + ", ExpectedFingerprint = " + (ExpectedFingerprint ?? "<none>") + ", ConfirmDelivery = " + ConfirmDelivery
        + ", AwaitReceipt = " + AwaitReceipt
        + ", Secret = " + (string.IsNullOrEmpty(Secret) ? "<none>" : "<redacted>") + " }";
}

/// <summary>What the receive side is doing right now.</summary>
public sealed record SyncTransferReceiveState
{
    /// <summary>True while a listener is bound.</summary>
    public bool Listening { get; init; }

    /// <summary>The bound address, or null when idle.</summary>
    public string? Address { get; init; }

    /// <summary>The real bound port (a requested 0 is replaced by the system's choice).</summary>
    public int? Port { get; init; }

    /// <summary>True when the listener presents TLS.</summary>
    public bool UseTls { get; init; }

    /// <summary>The listener's own fingerprint; the peer pins this value when it dials with TLS.</summary>
    public string? CertificateFingerprint { get; init; }

    /// <summary>True when this prepared receive will answer with a receipt byte once the envelope decoded.</summary>
    public bool WritesReceipt { get; init; }

    /// <summary>The sentence to show next to the buttons.</summary>
    public string StatusText { get; init; } = SyncTransferCopy.IdleStatus;

    /// <summary>The state shown when nothing is bound.</summary>
    public static SyncTransferReceiveState Idle { get; } = new() { StatusText = SyncTransferCopy.IdleStatus };
}

/// <summary>The structured result of one preparation, receive, stop, or send.</summary>
public sealed record SyncTransferOutcome
{
    /// <summary>True when the operation did what it said it would.</summary>
    public bool Succeeded { get; init; }

    /// <summary>One of the twenty-four stable reason codes when <see cref="Succeeded"/> is false and a code applies.</summary>
    public string? Reason { get; init; }

    /// <summary>Optional transport detail; never contains a credential or an envelope body.</summary>
    public string? Detail { get; init; }

    /// <summary>The sentence the page shows; always non-empty.</summary>
    public string Summary { get; init; } = string.Empty;

    /// <summary>What a form check rejected, or <see cref="SyncTransferFormIssue.None"/>.</summary>
    public SyncTransferFormIssue FormIssue { get; init; } = SyncTransferFormIssue.None;

    /// <summary>True when the form check, not the transport, produced this result.</summary>
    public bool IsFormIssue => FormIssue != SyncTransferFormIssue.None;

    /// <summary>True when the operator cancelled instead of the transfer failing.</summary>
    public bool Cancelled { get; init; }

    /// <summary>Payload bytes, excluding frame headers.</summary>
    public int Bytes { get; init; }

    /// <summary>How many frames carried the payload.</summary>
    public int Frames { get; init; }

    /// <summary>Null when no confirmation was requested; false never travels with a success.</summary>
    public bool? DeliveryConfirmed { get; init; }

    /// <summary>
    /// Whether the peer's receipt byte arrived (W6-39), or null when this operation did not wait for one. A send that
    /// asked and did not get it fails with one of <see cref="SourceSyncReceiptReasons"/>.
    /// </summary>
    public bool? ReceiptConfirmed { get; init; }

    /// <summary>
    /// Whether this end wrote a receipt byte (receive side), or null when no receipt was requested.
    /// </summary>
    public bool? ReceiptWritten { get; init; }

    /// <summary>The listener's own fingerprint, when a prepared receive has one.</summary>
    public string? CertificateFingerprint { get; init; }

    /// <summary>The port that was actually bound, when this result came from a prepare.</summary>
    public int? BoundPort { get; init; }

    /// <summary>The received envelope, decoded; null on a send or a failure.</summary>
    public SourceSyncEnvelope? Envelope { get; init; }

    /// <summary>
    /// The received envelope as the JSON text the apply section's preview takes; null on a send or a failure.
    /// </summary>
    public string? EnvelopeJson { get; init; }

    /// <summary>Never prints the envelope body, the JSON, or a secret.</summary>
    public override string ToString() =>
        "SyncTransferOutcome { Succeeded = " + Succeeded + ", Reason = " + (Reason ?? "<none>")
        + ", FormIssue = " + FormIssue + ", Cancelled = " + Cancelled + ", Bytes = " + Bytes
        + ", Frames = " + Frames + ", DeliveryConfirmed = "
        + (DeliveryConfirmed is null ? "<none>" : DeliveryConfirmed.ToString())
        + ", ReceiptConfirmed = " + (ReceiptConfirmed is null ? "<none>" : ReceiptConfirmed.ToString())
        + ", ReceiptWritten = " + (ReceiptWritten is null ? "<none>" : ReceiptWritten.ToString()) + " }";
}

/// <summary>
/// Drives one loopback transfer at a time for the sync page, with no UI types and no store.
/// </summary>
/// <remarks>
/// <para>
/// This type is the page's whole transport surface: it binds and releases the listener, owns the byte copy of the
/// shared secret, and turns every <see cref="SourceSyncTransferResult"/> into one <see cref="SyncTransferOutcome"/>
/// with a sentence a user can read. It never throws at the caller and never writes anything - the received envelope
/// leaves as JSON text and goes no further than the apply section's preview.
/// </para>
/// <para>
/// The listener is deliberately single-shot: the transport accepts exactly one peer per listener, so a second press
/// of "start" would otherwise silently move to a different port.
/// </para>
/// </remarks>
public sealed class SyncTransferPresenter : IDisposable
{
    /// <summary>How long a receive waits for one peer unless the caller says otherwise.</summary>
    public static TimeSpan DefaultReceiveBudget { get; } = TimeSpan.FromSeconds(60);

    /// <summary>How long a send may take unless the caller says otherwise.</summary>
    public static TimeSpan DefaultSendBudget { get; } = TimeSpan.FromSeconds(30);

    private readonly object _gate = new();
    private readonly string _deviceId;
    private readonly Func<SourceSyncEnvelope>? _localEnvelope;
    private SourceSyncTransferListener? _listener;
    private CancellationTokenSource? _receiveCts;
    private byte[] _receiveSecret = [];
    private SourceSyncReceiptOptions? _receiveReceipt;
    private bool _receiving;
    private int _session;
    private SyncTransferOutcome? _lastReceive;
    private SyncTransferOutcome? _lastSend;

    /// <summary>Creates a presenter bound to one device identity.</summary>
    /// <param name="deviceId">
    /// This device's sync identity; it also partitions the replay cache, so it must be the same value the rest of the
    /// page uses.
    /// </param>
    /// <param name="localEnvelope">
    /// Produces the envelope to send. The App passes its own export; a presenter without one can only receive.
    /// </param>
    public SyncTransferPresenter(string deviceId, Func<SourceSyncEnvelope>? localEnvelope = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceId);
        _deviceId = deviceId;
        _localEnvelope = localEnvelope;
    }

    /// <summary>This device's sync identity.</summary>
    public string DeviceId => _deviceId;

    /// <summary>What the receive side is doing right now.</summary>
    public SyncTransferReceiveState ReceiveState { get; private set; } = SyncTransferReceiveState.Idle;

    /// <summary>The last receive result, or null before the first one.</summary>
    public SyncTransferOutcome? LastReceive => _lastReceive;

    /// <summary>The last send result, or null before the first one.</summary>
    public SyncTransferOutcome? LastSend => _lastSend;

    /// <summary>True while a listener is bound.</summary>
    public bool IsListening
    {
        get
        {
            lock (_gate)
            {
                return _listener is not null;
            }
        }
    }

    /// <summary>
    /// How long a send waits for the peer's receipt byte when <see cref="SyncTransferSendForm.AwaitReceipt"/> is on
    /// (W6-39); defaults to the transport's own 2000 ms budget.
    /// </summary>
    public TimeSpan ReceiptBudget { get; set; } = SourceSyncReceipt.DefaultBudget;

    /// <summary>True while a secret is held in memory; false after a stop, a finish, or a dispose.</summary>
    public bool HoldsSecretInMemory
    {
        get
        {
            lock (_gate)
            {
                return _receiveSecret.Length > 0;
            }
        }
    }

    /// <summary>True when the text is a usable fingerprint (64 hex characters, either case).</summary>
    /// <param name="text">The text to check.</param>
    public static bool IsFingerprintText(string? text) => PairingFingerprint.TryParse(text, out _);

    /// <summary>Parses the port box the way the CLI parses --port.</summary>
    /// <param name="text">The text to parse.</param>
    /// <param name="port">The parsed port; 0 means "let the system choose".</param>
    /// <param name="issue">What was wrong, or <see cref="SyncTransferFormIssue.None"/>.</param>
    /// <returns>True when the text is a port in 0..65535.</returns>
    public static bool TryParsePort(string? text, out int port, out SyncTransferFormIssue issue)
    {
        port = 0;
        issue = SyncTransferFormIssue.None;
        string trimmed = (text ?? string.Empty).Trim();
        if (trimmed.Length == 0)
        {
            issue = SyncTransferFormIssue.PortNotNumber;
            return false;
        }

        if (!int.TryParse(trimmed, NumberStyles.None, CultureInfo.InvariantCulture, out int parsed))
        {
            issue = SyncTransferFormIssue.PortNotNumber;
            return false;
        }

        if (parsed is < 0 or > 65535)
        {
            issue = SyncTransferFormIssue.PortOutOfRange;
            return false;
        }

        port = parsed;
        return true;
    }

    /// <summary>
    /// Binds the listener now, so the page can show the real port and fingerprint before a peer arrives.
    /// </summary>
    /// <param name="form">The receive form.</param>
    /// <returns>A result whose <see cref="SyncTransferOutcome.BoundPort"/> is the port that is now bound.</returns>
    public SyncTransferOutcome PrepareReceive(SyncTransferReceiveForm form)
    {
        ArgumentNullException.ThrowIfNull(form);
        if (!TryParsePort(form.PortText, out int port, out SyncTransferFormIssue issue))
        {
            return FormFailure(issue);
        }

        lock (_gate)
        {
            if (_listener is not null)
            {
                SyncTransferReceiveState current = ReceiveState;
                return new SyncTransferOutcome
                {
                    FormIssue = SyncTransferFormIssue.AlreadyListening,
                    Summary = SyncTransferCopy.DescribeFormIssue(SyncTransferFormIssue.AlreadyListening)
                        + "当前监听：" + (current.Address ?? SourceSyncTransferAddress.DefaultAddress)
                        + ":" + (current.Port ?? 0).ToString(CultureInfo.InvariantCulture),
                    BoundPort = current.Port,
                    CertificateFingerprint = current.CertificateFingerprint,
                };
            }

            SourceSyncTransferStartResult start = SourceSyncTransferListener.Start(
                SourceSyncTransferAddress.DefaultAddress,
                port,
                form.UseTls);
            if (!start.Started || start.Listener is null)
            {
                return TransportFailure(start.Reason, start.Detail, null);
            }

            _listener = start.Listener;
            _receiveSecret = CopySecret(form.Secret);
            _receiveReceipt = form.WriteReceipt ? SourceSyncReceiptOptions.Sending() : null;
            _receiveCts = new CancellationTokenSource();
            _session++;
            string address = start.Listener.Address;
            int boundPort = start.Listener.Port;
            string? fingerprint = start.Listener.CertificateFingerprint;
            string status = SyncTransferCopy.WaitPrefix + address + ":"
                + boundPort.ToString(CultureInfo.InvariantCulture);
            if (form.UseTls && fingerprint is not null)
            {
                status += "，TLS 1.3，本机指纹 " + fingerprint;
            }

            if (!form.UseTls)
            {
                status += "，明文传输（不加密）";
            }

            if (form.WriteReceipt)
            {
                status += "，解出信封后回写回执（0x04）";
            }

            ReceiveState = new SyncTransferReceiveState
            {
                Listening = true,
                Address = address,
                Port = boundPort,
                UseTls = form.UseTls,
                CertificateFingerprint = fingerprint,
                WritesReceipt = form.WriteReceipt,
                StatusText = status,
            };

            return new SyncTransferOutcome
            {
                Succeeded = true,
                Summary = status,
                BoundPort = boundPort,
                CertificateFingerprint = fingerprint,
            };
        }
    }

    /// <summary>Waits for exactly one peer and turns what arrived into a preview-ready result.</summary>
    /// <param name="budget">How long to wait; null uses <see cref="DefaultReceiveBudget"/>.</param>
    /// <param name="cancellationToken">Cancel to abandon the wait; the listener is released either way.</param>
    /// <returns>A result that never throws for a transport failure.</returns>
    public async Task<SyncTransferOutcome> ReceiveOnceAsync(
        TimeSpan? budget = null,
        CancellationToken cancellationToken = default)
    {
        SourceSyncTransferListener? listener;
        CancellationTokenSource? generator;
        byte[] secret;
        SourceSyncReceiptOptions? receipt;
        int session;
        lock (_gate)
        {
            listener = _listener;
            generator = _receiveCts;
            secret = _receiveSecret;
            receipt = _receiveReceipt;
            session = _session;
            if (listener is null || generator is null)
            {
                return FormFailure(SyncTransferFormIssue.NotListening);
            }

            if (_receiving)
            {
                return FormFailure(SyncTransferFormIssue.AlreadyListening);
            }

            _receiving = true;
        }

        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            generator.Token,
            cancellationToken);
        SecretHandle holder = new(secret);
        SyncTransferOutcome outcome;
        try
        {
            SourceSyncTransferOptions options = holder.Secret.Length > 0
                ? SourceSyncTransferOptions.WithSecret(holder.Secret, deviceId: _deviceId)
                : SourceSyncTransferOptions.Off;
            // W6-39: the receipt is gated on the inbox's own decode check, never on "the frames arrived". A payload
            // this build cannot read must not be acknowledged, which is exactly the defect the receipt exists to close.
            SourceSyncTransferResult received = await listener.ReceiveOnceAsync(
                null,
                budget ?? DefaultReceiveBudget,
                linked.Token,
                options,
                receipt,
                receipt is null ? null : Decodes);

            if (generator.IsCancellationRequested || cancellationToken.IsCancellationRequested)
            {
                outcome = Cancelled(SyncTransferCopy.CancelledStatus);
            }
            else if (!received.Succeeded)
            {
                outcome = TransportFailure(
                    received.Reason,
                    received.Detail,
                    SyncTransferCopy.DescribeFailure(received.Reason, received.Detail));
            }
            else
            {
                outcome = Decode(received);
            }
        }
        catch (OperationCanceledException)
        {
            outcome = Cancelled(SyncTransferCopy.CancelledStatus);
        }
        finally
        {
            holder.Dispose();
            EndReceive(session);

            // This call owns the generator: a stop only cancels it so the wait can unwind, and a prepared
            // generator that was never awaited is disposed by whoever released it.
            generator.Dispose();
        }

        _lastReceive = outcome;
        return outcome;
    }

    /// <summary>Binds and waits in one call; the convenience wrapper the tests and the page's button use.</summary>
    /// <param name="form">The receive form.</param>
    /// <param name="budget">How long to wait; null uses <see cref="DefaultReceiveBudget"/>.</param>
    /// <param name="cancellationToken">Cancel to abandon the wait.</param>
    public async Task<SyncTransferOutcome> StartReceiveAsync(
        SyncTransferReceiveForm form,
        TimeSpan? budget = null,
        CancellationToken cancellationToken = default)
    {
        SyncTransferOutcome prepared = PrepareReceive(form);
        if (!prepared.Succeeded)
        {
            _lastReceive = prepared;
            return prepared;
        }

        return await ReceiveOnceAsync(budget, cancellationToken);
    }

    /// <summary>Closes the listener and clears the secret; safe to call when nothing is listening.</summary>
    public async Task<SyncTransferOutcome> StopReceiveAsync()
    {
        CancellationTokenSource? generator;
        SourceSyncTransferListener? listener;
        bool receiving;
        lock (_gate)
        {
            generator = _receiveCts;
            listener = _listener;
            receiving = _receiving;
            _receiveCts = null;
            _listener = null;
            _receiveReceipt = null;
            _session++;
            ZeroSecretLocked();
            ReceiveState = SyncTransferReceiveState.Idle;
        }

        if (generator is not null)
        {
            try
            {
                generator.Cancel();
            }
            catch (ObjectDisposedException)
            {
                // A finished receive already disposed its generator; nothing is waiting on it.
            }

            if (!receiving)
            {
                // A generator that was prepared but never awaited has no owner left to dispose it.
                generator.Dispose();
            }
        }

        if (listener is not null)
        {
            try
            {
                await listener.DisposeAsync();
            }
            catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidOperationException)
            {
                // Disposing a listener whose accept already failed is not an error worth showing.
            }
        }

        SyncTransferOutcome outcome = new()
        {
            Succeeded = true,
            Cancelled = listener is not null,
            Summary = listener is null ? SyncTransferCopy.NothingListening : SyncTransferCopy.StoppedStatus,
        };
        _lastReceive = outcome;
        return outcome;
    }

    /// <summary>Sends this presenter's local envelope to one loopback peer.</summary>
    /// <param name="form">The send form.</param>
    /// <param name="budget">How long the send may take; null uses <see cref="DefaultSendBudget"/>.</param>
    /// <param name="cancellationToken">Cancel to abandon the send.</param>
    /// <returns>A result that never throws for a transport failure.</returns>
    public async Task<SyncTransferOutcome> SendAsync(
        SyncTransferSendForm form,
        TimeSpan? budget = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(form);
        if (!TryParsePort(form.PortText, out int port, out SyncTransferFormIssue issue))
        {
            return RememberSend(FormFailure(issue));
        }

        string host = (form.Host ?? string.Empty).Trim();
        if (host.Length == 0)
        {
            return RememberSend(FormFailure(SyncTransferFormIssue.HostEmpty));
        }

        PairingFingerprint pin = default;
        if (form.UseTls && !PairingFingerprint.TryParse(form.ExpectedFingerprint, out pin))
        {
            return RememberSend(FormFailure(SyncTransferFormIssue.FingerprintNotSixtyFourHex));
        }

        SourceSyncEnvelope? envelope;
        try
        {
            envelope = _localEnvelope?.Invoke();
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or ArgumentException
            or NotSupportedException)
        {
            return RememberSend(FormFailure(SyncTransferFormIssue.NoLocalEnvelope) with { Detail = error.Message });
        }

        if (envelope is null)
        {
            return RememberSend(FormFailure(SyncTransferFormIssue.NoLocalEnvelope));
        }

        byte[] payload = Encoding.UTF8.GetBytes(SourceSyncCodec.Serialize(envelope));
        byte[] secret = CopySecret(form.Secret);
        SyncTransferOutcome outcome;
        try
        {
            SourceSyncTransferOptions options = secret.Length > 0
                ? SourceSyncTransferOptions.WithSecret(secret, deviceId: _deviceId)
                : SourceSyncTransferOptions.Off;

            // The challenge is the nonce the tag binds to, so an authenticated send must invent one per attempt;
            // reusing a nonce is exactly what the receiver's replay cache refuses.
            string? challenge = secret.Length > 0 ? SourceSyncTransferAuthenticator.CreateNonce() : null;
            SourceSyncTlsOptions tls = form.UseTls
                ? SourceSyncTlsOptions.Pinned(pin.Hex)
                : SourceSyncTlsOptions.Off;
            SourceSyncDeliveryOptions delivery = form.ConfirmDelivery
                ? SourceSyncDeliveryOptions.Confirming()
                : SourceSyncDeliveryOptions.Off;

            // W6-39: a receipt is a byte the receiver chose to send after decoding this envelope, so it outranks the
            // close evidence. The transport drops the close wait by itself when a receipt is awaited, so the two
            // switches never race for the same read.
            SourceSyncReceiptOptions receipt = form.AwaitReceipt
                ? SourceSyncReceiptOptions.Awaiting(ReceiptBudget)
                : SourceSyncReceiptOptions.Off;

            SourceSyncTransferResult sent = await SourceSyncTransferClient.SendAsync(
                host,
                port,
                payload,
                challenge,
                budget ?? DefaultSendBudget,
                cancellationToken,
                options,
                tls,
                delivery,
                receipt);

            if (cancellationToken.IsCancellationRequested)
            {
                outcome = Cancelled(SyncTransferCopy.SendCancelledStatus);
            }
            else if (!sent.Succeeded)
            {
                outcome = TransportFailure(
                    sent.Reason,
                    sent.Detail,
                    SyncTransferCopy.DescribeFailure(sent.Reason, sent.Detail));
            }
            else
            {
                outcome = new SyncTransferOutcome
                {
                    Succeeded = true,
                    Bytes = sent.Bytes,
                    Frames = sent.FrameCount,
                    DeliveryConfirmed = sent.DeliveryConfirmed,
                    ReceiptConfirmed = sent.ReceiptConfirmed,
                    CertificateFingerprint = form.UseTls ? pin.Hex : null,
                    Summary = SyncTransferCopy.DescribeSent(
                        sent.Bytes,
                        sent.FrameCount,
                        sent.DeliveryConfirmed,
                        sent.DeliveryElapsed,
                        sent.ReceiptConfirmed),
                };
            }
        }
        catch (OperationCanceledException)
        {
            outcome = Cancelled(SyncTransferCopy.SendCancelledStatus);
        }
        finally
        {
            Array.Clear(secret);
        }

        _lastSend = outcome;
        return outcome;
    }

    /// <summary>Releases the listener and clears the secret; safe to call twice.</summary>
    public void Dispose()
    {
        CancellationTokenSource? generator;
        SourceSyncTransferListener? listener;
        bool receiving;
        lock (_gate)
        {
            generator = _receiveCts;
            listener = _listener;
            receiving = _receiving;
            _receiveCts = null;
            _listener = null;
            _receiveReceipt = null;
            _session++;
            ZeroSecretLocked();
            ReceiveState = SyncTransferReceiveState.Idle;
        }

        if (generator is not null)
        {
            try
            {
                generator.Cancel();
            }
            catch (ObjectDisposedException)
            {
                // A finished receive already disposed it.
            }

            if (!receiving)
            {
                generator.Dispose();
            }
        }

        if (listener is not null)
        {
            try
            {
                listener.DisposeAsync().AsTask().GetAwaiter().GetResult();
            }
            catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidOperationException)
            {
                // The socket is already gone; disposal has nothing left to do.
            }
        }
    }

    private SyncTransferOutcome Decode(SourceSyncTransferResult received)
    {
        string json = Encoding.UTF8.GetString(received.Envelope);
        if (!SourceSyncCodec.TryDeserialize(json, out SourceSyncEnvelope? envelope, out string? error)
            || envelope is null)
        {
            string reason = SourceSyncTransferReasons.EnvelopeInvalid;
            return TransportFailure(reason, error, SyncTransferCopy.DescribeFailure(reason, error));
        }

        return new SyncTransferOutcome
        {
            Succeeded = true,
            Bytes = received.Bytes,
            Frames = received.FrameCount,
            ReceiptWritten = received.ReceiptWritten,
            Envelope = envelope,
            EnvelopeJson = json,
            Summary = SyncTransferCopy.DescribeReceived(received.Bytes, received.FrameCount),
        };
    }

    /// <summary>
    /// The decode predicate the receipt is gated on (W6-39): the listener must never acknowledge a payload this build
    /// cannot read, because the whole point of the byte is to tell "understood" apart from "arrived".
    /// </summary>
    private static bool Decodes(byte[] payload) => SourceSyncInbox.TryDecode(payload, out _, out _);

    private void EndReceive(int session)
    {
        SourceSyncTransferListener? listener;
        CancellationTokenSource? generator;
        lock (_gate)
        {
            _receiving = false;
            if (_session != session)
            {
                // A stop (or a newer prepare) already released this session's listener and secret.
                return;
            }

            listener = _listener;
            generator = _receiveCts;
            _listener = null;
            _receiveCts = null;
            _receiveReceipt = null;
            _session++;
            ZeroSecretLocked();
            ReceiveState = SyncTransferReceiveState.Idle;
        }

        generator?.Dispose();
        if (listener is not null)
        {
            try
            {
                listener.DisposeAsync().AsTask().GetAwaiter().GetResult();
            }
            catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidOperationException)
            {
                // The peer's connection is already closed; the listener has nothing left to release.
            }
        }
    }

    private void ZeroSecretLocked()
    {
        if (_receiveSecret.Length > 0)
        {
            Array.Clear(_receiveSecret);
        }

        _receiveSecret = [];
    }

    private static byte[] CopySecret(string? secret) =>
        string.IsNullOrEmpty(secret) ? [] : Encoding.UTF8.GetBytes(secret);

    private static SyncTransferOutcome FormFailure(SyncTransferFormIssue issue) => new()
    {
        FormIssue = issue,
        Summary = SyncTransferCopy.DescribeFormIssue(issue),
    };

    private static SyncTransferOutcome TransportFailure(string? reason, string? detail, string? summary)
    {
        return new SyncTransferOutcome
        {
            Reason = reason,
            Detail = detail,
            Summary = summary ?? SyncTransferCopy.DescribeFailure(reason, detail),
        };
    }

    private static SyncTransferOutcome Cancelled(string summary) => new()
    {
        Cancelled = true,
        Summary = summary,
    };

    private SyncTransferOutcome RememberSend(SyncTransferOutcome outcome)
    {
        _lastSend = outcome;
        return outcome;
    }

    /// <summary>Holds the secret bytes for one operation and wipes them when the operation ends.</summary>
    private sealed class SecretHandle : IDisposable
    {
        public SecretHandle(byte[] secret)
        {
            Secret = secret.Length == 0 ? [] : (byte[])secret.Clone();
        }

        public byte[] Secret { get; }

        public void Dispose()
        {
            if (Secret.Length > 0)
            {
                Array.Clear(Secret);
            }
        }
    }
}

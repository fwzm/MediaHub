using System.Globalization;

namespace MediaHub.Sync;

/// <summary>
/// Every fixed sentence the transport path writes to the log, in one place.
/// </summary>
/// <remarks>
/// <para>
/// The transfer section changes a state slot on every step: a listener opens, a peer's envelope arrives, a wait is
/// cancelled, a send fails. That slot is overwritten by the next state and dies with the process, so each step also
/// leaves one line here; the sentences live in this file so one edit cannot leave two screens disagreeing (same rule
/// as <see cref="SyncTransferCopy"/>).
/// </para>
/// <para>
/// What never appears in a line: the shared secret, a certificate fingerprint, the host, the endpoint URL, the
/// envelope body and any transport detail text. A line carries the stable reason code and the byte/frame counts
/// only, because those are what a later reader can act on (W6-107).
/// </para>
/// </remarks>
public static class SyncTransferLog
{
    /// <summary>A listener just bound.</summary>
    public const string ListeningPrefix = "传输监听已开启：";

    /// <summary>A listener just closed.</summary>
    public const string StoppedPrefix = "传输监听已关闭：";

    /// <summary>One envelope was received and decoded.</summary>
    public const string ReceivedPrefix = "传输收到信封：";

    /// <summary>One envelope was sent.</summary>
    public const string SentPrefix = "传输已发出信封：";

    /// <summary>The receive side failed.</summary>
    public const string ReceiveFailedPrefix = "传输接收失败：";

    /// <summary>The send side failed.</summary>
    public const string SendFailedPrefix = "传输发送失败：";

    /// <summary>A receive wait was cancelled; nothing arrived and nothing was written.</summary>
    public const string ReceiveCancelledText = "传输等待被取消：没有收到信封。";

    /// <summary>A send was cancelled; whether the peer got anything is unknowable.</summary>
    public const string SendCancelledText = "传输发送被取消：对端是否收到无法确定。";

    /// <summary>What a line says when the transport reported no code at all.</summary>
    public const string UnknownReason = "未说明原因";

    /// <summary>The listener bound line: port and whether the frames are encrypted, never the fingerprint.</summary>
    public static string Listening(int port, bool tls) =>
        ListeningPrefix + Port(port) + (tls ? " · TLS 1.3" : " · 明文");

    /// <summary>The listener closed line.</summary>
    public static string Stopped(int port) => StoppedPrefix + Port(port);

    /// <summary>One envelope arrived: bytes and frames, never the body.</summary>
    public static string Received(int bytes, int frames) => Transfer(ReceivedPrefix, bytes, frames);

    /// <summary>One envelope went out: bytes and frames, never the host.</summary>
    public static string Sent(int bytes, int frames) => Transfer(SentPrefix, bytes, frames);

    /// <summary>The receive side failed: the stable code, never the detail text.</summary>
    public static string ReceiveFailed(string? reason) => ReceiveFailedPrefix + Reason(reason);

    /// <summary>The send side failed: the stable code, never the detail text.</summary>
    public static string SendFailed(string? reason) => SendFailedPrefix + Reason(reason);

    private static string Port(int port) => "端口 " + port.ToString(CultureInfo.InvariantCulture);

    private static string Transfer(string prefix, int bytes, int frames) =>
        prefix + bytes.ToString(CultureInfo.InvariantCulture) + " 字节 · "
        + frames.ToString(CultureInfo.InvariantCulture) + " 帧";

    private static string Reason(string? reason) =>
        string.IsNullOrWhiteSpace(reason) ? UnknownReason : reason.Trim();
}

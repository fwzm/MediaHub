using System.Collections.Generic;

namespace MediaHub.Sync;

/// <summary>
/// Stable, machine-readable outcomes of moving one sync envelope between two MediaHub instances.
/// </summary>
/// <remarks>
/// <para>
/// A transfer has exactly one failure code, and every code below is reachable: the framing layer produces
/// frame-invalid / frame-truncated / payload-too-large / challenge-mismatch / io-failed, the socket wrapper adds
/// not-loopback / listener-failed / connect-failed / transfer-timeout, and the importer adds envelope-invalid.
/// A caller (the sync page, the CLI, a test) may branch on these strings, so a value change is a breaking change.
/// </para>
/// <para>
/// There is deliberately no "warning" and no "partial" code: half an envelope is never imported. Either the whole
/// payload arrived and decoded, or the transfer failed and nothing was written.
/// </para>
/// </remarks>
public static class SourceSyncTransferReasons
{
    /// <summary>The caller asked to bind or dial an address that is not a loopback address.</summary>
    /// <remarks>
    /// Reported before any socket call, on purpose: binding a wildcard address is what makes Windows show the
    /// firewall prompt, and a prompt the user did not ask for is a worse outcome than a refused transfer.
    /// </remarks>
    public const string NotLoopback = "not-loopback";

    /// <summary>The loopback listener could not be created or started.</summary>
    public const string ListenerFailed = "listener-failed";

    /// <summary>The loopback connection could not be established; nothing is listening on that port.</summary>
    public const string ConnectFailed = "connect-failed";

    /// <summary>A frame's own structure is wrong: unknown kind, inconsistent total, or an offset that does not continue.</summary>
    public const string FrameInvalid = "frame-invalid";

    /// <summary>The peer stopped mid-frame, or announced a frame length outside the framing range.</summary>
    public const string FrameTruncated = "frame-truncated";

    /// <summary>The envelope, or one chunk's announced total, exceeds the documented ceiling; nothing was sent.</summary>
    public const string PayloadTooLarge = "payload-too-large";

    /// <summary>The peer offered a different pairing challenge than the invitation this device accepted.</summary>
    public const string ChallengeMismatch = "challenge-mismatch";

    /// <summary>The payload arrived whole but is not a valid sync envelope (the contract reason is in the detail).</summary>
    public const string EnvelopeInvalid = "envelope-invalid";

    /// <summary>The peer did not answer within the caller's budget.</summary>
    public const string TransferTimeout = "transfer-timeout";

    /// <summary>The socket reported an I/O error while frames were moving.</summary>
    public const string IoFailed = "io-failed";

    /// <summary>Every documented reason code, in the order this file declares them.</summary>
    public static IReadOnlyList<string> All { get; } = new[]
    {
        NotLoopback,
        ListenerFailed,
        ConnectFailed,
        FrameInvalid,
        FrameTruncated,
        PayloadTooLarge,
        ChallengeMismatch,
        EnvelopeInvalid,
        TransferTimeout,
        IoFailed,
    };
}

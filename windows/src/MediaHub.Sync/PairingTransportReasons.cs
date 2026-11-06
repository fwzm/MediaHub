using System.Collections.Generic;

namespace MediaHub.Sync;

/// <summary>
/// Stable, machine-readable reasons the pairing transport can report (ADR-S003 D5).
/// </summary>
/// <remarks>
/// <para>
/// These strings end up in UI text, support logs and tests, so they are part of the contract: a caller may
/// branch on them, and changing a value is a breaking change. They are deliberately lowercase and
/// hyphen-separated to match the reason codes the W6-3a payload contract already produces
/// (<c>bad-fingerprint</c>, <c>expired</c>, ...), so one log line can mix both families without looking odd.
/// </para>
/// <para>
/// The set is closed on purpose. "Something went wrong" is not a reason; every branch of the transport maps
/// onto exactly one of these codes, which is what makes failures testable. In particular there is no
/// "warning" code: a fingerprint that does not match is a hard failure, never a nudge to keep going
/// (ADR-S001 D6).
/// </para>
/// </remarks>
public static class PairingTransportReasons
{
    /// <summary>The listening socket could not be created, or was closed underneath the accept call.</summary>
    public const string ListenerFailed = "listener-failed";

    /// <summary>TLS could not be established, or was established with a version other than TLS 1.3.</summary>
    /// <remarks>
    /// Downgrade is indistinguishable from breakage here and that is fine: both mean "the peer is not the
    /// TLS 1.3 endpoint we require", and neither is a reason to fall back to something weaker.
    /// </remarks>
    public const string TlsVersion = "tls-version";

    /// <summary>The peer's certificate does not carry the fingerprint the invitation advertised.</summary>
    /// <remarks>
    /// Reported by both ends: the server refuses to send anything when its own certificate and the advertised
    /// fingerprint disagree (a stale QR code), and the client refuses to read anything when the certificate it
    /// sees is not the one it pinned.
    /// </remarks>
    public const string FingerprintMismatch = "fingerprint-mismatch";

    /// <summary>The framed payload was missing, truncated, oversized, or not a valid pairing payload.</summary>
    public const string PayloadInvalid = "payload-invalid";

    /// <summary>The invitation's lifetime ran out while the transport was waiting for or talking to the peer.</summary>
    public const string LifetimeExceeded = "lifetime-exceeded";

    /// <summary>This endpoint already paired once; a pairing endpoint is one-shot.</summary>
    public const string AlreadyPaired = "already-paired";

    /// <summary>The caller gave up waiting, or the peer stopped answering mid-read.</summary>
    public const string PeerTimeout = "peer-timeout";

    /// <summary>Every documented reason code, in the order this file declares them.</summary>
    /// <remarks>
    /// Tests assert against this list so a new code cannot be added without also touching the documentation
    /// and the tests that prove the set is closed.
    /// </remarks>
    public static IReadOnlyList<string> All { get; } = new[]
    {
        ListenerFailed,
        TlsVersion,
        FingerprintMismatch,
        PayloadInvalid,
        LifetimeExceeded,
        AlreadyPaired,
        PeerTimeout,
    };
}

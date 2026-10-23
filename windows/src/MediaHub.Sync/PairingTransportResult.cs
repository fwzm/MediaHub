using System;
using System.Security.Authentication;

namespace MediaHub.Sync;

/// <summary>
/// Outcome of one pairing transport exchange: either the peer's payload arrived, or a stable reason code
/// explains why it did not (<see cref="PairingTransportReasons"/>).
/// </summary>
/// <remarks>
/// <para>
/// The type is a record so callers can pattern match and compare it in tests, but <see cref="ToString"/> is
/// overridden: the default record output would print <see cref="Payload"/>, and a payload contains the
/// one-time challenge, which must never reach a log line or an exception message (ADR-S003 D6). The override
/// prints the device id and the negotiated protocol only.
/// </para>
/// </remarks>
public sealed record PairingTransportResult
{
    private PairingTransportResult(bool paired, string? reason, PairingPayload? payload, SslProtocols? protocol)
    {
        Paired = paired;
        Reason = reason;
        Payload = payload;
        Protocol = protocol;
    }

    /// <summary>True when a payload was received from the peer over a pinned, TLS 1.3 channel.</summary>
    public bool Paired { get; }

    /// <summary>The stable failure reason, or null when <see cref="Paired"/> is true.</summary>
    public string? Reason { get; }

    /// <summary>The peer's payload, or null when the exchange failed.</summary>
    public PairingPayload? Payload { get; }

    /// <summary>The protocol version that was actually negotiated, or null before/without a handshake.</summary>
    public SslProtocols? Protocol { get; }

    /// <summary>A successful exchange that received <paramref name="payload"/> over <paramref name="protocol"/>.</summary>
    public static PairingTransportResult Success(PairingPayload payload, SslProtocols protocol)
    {
        ArgumentNullException.ThrowIfNull(payload);
        return new PairingTransportResult(true, null, payload, protocol);
    }

    /// <summary>A failure carrying one of <see cref="PairingTransportReasons.All"/>.</summary>
    /// <exception cref="ArgumentException"><paramref name="reason"/> is empty; an unreasoned failure is a bug.</exception>
    public static PairingTransportResult Failure(string reason)
    {
        if (string.IsNullOrWhiteSpace(reason))
        {
            throw new ArgumentException("A pairing transport failure must carry a reason code.", nameof(reason));
        }

        return new PairingTransportResult(false, reason, null, null);
    }

    /// <summary>The shorthand for the one-shot rule: this endpoint has already paired.</summary>
    public static PairingTransportResult AlreadyPaired() =>
        new(false, PairingTransportReasons.AlreadyPaired, null, null);

    /// <inheritdoc />
    public override string ToString() => Paired
        ? "pairing-transport: paired (tls=" + Protocol + ", peer=" + (Payload?.DeviceId ?? "?") + ")"
        : "pairing-transport: failed (" + Reason + ")";
}

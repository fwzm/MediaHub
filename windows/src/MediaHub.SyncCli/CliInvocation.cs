namespace MediaHub.SyncCli;

/// <summary>The envelope verbs, the verification-only verb, and the two offline maintenance groups.</summary>
public enum SyncCliCommand
{
    /// <summary>No command was recognised.</summary>
    None = 0,

    /// <summary>Write this device's envelope (plain or encrypted) to a file.</summary>
    Export = 1,

    /// <summary>Read a peer envelope and either preview it (default) or apply it.</summary>
    Import = 2,

    /// <summary>Merge preview only: three buckets with their reason codes.</summary>
    Plan = 3,

    /// <summary>Verification-only: does this device's sealed secret unseal to the expected bytes?</summary>
    SecretCheck = 4,

    /// <summary><c>baseline show</c>: read-only dump of the stored per-peer baselines.</summary>
    BaselineShow = 5,

    /// <summary><c>baseline forget</c>: dry-run by default, <c>--apply</c> drops a peer or one source.</summary>
    BaselineForget = 6,

    /// <summary><c>pair link</c>: build this device's shareable pairing link, offline.</summary>
    PairLink = 7,

    /// <summary><c>pair verify</c>: parse a pairing link and report the peer it names, offline.</summary>
    PairVerify = 8,

    /// <summary><c>pair list</c>: read-only list of the devices pinned on this machine.</summary>
    PairList = 9,

    /// <summary><c>pair forget</c>: dry-run by default, <c>--apply</c> drops one pinned device.</summary>
    PairForget = 10,

    /// <summary><c>transfer serve</c>: receive one envelope over a loopback socket (W6-16/17).</summary>
    TransferServe = 11,

    /// <summary><c>transfer send</c>: export this device's envelope and push it to a loopback listener.</summary>
    TransferSend = 12,

    /// <summary><c>transfer roundtrip</c>: two devices, two directions, in one command.</summary>
    TransferRoundtrip = 13,
}

/// <summary>A validated command line. Produced only by <see cref="CliParser"/>.</summary>
public sealed record CliInvocation
{
    /// <summary>Which verb to run.</summary>
    public SyncCliCommand Command { get; init; }

    /// <summary>Directory that holds <c>sync-sources.json</c> for this device.</summary>
    public string Root { get; init; } = "";

    /// <summary><c>--out</c>: where <c>export</c> writes; null means the default inside <see cref="Root"/>.</summary>
    public string? OutPath { get; init; }

    /// <summary><c>--in</c>: the peer envelope or archive to read.</summary>
    public string? InPath { get; init; }

    /// <summary><c>--baseline</c>: optional baseline file used by <c>import</c> and <c>plan</c>.</summary>
    public string? BaselinePath { get; init; }

    /// <summary><c>--passphrase</c>: encrypt on export, decrypt on import. Never logged.</summary>
    public string? Passphrase { get; init; }

    /// <summary><c>--secrets</c>: JSON map of syncSourceId to base64 plaintext.</summary>
    public string? SecretsJson { get; init; }

    /// <summary><c>--deletions</c>: JSON array of tombstones to advertise.</summary>
    public string? DeletionsJson { get; init; }

    /// <summary><c>--device</c>: override the device id stamped into the envelope.</summary>
    public string? DeviceId { get; init; }

    /// <summary><c>--id</c>: the syncSourceId to verify (<c>secret-check</c> only).</summary>
    public string? SyncSourceId { get; init; }

    /// <summary><c>--source</c>: one syncSourceId to forget (<c>baseline forget</c> only).</summary>
    public string? SourceId { get; init; }

    /// <summary><c>--link</c>: the <c>mediahub://pair?...</c> link to read (<c>pair verify</c> only).</summary>
    public string? Link { get; init; }

    /// <summary><c>--expect</c>: base64 of the plaintext the sealed secret must unseal to.</summary>
    public string? ExpectPlaintextBase64 { get; init; }

    /// <summary><c>--to</c>: the <c>host:port</c> a <c>transfer send</c> dials. Loopback only.</summary>
    public string? Target { get; init; }

    /// <summary><c>--port</c>: the port a <c>transfer serve</c> binds; null or 0 means the system picks one.</summary>
    public int? Port { get; init; }

    /// <summary><c>--device-a</c>: the sender device id of <c>transfer roundtrip</c>'s first round.</summary>
    public string? DeviceA { get; init; }

    /// <summary><c>--device-b</c>: the receiver device id of <c>transfer roundtrip</c>'s first round.</summary>
    public string? DeviceB { get; init; }

    /// <summary>
    /// <c>--once</c>: accepted for clarity. <c>transfer serve</c> always serves exactly one connection and
    /// exits, because a command-line tool that blocks forever cannot be scripted or tested.
    /// </summary>
    public bool Once { get; init; }

    /// <summary>
    /// <c>--authenticate</c> (the three socket verbs only): make both ends prove they hold a shared
    /// secret (W6-24 tag frame). Off by default, so the bytes on the wire stay exactly W6-16's.
    /// </summary>
    public bool Authenticate { get; init; }

    /// <summary>
    /// <c>--secret-env</c>: the <em>name</em> of the environment variable that holds the shared
    /// secret. Only the name is kept here; the value is read at run time and never printed, never
    /// written to JSON and never attached to an error.
    /// </summary>
    public string? SecretEnv { get; init; }

    /// <summary>
    /// <c>--tls</c> (the three socket verbs only): wrap the frames in TLS 1.3 with a self-signed
    /// certificate (W6-26). Off by default, so the bytes on the wire stay exactly W6-16's.
    /// </summary>
    public bool Tls { get; init; }

    /// <summary>
    /// <c>--expect-fingerprint</c> (with <c>--tls</c>): the peer certificate's SHA-256 public-key
    /// fingerprint, sixty-four hex characters. Public data, so it is printed and logged freely — but
    /// a peer that cannot produce it is refused before a single frame is read.
    /// </summary>
    public string? ExpectFingerprint { get; init; }

    /// <summary>
    /// <c>--confirm-delivery</c> (<c>transfer send</c> only): after the last frame, wait for the peer to
    /// close the connection, and treat everything short of that as a failure (W6-30). Off by default,
    /// because a receiver that keeps the connection open would otherwise turn every send into a timeout.
    /// </summary>
    public bool ConfirmDelivery { get; init; }

    /// <summary>
    /// <c>--send-receipt</c> (<c>transfer serve</c> only): once the envelope has decoded, write one receipt
    /// byte (0x04) before the connection closes, so the sender can tell "understood" from "closed" (W6-37).
    /// Off by default, so the bytes on the wire stay exactly W6-16's.
    /// </summary>
    public bool SendReceipt { get; init; }

    /// <summary>
    /// <c>--await-receipt</c> (<c>transfer send</c> only): after the last frame, wait for that receipt byte
    /// and fail with <c>receipt-missing</c>, <c>receipt-timeout</c> or <c>receipt-invalid</c> when it does
    /// not arrive (W6-37). Off by default, so a send never waits and never reads back.
    /// </summary>
    public bool AwaitReceipt { get; init; }

    /// <summary>
    /// <c>--budget-ms</c> (<c>transfer serve</c> only): how long to wait for one peer before giving up
    /// with <c>transfer-timeout</c>, in milliseconds (100..600000). Null means the built-in fifteen
    /// seconds, so an invocation that never mentions the option is byte-for-byte the pre-W6-50 one.
    /// </summary>
    public int? BudgetMilliseconds { get; init; }

    /// <summary><c>--apply</c>: really write. Without it <c>import</c> is a preview.</summary>
    public bool Apply { get; init; }

    /// <summary><c>--json</c>: stdout carries one JSON document and nothing else.</summary>
    public bool Json { get; init; }
}

/// <summary>Outcome of parsing: an invocation, a help/version request, or a usage error.</summary>
public sealed class CliParseResult
{
    /// <summary>The parsed command, or null when parsing failed.</summary>
    public CliInvocation? Invocation { get; init; }

    /// <summary>Stable usage-error code, or null.</summary>
    public string? Error { get; init; }

    /// <summary>The caller asked for usage text.</summary>
    public bool IsHelp { get; init; }

    /// <summary>The caller asked for the version.</summary>
    public bool IsVersion { get; init; }

    /// <summary>True when an invocation is available.</summary>
    public bool Succeeded => Invocation is not null;
}

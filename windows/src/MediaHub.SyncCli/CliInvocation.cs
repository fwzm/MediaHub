namespace MediaHub.SyncCli;

/// <summary>The three verbs plus the verification-only verb.</summary>
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

    /// <summary><c>--expect</c>: base64 of the plaintext the sealed secret must unseal to.</summary>
    public string? ExpectPlaintextBase64 { get; init; }

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

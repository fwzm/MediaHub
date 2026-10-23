using System.Globalization;

namespace MediaHub.Sync;

/// <summary>Stable reason and warning codes produced by <see cref="SourceSyncSession"/>.</summary>
public static class SourceSyncSessionReasons
{
    /// <summary>Credential migration is not part of this layer; asking for secrets is refused, not silently dropped.</summary>
    public const string SecretsNotSupported = "secrets-not-supported";

    /// <summary>Prefix of a warning about a local source that could not be exported (serverId and reason follow).</summary>
    public const string SkippedSourcePrefix = "source-skipped";

    /// <summary>The envelope carried neither sources nor tombstones, so the peer has nothing to do with it.</summary>
    public const string EmptyEnvelope = "empty-envelope";
}

/// <summary>What one export produced: the envelope always, plain JSON or ciphertext depending on the passphrase.</summary>
public sealed record ExportSession
{
    /// <summary>The contract envelope that was serialised or encrypted.</summary>
    public SourceSyncEnvelope Envelope { get; init; } = new();

    /// <summary>Plain envelope JSON when no passphrase was given; empty when the export is encrypted.</summary>
    public string EnvelopeJson { get; init; } = "";

    /// <summary>Archive bytes when a passphrase was given; empty otherwise. It never touches the file system.</summary>
    public byte[] Archive { get; init; } = [];

    /// <summary>Sources that could not be exported, plus empty-envelope, in a stable order.</summary>
    public IReadOnlyList<string> Warnings { get; init; } = [];

    public bool IsEncrypted => Archive.Length > 0;

    public bool IsEmpty => Envelope.Sources.Count == 0 && Envelope.Deletions.Count == 0;

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"ExportSession(device={Envelope.DeviceId}, encrypted={IsEncrypted}, sources={Envelope.Sources.Count}, deletions={Envelope.Deletions.Count}, warnings={Warnings.Count})");
}

/// <summary>One row of an import: what the planner decided for one sync source id.</summary>
public sealed record SourceSyncImportEntry
{
    public string SyncSourceId { get; init; } = "";

    public SourceSyncAction Action { get; init; }

    /// <summary>Planner or merger reason code, reported verbatim so callers can display it.</summary>
    public string Reason { get; init; } = "";

    /// <summary>Local server id when the entry maps onto a known local source.</summary>
    public string? ServerId { get; init; }

    public string? DisplayName { get; init; }

    public bool RequiresUserApproval { get; init; }

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"import-entry(id={SyncSourceId}, action={Action}, reason={Reason}, approval={RequiresUserApproval})");
}

/// <summary>What one import did: three disjoint buckets, and the failure reason when the archive was rejected.</summary>
/// <remarks>
/// A failed import wrote nothing at all; <see cref="Succeeded"/> is false and <see cref="FailureReason"/> carries the
/// stable reader code, which is why a wrong passphrase can be told apart from a successful no-op round.
/// </remarks>
public sealed record ImportResult
{
    public bool Succeeded { get; init; } = true;

    /// <summary>Reader or payload code when <see cref="Succeeded"/> is false.</summary>
    public string? FailureReason { get; init; }

    /// <summary>The inbound envelope after decryption, or null when the archive never decrypted.</summary>
    public SourceSyncEnvelope? Envelope { get; init; }

    public IReadOnlyList<SourceSyncImportEntry> Applied { get; init; } = [];

    public IReadOnlyList<SourceSyncImportEntry> Skipped { get; init; } = [];

    public IReadOnlyList<SourceSyncImportEntry> RequiresApproval { get; init; } = [];

    public int AppliedCount => Applied.Count;

    /// <summary>True when the round changed the store or left a proposal for the user.</summary>
    public bool HasWork => Applied.Count > 0 || RequiresApproval.Count > 0;

    public bool IsEmpty => Applied.Count == 0 && Skipped.Count == 0 && RequiresApproval.Count == 0;

    public IReadOnlyList<SourceSyncImportEntry> Entries => [.. Applied, .. Skipped, .. RequiresApproval];

    public int CountOf(string reason) =>
        Entries.Count(entry => string.Equals(entry.Reason, reason, StringComparison.Ordinal));

    public SourceSyncImportEntry? Find(string? syncSourceId)
    {
        string? key = syncSourceId?.Trim();
        if (string.IsNullOrEmpty(key))
        {
            return null;
        }

        return Entries.FirstOrDefault(entry => string.Equals(entry.SyncSourceId, key, StringComparison.OrdinalIgnoreCase));
    }

    public static ImportResult Failed(string reason) => new() { Succeeded = false, FailureReason = reason };

    public override string ToString() => string.Create(
        CultureInfo.InvariantCulture,
        $"ImportResult(succeeded={Succeeded}, applied={Applied.Count}, skipped={Skipped.Count}, requiresApproval={RequiresApproval.Count}, failure={FailureReason ?? "none"})");
}

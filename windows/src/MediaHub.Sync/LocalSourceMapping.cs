using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>
/// One locally configured source, described in the terms the platform-agnostic sync layer can consume.
/// </summary>
/// <remarks>
/// Plain values on purpose: <c>ServerEntry</c> lives in the App layer and carries provider handles, live
/// status and UI state, none of which may reach the contract. W6-2's adapter fills this in, and the mapping
/// itself is pure so it can be tested without starting the client.
/// </remarks>
public sealed record LocalSourceMappingInput
{
    /// <summary>Local, device-owned identity of the source.</summary>
    public required string ServerId { get; init; }

    /// <summary>Cross-device identity, when the source already has one.</summary>
    public string? SyncSourceId { get; init; }

    /// <summary>Local source kind as the Windows client models it.</summary>
    public ServerType Type { get; init; } = ServerType.Unknown;

    /// <summary>
    /// Wire value kept verbatim for a kind this build cannot map (an imported, not-yet-supported source).
    /// Ignored when <see cref="Type"/> maps to the contract.
    /// </summary>
    public string? SourceTypeRaw { get; init; }

    /// <summary>Address as the client stores it; normalised by <see cref="SyncUrlNormalizer"/> during mapping.</summary>
    public string? Url { get; init; }

    /// <summary>Account the source is logged in as.</summary>
    public string? AccountScope { get; init; }

    /// <summary>Name shown locally.</summary>
    public string? DisplayName { get; init; }

    /// <summary>Revision this device last recorded for the source.</summary>
    public long RecordRevision { get; init; }
}

/// <summary>Why a local source was not turned into a sync record.</summary>
public static class LocalSourceMappingReasons
{
    /// <summary>The source has no id, so nothing could ever reference it.</summary>
    public const string NoServerId = "no-server-id";

    /// <summary>The address does not normalise to one this client could dial (e.g. a pasted credential).</summary>
    public const string NoUsableEndpoint = "no-usable-endpoint";

    /// <summary>A local folder on this machine: its path means nothing on another device.</summary>
    public const string LocalDevicePath = "local-device-path";

    /// <summary>Neither a mapped kind nor a verbatim wire value: nothing to send.</summary>
    public const string NoWireType = "no-wire-type";
}

/// <summary>
/// A source that was skipped while mapping, so the UI can explain why it does not travel.
/// </summary>
/// <remarks>
/// Carries no address: the reason <see cref="LocalSourceMappingReasons.NoUsableEndpoint"/> is most often
/// triggered by a pasted credential (<c>alice:hunter2@host</c>), and echoing it back into a list or a log
/// would defeat the normaliser's whole purpose.
/// </remarks>
public sealed record LocalSourceMappingIssue
{
    /// <summary>Local identity of the skipped source.</summary>
    public required string ServerId { get; init; }

    /// <summary>One of the <see cref="LocalSourceMappingReasons"/> constants.</summary>
    public required string Reason { get; init; }

    /// <summary>Display name, safe to show (never contains a secret).</summary>
    public string? DisplayName { get; init; }
}

/// <summary>Result of mapping this device's sources into the sync layer's shape.</summary>
public sealed record LocalSourceMappingResult
{
    /// <summary>Records that can take part in sync, in input order.</summary>
    public required IReadOnlyList<LocalSourceRecord> Records { get; init; }

    /// <summary>Sources that were deliberately left out, in input order.</summary>
    public required IReadOnlyList<LocalSourceMappingIssue> Issues { get; init; }

    /// <summary>Whether the input contained no sources at all.</summary>
    public bool IsEmpty => Records.Count == 0 && Issues.Count == 0;
}

/// <summary>
/// The App-to-contract adapter: <see cref="LocalSourceMappingInput"/> to <see cref="LocalSourceRecord"/>.
/// </summary>
/// <remarks>
/// <para>
/// Kept in this layer rather than in the client so it is testable without the WinUI process, and kept pure
/// so the same inputs always produce the same records (the caller persists them right after).
/// </para>
/// <para>
/// Two kinds of source are reported instead of mapped. A local folder is a path on this machine, so exporting
/// it would hand the peer an endpoint it cannot dial; an address that does not normalise is either broken or
/// a pasted credential. Both stay in the local list, and the UI says why they are not part of the envelope.
/// </para>
/// </remarks>
public static class LocalSourceMapper
{
    /// <summary>Maps every input, skipping and reporting the ones that cannot travel.</summary>
    public static LocalSourceMappingResult Map(IEnumerable<LocalSourceMappingInput> inputs)
    {
        ArgumentNullException.ThrowIfNull(inputs);

        var records = new List<LocalSourceRecord>();
        var issues = new List<LocalSourceMappingIssue>();

        foreach (LocalSourceMappingInput input in inputs)
        {
            ArgumentNullException.ThrowIfNull(input);

            string serverId = input.ServerId.Trim();
            if (serverId.Length == 0)
            {
                issues.Add(Issue(input, LocalSourceMappingReasons.NoServerId));
                continue;
            }

            // A Windows folder path is device state, not a source: "D:\\Videos" would otherwise be
            // normalised into an invented http identity that another device would try to dial.
            if (input.Type == ServerType.Local)
            {
                issues.Add(Issue(input, LocalSourceMappingReasons.LocalDevicePath));
                continue;
            }

            if (SyncUrlNormalizer.Normalize(input.Url) is not { } url)
            {
                issues.Add(Issue(input, LocalSourceMappingReasons.NoUsableEndpoint));
                continue;
            }

            bool mapsToWire = WindowsSourceTypeMap.TryToWire(input.Type, out _);
            string? sourceTypeRaw = mapsToWire ? null : Trimmed(input.SourceTypeRaw);
            if (!mapsToWire && sourceTypeRaw is null)
            {
                issues.Add(Issue(input, LocalSourceMappingReasons.NoWireType));
                continue;
            }

            records.Add(new LocalSourceRecord
            {
                ServerId = serverId,
                SyncSourceId = Trimmed(input.SyncSourceId),
                // An unmapped kind is stored as Unknown plus the verbatim wire value: the pair is what
                // lets the record survive a round trip through this build without being re-spelled.
                Type = mapsToWire ? input.Type : ServerType.Unknown,
                SourceTypeRaw = sourceTypeRaw,
                Url = url,
                AccountScope = Trimmed(input.AccountScope),
                DisplayName = Trimmed(input.DisplayName),
                RecordRevision = input.RecordRevision,
            });
        }

        return new LocalSourceMappingResult { Records = records, Issues = issues };
    }

    private static LocalSourceMappingIssue Issue(LocalSourceMappingInput input, string reason) => new()
    {
        ServerId = input.ServerId.Trim(),
        Reason = reason,
        DisplayName = Trimmed(input.DisplayName),
    };

    private static string? Trimmed(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

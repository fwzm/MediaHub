namespace MediaHub.Sync;

/// <summary>One address a record carries that this client will not import.</summary>
/// <remarks>
/// Windows stores a single address per source, while Android stores several. Dropping the others silently would
/// make a preview say "nothing else to import" while the peer really did configure a second address, so every
/// non-primary endpoint is listed here with a stable reason code.
/// </remarks>
public sealed record SourceSyncNotImportedEndpoint
{
    /// <summary>Identity of the record the endpoint belongs to.</summary>
    public string SyncSourceId { get; init; } = "";

    /// <summary>Display name of that record, for a row label only.</summary>
    public string DisplayName { get; init; } = "";

    /// <summary>Address exactly as the peer sent it.</summary>
    public string Url { get; init; } = "";

    /// <summary>The address after <see cref="SyncUrlNormalizer"/>, or null when it does not normalise.</summary>
    public string? NormalizedUrl { get; init; }

    /// <summary>Sender-side preference order, smaller first.</summary>
    public int SortOrder { get; init; }

    /// <summary>Whether the sender marked this endpoint primary (several may be marked, D2 allows one).</summary>
    public bool IsPrimary { get; init; }

    /// <summary>Whether the sender considers the address enabled.</summary>
    public bool Enabled { get; init; } = true;

    /// <summary>Stable reason code from <see cref="SourceSyncEndpointPolicy"/>.</summary>
    public string Reason { get; init; } = SourceSyncEndpointPolicy.SecondaryNotImported;
}

/// <summary>What one envelope would leave behind on a single-endpoint client.</summary>
public sealed record SourceSyncEndpointSummary
{
    /// <summary>Every endpoint that will not be imported, in envelope order.</summary>
    public IReadOnlyList<SourceSyncNotImportedEndpoint> Items { get; init; } = [];

    /// <summary>How many addresses will not be imported.</summary>
    public int NotImportedCount => Items.Count;

    /// <summary>Records that offered more than one address.</summary>
    public int RecordsWithAlternatives { get; init; }

    /// <summary>Records that offered no address at all; they cannot be dialled and cannot be matched by address.</summary>
    public int RecordsWithoutEndpoint { get; init; }

    /// <summary>True when every record offered exactly one address.</summary>
    public bool IsEmpty => Items.Count == 0;
}

/// <summary>
/// Which endpoint of a multi-endpoint record is used, and what happens to the rest.
/// </summary>
/// <remarks>
/// <para>
/// The contract allows several endpoints per source (D2) and Android uses that; this client stores one address per
/// source. The rule is the one W6-1 fixed: import the primary endpoint — the marked one with the smallest
/// <c>sortOrder</c>, or the smallest <c>sortOrder</c> overall when no endpoint is marked — and report every other
/// endpoint as <see cref="SecondaryNotImported"/> instead of discarding it. The reason code is stable so the UI can
/// localise it and a test can assert it.
/// </para>
/// <para>
/// Selection is deterministic: ties keep the sender's order, never an arbitrary pick. No wall clock and no
/// revision is involved — an address list is not a conflict (D5).
/// </para>
/// </remarks>
public static class SourceSyncEndpointPolicy
{
    /// <summary>A non-primary endpoint of a record: listed, never imported (audit §5.4).</summary>
    public const string SecondaryNotImported = "secondary-endpoint-not-imported";

    /// <summary>Picks the endpoint this client should import, or null when the record has none.</summary>
    public static SourceEndpoint? SelectPrimary(SourceRecord? record)
    {
        if (record is null || record.Endpoints.Count == 0)
        {
            return null;
        }

        SourceEndpoint? primary = null;
        SourceEndpoint? any = null;
        foreach (SourceEndpoint endpoint in record.Endpoints)
        {
            if (endpoint is null)
            {
                continue;
            }

            if (any is null || endpoint.SortOrder < any.SortOrder)
            {
                any = endpoint;
            }

            if (endpoint.IsPrimary && (primary is null || endpoint.SortOrder < primary.SortOrder))
            {
                primary = endpoint;
            }
        }

        return primary ?? any;
    }

    /// <summary>Every endpoint of one record that this client will not import.</summary>
    public static IReadOnlyList<SourceSyncNotImportedEndpoint> NotImported(SourceRecord? record)
    {
        if (record is null || record.Endpoints.Count <= 1)
        {
            return [];
        }

        SourceEndpoint? primary = SelectPrimary(record);
        var items = new List<SourceSyncNotImportedEndpoint>(record.Endpoints.Count - 1);
        bool skippedPrimary = false;
        foreach (SourceEndpoint endpoint in record.Endpoints)
        {
            if (endpoint is null)
            {
                continue;
            }

            if (!skippedPrimary && ReferenceEquals(endpoint, primary))
            {
                // Only the first occurrence of the chosen endpoint is the primary; a duplicate row is still reported.
                skippedPrimary = true;
                continue;
            }

            items.Add(Describe(record, endpoint, primary));
        }

        return items;
    }

    /// <summary>Every endpoint in an envelope that this client will not import.</summary>
    public static IReadOnlyList<SourceSyncNotImportedEndpoint> NotImported(SourceSyncEnvelope? envelope)
    {
        if (envelope is null)
        {
            return [];
        }

        var items = new List<SourceSyncNotImportedEndpoint>();
        foreach (SourceRecord record in envelope.Sources)
        {
            items.AddRange(NotImported(record));
        }

        return items;
    }

    /// <summary>Counts what an envelope would leave behind, so a plan summary can show the number.</summary>
    public static SourceSyncEndpointSummary Summarize(SourceSyncEnvelope? envelope)
    {
        if (envelope is null)
        {
            return new SourceSyncEndpointSummary();
        }

        var items = new List<SourceSyncNotImportedEndpoint>();
        int recordsWithAlternatives = 0;
        int recordsWithoutEndpoint = 0;
        foreach (SourceRecord record in envelope.Sources)
        {
            if (record is null)
            {
                continue;
            }

            if (record.Endpoints.Count == 0)
            {
                recordsWithoutEndpoint++;
                continue;
            }

            if (record.Endpoints.Count > 1)
            {
                recordsWithAlternatives++;
            }

            items.AddRange(NotImported(record));
        }

        return new SourceSyncEndpointSummary
        {
            Items = items,
            RecordsWithAlternatives = recordsWithAlternatives,
            RecordsWithoutEndpoint = recordsWithoutEndpoint,
        };
    }

    private static SourceSyncNotImportedEndpoint Describe(
        SourceRecord record,
        SourceEndpoint endpoint,
        SourceEndpoint? primary)
    {
        return new SourceSyncNotImportedEndpoint
        {
            SyncSourceId = record.SyncSourceId?.Trim() ?? "",
            DisplayName = record.DisplayName ?? "",
            Url = endpoint.Url ?? "",
            NormalizedUrl = SyncUrlNormalizer.Normalize(endpoint.Url),
            SortOrder = endpoint.SortOrder,
            IsPrimary = ReferenceEquals(endpoint, primary),
            Enabled = endpoint.Enabled,
            Reason = SecondaryNotImported,
        };
    }
}

namespace MediaHub.Sync;

/// <summary>
/// The per-field names a record's content hash is split across.
/// </summary>
/// <remarks>
/// <para>
/// The digest reports <em>which</em> fields changed, not merely that something did, so the baseline keeps one
/// hash per field next to the whole-record hash. The names are the camelCase wire names of the record rather
/// than the C# property names, so a change reads the same in a diff, in the baseline and on the wire.
/// </para>
/// <para>
/// <see cref="All"/> is alphabetical, which is also the order
/// <see cref="SourceSyncContentHash.Canonicalize"/> writes the fields in.
/// </para>
/// </remarks>
public static class SourceSyncFields
{
    /// <summary>Account the source is logged in as.</summary>
    public const string AccountScope = "accountScope";

    /// <summary>Name shown to the user. A snapshot, but still a field the user can change.</summary>
    public const string DisplayName = "displayName";

    /// <summary>Addresses of the source.</summary>
    public const string Endpoints = "endpoints";

    /// <summary>Wire source type.</summary>
    public const string SourceType = "sourceType";

    /// <summary>Every hashed field, alphabetically.</summary>
    public static IReadOnlyList<string> All { get; } = Array.AsReadOnly(
    [
        AccountScope,
        DisplayName,
        Endpoints,
        SourceType,
    ]);
}

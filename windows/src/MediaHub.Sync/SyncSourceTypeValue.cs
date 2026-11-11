namespace MediaHub.Sync;

/// <summary>
/// A wire <c>sourceType</c> kept exactly as received, together with whether this build knows it.
/// </summary>
/// <remarks>
/// <para>
/// "Unknown" is not an error. A source type added on Android first must survive a round trip through
/// Windows unchanged, so that the user sees "saved, not supported yet" instead of a source that vanished
/// (ADR-S001 D3). <see cref="Raw"/> is therefore never rewritten; <see cref="Wire"/> is only the trimmed,
/// lowercase form used for comparison.
/// </para>
/// <para>
/// Equality is value-based and includes <see cref="Raw"/>, so two spellings of one known type are the same
/// kind (<see cref="IsKnown"/>) but not the same value. Compare with <see cref="Wire"/> when the question is
/// "which kind of source is this?".
/// </para>
/// </remarks>
public sealed record SyncSourceTypeValue
{
    private SyncSourceTypeValue(string raw, string wire, bool isKnown)
    {
        Raw = raw;
        Wire = wire;
        IsKnown = isKnown;
    }

    /// <summary>The value exactly as it appeared in the document (never trimmed, never re-cased).</summary>
    public string Raw { get; }

    /// <summary>Trimmed, lowercase form used for comparison; empty when the document carried no value.</summary>
    public string Wire { get; }

    /// <summary>Whether this build has a definition for <see cref="Wire"/>.</summary>
    public bool IsKnown { get; }

    /// <summary>Wraps <paramref name="raw"/>; null and blank are preserved as their own values.</summary>
    public static SyncSourceTypeValue Parse(string? raw)
    {
        string text = raw ?? "";
        string wire = text.Trim().ToLowerInvariant();
        return new SyncSourceTypeValue(text, wire, SyncSourceTypes.IsKnown(wire));
    }

    /// <summary>Renders the value as received, so logging or a preview never hides the other device's spelling.</summary>
    public override string ToString() => Raw;
}

namespace MediaHub.Storage;

/// <summary>
/// One entry of a saved playlist: a reference to a library item, never a copy of it.
/// </summary>
/// <remarks>
/// <para>
/// Identity is <see cref="ServerId"/> plus <see cref="ItemId"/>; <see cref="Title"/> and
/// <see cref="RunTimeTicks"/> are display snapshots taken when the item was added, so a playlist
/// stays readable while its server is offline. They never take part in matching: renaming an item on
/// the server must not turn one entry into two, and re-adding the same item by a different title is
/// still the same entry.
/// </para>
/// <para>
/// Identity comparison is ordinal. Item ids are opaque strings owned by the provider, so the store
/// does not try to guess at case- or culture-insensitive variants.
/// </para>
/// </remarks>
public sealed record SavedPlaylistItem
{
    /// <summary>Id of the source the item came from, in the same form as <c>PlaybackRequest.ServerId</c>.</summary>
    public required string ServerId { get; init; }

    /// <summary>The provider's own id for the item within that source.</summary>
    public required string ItemId { get; init; }

    /// <summary>Title as it looked when the item was added; display only.</summary>
    public required string Title { get; init; }

    /// <summary>Duration snapshot in 100 ns ticks, or null when the provider reported none.</summary>
    public long? RunTimeTicks { get; init; }

    /// <summary>True when both entries denote the same library item.</summary>
    public bool HasSameIdentityAs(SavedPlaylistItem? other)
        => other is not null
           && string.Equals(ServerId, other.ServerId, StringComparison.Ordinal)
           && string.Equals(ItemId, other.ItemId, StringComparison.Ordinal);
}

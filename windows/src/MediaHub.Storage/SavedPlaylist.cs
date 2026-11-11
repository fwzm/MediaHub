namespace MediaHub.Storage;

/// <summary>
/// A named list of library items that the user created and expects to find again after a restart.
/// </summary>
/// <remarks>
/// <para>
/// Immutable: every edit (rename, add, remove, reorder) produces a new instance, so a caller holding
/// a playlist is looking at a stable snapshot rather than at state another thread is changing under
/// it. <see cref="Items"/> is the play order and is only changed through
/// <see cref="PlaylistStore.MoveItem"/>.
/// </para>
/// <para>
/// A saved playlist is not <c>MediaHub.Domain.PlaybackQueue</c>. The queue is a temporary, in-memory
/// "play these now" list that dies with the session; a saved playlist is durable user data. Nothing
/// in this type or in <see cref="PlaylistStore"/> enqueues anything: items reach the queue only when
/// the user explicitly queues or plays them.
/// </para>
/// </remarks>
public sealed record SavedPlaylist
{
    /// <summary>Stable id, a GUID in "N" format (32 lowercase hex digits).</summary>
    public required string Id { get; init; }

    /// <summary>User-visible name. Duplicates are allowed; id is the identity.</summary>
    public required string Name { get; init; }

    /// <summary>When the playlist was created (UTC). <see cref="PlaylistStore.GetAll"/> sorts on it.</summary>
    public DateTimeOffset CreatedAt { get; init; }

    /// <summary>When the playlist last changed, or <see cref="CreatedAt"/> while untouched.</summary>
    public DateTimeOffset UpdatedAt { get; init; }

    /// <summary>The entries, in play order.</summary>
    public IReadOnlyList<SavedPlaylistItem> Items { get; init; } = Array.Empty<SavedPlaylistItem>();
}

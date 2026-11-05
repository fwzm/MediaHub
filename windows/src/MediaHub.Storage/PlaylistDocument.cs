namespace MediaHub.Storage;

/// <summary>
/// On-disk shape of <c>playlists.json</c>: <c>{ "version": 1, "playlists": [...] }</c>.
/// </summary>
/// <remarks>
/// The DTOs are deliberately more permissive than the public models: every field is optional here so
/// a hand-edited or partially written file degrades to "that entry is skipped" instead of taking the
/// whole document down. Anything that does not parse at all is handled in
/// <see cref="PlaylistStore"/> by quarantining the file.
/// </remarks>
internal sealed class PlaylistDocument
{
    public int Version { get; set; }

    public PlaylistRecord?[]? Playlists { get; set; }
}

internal sealed class PlaylistRecord
{
    public string? Id { get; set; }

    public string? Name { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public ItemRecord?[]? Items { get; set; }
}

internal sealed class ItemRecord
{
    public string? ServerId { get; set; }

    public string? ItemId { get; set; }

    public string? Title { get; set; }

    public long? RunTimeTicks { get; set; }
}

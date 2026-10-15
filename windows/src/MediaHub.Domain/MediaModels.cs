namespace MediaHub.Domain;

/// <summary>A logical media server (Emby/Jellyfin/Plex/WebDAV endpoint) or a local root.</summary>
public sealed record MediaServer(
    string ServerId,
    ServerType Type,
    string DisplayName,
    IReadOnlyList<ServerEndpoint> Endpoints)
{
    public ServerEndpoint? PrimaryEndpoint => Endpoints.Count > 0 ? Endpoints[0] : null;
}

/// <summary>One concrete address for a media server. Multiple endpoints enable line failover.</summary>
public sealed record ServerEndpoint(string Url, bool Enabled = true, string? Label = null);

public sealed record MediaUser(string Id, string Name, bool IsAdministrator = false);

/// <summary>
/// Unified catalog entry. <see cref="ServerId"/> + <see cref="Id"/> together form the resource
/// identity; <see cref="Path"/> is retained for local/WebDAV providers where Id is path-derived.
/// </summary>
public sealed record MediaItem
{
    public required string ServerId { get; init; }
    public required string Id { get; init; }
    public required string Name { get; init; }
    public MediaType Type { get; init; } = MediaType.Other;
    public string? Path { get; init; }
    public string? Overview { get; init; }
    public int? Year { get; init; }
    public long? RunTimeTicks { get; init; }
    public long? FileSizeBytes { get; init; }
    public string? PrimaryImageTag { get; init; }
    public string? BackdropImageTag { get; init; }
    public double? CommunityRating { get; init; }
    public string? SeriesId { get; init; }
    public string? SeriesName { get; init; }
    public int? SeasonNumber { get; init; }
    public int? EpisodeNumber { get; init; }
    public IReadOnlyList<string> Genres { get; init; } = Array.Empty<string>();
    public long? UserDataPlaybackPositionTicks { get; init; }
    public bool? UserDataPlayed { get; init; }
    public bool IsFolder { get; init; }

    public TimeSpan? RunTime => RunTimeTicks is { } t ? TimeSpan.FromTicks(t) : null;
}

public sealed record MediaLibrary(string Id, string Name, LibraryType Type, string? CollectionType = null);

public sealed record Season(string Id, string SeriesId, string Name, int SeasonNumber);

public sealed record Episode(
    string Id,
    string SeriesId,
    string SeasonId,
    string Name,
    int SeasonNumber,
    int EpisodeNumber);

public sealed record MediaDetail
{
    public required MediaItem Item { get; init; }
    public string? Tagline { get; init; }
    public string? OfficialRating { get; init; }
    public IReadOnlyList<string> Studios { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> Directors { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> Cast { get; init; } = Array.Empty<string>();
    public IReadOnlyList<MediaStreamInfo> Streams { get; init; } = Array.Empty<MediaStreamInfo>();
}

/// <summary>
/// Technical stream description as reported by the source. Every field is nullable on purpose:
/// unknown values must stay unknown, never be fabricated.
/// </summary>
public sealed record MediaStreamInfo
{
    public required string Type { get; init; }
    public int Index { get; init; }
    public string? Codec { get; init; }
    public string? Profile { get; init; }
    public int? Width { get; init; }
    public int? Height { get; init; }
    public double? FrameRate { get; init; }
    public long? BitRate { get; init; }
    public int? Channels { get; init; }
    public int? SampleRate { get; init; }
    public string? Language { get; init; }
    public string? DisplayTitle { get; init; }
    public string? ColorPrimaries { get; init; }
    public string? ColorTransfer { get; init; }
    public string? PixelFormat { get; init; }
    public bool IsDefault { get; init; }
    public bool IsExternal { get; init; }
}

public sealed record SubtitleTrack(
    int Index,
    string? Language,
    string? Title,
    string? Codec,
    bool IsExternal,
    bool IsDefault,
    bool IsForced,
    string? DeliveryUrl = null);

public sealed record AudioTrack(
    int Index,
    string? Language,
    string? Title,
    string? Codec,
    int? Channels,
    int? SampleRate,
    bool IsDefault,
    string? DecoderName = null);

public sealed record PageRequest(int Offset = 0, int Limit = 50)
{
    public static PageRequest First(int limit = 50) => new(0, limit);
}

public sealed record PagedResult<T>(IReadOnlyList<T> Items, int Offset, int? TotalCount)
{
    public static PagedResult<T> Empty { get; } = new(Array.Empty<T>(), 0, 0);
    public bool HasMore => TotalCount is { } total && Offset + Items.Count < total;
}

/// <summary>
/// Credentials held only in memory for the duration of a request. Never serialised to disk as-is;
/// the vault is responsible for encrypted persistence.
/// </summary>
public sealed record Credentials(string? Username = null, string? Password = null, string? ApiKey = null, string? Token = null);

/// <summary>Authentication session state driving login UI. Mirrors Android <c>AuthSessionState</c>.</summary>
public enum AuthSessionState
{
    Unknown = 0,
    Authenticated,
    NotAuthenticated,
    Expired,
    Unreachable,
}

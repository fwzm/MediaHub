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

    /// <summary>
    /// Server-side favourite flag for the current user, or null when the response did not say.
    /// </summary>
    /// <remarks>
    /// Null is the honest default: a list response that omits UserData must not render as
    /// "not favourited", because the user would then see an unstarred item they starred elsewhere.
    /// </remarks>
    public bool? UserDataFavorite { get; init; }
    public bool IsFolder { get; init; }

    /// <summary>
    /// Technical facts of the primary source, when the list response carried them. Used by the
    /// optional poster badges; every field stays null when the provider did not report it.
    /// </summary>
    public int? Width { get; init; }
    public int? Height { get; init; }
    public string? VideoCodec { get; init; }
    public string? AudioCodec { get; init; }
    public int? AudioChannels { get; init; }
    public string? VideoRange { get; init; }
    public string? Container { get; init; }
    public long? BitRate { get; init; }

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

    /// <summary>Every playable version the source holds for this item, in the server's order.</summary>
    public IReadOnlyList<MediaVersion> Versions { get; init; } = Array.Empty<MediaVersion>();
}

/// <summary>
/// One playable version ("media source") of a title.
/// </summary>
/// <remarks>
/// A server can hold several files for one item — a 4K remux, a 1080p copy, a phone-sized encode.
/// Without this list the client could only say "the server picked a version"; the user could neither
/// see which one is playing nor ask for another. The supports flags are the server's answer for this
/// client, so <see cref="TranscodeOnly"/> is a fact reported by the source, not a guess about codecs.
/// </remarks>
public sealed record MediaVersion
{
    public required string MediaSourceId { get; init; }

    /// <summary>Short, human-readable line such as "1920×1080 · h264 · mp4 · 直连".</summary>
    public required string Label { get; init; }

    public string? Name { get; init; }
    public string? Container { get; init; }
    public int? Width { get; init; }
    public int? Height { get; init; }
    public string? VideoCodec { get; init; }
    public long? BitRate { get; init; }
    public long? SizeBytes { get; init; }
    public bool SupportsDirectPlay { get; init; }
    public bool SupportsDirectStream { get; init; }
    public bool IsDefault { get; init; }

    /// <summary>True when the server offers no direct path for this version, only a transcode.</summary>
    public bool TranscodeOnly => !SupportsDirectPlay && !SupportsDirectStream;
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

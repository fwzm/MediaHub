namespace MediaHub.Domain;

/// <summary>Kind of backing media source. Mirrors Android <c>com.mediahub.model.ServerType</c>.</summary>
public enum ServerType
{
    Unknown = 0,
    Emby,
    Jellyfin,
    Plex,
    WebDav,
    Local,
    Smb,
}

/// <summary>Unified media item kind. Mirrors Android <c>com.mediahub.model.MediaType</c>.</summary>
public enum MediaType
{
    Other = 0,
    Movie,
    Series,
    Season,
    Episode,
    Video,
    Audio,
    Folder,
    LiveTv,
}

/// <summary>Library grouping. Mirrors Android <c>com.mediahub.model.LibraryType</c>.</summary>
public enum LibraryType
{
    Unknown = 0,
    Movies,
    TvShows,
    Music,
    Photos,
    Mixed,
    Folder,
}

/// <summary>
/// Playback mode three-state (ADR-004 semantics carried over to Windows).
/// </summary>
public enum PlaybackMode
{
    /// <summary>Container/codecs accepted as-is by the player engine.</summary>
    DirectPlay = 0,

    /// <summary>Remuxed but not re-encoded by the server (Emby "Direct Stream").</summary>
    Stream,

    /// <summary>Server-side transcode.</summary>
    Transcode,
}

/// <summary>Structured playback failure taxonomy. Mirrors Android <c>PlaybackError</c>.</summary>
public enum PlaybackErrorKind
{
    Unknown = 0,
    AuthExpired,
    UrlExpired,
    Http403,
    Http404,
    Http429,
    Http5xx,
    Network,
    DecoderError,
    Unsupported,
    Cancelled,
    NotFound,
}

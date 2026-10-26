namespace MediaHub.Domain;

/// <summary>
/// Capability declaration. Two distinct semantics, carried over from Android ADR-014 / ADR-022:
/// <list type="bullet">
/// <item><c>ProviderDescriptor.DeclaredCapabilities</c> — what the provider kind is <b>planned</b> to support.</item>
/// <item><c>ProviderHandle.RuntimeCapabilities</c> — what this build <b>actually implements</b>; the only
/// authority the UI may use to enable/disable features.</item>
/// </list>
/// UI must never branch on <c>ServerType</c>; it must read runtime capabilities.
/// </summary>
[Flags]
public enum ProviderCapability
{
    None = 0,

    /// <summary>Username/password or token authentication.</summary>
    Auth = 1 << 0,

    /// <summary>Library browsing (libraries / items / seasons / episodes).</summary>
    Library = 1 << 1,

    /// <summary>Single item detail.</summary>
    Detail = 1 << 2,

    /// <summary>Folder/file tree browsing (local disk, SMB, WebDAV, cloud).</summary>
    Browse = 1 << 3,

    /// <summary>Resolve a playable source at play time (ADR-003: URLs are never persisted).</summary>
    Playback = 1 << 4,

    /// <summary>Search.</summary>
    Search = 1 << 5,

    /// <summary>Exact canonical identity lookup.</summary>
    IdentityLookup = 1 << 6,

    /// <summary>Server-side sort/filter query pipeline.</summary>
    Query = 1 << 7,

    /// <summary>Subtitle track listing.</summary>
    Subtitle = 1 << 8,

    /// <summary>Sibling-file external subtitle discovery.</summary>
    SubtitleDiscovery = 1 << 9,

    /// <summary>Playback progress synchronisation.</summary>
    Progress = 1 << 10,

    /// <summary>Multiple files/versions for one logical item.</summary>
    MultiVersion = 1 << 11,

    /// <summary>Favourites.</summary>
    Favorite = 1 << 12,

    /// <summary>Live TV.</summary>
    LiveTv = 1 << 13,

    /// <summary>Server transcoding.</summary>
    Transcode = 1 << 14,
}

namespace MediaHub.Sync;

/// <summary>
/// The <c>sourceType</c> vocabulary of the cross-device sync contract (ADR-S001 D3).
/// </summary>
/// <remarks>
/// <para>
/// The two clients do not share an enum. Android has thirteen values, Windows seven, and the values the
/// two have in common differ in casing (<c>WEBDAV</c> vs <c>WebDav</c>, <c>LOCAL</c> vs <c>Local</c>).
/// Sending either enum's name across the wire would turn any future rename on either side into a silent
/// protocol break, so the contract defines its own stable, lowercase strings and each side keeps an
/// explicit adapter — <see cref="WindowsSourceTypeMap"/> for Windows.
/// </para>
/// <para>
/// The strings are protocol, not display text: never re-spell an existing value, only add a new one. A
/// device that receives a value it does not know must keep it verbatim (see
/// <see cref="SyncSourceTypeValue"/>); dropping it would show the user "sync succeeded" on one device
/// while a source quietly disappeared on the other, which is the failure this contract exists to prevent.
/// </para>
/// </remarks>
public static class SyncSourceTypes
{
    /// <summary>Emby (MediaBrowser dialect).</summary>
    public const string Emby = "emby";

    /// <summary>Jellyfin.</summary>
    public const string Jellyfin = "jellyfin";

    /// <summary>Plex.</summary>
    public const string Plex = "plex";

    /// <summary>WebDAV.</summary>
    public const string WebDav = "webdav";

    /// <summary>SMB / UNC share.</summary>
    public const string Smb = "smb";

    /// <summary>Local filesystem.</summary>
    public const string Local = "local";

    /// <summary>FnOS NAS. Known on the wire; Android-only today.</summary>
    public const string FnNas = "fn-nas";

    /// <summary>Aliyun Drive. Known on the wire; Android-only today.</summary>
    public const string AliyunDrive = "aliyun-drive";

    /// <summary>Baidu Netdisk. Known on the wire; Android-only today.</summary>
    public const string BaiduDrive = "baidu-drive";

    /// <summary>Quark Drive. Known on the wire; Android-only today.</summary>
    public const string QuarkDrive = "quark-drive";

    /// <summary>China Mobile Cloud (139). Known on the wire; Android-only today.</summary>
    public const string ChinaMobileCloud = "china-mobile-cloud";

    /// <summary>Tianyi Cloud (189). Known on the wire; Android-only today.</summary>
    public const string TianyiCloud = "tianyi-cloud";

    private static readonly string[] KnownValues =
    [
        Emby,
        Jellyfin,
        Plex,
        WebDav,
        Smb,
        Local,
        FnNas,
        AliyunDrive,
        BaiduDrive,
        QuarkDrive,
        ChinaMobileCloud,
        TianyiCloud,
    ];

    // Ordinal-ignore-case because the wire is defined lowercase, but a hand-edited document or a
    // future client that shouts its enum names should still be recognised rather than silently
    // becoming an "unknown, unsupported" source.
    private static readonly HashSet<string> Known = new(KnownValues, StringComparer.OrdinalIgnoreCase);

    /// <summary>Every wire value this version knows, in documentation order.</summary>
    public static IReadOnlyList<string> All { get; } = Array.AsReadOnly(KnownValues);

    /// <summary>Whether <paramref name="wire"/> is a value this version knows (trimmed, case-insensitive).</summary>
    public static bool IsKnown(string? wire)
        => !string.IsNullOrWhiteSpace(wire) && Known.Contains(wire.Trim());

    /// <summary>
    /// Wraps a wire string for use in the contract. Never throws and never rejects: an unknown value is a
    /// legal value that this build cannot act on.
    /// </summary>
    public static SyncSourceTypeValue Parse(string? raw) => SyncSourceTypeValue.Parse(raw);
}

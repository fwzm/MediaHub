using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>One row of the explicit wire ↔ Windows enum table.</summary>
/// <param name="Wire">Contract value, as defined by <see cref="SyncSourceTypes"/>.</param>
/// <param name="Type">The Windows enum value it stands for.</param>
public sealed record WindowsSourceTypeMapping(string Wire, ServerType Type);

/// <summary>
/// The Windows adapter between <see cref="SyncSourceTypes"/> and <see cref="ServerType"/>.
/// </summary>
/// <remarks>
/// <para>
/// Windows knows seven enum values; the contract knows twelve wire values. Six pairs map one to one. The
/// remaining values are known on the wire but unsupported here (<c>fn-nas</c> and the Android-only drives),
/// and <see cref="ServerType.Unknown"/> has no wire value at all — "unknown" is the absence of a kind, not a
/// kind that another device could have configured.
/// </para>
/// <para>
/// Deliberately an explicit table rather than <c>Enum.Parse</c>/<c>ToString</c>: the casing differs
/// (<c>WebDav</c> vs <c>webdav</c>), the two platforms do not have the same set, and a rename on either side
/// must break a test here rather than change the protocol silently.
/// </para>
/// </remarks>
public static class WindowsSourceTypeMap
{
    private static readonly WindowsSourceTypeMapping[] Rows =
    [
        new(SyncSourceTypes.Emby, ServerType.Emby),
        new(SyncSourceTypes.Jellyfin, ServerType.Jellyfin),
        new(SyncSourceTypes.Plex, ServerType.Plex),
        new(SyncSourceTypes.WebDav, ServerType.WebDav),
        new(SyncSourceTypes.Smb, ServerType.Smb),
        new(SyncSourceTypes.Local, ServerType.Local),
    ];

    /// <summary>The explicit table, in documentation order. Tests iterate it instead of trusting it.</summary>
    public static IReadOnlyList<WindowsSourceTypeMapping> Mappings { get; } = Array.AsReadOnly(Rows);

    /// <summary>Wire values this build can actually import.</summary>
    public static IReadOnlyList<string> SupportedWireValues { get; }
        = Array.AsReadOnly(Rows.Select(row => row.Wire).ToArray());

    /// <summary>
    /// Resolves a wire value to a Windows type, keeping the value verbatim either way.
    /// </summary>
    /// <remarks>
    /// This is the entry point when an unknown value must not be lost: the returned resolution always
    /// carries the original string, and only reports whether Windows has a type for it.
    /// </remarks>
    public static WindowsSourceTypeResolution Resolve(string? wire)
    {
        SyncSourceTypeValue value = SyncSourceTypeValue.Parse(wire);
        foreach (WindowsSourceTypeMapping row in Rows)
        {
            if (string.Equals(row.Wire, value.Wire, StringComparison.Ordinal))
                return new WindowsSourceTypeResolution(value, row.Type);
        }

        return new WindowsSourceTypeResolution(value, null);
    }

    /// <summary>Whether a wire value maps to a Windows type.</summary>
    public static bool TryToServerType(string? wire, out ServerType type)
    {
        WindowsSourceTypeResolution resolution = Resolve(wire);
        type = resolution.ServerType ?? ServerType.Unknown;
        return resolution.IsSupported;
    }

    /// <summary>Maps a wire value, falling back to <see cref="ServerType.Unknown"/> for anything unsupported.</summary>
    public static ServerType ToServerType(string? wire) => TryToServerType(wire, out ServerType type) ? type : ServerType.Unknown;

    /// <summary>Maps a Windows type to its wire value; false for <see cref="ServerType.Unknown"/> and undefined values.</summary>
    public static bool TryToWire(ServerType type, out string wire)
    {
        foreach (WindowsSourceTypeMapping row in Rows)
        {
            if (row.Type == type)
            {
                wire = row.Wire;
                return true;
            }
        }

        wire = "";
        return false;
    }

    /// <summary>Maps a Windows type to its wire value, or null when it has none.</summary>
    public static string? ToWire(ServerType type) => TryToWire(type, out string wire) ? wire : null;
}

/// <summary>Outcome of resolving a wire value against the Windows enum.</summary>
/// <param name="SourceType">The wire value, preserved verbatim.</param>
/// <param name="ServerType">The Windows type, or null when this build cannot import the value.</param>
public readonly record struct WindowsSourceTypeResolution(SyncSourceTypeValue SourceType, ServerType? ServerType)
{
    /// <summary>Whether the wire value is part of the contract vocabulary.</summary>
    public bool IsKnown => SourceType.IsKnown;

    /// <summary>Whether Windows has a type for it.</summary>
    public bool IsSupported => ServerType.HasValue;
}

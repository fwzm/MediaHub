using MediaHub.Domain;

namespace MediaHub.Providers.WebDav;

/// <summary>
/// Seek capability of a WebDAV source, resolved by asking the server instead of assuming.
/// </summary>
/// <remarks>
/// <para>
/// The Windows <see cref="PlaybackSource"/> contract has no "supports seeking" field — the Android
/// model did, and this is where that information had to go rather than being lost. A caller that
/// wants a seek bar must ask this interface; a caller that does not gets the conservative default.
/// </para>
/// <para>
/// The honest fallback is visible in <see cref="IProviderPlayback.ResolvePlaybackAsync"/>: when the
/// server ignores <c>Range</c>, the requested start position is dropped to null rather than handed to
/// an engine that cannot honour it, and <see cref="LastSeekSupport"/> reports
/// <see cref="WebDavRangeSupport.NotSupported"/> so the UI can disable seeking instead of lying to the
/// user with a bar that snaps back.
/// </para>
/// </remarks>
public interface IWebDavSeekCapability
{
    /// <summary>Result of the last range probe performed for this handle, or Unknown before the first.</summary>
    WebDavRangeSupport LastSeekSupport { get; }

    /// <summary>
    /// Probes (once per item, with a per-origin negative cache) whether this server honours Range.
    /// </summary>
    Task<WebDavRangeSupport> GetSeekSupportAsync(MediaItem item, CancellationToken ct = default);
}

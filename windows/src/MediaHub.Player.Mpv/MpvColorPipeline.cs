namespace MediaHub.Player.Mpv;

/// <summary>How per-frame HDR metadata (HDR10+ ST 2094-40 and Dolby Vision RPU) is used.</summary>
public enum HdrDynamicMetadataMode
{
    /// <summary>
    /// Let the engine decide: it follows the source's metadata and measures the picture only when it
    /// has to tone map. This is the value every build so far shipped with, so it stays the default.
    /// </summary>
    FollowSource = 0,

    /// <summary>
    /// Always trust the metadata carried by the stream (HDR10+ scene data, Dolby Vision L1-L11).
    /// Useful when the measured peak makes a graded title pump or look too bright.
    /// </summary>
    SourceMetadata = 1,

    /// <summary>
    /// Ignore per-scene metadata and measure the peak from the decoded picture instead. The most
    /// conservative choice when dynamic metadata is malformed; it also evens out scene pumping.
    /// </summary>
    ComputePeak = 2,
}

/// <summary>
/// The colour-pipeline half of the engine configuration.
/// </summary>
/// <remarks>
/// These options are pinned explicitly rather than left to the engine defaults because the values
/// that matter for Dolby Vision and HDR10+ are defaults the engine may change between builds, and a
/// change there shows up as a picture that is washed out (dynamic metadata ignored), crushed, or
/// over-bright (peak handling changed). Every value below equals the engine default of the validated
/// build, so pinning them does not alter today's playback — it freezes it.
/// </remarks>
public static class MpvColorPipeline
{
    /// <summary>Stored value for <see cref="HdrDynamicMetadataMode.FollowSource"/>; the shipped default.</summary>
    public const string FollowSourceValue = "auto";

    /// <summary>Stored value for <see cref="HdrDynamicMetadataMode.SourceMetadata"/>.</summary>
    public const string SourceMetadataValue = "metadata";

    /// <summary>Stored value for <see cref="HdrDynamicMetadataMode.ComputePeak"/>.</summary>
    public const string ComputePeakValue = "compute";

    /// <summary>Maps a persisted setting back to a mode; unknown or missing values mean "follow source".</summary>
    public static HdrDynamicMetadataMode Parse(string? stored) => stored?.Trim().ToLowerInvariant() switch
    {
        SourceMetadataValue or "no" => HdrDynamicMetadataMode.SourceMetadata,
        ComputePeakValue or "yes" => HdrDynamicMetadataMode.ComputePeak,
        _ => HdrDynamicMetadataMode.FollowSource,
    };

    /// <summary>The value written to the settings file.</summary>
    public static string ToStorageValue(HdrDynamicMetadataMode mode) => mode switch
    {
        HdrDynamicMetadataMode.SourceMetadata => SourceMetadataValue,
        HdrDynamicMetadataMode.ComputePeak => ComputePeakValue,
        _ => FollowSourceValue,
    };

    /// <summary>The engine's own value for <c>hdr-compute-peak</c>.</summary>
    public static string ToEngineValue(HdrDynamicMetadataMode mode) => mode switch
    {
        HdrDynamicMetadataMode.SourceMetadata => "no",
        HdrDynamicMetadataMode.ComputePeak => "yes",
        _ => "auto",
    };

    /// <summary>A short human label used by the settings page and the player log.</summary>
    public static string Describe(HdrDynamicMetadataMode mode) => mode switch
    {
        HdrDynamicMetadataMode.SourceMetadata => "仅用片源元数据",
        HdrDynamicMetadataMode.ComputePeak => "引擎实时计算峰值",
        _ => "跟随片源",
    };

    /// <summary>
    /// Engine options that make up the colour pipeline. Applied before the user's extra options so an
    /// explicit advanced override still wins.
    /// </summary>
    public static IReadOnlyDictionary<string, string> BuildOptions(HdrDynamicMetadataMode mode)
        => new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            // HDR content on an HDR desktop is passed through; on an SDR desktop libplacebo tone maps.
            // "auto" is the engine default and the only value that gets both right without a UI switch
            // for the display's own HDR state.
            ["target-colorspace-hint"] = "auto",
            // Keep the engine's validated tone-mapping curve. Dolby Vision reshaping happens before
            // this stage and is not affected by the curve; changing it here would regress SDR output
            // for HDR10 sources that were already accepted.
            ["tone-mapping"] = "auto",
            ["hdr-compute-peak"] = ToEngineValue(mode),
        };
}

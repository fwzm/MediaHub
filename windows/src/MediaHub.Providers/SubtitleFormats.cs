namespace MediaHub.Providers;

/// <summary>
/// Maps a source-declared subtitle codec to the format the server can render and the file extension
/// the temporary sidecar must carry for the engine to pick the right demuxer.
/// </summary>
/// <remarks>
/// Text only, deliberately. Bitmap subtitles (PGS, VobSub, DVB) would have to be burned in by the
/// server, which is transcoding, and a sidecar the client cannot parse is worse than none: it looks
/// like a selectable track and plays nothing.
/// </remarks>
public static class SubtitleFormats
{
    /// <summary>True when the codec is a text format this client can hand to the engine as a file.</summary>
    public static bool TryResolve(string? codec, out string format, out string extension)
    {
        switch (codec?.Trim().ToLowerInvariant())
        {
            case "srt":
            case "subrip":
            case "mov_text":
            case "tx3g":
            case "text":
                format = "srt";
                extension = "srt";
                return true;
            case "ass":
            case "ssa":
                // The server renders SSA as ASS; the engine reads both through the same demuxer.
                format = "ass";
                extension = "ass";
                return true;
            case "vtt":
            case "webvtt":
                format = "vtt";
                extension = "vtt";
                return true;
            default:
                format = string.Empty;
                extension = string.Empty;
                return false;
        }
    }
}

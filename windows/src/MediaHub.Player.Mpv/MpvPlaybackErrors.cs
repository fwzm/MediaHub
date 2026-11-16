namespace MediaHub.Player.Mpv;

/// <summary>
/// Turns the engine's end-file error plus the tail of its own log into a sentence the viewer can act on.
/// </summary>
/// <remarks>
/// The engine reports "loading failed" for almost everything: a missing file, an HTTP 401 from the
/// provider, a GPU that cannot initialise a decoder and a video output that failed to open all arrive
/// as the same string. The actual reason only exists in the log, which is why the caller keeps the last
/// few lines and passes them here instead of putting the raw error on screen.
/// </remarks>
public static class MpvPlaybackErrors
{
    private const int MaxDetailLength = 160;

    /// <summary>
    /// Builds the message shown when a load or playback fails.
    /// </summary>
    /// <param name="engineError">The engine's own error string, e.g. "loading failed".</param>
    /// <param name="logLines">Recent engine log lines, oldest first; may be empty.</param>
    /// <param name="isDolbyVision">True when the engine decoded a Dolby Vision source before failing.</param>
    public static string Describe(string? engineError, IEnumerable<string>? logLines, bool isDolbyVision)
    {
        string error = (engineError ?? string.Empty).Trim();
        string[] lines = (logLines ?? Array.Empty<string>())
            .Select(line => (line ?? string.Empty).Trim())
            .Where(line => line.Length > 0)
            .ToArray();
        string log = string.Join("\n", lines).ToLowerInvariant();

        if ((log.Contains("video output") && log.Contains("fail"))
            || (log.Contains("gpu context") && log.Contains("fail"))
            || (log.Contains("vo/gpu") && log.Contains("fail"))
            || log.Contains("failed to initialize the video output"))
        {
            return "无法初始化视频输出（gpu-next / D3D11）。请更新显卡驱动，或在“设置 → 高级 → 硬件解码”中改用软件解码后重试。"
                + DolbyVisionHint(isDolbyVision);
        }

        if ((log.Contains("decoder") && log.Contains("fail"))
            || log.Contains("could not open codec")
            || log.Contains("no decoder")
            || error.Contains("unsupported", StringComparison.OrdinalIgnoreCase))
        {
            return "解码失败：当前显卡或驱动无法解码这条视频流。请在“设置 → 高级 → 硬件解码”中改为“自动”或“软件解码”后重试。"
                + (isDolbyVision ? "这条片源是杜比视界，硬解需要显卡支持 10-bit HEVC。" : string.Empty);
        }

        if (log.Contains("401") || log.Contains("403") || log.Contains("unauthorized") || log.Contains("forbidden"))
        {
            return "服务器拒绝了播放请求：登录状态或访问权限已失效，请重新登录该服务器后重试。";
        }

        if (log.Contains(" 404") || log.Contains("not found"))
        {
            return "播放地址不存在：服务端可能已删除该文件，或转码任务还没有就绪。";
        }

        if (log.Contains("timed out") || log.Contains("timeout") || log.Contains("connection refused"))
        {
            return "连接播放源失败或超时，请检查网络（或代理）设置后重试。";
        }

        if (log.Contains("certificate") || log.Contains("ssl") || log.Contains("tls"))
        {
            return "与服务器的安全连接失败（证书或 TLS 握手问题），请检查该服务器的证书信任设置。";
        }

        if (error.Contains("no audio or video", StringComparison.OrdinalIgnoreCase))
        {
            return "该地址没有可播放的音视频流。";
        }

        string reason = error.Length == 0 ? "引擎没有给出原因" : error;
        string? detail = LastUsefulLine(lines);
        return "播放失败（引擎：" + reason + "）"
            + (detail is null ? "，可在“设置 → 高级”打开引擎日志查看细节。" : "：" + detail);
    }

    private static string DolbyVisionHint(bool isDolbyVision)
        => isDolbyVision ? "这条片源是杜比视界，对显卡和驱动的要求更高。" : string.Empty;

    /// <summary>The last non-empty log line, trimmed to something a status bar can show.</summary>
    private static string? LastUsefulLine(IReadOnlyList<string> lines)
    {
        for (int i = lines.Count - 1; i >= 0; i--)
        {
            string line = lines[i];
            if (line.Length == 0) continue;
            return line.Length <= MaxDetailLength ? line : line[..MaxDetailLength] + "…";
        }
        return null;
    }
}

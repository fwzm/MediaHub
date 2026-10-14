namespace MediaHub.Player.Mpv;

/// <summary>
/// Engine construction options. Defaults are the values validated for the MediaHub Windows client;
/// every one of them is overridable so the settings page can drive them.
/// </summary>
public sealed record MpvPlayerOptions
{
    /// <summary>Parent window handle for <c>--wid</c> embedding. Zero selects a headless engine.</summary>
    public IntPtr Wid { get; init; } = IntPtr.Zero;

    /// <summary>Video output driver. <c>gpu-next</c> is the current libplacebo-based renderer.</summary>
    public string VideoOutput { get; init; } = "gpu-next";

    /// <summary>GPU context. D3D11 is the native Windows presentation path.</summary>
    public string GpuContext { get; init; } = "d3d11";

    /// <summary>Hardware decoding mode. <c>auto-safe</c> falls back to software when unsupported.</summary>
    public string HwDec { get; init; } = "auto-safe";

    /// <summary>Direct3D 11 video decode API.</summary>
    public string HwDecCodecs { get; init; } = "d3d11va";

    public bool Osc { get; init; }
    public int OsdLevel { get; init; } = 1;
    public bool KeepOpen { get; init; } = true;
    public bool Idle { get; init; } = true;
    public string? LogFile { get; init; }

    /// <summary>Optional input.conf path; when null the engine's built-in bindings are used.</summary>
    public string? InputConf { get; init; }

    /// <summary>Extra options applied verbatim; used by the settings page and by tests.</summary>
    public IReadOnlyDictionary<string, string> ExtraOptions { get; init; } =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
}

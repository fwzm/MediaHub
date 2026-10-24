using System.Globalization;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediaHub.EngineProbe;

/// <summary>Everything one probe run measured, in both human and machine readable form.</summary>
internal sealed class ProbeReport
{
    public static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        // The reason/next-step text is Chinese; keep the evidence file readable instead of \uXXXX soup.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public string Label { get; set; } = "";

    public string DllPath { get; set; } = "";

    public long DllBytes { get; set; }

    public string DllSha256 { get; set; } = "";

    /// <summary>How the library was actually loaded; empty when loading failed.</summary>
    public string LoadMode { get; set; } = "";

    public IReadOnlyList<string> DependencyDirectories { get; set; } = [];

    public uint ClientApiVersion { get; set; }

    public int ClientApiMajor => (int)(ClientApiVersion >> 16);

    public int ClientApiMinor => (int)(ClientApiVersion & 0xFFFF);

    public string ClientApiText => ClientApiMajor.ToString(CultureInfo.InvariantCulture) + "." + ClientApiMinor.ToString(CultureInfo.InvariantCulture);

    public string? MpvVersion { get; set; }

    public string? FfmpegVersion { get; set; }

    public IReadOnlyList<string> EnabledFeatures { get; set; } = [];

    /// <summary>Where the feature list came from; "log" means libmpv printed it during initialisation.</summary>
    public string FeaturesSource { get; set; } = "none";

    public bool FeaturesContainGpl { get; set; }

    public string MediaPath { get; set; } = "";

    public double TargetSeconds { get; set; }

    public double SuccessFloorSeconds { get; set; }

    public bool FileLoaded { get; set; }

    public double? MaxTimePos { get; set; }

    public double? FinalTimePos { get; set; }

    public bool? EofReached { get; set; }

    public int? EndFileReason { get; set; }

    public string? EndFileReasonName { get; set; }

    public int? EndFileError { get; set; }

    public string? EndFileErrorText { get; set; }

    public string? VideoCodec { get; set; }

    public string? AudioCodec { get; set; }

    public long? VideoWidth { get; set; }

    public long? VideoHeight { get; set; }

    public double? Duration { get; set; }

    public string? HwdecCurrent { get; set; }

    public List<string> ErrorLogs { get; set; } = [];

    public List<string> WarnLogs { get; set; } = [];

    public int ErrorLogTotal { get; set; }

    public int WarnLogTotal { get; set; }

    public bool LogCaptureAvailable { get; set; }

    public bool TimedOut { get; set; }

    public long ElapsedMilliseconds { get; set; }

    public List<string> OptionErrors { get; set; } = [];

    public string? Failure { get; set; }

    public string Verdict { get; set; } = "FAIL";

    public string Reason { get; set; } = "";

    public int ExitCode { get; set; } = 3;

    /// <summary>Builds the identity half of a report before the library is touched.</summary>
    public static ProbeReport Create(ProbeOptions options)
    {
        FileInfo info = new(options.DllPath);
        return new ProbeReport
        {
            Label = options.Label,
            DllPath = options.DllPath,
            DllBytes = info.Exists ? info.Length : 0,
            DllSha256 = info.Exists ? ComputeSha256(options.DllPath) : "",
            DependencyDirectories = options.DependencyDirectories,
            MediaPath = options.MediaPath,
            TargetSeconds = options.Seconds,
            SuccessFloorSeconds = options.SuccessFloorSeconds
        };
    }

    public static string ComputeSha256(string path)
    {
        using FileStream stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    public void Fail(string message)
    {
        Failure = message;
    }

    /// <summary>Decides PASS/FAIL and the process exit code from what was measured.</summary>
    public void Evaluate()
    {
        if (TimedOut)
        {
            Verdict = "FAIL";
            ExitCode = 4;
            Reason = string.IsNullOrEmpty(Failure) ? "探针自超时（120 秒），没有拿到结论。" : Failure;
            return;
        }

        if (!string.IsNullOrEmpty(Failure))
        {
            Verdict = "FAIL";
            ExitCode = 3;
            Reason = Failure;
            return;
        }

        List<string> reasons = [];
        if (!LogCaptureAvailable)
        {
            reasons.Add("无法请求日志消息（mpv_request_log_messages 失败），不能证明没有 error 日志");
        }

        if (!FileLoaded)
        {
            reasons.Add("没有收到 file-loaded 事件");
        }

        double reached = MaxTimePos ?? 0;
        if (reached < SuccessFloorSeconds)
        {
            reasons.Add(
                "time-pos 只推进到 " + Format(reached) + " 秒，低于下限 " + Format(SuccessFloorSeconds) + " 秒");
        }

        if (ErrorLogTotal > 0)
        {
            reasons.Add("捕获到 " + ErrorLogTotal.ToString(CultureInfo.InvariantCulture) + " 条 error 级日志");
        }

        if (reasons.Count > 0)
        {
            Verdict = "FAIL";
            ExitCode = 3;
            Reason = string.Join("；", reasons);
            return;
        }

        Verdict = "PASS";
        ExitCode = 0;
        Reason = "file-loaded 且 time-pos 推进到 " + Format(reached) + " 秒（下限 " + Format(SuccessFloorSeconds) + " 秒），error 级日志 0 条";
    }

    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);

    /// <summary>Human readable block printed to stdout (PowerShell reads the JSON instead).</summary>
    public IReadOnlyList<string> ToLines()
    {
        List<string> lines =
        [
            "== MediaHub engine probe ==",
            "label          : " + Label,
            "dll            : " + DllPath,
            "dll bytes      : " + DllBytes.ToString(CultureInfo.InvariantCulture),
            "dll sha256     : " + DllSha256,
            "load mode      : " + (LoadMode.Length == 0 ? "(not loaded)" : LoadMode),
            "dependency dirs: " + (DependencyDirectories.Count == 0 ? "(none)" : string.Join("; ", DependencyDirectories)),
            "client api     : " + ClientApiText + " (0x" + ClientApiVersion.ToString("x8", CultureInfo.InvariantCulture) + ")",
            "mpv version    : " + Or(MpvVersion, "n/a"),
            "ffmpeg version : " + Or(FfmpegVersion, "n/a"),
            "enabled feats  : " + (EnabledFeatures.Count == 0 ? "n/a" : string.Join(" ", EnabledFeatures)) + "  [source: " + FeaturesSource + "]",
            "gpl token      : " + (FeaturesContainGpl ? "present" : "absent"),
            "media          : " + MediaPath,
            "target         : " + Format(TargetSeconds) + " s (floor " + Format(SuccessFloorSeconds) + " s)",
            "file loaded    : " + FileLoaded,
            "max time-pos   : " + Format(MaxTimePos) + " s",
            "final time-pos : " + Format(FinalTimePos) + " s",
            "eof reached    : " + (EofReached is null ? "n/a" : EofReached.Value.ToString()),
            "end-file       : " + (EndFileReason is null ? "n/a" : EndFileReasonName + " (" + EndFileReason.Value.ToString(CultureInfo.InvariantCulture) + ", error " + (EndFileError ?? 0).ToString(CultureInfo.InvariantCulture) + (EndFileErrorText is null ? "" : " / " + EndFileErrorText) + ")"),
            "video          : " + Or(VideoCodec, "n/a") + " " + (VideoWidth is null || VideoHeight is null ? "" : VideoWidth.Value.ToString(CultureInfo.InvariantCulture) + "x" + VideoHeight.Value.ToString(CultureInfo.InvariantCulture)),
            "audio          : " + Or(AudioCodec, "n/a"),
            "duration       : " + (Duration is null ? "n/a" : Format(Duration) + " s"),
            "hwdec-current  : " + Or(HwdecCurrent, "n/a"),
            "log capture    : " + (LogCaptureAvailable ? "available" : "UNAVAILABLE"),
            "error logs     : " + ErrorLogTotal.ToString(CultureInfo.InvariantCulture),
            "warn logs      : " + WarnLogTotal.ToString(CultureInfo.InvariantCulture),
            "timed out      : " + TimedOut,
            "elapsed        : " + ElapsedMilliseconds.ToString(CultureInfo.InvariantCulture) + " ms",
            "exit code      : " + ExitCode.ToString(CultureInfo.InvariantCulture),
            "verdict        : " + Verdict,
            "reason         : " + Reason
        ];

        foreach (string optionError in OptionErrors)
        {
            lines.Add("option warning : " + optionError);
        }

        foreach (string log in ErrorLogs)
        {
            lines.Add("error log      : " + log);
        }

        foreach (string log in WarnLogs)
        {
            lines.Add("warn log       : " + log);
        }

        return lines;
    }

    private static string Format(double? value) => value is null ? "n/a" : Format(value.Value);

    private static string Format(double value) => value.ToString("0.000", CultureInfo.InvariantCulture);

    private static string Or(string? value, string fallback) => string.IsNullOrWhiteSpace(value) ? fallback : value;
}

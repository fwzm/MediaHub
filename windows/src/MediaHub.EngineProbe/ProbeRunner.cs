using System.Diagnostics;
using System.Globalization;
using System.IO;
using MediaHub.EngineProbe;

namespace MediaHub.EngineProbe;

/// <summary>Wall-clock budget of one probe run, shared by the runner and the watchdog thread.</summary>
internal sealed class Deadline
{
    private readonly Stopwatch _watch = Stopwatch.StartNew();
    private readonly TimeSpan _budget;
    private volatile bool _timedOut;

    public Deadline(TimeSpan budget)
    {
        _budget = budget;
    }

    public bool Expired => _timedOut || _watch.Elapsed >= _budget;

    public bool TimedOut => _timedOut;

    public TimeSpan Elapsed => _watch.Elapsed;

    public void MarkTimedOut() => _timedOut = true;
}

/// <summary>
/// Drives one headless libmpv playback and fills a <see cref="ProbeReport"/>.
/// </summary>
/// <remarks>
/// The probe is deliberately deaf and blind: <c>vo=null</c> and <c>ao=null</c> mean libmpv decodes
/// without touching a window or an audio device, which is exactly why this run is reproducible in CI
/// and exactly why it cannot stand in for GUI evidence (no HDR, no hardware decoding, no subtitle
/// rendering, no audio output).
/// </remarks>
internal sealed class ProbeRunner
{
    /// <summary>Options every probe run applies before <c>mpv_initialize</c>.</summary>
    private static readonly (string Name, string Value)[] BaseOptions =
    [
        ("config", "no"),
        ("load-scripts", "no"),
        ("terminal", "no"),
        ("msg-level", "all=v"),
        ("vo", "null"),
        ("ao", "null"),
        ("pause", "no"),
        ("idle", "yes"),
        ("keep-open", "no"),
        ("osc", "no"),
        ("input-default-bindings", "no"),
        ("input-vo-keyboard", "no"),
        ("ytdl", "no"),
        ("hwdec", "no"),
        ("save-position-on-quit", "no")
    ];

    /// <summary>Options whose failure would invalidate the evidence, so they abort the run.</summary>
    private static readonly string[] CriticalOptions = ["vo", "ao", "pause", "config"];

    private const int MaximumCapturedLogs = 20;
    private const string EnabledFeaturesMarker = "List of enabled features:";

    private readonly ProbeOptions _options;
    private readonly MpvApi _api;

    public ProbeRunner(ProbeOptions options, MpvApi api)
    {
        _options = options;
        _api = api;
    }

    public void Run(ProbeReport report, Deadline deadline)
    {
        Stopwatch watch = Stopwatch.StartNew();
        IntPtr context = IntPtr.Zero;
        try
        {
            report.ClientApiVersion = _api.ClientApiVersion();
            context = _api.Create();
            if (context == IntPtr.Zero)
            {
                report.Fail("mpv_create 返回空句柄，无法继续探测");
                return;
            }

            // Attach the log client before initialization: libmpv announces its version and the
            // "List of enabled features:" line from [cplayer] at level v while it initializes, so a
            // client attached afterwards never sees the licence-relevant feature tokens.
            int logRequest = _api.RequestLogMessages(context, "v");
            report.LogCaptureAvailable = logRequest >= 0;
            if (!report.LogCaptureAvailable)
            {
                // Never let "no error logs observed" pass while logging was never enabled.
                report.Fail("mpv_request_log_messages 返回 " + logRequest.ToString(CultureInfo.InvariantCulture) + "（" + _api.ErrorString(logRequest) + "），无法观察到 error 级日志");
                return;
            }

            if (!ApplyOptions(context, report))
            {
                return;
            }

            int initialize = _api.Initialize(context);
            if (initialize < 0)
            {
                report.Fail("mpv_initialize 失败：" + _api.ErrorString(initialize));
                return;
            }

            report.MpvVersion = _api.GetString(context, "mpv-version");
            report.FfmpegVersion = _api.GetString(context, "ffmpeg-version");

            int load = _api.Command(context, "loadfile", _options.MediaPath, "replace");
            if (load < 0)
            {
                report.Fail("loadfile 被拒绝：" + _api.ErrorString(load));
                return;
            }

            PlaybackLoop(context, report, deadline, watch);
        }
        catch (Exception exception) when (exception is not OutOfMemoryException and not StackOverflowException)
        {
            report.Fail(exception.GetType().Name + ": " + exception.Message);
        }
        finally
        {
            if (context != IntPtr.Zero)
            {
                try
                {
                    _api.TerminateDestroy(context);
                }
                catch (Exception exception) when (exception is not OutOfMemoryException and not StackOverflowException)
                {
                    report.OptionErrors.Add("mpv_terminate_destroy 抛出 " + exception.GetType().Name + "：" + exception.Message);
                }
            }

            report.ElapsedMilliseconds = watch.ElapsedMilliseconds;
        }
    }

    private bool ApplyOptions(IntPtr context, ProbeReport report)
    {
        foreach ((string name, string value) in BaseOptions)
        {
            int result = _api.SetOptionString(context, name, value);
            if (result >= 0)
            {
                continue;
            }

            string message = name + "=" + value + " 失败：" + _api.ErrorString(result);
            if (CriticalOptions.Contains(name, StringComparer.Ordinal))
            {
                report.Fail("无法设置关键选项 " + message);
                return false;
            }

            report.OptionErrors.Add(message);
        }

        return true;
    }

    private void PlaybackLoop(IntPtr context, ProbeReport report, Deadline deadline, Stopwatch watch)
    {
        double wallBudgetSeconds = _options.Seconds + 15;
        while (true)
        {
            if (deadline.Expired)
            {
                report.TimedOut = true;
                report.Fail("探针自超时（" + ((int)Math.Round(deadline.Elapsed.TotalSeconds)).ToString(CultureInfo.InvariantCulture) + " 秒），没有拿到结论");
                return;
            }

            IntPtr pointer = _api.WaitEvent(context, 0.1);
            if (pointer != IntPtr.Zero && HandleEvent(pointer, context, report))
            {
                return;
            }

            Sample(context, report);

            if (report.MaxTimePos is { } reached && reached >= _options.Seconds)
            {
                return;
            }

            if (report.EofReached == true)
            {
                return;
            }

            if (watch.Elapsed.TotalSeconds > wallBudgetSeconds)
            {
                report.Fail(
                    "在 " + wallBudgetSeconds.ToString("0.#", CultureInfo.InvariantCulture) + " 秒内没有达到 "
                    + _options.Seconds.ToString("0.#", CultureInfo.InvariantCulture) + " 秒的播放进度（最高 "
                    + (report.MaxTimePos is { } max ? max.ToString("0.000", CultureInfo.InvariantCulture) : "n/a") + " 秒）");
                return;
            }
        }
    }

    /// <summary>Handles one event; returns true when the run should stop.</summary>
    private bool HandleEvent(IntPtr pointer, IntPtr context, ProbeReport report)
    {
        MpvApi.MpvEvent mpvEvent = MpvApi.ReadEvent(pointer);
        switch (mpvEvent.EventId)
        {
            case MpvApi.EventNone:
                return false;

            case MpvApi.EventShutdown:
                report.Fail("libmpv 在达到目标播放进度前关闭（shutdown 事件）");
                return true;

            case MpvApi.EventFileLoaded:
                report.FileLoaded = true;
                return false;

            case MpvApi.EventLogMessage:
                CaptureLog(mpvEvent, report);
                return false;

            case MpvApi.EventEndFile:
                RecordEndFile(mpvEvent, report);
                return true;

            default:
                return false;
        }
    }

    private void RecordEndFile(MpvApi.MpvEvent mpvEvent, ProbeReport report)
    {
        if (mpvEvent.Data == IntPtr.Zero)
        {
            report.Fail("收到 end-file 事件但事件数据为空");
            return;
        }

        MpvApi.MpvEventEndFile end = MpvApi.ReadEndFile(mpvEvent.Data);
        report.EndFileReason = end.Reason;
        report.EndFileReasonName = DescribeEndFileReason(end.Reason);
        report.EndFileError = end.Error;
        if (end.Error != 0)
        {
            report.EndFileErrorText = _api.ErrorString(end.Error);
        }

        if (end.Reason == MpvApi.EndFileReasonError)
        {
            report.Fail("libmpv 报告播放出错（end-file reason=error）：" + (report.EndFileErrorText ?? "错误码 " + end.Error.ToString(CultureInfo.InvariantCulture)));
        }
    }

    private void CaptureLog(MpvApi.MpvEvent mpvEvent, ProbeReport report)
    {
        if (mpvEvent.Data == IntPtr.Zero)
        {
            return;
        }

        MpvApi.MpvEventLogMessage message = MpvApi.ReadLogMessage(mpvEvent.Data);
        string text = MpvApi.MarshalledString(message.Text) ?? "";
        string prefix = MpvApi.MarshalledString(message.Prefix) ?? "";
        string levelName = MpvApi.MarshalledString(message.Level) ?? "";

        if (report.FeaturesSource == "none" &&
            text.Contains(EnabledFeaturesMarker, StringComparison.OrdinalIgnoreCase))
        {
            CaptureFeatures(text, report);
        }

        int severity = message.LogLevel != 0 ? message.LogLevel : SeverityFromName(levelName);
        string line = prefix.Length == 0 ? text : prefix + ": " + text;

        if (severity == 10 || severity == 20 || string.Equals(levelName, "error", StringComparison.OrdinalIgnoreCase) || string.Equals(levelName, "fatal", StringComparison.OrdinalIgnoreCase))
        {
            report.ErrorLogTotal++;
            if (report.ErrorLogs.Count < MaximumCapturedLogs)
            {
                report.ErrorLogs.Add(line);
            }

            return;
        }

        if (severity == 30 || string.Equals(levelName, "warn", StringComparison.OrdinalIgnoreCase))
        {
            report.WarnLogTotal++;
            if (report.WarnLogs.Count < MaximumCapturedLogs)
            {
                report.WarnLogs.Add(line);
            }
        }
    }

    private static void CaptureFeatures(string text, ProbeReport report)
    {
        int colon = text.IndexOf(':');
        string tail = colon >= 0 ? text[(colon + 1)..] : text;
        string[] tokens = tail.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        report.EnabledFeatures = tokens;
        report.FeaturesSource = "log";
        report.FeaturesContainGpl = tokens.Contains("gpl", StringComparer.OrdinalIgnoreCase);
    }

    private static int SeverityFromName(string levelName) => levelName.Trim().ToLowerInvariant() switch
    {
        "fatal" => 10,
        "error" => 20,
        "warn" => 30,
        "info" => 40,
        "v" => 50,
        "debug" => 60,
        "trace" => 70,
        _ => 0
    };

    private static string DescribeEndFileReason(int reason) => reason switch
    {
        MpvApi.EndFileReasonEof => "eof",
        MpvApi.EndFileReasonStop => "stop",
        MpvApi.EndFileReasonQuit => "quit",
        MpvApi.EndFileReasonError => "error",
        _ => "reason-" + reason.ToString(CultureInfo.InvariantCulture)
    };

    /// <summary>Samples the properties the report needs; cheap enough to run on every event cycle.</summary>
    private void Sample(IntPtr context, ProbeReport report)
    {
        if (_api.GetDouble(context, "time-pos") is { } position)
        {
            report.FinalTimePos = position;
            report.MaxTimePos = report.MaxTimePos is { } previous ? Math.Max(previous, position) : position;
        }

        report.EofReached ??= _api.GetFlag(context, "eof-reached");
        report.Duration ??= _api.GetDouble(context, "duration");

        if (!report.FileLoaded)
        {
            return;
        }

        report.VideoCodec ??= _api.GetString(context, "video-codec");
        report.AudioCodec ??= _api.GetString(context, "audio-codec");
        report.VideoWidth ??= _api.GetInt64(context, "width");
        report.VideoHeight ??= _api.GetInt64(context, "height");
        report.HwdecCurrent ??= _api.GetString(context, "hwdec-current");
    }
}

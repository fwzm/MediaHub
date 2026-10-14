using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using MediaHub.Player;
using MediaHub.Player.Mpv;

namespace MediaHub.Tools.MpvProbe;

/// <summary>
/// Headless integration probe for the mpv engine.
/// </summary>
/// <remarks>
/// <para>
/// This exists because the interesting failures of an embedded engine are invisible to unit tests:
/// a mis-set <c>wid</c>, a video output that cannot initialise on the host GPU, or an event loop
/// that deadlocks while handling events all look identical from the outside — the app runs, the
/// window is there, and nothing moves.
/// </para>
/// <para>
/// The probe drives the engine through a full load/play/seek/pause cycle against a real file and
/// asserts on observed engine state, so "playback works" is a checkable claim rather than a
/// visual impression. It exits non-zero with a specific reason when any stage fails.
/// </para>
/// </remarks>
internal static class Program
{
    private const string WindowClass = "MediaHub.MpvProbe.Host";
    private static readonly ManualResetEventSlim PumpReady = new(false);

    private static int Main(string[] args)
    {
        // Build-identity mode. The supply-chain record needs the engine's real version and build
        // configuration, which only the library itself can report.
        if (args.Length > 0 && args[0].Equals("--info", StringComparison.OrdinalIgnoreCase))
        {
            return PrintBuildInfo();
        }

        string? mediaPath = args.Length > 0 ? args[0] : null;
        int runSeconds = args.Length > 1 && int.TryParse(args[1], out int s) ? s : 6;

        if (mediaPath is null || !File.Exists(mediaPath))
        {
            Console.Error.WriteLine("usage: MpvProbe <media-file> [seconds]");
            return 2;
        }

        Console.WriteLine($"engine: libmpv client API {MpvPlayer.ClientApiVersion}");
        Console.WriteLine($"media : {mediaPath}");

        IntPtr host = CreateHiddenHostWindow();
        if (host == IntPtr.Zero)
        {
            Console.Error.WriteLine("FAIL: could not create a host window");
            return 2;
        }

        var failures = new List<string>();
        using var surface = new MpvVideoSurface(host);
        surface.SetBounds(0, 0, 1280, 720);
        surface.Show();

        string logPath = Path.Combine(Path.GetTempPath(), "mediahub-mpvprobe.log");
        try { File.Delete(logPath); } catch { }

        // A CI runner has no GPU and no desktop, so the probe must be able to run with the decoder
        // still exercised but the presentation path disabled. Overriding through the environment
        // keeps the same code path under test instead of a second, divergent "test mode".
        string? vo = Environment.GetEnvironmentVariable("MEDIAHUB_MPV_VO");
        string? hwdec = Environment.GetEnvironmentVariable("MEDIAHUB_MPV_HWDEC");
        string? gpuContext = Environment.GetEnvironmentVariable("MEDIAHUB_MPV_GPU_CONTEXT");
        string? ao = Environment.GetEnvironmentVariable("MEDIAHUB_MPV_AO");

        var options = new MpvPlayerOptions
        {
            Wid = string.IsNullOrEmpty(vo) ? surface.HostHandle : IntPtr.Zero,
            LogFile = logPath,
            HwDec = hwdec ?? "auto-safe",
            VideoOutput = vo ?? "gpu-next",
            GpuContext = gpuContext ?? "d3d11",
            AudioOutput = ao ?? "",
            ExtraOptions = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) { ["msg-level"] = "all=v" },
        };
        Console.WriteLine("vo=" + options.VideoOutput + " hwdec=" + options.HwDec + " ctx=" + options.GpuContext
            + " ao=" + (string.IsNullOrEmpty(options.AudioOutput) ? "(default)" : options.AudioOutput));
        var player = new MpvPlayer(options);

        var observed = new List<PlayerSnapshot>();
        player.SnapshotChanged += (_, snap) => { lock (observed) observed.Add(snap); };

        try
        {
            player.LoadAsync(mediaPath, new PlaybackSettings { Volume = 0 }).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"FAIL: LoadAsync threw {ex.GetType().Name}: {ex.Message}");
            return 1;
        }

        var sw = Stopwatch.StartNew();
        PlayerSnapshot? best = null;
        while (sw.Elapsed.TotalSeconds < runSeconds)
        {
            Thread.Sleep(400);
            var snap = player.Snapshot;
            if (best is null || snap.PositionSeconds > best.PositionSeconds) best = snap;
            Console.WriteLine($"  t={sw.Elapsed.TotalSeconds,4:0.0}s state={snap.State,-8} pos={snap.PositionSeconds,7:0.000} dur={snap.DurationSeconds,7:0.000} " +
                              $"size={(snap.Width?.ToString() ?? "?")}x{(snap.Height?.ToString() ?? "?")} codec={snap.Technical.VideoCodec ?? "?"} hwdec={snap.Technical.HwDecCurrent ?? "?"} " +
                              $"vo={snap.Technical.VideoOutput ?? "?"} ctx={snap.Technical.GpuContext ?? "?"} evloop={player.IsEventLoopRunning}");
        }

        // ---- assertions ----
        var final = player.Snapshot;

        if (!player.IsEventLoopRunning) failures.Add("mpv event loop is not running (thread exited)");
        if (final.DurationSeconds <= 0 && best is { DurationSeconds: <= 0 }) failures.Add("engine never reported a positive duration");
        if (best is null || best.PositionSeconds <= 0.05) failures.Add("playback position never advanced");
        if (best?.Width is null or <= 0) failures.Add("engine never reported a video width");
        if (string.IsNullOrEmpty(final.Technical.VideoCodec)) failures.Add("engine never reported a video codec");
        if (final.Technical.HwDecCurrent is null) failures.Add("engine never reported hwdec-current");

        // Transport assertions are ordered so they hold for clips of any length. A short fixture may
        // already have reached end-of-file after the observation window, and a paused-at-EOF engine
        // is legitimately "Ended" rather than "Paused", so the engine is seeked back and resumed
        // first and only then asked to pause.
        double span = final.DurationSeconds > 0.5 ? final.DurationSeconds : 4.0;
        player.SeekAbsolute(span * 0.25);
        player.Play();
        Thread.Sleep(900);
        double seeked = player.Snapshot.PositionSeconds;
        if (seeked < 0.2) failures.Add($"seek did not move position (landed at {seeked:0.000})");

        // Pause must be observable by the engine, not merely recorded by the caller.
        player.Pause();
        Thread.Sleep(600);
        TransportState pausedState = player.Snapshot.State;
        double pausedPos = player.Snapshot.PositionSeconds;
        Thread.Sleep(900);
        double stillPos = player.Snapshot.PositionSeconds;
        if (pausedState != TransportState.Paused) failures.Add($"state after Pause was {pausedState}, expected Paused");
        if (Math.Abs(stillPos - pausedPos) > 0.35) failures.Add($"pause did not hold position ({pausedPos:0.000} -> {stillPos:0.000})");

        // Resume must actually restart the clock.
        player.Play();
        Thread.Sleep(900);
        double resumedPos = player.Snapshot.PositionSeconds;
        if (resumedPos <= stillPos + 0.05) failures.Add($"resume did not advance the clock ({stillPos:0.000} -> {resumedPos:0.000})");

        // Track enumeration must produce at least one audio track for a normal movie file.
        if (final.AudioTracks.Count == 0) failures.Add("no audio tracks were enumerated");

        // The engine holds an exclusive handle on its log until it is shut down.
        player.Dispose();

        if (File.Exists(logPath))
        {
            Console.WriteLine();
            Console.WriteLine("---- engine log (last 40 lines) ----");
            foreach (string line in File.ReadLines(logPath).TakeLast(40)) Console.WriteLine("  " + line);
        }

        Console.WriteLine();
        Console.WriteLine($"RESULT duration={final.DurationSeconds:0.000}s reached={best?.PositionSeconds ?? 0:0.000}s seeked={seeked:0.000}s " +
                          $"audioTracks={final.AudioTracks.Count} subTracks={final.SubtitleTracks.Count} snapshots={observed.Count}");

        if (failures.Count == 0)
        {
            Console.WriteLine("MPV_PROBE PASS");
            return 0;
        }

        foreach (string f in failures) Console.Error.WriteLine("MPV_PROBE FAIL: " + f);
        return 1;
    }

    /// <summary>Prints the engine's build identity, used by the dependency record.</summary>
    private static int PrintBuildInfo()
    {
        using var player = new MpvPlayer(new MpvPlayerOptions
        {
            Wid = IntPtr.Zero,
            VideoOutput = "null",
            AudioOutput = "null",
            HwDec = "no",
        });

        string dll = Path.Combine(AppContext.BaseDirectory, "libmpv-2.dll");
        Console.WriteLine("libmpv-2.dll           : " + dll);
        if (File.Exists(dll))
        {
            using var stream = File.OpenRead(dll);
            Console.WriteLine("libmpv-2.dll sha256    : " + Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(stream)).ToLowerInvariant());
            Console.WriteLine("libmpv-2.dll bytes     : " + new FileInfo(dll).Length);
        }

        Console.WriteLine("client api version     : " + MpvPlayer.ClientApiVersion);
        foreach (string property in new[] { "mpv-version", "mpv-configuration", "ffmpeg-version", "libass-version" })
        {
            Console.WriteLine((property + ":").PadRight(23) + ": " + (player.GetProperty(property) ?? "(property not available)"));
        }

        // The configuration string is what determines the licence: a build that enables GPL-only
        // components is distributed under the GPL even though mpv's own code is LGPL.
        string? configuration = player.GetProperty("mpv-configuration");
        string banner = player.GetProperty("mpv-version") ?? "";
        Console.WriteLine("configuration          : " + (configuration ?? "(unavailable)"));
        Console.WriteLine("gpl mentioned in banner: " + banner.Contains("GPL", StringComparison.OrdinalIgnoreCase));
        return 0;
    }

    // ---------------------------------------------------------------- hidden host window

    private static IntPtr CreateHiddenHostWindow()
    {
        var thread = new Thread(() =>
        {
            var wc = new WNDCLASSEX
            {
                cbSize = (uint)Marshal.SizeOf<WNDCLASSEX>(),
                lpfnWndProc = Marshal.GetFunctionPointerForDelegate(WndProc),
                hInstance = GetModuleHandle(null),
                lpszClassName = WindowClass,
            };
            RegisterClassEx(ref wc);
            IntPtr hwnd = CreateWindowEx(0, WindowClass, "MediaHub MpvProbe", 0x00000000 /* WS_OVERLAPPED */,
                0, 0, 1290, 760, IntPtr.Zero, IntPtr.Zero, GetModuleHandle(null), IntPtr.Zero);
            _hostHandle = hwnd;
            PumpReady.Set();

            while (GetMessage(out MSG msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
        })
        { IsBackground = true, Name = "MpvProbe.Pump" };
        thread.Start();

        PumpReady.Wait(TimeSpan.FromSeconds(5));
        return _hostHandle;
    }

    private static IntPtr _hostHandle;

    private static readonly WndProcDelegate WndProc = (h, m, w, l) => DefWindowProc(h, m, w, l);

    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int ptX;
        public int ptY;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASSEX
    {
        public uint cbSize;
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        [MarshalAs(UnmanagedType.LPWStr)] public string? lpszMenuName;
        [MarshalAs(UnmanagedType.LPWStr)] public string lpszClassName;
        public IntPtr hIconSm;
    }

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern ushort RegisterClassEx(ref WNDCLASSEX lpwcx);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowEx(uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight, IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DefWindowProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DispatchMessage(ref MSG lpmsg);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);
}

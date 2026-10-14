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
        var options = new MpvPlayerOptions
        {
            Wid = surface.HostHandle,
            LogFile = logPath,
            ExtraOptions = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) { ["msg-level"] = "all=v" },
        };
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

        // Pause must be observable by the engine, not merely recorded by the caller.
        player.Pause();
        Thread.Sleep(900);
        double pausedPos = player.Snapshot.PositionSeconds;
        Thread.Sleep(900);
        double stillPos = player.Snapshot.PositionSeconds;
        if (Math.Abs(stillPos - pausedPos) > 0.35) failures.Add($"pause did not hold position ({pausedPos:0.000} -> {stillPos:0.000})");
        if (player.Snapshot.State != TransportState.Paused) failures.Add($"state after Pause was {player.Snapshot.State}, expected Paused");

        // Seek must move the engine clock.
        player.SeekAbsolute(Math.Max(0.5, final.DurationSeconds * 0.5));
        Thread.Sleep(1200);
        double seeked = player.Snapshot.PositionSeconds;
        if (seeked < 0.2) failures.Add($"seek did not move position (landed at {seeked:0.000})");

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

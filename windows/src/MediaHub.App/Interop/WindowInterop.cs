using System.Runtime.InteropServices;

namespace MediaHub.App.Interop;

/// <summary>Win32 helpers used to place and observe the native video surface.</summary>
internal static class WindowInterop
{
    internal const int VK_LBUTTON = 0x01;

    [StructLayout(LayoutKind.Sequential)]
    internal struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
        public int Width => Right - Left;
        public int Height => Bottom - Top;
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern short GetAsyncKeyState(int vKey);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", ExactSpelling = true)]
    internal static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", ExactSpelling = true)]
    internal static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    internal const int GWL_EXSTYLE = -20;
    internal const long WS_EX_NOACTIVATE = 0x08000000L;
    internal const int SW_SHOWNOACTIVATE = 4;

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ScreenToClient(IntPtr hWnd, ref POINT lpPoint);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr SetFocus(IntPtr hWnd);

    internal const int WH_MOUSE_LL = 14;
    internal const int WM_MOUSEWHEEL = 0x020A;

    [StructLayout(LayoutKind.Sequential)]
    internal struct MSLLHOOKSTRUCT
    {
        public POINT Point;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    internal delegate IntPtr LowLevelMouseProc(int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    internal static extern IntPtr SetWindowsHookEx(int idHook, LowLevelMouseProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    internal static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    internal static extern IntPtr GetModuleHandle(string? lpModuleName);
}

/// <summary>
/// Reveals the control bar when the pointer moves over the video rectangle.
/// </summary>
/// <remarks>
/// <para>
/// The engine owns a native child window on top of the XAML island, so pointer messages over the
/// video never reach XAML and no XAML-level pointer handler can fire. Rather than installing a
/// global mouse hook or subclassing the engine's window — both of which are invasive and fragile
/// across engine versions — the host polls the cursor position at ~7 Hz and only *reads* state.
/// </para>
/// <para>
/// The watcher never injects input and never dispatches actions; the engine remains the sole owner
/// of clicks and keys. That separation is what keeps the two input paths from fighting.
/// </para>
/// </remarks>
public sealed class PointerActivityWatcher : IDisposable
{
    private readonly IntPtr _windowHandle;
    private readonly Func<(int X, int Y, int Width, int Height)> _videoRectProvider;
    private readonly TimeSpan _interval;
    private readonly Timer _timer;
    private WindowInterop.POINT _lastPosition;
    private bool _disposed;

    /// <summary>Raised on the poll thread whenever the cursor moved inside the video rectangle.</summary>
    public event Action? Activity;

    public PointerActivityWatcher(
        IntPtr windowHandle,
        Func<(int X, int Y, int Width, int Height)> videoRectProvider,
        TimeSpan? interval = null)
    {
        _windowHandle = windowHandle;
        _videoRectProvider = videoRectProvider;
        _interval = interval ?? TimeSpan.FromMilliseconds(140);
        _timer = new Timer(_ => Poll(), null, _interval, _interval);
    }

    private void Poll()
    {
        if (_disposed) return;
        try
        {
            if (WindowInterop.GetForegroundWindow() != _windowHandle) return;
            if (!WindowInterop.GetCursorPos(out var screen)) return;

            var client = screen;
            if (!WindowInterop.ScreenToClient(_windowHandle, ref client)) return;

            var (x, y, w, h) = _videoRectProvider();
            bool inside = client.X >= x && client.X < x + w && client.Y >= y && client.Y < y + h;
            if (!inside)
            {
                _lastPosition = default;
                return;
            }

            int dx = Math.Abs(client.X - _lastPosition.X);
            int dy = Math.Abs(client.Y - _lastPosition.Y);
            bool first = _lastPosition.X == 0 && _lastPosition.Y == 0;
            _lastPosition = client;

            if (first || dx + dy >= 3) Activity?.Invoke();
        }
        catch
        {
            // A poll failure must never surface to the user; the control bar simply stops
            // auto-revealing and the explicit controls stay usable.
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _timer.Dispose();
    }
}

/// <summary>
/// Reads mouse-wheel gestures that happen over the native video surface.
/// </summary>
/// <remarks>
/// <para>
/// The engine's child window owns the pointer there, so a XAML <c>PointerWheelChanged</c> handler
/// never fires over the picture. A low-level hook is used instead of subclassing the engine's window
/// because the engine is free to recreate it: the hook only observes, never consumes, and is removed
/// when the player page goes away.
/// </para>
/// <para>
/// The hook is deliberately narrow: it acts only while this window is in the foreground and the
/// cursor is inside the video rectangle, and it forwards one integer per notch to the UI thread. Any
/// failure (no hook, no foreground window, a disposed page) means "no adjustment", never an exception
/// on a system-wide input path.
/// </para>
/// </remarks>
/// <summary>
/// Watches wheel notches over one window and reports which half of the gesture region the cursor is
/// on.
/// </summary>
/// <remarks>
/// The message is consumed once it has been dispatched. The player reserves the wheel for its own
/// gesture, so a surface underneath must not also scroll while the volume changes: two responses to
/// one notch is worse than either one alone.
/// </remarks>
public sealed class MouseWheelWatcher : IDisposable
{
    private readonly IntPtr _windowHandle;
    private readonly Func<(int X, int Y, int Width, int Height)> _rectProvider;
    private readonly Action<bool, double> _wheel;
    private readonly Microsoft.UI.Dispatching.DispatcherQueue _dispatcher;
    private readonly WindowInterop.LowLevelMouseProc _proc;
    private IntPtr _hook;
    private bool _disposed;

    /// <param name="wheel">Called with "cursor is on the left half" and the notch direction (+/-1).</param>
    public MouseWheelWatcher(
        IntPtr windowHandle,
        Func<(int X, int Y, int Width, int Height)> rectProvider,
        Action<bool, double> wheel)
    {
        _windowHandle = windowHandle;
        _rectProvider = rectProvider;
        _wheel = wheel;
        _dispatcher = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        _proc = OnMouse;
        _hook = WindowInterop.SetWindowsHookEx(
            WindowInterop.WH_MOUSE_LL, _proc, WindowInterop.GetModuleHandle(null), 0);
    }

    public bool IsAttached => _hook != IntPtr.Zero;

    private IntPtr OnMouse(int nCode, IntPtr wParam, IntPtr lParam)
    {
        bool consumed = false;
        if (nCode >= 0 && !_disposed && (int)wParam == WindowInterop.WM_MOUSEWHEEL)
        {
            try
            {
                if (WindowInterop.GetForegroundWindow() == _windowHandle)
                {
                    var info = Marshal.PtrToStructure<WindowInterop.MSLLHOOKSTRUCT>(lParam);
                    var client = info.Point;
                    if (WindowInterop.ScreenToClient(_windowHandle, ref client))
                    {
                        var (x, y, width, height) = _rectProvider();
                        bool inside = width > 0 && height > 0
                            && client.X >= x && client.X < x + width
                            && client.Y >= y && client.Y < y + height;
                        if (inside)
                        {
                            double direction = ((short)(info.MouseData >> 16)) > 0 ? 1 : -1;
                            bool leftHalf = client.X < x + width / 2;
                            _dispatcher.TryEnqueue(() => _wheel(leftHalf, direction));
                            consumed = true;
                        }
                    }
                }
            }
            catch
            {
                // A failure on the input path must never break scrolling for the rest of the desktop.
                consumed = false;
            }
        }

        return consumed ? (IntPtr)1 : WindowInterop.CallNextHookEx(_hook, nCode, wParam, lParam);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_hook != IntPtr.Zero)
        {
            WindowInterop.UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }
}

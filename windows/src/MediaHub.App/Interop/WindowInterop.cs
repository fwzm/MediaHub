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
    internal static extern bool ScreenToClient(IntPtr hWnd, ref POINT lpPoint);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr SetFocus(IntPtr hWnd);
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

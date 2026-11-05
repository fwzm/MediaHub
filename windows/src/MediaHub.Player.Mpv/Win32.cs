using System.Runtime.InteropServices;

namespace MediaHub.Player.Mpv;

/// <summary>Minimal user32 surface needed to host the engine's native video window.</summary>
internal static class Win32
{
    internal const uint WS_CHILD = 0x40000000;
    internal const uint WS_VISIBLE = 0x10000000;
    internal const uint WS_CLIPSIBLINGS = 0x04000000;
    internal const uint WS_CLIPCHILDREN = 0x02000000;
    internal const uint WS_EX_NOACTIVATE = 0x08000000;
    internal const uint WS_EX_TOOLWINDOW = 0x00000080;

    internal const uint SWP_NOZORDER = 0x0004;
    internal const uint SWP_NOACTIVATE = 0x0010;
    internal const uint SWP_SHOWWINDOW = 0x0040;
    internal const uint SWP_HIDEWINDOW = 0x0080;

    internal const int GWLP_USERDATA = -21;

    internal delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct WNDCLASSEXW
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

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern ushort RegisterClassExW(ref WNDCLASSEXW lpwcx);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern IntPtr CreateWindowExW(
        uint dwExStyle, string lpClassName, string? lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll", SetLastError = true, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int x, int y, int cx, int cy, uint flags);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern uint GetDpiForWindow(IntPtr hWnd);

    [DllImport("user32.dll", EntryPoint = "DefWindowProcW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", ExactSpelling = true)]
    internal static extern IntPtr SetFocus(IntPtr hWnd);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern IntPtr GetModuleHandleW(string? lpModuleName);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    internal const int SW_HIDE = 0;
    internal const int SW_SHOW = 5;
    internal const int SW_SHOWNA = 8;
}

/// <summary>
/// Owns the native child window that the engine renders into.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why a child HWND and not a composition surface.</b> Verified against the libmpv headers shipped
/// with the pinned Windows build (see docs/adr/ADR-W002): libmpv exposes exactly two render APIs,
/// <c>MPV_RENDER_API_TYPE_OPENGL</c> and <c>MPV_RENDER_API_TYPE_SW</c>. There is no D3D11 render API,
/// so a WinUI <c>SwapChainPanel</c> cannot be fed by the render API. The supported alternative is the
/// documented <c>--wid</c> embedding contract: mpv creates its own window, makes it a child of the
/// window we provide, and drives its D3D11 swapchain internally. That keeps hardware decoding and the
/// GPU present path intact and requires no private patches.
/// </para>
/// <para>
/// <b>Airspace.</b> A foreign child HWND is composited above the XAML island, so XAML content placed
/// underneath it is not visible. The player layout therefore keeps the control chrome outside the
/// video rectangle rather than trying to overlay it (see ADR-W002 for the rejected alternatives).
/// </para>
/// </remarks>
public sealed class MpvVideoSurface : IDisposable
{
    private const string WindowClassName = "MediaHub.MpvVideoSurface";
    private static readonly object ClassLock = new();
    private static bool _classRegistered;
    private static Win32.WndProcDelegate? _wndProcKeepAlive;

    private readonly IntPtr _parentHwnd;
    private IntPtr _hostHwnd;
    private bool _disposed;
    private int _x, _y, _w = 1, _h = 1;

    /// <summary>Creates the host child window under <paramref name="parentHwnd"/>.</summary>
    public MpvVideoSurface(IntPtr parentHwnd)
    {
        if (parentHwnd == IntPtr.Zero) throw new ArgumentException("parent HWND must not be zero", nameof(parentHwnd));
        _parentHwnd = parentHwnd;
        EnsureClassRegistered();

        _hostHwnd = Win32.CreateWindowExW(
            Win32.WS_EX_NOACTIVATE,
            WindowClassName,
            null,
            // Created hidden: the surface is revealed only once a media item is actually loading,
            // otherwise a 1x1 black child window would sit in the corner of the shell.
            Win32.WS_CHILD | Win32.WS_CLIPCHILDREN | Win32.WS_CLIPSIBLINGS,
            0, 0, 1, 1,
            _parentHwnd, IntPtr.Zero, Win32.GetModuleHandleW(null), IntPtr.Zero);

        if (_hostHwnd == IntPtr.Zero)
        {
            throw new InvalidOperationException($"CreateWindowExW failed: {System.Runtime.InteropServices.Marshal.GetLastWin32Error()}");
        }
    }

    /// <summary>Native handle handed to the engine through the <c>wid</c> option.</summary>
    public IntPtr HostHandle => _hostHwnd;

    /// <summary>Parent window (used for DPI queries).</summary>
    public IntPtr ParentHandle => _parentHwnd;

    /// <summary>Current bounds in physical pixels.</summary>
    public (int X, int Y, int Width, int Height) PhysicalBounds => (_x, _y, _w, _h);

    /// <summary>
    /// Moves and resizes the surface. Coordinates are device-independent pixels relative to the
    /// parent window's client area; they are scaled to physical pixels using the window's DPI so the
    /// surface stays pixel-aligned on mixed-DPI multi-monitor setups.
    /// </summary>
    public void SetBounds(double xDip, double yDip, double widthDip, double heightDip)
    {
        if (_disposed || _hostHwnd == IntPtr.Zero) return;

        double scale = GetScale();
        int x = (int)Math.Round(xDip * scale);
        int y = (int)Math.Round(yDip * scale);
        int w = Math.Max(1, (int)Math.Round(widthDip * scale));
        int h = Math.Max(1, (int)Math.Round(heightDip * scale));

        if (x == _x && y == _y && w == _w && h == _h) return;
        _x = x; _y = y; _w = w; _h = h;

        Win32.SetWindowPos(_hostHwnd, IntPtr.Zero, x, y, w, h, Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
    }

    /// <summary>Hides the surface without destroying it (used when leaving the player page).</summary>
    public void Hide() => Win32.ShowWindow(_hostHwnd, Win32.SW_HIDE);

    /// <summary>Shows the surface again after <see cref="Hide"/>.</summary>
    public void Show() => Win32.ShowWindow(_hostHwnd, Win32.SW_SHOW);

    private double GetScale()
    {
        uint dpi = Win32.GetDpiForWindow(_parentHwnd);
        if (dpi == 0) dpi = 96;
        return dpi / 96.0;
    }

    private static void EnsureClassRegistered()
    {
        lock (ClassLock)
        {
            if (_classRegistered) return;
            _wndProcKeepAlive = StaticWndProc;
            var wc = new Win32.WNDCLASSEXW
            {
                cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<Win32.WNDCLASSEXW>(),
                style = 0,
                lpfnWndProc = System.Runtime.InteropServices.Marshal.GetFunctionPointerForDelegate(_wndProcKeepAlive),
                hInstance = Win32.GetModuleHandleW(null),
                lpszClassName = WindowClassName,
                hbrBackground = IntPtr.Zero,
            };
            ushort atom = Win32.RegisterClassExW(ref wc);
            if (atom == 0)
            {
                int err = System.Runtime.InteropServices.Marshal.GetLastWin32Error();
                // 1410 = class already registered (e.g. a previous instance in the same process).
                if (err != 1410) throw new InvalidOperationException($"RegisterClassExW failed: {err}");
            }
            _classRegistered = true;
        }
    }

    private static IntPtr StaticWndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        const uint WM_ERASEBKGND = 0x0014;
        const uint WM_PAINT = 0x000F;
        const uint WM_NCHITTEST = 0x0084;
        const int HTTRANSPARENT = -1;

        switch (msg)
        {
            // Never paint: the engine's own child window covers the whole client area.
            case WM_ERASEBKGND:
                return new IntPtr(1);
            case WM_PAINT:
                return IntPtr.Zero;
            // Let the parent handle hit testing on any uncovered strip instead of swallowing input.
            case WM_NCHITTEST:
                return new IntPtr(HTTRANSPARENT);
            default:
                return Win32.DefWindowProcW(hWnd, msg, wParam, lParam);
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_hostHwnd != IntPtr.Zero)
        {
            Win32.DestroyWindow(_hostHwnd);
            _hostHwnd = IntPtr.Zero;
        }
    }
}

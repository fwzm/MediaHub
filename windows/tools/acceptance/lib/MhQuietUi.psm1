#Requires -Version 7
<#
.SYNOPSIS
Background ("quiet") UI acceptance helpers: start the app without stealing focus, drive it through
UI Automation patterns, and capture its pixels without a screen copy.

.DESCRIPTION
The rules this module exists to satisfy:

  * the app must not take the foreground while a run is in progress,
  * controls are driven through Invoke/Select/SetValue/Toggle/RangeValue instead of moving the
    cursor and injecting clicks/keys,
  * evidence comes from PrintWindow (PW_RENDERFULLCONTENT) of a specific HWND, not from
    CopyFromScreen, so an off-screen window can still be checked,
  * the idle gate and the pause/resume checkpoint live here too, because the three techniques above
    cannot see the user watching a video or reading a document ("no input" is not "not using the PC").

Nothing here starts the app by itself: the caller must pass the 30-minute idle gate first.
#>
Set-StrictMode -Version Latest

$script:MhQuietUiSource = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace MhQuietUi
{
    public static class Native
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        public struct STARTUPINFO
        {
            public int cb;
            public string lpReserved;
            public string lpDesktop;
            public string lpTitle;
            public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
            public short wShowWindow, cbReserved2;
            public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct PROCESS_INFORMATION
        {
            public IntPtr hProcess, hThread;
            public int dwProcessId, dwThreadId;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT { public int X, Y; }

        [StructLayout(LayoutKind.Sequential)]
        public struct SYSTEM_POWER_STATUS
        {
            public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
            public int BatteryLifeTime, BatteryFullLifeTime;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct LASTINPUTINFO { public uint cbSize, dwTime; }

        [StructLayout(LayoutKind.Sequential)]
        public struct KEYBDINPUT
        {
            public ushort wVk, wScan;
            public uint dwFlags, time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT
        {
            public int dx, dy;
            public uint mouseData, dwFlags, time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct INPUT
        {
            [FieldOffset(0)] public uint type;
            [FieldOffset(8)] public KEYBDINPUT ki;
            [FieldOffset(8)] public MOUSEINPUT mi;
        }

        public delegate bool EnumChildProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool CreateProcessW(string app, string cmd, IntPtr pa, IntPtr ta,
            bool inherit, uint flags, IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool CloseHandle(IntPtr h);

        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
        [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);

        [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumChildProc cb, IntPtr lParam);
        [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool CloseDesktop(IntPtr h);

        [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        public static extern bool WTSQuerySessionInformationW(IntPtr server, int sessionId, int infoClass,
            out IntPtr buffer, out int bytes);

        [DllImport("wtsapi32.dll")]
        public static extern void WTSFreeMemory(IntPtr p);

        [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS s);

        [DllImport("kernel32.dll")] public static extern uint GetTickCount();

        [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO info);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern uint SendInput(uint count, INPUT[] inputs, int size);

        [DllImport("user32.dll")]
        public static extern bool SetProcessDpiAwarenessContext(IntPtr value);

        public static void EnableDpiAwareness()
        {
            try { SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch { }
        }

        public static uint SendRelativeMouseMove(int dx, int dy)
        {
            var inputs = new INPUT[1];
            inputs[0].type = 0; // INPUT_MOUSE
            inputs[0].mi.dx = dx;
            inputs[0].mi.dy = dy;
            inputs[0].mi.dwFlags = 0x0001; // MOUSEEVENTF_MOVE
            return SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        public static string ClassName(IntPtr h)
        {
            var sb = new StringBuilder(256);
            GetClassName(h, sb, 256);
            return sb.ToString();
        }

        public static string WindowText(IntPtr h)
        {
            var sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            return sb.ToString();
        }

        public static List<IntPtr> Children(IntPtr parent)
        {
            var result = new List<IntPtr>();
            EnumChildWindows(parent, (h, l) => { result.Add(h); return true; }, IntPtr.Zero);
            return result;
        }

        private static volatile bool _keeperRun;
        private static Thread _keeperThread;
        public static long KeeperRestores;

        public static void StartFocusKeeper(int appPid, IntPtr target)
        {
            StopFocusKeeper();
            if (target == IntPtr.Zero) return;
            _keeperRun = true;
            KeeperRestores = 0;
            _keeperThread = new Thread(() =>
            {
                while (_keeperRun)
                {
                    IntPtr fg = GetForegroundWindow();
                    if (fg != IntPtr.Zero)
                    {
                        uint pid;
                        GetWindowThreadProcessId(fg, out pid);
                        if (pid == (uint)appPid)
                        {
                            SetForegroundWindow(target);
                            KeeperRestores++;
                        }
                    }
                    Thread.Sleep(50);
                }
            });
            _keeperThread.IsBackground = true;
            _keeperThread.Name = "mh-quiet-focus-keeper";
            _keeperThread.Start();
        }

        public static void StopFocusKeeper()
        {
            _keeperRun = false;
            if (_keeperThread != null)
            {
                try { _keeperThread.Join(500); } catch { }
                _keeperThread = null;
            }
        }
    }
}
'@

if (-not ('MhQuietUi.Native' -as [type])) {
    Add-Type -TypeDefinition $script:MhQuietUiSource -Language CSharp
}
# Window rectangles from user32 are physical pixels only in a DPI-aware process. Without this the
# off-screen 1280x800 window measures 853x533 and a PrintWindow capture is silently scaled.
[MhQuietUi.Native]::EnableDpiAwareness()
if (-not ('System.Windows.Automation.AutomationElement' -as [type])) {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
}
Add-Type -AssemblyName System.Drawing

$script:MhQuietUiInputDefined = $false
$script:MhQuietUiLastWatchLog = [datetime]::MinValue

function Write-MhQuietLog {
    [CmdletBinding()]
    param([string]$Message, [string]$Path)
    $line = (Get-Date).ToString('HH:mm:ss.fff') + ' ' + $Message
    Write-Host $line
    if ($Path) { Add-Content -Path $Path -Value $line -Encoding UTF8 -ErrorAction SilentlyContinue }
}

function Get-MhQuietSessionBlocker {
    <#
    .SYNOPSIS
    Returns the first state that makes an unattended GUI run dishonest: locked, disconnected/other
    user, or a clock jump consistent with sleep. $null means the session looks usable.
    #>
    [CmdletBinding()]
    param()

    # A locked workstation has no input desktop we can open. This is the same test Windows itself
    # uses to decide whether an app may show lock-screen UI.
    $desktop = [MhQuietUi.Native]::OpenInputDesktop(0, $false, 0x0001 -bor 0x0100)
    if ($desktop -eq [IntPtr]::Zero) {
        return [pscustomobject]@{ kind = 'locked'; detail = 'OpenInputDesktop failed err=' + [Runtime.InteropServices.Marshal]::GetLastWin32Error() }
    }
    [void][MhQuietUi.Native]::CloseDesktop($desktop)

    # WTS_CONNECTSTATE_CLASS.WTSActive = 0. A switched-away user has a Disconnected session, and a
    # disconnected session is exactly "another user is logged on".
    try {
        $buf = [IntPtr]::Zero
        $len = 0
        if ([MhQuietUi.Native]::WTSQuerySessionInformationW([IntPtr]::Zero, -1, 8, [ref]$buf, [ref]$len)) {
            try {
                $state = [Runtime.InteropServices.Marshal]::ReadInt32($buf)
                if ($state -ne 0) {
                    return [pscustomobject]@{ kind = 'session-not-active'; detail = 'WTSConnectState=' + $state }
                }
            } finally { [MhQuietUi.Native]::WTSFreeMemory($buf) }
        }
    } catch { }

    $power = New-Object MhQuietUi.Native+SYSTEM_POWER_STATUS
    if ([MhQuietUi.Native]::GetSystemPowerStatus([ref]$power)) {
        # SystemStatusFlag 1 is battery saver, not sleep. Sleep is inferred from a clock jump by the
        # caller, because Windows gives no "a sleep happened" query.
    }
    return $null
}

function Wait-MhQuietGate {
    <#
    .SYNOPSIS
    Waits until the machine has been free of input for -MinIdleSeconds and the session is usable.
    .DESCRIPTION
    Logs how long it has been waiting and why it is still waiting at least every -LogEverySeconds.
    Returns a result object; -TimeoutSeconds 0 means "wait forever".
    #>
    [CmdletBinding()]
    param(
        [int]$MinIdleSeconds = 1800,
        [int]$PollSeconds = 5,
        [int]$TimeoutSeconds = 0,
        [int]$LogEverySeconds = 30,
        [string]$LogPath
    )

    $start = Get-Date
    $deadline = if ($TimeoutSeconds -gt 0) { $start.AddSeconds($TimeoutSeconds) } else { [datetime]::MaxValue }
    $nextLog = $start
    $lastTick = [Environment]::TickCount64
    while ($true) {
        $now = Get-Date
        $gapMs = [Environment]::TickCount64 - $lastTick
        $lastTick = [Environment]::TickCount64

        $blocker = Get-MhQuietSessionBlocker
        # A wall-clock gap much larger than the poll interval means the process was suspended
        # (sleep/hibernate). Input idle time cannot be trusted across that boundary, so the wait
        # restarts instead of pretending the gap was idle time.
        if ($gapMs -gt (($PollSeconds + 20) * 1000)) {
            $blocker = [pscustomobject]@{ kind = 'sleep-gap'; detail = 'gap=' + [int]($gapMs / 1000) + 's' }
        }

        $idle = Get-MhUserIdleSeconds
        if ($null -ne $idle -and $idle -ge $MinIdleSeconds -and $null -eq $blocker) {
            $waited = [int]((Get-Date) - $start).TotalSeconds
            Write-MhQuietLog ("gate: ready idle=" + $idle + "s need=" + $MinIdleSeconds + "s waited=" + $waited + "s") $LogPath
            return [pscustomobject]@{ Ready = $true; IdleSeconds = $idle; WaitedSeconds = $waited; Blocker = $null }
        }

        if ($now -ge $nextLog) {
            $need = if ($null -ne $idle) { [Math]::Max(0, $MinIdleSeconds - $idle) } else { -1 }
            $why = if ($blocker) { $blocker.kind + ' (' + $blocker.detail + ')' } else { 'user active' }
            Write-MhQuietLog ("gate: waiting idle=" + $idle + "s need=" + $MinIdleSeconds + "s remaining~" + $need + "s reason=" + $why) $LogPath
            $nextLog = $now.AddSeconds($LogEverySeconds)
        }
        if ((Get-Date) -ge $deadline) {
            return [pscustomobject]@{ Ready = $false; IdleSeconds = $idle; WaitedSeconds = [int]((Get-Date) - $start).TotalSeconds; Blocker = $blocker }
        }
        Start-Sleep -Seconds $PollSeconds
    }
}

function Start-MhQuietUiWatch {
    <#
    .SYNOPSIS
    Watches a running test and aborts it the moment the user touches the machine.
    .DESCRIPTION
    Process-scoped: only -ProcessId is stopped, so a concurrent acceptance run owned by someone else
    is left alone. Writes "<FlagPath>.abort" with the reason ("user-active", "locked", ...).
    #>
    [CmdletBinding()]
    param(
        [int]$ProcessId = 0,
        [int]$MinIdleSeconds = 1800,
        [int]$PollMilliseconds = 1000,
        [string]$FlagPath,
        [string]$RootPidFile = ''
    )

    if (-not $FlagPath) { $FlagPath = Join-Path ([IO.Path]::GetTempPath()) ('mh-quiet-ui-' + [guid]::NewGuid().ToString('n')) }
    $typeSource = $script:MhQuietUiSource
    $job = Start-Job -ScriptBlock {
        param($ProcessId, $MinIdleSeconds, $PollMilliseconds, $FlagPath, $TypeSource, $RootPidFile)
        if (-not ('MhQuietUi.Native' -as [type])) { Add-Type -TypeDefinition $TypeSource -Language CSharp }
        $stopFile = $FlagPath + '.stop'
        $abortFile = $FlagPath + '.abort'
        $last = [Environment]::TickCount64
        while (-not (Test-Path $stopFile)) {
            Start-Sleep -Milliseconds $PollMilliseconds
            $now = [Environment]::TickCount64
            $gap = $now - $last
            $last = $now
            if ($gap -gt (($PollMilliseconds / 1000 + 20) * 1000)) {
                $reason = 'sleep-gap ' + [int]($gap / 1000) + 's'
            } else {
                $info = New-Object MhQuietUi.Native+LASTINPUTINFO
                $info.cbSize = [uint32][Runtime.InteropServices.Marshal]::SizeOf($info)
                if (-not [MhQuietUi.Native]::GetLastInputInfo([ref]$info)) { continue }
                $tick = [uint64][MhQuietUi.Native]::GetTickCount()
                $lastInput = [uint64]$info.dwTime
                $delta = if ($tick -ge $lastInput) { $tick - $lastInput } else { (0x100000000 - $lastInput) + $tick }
                $idle = [int]($delta / 1000)
                $desktop = [MhQuietUi.Native]::OpenInputDesktop(0, $false, 0x0001 -bor 0x0100)
                if ($desktop -eq [IntPtr]::Zero) { $reason = 'locked' }
                else {
                    [void][MhQuietUi.Native]::CloseDesktop($desktop)
                    $reason = if ($idle -lt $MinIdleSeconds) { 'user-active idle=' + $idle + 's' } else { $null }
                }
                if (-not $reason) {
                    $buf = [IntPtr]::Zero; $len = 0
                    if ([MhQuietUi.Native]::WTSQuerySessionInformationW([IntPtr]::Zero, -1, 8, [ref]$buf, [ref]$len)) {
                        try { if ([Runtime.InteropServices.Marshal]::ReadInt32($buf) -ne 0) { $reason = 'session-not-active' } }
                        finally { [MhQuietUi.Native]::WTSFreeMemory($buf) }
                    }
                }
            }
            if ($reason) {
                # Publish the abort first: the driver polls this file and starts its own cleanup,
                # so the app comes down while this job is still enumerating child processes.
                Set-Content -Path $abortFile -Value ($reason + ' at ' + (Get-Date).ToString('o')) -Encoding UTF8
                # Stop the suite process tree, not every process with the same name: a concurrent
                # acceptance run owned by someone else must not be killed by this watchdog.
                if ($RootPidFile -and (Test-Path $RootPidFile)) {
                    $rootId = 0
                    try { $rootId = [int]((Get-Content -Path $RootPidFile -Raw).Trim()) } catch { }
                    if ($rootId -gt 0) {
                        # The root goes first so the suite stops immediately; its children keep the
                        # creator pid in Win32_Process, so they are still found below.
                        Stop-Process -Id $rootId -Force -ErrorAction SilentlyContinue
                        try {
                            $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
                            $kill = New-Object 'System.Collections.Generic.List[int]'
                            $queue = New-Object 'System.Collections.Generic.Queue[int]'
                            $queue.Enqueue($rootId)
                            $seen = 0
                            while ($queue.Count -gt 0 -and $seen -lt 256) {
                                $seen++
                                $parentId = $queue.Dequeue()
                                foreach ($p in $all) {
                                    if ([int]$p.ParentProcessId -eq $parentId) {
                                        $queue.Enqueue([int]$p.ProcessId)
                                        $kill.Add([int]$p.ProcessId)
                                    }
                                }
                            }
                            foreach ($k in @($kill | Sort-Object -Descending)) {
                                Stop-Process -Id $k -Force -ErrorAction SilentlyContinue
                            }
                        } catch { }
                    }
                }
                if ($ProcessId -gt 0) {
                    Get-Process -Id $ProcessId -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                }
                break
            }
        }
    } -ArgumentList @($ProcessId, $MinIdleSeconds, $PollMilliseconds, $FlagPath, $typeSource, $RootPidFile)

    return [pscustomobject]@{
        Job = $job
        FlagPath = $FlagPath
        RootPidFile = $RootPidFile
        AbortFile = $FlagPath + '.abort'
        StopFile = $FlagPath + '.stop'
    }
}

function Stop-MhQuietUiWatch {
    [CmdletBinding()]
    param($Watch)
    if ($null -eq $Watch) { return }
    Set-Content -Path $Watch.StopFile -Value 'stop' -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($Watch.Job) {
        [void](Wait-Job -Job $Watch.Job -Timeout 8)
        Remove-Job -Job $Watch.Job -Force -ErrorAction SilentlyContinue
    }
}

function Test-MhQuietUiAborted {
    [CmdletBinding()] param($Watch)
    return ($null -ne $Watch -and (Test-Path $Watch.AbortFile))
}

function Get-MhQuietUiAbortReason {
    [CmdletBinding()] param($Watch)
    if ($null -eq $Watch -or -not (Test-Path $Watch.AbortFile)) { return '' }
    return (Get-Content -Path $Watch.AbortFile -Raw -ErrorAction SilentlyContinue).Trim()
}

function Get-MhQuietSnapshot {
    <# Foreground window, its owning pid/title, and cursor position. Used to prove "nothing moved". #>
    [CmdletBinding()]
    param()
    $fg = [MhQuietUi.Native]::GetForegroundWindow()
    $ownerPid = [uint32]0
    if ($fg -ne [IntPtr]::Zero) { [void][MhQuietUi.Native]::GetWindowThreadProcessId($fg, [ref]$ownerPid) }
    $p = New-Object MhQuietUi.Native+POINT
    [void][MhQuietUi.Native]::GetCursorPos([ref]$p)
    [pscustomobject]@{
        foreground = ('0x' + $fg.ToInt64().ToString('X'))
        foregroundHandle = $fg.ToInt64()
        foregroundPid = [int]$ownerPid
        foregroundTitle = if ($fg -ne [IntPtr]::Zero) { [MhQuietUi.Native]::WindowText($fg) } else { '' }
        foregroundClass = if ($fg -ne [IntPtr]::Zero) { [MhQuietUi.Native]::ClassName($fg) } else { '' }
        cursorX = $p.X
        cursorY = $p.Y
        idleSeconds = Get-MhUserIdleSeconds
    }
}

function Start-MhQuietFocusKeeper {
    <#
    .SYNOPSIS
    Keeps the desktop's foreground window in place while UIA actions run.
    .DESCRIPTION
    WinUI automation peers activate the window when a Toggle/Value/Range pattern is invoked. A
    thread inside this process checks every 50 ms and hands the foreground back, so a re-render
    cannot leave the keyboard on the off-screen test window. It only acts when the app owns the
    foreground; a real user switching apps is never fought.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App)
    $target = if ($App.baselineForegroundHandle) { [IntPtr][long]$App.baselineForegroundHandle } else { [IntPtr]::Zero }
    [MhQuietUi.Native]::StartFocusKeeper($App.pid, $target)
    return $target
}

function Stop-MhQuietFocusKeeper {
    [CmdletBinding()]
    param()
    $count = [MhQuietUi.Native]::KeeperRestores
    [MhQuietUi.Native]::StopFocusKeeper()
    return $count
}

function Start-MhQuietApp {
    <#
    .SYNOPSIS
    Starts the client with its window off-screen and not activated.
    .DESCRIPTION
    CreateProcess is used directly so the process survives this shell, and the two MEDIAHUB_QUIET_*
    variables are inherited by the child only. The window is placed outside the virtual desktop so
    it cannot cover anything, then shown with SW_SHOWNOACTIVATE from the product code. The result
    records whether the foreground moved; if it did, the previous window is restored immediately and
    ForegroundPreserved is false (a failed quiet start, not a silent success).
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Exe,
        [string]$Rect = '3200,200,1280,800',
        [int]$TimeoutSeconds = 60,
        [string[]]$Arguments = @()
    )
    if (-not (Test-Path $Exe)) { throw "app not found: $Exe" }
    $before = Get-MhQuietSnapshot

    $si = New-Object MhQuietUi.Native+STARTUPINFO
    $si.cb = [Runtime.InteropServices.Marshal]::SizeOf([type][MhQuietUi.Native+STARTUPINFO])
    $pi = New-Object MhQuietUi.Native+PROCESS_INFORMATION
    $cmd = '"' + $Exe + '"'
    if ($Arguments.Count -gt 0) { $cmd += ' ' + (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' ') }
    $savedStart = $env:MEDIAHUB_QUIET_START
    $savedRect = $env:MEDIAHUB_QUIET_RECT
    $env:MEDIAHUB_QUIET_START = '1'
    $env:MEDIAHUB_QUIET_RECT = $Rect
    try {
        $ok = [MhQuietUi.Native]::CreateProcessW($Exe, $cmd, [IntPtr]::Zero, [IntPtr]::Zero, $false, 0,
            [IntPtr]::Zero, (Split-Path $Exe -Parent), [ref]$si, [ref]$pi)
    } finally {
        $env:MEDIAHUB_QUIET_START = $savedStart
        $env:MEDIAHUB_QUIET_RECT = $savedRect
    }
    if (-not $ok) { throw ('CreateProcess failed: ' + [Runtime.InteropServices.Marshal]::GetLastWin32Error()) }
    [void][MhQuietUi.Native]::CloseHandle($pi.hThread)
    [void][MhQuietUi.Native]::CloseHandle($pi.hProcess)
    $appPid = $pi.dwProcessId

    $hwnd = [IntPtr]::Zero
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 200
        try {
            $proc = Get-Process -Id $appPid -ErrorAction Stop
            if ($proc.MainWindowHandle -ne 0) { $hwnd = [IntPtr]$proc.MainWindowHandle; break }
        } catch { break }
    }
    if ($hwnd -eq [IntPtr]::Zero) {
        if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) { Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue }
        throw "the app did not create a window within $TimeoutSeconds s (pid $appPid)"
    }

    # The product code shows the window without activation. Verify rather than trust it: if the
    # foreground did move, hand it back at once and report a failed quiet start.
    $afterShow = Get-MhQuietSnapshot
    $foregroundPreserved = -not ($afterShow.foregroundPid -eq $appPid)
    if (-not $foregroundPreserved -and $before.foregroundHandle -ne 0) {
        $prev = [IntPtr]$before.foregroundHandle
        [void][MhQuietUi.Native]::SetForegroundWindow($prev)
    }
    # From here on, every automation pattern that activates the window gets the desktop back
    # within ~50 ms, without waiting for the test step to notice.
    [MhQuietUi.Native]::StartFocusKeeper($appPid, [IntPtr]$before.foregroundHandle)

    $rectValue = New-Object MhQuietUi.Native+RECT
    [void][MhQuietUi.Native]::GetWindowRect($hwnd, [ref]$rectValue)
    [pscustomobject]@{
        pid = $appPid
        hwnd = $hwnd
        hwndHex = '0x' + $hwnd.ToInt64().ToString('X')
        before = $before
        after = $afterShow
        foregroundPreserved = $foregroundPreserved
        baselineForegroundHandle = $before.foregroundHandle
        baselineForegroundTitle = $before.foregroundTitle
        rect = "$($rectValue.Left),$($rectValue.Top) $($rectValue.Right - $rectValue.Left)x$($rectValue.Bottom - $rectValue.Top)"
        visible = [MhQuietUi.Native]::IsWindowVisible($hwnd)
        iconic = [MhQuietUi.Native]::IsIconic($hwnd)
    }
}

function Stop-MhQuietApp {
    [CmdletBinding()]
    param($App, [int]$TimeoutSeconds = 10)
    if ($null -eq $App) { return 'no-app' }
    [MhQuietUi.Native]::StopFocusKeeper()
    $proc = Get-Process -Id $App.pid -ErrorAction SilentlyContinue
    if (-not $proc) { return 'already-exited' }
    Stop-Process -Id $App.pid -Force -ErrorAction SilentlyContinue
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (-not (Get-Process -Id $App.pid -ErrorAction SilentlyContinue)) { return 'stopped' }
        Start-Sleep -Milliseconds 200
    }
    return 'still-running'
}

function Get-MhQuietRoots {
    <# The main window plus every other top-level window owned by this pid (dialogs, combo popups). #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App)
    $roots = @()
    try { $roots += [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$App.hwnd) } catch { }
    try {
        $top = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
            [System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($t in $top) {
            if ($t.Current.ProcessId -ne $App.pid) { continue }
            if ($t.Current.NativeWindowHandle -eq ([IntPtr]$App.hwnd).ToInt64()) { continue }
            $roots += $t
        }
    } catch { }
    return $roots
}

function Get-MhQuietElementAll {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App)
    $out = @()
    $seen = @{}
    foreach ($root in (Get-MhQuietRoots -App $App)) {
        try {
            $found = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                [System.Windows.Automation.Condition]::TrueCondition)
        } catch { continue }
        foreach ($e in $found) {
            $key = ($e.GetRuntimeId() -join '.')
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = $true
            $out += $e
        }
    }
    return $out
}

function Find-MhQuietElement {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$App,
        [string]$AutomationId,
        [string]$Name,
        [string]$Contains,
        [string]$ControlType,
        [int]$Index = 0
    )
    $matches = @()
    foreach ($e in (Get-MhQuietElementAll -App $App)) {
        $c = $e.Current
        if ($ControlType -and $c.ControlType.ProgrammaticName.Replace('ControlType.', '') -ne $ControlType) { continue }
        if ($AutomationId -and $c.AutomationId -ne $AutomationId) { continue }
        if ($Name -and $c.Name -ne $Name) { continue }
        if ($Contains -and -not ($c.Name -like ('*' + $Contains + '*'))) { continue }
        $matches += $e
    }
    if ($matches.Count -le $Index) { return $null }
    return $matches[$Index]
}

function Invoke-MhQuietElement {
    [CmdletBinding()]
    param($Element)
    if (-not $Element) { return 'NOT_FOUND' }
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) { $pattern.Invoke(); return 'INVOKED' }
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) { $pattern.Select(); return 'SELECTED' }
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) { $pattern.Toggle(); return 'TOGGLED' }
    return 'NO_PATTERN'
}

function Invoke-MhQuietElementDeep {
    [CmdletBinding()]
    param($Element, [int]$MaxDepth = 5)
    if (-not $Element) { return 'NOT_FOUND' }
    $cur = $Element
    for ($d = 0; $d -le $MaxDepth; $d++) {
        $r = Invoke-MhQuietElement -Element $cur
        if ($r -ne 'NO_PATTERN' -and $r -ne 'NOT_FOUND') { return ($r + '(depth=' + $d + ')') }
        try { $cur = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($cur) } catch { break }
        if (-not $cur) { break }
    }
    return 'NO_PATTERN'
}

function Set-MhQuietValue {
    [CmdletBinding()]
    param($Element, [string]$Value)
    if (-not $Element) { return 'NOT_FOUND' }
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
        $pattern.SetValue($Value); return 'SET'
    }
    return 'NO_VALUEPATTERN'
}

function Get-MhQuietValue {
    [CmdletBinding()]
    param($Element)
    if (-not $Element) { return $null }
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) { return $pattern.Current.Value }
    return $null
}

function Set-MhQuietRange {
    [CmdletBinding()]
    param($Element, [double]$Value)
    if (-not $Element) { return 'NOT_FOUND' }
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) {
        $pattern.SetValue($Value); return 'SET'
    }
    return 'NO_RANGEPATTERN'
}

function Get-MhQuietRange {
    [CmdletBinding()]
    param($Element)
    if (-not $Element) { return $null }
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) {
        return [pscustomobject]@{ value = $pattern.Current.Value; min = $pattern.Current.Minimum; max = $pattern.Current.Maximum }
    }
    return $null
}

function Select-MhQuietComboItem {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App, $Combo, [string]$Text)
    if (-not $Combo) { return 'COMBO_NOT_FOUND' }
    $expand = $null
    if (-not $Combo.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$expand)) { return 'NO_EXPAND' }
    $expand.Expand()
    Start-Sleep -Milliseconds 600
    $item = Find-MhQuietElement -App $App -ControlType 'ListItem' -Name $Text
    if (-not $item) { $item = Find-MhQuietElement -App $App -ControlType 'ListItem' -Contains $Text }
    if (-not $item) {
        # Some flyouts publish the row as a plain ListItem under a Popup window that is not part of
        # our roots yet; refresh the roots and try once more before giving up.
        Start-Sleep -Milliseconds 300
        $item = Find-MhQuietElement -App $App -ControlType 'ListItem' -Contains $Text
    }
    if (-not $item) { $expand.Collapse(); return 'ITEM_NOT_FOUND' }
    $r = Invoke-MhQuietElement -Element $item
    Start-Sleep -Milliseconds 400
    $sel = $null
    $verified = 'UNVERIFIED'
    if ($Combo.TryGetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern, [ref]$sel)) {
        $names = @($sel.Current.GetSelection() | ForEach-Object { $_.Current.Name })
        $verified = if ($names -contains $Text) { 'VERIFIED' } else { 'NOT_SELECTED:' + ($names -join ',') }
    }
    $expand.Collapse()
    Start-Sleep -Milliseconds 200
    return "$r/$verified"
}

function Restore-MhQuietForeground {
    <#
    .SYNOPSIS
    Puts the foreground back and keeps putting it back until the app stops reclaiming it.
    .DESCRIPTION
    A single SetForegroundWindow is not enough for Value/Range patterns: WinUI queues another focus
    request after the automation call returns, which re-activates the window roughly 100-300 ms
    later. The keeper holds the user's window for -HoldMilliseconds and re-asserts every 100 ms, so
    a re-render cannot quietly take the keyboard away after the check already passed.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App, [long]$BeforeHandle, [int]$HoldMilliseconds = 3000)
    if ($BeforeHandle -eq 0) { return $false }
    $target = [IntPtr]$BeforeHandle
    $deadline = (Get-Date).AddMilliseconds($HoldMilliseconds)
    $reassertions = 0
    while ((Get-Date) -lt $deadline) {
        $snap = Get-MhQuietSnapshot
        if ($snap.foregroundPid -eq $App.pid) {
            [void][MhQuietUi.Native]::SetForegroundWindow($target)
            $reassertions++
        }
        Start-Sleep -Milliseconds 100
    }
    $final = Get-MhQuietSnapshot
    return [pscustomobject]@{
        restored = ($final.foregroundPid -ne $App.pid)
        reassertions = $reassertions
        foreground = $final.foregroundTitle
    }
}

function Invoke-MhQuietGuarded {
    <#
    .SYNOPSIS
    Runs one UIA action and immediately hands the foreground back if WinUI moved it to the app.
    .DESCRIPTION
    WinUI's automation peers for SelectionItem/Toggle/Value call SetFocus on the control, so even a
    non-activated, off-screen window can become the keyboard focus owner for a few milliseconds.
    The idle gate exists so nobody is typing at that moment; this function makes sure the desktop is
    left exactly as it was found, and records that a transient steal happened.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$App, [Parameter(Mandatory)][scriptblock]$Action, [string]$LogPath)
    $before = Get-MhQuietSnapshot
    $result = & $Action
    Start-Sleep -Milliseconds 120
    $after = Get-MhQuietSnapshot
    $stolen = ($after.foregroundPid -eq $App.pid)
    $restored = $false
    if ($stolen) {
        # Restore to the desktop we found when the app started. Using the previous step's foreground
        # would restore to the app itself once one step has already failed to hand it back.
        $target = if ($App.baselineForegroundHandle) { [long]$App.baselineForegroundHandle } else { $before.foregroundHandle }
        $targetTitle = if ($App.baselineForegroundTitle) { $App.baselineForegroundTitle } else { $before.foregroundTitle }
        $restore = Restore-MhQuietForeground -App $App -BeforeHandle $target -HoldMilliseconds 3000
        $restored = $restore.restored
        Write-MhQuietLog ("focus: UIA moved foreground to app; restored=" + $restored + " reassertions=" + $restore.reassertions + " to '" + $targetTitle + "'") $LogPath
    }
    [pscustomobject]@{
        result = $result
        focusStolen = $stolen
        focusRestored = $restored
        foregroundBefore = $before.foregroundTitle
        foregroundAfter = (Get-MhQuietSnapshot).foregroundTitle
    }
}

function Get-MhQuietChildWindows {
    <#
    .SYNOPSIS
    Child HWNDs under a window, deepest last. The engine's video surface is a child window, so this
    is how a capture can target the pixels of the video alone.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][IntPtr]$Parent)
    $out = @()
    foreach ($h in [MhQuietUi.Native]::Children($Parent)) {
        $r = New-Object MhQuietUi.Native+RECT
        [void][MhQuietUi.Native]::GetWindowRect($h, [ref]$r)
        $out += [pscustomobject]@{
            hwnd = $h
            hwndHex = '0x' + $h.ToInt64().ToString('X')
            className = [MhQuietUi.Native]::ClassName($h)
            title = [MhQuietUi.Native]::WindowText($h)
            visible = [MhQuietUi.Native]::IsWindowVisible($h)
            rect = "$($r.Left),$($r.Top) $($r.Right - $r.Left)x$($r.Bottom - $r.Top)"
            area = [Math]::Max(0, $r.Right - $r.Left) * [Math]::Max(0, $r.Bottom - $r.Top)
        }
    }
    return $out
}

function Get-MhQuietImageStats {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    $bmp = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $w = $bmp.Width; $h = $bmp.Height
        $stepX = [Math]::Max(1, [int]($w / 160))
        $stepY = [Math]::Max(1, [int]($h / 90))
        $samples = 0; $nonBlack = 0; $bright = 0; $sum = 0.0
        $colors = New-Object 'System.Collections.Generic.HashSet[int]'
        $bandTop = [int]($h * 0.72)   # subtitle band: the lower quarter of the frame
        $bandBright = 0; $bandSamples = 0; $bandNeutral = 0
        for ($y = 0; $y -lt $h; $y += $stepY) {
            for ($x = 0; $x -lt $w; $x += $stepX) {
                $c = $bmp.GetPixel($x, $y)
                $lum = 0.2126 * $c.R + 0.7152 * $c.G + 0.0722 * $c.B
                $samples++; $sum += $lum
                if ($c.R + $c.G + $c.B -gt 18) { $nonBlack++ }
                if ($lum -gt 200) { $bright++ }
                [void]$colors.Add($c.ToArgb())
                if ($y -ge $bandTop) {
                    $bandSamples++
                    if ($lum -gt 180) { $bandBright++ }
                    # Subtitles are near-white and desaturated. A colour bar can be bright, so
                    # brightness alone is not evidence of text; this counts only low-chroma pixels.
                    $hi = [Math]::Max($c.R, [Math]::Max($c.G, $c.B))
                    $lo = [Math]::Min($c.R, [Math]::Min($c.G, $c.B))
                    if ($lum -gt 190 -and ($hi - $lo) -lt 30) { $bandNeutral++ }
                }
            }
        }
        [pscustomobject]@{
            path = $Path
            width = $w
            height = $h
            samples = $samples
            nonBlackRatio = [Math]::Round($nonBlack / [double][Math]::Max(1, $samples), 4)
            uniqueColors = $colors.Count
            meanLuma = [Math]::Round($sum / [double][Math]::Max(1, $samples), 1)
            brightRatio = [Math]::Round($bright / [double][Math]::Max(1, $samples), 4)
            subtitleBandBrightRatio = [Math]::Round($bandBright / [double][Math]::Max(1, $bandSamples), 4)
            subtitleBandNeutralRatio = [Math]::Round($bandNeutral / [double][Math]::Max(1, $bandSamples), 4)
            subtitleBandSamples = $bandSamples
        }
    } finally { $bmp.Dispose() }
}

function Save-MhQuietWindowCapture {
    <#
    .SYNOPSIS
    PrintWindow(PW_RENDERFULLCONTENT) of one HWND, saved as PNG plus pixel statistics.
    .DESCRIPTION
    Works on an off-screen window because it asks the window to render into a DC instead of reading
    the screen. A black or empty result is reported by the statistics, not assumed to be a pass.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][IntPtr]$Hwnd,
        [Parameter(Mandatory)][string]$Path,
        [uint32]$Flags = 2
    )
    $r = New-Object MhQuietUi.Native+RECT
    [void][MhQuietUi.Native]::GetWindowRect($Hwnd, [ref]$r)
    $w = [Math]::Max(1, $r.Right - $r.Left)
    $h = [Math]::Max(1, $r.Bottom - $r.Top)
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    try {
        $ok = [MhQuietUi.Native]::PrintWindow($Hwnd, $hdc, $Flags)
    } finally {
        $g.ReleaseHdc($hdc)
        $g.Dispose()
    }
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $stats = Get-MhQuietImageStats -Path $Path
    Add-Member -InputObject $stats -NotePropertyName printWindowReturned -NotePropertyValue ([bool]$ok) -PassThru
}

function Send-MhQuietSyntheticInput {
    <#
    .SYNOPSIS
    Injects one harmless input event for the idle-gate self-check.
    .DESCRIPTION
    A relative mouse move of (0,0) updates GetLastInputInfo without moving the pointer. If the input
    queue ignores it, a 1-pixel move is used and the cursor is put back, so the self-check is still
    honest about what it injected.
    #>
    [CmdletBinding()]
    param()
    $before = Get-MhQuietSnapshot
    $sent = [MhQuietUi.Native]::SendRelativeMouseMove(0, 0)
    Start-Sleep -Milliseconds 250
    $after = Get-MhQuietSnapshot
    $mode = 'zero-move'
    $beforeIdle = if ($null -eq $before.idleSeconds) { 0 } else { $before.idleSeconds }
    $afterIdle = if ($null -eq $after.idleSeconds) { 0 } else { $after.idleSeconds }
    if ($afterIdle -gt 1 -and $afterIdle -ge $beforeIdle) {
        $mode = 'one-pixel'
        $sent = [MhQuietUi.Native]::SendRelativeMouseMove(1, 0)
        Start-Sleep -Milliseconds 200
        [void][MhQuietUi.Native]::SetCursorPos($before.cursorX, $before.cursorY)
        $after = Get-MhQuietSnapshot
    }
    [pscustomobject]@{
        sent = $sent
        mode = $mode
        idleBefore = $before.idleSeconds
        idleAfter = $after.idleSeconds
        cursorBefore = "$($before.cursorX),$($before.cursorY)"
        cursorAfter = "$($after.cursorX),$($after.cursorY)"
    }
}

function Read-MhQuietCheckpoint {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path $Path)) { return [pscustomobject]@{ runId = $null; completed = @(); results = @{} } }
    try {
        $raw = Get-Content -Path $Path -Raw -Encoding UTF8
        return ($raw | ConvertFrom-Json)
    } catch {
        return [pscustomobject]@{ runId = $null; completed = @(); results = @{} }
    }
}

function Save-MhQuietCheckpoint {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Checkpoint)
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Checkpoint | ConvertTo-Json -Depth 12 | Set-Content -Path $Path -Encoding UTF8
}

Export-ModuleMember -Function Get-MhQuietSessionBlocker, Wait-MhQuietGate, Start-MhQuietUiWatch, Stop-MhQuietUiWatch,
    Test-MhQuietUiAborted, Get-MhQuietUiAbortReason, Get-MhQuietSnapshot, Start-MhQuietApp, Stop-MhQuietApp,
    Get-MhQuietRoots, Get-MhQuietElementAll, Find-MhQuietElement, Invoke-MhQuietElement, Invoke-MhQuietElementDeep,
    Invoke-MhQuietGuarded, Restore-MhQuietForeground, Start-MhQuietFocusKeeper, Stop-MhQuietFocusKeeper,
    Set-MhQuietValue, Get-MhQuietValue, Set-MhQuietRange, Get-MhQuietRange, Select-MhQuietComboItem,
    Get-MhQuietChildWindows, Save-MhQuietWindowCapture, Get-MhQuietImageStats, Send-MhQuietSyntheticInput,
    Read-MhQuietCheckpoint, Save-MhQuietCheckpoint, Write-MhQuietLog

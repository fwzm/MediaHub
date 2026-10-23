#Requires -Version 7
<#
.SYNOPSIS
Quiet-mode helpers: keep interactive GUI acceptance from disturbing the person using this machine.

.DESCRIPTION
GUI acceptance needs a real interactive session (UIA cannot see a window on another desktop and a
locked session has no window to find), so the only honest way to stay out of the user's way is:

  1. never start while the user is at the keyboard,
  2. abort within about a second if they come back,
  3. leave nothing running behind (app processes are killed, servers stopped by the caller).

Idle time comes from GetLastInputInfo, the same source Windows uses for screen savers, so "idle"
means "no keyboard, no mouse, no touch" rather than "no window focused".
#>
Set-StrictMode -Version Latest

$script:MhQuietTypeSource = @'
using System;
using System.Runtime.InteropServices;

namespace MhQuiet
{
    public static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct LASTINPUTINFO
        {
            public uint cbSize;
            public uint dwTime;
        }

        [DllImport("user32.dll")]
        public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

        [DllImport("kernel32.dll")]
        public static extern uint GetTickCount();
    }
}
'@

if (-not ('MhQuiet.Native' -as [type])) {
    Add-Type -TypeDefinition $script:MhQuietTypeSource -Language CSharp
}

function Get-MhUserIdleSeconds {
    <#
    .SYNOPSIS Seconds since the last keyboard/mouse/touch input. $null when the query fails.
    #>
    [CmdletBinding()]
    param()

    $info = New-Object MhQuiet.Native+LASTINPUTINFO
    $info.cbSize = [uint32][System.Runtime.InteropServices.Marshal]::SizeOf($info)
    if (-not [MhQuiet.Native]::GetLastInputInfo([ref]$info)) { return $null }

    $tick = [uint64][MhQuiet.Native]::GetTickCount()
    $last = [uint64]$info.dwTime
    $delta = if ($tick -ge $last) { $tick - $last } else { (0x100000000 - $last) + $tick }
    return [int]($delta / 1000)
}

function Wait-MhUserIdle {
    <#
    .SYNOPSIS Waits until the user has been away for -MinIdleSeconds. Returns $true when idle.
    .PARAMETER TimeoutSeconds 0 means "check once and return immediately".
    #>
    [CmdletBinding()]
    param(
        [int]$MinIdleSeconds = 180,
        [int]$TimeoutSeconds = 0,
        [int]$PollSeconds = 5
    )

    $deadline = (Get-Date).AddSeconds([Math]::Max(0, $TimeoutSeconds))
    while ($true) {
        $idle = Get-MhUserIdleSeconds
        if ($null -ne $idle -and $idle -ge $MinIdleSeconds) { return $true }
        if ((Get-Date) -ge $deadline) { return $false }
        Start-Sleep -Seconds $PollSeconds
    }
}

function Start-MhQuietWatch {
    <#
    .SYNOPSIS Watches for the user coming back and stops the run when they do.
    .DESCRIPTION
    Runs in a background job. When input is seen it kills the named processes (the app under test) and
    writes "<FlagPath>.abort", so the caller can tell "aborted by user" apart from "assertion failed".
    The job exits when "<FlagPath>.stop" appears (Stop-MhQuietWatch writes it).
    #>
    [CmdletBinding()]
    param(
        [int]$MinIdleSeconds = 180,
        [int]$PollMilliseconds = 700,
        [string]$FlagPath,
        [string[]]$KillProcessNames = @('MediaHub'),
        [switch]$DryRun
    )

    if (-not $FlagPath) { $FlagPath = Join-Path ([System.IO.Path]::GetTempPath()) ('mh-quiet-' + [guid]::NewGuid().ToString('n')) }

    $job = Start-Job -ScriptBlock {
        param($MinIdleSeconds, $PollMilliseconds, $FlagPath, $KillProcessNames, $DryRun, $TypeSource)

        if (-not ('MhQuiet.Native' -as [type])) { Add-Type -TypeDefinition $TypeSource -Language CSharp }

        $stopFile = $FlagPath + '.stop'
        $abortFile = $FlagPath + '.abort'
        while (-not (Test-Path $stopFile)) {
            $info = New-Object MhQuiet.Native+LASTINPUTINFO
            $info.cbSize = [uint32][System.Runtime.InteropServices.Marshal]::SizeOf($info)
            if (-not [MhQuiet.Native]::GetLastInputInfo([ref]$info)) {
                Start-Sleep -Milliseconds $PollMilliseconds
                continue
            }
            $tick = [uint64][MhQuiet.Native]::GetTickCount()
            $last = [uint64]$info.dwTime
            $delta = if ($tick -ge $last) { $tick - $last } else { (0x100000000 - $last) + $tick }
            $idle = [int]($delta / 1000)

            if ($idle -lt $MinIdleSeconds) {
                if (-not $DryRun) {
                    foreach ($name in $KillProcessNames) {
                        Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                    }
                }
                $line = 'user-active idle=' + $idle + ' threshold=' + $MinIdleSeconds + ' at ' + (Get-Date).ToString('o') + ' dryRun=' + [bool]$DryRun
                Set-Content -Path $abortFile -Value $line -Encoding UTF8
                break
            }
            Start-Sleep -Milliseconds $PollMilliseconds
        }
    } -ArgumentList $MinIdleSeconds, $PollMilliseconds, $FlagPath, $KillProcessNames, [bool]$DryRun, $script:MhQuietTypeSource

    return [pscustomobject]@{
        Job      = $job
        FlagPath = $FlagPath
        AbortFile = $FlagPath + '.abort'
        StopFile = $FlagPath + '.stop'
    }
}

function Stop-MhQuietWatch {
    [CmdletBinding()]
    param($Watch)

    if ($null -eq $Watch) { return }
    Set-Content -Path $Watch.StopFile -Value 'stop' -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($Watch.Job) {
        [void](Wait-Job -Job $Watch.Job -Timeout 10)
        Remove-Job -Job $Watch.Job -Force -ErrorAction SilentlyContinue
    }
}

function Test-MhQuietAborted {
    <#
    .SYNOPSIS $true when the watchdog recorded the user coming back.
    #>
    [CmdletBinding()]
    param($Watch)

    if ($null -eq $Watch) { return $false }
    return (Test-Path $Watch.AbortFile)
}

function Get-MhQuietAbortReason {
    [CmdletBinding()]
    param($Watch)

    if ($null -eq $Watch -or -not (Test-Path $Watch.AbortFile)) { return '' }
    return (Get-Content -Path $Watch.AbortFile -Raw -ErrorAction SilentlyContinue).Trim()
}

Export-ModuleMember -Function Get-MhUserIdleSeconds, Wait-MhUserIdle, Start-MhQuietWatch, Stop-MhQuietWatch, Test-MhQuietAborted, Get-MhQuietAbortReason

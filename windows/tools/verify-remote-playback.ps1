<#
.SYNOPSIS
Drives the real MediaHub Windows client against a local mock media server and asserts on what the
user interface actually displays.

.DESCRIPTION
Unit tests prove the provider speaks the protocol. They cannot prove that a user can open the app,
browse a library and start playback, because that path runs through WinUI. This script performs that
path for real: it starts the mock server, seeds the credential vault through the same
DpapiCredentialStore the application uses, launches the app, and then drives and reads the UI with
UI Automation.

It is deliberately not part of CI: it needs a desktop session and a real window, which a hosted
runner does not reliably provide. The outcome is recorded as a local, repeatable verification
(docs/W2-EVIDENCE.md) rather than as a CI gate.

.NOTES
Build first:
  dotnet build tools/MockServerHost/MockServerHost.csproj -c Debug -p:Platform=x64
  dotnet build src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:\mh-out\
#>
[CmdletBinding()]
param(
    [string] $AppPath = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [int]    $Items = 40,
    [string] $OutDir = (Join-Path $PSScriptRoot '..\..\.tools\e2e')
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

$failures = New-Object System.Collections.Generic.List[string]

function Get-App {
    return Get-Process MediaHub -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
}

function Get-Root {
    $app = Get-App
    if (-not $app) { return $null }
    return [System.Windows.Automation.AutomationElement]::FromHandle($app.MainWindowHandle)
}

function Get-Text {
    $root = Get-Root
    if (-not $root) { return @() }
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                         [System.Windows.Automation.Condition]::TrueCondition)
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($e in $all) {
        $c = $e.Current
        if ($c.ControlType -eq [System.Windows.Automation.ControlType]::Text -and -not $c.IsOffscreen -and $c.Name) {
            $out.Add($c.Name)
        }
    }
    return $out
}

function Wait-For([scriptblock]$Condition, [int]$TimeoutSeconds = 20, [string]$What = 'condition') {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return $true }
        Start-Sleep -Milliseconds 500
    }
    Write-Warning "timed out waiting for $What"
    return $false
}

function Assert-Text([string]$Needle, [string]$Label) {
    $text = Get-Text
    $hit = $text | Where-Object { $_ -like ("*" + $Needle + "*") }
    if ($hit) { Write-Host ("  PASS  " + $Label + "  ->  " + ($hit | Select-Object -First 1)) }
    else { $failures.Add($Label + " (expected text containing '" + $Needle + "')"); Write-Host ("  FAIL  " + $Label) }
}

function Invoke-ByName([string]$Name) {
    $root = Get-Root
    if (-not $root) { throw 'no MediaHub window' }
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                         [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($e in $all) {
        $c = $e.Current
        if ($c.IsOffscreen) { continue }
        if ($c.Name -eq $Name -or $c.Name -like ("*" + $Name + "*")) {
            $pattern = $null
            if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
                $pattern.Invoke(); return $true
            }
            # NavigationView items expose SelectionItemPattern rather than InvokePattern.
            if ($e.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) {
                $pattern.Select(); return $true
            }
        }
    }
    return $false
}

function Enable-SyntheticInput {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class MediaHubClick {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr c);
}
"@ -ErrorAction SilentlyContinue
    # The client window must be foreground or the synthetic click lands on whatever window is on top.
    [MediaHubClick]::SetProcessDpiAwarenessContext([IntPtr](-4)) | Out-Null
}

function Click-At([int]$X, [int]$Y) {
    Enable-SyntheticInput
    $app = Get-App
    if ($app) { [MediaHubClick]::SetForegroundWindow($app.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 400 }
    [MediaHubClick]::SetCursorPos($X, $Y) | Out-Null
    Start-Sleep -Milliseconds 250
    [MediaHubClick]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
    [MediaHubClick]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    return $true
}

function Click-PlayButton {
    $root = Get-Root
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                         [System.Windows.Automation.Condition]::TrueCondition)
    $buttons = @()
    foreach ($e in $all) {
        $c = $e.Current
        if ($c.IsOffscreen) { continue }
        if ($c.ControlType -eq [System.Windows.Automation.ControlType]::Button -and $c.BoundingRectangle.Width -gt 100) {
            $buttons += $e
        }
    }
    if ($buttons.Count -eq 0) { return $false }
    $r = $buttons[0].Current.BoundingRectangle
    return Click-At ([int]($r.X + $r.Width / 2)) ([int]($r.Y + $r.Height / 2))
}

function Click-FirstPoster {
    $root = Get-Root
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                         [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($e in $all) {
        $c = $e.Current
        if ($c.IsOffscreen) { continue }
        if ($c.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and
            $c.Name -like '*MediaCardViewModel*' -and $c.BoundingRectangle.Width -gt 50 -and
            $c.BoundingRectangle.Height -gt 100) {
            $r = $c.BoundingRectangle
            return Click-At ([int]($r.X + $r.Width / 2)) ([int]($r.Y + 160))
        }
    }
    return $false
}

# ---------------------------------------------------------------- run

Write-Host '== starting mock server =='
foreach ($p in @(Get-Process MockServerHost -ErrorAction SilentlyContinue)) {
    try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { }
}
$mockExe = Join-Path $PSScriptRoot 'MockServerHost\bin\x64\Debug\net10.0-windows\win-x64\MockServerHost.exe'
if (-not (Test-Path $mockExe)) { throw "mock host not built: $mockExe" }
$mockLog = Join-Path $OutDir 'verify-mock.log'
$mockArgs = @{
    FilePath               = $mockExe
    ArgumentList           = @('--seed', '--items', $Items)
    PassThru               = $true
    RedirectStandardOutput = $mockLog
    RedirectStandardError  = (Join-Path $OutDir 'verify-mock.err')
    WindowStyle            = 'Hidden'
}
$mock = Start-Process @mockArgs
Start-Sleep -Seconds 6
$mockText = Get-Content $mockLog -Raw
if ($mockText -notmatch 'vault check : Ok') { throw "mock server did not seed the vault: $mockText" }
Write-Host '  mock ready, credential vault seeded'

Write-Host '== launching client =='
# A previously force-killed instance can linger as an unkillable zombie on this machine; it holds no
# window, so it is skipped rather than allowed to abort the run.
foreach ($stale in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
    try { if ($stale.MainWindowHandle -ne 0) { $stale.CloseMainWindow() | Out-Null } } catch { }
}
Start-Sleep -Seconds 2
Start-Process -FilePath $AppPath | Out-Null
if (-not (Wait-For { Get-App } 30 'the client window')) { throw 'client window never appeared' }
Start-Sleep -Seconds 8

try {
    Write-Host '== step 1: the seeded source restores and connects =='
    Wait-For { (Get-Text) -match '个当前可用' } 40 'source restore' | Out-Null
    Assert-Text '个当前可用' 'source restored from the credential vault'

    Write-Host '== step 2: library list from the server =='
    Invoke-ByName '媒体库' | Out-Null
    Wait-For { (Get-Text) -match 'Movies' } 40 'libraries' | Out-Null
    Assert-Text 'Movies' 'a library returned by the server is listed'
    Assert-Text '电影' 'the library kind is shown'

    Write-Host '== step 3: poster grid =='
    Invoke-ByName 'Movies' | Out-Null
    Wait-For { (Get-Text) -match '已加载' } 60 'library items' | Out-Null
    Assert-Text '已加载' 'items were paged in from the server'

    Write-Host '== step 4: detail page =='
    if (-not (Click-FirstPoster)) { throw 'no poster tile was found' }
    Wait-For { (Get-Text) -match '播放源' } 40 'detail page' | Out-Null
    Assert-Text '播放源' 'detail page shows the server-side stream description'
    Assert-Text '来源：' 'detail page names the source'

    Write-Host '== step 5: playback =='
    if (-not (Click-PlayButton)) { throw 'play button was not found' }
    Wait-For { (Get-Text) -match 'hwdec=' } 60 'playback' | Out-Null
    Assert-Text 'hwdec=' 'the engine reports decoder state while playing'

    $text = Get-Text
    $times = $text | Where-Object { $_ -match '^\d+:\d\d$' } | Select-Object -First 2
    $tech = $text | Where-Object { $_ -match 'hwdec=' } | Select-Object -First 1
    Write-Host ("  transport readout : " + ($times -join ' / '))
    Write-Host ("  technical readout : " + $tech)
    if (-not $tech) {
        # Everything the page currently says, so a failure here is diagnosable instead of opaque.
        Write-Host '  --- visible text at failure ---'
        foreach ($line in $text) { Write-Host ("    | " + $line) }
    }
    if ($times.Count -lt 2) { $failures.Add('the transport readout never showed a position and duration') }

    Write-Host '== step 6: the engine never contacted the server directly =='
    Start-Sleep -Seconds 3
    $requests = Get-Content $mockLog | Where-Object { $_ -match '^\d{2}:\d{2}:\d{2}' }
    $mediaRequests = @($requests | Where-Object { $_ -match 'stream' })
    $unauthenticated = @($requests | Where-Object { $_ -match 'stream' -and $_ -match 'session=NO' })
    Write-Host ("  server saw " + @($requests).Count + " requests, " + $mediaRequests.Count + " on the media route")
    if ($mediaRequests.Count -eq 0) { $failures.Add('the server never received a media stream request') }
    if ($unauthenticated.Count -ne 0) { $failures.Add('a media request reached the server without a session header') }
    else { Write-Host '  PASS  every media request carried a session header' }
}
finally {
    foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
        try { $p.CloseMainWindow() | Out-Null } catch { }
    }
    Start-Sleep -Seconds 4
    foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
        try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { }
    }
    if ($mock -and -not $mock.HasExited) { try { Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue } catch { } }
    Get-Process MockServerHost -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { } }
}

Write-Host ''
if ($failures.Count -eq 0) {
    Write-Host 'GUI_E2E PASS - remote streaming verified through the real user interface'
    exit 0
}
foreach ($f in $failures) { Write-Error ("GUI_E2E FAIL: " + $f) }
exit 1

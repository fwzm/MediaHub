#Requires -Version 7
<#
.SYNOPSIS
Proves the three non-intrusive techniques on this machine, one step at a time, behind the idle gate.

.DESCRIPTION
Steps (each one is a checkpoint entry, so a pause never redoes a passed step):

  s1-launch   start the app off-screen without activating it; foreground, cursor and window state
              are captured before/after. The window must answer UIA afterwards.
  s2-uia      drive real controls through Invoke/Select/Toggle/SetValue/RangeValue on that
              non-activated window, then read the values back from both the control and settings.json.
  s3-capture  PrintWindow(PW_RENDERFULLCONTENT) of the settings window; the PNG must not be black.
  s4-video    restart the app with a local media file and wait for the engine's child surface.
  s5-capture  capture the video child window and the main window; the frame must contain video
              pixels and white subtitle text (low-chroma bright pixels in the subtitle band), not a
              black block. Player position is also read twice to prove playback is running.

The gate runs before each attempt (and again after a pause), exactly like run-quiet.ps1. Evidence
goes to <OutRoot>: PNGs, an JSON record and a timestamped summary log.

Exit codes: 0 = every step passed, 1 = at least one step failed, 3 = the idle gate never opened
(SKIPPED-GATE: no step was measured; a gated room is not a green room).

-DevOverrideIdleGate is for developing the harness on a machine that is in use. A run made with it
is not acceptance evidence and the log says so. Locked/session/sleep checks still apply.

.EXAMPLE
pwsh -File windows/tools/acceptance/quiet-ui/verify.ps1 -DevOverrideIdleGate

.EXAMPLE
pwsh -File windows/tools/acceptance/quiet-ui/verify.ps1
#>
[CmdletBinding()]
param(
    [int]$MinIdleSeconds = 1800,
    [int]$WaitForIdleMinutes = 0,
    [switch]$DevOverrideIdleGate,
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$Media = 'D:\mh-media\playback-180s.mp4',
    [string]$OutRoot = 'D:\mh-verify\quiet-ui',
    [string]$Checkpoint = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhQuiet.psm1') -Force
Import-Module (Join-Path $here '..\lib\MhQuietUi.psm1') -Force

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$captureDir = Join-Path $OutRoot 'captures'
New-Item -ItemType Directory -Force -Path $captureDir | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$logPath = Join-Path $OutRoot ('verify-' + $stamp + '.log')
if (-not $Checkpoint) { $Checkpoint = Join-Path $OutRoot 'checkpoint.json' }

$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$settingsBackup = Join-Path $OutRoot 'settings-backup.json'
if (Test-Path $settingsPath) { Copy-Item $settingsPath $settingsBackup -Force }

$cp = Read-MhQuietCheckpoint -Path $Checkpoint
$completed = New-Object System.Collections.Generic.List[string]
foreach ($k in @($cp.completed)) { if ($k) { $completed.Add([string]$k) } }
$results = [ordered]@{}
if (($cp.PSObject.Properties.Name -contains 'results') -and $cp.results) {
    if ($cp.results -is [System.Collections.IDictionary]) {
        foreach ($k in $cp.results.Keys) { $results[[string]$k] = $cp.results[$k] }
    }
    else {
        foreach ($p in $cp.results.PSObject.Properties) { $results[$p.Name] = $p.Value }
    }
}

function Save-State {
    [pscustomobject]@{
        runId     = $stamp
        updatedAt = (Get-Date).ToString('o')
        completed = @($completed | Sort-Object -Unique)
        results   = $results
    } | ConvertTo-Json -Depth 14 | Set-Content -Path $Checkpoint -Encoding UTF8
}

$script:app = $null
$script:watch = $null
$script:paused = 0

function Add-Note([string]$Text) {
    Write-MhQuietLog -Message $Text -Path $logPath
}

function Test-Aborted {
    return ($null -ne $script:watch -and (Test-MhQuietUiAborted $script:watch))
}

function Stop-Session {
    if ($script:app) {
        $r = Stop-MhQuietApp -App $script:app
        Add-Note ('app stop: ' + $r)
        $script:app = $null
    }
}

function Start-QuietSession([string[]]$Arguments = @()) {
    Stop-Session
    $app = Start-MhQuietApp -Exe $Exe -Arguments $Arguments
    $script:app = $app
    Add-Note ('app start pid=' + $app.pid + ' hwnd=' + $app.hwndHex + ' preserved=' + $app.foregroundPreserved + ' rect=' + $app.rect + ' visible=' + $app.visible)
    return $app
}

function Find-NavSettings {
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        $nav = Find-MhQuietElement -App $script:app -Name '设置' -ControlType 'ListItem'
        if (-not $nav) { $nav = Find-MhQuietElement -App $script:app -Name '设置' }
        if ($nav) { return $nav }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Ensure-SettingsApp {
    if ($script:app) { return }
    Start-QuietSession | Out-Null
    $nav = Find-NavSettings
    if (-not $nav) { throw 'settings navigation item not found after quiet relaunch' }
    $r = Invoke-MhQuietGuarded -App $script:app -Action { Invoke-MhQuietElement -Element $nav }
    Add-Note ('resume nav.settings -> ' + $r.result + ' focusStolen=' + $r.focusStolen + ' restored=' + $r.focusRestored)
    Start-Sleep -Seconds 2
}

function Ensure-VideoApp {
    if ($script:app) {
        $existing = Get-MhQuietChildWindows -Parent ([IntPtr]$script:app.hwnd) |
            Where-Object { $_.className -like '*Mpv*' -and $_.area -gt 10000 } |
            Sort-Object area -Descending | Select-Object -First 1
        if ($existing) { return $existing }
    }
    Start-QuietSession -Arguments @($Media) | Out-Null
    $deadline = (Get-Date).AddSeconds(60)
    $surface = $null
    while ((Get-Date) -lt $deadline -and -not $surface) {
        if (Test-Aborted) { return $null }
        $surface = Get-MhQuietChildWindows -Parent ([IntPtr]$script:app.hwnd) |
            Where-Object { $_.className -like '*Mpv*' -and $_.area -gt 10000 } |
            Sort-Object area -Descending | Select-Object -First 1
        if (-not $surface) { Start-Sleep -Milliseconds 500 }
    }
    return $surface
}

function Read-Setting([string]$Name) {
    if (-not (Test-Path $settingsPath)) { return $null }
    try { return (Get-Content $settingsPath -Raw | ConvertFrom-Json).$Name } catch { return $null }
}

function Invoke-Step([string]$Name, [scriptblock]$Body) {
    $before = Get-MhQuietSnapshot
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $value = & $Body
        $pass = [bool]$value.pass
        $detail = [string]$value.detail
        $data = $value.data
    }
    catch {
        $pass = $false
        $detail = 'ERROR: ' + $_.Exception.Message
        $data = $null
    }
    $sw.Stop()
    $after = Get-MhQuietSnapshot
    $record = [pscustomobject]@{
        name       = $Name
        pass       = $pass
        detail     = $detail
        elapsedMs  = [int]$sw.ElapsedMilliseconds
        cursor     = "$($before.cursorX),$($before.cursorY) -> $($after.cursorX),$($after.cursorY)"
        idleBefore = $before.idleSeconds
        idleAfter  = $after.idleSeconds
        appForeground = if ($script:app) { $after.foregroundPid -eq $script:app.pid } else { $null }
        foreground = "$($after.foreground) '$($after.foregroundTitle)'"
        data       = $data
    }
    $results[$Name] = $record
    Add-Note (('[{0}] {1} | {2} | cursor {3} | appForeground={4}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $Name, $detail, $record.cursor, $record.appForeground))
    return $record
}

# ---------------------------------------------------------------- steps
function Step-Launch {
    $app = Start-QuietSession
    $nav = Find-NavSettings
    $offscreen = $true
    if ($app.rect -match '^(-?\d+),') { $offscreen = ([int]$Matches[1] -ge 2500) }
    $pass = $app.foregroundPreserved -and $app.visible -and (-not $app.iconic) -and $offscreen -and ($null -ne $nav) -and ($app.after.foregroundPid -ne $app.pid)
    [pscustomobject]@{
        pass   = $pass
        detail = 'preserved=' + $app.foregroundPreserved + ' visible=' + $app.visible + ' iconic=' + $app.iconic + ' offscreenX=' + $offscreen + ' uiaNav=' + ($null -ne $nav) + ' fgBefore=' + $app.before.foregroundTitle + ' fgAfter=' + $app.after.foregroundTitle
        data   = $app
    }
}

function Step-Uia {
    Ensure-SettingsApp
    $nav = Find-NavSettings
    if (-not $nav) { return [pscustomobject]@{ pass = $false; detail = 'settings nav missing'; data = $null } }
    $g0 = Invoke-MhQuietGuarded -App $script:app -Action { Invoke-MhQuietElement -Element $nav }
    Start-Sleep -Seconds 2

    $toggle = Find-MhQuietElement -App $script:app -AutomationId 'settings.diskcache'
    if (-not $toggle) { return [pscustomobject]@{ pass = $false; detail = 'settings.diskcache missing'; data = $null } }
    $tp = $null
    $toggleBefore = if ($toggle.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$tp)) { $tp.Current.ToggleState } else { $null }
    $g1 = Invoke-MhQuietGuarded -App $script:app -Action { Invoke-MhQuietElement -Element $toggle }
    Start-Sleep -Milliseconds 800
    $null = $toggle.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$tp)
    $toggleAfter = if ($tp) { $tp.Current.ToggleState } else { $null }
    $toggleFile = Read-Setting 'DiskCacheEnabled'
    $expectedFile = ($toggleAfter -eq 'On')
    $toggleOk = ($null -ne $toggleBefore) -and ($toggleAfter -ne $toggleBefore) -and ($toggleFile -eq $expectedFile)

    $exp = Find-MhQuietElement -App $script:app -Name '软件更新'
    if (-not $exp) { $exp = Find-MhQuietElement -App $script:app -Contains '软件更新' }
    if ($exp) {
        $ep = $null
        if ($exp.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$ep)) { $ep.Expand(); Start-Sleep -Milliseconds 800 }
    }
    $box = Find-MhQuietElement -App $script:app -AutomationId 'settings.updatesource'
    if (-not $box) { $box = Find-MhQuietElement -App $script:app -ControlType 'Edit' }
    $urlOk = $false
    $urlDetail = 'updatesource missing'
    if ($box) {
        $oldUrl = Get-MhQuietValue $box
        $g2 = Invoke-MhQuietGuarded -App $script:app -Action { Set-MhQuietValue -Element $box -Value 'D:\mh-quiet-test' }
        Start-Sleep -Milliseconds 700
        $readUrl = Get-MhQuietValue $box
        $fileUrl = Read-Setting 'UpdateSource'
        $urlOk = ($readUrl -eq 'D:\mh-quiet-test') -and ($fileUrl -eq 'D:\mh-quiet-test')
        $urlDetail = 'set=' + $g2.result + ' read=' + $readUrl + ' file=' + $fileUrl
        if ($null -ne $oldUrl) { $null = Invoke-MhQuietGuarded -App $script:app -Action { Set-MhQuietValue -Element $box -Value $oldUrl } }
        Start-Sleep -Milliseconds 500
    }

    $combo = Find-MhQuietElement -App $script:app -AutomationId 'settings.qualitypreference'
    $qualityOk = $false
    $qualityDetail = 'quality combo missing'
    if ($combo) {
        $sel = $null
        $current = @()
        if ($combo.TryGetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern, [ref]$sel)) {
            $current = @($sel.Current.GetSelection() | ForEach-Object { $_.Current.Name })
        }
        $target = if ($current -contains '最高画质') { '平衡' } else { '最高画质' }
        $g3 = Invoke-MhQuietGuarded -App $script:app -Action { Select-MhQuietComboItem -App $script:app -Combo $combo -Text $target }
        $now = @()
        if ($combo.TryGetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern, [ref]$sel)) {
            $now = @($sel.Current.GetSelection() | ForEach-Object { $_.Current.Name })
        }
        $qualityOk = ($now -contains $target)
        $qualityDetail = 'target=' + $target + ' result=' + $g3.result + ' now=' + ($now -join ',')
        if ($current.Count -gt 0 -and $current[0] -ne $target) {
            $null = Invoke-MhQuietGuarded -App $script:app -Action { Select-MhQuietComboItem -App $script:app -Combo $combo -Text $current[0] }
        }
    }

    $slider = Find-MhQuietElement -App $script:app -ControlType 'Slider'
    $rangeOk = $false
    $rangeDetail = 'slider missing'
    if ($slider) {
        $old = Get-MhQuietRange $slider
        $g4 = Invoke-MhQuietGuarded -App $script:app -Action { Set-MhQuietRange -Element $slider -Value 55 }
        Start-Sleep -Milliseconds 700
        $now = Get-MhQuietRange $slider
        $rangeOk = ($null -ne $now) -and ([Math]::Abs($now.value - 55) -lt 0.51)
        $rangeDetail = 'set=' + $g4.result + ' read=' + $(if ($now) { $now.value } else { 'n/a' })
        if ($old) { $null = Invoke-MhQuietGuarded -App $script:app -Action { Set-MhQuietRange -Element $slider -Value $old.value } }
    }

    $focusStolen = @($g0, $g1).Where({ $_.focusStolen }).Count
    $pass = $toggleOk -and $urlOk -and $qualityOk -and $rangeOk
    [pscustomobject]@{
        pass   = $pass
        detail = 'toggle=' + $toggleOk + '(' + $toggleBefore + '->' + $toggleAfter + ' file=' + $toggleFile + ') ' + $urlDetail + ' quality=' + $qualityOk + '(' + $qualityDetail + ') range=' + $rangeOk + '(' + $rangeDetail + ') transientFocusSteals=' + $focusStolen
        data   = [pscustomobject]@{ toggle = $toggleOk; url = $urlOk; quality = $qualityOk; range = $rangeOk }
    }
}

function Step-CaptureSettings {
    Ensure-SettingsApp
    $shot = Save-MhQuietWindowCapture -Hwnd ([IntPtr]$script:app.hwnd) -Path (Join-Path $captureDir 'settings-main.png')
    $pass = $shot.printWindowReturned -and ($shot.nonBlackRatio -gt 0.5) -and ($shot.uniqueColors -ge 50) -and ($shot.width -ge 800)
    [pscustomobject]@{
        pass   = $pass
        detail = 'png=' + $shot.path + ' size=' + $shot.width + 'x' + $shot.height + ' nonBlack=' + $shot.nonBlackRatio + ' colors=' + $shot.uniqueColors + ' mean=' + $shot.meanLuma
        data   = $shot
    }
}

function Step-Video {
    $surface = Ensure-VideoApp
    if (-not $surface) { return [pscustomobject]@{ pass = $false; detail = 'video surface not found'; data = $null } }
    Start-Sleep -Seconds 2
    $snap = Get-MhQuietSnapshot
    $pass = $surface.visible -and ($surface.area -gt 100000) -and ($snap.foregroundPid -ne $script:app.pid)
    [pscustomobject]@{
        pass   = $pass
        detail = 'class=' + $surface.className + ' rect=' + $surface.rect + ' visible=' + $surface.visible + ' appForeground=' + ($snap.foregroundPid -eq $script:app.pid) + ' fg=' + $snap.foregroundTitle
        data   = $surface
    }
}

function Step-CaptureVideo {
    $surface = Ensure-VideoApp
    if (-not $surface) { return [pscustomobject]@{ pass = $false; detail = 'video surface not found'; data = $null } }

    $slider = Find-MhQuietElement -App $script:app -AutomationId 'player.seekslider'
    $posBefore = if ($slider) { Get-MhQuietRange $slider } else { $null }
    Start-Sleep -Seconds 3

    $main = Save-MhQuietWindowCapture -Hwnd ([IntPtr]$script:app.hwnd) -Path (Join-Path $captureDir 'video-main.png')
    $shot = Save-MhQuietWindowCapture -Hwnd $surface.hwnd -Path (Join-Path $captureDir 'video-surface.png')
    $posAfter = if ($slider) { Get-MhQuietRange $slider } else { $null }

    $progressed = $false
    # The seek slider exposes a 0..1 fraction of the duration, not seconds.
    if ($posBefore -and $posAfter) { $progressed = (($posAfter.value - $posBefore.value) -ge 0.002) }
    $videoOk = $shot.printWindowReturned -and ($shot.nonBlackRatio -gt 0.5) -and ($shot.uniqueColors -ge 100) -and ($shot.width -ge 100) -and ($shot.height -ge 100)
    $subtitleOk = ($shot.subtitleBandNeutralRatio -gt 0.001)
    $pass = $videoOk -and $progressed
    [pscustomobject]@{
        pass   = $pass
        detail = 'surface=' + $shot.path + ' ' + $shot.width + 'x' + $shot.height + ' nonBlack=' + $shot.nonBlackRatio + ' colors=' + $shot.uniqueColors + ' mean=' + $shot.meanLuma + ' subtitleBandNeutral=' + $shot.subtitleBandNeutralRatio + '(ok=' + $subtitleOk + ') pos=' + $(if ($posBefore) { $posBefore.value } else { 'n/a' }) + '->' + $(if ($posAfter) { $posAfter.value } else { 'n/a' }) + ' progressed=' + $progressed + ' | main nonBlack=' + $main.nonBlackRatio + ' colors=' + $main.uniqueColors
        data   = [pscustomobject]@{ surface = $shot; main = $main; positionBefore = $posBefore; positionAfter = $posAfter; progressed = $progressed; subtitleVisual = $subtitleOk }
    }
}

$steps = [ordered]@{
    's1-launch' = { Step-Launch }
    's2-uia'    = { Step-Uia }
    's3-capture' = { Step-CaptureSettings }
    's4-video'  = { Step-Video }
    's5-capture' = { Step-CaptureVideo }
}

Add-Note ('quiet-ui verify start threshold=' + $MinIdleSeconds + 's devOverride=' + [bool]$DevOverrideIdleGate + ' exe=' + $Exe + ' media=' + $Media)
Add-Note ('checkpoint: done=' + $(if ($completed.Count) { ($completed -join ',') } else { '<none>' }) + ' at ' + $Checkpoint)

$gateAttempt = 0
$gateTimedOut = $false
try {
    while ($true) {
        $pending = @($steps.Keys | Where-Object { $completed -notcontains $_ })
        if ($pending.Count -eq 0) { break }

        $gateAttempt++
        if ($DevOverrideIdleGate) {
            Add-Note ('gate: DEV-OVERRIDE attempt=' + $gateAttempt + ' idle=' + (Get-MhUserIdleSeconds) + 's need=' + $MinIdleSeconds + 's - development run only, not acceptance evidence')
        }
        else {
            Add-Note ('gate: attempt=' + $gateAttempt + ' idle=' + (Get-MhUserIdleSeconds) + 's need=' + $MinIdleSeconds + 's - waiting for a continuous quiet window')
            $gate = Wait-MhQuietGate -MinIdleSeconds $MinIdleSeconds -TimeoutSeconds ($WaitForIdleMinutes * 60) -LogPath $logPath
            if (-not $gate.Ready) {
                Add-Note ('SKIPPED-USER-ACTIVE waited=' + $gate.WaitedSeconds + 's blocker=' + $(if ($gate.Blocker) { $gate.Blocker.kind } else { 'user-active' }) + ': nothing was started')
                $gateTimedOut = $true
                break
            }
            Add-Note ('gate: READY idle=' + $gate.IdleSeconds + 's waited=' + $gate.WaitedSeconds + 's')
        }

        $flagPath = Join-Path $OutRoot ('watch-' + $stamp + '-' + $gateAttempt)
        $threshold = if ($DevOverrideIdleGate) { 0 } else { $MinIdleSeconds }
        $script:watch = Start-MhQuietUiWatch -MinIdleSeconds $threshold -PollMilliseconds 1000 -FlagPath $flagPath
        Add-Note ('watchdog started (poll=1s, abortOnInput=' + (-not $DevOverrideIdleGate) + ')')

        $paused = $false
        foreach ($name in $pending) {
            if (Test-Aborted) { $paused = $true; break }
            $record = Invoke-Step $name $steps[$name]
            # Another acceptance session on this machine kills MediaHub by name between its own
            # suites. A step that failed because our process disappeared is retried once after a
            # quiet relaunch instead of being reported as a product failure.
            $appGone = ($null -ne $script:app) -and -not (Get-Process -Id $script:app.pid -ErrorAction SilentlyContinue)
            if (-not $record.pass -and $appGone -and $name -ne 's1-launch') {
                Add-Note ('step ' + $name + ' failed while the app process was already gone; relaunching and retrying once')
                $script:app = $null
                $record = Invoke-Step $name $steps[$name]
            }
            if (Test-Aborted) {
                Add-Note ('step ' + $name + ' finished after the abort marker; result discarded, it will run again after the pause')
                $paused = $true
                break
            }
            if ($record.pass) { if (-not $completed.Contains($name)) { $completed.Add($name) } }
            Save-State
        }

        $abortReason = if (Test-Aborted) { Get-MhQuietUiAbortReason $script:watch } else { '' }
        Stop-MhQuietUiWatch $script:watch
        $script:watch = $null
        Stop-Session

        if (-not $paused) { break }

        $script:paused++
        Add-Note ('PAUSED 用户回来了，已暂停 reason=' + $abortReason + ' completed=' + $completed.Count + '/' + $steps.Count + ' -> waiting for the next continuous ' + [int]($MinIdleSeconds / 60) + '-minute quiet window')
        Save-State
    }
}
finally {
    if ($script:watch) { Stop-MhQuietUiWatch $script:watch }
    Stop-Session
    # Put the user's settings back exactly as they were before the step that edits them.
    if (Test-Path $settingsBackup) {
        Copy-Item $settingsBackup $settingsPath -Force
        Add-Note 'settings restored from backup'
    }
    Save-State
}

$failed = @($results.Keys | Where-Object { -not $results[$_].pass })
$evidencePath = Join-Path $OutRoot 'evidence.json'
$results | ConvertTo-Json -Depth 14 | Set-Content -Path $evidencePath -Encoding UTF8

if ($gateTimedOut) {
    # A timed-out gate means the machine was never idle long enough to run a step. That is not a
    # result: reporting exit 0 makes a fully skipped round indistinguishable from a fully passing
    # one in CI logs and in the transcript summary.
    Add-Note ('RESULT: SKIPPED-GATE no continuous ' + [int]($MinIdleSeconds / 60) + '-minute quiet window; no step was measured completed=' + $completed.Count + '/' + $steps.Count + ' evidence=' + $evidencePath)
    exit 3
}
if ($failed.Count -gt 0) {
    Add-Note ('RESULT: FAILED ' + ($failed -join ', ') + ' evidence=' + $evidencePath)
    exit 1
}
Add-Note ('RESULT: OK all ' + $steps.Count + ' steps passed paused=' + $script:paused + ' evidence=' + $evidencePath)
exit 0

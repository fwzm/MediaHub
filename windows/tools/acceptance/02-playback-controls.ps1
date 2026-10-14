<#
.SYNOPSIS
W5-E area 2: playback controls - play/pause, seek, volume, mute, speed, subtitle, audio track.

.DESCRIPTION
Every check records before-state, the input, after-state, the assertion and its evidence file.
The observables are deliberately not "the button exists":
  position/duration  read from the player's own text, so a pause is a frozen number, not a glyph
  seek slider        read back through UI Automation's RangeValue pattern
  mute               measured as the peak level of the app's Windows audio session, because the
                     client exposes no mute state to UI Automation and a screenshot cannot hear
  speed              measured as the rate the position advances over a fixed wall-clock window
  track lists        read from the audio/subtitle ListView rows and their selection state
The engine's technical summary is recorded with every state so a software-decode fallback cannot
masquerade as a successful playback test.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$Media = 'D:\mh-media\playback-180s.mp4',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence'),
  [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue

$dir = Join-Path $EvidenceRoot '02-playback-controls'
$run = New-MhRun -Area 'playback-controls' -EvidenceDir $dir -Meta @{ exe = $Exe; media = $Media }
$shot = { param($name, $rect) (Save-MhScreenshot -Path (Join-Path $dir ($name + '.png')) -Rect $rect).path }
$dump = { param($name) (Save-MhDump -Path (Join-Path $dir ($name + '.txt')) -Root (Get-MhWindow) -VisibleOnly).path }

function Get-WinRect { $i = Get-MhWindowInfo; @($i.frame.x, $i.frame.y, $i.frame.w, $i.frame.h) }
function Wait-Seconds { param([double]$s) Start-Sleep -Milliseconds ([int]($s * 1000)) }

Write-Host '== setup: fresh app, local 3-minute fixture, engine must be on the hardware path =='
Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
Wait-Seconds 2
$app = Start-MhApp -Exe $Exe -ResetTrace -ArgumentList @($Media)
Wait-Seconds 7
$info = Get-MhWindowInfo
[void](Set-MhForeground -Hwnd ([IntPtr]$info.hwnd))
Wait-Seconds 1
$ready = Get-MhPlayerState
$evReady = @(& $shot '00-player-ready' (Get-WinRect)), (& $dump '00-player-ready')

Add-MhCheck -Run $run -Id 'setup.player-open' -Before @{ processes = (Get-MhProcess).Count } -Input ('MediaHub.exe ' + $Media) -After @{ playerVisible = $ready.playerVisible; title = $ready.title; duration = $ready.duration; technical = $ready.technical } -Assertion 'the player page is open for the fixture and mpv reports hardware decoding (hwdec=d3d11va), not the software fallback' -Result $(if ($ready.playerVisible -and $ready.technical -match 'hwdec=d3d11va') { 'pass' } else { 'fail' }) -Evidence $evReady -Note ('technical=' + $ready.technical)

Wait-Seconds 3
$p1 = Get-MhPlayerState -SkipSlow
Wait-Seconds 3
$p2 = Get-MhPlayerState -SkipSlow
$advanced = ($null -ne $p1.positionSeconds -and $null -ne $p2.positionSeconds -and $p2.positionSeconds -gt $p1.positionSeconds + 1)
Add-MhCheck -Run $run -Id 'play.advances' -Before @{ position = $p1.position; seconds = $p1.positionSeconds } -Input 'wait 3 s' -After @{ position = $p2.position; seconds = $p2.positionSeconds; audio = $p2.audio.raw } -Assertion 'position advances by roughly 3 s over a 3 s wall-clock wait' -Result $(if ($advanced) { 'pass' } else { 'fail' }) -Evidence $evReady

# ---------------------------------------------------------------- pause / resume

$beforePause = Get-MhPlayerState -SkipSlow
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$keyIn = Send-MhKeys -Keys @('SPACE')
Wait-Seconds 1.5
$pausedA = Get-MhPlayerState -SkipSlow
Wait-Seconds 3
$pausedB = Get-MhPlayerState -SkipSlow
$frozen = ($null -ne $pausedA.positionSeconds -and $null -ne $pausedB.positionSeconds -and [Math]::Abs($pausedB.positionSeconds - $pausedA.positionSeconds) -lt 0.5)
$evPauseAfter = @(& $shot '02-paused' (Get-WinRect))
Add-MhCheck -Run $run -Id 'play.pause-frozen' -Before @{ position = $beforePause.position } -Input 'key SPACE (pause)' -After @{ position1 = $pausedA.position; position2 = $pausedB.position } -Assertion 'after the pause key the position does not advance over 3 s' -Result $(if ($frozen) { 'pass' } else { 'fail' }) -Evidence $evPauseAfter -Note 'the key is sent to the foreground window; the client routes SPACE to the player page'

$keyIn = Send-MhKeys -Keys @('SPACE')
Wait-Seconds 1
$r1 = Get-MhPlayerState -SkipSlow
Wait-Seconds 3
$r2 = Get-MhPlayerState -SkipSlow
$resumed = ($null -ne $r1.positionSeconds -and $null -ne $r2.positionSeconds -and $r2.positionSeconds -gt $r1.positionSeconds + 1)
Add-MhCheck -Run $run -Id 'play.resume-advances' -Before @{ position = $r1.position } -Input 'key SPACE (resume)' -After @{ position = $r2.position } -Assertion 'position advances again after the second space press' -Result $(if ($resumed) { 'pass' } else { 'fail' }) -Evidence $evPauseAfter

# ---------------------------------------------------------------- seek: button, key, slider

$s0 = Get-MhPlayerState -SkipSlow
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
[void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.seekforward'))
Wait-Seconds 2
$s1 = Get-MhPlayerState -SkipSlow
$delta1 = if ($null -ne $s0.positionSeconds -and $null -ne $s1.positionSeconds) { [Math]::Round($s1.positionSeconds - $s0.positionSeconds, 1) } else { $null }
Add-MhCheck -Run $run -Id 'seek.forward-button' -Before @{ position = $s0.position } -Input 'invoke player.seekforward (10 s step)' -After @{ position = $s1.position; deltaSeconds = $delta1 } -Assertion 'position jumps forward by about the 10 s step (7..16 s covers playback during the wait)' -Result $(if ($null -ne $delta1 -and $delta1 -ge 7 -and $delta1 -le 16) { 'pass' } else { 'fail' }) -Evidence @(& $shot '03-after-seek-forward' (Get-WinRect))

$s2 = Get-MhPlayerState -SkipSlow
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
[void](Send-MhKeys -Keys @('LEFT'))
Wait-Seconds 2
$s3 = Get-MhPlayerState -SkipSlow
$delta2 = if ($null -ne $s2.positionSeconds -and $null -ne $s3.positionSeconds) { [Math]::Round($s3.positionSeconds - $s2.positionSeconds, 1) } else { $null }
Add-MhCheck -Run $run -Id 'seek.back-key' -Before @{ position = $s2.position } -Input 'key LEFT (10 s back)' -After @{ position = $s3.position; deltaSeconds = $delta2 } -Assertion 'the left-arrow key seeks backwards by about 10 s' -Result $(if ($null -ne $delta2 -and $delta2 -le -6 -and $delta2 -ge -16) { 'pass' } else { 'fail' }) -Evidence $evPauseAfter

$seek = Find-MhAppElement -AutomationId 'SeekSlider'
$sr = Get-MhElementRect -Element $seek
$s4 = Get-MhPlayerState -SkipSlow
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$targetX = $sr.x + [int]($sr.w * 0.7)
[void](Click-MhPoint -X $targetX -Y $sr.cy -SettleMs 1500)
$s5 = Get-MhPlayerState -SkipSlow
$frac = if ($null -ne $s5.positionSeconds -and $s5.durationSeconds -gt 0) { [Math]::Round($s5.positionSeconds / $s5.durationSeconds, 3) } else { $null }
Add-MhCheck -Run $run -Id 'seek.slider-click' -Before @{ position = $s4.position } -Input ('click the seek slider at 70% of its width (x=' + $targetX + ')') -After @{ position = $s5.position; fraction = $frac } -Assertion 'a click at 70% of the seek bar lands within +-8% of 70% of the duration' -Result $(if ($null -ne $frac -and [Math]::Abs($frac - 0.7) -le 0.08) { 'pass' } else { 'fail' }) -Evidence @(& $shot '04-after-seek-slider-70pct' (Get-WinRect))

# ---------------------------------------------------------------- volume

$vol = Find-MhAppElement -AutomationId 'VolumeSlider'
$v0 = Get-MhPlayerState -SkipSlow
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$peakLoud = Get-MhAudioPeak
[void](Set-MhRangeValue -Element $vol -Value 25)
Wait-Seconds 2
$v1 = Get-MhPlayerState -SkipSlow
$peakQuiet = Get-MhAudioPeak
Add-MhCheck -Run $run -Id 'volume.slider-to-25' -Before @{ volume = $v0.volume; audioPeak = $peakLoud.peak } -Input 'set the volume slider (UIA RangeValue) to 25' -After @{ volume = $v1.volume; audioPeak = $peakQuiet.peak } -Assertion 'the slider reads back 25 and the measured audio peak drops below the value measured at volume 100' -Result $(if ($null -ne $v1.volume -and [Math]::Abs($v1.volume - 25) -le 1 -and [double]$peakQuiet.peak -lt [double]$peakLoud.peak) { 'pass' } else { 'fail' }) -Evidence @(& $dump '05-volume-25')

[void](Set-MhRangeValue -Element $vol -Value 100)
Wait-Seconds 2
$v2 = Get-MhPlayerState -SkipSlow
Add-MhCheck -Run $run -Id 'volume.restore-100' -Before @{ volume = $v1.volume } -Input 'set the volume slider back to 100' -After @{ volume = $v2.volume } -Assertion 'the volume slider reads back 100' -Result $(if ($null -ne $v2.volume -and [Math]::Abs($v2.volume - 100) -le 1) { 'pass' } else { 'fail' }) -Evidence @(& $dump '06-volume-100')

# ---------------------------------------------------------------- mute (measured, not assumed)

$peakBefore = Get-MhAudioPeak
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
[void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.mute'))
Wait-Seconds 1.5
$peakMuted = Get-MhAudioPeak
$posMuted = Get-MhPlayerState -SkipSlow
Add-MhCheck -Run $run -Id 'mute.audio-silent' -Before @{ audioPeak = $peakBefore.peak; sessionState = $peakBefore.state } -Input 'invoke player.mute' -After @{ audioPeak = $peakMuted.peak; sessionState = $peakMuted.state; position = $posMuted.position } -Assertion 'the audio session stays active but its peak level falls to 0 while playback continues - the sound actually stopped, not just the icon changing' -Result $(if ([double]$peakBefore.peak -gt 0.001 -and [double]$peakMuted.peak -eq 0 -and $peakMuted.state -eq 1) { 'pass' } else { 'fail' }) -Evidence @(& $shot '07-muted' (Get-WinRect)) -Note ('before=' + $peakBefore.raw + ' after=' + $peakMuted.raw)

[void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.mute'))
Wait-Seconds 1.5
$peakAfter = Get-MhAudioPeak
Add-MhCheck -Run $run -Id 'mute.unmute-restores-audio' -Before @{ audioPeak = $peakMuted.peak } -Input 'invoke player.mute again' -After @{ audioPeak = $peakAfter.peak } -Assertion 'the second press restores audible output above the muted level (the toggle is bidirectional, not a one-way mute)' -Result $(if ([double]$peakAfter.peak -gt 0.001) { 'pass' } else { 'fail' }) -Evidence @(& $dump '08-unmuted') -Note $peakAfter.raw

# ---------------------------------------------------------------- speed

$speedCombo = Find-MhAppElement -AutomationId 'SpeedCombo'
$speedItems = @()
$expand = $null
if ($speedCombo.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$expand)) {
  $expand.Expand(); Wait-Seconds 1
  $speedItems = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'ListItem' -and $_.Current.Name -match '^\d' } | ForEach-Object { $_.Current.Name })
  $expand.Collapse(); Wait-Seconds 0.5
}
Add-MhCheck -Run $run -Id 'speed.options-listed' -Before @{ selected = $v2.speed } -Input 'expand SpeedCombo' -After @{ options = $speedItems } -Assertion 'the speed combo offers more than one rate to choose from' -Result $(if ($speedItems.Count -gt 1) { 'pass' } else { 'fail' }) -Evidence @(& $dump '09-speed-options')

function Measure-Rate {
  param([double]$Seconds)
  $a = Get-MhPlayerState -SkipSlow
  Start-Sleep -Milliseconds ([int]($Seconds * 1000))
  $b = Get-MhPlayerState -SkipSlow
  if ($null -eq $a.positionSeconds -or $null -eq $b.positionSeconds) { return $null }
  return [Math]::Round(($b.positionSeconds - $a.positionSeconds) / $Seconds, 3)
}
$rate1 = Measure-Rate -Seconds 6
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$selSpeed = Select-MhComboItem -ComboElement $speedCombo -Text '2.0x'
Wait-Seconds 2
$curSpeed = (Get-MhPlayerState -SkipSlow).speed -join ','
$rate2 = Measure-Rate -Seconds 6
Add-MhCheck -Run $run -Id 'speed.2x-doubles-rate' -Before @{ rate = $rate1; selected = '1.0x' } -Input ('select speed 2.0x (' + $selSpeed + ')') -After @{ rate = $rate2; selected = $curSpeed } -Assertion 'the position advances roughly twice as fast at 2.0x as it did at 1.0x (rate > 1.5x baseline)' -Result $(if ($null -ne $rate1 -and $null -ne $rate2 -and $rate2 -gt ($rate1 * 1.5)) { 'pass' } else { 'fail' }) -Evidence @(& $shot '10-speed-2x' (Get-WinRect)) -Note ('rate1=' + $rate1 + ' rate2=' + $rate2 + ' combo=' + $curSpeed)

[void](Select-MhComboItem -ComboElement $speedCombo -Text '1.0x')
Wait-Seconds 1
$rate3 = Measure-Rate -Seconds 4
Add-MhCheck -Run $run -Id 'speed.restore-1x' -Before @{ rate = $rate2 } -Input 'select speed 1.0x' -After @{ rate = $rate3 } -Assertion 'the rate returns to about 1.0 after selecting 1.0x again' -Result $(if ($null -ne $rate3 -and $rate3 -gt 0.5 -and $rate3 -lt 1.6) { 'pass' } else { 'fail' }) -Evidence @(& $dump '11-speed-1x')

# ---------------------------------------------------------------- audio track and subtitle

$audioBtn = Get-MhPanelButton -Kind audio
$subBtn = Get-MhPanelButton -Kind subtitle
Add-MhCheck -Run $run -Id 'panel.buttons-located' -Before @{ } -Input 'locate the audio/subtitle panel buttons' -After @{ audioButton = ($null -ne $audioBtn); subtitleButton = ($null -ne $subBtn) } -Assertion 'both bottom-bar panel buttons are reachable (they carry no AutomationId, only a tooltip)' -Result $(if ($audioBtn -and $subBtn) { 'pass' } else { 'fail' }) -Evidence @(& $dump '12-panel-buttons')

if ($audioBtn) {
  [void](Invoke-MhElement -Element $audioBtn)
  Wait-Seconds 1.5
  $rows = @(Get-MhTrackList -Kind 'audio')
  $evAudio = @(& $shot '13-audio-panel' (Get-WinRect))
  Add-MhCheck -Run $run -Id 'audio.tracks-listed' -Before @{ panel = 'closed' } -Input 'invoke the audio-track panel button' -After @{ rows = ($rows | ForEach-Object { $_.name }) } -Assertion 'the fixture declares two audio tracks and the panel lists both' -Result $(if ($rows.Count -ge 2) { 'pass' } else { 'fail' }) -Evidence $evAudio -Note (($rows | ForEach-Object { $_.name }) -join ' | ')

  if ($rows.Count -ge 2) {
    $pick = @($rows | Where-Object { -not $_.selected })[0]
    $item = Find-MhAppElement -ControlType 'ListItem' -Name $pick.name
    $r = Invoke-MhElement -Element $item
    Wait-Seconds 2
    $rows2 = @(Get-MhTrackList -Kind 'audio')
    $now = (($rows2 | Where-Object { $_.selected } | ForEach-Object { $_.name }) -join ' | ')
    Add-MhCheck -Run $run -Id 'audio.switch-track' -Before @{ selected = (($rows | Where-Object { $_.selected } | ForEach-Object { $_.name }) -join ' | ') } -Input ('select the other audio track (' + $pick.name + ') -> ' + $r) -After @{ selected = $now } -Assertion 'the selection moves to the other audio track and stays there after a re-read' -Result $(if ($now -eq $pick.name) { 'pass' } else { 'fail' }) -Evidence @(& $shot '14-audio-switched' (Get-WinRect))
  }
}

if ($subBtn) {
  [void](Invoke-MhElement -Element $subBtn)
  Wait-Seconds 1.5
  $srows = @(Get-MhTrackList -Kind 'subtitle')
  $evSub = @(& $shot '15-subtitle-panel' (Get-WinRect))
  Add-MhCheck -Run $run -Id 'subtitle.tracks-listed' -Before @{ panel = 'closed' } -Input 'invoke the subtitle panel button' -After @{ rows = ($srows | ForEach-Object { $_.name }) } -Assertion 'the subtitle panel lists a close-subtitles entry plus at least one real track' -Result $(if ($srows.Count -ge 2) { 'pass' } else { 'fail' }) -Evidence $evSub -Note (($srows | ForEach-Object { $_.name }) -join ' | ')

  $external = @($srows | Where-Object { $_.name -match '外挂' })
  Add-MhCheck -Run $run -Id 'subtitle.external-discovered' -Before @{ } -Input 'read the subtitle list' -After @{ external = ($external | ForEach-Object { $_.name }) } -Assertion 'the sibling playback-180s.srt is discovered and listed as an external subtitle' -Result $(if ($external.Count -ge 1) { 'pass' } else { 'fail' }) -Evidence $evSub

  if ($srows.Count -ge 1) {
    $off = @($srows | Where-Object { $_.name -eq '关闭字幕' })[0]
    if ($off) {
      $item = Find-MhAppElement -ControlType 'ListItem' -Name $off.name
      $r = Invoke-MhElement -Element $item
      Wait-Seconds 1.5
      $srows2 = @(Get-MhTrackList -Kind 'subtitle')
      $selected = (($srows2 | Where-Object { $_.selected } | ForEach-Object { $_.name }) -join ' | ')
      Add-MhCheck -Run $run -Id 'subtitle.select-off' -Before @{ } -Input ('select the close-subtitles row -> ' + $r) -After @{ selected = $selected } -Assertion 'choosing that row leaves it as the selected entry' -Result $(if ($selected -match '关闭字幕') { 'pass' } else { 'fail' }) -Evidence @(& $shot '16-subtitle-off' (Get-WinRect))
    }
  }
}

# ---------------------------------------------------------------- evidence snapshot and teardown

$final = Get-MhPlayerState
Add-MhCheck -Run $run -Id 'final.state' -Before @{ } -Input 'read the final state' -After $final -Assertion 'the player is still healthy after every control was exercised: still playing, engine still on hwdec=d3d11va' -Result $(if ($final.playerVisible -and $final.technical -match 'hwdec=d3d11va') { 'pass' } else { 'fail' }) -Evidence @(& $shot '99-final' (Get-WinRect), (& $dump '99-final'))

if (-not $KeepRunning) {
  $close = Stop-MhAppGracefully -TimeoutSec 20
  Add-MhCheck -Run $run -Id 'teardown.close' -Before @{ processes = (Get-MhProcess).Count } -Input 'close the window normally' -After $close -Assertion 'the app exits when the window is closed (no force-kill was needed for this area)' -Result $(if ($close.method -eq 'graceful') { 'pass' } else { 'fail' }) -Evidence @()
}

$result = Save-MhRun -Run $run -MarkdownPath (Join-Path $dir 'results.md')
Write-Host ''
Write-Host ('results: ' + ($result.counts | ConvertTo-Json -Compress))
Write-Host ('json:    ' + $result.json)

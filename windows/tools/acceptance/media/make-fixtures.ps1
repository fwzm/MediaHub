<#
.SYNOPSIS
Generates the local media fixtures the W5-E area scripts drive the client with.

.DESCRIPTION
Fixtures are written OUTSIDE the repository (default D:\mh-media) so no megabytes of generated
video land in a commit; only this generator is versioned.

Fixtures:
  playback-180s.mp4      180 s, 720p, two audio tracks (eng/chi) and one embedded subtitle track.
                         Long enough that a pause, a seek and a speed change are all observable,
                         and multi-track enough to test audio-track and subtitle switching.
  playback-180s.srt      external sibling subtitle, for external-subtitle discovery
  queue-a|b|c.mp4        30 s each, visually distinct, for drag-and-drop and temporary-queue order
#>
param(
  [string]$OutDir = 'D:\mh-media',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'ffmpeg not on PATH' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function New-Clip {
  param([string]$Path, [int]$Seconds, [string]$Video, [string[]]$Extra = @(), [switch]$MultiTrack)
  if ((Test-Path $Path) -and -not $Force) { Write-Host "skip (exists): $Path"; return }
  # "$Video=duration=..." produced "rate=25=duration=180" and ffmpeg rejected the filtergraph; the
  # duration has to be appended as another option of the same filter.
  $ff = @('-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', "${Video}:duration=$Seconds")
  if ($MultiTrack) {
    $ff += @('-f', 'lavfi', '-i', "sine=frequency=440:sample_rate=48000:duration=$Seconds",
             '-f', 'lavfi', '-i', "sine=frequency=880:sample_rate=48000:duration=$Seconds")
  } else {
    $ff += @('-f', 'lavfi', '-i', "sine=frequency=440:sample_rate=48000:duration=$Seconds")
  }
  $ff += @('-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '32', '-pix_fmt', 'yuv420p',
           '-c:a', 'aac', '-b:a', '96k', '-ac', '1', '-movflags', '+faststart')
  $ff += $Extra
  $ff += $Path
  & ffmpeg @ff
  if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $Path" }
  Write-Host ('made: {0} ({1:N0} bytes)' -f $Path, (Get-Item $Path).Length)
}

# A burnt-in frame counter means a screenshot of the surface identifies the exact frame on screen,
# which is what makes "the position stopped advancing" checkable by eye as well as by text.
New-Clip -Path (Join-Path $OutDir 'playback-180s.mp4') -Seconds 180 -Video 'testsrc2=size=1280x720:rate=25' -MultiTrack -Extra @(
  '-map', '0:v:0', '-map', '1:a:0', '-map', '2:a:0',
  '-metadata:s:a:0', 'language=eng', '-metadata:s:a:0', 'title=English',
  '-metadata:s:a:1', 'language=chi', '-metadata:s:a:1', 'title=Chinese')

New-Clip -Path (Join-Path $OutDir 'queue-a.mp4') -Seconds 30 -Video 'testsrc2=size=640x360:rate=25' -Extra @('-vf', 'hue=h=0')
New-Clip -Path (Join-Path $OutDir 'queue-b.mp4') -Seconds 30 -Video 'testsrc2=size=640x360:rate=25' -Extra @('-vf', 'hue=h=90')
New-Clip -Path (Join-Path $OutDir 'queue-c.mp4') -Seconds 30 -Video 'testsrc2=size=640x360:rate=25' -Extra @('-vf', 'hue=h=180')

$srt = Join-Path $OutDir 'playback-180s.srt'
if ((-not (Test-Path $srt)) -or $Force) {
  $sb = New-Object System.Text.StringBuilder
  for ($i = 0; $i -lt 24; $i++) {
    $a = [TimeSpan]::FromSeconds($i * 7); $b = [TimeSpan]::FromSeconds($i * 7 + 5)
    [void]$sb.AppendLine([string]($i + 1))
    [void]$sb.AppendLine(('{0:hh\:mm\:ss\,fff} --> {1:hh\:mm\:ss\,fff}' -f $a, $b))
    [void]$sb.AppendLine('external cue ' + ($i + 1))
    [void]$sb.AppendLine()
  }
  [System.IO.File]::WriteAllText($srt, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "made: $srt"
}

Write-Host ''
Write-Host 'fixtures:'
Get-ChildItem $OutDir -File | ForEach-Object { Write-Host ('  {0}  ({1:N0} bytes)' -f $_.Name, $_.Length) }

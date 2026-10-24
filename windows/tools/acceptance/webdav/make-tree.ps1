<#
.SYNOPSIS
Builds the WebDAV test tree used by the W5-D GUI loop.

.DESCRIPTION
Produces a directory tree that exercises every classification the WebDAV provider performs:

  <root>/Normal Movie.mp4                plain ASCII name, served with Range support
  <root>/Chinese 中文 视频 空格.mp4    non-ASCII name with spaces (path encoding)
  <root>/Subs Movie.mp4                  video with an external sibling subtitle
  <root>/Subs Movie.srt                  the sibling subtitle (not a video, must be classified as subtitle)
  <root>/Season 01/                      nested collection
  <root>/Season 01/Episode 01.mp4
  <root>/Season 01/Episode 01.srt
  <root>/norange/NoRange 无范围.mp4   served by the middleware with the Range header stripped

Every file is generated locally with ffmpeg (deterministic, no download). Video is long enough
(90 s) that a seek has somewhere to land and a pause is observable as a frozen position.
#>
param(
  [string]$Root = "D:\mh-webdav\root",
  [int]$LongSeconds = 90,
  [int]$ShortSeconds = 60,
  [switch]$Force
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw "ffmpeg not on PATH" }

function New-Video {
  param([string]$Path, [int]$Seconds, [string]$Pattern)
  if ((Test-Path $Path) -and -not $Force) { Write-Host "skip (exists): $Path"; return }
  New-Item -ItemType Directory -Force -Path (Split-Path $Path -Parent) | Out-Null
  # testsrc2 carries a burned-in frame counter, so a screenshot of the surface proves which frame
  # is on screen (a frozen frame after "pause" is only meaningful if the frame is identifiable).
  $args = @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=$Seconds",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$Seconds",
    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "34", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "96k", "-ac", "1",
    "-movflags", "+faststart",
    "-metadata", "title=$Pattern",
    $Path
  )
  & ffmpeg @args
  if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $Path" }
  Write-Host ("made: {0} ({1:N0} bytes)" -f $Path, (Get-Item $Path).Length)
}

function New-MoveableSrt {
  param([string]$Path, [string]$Label, [int]$Seconds)
  if ((Test-Path $Path) -and -not $Force) { return }
  New-Item -ItemType Directory -Force -Path (Split-Path $Path -Parent) | Out-Null
  # UTF-8 without BOM: the provider must decode it as UTF-8; a BOM would make this a weaker test.
  $sb = New-Object System.Text.StringBuilder
  $cue = 1
  for ($t = 0; $t -lt $Seconds; $t += 5) {
    $a = [TimeSpan]::FromSeconds($t); $b = [TimeSpan]::FromSeconds($t + 4)
    [void]$sb.AppendLine([string]$cue)
    [void]$sb.AppendLine(("{0:hh\:mm\:ss\,fff} --> {1:hh\:mm\:ss\,fff}" -f $a, $b))
    [void]$sb.AppendLine("$Label cue $cue — 中文字幕")
    [void]$sb.AppendLine()
    $cue++
  }
  [System.IO.File]::WriteAllText($Path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("made: {0}" -f $Path)
}

New-Video -Path (Join-Path $Root "Normal Movie.mp4") -Seconds $LongSeconds -Pattern "normal"
New-Video -Path (Join-Path $Root "Chinese 中文 视频 空格.mp4") -Seconds $LongSeconds -Pattern "chinese"
New-Video -Path (Join-Path $Root "Subs Movie.mp4") -Seconds $LongSeconds -Pattern "subs"
New-MoveableSrt -Path (Join-Path $Root "Subs Movie.srt") -Label "Subs Movie" -Seconds $LongSeconds
New-Video -Path (Join-Path $Root "Season 01\Episode 01.mp4") -Seconds $ShortSeconds -Pattern "episode"
New-MoveableSrt -Path (Join-Path $Root "Season 01\Episode 01.srt") -Label "Episode 01" -Seconds $ShortSeconds
New-Video -Path (Join-Path $Root "norange\NoRange 无范围.mp4") -Seconds $LongSeconds -Pattern "norange"

Write-Host ""
Write-Host "tree:"
Get-ChildItem -Recurse -File $Root | ForEach-Object {
  Write-Host ("  {0}  ({1:N0} bytes)" -f $_.FullName.Substring($Root.Length).TrimStart('\\'), $_.Length)
}

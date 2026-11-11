<#
.SYNOPSIS
Generates the media fixture tree used to validate the Windows client against a real media server.

.DESCRIPTION
The fixtures are synthesised with ffmpeg rather than downloaded, so they carry no third-party
rights and the tree can be rebuilt byte-for-byte from this script. They deliberately cover the
shapes that change client behaviour:

  - H.264/AAC at two resolutions, and HEVC, so codec and transcode decisions differ per item
  - one item with an EMBEDDED subtitle track and two EXTERNAL subtitle files, because those are
    discovered by completely different code paths
  - a multi-season series with unequal episode durations, so end-of-item and auto-advance can be
    exercised without waiting for a real 45-minute episode
  - one longer, higher-bitrate movie, so Range requests and seeking have something to seek within

The output is intentionally outside the repository: these are build inputs, not source.

.EXAMPLE
pwsh -File windows/tools/make-test-media.ps1 -Destination D:\mh-media -Force
#>
[CmdletBinding()]
param(
    [string] $Destination = 'D:\mh-media',
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'ffmpeg is not on PATH' }

$dest = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path $dest) {
    if (-not $Force) { throw "$dest already exists. Pass -Force to regenerate." }
    Remove-Item -Recurse -Force $dest
}
New-Item -ItemType Directory -Force -Path $dest | Out-Null

function New-Video {
    param(
        [string] $Path,
        [string] $VideoCodec = 'libx264',
        [int]    $Width = 1280,
        [int]    $Height = 720,
        [int]    $Seconds = 8,
        [int]    $Rate = 15,
        [string] $SubtitleFile
    )
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $ext = [System.IO.Path]::GetExtension($Path).ToLowerInvariant()

    $args = @('-hide_banner', '-loglevel', 'error', '-y')
    $args += @('-f', 'lavfi', '-i', "testsrc2=size=$($Width)x$($Height):rate=$($Rate):duration=$Seconds")
    $args += @('-f', 'lavfi', '-i', "sine=frequency=440:sample_rate=48000:duration=$Seconds")

    if ($SubtitleFile) {
        $args += @('-i', $SubtitleFile)
        # Subtitles in Matroska keep their own stream; in MP4 they must be mov_text.
        $args += @('-map', '0:v', '-map', '1:a', '-map', '2:s', '-c:s', $(if ($ext -eq '.mkv') { 'srt' } else { 'mov_text' }))
    }
    else {
        $args += @('-map', '0:v', '-map', '1:a')
    }

    if ($VideoCodec -eq 'libx265') {
        $args += @('-c:v', 'libx265', '-preset', 'ultrafast', '-crf', '34', '-tag:v', 'hvc1')
    }
    else {
        $args += @('-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '30', '-pix_fmt', 'yuv420p')
    }
    $args += @('-c:a', 'aac', '-b:a', '64k')
    if ($ext -eq '.mp4') { $args += @('-movflags', '+faststart') }
    $args += $Path

    & ffmpeg @args
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $Path" }
    $size = (Get-Item $Path).Length
    Write-Host ("  {0,-58} {1,8:N0} bytes" -f (Split-Path -Leaf $Path), $size)
}

function New-Subtitle {
    param([string] $Path, [string] $Text)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $srt = @"
1
00:00:00,500 --> 00:00:03,000
$Text line one

2
00:00:03,500 --> 00:00:06,500
$Text line two
"@
    Set-Content -Path $Path -Value $srt -Encoding UTF8
    Write-Host ("  {0,-58} {1,8:N0} bytes" -f (Split-Path -Leaf $Path), (Get-Item $Path).Length)
}

Write-Host "Generating fixtures in $dest"

# ---------------------------------------------------------------- movies
$movies = Join-Path $dest 'Movies'

Write-Host 'Movies:'
New-Video -Path (Join-Path $movies 'Example Movie (2020)\Example Movie (2020).mp4') -Width 1280 -Height 720 -Seconds 8
New-Subtitle -Path (Join-Path $movies 'Example Movie (2020)\Example Movie (2020).zh.srt') -Text '示例字幕'
New-Subtitle -Path (Join-Path $movies 'Example Movie (2020)\Example Movie (2020).en.srt') -Text 'Example subtitle'

# Longer and higher bitrate so Range requests and seeking have real distance to cover.
New-Video -Path (Join-Path $movies 'Range Movie (2019)\Range Movie (2019).mp4') -Width 1920 -Height 1080 -Seconds 40 -Rate 24

# Embedded subtitle track: discovered from the container, not from the filesystem.
$embeddedSrt = Join-Path $env:TEMP 'mediahub-embedded.srt'
New-Subtitle -Path $embeddedSrt -Text 'Embedded track'
New-Video -Path (Join-Path $movies 'Embedded Subs (2018)\Embedded Subs (2018).mkv') -Width 1280 -Height 720 -Seconds 8 -SubtitleFile $embeddedSrt

New-Video -Path (Join-Path $movies 'Hevc Movie (2021)\Hevc Movie (2021).mp4') -VideoCodec 'libx265' -Width 1920 -Height 1080 -Seconds 5 -Rate 15
New-Video -Path (Join-Path $movies 'Low Res Movie (2022)\Low Res Movie (2022).mp4') -Width 640 -Height 360 -Seconds 6 -Rate 15

# ---------------------------------------------------------------- series
# Unequal durations on purpose: the end-of-item path is then reachable in seconds, and a
# remaining-time prompt can be observed rather than assumed.
Write-Host 'TV:'
$show = Join-Path $dest 'TV\Example Show'
New-Video -Path (Join-Path $show 'Season 01\Example Show S01E01.mp4') -Width 1280 -Height 720 -Seconds 10
New-Video -Path (Join-Path $show 'Season 01\Example Show S01E02.mp4') -Width 1280 -Height 720 -Seconds 12
New-Video -Path (Join-Path $show 'Season 01\Example Show S01E03.mp4') -Width 1280 -Height 720 -Seconds 14
New-Video -Path (Join-Path $show 'Season 02\Example Show S02E01.mp4') -Width 1280 -Height 720 -Seconds 16
New-Video -Path (Join-Path $show 'Season 02\Example Show S02E02.mp4') -Width 1280 -Height 720 -Seconds 18

# A second series so search and library listing have more than one match to choose between.
$show2 = Join-Path $dest 'TV\Second Show'
New-Video -Path (Join-Path $show2 'Season 01\Second Show S01E01.mp4') -Width 1280 -Height 720 -Seconds 9

Write-Host ''
$files = Get-ChildItem -Recurse -File $dest
Write-Host ("total: {0} files, {1:N1} MB" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB))
Write-Host "fixture root: $dest"

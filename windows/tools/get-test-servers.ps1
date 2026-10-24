<#
.SYNOPSIS
Downloads and extracts the official Jellyfin and Emby server builds used for real-server validation.

.DESCRIPTION
Both are the official portable builds, so no system service is installed and nothing is left behind
on the machine beyond the extraction directory. The exact version and the SHA-256 of every downloaded
artifact are recorded into a versions file next to the servers: a server version that is only
remembered in a report is not reproducible, and an unhashed download is not evidence.

Sources:
  Jellyfin - https://repo.jellyfin.org/files/server/windows/stable/<version>/amd64/jellyfin_<version>-amd64.zip
  Emby     - https://github.com/MediaBrowser/Emby.Releases (official release repository)

.PARAMETER Destination
Where the servers are extracted. Defaults to D:\mh-servers, outside the repository.

.EXAMPLE
pwsh -File windows/tools/get-test-servers.ps1
#>
[CmdletBinding()]
param(
    [string] $Destination = 'D:\mh-servers',
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'

$JellyfinVersion = '12.2'
$JellyfinUrl     = "https://repo.jellyfin.org/files/server/windows/stable/v$JellyfinVersion/amd64/jellyfin_$JellyfinVersion-amd64.zip"
$EmbyTag         = '4.10.1.0'
$EmbyUrl         = "https://github.com/MediaBrowser/Emby.Releases/releases/download/$EmbyTag/embyserver-win-x64-$EmbyTag.7z"

$dest = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$cache = Join-Path $dest '_downloads'
New-Item -ItemType Directory -Force -Path $cache | Out-Null

function Get-Artifact {
    param([string] $Url, [string] $Name)
    $path = Join-Path $cache $Name
    if ((Test-Path $path) -and -not $Force) {
        Write-Host "  cached  $Name"
    }
    else {
        Write-Host "  fetching $Name"
        Invoke-WebRequest -Uri $Url -OutFile $path -UseBasicParsing -TimeoutSec 3600
    }
    return $path
}

# 7zr is a 7z-ONLY extractor: it cannot open a .zip at all, and it fails with "Cannot open the
# file as archive" rather than something that names the real problem. Zip archives therefore go
# through Expand-Archive, and only the Emby .7z uses 7zr.
$sevenZip = Join-Path $env:TEMP '7zr.exe'
if (-not (Test-Path $sevenZip)) {
    Invoke-WebRequest -Uri 'https://www.7-zip.org/a/7zr.exe' -OutFile $sevenZip -UseBasicParsing -TimeoutSec 300
}

$records = @()

# ---------------------------------------------------------------- jellyfin
$jfDir = Join-Path $dest 'jellyfin'
$jfZip = Get-Artifact -Url $JellyfinUrl -Name "jellyfin_$JellyfinVersion-amd64.zip"
if (-not (Test-Path (Join-Path $jfDir 'jellyfin.exe')) -or $Force) {
    if (Test-Path $jfDir) { Remove-Item -Recurse -Force $jfDir }
    New-Item -ItemType Directory -Force -Path $jfDir | Out-Null
    Write-Host '  extracting jellyfin'
    Expand-Archive -Path $jfZip -DestinationPath $jfDir -Force
}
$records += [pscustomobject]@{
    Server = 'Jellyfin'; Version = $JellyfinVersion
    Source = $JellyfinUrl; Sha256 = (Get-FileHash -Algorithm SHA256 $jfZip).Hash.ToLowerInvariant()
    Bytes = (Get-Item $jfZip).Length
}

# ---------------------------------------------------------------- emby
$embyDir = Join-Path $dest 'emby'
$emby7z = Get-Artifact -Url $EmbyUrl -Name "embyserver-win-x64-$EmbyTag.7z"
if (-not (Test-Path (Join-Path $embyDir 'system\EmbyServer.exe')) -or $Force) {
    if (Test-Path $embyDir) { Remove-Item -Recurse -Force $embyDir }
    New-Item -ItemType Directory -Force -Path $embyDir | Out-Null
    Write-Host '  extracting emby'
    & $sevenZip x -y "-o$embyDir" $emby7z | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'extraction failed for emby' }
}
$records += [pscustomobject]@{
    Server = 'Emby'; Version = $EmbyTag
    Source = $EmbyUrl; Sha256 = (Get-FileHash -Algorithm SHA256 $emby7z).Hash.ToLowerInvariant()
    Bytes = (Get-Item $emby7z).Length
}

Write-Host ''
$records | Format-Table -AutoSize | Out-String | Write-Host

# The version record is written to disk so the report can cite a file rather than a recollection.
$recordPath = Join-Path $dest 'SERVER-VERSIONS.json'
$records | ConvertTo-Json -Depth 4 | Set-Content -Path $recordPath -Encoding UTF8
Write-Host "versions recorded: $recordPath"

Write-Host ''
$jfExe = Get-ChildItem -Path $jfDir -Filter 'jellyfin.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
$emExe = Get-ChildItem -Path $embyDir -Filter 'EmbyServer.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
# The zip nests everything under a single top-level directory, so the executable is located rather
# than assumed to be at the extraction root.
Write-Host 'jellyfin.exe  : ' ($jfExe -ne $null) ' ' $jfExe.FullName
Write-Host 'EmbyServer.exe: ' ($emExe -ne $null) ' ' $emExe.FullName

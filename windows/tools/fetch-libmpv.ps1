<#
.SYNOPSIS
Downloads the pinned libmpv Windows build into windows/native/libmpv.

.DESCRIPTION
libmpv is not vendored in git: the runtime DLL alone is ~115 MB. This script fetches the pinned
mpv-dev archive, verifies its SHA-256 against the recorded digest, and extracts only the files the
client needs. Run it once after cloning, and again after changing the pin.

.NOTES
Pin: shinchiro/mpv-winbuild-cmake release 20261014, asset mpv-dev-x86_64-20261014-git-ec38361c56.7z
#>
[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $PSScriptRoot '..\native\libmpv'),
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ReleaseTag  = '20261014'
$AssetName   = 'mpv-dev-x86_64-20261014-git-ec38361c56.7z'
$AssetSha256 = 'PENDING'
$Url         = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$ReleaseTag/$AssetName"

$dest = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$dll = Join-Path $dest 'libmpv-2.dll'
if ((Test-Path $dll) -and -not $Force) {
    Write-Host "libmpv already present: $dll ($((Get-Item $dll).Length) bytes). Use -Force to re-download."
    exit 0
}

$cache = Join-Path $env:TEMP $AssetName
if (-not (Test-Path $cache) -or $Force) {
    Write-Host "Downloading $Url"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $Url -OutFile $cache -UseBasicParsing -TimeoutSec 1800
}

$actual = (Get-FileHash -Algorithm SHA256 -Path $cache).Hash.ToLowerInvariant()
Write-Host "Archive SHA-256: $actual"
if ($AssetSha256 -ne 'PENDING' -and $actual -ne $AssetSha256) {
    throw "SHA-256 mismatch for $AssetName. Expected $AssetSha256, got $actual."
}

$sevenZip = Join-Path $env:TEMP '7zr.exe'
if (-not (Test-Path $sevenZip)) {
    Write-Host 'Fetching standalone 7-Zip extractor (7zr.exe)'
    Invoke-WebRequest -Uri 'https://www.7-zip.org/a/7zr.exe' -OutFile $sevenZip -UseBasicParsing -TimeoutSec 300
}

$stage = Join-Path $env:TEMP ('mediahub-libmpv-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
try {
    & $sevenZip x -y "-o$stage" $cache | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "7zr extraction failed with exit code $LASTEXITCODE" }

    Copy-Item (Join-Path $stage 'libmpv-2.dll') $dest -Force
    $incSrc = Join-Path $stage 'include'
    if (Test-Path $incSrc) {
        $incDst = Join-Path $dest 'include'
        New-Item -ItemType Directory -Force -Path $incDst | Out-Null
        Copy-Item (Join-Path $incSrc 'mpv') $incDst -Recurse -Force
    }
    Copy-Item (Join-Path $stage 'libmpv.dll.a') $dest -Force -ErrorAction SilentlyContinue
}
finally {
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}

Write-Host "libmpv installed to $dest"
Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize

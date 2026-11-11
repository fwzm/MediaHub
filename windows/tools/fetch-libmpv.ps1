<#
.SYNOPSIS
Downloads the pinned libmpv Windows build into windows/native/libmpv.

.DESCRIPTION
libmpv is not vendored in git: the runtime DLL alone is ~115 MB. This script fetches the pinned
mpv-dev archive, verifies BOTH the archive digest and the digest of the extracted runtime against
recorded values, and only then installs it.

Why two digests: a release asset on a third-party GitHub repo can be replaced in place, so the
download URL is not by itself a trustworthy identity. Pinning the archive digest detects a swapped
archive; pinning the extracted DLL digest additionally detects an extraction step that produced
something other than the reviewed binary.

NOTE ON LICENSING: this build reports "gpl" in mpv's own list of enabled features, so it is
distributed under the GNU GPL, not the LGPL. See windows/THIRD-PARTY-NOTICES.md. Distributing a
product that contains this binary obliges the distributor to satisfy the GPL.

.NOTES
Pin source : shinchiro/mpv-winbuild-cmake, release 20261014
Asset      : mpv-dev-x86_64-20261014-git-ec38361c56.7z
Engine     : mpv v0.41.0-1154-gec38361c5 (ffmpeg N-127521-g2ec8998c3)
#>
[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $PSScriptRoot '..\native\libmpv'),
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ReleaseTag   = '20261014'
$AssetName    = 'mpv-dev-x86_64-20261014-git-ec38361c56.7z'
$AssetSha256  = '325555b4e0b1d87fb7c92c96010776581599c64e4988052cb9d05e130b52f939'
$DllSha256    = '6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9'
$Url          = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$ReleaseTag/$AssetName"

$dest = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$dll = Join-Path $dest 'libmpv-2.dll'

function Test-DllDigest {
    param([string] $Path)
    if (-not (Test-Path $Path)) { return $false }
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant() -eq $DllSha256
}

if ((Test-Path $dll) -and -not $Force) {
    if (Test-DllDigest $dll) {
        Write-Host "libmpv already present and verified: $dll ($((Get-Item $dll).Length) bytes)"
        exit 0
    }
    Write-Warning "Existing libmpv-2.dll does not match the pinned digest; re-downloading. Remove it manually if this is unexpected."
}

$cache = Join-Path $env:TEMP $AssetName
$cacheValid = (Test-Path $cache) -and ((Get-FileHash -Algorithm SHA256 -Path $cache).Hash.ToLowerInvariant() -eq $AssetSha256)

if (-not $cacheValid -or $Force) {
    Write-Host "Downloading $Url"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $Url -OutFile $cache -UseBasicParsing -TimeoutSec 1800
}

$actual = (Get-FileHash -Algorithm SHA256 -Path $cache).Hash.ToLowerInvariant()
Write-Host "archive sha256 : $actual"
if ($actual -ne $AssetSha256) {
    throw "SHA-256 mismatch for $AssetName. Expected $AssetSha256, got $actual. Refusing to extract."
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

    $stagedDll = Join-Path $stage 'libmpv-2.dll'
    $stagedHash = (Get-FileHash -Algorithm SHA256 -Path $stagedDll).Hash.ToLowerInvariant()
    Write-Host "dll sha256     : $stagedHash"
    if ($stagedHash -ne $DllSha256) {
        throw "SHA-256 mismatch for the extracted libmpv-2.dll. Expected $DllSha256, got $stagedHash. Refusing to install."
    }

    Copy-Item $stagedDll $dest -Force
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

if (-not (Test-DllDigest $dll)) { throw "installed libmpv-2.dll failed verification" }
Write-Host "libmpv installed and verified in $dest"
Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize

#Requires -Version 7.0
<#
.SYNOPSIS
    Builds the MediaHub per-user install kit: a Release publish plus the installer scripts, zipped.

.DESCRIPTION
    This is the only step that turns the repository into something a user can install. It runs the
    same Release publish CI runs, stages the licence/notice files the package must carry, copies
    windows/installer/{install.ps1,install.cmd,uninstall.ps1} beside the payload, and zips the result
    as MediaHub-Setup-win-x64-<version>.zip with a SHA-256 file next to it.

    No third-party packaging tool is used or required: dotnet publish, stage-licence-files.ps1 and
    Compress-Archive all ship with the SDK / Windows.

    The kit is unsigned. Windows SmartScreen will warn on first run of the extracted
    install.cmd/install.ps1 because the payload executables have no Authenticode signature; signing is
    a separate, certificate-dependent decision (windows/docs/adr/ADR-W007,
    windows/docs/licensing/PACKAGING.md section 9). Nothing here pretends otherwise.

.PARAMETER OutputDir
    Where the kit directory and zip are written. Default: windows/artifacts/setup (git-ignored).

.PARAMETER Configuration
    Build configuration. Default: Release.

.PARAMETER Platform
    Target platform. Default: x64. arm64 is not supported: the engine binary is x64 only.

.PARAMETER Version
    Product version to stamp into MediaHub.exe, e.g. 1.1.0. Empty keeps the SDK default. The update
    drill uses this to produce a newer payload from the same source tree.

.PARAMETER NoZip
    Leave the kit as a directory and skip Compress-Archive and update.json.

.EXAMPLE
    pwsh -NoProfile -File windows/tools/make-install-kit.ps1

.NOTES
    Exit codes: 0 = kit produced, 1 = publish/staging failed, 2 = the produced tree is incomplete.
#>
[CmdletBinding()]
param(
    [string] $OutputDir,
    [string] $Configuration = 'Release',
    [string] $Platform = 'x64',
    [string] $Version,
    [switch] $NoZip
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutputDir) { $OutputDir = Join-Path $PSScriptRoot '..\artifacts\setup' }
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$stagingRoot = Join-Path $OutputDir 'staging'
$appDir = Join-Path $stagingRoot 'app'

function Fail {
    param([int] $Code, [string] $Text)
    Write-Host ('kit: FAILED - ' + $Text) -ForegroundColor Red
    exit $Code
}

if ($Platform -ne 'x64') {
    Fail 1 ("platform '" + $Platform + "' is not supported: windows/native/libmpv and windows/engine/lgpl/dist ship x64 binaries only")
}

$project = Join-Path $repoRoot 'windows\src\MediaHub.App\MediaHub.App.csproj'
if (-not (Test-Path -LiteralPath $project)) { Fail 1 ('app project not found: ' + $project) }

if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $appDir | Out-Null

Write-Host ('kit: publishing ' + $Configuration + ' ' + $Platform + ' -> ' + $appDir)
Push-Location (Join-Path $repoRoot 'windows')
try {
    $publishArgs = @('publish', 'src/MediaHub.App/MediaHub.App.csproj', '-c', $Configuration, "-p:Platform=$Platform", '-o', $appDir)
    if ($Version) { $publishArgs += "-p:Version=$Version" }
    & dotnet @publishArgs
    if ($LASTEXITCODE -ne 0) { Fail 1 ('dotnet publish exited with ' + $LASTEXITCODE) }
}
finally {
    Pop-Location
}

Write-Host 'kit: staging licence and notice files'
& (Join-Path $PSScriptRoot 'stage-licence-files.ps1') -RepoRoot $repoRoot -TargetDir $appDir | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 1 ('stage-licence-files.ps1 exited with ' + $LASTEXITCODE) }

# The Windows App SDK references its optional ML slice, so every publish carries DirectML + ONNX
# Runtime (~43 MB) even though MediaHub has no WinML/ONNX call site. It is dropped from the kit:
# nothing loads it, the app starts and restores its sources without it (verified on the test
# machine), and an unused GPU inference runtime is not something an installer should push onto a
# user's disk.
$mlRuntime = @(Get-ChildItem -LiteralPath $appDir -File | Where-Object { $_.Name -match '^(DirectML|onnxruntime|Microsoft\.ML\.OnnxRuntime|Microsoft\.Windows\.AI\.MachineLearning)' })
if ($mlRuntime.Count -gt 0) {
    $mlBytes = ($mlRuntime | Measure-Object -Sum Length).Sum
    foreach ($file in $mlRuntime) { Remove-Item -LiteralPath $file.FullName -Force }
    Write-Host ('kit: excluded ' + $mlRuntime.Count + ' Windows App SDK ML file(s), ' + [math]::Round($mlBytes / 1MB, 1) + ' MiB (unused by MediaHub)')
}

foreach ($name in @('install.ps1', 'uninstall.ps1', 'install.cmd', 'update.ps1')) {
    $source = Join-Path $repoRoot ('windows\installer\' + $name)
    if (-not (Test-Path -LiteralPath $source)) { Fail 1 ('installer file missing: ' + $source) }
    Copy-Item -LiteralPath $source -Destination $stagingRoot -Force
}

$exe = Join-Path $appDir 'MediaHub.exe'
$engine = Join-Path $appDir 'libmpv-2.dll'
foreach ($required in @($exe, $engine, (Join-Path $appDir 'MediaHub.dll'), (Join-Path $appDir 'licences\MANIFEST.txt'))) {
    if (-not (Test-Path -LiteralPath $required)) { Fail 2 ('the produced tree is incomplete: ' + $required) }
}

$version = ((Get-Item -LiteralPath $exe).VersionInfo.ProductVersion -split '\+')[0]
if (-not $version) { $version = '0.0.0' }
$kitName = 'MediaHub-Setup-win-x64-' + $version
$kitDir = Join-Path $OutputDir $kitName
if (Test-Path -LiteralPath $kitDir) { Remove-Item -LiteralPath $kitDir -Recurse -Force }
Move-Item -LiteralPath $stagingRoot -Destination $kitDir

$fileCount = (Get-ChildItem -LiteralPath $kitDir -Recurse -File | Measure-Object).Count
$totalBytes = (Get-ChildItem -LiteralPath $kitDir -Recurse -File | Measure-Object -Sum Length).Sum
$engineHash = (Get-FileHash -LiteralPath (Join-Path $kitDir 'app\libmpv-2.dll') -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host ('kit: version    ' + $version)
Write-Host ('kit: directory  ' + $kitDir)
Write-Host ('kit: files      ' + $fileCount + ' (' + [math]::Round($totalBytes / 1MB, 1) + ' MiB)')
Write-Host ('kit: engine     sha256 ' + $engineHash)

if (-not $NoZip) {
    $zipPath = Join-Path $OutputDir ($kitName + '.zip')
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Compress-Archive -Path $kitDir -DestinationPath $zipPath -CompressionLevel Optimal
    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $zipBytes = (Get-Item -LiteralPath $zipPath).Length
    $sums = Join-Path $OutputDir ($kitName + '.zip.sha256')
    [System.IO.File]::WriteAllText($sums, ($zipHash + '  ' + (Split-Path -Leaf $zipPath) + [Environment]::NewLine))
    Write-Host ('kit: zip        ' + $zipPath + ' (' + [math]::Round($zipBytes / 1MB, 1) + ' MiB)')
    Write-Host ('kit: zip sha256 ' + $zipHash)

    # The zip plus this manifest is already a usable static update source: point the app at the
    # directory (or serve it over HTTP) and the update path has everything it verifies against.
    $manifestPath = Join-Path $OutputDir 'update.json'
    $manifest = [ordered]@{
        version   = $version
        package   = (Split-Path -Leaf $zipPath)
        sha256    = $zipHash
        size      = $zipBytes
        published = (Get-Date).ToUniversalTime().ToString('o')
        notes     = ''
    }
    [System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json) + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ('kit: manifest   ' + $manifestPath)
}

Write-Host ('RESULT: KIT ' + $kitDir)
exit 0

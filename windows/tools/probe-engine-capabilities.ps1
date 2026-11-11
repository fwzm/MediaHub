<#
.SYNOPSIS
Probes an mpv engine build for the capabilities MediaHub actually requests. Headless: no window,
no audio device, no input file.

.DESCRIPTION
MediaHub pins --gpu-api=d3d11 and defaults --gpu-context=d3d11 / --vo=gpu-next, so the question
"is Vulkan required?" is answered by what the product asks for, not by what the engine could do.
This script records the engine side of that question: the values the binary advertises for the four
options the player sets, plus the enabled-feature list recorded next to the binary.

Every query is an option-help request: mpv prints the accepted values while parsing arguments and
exits before any window or audio device is created. Exit codes: 0 = observation complete and every
product-required capability present; 1 = a product-required capability is missing; 2 = usage error;
3 = engine binary missing. The Vulkan answer is information, not a failure condition: it is absent in
the LGPL candidate (see windows/docs/licensing/VULKAN-NECESSITY.md).
#>
[CmdletBinding()]
param(
    [string] $RepoRoot = "",
    [string] $EnginePath = "",
    [string] $FeatureListPath = "",
    [string] $OutFile = "",
    [switch] $Json
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($EnginePath)) {
    $EnginePath = Join-Path $RepoRoot "windows/engine/lgpl/dist/mpv.exe"
}
if ([string]::IsNullOrWhiteSpace($FeatureListPath)) {
    $FeatureListPath = Join-Path $RepoRoot "windows/engine/lgpl/mpv-lgpl-features.txt"
}
if (-not (Test-Path -LiteralPath $EnginePath -PathType Leaf)) {
    Write-Output ("probe-engine-capabilities  ENGINE MISSING  " + $EnginePath)
    exit 3
}

function Invoke-MpvHelp {
    param([string] $Exe, [string] $Arguments)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = $Arguments
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    if (-not $proc.WaitForExit(20000)) {
        $proc.Kill()
        return @{ ok = $false; text = ""; timedOut = $true }
    }
    return @{ ok = $true; text = ($stdout + [Environment]::NewLine + $stderr); timedOut = $false }
}

function Get-AdvertisedValues {
    param([string] $Text)
    $values = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($Text -split "[\r\n]+")) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) { continue }
        if ($trimmed.EndsWith(":")) { continue }
        $token = ($trimmed -split "\s+")[0]
        if ($token -match "^[A-Za-z0-9][A-Za-z0-9_.-]*$") {
            if (-not $values.Contains($token)) { [void]$values.Add($token) }
        }
    }
    return $values.ToArray()
}

function Get-FeatureTokens {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $text = Get-Content -LiteralPath $Path -Raw
    $match = [regex]::Match($text, "List of enabled features:\s*(?<list>[^\r\n]+)")
    if (-not $match.Success) { return @() }
    return ($match.Groups["list"].Value.Trim() -split "\s+") | Where-Object { $_.Length -gt 0 }
}

$version = Invoke-MpvHelp -Exe $EnginePath -Arguments "--version"
$gpuApi = Invoke-MpvHelp -Exe $EnginePath -Arguments "--no-config --gpu-api=help"
$gpuContext = Invoke-MpvHelp -Exe $EnginePath -Arguments "--no-config --gpu-context=help"
$vo = Invoke-MpvHelp -Exe $EnginePath -Arguments "--no-config --vo=help"
$hwdec = Invoke-MpvHelp -Exe $EnginePath -Arguments "--no-config --hwdec=help"

$versionLine = (($version.text -split "[\r\n]+") | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1)
$gpuApiValues = Get-AdvertisedValues -Text $gpuApi.text
$gpuContextValues = Get-AdvertisedValues -Text $gpuContext.text
$voValues = Get-AdvertisedValues -Text $vo.text
$hwdecValues = Get-AdvertisedValues -Text $hwdec.text
$features = @(Get-FeatureTokens -Path $FeatureListPath)
$engineHash = (Get-FileHash -LiteralPath $EnginePath -Algorithm SHA256).Hash.ToLowerInvariant()

$required = [ordered]@{
    "gpu-api=d3d11" = ($gpuApiValues -contains "d3d11");
    "gpu-context=d3d11" = ($gpuContextValues -contains "d3d11");
    "vo=gpu-next" = ($voValues -contains "gpu-next");
    "hwdec=d3d11va" = ($hwdecValues -contains "d3d11va");
    "feature=d3d11" = ($features -contains "d3d11");
    "feature=d3d-hwaccel" = ($features -contains "d3d-hwaccel");
    "feature=libplacebo" = ($features -contains "libplacebo")
}
$missing = @($required.Keys | Where-Object { -not $required[$_] })
$vulkanApi = ($gpuApiValues -contains "vulkan")
$vulkanContext = ($gpuContextValues -contains "winvk") -or ($gpuContextValues -contains "vulkan")
$vulkanFeature = ($features -contains "vulkan")
$vulkan = ($vulkanApi -or $vulkanContext -or $vulkanFeature)

$result = @{
    engine = $EnginePath;
    engineSha256 = $engineHash;
    engineVersion = $versionLine;
    featureListPath = $FeatureListPath;
    featureCount = $features.Count;
    gpuApi = @($gpuApiValues);
    gpuContext = @($gpuContextValues);
    vo = @($voValues);
    hwdec = @($hwdecValues);
    features = @($features);
    required = @($required.GetEnumerator() | ForEach-Object { @{ name = $_.Key; present = $_.Value } });
    missingRequired = @($missing);
    vulkanAdvertised = $vulkan;
    timedOut = @($version.timedOut, $gpuApi.timedOut, $gpuContext.timedOut, $vo.timedOut, $hwdec.timedOut) -contains $true
}

if ($OutFile) {
    $json = ($result | ConvertTo-Json -Depth 5)
    [System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))
}
if ($Json) { $result | ConvertTo-Json -Depth 5 }
else {
    Write-Output "probe-engine-capabilities"
    Write-Output ("engine          : " + $EnginePath)
    Write-Output ("engine sha256   : " + $engineHash)
    Write-Output ("engine version  : " + $versionLine)
    Write-Output ("feature source  : " + $FeatureListPath + " (" + $features.Count + " tokens)")
    Write-Output ("gpu-api         : " + ($gpuApiValues -join ", "))
    Write-Output ("gpu-context     : " + ($gpuContextValues -join ", "))
    Write-Output ("vo              : " + ($voValues -join ", "))
    Write-Output ("hwdec           : " + (($hwdecValues | Select-Object -First 8) -join ", ") + " (+" + [Math]::Max(0, $hwdecValues.Count - 8) + " more)")
    Write-Output ("features        : d3d11=" + ($features -contains "d3d11") + " d3d-hwaccel=" + ($features -contains "d3d-hwaccel") + " libplacebo=" + ($features -contains "libplacebo") + " vulkan=" + $vulkanFeature)
    foreach ($key in $required.Keys) {
        $outcome = if ($required[$key]) { "PASS" } else { "FAIL" }
        Write-Output ("required        : " + $outcome + "  " + $key)
    }
    $vulkanNote = if ($vulkan) { "advertised by this build" } else { "absent from this build (gpu-api, gpu-context and features)" }
    Write-Output ("vulkan          : " + $vulkanNote + " - see windows/docs/licensing/VULKAN-NECESSITY.md")
    if ($missing.Count -gt 0) { Write-Output ("RESULT: FAIL (missing: " + ($missing -join ", ") + ")") }
    else { Write-Output "RESULT: PASS (every product-required capability is advertised)" }
}
if ($missing.Count -gt 0) { exit 1 }
exit 0

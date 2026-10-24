<#
.SYNOPSIS
Runs every W5-E GUI acceptance area and aggregates the results.

.DESCRIPTION
The areas are NOT run in parallel. Each one drives the single client instance with synthetic input,
and a concurrent foreground window silently swallows keystrokes - which is exactly how an earlier
run produced a page of product-looking failures that were only a focus problem. Run them one at a
time, or not at all.

Areas 04 (file menu / drag-and-drop / queue) is listed but is not implemented in this round; the
aggregate output says so explicitly rather than quietly omitting it.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence'),
  [string[]]$Areas = @('02-playback-controls', '03-fullscreen', '01-lifecycle', '05-resource-release')
)
$ErrorActionPreference = 'Stop'
$summary = @()
foreach ($area in $Areas) {
  $script = Join-Path $PSScriptRoot ($area + '.ps1')
  if (-not (Test-Path $script)) {
    $summary += [pscustomobject]@{ area = $area; result = 'not-implemented'; pass = 0; fail = 0; notVerified = 0 }
    Write-Host ("SKIP  $area (script not present)") -ForegroundColor Yellow
    continue
  }
  Write-Host ''
  Write-Host ("===== $area =====") -ForegroundColor Cyan
  & pwsh -NoProfile -File $script -Exe $Exe -EvidenceRoot $EvidenceRoot | Out-Host
  $json = Join-Path (Join-Path $EvidenceRoot $area) 'results.json'
  if (Test-Path $json) {
    $r = Get-Content $json -Raw | ConvertFrom-Json
    $summary += [pscustomobject]@{ area = $area; result = 'ran'; pass = $r.summary.pass; fail = $r.summary.fail; notVerified = $r.summary.'not-verified' }
  } else {
    $summary += [pscustomobject]@{ area = $area; result = 'no-results'; pass = 0; fail = 0; notVerified = 0 }
  }
}

$summary | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
$out = Join-Path $EvidenceRoot 'w5e-summary.json'
$summary | ConvertTo-Json | Set-Content -Path $out -Encoding UTF8
Write-Host ("aggregate written to $out")

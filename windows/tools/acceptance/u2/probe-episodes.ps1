# U2 布局勘察 2（临时）：媒体库 → MHTest TV → Example Show → Season 1 → 剧集卡片 → 点开看是详情页还是直接播放
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$out = $R + 'windows\tools\acceptance\evidence\u2-autoplay\probe'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$exe      = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'

function Dump([string]$tag) {
  $lines = New-Object System.Collections.Generic.List[string]
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    try {
      $c = $e.Current
      $id = $c.AutomationId; $n = $c.Name
      if ([string]::IsNullOrEmpty($id) -and [string]::IsNullOrEmpty($n)) { continue }
      $ct = ''
      try { $ct = Get-MhControlType $e } catch { $ct = '?' }
      $nn = if ($null -eq $n) { '<null>' } else { $n }
      if ($nn.Length -gt 80) { $nn = $nn.Substring(0, 80) }
      $lines.Add(('{0} | {1} | {2}' -f $ct, $id, $nn))
    } catch { }
  }
  $p = Join-Path $out ('tree-' + $tag + '.txt')
  $lines | Set-Content -Path $p -Encoding UTF8
  Write-Host ('--- ' + $tag + ': ' + $lines.Count + ' nodes -> ' + $p)
  foreach ($l in $lines) {
    if ($l -match '\| (library\||media:|detail\.|SeekSlider|TitleText|EpisodeList)') { Write-Host ('    ' + $l) }
  }
  return $lines
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if ($item) { Invoke-MhElement -Element $item | Out-Null; Start-Sleep -Milliseconds 1500 }
  return ($null -ne $item)
}
function ClickByName([string]$name) {
  $el = Find-MhAppElement -Name $name -IncludeOffscreen
  if (-not $el) { Write-Host ('  MISS name=' + $name); return $false }
  Write-Host ('  click name=' + $el.Current.Name + ' id=' + $el.Current.AutomationId)
  try { Invoke-MhElementDeep -Element $el | Out-Null } catch { Write-Host ('  click error: ' + $_.Exception.Message) }
  return $true
}

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Write-Host ('pid=' + $app.pid)
Start-Sleep -Seconds 4
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }

Go-Nav '媒体库' | Out-Null; Start-Sleep -Seconds 2
if (ClickByName 'MHTest TV') { Start-Sleep -Seconds 4 }
Dump '10-tv-library' | Out-Null
if (ClickByName 'Example Show') { Start-Sleep -Seconds 4 }
Dump '11-series' | Out-Null
if (ClickByName 'Season 1') { Start-Sleep -Seconds 4 }
Dump '12-season1' | Out-Null
Save-MhScreenshot -Path (Join-Path $out '12-season1.png') | Out-Null

# 点第一集卡片
$ep = Find-MhAppElement -AutomationId ('media:emby:1271d0a1040046db8c0089cc328bbbf1:15') -IncludeOffscreen
if (-not $ep) { Write-Host '  no media:...:15 card, trying by name S01E01'; $ep = Find-MhAppElement -Name 'Example Show S01E01' -IncludeOffscreen }
if ($ep) {
  Write-Host ('  click episode id=' + $ep.Current.AutomationId + ' name=' + $ep.Current.Name)
  try { Invoke-MhElementDeep -Element $ep | Out-Null } catch { Write-Host ('  click error: ' + $_.Exception.Message) }
  Start-Sleep -Seconds 5
  Dump '13-after-episode-click' | Out-Null
  Save-MhScreenshot -Path (Join-Path $out '13-after-episode-click.png') | Out-Null
  $st = Get-MhPlayerState
  Write-Host ('player state: title=' + $st.title + ' duration=' + $st.duration + ' position=' + $st.position)
} else { Write-Host '  NO EPISODE CARD' }
Write-Host '--- trace ---'
(Get-MhLog) | ForEach-Object { Write-Host ('  ' + $_) }
Stop-MhAppForced | Out-Null
Write-Host 'PROBE2 DONE'
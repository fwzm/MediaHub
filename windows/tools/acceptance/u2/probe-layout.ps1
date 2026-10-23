# U2 布局勘察（临时辅助脚本，不参与门禁）：把「媒体库 → 剧集 → 播放页」的真实 UIA 结构落盘，
# 供 autoplay-gui.ps1 写选择器时对照。只读 UI，不改 src、不改服务器数据（除进度复位）。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$out = $R + 'windows\tools\acceptance\evidence\u2-autoplay\probe'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$exe      = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$base = 'http://127.0.0.1:8097'; $user = 'mh'; $pass = 'mh-test-pass'
$serverId = '1271d0a1040046db8c0089cc328bbbf1'

function Dump([string]$tag, [string]$filter = '') {
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
    if ($l -match '\| (library\||media:|browse\||detail\.|SeekSlider|TitleText)') { Write-Host ('    ' + $l) }
    elseif ($l -match 'ListItem') { Write-Host ('    ' + $l) }
    elseif ($l -match 'ComboBox|Combo|ListView|GridView') { Write-Host ('    ' + $l) }
  }
  return $lines
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if ($item) { Invoke-MhElement -Element $item | Out-Null; Start-Sleep -Milliseconds 1500 }
  return ($null -ne $item)
}

$hdr = 'MediaBrowser Client="MediaHub-U2", Device="probe", DeviceId="u2-autoplay", Version="1.0.0"'
$login = Invoke-RestMethod -Method POST -Uri ($base + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $hdr } -Body (@{ Username = $user; Pw = $pass } | ConvertTo-Json -Compress) -TimeoutSec 20
$token = $login.AccessToken; $uid = $login.User.Id
Write-Host ('login ok uid=' + $uid)

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$raw = Get-Content $settings -Raw | ConvertFrom-Json
$raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like 'emby:*' })
$raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
& $seed emby $base $user $pass $serverId $token $uid 2>&1 | ForEach-Object { Write-Host ('seed: ' + $_) }
$raw2 = Get-Content $settings -Raw | ConvertFrom-Json
Write-Host ('servers now: ' + (@($raw2.Servers | ForEach-Object { $_.ServerId }) -join ', '))

$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Write-Host ('pid=' + $app.pid)
Start-Sleep -Seconds 4
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
Dump '00-home'

Write-Host ('nav 媒体库: ' + (Go-Nav '媒体库'))
Start-Sleep -Seconds 2
Dump '01-libraries'

$lib = Find-MhAppElement -Name 'MHTest TV' -IncludeOffscreen
Write-Host ('library card MHTest TV found: ' + ($null -ne $lib) + ' id=' + $(if ($lib) { $lib.Current.AutomationId } else { '' }))
if ($lib) { Invoke-MhElementDeep -Element $lib | Out-Null; Start-Sleep -Seconds 4 }
Dump '02-tv-library'

$series = Find-MhAppElement -Name 'Example Show' -IncludeOffscreen
Write-Host ('series card found: ' + ($null -ne $series) + ' id=' + $(if ($series) { $series.Current.AutomationId } else { '' }))
if ($series) { Invoke-MhElementDeep -Element $series | Out-Null; Start-Sleep -Seconds 5 }
Dump '03-series-detail'
Save-MhScreenshot -Path (Join-Path $out '03-series-detail.png') | Out-Null

# 剧集行
$rows = @()
$list = Find-MhAppElement -AutomationId 'EpisodeList' -IncludeOffscreen
if ($list) {
  $rows = @($list.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) | Where-Object { (Get-MhControlType $_) -eq 'ListItem' })
}
Write-Host ('EpisodeList rows: ' + $rows.Count)
foreach ($r in $rows) { Write-Host ('  row name=' + $r.Current.Name + ' id=' + $r.Current.AutomationId) }

if ($rows.Count -gt 0) {
  $r0 = $rows | Where-Object { $_.Current.Name -match 'E01|S01E01' } | Select-Object -First 1
  if (-not $r0) { $r0 = $rows[0] }
  Write-Host ('clicking row: ' + $r0.Current.Name)
  try { Invoke-MhElementDeep -Element $r0 | Out-Null } catch { Write-Host ('row click error: ' + $_.Exception.Message) }
  Start-Sleep -Seconds 6
  Dump '04-player'
  $st = Get-MhPlayerState
  Write-Host ('player state: title=' + $st.title + ' duration=' + $st.duration + ' position=' + $st.position + ' seekValue=' + $st.seekValue + ' seekMax=' + $st.seekMax)
  Save-MhScreenshot -Path (Join-Path $out '04-player.png') | Out-Null
}
$trace = Get-MhLog
Write-Host '--- trace ---'
$trace | ForEach-Object { Write-Host ('  ' + $_) }
Stop-MhAppForced | Out-Null
Write-Host 'PROBE DONE'

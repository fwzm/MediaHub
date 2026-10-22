# R7 Gate A3（第一部分）：真实 WsgiDAV 上的逐级导航、面包屑、空目录、中文空格路径播放、
# 导航竞态，以及“关闭整个应用释放回环转发层”（先证存在再证释放）。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\r7-a3-webdav'
New-Item -ItemType Directory -Force -Path $ev | Out-Null
$tapPath = $R + 'windows\tools\acceptance\evidence\webdav\requests.jsonl'

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
function Wait-Text([string]$id, [string]$expected, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-Text $id
    if ($null -ne $last -and $last -eq $expected) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}
function Wait-TextContains([string]$id, [string]$needle, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-Text $id
    if ($null -ne $last -and $last -like ('*' + $needle + '*')) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}
function Get-RowNames {
  $names = @()
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($c.AutomationId -like 'browse:*') { $names += $c.Name }
  }
  return @($names)
}
function Wait-Row([string]$name, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((Get-RowNames) -contains $name) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
function Click-Row([string]$name) {
  $e = $null
  foreach ($x in (Get-MhAppElementAll -IncludeOffscreen)) {
    if ($x.Current.AutomationId -like 'browse:*' -and $x.Current.Name -eq $name) { $e = $x; break }
  }
  if (-not $e) { return 'ROW-NOT-FOUND' }
  # The row's text sits in a DataTemplate body with no pattern; the ListViewItem ancestor is what
  # raises ItemClick. Never a synthetic mouse click: measured to return without navigating.
  return (Invoke-MhElementDeep -Element $e)
}
function Find-IdLike([string]$pattern) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    if ($e.Current.AutomationId -like $pattern) { return $e }
  }
  return $null
}
function Tap-Lines {
  if (-not (Test-Path $tapPath)) { return @() }
  return @(Get-Content -Path $tapPath)
}
function Tap-Since([int]$n) {
  $all = @(Tap-Lines)
  $out = @()
  for ($i = $n; $i -lt $all.Count; $i++) { $out += $all[$i] }
  return $out
}
function Tap-Json($lines) { return @($lines | ForEach-Object { try { $_ | ConvertFrom-Json } catch { $null } } | Where-Object { $_ }) }
function St($rec) {
  # The tap stores the WSGI status *string* ("207 Multi-Status"), not an integer. Comparing it to 200
  # silently never matched, which is how a successful PROPFIND was reported as missing during the
  # first run. Only the leading code is meaningful here.
  if (-not $rec -or -not $rec.status) { return -1 }
  $s = [string]$rec.status
  $m = [regex]::Match($s, '^(\d{3})')
  if ($m.Success) { return [int]$m.Groups[1].Value }
  return -1
}
function Un-Mojibake([string]$text) {
  # PEP 3333 hands WSGI a PATH_INFO decoded as latin-1, so a UTF-8 name arrives as mojibake. Round
  # -tripping the bytes back recovers the real name; this is the only way to assert an encoding
  # claim from the tap, because the percent-encoded form was already decoded before it was logged.
  if (-not $text) { return $text }
  try {
    $bytes = [System.Text.Encoding]::GetEncoding(28591).GetBytes($text)
    return [System.Text.Encoding]::UTF8.GetString($bytes)
  } catch { return $text }
}
function Tap-Text($rows) { return (@($rows | ForEach-Object { $_.method + ' ' + $_.path + ' -> ' + $_.status + ' auth=' + $_.auth }) -join ' | ') }
function Set-AutoPlayNext([bool]$value) {
  $path = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
  $obj = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
  $obj | Add-Member -NotePropertyName AutoPlayNext -NotePropertyValue $value -Force
  ($obj | ConvertTo-Json -Depth 8) | Set-Content -Path $path -Encoding UTF8
}
function Set-ServersEmpty {
  # 只保留单个来源，避免多来源互相干扰；同时不动其它设置。
  param([string]$keepRegex)
  $path = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
  $obj = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
  $servers = @($obj.Servers | Where-Object { $_.ServerId -match $keepRegex })
  $obj | Add-Member -NotePropertyName Servers -NotePropertyValue $servers -Force
  ($obj | ConvertTo-Json -Depth 10) | Set-Content -Path $path -Encoding UTF8
}
function Netsh-Ports {
  $raw = (netsh http show servicestate view=requestq 2>$null) -join ' '
  $m = [regex]::Matches($raw, '127\.0\.0\.1:(\d{4,5})')
  $ports = @()
  foreach ($x in $m) { $ports += $x.Groups[1].Value }
  return @($ports | Sort-Object -Unique)
}

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed = 'D:\deepseek_test\MediaHub_Win\windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'

Write-Host '===== P0 环境准备 ====='
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mediahub:hub-test-pw'))
$code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
if ($code -ne '200') {
  Write-Host ('  server down (' + $code + '), starting')
  pwsh -NoProfile -File ($R + 'windows\tools\acceptance\webdav\start-server.ps1') 2>&1 | Select-Object -Last 2 | ForEach-Object { Write-Host ('  ' + $_) }
  Start-Sleep -Seconds 8
  $code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
}
Check 'P0 WsgiDAV 可用（OPTIONS 200）' ($code -eq '200') ('OPTIONS=' + $code)
New-Item -ItemType Directory -Force -Path 'D:\mh-webdav\root\Empty' | Out-Null
Remove-Item -Recurse -Force 'D:\mh-webdav\root\Empty\*' -ErrorAction SilentlyContinue

Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 800
Set-AutoPlayNext $true
& $seed webdav 'http://127.0.0.1:8181/' 'mediahub' 'hub-test-pw' '127.0.0.1:8181' 2>&1 | ForEach-Object { Write-Host ('  seed ' + $_) }
Set-ServersEmpty '^webdav:127'
$before = @(Tap-Lines).Count
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 120

function Go-Libraries {
  $nav = Find-MhAppElement -Name '媒体库'
  if ($nav) { Invoke-MhElement -Element $nav | Out-Null }
  Start-Sleep -Seconds 4
}

Write-Host '===== P1 根目录列举 ====='
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $card = Find-IdLike 'browse|webdav%3A127*'
  if ($card) { break }
  Go-Libraries
  Start-Sleep -Seconds 2
}
$card = Find-IdLike 'browse|webdav%3A127*'
Check 'P1 媒体库页出现该来源的浏览卡片' ($null -ne $card) ('id=' + $(if ($card) { $card.Current.AutomationId } else { 'none' }))
if ($card) { Invoke-MhElementDeep -Element $card | Out-Null }
Check 'P1 根目录出现 Season 01' (Wait-Row 'Season 01' 40) ((Get-RowNames) -join ', ')
$names = Get-RowNames
Check 'P1 根目录出现中文空格文件' ([bool]($names -contains 'Chinese 中文 视频 空格.mp4')) ($names -join ', ')
Check 'P1 根目录出现 norange 目录' ([bool]($names -contains 'norange')) ''
Check 'P1 面包屑显示根目录' (Wait-Text 'BreadcrumbText' '根目录' 20) ('actual=' + (Get-Text 'BreadcrumbText'))
$new = Tap-Since $before
$pj = Tap-Json $new
$rootPropfind = @($pj | Where-Object { $_.method -eq 'PROPFIND' -and (St $_) -eq 207 -and $_.path -eq '/' })
Check 'P1 服务端确实收到根目录 PROPFIND（Basic 认证）' ($rootPropfind.Count -ge 1) (Tap-Text $pj)
Save-MhScreenshot -Path ($ev + '\p1-root.png') | Out-Null

Write-Host '===== P2 逐级导航 ====='
$before = @(Tap-Lines).Count
$r = Click-Row 'Season 01'
Check 'P2 进入 Season 01 后出现 Episode 01.mp4' (Wait-Row 'Episode 01.mp4' 30) ('click=' + $r + ' rows=' + ((Get-RowNames) -join ', '))
Check 'P2 面包屑反映子目录' (Wait-TextContains 'BreadcrumbText' 'Season 01' 20) ('actual=' + (Get-Text 'BreadcrumbText'))
Check 'P2 标题为子目录名' (Wait-Text 'TitleText' 'Season 01' 20) ('actual=' + (Get-Text 'TitleText'))
$pj = Tap-Json (Tap-Since $before)
$sub = @($pj | Where-Object { $_.method -eq 'PROPFIND' -and $_.path -like '*Season*01*' -and (St $_) -eq 207 })
Check 'P2 子目录路径被正确编码后请求' ($sub.Count -ge 1) (Tap-Text $pj)
Save-MhScreenshot -Path ($ev + '\p2-subfolder.png') | Out-Null

Write-Host '===== P3 返回上级 ====='
$before = @(Tap-Lines).Count
$up = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Check 'P3 返回上级按钮可用' ([bool]($up -and $up.Current.IsEnabled)) ('enabled=' + $(if ($up) { $up.Current.IsEnabled } else { 'missing' }))
if ($up) { Invoke-MhElement -Element $up | Out-Null }
Check 'P3 回到根目录（Season 01 与 Normal Movie 同屏）' ((Wait-Row 'Normal Movie.mp4' 30) -and ((Get-RowNames) -contains 'Season 01')) ((Get-RowNames) -join ', ')
Check 'P3 面包屑回到根目录' (Wait-Text 'BreadcrumbText' '根目录' 20) ('actual=' + (Get-Text 'BreadcrumbText'))
$up2 = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Check 'P3 根目录时返回上级被禁用' ([bool]($up2 -and -not $up2.Current.IsEnabled)) ('enabled=' + $(if ($up2) { $up2.Current.IsEnabled } else { 'missing' }))
Save-MhScreenshot -Path ($ev + '\p3-back.png') | Out-Null

Write-Host '===== P4 空目录 ====='
Click-Row 'Empty' | Out-Null
Check 'P4 空目录显示空状态文案' (Wait-TextContains 'EmptyText' '空的' 25) ('actual=' + (Get-Text 'EmptyText'))
Check 'P4 空目录没有条目' (@(Get-RowNames).Count -eq 0) ('rows=' + ((Get-RowNames) -join ', '))
$up3 = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
if ($up3) { Invoke-MhElement -Element $up3 | Out-Null }
Start-Sleep -Seconds 3
Save-MhScreenshot -Path ($ev + '\p4-empty.png') | Out-Null

Write-Host '===== P5 中文空格文件名：播放与路径编码 ====='
$before = @(Tap-Lines).Count
Click-Row 'Chinese 中文 视频 空格.mp4' | Out-Null
$deadline = (Get-Date).AddSeconds(60)
$st = $null
while ((Get-Date) -lt $deadline) {
  $st = Get-MhPlayerState -SkipSlow
  if ($st.positionSeconds -ge 2) { break }
  Start-Sleep -Milliseconds 600
}
Check 'P5 中文空格文件真的在播放' ([bool]($st -and $st.positionSeconds -ge 2)) ('title=' + $st.title + ' position=' + $st.position)
$pj = Tap-Json (Tap-Since $before)
$get = @($pj | Where-Object { $_.method -eq 'GET' -and (St $_) -eq 206 })
# The name the server received, recovered from the latin-1 mojibake the tap log carries. If the
# client had sent a raw (unencoded) path the bytes on the wire would break the request line, and a
# mis-encoded name would not round-trip back to this exact string.
$received = @($get | ForEach-Object { Un-Mojibake $_.path })
$nameOk = @($received | Where-Object { $_ -eq '/Chinese 中文 视频 空格.mp4' })
Check 'P5 中文与空格文件名完整到达服务端（编码可还原）' ($nameOk.Count -ge 1) (($received -join ' , '))
$bytes = @($get | Where-Object { [int]$_.bytes -gt 0 })
Check 'P5 服务端以 206 返回真实媒体字节' ($bytes.Count -ge 1) ('bytes=' + (($get | ForEach-Object { $_.bytes }) -join ','))
Save-MhScreenshot -Path ($ev + '\p5-chinese-play.png') | Out-Null

Write-Host '===== P6 关闭整个应用：先证转发层存在，再证释放 ====='
$portsDuring = Netsh-Ports
Check 'P6 播放中 http.sys 存在回环注册（转发层确实建立）' ($portsDuring.Count -ge 1) ('ports=' + ($portsDuring -join ','))
$procBefore = @(Get-Process MediaHub -ErrorAction SilentlyContinue)
Check 'P6 关闭前进程存在' ($procBefore.Count -ge 1) ('count=' + $procBefore.Count)
Stop-MhAppGracefully | Out-Null
Start-Sleep -Seconds 6
$procAfter = @(Get-Process MediaHub -ErrorAction SilentlyContinue)
$portsAfter = Netsh-Ports
$left = @($portsAfter | Where-Object { $portsDuring -contains $_ })
Check 'P6 关闭后没有 MediaHub 进程' ($procAfter.Count -eq 0) ('count=' + $procAfter.Count)
Check 'P6 关闭后回环注册全部释放' ($left.Count -eq 0) ('before=' + ($portsDuring -join ',') + ' after=' + ($portsAfter -join ',') + ' left=' + ($left -join ','))

$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' passed, ' + $fail + ' failed')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\checks-part1.json') -Encoding UTF8
if ($fail -gt 0) { exit 1 } else { exit 0 }

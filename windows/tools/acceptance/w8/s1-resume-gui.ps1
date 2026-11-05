# W8 Gate S1.3：Jellyfin 真实续播 GUI 门禁（播放→暂停退出→回读服务端→重启恢复→再 Seek→自然结束）。
# 判据纪律：
#   1) 位置由界面与引擎读取，服务端位置只由 Jellyfin 官方 API 回读，脚本绝不写 UserData；
#   2) 每条断言都要证明"应用状态被改变"（时钟真的走了、位置真的跳到新值），不接受"动作被调用"；
#   3) 服务端状态必须由服务端自己产生，客户端只发播放会话上报（Start / Progress / Stopped）。
# 控制台输出按 UTF-8 写，否则批处理重定向到文件后中文全是乱码，日志就不能当证据。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\w8-s1\gui'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe    = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed   = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$trace    = Join-Path $env:LOCALAPPDATA 'MediaHub\startup.log'

$jfBase   = 'http://127.0.0.1:8096'
$jfUser   = 'mediahub-test'
$jfPass   = 'mediahub-test-password'
$jfServer = 'daffc6534af44bbb8f73848209519255'
$itemId   = 'b22e31da9faf6ef247e19f7b3774dab2'   # Long Resume (2026)，600 s
$itemName = 'Long Resume'

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

# ---------------------------------------------------------------- Jellyfin API
$script:authHeader = 'MediaBrowser Client="MediaHub-W8", Device="probe", DeviceId="w8-s1-gui", Version="1.0.0"'
function Connect-Jf {
  $body = @{ Username = $jfUser; Pw = $jfPass } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Method POST -Uri ($jfBase + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $script:authHeader } -Body $body -TimeoutSec 20
  $script:token = $login.AccessToken
  $script:uid   = $login.User.Id
  return $login
}
function Jf([string]$method, [string]$path, $body = $null) {
  $headers = @{ Authorization = ('MediaBrowser Token="' + $script:token + '"') }
  $params = @{ Method = $method; Uri = ($jfBase + $path); Headers = $headers; TimeoutSec = 25 }
  if ($null -ne $body) { $params['ContentType'] = 'application/json'; $params['Body'] = ($body | ConvertTo-Json -Compress -Depth 6) }
  return Invoke-RestMethod @params
}
function Get-ItemUserData {
  $d = Jf 'GET' ('/Users/' + $script:uid + '/Items/' + $itemId + '?Fields=UserData')
  $ticks = 0
  if ($null -ne $d.UserData.PlaybackPositionTicks) { $ticks = [long]$d.UserData.PlaybackPositionTicks }
  return [pscustomobject]@{
    played   = [bool]$d.UserData.Played
    ticks    = $ticks
    seconds  = [math]::Round($ticks / 10000000.0, 2)
    playCount = $d.UserData.PlayCount
  }
}
function Reset-ItemState {
  try { Jf 'DELETE' ('/Users/' + $script:uid + '/PlayedItems/' + $itemId) | Out-Null } catch { }
  Start-Sleep -Milliseconds 600
  return (Get-ItemUserData)
}
function Get-ResumeIds {
  $r = Jf 'GET' ('/UserItems/Resume?UserId=' + $script:uid + '&Limit=60&MediaTypes=Video')
  return @($r.Items | ForEach-Object { $_.Id })
}

# ---------------------------------------------------------------- UI helpers
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
function Wait-Text([string]$id, [string]$expected, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((Get-Text $id) -eq $expected) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}
function Wait-TextContains([string]$id, [string]$needle, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $t = Get-Text $id
    if ($null -ne $t -and $t -like ('*' + $needle + '*')) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}
function Find-IdLike([string]$pattern) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    if ($e.Current.AutomationId -like $pattern) { return $e }
  }
  return $null
}
function Find-IdLikeName([string]$pattern, [string]$nameNeedle) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($c.AutomationId -like $pattern -and $null -ne $c.Name -and $c.Name -like ('*' + $nameNeedle + '*')) { return $e }
  }
  return $null
}
function Wait-Element([string]$id, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 250
  }
  return $null
}
function Wait-IdLikeName([string]$pattern, [string]$nameNeedle, [int]$timeoutSec = 40) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-IdLikeName $pattern $nameNeedle
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
function Invoke-Safe($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Invoke-Id([string]$id) {
  $e = Wait-Element $id 20
  return (Invoke-Safe $e)
}
function Go-Libraries {
  # NavigationView 在「该栏目已经处于选中态」时不会再触发 SelectionChanged：从详情页/媒体库子页
  # 直接点「媒体库」不会回到库根，于是卡片永远等不到（实测 P4 因此 LIBRARY-CARD-NOT-FOUND）。
  # 先切「首页」再切回来，保证真的发生一次栏目切换。
  # 变量不能叫 $home：PowerShell 里 HOME 是只读自动变量，赋值直接抛「无法覆盖变量 HOME」。实测踩过。
  $homeNav = Find-MhAppElement -Name '首页' -IncludeOffscreen
  if ($homeNav) { Invoke-MhElement -Element $homeNav | Out-Null; Start-Sleep -Milliseconds 1200 }
  $nav = Find-MhAppElement -Name '媒体库' -IncludeOffscreen
  if ($nav) { Invoke-MhElement -Element $nav | Out-Null }
  Start-Sleep -Seconds 2
}
function Get-Position {
  $s = Get-MhPlayerState -SkipSlow
  if ($null -eq $s.positionSeconds) { return -1 }
  return [double]$s.positionSeconds
}
function Wait-Position([double]$min, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $p = Get-Position
    if ($p -ge $min) { return $p }
    Start-Sleep -Milliseconds 400
  }
  return (Get-Position)
}
function Seek-To([double]$fraction) {
  $slider = Wait-Element 'player.seekslider' 20
  if (-not $slider) { return 'NO-SLIDER' }
  try { Set-MhRangeValue -Element $slider -Value $fraction; return 'OK' }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Wait-Seeked([double]$target, [int]$timeoutSec = 40) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $p = Get-Position
    if ([math]::Abs($p - $target) -le 12) { return $p }
    Start-Sleep -Milliseconds 400
  }
  return (Get-Position)
}
function Wait-PlayerGone([int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (-not (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen)) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
function Wait-PlayerUp([int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}

# 从首页一路走到 Long Resume 的详情页。返回 'OK' 或失败原因。
function Open-LongResumeDetail {
  Go-Libraries
  $lib = Wait-IdLikeName 'library|jellyfin*' 'Movies' 60
  if (-not $lib) { return 'LIBRARY-CARD-NOT-FOUND' }
  $r1 = Invoke-MhElementDeep -Element $lib
  Start-Sleep -Seconds 2
  $card = Wait-IdLikeName 'media:jellyfin:*' $itemName 60
  if (-not $card) { return ('MEDIA-CARD-NOT-FOUND (' + $r1 + ')') }
  $r2 = Invoke-MhElementDeep -Element $card
  Start-Sleep -Seconds 2
  if (-not (Wait-Element 'detail.play' 30)) { return ('DETAIL-NOT-OPEN (' + $r2 + ')') }
  return 'OK'
}

# ---------------------------------------------------------------- 阶段 P0
Write-Host '=== P0 环境与复位 ==='
$login = $null
try { $login = Connect-Jf } catch { }
Check 'P0.1 Jellyfin 登录成功（真实服务器）' ($null -ne $login) $(if ($login) { 'user=' + $login.User.Name + ' uid=' + $login.User.Id } else { 'login failed' })
if (-not $login) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  Write-Host 'RESULT: 无法继续（服务端不可用）'
  exit 1
}

$before = Reset-ItemState
Check 'P0.2 用官方 API 复位后回读为未观看' ((-not $before.played) -and ($before.ticks -eq 0)) ('played=' + $before.played + ' pos=' + $before.seconds + 's')

# 只保留 Jellyfin 这一条来源，避免其它来源在媒体库页产生噪声卡片。
$raw = Get-Content $settings -Raw | ConvertFrom-Json
$kept = @($raw.Servers | Where-Object { $_.ServerId -like 'jellyfin:*' })
$raw.Servers = $kept
$raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
# 播种「产品真实的恢复态」：在 UI 登录成功后客户端同时保存 token 与 userId，
# MediaBrowserProvider.RestoreSessionAsync 要求两者齐全才判为已登录（缺 token 只会得到「需要登录」，
# 那是命令行假造的状态，不是本 Gate 要测的路径）。
& $seed jellyfin $jfBase $jfUser $jfPass $jfServer $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  seed: ' + $_) }
$after = Get-Content $settings -Raw | ConvertFrom-Json
$jfEntry = @($after.Servers | Where-Object { $_.ServerId -eq ('jellyfin:' + $jfServer) })
Check 'P0.3 来源写入客户端自己的配置（jellyfin:' ($jfEntry.Count -eq 1) ('servers=' + @($after.Servers).Count + ' id=' + ($jfEntry | Select-Object -First 1).ServerId)

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800

# ---------------------------------------------------------------- 阶段 P1
Write-Host ''
Write-Host '=== P1 首次播放：播到中间 → 暂停 → 退出播放页 ==='
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 2
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
Check 'P1.0 客户端已启动且窗口在前台' (($null -ne $app) -and ($null -ne $hwnd)) ('pid=' + $app.pid)

$opened = Open-LongResumeDetail
Check 'P1.1 媒体库 → Movies → Long Resume 详情页可达' ($opened -eq 'OK') $opened
$resumeBtn = Find-MhAppElement -AutomationId 'detail.resume' -IncludeOffscreen
Check 'P1.2 未观看条目首次进入不显示续播标记' ($null -eq $resumeBtn) ('resumeExists=' + ($null -ne $resumeBtn))

$play = Invoke-Id 'detail.play'
$up = Wait-PlayerUp 60
Check 'P1.3 详情页点播放后进入播放页' $up ('invoke=' + $play)
$p1 = Wait-Position 3 40
Check 'P1.4 引擎时钟真的在走（>3 s）' ($p1 -gt 3) ('position=' + $p1 + 's')
Start-Sleep -Seconds 2
$tech = (Get-MhPlayerState -SkipSlow).technical
Check 'P1.5 真实播放而非降级（技术信息非空）' (-not [string]::IsNullOrWhiteSpace($tech)) ('technical=' + $tech)

$seek1 = Seek-To 0.5
$pos1 = Wait-Seeked 300 40
Check 'P1.6 Seek 到片长中点后位置生效（约 300 s）' (([math]::Abs($pos1 - 300)) -le 15) ('seek=' + $seek1 + ' position=' + $pos1)
Start-Sleep -Seconds 2
$pauseres1 = Invoke-Id 'player.playpause'
Start-Sleep -Milliseconds 1200
$pa = Get-Position
Start-Sleep -Seconds 2
$pb = Get-Position
Check 'P1.7 暂停后时钟冻结' (([math]::Abs($pb - $pa)) -le 0.6) ('invoke=' + $pauseres1 + ' p1=' + $pa + ' p2=' + $pb)
$srvTarget = $pb
$shot1 = Join-Path $ev 'p1-midway-paused.png'
Save-MhScreenshot -Path $shot1 | Out-Null

$back = Invoke-Id 'player.back'
$gone = Wait-PlayerGone 30
Check 'P1.8 退出播放页回到外壳' $gone ('invoke=' + $back)

# 停止上报走客户端自己的停止路径（OnUnloaded → StopAsync(UserExited)），脚本不写任何位置。
$srv = $null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
  $srv = Get-ItemUserData
  if ($srv.ticks -gt 0) { break }
  Start-Sleep -Seconds 1
}
$delta = [math]::Abs($srv.seconds - $srvTarget)
Check 'P1.9 客户端退出后服务端记录了中断位置（不写脚本值）' (($srv.seconds -gt 0) -and ($delta -le 15)) ('server=' + $srv.seconds + 's client=' + $srvTarget + 's delta=' + [math]::Round($delta,1))
Check 'P1.10 中断条目未被标成已观看（600 s > 阈值 300 s）' (-not $srv.played) ('played=' + $srv.played + ' pos=' + $srv.seconds + 's playCount=' + $srv.playCount)
$resumeIds = Get-ResumeIds
Check 'P1.11 条目出现在服务端 Resume 列表' ($resumeIds -contains $itemId) ('resumeCount=' + $resumeIds.Count + ' contains=' + ($resumeIds -contains $itemId))
$p1srv = $srv.seconds

# ---------------------------------------------------------------- 阶段 P2
Write-Host ''
Write-Host '=== P2 正常关闭并重启后恢复位置 ==='
$stop = Stop-MhAppGracefully -TimeoutSec 30
Check 'P2.1 客户端正常退出（非强杀）' ($stop.method -eq 'graceful' -or $stop.method -eq 'already-exited') ('method=' + $stop.method + ' waited=' + $stop.waitedSec + 's')
Start-Sleep -Seconds 1

$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
$opened = Open-LongResumeDetail
Check 'P2.2 重启后同一媒体详情页可达' ($opened -eq 'OK') $opened
$resumeBtn2 = Find-MhAppElement -AutomationId 'detail.resume' -IncludeOffscreen
Check 'P2.3 详情页显示续播入口' ($null -ne $resumeBtn2) ('resumeExists=' + ($null -ne $resumeBtn2))

$play = Invoke-Id 'detail.play'
$up = Wait-PlayerUp 60
$p2 = Wait-Position 1 30
Check 'P2.4 播放页出现且引擎时钟在走' ($up -and ($p2 -gt 1)) ('up=' + $up + ' position=' + $p2)
$delta2 = [math]::Abs($p2 - $p1srv)
Check 'P2.5 libmpv 实际起始位置落在服务端记录附近（±15 s）' ($delta2 -le 15) ('engine=' + $p2 + 's server=' + $p1srv + 's delta=' + [math]::Round($delta2,1))
$shot2 = Join-Path $ev 'p2-resumed.png'
Save-MhScreenshot -Path $shot2 | Out-Null

# ---------------------------------------------------------------- 阶段 P3
Write-Host ''
Write-Host '=== P3 再 Seek 一次后停止：验证第二次更新且仍未观看 ==='
$seek2 = Seek-To 0.3
$pos3 = Wait-Seeked 180 40
Check 'P3.1 第二次 Seek 生效（约 180 s）' (([math]::Abs($pos3 - 180)) -le 15) ('seek=' + $seek2 + ' position=' + $pos3)
Start-Sleep -Seconds 3
$p3a = Get-Position
$pause3 = Invoke-Id 'player.playpause'
Start-Sleep -Milliseconds 1200
$p3b = Get-Position
$back = Invoke-Id 'player.back'
$gone = Wait-PlayerGone 30
Check 'P3.2 再次退出播放页' $gone ('invoke=' + $back)

$srv3 = $null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
  $srv3 = Get-ItemUserData
  if ([math]::Abs($srv3.seconds - $p3b) -le 15) { break }
  Start-Sleep -Seconds 1
}
Check 'P3.3 服务端位置被第二次更新覆盖' (([math]::Abs($srv3.seconds - $p3b)) -le 15) ('server=' + $srv3.seconds + 's client=' + $p3b + 's before=' + $p1srv + 's')
Check 'P3.4 第二次停止后仍然未观看' (-not $srv3.played) ('played=' + $srv3.played + ' pos=' + $srv3.seconds + 's')
$p3srv = $srv3.seconds

# ---------------------------------------------------------------- 阶段 P4
Write-Host ''
Write-Host '=== P4 自然播到末尾：服务端应记为已完成 ==='
$opened = Open-LongResumeDetail
Check 'P4.1 从详情页再次进入播放' ($opened -eq 'OK') $opened
# 只打开详情页不等于开始播放：必须真的按「播放」。上一版漏了这一步，于是 P4.2 恒为 up=False position=-1。
$play4 = Invoke-Id 'detail.play'
$up = Wait-PlayerUp 60
$p4 = Wait-Position 1 30
Check 'P4.2 播放页与引擎就绪' ($up -and ($p4 -gt 1)) ('up=' + $up + ' position=' + $p4)
$seek4 = Seek-To 0.985
$pos4 = Wait-Seeked 591 40
Check 'P4.3 Seek 到片尾前（约 591 s）' (([math]::Abs($pos4 - 591)) -le 15) ('seek=' + $seek4 + ' position=' + $pos4)
# 自然播放到 EOF：不 Seek、不退出，只等引擎自己播完。
$deadline = (Get-Date).AddSeconds(120)
$final = $null
while ((Get-Date) -lt $deadline) {
  $final = Get-ItemUserData
  if ($final.played) { break }
  Start-Sleep -Seconds 2
}
Check 'P4.4 自然播完后服务端标记已观看' ($final.played) ('played=' + $final.played + ' pos=' + $final.seconds + 's playCount=' + $final.playCount)
Check 'P4.5 已完成条目的位置被清零' (($final.ticks -eq 0)) ('pos=' + $final.seconds + 's ticks=' + $final.ticks)
$shot4 = Join-Path $ev 'p4-ended.png'
Save-MhScreenshot -Path $shot4 | Out-Null

# ---------------------------------------------------------------- 证据
Write-Host ''
Write-Host '=== 证据 ==='
try {
  $logDir = 'D:\mh-servers\data\jellyfin\log'
  $latest = Get-ChildItem $logDir -Filter '*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  Get-Content $latest.FullName | Select-String -Pattern 'started playback|stopped playback|playstate|PlaybackStop' | ForEach-Object { $_.Line } | Set-Content (Join-Path $ev 'jellyfin-playstate-window.txt') -Encoding UTF8
} catch { }
try { Copy-Item $trace (Join-Path $ev 'startup.log') -Force } catch { }
$checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
$failed = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ''
Write-Host ('RESULT: ' + ($checks.Count - $failed) + '/' + $checks.Count + ' passed, ' + $failed + ' failed')

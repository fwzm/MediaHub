# W8 Gate S2：Emby 4.10.1.0 官方服务器端到端门禁。
# 判据纪律：
#   1) 位置只由界面/引擎读取，服务端位置只由 Emby 官方 API 回读，脚本绝不写 UserData；
#   2) 每条断言都必须证明"应用状态被改变"（时钟真的走了、位置真的跳到新值），不接受"动作被调用"；
#   3) 跨服务端差异单独记录：Emby 的 Resume 端点是 /Users/{uid}/Items/Resume，Jellyfin 是 /UserItems/Resume。
# -OnlyP5：跳过 P1..P4 的播放流程，只重跑 P5 的失败/认证路径（P0 的复位、播种、启动照跑）。
# 正式留证时必须不带该开关完整跑一遍。
param([switch]$OnlyP5)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\w8-s2\gui'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe      = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$trace    = Join-Path $env:LOCALAPPDATA 'MediaHub\startup.log'
$embyLog  = 'D:\mh-servers\data\emby\logs\embyserver.txt'
$embyExe  = 'D:\mh-servers\emby-install\app\system\EmbyServer.exe'
$embyData = 'D:\mh-servers\data\emby'

$emBase   = 'http://127.0.0.1:8097'
$emUser   = 'mh'
$emPass   = 'mh-test-pass'
$emServer = '1271d0a1040046db8c0089cc328bbbf1'
$itemId   = '34'      # Long Resume，600 s，实机 id 是短数字串（Emby 与 Jellyfin 的另一处差异）
$itemName = 'Long Resume'
$libId    = '22'      # MHMovies
$libName  = 'MHMovies'

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

# ---------------------------------------------------------------- Emby API
$script:authHeader = 'MediaBrowser Client="MediaHub-W8", Device="probe", DeviceId="w8-s2-gui", Version="1.0.0"'
function Connect-Em([string]$deviceId) {
  $hdr = 'MediaBrowser Client="MediaHub-W8", Device="probe", DeviceId="' + $deviceId + '", Version="1.0.0"'
  $body = @{ Username = $emUser; Pw = $emPass } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Method POST -Uri ($emBase + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $hdr } -Body $body -TimeoutSec 20
  return $login
}
function Em([string]$method, [string]$path, $body = $null, [string]$tokenOverride = '') {
  $t = $script:token
  if ($tokenOverride -ne '') { $t = $tokenOverride }
  $headers = @{ Authorization = ('MediaBrowser Token="' + $t + '"') }
  $params = @{ Method = $method; Uri = ($emBase + $path); Headers = $headers; TimeoutSec = 30 }
  if ($null -ne $body) { $params['ContentType'] = 'application/json'; $params['Body'] = ($body | ConvertTo-Json -Compress -Depth 6) }
  return Invoke-RestMethod @params
}
function Get-ItemUserData {
  $d = Em 'GET' ('/Users/' + $script:uid + '/Items/' + $itemId + '?Fields=UserData')
  $ticks = 0
  if ($null -ne $d.UserData.PlaybackPositionTicks) { $ticks = [long]$d.UserData.PlaybackPositionTicks }
  return [pscustomobject]@{
    played    = [bool]$d.UserData.Played
    ticks     = $ticks
    seconds   = [math]::Round($ticks / 10000000.0, 2)
    playCount = $d.UserData.PlayCount
  }
}
function Reset-ItemState {
  try { Em 'DELETE' ('/Users/' + $script:uid + '/PlayedItems/' + $itemId) | Out-Null } catch { }
  Start-Sleep -Milliseconds 800
  return (Get-ItemUserData)
}
function Get-ResumeIds {
  # Emby：/Users/{uid}/Items/Resume（Jellyfin 用 /UserItems/Resume；写死一端会让另一端 404）
  $r = Em 'GET' ('/Users/' + $script:uid + '/Items/Resume?Limit=60&MediaTypes=Video')
  return @($r.Items | ForEach-Object { $_.Id })
}

# ---------------------------------------------------------------- UI helpers
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
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
function Wait-IdLikeName([string]$pattern, [string]$nameNeedle, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-IdLikeName $pattern $nameNeedle
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
function Get-Banner([string]$id) {
  # InfoBar 自身的 Name 是空串，标题与正文是它内部的 Text 子元素。只读 InfoBar 会永远得到空值，
  # 于是「没有错误提示」这类判据会假通过、「有错误提示」会假失败（两者都在 P5 上真实发生过）。
  $bar = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if (-not $bar) { return '' }
  $texts = New-Object System.Collections.Generic.List[string]
  foreach ($e in $bar.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
    $c = $e.Current
    if ($c.ControlType.ProgrammaticName -eq 'ControlType.Text' -and $c.Name) { $texts.Add($c.Name) }
  }
  return ($texts -join ' | ')
}
function Invoke-Safe($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Invoke-Deep($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElementDeep -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Invoke-Id([string]$id) {
  $e = Wait-Element $id 20
  return (Invoke-Safe $e)
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if ($item) { Invoke-MhElement -Element $item | Out-Null; Start-Sleep -Milliseconds 1200 }
  return ($null -ne $item)
}
function Go-Libraries {
  # NavigationView 在栏目已选中时不再触发 SelectionChanged，必须先切走再切回。
  Go-Nav '首页' | Out-Null
  Go-Nav '媒体库' | Out-Null
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
    if ([math]::Abs($p - $target) -le 15) { return $p }
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
function Netsh-Ports {
  # HttpListener 由 http.sys 内核承载，端口不在进程 TCP 监听表里（Get-NetTCPConnection 恒空），
  # 只能查 http.sys 的 servicestate；先证存在再证消失。
  $out = & netsh http show servicestate view=requestq 2>$null | Out-String
  $ports = New-Object System.Collections.Generic.List[int]
  foreach ($m in [regex]::Matches($out, '127\.0\.0\.1:(\d{4,5})')) {
    $v = [int]$m.Groups[1].Value
    if (-not $ports.Contains($v)) { $ports.Add($v) }
  }
  return $ports
}
function Keep-OnlyServer([string]$pattern) {
  $raw = Get-Content $settings -Raw | ConvertFrom-Json
  $raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like $pattern })
  $raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
  return @($raw.Servers).Count
}

# 从首页走到 MHMovies 里的 Long Resume 详情页，返回 'OK' 或失败原因。
function Open-ItemDetail {
  Go-Libraries
  $lib = Wait-IdLikeName 'library|emby*' $libName 60
  if (-not $lib) { return 'LIBRARY-CARD-NOT-FOUND' }
  $r1 = Invoke-Deep $lib
  Start-Sleep -Seconds 2
  $card = Wait-IdLikeName 'media:emby:*' $itemName 60
  if (-not $card) { return ('MEDIA-CARD-NOT-FOUND ' + $r1) }
  $r2 = Invoke-Deep $card

  # Emby 在每个电影库根下放一层物理文件夹（Jellyfin 会把同一库扁平化成电影），
  # 所以第一次点击可能只进入文件夹层：此时页面标题是「库名 / 文件夹名」，需要再点一次真正的条目。
  $deadline = (Get-Date).AddSeconds(25)
  $inFolder = $false
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen) { return 'OK' }
    $t = Get-Text 'TitleText'
    if ($t -and $t.Contains(' / ')) { $inFolder = $true; break }
    Start-Sleep -Milliseconds 300
  }
  if (-not $inFolder) { return ('DETAIL-NOT-OPEN ' + $r2) }

  $inner = Wait-IdLikeName 'media:emby:*' $itemName 40
  if (-not $inner) { return ('INNER-CARD-NOT-FOUND ' + $r2 + ' title=' + (Get-Text 'TitleText')) }
  $r3 = Invoke-Deep $inner
  if (-not (Wait-Element 'detail.play' 30)) { return ('FOLDER-DETAIL-NOT-OPEN ' + $r3) }
  return 'OK'
}


# ---------------------------------------------------------------- 阶段 P0
Write-Host '=== P0 环境、复位与来源播种 ==='
$login = $null
try { $login = Connect-Em 'w8-s2-gui' } catch { }
Check 'P0.1 Emby 官方服务器登录成功' ($null -ne $login) $(if ($login) { 'user=' + $login.User.Name + ' uid=' + $login.User.Id } else { 'login failed' })
if (-not $login) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  Write-Host 'RESULT: 无法继续（服务端不可用）'
  exit 1
}
$script:token = $login.AccessToken
$script:uid   = $login.User.Id
$info = Em 'GET' '/System/Info'
# 本轮开工时官方实例仍在 4.10.1.0，但它在 20:11:52 自行执行了官方自动更新（服务器日志有
# 「Exiting to perform application update」），现在是 4.10.2.0。版本以实测为准并如实记录，
# 不按任务书里的旧版本号硬编码；同时已在隔离测试实例上关闭 EnableAutoUpdate 防止再次中途重启。
Check 'P0.2 被验收的是官方 Emby 4.10.x 实例（版本实测记录）' ($info.Version -like '4.10.*') ('version=' + $info.Version + ' serverId=' + $info.Id)

$before = Reset-ItemState
Check 'P0.3 官方 API 复位后回读为未观看' ((-not $before.played) -and ($before.ticks -eq 0)) ('played=' + $before.played + ' pos=' + $before.seconds + 's count=' + $before.playCount)

# 播种前先确保客户端没在运行：它的正常退出会把内存设置回写 settings.json。
Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$keptCount = Keep-OnlyServer 'emby:*'
# 播种「产品真实的恢复态」：UI 登录成功后客户端同时保存 token 与 userId。
& $seed emby $emBase $emUser $emPass $emServer $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  seed: ' + $_) }
$after = Get-Content $settings -Raw | ConvertFrom-Json
$emEntry = @($after.Servers | Where-Object { $_.ServerId -eq ('emby:' + $emServer) })
Check 'P0.4 来源写入客户端自己的配置' ($emEntry.Count -eq 1) ('keptBefore=' + $keptCount + ' serversNow=' + @($after.Servers).Count + ' id=' + (($emEntry | Select-Object -First 1).ServerId))

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800

# ---------------------------------------------------------------- 阶段 P1
Write-Host ''
Write-Host '=== P1 真实库浏览 → 详情 → 真实播放（源站直连 + 回环中继） ==='
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
Check 'P1.0 客户端已启动且窗口在前台' (($null -ne $app) -and ($null -ne $hwnd)) ('pid=' + $app.pid)

if (-not $OnlyP5) {

$open = Open-ItemDetail
Check ('P1.1 媒体库 → ' + $libName + ' → ' + $itemName + ' 详情页可达') ($open -eq 'OK') $open
$titleText = Get-Text 'TitleText'
Check 'P1.2 详情页显示真实标题（非占位）' ($null -ne $titleText -and $titleText -like ('*' + $itemName + '*')) ('title=' + $titleText)

$play = Invoke-Id 'detail.play'
$up = Wait-PlayerUp 60
Check 'P1.3 详情页点播放后进入播放页' $up ('invoke=' + $play)
# 判据要留出采样边界：Wait-Position 4 一满 4.0 就返回，再用 "> 4" 比就必然是假失败。
$p1 = Wait-Position 6 60
Check 'P1.4 引擎时钟真的在走（≥6 s）' ($p1 -ge 6) ('position=' + $p1 + 's')
Start-Sleep -Seconds 2
$state1 = Get-MhPlayerState -SkipSlow
Check 'P1.5 真实硬解播放而非降级' ($null -ne $state1.technical -and $state1.technical -like '*hwdec=d3d11va*') ('technical=' + $state1.technical)
Check 'P1.6 播放的是 600 s 的长片而非别的条目' ($null -ne $state1.durationSeconds -and [math]::Abs([double]$state1.durationSeconds - 600) -le 3) ('duration=' + $state1.durationSeconds + 's')

$portsPlaying = Netsh-Ports
Check 'P1.7 播放期间回环中继已在 http.sys 注册（先证存在）' ($portsPlaying.Count -gt 0) ('ports=' + ($portsPlaying -join ','))
$shot1 = Join-Path $ev 'p1-playing.png'
Save-MhScreenshot -Path $shot1 | Out-Null

# 源站证据：Emby 自己的 HTTP 日志里必须出现该条目的媒体流请求与 2xx 响应。
$streamHits = @()
if (Test-Path $embyLog) {
  $streamHits = @(Get-Content $embyLog -Tail 4000 | Select-String -Pattern ('Response 20[06] .*GET http.*/videos/' + $itemId + '/stream'))
}
Check 'P1.8 源站确实收到该条目的媒体流请求（Emby 日志 206/200）' ($streamHits.Count -gt 0) ('hits=' + $streamHits.Count + $(if ($streamHits.Count -gt 0) { ' last=' + $streamHits[-1].Line.Substring(0, [math]::Min(150, $streamHits[-1].Line.Length)) } else { '' }))
$sessionHits = @()
if (Test-Path $embyLog) {
  $sessionHits = @(Get-Content $embyLog -Tail 4000 | Select-String -Pattern 'Sessions/Playing')
}
Check 'P1.9 客户端向源站发出了播放会话上报（Sessions/Playing）' ($sessionHits.Count -gt 0) ('hits=' + $sessionHits.Count)

# ---------------------------------------------------------------- 阶段 P2
Write-Host ''
Write-Host '=== P2 播到中间 → 暂停 → 退出播放页 → 回读 Emby UserData ==='
$seek1 = Seek-To 0.5
$pos1 = Wait-Seeked 300 45
Check 'P2.1 Seek 到片长中点后位置生效（约 300 s）' (([math]::Abs($pos1 - 300)) -le 15) ('seek=' + $seek1 + ' position=' + $pos1)
Start-Sleep -Seconds 2
$pause = Invoke-Id 'player.playpause'
Start-Sleep -Milliseconds 1200
$pa = Get-Position
Start-Sleep -Seconds 2
$pb = Get-Position
Check 'P2.2 暂停后时钟冻结' (([math]::Abs($pb - $pa)) -le 0.8) ('invoke=' + $pause + ' p1=' + $pa + ' p2=' + $pb)
$srvTarget = $pb
$shot2 = Join-Path $ev 'p2-midway-paused.png'
Save-MhScreenshot -Path $shot2 | Out-Null

$back = Invoke-Id 'player.back'
$gone = Wait-PlayerGone 30
Check 'P2.3 退出播放页回到外壳' $gone ('invoke=' + $back)

# 停止上报走客户端自己的停止路径（OnUnloaded → StopAsync），脚本不写任何位置。
$srv = $null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
  $srv = Get-ItemUserData
  if ($srv.ticks -gt 0) { break }
  Start-Sleep -Seconds 1
}
$delta = [math]::Abs($srv.seconds - $srvTarget)
Check 'P2.4 客户端退出后 Emby 记录了中断位置（不写脚本值）' (($srv.seconds -gt 0) -and ($delta -le 20)) ('server=' + $srv.seconds + 's client=' + $srvTarget + 's delta=' + [math]::Round($delta, 1))
Check 'P2.5 中断条目未被标成已观看' (-not $srv.played) ('played=' + $srv.played + ' pos=' + $srv.seconds + 's count=' + $srv.playCount)
$resumeIds = Get-ResumeIds
Check 'P2.6 条目出现在 Emby Resume 列表 (/Users/{uid}/Items/Resume)' ($resumeIds -contains $itemId) ('resumeCount=' + $resumeIds.Count + ' contains=' + ($resumeIds -contains $itemId))
$p2srv = $srv.seconds


# ---------------------------------------------------------------- 阶段 P3
Write-Host ''
Write-Host '=== P3 完全重启客户端后从 Emby 恢复位置 ==='
$stop = Stop-MhAppGracefully -TimeoutSec 30
Check 'P3.1 客户端正常退出（非强杀）' ($stop.method -eq 'graceful' -or $stop.method -eq 'already-exited') ('method=' + $stop.method + ' waited=' + $stop.waitedSec + 's')
$portsAfterExit = Netsh-Ports
Check 'P3.2 退出后回环中继注册已释放（先证存在于 P1.7 再证消失）' ($portsAfterExit.Count -eq 0) ('ports=' + ($portsAfterExit -join ','))
Start-Sleep -Seconds 1

$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
$open3 = Open-ItemDetail
Check 'P3.3 重启后同一媒体详情页可达' ($open3 -eq 'OK') $open3
$resumeBtn = Find-MhAppElement -AutomationId 'detail.resume' -IncludeOffscreen
Check 'P3.4 详情页显示续播入口（读的是服务端 UserData）' ($null -ne $resumeBtn) ('resumeExists=' + ($null -ne $resumeBtn))
$play3 = Invoke-Id 'detail.play'
$up3 = Wait-PlayerUp 60
$p3 = Wait-Position 1 30
Check 'P3.5 播放页出现且引擎时钟在走' ($up3 -and ($p3 -gt 1)) ('invoke=' + $play3 + ' up=' + $up3 + ' position=' + $p3)
$delta3 = [math]::Abs($p3 - $p2srv)
Check 'P3.6 libmpv 实际起始位置落在服务端记录附近（±20 s）' ($delta3 -le 20) ('engine=' + $p3 + 's server=' + $p2srv + 's delta=' + [math]::Round($delta3, 1))
$shot3 = Join-Path $ev 'p3-resumed.png'
Save-MhScreenshot -Path $shot3 | Out-Null

# ---------------------------------------------------------------- 阶段 P4
Write-Host ''
Write-Host '=== P4 自然播到末尾：Emby 应记为已完成 ==='
$seek4 = Seek-To 0.985
$pos4 = Wait-Seeked 591 45
Check 'P4.1 Seek 到片尾前（约 591 s）' (([math]::Abs($pos4 - 591)) -le 15) ('seek=' + $seek4 + ' position=' + $pos4)
$deadline = (Get-Date).AddSeconds(150)
$final = $null
while ((Get-Date) -lt $deadline) {
  $final = Get-ItemUserData
  if ($final.played) { break }
  Start-Sleep -Seconds 2
}
Check 'P4.2 自然播完后 Emby 标记已观看' ($final.played) ('played=' + $final.played + ' pos=' + $final.seconds + 's count=' + $final.playCount)
Check 'P4.3 已完成条目的位置被清零' ($final.ticks -eq 0) ('ticks=' + $final.ticks + ' pos=' + $final.seconds + 's')
$shot4 = Join-Path $ev 'p4-ended.png'
Save-MhScreenshot -Path $shot4 | Out-Null
$resumeAfter = Get-ResumeIds
Check 'P4.4 已完成条目已移出 Emby Resume 列表' (-not ($resumeAfter -contains $itemId)) ('resumeCount=' + $resumeAfter.Count)
}

# ---------------------------------------------------------------- 阶段 P5
Write-Host ''
Write-Host '=== P5 失败与认证失效路径（不得泄密、不得显示成空目录） ==='
if (-not $OnlyP5) {
  Invoke-Id 'player.back' | Out-Null
  Wait-PlayerGone 30 | Out-Null
}

# P5 的断言对象是「媒体库页」，而退出播放页只会回到详情页。这里必须先真的导航到媒体库页，
# 否则 libraries.reload / libraries.status 根本不在 UIA 树里，读到的只能是空字符串——
# 上一轮就是这样把「页面没打开」误判成「页面没有给出失败提示」。
Go-Libraries

# P5.1 断网：停掉 Emby 进程，再让媒体库页真的重新请求一次。
Get-Process EmbyServer -ErrorAction SilentlyContinue | Stop-Process -Force
$portDown = $false
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) {
  try { Invoke-WebRequest -Uri ($emBase + '/System/Info/Public') -TimeoutSec 3 -UseBasicParsing | Out-Null } catch { $portDown = $true; break }
  Start-Sleep -Milliseconds 700
}
Check 'P5.1 Emby 进程已停止且端口不再应答（离线前提成立）' $portDown ('portDown=' + $portDown)
Invoke-Id 'libraries.reload' | Out-Null
$banner = ''
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
  $banner = Get-Banner 'libraries.status'
  if ($banner -like '*未能加载*') { break }
  Start-Sleep -Milliseconds 500
}
Check 'P5.2 离线时媒体库页给出具名失败提示而不是空列表' ($banner -like '*未能加载*') ('status=' + $banner)
$shot5 = Join-Path $ev 'p5-offline.png'
Save-MhScreenshot -Path $shot5 | Out-Null

# P5.3 恢复服务端后重试成功：产品侧的重试入口是「刷新」，必须以真实列表回来为准。
$emby = Start-Process -FilePath $embyExe -ArgumentList @('-programdata', $embyData, '-nointerface') -PassThru -WindowStyle Hidden
$ready = $false
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
  try {
    $pub = Invoke-RestMethod ($emBase + '/System/Info/Public') -TimeoutSec 4
    if ($pub.Version -like '4.10*') { $ready = $true; break }
  } catch { }
  Start-Sleep -Seconds 2
}
Check 'P5.3 Emby 已重新启动并就绪' $ready ('pid=' + $emby.Id)
Invoke-Id 'libraries.reload' | Out-Null
$libBack = Wait-IdLikeName 'library|emby*' $libName 90
Check 'P5.4 重试后媒体库列表真的回来了' ($null -ne $libBack) ('cardFound=' + ($null -ne $libBack))

# P5.5 认证失效：吊销客户端那份令牌，再让页面重新请求。
$appLogin = Connect-Em 'w8-s2-app'
$script:token = $appLogin.AccessToken
$script:uid = $appLogin.User.Id
# 必须先让客户端退出再写配置：客户端正常退出时会把内存里的设置回写 settings.json，
# 先播种后退出会被它覆盖（P6 首跑就是这样丢掉了刚写入的 Jellyfin 来源）。
Stop-MhAppGracefully -TimeoutSec 30 | Out-Null
& $seed emby $emBase $emUser $emPass $emServer $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  reseed: ' + $_) }
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
Go-Libraries
$authBanner = ''
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
  $authBanner = Get-Banner 'libraries.status'
  if ($authBanner -like '*未能加载*') { break }
  Start-Sleep -Milliseconds 500
}
$card55 = Wait-IdLikeName 'library|emby*' $libName 60
Check 'P5.5 播种后（未吊销前）媒体库可加载' (($null -ne $card55) -and ($authBanner -notlike '*未能加载*')) ('card=' + ($null -ne $card55) + ' status=' + $authBanner)

$admin = Connect-Em 'w8-s2-gui'
try {
  Invoke-RestMethod -Method POST -Uri ($emBase + '/Sessions/Logout') -Headers @{ Authorization = ('MediaBrowser Token="' + $script:token + '"') } -TimeoutSec 20 | Out-Null
  $revoked = $true
} catch { $revoked = $false }
Check 'P5.6 客户端令牌已被服务端吊销' $revoked ('revoked=' + $revoked)
$script:token = $admin.AccessToken
$script:uid = $admin.User.Id
Invoke-Id 'libraries.reload' | Out-Null
$authBanner2 = ''
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
  $authBanner2 = Get-Banner 'libraries.status'
  if ($authBanner2 -like '*登录*') { break }
  Start-Sleep -Milliseconds 500
}
Check 'P5.7 令牌失效后显示需要重新登录而不是空列表/崩溃' ($authBanner2 -like '*登录*') ('status=' + $authBanner2)
Check 'P5.8 客户端仍在运行（认证失效不导致退出）' ((Get-MhProcess) -ne $null) ('pid=' + (Get-MhWindowHandle))

# 泄密面：客户端启动日志里不得出现任何令牌明文。
$appToken = $appLogin.AccessToken
$logText = ''
if (Test-Path $trace) { $logText = Get-Content $trace -Raw }
Check 'P5.9 客户端日志中不含访问令牌明文' (-not ($logText -like ('*' + $appToken + '*'))) ('tokenLen=' + $appToken.Length + ' logChars=' + $logText.Length)
$authLog = @()
if (Test-Path $embyLog) { $authLog = @(Get-Content $embyLog -Tail 3000 | Select-String -Pattern 'Response 401') }
Check 'P5.10 源站确实以 401 拒绝（不是客户端臆造）' ($authLog.Count -gt 0) ('hits401=' + $authLog.Count)

# ---------------------------------------------------------------- 阶段 P6
Write-Host ''
Write-Host '=== P6 跨服务端回归：同一份客户端连 Jellyfin 仍然可用 ==='
$jfBase = 'http://127.0.0.1:8096'
$jfServer = 'daffc6534af44bbb8f73848209519255'
$jfHdr = 'MediaBrowser Client="MediaHub-W8", Device="probe", DeviceId="w8-s2-jf", Version="1.0.0"'
$jf = $null
try {
  $jf = Invoke-RestMethod -Method POST -Uri ($jfBase + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $jfHdr } -Body (@{ Username = 'mediahub-test'; Pw = 'mediahub-test-password' } | ConvertTo-Json -Compress) -TimeoutSec 20
} catch { }
Check 'P6.1 Jellyfin 登录成功' ($null -ne $jf) $(if ($jf) { 'uid=' + $jf.User.Id } else { 'login failed' })
if ($jf) {
  # 同上：先退出客户端再改 settings.json，否则退出时的回写会覆盖播种结果。
  Stop-MhAppGracefully -TimeoutSec 30 | Out-Null
  Keep-OnlyServer 'jellyfin:*' | Out-Null
  & $seed jellyfin $jfBase 'mediahub-test' 'mediahub-test-password' $jfServer $jf.AccessToken $jf.User.Id 2>&1 | ForEach-Object { Write-Host ('  seed-jf: ' + $_) }
  $app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
  Start-Sleep -Seconds 3
  $hwnd = Get-MhWindowHandle
  if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
  Go-Libraries
  $jfCard = Wait-IdLikeName 'library|jellyfin*' 'Movies' 60
  Check 'P6.2 Jellyfin 媒体库卡片仍可达（没被 Emby 的修复弄坏）' ($null -ne $jfCard) ('found=' + ($null -ne $jfCard))
  if ($jfCard) {
    Invoke-Deep $jfCard | Out-Null
    Start-Sleep -Seconds 2
    $jfItem = Wait-IdLikeName 'media:jellyfin:*' 'Long Resume' 60
    if ($jfItem) { Invoke-Deep $jfItem | Out-Null; Start-Sleep -Seconds 2 }
    $jfPlay = Invoke-Id 'detail.play'
    $jfUp = Wait-PlayerUp 60
    # 同 P1.4：Wait-Position 一满阈值就返回，判据必须与阈值同向（≥ 而不是 >）。
    $jfPos = Wait-Position 6 60
    Check 'P6.3 Jellyfin 条目仍能真实播放（引擎时钟前进）' ($jfUp -and ($jfPos -ge 6)) ('up=' + $jfUp + ' position=' + $jfPos + ' invoke=' + $jfPlay)
    Invoke-Id 'player.back' | Out-Null
    Wait-PlayerGone 30 | Out-Null
  } else {
    Check 'P6.3 Jellyfin 条目仍能真实播放（引擎时钟前进）' $false 'skipped: no library card'
  }
}

# ---------------------------------------------------------------- 证据
Write-Host ''
Write-Host '=== 证据 ==='
try {
  Get-Content $embyLog -Tail 6000 | Select-String -Pattern 'Sessions/Playing|videos/34|Response 401|PlaybackStop' | ForEach-Object { $_.Line } | Set-Content (Join-Path $ev 'emby-http-window.txt') -Encoding UTF8
} catch { }
try { Copy-Item $trace (Join-Path $ev 'startup.log') -Force } catch { }
$checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
$failed = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ''
Write-Host ('RESULT: ' + ($checks.Count - $failed) + '/' + $checks.Count + ' passed, ' + $failed + ' failed')


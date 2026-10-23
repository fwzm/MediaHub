# R7 Gate A3（第二部分）：子根挂载、失败语义（404 / 403 / 500 / 401）、离线与重试、导航竞态。
# 全部断言都要求“应用状态被改变”而不是“动作被调用”：每条失败路径都同时核对
#   ①界面文案（不是空目录） ②重试入口真的出现 ③列表被清空 ④服务器确实收到该请求。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\r7-a3b-webdav'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$startServer = $R + 'windows\tools\acceptance\webdav\start-server.ps1'
$faultPy = $R + 'windows\tools\acceptance\webdav\fault_server.py'
$subrootState = $R + 'windows\tools\acceptance\evidence\webdav-subroot'
$subTapPath = $subrootState + '\requests.jsonl'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$trace = Join-Path $env:LOCALAPPDATA 'MediaHub\startup.log'

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
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
  # 行文本在 DataTemplate 里没有 pattern；触发 ItemClick 的是 ListViewItem 祖先。合成鼠标点击
  # 落在 Border 上实测不导航，所以必须用祖先回退。
  return (Invoke-MhElementDeep -Element $e)
}
function Invoke-Safe($element) {
  # 对禁用或已失效的元素调用 InvokePattern 会抛 COM 异常；把异常变成可判定的返回值，
  # 否则脚本会在半途中断、后面的检查一条都不跑。
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Find-IdLike([string]$pattern) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    if ($e.Current.AutomationId -like $pattern) { return $e }
  }
  return $null
}
function Get-ErrorState {
  $retry = Find-MhAppElement -AutomationId 'browse.retry' -IncludeOffscreen
  return [pscustomobject]@{
    text           = (Get-Text 'EmptyText')
    retryExists    = ($null -ne $retry)
    retryOffscreen = $(if ($retry) { $retry.Current.IsOffscreen } else { $null })
    rows           = (Get-RowNames)
  }
}
function Wait-ErrorLike([string]$needle, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $state = $null
  while ((Get-Date) -lt $deadline) {
    $state = Get-ErrorState
    if ($state.text -and $state.text -like ('*' + $needle + '*')) { return $state }
    Start-Sleep -Milliseconds 300
  }
  return $state
}
function Go-Libraries {
  $nav = Find-MhAppElement -Name '媒体库' -IncludeOffscreen
  if ($nav) { Invoke-MhElement -Element $nav | Out-Null }
  Start-Sleep -Seconds 3
}
function Wait-Card([string]$pattern, [int]$timeoutSec = 90) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $card = Find-IdLike $pattern
    if ($card) { return $card }
    Go-Libraries
    Start-Sleep -Seconds 2
  }
  return $null
}

# ---------------------------------------------------------------- environment helpers
function Test-Port([int]$port) {
  try {
    $c = New-Object System.Net.Sockets.TcpClient
    $c.Connect('127.0.0.1', $port)
    $c.Close()
    return $true
  } catch { return $false }
}
function Start-FaultServer([int]$port) {
  if (Test-Port $port) { return [pscustomobject]@{ pid = 0; reused = $true } }
  $py = (Get-Command python).Source
  $cmd = '"' + $py + '" -u "' + $faultPy + '" ' + $port
  $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $cmd
    CurrentDirectory = (Split-Path $faultPy -Parent)
  }
  if ($created.ReturnValue -ne 0) { return [pscustomobject]@{ pid = 0; reused = $false; error = 'create-failed' } }
  $deadline = (Get-Date).AddSeconds(20)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $port) { return [pscustomobject]@{ pid = [int]$created.ProcessId; reused = $false } }
    Start-Sleep -Milliseconds 250
  }
  return [pscustomobject]@{ pid = [int]$created.ProcessId; reused = $false; error = 'not-listening' }
}
# 上一轮留在端口上的故障服务器会让「离线」前提失效：卡片能正常打开、页面里没有任何错误，
# 于是 P7 的每一条失败断言都在测一个根本没进入的失败态。必须先证端口空闲再起应用。
# 不能靠 Get-NetTCPConnection -State Listen 找监听者：实测这些 python 监听套接字在该视图里
# 只出现一条 State 为空的行，按 -State Listen 过滤会得到空集，于是「杀进程」什么也没杀。
# 改用 CIM 命令行匹配 —— 命令行里带端口号，定位是确定的。
function Stop-FaultServer([int]$port) {
  $targets = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like ('*fault_server.py* ' + $port + '*') })
  foreach ($t in $targets) { try { Stop-Process -Id $t.ProcessId -Force -ErrorAction Stop } catch { } }
  $deadline = (Get-Date).AddSeconds(10)
  while ((Get-Date) -lt $deadline) {
    if (-not (Test-Port $port)) { return [pscustomobject]@{ killed = @($targets | ForEach-Object { $_.ProcessId }); free = $true } }
    Start-Sleep -Milliseconds 200
  }
  return [pscustomobject]@{ killed = @($targets | ForEach-Object { $_.ProcessId }); free = (-not (Test-Port $port)) }
}
function Find-FreePort([int]$from, [int]$to) {
  for ($candidate = $from; $candidate -le $to; $candidate++) {
    if (-not (Test-Port $candidate)) { return $candidate }
  }
  return 0
}
function Fault-Log([int]$port) { return ($R + 'windows\tools\acceptance\webdav\fault-requests-' + $port + '.jsonl') }
function Tap-All([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  return @(Get-Content -Path $path)
}
function Tap-JsonAt([string]$path, [int]$from) {
  $all = @(Tap-All $path)
  $out = @()
  for ($i = $from; $i -lt $all.Count; $i++) {
    try { $out += ($all[$i] | ConvertFrom-Json) } catch { }
  }
  return @($out)
}
function St($rec) {
  # 抓包里 status 是 WSGI 字符串（"207 Multi-Status"），与整数比较永远不成立。
  if (-not $rec -or -not $rec.status) { return -1 }
  $m = [regex]::Match([string]$rec.status, '^(\d{3})')
  if ($m.Success) { return [int]$m.Groups[1].Value }
  return -1
}
function Fmt($rows) {
  return (@($rows | ForEach-Object { $_.method + ' ' + $_.path + ' -> ' + $_.status }) -join ' | ')
}
function Set-ServersEmpty {
  param([string]$keepRegex)
  $obj = Get-Content -Raw -Encoding UTF8 $settings | ConvertFrom-Json
  $servers = @($obj.Servers | Where-Object { $_.ServerId -match $keepRegex })
  $obj | Add-Member -NotePropertyName Servers -NotePropertyValue $servers -Force
  ($obj | ConvertTo-Json -Depth 10) | Set-Content -Path $settings -Encoding UTF8
  return $servers.Count
}
function Start-AppWithSource([string]$url, [string]$remoteId, [string]$keepRegex) {
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 900
  & $seed webdav $url 'mediahub' 'hub-test-pw' $remoteId 2>&1 | ForEach-Object { Write-Host ('  seed ' + $_) }
  $kept = Set-ServersEmpty $keepRegex
  Write-Host ('  servers kept=' + $kept)
  return (Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 120)
}
function Trace-Mark {
  if (-not (Test-Path $trace)) { return 0 }
  return @(Get-Content $trace).Count
}
function Trace-Since([int]$n) {
  if (-not (Test-Path $trace)) { return @() }
  $all = @(Get-Content $trace)
  $out = @()
  for ($i = $n; $i -lt $all.Count; $i++) { $out += $all[$i] }
  return $out
}

Write-Host '===== P0 环境准备 ====='
Stop-MhAppForced | Out-Null
# 复用上一轮留下的故障服务器会连带复用它的抓包文件（新实例才 truncate），
# 于是"服务器收到了请求"这类断言可能命中上一轮的记录。每次都从空日志开始。
$stop82 = Stop-FaultServer 8182
Write-Host ('  8182 cleanup: killed=' + ($stop82.killed -join ',') + ' free=' + $stop82.free)
$fault82 = Start-FaultServer 8182
Check 'P0 故障服务器在 8182 监听' (Test-Port 8182) ('pid=' + $fault82.pid + ' reused=' + $fault82.reused)
if (-not (Test-Port 8183)) {
  pwsh -NoProfile -File $startServer -Root 'D:\mh-webdav\root\norange' -Port 8183 -StateDir $subrootState 2>&1 |
    Select-Object -Last 2 | ForEach-Object { Write-Host ('  ' + $_) }
  Start-Sleep -Seconds 6
}
Check 'P0 子根 WsgiDAV 在 8183 监听（root 指向 norange 子目录）' (Test-Port 8183) ''

Write-Host '===== P1 子根挂载：来源指向服务器的某个子目录 ====='
$app = Start-AppWithSource 'http://127.0.0.1:8183/' '127.0.0.1:8183' '^webdav:127\.0\.0\.1:8183$'
$subBefore = @(Tap-All $subTapPath).Count
$card = Wait-Card 'browse|webdav*8183*'
Check 'P1 媒体库页出现子根来源卡片' ($null -ne $card) ('id=' + $(if ($card) { $card.Current.AutomationId } else { 'none' }))
$cardName = ''
if ($card) { $cardName = $card.Current.Name; Invoke-MhElementDeep -Element $card | Out-Null }
Check 'P1 子根来源列出该子树的内容（NoRange 无范围.mp4）' (Wait-Row 'NoRange 无范围.mp4' 40) ((Get-RowNames) -join ', ')
$names = Get-RowNames
Check 'P1 只显示子树内容，不含父目录里的 Season 01' (-not ($names -contains 'Season 01')) ($names -join ', ')
Check 'P1 子根条目数正确（1 项）' ($names.Count -eq 1) ('count=' + $names.Count)
Check 'P1 面包屑在子根来源下仍是根目录' (Wait-Text 'BreadcrumbText' '根目录' 20) ('actual=' + (Get-Text 'BreadcrumbText'))
Check 'P1 标题显示来源名' (Wait-Text 'TitleText' $cardName 20) ('actual=' + (Get-Text 'TitleText') + ' expected=' + $cardName)
$subTap = Tap-JsonAt $subTapPath $subBefore
$subProp = @($subTap | Where-Object { $_.method -eq 'PROPFIND' -and (St $_) -eq 207 -and $_.path -eq '/' })
Check 'P1 请求发到子根挂载点（PROPFIND / 207，子目录路径没有出现在请求里）' ($subProp.Count -ge 1) ((Fmt $subTap) + ' all=' + (($subTap | ForEach-Object { $_.path }) -join ','))
Save-MhScreenshot -Path ($ev + '\p1-subroot.png') | Out-Null

Write-Host '===== P2 失败语义：404 ====='
$app = Start-AppWithSource 'http://127.0.0.1:8182/' '127.0.0.1:8182' '^webdav:127\.0\.0\.1:8182$'
$faultPath = Fault-Log 8182
$card = Wait-Card 'browse|webdav*8182*'
Check 'P2 媒体库页出现故障来源卡片' ($null -ne $card) ('id=' + $(if ($card) { $card.Current.AutomationId } else { 'none' }))
if ($card) { Invoke-MhElementDeep -Element $card | Out-Null }
Check 'P2 故障来源的根目录可正常列举（对照组）' (Wait-Row 'ok' 40) ((Get-RowNames) -join ', ')

$m = @(Tap-All $faultPath).Count
Click-Row 'gone' | Out-Null
$state = Wait-ErrorLike '未找到' 30
Check 'P2 404：文案说明未找到' ([bool]($state.text -like '*未找到*')) ('text=' + $state.text)
Check 'P2 404：没有把失败显示成“这个目录是空的”' (-not ($state.text -like '*空的*')) ('text=' + $state.text)
Check 'P2 404：出现可见的重试入口' ($state.retryExists -and -not $state.retryOffscreen) ('exists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
Check 'P2 404：列表被清空，不残留父目录条目' ($state.rows.Count -eq 0) ('rows=' + ($state.rows -join ', '))
$fj = Tap-JsonAt $faultPath $m
Check 'P2 404：服务器确实收到 /gone/ 并回 404' (@($fj | Where-Object { $_.path -eq '/gone/' -and (St $_) -eq 404 }).Count -ge 1) (Fmt $fj)
Save-MhScreenshot -Path ($ev + '\p2-404.png') | Out-Null

$m = @(Tap-All $faultPath).Count
$retry = Find-MhAppElement -AutomationId 'browse.retry' -IncludeOffscreen
Write-Host ('  retry-invoke: ' + (Invoke-Safe $retry))
Start-Sleep -Seconds 3
$fj = Tap-JsonAt $faultPath $m
$state2 = Get-ErrorState
Check 'P2 重试真的重新请求了服务器（第二次 /gone/ 404）' (@($fj | Where-Object { $_.path -eq '/gone/' -and (St $_) -eq 404 }).Count -ge 1) (Fmt $fj)
Check 'P2 重试仍失败时继续说明失败，不假装成功' ([bool]($state2.text -like '*未找到*')) ('text=' + $state2.text)
$up = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Write-Host ('  up-invoke: ' + (Invoke-Safe $up))
Check 'P2 失败后可以返回上级并恢复列举' (Wait-Row 'ok' 30) ((Get-RowNames) -join ', ')
Check 'P2 返回后面包屑回到根目录且返回上级重新禁用' ((Wait-Text 'BreadcrumbText' '根目录' 15) -and -not (Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen).Current.IsEnabled) ('bc=' + (Get-Text 'BreadcrumbText'))

Write-Host '===== P3 失败语义：403 无权限 ====='
$m = @(Tap-All $faultPath).Count
Click-Row 'denied' | Out-Null
$state = Wait-ErrorLike '403' 30
Check 'P3 403：文案带真实状态码' ([bool]($state.text -like '*403*')) ('text=' + $state.text)
Check 'P3 403：没有显示成空目录' (-not ($state.text -like '*空的*')) ('text=' + $state.text)
Check 'P3 403：出现可见的重试入口' ($state.retryExists -and -not $state.retryOffscreen) ('exists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
$fj = Tap-JsonAt $faultPath $m
Check 'P3 403：服务器确实收到 /denied/ 并回 403' (@($fj | Where-Object { $_.path -eq '/denied/' -and (St $_) -eq 403 }).Count -ge 1) (Fmt $fj)
$up = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Write-Host ('  up-invoke: ' + (Invoke-Safe $up))
Check 'P3 403 后可以返回上级' (Wait-Row 'ok' 30) ((Get-RowNames) -join ', ')

Write-Host '===== P4 失败语义：500 服务器错误 ====='
$m = @(Tap-All $faultPath).Count
Click-Row 'broken' | Out-Null
$state = Wait-ErrorLike '500' 30
Check 'P4 500：文案带真实状态码' ([bool]($state.text -like '*500*')) ('text=' + $state.text)
Check 'P4 500：没有显示成空目录' (-not ($state.text -like '*空的*')) ('text=' + $state.text)
Check 'P4 500：出现可见的重试入口' ($state.retryExists -and -not $state.retryOffscreen) ('exists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
$fj = Tap-JsonAt $faultPath $m
Check 'P4 500：服务器确实收到 /broken/ 并回 500' (@($fj | Where-Object { $_.path -eq '/broken/' -and (St $_) -eq 500 }).Count -ge 1) (Fmt $fj)
$up = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Write-Host ('  up-invoke: ' + (Invoke-Safe $up))
Check 'P4 500 后可以返回上级' (Wait-Row 'ok' 30) ((Get-RowNames) -join ', ')

Write-Host '===== P5 失败语义：401 认证 ====='
$m = @(Tap-All $faultPath).Count
Click-Row 'secure' | Out-Null
$state = Wait-ErrorLike '登录' 30
Check 'P5 401：文案指向登录/认证而不是空目录' ([bool]($state.text -like '*登录*')) ('text=' + $state.text)
Check 'P5 401：没有显示成空目录' (-not ($state.text -like '*空的*')) ('text=' + $state.text)
Check 'P5 401：出现可见的重试入口' ($state.retryExists -and -not $state.retryOffscreen) ('exists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
$fj = Tap-JsonAt $faultPath $m
Check 'P5 401：服务器确实收到 /secure/ 并回 401' (@($fj | Where-Object { $_.path -eq '/secure/' -and (St $_) -eq 401 }).Count -ge 1) (Fmt $fj)
Save-MhScreenshot -Path ($ev + '\p5-401.png') | Out-Null
$up = Find-MhAppElement -AutomationId 'browse.up' -IncludeOffscreen
Write-Host ('  up-invoke: ' + (Invoke-Safe $up))
Check 'P5 401 后可以返回上级' (Wait-Row 'ok' 30) ((Get-RowNames) -join ', ')

Write-Host '===== P6 导航竞态：慢服务器上连续两次导航 ====='
$m = @(Tap-All $faultPath).Count
$tMark = Trace-Mark
$r1 = Click-Row 'slow1'
Start-Sleep -Milliseconds 400
$r2 = Click-Row 'slow2'
Check 'P6 两次点击都命中真实行' (($r1 -ne 'ROW-NOT-FOUND') -and ($r2 -ne 'ROW-NOT-FOUND')) ('r1=' + $r1 + ' r2=' + $r2)
$samples = @()
$final = $null
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
  # 面包屑与列表是两个 UIA 属性，分开读就是两个时刻。上一版在“渲染刚完成”的那一帧读到
  # 旧面包屑 + 新列表，于是把一次正确的渲染判成了内容/标题错配。读两次列表把这一对夹在
  # 中间：两次读到同一份列表，才认为这一帧是稳定帧、可以拿来判定。
  $rows1 = Get-RowNames
  $bc = Get-Text 'BreadcrumbText'
  $rows2 = Get-RowNames
  if (($rows1 -join ',') -ne ($rows2 -join ',')) { continue }
  $rows = $rows2
  $samples += [pscustomobject]@{ bc = $bc; rows = ($rows -join ',') }
  if ($rows -contains 'b.mp4') { $final = [pscustomobject]@{ bc = $bc; rows = $rows }; break }
  Start-Sleep -Milliseconds 250
}
$samples | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + 'p6-samples.json') -Encoding UTF8
Check 'P6 最终落在第二个目录（slow2 的 b.mp4）' ([bool]($final -and ($final.rows -contains 'b.mp4'))) ('rows=' + $(if ($final) { $final.rows -join ',' } else { (Get-RowNames) -join ',' }))
Check 'P6 最终面包屑指向 slow2' ([bool]($final -and $final.bc -like '*slow2*')) ('bc=' + $(if ($final) { $final.bc } else { 'none' }))
Check 'P6 最终标题为 slow2' (Wait-Text 'TitleText' 'slow2' 15) ('actual=' + (Get-Text 'TitleText'))
# 判据只能比面包屑的**最后一段**：进入 slow2 之后面包屑本来就会写成 "根目录 / slow1 / slow2"，
# 在整条面包屑里搜 'slow1' 会把一帧完全正确的渲染判成错配（上一版就因此假阳性）。
$mismatch = @($samples | ForEach-Object {
  $crumbLeaf = (($_.bc -split '/')[-1]).Trim()
  [pscustomobject]@{ bc = $_.bc; rows = $_.rows; leaf = $crumbLeaf; bad = (($crumbLeaf -eq 'slow2' -and $_.rows -like '*a.mp4*') -or ($crumbLeaf -eq 'slow1' -and $_.rows -like '*b.mp4*')) }
} | Where-Object { $_.bad })
Check 'P6 全程没有把 A 目录内容配上 B 目录标题（内容与面包屑同源）' ($mismatch.Count -eq 0) ('mismatch=' + $mismatch.Count + ' samples=' + $samples.Count + ' bad=' + (($mismatch | ForEach-Object { $_.bc + '=>' + $_.rows }) -join ' ; '))
$tlines = Trace-Since $tMark
$ttext = ($tlines -join ' | ')
Check 'P6 第二次导航被记为延迟重跑而不是静默丢弃' ($ttext -like '*browse:load defer*') ((@($tlines | Where-Object { $_ -like '*browse:load*' })) -join ' | ')
$gens = @($tlines | Where-Object { $_ -like '*browse:load gen=*' })
Check 'P6 两次导航都真的执行了（两条 load 记录）' ($gens.Count -ge 2) ($gens -join ' | ')
$fj = Tap-JsonAt $faultPath $m
$s1 = @($fj | Where-Object { $_.path -eq '/slow1/' -and (St $_) -eq 207 })
$s2 = @($fj | Where-Object { $_.path -eq '/slow2/' -and (St $_) -eq 207 })
Check 'P6 服务器收到两次慢请求且都成功' (($s1.Count -ge 1) -and ($s2.Count -ge 1)) (Fmt $fj)
Save-MhScreenshot -Path ($ev + '\p6-race.png') | Out-Null

Write-Host '===== P7 离线来源：可见、可重试、恢复后真的列出内容 ====='
# 不用固定端口：任何一轮残留的故障服务器都会让「离线」变成「其实在线」，而这一轮的判据
# 恰恰要观察一个只会在离线时出现的失败态。取一个当场确认空闲的端口，前提就是可证的。
$stop85 = Stop-FaultServer 8185
Write-Host ('  8185 cleanup: killed=' + ($stop85.killed -join ',') + ' free=' + $stop85.free)
$p7Port = Find-FreePort 8210 8259
Check 'P7 前提：选到一个确实没有监听者的端口（否则「离线」不成立，后面的失败态根本不会出现）' (($p7Port -gt 0) -and (-not (Test-Port $p7Port))) ('port=' + $p7Port)
$app = Start-AppWithSource ('http://127.0.0.1:' + $p7Port + '/') ('127.0.0.1:' + $p7Port) ('^webdav:127\.0\.0\.1:' + $p7Port + '$')
$card = Wait-Card ('browse|webdav*' + $p7Port + '*') 90
Check 'P7 离线来源仍出现在媒体库页（不是像被删掉一样消失）' ($null -ne $card) ('id=' + $(if ($card) { $card.Current.AutomationId } else { 'none' }))
if ($card) { Invoke-MhElementDeep -Element $card | Out-Null }
$state = Get-ErrorState
# 失败态的可靠标志是"重试入口出现 + 可见"，不是某条文案刚好被匹配到：
# 页面在"正在读取目录…"阶段的文案是空/加载中，用文案当完成条件会在页面还没进失败态时就判定。
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline -and -not ($state.retryExists -and -not $state.retryOffscreen)) {
  Start-Sleep -Milliseconds 400
  $state = Get-ErrorState
}
Write-Host ('  offline state: text=' + $state.text + ' retryExists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
Check 'P7 离线：浏览页说明连不上服务器' ([bool]($state.text -match '网络|无法连接|连接|超时')) ('text=' + $state.text)
Check 'P7 离线：没有显示成空目录' (-not ($state.text -like '*空的*')) ('text=' + $state.text)
Check 'P7 离线：出现可见的重试入口' ($state.retryExists -and -not $state.retryOffscreen) ('exists=' + $state.retryExists + ' offscreen=' + $state.retryOffscreen)
Save-MhScreenshot -Path ($ev + '\p7-offline.png') | Out-Null

$fault85 = Start-FaultServer $p7Port
Check ('P7 恢复：故障服务器在 ' + $p7Port + ' 起来') ((Test-Port $p7Port) -and (-not $fault85.reused)) ('pid=' + $fault85.pid + ' reused=' + $fault85.reused)
$tMark = Trace-Mark
$retry = Find-MhAppElement -AutomationId 'browse.retry' -IncludeOffscreen
Check 'P7 重试按钮在点击前确实存在于界面' ($null -ne $retry) ('exists=' + ($null -ne $retry))
Write-Host ('  retry-invoke: ' + (Invoke-Safe $retry))
Check 'P7 重试后目录真的列出来了（ok 组出现）' (Wait-Row 'ok' 45) ((Get-RowNames) -join ', ')
Check 'P7 重试后错误提示消失（不再是失败态）' (-not ((Get-ErrorState).retryExists -and -not (Get-ErrorState).retryOffscreen)) ('state=' + ((Get-ErrorState).text))
$tlines = Trace-Since $tMark
Check 'P7 重试有 trace 记录' ((($tlines -join ' | ')) -like '*browse:retry*') ((@($tlines | Where-Object { $_ -like '*browse:*' })) -join ' | ')
$fj = Tap-JsonAt (Fault-Log $p7Port) 0
Check 'P7 恢复后服务器收到 PROPFIND / 207' (@($fj | Where-Object { $_.method -eq 'PROPFIND' -and (St $_) -eq 207 -and $_.path -eq '/' }).Count -ge 1) (Fmt $fj)
Save-MhScreenshot -Path ($ev + '\p7-recovered.png') | Out-Null

Stop-MhAppGracefully | Out-Null
$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' passed, ' + $fail + ' failed')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\checks-part2.json') -Encoding UTF8
if ($fail -gt 0) { exit 1 } else { exit 0 }
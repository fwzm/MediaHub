# U1「继续观看」真实服务器 GUI 验收（Emby / Jellyfin，真实客户端 UIA）。
#
# 被测事实（不重新设计产品，只验收）：
#   - 页面：windows/src/MediaHub.App/Views/ContinueWatchingPage.xaml（带 AutomationId：
#     continue.reload=刷新按钮、continue.grid=GridView、continue.empty=空态 StackPanel；
#     InfoBar StatusBar 仍无 AutomationId，警告只能按文案扫全树）；
#   - 导航：MainWindow.xaml 的 NavigationViewItem Content="继续观看" Tag="continue"；
#   - 数据：只来自各在线来源的 IProviderProgress.GetContinueWatchingAsync(24)
#     （ContinueWatchingPage.xaml.cs:45-69），不读本地历史；行点击 → MainWindow.EnterPlayer(
#     PlaybackRequest{ServerId,ItemId,Title,StartPositionTicks,SeriesId,SeasonNumber,EpisodeNumber})；
#   - 服务器实现：MediaBrowserProvider.GetContinueWatchingAsync 取 Movie,Episode 且过滤
#     UserDataPlaybackPositionTicks > 0 && UserDataPlayed != true。
#
# 判据纪律：
#   1) 「列表里有这条」必须两端同时成立：UI 元素存在性 + 服务器「同源」回读
#      （/Users/{uid}/Items 取 Movie,Episode 后按 position>0 && !played 过滤，与客户端
#      GetContinueWatchingAsync 同一条件；不是 /Users/{uid}/Items/Resume，真服上那里是空的）；
#   2) 「续播位置」只认引擎时钟（Get-MhPlayerState.positionSeconds）与服务器 PlaybackPositionTicks，
#      不接受「点了播放」这种动作证据；
#   3) P4 必须区分「条目消失」与「整页加载失败」——出现任何 InfoBar 警告即判 FAIL，并单独用一条
#      check 证明空态文案真的出现（正确的消失形态是空列表，不是空白页）；
#   4) 需要等待的地方一律「轮询到条件成立或超时」，不用固定长 sleep 冒充断言；
#   5) 任一 check 失败都不中断后续项，最后统一 RESULT 并用退出码表达失败。
#
# 本轮分工：只写脚本 + 静态自检，不运行脚本、不启动 MediaHub、不构建。
#
# 用法：pwsh -File windows\tools\acceptance\u1\continue-watching-gui.ps1 -Server emby
#       pwsh -File windows\tools\acceptance\u1\continue-watching-gui.ps1 -Server jellyfin
param(
  [ValidateSet('emby','jellyfin')][string]$Server = 'emby'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\u1-continue\' + $Server
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe      = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$NL = [Environment]::NewLine

if ($Server -eq 'emby') {
  $base = 'http://127.0.0.1:8097'; $user = 'mh'; $pass = 'mh-test-pass'
  $expectUid = 'a5d8c0447545470fa9e227b942569158'
  $serverId  = '1271d0a1040046db8c0089cc328bbbf1'
  $libName = 'MHMovies'; $itemId = '34'; $itemName = 'Long Resume'
  $verPattern = '4.10.*'
} else {
  $base = 'http://127.0.0.1:8096'; $user = 'mediahub-test'; $pass = 'mediahub-test-password'
  $expectUid = 'c0c242f88d664109a5ed45f8ba43e335'
  $serverId  = 'daffc6534af44bbb8f73848209519255'
  $libName = 'Movies'; $itemId = 'b22e31da9faf6ef247e19f7b3774dab2'; $itemName = 'Long Resume'
  $verPattern = '12.*'
}
$durationSec = 600   # Long Resume 约 600 s（容差见 P1.5）
$navNames = @('首页','媒体库','搜索','继续观看','我的收藏','媒体源','设置')

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}
function Note([string]$text) { Write-Host ('  · ' + $text) }
function Print-Evidence {
  Write-Host ''
  Write-Host '=== 证据产物 ==='
  foreach ($f in @('checks.json','results.md','p2-continue-list.png','p3-resumed.png','p4-after-played.png','p2-page-tree.txt','p4-page-tree.txt','trace.log')) {
    $p = Join-Path $ev $f
    if (Test-Path $p) { Write-Host ('  [有] ' + $p + '  ' + (Get-Item $p).Length + ' B') }
    else { Write-Host ('  [缺] ' + $p) }
  }
}

# ================================================================ 服务器 API
# 鉴权头按要求统一：Emby 与 Jellyfin 都用同一个 MediaBrowser 头（Client/Device/DeviceId/Version + Token）。
$script:authHdr = 'MediaBrowser Client="MediaHub-Acceptance", Device="Acceptance", DeviceId="u1-continue-gui", Version="0.1"'
$script:token = $null
$script:uid = $expectUid
$script:apiErrors = New-Object System.Collections.Generic.List[string]

function Connect-Server {
  $body = @{ Username = $user; Pw = $pass } | ConvertTo-Json -Compress
  $params = @{
    Method      = 'POST'
    Uri         = ($base + '/Users/AuthenticateByName')
    ContentType = 'application/json'
    Headers     = @{ Authorization = $script:authHdr }
    Body        = $body
    TimeoutSec  = 20
  }
  return Invoke-RestMethod @params
}
# 所有服务器调用都过这一层：失败不抛、只记账。这样某一条 API 失败不会打断后面的 GUI 断言。
function Api([string]$method, [string]$path, $body = $null) {
  if (-not $script:token) {
    $script:apiErrors.Add('no-token ' + $method + ' ' + $path)
    return $null
  }
  $params = @{
    Method     = $method
    Uri        = ($base + $path)
    Headers    = @{ Authorization = ($script:authHdr + ', Token="' + $script:token + '"') }
    TimeoutSec = 30
  }
  if ($null -ne $body) {
    $params['Body'] = ($body | ConvertTo-Json -Compress -Depth 6)
    $params['ContentType'] = 'application/json'
  }
  try { return (Invoke-RestMethod @params) }
  catch {
    $script:apiErrors.Add($method + ' ' + $path + ' -> ' + $_.Exception.Message)
    Write-Host ('  api error: ' + $method + ' ' + $path + ' :: ' + $_.Exception.Message)
    return $null
  }
}
function Get-ItemUserData([string]$id) {
  $d = Api 'GET' ('/Users/' + $script:uid + '/Items/' + $id + '?Fields=UserData')
  if (-not $d -or -not $d.UserData) { return $null }
  $ticks = [long]0
  if ($null -ne $d.UserData.PlaybackPositionTicks) { $ticks = [long]$d.UserData.PlaybackPositionTicks }
  return [pscustomobject]@{
    played    = [bool]$d.UserData.Played
    ticks     = $ticks
    seconds   = [math]::Round($ticks / 10000000.0, 2)
    playCount = $d.UserData.PlayCount
  }
}
# 位置清零：先走官方 POST UserData；该端点若不接受（回读仍有位置）再退「标已看→撤销已看」
# （服务端在标记已看时会清掉中断位置）。返回实际生效的策略名，写进 evidence。
function Set-PositionZero([string]$id) {
  $before = $script:apiErrors.Count
  $null = Api 'POST' ('/Users/' + $script:uid + '/Items/' + $id + '/UserData') @{ PlaybackPositionTicks = 0 }
  Start-Sleep -Milliseconds 400
  $now = Get-ItemUserData $id
  if ($now -and $now.ticks -eq 0) {
    if ($script:apiErrors.Count -eq $before) { return 'POST-UserData' }
    return 'POST-UserData(partial)'
  }
  $null = Api 'POST' ('/Users/' + $script:uid + '/PlayedItems/' + $id)
  Start-Sleep -Milliseconds 500
  $null = Api 'DELETE' ('/Users/' + $script:uid + '/PlayedItems/' + $id)
  Start-Sleep -Milliseconds 400
  return 'played-toggle-fallback'
}
function Clear-Played([string]$id) { $null = Api 'DELETE' ('/Users/' + $script:uid + '/PlayedItems/' + $id) }
function Set-Played([string]$id) { $null = Api 'POST' ('/Users/' + $script:uid + '/PlayedItems/' + $id) }
# 只保留被测来源：其它来源会在列表里产生噪声行，让「行数/空态」判据不可解释（u2/u3 的既有范式）。
function Keep-OnlyServer([string]$pattern) {
  $raw = Get-Content $settings -Raw | ConvertFrom-Json
  $raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like $pattern })
  $raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
  return @($raw.Servers).Count
}

function Wait-ItemState([string]$id, [bool]$played, [int]$timeoutSec = 25, [long]$ticks = [long]::MinValue) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-ItemUserData $id
    if ($last -and ($last.played -eq $played) -and (($ticks -eq [long]::MinValue) -or ($last.ticks -eq $ticks))) { return $last }
    Start-Sleep -Milliseconds 500
  }
  return $last
}
# 与客户端同源：客户端现在走 MediaBrowserApi.GetResumableItemsAsync（MediaBrowserProvider.GetContinueWatchingAsync
# 改调它，limit=24 写死）：Recursive=true + IncludeItemTypes=Movie,Episode + Filters=IsResumable + SortBy=DatePlayed
# + SortOrder=Descending + Limit=24 + ImageTypeLimit=1；返回前客户端仍按 PlaybackPositionTicks>0 && Played!=true 兜底过滤。
# 这里用同一查询串（Fields 取 UserData 以便回读位置）+ 同一兜底过滤器，判据与产品同源；不调用 /Users/{uid}/Items/Resume
# （两个真服的 Resume 端点对本夹具返回空，而条目 UserData 里确有真实位置，拿它当判据会把正确行为判成 FAIL）。
$script:continueQuery = '/Users/{uid}/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Filters=IsResumable&Fields=UserData&SortBy=DatePlayed&SortOrder=Descending&Limit=24&ImageTypeLimit=1'
function Get-ContinueList([switch]$All) {
  $q = $script:continueQuery.Replace('{uid}', $script:uid)
  $d = Api 'GET' $q
  if (-not $d -or $null -eq $d.Items) { return @() }
  $out = @()
  foreach ($it in @($d.Items)) {
    $t = [long]0
    if ($it.UserData -and $null -ne $it.UserData.PlaybackPositionTicks) { $t = [long]$it.UserData.PlaybackPositionTicks }
    $p = $false
    if ($it.UserData -and $null -ne $it.UserData.Played) { $p = [bool]$it.UserData.Played }
    if (-not $All) {
      # 与客户端过滤器同一条件（含边界）：position>0 且未看完。
      if ($t -le 0 -or $p) { continue }
    }
    $out += [pscustomobject]@{ id = $it.Id; name = $it.Name; ticks = $t; seconds = [math]::Round($t / 10000000.0, 2); played = $p }
  }
  return $out
}
function Format-ContinueList($items) {
  $list = @($items)
  if ($list.Count -eq 0) { return '（空）' }
  return (($list | ForEach-Object { $_.name + '@' + $_.seconds + 's' }) -join ',')
}
# 幂等关键：上一次跑剩下的进度（含历史剧集）会让「行数」判据不可解释，先把同源候选列表清空。
function Reset-ContinueList([int]$rounds = 4) {
  $log = @()
  for ($i = 1; $i -le $rounds; $i++) {
    $items = @(Get-ContinueList)
    if ($items.Count -eq 0) { return [pscustomobject]@{ clean = $true; rounds = $i; detail = (($log -join ' / ') + ' remaining=0') } }
    foreach ($it in $items) {
      $mode = Set-PositionZero $it.id
      Clear-Played $it.id
      $log += ($it.id + ':' + $it.name + ' was=' + $it.seconds + 's via=' + $mode)
    }
    Start-Sleep -Milliseconds 800
  }
  $left = @(Get-ContinueList)
  return [pscustomobject]@{
    clean  = ($left.Count -eq 0)
    rounds = $rounds
    detail = (($log -join ' / ') + ' remaining=' + $left.Count + ':' + (($left | ForEach-Object { $_.name }) -join ','))
  }
}

# ================================================================ UI helpers
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
function Wait-Element([string]$id, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
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
function Wait-IdLikeName([string]$pattern, [string]$nameNeedle, [int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-IdLikeName $pattern $nameNeedle
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
# 导航加固：ContinueWatchingPage 的标题 TextBlock 也叫「继续观看」，与 NavigationViewItem 同名。
# 取第一个同名元素可能拿到 TextBlock（无 Invoke/SelectionItem 模式）→ 导航静默失效，所以按 ControlType 定位。
function Go-Nav([string]$name) {
  $cands = @(Get-MhAppElementAll -IncludeOffscreen | Where-Object { $_.Current.Name -eq $name })
  foreach ($ct in @('ListItem','Button','TabItem')) {
    foreach ($e in $cands) {
      if ((Get-MhControlType $e) -ne $ct) { continue }
      $r = Invoke-MhElement -Element $e
      if ($r -ne 'NO_PATTERN' -and $r -ne 'NOT_FOUND') {
        Start-Sleep -Milliseconds 900
        return ($r + '/' + $ct)
      }
    }
  }
  if ($cands.Count -gt 0) { return ('NO-ACTIONABLE(' + (Get-MhControlType $cands[0]) + ')') }
  return 'NOT-FOUND'
}
# NavigationView 在目标栏目已选中时不会再触发切换，所以先切「首页」再切回来，保证真的重载页面。
function Go-NavDeep([string]$name) {
  $a = Go-Nav '首页'
  Start-Sleep -Milliseconds 800
  $b = Go-Nav $name
  return ('home=' + $a + ' target=' + $b)
}
function Go-Libraries {
  $null = Go-Nav '首页'
  Start-Sleep -Milliseconds 900
  $r = Go-Nav '媒体库'
  Start-Sleep -Seconds 2
  return $r
}
# 从「媒体库」一级级点到条目详情页（u3 的既有范式，库/条目名按参数传入）。
function Open-ItemDetail([string]$lib, [string]$item) {
  $null = Go-Libraries
  $libCard = Wait-IdLikeName 'library|*' $lib 60
  if (-not $libCard) { return ('LIBRARY-CARD-NOT-FOUND title=' + (Get-Text 'TitleText')) }
  $r1 = Invoke-Deep $libCard
  Start-Sleep -Seconds 2
  $card = Wait-IdLikeName 'media:*' $item 60
  if (-not $card) {
    # 库可能先进到文件夹层，退回库根再试一次
    $back = Find-MhAppElement -AutomationId 'nav.back' -IncludeOffscreen
    if ($back) { $null = Invoke-Safe $back; Start-Sleep -Seconds 2 }
    $card = Wait-IdLikeName 'media:*' $item 30
  }
  if (-not $card) { return ('MEDIA-CARD-NOT-FOUND (' + $r1 + ')') }
  $r2 = Invoke-Deep $card
  $deadline = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen) { return 'OK' }
    $t = Get-Text 'TitleText'
    if ($t -and $t.Contains(' / ')) {
      $inner = Wait-IdLikeName 'media:*' $item 15
      if ($inner) { $null = Invoke-Deep $inner }
    }
    Start-Sleep -Milliseconds 400
  }
  return ('DETAIL-NOT-OPEN title=' + (Get-Text 'TitleText') + ' r1=' + $r1 + ' r2=' + $r2)
}
function Invoke-Deep($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElementDeep -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Invoke-Safe($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $element) }
  catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Save-Shot([string]$name) {
  $p = Join-Path $ev $name
  try {
    $r = Save-MhScreenshot -Path $p
    Note ('screenshot ' + $name + ' ' + $r.size)
    return $p
  } catch {
    Note ('screenshot FAILED ' + $name + ' :: ' + $_.Exception.Message)
    return ''
  }
}
function Save-Tree([string]$name) {
  $lines = @()
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    $lines += ('{0} | id={1} | name={2} | offscreen={3}' -f (Get-MhControlType $e), $c.AutomationId, $c.Name, $c.IsOffscreen)
  }
  $target = Join-Path $ev $name
  if ($lines.Count -eq 0) { Set-Content -Path $target -Value '' -Encoding UTF8 }
  else { $lines | Set-Content -Path $target -Encoding UTF8 }
  return $lines.Count
}

# ---------------------------------------------------------------- player
function Wait-Player([int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
function Wait-PlayerGone([int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (-not (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen)) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
function Get-PositionSeconds {
  $s = Get-MhPlayerState -SkipSlow
  if ($null -eq $s.positionSeconds) { return -1 }
  return [double]$s.positionSeconds
}
function Wait-Position([double]$min, [int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $p = -1
  while ((Get-Date) -lt $deadline) {
    $p = Get-PositionSeconds
    if ($p -ge $min) { return $p }
    Start-Sleep -Milliseconds 400
  }
  return $p
}
function Wait-PositionBetween([double]$lo, [double]$hi, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $p = -1
  while ((Get-Date) -lt $deadline) {
    $p = Get-PositionSeconds
    if ($p -ge $lo -and $p -le $hi) { return $p }
    Start-Sleep -Milliseconds 400
  }
  return $p
}
# Seek 只通过 UIA RangeValuePattern 改播放进度条（0..1 归一化），并回读滑条确认写进去了。
function Seek-ToRatio([double]$ratio) {
  $slider = Wait-Element 'player.seekslider' 20
  if (-not $slider) { return [pscustomobject]@{ ok = $false; reason = 'NO-SLIDER'; min = $null; max = $null; target = $null; before = $null; after = $null } }
  $rv = Get-MhRangeValue $slider
  if (-not $rv) { return [pscustomobject]@{ ok = $false; reason = 'NO-RANGEPATTERN'; min = $null; max = $null; target = $null; before = $null; after = $null } }
  $target = $rv.min + ($rv.max - $rv.min) * $ratio
  $set = Set-MhRangeValue -Element $slider -Value $target
  Start-Sleep -Milliseconds 500
  $after = Get-MhRangeValue $slider
  return [pscustomobject]@{
    ok     = ($set -eq 'SET')
    reason = $set
    min    = $rv.min
    max    = $rv.max
    before = $rv.value
    target = $target
    after  = $(if ($after) { $after.value } else { $null })
  }
}
function Exit-Player([int]$timeoutSec = 30) {
  $back = Wait-Element 'player.back' 15
  $invoke = Invoke-Safe $back
  $gone = Wait-PlayerGone $timeoutSec
  return [pscustomobject]@{ invoke = $invoke; gone = $gone }
}

# ---------------------------------------------------------------- 继续观看页
# 页面身份只认产品新加的 AutomationId（continue.reload / continue.grid / continue.empty）。
# 首跑用「名字叫刷新的 Button」当页面身份、并在全树找同名 ListItem，结果 P2/P3 打在 HomePage 上：
# HomePage 的行是 ContinueItem（本地历史 PlaybackHistory），ContinueWatchingPage 的行是 ContinueRow
# （在线来源进度），两者都可能含同一个标题，全树查找无法区分。
function Find-ContinueGrid { return (Find-MhAppElement -AutomationId 'continue.grid' -IncludeOffscreen) }
function Wait-ContinuePage([int]$timeoutSec = 15) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $btn = Find-MhAppElement -AutomationId 'continue.reload' -IncludeOffscreen
    if ($btn) { return $btn }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
$script:gridRowMode = 'n/a'
function Get-GridRowCandidates($grid) {
  if (-not $grid) { return @() }
  $rows = @()
  foreach ($e in @(Get-MhElementAll -Root $grid)) {
    $ct = Get-MhControlType $e
    if ($ct -eq 'ListItem' -or $ct -eq 'DataItem') { $rows += $e }
  }
  if ($rows.Count -gt 0) { $script:gridRowMode = 'ListItem/DataItem'; return $rows }
  # 兜底：GridViewItem 若没有以 ListItem/DataItem 暴露，用 GridView 的直接子元素当行代理。
  $direct = @()
  try {
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $child = $walker.GetFirstChild($grid)
    while ($child) { $direct += $child; $child = $walker.GetNextSibling($child) }
  } catch { }
  $script:gridRowMode = 'direct-child-fallback'
  return @($direct)
}
function Get-RowText($row) {
  $parts = @()
  $n = $row.Current.Name
  if ($n) { $parts += $n }
  foreach ($e in @(Get-MhElementAll -Root $row)) {
    $dn = $e.Current.Name
    if ($dn) { $parts += $dn }
  }
  return (@($parts) -join ' | ')
}
function Get-GridRows($grid) {
  $out = @()
  foreach ($r in @(Get-GridRowCandidates $grid)) { $out += [pscustomobject]@{ element = $r; text = (Get-RowText $r) } }
  return $out
}
# 只在 continue.grid 子树里枚举行，并轮询到出现（渲染晚于页面就绪）。
function Wait-GridRows([string]$needle, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = [pscustomobject]@{ grid = $null; rows = @(); matches = @() }
  while ((Get-Date) -lt $deadline) {
    $g = Find-ContinueGrid
    $rows = @(Get-GridRows $g)
    $m = @($rows | Where-Object { $_.text.Contains($needle) })
    $last = [pscustomobject]@{ grid = $g; rows = $rows; matches = $m }
    if ($m.Count -ge 1) { return $last }
    Start-Sleep -Milliseconds 400
  }
  return $last
}
function Find-GridRow([string]$needle) {
  $g = Find-ContinueGrid
  foreach ($r in @(Get-GridRows $g)) { if ($r.text.Contains($needle)) { return $r } }
  return $null
}
function Get-GridRowTexts {
  $g = Find-ContinueGrid
  return @((Get-GridRows $g) | ForEach-Object { $_.text })
}
function Wait-GridRowGone([string]$needle, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Find-GridRow $needle
    if (-not $last) { return $null }
    Start-Sleep -Milliseconds 400
  }
  return $last
}
function Get-EmptyStateVisible {
  # continue.empty 是空态 StackPanel 的 AutomationId；Collapsed 时整个子树不在 UIA 树里。
  $e = Find-MhAppElement -AutomationId 'continue.empty' -IncludeOffscreen
  if ($e) { return (-not $e.Current.IsOffscreen) }
  $t = Find-MhAppElement -Name '还没有可继续观看的内容。' -IncludeOffscreen
  if (-not $t) { return $false }
  return (-not $t.Current.IsOffscreen)
}
# InfoBar 没有 AutomationId（产品事实），只能按文案扫全树。命中即说明页面报过错 —— P2/P4 判 FAIL。
function Get-WarningTexts {
  $needles = @('未能返回','未能','无法','失败','错误','警告','异常','Error','error','Warning','warning','未保存')
  $hits = @()
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($null -eq $c.Name -or $c.Name -eq '') { continue }
    # 只看可见元素：InfoBar 关掉后整条不在树里，但 IsOffscreen 的残留文本会把「无警告」判成 FAIL。
    if ($c.IsOffscreen) { continue }
    $ct = Get-MhControlType $e
    if ($ct -ne 'Text' -and $ct -ne 'Pane' -and $ct -ne 'Group' -and $ct -ne 'Custom') { continue }
    foreach ($n in $needles) {
      if ($c.Name.Contains($n)) { $hits += ($ct + ':' + $c.Name); break }
    }
  }
  return @($hits | Select-Object -Unique)
}
# 行上的进度文案（ProgressLabel）形如 "5:03 / 10:00"：它是页面把服务器位置渲染出来的直接证据。
function Find-ProgressText([double]$expectedSeconds, [double]$tolSec = 25) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $n = $e.Current.Name
    if ($null -eq $n) { continue }
    $m = [regex]::Match($n, '^(\d+):([0-5]\d)\s*/\s*(\d+):([0-5]\d)$')
    if (-not $m.Success) { continue }
    $left = [int]$m.Groups[1].Value * 60 + [int]$m.Groups[2].Value
    if ([math]::Abs($left - $expectedSeconds) -le $tolSec) { return $n }
  }
  return ''
}

# ---------------------------------------------------------------- trace
$script:logMark = 0
function Set-LogMark { $script:logMark = @(Get-MhLog).Count }
function Get-NewLog {
  $lines = @(Get-MhLog)
  if ($script:logMark -ge $lines.Count) { return @() }
  return @($lines[$script:logMark..($lines.Count - 1)])
}

# ================================================================ 主流程（任一断言失败都继续往下跑）
Write-Host ('U1 继续观看 GUI 验收：server=' + $Server + ' base=' + $base + ' item=' + $itemName + '(' + $itemId + ') lib=' + $libName)
$script:serverReachable = $false
$script:finalFail = 1
$info = $null
$dllHash = ''
$scriptHash = ''
$apnValue = 'n/a'
$srvPos = -1.0
$p4Path = 'server-api:POST /Users/{uid}/PlayedItems/{id}'

try {
  # -------------------------------------------------------------- P0 前置
  Write-Host ''
  Write-Host ('=== P0 登录、版本、复位与来源播种（' + $Server + '）===')
  $login = $null
  try { $login = Connect-Server } catch { Write-Host ('  login error: ' + $_.Exception.Message) }
  $uidOk = ($null -ne $login) -and ($login.User.Id -eq $expectUid)
  Check 'P0.1 官方服务器登录成功且用户与文档坐标一致' $uidOk ('user=' + $(if ($login) { $login.User.Name } else { 'n/a' }) + ' uid=' + $(if ($login) { $login.User.Id } else { 'n/a' }) + ' expected=' + $expectUid + ' apiErrors=' + $script:apiErrors.Count)
  if ($login) { $script:token = $login.AccessToken; $script:uid = $login.User.Id }

  $info = Api 'GET' '/System/Info'
  $script:serverReachable = ($null -ne $info)
  Check 'P0.2 被测实例身份符合文档坐标（System/Info.Id）' ([bool]($info -and $info.Id -eq $serverId)) ('serverId=' + $(if ($info) { $info.Id } else { 'n/a' }) + ' expected=' + $serverId)
  $verOk = $false
  if ($info -and $info.Version) { $verOk = ($info.Version -like $verPattern) }
  Check 'P0.3 服务器版本符合预期' $verOk ('version=' + $(if ($info) { $info.Version } else { 'n/a' }) + ' expectedLike=' + $verPattern)

  $resetMode = Set-PositionZero $itemId
  Clear-Played $itemId
  $st0 = Wait-ItemState $itemId $false 25 ([long]0)
  Check 'P0.4 长片条目已复位（Played=false 且 PlaybackPositionTicks=0）' ([bool]($st0 -and (-not $st0.played) -and ($st0.ticks -eq 0))) ('mode=' + $resetMode + ' played=' + $(if ($st0) { $st0.played } else { 'n/a' }) + ' posSeconds=' + $(if ($st0) { $st0.seconds } else { 'n/a' }) + ' ticks=' + $(if ($st0) { $st0.ticks } else { 'n/a' }))

  $candBefore = @(Get-ContinueList)
  $candAll = @(Get-ContinueList -All)
  $resetInfo = Reset-ContinueList
  Check 'P0.5 与客户端同源的候选列表已清空（Recursive+Filters=IsResumable 查询再过滤 position>0 且未看完）' ([bool]($script:serverReachable -and $resetInfo.clean)) ('reachable=' + $script:serverReachable + ' probed=' + $candAll.Count + ' withProgress=' + (Format-ContinueList $candBefore) + ' after=empty rounds=' + $resetInfo.rounds + ' detail=' + $resetInfo.detail)

  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 800
  $kept = Keep-OnlyServer ($Server + ':*')
  & $seed $Server $base $user $pass $serverId $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  seed: ' + $_) }
  $cfg = Get-Content $settings -Raw | ConvertFrom-Json
  $entry = @($cfg.Servers | Where-Object { $_.ServerId -eq ($Server + ':' + $serverId) })
  Check 'P0.6 来源写入客户端配置（只保留被测来源一条）' (($entry.Count -eq 1) -and (@($cfg.Servers).Count -eq 1)) ('keptBefore=' + $kept + ' serversNow=' + @($cfg.Servers).Count + ' id=' + (@($cfg.Servers | ForEach-Object { $_.ServerId }) -join ','))
  if ($null -ne $cfg.AutoPlayNext) { $apnValue = $cfg.AutoPlayNext }
  Check 'P0.7 AutoPlayNext 取值已记录（本用例不产生 EOF，不受其影响）' ($null -ne $cfg.AutoPlayNext) ('AutoPlayNext=' + $apnValue + ' 说明=P1 在中点退出、P4 用服务器 API 置已看，都不触发连播/结束判定')

  try { $dllHash = (Get-FileHash (Join-Path (Split-Path $exe -Parent) 'MediaHub.dll') -Algorithm SHA256).Hash } catch { }
  try { $scriptHash = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash } catch { }
  Check 'P0.8 被测产物与脚本指纹已钉住（验收报告可追溯）' ([bool]($dllHash -and $scriptHash -and $dllHash.Length -eq 64)) ('MediaHub.dll SHA256=' + $dllHash + ' script SHA256=' + $scriptHash + ' exeExists=' + (Test-Path $exe))
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 800

  # -------------------------------------------------------------- P1 产生真实进度
  Write-Host ''
  Write-Host '=== P1 媒体库 → 长片 → 播放 → 播到中点 → 退出（产生真实进度）==='
  $app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
  Start-Sleep -Seconds 3
  $hwnd = Get-MhWindowHandle
  if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
  Check 'P1.0 客户端启动且窗口在前台' (($null -ne $app) -and ($null -ne $hwnd)) ('pid=' + $(if ($app) { $app.pid } else { 'n/a' }) + ' hwnd=' + $hwnd)

  Set-LogMark
  $open = Open-ItemDetail $libName $itemName
  Check ('P1.1 媒体库 → ' + $libName + ' → ' + $itemName + ' 详情页可达') ($open -eq 'OK') $open
  $playBtn = Wait-Element 'detail.play' 25
  Check 'P1.2 详情页出现播放入口（detail.play）' ($null -ne $playBtn) $(if ($playBtn) { 'name=' + $playBtn.Current.Name } else { 'detail.play missing' })
  $playInvoke = Invoke-Safe $playBtn
  $playerUp1 = Wait-Player 60
  $st1 = Get-MhPlayerState -SkipSlow
  Check 'P1.3 点播放后进入播放页' $playerUp1 ('invoke=' + $playInvoke)
  Check 'P1.4 播放页标题为长片条目' ([bool]($st1.title -and $st1.title.Contains($itemName))) ('title=' + $st1.title)
  Check 'P1.5 播放时长与条目一致（约 600 s，±30）' ([bool]($null -ne $st1.durationSeconds -and [math]::Abs([double]$st1.durationSeconds - $durationSec) -le 30)) ('duration=' + $st1.duration + ' seconds=' + $st1.durationSeconds + ' expected=' + $durationSec)

  # 先等位置稳稳越过 8 s 再判 > 5 s：Wait-Position 是「到阈值就返回」，直接拿 5 s 阈值去判 -gt 5 会在
  # 恰好读到 5.0 s 时误判（jellyfin 首跑就踩到 position=5s 假红），因此等待阈值与断言阈值之间留余量。
  $pos1 = Wait-Position 8 60
  Check 'P1.6 引擎时钟真的在走（位置 > 5 s）' ([bool]($pos1 -gt 5)) ('position=' + $pos1 + 's waited-to=8s title=' + $st1.title)

  $seek = Seek-ToRatio 0.5
  $lo = $durationSec * 0.4
  $hi = $durationSec * 0.6
  $posMid = Wait-PositionBetween $lo $hi 45
  Check ('P1.7 Seek 到中点后引擎位置落在 40%-60%（' + $lo + '-' + $hi + ' s）') ([bool]($posMid -ge $lo -and $posMid -le $hi)) ('seek=' + $seek.reason + ' sliderMin=' + $seek.min + ' sliderMax=' + $seek.max + ' before=' + $seek.before + ' target=' + $(if ($null -ne $seek.target) { [math]::Round($seek.target, 4) } else { 'n/a' }) + ' after=' + $seek.after + ' position=' + $posMid + 's')

  # 暂停后再读位置作为「退出位置」基准：否则读取与真实停止之间的自然推进会让 delta 抖。
  $pauseInvoke = Invoke-Safe (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen)
  Start-Sleep -Milliseconds 1200
  $posAtExit = Get-PositionSeconds
  $ex1 = Exit-Player 30
  Check 'P1.8 点 player.back 退出播放页回到外壳' $ex1.gone ('pause=' + $pauseInvoke + ' back=' + $ex1.invoke + ' positionAtExit=' + $posAtExit + 's')
  $newLines = @(Get-NewLog | Where-Object { $_ -match 'player:|queue:' })
  foreach ($l in $newLines) { Note ('trace ' + $l) }

  # 停止上报走客户端自己的卸载路径（OnUnloaded → StopAsync），脚本不写任何位置。
  $srv1 = $null
  $deadline = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $deadline) {
    $srv1 = Get-ItemUserData $itemId
    if ($srv1 -and $srv1.ticks -gt 0) { break }
    Start-Sleep -Seconds 1
  }
  $delta1 = -1.0
  if ($srv1) { $delta1 = [math]::Abs($srv1.seconds - $posAtExit) }
  Check 'P1.9 服务器回读 PlaybackPositionTicks > 0 且约等于退出位置（±15 s）' ([bool]($srv1 -and $srv1.ticks -gt 0 -and $delta1 -le 15)) ('server=' + $(if ($srv1) { $srv1.seconds } else { 'n/a' }) + 's ticks=' + $(if ($srv1) { $srv1.ticks } else { 'n/a' }) + ' client=' + $posAtExit + 's delta=' + $(if ($delta1 -ge 0) { [math]::Round($delta1,1) } else { 'n/a' }))
  Check 'P1.10 服务器回读 Played=false（是中断，不是看完）' ([bool]($srv1 -and (-not $srv1.played))) ('played=' + $(if ($srv1) { $srv1.played } else { 'n/a' }) + ' pos=' + $(if ($srv1) { $srv1.seconds } else { 'n/a' }) + 's playCount=' + $(if ($srv1) { $srv1.playCount } else { 'n/a' }))
  if ($srv1) { $srvPos = [double]$srv1.seconds }

  # -------------------------------------------------------------- P2 继续观看页出现该条目
  Write-Host ''
  Write-Host '=== P2 「继续观看」页出现该条目（UI 与服务器两侧）==='
  $nav2 = Go-NavDeep '继续观看'
  $reload2 = Wait-ContinuePage 15
  $grid2 = Find-ContinueGrid
  Check 'P2.0 点导航「继续观看」进入页面（continue.reload 出现且 continue.grid 存在，≤15 s）' ([bool]($null -ne $reload2 -and $null -ne $grid2)) ('nav=' + $nav2 + ' reload=' + $(if ($reload2) { 'continue.reload' } else { 'MISSING' }) + ' grid=' + $(if ($grid2) { 'continue.grid' } else { 'MISSING' }) + ' note=页面身份只认 AutomationId（FavoritesPage 也有名为「刷新」的 Button）')

  $scan2 = Wait-GridRows $itemName 45
  $row2 = $null
  $rowText2 = ''
  if ($scan2.matches.Count -ge 1) { $row2 = $scan2.matches[0].element; $rowText2 = $scan2.matches[0].text }
  $rowTexts2 = @($scan2.rows | ForEach-Object { $_.text })
  $tree2 = Save-Tree 'p2-page-tree.txt'
  Save-Shot 'p2-continue-list.png' | Out-Null
  Check 'P2.1 continue.grid 子树里恰好 1 行含长片条目名（全树找会命中 HomePage 的本地历史行）' ([bool]($scan2.matches.Count -eq 1)) ('matched=' + $scan2.matches.Count + ' gridRows=' + $rowTexts2.Count + ' rowMode=' + $script:gridRowMode + ' row=' + $rowText2 + ' allGridRows=' + ($rowTexts2 -join ' || ') + ' treeElements=' + $tree2)

  $warn2 = @(Get-WarningTexts)
  Check 'P2.2 继续观看页没有 InfoBar 警告（页面未报「未能返回继续观看列表」）' ($warn2.Count -eq 0) ('warnings=' + $(if ($warn2.Count -eq 0) { 'none' } else { $warn2 -join ' / ' }))
  Check 'P2.3 列表不是空态（continue.empty 不可见且未显示「还没有可继续观看的内容。」）' (-not (Get-EmptyStateVisible)) ('emptyVisible=' + (Get-EmptyStateVisible))

  $srvList2 = @(Get-ContinueList)
  $probed2 = @(Get-ContinueList -All)
  $has2 = @($srvList2 | Where-Object { $_.id -eq $itemId }).Count -ge 1
  Check 'P2.4 服务器同源判据也列出该条目（/Users/{uid}/Items 过滤 position>0 且未看完）' $has2 ('sameSourceList=' + (Format-ContinueList $srvList2) + ' contains=' + $has2 + ' probed=' + $probed2.Count + ' endpoint=' + $script:continueQuery)

  $progressLabel = Find-ProgressText $srvPos 25
  $labelInRow = ($progressLabel -ne '' -and $rowText2.Contains($progressLabel))
  Check 'P2.5 行上的进度文案来自服务器记录（±25 s，且出现在该行文本里）' ([bool]($srvPos -gt 0 -and $progressLabel -ne '' -and $labelInRow)) ('label=' + $(if ($progressLabel) { $progressLabel } else { 'NOT-FOUND' }) + ' inRow=' + $labelInRow + ' serverPos=' + $srvPos + 's row=' + $rowText2)

  # -------------------------------------------------------------- P3 从卡片续播
  Write-Host ''
  Write-Host '=== P3 点继续观看卡片续播（位置应接上服务器记录）==='
  $row3 = $row2
  $rowText3 = $rowText2
  $invoke3 = 'SKIPPED-NO-ROW'
  $playerUp3 = $false
  $st3 = $null
  if ($row3) {
    # 只点 P2.1 存下来的那一个行元素；「全树第一个同名元素」兜底已删除（首跑就是那样点到首页历史行的）。
    $invoke3 = Invoke-Deep $row3
    $playerUp3 = Wait-Player 60
    if ($playerUp3) { $st3 = Get-MhPlayerState -SkipSlow }
  } else {
    Note 'P2.1 未定位到行：按设计不再退化为全树同名元素，P3 直接判 FAIL'
  }
  $dur3 = 'n/a'
  if ($st3 -and $null -ne $st3.durationSeconds) { $dur3 = $st3.durationSeconds }
  Check 'P3.0 只 invoke P2.1 存下的那一行并进入播放页' ([bool]($row3 -and $playerUp3)) ('invoke=' + $invoke3 + ' row=' + $rowText3)
  $titleOk3 = [bool]($st3 -and $st3.title -and $st3.title.Contains($itemName))
  $durOk3 = [bool]($st3 -and $null -ne $st3.durationSeconds -and [math]::Abs([double]$st3.durationSeconds - $durationSec) -le 30)
  Check 'P3.1 播放页标题含长片条目名且时长与条目一致（排除播成了别的条目）' ($titleOk3 -and $durOk3) ('title=' + $(if ($st3) { $st3.title } else { 'n/a' }) + ' duration=' + $dur3 + ' expected=' + $durationSec + ' titleOk=' + $titleOk3 + ' durOk=' + $durOk3)

  $pos3 = -1.0
  if ($playerUp3) { $pos3 = Wait-Position 1 15 }
  Check 'P3.2 续播位置 > 0' ([bool]($pos3 -gt 0)) ('position=' + $pos3 + 's duration=' + $dur3)
  $delta3 = -1.0
  if ($srvPos -gt 0 -and $pos3 -ge 0) { $delta3 = [math]::Abs($pos3 - $srvPos) }
  Check 'P3.3 续播起点与服务器记录位置差 <= 15 s' ([bool]($srvPos -gt 0 -and $delta3 -ge 0 -and $delta3 -le 15)) ('player=' + $pos3 + 's server=' + $srvPos + 's delta=' + $(if ($delta3 -ge 0) { [math]::Round($delta3,1) } else { 'n/a' }))
  # 反假阳性：从零起播后自然前进也满足「位置 > 0」，但不可能一上来就跳过 30 s 以上。
  Check 'P3.4 起点不是从 0 开始（> 30 s，排除「从零起播后自然前进」的假阳性）' ([bool]($pos3 -gt 30)) ('position=' + $pos3 + 's threshold=30s')
  Save-Shot 'p3-resumed.png' | Out-Null

  $posAtExit3 = -1.0
  $ex3 = [pscustomobject]@{ invoke = 'SKIPPED-NO-PLAYER'; gone = $false }
  if ($playerUp3) {
    $posAtExit3 = Get-PositionSeconds
    $ex3 = Exit-Player 30
  }
  $srv3 = $null
  $deadline = (Get-Date).AddSeconds(30)
  while ($playerUp3 -and ((Get-Date) -lt $deadline)) {
    $srv3 = Get-ItemUserData $itemId
    if ($srv3 -and $srv3.ticks -gt 0 -and $posAtExit3 -ge 0 -and ([math]::Abs($srv3.seconds - $posAtExit3) -le 15)) { break }
    Start-Sleep -Seconds 1
  }
  $delta3b = -1.0
  if ($srv3) { $delta3b = [math]::Abs($srv3.seconds - $posAtExit3) }
  Check 'P3.5 续播退出后服务器位置被这次播放更新（±15 s）' ([bool]($ex3.gone -and $srv3 -and $srv3.ticks -gt 0 -and $delta3b -le 15)) ('back=' + $ex3.invoke + ' server=' + $(if ($srv3) { $srv3.seconds } else { 'n/a' }) + 's client=' + $posAtExit3 + 's delta=' + $(if ($delta3b -ge 0) { [math]::Round($delta3b,1) } else { 'n/a' }))
  if ($srv3) { $srvPos = [double]$srv3.seconds }

  # -------------------------------------------------------------- P4 已看完 → 不再出现
  Write-Host ''
  Write-Host '=== P4 已看完的条目不再出现在继续观看 ==='
  Write-Host ('  路径：' + $p4Path + '（API 置已看 + 回读确认；不走「播到 EOF」路径，理由见 results.md）')
  Set-Played $itemId
  $st4 = Wait-ItemState $itemId $true 25
  Check 'P4.0 服务器侧已置为已看完并回读确认（played=true）' ([bool]($st4 -and $st4.played)) ('path=' + $p4Path + ' played=' + $(if ($st4) { $st4.played } else { 'n/a' }) + ' pos=' + $(if ($st4) { $st4.seconds } else { 'n/a' }) + 's playCount=' + $(if ($st4) { $st4.playCount } else { 'n/a' }))
  $srvList4 = @(Get-ContinueList)
  $has4 = @($srvList4 | Where-Object { $_.id -eq $itemId }).Count -ge 1
  Check 'P4.1 服务器同源判据不再提供该条目（外部判据：Recursive+Filters=IsResumable 查询 + position>0 且未看完过滤）' (-not $has4) ('sameSourceList=' + (Format-ContinueList $srvList4) + ' contains=' + $has4)

  # 退出播放页后外壳仍停在同一个「继续观看」页实例上（页面不会自动重载），所以必须点页面自带刷新（continue.reload）。
  # 刷新走 LoadAsync，而 LoadAsync 有 _loading 重入守卫（ContinueWatchingPage.xaml.cs:32-36）：若点击恰好落在一次仍在飞
  # 的加载里就成了空操作（jellyfin 首跑一次点击后旧行留存 45 s）。所以改成「点一次 → 轮询 12 s → 仍在就再点」最多 4 次，
  # 每次结果都记进 detail，既排掉脚本竞态，也能区分「点不动」和「点了但列表没变」。
  $ref4 = 'NOT-INVOKED'
  $residual = $null
  $attempts4 = @()
  for ($attempt = 1; $attempt -le 4; $attempt++) {
    $refresh4 = Wait-ContinuePage 15
    if (-not $refresh4) {
      $nav4 = Go-NavDeep '继续观看'
      Note ('页面不在继续观看，重新导航：' + $nav4)
      $refresh4 = Wait-ContinuePage 15
    }
    $r4 = Invoke-Safe $refresh4
    if ($attempt -eq 1) { $ref4 = $r4 }
    $residual = Wait-GridRowGone $itemName 12
    $attempts4 += ('#' + $attempt + '=' + $r4 + '/' + $(if ($residual) { 'row-still-there' } else { 'row-gone' }))
    if ($null -eq $residual) { break }
  }
  $grid4 = Find-ContinueGrid
  $rowTexts4 = @(Get-GridRowTexts)
  $warn4 = @(Get-WarningTexts)
  $empty4 = Get-EmptyStateVisible
  $tree4 = Save-Tree 'p4-page-tree.txt'
  Save-Shot 'p4-after-played.png' | Out-Null
  Check 'P4.2 点「刷新」后 continue.grid 里不再有该条目' ([bool]($attempts4.Count -gt 0 -and $ref4 -ne 'NO-ELEMENT' -and $ref4 -ne 'NOT-INVOKED' -and $null -eq $residual -and $null -ne $grid4)) ('refreshAttempts=' + ($attempts4 -join ';') + ' grid=' + $(if ($grid4) { 'continue.grid' } else { 'MISSING' }) + ' residual=' + $(if ($residual) { $residual.text } else { 'none' }) + ' sameSourceList=' + (Format-ContinueList $srvList4) + ' rows=' + ($rowTexts4 -join ' || '))
  Check 'P4.3 消失不是「整页加载失败」：没有 InfoBar 警告' ($warn4.Count -eq 0) ('warnings=' + $(if ($warn4.Count -eq 0) { 'none' } else { $warn4 -join ' / ' }) + ' treeElements=' + $tree4)
  Check 'P4.4 列表呈真空态（continue.empty 可见或「还没有可继续观看的内容。」可见）' $empty4 ('emptyVisible=' + $empty4 + ' rows=' + ($rowTexts4 -join ' || '))
}
catch {
  Check 'HARNESS 未预期异常（已记录，继续走 P5 收尾）' $false ($_.Exception.Message + ' @ line ' + $_.InvocationInfo.ScriptLineNumber)
}
finally {
  # -------------------------------------------------------------- P5 清理
  Write-Host ''
  Write-Host '=== P5 清理与复位（幂等：重跑不带上一次状态）==='
  try {
    $resetMode5 = Set-PositionZero $itemId
    Clear-Played $itemId
    $st5 = Wait-ItemState $itemId $false 25 ([long]0)
    Check 'P5.1 长片条目已复位（Played=false 且 pos=0）' ([bool]($st5 -and (-not $st5.played) -and ($st5.ticks -eq 0))) ('mode=' + $resetMode5 + ' played=' + $(if ($st5) { $st5.played } else { 'n/a' }) + ' ticks=' + $(if ($st5) { $st5.ticks } else { 'n/a' }))
    $list5 = @(Get-ContinueList)
    $all5 = @(Get-ContinueList -All)
    Check 'P5.2 同源判据已不再包含该条目（清理彻底）' (@($list5 | Where-Object { $_.id -eq $itemId }).Count -eq 0) ('sameSourceList=' + (Format-ContinueList $list5) + ' probed=' + $all5.Count)
  } catch {
    Check 'P5 服务器复位失败' $false ($_.Exception.Message)
  }
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 500
  $procLeft = @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count
  Check 'P5.3 客户端进程已停止（错误路径也不留进程）' ($procLeft -eq 0) ('remaining=' + $procLeft)

  # -------------------------------------------------------------- 证据落盘
  try { Copy-Item (Get-MhLogPath) (Join-Path $ev 'trace.log') -Force } catch { Note 'trace.log 复制失败' }
  $traceAll = @()
  try { $traceAll = @(Get-MhLog) } catch { }
  $traceKey = @($traceAll | Where-Object { $_ -match 'player:start|player:stop|queue:|nav:|continue' })

  $pass = @($checks | Where-Object { $_.pass }).Count
  $fail = @($checks | Where-Object { -not $_.pass }).Count
  try { $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8 } catch { Note ('checks.json 写入失败: ' + $_.Exception.Message) }

  $md = @()
  $md += ('# U1「继续观看」真实服务器 GUI 验收（' + $Server + '）')
  $md += ''
  $md += ('- 服务器：' + $base + '  版本 ' + $(if ($info) { $info.Version } else { 'n/a' }) + '  ServerId ' + $serverId)
  $md += ('- 媒体库/条目：' + $libName + ' / ' + $itemName + ' (' + $itemId + ')，期望时长 ' + $durationSec + ' s')
  $md += ('- 断言：' + $pass + ' 通过 / ' + $fail + ' 失败')
  $md += ('- 被测 exe：' + $exe + '（未重建）')
  $md += ('- MediaHub.dll SHA256：' + $dllHash)
  $md += ('- 本脚本 SHA256：' + $scriptHash)
  $md += ('- AutoPlayNext：' + $apnValue + '（只记录；本用例不产生 EOF，不受其影响）')
  $md += ''
  $md += '## P4 已看完状态的产生路径'
  $md += ('- 采用：' + $p4Path + '，写后用 UserData 回读确认（P4.0），并以「与客户端同源的候选列表」作为外部判据（P4.1）。')
  $md += '- 未采用「seek 到 99% 播到 EOF」：该路径同时依赖结束判定、连播开关与 EOF 时序，属于 W8/S1 的验收范围；放进 U1 会让「列表筛选」这条断言被无关环节拖红，也无法区分是筛选错还是结束标记错。'
  $md += ''
  $md += '## 判据来源（首跑缺陷修复）'
  $md += '- 页面身份与行定位：只认产品新加的 AutomationId（continue.reload / continue.grid / continue.empty），行只在 continue.grid 子树里枚举。首跑用「名字是刷新的 Button」当页面身份、并在全树找同名 ListItem，结果打在 HomePage 的本地最近播放（ContinueItem / PlaybackHistory）上——P3 因此播成了别的条目（Example Show S01E03 / 0:14）。'
  $md += '- 服务器侧：与客户端 GetResumableItemsAsync（MediaBrowserApi.cs，由 MediaBrowserProvider.GetContinueWatchingAsync 调用，limit=24）同源——GET /Users/{uid}/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Filters=IsResumable&Fields=UserData&SortBy=DatePlayed&SortOrder=Descending&Limit=24&ImageTypeLimit=1，再按 PlaybackPositionTicks > 0 且 Played 不为 true 兜底过滤。不使用 /Users/{uid}/Items/Resume：两个真服上该端点对本夹具返回空（而条目 UserData 里确有真实位置），拿它当判据会把产品正确行为判成 FAIL。'
  $md += '- 警告扫描只看可见元素（IsOffscreen 的残留文本不算），避免把「InfoBar 已关闭」误判成「有警告」。'
  $md += ''
  $md += '## 断言明细'
  foreach ($c in $checks) { $md += ('- [' + $(if ($c.pass) { 'PASS' } else { 'FAIL' }) + '] ' + $c.name + ' — ' + $c.detail) }
  $md += ''
  $md += '## 关键 trace（startup.log 过滤：player:/queue:/nav:/continue）'
  if ($traceKey.Count -eq 0) { $md += '- （无匹配行；完整日志见 trace.log）' }
  foreach ($l in $traceKey) { $md += ('- ' + $l) }
  $md += ''
  $md += '## 稳定性加固（最可能 flaky 的三处）'
  $md += '- ① 继续观看页的刷新时机：页面 Loaded 才拉服务器列表，导航到已选中栏目不会重载。加固：Go-NavDeep 先切「首页」再切目标栏目；P4 用页面自带「刷新」按钮并轮询到条目消失（45 s）而非固定 sleep；导航按 ControlType=ListItem 定位，避开与页面标题同名的 TextBlock。'
  $md += '- ② Seek 与位置判定：只认引擎时钟与服务器 ticks。加固：滑条值读写都走 RangeValuePattern 并回读 after 值；位置用 Wait-PositionBetween 轮询到 40%-60% 窗口；退出前先暂停再读基准位置，避免「读-停」之间的自然推进污染 ±15 s 容差。'
  $md += '- ③ 行定位与警告判定：同一个标题会同时出现在首页「本地最近播放」和继续观看页，全树按名字找必然会打偏。加固：行只在 continue.grid 子树里枚举（ListItem/DataItem，取不到时改用直接子元素并记录 rowMode），恰好 1 行才算通过；P3 只点 P2.1 存下的那个元素、不做全树兜底；「无警告」与「真空态」拆成两条 check，避免把「整页加载失败」误判成「条目按预期消失」。'
  $md += ''
  $md += '## 证据产物'
  $md += '- checks.json（逐条断言与 detail）、results.md（本文件）、p2-continue-list.png、p3-resumed.png、p4-after-played.png、p2-page-tree.txt、p4-page-tree.txt、trace.log'
  try { $md | Set-Content (Join-Path $ev 'results.md') -Encoding UTF8 } catch { Note ('results.md 写入失败: ' + $_.Exception.Message) }

  Print-Evidence
  Write-Host ''
  Write-Host ('RESULT: ' + $pass + ' passed, ' + $fail + ' failed')
  $script:finalFail = $fail
}

if ($script:finalFail -gt 0) { exit 1 } else { exit 0 }

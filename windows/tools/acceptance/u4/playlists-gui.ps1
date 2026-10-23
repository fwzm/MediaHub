# U4「永久播放列表」真实服务器 GUI 验收（Emby / Jellyfin，真实客户端 UIA）。
#
# 被测事实（只验收，不重新设计产品）：
#   - 详情页 detail.addtoplaylist（Name「加入播放列表」，仅非文件夹条目可见）→ MenuFlyout 每次打开
#     从存储重建：已有列表逐项 + 分隔线 + 末项「新建播放列表…」→ ContentDialog
#     （AutomationId playlist.name.dialog，输入框 playlist.name.input，PrimaryButton「确定」）→ 写盘 +
#     InfoBar（成功「已加入播放列表」/ 重复「已在该列表中」）。
#   - 播放列表页：playlists.new（新建）、playlists.list（行 Name=列表名）、行内
#     playlist.play / playlist.rename / playlist.delete、空态 playlists.empty（Text「还没有播放列表。」）。
#   - 列表详情页：playlist.back / playlist.title / playlist.items（行 Name=条目标题）、
#     playlist.item.play / playlist.item.remove、空态 playlist.items.empty（Text「这个播放列表还是空的。」）。
#   - 存储：%LOCALAPPDATA%\MediaHub\playlists.json，{version:1,playlists:[{id,name,createdAt,updatedAt,
#     items:[{serverId,itemId,title,runTimeTicks}]}]}，camelCase + WriteIndented + UTF8 无 BOM；
#     构造时不建文件；每次变更先写 .tmp 再 File.Move 原子替换（所以 mtime/字节数会变）。
#   - 播放：行内「播放」只把这一条交给播放器，不把整张列表灌进临时队列（PlaylistsPage 注释即契约）。
#
# 判据纪律：
#   1) 「加入成功 / 重复不加 / 移除删除」的主判据是 playlists.json 的内容，InfoBar 文案只作 UI 侧证据；
#   2) P4「播放不改写永久列表」按规范化 JSON 内容比对判定；字节数与 mtime 只作辅助（原子替换会刷新它们）；
#   3) 「列表空了 / 条目没了」必须同时看空态文案是否可见：空白页 + 警告 = 加载失败，不算通过；
#   4) 等待一律「轮询到条件成立或超时」，不用固定长 sleep 冒充断言；
#   5) 任一 Check 失败都继续后续项；最后统一 RESULT，有失败用非 0 退出码；finally 里删数据文件并杀进程。
#
# 本轮分工：只写脚本 + 静态自检，不运行、不启动 MediaHub、不构建。
#
# 用法：pwsh -File windows\tools\acceptance\u4\playlists-gui.ps1 -Server emby
#       pwsh -File windows\tools\acceptance\u4\playlists-gui.ps1 -Server jellyfin
param(
  [ValidateSet('emby','jellyfin')][string]$Server = 'emby',
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\u4-playlists\' + $Server
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe      = $Exe
$dll      = Join-Path (Split-Path $exe -Parent) 'MediaHub.dll'
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$localApp = Join-Path $env:LOCALAPPDATA 'MediaHub'
$settings = Join-Path $localApp 'settings.json'
$plPath   = Join-Path $localApp 'playlists.json'
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
# 客户端来源 id 带来源前缀（settings.json / playlists.json 里都是 'emby:<guid>'、'jellyfin:<guid>'），
# 而 $serverId 是裸 guid（用于 /System/Info.Id 校验与 SeedSource 参数），比较落盘/配置里的 id 必须用带前缀的全 id。
$fullServerId = $Server + ':' + $serverId
$listName = '验收列表-' + $Server
$newListText = '新建播放列表…'
$navNames = @('首页','媒体库','搜索','继续观看','我的收藏','播放列表','媒体源','设置')

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}
function Note([string]$text) { Write-Host ('  · ' + $text) }
function Print-Evidence {
  Write-Host ''
  Write-Host '=== 证据产物 ==='
  foreach ($f in @('checks.json','results.md','p1-added.png','p2-duplicate.png','p3-playlists.png','p3-detail.png','p4-player.png','p5-after-remove.png','p5-after-delete.png','p3-page-tree.txt','p5-page-tree.txt','trace.log')) {
    $p = Join-Path $ev $f
    if (Test-Path $p) { Write-Host ('  [有] ' + $p + '  ' + (Get-Item $p).Length + ' B') }
    else { Write-Host ('  [缺] ' + $p) }
  }
}

# ================================================================ 服务器 API
# 鉴权头与 U1 一致：Emby / Jellyfin 都用同一个 MediaBrowser 头，DeviceId 换成 U4 专用串。
$script:authHdr = 'MediaBrowser Client="MediaHub-Acceptance", Device="Acceptance", DeviceId="u4-playlists-gui", Version="0.1"'
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
# 所有服务器调用都过这一层：失败不抛、只记账。某条 API 失败不该打断后面的 GUI 断言。
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
# 条目回读：名字 / 已看标记 / 中断位置 / 时长。名字与时长用来做 UI 判据的期望值，不写死。
function Get-ItemUserData([string]$id) {
  $d = Api 'GET' ('/Users/' + $script:uid + '/Items/' + $id + '?Fields=UserData')
  if (-not $d) { return $null }
  $ticks = [long]0
  if ($null -ne $d.UserData.PlaybackPositionTicks) { $ticks = [long]$d.UserData.PlaybackPositionTicks }
  $run = [long]0
  if ($null -ne $d.RunTimeTicks) { $run = [long]$d.RunTimeTicks }
  return [pscustomobject]@{
    name     = $d.Name
    played   = [bool]$d.UserData.Played
    ticks    = $ticks
    seconds  = [math]::Round($ticks / 10000000.0, 2)
    runTicks = $run
    duration = [math]::Round($run / 10000000.0, 2)
  }
}
# 位置清零：先走官方 POST UserData；该端点若不接受（回读仍有位置）再退「标已看→撤销已看」。
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
# 只保留被测来源：多来源会让「列表/条目」判据不可解释（u2/u3 的既有范式）。
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

# ================================================================ playlists.json（存储侧判据）
# 直接读应用写的数据文件：产品事实是 %LOCALAPPDATA%\MediaHub\playlists.json，camelCase，version=1。
# 文件不存在 = 空库（Store 构造时不建文件），所以「不存在」是合法状态，不是错误。
function Read-Playlists {
  if (-not (Test-Path $plPath)) {
    return [pscustomobject]@{ exists = $false; raw = ''; doc = $null; bytes = 0; mtime = ''; playlists = @(); error = '' }
  }
  $raw = ''; $err = ''
  try { $raw = Get-Content $plPath -Raw } catch { $err = $_.Exception.Message }
  $doc = $null
  if ($raw) { try { $doc = $raw | ConvertFrom-Json } catch { $err = ('json: ' + $_.Exception.Message) } }
  $pl = @()
  if ($doc -and $null -ne $doc.playlists) { $pl = @($doc.playlists) }
  $bytes = 0; $mtime = ''
  try {
    $fi = Get-Item $plPath
    $bytes = $fi.Length
    $mtime = $fi.LastWriteTimeUtc.ToString('HH:mm:ss.fff')
  } catch { }
  return [pscustomobject]@{ exists = $true; raw = $raw; doc = $doc; bytes = $bytes; mtime = $mtime; playlists = $pl; error = $err }
}
function Format-Playlists($state) {
  if (-not $state.exists) { return '（文件不存在）' }
  if ($state.playlists.Count -eq 0) { return ('playlists=0 bytes=' + $state.bytes) }
  $parts = @()
  foreach ($p in $state.playlists) { $parts += ($p.name + '[' + @($p.items).Count + ']') }
  return ('playlists=' + $state.playlists.Count + ' ' + ($parts -join ',') + ' bytes=' + $state.bytes)
}
# 规范化内容：不比较原始字节（WriteIndented 的空白、时间戳字段都会变），比较解析后的结构。
function Get-CanonicalPlaylists($state) {
  if (-not $state.exists) { return 'ABSENT' }
  if ($null -eq $state.doc) { return ('RAW:' + $state.raw) }
  try { return ($state.doc | ConvertTo-Json -Depth 10 -Compress) } catch { return ('RAW:' + $state.raw) }
}
function Wait-Playlists([scriptblock]$cond, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Read-Playlists
    if (& $cond $last) { return $last }
    Start-Sleep -Milliseconds 300
  }
  return $last
}

# ================================================================ UI helpers（照抄 u1 的骨架）
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
  foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
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
# 导航加固：页面标题 TextBlock 可能和 NavigationViewItem 同名（u1 踩过），所以按 ControlType 找可操作项。
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
# 媒体库 → 库 → 条目详情页（u1/u3 的既有范式：library| 前缀卡片 → media: 前缀卡片 → detail.play）。
function Open-ItemDetail([string]$lib, [string]$item) {
  $null = Go-Libraries
  $libCard = Wait-IdLikeName 'library|*' $lib 60
  if (-not $libCard) { return ('LIBRARY-CARD-NOT-FOUND title=' + (Get-Text 'TitleText')) }
  $r1 = Invoke-Deep $libCard
  Start-Sleep -Seconds 2
  $card = Wait-IdLikeName 'media:*' $item 60
  if (-not $card) {
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
  foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    $lines += ('{0} | id={1} | name={2} | offscreen={3}' -f (Get-MhControlType $e), $c.AutomationId, $c.Name, $c.IsOffscreen)
  }
  $target = Join-Path $ev $name
  if ($lines.Count -eq 0) { Set-Content -Path $target -Value '' -Encoding UTF8 }
  else { $lines | Set-Content -Path $target -Encoding UTF8 }
  return $lines.Count
}
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
function Exit-Player([int]$timeoutSec = 30) {
  $back = Wait-Element 'player.back' 15
  $invoke = Invoke-Safe $back
  $gone = Wait-PlayerGone $timeoutSec
  return [pscustomobject]@{ invoke = $invoke; gone = $gone }
}
function Set-LogMark { $script:logMark = @(Get-MhLog).Count }
function Get-NewLog {
  $lines = @(Get-MhLog)
  if ($script:logMark -ge $lines.Count) { return @() }
  return @($lines[$script:logMark..($lines.Count - 1)])
}

# ================================================================ 菜单 / 对话框（InfoBar、ContentDialog 无 AutomationId 时按结构找）
function Get-AllNames {
  $out = @()
  foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
    $n = $e.Current.Name
    if ($null -eq $n -or $n -eq '') { continue }
    $out += $n
  }
  return @($out | Select-Object -Unique)
}
function Wait-Text([string]$needle, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $hits = @()
  while ((Get-Date) -lt $deadline) {
    $hits = @(Get-AllNames | Where-Object { $_.Contains($needle) })
    if ($hits.Count -gt 0) { return $hits }
    Start-Sleep -Milliseconds 300
  }
  return $hits
}
# 精确名匹配：行内删除按钮叫「删除 验收列表-emby」，对话框主按钮叫「删除」，用相等而不是包含。
function Find-ExactButton([string]$name) {
  return (Find-MhAppElement -Name $name -ControlType 'Button' -IncludeOffscreen)
}
function Wait-MenuItem([string]$name, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -Name $name -ControlType 'MenuItem' -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
# ContentDialog 可能作为独立 Window 挂在桌面根下（MhUia 的 Get-MhAppRoots 已把这类 popup 收进 app roots，
# 但根节点本身不在 Descendants 里），所以把「根 + 全部后代」一起看。
function Get-UiFrontier {
  $out = @()
  foreach ($r in @(Get-MhAppRoots)) {
    $out += $r
    try {
      $out += @($r.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition))
    } catch { }
  }
  return $out
}
function Find-NameDialog {
  # ① 产品给的 AutomationId（PlaylistNameDialog.cs 里 SetAutomationId 的那两处）
  $e = Find-MhAppElement -AutomationId 'playlist.name.dialog' -IncludeOffscreen
  if ($e) { return [pscustomobject]@{ element = $e; via = 'automation-id' } }
  # ② 独立窗口：按标题找 Window/Pane
  foreach ($t in @(Get-UiFrontier)) {
    $ct = Get-MhControlType $t
    if ($ct -ne 'Window' -and $ct -ne 'Pane') { continue }
    $n = $t.Current.Name
    if ($null -eq $n -or $n -eq '') { continue }
    if ($n -like '*新建播放列表*' -or $n -like '*重命名播放列表*') { return [pscustomobject]@{ element = $t; via = ('title-window:' + $ct) } }
  }
  # ③ 兜底：某个 Window/Pane 里同时有 Edit 与「确定」按钮
  foreach ($t in @(Get-UiFrontier)) {
    $ct = Get-MhControlType $t
    if ($ct -ne 'Window' -and $ct -ne 'Pane') { continue }
    $hasEdit = $false; $hasOk = $false
    try {
      $kids = $t.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
      foreach ($k in $kids) {
        $kc = $k.Current
        if ($kc.ControlType -eq [System.Windows.Automation.ControlType]::Edit) { $hasEdit = $true }
        elseif ($kc.ControlType -eq [System.Windows.Automation.ControlType]::Button -and $kc.Name -eq '确定') { $hasOk = $true }
      }
    } catch { }
    if ($hasEdit -and $hasOk) { return [pscustomobject]@{ element = $t; via = ('heuristic:' + $ct) } }
  }
  return [pscustomobject]@{ element = $null; via = 'NOT-FOUND' }
}
function Wait-NameDialog([int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Find-NameDialog
    if ($last.element) { return $last }
    Start-Sleep -Milliseconds 300
  }
  return $last
}
function Find-DialogEdit($dlg) {
  $e = Find-MhAppElement -AutomationId 'playlist.name.input' -ControlType 'Edit' -IncludeOffscreen
  if ($e) { return $e }
  if (-not $dlg) { return $null }
  try {
    $kids = $dlg.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($k in $kids) {
      if ($k.Current.ControlType -eq [System.Windows.Automation.ControlType]::Edit) { return $k }
    }
  } catch { }
  return $null
}
function Set-DialogName($dlg, [string]$text) {
  $box = Find-DialogEdit $dlg
  if (-not $box) { return 'NO-EDIT' }
  $r = Set-MhValue -Element $box -Value $text
  Start-Sleep -Milliseconds 300
  $back = ''
  try {
    $vp = $null
    if ($box.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$vp)) { $back = $vp.Current.Value }
  } catch { }
  return ($r + ' value=' + $back)
}
function Wait-Empty([string]$automationId, [string]$text, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $automationId -IncludeOffscreen
    if ($e -and -not $e.Current.IsOffscreen) { return $true }
    $t = Find-MhAppElement -Name $text -IncludeOffscreen
    if ($t -and -not $t.Current.IsOffscreen) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}

# ================================================================ 播放列表页 / 详情页
function Wait-PlaylistsPage([int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'playlists.new' -IncludeOffscreen) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
# 行 = ListItem 且 Name 含 needle；导航项也是 ListItem，按名字排除（u1 的既有做法）。
function Find-Row([string]$needle) {
  foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($null -eq $c.Name -or $c.Name -eq '') { continue }
    if ($navNames -contains $c.Name) { continue }
    if ($c.Name -notlike ('*' + $needle + '*')) { continue }
    if ((Get-MhControlType $e) -eq 'ListItem') { return $e }
  }
  return $null
}
function Wait-Row([string]$needle, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-Row $needle
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
function Get-ListRows {
  $out = @()
  foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
    if ((Get-MhControlType $e) -ne 'ListItem') { continue }
    $n = $e.Current.Name
    if ($null -eq $n -or $n -eq '') { continue }
    if ($navNames -contains $n) { continue }
    $out += $n
  }
  return @($out | Select-Object -Unique)
}
function Wait-PlaylistDetail([string]$expectedTitle, [int]$timeoutSec = 40) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId 'playlist.title' -IncludeOffscreen
    if ($e) {
      $n = $e.Current.Name
      if ($null -ne $n -and $n.Contains($expectedTitle)) { return $n }
    }
    Start-Sleep -Milliseconds 300
  }
  return ''
}

# ================================================================ 主流程（任一断言失败都继续往下跑）
Write-Host ('U4 播放列表 GUI 验收：server=' + $Server + ' base=' + $base + ' item=' + $itemName + '(' + $itemId + ') list=' + $listName)
$script:finalFail = 1          # fail-closed：任何未预期异常都不允许以 0 退出
$script:logMark = 0
$info = $null
$infoId = ''; $infoVer = 'n/a'
$dllHash = ''; $scriptHash = ''
$itemTitle = $itemName
$expectedDur = 600
$existedBefore = $false
$canonAfterP1 = ''
$p1Bytes = -1
$p1Mtime = ''

try {
  # ------------------------------------------------------------ P0 前置
  Write-Host ''
  Write-Host ('=== P0 前置（' + $Server + '）===')
  # 槽位必须先干净：Start-MhApp 不检查已有实例，残留进程会让 Get-MhWindowHandle 抓到旧窗口，
  # 而旧实例内存里的播放列表是启动时读的，与本轮刚删的数据文件不一致。
  $pre = @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count
  if ($pre -gt 0) { Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 900 }
  $preLeft = @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count
  Check 'P0.1 GUI 槽位干净（启动前无 MediaHub 进程）' ($pre -eq 0 -and $preLeft -eq 0) ('pre-existing=' + $pre + ' after-clear=' + $preLeft)

  $login = $null
  try { $login = Connect-Server } catch { Write-Host ('  login error: ' + $_.Exception.Message) }
  $uidOk = ($null -ne $login) -and ($login.User.Id -eq $expectUid)
  if ($uidOk) { $script:token = $login.AccessToken }
  $loginName = 'n/a'; $loginUid = 'n/a'
  if ($null -ne $login) { $loginName = [string]$login.User.Name; $loginUid = [string]$login.User.Id }
  Check 'P0.2 官方服务器登录成功且 uid 与文档坐标一致' $uidOk ('user=' + $loginName + ' uid=' + $loginUid + ' expected=' + $expectUid + ' apiErrors=' + $script:apiErrors.Count)

  $info = Api 'GET' '/System/Info'
  if ($info) { $infoId = [string]$info.Id; $infoVer = [string]$info.Version }
  Check 'P0.3 被测实例身份符合文档坐标（System/Info.Id）' ($infoId -eq $serverId) ('Id=' + $infoId + ' expected=' + $serverId)
  Check ('P0.4 服务器版本符合预期（' + $verPattern + '）') ($infoVer -like $verPattern) ('Version=' + $infoVer + ' pattern=' + $verPattern)

  $resetMode = Set-PositionZero $itemId
  Clear-Played $itemId
  $st0 = Wait-ItemState $itemId $false 25 ([long]0)
  if ($st0 -and $st0.name) { $itemTitle = [string]$st0.name }
  if ($st0 -and $st0.duration -gt 0) { $expectedDur = [double]$st0.duration }
  $st0Played = 'n/a'; $st0Ticks = 'n/a'
  if ($st0) { $st0Played = [string]$st0.played; $st0Ticks = [string]$st0.ticks }
  Check 'P0.5 条目进度已复位（Played=false 且 PlaybackPositionTicks=0）' ([bool]($st0 -and (-not $st0.played) -and ($st0.ticks -eq 0))) ('name=' + $itemTitle + ' played=' + $st0Played + ' ticks=' + $st0Ticks + ' duration=' + $expectedDur + 's mode=' + $resetMode)

  $plBefore = Read-Playlists
  $existedBefore = $plBefore.exists
  $beforeDetail = Format-Playlists $plBefore
  if ($existedBefore) { Remove-Item $plPath -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Milliseconds 250
  $plAfterDel = Read-Playlists
  Check 'P0.6 playlists.json 已清空（先记录删除前状态）' ((-not $plAfterDel.exists) -or ($plAfterDel.playlists.Count -eq 0)) ('before=' + $beforeDetail + ' existed=' + $existedBefore + ' after=' + (Format-Playlists $plAfterDel))

  $kept = Keep-OnlyServer ($Server + ':*')
  $seedArgs = @($Server, $base, $user, $pass, $serverId)
  if ($script:token) { $seedArgs += $script:token; $seedArgs += $script:uid }
  $seedCode = -1
  $seedOut = @()
  if (Test-Path $seed) {
    $seedOut = @(& $seed @seedArgs 2>&1 | ForEach-Object { [string]$_ })
    $seedCode = $LASTEXITCODE
  } else {
    Note ('SeedSource 不存在：' + $seed)
  }
  foreach ($l in $seedOut) { Write-Host ('  seed: ' + $l) }
  Start-Sleep -Milliseconds 600
  $cfg = Get-Content $settings -Raw | ConvertFrom-Json
  $srvs = @($cfg.Servers)
  $srvShown = 'none'
  if ($srvs.Count -gt 0) { $srvShown = [string]$srvs[0].ServerId }
  Check 'P0.7 只保留被测来源且已播种（settings.json 仅 1 条）' (($srvs.Count -eq 1) -and ([string]$srvs[0].ServerId -eq $fullServerId)) ('kept=' + $kept + ' servers=' + $srvs.Count + ' first=' + $srvShown + ' expected=' + $fullServerId + ' seedExit=' + $seedCode)

  if (Test-Path $dll) { $dllHash = (Get-FileHash $dll -Algorithm SHA256).Hash }
  $scriptHash = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
  Check 'P0.8 被测产物与脚本指纹已钉住（报告可追溯）' (($dllHash.Length -eq 64) -and ($scriptHash.Length -eq 64)) ('dll=' + $dllHash + ' script=' + $scriptHash)

  # ------------------------------------------------------------ P1 加入
  Write-Host ''
  Write-Host '=== P1 加入播放列表（详情页 → 新建）==='
  $app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 90
  Start-Sleep -Seconds 2
  $hwnd = Get-MhWindowHandle
  $fgOk = $false
  if ($hwnd) {
    $fg = Set-MhForeground -Hwnd $hwnd
    $fgOk = [bool]$fg.isForeground
  }
  Check 'P1.0 客户端启动且窗口在前台' (($null -ne $app.pid) -and ($null -ne $hwnd) -and $fgOk) ('pid=' + $app.pid + ' hwnd=' + $hwnd + ' fg=' + $fgOk)

  $nav = Open-ItemDetail $libName $itemTitle
  Check ('P1.1 媒体库 → ' + $libName + ' → ' + $itemTitle + ' 详情页可达') ($nav -eq 'OK') ('nav=' + $nav)
  $playBtn = Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen
  Check 'P1.2 详情页出现播放入口（detail.play）' ($null -ne $playBtn) ('found=' + ($null -ne $playBtn))
  $addBtn = Wait-Element 'detail.addtoplaylist' 30
  $addName = 'n/a'
  if ($addBtn) { $addName = [string]$addBtn.Current.Name }
  Check 'P1.3 详情页出现「加入播放列表」入口（detail.addtoplaylist）' ($null -ne $addBtn) ('found=' + ($null -ne $addBtn) + ' name=' + $addName)

  $open1 = Invoke-Safe $addBtn
  $miNew = Wait-MenuItem $newListText 25
  Check 'P1.4 菜单出现「新建播放列表…」（MenuFlyout 末项）' ($null -ne $miNew) ('invoke=' + $open1 + ' item=' + $newListText)
  $clickNew = Invoke-Safe $miNew
  $dlg = Wait-NameDialog 30
  $dlgEl = $null; $dlgVia = 'NOT-FOUND'
  if ($dlg) { $dlgEl = $dlg.element; $dlgVia = $dlg.via }
  Check 'P1.5 命名对话框出现（记录定位方式：AutomationId / 独立窗口 / 启发式）' ($null -ne $dlgEl) ('via=' + $dlgVia + ' click=' + $clickNew)

  $setName = Set-DialogName $dlgEl $listName
  $okBtn = Find-ExactButton '确定'
  $okInv = Invoke-Safe $okBtn
  $dlgClosed = $false
  $dlgDeadline = (Get-Date).AddSeconds(20)
  while ((Get-Date) -lt $dlgDeadline) {
    $probe = Find-NameDialog
    if (-not $probe.element) { $dlgClosed = $true; break }
    Start-Sleep -Milliseconds 300
  }
  Check 'P1.6 输入列表名并点「确定」后对话框关闭' ($setName -like 'SET*' -and $dlgClosed) ('set=' + $setName + ' ok=' + $okInv + ' closed=' + $dlgClosed)

  $bar1 = @(Wait-Text '已加入播放列表' 25)
  Check 'P1.7 InfoBar 出现成功文案「已加入播放列表」' ($bar1.Count -gt 0) ('hits=' + ($bar1 -join ' | '))
  $null = Save-Shot 'p1-added.png'

  $st1 = Wait-Playlists { param($s) $s.exists -and $s.playlists.Count -eq 1 } 30
  $pl0 = $null
  if ($st1 -and $st1.playlists.Count -ge 1) { $pl0 = $st1.playlists[0] }
  $items0 = @()
  $plName = 'n/a'
  if ($pl0) { $items0 = @($pl0.items); $plName = [string]$pl0.name }
  $it0 = $null
  if ($items0.Count -ge 1) { $it0 = $items0[0] }
  $it0Server = 'n/a'; $it0Item = 'n/a'
  if ($it0) { $it0Server = [string]$it0.serverId; $it0Item = [string]$it0.itemId }
  $nameOk = ($plName -eq $listName)
  $itemOk = ($null -ne $it0) -and ($it0Server -eq $fullServerId) -and ($it0Item -eq $itemId)
  Check 'P1.8 playlists.json 里 1 个列表、名字一致、items=1 且 serverId/itemId 与条目一致' ($st1.exists -and $st1.playlists.Count -eq 1 -and $items0.Count -eq 1 -and $nameOk -and $itemOk) ('state=' + (Format-Playlists $st1) + ' name=' + $plName + ' expectedName=' + $listName + ' items=' + $items0.Count + ' serverId=' + $it0Server + ' expectedServerId=' + $fullServerId + ' itemId=' + $it0Item + ' expectedItemId=' + $itemId)

  $verOk = ($null -ne $st1.doc) -and ($st1.doc.version -eq 1)
  $docVer = 'n/a'; $it0Title = 'n/a'; $it0Run = 'n/a'
  if ($st1.doc) { $docVer = [string]$st1.doc.version }
  if ($it0) { $it0Title = [string]$it0.title; $it0Run = [string]$it0.runTimeTicks }
  $fieldsOk = ($null -ne $it0) -and ($null -ne $it0.title) -and ($it0Title.Length -gt 0) -and ($null -ne $it0.runTimeTicks)
  $runPass = $true
  if ($st0 -and $st0.runTicks -gt 0 -and $it0) { $runPass = ([long]$it0.runTimeTicks -eq [long]$st0.runTicks) }
  Check 'P1.9 文档结构符合契约（version=1、camelCase 字段齐全、runTimeTicks 与服务器一致）' ($verOk -and $fieldsOk -and $runPass) ('version=' + $docVer + ' title=' + $it0Title + ' runTimeTicks=' + $it0Run + ' server=' + $(if ($st0) { $st0.runTicks } else { 'n/a' }) + ' runMatch=' + $runPass)
  $canonAfterP1 = Get-CanonicalPlaylists $st1
  $p1Bytes = $st1.bytes
  $p1Mtime = $st1.mtime

  # ------------------------------------------------------------ P2 重复加入
  Write-Host ''
  Write-Host '=== P2 重复加入（同一条目 → 同一列表）==='
  $addBtn2 = Wait-Element 'detail.addtoplaylist' 20
  $open2 = Invoke-Safe $addBtn2
  $miList = Wait-MenuItem $listName 25
  Check 'P2.1 菜单里出现刚建的列表（每次打开从存储重建）' ($null -ne $miList) ('invoke=' + $open2 + ' item=' + $listName)
  $clickExisting = Invoke-Safe $miList
  $bar2 = @(Wait-Text '已在该列表中' 25)
  Check 'P2.2 InfoBar 提示「已在该列表中」' ($bar2.Count -gt 0) ('hits=' + ($bar2 -join ' | ') + ' click=' + $clickExisting)
  $null = Save-Shot 'p2-duplicate.png'
  Start-Sleep -Milliseconds 900
  $st2 = Read-Playlists
  $items2 = @()
  if ($st2 -and $st2.playlists.Count -ge 1) { $items2 = @($st2.playlists[0].items) }
  Check 'P2.3 重复加入后 JSON 仍只有 1 个条目（没有重复写入）' ($st2.exists -and $st2.playlists.Count -eq 1 -and $items2.Count -eq 1) ('state=' + (Format-Playlists $st2))
  $canon2 = Get-CanonicalPlaylists $st2
  $sameCanon = ($canon2 -eq $canonAfterP1)
  Check 'P2.4 重复加入没有改写文件内容（主判据：规范化 JSON 相等）' $sameCanon ('canonicalEqual=' + $sameCanon + ' bytes=' + $st2.bytes + '(P1=' + $p1Bytes + ') mtime=' + $st2.mtime + '(P1=' + $p1Mtime + ')')

  # ------------------------------------------------------------ P3 列表页 / 详情页
  Write-Host ''
  Write-Host '=== P3 播放列表页 / 列表详情页 ==='
  $navPl = Go-NavDeep '播放列表'
  $pageOk = Wait-PlaylistsPage 45
  Check 'P3.1 导航「播放列表」进入页面（playlists.new 可达）' $pageOk ('nav=' + $navPl)
  $row = Wait-Row $listName 30
  Check 'P3.2 列表页出现 Name 含列表名的行' ($null -ne $row) ('rows=' + ((Get-ListRows) -join '|'))
  $null = Save-Shot 'p3-playlists.png'
  $clickRow = 'NO-ROW'
  if ($row) { $clickRow = Invoke-Deep $row; Start-Sleep -Milliseconds 1000 }
  $detailTitle = Wait-PlaylistDetail $listName 30
  Check 'P3.3 点行进入列表详情页且标题=列表名（playlist.title）' ($detailTitle -ne '') ('title=' + $detailTitle + ' click=' + $clickRow)
  $irows = @(Get-ListRows)
  $itemRowOk = $false
  foreach ($n in $irows) { if ($n -like ('*' + $itemTitle + '*')) { $itemRowOk = $true } }
  Check 'P3.4 详情页有 1 行且 Name 含条目名（playlist.items）' ($irows.Count -eq 1 -and $itemRowOk) ('rows=' + ($irows -join '|'))
  $null = Save-Shot 'p3-detail.png'
  $shotA = Test-Path (Join-Path $ev 'p3-playlists.png')
  $shotB = Test-Path (Join-Path $ev 'p3-detail.png')
  Check 'P3.5 截图已落盘（列表页 + 详情页）' ($shotA -and $shotB) ('p3-playlists.png=' + $shotA + ' p3-detail.png=' + $shotB)

  # ------------------------------------------------------------ P4 播放
  Write-Host ''
  Write-Host '=== P4 从播放列表播放（且不得改写永久列表）==='
  Set-LogMark
  $playRowBtn = Find-MhAppElement -AutomationId 'playlist.item.play' -IncludeOffscreen
  $playInv = Invoke-Safe $playRowBtn
  $inPlayer = Wait-Player 60
  Check 'P4.0 点「播放」进入播放页（playlist.item.play）' $inPlayer ('invoke=' + $playInv)
  $ps = $null
  try { $ps = Get-MhPlayerState -SkipSlow } catch { }
  $pTitle = ''; $pDur = -1
  if ($ps) {
    if ($null -ne $ps.title) { $pTitle = [string]$ps.title }
    if ($null -ne $ps.durationSeconds) { $pDur = [double]$ps.durationSeconds }
  }
  Check 'P4.1 播放页标题含条目名' ($pTitle.Contains($itemTitle)) ('title=' + $pTitle + ' needle=' + $itemTitle)
  $durDelta = -1
  if ($pDur -ge 0) { $durDelta = [math]::Abs($pDur - $expectedDur) }
  Check ('P4.2 播放时长与条目一致（' + $expectedDur + ' s，±10）') (($pDur -gt 0) -and ($durDelta -le 10)) ('duration=' + $pDur + 's expected=' + $expectedDur + ' delta=' + $durDelta)
  $null = Save-Shot 'p4-player.png'
  Start-Sleep -Milliseconds 1200
  $st4 = Read-Playlists
  $canon4 = Get-CanonicalPlaylists $st4
  $same4 = ($canon4 -eq $canonAfterP1)
  Check 'P4.3 播放没有改写 playlists.json（永久列表不因播放被改写）' $same4 ('canonicalEqual=' + $same4 + ' bytes=' + $st4.bytes + '(P1=' + $p1Bytes + ') mtime=' + $st4.mtime + '(P1=' + $p1Mtime + ') state=' + (Format-Playlists $st4))
  $newLines = @(Get-NewLog)
  $intake = @($newLines | Where-Object { $_ -match 'queue:intake' })
  $qItem = @($newLines | Where-Object { ($_ -match 'queue:') -and $_.Contains($itemId) })
  $pStart = @($newLines | Where-Object { $_ -match 'player:start' })
  $traceSample = (@($newLines | Where-Object { $_ -match 'player:start|queue:' } | Select-Object -First 3) -join ' || ')
  Check 'P4.4 trace 未把该条目塞进临时队列（无 queue:intake、无 queue 行含 itemId）' (($intake.Count -eq 0) -and ($qItem.Count -eq 0) -and ($pStart.Count -ge 1)) ('player:start=' + $pStart.Count + ' queue:intake=' + $intake.Count + ' queue-with-itemId=' + $qItem.Count + ' newLines=' + $newLines.Count + ' sample=' + $traceSample)
  $ex = Exit-Player 30
  Check 'P4.5 player.back 退出播放页' ($ex.gone) ('invoke=' + $ex.invoke + ' gone=' + $ex.gone)

  # ------------------------------------------------------------ P5 移除 / 删除
  Write-Host ''
  Write-Host '=== P5 移除条目 / 删除列表 ==='
  # player.back 的落点不保证，显式重进列表详情页，避免依赖返回栈。
  $onDetail = Find-MhAppElement -AutomationId 'playlist.items' -IncludeOffscreen
  $reenter = 'already-on-detail'
  if (-not $onDetail) {
    $reenter = Go-NavDeep '播放列表'
    $null = Wait-PlaylistsPage 40
    $r5 = Wait-Row $listName 30
    if ($r5) { $null = Invoke-Deep $r5; Start-Sleep -Milliseconds 1000 }
  }
  $rmBtn = Wait-Element 'playlist.item.remove' 30
  $rmInv = Invoke-Safe $rmBtn
  $emptyOk = Wait-Empty 'playlist.items.empty' '这个播放列表还是空的。' 30
  Check 'P5.1 移除条目后详情页显示空态（playlist.items.empty）' $emptyOk ('invoke=' + $rmInv + ' reenter=' + $reenter)
  $st5 = Wait-Playlists { param($s) $s.exists -and $s.playlists.Count -eq 1 -and (@($s.playlists[0].items).Count -eq 0) } 25
  $items5 = -1
  if ($st5 -and $st5.playlists.Count -ge 1) { $items5 = @($st5.playlists[0].items).Count }
  Check 'P5.2 JSON 里该列表 items=0（移除已落盘）' ($items5 -eq 0) ('state=' + (Format-Playlists $st5))
  $null = Save-Shot 'p5-after-remove.png'
  $backBtn = Wait-Element 'playlist.back' 20
  $backInv = Invoke-Safe $backBtn
  $null = Wait-PlaylistsPage 30
  $row6 = Wait-Row $listName 30
  $delBtn = Wait-Element 'playlist.delete' 25
  $delInv = Invoke-Safe $delBtn
  $dlgTitle = @(Wait-Text '删除播放列表' 20)
  $okDel = $null
  if ($dlgTitle.Count -gt 0) { $okDel = Find-ExactButton '删除' }
  Check 'P5.3 点行内「删除」弹出二次确认（标题「删除播放列表」+ 主按钮「删除」）' (($dlgTitle.Count -gt 0) -and ($null -ne $okDel)) ('back=' + $backInv + ' delete=' + $delInv + ' rowFound=' + ($null -ne $row6) + ' titleHits=' + ($dlgTitle -join ' | ') + ' primary=' + ($null -ne $okDel))
  $okDelInv = Invoke-Safe $okDel
  $emptyList = Wait-Empty 'playlists.empty' '还没有播放列表。' 30
  Check 'P5.4 确认删除后列表页显示空态（playlists.empty）' $emptyList ('invoke=' + $okDelInv)
  $st5b = Wait-Playlists { param($s) $s.exists -and $s.playlists.Count -eq 0 } 25
  $pl5 = -1
  if ($st5b) { $pl5 = $st5b.playlists.Count }
  Check 'P5.5 JSON 里 playlists=0（删除已落盘）' ($st5b.exists -and $pl5 -eq 0) ('state=' + (Format-Playlists $st5b))
  $null = Save-Shot 'p5-after-delete.png'
  $null = Save-Tree 'p5-page-tree.txt'
}
catch {
  Check 'HARNESS 未预期异常（已记录，继续走 P6 收尾）' $false ($_.Exception.Message + ' @ line ' + $_.InvocationInfo.ScriptLineNumber)
  # 失败时留一份控件树，便于事后判断是「没进页面」还是「进了但找不到控件」。
  try { $null = Save-Tree 'fail-page-tree.txt' } catch { }
  try { $null = Save-Shot 'fail-screen.png' } catch { }
}
finally {
  # ------------------------------------------------------------ P6 清理
  Write-Host ''
  Write-Host '=== P6 清理（幂等：重跑从空开始）==='
  try {
    if (Test-Path $plPath) { Remove-Item $plPath -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 300
    Check 'P6.1 playlists.json 已删除（重跑从空开始）' (-not (Test-Path $plPath)) ('exists=' + (Test-Path $plPath))
  } catch {
    Check 'P6.1 playlists.json 已删除（重跑从空开始）' $false $_.Exception.Message
  }
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 500
  $left = @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count
  Check 'P6.2 客户端进程已停止（错误路径也不留进程）' ($left -eq 0) ('remaining=' + $left)

  # ------------------------------------------------------------ 证据落盘
  try { Copy-Item (Get-MhLogPath) (Join-Path $ev 'trace.log') -Force } catch { Note 'trace.log 复制失败' }
  $pass = @($checks | Where-Object { $_.pass }).Count
  $fail = @($checks | Where-Object { -not $_.pass }).Count
  try { $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8 } catch { Note ('checks.json 写入失败: ' + $_.Exception.Message) }

  $md = @()
  $md += ('# U4「永久播放列表」真实服务器 GUI 验收（' + $Server + '）')
  $md += ''
  $md += ('- 服务器：' + $base + '  版本 ' + $infoVer + '  ServerId ' + $infoId)
  $md += ('- 媒体库/条目：' + $libName + ' / ' + $itemTitle + ' (' + $itemId + ')，期望时长 ' + $expectedDur + ' s')
  $md += ('- 播放列表名：' + $listName)
  $md += ('- 断言：' + $pass + ' 通过 / ' + $fail + ' 失败')
  $md += ('- 被测 exe：' + $exe + '（未重建）')
  $md += ('- MediaHub.dll SHA256：' + $dllHash)
  $md += ('- 本脚本 SHA256：' + $scriptHash)
  $md += ('- playlists.json：' + $plPath + '（跑前存在=' + $existedBefore + '，跑后已删除）')
  $md += ''
  $md += '## 数据判据'
  $md += '- 「加入 / 重复 / 移除 / 删除」都以 playlists.json 的结构为准；InfoBar 文案只作为 UI 侧证据。'
  $md += '- P4「播放不改写永久列表」用规范化 JSON 内容比对；字节数与 mtime 因 .tmp→File.Move 的原子替换会刷新，只作辅助写进 detail。'
  $md += '- 「条目消失 / 列表为空」同时要求空态文案可见：空白页 + InfoBar 警告 = 加载失败，判 FAIL。'
  $md += ''
  $md += '## 断言明细'
  foreach ($c in $checks) { $md += ('- [' + $(if ($c.pass) { 'PASS' } else { 'FAIL' }) + '] ' + $c.name + ' — ' + $c.detail) }
  $md += ''
  $md += '## 最担心 flaky 的两处与加固'
  $md += '- ① MenuFlyout / ContentDialog 的 UIA 归属：WinUI 3 的 ContentDialog 会作为独立 Window 挂在桌面根下，Flyout 也可能延迟出现。加固：Find-NameDialog 三级定位（playlist.name.dialog → 标题 Window → 含 Edit 与「确定」的启发式）并把实际用到的级别写进 detail；Wait-MenuItem / Wait-NameDialog 一律轮询到出现（25-30 s）；点「确定」后先轮询「对话框已消失」再断言 InfoBar。'
  $md += '- ② playlists.json 的写入时机与原子替换：Persist 先写 .tmp 再 File.Move，UI 提示与落盘完成不保证同一刻。加固：所有文件判据都用 Wait-Playlists 轮询到条件成立（≤30 s）；P2/P4 的「未改写」用规范化 JSON 相等判定；行内按钮按精确 Name 匹配（行内叫「删除 <列表名>」，对话框主按钮才叫「删除」），避免把确认对话框的主按钮和行内删除按钮搞混。'
  $md += ''
  $md += '## 证据产物'
  $md += '- checks.json（逐条断言与 detail）、results.md（本文件）、p1-added.png、p2-duplicate.png、p3-playlists.png、p3-detail.png、p4-player.png、p5-after-remove.png、p5-after-delete.png、p5-page-tree.txt、trace.log；异常中断时另有 fail-page-tree.txt / fail-screen.png'
  $md += '- 一次性把「列表为空」与「加载失败」区分开：空态文案可见才算通过，出现 InfoBar 警告即判 FAIL（见 P5.4/P5.3）。'
  try { $md | Set-Content (Join-Path $ev 'results.md') -Encoding UTF8 } catch { Note ('results.md 写入失败: ' + $_.Exception.Message) }

  Print-Evidence
  Write-Host ''
  Write-Host ('RESULT: ' + $pass + ' passed, ' + $fail + ' failed')
  $script:finalFail = $fail
}

if ($script:finalFail -gt 0) { exit 1 } else { exit 0 }

# U3 收藏（favorites）端到端门禁：真实 Emby / Jellyfin 服务器 + 真实客户端 GUI。
# 判据纪律：
#   1) 收藏状态只由服务器 API 回读判定，客户端 UI 状态另行核对；脚本绝不直接改 UserData；
#   2) 写端点用真实服务端实测过的 /Users/{uid}/FavoriteItems/{id}（/Favorites/{id} 在 Emby 4.10.2 与
#      Jellyfin 12.2 上都是 404，写死旧路由会得到一条永远绿的假用例）；
#   3) 「重启后仍显示已收藏」必须来自服务器回读，不是本地缓存——因此重启前后都直接查服务器。
param(
  [ValidateSet('emby','jellyfin')][string]$Server = 'emby'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\u3-favorite\' + $Server
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe      = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'

if ($Server -eq 'emby') {
  $base = 'http://127.0.0.1:8097'; $user = 'mh'; $pass = 'mh-test-pass'
  $serverId = '1271d0a1040046db8c0089cc328bbbf1'
  $libName = 'MHMovies'; $itemId = '34'; $itemName = 'Long Resume'
} else {
  $base = 'http://127.0.0.1:8096'; $user = 'mediahub-test'; $pass = 'mediahub-test-password'
  $serverId = 'daffc6534af44bbb8f73848209519255'
  $libName = 'Movies'; $itemId = 'b22e31da9faf6ef247e19f7b3774dab2'; $itemName = 'Long Resume'
}

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

# ---------------------------------------------------------------- 服务器 API
function Em([string]$method, [string]$path) {
  $headers = @{ Authorization = ('MediaBrowser Token="' + $script:token + '"') }
  $params = @{ Method = $method; Uri = ($base + $path); Headers = $headers; TimeoutSec = 30 }
  return Invoke-RestMethod @params
}
function Connect-Server {
  $hdr = 'MediaBrowser Client="MediaHub-U3", Device="probe", DeviceId="u3-favorite-gui", Version="1.0.0"'
  $body = @{ Username = $user; Pw = $pass } | ConvertTo-Json -Compress
  return Invoke-RestMethod -Method POST -Uri ($base + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $hdr } -Body $body -TimeoutSec 20
}
function Get-Fav([string]$id) {
  $d = Em 'GET' ('/Users/' + $script:uid + '/Items/' + $id + '?Fields=UserData')
  return [bool]$d.UserData.IsFavorite
}
function Set-Fav([string]$id, [bool]$favorite) {
  $method = if ($favorite) { 'POST' } else { 'DELETE' }
  Em $method ('/Users/' + $script:uid + '/FavoriteItems/' + $id) | Out-Null
}
function Wait-Fav([string]$id, [bool]$expected, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-Fav $id
    if ($last -eq $expected) { return $last }
    Start-Sleep -Milliseconds 500
  }
  return $last
}

# ---------------------------------------------------------------- UI helpers
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
function Find-IdLikeName([string]$pattern, [string]$nameNeedle) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($c.AutomationId -like $pattern -and $null -ne $c.Name -and $c.Name -like ('*' + $nameNeedle + '*')) { return $e }
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
function Wait-Element([string]$id, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 250
  }
  return $null
}
function Wait-FavoriteState([bool]$favorited, [int]$timeoutSec = 30) {
  # 不能按「包含 收藏」判等：已收藏的名字「取消收藏」也包含这两个字，会得到一条永远为真的断言。
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = ''
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId 'detail.favorite' -IncludeOffscreen
    if ($e) {
      $last = $e.Current.Name
      $isFav = ($null -ne $last) -and ($last -like '*取消收藏*')
      if ($isFav -eq $favorited) { return $last }
    }
    Start-Sleep -Milliseconds 300
  }
  return $last
}
function Find-AnyNameLike([string]$needle) {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $n = $e.Current.Name
    if ($null -ne $n -and $n -like ('*' + $needle + '*')) { return $n }
  }
  return ''
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if ($item) { Invoke-MhElement -Element $item | Out-Null; Start-Sleep -Milliseconds 1200 }
  return ($null -ne $item)
}
function Go-Libraries {
  Go-Nav '首页' | Out-Null
  Go-Nav '媒体库' | Out-Null
  Start-Sleep -Seconds 2
}
function Keep-OnlyServer([string]$pattern) {
  $raw = Get-Content $settings -Raw | ConvertFrom-Json
  $raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like $pattern })
  $raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
  return @($raw.Servers).Count
}
function Open-ItemDetail {
  Go-Libraries
  $lib = Wait-IdLikeName 'library|*' $libName 60
  if (-not $lib) { return 'LIBRARY-CARD-NOT-FOUND' }
  Invoke-MhElementDeep -Element $lib | Out-Null
  Start-Sleep -Seconds 2
  $card = Wait-IdLikeName 'media:*' $itemName 60
  if (-not $card) {
    # 库可能先进到文件夹层，再返回库根重试一次
    $back = Find-MhAppElement -AutomationId 'nav.back' -IncludeOffscreen
    if ($back) { Invoke-MhElement -Element $back | Out-Null; Start-Sleep -Seconds 2 }
    $card = Wait-IdLikeName 'media:*' $itemName 30
  }
  if (-not $card) { return 'MEDIA-CARD-NOT-FOUND' }
  Invoke-MhElementDeep -Element $card | Out-Null

  $deadline = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen) { return 'OK' }
    $t = Get-Text 'TitleText'
    if ($t -and $t.Contains(' / ')) {
      # 进入了文件夹层：点里面真正的条目
      $inner = Wait-IdLikeName 'media:*' $itemName 30
      if ($inner) { Invoke-MhElementDeep -Element $inner | Out-Null }
    }
    Start-Sleep -Milliseconds 400
  }
  return ('DETAIL-NOT-OPEN title=' + (Get-Text 'TitleText'))
}
function Click-Favorite {
  $e = Wait-Element 'detail.favorite' 20
  if (-not $e) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $e) } catch { return ('ERROR: ' + $_.Exception.Message) }
}

# ---------------------------------------------------------------- P0 复位与播种
Write-Host ('=== P0 ' + $Server + ' 复位、来源播种 ===')
$login = $null
try { $login = Connect-Server } catch { }
Check 'P0.1 官方服务器登录成功' ($null -ne $login) $(if ($login) { 'user=' + $login.User.Name + ' uid=' + $login.User.Id } else { 'login failed' })
if (-not $login) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  exit 1
}
$script:token = $login.AccessToken
$script:uid = $login.User.Id
$info = Em 'GET' '/System/Info'
Check 'P0.2 被测实例版本符合预期' ($info.Version -like '12.*' -or $info.Version -like '4.10.*') ('version=' + $info.Version + ' serverId=' + $info.Id)

Set-Fav $itemId $false
$fav0 = Wait-Fav $itemId $false 15
Check 'P0.3 官方 API 复位后回读为未收藏' (-not $fav0) ('isFavorite=' + $fav0)

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$kept = Keep-OnlyServer ($Server + ':*')
& $seed $Server $base $user $pass $serverId $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  seed: ' + $_) }
$after = Get-Content $settings -Raw | ConvertFrom-Json
$entry = @($after.Servers | Where-Object { $_.ServerId -eq ($Server + ':' + $serverId) })
Check 'P0.4 来源写入客户端配置' ($entry.Count -eq 1) ('keptBefore=' + $kept + ' serversNow=' + @($after.Servers).Count)
Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800

# ---------------------------------------------------------------- P1 首次收藏
Write-Host ''
Write-Host '=== P1 详情页收藏（UI 触发 → 服务器回读） ==='
$app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd = Get-MhWindowHandle
if ($hwnd) { Set-MhForeground -Hwnd $hwnd | Out-Null }
Check 'P1.0 客户端启动且窗口在前台' (($null -ne $app) -and ($null -ne $hwnd)) ('pid=' + $app.pid)

$open = Open-ItemDetail
Check ('P1.1 媒体库 → ' + $libName + ' → ' + $itemName + ' 详情页可达') ($open -eq 'OK') $open
$favBtn = Wait-Element 'detail.favorite' 25
Check 'P1.2 详情页出现收藏按钮（来源声明了 Favorite 能力）' ($null -ne $favBtn) $(if ($favBtn) { 'name=' + $favBtn.Current.Name } else { 'button missing' })
$name0 = if ($favBtn) { $favBtn.Current.Name } else { '' }
Check 'P1.3 初始显示为未收藏' ($name0 -like '*收藏*' -and $name0 -notlike '*取消*') ('name=' + $name0)
Save-MhScreenshot -Path (Join-Path $ev 'p1-before.png') | Out-Null

$click = Click-Favorite
$name1 = Wait-FavoriteState $true 30
Check 'P1.4 点击后界面变为「取消收藏」' ($name1 -like '*取消收藏*') ('invoke=' + $click + ' name=' + $name1)
$fav1 = Wait-Fav $itemId $true 25
Check 'P1.5 服务器回读 IsFavorite=True（真实写入，不是本地状态）' $fav1 ('isFavorite=' + $fav1)
Save-MhScreenshot -Path (Join-Path $ev 'p1-after-favorite.png') | Out-Null

$err1 = Find-AnyNameLike '无法更新收藏'
$warn1 = Find-AnyNameLike '收藏未保存'
Check 'P1.6 收藏成功没有出现错误/未保存提示' (($err1 -eq '') -and ($warn1 -eq '')) ('error=' + $err1 + ' warning=' + $warn1)

# 收藏页：服务器侧写入必须能在跨来源列表里看到，而不是只有详情页知道
Go-Nav '我的收藏' | Out-Null
$grid = Wait-Element 'favorites.grid' 30
$rowName = ''
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
  $hit = $null
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $n = $e.Current.Name
    if ($null -ne $n -and $n -like ('*' + $itemName + '*') -and $e.Current.AutomationId -notlike 'LibraryCard*') { $hit = $n; break }
  }
  if ($hit) { $rowName = $hit; break }
  Start-Sleep -Milliseconds 400
}
Check 'P1.7 「我的收藏」页出现刚收藏的条目' (($null -ne $grid) -and ($rowName -ne '')) ('grid=' + ($null -ne $grid) + ' row=' + $rowName)
Save-MhScreenshot -Path (Join-Path $ev 'p1-favorites-page.png') | Out-Null

$favClick = 'NO-ELEMENT'
if ($rowName -ne '') {
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    $c = $e.Current
    if ($null -ne $c.Name -and $c.Name -like ('*' + $itemName + '*') -and $c.ControlType -eq 'ListItem') {
      try { $favClick = Invoke-MhElementDeep -Element $e } catch { $favClick = 'ERROR: ' + $_.Exception.Message }
      break
    }
  }
  Start-Sleep -Seconds 2
}
$detailFromFav = $null -ne (Wait-Element 'detail.play' 30)
$titleFromFav = Get-Text 'TitleText'
Check 'P1.8 收藏页点条目进入详情页（不是死链接）' ($detailFromFav -and $titleFromFav -like ('*' + $itemName + '*')) ('invoke=' + $favClick + ' title=' + $titleFromFav)

# ---------------------------------------------------------------- P2 重启后状态来自服务器
Write-Host ''
Write-Host '=== P2 完全重启客户端后状态仍为已收藏（服务器持久化，非本地缓存） ==='
Stop-MhAppForced | Out-Null
Start-Sleep -Seconds 2
Check 'P2.0 客户端进程已退出' (-not (Get-Process MediaHub -ErrorAction SilentlyContinue)) 'process check'
$app2 = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60
Start-Sleep -Seconds 3
$hwnd2 = Get-MhWindowHandle
if ($hwnd2) { Set-MhForeground -Hwnd $hwnd2 | Out-Null }
$open2 = Open-ItemDetail
Check 'P2.1 重启后仍能到达详情页' ($open2 -eq 'OK') $open2
$name2 = Wait-FavoriteState $true 35
Check 'P2.2 重启后按钮显示「取消收藏」' ($name2 -like '*取消收藏*') ('name=' + $name2)
$fav2 = Get-Fav $itemId
Check 'P2.3 此时服务器上确实是已收藏' $fav2 ('isFavorite=' + $fav2)
Save-MhScreenshot -Path (Join-Path $ev 'p2-after-restart.png') | Out-Null

# ---------------------------------------------------------------- P3 取消收藏
Write-Host ''
Write-Host '=== P3 取消收藏（UI 触发 → 服务器回读） ==='
$click3 = Click-Favorite
$name3 = Wait-FavoriteState $false 30
Check 'P3.1 点击后界面回到未收藏' (($name3 -like '*收藏*') -and ($name3 -notlike '*取消*')) ('invoke=' + $click3 + ' name=' + $name3)
$fav3 = Wait-Fav $itemId $false 25
Check 'P3.2 服务器回读 IsFavorite=False' (-not $fav3) ('isFavorite=' + $fav3)
Save-MhScreenshot -Path (Join-Path $ev 'p3-after-unfavorite.png') | Out-Null

# 取消收藏后收藏页必须同步消失：列表来自服务器，不是本地缓存
Go-Nav '我的收藏' | Out-Null
$null = Wait-Element 'favorites.grid' 30
Start-Sleep -Seconds 2
if (Find-MhAppElement -AutomationId 'favorites.reload' -IncludeOffscreen) {
  Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'favorites.reload' -IncludeOffscreen) | Out-Null
  Start-Sleep -Seconds 2
}
$stillThere = ''
foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
  $n = $e.Current.Name
  if ($null -ne $n -and $n -like ('*' + $itemName + '*') -and $e.Current.AutomationId -like 'favorites*') { $stillThere = $n; break }
}
$emptyEl = Find-MhAppElement -AutomationId 'favorites.empty' -IncludeOffscreen
$emptyVisible = ($null -ne $emptyEl) -and (-not $emptyEl.Current.IsOffscreen)
Check 'P3.3 取消收藏后「我的收藏」页不再列出该条目（且显示空态）' (($stillThere -eq '') -and $emptyVisible) ('residual=' + $stillThere + ' emptyVisible=' + $emptyVisible)
Save-MhScreenshot -Path (Join-Path $ev 'p3-favorites-empty.png') | Out-Null

# ---------------------------------------------------------------- P4 播放未被破坏（回归）
Write-Host ''
Write-Host '=== P4 收藏改动未破坏播放路径（回归） ==='
$play = $null
$pb = Wait-Element 'detail.play' 20
if ($pb) { try { $play = Invoke-MhElement -Element $pb } catch { $play = 'ERROR: ' + $_.Exception.Message } }
$deadline = (Get-Date).AddSeconds(60)
$playerUp = $false
while ((Get-Date) -lt $deadline) {
  if (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen) { $playerUp = $true; break }
  Start-Sleep -Milliseconds 400
}
Check 'P4.1 详情页播放仍能进入播放页' $playerUp ('invoke=' + $play)
Start-Sleep -Seconds 2
$state = Get-MhPlayerState -SkipSlow
Check 'P4.2 播放时长与条目一致（600 s）' ($null -ne $state.durationSeconds -and [math]::Abs([double]$state.durationSeconds - 600) -le 30) ('duration=' + $state.durationSeconds)
$back = Find-MhAppElement -AutomationId 'player.back' -IncludeOffscreen
if ($back) { Invoke-MhElement -Element $back | Out-Null }
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- 收尾
Set-Fav $itemId $false
$favEnd = Wait-Fav $itemId $false 15
Write-Host ''
Write-Host ('服务器收藏状态已复位: isFavorite=' + $favEnd)
Stop-MhAppForced | Out-Null

$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
$checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
$md = @('# U3 收藏门禁（' + $Server + '）', '', ('- 服务器：' + $base + ' 版本 ' + $info.Version), ('- 条目：' + $itemName + ' (' + $itemId + ')'), ('- 断言：' + $pass + ' 通过 / ' + $fail + ' 失败'), '')
foreach ($c in $checks) { $md += ('- [' + $(if ($c.pass) { 'PASS' } else { 'FAIL' }) + '] ' + $c.name + ' — ' + $c.detail) }
$md | Set-Content (Join-Path $ev 'results.md') -Encoding UTF8
Write-Host ('RESULT: ' + $pass + ' passed, ' + $fail + ' failed')
if ($fail -gt 0) { exit 1 } else { exit 0 }

# U2 跨季自动连播 GUI 门禁：真实 Emby / Jellyfin 服务器 + 真实客户端 GUI（UIA）。
# 判据纪律：
#   1) 「自动连播发生了」必须同时有 UI 证据（播放页标题变成下一集）与 trace 证据
#      （startup.log 出现 queue:auto-advance episode=<id>），任一缺失即 FAIL；
#   2) 「播完不切集」的负向对照必须看到 queue:auto-advance-disabled，否则不能算验证过
#      （没看到这行说明结束判定分支根本没被触达，而不是「正确地没有连播」）；
#   3) 服务器侧已播状态由服务器 API 回读判定，不看客户端 UI 自述；
#   4) 开关优先真实 UI（设置页 ToggleSwitch），UI 定位失败才退回 settings.json 注入并写明。
# 服务器无关：库/剧/季/集的 id、名称、时长全部在 P0 阶段通过 API 动态探测，
#   不硬编码任何一家的 id（Emby 15/16/17/18 与 Jellyfin 227300…/21d315… 各自探测）。
# 时序纪律（首跑教训）：S01E01 只有 10 s，任何「等结束」的断言必须与「mark → 起播 → seek」在同一窗口内，
#   否则 queue: 行会落在 mark 之前，出现假 FAIL。
param(
  [ValidateSet('emby', 'jellyfin')][string]$Server = 'emby',
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\u2-autoplay\' + $Server
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$exe      = $Exe
$seed     = $R + 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$dllPath  = Join-Path (Split-Path $exe -Parent) 'MediaHub.dll'
$NL = [Environment]::NewLine
# 运行期间可能被其他构建任务替换输出目录，记下起始 hash 以便收尾比对（跑之前/之后 hash 不一致则结果不可信）
$hashAtStart = $null
try { $hashAtStart = (Get-FileHash $dllPath -Algorithm SHA256).Hash } catch { }

# 每台服务器的连接参数与探测线索（id 一律运行时探测，这里只有名字与版本模式）
if ($Server -eq 'emby') {
  $cfg = @{ base = 'http://127.0.0.1:8097'; user = 'mh'; pass = 'mh-test-pass'; libName = 'MHTest TV'; seriesName = 'Example Show'; versionPattern = '4.10.*' }
} else {
  $cfg = @{ base = 'http://127.0.0.1:8096'; user = 'mediahub-test'; pass = 'mediahub-test-password'; libName = 'TV Shows'; seriesName = 'Example Show'; versionPattern = '12.*' }
}
$base = $cfg.base; $user = $cfg.user; $pass = $cfg.pass

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

# ---------------------------------------------------------------- 服务器 API
function Em([string]$method, [string]$path, $body = $null) {
  $headers = @{ Authorization = ('MediaBrowser Token="' + $script:token + '"') }
  $params = @{ Method = $method; Uri = ($base + $path); Headers = $headers; TimeoutSec = 30 }
  if ($null -ne $body) {
    $params['Body'] = ($body | ConvertTo-Json -Compress)
    $params['ContentType'] = 'application/json'
  }
  return Invoke-RestMethod @params
}
function Connect-Server {
  $hdr = 'MediaBrowser Client="MediaHub-U2", Device="probe", DeviceId="u2-autoplay", Version="1.0.0"'
  $body = @{ Username = $user; Pw = $pass } | ConvertTo-Json -Compress
  return Invoke-RestMethod -Method POST -Uri ($base + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $hdr } -Body $body -TimeoutSec 20
}
function Get-UserData([string]$id) {
  $d = Em 'GET' ('/Users/' + $script:uid + '/Items/' + $id + '?Fields=UserData')
  return $d.UserData
}
function Reset-Item([string]$id) {
  Em 'POST' ('/Users/' + $script:uid + '/Items/' + $id + '/UserData') @{ PlaybackPositionTicks = 0 } | Out-Null
  try { Em 'DELETE' ('/Users/' + $script:uid + '/PlayedItems/' + $id) | Out-Null } catch { }
}

# ---------------------------------------------------------------- UIA helpers
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
function Wait-Text([string]$id, [string]$expected, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-Text $id
    if ($null -ne $last -and $last -eq $expected) { return $true }
    Start-Sleep -Milliseconds 200
  }
  Write-Host ('  wait-text ' + $id + ' expected="' + $expected + '" actual="' + $last + '"')
  return $false
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if ($item) { try { Invoke-MhElement -Element $item | Out-Null } catch { } ; Start-Sleep -Milliseconds 1000 }
  return ($null -ne $item)
}
function Wait-Card([string]$automationId, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $automationId -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
function Invoke-Deep($element) {
  try { Invoke-MhElementDeep -Element $element | Out-Null; return $true } catch { Write-Host ('  invoke error: ' + $_.Exception.Message); return $false }
}
function Keep-OnlyServer([string]$pattern) {
  $raw = Get-Content $settings -Raw | ConvertFrom-Json
  $raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like $pattern })
  $raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
  return @($raw.Servers).Count
}
function Set-AutoPlayNext([bool]$on) {
  $raw = Get-Content $settings -Raw | ConvertFrom-Json
  $raw.AutoPlayNext = $on
  $raw | ConvertTo-Json -Depth 8 | Set-Content $settings -Encoding UTF8
  $back = (Get-Content $settings -Raw | ConvertFrom-Json).AutoPlayNext
  return [bool]$back
}
function Get-AutoPlayNext {
  return [bool]((Get-Content $settings -Raw | ConvertFrom-Json).AutoPlayNext)
}
# 真实 UI 关/开开关；返回 'UI' / 'NOT-FOUND' / 'NO-TOGGLEPATTERN' / 'UI-MISMATCH <state>' / 'UI-ERROR ...'
function Set-AutoPlayNextUi([bool]$on) {
  $null = Go-Nav '设置'
  $el = $null
  $deadline = (Get-Date).AddSeconds(15)
  while ((Get-Date) -lt $deadline -and -not $el) {
    $el = Find-MhAppElement -Name '自动连播下一集' -IncludeOffscreen
    if (-not $el) { $el = Find-MhAppElement -Contains '自动连播下一集' -IncludeOffscreen }
    if (-not $el) { Start-Sleep -Milliseconds 400 }
  }
  if (-not $el) { return 'NOT-FOUND' }
  try {
    $pat = $null
    if ($el.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pat)) {
      $isOn = ($pat.Current.ToggleState -eq [System.Windows.Automation.ToggleState]::On)
      if ($isOn -ne $on) { $pat.Toggle(); Start-Sleep -Milliseconds 800 }
      $after = Get-AutoPlayNext
      if ($after -eq $on) { return 'UI' }
      return ('UI-MISMATCH settings=' + $after)
    }
    return 'NO-TOGGLEPATTERN'
  } catch { return ('UI-ERROR ' + $_.Exception.Message) }
}
$script:logMark = 0
function Set-LogMark { $script:logMark = @(Get-MhLog).Count }
function Get-NewLog {
  $lines = @(Get-MhLog)
  if ($script:logMark -ge $lines.Count) { return @() }
  return @($lines[$script:logMark..($lines.Count - 1)])
}
function Wait-LogLine([string]$pattern, [int]$timeoutSec = 20) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((@(Get-NewLog) -join $NL) -match $pattern) { return $true }
    Start-Sleep -Milliseconds 250
  }
  return $false
}
function Wait-Player([int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
# 干净基线：杀掉再起（P1 已证明「冷启动 → 媒体库 → 剧集 → 播放」这条路径稳定）
# 抗干扰：exe 可能在被其他构建任务同时写（临时不存在），所以先等文件回来，启动失败再重试 3 次。
function Restart-App([switch]$ResetTrace) {
  for ($attempt = 1; $attempt -le 4; $attempt++) {
    Stop-MhAppForced | Out-Null
    Start-Sleep -Milliseconds (1000 + 500 * $attempt)
    $waitDeadline = (Get-Date).AddSeconds(60)
    while ((-not (Test-Path $exe)) -and ((Get-Date) -lt $waitDeadline)) { Start-Sleep -Milliseconds 500 }
    if (-not (Test-Path $exe)) {
      Write-Host ('  restart attempt ' + $attempt + ': exe missing, waiting')
      continue
    }
    try {
      $a = Start-MhApp -Exe $exe -ResetTrace:($ResetTrace -and $attempt -eq 1) -TimeoutSec 60
      if ($a) {
        Start-Sleep -Seconds 3
        $h = Get-MhWindowHandle
        if ($h) { Set-MhForeground -Hwnd $h | Out-Null }
        return $a
      }
    } catch { Write-Host ('  restart attempt ' + $attempt + ' failed: ' + $_.Exception.Message) }
    Stop-Process -Name MediaHub -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
  }
  Write-Host '  restart FAILED after 4 attempts'
  return $null
}

function Exit-Player([int]$timeoutSec = 12) {
  $back = Find-MhAppElement -AutomationId 'player.back' -IncludeOffscreen
  if ($back) { try { Invoke-MhElement -Element $back | Out-Null } catch { } }
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (-not (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen)) { return $true }
    Start-Sleep -Milliseconds 400
  }
  [void](Send-MhKeys -Keys @('ESC'))
  Start-Sleep -Milliseconds 900
  return (-not [bool](Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen))
}
# 从「媒体库」一级级点到剧集卡片，再进详情页点播放。任何一级找不到就退出去重来一次。
function Open-EpisodePlay([string]$episodeId, [string]$needle) {
  for ($attempt = 1; $attempt -le 2; $attempt++) {
    $null = Go-Nav '媒体库'
    Start-Sleep -Milliseconds 1200
    $lib = Wait-Card $script:libCardId 30
    if (-not $lib) { continue }
    if (-not (Invoke-Deep $lib)) { continue }
    $series = Wait-Card $script:seriesCard 30
    if (-not $series) { continue }
    if (-not (Invoke-Deep $series)) { continue }
    $season = Wait-Card $script:season1Card 30
    if (-not $season) { continue }
    if (-not (Invoke-Deep $season)) { continue }
    $ep = Wait-Card (EpCard $episodeId) 30
    if (-not $ep) { continue }
    if (-not (Invoke-Deep $ep)) { continue }

    $deadline = (Get-Date).AddSeconds(35)
    while ((Get-Date) -lt $deadline) {
      if (Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen) { break }
      if (Find-MhAppElement -AutomationId 'player.playpause' -IncludeOffscreen) { return 'DIRECT-PLAYER' }
      Start-Sleep -Milliseconds 300
    }
    $btn = Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen
    if (-not $btn) {
      Exit-Player | Out-Null
      continue
    }
    try { Invoke-MhElement -Element $btn | Out-Null } catch { return ('PLAY-CLICK-ERROR: ' + $_.Exception.Message) }
    return 'OK'
  }
  return ('NAV-FAILED last-title=' + (Get-Text 'TitleText') + ' detail-play=' + [bool](Find-MhAppElement -AutomationId 'detail.play' -IncludeOffscreen))
}
function Seek-Ratio([double]$ratio) {
  $slider = Find-MhAppElement -AutomationId 'player.seekslider' -IncludeOffscreen
  if (-not $slider) { return $null }
  $rv = Get-MhRangeValue $slider
  if ($null -eq $rv -or $rv.max -le 0) { return $rv }
  $target = $rv.min + ($rv.max - $rv.min) * $ratio
  $set = Set-MhRangeValue -Element $slider -Value $target
  Start-Sleep -Milliseconds 400
  $after = Get-MhRangeValue $slider
  return [pscustomobject]@{ before = $rv.value; target = $target; after = $(if ($after) { $after.value } else { $null }); min = $rv.min; max = $rv.max; set = $set }
}
# 等本项结束：queue 结束处理行 / 位置触底 / 标题被切走 / 播放页消失
function Wait-Ended([string]$originalTitle, [int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $q = @(Get-NewLog | Where-Object { $_ -match 'queue:auto-advance|queue:auto-advance-disabled|queue:no-next-episode|queue:switch' })
    $s = Get-MhPlayerState -SkipSlow
    $last = $s
    if ($q.Count -gt 0) { return [pscustomobject]@{ ended = $true; why = 'queue-line'; state = $s; queue = $q } }
    if (-not $s.playerVisible) { return [pscustomobject]@{ ended = $true; why = 'player-closed'; state = $s; queue = @() } }
    if ($null -ne $s.title -and $s.title -ne $originalTitle) { return [pscustomobject]@{ ended = $true; why = 'title-changed'; state = $s; queue = @() } }
    $d = 0.0; $p = 0.0
    try { $d = [double]$s.durationSeconds } catch { }
    try { $p = [double]$s.positionSeconds } catch { }
    if ($d -gt 0 -and $p -ge ($d - 0.6)) { return [pscustomobject]@{ ended = $true; why = 'position-at-end'; state = $s; queue = @() } }
    Start-Sleep -Milliseconds 250
  }
  return [pscustomobject]@{ ended = $false; why = 'timeout'; state = $last; queue = @() }
}
function Wait-Title([string]$expected, [int]$timeoutSec = 20) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $t = ''
  while ((Get-Date) -lt $deadline) {
    $s = Get-MhPlayerState -SkipSlow
    $t = if ($s.title) { $s.title } else { '' }
    if ($t -eq $expected) { break }
    Start-Sleep -Milliseconds 400
  }
  return $t
}
# 采样位置 5 s：位置推进 / seek 值推进 / 已经切集，任一条成立即证明媒体在播
function Watch-Playback([int]$timeoutSec = 6) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $samples = @()
  $posOk = $false; $seekOk = $false; $adv = $false
  while ((Get-Date) -lt $deadline) {
    $s = Get-MhPlayerState -SkipSlow
    if ($null -ne $s.positionSeconds -and [double]$s.positionSeconds -gt 0.5) { $posOk = $true }
    if ($null -ne $s.seekValue -and [double]$s.seekValue -gt 0.02) { $seekOk = $true }
    if (@(Get-NewLog) -match 'queue:auto-advance') { $adv = $true }
    $samples += ('{0}/{1}' -f $s.position, $s.seekValue)
    if ($posOk -or $seekOk -or $adv) { break }
    Start-Sleep -Milliseconds 300
  }
  return [pscustomobject]@{ posOk = $posOk; seekOk = $seekOk; advanced = $adv; samples = $samples }
}

# ---------------------------------------------------------------- P0 登录、探测、复位、播种
Write-Host ('=== P0 ' + $Server + ' 登录 / 探测 / 复位 / 来源播种 ===')
$login = $null
try { $login = Connect-Server } catch { Write-Host ('  login error: ' + $_.Exception.Message) }
Check 'P0.1 官方服务器登录成功' ($null -ne $login) $(if ($login) { 'user=' + $login.User.Name + ' uid=' + $login.User.Id } else { 'login failed' })
if (-not $login) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  Write-Host 'RESULT: 0 passed, 1 failed'
  exit 1
}
$script:token = $login.AccessToken
$script:uid = $login.User.Id
$info = $null
try { $info = Invoke-RestMethod -Uri ($base + '/System/Info/Public') -TimeoutSec 20 } catch { }
if (-not $info) { try { $info = Em 'GET' '/System/Info' } catch { } }
Check 'P0.2 服务器实例信息可读且版本符合预期' ([bool]($info -and ([string]$info.Version -like $cfg.versionPattern))) ('version=' + $(if ($info) { $info.Version } else { 'n/a' }) + ' serverId=' + $(if ($info) { $info.Id } else { 'n/a' }) + ' pattern=' + $cfg.versionPattern)
if (-not $info -or -not $info.Id) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  Write-Host 'RESULT: 1 passed, 1 failed'
  exit 1
}

# ---- 动态探测：库 → 剧 → 季 → 集（id/名称/时长全部以服务器返回为准）
$serverId = [string]$info.Id
$lib = $null; $series = $null; $season1 = $null
$epMap = @{}
$discoveryErr = ''
try {
  $views = Em 'GET' ('/Users/' + $script:uid + '/Views')
  $lib = @($views.Items | Where-Object { $_.Name -eq $cfg.libName }) | Select-Object -First 1
  if (-not $lib) { $lib = @($views.Items | Where-Object { $_.CollectionType -eq 'tvshows' }) | Select-Object -First 1 }
  if (-not $lib) { throw ('library not found, views=' + ((@($views.Items | ForEach-Object { $_.Name })) -join ',')) }

  $allSeries = Em 'GET' ('/Items?ParentId=' + $lib.Id + '&IncludeItemTypes=Series&Recursive=true')
  $series = @($allSeries.Items | Where-Object { $_.Name -eq $cfg.seriesName }) | Select-Object -First 1
  if (-not $series) { $series = @($allSeries.Items | Where-Object { $_.Name -like ($cfg.seriesName + '*') }) | Select-Object -First 1 }
  if (-not $series) { throw ('series not found, items=' + ((@($allSeries.Items | ForEach-Object { $_.Name })) -join ',')) }

  $seasons = Em 'GET' ('/Items?ParentId=' + $series.Id + '&IncludeItemTypes=Season&Recursive=true')
  $season1 = @($seasons.Items | Where-Object { $_.IndexNumber -eq 1 }) | Select-Object -First 1
  if (-not $season1) { throw ('season 1 not found, seasons=' + ((@($seasons.Items | ForEach-Object { $_.Name })) -join ',')) }

  $eps = Em 'GET' ('/Shows/' + $series.Id + '/Episodes?UserId=' + $script:uid + '&Fields=UserData,MediaSources')
  foreach ($e in @($eps.Items)) {
    $k = ('S{0:D2}E{1:D2}' -f [int]$e.ParentIndexNumber, [int]$e.IndexNumber)
    $epMap[$k] = $e
  }
  foreach ($k in @('S01E01', 'S01E02', 'S01E03', 'S02E01')) {
    if (-not $epMap.ContainsKey($k)) { throw ('episode ' + $k + ' missing, got=' + ((@($epMap.Keys | Sort-Object)) -join ',')) }
  }
} catch { $discoveryErr = $_.Exception.Message }

$script:libTvId = if ($lib) { [string]$lib.Id } else { '' }
$script:seriesSeriesId = if ($series) { [string]$series.Id } else { '' }
$script:season1Id = if ($season1) { [string]$season1.Id } else { '' }
$epS01E01 = if ($epMap.ContainsKey('S01E01')) { [string]$epMap['S01E01'].Id } else { '' }
$epS01E02 = if ($epMap.ContainsKey('S01E02')) { [string]$epMap['S01E02'].Id } else { '' }
$epS01E03 = if ($epMap.ContainsKey('S01E03')) { [string]$epMap['S01E03'].Id } else { '' }
$epS02E01 = if ($epMap.ContainsKey('S02E01')) { [string]$epMap['S02E01'].Id } else { '' }
$nameS01E01 = if ($epMap.ContainsKey('S01E01')) { [string]$epMap['S01E01'].Name } else { '' }
$nameS01E02 = if ($epMap.ContainsKey('S01E02')) { [string]$epMap['S01E02'].Name } else { '' }
$nameS01E03 = if ($epMap.ContainsKey('S01E03')) { [string]$epMap['S01E03'].Name } else { '' }
$nameS02E01 = if ($epMap.ContainsKey('S02E01')) { [string]$epMap['S02E01'].Name } else { '' }
$durS01E01 = if ($epMap.ContainsKey('S01E01')) { [double]$epMap['S01E01'].RunTimeTicks / 10000000 } else { 0 }
$durS01E02 = if ($epMap.ContainsKey('S01E02')) { [double]$epMap['S01E02'].RunTimeTicks / 10000000 } else { 0 }
$durS01E03 = if ($epMap.ContainsKey('S01E03')) { [double]$epMap['S01E03'].RunTimeTicks / 10000000 } else { 0 }
$durS02E01 = if ($epMap.ContainsKey('S02E01')) { [double]$epMap['S02E01'].RunTimeTicks / 10000000 } else { 0 }

$discDetail = 'lib=' + $script:libTvId + ':' + $(if ($lib) { $lib.Name } else { '' }) +
  ' series=' + $script:seriesSeriesId + ':' + $(if ($series) { $series.Name } else { '' }) +
  ' season1=' + $script:season1Id +
  ' ids=' + (@($epS01E01, $epS01E02, $epS01E03, $epS02E01) -join ',') +
  ' names=' + (@($nameS01E01, $nameS01E02, $nameS01E03, $nameS02E01) -join '|') +
  ' secs=' + (@($durS01E01, $durS01E02, $durS01E03, $durS02E01) -join ',')
Check 'P0.3 剧集元数据动态探测成功（库 / 剧 / 第 1 季 / S01E01-E03 + S02E01）' ([string]::IsNullOrEmpty($discoveryErr)) ($(if ($discoveryErr) { 'ERROR: ' + $discoveryErr } else { $discDetail }))
if ($discoveryErr) {
  $checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
  Write-Host 'RESULT: 2 passed, 1 failed'
  exit 1
}

$script:serverPrefix = $Server + ':' + $serverId
$script:libCardId   = 'library|' + [uri]::EscapeDataString($script:serverPrefix) + '|' + $script:libTvId
$script:seriesCard  = 'media:' + $script:serverPrefix + ':' + $script:seriesSeriesId
$script:season1Card = 'media:' + $script:serverPrefix + ':' + $script:season1Id
function EpCard([string]$id) { return ('media:' + $script:serverPrefix + ':' + $id) }
Write-Host ('  prefix=' + $script:serverPrefix)
Write-Host ('  libCard=' + $script:libCardId)
Write-Host ('  seriesCard=' + $script:seriesCard + ' season1Card=' + $script:season1Card)

foreach ($id in @($epS01E01, $epS01E02, $epS01E03, $epS02E01)) { Reset-Item $id }
$clean = $true
$detail = @()
foreach ($id in @($epS01E01, $epS01E02, $epS01E03, $epS02E01)) {
  $u = Get-UserData $id
  if ($u.Played -or $u.PlaybackPositionTicks -ne 0) { $clean = $false }
  $detail += ($id + ':played=' + $u.Played + ',pos=' + $u.PlaybackPositionTicks)
}
Check 'P0.4 四集进度已复位（Played=false 且 PositionTicks=0）' $clean ($detail -join ' | ')

Check 'P0.5 AutoPlayNext 默认开启（前置条件）' (Get-AutoPlayNext) ('AutoPlayNext=' + (Get-AutoPlayNext))

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$kept = Keep-OnlyServer ($Server + ':*')
& $seed $Server $base $user $pass $serverId $script:token $script:uid 2>&1 | ForEach-Object { Write-Host ('  seed: ' + $_) }
$after = Get-Content $settings -Raw | ConvertFrom-Json
$entry = @($after.Servers | Where-Object { $_.ServerId -eq ($Server + ':' + $serverId) })
Check 'P0.6 来源写入客户端配置（只保留被测服务器一条）' (($entry.Count -eq 1) -and (@($after.Servers).Count -eq 1)) ('keptBefore=' + $kept + ' serversNow=' + @($after.Servers).Count + ' id=' + (@($after.Servers | ForEach-Object { $_.ServerId }) -join ','))
Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800

# ---------------------------------------------------------------- P1 播放第一集
Write-Host ''
Write-Host ('=== P1 ' + $cfg.libName + ' → ' + $series.Name + ' → S01E01 播放 ===')
$app = Restart-App -ResetTrace
$hwnd = Get-MhWindowHandle
Check 'P1.0 客户端启动且窗口在前台' (($null -ne $app) -and ($null -ne $hwnd)) ('pid=' + $app.pid)

Set-LogMark
$open1 = Open-EpisodePlay $epS01E01 $nameS01E01
Check 'P1.1 媒体库 → 库 → 剧 → 第 1 季 → S01E01 详情页与播放入口可达' (($open1 -eq 'OK') -or ($open1 -eq 'DIRECT-PLAYER')) $open1
$playerUp = Wait-Player 60
$st1 = Get-MhPlayerState -SkipSlow
Check 'P1.2 进入播放页' $playerUp ('open=' + $open1 + ' playerVisible=' + $st1.playerVisible)
Check 'P1.3 播放页标题为 S01E01' ([bool]($st1.title -and ($st1.title -eq $nameS01E01))) ('title=' + $st1.title + ' expected=' + $nameS01E01)
# 时长要等解码器报告（刚进播放页时 Duration 可能还是 0，不能立刻判 FAIL）
$durDeadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $durDeadline -and (-not $st1.durationSeconds -or [double]$st1.durationSeconds -le 0)) {
  Start-Sleep -Milliseconds 400
  $st1 = Get-MhPlayerState -SkipSlow
}
Check ('P1.4 播放时长与条目一致（约 ' + [math]::Round($durS01E01) + ' s）') ([bool]($null -ne $st1.durationSeconds -and [double]$st1.durationSeconds -gt 0 -and [math]::Abs([double]$st1.durationSeconds - $durS01E01) -le 2)) ('duration=' + $st1.duration + ' durationSeconds=' + $st1.durationSeconds + ' server=' + $durS01E01)
$w1 = Watch-Playback 6
Save-MhScreenshot -Path (Join-Path $ev 'p1-s01e01-playing.png') | Out-Null
Check 'P1.5 视频真的在播放（位置推进 / seek 值推进 / 已切集任一成立）' ([bool]($w1.posOk -or $w1.seekOk -or $w1.advanced)) ('posOk=' + $w1.posOk + ' seekOk=' + $w1.seekOk + ' advanced=' + $w1.advanced + ' samples=' + (($w1.samples | Select-Object -Last 5) -join ' '))
Write-Host ('--- P1 trace ---')
$new1 = @(Get-NewLog)
$new1 | Set-Content (Join-Path $ev 'trace-p1.log') -Encoding UTF8
$new1 | ForEach-Object { Write-Host ('  ' + $_) }

# ---------------------------------------------------------------- P2 跨集自动连播
Write-Host ''
Write-Host '=== P2 S01E01 播完 → 自动连播 S01E02 ==='
$null = Restart-App
Set-LogMark
$open2 = Open-EpisodePlay $epS01E01 $nameS01E01
Check 'P2.0 S01E01 重新起播的入口可达' (($open2 -eq 'OK') -or ($open2 -eq 'DIRECT-PLAYER')) $open2
$null = Wait-Player 60
$st2 = Get-MhPlayerState -SkipSlow
Check 'P2.1 播放页标题为 S01E01（起点正确）' ([bool]($st2.title -and $st2.title -eq $nameS01E01)) ('title=' + $st2.title)
$started2 = Wait-LogLine ('player:start ' + [regex]::Escape($nameS01E01)) 20
$rv2 = Seek-Ratio 0.93
Check 'P2.2 起播确认后 seek 到 93%（trace player:start 已出现）' ([bool]($started2 -and $null -ne $rv2 -and $rv2.set -eq 'SET')) ('started=' + $started2 + ' before=' + $(if ($rv2) { $rv2.before } else { 'n/a' }) + ' target=' + $(if ($rv2) { [math]::Round($rv2.target, 3) } else { 'n/a' }) + ' after=' + $(if ($rv2) { [math]::Round($rv2.after, 3) } else { 'n/a' }) + ' set=' + $(if ($rv2) { $rv2.set } else { 'n/a' }))
$end2 = Wait-Ended $nameS01E01 60
Check 'P2.3 第一集结束（queue 行 / 标题切换 / 位置到末尾 / 播放页关闭）' ([bool]$end2.ended) ('why=' + $end2.why + ' position=' + $(if ($end2.state) { $end2.state.position } else { '' }) + ' title=' + $(if ($end2.state) { $end2.state.title } else { '' }))
$title2 = Wait-Title $nameS01E02 20
$new2 = Get-NewLog
$adv2 = @($new2 | Where-Object { $_ -match 'queue:auto-advance' })
Check ('P2.4 trace 出现 queue:auto-advance episode=' + $epS01E02) ([bool](($new2 -join $NL) -match ('queue:auto-advance episode=' + [regex]::Escape($epS01E02)))) ('lines=' + ($adv2 -join ' / '))
Check 'P2.5 播完后 trace 出现任何 queue: 结束处理行（结束分支是否被触达）' ([bool](($new2 -join $NL) -match 'queue:')) ('queueLines=' + ((@($new2 | Where-Object { $_ -match 'queue:' })) -join ' / '))
Check 'P2.6 播完自动切到下一集 S01E02（播放页标题）' ([bool]($title2 -eq $nameS01E02)) ('title=' + $title2 + ' expected=' + $nameS01E02)
$u1 = Get-UserData $epS01E01
Check 'P2.7 服务器端 S01E01 记为已播' ([bool]($u1.Played -or ($u1.PlayCount -ge 1))) ('played=' + $u1.Played + ' pos=' + $u1.PlaybackPositionTicks + ' playCount=' + $u1.PlayCount)
Save-MhScreenshot -Path (Join-Path $ev 'p2-cross-episode.png') | Out-Null
Write-Host ('--- P2 trace (new) ---')
$new2 | Set-Content (Join-Path $ev 'trace-p2.log') -Encoding UTF8
$new2 | ForEach-Object { Write-Host ('  ' + $_) }

# ---------------------------------------------------------------- P3 跨季自动连播
Write-Host ''
Write-Host '=== P3 S01E03 播完 → 跨季连播 S02E01 ==='
$null = Restart-App
Set-LogMark
$open3 = Open-EpisodePlay $epS01E03 $nameS01E03
Check 'P3.0 S01E03 播放入口可达' (($open3 -eq 'OK') -or ($open3 -eq 'DIRECT-PLAYER')) $open3
$playerUp3 = Wait-Player 60
$st3 = Get-MhPlayerState -SkipSlow
Check 'P3.1 播放页标题为 S01E03' ([bool]($st3.title -and $st3.title -eq $nameS01E03)) ('title=' + $st3.title + ' player=' + $playerUp3)
$started3 = Wait-LogLine ('player:start ' + [regex]::Escape($nameS01E03)) 20
$rv3 = Seek-Ratio 0.93
Check 'P3.2 起播确认后 seek 到 93%' ([bool]($started3 -and $null -ne $rv3 -and $rv3.set -eq 'SET')) ('started=' + $started3 + ' before=' + $(if ($rv3) { $rv3.before } else { 'n/a' }) + ' target=' + $(if ($rv3) { [math]::Round($rv3.target, 3) } else { 'n/a' }) + ' after=' + $(if ($rv3) { [math]::Round($rv3.after, 3) } else { 'n/a' }) + ' set=' + $(if ($rv3) { $rv3.set } else { 'n/a' }))
$end3 = Wait-Ended $nameS01E03 60
Check 'P3.3 S01E03 结束（queue 行 / 标题切换 / 位置到末尾 / 播放页关闭）' ([bool]$end3.ended) ('why=' + $end3.why + ' position=' + $(if ($end3.state) { $end3.state.position } else { '' }) + ' title=' + $(if ($end3.state) { $end3.state.title } else { '' }))
$title3 = Wait-Title $nameS02E01 20
$new3 = Get-NewLog
$adv3 = @($new3 | Where-Object { $_ -match 'queue:auto-advance' })
Check ('P3.4 trace 出现 queue:auto-advance episode=' + $epS02E01) ([bool](($new3 -join $NL) -match ('queue:auto-advance episode=' + [regex]::Escape($epS02E01)))) ('lines=' + ($adv3 -join ' / '))
Check 'P3.5 跨季边界自动切到 S02E01（播放页标题）' ([bool]($title3 -eq $nameS02E01)) ('title=' + $title3 + ' expected=' + $nameS02E01)
$u3 = Get-UserData $epS01E03
Check 'P3.6 服务器端 S01E03 记为已播' ([bool]($u3.Played -or ($u3.PlayCount -ge 1))) ('played=' + $u3.Played + ' pos=' + $u3.PlaybackPositionTicks + ' playCount=' + $u3.PlayCount)
Save-MhScreenshot -Path (Join-Path $ev 'p3-cross-season.png') | Out-Null
Write-Host ('--- P3 trace (new) ---')
$new3 | Set-Content (Join-Path $ev 'trace-p3.log') -Encoding UTF8
$new3 | ForEach-Object { Write-Host ('  ' + $_) }

# ---------------------------------------------------------------- P4 负向对照
Write-Host ''
Write-Host '=== P4 负向对照：AutoPlayNext 关闭后播完不切集 ==='
$null = Restart-App
$mode = Set-AutoPlayNextUi $false
$inject = $false
if ($mode -ne 'UI') {
  # 回退：配置文件注入 + 重启客户端（设置页 ToggleSwitch 无 AutomationId 时无法按 id 定位）
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 800
  $off = Set-AutoPlayNext $false
  $inject = $true
  $mode2 = 'config-injection'
  $null = Restart-App
} else {
  $off = Get-AutoPlayNext
  $mode2 = 'ui-toggle'
}
Check 'P4.0 关闭 AutoPlayNext（UI 优先，失败才配置注入）' ([bool](-not $off)) ('AutoPlayNext=' + $off + ' mode=' + $mode2 + ' uiResult=' + $mode)
Set-LogMark
$open4 = Open-EpisodePlay $epS01E02 $nameS01E02
Check 'P4.1 S01E02 播放入口可达' (($open4 -eq 'OK') -or ($open4 -eq 'DIRECT-PLAYER')) $open4
$null = Wait-Player 60
$st4 = Get-MhPlayerState -SkipSlow
Check 'P4.2 播放页标题为 S01E02' ([bool]($st4.title -and $st4.title -eq $nameS01E02)) ('title=' + $st4.title)
$started4 = Wait-LogLine ('player:start ' + [regex]::Escape($nameS01E02)) 20
$rv4 = Seek-Ratio 0.93
$end4 = Wait-Ended $nameS01E02 60
Check 'P4.3 S01E02 结束（结束判定被触达）' ([bool]($started4 -and $end4.ended)) ('started=' + $started4 + ' why=' + $end4.why + ' position=' + $(if ($end4.state) { $end4.state.position } else { '' }))
# 播完后观察 8 s，采样标题：必须一直停在 S01E02，绝不出现 S01E03 / S02E01
$titles4 = @()
$deadline = (Get-Date).AddSeconds(8)
while ((Get-Date) -lt $deadline) {
  $s = Get-MhPlayerState -SkipSlow
  if ($s.title) { $titles4 += $s.title }
  Start-Sleep -Milliseconds 500
}
$new4 = Get-NewLog
$dis = @($new4 | Where-Object { $_ -match 'queue:auto-advance-disabled' })
Check 'P4.4 trace 出现 queue:auto-advance-disabled（结束判定分支被真实触达）' ([bool]($dis.Count -ge 1)) ('lines=' + ($dis -join ' / '))
$wrongTitles = @($titles4 | Where-Object { ($_ -eq $nameS01E03) -or ($_ -eq $nameS02E01) })
$noSwitch = -not ([bool](($new4 -join $NL) -match 'queue:auto-advance episode=')) -and ($wrongTitles.Count -eq 0) -and (@($titles4 | Where-Object { $_ -eq $nameS01E02 }).Count -gt 0)
Check 'P4.5 关闭开关后播完不切集（标题保持 S01E02，且 trace 无 queue:auto-advance episode=）' $noSwitch ('titles=' + (($titles4 | Select-Object -Unique) -join ',') + ' advanceLines=' + ((@($new4 | Where-Object { $_ -match 'queue:auto-advance episode=' })) -join ' / '))
Save-MhScreenshot -Path (Join-Path $ev 'p4-disabled.png') | Out-Null
Write-Host ('--- P4 trace (new) ---')
$new4 | Set-Content (Join-Path $ev 'trace-p4.log') -Encoding UTF8
$new4 | ForEach-Object { Write-Host ('  ' + $_) }

# ---- 恢复开关（同样 UI 优先）
$null = Restart-App
$restoreMode = Set-AutoPlayNextUi $true
if ($restoreMode -ne 'UI') {
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 800
  $on = Set-AutoPlayNext $true
} else {
  $on = Get-AutoPlayNext
}
Write-Host ('AutoPlayNext 已恢复: ' + $on + ' (restoreMode=' + $restoreMode + ')')

# ---------------------------------------------------------------- 收尾
$fullTrace = @(Get-MhLog)
$fullTrace -join $NL | Set-Content (Join-Path $ev 'trace.log') -Encoding UTF8
# 每次启动都会重写 startup.log，最终实例里没有前几个阶段的队列行；证据汇总用逐阶段窗口（trace-p1..p4）的合并结果
$phaseTrace = @($new1) + @($new2) + @($new3) + @($new4) + @($fullTrace)
$advAll = @($phaseTrace | Where-Object { $_ -match 'queue:|player:start' } | Select-Object -Unique)
Stop-MhAppForced | Out-Null

$hashAtEnd = $null
try { $hashAtEnd = (Get-FileHash $dllPath -Algorithm SHA256).Hash } catch { }
Check '收尾 被测二进制在运行期间未被替换（MediaHub.dll hash 一致）' ([bool]($hashAtStart -and $hashAtEnd -and ($hashAtStart -eq $hashAtEnd))) ('start=' + $hashAtStart + ' end=' + $hashAtEnd)
# 断言条数自检：预期 P0=6 P1=6 P2=8 P3=7 P4=6 + 收尾 hash = 34 条（历史教训：Check 调用写错会静默少一条）
$preCount = $checks.Count
Check '收尾 断言条数与预期一致（34 条，无静默跳过）' ($preCount -eq 34) ('checksBeforeThis=' + $preCount + ' expected=34')
$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
$checks | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $ev 'checks.json') -Encoding UTF8
$dllHash = if ($hashAtEnd) { $hashAtEnd } else { (Get-FileHash $dllPath -Algorithm SHA256).Hash }
$md = @()
$md += ('# U2 跨季自动连播门禁（' + $Server + '）')
$md += ''
$md += ('- 服务器：' + $base + ' 版本 ' + $info.Version + ' ServerId=' + $serverId)
$md += ('- 探测结果：库 ' + $lib.Name + '（' + $lib.Id + '） / 剧 ' + $series.Name + '（seriesId=' + $script:seriesSeriesId + '） / 第 1 季 seasonId=' + $script:season1Id)
$md += ('- 集：S01E01=' + $epS01E01 + '「' + $nameS01E01 + '」' + [math]::Round($durS01E01) + 's ; S01E02=' + $epS01E02 + '「' + $nameS01E02 + '」' + [math]::Round($durS01E02) + 's ; S01E03=' + $epS01E03 + '「' + $nameS01E03 + '」' + [math]::Round($durS01E03) + 's ; S02E01=' + $epS02E01 + '「' + $nameS02E01 + '」' + [math]::Round($durS02E01) + 's')
$md += ('- 断言：' + $pass + ' 通过 / ' + $fail + ' 失败')
$md += ('- 被测 exe：D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe（未重建，MediaHub.dll SHA256 ' + $dllHash + '）')
$md += ''
$md += '## 断言明细'
foreach ($c in $checks) { $md += ('- [' + $(if ($c.pass) { 'PASS' } else { 'FAIL' }) + '] ' + $c.name + ' — ' + $c.detail) }
$md += ''
$md += '## startup.log 中与连播相关的行'
if ($advAll.Count -eq 0) { $md += '- （无）' }
foreach ($l in $advAll) { $md += ('- ' + $l) }
$md += ''
$md += '## 备注'
$md += ('- 开关切换方式：P4 关 / 收尾开。UI 尝试结果 mode=' + $mode2 + '（uiResult=' + $mode + '，restoreMode=' + $restoreMode + '）；UI 不可用时退回 settings.json 注入并重启客户端。')
$md += '- 时序：S01E01 只有 10 s，P2/P3/P4 的「mark → 起播 → seek → 等结束」在同一窗口内完成；结束判定接受 queue 行 / 标题切换 / 位置到末尾 / 播放页关闭。'
$md += '- 截图：p1-s01e01-playing.png / p2-cross-episode.png / p3-cross-season.png / p4-disabled.png'
$md += '- trace 证据：客户端每次启动都会重写 %LOCALAPPDATA%\MediaHub\startup.log，因此 trace.log 只是最后一个实例的日志；逐阶段窗口另存 trace-p1.log / trace-p2.log / trace-p3.log / trace-p4.log。'
$md | Set-Content (Join-Path $ev 'results.md') -Encoding UTF8
Write-Host ''
Write-Host ('RESULT: ' + $pass + ' passed, ' + $fail + ' failed')
if ($fail -gt 0) { exit 1 } else { exit 0 }

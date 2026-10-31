#Requires -Version 7
<#
.SYNOPSIS
W6 设备同步页（SyncPage）端到端门禁：真实 Emby / Jellyfin 服务器 + 真实客户端 GUI。

.DESCRIPTION
这一套只验收「同步页」这一件事，判据全部来自真实证据：UIA 元素存在性、页面上真实出现的文案，
以及脚本自己按产品协议读写的两个文件（sync-envelope.json / sync-sources.json）的字节级比对。
没有一条断言可以用「点了没报错」换绿。

判据纪律：
  1) 页面身份只认 AutomationId（sync.actions / sync.sources / sync.status / sync.preview.details），
     不按文案猜：同名的 TextBlock（列表项、摘要、状态栏 message）在全树里遍地都是，按文案找必然打偏。
  2) 导出的信封是「对外文件」，必须逐键扫描：不允许任何 secret/password/token/apiKey 键。
     唯一白名单是 capabilities.canSendSecrets —— 这个键名本身含 secret 字样，朴素子串搜索会把它
     误判成泄漏（它是协议字段，值必须是 false，脚本同时断言这一点）。
  3) 预览是「纯计算」：点预览前后，本地 sync-sources.json 与对端 sync-envelope.json 的
     字节（SHA256）与 mtime 都必须一模一样。只对 UI 断言等于没测（页面可以画出预览而顺手写入）。
  4) 逐源验证只有三种结果（已验证 / 认证失败 / 未验证）。反向断言「页面从未把验证结果写成已同步」时
     必须排除导出成功文案里的免责句「导出成功也不代表任何来源已同步。」——它含「已同步」但不代表同步成功，
     直接全树子串搜索会得到一条永远为假的断言。
  5) 幂等：重跑前先杀掉残留进程、清掉历史信封；失败也要在 finally 里杀进程，不留 MediaHub 进程。

已知前置：Start-MhApp 自带「用户正在使用这台机器就拒绝启动」的空闲门禁（除非设置 MH_ALLOW_ACTIVE=1）。
本脚本不绕过它；无人值守请用 windows/tools/acceptance/run-quiet.ps1 -Suites w6。

.EXAMPLE
pwsh -NoProfile -File windows/tools/acceptance/w6/sync-page-gui.ps1 -Server emby
#>
param(
  [ValidateSet('emby', 'jellyfin')][string]$Server = 'emby',
  [string]$Exe = 'D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------- 路径与模块
# 工作树可以从任意位置 clone / 复制，因此根目录由脚本位置推出来，不写死绝对路径。
$here       = $PSScriptRoot
$acceptance = Split-Path $here -Parent
$repoRoot   = (Resolve-Path (Join-Path $here '..\..\..\..')).Path
$R          = $repoRoot + '\'
$ev         = Join-Path $acceptance ('evidence\w6-sync\' + $Server)
New-Item -ItemType Directory -Force -Path $ev | Out-Null
Import-Module (Join-Path $acceptance 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue

$exe      = $Exe
$dll      = Join-Path (Split-Path $exe -Parent) 'MediaHub.dll'
$syncDir  = Join-Path $env:LOCALAPPDATA 'MediaHub'
$settings = Join-Path $syncDir 'settings.json'
$envelope = Join-Path $syncDir 'sync-envelope.json'
$store    = Join-Path $syncDir 'sync-sources.json'

# SeedSource 是既有的播种工具（写 settings.json 的 Servers 段）。工作树里不一定构建过它，
# 所以按「工作树优先、已知构建产物兜底」的顺序找，找不到就让 P0 报失败而不是静默播种为空。
$seedCandidates = @(
  (Join-Path $R 'windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'),
  'D:\deepseek_test\MediaHub_Win\windows\tools\SeedSource\bin\x64\Debug\net10.0-windows\win-x64\SeedSource.exe'
)
$seed = $null
foreach ($cand in $seedCandidates) {
  if (Test-Path $cand) { $seed = $cand; break }
}

if ($Server -eq 'emby') {
  $base     = 'http://127.0.0.1:8097'
  $user     = 'mh'
  $pass     = 'mh-test-pass'
  $serverId = '1271d0a1040046db8c0089cc328bbbf1'
  $uid      = 'a5d8c0447545470fa9e227b942569158'
  $verLike  = '4.*'
} else {
  $base     = 'http://127.0.0.1:8096'
  $user     = 'mediahub-test'
  $pass     = 'mediahub-test-password'
  $serverId = 'daffc6534af44bbb8f73848209519255'
  $uid      = 'c0c242f88d664109a5ed45f8ba43e335'
  $verLike  = '12.*'
}

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

# ---------------------------------------------------------------- 服务器 API
# 与产品同一套鉴权头：缺 Client/Device/Version 时 Emby 会 400，Jellyfin 会 401，
# 用「极简 Token 头」探针会得到与真实客户端不同的答案（u3 已钉过这一点）。
$script:authBase = 'MediaBrowser Client="MediaHub-Acceptance", Device="Acceptance", DeviceId="w6-sync-gui", Version="0.1"'
$script:token    = ''
$script:uid      = $uid

function Em([string]$method, [string]$path) {
  $headers = @{ Authorization = ($script:authBase + ', Token="' + $script:token + '"') }
  return Invoke-RestMethod -Method $method -Uri ($base + $path) -Headers $headers -TimeoutSec 30
}
function Connect-Server {
  $body = @{ Username = $user; Pw = $pass } | ConvertTo-Json -Compress
  return Invoke-RestMethod -Method POST -Uri ($base + '/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{ Authorization = $script:authBase } -Body $body -TimeoutSec 20
}

# ---------------------------------------------------------------- 文件指纹
# 预览「不得落盘」是字节级断言：长度、SHA256、LastWriteTimeUtc 三者都要一致。
function Get-FileFingerprint([string]$path) {
  if (-not (Test-Path $path)) {
    return [pscustomobject]@{ exists = $false; length = 0; sha = ''; mtime = '' }
  }
  $f = Get-Item -LiteralPath $path
  $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
  return [pscustomobject]@{ exists = $true; length = $f.Length; sha = $sha; mtime = $f.LastWriteTimeUtc.ToString('o') }
}
function Test-FingerprintSame($a, $b) {
  if ($null -eq $a -or $null -eq $b) { return $false }
  return ($a.exists -eq $b.exists) -and ($a.length -eq $b.length) -and ($a.sha -eq $b.sha) -and ($a.mtime -eq $b.mtime)
}
function Format-Fingerprint($f) {
  if ($null -eq $f) { return 'null' }
  if (-not $f.exists) { return 'absent' }
  return ('len=' + $f.length + ' sha=' + $f.sha.Substring(0, [Math]::Min(16, $f.sha.Length)) + ' mtime=' + $f.mtime)
}

# ---------------------------------------------------------------- JSON 键扫描
# 逐键遍历，而不是全文子串搜索：子串搜索既会漏掉「键存在但值为空」的泄漏，也会被
# canSendSecrets 这样的合法键名反噬。
function Get-JsonKeyPaths($node, [string]$prefix) {
  $out = New-Object System.Collections.Generic.List[string]
  if ($null -eq $node) { return $out }
  if ($node -is [System.Management.Automation.PSCustomObject]) {
    foreach ($p in $node.PSObject.Properties) {
      $path = $p.Name
      if ($prefix) { $path = $prefix + '.' + $p.Name }
      $out.Add($path)
      foreach ($child in @(Get-JsonKeyPaths $p.Value $path)) { $out.Add($child) }
    }
    return $out
  }
  if (($node -is [System.Collections.IEnumerable]) -and ($node -isnot [string])) {
    $i = 0
    foreach ($item in @($node)) {
      foreach ($child in @(Get-JsonKeyPaths $item ($prefix + '[' + $i + ']'))) { $out.Add($child) }
      $i = $i + 1
    }
  }
  return $out
}
function Clone-JsonObject($node) {
  return ($node | ConvertTo-Json -Depth 14 | ConvertFrom-Json)
}
function Write-JsonFile([string]$path, $node) {
  $json = $node | ConvertTo-Json -Depth 14
  # 信封是按 UTF-8 无 BOM 读写的（产品用 JsonDocument 解析），这边保持同样的编码习惯。
  [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
}
function Get-NewHex([string]$seedText, [int]$length) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($seedText))
  $hex = ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
  return $hex.Substring(0, $length)
}

# ---------------------------------------------------------------- 配置读写
function Get-SettingsServers {
  if (-not (Test-Path $settings)) { return @() }
  try { $raw = Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json } catch { return @() }
  return @($raw.Servers)
}
function Keep-OnlyServer([string]$pattern) {
  if (-not (Test-Path $settings)) { return -1 }
  $raw = Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json
  $raw.Servers = @($raw.Servers | Where-Object { $_.ServerId -like $pattern })
  $raw | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $settings -Encoding UTF8
  return @($raw.Servers).Count
}
function Get-AutoPlayNext {
  if (-not (Test-Path $settings)) { return '<no-settings>' }
  try { $s = Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json } catch { return '<unparsable>' }
  if ($null -eq $s.PSObject.Properties['AutoPlayNext']) { return '<absent>' }
  return [string]$s.AutoPlayNext
}

# ---------------------------------------------------------------- UI helpers
function Wait-Element([string]$id, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 250
  }
  return $null
}
function Invoke-Safe($element) {
  if (-not $element) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $element) } catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Click-Id([string]$id, [int]$timeoutSec = 20) {
  $e = Wait-Element $id $timeoutSec
  return (Invoke-Safe $e)
}
function Go-Nav([string]$name) {
  $item = Find-MhAppElement -Name $name -IncludeOffscreen
  if (-not $item) { return $false }
  Invoke-MhElement -Element $item | Out-Null
  Start-Sleep -Milliseconds 1200
  return $true
}
function Wait-Container([string]$id, [int]$timeoutSec = 45) {
  return (Wait-Element $id $timeoutSec)
}
# 状态栏（InfoBar sync.status）的可见文案：Title 在 InfoBar 自身 Name 上，Message 是它的子 TextBlock。
function Get-StatusText {
  $e = Find-MhAppElement -AutomationId 'sync.status' -IncludeOffscreen
  if (-not $e) { return '' }
  $parts = New-Object System.Collections.Generic.List[string]
  $n = $e.Current.Name
  if ($n) { $parts.Add([string]$n) }
  foreach ($d in @(Get-MhElementAll -Root $e)) {
    $dn = $d.Current.Name
    if ($dn) { $parts.Add([string]$dn) }
  }
  if ($parts.Count -eq 0) { return '' }
  return (($parts | Select-Object -Unique) -join ' | ')
}
function Wait-Status([string]$pattern, [int]$timeoutSec = 45) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = ''
  while ((Get-Date) -lt $deadline) {
    $last = Get-StatusText
    if ($last -and ($last -like $pattern)) { return $last }
    Start-Sleep -Milliseconds 300
  }
  return $last
}
function Get-ChildIds($root) {
  $ids = New-Object System.Collections.Generic.List[string]
  if (-not $root) { return $ids }
  foreach ($d in @(Get-MhElementAll -Root $root)) {
    $id = $d.Current.AutomationId
    if ($id) { $ids.Add([string]$id) }
  }
  return $ids
}
# 来源行只在 sync.sources 子树里枚举：页面标题、状态栏、预览明细都可能带同名文字。
function Get-SourceRows {
  $list = Find-MhAppElement -AutomationId 'sync.sources' -IncludeOffscreen
  if (-not $list) { return @() }
  $rows = @()
  foreach ($e in @(Get-MhElementAll -Root $list)) {
    try { $ct = Get-MhControlType $e } catch { continue }
    if ($ct -eq 'ListItem' -or $ct -eq 'DataItem') { $rows += $e }
  }
  return @($rows)
}
function Get-RowByIndex([int]$index) {
  $rows = @(Get-SourceRows)
  if ($rows.Count -gt $index) { return $rows[$index] }
  return $null
}
function Get-RowText($row) {
  if (-not $row) { return '' }
  $parts = New-Object System.Collections.Generic.List[string]
  try {
    $n = $row.Current.Name
    if ($n) { $parts.Add([string]$n) }
    foreach ($d in @(Get-MhElementAll -Root $row)) {
      $dn = $d.Current.Name
      if ($dn) { $parts.Add([string]$dn) }
    }
  } catch { }
  if ($parts.Count -eq 0) { return '' }
  return (($parts | Select-Object -Unique) -join ' | ')
}
function Get-RowVerifyButton($row) {
  if (-not $row) { return $null }
  try {
    foreach ($d in @(Get-MhElementAll -Root $row)) {
      if ($d.Current.AutomationId -eq 'sync.verify') { return $d }
    }
  } catch { }
  return $null
}
function Wait-RowText([string]$pattern, [int]$timeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = ''
  while ((Get-Date) -lt $deadline) {
    $last = Get-RowText (Get-RowByIndex 0)
    if ($last -and ($last -match $pattern)) { return $last }
    Start-Sleep -Milliseconds 400
  }
  return $last
}
function Get-PreviewRows {
  $list = Find-MhAppElement -AutomationId 'sync.preview.details' -IncludeOffscreen
  if (-not $list) { return @() }
  $rows = @()
  foreach ($e in @(Get-MhElementAll -Root $list)) {
    try { $ct = Get-MhControlType $e } catch { continue }
    if ($ct -eq 'ListItem' -or $ct -eq 'DataItem') { $rows += $e }
  }
  return @($rows)
}
function Get-PreviewText {
  $parts = New-Object System.Collections.Generic.List[string]
  $list = Find-MhAppElement -AutomationId 'sync.preview.details' -IncludeOffscreen
  if ($list) {
    foreach ($d in @(Get-MhElementAll -Root $list)) {
      $n = $d.Current.Name
      if ($n) { $parts.Add([string]$n) }
    }
  }
  # 汇总行没有 AutomationId（SyncPage.xaml 里就是个 TextBlock），只能按它独有的句式捞。
  try {
    foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
      $n = $e.Current.Name
      if (-not $n) { continue }
      if (($n -like '共 * 项*') -or ($n -like '对端信封里没有媒体源*') -or ($n -like '尚未预览*')) { $parts.Add([string]$n) }
    }
  } catch { }
  if ($parts.Count -eq 0) { return '' }
  return (($parts | Select-Object -Unique) -join ' | ')
}
# 页面全树落盘：失败时能区分「按预期消失」与「整页没加载」，是这类断言唯一可靠的事后证据。
function Dump-Tree([string]$name) {
  $path = Join-Path $ev ($name + '.txt')
  $lines = New-Object System.Collections.Generic.List[string]
  try {
    foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
      $c = $e.Current
      $ct = ''
      try { $ct = Get-MhControlType $e } catch { $ct = '?' }
      $lines.Add(($ct + ' | id=' + [string]$c.AutomationId + ' | name=' + ([string]$c.Name) + ' | offscreen=' + [string]$c.IsOffscreen))
    }
  } catch {
    $lines.Add('TREE-DUMP-FAILED: ' + $_.Exception.Message)
  }
  $lines | Set-Content -LiteralPath $path -Encoding UTF8
  return $path
}

Write-Host ('W6 同步页门禁  server=' + $Server + '  exe=' + $exe)
Write-Host ('证据目录: ' + $ev)
Write-Host ''

$appReady = $false
$serverInfo = $null
$dllHash0 = ''
$storeBaseline = $null
$envelopeBaseline = $null
$peerInfo = ''
$autoPlay0 = ''
$statusText = ''

try {
  # ---------------------------------------------------------------- P0 准备
  Write-Host '=== P0 准备（真实服务器 / 单一来源 / 被测二进制指纹） ==='
  Stop-MhAppForced | Out-Null
  $leftover = @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue).Count
  Check 'P0.0 起跑前没有残留 MediaHub 进程' ($leftover -eq 0) ('processes=' + $leftover)

  $login = $null
  $loginError = ''
  try { $login = Connect-Server } catch { $loginError = $_.Exception.Message }
  if ($login) {
    $script:token = $login.AccessToken
    $script:uid = $login.User.Id
  }
  Check 'P0.1 官方服务器登录成功' ($null -ne $login) $(if ($login) { 'user=' + $login.User.Name + ' uid=' + $script:uid } else { 'login failed: ' + $loginError })

  if ($script:token) {
    try { $serverInfo = Em 'GET' '/System/Info' } catch { $serverInfo = $null }
  }
  $infoOk = ($null -ne $serverInfo) -and ([string]$serverInfo.Id -eq $serverId) -and ([string]$serverInfo.Version -like $verLike)
  Check 'P0.2 服务器版本与 ServerId 均符合预期' $infoOk $(if ($serverInfo) { 'version=' + $serverInfo.Version + ' serverId=' + $serverInfo.Id + ' expectedServerId=' + $serverId } else { 'system/info unavailable' })

  # 只保留被测来源：多来源会让「同步页显示了几行」「导出几个来源」全都失去确定性。
  $keptBefore = Keep-OnlyServer ($Server + ':*')
  $seeded = @()
  if ($seed) {
    $seeded = @(& $seed $Server $base $user $pass $serverId $script:token $script:uid 2>&1 | ForEach-Object { [string]$_ })
  }
  $null = Keep-OnlyServer ($Server + ':*')
  $serversNow = @(Get-SettingsServers)
  $seedDetail = 'seed=' + $(if ($seed) { $seed } else { 'NOT-FOUND' }) + ' keptBefore=' + $keptBefore + ' serversNow=' + $serversNow.Count + ' ids=' + (($serversNow | ForEach-Object { [string]$_.ServerId }) -join ',')
  if ($seeded.Count -gt 0) { $seedDetail = $seedDetail + ' last=' + ($seeded[-1]) }
  Check 'P0.3 SeedSource 播种成功且客户端配置只剩被测来源' (($serversNow.Count -eq 1) -and ($seed -ne $null)) $seedDetail

  $dllExists = Test-Path $dll
  if ($dllExists) { $dllHash0 = (Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash }
  $dllInfo = Get-Item -LiteralPath $dll -ErrorAction SilentlyContinue
  Check 'P0.4 被测二进制存在且已记录 MediaHub.dll 的 SHA256' $dllExists $(if ($dllExists) { 'size=' + $dllInfo.Length + ' mtime=' + $dllInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + ' sha256=' + $dllHash0 } else { 'missing: ' + $dll })

  # 历史信封必须清掉：否则「导出成功」这条断言可能读的是上一轮遗留的文件（假绿）。
  foreach ($stale in @($envelope, ($envelope + '.tmp'))) {
    if (Test-Path $stale) { Remove-Item -LiteralPath $stale -Force -ErrorAction SilentlyContinue }
  }
  $staleGone = -not (Test-Path $envelope)
  $storeFp0 = Get-FileFingerprint $store
  Check 'P0.5 历史信封已清理（导出断言不会读到上一轮的文件）' $staleGone ('envelopeExists=' + (Test-Path $envelope) + ' storeBefore=' + (Format-Fingerprint $storeFp0))

  $autoPlay0 = Get-AutoPlayNext
  Check 'P0.6 AutoPlayNext 现值已记录（本用例不改动它）' $true ('autoPlayNext=' + $autoPlay0)

  # ---------------------------------------------------------------- P1 页面身份
  Write-Host ''
  Write-Host '=== P1 同步页身份（只认 AutomationId） ==='
  $app = $null
  $startError = ''
  try { $app = Start-MhApp -Exe $exe -ResetTrace -TimeoutSec 60 } catch { $startError = $_.Exception.Message }
  $appReady = ($null -ne $app)
  Check 'P1.0 客户端启动成功（窗口可被 UIA 驱动）' $appReady $(if ($appReady) { 'pid=' + $app.Id } else { 'start failed: ' + $startError })

  if ($appReady) {
    $navOk = Go-Nav '设备同步'
    Check 'P1.1 导航项「设备同步」存在且点击成功' $navOk ('navClicked=' + $navOk)

    $actions = Wait-Container 'sync.actions' 45
    Check 'P1.2 同步页身份：sync.actions 出现' ($null -ne $actions) $(if ($actions) { 'name=' + $actions.Current.Name } else { 'NOT-FOUND sync.actions' })

    $sourcesList = Wait-Container 'sync.sources' 30
    Check 'P1.3 同步页身份：sync.sources 出现' ($null -ne $sourcesList) $(if ($sourcesList) { 'name=' + $sourcesList.Current.Name } else { 'NOT-FOUND sync.sources' })

    $actionsIds = @(Get-ChildIds $actions)
    $btnIds = @('sync.export', 'sync.preview', 'sync.refresh')
    $btnMissing = @()
    foreach ($bid in $btnIds) {
      if (-not (Find-MhAppElement -AutomationId $bid -IncludeOffscreen)) { $btnMissing += $bid }
      elseif ($actions -and ($actionsIds -notcontains $bid)) { $btnMissing += ($bid + '(不在sync.actions子树)') }
    }
    Check 'P1.4 操作区三按钮齐备且都在 sync.actions 子树内' ($btnMissing.Count -eq 0) ('missing=' + ($btnMissing -join ',') + ' actionsChildren=' + ($actionsIds -join ','))

    Start-Sleep -Milliseconds 800
    $rows = @(Get-SourceRows)
    $emptyEl = Find-MhAppElement -AutomationId 'sync.empty' -IncludeOffscreen
    $emptyVisible = ($null -ne $emptyEl) -and (-not $emptyEl.Current.IsOffscreen)
    $rowNames = @($rows | ForEach-Object { [string]$_.Current.Name })
    Check 'P1.5 来源列表非空且未落在空态上' (($rows.Count -ge 1) -and (-not $emptyVisible)) ('rows=' + $rows.Count + ' names=' + ($rowNames -join ';') + ' emptyVisible=' + $emptyVisible)

    $null = Dump-Tree 'p1-sync-page-tree'
    Save-MhScreenshot -Path (Join-Path $ev 'p1-sync-page.png') | Out-Null

    # -------------------------------------------------------------- P2 导出
    Write-Host ''
    Write-Host '=== P2 导出同步信封（对外文件，必须无秘密） ==='
    $exportInvoke = Click-Id 'sync.export' 20
    $statusText = Wait-Status '*已导出同步信封*' 45
    Check 'P2.1 点击导出后状态栏出现「已导出同步信封」' ($statusText -like '*已导出同步信封*') ('invoke=' + $exportInvoke + ' status=' + $statusText)

    Start-Sleep -Milliseconds 600
    $envFp = Get-FileFingerprint $envelope
    Check 'P2.2 信封已落盘且非空' ($envFp.exists -and ($envFp.length -gt 0)) ('envelope=' + $envelope + ' ' + (Format-Fingerprint $envFp))

    $envObj = $null
    $envRaw = ''
    if ($envFp.exists) {
      $envRaw = Get-Content -LiteralPath $envelope -Raw
      try { $envObj = $envRaw | ConvertFrom-Json } catch { $envObj = $null }
    }
    $schemaOk = $null -ne $envObj -and ([int]$envObj.schemaVersion -eq 1)
    Check 'P2.3 信封 schemaVersion=1' $schemaOk $(if ($envObj) { 'schemaVersion=' + $envObj.schemaVersion + ' minimumReaderVersion=' + $envObj.minimumReaderVersion + ' deviceId=' + $envObj.deviceId } else { 'envelope unreadable' })

    $srcCount = 0
    $syncId = ''
    if ($null -ne $envObj) {
      $srcCount = @($envObj.sources).Count
      if ($srcCount -ge 1) { $syncId = [string]$envObj.sources[0].syncSourceId }
    }
    $idOk = ($syncId -match '^local-[0-9a-f]{16}$')
    Check 'P2.4 sources[0].syncSourceId 非空且形如 local-<16 位十六进制>' ($srcCount -ge 1 -and $idOk) ('sources=' + $srcCount + ' syncSourceId=' + $syncId)

    $keyPaths = @()
    if ($null -ne $envObj) { $keyPaths = @(Get-JsonKeyPaths $envObj '') }
    $forbidden = @()
    foreach ($kp in $keyPaths) {
      $leaf = (($kp -split '\.')[-1]) -replace '\[\d+\]', ''
      if ($leaf -notmatch '(?i)(secret|password|passwd|token|apikey|api_key|credential)') { continue }
      if ($kp -eq 'capabilities.canSendSecrets') { continue }
      $forbidden += $kp
    }
    Check 'P2.5 信封不含任何 secret/password/token/apiKey 键（逐键扫描）' ($forbidden.Count -eq 0) ('keys=' + $keyPaths.Count + ' forbidden=' + ($forbidden -join ','))

    $canSend = $null
    $hasSecretsNode = $false
    if ($null -ne $envObj) {
      if ($envObj.capabilities) { $canSend = $envObj.capabilities.canSendSecrets }
      foreach ($kp in $keyPaths) { if ($kp -match '^sources\[\d+\]\.secrets') { $hasSecretsNode = $true } }
    }
    Check 'P2.6 canSendSecrets 明确为 false 且没有 sources[*].secrets 节点' (($canSend -eq $false) -and (-not $hasSecretsNode)) ('canSendSecrets=' + [string]$canSend + ' secretsNode=' + $hasSecretsNode)

    $tokenLeak = $envRaw.Contains($script:token)
    $passLeak = $envRaw.Contains($pass)
    Check 'P2.7 信封全文不含真实访问令牌与账号密码' ((-not $tokenLeak) -and (-not $passLeak)) ('tokenLen=' + $script:token.Length + ' tokenInFile=' + $tokenLeak + ' passwordInFile=' + $passLeak)

    $disclaimer = $statusText -like '*不代表任何来源已同步*'
    $countText = $statusText -like ('*共 ' + $srcCount + ' 个媒体源*')
    Check 'P2.8 导出文案如实说明不含密码且不代表已同步' ($disclaimer -and $countText) ('status=' + $statusText)

    Save-MhScreenshot -Path (Join-Path $ev 'p2-export.png') | Out-Null

    # -------------------------------------------------------------- P3 预览
    Write-Host ''
    Write-Host '=== P3 导入预览（纯计算，不得落盘） ==='
    # 对端信封：本机导出的那份原样保留，另外造两个来源凑出三态，
    # 这样「预览明细有行」不是靠运气，而是协议里必然出现的 新建 / 冲突 / 无变化。
    $peerBuilt = $false
    $peerNote = ''
    if ($null -ne $envObj -and @($envObj.sources).Count -ge 1) {
      $null = New-Item -ItemType Directory -Force -Path $ev
      Copy-Item -LiteralPath $envelope -Destination (Join-Path $ev 'exported-envelope.json') -Force
      $s0 = Clone-JsonObject @($envObj.sources)[0]
      $sNew = Clone-JsonObject $s0
      $sNew.syncSourceId = 'local-' + (Get-NewHex 'w6-acceptance-peer-new-source' 16)
      $sNew.displayName = ([string]$s0.displayName) + '（对端新增）'
      if (@($sNew.endpoints).Count -ge 1) { $sNew.endpoints[0].url = 'http://127.0.0.1:8199' }
      $sConflict = Clone-JsonObject $s0
      $sConflict.displayName = ([string]$s0.displayName) + '（对端改名）'
      $peer = [pscustomobject]@{
        schemaVersion        = [int]$envObj.schemaVersion
        minimumReaderVersion = [int]$envObj.minimumReaderVersion
        deviceId             = ('dev-' + (Get-NewHex 'w6-acceptance-peer-device' 12))
        snapshotId           = [guid]::NewGuid().ToString('N')
        createdAtEpochMs     = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        capabilities         = $envObj.capabilities
        sources              = @($s0, $sNew, $sConflict)
        deletions            = @()
      }
      Write-JsonFile $envelope $peer
      $peerBuilt = Test-Path $envelope
      $peerNote = 'peerDevice=' + $peer.deviceId + ' sources=' + @($peer.sources).Count + ' ids=' + ((@($peer.sources) | ForEach-Object { [string]$_.syncSourceId }) -join ',')
    } else {
      $peerNote = 'no exported envelope to build peer from'
    }
    Check 'P3.0 对端信封已就位（本机副本 + 新建/冲突/无变化三态）' $peerBuilt $peerNote

    # 预览前取指纹：这就是「预览不得落盘」的判据基线。
    $storeFpBefore = Get-FileFingerprint $store
    $envFpBefore = Get-FileFingerprint $envelope

    $previewInvoke = Click-Id 'sync.preview' 20
    $statusText = Wait-Status '*已生成合并预览*' 45
    Check 'P3.1 点击预览后状态栏出现「已生成合并预览」' ($statusText -like '*已生成合并预览*') ('invoke=' + $previewInvoke + ' status=' + $statusText)

    Start-Sleep -Milliseconds 800
    $previewRows = @(Get-PreviewRows)
    Check 'P3.2 预览明细有行（>=3：新建/冲突/无变化）' ($previewRows.Count -ge 3) ('rows=' + $previewRows.Count)

    $previewText = Get-PreviewText
    $hasNoChange = $previewText.Contains('无变化')
    $hasConfirm = $previewText.Contains('需用户确认')
    Check 'P3.3 明细文案含「无变化」与「需用户确认」' ($hasNoChange -and $hasConfirm) ('noChange=' + $hasNoChange + ' needConfirm=' + $hasConfirm + ' text=' + $previewText)

    Start-Sleep -Milliseconds 500
    $storeFpAfter = Get-FileFingerprint $store
    $storeSame = Test-FingerprintSame $storeFpBefore $storeFpAfter
    Check 'P3.4 预览没有改写本地同步源文件（字节与 mtime 均未变）' $storeSame ('before=' + (Format-Fingerprint $storeFpBefore) + ' after=' + (Format-Fingerprint $storeFpAfter))

    $envFpAfter = Get-FileFingerprint $envelope
    $envSame = Test-FingerprintSame $envFpBefore $envFpAfter
    Check 'P3.5 预览没有改写对端信封文件（字节与 mtime 均未变）' $envSame ('before=' + (Format-Fingerprint $envFpBefore) + ' after=' + (Format-Fingerprint $envFpAfter))

    $null = Dump-Tree 'p3-preview-tree'
    Save-MhScreenshot -Path (Join-Path $ev 'p3-preview.png') | Out-Null

    # -------------------------------------------------------------- P4 逐源验证
    Write-Host ''
    Write-Host '=== P4 逐源验证（三态，且绝不写成「已同步」） ==='
    $row0 = Get-RowByIndex 0
    $verifyBtn = Get-RowVerifyButton $row0
    $rowTextBefore = Get-RowText $row0
    Check 'P4.0 来源行内存在逐源验证按钮（sync.verify）' ($null -ne $verifyBtn) ('row=' + $rowTextBefore + ' button=' + $(if ($verifyBtn) { [string]$verifyBtn.Current.Name } else { 'NOT-FOUND' }))

    $beforeUnverified = $rowTextBefore.Contains('未验证')
    Check 'P4.1 验证前该行标签为「未验证」' $beforeUnverified ('rowText=' + $rowTextBefore)

    $verifyInvoke = Invoke-Safe $verifyBtn
    # 注意：验证前那行的标签本来就是「未验证」，所以「三态之一出现」这种写法会被初始值直接喂饱。
    # 这里等的是「离开未验证、进入已判定态」，并把前后文本都写进 detail。
    $rowTextAfter = Wait-RowText '(已验证|认证失败)' 90
    $threeState = ($rowTextAfter -match '(已验证|认证失败)')
    $transitioned = ($rowTextAfter -ne $rowTextBefore)
    Check 'P4.2 点击验证后行内进入已判定三态（已验证/认证失败）' ($threeState -and $transitioned) ('invoke=' + $verifyInvoke + ' before=' + $rowTextBefore + ' after=' + $rowTextAfter + ' states=已验证|认证失败|未验证')

    $verifiedOk = $rowTextAfter.Contains('已验证') -and $rowTextAfter.Contains('认证通过')
    Check 'P4.3 服务器可达时得到「已验证」且 detail 说明认证通过' $verifiedOk ('rowText=' + $rowTextAfter + ' status=' + (Get-StatusText))

    # 反向断言：整棵树里不允许把「同步成功」说成已同步。导出成功文案里的免责句
    # 「…导出成功也不代表任何来源已同步。」含同样三个字，必须先排除，否则这条断言永远为假。
    $syncedHits = New-Object System.Collections.Generic.List[string]
    $sourcesForScan = Find-MhAppElement -AutomationId 'sync.sources' -IncludeOffscreen
    if ($sourcesForScan) {
      foreach ($d in @(Get-MhElementAll -Root $sourcesForScan)) {
        $n = [string]$d.Current.Name
        if ($n -and $n.Contains('已同步')) { $syncedHits.Add('in-sources:' + $n) }
      }
    }
    foreach ($e in @(Get-MhAppElementAll -IncludeOffscreen)) {
      $n = [string]$e.Current.Name
      if (-not $n) { continue }
      $t = $n.Trim()
      if ($t -eq '已同步') { $syncedHits.Add('exact-label:' + [string]$e.Current.AutomationId) }
      if ($t -match '^已同步[:：]') { $syncedHits.Add('prefixed-label:' + $t) }
    }
    Check 'P4.4 反向断言：页面从未把验证结果写成「已同步」' ($syncedHits.Count -eq 0) ('hits=' + $syncedHits.Count + ' ' + ($syncedHits -join ';') + ' disclaimerExcluded=True')

    $null = Dump-Tree 'p4-verify-tree'
    Save-MhScreenshot -Path (Join-Path $ev 'p4-verified.png') | Out-Null

    # 保存这一轮结束时（预览与验证之后）的指纹，收尾要用它证明「读操作确实没写盘」。
    $storeBaseline = Get-FileFingerprint $store
    $envelopeBaseline = Get-FileFingerprint $envelope
    $peerInfo = 'store=' + (Format-Fingerprint $storeBaseline) + ' envelope=' + (Format-Fingerprint $envelopeBaseline)
  } else {
    Check 'P1~P4 依赖客户端已启动' $false ('SKIPPED: ' + $startError)
  }

  # ---------------------------------------------------------------- P5 收尾
  Write-Host ''
  Write-Host '=== P5 收尾（不留进程 / 二进制未变 / 配置未被本用例改动） ==='
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 800
  $after = @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue).Count
  Check 'P5.0 收尾后没有残留 MediaHub 进程' ($after -eq 0) ('processes=' + $after)

  $dllHash1 = ''
  if (Test-Path $dll) { $dllHash1 = (Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash }
  Check 'P5.1 运行期间被测 MediaHub.dll 未被替换' (($dllHash0 -ne '') -and ($dllHash0 -eq $dllHash1)) ('start=' + $dllHash0 + ' end=' + $dllHash1)

  $autoPlay1 = Get-AutoPlayNext
  Check 'P5.2 AutoPlayNext 未被本用例改动' ($autoPlay0 -eq $autoPlay1) ('start=' + $autoPlay0 + ' end=' + $autoPlay1)

  if ($null -ne $storeBaseline) {
    $storeFpEnd = Get-FileFingerprint $store
    $envFpEnd = Get-FileFingerprint $envelope
    $same1 = Test-FingerprintSame $storeBaseline $storeFpEnd
    $same2 = Test-FingerprintSame $envelopeBaseline $envFpEnd
    Check 'P5.3 预览与验证结束后本地同步源文件仍未被改写' $same1 ('baseline=' + (Format-Fingerprint $storeBaseline) + ' end=' + (Format-Fingerprint $storeFpEnd))
    Check 'P5.4 对端信封在预览后被原样保留（清理基线一致）' $same2 ('baseline=' + (Format-Fingerprint $envelopeBaseline) + ' end=' + (Format-Fingerprint $envFpEnd))
  } else {
    Check 'P5.3 预览与验证结束后本地同步源文件仍未被改写' $false 'SKIPPED: 未取得预览后基线（P1~P4 未完成）'
    Check 'P5.4 对端信封在预览后被原样保留（清理基线一致）' $false 'SKIPPED: 未取得预览后基线（P1~P4 未完成）'
  }

  $serversEnd = @(Get-SettingsServers).Count
  Check 'P5.5 客户端来源数仍为 1（验证与预览都没有增删来源）' ($serversEnd -eq 1) ('servers=' + $serversEnd)
}
catch {
  Check 'HARNESS 执行期异常未吞掉' $false ('exception=' + $_.Exception.Message + ' at=' + $_.InvocationInfo.PositionMessage)
}
finally {
  Stop-MhAppForced | Out-Null
  Start-Sleep -Milliseconds 500
  $final = @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue).Count
  Write-Host ('cleanup: MediaHub processes=' + $final)
}

# ---------------------------------------------------------------- 结果与证据
$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
$checks | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $ev 'checks.json') -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add('# W6 设备同步页门禁（' + $Server + '）')
$md.Add('')
$md.Add('- 服务器：' + $base + '  版本 ' + $(if ($serverInfo) { [string]$serverInfo.Version } else { 'unknown' }) + '  ServerId ' + $serverId)
$md.Add('- 被测二进制：' + $exe)
$md.Add('- MediaHub.dll SHA256：' + $dllHash0)
$md.Add('- 断言：' + $pass + ' 通过 / ' + $fail + ' 失败')
$md.Add('')
$md.Add('## 断言明细')
$md.Add('')
foreach ($c in $checks) {
  $md.Add('- [' + $(if ($c.pass) { 'PASS' } else { 'FAIL' }) + '] ' + $c.name + ' — ' + $c.detail)
}
$md.Add('')
$md.Add('## 证据产物')
$md.Add('')
$md.Add('- p1-sync-page.png / p2-export.png / p3-preview.png / p4-verified.png — 各阶段截图')
$md.Add('- p1-sync-page-tree.txt / p3-preview-tree.txt / p4-verify-tree.txt — 页面全树（ControlType | AutomationId | Name | offscreen）')
$md.Add('- exported-envelope.json — 本机导出的信封副本（P3 之前保存，用于事后核对键集合）')
$md.Add('- checks.json — 机器可读的断言结果')
$md | Set-Content -LiteralPath (Join-Path $ev 'results.md') -Encoding UTF8

function Print-Evidence {
  Write-Host ''
  Write-Host '--- 证据产物 ---'
  Write-Host ('目录: ' + $ev)
  foreach ($f in @(Get-ChildItem -LiteralPath $ev -File -ErrorAction SilentlyContinue | Sort-Object Name)) {
    Write-Host ('  ' + $f.Name + '  ' + $f.Length + ' B')
  }
  Write-Host ('信封文件: ' + $envelope)
  Write-Host ('本地同步源: ' + $store)
}
Print-Evidence

Write-Host ('RESULT: ' + $pass + ' passed, ' + $fail + ' failed')
if ($fail -gt 0) { exit 1 } else { exit 0 }

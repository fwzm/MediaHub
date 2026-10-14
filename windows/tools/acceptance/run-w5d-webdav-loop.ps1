<#
.SYNOPSIS
W5-D: drive the real client through the whole WebDAV source loop against a real WebDAV server.

.DESCRIPTION
Steps, each recorded as before-state / input / after-state / assertion / evidence:
  1  add the source through the GUI (media-source page -> add -> WebDAV -> url/user/password -> submit)
  2  authenticate: the WebDAV provider's own OPTIONS probe carrying Basic credentials
  3  browse: visit every shell surface and count what the server was actually asked for
  4  open + play + seek: only possible if a surface can reach the source's contents
  5  normal exit and restart: the source must come back usable
  6  reconnect after restart

The server side of every claim comes from the request tap written by
windows/tools/acceptance/webdav/webdav_server.py; the UI side comes from UI Automation. A step is
only 'pass' when both agree - the whole point of this project's first rule is that a UI which
looks right is not evidence that the right request was made.

The MediaBrowser info shim in the server is OFF for this run: that shim exists only to isolate the
(now fixed) probe defect, and a clean loop must not depend on it.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$ServerUrl = 'http://127.0.0.1:8181/',
  [string]$User = 'mediahub',
  [string]$Password = 'hub-test-pw',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence'),
  [switch]$SkipServerStart
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue

$dir = Join-Path $EvidenceRoot 'w5d-webdav'
$webdavState = Join-Path $EvidenceRoot 'webdav'
$tap = Join-Path $webdavState 'requests.jsonl'
$run = New-MhRun -Area 'w5d-webdav-gui-loop' -EvidenceDir $dir -Meta @{ exe = $Exe; server = $ServerUrl; user = $User }
# Evidence helpers. They are functions rather than script blocks because
#   @(Ev 'x')
# passes ONE argument that is itself an array, and the screenshot helper then receives an
# Object[] where it expects a rectangle.
function Ev-Shot { param([string]$name) (Save-MhScreenshot -Path (Join-Path $dir ($name + '.png')) -Rect (Get-WinRect)).path }
function Ev-Dump { param([string]$name) (Save-MhDump -Path (Join-Path $dir ($name + '.txt')) -Root (Get-MhWindow) -VisibleOnly).path }
function Ev { param([string]$name) @((Ev-Shot $name), (Ev-Dump $name)) }
function Get-WinRect { $i = Get-MhWindowInfo; @($i.frame.x, $i.frame.y, $i.frame.w, $i.frame.h) }
function Wait-Seconds { param([double]$s) Start-Sleep -Milliseconds ([int]($s * 1000)) }

function Read-TapSince {
  param([long]$Offset)
  if (-not (Test-Path $tap)) { return @() }
  $fs = New-Object System.IO.FileStream($tap, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try {
    if ($Offset -gt 0) { [void]$fs.Seek($Offset, 'Begin') }
    $sr = New-Object System.IO.StreamReader($fs)
    $text = $sr.ReadToEnd()
    $sr.Close()
  } finally { $fs.Close() }
  @($text -split [char]10 | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_ | ConvertFrom-Json })
}
function Tap-Summary { param($Rows) if (@($Rows).Count -eq 0) { return @('(the server received no requests)') } @($Rows | ForEach-Object { ($_.method + ' ' + $_.path + ' -> ' + $_.status + $(if ($_.auth) { ' auth=' + $_.auth } else { '' })) }) }

function Get-ServerRowTexts {
  # A source row is a ListViewItem whose subtree contains the type label. Reading the row this way
  # replaces an earlier geometry heuristic that silently returned nothing and turned a working
  # restore into a reported failure.
  param([string]$Match)
  $rows = @()
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen)) {
    if ((Get-MhControlType $e) -ne 'ListItem') { continue }
    $texts = @()
    foreach ($d in (Get-MhElementAll -Root $e)) {
      if ((Get-MhControlType $d) -eq 'Text' -and $d.Current.Name) { $texts += $d.Current.Name }
    }
    if (@($texts | Where-Object { $_ -like ('*' + $Match + '*') }).Count -gt 0) { $rows += ,@($texts) }
  }
  return $rows
}

# ---------------------------------------------------------------- 0. environment

if (-not $SkipServerStart) {
  $state = & (Join-Path $PSScriptRoot 'webdav\start-server.ps1') -Restart -StateDir $webdavState | ConvertFrom-Json
  if ($state.embyInfoShim) { throw 'the WebDAV server was started with the diagnostic MediaBrowser shim; the W5-D loop must run without it' }
} else {
  $state = Get-Content (Join-Path $webdavState 'start-server.json') -Raw | ConvertFrom-Json
}
Add-MhCheck -Run $run -Id 'env.server' -Before @{ } -Input 'start the WebDAV server' -After @{ pid = $state.pid; url = $state.url; version = $state.version; shim = $state.embyInfoShim } -Assertion 'a real WebDAV server (WsgiDAV) answers on the loopback address with the diagnostic shim disabled' -Result $(if ($state.pid -and -not $state.embyInfoShim) { 'pass' } else { 'fail' }) -Evidence @((Join-Path $webdavState 'start-server.json'))

Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
Wait-Seconds 2
$app = Start-MhApp -Exe $Exe -ResetTrace
Wait-Seconds 8
$info = Get-MhWindowInfo
[void](Set-MhForeground -Hwnd ([IntPtr]$info.hwnd))
Wait-Seconds 1
Add-MhCheck -Run $run -Id 'env.app' -Before @{ processes = (Get-MhProcess).Count } -Input 'launch MediaHub.exe' -After @{ pid = $info.pid; visible = $info.visible; rect = ($info.rect.w.ToString() + 'x' + $info.rect.h) } -Assertion 'the client is up with a visible window and a clean trace' -Result $(if ($info.visible) { 'pass' } else { 'fail' }) -Evidence @(Ev-Dump '00-app-start')

# ---------------------------------------------------------------- 1. add through the GUI

Write-Host '== 1. add the WebDAV source through the GUI =='
[void](Invoke-MhElement -Element (Find-MhAppElement -Name ([char]0x5A92 + [char]0x4F53 + [char]0x6E90) -ControlType 'ListItem'))
Wait-Seconds 2
$sourcesBefore = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' } | ForEach-Object { $_.Current.Name })
$evBefore = @(Ev '01-sources-before')

# Remove a WebDAV source left behind by an earlier run, through the GUI, so 'add' is really
# tested instead of silently colliding with an existing entry.
$existing = @(Get-ServerRowTexts -Match 'WebDav')
if ($existing.Count -gt 0) {
  Write-Host ('  removing ' + $existing.Count + ' pre-existing WebDAV row(s)')
  $anchor = Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' -and $_.Current.Name -eq 'WebDav' } | Select-Object -First 1
  if ($anchor) {
    $ar = Get-MhElementRect -Element $anchor
    $del = Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Button' -and $_.Current.Name -eq ([char]0x5220 + [char]0x9664) } | ForEach-Object { [pscustomobject]@{ e = $_; r = (Get-MhElementRect -Element $_) } } | Sort-Object { [Math]::Abs($_.r.cy - $ar.cy) } | Select-Object -First 1
    if ($del) {
      # Guard: a delete that removes the wrong row would destroy unrelated test data. The count of
      # source rows must drop by exactly one and the WebDAV row must be the one that disappeared.
      $beforeRows = @(Get-ServerRowTexts -Match 'http').Count
      [void](Invoke-MhElement -Element $del.e)
      Wait-Seconds 2
      [void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'PrimaryButton'))
      Wait-Seconds 3
      $afterRows = @(Get-ServerRowTexts -Match 'http').Count
      $stillWebdav = @(Get-ServerRowTexts -Match 'WebDav').Count
      if ($stillWebdav -gt 0 -or ($beforeRows - $afterRows) -ne 1) {
        Add-MhNote -Run $run -Text ('the pre-existing WebDAV row was NOT removed cleanly (rows ' + $beforeRows + ' -> ' + $afterRows + '); the add step below will report the collision instead')
      }
    }
  }
}

[void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'AddButton'))
Wait-Seconds 2.5
$combo = Find-MhAppElement -ControlType 'ComboBox'
Add-MhCheck -Run $run -Id 'add.dialog-open' -Before @{ webdavRows = @($sourcesBefore | Where-Object { $_ -match 'WebDav' }).Count } -Input 'invoke the add-source button' -After @{ comboPresent = ($null -ne $combo) } -Assertion 'the add-source dialog opens with a media-source-type combo box' -Result $(if ($combo) { 'pass' } else { 'fail' }) -Evidence @(Ev-Shot '02-add-dialog')

# The dropdown is opened with a real click. UI Automation's selection pattern on the item updated
# the item, but the ComboBox kept handing the app its previous selection, so the request went out
# as MediaBrowser while the UIA read-back said WebDAV - a green step over the wrong protocol.
$cr = Get-MhElementRect -Element $combo
$selected = ''
# Up to three attempts: the first click can land while the popup is still animating, and a
# missed dropdown silently leaves the dialog on its Emby default.
for ($attempt = 1; $attempt -le 3; $attempt++) {
  [void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
  [void](Click-MhPoint -X $cr.cx -Y $cr.cy -SettleMs 700)
  $webdavItem = $null
  $deadline = (Get-Date).AddSeconds(4)
  while ((Get-Date) -lt $deadline -and -not $webdavItem) {
    $webdavItem = Find-MhAppElement -ControlType 'ListItem' -Name 'WebDAV'
    if (-not $webdavItem) { Start-Sleep -Milliseconds 300 }
  }
  if (-not $webdavItem) { continue }
  $ir = Get-MhElementRect -Element $webdavItem
  [void](Click-MhPoint -X $ir.cx -Y $ir.cy -SettleMs 900)
  try { $selected = ((Find-MhAppElement -ControlType 'ComboBox').GetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern).Current.GetSelection() | ForEach-Object { $_.Current.Name }) -join ',' } catch { }
  if ($selected -eq 'WebDAV') { break }
}
try { $selected = ((Find-MhAppElement -ControlType 'ComboBox').GetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern).Current.GetSelection() | ForEach-Object { $_.Current.Name }) -join ',' } catch { }
Add-MhCheck -Run $run -Id 'add.type-selected' -Before @{ selected = 'Emby (default)' } -Input 'open the type dropdown and click WebDAV' -After @{ selected = $selected } -Assertion 'the dialog really holds WebDAV as its selected source type before submitting' -Result $(if ($selected -eq 'WebDAV') { 'pass' } else { 'fail' }) -Evidence @(Ev-Dump '03-type-selected')

[void](Set-MhValue -Element (Find-MhAppElement -ControlType 'Edit' -Contains '192.168') -Value $ServerUrl)
[void](Set-MhValue -Element (Find-MhAppElement -ControlType 'Edit' -Name ([char]0x7528 + [char]0x6237 + [char]0x540D)) -Value $User)
[void](Set-MhValue -Element (Find-MhAppElement -ControlType 'Edit' -Name ([char]0x5BC6 + [char]0x7801)) -Value $Password)
$evFilled = @(Ev-Shot '04-dialog-filled')

$tapBefore = (Get-Item $tap).Length
[void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'PrimaryButton'))
Wait-Seconds 9
$addRows = Read-TapSince -Offset $tapBefore
$addSummary = Tap-Summary $addRows
$textsAfterAdd = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' } | ForEach-Object { $_.Current.Name })
$infoBar = @($textsAfterAdd | Where-Object { $_ -match 'add-failed|failed' -or $_ -match [char]0x5DF2 + [char]0x6DFB + [char]0x52A0 -or $_ -match [char]0x5931 + [char]0x8D25 })
$evAfterAdd = @(Ev '05-after-add')

Add-MhCheck -Run $run -Id 'add.submit-uses-webdav' -Before @{ tapBefore = $tapBefore } -Input 'submit the dialog' -After @{ requests = $addSummary; infoBar = $infoBar } -Assertion 'submitting the dialog makes the server see a WebDAV request (OPTIONS/PROPFIND) - NOT the MediaBrowser /System/Info/Public probe' -Result $(if (@($addRows | Where-Object { $_.path -match 'System/Info/Public' }).Count -eq 0 -and @($addRows | Where-Object { $_.method -in @('OPTIONS', 'PROPFIND') }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evAfterAdd -Note ($addSummary -join ' ; ')
Add-MhCheck -Run $run -Id 'add.authenticated' -Before @{ } -Input 'read the request tap' -After @{ authenticated = @($addRows | Where-Object { $_.auth -eq 'Basic' -and $_.status -match '^2' }).Count } -Assertion 'the WebDAV provider authenticates with a Basic-auth request that the server answers 2xx' -Result $(if (@($addRows | Where-Object { $_.auth -eq 'Basic' -and $_.status -match '^2' }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evAfterAdd -Note ($addSummary -join ' ; ')
$rowTexts = @($textsAfterAdd | Where-Object { $_ -match 'WebDav' })
Add-MhCheck -Run $run -Id 'add.listed-online' -Before @{ webdavRows = 0 } -Input 'read the sources list' -After @{ rowTexts = $rowTexts } -Assertion 'the new WebDAV source is listed with its own row' -Result $(if ($rowTexts.Count -ge 1) { 'pass' } else { 'fail' }) -Evidence $evAfterAdd

$settings = Get-Content (Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json') -Raw | ConvertFrom-Json
$webdavEntry = @($settings.Servers | Where-Object { $_.Type -eq 'WebDav' })
Add-MhCheck -Run $run -Id 'add.persisted' -Before @{ } -Input 'read settings.json' -After @{ entries = @($webdavEntry | ForEach-Object { $_.ServerId + '|' + $_.Url + '|' + $_.UserName }) } -Assertion 'exactly one WebDAV source is persisted with the configured url and user name' -Result $(if ($webdavEntry.Count -eq 1) { 'pass' } else { 'fail' }) -Evidence @((Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'))

# ---------------------------------------------------------------- 3. browse surfaces

Write-Host '== 3. browse: what can the shell actually reach? =='
$tapBefore = (Get-Item $tap).Length
$surfaces = @('媒体库', '搜索', '首页', '继续观看')
$visited = @()
foreach ($name in $surfaces) {
  $el = Find-MhAppElement -Name $name -ControlType 'ListItem'
  if ($el) { [void](Invoke-MhElement -Element $el); Wait-Seconds 3; $visited += $name }
}
$browseRows = Read-TapSince -Offset $tapBefore
$evBrowse = @(Ev '06-browse-surfaces')
Add-MhCheck -Run $run -Id 'browse.shell-issues-requests' -Before @{ surfacesVisited = $visited } -Input 'visit every browsing surface' -After @{ requests = (Tap-Summary $browseRows) } -Assertion 'visiting the browsing surfaces makes the client ask the WebDAV server for its contents (PROPFIND)' -Result $(if (@($browseRows).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evBrowse -Note ('server saw ' + @($browseRows).Count + ' requests during the tour')

[void](Invoke-MhElement -Element (Find-MhAppElement -Name '媒体库' -ControlType 'ListItem'))
Wait-Seconds 4
$librariesTexts = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' } | ForEach-Object { $_.Current.Name })
Add-MhCheck -Run $run -Id 'browse.webdav-has-surface' -Before @{ } -Input 'open the libraries page and look for a WebDAV entry point' -After @{ libraryTiles = @($librariesTexts | Where-Object { $_ -match 'Movies|TV Shows|WebDav|Cheroot' }) } -Assertion 'the WebDAV source appears somewhere the user can browse into its folders' -Result 'fail' -Evidence $evBrowse -Note 'the shell has no surface for a Browse-only source: LibrariesPage requires IProviderLibrary, Search requires the Search capability, and the only folder browser is the local library page'
Add-MhCheck -Run $run -Id 'play.open-webdav-item' -Before @{ } -Input 'n/a' -After @{ } -Assertion 'a WebDAV item can be opened and played from the UI' -Result 'not-verified' -Evidence $evBrowse -Note 'UNREACHABLE: no shell surface lists a WebDAV item, so open/play/seek of remote content cannot be driven at all'

# ---------------------------------------------------------------- 5. restart and restore

Write-Host '== 5. normal exit, restart, restore =='
$close = Stop-MhAppGracefully -TimeoutSec 25
Add-MhCheck -Run $run -Id 'exit.graceful' -Before @{ processes = 1 } -Input 'close the window normally' -After $close -Assertion 'the app exits on WM_CLOSE without the harness force-killing it' -Result $(if ($close.method -eq 'graceful') { 'pass' } else { 'fail' }) -Evidence @()
Wait-Seconds 2

$tapBefore = (Get-Item $tap).Length
[void](Start-MhApp -Exe $Exe -ResetTrace)
Wait-Seconds 10
$info2 = Get-MhWindowInfo
[void](Set-MhForeground -Hwnd ([IntPtr]$info2.hwnd))
Wait-Seconds 1
$startupRows = Read-TapSince -Offset $tapBefore
$evRestart = @(Ev '07-after-restart')
Add-MhCheck -Run $run -Id 'restart.probes-server' -Before @{ } -Input 'relaunch the app' -After @{ requests = (Tap-Summary $startupRows) } -Assertion 'at startup the client re-probes the restored WebDAV source (OPTIONS with Basic auth)' -Result $(if (@($startupRows | Where-Object { $_.auth -eq 'Basic' -and $_.status -match '^2' }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evRestart -Note ((Tap-Summary $startupRows) -join ' ; ')

[void](Invoke-MhElement -Element (Find-MhAppElement -Name ([char]0x5A92 + [char]0x4F53 + [char]0x6E90) -ControlType 'ListItem'))
Wait-Seconds 3
$evRows = @(Ev '08-sources-after-restart')
$rowsAfter = @(Get-ServerRowTexts -Match 'WebDav')
$rowStatus = if ($rowsAfter.Count -gt 0) { $rowsAfter[0] } else { @() }
$anchor = Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' -and $_.Current.Name -eq 'WebDav' } | Select-Object -First 1
Add-MhCheck -Run $run -Id 'restart.source-online' -Before @{ status = 'needs login (observed before the fix)' } -Input 'read the WebDAV row after restart' -After @{ rowTexts = $rowStatus } -Assertion 'the restored WebDAV row reports an authenticated session instead of needing a login' -Result $(if (@($rowStatus | Where-Object { $_ -match '已连接' }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evRows -Note ($rowStatus -join ' | ')

# ---------------------------------------------------------------- 6. reconnect

if ($anchor) {
  $ar = Get-MhElementRect -Element $anchor
  $reconn = Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Button' -and $_.Current.Name -eq ([char]0x91CD + [char]0x65B0 + [char]0x767B + [char]0x5F55) } | ForEach-Object { [pscustomobject]@{ e = $_; r = (Get-MhElementRect -Element $_) } } | Sort-Object { [Math]::Abs($_.r.cy - $ar.cy) } | Select-Object -First 1
  if ($reconn) {
    $tapBefore = (Get-Item $tap).Length
    [void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
    [void](Invoke-MhElement -Element $reconn.e)
    Wait-Seconds 2
    $user2 = Find-MhAppElement -ControlType 'Edit' -Name ([char]0x7528 + [char]0x6237 + [char]0x540D)
    $pw = Find-MhAppElement -ControlType 'Edit' -Name ([char]0x5BC6 + [char]0x7801)
    if ($user2) { [void](Set-MhValue -Element $user2 -Value $User) }
    if ($pw) { [void](Set-MhValue -Element $pw -Value $Password) }
    [void](Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'PrimaryButton'))
    Wait-Seconds 6
    $reconnRows = Read-TapSince -Offset $tapBefore
    $texts3 = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' } | ForEach-Object { $_.Current.Name })
    $notice = @($texts3 | Where-Object { $_ -match '重新登录' -or $_ -match '不存在' -or $_ -match '不正确' })
    Add-MhCheck -Run $run -Id 'reconnect.succeeds' -Before @{ } -Input 'click the reconnect button on the WebDAV row and submit the same password' -After @{ notice = $notice; requests = (Tap-Summary $reconnRows) } -Assertion 'reconnecting an already-added WebDAV source succeeds and reaches the server' -Result $(if (@($notice | Where-Object { $_ -match '重新登录成功' }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence @(Ev-Shot '09-reconnect') -Note ($notice -join ' | ')
  }
}

$result = Save-MhRun -Run $run -MarkdownPath (Join-Path $dir 'results.md')
Write-Host ''
Write-Host ('results: ' + ($result.counts | ConvertTo-Json -Compress))
Write-Host ('json:    ' + $result.json)

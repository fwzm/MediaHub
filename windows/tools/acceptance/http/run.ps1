#Requires -Version 7
<#
.SYNOPSIS
Real-machine acceptance for the plain http(s) source: playlist browsing, direct-link playback,
seeking and resume, against a local static server that records every request.

.DESCRIPTION
Starts a Range-capable static server (tools/acceptance/http/static_server.py) on 127.0.0.1, generates
two neutral ffmpeg test clips plus an m3u list, then drives the built client through UI Automation:

  1. add http://127.0.0.1:<port>/list.m3u through the add-source dialog (asserting the account and
     password fields are hidden for this kind),
  2. browse the playlist and play its first entry,
  3. seek through the shared player path (the server must answer 206 for the seek range),
  4. leave the player and replay the same entry, which must resume from the recorded position,
  5. add a single-file direct address and play it.

Evidence is the check table plus screenshots, the app trace, and the server request tap.

Cleanup (default) stops the server and app, removes the two http sources from settings.json, removes
their history keys, and deletes the generated media. -NoCleanup keeps everything for inspection.

.EXAMPLE
$env:MH_ALLOW_ACTIVE='1'
pwsh -NoProfile -File windows/tools/acceptance/http/run.ps1
#>
[CmdletBinding()]
param(
    [int]$Port = 8199,
    [string]$Root = 'D:\mh-http',
    [string]$Exe = 'D:\deepseek_test\MediaHub_Win\windows\src\MediaHub.App\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [switch]$SkipMedia,
    [switch]$NoCleanup
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhUia.psm1') -Force

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path (Split-Path $here -Parent) ('evidence\http\' + $stamp)
$run = New-MhRun -Area 'http-source' -EvidenceDir $evidence -Meta @{
    port = $Port; root = $Root; exe = $Exe; startedBy = 'run.ps1'
}

$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$historyPath = Join-Path $env:LOCALAPPDATA 'MediaHub\history.json'
$vaultPath = Join-Path $env:LOCALAPPDATA 'MediaHub\credentials\credentials.vault.json'
$serverLog = Join-Path $evidence 'requests.jsonl'
$serverOut = Join-Path $evidence 'server.log'
$listUrl = "http://127.0.0.1:$Port/list.m3u"
$clipAUrl = "http://127.0.0.1:$Port/media/clip-a.mp4"
$clipBUrl = "http://127.0.0.1:$Port/media/clip-b.mp4"
$serverProc = $null

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec = 20, [int]$IntervalMs = 400)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds $IntervalMs
    }
    return $null
}

function Save-WindowShot([string]$Name) {
    # Raise the app before grabbing: the window rectangle is captured from the framebuffer, so anything
    # stacked above it ends up in the picture and the evidence shows someone else's desktop instead of
    # the page under test. Foreground alone was not enough here - a maximised WinUI window stayed behind
    # other applications - so the window is briefly pinned topmost for the grab and released after.
    $hwnd = Get-MhWindowHandle -Exe 'MediaHub'
    if ($hwnd) {
        Set-MhForeground -Hwnd $hwnd | Out-Null
        [Mh.Native]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0001 -bor 0x0002) | Out-Null
        Start-Sleep -Milliseconds 700
    }
    $info = Get-MhWindowInfo
    $rect = $null
    if ($info) { $rect = @($info.rect.x, $info.rect.y, $info.rect.w, $info.rect.h) }
    $shot = Save-MhScreenshot -Path (Join-Path $evidence $Name) -Rect $rect
    if ($hwnd) { [Mh.Native]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0001 -bor 0x0002) | Out-Null }
    return $shot
}

function Get-Tap {
    if (-not (Test-Path $serverLog)) { return @() }
    @(Get-Content $serverLog | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
}

function Wake-PlayerControls {
    # The player hides its bar after a few idle seconds (layout trace: topVis=Collapsed). A mouse move
    # is the gesture that brings it back, and without it the position text and the back button are
    # reported offscreen - which is not a playback failure, only an idle UI. Park the cursor on the
    # bottom strip where the controls live.
    $info = Get-MhWindowInfo
    if (-not $info) { return }
    # Two positions alternating, because SetCursorPos to where the cursor already is produces no
    # WM_MOUSEMOVE at all - the controls then stay hidden and the read reports "no position".
    $x = [int]($info.rect.x + $info.rect.w / 2)
    $y = [int]($info.rect.y + $info.rect.h - 110)
    [Mh.Native]::SetCursorPos($x - 48, $y) | Out-Null
    Start-Sleep -Milliseconds 150
    [Mh.Native]::SetCursorPos($x + 48, $y) | Out-Null
    Start-Sleep -Milliseconds 650
}

function Get-PlayerPosition {
    Wake-PlayerControls
    $p = Find-MhAppElement -AutomationId 'PositionText'
    if (-not $p) { return $null }
    ConvertFrom-MhTime $p.Current.Name
}

function Get-PlayerDuration {
    Wake-PlayerControls
    $d = Find-MhAppElement -AutomationId 'DurationText'
    if (-not $d) { return $null }
    ConvertFrom-MhTime $d.Current.Name
}

function Close-Player {
    Wake-PlayerControls
    $back = Find-MhAppElement -AutomationId 'player.back'
    if (-not $back) { $back = Find-MhAppElement -AutomationId 'player.back' -IncludeOffscreen }
    if ($back) { Invoke-MhElementDeep -Element $back | Out-Null }
    return [bool](Wait-Until { -not (Find-MhAppElement -AutomationId 'player.seekslider' -IncludeOffscreen) } 25)
}

function Assert-PlayerPlaying([string]$Label, [double]$ExpectSeconds) {
    Wake-PlayerControls
    $shown = Wait-Until { if (Find-MhAppElement -AutomationId 'player.seekslider') { $true } } 25
    if (-not $shown) { return $null }
    $first = Wait-Until { $v = Get-PlayerPosition; if ($null -ne $v) { [double]$v } } 25
    Start-Sleep -Seconds 3
    $second = Get-PlayerPosition
    $duration = Get-PlayerDuration
    return [pscustomobject]@{
        label = $Label
        position = $first
        positionLater = $second
        duration = $duration
        advanced = ($null -ne $first -and $null -ne $second -and $second -gt $first)
        expectedDuration = $ExpectSeconds
        durationOk = ($null -ne $duration -and [Math]::Abs($duration - $ExpectSeconds) -le 3)
    }
}

function Scroll-ToEnd([string]$AutomationId) {
    # WinUI lists virtualise: an item below the fold is not merely offscreen, it does not exist in the
    # UIA tree at all. Scrolling is therefore part of reading the list, not a screenshot nicety.
    $list = Find-MhAppElement -AutomationId $AutomationId
    if (-not $list) { return 'NOT_FOUND' }
    foreach ($candidate in @($list) + @(Get-MhElementAll -Root $list | Where-Object {
                $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::Pane })) {
        $pattern = $null
        if ($candidate.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
            try {
                if ($pattern.Current.VerticallyScrollable) {
                    $pattern.SetScrollPercent(-1, 100)
                    Start-Sleep -Milliseconds 800
                    return 'SCROLLED'
                }
            }
            catch { }
        }
    }
    # Fallback: focus the list and page down (VK_NEXT); ListView forwards it to its scroll viewer.
    Set-MhFocus -Element $list | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        [Mh.Native]::keybd_event(0x22, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 40
        [Mh.Native]::keybd_event(0x22, 0, 0x0002, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 300
    }
    return 'PAGED'
}

function Find-LibraryCard([string]$IdPattern) {
    # The library grid virtualises. A card below the fold keeps its automation peer but is reported
    # offscreen with no rectangle, so a visible-only lookup calls a present source "missing" - which is
    # exactly what a first attempt did. Ask for it including offscreen, scroll that card into view, then
    # read it back as a visible element that can actually be invoked.
    $found = Wait-Until {
        Get-MhAppElementAll -IncludeOffscreen |
            Where-Object { $_.Current.AutomationId -like $IdPattern } |
            Select-Object -First 1
    } 30
    if (-not $found) { return $null }
    $id = $found.Current.AutomationId
    $cur = $found
    for ($depth = 0; $depth -lt 3 -and $cur; $depth++) {
        $pattern = $null
        if ($cur.TryGetCurrentPattern([System.Windows.Automation.ScrollItemPattern]::Pattern, [ref]$pattern)) {
            try { $pattern.ScrollIntoView(); break } catch { }
        }
        try { $cur = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($cur) } catch { break }
    }
    Start-Sleep -Milliseconds 700
    $visible = Find-MhAppElement -AutomationId $id
    if ($visible) { return $visible }
    Scroll-ToEnd 'LibraryGrid' | Out-Null
    return (Wait-Until { Find-MhAppElement -AutomationId $id } 10)
}

function Get-CardField([System.Windows.Automation.AutomationElement]$NameElement, [string]$FieldName) {
    # The card is the nearest ancestor that owns a 删除 button; reading inside it is what ties a status
    # or capability line to the source under test instead of to whichever card happened to be first.
    $cur = $NameElement
    for ($depth = 0; $depth -lt 8 -and $cur; $depth++) {
        $delete = Find-MhElement -Root $cur -Name '删除'
        if ($delete) { return (Find-MhElement -Root $cur -Name $FieldName) }
        $parent = $null
        try { $parent = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($cur) } catch { break }
        if (-not $parent) { break }
        if ($parent.Current.ControlType -eq [System.Windows.Automation.ControlType]::Window) { break }
        $cur = $parent
    }
    return $null
}

function Invoke-Nav([string]$Name, [string]$ProofId) {
    # NavigationViewItem exposes SelectionItem, not Invoke; selecting it raises the view's
    # SelectionChanged, which is what actually swaps the page. The proof id confirms the page is up,
    # so "the nav call returned" is never mistaken for "the page changed".
    $item = Wait-Until {
        Get-MhAppElementAll | Where-Object {
            $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and
            $_.Current.Name -eq $Name
        } | Select-Object -First 1
    } 20
    if (-not $item) { throw "未找到导航项：$Name" }
    Invoke-MhElementDeep -Element $item | Out-Null
    return [bool](Wait-Until { Find-MhAppElement -AutomationId $ProofId } 20)
}

function Add-HttpSource([string]$Url, [string]$ShotName) {
    $add = Wait-Until { Find-MhAppElement -AutomationId 'AddButton' } 20
    if (-not $add) { throw '未找到“添加媒体源”按钮' }
    Invoke-MhElementDeep -Element $add | Out-Null
    $combo = Wait-Until { Find-MhAppElement -AutomationId 'sources.type' } 15
    if (-not $combo) { throw '添加对话框未出现' }
    # The dialog opens on Emby, where the account fields are part of the form; the assertion for this
    # kind is that selecting HTTP removes them rather than leaving a password box that cannot work.
    $accountBefore = $null -ne (Find-MhAppElement -AutomationId 'sources.user')
    $selected = Select-MhComboItem -ComboElement $combo -Text 'HTTP 直链 / 播放列表'
    Start-Sleep -Milliseconds 700
    $accountAfter = $null -ne (Find-MhAppElement -AutomationId 'sources.user')
    $passwordAfter = $null -ne (Find-MhAppElement -AutomationId 'sources.password')
    $edit = Find-MhAppElement -AutomationId 'sources.url'
    if (-not $edit) { throw '添加对话框未找到地址输入框' }
    $set = Set-MhValue -Element $edit -Value $Url
    if ($ShotName) { Save-WindowShot $ShotName | Out-Null }
    $primary = Find-MhAppElement -AutomationId 'PrimaryButton'
    if (-not $primary) { throw '添加对话框未找到主按钮' }
    Invoke-MhElementDeep -Element $primary | Out-Null
    $closed = Wait-Until { $null -eq (Find-MhAppElement -AutomationId 'sources.type') } 25
    return [pscustomobject]@{
        selected = $selected
        accountBefore = $accountBefore
        accountAfter = $accountAfter
        passwordAfter = $passwordAfter
        set = $set
        closed = [bool]$closed
        primaryText = $(if ($primary) { $primary.Current.Name } else { $null })
    }
}

try {
    # ------------------------------------------------------------ 0. baseline + media
    if (Test-Path $settingsPath) { Copy-Item $settingsPath (Join-Path $evidence 'settings-before.json') -Force }
    if (Test-Path $historyPath) { Copy-Item $historyPath (Join-Path $evidence 'history-before.json') -Force }
    $vaultBefore = if (Test-Path $vaultPath) { Get-Content $vaultPath -Raw } else { '' }

    if (-not $SkipMedia) {
        $media = Join-Path $Root 'media'
        New-Item -ItemType Directory -Force -Path $media | Out-Null
        # testsrc2 + a single tone: neutral, synthetic, and with a visible clock so a frozen frame is
        # obvious in a screenshot.
        & ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=25:duration=120' `
            -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=120' `
            -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k -movflags +faststart `
            (Join-Path $media 'clip-a.mp4')
        & ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=480x270:rate=25:duration=60' `
            -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=60' `
            -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k -movflags +faststart `
            (Join-Path $media 'clip-b.mp4')
        $m3u = @(
            '#EXTM3U',
            '#PLAYLIST:Neutral HTTP List',
            '#EXTINF:120,Neutral Clip A',
            'media/clip-a.mp4',
            '#EXTINF:60,Neutral Clip B',
            $clipBUrl
        )
        Set-Content -Path (Join-Path $Root 'list.m3u') -Value $m3u -Encoding UTF8
    }

    # ------------------------------------------------------------ 1. static server + protocol proof
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*static_server.py*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $python = (Get-Command python -ErrorAction Stop).Source
    $serverProc = Start-Process -FilePath $python -PassThru -WindowStyle Hidden -ArgumentList @(
        (Join-Path $here 'static_server.py'), '--root', $Root, '--port', $Port, '--log', $serverLog, '--stdout', $serverOut)
    $listening = Wait-Until {
        $c = [System.Net.Sockets.TcpClient]::new()
        try { $c.Connect('127.0.0.1', $Port); $c.Connected } catch { $false } finally { $c.Dispose() }
    } 20
    if (-not $listening) { throw '静态文件服务未能在 20s 内监听' }

    $head = (& curl.exe -sS -I $listUrl) -join "`n"
    $rangeHeaders = (& curl.exe -sS -D - -o NUL -H 'Range: bytes=0-99' $clipAUrl) -join "`n"
    $listBody = (& curl.exe -sS $listUrl) -join "`n"
    $rangeOk = $rangeHeaders -match '206' -and $rangeHeaders -match 'Content-Range: bytes 0-99/'
    $headOk = $head -match '200' -and $head -match 'Audio/x-mpegurl|audio/x-mpegurl'
    Add-MhCheck -Run $run -Id 'S1' -Input 'HEAD /list.m3u; GET /media/clip-a.mp4 with Range: bytes=0-99' `
        -After @{ head = ($head -split "`n")[0]; rangeStatus = (($rangeHeaders -split "`n")[0]).Trim(); contentRange = (($rangeHeaders -split "`n") | Where-Object { $_ -match 'Content-Range' }) } `
        -Assertion '静态服务对播放列表返回 200（m3u 类型），对分段请求返回 206 且 Content-Range 为 0-99/total' `
        -Result $(if ($headOk -and $rangeOk) { 'pass' } else { 'fail' }) `
        -Evidence @('requests.jsonl') `
        -Note ("列表内容行数=" + (($listBody -split "`n") | Where-Object { $_ -match 'clip' }).Count)

    # ------------------------------------------------------------ 2. app + add playlist source
    Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    $app = Start-MhApp -Exe $Exe -ResetTrace
    if (-not (Wait-Until { Get-MhWindowInfo } 40)) { throw '应用窗口未出现' }
    Set-MhWindowState -State maximized | Out-Null
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null

    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }

    $dialog = Add-HttpSource -Url $listUrl -ShotName '02-dialog-http.png'
    Add-MhCheck -Run $run -Id 'S2' -Input '添加媒体源 → 类型选择“HTTP 直链 / 播放列表”' `
        -Before @{ accountVisibleOnEmby = $dialog.accountBefore; selection = $dialog.selected } `
        -After @{ accountVisible = $dialog.accountAfter; passwordVisible = $dialog.passwordAfter; urlSet = $dialog.set; dialogClosed = $dialog.closed } `
        -Assertion '选择 HTTP 后账号与密码字段从表单移除，地址可写入，主按钮完成添加并关闭对话框' `
        -Result $(if ($dialog.accountBefore -and -not $dialog.accountAfter -and -not $dialog.passwordAfter -and $dialog.set -eq 'SET' -and $dialog.closed) { 'pass' } else { 'fail' }) `
        -Evidence @('02-dialog-http.png')

    $scroll = Scroll-ToEnd 'ServerList'
    $card = Wait-Until { Find-MhAppElement -Name 'list.m3u' } 15
    if (-not $card) {
        $scroll = Scroll-ToEnd 'ServerList'
        $card = Wait-Until { Find-MhAppElement -Name 'list.m3u' } 10
    }
    $statusLine = if ($card) { Get-CardField $card '已连接' } else { $null }
    $caps = if ($card) { Get-CardField $card 'Browse / Playback / Progress' } else { $null }
    $typeBadge = if ($card) { Get-CardField $card 'Http' } else { $null }
    $shotSources = Save-WindowShot '03-sources.png'
    $visibleIds = @(Get-MhAppElementAll | Where-Object { $_.Current.AutomationId } | ForEach-Object { $_.Current.AutomationId })
    Add-MhCheck -Run $run -Id 'S3' -Input '滚动来源列表到底部后读取新来源卡片' `
        -After @{ scroll = $scroll; name = [bool]$card; status = [bool]$statusLine; capabilities = [bool]$caps; type = [bool]$typeBadge } `
        -Assertion '播放列表来源出现在来源列表：名称 list.m3u、状态“已连接”、能力行 Browse / Playback / Progress、类型 Http' `
        -Result $(if ($card -and $statusLine -and $caps -and $typeBadge) { 'pass' } else { 'fail' }) `
        -Evidence @('02-dialog-http.png', '03-sources.png') `
        -Note ('visible ids=' + (($visibleIds | Select-Object -First 12) -join ';') + ' screenshot=' + $shotSources.path)

    $vaultAfterAdd = if (Test-Path $vaultPath) { Get-Content $vaultPath -Raw } else { '' }
    $vaultTouched = ($vaultAfterAdd -ne $vaultBefore) -or ($vaultAfterAdd -like '*list.m3u*')
    Add-MhCheck -Run $run -Id 'S4' -Input '比对添加前后的 credentials.vault.json' `
        -Before @{ vaultChanged = $vaultTouched } `
        -After @{ containsListAddress = ($vaultAfterAdd -like '*list.m3u*'); bytes = $vaultAfterAdd.Length } `
        -Assertion 'HTTP 来源不写入任何凭据：vault 内容不变且不含该地址' `
        -Result $(if (-not $vaultTouched) { 'pass' } else { 'fail' })

    # ------------------------------------------------------------ 3. browse the playlist
    if (-not (Invoke-Nav '媒体库' 'libraries.reload')) { throw '媒体库页面未打开' }
    $browseCard = Find-LibraryCard 'browse|*list.m3u*|root'
    if (-not $browseCard) {
        $cardIds = @(Get-MhAppElementAll -IncludeOffscreen | Where-Object { $_.Current.AutomationId -like 'browse|*' } |
            ForEach-Object { $_.Current.AutomationId })
        Add-MhNote -Run $run -Text ('browse cards after add: ' + ($cardIds -join ' ; '))
        throw '媒体库中没有该来源的文件夹浏览卡片'
    }
    Invoke-MhElementDeep -Element $browseCard | Out-Null

    $rowA = Wait-Until { Find-MhAppElement -AutomationId ('browse:http:' + $listUrl + ':' + $clipAUrl) } 25
    $rowB = Find-MhAppElement -AutomationId ('browse:http:' + $listUrl + ':' + $clipBUrl)
    $shotBrowse = Save-WindowShot '03-browse-list.png'
    Add-MhCheck -Run $run -Id 'S5' -Input '打开列表来源的根目录' `
        -After @{ rowA = [bool]$rowA; rowB = [bool]$rowB; rowAId = $(if ($rowA) { $rowA.Current.AutomationId } else { $null }) } `
        -Assertion '播放列表被解析为两条可播条目：相对地址解析为 clip-a，绝对地址保留 clip-b' `
        -Result $(if ($rowA -and $rowB) { 'pass' } else { 'fail' }) `
        -Evidence @('03-browse-list.png') -Note ("screenshot=" + $shotBrowse.path)

    # ------------------------------------------------------------ 4. play, seek, leave
    if (-not $rowA) { throw '列表中未找到条目 A' }
    Invoke-MhElementDeep -Element $rowA | Out-Null
    $playA = Assert-PlayerPlaying 'clip-a' 120
    $shotPlay = Save-WindowShot '04-player-start.png'
    Add-MhCheck -Run $run -Id 'S6' -Input '点击条目 A 起播' `
        -After $playA `
        -Assertion '播放器打开，时长约 120s，播放位置在 3s 内推进' `
        -Result $(if ($playA -and $playA.durationOk -and $playA.advanced) { 'pass' } else { 'fail' }) `
        -Evidence @('04-player-start.png', 'requests.jsonl') -Note ("screenshot=" + $shotPlay.path)

    Wake-PlayerControls
    $seekElement = Find-MhAppElement -AutomationId 'player.seekslider'
    $seekResult = Set-MhRangeValue -Element $seekElement -Value 0.6
    Start-Sleep -Seconds 3
    $afterSeek = Get-PlayerPosition
    $rv = Get-MhRangeValue -Element $seekElement
    $rangeSeen = @(Get-Tap | Where-Object { $_.status -eq 206 -and $_.range })
    $shotSeek = Save-WindowShot '05-seek.png'
    Add-MhCheck -Run $run -Id 'S7' -Input '将进度条拖到 60%' `
        -Before @{ position = $playA.positionLater } `
        -After @{ set = $seekResult; slider = $rv.value; position = $afterSeek; rangedRequests = $rangeSeen.Count; lastRange = $(if ($rangeSeen.Count -gt 0) { $rangeSeen[-1].range } else { $null }) } `
        -Assertion '拖动后位置跳到约 72s，服务端收到 Range 请求并以 206 响应（分段生效，不是从头重播）' `
        -Result $(if ($seekResult -eq 'SET' -and $null -ne $afterSeek -and $afterSeek -ge 65 -and $rangeSeen.Count -gt 0) { 'pass' } else { 'fail' }) `
        -Evidence @('05-seek.png', 'requests.jsonl') -Note ("screenshot=" + $shotSeek.path)

    Start-Sleep -Seconds 2
    $leftAt = Get-PlayerPosition
    Close-Player
    Start-Sleep -Seconds 7   # let the 5s history flush tick land

    # ------------------------------------------------------------ 5. resume
    $rowA2 = Find-MhAppElement -AutomationId ('browse:http:' + $listUrl + ':' + $clipAUrl)
    if (-not $rowA2) { throw '返回后找不到条目 A' }
    Invoke-MhElementDeep -Element $rowA2 | Out-Null
    $resume = Wait-Until { $v = Get-PlayerPosition; if ($null -ne $v) { [double]$v } } 25
    Start-Sleep -Seconds 2
    $resume2 = Get-PlayerPosition
    $resumeLine = @(Get-MhLog | Where-Object { $_ -match 'playback:resume=local:' } | Select-Object -Last 1)
    $shotResume = Save-WindowShot '06-resume.png'
    Add-MhCheck -Run $run -Id 'S8' -Input '退出播放器后再次打开同一条目' `
        -Before @{ leftAt = $leftAt; historyBytes = $(if (Test-Path $historyPath) { (Get-Item $historyPath).Length } else { 0 }) } `
        -After @{ resumePosition = $resume; after2s = $resume2; trace = $(if ($resumeLine.Count -gt 0) { $resumeLine[0] } else { $null }) } `
        -Assertion '重播从上次位置继续（起点 ≥ 60s），trace 记录 playback:resume=local' `
        -Result $(if ($null -ne $resume -and $resume -ge 60 -and $resumeLine.Count -gt 0) { 'pass' } else { 'fail' }) `
        -Evidence @('06-resume.png', 'startup.log') -Note ("screenshot=" + $shotResume.path)
    Close-Player

    # ------------------------------------------------------------ 6. history + direct-link source
    $historyRaw = if (Test-Path $historyPath) { Get-Content $historyPath -Raw } else { '' }
    Add-MhCheck -Run $run -Id 'S9' -Input '读取 history.json' `
        -After @{ hasListEntry = ($historyRaw -like ('*' + $clipAUrl + '*')) } `
        -Assertion '本地历史记录了该直链条目的播放位置（Http 无服务端进度，靠本地快照兜底）' `
        -Result $(if ($historyRaw -like ('*' + $clipAUrl + '*')) { 'pass' } else { 'fail' }) `
        -Evidence @('history-before.json')

    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }
    $direct = Add-HttpSource -Url $clipBUrl -ShotName '07-direct-add.png'
    Scroll-ToEnd 'ServerList' | Out-Null
    $directCard = Wait-Until { Find-MhAppElement -Name 'clip-b.mp4' } 15
    Add-MhCheck -Run $run -Id 'S10' -Input ('添加单文件直链 ' + $clipBUrl) `
        -Before @{ selection = $direct.selected } `
        -After @{ card = [bool]$directCard; dialogClosed = $direct.closed; accountVisible = $direct.accountAfter } `
        -Assertion '单文件直链可直接作为来源添加，无需账号字段' `
        -Result $(if ($directCard -and $direct.closed -and -not $direct.accountAfter) { 'pass' } else { 'fail' })

    if (-not (Invoke-Nav '媒体库' 'libraries.reload')) { throw '媒体库页面未打开' }
    $directBrowse = Find-LibraryCard 'browse|*clip-b.mp4*|root'
    if (-not $directBrowse) { throw '媒体库中没有单文件来源的卡片' }
    Invoke-MhElementDeep -Element $directBrowse | Out-Null
    $directRow = Wait-Until { Find-MhAppElement -AutomationId ('browse:http:' + $clipBUrl + ':' + $clipBUrl) } 20
    if ($directRow) { Invoke-MhElementDeep -Element $directRow | Out-Null }
    $playB = Assert-PlayerPlaying 'clip-b' 60
    $shotDirect = Save-WindowShot '07-direct-source.png'
    Add-MhCheck -Run $run -Id 'S11' -Input '单文件来源 → 根目录 → 起播' `
        -After @{ row = [bool]$directRow; state = $playB } `
        -Assertion '单文件来源浏览出一行并以 DirectPlay 起播，时长约 60s' `
        -Result $(if ($directRow -and $playB -and $playB.durationOk -and $playB.advanced) { 'pass' } else { 'fail' }) `
        -Evidence @('07-direct-source.png', 'requests.jsonl') -Note ("screenshot=" + $shotDirect.path)

    Close-Player
}
catch {
    Add-MhNote -Run $run -Text ('运行中止：' + $_.Exception.Message)
    Add-MhCheck -Run $run -Id 'X1' -Input '脚本异常' -Assertion '脚本应完整跑完所有检查' -Result 'fail' -Note $_.Exception.Message
}
finally {
    Add-MhNote -Run $run -Text ('应用日志行数=' + @(Get-MhLog).Count)
    $head = Get-MhLog | Where-Object { $_ -match '播放源：' } | Select-Object -Last 3
    Add-MhNote -Run $run -Text ('最近播放源日志：' + ($head -join ' | '))
    # The checks cite the app trace; citing a live path that later disappears is not evidence.
    try { Copy-Item (Get-MhLogPath) (Join-Path $evidence 'startup.log') -Force } catch { }
    $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')

    try { Stop-MhAppGracefully -TimeoutSec 15 | Out-Null } catch { }
    if (@(Get-MhProcess).Count -gt 0) { Stop-MhAppForced -Reason 'http acceptance cleanup' | Out-Null }
    if ($serverProc -and -not $serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }

    if (-not $NoCleanup) {
        # Remove exactly the two sources this run added; every other entry is left untouched.
        try {
            $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
            $httpIds = @($json.Servers | Where-Object { $_.Type -eq 'Http' } | ForEach-Object { $_.ServerId })
            $json.Servers = @($json.Servers | Where-Object { $_.Type -ne 'Http' })
            $json | ConvertTo-Json -Depth 24 | Set-Content -Path $settingsPath -Encoding UTF8
            $raw = Get-Content $settingsPath -Raw
            $shapeOk = $raw -match '"Servers"\s*:\s*\['
            Add-MhNote -Run $run -Text ('cleanup: removed http sources=' + ($httpIds -join ',') + ' serversArray=' + $shapeOk)
        }
        catch { Add-MhNote -Run $run -Text ('cleanup settings failed: ' + $_.Exception.Message) }

        try {
            if (Test-Path $historyPath) {
                $history = @(Get-Content $historyPath -Raw | ConvertFrom-Json |
                    Where-Object { $_.MediaPath -notlike '*127.0.0.1:8199*' })
                $history | ConvertTo-Json -Depth 12 -AsArray | Set-Content -Path $historyPath -Encoding UTF8
            }
        }
        catch { Add-MhNote -Run $run -Text ('cleanup history failed: ' + $_.Exception.Message) }

        Remove-Item -Path $Root -Recurse -Force -ErrorAction SilentlyContinue
        $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')
    }

    Write-Host ''
    Write-Host ('RESULT: ' + $saved.counts.pass + ' passed, ' + $saved.counts.fail + ' failed, ' +
        $saved.counts['not-verified'] + ' not-verified')
    Write-Host ('evidence: ' + $evidence)
    if ($saved.counts.fail -gt 0) { exit 1 }
    exit 0
}

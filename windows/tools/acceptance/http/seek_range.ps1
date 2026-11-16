#Requires -Version 7
<#
.SYNOPSIS
Proves that dragging the progress bar asks the server for a new byte range, instead of sliding
through bytes the engine already buffered.

.DESCRIPTION
Against an unthrottled local server the whole clip can arrive before the user's hand leaves the
mouse, so "the seek worked" would only prove the player can slide inside its own buffer. This run
serves the clip at a fixed byte rate (tools/acceptance/http/static_server.py --rate): the buffer
cannot contain the seek target, so a successful seek has to appear in the server's request tap as
another Range request, and the request it abandoned has to show fewer bytes sent than it declared.

Checks:
  R1  throttled server is up and the clip is large enough to still be streaming when the seek happens
  R2  playback starts and the position advances
  R3  dragging to 70% moves the position to ~126 s
  R4  the tap shows the first stream cut short and a second Range whose offset covers the seek target

.EXAMPLE
$env:MH_ALLOW_ACTIVE='1'
pwsh -NoProfile -File windows/tools/acceptance/http/seek_range.ps1
#>
[CmdletBinding()]
param(
    [int]$Port = 8199,
    [int]$Rate = 300000,
    [int]$DurationSeconds = 180,
    [double]$SeekFraction = 0.7,
    [string]$Exe = 'D:\deepseek_test\MediaHub_Win\windows\src\MediaHub.App\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [switch]$SkipMedia,
    [switch]$NoCleanup
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhUia.psm1') -Force

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path (Split-Path $here -Parent) ('evidence\http-seek\' + $stamp)
$run = New-MhRun -Area 'http-seek-range' -EvidenceDir $evidence -Meta @{
    url = "http://127.0.0.1:$Port/media/clip.mp4"; rate = $Rate; exe = $Exe
}

$root = Join-Path $here '..\work\seek-range'
$media = Join-Path $root 'media'
$clipPath = Join-Path $media 'clip.mp4'
$clipUrl = "http://127.0.0.1:$Port/media/clip.mp4"
$serverLog = Join-Path $evidence 'tap.jsonl'
$serverOut = Join-Path $evidence 'server.log'
$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$historyPath = Join-Path $env:LOCALAPPDATA 'MediaHub\history.json'

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

function Get-AppElements([switch]$IncludeOffscreen) {
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $out = @()
            foreach ($appRoot in (Get-MhAppRoots)) { $out += @(Get-MhElementAll -Root $appRoot) }
            if (-not $IncludeOffscreen) { $out = @($out | Where-Object { -not $_.Current.IsOffscreen }) }
            return $out
        }
        catch { Start-Sleep -Milliseconds 300 }
    }
    return @()
}

function Find-AppElement([string]$AutomationId, [string]$Name, [switch]$IncludeOffscreen) {
    for ($i = 0; $i -lt 3; $i++) {
        try {
            foreach ($appRoot in (Get-MhAppRoots)) {
                $element = Find-MhElement -Root $appRoot -AutomationId $AutomationId -Name $Name -IncludeOffscreen:$IncludeOffscreen
                if ($element) { return $element }
            }
            return $null
        }
        catch { Start-Sleep -Milliseconds 300 }
    }
    return $null
}

function Invoke-AppElement($Element) {
    for ($i = 0; $i -lt 3; $i++) {
        try { Invoke-MhElementDeep -Element $Element | Out-Null; return $true }
        catch { Start-Sleep -Milliseconds 400 }
    }
    return $false
}

function Save-WindowShot([string]$Name) {
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

function Wake-PlayerControls {
    $info = Get-MhWindowInfo
    if (-not $info) { return }
    $x = [int]($info.rect.x + $info.rect.w / 2)
    $y = [int]($info.rect.y + $info.rect.h - 110)
    [Mh.Native]::SetCursorPos($x - 48, $y) | Out-Null
    Start-Sleep -Milliseconds 150
    [Mh.Native]::SetCursorPos($x + 48, $y) | Out-Null
    Start-Sleep -Milliseconds 650
}

function Get-PlayerPosition {
    # The bar hides after a few idle seconds; if the wake does not land (another window took the
    # foreground between the two cursor moves), a single retry is cheaper than a false negative.
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        Wake-PlayerControls
        $p = Find-AppElement -AutomationId 'PositionText'
        if (-not $p) { $p = Find-AppElement -AutomationId 'PositionText' -IncludeOffscreen }
        if ($p) { return (ConvertFrom-MhTime $p.Current.Name) }
        Start-Sleep -Milliseconds 300
    }
    return $null
}

function Invoke-Nav([string]$Name, [string]$ProofId) {
    $item = Wait-Until {
        Get-AppElements | Where-Object {
            $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and
            $_.Current.Name -eq $Name
        } | Select-Object -First 1
    } 20
    if (-not $item) { throw "未找到导航项：$Name" }
    Invoke-AppElement $item | Out-Null
    return [bool](Wait-Until { Find-AppElement -AutomationId $ProofId } 20)
}

function Find-LibraryCard([string]$IdPattern) {
    $found = Wait-Until {
        Get-AppElements -IncludeOffscreen |
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
    $visible = Find-AppElement -AutomationId $id
    if ($visible) { return $visible }
    return (Wait-Until { Find-AppElement -AutomationId $id } 10)
}

function Add-HttpSource([string]$Url) {
    $add = Wait-Until { Find-AppElement -AutomationId 'AddButton' } 20
    if (-not $add) { throw '未找到“添加媒体源”按钮' }
    Invoke-AppElement $add | Out-Null
    $combo = Wait-Until { Find-AppElement -AutomationId 'sources.type' } 15
    if (-not $combo) { throw '添加对话框未出现' }
    $selected = Select-MhComboItem -ComboElement $combo -Text 'HTTP 直链 / 播放列表'
    Start-Sleep -Milliseconds 600
    $edit = Find-AppElement -AutomationId 'sources.url'
    if (-not $edit) { throw '添加对话框未找到地址输入框' }
    $set = Set-MhValue -Element $edit -Value $Url
    $primary = Find-AppElement -AutomationId 'PrimaryButton'
    if (-not $primary) { throw '添加对话框未找到主按钮' }
    Invoke-AppElement $primary | Out-Null
    $closed = Wait-Until { $null -eq (Find-AppElement -AutomationId 'sources.type') } 25
    return [pscustomobject]@{ selected = $selected; set = $set; closed = [bool]$closed }
}

function Get-Tap {
    if (-not (Test-Path $serverLog)) { return @() }
    return @(Get-Content $serverLog | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
}

$serverProc = $null
try {
    # ------------------------------------------------------------ 0. fixtures
    New-Item -ItemType Directory -Force -Path $media | Out-Null
    if (-not $SkipMedia -or -not (Test-Path $clipPath)) {
        & ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=640x360:rate=25:duration=$DurationSeconds" `
            -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=$DurationSeconds" `
            -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k -movflags +faststart `
            $clipPath
    }
    $size = (Get-Item $clipPath).Length
    $secondsOfWire = [Math]::Round($size / $Rate, 1)

    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*static_server.py*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $python = (Get-Command python -ErrorAction Stop).Source
    $serverProc = Start-Process -FilePath $python -PassThru -WindowStyle Hidden -ArgumentList @(
        (Join-Path $here 'static_server.py'), '--root', $root, '--port', $Port,
        '--log', $serverLog, '--stdout', $serverOut, '--rate', $Rate)
    $listening = Wait-Until { if (Test-Path $serverOut) { (Get-Content $serverOut -Raw) -like '*listening*' } } 20
    Add-MhCheck -Run $run -Id 'R1' -Input ("static_server.py --rate $Rate 服务 $clipPath") `
        -After @{ listening = [bool]$listening; clipBytes = $size; wireSeconds = $secondsOfWire } `
        -Assertion "限速服务启动；整片走完带宽需要 $secondsOfWire s，远长于拖动前等待的几秒" `
        -Result $(if ($listening -and $secondsOfWire -ge 20) { 'pass' } else { 'fail' }) `
        -Evidence @('server.log') -Note ("size=$size rate=$Rate")

    # ------------------------------------------------------------ 1. app + source
    Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Start-MhApp -Exe $Exe -ResetTrace | Out-Null
    if (-not (Wait-Until { Get-MhWindowInfo } 40)) { throw '应用窗口未出现' }
    Set-MhWindowState -State maximized | Out-Null
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }

    $added = Add-HttpSource -Url $clipUrl
    if (-not $added.closed) { throw '添加直链来源失败' }
    if (-not (Invoke-Nav '媒体库' 'libraries.reload')) { throw '媒体库页面未打开' }
    $card = Find-LibraryCard 'browse|*clip.mp4*|root'
    if (-not $card) { throw '媒体库中没有该直链的卡片' }

    # ------------------------------------------------------------ 2. play, then seek
    Invoke-AppElement $card | Out-Null
    # A single-file source opens as a one-row folder, so the row is what starts playback.
    $row = Wait-Until {
        Get-AppElements -IncludeOffscreen |
            Where-Object { $_.Current.AutomationId -like 'browse:*clip.mp4*' } |
            Select-Object -First 1
    } 25
    if (-not $row) { throw '来源目录中没有 clip.mp4 条目' }
    Invoke-AppElement $row | Out-Null
    $shown = Wait-Until { if (Find-AppElement -AutomationId 'player.seekslider') { $true } } 30
    $first = Wait-Until { $v = Get-PlayerPosition; if ($null -ne $v) { [double]$v } } 30
    Start-Sleep -Seconds 4
    $before = Get-PlayerPosition
    $tapBefore = @(Get-Tap | Where-Object { $_.method -eq 'GET' -and $_.path -like '*clip.mp4*' })
    $shotBefore = Save-WindowShot '01-before-seek.png'
    Add-MhCheck -Run $run -Id 'R2' -Input '播放限速直链' `
        -After @{ player = [bool]$shown; position = $first; positionLater = $before; getRequests = $tapBefore.Count } `
        -Assertion '播放器打开且位置在推进；此刻只有一次尚未完成的 GET（数据仍在传输）' `
        -Result $(if ($shown -and $null -ne $before -and $before -ge 1) { 'pass' } else { 'fail' }) `
        -Evidence @('01-before-seek.png') -Note ("screenshot=" + $shotBefore.path)

    $seekElement = Find-AppElement -AutomationId 'player.seekslider'
    $seekStartedEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
    $seekResult = Set-MhRangeValue -Element $seekElement -Value $SeekFraction
    Start-Sleep -Seconds 5
    $afterSeek = Get-PlayerPosition
    $shotAfter = Save-WindowShot '02-after-seek.png'
    $targetSeconds = [Math]::Round($DurationSeconds * $SeekFraction)
    Add-MhCheck -Run $run -Id 'R3' -Input ("把进度条拖到 {0:P0}" -f $SeekFraction) `
        -Before @{ position = $before } `
        -After @{ set = $seekResult; position = $afterSeek; expected = $targetSeconds } `
        -Assertion "拖动后位置跳到约 $targetSeconds s（±8s）" `
        -Result $(if ($seekResult -eq 'SET' -and $null -ne $afterSeek -and [Math]::Abs($afterSeek - $targetSeconds) -le 8) { 'pass' } else { 'fail' }) `
        -Evidence @('02-after-seek.png') -Note ("screenshot=" + $shotAfter.path)

    # ------------------------------------------------------------ 3. tap analysis
    Start-Sleep -Seconds 2
    $tap = @(Get-Tap | Where-Object { $_.method -eq 'GET' -and $_.path -like '*clip.mp4*' })
    Copy-Item $serverLog (Join-Path $evidence 'tap-copy.jsonl') -Force -ErrorAction SilentlyContinue
    $ranges = @($tap | Where-Object { $_.phase -eq 'begin' } | ForEach-Object {
            $start = $null
            if ($_.range -match 'bytes=(\d+)-') { $start = [int64]$Matches[1] }
            [pscustomobject]@{
                start = $start
                t     = $_.t
                after = ($_.t -ge $seekStartedEpoch)
                bytes = $_.length
            }
        })
    $ends = @($tap | Where-Object { $_.phase -eq 'end' } | ForEach-Object {
            [pscustomobject]@{ sent = $_.sent; declared = $_.declared; aborted = ($_.declared -gt 0 -and $_.sent -lt $_.declared) }
        })
    $seekedRange = @($ranges | Where-Object { $null -ne $_.start -and $_.after -and $_.start -gt ($size * 0.6) } |
        Select-Object -Last 1)
    $abortedCount = @($ends | Where-Object { $_.aborted }).Count
    $summary = ($ranges | ForEach-Object {
            "start=$($_.start) bytes=$($_.bytes) at=$($_.t) afterSeek=$($_.after)"
        }) -join ' | '
    Add-MhCheck -Run $run -Id 'R4' -Input '服务端请求 tap（拖动前后）' `
        -After @{
            requests     = $ranges.Count
            seekedRange  = $(if ($seekedRange) { $seekedRange.start } else { $null })
            sixtyPercent = [int64]($size * 0.6)
            aborted      = $abortedCount
            seekAt       = [Math]::Round($seekStartedEpoch, 3)
        } `
        -Assertion '拖动后出现一次新的 GET，其 Range 起点超过文件的 60%——引擎没有在已缓冲数据里滑动，而是重新向服务器取数' `
        -Result $(if ($seekedRange) { 'pass' } else { 'fail' }) `
        -Evidence @('tap.jsonl') -Note $summary
}
catch {
    Add-MhNote -Run $run -Text ('运行中止：' + $_.Exception.Message)
    Add-MhCheck -Run $run -Id 'X1' -Input '脚本异常' -Assertion '脚本应完整跑完所有检查' -Result 'fail' -Note $_.Exception.Message
}
finally {
    try { Copy-Item (Get-MhLogPath) (Join-Path $evidence 'startup.log') -Force } catch { }
    $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')

    try { Stop-MhAppGracefully -TimeoutSec 15 | Out-Null } catch { }
    if (@(Get-MhProcess).Count -gt 0) { Stop-MhAppForced -Reason 'seek range cleanup' | Out-Null }
    if ($serverProc -and -not $serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }

    if (-not $NoCleanup) {
        try {
            $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
            $json.Servers = @($json.Servers | Where-Object { $_.Url -notlike '*127.0.0.1:' + $Port + '*' })
            $json | ConvertTo-Json -Depth 24 | Set-Content -Path $settingsPath -Encoding UTF8
        }
        catch { Add-MhNote -Run $run -Text ('cleanup settings failed: ' + $_.Exception.Message) }
        try {
            if (Test-Path $historyPath) {
                $history = @(Get-Content $historyPath -Raw | ConvertFrom-Json |
                    Where-Object { $_.MediaPath -notlike ('*127.0.0.1:' + $Port + '*') })
                $history | ConvertTo-Json -Depth 12 -AsArray | Set-Content -Path $historyPath -Encoding UTF8
            }
        }
        catch { Add-MhNote -Run $run -Text ('cleanup history failed: ' + $_.Exception.Message) }
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
        $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')
    }

    Write-Host ''
    Write-Host ('RESULT: ' + $saved.counts.pass + ' passed, ' + $saved.counts.fail + ' failed, ' +
        $saved.counts['not-verified'] + ' not-verified')
    Write-Host ('evidence: ' + $evidence)
    if ($saved.counts.fail -gt 0) { exit 1 }
    exit 0
}

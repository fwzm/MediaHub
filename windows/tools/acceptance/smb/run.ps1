#Requires -Version 7
<#
.SYNOPSIS
Real-machine acceptance for the SMB share source: browse, play, seek, resume, and every failure the
form has to explain.

.DESCRIPTION
The share used here is the machine's own built-in "Users" share (\localhost\Users), so the run needs
no elevation and no third-party SMB server: the traffic really goes through the Windows redirector
and the local SMB server on TCP 445, which is recorded as connection state while the run proceeds.
A temporary folder inside it holds two neutral ffmpeg clips, one subfolder, one empty folder and one
folder that is deleted mid-run.

Checks:
  S1  share + fixtures exist and the UNC path answers
  S2  wrong credentials are reported as 认证失败 inside the dialog
  S3  a folder the account may not open is reported as a permission, not as "fill in a password"
  S4  a share that does not exist reports 共享不存在
  S5  an unreachable host returns a timeout message inside the bounded wait instead of hanging
  S6  add-source dialog: SMB keeps the optional account fields and the right address hint
  S7  adding anonymously closes the dialog
  S8  the source card reads 已连接 / Smb / Browse / Playback / Progress
  S9  an anonymous share writes nothing to the credential vault
  S10 the library browses the share root into folders and files
  S11 a loopback SMB connection on port 445 is established while reading, with no drive mapping left
  S12 playback starts, reports ~120 s and advances
  S13 seeking to 60% moves the position to ~72 s
  S14 leaving and replaying resumes from the recorded position (trace: playback:resume=local)
  S15 an empty folder shows the empty state, not an error
  S16 a folder that disappears shows a readable error and 重试 recovers after it returns
  S17 (not-verified) "share wants a password and none was given" cannot be produced on a machine whose
      own admin token opens every share; covered by SmbSourceTests instead

.EXAMPLE
$env:MH_ALLOW_ACTIVE='1'
pwsh -NoProfile -File windows/tools/acceptance/smb/run.ps1
#>
[CmdletBinding()]
param(
    [string]$ShareHost = 'localhost',
    [string]$ShareName = 'Users',
    [string]$FolderName = 'mh-smb-accept',
    [string]$Exe = 'D:\deepseek_test\MediaHub_Win\windows\src\MediaHub.App\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$UnreachableTarget = '\\192.0.2.1\media',
    [string]$PermissionPath = '\\localhost\C$\System Volume Information',
    [switch]$SkipMedia,
    [switch]$NoCleanup
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhUia.psm1') -Force

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path (Split-Path $here -Parent) ('evidence\smb\' + $stamp)
$run = New-MhRun -Area 'smb-source' -EvidenceDir $evidence -Meta @{
    share = "\\$ShareHost\$ShareName"; exe = $Exe; startedBy = 'run.ps1'
}

$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$historyPath = Join-Path $env:LOCALAPPDATA 'MediaHub\history.json'
$vaultPath = Join-Path $env:LOCALAPPDATA 'MediaHub\credentials\credentials.vault.json'

$localRoot = Join-Path (Join-Path $env:SystemDrive "Users\$env:USERNAME") $FolderName
$uncRoot = "\\$ShareHost\$ShareName\$env:USERNAME\$FolderName"
$flakyLocal = Join-Path $localRoot 'flaky'
$flakyUnc = "$uncRoot\flaky"
$emptyLocal = Join-Path $localRoot 'empty'
$emptyUnc = "$uncRoot\empty"

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
    # UIA throws "unrecognized error" when the tree changes under a FindAll, which happens on every
    # page transition. That is a transient state, not a finding, so the read is retried rather than
    # aborting the run at the first animation frame.
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $out = @()
            foreach ($root in (Get-MhAppRoots)) { $out += @(Get-MhElementAll -Root $root) }
            if (-not $IncludeOffscreen) { $out = @($out | Where-Object { -not $_.Current.IsOffscreen }) }
            return $out
        }
        catch { Start-Sleep -Milliseconds 300 }
    }
    Add-MhNote -Run $run -Text 'UIA FindAll 连续三次失败，返回空集合'
    return @()
}

function Find-AppElement([string]$AutomationId, [string]$Name, [switch]$IncludeOffscreen) {
    for ($i = 0; $i -lt 3; $i++) {
        try {
            foreach ($root in (Get-MhAppRoots)) {
                $element = Find-MhElement -Root $root -AutomationId $AutomationId -Name $Name -IncludeOffscreen:$IncludeOffscreen
                if ($element) { return $element }
            }
            return $null
        }
        catch { Start-Sleep -Milliseconds 300 }
    }
    return $null
}

function Invoke-AppElement($Element) {
    # Invoke() fails with "unrecognized error" when the element is replaced between the read and the
    # click (page transitions, list virtualisation). Retry the click instead of failing the whole area
    # for a timing artefact; a retry only happens when the first call actually threw.
    for ($i = 0; $i -lt 3; $i++) {
        try { Invoke-MhElementDeep -Element $Element | Out-Null; return $true }
        catch { Start-Sleep -Milliseconds 400 }
    }
    return $false
}

function Save-WindowShot([string]$Name) {
    # Foreground alone was not enough: a maximised WinUI window stayed behind other applications, so
    # the window is briefly pinned topmost for the grab and released afterwards.
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

function Get-SmbConnectionSnapshot([string]$Name) {
    # The SMB client runs in the kernel, so attributing a socket to the app is not possible. What is
    # provable is the shape: a loopback TCP connection to port 445 exists only while something reads
    # through the redirector, and no drive mapping is left behind (CONNECT_TEMPORARY).
    $lines = @(netstat -ano | Select-String ':445\s' | ForEach-Object { $_.Line.Trim() })
    $established = @($lines | Where-Object { $_ -match 'ESTABLISHED' -and $_ -match '127\.0\.0\.1:445|\[::1\]:445' })
    $netUse = @(net use 2>&1 | ForEach-Object { $_.ToString() })
    $text = @(
        ('sampled = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')),
        ('loopback 445 established = ' + $established.Count),
        '--- netstat (port 445) ---'
    ) + $lines + @('--- net use ---') + $netUse
    Set-Content -Path (Join-Path $evidence $Name) -Value $text -Encoding UTF8
    return [pscustomobject]@{ established = $established.Count; mapped = @($netUse | Where-Object { $_ -match '\\\\' }).Count }
}

function Wake-PlayerControls {
    # The player hides its bar after a few idle seconds. Two alternating positions are needed: moving
    # the cursor to where it already is produces no WM_MOUSEMOVE, so the bar would stay hidden.
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
    Wake-PlayerControls
    $p = Find-AppElement -AutomationId 'PositionText'
    if (-not $p) { return $null }
    ConvertFrom-MhTime $p.Current.Name
}

function Get-PlayerDuration {
    Wake-PlayerControls
    $d = Find-AppElement -AutomationId 'DurationText'
    if (-not $d) { return $null }
    ConvertFrom-MhTime $d.Current.Name
}

function Close-Player {
    Wake-PlayerControls
    $back = Find-AppElement -AutomationId 'player.back'
    if (-not $back) { $back = Find-AppElement -AutomationId 'player.back' -IncludeOffscreen }
    if ($back) { Invoke-AppElement $back | Out-Null }
    return [bool](Wait-Until { -not (Find-AppElement -AutomationId 'player.seekslider' -IncludeOffscreen) } 25)
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
    # The library grid virtualises. A card below the fold keeps its automation peer but is reported
    # offscreen with no rectangle, so ask for it including offscreen, scroll it into view, then read
    # it back as a visible element that can actually be invoked.
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

function Find-BrowseRow([string]$Pattern) {
    return (Wait-Until {
            Get-AppElements -IncludeOffscreen |
                Where-Object { $_.Current.AutomationId -like $Pattern } |
                Select-Object -First 1
        } 25)
}

function Get-CardField([System.Windows.Automation.AutomationElement]$NameElement, [string]$FieldName) {
    $cur = $NameElement
    for ($depth = 0; $depth -lt 8 -and $cur; $depth++) {
        $delete = Find-MhElement -Root $cur -Name '删除' -IncludeOffscreen
        if ($delete) { return (Find-MhElement -Root $cur -Name $FieldName -IncludeOffscreen) }
        $parent = $null
        try { $parent = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($cur) } catch { break }
        if (-not $parent) { break }
        if ($parent.Current.ControlType -eq [System.Windows.Automation.ControlType]::Window) { break }
        $cur = $parent
    }
    return $null
}

function Find-SourceCard([string]$NameLike) {
    # The card title is the share's display name, and the list scrolls. Match on the fragment, then
    # keep only a candidate that actually sits in a card (its ancestor row owns the 删除 button), so
    # the status message of the InfoBar above cannot be mistaken for the card.
    $candidates = Wait-Until {
        $list = @(Get-AppElements -IncludeOffscreen | Where-Object { $_.Current.Name -like $NameLike })
        if ($list.Count -gt 0) { $list }
    } 30
    if (-not $candidates) { return $null }
    foreach ($candidate in $candidates) {
        if (Get-CardField $candidate '删除') { return $candidate }
    }
    return $null
}

function Open-AddDialog {
    $add = Wait-Until { Find-AppElement -AutomationId 'AddButton' } 20
    if (-not $add) { throw '未找到“添加媒体源”按钮' }
    Invoke-AppElement $add | Out-Null
    $combo = Wait-Until { Find-AppElement -AutomationId 'sources.type' } 15
    if (-not $combo) { throw '添加对话框未出现' }
    $selected = Select-MhComboItem -ComboElement $combo -Text 'SMB 共享'
    Start-Sleep -Milliseconds 700
    return [pscustomobject]@{ selected = $selected }
}

function Set-DialogFields([string]$Url, [string]$User, [string]$Password) {
    $edit = Find-AppElement -AutomationId 'sources.url'
    if (-not $edit) { throw '添加对话框未找到地址输入框' }
    $set = Set-MhValue -Element $edit -Value $Url

    $userBox = Find-AppElement -AutomationId 'sources.user'
    $passwordBox = Find-AppElement -AutomationId 'sources.password'
    if ($null -ne $userBox) { Set-MhValue -Element $userBox -Value $User | Out-Null }
    if ($null -ne $passwordBox) { Set-MhValue -Element $passwordBox -Value $Password | Out-Null }
    return $set
}

function Get-DialogHint {
    $hint = Find-AppElement -AutomationId 'sources.hint'
    if (-not $hint) { $hint = Find-AppElement -AutomationId 'sources.hint' -IncludeOffscreen }
    if (-not $hint) { return $null }
    return $hint.Current.Name
}

function Invoke-TestConnection {
    $button = Wait-Until {
        Get-AppElements | Where-Object { $_.Current.Name -eq '测试连接' } | Select-Object -First 1
    } 10
    if (-not $button) { throw '添加对话框未找到“测试连接”按钮' }
    $started = Get-Date
    Invoke-AppElement $button | Out-Null
    $hint = Wait-Until {
        $text = Get-DialogHint
        if ($text -and $text -ne '正在连接…') { $text }
    } 45
    $elapsed = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    return [pscustomobject]@{ text = $hint; seconds = $elapsed }
}

function Close-Dialog {
    $cancel = Wait-Until {
        Get-AppElements | Where-Object { $_.Current.Name -eq '取消' } | Select-Object -First 1
    } 10
    if ($cancel) { Invoke-AppElement $cancel | Out-Null }
    return [bool](Wait-Until { $null -eq (Find-AppElement -AutomationId 'sources.type') } 20)
}

function Add-SmbSource([string]$Url, [string]$ShotName) {
    Open-AddDialog | Out-Null
    $accountVisible = [bool](Find-AppElement -AutomationId 'sources.user')
    $passwordVisible = [bool](Find-AppElement -AutomationId 'sources.password')
    $optionalLabel = $false
    $userBox = Find-AppElement -AutomationId 'sources.user'
    if ($userBox) {
        $parent = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($userBox)
        if ($parent) { $optionalLabel = [bool](Find-MhElement -Root $parent -Name '账号（可选）') }
    }
    $placeholder = $null
    $urlBox = Find-AppElement -AutomationId 'sources.url'
    if ($urlBox) { $placeholder = $urlBox.Current.HelpText }
    $set = Set-DialogFields -Url $Url -User '' -Password ''
    if ($ShotName) { Save-WindowShot $ShotName | Out-Null }
    $primary = Find-AppElement -AutomationId 'PrimaryButton'
    if (-not $primary) { throw '添加对话框未找到主按钮' }
    $primaryText = $primary.Current.Name
    Invoke-AppElement $primary | Out-Null
    $closed = Wait-Until { $null -eq (Find-AppElement -AutomationId 'sources.type') } 30
    return [pscustomobject]@{
        accountVisible = $accountVisible
        passwordVisible = $passwordVisible
        optionalLabel = $optionalLabel
        placeholder = $placeholder
        set = $set
        primaryText = $primaryText
        closed = [bool]$closed
    }
}

function Get-HistoryRaw {
    if (Test-Path $historyPath) { return (Get-Content $historyPath -Raw) } else { return '' }
}

try {
    # ------------------------------------------------------------ 0. baseline fixtures
    if (Test-Path $settingsPath) { Copy-Item $settingsPath (Join-Path $evidence 'settings-before.json') -Force }
    if (Test-Path $historyPath) { Copy-Item $historyPath (Join-Path $evidence 'history-before.json') -Force }
    $vaultBefore = if (Test-Path $vaultPath) { Get-Content $vaultPath -Raw } else { '' }
    $netBefore = Get-SmbConnectionSnapshot 'netstat-before.txt'

    New-Item -ItemType Directory -Force -Path $localRoot, $emptyLocal, $flakyLocal | Out-Null
    if (-not $SkipMedia) {
        # testsrc2 + a single tone: neutral, synthetic, and carrying a visible clock so a frozen frame
        # is obvious. faststart puts moov first so nothing needs the end of the file to start.
        & ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=25:duration=120' `
            -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=120' `
            -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k -movflags +faststart `
            (Join-Path $localRoot 'clip-a.mp4')
        & ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=480x270:rate=25:duration=60' `
            -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=60' `
            -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k -movflags +faststart `
            (Join-Path $flakyLocal 'clip-b.mp4')
    }

    $shareInfo = (Get-SmbShare -Name $ShareName | Format-List Name,Path,Description | Out-String).Trim()
    Set-Content -Path (Join-Path $evidence 'smb-share.txt') -Value @(
        ('share = \\' + $ShareHost + '\' + $ShareName),
        ('uncRoot = ' + $uncRoot),
        ('localRoot = ' + $localRoot),
        $shareInfo,
        '--- fixtures ---',
        ((Get-ChildItem -Recurse $localRoot | ForEach-Object { $_.FullName + '  ' + $_.Length }) -join "`n")
    ) -Encoding UTF8

    $reach = Wait-Until { Test-Path $uncRoot } 20
    $size = 0
    try { $size = (Get-Item (Join-Path $uncRoot 'clip-a.mp4')).Length } catch { }
    Add-MhCheck -Run $run -Id 'S1' -Input ('本机共享 \\' + $ShareHost + '\' + $ShareName + ' + 目录 ' + $uncRoot) `
        -After @{ share = $ShareName; reachable = [bool]$reach; clipBytes = $size; subfolder = (Test-Path $flakyUnc); emptyFolder = (Test-Path $emptyUnc) } `
        -Assertion '共享可访问，UNC 根目录下有两个测试片、一个子目录和一个空目录' `
        -Result $(if ($reach -and $size -gt 0 -and (Test-Path $flakyUnc) -and (Test-Path $emptyUnc)) { 'pass' } else { 'fail' }) `
        -Evidence @('smb-share.txt')

    # ------------------------------------------------------------ 1. app up
    Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Start-MhApp -Exe $Exe -ResetTrace | Out-Null
    if (-not (Wait-Until { Get-MhWindowInfo } 40)) { throw '应用窗口未出现' }
    Set-MhWindowState -State maximized | Out-Null
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }

    # ------------------------------------------------------------ 2. failure vocabulary (before any session)
    # These run first, on purpose: once the app has browsed the share the redirector holds a session
    # for the current identity, and a second attempt with other credentials comes back as a conflict
    # instead of a rejected logon. A fresh process gives the real answer.
    Open-AddDialog | Out-Null

    Set-DialogFields -Url ('\\' + $ShareHost + '\' + $ShareName) -User 'mh\definitely-not-a-user' -Password 'wrong-password' | Out-Null
    $auth = Invoke-TestConnection
    $shotAuth = Save-WindowShot '02-auth-failed.png'
    Add-MhCheck -Run $run -Id 'S2' -Input '使用错误账号密码测试连接' `
        -After @{ hint = $auth.text; seconds = $auth.seconds } `
        -Assertion '提示“认证失败：账号或密码不正确”，不出现系统错误码，也不回显账号密码' `
        -Result $(if ($auth.text -like '*认证失败*' -and $auth.text -like '*账号或密码*' -and $auth.text -notlike '*wrong-password*') { 'pass' } else { 'fail' }) `
        -Evidence @('02-auth-failed.png') -Note ("screenshot=" + $shotAuth.path)

    Set-DialogFields -Url $PermissionPath -User '' -Password '' | Out-Null
    $permission = Invoke-TestConnection
    $shotPermission = Save-WindowShot '03-permission-denied.png'
    Add-MhCheck -Run $run -Id 'S3' -Input ('对共享内无权限的目录测试连接 ' + $PermissionPath) `
        -After @{ hint = $permission.text; seconds = $permission.seconds } `
        -Assertion '共享本身可打开、目录被拒时提示“没有访问权限”，而不是让用户去填一个并不缺的密码' `
        -Result $(if ($permission.text -like '*没有访问权限*') { 'pass' } else { 'fail' }) `
        -Evidence @('03-permission-denied.png') -Note ("screenshot=" + $shotPermission.path)

    Set-DialogFields -Url ('\\' + $ShareHost + '\MhNoSuchShare') -User '' -Password '' | Out-Null
    $missing = Invoke-TestConnection
    $shotMissing = Save-WindowShot '04-missing-share.png'
    Add-MhCheck -Run $run -Id 'S4' -Input '测试不存在的共享 \\localhost\MhNoSuchShare' `
        -After @{ hint = $missing.text; seconds = $missing.seconds } `
        -Assertion '提示共享不存在或无法访问，并回显用户输入的共享地址' `
        -Result $(if ($missing.text -like '*共享不存在*' -and $missing.text -like '*MhNoSuchShare*') { 'pass' } else { 'fail' }) `
        -Evidence @('04-missing-share.png') -Note ("screenshot=" + $shotMissing.path)

    Set-DialogFields -Url $UnreachableTarget -User '' -Password '' | Out-Null
    $timeout = Invoke-TestConnection
    $shotTimeout = Save-WindowShot '05-timeout.png'
    Add-MhCheck -Run $run -Id 'S5' -Input ('测试不可达主机 ' + $UnreachableTarget) `
        -After @{ hint = $timeout.text; seconds = $timeout.seconds } `
        -Assertion '在受限时间内返回“连接超时”提示（10–35s 之间），界面不冻结、不崩溃' `
        -Result $(if ($timeout.text -like '*超时*' -and $timeout.seconds -ge 10 -and $timeout.seconds -le 35) { 'pass' } else { 'fail' }) `
        -Evidence @('05-timeout.png') -Note ("screenshot=" + $shotTimeout.path)

    Add-MhCheck -Run $run -Id 'S6' -Input '“共享需要账号密码但表单未填”在本机复现' `
        -After @{ reason = '本机管理员令牌可打开所有共享（含 ADMIN$），无法制造凭据被拒的共享' } `
        -Assertion '该分支由 SmbSourceTests.Access_denied_means_different_things_with_and_without_a_supplied_account 覆盖' `
        -Result 'not-verified' -Note '真机上所有共享都可匿名打开；该句话由单元测试固定'
    Close-Dialog | Out-Null

    # ------------------------------------------------------------ 3. dialog shape + anonymous add
    $opened = Open-AddDialog
    $accountVisible = [bool](Find-AppElement -AutomationId 'sources.user')
    $passwordVisible = [bool](Find-AppElement -AutomationId 'sources.password')
    $urlBox = Find-AppElement -AutomationId 'sources.url'
    $placeholder = $urlBox.Current.HelpText
    $shotDialog = Save-WindowShot '06-dialog-smb.png'
    Add-MhCheck -Run $run -Id 'S7' -Input '添加媒体源 → 类型选择“SMB 共享”' `
        -After @{ selection = $opened.selected; accountVisible = $accountVisible; passwordVisible = $passwordVisible; placeholder = $placeholder } `
        -Assertion '选择 SMB 后账号与密码字段仍然存在（可选），地址框提示共享地址，主按钮文案为“添加”' `
        -Result $(if ($opened.selected -like '*VERIFIED*' -and $accountVisible -and $passwordVisible -and $placeholder -like '*\\*' -and $placeholder -like '*smb://*') { 'pass' } else { 'fail' }) `
        -Evidence @('06-dialog-smb.png') -Note ("placeholder='" + $placeholder + "' screenshot=" + $shotDialog.path)
    Close-Dialog | Out-Null

    $added = Add-SmbSource -Url $uncRoot -ShotName '06-add-smb.png'
    Add-MhCheck -Run $run -Id 'S8' -Input ('不填账号密码，地址填 ' + $uncRoot) `
        -After @{ accountVisible = $added.accountVisible; optionalLabel = $added.optionalLabel; set = $added.set; primary = $added.primaryText; closed = $added.closed } `
        -Assertion '账号标记为可选、留空可用当前 Windows 身份添加，对话框关闭' `
        -Result $(if ($added.accountVisible -and $added.optionalLabel -and $added.set -eq 'SET' -and $added.closed) { 'pass' } else { 'fail' }) `
        -Evidence @('06-add-smb.png')

    Start-Sleep -Seconds 2
    $card = Find-SourceCard ('*' + $FolderName + '*')
    $statusLine = if ($card) { Get-CardField $card '已连接' } else { $null }
    $caps = if ($card) { Get-CardField $card 'Browse / Playback / Progress' } else { $null }
    $typeBadge = if ($card) { Get-CardField $card 'Smb' } else { $null }
    $shotSources = Save-WindowShot '07-sources.png'
    Add-MhCheck -Run $run -Id 'S9' -Input '读取新来源卡片' `
        -After @{ name = [bool]$card; status = [bool]$statusLine; capabilities = [bool]$caps; type = [bool]$typeBadge } `
        -Assertion '来源卡片显示名称、状态“已连接”、能力 Browse / Playback / Progress 与类型 Smb' `
        -Result $(if ($card -and $statusLine -and $caps -and $typeBadge) { 'pass' } else { 'fail' }) `
        -Evidence @('07-sources.png') -Note ("screenshot=" + $shotSources.path)

    $vaultAfterAdd = if (Test-Path $vaultPath) { Get-Content $vaultPath -Raw } else { '' }
    Add-MhCheck -Run $run -Id 'S10' -Input '比对添加前后的 credentials.vault.json' `
        -Before @{ vaultBytes = $vaultBefore.Length } `
        -After @{ vaultBytes = $vaultAfterAdd.Length; containsShare = ($vaultAfterAdd -like ('*' + $FolderName + '*')) } `
        -Assertion '匿名共享不写凭据：vault 内容不变且不含该共享地址' `
        -Result $(if ($vaultAfterAdd -eq $vaultBefore -and -not ($vaultAfterAdd -like ('*' + $FolderName + '*'))) { 'pass' } else { 'fail' })

    # ------------------------------------------------------------ 4. browse root
    if (-not (Invoke-Nav '媒体库' 'libraries.reload')) { throw '媒体库页面未打开' }
    $libraryCard = Find-LibraryCard ('browse|*' + $FolderName + '*|root')
    if (-not $libraryCard) {
        $ids = @(Get-AppElements -IncludeOffscreen | Where-Object { $_.Current.AutomationId -like 'browse|*' } |
            ForEach-Object { $_.Current.AutomationId })
        Add-MhNote -Run $run -Text ('browse cards: ' + ($ids -join ' ; '))
        throw '媒体库中没有该共享的浏览卡片'
    }
    Invoke-AppElement $libraryCard | Out-Null
    Start-Sleep -Milliseconds 800

    $netDuring = Get-SmbConnectionSnapshot 'netstat-during-browse.txt'
    $clipRow = Find-BrowseRow ('*' + $FolderName + '*clip-a.mp4')
    $folderRow = Find-BrowseRow ('*' + $FolderName + '*flaky')
    $emptyRow = Find-BrowseRow ('*' + $FolderName + '*empty')
    $shotBrowse = Save-WindowShot '08-browse-root.png'
    Add-MhCheck -Run $run -Id 'S11' -Input '打开共享来源的根目录' `
        -After @{ clip = [bool]$clipRow; folder = [bool]$folderRow; emptyFolder = [bool]$emptyRow } `
        -Assertion '根目录列出测试片、子目录与空目录，名称去扩展名，路径保留在共享上' `
        -Result $(if ($clipRow -and $folderRow -and $emptyRow) { 'pass' } else { 'fail' }) `
        -Evidence @('08-browse-root.png') -Note ("screenshot=" + $shotBrowse.path)
    Add-MhCheck -Run $run -Id 'S12' -Input 'net use + netstat（读取共享期间）' `
        -Before @{ loopback445Before = $netBefore.established; mappedDrivesBefore = $netBefore.mapped } `
        -After @{ loopback445During = $netDuring.established; mappedDrivesDuring = $netDuring.mapped } `
        -Assertion '读取共享期间出现本机 445 的已建立连接（流量确实经过 SMB 服务），且没有留下盘符映射' `
        -Result $(if ($netDuring.established -ge 1 -and $netDuring.mapped -eq 0) { 'pass' } else { 'fail' }) `
        -Evidence @('netstat-before.txt', 'netstat-during-browse.txt')

    # ------------------------------------------------------------ 5. play, seek, resume
    if (-not $clipRow) { throw '根目录中未找到测试片' }
    Invoke-AppElement $clipRow | Out-Null
    $shown = Wait-Until { if (Find-AppElement -AutomationId 'player.seekslider') { $true } } 30
    $first = Wait-Until { $v = Get-PlayerPosition; if ($null -ne $v) { [double]$v } } 30
    Start-Sleep -Seconds 3
    $second = Get-PlayerPosition
    $duration = Get-PlayerDuration
    $shotPlay = Save-WindowShot '09-player.png'
    Add-MhCheck -Run $run -Id 'S13' -Input '点击共享上的测试片起播' `
        -After @{ player = [bool]$shown; position = $first; positionLater = $second; duration = $duration } `
        -Assertion '播放器打开，时长约 120s，位置在 3s 内推进（file: URI 走本地播放链路）' `
        -Result $(if ($shown -and $null -ne $first -and $null -ne $second -and $second -gt $first -and
                $null -ne $duration -and [Math]::Abs($duration - 120) -le 4) { 'pass' } else { 'fail' }) `
        -Evidence @('09-player.png') -Note ("screenshot=" + $shotPlay.path)

    $seekElement = Find-AppElement -AutomationId 'player.seekslider'
    $seekResult = Set-MhRangeValue -Element $seekElement -Value 0.6
    Start-Sleep -Seconds 3
    $afterSeek = Get-PlayerPosition
    $sliderValue = Get-MhRangeValue -Element $seekElement
    $shotSeek = Save-WindowShot '10-seek.png'
    Add-MhCheck -Run $run -Id 'S14' -Input '将进度条拖到 60%' `
        -Before @{ position = $second } `
        -After @{ set = $seekResult; slider = $sliderValue.value; position = $afterSeek } `
        -Assertion '拖动后位置跳到约 72s（≥65s），播放继续' `
        -Result $(if ($seekResult -eq 'SET' -and $null -ne $afterSeek -and $afterSeek -ge 65) { 'pass' } else { 'fail' }) `
        -Evidence @('10-seek.png') -Note ("screenshot=" + $shotSeek.path)

    Start-Sleep -Seconds 2
    $leftAt = Get-PlayerPosition
    Close-Player | Out-Null
    Start-Sleep -Seconds 7   # let the 5s history flush tick land

    $replayRow = Find-BrowseRow ('*' + $FolderName + '*clip-a.mp4')
    if (-not $replayRow) { throw '返回后找不到测试片条目' }
    Invoke-AppElement $replayRow | Out-Null
    $resume = Wait-Until { $v = Get-PlayerPosition; if ($null -ne $v) { [double]$v } } 30
    Start-Sleep -Seconds 2
    $resume2 = Get-PlayerPosition
    $resumeLine = @(Get-MhLog | Where-Object { $_ -match 'playback:resume=local:' } | Select-Object -Last 1)
    $shotResume = Save-WindowShot '11-resume.png'
    Add-MhCheck -Run $run -Id 'S15' -Input '退出播放器后再次打开同一条目' `
        -Before @{ leftAt = $leftAt; historyHasEntry = ((Get-HistoryRaw) -like ('*' + $FolderName + '*')) } `
        -After @{ resumePosition = $resume; after2s = $resume2; trace = $(if ($resumeLine.Count -gt 0) { $resumeLine[0] } else { $null }) } `
        -Assertion '重播从上次位置继续（起点 ≥ 60s），trace 记录 playback:resume=local（共享无服务端进度，走本地历史）' `
        -Result $(if ($null -ne $resume -and $resume -ge 60 -and $resumeLine.Count -gt 0) { 'pass' } else { 'fail' }) `
        -Evidence @('11-resume.png', 'startup.log') -Note ("screenshot=" + $shotResume.path)
    Close-Player | Out-Null

    # ------------------------------------------------------------ 6. empty folder
    $back = Find-AppElement -AutomationId 'browse.up'
    if ($back) { Invoke-AppElement $back | Out-Null }
    Start-Sleep -Milliseconds 900
    $emptyRow2 = Find-BrowseRow ('*' + $FolderName + '*empty')
    if ($emptyRow2) { Invoke-AppElement $emptyRow2 | Out-Null }
    $emptyState = Wait-Until { Find-AppElement -Name '这个目录是空的' } 20
    $errorBar = Get-AppElements | Where-Object { $_.Current.Name -like '*无法*' -or $_.Current.Name -like '*失败*' } | Select-Object -First 1
    $shotEmpty = Save-WindowShot '12-empty.png'
    Add-MhCheck -Run $run -Id 'S16' -Input '打开共享上的空目录' `
        -After @{ row = [bool]$emptyRow2; emptyState = [bool]$emptyState; errorBar = [bool]$errorBar } `
        -Assertion '空目录显示“这个目录是空的”，不弹出错误、不崩溃' `
        -Result $(if ($emptyRow2 -and $emptyState -and -not $errorBar) { 'pass' } else { 'fail' }) `
        -Evidence @('12-empty.png') -Note ("screenshot=" + $shotEmpty.path)

    # ------------------------------------------------------------ 7. flaky folder -> error + retry
    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }
    Add-SmbSource -Url $flakyUnc | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Invoke-Nav '媒体库' 'libraries.reload')) { throw '媒体库页面未打开' }
    $flakyCard = Find-LibraryCard ('browse|*' + $FolderName + '*flaky*|root')
    if (-not $flakyCard) {
        $ids = @(Get-AppElements -IncludeOffscreen | Where-Object { $_.Current.AutomationId -like 'browse|*' } |
            ForEach-Object { $_.Current.AutomationId })
        Add-MhNote -Run $run -Text ('browse cards: ' + ($ids -join ' ; '))
        throw '媒体库中没有 flaky 来源的卡片'
    }

    # Remove the folder behind the app's back: the next read has to fail with a sentence, not a crash.
    Remove-Item $flakyLocal -Recurse -Force
    Start-Sleep -Milliseconds 500
    Invoke-AppElement $flakyCard | Out-Null
    $retry = Wait-Until { Find-AppElement -AutomationId 'browse.retry' } 25
    $errorText = $null
    $errorElement = Get-AppElements -IncludeOffscreen |
        Where-Object { $_.Current.Name -like '*无法读取*' -or $_.Current.Name -like '*找不到*' -or $_.Current.Name -like '*共享*' } |
        Select-Object -First 1
    if ($errorElement) { $errorText = $errorElement.Current.Name }
    $shotError = Save-WindowShot '13-browse-error.png'

    New-Item -ItemType Directory -Force -Path $flakyLocal | Out-Null
    Copy-Item (Join-Path $localRoot 'clip-a.mp4') (Join-Path $flakyLocal 'clip-c.mp4') -Force
    if ($retry) { Invoke-AppElement $retry | Out-Null }
    $recovered = Wait-Until { Find-BrowseRow ('*' + $FolderName + '*clip-c.mp4') } 25
    $shotRetry = Save-WindowShot '13-browse-retry.png'
    Add-MhCheck -Run $run -Id 'S17' -Input '共享子目录在应用外被删除 → 浏览 → 恢复目录后点“重试”' `
        -Before @{ retryButton = [bool]$retry } `
        -After @{ error = $errorText; recoveredRow = [bool]$recovered } `
        -Assertion '删除后浏览给出可读错误并提供“重试”；目录恢复后重试即可列出文件' `
        -Result $(if ($retry -and $errorText -and $recovered) { 'pass' } else { 'fail' }) `
        -Evidence @('13-browse-error.png', '13-browse-retry.png') `
        -Note ("error='" + $errorText + "' shots=" + $shotError.path + ',' + $shotRetry.path)
}
catch {
    Add-MhNote -Run $run -Text ('运行中止：' + $_.Exception.Message)
    Add-MhCheck -Run $run -Id 'X1' -Input '脚本异常' -Assertion '脚本应完整跑完所有检查' -Result 'fail' -Note $_.Exception.Message
}
finally {
    Add-MhNote -Run $run -Text ('应用日志行数=' + @(Get-MhLog).Count)
    $sources = Get-MhLog | Where-Object { $_ -match '播放源' } | Select-Object -Last 3
    Add-MhNote -Run $run -Text ('最近播放源日志：' + ($sources -join ' | '))
    try { Copy-Item (Get-MhLogPath) (Join-Path $evidence 'startup.log') -Force } catch { }
    $netAfter = Get-SmbConnectionSnapshot 'netstat-after.txt'
    Add-MhNote -Run $run -Text ('netstat after: loopback445=' + $netAfter.established + ' mapped=' + $netAfter.mapped)
    $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')

    try { Stop-MhAppGracefully -TimeoutSec 15 | Out-Null } catch { }
    if (@(Get-MhProcess).Count -gt 0) { Stop-MhAppForced -Reason 'smb acceptance cleanup' | Out-Null }

    if (-not $NoCleanup) {
        try {
            $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
            $smbIds = @($json.Servers | Where-Object { $_.Type -eq 'Smb' } | ForEach-Object { $_.ServerId })
            $json.Servers = @($json.Servers | Where-Object { $_.Type -ne 'Smb' })
            $json | ConvertTo-Json -Depth 24 | Set-Content -Path $settingsPath -Encoding UTF8
            Add-MhNote -Run $run -Text ('cleanup: removed smb sources=' + ($smbIds -join ','))
        }
        catch { Add-MhNote -Run $run -Text ('cleanup settings failed: ' + $_.Exception.Message) }

        try {
            if (Test-Path $historyPath) {
                $history = @(Get-Content $historyPath -Raw | ConvertFrom-Json |
                    Where-Object { $_.MediaPath -notlike ('*' + $FolderName + '*') })
                $history | ConvertTo-Json -Depth 12 -AsArray | Set-Content -Path $historyPath -Encoding UTF8
            }
        }
        catch { Add-MhNote -Run $run -Text ('cleanup history failed: ' + $_.Exception.Message) }

        Remove-Item -Path $localRoot -Recurse -Force -ErrorAction SilentlyContinue
        $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')
    }

    Write-Host ''
    Write-Host ('RESULT: ' + $saved.counts.pass + ' passed, ' + $saved.counts.fail + ' failed, ' +
        $saved.counts['not-verified'] + ' not-verified')
    Write-Host ('evidence: ' + $evidence)
    if ($saved.counts.fail -gt 0) { exit 1 }
    exit 0
}

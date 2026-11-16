#Requires -Version 7
<#
.SYNOPSIS
Real-machine acceptance for the OneDrive source, in the state the machine is actually in: no Entra
app registration, therefore no account.

.DESCRIPTION
This run does not pretend an account exists. It verifies the parts that can be decided without one -
the add-source dialog shape, the configuration-missing path, the absence of any credential on disk or
in the log, that the callback port really binds on this machine, and that the real Microsoft
authority and Graph endpoints are reachable and answer the way the error mapping expects. Every flow
that needs a signed-in account (browse, play, seek, resume, refresh after expiry) is recorded as
NOT VERIFIED together with exactly what is missing; it is never reported as passed.

Checks:
  O1  no oneDriveClientId in settings (the machine is genuinely unconfigured)
  O2  http://localhost:53682/ can be bound by an HttpListener (the loopback callback works here)
  O3  login.microsoftonline.com rejects an unregistered public client with invalid_client (real POST)
  O4  graph.microsoft.com/v1.0/me answers 401 without a token (the 401-refresh path has a real target)
  O5  dialog: OneDrive hides the address and the account fields, shows the two-step Entra help,
      primary button reads 登录并添加
  O6  测试连接 returns the configuration sentence immediately (no 8 s certificate probe)
  O7  登录并添加 with no client id shows “OneDrive 尚未配置”, adds nothing, leaves settings and vault
      byte-identical, and the app stays responsive
  O8  the log contains no access token, refresh token, authorization header, PKCE verifier or auth code
  O9-O13 (not-verified) browse / play / seek / resume / refresh-after-expiry need a signed-in account
  O14 mock coverage note: OneDriveSourceTests (27 cases) is a separate, labelled layer

.EXAMPLE
pwsh -NoProfile -File windows/tools/acceptance/onedrive/run.ps1
#>
[CmdletBinding()]
param(
    [string]$Exe = 'D:\deepseek_test\MediaHub_Win\windows\src\MediaHub.App\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$CallbackUrl = 'http://localhost:53682/',
    [switch]$NoCleanup
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhUia.psm1') -Force

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path (Split-Path $here -Parent) ('evidence\onedrive\' + $stamp)
$run = New-MhRun -Area 'onedrive-source' -EvidenceDir $evidence -Meta @{
    exe = $Exe; callback = $CallbackUrl; startedBy = 'run.ps1'; account = 'none'
}

$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$historyPath = Join-Path $env:LOCALAPPDATA 'MediaHub\history.json'
$vaultPath = Join-Path $env:LOCALAPPDATA 'MediaHub\credentials\credentials.vault.json'

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
    # UIA throws "unrecognized error" while the tree is being replaced (page transitions). That is a
    # transient state, not a finding, so the read is retried instead of aborting the run.
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

function Find-Any([string]$Contains, [switch]$IncludeOffscreen) {
    return (Wait-Until {
            Get-AppElements -IncludeOffscreen:$IncludeOffscreen |
                Where-Object { $_.Current.Name -and $_.Current.Name.Contains($Contains) } |
                Select-Object -First 1
        } 15)
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

function Open-AddDialog {
    $add = Wait-Until { Find-AppElement -AutomationId 'AddButton' } 20
    if (-not $add) { throw '未找到“添加媒体源”按钮' }
    Invoke-AppElement $add | Out-Null
    $combo = Wait-Until { Find-AppElement -AutomationId 'sources.type' } 15
    if (-not $combo) { throw '添加对话框未出现' }
    $selected = Select-MhComboItem -ComboElement $combo -Text 'OneDrive'
    Start-Sleep -Milliseconds 900
    return [pscustomobject]@{ selected = $selected; combo = $combo }
}

function Get-DialogHint {
    $hint = Find-AppElement -AutomationId 'sources.hint'
    if (-not $hint) { $hint = Find-AppElement -AutomationId 'sources.hint' -IncludeOffscreen }
    if (-not $hint) { return $null }
    return $hint.Current.Name
}

function Close-Dialog {
    $cancel = Wait-Until {
        Get-AppElements | Where-Object { $_.Current.Name -eq '取消' } | Select-Object -First 1
    } 10
    if ($cancel) { Invoke-AppElement $cancel | Out-Null }
    return [bool](Wait-Until { $null -eq (Find-AppElement -AutomationId 'sources.type') } 20)
}

function Get-OneDriveServerCount {
    if (-not (Test-Path $settingsPath)) { return 0 }
    try {
        $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
        return @($json.Servers | Where-Object { $_.Type -eq 'OneDrive' }).Count
    }
    catch { return -1 }
}

function Get-SettingsClientId {
    if (-not (Test-Path $settingsPath)) { return $null }
    try {
        $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
        return $json.oneDriveClientId
    }
    catch { return $null }
}

function Get-FileText([string]$Path) {
    if (Test-Path $Path) { return (Get-Content $Path -Raw) } else { return '' }
}

try {
    # ------------------------------------------------------------ 0. environment, no app yet
    if (Test-Path $settingsPath) { Copy-Item $settingsPath (Join-Path $evidence 'settings-before.json') -Force }
    $settingsBefore = Get-FileText $settingsPath
    $vaultBefore = Get-FileText $vaultPath
    $clientIdBefore = Get-SettingsClientId

    Add-MhCheck -Run $run -Id 'O1' -Input '读取 settings.json 的 oneDriveClientId' `
        -After @{ clientId = $(if ($null -eq $clientIdBefore -or $clientIdBefore -eq '') { '(empty)' } else { '(set)' }); settingsExists = (Test-Path $settingsPath) } `
        -Assertion '本机没有配置 OneDrive 客户端 ID，因此本轮只能验证“未配置”路径，真机账号流程必须另记' `
        -Result $(if ([string]::IsNullOrWhiteSpace([string]$clientIdBefore)) { 'pass' } else { 'fail' }) `
        -Evidence @('settings-before.json') `
        -Note '配置项名 oneDriveClientId / oneDriveRedirectUri；仓库里没有任何真实客户端 ID'

    # The callback is a loopback listener owned by the app. If the port cannot be bound here, sign-in
    # would fail for a reason that has nothing to do with OAuth, so the port is probed directly.
    $portProbe = 'not attempted'
    $portOk = $false
    try {
        $listener = [System.Net.HttpListener]::new()
        $listener.Prefixes.Add($CallbackUrl)
        $listener.Start()
        $portOk = $listener.IsListening
        $listener.Stop(); $listener.Close()
        $portProbe = 'bound and released'
    }
    catch {
        $portProbe = $_.Exception.GetType().Name + ': ' + $_.Exception.Message
    }
    Set-Content -Path (Join-Path $evidence 'callback-port.txt') -Encoding UTF8 -Value @(
        ('callback = ' + $CallbackUrl),
        ('result = ' + $portProbe),
        ('time = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    )
    Add-MhCheck -Run $run -Id 'O2' -Input ('用 HttpListener 绑定 ' + $CallbackUrl) `
        -After @{ probe = $portProbe } `
        -Assertion '本机可以把 OAuth 回调地址绑定为回环监听，登录回调不会因端口或权限失败' `
        -Result $(if ($portOk) { 'pass' } else { 'fail' }) `
        -Evidence @('callback-port.txt')

    # ------------------------------------------------------------ 0b. real endpoints (no account)
    # A public client that was never registered must be refused by the real token endpoint. This is a
    # real POST to Microsoft, with no credential of any kind in it.
    $tokenStatus = $null
    $tokenError = $null
    $tokenNote = ''
    try {
        $body = @{
            client_id     = '00000000-0000-0000-0000-000000000000'
            grant_type    = 'authorization_code'
            code          = 'not-a-real-code'
            redirect_uri  = $CallbackUrl
            code_verifier = ('v' * 43)
        }
        $resp = Invoke-WebRequest -Method Post -SkipHttpErrorCheck -TimeoutSec 25 `
            -Uri 'https://login.microsoftonline.com/common/oauth2/v2.0/token' -Body $body
        $tokenStatus = [int]$resp.StatusCode
        $parsed = $null
        try { $parsed = $resp.Content | ConvertFrom-Json } catch { }
        if ($parsed) { $tokenError = $parsed.error }
        $tokenNote = 'status=' + $tokenStatus + ' error=' + $tokenError
    }
    catch {
        $tokenNote = 'unreachable: ' + $_.Exception.Message
    }
    Set-Content -Path (Join-Path $evidence 'endpoints.txt') -Encoding UTF8 -Value @(
        ('token endpoint = https://login.microsoftonline.com/common/oauth2/v2.0/token'),
        ('unregistered client probe = ' + $tokenNote),
        ('time = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    )
    Add-MhCheck -Run $run -Id 'O3' -Input '向真实 token 端点提交一个未注册的公共客户端（无任何真实凭据）' `
        -After @{ status = $tokenStatus; error = $tokenError } `
        -Assertion '微软返回 400 且 error 为 invalid_client/invalid_grant，说明令牌端点可达、请求形态正确' `
        -Result $(if ($tokenStatus -eq 400 -and $tokenError) { 'pass' } elseif (-not $tokenStatus) { 'not-verified' } else { 'fail' }) `
        -Evidence @('endpoints.txt') -Note $tokenNote

    $graphStatus = $null
    $graphNote = ''
    try {
        $graph = Invoke-WebRequest -SkipHttpErrorCheck -TimeoutSec 25 -Uri 'https://graph.microsoft.com/v1.0/me'
        $graphStatus = [int]$graph.StatusCode
        $graphNote = 'status=' + $graphStatus
    }
    catch {
        $graphNote = 'unreachable: ' + $_.Exception.Message
    }
    Add-Content -Path (Join-Path $evidence 'endpoints.txt') -Encoding UTF8 -Value @(
        ('graph /me without token = ' + $graphNote)
    )
    Add-MhCheck -Run $run -Id 'O4' -Input '不带令牌访问 https://graph.microsoft.com/v1.0/me' `
        -After @{ status = $graphStatus } `
        -Assertion 'Graph 返回 401，说明应用里的“401 → 刷新一次 → 重试”有真实的被调用方' `
        -Result $(if ($graphStatus -eq 401) { 'pass' } elseif (-not $graphStatus) { 'not-verified' } else { 'fail' }) `
        -Evidence @('endpoints.txt') -Note $graphNote

    # ------------------------------------------------------------ 1. app up
    Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Start-MhApp -Exe $Exe -ResetTrace | Out-Null
    if (-not (Wait-Until { Get-MhWindowInfo } 40)) { throw '应用窗口未出现' }
    Set-MhWindowState -State maximized | Out-Null
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
    if (-not (Invoke-Nav '媒体源' 'AddButton')) { throw '媒体源页面未打开' }

    # ------------------------------------------------------------ 2. dialog shape
    $opened = Open-AddDialog
    $urlVisible = [bool](Find-AppElement -AutomationId 'sources.url')
    $userVisible = [bool](Find-AppElement -AutomationId 'sources.user')
    $passwordVisible = [bool](Find-AppElement -AutomationId 'sources.password')
    $hint = Get-DialogHint
    $primary = Find-AppElement -AutomationId 'PrimaryButton'
    $primaryText = if ($primary) { $primary.Current.Name } else { $null }
    $shotDialog = Save-WindowShot '01-dialog-onedrive.png'
    Add-MhCheck -Run $run -Id 'O5' -Input '添加媒体源 → 类型选择“OneDrive”' `
        -After @{ selection = $opened.selected; urlVisible = $urlVisible; userVisible = $userVisible;
                   passwordVisible = $passwordVisible; primary = $primaryText; hint = $hint } `
        -Assertion 'OneDrive 隐藏地址与账号密码（身份来自浏览器登录），提示两步 Entra 配置，主按钮为“登录并添加”' `
        -Result $(if ($opened.selected -like '*VERIFIED*' -and -not $urlVisible -and -not $userVisible -and
                -not $passwordVisible -and $primaryText -eq '登录并添加' -and
                $hint -like '*oneDriveClientId*' -and $hint -like '*Entra*') { 'pass' } else { 'fail' }) `
        -Evidence @('01-dialog-onedrive.png') -Note ("hint='" + $hint + "' screenshot=" + $shotDialog.path)

    # ------------------------------------------------------------ 3. test connection is a sentence, fast
    $testButton = Wait-Until {
        Get-AppElements | Where-Object { $_.Current.Name -eq '测试连接' } | Select-Object -First 1
    } 10
    if (-not $testButton) { throw '添加对话框未找到“测试连接”按钮' }
    $started = Get-Date
    Invoke-AppElement $testButton | Out-Null
    $testHint = Wait-Until {
        $text = Get-DialogHint
        if ($text -and $text -ne '正在连接…') { $text }
    } 30
    $elapsed = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    $shotTest = Save-WindowShot '02-test-unconfigured.png'
    Add-MhCheck -Run $run -Id 'O6' -Input '未配置客户端 ID 时点击“测试连接”' `
        -After @{ hint = $testHint; seconds = $elapsed } `
        -Assertion '立即返回配置说明（<10s），不再等待 8 秒证书探测，提示里点名 oneDriveClientId' `
        -Result $(if ($testHint -and $testHint -like '*oneDriveClientId*' -and $elapsed -lt 10) { 'pass' } else { 'fail' }) `
        -Evidence @('02-test-unconfigured.png') -Note ("seconds=" + $elapsed + " screenshot=" + $shotTest.path)

    # ------------------------------------------------------------ 4. primary button with no client id
    # The baseline is taken after the app has opened its pages, so the comparison isolates the click
    # from any settings rewrite a normal startup may do.
    $serverCountBefore = Get-OneDriveServerCount
    $settingsAtDialog = Get-FileText $settingsPath
    $vaultAtDialog = Get-FileText $vaultPath
    $primary = Find-AppElement -AutomationId 'PrimaryButton'
    Invoke-AppElement $primary | Out-Null
    $closed = Wait-Until { $null -eq (Find-AppElement -AutomationId 'sources.type') } 30
    $bar = Find-Any 'OneDrive 尚未配置'
    $barText = if ($bar) { $bar.Current.Name } else { $null }
    $shotBar = Save-WindowShot '03-not-configured.png'

    $settingsAfter = Get-FileText $settingsPath
    $vaultAfter = Get-FileText $vaultPath
    $serverCountAfter = Get-OneDriveServerCount
    if (Test-Path $settingsPath) { Copy-Item $settingsPath (Join-Path $evidence 'settings-after.json') -Force }

    # Responsiveness after the refusal: the page must still be usable, not stuck on a spinner.
    $navBack = Invoke-Nav '媒体库' 'libraries.reload'
    $navAgain = Invoke-Nav '媒体源' 'AddButton'

    Add-MhCheck -Run $run -Id 'O7' -Input '未配置客户端 ID 时点击“登录并添加”' `
        -Before @{ oneDriveServers = $serverCountBefore; settingsBytes = $settingsAtDialog.Length; vaultBytes = $vaultAtDialog.Length } `
        -After @{ dialogClosed = [bool]$closed; bar = $barText; oneDriveServers = $serverCountAfter;
                  settingsBytes = $settingsAfter.Length; vaultBytes = $vaultAfter.Length;
                  settingsIdentical = ($settingsAfter -eq $settingsAtDialog); vaultIdentical = ($vaultAfter -eq $vaultAtDialog);
                  responsive = ($navBack -and $navAgain) } `
        -Assertion '状态栏提示“OneDrive 尚未配置”并给出下一步；不新增来源、settings 与 vault 逐字节不变、应用仍可导航' `
        -Result $(if ($closed -and $barText -like '*OneDrive 尚未配置*' -and $serverCountAfter -eq 0 -and
                $settingsAfter -eq $settingsAtDialog -and $vaultAfter -eq $vaultAtDialog -and $navBack -and $navAgain) { 'pass' } else { 'fail' }) `
        -Evidence @('03-not-configured.png', 'settings-after.json') `
        -Note ("bar='" + $barText + "' screenshot=" + $shotBar.path)
}
catch {
    Add-MhNote -Run $run -Text ('运行中止：' + $_.Exception.Message)
    Add-MhCheck -Run $run -Id 'X1' -Input '脚本异常' -Assertion '脚本应完整跑完所有检查' -Result 'fail' -Note $_.Exception.Message
}
finally {
    # ------------------------------------------------------------ 5. secrets in the log
    $logLines = @(Get-MhLog)
    $secretPatterns = @(
        'access_token', 'refresh_token', 'client_secret', 'code_verifier', 'Bearer\s+[A-Za-z0-9\-_\.]{20,}',
        'code=[A-Za-z0-9\-_\.]{8,}'
    )
    $hits = @()
    foreach ($pattern in $secretPatterns) {
        $hits += @($logLines | Where-Object { $_ -match $pattern })
    }
    # The log is meant to be safe to paste into a bug report; a hit here is a truthfulness failure even
    # if the value were fake, because nothing in this run ever had a token.
    Add-MhCheck -Run $run -Id 'O8' -Input '在 startup.log 中检索 access_token / refresh_token / Bearer / code_verifier / code=' `
        -After @{ logLines = $logLines.Count; hits = $hits.Count } `
        -Assertion '日志不含任何令牌、授权码或 PKCE 校验串；签名 URL 与刷新令牌都不落日志' `
        -Result $(if ($hits.Count -eq 0) { 'pass' } else { 'fail' }) `
        -Evidence @('startup.log') `
        -Note $(if ($hits.Count -gt 0) { 'first hit: ' + ($hits[0].Substring(0, [Math]::Min(160, $hits[0].Length))) } else { 'no secret-shaped line' })

    $needsAccount = '需要一个已注册的 Entra 公共客户端（oneDriveClientId）+ 一个可登录的 OneDrive 账号；' +
        '本轮机器上两者都没有，因此未执行'
    foreach ($row in @(
            @{ id = 'O9'; what = '浏览：列 OneDrive 根目录与子目录'; why = '目录读取需要真实 Bearer 令牌' },
            @{ id = 'O10'; what = '起播：按文件起播 OneDrive 视频'; why = '播放需要 /content 302 到存储主机的签名 URL' },
            @{ id = 'O11'; what = '拖动：播放中跳到中段，观察新的 Range 请求'; why = '拖动需要真实媒体流才能观察到 206' },
            @{ id = 'O12'; what = '续播：退出后重开同一条目从上次位置继续'; why = '续播依赖真实起播 + 本地历史' },
            @{ id = 'O13'; what = '令牌过期后刷新：删掉访问令牌后继续浏览/起播'; why = '刷新需要真实 refresh token' }
        )) {
        Add-MhCheck -Run $run -Id $row.id -Input $row.what `
            -Assertion ($row.why + '；缺少真实账号时不得以 mock 结论代替') `
            -Result 'not-verified' -Note $needsAccount
    }

    Add-MhNote -Run $run -Text ('mock 覆盖（单独标注，不算真机结论）：OneDriveSourceTests 27 例，覆盖 PKCE、回调、刷新轮换、401/429/404/403、Range 中继与签名 URL 不外泄；由 dotnet test 执行')
    Add-MhNote -Run $run -Text ('应用日志行数=' + $logLines.Count)
    try { Copy-Item (Get-MhLogPath) (Join-Path $evidence 'startup.log') -Force } catch { }
    $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')

    try { Stop-MhAppGracefully -TimeoutSec 15 | Out-Null } catch { }
    if (@(Get-MhProcess).Count -gt 0) { Stop-MhAppForced -Reason 'onedrive acceptance cleanup' | Out-Null }

    if (-not $NoCleanup) {
        # Nothing was added and nothing was written, but the run asserts that rather than assuming it;
        # if a source ever did appear, remove it instead of leaving it behind.
        try {
            $json = Get-Content $settingsPath -Raw | ConvertFrom-Json
            $oneDriveIds = @($json.Servers | Where-Object { $_.Type -eq 'OneDrive' } | ForEach-Object { $_.ServerId })
            if ($oneDriveIds.Count -gt 0) {
                $json.Servers = @($json.Servers | Where-Object { $_.Type -ne 'OneDrive' })
                $json | ConvertTo-Json -Depth 24 | Set-Content -Path $settingsPath -Encoding UTF8
                Add-MhNote -Run $run -Text ('cleanup: removed onedrive sources=' + ($oneDriveIds -join ','))
            }
        }
        catch { Add-MhNote -Run $run -Text ('cleanup settings failed: ' + $_.Exception.Message) }
        $saved = Save-MhRun -Run $run -MarkdownPath (Join-Path $evidence 'results.md')
    }

    Write-Host ''
    Write-Host ('RESULT: ' + $saved.counts.pass + ' passed, ' + $saved.counts.fail + ' failed, ' +
        $saved.counts['not-verified'] + ' not-verified')
    Write-Host ('evidence: ' + $evidence)
    if ($saved.counts.fail -gt 0) { exit 1 }
    exit 0
}

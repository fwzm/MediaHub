#Requires -Version 7
<#
.SYNOPSIS
可续跑驱动器：把「套件 × 服务器」拆成 6 个独立组合，每个组合单独过空闲门禁、单独起停服务器；
任何一次被使用者打断都只损失当前组合，已完成的组合记进检查点，下个空闲窗口从断点继续。

.DESCRIPTION
为什么不是一次跑完：这台机器的使用者在场时间长且不可预测，一次跑完的窗口（约 40 分钟）很难拿到；
四次整跑全部在门禁打开后 1-3 分钟被输入打断（ABORTED-BY-USER）。拆成组合后，一个 8-10 分钟的空闲窗口就能推进一格。
#>
[CmdletBinding()]
param(
    [string]$OutRoot = 'D:\mh-verify\quiet-w10r',
    [string]$Checkpoint = '',
    [int]$MinIdleSeconds = 900,
    [int]$WaitForIdleMinutes = 420,
    [int]$BuildWaitMinutes = 20,
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$Runner = ''
)

if (-not $Runner) { $Runner = Join-Path $PSScriptRoot 'run-quiet.ps1' }
if (-not $Checkpoint) { $Checkpoint = Join-Path $OutRoot 'checkpoint.json' }
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$rollup = Join-Path $OutRoot 'resume-summary.txt'

function Write-Rollup([string]$msg) {
    $msg | Add-Content -Path $rollup -Encoding utf8
    Write-Host $msg
}

# 检查点：已完成的组合 key
$state = @{ done = @(); failed = @() }
if (Test-Path $Checkpoint) {
    try {
        $raw = Get-Content $Checkpoint -Raw | ConvertFrom-Json
        if ($raw.done) { $state.done = @($raw.done) }
        if ($raw.failed) { $state.failed = @($raw.failed) }
    } catch {
        Write-Rollup ('checkpoint unreadable, starting fresh: ' + $_.Exception.Message)
    }
}

function Save-Checkpoint {
    $state | ConvertTo-Json -Depth 4 | Set-Content -Path $Checkpoint -Encoding utf8
}

$combos = @(
    @{ server = 'emby';     suite = 'u2' },
    @{ server = 'emby';     suite = 'u3' },
    @{ server = 'emby';     suite = 'u4' },
    @{ server = 'jellyfin'; suite = 'u2' },
    @{ server = 'jellyfin'; suite = 'u3' },
    @{ server = 'jellyfin'; suite = 'u4' }
)

Write-Rollup ('=== resume run ' + (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') + ' threshold=' + $MinIdleSeconds + 's wait=' + $WaitForIdleMinutes + 'min exe=' + $Exe)
Write-Rollup ('already done: ' + ($(if ($state.done.Count) { $state.done -join ',' } else { '<none>' })))

# GUI 验收期间任何 dotnet build/test 都会污染时序断言并抢 CPU，所以每次开跑前先等它清场。
# 只按「名字」判忙会误杀：dotnet build 结束后 MSBuild 节点与 VBCSCompiler 会空闲存活很久，
# 于是空闲窗口里永远看到 dotnet 进程、驱动器一直等下去。改为看 1.5 秒内的 CPU 增量：
# 真正的构建/测试会吃掉可观 CPU，空闲节点几乎为 0。MediaHub 进程则一律算忙（GUI 已在跑）。
# java/javaw 是 Gradle 跑 Android 单测的宿主：同样只看 CPU 增量，闲置的 Gradle/Kotlin 守护进程不会误判。
function Test-BuildBusy {
    $procs = @(Get-Process dotnet, MSBuild, VBCSCompiler, MediaHub, java, javaw -ErrorAction SilentlyContinue)
    if ($procs.Count -eq 0) { return $false }
    if (@($procs | Where-Object { $_.ProcessName -eq 'MediaHub' }).Count -gt 0) { return $true }
    $before = @{}
    foreach ($p in $procs) { $before[$p.Id] = $p.TotalProcessorTime.TotalSeconds }
    Start-Sleep -Milliseconds 1500
    foreach ($id in $before.Keys) {
        $p = Get-Process -Id $id -ErrorAction SilentlyContinue
        if (-not $p) { continue }
        if (($p.TotalProcessorTime.TotalSeconds - $before[$id]) -gt 0.6) { return $true }
    }
    return $false
}

function Wait-NoBuildActivity([int]$TimeoutSeconds) {
    # 先查一次再决定要不要等：TimeoutSeconds=0 的语义是「现在就看着办」，不是「一律判忙」。
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        if (-not (Test-BuildBusy)) { return $true }
        if ((Get-Date) -ge $deadline) { return $false }
        Start-Sleep -Seconds 10
    }
}

$stopped = $null
foreach ($c in $combos) {
    $key = $c.server + '-' + $c.suite
    if ($state.done -contains $key) { Write-Rollup ($key + ' SKIP (already done)'); continue }

    $dir = Join-Path $OutRoot $key
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if (-not (Wait-NoBuildActivity -TimeoutSeconds ($BuildWaitMinutes * 60))) {
        Write-Rollup ($key + ' BUSY-BUILD: dotnet/msbuild still running after ' + $BuildWaitMinutes + ' min - leaving this window for the next one')
        $stopped = 'busy-build'
        break
    }
    Write-Rollup ($key + ' START ' + (Get-Date -Format 'HH:mm:ss'))

    & $Runner -Suites @($c.suite) -Servers @($c.server) -MinIdleSeconds $MinIdleSeconds -WaitForIdleMinutes $WaitForIdleMinutes -OutRoot $dir -Exe $Exe *>&1 |
        Tee-Object -FilePath (Join-Path $dir 'console.log') | Out-Null

    $summary = Get-ChildItem $dir -Filter 'summary-*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $line = $null
    if ($summary) {
        $hit = Select-String -Path $summary.FullName -Pattern '^RESULT:' | Select-Object -Last 1
        if ($hit) { $line = $hit.Line }
    }
    if (-not $line) {
        $skip = $false
        if ($summary) {
            $skipHit = Select-String -Path $summary.FullName -Pattern '^SKIPPED-USER-ACTIVE' | Select-Object -Last 1
            if ($skipHit) { $skip = $true }
        }
        if ($skip) {
            Write-Rollup ($key + ' SKIPPED-USER-ACTIVE (gate never opened) - stopping, nothing was started')
            $stopped = 'skipped'
            break
        }
        Write-Rollup ($key + ' NO-RESULT (see ' + $dir + ') - treating as failure')
        $state.failed = @($state.failed + $key | Select-Object -Unique)
        Save-Checkpoint
        $stopped = 'failed'
        break
    }

    Write-Rollup ($key + ' ' + $line)
    if ($line -match 'RESULT:\s*OK') {
        $state.done = @($state.done + $key | Select-Object -Unique)
        $state.failed = @($state.failed | Where-Object { $_ -ne $key })
        Save-Checkpoint
    } elseif ($line -match 'ABORTED-BY-USER') {
        Write-Rollup ($key + ' ABORTED-BY-USER - stopping the loop, next window resumes here')
        Save-Checkpoint
        $stopped = 'aborted'
        break
    } else {
        $state.failed = @($state.failed + $key | Select-Object -Unique)
        Save-Checkpoint
        Write-Rollup ($key + ' FAILED - continuing with the next combo')
    }
}

# 收尾：确保没有残留
Get-Process MediaHub,EmbyServer,jellyfin -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$remaining = @($combos | ForEach-Object { $_.server + '-' + $_.suite } | Where-Object { $state.done -notcontains $_ })
Write-Rollup ('done=' + $state.done.Count + '/6 failed=' + ($(if ($state.failed.Count) { $state.failed -join ',' } else { '<none>' })) + ' remaining=' + ($(if ($remaining.Count) { $remaining -join ',' } else { '<none>' })))
if ($remaining.Count -eq 0) {
    Write-Rollup 'RESULT: COMPLETE (all 6 combos have evidence)'
    exit 0
}
Write-Rollup ('RESULT: INCOMPLETE stopped=' + $stopped)
exit 2
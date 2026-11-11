# R7 验收 B2：多选 → 临时队列 → 下一项真实播放闭环。
# 判据纪律：每一处「变了」都断言具体值或具体 trace 键；不用「时间在动」这类可被自然播放满足的判据。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force
Import-Module ($R + 'windows\tools\acceptance\lib\MhPicker.psm1') -Force
$ev = $R + 'windows\tools\acceptance\evidence\r7-b2-queue'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}
function Wait-Element([string]$id, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id
    if ($e) { return $e }
    Start-Sleep -Milliseconds 300
  }
  return $null
}
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
# 等播放位置长到阈值：切换/起播后立刻采样 position 往往仍是 0:00，
# 固定时刻采样会把「刚起播」误判成「没在播放」（本轮回归就出现了一次假失败）。
function Wait-Position([int]$minSeconds, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  do {
    $s = Get-MhPlayerState -SkipSlow
    if ($null -ne $s.positionSeconds -and $s.positionSeconds -ge $minSeconds) { return $s }
    Start-Sleep -Milliseconds 400
  } while ((Get-Date) -lt $deadline)
  return (Get-MhPlayerState -SkipSlow)
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
function Invoke-Menu([string]$id) {
  $fm = Wait-Element 'menu.file' 25
  if (-not $fm) { return $false }
  Invoke-MhElement -Element $fm | Out-Null
  Start-Sleep -Milliseconds 900
  $item = Find-MhAppElement -AutomationId $id
  if (-not $item) { return $false }
  Invoke-MhElement -Element $item | Out-Null
  return $true
}
function Get-QueueRowNames {
  $list = Find-MhAppElement -AutomationId 'panel.queue' -IncludeOffscreen
  if (-not $list) { return @() }
  $rows = $list.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { (Get-MhControlType $_) -eq 'ListItem' }
  return @($rows | ForEach-Object { $_.Current.Name })
}
function Get-QueueRows {
  $list = Find-MhAppElement -AutomationId 'panel.queue' -IncludeOffscreen
  if (-not $list) { return @() }
  return @($list.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { (Get-MhControlType $_) -eq 'ListItem' })
}

$a = 'D:\mh-media\queue-a.mp4'
$b = 'D:\mh-media\queue-b.mp4'
$c = 'D:\mh-media\queue-c.mp4'
$audio = 'D:\mh-media\Audio\Sine Tone.aac'
$cn = 'D:\mh-media\LocalOpen\Chinese 中文 空格.mp4'
foreach ($f in @($a, $b, $c, $audio, $cn)) {
  if (-not (Test-Path $f)) { Write-Host ('MISSING FIXTURE ' + $f) }
}

# ================================================================ B2-1 命令行多文件摄入
Write-Host '===== B2-1 命令行多文件 → 临时队列 ====='
Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$app = Start-MhApp -ArgumentList @($a, $b, $c) -ResetTrace -TimeoutSec 90
Write-Host ('pid=' + $app.pid)
$titleEl = Wait-Element 'TitleText' 60
Check 'B2-1 命令行多文件进入播放器' ($null -ne $titleEl) ('title=' + $(if ($titleEl) { $titleEl.Current.Name } else { '<none>' }))
Check 'B2-1 队首是 queue-a' (Wait-Text 'TitleText' 'queue-a' 25) ('actual=' + (Get-Text 'TitleText'))
$trace = Get-MhLog
Check 'B2-1 trace: 多文件以 Replace 摄入' ([bool]($trace -match 'queue:open mode=Replace paths=3')) (($trace | Where-Object { $_ -match 'queue:open' }) -join ' / ')
Check 'B2-1 trace: intake 3 项 0 重复' ([bool]($trace -match 'queue:intake mode=Replace requested=3 added=3 duplicates=0')) (($trace | Where-Object { $_ -match 'queue:intake' }) -join ' / ')
Check 'B2-1 队列计数 1/3' (Wait-Text 'player.queuecount' '队列 1/3' 20) ('actual=' + (Get-Text 'player.queuecount'))
Check 'B2-1 下一项提示 queue-b' (Wait-Text 'player.queuenexttitle' '下一项：queue-b' 20) ('actual=' + (Get-Text 'player.queuenexttitle'))
$prev = Find-MhAppElement -AutomationId 'player.queueprev'
$nextB = Find-MhAppElement -AutomationId 'player.queuenext'
Check 'B2-1 首项时「上一项」禁用' ([bool]($prev -and -not $prev.Current.IsEnabled)) ('found=' + ($null -ne $prev))
Check 'B2-1 首项时「下一项」可用' ([bool]($nextB -and $nextB.Current.IsEnabled)) ('found=' + ($null -ne $nextB))
$st = Get-MhPlayerState -SkipSlow
Check 'B2-1 首项真的在播放' ([bool]($st.positionSeconds -gt 0)) ('position=' + $st.position + ' duration=' + $st.duration)
Save-MhScreenshot -Path ($ev + '\01-cli-intake.png') | Out-Null

# ================================================================ B2-2 下一项：切项 + 释放旧会话
Write-Host '===== B2-2 下一项 ====='
$before = (Get-MhPlayerState -SkipSlow).positionSeconds
Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.queuenext') | Out-Null
Check 'B2-2 切到 queue-b' (Wait-Text 'TitleText' 'queue-b' 30) ('actual=' + (Get-Text 'TitleText'))
Check 'B2-2 队列计数 2/3' (Wait-Text 'player.queuecount' '队列 2/3' 20) ('actual=' + (Get-Text 'player.queuecount'))
Start-Sleep -Milliseconds 800
$st2 = Get-MhPlayerState -SkipSlow
Check 'B2-2 新项从起点重新播放（旧位置未沿用）' ([bool]($st2.positionSeconds -ge 0 -and $st2.positionSeconds -lt 6)) ('before=' + $before + ' after=' + $st2.position)
$trace = Get-MhLog
Check 'B2-2 trace: 切项且旧项以 replaced 停止' ([bool](($trace -match 'queue:switch reason=replaced') -and ($trace -match 'title=queue-b'))) (($trace | Where-Object { $_ -match 'queue:switch' }) -join ' / ')
Start-Sleep -Seconds 2
$st3 = Get-MhPlayerState -SkipSlow
Check 'B2-2 切项后时钟继续推进' ([bool]($st3.positionSeconds -gt $st2.positionSeconds)) (('{0} -> {1}' -f $st2.position, $st3.position))

# ================================================================ B2-3 队列面板
Write-Host '===== B2-3 队列面板 ====='
$qp = Find-MhAppElement -AutomationId 'player.queuepanel'
Check 'B2-3 队列按钮可达' ($null -ne $qp) ''
if ($qp) { Invoke-MhElement -Element $qp | Out-Null }
Start-Sleep -Milliseconds 900
$rows = Get-QueueRowNames
Check 'B2-3 队列面板列出全部 3 项' ([bool]($rows.Count -eq 3)) ('rows=' + $rows.Count + ' :: ' + ($rows -join ' | '))
$marked = @($rows | Where-Object { $_ -match 'queue-b' -and $_ -match [string][char]0x25B6 })
Check 'B2-3 当前项带 ▶ 标记' ([bool]($marked.Count -eq 1)) ('marked=' + ($marked -join ' / '))
Save-MhScreenshot -Path ($ev + '\02-queue-panel.png') | Out-Null

# ================================================================ B2-4 移除非当前项
Write-Host '===== B2-4 移除非当前项 ====='
$rowEls = Get-QueueRows
$rowA = $rowEls | Where-Object { $_.Current.Name -match 'queue-a' } | Select-Object -First 1
Check 'B2-4 找到 queue-a 行' ($null -ne $rowA) ''
if ($rowA) { Invoke-MhElement -Element $rowA | Out-Null }
Start-Sleep -Milliseconds 600
# 面板内的按钮可能滚出可见区，但 InvokePattern 仍可用；不查 offscreen 会误判为「按钮不存在」。
$rm = Find-MhAppElement -AutomationId 'player.queueremove' -IncludeOffscreen
Check 'B2-4 移除按钮存在' ($null -ne $rm) ('offscreen=' + $(if ($rm) { $rm.Current.IsOffscreen } else { 'n/a' }))
Invoke-MhElement -Element $rm | Out-Null
Check 'B2-4 移除后 1/2' (Wait-Text 'player.queuecount' '队列 1/2' 20) ('actual=' + (Get-Text 'player.queuecount'))
Check 'B2-4 移除非当前项不打断播放' ([bool]((Get-Text 'TitleText') -eq 'queue-b')) ('title=' + (Get-Text 'TitleText'))
$names2 = Get-QueueRowNames
Check 'B2-4 面板不再含 queue-a' ([bool](($names2 -join ' ') -notmatch 'queue-a')) ('rows=' + ($names2 -join ' | '))
$trace = Get-MhLog
Check 'B2-4 trace: 移除记录' ([bool]($trace -match 'queue:remove index=0 remaining=2')) (($trace | Where-Object { $_ -match 'queue:remove' }) -join ' / ')

# ================================================================ B2-5 EOF 自动跳项
Write-Host '===== B2-5 EOF 自动跳项 ====='
$slider = Find-MhAppElement -AutomationId 'player.seekslider'
$rv = Get-MhRangeValue $slider
Write-Host ('  slider value=' + $rv.value + ' min=' + $rv.min + ' max=' + $rv.max)
Check 'B2-5 Seek 滑块可读' ($null -ne $rv -and $rv.max -gt 0) ('max=' + $(if ($rv) { $rv.max } else { 'n/a' }))
# 滑块是 0..1 归一值（不是秒），跳到 97% 让本项尽快播完。
if ($rv -and $rv.max -gt 0) { Set-MhRangeValue -Element $slider -Value ($rv.min + ($rv.max - $rv.min) * 0.97) | Out-Null }
Check 'B2-5 播完自动跳到 queue-c' (Wait-Text 'TitleText' 'queue-c' 40) ('actual=' + (Get-Text 'TitleText'))
Check 'B2-5 自动跳项后计数 2/2' (Wait-Text 'player.queuecount' '队列 2/2' 20) ('actual=' + (Get-Text 'player.queuecount'))
$trace = Get-MhLog
Check 'B2-5 trace: 自动跳项（队列优先）' ([bool]($trace -match 'queue:auto-advance')) (($trace | Where-Object { $_ -match 'queue:auto-advance' }) -join ' / ')
Save-MhScreenshot -Path ($ev + '\03-auto-advance.png') | Out-Null
Start-Sleep -Seconds 4
Check 'B2-5 末项播完不再前进（无重入）' ([bool]((Get-Text 'TitleText') -eq 'queue-c')) ('title=' + (Get-Text 'TitleText') + ' count=' + (Get-Text 'player.queuecount'))

# ================================================================ B2-6 播放中追加
Write-Host '===== B2-6 播放中追加（加入播放队列…）====='
Check 'B2-6 菜单项可达' (Invoke-Menu 'menu.addtoqueue') ''
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'B2-6 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ('title="' + $(if ($dlg -ne [IntPtr]::Zero) { Get-MhPickerTitle -Dialog $dlg } else { '' }) + '"')
if ($dlg -ne [IntPtr]::Zero) {
  $w = Set-MhPickerPath -Dialog $dlg -Path $audio
  Write-Host ('  路径框: ok=' + $w.ok + ' reason=' + $w.reason)
  Confirm-MhPicker -Dialog $dlg | Out-Null
  Check 'B2-6 对话框被确认关闭' (Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15) ''
}
Check 'B2-6 追加后计数 2/3' (Wait-Text 'player.queuecount' '队列 2/3' 25) ('actual=' + (Get-Text 'player.queuecount'))
Check 'B2-6 追加不打断当前播放' ([bool]((Get-Text 'TitleText') -eq 'queue-c')) ('title=' + (Get-Text 'TitleText'))
$trace = Get-MhLog
Check 'B2-6 trace: Append requested=1 added=1' ([bool]($trace -match 'queue:intake mode=Append requested=1 added=1')) (($trace | Where-Object { $_ -match 'queue:intake' }) -join ' / ')
Check 'B2-6 追加项出现在队列里' ([bool]((Get-QueueRowNames) -join ' ' -match 'Sine Tone')) ('rows=' + ((Get-QueueRowNames) -join ' | '))

# ================================================================ B2-6b 重复路径
Write-Host '===== B2-6b 重复路径去重 ====='
Check 'B2-6b 菜单项可达' (Invoke-Menu 'menu.addtoqueue') ''
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
if ($dlg -ne [IntPtr]::Zero) {
  Set-MhPickerPath -Dialog $dlg -Path $c | Out-Null
  Confirm-MhPicker -Dialog $dlg | Out-Null
  Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15 | Out-Null
}
Start-Sleep -Seconds 1
$trace = Get-MhLog
Check 'B2-6b trace: 重复项 added=0 duplicates=1' ([bool]($trace -match 'queue:intake mode=Append requested=1 added=0 duplicates=1')) (($trace | Where-Object { $_ -match 'queue:intake' }) -join ' / ')
Check 'B2-6b 计数未变仍为 2/3' ([bool]((Get-Text 'player.queuecount') -eq '队列 2/3')) ('actual=' + (Get-Text 'player.queuecount'))

# ================================================================ B2-7 取消不改状态
Write-Host '===== B2-7 取消替换（打开视频… → 取消）====='
$titleBefore = Get-Text 'TitleText'
$countBefore = Get-Text 'player.queuecount'
Check 'B2-7 菜单项可达' (Invoke-Menu 'menu.openvideo') ''
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'B2-7 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ''
if ($dlg -ne [IntPtr]::Zero) {
  Write-Host ('  取消按钮=' + (Close-MhPicker -Dialog $dlg))
  Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15 | Out-Null
}
Start-Sleep -Seconds 1
$trace = Get-MhLog
Check 'B2-7 trace: 记录为取消' ([bool]($trace -match 'menu:openvideo-cancelled')) (($trace | Where-Object { $_ -match 'menu:openvideo' }) -join ' / ')
Check 'B2-7 取消后仍在播原项' ([bool]((Get-Text 'TitleText') -eq $titleBefore)) ('before=' + $titleBefore + ' after=' + (Get-Text 'TitleText'))
Check 'B2-7 取消后队列未变' ([bool]((Get-Text 'player.queuecount') -eq $countBefore)) ('before=' + $countBefore + ' after=' + (Get-Text 'player.queuecount'))

# ================================================================ B2-8 播放中替换
Write-Host '===== B2-8 播放中替换（打开视频… → 单个文件）====='
Check 'B2-8 菜单项可达' (Invoke-Menu 'menu.openvideo') ''
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'B2-8 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ''
if ($dlg -ne [IntPtr]::Zero) {
  Set-MhPickerPath -Dialog $dlg -Path $cn | Out-Null
  Confirm-MhPicker -Dialog $dlg | Out-Null
  Check 'B2-8 对话框被确认关闭' (Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15) ''
}
Check 'B2-8 替换后队列只剩该文件 1/1' (Wait-Text 'player.queuecount' '队列 1/1' 40) ('actual=' + (Get-Text 'player.queuecount'))
Check 'B2-8 真的切到新文件（含中文空格）' (Wait-Text 'TitleText' 'Chinese 中文 空格' 40) ('actual=' + (Get-Text 'TitleText'))
$st4 = Wait-Position 1 30
Check 'B2-8 新文件在播放' ([bool]($st4.positionSeconds -ge 1 -and $st4.durationSeconds -gt 0)) ('position=' + $st4.position + ' duration=' + $st4.duration)
$trace = Get-MhLog
Check 'B2-8 trace: Replace requested=1 added=1' ([bool]($trace -match 'queue:intake mode=Replace requested=1 added=1')) (($trace | Where-Object { $_ -match 'queue:intake' }) -join ' / ')
$prev2 = Find-MhAppElement -AutomationId 'player.queueprev'
$next2 = Find-MhAppElement -AutomationId 'player.queuenext'
Check 'B2-8 单项时上一项/下一项均禁用' ([bool](-not $prev2.Current.IsEnabled -and -not $next2.Current.IsEnabled)) ('prev=' + $prev2.Current.IsEnabled + ' next=' + $next2.Current.IsEnabled)
Save-MhScreenshot -Path ($ev + '\04-replaced-single.png') | Out-Null

# ================================================================ B2-9 清空队列
Write-Host '===== B2-9 清空队列（不打断当前播放）====='
$titleKeep = Get-Text 'TitleText'
$qc = Find-MhAppElement -AutomationId 'player.queueclear' -IncludeOffscreen
Check 'B2-9 清空按钮存在' ($null -ne $qc) ('offscreen=' + $(if ($qc) { $qc.Current.IsOffscreen } else { 'n/a' }))
Invoke-MhElement -Element $qc | Out-Null
Start-Sleep -Seconds 1
$trace = Get-MhLog
Check 'B2-9 trace: queue:clear' ([bool]($trace -match 'queue:clear')) (($trace | Where-Object { $_ -match 'queue:' }) -join ' / ')
Check 'B2-9 队列计数文本消失' ([string]::IsNullOrEmpty((Get-Text 'player.queuecount'))) ('count="' + (Get-Text 'player.queuecount') + '"')
Check 'B2-9 清空后仍在播同一项' ([bool]((Get-Text 'TitleText') -eq $titleKeep)) ('before=' + $titleKeep + ' after=' + (Get-Text 'TitleText'))
Check 'B2-9 队列列表为空' ([bool]((@(Get-QueueRowNames)).Count -eq 0)) ('rows=' + (@(Get-QueueRowNames)).Count)
$qc2 = Find-MhAppElement -AutomationId 'player.queueclear' -IncludeOffscreen
$rm2 = Find-MhAppElement -AutomationId 'player.queueremove' -IncludeOffscreen
Check 'B2-9 队列空时清空/移除按钮禁用' ([bool](-not $qc2.Current.IsEnabled -and -not $rm2.Current.IsEnabled)) ('clear=' + $qc2.Current.IsEnabled + ' remove=' + $rm2.Current.IsEnabled)
Save-MhScreenshot -Path ($ev + '\05-cleared.png') | Out-Null

# ================================================================ B2-10 移除当前项
Write-Host '===== B2-10 移除当前项（提升下一项）====='
Stop-MhAppGracefully | Out-Null
Start-Sleep -Milliseconds 800
$app2 = Start-MhApp -ArgumentList @($a, $b) -ResetTrace -TimeoutSec 90
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) { if ((Get-Text 'TitleText') -eq 'queue-a') { break }; Start-Sleep -Milliseconds 300 }
Check 'B2-10 两项队列就绪 1/2' (Wait-Text 'player.queuecount' '队列 1/2' 20) ('actual=' + (Get-Text 'player.queuecount'))
Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.queuepanel' -IncludeOffscreen) | Out-Null
Start-Sleep -Milliseconds 900
$rows3 = Get-QueueRows
$rowCur = $rows3 | Where-Object { $_.Current.Name -match 'queue-a' } | Select-Object -First 1
Check 'B2-10 选中当前行' ($null -ne $rowCur) ('rows=' + $rows3.Count)
if ($rowCur) { Invoke-MhElement -Element $rowCur | Out-Null }
Start-Sleep -Milliseconds 500
$rm3 = Find-MhAppElement -AutomationId 'player.queueremove' -IncludeOffscreen
Invoke-MhElement -Element $rm3 | Out-Null
Check 'B2-10 移除当前项后切到 queue-b' (Wait-Text 'TitleText' 'queue-b' 30) ('actual=' + (Get-Text 'TitleText'))
Check 'B2-10 计数变为 1/1' (Wait-Text 'player.queuecount' '队列 1/1' 20) ('actual=' + (Get-Text 'player.queuecount'))
$trace = Get-MhLog
Check 'B2-10 trace: 移除当前项' ([bool]($trace -match 'queue:remove index=0 remaining=1')) (($trace | Where-Object { $_ -match 'queue:remove' }) -join ' / ')
Start-Sleep -Seconds 2
$st5 = Get-MhPlayerState -SkipSlow
Check 'B2-10 提升后的项真的在播放' ([bool]($st5.positionSeconds -gt 0)) ('position=' + $st5.position + ' title=' + $st5.title)

# ================================================================ 收尾
$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' passed, ' + $fail + ' failed')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\checks.json') -Encoding UTF8
(Get-MhLog) -join [Environment]::NewLine | Set-Content -Path ($ev + '\trace.log') -Encoding UTF8
Stop-MhAppGracefully | Out-Null
if ($fail -gt 0) { exit 1 } else { exit 0 }
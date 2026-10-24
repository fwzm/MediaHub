# R7 Gate C：对 R6 已修缺陷的回归。
#   第一部分：原样重放 r6-audio.ps1（音轨面板 toggle / 字幕列表不重建 / .aac 可播）
#   第二部分：原样重放 r6-pickers.ps1（三条真实文件选择器路径 + 一次性提示不被快照覆盖）
#   第三部分：R6 未覆盖、但同一批代码路径上的相互作用（面板互斥、Escape 分层、初始焦点、
#             提示到期消退、轨道列表跟随引擎而不是跟随数量）
# "动作被调用"与"应用状态被改变"分开举证：所有点击之后都断言界面状态。
# -OnlyC3 跳过前两部分的重放（每次重放要跑一整轮 GUI，改第三部分的判据时用它快速迭代）；
# 正式留证必须不带这个开关跑一遍完整脚本。
param([switch]$OnlyC3)
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
Import-Module ($R + 'windows\tools\acceptance\lib\MhPicker.psm1') -Force -WarningAction SilentlyContinue
$ev = $R + 'windows\tools\acceptance\evidence\r7-c-regress'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}
function Get-Text([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if ($e) { return $e.Current.Name }
  return $null
}
function Wait-Element([string]$id, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
    if ($e) { return $e }
    Start-Sleep -Milliseconds 250
  }
  return $null
}
function Invoke-Id([string]$id) {
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if (-not $e) { return 'NO-ELEMENT' }
  try { return (Invoke-MhElement -Element $e) } catch { return ('ERROR: ' + $_.Exception.Message) }
}
function Test-PanelOpen([string]$id) {
  # "面板开着"必须是"元素存在且不是 offscreen"。元素从来不存在时 IsOffscreen 无从谈起，
  # 所以先判存在，再判可见 —— 跳过第一步会让"从未出现的面板"被算成"已关闭"。
  $e = Find-MhAppElement -AutomationId $id -IncludeOffscreen
  if (-not $e) { return $false }
  return (-not $e.Current.IsOffscreen)
}
function Get-TrackRows([string]$listId) {
  $list = Find-MhAppElement -AutomationId $listId -IncludeOffscreen
  if (-not $list) { return @() }
  return @($list.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    ForEach-Object { $_.Current.Name } | Where-Object { $_ })
}
function Wait-Track([string]$listId, [string]$needle, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((Get-TrackRows $listId) -match [regex]::Escape($needle)) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
# 位置文本不存在时 positionSeconds 是 $null，参与算术会被当成 0，从而凭空造出一次「时间冻结」。
function Get-Position {
  $s = Get-MhPlayerState -SkipSlow
  if ($null -eq $s.positionSeconds) { return -1 }
  return [double]$s.positionSeconds
}
function Read-RunResult([string]$path) {
  if (-not (Test-Path $path)) { return $null }
  $rows = @(Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json)
  return [pscustomobject]@{
    total = $rows.Count
    pass  = @($rows | Where-Object { $_.pass }).Count
    fails = @($rows | Where-Object { -not $_.pass } | ForEach-Object { $_.name })
  }
}

if (-not $OnlyC3) {
Write-Host '===== 第一部分：重放 r6-audio.ps1 ====='
Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 800
$audioOut = & pwsh -NoProfile -File ($R + 'windows\tools\acceptance\r6-audio.ps1') 2>&1
$audioOut | Set-Content -Path ($ev + '\r6-audio-replay.log') -Encoding UTF8
$audioLine = @($audioOut | Where-Object { $_ -like '*RESULT*' }) | Select-Object -Last 1
$audioRes = Read-RunResult ($R + 'windows\tools\acceptance\evidence\r6-audio\results.json')
Write-Host ('  ' + $audioLine)
Check 'C1 r6-audio 重放：12/12 全部通过' ([bool]($audioLine -match 'RESULT 12/12') -and $audioRes.pass -eq 12) ('line=' + $audioLine + ' fails=' + ($audioRes.fails -join ', '))
$audioText = ($audioOut | Out-String)
Check 'C1 r6-audio 走的是主路径：真有音频输出峰值' ([bool]($audioText -match 'PEAK=')) ('PEAK 行=' + (@($audioOut | Where-Object { $_ -match 'PEAK=' }) -join ' | '))

Write-Host '===== 第二部分：重放 r6-pickers.ps1 ====='
Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 800
$pickOut = & pwsh -NoProfile -File ($R + 'windows\tools\acceptance\r6-pickers.ps1') 2>&1
$pickOut | Set-Content -Path ($ev + '\r6-pickers-replay.log') -Encoding UTF8
$pickLine = @($pickOut | Where-Object { $_ -like '*RESULT*' }) | Select-Object -Last 1
$pickRes = Read-RunResult ($R + 'windows\tools\acceptance\evidence\r6-pickers\results.json')
Write-Host ('  ' + $pickLine)
Check 'C2 r6-pickers 重放：18/18 全部通过' ([bool]($pickLine -match 'RESULT 18/18') -and $pickRes.pass -eq 18) ('line=' + $pickLine + ' fails=' + ($pickRes.fails -join ', '))
}

Write-Host '===== 第三部分：同一批代码路径上的相互作用 ====='
Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 800
$movie = 'D:\mh-media\playback-180s.mp4'
$app = Start-MhApp -ArgumentList @($movie) -ResetTrace -TimeoutSec 90
$title = Wait-Element 'TitleText' 60
Check 'C3 影片起播（后续所有面板/焦点检查的前提）' ($null -ne $title) ('title=' + (Get-Text 'TitleText'))
Start-Sleep -Seconds 3

Write-Host '  -- 面板互斥与 toggle'
Check 'C3.1 起播时没有任何面板打开' ((-not (Test-PanelOpen 'panel.audio')) -and (-not (Test-PanelOpen 'panel.subtitle')) -and (-not (Test-PanelOpen 'panel.queue'))) ''
Invoke-Id 'player.audiopanel' | Out-Null
Start-Sleep -Milliseconds 900
Check 'C3.1 打开音轨面板：面板出现且可见' (Test-PanelOpen 'panel.audio') ('rows=' + ((Get-TrackRows 'panel.audio') -join ' | '))
Invoke-Id 'player.subtitlepanel' | Out-Null
Start-Sleep -Milliseconds 900
Check 'C3.1 再打开字幕面板：字幕可见、音轨同时收起（互斥）' ((Test-PanelOpen 'panel.subtitle') -and (-not (Test-PanelOpen 'panel.audio'))) ('sub=' + (Test-PanelOpen 'panel.subtitle') + ' audio=' + (Test-PanelOpen 'panel.audio'))
Invoke-Id 'player.queuepanel' | Out-Null
Start-Sleep -Milliseconds 900
Check 'C3.1 再打开队列面板：队列可见、字幕收起' ((Test-PanelOpen 'panel.queue') -and (-not (Test-PanelOpen 'panel.subtitle'))) ('queue=' + (Test-PanelOpen 'panel.queue') + ' sub=' + (Test-PanelOpen 'panel.subtitle'))
$again = Invoke-Id 'player.queuepanel'
Start-Sleep -Milliseconds 900
Check 'C3.1 同一个按钮再点一次就把队列面板关掉（toggle 真的存在）' (($again -ne 'NO-ELEMENT') -and (-not (Test-PanelOpen 'panel.queue'))) ('invoke=' + $again + ' queueOpen=' + (Test-PanelOpen 'panel.queue'))
Save-MhScreenshot -Path ($ev + '\c3-panels.png') | Out-Null

Write-Host '  -- Escape 分层'
Invoke-Id 'player.audiopanel' | Out-Null
Start-Sleep -Milliseconds 900
$openBeforeEsc = Test-PanelOpen 'panel.audio'
Send-MhKeys -Keys 'ESC' | Out-Null
Start-Sleep -Milliseconds 900
$openAfterEsc = Test-PanelOpen 'panel.audio'
$stillInPlayer = $null -ne (Find-MhAppElement -AutomationId 'player.seekslider' -IncludeOffscreen)
Check 'C3.2 Escape 第一层：关面板，不退出播放页' ($openBeforeEsc -and (-not $openAfterEsc) -and $stillInPlayer) ('before=' + $openBeforeEsc + ' after=' + $openAfterEsc + ' stillInPlayer=' + $stillInPlayer)
Send-MhKeys -Keys 'ESC' | Out-Null
Start-Sleep -Seconds 2
$leftPlayer = $null -eq (Find-MhAppElement -AutomationId 'player.seekslider' -IncludeOffscreen)
Check 'C3.2 Escape 第二层：没有面板可关时才退出播放页' $leftPlayer ('seeksliderGone=' + $leftPlayer)

Write-Host '  -- 初始焦点与 Space'
# 上一段已经把播放页退出了，这里要再起一个应用实例。不先关掉旧实例的话，两个进程会同时
# 把窗口与引擎子窗口暴露给 UIA，元素查找与截屏都退化成"随便命中一个"。
Stop-MhAppGracefully -TimeoutSec 25 | Out-Null
Start-Sleep -Milliseconds 800
$app2 = Start-MhApp -ArgumentList @($movie) -ResetTrace -TimeoutSec 90
$t2 = Wait-Element 'TitleText' 60
Start-Sleep -Seconds 3
# 合成键盘事件只落到前台窗口。Start-MhApp 用 Win32_Process.Create 启动，进程拿不到前台；不显式
# 前置时 Space 打进了控制台（上一轮实测 focused 元素属于 python.exe），于是"暂停"根本没发生，
# 而"恢复播放"因为时间一直在走反而假通过。焦点判据同时要求 pid 属于本应用，才算真的落在应用里。
Set-MhForeground -Hwnd (Get-MhWindowHandle) | Out-Null
Start-Sleep -Milliseconds 600
$focused = Get-MhFocused
Check 'C3.3 进入播放页后焦点落在视频区而不是某个按钮上（否则 Space 会被按钮吃掉）' (($null -ne $focused) -and ($focused.type -ne 'Button') -and ($focused.pid -eq $app2.pid)) ('focused=' + $(if ($focused) { $focused.type + ' / ' + $focused.name + ' / id=' + $focused.id + ' / pid=' + $focused.pid + ' app=' + $app2.pid } else { 'none' }))
$p1 = Get-Position
Start-Sleep -Milliseconds 1200
$p2 = Get-Position
Set-MhForeground -Hwnd (Get-MhWindowHandle) | Out-Null
Send-MhKeys -Keys 'SPACE' | Out-Null
Start-Sleep -Milliseconds 1200
$p3 = Get-Position
Start-Sleep -Milliseconds 1500
$p4 = Get-Position
Set-MhForeground -Hwnd (Get-MhWindowHandle) | Out-Null
Send-MhKeys -Keys 'SPACE' | Out-Null
Start-Sleep -Milliseconds 1200
$p5 = Get-Position
Start-Sleep -Milliseconds 1200
$p6 = Get-Position
Check 'C3.3 起播后时间在推进（对照组，否则"冻结"没有意义）' ($p2 -gt $p1) ('p1=' + $p1 + ' p2=' + $p2)
Check 'C3.3 Space 暂停：时间冻结' (($p4 - $p3) -lt 0.35) ('p3=' + $p3 + ' p4=' + $p4 + ' delta=' + [math]::Round($p4 - $p3, 2))
Check 'C3.3 再按 Space 恢复播放' (($p6 - $p5) -gt 0.35) ('p5=' + $p5 + ' p6=' + $p6 + ' delta=' + [math]::Round($p6 - $p5, 2))

Write-Host '  -- 外挂字幕：列表跟随引擎，提示到期消退'
Invoke-Id 'player.subtitlepanel' | Out-Null
Start-Sleep -Milliseconds 800
Check 'C3.4 字幕面板打开' (Test-PanelOpen 'panel.subtitle') ''
$btn = Find-MhAppElement -Name '加载外挂字幕…' -IncludeOffscreen
if ($btn) { Invoke-MhElement -Element $btn | Out-Null }
$dlg = Wait-MhPickerDialog -AppPid $app2.pid -TimeoutSec 30
Check 'C3.4 外挂字幕对话框出现' ($dlg -ne [IntPtr]::Zero) ('title="' + $(if ($dlg -ne [IntPtr]::Zero) { Get-MhPickerTitle -Dialog $dlg } else { '' }) + '"')
if ($dlg -ne [IntPtr]::Zero) {
  Set-MhPickerPath -Dialog $dlg -Path 'D:\mh-media\subtitle-alpha.srt' | Out-Null
  Confirm-MhPicker -Dialog $dlg | Out-Null
  Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15 | Out-Null
}
Check 'C3.4 轨道列表出现刚加载的文件名' (Wait-Track 'panel.subtitle' 'subtitle-alpha.srt' 25) ((Get-TrackRows 'panel.subtitle') -join ' | ')
$hintNow = Get-Text 'player.subtitlehint'
Check 'C3.4 提示里保留已加载的文件名' ([bool]($hintNow -like '*subtitle-alpha.srt*')) ('hint=' + $hintNow)

$btn2 = Find-MhAppElement -Name '加载外挂字幕…' -IncludeOffscreen
if ($btn2) { Invoke-MhElement -Element $btn2 | Out-Null }
$dlg2 = Wait-MhPickerDialog -AppPid $app2.pid -TimeoutSec 30
if ($dlg2 -ne [IntPtr]::Zero) {
  Set-MhPickerPath -Dialog $dlg2 -Path 'D:\mh-media\subtitle-beta.srt' | Out-Null
  Confirm-MhPicker -Dialog $dlg2 | Out-Null
  Wait-MhPickerClosed -Dialog $dlg2 -TimeoutSec 15 | Out-Null
}
Check 'C3.4 第二个外挂字幕也进了列表（列表跟随引擎新增，不是只显示第一份）' (Wait-Track 'panel.subtitle' 'subtitle-beta.srt' 25) ((Get-TrackRows 'panel.subtitle') -join ' | ')
# 每行由多个 TextBlock 组成，Get-TrackRows 会把同一行的文本读出来两次；判"出现几次"之前先去重，
# 否则一次加载会被数成两次，而真正的重复行又会和这个伪影混在一起看不出来。
$rows = @(Get-TrackRows 'panel.subtitle' | Select-Object -Unique)
$hint2 = Get-Text 'player.subtitlehint'
Check 'C3.4 提示更新为最后加载的文件' ([bool]($hint2 -like '*subtitle-beta.srt*')) ('hint=' + $hint2)
Check 'C3.4 两份文件同时存在且各出现一次（没有重复行）' ((@($rows | Where-Object { $_ -like '*subtitle-alpha.srt*' }).Count -eq 1) -and (@($rows | Where-Object { $_ -like '*subtitle-beta.srt*' }).Count -eq 1)) ('rows=' + ($rows -join ' | '))

Start-Sleep -Seconds 9
$hint3 = Get-Text 'player.subtitlehint'
Check 'C3.5 一次性提示在有效期后消退为基线文案' ((-not ($hint3 -like '*已加载*')) -and ($hint3 -like '*字幕轨道*')) ('hint=' + $hint3)

Save-MhScreenshot -Path ($ev + '\c3-subtitles.png') | Out-Null
Stop-MhAppGracefully | Out-Null

$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' passed, ' + $fail + ' failed')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\checks.json') -Encoding UTF8
if ($fail -gt 0) { exit 1 } else { exit 0 }

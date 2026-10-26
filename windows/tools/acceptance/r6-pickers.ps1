# R6 验收 B：三条真实文件选择器路径。
#   P1 文件→打开视频…（.aac）  P2 文件→打开文件夹…  P3 播放器→加载外挂字幕…
# 对话框驱动全部走 windows/tools/acceptance/lib/MhPicker.psm1（Win32），不碰对话框的 UIA 子树。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force
Import-Module ($R + 'windows\tools\acceptance\lib\MhPicker.psm1') -Force
$ev = $R + 'windows\tools\acceptance\evidence\r6-pickers'
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

# ---------------------------------------------------------------- P1
Write-Host '===== P1 文件→打开视频…（.aac）====='
Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 600
$app = Start-MhApp -ResetTrace -TimeoutSec 60
Check 'P1 菜单项可达' (Invoke-Menu 'menu.openvideo') ('pid=' + $app.pid)
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'P1 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ('title="' + $(if ($dlg -ne [IntPtr]::Zero) { Get-MhPickerTitle -Dialog $dlg } else { '' }) + '"')
$wrote = $null
if ($dlg -ne [IntPtr]::Zero) {
  Save-MhScreenshot -Path ($ev + '\p1-dialog-open.png') | Out-Null
  $wrote = Set-MhPickerPath -Dialog $dlg -Path 'D:\mh-media\Audio\Sine Tone.aac'
  Write-Host ('  文件名框: ok=' + $wrote.ok + ' before="' + $wrote.before + '" after="' + $wrote.after + '" ' + $wrote.reason)
  $clicked = Confirm-MhPicker -Dialog $dlg
  Write-Host ('  确认按钮点击=' + $clicked)
  Check 'P1 对话框被确认关闭' (Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15) ''
}
$title = Wait-Element 'TitleText' 40
$st = Get-MhPlayerState -SkipSlow
$trace = Get-MhLog
Save-MhScreenshot -Path ($ev + '\p1-now-playing.png') | Out-Null
Check 'P1 trace: 选中后进入播放' ([bool]($trace -match 'menu:openvideo-selected')) ('cancelled=' + [bool]($trace -match 'menu:openvideo-cancelled') + ' failed=' + [bool]($trace -match 'menu:openvideo-failed'))
Check 'P1 选中的 .aac 真的在播放器里' ([bool]($st.title -eq 'Sine Tone' -and $st.durationSeconds -ge 60)) ('title=' + $st.title + ' duration=' + $st.duration + ' technical=' + $st.technical)

# ---------------------------------------------------------------- P2
Write-Host '===== P2 文件→打开文件夹… ====='
Stop-MhAppGracefully | Out-Null; Start-Sleep -Milliseconds 600
$app = Start-MhApp -ResetTrace -TimeoutSec 60
Check 'P2 菜单项可达' (Invoke-Menu 'menu.openfolder') ('pid=' + $app.pid)
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'P2 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ('title="' + $(if ($dlg -ne [IntPtr]::Zero) { Get-MhPickerTitle -Dialog $dlg } else { '' }) + '"')
if ($dlg -ne [IntPtr]::Zero) {
  Save-MhScreenshot -Path ($ev + '\p2-dialog-open.png') | Out-Null
  $wrote = Set-MhPickerPath -Dialog $dlg -Path 'D:\mh-media\Audio'
  Write-Host ('  路径框: ok=' + $wrote.ok + ' after="' + $wrote.after + '" ' + $wrote.reason)
  Write-Host ('  确认按钮点击=' + (Confirm-MhPicker -Dialog $dlg))
  Check 'P2 对话框被确认关闭' (Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15) ''
}
Start-Sleep -Seconds 2
$trace = Get-MhLog
Check 'P2 trace: 文件夹已选中' ([bool]($trace -match 'open-folder:selected')) ('cancelled=' + [bool]($trace -match 'open-folder:cancelled') + ' failed=' + [bool]($trace -match 'open-folder:failed'))
$settings = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$raw = if (Test-Path $settings) { Get-Content -Raw -Encoding UTF8 $settings } else { '' }
Check 'P2 文件夹持久化到设置' ([bool]($raw -match [regex]::Escape('mh-media\\Audio'))) ('settings=' + $settings)
# 本地库页面：文件夹行绑定的就是完整路径（LocalLibraryPage.xaml FolderList），
# 用"文件名出现"作判据会在扫描未完成时给出假阴性，所以断言目录行本身。
$row = $null
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) { $row = Find-MhAppElement -Contains 'mh-media\Audio' -IncludeOffscreen; if ($row) { break }; Start-Sleep -Milliseconds 500 }
Save-MhScreenshot -Path ($ev + '\p2-library.png') | Out-Null
Check 'P2 本地库页面列出该文件夹' ($null -ne $row) ('element="' + $(if ($row) { $row.Current.Name } else { '' }) + '"')
$names = @(Get-MhAppElementAll -IncludeOffscreen | ForEach-Object { $_.Current.Name } | Where-Object { $_ } | Select-Object -First 60)
$names -join ' | ' | Set-Content -Path ($ev + '\p2-elements.txt') -Encoding UTF8

# ---------------------------------------------------------------- P3
Write-Host '===== P3 播放器→加载外挂字幕… ====='
Stop-MhAppGracefully | Out-Null; Start-Sleep -Milliseconds 600
$movie = 'D:\mh-media\Movies\Hevc Movie (2021)\Hevc Movie (2021).mp4'
$app = Start-MhApp -ArgumentList @($movie) -ResetTrace -TimeoutSec 60
Check 'P3 影片起播' ($null -ne (Wait-Element 'TitleText' 40)) ''
Start-Sleep -Seconds 2
$sb = Find-MhAppElement -AutomationId 'player.subtitlepanel'
if ($sb) { Invoke-MhElement -Element $sb | Out-Null; Start-Sleep -Milliseconds 800 }
$listBefore = Find-MhAppElement -AutomationId 'panel.subtitle' -IncludeOffscreen
$countBefore = if ($listBefore) { $listBefore.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition).Count } else { -1 }
$btn = Find-MhAppElement -Name '加载外挂字幕…'
Check 'P3 加载外挂字幕按钮可达' ($null -ne $btn) ''
if ($btn) { Invoke-MhElement -Element $btn | Out-Null }
$dlg = Wait-MhPickerDialog -AppPid $app.pid -TimeoutSec 30
Check 'P3 选择器对话框出现' ($dlg -ne [IntPtr]::Zero) ('title="' + $(if ($dlg -ne [IntPtr]::Zero) { Get-MhPickerTitle -Dialog $dlg } else { '' }) + '"')
if ($dlg -ne [IntPtr]::Zero) {
  $wrote = Set-MhPickerPath -Dialog $dlg -Path 'D:\mh-media\playback-180s.srt'
  Write-Host ('  路径框: ok=' + $wrote.ok + ' after="' + $wrote.after + '" ' + $wrote.reason)
  Write-Host ('  确认按钮点击=' + (Confirm-MhPicker -Dialog $dlg))
  Check 'P3 对话框被确认关闭' (Wait-MhPickerClosed -Dialog $dlg -TimeoutSec 15) ''
}
Start-Sleep -Seconds 3
$listAfter = Find-MhAppElement -AutomationId 'panel.subtitle' -IncludeOffscreen
$countAfter = if ($listAfter) { $listAfter.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition).Count } else { -1 }
# 主判据：轨道名里出现所选文件名 —— 只数"轨数 +1"无法证明加进去的就是用户选的那个文件。
$track = $null
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
  if ($listAfter) {
    $track = $listAfter.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
      Where-Object { $_.Current.Name -like '*playback-180s*' } | Select-Object -First 1
  }
  if ($track) { break }
  Start-Sleep -Milliseconds 400
}
# 面板提示：8 秒内必须还写着所选文件名。快照每 100ms 重写一次提示文本，
# 若刷新逻辑把一次性结果覆盖掉，用户就看不到"加载了哪个文件"（这是修掉的真缺陷，得钉住）。
$hint = Find-MhAppElement -AutomationId 'player.subtitlehint' -IncludeOffscreen
$hintText = if ($hint) { $hint.Current.Name } else { '' }
Save-MhScreenshot -Path ($ev + '\p3-subtitle-loaded.png') | Out-Null
Check 'P3 字幕轨列表出现所选文件' ($null -ne $track) ('track="' + $(if ($track) { $track.Current.Name } else { '' }) + '"')
Check 'P3 面板提示保留已加载的文件名' ([bool]($hintText -like '*playback-180s*')) ('hint="' + $hintText + '"')
Check 'P3 字幕轨数量增加' ([bool]($countAfter -gt $countBefore -and $countBefore -ge 0)) ('{0} -> {1}' -f $countBefore, $countAfter)

Stop-MhAppGracefully | Out-Null
$pass = @($checks | Where-Object { $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' PASS')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\results.json') -Encoding UTF8

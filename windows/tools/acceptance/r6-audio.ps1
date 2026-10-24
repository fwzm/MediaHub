# R6 验收 A：音频文件（.aac）经命令行进入播放，并验证音轨面板可开可关、字幕列表不再每帧重建。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force
$ev = $R + 'windows\tools\acceptance\evidence\r6-audio'
New-Item -ItemType Directory -Force -Path $ev | Out-Null

$checks = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$pass, [string]$detail) {
  $checks.Add([pscustomobject]@{ name = $name; pass = $pass; detail = $detail })
  Write-Host (('[{0}] {1} :: {2}' -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $name, $detail))
}

$target = 'D:\mh-media\Audio\Sine Tone.aac'
Write-Host ('target=' + $target + ' exists=' + (Test-Path $target) + ' bytes=' + (Get-Item $target).Length)

Stop-MhAppForced | Out-Null
Start-Sleep -Milliseconds 800
$started = Start-MhApp -ArgumentList @($target) -ResetTrace -TimeoutSec 60
Write-Host ('pid=' + $started.pid)

# 等到播放器页真的出现（TitleText），而不是靠固定睡眠猜
$deadline = (Get-Date).AddSeconds(45)
$title = $null
while ((Get-Date) -lt $deadline) {
  $title = Find-MhAppElement -AutomationId 'TitleText'
  if ($title) { break }
  Start-Sleep -Milliseconds 300
}
Check 'player page opened from command line' ($null -ne $title) ('title=' + $(if ($title) { $title.Current.Name } else { '<none>' }))

$trace = (Get-MhLog) | Where-Object { $_ -match 'cli:|playback' }
Write-Host '--- trace ---'; $trace | ForEach-Object { Write-Host ('  ' + $_) }
Check 'trace records cli:opening' ([bool]($trace -match 'cli:opening')) ('lines=' + $trace.Count)

# 1) 音频真的在发声：在主路径上取真实音频会话峰值（而不是只看时间在走）
$peak = Get-MhAudioPeak
Write-Host ('audio peak: ' + $peak.raw)
Check 'audio is audible (peak > 0)' ([bool]($peak.peak -ne 'NO_SESSION' -and [double]$peak.peak -gt 0.001)) $peak.raw

# 2) 时长与编码信息来自引擎
$st1 = Get-MhPlayerState -SkipSlow
Start-Sleep -Seconds 3
$st2 = Get-MhPlayerState -SkipSlow
Write-Host ('state1=' + ($st1 | ConvertTo-Json -Compress -Depth 3))
Write-Host ('state2=' + ($st2 | ConvertTo-Json -Compress -Depth 3))
Check 'title is the file name' ($st1.title -eq 'Sine Tone') ('title=' + $st1.title)
Check 'duration parsed from .aac' ([bool]($st1.durationSeconds -ge 60)) ('duration=' + $st1.duration)
Check 'technical summary present' (-not [string]::IsNullOrWhiteSpace($st1.technical)) ('technical=' + $st1.technical)
Check 'playback clock advances' ([bool]($st2.positionSeconds -gt $st1.positionSeconds)) (('{0} -> {1}' -f $st1.position, $st2.position))

Save-MhScreenshot -Path ($ev + '\00-audio-playing.png') | Out-Null

# 3) 音轨面板：开 → 关（此前只能开不能关）
$btn = Find-MhAppElement -AutomationId 'player.audiopanel'
Check 'audio panel button exists' ($null -ne $btn) ''
if ($btn) {
  Invoke-MhElement -Element $btn | Out-Null
  Start-Sleep -Milliseconds 700
  $open = Find-MhAppElement -AutomationId 'panel.audio' -IncludeOffscreen
  Save-MhScreenshot -Path ($ev + '\01-audio-panel-open.png') | Out-Null
  Check 'audio panel opens' ($null -ne $open) ('offscreen=' + $(if ($open) { $open.Current.IsOffscreen } else { 'n/a' }))
  $btn2 = Find-MhAppElement -AutomationId 'player.audiopanel'
  if ($btn2) { Invoke-MhElement -Element $btn2 | Out-Null }
  Start-Sleep -Milliseconds 700
  $after = Find-MhAppElement -AutomationId 'panel.audio' -IncludeOffscreen
  # 先证明它开过，再证明它关了。元素从未存在时"消失"判据会假通过。
  $hidden = ($null -ne $open) -and (($null -eq $after) -or $after.Current.IsOffscreen)
  Save-MhScreenshot -Path ($ev + '\02-audio-panel-closed.png') | Out-Null
  Check 'audio panel closes on second click' $hidden ('found=' + ($null -ne $after) + ' offscreen=' + $(if ($after) { $after.Current.IsOffscreen } else { 'n/a' }))
}

# 4) 字幕列表不再每帧重建：同一列表项在 1.2 秒内的 RuntimeId 必须保持不变。
#    缺陷形态是 ApplySnapshot 每 100ms 判定"数量不一致"于是拆掉重建，重建会产生新的 RuntimeId。
$sub = Find-MhAppElement -AutomationId 'player.subtitlepanel'
if ($sub) { Invoke-MhElement -Element $sub | Out-Null; Start-Sleep -Milliseconds 700 }
# SubtitleList 现在显式带 AutomationId=panel.subtitle（原来的 x:Name 隐式 id 被覆盖）。
$list = Find-MhAppElement -AutomationId 'panel.subtitle' -IncludeOffscreen
$ids = @()
if ($list) {
  for ($i = 0; $i -lt 6; $i++) {
    $items = $list.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
    $sig = @($items | ForEach-Object { ($_.GetRuntimeId() -join '.') }) -join '|'
    $ids += ('n=' + $items.Count + ':' + $sig)
    Start-Sleep -Milliseconds 200
  }
}
$unique = ($ids | Select-Object -Unique)
Write-Host ('subtitle list signatures: ' + ($unique -join ' ; '))
Check 'subtitle panel is reachable' ($null -ne $list) ('id=panel.subtitle')
Check 'subtitle list is stable (no per-frame rebuild)' ([bool]($list -and $unique.Count -eq 1)) ('distinct=' + $unique.Count + ' samples=' + $ids.Count)

$st3 = Get-MhPlayerState -SkipSlow
Write-Host ('final position=' + $st3.position + ' duration=' + $st3.duration)

Stop-MhAppGracefully | Out-Null
$pass = @($checks | Where-Object { $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' PASS')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\results.json') -Encoding UTF8

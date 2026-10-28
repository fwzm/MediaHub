# R7 验收 B2（失败路径）：单项失败跳项、连续失败上限、AutoPlayNext=false、命令行非法输入。
$ErrorActionPreference = 'Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Import-Module ($R + 'windows\tools\acceptance\lib\MhUia.psm1') -Force
$ev = $R + 'windows\tools\acceptance\evidence\r7-b2-failure'
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
function Set-AutoPlayNext([bool]$value) {
  $path = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
  $obj = $null
  if (Test-Path $path) { $obj = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json }
  if ($null -eq $obj) { $obj = [pscustomobject]@{} }
  $obj | Add-Member -NotePropertyName AutoPlayNext -NotePropertyValue $value -Force
  ($obj | ConvertTo-Json -Depth 8) | Set-Content -Path $path -Encoding UTF8
  return (Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json).AutoPlayNext
}

$a = 'D:\mh-media\queue-a.mp4'
$b = 'D:\mh-media\queue-b.mp4'
$c = 'D:\mh-media\queue-c.mp4'
$bad1 = 'D:\mh-media\broken-1.mp4'
$bad2 = 'D:\mh-media\broken-2.mp4'
$bad3 = 'D:\mh-media\broken-3.mp4'

Write-Host '===== F1 无法播放的项被跳过 ====='
Write-Host ('AutoPlayNext=' + (Set-AutoPlayNext $true))
Stop-MhAppForced | Out-Null; Start-Sleep -Milliseconds 800
$app = Start-MhApp -ArgumentList @($a, $bad1, $c) -ResetTrace -TimeoutSec 90
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) { if ((Get-Text 'TitleText') -eq 'queue-a') { break }; Start-Sleep -Milliseconds 300 }
Check 'F1 三项队列就绪 1/3' (Wait-Text 'player.queuecount' '队列 1/3' 20) ('actual=' + (Get-Text 'player.queuecount'))
Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.queuenext' -IncludeOffscreen) | Out-Null
Check 'F1 跳过损坏项后播 queue-c' (Wait-Text 'TitleText' 'queue-c' 45) ('actual=' + (Get-Text 'TitleText'))
Check 'F1 跳过损坏项后计数 3/3' (Wait-Text 'player.queuecount' '队列 3/3' 20) ('actual=' + (Get-Text 'player.queuecount'))
$trace = Get-MhLog
Check 'F1 trace: 记录项失败' ([bool]($trace -match 'queue:item-failed')) (($trace | Where-Object { $_ -match 'queue:item-failed' }) -join ' / ')
Check 'F1 trace: 失败后以 replaced 切项' ([bool]($trace -match 'queue:switch reason=replaced title=queue-c')) (($trace | Where-Object { $_ -match 'queue:switch' }) -join ' / ')
$st = Get-MhPlayerState -SkipSlow
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
  if ($st.title -eq 'queue-c' -and $st.positionSeconds -ge 1) { break }
  Start-Sleep -Milliseconds 400
  $st = Get-MhPlayerState -SkipSlow
}
Check 'F1 跳项后真的在播放' ([bool]($st.positionSeconds -ge 1 -and $st.title -eq 'queue-c')) ('title=' + $st.title + ' position=' + $st.position)
Save-MhScreenshot -Path ($ev + '\f1-skip.png') | Out-Null

Write-Host '===== F2 连续 3 次失败后停止跳项 ====='
Stop-MhAppGracefully | Out-Null; Start-Sleep -Milliseconds 800
$app = Start-MhApp -ArgumentList @($bad1, $bad2, $bad3, $c) -ResetTrace -TimeoutSec 90
Start-Sleep -Seconds 22
$trace = Get-MhLog
$failed = @($trace | Where-Object { $_ -match 'queue:item-failed' }).Count
Check 'F2 记录到多次项失败' ([bool]($failed -ge 3)) ('failures=' + $failed)
Check 'F2 trace: 达到上限停止' ([bool]($trace -match 'queue:skip-capped')) (($trace | Where-Object { $_ -match 'queue:skip' }) -join ' / ')
Check 'F2 停止后没有继续跳到可播项' ([bool]((Get-Text 'TitleText') -ne 'queue-c')) ('title=' + (Get-Text 'TitleText'))
Save-MhScreenshot -Path ($ev + '\f2-cap.png') | Out-Null

Write-Host '===== F3 AutoPlayNext=false 时不自动跳项 ====='
Stop-MhAppGracefully | Out-Null; Start-Sleep -Milliseconds 800
Write-Host ('AutoPlayNext=' + (Set-AutoPlayNext $false))
$app = Start-MhApp -ArgumentList @($a, $b) -ResetTrace -TimeoutSec 90
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) { if ((Get-Text 'TitleText') -eq 'queue-a') { break }; Start-Sleep -Milliseconds 300 }
Check 'F3 两项队列就绪 1/2' (Wait-Text 'player.queuecount' '队列 1/2' 20) ('actual=' + (Get-Text 'player.queuecount'))
$slider = Find-MhAppElement -AutomationId 'player.seekslider'
$rv = Get-MhRangeValue $slider
if ($rv) { Set-MhRangeValue -Element $slider -Value ($rv.min + ($rv.max - $rv.min) * 0.97) | Out-Null }
Start-Sleep -Seconds 12
$trace = Get-MhLog
Check 'F3 trace: 明确记录自动跳项已关闭' ([bool]($trace -match 'queue:auto-advance-disabled')) (($trace | Where-Object { $_ -match 'queue:auto' }) -join ' / ')
Check 'F3 播完不自动跳到 queue-b' ([bool]((Get-Text 'TitleText') -eq 'queue-a')) ('title=' + (Get-Text 'TitleText'))
Check 'F3 计数仍为 1/2' ([bool]((Get-Text 'player.queuecount') -eq '队列 1/2')) ('actual=' + (Get-Text 'player.queuecount'))
Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.queuenext' -IncludeOffscreen) | Out-Null
Check 'F3 关闭自动跳项后手动「下一项」仍可用' (Wait-Text 'TitleText' 'queue-b' 30) ('actual=' + (Get-Text 'TitleText'))
Save-MhScreenshot -Path ($ev + '\f3-autonext-off.png') | Out-Null
Stop-MhAppGracefully | Out-Null
Write-Host ('AutoPlayNext restored=' + (Set-AutoPlayNext $true))

Write-Host '===== F4 命令行非法输入被逐条拒绝 ====='
Start-Sleep -Milliseconds 800
$missing = 'D:\mh-media\does-not-exist.mp4'
$unsupported = 'D:\mh-media\notes.txt'
Set-Content -Path $unsupported -Value 'not media' -Encoding UTF8
$app = Start-MhApp -ArgumentList @($a, $missing, $unsupported) -ResetTrace -TimeoutSec 90
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) { if ((Get-Text 'TitleText') -eq 'queue-a') { break }; Start-Sleep -Milliseconds 300 }
Start-Sleep -Seconds 2
$trace = Get-MhLog
Check 'F4 trace: 缺失文件被拒' ([bool]($trace -match 'cli:not-a-file')) (($trace | Where-Object { $_ -match 'cli:' }) -join ' / ')
Check 'F4 trace: 不支持的扩展名被拒' ([bool]($trace -match 'cli:unsupported-extension')) ''
Check 'F4 队列只含一个合法文件' ([bool]((Get-Text 'player.queuecount') -eq '队列 1/1')) ('actual=' + (Get-Text 'player.queuecount'))
Check 'F4 合法文件在播放' ([bool]((Get-Text 'TitleText') -eq 'queue-a')) ('title=' + (Get-Text 'TitleText'))
Save-MhScreenshot -Path ($ev + '\f4-cli-invalid.png') | Out-Null
Remove-Item -Force $unsupported -ErrorAction SilentlyContinue

$pass = @($checks | Where-Object { $_.pass }).Count
$fail = @($checks | Where-Object { -not $_.pass }).Count
Write-Host ('RESULT ' + $pass + '/' + $checks.Count + ' passed, ' + $fail + ' failed')
$checks | ConvertTo-Json -Depth 4 | Set-Content -Path ($ev + '\checks.json') -Encoding UTF8
(Get-MhLog) -join [Environment]::NewLine | Set-Content -Path ($ev + '\trace.log') -Encoding UTF8
Stop-MhAppGracefully | Out-Null
if ($fail -gt 0) { exit 1 } else { exit 0 }

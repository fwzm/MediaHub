$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'; $T = 'D:\deepseek_test\MediaHub_Win\.tools\'
$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Write-Host ('exe time=' + (Get-Item $exe).LastWriteTime + '  sha=' + (Get-FileHash -Algorithm SHA256 $exe).Hash.ToLowerInvariant().Substring(0,16))

$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mediahub:hub-test-pw'))
$code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
Write-Host ('WsgiDAV OPTIONS -> ' + $code)
if ($code -ne '200') {
  pwsh -NoProfile -File ($R + 'windows\tools\acceptance\webdav\start-server.ps1') 2>&1 | Select-Object -Last 2
  Start-Sleep -Seconds 7
  $code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
  Write-Host ('重试 OPTIONS -> ' + $code)
}
if ($code -ne '200') { Write-Host 'ABORT webdav'; exit 1 }

$tap = $R + 'windows\tools\acceptance\evidence\webdav\requests.jsonl'
$before = (Get-Content $tap | Measure-Object).Count
Write-Host ('tap 基线 = ' + $before)

Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
Start-Process -FilePath $exe | Out-Null
Start-Sleep -Seconds 26

$U = $T + 'uia-id.ps1'
function UIA { param([string[]]$a) (& pwsh -NoProfile -File $U @a 2>&1) }

Write-Host '--- 1) 媒体库 ---'
UIA @('-Action','invoke-name','-Name','媒体库') | Out-Null
Start-Sleep -Seconds 9
$ids = UIA @('-Action','ids')
$card = ($ids | Where-Object { $_ -match 'browse\|' -and $_ -match 'webdav' } | Select-Object -First 1)
Write-Host ('  card = ' + $card)
$cardId = ($card -split ' \| ')[0].Trim()
UIA @('-Action','invoke-id','-AutoId',$cardId) | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 12

Write-Host '--- 2) BrowsePage 条目 ---'
$ids2 = UIA @('-Action','ids')
$rows = $ids2 | Where-Object { $_ -match '^browse:' }
$rows | ForEach-Object { Write-Host ('  ' + $_) }
$video = ($rows | Where-Object { $_ -match '\.mp4|\.mkv|Chinese' } | Select-Object -First 1)
if (-not $video) { Write-Host '  未找到视频条目'; Write-Host 'DONE'; exit 1 }
$videoId = ($video -split ' \| ')[0].Trim()
Write-Host ('  选中: ' + $videoId)

Write-Host '--- 3) 点击视频 ---'
UIA @('-Action','invoke-id','-AutoId',$videoId) | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 22

Write-Host '--- 4) 播放器页 ---'
$pt = (UIA @('-Action','text')) -join ' | '
Write-Host ('  ' + $pt.Substring(0,[Math]::Min(600,$pt.Length)))

Write-Host '--- 5) 应用 trace ---'
Get-Content "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue | Select-String -Pattern 'playback' | Select-Object -Last 6 | ForEach-Object { Write-Host ('  ' + $_.Line) }

Write-Host '--- 6) 服务端请求 ---'
$new = @(Get-Content $tap | Select-Object -Skip $before)
Write-Host ('  新增 ' + $new.Count + '  PROPFIND=' + @($new | Where-Object { $_ -match 'PROPFIND' }).Count + '  GET=' + @($new | Where-Object { $_ -match '"GET"' }).Count + '  HEAD=' + @($new | Where-Object { $_ -match '"HEAD"' }).Count)
$new | ForEach-Object { Write-Host ('  ' + $_.Substring(0,[Math]::Min(230,$_.Length))) }
Write-Host 'DONE'

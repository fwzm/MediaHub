$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'; $T = 'D:\deepseek_test\MediaHub_Win\.tools\'
$NL = [string][char]10

Write-Host '== WsgiDAV 健康检查（OPTIONS）=='
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mediahub:hub-test-pw'))
$code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
Write-Host ('  OPTIONS -> ' + $code)
if ($code -ne '200') {
  pwsh -NoProfile -File ($R + 'windows\tools\acceptance\webdav\start-server.ps1') 2>&1 | Select-Object -Last 2
  Start-Sleep -Seconds 7
  $code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
  Write-Host ('  重试 OPTIONS -> ' + $code)
}
if ($code -ne '200') { Write-Host 'ABORT: WebDAV 不可用'; exit 1 }

$tap = $R + 'windows\tools\acceptance\evidence\webdav\requests.jsonl'
$before = 0
if (Test-Path $tap) { $before = (Get-Content $tap | Measure-Object).Count }
Write-Host ('  抓包基线 = ' + $before)

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Write-Host ('== 启动客户端 (' + (Get-Item $exe).LastWriteTime + ') ==')
Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
Start-Process -FilePath $exe | Out-Null
Start-Sleep -Seconds 24

$U = $T + 'uia-id.ps1'
Write-Host '== 媒体库页 =='
& pwsh -NoProfile -File $U -Action invoke-name -Name '媒体库' | Out-Null
Start-Sleep -Seconds 9

$ids = & pwsh -NoProfile -File $U -Action ids 2>&1
Write-Host '  卡片 id:'
$ids | Where-Object { $_ -match 'library\||browse\|' } | ForEach-Object { Write-Host ('    ' + $_) }

$target = ($ids | Where-Object { $_ -match 'browse\|' -and $_ -match 'webdav' } | Select-Object -First 1)
if (-not $target) { $target = ($ids | Where-Object { $_ -match 'browse\|' } | Select-Object -First 1) }
if (-not $target) { Write-Host 'ABORT: 没有 browse 卡片'; exit 1 }
$id = ($target -split ' \| ')[0].Trim()
Write-Host ('== 点击 ' + $id + ' ==')
& pwsh -NoProfile -File $U -Action invoke-id -AutoId $id 2>&1 | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 13

Write-Host '== BrowsePage 文本 =='
$t = (& pwsh -NoProfile -File $U -Action text 2>&1) -join ' | '
Write-Host ('  ' + $t.Substring(0,[Math]::Min(700,$t.Length)))

Write-Host '== 服务端请求 =='
$new = @(Get-Content $tap | Select-Object -Skip $before)
$pf = @($new | Where-Object { $_ -match 'PROPFIND' }).Count
Write-Host ('  新增 ' + $new.Count + ' 条，PROPFIND = ' + $pf)
$new | Select-Object -First 12 | ForEach-Object { Write-Host ('  ' + $_.Substring(0,[Math]::Min(200,$_.Length))) }
Write-Host 'DONE'

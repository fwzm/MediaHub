$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Write-Host ('exe time=' + (Get-Item $exe).LastWriteTime + ' sha=' + (Get-FileHash -Algorithm SHA256 $exe).Hash.ToLowerInvariant().Substring(0,16))

$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mediahub:hub-test-pw'))
$code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
Write-Host ('WsgiDAV OPTIONS -> ' + $code)
if ($code -ne '200') {
  pwsh -NoProfile -File ($R + 'windows\tools\acceptance\webdav\start-server.ps1') 2>&1 | Select-Object -Last 1
  Start-Sleep -Seconds 8
  $code = curl.exe -s -o NUL -w '%{http_code}' -X OPTIONS -H ('Authorization: Basic ' + $b64) 'http://127.0.0.1:8181/'
  Write-Host ('retry OPTIONS -> ' + $code)
}
if ($code -ne '200') { Write-Host 'ABORT webdav'; exit 1 }

Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { if ($_.MainWindowHandle -ne 0) { $_.CloseMainWindow() | Out-Null } } catch {} }
Start-Sleep -Seconds 5
Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch {} }
Start-Sleep -Seconds 3

Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 26
Write-Host ('app pid=' + $proc.Id)

$U = $R + 'windows\tools\acceptance\lib\uia-id.ps1'
function UIA { param([string[]]$a) (& pwsh -NoProfile -File $U @a 2>&1) }
function Show($label, $v) { Write-Host ('  ' + $label + ': ' + ($v -join ' | ')) }

Write-Host '--- 进入 WebDAV 视频 ---'
UIA @('-Action','invoke-name','-Name','媒体库') | Out-Null
Start-Sleep -Seconds 9
$ids = UIA @('-Action','ids')
$card = ($ids | Where-Object { $_ -match 'browse\|' -and $_ -match 'webdav' } | Select-Object -First 1)
$cardId = ($card -split ' \| ')[0].Trim()
UIA @('-Action','invoke-id','-AutoId',$cardId) | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 12
$rows = (UIA @('-Action','ids')) | Where-Object { $_ -match '^browse:' -and $_ -match 'mp4' }
$v = ($rows | Select-Object -First 1)
$vId = ($v -split ' \| ')[0].Trim()
UIA @('-Action','invoke-id','-AutoId',$vId) | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 20

function TimeLine { ((UIA @('-Action','text')) | Where-Object { $_ -match '^\d+:\d\d$' }) }
Write-Host '--- 播放中基线 ---'
Show 'time' (TimeLine)
Show 'range' (UIA @('-Action','get-range','-AutoId','player.seekslider'))
$t1 = TimeLine

Write-Host '--- A1 前跳：Slider -> 0.60 (片长 1:30 => 约 0:54) ---'
UIA @('-Action','set-range','-AutoId','player.seekslider','-Text','0.60') | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 4
Show 'time after fwd' (TimeLine)
$t2 = TimeLine
Start-Sleep -Seconds 6
Show 'time +6s' (TimeLine)
$t3 = TimeLine

Write-Host '--- A1 后跳：Slider -> 0.15 => 约 0:13 ---'
UIA @('-Action','set-range','-AutoId','player.seekslider','-Text','0.15') | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 4
Show 'time after back' (TimeLine)
$t4 = TimeLine

Write-Host '--- A2 退出播放页 ---'
# HttpListener is serviced by the http.sys kernel driver, so a loopback relay's port does NOT appear
# in the owning process's TCP listener table. Filtering Get-NetTCPConnection by PID therefore always
# returned nothing and could not tell "released" from "never existed". netsh http show servicestate
# reports the registrations the driver actually holds, and the startup trace records the endpoint the
# relay bound - both are used together.
$trace = Get-Content "$env:LOCALAPPDATAMediaHubstartup.log" -ErrorAction SilentlyContinue -Raw
$relayPorts = ([regex]::Matches($trace, '127.0.0.1:(d{4,5})')) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
Write-Host ('  trace 中出现的回环端口 = ' + ($relayPorts -join ','))
$before = (netsh http show servicestate view=requestq 2>$null | Select-String -Pattern '127\.0\.0\.1:(\d+)' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) -join ','
Write-Host ('  http.sys 注册(退出前) = ' + $before)
Write-Host ('  app 监听端口(退出前) = ' + $before)
UIA @('-Action','invoke-id','-AutoId','player.back') | ForEach-Object { Write-Host ('  ' + $_) }
Start-Sleep -Seconds 10
$after = (netsh http show servicestate view=requestq 2>$null | Select-String -Pattern '127\.0\.0\.1:(\d+)' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique) -join ','
Write-Host ('  app 监听端口(退出播放页后) = ' + $after)
Write-Host ('DONE')

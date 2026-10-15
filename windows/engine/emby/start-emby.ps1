<#
W5-F: start the official Emby Server (4.10.1.0) out of an isolated data directory on a port that
does not collide with the Jellyfin 12.2 instance already listening on 8096.
Artifact: embyserver-win-x64-4.10.1.0.7z
  size   133,879,144 bytes
  sha256 FBC951E1583310E1385E3D0779DE8823F5E9809F5BBCDAC4D7AC8F4033BD01FD
#>
$ErrorActionPreference = 'Continue'
$app      = 'D:\mh-servers\emby-install\app\system'
$data     = 'D:\mh-servers\data\emby'
$port     = 8097
$logDir   = Join-Path $data 'logs'

New-Item -ItemType Directory -Force -Path $data | Out-Null

$exe = Join-Path $app 'EmbyServer.exe'
Write-Output "exe: $exe"
(Get-Item $exe).VersionInfo | Select-Object FileVersion, ProductVersion, CompanyName | Format-List | Out-String

$existing = Get-Process -Name EmbyServer -ErrorAction SilentlyContinue
if ($existing) { Write-Output "already running: $($existing.Id -join ',')" }
else {
  Write-Output "=== starting EmbyServer.exe -programdata $data -port $port $(Get-Date -Format o) ==="
  Start-Process -FilePath $exe -ArgumentList @('-programdata', $data, '-port', "$port") -WorkingDirectory $app -WindowStyle Hidden
}

$deadline = (Get-Date).AddSeconds(180)
$up = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/System/Info/Public" -TimeoutSec 5
    $up = $true
    Write-Output "=== SERVER UP after $([int]((Get-Date) - $deadline.AddSeconds(180)).TotalSeconds*-1)s ==="
    $r | ConvertTo-Json -Depth 5
    break
  } catch { }
}
if (-not $up) { Write-Output "=== SERVER DID NOT COME UP in 180s ===" }

Write-Output "=== listening 809x ==="
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -ge 8090 -and $_.LocalPort -le 8100 } |
  Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize | Out-String

Write-Output "=== emby log files ==="
Get-ChildItem $logDir -ErrorAction SilentlyContinue | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize | Out-String
$newest = Get-ChildItem $logDir -Filter '*.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($newest) {
  Write-Output "=== tail of $($newest.Name) ==="
  Get-Content $newest.FullName -Tail 25 | Out-String
}

<#
.SYNOPSIS
Stops the W5-D WsgiDAV test server started by start-server.ps1.
#>
param(
  [string]$StateDir = "D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\webdav",
  [int]$Port = 8181
)
$pidFile = Join-Path $StateDir "server.pid"
if (-not (Test-Path $pidFile)) { Write-Host "no pid file"; exit 0 }
$procId = [int](Get-Content $pidFile -Raw).Trim()
$p = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($p) {
  Stop-Process -Id $procId -Force
  Start-Sleep -Milliseconds 500
  Write-Host "stopped pid=$procId"
} else {
  Write-Host "pid=$procId not running"
}
# A stuck child (cheroot thread holding the socket) is refused loudly rather than left behind.
$left = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($left) { Write-Host "WARNING: port $Port still listening (pid $($left.OwningProcess -join ','))" } else { Write-Host "port $Port free" }

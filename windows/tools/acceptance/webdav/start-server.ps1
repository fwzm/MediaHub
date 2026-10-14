<#
.SYNOPSIS
Starts the W5-D WsgiDAV test server fully detached and waits until it answers.

.DESCRIPTION
Two things this script learned the hard way and now does deliberately:

* The server process is created through Win32_Process.Create, not Start-Process. A Start-Process
  child inherits the launcher's stdout pipe, and because the server outlives the launcher the
  reader never sees EOF - every harness step that started the server hung. The created process
  inherits no handles and owns its own log file.
* Readiness is a real TCP connect, not a sleep, and the pid is re-checked after a settle delay so a
  server that binds and then dies is not reported as "started".

Writes start-server.json (pid, url, tap path, exact wsgidav/cheroot/python versions) next to the
request tap so every later step can say which server instance it was talking to.
#>
param(
  [string]$Root = "D:\mh-webdav\root",
  [int]$Port = 8181,
  [string]$StateDir = "D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\webdav",
  [string]$User = "mediahub",
  [string]$Password = "hub-test-pw",
  [switch]$Restart
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$pidFile = Join-Path $StateDir "server.pid"
$stateFile = Join-Path $StateDir "start-server.json"
$tap = Join-Path $StateDir "requests.jsonl"
$serverLog = Join-Path $StateDir "server.log"

if ($Restart) { & (Join-Path $PSScriptRoot "stop-server.ps1") -StateDir $StateDir -Port $Port | Out-Null }

if (Test-Path $pidFile) {
  $oldPid = 0
  [void][int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$oldPid)
  if ($oldPid -gt 0 -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
    Write-Host "already running pid=$oldPid"
    Get-Content $stateFile -Raw
    exit 0
  }
}

$script = Join-Path $PSScriptRoot "webdav_server.py"
$py = (Get-Command python).Source
$cmdLine = '"' + $py + '" -u "' + $script + '" --root "' + $Root + '" --port ' + $Port +
           ' --log "' + $tap + '" --user "' + $User + '" --password "' + $Password +
           '" --stdout "' + $serverLog + '"'

$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine      = $cmdLine
  CurrentDirectory = $StateDir
}
if ($created.ReturnValue -ne 0) { throw "Win32_Process.Create failed, returnValue=$($created.ReturnValue)" }
$serverPid = [int]$created.ProcessId
$serverPid | Set-Content -Path $pidFile -Encoding ascii

$deadline = (Get-Date).AddSeconds(30)
$ready = $false
while ((Get-Date) -lt $deadline) {
  if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) { break }
  try {
    $conn = New-Object System.Net.Sockets.TcpClient
    $conn.Connect("127.0.0.1", $Port)
    $conn.Close()
    $ready = $true
    break
  } catch { Start-Sleep -Milliseconds 250 }
}

if (-not $ready) {
  Write-Host "SERVER FAILED TO START (pid $serverPid)"
  if (Test-Path $serverLog) { Get-Content $serverLog -Raw }
  exit 1
}

# A server that binds and then dies must not be reported as healthy.
Start-Sleep -Seconds 2
if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
  Write-Host "SERVER DIED AFTER BINDING (pid $serverPid)"
  if (Test-Path $serverLog) { Get-Content $serverLog -Raw }
  exit 1
}

$version = (python -c "import wsgidav, cheroot, sys; print('wsgidav ' + wsgidav.__version__ + ' / cheroot ' + cheroot.__version__ + ' / python ' + sys.version.split()[0])")
$state = [ordered]@{
  pid     = $serverPid
  url     = "http://127.0.0.1:$Port/"
  root    = $Root
  user    = $User
  passwordLength = $Password.Length
  tap     = $tap
  log     = $serverLog
  version = $version
  python  = $py
  script  = $script
  cmdline = $cmdLine
  started = (Get-Date).ToString("s")
}
$state | ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8
Get-Content $stateFile -Raw

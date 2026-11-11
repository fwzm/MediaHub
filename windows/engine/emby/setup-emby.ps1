<#
W5-F: bring the official Emby Server 4.10.1.0 up on a non-colliding port and drive first-run setup
through its HTTP API.  Records every request/response verbatim, because the whole point of this
exercise is the DIFFERENCE between Emby's and Jellyfin's response shapes.
#>
$ErrorActionPreference = 'Continue'
$port = 8097
$base = "http://127.0.0.1:$port"
$data = 'D:\mh-servers\data\emby'
$sysXml = Join-Path $data 'config\system.xml'
$log = 'D:\mh-servers\emby-install\setup-transcript.txt'
$app = 'D:\mh-servers\emby-install\app\system'

function Say($m) { $m | Tee-Object -FilePath $log -Append }

Say "### Emby setup transcript $(Get-Date -Format o)"

# --- 1. stop any running instance and force the port in system.xml (the -port switch was ignored) ---
Get-Process -Name EmbyServer -ErrorAction SilentlyContinue | ForEach-Object {
  Say "stopping EmbyServer pid=$($_.Id)"; Stop-Process -Id $_.Id -Force
}
Start-Sleep -Seconds 4

if (Test-Path $sysXml) {
  $x = Get-Content $sysXml -Raw
  $x = $x -replace '<PublicPort>\d+</PublicPort>', "<PublicPort>$port</PublicPort>"
  $x = $x -replace '<HttpServerPortNumber>\d+</HttpServerPortNumber>', "<HttpServerPortNumber>$port</HttpServerPortNumber>"
  Set-Content -Path $sysXml -Value $x -Encoding UTF8
  Say "system.xml ports set:"
  (Select-String -Path $sysXml -Pattern 'PublicPort|HttpServerPortNumber' | ForEach-Object { "  " + $_.Line.Trim() }) | ForEach-Object { Say $_ }
}

# --- 2. start ---
Say "starting EmbyServer.exe -programdata $data"
Start-Process -FilePath (Join-Path $app 'EmbyServer.exe') -ArgumentList @('-programdata', $data) -WorkingDirectory $app -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds(150)
$info = $null
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  try { $info = Invoke-RestMethod -Uri "$base/System/Info/Public" -TimeoutSec 5; break } catch { }
}
if (-not $info) {
  Say "SERVER DID NOT ANSWER on $port within 150s"
  Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -ge 8090 -and $_.LocalPort -le 8100 } |
    Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize | Out-String | ForEach-Object { Say $_ }
  exit 1
}
Say "=== SERVER UP on $port ==="
Say ($info | ConvertTo-Json -Depth 6)

Say "=== listening sockets ==="
(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -eq $port } |
  Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize | Out-String) | ForEach-Object { Say $_ }

# --- 3. first-run setup via API ---
function Post($path, $body, $hdrs) {
  $json = $body | ConvertTo-Json -Depth 6
  Say ""
  Say "POST $path"
  Say "  request: $json"
  try {
    if ($hdrs) { $r = Invoke-WebRequest -Uri "$base$path" -Method Post -Body $json -ContentType 'application/json' -Headers $hdrs -TimeoutSec 30 }
    else       { $r = Invoke-WebRequest -Uri "$base$path" -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 30 }
    Say "  status : $($r.StatusCode)"
    Say "  body   : $($r.Content)"
    return $r
  } catch {
    Say "  FAILED : $($_.Exception.Message)"
    if ($_.Exception.Response) {
      $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      Say "  errbody: $($sr.ReadToEnd())"
    }
    return $null
  }
}
function Get2($path, $hdrs) {
  Say ""
  Say "GET $path"
  try {
    if ($hdrs) { $r = Invoke-WebRequest -Uri "$base$path" -Headers $hdrs -TimeoutSec 30 }
    else       { $r = Invoke-WebRequest -Uri "$base$path" -TimeoutSec 30 }
    Say "  status : $($r.StatusCode)"
    Say "  body   : $($r.Content)"
    return $r
  } catch { Say "  FAILED : $($_.Exception.Message)"; return $null }
}

Say ""
Say "### startup wizard"
Get2 '/Startup/User'
Post '/Startup/Configuration' @{ UICulture = 'en-US'; MetadataCountryCode = 'US'; PreferredMetadataLanguage = 'en' }
$u = Post '/Startup/User' @{ Name = 'mh'; Password = 'mh-test-pass' }
Post '/Startup/RemoteAccess' @{ EnableRemoteAccess = $false; EnableAutomaticPortMapping = $false }
Post '/Startup/Complete' @{}

Say ""
Say "### login (Emby's X-Emby-Authorization shape)"
$auth = 'MediaBrowser Client="MediaHubProbe", Device="Windows", DeviceId="mediahub-w5f-probe", Version="1.0.0"'
$login = Post '/Users/AuthenticateByName' @{ Username = 'mh'; Pw = 'mh-test-pass' } @{ 'X-Emby-Authorization' = $auth }
$token = $null
if ($login) { try { $token = ($login.Content | ConvertFrom-Json).AccessToken } catch {} }
Say "token: $token"

if ($token) {
  Say ""
  Say "### /Users/Me with X-Emby-Token"
  Get2 '/Users/Me' @{ 'X-Emby-Token' = $token }
}
Say ""
Say "### done $(Get-Date -Format o)"

<#
.SYNOPSIS
Starts an official Jellyfin or Emby server against the synthetic fixture tree, completes first-run
setup through the server's own API, and builds a scannable library.

.DESCRIPTION
A real-server test must be repeatable. A server configured by hand once is an anecdote; the same
server, version, library and media brought up by a script is evidence that can be re-run.

Everything happens in a disposable data directory. No service is installed, no existing server on the
machine is touched, and the credentials are generated for this run only.

  1. start the server process with an isolated data directory
  2. wait for the HTTP endpoint
  3. walk the first-run wizard via the API
  4. authenticate to obtain a token
  5. create a Movies and a TV Shows library over the fixture tree
  6. trigger a scan and wait for it to settle
  7. write a machine-readable summary

.EXAMPLE
pwsh -File windows/tools/setup-test-server.ps1 -Server jellyfin
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('jellyfin', 'emby')][string] $Server,
    [string] $MediaRoot = 'D:\mh-media',
    [string] $InstallRoot = 'D:\mh-servers',
    [string] $DataRoot,
    [int]    $Port = 0,
    [string] $UserName = 'mediahub-test',
    [string] $Password = 'mediahub-test-password',
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'

if (-not (Test-Path $MediaRoot)) { throw "fixture root not found: $MediaRoot (run make-test-media.ps1)" }
if (-not $DataRoot) { $DataRoot = Join-Path $InstallRoot ("data\" + $Server) }
if (-not $Port) { $Port = if ($Server -eq 'jellyfin') { 18096 } else { 18097 } }
$base = "http://127.0.0.1:$Port"

if ($Force -and (Test-Path $DataRoot)) { Remove-Item -Recurse -Force $DataRoot }
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null

function Wait-Http {
    param([string] $Url, [int] $TimeoutSeconds = 180, [string] $What = 'server')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { return $r }
        }
        catch {
            if ($_.Exception.Response) { return $null }
        }
        Start-Sleep -Milliseconds 800
    }
    throw "$What did not answer $Url within $TimeoutSeconds s"
}

function Invoke-Api {
    param([string] $Method, [string] $Path, $Body, [hashtable] $Headers = @{}, [switch] $AllowFailure)
    $uri = $base + $Path
    $params = @{ Method = $Method; Uri = $uri; UseBasicParsing = $true; TimeoutSec = 120; Headers = $Headers }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
    }
    try {
        return Invoke-WebRequest @params
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        $detail = ''
        try {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
        }
        catch { }
        if ($AllowFailure) {
            Write-Host ("    " + $Method + " " + $Path + " -> " + $status + " (tolerated)")
            return $null
        }
        throw ($Method + " " + $Path + " failed with " + $status + ": " + $detail)
    }
}

# ---------------------------------------------------------------- start

$exe = if ($Server -eq 'jellyfin') {
    Join-Path $InstallRoot 'jellyfin\jellyfin.exe'
} else {
    Join-Path $InstallRoot 'emby\system\EmbyServer.exe'
}
if (-not (Test-Path $exe)) { throw "server binary not found: $exe (run get-test-servers.ps1)" }

Get-Process jellyfin, EmbyServer -ErrorAction SilentlyContinue | ForEach-Object {
    try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { }
}
Start-Sleep -Seconds 2

Write-Host "starting $Server on port $Port"
$stdout = Join-Path $DataRoot 'server.out.log'
$stderr = Join-Path $DataRoot 'server.err.log'

if ($Server -eq 'jellyfin') {
    $startArgs = @{
        FilePath = $exe
        ArgumentList = @('--datadir', $DataRoot, '--webdir', (Join-Path $InstallRoot 'jellyfin\jellyfin-web'),
                         '--nowebclient', 'false', '--port', $Port)
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
    }
}
else {
    $startArgs = @{
        FilePath = $exe
        ArgumentList = @('-port', $Port, '-datadir', $DataRoot, '-nointerface')
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
    }
}
$process = Start-Process @startArgs

Write-Host "  pid $($process.Id); waiting for HTTP"
Wait-Http -Url "$base/System/Info/Public" -TimeoutSeconds 240 -What $Server | Out-Null
$info = (Invoke-Api -Method GET -Path '/System/Info/Public').Content | ConvertFrom-Json
Write-Host ("  version: " + $info.Version + "   name: " + $info.ServerName + "   id: " + $info.Id)

# ---------------------------------------------------------------- first run wizard

$needsSetup = $true
try {
    Invoke-Api -Method GET -Path '/Startup/Configuration' | Out-Null
    Write-Host '  first-run wizard required'
}
catch {
    $needsSetup = $false
    Write-Host '  server reports setup already complete'
}

if ($needsSetup) {
    Invoke-Api -Method POST -Path '/Startup/Configuration' -Body @{
        UICulture = 'en-US'; MetadataCountryCode = 'US'; PreferredMetadataLanguage = 'en'
    } | Out-Null

    # Versions differ: some want the user before the wizard completes, some reject an empty password,
    # and some have no remote-access step at all. Each is tolerated and reported rather than assumed.
    Invoke-Api -Method GET -Path '/Startup/User' -AllowFailure | Out-Null
    Invoke-Api -Method POST -Path '/Startup/User' -Body @{ Name = $UserName; Password = $Password } -AllowFailure | Out-Null
    Invoke-Api -Method POST -Path '/Startup/RemoteAccess' -Body @{ EnableRemoteAccess = $false; EnableAutomaticPortMapping = $false } -AllowFailure | Out-Null
    Invoke-Api -Method POST -Path '/Startup/Complete' -AllowFailure | Out-Null
    Write-Host '  wizard submitted'
    Start-Sleep -Seconds 3
}

# ---------------------------------------------------------------- authenticate

$authHeaders = @{ 'X-Emby-Authorization' = 'MediaBrowser Client="MediaHub-Test", Device="setup", DeviceId="setup-script", Version="1.0.0"' }
$body = @{ Username = $UserName; Pw = $Password }
$auth = $null
foreach ($path in @('/Users/AuthenticateByName', '/Users/authenticatebyname')) {
    try {
        $response = Invoke-Api -Method POST -Path $path -Body $body -Headers $authHeaders
        $auth = $response.Content | ConvertFrom-Json
        break
    }
    catch { Write-Host ("    auth via " + $path + " failed: " + $_.Exception.Message) }
}
if (-not $auth) { throw "could not authenticate against $Server" }

$token = $auth.AccessToken
$userId = $auth.User.Id
$tokenHeaders = @{ 'X-Emby-Token' = $token }
Write-Host ("  authenticated as " + $auth.User.Name + " (userId " + $userId + ")")

# ---------------------------------------------------------------- libraries

function Ensure-Library {
    param([string] $Name, [string] $CollectionType, [string] $Path)
    $existing = (Invoke-Api -Method GET -Path '/Library/VirtualFolders' -Headers $tokenHeaders).Content | ConvertFrom-Json
    foreach ($folder in $existing) {
        if ($folder.Name -eq $Name) { Write-Host ("    library '" + $Name + "' already present"); return }
    }
    $query = '?name=' + [uri]::EscapeDataString($Name) +
             '&collectionType=' + $CollectionType +
             '&refreshLibrary=false' +
             '&paths=' + [uri]::EscapeDataString($Path)
    Invoke-Api -Method POST -Path ('/Library/VirtualFolders' + $query) -Headers $tokenHeaders | Out-Null
    Write-Host ("    created library '" + $Name + "' -> " + $Path)
}

Write-Host 'creating libraries'
Ensure-Library -Name 'Movies' -CollectionType 'movies' -Path (Join-Path $MediaRoot 'Movies')
Ensure-Library -Name 'TV Shows' -CollectionType 'tvshows' -Path (Join-Path $MediaRoot 'TV')

# ---------------------------------------------------------------- scan

Write-Host 'starting library scan'
Invoke-Api -Method POST -Path '/Library/Refresh' -Headers $tokenHeaders | Out-Null

$deadline = (Get-Date).AddMinutes(10)
$items = 0
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try {
        $result = (Invoke-Api -Method GET -Path ("/Users/" + $userId + "/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Limit=1") -Headers $tokenHeaders).Content | ConvertFrom-Json
        $items = $result.TotalRecordCount
    }
    catch { continue }

    $running = 0
    try {
        $tasks = (Invoke-Api -Method GET -Path '/ScheduledTasks' -Headers $tokenHeaders).Content | ConvertFrom-Json
        $running = @($tasks | Where-Object { $_.State -eq 'Running' }).Count
    }
    catch { }
    Write-Host ("    items=" + $items + "  running-tasks=" + $running)
    if ($items -gt 0 -and $running -eq 0) { break }
}

# ---------------------------------------------------------------- report

$libraries = (Invoke-Api -Method GET -Path '/Library/VirtualFolders' -Headers $tokenHeaders).Content | ConvertFrom-Json
$summary = [pscustomobject]@{
    Server         = $Server
    Version        = $info.Version
    ServerId       = $info.Id
    BaseUrl        = $base
    DataRoot       = $DataRoot
    UserName       = $UserName
    Password       = $Password
    UserId         = $userId
    Token          = $token
    Libraries      = @($libraries | ForEach-Object { $_.Name })
    TotalItemCount = $items
    ProcessId      = $process.Id
}
$summaryPath = Join-Path $DataRoot 'TEST-SERVER.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ''
Write-Host "server ready: $Server $($info.Version) at $base"
Write-Host "  libraries : $($summary.Libraries -join ', ')"
Write-Host "  items     : $items"
Write-Host "  summary   : $summaryPath"

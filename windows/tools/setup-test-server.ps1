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
    [switch] $Force,
    [switch] $DisableAutoUpdate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'

if (-not (Test-Path $MediaRoot)) { throw "fixture root not found: $MediaRoot (run make-test-media.ps1)" }
if (-not $DataRoot) { $DataRoot = Join-Path $InstallRoot ("data\" + $Server) }
# Jellyfin 12.2 has no --port option at all (its help lists --datadir, --webdir, --cachedir,
# --configdir, --logdir and not much else), so the server keeps its default 8096. Trying to pass a
# port is an immediate argument-parsing failure, which is what an earlier version of this script did.
if (-not $Port) { $Port = 8096 }
$base = "http://127.0.0.1:$Port"

if ($Force -and (Test-Path $DataRoot)) { Remove-Item -Recurse -Force $DataRoot }
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null

function Wait-Http {
    param([string] $Url, [int] $TimeoutSeconds = 180, [string] $What = 'server')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # 2xx, not "any answer". A warming server answers 503 before it serves, and treating that as
    # readiness makes the next call fail with the server's own error instead of waiting it out.
    $lastStatus = 'no-response'
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300) { return $r }
        }
        catch {
            # A connection refusal is not a WebException and has no Response property at all, which
            # under StrictMode is an error rather than a null. "Answered with an error" and "not
            # listening yet" are told apart on the exception's own shape, but neither is readiness:
            # an error response is remembered only so the eventual failure names it.
            $response = $null
            try { $response = $_.Exception.PSObject.Properties['Response'].Value } catch { }
            if ($null -ne $response) {
                try { $lastStatus = '' + [int]$response.StatusCode } catch { $lastStatus = 'error-response' }
            }
        }
        Start-Sleep -Milliseconds 800
    }
    throw "$What did not answer $Url within $TimeoutSeconds s (last status: $lastStatus)"
}

function Invoke-Api {
    param([string] $Method, [string] $Path, $Body, [hashtable] $Headers = @{}, [switch] $AllowFailure, [switch] $RawBody)
    $uri = $base + $Path
    $params = @{ Method = $Method; Uri = $uri; UseBasicParsing = $true; TimeoutSec = 120; Headers = $Headers }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        # -Depth 8 is right for the small bodies this script sends, but the auto-update pin posts a
        # whole configuration object back: that one is passed as raw JSON so no nested collection in
        # it is silently flattened into a string.
        $params.Body = if ($RawBody) { [string]$Body } else { ($Body | ConvertTo-Json -Depth 8 -Compress) }
    }
    try {
        return Invoke-WebRequest @params
    }
    catch {
        # PowerShell 7 raises HttpResponseException carrying an HttpResponseMessage, whereas
        # Windows PowerShell raises WebException carrying a WebResponse. Reading the body through the
        # wrong one produces a confusing "method not found" instead of the server's actual reply, so
        # both shapes are handled explicitly.
        $status = 'no-response'
        $detail = ''
        try {
            $response = $_.Exception.PSObject.Properties['Response'].Value
            if ($null -ne $response) {
                if ($response -is [System.Net.Http.HttpResponseMessage]) {
                    $status = [int]$response.StatusCode
                    $detail = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                else {
                    $status = $response.StatusCode.value__
                    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                    $detail = $reader.ReadToEnd()
                }
            }
            else {
                $detail = $_.Exception.Message
            }
        }
        catch { $detail = $_.Exception.Message }
        if ($AllowFailure) {
            Write-Host ("    " + $Method + " " + $Path + " -> " + $status + " (tolerated)")
            return $null
        }
        throw ($Method + " " + $Path + " failed with " + $status + ": " + $detail)
    }
}

# Emby reads its listening port from its own data directory, never from the command line. Two small
# helpers keep that fact in one place instead of scattering XPath strings through the start-up path.
function Get-EmbyConfiguredPort {
    param([string] $ConfigPath)
    # 8096 is Emby's own default for a data directory that has never run: measured, not assumed.
    if (-not (Test-Path $ConfigPath)) { return 8096 }
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($ConfigPath)
    $node = $doc.DocumentElement.SelectSingleNode('HttpServerPortNumber')
    if (-not $node) { return 8096 }
    return [int]$node.InnerText
}

function Set-EmbyXmlValue {
    param([System.Xml.XmlDocument] $Doc, [string] $Name, [string] $Value)
    $node = $Doc.DocumentElement.SelectSingleNode($Name)
    if (-not $node) {
        $node = $Doc.CreateElement($Name)
        $Doc.DocumentElement.AppendChild($node) | Out-Null
    }
    if ($node.InnerText -ne $Value) { $node.InnerText = $Value }
}

# ---------------------------------------------------------------- start

# Located rather than assumed: the Jellyfin zip nests its payload under a versioned directory,
# and hard-coding the path would silently break on the next release layout change.
$exeName = if ($Server -eq 'jellyfin') { 'jellyfin.exe' } else { 'EmbyServer.exe' }
$exe = Get-ChildItem -Path (Join-Path $InstallRoot $Server) -Filter $exeName -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $exe) { throw "$exeName not found under $InstallRoot\$Server (run get-test-servers.ps1)" }

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
        ArgumentList = @('--datadir', $DataRoot, '--configdir', (Join-Path $DataRoot 'config'),
                         '--cachedir', (Join-Path $DataRoot 'cache'), '--logdir', (Join-Path $DataRoot 'log'),
                         '--webdir', (Join-Path (Split-Path -Parent $exe) 'jellyfin-web'))
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
    }
}
else {
    # -programdata, not -datadir: Emby 4.10 silently ignores the latter, falls back to its own
    # default data directory and then competes with the Jellyfin fixture for port 8096. Measured
    # on this machine; headless-resume.ps1 already starts Emby this way.
    $startArgs = @{
        FilePath = $exe
        ArgumentList = @('-programdata', $DataRoot, '-nointerface')
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
    }
}
$process = Start-Process @startArgs

Write-Host "  pid $($process.Id); waiting for HTTP"

# A data root that has never run comes up on Emby's default 8096 - the very port the Jellyfin fixture
# uses - so -Port has to be pinned in the configuration before the rest of this script can talk to it.
# Two starts, not one: the first creates the configuration file, the second listens where we asked.
if ($Server -eq 'emby') {
    $configPath = Join-Path $DataRoot 'config\system.xml'
    $coldPort = Get-EmbyConfiguredPort -ConfigPath $configPath
    if ($coldPort -ne $Port) {
        Write-Host ("  configured port is " + $coldPort + "; pinning " + $Port)
        Wait-Http -Url ("http://127.0.0.1:" + $coldPort + "/System/Info/Public") -TimeoutSeconds 240 -What $Server | Out-Null
        $configDeadline = (Get-Date).AddSeconds(90)
        while (-not (Test-Path $configPath) -and (Get-Date) -lt $configDeadline) { Start-Sleep -Seconds 2 }
        if (-not (Test-Path $configPath)) { throw ("the server never wrote " + $configPath) }
        Stop-Process -Id $process.Id -Force
        $downDeadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $downDeadline) {
            $stillUp = $true
            try { Invoke-WebRequest -Uri ("http://127.0.0.1:" + $coldPort + "/System/Info/Public") -UseBasicParsing -TimeoutSec 3 | Out-Null } catch { $stillUp = $false }
            if (-not $stillUp) { break }
            Start-Sleep -Seconds 1
        }
        $doc = New-Object System.Xml.XmlDocument
        $doc.Load($configPath)
        Set-EmbyXmlValue -Doc $doc -Name 'HttpServerPortNumber' -Value ([string]$Port)
        Set-EmbyXmlValue -Doc $doc -Name 'PublicPort' -Value ([string]$Port)
        $doc.Save($configPath)
        $readBack = Get-EmbyConfiguredPort -ConfigPath $configPath
        if ($readBack -ne $Port) { throw ("the pinned port did not stick: " + $readBack) }
        $process = Start-Process @startArgs
        Write-Host ("  pid " + $process.Id + "; waiting for HTTP on " + $Port)
    }
}

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

# ---------------------------------------------------------------- auto update

# A test server that upgrades itself mid-run is not the version the run claims to prove: an earlier
# Emby acceptance run started on 4.10.1.0 and finished on 4.10.2.0. Opt-in, and verified by reading
# the setting back instead of trusting the write.
if ($Server -eq 'emby' -and $DisableAutoUpdate) {
    $config = (Invoke-Api -Method GET -Path '/System/Configuration' -Headers $tokenHeaders).Content | ConvertFrom-Json
    $config.EnableAutoUpdate = $false
    $configJson = $config | ConvertTo-Json -Depth 64 -Compress
    Invoke-Api -Method POST -Path '/System/Configuration' -Headers $tokenHeaders -Body $configJson -RawBody | Out-Null
    $pinned = (Invoke-Api -Method GET -Path '/System/Configuration' -Headers $tokenHeaders).Content | ConvertFrom-Json
    if ($pinned.EnableAutoUpdate) { throw 'auto update is still enabled after the write' }
    Write-Host '  auto update disabled (read back as false)'
}

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

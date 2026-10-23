<#
.SYNOPSIS
Drives the headless W8 resume proof end to end: brings the two fixture servers up, runs
MediaHub.ResumeProbe against each of them, and takes both of them down again.

.DESCRIPTION
Nothing in this script opens a window. The probe it drives (windows/src/MediaHub.ResumeProbe) talks
to the servers through the product provider stack, so a pass means the *product* recorded and read
back a resume point, not that this script can speak Emby's protocol.

Order matters. windows/tools/setup-test-server.ps1 stops every jellyfin and EmbyServer process before
it starts its own (setup-test-server.ps1:125-127), so Jellyfin comes up first and Emby is started
afterwards, otherwise the Jellyfin setup would take Emby down mid-run.

Fixture hygiene: neither product interface can clear a played flag, so the reset that makes the run
repeatable is done here through the servers' own APIs (DELETE /Users/{uid}/PlayedItems/{id}). The
probe then asserts the reset actually landed instead of trusting it.

.EXAMPLE
pwsh -File windows/tools/acceptance/w8/headless-resume.ps1
#>
[CmdletBinding()]
param(
    [string] $InstallRoot = 'D:\mh-servers',
    [int]    $EmbyPort = 8097,
    [int]    $JellyfinPort = 8096,
    [string] $EmbyUser = 'mh',
    [string] $EmbyPassword = 'mh-test-pass',
    [string] $JellyfinUser = 'mediahub-test',
    [string] $JellyfinPassword = 'mediahub-test-password',
    [string] $EvidenceDirectory,
    [switch] $SkipBuild,
    [switch] $KeepServers
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $RepoRoot 'windows\tools\acceptance\evidence\w8' }
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

$ProbeProject = Join-Path $RepoRoot 'windows\src\MediaHub.ResumeProbe\MediaHub.ResumeProbe.csproj'
$ProbeDll = Join-Path $RepoRoot 'windows\src\MediaHub.ResumeProbe\bin\Debug\net10.0\MediaHub.ResumeProbe.dll'

$script:passed = 0
$script:failed = 0
$script:startedPids = @()
$script:summary = [ordered]@{ startedUtc = (Get-Date).ToUniversalTime().ToString('o'); servers = @(); probes = @() }

function Assert-That {
    param([string] $Name, [bool] $Condition, [string] $Detail = '')
    if ($Condition) {
        $script:passed++
        Write-Host ('PASS ' + $Name + ' :: ' + $Detail)
    }
    else {
        $script:failed++
        Write-Host ('FAIL ' + $Name + ' :: ' + $Detail)
    }
}

function Get-PublicInfo {
    param([int] $Port)
    # A TCP probe is not enough: http.sys keeps answering on a port that Get-NetTCPConnection cannot
    # see, so health is decided by the HTTP endpoint the probe itself will use. A server that is still
    # warming up answers 503 (and sometimes not JSON at all), which is "not ready" rather than "up":
    # -SkipHttpErrorCheck keeps the status readable instead of failing inside the response body reader.
    try {
        $response = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $Port + '/System/Info/Public') -UseBasicParsing -TimeoutSec 5 -SkipHttpErrorCheck
        if ([int]$response.StatusCode -ne 200) { return $null }
        $info = $response.Content | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($info.Id)) { return $null }
        return $info
    }
    catch { return $null }
}

function Wait-PublicInfo {
    param([int] $Port, [int] $TimeoutSeconds = 240, [string] $What = 'server')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $info = Get-PublicInfo -Port $Port
        if ($null -ne $info) { return $info }
        Start-Sleep -Milliseconds 800
    }
    throw ($What + ' did not answer on port ' + $Port + ' within ' + $TimeoutSeconds + ' s')
}

function Stop-TestServers {
    Get-Process jellyfin, EmbyServer -ErrorAction SilentlyContinue | ForEach-Object {
        try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { }
    }
}

function Start-JellyfinHeadless {
    param([string] $DataRoot)
    $exe = Get-ChildItem -Path (Join-Path $InstallRoot 'jellyfin') -Filter 'jellyfin.exe' -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $exe) { throw ('jellyfin.exe not found under ' + $InstallRoot + ' (run get-test-servers.ps1)') }

    # Same argument shape setup-test-server.ps1 uses. Jellyfin 12.2 has no --port switch at all, so the
    # port comes from the fixture's own configuration (8096) rather than from this command line.
    $webDir = Join-Path (Split-Path -Parent $exe) 'jellyfin-web'
    $commandLine = ('"' + $exe + '" --datadir "' + $DataRoot + '" --configdir "' + (Join-Path $DataRoot 'config') +
        '" --cachedir "' + (Join-Path $DataRoot 'cache') + '" --logdir "' + (Join-Path $DataRoot 'log') +
        '" --webdir "' + $webDir + '"')
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine }
    if ($result.ReturnValue -ne 0) { throw ('Win32_Process.Create failed with ' + $result.ReturnValue) }
    return [int]$result.ProcessId
}

function Start-EmbyHeadless {
    param([string] $DataRoot)
    # Located rather than assumed: this machine's Emby lives in emby-install\app\system, while the
    # Jellyfin fixture sits in its own folder, so every plausible root is tried before giving up.
    $exe = $null
    foreach ($candidate in @((Join-Path $InstallRoot 'emby-install'), (Join-Path $InstallRoot 'emby'))) {
        if (-not (Test-Path $candidate)) { continue }
        $exe = Get-ChildItem -Path $candidate -Filter 'EmbyServer.exe' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        if ($exe) { break }
    }
    if (-not $exe) { throw ('EmbyServer.exe not found under ' + $InstallRoot + '\emby-install or ' + $InstallRoot + '\emby (run get-test-servers.ps1)') }

    # -programdata, not -datadir: Emby 4.10 silently ignores the latter, falls back to its default
    # data directory and then competes with Jellyfin for port 8096. Measured on this fixture.
    $commandLine = ('"' + $exe + '" -programdata "' + $DataRoot + '" -nointerface')

    # Started through WMI on purpose. A Start-Process child is torn down with the console that owns it,
    # which would kill the server the moment an outer timeout fires; this way the process belongs to
    # nobody and is only stopped by the cleanup below.
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine }
    if ($result.ReturnValue -ne 0) { throw ('Win32_Process.Create failed with ' + $result.ReturnValue) }
    return [int]$result.ProcessId
}

function New-IdentityHeaders {
    param([string] $ServerType, [string] $Token = '')
    # The two servers read the client identity from different headers, and this is not cosmetic:
    # Jellyfin 12.2 parses the MediaBrowser fields out of Authorization, so a login that carries them
    # in X-Emby-Authorization instead is rejected with 400 (System.ArgumentNullException on request.App).
    # Emby reads X-Emby-Authorization and takes the token from X-Emby-Token; Jellyfin folds the token
    # into the same Authorization value. Measured against both fixtures.
    $value = 'MediaBrowser Client="MediaHub-W8", Device="headless-resume", DeviceId="w8-headless", Version="1.0.0"'
    if ($Token) { $value = $value + ', Token="' + $Token + '"' }
    if ($ServerType -eq 'jellyfin') { return @{ 'Authorization' = $value } }
    $headers = @{ 'X-Emby-Authorization' = $value }
    if ($Token) {
        $headers['X-Emby-Token'] = $Token
        $headers['X-MediaBrowser-Token'] = $Token
    }
    return $headers
}

function Connect-Fixture {
    param([string] $ServerType, [string] $Base, [string] $User, [string] $Password, [int] $TimeoutSeconds = 240)
    # A server can answer /System/Info/Public with 200 while its own API is still warming up: Jellyfin
    # 12.2 serves 503 from every other endpoint until startup and the first library scan are done. So the
    # login is the readiness gate, retried until it succeeds - but a 401 is final, because that is a
    # wrong credential and retrying it would only hide the mistake for four minutes.
    $headers = New-IdentityHeaders -ServerType $ServerType
    $body = @{ Username = $User; Pw = $Password } | ConvertTo-Json -Compress
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'no attempt was made'
    $rejected = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri ($Base + '/Users/AuthenticateByName') -Method POST -UseBasicParsing -TimeoutSec 30 -SkipHttpErrorCheck -ContentType 'application/json' -Headers $headers -Body $body
            $status = [int]$response.StatusCode
            if ($status -eq 401 -or $status -eq 403) {
                # Set rather than thrown: the throw would be swallowed by the catch below and turn a
                # credential mistake into a four-minute retry loop.
                $rejected = 'the fixture rejected the credentials for ' + $User + ' (HTTP ' + $status + ')'
                break
            }
            if ($status -eq 200) {
                $auth = $response.Content | ConvertFrom-Json
                if (-not [string]::IsNullOrWhiteSpace($auth.AccessToken)) {
                    return @{ Token = $auth.AccessToken; UserId = $auth.User.Id; UserName = $auth.User.Name }
                }
                $lastError = 'the reply carried no AccessToken'
            }
            else { $lastError = 'HTTP ' + $status }
        }
        catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 2
    }
    if ($null -ne $rejected) { throw $rejected }
    throw ('could not authenticate against ' + $Base + ' within ' + $TimeoutSeconds + ' s: ' + $lastError)
}

function Reset-Fixture {
    param([string] $ServerType, [string] $Base, [hashtable] $Session)
    # The probe only trusts a clean target if it can still see one, so this is hygiene and not proof:
    # it clears the played flag and the stored position on the longest title long enough for the
    # servers' resume rules (300 s floor; the fixtures ship exactly one 600 s title).
    $headers = New-IdentityHeaders -ServerType $ServerType -Token $Session.Token
    $uri = ($Base + '/Users/' + $Session.UserId + '/Items?Recursive=true&IncludeItemTypes=Movie&Fields=RunTimeTicks,UserData&Limit=500')
    $items = $null
    $deadline = (Get-Date).AddSeconds(180)
    $lastError = 'no attempt was made'
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 60 -SkipHttpErrorCheck -Headers $headers
            if ([int]$response.StatusCode -eq 200) {
                $items = $response.Content | ConvertFrom-Json
                break
            }
            $lastError = 'HTTP ' + [int]$response.StatusCode
        }
        catch { $lastError = $_.Exception.Message }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $items) { throw ('could not list the fixture movies: ' + $lastError) }
    $longest = @($items.Items | Where-Object { $_.RunTimeTicks -ge (300 * 10000000) } | Sort-Object -Property RunTimeTicks -Descending) | Select-Object -First 1
    if ($null -eq $longest) {
        Write-Warning 'no movie of at least 300 s was found; the probe will report the missing fixture'
        return $null
    }
    Invoke-WebRequest -Uri ($Base + '/Users/' + $Session.UserId + '/PlayedItems/' + $longest.Id) -Method DELETE -UseBasicParsing -TimeoutSec 60 -Headers $headers | Out-Null
    Start-Sleep -Milliseconds 500
    Write-Host ('  fixture reset: ' + $longest.Id + ' "' + $longest.Name + '"')
    return $longest
}

function Invoke-Probe {
    param([string] $Server, [int] $Port, [string] $User, [string] $Password, [string] $Label)
    $jsonPath = Join-Path $EvidenceDirectory ($Label + '-resume.json')
    $logPath = Join-Path $EvidenceDirectory ($Label + '-resume.log')
    Write-Host ('--- probe ' + $Server + ' on port ' + $Port + ' ---')
    $output = & dotnet $ProbeDll --server $Server --url ('http://127.0.0.1:' + $Port) --user $User --password $Password --json $jsonPath --label $Label 2>&1
    $code = $LASTEXITCODE
    foreach ($line in $output) { Write-Host ('  ' + $line) }
    Set-Content -Path $logPath -Value (@($output) -join [Environment]::NewLine) -Encoding UTF8
    $resultLine = @($output | Where-Object { $_ -like 'RESULT:*' }) | Select-Object -Last 1
    return [pscustomobject]@{ ExitCode = $code; Json = $jsonPath; Log = $logPath; ResultLine = $resultLine }
}

$fatal = $null
$timings = [ordered]@{}
try {
    # ---------------------------------------------------------------- build
    if (-not $SkipBuild -or -not (Test-Path $ProbeDll)) {
        Write-Host 'building MediaHub.ResumeProbe'
        & dotnet build $ProbeProject -c Debug -nologo | Select-Object -Last 4 | ForEach-Object { Write-Host ('  ' + $_) }
        if ($LASTEXITCODE -ne 0) { throw 'MediaHub.ResumeProbe did not build' }
    }
    Assert-That 'probe-binary-exists' (Test-Path $ProbeDll) $ProbeDll

    # ---------------------------------------------------------------- ports first
    $jellyfinInfo = Get-PublicInfo -Port $JellyfinPort
    $reusedJellyfin = $null -ne $jellyfinInfo
    Write-Host ('jellyfin port ' + $JellyfinPort + ': ' + $(if ($reusedJellyfin) { 'already answering, reusing it' } else { 'idle, starting it' }))

    $jellyfinData = Join-Path $InstallRoot 'data\jellyfin'
    if (-not $reusedJellyfin) {
        # A configured data directory (its own config/system.xml) is all a start needs; the one-time
        # wizard is windows/tools/setup-test-server.ps1's job and is not repeated here. That script is
        # deliberately not called per run: it stops every jellyfin/EmbyServer process on the machine
        # (taking the Emby started below down as well), and on a cold start it treats the 503 a warming
        # server returns as a served response, which then fails its own first API call.
        $jellyfinConfig = Join-Path $jellyfinData 'config\system.xml'
        if (-not (Test-Path $jellyfinConfig)) {
            throw ('the jellyfin fixture has never been set up (' + $jellyfinConfig + ' is missing): run pwsh -File windows/tools/setup-test-server.ps1 -Server jellyfin once, then re-run this script')
        }
        $jellyfinPid = Start-JellyfinHeadless -DataRoot $jellyfinData
        $script:startedPids += $jellyfinPid
        Write-Host ('jellyfin started, pid ' + $jellyfinPid + '; waiting for HTTP')
        $jellyfinInfo = Wait-PublicInfo -Port $JellyfinPort -TimeoutSeconds 240 -What 'jellyfin'
    }
    Assert-That 'jellyfin-answers' ($null -ne $jellyfinInfo) ('version=' + $jellyfinInfo.Version)

    $embyInfo = Get-PublicInfo -Port $EmbyPort
    if ($null -eq $embyInfo) {
        $embyPid = Start-EmbyHeadless -DataRoot (Join-Path $InstallRoot 'data\emby')
        $script:startedPids += $embyPid
        Write-Host ('emby started, pid ' + $embyPid + '; waiting for HTTP')
        $embyInfo = Wait-PublicInfo -Port $EmbyPort -TimeoutSeconds 240 -What 'emby'
    }
    else {
        Write-Host ('emby port ' + $EmbyPort + ': already answering, reusing it')
    }
    Assert-That 'emby-answers' ($null -ne $embyInfo) ('version=' + $embyInfo.Version)
    Assert-That 'the-two-servers-are-not-the-same-process' ($jellyfinInfo.Id -ne $embyInfo.Id) ('jellyfin=' + $jellyfinInfo.Id + ' emby=' + $embyInfo.Id)

    $script:summary.servers = @(
        [ordered]@{ server = 'jellyfin'; version = $jellyfinInfo.Version; serverId = $jellyfinInfo.Id; baseUrl = ('http://127.0.0.1:' + $JellyfinPort) },
        [ordered]@{ server = 'emby'; version = $embyInfo.Version; serverId = $embyInfo.Id; baseUrl = ('http://127.0.0.1:' + $EmbyPort) }
    )

    # ---------------------------------------------------------------- jellyfin run
    $jellyfinBase = 'http://127.0.0.1:' + $JellyfinPort
    $jellyfinSession = Connect-Fixture -ServerType 'jellyfin' -Base $jellyfinBase -User $JellyfinUser -Password $JellyfinPassword
    Assert-That 'jellyfin-fixture-login' ($null -ne $jellyfinSession.Token) ('user=' + $jellyfinSession.UserName)
    $jellyfinTarget = Reset-Fixture -ServerType 'jellyfin' -Base $jellyfinBase -Session $jellyfinSession
    Assert-That 'jellyfin-has-a-long-enough-fixture' ($null -ne $jellyfinTarget) $(if ($jellyfinTarget) { $jellyfinTarget.Name + ' ' + [math]::Round($jellyfinTarget.RunTimeTicks / 10000000, 1) + 's' } else { 'none' })

    $watch = [Diagnostics.Stopwatch]::StartNew()
    $jellyfinProbe = Invoke-Probe -Server jellyfin -Port $JellyfinPort -User $JellyfinUser -Password $JellyfinPassword -Label 'jellyfin-headless'
    $watch.Stop()
    $timings['jellyfin'] = [int]$watch.Elapsed.TotalSeconds
    Assert-That 'jellyfin-probe-passes' ($jellyfinProbe.ExitCode -eq 0) ('exit=' + $jellyfinProbe.ExitCode + ' ' + $jellyfinProbe.ResultLine)
    Assert-That 'jellyfin-probe-wrote-evidence' (Test-Path $jellyfinProbe.Json) $jellyfinProbe.Json

    # ---------------------------------------------------------------- emby run
    $embyBase = 'http://127.0.0.1:' + $EmbyPort
    $embySession = Connect-Fixture -ServerType 'emby' -Base $embyBase -User $EmbyUser -Password $EmbyPassword
    Assert-That 'emby-fixture-login' ($null -ne $embySession.Token) ('user=' + $embySession.UserName)
    $embyTarget = Reset-Fixture -ServerType 'emby' -Base $embyBase -Session $embySession
    Assert-That 'emby-has-a-long-enough-fixture' ($null -ne $embyTarget) $(if ($embyTarget) { $embyTarget.Name + ' ' + [math]::Round($embyTarget.RunTimeTicks / 10000000, 1) + 's' } else { 'none' })

    $watch = [Diagnostics.Stopwatch]::StartNew()
    $embyProbe = Invoke-Probe -Server emby -Port $EmbyPort -User $EmbyUser -Password $EmbyPassword -Label 'emby-headless'
    $watch.Stop()
    $timings['emby'] = [int]$watch.Elapsed.TotalSeconds
    Assert-That 'emby-probe-passes' ($embyProbe.ExitCode -eq 0) ('exit=' + $embyProbe.ExitCode + ' ' + $embyProbe.ResultLine)
    Assert-That 'emby-probe-wrote-evidence' (Test-Path $embyProbe.Json) $embyProbe.Json

    # ---------------------------------------------------------------- the assertion lines themselves
    foreach ($pair in @(@{ Label = 'jellyfin'; Path = $jellyfinProbe.Json }, @{ Label = 'emby'; Path = $embyProbe.Json })) {
        $report = (Get-Content -Raw $pair.Path | ConvertFrom-Json)
        $assertions = @($report.assertions)
        # Named failures rather than failed: a bare $failed at script level IS $script:failed, and the
        # counter the whole script reports would be overwritten by this loop's empty array.
        $failures = @($assertions | Where-Object { -not $_.pass })
        $script:summary.probes += [ordered]@{
            server = $report.server; label = $report.label; baseUrl = $report.baseUrl
            clientInstances = $report.clientInstances; durationMs = $report.durationMs
            assertions = $assertions.Count; failures = $failures.Count
            items = $report.items
        }
        Assert-That ($pair.Label + '-report-is-all-green') ($failures.Count -eq 0) ($failures.Count.ToString() + ' failed of ' + $assertions.Count)
        Assert-That ($pair.Label + '-report-used-several-client-instances') ([int]$report.clientInstances -ge 8) ('clients=' + $report.clientInstances)
    }
}
catch {
    $fatal = $_.Exception.Message
    Write-Host ('FATAL ' + $fatal)
}
finally {
    if (-not $KeepServers) {
        foreach ($pidValue in $script:startedPids) {
            try { Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue } catch { }
        }
        Stop-TestServers
        Start-Sleep -Seconds 2
        $left = @(Get-Process jellyfin, EmbyServer -ErrorAction SilentlyContinue).Count
        Assert-That 'no-test-server-processes-left' ($left -eq 0) ('running=' + $left)
    }
    else {
        Write-Host ('servers left running on request: ' + ($script:startedPids -join ', '))
    }

    $script:summary['finishedUtc'] = (Get-Date).ToUniversalTime().ToString('o')
    $script:summary['durations'] = $timings
    $script:summary['passed'] = $script:passed
    $script:summary['failed'] = $script:failed
    $script:summary['error'] = $fatal
    $summaryFile = Join-Path $EvidenceDirectory 'headless-resume-summary.json'
    $script:summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryFile -Encoding UTF8
    Write-Host ('summary: ' + $summaryFile)
}

Write-Host ''
Write-Host ('RESULT: ' + $script:passed + ' passed, ' + $script:failed + ' failed')
if ($script:failed -gt 0 -or $null -ne $fatal) { exit 1 }
exit 0
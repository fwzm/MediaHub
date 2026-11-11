<#
.SYNOPSIS
Gate S1.2: three controlled stop/resume comparisons against the live Jellyfin 12.2 instance.

.DESCRIPTION
Each case resets the item to unplayed through the server's own API, opens a real playback session
(POST /Sessions/Playing), reports progress, and stops at a chosen position. Every request and the
resulting server-side UserData are recorded, so the conclusion is drawn from what the server
persisted rather than from what the client claimed to send.

The cases differ in exactly one variable each:
  A  180 s item, stop at 32 s   - the W4 fixture, with the server's shipped resume threshold
  B  600 s item, stop at 100 s  - same threshold, a runtime the threshold can accept
  C  180 s item, stop at 32 s   - the isolated test instance with MinResumeDurationSeconds=60

.PARAMETER Case
A, B or C.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('A','B','C')][string] $Case,
    [string] $Base = 'http://127.0.0.1:8096',
    [string] $UserName = 'mediahub-test',
    [string] $Password = 'mediahub-test-password',
    [string] $OutDir = 'D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w8-s1'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$authHeader = 'MediaBrowser Client="MediaHub-W8", Device="probe", DeviceId="w8-probe", Version="1.0.0"'
$loginParams = @{
    Method      = 'POST'
    Uri         = ($Base + '/Users/AuthenticateByName')
    ContentType = 'application/json'
    Headers     = @{ Authorization = $authHeader }
    Body        = (@{ Username = $UserName; Pw = $Password } | ConvertTo-Json -Compress)
}
$login = Invoke-RestMethod @loginParams
$token = $login.AccessToken
$userId = $login.User.Id
$h = @{ Authorization = ('MediaBrowser Token="' + $token + '"') }
$transcript = New-Object System.Collections.Generic.List[string]

function Invoke-Json {
    param([string] $Method, [string] $Path, $Body)
    $params = @{ Method = $Method; Uri = ($Base + $Path); Headers = $h }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    $script:transcript.Add($Method + ' ' + $Path + ' ' + $(if ($null -eq $Body) { '' } else { $params.Body }))
    try {
        $r = Invoke-WebRequest @params -UseBasicParsing -TimeoutSec 30
        $script:transcript.Add('  -> ' + [int]$r.StatusCode)
        if ($r.Content) { return ($r.Content | ConvertFrom-Json) }
        return $null
    }
    catch {
        $status = 'no-response'
        $detail = $_.Exception.Message
        $resp = $null
        try { $resp = $_.Exception.PSObject.Properties['Response'].Value } catch { }
        if ($null -ne $resp) {
            $status = [int]$resp.StatusCode
            $detail = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        $script:transcript.Add('  -> ' + $status + ' ' + $detail)
        return $null
    }
}

function Get-ItemRow {
    param([string] $ItemId)
    $u = $Base + '/Users/' + $userId + '/Items/' + $ItemId + '?Fields=UserData,RunTimeTicks'
    $i = Invoke-Json -Method GET -Path ('/Users/' + $userId + '/Items/' + $ItemId + '?Fields=UserData,RunTimeTicks')
    return $i
}

# ---------------------------------------------------------------- pick the item

switch ($Case) {
    'A' { $itemName = 'Resume Test (2024)'; $stopSeconds = 32 }
    'B' { $itemName = 'Long Resume (2026)'; $stopSeconds = 100 }
    'C' { $itemName = 'Resume Test (2024)'; $stopSeconds = 32 }
}

$all = Invoke-Json -Method GET -Path ('/Users/' + $userId + '/Items?Recursive=true&IncludeItemTypes=Movie&Fields=RunTimeTicks&Limit=200')
$item = $all.Items | Where-Object { $_.Name -eq $itemName } | Select-Object -First 1
if (-not $item) { throw ('item not found: ' + $itemName) }
$itemId = $item.Id
$runtimeSeconds = [math]::Round($item.RunTimeTicks / 10000000, 1)
Write-Host ('case ' + $Case + ': item=' + $itemName + ' id=' + $itemId + ' runtime=' + $runtimeSeconds + 's stop=' + $stopSeconds + 's')

# ---------------------------------------------------------------- reset to a clean state

# MarkUnplayed is the supported way to get Played=false and position=0; reusing an item that a
# previous run already finished would make every later comparison meaningless.
Invoke-Json -Method DELETE -Path ('/Users/' + $userId + '/PlayedItems/' + $itemId) | Out-Null
$before = Get-ItemRow -ItemId $itemId
Write-Host ('  before: played=' + $before.UserData.Played + ' pos=' + $before.UserData.PlaybackPositionTicks)
if ($before.UserData.Played -ne $false -or $before.UserData.PlaybackPositionTicks -ne 0) {
    throw 'reset failed: item is not in a clean unplayed state'
}

# ---------------------------------------------------------------- play and stop

$info = Invoke-Json -Method POST -Path ('/Items/' + $itemId + '/PlaybackInfo?UserId=' + $userId) -Body @{ UserId = $userId }
$sourceId = $null
if ($info.MediaSources -and @($info.MediaSources).Count -gt 0) { $sourceId = $info.MediaSources[0].Id }
$playSessionId = $info.PlaySessionId
Write-Host ('  playSessionId=' + $playSessionId + ' mediaSourceId=' + $sourceId)

$positionTicks = [int64]($stopSeconds * 10000000)
$body = @{
    ItemId        = $itemId
    MediaSourceId = $sourceId
    PlaySessionId = $playSessionId
    CanSeek       = $true
    PlayMethod    = 'DirectStream'
    PositionTicks = 0
}
Invoke-Json -Method POST -Path '/Sessions/Playing' -Body $body | Out-Null

$body.PositionTicks = [int64]($positionTicks / 2)
$body.IsPaused = $false
$body.EventName = 'timeupdate'
Invoke-Json -Method POST -Path '/Sessions/Playing/Progress' -Body $body | Out-Null

Start-Sleep -Milliseconds 500
$body.PositionTicks = $positionTicks
$body.IsPaused = $false
Invoke-Json -Method POST -Path '/Sessions/Playing/Progress' -Body $body | Out-Null

Start-Sleep -Milliseconds 500
Invoke-Json -Method POST -Path '/Sessions/Playing/Stopped' -Body $body | Out-Null
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- what the server persisted

$after = Get-ItemRow -ItemId $itemId
$resume = Invoke-Json -Method GET -Path '/UserItems/Resume?UserId=' + $userId + '&Limit=50&MediaTypes=Video'
$resumeIds = @($resume.Items | ForEach-Object { $_.Id })

$result = [pscustomobject]@{
    Case             = $Case
    Item             = $itemName
    ItemId           = $itemId
    RuntimeSeconds   = $runtimeSeconds
    StopSeconds      = $stopSeconds
    StopPct          = [math]::Round(100 * $stopSeconds / $runtimeSeconds, 1)
    Played           = $after.UserData.Played
    PositionTicks    = $after.UserData.PlaybackPositionTicks
    PositionSeconds  = [math]::Round($after.UserData.PlaybackPositionTicks / 10000000, 1)
    PlayCount        = $after.UserData.PlayCount
    InResumeList     = ($resumeIds -contains $itemId)
    MinResumeSetting = $null
}

$cfg = Invoke-Json -Method GET -Path '/System/Configuration'
$result.MinResumeSetting = $cfg.MinResumeDurationSeconds

Write-Host ''
$result | Format-List | Out-String | Write-Host
$result | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $OutDir ('case-' + $Case + '.json')) -Encoding UTF8
$transcript | Set-Content -Path (Join-Path $OutDir ('case-' + $Case + '-transcript.txt')) -Encoding UTF8

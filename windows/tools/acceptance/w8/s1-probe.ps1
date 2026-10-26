<#
.SYNOPSIS
Gate S1 probe: read the live Jellyfin 12.2 resume/watch rules and the library items with their
current UserData, and write a machine-readable snapshot.

.DESCRIPTION
The W4 resume failure was measured against a 180 s fixture. Before changing any client code this
probe records what the server is actually configured to do, because a resume threshold that the
fixture cannot satisfy turns a protocol result into a fixture artefact. Nothing is asserted here;
the values are written to evidence and quoted in the W8 report.
#>
[CmdletBinding()]
param(
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
Write-Host ("login: user=" + $login.User.Name + " id=" + $userId + " tokenTail=" + $token.Substring($token.Length - 6))

$h = @{ Authorization = ('MediaBrowser Token="' + $token + '"') }

$cfg = Invoke-RestMethod -Method GET -Uri ($Base + '/System/Configuration') -Headers $h
$cfg | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $OutDir 'system-configuration.json') -Encoding UTF8

foreach ($k in @('MinResumeDurationSeconds','MinResumePct','MaxResumePct','EnableNextEpisodeAutoPlay')) {
    $v = $cfg.PSObject.Properties[$k]
    if ($null -eq $v) { Write-Host ("config." + $k + " = <property-absent>") }
    else { Write-Host ("config." + $k + " = " + $v.Value) }
}

$itemUrl = $Base + '/Users/' + $userId + '/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Fields=RunTimeTicks,UserData,Path&Limit=200'
$items = Invoke-RestMethod -Method GET -Uri $itemUrl -Headers $h
$rows = @()
foreach ($i in $items.Items) {
    $rows += [pscustomobject]@{
        Id             = $i.Id
        Name           = $i.Name
        Type           = $i.Type
        RunTimeTicks   = $i.RunTimeTicks
        Seconds        = if ($i.RunTimeTicks) { [math]::Round($i.RunTimeTicks / 10000000, 1) } else { 0 }
        Played         = $i.UserData.Played
        PositionTicks  = $i.UserData.PlaybackPositionTicks
        PlayCount      = $i.UserData.PlayCount
        LastPlayedDate = $i.UserData.LastPlayedDate
        Path           = $i.Path
    }
}
$rows | Sort-Object Seconds | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
$rows | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $OutDir 'items-before.json') -Encoding UTF8

$resume = Invoke-RestMethod -Method GET -Uri ($Base + '/UserItems/Resume?UserId=' + $userId + '&Limit=50&MediaTypes=Video') -Headers $h
Write-Host ("resume items: " + @($resume.Items).Count)
$resume.Items | Select-Object Name, Id, PlaybackPositionTicks | Format-Table -Auto | Out-String | Write-Host

Write-Host ("evidence: " + $OutDir)

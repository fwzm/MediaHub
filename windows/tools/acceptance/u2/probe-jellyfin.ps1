[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$ErrorActionPreference='Stop'
$base='http://127.0.0.1:8096'
$hdr='MediaBrowser Client="MediaHub-U2", Device="probe", DeviceId="u2-autoplay", Version="1.0.0"'
$body=@{Username='mediahub-test';Pw='mediahub-test-password'}|ConvertTo-Json -Compress
$login=Invoke-RestMethod -Method POST -Uri ($base+'/Users/AuthenticateByName') -ContentType 'application/json' -Headers @{Authorization=$hdr} -Body $body -TimeoutSec 20
$H=@{Authorization=('MediaBrowser Token="'+$login.AccessToken+'"')}
Write-Host ('uid='+$login.User.Id)
$libs=@((Invoke-RestMethod -Uri ($base+'/Users/'+$login.User.Id+'/Views') -Headers $H).Items)
foreach($l in $libs){ Write-Host ('lib: '+$l.Id+' | '+$l.Name+' | '+$l.CollectionType) }
foreach($l in $libs){
  if($l.CollectionType -ne 'tvshows'){ continue }
  $top=Invoke-RestMethod -Uri ($base+'/Items?ParentId='+$l.Id+'&IncludeItemTypes=Series&Recursive=true&Fields=ChildCount,RecursiveItemCount') -Headers $H -TimeoutSec 20
  foreach($sh in @($top.Items)){
    $eps=Invoke-RestMethod -Uri ($base+'/Shows/'+$sh.Id+'/Episodes?UserId='+$login.User.Id+'&Fields=UserData,MediaSources') -Headers $H -TimeoutSec 20
    Write-Host ('series: '+$sh.Id+' | '+$sh.Name+' | episodes='+@($eps.Items).Count)
    foreach($e in @($eps.Items)){ Write-Host ('  ep: '+$e.Id+' | '+$e.Name+' | s'+$e.ParentIndexNumber+'e'+$e.IndexNumber+' | ticks='+$e.RunTimeTicks+' | played='+$e.UserData.Played+' pos='+$e.UserData.PlaybackPositionTicks+' | seriesId='+$e.SeriesId+' | seasonId='+$e.SeasonId+' | src='+(@($e.MediaSources).Count)) }
  }
}

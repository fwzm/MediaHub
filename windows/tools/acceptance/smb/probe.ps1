#Requires -Version 7
<#
.SYNOPSIS
Prints what the Windows redirector answers for the failure cases the SMB source has to translate.

.DESCRIPTION
The app maps Win32 codes to sentences; this probe reports the raw code each scenario produces on this
machine so the expected sentence in the acceptance run is grounded in what actually happens here
rather than in what the documentation promises. It also proves the cases are reproducible without
touching the app.

Run (no elevation needed for the negative cases):
    pwsh -NoProfile -File windows/tools/acceptance/smb/probe.ps1
#>
[CmdletBinding()]
param(
    [string]$UnreachableTarget = '\\192.0.2.1\media'
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class MhSmbProbe
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct NetResource
    {
        public uint dwScope;
        public uint dwType;
        public uint dwDisplayType;
        public uint dwUsage;
        public string lpLocalName;
        public string lpRemoteName;
        public string lpComment;
        public string lpProvider;
    }

    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetAddConnection2W")]
    public static extern int Add(ref NetResource resource, string password, string user, uint flags);

    public static int Connect(string target, string user, string password)
    {
        var resource = new NetResource
        {
            dwType = 1,
            dwDisplayType = 2,
            lpRemoteName = target,
        };
        return Add(ref resource, password, user, 0x4);
    }
}
'@

function Invoke-Case([string]$Name, [string]$Target, [string]$User, [string]$Password) {
    $started = Get-Date
    $code = [MhSmbProbe]::Connect($Target, $User, $Password)
    $elapsed = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    [pscustomobject]@{
        case = $Name
        target = $Target
        user = $(if ($User) { $User } else { '(当前身份)' })
        code = $code
        seconds = $elapsed
    }
}

$rows = @()
# Anonymous read of a share only administrators may open: the redirector refuses the logon.
$rows += Invoke-Case 'no-credentials-admin-share' '\\localhost\ADMIN$' '' ''
$rows += Invoke-Case 'missing-share' '\\localhost\MhNoSuchShare' '' ''
$rows += Invoke-Case 'wrong-password' '\\localhost\Users' 'mh\definitely-not-a-user' 'wrong-password'
$rows += Invoke-Case 'unreachable-host' $UnreachableTarget '' ''

$rows | Format-Table -AutoSize

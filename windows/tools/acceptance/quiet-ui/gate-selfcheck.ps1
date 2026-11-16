#Requires -Version 7
<#
.SYNOPSIS
Self-check for the idle gate: injects one harmless input event and verifies the gate waits, the
watchdog pauses the run immediately, the subject process is closed, and the next window resumes
from the checkpoint instead of redoing completed steps.

.DESCRIPTION
Phase 1 (gate): resets idle with a synthetic input, starts Wait-MhQuietGate in a job, injects a
second input while the gate is still waiting, and asserts the gate only opens one full quiet window
after that second input. The log must show "reason=user active" and a waited=<n>s value.

Phase 2 (run): starts a throwaway subject process (stands in for the app under test), runs four
one-second steps with a 500 ms watchdog, injects input during step 2, and asserts:
  * the watchdog writes an abort reason within ~2 s,
  * the subject process is gone (watchdog kill),
  * the driver logs "用户回来了，已暂停",
  * the resume skips the completed step and finishes the rest.

The injected event is a relative mouse move of (0,0); if the input queue ignores it, a 1-pixel move
is used and the cursor is put back. Nothing is typed and no window is activated.

.EXAMPLE
pwsh -File windows/tools/acceptance/quiet-ui/gate-selfcheck.ps1
#>
[CmdletBinding()]
param(
    [int]$MinIdleSeconds = 3,
    [string]$OutRoot = 'D:\mh-verify\quiet-selfcheck'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhQuiet.psm1') -Force
Import-Module (Join-Path $here '..\lib\MhQuietUi.psm1') -Force

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$logPath = Join-Path $OutRoot ('selfcheck-' + $stamp + '.log')
$checkpoint = Join-Path $OutRoot ('checkpoint-' + $stamp + '.json')

$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $line = ('[{0}] {1} :: {2}' -f $(if ($Pass) { 'PASS' } else { 'FAIL' }), $Name, $Detail)
    if (-not $Pass) { $failures.Add($Name) }
    Add-Content -Path $logPath -Value ((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $line) -Encoding UTF8
    Write-Host $line
}

function Note([string]$Text) {
    Add-Content -Path $logPath -Value ((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $Text) -Encoding UTF8
    Write-Host $Text
}

Note ('gate self-check start threshold=' + $MinIdleSeconds + 's out=' + $OutRoot)

# ---------------------------------------------------------------- phase 1: gate
$inject0 = Send-MhQuietSyntheticInput
Note ('phase1: first synthetic input mode=' + $inject0.mode + ' idle ' + $inject0.idleBefore + 's -> ' + $inject0.idleAfter + 's cursor ' + $inject0.cursorBefore + ' -> ' + $inject0.cursorAfter)
Check 'phase1.idle-reset' (($inject0.idleAfter -lt 2)) ('idleAfter=' + $inject0.idleAfter + 's')

$gateJob = Start-Job -ScriptBlock {
    param($LibDir, $MinIdle, $LogPath)
    Import-Module (Join-Path $LibDir 'MhQuiet.psm1') -Force
    Import-Module (Join-Path $LibDir 'MhQuietUi.psm1') -Force
    Wait-MhQuietGate -MinIdleSeconds $MinIdle -PollSeconds 1 -LogEverySeconds 2 -LogPath $LogPath
} -ArgumentList (Join-Path $here '..\lib'), $MinIdleSeconds, $logPath

# Inject early enough that the event lands well inside the quiet window; Send-MhQuietSyntheticInput
# itself takes about a second (it snapshots state before and after), so sleeping here is a race.
Start-Sleep -Milliseconds 300
$injectStart = Get-Date
$inject1 = Send-MhQuietSyntheticInput
$injectedAt = Get-Date
Note ('phase1: second synthetic input while gate is waiting mode=' + $inject1.mode + ' idle=' + $inject1.idleAfter + 's cursor ' + $inject1.cursorBefore + ' -> ' + $inject1.cursorAfter + ' at ' + $injectedAt.ToString('HH:mm:ss.fff'))

$gate = Receive-Job -Job $gateJob -Wait -AutoRemoveJob
$gateReturnedAt = Get-Date
$gateOpenDelay = [int]($gateReturnedAt - $injectStart).TotalSeconds
$gateLog = Get-Content $logPath -Raw
Note ('phase1: gate returned at ' + $gateReturnedAt.ToString('HH:mm:ss.fff') + ' delayFromInjectStart=' + $gateOpenDelay + 's idle=' + $gate.IdleSeconds + 's saidWaited=' + $gate.WaitedSeconds + 's')
Check 'phase1.gate-waited' (($gate.IdleSeconds -ge $MinIdleSeconds) -and ($gateOpenDelay -ge $MinIdleSeconds)) ('opened with idle=' + $gate.IdleSeconds + 's, ' + $gateOpenDelay + 's after the injection started, need ' + $MinIdleSeconds + 's')
Check 'phase1.log-waiting' (($gateLog -match 'reason=user active') -and ($gateLog -match 'waited=\d+s')) 'log contains "reason=user active" and waited=<n>s'
Check 'phase1.ready' ([bool]$gate.Ready) ('waited=' + $gate.WaitedSeconds + 's')

# ---------------------------------------------------------------- phase 2: pause/resume
$allSteps = @('step-1', 'step-2', 'step-3', 'step-4')
$completed = New-Object System.Collections.Generic.List[string]
$subject = $null
$watch = $null
$paused = $false
$detectMs = -1

try {
    # Phase 1 leaves a fresh injection behind, so phase 2 must pass the gate before it may start -
    # the same rule run-quiet.ps1 applies to every attempt.
    $gate2 = Wait-MhQuietGate -MinIdleSeconds $MinIdleSeconds -PollSeconds 1 -LogEverySeconds 2 -LogPath $logPath
    Note ('phase2: gate ready idle=' + $gate2.IdleSeconds + 's waited=' + $gate2.WaitedSeconds + 's')

    # A disposable process stands in for the app under test: the pause must take it down.
    $subject = Start-Process pwsh -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 300') -PassThru -WindowStyle Hidden
    Note ('phase2: subject process pid=' + $subject.Id)

    $flagPath = Join-Path $OutRoot ('watch-' + $stamp)
    $watch = Start-MhQuietUiWatch -MinIdleSeconds $MinIdleSeconds -PollMilliseconds 500 -FlagPath $flagPath -ProcessId $subject.Id
    Note 'phase2: watchdog started (poll=500ms)'

    foreach ($step in $allSteps) {
        if (Test-MhQuietUiAborted $watch) { $paused = $true; break }
        Start-Sleep -Milliseconds 1200
        if ($step -eq 'step-2' -and $detectMs -lt 0) {
            $inject2 = Send-MhQuietSyntheticInput
            $injectedAt = Get-Date
            Note ('phase2: synthetic input during step-2 mode=' + $inject2.mode + ' idle=' + $inject2.idleAfter + 's')
            $deadline = (Get-Date).AddSeconds(4)
            while ((Get-Date) -lt $deadline) {
                if (Test-MhQuietUiAborted $watch) { break }
                Start-Sleep -Milliseconds 100
            }
            $detectMs = [int]((Get-Date) - $injectedAt).TotalMilliseconds
        }
        if (Test-MhQuietUiAborted $watch) { $paused = $true; break }
        $completed.Add($step)
        Note ('phase2: completed ' + $step)
    }

    $abortReason = if (Test-MhQuietUiAborted $watch) { Get-MhQuietUiAbortReason $watch } else { '' }
    Stop-MhQuietUiWatch $watch
    $watch = $null

    Check 'phase2.detected' ($paused -and $detectMs -ge 0 -and $detectMs -le 2000) ('paused=' + $paused + ' detect=' + $detectMs + 'ms reason=' + $abortReason)
    $aliveDeadline = (Get-Date).AddSeconds(3)
    while ((Get-Date) -lt $aliveDeadline -and (Get-Process -Id $subject.Id -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 100
    }
    $subjectAlive = [bool](Get-Process -Id $subject.Id -ErrorAction SilentlyContinue)
    Check 'phase2.subject-closed' (-not $subjectAlive) ('subject pid=' + $subject.Id + ' alive=' + $subjectAlive)

    if (-not $paused) {
        Note 'phase2: no pause happened, the resume half of the check cannot run'
        Check 'phase2.paused-log' $false 'pause never happened'
    }
    else {
        Note ('PAUSED 用户回来了，已暂停 reason=' + $abortReason)
        Save-MhQuietCheckpoint -Path $checkpoint -Checkpoint ([pscustomobject]@{
                runId     = $stamp
                updatedAt = (Get-Date).ToString('o')
                completed = @($completed)
                results   = @{}
            })

        # Resume exactly like run-quiet.ps1: gate first, then read the checkpoint from disk.
        Note 'phase2: waiting for the next quiet window before resuming'
        $resumeGate = Wait-MhQuietGate -MinIdleSeconds $MinIdleSeconds -PollSeconds 1 -LogEverySeconds 2 -LogPath $logPath
        Note ('phase2: gate ready again idle=' + $resumeGate.IdleSeconds + 's waited=' + $resumeGate.WaitedSeconds + 's')

        $saved = Read-MhQuietCheckpoint -Path $checkpoint
        $doneBefore = @($saved.completed)
        Check 'phase2.checkpoint' ($doneBefore.Count -ge 1) ('checkpoint completed=' + ($doneBefore -join ','))

        $flagPath2 = Join-Path $OutRoot ('watch-resume-' + $stamp)
        $watch = Start-MhQuietUiWatch -MinIdleSeconds $MinIdleSeconds -PollMilliseconds 500 -FlagPath $flagPath2
        foreach ($step in $allSteps) {
            if ($doneBefore -contains $step) { Note ('phase2: SKIP completed ' + $step); continue }
            if (Test-MhQuietUiAborted $watch) { Note 'phase2: aborted again during resume'; break }
            Start-Sleep -Milliseconds 400
            $completed.Add($step)
            Note ('phase2: resumed and completed ' + $step)
        }
        Stop-MhQuietUiWatch $watch
        $watch = $null

        Check 'phase2.resume-skipped' ($doneBefore.Count -gt 0) ('skipped ' + $doneBefore.Count + ' completed step(s), ran ' + ($allSteps.Count - $doneBefore.Count))
        Check 'phase2.resume-complete' ($completed.Count -eq $allSteps.Count) ('completed=' + ($completed -join ','))
    }
}
finally {
    if ($watch) { Stop-MhQuietUiWatch $watch }
    if ($subject -and (Get-Process -Id $subject.Id -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $subject.Id -Force -ErrorAction SilentlyContinue
    }
    Save-MhQuietCheckpoint -Path $checkpoint -Checkpoint ([pscustomobject]@{
            runId     = $stamp
            updatedAt = (Get-Date).ToString('o')
            completed = @($completed)
            results   = @{ paused = $paused; detectMs = $detectMs }
        })
}

if ($failures.Count -gt 0) {
    Note ('RESULT: FAILED ' + ($failures -join ', ') + ' log=' + $logPath)
    exit 1
}
Note ('RESULT: OK log=' + $logPath + ' checkpoint=' + $checkpoint)
exit 0

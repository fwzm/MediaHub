<#
.SYNOPSIS
    MediaHub W6-28 acceptance: the transfer layer over real, separate processes
    (W6-16 plaintext, W6-24 shared-secret tags, W6-26 TLS 1.3 with a pinned fingerprint,
    W6-37 the one-byte receipt frame that tells "decoded" from "merely closed").

.DESCRIPTION
    Every case starts a REAL "transfer serve" child process, reads the loopback port (and,
    when TLS is on, the announced certificate fingerprint) off its stderr, then runs a REAL
    "transfer send" child process against it. Nothing here is an in-process unit test: both
    sides are separate OS processes, which is the only way to see a handshake or a socket
    close behave the way a user's two terminals would.

    Case matrix (expected codes and write counts are asserted verbatim):

      A  plaintext, no auth                  send 0 / serve 0, one envelope lands
      B  plaintext + shared-secret tag       send 0 / serve 0, authenticate=true, one envelope
      C  TLS 1.3, no auth                    send 0 / serve 0, pin echoed, one envelope
      D  TLS 1.3 + tag                       send 0 / serve 0, authenticate=true + pin, one envelope
      E  wrong pin (64 zeros)                send 2 tls-fingerprint-mismatch, zero writes
      F  plaintext client -> TLS listener    serve 2 tls-handshake-failed, zero writes
      G  TLS client -> plaintext listener    send 2 tls-handshake-failed, serve 2 tls-not-enabled, zero writes
      H  TLS + two different secrets         serve 2 auth-mismatch, zero writes
      J  --await-receipt + --send-receipt    send 0 receiptConfirmed=true / serve receiptWritten=true
      K  --await-receipt, no receipt written send 2 receipt-missing / serve 0, two records
      L  --await-receipt, peer never closes  send 2 receipt-timeout, zero writes

    W6-45 adds the corners where TWO of the three optional switches are on at once, because W6-28
    (TLS x tag) and W6-37 (receipt alone) each measured only one axis at a time:

      M  TLS + tag + receipt, all on         send 0 receiptConfirmed=true / serve 0 receiptWritten=true, 2 records
      N  same, but the two secrets differ    serve 2 auth-mismatch / send 2 receipt-missing, zero writes
      O  wrong pin with the tag switched on  send 2 tls-fingerprint-mismatch / no auth-* anywhere, zero writes
      P  plaintext listener, TLS sender      send 2 tls-handshake-failed (never receipt-missing) / serve 2 tls-not-enabled

    W6-50 turns the receive budget (a hard-coded fifteen seconds) into a switch, so two cases cover the
    option itself, with no sockets beyond the two real child processes:

      S1  --budget-ms 1200, nobody dials    serve 2 transfer-timeout after ~1.2 s, "budget: 1200 ms" on stderr
      S2  --budget-ms 30000, a real sender  send 0 / serve 0, two records, JSON budgetMs=30000

    Each case also asserts: the receiver's sync-sources.json holds exactly the sender's two
    sources (or does not exist at all when the case must not write), the receiver's
    sync-baseline.json exists only after a successful apply, and no log line contains either
    shared secret in plaintext.

    Nothing here starts the MediaHub GUI, writes D:\mh-out, installs anything or needs
    administrator rights. Every child process has a timeout: on expiry it is killed and the
    case FAILs instead of hanging the run.

    The script prints "PASS/FAIL <case> :: <detail>" lines, a final table, and
    "<OutDir>/w6-28-summary.json". It exits 0 only when every case passed.

.PARAMETER OutDir
    Where logs, the per-case receiver snapshots and the summary JSON go.
    Default: D:/mh-verify/w6-28

.PARAMETER SkipBuild
    Reuse the SyncCli dll that is already on disk instead of running dotnet build once.

.PARAMETER KeepArtifacts
    Keep the per-case sender/receiver stores (the script deletes them otherwise and keeps a
    snapshot of the receiver store in the case log directory).

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/transfer-tls-auth.ps1

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/transfer-tls-auth.ps1 -SkipBuild -KeepArtifacts
#>
[CmdletBinding()]
param(
    [string]$OutDir = 'D:/mh-verify/w6-28',
    [switch]$SkipBuild,
    [switch]$KeepArtifacts
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$script:utf8 = [System.Text.UTF8Encoding]::new($false)
$script:nl = [Environment]::NewLine
$script:repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$script:cliProject = Join-Path $script:repoRoot 'windows/src/MediaHub.SyncCli/MediaHub.SyncCli.csproj'
$script:cliDll = Join-Path $script:repoRoot 'windows/src/MediaHub.SyncCli/bin/Debug/net10.0/MediaHub.SyncCli.dll'
$script:outDir = [System.IO.Path]::GetFullPath($OutDir)
$script:rootsDir = Join-Path $script:outDir '_roots'
$script:summaryPath = Join-Path $script:outDir 'w6-28-summary.json'
$script:startedAt = Get-Date
$script:portWaitMs = 25000
$script:exitWaitMs = 40000

# Two sources are enough to tell "the envelope landed" from "some bytes arrived": the receiver
# writes both or neither, so a partial apply cannot be mistaken for success.
$script:expectedSources = @('Living Room Emby', 'WebDAV Gate')
$script:secretA = 'w628-acceptance-secret-alpha'
$script:secretB = 'w628-acceptance-secret-beta'
$script:envA = 'MH_W628_SECRET_A'
$script:envB = 'MH_W628_SECRET_B'

$script:results = New-Object 'System.Collections.Generic.List[object]'
$script:spawned = New-Object 'System.Collections.Generic.List[int]'

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, $script:utf8)
}

function Read-Shared {
    # The child keeps its redirected file open, so a plain Get-Content can collide with it.
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    try {
        $stream = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
        try {
            $reader = New-Object System.IO.StreamReader($stream, $script:utf8)
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $stream.Dispose() }
    } catch {
        return ''
    }
}

function Get-JsonDocument {
    # The CLI writes exactly one JSON document on stdout; find the first line that starts with {
    param([string]$Text)
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line.TrimStart().StartsWith('{')) {
            try { return ($line | ConvertFrom-Json) } catch { return $null }
        }
    }
    return $null
}

function Get-ErrorCode {
    param([string]$Stdout, [string]$Stderr, $Document)
    if ($null -ne $Document -and $null -ne $Document.error -and $Document.error.code) { return [string]$Document.error.code }
    if ($Stderr -match 'error ([a-z0-9-]+):') { return $Matches[1] }
    return ''
}

function New-SenderStore {
    param([string]$Directory)
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $records = @(
        [ordered]@{
            serverId = 'srv-emby'; type = 'Emby'; sourceTypeRaw = 'emby'
            url = 'http://nas-a:8096/emby/'; accountScope = 'alice'
            displayName = 'Living Room Emby'; recordRevision = 3
        },
        [ordered]@{
            serverId = 'srv-dav'; type = 'WebDav'; sourceTypeRaw = 'webdav'
            url = 'https://dav.example/gate'
            displayName = 'WebDAV Gate'; recordRevision = 2
        }
    )
    Write-Utf8 -Path (Join-Path $Directory 'sync-sources.json') -Text (ConvertTo-Json -InputObject @($records) -Depth 6)
}

function Start-CliProcess {
    param(
        [string]$Name,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$StdoutPath,
        [Parameter(Mandatory)][string]$StderrPath
    )
    $process = Start-Process -FilePath 'dotnet' -ArgumentList $Arguments -PassThru -NoNewWindow -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    $script:spawned.Add($process.Id)
    return $process
}

function Wait-ForExitOrKill {
    param([Parameter(Mandatory)]$Process, [int]$TimeoutMs, [string]$Label)
    if ($Process.WaitForExit($TimeoutMs)) { return $true }
    try { $Process.Kill() } catch { }
    try { $Process.WaitForExit(5000) | Out-Null } catch { }
    Write-Host ('  ' + $Label + ' exceeded ' + $TimeoutMs + ' ms and was killed')
    return $false
}

function Wait-ForServeReady {
    # stderr is the progress channel: the fingerprint (TLS only) comes first, then the port.
    param([Parameter(Mandatory)]$Process, [string]$StderrPath)
    $deadline = (Get-Date).AddMilliseconds($script:portWaitMs)
    $fingerprint = ''
    while ((Get-Date) -lt $deadline) {
        $text = Read-Shared -Path $StderrPath
        if (-not $fingerprint -and $text -match 'certificate fingerprint: ([0-9a-f]{64})') { $fingerprint = $Matches[1] }
        if ($text -match 'listening 127\.0\.0\.1:(\d+)') {
            $script:lastPort = [int]$Matches[1]
            $script:lastFingerprint = $fingerprint
            return $true
        }
        if ($Process.HasExited) {
            $script:lastPort = 0
            $script:lastFingerprint = $fingerprint
            return $false
        }
        Start-Sleep -Milliseconds 80
    }
    $script:lastPort = 0
    $script:lastFingerprint = $fingerprint
    return $false
}

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function New-FailedCase {
    # Used when a case cannot even start: the run still prints a row for it instead of aborting.
    param([string]$Name, [string]$Title, [string]$Expected, [string]$Detail, [string]$SendStderr = '', [string]$ServeStderr = '')
    $actual = [ordered]@{
        sendExit = -1; serveExit = -1; sendCode = ''; serveCode = ''; frames = -1
        receiverRecords = 0; envelopeCount = 0; receiverStoreExists = $false; receiverBaseline = $false
        receiverDisplayNames = ''; port = 0; fingerprint = ''; durationMs = 0
        sendStderr = $SendStderr; serveStderr = $ServeStderr
    }
    return [ordered]@{ Name = $Name; Title = $Title; Expected = $Expected; Actual = $actual; Detail = $Detail; Passed = $false }
}

function Test-CaseLogsForSecret {
    param([string]$Directory, [string[]]$Secrets)
    foreach ($file in (Get-ChildItem -LiteralPath $Directory -File -ErrorAction SilentlyContinue)) {
        $text = Read-Shared -Path $file.FullName
        foreach ($secret in $Secrets) {
            if ($secret -and $text.Contains($secret)) {
                throw ('the shared secret leaked into ' + $file.Name)
            }
        }
    }
}

function Invoke-TransferCase {
    param([Parameter(Mandatory)]$Spec)

    $name = $Spec.Name
    $caseDir = Join-Path $script:outDir $name
    $rootDir = Join-Path $script:rootsDir $name
    New-Item -ItemType Directory -Path $caseDir, $rootDir -Force | Out-Null
    $senderRoot = Join-Path $rootDir 'sender'
    $receiverRoot = Join-Path $rootDir 'receiver'
    New-SenderStore -Directory $senderRoot

    $serveOut = Join-Path $caseDir 'serve.stdout.txt'
    $serveErr = Join-Path $caseDir 'serve.stderr.txt'
    $sendOut = Join-Path $caseDir 'send.stdout.txt'
    $sendErr = Join-Path $caseDir 'send.stderr.txt'

    $secretsInPlay = @()
    if ($Spec.ServeSecretEnv) {
        Set-Item -Path ('Env:' + $Spec.ServeSecretEnv) -Value $Spec.ServeSecret
        $secretsInPlay += $Spec.ServeSecret
    }
    if ($Spec.SendSecretEnv) {
        Set-Item -Path ('Env:' + $Spec.SendSecretEnv) -Value $Spec.SendSecret
        $secretsInPlay += $Spec.SendSecret
    }

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $serveArguments = @($script:cliDll, 'transfer', 'serve', '--root', $receiverRoot, '--device', $Spec.ServeDevice, '--port', '0', '--apply', '--json') + @($Spec.ServeExtra)
    try {
        $serve = Start-CliProcess -Name ($name + '-serve') -Arguments $serveArguments -StdoutPath $serveOut -StderrPath $serveErr
    } catch {
        return New-FailedCase -Name $name -Title $Spec.Title -Expected $Spec.Expect.Table -Detail ('the listener could not be started: ' + $_.Exception.Message)
    }
    $ready = Wait-ForServeReady -Process $serve -StderrPath $serveErr
    $port = $script:lastPort
    $fingerprint = $script:lastFingerprint
    if (-not $ready) {
        try { $serve.Kill() } catch { }
        try { $serve.WaitForExit(5000) | Out-Null } catch { }
        return New-FailedCase -Name $name -Title $Spec.Title -Expected $Spec.Expect.Table -ServeStderr (Read-Shared -Path $serveErr) -Detail ('the listener never announced a port; serve.stderr.txt: ' + (Read-Shared -Path $serveErr).Trim())
    }

    $pinArgument = @()
    if ($Spec.ClientTls) {
        $pin = $fingerprint
        if ($Spec.PinOverride) { $pin = $Spec.PinOverride }
        $pinArgument = @('--tls', '--expect-fingerprint', $pin)
    }
    $sendArguments = @($script:cliDll, 'transfer', 'send', '--to', ('127.0.0.1:' + $port), '--root', $senderRoot, '--device', $Spec.SendDevice, '--json') + $pinArgument + @($Spec.SendExtra)
    try {
        $send = Start-CliProcess -Name ($name + '-send') -Arguments $sendArguments -StdoutPath $sendOut -StderrPath $sendErr
    } catch {
        try { $serve.Kill() } catch { }
        return New-FailedCase -Name $name -Title $Spec.Title -Expected $Spec.Expect.Table -Detail ('the sender could not be started: ' + $_.Exception.Message)
    }

    $sendExited = Wait-ForExitOrKill -Process $send -TimeoutMs $script:exitWaitMs -Label 'send'
    $serveExited = Wait-ForExitOrKill -Process $serve -TimeoutMs $script:exitWaitMs -Label 'serve'
    $stopwatch.Stop()

    $sendStdout = Read-Shared -Path $sendOut
    $sendStderr = Read-Shared -Path $sendErr
    $serveStdout = Read-Shared -Path $serveOut
    $serveStderr = Read-Shared -Path $serveErr
    $sendJson = Get-JsonDocument -Text $sendStdout
    $serveJson = Get-JsonDocument -Text $serveStdout

    # Receiver state: the store file only exists once something was applied, so "absent" is the
    # strongest possible statement of "wrote nothing".
    $storePath = Join-Path $receiverRoot 'sync-sources.json'
    $baselinePath = Join-Path $receiverRoot 'sync-baseline.json'
    $storeExists = Test-Path -LiteralPath $storePath
    $baselineExists = Test-Path -LiteralPath $baselinePath
    $records = @()
    if ($storeExists) {
        $storeText = Read-Shared -Path $storePath
        Write-Utf8 -Path (Join-Path $caseDir 'receiver-sync-sources.json') -Text $storeText
        $parsed = $storeText | ConvertFrom-Json
        if ($null -ne $parsed) { $records = @($parsed) }
    }
    if ($baselineExists) {
        Write-Utf8 -Path (Join-Path $caseDir 'receiver-sync-baseline.json') -Text (Read-Shared -Path $baselinePath)
    }
    $recordCount = @($records).Count
    $displayNames = @($records | ForEach-Object { [string]$_.displayName })
    $envelopeCount = 0
    if ($recordCount -eq $script:expectedSources.Count) { $envelopeCount = 1 }

    $sendCode = Get-ErrorCode -Stdout $sendStdout -Stderr $sendStderr -Document $sendJson
    $serveCode = Get-ErrorCode -Stdout $serveStdout -Stderr $serveStderr -Document $serveJson
    $sendExit = -1
    if ($sendExited) { $sendExit = $send.ExitCode }
    $serveExit = -1
    if ($serveExited) { $serveExit = $serve.ExitCode }

    $actual = [ordered]@{
        sendExit             = $sendExit
        serveExit            = $serveExit
        sendCode             = $sendCode
        serveCode            = $serveCode
        frames               = -1
        receiverRecords      = $recordCount
        envelopeCount        = $envelopeCount
        receiverStoreExists  = $storeExists
        receiverBaseline     = $baselineExists
        receiverDisplayNames = ($displayNames -join ', ')
        port                 = $port
        fingerprint          = $fingerprint
        receiptConfirmed     = $null
        receiptWritten       = $null
        durationMs           = [int]$stopwatch.ElapsedMilliseconds
    }
    if ($null -ne $sendJson) { $actual.frames = [int]$sendJson.frames }

    try {
        Assert-Condition ($sendExited) 'the send process had to be killed (it never exited)'
        Assert-Condition ($serveExited) 'the serve process had to be killed (it never exited)'

        # 1 the exit codes the task book asks for
        Assert-Condition ($sendExit -eq $Spec.Expect.SendExit) ('send exit code: expected ' + $Spec.Expect.SendExit + ' but was ' + $sendExit)
        Assert-Condition ($serveExit -eq $Spec.Expect.ServeExit) ('serve exit code: expected ' + $Spec.Expect.ServeExit + ' but was ' + $serveExit)

        # 2 the stable codes, verbatim
        Assert-Condition ($sendCode -eq $Spec.Expect.SendCode) ('send stable code: expected [' + $Spec.Expect.SendCode + '] but was [' + $sendCode + ']')
        Assert-Condition ($serveCode -eq $Spec.Expect.ServeCode) ('serve stable code: expected [' + $Spec.Expect.ServeCode + '] but was [' + $serveCode + ']')
        if ($Spec.Expect.SendCode) {
            Assert-Condition ($sendStderr.Contains('error ' + $Spec.Expect.SendCode + ':')) ('send stderr must carry "error ' + $Spec.Expect.SendCode + ':"')
        }
        if ($Spec.Expect.ServeCode) {
            Assert-Condition ($serveStderr.Contains('error ' + $Spec.Expect.ServeCode + ':')) ('serve stderr must carry "error ' + $Spec.Expect.ServeCode + ':"')
        }

        # 2b W6-45: a combination case can forbid a whole code family. This is what turns "the TLS
        # mismatch did not get rewritten into an auth or receipt failure" from an observation into an
        # assertion: the expected code is checked above, and the absence of the other family here.
        if ($Spec.Contains('ForbidSendCodePrefixes') -or $Spec.Contains('ForbidServeCodePrefixes')) {
            foreach ($prefix in @($Spec.ForbidSendCodePrefixes)) {
                if (-not $prefix) { continue }
                Assert-Condition (-not $sendCode.StartsWith($prefix, [System.StringComparison]::Ordinal)) ('send reported a forbidden ' + $prefix + '* code: ' + $sendCode)
                Assert-Condition (-not $sendStderr.Contains('error ' + $prefix)) ('send stderr carried a forbidden ' + $prefix + '* error line')
            }
            foreach ($prefix in @($Spec.ForbidServeCodePrefixes)) {
                if (-not $prefix) { continue }
                Assert-Condition (-not $serveCode.StartsWith($prefix, [System.StringComparison]::Ordinal)) ('serve reported a forbidden ' + $prefix + '* code: ' + $serveCode)
                Assert-Condition (-not $serveStderr.Contains('error ' + $prefix)) ('serve stderr carried a forbidden ' + $prefix + '* error line')
            }
        }

        # 3 what actually landed on the receiver
        if ($Spec.Expect.Records -eq 0) {
            Assert-Condition (-not $storeExists) 'a failed case must not create the receiver sync-sources.json at all'
            Assert-Condition (-not $baselineExists) 'a failed case must not create the receiver sync-baseline.json at all'
            Assert-Condition ($envelopeCount -eq 0) 'a failed case must not count as an accepted envelope'
        } else {
            Assert-Condition ($storeExists) 'the receiver store was not written'
            Assert-Condition ($recordCount -eq $Spec.Expect.Records) ('receiver record count: expected ' + $Spec.Expect.Records + ' but was ' + $recordCount)
            foreach ($expectedName in $script:expectedSources) {
                Assert-Condition ($displayNames -contains $expectedName) ('receiver is missing source ' + $expectedName)
            }
            Assert-Condition ($baselineExists) 'the receiver baseline was not updated after a successful apply'
            Assert-Condition ($null -ne $serveJson) 'the serve process printed no JSON document'
            Assert-Condition ($serveJson.applied -eq $true) 'serve JSON must report applied=true under --apply'
            Assert-Condition ([int]$serveJson.report.applied -eq $Spec.Expect.Records) ('serve report.applied: expected ' + $Spec.Expect.Records + ' but was ' + $serveJson.report.applied)
            Assert-Condition ([int]$serveJson.report.baselineEntries -eq $Spec.Expect.Records) 'serve report.baselineEntries must equal the applied record count'
            Assert-Condition ([int]$serveJson.counts.apply -eq $Spec.Expect.Records) 'serve counts.apply must equal the applied record count'
            Assert-Condition ($serveJson.from -eq $Spec.SendDevice) ('serve JSON from: expected ' + $Spec.SendDevice + ' but was ' + $serveJson.from)
            Assert-Condition ($serveJson.to -eq $Spec.ServeDevice) ('serve JSON to: expected ' + $Spec.ServeDevice + ' but was ' + $serveJson.to)
        }

        # 4 the frame shape W6-16/W6-24 define: 1 chunk, or challenge + chunk (+ tag under auth)
        if ($null -ne $sendJson -and $Spec.Expect.Frames -ge 0) {
            Assert-Condition ([int]$sendJson.frames -eq $Spec.Expect.Frames) ('send frame count: expected ' + $Spec.Expect.Frames + ' but was ' + $sendJson.frames)
            Assert-Condition ([int]$sendJson.sourceCount -eq $script:expectedSources.Count) 'send JSON sourceCount must be 2'
        }

        # 5 the authentication flag and the TLS pin, exactly as the CLI surfaces them
        if ($Spec.Expect.ServeExit -ne 0) {
            # A failing serve prints one error document, not a result document: the flags it was
            # given are on its stderr ('error <code>: ...'), so only the error shape is asserted here.
            Assert-Condition ($null -ne $serveJson) 'serve printed no JSON document'
            Assert-Condition ($serveJson.ok -eq $false) 'a failing serve must print ok=false'
        }
        if ($Spec.ServeSecretEnv -and $Spec.Expect.ServeExit -eq 0) {
            Assert-Condition ($null -ne $serveJson) 'serve printed no JSON document'
            Assert-Condition ($serveJson.authenticate -eq $true) 'serve JSON must report authenticate=true'
            Assert-Condition ([string]$serveJson.secretEnv -eq $Spec.ServeSecretEnv) ('serve JSON secretEnv: expected ' + $Spec.ServeSecretEnv + ' but was ' + $serveJson.secretEnv)
        }
        if ($Spec.SendSecretEnv -and $Spec.Expect.SendExit -eq 0) {
            Assert-Condition ($null -ne $sendJson) 'send printed no JSON document'
            Assert-Condition ($sendJson.authenticate -eq $true) 'send JSON must report authenticate=true'
            Assert-Condition ([string]$sendJson.secretEnv -eq $Spec.SendSecretEnv) ('send JSON secretEnv: expected ' + $Spec.SendSecretEnv + ' but was ' + $sendJson.secretEnv)
        }
        if ($Spec.TlsListener) {
            Assert-Condition ($fingerprint -match '^[0-9a-f]{64}$') 'the TLS listener must announce a lowercase 64 hex fingerprint'
            Assert-Condition ($serveStderr.Contains('certificate fingerprint: ' + $fingerprint)) 'serve stderr must carry the announced fingerprint'
            if ($Spec.Expect.ServeExit -eq 0) {
                Assert-Condition ($null -ne $serveJson) 'serve printed no JSON document'
                Assert-Condition ([string]$serveJson.certificateFingerprint -eq $fingerprint) 'serve JSON certificateFingerprint must equal the announced pin'
                Assert-Condition ($serveJson.tls -eq $true) 'serve JSON must report tls=true'
            }
        } else {
            Assert-Condition (-not $serveStderr.Contains('certificate fingerprint:')) 'a plaintext listener must not announce a certificate fingerprint'
        }
        if ($Spec.Expect.SendExit -ne 0) {
            Assert-Condition ($null -ne $sendJson) 'send printed no JSON document'
            Assert-Condition ($sendJson.ok -eq $false) 'a failing send must print ok=false'
        }
        if ($Spec.Expect.SendExit -eq 0 -and $null -ne $sendJson) {
            if ($Spec.ClientTls) {
                Assert-Condition ([string]$sendJson.expectFingerprint -eq $fingerprint) 'send JSON expectFingerprint must echo the pin in lowercase'
                Assert-Condition ([string]$sendJson.certificateFingerprint -eq $fingerprint) 'send JSON certificateFingerprint must equal the pinned fingerprint'
            } else {
                Assert-Condition ($null -eq $sendJson.certificateFingerprint) 'a plaintext send must not report a certificate fingerprint'
            }
            Assert-Condition ($sendJson.tls -eq $Spec.ClientTls) 'send JSON tls flag must match the command line'
        }

        # 6 no shared secret may appear anywhere in the case logs
        Test-CaseLogsForSecret -Directory $caseDir -Secrets $secretsInPlay

        # 7 W6-30: the delivery confirmation surface. A send that did not ask must say so (null),
        # and a send that asked must report what the wait for the peer's close saw.
        if ($null -ne $sendJson -and $Spec.Expect.SendExit -eq 0) {
            $askedForIt = ($Spec.Contains('ConfirmDelivery') -and $Spec.ConfirmDelivery)
            Assert-Condition ($sendJson.confirmDelivery -eq $askedForIt) ('send JSON confirmDelivery: expected ' + $askedForIt + ' but was ' + $sendJson.confirmDelivery)
            if ($askedForIt) {
                Assert-Condition ($sendJson.deliveryConfirmed -eq $true) 'a confirming send that succeeded must report deliveryConfirmed=true'
                Assert-Condition ($Spec.ExpectedDeliveryConfirmed -eq $true) 'the case and its expectation disagree about the confirmation'
            } else {
                Assert-Condition ($null -eq $sendJson.deliveryConfirmed) 'a fire-and-forget send must report deliveryConfirmed=null instead of claiming a receipt'
            }
        }

        # 8 W6-37: the receipt frame. A send that asked has to say whether the 0x04 byte arrived, and a
        # serve that was told to write one has to say whether it wrote it; when the flag is absent the
        # field must stay null so "not asked" is never confused with "asked and refused".
        $askedForReceipt = ($Spec.Contains('AwaitReceipt') -and $Spec.AwaitReceipt)
        $toldToWriteReceipt = ($Spec.Contains('SendReceipt') -and $Spec.SendReceipt)
        # A failing side prints one error document ({"ok":false,...}), which carries no flags at all,
        # so the flag assertions only run for the side that actually produced a result document.
        if ($null -ne $sendJson -and $Spec.Expect.SendExit -eq 0) {
            Assert-Condition ($sendJson.awaitReceipt -eq $askedForReceipt) ('send JSON awaitReceipt: expected ' + $askedForReceipt + ' but was ' + $sendJson.awaitReceipt)
            $actual.receiptConfirmed = $sendJson.receiptConfirmed
            if ($askedForReceipt) {
                Assert-Condition ($sendJson.receiptConfirmed -eq $true) 'a send that awaited a receipt and succeeded must report receiptConfirmed=true'
            } else {
                Assert-Condition ($null -eq $sendJson.receiptConfirmed) 'a send that never asked for a receipt must report receiptConfirmed=null'
            }
        }
        if ($null -ne $serveJson -and $Spec.Expect.ServeExit -eq 0) {
            Assert-Condition ($serveJson.sendReceipt -eq $toldToWriteReceipt) ('serve JSON sendReceipt: expected ' + $toldToWriteReceipt + ' but was ' + $serveJson.sendReceipt)
            $actual.receiptWritten = $serveJson.receiptWritten
            if ($toldToWriteReceipt) {
                Assert-Condition ($serveJson.receiptWritten -eq $true) 'a serve told to write a receipt and that decoded the envelope must report receiptWritten=true'
            } else {
                Assert-Condition ($null -eq $serveJson.receiptWritten) 'a serve that was not told to write a receipt must report receiptWritten=null'
            }
        }

        $detail = 'send=' + $sendExit + ' serve=' + $serveExit + ' frames=' + $actual.frames + ' records=' + $recordCount + ' envelopes=' + $envelopeCount + ' codes=[' + $sendCode + '/' + $serveCode + '] ' + $actual.durationMs + ' ms'
        return [ordered]@{ Name = $name; Title = $Spec.Title; Expected = $Spec.Expect.Table; Actual = $actual; Detail = $detail; Passed = $true }
    } catch {
        $actual.sendStderr = $sendStderr
        $actual.serveStderr = $serveStderr
        return [ordered]@{ Name = $name; Title = $Spec.Title; Expected = $Spec.Expect.Table; Actual = $actual; Detail = $_.Exception.Message; Passed = $false }
    } finally {
        foreach ($process in @($send, $serve)) {
            if ($null -ne $process -and -not $process.HasExited) { try { $process.Kill() } catch { } }
        }
        if (-not $KeepArtifacts) {
            try { Remove-Item -LiteralPath $rootDir -Recurse -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
}

function Invoke-SilentPeerCase {
    # W6-30: the one shape where a one-way sender really can observe a missing receipt - a peer that
    # accepts the connection, takes the bytes and never closes. There is no CLI listener for that, so the
    # peer is a PowerShell job holding a loopback socket open.
    $name = 'I-silent-peer'
    $title = 'I --confirm-delivery against a peer that accepts and never closes'
    $expected = 'send 2 delivery-unconfirmed / no write'
    $caseDir = Join-Path $script:outDir $name
    $rootDir = Join-Path $script:rootsDir $name
    New-Item -ItemType Directory -Path $caseDir, $rootDir -Force | Out-Null
    $senderRoot = Join-Path $rootDir 'sender'
    New-SenderStore -Directory $senderRoot
    $sendOut = Join-Path $caseDir 'send.stdout.txt'
    $sendErr = Join-Path $caseDir 'send.stderr.txt'

    $peer = $null
    $send = $null
    try {
        $peer = Start-Job -ScriptBlock {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
            Write-Output ('PORT ' + $port)
            $client = $listener.AcceptTcpClient()
            Start-Sleep -Seconds 30
            $client.Dispose()
            $listener.Stop()
        }

        $port = 0
        $deadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $deadline) {
            foreach ($line in @(Receive-Job -Job $peer -Keep)) {
                if ([string]$line -match '^PORT (\d+)$') { $port = [int]$Matches[1] }
            }
            if ($port -gt 0) { break }
            Start-Sleep -Milliseconds 100
        }
        if ($port -le 0) { throw 'the silent peer never announced its port' }

        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $send = Start-CliProcess -Name ($name + '-send') -Arguments @($script:cliDll, 'transfer', 'send', '--to', ('127.0.0.1:' + $port), '--root', $senderRoot, '--device', 'tx-device', '--json', '--confirm-delivery') -StdoutPath $sendOut -StderrPath $sendErr
        $exited = Wait-ForExitOrKill -Process $send -TimeoutMs $script:exitWaitMs -Label 'send'
        $stopwatch.Stop()

        $sendStdout = Read-Shared -Path $sendOut
        $sendStderr = Read-Shared -Path $sendErr
        $sendJson = Get-JsonDocument -Text $sendStdout
        $sendCode = Get-ErrorCode -Stdout $sendStdout -Stderr $sendStderr -Document $sendJson
        $sendExit = -1
        if ($exited) { $sendExit = $send.ExitCode }
        $message = ''
        if ($null -ne $sendJson -and $null -ne $sendJson.error) { $message = [string]$sendJson.error.message }

        $actual = [ordered]@{
            sendExit = $sendExit; serveExit = -1; sendCode = $sendCode; serveCode = ''
            frames = -1; receiverRecords = 0; envelopeCount = 0
            receiverStoreExists = $false; receiverBaseline = $false; receiverDisplayNames = ''
            port = $port; fingerprint = ''; durationMs = [int]$stopwatch.ElapsedMilliseconds
        }

        try {
            Assert-Condition ($exited) 'the send had to be killed (it never exited)'
            Assert-Condition ($sendExit -eq 2) ('send exit code: expected 2 but was ' + $sendExit)
            Assert-Condition ($sendCode -eq 'delivery-unconfirmed') ('send stable code: expected [delivery-unconfirmed] but was [' + $sendCode + ']')
            Assert-Condition ($sendStderr.Contains('error delivery-unconfirmed:')) 'send stderr must carry "error delivery-unconfirmed:"'
            Assert-Condition ($message.Contains('did not close the connection within 2000 ms')) ('the failure must name the budget; message was: ' + $message)
            Assert-Condition ($actual.durationMs -ge 1900) ('the confirmation budget must really be waited out; the send took ' + $actual.durationMs + ' ms')
            Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $rootDir 'receiver'))) 'nothing may be written for a peer that never closed'
            $detail = 'send=' + $sendExit + ' code=' + $sendCode + ' no receipt in ' + $actual.durationMs + ' ms'
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $detail; Passed = $true }
        } catch {
            $actual.sendStderr = $sendStderr
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $_.Exception.Message; Passed = $false }
        }
    } finally {
        if ($null -ne $send -and -not $send.HasExited) { try { $send.Kill() } catch { } }
        if ($null -ne $peer) {
            try { Stop-Job -Job $peer -ErrorAction SilentlyContinue } catch { }
            try { Remove-Job -Job $peer -Force -ErrorAction SilentlyContinue } catch { }
        }
        if (-not $KeepArtifacts) { try { Remove-Item -LiteralPath $rootDir -Recurse -Force -ErrorAction SilentlyContinue } catch { } }
    }
}

function Invoke-ReceiptTimeoutCase {
    # W6-37: a peer that takes every byte, never writes the 0x04 receipt byte and never closes. The
    # listener CLI always closes, so the only way to observe the receipt budget running out is a peer
    # of our own - it is also the shape that tells "no receipt yet" from "no receipt ever".
    $name = 'L-receipt-timeout'
    $title = 'L --await-receipt against a peer that never sends a receipt'
    $expected = 'send 2 receipt-timeout / no write'
    $caseDir = Join-Path $script:outDir $name
    $rootDir = Join-Path $script:rootsDir $name
    New-Item -ItemType Directory -Path $caseDir, $rootDir -Force | Out-Null
    $senderRoot = Join-Path $rootDir 'sender'
    New-SenderStore -Directory $senderRoot
    $sendOut = Join-Path $caseDir 'send.stdout.txt'
    $sendErr = Join-Path $caseDir 'send.stderr.txt'

    $peer = $null
    $send = $null
    try {
        $peer = Start-Job -ScriptBlock {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
            Write-Output ('PORT ' + $port)
            $client = $listener.AcceptTcpClient()
            $stream = $client.GetStream()
            $stream.ReadTimeout = 500
            $buffer = New-Object 'System.Byte[]' 8192
            $total = 0
            while ($true) {
                try {
                    $read = $stream.Read($buffer, 0, $buffer.Length)
                    if ($read -le 0) { break }
                    $total += $read
                } catch {
                    break
                }
            }
            Write-Output ('BYTES ' + $total)
            # W6-37: read everything, then hold the connection open and stay silent. No 0x04, no close.
            Start-Sleep -Seconds 30
            $client.Dispose()
            $listener.Stop()
        }

        $port = 0
        $peerBytes = -1
        $deadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $deadline) {
            foreach ($line in @(Receive-Job -Job $peer -Keep)) {
                $text = [string]$line
                if ($text -match '^PORT (\d+)$') { $port = [int]$Matches[1] }
                if ($text -match '^BYTES (\d+)$') { $peerBytes = [int]$Matches[1] }
            }
            # Only the port can arrive before the sender connects: the peer announces BYTES after it
            # has drained the envelope, so waiting for that here would wait for the send we have not
            # started yet (a deadlock the first version of this case really had).
            if ($port -gt 0) { break }
            Start-Sleep -Milliseconds 100
        }
        if ($port -le 0) { throw 'the silent peer never announced its port' }

        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $send = Start-CliProcess -Name ($name + '-send') -Arguments @($script:cliDll, 'transfer', 'send', '--to', ('127.0.0.1:' + $port), '--root', $senderRoot, '--device', 'tx-device', '--json', '--await-receipt') -StdoutPath $sendOut -StderrPath $sendErr
        $exited = Wait-ForExitOrKill -Process $send -TimeoutMs $script:exitWaitMs -Label 'send'
        $stopwatch.Stop()

        $sendStdout = Read-Shared -Path $sendOut
        $sendStderr = Read-Shared -Path $sendErr
        $sendJson = Get-JsonDocument -Text $sendStdout
        $sendCode = Get-ErrorCode -Stdout $sendStdout -Stderr $sendStderr -Document $sendJson
        $sendExit = -1
        if ($exited) { $sendExit = $send.ExitCode }
        $message = ''
        if ($null -ne $sendJson -and $null -ne $sendJson.error) { $message = [string]$sendJson.error.message }
        foreach ($line in @(Receive-Job -Job $peer -Keep)) {
            $text = [string]$line
            if ($text -match '^BYTES (\d+)$') { $peerBytes = [int]$Matches[1] }
        }

        $actual = [ordered]@{
            sendExit = $sendExit; serveExit = -1; sendCode = $sendCode; serveCode = ''
            frames = -1; receiverRecords = 0; envelopeCount = 0
            receiverStoreExists = $false; receiverBaseline = $false; receiverDisplayNames = ''
            port = $port; fingerprint = ''; receiptConfirmed = $null; receiptWritten = $null
            peerBytes = $peerBytes
            durationMs = [int]$stopwatch.ElapsedMilliseconds
        }

        try {
            Assert-Condition ($exited) 'the send had to be killed (it never exited)'
            Assert-Condition ($sendExit -eq 2) ('send exit code: expected 2 but was ' + $sendExit)
            Assert-Condition ($sendCode -eq 'receipt-timeout') ('send stable code: expected [receipt-timeout] but was [' + $sendCode + ']')
            Assert-Condition ($sendStderr.Contains('error receipt-timeout:')) 'send stderr must carry "error receipt-timeout:"'
            Assert-Condition ($message.Contains('kept the connection open for 2000 ms')) ('the failure must name the receipt budget; message was: ' + $message)
            Assert-Condition ($actual.durationMs -ge 1900) ('the receipt budget must really be waited out; the send took ' + $actual.durationMs + ' ms')
            Assert-Condition ($peerBytes -gt 0) ('the peer must have accepted the envelope bytes before going silent; it read ' + $peerBytes)
            Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $rootDir 'receiver'))) 'nothing may be written for a peer that never acknowledged'
            $detail = 'send=' + $sendExit + ' code=' + $sendCode + ' no receipt in ' + $actual.durationMs + ' ms'
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $detail; Passed = $true }
        } catch {
            $actual.sendStderr = $sendStderr
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $_.Exception.Message; Passed = $false }
        }
    } finally {
        if ($null -ne $send -and -not $send.HasExited) { try { $send.Kill() } catch { } }
        if ($null -ne $peer) {
            try { Stop-Job -Job $peer -ErrorAction SilentlyContinue } catch { }
            try { Remove-Job -Job $peer -Force -ErrorAction SilentlyContinue } catch { }
        }
        if (-not $KeepArtifacts) { try { Remove-Item -LiteralPath $rootDir -Recurse -Force -ErrorAction SilentlyContinue } catch { } }
    }
}

function Invoke-BudgetTimeoutCase {
    # W6-50: the receive budget used to be a hard-coded fifteen seconds. A listener nobody dials is the
    # cheapest way to prove that the option, not the constant, is the clock that runs out - and text mode
    # is the shape that prints the number, right after the port.
    $name = 'S1-budget-timeout'
    $title = 'S1 --budget-ms 1200 with no peer at all'
    $expected = 'serve 2 transfer-timeout after ~1.2 s, budget line on stderr, no writes'
    $caseDir = Join-Path $script:outDir $name
    $rootDir = Join-Path $script:rootsDir $name
    New-Item -ItemType Directory -Path $caseDir, $rootDir -Force | Out-Null
    $receiverRoot = Join-Path $rootDir 'receiver'
    New-Item -ItemType Directory -Path $receiverRoot -Force | Out-Null
    $serveOut = Join-Path $caseDir 'serve.stdout.txt'
    $serveErr = Join-Path $caseDir 'serve.stderr.txt'

    $serve = $null
    try {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $serve = Start-CliProcess -Name ($name + '-serve') -Arguments @($script:cliDll, 'transfer', 'serve', '--root', $receiverRoot, '--device', 'rx-device', '--port', '0', '--apply', '--budget-ms', '1200') -StdoutPath $serveOut -StderrPath $serveErr
        $exited = Wait-ForExitOrKill -Process $serve -TimeoutMs $script:exitWaitMs -Label 'serve'
        $stopwatch.Stop()

        $serveStdout = Read-Shared -Path $serveOut
        $serveStderr = Read-Shared -Path $serveErr
        $serveExit = -1
        if ($exited) { $serveExit = $serve.ExitCode }
        $serveJson = Get-JsonDocument -Text $serveStdout
        $serveCode = Get-ErrorCode -Stdout $serveStdout -Stderr $serveStderr -Document $serveJson
        $listeningAt = $serveStderr.IndexOf('listening 127.0.0.1:', [System.StringComparison]::Ordinal)
        $budgetAt = $serveStderr.IndexOf('budget: ', [System.StringComparison]::Ordinal)

        $actual = [ordered]@{
            serveExit = $serveExit; sendExit = -1; serveCode = $serveCode; sendCode = ''
            frames = -1; receiverRecords = 0; envelopeCount = 0
            receiverStoreExists = $false; receiverBaseline = $false; receiverDisplayNames = ''
            port = 0; fingerprint = ''; durationMs = [int]$stopwatch.ElapsedMilliseconds
        }

        try {
            Assert-Condition ($exited) 'the serve process had to be killed (it never exited)'
            Assert-Condition ($serveExit -eq 2) ('serve exit code: expected 2 but was ' + $serveExit)
            Assert-Condition ($serveCode -eq 'transfer-timeout') ('serve stable code: expected [transfer-timeout] but was [' + $serveCode + ']')
            Assert-Condition ($serveStderr.Contains('error transfer-timeout:')) 'serve stderr must carry "error transfer-timeout:"'
            Assert-Condition ($serveStderr.Contains('within 1.2 s')) ('the failure must name the configured budget; stderr was: ' + $serveStderr)
            Assert-Condition ($listeningAt -ge 0) 'the port must still be announced before the wait begins'
            Assert-Condition ($budgetAt -gt $listeningAt) 'the budget line belongs after the listening line'
            Assert-Condition ($serveStderr.Contains('budget: 1200 ms')) 'text mode must print "budget: 1200 ms"'
            Assert-Condition (-not $serveStderr.Contains('served one connection')) 'a serve that timed out must not report a connection'
            Assert-Condition (-not $serveStdout.Contains('{')) 'text mode must not print a JSON document'
            Assert-Condition ($actual.durationMs -ge 1100) ('the budget must really be waited out; the serve took ' + $actual.durationMs + ' ms')
            Assert-Condition ($actual.durationMs -lt 9000) ('the budget must not be overshot; the serve took ' + $actual.durationMs + ' ms')
            Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $receiverRoot 'sync-sources.json'))) 'a serve that timed out must not write the receiver store'
            $detail = 'serve=' + $serveExit + ' code=' + $serveCode + ' gave up in ' + $actual.durationMs + ' ms, no writes'
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $detail; Passed = $true }
        } catch {
            $actual.serveStderr = $serveStderr
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $_.Exception.Message; Passed = $false }
        }
    } finally {
        if ($null -ne $serve -and -not $serve.HasExited) { try { $serve.Kill() } catch { } }
        if (-not $KeepArtifacts) { try { Remove-Item -LiteralPath $rootDir -Recurse -Force -ErrorAction SilentlyContinue } catch { } }
    }
}

function Invoke-BudgetJsonCase {
    # W6-50: the number also has to reach a serve that succeeds, as budgetMs in the one JSON document. A
    # budget far larger than the transfer needs must change nothing else about the round.
    $name = 'S2-budget-in-json'
    $title = 'S2 --budget-ms 30000 against a real sender'
    $expected = 'send 0 / serve 0, two records, JSON budgetMs=30000'
    $caseDir = Join-Path $script:outDir $name
    $rootDir = Join-Path $script:rootsDir $name
    New-Item -ItemType Directory -Path $caseDir, $rootDir -Force | Out-Null
    $receiverRoot = Join-Path $rootDir 'receiver'
    New-Item -ItemType Directory -Path $receiverRoot -Force | Out-Null
    $senderRoot = Join-Path $rootDir 'sender'
    New-SenderStore -Directory $senderRoot
    $serveOut = Join-Path $caseDir 'serve.stdout.txt'
    $serveErr = Join-Path $caseDir 'serve.stderr.txt'
    $sendOut = Join-Path $caseDir 'send.stdout.txt'
    $sendErr = Join-Path $caseDir 'send.stderr.txt'

    $serve = $null
    $send = $null
    try {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $serve = Start-CliProcess -Name ($name + '-serve') -Arguments @($script:cliDll, 'transfer', 'serve', '--root', $receiverRoot, '--device', 'rx-device', '--port', '0', '--apply', '--json', '--budget-ms', '30000') -StdoutPath $serveOut -StderrPath $serveErr
        $ready = Wait-ForServeReady -Process $serve -StderrPath $serveErr
        Assert-Condition ($ready) ('the listener never announced its port; stderr was: ' + (Read-Shared -Path $serveErr))
        $port = $script:lastPort

        $send = Start-CliProcess -Name ($name + '-send') -Arguments @($script:cliDll, 'transfer', 'send', '--to', ('127.0.0.1:' + $port), '--root', $senderRoot, '--device', 'tx-device', '--json') -StdoutPath $sendOut -StderrPath $sendErr
        $sendExited = Wait-ForExitOrKill -Process $send -TimeoutMs $script:exitWaitMs -Label 'send'
        $serveExited = Wait-ForExitOrKill -Process $serve -TimeoutMs $script:exitWaitMs -Label 'serve'
        $stopwatch.Stop()

        $serveStdout = Read-Shared -Path $serveOut
        $serveStderr = Read-Shared -Path $serveErr
        $sendStdout = Read-Shared -Path $sendOut
        $sendStderr = Read-Shared -Path $sendErr
        $serveJson = Get-JsonDocument -Text $serveStdout
        $sendJson = Get-JsonDocument -Text $sendStdout
        $serveExit = -1
        if ($serveExited) { $serveExit = $serve.ExitCode }
        $sendExit = -1
        if ($sendExited) { $sendExit = $send.ExitCode }
        $serveCode = Get-ErrorCode -Stdout $serveStdout -Stderr $serveStderr -Document $serveJson
        $sendCode = Get-ErrorCode -Stdout $sendStdout -Stderr $sendStderr -Document $sendJson

        $storePath = Join-Path $receiverRoot 'sync-sources.json'
        $storeExists = Test-Path -LiteralPath $storePath
        $recordCount = 0
        $displayNames = @()
        if ($storeExists) {
            try {
                $records = @(ConvertFrom-Json -InputObject (Read-Shared -Path $storePath))
                $recordCount = $records.Count
                $displayNames = @($records | ForEach-Object { [string]$_.displayName })
            } catch { }
        }
        $baselineExists = Test-Path -LiteralPath (Join-Path $receiverRoot 'sync-baseline.json')

        $actual = [ordered]@{
            serveExit = $serveExit; sendExit = $sendExit; serveCode = $serveCode; sendCode = $sendCode
            frames = -1; receiverRecords = $recordCount; envelopeCount = $recordCount
            receiverStoreExists = $storeExists; receiverBaseline = $baselineExists
            receiverDisplayNames = ($displayNames -join ', ')
            port = $port; fingerprint = ''; durationMs = [int]$stopwatch.ElapsedMilliseconds
        }
        if ($null -ne $sendJson) { $actual.frames = [int]$sendJson.frames }

        try {
            Assert-Condition ($sendExited) 'the send process had to be killed (it never exited)'
            Assert-Condition ($serveExited) 'the serve process had to be killed (it never exited)'
            Assert-Condition ($sendExit -eq 0) ('send exit code: expected 0 but was ' + $sendExit + ' (' + $sendCode + ')')
            Assert-Condition ($serveExit -eq 0) ('serve exit code: expected 0 but was ' + $serveExit + ' (' + $serveCode + ')')
            Assert-Condition ($null -ne $serveJson) 'the serve process printed no JSON document'
            Assert-Condition ($null -ne $serveJson.budgetMs) 'serve JSON has no budgetMs field'
            Assert-Condition ([int]$serveJson.budgetMs -eq 30000) ('serve JSON budgetMs: expected 30000 but was ' + $serveJson.budgetMs)
            Assert-Condition ($serveJson.applied -eq $true) 'serve JSON must report applied=true under --apply'
            Assert-Condition ([int]$serveJson.report.applied -eq 2) 'serve report.applied must stay 2: a large budget changes nothing about the apply'
            Assert-Condition (-not $serveStderr.Contains('budget: ')) 'json mode must not print the human budget line on stderr'
            Assert-Condition ($storeExists) 'the receiver store was not written'
            Assert-Condition ($recordCount -eq 2) ('receiver record count: expected 2 but was ' + $recordCount)
            foreach ($expectedName in $script:expectedSources) {
                Assert-Condition ($displayNames -contains $expectedName) ('receiver is missing source ' + $expectedName)
            }
            Assert-Condition ($baselineExists) 'the receiver baseline was not updated after a successful apply'
            $detail = 'send=' + $sendExit + ' serve=' + $serveExit + ' budgetMs=' + $serveJson.budgetMs + ' records=' + $recordCount + ' in ' + $actual.durationMs + ' ms'
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $detail; Passed = $true }
        } catch {
            $actual.sendStderr = $sendStderr
            $actual.serveStderr = $serveStderr
            return [ordered]@{ Name = $name; Title = $title; Expected = $expected; Actual = $actual; Detail = $_.Exception.Message; Passed = $false }
        }
    } finally {
        if ($null -ne $send -and -not $send.HasExited) { try { $send.Kill() } catch { } }
        if ($null -ne $serve -and -not $serve.HasExited) { try { $serve.Kill() } catch { } }
        if (-not $KeepArtifacts) { try { Remove-Item -LiteralPath $rootDir -Recurse -Force -ErrorAction SilentlyContinue } catch { } }
    }
}

function Write-CaseResult {
    param($Result)
    $script:results.Add($Result)
    if ($Result.Passed) {
        Write-Host ('PASS ' + $Result.Name + ' :: ' + $Result.Detail)
    } else {
        Write-Host ('FAIL ' + $Result.Name + ' :: ' + $Result.Detail)
        if ($Result.Actual.Contains('sendStderr') -and $Result.Actual.sendStderr) {
            Write-Host ('  send stderr: ' + ($Result.Actual.sendStderr.Trim() -replace '\r?\n', ' | '))
        }
        if ($Result.Actual.Contains('serveStderr') -and $Result.Actual.serveStderr) {
            Write-Host ('  serve stderr: ' + ($Result.Actual.serveStderr.Trim() -replace '\r?\n', ' | '))
        }
    }
}
function Write-CaseTable {
    param($Rows)
    Write-Host ''
    Write-Host ('{0,-34} {1,-44} {2,-56} {3}' -f 'case', 'expected', 'actual', 'verdict')
    Write-Host (('-' * 34) + ' ' + ('-' * 44) + ' ' + ('-' * 56) + ' ' + ('-' * 7))
    foreach ($row in $Rows) {
        $verdict = 'FAIL'
        if ($row.Passed) { $verdict = 'PASS' }
        Write-Host ('{0,-34} {1,-44} {2,-56} {3}' -f $row.Name, $row.Expected, $row.Detail, $verdict)
    }
}

# ---------------------------------------------------------------------------------------------
Write-Host ('MediaHub W6-28 transfer acceptance (TLS 1.3 + shared-secret tag) :: ' + $script:startedAt.ToString('yyyy-MM-dd HH:mm:ss'))
Write-Host ('repo   : ' + $script:repoRoot)
Write-Host ('outDir : ' + $script:outDir)
Write-Host ''

if (Test-Path -LiteralPath $script:outDir) {
    # A rerun must not be able to read a previous run's receiver store and call it a pass.
    Get-ChildItem -LiteralPath $script:outDir -Directory -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
} else {
    New-Item -ItemType Directory -Path $script:outDir -Force | Out-Null
}

if (-not $SkipBuild) {
    Write-Host 'building MediaHub.SyncCli (once; the cases then run the produced dll)...'
    $buildOutput = & dotnet build $script:cliProject -c Debug -v:m 2>&1
    $buildExit = $LASTEXITCODE
    Write-Utf8 -Path (Join-Path $script:outDir 'build.log') -Text (($buildOutput | ForEach-Object { [string]$_ }) -join $script:nl)
    foreach ($line in $buildOutput) {
        $text = [string]$line
        if ($text -match '个警告|个错误|warning |error ') { Write-Host ('  ' + $text.Trim()) }
    }
    if ($buildExit -ne 0) {
        Write-Host ('RESULT: 0 passed, 1 failed (dotnet build exited ' + $buildExit + ')')
        exit 1
    }
    Write-Host '  build ok'
}
if (-not (Test-Path -LiteralPath $script:cliDll)) {
    Write-Host ('RESULT: 0 passed, 1 failed (missing ' + $script:cliDll + '; drop -SkipBuild)')
    exit 1
}
Write-Host ('cli    : ' + $script:cliDll)
Write-Host ''

$specs = @(
    [ordered]@{
        Name = 'A-plaintext-no-auth'; Title = 'A plaintext + no auth (W6-16 baseline)'
        TlsListener = $false; ClientTls = $false; PinOverride = ''
        ServeExtra = @(); SendExtra = @(); ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 1
            Table = 'send 0 / serve 0 / 1 envelope / 1 frame' }
    },
    [ordered]@{
        Name = 'B-plaintext-tag'; Title = 'B plaintext + shared-secret tag'
        TlsListener = $false; ClientTls = $false; PinOverride = ''
        ServeExtra = @('--authenticate', '--secret-env', $script:envA)
        SendExtra = @('--authenticate', '--secret-env', $script:envA)
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envA; ServeSecret = $script:secretA; SendSecret = $script:secretA
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 3
            Table = 'send 0 / serve 0 / authenticate / 3 frames' }
    },
    [ordered]@{
        Name = 'C-tls-no-auth'; Title = 'C TLS 1.3 + no auth'
        TlsListener = $true; ClientTls = $true; PinOverride = ''
        ServeExtra = @('--tls'); SendExtra = @()
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 1
            Table = 'send 0 / serve 0 / pin echoed / 1 envelope' }
    },
    [ordered]@{
        Name = 'D-tls-tag'; Title = 'D TLS 1.3 + shared-secret tag'
        TlsListener = $true; ClientTls = $true; PinOverride = ''
        ServeExtra = @('--tls', '--authenticate', '--secret-env', $script:envA)
        SendExtra = @('--authenticate', '--secret-env', $script:envA)
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envA; ServeSecret = $script:secretA; SendSecret = $script:secretA
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 3
            Table = 'send 0 / serve 0 / authenticate + pin / 3 frames' }
    },
    [ordered]@{
        Name = 'E-wrong-pin'; Title = 'E wrong pin (64 zeros)'
        TlsListener = $true; ClientTls = $true; PinOverride = ('0' * 64)
        ServeExtra = @('--tls'); SendExtra = @()
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # Measured: the client aborts the handshake, so the listener never completes one and
        # reports io-failed. What matters is the client's tls-fingerprint-mismatch and the fact
        # that nothing was written.
        Expect = [ordered]@{ SendExit = 2; ServeExit = 2; SendCode = 'tls-fingerprint-mismatch'; ServeCode = 'io-failed'; Records = 0; Frames = -1
            Table = 'send 2 tls-fingerprint-mismatch / 0 writes' }
    },
    [ordered]@{
        Name = 'F-plaintext-client-tls-listener'; Title = 'F plaintext client -> TLS listener'
        TlsListener = $true; ClientTls = $false; PinOverride = ''
        ServeExtra = @('--tls'); SendExtra = @()
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # Measured: 'transfer send' only writes, so it cannot observe the listener rejecting it and
        # exits 0 with no code. The listener is the side that names the failure.
        Expect = [ordered]@{ SendExit = 0; ServeExit = 2; SendCode = ''; ServeCode = 'tls-handshake-failed'; Records = 0; Frames = 1
            Table = 'serve 2 tls-handshake-failed / 0 writes' }
    },
    [ordered]@{
        Name = 'G-tls-client-plaintext-listener'; Title = 'G TLS client -> plaintext listener'
        TlsListener = $false; ClientTls = $true; PinOverride = ('a' * 64)
        ServeExtra = @(); SendExtra = @()
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 2; ServeExit = 2; SendCode = 'tls-handshake-failed'; ServeCode = 'tls-not-enabled'; Records = 0; Frames = -1
            Table = 'send 2 tls-handshake-failed / serve 2 tls-not-enabled' }
    },
    [ordered]@{
        Name = 'H-tag-mismatch'; Title = 'H TLS + two different secrets'
        TlsListener = $true; ClientTls = $true; PinOverride = ''
        ServeExtra = @('--tls', '--authenticate', '--secret-env', $script:envA)
        SendExtra = @('--authenticate', '--secret-env', $script:envB)
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envB; ServeSecret = $script:secretA; SendSecret = $script:secretB
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        Expect = [ordered]@{ SendExit = 0; ServeExit = 2; SendCode = ''; ServeCode = 'auth-mismatch'; Records = 0; Frames = -1
            Table = 'serve 2 auth-mismatch / 0 writes' }
    },
    [ordered]@{
        Name = 'A2-plaintext-confirm-delivery'; Title = 'A2 plaintext + --confirm-delivery'
        TlsListener = $false; ClientTls = $false; PinOverride = ''
        ConfirmDelivery = $true; ExpectedDeliveryConfirmed = $true
        ServeExtra = @(); SendExtra = @('--confirm-delivery')
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # The receiver closes as soon as it has the envelope, so the sender can see the receipt.
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 1
            Table = 'send 0 + deliveryConfirmed / 1 envelope' }
    },
    [ordered]@{
        Name = 'F2-plaintext-confirm-vs-tls'; Title = 'F2 plaintext + --confirm-delivery -> TLS listener (blind spot)'
        TlsListener = $true; ClientTls = $false; PinOverride = ''
        ConfirmDelivery = $true; ExpectedDeliveryConfirmed = $true
        ServeExtra = @('--tls'); SendExtra = @('--confirm-delivery')
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # MEASURED, and deliberately pinned: the listener rejects the first record and then CLOSES the
        # socket, and a close is the only receipt a one-way frame protocol can observe, so the sender
        # reports deliveryConfirmed=true even though nothing was imported (serve says
        # tls-handshake-failed and wrote nothing). This case exists to keep that blind spot visible in
        # the table: closing is necessary evidence, not sufficient. Only a receiver that closes without
        # accepting is indistinguishable from one that accepted - a real acknowledgement would need a
        # new frame type and an Android change, which W6-30 deliberately does not do.
        Expect = [ordered]@{ SendExit = 0; ServeExit = 2; SendCode = ''; ServeCode = 'tls-handshake-failed'; Records = 0; Frames = 1
            Table = 'send 0 deliveryConfirmed (blind spot) / serve 2 / 0 writes' }
    },
    [ordered]@{
        Name = 'J-receipt-written'; Title = 'J --await-receipt against a listener that writes the receipt'
        TlsListener = $false; ClientTls = $false; PinOverride = ''; ConfirmDelivery = $false
        SendReceipt = $true; AwaitReceipt = $true
        ServeExtra = @('--send-receipt'); SendExtra = @('--await-receipt')
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # W6-37: the receipt is the first evidence a one-way sender can get that the receiver
        # DECODED the envelope. The byte travels back on the same connection, so the sender's own
        # output is still the W6-16 stream (1 frame here).
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 1
            Table = 'send 0 receipt confirmed / serve receiptWritten / 1 envelope' }
    },
    [ordered]@{
        Name = 'K-receipt-missing'; Title = 'K --await-receipt against a listener that never writes one'
        TlsListener = $false; ClientTls = $false; PinOverride = ''; ConfirmDelivery = $false
        SendReceipt = $false; AwaitReceipt = $true
        ServeExtra = @(); SendExtra = @('--await-receipt')
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # The listener accepted and applied (serve 0, two records) but closed without a 0x04, so the
        # sender finally distinguishes "understood" from "merely closed": send 2 receipt-missing.
        Expect = [ordered]@{ SendExit = 2; ServeExit = 0; SendCode = 'receipt-missing'; ServeCode = ''; Records = 2; Frames = -1
            Table = 'send 2 receipt-missing / serve 0 / 2 records' }
    },

    # --- W6-45: two switches at once -------------------------------------------------------------------
    [ordered]@{
        Name = 'M-tls-tag-receipt'; Title = 'M TLS 1.3 + shared-secret tag + receipt, all three on'
        TlsListener = $true; ClientTls = $true; PinOverride = ''
        ConfirmDelivery = $false; SendReceipt = $true; AwaitReceipt = $true
        ServeExtra = @('--tls', '--authenticate', '--secret-env', $script:envA, '--send-receipt')
        SendExtra = @('--authenticate', '--secret-env', $script:envA, '--await-receipt')
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envA; ServeSecret = $script:secretA; SendSecret = $script:secretA
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # The receipt travels back inside the very TLS stream the tag protected, so neither switch may
        # displace the other: 3 frames (challenge + chunk + tag) and a confirmed receipt on both sides.
        Expect = [ordered]@{ SendExit = 0; ServeExit = 0; SendCode = ''; ServeCode = ''; Records = 2; Frames = 3
            Table = 'send 0 receiptConfirmed / serve 0 receiptWritten / 2 records' }
    },
    [ordered]@{
        Name = 'N-tls-tag-receipt-wrong-secret'; Title = 'N TLS 1.3 + tag + receipt, the two secrets differ'
        TlsListener = $true; ClientTls = $true; PinOverride = ''
        ConfirmDelivery = $false; SendReceipt = $true; AwaitReceipt = $true
        ServeExtra = @('--tls', '--authenticate', '--secret-env', $script:envA, '--send-receipt')
        SendExtra = @('--authenticate', '--secret-env', $script:envB, '--await-receipt')
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envB; ServeSecret = $script:secretA; SendSecret = $script:secretB
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # MEASURED, and it corrects the W6-45 task book: the send side CANNOT report auth-mismatch. The
        # envelope is one-way, so a sender never watches the peer verify its tag - all it can observe is
        # that the receipt byte never arrived, which is receipt-missing (exit 2). The peer names the real
        # reason (auth-mismatch) and writes nothing at all. Both halves are asserted so the pairing
        # "the sender reports the symptom, the listener names the cause" stays visible.
        ForbidServeCodePrefixes = @('receipt-')
        Expect = [ordered]@{ SendExit = 2; ServeExit = 2; SendCode = 'receipt-missing'; ServeCode = 'auth-mismatch'; Records = 0; Frames = -1
            Table = 'serve 2 auth-mismatch / send 2 receipt-missing / 0 writes' }
    },
    [ordered]@{
        Name = 'O-wrong-pin-with-tag'; Title = 'O wrong pin while the tag is switched on'
        TlsListener = $true; ClientTls = $true; PinOverride = ('0' * 64)
        ConfirmDelivery = $false
        ServeExtra = @('--tls', '--authenticate', '--secret-env', $script:envA)
        SendExtra = @('--authenticate', '--secret-env', $script:envA)
        ServeSecretEnv = $script:envA; SendSecretEnv = $script:envA; ServeSecret = $script:secretA; SendSecret = $script:secretA
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # The pin is checked during the handshake, before a single frame exists, so the tag can never be
        # the reason: the client says tls-fingerprint-mismatch and the listener sees the aborted
        # handshake (io-failed, exactly as case E measures it without the tag). No auth-* and no
        # receipt-* code may appear on either side.
        ForbidSendCodePrefixes = @('auth-', 'receipt-')
        ForbidServeCodePrefixes = @('auth-', 'receipt-')
        Expect = [ordered]@{ SendExit = 2; ServeExit = 2; SendCode = 'tls-fingerprint-mismatch'; ServeCode = 'io-failed'; Records = 0; Frames = -1
            Table = 'send 2 tls-fingerprint-mismatch / serve 2 io-failed / 0 writes' }
    },
    [ordered]@{
        Name = 'P-plaintext-listener-tls-sender-receipt'; Title = 'P plaintext listener + TLS sender that awaits a receipt'
        TlsListener = $false; ClientTls = $true; PinOverride = ('b' * 64)
        ConfirmDelivery = $false; AwaitReceipt = $true
        ServeExtra = @(); SendExtra = @('--await-receipt')
        ServeSecretEnv = ''; SendSecretEnv = ''; ServeSecret = ''; SendSecret = ''
        ServeDevice = 'rx-device'; SendDevice = 'tx-device'
        # The listener answers the TLS record with tls-not-enabled and closes, so the client's handshake
        # never completes. Asking for a receipt must not relabel that: the receipt wait is never reached
        # and the failure stays tls-handshake-failed instead of degrading into receipt-missing.
        ForbidSendCodePrefixes = @('receipt-')
        Expect = [ordered]@{ SendExit = 2; ServeExit = 2; SendCode = 'tls-handshake-failed'; ServeCode = 'tls-not-enabled'; Records = 0; Frames = -1
            Table = 'send 2 tls-handshake-failed (not receipt-missing) / serve 2 tls-not-enabled' }
    }
)

foreach ($spec in $specs) {
    Write-Host ('-- ' + $spec.Title)
    try {
        $result = Invoke-TransferCase -Spec $spec
    } catch {
        $result = New-FailedCase -Name $spec.Name -Title $spec.Title -Expected $spec.Expect.Table -Detail ('the case aborted: ' + $_.Exception.Message)
    }
    Write-CaseResult -Result $result
}

Write-Host ('-- ' + 'I --confirm-delivery against a peer that accepts and never closes')
$silentResult = $null
try {
    $silentResult = Invoke-SilentPeerCase
} catch {
    $silentResult = New-FailedCase -Name 'I-silent-peer' -Title 'I --confirm-delivery against a peer that accepts and never closes' -Expected 'send 2 delivery-unconfirmed / no write' -Detail ('the case aborted: ' + $_.Exception.Message)
}

Write-CaseResult -Result $silentResult

Write-Host ('-- ' + 'L --await-receipt against a peer that never sends a receipt')
$receiptTimeoutResult = $null
try {
    $receiptTimeoutResult = Invoke-ReceiptTimeoutCase
} catch {
    $receiptTimeoutResult = New-FailedCase -Name 'L-receipt-timeout' -Title 'L --await-receipt against a peer that never sends a receipt' -Expected 'send 2 receipt-timeout / no write' -Detail ('the case aborted: ' + $_.Exception.Message)
}

Write-CaseResult -Result $receiptTimeoutResult

Write-Host ('-- ' + 'S1 --budget-ms 1200 with no peer at all')
$budgetTimeoutResult = $null
try {
    $budgetTimeoutResult = Invoke-BudgetTimeoutCase
} catch {
    $budgetTimeoutResult = New-FailedCase -Name 'S1-budget-timeout' -Title 'S1 --budget-ms 1200 with no peer at all' -Expected 'serve 2 transfer-timeout after ~1.2 s / no write' -Detail ('the case aborted: ' + $_.Exception.Message)
}

Write-CaseResult -Result $budgetTimeoutResult

Write-Host ('-- ' + 'S2 --budget-ms 30000 against a real sender')
$budgetJsonResult = $null
try {
    $budgetJsonResult = Invoke-BudgetJsonCase
} catch {
    $budgetJsonResult = New-FailedCase -Name 'S2-budget-in-json' -Title 'S2 --budget-ms 30000 against a real sender' -Expected 'send 0 / serve 0, two records, budgetMs=30000' -Detail ('the case aborted: ' + $_.Exception.Message)
}

Write-CaseResult -Result $budgetJsonResult

# ---------------------------------------------------------------------------------------------
# Lingering process check: by name+command line, and by the pids this script started.
Start-Sleep -Milliseconds 300
$lingering = @()
foreach ($process in (Get-CimInstance Win32_Process -Filter "Name = 'dotnet.exe'" -ErrorAction SilentlyContinue)) {
    if ($process.CommandLine -and $process.CommandLine.Contains('MediaHub.SyncCli.dll')) {
        $lingering += [ordered]@{ pid = [int]$process.ProcessId; reason = 'dotnet.exe running MediaHub.SyncCli.dll'; commandLine = [string]$process.CommandLine }
    }
}
foreach ($spawnedPid in $script:spawned) {
    $alive = Get-Process -Id $spawnedPid -ErrorAction SilentlyContinue
    if ($null -ne $alive) {
        $lingering += [ordered]@{ pid = $spawnedPid; reason = 'started by this script and still alive'; commandLine = '' }
    }
}
$lingeringPassed = ($lingering.Count -eq 0)
if ($lingeringPassed) {
    Write-Host 'PASS no-residual-syncli-process :: no dotnet.exe is running MediaHub.SyncCli.dll'
} else {
    Write-Host ('FAIL no-residual-syncli-process :: ' + ($lingering | ConvertTo-Json -Compress))
}

if (-not $KeepArtifacts) {
    try { Remove-Item -LiteralPath $script:rootsDir -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}
$rootsRemain = Test-Path -LiteralPath $script:rootsDir

$passedCases = @($script:results | Where-Object { $_.Passed }).Count
$failedCases = @($script:results | Where-Object { -not $_.Passed }).Count
Write-CaseTable -Rows $script:results
$totalMs = [int]((Get-Date) - $script:startedAt).TotalMilliseconds
Write-Host ''
Write-Host ('cases: ' + ($passedCases + $failedCases) + ', passed: ' + $passedCases + ', failed: ' + $failedCases + ', total: ' + $totalMs + ' ms')
Write-Host ('residual SynCli processes: ' + $lingering.Count)
Write-Host ('case roots kept: ' + $rootsRemain + ' (' + $script:rootsDir + ')')

$failedTotal = $failedCases
if (-not $lingeringPassed) { $failedTotal = $failedCases + 1 }
$summary = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    repo = $script:repoRoot
    cliDll = $script:cliDll
    outDir = $script:outDir
    totalMs = $totalMs
    caseCount = $script:results.Count
    passed = $passedCases
    failed = $failedTotal
    residualProcesses = $lingering
    caseRootsKept = $rootsRemain
    caseRootsPath = $script:rootsDir
    cases = @($script:results | ForEach-Object {
        [ordered]@{
            name = $_.Name
            title = $_.Title
            expected = $_.Expected
            passed = $_.Passed
            detail = $_.Detail
            sendExit = $_.Actual.sendExit
            serveExit = $_.Actual.serveExit
            sendCode = $_.Actual.sendCode
            serveCode = $_.Actual.serveCode
            frames = $_.Actual.frames
            receiverRecords = $_.Actual.receiverRecords
            envelopeCount = $_.Actual.envelopeCount
            receiptConfirmed = $_.Actual.receiptConfirmed
            receiptWritten = $_.Actual.receiptWritten
            receiverStoreExists = $_.Actual.receiverStoreExists
            receiverBaseline = $_.Actual.receiverBaseline
            receiverDisplayNames = $_.Actual.receiverDisplayNames
            durationMs = $_.Actual.durationMs
            logDir = (Join-Path $script:outDir $_.Name)
            serveStdout = (Join-Path $script:outDir ($_.Name + '/serve.stdout.txt'))
            serveStderr = (Join-Path $script:outDir ($_.Name + '/serve.stderr.txt'))
            sendStdout = (Join-Path $script:outDir ($_.Name + '/send.stdout.txt'))
            sendStderr = (Join-Path $script:outDir ($_.Name + '/send.stderr.txt'))
        }
    })
}
Write-Utf8 -Path $script:summaryPath -Text (ConvertTo-Json -InputObject $summary -Depth 8)
Write-Host ('summary: ' + $script:summaryPath)

$exit = 1
if ($failedTotal -eq 0) { $exit = 0 }
Write-Host ('RESULT: ' + $passedCases + ' passed, ' + $failedTotal + ' failed')
exit $exit

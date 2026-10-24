<#
.SYNOPSIS
    MediaHub W6-28 acceptance: the transfer layer over real, separate processes
    (W6-16 plaintext, W6-24 shared-secret tags, W6-26 TLS 1.3 with a pinned fingerprint).

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
    }
)

foreach ($spec in $specs) {
    Write-Host ('-- ' + $spec.Title)
    try {
        $result = Invoke-TransferCase -Spec $spec
    } catch {
        $result = New-FailedCase -Name $spec.Name -Title $spec.Title -Expected $spec.Expect.Table -Detail ('the case aborted: ' + $_.Exception.Message)
    }
    $script:results.Add($result)
    if ($result.Passed) {
        Write-Host ('PASS ' + $result.Name + ' :: ' + $result.Detail)
    } else {
        Write-Host ('FAIL ' + $result.Name + ' :: ' + $result.Detail)
        if ($result.Actual.Contains('sendStderr') -and $result.Actual.sendStderr) {
            Write-Host ('  send stderr: ' + ($result.Actual.sendStderr.Trim() -replace '\r?\n', ' | '))
        }
        if ($result.Actual.Contains('serveStderr') -and $result.Actual.serveStderr) {
            Write-Host ('  serve stderr: ' + ($result.Actual.serveStderr.Trim() -replace '\r?\n', ' | '))
        }
    }
}

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

param(
    [string]$Label = 'vm-jvm',
    [string[]]$Tests = @('com.mediahub.feature.server.ServerEditorViewModelTest'),
    [int]$TimeoutSeconds = 180,
    [switch]$CompileOnly,
    [string]$SdkSource = 'C:/Users/55160/.m2/repository/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar'
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$cacheRoot = 'C:/Users/55160/.gradle/caches/modules-2/files-2.1'
$priorRoot = 'D:/deepseek_test/MediaHub'
$java = 'C:/Program Files/Java/jdk-21/bin/java.exe'
$dependencyModel = "$priorRoot/feature/server/build/intermediates/unit_test_lint_model/debug/generateDebugUnitTestLintModel/debug-artifact-libraries.xml"
function Jar([string]$Coordinate) {
    $found = @(Get-ChildItem -LiteralPath "$cacheRoot/$Coordinate" -Filter '*.jar' -Recurse)
    if ($found.Count -ne 1) { throw "Expected one jar: $Coordinate, got $($found.Count)" }
    $found[0].FullName
}
function JavaArg([string]$Value) {
    '"' + $Value.Replace('\', '/').Replace('"', '\"') + '"'
}
function RunJava([string]$Phase, [string[]]$Arguments) {
    $argFile = "$PSScriptRoot/$Label-$Phase.args"
    $stdout = "$PSScriptRoot/$Label-$Phase.log"
    $stderr = "$PSScriptRoot/$Label-$Phase.stderr.log"
    [System.IO.File]::WriteAllLines($argFile, @($Arguments | ForEach-Object { JavaArg $_ }), [System.Text.UTF8Encoding]::new($false))
    $process = Start-Process -FilePath $java -ArgumentList ('@' + $argFile) -WorkingDirectory $workspace -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try {
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            $process.WaitForExit()
            Add-Content -LiteralPath $stderr -Value "Independent watchdog terminated $Phase after $TimeoutSeconds seconds."
            return 124
        }
        $process.ExitCode
    } finally {
        if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        $process.Dispose()
    }
}
$compiler = @(
    (Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.2.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'),
    (Jar 'org.jetbrains/annotations/13.0')
) -join ';'
[xml]$model = Get-Content -LiteralPath $dependencyModel -Raw
$dependencies = @($model.libraries.library | ForEach-Object { $_.jars -split ';' } | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
# Recompile the changed service and actual ViewModel below. Only unchanged project dependencies reuse prior outputs.
$priorModules = @('core/common', 'core/database', 'core/security')
$priorOutputs = @($priorModules | ForEach-Object { "$priorRoot/$_/build/tmp/kotlin-classes/debug" })
foreach ($output in $priorOutputs) { if (!(Test-Path -LiteralPath $output)) { throw "Missing prior module classes: $output" } }
$dependencies += $priorOutputs
$dependencies += @(
    (Jar 'com.squareup.okhttp3/mockwebserver/4.12.0'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm/1.9.0'),
    'C:/Users/55160/.gradle/caches/8.14/transforms/15a448721c84f6b3c68a9b8d677faa65/transformed/android.jar'
)
$dependencies = @($dependencies | Select-Object -Unique)
$sources = @(
    'core/network/src/main/kotlin/com/mediahub/core/network/EndpointTestService.kt',
    'core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt',
    'core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt',
    'core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/Logger.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/LogTag.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt',
    'feature/server/src/main/kotlin/com/mediahub/feature/server/ServerEditorViewModel.kt',
    'feature/server/src/main/kotlin/com/mediahub/feature/server/AddressTestResultPolicy.kt',
    'feature/server/src/main/kotlin/com/mediahub/feature/server/ServerIconStore.kt',
    'feature/server/src/main/kotlin/com/mediahub/feature/server/RemoveServerUseCase.kt',
    'feature/server/src/test/kotlin/com/mediahub/feature/server/ServerEditorViewModelTest.kt'
) | ForEach-Object { Join-Path $workspace $_ }
$destination = Join-Path $PSScriptRoot "$Label-classes"
if (Test-Path -LiteralPath $destination) { throw "Use a new label; classes directory already exists: $destination" }
New-Item -ItemType Directory -Path $destination | Out-Null
$roboCache = Join-Path $env:TEMP 'mh-t0001-robolectric-sdk'
New-Item -ItemType Directory -Path $roboCache -Force | Out-Null
$sdkName = 'android-all-instrumented-14-robolectric-10818077-i7.jar'
$sdkTarget = Join-Path $roboCache $sdkName
$manifest = [ordered]@{
    label = $Label
    head = (git -C $workspace rev-parse HEAD)
    sources = @($sources | ForEach-Object { @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
    dependencyModel = @{ path = $dependencyModel; sha256 = (Get-FileHash -LiteralPath $dependencyModel).Hash }
    compilerClasspath = $compiler
    runtimeDependencies = $dependencies
    priorProjectOutputs = $priorOutputs
    sdk = @{ source = $SdkSource; target = $sdkTarget; prepared = $false }
    tests = $Tests
    timeoutSecondsPerProcess = $TimeoutSeconds
    note = 'Direct Kotlin + JUnit + offline Robolectric fallback. Current EndpointTestService, HttpClientFactory, logging, ServerEditorViewModel and test sources are compiled; unchanged common/database/security/model/provider dependencies reuse prior outputs. Not Android Gradle module tests, lint, assemble or full-source proof.'
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-manifest.json"
$compileArguments = @('-cp', $compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ($dependencies -join ';'), '-d', $destination) + $sources
$compileExit = RunJava 'compile' $compileArguments
$testExit = $null
$runtimeBlocked = $null
if ($compileExit -eq 0 -and !$CompileOnly) {
    try {
        # Verify actual readable bytes, not merely that metadata for a path exists.
        $sdkStream = [System.IO.File]::OpenRead($SdkSource)
        try { if ($sdkStream.ReadByte() -ne 80) { throw 'SDK source is not a ZIP/JAR file' } } finally { $sdkStream.Dispose() }
        if (!(Test-Path -LiteralPath $sdkTarget)) { Copy-Item -LiteralPath $SdkSource -Destination $sdkTarget }
        if ((Get-FileHash -LiteralPath $SdkSource).Hash -ne (Get-FileHash -LiteralPath $sdkTarget).Hash) { throw 'Robolectric SDK copy hash mismatch' }
        $manifest.sdk = @{ source = $SdkSource; target = $sdkTarget; prepared = $true; sha256 = (Get-FileHash -LiteralPath $sdkTarget).Hash }
        $runtimeArguments = @('-Drobolectric.offline=true', "-Drobolectric.dependency.dir=$roboCache", '-cp', ($destination + ';' + ($dependencies -join ';')), 'org.junit.runner.JUnitCore') + $Tests
        $testExit = RunJava 'junit' $runtimeArguments
    } catch {
        $runtimeBlocked = $_.Exception.Message
        $runtimeBlocked | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-runtime-blocked.log"
    }
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-manifest.json"
@{ compileExit = $compileExit; testExit = $testExit; compileOnly = [bool]$CompileOnly; runtimeBlocked = $runtimeBlocked } | ConvertTo-Json | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-exit.json"
foreach ($phase in @('compile', 'junit')) {
    foreach ($suffix in @('log', 'stderr.log')) {
        $log = "$PSScriptRoot/$Label-$phase.$suffix"
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log }
    }
}
if ($compileExit -ne 0) { exit $compileExit }
if ($runtimeBlocked) { Write-Output $runtimeBlocked; exit 78 }
if ($CompileOnly) { exit 0 }
exit $testExit

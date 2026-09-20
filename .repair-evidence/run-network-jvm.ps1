param(
    [string]$Label = 'red',
    [string[]]$Tests = @('com.mediahub.core.network.EndpointTestServiceOwnershipTest'),
    [string]$EndpointSource = 'core/network/src/main/kotlin/com/mediahub/core/network/EndpointTestService.kt',
    [string[]]$Methods = @()
)
$ErrorActionPreference = 'Stop'
$cacheRoot = 'C:/Users/55160/.gradle/caches/modules-2/files-2.1'
function Jar([string]$Coordinate) {
    $found = @(Get-ChildItem -LiteralPath "$cacheRoot/$Coordinate" -Filter '*.jar' -Recurse)
    if ($found.Count -ne 1) { throw "Expected one jar: $Coordinate, got $($found.Count)" }
    $found[0].FullName
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
$dependencies = @(
    (Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm/1.9.0'),
    (Jar 'com.squareup.okhttp3/okhttp/4.12.0'),
    (Jar 'com.squareup.okhttp3/mockwebserver/4.12.0'),
    (Jar 'com.squareup.okio/okio-jvm/3.6.0'),
    (Jar 'junit/junit/4.13.2'),
    (Jar 'org.hamcrest/hamcrest-core/1.3'),
    (Jar 'org.jetbrains/annotations/13.0'),
    'C:/Users/55160/.gradle/caches/8.14/transforms/15a448721c84f6b3c68a9b8d677faa65/transformed/android.jar'
) -join ';'
$sources = @(
    $EndpointSource,
    'core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt',
    'core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt',
    'core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/Logger.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/LogTag.kt',
    'core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt'
)
$sources += (Get-ChildItem core/network/src/test/kotlin/com/mediahub/core/network/EndpointTestService*Test.kt).FullName
$sources += "$PSScriptRoot/MethodRunner.kt"
$destination = Join-Path $PSScriptRoot "$Label-classes"
New-Item -ItemType Directory -Force $destination | Out-Null
$manifest = [ordered]@{
    label = $Label
    head = (git rev-parse HEAD)
    sources = @($sources | ForEach-Object { @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
    compilerClasspath = $compiler
    runtimeClasspath = $dependencies
    tests = $Tests
    methods = $Methods
    note = 'Direct Kotlin 2.2.10 + JUnit fallback; does not replace Android Gradle module tests/lint/assemble.'
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-manifest.json"
& 'C:/Program Files/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $dependencies -d $destination @sources *> "$PSScriptRoot/$Label-compile.log"
$compileExit = $LASTEXITCODE
if ($compileExit -ne 0) { Get-Content "$PSScriptRoot/$Label-compile.log"; exit $compileExit }
if ($Methods.Count -gt 0) {
    & 'C:/Program Files/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp "$destination;$dependencies" MethodRunner @Methods *> "$PSScriptRoot/$Label-junit.log"
} else {
    & 'C:/Program Files/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp "$destination;$dependencies" org.junit.runner.JUnitCore @Tests *> "$PSScriptRoot/$Label-junit.log"
}
$testExit = $LASTEXITCODE
@{ compileExit = $compileExit; testExit = $testExit } | ConvertTo-Json | Set-Content -Encoding utf8 "$PSScriptRoot/$Label-exit.json"
Get-Content "$PSScriptRoot/$Label-junit.log"
exit $testExit

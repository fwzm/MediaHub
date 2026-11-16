#Requires -Version 7.0
<#
.SYNOPSIS
    Drives the real update client against a local HTTP static directory, from manifest check to restart.

.DESCRIPTION
    This is the network half of the update acceptance: the previous drill proves the agent can swap a
    local zip; this one proves the client can find, fetch and verify the same update over HTTP and then
    hand it to the agent.

    What actually runs:
      * the product's own MediaHub.Update library (UpdateSource / UpdateClient / UpdateManifest), built
        from source at drill time into the work directory - no re-implementation of the wire format;
      * a loopback-only HTTP/1.1 static server written with TcpListener (no URL ACL, no admin, no
        third-party server). Every request it answers is logged with method, path, status and bytes;
      * the real installer/update.ps1 agent, on an isolated per-user tree;
      * a tiny windowless probe executable that stands in for MediaHub.exe so the -Restart path can be
        observed without opening a MediaHub window. The probe carries a real version resource (1.0.0 or
        1.1.0) so the agent's version checks are genuine; the real binary swap is covered by
        verify-update-chain.ps1 against the published kits.

    Runs:
      1. upgrade    - manifest -> download -> hash/size -> agent swap -> restart probe reports 1.1.0.
      2. truncated  - the server advertises the full length but closes the socket half way; the client
                      must fail, delete the .part file and leave the installation untouched.
      3. bad version- the manifest carries a version the product rejects; no download, no swap.

    Quiet gate (hard rule): the drill refuses to start unless the signed-in user has been idle for
    -MinIdleSeconds (30 minutes by default), and a watchdog aborts the remaining steps the moment input
    comes back. Nothing is put on screen, no window is activated, no input is injected.

.PARAMETER MinIdleSeconds
    Idle time required before the drill starts. Default 1800 (30 minutes).

.PARAMETER IdleWaitSeconds
    How long to wait for that idle window. 0 (default) means "check once and refuse".

.PARAMETER WorkDir
    Throw-away root. Default: windows/artifacts/update-drill/http-<timestamp>.

.EXAMPLE
    pwsh -NoProfile -File windows/tools/verify-update-http.ps1
    pwsh -NoProfile -File windows/tools/verify-update-http.ps1 -IdleWaitSeconds 3600

.NOTES
    Exit codes: 0 = all three runs passed, 1 = a run failed, 2 = prerequisite missing,
    10 = idle gate refused, 11 = aborted because the user came back.
#>
[CmdletBinding()]
param(
    [int] $MinIdleSeconds = 1800,
    [int] $IdleWaitSeconds = 0,
    [string] $WorkDir,
    [string] $OldVersion = '1.0.0',
    [string] $NewVersion = '1.1.0',
    [switch] $KeepWorkDir
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$artifactRoot = Join-Path $repoRoot 'windows\artifacts\update-drill'
$agent = Join-Path $repoRoot 'windows\installer\update.ps1'
if (-not $WorkDir) { $WorkDir = Join-Path $artifactRoot ('http-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$WorkDir = [System.IO.Path]::GetFullPath($WorkDir)

if (-not (Test-Path -LiteralPath $agent)) {
    Write-Host ('http-drill: FAILED - update agent not found: ' + $agent) -ForegroundColor Red
    exit 2
}

# ------------------------------------------------------------------------------------------------
# quiet gate (hard rule: never run while the user is at the keyboard)
# ------------------------------------------------------------------------------------------------

$quietModule = Join-Path $repoRoot 'windows\tools\acceptance\lib\MhQuiet.psm1'
Import-Module $quietModule -Force

$idle = Get-MhUserIdleSeconds
if ($null -eq $idle) {
    Write-Host 'http-drill: idle time unavailable; refusing to run' -ForegroundColor Red
    exit 10
}
if ($idle -lt $MinIdleSeconds) {
    if ($IdleWaitSeconds -le 0) {
        Write-Host ('http-drill: REFUSED - user idle ' + $idle + 's, gate needs ' + $MinIdleSeconds + 's') -ForegroundColor Yellow
        Write-Host 'http-drill: re-run with -IdleWaitSeconds <n> to wait for the window, or leave the machine idle'
        exit 10
    }

    $deadline = (Get-Date).AddSeconds($IdleWaitSeconds)
    Write-Host ('http-drill: waiting for ' + $MinIdleSeconds + 's idle (now ' + $idle + 's, up to ' + $IdleWaitSeconds + 's)')
    while ($idle -lt $MinIdleSeconds) {
        if ((Get-Date) -ge $deadline) {
            Write-Host ('http-drill: REFUSED - still only ' + $idle + 's idle after waiting') -ForegroundColor Yellow
            exit 10
        }
        Start-Sleep -Seconds 10
        $idle = Get-MhUserIdleSeconds
        if ($null -eq $idle) { $idle = 0 }
    }
    Write-Host ('http-drill: idle gate open at ' + $idle + 's idle')
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# The watchdog only ever stops probe processes that live under this work directory. It must never
# touch a MediaHub the user actually has installed and running.
$abortFile = Join-Path $WorkDir 'aborted.txt'
$stopWatchFile = Join-Path $WorkDir 'watch.stop'
$watchdogSource = @'
using System;
using System.Runtime.InteropServices;
namespace MhHttpDrillWatch
{
    public static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
        [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);
        [DllImport("kernel32.dll")] public static extern uint GetTickCount();
    }
}
'@

$watchdog = Start-Job -ScriptBlock {
    param($TypeSource, $MinIdleSeconds, $AbortFile, $StopFile, $Root)
    if (-not ('MhHttpDrillWatch.Native' -as [type])) { Add-Type -TypeDefinition $TypeSource -Language CSharp }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    while (-not (Test-Path -LiteralPath $StopFile)) {
        $info = New-Object MhHttpDrillWatch.Native+LASTINPUTINFO
        $info.cbSize = [uint32][System.Runtime.InteropServices.Marshal]::SizeOf($info)
        if ([MhHttpDrillWatch.Native]::GetLastInputInfo([ref]$info)) {
            $tick = [uint64][MhHttpDrillWatch.Native]::GetTickCount()
            $last = [uint64]$info.dwTime
            $delta = if ($tick -ge $last) { $tick - $last } else { (0x100000000 - $last) + $tick }
            $idle = [int]($delta / 1000)
            if ($idle -lt $MinIdleSeconds) {
                foreach ($process in @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue)) {
                    $path = ''
                    try { $path = $process.Path } catch { }
                    if ($path -and ([System.IO.Path]::GetFullPath($path)).StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
                        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    }
                }
                Set-Content -LiteralPath $AbortFile -Value ('user-active idle=' + $idle + ' threshold=' + $MinIdleSeconds) -Encoding UTF8
                break
            }
        }
        Start-Sleep -Milliseconds 700
    }
} -ArgumentList @($watchdogSource, $MinIdleSeconds, $abortFile, $stopWatchFile, $WorkDir)

function Test-Aborted {
    if (Test-Path -LiteralPath $abortFile) {
        Write-Host ('http-drill: ABORTED - ' + (Get-Content -LiteralPath $abortFile -Raw).Trim()) -ForegroundColor Yellow
        return $true
    }
    return $false
}

# ------------------------------------------------------------------------------------------------
# helpers
# ------------------------------------------------------------------------------------------------

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Get-ExeVersion([string] $ExePath) {
    return ((Get-Item -LiteralPath $ExePath).VersionInfo.ProductVersion -split '\+')[0]
}

function New-Probe([string] $Version, [string] $Destination) {
    $projectDir = Join-Path $WorkDir ('probe-' + ($Version -replace '[^0-9]', ''))
    New-Item -ItemType Directory -Force -Path $projectDir | Out-Null
    $source = @'
using System;
using System.IO;

internal static class Program
{
    private static int Main()
    {
        string marker = Environment.GetEnvironmentVariable("MH_PROBE_MARKER") ?? "";
        if (marker.Length > 0)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(marker)!);
            File.WriteAllText(marker, DateTime.UtcNow.ToString("o") + " version=" + typeof(Program).Assembly.GetName().Version);
        }
        return 0;
    }
}
'@
    [System.IO.File]::WriteAllText((Join-Path $projectDir 'Program.cs'), $source)
    $project = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <AssemblyName>MediaHub</AssemblyName>
    <RootNamespace>MediaHubProbe</RootNamespace>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <Version>$Version</Version>
    <AssemblyVersion>$Version.0</AssemblyVersion>
    <FileVersion>$Version.0</FileVersion>
    <Platforms>x64</Platforms>
    <PlatformTarget>x64</PlatformTarget>
    <AllowMissingPrunePackageData>true</AllowMissingPrunePackageData>
    <GenerateDocumentationFile>false</GenerateDocumentationFile>
  </PropertyGroup>
</Project>
"@
    [System.IO.File]::WriteAllText((Join-Path $projectDir 'MediaHub.csproj'), $project)
    $build = & dotnet build (Join-Path $projectDir 'MediaHub.csproj') -c Release -p:Platform=x64 -o $Destination -v q 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('probe build failed: ' + (($build | Select-Object -Last 6) -join ' ')) }
}

function New-Kit([string] $ProbeDir, [string] $KitDir) {
    New-Item -ItemType Directory -Force -Path (Join-Path $KitDir 'app') | Out-Null
    foreach ($name in @('MediaHub.exe', 'MediaHub.dll', 'MediaHub.runtimeconfig.json', 'MediaHub.deps.json')) {
        $from = Join-Path $ProbeDir $name
        if (Test-Path -LiteralPath $from) { Copy-Item -LiteralPath $from -Destination (Join-Path $KitDir ('app\' + $name)) -Force }
    }
    Set-Content -LiteralPath (Join-Path $KitDir 'app\libmpv-2.dll') -Value 'probe placeholder' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $KitDir 'uninstall.ps1') -Value '# probe kit' -Encoding ASCII
}

# The client half is the product's own MediaHub.Update library, compiled at drill time so the drill
# cannot drift from the shipped wire format.
function New-ClientHarness {
    $dir = Join-Path $WorkDir 'client'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $source = @'
using System;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using MediaHub.Update;

internal static class Program
{
    private static void WriteJson(string path, object value)
    {
        if (path.Length == 0) return;
        File.WriteAllText(path, JsonSerializer.Serialize(value, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static async Task<int> Main(string[] args)
    {
        string sourceText = "", installed = "0.0.0", dataDir = "", jsonPath = "";
        for (int i = 0; i + 1 < args.Length; i++)
        {
            switch (args[i])
            {
                case "--source": sourceText = args[++i]; break;
                case "--installed": installed = args[++i]; break;
                case "--data-dir": dataDir = args[++i]; break;
                case "--json": jsonPath = args[++i]; break;
            }
        }
        if (sourceText.Length == 0 || dataDir.Length == 0)
        {
            Console.WriteLine("client: missing --source or --data-dir");
            return 5;
        }

        if (!UpdateSource.TryParse(sourceText, out UpdateSource source, out string sourceError))
        {
            Console.WriteLine("client: source rejected: " + sourceError);
            WriteJson(jsonPath, new { ok = false, stage = "source", error = sourceError });
            return 2;
        }
        Console.WriteLine("client: source kind=" + source.Kind + " root=" + source.Root);

        using var client = new UpdateClient();
        UpdateManifest manifest;
        try
        {
            manifest = await client.GetManifestAsync(source);
        }
        catch (Exception ex)
        {
            Console.WriteLine("client: manifest rejected: " + ex.Message);
            WriteJson(jsonPath, new { ok = false, stage = "manifest", error = ex.Message });
            return 3;
        }

        Console.WriteLine("client: manifest version=" + manifest.Version + " package=" + manifest.Package +
            " size=" + manifest.Size + " sha256=" + manifest.Sha256);

        bool available = UpdateVersions.IsNewer(manifest.Version, installed);
        Console.WriteLine("client: installed=" + UpdateVersions.Normalize(installed) + " updateAvailable=" + available);
        if (!available)
        {
            WriteJson(jsonPath, new { ok = true, available = false, version = manifest.Version });
            return 0;
        }

        try
        {
            string path = await client.DownloadPackageAsync(source, manifest, dataDir);
            long bytes = new FileInfo(path).Length;
            string hash = UpdateClient.ComputeSha256(path);
            bool sizeOk = manifest.Size <= 0 || bytes == manifest.Size;
            bool hashOk = string.Equals(hash, manifest.Sha256, StringComparison.OrdinalIgnoreCase);
            Console.WriteLine("client: downloaded path=" + path);
            Console.WriteLine("client: bytes=" + bytes + " expectedBytes=" + manifest.Size + " sizeMatch=" + sizeOk);
            Console.WriteLine("client: sha256=" + hash + " hashMatch=" + hashOk);
            WriteJson(jsonPath, new
            {
                ok = sizeOk && hashOk,
                available = true,
                version = manifest.Version,
                packagePath = path,
                bytes,
                expectedBytes = manifest.Size,
                sha256 = hash,
                expectedSha256 = manifest.Sha256,
                sizeMatch = sizeOk,
                hashMatch = hashOk,
            });
            return sizeOk && hashOk ? 0 : 4;
        }
        catch (Exception ex)
        {
            Console.WriteLine("client: download failed: " + ex.GetType().Name + ": " + ex.Message);
            WriteJson(jsonPath, new { ok = false, stage = "download", error = ex.Message });
            return 4;
        }
    }
}
'@
    [System.IO.File]::WriteAllText((Join-Path $dir 'Program.cs'), $source)
    $project = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <AssemblyName>UpdateDrillClient</AssemblyName>
    <RootNamespace>UpdateDrillClient</RootNamespace>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <Platforms>x64</Platforms>
    <PlatformTarget>x64</PlatformTarget>
    <AllowMissingPrunePackageData>true</AllowMissingPrunePackageData>
    <GenerateDocumentationFile>false</GenerateDocumentationFile>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="$(Join-Path $repoRoot 'windows\src\MediaHub.Update\MediaHub.Update.csproj')" />
  </ItemGroup>
</Project>
"@
    [System.IO.File]::WriteAllText((Join-Path $dir 'UpdateDrillClient.csproj'), $project)
    $out = Join-Path $dir 'out'
    $build = & dotnet build (Join-Path $dir 'UpdateDrillClient.csproj') -c Release -p:Platform=x64 -o $out -v q 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('client harness build failed: ' + (($build | Select-Object -Last 8) -join ' ')) }
    return Join-Path $out 'UpdateDrillClient.exe'
}

function Start-StaticServer([string] $Root, [string] $RequestLog, [string] $StopFile) {
    $portFile = Join-Path $WorkDir 'http.port'
    $job = Start-Job -ScriptBlock {
        param($Root, $PortFile, $RequestLog, $StopFile)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        Set-Content -LiteralPath $PortFile -Value $port -Encoding ASCII
        $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
        while (-not (Test-Path -LiteralPath $StopFile)) {
            if (-not $listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
            $client = $listener.AcceptTcpClient()
            try {
                $stream = $client.GetStream()
                $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 4096, $true)
                $requestLine = $reader.ReadLine()
                while (($line = $reader.ReadLine()) -and $line.Length -gt 0) { }
                $parts = @($requestLine -split ' ')
                $method = $parts[0]
                $rawPath = $parts[1]
                $relative = [uri]::UnescapeDataString(($rawPath -split '\?')[0]).TrimStart('/')
                # Requests under /truncated/ are answered honestly until 1 MiB, then the socket is closed
                # with the advertised Content-Length still outstanding.
                $truncate = $relative.StartsWith('truncated/', [System.StringComparison]::OrdinalIgnoreCase)
                $file = if ($relative.Length -gt 0) { [System.IO.Path]::GetFullPath((Join-Path $Root $relative)) } else { '' }
                $status = '200 OK'
                $length = 0
                if (-not $file -or -not $file.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase) -or
                    -not (Test-Path -LiteralPath $file -PathType Leaf)) {
                    $status = '404 Not Found'
                }
                else {
                    $length = (Get-Item -LiteralPath $file).Length
                }

                $head = "HTTP/1.1 $status`r`nContent-Length: $length`r`nContent-Type: application/octet-stream`r`nConnection: close`r`n`r`n"
                $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
                $stream.Write($headBytes, 0, $headBytes.Length)
                $sent = 0
                if ($status -eq '200 OK' -and $method -ne 'HEAD') {
                    $fs = [System.IO.File]::OpenRead($file)
                    try {
                        $buffer = New-Object byte[] 65536
                        while (($read = $fs.Read($buffer, 0, $buffer.Length)) -gt 0) {
                            $stream.Write($buffer, 0, $read)
                            $sent += $read
                            if ($truncate -and $sent -ge 1048576) { break }
                        }
                    }
                    finally { $fs.Dispose() }
                }
                $stream.Flush()
                $entry = [ordered]@{
                    at     = (Get-Date).ToString('o')
                    method = $method
                    path   = $rawPath
                    status = $status
                    bytes  = $sent
                    total  = $length
                    cut    = ($truncate -and $sent -lt $length)
                }
                Add-Content -LiteralPath $RequestLog -Value ($entry | ConvertTo-Json -Compress) -Encoding UTF8
            }
            catch { }
            finally { $client.Dispose() }
        }
        $listener.Stop()
    } -ArgumentList @($Root, $portFile, $RequestLog, $StopFile)

    $waited = 0
    while (-not (Test-Path -LiteralPath $portFile)) {
        Start-Sleep -Milliseconds 100
        $waited++
        if ($waited -gt 150) { throw 'local HTTP server did not start' }
    }
    return [pscustomobject]@{
        Job        = $job
        Port       = [int](Get-Content -LiteralPath $portFile -Raw).Trim()
        StopFile   = $StopFile
        RequestLog = $RequestLog
    }
}

function Stop-StaticServer($Server) {
    if ($null -eq $Server) { return }
    Set-Content -LiteralPath $Server.StopFile -Value 'stop' -Encoding ASCII -ErrorAction SilentlyContinue
    [void](Wait-Job -Job $Server.Job -Timeout 10)
    Remove-Job -Job $Server.Job -Force -ErrorAction SilentlyContinue
}

function Invoke-Agent([string] $PackageZip, [string] $Hash) {
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', $agent,
        '-InstallDir', $installDir,
        '-PackageZip', $PackageZip,
        '-ExpectedSha256', $Hash,
        '-NewVersion', $NewVersion,
        '-StateDir', $stateDir,
        '-StartMenuDir', $startMenu,
        '-DesktopDir', $desktop,
        '-TaskbarPinsDir', $taskbar,
        '-Restart'
    )
    $output = & powershell.exe @arguments 2>&1
    $code = $LASTEXITCODE
    foreach ($line in ($output | Select-Object -Last 10)) { Write-Host ('    ' + $line) }
    return [pscustomobject]@{ Code = $code; Output = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine) }
}

# ------------------------------------------------------------------------------------------------
# layout
# ------------------------------------------------------------------------------------------------

$installDir = Join-Path $WorkDir 'LocalAppData\Programs\MediaHub'
$stateDir = Join-Path $WorkDir 'LocalAppData\MediaHub\update'
$startMenu = Join-Path $WorkDir 'StartMenu'
$desktop = Join-Path $WorkDir 'Desktop'
$taskbar = Join-Path $WorkDir 'Taskbar'
$wwwRoot = Join-Path $WorkDir 'wwwroot'
$downloadDir = Join-Path $WorkDir 'downloads'
$probeMarker = Join-Path $WorkDir 'probe-ran.txt'
New-Item -ItemType Directory -Force -Path $startMenu, $desktop, $taskbar, $wwwRoot, $downloadDir | Out-Null
$env:MH_PROBE_MARKER = $probeMarker

function Install-OldTree([string] $OldKit) {
    if (Test-Path -LiteralPath $installDir) { Remove-Item -LiteralPath $installDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    Copy-Item -Path (Join-Path $OldKit 'app\*') -Destination $installDir -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $OldKit 'uninstall.ps1') -Destination $installDir -Force
    Copy-Item -LiteralPath $agent -Destination (Join-Path $installDir 'update.ps1') -Force
    $shell = New-Object -ComObject WScript.Shell
    $exe = Join-Path $installDir 'MediaHub.exe'
    foreach ($link in @((Join-Path $startMenu 'MediaHub.lnk'), (Join-Path $desktop 'MediaHub.lnk'), (Join-Path $taskbar 'MediaHub.lnk'))) {
        $shortcut = $shell.CreateShortcut($link)
        $shortcut.TargetPath = $exe
        $shortcut.WorkingDirectory = $installDir
        $shortcut.IconLocation = $exe + ',0'
        $shortcut.Save()
    }
}

function Assert-Shortcut([string] $Path) {
    Assert-True (Test-Path -LiteralPath $Path) ('shortcut missing: ' + $Path)
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($Path)
    Assert-True ($shortcut.TargetPath -ieq (Join-Path $installDir 'MediaHub.exe')) ('shortcut target is wrong: ' + $shortcut.TargetPath)
}

# ------------------------------------------------------------------------------------------------
# run
# ------------------------------------------------------------------------------------------------

$exitCode = 0
$server = $null
$clientExe = ''
try {
    Write-Host ('http-drill: workdir ' + $WorkDir)
    Write-Host ('http-drill: versions ' + $OldVersion + ' -> ' + $NewVersion)
    if (Test-Aborted) { throw 'aborted by user input' }

    $oldProbe = Join-Path $WorkDir 'probe-out-old'
    $newProbe = Join-Path $WorkDir 'probe-out-new'
    Write-Host 'http-drill: building windowless probe payloads'
    New-Probe -Version $OldVersion -Destination $oldProbe
    New-Probe -Version $NewVersion -Destination $newProbe
    $oldKit = Join-Path $WorkDir 'kit-old'
    $newKit = Join-Path $WorkDir 'kit-new'
    New-Kit -ProbeDir $oldProbe -KitDir $oldKit
    New-Kit -ProbeDir $newProbe -KitDir $newKit

    $packageName = 'MediaHub-Setup-win-x64-' + $NewVersion + '.zip'
    $goodDir = Join-Path $wwwRoot 'good'
    $cutDir = Join-Path $wwwRoot 'truncated'
    $badDir = Join-Path $wwwRoot 'badversion'
    New-Item -ItemType Directory -Force -Path $goodDir, $cutDir, $badDir | Out-Null
    Compress-Archive -LiteralPath $newKit -DestinationPath (Join-Path $goodDir $packageName) -CompressionLevel Optimal
    $packagePath = Join-Path $goodDir $packageName
    $packageHash = (Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $packageSize = (Get-Item -LiteralPath $packagePath).Length
    $goodManifest = [ordered]@{
        version   = $NewVersion
        package   = $packageName
        sha256    = $packageHash
        size      = $packageSize
        published = (Get-Date).ToUniversalTime().ToString('o')
        notes     = 'local HTTP drill'
    }
    [System.IO.File]::WriteAllText((Join-Path $goodDir 'update.json'),
        ($goodManifest | ConvertTo-Json) + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))
    # Same package, same digest; the server publishes the full length but stops after 1 MiB.
    Copy-Item -LiteralPath $packagePath -Destination (Join-Path $cutDir $packageName) -Force
    [System.IO.File]::WriteAllText((Join-Path $cutDir 'update.json'),
        ($goodManifest | ConvertTo-Json) + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllText((Join-Path $badDir 'update.json'),
        ('{"version":"banana","package":"' + $packageName + '","sha256":"' + $packageHash + '","size":' + $packageSize + '}'),
        (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ('http-drill: package ' + $packageName + ' (' + [math]::Round($packageSize / 1KB, 1) + ' KiB) sha256 ' + $packageHash)

    $requestLog = Join-Path $WorkDir 'http-requests.jsonl'
    $server = Start-StaticServer -Root $wwwRoot -RequestLog $requestLog -StopFile (Join-Path $WorkDir 'http.stop')
    $baseUrl = 'http://127.0.0.1:' + $server.Port + '/'
    Write-Host ('http-drill: source ' + $baseUrl + 'good/')

    Write-Host 'http-drill: building client harness (MediaHub.Update)'
    $clientExe = New-ClientHarness
    if (Test-Aborted) { throw 'aborted by user input' }

    # --- 1. upgrade over HTTP ------------------------------------------------------------------
    Write-Host ''
    Write-Host 'http-drill: [1/3] check -> download -> verify -> agent -> restart'
    Install-OldTree $oldKit
    Remove-Item -LiteralPath $probeMarker -ErrorAction SilentlyContinue
    $json = Join-Path $WorkDir 'client-upgrade.json'
    $output = & $clientExe --source ($baseUrl + 'good/') --installed $OldVersion --data-dir (Join-Path $downloadDir 'upgrade') --json $json 2>&1
    $clientExit = $LASTEXITCODE
    foreach ($line in $output) { Write-Host ('    ' + $line) }
    Assert-True ($clientExit -eq 0) ('client exited ' + $clientExit)
    $result = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json
    Assert-True ($result.available -eq $true) 'the client did not see the update'
    Assert-True ($result.version -like ($NewVersion + '*')) ('manifest version is ' + $result.version)
    Assert-True ($result.sizeMatch -and $result.hashMatch) 'hash/size verification did not pass'
    Assert-True ($result.bytes -eq $packageSize) ('downloaded ' + $result.bytes + ' bytes, expected ' + $packageSize)

    $agentResult = Invoke-Agent -PackageZip $result.packagePath -Hash $result.sha256
    Assert-True ($agentResult.Code -eq 0) ('agent exited ' + $agentResult.Code)
    Assert-True ((Get-ExeVersion (Join-Path $installDir 'MediaHub.exe')) -like ($NewVersion + '*')) 'installed version did not change'
    Start-Sleep -Milliseconds 800
    Assert-True (Test-Path -LiteralPath $probeMarker) 'the restarted application never ran'
    $markerText = (Get-Content -LiteralPath $probeMarker -Raw).Trim()
    Assert-True ($markerText -like ('*version=' + $NewVersion + '.0')) ('restarted version was: ' + $markerText)
    Assert-Shortcut (Join-Path $startMenu 'MediaHub.lnk')
    Assert-Shortcut (Join-Path $desktop 'MediaHub.lnk')
    Assert-Shortcut (Join-Path $taskbar 'MediaHub.lnk')
    $state = Get-Content -LiteralPath (Join-Path $stateDir 'state.json') -Raw | ConvertFrom-Json
    Assert-True ($state.status -eq 'succeeded') ('state is ' + $state.status)
    Assert-True ($state.restarted -eq $true) 'state.json does not record a restart'
    Start-Sleep -Milliseconds 300
    $requests = @(Get-Content -LiteralPath $requestLog | ForEach-Object { $_ | ConvertFrom-Json })
    $manifestGet = @($requests | Where-Object { $_.path -like '*/good/update.json' })
    $packageGet = @($requests | Where-Object { $_.path -like '*/good/*.zip' })
    Assert-True ($manifestGet.Count -eq 1) ('manifest was fetched ' + $manifestGet.Count + ' times')
    Assert-True ($packageGet.Count -eq 1) ('package was fetched ' + $packageGet.Count + ' times')
    Assert-True ($packageGet[0].bytes -eq $packageSize) ('server sent ' + $packageGet[0].bytes + ' of ' + $packageSize + ' bytes')
    Write-Host ('http-drill: [1/3] PASS ' + $OldVersion + ' -> ' + $NewVersion + ', restart observed, ' + $packageGet[0].bytes + ' bytes over HTTP') -ForegroundColor Green
    if (Test-Aborted) { throw 'aborted by user input' }

    # --- 2. download cut in half ----------------------------------------------------------------
    Write-Host ''
    Write-Host 'http-drill: [2/3] download interrupted at 1 MiB'
    Install-OldTree $oldKit
    $beforeHash = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.exe') -Algorithm SHA256).Hash
    $jsonCut = Join-Path $WorkDir 'client-cut.json'
    $cutOutput = & $clientExe --source ($baseUrl + 'truncated/') --installed $OldVersion --data-dir (Join-Path $downloadDir 'cut') --json $jsonCut 2>&1
    $cutExit = $LASTEXITCODE
    foreach ($line in $cutOutput) { Write-Host ('    ' + $line) }
    Assert-True ($cutExit -eq 4) ('interrupted download exited ' + $cutExit + ', expected 4')
    Start-Sleep -Milliseconds 300
    $cutRequests = @(Get-Content -LiteralPath $requestLog | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.path -like '*/truncated/*.zip' })
    Assert-True ($cutRequests.Count -eq 1) 'truncated package was not requested exactly once'
    Assert-True ($cutRequests[0].cut -eq $true) 'the server did not cut the response'
    $afterHash = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.exe') -Algorithm SHA256).Hash
    Assert-True ($beforeHash -eq $afterHash) 'an interrupted download changed the installed tree'
    $parts = @(Get-ChildItem -LiteralPath $downloadDir -Filter '*.part' -File -Recurse -ErrorAction SilentlyContinue)
    Assert-True ($parts.Count -eq 0) 'a .part file was left behind after the failed download'
    Write-Host ('http-drill: [2/3] PASS cut after ' + $cutRequests[0].bytes + ' of ' + $cutRequests[0].total + ' bytes, no partial file, install untouched') -ForegroundColor Green
    if (Test-Aborted) { throw 'aborted by user input' }

    # --- 3. manifest with an invalid version ---------------------------------------------------
    Write-Host ''
    Write-Host 'http-drill: [3/3] manifest with an invalid version'
    Install-OldTree $oldKit
    $beforeHash = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.exe') -Algorithm SHA256).Hash
    $jsonBad = Join-Path $WorkDir 'client-bad.json'
    $badOutput = & $clientExe --source ($baseUrl + 'badversion/') --installed $OldVersion --data-dir (Join-Path $downloadDir 'bad') --json $jsonBad 2>&1
    $badExit = $LASTEXITCODE
    foreach ($line in $badOutput) { Write-Host ('    ' + $line) }
    Assert-True ($badExit -eq 3) ('invalid manifest exited ' + $badExit + ', expected 3')
    Assert-True ((($badOutput | ForEach-Object { $_.ToString() }) -join ' ') -like '*version*') 'the rejection does not name the version'
    $afterHash = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.exe') -Algorithm SHA256).Hash
    Assert-True ($beforeHash -eq $afterHash) 'an invalid manifest changed the installed tree'
    Start-Sleep -Milliseconds 300
    $zipRequests = @(Get-Content -LiteralPath $requestLog | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.path -like '*/badversion/*.zip' })
    Assert-True ($zipRequests.Count -eq 0) 'an invalid manifest still downloaded the package'
    Write-Host 'http-drill: [3/3] PASS invalid manifest rejected before download (exit 3)' -ForegroundColor Green

    Write-Host ''
    Write-Host 'http-drill: request log'
    foreach ($entry in @(Get-Content -LiteralPath $requestLog | ForEach-Object { $_ | ConvertFrom-Json })) {
        Write-Host ('    ' + $entry.method + ' ' + $entry.path + ' -> ' + $entry.status + ' ' + $entry.bytes + '/' + $entry.total + ' bytes' + $(if ($entry.cut) { ' (cut)' } else { '' }))
    }
    Write-Host ''
    Write-Host 'RESULT: HTTP UPDATE CHAIN PASS (upgrade+restart, truncated download, invalid manifest)'
}
catch {
    if (Test-Path -LiteralPath $abortFile) { $exitCode = 11 } else { $exitCode = 1 }
    Write-Host ('http-drill: FAILED - ' + $_.Exception.Message) -ForegroundColor Red
}
finally {
    Stop-StaticServer $server
    if ($watchdog) {
        Set-Content -LiteralPath $stopWatchFile -Value 'stop' -Encoding ASCII -ErrorAction SilentlyContinue
        [void](Wait-Job -Job $watchdog -Timeout 10)
        Remove-Job -Job $watchdog -Force -ErrorAction SilentlyContinue
    }

    $logArchive = Join-Path $artifactRoot 'last-http-drill'
    try {
        if (Test-Path -LiteralPath $logArchive) { Remove-Item -LiteralPath $logArchive -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $logArchive | Out-Null
        foreach ($name in @('http-requests.jsonl', 'client-upgrade.json', 'client-cut.json', 'client-bad.json')) {
            $file = Join-Path $WorkDir $name
            if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination $logArchive -Force }
        }
        if (Test-Path -LiteralPath (Join-Path $stateDir 'logs')) {
            Copy-Item -LiteralPath (Join-Path $stateDir 'logs') -Destination $logArchive -Recurse -Force
        }
        Write-Host ('LOGS: ' + $logArchive)
    }
    catch { Write-Host ('http-drill: could not archive logs: ' + $_.Exception.Message) }

    Remove-Item Env:\MH_PROBE_MARKER -ErrorAction SilentlyContinue

    if ($KeepWorkDir) {
        Write-Host ('EVIDENCE: ' + $WorkDir)
    }
    else {
        try {
            if (Test-Path -LiteralPath $installDir) {
                foreach ($process in @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue)) {
                    $path = ''
                    try { $path = $process.Path } catch { }
                    if ($path -and $path.StartsWith($WorkDir, [System.StringComparison]::OrdinalIgnoreCase)) {
                        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    }
                }
            }
            Remove-Item -LiteralPath $WorkDir -Recurse -Force
            Write-Host ('CLEANUP: removed temporary installation and HTTP server files under ' + $WorkDir)
        }
        catch { Write-Host ('http-drill: CLEANUP FAILED, remove manually: ' + $WorkDir) -ForegroundColor Yellow }
    }
}
exit $exitCode

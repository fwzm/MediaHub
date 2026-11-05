<#
.SYNOPSIS
    Builds and runs the WebDAV provider tests with build output kept outside the repository.

.DESCRIPTION
    The manual instruction was to pass -p:BaseOutputPath and -p:BaseIntermediateOutputPath on the
    command line. Done literally that breaks the build, and the reason is worth recording because it
    will bite again:

      1. MSBuild global properties (the -p: form) propagate to every project in the graph, so the
         three referenced projects also get BaseIntermediateOutputPath pointed at one shared
         directory. Their own "obj/" trees then stop matching the SDK's default item exclusion and
         every stale generated AssemblyInfo.cs is compiled a second time: 24x CS0579.
      2. With one shared intermediate directory all five projects also share one
         project.assets.json, and a restore for one project overwrites the asset file of the others
         (NETSDK1005 as soon as two projects disagree about the target framework).

    This script therefore keeps per-project isolation and stays off the shared repo directories:

      * each project is restored and built in its own invocation, with its own intermediate
        directory, so no two projects ever share project.assets.json;
      * -p:DefaultItemExcludes=obj/** removes the stale generated sources left in the repository by
        earlier builds (the SDK only excludes the *overridden* intermediate path, which no longer
        points at "obj/");
      * -p:BuildProjectReferences=false builds one project at a time, using the references already
        produced by the earlier steps of this script, so nothing in the repository is written to;
      * -p:ProduceReferenceAssembly=false keeps reference resolution on the main assembly, whose
        location is shared through BaseOutputPath.

.PARAMETER OutputRoot
    Directory for build outputs. Must not be inside the repository.

.PARAMETER IntermediateRoot
    Directory for intermediate artifacts. Must not be inside the repository.

.PARAMETER SkipTests
    Build only.

.EXAMPLE
    pwsh -File windows/tests/MediaHub.WebDav.Tests/run-webdav-tests.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputRoot = 'D:\mh-wdav',
    [string]$IntermediateRoot = 'D:\mh-wdav-obj',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

$projects = @(
    @{ Name = 'MediaHub.Domain';            Path = 'windows\src\MediaHub.Domain\MediaHub.Domain.csproj' },
    @{ Name = 'MediaHub.Net';               Path = 'windows\src\MediaHub.Net\MediaHub.Net.csproj' },
    @{ Name = 'MediaHub.Providers';         Path = 'windows\src\MediaHub.Providers\MediaHub.Providers.csproj' },
    @{ Name = 'MediaHub.Providers.WebDav';  Path = 'windows\src\MediaHub.Providers.WebDav\MediaHub.Providers.WebDav.csproj' },
    @{ Name = 'MediaHub.WebDav.Tests';      Path = 'windows\tests\MediaHub.WebDav.Tests\MediaHub.WebDav.Tests.csproj' }
)

function Invoke-Dotnet {
    param([string[]]$Arguments)
    Write-Host ('  dotnet ' + ($Arguments -join ' ')) -ForegroundColor DarkGray
    & dotnet @Arguments
    if ($LASTEXITCODE -ne 0) { throw "dotnet exited with $LASTEXITCODE" }
}

foreach ($project in $projects) {
    $full = Join-Path $repoRoot $project.Path
    $props = @(
        "-p:BaseOutputPath=$OutputRoot\",
        "-p:BaseIntermediateOutputPath=$IntermediateRoot\$($project.Name)\",
        '-p:DefaultItemExcludes=obj/**',
        '-p:ProduceReferenceAssembly=false'
    )

    Write-Host "=== $($project.Name) ===" -ForegroundColor Cyan
    # --no-dependencies: restore this project only; the reference graph would otherwise write every
    # project's assets file to this project's intermediate directory.
    Invoke-Dotnet (@('restore', $full, '--no-dependencies', '-v:q', '-nologo') + $props)
    Invoke-Dotnet (@('build', $full, '--no-restore', '-p:BuildProjectReferences=false', '-v:m', '-nologo') + $props)
}

if ($SkipTests) { return }

$testProject = Join-Path $repoRoot 'windows\tests\MediaHub.WebDav.Tests\MediaHub.WebDav.Tests.csproj'
$testProps = @(
    "-p:BaseOutputPath=$OutputRoot\",
    "-p:BaseIntermediateOutputPath=$IntermediateRoot\MediaHub.WebDav.Tests\",
    '-p:DefaultItemExcludes=obj/**',
    '-p:ProduceReferenceAssembly=false'
)

Write-Host '=== tests ===' -ForegroundColor Cyan
Invoke-Dotnet (@('test', $testProject, '--no-build', '--no-restore', '-v:q', '-nologo') + $testProps)

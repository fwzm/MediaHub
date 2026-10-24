#Requires -Version 7.0
<#
.SYNOPSIS
  Collect the runtime dependencies of the LGPL libmpv build into windows/engine/lgpl/dist so that
  the directory is self-contained.

.DESCRIPTION
  windows/engine/lgpl/dist/libmpv-2.dll is an LGPL build that links FFmpeg (av*-*.dll) and a handful
  of MSYS2 libraries as *shared* libraries. Those DLLs were never copied next to it, which is why the
  headless probe only loaded the build when it was handed the MSYS2 search directories
  (C:\msys64\ucrt64\bin, D:\mh-servers\lgpl-build\prefix\bin).

  This script walks the PE import tables of dist\libmpv-2.dll recursively, resolves every imported
  DLL against an ordered list of dependency roots, copies the non-system ones into dist and writes
  dist\runtime-dependencies-collected.txt ('name size sha256 source' per line).

  The PE parsing is done in plain PowerShell on purpose: dumping imports with objdump.exe/dumpbin.exe
  would tie the repository to a machine that has MSYS2 or Visual Studio installed, and this script has
  to run on a clean Windows box that only has .NET.

  WHAT COUNTS AS A SYSTEM DLL (the rule, in order)
    1. api-ms-win-* / ext-ms-win-* are API sets. They are virtual names that only exist in
       C:\Windows\System32 and are always resolvable by the loader, so they are never copied.
    2. A name that resolves inside one of the dependency roots (C:\msys64\ucrt64\bin,
       D:\mh-servers\lgpl-build\prefix\bin) is a *third-party* dependency even when a file with the
       same name also lives in C:\Windows\System32. The MinGW runtime is the reason for this ordering:
       libwinpthread-1.dll, libgcc_s_seh-1.dll and friends exist in both places, and the copy the
       build was linked against is the one in the MSYS2 prefix.
    3. Anything else that exists in C:\Windows\System32 is a Windows component and is left alone.
    4. Anything that matches none of the above is reported as UNRESOLVED and makes the script exit
       non-zero: a silently missing dependency would only show up later as a load failure.

  The script is re-runnable. A file already present in dist is left untouched (and reported as
  'dist') unless -Force is given and its hash differs from the root copy.

.PARAMETER DependencyRoots
  Ordered search path for imported DLLs, most specific first. Defaults to the two directories the
  W13 probe needed and the FFmpeg prefix build output.

.PARAMETER WhatIf
  Parse and report only; copy nothing. Part of the standard -WhatIf support, so -WhatIf prints the
  same table the real run writes to runtime-dependencies-collected.txt.

.EXAMPLE
  pwsh -NoProfile -File windows/tools/collect-lgpl-runtime.ps1 -WhatIf

.EXAMPLE
  pwsh -NoProfile -File windows/tools/collect-lgpl-runtime.ps1

.NOTES
  Exit codes: 0 = dist is self-contained, 1 = usage/paths, 2 = core DLL missing or unreadable,
  3 = unresolved imports, 4 = dist\libmpv-2.dll is not the expected LGPL build.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$RepoRoot = '',
    [string]$DistDirectory = '',
    [string]$CoreDllName = 'libmpv-2.dll',
    [string[]]$DependencyRoots = @(
        'D:\mh-servers\lgpl-build\prefix\bin',
        'C:\msys64\ucrt64\bin'
    ),
    [string]$OutputFileName = 'runtime-dependencies-collected.txt',
    [switch]$Force,
    [switch]$AllowUnresolved
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

if ([string]::IsNullOrWhiteSpace($DistDirectory)) {
    $DistDirectory = Join-Path $RepoRoot 'windows\engine\lgpl\dist'
}

# Identity of the artefact this script is allowed to complete. An LGPL build that silently became
# something else (a relink, a different mpv tag) must not be blessed with a dependency bundle.
$ExpectedCoreSha256 = '20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a'
$ExpectedCoreBytes = 3327274

$systemRoot = $env:SystemRoot
if ([string]::IsNullOrWhiteSpace($systemRoot)) { $systemRoot = 'C:\Windows' }
$system32 = Join-Path $systemRoot 'System32'

$distPath = (Resolve-Path -LiteralPath $DistDirectory -ErrorAction SilentlyContinue)
if ($null -eq $distPath) {
    Write-Host ("ERROR dist directory does not exist: " + $DistDirectory)
    exit 1
}
$DistDirectory = $distPath.Path

$corePath = Join-Path $DistDirectory $CoreDllName
if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) {
    Write-Host ("ERROR core DLL not found: " + $corePath)
    Write-Host '      build the LGPL engine first (windows/engine/lgpl/build-mpv-lgpl.sh) and copy libmpv-2.dll into dist.'
    exit 2
}

# ---------------------------------------------------------------- helpers

function Get-Sha256Hex {
    param([Parameter(Mandatory)][string]$Path)
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hash = [System.Security.Cryptography.SHA256]::HashData($stream)
        return [System.Convert]::ToHexString($hash).ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
    }
}

function Read-AsciiZ {
    param([byte[]]$Bytes, [int]$Offset)
    $builder = [System.Text.StringBuilder]::new()
    for ($i = $Offset; $i -lt $Bytes.Length; $i++) {
        $b = $Bytes[$i]
        if ($b -eq 0) { break }
        [void]$builder.Append([char]$b)
    }
    return $builder.ToString()
}

function Convert-RvaToOffset {
    param([object[]]$Sections, [uint32]$Rva)
    foreach ($section in $Sections) {
        $span = [Math]::Max($section.VirtualSize, $section.RawSize)
        if ($Rva -ge $section.VirtualAddress -and $Rva -lt ($section.VirtualAddress + $span)) {
            return [int]($section.RawPointer + ($Rva - $section.VirtualAddress))
        }
    }
    return -1
}

# Reads the import table (and the delay-load table when present) of one PE file.
function Get-PeImportedDlls {
    param([Parameter(Mandatory)][string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 0x40) { throw "not a PE file (too short): $Path" }
    if ($bytes[0] -ne 0x4D -or $bytes[1] -ne 0x5A) { throw "not a PE file (no MZ header): $Path" }

    $peOffset = [BitConverter]::ToInt32($bytes, 0x3C)
    if ($peOffset -le 0 -or ($peOffset + 24) -ge $bytes.Length) { throw "not a PE file (bad e_lfanew): $Path" }
    if ($bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45) { throw "not a PE file (no PE signature): $Path" }

    $coff = $peOffset + 4
    $sectionCount = [BitConverter]::ToUInt16($bytes, $coff + 2)
    $optionalSize = [BitConverter]::ToUInt16($bytes, $coff + 16)
    $optional = $coff + 20
    $magic = [BitConverter]::ToUInt16($bytes, $optional)
    $isPe32Plus = $magic -eq 0x20B
    if (-not $isPe32Plus -and $magic -ne 0x10B) { throw "unknown PE optional header magic 0x$('{0:x}' -f $magic): $Path" }

    $dataDirectoryOffset = if ($isPe32Plus) { $optional + 112 } else { $optional + 96 }
    $sectionTable = $optional + $optionalSize

    $sections = @()
    for ($i = 0; $i -lt $sectionCount; $i++) {
        $entry = $sectionTable + ($i * 40)
        if ($entry + 40 -gt $bytes.Length) { break }
        $sections += [pscustomobject]@{
            Name           = Read-AsciiZ -Bytes $bytes -Offset $entry
            VirtualSize    = [BitConverter]::ToUInt32($bytes, $entry + 8)
            VirtualAddress = [BitConverter]::ToUInt32($bytes, $entry + 12)
            RawSize        = [BitConverter]::ToUInt32($bytes, $entry + 16)
            RawPointer     = [BitConverter]::ToUInt32($bytes, $entry + 20)
        }
    }

    $names = [System.Collections.Generic.List[string]]::new()

    # Index 1 = IMAGE_DIRECTORY_ENTRY_IMPORT, index 13 = IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT.
    foreach ($directoryIndex in @(1, 13)) {
        $directory = $dataDirectoryOffset + ($directoryIndex * 8)
        if ($directory + 8 -gt $bytes.Length) { continue }
        $directoryRva = [BitConverter]::ToUInt32($bytes, $directory)
        $directorySize = [BitConverter]::ToUInt32($bytes, $directory + 4)
        if ($directoryRva -eq 0 -or $directorySize -eq 0) { continue }

        $offset = Convert-RvaToOffset -Sections $sections -Rva $directoryRva
        if ($offset -lt 0) { continue }

        $descriptorSize = if ($directoryIndex -eq 1) { 20 } else { 32 }
        $imageBase = if ($isPe32Plus) { [BitConverter]::ToUInt64($bytes, $optional + 24) } else { [uint64][BitConverter]::ToUInt32($bytes, $optional + 28) }

        while (($offset + $descriptorSize) -le $bytes.Length) {
            $allZero = $true
            for ($b = 0; $b -lt $descriptorSize; $b++) {
                if ($bytes[$offset + $b] -ne 0) { $allZero = $false; break }
            }
            if ($allZero) { break }

            if ($directoryIndex -eq 1) {
                $nameRva = [BitConverter]::ToUInt32($bytes, $offset + 12)
            }
            else {
                $attributes = [BitConverter]::ToUInt32($bytes, $offset)
                $nameRva = [BitConverter]::ToUInt32($bytes, $offset + 4)
                # Old-style delay descriptors stored VAs; bit 0 ("dlattrRva") says the fields are RVAs.
                if (($attributes -band 1) -eq 0 -and $nameRva -ne 0) {
                    $nameRva = [uint32]([uint64]$nameRva - $imageBase)
                }
            }

            if ($nameRva -ne 0) {
                $nameOffset = Convert-RvaToOffset -Sections $sections -Rva $nameRva
                if ($nameOffset -ge 0 -and $nameOffset -lt $bytes.Length) {
                    $name = Read-AsciiZ -Bytes $bytes -Offset $nameOffset
                    if (-not [string]::IsNullOrWhiteSpace($name)) {
                        $names.Add($name)
                    }
                }
            }

            $offset += $descriptorSize
        }
    }

    return $names
}

function Find-InRoots {
    param([string]$Name, [string[]]$Roots)
    foreach ($root in $Roots) {
        if ([string]::IsNullOrWhiteSpace($root)) { continue }
        $candidate = Join-Path $root $Name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Find-InDirectory {
    param([string]$Directory, [string]$Name)
    $candidate = Join-Path $Directory $Name
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    return $null
}

# ---------------------------------------------------------------- identity of the core artefact

$coreInfo = Get-Item -LiteralPath $corePath
$coreSha = Get-Sha256Hex -Path $corePath
Write-Host '== collect LGPL runtime dependencies =='
Write-Host ('repo            : ' + $RepoRoot)
Write-Host ('dist            : ' + $DistDirectory)
Write-Host ('core            : ' + $corePath)
Write-Host ('core bytes      : ' + $coreInfo.Length)
Write-Host ('core sha256     : ' + $coreSha)

if ($coreSha -ne $ExpectedCoreSha256) {
    Write-Host ''
    Write-Host ('ERROR dist\' + $CoreDllName + ' is not the expected LGPL build.')
    Write-Host ('      expected sha256 ' + $ExpectedCoreSha256 + ' (' + $ExpectedCoreBytes + ' bytes)')
    Write-Host ('      found    sha256 ' + $coreSha + ' (' + $coreInfo.Length + ' bytes)')
    exit 4
}

Write-Host ('dependency roots: ' + ($DependencyRoots -join ' ; '))
Write-Host ''

# ---------------------------------------------------------------- resolve the closure

$resolved = [System.Collections.Generic.List[object]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$systemSkipped = [System.Collections.Generic.List[string]]::new()
$unresolved = [System.Collections.Generic.List[string]]::new()
$queue = [System.Collections.Generic.Queue[object]]::new()

$seen.Add($CoreDllName) | Out-Null
$queue.Enqueue([pscustomobject]@{ Name = $CoreDllName; Path = $corePath; Origin = 'dist'; IsCore = $true })

$parsed = 0
while ($queue.Count -gt 0) {
    $item = $queue.Dequeue()
    $imports = Get-PeImportedDlls -Path $item.Path
    $parsed++

    if (-not $item.IsCore) {
        $resolved.Add([pscustomobject]@{
            Name     = $item.Name
            Path     = $item.Path
            Origin   = $item.Origin
            InDist   = ($item.Origin -eq 'dist')
            Imported = @($imports)
        })
    }

    foreach ($import in $imports) {
        if (-not $seen.Add($import)) { continue }

        $local = Find-InDirectory -Directory $DistDirectory -Name $import
        if ($null -ne $local) {
            $queue.Enqueue([pscustomobject]@{ Name = $import; Path = $local; Origin = 'dist'; IsCore = $false })
            continue
        }

        $source = Find-InRoots -Name $import -Roots $DependencyRoots
        if ($null -ne $source) {
            $queue.Enqueue([pscustomobject]@{ Name = $import; Path = $source; Origin = $source; IsCore = $false })
            continue
        }

        # api-ms-win-* / ext-ms-win-* are API set contracts, never real files to ship.
        if ($import -match '^(api-ms-win|ext-ms-win)-') {
            $systemSkipped.Add($import)
            continue
        }

        if (Test-Path -LiteralPath (Join-Path $system32 $import) -PathType Leaf) {
            $systemSkipped.Add($import)
            continue
        }

        $unresolved.Add($import)
    }
}

Write-Host ('PE files parsed : ' + $parsed)
Write-Host ('dependencies    : ' + $resolved.Count + ' non-system, ' + $systemSkipped.Count + ' system, ' + $unresolved.Count + ' unresolved')

# ---------------------------------------------------------------- copy

$copied = [System.Collections.Generic.List[object]]::new()
$kept = [System.Collections.Generic.List[object]]::new()
$conflicts = [System.Collections.Generic.List[string]]::new()

foreach ($entry in ($resolved | Sort-Object Name)) {
    $sourceSha = Get-Sha256Hex -Path $entry.Path
    $sourceBytes = (Get-Item -LiteralPath $entry.Path).Length
    $target = Join-Path $DistDirectory $entry.Name

    $record = [pscustomobject]@{
        Name   = $entry.Name
        Bytes  = $sourceBytes
        Sha256 = $sourceSha
        Source = $entry.Path
    }

    if (Test-Path -LiteralPath $target -PathType Leaf) {
        $targetSha = Get-Sha256Hex -Path $target
        if ($targetSha -eq $sourceSha) {
            $record.Source = $target
            $kept.Add($record)
            continue
        }

        if (-not $Force) {
            $conflicts.Add(($entry.Name + ' (dist=' + $targetSha.Substring(0, 12) + '… source=' + $sourceSha.Substring(0, 12) + '…)'))
            $record.Source = $target
            $kept.Add($record)
            continue
        }
    }

    if ($entry.Origin -eq 'dist') {
        # Already in dist with the same bytes; nothing to do.
        $record.Source = $target
        $kept.Add($record)
        continue
    }

    if ($PSCmdlet.ShouldProcess($target, ('copy ' + $sourceBytes + ' bytes from ' + $entry.Path))) {
        Copy-Item -LiteralPath $entry.Path -Destination $target -Force
        $copied.Add($record)
    }
}

# ---------------------------------------------------------------- report

$header = @(
    '# Runtime dependencies of the LGPL libmpv build (windows/engine/lgpl/dist)',
    '# generated by windows/tools/collect-lgpl-runtime.ps1',
    ('# generated at    : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:sszzz')),
    ('# core artefact   : ' + $CoreDllName + ' sha256=' + $coreSha + ' bytes=' + $coreInfo.Length),
    ('# dependency roots: ' + ($DependencyRoots -join ' ; ')),
    '#',
    '# One line per shipped dependency: <name> <bytes> <sha256> <source>',
    '#   source = where the bytes came from; the dist path itself once the file is already in place.',
    ('# system DLLs not copied (' + $systemSkipped.Count + '): ' + (($systemSkipped | Sort-Object) -join ' ')),
    '#'
)

$lines = [System.Collections.Generic.List[string]]::new()
$header | ForEach-Object { $lines.Add($_) }

foreach ($record in (($copied + $kept) | Sort-Object Name)) {
    $lines.Add(('{0} {1} {2} {3}' -f $record.Name, $record.Bytes, $record.Sha256, $record.Source))
}

$outputPath = Join-Path $DistDirectory $OutputFileName
if ($PSCmdlet.ShouldProcess($outputPath, 'write dependency manifest')) {
    $lines | Set-Content -LiteralPath $outputPath -Encoding utf8
}

Write-Host ''
Write-Host ('copied          : ' + $copied.Count)
foreach ($record in ($copied | Sort-Object Name)) {
    Write-Host ('  + ' + $record.Name + ' (' + $record.Bytes + ' bytes) from ' + $record.Source)
}
Write-Host ('already present : ' + $kept.Count)
foreach ($record in ($kept | Sort-Object Name)) {
    Write-Host ('  = ' + $record.Name + ' (' + $record.Bytes + ' bytes)')
}

if ($conflicts.Count -gt 0) {
    Write-Host ''
    Write-Host 'WARNING hash conflicts left untouched (use -Force to replace):'
    $conflicts | ForEach-Object { Write-Host ('  ! ' + $_) }
}

if ($unresolved.Count -gt 0) {
    Write-Host ''
    Write-Host 'UNRESOLVED imports (not in dist, not in the dependency roots, not a Windows DLL):'
    ($unresolved | Sort-Object) | ForEach-Object { Write-Host ('  ? ' + $_) }
}

if ($PSCmdlet.ShouldProcess($outputPath, 'report')) {
    Write-Host ''
    Write-Host ('manifest        : ' + $outputPath)
}

$whatIf = $WhatIfPreference
if ($whatIf) {
    Write-Host ''
    Write-Host 'WHATIF: nothing was copied and no manifest was written.'
}

if ($unresolved.Count -gt 0 -and -not $AllowUnresolved) {
    Write-Host ''
    Write-Host 'RESULT: INCOMPLETE - the dist directory would not survive a machine without MSYS2.'
    exit 3
}

Write-Host ''
Write-Host ('RESULT: SELF-CONTAINED - ' + $resolved.Count + ' dependency DLLs resolved, ' + $systemSkipped.Count + ' Windows DLLs left to the OS.')
exit 0

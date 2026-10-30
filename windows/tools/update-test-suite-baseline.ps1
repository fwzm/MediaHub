# Refresh tools/test-suite-baseline.json from real TRX output.
#
# Run this after a test run when CI says "suite grew beyond the baseline",
# "new or larger test classes" or "new test methods". Do NOT run it to make a red
# gate green: a green by baseline surgery is the exact failure this file exists
# to prevent, which is why shrinking a suite (a case count, a class, a method or
# a method's own case count) requires the explicit -AcceptRemoval switch and
# shows up as a diff in the same commit as the removal.
#
# The written file carries four levels, each one closing a substitution the
# level above cannot see:
#   expected : total case count (deleting and adding cases keeps it equal)
#   classes  : cases per class   (deleting and adding inside one class keeps it)
#   methods  : method names per class (deleting one method and adding another to
#              the same class keeps every count above identical)
#   methods  : cases per method (a [Theory] reports one method with many data
#              rows, so moving a row between two theories of one class keeps the
#              total, the class count and the method set identical)
# The argument text of a data row is deliberately not stored: it is produced by
# the test framework, is not stable text, and would turn every data table edit
# into a baseline diff. Counts are stable and pin "how many rows ran".
#
# Typical local use (after the two dotnet test steps, from the windows directory):
#   powershell -NoProfile -File tools/update-test-suite-baseline.ps1
#   powershell -NoProfile -File tools/update-test-suite-baseline.ps1 -ResultsDir D:\ci\test-results
#
# Output is ASCII on purpose: Windows PowerShell 5.1 decodes BOM-less UTF-8 as
# ANSI, so a non-ASCII baseline corrupts on read. Non-ASCII names fail the write
# instead of being silently mangled.

[CmdletBinding()]
param(
  [string]$BaselinePath = "./tools/test-suite-baseline.json",
  [string]$ResultsDir = "./artifacts/test-results",
  [switch]$AcceptRemoval
)

$ErrorActionPreference = "Stop"
$nl = [string][char]10
$q = [string][char]34

$comment = @(
  "Windows CI test-suite baseline. Consumed by windows/tools/check-test-suite-baseline.ps1.",
  "  total  < expected -> red   (cases were deleted or silently skipped)",
  "  total  > expected -> green (new cases are not a failure) with a please-update warning",
  "  a class listed here must still exist with at least this many cases; equal totals with",
  "  different classes are red, because deleting N cases and adding N others keeps the total.",
  "  a method listed here for a class must still be reported by the run; equal totals and",
  "  equal per-class counts with different methods are red, because deleting one method and",
  "  adding another to the same class keeps every count identical.",
  "  a method must also still report at least this many cases; equal totals, equal class counts",
  "  and an unchanged method list are red when a data row moved between two theories of one",
  "  class. The argument text of a row is not stored, only how many rows the method ran.",
  "Why not just raise a hard-coded floor: that only moves the same rot to the next person.",
  "A committed baseline shows up in review as a diff and can be git-blamed.",
  "Refresh (never to turn a red gate green): windows/tools/update-test-suite-baseline.ps1",
  "ASCII only on purpose: PowerShell 5.1 Get-Content decodes BOM-less UTF-8 as ANSI and corrupts",
  "non-ASCII text, which breaks ConvertFrom-Json. Keep this file pure ASCII."
)

function Read-Suite([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { throw "missing test results: $Path" }
  $xml = [xml](Get-Content -LiteralPath $Path -Raw)
  if (-not $xml.TestRun.ResultSummary.Counters) { throw "$Path has no ResultSummary/Counters" }
  $total = [int]$xml.TestRun.ResultSummary.Counters.total
  $idToClass = @{}
  $methods = @{}
  foreach ($ut in @($xml.TestRun.TestDefinitions.UnitTest)) {
    if ($null -eq $ut) { continue }
    $cls = $null
    $raw = $null
    if ($ut.TestMethod) {
      $cls = [string]$ut.TestMethod.className
      $raw = [string]$ut.TestMethod.name
    }
    if ([string]::IsNullOrWhiteSpace($cls)) { throw "$Path : a test definition has no TestMethod.className" }
    if ([string]::IsNullOrWhiteSpace($raw)) { $raw = [string]$ut.name }
    if ([string]::IsNullOrWhiteSpace($raw)) { throw "$Path : a test definition has no method name" }
    $base = $raw
    $paren = $raw.IndexOf('(')
    if ($paren -ge 0) { $base = $raw.Substring(0, $paren) }
    if (-not $methods.ContainsKey($cls)) { $methods[$cls] = @{} }
    if ($methods[$cls].ContainsKey($base)) {
      $methods[$cls][$base] = $methods[$cls][$base] + 1
    } else {
      $methods[$cls][$base] = 1
    }
    $idToClass[[string]$ut.id] = $cls
  }
  $classes = @{}
  foreach ($res in @($xml.TestRun.Results.UnitTestResult)) {
    if ($null -eq $res) { continue }
    $cls = $null
    if ($idToClass.ContainsKey([string]$res.testId)) { $cls = $idToClass[[string]$res.testId] }
    if ($null -eq $cls) { throw "$Path : a result has no TestMethod.className" }
    if ($classes.ContainsKey($cls)) { $classes[$cls] = $classes[$cls] + 1 } else { $classes[$cls] = 1 }
  }
  return @{ total = $total; classes = $classes; methods = $methods }
}

# Only used to read the file being refreshed. Older entries stored method names as
# a bare list (the 2026-10-30 format); their case counts are unknown, so they are
# read as -1 and never make the run look smaller by themselves. Any other shape is
# treated as the case-count form.
function Convert-OldMethods($node) {
  $out = @{}
  if ($null -eq $node) { return $out }
  foreach ($cp in $node.PSObject.Properties) {
    $cls = [string]$cp.Name
    $inner = $cp.Value
    $map = @{}
    if ($null -ne $inner) {
      if ($inner -is [string] -or $inner -is [System.Array] -or $inner -is [System.Collections.IList]) {
        foreach ($m in @($inner)) {
          if (-not [string]::IsNullOrWhiteSpace([string]$m)) { $map[[string]$m] = -1 }
        }
      } else {
        foreach ($mp in $inner.PSObject.Properties) { $map[[string]$mp.Name] = [int]$mp.Value }
      }
    }
    $out[$cls] = $map
  }
  return $out
}

function Json-String([string]$s) {
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append($q)
  foreach ($ch in $s.ToCharArray()) {
    $code = [int]$ch
    if ($code -eq 34) { [void]$sb.Append($q + [char]92 + $q) }
    elseif ($code -eq 92) { [void]$sb.Append([char]92 + [char]92) }
    elseif ($code -lt 32) { [void]$sb.Append([char]92 + "u" + ("{0:x4}" -f $code)) }
    else { [void]$sb.Append($ch) }
  }
  [void]$sb.Append($q)
  return $sb.ToString()
}

if (-not (Test-Path -LiteralPath $BaselinePath)) { throw "missing $BaselinePath" }
$baseline = Get-Content -LiteralPath $BaselinePath -Raw | ConvertFrom-Json
if (-not $baseline.suites -or @($baseline.suites).Count -eq 0) { throw "$BaselinePath lists no suites" }

$entries = @()
$shrunk = @()
foreach ($e in $baseline.suites) {
  $path = Join-Path -Path $ResultsDir -ChildPath $e.file
  $read = Read-Suite -Path $path
  $oldClasses = @{}
  if ($null -ne $e.classes) {
    foreach ($p in $e.classes.PSObject.Properties) { $oldClasses[$p.Name] = [int]$p.Value }
  }
  $oldMethods = Convert-OldMethods $e.methods
  $removed = @()
  foreach ($name in @($oldClasses.Keys | Sort-Object)) {
    $before = $oldClasses[$name]
    $now = 0
    if ($read.classes.ContainsKey($name)) { $now = $read.classes[$name] }
    if ($now -lt $before) { $removed += "$name ($before -> $now)" }
  }
  foreach ($name in @($oldMethods.Keys | Sort-Object)) {
    if (-not $read.methods.ContainsKey($name)) {
      $removed += "$name (class gone)"
      continue
    }
    foreach ($m in @($oldMethods[$name].Keys | Sort-Object)) {
      if (-not $read.methods[$name].ContainsKey($m)) { $removed += "$name.$m (method gone)"; continue }
      $before = [int]$oldMethods[$name][$m]
      $now = [int]$read.methods[$name][$m]
      if ($before -ge 0 -and $now -lt $before) { $removed += "$name.$m (cases $before -> $now)" }
    }
  }
  if ([int]$e.expected -gt $read.total) { $removed += "total ($($e.expected) -> $($read.total))" }
  if ($removed.Count -gt 0 -and -not $AcceptRemoval) {
    $shrunk += "$($e.suite): " + ($removed -join ", ")
  }
  $entryMethods = @{}
  foreach ($name in $read.methods.Keys) {
    $inner = @{}
    foreach ($m in $read.methods[$name].Keys) { $inner[$m] = [int]$read.methods[$name][$m] }
    $entryMethods[$name] = $inner
  }
  $entries += @{ file = [string]$e.file; suite = [string]$e.suite; expected = $read.total; classes = $read.classes; methods = $entryMethods }
}
if ($shrunk.Count -gt 0) {
  throw ("refusing to lower the baseline without -AcceptRemoval - a smaller suite must be an explicit, reviewed change: " + ($shrunk -join " | "))
}

# Deterministic hand-written JSON: stable key order and stable class/method order
# across PowerShell 5.1 / 7, where ConvertTo-Json formatting differs.
$text = New-Object System.Text.StringBuilder
[void]$text.Append("{" + $nl + "  " + $q + "_comment" + $q + ": [" + $nl)
for ($i = 0; $i -lt $comment.Count; $i++) {
  $comma = [string][char]44
  if ($i -eq $comment.Count - 1) { $comma = "" }
  [void]$text.Append("    " + (Json-String $comment[$i]) + $comma + $nl)
}
[void]$text.Append("  ]," + $nl + "  " + $q + "suites" + $q + ": [" + $nl)
for ($i = 0; $i -lt $entries.Count; $i++) {
  $entry = $entries[$i]
  [void]$text.Append("    {" + $nl)
  [void]$text.Append("      " + $q + "file" + $q + ": " + (Json-String $entry.file) + "," + $nl)
  [void]$text.Append("      " + $q + "suite" + $q + ": " + (Json-String $entry.suite) + "," + $nl)
  [void]$text.Append("      " + $q + "expected" + $q + ": " + [int]$entry.expected + "," + $nl)
  [void]$text.Append("      " + $q + "classes" + $q + ": {" + $nl)
  $names = @($entry.classes.Keys | Sort-Object)
  for ($j = 0; $j -lt $names.Count; $j++) {
    $comma = [string][char]44
    if ($j -eq $names.Count - 1) { $comma = "" }
    [void]$text.Append("        " + (Json-String $names[$j]) + ": " + [int]$entry.classes[$names[$j]] + $comma + $nl)
  }
  [void]$text.Append("      }," + $nl)
  [void]$text.Append("      " + $q + "methods" + $q + ": {" + $nl)
  $mnames = @($entry.methods.Keys | Sort-Object)
  for ($k = 0; $k -lt $mnames.Count; $k++) {
    $clsName = $mnames[$k]
    $comma0 = [string][char]44
    if ($k -eq $mnames.Count - 1) { $comma0 = "" }
    [void]$text.Append("        " + (Json-String $clsName) + ": {" + $nl)
    $methods = @($entry.methods[$clsName].Keys | Sort-Object)
    for ($j = 0; $j -lt $methods.Count; $j++) {
      $comma = [string][char]44
      if ($j -eq $methods.Count - 1) { $comma = "" }
      [void]$text.Append("          " + (Json-String $methods[$j]) + ": " + [int]$entry.methods[$clsName][$methods[$j]] + $comma + $nl)
    }
    [void]$text.Append("        }" + $comma0 + $nl)
  }
  [void]$text.Append("      }" + $nl)
  [void]$text.Append("    }")
  if ($i -lt $entries.Count - 1) { [void]$text.Append(",") }
  [void]$text.Append($nl)
}
[void]$text.Append("  ]" + $nl + "}" + $nl)

$encoding = New-Object System.Text.ASCIIEncoding
try {
  [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $BaselinePath).Path, $text.ToString(), $encoding)
} catch {
  throw "refusing to write a non-ASCII baseline: $($_.Exception.Message)"
}
"wrote $BaselinePath"
foreach ($entry in $entries) {
  $methodCount = 0
  $caseCount = 0
  foreach ($cls in $entry.methods.Keys) {
    $methodCount += @($entry.methods[$cls].Keys).Count
    foreach ($m in $entry.methods[$cls].Keys) { $caseCount += [int]$entry.methods[$cls][$m] }
  }
  "$($entry.suite): total=$($entry.expected) classes=$(@($entry.classes.Keys).Count) methods=$methodCount cases=$caseCount"
}

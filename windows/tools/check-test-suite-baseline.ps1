# Test-suite baseline gate.
#
# WHY THIS FILE EXISTS
#   A "dotnet test" step that quietly runs fewer cases than before still prints
#   "Passed!". The gate used to live inline in .github/workflows/windows-ci.yml
#   with hard-coded floors (400 / 100 against 438 / 104 as of 2026-10-24). Those
#   floors rotted: by 2026-10-30 the suite had grown to ~1943 cases, so dropping
#   ~1540 of them (79%) still passed. The expected numbers now live in
#   tools/test-suite-baseline.json so that a suite shrinking shows up as a diff.
#
#   The total alone is not enough. Deleting N cases and adding N new ones keeps
#   the total identical, so the total-only gate cannot see it. Each suite entry
#   therefore also carries a per-class case count ("classes"), and every class
#   that existed in the baseline must still exist with at least its baseline
#   count.
#
#   Per-class counts are still not enough. Deleting one method and adding
#   another one to the SAME class keeps the total and every class count
#   identical, which is the same substitution one level down. Each suite entry
#   therefore also carries the method names that existed in each class
#   ("methods"), and every method that existed must still be reported by the run.
#
#   A method list is still not enough. A [Theory] reports one method with many
#   data rows, so deleting a data row from one theory and adding one to another
#   theory of the SAME class keeps the total, every class count and the method
#   set identical. Each entry therefore pins a case count per method
#   (methods: { class: { method: cases } }), and every method must still report
#   at least its baseline case count.
#
#   Per-method counts are still not enough. A [Theory] row carries its argument
#   text in the display name, so replacing one row's values keeps every count
#   identical while changing what the test actually exercises. Each entry
#   therefore also pins the argument rows themselves, one sorted list per method
#   that HAS argument text ("data": { "Class.Method": [ "(args)", ... ] }), with
#   duplicates kept, and a baseline row the run no longer reports is red.
#
#   A digest would not have been enough either, which is why the rows are stored
#   verbatim: a digest cannot separate "one row was added" (the documented
#   growth warning) from "one row was added AND another row's values were
#   replaced" (a hidden loss that would otherwise pass with a warning). The gate
#   compares multisets: identical -> pass, baseline a strict subset of the run ->
#   warning, anything else including a simultaneous add and replace -> red.
#
#   What that still cannot see: argument text the test framework already
#   truncated (the runner shortens long argument values and marks the cut), where
#   a difference past the truncation point yields the same display text. A TRX
#   alone cannot close that hole, so the gate makes the exposure visible instead:
#   each entry also records, per method, how many of its argument rows are
#   truncated ("truncated"), the run prints argumentTruncated=, and any change in
#   that set warns and names the methods. It is deliberately never red - the tail
#   is invisible to the TRX, so red would accuse a change that cannot be proven.
#
#   The same substitution exists one level up, at the suite itself. The baseline
#   file is what names the suites to check, so a suite entry deleted by hand (or
#   a whole test project dropped from the workflow) would simply stop being
#   checked, and every case it reported would stop being counted without any
#   count in the file changing. The baseline therefore pins how many suites it
#   must hold ("expected_suites", validated against the table itself), the run
#   enumerates the *.trx files it produced and warns about the ones the baseline
#   does not list, and each entry's declared suite name is compared with the test
#   assembly recorded inside its own results file.
#
#   Every count above is still read from the run's own ResultSummary/Counters, and
#   the per-row outcomes are never counted. A results file whose rows disagree with
#   its own summary - a hand-merged, patched or truncated artifact - therefore
#   passes as long as the summary numbers look clean, and an outcome kind the
#   summary does not name at all (Aborted / Inconclusive / Timeout / Error / ...) is
#   invisible. Each entry now also pins the outcome split
#   ("outcomes": { "<Outcome>": count }), the run counts the per-row outcomes and
#   refuses a file that disagrees with its own Counters (total included), and an
#   outcome kind the baseline did not have, or a baseline outcome that fell, is
#   red: a row moving from Passed to Aborted keeps the total, every class, every
#   method case count and every argument row identical.
#
# SEMANTICS
#   total    < baseline -> red      (cases were dropped or skipped)
#   total    > baseline -> green + warning (please refresh the baseline)
#   class missing or class count < baseline -> red
#   class new, or class count > baseline     -> green + warning
#   method in the baseline missing from the run (or moved to another class)
#     -> red, even when the total and every class count match
#   method reporting fewer cases than the baseline -> red (a theory lost rows)
#   method new, or method reporting more cases -> green + warning
#   a baseline argument row is no longer reported, or its text changed
#     -> red (the rows that ran are not the rows the baseline was reviewed with),
#        even when the row count grew in the same edit
#   a method reporting only argument rows the baseline did not have -> warning
#   the set of argument rows carrying the truncation marker changed -> warning
#     (the text past the marker is not pinned; the warning names the methods)
#   a *.trx in the results directory that no baseline entry lists -> warning
#     (a new suite has to stay visible before it can be gated)
#   a declared suite name that disagrees with the test assembly recorded in its
#     own results file -> red (the entry is wired to another suite's results)
#   per-row outcomes disagree with the run's own Counters -> red
#     (the summary is not trusted on its own; the rows are counted, total included)
#   an outcome kind the baseline did not have appears, or a baseline outcome
#     reports fewer rows -> red; an outcome reporting more rows -> green + warning
#   a test id that is not unique inside one TRX, a definition whose id has no
#     result row (a result whose testId names no definition is caught by the
#     unmapped-row check above, which now names the id), or a result whose
#     display name disagrees with the name of the definition that id points at
#     -> red. These are the two halves of one TRX disagreeing with each other:
#     swapping the ids of two definitions keeps the total, every class count,
#     the method set, every argument row and the outcome split identical, and so
#     does duplicating one id onto another definition and its result.
#   failed != 0 or notExecuted != 0 -> red
#
# CONFIG IS VALIDATED BEFORE IT IS TRUSTED
#   expected <= 0, empty file/suite, missing/empty classes object, missing/empty
#   methods object, a class listed in one table but not the other, a class with
#   no methods, a method case count below 1, a method table whose case counts do
#   not add up to the class count, a baseline still in the 2026-10-30
#   method-name list format, a baseline with no data table, an empty data table,
#   a data key that names no method in the methods table, a blank row, a row
#   list that disagrees with the method's case count, a baseline still in the
#   2026-10-31 digest format, a truncated table that is not an object, a
#   truncated key that names no method of the data table, a truncated count
#   below 1, a missing expected_suites, an expected_suites that disagrees with
#   the number of entries, two entries reading the same results file, a baseline
#   with no outcomes table, an empty outcomes table, an outcome name outside the
#   TRX vocabulary, an outcome count below 1, and an outcome table that does not
#   add up to the suite's expected total all throw. Otherwise the gate would be
#   vacuous and un-failable.
#
# ASCII ONLY on purpose: Windows PowerShell 5.1 decodes BOM-less UTF-8 as ANSI
# and corrupts non-ASCII text, which then breaks ConvertFrom-Json parsing.

[CmdletBinding()]
param(
  [string]$BaselinePath = "./tools/test-suite-baseline.json",
  [string]$ResultsDir = "./artifacts/test-results"
)

$ErrorActionPreference = "Stop"

function Test-MethodCaseMap($node) {
  if ($null -eq $node) { return $false }
  if ($node -is [string]) { return $false }
  if ($node -is [System.Array]) { return $false }
  if ($node -is [System.Collections.IList]) { return $false }
  return $true
}

# The runner marks a shortened argument value with three middle dots (U+00B7),
# written through [char] so this file stays pure ASCII. Kept identical to the
# constant in update-test-suite-baseline.ps1.
$TruncationMarker = [string][char]0x00B7 + [string][char]0x00B7 + [string][char]0x00B7

# TRX outcome names and the ResultSummary/Counters attribute each one is summarised
# in (the attribute is the same name with a lower-cased first letter). Kept
# identical to the vocabulary in update-test-suite-baseline.ps1.
$OutcomeVocabulary = @{}
$OutcomeByAttribute = @{}
foreach ($outcome in @('Passed', 'Failed', 'NotExecuted', 'Error', 'Timeout', 'Aborted', 'Inconclusive', 'PassedButRunAborted', 'NotRunnable', 'Disconnected', 'Warning', 'Completed', 'InProgress', 'Pending')) {
  $OutcomeVocabulary[$outcome] = $true
  $OutcomeByAttribute[($outcome.Substring(0, 1).ToLower() + $outcome.Substring(1))] = $outcome
}

# Sorted copy of one method's argument rows, duplicates kept. A row is the
# display-name suffix from the first "(", i.e. the argument text of one
# [Theory] row. Sorting with the ordinal comparer makes the list independent of
# row order and of the culture of the machine that computes it.
function Sort-Rows {
  param([string[]]$Rows)
  $sorted = New-Object System.Collections.Generic.List[string]
  foreach ($r in $Rows) { [void]$sorted.Add([string]$r) }
  $sorted.Sort([System.StringComparer]::Ordinal)
  return $sorted
}

# Multiset difference of two sorted row lists: baseline rows the run no longer
# reports, and rows the run reports that the baseline never had. Compared with
# String.CompareOrdinal, so neither culture nor a hashtable's case folding can
# make two different argument rows look equal.
function Get-RowDifference {
  param([string[]]$Baseline, [string[]]$Live)
  $missing = @()
  $extra = @()
  $i = 0
  $j = 0
  while ($i -lt $Baseline.Count -or $j -lt $Live.Count) {
    if ($i -ge $Baseline.Count) { $extra += $Live[$j]; $j++; continue }
    if ($j -ge $Live.Count) { $missing += $Baseline[$i]; $i++; continue }
    $cmp = [System.String]::CompareOrdinal($Baseline[$i], $Live[$j])
    if ($cmp -eq 0) { $i++; $j++ }
    elseif ($cmp -lt 0) { $missing += $Baseline[$i]; $i++ }
    else { $extra += $Live[$j]; $j++ }
  }
  return @{ missing = $missing; extra = $extra }
}

if (-not (Test-Path -LiteralPath $BaselinePath)) {
  throw "missing $BaselinePath - the suite baseline is required"
}
$baseline = Get-Content -LiteralPath $BaselinePath -Raw | ConvertFrom-Json
if (-not $baseline.suites -or @($baseline.suites).Count -eq 0) {
  throw "$BaselinePath lists no suites"
}

$suiteCount = @($baseline.suites).Count
if ($null -eq $baseline.expected_suites) {
  throw "$BaselinePath has no expected_suites - a deleted suite entry would stop being gated; refresh with windows/tools/update-test-suite-baseline.ps1"
}
$pinned = [string]$baseline.expected_suites
if ($pinned -notmatch '^[0-9]+$' -or [int]$pinned -lt 1) {
  throw "$BaselinePath : expected_suites is $pinned - must be a positive integer"
}
if ([int]$pinned -ne $suiteCount) {
  throw "$BaselinePath : expected_suites is $pinned but the table lists $suiteCount suites - a suite must not stop being gated without an explicit refresh"
}
$seenFiles = @{}

foreach ($e in $baseline.suites) {
  $declared = [int]$e.expected
  if ($declared -lt 1) {
    throw "$BaselinePath : expected for $($e.suite) is $declared - must be a positive integer, otherwise the gate cannot fail"
  }
  if ([string]::IsNullOrWhiteSpace($e.file)) { throw "$BaselinePath : an entry has no file" }
  if ([string]::IsNullOrWhiteSpace($e.suite)) { throw "$BaselinePath : an entry has no suite name" }
  $fileName = [string]$e.file
  if ($seenFiles.ContainsKey($fileName)) { throw "$BaselinePath : two entries read the same results file $fileName - one suite would be counted twice and another left unchecked" }
  $seenFiles[$fileName] = $true
  if ($null -eq $e.classes) {
    throw "$BaselinePath : entry for $($e.suite) has no classes table - the per-class check would be vacuous"
  }
  if ($null -eq $e.methods) {
    throw "$BaselinePath : entry for $($e.suite) has no methods table - the per-method check would be vacuous"
  }
  $classNames = @($e.classes.PSObject.Properties.Name)
  if ($classNames.Count -eq 0) {
    throw "$BaselinePath : entry for $($e.suite) has an empty classes table - the per-class check would be vacuous"
  }
  $methodClasses = @($e.methods.PSObject.Properties.Name)
  if ($methodClasses.Count -eq 0) {
    throw "$BaselinePath : entry for $($e.suite) has an empty methods table - the per-method check would be vacuous"
  }
  foreach ($name in $classNames) {
    if ([string]::IsNullOrWhiteSpace($name)) { throw "$BaselinePath : entry for $($e.suite) has a blank class name" }
    if ([int]$e.classes.$name -lt 1) { throw "$BaselinePath : entry for $($e.suite) declares class $name with a count below 1" }
    if (-not ($methodClasses -contains $name)) {
      throw "$BaselinePath : entry for $($e.suite) declares class $name in classes but not in methods - the per-method check would skip it"
    }
  }
  foreach ($name in $methodClasses) {
    if (-not ($classNames -contains $name)) {
      throw "$BaselinePath : entry for $($e.suite) declares class $name in methods but not in classes - the two tables must describe the same classes"
    }
    $inner = $e.methods.$name
    if (-not (Test-MethodCaseMap $inner)) {
      throw "$BaselinePath : entry for $($e.suite) lists method names for class $name without case counts - that is the 2026-10-30 format, which cannot see a data row moving between two theories of one class. Refresh it with windows/tools/update-test-suite-baseline.ps1."
    }
    $methodNames = @($inner.PSObject.Properties.Name)
    if ($methodNames.Count -lt 1) {
      throw "$BaselinePath : entry for $($e.suite) lists no methods for class $name - the per-method check would be vacuous for it"
    }
    $sum = 0
    foreach ($m in $methodNames) {
      if ([string]::IsNullOrWhiteSpace($m)) { throw "$BaselinePath : entry for $($e.suite) class $name has a blank method name" }
      $want = [int]$inner.$m
      if ($want -lt 1) {
        throw "$BaselinePath : entry for $($e.suite) declares $name.$m with $want case(s) - must be a positive integer, otherwise losing its rows cannot be seen"
      }
      $sum += $want
    }
    if ($sum -ne [int]$e.classes.$name) {
      throw "$BaselinePath : entry for $($e.suite) gives class $name $($e.classes.$name) case(s) in classes but $sum case(s) in methods - the two tables must agree"
    }
  }
  if (-not (Test-MethodCaseMap $e.data)) {
    throw "$BaselinePath : entry for $($e.suite) has no data table - the per-argument-row check would be vacuous. Refresh it with windows/tools/update-test-suite-baseline.ps1."
  }
  $dataProps = @($e.data.PSObject.Properties)
  if ($dataProps.Count -eq 0) {
    throw "$BaselinePath : entry for $($e.suite) has an empty data table - the per-argument-row check would be vacuous"
  }
  $dataKeys = @($dataProps | ForEach-Object { [string]$_.Name })
  $knownMethods = @{}
  foreach ($name in $methodClasses) {
    foreach ($m in @($e.methods.$name.PSObject.Properties.Name)) { $knownMethods["$name.$m"] = $true }
  }
  $knownDataKeys = @{}
  foreach ($k in $dataKeys) {
    $knownDataKeys[$k] = $true
    if ([string]::IsNullOrWhiteSpace($k)) { throw "$BaselinePath : entry for $($e.suite) has a blank data key" }
    if (-not $knownMethods.ContainsKey($k)) {
      throw "$BaselinePath : entry for $($e.suite) pins argument rows for $k, which is not a method of the methods table"
    }
    $rows = $e.data.$k
    if ($rows -is [string]) {
      throw "$BaselinePath : entry for $($e.suite) pins argument rows for $k as a digest - that is the 2026-10-31 format, which cannot see a data row added in the same edit as a replaced value. Refresh it with windows/tools/update-test-suite-baseline.ps1."
    }
    if (-not ($rows -is [System.Array])) {
      throw "$BaselinePath : entry for $($e.suite) pins argument rows for $k as something other than a list"
    }
    if (@($rows).Count -lt 1) {
      throw "$BaselinePath : entry for $($e.suite) pins no argument rows for $k - the check would be vacuous for it"
    }
    foreach ($r in @($rows)) {
      if ($null -eq $r -or [string]::IsNullOrWhiteSpace([string]$r)) {
        throw "$BaselinePath : entry for $($e.suite) pins a blank argument row for $k"
      }
    }
    $dot = $k.LastIndexOf(".")
    $mcls = $k.Substring(0, $dot)
    $mname = $k.Substring($dot + 1)
    $cases = [int]$e.methods.$mcls.$mname
    if (@($rows).Count -ne $cases) {
      throw "$BaselinePath : entry for $($e.suite) pins $(@($rows).Count) argument row(s) for $k but the methods table gives it $cases case(s) - the two layers must agree"
    }
  }
  if ($null -ne $e.truncated) {
    if ($e.truncated -is [string] -or $e.truncated -is [System.Array]) {
      throw "$BaselinePath : entry for $($e.suite) has a truncated table that is not an object"
    }
    foreach ($tp in @($e.truncated.PSObject.Properties)) {
      $tk = [string]$tp.Name
      if ([string]::IsNullOrWhiteSpace($tk)) { throw "$BaselinePath : entry for $($e.suite) has a blank truncated key" }
      if (-not $knownMethods.ContainsKey($tk)) {
        throw "$BaselinePath : entry for $($e.suite) records truncated argument rows for $tk, which is not a method of the methods table"
      }
      if (-not $knownDataKeys.ContainsKey($tk)) {
        throw "$BaselinePath : entry for $($e.suite) records truncated argument rows for $tk, which the data table does not pin"
      }
      $tn = 0
      if (-not [int]::TryParse([string]$tp.Value, [ref]$tn)) {
        throw "$BaselinePath : entry for $($e.suite) records a non-integer truncated count for $tk"
      }
      if ($tn -lt 1) {
        throw "$BaselinePath : entry for $($e.suite) records $tn truncated argument row(s) for $tk - record only methods with at least one"
      }
    }
  }
  if ($null -eq $e.outcomes) {
    throw "$BaselinePath : entry for $($e.suite) has no outcomes table - the per-outcome check would be vacuous. Refresh it with windows/tools/update-test-suite-baseline.ps1"
  }
  $outcomeProps = @($e.outcomes.PSObject.Properties)
  if ($outcomeProps.Count -eq 0) {
    throw "$BaselinePath : entry for $($e.suite) has an empty outcomes table - the per-outcome check would be vacuous"
  }
  $outcomeSum = 0
  foreach ($op in $outcomeProps) {
    $ok = [string]$op.Name
    if ([string]::IsNullOrWhiteSpace($ok)) { throw "$BaselinePath : entry for $($e.suite) has a blank outcome name" }
    if (-not $OutcomeVocabulary.ContainsKey($ok)) {
      throw "$BaselinePath : entry for $($e.suite) records outcome $ok, which is not an outcome name this gate knows"
    }
    $on = 0
    if (-not [int]::TryParse([string]$op.Value, [ref]$on)) {
      throw "$BaselinePath : entry for $($e.suite) records a non-integer outcome count for $ok"
    }
    if ($on -lt 1) {
      throw "$BaselinePath : entry for $($e.suite) records $on row(s) for outcome $ok - record only outcomes with at least one row"
    }
    $outcomeSum += $on
  }
  if ($outcomeSum -ne [int]$e.expected) {
    throw "$BaselinePath : entry for $($e.suite) outcome table adds up to $outcomeSum but expected is $($e.expected) - every result row carries exactly one outcome"
  }
}

$all = 0
$stale = @()
$grown = @()
$newMethods = @()
$newData = @()
$truncWarn = @()
$outcomeGrew = @()
$suiteIdentity = @()
$provenanceWarn = @()
$auditFiles = @()
foreach ($e in $baseline.suites) {
  $path = Join-Path -Path $ResultsDir -ChildPath $e.file
  if (-not (Test-Path -LiteralPath $path)) {
    throw "no test results for $($e.suite): $($e.file) was not produced"
  }
  $xml = [xml](Get-Content -LiteralPath $path -Raw)
  if (-not $xml.TestRun.ResultSummary.Counters) {
    throw "$($e.file) has no ResultSummary/Counters - the test run may not have completed"
  }
  $total = [int]$xml.TestRun.ResultSummary.Counters.total
  $failed = [int]$xml.TestRun.ResultSummary.Counters.failed
  $skipped = [int]$xml.TestRun.ResultSummary.Counters.notExecuted
  $auditFiles += [string]$e.file

  # Identity from the artifact: the TestMethod elements record the assembly that
  # produced these results (codeBase; older writers only fill storage). A single
  # recorded assembly that does not match the declared suite name means this
  # entry is wired to another suite's results.
  $assemblies = New-Object System.Collections.Generic.List[string]
  foreach ($ut in @($xml.TestRun.TestDefinitions.UnitTest)) {
    if ($null -eq $ut) { continue }
    $cb = $null
    if ($ut.TestMethod) { $cb = [string]$ut.TestMethod.codeBase }
    if ([string]::IsNullOrWhiteSpace($cb)) { $cb = [string]$ut.storage }
    if ([string]::IsNullOrWhiteSpace($cb)) { continue }
    $leaf = $cb.Split([char]92, [char]47)
    $leafName = ""
    foreach ($part in $leaf) { if (-not [string]::IsNullOrWhiteSpace($part)) { $leafName = $part } }
    if ([string]::IsNullOrWhiteSpace($leafName)) { continue }
    if ($leafName.EndsWith(".dll", [System.StringComparison]::OrdinalIgnoreCase)) { $leafName = $leafName.Substring(0, $leafName.Length - 4) }
    if (-not $assemblies.Contains($leafName)) { [void]$assemblies.Add($leafName) }
  }
  if ($assemblies.Count -eq 1) {
    if (-not $assemblies[0].Equals([string]$e.suite, [System.StringComparison]::OrdinalIgnoreCase)) {
      $suiteIdentity += "$($e.suite) ($($e.file) was produced by $($assemblies[0]))"
    }
  } elseif ($assemblies.Count -eq 0) {
    $provenanceWarn += "$($e.suite) ($($e.file) records no test assembly)"
  } else {
    $provenanceWarn += "$($e.suite) ($($e.file) mixes $($assemblies.Count) test assemblies)"
  }

  # Class membership comes from TestDefinitions (UnitTest.id -> TestMethod.className),
  # never from the result name: xUnit appends the data arguments of a [Theory] to the
  # result name, and argument text may itself contain dots (dates, decimal numbers).
  # The same definitions carry the method name; the arguments are stripped so that
  # every row of a [Theory] maps to the method it exercises, and the rows are counted
  # per method so that a row moving between two theories of one class is visible.
  $idToClass = @{}
  $defIdCounts = @{}
  $defIdNames = @{}
  $blankDefIds = 0
  $actualMethods = @{}
  $actualData = @{}
  $actualTruncated = @{}
  $actualMethodCases = 0
  $defsWithoutClass = 0
  foreach ($ut in @($xml.TestRun.TestDefinitions.UnitTest)) {
    if ($null -eq $ut) { continue }
    $cls = $null
    $raw = $null
    if ($ut.TestMethod) {
      $cls = [string]$ut.TestMethod.className
      $raw = [string]$ut.TestMethod.name
    }
    if ([string]::IsNullOrWhiteSpace($cls)) { $defsWithoutClass++; continue }
    if ([string]::IsNullOrWhiteSpace($raw)) { $raw = [string]$ut.name }
    if ([string]::IsNullOrWhiteSpace($raw)) {
      throw "$($e.suite): a test definition in $($e.file) has no method name - the per-method check cannot be trusted"
    }
    $base = $raw
    $paren = $raw.IndexOf('(')
    if ($paren -ge 0) { $base = $raw.Substring(0, $paren) }
    # The argument text lives in the definition display name, not in the method
    # name: the method name is the bare identifier, the display name is
    # "Class.Method(<arguments>)".
    $suffix = ""
    $display = [string]$ut.name
    if (-not [string]::IsNullOrWhiteSpace($display)) {
      $dparen = $display.IndexOf('(')
      if ($dparen -ge 0) { $suffix = $display.Substring($dparen) }
    }
    if ($suffix.Length -gt 0) {
      if (-not $actualData.ContainsKey($cls)) { $actualData[$cls] = @{} }
      if ($actualData[$cls].ContainsKey($base)) {
        $actualData[$cls][$base] += ,$suffix
      } else {
        $actualData[$cls][$base] = @($suffix)
      }
      if ($suffix.Contains($TruncationMarker)) {
        $tkey = "$cls.$base"
        if ($actualTruncated.ContainsKey($tkey)) { $actualTruncated[$tkey] = $actualTruncated[$tkey] + 1 } else { $actualTruncated[$tkey] = 1 }
      }
    }
    if (-not $actualMethods.ContainsKey($cls)) { $actualMethods[$cls] = @{} }
    if ($actualMethods[$cls].ContainsKey($base)) {
      $actualMethods[$cls][$base] = $actualMethods[$cls][$base] + 1
    } else {
      $actualMethods[$cls][$base] = 1
    }
    $actualMethodCases++
    $did = [string]$ut.id
    if ([string]::IsNullOrWhiteSpace($did)) {
      $blankDefIds++
    } elseif ($defIdCounts.ContainsKey($did)) {
      $defIdCounts[$did] = $defIdCounts[$did] + 1
    } else {
      $defIdCounts[$did] = 1
      $defIdNames[$did] = $display
    }
    $idToClass[$did] = $cls
  }
  if ($defsWithoutClass -gt 0) {
    throw "$($e.suite): $defsWithoutClass test definition(s) in $($e.file) have no TestMethod.className - the per-method check cannot be trusted"
  }
  $actualDataRows = @{}
  $actualRowTotal = 0
  foreach ($dcls in @($actualData.Keys)) {
    foreach ($dm in @($actualData[$dcls].Keys)) {
      $rows = @(Sort-Rows $actualData[$dcls][$dm])
      $actualDataRows["$dcls.$dm"] = $rows
      $actualRowTotal += $rows.Count
    }
  }

  $actual = @{}
  $actualOutcomes = @{}
  $resIdCounts = @{}
  $namedExact = 0
  $namedPrefix = 0
  $nameMismatch = @()
  $namesUnprovable = 0
  $unmapped = 0
  foreach ($res in @($xml.TestRun.Results.UnitTestResult)) {
    if ($null -eq $res) { continue }
    $rid = [string]$res.testId
    if ($resIdCounts.ContainsKey($rid)) { $resIdCounts[$rid] = $resIdCounts[$rid] + 1 } else { $resIdCounts[$rid] = 1 }
    $cls = $null
    if ($idToClass.ContainsKey($rid)) { $cls = $idToClass[$rid] }
    if ([string]::IsNullOrWhiteSpace($cls)) { $unmapped++; continue }
    # The id is only a coordinate: check that the row on the other side of it is
    # the same row. xUnit writes the same display name into the definition and
    # into the result, so a name that disagrees means the two halves were not
    # produced together. Rows the runner truncated are compared on the text
    # before the marker, because the tail is not recoverable from a TRX.
    $dname = [string]$defIdNames[$rid]
    if ([string]::IsNullOrWhiteSpace($dname)) {
      $namesUnprovable++
    } else {
      $rname = [string]$res.testName
      if ($dname -eq $rname) {
        $namedExact++
      } elseif ($dname.Contains($TruncationMarker) -or $rname.Contains($TruncationMarker)) {
        $dpre = $dname
        if ($dname.Contains($TruncationMarker)) { $dpre = $dname.Substring(0, $dname.IndexOf($TruncationMarker)) }
        $rpre = $rname
        if ($rname.Contains($TruncationMarker)) { $rpre = $rname.Substring(0, $rname.IndexOf($TruncationMarker)) }
        if ($dpre.StartsWith($rpre) -or $rpre.StartsWith($dpre)) {
          $namedPrefix++
        } elseif ($nameMismatch.Count -lt 12) {
          $nameMismatch += "$cls (id ${rid}: definition $dpre vs result $rpre)"
        }
      } elseif ($nameMismatch.Count -lt 12) {
        $nameMismatch += "$cls (id ${rid}: definition $dname vs result $rname)"
      }
    }
    if ($actual.ContainsKey($cls)) { $actual[$cls] = $actual[$cls] + 1 } else { $actual[$cls] = 1 }
    $oc = [string]$res.outcome
    if ([string]::IsNullOrWhiteSpace($oc)) { $oc = "(missing)" }
    if ($actualOutcomes.ContainsKey($oc)) { $actualOutcomes[$oc] = $actualOutcomes[$oc] + 1 } else { $actualOutcomes[$oc] = 1 }
  }
  if ($unmapped -gt 0) {
    $orphanResIds = @($resIdCounts.Keys | Where-Object { -not $defIdCounts.ContainsKey($_) } | Sort-Object)
    $shownOrphan = @($orphanResIds)
    if ($shownOrphan.Count -gt 12) { $shownOrphan = $shownOrphan[0..11] + "... ($($orphanResIds.Count) total)" }
    throw "$($e.suite): $unmapped result(s) in $($e.file) have no TestMethod.className (testId $($shownOrphan -join ', ')) - the per-class check cannot be trusted"
  }

  # The two halves of one TRX must describe the same rows. The total, the class
  # counts, the method set, the argument rows and the outcome buckets all come
  # from one half or the other, so a file whose halves disagree can keep every
  # one of them identical: swapping the ids of two definitions re-points each
  # result row at the other definition, and duplicating one id onto another
  # definition (with its result) does the same while every count stays put.
  $dupDefIds = @($defIdCounts.Keys | Where-Object { [int]$defIdCounts[$_] -gt 1 } | Sort-Object)
  $dupResIds = @($resIdCounts.Keys | Where-Object { [int]$resIdCounts[$_] -gt 1 } | Sort-Object)
  $orphanDefIds = @($defIdCounts.Keys | Where-Object { -not $resIdCounts.ContainsKey($_) } | Sort-Object)
  $pairedIds = @($defIdCounts.Keys | Where-Object { $resIdCounts.ContainsKey($_) }).Count
  $namedRows = $namedExact + $namedPrefix
  $identityLost = @()
  if ($blankDefIds -gt 0) { $identityLost += "$blankDefIds test definition(s) have no id" }
  foreach ($id in @($dupDefIds)) { if ($identityLost.Count -lt 12) { $identityLost += "definition id $id appears $($defIdCounts[$id]) times" } }
  foreach ($id in @($dupResIds)) { if ($identityLost.Count -lt 12) { $identityLost += "result testId $id appears $($resIdCounts[$id]) times" } }
  foreach ($id in @($orphanDefIds)) { if ($identityLost.Count -lt 12) { $identityLost += "definition id $id has no result row" } }
  foreach ($m in @($nameMismatch)) { if ($identityLost.Count -lt 12) { $identityLost += $m } }
  if ($identityLost.Count -gt 0) {
    throw "$($e.suite): the result rows do not line up with their test definitions while the total was $total - equal totals, equal per-class counts, an unchanged method list, unchanged argument rows and an unchanged outcome split do not prove that each row is the row it claims to be. mismatched: $($identityLost -join ', '). A TRX whose two halves disagree was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($namesUnprovable -gt 0) {
    Write-Host "::warning::$($e.suite): $namesUnprovable row(s) cannot be name-checked - the definition carries no display name"
  }

  # The summary is not trusted on its own: every result row carries exactly one
  # outcome, so the rows must reproduce every Counters bucket. A file whose rows
  # disagree with its own summary (hand-merged, patched, truncated) would
  # otherwise pass on the summary numbers alone.
  $counterOutcomes = @{}
  foreach ($cp in @($xml.TestRun.ResultSummary.Counters.PSObject.Properties)) {
    $cn = [string]$cp.Name
    if ($cn -eq 'total' -or $cn -eq 'executed') { continue }
    $cv = 0
    if (-not [int]::TryParse([string]$cp.Value, [ref]$cv)) { continue }
    if ($cv -gt 0) { $counterOutcomes[$cn] = $cv }
  }
  $observedOutcomes = @{}
  $rowTotal = 0
  foreach ($oc in @($actualOutcomes.Keys)) {
    $name = [string]$oc
    $attribute = $name.Substring(0, 1).ToLower() + $name.Substring(1)
    $observedOutcomes[$attribute] = [int]$actualOutcomes[$oc]
    $rowTotal += [int]$actualOutcomes[$oc]
  }
  $counterMismatch = @()
  if ($rowTotal -ne $total) { $counterMismatch += "rows=$rowTotal vs Counters.total=$total" }
  foreach ($an in @($observedOutcomes.Keys | Sort-Object)) {
    $cv = 0
    if ($counterOutcomes.ContainsKey($an)) { $cv = [int]$counterOutcomes[$an] }
    if ($cv -ne [int]$observedOutcomes[$an]) { $counterMismatch += "$an rows=$($observedOutcomes[$an]) vs Counters.$an=$cv" }
  }
  foreach ($an in @($counterOutcomes.Keys | Sort-Object)) {
    if ($observedOutcomes.ContainsKey($an)) { continue }
    $counterMismatch += "$an rows=0 vs Counters.$an=$($counterOutcomes[$an])"
  }
  if ($counterMismatch.Count -gt 0) {
    throw "$($e.suite): $($e.file) disagrees with its own ResultSummary/Counters - the per-outcome rows do not reproduce the summary (total $total): $($counterMismatch -join '; '). The summary alone cannot be trusted; if the artifact came from another runner, teach the gate that vocabulary before trusting it."
  }

  $actualMethodCount = 0
  foreach ($k in @($actualMethods.Keys)) { $actualMethodCount += @($actualMethods[$k].Keys).Count }
  $baselineMethodCount = 0
  $baselineCaseCount = 0
  foreach ($k in @($e.methods.PSObject.Properties.Name)) {
    foreach ($m in @($e.methods.$k.PSObject.Properties.Name)) {
      $baselineMethodCount++
      $baselineCaseCount += [int]$e.methods.$k.$m
    }
  }

  "$($e.suite): total=$total baseline=$($e.expected) failed=$failed notExecuted=$skipped"
  "$($e.suite): classes=$(($actual.Keys | Sort-Object).Count) baseline=class table $(@($e.classes.PSObject.Properties.Name).Count) entries"
  "$($e.suite): methods=$actualMethodCount baseline=method table $baselineMethodCount methods"
  $baselineDataCount = @($e.data.PSObject.Properties.Name).Count
  $baselineRowTotal = 0
  foreach ($dk in @($e.data.PSObject.Properties.Name)) { $baselineRowTotal += @($e.data.$dk).Count }
  "$($e.suite): methodCases=$actualMethodCases baseline=method case table $baselineCaseCount cases"
  "$($e.suite): methodData=$($actualDataRows.Count) baseline=method data table $baselineDataCount row sets"
  "$($e.suite): argumentRows=$actualRowTotal baseline=argument row table $baselineRowTotal rows"
  $actualTruncTotal = 0
  foreach ($tkey in @($actualTruncated.Keys)) { $actualTruncTotal += [int]$actualTruncated[$tkey] }
  if ($null -eq $e.truncated) {
    "$($e.suite): argumentTruncated=$actualTruncTotal baseline=argument truncated table not recorded"
  } else {
    $baselineTruncTotal = 0
    foreach ($tkey in @($e.truncated.PSObject.Properties.Name)) { $baselineTruncTotal += [int]$e.truncated.$tkey }
    "$($e.suite): argumentTruncated=$actualTruncTotal baseline=argument truncated table $baselineTruncTotal rows"
  }
  "$($e.suite): outcomes=$($actualOutcomes.Count) baseline=outcome table $(@($e.outcomes.PSObject.Properties.Name).Count) outcomes"
  "$($e.suite): identities=$pairedIds named=$namedRows unique test ids shared by both halves"

  if ($failed -ne 0) { throw "$($e.suite): $failed test(s) failed" }
  if ($skipped -ne 0) { throw "$($e.suite): $skipped test(s) were not executed" }
  if ($total -lt [int]$e.expected) {
    throw "$($e.suite) ran $total tests but the baseline says $($e.expected) - cases were dropped or skipped. If the removal was intentional, lower the baseline in windows/tools/test-suite-baseline.json in the same commit."
  }
  if ($total -gt [int]$e.expected) { $stale += "$($e.suite): $total > $($e.expected)" }

  # Per-class check: a class that existed must still exist with at least its count.
  $missing = @()
  $short = @()
  foreach ($name in @($e.classes.PSObject.Properties.Name)) {
    $want = [int]$e.classes.$name
    if (-not $actual.ContainsKey($name)) {
      $missing += "$name (baseline $want, now 0)"
    } elseif ($actual[$name] -lt $want) {
      $short += "$name (baseline $want, now $($actual[$name]))"
    }
  }
  if ($missing.Count -gt 0 -or $short.Count -gt 0) {
    $detail = @()
    if ($missing.Count -gt 0) { $detail += "gone: " + ($missing -join ", ") }
    if ($short.Count -gt 0) { $detail += "shrunk: " + ($short -join ", ") }
    # This is the substitution hole the total-only gate could not see: equal totals,
    # different cases. Say so in the failure text so the reviewer knows what to look for.
    throw "$($e.suite): test classes lost cases while the total was $total (baseline $($e.expected)) - equal totals do not prove the same cases ran. $($detail -join '; '). If the removal was intentional, refresh the baseline in windows/tools/test-suite-baseline.json in the same commit."
  }

  # Per-method check: a method that existed must still be reported, with at least
  # its baseline case count. This is the substitution hole the per-class check
  # could not see: deleting one method and adding another to the same class keeps
  # every count identical, and moving one [Theory] row between methods of a class
  # keeps the method list identical too.
  $lostMethods = @()
  foreach ($name in @($e.methods.PSObject.Properties.Name)) {
    if (-not $actualMethods.ContainsKey($name)) { continue }
    $inner = $e.methods.$name
    foreach ($m in @($inner.PSObject.Properties.Name)) {
      $want = [int]$inner.$m
      if (-not $actualMethods[$name].ContainsKey($m)) {
        $lostMethods += "$name.$m (gone)"
      } elseif ([int]$actualMethods[$name][$m] -lt $want) {
        $lostMethods += "$name.$m (baseline $want cases, now $([int]$actualMethods[$name][$m]))"
      }
    }
  }
  if ($lostMethods.Count -gt 0) {
    $shown = $lostMethods
    if ($shown.Count -gt 12) { $shown = $shown[0..11] + "... ($($lostMethods.Count - 12) more)" }
    throw "$($e.suite): test methods lost cases while the total was $total (baseline $($e.expected)) - equal totals, equal per-class counts and an unchanged method list do not prove the same cases ran. gone or shrunk: $($shown -join ', '). If the removal was intentional, refresh the baseline in windows/tools/test-suite-baseline.json in the same commit."
  }

  # Per-argument-row check: every baseline argument row of a method must still be
  # reported. This is the substitution the per-method counts cannot see:
  # replacing one row's values keeps the total, every class count, the method set
  # and every per-method case count identical. Row lists rather than a digest,
  # because a digest cannot separate "a row was added" (the documented growth
  # warning) from "a row was added and another was replaced" (a loss).
  $dataLost = @()
  foreach ($k in @($e.data.PSObject.Properties.Name)) {
    $wantRows = @($e.data.$k)
    if (-not $actualDataRows.ContainsKey($k)) {
      $dataLost += "$k (argument rows gone)"
      continue
    }
    $diff = Get-RowDifference -Baseline $wantRows -Live $actualDataRows[$k]
    if (@($diff.missing).Count -eq 0 -and @($diff.extra).Count -eq 0) { continue }
    if (@($diff.missing).Count -eq 0) {
      $newData += "$($e.suite):$k (argument rows grew: $($wantRows.Count) -> $(@($actualDataRows[$k]).Count) rows)"
      continue
    }
    $miss = @($diff.missing)
    $shownMiss = @($miss)
    if ($shownMiss.Count -gt 6) { $shownMiss = $shownMiss[0..5] + "... ($($miss.Count) total)" }
    $dataLost += "$k (baseline row(s) no longer reported: $($shownMiss -join ' | '))"
    $ext = @($diff.extra)
    if ($ext.Count -gt 0) {
      $shownExt = @($ext)
      if ($shownExt.Count -gt 3) { $shownExt = $shownExt[0..2] + "... ($($ext.Count) total)" }
      $dataLost += "$k (new row(s) in the same method: $($shownExt -join ' | '))"
    }
  }
  if ($dataLost.Count -gt 0) {
    $shown = $dataLost
    if ($shown.Count -gt 12) { $shown = $shown[0..11] + "... ($($dataLost.Count - 12) more)" }
    throw "$($e.suite): test method argument rows changed while the total was $total (baseline $($e.expected)) - equal totals, equal per-class counts, an unchanged method list and unchanged per-method case counts do not prove the same argument values ran, and a row added in the same edit as a replaced row does not excuse it. changed: $($shown -join ', '). If the change was intentional, refresh the baseline in windows/tools/test-suite-baseline.json in the same commit."
  }

  # Truncation accounting. The runner shortens a long argument value and marks
  # the cut, so a difference past the marker leaves the visible text - and
  # therefore the row layer above - untouched. A TRX cannot recover the tail, so
  # this layer only makes the exposure visible: it warns, it never goes red.
  if ($null -eq $e.truncated) {
    $truncWarn += "$($e.suite): the argument truncated table is not recorded (this run has $actualTruncTotal truncated row(s)) - refresh windows/tools/test-suite-baseline.json to make the exposure visible"
  } else {
    foreach ($tk in @($e.truncated.PSObject.Properties.Name | Sort-Object)) {
      $before = [int]$e.truncated.$tk
      $now = 0
      if ($actualTruncated.ContainsKey($tk)) { $now = [int]$actualTruncated[$tk] }
      if ($now -ne $before) { $truncWarn += "$($e.suite):$tk (baseline $before, now $now)" }
    }
    foreach ($tk in @($actualTruncated.Keys | Sort-Object)) {
      if (-not (@($e.truncated.PSObject.Properties.Name) -contains $tk)) {
        $truncWarn += "$($e.suite):$tk (new, $($actualTruncated[$tk]) truncated row(s))"
      }
    }
  }

  # Outcome kinds. Correct totals and correct per-row counts do not say the same
  # rows PASSED: a row moving from Passed to Aborted (or to any other kind) keeps
  # the total, every class count, the method set, every per-method case count and
  # every argument row identical. A baseline outcome that fell, or an outcome kind
  # the baseline did not have, is red; growth is the documented warning path.
  $outcomeLost = @()
  foreach ($ok in @($e.outcomes.PSObject.Properties.Name | Sort-Object)) {
    $before = [int]$e.outcomes.$ok
    $attribute = $ok.Substring(0, 1).ToLower() + $ok.Substring(1)
    $now = 0
    if ($observedOutcomes.ContainsKey($attribute)) { $now = [int]$observedOutcomes[$attribute] }
    if ($now -lt $before) { $outcomeLost += "$ok ($before -> $now rows)" }
  }
  foreach ($an in @($observedOutcomes.Keys | Sort-Object)) {
    $kind = $OutcomeByAttribute[$an]
    if ($null -eq $kind) { $kind = [string]$an + " (outside the TRX vocabulary)" }
    if (-not (@($e.outcomes.PSObject.Properties.Name) -contains $kind)) {
      $outcomeLost += "$kind (baseline 0, now $($observedOutcomes[$an]) row(s))"
    }
  }
  if ($outcomeLost.Count -gt 0) {
    throw "$($e.suite): test outcome kinds changed while the total was $total (baseline $($e.expected)) - equal totals, equal per-class counts, an unchanged method list, unchanged per-method case counts and unchanged argument rows do not prove the same rows passed. changed: $($outcomeLost -join ', '). If the change was intentional, refresh the baseline in windows/tools/test-suite-baseline.json in the same commit."
  }
  foreach ($an in @($observedOutcomes.Keys | Sort-Object)) {
    $kind = $OutcomeByAttribute[$an]
    if ($null -eq $kind) { continue }
    if (@($e.outcomes.PSObject.Properties.Name) -contains $kind) {
      if ([int]$observedOutcomes[$an] -gt [int]$e.outcomes.$kind) {
        $outcomeGrew += "$($e.suite):$kind (+$([int]$observedOutcomes[$an] - [int]$e.outcomes.$kind) rows)"
      }
    }
  }

  foreach ($name in @($actual.Keys | Sort-Object)) {
    if (-not (@($e.classes.PSObject.Properties.Name) -contains $name)) {
      $grown += "$($e.suite):$name (+$($actual[$name]), new class)"
    } elseif ($actual[$name] -gt [int]$e.classes.$name) {
      $grown += "$($e.suite):$name (+$($actual[$name] - [int]$e.classes.$name))"
    }
  }
  foreach ($name in @($actualMethods.Keys | Sort-Object)) {
    if (-not (@($e.methods.PSObject.Properties.Name) -contains $name)) {
      $newMethods += "$($e.suite):$name (new class, $(@($actualMethods[$name].Keys).Count) methods)"
    } else {
      $inner = $e.methods.$name
      foreach ($m in @($actualMethods[$name].Keys | Sort-Object)) {
        if (-not (@($inner.PSObject.Properties.Name) -contains $m)) {
          $newMethods += "$($e.suite):$name.$m (+$([int]$actualMethods[$name][$m]) cases)"
        } elseif ([int]$actualMethods[$name][$m] -gt [int]$inner.$m) {
          $newMethods += "$($e.suite):$name.$m (+$([int]$actualMethods[$name][$m] - [int]$inner.$m) cases)"
        }
      }
    }
  }
  foreach ($k in @($actualDataRows.Keys | Sort-Object)) {
    if (-not (@($e.data.PSObject.Properties.Name) -contains $k)) {
      $newData += "$($e.suite):$k (new argument rows)"
    }
  }
  if ($actualMethodCases -ne $total) {
    Write-Host "::warning::$($e.suite): $actualMethodCases test definition(s) against $total result(s) - the per-method case counts and the per-class counts disagree; check how the TRX was produced"
  }
  $all += $total
}

if ($suiteIdentity.Count -gt 0) {
  throw "$BaselinePath : the declared suite name does not match the assembly that produced its results: $($suiteIdentity -join '; ')"
}
$producedResults = @()
if (Test-Path -LiteralPath $ResultsDir) {
  $producedResults = @(Get-ChildItem -LiteralPath $ResultsDir -File -Filter *.trx | ForEach-Object { [string]$_.Name } | Sort-Object)
}
$unlistedResults = @($producedResults | Where-Object { -not ($auditFiles -contains $_) })
"suites=$($producedResults.Count) baseline=suite table $($auditFiles.Count) suites"
if ($unlistedResults.Count -gt 0) {
  $shownUnlisted = $unlistedResults
  if ($shownUnlisted.Count -gt 12) { $shownUnlisted = $shownUnlisted[0..11] + "... ($($unlistedResults.Count - 12) more)" }
  Write-Host "::warning::test results that the baseline does not list - their cases are neither counted nor gated (refresh windows/tools/test-suite-baseline.json with windows/tools/update-test-suite-baseline.ps1): $($shownUnlisted -join '; ')"
}
if ($provenanceWarn.Count -gt 0) {
  $shownProv = $provenanceWarn
  if ($shownProv.Count -gt 12) { $shownProv = $shownProv[0..11] + "... ($($provenanceWarn.Count - 12) more)" }
  Write-Host "::warning::the declared suite name cannot be proven against the artifact - the results file does not name exactly one test assembly: $($shownProv -join '; ')"
}
if ($stale.Count -gt 0) {
  Write-Host "::warning::suite grew beyond the baseline - refresh windows/tools/test-suite-baseline.json: $($stale -join '; ')"
}
if ($grown.Count -gt 0) {
  $shown = $grown
  if ($shown.Count -gt 12) {
    $shown = $shown[0..11] + "... ($($grown.Count - 12) more)"
  }
  Write-Host "::warning::new or larger test classes - refresh windows/tools/test-suite-baseline.json (see windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
if ($newMethods.Count -gt 0) {
  $shown = $newMethods
  if ($shown.Count -gt 12) {
    $shown = $shown[0..11] + "... ($($newMethods.Count - 12) more)"
  }
  Write-Host "::warning::new test methods - refresh windows/tools/test-suite-baseline.json (see windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
if ($newData.Count -gt 0) {
  $shown = $newData
  if ($shown.Count -gt 12) {
    $shown = $shown[0..11] + "... ($($newData.Count - 12) more)"
  }
  Write-Host "::warning::new test method argument rows - refresh windows/tools/test-suite-baseline.json (see windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
if ($truncWarn.Count -gt 0) {
  $shown = $truncWarn
  if ($shown.Count -gt 12) {
    $shown = $shown[0..11] + "... ($($truncWarn.Count - 12) more)"
  }
  Write-Host "::warning::argument rows the runner truncated changed - the text past the marker is not pinned, so a difference there cannot be seen (refresh the truncated table with windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
if ($outcomeGrew.Count -gt 0) {
  $shown = $outcomeGrew
  if ($shown.Count -gt 12) { $shown = $shown[0..11] + "... ($($outcomeGrew.Count - 12) more)" }
  Write-Host "::warning::new outcome rows - refresh windows/tools/test-suite-baseline.json (see windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
"combined tests=$all"

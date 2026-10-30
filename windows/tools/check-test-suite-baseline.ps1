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
#   text in the display name, so swapping the values of two rows (or replacing
#   one row's values) keeps every count identical while changing what the test
#   actually exercises. Each entry therefore also pins one digest per method
#   that HAS argument text ("data": { "Class.Method": "<16 hex>" }), computed
#   over the sorted list of every row's argument suffix with duplicates included,
#   and a method that carried argument text must still produce the same digest.
#   What that still cannot see: argument text the test framework already
#   truncated (xUnit shortens long arguments), where a difference past the
#   truncation point yields the same display text.
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
#   a method's argument-text digest changed, or its argument text went away
#     -> red (the rows that ran are not the rows the baseline was reviewed with)
#   a method reporting argument text the baseline did not have -> warning
#   failed != 0 or notExecuted != 0 -> red
#
# CONFIG IS VALIDATED BEFORE IT IS TRUSTED
#   expected <= 0, empty file/suite, missing/empty classes object, missing/empty
#   methods object, a class listed in one table but not the other, a class with
#   no methods, a method case count below 1, a method table whose case counts do
#   not add up to the class count, a baseline still in the 2026-10-30
#   method-name list format, a baseline with no data table, a data key that
#   names no method in the methods table, and a digest that is not 16 lowercase
#   hex digits all throw. Otherwise the gate would be vacuous and un-failable.
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

# Digest of the argument text of one method's rows. Every row of a [Theory] is a
# separate test definition whose display name is "Class.Method(<arguments>)", so
# the arguments are the suffix from the first "(". They are sorted with the
# ordinal comparer and joined with LF before hashing, which makes the digest
# independent of row order and of the culture of the machine that computes it.
# Duplicates are kept: a duplicated row swapped for a deleted one would
# otherwise leave the digest unchanged.
function Get-DataSignature {
  param([string[]]$Rows)
  $sorted = New-Object System.Collections.Generic.List[string]
  foreach ($r in $Rows) { [void]$sorted.Add([string]$r) }
  $sorted.Sort([System.StringComparer]::Ordinal)
  $joined = [string]::Join([string][char]10, $sorted.ToArray())
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($joined)
    $hash = $sha.ComputeHash($bytes)
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt 8; $i++) { [void]$sb.Append($hash[$i].ToString("x2")) }
    return $sb.ToString()
  } finally {
    $sha.Dispose()
  }
}

if (-not (Test-Path -LiteralPath $BaselinePath)) {
  throw "missing $BaselinePath - the suite baseline is required"
}
$baseline = Get-Content -LiteralPath $BaselinePath -Raw | ConvertFrom-Json
if (-not $baseline.suites -or @($baseline.suites).Count -eq 0) {
  throw "$BaselinePath lists no suites"
}

foreach ($e in $baseline.suites) {
  $declared = [int]$e.expected
  if ($declared -lt 1) {
    throw "$BaselinePath : expected for $($e.suite) is $declared - must be a positive integer, otherwise the gate cannot fail"
  }
  if ([string]::IsNullOrWhiteSpace($e.file)) { throw "$BaselinePath : an entry has no file" }
  if ([string]::IsNullOrWhiteSpace($e.suite)) { throw "$BaselinePath : an entry has no suite name" }
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
  foreach ($k in $dataKeys) {
    if ([string]::IsNullOrWhiteSpace($k)) { throw "$BaselinePath : entry for $($e.suite) has a blank data key" }
    if (-not $knownMethods.ContainsKey($k)) {
      throw "$BaselinePath : entry for $($e.suite) pins argument text for $k, which is not a method of the methods table"
    }
    if ([string]$e.data.$k -notmatch '^[0-9a-f]{16}$') {
      throw "$BaselinePath : entry for $($e.suite) pins argument text for $k with a digest that is not 16 lowercase hex digits"
    }
  }
}

$all = 0
$stale = @()
$grown = @()
$newMethods = @()
$newData = @()
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

  # Class membership comes from TestDefinitions (UnitTest.id -> TestMethod.className),
  # never from the result name: xUnit appends the data arguments of a [Theory] to the
  # result name, and argument text may itself contain dots (dates, decimal numbers).
  # The same definitions carry the method name; the arguments are stripped so that
  # every row of a [Theory] maps to the method it exercises, and the rows are counted
  # per method so that a row moving between two theories of one class is visible.
  $idToClass = @{}
  $actualMethods = @{}
  $actualData = @{}
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
    }
    if (-not $actualMethods.ContainsKey($cls)) { $actualMethods[$cls] = @{} }
    if ($actualMethods[$cls].ContainsKey($base)) {
      $actualMethods[$cls][$base] = $actualMethods[$cls][$base] + 1
    } else {
      $actualMethods[$cls][$base] = 1
    }
    $actualMethodCases++
    $idToClass[[string]$ut.id] = $cls
  }
  if ($defsWithoutClass -gt 0) {
    throw "$($e.suite): $defsWithoutClass test definition(s) in $($e.file) have no TestMethod.className - the per-method check cannot be trusted"
  }
  $actualDataSigs = @{}
  foreach ($dcls in @($actualData.Keys)) {
    foreach ($dm in @($actualData[$dcls].Keys)) {
      $rows = $actualData[$dcls][$dm]
      $actualDataSigs["$dcls.$dm"] = Get-DataSignature -Rows $rows
    }
  }

  $actual = @{}
  $unmapped = 0
  foreach ($res in @($xml.TestRun.Results.UnitTestResult)) {
    if ($null -eq $res) { continue }
    $cls = $null
    if ($idToClass.ContainsKey([string]$res.testId)) { $cls = $idToClass[[string]$res.testId] }
    if ([string]::IsNullOrWhiteSpace($cls)) { $unmapped++; continue }
    if ($actual.ContainsKey($cls)) { $actual[$cls] = $actual[$cls] + 1 } else { $actual[$cls] = 1 }
  }
  if ($unmapped -gt 0) {
    throw "$($e.suite): $unmapped result(s) in $($e.file) have no TestMethod.className - the per-class check cannot be trusted"
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
  "$($e.suite): methodCases=$actualMethodCases baseline=method case table $baselineCaseCount cases"
  "$($e.suite): methodData=$($actualDataSigs.Count) baseline=method data table $baselineDataCount digests"

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

  # Per-argument-row check: a method that carried argument text must still carry
  # the same rows. This is the substitution the per-method counts cannot see:
  # swapping the values of two rows, or replacing one row's values, keeps the
  # total, every class count, the method set and every per-method case count
  # identical. The digest is over the sorted argument suffixes, so row order does
  # not matter, but the values themselves do.
  $dataLost = @()
  foreach ($k in @($e.data.PSObject.Properties.Name)) {
    $want = [string]$e.data.$k
    if (-not $actualDataSigs.ContainsKey($k)) {
      $dataLost += "$k (argument text gone)"
    } elseif ([string]$actualDataSigs[$k] -ne $want) {
      # A newly added data row changes the digest as well, so compare the row
      # counts first: growth is what the per-method layer already reports as a
      # warning, and only a substitution at the same or a smaller row count is a
      # loss. Without this, adding an [InlineData] row would turn a documented
      # "refresh the baseline" warning into a hard failure.
      $before = 0
      $now = 0
      $dot = $k.LastIndexOf(".")
      if ($dot -gt 0) {
        $bcls = $k.Substring(0, $dot)
        $bname = $k.Substring($dot + 1)
        if ((@($e.methods.PSObject.Properties.Name) -contains $bcls) -and (@($e.methods.$bcls.PSObject.Properties.Name) -contains $bname)) {
          $before = [int]$e.methods.$bcls.$bname
        }
        if ($actualMethods.ContainsKey($bcls)) { $now = [int]$actualMethods[$bcls][$bname] }
      }
      if ($now -gt $before) {
        $newData += "$($e.suite):$k (argument text grew: $before -> $now rows)"
      } else {
        $dataLost += "$k (baseline $want, now $($actualDataSigs[$k]))"
      }
    }
  }
  if ($dataLost.Count -gt 0) {
    $shown = $dataLost
    if ($shown.Count -gt 12) { $shown = $shown[0..11] + "... ($($dataLost.Count - 12) more)" }
    throw "$($e.suite): test method argument rows changed while the total was $total (baseline $($e.expected)) - equal totals, equal per-class counts, an unchanged method list and unchanged per-method case counts do not prove the same argument values ran. changed: $($shown -join ', '). If the change was intentional, refresh the baseline in windows/tools/test-suite-baseline.json in the same commit."
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
  foreach ($k in @($actualDataSigs.Keys | Sort-Object)) {
    if (-not (@($e.data.PSObject.Properties.Name) -contains $k)) {
      $newData += "$($e.suite):$k (new argument text)"
    }
  }
  if ($actualMethodCases -ne $total) {
    Write-Host "::warning::$($e.suite): $actualMethodCases test definition(s) against $total result(s) - the per-method case counts and the per-class counts disagree; check how the TRX was produced"
  }
  $all += $total
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
  Write-Host "::warning::new test method argument text - refresh windows/tools/test-suite-baseline.json (see windows/tools/update-test-suite-baseline.ps1): $($shown -join '; ')"
}
"combined tests=$all"

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
#   One more level, across files rather than inside one: every count above is read
#   from ONE results file at a time, and the baseline names which file each suite
#   reads. A file copied over another suite's name - or one run exported twice into
#   two names - still looks clean file by file, so the run now also refuses a
#   results set whose files share a TestRun id or an execution id, and prints
#   file identities=/distinct run ids=/cross-file duplicate execution ids=.
#
#   One more level, per row rather than per file: a row's execution identity is
#   written three times inside one TRX - the definition's Execution element, the
#   result row's executionId and the relativeResultsDirectory the row was written
#   to. No count reads any of them, and the row-level metadata check only reads
#   values a row already carries, so the run now also requires the three to agree
#   row by row and prints
#   executionIds=/distinct declared ids=/paired rows=/paired result directories=.

#   One more level, on the name side of the same row identity: a definition's
#   display name is built from the class and method its TestMethod element
#   declares ("Class.Method(<arguments>)"), and no count reads that composition
#   - a file whose display names were rewritten on both halves still pairs up with
#   itself. The run therefore also requires every display name to begin with the
#   declared "Class.Method" and prints
#   displayNames=/declared prefixes=/mismatched=.
#
#   One more level, on the summary itself: every count above is read from this
#   file's ResultSummary/Counters, and nothing looked at that block's own
#   arithmetic or even at whether the attributes it records are numbers. A block
#   that drops an attribute this gate reads, stores a negative or unreadable value,
#   or edits total, executed and notExecuted so that total = executed + notExecuted
#   no longer holds keeps every count above looking exactly as the baseline
#   reviewed it. The run therefore also requires the five attributes it reads to be
#   present, every recorded attribute to be a whole non-negative number, that
#   identity to hold, and prints
#   counters=/attributes required=/missing=/malformed=/identity=.
#
#   One more level, on the verdict rather than the counts: ResultSummary/@outcome
#   says how the whole run ended, and nothing read it - a summary whose buckets
#   were edited to look clean keeps its old (or any plausible) verdict, and a file
#   whose verdict was rewritten keeps every count above identical. A missing or
#   unknown verdict, a Completed verdict with a non-zero failure bucket, and a
#   Failed verdict with failed=0 are now red; the rarer verdicts (Error / Timeout /
#   Aborted / InProgress / NotExecuted / Warning) are recorded on the ladder line
#   and accepted, and the run prints
#   summaryOutcome=/failureBuckets=/contradiction=.
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
#
#   One more level, on the file's header rather than its body: TestRun/@name and
#   @runUser name the run, TestSettings names the settings block (with its own id)
#   and Deployment names the directory the run deployed into. No count reads any of
#   them, and the row metadata check only asks computerName to be uniform inside one
#   file - so a header swapped for another run's header, or a header naming a
#   different machine than every row under it, keeps all of them identical. VSTest
#   writes the run name as "<user>@<machine> <date> <time>" and derives the
#   deployment root from it by replacing "@", " " and ":" with "_"; a name in that
#   shape is checked against both runUser and the deployment root, a name in any
#   other shape is only reported, and a header id that is also a test, execution or
#   list id fails. The run prints
#   runHeader=/settings=/deployments=/derivedRoot=/bound rows=/header id collisions=.
#
#
#   One more level, on the definitions' own view of the artifact: a definition names
#   the assembly it was loaded from twice - UnitTest/@storage records the path the
#   test host copied in, TestMethod/@codeBase records the path the runner wrote - and
#   it declares the adapter that ran it. Nothing compared the two paths, and the
#   assembly set every later count rests on prefers codeBase, so a definition whose
#   storage points at another suite's dll keeps the declared assembly, every class,
#   method, argument row, outcome and header line identical. The run now requires the
#   two records to name the same file (they may differ only in case and in separator
#   direction), requires every definition to declare an adapter and requires one
#   adapter per results file, and prints
#   assemblyDeclaration=/storage\/codeBase pairs=/same file/adapters=.
#
#
#
#   One more level, on the declared lists: TestLists declares the lists the rows ran
#   in, and the only part a reader ever sees is the name. The structure check asks an
#   id to exist, be unique and be used; the row metadata check asks every row to name
#   one list; the header check asks a header id not to collide with a list id. None of
#   them reads a name. Two lists can therefore trade their names - the writer always
#   declares the same two lists with the same ids - and every count, every pairing and
#   every printed line above stays identical while the file tells the reader the rows
#   ran somewhere else. The run now pins the writer's two id-to-name pairs, requires
#   every declared name to be non-blank and distinct, and prints
#   listVocabulary=/listCount canonical names=/distinct names=/unreferenced lists=.

#   The run also declares which schema it was written in and when it was created
#   and queued, and none of the three was read: TestRun/@xmlns, Times/@creation and
#   Times/@queuing. A results file written by another tool (a foreign namespace) or
#   patched to swap its two stamps keeps every count, header line, definition, list
#   name and row timing exactly as the baseline reviewed it, because nothing above
#   looks at the declarations the file makes about itself. Each entry now requires
#   the TeamTest 2010 namespace, a creation and a queueing instant that carry an
#   explicit UTC offset and are readable, and creation before queueing; the ladder
#   line records runDeclarations=<facts>/4 namespace=<ns> timestamps=<n> ordered=<0|1>
#   explicit offsets=<n> outside window=<n>. A creation or queueing instant later
#   than the run finished is a warning, not a red: clock skew between the writer and
#   the runner is not something this gate can decide.

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
#   a result row with no executionId, two result rows sharing one executionId, a
#     row attribute (testType / testListId / computerName on the results, storage
#     on the definitions) that is missing or not the same on every row of the
#     file, an unreadable startTime / endTime, a row that ends before it starts,
#     an unreadable or negative duration, or a row whose start or end falls
#     outside the run's own <Times> window -> red. Every count above is read from
#     this file's own rows, so a row whose own metadata is impossible or borrowed
#     from another row can keep all of them identical.
#   a duration that disagrees with the row's own start/end by more than a second,
#     or a run whose <Times> window records no usable start/finish -> warning.
#   failed != 0 or notExecuted != 0 -> red
#   a declared list that carries no name, two declared lists that call themselves
#     the same thing, or a list whose well-known id does not carry the name the
#     writer gives that id -> red (the list table is the only place a reader learns
#     where a row ran, and no count reads it)
#   a declared list id outside the writer's known vocabulary, a non-GUID list id,
#     or a declared list that no row and no entry references -> warning
#   two results files that share a TestRun id or an execution id -> red
#     (one suite's file copied over another name doubles the reported run)
#   a TestRun id that is not GUID-shaped -> warning
#   the summary's own verdict disagrees with its failure buckets (Completed with a
#     non-zero failed / error / timeout / aborted / notRunnable / disconnected /
#     passedButRunAborted, or Failed with failed=0), or the verdict is missing or
#     outside the TRX summary vocabulary -> red: a block whose counters were edited
#     to look clean keeps its old or any plausible verdict, and no count reads it.
#   failed != 0 or notExecuted != 0 -> red
#
#   the file's header disagrees with the rows it summarises (TestSettings missing,
#     duplicated or nameless, Deployment missing, duplicated or without a root, a
#     runUser that is not machine<backslash>user, a name whose user or machine half
#     disagrees with runUser, a canonical name whose deployment root is not the name
#     with "@", " " and ":" replaced by "_", or a header id reused as a test,
#     execution or list id) -> red: every count is read from the rows and counters,
#     so a swapped header keeps all of them identical.
#   a run name that is not user@machine plus a timestamp, a TestSettings id that is
#     not GUID-shaped -> warning (the producer format is not pinned, only its
#     internal consistency is).
#   a definition whose UnitTest/@storage and TestMethod/@codeBase name different
#     files (the two records of one assembly disagree), a definition that declares no
#     adapterTypeName, or two different adapterTypeName values inside one results file
#     -> red: codeBase alone decides which assembly a file was produced from, so these
#     records can contradict each other while every count stays identical.
#   an adapterTypeName that is not an executor:// URI -> warning (the adapter naming
#     scheme is not pinned, only its presence and its uniformity are).
#   the schema namespace is not the TeamTest 2010 one, or Times records no creation
#     or no queueing instant, or either instant cannot be read or carries no explicit
#     UTC offset, or the run is queued before it is created -> red

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
  
# The only schema this gate reads. A file that declares another namespace was written
# by another tool (or hand-patched to say so), and every property read above still works
# because PowerShell's XML adapter ignores namespaces, so nothing else would notice.
$TeamTestNamespace = 'http://microsoft.com/schemas/VisualStudio/TeamTest/2010'
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

# Parses a TRX timestamp (ISO 8601 with offset) into a comparable instant.
# Returns $null when the text is missing or unreadable, so a row with an
# impossible time is reported instead of being sorted as the epoch.
function Convert-ToInstant {
  param([string]$Text)
  if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
  try {
    return [System.DateTimeOffset]::Parse($Text, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind)
  } catch {
    return $null
  }
}

# Parses a TRX duration (TimeSpan text) into milliseconds; $null when unreadable.
# Kept separate from Convert-ToInstant because a duration is not a timestamp.
function Convert-ToMillis {
  param([string]$Text)
  if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
  try {
    return [double]([System.TimeSpan]::Parse($Text, [System.Globalization.CultureInfo]::InvariantCulture).TotalMilliseconds)
  } catch {
    return $null
  }
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
$durWarn = @()

$runIdByFile = @{}
$fileOfExecution = @{}
$runIdWarn = @()
$fileIdentityLost = @()
$counterVocabularyWarn = @()
$headerWarn = @()
$assemblyWarn = @()
$listWarn = @()
$declarationWarn = @()
$assemblyWarn = @()
$listWarn = @()
$headerWarn = @()
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

  # The run's own identity. Each results file must name the run that produced it,
  # and two files must not claim the same run or the same execution ids: copying one
  # suite's TRX over another name (or exporting one run twice) doubles the cases the
  # gate counts while every per-file number above stays exactly as the baseline
  # reviewed it, so nothing else here would notice.
  $runId = [string]$xml.TestRun.id
  $runIdByFile[[string]$e.file] = $runId
  if ([string]::IsNullOrWhiteSpace($runId)) {
    $fileIdentityLost += "$($e.file) names no TestRun id"
  } elseif ($runId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
    $runIdWarn += "$($e.file) run id '$runId' is not a GUID"
  }

  # The run says when it started and finished; a row outside that window was not
  # produced by this run. A TRX that records no usable window can only be
  # reported, never gated.
  $tRunStart = $null
  $tRunFinish = $null
  $windowUnknown = $false
  if ($xml.TestRun.Times) {
    $tRunStart = Convert-ToInstant ([string]$xml.TestRun.Times.start)
    $tRunFinish = Convert-ToInstant ([string]$xml.TestRun.Times.finish)
  }
  if ($null -eq $tRunStart -or $null -eq $tRunFinish) { $windowUnknown = $true }

  # The run declares the schema it was written in and the instants it was created and
  # queued. No count above reads any of the three, so a file from another tool or one
  # whose two stamps were swapped still reproduces every number this gate prints.
  $declarationSchema = [string]$xml.TestRun.GetAttribute('xmlns')
  $declarationCreationRaw = ''
  $declarationQueuingRaw = ''
  if ($xml.TestRun.Times) {
    $declarationCreationRaw = [string]$xml.TestRun.Times.creation
    $declarationQueuingRaw = [string]$xml.TestRun.Times.queuing
  }
  $declarationCreation = Convert-ToInstant $declarationCreationRaw
  $declarationQueuing = Convert-ToInstant $declarationQueuingRaw
  $declarationLost = @()
  $declarationFileWarn = @()
  $declarationFacts = 0
  $declarationOrdered = 0
  $declarationOffsets = 0
  $declarationOutside = 0
  $declarationNamesOk = 0
  if ([string]::IsNullOrWhiteSpace($declarationSchema)) {
    $declarationLost += 'the run declares no schema namespace'
  } elseif ($declarationSchema -ne $TeamTestNamespace) {
    $declarationLost += "the run declares the schema '$declarationSchema' instead of '$TeamTestNamespace'"
  } else {
    $declarationNamesOk = 1
  }
  $declarationStamps = 0
  foreach ($stamp in @(@('creation', $declarationCreationRaw, $declarationCreation), @('queuing', $declarationQueuingRaw, $declarationQueuing))) {
    $stampName = [string]$stamp[0]
    $stampRaw = [string]$stamp[1]
    $stampValue = $stamp[2]
    if ([string]::IsNullOrWhiteSpace($stampRaw)) {
      $declarationLost += "Times records no $stampName instant"
      continue
    }
    $declarationStamps++
    if ($null -eq $stampValue) {
      $declarationLost += "Times.$stampName '$stampRaw' is not an instant this gate can read"
      continue
    }
    if ($stampRaw -notmatch '([+-][0-9]{2}:[0-9]{2}|Z)$') {
      $declarationLost += "Times.$stampName '$stampRaw' carries no explicit UTC offset"
      continue
    }
    $declarationOffsets++
  }
  if ($null -ne $declarationCreation -and $null -ne $declarationQueuing) {
    if ($declarationQueuing -lt $declarationCreation) {
      $declarationLost += "Times queues the run before it creates it (creation $declarationCreationRaw, queuing $declarationQueuingRaw)"
    } else {
      $declarationOrdered = 1
    }
  }
  if ($null -ne $tRunFinish) {
    foreach ($stamp in @(@('creation', $declarationCreation), @('queuing', $declarationQueuing))) {
      if ($null -ne $stamp[1] -and $stamp[1] -gt $tRunFinish) {
        $declarationOutside++
        $declarationFileWarn += "$($e.file): Times.$([string]$stamp[0]) is later than the run finished"
      }
    }
  }
  $declarationFacts = $declarationNamesOk + $declarationStamps + $declarationOrdered
  $declarationWarn += $declarationFileWarn

  # Row metadata is collected from both halves before anything is compared: the
  # result rows carry their own identity and timings, the definitions carry the
  # assembly each row was loaded from.
  $execIdCounts = @{}
  $blankExecIds = 0
  $rowCount = 0
  $timeOrdered = 0
  $badTimes = 0
  $timeReversed = 0
  $badDuration = 0
  $durDrift = 0
  $inWindow = 0
  $outOfWindow = 0
  $attrBlank = @{ testType = 0; testListId = 0; computerName = 0; storage = 0 }
  $attrValues = @{ testType = @{}; testListId = @{}; computerName = @{}; storage = @{} }

  # Two whole sections carry pairing that no count above reads: TestEntries ties
  # each testId to the executionId and the list the row ran in, and TestLists
  # declares those lists. A hand-edited file can drop either section, add or
  # remove entries, or reshuffle the executionId of two entries while every
  # number read so far stays identical.
  # Count the blocks from the document itself: property access on a missing
  # child hands back $null, and @($null).Count is 1 in both PowerShell editions,
  # so a presence test written that way can never notice a dropped section.
  $sectionBlocks = @{}
  foreach ($sbn in @('ResultSummary', 'Times', 'TestDefinitions', 'TestEntries', 'TestLists', 'Results')) { $sectionBlocks[$sbn] = 0 }
  $counterBlocks = 0
  foreach ($child in @($xml.TestRun.ChildNodes)) {
    $childName = [string]$child.LocalName
    if ($sectionBlocks.ContainsKey($childName)) { $sectionBlocks[$childName] = [int]$sectionBlocks[$childName] + 1 }
    if ($childName -eq 'ResultSummary') {
      foreach ($sub in @($child.ChildNodes)) { if ([string]$sub.LocalName -eq 'Counters') { $counterBlocks++ } }
    }
  }
  $sectionBlocks['Counters'] = $counterBlocks
  $entryCount = 0
  $blankEntryIds = 0
  $duplicateEntryIds = @()
  $entryExecById = @{}
  $entryListById = @{}
  $listCount = 0
  $blankListIds = 0
  $duplicateListIds = 0
  $listNames = @{}
  # The declared lists are a vocabulary, not just an id set: VSTest always declares
  # the same two lists with the same ids and the same names, and no check above ever
  # read a name. Keep the writer's id-to-name pairs here so the two can be compared.
  $listNameBlank = 0
  $listNameDuplicates = @()
  $listNameCanonicalLost = @()
  $listIdUnknown = 0
  $listIdNotGuid = 0
  $listFileWarn = @()
  $listNamesSeen = @{}
  $canonicalListNames = @{
    '8c84fa94-04c1-424b-9868-57a2d4851a1d' = 'Results Not in a List'
    '19431567-8539-422a-85d7-44ee4e166bda' = 'All Loaded Results'
  }
  foreach ($te in @($xml.TestRun.TestEntries.TestEntry)) {
    if ($null -eq $te) { continue }
    $entryCount++
    $tidEntry = [string]$te.testId
    if ([string]::IsNullOrWhiteSpace($tidEntry)) { $blankEntryIds++; continue }
    if ($entryExecById.ContainsKey($tidEntry)) {
      $duplicateEntryIds += $tidEntry
    } else {
      $entryExecById[$tidEntry] = [string]$te.executionId
      $entryListById[$tidEntry] = [string]$te.testListId
    }
  }
  foreach ($tl in @($xml.TestRun.TestLists.TestList)) {
    if ($null -eq $tl) { continue }
    $listCount++
    $lidEntry = [string]$tl.id
    if ([string]::IsNullOrWhiteSpace($lidEntry)) { $blankListIds++; continue }
    $lname = [string]$tl.name
    if ([string]::IsNullOrWhiteSpace($lname)) { $listNameBlank++ }
    if (-not [string]::Equals($lname, $lname.Trim(), [System.StringComparison]::Ordinal)) {
      $listFileWarn += "$($e.suite): declared list $lidEntry names itself with surrounding whitespace ('$lname')"
    }
    if ($listNames.ContainsKey($lidEntry)) { $duplicateListIds++ } else { $listNames[$lidEntry] = $lname }
    if (-not [string]::IsNullOrWhiteSpace($lname)) {
      $lkey = $lname.ToUpperInvariant()
      if ($listNamesSeen.ContainsKey($lkey)) {
        $listNameDuplicates += "declared lists $($listNamesSeen[$lkey]) and $lidEntry both call themselves '$lname'"
      } else {
        $listNamesSeen[$lkey] = $lidEntry
      }
    }
    $lidKey = $lidEntry.ToLowerInvariant()
    if ($lidKey -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { $listIdNotGuid++ }
    if ($canonicalListNames.ContainsKey($lidKey)) {
      $wantListName = [string]$canonicalListNames[$lidKey]
      if (-not [string]::Equals($lname, $wantListName, [System.StringComparison]::Ordinal)) {
        $listNameCanonicalLost += "list $lidEntry names itself '$lname' while that id is the writer's '$wantListName'"
      }
    } else {
      $listIdUnknown++
    }
  }

  $blankDefExec = 0
  $defExecCounts = @{}
  $execByDefId = @{}
  $exElWarn = @()

  # The definition also names its assembly twice, and declares the adapter that ran
  # it. Compare both records instead of trusting the one the assembly set prefers.
  $assemblyPairs = 0
  $assemblyPairLostCount = 0
  $assemblyPairLost = @()
  $adapterBlank = 0
  $adapterValues = @{}
  $assemblyFileWarn = @()

  # Identity from the artifact: the TestMethod elements record the assembly that
  # produced these results (codeBase; older writers only fill storage). A single
  # recorded assembly that does not match the declared suite name means this
  # entry is wired to another suite's results.
  $assemblies = New-Object System.Collections.Generic.List[string]
  foreach ($ut in @($xml.TestRun.TestDefinitions.UnitTest)) {
    if ($null -eq $ut) { continue }
    $stv = [string]$ut.storage
    if ([string]::IsNullOrWhiteSpace($stv)) {
      $attrBlank['storage'] = [int]$attrBlank['storage'] + 1
    } elseif (-not $attrValues['storage'].ContainsKey($stv)) {
      $attrValues['storage'][$stv] = $true
    }
    # The definition also names the execution it belongs to. xUnit writes that same
    # id into the result row's executionId and into the row's result directory, so a
    # definition whose declared execution does not match its own row was edited.
    $exEls = @(@($ut.Execution) | Where-Object { $null -ne $_ })
    if ($exEls.Count -eq 1) {
      $dxid = [string]$exEls[0].id
      if ([string]::IsNullOrWhiteSpace($dxid)) {
        $blankDefExec++
      } else {
        if ($defExecCounts.ContainsKey($dxid)) { $defExecCounts[$dxid] = [int]$defExecCounts[$dxid] + 1 } else { $defExecCounts[$dxid] = 1 }
        $didKey = [string]$ut.id
        if (-not [string]::IsNullOrWhiteSpace($didKey) -and -not $execByDefId.ContainsKey($didKey)) { $execByDefId[$didKey] = $dxid }
      }
    } elseif ($exEls.Count -eq 0) {
      $blankDefExec++
    } else {
      $exElWarn += "definition $([string]$ut.id) declares $($exEls.Count) Execution elements"
    }

    $cb = $null
    if ($ut.TestMethod) { $cb = [string]$ut.TestMethod.codeBase }
    if ([string]::IsNullOrWhiteSpace($cb)) { $cb = [string]$ut.storage }
    if ([string]::IsNullOrWhiteSpace($cb)) { continue }
    $leaf = $cb.Split([char]92, [char]47)
    $leafName = ""
    foreach ($part in $leaf) { if (-not [string]::IsNullOrWhiteSpace($part)) { $leafName = $part } }
    if ([string]::IsNullOrWhiteSpace($leafName)) { continue }
    if ($leafName.EndsWith(".dll", [System.StringComparison]::OrdinalIgnoreCase)) { $leafName = $leafName.Substring(0, $leafName.Length - 4) }
    # storage and codeBase are two records of one file: compare them as paths that
    # may differ only in case and in separator direction.
    $cbDeclared = ""
    if ($ut.TestMethod) { $cbDeclared = [string]$ut.TestMethod.codeBase }
    if (-not [string]::IsNullOrWhiteSpace($cbDeclared) -and -not [string]::IsNullOrWhiteSpace($stv)) {
      $assemblyPairs++
      $normalStorage = $stv.Replace('/', [char]92).ToLowerInvariant()
      $normalCodeBase = $cbDeclared.Replace('/', [char]92).ToLowerInvariant()
      if (-not ($normalStorage -eq $normalCodeBase)) {
        $assemblyPairLostCount++
        if ($assemblyPairLost.Count -lt 12) { $assemblyPairLost += "definition $([string]$ut.id) records '$stv' as storage and '$cbDeclared' as codeBase" }
      }
    }

    # The adapter that ran the definition is written once per definition and was
    # never read: a file whose definitions name different adapters, or none at all,
    # was assembled from more than one run.
    $adapter = ""
    if ($ut.TestMethod) { $adapter = [string]$ut.TestMethod.adapterTypeName }
    if ([string]::IsNullOrWhiteSpace($adapter)) {
      $adapterBlank++
    } else {
      if (-not $adapterValues.ContainsKey($adapter)) { $adapterValues[$adapter] = 0 }
      $adapterValues[$adapter] = [int]$adapterValues[$adapter] + 1
      if (-not $adapter.StartsWith('executor://', [System.StringComparison]::OrdinalIgnoreCase)) {
        if ($assemblyFileWarn.Count -lt 12) { $assemblyFileWarn += "$($e.file) adapter '$adapter' is not an executor:// URI" }
      }
    }
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

  # One message list for the definition-side assembly declaration.
  $assemblyLost = @()
  if ($assemblyPairLostCount -gt 0) {
    $assemblyLost += "$assemblyPairLostCount definition(s) record a storage path and a codeBase path that name different files: $($assemblyPairLost -join '; ')"
  }
  if ($adapterBlank -gt 0) { $assemblyLost += "$adapterBlank definition(s) declare no adapterTypeName" }
  if ($adapterValues.Count -gt 1) {
    $assemblyLost += "$($adapterValues.Count) different adapterTypeName values inside one results file: $((@($adapterValues.Keys | Sort-Object)) -join ', ')"
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
  $displayNameChecked = 0
  $displayNameLost = @()
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
    if (-not [string]::IsNullOrWhiteSpace($display)) {
      $declCls = ""
      $declName = ""
      if ($ut.TestMethod) {
        $declCls = [string]$ut.TestMethod.className
        $declName = [string]$ut.TestMethod.name
      }
      if (-not [string]::IsNullOrWhiteSpace($declCls) -and -not [string]::IsNullOrWhiteSpace($declName)) {
        $displayNameChecked++
        $wantDisplayPrefix = "$declCls.$declName"
        $okDisplay = $false
        if ($display -eq $wantDisplayPrefix) { $okDisplay = $true }
        elseif ($display.StartsWith($wantDisplayPrefix + "(")) { $okDisplay = $true }
        if (-not $okDisplay) {
          $dnLabel = $did
          if ([string]::IsNullOrWhiteSpace($dnLabel)) { $dnLabel = $display }
          $displayNameLost += "$dnLabel display '$display' does not begin with the declared method '$wantDisplayPrefix'"
        }
      }
    }
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
  $resExecById = @{}
  $resListById = @{}
  $resDirById = @{}
  $blankRowDirs = 0
  $namedExact = 0
  $namedPrefix = 0
  $nameMismatch = @()
  $namesUnprovable = 0
  $unmapped = 0
  foreach ($res in @($xml.TestRun.Results.UnitTestResult)) {
    if ($null -eq $res) { continue }
    $rowCount++
    $eid = [string]$res.executionId
    if ([string]::IsNullOrWhiteSpace($eid)) {
      $blankExecIds++
    } elseif ($execIdCounts.ContainsKey($eid)) {
      $execIdCounts[$eid] = [int]$execIdCounts[$eid] + 1
    } else {
      $execIdCounts[$eid] = 1
    }
    if (-not [string]::IsNullOrWhiteSpace($eid)) {
      $execFile = [string]$e.file
      if (-not $fileOfExecution.ContainsKey($eid)) { $fileOfExecution[$eid] = New-Object System.Collections.Generic.List[string] }
      if (-not $fileOfExecution[$eid].Contains($execFile)) { $fileOfExecution[$eid].Add($execFile) }
    }
    foreach ($ak in @('testType', 'testListId', 'computerName')) {
      $av = [string]$res.$ak
      if ([string]::IsNullOrWhiteSpace($av)) {
        $attrBlank[$ak] = [int]$attrBlank[$ak] + 1
      } elseif (-not $attrValues[$ak].ContainsKey($av)) {
        $attrValues[$ak][$av] = $true
      }
    }
    $rstart = Convert-ToInstant ([string]$res.startTime)
    $rend = Convert-ToInstant ([string]$res.endTime)
    $rms = Convert-ToMillis ([string]$res.duration)
    if ($null -eq $rstart -or $null -eq $rend) {
      $badTimes++
    } else {
      if ($rend -lt $rstart) {
        $timeReversed++
      } else {
        $timeOrdered++
      }
      if ($null -ne $rms -and [math]::Abs((($rend - $rstart).TotalMilliseconds) - $rms) -gt 1000) { $durDrift++ }
      if ($null -ne $tRunStart -and $null -ne $tRunFinish) {
        if ($rstart -lt $tRunStart -or $rend -gt $tRunFinish) { $outOfWindow++ } else { $inWindow++ }
      }
    }
    if ($null -eq $rms -or $rms -lt 0) { $badDuration++ }
    $rid = [string]$res.testId
    if ($resIdCounts.ContainsKey($rid)) { $resIdCounts[$rid] = $resIdCounts[$rid] + 1 } else { $resIdCounts[$rid] = 1 }
    if (-not [string]::IsNullOrWhiteSpace($rid)) {
      $resExecById[$rid] = [string]$res.executionId
      $resListById[$rid] = [string]$res.testListId
      $rdir = [string]$res.relativeResultsDirectory
      $resDirById[$rid] = $rdir
      if ([string]::IsNullOrWhiteSpace($rdir)) { $blankRowDirs++ }
    }
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

  # Each row's own metadata must be self-consistent. The total, every class
  # count, the method set, the argument rows, the outcome split and the pairing
  # of the two halves are all read from this file, so a file whose rows borrow
  # each other's execution ids, drop or mix one row attribute, or carry an
  # impossible timestamp can leave every one of them identical.
  $rowAttrLost = @()
  if ($blankExecIds -gt 0) { $rowAttrLost += "$blankExecIds result row(s) have no executionId" }
  foreach ($eid in @($execIdCounts.Keys | Where-Object { [int]$execIdCounts[$_] -gt 1 } | Sort-Object)) {
    if ($rowAttrLost.Count -lt 12) { $rowAttrLost += "result executionId $eid appears $($execIdCounts[$eid]) times" }
  }
  $uniformFields = 0
  foreach ($ak in @('computerName', 'storage', 'testListId', 'testType')) {
    if ([int]$attrBlank[$ak] -gt 0) {
      if ($rowAttrLost.Count -lt 12) { $rowAttrLost += "$($attrBlank[$ak]) row(s) have no $ak" }
    } elseif ($attrValues[$ak].Count -eq 1) {
      $uniformFields++
    } elseif ($attrValues[$ak].Count -gt 1) {
      if ($rowAttrLost.Count -lt 12) { $rowAttrLost += "$ak is not the same on every row ($($attrValues[$ak].Count) values in one file)" }
    } else {
      if ($rowAttrLost.Count -lt 12) { $rowAttrLost += "$ak is recorded nowhere in $($e.file)" }
    }
  }
  if ($badTimes -gt 0) { $rowAttrLost += "$badTimes row(s) carry an unreadable startTime or endTime" }
  if ($timeReversed -gt 0) { $rowAttrLost += "$timeReversed row(s) end before they start" }
  if ($badDuration -gt 0) { $rowAttrLost += "$badDuration row(s) carry an unreadable or negative duration" }
  if ($outOfWindow -gt 0) { $rowAttrLost += "$outOfWindow row(s) fall outside the run's own Times window" }
  if ($rowAttrLost.Count -gt 0) {
    throw "$($e.suite): the result rows contradict their own metadata while the total was $total - every count above is read from this file's rows, so rows that share an execution id, drop or mix a row attribute, or carry an impossible timestamp can keep all of them identical. broken: $($rowAttrLost -join ', '). A TRX whose rows disagree with themselves was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($durDrift -gt 0) {
    $durWarn += "$($e.suite) ($durDrift row(s) report a duration that disagrees with their own start/end by more than a second)"
  }
  if ($windowUnknown) {
    $durWarn += "$($e.suite) ($($e.file) records no usable Times window - row times cannot be checked against the run)"
  }

  # The sections above are read one at a time; the file also has to be whole.
  # TestEntries pairs each testId with the executionId and the list the row ran
  # in, and TestLists declares those lists. Neither section feeds any count
  # above, so dropping a section, adding or removing entries, or swapping the
  # executionId of two entries leaves every one of those numbers identical.
  $structureLost = @()
  $sectionCount = 0
  foreach ($sn in @('ResultSummary', 'Times', 'Counters', 'TestDefinitions', 'TestEntries', 'TestLists', 'Results')) {
    if ([int]$sectionBlocks[$sn] -eq 1) { $sectionCount++ } else { $structureLost += "$sn appears $($sectionBlocks[$sn]) time(s)" }
  }
  if ($blankEntryIds -gt 0) { $structureLost += "$blankEntryIds TestEntry item(s) have no testId" }
  foreach ($id in @($duplicateEntryIds | Sort-Object)) {
    if ($structureLost.Count -lt 12) { $structureLost += "TestEntry testId $id appears more than once" }
  }
  foreach ($id in @($defIdCounts.Keys | Where-Object { -not $entryExecById.ContainsKey($_) } | Sort-Object)) {
    if ($structureLost.Count -lt 12) { $structureLost += "definition id $id has no TestEntry" }
  }
  $matchedEntries = 0
  foreach ($id in @($entryExecById.Keys | Sort-Object)) {
    if ($defIdCounts.ContainsKey($id) -and [int]$defIdCounts[$id] -eq 1 -and $resIdCounts.ContainsKey($id)) {
      $matchedEntries++
    } else {
      if ($structureLost.Count -lt 12) { $structureLost += "TestEntry testId $id is not a unique test definition with a result row" }
    }
  }
  $pairedExecIds = 0
  $pairedListIds = 0
  $ghostLists = @()
  foreach ($id in @($entryExecById.Keys | Sort-Object)) {
    $ee = [string]$entryExecById[$id]
    $el = [string]$entryListById[$id]
    if (-not $resExecById.ContainsKey($id)) { continue }
    if (-not [string]::IsNullOrWhiteSpace($ee) -and [string]::Equals([string]$resExecById[$id], $ee, [System.StringComparison]::OrdinalIgnoreCase)) {
      $pairedExecIds++
    } elseif ($structureLost.Count -lt 12) {
      $structureLost += "testId $id pairs TestEntry executionId $ee with result executionId $($resExecById[$id])"
    }
    if ($listNames.ContainsKey($el)) {
      $pairedListIds++
    } elseif (-not [string]::IsNullOrWhiteSpace($el)) {
      $ghostLists += $id
    }
  }
  if ($blankListIds -gt 0) { $structureLost += "$blankListIds TestList item(s) have no id" }
  if ($duplicateListIds -gt 0) { $structureLost += "$duplicateListIds TestList id(s) appear more than once" }
  if ($ghostLists.Count -gt 0) {
    $shownGhost = @($ghostLists | Sort-Object)
    if ($shownGhost.Count -gt 12) { $shownGhost = $shownGhost[0..11] + "... ($($ghostLists.Count) total)" }
    $structureLost += "result row(s) ran in a list that TestLists does not declare (testId $($shownGhost -join ', '))"
  }
  if ($structureLost.Count -gt 0) {
    throw "$($e.suite): the TRX sections do not agree with each other while the total was $total - the summary, the definitions, the result rows, the entry map and the declared lists all come from this file, so one that drops a section, adds or removes entries, or pairs an entry with the wrong execution id can keep every count above identical. mismatched: $($structureLost -join ', '). A TRX whose sections disagree was hand-merged or patched; regenerate it from a clean test run."
  }

  # A declared list is a vocabulary entry, not just an id in a set. The structure
  # check above asks a list id to exist, be unique and be used, and the row metadata
  # check asks every row to name one list, so the name - the only part a reader ever
  # sees - was never read. Two lists can therefore trade their names, or a list can
  # be renamed to another list's name, while every count and every pairing above
  # stays identical.
  $listVocabularyLost = @()
  $listKnown = 0
  $listCanonical = 0
  if ($listNameBlank -gt 0) { $listVocabularyLost += "$listNameBlank declared list(s) carry no name" }
  foreach ($dupName in @($listNameDuplicates)) {
    if ($listVocabularyLost.Count -lt 12) { $listVocabularyLost += $dupName }
  }
  foreach ($lostListName in @($listNameCanonicalLost)) {
    if ($listVocabularyLost.Count -lt 12) { $listVocabularyLost += $lostListName }
  }
  foreach ($lidScan in @($listNames.Keys | Sort-Object)) {
    $lidScanKey = $lidScan.ToLowerInvariant()
    if ($canonicalListNames.ContainsKey($lidScanKey)) {
      $listKnown++
      if ([string]::Equals([string]$listNames[$lidScan], [string]$canonicalListNames[$lidScanKey], [System.StringComparison]::Ordinal)) { $listCanonical++ }
    }
  }
  $listReferenced = @{}
  foreach ($krl in @($resListById.Keys)) {
    $rlv = [string]$resListById[$krl]
    if (-not [string]::IsNullOrWhiteSpace($rlv)) { $listReferenced[$rlv.ToLowerInvariant()] = $true }
  }
  foreach ($kel in @($entryListById.Keys)) {
    $elv = [string]$entryListById[$kel]
    if (-not [string]::IsNullOrWhiteSpace($elv)) { $listReferenced[$elv.ToLowerInvariant()] = $true }
  }
  $unreferencedLists = @($listNames.Keys | Where-Object { -not $listReferenced.ContainsKey($_.ToLowerInvariant()) } | Sort-Object)
  if ($listIdUnknown -gt 0) {
    $listFileWarn += "$($e.suite): $listIdUnknown declared list id(s) outside the vocabulary this gate knows ($listCount declared) - for those only presence, uniqueness, use and a non-blank distinct name were verified"
  }
  if ($listIdNotGuid -gt 0) { $listFileWarn += "$($e.suite): $listIdNotGuid declared list id(s) are not GUID-shaped" }
  if ($unreferencedLists.Count -gt 0) {
    $listFileWarn += "$($e.suite): declared list(s) that no row and no entry references: $($unreferencedLists -join ', ') - a virtual list such as All Loaded Results is expected here"
  }
  $listWarn += $listFileWarn

  # Each row's execution identity is declared three times inside the file: the
  # definition element carries the execution it belongs to, the result row carries
  # the same id as executionId, and it also names the directory its result was
  # written to (relativeResultsDirectory - the run writes the execution id there).
  # None of the three feeds a count above, and the row-level metadata check only
  # looks at values a row already has, so swapping a definition's execution id,
  # blanking one row's directory or dropping the element entirely keeps every
  # number identical.
  $execPairingLost = @()
  $pairedRowExecs = 0
  $pairedRowDirs = 0
  if ($blankDefExec -gt 0) { $execPairingLost += "$blankDefExec test definition(s) declare no Execution id" }
  if ($exElWarn.Count -gt 0) { $execPairingLost += @($exElWarn) }
  foreach ($xid in @($defExecCounts.Keys | Sort-Object)) {
    if ([int]$defExecCounts[$xid] -gt 1) {
      if ($execPairingLost.Count -lt 12) { $execPairingLost += "Execution id $xid is declared by $($defExecCounts[$xid]) definitions" }
    }
  }
  foreach ($did in @($execByDefId.Keys | Sort-Object)) {
    $want = [string]$execByDefId[$did]
    if (-not $resExecById.ContainsKey($did)) {
      if ($execPairingLost.Count -lt 12) { $execPairingLost += "definition id $did declares execution id $want but has no result row" }
      continue
    }
    $got = [string]$resExecById[$did]
    if ([string]::Equals($got, $want, [System.StringComparison]::OrdinalIgnoreCase)) {
      $pairedRowExecs++
    } elseif ($execPairingLost.Count -lt 12) {
      $execPairingLost += "testId $did declares execution id $want while its result row reports $got"
    }
    $rdir = [string]$resDirById[$did]
    if (-not [string]::IsNullOrWhiteSpace($rdir)) {
      if ([string]::Equals($rdir, $want, [System.StringComparison]::OrdinalIgnoreCase)) {
        $pairedRowDirs++
      } elseif ($execPairingLost.Count -lt 12) {
        $execPairingLost += "testId $did writes its result directory $rdir while its definition declares execution id $want"
      }
    }
  }
  if ($blankRowDirs -gt 0) { $execPairingLost += "$blankRowDirs result row(s) record no relativeResultsDirectory" }
  if ($execPairingLost.Count -gt 0) {
    throw "$($e.suite): each row's execution identity does not agree with itself across the file while the total was $total - a clean run writes the same id into the definition's Execution element, the result row's executionId and the directory the row's result was written to, so an edit that swaps a definition's execution id, blanks a row's directory or drops the element keeps every count above identical. mismatched: $($execPairingLost -join ', '). A TRX whose rows disagree with their own declared execution was hand-merged or patched; regenerate it from a clean test run."
  }

  if ($displayNameLost.Count -gt 0) {
    throw "$($e.suite): the row display names do not agree with the method they declare while the total was $total - every count above is read from this file's own rows, so a file whose display names were rewritten on both halves reports a different set of cases than the one the baseline reviewed and still reproduces every number above. mismatched: $($displayNameLost -join ', '). A TRX whose display names disagree with their TestMethod was hand-merged or patched; regenerate it from a clean test run."
  }

  # The summary is not trusted on its own: every result row carries exactly one
  # The summary is not trusted on its own either. Every count above is read from
  # this block, and nothing used to look at the block itself: the five attributes
  # this gate reads must be there, each recorded attribute must be a whole
  # non-negative number, and VSTest's own identity must hold. A block that drops an
  # attribute, stores a negative or unreadable value, or edits total, executed and
  # notExecuted independently keeps every count above identical.
  $counterAttributes = @{}
  foreach ($ca in @($xml.TestRun.ResultSummary.Counters.Attributes)) {
    $counterAttributes[[string]$ca.Name] = [string]$ca.Value
  }
  $counterCount = $counterAttributes.Count
  $requiredCounterNames = @('total', 'executed', 'passed', 'failed', 'notExecuted')
  $missingCounters = @()
  foreach ($rn in $requiredCounterNames) {
    if (-not $counterAttributes.ContainsKey($rn) -or [string]::IsNullOrWhiteSpace([string]$counterAttributes[$rn])) {
      $missingCounters += $rn
    }
  }
  $malformedCounters = @()
  foreach ($ack in @($counterAttributes.Keys | Sort-Object)) {
    $araw = [string]$counterAttributes[$ack]
    $aparsed = 0
    if (-not [int]::TryParse($araw, [ref]$aparsed) -or $aparsed -lt 0) {
      $malformedCounters += "$ack='$araw'"
    } elseif (-not $OutcomeByAttribute.ContainsKey($ack) -and $ack -ne 'total' -and $ack -ne 'executed') {
      $counterVocabularyWarn += "$($e.file) records the Counters attribute '$ack', which this gate does not know"
    }
  }
  $counterArithmetic = @()
  if ($missingCounters.Count -eq 0 -and $malformedCounters.Count -eq 0) {
    $cTotal = [int]$counterAttributes['total']
    $cExecuted = [int]$counterAttributes['executed']
    $cNotExecuted = [int]$counterAttributes['notExecuted']
    $cPassed = [int]$counterAttributes['passed']
    $cFailed = [int]$counterAttributes['failed']
    if ($cTotal -ne ($cExecuted + $cNotExecuted)) {
      $counterArithmetic += "total=$cTotal vs executed+notExecuted=$($cExecuted + $cNotExecuted)"
    }
    if (($cPassed + $cFailed) -gt $cExecuted) {
      $counterArithmetic += "passed+failed=$($cPassed + $cFailed) vs executed=$cExecuted"
    }
  }
  if ($missingCounters.Count -gt 0 -or $malformedCounters.Count -gt 0 -or $counterArithmetic.Count -gt 0) {
    throw "$($e.suite): $($e.file) records a Counters block that contradicts itself while the total was $total - every count above is read from this block, so a block that drops an attribute this gate reads, stores a negative or unreadable value, or edits total, executed and notExecuted independently can leave all of them looking exactly like the baseline reviewed. broken: missing [$(@($missingCounters) -join ', ')] / malformed [$(@($malformedCounters) -join ', ')] / arithmetic [$(@($counterArithmetic) -join '; ')]. A TRX whose summary does not add up was hand-merged or patched; regenerate it from a clean test run."
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

  # The verdict on top of the counters: ResultSummary/@outcome says how the whole
  # run ended, and no count above reads it. The rows and the buckets can agree with
  # each other while the verdict that summarises both is stale or invented - a file
  # whose buckets were edited to look clean, or whose verdict was rewritten, keeps
  # every count above identical. Only the two unequivocal verdicts are pinned to the
  # buckets; the rarer ones (Error / Timeout / Aborted / InProgress / ...) are
  # recorded on the ladder line and accepted, and a bucket this block does not read
  # is treated as zero.
  $summaryOutcome = [string]$xml.TestRun.ResultSummary.outcome
  $summaryKnownOutcomes = @('Completed', 'Failed', 'Error', 'Timeout', 'Aborted', 'InProgress', 'NotExecuted', 'Warning')
  $summaryContradiction = @()
  if ([string]::IsNullOrWhiteSpace($summaryOutcome)) {
    $summaryContradiction += 'the summary names no outcome'
  } elseif ($summaryKnownOutcomes -notcontains $summaryOutcome) {
    $summaryContradiction += "the summary records outcome '$summaryOutcome', which is not in the TRX summary vocabulary"
  }
  $failureBucketNames = @('failed', 'error', 'timeout', 'aborted', 'notRunnable', 'disconnected', 'passedButRunAborted')
  $failureBucketTotal = 0
  $failureBucketsSet = @()
  foreach ($fb in $failureBucketNames) {
    if ($counterAttributes.ContainsKey($fb) -and -not [string]::IsNullOrWhiteSpace([string]$counterAttributes[$fb])) {
      $fbValue = [int]$counterAttributes[$fb]
      $failureBucketTotal += $fbValue
      if ($fbValue -gt 0) { $failureBucketsSet += "$fb=$fbValue" }
    }
  }
  if ($summaryContradiction.Count -eq 0) {
    if ($summaryOutcome -eq 'Completed' -and $failureBucketTotal -gt 0) {
      $summaryContradiction += "the summary says Completed while $($failureBucketsSet -join ', ')"
    }
    if ($summaryOutcome -eq 'Failed' -and ([int]$counterAttributes['failed']) -eq 0) {
      $summaryContradiction += 'the summary says Failed while failed=0'
    }
  }
  # The verdict contradiction is raised at the very end of this suite iteration (see
  # below) so that every pre-existing check keeps its own failure text: a fixture that
  # trips a legacy rule must still fail with the legacy message, not with this layer's.

  # The header of each file names the run itself: TestRun/@name and @runUser say
  # which user ran it on which machine, TestSettings names the settings block and
  # carries its own id, and Deployment names the directory the run deployed into.
  # No count above reads any of them, and the row metadata check only requires
  # computerName to be uniform inside one file - so a header swapped for another
  # run's header, or a header naming a different machine than every row under it,
  # keeps all of them identical. VSTest writes the run name as
  # "<user>@<machine> <date> <time>" and derives the deployment root from it by
  # replacing "@", " " and ":" with "_"; a name in any other shape is only
  # reported, so a different producer format cannot turn a clean run red.
  $headerLost = @()
  $headerFileWarn = @()
  $settingsCount = 0
  $deploymentCount = 0
  $settingsId = $null
  $settingsName = $null
  $deploymentRoot = $null
  foreach ($child in @($xml.TestRun.ChildNodes)) {
    if ([string]$child.LocalName -ne 'TestSettings') { continue }
    $settingsCount++
    foreach ($sat in @($child.Attributes)) {
      if ([string]$sat.Name -eq 'id') { $settingsId = [string]$sat.Value }
      if ([string]$sat.Name -eq 'name') { $settingsName = [string]$sat.Value }
    }
    foreach ($sub in @($child.ChildNodes)) {
      if ([string]$sub.LocalName -ne 'Deployment') { continue }
      $deploymentCount++
      foreach ($dat in @($sub.Attributes)) {
        if ([string]$dat.Name -eq 'runDeploymentRoot') { $deploymentRoot = [string]$dat.Value }
      }
    }
  }
  $runName = [string]$xml.TestRun.name
  $runUser = [string]$xml.TestRun.runUser
  $runNameUser = $null
  $runNameMachine = $null
  $runNameCanonical = $false
  if ($runName -match '^(\S+)@(\S+) ([0-9]{4}-[0-9]{2}-[0-9]{2}) ([0-9]{2}:[0-9]{2}:[0-9]{2})$') {
    $runNameCanonical = $true
    $runNameUser = [string]$Matches[1]
    $runNameMachine = [string]$Matches[2]
  }
  $runUserMachine = $null
  $runUserLeaf = $null
  $runUserCanonical = $false
  if ($runUser -match '^([^\\]+)\\([^\\]+)$') {
    $runUserCanonical = $true
    $runUserMachine = [string]$Matches[1]
    $runUserLeaf = [string]$Matches[2]
  }
  $derivedRoot = 0
  $headerMachine = $null
  if ($settingsCount -ne 1) { $headerLost += "the file declares $settingsCount TestSettings block(s)" }
  if ([string]::IsNullOrWhiteSpace($settingsName)) { $headerLost += 'TestSettings names no settings block' }
  if ([string]::IsNullOrWhiteSpace($settingsId)) {
    $headerLost += 'TestSettings records no id'
  } elseif ($settingsId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
    if ($headerFileWarn.Count -lt 12) { $headerFileWarn += "$($e.file) TestSettings id '$settingsId' is not a GUID" }
  }
  if ($deploymentCount -ne 1) { $headerLost += "TestSettings declares $deploymentCount Deployment element(s)" }
  if ([string]::IsNullOrWhiteSpace($deploymentRoot)) { $headerLost += 'Deployment records no runDeploymentRoot' }
  if (-not $runUserCanonical) {
    $headerLost += "TestRun names runUser '$runUser', which is not machine and user"
  } else {
    $headerMachine = $runUserMachine
  }
  if (-not $runNameCanonical) {
    if ($headerFileWarn.Count -lt 12) { $headerFileWarn += "$($e.file) run name '$runName' is not user@machine plus a timestamp - the deployment root cannot be derived from it" }
  } else {
    if ($runUserCanonical) {
      if (-not [string]::Equals($runNameUser, $runUserLeaf, [System.StringComparison]::OrdinalIgnoreCase)) {
        $headerLost += "TestRun name says user '$runNameUser' while runUser says '$runUserLeaf'"
      }
      if (-not [string]::Equals($runNameMachine, $runUserMachine, [System.StringComparison]::OrdinalIgnoreCase)) {
        $headerLost += "TestRun name says machine '$runNameMachine' while runUser says '$runUserMachine'"
      }
    }
    $derivedExpect = $runName.Replace('@', '_').Replace(' ', '_').Replace(':', '_')
    if (-not [string]::IsNullOrWhiteSpace($deploymentRoot)) {
      if ([string]::Equals($deploymentRoot, $derivedExpect, [System.StringComparison]::Ordinal)) {
        $derivedRoot = 1
      } else {
        $headerLost += "Deployment root '$deploymentRoot' is not the run name with its separators replaced ('$derivedExpect')"
      }
    }
  }
  $boundRows = 0
  if ($null -ne $headerMachine -and [int]$attrValues['computerName'].Count -eq 1) {
    $rowMachine = ''
    foreach ($rk in @($attrValues['computerName'].Keys)) { $rowMachine = [string]$rk }
    if ([string]::Equals($rowMachine, $headerMachine, [System.StringComparison]::OrdinalIgnoreCase)) {
      $boundRows = $rowCount
    } else {
      $headerLost += "every row ran on '$rowMachine' while the run header names machine '$headerMachine'"
    }
  }
  $headerFacts = 0
  if ($runNameCanonical) { $headerFacts++ }
  if ($runUserCanonical) { $headerFacts++ }
  if ($derivedRoot -eq 1) { $headerFacts++ }
  $headerCollisions = 0
  foreach ($hid in @($runId, $settingsId)) {
    if ([string]::IsNullOrWhiteSpace([string]$hid)) { continue }
    if ($defIdCounts.ContainsKey([string]$hid) -or $execIdCounts.ContainsKey([string]$hid) -or $listNames.ContainsKey([string]$hid)) {
      $headerCollisions++
      if ($headerLost.Count -lt 12) { $headerLost += "header id $hid is also a test id, an execution id or a list id inside the file" }
    }
  }
  if (-not [string]::IsNullOrWhiteSpace($runId) -and -not [string]::IsNullOrWhiteSpace($settingsId) -and [string]::Equals($runId, $settingsId, [System.StringComparison]::OrdinalIgnoreCase)) {
    $headerCollisions++
    if ($headerLost.Count -lt 12) { $headerLost += 'the run id and the TestSettings id are the same id' }
  }

  $assemblyWarn += $assemblyFileWarn
  $headerWarn += $headerFileWarn

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
  "$($e.suite): rowAttributes=$rowCount unique execution ids=$($execIdCounts.Count) ordered row times=$timeOrdered rows within the run window=$inWindow uniform row fields=$uniformFields"
  "$($e.suite): structure=$sectionCount/7 single blocks entries=$entryCount matched entries=$matchedEntries paired execution ids=$pairedExecIds paired list ids=$pairedListIds lists=$listCount"
  "$($e.suite): executionIds=$($execByDefId.Count) distinct declared ids=$($defExecCounts.Count) paired rows=$pairedRowExecs paired result directories=$pairedRowDirs"
  "$($e.suite): displayNames=$displayNameChecked declared prefixes=$($displayNameChecked - $displayNameLost.Count) mismatched=$($displayNameLost.Count)"
  "$($e.suite): counters=$counterCount attributes required=$($requiredCounterNames.Count) missing=$($missingCounters.Count) malformed=$($malformedCounters.Count) identity=total=executed+notExecuted"
  "$($e.suite): runHeader=$headerFacts/3 settings=$settingsCount deployments=$deploymentCount derivedRoot=$derivedRoot bound rows=$boundRows header id collisions=$headerCollisions"
  "$($e.suite): assemblyDeclaration=$assemblyPairs storage/codeBase pairs=$($assemblyPairs - $assemblyPairLostCount) same file adapters=$($adapterValues.Count)"
  "$($e.suite): listVocabulary=$listKnown/$listCount canonical names=$listCanonical distinct names=$($listNames.Count - $listNameBlank - $listNameDuplicates.Count) unreferenced lists=$($unreferencedLists.Count)"
  "$($e.suite): runDeclarations=$declarationFacts/4 namespace=$([string](@('foreign','TeamTest/2010')[$declarationNamesOk])) timestamps=$declarationStamps ordered=$declarationOrdered explicit offsets=$declarationOffsets outside window=$declarationOutside"
  "$($e.suite): summaryOutcome=$summaryOutcome failureBuckets=$failureBucketTotal contradiction=$($summaryContradiction.Count)"

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
  if ($summaryContradiction.Count -gt 0) {
    throw "$($e.suite): $($e.file) records a ResultSummary whose verdict contradicts the Counters block it summarises while the total was $total - every count above is read from this block, so a stale or invented verdict keeps all of them looking exactly like the baseline reviewed. broken: [$(@($summaryContradiction) -join '; ')]. A TRX whose verdict does not match its own buckets was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($headerLost.Count -gt 0) {
    throw "$($e.suite): the run header does not agree with the rows it summarises while the total was $total - every count above is read from the rows and the counters, and nothing read the header that names the run, its settings block, the deployment root or the machine the rows ran on, so a header swapped for another run's keeps all of them identical. mismatched: $($headerLost -join ', '). A TRX whose header disagrees with its own rows was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($assemblyLost.Count -gt 0) {
    throw "$($e.suite): the definitions do not agree with themselves about the assembly they were loaded from while the total was $total - each definition records that assembly twice (UnitTest/@storage and TestMethod/@codeBase) and declares the adapter that ran it, and no count above read either record, so a definition pointing at another suite's dll keeps every class, method, argument row, outcome and header line identical. broken: [$(@($assemblyLost) -join '; ')]. A TRX whose two assembly records disagree was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($listVocabularyLost.Count -gt 0) {
    throw "$($e.suite): the declared lists do not name themselves consistently while the total was $total - every count above reads the rows, the counters and the ids, and nothing read a list name, so two lists that trade their names keep every one of them identical while the file tells the reader the rows ran somewhere else. mismatched: $($listVocabularyLost -join ', '). A TRX whose list names were rewritten was hand-merged or patched; regenerate it from a clean test run."
  }
  if ($declarationLost.Count -gt 0) {
    throw "$($e.suite): the run does not declare the schema it was written in and the instants it was created in while the total was $total - every count above is read from the rows, the counters, the header, the definitions and the lists, and nothing read TestRun/@xmlns, Times/@creation or Times/@queuing, so a file written by another tool or patched to swap its two stamps keeps all of them identical. mismatched: $($declarationLost -join ', '). A TRX whose own declarations were rewritten was hand-merged or patched; regenerate it from a clean test run."
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
if ($declarationWarn.Count -gt 0) {
  $shownDW = $declarationWarn
  if ($shownDW.Count -gt 12) { $shownDW = $shownDW[0..11] + "... ($($declarationWarn.Count - 12) more)" }
  Write-Host "::warning::a run declaration this gate cannot place - the creation and queueing instants are checked for an explicit offset and for their order, never against the row timings: $($shownDW -join '; ')"
}
if ($listWarn.Count -gt 0) {
  $shownLW = $listWarn
  if ($shownLW.Count -gt 12) { $shownLW = $shownLW[0..11] + "... ($($listWarn.Count - 12) more)" }
  Write-Host "::warning::a declared list this gate cannot pin - the writer's list vocabulary is two fixed id-to-name pairs, so anything else is only checked for presence, a non-blank distinct name and use: $($shownLW -join '; ')"
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
if ($durWarn.Count -gt 0) {
  $shown = $durWarn
  if ($shown.Count -gt 12) { $shown = $shown[0..11] + "... ($($durWarn.Count - 12) more)" }
  Write-Host "::warning::row times are not fully pinned - the recorded duration or the run's own Times window could not be checked: $($shown -join '; ')"
}
# Cross-file identity. Every number above is read from one results file at a time,
# so two files can describe the same run (or one file can be copied over another
# suite's name) and every per-file count stays exactly what the baseline reviewed.
$runSeen = @{}
foreach ($rk in @($runIdByFile.Keys | Sort-Object)) {
  $rv = [string]$runIdByFile[$rk]
  if ([string]::IsNullOrWhiteSpace($rv)) { continue }
  if ($runSeen.ContainsKey($rv)) {
    if ($fileIdentityLost.Count -lt 12) { $fileIdentityLost += "run id $rv is claimed by both $($runSeen[$rv]) and $rk" }
  } else {
    $runSeen[$rv] = $rk
  }
}
$dupExec = @()
foreach ($xk in @($fileOfExecution.Keys | Sort-Object)) {
  if ([int]$fileOfExecution[$xk].Count -gt 1) { $dupExec += $xk }
}
foreach ($xk in @($dupExec | Select-Object -First 12)) {
  if ($fileIdentityLost.Count -lt 12) { $fileIdentityLost += "execution id $xk appears in more than one results file ($([string]::Join(', ', @($fileOfExecution[$xk]))))" }
}
"file identities=$($runIdByFile.Count) distinct run ids=$($runSeen.Count) cross-file duplicate execution ids=$($dupExec.Count)"
if ($counterVocabularyWarn.Count -gt 0) {
  $shownCounterVocab = $counterVocabularyWarn
  if ($shownCounterVocab.Count -gt 12) { $shownCounterVocab = $shownCounterVocab[0..11] + "... ($($counterVocabularyWarn.Count - 12) more)" }
  Write-Host "::warning::a Counters attribute this gate does not know - it is recorded but not cross-checked against the rows: $($shownCounterVocab -join '; ')"
}
if ($runIdWarn.Count -gt 0) {
  $shownRunId = $runIdWarn
  if ($shownRunId.Count -gt 12) { $shownRunId = $shownRunId[0..11] + "... ($($runIdWarn.Count - 12) more)" }
  Write-Host "::warning::a run id is not a GUID - the file may have been patched by hand: $($shownRunId -join '; ')"
}
if ($fileIdentityLost.Count -gt 0) {
  $shownFileIdentity = $fileIdentityLost
  if ($shownFileIdentity.Count -gt 12) { $shownFileIdentity = $shownFileIdentity[0..11] + "... ($($fileIdentityLost.Count - 12) more)" }
  throw "the results files do not describe distinct test runs - every count above is read from one file at a time, so a file copied over another suite's name, or one run exported twice, doubles what this gate reports while each file still looks exactly like the baseline reviewed it. mismatched: $($shownFileIdentity -join '; ') . A results set whose files share a run id or an execution id was copied or exported twice; regenerate each file from its own test run."
}
if ($headerWarn.Count -gt 0) {
  $shownHeader = $headerWarn
  if ($shownHeader.Count -gt 12) { $shownHeader = $shownHeader[0..11] + "... ($($headerWarn.Count - 12) more)" }
  Write-Host "::warning::a run header this gate cannot derive - the run name or the settings id has an unexpected shape, so only its internal consistency was checked: $($shownHeader -join '; ')"
}
if ($assemblyWarn.Count -gt 0) {
  $shownAssembly = $assemblyWarn
  if ($shownAssembly.Count -gt 12) { $shownAssembly = $shownAssembly[0..11] + "... ($($assemblyWarn.Count - 12) more)" }
  Write-Host "::warning::a definition-side assembly or adapter declaration this gate cannot cross-check - the adapter naming scheme is not pinned, so only its presence and its uniformity were verified: $($shownAssembly -join '; ')"
}
"combined tests=$all"

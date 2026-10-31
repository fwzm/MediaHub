# TEST_MATRIX — MediaHub 门禁矩阵

本文件只写**可复现的门禁事实**。每条都标注证据级别：

- `EXECUTED`：命令真跑过，结果来自日志或 CI run。
- `SOURCE_REVIEWED`：只读了 CI 配置或测试源码，未在本机执行。
- `NOT_RUN`：已知存在但本轮未执行。

**刷新纪律**：数字随提交漂移，任何一行都必须能追到一个 SHA 或 run id。写入新数字前先核对 `git rev-parse HEAD`，不要把旧 SHA 的计数挂到新 head 下。

---

## 1. Windows 套件

| 套件 | 工程 | 计数来源 | 本机最近实测 | CI 门禁位置 |
| --- | --- | --- | --- | --- |
| `MediaHub.Tests` | `windows/tests/MediaHub.Tests` | xUnit `[Fact]` **1475** + `[Theory]` **100**，83 个文件（**源码静态计数，SOURCE_REVIEWED**；不等于执行数，且随提交漂移） | **1890 / 0 / 0**（EXECUTED，2026-10-30 @ `e7779b7f`，run `41003347790`） | `windows-ci.yml` → Unit and contract tests (MediaHub.Tests) |
| `MediaHub.WebDav.Tests` | `windows/tests/MediaHub.WebDav.Tests` | 5 个文件，`[Fact]` 92 + `[Theory]` 3（SOURCE_REVIEWED） | **104 / 0 / 0**（EXECUTED，同上 run） | 同文件 → Unit and contract tests (MediaHub.WebDav.Tests) |

**为什么静态计数与执行数不等**：`[Theory]` 的每行 `InlineData` 独立计一例。静态计数只能当量级参考，**任何门禁比较都必须用执行数**。

**本文件自身的教训（2026-10-30）**：首次写入时静态计数记的是 1459 / 99，同一小时复核已变成 **1475 / 100** —— 并行执行者新增了 `BackupListTests.cs`；`--required-suite` 也曾被我误记成 61 处，实测是 **60**。⇒ 写入任何计数前必须当场重新测量，并把测量命令一起写进本文件（见 §5）。

### windows-ci 的额外门禁（SOURCE_REVIEWED，来自 `.github/workflows/windows-ci.yml`）

| 步骤 | 作用 | 失败即红的理由 |
| --- | --- | --- |
| Fail if a suite ran nothing or lost tests | 解析 TRX，与**提交基线** `windows/tools/test-suite-baseline.json` 比对：`actual < baseline` 判红、`actual > baseline` 判绿并 `::warning` 提示更新 | 套件整体消失或缩水时 dotnet test 仍打印 Passed，这条把「跑了个空」变成红。**2026-10-30 起改用基线**：此前是硬编码 floor 400/100，而套件已涨到 1920 例，丢 79% 用例仍判绿（见 §6） |
| Licence report | 先 dotnet restore 两个**故意不入 sln** 的探针工程（EngineProbe / ResumeProbe） | 不 restore 探针工程时 CI 必红，而本机有历史产物会假绿 |
| Licence surface guard | `tools/check-licence-surface.ps1` 比对 notices / SBOM / 工程漂移 | 新增工程未进 SBOM 或 sln 时拦下 |
| Engine integration probe | 真实 libmpv 无窗口解码对拍 | 打包与引擎回归的护栏 |

> 新增测试工程必须同时满足：① 并入 `MediaHub.Windows.sln`（否则 CI 不 restore/build）；② 若走独立套件，要在上述下限表里登记。

## 2. Android 套件

| 门禁 | 机制 | 最近实测 |
| --- | --- | --- |
| 单元测试 | `./gradlew testDebugUnitTest :core:model:test :provider:api:test :metadata:test --no-daemon --continue --rerun-tasks --no-build-cache` | `uniqueTests` **2501**、`--required-suite` **60 条**（EXECUTED，2026-10-30 @ `d6331139`，run `40995411720`）。⚠️ 之后多次 run 因同分支连续 push 被 concurrency 取消（`40993937937`、`41003347784`）—— 那是**取消，不是失败**，不能记成 PASS 也不能记成 FAILED |
| 套件必须在场 | `--required-suite <FQCN>` 逐个声明 | 套件整类消失时门禁必红；**新建测试类不登记就等于没有门禁** |
| 新鲜证据 | 先清早前单元测试报告目录 | 复用旧报告会被这条挡住 |
| VI（visual-instrumentation） | API 32 / API 36 双作业 | 各 **19 unique mandatory**（EXECUTED，同上） |
| 模拟器脚本单测 | `python3 -m unittest discover -s .github/scripts/tests` | 改 `.github/scripts/` 任何脚本必须同时跑这条（SOURCE_REVIEWED） |

## 3. 状态词口径

| 词 | 含义 |
| --- | --- |
| `EXECUTED_PASS` | 命令真跑且通过；附 SHA 或 run id |
| `EXECUTED_PASS(emulator)` | 模拟器上跑通；真机未跑 |
| `GUI_UNVERIFIED` | 未经人工点按确认的界面行为（UIA INVOKED 不算） |
| `SERVER_UNVERIFIED` / `DEVICE_UNVERIFIED` | 真服 / 真机维度的证据缺失 |
| `SOURCE_REVIEWED` | 只读源码或 CI 配置得出 |
| `NOT_RUN` | 已知需要但本轮未执行 |
| `BLOCKED` / `SIGNING_BLOCKED` / `RELEASE_BLOCKED` | 外部条件（授权 / 签名 / 许可证）不满足 |

**模拟器结果不得替代真机状态**：VI 全绿仍记 `DEVICE_UNVERIFIED`。

## 4. 本文件的已知边界

- 不记录「计划要跑」的东西；只记跑过的或源码可查的。
- 执行数会随提交漂移（同一分支上多个执行者并行提交时尤其明显）。**引用数字必须连 SHA 一起引**，例如「1858 @ 055c85c3」，而不是「现在是 1858」。
- `uniqueTests` 从 2495 到 2501 是 W15 Kotlin 用例带进来的，不是 Windows 侧改动所致；跨执行者协作时这类变化必须在账本里归因。

## 5. 测量命令（复用这些，别手数）

```bash
cd D:/mh-w6

# Windows 静态计数（会漂，只作量级参考）
echo "Fact=$(grep -h '\[Fact\]'   windows/tests/MediaHub.Tests/*.cs | wc -l)"
echo "Theory=$(grep -h '\[Theory\]' windows/tests/MediaHub.Tests/*.cs | wc -l)"
echo "files=$(ls windows/tests/MediaHub.Tests/*.cs | wc -l)"

# Android required-suite 条数（-- 分隔符不可省）
grep -c -- '--required-suite ' .github/workflows/android-ci.yml

# windows-ci 的套件下限表
grep -n 'Floor = ' .github/workflows/windows-ci.yml

# 执行数：只认 CI 日志的 Passed! 行或本机 dotnet test 汇总行
#   gh api "repos/fwzm/MediaHub/actions/runs/<run_id>/jobs" --jq '.jobs[0].id'
#   gh api "repos/fwzm/MediaHub/actions/jobs/<job_id>/logs" | grep -a 'Passed!  - Failed:'
```

**grep 的坑**：`grep -c '--required-suite '` 会被当成选项解析，必须写 `grep -c -- '--required-suite '`。

## 6. 门禁失效的一次真实案例（2026-10-30）

**现象**：`windows-ci` 的套件门禁自述意图是「a suite that quietly loses half its cases still prints Passed!」，
但下限是硬编码的 `Floor = 400`（MediaHub.Tests）/ `100`（WebDav），取自 2026-10-24 的 438 / 104。
到 10-30 套件实际执行 **1920** 例 ⇒ 删掉 **1520 个用例（79%）** 仍然判绿。
门禁防的是「腰斩」，实际只防「砍到 20% 以下」。

**为什么不是「把 400 改成 1920」**：下次用例再涨，同样的洞原样重现。数字写死在 YAML 里就一定会腐坏，
因为它没有任何东西提醒你去改。

**修法**：`windows/tools/test-suite-baseline.json` 进仓库，CI 与它比对：

| 情况 | 结果 |
| --- | --- |
| actual < baseline | 红（用例被删除或静默跳过），报错里写明「若确实要删，在同一次提交里下调基线」 |
| actual > baseline | 绿 + `::warning`（新增用例不是失败，但提示更新基线） |

**验证**（把 CI 里那段 PowerShell 原样抽出，用真实 TRX 夹具跑）：

| 对照 | 场景 | 期望 | 实测 |
| --- | --- | --- | --- |
| NC0 | 1920 / 1920 | 绿 | GREEN |
| NC1 | 1919（少 1 例） | 红 | RED |
| NC2 | 400（丢 1520 例） | 红 | RED ← 旧 `Floor=400` 在此会判绿 |
| NC3 | 1921（涨 1 例） | 绿 + warning | GREEN + ::warning |
| NC4 | 1 个 failed | 红 | RED |
| NC5 | 基线文件缺失 | 红 | RED |
| NC6 | 还原后 | 绿 | GREEN |

真实 CI：`ffe5a9fb` 的 windows-ci run `41025538206` success，日志含
`MediaHub.Tests: total=1920 baseline=1920`、`MediaHub.WebDav.Tests: total=104 baseline=104`、`combined tests=2024`。

**附带踩到的陷阱**：基线最初写成含中文注释的 JSON，`PowerShell 5.1` 的 `Get-Content -Raw`
把无 BOM 的 UTF-8 当 ANSI 解码，中文损坏、字符串截断，`ConvertFrom-Json` 报 `Invalid array passed in`。
文件本身是合法 UTF-8（node 的 `JSON.parse` 通过），坏的只是 PS 5.1 的读取路径。
⇒ **基线文件刻意保持纯 ASCII**，理由写在 `_comment` 里。

### 6.1 基线自身的校验（2026-10-30 追加）

第一版基线只做「实际数 vs 期望数」比较，`expected` 字段没有任何约束。实测发现：

| baseline expected | 实际执行 | 结果 |
| --- | --- | --- |
| 0 | 5 例 | **绿**（`total >= 0` 恒成立） |
| -5 | 1 例 | **绿** |

也就是说：只要有人把基线写成 0（复制粘贴、误删、脚本生成），门禁就退回形同虚设且无提示。
**保护机制的配置本身也需要被保护。**

现在 `expected` 必须是正整数，`file` / `suite` 不能为空，TRX 缺 `ResultSummary/Counters` 会报明确错误
（而不是让 `[int]$null` 变成 0 再报一个误导性的 dropped-cases）。

**一个只有真跑 PowerShell 才暴露的坑**：`"$baselinePath: ..."` 里的 `$baselinePath:` 会被解析成
驱动器限定符（像 `D:`），报 `Variable reference is not valid` 并让整个脚本解析失败 —— 必须写 `${baselinePath}:`。
光看 YAML 完全看不出来。

完整边界矩阵（全部实跑）：

| 场景 | 期望 | 实测 |
| --- | --- | --- |
| 1920 / 104 正常 | 绿 | GREEN |
| total = 0（跑了个空） | 红 | RED |
| baseline expected = 0 | 红 | RED |
| baseline expected = -5 | 红 | RED |
| TRX 缺 Counters | 红 | RED |
| 基线文件缺失 | 红 | RED |
| 增长 1921 | 绿 + warning | GREEN |
| 有 failed | 红 | RED |
| 少 1 例 1919 | 红 | RED |
| 丢 1520 例 400 | 红 | RED |

### 6.2 基线机制被第二个使用者正确使用的实证（2026-10-30 17:35）

`ffe5a9fb` 引入基线后不到 1.5 小时，另一个执行者的 `2adb207f`（W28）就新增了 7 个 C# 用例，
并在同一次提交里把 `test-suite-baseline.json` 的 `expected` 从 **1920 改成 1927**。

它的 CI 日志（run `41034588485`）证实两者吻合：

```
Passed!  - Failed: 0, Passed: 1927
MediaHub.Tests: total=1927 baseline=1927
```

**这是本轮最有价值的验证**：基线不只是一个「我写的更严的检查」，它对**第二个使用者**也是可理解、
可按提示动作的 —— 看到 `::warning::suite grew beyond the baseline` 就去改基线，一次做对。
如果当初选择的是「把 Floor 从 400 改成 1920」，对方加 7 个用例时不会有人去动那个常量，
下次再有人删用例就又回到静默放行。

**附带结论**：基线数字会随并行开发频繁变动。这不是缺陷，是设计意图 —— 变动以一条独立 diff
出现在 review 里，比藏在 YAML 常量里可见得多。

### 6.3 门禁脚本在两种 PowerShell 下行为一致（2026-10-30 补测）

6.1 的边界矩阵是用本机 **PowerShell 5.1** 跑的，但 `windows-ci` 里该步骤声明的是 `shell: pwsh`（pwsh 7）。
这是一个**验证缺口**：脚本里用到 `ConvertFrom-Json`、`[int]` 转换、`-lt` 比较与 `::warning::` 输出，
这些在 5.1 与 7 之间并非全部同义。

本机装有 pwsh 7.6.9，用它把同一组 9 条边界重跑一遍，逐条对比：

| 场景 | PS 5.1 | pwsh 7 | 期望 |
| --- | --- | --- | --- |
| 正常（1927/104） | GREEN | GREEN | GREEN |
| total = 0 | RED | RED | RED |
| expected = 0 | RED | RED | RED |
| expected = -5 | RED | RED | RED |
| 缺 Counters | RED | RED | RED |
| 少 1 例 | RED | RED | RED |
| 多 1 例 | GREEN | GREEN | GREEN |
| 有 failed | RED | RED | RED |
| 基线缺失 | RED | RED | RED |

**差异 0 条**。脚本在两代 PowerShell 上可互换，CI 用的 pwsh 7 也就是本机验证过的那套行为。

> **踩坑记录（测试脚本自身）**：第一版对比脚本用 `execSync(..., { shell: 'bash' })` 调 PowerShell，
> bash 无法解析 `C:\Program Files\...` 这类路径，两边都只拿到 WSL 的报错文本 —— 于是**所有用例都被判成 GREEN**，
> 看起来像「5 处行为不一致」。改用 `spawnSync(exe, args)` 直接起进程后结果才正常。
> 教训：**对比两种 shell 时，调用方式本身不能引入第三个 shell**。

### 6.4 只比总场数仍可被「等量替换」绕过（2026-10-30 19:5x 追加，W6-113k）

上一版（`ffe5a9fb` + `d6cdc7ee`）只校验**每套件的用例总数**。它挡住「少跑」和「配置写成 0」，但挡不住
**删 N 例 + 新增 N 个新例**：总数不变 ⇒ 判绿。而这正是该门禁自述要挡的场景。

- **补法**：基线每套件多带一张 `classes` 表（类名 → 用例数）。类归属取自 TRX `TestDefinitions` 的
  `TestMethod.className`，**不取 `UnitTestResult.testName`**：xUnit 把 Theory 的数据参数拼进结果名，
  参数里可能含点号（`(1.5, 2026.10.30)`），按结果名切类会切出错位的「类」。
- **判定**：基线里的类必须仍存在且用例数 `>=` 基线值，否则 RED（提示「总数相同不代表跑的是同一批用例」）；
  新类或类内变多 → GREEN + `::warning`，让漂移可见而不阻塞别人。
- **刷新路径**：`windows/tools/update-test-suite-baseline.ps1`，按真实 TRX 重算；无 `-AcceptRemoval` 时
  拒绝下调 → 「刷新基线」不能当作把红改绿的手段。
- **闸门搬出 YAML**：`windows/tools/check-test-suite-baseline.ps1`，负向对照跑的就是 CI 跑的同一份代码。

| 对照 | 输入 | 预期 | 实测（pwsh7 / PS5.1） |
|---|---|---|---|
| NC0 | 基线与真实结果一致 | PASS | PASS / PASS |
| NC1 | 总数少 1 | RED | RED / RED |
| **NC2** | **总数相同、类分布不同（删除 1 类、另一类减少、新增 1 类）** | **RED** | **RED / RED** |
| NC3 | 整类消失 | RED | RED / RED |
| NC4 | 出现新类 | GREEN+warning | GREEN+warning ×2 |
| NC5 | 某类用例数变大 | GREEN+warning | GREEN+warning ×2 |
| NC6 | 有 failed | RED | RED / RED |
| NC7 | 有 notExecuted | RED | RED / RED |
| NC8 | 基线缺 classes 表 | RED | RED / RED |
| NC9 | classes 表为空 | RED | RED / RED |
| NC10 | expected=0 | RED | RED / RED |
| NC11 | expected<0 | RED | RED / RED |
| NC12 | 类计数<1 | RED | RED / RED |
| NC13 | 刷新脚本遇缩减，无 -AcceptRemoval | RED 且文件不变 | RED / 文件逐字节不变 |
| NC14 | 同上 + -AcceptRemoval | 写入 5 / 2 | PASS |
| NC15 | 真实 CI TRX 重生成基线 | 逐字节一致 | 一致 |
| **NC16** | **NC2 的输入交给 HEAD 旧 total-only 闸门** | **GREEN** | **GREEN**（旧闸门确实放行 → NC2 的红是本次收口所得） |
| NC17 | 真实数据 1943 + 104 | PASS | PASS |

> 数据源：CI run `41048006881`（head `52cda428`）的 `test-results/*.trx`，因此基线钉的是该 commit 的 CI 真实行为。
> 台账位置：`D:/mh-verify/r7-nc/run-nc.cjs`（18 条对照一次跑完，两代 PowerShell 各跑一遍）。

**CI 实测（EXECUTED，head `0cef5d792ea06ebe34fddcff550c98bdf0d895a7`）**：windows-ci run `41051026115` success，台阶日志逐行为 `MediaHub.Tests: total=1943 baseline=1943 failed=0 notExecuted=0` / `MediaHub.Tests: classes=112 baseline=class table 112 entries` / `MediaHub.WebDav.Tests: total=104 baseline=104 failed=0 notExecuted=0` / `MediaHub.WebDav.Tests: classes=4 baseline=class table 4 entries` —— 即 CI 跑的就是带类表的这份闸门，不是只改文档。同日 Android CI run `41051026121`（同 head）在入账时仍 in_progress，本行不为其作任何结论。

**第三方采纳实证（EXECUTED，2026-10-30 20:08）**：并行写者 W31 `06b6890d` 新增 9 例后把基线 1943 → 1952、类表 112 → 113；同 head windows-ci `41053179818` success，日志 `total=1952 baseline=1952` / `classes=113 baseline=class table 113 entries`。相对 W28 只刷总数，这次连类表一起改 —— per-class 口径被第二个使用者按设计跟上。

### 6.5 只比「每类用例数」仍可被「类内换方法」绕过（2026-10-30 22:3x 追加，W6-113l）

per-class 版（`W6-113k`）挡的是「跨类搬用例」，挡不住**同一个类里删掉一个方法、再加一个新方法**：
类内计数不变、总数也不变 ⇒ 判绿。这与该门禁自述要挡的 *equal totals do not prove the same cases ran*
是同一个洞的再下一层。

- **补法**：基线每套件再带一张 `methods` 表（类名 → 方法名数组）。方法名取 TRX `TestDefinitions` 的
  `TestMethod.className` + `TestMethod.name`，**剥掉第一个 `(` 起的 Theory 参数**后去重 —— 数据行由
  `classes` 的用例数管，方法表只管「这个方法还在不在」。
- **判定**：基线里的方法必须仍由其所属类上报，否则 RED，失败消息直接点名（`gone: <Class>.<Method>`）；
  方法新增或类内变多 → GREEN + `::warning`（与类表同口径：漂移可见，但不阻塞别人）。
- **配置校验**：缺/空 `methods` 表、`classes` 里有而 `methods` 里无的类、空方法名、类内重名，一律 throw。
- **明示残留限制**：Theory 的**数据行（参数值）不钉**，只钉方法名 —— 只改 Theory 数据仍判绿（NC16）。
- **刷新脚本**：`update-test-suite-baseline.ps1` 同步重写 `methods` 表；`_comment` 由脚本拥有（每次刷新
  归一化为脚本内置文本，NC24 显式验证），下调仍需 `-AcceptRemoval`。
- **规模取舍**：基线 **7,659 B → 122,204 B**，`file` / `expected` / `classes` 三栏与上一版逐字段比对
  **全部不变**，只多出 `methods`。用 100+ KB 换「点名缺失方法」的可诊断性与 review 里逐行可见的 diff。

| 对照 | 输入 | 预期 | 实测（pwsh7 / PS5.1） |
|---|---|---|---|
| NC0 | 基线与真实结果一致 | PASS | PASS / PASS |
| NC1 | 总数少 1 | RED | RED / RED |
| NC2 | 总数相同、类分布不同 | RED | RED / RED |
| NC3 | 整类消失（计数搬到别类以保持总数） | RED | RED / RED |
| NC4 | 出现新类 | GREEN+warning | GREEN+warning ×2 |
| NC5 | 某类用例数变大 | GREEN+warning | GREEN+warning ×2 |
| NC6 | 有 failed | RED | RED / RED |
| NC7 | 有 notExecuted | RED | RED / RED |
| NC8 | 基线缺 `classes` 表 | RED | RED / RED |
| NC9 | `classes` 表为空 | RED | RED / RED |
| NC10 | `expected=0` | RED | RED / RED |
| NC11 | `expected<0` | RED | RED / RED |
| NC12 | 类计数<1 | RED | RED / RED |
| **NC13** | **同类内换方法：total 与每个类计数都不变** | **RED** | **RED / RED** |
| **NC14** | **NC13 的同一输入交给 round 7 的 per-class 门禁** | **GREEN** | **GREEN**（旧门禁确实放行 ⇒ NC13 的红是本轮收口所得） |
| NC15 | 方法跨类搬移、两侧计数不变 | RED | RED / RED |
| NC16 | 只改 Theory 数据参数 | PASS（明示限制） | PASS / PASS |
| NC17 | 基线缺 `methods` 表 | RED | RED / RED |
| NC18 | `methods` 表为空 | RED | RED / RED |
| NC19 | `classes` 有而 `methods` 无的类 | RED | RED / RED |
| NC20 | 类内方法重名 | RED | RED / RED |
| NC21 | 失败消息点名缺失方法 | 含 `MediaHub.Tests.AlphaTests.A_one` | 命中 |
| NC22 | 刷新脚本遇方法消失、无 `-AcceptRemoval` | RED 且文件不变 | RED / RED，文件未变 |
| NC23 | 同上 + `-AcceptRemoval` | 写入新方法集 | PASS（`A_gone` 被移除） |
| NC24 | 刷新脚本二次运行 | 逐字节稳定 + `suites` 数据不变 | 稳定；`_comment` 归一化为脚本内置文本 |
| NC25 | 真实 CI TRX 重生成基线 | 逐字节一致 | 一致 |
| NC26 | 真实 1952 + 104 对已提交基线 | PASS | PASS，含 `methods=1637` / `methods=95`、`combined tests=2056` |
| NC27 | 默认相对路径冒烟 | PASS | PASS |

> 数据源：CI run `41055690826`（head `48d5feb7`）的 `test-results/*.trx`（windows 2,719,155 B / webdav 145,266 B），
> 因此基线钉的是该 commit 的 CI 真实行为。
> 台账位置：`D:/mh-verify/r9/run-nc.cjs`（28 条对照一次跑完，两代 PowerShell 各跑一遍）。

**CI 实测验收（EXECUTED_PASS）**：head `f045d34c` 的 windows-ci run `41072599036` **completed/success**（`pull_request` 事件），
日志逐行命中台阶：

- `MediaHub.Tests: total=1952 baseline=1952 failed=0 notExecuted=0`
- `MediaHub.Tests: classes=113 baseline=class table 113 entries`
- `MediaHub.Tests: methods=1637 baseline=method table 1637 names`
- `MediaHub.WebDav.Tests: total=104 baseline=104 ... classes=4 ... 4 entries ... methods=95 baseline=method table 95 names`
- `combined tests=2056`

**迁移验证（EXECUTED，第 29 例）**：把 head `48d5feb7` 上的**旧格式基线**（无 `methods` 表）交给新闸门 ⇒ 两代 PowerShell 均 **exit 1** 并抛出
`entry for MediaHub.Tests has no methods table - the per-method check would be vacuous`。即旧基线不会在新闸门下**静默变绿**，
而是当场失败并指明缺哪张表 —— 这是本轮新校验的直接可用性证据。

## 6.6 诊断导出契约复验（W6-113n，no-op）：25 例钉住「内存快照 + 滚动目录指针」

W6-113 round 10 按指令复验 W14 口径与 Android 端口的一致性，**结论一致，不改代码**；此节记录该契约由哪组用例钉住，
便于以后改导出格式时对照（本轮实测 `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj
--filter "FullyQualifiedName~DiagnosticsReportTests|FullyQualifiedName~LogFileSinkTests"` ⇒ exit 0、`通过数: 25`，
16 `DiagnosticsReportTests` + 9 `LogFileSinkTests`）。

| 契约面 | 点名要求 | 钉住它的用例 |
|---|---|---|
| 旧签名兼容 | 2/3/4 参旧调用输出逐字不变 | `A_missing_rolling_directory_leaves_the_header_byte_for_byte_as_before` |
| 目录缺省与存在 | `""`/空白/null 视为缺省；缺目录自动创建；有目录写进文件 | `An_empty_rolling_directory_is_treated_as_absent`、`Write_creates_a_missing_directory_and_reports_bytes_and_entries`、`Write_carries_the_rolling_directory_into_the_file_it_writes` |
| 路径含十六进制 | 指针行不含 `***`（`LongHexRegex` 不得吃掉临时目录的 32 位十六进制段） | `The_hexadecimal_segment_of_the_directory_survives_redaction_untouched` |
| 敏感 URL | 目录行原样回显，同报告内条目仍脱敏（`api_key=abc123 → ***`） | `The_directory_is_echoed_verbatim_while_entries_in_the_same_report_are_still_redacted` |
| 日志正文不内嵌 | 只给目录指针，不内联滚动文件内容 | `The_report_points_at_the_rolling_directory_instead_of_inlining_the_file` |

> 两处「不同表面」的边界（勿混）：Windows 的指针行位于**报告头**（`DiagnosticsReport.RollingDirectoryPrefix`，L30/L50-54），
> 刻意**不过** `LogService.Redact`；Android 端口的 `the_information_line_carries_the_counts_and_never_the_directory` 管的是
> **日志信息行**不含目录，二者不冲突。若未来改口径，须同步 `windows/docs/W14-DIAGNOSTIC-LOG-FILES.md`、
> `docs/android-source-sync-transfer.md` 与两侧用例，并重跑本组 25 例。

## 6.7 第四层口径：per-method 用例数（W6-113p）

§6.5 把口径推到「总数 → 每类计数 → 方法集合」三层，但第三层只钉**方法名**。xUnit 的 `[Theory]` 是「一个方法 + N 行数据」，同类的两个 theory 之间挪走一行数据（A−1、B+1）时，总数、每类计数、方法集合**三层全都看不到** —— 这正是 §6.5 的 NC16 写明的残留限制。本轮把 `methods` 由「类 → 方法名数组」升级为「类 → { 方法: 用例数 }」，并给新结构配自洽校验。

### 基线字段（2026-10-31 起）

| 字段 | 含义 | 校验 |
|---|---|---|
| `expected` | 套件用例总数 | 实际 total < 基线 → RED |
| `classes` | 类名 → 该类的用例数 | 类缺失/缩减 → RED；新增/变大 → GREEN + warning |
| `methods` | 类名 → { 方法名 → 该方法的用例数 } | 方法缺失或**用例数下降** → RED；新增/变大 → GREEN + warning |
| 结构自洽 | —— | 两表类集合一致；每方法用例数 ≥ 1；类内各方法用例数之和 == `classes` 里该类计数；否则 RED |
| 旧格式 | 2026-10-30 的「方法名数组」 | 明确 throw 并提示用 `update-test-suite-baseline.ps1` 刷新，不静默放行 |

`check-test-suite-baseline.ps1` 日志新增一行 `methodCases=N baseline=method case table N cases`（原 `methods=` 行保留）；TRX 的 definition 数 ≠ result 数时打 `::warning::` 而非静默。`update-test-suite-baseline.ps1` 把旧数组格式按 −1（未知）读，使迁移时旧格式不会被误判成「用例数下降」，移除判定扩展为「类消失 / 方法消失 / 方法用例数下降 / total 下降」，只有显式 `-AcceptRemoval` 才允许降。

### 负向对照（23 例，两代 PowerShell 逐例判定一致）

| 例 | 输入 | 期望 | 观测 |
|---|---|---|---|
| NC28 | 同类内挪一行 theory 数据（三层可见量全不变） | RED | 新闸门 RED，消息 `G_alpha (baseline 2 cases, now 1)` |
| NC29 | 同 NC28 的输入交给 round 9 门禁 | GREEN | GREEN ⇒ NC28 的红确为本轮收口所得 |
| NC30 | 某方法用例数 +1 | PASS + warning | `G_beta (+1 cases)` |
| NC31 | 旧「方法名数组」格式基线 | RED | 提示 2026-10-30 format，要求刷新 |
| NC32 | 类内方法用例数之和 ≠ `classes` 计数 | RED | 配置自洽失败 |
| NC33 | 某方法用例数 = 0 | RED | 配置自洽失败 |
| NC34 | 某类只在 `methods` 里、`classes` 没有 | RED | 两表类集合不一致 |
| NC35 | TRX definition 数 6 ≠ result 数 5 | PASS + `::warning::` | `6 test definition(s) against 5 result(s)` |
| R1–R7 | round 9 回归：total 降 / 类增长 / failed≠0 / 同类换方法 / 缺 methods 表 / 方法跨类搬 / 数据行参数值变化 | RED×5 + PASS×2 | 与 §6.5 判定一致 |
| NC38 | 无 `-AcceptRemoval` 时刷新脚本遇到下调 | 拒绝 | 消息含 `G_alpha (cases 2 -> 1)` |
| NC39 | 带 `-AcceptRemoval` 刷新 | 写入降后计数 | `G_alpha 1 / G_beta 2`，sum == classes |
| NC40 | 旧格式基线直接刷新 | 升级为计数格式 | 通过 |
| NC41 | round 9 旧格式基线（122,204 B）× 真实 TRX | RED | 要求迁移 |
| NC43 | 同 NC41 输入交给 round 9 门禁 | GREEN | 迁移闸门只在新版生效 |
| NC36 | 真实 TRX × 已提交基线 | PASS | 含 `methodCases=1952 baseline=method case table 1952 cases`、`combined tests=2056` |
| NC37 | 真实 TRX 重生成基线 | 逐字节一致 | 与提交版 sha256 相等 |
| NC42 | 缺省相对路径冒烟 | PASS | `-BaselinePath`/`-ResultsDir` 省略时按脚本目录解析 |

### 数据源与规模

数据源取自 CI run `41095080796`（head `701bb547`，与 round 11 验收同一 merge commit `47fb6f5075d21ee70a568a8cb460256b60d200bf` 对应的 TRX）：`windows-tests.trx` 2,719,155 B、`webdav-tests.trx` 145,266 B。基线由新刷新脚本重生成后为 **127,587 B**（原 122,204 B，+5,383 B），规模换取的是「方法用例数下降即点名到 `Class.Method` 与两侧计数」。

### 残留限制

- **数据行的参数值**：只钉「某方法有几行」时，行内容被改写（行数不变）仍为 PASS（R7 / round 9 NC16）。**该条已由 §6.8（W6-113q，2026-10-31）收口** —— `data` 表钉住每个方法的参数行指纹，代价是基线 127,587 B → 140,085 B。
- **测试重命名等同移除+新增**，表现为 RED + warning，需人工确认后刷新，这与 §6.5 的口径一致。
- **口径依赖 TRX 的 TestDefinitions**：若 runner 输出结构变化导致 definition 无法解析，会走 `::warning::` 与配置校验，而不是静默判绿。

**exact-head CI 验收（EXECUTED）**：head `8ec0d890`（merge commit `e79957c5c903…`）的 windows-ci **`41099478575` completed/success**（`pull_request`）：日志 `D:/mh-verify/r12/ci-41099478575-windows.log` 逐行命中 `MediaHub.Tests: total=1952 baseline=1952 failed=0 notExecuted=0` / `classes=113 baseline=class table 113 entries` / `methods=1637 baseline=method table 1637 methods` / **`methodCases=1952 baseline=method case table 1952 cases`** / `MediaHub.WebDav.Tests: total=104 baseline=104` / `classes=4 ... 4 entries` / `methods=95 ... 95 methods` / `methodCases=104 ... 104 cases` / `combined tests=2056`；licence `check-licence-surface  checks=27 failed=0 warnings=1 skipped=0`，`sbom-inventory` / `sbom-source-revision`（对 merge commit `e79957c5c903` 判祖先）/ `sdk-pin-matches-report 10.0.113` 全 PASS；探针 `MPV_PROBE PASS … GPU/HDR/audio output NOT exercised -> UNVERIFIED` 与 `ENGINE_PROBE PASS …` 口径不变。同 head Android CI `41099478811` 取证时 in_progress（本轮无 Android 代码改动）。 ⇒ 新的第四层口径在 CI 上确实打印了自己的台阶行，不是只在本机绿。

**文档提交的 exact-head CI 验收（EXECUTED）**：head `4461140b`（docs 三文件）的 windows-ci **`41100655895` completed/success**，台阶行与 `8ec0d890` 完全一致（`methodCases=1952 baseline=method case table 1952 cases` / WebDav `104 … 104 cases` / `methods=1637 … 1637 methods` / `combined tests=2056`）、licence `check-licence-surface  checks=27 failed=0 warnings=1 skipped=0` 且 `sbom-source-revision` / `sdk-pin-matches-report` PASS、探针 `MPV_PROBE`/`ENGINE_PROBE` PASS 且 GPU/HDR/audio 未跑口径不变；同 head Android CI `41100655793` 取证时 in_progress，而 core/feature/app 三棵树哈希与 `701bb547`/`8ec0d890` 全同（`ff4eed826cbc` / `e983feb08237` / `70c541457cae`）⇒ 按 ADR-AUTO-001 继承、不作独立结论。

**最终 head 的 exact-head CI 验收（EXECUTED）**：head `3eafac91`（PR merge commit `a3e5adf14d13`，parents `69a454a7` + `3eafac91`；base 已前进到 `69a454a7`）的 windows-ci **`41102903873` completed/success**，台阶行与前面两个 head 一致（`methodCases=1952 baseline=method case table 1952 cases` / WebDav `104 … 104 cases` / `methods=1637 … 1637 methods` / `combined tests=2056`）；licence **`checks=29 failed=0 warnings=1`**（base 前进新增的 W35 `notices-inventory` / `notices-declarations` 两项，`sbom-source-revision` 对 merge commit `a3e5adf14d13` 判祖先，`sdk-pin-matches-report 10.0.113` PASS，唯一 WARN `lgpl-licence-gaps 5 row`）；探针口径不变。同 head Android CI `41102903866` 取证时 in_progress；`41099478811`（@`8ec0d890`）与 `41100655793`（@`4461140b`）被 concurrency 取消，而三 head 的 core/feature/app 树哈希全同 ⇒ 按 ADR-AUTO-001 继承。

## 6.8 第五层口径：per-argument-row 数据行 digest（W6-113q）

§6.7 把口径推到「总数 → 每类计数 → 方法集合 → 每方法用例数」四层，但第四层只钉「某方法有几行」。把某方法某一行数据的**参数值**替换成活着的另一行的值 / 新值（行数不变）时，四层可见量全部不变 ⇒ 判绿 —— 这正是 §6.7「残留限制」的第 1 条。本轮给每套件加 `data` 表：`Class.Method → 16 位十六进制 digest`。

**要点：只有「值被改写」才是漏洞，「两行互换」不是。** 互换只是重新分配同一个 multiset，语义等价（D3 判 GREEN）；被替换成活着的另一行的值、或替换成新值，才会让某一行「本来测的东西」不再被测（D1）；`{p,p,q} → {p,q,q}` 这种「去重集合与总行数都不变、只有重数变」也属漏洞（D2）。因此 digest 必须**保留重复行**（排序后逐行 join 再哈希，而不是先 set 去重）。

### 口径（2026-10-31 起）

| 字段 | 含义 | 校验 |
|---|---|---|
| `data` | `类.方法` → 该方法全部参数行的指纹 | 行值被替换而计数不变 → RED（点名 `Class.Method (baseline <want>, now <got>)`）；条目消失 → RED（`argument text gone`）；新方法带参数 → GREEN + warning |
| digest 输入 | 按 `[System.StringComparer]::Ordinal` 排序、以 LF join 的参数后缀（**保留重复行**），SHA-256 取前 8 字节小写十六进制 | 只对**带参数**的方法写条目（windows 100 / webdav 3），否则规模再涨数倍而收益不变 |
| 行数先比 | 同方法实际行数 > 基线的 → warning（`argument text grew: N -> M rows`） | 若不分行数，新增一行 `[InlineData]` 会从「增长=warning」被误判成硬失败 |
| 结构自洽 | —— | 缺 / 空 `data` 表、`data` key 不在 `methods` 表、digest 非 16 位小写 hex、旧（2026-10-30）方法名数组格式 → 一律 throw |

TRX 事实（EXECUTED）：每个 `[Theory]` 数据行是独立的 `<UnitTest name="Class.Method(<args>)">`，`TestMethod.name` 只给裸方法名 ⇒ 参数文本仅存在于显示名里，类的归属仍由 `TestMethod.className` 决定（§6.1 起沿用）。真实语料 `windows-tests.trx` 2,719,155 B：defs 1952 / 带参数显示名 415 行 / 有参数的方法 100 个，其中 **6 行被框架用 `···` 截断**（截断点之后的差异不可见）；`webdav-tests.trx` 145,266 B：3 方法 / 12 行 / 无截断。跨运行确定性：`D:/mh-verify/r9-art`（run `41055690826` @48d5feb7）与 r12-art（run `41095080796` @701bb547）的同方法 digest windows 100/100、webdav 3/3 完全一致，无 GUID / runner 名 / 时间戳等非确定性参数。

`check-test-suite-baseline.ps1`（23,063 B）新增台阶行 `methodData=N baseline=method data table N digests`；`update-test-suite-baseline.ps1`（16,197 B）写出 `data` 表、summary 行尾 `data=<n>`，并把「argument text gone / argument text changed」纳入 `-AcceptRemoval` 守卫。

### 负向对照（38 例，两代 PowerShell 逐例判定一致）

| 例 | 输入 | 期望 | 观测 |
|---|---|---|---|
| D1 | 某方法一行参数值被替换成活着的另一行的值（四层可见量全同） | RED | RED，点名 `ToyTests.M_a (baseline …)` |
| D1x | 同 D1 输入交给 round 12 门禁 | GREEN | GREEN ⇒ D1 的红确为本轮收口所得 |
| D2 | `{p,p,q} → {p,q,q}`（去重集合与总行数都不变，只有重数变） | RED | RED（digest 保留重复行才看得到） |
| D3 | 两行身份互换（同一 multiset） | GREEN | GREEN（排序后哈希，顺序无关） |
| D4 | 某方法的参数文本整体消失（Fact 化） | RED | `M_a (argument text gone)` |
| D5 | Fact 长出参数（变 Theory） | PASS + warning | `::warning::new test method argument text` |
| D6 | r12 旧格式基线（无 `data` 表） | 新门禁 RED | `has no data table`；同一输入在 r12 门禁 GREEN |
| D7 | 旧格式基线用新刷新脚本、且无 `-AcceptRemoval` | GREEN（迁移不降级） | expected=6、classes 不变、data 2 键 |
| D8 | 替换参数值后刷新：先无开关、再带 `-AcceptRemoval` | 先拒绝、后写入 | 拒绝消息含 `argument text changed` 且基线字节未动；带开关后 GREEN |
| D9 | 参数文本消失后刷新 | 拒绝 | 与 D8 同守卫 |
| G1 | 某 theory 增加一行数据 | PASS + warning | `argument text grew: 2 -> 3 rows` + `M_b (+1 cases)` |
| G3 | 同一 theory 同时「加一行 + 改一行值」 | PASS + warning | 仍按增长报（见残留限制） |
| C1–C5 | 缺 / 空 / 幽灵 key（不在 `methods`）/ 坏 digest / `data` 是数组 | RED | 全 RED；空表一例在首轮暴露出实现缺陷（见下） |
| R1–R6 | 回归：total 降 / 类缩减 / 方法缩减 / failed≠0 / notExecuted≠0 / definition≠result | RED×5 + warning | 与 §6.5/§6.7 判定一致 |
| RE1 | 真实 TRX × 已提交基线 | PASS | 含 `methodData=100 baseline=method data table 100 digests`、`methodData=3 … 3 digests`、`combined tests=2056` |
| RE2 | r12 基线 × 真实 TRX：新门禁 vs r12 门禁 | RED / GREEN | 迁移闸门只在新版生效 |
| RE3 | 真实语料迁移刷新 | 写入 | `data=100` / `data=3` |
| RE4 | 两代 PowerShell 各写一遍；两代语料各算一遍 digest | 一致 | 写出的字节相同；r9-art 与 r12-art digest 全等 |

**首轮暴露的两处问题（EXECUTED）**：① **实现缺陷**：C2（`data` = `{}`）走错分支 —— `@($e.data.PSObject.Properties.Name)` 在空对象上退化成「一个空名属性」，报 `has a blank data key` 而非专用消息；改为显式枚举 `@($e.data.PSObject.Properties)` 后两代 PowerShell 都给出 `has an empty data table - the per-argument-row check would be vacuous`。② **台账笔误**：D7 断言写成 `before.expected === 0`（该处应为 6 且 `before.data === undefined`）。两处修完后 38/38 pass（`D:/mh-verify/r13/nc-run.log`）。

### 数据源与规模

数据源沿用 CI run `41095080796`（head `701bb547`）的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B）。基线 **127,587 B → 140,085 B**（+12,498 B），其中 `data` 只覆盖 100 + 3 个带参数方法；被钉住的是 415 + 12 行参数文本的指纹。

### 残留限制

- **框架截断**：6 行显示名以 `···` 结尾（如 `ServerUrlNormalizerSecurityTests.Query_and_fragment_are_refused_without_echoing_them`），截断点之后的差异不可见；这些方法的 digest 仍会随前缀变化而变，但前缀内的改动看不到。
- **同一 theory「加行 + 改值」**：因为数据层先比行数，「增加了行」会把它归入 warning（G3）；要区分「加了一行且改了另一行的值」需要逐行身份，代价更高。
- **digest 不含类名与方法名**（改名由 `methods` 表管），也不含运行期生成的值（Theory 参数只有字面量才进 TRX 显示名）。

**exact-head CI 验收（EXECUTED）**：head `a0b46f6e` 的 windows-ci **`41111613263` completed/success**（`pull_request`）：日志 `D:/mh-verify/r13c/ci-41111613263-windows.log` 逐行命中 `total=1952 baseline=1952` / `classes=113 … 113 entries` / `methods=1637 … 1637 methods` / `methodCases=1952 … 1952 cases` / **`methodData=100 baseline=method data table 100 digests`**，WebDav `104 / 4 / 95 / 104` / **`methodData=3 … 3 digests`**，`combined tests=2056`；licence **`check-licence-surface  checks=30 failed=0 warnings=1 skipped=0`**（base 前进到 `faff3927`，`sbom-source-revision` 对 merge commit `d7fa04aa5d8b` 判祖先，`sdk-pin-matches-report 10.0.113` PASS，唯一 WARN `lgpl-licence-gaps 5 row(s)`）；探针 `MPV_PROBE PASS` / `ENGINE_PROBE PASS`（GPU/HDR/audio output NOT exercised → UNVERIFIED）。同 head Android CI `41111613255` 取证时 in_progress，而 `a0b46f6e` 与 `92c93e51` 的 core/feature/app 树哈希全同（`ff4eed826cbc` / `e983feb08237` / `70c541457cae`）⇒ 按 ADR-AUTO-001 继承、不作独立结论。

**docs 提交的 exact-head CI（EXECUTED）**：head `cb2f0b14`（TEST_MATRIX 本小节 + RUNLOG round 13 小节 + queue `W6-113q`）的 windows-ci **`41113224063` completed/success**：`methods=1637 … 1637 methods` / `methodCases=1952 … 1952 cases` / **`methodData=100 baseline=method data table 100 digests`** / WebDav `104 / 4 / 95 / 104` / **`methodData=3 baseline=method data table 3 digests`** / `combined tests=2056`，licence `check-licence-surface  checks=30 failed=0 warnings=1 skipped=0` ⇒ 本层口径与台阶行随文档一并进入 exact-head CI；同 head Android CI 按树哈希（`ff4eed826cbc` / `e983feb08237` / `70c541457cae`）继承。
## 6.9 第五层口径升级：per-method 参数行清单（W6-113r，2026-10-31）

### 为什么必须从 digest 升级

§6.8（W6-113q）把第五层做成「每个带参方法一个 16 位十六进制 digest」，并在残留限制里写明：同一 theory 里「**加一行** + **换掉另一行**」只改变 digest，于是走 growth 的 `::warning::` 判绿，而真正消失的那一行被新增行掩盖。根因是 digest 属于**集合摘要**：它只能回答「这一堆行的整体是否变了」，不能回答「哪一行没了」——**无法区分「增长」与「增长且替换」**。

本层把摘要换成**排序后的参数行清单**，判定改为**多重集包含**：

| 关系 | 基线数据 → 运行数据 | 判定 |
| --- | --- | --- |
| 完全相同 | 多重集相等 | PASS（`methodData=` / `argumentRows=` 台阶行不变） |
| 真子集 | 基线每一行仍在，运行侧多出行 | `::warning::`（`argument rows grew: A -> B rows`），刷新基线即可 |
| 其余 | 有基线行不再出现（含「同时加行且换值」） | **RED**，点名 `baseline row(s) no longer reported: …` 与 `new row(s) in the same method: …` |

**口径要点**：两行文本互换**不是**漏洞（多重集不变、语义等价）⇒ 必须判绿；只有「某行被替换成活着的另一行的值或别的新值」才是漏洞。因此实现**保留重复行**、用双游标归并做多重集差（`Get-RowDifference` + `[System.String]::CompareOrdinal`），**不用 hashtable**（避免大小写折叠）也不用 set（会丢掉重数）。

### 基线字段（第六版）

| 字段 | 内容 | 与上一版的关系 |
| --- | --- | --- |
| `expected` | 每套件用例行总数 | 不变 |
| `classes` | 类 → 用例数 | 不变 |
| `methods` | 类 → { 方法: 用例数 } | 不变 |
| `data` | `"Class.Method": [ "(args)", … ]`，Ordinal 排序、保留重复、纯 ASCII | **替换 §6.8 的 digest 字符串**（旧值出现即 throw：`as a digest - that is the 2026-10-31 format`） |

数据层自洽校验（check 脚本 L187-226，违反即 throw）：`data` 表缺失（`has no data table - the per-argument-row check would be vacuous`）/ 为空；值仍是 digest 字符串；值不是列表；某方法行清单为空或含空白行；key 不是 `methods` 表里的方法；**某方法行数 ≠ 该方法在 `methods` 表的用例数**（`the two layers must agree`）。最后一条把第五层与第四层钉在一起：行数由第四层的用例数决定，两表不允许各自记账。

### TRX 事实（EXECUTED）

`[Theory]` 的每一行数据是独立的 `<UnitTest name="Class.Method(<args>)" id="…">`，参数文本**只出现在显示名**里（定义侧 `UnitTest.name`、结果侧 `UnitTestResult.testName`），`TestMethod.name` 始终是裸方法名。规模：

| 套件 | 定义行 | 带参数行 | 带参数方法 | 被框架截断的行 | 行清单总行数 |
| --- | --- | --- | --- | --- | --- |
| MediaHub.Tests（windows-tests.trx 2,719,155 B） | 1952 | 415 | 100 | 6（出现 `···`） | 415 |
| MediaHub.WebDav.Tests（webdav-tests.trx 145,266 B） | 104 | 12 | 3 | 0 | 12 |

跨运行确定性（EXECUTED）：run `41055690826` @`48d5feb7` 与 run `41095080796` @`701bb547` 两次运行的**逐行清单** windows 100/100、webdav 3/3 完全相同 ⇒ 行清单可作基线钉住。

### 负向对照（42 例，pwsh 7 与 Windows PowerShell 5.1 各跑一遍且判定一致）

| 例 | 输入 | 期望 | 结果 |
| --- | --- | --- | --- |
| E1 | 同一 theory 加一行 + 换掉另一行（行数不变） | RED | PASS |
| E1x | 同一输入给 round 13 门禁 | GREEN（只报 growth warning） | PASS |
| E2 | 某行值被替换 | RED | PASS |
| E3 / E3b | 纯加一行（check / update 接受增长） | GREEN + warning | PASS |
| E4 | 重数变化 `{p,p,q} → {p,q,q}` | RED | PASS |
| E5 | 两行身份互换（多重集不变） | GREEN | PASS |
| E6 | 参数文本改名 | RED | PASS |
| E7 | 某方法不再报参数行 | RED（`argument rows gone`） | PASS |
| E8 | 新方法带参数行 | GREEN + warning | PASS |
| E9 | round 13 digest 基线（新闸门 / 旧闸门） | RED / GREEN | PASS |
| E10 | 用新 update 刷新 digest 基线（无 `-AcceptRemoval`） | GREEN，计数不变 | PASS |
| E11 | 换值后刷新：无开关 / 有开关 / 之后 check | RED 且基线未改写 / GREEN / PASS | PASS |
| E12 | 行消失后刷新（无 `-AcceptRemoval`） | RED | PASS |
| E13–E17 | 配置五负例（行数≠用例数 / 非列表 / 空清单 / 空白行 / 幽灵方法） | RED | PASS |
| E18 | 参数行含中文与引号 | 基线仍纯 ASCII、check PASS、转义无损 | PASS |
| R1–R5 | 总数降 / 方法用例数降 / 类消失（类改名、总数不变）/ failed / notExecuted | RED | PASS |
| R6 / R7 | definition≠result / 新类 | PASS + `::warning::` | PASS |
| RE1 | 真实 TRX × 已提交基线 | PASS，含 `methodData=100 … 100 row sets` / `argumentRows=415 … 415 rows` / `combined tests=2056` | PASS |
| RE2 | round 13 digest 基线 × 真实 TRX（新 / 旧闸门） | RED / GREEN | PASS |
| RE3 | 用真实 TRX 重生成 | 与已提交基线逐字节相同 | PASS |
| RE4 | 更早一次运行的 TRX × 同一基线 | PASS（跨运行稳定） | PASS |

### 数据源与规模

数据源 = CI run `41095080796` @head `701bb547` 的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B），与 §6.7 / §6.8 同一批次。基线规模演进：`7,659 B`（round 7/8 每类计数）→ `122,204 B`（round 9 方法名）→ `127,587 B`（round 12 每方法用例数）→ `140,085 B`（round 13 digest）→ **`163,501 B`（本轮行清单）**。行清单把每条参数文本完整写进仓库，换来可点名「哪一行不再出现」的可诊断性；163 KB 对 1952 个用例行是可接受的代价。

### 残留限制

1. **框架截断**：windows 侧 415 个带参数行中 6 行的显示名已被 xUnit 用 `···` 截断，截断点之后的差异不可见（如 `ServerUrlNormalizerSecurityTests.Query_and_fragment_are_refused_without_echoing_them`、`SyncCliTests.Parse_RejectsMalformedCommandLinesWithStableCodes`）。
2. **多重集包含的语义极限**：若同一编辑里「替换一行」并「在同一方法新增一行且新增行文本恰好等于被替换行的文本」，多重集恰好复原 ⇒ 仍判绿。这是「按文本多重集记账」覆盖不到的最后一层，只能靠签名/语义哈希（代价与噪声显著上升）。
3. **行清单不含类名与方法名**：方法改名 / 跨类搬移由 `methods` 与 `classes` 两表负责，数据层只钉「同一方法名下的参数文本多重集」。
4. **不钉执行序**：行清单按 Ordinal 排序后比较，测试执行顺序与行顺序本就非契约。

### exact-head CI 验收（EXECUTED）

head `1e2cf9ed5de87abe4e3da6691cbacc6a50167cc3` 的 windows-ci **`41124244650` completed/success**：台阶行逐行命中 `total=1952 baseline=1952` / `classes=113 baseline=class table 113 entries` / `methods=1637 baseline=method table 1637 methods` / `methodCases=1952 baseline=method case table 1952 cases` / **`methodData=100 baseline=method data table 100 row sets`** / **`argumentRows=415 baseline=argument row table 415 rows`**，WebDav `104` / `4 entries` / `95 methods` / `104 cases` / `3 row sets` / `12 rows`，`combined tests=2056`；licence `check-licence-surface  checks=31 failed=0 warnings=0 skipped=0`；探针 `MPV_PROBE PASS` / `ENGINE_PROBE PASS`。同 head 的 Android CI `41124244565` 取证时 in_progress；`a0b46f6e` / `cb2f0b14` / `825f46f4` / `1e2cf9ed5de87abe4e3da6691cbacc6a50167cc3` 的 `core` / `feature` / `app` 树哈希相同 ⇒ 按 ADR-AUTO-001 继承。

### docs head 的 exact-head CI 验收（第 42 例之后）

| run | head | 结果 |
| --- | --- | --- |
| windows-ci `41124244650` | `1e2cf9ed`（代码） | success：台阶行含 `methodData=100 ... 100 row sets` 与 `argumentRows=415 ... 415 rows`，WebDav `3` / `12`，`combined tests=2056`，licence `checks=31 failed=0 warnings=0 skipped=0` |
| windows-ci `41125371678` | `49911e49`（docs） | success：所有台阶行与代码 head 逐字一致（含 `argumentRows=415 ... 415 rows`），licence `checks=31 failed=0 warnings=0 skipped=0`，两个探针 PASS |

- 两个 head 的 Android CI（`41124244565` / `41125371452`）在本轮取证时均未结束（concurrency 取消或 in_progress）；两个 head 与 `825f46f4` 的 `core` / `feature` / `app` 树哈希完全相同 ⇒ 按 ADR-AUTO-001 继承，不作为独立结论。

## 6.10 截断暴露面口径：runner 截断的参数行（W6-113s，2026-10-31）

### 为什么单开一层
§6.9 的行清单把「每个方法的参数行」钉住，但 runner 会把超长参数值截断并以 `···`（U+00B7 × 3）标注；**截断点之后的文本在 TRX 里不可恢复**，那段差异不可钉。本层只做两件事：把「哪些方法被截、各截几行」变成基线里的可见数字；它变化时给出 `::warning::`，**永不判红**（若判红，CI 会因 runner 的截断行为随时变红）。

### 基线字段与台阶行（2026-10-31 起）
| 项 | 值 | 说明 |
| --- | --- | --- |
| `truncated` | 对象：`"类.方法" -> 截断行数` | 仅记有截断的方法；round 14 格式（无此表）在新闸门下打印 `table not recorded` + warning ⇒ 迁移非破坏 |
| check 台阶行 | `argumentTruncated=N baseline=argument truncated table M rows` | 实测截断行数 / 基线记录数 |
| update summary | 行尾 `truncated=N` | 刷新时可见 |
| 判定 | 计数变化 / 新方法被截 → `::warning::argument rows the runner truncated changed` | 上升、下降、新增都只是 warning |
| 配置自检 | 非对象 / 空 key / key 不在 `methods` 表 / key 不在 `data` 表 / 非整数 / 计数 < 1 → throw | 防止本层空转 |

### 实测暴露面（r12 真实 TRX）
- `MediaHub.Tests`：415 行参数行中 **6 行 / 5 个方法**被截（可见长度 143–201），定义侧与结果侧共 12 处标记；`truncated=6`（`FavoriteMappingTests.IsFavorite_maps_onto_the_domain_flag` 2 行，`RedactionTests.Credential_query_values_are_masked` / `ServerUrlNormalizerSecurityTests.Query_and_fragment_are_refused_without_echoing_them` / `SourceSyncBaselineCodecTests.Deserialize_RejectsUnusableDocuments` / `SyncCliTests.Parse_RejectsMalformedCommandLinesWithStableCodes` 各 1 行）。
- `MediaHub.WebDav.Tests`：12 行中 **0 行**被截；`truncated=0`。

### 负向对照（19 例，pwsh 7 与 Windows PowerShell 5.1 判定一致）
| 编号 | 场景 | 期望 |
| --- | --- | --- |
| T1 | 基线已记录、运行不变（截断行一致） | PASS（无 warning） |
| T2 | 基线记 0、运行有 1 行被截 | PASS + `(new, 1 truncated row(s))` |
| T3 | 基线多记（2）而运行 1 | PASS + `(baseline 2, now 1)` |
| T4 | 基线记了某方法，而该方法已不再报参数 | PASS + warning |
| T5 | `truncated` key 不是 `methods` 表里的方法 | RED（配置自检） |
| T6 | `truncated` key 不在 `data` 表（未被钉住） | RED（`which the data table does not pin`） |
| T7 | 截断计数为 0 | RED（`records 0 truncated argument row`） |
| T8 | `truncated` 表是字符串 | RED（`truncated table that is not an object`） |
| T9 | 截断计数非整数 | RED（`non-integer truncated count`） |
| T10 | round 14 格式基线 + 运行有截断 | PASS + `table not recorded` + `the runner truncated changed` |
| T10x | 同输入给 round 14 闸门 | PASS 且输出无 `argumentTruncated=` |
| T11 | 截断计数不变 | PASS + 台阶行 `... 1 rows`、无 warning |
| R1 | 丢一行 → 总数层红 | RED（`ran 7 tests but the baseline says 8`） |
| R2 | 改类名 → 每类层红 | RED（`test classes lost cases`） |
| R3 | 同一 theory 丢行而总数不变 | RED（`test methods lost cases`） |
| R4 | 换一个参数值、计数不变 | RED（`test method argument rows changed`） |
| R5 | 有失败用例 | RED |
| R6 | 新方法 | PASS + 增长 warning |
| U1 | 两个 shell 刷新的字节 | `equal=true` |

### 首轮 11 红均为台账自身问题（已修）
1. 夹具 TRX 缺 UTF-8 BOM ⇒ PS 5.1 按 ANSI 解码 U+00B7，标记识别为 0（真实 CI TRX 自带 BOM，故此前真实语料在两个 shell 都正确）。
2. R1–R4/R6 把基线建立在「已改动的运行」上，闸门正确地保持绿；改为「先用 canonical rows 建基线、再写改动后的 TRX」。
3. 诊断切片 300 字符掩盖 throw 文本，加到 500。
4. 5 红来自 harness 用 JS `JSON.stringify` 直接写基线（含原始非 ASCII）：PS 5.1 读无 BOM 文件按 ANSI ⇒ 行文本变 mojibake，两个 shell 合法地判定不一致；改用与 update 脚本一致的 ASCII `\uXXXX` 转义后全绿。**真实流水线不受影响**：CI TRX 有 BOM，基线一律由 update 脚本写出。

### 数据源与规模
- 数据源：run `41095080796` @`701bb547` 的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B）。
- 基线规模：round 14 `163,501 B` → **164,027 B**（+526 B 的 `truncated` 表）；纯 ASCII；两 shell 写出的字节相同。

### 残留限制
1. 截断点之后的文本不可恢复 ⇒ 只能暴露、不能钉住（本层设计如此）。
2. 截断阈值由 runner 的显示名长度策略决定；runner 改变阈值会让本层给出 warning（需人工刷新基线）。
3. 本层与 §6.9 的 `data` 行清单互补：`data` 钉住可见文本，`truncated` 说明可见文本有多可信。

### exact-head CI 验收（EXECUTED）
- head `c3a49cef`（merge commit `3ed7baaf4abd`）：windows-ci `41169510082` completed/success —— `MediaHub.Tests: argumentTruncated=6 baseline=argument truncated table 6 rows`、`MediaHub.WebDav.Tests: argumentTruncated=0 baseline=argument truncated table 0 rows`，其余台阶行与 §6.9 相同（`argumentRows=415 … 415 rows` / `methodData=100 … 100 row sets` / `methodCases=1952 … 1952 cases` / `combined tests=2056`）；licence `checks=42 failed=0 warnings=0 skipped=0`；两个探针 PASS（GPU/HDR/audio 未行使 ⇒ UNVERIFIED）。
- docs head `86a4c5aa` 的 windows-ci `41169881421` success：台阶行与代码 head 逐字相同（含 `argumentTruncated=6 … 6 rows`、WebDav `0 … 0 rows`），licence `checks=42 failed=0 warnings=0 skipped=0`。

## 6.11 套件集口径：基线钉住 suite 清单并核对产出的结果文件（W6-113t，2026-10-31）

§6.10 之前的五层口径都只在基线**列出的** suite 条目上做比对。因此出现一个更外层的洞：**删掉一个 suite 条目**（或整块测试项目从 CI 里消失）之后，它不再被检查，其用例数也只是从 `combined tests` 中消失，而文件里其他任何计数都不变 —— 门禁看不见这次收缩。

### 基线字段（2026-10-31 起）

| 字段 | 含义 | 本轮判定 |
| --- | --- | --- |
| `expected_suites` | 顶层计数：本文件声明了几个 suite | 与 `suites` 表长度不符 → RED（手工删条目立即暴露） |
| `suites[].file` / `suites[].suite` | 结果文件名 / 程序集名 | 结果文件缺失 → RED；声明的 suite 名与结果文件里 `TestMethod codeBase`/`storage` 的程序集名不符 → RED |
| （枚举 `-ResultsDir`） | 实际产出的 `*.trx` | 未被基线列出 → `::warning::`（新增 suite 走增长路径，但保持可见） |

### 台阶行

```text
MediaHub.Tests: suites=2 baseline=suite table 2 suites
combined tests=2056
```

### 负向对照（26 例，两代 PowerShell 判定一致）

| 例 | 输入 | 期望 |
| --- | --- | --- |
| S1 | 规范输入 | PASS，`suites=2 baseline=suite table 2 suites`、`combined tests=10` |
| S1x | 同一输入给 round 15 闸门 | 输出无 `suites=` 行（新层确为新增） |
| S2 | 多出一个未列出的 `*.trx` | PASS + warning，`suites=3 … 2 suites` |
| S2x | 同一输入给 round 15 闸门 | 完全看不见该文件（`combined tests=10`，输出不含其文件名） |
| S3 | `expected_suites=1` 而表里 2 条 | RED |
| S4 / S4x | 删掉 `expected_suites` | 新闸门 RED / round 15 闸门放行 |
| S5 | `expected_suites` 非数字 | RED |
| S6 | 两条目指向同一结果文件 | RED |
| S7 | 列出的 suite 没有结果文件 | RED |
| S8 | 结果文件来自别的程序集 | RED |
| S9 | 结果文件无可解析的程序集记录 | PASS + warning |
| S10 | 一个文件混两个程序集 | PASS + warning |
| S11 | 第二个 suite 用例数缩减 | RED |
| R1/R2/R4/R6 | 总数降 / 每类降 / 方法换 / 新增方法 | 既有各层判定不变 |
| U1 | 刷新时多出一个未列出的结果文件 | 自动接纳（`added suite: …`、`expected_suites=3`、check `combined tests=12`） |
| U2 | 两代 PowerShell 刷新 | 写出字节相同 |
| U3 | 结果文件消失：无 `-AcceptRemoval` / 有开关 | 前者拒绝且基线未被改写；后者丢弃条目（`removed: suite …`、`expected_suites=1`、check `suites=1 … 1 suites`） |
| U4 | 无变化输入刷新 | 逐字节稳定 |
| RE1 | 真实 CI 语料 | PASS |
| RE2 / RE2x / RE3 | round 15 旧基线 × 真实语料 | 新闸门 RED（要求迁移）/ round 15 闸门放行 / 新 update 可升级旧基线 |

首轮 2 红都不在判定逻辑上：一处是实现缺陷（update 在 `-AcceptRemoval` 分支把「suite 结果文件消失」记进 `$shrunk`，而 `$shrunk` 非空会在末尾无条件 throw ⇒ 带开关也失败），已拆出 `$dropped` 专用于 reporting；另一处是夹具用 `JSON.stringify` 重写旧基线导致 `\uXXXX` 被还原成原始非 ASCII、PS 5.1 按 ANSI 解码，改为逐字节复制旧基线。修后 26/26 通过。

### 数据源与规模

- 数据源仍为 run `41095080796` @head `701bb547` 的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B）。
- 基线 164,027 → **164,051 B**（只多一行 `"expected_suites": 2,`）；check 29,523 → **34,527 B**，update 18,921 → **21,834 B**。

### 残留限制

- 未列出的结果文件只报 warning，不判红：新增测试项目属「增长」，必须可合并；哨兵是 `expected_suites` 与条数一致性，以及「被删条目对应的结果文件是否还在」。
- 一个结果文件混多个程序集会退化为 warning（程序集名无法唯一归属），此时该条目的归属核对实际不成立。
- 结果文件的程序集名来自 TRX 记录的 `codeBase`/`storage` 字串；runner 改写入方式（例如不再写这些属性）会退化成 warning 而不是 RED。

### exact-head CI 验收（2026-10-31）

- 代码 head `716f481a` 的 windows-ci **`41200282567` = completed/failure**：测试门禁步骤全绿并打印 `suites=2 baseline=suite table 2 suites`（连同六层台阶行与 `combined tests=2056`），唯一失败步骤是 licence 表面的 `Licence surface guard`，停在 `check-licence-surface  FAIL  log-file-claims`（`checks=44 failed=1 warnings=0 skipped=0`）。
- 归因（见 RUNLOG round 16 与 queue `W6-113u`）：base `2bdaa499` 新加的第 44 项守卫 W50 要求 `windows/src/MediaHub.Net/LogService.cs` 含字面量 `sb.AppendLine("# 滚动文件目录：" + FileSink.DirectoryPath);`，而 W6 分支在 `055c85c3` 已把该行搬进 `DiagnosticsReport.cs`（`RollingDirectoryPrefix`）；base 自身在该 head 上 `checks=44 failed=0`（`41174155974` / `41174160859`），上一轮我方 head 在旧 base 上 `checks=42 failed=0` ⇒ 红由跨分支形状分歧产生，与本轮改动无因果。
- docs head `f396e791` 的 windows-ci `41200702543` failure：台阶行与代码 head 逐字一致，唯一失败仍是 `log-file-claims`（`checks=45 failed=1`）⇒ 与本层无关，归因见 `W6-113u`。

## 6.12 第七层口径：outcome 分布与结果文件自洽性（W6-113v，2026-10-31）

§6.11 之前，每一层的数字都读自结果文件自己的 `ResultSummary/Counters`，从不数每一行的 outcome。因此两种情形可以整层通过：**行与汇总不一致**的文件（手工合并、裁剪或改写的 TRX）只要汇总干净就绿；以及**某一行换了 outcome kind**（`Passed` → `Aborted` / `Inconclusive` / `Timeout` / `Error` …）时，只要汇总仍报 `failed=0 notExecuted=0`、总数不变，每类计数、方法集合、每方法用例数与参数行都一模一样。

### 基线字段与判定（2026-10-31 起）

| 字段 / 检查 | 含义 | 判定 |
| --- | --- | --- |
| `outcomes` | 每条 suite 的 outcome 桶（TRX outcome 名，只记非零） | 各桶之和必须等于 `expected`，否则配置 throw |
| 逐行 outcome 计数 | 数 `UnitTestResult@outcome` | 与运行自己的 `Counters` 逐桶（含 `total`）不一致 → RED |
| 基线 outcome 行数下降 | 例如 `Passed 1952 → 1951` | RED |
| 出现基线没有的 outcome kind | 例如 `Aborted` | RED（新桶必须显式刷新并走 `-AcceptRemoval`） |
| outcome 行数增长 | 新增用例 | GREEN + `::warning::new outcome rows` |

### 台阶行

```text
MediaHub.Tests: outcomes=1 baseline=outcome table 1 outcomes
MediaHub.WebDav.Tests: outcomes=1 baseline=outcome table 1 outcomes
```

### 负向对照（29 例，两代 PowerShell 判定一致）

| 例 | 输入 | 期望 |
| --- | --- | --- |
| V1 / V1x | 规范输入 / 同一输入给 round 16 闸门 | PASS 且含 `outcomes=1`；旧闸门输出无 `outcomes=` 行 |
| V2 / V2x | 行皆为 Passed 但 `Counters.passed=5` | 新闸门 RED（`passed rows=8 vs Counters.passed=5`）/ 旧闸门放行 |
| V3 / V3x | 末行 Passed → Aborted（汇总随之一致） | 新闸门 RED（`Passed (8 -> 7 rows)`、`Aborted`）/ 旧闸门放行 |
| V5 | 新增 2 行数据 | PASS + `::warning::new outcome rows` |
| R7 | `Counters.inconclusive=1` 但无该 outcome 行 | RED（反方向不一致） |
| C1 / C1x | 基线缺 `outcomes` | 新闸门 RED / 旧闸门放行 |
| C2–C6 | 空表 / 未知 outcome 名 / 计数 0 / 非整数 / 与 expected 不符 | 全 RED（配置自检） |
| U1 | 旧格式基线用新 update 刷新 | 自动补 `outcomes`，无拒绝 |
| U2 | 出现新桶：无 `-AcceptRemoval` / 有开关 | 前者拒绝且基线未改写；后者写入 `{Inconclusive:1, Passed:7}` 且 check PASS |
| U3 / U4 | 未列出的 suite / 无变化刷新 | 自动接纳；两 shell 字节稳定 |
| R1–R6 | 总数降 / 类改名 / 参数值替换 / 截断表虚报 / 新方法 / 未列出结果文件 | 与前六层判定一致（红/warning 各归其位） |
| RE1–RE3 | 真实语料 + round 16 真实基线 | PASS；旧基线被新闸门拒（`has no outcomes table`）但旧闸门放行；新 update 可迁移旧基线 |

首轮 13 红全部是台账自身（`V` 路径仍指 round 16、夹具行数按 6 行算而实际 8 行、`combined tests` 写成 8），修正后 29/29。

### 数据源与规模

- 数据源仍为 run `41095080796` @head `701bb547` 的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B，行 outcome 全为 `Passed`）。
- baseline 164,051 → **164,452 B**；check 34,527 → **42,459 B**；update 21,834 → **24,836 B**。

### 残留限制

- 本层核对「每行都有且只有一个 outcome，且逐桶等于汇总」与「桶分布与基线一致」；TRX 的 `testId` 唯一性未单独校验（重复 id 会让归属映射把两行算到同一方法上，通常在每类/每方法计数处先红）。
- 结果文件里的时长、时间戳、`ResultSummary@outcome` 属性不参与判定。
- outcome 名必须落在两脚本共有的 14 个 TRX 桶名内；runner 引入新桶名时 update 会直接 throw（宁可拒绝，也不静默把新 kind 写进基线）。

### exact-head CI 验收（2026-10-31）

- 代码 head `9e90f1e1` 的 windows-ci **`41202161858` = completed/failure**：测试门禁步骤全绿并打印 `MediaHub.Tests: outcomes=1 baseline=outcome table 1 outcomes` 与 WebDav 同形行（连同七层旧台阶行、`suites=2 baseline=suite table 2 suites`、`combined tests=2056`）；唯一失败步骤仍是 licence 的 `log-file-claims`（`checks=45 failed=1`）。
- 该红与 `W6-113u` 记录的跨分支形状分歧逐字相同，与本轮改动无因果（本轮只动 `windows/tools/*.ps1` 与基线，未触碰 `LogService.cs`）。

- docs head `33a2d77c` 的 windows-ci **`41202435323` = completed/failure**：测试门禁步骤全绿，两套件均打印 `outcomes=1 baseline=outcome table 1 outcomes`（连同七层旧台阶行、`suites=2`、`combined tests=2056`）；唯一失败步骤仍是 licence 的 `log-file-claims`（`checks=45 failed=1`，即 `W6-113u` 记录的 W50 跨分支 needle 分歧），与本层改动无因果。

## 6.13 第九层口径：TRX 两侧身份对齐（W6-113w，2026-10-31）

前八层的每个数字与每个集合都只来自 TRX 的**一侧**：总数与 `Counters`、每类、每方法、参数行来自定义侧，outcome 分布来自结果侧，而 `id` 一直只被当作坐标使用、从未在两侧互校。两种手改文件因此可以整层通过：把一个定义的 id 复制到另一个定义（连同其对应结果），或交换同一类内两个定义的 id（结果侧不动，于是每个结果行被归到另一个定义上）—— 总数、每类计数、方法集合、每参数行与 outcome 分布全都保持不变。

### 口径与判定

| 检查 | 含义 | 判定 |
| --- | --- | --- |
| 定义 id 唯一 | `TestDefinitions/UnitTest@id` 不得重复 | 重复 RED（点名 id 与出现次数） |
| 结果 id 唯一 | `Results/UnitTestResult@testId` 不得重复 | 重复 RED |
| 两侧 id 集合互为子集 | 定义 id 必须有结果行；结果 id 必须有定义 | 定义侧孤儿 RED；结果侧孤儿沿用 unmapped 检查（现在点名 id） |
| 显示名一致 | 结果 `testName` 必须等于其 id 指向的定义 `UnitTest@name` | 不一致 RED（点名 `id`、定义名与结果名） |
| 截断容错 | 任一侧含 runner marker 时，按 marker 前文本比较前缀关系 | 前缀一致 PASS；不一致 RED |

### 台阶行

```text
MediaHub.Tests: identities=1952 named=1952 unique test ids shared by both halves
MediaHub.WebDav.Tests: identities=104 named=104 unique test ids shared by both halves
```

基线**不加字段**：这些是单个 TRX 内部的不变量（同一 id 两侧必须指向同一行），不是跨 run 的量，钉进基线只会重复 `expected`。因此本轮无迁移、update 脚本未改、`test-suite-baseline.json` 未改。

### 负向对照（18 例，两代 PowerShell 判定一致）

| 例 | 输入 | 期望 |
| --- | --- | --- |
| I1 / I1x | 规范输入 / 同一输入给 round 17 闸门 | PASS 且含 `identities=8 named=8`；旧闸门无 `identities=` 行 |
| **I2 / I2x** | 一个 id 复制到另一个定义（结果侧同步复制） | 新闸门 RED（`definition id 5 appears 2 times`）/ 旧闸门 GREEN |
| **I3 / I3x** | 交换同类内两个定义的 id（结果侧不动） | 新闸门 RED（`id 1: definition ToyTests.M_a(1) vs result ToyTests.M_fact`）/ 旧闸门 GREEN |
| I4 | 一侧多出的 id | RED（unmapped 消息点名 `testId 8`） |
| I4b / I4bx | 定义侧孤儿 id（配 count 谎报以免总数检查先拦） | 新闸门 RED（`definition id 8 has no result row`、`result testId 1 appears 2 times`）/ 旧闸门 GREEN |
| I5 / I5x | 两侧同一行 id 为空 | RED（`1 test definition(s) have no id`）/ 旧闸门 GREEN |
| I6 / I7 | 截断行两侧尾巴不同但前缀一致 / 前缀不一致 | PASS（容错无假红）/ RED |
| R1–R3 | 总数降 / outcome kind 变化 / 参数值替换 | 与前八层判定一致 |
| RE1 / RE2 | 真实语料 | PASS（`identities=1952 named=1952`、`combined tests=2056`）/ 旧闸门无该行 |

首轮 16 例里 1 红为实现边界问题：`result testId … names no definition` 分支不可达（结果侧孤儿 id 会先撞既有的 unmapped 检查）⇒ 改为在 unmapped 消息里点名 id，并把「定义侧孤儿」改用「结果侧重复 id + 定义缺结果行」构造，第二轮 18/18。

### 数据源与规模

- 数据源仍为 run `41095080796` @head `701bb547` 的 TRX（`windows-tests.trx` 2,719,155 B / `webdav-tests.trx` 145,266 B）。实测两侧显示名逐行完全相同（1952/1952、104/104，含 6 行截断行），无重复/空白/孤儿 id。
- `check-test-suite-baseline.ps1` 42,459 → **47,512 B**（本次唯一改动文件）；基线未变。

### 残留限制

- 只核对 `id` 与显示名；`executionId`、`duration`、`startTime` 等其它行属性不参与判定。
- runner 若改变截断策略，使两侧截断长度不一致，会落到「前缀一致即通过」的较宽路径（比逐字相等宽松），这是为跨 runner 版本鲁棒性付出的代价。
- 同一 id 在 `<Results>` 中出现多次按 RED 处理（runner 正常不产生）；若上游将来合法地拆出「重试行」，本层需要重新约定。

### exact-head CI 验收（2026-10-31）

- docs head `c8745bb4` 的 windows-ci **`41204722993` = completed/failure**：台阶行与代码 head 逐字一致（含 `identities=1952 named=1952`、WebDav `identities=104 named=104`、`combined tests=2056`）；licence 仍 `checks=45 failed=1`（`W6-113u`）。
- 代码 head `21bc3226` 的 windows-ci **`41204520499` = completed/failure**：测试门禁步骤全绿并打印九层台阶行，含两套件的 `identities=1952 named=1952` / `identities=104 named=104` 与 `combined tests=2056`；唯一失败步骤仍是 licence 的 `log-file-claims`（`checks=45 failed=1`，即 `W6-113u` 的 W50 跨分支 needle 分歧），与本层改动无因果。

## 6.14 第十层口径：结果行自身元数据（W6-113x，2026-10-31）

前九层读的都是「文件内某一侧的数字，或两侧配对」；第十层读的是**一行自己的元数据**。这一层不需要基线字段，因为它是单个 TRX 内部的不变量：钉进基线只会重复 `expected`。

| 项 | 内容 |
| --- | --- |
| 台阶行 | `rowAttributes=1952 unique execution ids=1952 ordered row times=1952 rows within the run window=1952 uniform row fields=4` |
| 字段归属 | `<UnitTestResult>` 的 `executionId` / `testType` / `testListId` / `computerName`，`<UnitTest>` 的 `storage`，以及 `startTime` / `endTime` / `duration` 与 `<TestRun><Times>` 的 `start` / `finish` |
| RED | `executionId` 为空或重复；4 个「每行应一致」的字段出现两个以上取值或整列空白；`startTime` / `endTime` / `duration` 无法解析；`end` 早于 `start`；`duration` 为负；行落在 `<Times>` 窗口之外 |
| WARNING | `duration` 与 (`end` − `start`) 漂移超过 1 秒；文件缺 `<Times>` 窗口（两项在真实语料上均为 0 例，刻意不升级为硬红，避免跨 runner 版本的假红） |
| 基线字段 | 无（单文件内部不变量） |
| 迁移 | 无（`test-suite-baseline.json` 与 `update-test-suite-baseline.ps1` 未改动） |

真实语料（`D:/mh-verify/r12-art/.../test-results` 与 `D:/mh-verify/r9-art/test-results` 四份 TRX）：`executionId` 无空、无重复；`computerName` / `storage` / `testType` / `testListId` 每个文件各只有一个取值；时间全部可解析、`end ≥ start`、无负 `duration`、漂移超过 1 秒 0 例、全部落在 `<Times>` 窗口内。

### 负向对照（30 例，pwsh 7 与 Windows PowerShell 5.1 判定一致）

| 例 | 输入 | 判定 |
| --- | --- | --- |
| N0 / N0x | 规范夹具 | 新闸门 PASS（含 `rowAttributes=`）；round 18 闸门 PASS 且无 `rowAttributes=` 行 |
| N1 / N1x | 两行共用 `executionId` | **RED**（`executionId dup-exec appears 2 times`）；round 18 闸门 GREEN |
| N2 / N2x | `end` 早于 `start` | **RED**；round 18 闸门 GREEN |
| N3 / N4 / N5 / N6 | `computerName` 混用 / 时间不可解析 / 行在 `<Times>` 窗口外 / 空 `executionId` | 各 RED（旧闸门各 GREEN） |
| N7 / N8 / N9 | `storage` 混用 / 负 `duration` / 整列无 `testType` | 各 RED |
| N10 / N11 | `duration` 漂移超过 1 秒 / 缺 `<Times>` 窗口 | PASS + `::warning::`（只暴露，不阻断） |
| R1–R7 | 行被删 / outcome 变化 / 参数换值 / 定义 id 互换 / 类丢行 / 行失败 / 新方法 | 前六层各归其位（不因新层改变语义） |
| RE1 / RE2 / RE3 | 真实语料 / 真实语料给 round 18 闸门 / `git status` | PASS（`rowAttributes=1952 unique execution ids=1952`、`rowAttributes=104`、`combined tests=2056`）/ 旧闸门无该台阶行 / 仅一个文件改动 |

首轮 28/30 的两处红都是**台账自身**：R5 的夹具实际命中的是「方法消失」层（改为第二个类整类消失且总数不变，才真正行使类层），R6 的匹配串 `\(s\)` 被正则当成分组（改为 `MediaHub.Tests: 1 test`）。

### 残留限制

- 只钉「每行应一致」的 4 个字段与 3 个时间字段；`relativeResultsDirectory` 等其它属性未参与判定。
- `duration` 与 (`end` − `start`) 的漂移、以及缺 `<Times>` 窗口只报 WARNING：cross-runner 的时钟精度差异不应把合法产物判红。
- 这一层只证明「文件内部自洽」，不证明「这批用例就是该 commit 的用例」——后者仍由 `expected` 与身份层承担。
- runner 将来若合法地产生「重试行」，同一 `executionId` 出现多次，本层需要重新约定。

### exact-head CI 验收（2026-10-31）

- 代码 head `14bfea5b` 的 windows-ci **`41207751934` = completed/failure**：测试门禁步骤全绿并打印十层台阶行（含 `rowAttributes=1952 unique execution ids=1952 ordered row times=1952 rows within the run window=1952 uniform row fields=4`、WebDav `rowAttributes=104 … uniform row fields=4`、`combined tests=2056`）；licence `checks=46 failed=2`（`log-file-claims` = `W6-113u`，以及本轮新暴露的 `doc-residue-claims`）。

## 6.15 上游 doc-residue 守卫下的分支自建文档修复（W6-113y，2026-10-31）

base 一侧在 2026-10-31 新增了 `doc-residue-claims` 守卫（本分支 HEAD 的 `windows/tools/check-licence-surface.ps1` 不含它；PR 的虚拟合并树取 base 版，所以 CI 会跑到它）。它的判定：

- 扫描仓库根下 `.md` / `.ps1` / `.kt` / `.kts` / `.cs` / `.xaml` / `.yml` / `.yaml` / `.json` / `.txt` / `.sh` / `.gradle` / `.toml` / `.props` / `.csproj`，跳过 `node_modules|build|obj|bin|artifacts|.git|.gradle|.tools|.idea|.local`；
- 残留 token `{BT}` / `~BT~` / `⟪BT⟫` / ~`~` 出现且该行不含 `residue-allow` ⇒ 报问题；
- 对 `.md`：以 ``` 或 ~~~ 开头的行翻转「篱笆内」状态并跳过；**篱笆之外**、trim 后以 `|` 开头、去掉行内反引号片段后仍含 `||` 的行 ⇒ 报「两行表格粘连」；围栏行数为奇数 ⇒ 报「围栏未闭合」；
- 扫到的文本文件少于 20 个也报问题。

本轮触发 5 处：`docs/cross-device/W6-70-PAIRED-DEVICES.md` 第 24 / 25 / 40 / 41 / 42 行。该文档在 base 上不存在（是本分支自己的 W6-70 文档），根因是它的四个代码块开闭围栏被压坏（开围栏 `\`csharp\``、闭围栏 `\`\``），以 `||` 开头的 C# 续行因此落在篱笆之外。修复 = 恢复四个围栏（正文一字未改）：文档 10,305 → 10,309 B，提交 `c373956d`（1 file / 4 insertions / 4 deletions）。

### 复现方法（可复用）

守卫看的是 **PR 合并树**，不是分支 worktree。本机复跑必须按合并树内容判定：`git fetch origin refs/pull/39/merge` → `git ls-tree -r <merge sha>` 取全部 `.md` 的 blob → `git cat-file --batch` 批量取字节 → 按上面的规则扫描。反例：worktree 里的 `docs/android-source-sync-transfer.md` 没有合并树那条 `<!-- residue-allow -->` 标记，按 worktree 扫描会多报 1 个假问题（CI 报 5 处、worktree 扫描报 6 处）。

### exact-head CI 验收（2026-10-31）

- head `c373956d` 的 windows-ci **`41208701809` = completed/failure**：`check-licence-surface  PASS  doc-residue-claims  1317 text file(s) scanned: no unmarked placeholder residue, all 123 markdown file(s) have balanced fences, and the capability map status block sits inside a real fence`；licence 由 `failed=2` 降为 `checks=46 failed=1`，唯一红仍是 `log-file-claims`（`W6-113u`）。

- 账本 head `ad8b1698` 的 windows-ci **`41209166800` = completed/failure**：十层台阶行与代码 head 逐字一致（含 `rowAttributes=1952 unique execution ids=1952 ordered row times=1952 rows within the run window=1952 uniform row fields=4`、WebDav `rowAttributes=104 …`）；`doc-residue-claims` PASS ⇒ 第十层在三个 head 上均成立。

- 账本 head `ad8b1698` 的 windows-ci **`41209166800` = completed/failure**：`doc-residue-claims` 仍 PASS（`1317 text file(s) scanned … all 123 markdown file(s) have balanced fences`）；licence `checks=46 failed=1`，唯一红仍是 `log-file-claims`（`W6-113u`）⇒ 本项修复在三个 head 上稳定。

- 最终 head `828a93c5` 的 windows-ci **`41209461489` = completed/failure**：十层台阶行全部命中（`rowAttributes=1952 unique execution ids=1952 ordered row times=1952 rows within the run window=1952 uniform row fields=4`、WebDav `rowAttributes=104 …`、`identities=1952 named=1952`、`combined tests=2056`），`doc-residue-claims` PASS；licence 为 `checks=47 failed=2`，两条红均在**上游侧**：`log-file-claims`（`W6-113u`，W50）与新出现的第 47 项 `capability-map-pointers`（W53 在 base `f614546e` 上自红，base 自身 run `41209328582`/`41209324178` 同样 checks=47 failed=1），与本轮门禁改动无因果。
## 6.16 第十一层口径：TRX 节结构完整性与节间配对（W6-113aa，2026-10-31）

前十层读的都是文件**内部**的某一侧或两侧配对：总数与 outcome 分布来自 `ResultSummary/Counters`，类、方法、参数行来自 `TestDefinitions` 的定义显示名，行元数据（executionId、时间、列表 id）来自 `Results` 的结果行。`TestEntries`（每个 testId 一条 `<TestEntry>`）与 `TestLists`（声明的列表 id/name）这两个节**从未被读取**。于是以下改动都能让前十层的每一个数字保持完全相同：

- 删掉整个 `<TestEntries>` 或 `<TestLists>` 节；
- 增删 `TestEntry`、让某条 `TestEntry` 的 testId 不对应任何定义；
- 把两条 `TestEntry` 的 `executionId` 互换（结果行不动，行与定义仍一一对应）；
- 让结果行的 `testListId` 指向 `TestLists` 从未声明的列表；
- 复制整个 `ResultSummary` 或 `Times` 节。

### 口径（`windows/tools/check-test-suite-baseline.ps1`，第十一层）

| 检查 | 判据 | 失败后果 |
| --- | --- | --- |
| 节块唯一性 | `ResultSummary` / `Times` / `TestDefinitions` / `TestEntries` / `TestLists` / `Results` 各恰好 1 次，`ResultSummary` 内 `Counters` 恰好 1 次 | RED |
| 定义 ↔ 条目配对 | 每个定义 id 必须有一条 `TestEntry`；每条 `TestEntry.testId` 必须是「唯一且有结果行」的定义 id；`testId` 与列表 id 不得空白或重复 | RED |
| 条目 ↔ 结果行配对 | 同 testId 的 `TestEntry.executionId` 与 `testListId` 必须与该 testId 结果行的同名属性相同 | RED |
| 列表声明 | 结果行的 `testListId` 必须在 `TestLists` 中声明 | RED |
| 台阶行 | `structure=N/7 single blocks entries=… matched entries=… paired execution ids=… paired list ids=… lists=…` | 信息行 |

**基线不加字段**：这是单个 TRX 文件内部的不变量，与运行结果无关，钉进基线只会重复 `expected` ⇒ 本层**无迁移**，`update-test-suite-baseline.ps1` 与 `test-suite-baseline.json` 均未改动。

### 实现要点与坑

- 节块存在性**必须**从 `$xml.TestRun.ChildNodes` 按 `LocalName` 计数：两种 PowerShell 里 `@($null).Count` 都是 **1**，用 `@($xml.TestRun.TestEntries).Count` 判「整节消失」永远得 1（首轮负向对照 S2/S5/S14 假绿就是此坑）。
- 编辑事故：`t.replace(anchor, anchor+add)` 且 `add` 串首又含 anchor 本身 ⇒ 同一行被写两遍，真实语料假红为「每个 testId 出现 2 次」。修法是从改前快照重新应用、增量串不含锚点、并逐锚点断言 `split(...).length-1 === 1`。

### 负向对照（28 例，pwsh 7 与 Windows PowerShell 5.1 判定一致，`pass=28 fail=0`）

| 例 | 改动 | round 20 闸门 | round 19 闸门 |
| --- | --- | --- | --- |
| S1 | 规范夹具 | PASS（`structure=7/7 … entries=8 matched entries=8 paired execution ids=8 paired list ids=8 lists=2`） | PASS 但无 `structure=` 行（S1x） |
| S2 | 删掉 `<TestEntries>` | RED | PASS（S2x） |
| S3 | 两条 `TestEntry` 互换 `executionId` | RED（`pairs TestEntry executionId`） | PASS（S3x） |
| S4 | 结果行跑在 `TestLists` 未声明的列表 | RED | PASS（S4x） |
| S5 | 删掉 `<TestLists>` | RED | PASS（S5x） |
| S6 | `TestEntry` 指向没有定义的 testId | RED | PASS |
| S7 | 同一 testId 两条 `TestEntry` | RED | PASS |
| S8 | `TestEntry` 无 testId | RED | PASS |
| S9 | 两条 `TestList` 同 id | RED | PASS |
| S10 | `TestList` 无 id | RED | PASS |
| S11 | 第二个 `ResultSummary` | RED | PASS |
| S12 | 第二个 `Times` | RED | PASS |
| S13 | `TestEntry` 无 `executionId` | RED | PASS |
| S14 | 第二套件（WebDav）删掉 `<TestEntries>` | RED（`TestEntries appears 0 time(s)`） | PASS |
| R1–R6 | 行被删 / 参数换值 / 行 Passed→Aborted / 两行共享 executionId / 定义 id 互换 / 新参数行 | 各归前几层（RED / RED / RED / RED / RED / warning） | 同 |
| RE1–RE3 | 真实语料两 shell 打印 `structure=7/7 … 1952 / 104` 与 `combined tests=2056`；round 19 闸门无 `structure=`；基线与 update 脚本字节与 HEAD 相等 | PASS | — |

### 数据源与规模

- 数据源：CI run `41095080796` @ head `701bb547` 的 TRX —— `windows-tests.trx` 2,719,155 B、`webdav-tests.trx` 145,266 B（`D:/mh-verify/r12-art/mediahub-windows-47fb6f5075d21ee70a568a8cb460256b60d200bf/test-results`）。
- 真实语料结构事实：windows `UnitTest` 1952 / `TestEntry` 1952 / 定义与条目一一配对 / `TestList` 2 条（`8c84fa94-…` "Results Not in a List"、`19431567-…` "All Loaded Results"） / 结果行 testListId 全部已声明；webdav 104 / 104 / 2 / 1。
- 脚本规模：check 54,842 → **60,725 B**（+109 行），sha256 前 16 `2f255f04e5333842`，纯 ASCII；基线 164,452 B 与 update 脚本未改动。

### 残留限制

- 本层只做**单文件内部**一致：它不核对 TRX 与 CI 实际执行的测试程序集是否对应（那是第九层的 `storage`/`codeBase` 与套件表在管），也不核对 `TestEntry` 是否覆盖全部定义之外的多余属性。
- 节块唯一性按 `LocalName` 计数，不区分 `ResultSummary` 的 `outcome` 属性取值（该取值属第七层）。
- 与其他层相同：把整份 TRX 换成另一份自洽的手写文件仍可整体造假；闸门的目标是让「局部手改」留下痕迹，而不是证明测试真的跑过。

### exact-head CI 验收（2026-10-31）

- head `e3283e47` 的 windows-ci **`41212076261` = completed/failure**（`pull_request`；Android CI `41212076088` 取证时 in_progress，`core`/`feature`/`app` 树哈希与 `3a09dfbd` 完全相同 ⇒ ADR-AUTO-001 继承）：**测试门禁步骤全绿**，两套件各打印十一层台阶行，新增行 `MediaHub.Tests: structure=7/7 single blocks entries=1952 matched entries=1952 paired execution ids=1952 paired list ids=1952 lists=2`、`MediaHub.WebDav.Tests: structure=7/7 … entries=104 … lists=2`；前十层行不变（含 `identities=1952 named=1952`、`rowAttributes=1952 unique execution ids=1952 ordered row times=1952 rows within the run window=1952 uniform row fields=4`），`suites=2 baseline=suite table 2 suites`、`combined tests=2056`。
- licence `checks=47 failed=1 warnings=0 skipped=0`：唯一红是上游 `log-file-claims`（`W6-113u`）；第 47 项 `capability-map-pointers` 本轮 PASS（base 已前进到 `19beb0cc`，其 windows-ci `41210845895`/`41210841634` success）⇒ `W6-113z` RESOLVED。

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

- **数据行的参数值不钉**：只钉「某方法有几行」。行内容互换（例如 theory 的 InlineData 参数值被改写但行数不变）仍为 PASS（R7 / round 9 NC16）。要收掉这一层需要把数据行参数纳入基线，代价与噪声都显著上升，暂不做。
- **测试重命名等同移除+新增**，表现为 RED + warning，需人工确认后刷新，这与 §6.5 的口径一致。
- **口径依赖 TRX 的 TestDefinitions**：若 runner 输出结构变化导致 definition 无法解析，会走 `::warning::` 与配置校验，而不是静默判绿。

**exact-head CI 验收（EXECUTED）**：head `8ec0d890`（merge commit `e79957c5c903…`）的 windows-ci **`41099478575` completed/success**（`pull_request`）：日志 `D:/mh-verify/r12/ci-41099478575-windows.log` 逐行命中 `MediaHub.Tests: total=1952 baseline=1952 failed=0 notExecuted=0` / `classes=113 baseline=class table 113 entries` / `methods=1637 baseline=method table 1637 methods` / **`methodCases=1952 baseline=method case table 1952 cases`** / `MediaHub.WebDav.Tests: total=104 baseline=104` / `classes=4 ... 4 entries` / `methods=95 ... 95 methods` / `methodCases=104 ... 104 cases` / `combined tests=2056`；licence `check-licence-surface  checks=27 failed=0 warnings=1 skipped=0`，`sbom-inventory` / `sbom-source-revision`（对 merge commit `e79957c5c903` 判祖先）/ `sdk-pin-matches-report 10.0.113` 全 PASS；探针 `MPV_PROBE PASS … GPU/HDR/audio output NOT exercised -> UNVERIFIED` 与 `ENGINE_PROBE PASS …` 口径不变。同 head Android CI `41099478811` 取证时 in_progress（本轮无 Android 代码改动）。 ⇒ 新的第四层口径在 CI 上确实打印了自己的台阶行，不是只在本机绿。

**文档提交的 exact-head CI 验收（EXECUTED）**：head `4461140b`（docs 三文件）的 windows-ci **`41100655895` completed/success**，台阶行与 `8ec0d890` 完全一致（`methodCases=1952 baseline=method case table 1952 cases` / WebDav `104 … 104 cases` / `methods=1637 … 1637 methods` / `combined tests=2056`）、licence `check-licence-surface  checks=27 failed=0 warnings=1 skipped=0` 且 `sbom-source-revision` / `sdk-pin-matches-report` PASS、探针 `MPV_PROBE`/`ENGINE_PROBE` PASS 且 GPU/HDR/audio 未跑口径不变；同 head Android CI `41100655793` 取证时 in_progress，而 core/feature/app 三棵树哈希与 `701bb547`/`8ec0d890` 全同（`ff4eed826cbc` / `e983feb08237` / `70c541457cae`）⇒ 按 ADR-AUTO-001 继承、不作独立结论。

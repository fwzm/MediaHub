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
| Fail if a suite ran nothing or lost tests | 解析 TRX，按下限表比对（MediaHub.Tests floor 400、MediaHub.WebDav.Tests floor 100） | 套件整体消失或大幅缩水时 dotnet test 仍打印 Passed，这条把「跑了个空」变成红 |
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

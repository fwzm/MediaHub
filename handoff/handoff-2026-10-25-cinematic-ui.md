# Handoff — Cinematic UI refresh（更新于 2026-10-27）

## Status

- 分支：`feature/cinematic-ui`；独立工作区：`D:/deepseek_test/mh-cinematic-ui`。
- UI 初始提交：`8f9650e78216fd58e9af6070237dd3bf2d3f8fec`；基线合并：`7f3c840330698e0d41ae0bc23f6f84f31387fb4f`。
- 已合入未合并 Draft PR #59 @ `96f051f7cb718d3e9acf10711ef413adb1d916d7`；`origin/main` 仍为 `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`，未写 main。
- 深色影院主题、共享 artwork/card/status、Home / Library / Search / Detail / Server / Settings / Player 展示层与 README / CHANGELOG 已完成。
- 保留媒体源切换清理、表单 operation/input 锁、配对入口、Provider 能力、聚合顺序、分页与播放/字幕/手势语义。
- 原有 `EffectsDemoActivity` 的 Debug Manifest 声明保留，不因本预览组件而移除。
- Debug-only `CinematicVisualActivity` 只在 `app/src/debug`，`exported=true` 仅用于显式预览与 Compose instrumentation；不进入 Release，也不接入正式导航。预览与测试均使用正式 `MediaHubTheme`。

## Executed evidence

- 旧 `:app:assembleDebug` 真实 exit 0；当时页面截图 APK SHA-256：`b22b9ad97cfd8589662d989486db320f6a5095e957fd59dc57ac04b9bd5837ab`。该哈希不背书最终合并/修订后的 APK。
- Xiaomi 14 Ultra `123e243f` / API 36：同签名 `adb install -r` Success；未卸载、清用户数据、改系统旋转或访问真实媒体服务。
- 四张 1080×2400 页面截图来自实际 Android Debug 生产 Compose 组件，使用虚构片名与本地生成素材。首页、海报墙、搜索结果、详情已人工复核；详情文字曾过暗，已修正并重新采集。
- 最新截图复核发现搜索预览只有结果区，缺正式搜索框；已抽取 `SearchContent` 补全搜索页，并给清空操作补回调断言。最终四张 1080×2400 截图已重新采集并人工复核。
- 最终截图构建 `bash-urqvgtz8`：BUILD SUCCESSFUL / exit 0，515 actionable tasks / 9 executed；截图 APK SHA-256 `7742dce27b829cf91dfc1434cadaff4e516ff5185c7a3e1ce03ef8fa34b2239c`。同签名覆盖安装成功；随后正式 MainActivity 冷启动成功。
- 四页交互测试已加入 `verify-visual-tests.py` 的 API32/36 required_tests，并由 `run-visual-validation.sh` 单独执行。
- 门禁测试先 RED：新增 coverage 用例 exit 1，8 failures（4 用例 × 2 API）；补齐后 exit 0。全套 `.github/scripts/tests`：64 tests / 0 failures。
- 中断前累计 XML 已有 278 suites / 2600 tests / 0 failures / 0 errors / 23 skips，但包含中断构建产生的部分输出，**不是一次完整全量门禁通过证据**。

## Blocked / not passed

- 真机 `CinematicVisualTest` 曾开始 4 tests，但 OEM 将测试 Activity 切到桌面且 runner 未结束，最终超时：`BLOCKED_ENV`，不得写 PASS；API32/36 CI instrumentation 另证。
- `bash-wuybx9f8` 旧门禁 1200 s runner timeout；`bash-v95t1i2f` 首次最终门禁误设 300 s runner timeout。两次都没有 BUILD SUCCESSFUL / 最终退出码，不算通过；原始日志已保留。
- 早期 Windows JVM native-memory 失败在 emulator 与 Gradle 重叠时发生；`hs_err_*` / `replay_*` 已移到 `D:/deepseek_test/mh-cinematic-evidence/build-failures`，没有删除失败证据。自建空闲 AVD 已关闭释放内存，没有删除 AVD。
- 后续 APK/testAPK 构建真实 exit 1：测试 helper 缺 `androidx.activity.compose.setContent` 扩展导入；已修复并重新编译。原始 `cinematic-apk.log` 保留，不将先前成功构建升级为最终 PASS。
- `bash-bcuptkhc` 强制重跑约 1379 s 后 exit 1：`Gradle build daemon has been stopped: stop command received`。这是外部停止，不是测试断言失败；后续改用独立 `org.gradle.daemon.registry.base`，不终止他人构建。
- 最终 `bash-kfb4v6ms` 完整强制门禁 exit 0 / BUILD SUCCESSFUL：1213 actionable tasks / 1213 executed / 47m8s；295 XML suites / **2734 tests / 0 failures / 0 errors / 23 skips**。
- `assembleDebug`、`lintDebug`、`app:assembleDebugAndroidTest` 均在该次完整命令内成功；22 份 lint XML / 0 errors / 125 warnings（未宣称零警告）。
- 最终 Debug APK SHA-256：`11dd22d4a717a9c6268dae2037016850149cedd59e45f45884213808ff1fd6f0`。日志：`D:/deepseek_test/mh-cinematic-evidence/cinematic-release-gate.log`。
- 发布提交：`eda3e525f5e47b3970f6c2b82dee54a28008a9d0`；已普通 push `feature/cinematic-ui` 并创建 [Draft PR #73](https://github.com/fwzm/MediaHub/pull/73)（base=main，未合并）。
- 新 head 已触发 Android CI `40525066312`、windows-ci `40525066278`、Sync utility safety `40525066379`；当前 CI_PENDING，待实际结果。真机 instrumentation 仍 BLOCKED_ENV。

## Rules and next steps

- G7：DO_NOT_MERGE。只允许普通提交到 feature 分支并创建 Draft PR；不合并、不 close、不 mark-ready、不 force push。
- 外部台账：`D:/deepseek_test/mh-dsh-longrun-20261014/{queue.json,RUNLOG.md,TEST_MATRIX.md}`，与其他任务并存，不覆盖他人的工作。
- 任何后续代码改动都必须重新绑定测试、APK、截图和 CI；旧 head 证据不能给新 head 背书。

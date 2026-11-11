# 2026-10-18 播放控制与 GitHub 门禁审查

## 候选身份与范围

- `git fetch origin` exit 0。审查入口为 main-facing Draft #45，base `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`，head `d9c8d7e4d2cfa5d9e4783e42e72c3f73f95909a1`；开场均未漂移。
- GitHub run `38726271624` 的 build / API 32 / API 36 三 job success，只为上述既有 head 背书。
- 修复分支 `codex/review-playback-controls-20261018` 从该联合候选建立。未修改 `main`、既有候选分支或他人未提交内容。
- 收尾再次 fetch 时 #45 已前移至 `c8299281d22bf8525182c08abacd33ded0fe15c0`：新增观测接线与 Emby 403 处理，含 `PlayerFactory` 变更。自己的未发布修复提交已无冲突 rebase 到该新基线，未改写远端历史。
- 主 checkout 的 17 项未跟踪资产保留。观测性 worktree 有其他工作正在执行，其改动未纳入本修复。
- 本轮按队列首项 G3 审查暂停/定位链路，以及对应 GitHub 测试门禁和构建说明；不代表 25 模块已经逐行审查或独立批准。

## 已证明的问题与修复

1. **Media3 缓冲/输出受抑制时切换错误。** 原实现以 `player.isPlaying` 分支；缓冲或输出抑制会使其为 false，暂停点击因而调用 resume，并保留自动继续播放意图。RED 分别命中两个断言失败。修复按 `player.playWhenReady` 切换，保留结束状态的既有 resume 行为。
2. **mpv 使用迟到的 UI 状态决定原生指令。** 首个 pause 观察回调之前，原实现把暂停发成继续；连续切换而回调延迟时，原生写入为 `[true, true]`，期望为 `[true, false]`。RED 两项均失败。修复在已有 nativeLock 下读取原生 pause 值再取反；读不到值时不伪造成功事件，第三项 RED 断言也证实原行为有误。
3. **按钮未区分播放意图与实际出帧。** `PlaybackUiState` / 组合状态增加播放意图，Media3 listener 与 mpv 观察/控制路径同步该值。按钮使用播放意图并保留结束状态显示；实际 `isPlaying` 继续用于播放/视觉状态。
4. **CI 缺少暂停/定位用例的强制执行要求。** 新增真实 PlayerRoute 的 pointer 点击、暂停后进度冻结、50% 定位仍暂停、继续后进度恢复断言；引擎/媒体时间受控。API 32/36 清单及 unit required suites 均补上门禁，缺失/跳过/失败不得放行。
5. **README 构建说明过时且测试不完整。** 将 Gradle 8.9 / compile SDK 35 改为实际 wrapper 8.14 / SDK 36；完整单元命令加入三个独立 JVM task，并给出 Windows JDK 与串行构建参数。

## 执行证据

- 下列 307 单测、lint、受控真机 APK 是 **d9c8d7e + 本修复** 的移基前证据，不冒充 c829928 新集成基线的验收。移基后以新 Draft PR 的 exact-head CI、本机 `rebased-regression.log` 和收尾交接分别核验；提交后新增的执行结果登记在 PR 与外部台账，不改写本段原始证据。
- 本机证据目录：`D:\deepseek_test\mh-review-evidence-20261018`。原始日志/XML/APK 留在本机；仓库只记录脱敏结论。
- 所有本轮 Gradle 命令逐进程设置 JDK 21，使用 `--no-daemon --console=plain --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2048m' '-Pkotlin.compiler.execution.strategy=in-process'`。
- RED：`:player:engine:testDebugUnitTest --tests '*PlaybackEnginePlaybackControlTest' :player:mpv:testDebugUnitTest --tests '*MpvPlaybackEngineTest' --continue --rerun-tasks --no-build-cache`，exit 1；Media3 3 tests / 2 failed，mpv 26 tests / 3 failed。`controls-red3.log` 及 `red3-*-xml` 保留失败。
- 早期 `controls-red.log` 为 PowerShell 参数解析失败；`controls-red2.log` 为测试夹具未配置 DefaultTrackSelector。均保留，均不计为产品 RED。
- CI gate RED：新增必跑断言在 API 32/36 均失败；`ci-gate-red.log`，exit 1。
- GREEN：此前失败的 5 个断言全部通过；最终 Media3 控制套件 4 tests / 0 failed，mpv 控制/生命周期套件 26 tests / 0 failed。
- 完整受影响模块回归：`:player:engine:testDebugUnitTest :player:mpv:testDebugUnitTest :feature:player:testDebugUnitTest :player:engine:lintDebug :player:mpv:lintDebug :feature:player:lintDebug --continue --rerun-tasks --no-build-cache`，exit 0，`affected-regression.log`。XML：15 / 10 / 8 suites，128 / 86 / 93 tests，合计 **307 tests / 0 failures / 0 errors / 0 skipped**。
- 三模块 lint 全通过，无错误；保留 5 条警告：mpv 的 `ChromeOsAbiSupport`，页面的 `OldTargetApi`、`TypographyDashes` ×2、`UseKtx`。本轮未扩大 ABI / targetSdk 或顺带修改无关样式。
- Python 门禁首轮 52 tests / PASS，`ci-gate-green.log`；中间完整复跑 3 个 Bash 夹具超时、单独复核 2/9 超时，均为 15 秒 `subprocess.TimeoutExpired`。所有 Gradle 结束后最终复核 **52 tests / PASS / exit 0**，`ci-gate-unloaded-recheck.log`。原始失败保留在 `ci-gate-final.log` / `ci-shell-recheck.log`；未放宽超时或删除断言。
- 页面首轮 **BLOCKED_ENV**：测试方法开始前被系统后台启动策略拦截。ActivityTaskManager 明确记录 `Abort background activity starts from 10605`，目标为独立测试包的 `androidx.activity.ComponentActivity`，返回 code 102；设备 Awake、未锁屏。首轮仅在核实 fixture PID 后停止本轮测试进程，未清数据。`physical-controlled-ui.log` 中的 `ADB_EXIT=0` **不是测试 PASS**。
- 页面第二轮 **EXECUTED_PASS（实际真机上的受控引擎仪器测试）**：通过 ADB 显式前台启动独立 fixture Activity，随后使用相同 MAIN / LAUNCHER intent 恢复启动入口；仪器测试正常完成，`physical-controlled-ui-2.log`：`OK (1 test)`、`INSTRUMENTATION_STATUS_CODE: 0`、`INSTRUMENTATION_CODE: -1`、`ADB_EXIT=0 TEST_RESULT_EXIT=0`。实际断言覆盖缓冲时显示暂停、一次 pointer 点击仅触发一次切换、暂停冻结 15 秒、50% COMMIT seek 保持暂停、继续后位置推进。引擎和媒体时间为 fake / controlled，**不是原生 Media3/mpv 解码器暂停验收**。
- 测试在缓冲动画启动前冻结 Compose 时钟，以避免无限动画的同步等待；API 32/36 的执行仍需由新 head CI 单独取得。
- 独立测试 APK：package `com.mediahub.feature.player.test`，versionCode 0，同签名 `adb install -r`；设备 `123e243f`，安装后设备端 hash 与本地一致：`40F50E146B6C2C07A69F2ED298A1A4F265CD4AA7B619F892B1DFBF584E0512A1`。未安装/停止正式 MediaHub 包，未改系统旋转/后台启动权限。
- 验证范围：本机未跑全 25 模块单元门禁或正式 app APK 安装；这些不得被上述 307 tests 与测试 APK 编译代替。新 Draft PR 将执行其 head 对应的完整 CI。

## 仍未闭环

- 2026-10-15 真机原生播放的暂停冻结失败记录保留。本轮受控引擎/页面测试不能证明该历史故障已根治，不能升级为真机原生解码验收通过。
- 初始 d9c8d7e 基线的 `recordUnobservable` 无生产调用者；收尾前移的 c829928 已新增 Media3 / Coil 边界调用，本修复保留这些改动，不再把“无生产调用者”当作新基线残余。Media3 / Coil 的 429 冷却仍需另行验证与接线。
- M14 W1 仍为 `UNPROVABLE-IN-PROCESS`；未声称修复。
- 新 head 的 CI 以实际 run 为准；旧 #45 绿灯不得给本修复背书。**G7 = DO_NOT_MERGE**。

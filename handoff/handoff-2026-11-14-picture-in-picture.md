# Handoff 2026-11-14 — 画中画播放（条目 30 / 31 / 123 / 113 / 137）

## 1. 状态

| 项 | 值 |
|---|---|
| 基线 | `origin/main` = `77ef1f2fc3`（开工前 `git fetch` 核验；主工作树仍停在旧提交 `1246313c7a`，未使用） |
| worktree | `D:\deepseek_test\mh-pip-20261114` |
| 分支 | `feature/pip-playback-20261114`（从 `origin/main` 新建） |
| 本轮改动 | 生产代码 + 测试；未合并、未 mark-ready、未改 `main` |
| commit | `2f40b588`（`feature/pip-playback-20261114`），已 push 到 origin |
| 证据级别 | JVM / Robolectric 部分 `EXECUTED_PASS`；**真机 PiP 窗口行为 `NOT_RUN`** |
| G7 | `DO_NOT_MERGE`（未触碰） |

## 2. 目标与四段交付

| 段 | 内容 | 状态 |
|---|---|---|
| 1 | Manifest 与入口：`supportsPictureInPicture`、PiP 策略/参数构造/会话、播放页「画中画」按钮 | 完成 |
| 2 | 后台自动进入与开关：`autoPictureInPicture` 偏好、设置页开关、API 31+ 系统自动进入 / API 26–30 `onUserLeaveHint` 回退 | 完成 |
| 3 | 窗口内控制：快退 10 秒 / 播放暂停 / 快进 10 秒，`RemoteAction` → 广播 → 播放引擎 | 完成 |
| 4 | 连播与字幕：PiP 内切集不退出、标题/动作刷新；窗口比例取视频比例以保持字幕相对位置 | 完成 |

## 3. 改动清单（相对 `origin/main`）

新增：
- `feature/player/src/main/kotlin/com/mediahub/feature/player/pip/PictureInPicturePolicy.kt`
- `.../pip/PictureInPictureSession.kt`
- `.../pip/PictureInPictureController.kt`
- `.../pip/PictureInPictureActionReceiver.kt`
- `.../pip/PictureInPictureSessionEntryPoint.kt`
- `feature/player/src/main/AndroidManifest.xml`（接收器 `exported=false`）
- 测试：`feature/player/src/test/.../pip/`（策略 / 会话 / 参数构造 / manifest 契约）
- `app/src/test/kotlin/com/mediahub/app/PlayerPictureInPictureManifestTest.kt`

修改：
- `app/src/main/AndroidManifest.xml`：`supportsPictureInPicture` / `resizeableActivity`（`configChanges` 已覆盖 PiP 改尺寸）
- `app/.../MainActivity.kt`：PiP 参数刷新、`setAutoEnterEnabled`（API 31+）、`onUserLeaveHint`（26–30）、进出 PiP 状态
- `feature/player/.../PlayerScreen.kt`：入口按钮、会话快照、窗口动作分发、PiP 内隐藏 Compose 控制层/手势层/自定义返回
- `feature/player/.../PlayerViewModel.kt`：连播判定收敛到 `PictureInPicturePolicy.allowsAutoAdvance`
- `feature/settings/.../SettingsScreen.kt` + 中英文字符串：自动画中画开关（设备不支持时不显示）
- `core/model/.../UserPreferences.kt`、`core/database/.../UserPreferencesStore.kt`：新偏好持久化（默认 `true`）
- `core/common/.../BackupDtos.kt`、`feature/settings/.../BackupRepository.kt`：备份 JSON 追加字段（旧备份缺省 `true`）
- `feature/settings/.../RestoreSnapshotStore.kt`：保护快照格式 v3，新字段**追加在末尾**，v1/v2 仍可读
- `README.md`：画中画能力、开关与已知限制

## 4. 真实执行记录（均为 `--no-parallel --max-workers=1 --no-daemon`）

| # | 命令（省略公共参数） | 结果 |
|---|---|---|
| 1 | RED：`:feature:player:testDebugUnitTest --tests "com.mediahub.feature.player.pip.*"`（实现前） | `EXIT=1`，编译失败：`PictureInPicturePolicy/Session/Controller` 不存在 |
| 2 | GREEN：同一命令（`--rerun-tasks --no-build-cache`） | `EXIT=0`，**26 tests / 0 fail** |
| 3 | `:feature:player:testDebugUnitTest --rerun-tasks --no-build-cache`（全模块） | `EXIT=0`，**155 tests / 0 fail** |
| 4 | `:core:database:testDebugUnitTest --tests "...UserPreferencesStoreTest"` | `EXIT=0`，**8 tests / 0 fail**（含关闭状态跨 store 重建持久化） |
| 5 | `:feature:settings:testDebugUnitTest` + `:core:common:testDebugUnitTest` | `EXIT=0`，**440 + 682 tests / 0 fail**（旧 v1 快照仍可解码） |
| 6 | `:app:testDebugUnitTest --tests "...PlayerPictureInPictureManifestTest"` | `EXIT=0`，入口 Manifest 契约通过 |
| 7 | `:app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-build-cache`（最终 head 复跑） | `EXIT=0`，**31 tests / 0 fail**；`app-debug.apk` 生成（130,598,543 bytes） |
| 8 | `:feature:player:lintDebug --rerun-tasks --no-build-cache` | `EXIT=0`，**0 errors**；修掉了 `setTitle` 的 API 33 `NewApi`（低版本跳过标题） |

未运行：真机安装/操作、任何真实媒体服。

## 5. 真机 `NOT_RUN` 与验证条件

设备基线：Xiaomi 14 Ultra / serial `123e243f` / API 36（需先 `adb devices -l` 确认在线）。低版本路径需要 API 26–30 设备或模拟器，目前**没有**。

必须逐条确认（任一未做都不得写成通过）：
1. **手动入口（30）**：播放中点击标题栏「画中画」，窗口出现且播放不中断。
2. **后台自动进入（30/31）**：开关开启、播放中按 Home → 自动进入；关闭开关后重复 → 不进入（仅手动入口可用）。
3. **低版本回退**：API 26–30 上按 Home 走 `onUserLeaveHint` 进入；API 31+ 不得重复进入/抖动。
4. **窗口内控制（123）**：记录进入前位置，点快退应 -10s（片头处钳制 0）；播放/暂停图标与状态同步；快进 +10s（片尾处钳制时长）。
5. **连播（113）**：第 1 集播完，在 PiP 中自动切到第 2 集，窗口不退出、标题更新；关闭「自动连播下一集」后不得继续。
6. **字幕位置（137）**：同一时间点分别截全屏与 PiP，字幕底部相对视频区域比例一致；超宽/竖屏视频各验一次（比例会被钳制在 1:2.39–2.39:1）。

约束：只允许同签名 `adb install -r`；不得访问真实 Emby/Jellyfin/WebDAV 账号；用本地夹具 + 每轮不同 itemId，位置取中段 15%–60%。

## 6. 已知风险 / 边界

- `onUserLeaveHint` 与 `setAutoEnterEnabled` 在 MIUI/OEM 上的实际表现可能与 AOSP 不同，必须真机确认。
- `PictureInPictureParams.setTitle` 仅 API 33+ 可用，低版本窗口不显示自定义标题（已按版本降级，不影响动作与比例）。
- PiP 窗口比例由系统最终决定；若 OEM 强制另一比例，字幕相对位置仍可能有差异（代码侧已按视频比例请求）。
- PiP 内隐藏 Compose 控制层/手势层属于产品取舍；若真机需要窗口内自绘控制，需另开增量。
- 保护快照 v3 只保证「新读旧」；旧版本 App 读 v3 快照不在兼容承诺内。

## 7. 下一步

1. ~~完成 `:app:testDebugUnitTest + assembleDebug` 门禁并记录 exit code。~~ 已完成（31 tests / 0 fail，exit 0）。
2. ~~commit 并 push 到 `feature/pip-playback-20261114`。~~ 已完成（`2f40b588`）；未创建/未更新 PR，未合并、未 mark-ready。
3. 按 §5 在真机上补 `EXECUTED_PHYSICAL_DEVICE_PASS/FAIL`，逐条替换 `NOT_RUN`。
4. 若需要 PR：由主会话按仓库流程创建 Draft PR，禁止合并（G7 = `DO_NOT_MERGE`）。

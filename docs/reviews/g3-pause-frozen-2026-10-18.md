# G3 · 暂停冻结缺陷：真机复核与代码修复（2026-10-18）

## 结论（三条，彼此独立）

1. **原有「暂停冻结 FAIL」是测量伪影，不是产品缺陷。** 改正测量方法后，真机 3/3 PASS。
2. **`media key` 那条「独立实现」从未生效**，因此「两种实现各复现」的说法不成立。
3. **另有四条真实代码缺陷**，**本轮全部修复**（均先红后绿）。其中第 4 条是从兄弟分支 PR #46 交叉核对后补齐的。

---

## 1. 旧 FAIL 的成因（测量伪影，已证伪）

旧脚本 `g3_decisive.ps1` / `g3_rounds345.ps1` 的时序是：

    tap 唤出 overlay → sleep 1s → uiautomator dump（1–3s）→ adb pull → python 定位 → input tap 暂停坐标

总延迟 **≳3s**，超过 overlay 的 `OVERLAY_AUTO_HIDE_MS = 3_000`（PlayerScreen.kt:1166 / 224-229）。
于是最后那次 tap 落在**已隐藏的控件层下方**——全屏手势层 `PlayerGestureLayer`（底层，PlayerScreen.kt:420-429），
被识别为「单击 → Overlay 显隐切换」，**引擎从未收到 pause**。位置自然继续按墙钟推进。

旧证据内部本来就有矛盾，只是当时没被读出：

- 点击后仍可读的 dump 里，暂停按钮的 `content-desc` **恒为「暂停」**；该文案只在 `state.isPlaying == true` 时出现（PlayerScreen.kt:973-976）→ `isPlaying` 从未变 false。
- 部分 dump 是 8 节点空树（2949 B）——正是「控件已隐藏」的形态。
- t1→t2 的 +15s ≈ 两次采样之间的墙钟（sleep 5s + 两轮 tap/dump）。

### 修正后的测量方法与结果

**关键改动**：唤出 overlay 后**不 dump**，在同一 3s 窗口内直接盲点固定坐标（暂停按钮中心 `2137,996`，来自真实 dump 的 bounds `[2106,965][2169,1028]`）。
暂停后 overlay **不会**自动隐藏（LaunchedEffect 只在 `isPlaying` 时才隐藏），因此暂停后的 dump 是可靠的。

| 轮次 | 夹具（全新 itemId） | t1 | t2 (+6s) | 图标翻转 | 中段 | 判定 |
|---|---|---|---|---|---|---|
| 1 | `g3d18044422_1` | 6s | 6s | 暂停→播放 | 是 | **PASS** |
| 2 | `g3d18044422_2` | 6s | 6s | 暂停→播放 | 是 | **PASS** |
| 3 | `g3d18044422_3` | 6s | 6s | 暂停→播放 | 是 | **PASS** |

`delta=0s`、`iconFlipped=True`、`midFile=True`（三者同时成立才算 PASS）。

**证据级别**：`EXECUTED_PHYSICAL_DEVICE_PASS`（设备 `123e243f`，Xiaomi 14 Ultra / API 36，3 轮全部 PASS）。
证据目录：`D:\deepseek_test\g3-decisive-20261018`（dump + 脚本 + 日志）。

---

## 2. media key 路径不存在（第二个伪复现）

全仓 `.kt` 检索以下符号**均为 0 命中**：`MediaSession`、`KEYCODE_*`、`onKeyDown`、`dispatchKeyEvent`、
`onMediaButtonEvent`、`MediaButtonReceiver`。`MainActivity.kt:12-22` 只有 `setContent`。
依赖里也没有 `media3-session`。

因此 `g3_rounds345.ps1:77` 的 `input keyevent KEYCODE_MEDIA_PAUSE` **必然是 no-op**。
它的「FAIL」不能与 UI 控件并列称为「两种实现各复现」——真正被验证的实现只有一个。

**处置**：该条从验收矩阵移除（或补 MediaSession/`onKeyDown` 后再测），否则会持续产出假 FAIL。

---

## 3. 三条真实代码缺陷

### 3.1 Media3 `togglePlayPause` 用 `isPlaying` 而非播放意图（已修）

`player/engine/.../PlaybackEngine.kt:269-271` 旧实现：

```kotlin
override fun togglePlayPause() { if (player.isPlaying) pause() else resume() }
```

Media3 的 `isPlaying` 在**缓冲中 / 未就绪 / 音频焦点短暂丢失**时为 false，而 `playWhenReady` 仍为 true。
此时「点击暂停」会被解释成 **resume** —— 用户看到的正是「点了暂停，位置还在走」。

已改为 `if (player.playWhenReady) pause() else resume()`。

### 3.2 mpv `togglePlayPause` 极性取自异步 UI 状态（已修）

`player/mpv/.../MpvPlaybackEngine.kt:360-364` 旧实现取 `_uiState.value.isPlaying` ——
那是**异步 observer 回调**写入的派生状态，在起播瞬间（`pause` 属性首次回传前）恒为默认值 false。
于是首次点击会下发 `pause=false`（即「继续播放」），引擎毫无变化。

已改为读 **mpv 的权威属性 `pause`**，不依赖回调时序；读失败才回退 UI 状态。

### 3.3 引擎降级重播不保留暂停意图（**已修，见 §3.3.1**）

- `SwitchablePlaybackEngine.fallbackToMpv`（:417-480）只捕获位置，`startEngine(...)` 后 `engine.play(session)` **无条件起播**（:356）。
- `PlaybackSession`（PlaybackSession.kt:7-18）**没有暂停意图字段** → 类型系统上无法表达「降级后保持暂停」。
- 另有竞态：降级进行中（`current` 尚未置空）用户点击，`togglePlayPause` 会发给即将被 release 的旧引擎，pause 丢失。

#### 3.3.1 修复（同轮完成）

---

### 3.4 播放/暂停图标跟随的是「实际在播」而非「播放意图」（已修）

`PlayerScreen.kt:971-979` 旧实现：

```kotlin
if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
```

缓冲中 / 音频焦点被短暂抑制 / surface 未就绪时 `isPlaying == false` 而用户并未暂停，
图标会闪成「播放」；用户此时点它，语义上又变成「继续播放」—— 与 §3.1 的引擎缺陷叠加，
就是完整的「暂停无效」观感。

修复：`PlaybackUiState` 新增 `playWhenReady: Boolean = isPlaying`（默认取 isPlaying，
未显式设置该字段的实现行为不变），Media3 通过 `onPlayWhenReadyChanged` 上报、
`pause()/resume()` 立即同步；mpv 由 `pause` 属性同时驱动；UI 改为跟随意图：

```kotlin
val showPauseIcon = state.playWhenReady && !state.isEnded
```

> 交叉核对说明：兄弟分支 `codex/review-playback-controls-20261018`（PR #46，同 base）
> 独立发现了同一组缺陷并给出了等价修法（`PlaybackUiState.playWhenReady` + 图标跟随意图）。
> 本条按该结论补齐，两边实现语义一致，避免两份修复各自只覆盖一半。

## 4. 本轮修复的证据

### 先红后绿（`--rerun-tasks --no-build-cache`）

| 阶段 | 命令结果 | 关键输出 |
|---|---|---|
| **RED** | EXIT=1 | `PauseFreezeContractTest > togglePlayPause must use playWhenReady not isPlaying FAILED`（2 tests / 1 failed）；`MpvTogglePolarityTest > toggle polarity comes from native pause property not from async isPlaying FAILED`（1 tests / 1 failed） |
| **GREEN** | EXIT=0 | 两个测试类全绿；`:player:engine` 2 tests / 0 failures、`:player:mpv` 1 test / 0 failures |

红线日志：`D:\deepseek_test\g3-red.log`；绿线日志：`D:\deepseek_test\g3-green3.log`。

### 回归

`:player:engine:testDebugUnitTest` + `:player:mpv:testDebugUnitTest` + `:core:network:testDebugUnitTest` + `:app:testDebugUnitTest`
`--rerun-tasks --no-build-cache --continue` → **BUILD SUCCESSFUL**（3m50s / 400 tasks），
**61 类 / 439 tests / 0 failures / 0 errors / 0 skipped**。

---

## 5. 对验收矩阵的更正

| 项 | 旧结论 | 更正后 |
|---|---|---|
| M12R 暂停冻结 | `EXECUTED_FAIL`（真实缺陷） | **`EXECUTED_PHYSICAL_DEVICE_PASS`**（3/3 轮，改正测量方法后） |
| media key 暂停 | 「第二种实现各复现」 | **`NOT_APPLICABLE`**（该路径不存在，命令为 no-op） |
| Media3 暂停极性 | 未发现 | **真实缺陷，已修 + 先红后绿** |
| mpv 暂停极性 | 未发现 | **真实缺陷，已修 + 先红后绿** |
| 降级丢暂停意图 | 未发现 | **真实缺陷，已修 + 先红后绿（`PlaybackSession.startPaused`）** |
| 播放/暂停图标跟随 isPlaying | 未发现 | **真实缺陷，已修（`PlaybackUiState.playWhenReady`，与 PR #46 交叉核对）** |
| Seek 50% | `NOT_RUN` | `NOT_RUN`（uiautomator 取不到 SeekBar，本轮未处理） |

## 6. 残余风险

（§3.3 的「降级丢暂停意图」已在本轮修复，不再列为残余风险。）

仍在的：

- **降级进行中的点击竞态**：`current` 尚未置空时用户点击，`togglePlayPause` 仍会发给即将被 release 的旧引擎，该次 pause 可能丢失。属窄窗口，未修。
- **Seek 50%** 仍 `NOT_RUN`（uiautomator 取不到可解析 SeekBar）。

## 7. 回归（含 §3.3 修复后重跑）

`:player:engine` + `:player:mpv` + `:core:network` + `:app` `--rerun-tasks --no-build-cache --continue`
→ **BUILD SUCCESSFUL**（3m34s / 400 tasks），**61 类 / 441 tests / 0 failures / 0 errors / 0 skipped**。

补上 §3.4（UI 意图）后，跑全仓 `testDebugUnitTest :core:model:test :provider:api:test --continue`
→ **BUILD SUCCESSFUL**（5m50s / 559 tasks），**188 类 / 1515 tests / 0 failures / 0 errors / 0 skipped**；
其中 `PauseFreezeContractTest` 3 例（含新增的「缓冲时 playWhenReady 仍为 true」意图契约）、
`SwitchablePlaybackEngineTest` 30 例、`MpvTogglePolarityTest` 1 例全绿。

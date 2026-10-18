# SEEK-B-fix · EOF 后 UI 状态机缺陷（V13）

**分支**: `feature/seek-a-real-component-test`（在 SEEK-A 基础上追加）
**基线**: `3fb7c1b`

## 1. 真机发现的缺陷（EXECUTED_FAIL）

设备 `123e243f`，APK `3fb7c1b`，把进度拖到片尾后：

| 观测 | 结果 |
|---|---|
| UI 文本 | `3:04 / 3:05`（184s / 185s） |
| 按钮 content-desc | **「暂停」**（应用仍认为在播放） |
| 两次间隔 2s 截图 sha256 | **完全相同**（画面冻结） |
| 烧录时码 | `00:03:04.933`（确实在片尾） |

**后果**：画面冻结在最后一帧，但按钮仍显示「暂停」，用户**无法用播放按钮恢复**（点它只是 pause 一帧静止画面）。需返回/重选片才能继续。

## 2. 根因

`MpvPlaybackEngine` 的 `END_FILE` 事件处理只发事件，**从不更新 UI 状态**：

```kotlin
// 修复前
MpvInstance.Event.END_FILE -> _events.trySend(PlaybackEvent.Ended)
```

而进度循环里的 `eof-reached` 探测（`line ~586`）在 mpv 结束播放后**不一定仍可读**：

```kotlin
val ended = resources.mpv.getPropertyBoolean("eof-reached") ?: false
```

于是 `_uiState.isEnded` 永远为 false，G3 已加的 UI 判据失效：

```kotlin
val showPauseIcon = state.playWhenReady && !state.isEnded   // isEnded 恒 false → 恒显示暂停图标
```

## 3. 修复

```kotlin
// 修复后
MpvInstance.Event.END_FILE -> {
    withCurrent(run) {
        _uiState.update { it.copy(isEnded = true, playWhenReady = false) }
        _events.trySend(PlaybackEvent.Ended)
    }
}
```

同时置 `isEnded = true` 与 `playWhenReady = false`，让 G3 的 UI 判据真正生效。

## 4. 先红后绿

**RED**（修复前）：

```
FAIL: END_FILE marks the session ended and no longer playing
  java.lang.AssertionError: EOF 后 isEnded 必须为 true（否则 UI 仍显示「暂停」）
```

**GREEN**（修复后）：

```
tests=1 failures=0
```

测试 `MpvEndedStateContractTest` 的关键设计：fake instance 的 `getPropertyBoolean("eof-reached")` **返回 null**
（模拟真机上 mpv 结束后属性不可读），从而证明修复**不依赖**进度循环探测。

三重断言：
1. `isEnded == true`；
2. `playWhenReady == false`；
3. 仍恰好发出一次 `PlaybackEvent.Ended`（进度同步不受影响）。

## 5. 回归

`:player:mpv` + `:player:engine` + `:feature:player` `--rerun-tasks --no-build-cache --continue`
→ **BUILD SUCCESSFUL**（2m22s / 184 tasks），**46 类 / 367 tests / 0 failures / 0 errors / 0 skipped**。

## 6. 遗留

- **Media3 路径**：已有 `onPlaybackStateChanged(STATE_ENDED) → isEnded = true`（`PlaybackEngine.kt:153`），
  看起来正确；但**未在真机验证** Media3 引擎到 EOF 的行为（本轮真机走的是 mpv 路径）；
- 修复后**尚未重新真机复核**（需重装 APK 后重跑 EOF 场景）—— 见 §7 待办。

## 7. 待办

```
cd D:\deepseek_test\mh-seek-a
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 然后重跑 EOF 场景：拖到片尾 → 断言按钮变「播放」、画面可重播
```
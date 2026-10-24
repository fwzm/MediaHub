# V13 · SEEK-A / SEEK-B 交付证据

**会话**: V13（MODE_B，非 24h 授权） | **日期**: 2026-10-18

---

## 1. SEEK-A：消灭「测试绿但没测到真实代码」

**分支**: `feature/seek-a-real-component-test` | **PR**: #54 | **commit**: `3fb7c1b`

### 问题

PR #53 的 4 个 Compose Seek 测试在 `setContent` 里复制了一份 Slider；把生产 `onValueChange` 改成「变更即提交」后**仍然 4/0 全绿**（变异存活）→ 只能证明 Compose 框架语义。

### 修法

抽取生产组件 `PlayerSeekBar`，由 `PlayerControls` **真实引用**（V13 §03.6 允许路径）。新增 `PLAYER_PROGRESS` testTag（仅语义标识）。

### 测试

`PlayerSeekBarContractTest`（4 例），用**真实触摸** `performTouchInput { swipe(...) }`（不用 `SetProgress`，它在 Robolectric 下会连带触发 `onValueChangeFinished`）。

### 双变异验证（关键）

| 变异 | 结果 | 证据 |
|---|---|---|
| **MUT-1**：生产 `onValueChange` 改为同时提交 | **杀死**（4/2） | `实际=[7777, 10064, ..., 40472] expected:<1> but was:<18>` |
| **MUT-2**：移除 `onValueChangeFinished` 提交 | **杀死**（4/2） | `实际=[] expected:<1> but was:<0>` |
| 恢复 | GREEN | 4/0 |

→ **SEEK-UI 门禁达成**。

---

## 2. SEEK-B：真机定量 Seek

**APK**: `3fb7c1b`，sha256 `26FD1117…`（后续 EOF 修复版为 `BADCBE15…`）
**夹具**: ffmpeg 185s + 烧录时码 `HH:MM:SS.cs`，每轮独立 itemId

| 目标 | 轮次 | PASS | 误差 |
|---|---|---|---|
| 25% | 5 | **5** | 2–3s |
| 50% | 3（补测） | **3** | 0–1s |
| 90% | 5 | **5** | 0–2s |
| 连续 10 次拖动 | 10 | **10** | 0–4s |
| 右边界 | 1 | ✅ 钳制到 184/185s | — |
| 左边界 | 1 | ✅ 截图时码 5.6s | — |

**主矩阵 50% 有 4 轮 `UNREADABLE`**（overlay 隐藏后的空树，测量时序），按 §04.5 逐轮记录、不挑绿；独立补测 3/3 PASS。

---

## 3. 发现的真实缺陷：EOF 后 UI 状态机（已修 + 真机复核）

### 缺陷（修复前）

| 观测 | 值 |
|---|---|
| UI | `3:04 / 3:05` |
| 按钮 desc | **「暂停」**（仍认为在播放） |
| 两次 2s 截图 | **sha256 完全相同**（冻结） |

用户**无法**用播放按钮恢复。

### 根因

`MpvPlaybackEngine` 的 `END_FILE` 只发事件、**从不更新 `_uiState.isEnded`**；进度循环的 `eof-reached` 在 mpv 结束后不一定可读。于是 G3 的 UI 判据 `showPauseIcon = playWhenReady && !isEnded` 永远不生效。

### 修复（commit `fef6f35`）

```kotlin
MpvInstance.Event.END_FILE -> {
    withCurrent(run) {
        _uiState.update { it.copy(isEnded = true, playWhenReady = false) }
        _events.trySend(PlaybackEvent.Ended)
    }
}
```

### 先红后绿

- **RED**：`AssertionError: EOF 后 isEnded 必须为 true`
- **GREEN**：`tests=1 failures=0`
- 测试设计：fake 的 `getPropertyBoolean("eof-reached")` **返回 null**（模拟真机属性不可读），证明修复不依赖进度循环探测
- 回归：`46 类 / 367 tests / 0 failures`

### 真机复核（修复后，APK `BADCBE15`）

| 项 | 修复前 | 修复后 |
|---|---|---|
| EOF UI | `3:04 / 3:05` | **`3:05 / 3:05`** |
| 按钮 desc | 「暂停」 | **「播放」** |
| 截图 | 冻结最后一帧 | 显示播放按钮，可点击重播 |

**截图 `fin.png` 可证**：满格进度条 + 右下角播放三角 `▶` + `3:05 / 3:05`。

→ `EXECUTED_PHYSICAL_DEVICE_PASS`（EOF 状态机修复）

---

## 4. 诚实边界

| 项 | 状态 |
|---|---|
| Seek 25/50/90% | ✅ `SEEK-DEVICE PASS` |
| 连续拖动 / 边界 | ✅ PASS |
| EOF 状态机 | ✅ 已修 + 真机复核 |
| **H.264→HEVC→双音轨 MKV 切源** | ❌ `NOT_RUN` |
| **冷重启续播 / 最终进度 flush** | ❌ `NOT_RUN` |
| **Media3 引擎的 EOF 行为** | ❌ 未真机验证（本轮走 mpv 路径） |

**因此 Seek 不能宣称「全部验收完成」**。

---

## 5. 对 PR #53 的处置

- 不删除历史证据（§03 要求）；
- 标 `INVALID_FOR_PRODUCT`（框架测试），**不得**据此关闭 Seek 验收；
- 本 PR 的 `PlayerSeekBarContractTest` 为替代性有效证据。
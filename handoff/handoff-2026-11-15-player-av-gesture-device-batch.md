# 交接：播放渲染 / 手势 / 设备兼容缺陷批次（条目 7/8/32/33/34/41/46/47/87）

- 分支：`fix/av-gesture-device-batch-20261115`
- worktree：`D:\deepseek_test\mh-av-gesture-20261115`
- base：`origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`（2026-11-15 fetch 后确认 HEAD 已包含 origin/main）
- head：`49c0905b98efdd734ac4d313ce27e8130cff30ff`（4 个提交）

## 1. 状态

三批全部提交，本地 JVM 门禁 `EXECUTED_PASS`；真机项 `NOT_RUN`。

| 提交 | 内容 |
|---|---|
| `4198b209` | 批次 1 声音与渲染：多声道下混归一化 + 杜比视界不支持时路由 mpv / `hwdec=no` 兜底 |
| `76973044` | 批次 2 手势与缩放：手势分区规则、双指缩放、安全区 insets、双击边缘显隐设置项 |
| `0be03863` | 批次 3 设备兼容：共享纹理失败重绑 Surface、SAF 厂商异常归类 |
| `49c0905b` | README「播放画面与手势」能力说明 |

### 条目映射

| 条目 | 落地 | 证据级别 |
|---|---|---|
| 7 音频偏小 | `MpvAudioGainPolicy` 识别 AC3/EAC3/DTS/DTS-HD/TrueHD/MLP/FLAC/PCM 多声道 → `audio-normalize-downmix=yes`；不改 `volume-max` | `EXECUTED_PASS`（单测） |
| 8 杜比后台画面异常 | DV + 设备 `dolbyVision` 证据 `UNSUPPORTED` → `PlaybackEngineSelector` 路由 mpv，并 `hwdec=no` + tone-mapping | `EXECUTED_PASS`（路由/调参单测）；真机 `NOT_RUN` |
| 32 状态栏遮挡 | `PlayerInsetPolicy`：顶部 `max(statusBars, displayCutout)`、左右 cutout、底部 `max(navigationBars, systemGestures)`，负值归零 | `EXECUTED_PASS`（insets 单测）；刘海屏真机 `NOT_RUN` |
| 33 手动缩放 / 恢复 | 双指缩放 1.0×–3.0×，`SurfaceView` + 字幕层 `scaleX/scaleY`；控制栏出现「还原画面」 | `EXECUTED_PASS`（策略/控制器单测）；触控真机 `NOT_RUN` |
| 34 双击两侧显隐设置项 | `PlayerGestures.doubleTapSidesToggleOverlay`；开启后最外侧 10% 显隐，10–40/40–60/60–90 保留快退/暂停/快进 | `EXECUTED_PASS`（分区 + 控制器 + DataStore 单测） |
| 41 上滑误触亮度/音量 | `isReservedSystemSwipe`：起点落在 `systemGestures.bottom` 保留区 → 手势层直接放行给系统 | `EXECUTED_PASS`（分区单测）；手势导航真机 `NOT_RUN` |
| 46 杜比解码失败黑屏 | 同条目 8：明确不支持时软解 + 色调映射；`SwitchablePlaybackEngine` 仍保留 `DECODER_ERROR/UNSUPPORTED_CODEC` 降级 | `EXECUTED_PASS`（选择器/降级单测）；真实 HDR 设备 `NOT_RUN` |
| 47 共享纹理失败 | `SURFACE_ERROR` 先 `attachSurface(null)` → 120ms → 重挂 → 原位 `seekTo(COMMIT)` → 恢复播放意图，每 session 一次；仍失败降级 mpv，文案「画面渲染失败，已尝试恢复」 | `EXECUTED_PASS`（Robolectric 双例）；真实纹理失败 `NOT_RUN` |
| 87 数据访问失败 | `SafAccessFailurePolicy` 把 `SecurityException` 之外的厂商异常（`IllegalArgumentException/UnsupportedOperationException/IllegalStateException/FileNotFoundException`）统一归为 `DirectoryAccessRequired` | `EXECUTED_PASS`（provider:local 单测）；真实厂商 SAF 异常 `NOT_RUN` |

### 门禁（真实执行）

命令（`JAVA_HOME=jdk-21`，串行）：

```
.\gradlew.bat :player:engine:testDebugUnitTest :player:mpv:testDebugUnitTest :feature:player:testDebugUnitTest \
  :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest :provider:local:testDebugUnitTest \
  :core:network:testDebugUnitTest :core:model:test :core:common:test \
  --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL in 11m 58s`，**exit=0**，334 tasks executed。

| 模块 | suites | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| core/common | 52 | 682 | 0 | 0 | 11 |
| core/database | 27 | 277 | 0 | 0 | 12 |
| core/model | 8 | 71 | 0 | 0 | 0 |
| core/network | 36 | 233 | 0 | 0 | 0 |
| feature/player | 16 | 145 | 0 | 0 | 0 |
| feature/settings | 33 | 440 | 0 | 0 | 0 |
| player/engine | 28 | 201 | 0 | 0 | 0 |
| player/mpv | 20 | 134 | 0 | 0 | 0 |
| provider/local | 4 | 29 | 0 | 0 | 0 |

合计 224 suites / 2212 tests / 0 failures / 0 errors / 23 skipped。

### 先红后绿（可复现的 RED）

- 批次 1：把 `MpvAudioGainPolicy.shouldNormalizeDownmix` 临时改为恒 `false` → `MpvAudioGainPolicyTest` **3 tests / 2 failed**，exit=1；恢复后 exit=0。
- 批次 2：临时让 `doubleTapZone` 忽略设置项、`PlayerInsetPolicy.topDp` 只取状态栏 →
  `PlayerGestureZonePolicyTest` **6 / 2 failed**、`PlayerInsetPolicyTest` **5 / 1 failed**，exit=1；
  恢复后（含 `PlayerGestureControllerTest`）exit=0。
- 批次 3：`PlaybackFailureClassifier.classify` 临时 `return null` → **4 / 3 failed**；
  `SafAccessFailurePolicy` 只认 `SecurityException` → **14 / 3 failed**；恢复后两个模块 exit=0。
- 所有临时改动均已还原，`git status` 干净，仓库内无 `RED-EVIDENCE-TEMP` 残留。

## 2. 目标

- 让上述 9 条在**不改既有手势语义**的前提下各自成立：手势分区先落规则对象，再让双击显隐、缩放、底部系统手势分别接入。
- 只做真实实现与可注入的 JVM 测试；真机才能判定的部分如实标 `NOT_RUN`。

## 3. 失败 / 未采纳的方案

- **抬高 `volume-max` 修音频**：未采纳。会整体放大并压缩动态范围，且对多声道下混无效；改为 `audio-normalize-downmix`。
- **DV 一律转 mpv**：未采纳。`CapabilityEvidence.UNKNOWN` 时不做判定，保持 ADR-042 的「无证据不干预」。
- **共享纹理失败无限重试**：未采纳。每 session 只重绑一次，避免错误流抖动造成循环。
- **手势改成分区全重写**：未采纳。关闭设置项时 40/20/40 行为与旧版逐字节一致，防止回归。
- **`RestoreSnapshotStore` 直接复用版本 2**：未采纳。新增字段写入版本 3；旧版本快照仍可读（1..3），但新快照旧版应用读不了，属可接受的降级限制。

## 4. 下一步

1. push 分支并开 **Draft** PR（base=main），**不 merge / 不 close / 不 mark-ready**，G7 恒为 `DO_NOT_MERGE`。
2. 真机验收（全部 `NOT_RUN`，条件如下）：
   - 杜比视界片源 + 明确不支持 DV 的设备（或用 mi 14 Ultra 上 Media3 硬解失败的样本）验证不黑屏；
   - 真实共享纹理失败：需要可复现的厂商设备或注入 Surface 创建失败；
   - 刘海/挖孔屏：控制栏顶部不被遮挡（`statusBars=0` 时仍按 cutout 避让）；
   - 手势导航上滑返回桌面：从底部 `systemGestures` 区起滑不触发亮度/音量；
   - 双指缩放与「还原画面」的实际缩放效果；
   - 厂商 SAF 异常（如 MIUI 文件管理器撤权）落到「重新授权目录」。
3. 等 PR exact-head CI；旧 head 绿灯不得为新 head 背书。

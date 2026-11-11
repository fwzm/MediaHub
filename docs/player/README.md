# 播放器设计文档

## 播放源、内核与会话

播放能力由 `ProviderHandle.playback` 提供，`resolvePlayback(item, options)` 返回当前使用的 `PlaybackSource`。
[`SwitchablePlaybackEngine`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/SwitchablePlaybackEngine.kt)
负责将同一会话交给 Media3 或 mpv；
[`PlaybackEngineSelector`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEngineSelector.kt)
综合用户内核选择、已记录的兼容性签名和设备能力证据进行路由。UNKNOWN 设备证据保留默认行为，
不等于已确认不支持。

[`PlaybackCompatibilityEvaluator`](../../player/compatibility/src/main/kotlin/com/mediahub/player/compatibility/PlaybackCompatibilityEvaluator.kt)
提供媒体信息、设备能力与偏好的纯逻辑评估，结果为 DIRECT_PLAY / DIRECT_STREAM / TRANSCODE / UNSUPPORTED。
资源模式、内核路由与实际解码结果应分别判断；纯逻辑结果不能代替设备实片验收。

会话所有权贯穿解析、降级、轨道、字幕、媒体键和退出。
[`ForegroundMediaSessionController`](../../feature/player/src/main/kotlin/com/mediahub/feature/player/ForegroundMediaSessionController.kt)
把前台系统媒体键路由到当前引擎，离开前台或释放时撤下旧会话。
播放意图与当前是否正在出帧分别记录；降级重播保留暂停意图。

## 请求头与缓存

[`PlaybackEngineFactory`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEngineFactory.kt)
为每个引擎创建独立的请求头上下文；Media3 的
[`SnapshotMediaSourceFactory`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/SnapshotMediaSourceFactory.kt)
在创建每个 MediaSource 时进一步固定不可变 Header 快照。
延迟准备的 HLS/DASH 来源和已经创建的数据源不能被后一次播放的 Header 覆盖。
mpv 使用其会话拥有的本地桥与原站请求上下文；Header/Cookie 不作为共享可变全局状态。

Media3 的 [`MediaCacheProvider`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/MediaCacheProvider.kt)
使用 SimpleCache/LRU，默认 512MiB，仅缓存媒体数据；元数据、图片和未来离线下载分别管理。

## 进度、关键事件与退出

UI 进度保持在引擎内存状态。生产控制事件可通过
[`PlaybackEventSnapshot`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEvent.kt)
携带事件发生时的不可变进度，异步消费时不重新采样另一会话或较晚的 seek 位置。

[`ProgressSyncCoordinator`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/ProgressSyncCoordinator.kt)
独立采样本地快照（默认 5s）与远端上报（默认 10s，Provider 可覆写）。
Pause/Seek/Ended 立即使用事件进度刷新，不重置两条周期时钟；Stopped 只通知状态。
[`PlayerViewModel.stopAndFlush()`](../../feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt)
幂等执行引擎 stop、停止周期工作、`flushFinal(finalProgress)` 和 release，最终远端操作带有限等待。

## 轨道、字幕与错误

Media3 的音轨/字幕选择使用当前快照中的 group/track 地址与所有权标识；过期选择被拒绝。
mpv 使用自己的权威轨道属性。Off 与当前没有可选轨道分别表达，不能拿旧下标作用于新会话。
字幕中心的外挂加载与选择按会话和操作世代作废旧结果，后端自报的外挂/偏移能力决定控件是否可用。

音频输出状态以引擎观测为准，未知状态不应被当成已确认失败；编码名和设备枚举不能代替实际声音输出验收。
[`PlaybackError`](../../core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt)
保留认证、HTTP、网络、TLS、解码、DRM、转码等结构化分类；UI 使用用户文案，诊断详情脱敏。
自动连播、降级及会话恢复的代码与测试不构成所有片源、设备或后台播放能力的普遍保证。

## 播放器视觉系统

正式入口为 **播放页控制层 → 视觉效果**，以及 **设置 → 播放 → 视觉效果**。
两处使用同一个设置面板和生产 Renderer；Debug `EffectsDemoActivity` 仅供开发调试。
界面资源默认中文，`values-en` 提供英文覆盖，跟随系统/应用的资源语言。

### 偏好与预置

`UserPreferencesStore` 持久化 `PlayerVisualEffectsPreferences` 的六项设置：

- 默认开启，预置 `AURORA`，强度 `0.35`。
- 默认跟随媒体 artwork 色彩，允许音频响应，性能策略为 `AUTO`。
- 正式选择为 Off、Aurora、Liquid、Spectrum。Off 保存为 `enabled=false`，保留上次预置。
- 强度收敛到 `0..1`，异常数值和未知存储枚举安全回退。
- 恢复默认只重置视觉设置，不改方向、手势或其他播放偏好。

Aurora 使用克制的慢速流光；Liquid 提高流体扭曲；Spectrum 在相同 FlowGlow
Renderer 上消费平滑后的音频能量。设置页的实时 Preview 也走这条路径，不复制 Demo 动画。

### 主题与视频边界

`ArtworkPaletteController` 异步加载 artwork/backdrop，按媒体/图片 key 缓存并拒绝过时结果；
缺图或失败时使用预置配色。`PlayerVisualTheme` 用约 500ms 过渡驱动 SeekBar、激活控件、
音轨/字幕选中状态、buffering indicator 和控制层 surface。色彩生成包含明度与对比度约束。
Shader 时间、FFT 帧不会驱动全局 `MaterialTheme` 重组。

视觉只绘制在窄边缘和实际底部控制区域。底部 ambient 同时受全播放器高度 9% 的上限约束，
不是整段 controls 的高度；横屏及高 controls 布局仍保留字幕安全带。中央视频及该安全带不绘制 Shader，
不采样视频纹理、不更改 SurfaceView/DRM 管线、不对 HDR/Dolby Vision 画面做后处理。
这属于实现边界，不能代替真机 HDR/DRM/字幕播放验收。

### 音频与降级

Media3 路径将正数 audio session id 交给 Android `Visualizer`，FFT 经频带归一化以及
约 45ms attack / 250ms release 平滑后输出 bass、mid、treble、amplitude。
不会绑定全局 session 0，也不在生产路径使用模拟频谱。

只有用户显式选择/启用 Spectrum 或点击权限动作才可能请求 `RECORD_AUDIO`；单纯打开
播放页不会弹权限窗。拒绝权限、session 不支持、无音轨、初始化失败或超时都显示音频不可用，
继续基础视觉效果；符合权限与设备条件时可重试。mpv 未暴露兼容 audio session，明确使用不可用降级。
捕获只在受支持 Renderer、可见视觉消费方及 STARTED 生命周期下开启；Off、切 session、
后台、页面 dispose、引擎 release 都关闭旧捕获。音频样本不持久化。

API 33+ 优先 RuntimeShader；较旧 Android 或 Shader/驱动失败时使用静态渐变。
降级保留入口、偏好、预览和主题能力，并停止无用的 Shader 时钟。

### 生命周期与性能

Off、近零强度、控制层隐藏且无设置预览、STOPPED 或 dispose 均停止视觉帧循环。
`AUTO` 在用户交互时最高 60fps，普通可见状态约 30fps，低强度约 15fps；
Battery/Balanced/High 分别设为 15/30/60fps。系统省电或 reduce-motion 信号优先，
上限降至 15fps 并减小运动幅度。Settings Preview 遵守相同的生命周期与系统策略。

mpv 的 native instance/bridge 由播放 generation 拥有；旧初始化、事件和进度不能污染新会话。
不可取消的 JNI 初始化在返回后执行失效检查与清理，stop/release 不等待未发布的初始化资源。

### 验证入口与证据边界

```sh
./gradlew testDebugUnitTest :core:model:test :provider:api:test :metadata:test --rerun-tasks --no-build-cache --no-daemon --continue --no-parallel --max-workers=1
./gradlew assembleDebug lintDebug --no-daemon --no-parallel --max-workers=1

# CI 在已启动的隔离模拟器上执行完整仪器测试、备份夹具和证据记录：
bash .github/scripts/run-visual-validation.sh 36

# 已有完整证据目录时，按这次真实运行的身份复核：
python3 .github/scripts/verify-visual-tests.py --api 36 --sha "$EXPECTED_HEAD" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT"
```

Windows 本机 Gradle 进程先按仓库指引设置 JDK21；大构建还应使用约定的堆上限与进程内 Kotlin 编译。
完整仪器入口需要 CI 注入 `EXPECTED_HEAD`、`GITHUB_RUN_ID`、`GITHUB_RUN_ATTEMPT` 和已启动模拟器的
`ANDROID_SERIAL`，不是独立的模拟器创建命令。仅手动执行 connected 测试不等于已经生成修订、API、
报告哈希均绑定的证据；完整参数与门禁见 [Android CI](../../.github/workflows/android-ci.yml)。

CI 对精确 PR head 分别运行 API 32 / 36 的硬件加速 Android Canvas 仪器测试，检查
Renderer 实际像素、真实 Compose 主题与生命周期、Player/Settings 入口及 DataStore 重入。
必需测试缺失、跳过或失败均不能通过总门禁；API 不适用的 Shader/低版本专用测试除外。

Player/Settings 路径测试使用真实 UI、ViewModel、DataStore 和 Renderer，但隔离了 Provider/
播放引擎。`captureVisualEvidence=true` 可保存测试设备全屏截图到测试 APK 的
`externalFilesDir/visual-smoke`，便于检查入口、三个预置和 Off。该证据不是实际媒体播放、
OEM GPU、真实 FFT、进程死亡恢复或真机验收的替代；这些结果须在 PR 中单独记录。

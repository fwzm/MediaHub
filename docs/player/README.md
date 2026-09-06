# 播放器设计文档

## 管线

```
MediaItem + PlaybackOptions
  → Provider.resolvePlayback()        // 临时 URL + 三态 mode
  → PlaybackCompatibilityEvaluator    // 设备能力 + 偏好 → 决策（V0.1 接入）
  → PlaybackEngine.play(PlaybackSession)
      ├─ HeaderAwareDataSource（鉴权头/Cookie 注入，仅内存）
      ├─ SimpleCache（LRU 512MB，播放缓存）
      └─ 轨道选择 / 进度循环 / 结构化错误
  → PlayerView + Compose 控制层
```

## 播放决策（PlaybackCompatibilityEvaluator，纯逻辑）

- 输入：MediaInfo（容器/视频/音频/分辨率/码率/HDR）+ DeviceCapabilities + UserPreferences。
- 输出：DIRECT_PLAY / DIRECT_STREAM（视频不转码，仅 remux/音频/限速）/ TRANSCODE / UNSUPPORTED。
- 设备能力采集：MediaCodecList（编码/最大分辨率/10bit/HW）、Display（HDR）、
  API 29+ isHardwareAccelerated。
- 禁止在别处复制 codec 判断逻辑（单测 9 例兜底）。

## 播放错误（PlaybackError）

分层 code：AUTH_EXPIRED / URL_EXPIRED / HTTP_403 / HTTP_404 / HTTP_429 / SERVER_ERROR /
NETWORK_TIMEOUT / DNS_ERROR / TLS_ERROR / DECODER_ERROR / UNSUPPORTED_CODEC / DRM_ERROR /
TRANSCODE_ERROR / UNKNOWN。UI 展示中文文案；日志保留脱敏详情。

## 播放缓存

- `MediaCacheProvider`：Media3 SimpleCache + LRU（512MB），仅缓存媒体分片。
- 与元数据缓存（Room）/图片缓存（Coil）/离线下载（后续 DownloadManager）严格分离。

## 播放器功能（当前 / 规划）

已实现：播放/暂停、Seek、倍速循环、音轨/字幕选择、音量、结构化错误、进度快照。
V0.1：URL 过期重解析、外挂字幕、字幕/音频延迟、HDR/媒体信息显示、连播、片头/片尾跳过。
V0.2+：画中画、后台播放、投屏（DLNA/Chromecast）、Android TV。

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
./gradlew testDebugUnitTest :core:model:test :provider:api:test :metadata:test --no-daemon --continue
./gradlew assembleDebug lintDebug --no-daemon
./gradlew :core:ui:connectedDebugAndroidTest :feature:player:connectedDebugAndroidTest :feature:settings:connectedDebugAndroidTest --no-daemon --continue --no-parallel
python3 .github/scripts/verify-visual-tests.py --api 36
```

CI 对精确 PR head 分别运行 API 32 / 36 的硬件加速 Android Canvas 仪器测试，检查
Renderer 实际像素、真实 Compose 主题与生命周期、Player/Settings 入口及 DataStore 重入。
必需测试缺失、跳过或失败均不能通过总门禁；API 不适用的 Shader/低版本专用测试除外。

Player/Settings 路径测试使用真实 UI、ViewModel、DataStore 和 Renderer，但隔离了 Provider/
播放引擎。`captureVisualEvidence=true` 可保存测试设备全屏截图到测试 APK 的
`externalFilesDir/visual-smoke`，便于检查入口、三个预置和 Off。该证据不是实际媒体播放、
OEM GPU、真实 FFT、进程死亡恢复或真机验收的替代；这些结果须在 PR 中单独记录。

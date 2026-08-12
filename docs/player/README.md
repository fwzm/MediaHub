# 播放器设计

## 管线

```text
MediaItem + PlaybackOptions
  → MediaPlaybackProvider.resolvePlayback
  → PlaybackSource（临时 URL/Header/Cookie）
  → PlaybackRequestContext（不可变、每资源独立）
  → MediaSource + HeaderAwareDataSource + SimpleCache
  → PlaybackEngine / PlayerView
```

`PlayerFactory` 不保存播放请求状态。每次 `play()` 新建 MediaSource，请求上下文被防御性复制；两个播放器、预加载、
跨来源切换无法覆盖彼此的 Authorization/Cookie。不得重新引入 Singleton Header holder。

## 进度管线

`PlaybackEngine` 每 500ms 只更新内存 StateFlow。`PlayerViewModel` 对位置流 conflate，并通过 `ProgressSyncGate` 分别控制：

- 本地续播快照：默认 5s；
- 远端周期：Provider policy，默认 10s，可设 null；
- Play/Pause/Seek/Stop/End：立即保存/上报；
- release：最后兜底 flush。

Slider 拖动期间仅更新 UI，结束时触发一次 Seek，避免事件风暴。

## 播放决策与错误

`PlaybackCompatibilityEvaluator` 输入 MediaInfo、DeviceCapabilities、UserPreferences，输出 DIRECT_PLAY /
DIRECT_STREAM / TRANSCODE / UNSUPPORTED；V0.1 接入 resolve 主路径。Codec 判断不得复制到其他层。

`PlaybackError` 对认证、URL 过期、HTTP、网络、解码、DRM 和转码错误做结构化分类；UI 展示中文文案，日志保留脱敏详情。

## 后续资源集合演进

Phase 0.5 保留单主资源 `PlaybackSource`。未来 `ResolvedPlayback` 可包含主视频、外挂字幕、音频、DRM License、备用线路和
转码 Session，但每个资源都必须拥有自己的 Header/Cookie/expiry，不共享可变状态。

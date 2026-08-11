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

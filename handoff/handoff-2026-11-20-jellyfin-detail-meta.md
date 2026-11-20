# Handoff 2026-11-20 Jellyfin 详情元数据（#145 切片）

## 目标
type:goal
补齐 Jellyfin 详情端点的版本/轨道/章节元数据映射：JellyfinDetailProvider 原先只返回 MediaDetail(item)，
versions、streams、audioTracks、subtitles、chapters 全为空；本切片让 Jellyfin 与 Emby 的详情元数据对齐，
为详情页版本选择与轨道展示提供数据基础。

## 任务状态
type:state
实现与本地验证已完成：RED 2 红、聚焦 GREEN 5/0、NC 3/3 每场恰好 1 红并逐字节还原；
全量 testDebugUnitTest + assembleDebug BUILD SUCCESSFUL 16m48s / 882 tasks / 319 suites / 2993 tests / 23 skipped / 0 failures。
真机与真实 Jellyfin 服务器 NOT_RUN（不触碰真实账号/服务器），详情页 UI 消费版本仍为后续缺口，故状态 PARTIAL。

## 已试方案与失败原因
type:dead-end
- 直接复用 EmbyDetailMapper：Jellyfin 与 Emby 分属独立 provider（ADR-039 禁止跨 provider DTO/ mapper 依赖），
  且字段命名与 DTO 不同，因此新建 JellyfinDetailMapper 独立实现。
- 只解析顶层 MediaStreams：部分 Jellyfin 版本只在 MediaSources[].MediaStreams 内回填轨道，
  故实现 ifEmpty 回退到首个 MediaSource，并用「仅 MediaSources」测试固定该分支。

## 进度与下一步
type:progress
- 分支 feature/jellyfin-detail-meta-20261120，base origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 实现文件：新增 provider/jellyfin/.../mapper/JellyfinDetailMapper.kt；修改 JellyfinLibraryDtos.kt、
  JellyfinPlaybackDtos.kt、detail/JellyfinDetailProvider.kt 与 JellyfinDetailProviderTest.kt。
- 测试：新增 2 个 MockWebServer 测试（版本/流/音轨/字幕/章节 + Dolby Vision ExtendedVideoType）。
- 全量：BUILD SUCCESSFUL 16m48s / 882 tasks / 319 suites / 2993 tests / 23 skipped / 0 failures / 0 errors；
  APK app-debug.apk 130595735 B sha256=2d99d812022d6c69f5bad4ad3231d0e5f98b6cd72afba6cab00e0b6cc658db13（未安装）。
- 下一步：提交并创建 Draft PR；queue 回填；exact-head CI。后续切片需在详情页/播放侧消费 versions 与 tracks。
- 证据目录：D:/deepseek_test/mh-jellyfin-detail-evidence-20261120。

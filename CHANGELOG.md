# 变更记录（CHANGELOG）
## [0.10.0-universal-playback] — 2026-08-23（U：双内核 Universal Playback + 播放器手势）
### 功能（双内核，U1/U2）
- Media3 1.5.1 → 1.11.0（含 required toolchain 升级）。
- PlaybackEnginePort 与 ExoPlayer 解耦（U2 foundation）：PlaybackEngine（Media3 封装）实现同一端口，
  PlayerViewModel 依赖 fun interface PlaybackEngineCreator，可注入第二内核。
- MpvPlaybackEngine（player:mpv）：pinned prebuilt libmpv，demuxer-by-container，与 Media3 同端口实现；
  MpvHttpBridge（core:network，IPv4 绑定）代理 HTTP 流，沿用 ADR-030 跨 origin 凭据剥离。
- 已知兼容性缺口（保留在矩阵中，非回归）：Blu-ray .iso 源在 resolve 阶段 skip 并提示；MPEG-TS 原始流。
### 功能（AUTO 引擎选择，U3-A，ADR-034）
- 设置项「播放内核」：AUTO（默认）/ Media3 / mpv。
- PlaybackEngineSelector：按 container|videoCodec|audioCodec 签名决策——显式模式 > 历史失败指纹
  （EnginePreferenceHistory，DataStore 持久化）> DTS/TrueHD 音频集 > 默认 Media3 fast path。
- SwitchablePlaybackEngine 门面：AUTO 模式下 Media3 decoder/source 错误或静音（宽限期后）
  自动切 mpv 同位置重播，UI 仅显示"正在切换兼容播放模式…"，签名写入历史。
### 功能（播放器手势，U3-B，ADR-035）
- PlayerGestureLayer 统一手势层替换全屏 clickable；PlayerGestureController 纯状态机（可单测）。
- 水平拖动 scrub：灵敏度 clamp(时长×10%, 60s, 10min)，拖动只预览、松手 COMMIT。
- 双击矩阵：左右双击快退/快进（默认关，5-60s 可调），未启用侧回退播放/暂停；双击不产生两次单击（Overlay 不闪烁）。
- 双击左半屏按住：连续快退，节流 PREVIEW seek（约 3 次/秒，每步 1s），松手 COMMIT。
- 长按临时倍速：入口 2.0×，水平拖动按阶梯调档（0.1-5.0×，屏宽 1/10 一档），松开恢复长按前永久倍速。
- SeekMode.PREVIEW/COMMIT 贯穿 PlaybackEnginePort + 三个实现：PREVIEW 只移位置不发 Seeked
  （不触发远端进度即时 flush），COMMIT 才发；进度条同样 preview→commit。
- PlayerGestures 9 项偏好（模型 + DataStore）与设置页「播放器手势」区。
### 测试
- PlaybackEngineSelectorTest（7）、SwitchablePlaybackEngineTest（8，含 coroutines-test backgroundScope
  需 runCurrent 驱动的经验）、PlayerGestureControllerTest（22）。
- 全项目 testDebugUnitTest / assembleDebug / lintDebug 全绿。
## [0.9.3-server-editor] — 2026-08-22（Phase 1B-2.5：Server Editor）
### 功能
- Server Editor 页面（feature:server）：编辑名称/备注/主线路 URL、测试连接、账号状态/重新登录、
  设为默认、删除媒体源。URL 采用「草稿 → 测试（可选）→ 保存 → 一次 updateServer」，边输入不改变使用中地址。
- 服务器自定义图标：Photo Picker → 缩放/中心裁剪方形 → WebP 复制到 files/server_icons/{serverId}.webp，
  MediaServer.icon 保存 file:// 引用（不长期保存 SAF content:// URI）。内置图标 builtin://<type>，默认 null。
- 统一 ServerIcon 组件（core:ui）：null/builtin 回退首字母徽标，file:// 走 Coil，首页卡片/编辑页/播放器 Overlay 共用。
- 删除级联 RemoveServerUseCase：server(+endpoints) → account → token → credential → progress → 图标文件 → provider 会话。
- 设为默认原子化：单条 SQL 清旧设新，保证最多一个 isDefault==true；删除默认媒体源重选首条。
### 修复（P1）
- 移除 PlayerViewModel.snapshotPreferences() 的 runBlocking 主线程读 DataStore：改 playerSystemUiPrefs
  StateFlow<PlayerSystemUiPrefs?>（null=未加载，加载后 apply），composable 收到非 null 再 enterPlayback。
### 测试
- core:ui ServerIconTest（icon 引用 → Coil model 映射 4 用例）；feature:server / core:database / feature:player 单测通过。
## [0.9.2-player-overlay] — 2026-08-22（Player Startup & Immersive UX：Overlay 两层 UI）
### 功能
- Overlay 两层 UI：点击视频切换显示 / 播放中 3s 自动隐藏（fadeIn/fadeOut）。
- 顶层：左上角 ×（关闭，经 awaited stopAndFlush 退出）+ 完整剧集/系列标题 + 服务器显示名/图标。
- 底层：真实媒体下载速度 + 设备电量 + 进度条/时间/播放/倍速。
- 下载速度走 Media3 TransferListener（PlaybackSpeedMonitor，1s 滑动窗口 + 空闲 1.5s 衰减归零），
  DataSource 工厂 setTransferListener 注入，engine 暴露 downloadSpeedBps。
- 设备电量走 BatteryManager（BATTERY_PROPERTY_CAPACITY，进入即读 + 每 60s 刷新）。
### 测试
- FakeEngine 补 downloadSpeedBps；feature:player + player:engine + core:database 单测通过。
### 真机验证（Xiaomi 24031PN0DC / Android 14）
- 下载速度 740 KB/s、电量 100%、服务器名 Emby、进度 14:37/32:11 均正常；× 关闭触发 stopAndFlush + 方向恢复。
## [0.9.1-player-ux-orientation] — 2026-08-22（Player Startup & Immersive UX：横屏/沉浸式加固）
### 修复（真机级隐患）
- **MIUI 方向恢复不敏感**：SCREEN_ORIENTATION_UNSPECIFIED(-1) / SCREEN_ORIENTATION_SENSOR(4)
  退出播放器后无法回竖屏。改为按进入前实际方向（resources.configuration.orientation）显式恢复
  PORTRAIT / SENSOR_LANDSCAPE（PlayerSystemUiController.resolveRestoreOrientation）。
- **旋转触发 Activity 重建 → 双播放器**：MainActivity 未声明 configChanges，requestedOrientation
  旋转时 Activity 重建，播放器被 onDispose 释放 + ViewModel 重建 + 重新 resolve 播放源。
  补 configChanges=orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden，Compose 经
  LocalConfiguration 自适应尺寸，无需重建。
- **设置关闭后不生效**：自动横屏/沉浸式开关关闭后仍横屏——DisposableEffect(Unit) 在 DataStore
  发射持久化值前读到 StateFlow 默认值（true）。新增 UserPreferencesRepository.snapshot() 同步读
  持久化值（DataStore 已缓存时即时返回），PlayerScreen 进入播放器前一次性读取。
### 测试
- PlayerViewModelTest FakeUserPreferences 补 snapshot()；feature:player + core:database 单测通过。
### 真机验证（Xiaomi 24031PN0DC / Android 14）
- 横屏进入、Back 恢复竖屏+状态栏、re-enter 正常、设置关闭保持竖屏+系统栏、seek/播放正常、无重建死循环。
## [0.9.0-phase1b2.5] — 2026-08-22（Phase 1B-2.5：Server Management 地基）
### 新增（数据模型地基）
- ServerEndpoint 领域模型 + Room 实体（server_endpoints 表）：一条 MediaServer 多条线路（主/备）。
- MediaServer：baseUrl 迁为计算属性（返回当前生效线路 URL，读取方零改动），新增 note/icon/endpoints；
  保留旧签名向后兼容构造器（baseUrl → 单条主线路）。
- Room migration 1→2：servers 去 baseUrl 加 note/icon，新增 server_endpoints，旧 baseUrl 迁为主线路。
- ServerRepository 用 combine 加载 servers+endpoints；add/update/delete 同步线路。
- mapper 补 ServerEndpoint 双向映射 + ServerEntityMappersTest 扩展（5 用例）。
### 文档
- 修正 ADR-032 漂移：无声提示基于当前 Audio Pipeline 观测状态，isSupported 仅作轨道能力提示。
## [0.8.0-player-ux-hardening] — 2026-08-22（Phase 1B-2.4：播放器 UX 硬化）
### 修复（真机级隐患）
- **TrackSelection 三套 index 语义统一**：TrackMapper 原保存 Tracks.groups 全局序号，UI 用
  音轨列表位置、引擎用 per-renderer 组序号，存在视频组时错位（全局 1 == 音频 0），
  导致选错组/选择被静默拒绝/选中态错乱（无声隐患之一）。改为同类型内序号（per-type
  ordinal），AudioTrack.index/SubtitleTrack.index/selected TrackSelection 与
  MappedTrackInfo.getTrackGroups(type) 一一对应；TrackMapperTest 3 用例钉死。
- isSelected 此前被错存进 isDefault；现 isDefault 取 SELECTION_FLAG_DEFAULT，isSelected 取 group.isSelected。
- 字幕黑底：PlayerView 未应用任何样式（Media3 默认 caption）。现默认白字 + 全透明背景 +
  黑描边（ADR-032），字号链接设置页 18sp 基准与播放器内缩放。
### 功能
- 音轨 Bottom Sheet：语言/codec（EAC3/TrueHD/DTS-HD…展示名）/声道/采样率/解码器/支持状态。
- 字幕 Bottom Sheet：轨道（关闭/选择，SRT/ASS/PGS 格式显示）+ 样式（字号/文字颜色/背景
  （默认透明）/描边（无/描边/阴影）/垂直位置/尊重内嵌 ASS 样式），DataStore 持久化。
- Audio 诊断：全部音轨不被设备支持时，播放页显式黄条提示"当前设备/Media3 不支持该音频格式"，
  不再静默无声（mpv 第二内核的数据基础）。
- UserPreferencesRepository 抽象（可测性），UserPreferencesStore 实现 DataStore 新键。
### 测试
- TrackMapperTest（per-type ordinal/unsupported/默认轨标志/无音轨 null）；
  PlayerViewModelTest 补 FakeUserPreferences；feature:player 关闭 UnsafeOptInUsageError
  （与 player:engine 一致，见 DECISIONS.md）。
## [0.7.0-artwork-pipeline] — 2026-08-22（Phase 1B-2.3：海报与背景图）
### 功能
- Emby 图片 URL 契约（/emby/Items/{id}/Images/{Primary|Thumb|Backdrop}?tag&maxWidth&quality）：
  Token 永不进 URL（ADR-026），类型策略 Movie/Series/Season=海报+背景图、Episode=Thumb??Primary 缩略图、
  Folder/Audio 不生成（EmbyImageMapper，detail DTO 补 ImageTags/BackdropImageTags/AspectRatio）。
- 全局 Coil ImageLoader（MediaHubApp ImageLoaderFactory）：命中已知 Emby origin（scheme+host+port）由
  EmbyImageAuthInterceptor 注入鉴权头，跨 origin 重定向剥离凭据（ADR-030 红线覆盖图片）；
  磁盘缓存 cacheDir/image_cache 256MB LRU，respectCacheHeaders(false)。
- 新模块 core:ui（PosterImage 2:3 / ThumbImage 16:9 / BackdropImage + Compose 占位/错误态）。
- 库浏览媒体条目改 3 列海报墙（Episode 16:9 缩略图，文件夹保持行）；继续观看改缩略图+底部进度条
  （旧记录无 posterUrl 走占位，重播自愈）；接线极简详情页（backdrop 渐变+海报+元信息+简介折叠+播放按钮，
  home/library 点击先进详情再播放）。
- posterUrl 落盘：PlaybackSession→PlaybackProgress→Room（列已存在，零迁移）。
### 重构
- OriginScopedCredentialInterceptor 从 player:engine 迁至 core:network（播放器与图片加载共用，ADR-030）。
### 测试
- EmbyImageMapperTest（URL 契约/无凭据/Episode Thumb 优先/无图 null/Folder 不生成）；
  EmbyImageAuthInterceptorTest（命中注入/未命中标放行/URL 无 Token）。
## [0.6.3-redirect-credential-hardening] — 2026-08-22（Phase 1B-2.2：重定向凭据隔离）
### 安全（P1）
- 跨 origin 重定向泄漏 Emby 凭据：0.6.2 的 DefaultHttpDataSource 手动 redirect 循环会把
  DataSpec 请求头（X-Emby-Token / X-Emby-Authorization）原样发送给每一跳 redirect 目标
  （media3 1.5.1 无剥离逻辑），真实链路 HTTPS(Emby)→307→HTTP 直链→对象存储 下等于把
  长期凭据明文发给第三方主机。双 MockWebServer 回归测试确定性复现
  （`X-Emby-Token: secret-token` 到达跨 origin 目标）。
- 修复（ADR-030）：播放 HTTP 栈切换为 media3 OkHttpDataSource（redirect 由 OkHttp 原生
  跟随，跨协议行为不变）+ OriginScopedCredentialInterceptor（network interceptor，每跳生效）：
  跨 origin（scheme+host+port 变化）一律剥离鉴权/身份头，同 origin 保留；
  Range / User-Agent 等安全媒体头继续透传。
### 测试
- RedirectCredentialIsolationTest（Robolectric + MockWebServer×3）：跨 origin 单跳剥离、
  多跳（307+302）每跳剥离、同 origin 重定向保留凭据、无重定向直连携带凭据，4/4 绿。
### 验证
- 本地 testDebugUnitTest / lintDebug / assembleDebug 全绿；真机复验见 smoke 记录。
## [0.6.2-smoke-fixes] — 2026-08-21（真机 Direct Stream smoke 修复）
### 修复（真机 smoke 暴露）
- MainActivity 缺 @AndroidEntryPoint：MediaHubApp 有 @HiltAndroidApp、5 个 @HiltViewModel 经
  hiltViewModel() 注入，但宿主 Activity 缺注解，首启确定性崩溃（GeneratedComponent/GeneratedComponentManager）。
  修复：补 import dagger.hilt.android.AndroidEntryPoint + @AndroidEntryPoint（CI 只构建不启动，故未暴露）。
- EmbyMediaStreamDto.Level 误声明 String?：真实 Emby 返回整数（如 153=HEVC 5.1），
  coerceInputValues 不把数字强转 String，导致 Detail/PlaybackInfo 解析失败
  （JsonDecodingException at $.MediaSources[0].MediaStreams[0].Level）、Direct Stream 被完全阻断。
  修复：DTO level String?→Int?，mapper level?.toString() 转回领域模型字符串（core:model 不动，免 ADR）。
- DefaultDataSource 未开跨协议重定向：Emby 远端媒体的 Direct Stream URL 常经 HTTPS→HTTP(直链)→S3
  三级重定向，Media3 默认 allowCrossProtocolRedirects=false 拒绝 HTTPS→HTTP 降级，播放报
  Source error(2004)。修复：PlayerFactory 用 DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
  作为上游 DataSource。真机验证：1080p H.264 MKV（007：海底城）Direct Stream 起播成功（0:49/2:05:40）。
### 测试
- EmbyDetailProviderTest 的 mock 视频流补 "Level":153（整数），回归覆盖真实响应形态。
### 验证
- exact-head CI：Hilt 修复 run 32413689874、Level 修复 run 32504254869，均 success。
## [0.6.1-phase1b2.1] — 2026-08-13（Phase 1B-2.1：Direct Stream 协议边界 FINAL HARDENING）
### 修正（审查 11 项收口，禁转码/Token 红线加固）
- PlaybackInfo 改官方 POST contract：POST /Items/{itemId}/PlaybackInfo；协商参数进 JSON body
  （EmbyPlaybackInfoRequestDto：UserId/IsPlayback/EnableDirectPlay=false/EnableDirectStream=true/
  EnableTranscoding=false/StartTimeTicks/MaxStreamingBitrate/DeviceProfile）；UserId 同时走 query
  与 typed body；
  requestJson encodeDefaults=true / explicitNulls=false；Token 不进 URL 也不进 body（只走请求头）。
- DTO 补齐：EmbyMediaSourceInfoDto 增 RequiredHttpHeaders/DirectStreamUrl；EmbyMediaStreamDto 增
  DisplayTitle/IsExternal/DeliveryUrl/PixelFormat/ExtendedVideoType/ExtendedVideoSubType；
  EmbyChapterInfoDto 增 ChapterIndex；新增 EmbyPlaybackInfoRequestDto/EmbyDeviceProfileDto；
  DeviceProfile 使用最小官方形状 SupportedMediaTypes=Video + DirectPlayProfiles[{Type=Video}]，
  三项 Direct/Transcode 开关只保留在 PlaybackInfoRequest 顶层。
- 严格校验（响应损坏 ≠ 需要转码）：MediaSources 空→Parse；MediaSourceId/PlaySessionId 缺失→Parse；
  仅"源非空但全不支持 DirectStream"→NotYetImplemented("需要转码")。
- directStreamUrl 参数改非 nullable 必填（itemId/container/mediaSourceId/playSessionId），
  始终输出 MediaSourceId/PlaySessionId/static=true；禁止生成残缺 URL。
- RequiredHttpHeaders 并入 PlaybackSource.headers；按 HTTP Header 大小写不敏感语义剔除
  与鉴权头冲突的源级键，再写入权威 X-Emby-Token / X-Emby-Authorization。
- 只视频型门禁：MOVIE/EPISODE/VIDEO 才进入播放协议；AUDIO（0 HTTP）、LIVE_TV/OTHER 明确拒绝。
- mapHdrType 3 参（videoRange/extendedVideoType/extendedVideoSubType）识别 Dolby Vision，
  包括 ExtendedVideoSubType=DoviProfile81 等官方枚举值。
### 可测性（feature:player 从 0 测试到有测试）
- 新增 PlaybackEnginePort（uiState/progress/events/exoPlayer/play/控制/stop/release）与
  PlaybackEngineCreator（fun interface create(scope)）；PlaybackEngine/PlaybackEngineFactory 实现。
- PlayerViewModel 依赖改为 ServerStore/ProgressStore/PlaybackEngineCreator（替代 Repository/Factory 具体类）；
  ProgressStore 补 getResume/save（ProgressRepository 实现并补 override）。
- AppModule providePlaybackEngineFactory 返回 PlaybackEngineCreator。
### 测试（当前树共 133 个 `@Test`）
- EmbyPlaybackProviderTest 19 用例：POST contract（query 仅 UserId、body 完整协商字段、
  Token 不进 URL/body）、DirectStream URL 必备参数、元数据映射、Dolby Vision、
  DoviProfile subtype、RequiredHttpHeaders 合并、鉴权头大小写不敏感保护、
  空 MediaSources/缺 MediaSourceId/缺 PlaySessionId→Parse、
  非空无 DS→NotYetImplemented、403/404/500 映射、AUDIO 0 HTTP、forceTranscode 0 HTTP、
  缺 session 0 HTTP、401→AuthExpired、多源取第一个 DirectStream。
- PlayerViewModelTest 新增 3 用例：A. detail 真实 MediaType 覆盖 fallback 并透传续播位置；
  B. PlaybackSource 到达 engine.play；C. 失败→Failed 且不 play。
- HomeViewModelTest 的 FakeProgressStore 补 getResume/save；feature/player 补测试依赖（junit/coroutines-test）。
### 验证
- 随本提交 push 后由 GitHub Actions 执行（本机 aarch64 无 Android SDK）；结果 run id 见交付报告。
## [0.6.0-phase1b2] — 2026-08-13（Phase 1B-2：Emby 条目详情 + 无转码 Direct Stream）
### 新增（Phase 1B 第二刀：详情 + 播放源，禁转码）
- EmbyDetailDtos：UserItem/PlaybackInfo/MediaSource/MediaStream/Chapter（@SerialName，非关键字段缺失不整页失败）。
- EmbyLibraryDtos 重构：EmbyItemFields 统一字段映射接口，MediaItem 与 Detail 共用，消除双份映射。
- EmbyMediaItemMapper 加固：空白 Id 拒绝返回 null；补 overview/genres/container/communityRating。
- EmbyApiClient 新增：getUserItem（GET /Users/{userId}/Items/{itemId}）、getPlaybackInfo（带最小
  DeviceProfile，固定 EnableDirectPlay=false/EnableDirectStream=true/EnableTranscoding=false）、
  directStreamUrl（/Videos/{itemId}/stream.{container}?static=true&MediaSourceId&PlaySessionId，
  绝不包含 Token）、buildUrlWithSegments、DEVICE_PROFILE。
- EmbyDetailMapper：MediaDetail 映射（versions/streams/audioTracks/subtitles/chapters/hdr 类型映射）。
- EmbyDetailProvider：getItemDetail；缺 session 不触网；404→NotFound；401→AuthExpired。
- MediaSourceSelector：纯函数，只接受 SupportsDirectStream==true 的源，否则返回 null。
- EmbyPlaybackProvider：resolvePlayback→PlaybackSource（DIRECT_STREAM，headers 含 X-Emby-Token +
  X-Emby-Authorization，PlaySessionId 注入 URL 参数）；无可直接流源/forceTranscode→NotYetImplemented。
- EmbyProviderFactory 装配 DETAIL+PLAYBACK（runtimeCapabilities={AUTH,LIBRARY,DETAIL,PLAYBACK}）。
- 结构化错误映射与现有 Emby 风格一致（无 session→AuthRequired、401→AuthExpired、404→NotFound 等）。
### 测试（96 + 18 = 114 用例）
- EmbyMediaItemMapperTest 2（空 Id/空白 Id → null）。
- MediaSourceSelectorTest 2（无可直接流源→null、多源取第一个 SupportsDirectStream）。
- EmbyDetailProviderTest 6（详情映射/路径与鉴权头/缺 session 不触网/404→NotFound/401→AuthExpired/无 Id→Parse）。
- EmbyPlaybackProviderTest 8（DirectStream URL 无 Token/PlaybackInfo 请求参数/model 全字段/
  无可直接流源→NotYetImplemented/forceTranscode→NotYetImplemented 不触网/缺 session 不触网/401→AuthExpired）。
- EmbyProviderFactoryTest 断言更新（AUTH+LIBRARY+DETAIL+PLAYBACK）。
### 验证
- GitHub Actions CI 通过（run#31630416127）：assembleDebug（4m36s）/ testDebugUnitTest（1m9s）/ lintDebug（1m27s）全绿。
  本机 aarch64 无 Android SDK，无法本地构建。
## [0.5.0-phase1b1] — 2026-08-12（Phase 1B-1：Emby 媒体库浏览）

### 新增（Phase 1B 第一刀，不碰播放）
- Emby 媒体库真实浏览竖切：/Users/{userId}/Views（顶层库）→ /Users/{userId}/Items?ParentId=...
  （View → Series → Season → Folder 通用进入子级）。
- EmbyLibraryProvider（TokenStore + EmbySessionStore + EmbyApiClient；getLibraries/getItems；
  getSeasons/getEpisodes 占位）。
- EmbyApiClient 新增 getUserViews/getUserItems（HttpUrl.Builder 安全拼 query，禁止手拼）。
- EmbyLibraryDtos（QueryResult/BaseItem/UserData，@SerialName，非关键字段缺失不整页失败）。
- EmbyLibraryMapper / EmbyMediaItemMapper（CollectionType→LibraryType；Type→MediaType）。
- MediaType.isContainer = FOLDER||SERIES||SEASON（Series/Season 可继续进入，不再误送播放器）。
- EmbyProviderFactory 暴露 library 能力（runtimeCapabilities 含 LIBRARY）。
- LibraryViewModel：root→getLibraries（LibraryUiState.Libraries）；非 root→getItems(parentId)。
- LibraryScreen：顶层 Views 列表 + MediaLibrary 导航 + item.type.isContainer 点击。
- 结构化错误映射：无 session→AuthRequired、401→AuthExpired、404→NotFound、5xx→Http、网络/解析分别表达。

### 测试（96 用例）
- EmbyLibraryProviderTest 5（Views/header/ParentId/StartIndex/Limit/类型映射/缺失 session 不发请求/401/403/5xx）。
- LibraryViewModelTest 4（root→getLibraries、非 root→getItems、open container→item.id、goToParent 恢复）。
- EmbyProviderFactoryTest 更新（AUTH+LIBRARY）。

### 验证
- assembleDebug / testDebugUnitTest（96）/ lintDebug 通过

## [0.4.6.1-phase1a-final4.1] — 2026-08-12（Phase 1A FINAL PATCH 4.1：TEST-ONLY 修正)

### 修正（纯测试，不改生产代码）
- `HomeViewModelTest` 的 B/C/D 三个测试名与实际状态迁移不一致，已修正为真实制造前置状态：
  - B：初始 restore = SESSION_EXPIRED → assert SessionExpired → 改 Authenticated → forceRestore → Authenticated
  - C：初始 restore = SignedOut → assert SignedOut → 改 Authenticated → forceRestore → Authenticated
  - D：先 auth-capable 产生 Authenticated → assert 包含 → 改 auth==null → forceRestore → 移除该 id
- 生产代码 forceRestore（serverStore.getServer + restore）已在 0.4.6 通过评审，未改动。

### 验证
- assembleDebug / testDebugUnitTest（87）/ lintDebug 通过

## [0.4.6-phase1a-final4] — 2026-08-12（Phase 1A FINAL PATCH 4：forceRestore 竞态修复 + 测试)

### 修复（评审 FINAL PATCH 4）
- **P1：forceRestore 读旧缓存竞态**：原用 `servers.first()`（observeServers StateFlow 缓存，re-login
  修改 baseUrl 后可能落后于 updateServer 的 DB 写入）。改为 `serverRepository.getServer(serverId)`
  直接读 DB 最新，复用 `restore(server)`（消除重复恢复逻辑）。
- **P2：Local 等非认证 Provider 瞬间显示 Restoring**：forceRestore 复用 restore(server)（先判
  handle.auth != null 才写 Restoring），Local reauthorization 返回 Home 不再闪现 Restoring/SignedOut。
- 可测性：提取 `ServerStore`/`ProgressStore` 接口（ServerRepository/ProgressRepository 实现），
  HomeViewModel 依赖接口；AppModule @Binds 绑定。

### 测试
- 新增 `HomeViewModelTest`（4 例）：forceRestore 读 DB 最新（非缓存）、SessionExpired→Authenticated、
  SignedOut→Authenticated、非认证 Provider 不写 authStates。
- 清理 AuthNavigationPolicyTest 未用 MediaType import。全项目 87 用例。

### 验证
- assembleDebug / testDebugUnitTest（87）/ lintDebug 通过

## [0.4.5-phase1a-final3] — 2026-08-12（Phase 1A FINAL PATCH 3：Home 状态闭环)

### 修复（评审 FINAL PATCH 3）
- **P1：re-login 后 Home 登录状态不刷新**：新增 `HomeViewModel.forceRestore(serverId)`（强制恢复，
  不管 authStates 是否已有记录）；导航用 Navigation result（`auth_changed_server_id`）在
  AddServer re-login 成功后通知 Home 强制刷新 → SessionExpired/SignedOut 后重新登录，
  返回 Home 立即变 Authenticated（不用杀 App）。
- **P2：非认证 Provider 不显示"未登录"**：`restore()` 在 auth==null 时不写入 authStates
  （移除该 id），Local/WebDAV 等不再显示 未登录/Restoring/Logout。
- **needsRelogin 单一 source of truth**：新建 `AuthNavigationPolicy`（feature:home），
  HomeViewModel 实际调用它；删除 ExistingServerEditPolicy 里仅被测试调用的重复 needsRelogin
  + 无用 isProviderLocked/MediaItem import。

### 测试
- 新增 `AuthNavigationPolicyTest`（测生产实际调用的 policy）：SignedOut/SESSION_EXPIRED/
  SERVER_MISMATCH → relogin；FORBIDDEN/网络/5xx/INVALID/UNKNOWN/Authenticated → 不 relogin；
  非认证 Provider → 不 relogin。全项目 83 用例。

### 验证
- assembleDebug / testDebugUnitTest（83）/ lintDebug 通过

## [0.4.4-phase1a-final2] — 2026-08-12（Phase 1A FINAL PATCH 2：Existing Server Re-login 修复)

### 修复（评审 FINAL PATCH 2）
- **descriptor id 映射 P1 bug**：existing-server 加载原本用 `ServerType.EMBY.name = "EMBY"`，
  与 `ProviderDescriptor.id = "emby"` 不匹配 → 表单无 descriptor 选中、URL/name/username 不预填。
  改为 `ExistingServerEditPolicy.descriptorFor`（按 `serverType` 匹配）；找不到 descriptor 显示错误而非静默。
- **Re-login Provider 锁定**：existingServer != null 时 `selectProvider` 拒绝切换（UI 不能点 Jellyfin/WebDAV）。
- **needsRelogin 精确语义**：仅 `SignedOut / SESSION_EXPIRED / SERVER_MISMATCH` 进入重登录；
  FORBIDDEN / NETWORK / 5xx / INVALID_RESPONSE / UNKNOWN 保留 session（不送重登录页）。
- **existing update 完整保留元数据**：`ExistingServerEditPolicy.buildDraft` 保留
  id/type/isDefault/sortOrder/createdAtEpochMs/lastConnectedAtEpochMs/lastError（不再重置为 0/null）。
- 修正 EmbyAuthProvider 注释中 `/Users/Me` → `/Users/{userId}`。

### 新增测试
- `ExistingServerEditPolicyTest`（8 例）：descriptor 按 serverType 匹配（禁 "EMBY"≠"emby"）、
  same id + 全元数据保留、Provider 锁定、needsRelogin 精确分支、新建不受破坏。

### 验证
- assembleDebug / testDebugUnitTest（82）/ lintDebug 通过

## [0.4.3-phase1a-final] — 2026-08-12（Phase 1A FINAL PATCH）

### 修复（评审 Phase 1A FINAL PATCH）
- **Emby 官方 API**：会话验证改用 `GET /emby/Users/{userId}`（废弃未文档化 /Users/Me）；
  authenticated request 的 `X-Emby-Authorization` 必带 `UserId="..."`
  （EmbyApiClient.authenticatedHeaders(token, userId) / getCurrentUser(token, userId)；
  logout(token, userId) 同样带 UserId）。
- **Existing Server Re-login**：ServerCard 点击在 已失效/身份变更/未登录（且为认证 Provider）时
  进入 `server/add?reauthorizeId={serverId}`；复用 SAME localServerId、预填 URL/name/username
  （密码留空）、成功后 `updateServer`（不 addServer，不产生重复卡片）。
  导航 HomeRoute 新增 `onRelogin`；AddServerViewModel 新增 reauthorizeId/existingServer 模式。
- **Playback progress**：`isPaused` 改为 `!player.playWhenReady`（播放意图），
  buffering 不再误报 paused（playWhenReady=true 但 isPlaying=false 时）。

### 验证
- assembleDebug / testDebugUnitTest（74）/ lintDebug 通过

## [0.4.2-reconcile] — 2026-08-12（PR #1 × main 合流，以 main 为主线")

### 合流决策（经用户确认，ADR-029）
- 以 **main 为主线**：不采用 PR #1 的 CredentialVault/AuthenticationCoordinator 双轨重构，
  保留 main 已绿灯的 Phase 1A（TokenStore + EmbySessionStore）。
- PR #1 中有价值的**实质性 review 修复**cherry-pick 到 main：
  - SAF 嵌套浏览基础设施：`SafTreeNavigator` + `SafUri`（tree-backed，纯 Kotlin 可测，+5 测试）；
    作为 Phase 0.6 SAF 演进基础（main 的 LocalProvider 仍为 File 型，无 PR#1 的 fromSingleUri bug）
  - 播放关键事件 Channel：`_events` 改 `Channel(UNLIMITED)`（Pause/Seek/Resume 不 conflate/drop）
  - 系统 Back：PlayerScreen 加 `BackHandler`（awaited stopAndFlush，不走 toolbar 绕过）
  - browse-only MediaType：`MediaTypeGuesser`（core:model）+ PlayerViewModel browse-only fallback
- WebDAV 骨架保留（其 PROPFIND 认证属 Phase 1 完整实现；main 的 testConnection 已含 401/403 AUTH_REQUIRED）

### 验证
- assembleDebug / testDebugUnitTest（74）/ lintDebug 全部通过

## [0.4.1-phase1a-finalization] — 2026-08-12（Phase 1A 端到端收尾，ADR-028）

### 功能补齐（评审 10 项）
- MediaAuthProvider 通用 `restoreSession(): AuthSessionState` 契约（Jellyfin/WebDAV 占位）
- Home 启动/服务器变化自动恢复登录（HomeViewModel.restore）；ServerCard 展示登录态 + 退出入口
- **防 Token 串服**：恢复/登出前无 Token 校验 /System/Info/Public 的 remoteServerId，
  不一致 → SERVER_MISMATCH 绝不发送 Token（含测试：SERVER_B 从未收到 X-Emby-Token）
- **失效策略修正**：仅 401 清会话；403/malformed/5xx/网络保留（含测试）
- **官方协议对齐**：EmbyEndpointResolver（/emby root）+ X-Emby-Authorization 官方 schema
  `Emby UserId=..., Client=...`（不再 MediaBrowser 前缀）
- 密码输入框遮罩 + 文案"仅用于本次登录，不会保存在设备中"

### 测试
- EmbyAuthProviderTest 扩至 16 用例（+403 preserve、malformed preserve、SERVER_MISMATCH、
  logout mismatch）；全项目 69 用例

### 验证
- assembleDebug / testDebugUnitTest（69）/ lintDebug 全部通过

## [0.3.0-phase1a] — 2026-08-12（Emby 认证与会话）

### 新增
- **Emby 认证闭环**（ADR-026）：登录（/Users/AuthenticateByName）→ 严格校验
  AccessToken/ServerId/User.Id → TokenStore（localServerId 键）+ EmbySessionStore（会话元数据）。
- **会话恢复验证**：Token+Session 双份齐全 + GET /Users/Me 真实验证；
  401/403 清会话（SessionExpired），Timeout/DNS/5xx 保留会话（网络问题 ≠ token 失效）。
- **Logout**：POST /Sessions/Logout best-effort + 本地清理权威。
- **EmbyAuthState 状态机**（Unknown/SignedOut/Restoring/Authenticating/Authenticated/Error）。
- **ClientIdentity**（core:common，ADR-025）：DeviceId 首次生成持久化；Emby/Jellyfin 共用。
- **Emby 内部模块拆分**（ADR-027）：api/auth/session/mapper；EmbyProvider 瘦身为身份/探测。
- **添加服务器 UI**：测试连接（协议嗅探）→ 登录并添加（事务语义：认证成功但保存失败 → 回滚会话）。
- ApiClient.postNoContent（HTTP 200 + 空 body 正规处理）。

### 修复
- OkHttp "POST must have a request body"：buildBody 对 POST 兜底空 body。

### 测试
- EmbyAuthProviderTest 13 用例（MockWebServer：登录/401/malformed/关键字段缺失/恢复/
  超时保留/500 保留/X-Emby-Token/登出成功/登出网络失败/密码不落库）；
  EmbyProviderFactoryTest（Handle 只开放 AUTH）；全项目 66 用例。

### 验证
- assembleDebug / testDebugUnitTest（66）/ lintDebug 全部通过。

## [0.2.2-exit-flush-fix] — 2026-08-12（退出重复上报 patch）

### 修复
- 退出 final flush 唯一权威路径（ADR-023）：`PlaybackEvent.Stopped` 仅作状态通知，
  **不再由 ProgressSyncCoordinator 自动 flush**；退出上报只走
  `engine.stop() → flush(finalProgress) → stop() → release()` 一条链，
  消除"Stopped 自动 flush(latest) + 显式 flush(finalProgress)"导致的
  本地两次写入 + 远端两次上报。
- 修正代码注释与文档中 final flush 的 ADR 引用（ADR-022 → ADR-023）。

### 测试
- 完整退出链测试：流中 20s 不得作为退出 final 上报；25s 本地/远端各恰好一次；
  Stopped 事件不触发自动 flush。

### 验证
- assembleDebug / testDebugUnitTest（51 用例）/ lintDebug 全部通过。

## [0.2.1-hardening-fix] — 2026-08-12（Phase 0.5.1 边界修复）

### 修复
- **ProviderHandle 运行时语义**（ADR-022）：Handle 只暴露已实现能力；Emby/Jellyfin/WebDAV
  Handle 置空（Phase 1 逐项填充）；`declaredCapabilities`（计划）/`runtimeCapabilities`（运行时）
  命名分离；ProviderCapability 补 PLAYBACK/DETAIL。
- **退出 final flush 状态机**（ADR-023）：`PlayerViewModel.stopAndFlush()` 显式流程
  （stop → 最终进度 → local save + remote report(2s 超时) → release）；Stopped 事件触发 flush；
  onCleared 兜底；返回按钮同步走 stopAndFlush，DisposableEffect 仅兜底。
- **CredentialVault 接入 Hilt DI**（AppModule @Provides；不保存 Emby/Jellyfin 原始密码）。
- **连接测试协议签名校验**（ADR-024）：Emby/Jellyfin SystemInfo 必须 Id/Version 非空；
  DTO 键名 @SerialName("Id"…) 对齐真实协议。

### 测试
- 新增 11 用例（共 51）：ProviderHandle 一致性、Emby 连接（6）、Jellyfin 连接（3）、
  Stopped/flush override（2）；MockWebServer 覆盖正确/错误JSON/404/401/403/malformed。

### 验证
- assembleDebug / testDebugUnitTest（51 用例）/ lintDebug 全部通过。

## [0.2.0-hardening] — 2026-08-12（Phase 0.5 架构加固）

### 重构
- **MediaProvider 能力组合**（ADR-014）：Fat Interface 拆为公共契约 + 7 个可选能力接口，
  新增 `ProviderHandle`（类型安全组合）。LocalProvider 去除伪造认证/空 Season/NotYetImplemented 堆；
  Emby/Jellyfin/WebDAV 按类型声明能力。
- **ProviderDescriptor**（ADR-015）：Factory 自报描述；"添加媒体库"从 Registry 动态渲染，
  删除 UI 硬编码 ServerTypeOptions。
- **播放请求头 session-scoped**（ADR-018）：PlaybackHeadersHolder 改为 per-engine，
  废弃全局 Singleton mutable holder。
- **进度同步三档节流**（ADR-017）：新增 ProgressSyncCoordinator（本地 5s 采样 / 远端按
  Provider 间隔 / 关键事件立即 flush / 退出 final flush）；PlaybackEngine 提供 progress/events 流。
- **协议级连接测试**（ADR-019）：Emby/Jellyfin 嗅探 /System/Info/Public，WebDAV OPTIONS+DAV 头；
  AddServerViewModel 不再用通用 HTTP probe。
- **CredentialVault**（ADR-016，机制先行）：长期凭据加密存取；TokenStore 改 java.util.Base64（可 JVM 测试）。
- SAF 预留（ADR-020）：LocalRootProvider.contentRoots() 接口（Phase 0.6 接入文档树）。

### 新增
- GitHub Actions CI（.github/workflows/android-ci.yml）：assembleDebug + testDebugUnitTest + lintDebug。
- 测试 40 个（新增 20）：Registry、能力组合、CredentialVault、TokenStore、进度节流、
  Headers 隔离、ServerEntityMappers。

### 验证
- assembleDebug / testDebugUnitTest（40 用例）/ lintDebug 全部通过。

## [0.1.0-skeleton] — 2026-08-12（Phase 0）

### 新增
- 工程骨架：Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + 版本目录（libs.versions.toml）+ wrapper。
- 23 个 Gradle 模块：core(common/model/network/database/security/logging)、
  player(engine/compatibility)、provider(api/base/emby/jellyfin/webdav/local)、
  metadata、feature(home/server/library/detail/search/settings/player)、app。
- core:model：统一领域模型（MediaType/MediaItem/PlaybackSource/PlaybackProgress/分页等）。
- core:network：ApiClient（OkHttp+kotlinx.serialization）、MediaHttpClient（媒体探测）、
  PlaybackError 结构化错误与映射、脱敏日志拦截、X-Request-Id。
- core:database：Room（servers/accounts/playback_progress）、DataStore 偏好、Server/Progress 仓库。
- core:security：Keystore AES/GCM SecretStorage + TokenStore。
- core:logging：Logger（分类）、Redactor（脱敏）、LogBuffer（诊断缓冲）；3 组规则 + 7 用例测试。
- player:engine：PlaybackEngine（Media3/ExoPlayer）、SimpleCache 播放缓存、
  HeaderAwareDataSource 请求头注入、音轨/字幕选择、进度循环、结构化错误映射。
- player:compatibility：DeviceCapabilities 采集、PlaybackCompatibilityEvaluator（9 用例测试）。
- provider:api/base：MediaProvider 能力接口、ProviderException、@IntoSet 注册表、
  BaseMediaServerProvider（异常收敛/Token 会话/连通性探测）。
- provider:local：本地文件树浏览 + file:// 播放（真实实现）。
- provider:emby/jellyfin/webdav：骨架（构造函数/注册就绪，API 业务待 V0.1）。
- metadata：刮削抽象（接口 + 注册表）。
- feature:*：首页媒体源卡片与继续观看、添加媒体库流程（类型选择/测试连接/保存）、
  本地文件树、播放器页（自定义控制/音轨/字幕/倍速）、设置（DataStore 偏好）。
- 文档：README/ARCHITECTURE/ROADMAP/TASKS/DECISIONS/HANDOFF + docs/{providers,player,api}。

### 修复（构建期）
- Hilt KSP `@IntoMap` NonExistentClass → 改用 `@IntoSet`（ADR-005）。
- Media3 1.5.1 API 对齐（无 MediaItem.headers → DataSource 包装层；SelectionOverride 位置等）。
- 跨模块 okhttp/kotlinx-serialization 可见性 → core:network 以 api 暴露。
- Redactor 正则：Bearer 令牌含空格与查询串无引号场景。
- lint：UnsafeOptInUsageError（类级 OptIn + 模块关闭）、NewApi/InlinedApi（API 29 守卫）、
  StateFlowValueCalledInComposition（改为 collectAsStateWithLifecycle）。

### 已知限制
- 沙箱（Linux aarch64）无法原生跑 aapt2，验证时启用 qemu 包装器（见 HANDOFF.md）。

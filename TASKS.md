# 任务看板（TASKS）

> 状态：TODO / IN PROGRESS / DONE / BLOCKED。由完成任务的 AI 更新。

## Phase 0 —— 骨架（本次交付）✅ DONE

- [x] DONE 工程骨架：settings/build/version catalog/wrapper
- [x] DONE 模块划分（23 模块）与依赖图
- [x] DONE core:model 统一领域模型
- [x] DONE provider:api MediaProvider 能力接口 + ProviderException
- [x] DONE provider:base BaseMediaServerProvider + DefaultProviderRegistry（Hilt @IntoSet）
- [x] DONE provider:local 本地文件浏览/播放（真实实现）
- [x] DONE core:network ApiClient/MediaHttpClient/PlaybackError/脱敏拦截
- [x] DONE core:database Room（servers/accounts/playback_progress）+ DataStore + Repository
- [x] DONE core:security Keystore SecretStorage + TokenStore
- [x] DONE core:logging Logger/Redactor/LogBuffer + 单测
- [x] DONE player:engine Media3 封装（引擎/缓存/请求头注入/轨道选择/结构化错误）
- [x] DONE player:compatibility 设备能力 + 评估器 + 单测
- [x] DONE feature:server 添加媒体库（类型选择/测试连接/保存）
- [x] DONE feature:home 媒体源卡片 + 继续观看
- [x] DONE feature:library 本地文件树浏览
- [x] DONE feature:player 播放器页（PlayerView + 自定义控制）
- [x] DONE feature:settings 播放偏好（DataStore）
- [x] DONE 文档体系 + git 初始提交
- [x] DONE assembleDebug / testDebugUnitTest / lintDebug 通过

## Phase 0.5 —— 架构加固（本次交付）✅ DONE

- [x] DONE MediaProvider 能力组合（Interface Segregation + ProviderHandle，ADR-014）
- [x] DONE ProviderDescriptor 动态注册（UI 从 Registry 读取，ADR-015）
- [x] DONE LocalProvider 去除假实现（仅 BROWSE/DETAIL/PLAYBACK）
- [x] DONE CredentialVault（core:security，ADR-016）
- [x] DONE 进度同步三档节流（ProgressSyncCoordinator，ADR-017）
- [x] DONE 播放请求头 session-scoped（per-engine holder，ADR-018）
- [x] DONE 协议级连接测试（Emby/Jellyfin System Info、WebDAV OPTIONS，ADR-019）
- [x] DONE CI（.github/workflows/android-ci.yml）
- [x] DONE 测试补充（Registry/能力组合/Credential/进度节流/Headers 隔离/Mapper，共 40 用例）
- [x] DONE assembleDebug / testDebugUnitTest / lintDebug 通过

## Phase 0.5.1 —— 边界修复（本次交付）✅ DONE

- [x] DONE ProviderHandle 运行时语义（ADR-022）：Handle 只暴露已实现能力；
      Emby/Jellyfin/WebDAV Handle 置空；declaredCapabilities/runtimeCapabilities 命名区分；
      ProviderCapability 补 PLAYBACK/DETAIL；一致性测试
- [x] DONE 退出 final flush 状态机（ADR-023）：stopAndFlush 显式流程 + Stopped 触发 flush +
      远端 2s 短超时 + onCleared 兜底；单元测试（Stopped/flush override）
- [x] DONE CredentialVault 接入 Hilt DI（AppModule @Provides）
- [x] DONE 连接测试协议签名校验（ADR-024）：Emby/Jellyfin SystemInfo Id/Version 必填；
      MockWebServer 测试（正确/错误JSON/404/401/403/malformed）
- [x] DONE assembleDebug / testDebugUnitTest（51 用例）/ lintDebug 通过

## Phase 1A —— Emby 认证与会话（本次交付）✅ DONE

- [x] DONE Emby 登录（POST /Users/AuthenticateByName，JSON Username/Pw + X-Emby-Authorization）
- [x] DONE 会话安全持久化：Token 入 TokenStore（localServerId 键）、Session 元数据入 EmbySessionStore
- [x] DONE localServerId / remoteServerId 明确区分（ADR-026）
- [x] DONE 重启恢复：Token+Session 双份齐全 + 真实验证（GET /Users/Me）；401 清会话；网络问题保留
- [x] DONE Logout：POST /Sessions/Logout best-effort + 本地清理权威
- [x] DONE EmbyAuthState 状态机（Unknown/SignedOut/Restoring/Authenticating/Authenticated/Error）
- [x] DONE ProviderHandle 只开放 AUTH（runtimeCapabilities={AUTH}，ADR-022/026）
- [x] DONE ClientIdentity（core:common，跨协议复用，DeviceId 稳定持久化）
- [x] DONE EmbyProvider 内部模块拆分（api/auth/session/mapper，防巨型类，ADR-027）
- [x] DONE 添加服务器 UI：测试连接（协议）/ 登录并添加（事务语义，失败回滚会话）
- [x] DONE 测试 13 个（登录/401/malformed/关键字段缺失/恢复/超时/Header/登出/密码不落库/Handle）
- [x] DONE assembleDebug / testDebugUnitTest（66）/ lintDebug 通过

## Phase 1B-1 —— Emby 媒体库浏览（本次交付）✅ DONE

- [x] EmbyLibraryProvider（getLibraries/getItems，getSeasons/getEpisodes 占位）
- [x] EmbyApiClient getUserViews/getUserItems（HttpUrl 安全 query）
- [x] EmbyLibraryDtos + EmbyLibraryMapper + EmbyMediaItemMapper
- [x] MediaType.isContainer = FOLDER||SERIES||SEASON
- [x] EmbyProviderFactory 暴露 library（AUTH+LIBRARY）
- [x] LibraryViewModel root→getLibraries；LibraryScreen Views + MediaLibrary 导航
- [x] 测试：EmbyLibraryProviderTest 5 + LibraryViewModelTest 4；全项目 96
- [x] assembleDebug / testDebugUnitTest(96) / lintDebug 通过
## Phase 1B-2 —— Emby 条目详情 + 无转码 Direct Stream（本次交付）✅ DONE
- [x] EmbyDetailDtos：UserItem/PlaybackInfo/MediaSource/MediaStream/Chapter（@SerialName，非关键字段缺失不整页失败）
- [x] EmbyLibraryDtos 重构：EmbyItemFields 统一字段映射（MediaItem 与 Detail 共用，消除双份映射）
- [x] EmbyMediaItemMapper 加固：空白 Id 拒绝；补 overview/genres/container/communityRating
- [x] EmbyApiClient：getUserItem / getPlaybackInfo / directStreamUrl / buildUrlWithSegments / DEVICE_PROFILE
- [x] 无转码红线：PlaybackInfo 固定 EnableDirectPlay=false/EnableDirectStream=true/EnableTranscoding=false；
      MediaSourceSelector 只接受 SupportsDirectStream==true；否则 NotYetImplemented
- [x] Token 红线：directStreamUrl 参数仅 static/MediaSourceId/PlaySessionId；Token 只进 source.headers
- [x] EmbyDetailMapper：MediaDetail（versions/streams/audioTracks/subtitles/chapters/hdr）
- [x] EmbyDetailProvider（getItemDetail；缺 session 不触网；404→NotFound；401→AuthExpired）
- [x] EmbyPlaybackProvider（resolvePlayback→PlaybackSource DIRECT_STREAM，含 headers 与 PlaySessionId）
- [x] EmbyProviderFactory 装配 DETAIL+PLAYBACK（runtimeCapabilities={AUTH,LIBRARY,DETAIL,PLAYBACK}，ADR-022/026）
- [x] 测试 18 条：MapperTest 2 / SelectorTest 2 / DetailProviderTest 6 / PlaybackProviderTest 8 +
      EmbyProviderFactoryTest 断言更新；全项目预计 114
- [x] 文档同步（TASKS/HANDOFF/CHANGELOG）
- [x] DONE GitHub Actions CI 通过：run#31630416127（assembleDebug 4m36s / testDebugUnitTest 1m9s / lintDebug 1m27s 全绿）
      （本机 aarch64 无 Android SDK，构建验证依赖远程 CI）
## Phase 1B-2.1 —— FINAL HARDENING（Direct Stream 协议边界封板，审查 11 项）✅ 代码+测试完成
- [x] 指令 1：PlaybackInfo 改官方 POST（/Items/{itemId}/PlaybackInfo），协商参数全部进 JSON body；
      UserId 同时走 query 与 typed body；requestJson encodeDefaults=true / explicitNulls=false
- [x] 指令 2：DTO 补齐（RequiredHttpHeaders/DirectStreamUrl/DisplayTitle/IsExternal/DeliveryUrl/
      PixelFormat/ExtendedVideoType/ExtendedVideoSubType/ChapterIndex + 新增 PlaybackInfoRequest/DeviceProfile DTO）；
      DeviceProfile 使用官方最小 SupportedMediaTypes + DirectPlayProfiles 形状
- [x] 指令 3：严格校验——MediaSources 空→Parse；MediaSourceId 空→Parse；PlaySessionId 空→Parse；
      仅"源非空且全不支持 DirectStream"才 NotYetImplemented
- [x] 指令 4：directStreamUrl 参数全必填（itemId/container/mediaSourceId/playSessionId 非 nullable），
      始终输出 MediaSourceId/PlaySessionId/static=true
- [x] 指令 5：RequiredHttpHeaders 并入 source.headers；按 Header 名大小写不敏感过滤冲突键，
      权威 X-Emby-Token/Authorization 最终写入
- [x] 指令 6：只视频型门禁——MOVIE/EPISODE/VIDEO 才进 DIRECT_STREAM_TYPES；AUDIO/LIVE_TV/OTHER→NotYetImplemented 且 0 HTTP
- [x] 指令 7：mapHdrType 3 参（videoRange+extendedVideoType+extendedVideoSubType），
      Dolby Vision 与 DoviProfile subtype 识别
- [x] 指令 8：EmbyPlaybackProviderTest 19 用例（POST contract/Token 不进 URL+body/Headers 合并/
      鉴权头保护/空 MediaSources/缺 MediaSourceId/缺 PlaySessionId/非空无 DS→NotYetImplemented/
      403/404/500/AUDIO 0 HTTP/forceTranscode 0 HTTP/缺 session/401/多源选择/元数据/DV）
- [x] 指令 9：feature:player 可测性——新增 PlaybackEnginePort/PlaybackEngineCreator；PlayerViewModel 改依赖
      ServerStore/ProgressStore/PlaybackEngineCreator；ProgressStore 补 getResume/save；
      AppModule @Provides 返回 PlaybackEngineCreator；PlayerViewModelTest 3 用例（A/B/C）；测试依赖补齐
- [x] 附带修复：ProgressRepository 的 save/getResume 补 override（接口新成员）
- [x] CI 验证：smoke 修复链 run 32413689874 / 32504254869 / 32509040901 / 32511203757（ed073b5 exact-head）全绿
- [x] 真机 Final Smoke：Movie（007：海底城 1080p AVC MKV）+ Episode（冰血暴 S01E01）Direct Stream 起播/seek/
      退出释放/本地续播全 PASS；转码样本与 RequiredHttpHeaders 均无真实样本（NOT_AVAILABLE，不伪造）
## Phase 1B-2.2 —— Redirect Credential Hardening（跨 origin 凭据隔离）✅ DONE
- [x] 确定性复现：双 MockWebServer 在 ed073b5 上证明 X-Emby-Token/X-Emby-Authorization 被 DefaultHttpDataSource
      手动 redirect 循环原样转发给跨 origin 第三方主机（media3 1.5.1 无剥离逻辑，P1）
- [x] ADR-030：播放 HTTP 栈切 media3 OkHttpDataSource（OkHttp 原生跟随跨协议 redirect）+
      OriginScopedCredentialInterceptor（network interceptor 每跳生效；跨 scheme+host+port 剥离鉴权/身份头）
- [x] 回归测试 RedirectCredentialIsolationTest 4/4（跨 origin 剥离/多跳 307+302/同 origin 保留/直连携带）
- [x] exact-head CI run 32521821629（1bc4351）全绿；真机 A/B（同设备同片源同时段 ed073b5 vs 1bc4351）
      确认吞吐劣化为服务器侧、非栈切换回归
## Phase 1B-2.3 —— Artwork Pipeline（海报与背景图）✅ 代码+测试完成
- [x] EmbyImageUrl 契约：/emby/Items/{id}/Images/{Primary|Thumb|Backdrop}?tag&maxWidth&quality，
      Token 永不进 URL（ADR-026 延续；EmbyImageMapperTest 契约测试）
- [x] 类型策略：Movie/Series/Season=Primary(400)+Backdrop(1280)；Episode/Video=Thumb??Primary(400)；
      Folder/Audio 不生成；detail DTO 补 ImageTags/BackdropImageTags/PrimaryImageAspectRatio
- [x] 鉴权加载：MediaHubApp 实现 ImageLoaderFactory（OkHttp + EmbyImageAuthInterceptor 注入
      X-Emby-Token/X-Emby-Authorization，origin=scheme+host+port 匹配；跨 origin 重定向由
      OriginScopedCredentialInterceptor 剥离，ADR-030 红线覆盖图片）；磁盘缓存 image_cache 256MB LRU
- [x] 新模块 core:ui：PosterImage(2:3)/ThumbImage(16:9)/BackdropImage + 占位/错误态（Compose 绘制）
- [x] UI：库浏览媒体条目改 3 列海报墙（Episode 16:9）；继续观看改缩略图+进度条；
      极简详情页接线（backdrop+海报+元信息+简介+播放按钮，nav detail 路由）
- [x] posterUrl 落盘：PlaybackSession→PlaybackProgress→Room（列已存在，零迁移）
- [x] 真机走查（Xiaomi 14 Ultra / Android 16）：海报墙 3 列真实海报、Episode 16:9 剧照、详情页 backdrop+
      海报+元信息（2014·69分钟·★8.6）+播放按钮、继续观看缩略图+进度条（旧记录占位、重播自愈）、
      播放/退出释放无回归；图片 URL 无 Token（EmbyImageMapperTest/EmbyImageAuthInterceptorTest 契约钉死）
- [ ] CI 验证：随本提交 push 后由 GitHub Actions 执行；结果以交付报告记录 run id（不做 docs-only 二次提交）

## Phase 1B-2.4 —— Player UX Hardening（音轨 index 统一 / 音频诊断 / 字幕样式）✅ 代码+测试完成
- [x] TrackSelection 三套 index 语义统一（per-type ordinal；TrackMapperTest 3 用例钉死；isSelected/isDefault 分离）
- [x] Audio 诊断：isSupported / decoderName / 默认轨标志；全部音轨不支持时播放页黄条提示（不再静默无声）
- [x] 字幕默认白字+全透明背景+黑描边（ADR-032），字号/颜色/背景/描边/位置持久化（SubtitleStyle→DataStore）
- [x] 音轨 Bottom Sheet（语言/codec/声道/采样率/解码器/支持状态）+ 字幕 Bottom Sheet（轨道+样式）
- [x] UserPreferencesRepository 抽象；README/CHANGELOG/DECISIONS 同步
- [ ] CI 验证：随本提交 push 后由 GitHub Actions 执行；真机多音轨/字幕样式验证随后记录

## Phase 1B-2.5a —— Player Startup & Immersive UX（插入，暂停 Server Editor）🔄 IN PROGRESS
- [x] Item 1：TTFF 单调时钟（SystemClock.elapsedRealtime）+ 首帧渲染日志（renderTimeMs）
- [x] Item 2：provisional duration progress fallback（起播用 source.durationMs 临时时长，进度管线/UI/currentProgress 全链路回退）
- [x] Item 3：PlaybackLaunchSnapshot 直传（详情页快照跳过重复 Detail GET）
- [x] Item 4：自动横屏（SENSOR_LANDSCAPE 进入；退出按进入前实际方向显式恢复 PORTRAIT/LANDSCAPE，MIUI 兼容）
- [x] Item 5：沉浸式系统栏（隐藏 status/navigation bars + BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 边缘滑动临时唤出）
- [x] Item 6：Overlay 两层 UI —— 点击唤出 / 3s 自动隐藏
- [x] Item 7：Overlay 左上角 × + 完整剧集/系列标题
- [x] Item 8：Overlay 服务器名 + 图标（图标跟随 Server Editor，暂无则只显名）
- [x] Item 9：Overlay 真实媒体下载速度（Media3 TransferListener）
- [x] Item 10：Overlay 设备电量
- [x] Item 11：回归（seek / 字幕 / 音轨 Bottom Sheet / 退出 final flush 不回归）
- [x] Item 12：exact-head CI + 真机 smoke（CI run 32572243824 success；items 4-5 + Overlay 全过真机）

## Phase 1B-2.5 —— Server Management（暂停 Server Editor）🔄 IN PROGRESS
- [x] ServerEndpoint 数据模型 + Room migration 1→2（servers 去 baseUrl 加 note/icon；新增 server_endpoints 表）
- [x] MediaServer 模型：baseUrl 迁为计算属性（活跃线路 URL）+ note/icon/endpoints + 向后兼容构造器
- [x] ServerRepository 加载线路（combine servers+endpoints）；add/update/delete 处理线路
- [x] mapper 补 ServerEndpoint 双向映射 + 测试（ServerEntityMappersTest 5 用例）
- [x] Server Editor（名称/备注/图标/URL/用户名/重新登录/设为默认/删除 + 删除级联 + 默认原子 + 移除 runBlocking P1）
- [ ] 线路测试（走 testConnection，记录 DNS/HTTP/耗时/身份/会话）
- [ ] 手动线路切换（不做自动最快）
- [ ] 媒体源独立一级页面（首页/媒体库/媒体源/设置 路由）
- [ ] 单测 + exact-head CI + 真机验证

## Universal Playback —— 双内核 + 播放器手势（2026-08-23）✅ DONE（待真机 smoke）
- [x] U1 Media3 1.5.1 → 1.11.0 升级
- [x] U2 PlaybackEnginePort 解耦 + MpvPlaybackEngine（pinned prebuilt libmpv）+ MpvHttpBridge
- [x] U2 已知缺口记录：Blu-ray .iso resolve skip；MPEG-TS 原始流（留在矩阵，非回归）
- [x] U3-A AUTO 引擎选择：签名决策链（显式 > 历史 > DTS/TrueHD > Media3）+ 运行时降级（ADR-034）
- [x] U3-B 手势层：PlayerGestureLayer + PlayerGestureController 纯状态机 + SeekMode PREVIEW/COMMIT（ADR-035）
- [x] U3-B scrub / 双击矩阵 / 连续快退 / 长按倍速 + 9 项手势偏好 + 设置区
- [x] testDebugUnitTest / assembleDebug / lintDebug 全绿（cf9b9d2 U3-A、6eb01ac U3-B）
- [ ] 双内核真机 smoke：Media3 fast-path 片源 + DTS-HD 片源各测手势一致性
- [ ] exact-head CI（推送由用户执行）

## U4-A — Fast Start Instrumentation ✅ 代码+测试完成
- [x] PlaybackStartupTrace 数据模型：线程安全、milestone 只记第一次、可注入时钟（JVM 可测）
- [x] 生命周期：PlayerViewModel 创建 → PlaybackSession 传递 → 引擎/选择器记录
- [x] Media3 milestones: prepare/ready/videoDecoderInit/audioDecoderInit/firstFrame/playing
- [x] mpv milestones: bridge/create/init/loadfile/fileLoaded/videoReconfig(=firstFrame)/audioReconfig
- [x] 结构化 summary() 输出（不含 token/Authorization/Cookie）
- [x] U4-B Network Timing：OkHttp EventListener（StartupNetworkEventListener）+ PlaybackNetworkTraceSink
      PlaybackInfo POST → piStart/piEnd; media stream GET → mediaStart/firstByte; 无 sink 时不崩溃
- [x] StartupNetworkEventListenerTest 4 用例（URL 匹配/时序/无泄漏/无 sink 安全）
- [ ] CI 验证：随本提交 push 后由 GitHub Actions 执行；真机 baseline 随后记录

## Phase 1C — Unified Discovery（2026-08-29）✅ SEALED / PASS（device verification PASS）

- [x] 1C-1 Emby 搜索 Provider：SearchTerm+Recursive+IncludeItemTypes（Token 只走 Header，ADR-026）
- [x] 1C-1 GlobalSearchEngine：bounded concurrency 4 / 单服 8s / partial success / 取消传播
- [x] 1C-1 SearchViewModel（debounce 350ms+flatMapLatest）+ SearchScreen + 首页入口
- [x] 1C-1 搜索命中统一进 DetailRoute → Series Detail 页 UI 入口打通（Library 下钻不变）
- [x] 1C-2 MediaListQuery/MediaSort/MediaSortCapabilities + MediaQueryLibraryProvider（ADR-036）
- [x] 1C-2 Emby SortBy/SortOrder 全字段映射 + RANDOM 单页快照 + Fields 扩 6 字段
- [x] 1C-2 Library 排序入口 + 能力过滤 BottomSheet + 改排序全量重拉（沿用 race guard）
- [x] C2 用户排序激活后 Provider 顺序权威（SERVER_DEFAULT 保留目录优先）
- [x] LocalProvider nextOffset 累计推进修复（600/401/空/越界/边界回归锁）
- [x] Redactor 脱敏 SearchTerm（明文 + percent-encoded）
- [x] TokenStoreTest 夹具合成化（secret-lint 合规）
- [x] 评审 final hardening（P1×3+P2×1）：capability 收缩（OFFICIAL_RATING/BITRATE/SIZE 隐藏）/
  mapError 取消传播（+真实边界测试）/ 引擎快照 mutex 化（+多线程一致性测试）/ load() sort 快照
- [x] 冲突语义合并：Factory query+search 并存 / LibraryProvider Support 重构+sort 并存 / capabilities 并集
- [x] 二轮评审 concurrency patch：sendSnapshot 构建+发送整体串行化（防旧 snapshot 晚到状态倒退）+ 单调性回归
- [x] 真机 smoke（Xiaomi 14 Ultra / Android 16，APK @ aaca301，SHA256 83dc3132…）：PASS
      聚合搜索（拼音上屏冰血暴；剧集/电影/单集多类型结果带来源服务器名；多源并发参与）/
      Search→Series Detail（hero/元数据/播放/演员/季 chips×5）/
      快速切季 S2→S3→S1 终态正确（无 stale）/
      EpisodeRow SxxExx+标题+时长+缩略图；集点击→集详情（69分钟·★8.6·MKV）/
      排序菜单精确 9 项，OfficialRating/Bitrate/Size 确认隐藏（恢复须 per-server probe，不做客户端 fallback）/
      8 个非默认排序逐项切换零错误；标题 ASC→DESC 精确反转；RANDOM 快照滚动到底静态终止；
      排序后 loadMore 沿用同 sort；日志无搜索词（logcat 14371 行零命中，SearchTerm=**** 脱敏）
- [x] 1B-3.1 Series Detail 借 Search 入口正式补齐设备验收
- NOT_AVAILABLE（不阻塞，自动化覆盖）：>200 项容器「排序+续页」（欧美剧墙 67 项单页；
  LibrarySortViewModelTest 同 sort loadMore 用例覆盖）/ Local 源（设备未配置；
  LocalProviderPaginationTest 真实临时目录 600/401/边界分页回归覆盖）
- 状态：code complete / tests complete / **device verification PASS / SEALED**
- Device smoke code SHA: aaca301bf30378c0d47c846c728f0a43f9421f93
- Device: Xiaomi 14 Ultra / Android 16
- Result: PASS

## Phase 1E — Canonical Media Identity / Multi-Source Aggregation（2026-08-29）✅ 代码+测试完成（真机验证 pending）

- [x] A1 canonical-contract：ExternalIds/ExternalIdProvider/CanonicalKey(type,provider,value)/
      CanonicalKeyPolicy.keys 别名候选集合 + union-find 传递闭包分桶
      （core/model，仅 +2 文件 +MediaItem 一字段）
- [x] B1 emby-provider-ids：DTO ProviderIds 解析 + mapper 归一化（key 小写/冲突 provider 丢弃）+
      SEARCH_FIELDS += ProviderIds（LIBRARY_FIELDS 不动）+ 详情路径同步
- [x] C1 search-aggregation-ui：SearchAggregator union-find 纯投影（engine 零改动）+
      SearchResultEntry(Single/MultiSource) + VM entries 投影 + 聚合卡/展开成员行 + LazyColumn key
- [x] 测试：ExternalIdentityTest 9 + EmbyProviderIdsMappingTest 6 + 搜索 Fields 断言 +
      SearchAggregatorTest 12（冻结场景全覆盖 + A/B/C 传递闭包桥接回归）
- [ ] 真机 smoke（冻结）：予初/墨云阁同一 TMDb+IMDb 作品 → 聚合卡 '2 个来源' → 展开 →
      成员行 Series Detail；电影/剧集 TMDb 空间隔离；Episode TVDb 聚合；
      同 server 重复不算多来源；title/year 不参与
- NOT_AVAILABLE 预判：真服 ProviderIds 刮削质量决定实际聚合量（挂 UI 观察项，不判失败）
- 状态：code complete / tests complete / device verification pending（真机前不写 SEALED）

## Phase 1D — Library Filtering / Query Pipeline Extension（2026-08-29）✅ SEALED / PASS（device verification PASS）

- [x] A1 filter-contract：MediaFilter/MediaFilterField/MediaListQuery.filter +
      MediaQueryCapabilities 迁移（方案 a，MediaSortCapabilities 移除，无双术语）
- [x] B1 emby-filter：IncludeItemTypes/Years/IsPlayed/IsFavorite wire（played 用 IsPlayed Boolean，
      不用 Filters=；favorite 完整三态；不加 Recursive）；RANDOM+filter 快照
- [x] C1 library-filter-state：NavigationFrame 容器作用域（push/重置/恢复）+ onFilterSelected +
      loadMore 同 filter 快照 + race（generation guard）
- [x] C2 library-filter-ui：筛选入口（active indicator）+ Sheet（类型/年份草稿/已看/收藏）+ 清除全部
- [x] 测试：filter 域 7 + capability 迁移断言 + wire contract 6（含 filter+sort+分页核心组合）+
      VM 状态 9（容器作用域往返/race/loadMore 快照/能力过滤）+ UI helper 8
- [x] 真机 smoke（冻结规格，Xiaomi 14 Ultra / Android 16，APK @ b9b6f72）：
      APK SHA256 = 4d784d9c15dab7d02564bd57ed0c711732b021fb16270f6ee805385939e5b6ee：PASS
      剧集筛选→Fargo→季列表正常→返回筛选保持（container-scoped 实证：folder 行被 IncludeItemTypes 过滤、
      子容器未被打空）；Year=2014→冰血暴+血族；已看=true→1 部/未看=false→其余（互补）；收藏=true→空目录
      优雅占位/false→全量；Year=2014+公众评分降序（冰血暴前血族后）；Year+随机快照滚动终止；
      active indicator（primary tint+筛选（已启用））；清除全部→默认恢复
      年份草稿 "0000"：field 接受草稿文本 / 应用存活 / 零 Years=0000 请求 / 未提交任何有效年份筛选
- [x] 1B-3.1 关联：Series Detail 借 Search 入口的设备验收已于 Phase 1C smoke 补齐
- NOT_AVAILABLE（不阻塞，自动化覆盖）：>200 项容器「筛选+loadMore」（墙 67 项单页；
  LibraryFilterViewModelTest loadMore 同 filter 快照用例覆盖）
- 状态：code complete / tests complete / **device verification PASS / SEALED**
- Device smoke code SHA: b9b6f72a991379524f7bbe6ea8297534ac7152f1
- Device: Xiaomi 14 Ultra / Android 16
- Result: PASS

## V0.1 —— MVP（下一步）## V0.1 —— MVP（下一步）

### IN PROGRESS（无，等待开工）

### TODO（新增于 Phase 0.5）
- [ ] SAF 目录选择器（ACTION_OPEN_DOCUMENT_TREE + 持久化授权 + DocumentFile 树导航，Phase 0.6）
- [ ] AddServer 认证流接入 CredentialVault（密码按 Provider 策略加密保存/销毁）
- [ ] Emby/Jellyfin/WebDAV 的 `remoteReportIntervalMs` 按官方协议确认节流值

### TODO
- [ ] Emby Provider 剩余能力（搜索→字幕→进度上报；登录/媒体库/详情/播放源已完成）
- [ ] Jellyfin Provider 完整实现（同上，独立 Connector）
- [ ] WebDAV Provider（PROPFIND 文件树 + Basic 认证 + 播放）
- [ ] 播放前接入 PlaybackCompatibilityEvaluator（resolve 流程输出三态决策）
- [ ] 播放器：URL 过期自动重解析、外挂字幕、字幕/音频延迟、HDR/媒体信息显示
- [ ] 详情页完整化（演职员/多版本/季集直达；海报/简介/背景图已随 1B-2.3 完成）
- [ ] 全局搜索（跨 Provider 聚合）
- [ ] cleartext 改 networkSecurityConfig（按域名放行）
- [ ] 诊断页 + 脱敏报告导出
- [x] Coil 图片管线接入（海报/背景图，Phase 1B-2.3）

### BLOCKED
- （无）

## V0.2 / V0.3 / V1.0

见 ROADMAP.md（SMB、云盘、聚合媒体库等）。

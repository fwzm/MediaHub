# 交接文档（HANDOFF）—— 每个 AI 必读
# 交接文档（HANDOFF）—— 每个 AI 必读
> 最后更新：2026-08-30（Phase 1G A/B/C 三 slice 全 ACCEPTED @ `9329bf3`，integration/1g-jellyfin 已建，PR/CI/device smoke 待走）

## Phase 1G Jellyfin Provider Foundation & Parity（进行中——A/B/C 全 ACCEPTED，integration 待 merge）
- **ADR-039 已冻结**（DECISIONS.md）：现代 Authorization contract / generic image auth
  （auth-scope 归属 fail-closed）/ provider-specific session store / IDENTITY_LOOKUP
  协议缺口 DEFER（capability 保持 null）/ one-way Detail source guard / 旧 Emby
  progress WIP out-of-scope / encodeDefaults 教训。core parity ≠ Emby 字段 1:1。
- **slice 链（全 ACCEPTED，线性 @ baseline 45a467b）**：
  A auth+shared foundation @ `37f01a3`（review round2 后）→
  B catalog+search @ `dd2288d`（review round2 后）→
  C playback+progress+identity gate @ `9329bf3`（review round2 后）。
- **runtime capability（audit 测试锁定）**：AUTH+LIBRARY+DETAIL+SEARCH+PLAYBACK+PROGRESS；
  QUERY/IDENTITY_LOOKUP 缺席（null）。
- **集成注意（device smoke 新增验证点）**：Jellyfin 10.9.x 服务端自述 direct-stream
  http streaming 有缺陷且非 force 时关内部 EnableDirectStream——真机必须验证真实
  PlaybackInfo 响应，不能只信手写 SupportsDirectStream fixture。
- **Integration 状态**：PR #11 OPEN（integration/1g-jellyfin → main，base=`45a467b`，
  GitHub ahead_by=10 commits 线性）；exact-head CI = 待本轮 docs/KDoc patch 后的新 run
  （前一 run `33295225612` @ `6511bd0` success，但该 head 缺 KDoc 修正，不给新 SHA 背书）。
- **待办**：新 exact-head CI 绿 → PR #11 merge → device smoke（16 场景链，
  需真实 Jellyfin server；无 server = device verification BLOCKED ≠ SEALED）。
  非阻塞遗留：in-flight cancellation robustness test（barrier/deferred 构造）；
  PlayerViewModel onCleared 兜底无 flushFinal（既有生命周期设计，非 1G 引入）。

## Phase 1F Canonical Detail / Source Selection（2026-08-29）✅ SEALED / PASS
- **ADR-038 已冻结**（DECISIONS.md）：方案 A = detail-time canonical source resolution；
  supersede ADR-037 的"聚合只发生在搜索层"阶段性 scope 限制（identity 规则全部不变）。
- **核心语义**：CanonicalIdentityGraph（core/model）= connected-component 唯一实现
  （SearchAggregator 与 CanonicalSourceResolver 共享，禁止第二套 union-find）；
  resolver = 有界传递闭包（frontier 扩张，查询含当前服务器自身），
  硬边界 concurrency≤4 / per-server timeout 8s / maxRounds 4 / maxKeys 32 / maxOccurrences 64 → truncated；
  sourceState 独立于 DetailUiState（主内容绝不等待解析）；
  来源语义单位 = occurrence（同服副本"·副本N"，禁用 MediaVersion 语义）；
  切换 = route replacement（Back 不回 Detail A）；occurrences 不进 nav args。
- **Provider**：MediaIdentityLookupProvider.findByCanonicalKeys + ProviderCapability.IDENTITY_LOOKUP
  （ADR-022 nullable 组合）；Emby `AnyProviderIdEquals=tmdb.123,imdb.tt…`（非 SearchTerm），
  IncludeItemTypes 锁 MediaType，SEARCH_FIELDS 共用（LIBRARY_FIELDS 不动）。
- **Slice 链**：dev feature/1f-canonical-detail a11222f(A1) → 8d4f052(B1) → a360eb2(C1) →
  770de17(C2) → c76f38e(docs)；integration/1f-canonical-detail cherry-pick 911b835 → d572044 →
  71d4a98 → 9281dab → 592182b（**PR #8**，base=d128d09），exact-head CI run 33262600151 @
  592182b success。
- **独立 review 裁定（PATCH REQUIRED → 本 commit 修复）**：
  P2-1 lookup `PagedResult.hasMore` 被丢弃→任一 server hasMore=true 即标 truncated
  （v1 仍不续拉分页，但绝不把 partial 当 complete；后续页可能含 alias bridge）；
  P2-2 truncation 提示与 selector gate（distinct serverId ≥ 2）解耦——未发现第二 server
  时（最可能漏源的场景）也必须提示（`truncationMessage` 纯函数）；
  hardening：Emby lookup 对无 IncludeItemTypes wire 值的类型（SEASON/VIDEO 等）
  requireNotNull 拒绝，不隐式放宽为全类型 AnyProviderIdEquals 查询。
  对应回归：CanonicalSourceResolverTest 13（hasMore→truncated）/
  SourceSelectorModelTest（单 server truncated 也提示）/ EmbyIdentityLookupProviderTest（SEASON 拒绝 0 请求）。
- **真机 smoke（2026-08-30 PASS）**：Xiaomi 14U @123e243f，APK @ 267c122
  SHA256 9774684c…dc38b3（device pull == local build 三向一致）。
  8 场景：1-6 全 PASS（含 route replacement Back 核心验收、播放 header 显示目标 server）；
  7 单源无 selector = NOT_AVAILABLE（三样本 shogun/severance/zootopia 全真实跨源；
  Episode Idle 路径真机确认 + 单测覆盖）；8 = NOT_AVAILABLE/automated PASS。
  隐私：SearchTerm=**** 脱敏 ×36，原始词仅 adbd 命令日志，AnyProviderIdEquals 零命中。
  附加观察：予初同 canonical 双副本（occurrence 语义真实验证）；入口相关可达性
  （墨云阁入口仅见予初单条目）；幕府将军搜索 Single 但 resolver 补全跨源（增量价值实证）。
- **封板（2026-08-29 裁定 APPROVED TO MERGE）**：PR #8 merged，普通 merge 生成 merge
  commit **`a7df978`** = 1F 的 authoritative baseline（main 前移到 merge commit 本身，
  非 PR branch 上的 267c122/68ac981——SHA 表述已校正）。下一阶段 =
  **Phase 1G Jellyfin Provider Foundation & Parity**（从本 closeout PR #9 合并后的
  authoritative main baseline 切分支；contract 待冻结：
  A connection/auth → B library/detail → C search+identity lookup（1G-E 为架构验收点：
  Emby↔Jellyfin 跨 Provider canonical source 零特殊分支）→ D playback/progress/smoke）。

## Phase CI-H1（前次）：CI/Test Hardening
- **测试治理规则（对所有后续 Agent 生效）**：
  - **coroutine-test**：被测对象拥有周期性 coroutine（polling/heartbeat/periodic delay）时，
    测试禁止无界 `advanceUntilIdle()`（会永久自旋拖死测试）——改用 `runCurrent()`、
    `advanceTimeBy(x)` 与显式 job shutdown/cancel。一次性异步到稳定态的 VM 测试
    仍可 `advanceUntilIdle()`。已体现在 LibraryFilterViewModelTest（1D）。
  - **race/stale fake 必须 non-cooperative**：吞 CancellationException 后照常返回旧数据，
    才能真实走完 generation guard 丢弃路径（delay 版取消即消失，是假覆盖）。
    已体现在 LibraryFilterViewModelTest（1D）。
- **CI workflow（android-ci.yml）**：actions 升级 checkout@v5 / setup-java@v5 /
  setup-gradle@v5 / setup-android@v4（全部 Node-24 运行时，消除 deprecation 警告）；
  **Unit tests 与 Lint step 各自 timeout-minutes: 15**（测试挂死不再吃满 40 分钟 job
  timeout）；Lint 条件用 !cancelled()（即 ${{ !cancelled() }}）——测试 FAIL/TIMEOUT 仍执行、
  workflow 被 concurrency 取消时不执行（不用 always()，它与 cancel-in-progress
  语义冲突：被 supersede 的旧 run 会照样进 Lint）；job 名保持 `build`——main
  branch protection 按 context "build" 校验，拆分 jobs 前必须先同步修改 protection rule。
- **setup-gradle 刻意停在 v5（评审裁定）**：v6 默认 cache-provider 切换为
  proprietary 的 enhanced caching（非纯 OSS）——Node-24 迁移只需 v5；
  未来显式接受 enhanced caching/Terms 再单独升级，不夹带在 runtime hardening 里。
- **CI 状态口径（Agent 报告纪律）**：commit created / local gate PASS /
  remote CI cancelled / exact-head CI PASS 四态严格区分；被 concurrency 自动取消的
  SHA 不算 CI 验证过，禁止倒推。
## Phase 1E Canonical Media Identity（本次）：跨源身份 + 搜索聚合卡
- **身份模型（ADR-037）**：ExternalIds（tmdb/imdb/tvdb，multi-alias 无优先级）→
  CanonicalKey(type, provider, value) 候选集合 → 集合交集判定聚合；
  MediaType 内嵌键内；title/year 永不参与；无 ID = 单例不聚合。
- **Episode**：v1 只用自身 ProviderIds；series+S/E 复合键 DEFER（数据链不支持）。
- **聚合层**：SearchAggregator（feature/search 纯投影，union-find 分桶）；
  GlobalSearchEngine/State 零改动；MultiSource 卡 = canonical 匹配 + distinct serverId ≥ 2。
- **Emby**：EmbyItemFields/双 DTO 增 ProviderIds；mapper 归一化（key 小写、冲突丢弃）；
  SEARCH_FIELDS += ProviderIds（LIBRARY_FIELDS 不动——聚合只在搜索层）。
- **UI**：搜索结果 = Single（现有行）/ MultiSource（聚合卡：首 occurrence 元数据 +
  'N 个来源'，点击展开成员行，成员行走现有 Detail 导航）。
- **状态**：code complete / tests complete / **device verification PASS / SEALED**。
- **真机验收（2026-08-29）**：
  ```
  Device smoke code SHA: 9fccb91b8a4f1c12d14fa6ac73bf0dfcdf41cd70
  APK SHA256: 0d0971ad4697278a8611e17bc1bcf6b3a1e1f98e68f0b695fb4672e06558a26e
  Device: Xiaomi 14 Ultra / Android 16
  Result: PASS
  ```
  Series cross-source aggregation: PASS — Fargo 2014 Series → 2 个来源聚合卡，
  展开予初/墨云阁成员行 serverName 正确，点成员 → Series Detail → 返回搜索页完整。
  Movie cross-source aggregation: NOT_AVAILABLE — tested server items did not share
  canonical ProviderIds; correctly remained separate。
  Episode cross-source: NOT_AVAILABLE — 予初/墨云阁均未在 Episode 响应中返回 ProviderIds。
  title/year 不参与（负向实证）：同名/同年 Movie 条目在 canonical IDs 不匹配时仍保持独立。
- **待真机验证（冻结）**：予初/墨云阁搜索同一部有 TMDb+IMDb 的作品 → 聚合卡
  '2 个来源' → 展开 → 点成员走 Series Detail；电影 TMDb=123 vs 剧集 TMDb=123 不聚合；
  Episode 自身 ProviderIds 聚合；同 server 重复条目不算多来源。
- **下一步**：Integration PR 独立审查 → 真机 smoke → 封板。

## Phase 1D Library Filtering（前次）：筛选器 + Query Pipeline 扩展
- **域模型**：MediaFilter（tri-state aggregate：mediaType/year/played/favorite，全 null=默认）+
  MediaFilterField；MediaListQuery 增加 filter——筛选与排序同一查询管道下沉服务器，分页前执行。
- **capability**：MediaSortCapabilities 演进为 MediaQueryCapabilities(sortFields, filterFields)
  （方案 a，一次性迁移，无 typealias 双术语）；MediaQueryLibraryProvider.sortCapabilities → capabilities。
- **Emby wire**：IncludeItemTypes（Movie/Series/Episode 单值）/ Years（单年等值）/
  IsPlayed=true|false（Boolean 1:1，不用 Filters=IsPlayed|IsUnplayed）/ IsFavorite=true|false（完整三态）；
  默认 filter = 四参数全不传；**不加 Recursive**（筛选只作用于当前容器直接子级，不改浏览导航契约）。
- **核心语义（冻结规格 B 修订）**：Filter 是 container-scoped 状态，不跨容器继承（与 Sort 刻意不同）——
  NavigationFrame(folder, filter) 导航栈：openFolder push+重置子级、goToParent pop+恢复父级。
  VM 层 filter 快照与 race guard 同 1C 模式。
- **UI**：TopAppBar 筛选入口（active=primary tint+『筛选（已启用）』）；筛选 Sheet 四分区即时生效；
  年份数字输入带草稿语义（空串=清除、恰好四位=提交、中间态不发请求）；『清除全部』。
- **评审 hardening**：yearDraftToFilter no-crash（"0000" 等 year<=0 草稿 no-op，domain invariant 不放宽）+ race 测试升级 non-cooperative（吞取消仍返回旧数据，guard 丢弃路径真实锁定）。
- **状态**：code complete / tests complete / **device verification PASS / SEALED**。
- **真机验收（2026-08-29）**：
  ```
  Device smoke code SHA: b9b6f72a991379524f7bbe6ea8297534ac7152f1
  APK SHA256: 4d784d9c15dab7d02564bd57ed0c711732b021fb16270f6ee805385939e5b6ee
  Device: Xiaomi 14 Ultra / Android 16
  Result: PASS
  ```
  冻结链：类型=剧集→Fargo→季列表正常→返回筛选保持（container-scoped 实证）；
  Year=2014→冰血暴+血族；Played 双向互补（已看=英版同志亦凡人 1 部）；
  Favorite=true→空目录优雅占位/false→全量；Year+公众评分降序（冰血暴前）；
  Year+随机快照滚动终止；active indicator；清除全部。
  年份草稿 "0000"：field 接受草稿文本 / 应用存活 / 零 Years=0000 请求 / 未提交任何有效年份筛选。
- **UX backlog（不阻塞）**：选排序/筛选后 Sheet 在 Loading 期消失、Content 回来后重现（闪烁）；
  筛选状态为 per-Route VM，离开 library 重进重置（导航栈内往返保留）。
- **已真机验证（冻结规格 smoke 全 PASS）**：TV library 类型=剧集 → Fargo → 季列表正常 → 返回后类型=剧集保持；
  Year=2014 / Played/Unplayed / Favorite 双向 / Year+Rating sort / Filter+Random 快照 /
  Filter 后 loadMore 同 query / active indicator / 清除全部。
- **下一步**：Phase 1D 已封板。下一阶段从新 main（89171cf）单独开分支：
  先 feature/ci-test-hardening（CI/Test Hygiene backlog），再同片多源聚合卡（externalIds，独立阶段）。

## Phase 1C Unified Discovery（本次）：1C-1 聚合搜索 + 1C-2 服务端排序
- **Query Pipeline（ADR-036）**：MediaListQuery/MediaSort/MediaQueryCapabilities 独立于 PageRequest；
  MediaQueryLibraryProvider.getItems(libraryId, query) 把排序下沉服务器（分页前执行）；
  Provider 用 capabilities（1D 起 sortFields+filterFields）自述能力，UI 能力过滤渲染，
  无能力回退旧接口并隐藏入口。（MediaSortCapabilities 已于 Phase 1D 迁移移除，勿再引用）
- **Emby 搜索/排序**：EmbySearchProvider（SearchTerm+Recursive，Token 只走 Header）；
  EmbyLibraryProvider 同实例承载 LIBRARY+QUERY，SortBy/SortOrder 全字段映射，
  RANDOM 单页快照（totalCount 再大也终止）；Fields 扩 6 个发现字段并映射进 MediaItem。
- **聚合搜索**：GlobalSearchEngine（并发 4/单服 8s/partial success/hits 稳定序/取消传播）+
  GlobalSearchViewModel（debounce 350ms+flatMapLatest）+ SearchScreen + 首页入口；
  命中统一进 DetailRoute —— **Series Detail（季 chips+EpisodeRow）首次获得 UI 入口**，
  Library 的 SERIES 下钻保持原行为（两条 UX 并存，见 ADR-036）。
- **展示语义**：用户主动排序后 Provider 顺序权威（UI 不再目录优先重排）；SERVER_DEFAULT 保留旧策略。
- **评审加固**：LocalProvider nextOffset 累计推进（修死循环）；Redactor 脱敏 SearchTerm
  （含 percent-encoded）；TokenStoreTest 夹具合成化（secret-lint）。
- **状态**：code complete / tests complete / **device verification PASS / SEALED**。
- **真机验收（2026-08-29）**：
  ```
  Device smoke code SHA: aaca301bf30378c0d47c846c728f0a43f9421f93
  Device: Xiaomi 14 Ultra / Android 16
  Result: PASS
  ```
  1C-1 聚合搜索 / 1C-2 服务端排序 / 1B-3.1 Series Detail（借 Search 入口）全部 PASS；
  排序菜单精确 9 项（OfficialRating/Bitrate/Size capability hidden pending per-server
  protocol evidence）；RANDOM 快照终止；logcat 14371 行搜索词零泄露（SearchTerm=****）。
- **NOT_AVAILABLE（不阻塞，自动化覆盖）**：>200 项容器「排序+续页」（墙 67 项单页；
  JVM loadMore 回归覆盖）；Local 源设备未配置（LocalProviderPaginationTest 600/401/边界回归覆盖）。
- **待真机验证**：排序菜单应只见九个已证实字段（OfficialRating/Bitrate/Size 已隐藏，
  恢复需 per-server probe）；DateAdded/Title/CommunityRating/CriticRating/Year/Premiere/Runtime/Random
  实测排序正确（RANDOM 快照）；搜索冰血暴双源 → Series Detail；单服超时保留他源结果；
  日志无搜索词；Local 600 项分页走完。
- **下一步**：merge PR #2（integration/1c-unified-discovery → main）→ Phase 1C 结束，下一阶段单独开 feature branch。
## Universal Playback U1-U3（本次）：双内核 + AUTO 引擎选择 + 播放器手势
- **双内核**：PlaybackEnginePort 契约下 Media3（1.11.0）与 mpv（pinned prebuilt libmpv）并存；
  mpv HTTP 流走 MpvHttpBridge（core:network，IPv4 loopback），ADR-030 凭据红线覆盖。
  架构与决策链见 ADR-034；手势层与 SeekMode 语义见 ADR-035。
- **AUTO 选择**：UserPreferences.playbackEngineMode 默认 AUTO；PlaybackEngineSelector 按
  container|videoCodec|audioCodec 签名决策（显式 > EnginePreferenceHistory 失败指纹 >
  DTS/TrueHD > Media3）；SwitchablePlaybackEngine 在 decoder/source 错误或静音时
  保存位置切 mpv 同位置重播，网络错误不降级。
- **手势层**：PlayerGestureLayer（Compose 自定义识别器）+ PlayerGestureController（纯状态机）；
  scrub（灵敏度 clamp(时长×10%, 60s, 10min)，松手 COMMIT）、双击矩阵（快退/快进默认关，
  未启用侧回退播放/暂停）、双击左侧按住连续快退（333ms tick PREVIEW seek）、长按倍速
  （入口 2.0×，0.1-5.0× 阶梯，松开恢复永久倍速）。SeekMode.PREVIEW 不发 Seeked，
  不触发远端进度 flush。
- **测试经验（重要）**：kotlinx-coroutines-test 中 backgroundScope 协程不被
  advanceUntilIdle() 驱动，需 runCurrent() / advanceTimeBy()+runCurrent()——
  SwitchablePlaybackEngineTest 错误路径用例已据此修正。
- **提交**：cf9b9d2（U3-A）、6eb01ac（U3-B）、bbfcfaf（docs），均已推送；U3 已封板 @12447fb（含 U3-C 竖向手势 + U3-C.1 实时apply）；下一阶段 U4-A 起播埋点
  testDebugUnitTest / assembleDebug / lintDebug 本地全绿。
- **已知缺口（留在矩阵，非回归）**：Blu-ray .iso resolve skip；MPEG-TS 原始流。
- **下一步**：双内核真机 smoke（Media3 fast-path + DTS-HD 片源各测手势一致性）→
  用户推送 → exact-head CI。
## Phase 1B-2.1（前次）：Direct Stream 协议边界 FINAL HARDENING
- PlaybackInfo 改官方 POST contract：POST /Items/{itemId}/PlaybackInfo；协商参数进 JSON body
  （EmbyPlaybackInfoRequestDto + EmbyDeviceProfileDto），UserId 同时走 query 与 typed body；
  Token 不进 URL 不进 body（只走 X-Emby-Token 请求头）；requestJson encodeDefaults=true/explicitNulls=false。
- DeviceProfile 保持最小官方形状：SupportedMediaTypes=Video + DirectPlayProfiles[{Type=Video}]；
  EnableDirectPlay/EnableDirectStream/EnableTranscoding 只存在于 PlaybackInfoRequest 顶层。
- 严格校验（响应损坏 ≠ 需要转码）：MediaSources 空 / MediaSourceId 缺 / PlaySessionId 缺 → Parse；
  仅"源非空但全不支持 DirectStream"才 NotYetImplemented("需要转码")。
- directStreamUrl 参数非 nullable 必填，始终输出 MediaSourceId/PlaySessionId/static=true；禁止残缺 URL。
- RequiredHttpHeaders 并入播放请求头；先按 Header 名大小写不敏感语义剔除冲突源级键，
  再写入权威 X-Emby-Token/X-Emby-Authorization，避免重复鉴权头。
- 只视频型门禁：MOVIE/EPISODE/VIDEO 才进播放协议（DIRECT_STREAM_TYPES）；AUDIO（0 HTTP）、
  LIVE_TV/OTHER 明确拒绝，绝不构造 /Videos/... 音频地址。
- mapHdrType 3 参（videoRange/extendedVideoType/extendedVideoSubType）识别 Dolby Vision，
  覆盖 ExtendedVideoSubType=DoviProfile81 等枚举值。
- 可测性改造：新增 PlaybackEnginePort / PlaybackEngineCreator（fun interface）；
  PlayerViewModel 依赖 ServerStore/ProgressStore/PlaybackEngineCreator；
  ProgressStore 补 getResume/save（ProgressRepository 实现补 override）；
  AppModule @Provides 返回 PlaybackEngineCreator；feature:player 补测试依赖。
- 测试：EmbyPlaybackProviderTest 19 用例 + PlayerViewModelTest 3 用例（A/B/C）；
  HomeViewModelTest FakeProgressStore 补 2 方法；当前树共 133 个 `@Test`。
- **验证状态**：随本提交 push 后等 GitHub Actions（本机 aarch64 无 Android SDK/qemu，无法本地构建）；
  结果 run id 以交付报告记录，**不做 docs-only 二次提交**（避免 code→CI→docs→CI 循环）。
- 下一刀：Phase 1B-3（候选：搜索 / 播放进度上报 / 真机 smoke Direct Stream）。
## Phase 1B-2（前次）：Emby Item Detail + 无转码 Direct Stream
- 详情竖切已实现：getItemDetail（GET /Users/{userId}/Items/{itemId}）→ MediaDetail
  （versions/streams/audioTracks/subtitles/chapters/hdr 类型映射）。
- 播放竖切已实现：resolvePlayback → getPlaybackInfo → MediaSourceSelector → directStreamUrl。
- **无转码红线**：PlaybackInfo 固定 EnableDirectPlay=false/EnableDirectStream=true/EnableTranscoding=false；
  Selector 只接受 SupportsDirectStream==true 的源；否则 NotYetImplemented("需要转码")。
- **Token 红线**：Token 永不进 URL——directStreamUrl 参数只有 static/MediaSourceId/PlaySessionId，
  Token 走 source.headers（X-Emby-Token + X-Emby-Authorization 含 UserId）。
- EmbyProviderFactory 现暴露 AUTH+LIBRARY+DETAIL+PLAYBACK（runtimeCapabilities，ADR-022/026）。
- 新增测试 18 条（Mapper 2/Selector 2/DetailProvider 6/PlaybackProvider 8）+ FactoryTest 断言更新，
  全项目预计 114 用例。
- 新增文件：EmbyDetailDtos / EmbyDetailMapper / EmbyDetailProvider / EmbyPlaybackProvider / MediaSourceSelector；
  改造：EmbyApiClient / EmbyLibraryDtos（EmbyItemFields 统一映射）/ EmbyMediaItemMapper / EmbyLibraryProvider。
- **尚未实现**（Phase 1B-3+）：转码（禁入红线，短期不做）、搜索、字幕、进度上报、
  播放器 URL 过期自动重解析、外挂字幕。
- **验证状态**：CI 已通过（run#31630416127，2026-08-13：assembleDebug/testDebugUnitTest/lintDebug 全绿）。
  本机 aarch64 无 Android SDK/qemu，无法本地构建，构建验证由 GitHub Actions 完成。
- 下一刀：Phase 1B-3（候选：搜索 / 播放进度上报 / 真机 smoke Direct Stream）。
## 排头 Phase 1B-1（2026-08-12）
- 真实浏览竖切已实现：Views → Items(ParentId) → Series → Season → Folder。
- EmbyLibraryProvider + Mapper + DTO；MediaType.isContainer=FOLDER/SERIES/SEASON；
  LibraryViewModel root→getLibraries；LibraryScreen 顶层 Views + MediaLibrary 导航。
- EmbyProviderFactory 现暴露 AUTH + LIBRARY（runtimeCapabilities）。
- **尚未实现**（Phase 1B-2+）：PlaybackInfo、DirectPlay、Transcode、详情、搜索、字幕、进度上报。
- 真机 smoke：登录→点 Emby→见 Views→点电影库→见电影→点剧集库→见 Series→Series→Season。
  点 Movie/Episode 暂提示"播放能力尚未接入"（LibraryScreen onOpenItem 会导航 player，但 player 会解析失败）。
- 下一刀：Phase 1B-2 Item Detail + Direct Play PlaybackSource。

## 排头 FINAL PATCH 4.1（2026-08-12）

- 纯测试修正：HomeViewModelTest B/C/D 改为真实制造前置状态（SessionExpired/SignedOut/已有 auth state），
  不再"测试名比覆盖范围更强"。生产代码未改动。
- **Phase 1A 生产实现封板**；PR #1 关闭（superseded by main），不再 merge。
- logout 仍用 servers.first()（P2，留 Phase 1B 顺手统一，非 blocker）。
- 待办：确认 latest main（下个提交）CI success 后进入 Phase 1B。

## 排头 FINAL PATCH 4 摘要（2026-08-12）

- P1 修复：forceRestore 改 `serverRepository.getServer(serverId)`（读 DB 最新，非 servers.first() 缓存），
  复用 restore(server)（消除重复恢复 + 非认证不写 Restoring）。
- 可测性：ServerStore/ProgressStore 接口 + AppModule @Binds；HomeViewModelTest 4 例。
- 全项目 87 用例；assembleDebug/test/lint 通过。
- **待办**：确认 latest main（下个提交）GitHub Actions success 后 Phase 1A 封板；进入 Phase 1B。

## 排头 FINAL PATCH 3 摘要（2026-08-12）

- P1 修复：re-login 成功后 Home 登录状态立即刷新（HomeViewModel.forceRestore + Navigation result
  `auth_changed_server_id`，AddServer onDone 设置 result，Home 观察并 forceRestore）。
- P2 修复：非认证 Provider（Local/WebDAV 无 auth）不再进入 authStates，不显示 未登录/Restoring/Logout。
- needsRelogin 单一源：AuthNavigationPolicy（feature:home，HomeViewModel 实际调用）+ AuthNavigationPolicyTest；
  清理 ExistingServerEditPolicy 的重复 needsRelogin/isProviderLocked/unused MediaItem import。
- 全项目 83 用例；assembleDebug/test/lint 通过。
- **待办**：确认 latest main（下个提交）GitHub Actions 真正 success 后 Phase 1A 封板，进入 Phase 1B。

## 排头 FINAL PATCH 2 摘要（2026-08-12）

- 修复 existing-server re-login 最后一组确定 bug：
  - descriptor 按 serverType 匹配（`ExistingServerEditPolicy.descriptorFor`，不再 "EMBY"≠"emby"）
  - re-login Provider 锁定（existingServer 时 selectProvider 拒绝切换）
  - needsRelogin 精确（仅 SignedOut/SESSION_EXPIRED/SERVER_MISMATCH；FORBIDDEN/网络/5xx 保留）
  - buildDraft 完整保留元数据（id/type/isDefault/sortOrder/createdAt/lastConnectedAt/lastError）
  - EmbyAuthProvider 注释 /Users/Me → /Users/{userId}
- 新增 `feature/server` 测试：`ExistingServerEditPolicyTest` 8 例；全项目 82 用例。
- **待办**：等在 GitHub 确认 latest main（下一提交）Actions 真正 success；不进入 Phase 1B。

## 0. 最终架构主线（main 为唯一主线）

- **以 main 为唯一架构主线**（ADR-029，用户确认）。PR #1 不 merge（superseded by ADR-029/main）。
- 认证架构：main 版本（TokenStore + EmbySessionStore + restoreSession: AuthSessionState）。
- **Phase 1A FINAL PATCH（本提交）**：
  1. Emby 会话验证改用 `GET /emby/Users/{userId}`（去未文档化 /Users/Me）；
     getCurrentUser(token,userId) / logout(token,userId)；X-Emby-Authorization 带 UserId。
  2. Existing Server Re-login：卡片点击 失效/未登录/身份变更（认证 Provider）→
     `server/add?reauthorizeId={serverId}`，复用 same localServerId + updateServer（不重复）。
  3. isPaused → `!player.playWhenReady`（buffering 不误报 paused）。
- 防串服 + 失效策略：remoteServerId 校验不发错 Token；仅 401 清会话；403/malformed/网络保留。
- **后续新功能一律 base 在 main**；SAF 完整落地留待 Phase 0.6（当前 LocalProvider 为 File 型）。

## 0. Phase 1A finalization（最新）

- 会话恢复已接入 App：Home 启动自动 restoreSession（通用 MediaAuthProvider 契约），
  ServerCard 显示登录态 + 退出入口；密码框遮罩。
- 防串服：恢复/登出前无 Token 校验 remoteServerId（SERVER_MISMATCH 不发 Token）。
- 失效策略：仅 401 清会话；403/malformed/5xx/网络保留。
- 官方协议：/emby API root（EmbyEndpointResolver）+ X-Emby-Authorization `Emby UserId=...` schema。
- **待真机验收**：杀 App → 恢复 → 撤销 Token → 失效 → 重新登录 → Logout（步骤见交付报告）。

## 1. 当前项目状态

- **Phase 0（骨架）+ 0.5（加固）+ 0.5.1（边界）+ 1A（Emby 认证）已完成**：`assembleDebug`、
  `testDebugUnitTest`（66 用例）、`lintDebug` 全部通过；CI（GitHub Actions）已就绪。
- 能力语义（ADR-022/026）：Handle 只暴露已实现能力——**Emby 当前仅 AUTH**（runtimeCapabilities={AUTH}）；
  Local 有 BROWSE/DETAIL/PLAYBACK；Jellyfin/WebDAV 为空。
- **Emby 认证闭环已实现**（ADR-026）：登录/恢复/验证/登出；Token 入 TokenStore（localServerId 键）、
  会话元数据入 EmbySessionStore；localServerId ≠ remoteServerId；X-Emby-Token 集中注入；
  401=token 失效清会话，网络问题保留；密码绝不持久化。
- 退出流程（ADR-023）：返回按钮走 `stopAndFlush()` 显式状态机；
  **Stopped 事件仅状态通知、不触发自动 flush**——退出上报唯一权威路径是
  `engine.stop() → flush(finalProgress) → coordinator.stop() → engine.release()`，
  禁止让 Coordinator 再对 Stopped 自动 flush（会造成两次本地写入 + 两次远端上报）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 端到端可用路径：添加媒体库（本地存储）→ 文件树浏览 → 播放（本地文件）→ 继续观看。
- Emby / Jellyfin / WebDAV 的 API 业务**未实现**（Phase 1 目标，见 TASKS.md），
  能力组合已就绪，方法以 `ProviderException.NotYetImplemented` 占位；
  连接测试已是协议级（System Info / OPTIONS 嗅探）。

## 2. 本次完成了什么

- Phase 0：23 模块工程、领域模型、Provider 抽象、网络层、存储、Media3 引擎、
  兼容性评估器、Server 管理 UI、首页/媒体库/播放器/设置页面、文档体系、git 初始提交。
- Phase 0.5：MediaProvider 能力组合 + ProviderHandle；ProviderDescriptor 动态注册；
  CredentialVault；进度三档节流（ProgressSyncCoordinator）；播放请求头 per-engine；
  协议级连接测试；CI workflow；测试扩至 40 用例。

## 3. 修改了哪些文件（关键）

```
根：settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/libs.versions.toml
core/model/*（领域模型） core/network/*（网络） core/database/*（Room/DataStore）
core/security/*（Keystore） core/logging/*（日志脱敏）
player/engine/*（Media3 封装） player/compatibility/*（评估器）
provider/api/* provider/base/* provider/local/* provider/{emby,jellyfin,webdav}/*
feature/{home,server,library,detail,search,settings,player}/*
app/*（DI/导航/主题/资源）
docs/* 与 7 份根文档
```

## 4. 为什么这样设计（要点）

- 所有数据源实现 `MediaProvider`（provider:api），UI 不感知来源（ADR-002）。
- 播放 URL 永不落库，播放时 resolve（ADR-003）。
- Provider 自注册用 Hilt `@IntoSet`（@IntoMap 在该工具链有 KSP bug，ADR-005）。
- 播放决策纯函数化（ADR-004），评估器已就绪但**尚未接入 resolve 流程**（下一步做）。
- 缓存四类分离（ADR-009）；网络 API/媒体分离（ADR-012）。

## 5. 遗留问题 / 下一步建议

1. **V0.1 下一里程碑 Phase 1B：Emby 媒体库**（LibraryProvider）：/Users/{userId}/Views、
   /Users/{userId}/Items（分页）、详情 /Items/{itemId}；实现后在 EmbyProviderFactory 填
   library + detail 字段（ADR-022/027）。endpoint 必须查官方文档。
2. 待接 UI：首页服务器卡片显示 Emby 登录态（EmbyAuthState 驱动），提供"登录/退出"入口。
3. Jellyfin 复用 ClientIdentity/会话模式（独立 Connector）。
2. 播放前把 `PlaybackCompatibilityEvaluator` 接入 `resolvePlayback` 决策流程。
3. `usesCleartextTraffic=true` 是临时的，接入 provider 后换 networkSecurityConfig。
4. AddServer 认证流接入 CredentialVault（密码按 Provider 策略加密保存/销毁，ADR-016）。
5. SAF 目录选择器（Phase 0.6：ACTION_OPEN_DOCUMENT_TREE + DocumentFile 树导航）。
6. 详情页/全局搜索/诊断页为占位（TASKS.md 有清单）；图片管线（Coil）待媒体库数据接入后引入。

## 6. 已知 Bug / 注意事项

- `LibraryViewModel.goToParent()` 与 `openFolder()` 的文件夹栈：当前实现正确；
  若 Provider 支持非树形浏览（如 Emby 无 folder 概念），此逻辑需按 ProviderCapability 分支。
- `PlaybackEngine` 进度循环 1s 间隔；服务端上报为尽力而为，失败只记日志（不打断播放）。
- 播放器音轨/字幕选择基于"每组取第一轨"（TrackMapper 简化），多轨同组场景待完善。
- 设置页"默认倍速"当前仅持久化，播放器起播未读取（V0.1 接入）。
- Room schema 导出在 `core/database/schemas`（迁移用，勿删）。

## 7. 禁止随意修改

- `core:model` 领域模型：变更需先更新 ADR 并同步全部 Provider（禁止未经说明改 Schema/模型）。
- `PlaybackCompatibilityEvaluator` 的决策语义（DIRECT_STREAM=视频不转码）。
- `Redactor` 脱敏规则：只能加强，不能放松。
- 网络分层（ApiClient/MediaHttpClient 分离）与缓存分离原则。
- 播放引擎封装（player:engine）对外 API（PlaybackEngine/PlaybackSession/PlaybackUiState）。
- Provider 能力组合：**禁止把新能力塞回 MediaProvider Fat Interface**；
  新增能力 = 独立能力接口 + ProviderHandle 字段 + Factory 装配。
- 播放请求头：禁止恢复全局 Singleton holder（ADR-018）。
- 进度上报：禁止回到"每秒写库+上报"（ADR-017）。
- ProviderHandle：禁止把未实现/占位能力塞进 Handle（ADR-022）——feature 层不得用异常发现"未实现"。
- Emby 认证：禁止密码持久化/日志；禁止 AccessToken 放 URL query；禁止各端点手拼 X-Emby-Token
  （统一 EmbyApiClient）；禁止把 localServerId 与 remoteServerId 混用（ADR-026）。
- 退出流程：禁止"先 stop 协调器再 emit Stopped"（ADR-023）；必须走 stopAndFlush。
- 连接测试：禁止仅凭 HTTP <500 或 200+可解析JSON 判定协议有效（ADR-024）。

## 8. 沙箱专用说明（仅本环境）

- 本沙箱为 Linux **aarch64**：AGP 无 arm64 版 aapt2，验证 APK 需启用 qemu 包装器：
  `gradle.properties` 中 `android.aapt2FromMavenOverride=/opt/aapt2-bin/aapt2`（默认已注释）。
  `/opt/aapt2-bin/aapt2` 是 aarch64 原生 ELF 包装器，内部 exec `qemu-x86_64-static -L /usr/x86_64-linux-gnu` 运行 x86_64 aapt2。
  **正常开发机（x86_64）不需要也不应启用该配置。**
- 构建命令（本沙箱）：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew assembleDebug`
- 单测命令：`./gradlew testDebugUnitTest`（Redactor/PlaybackErrorMapper/PlaybackCompatibilityEvaluator）。

## 9. 环境信息

- JDK 17（OpenJDK arm64）/ Gradle 8.9 / Android SDK 35（platform 35 + build-tools 35.0.0 + platform-tools）
- SDK 路径：`/opt/android-sdk`（local.properties 已配置 sdk.dir，该文件不入库）

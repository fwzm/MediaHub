# HLS 直连最小闭环（第一阶段）

## Context

当前 Windows 客户端对 Emby/Jellyfin 只走 `EnableTranscoding=false` + `static=true` 的直连/直串流；
只要服务器把所有版本都标成不可直放，播放就直接失败。HLS 是“什么都能放”的第一块拼图：
服务器给出 `m3u8` 时，由 mpv 直接播放该播放列表，凭据仍只存在于现有回环鉴权桥中，
不改证书/代理/重定向策略。本阶段只做“能播”，转码会话生命周期（PlaySessionId 上报、
停止请求、避免 FFmpeg 残留）留到第二阶段。

同时收尾上一轮遗留：末集“下一集”置灰证据、竞态确定性复现评估。

## 范围与非目标

- 做：直连/直串流优先不变；仅当没有任何可直放版本时，第二次协商取 `TranscodingUrl`（HLS），交给 mpv。
- 做：回环桥支持 m3u8 多级相对 URL 改写（主列表 → 子列表 → 分片/密钥），子请求按同源规则注入凭据。
- 不做：转码会话停止请求、`/Sessions/Playing/Stopped` 之外的清理、码率/字幕烧录协商、ForceTranscode UI。
- 不做：改动美化成果、引入第三方依赖、把凭据写入任何持久化文件。

## 一、尾巴

1. 末集“下一集”置灰：启动 Jellyfin + 应用，进入 S02E02，用 UIA 读 `player.nextepisode` 的
   `IsEnabled=False`，截图存 `C:\Users\55160\Pictures\MediaHub 截图\round3\`。
2. 竞态复现：该竞态位于 `PlayerPage.SwitchToAsync` 与结束推进的 UI 时序，现有测试程序集无法驱动
   WinUI 页面；不引入测试专用钩子的前提下没有确定性复现入口。**维持现状**，在回报中说明原因，
   保留已有的身份守卫与顺序化测试。

## 二、Provider：直连优先，HLS 兜底

文件：`windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserApi.cs`、
`windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs`

1. `GetPlaybackInfoAsync` 增加“是否允许转码”参数，保持默认 false；第一次请求体与今天完全一致。
2. `ResolvePlaybackAsync` 保持现有选择规则（DirectPlay > DirectStream）；仅当两者都不存在时：
   - 用 `EnableTranscoding=true` 再请求一次；
   - 在返回的 `MediaSources` 中取第一个带非空 `TranscodingUrl` 的源；
   - `TranscodingUrl` 允许相对地址，按 `_api.ApiOrigin` 解析为绝对地址；
   - 产出 `PlaybackMode.Transcode`、`Container = TranscodingContainer ?? 源 Container ?? "ts"`、
     `SessionId` 取第二次响应的 Root/Source `PlaySessionId`、`Streams` 正常映射、
     `Headers = BuildPlaybackHeaders(source)`（已剔除敏感头）。
3. 第二次响应仍无 `TranscodingUrl` 时，保留现有可读错误（“只提供转码流…”），不伪造 URL。
4. 先对真实 Jellyfin 12.2 实测 `EnableTranscoding=true` 的响应形状（是否直接给 `TranscodingUrl`、
   是否需要 DeviceProfile），按实测结果决定是否需要额外请求字段；实测前不猜测。

## 三、回环桥：m3u8 多级改写与鉴权

文件：`windows/src/MediaHub.Net/LoopbackMediaBridge.cs`

1. 路由：
   - `/{token}/stream` 及其它既有路径 → 仍然取配置目标（保持既有安全测试语义）。
   - `/{token}/hls/{id}` → 仅当 `id` 存在于本会话注册表时取对应上游 URL，否则 404，且不触碰上游。
2. 播放列表识别：响应 `Content-Type` 含 `mpegurl`，或最终有效 URL 以 `.m3u8` 结尾。
   - 以最终响应 URI（含重定向后的地址）为 base 解析相对地址。
3. 改写规则：
   - 非注释非空行视为 URI；
   - `#EXT-X-KEY` / `#EXT-X-MEDIA` / `#EXT-X-MAP` / `#EXT-X-I-FRAME-STREAM-INF` 等
     `URI="..."` 属性逐项改写；
   - 仅 http/https 改写；`data:` 等保持原样；解析失败保持原样；
   - 每个上游绝对 URL 生成不透明 id（SHA-256 前 32 hex），只登记在内存表，引擎看不到上游 URL 与查询串；
   - 回环 URL 形如 `http://127.0.0.1:{port}/{token}/hls/{id}`，对 `.m3u8` 保留扩展名提示；
   - 播放列表正文上限（4 MB）与条目上限（2 万）；超限记录错误并回 502，不返回半截列表。
4. 子请求鉴权：
   - 仅当子 URL 与 `_target.Url` 同源时附加 `_target.Headers`；跨源（CDN）不附加，避免凭据外泄；
   - 重定向继续由 `OriginScopedCredentialHandler` 处理，跨源自动剥离敏感头，证书策略不变；
   - `Range`/`If-Range` 照旧透传；客户端查询串不进入上游 URL。
5. 响应整形：重写后按新字节数设置 `Content-Length`，保留 `Content-Type`，继续 `no-store`，
   播放列表不宣告 `Accept-Ranges`。
6. 诊断：错误记录中脱敏 token 与上游查询串（HLS URL 常带签名/`api_key`）。

## 四、播放衔接

文件：`windows/src/MediaHub.App/Services/PlaybackCoordinator.cs`

- 转码模式下服务器已按 `StartTimeTicks` 从续播点开始出流，引擎起播位置应为 0，避免二次跳转；
  直连/直串流保持现有 `resumeTicks` 行为。
- `StartupTrace` 记录 `mode=`（DirectPlay/DirectStream/Transcode），作为验证证据。

## 五、测试与 mock

1. `windows/tests/MediaHub.TestSupport/MockMediaServer.cs`
   - `MockMediaSourceCapabilities` 增加可选 `TranscodingUrl` / `TranscodingContainer`；
   - `ServePlaybackInfo` 读取请求体的 `EnableTranscoding`，仅在允许转码时返回 `TranscodingUrl`。
2. `windows/tests/MediaHub.Tests/PlaybackSourceSelectionTests.cs`
   - 更新“仅可转码”用例：有 `TranscodingUrl` 时断言 `PlaybackMode.Transcode` + m3u8 URL + SessionId；
   - 新增“无 `TranscodingUrl` 仍报可读错误”用例；
   - 保留直连/直串流优先用例不动。
3. 新增 `windows/tests/MediaHub.Tests/LoopbackHlsBridgeTests.cs`（复用同程序集的 `BridgeTestOrigin`）：
   - 主列表 → 相对子列表 → 相对分片两级改写，逐级经回环 URL 取到正确字节；
   - 上游对缺失鉴权头回 401，经桥请求分片得 200，证明分片不 401；
   - 跨源子列表/分片不携带会话凭据；
   - `#EXT-X-KEY` 的 `URI` 被改写；
   - 未知 `/hls/{id}` 返回 404 且上游未被访问；
   - 上游 URL 的查询串不暴露在回环 URL 中。
4. 全量回归：`MediaHub.Tests` 与 `MediaHub.WebDav.Tests`。

## 六、验证与如实标注

- 构建：`dotnet build windows\MediaHub.Windows.sln -c Debug -p:Platform=x64` 0 警告 0 错误。
- 测试：两套测试全绿。
- **mock 验证**：上述单元/集成用例（Provider 协商、多级改写、鉴权、404）。
- **真实服务器验证**：
  - 先用真实 Jellyfin 12.2 测 `PlaybackInfo`（直连/转码两次调用），记录响应中
    `SupportsDirectPlay/Stream`、`TranscodingUrl` 的实际形状；
  - 若测试片源能被服务器判定为“仅转码”，在应用内播放并记录 `startup.log` 的
    `playback:resolved mode=Transcode`、mpv 位置推进、截图；
  - 若片源仍被判为可直串流（无法自然触发 HLS），则用 Jellyfin 自己产出的 `master.m3u8`
    经同一个 `LoopbackMediaBridge` 交给真实 mpv 播放，作为“真实服务器 + 真实 mpv”的 HLS 证据，
    并明确标注“未经过应用内 Provider 选择”；应用内 HLS 选择路径只算 mock 验证。
- 任何未实际跑通的项一律标注“未验证”，不写成通过。

## 七、验证结果（本轮实测，2026-11-12）

构建与测试（本机实测）：

- `dotnet build windows\MediaHub.Windows.sln`：0 警告 0 错误。
- `MediaHub.Tests`：539 通过 / 0 失败；`MediaHub.WebDav.Tests`：104 通过 / 0 失败。
- 其中 HLS 相关 10 条（`LoopbackHlsBridgeTests` 5 条 + `PlaybackSourceSelectionTests` 5 条）单跑通过。

mock 验证：

- 直连优先：服务器标记 `SupportsDirectPlay` 的片源仍返回 `DirectPlay`，不会因为引入 HLS 而改走转码。
- 仅转码片源：第二次 `PlaybackInfo` 带 HLS `DeviceProfile`，取到 `TranscodingUrl` 后返回 `Mode=Transcode`、
  `Container=ts`、`SessionId=PlaySessionId`，URL 保留服务器查询串（含 ApiKey），不进入日志。
- 多级改写与鉴权：master → 子列表 → 分片每一级都回环，分片在缺少会话头的上游会 401，经桥为 200；
  跨源分片保留自身查询串但不带会话凭据；`#EXT-X-KEY` 的 `URI` 同样改写；未知 `/hls/{id}` 404 且不触达上游。
- 上述全部分片请求的真实 HTTP 往返都在测试内完成（真实 `BridgeTestOrigin`、真实 `HttpClient`），不是字符串断言。

真实服务器验证（Jellyfin 12.2，`http://127.0.0.1:8096`，真实 mpv + 生产 `LoopbackMediaBridge`）：

- 协商：按产品同一份 HLS `DeviceProfile`（Container=ts / h264 / aac,mp3 / Protocol=hls）请求 `PlaybackInfo`，
  服务器返回形如 `/videos/{id}/master.m3u8?...&PlaySessionId=...&ApiKey=...` 的 `TranscodingUrl`。
- 改写：经桥取到的 master 只含回环 URL；子列表中的分片全部变为 `/hls/{id}.ts`，`#EXT-X-ENDLIST` 保留。
- 播放（**真实服务器 + 真实 mpv + 生产桥**）：
  - 《Long Resume (2026)》10 分钟、640×360：连续播放 30 秒，`state=Playing`、`dur=600.00`，
    桥计数 `requests=102 / bytes=57,071,061 / denied=0`（102 次请求含 master、子列表与分片）。
  - 《Example Show S02E02》8 秒、1280×720：`state=Playing`（完整播放到 `Ended`，`dur=8.02`、`bytes≈3.4MB`）。
- 边角片源失败已定位（第二轮复核）：《Example Show S02E01》16 秒、单一关键帧。初始表现是分片请求拿到
  `206` 与 `Content-Length=6,860,496`，随后 0 字节、mpv 报 `Error when loading first segment`；事后用 curl
  直取该分片 0.03 秒拿到完整 6.86MB。本轮用同一 `PlaySessionId` 复测，确认不是“长度未知/不分段/Range”问题：
  - mpv 日志：t=0.54s 打开分片 0（206，`Content-Length=6,860,496`），t=0.835s 立刻又打开分片 1；之后 19.2s
    没有任何字节到达，t=20.075s 报 `Error when loading first segment`；桥计数 `bytes=853`（只有播放列表）。
  - 同一会话的两个分片用 curl **同时**请求：分别返回 6,860,496 / 6,860,308 字节，均 <0.5s 完成。说明服务端
    并不拒绝并发，也不要求 Range 从头；每个分片响应实际都包含整段 remux 输出（该片源只有一个关键帧，
    无法按时长切段）。
  - 加上 `--demuxer-lavf-o=http_multiple=0` 后，同一片源经生产桥播放 `state=Playing`、位置持续推进、字节正常。
  - 判定：ffmpeg HLS 解复用器默认 `http_multiple=auto`，会同时预取两条分片连接；第一条先拿到响应头，
    第二条等待同一个按需 remux 编码器，客户端又不继续读第一条的响应体，双方互等到 20 秒超时。属于
    “并发预取 + 服务端按需 remux”的交互问题，服务端行为本身没有可配置项可改。
  - 客户端规避（已落地）：`PlayerHostService` 给引擎固定加 `demuxer-lavf-o=http_multiple=0`（非 HLS 解复用器
    忽略该选项），代码注释写明上述判定依据。
- **未验证**：应用内 Provider 的 HLS 分支对真实服务器的端到端播放。原因与证据见第九节。

## 九、第二阶段：转码会话生命周期（已落地）

代码位置：`PlaybackCoordinator`（起播/停止上报、位置换算）、`PlayerPage`（异常路径释放）。

- 起流上报：`ResolveAsync` 成功后向 `/Sessions/Playing` 报告，`PlaySessionId` 来自 `PlaybackInfo` 根节点，
  已在第一阶段验证。
- 停止上报：`ReleaseCurrentAsync` 的条件是 `StartReported || Source.Mode == Transcode`，即直连/直串沿用原有
  “播过才报停”，转码额外保证“服务器已经开始出流就必须报停”，覆盖 `user-exited` / `replaced` /
  `superseded` / `completed` / `shutdown` / `failed` 六种原因。
- 起播位置（本轮修正）：实测 Jellyfin 12.2 的 HLS 转码**不按** `StartTimeTicks` 偏移起流——`PlaybackInfo`
  返回的 `TranscodingUrl` 不含该参数，master 列表从 segment 0 开始（`runtimeTicks=0`），只有客户端请求
  segment N 时服务端 ffmpeg 才以 `-ss N*6s` 起编码。因此 `ServerStartOffsetTicks` 的假设不成立，已删除；
  引擎在三种模式下都由自己 seek 到续播点，进度上报直接使用引擎位置/时长，不做时间轴加回。
  真实服务器复测：切换转码后服务端上报 `PositionTicks=248,479,666`（≈4:08）与客户端 4:01 同量级，无叠加。
- 异常路径：`PlayerPage.HandlePlaybackFailure` 的三个“原地停留”分支（关闭自动连播、连续失败达上限、
  队列无下一项）都补发 `StopCurrentAsync(failed)`，避免引擎报错后会话悬挂。

真实服务器证据（Jellyfin 活动日志 `/System/ActivityLog/Entries`，同一台 `MediaHub Windows` 客户端）：

| 路径 | 触发方式 | 服务端记录 |
|---|---|---|
| shutdown | 关闭主窗口（WM_CLOSE） | 10:22:45 `has finished playing Transcode Only (2024)` |
| completed | 16 秒剧集自然播完自动连播 | 10:27:03 `has finished playing ... S02E01` |
| replaced | 播放 4 秒后点“上一项” | 10:31:14.234 `stopped ... S02E01`，10:31:18.060 `playing ... S01E03` |
| replaced（位置 0） | 起播 1 秒内切下一集 | 只有下一条 `playing`，无 stop 活动：位置为 0 时服务端不记 stop 活动，属于服务端日志行为 |

服务端 FFmpeg 生命周期（10 分钟转码片源，探针直接走服务器接口）：

- 请求完整分片后 `ffmpeg=1`；`POST /Sessions/Playing/Stopped` 后约 3.1 秒 `ffmpeg=0`。
- `DELETE /Videos/ActiveEncodings?deviceId=&playSessionId=` 返回 204（端点存在），但不调用它也已被 Stopped 终止；
  ffmpeg 命令行不含 PlaySessionId，无法直接按会话归因，所以以“Stopped 后进程消失”作为证据。
- 本轮 HLS 探针结束后再次检查 `ffmpeg=0`，无残留会话。

结论：停止报告（`/Sessions/Playing/Stopped`）足以终止服务端编码器；应用的四条真实路径（shutdown、replaced、
completed、user-exited）都会发出停止报告。`failed` 与其余路径共用 `ReleaseCurrentAsync`，未单独构造真实引擎崩溃。

## 十、真实服务器 HLS 端到端：阻塞点与证据

目标：让应用内 Provider 真正走 HLS 分支（`mode=Transcode`）。结论：**在现有真实服务器上无法自然触发**，
不是 mock 能替代的验证，以下是判定依据。

证据一：应用的首轮 `PlaybackInfo` 不带 `DeviceProfile`，真实服务器一律回答“可直放”。

| 片源（本地库） | 容器/编码 | SupportsDirectPlay | SupportsDirectStream |
|---|---|---|---|
| Mpeg2 Only（30s） | mpeg2video + mp2 | True | True |
| Vp9 Only（30s） | VP9 + Opus（webm） | True | True |
| Avi Only（20s） | Xvid + MP3 | True | True |
| Wmv Only（20s） | WMV2 + WMA | True | True |
| Theora Only（20s） | Theora + Vorbis | True | True |
| Broken Clip（损坏文件） | —（无可解析流） | True | True |
| Remote Strm（远程 .strm） | mpeg2 + mp2 | True | True |
| Transcode Only（10min） | mpeg2video + mp2 | True | True |

证据二：标志由**客户端画像**决定，而不是片源本身。同一条 mp4（h264/aac）在三种请求下：

- 不带 `DeviceProfile`：`directPlay=True`
- 空 `DeviceProfile`（`DirectPlayProfiles=[]`）：`directPlay=False`
- 带 `DirectPlayProfiles=[{Type=Video, Container=mp4,mkv, Codec=h264, Audio=aac}]`：仍为 `False`
  （Jellyfin 的匹配细节未继续深挖，但足以证明标志来自画像评估，而不是“文件能不能放”）

证据三：服务端“强制转码”策略在无画像时不改变标志：

- 用户策略 `RemoteClientBitrateLimit=300000`（300kbps，源 1.3Mbps），回环地址访问：仍 `directPlay=True`。
- 用户策略 `ForceRemoteSourceTranscoding=true` + 远程 `.strm`：仍 `directPlay=True`。

证据四：真实 Emby 4.10.1.0（远程 HTTPS 服务器）同样如此：随机 8 个电影首轮 `directPlay/directStream` 全为 True。

结论：`MediaBrowserProvider.ResolvePlaybackAsync` 只在服务器自己把两个标志都判为 false 时才请求 HLS；真实
Jellyfin/Emby 在不带画像时不会这样做。要触发 HLS 只能：

1. 首轮就发送一份**更窄**的直放画像（例如只声明 h264/hevc/vp9），这会让引擎本来能直放的 mpeg2/AVI/WMV/Theora
   改走转码，违反“直连优先不回退”，未采用；
2. 增加“强制转码”开关（`PlaybackOptions.ForceTranscode`），作为显式选择加入，默认关闭。该开关已按此方案落地，
   见第十一节；默认路径仍不带画像、不请求转码。

因此本轮没有伪造应用内 HLS 证据；应用内分支仍只有 mock 覆盖，真实链路覆盖到“真服务器协商 → 生产桥 → 真 mpv
（含这轮 `http_multiple=0` 的 16 秒片源播放）”。


上一轮两个尾巴：

- 末集“下一集”置灰：截图 `C:\Users\55160\Pictures\MediaHub 截图\round3\last-episode-next-disabled.png`；
  UIA 复核 `player.nextepisode IsEnabled=False`、`player.queuenext IsEnabled=False`、`player.prevepisode IsEnabled=True`。
- 跨季上一集竞态复现：现有测试程序集无法驱动 WinUI 页面时序，不引入测试专用钩子就没有确定性入口，
  按约定维持现状，只用既有排序/边界用例保证两侧规则一致。


## 十一、强制转码开关（已落地并跑通真实服务器端到端）

第十节说明“不带画像时真实服务器一律判可直放”。为了能在真实服务器上走到 HLS 分支，同时不改变默认路径，
新增了一个**播放器侧、按条目生效、不持久化**的强制转码开关。

交互与状态：

- 播放器顶栏按钮 `player.transcode`（工具提示“强制转码（HLS，本次播放有效）”），仅服务端条目显示，本地文件不显示。
- 顶栏徽章 `player.transcodebadge`（文本“转码”）只在**已解析的source 是 `PlaybackMode.Transcode`** 时出现，
  与按钮状态解耦：按钮是“请求”，徽章是“事实”。服务器拒绝或自行转码时两者可能不一致，以徽章为准。
- 不持久化，也不进入 `SettingsStore`：开关只在当前播放页、当前条目上有效，换条目即回到默认关闭。这样默认路径
  没有任何行为变化，也不必为诊断性开关增加配置项和迁移。

数据流：

1. `PlaybackRequest.ForceTranscode`（默认 false）→ `PlaybackCoordinator` 透传到 `PlaybackOptions.ForceTranscode`。
2. `MediaBrowserProvider.ResolvePlaybackAsync` 在 `ForceTranscode` 为真时**跳过直连探测**，首轮就带 HLS
   `DeviceProfile` + `EnableTranscoding=true` 调用 `PlaybackInfo`，直接取 `TranscodingUrl`。
3. 服务器仍未给 HLS 时抛可读错误“已开启强制转码，但服务器没有返回 HLS 转码流（服务器可能拒绝转码该片源），
   已停止播放”，**不会**回退成直连静默播放。
4. 切换模式视为一次替换：先按 `replaced` 停止旧会话（含停止上报），再以当前引擎位置重新起流，
   避免服务端残留或进度漂移。位置由引擎自己 seek 到（见第九节的 StartTimeTicks 修正）。

测试：`PlaybackSourceSelectionTests` 新增 3 条（Emby/Jellyfin 两种服务器“强制转码忽略可直放源”+“服务器拒绝时
报错而不是直连”），`MediaHub.Tests` 542 通过 / 0 失败，`MediaHub.WebDav.Tests` 104 通过 / 0 失败。

真实服务器端到端（Jellyfin 12.2，`http://127.0.0.1:8096`，《Long Resume (2026)》10 分钟）：

| 证据 | 值 |
|---|---|
| 客户端协商 | `startup.log` 18:46:48.930 `playback:resolved .../master.m3u8?...&TranscodeReasons=DirectPlayError mode=Transcode` |
| 服务端会话 | `GET /Sessions` 中该用户 `PlayMethod=Transcode`，`NowPlayingItem=Long Resume (2026)` |
| 服务端编码 | 工作目录出现 `FFmpeg.Remux-2026-11-12_18-46-52_b22e31da..._*.log`（3 个），即服务端真的起了 ffmpeg |
| 客户端画面 | 播放位置从 `0:02` 推进到 `1:06`；截图 `C:\Users\55160\Pictures\MediaHub 截图\round4\force-transcode.png`（含“转码”徽章） |
| 桥计数 | 18:48:10.956 `转码会话结束（user-exited）：桥请求 102 次 / 57071061 字节`（播放列表 + 分片全部经回环桥） |
| 停止上报 | 服务端活动日志 10:48:10 `VideoPlaybackStopped`；随后 `/Sessions` 无正在播放项、`ffmpeg` 进程 0 |
| 默认路径回归 | 同一条目再次从详情页起播：18:48:26 `mode=DirectPlay`（`static=true`），开关 `Off`、徽章不存在 |
| 关闭窗口 | 10:48:55 `VideoPlaybackStopped`（direct 会话，位置 >0，符合既有停止条件） |

说明：本轮 ffmpeg 是 remux 输出（`TranscodeReasons=DirectPlayError`、`RequireAvc=false`、码率上限未压低），
10 分钟片源会很快把全部分片写到服务端缓存后退出，所以“`ffmpeg` 进程数为 0”出现在播放仍在继续时，不是残留判断。
残留判断以“活动日志出现 `VideoPlaybackStopped` 且 `/Sessions` 无 NowPlayingItem”为准。

开关按约定保持默认关闭：代码中无持久化字段，新起播的请求一律 `ForceTranscode=false`，真实服务器复测已确认。

### 位置保持与默认关闭的复测（同一天 19:03–19:05，清空进程后重启应用）

| 证据 | 值 |
|---|---|
| 切换前 | UIA `PositionText=8:09`、`DurationText=10:00`、徽章不存在（直连） |
| 切换后 6 秒 | `8:15`、徽章存在（`PlaybackMode.Transcode`），未回到 0 |
| 再过 10 秒 | `8:26`、徽章仍在，位置继续推进 |
| 服务端会话 | `GET /Sessions`：`PlayMethod=Transcode`、`positionTicks=5,352,296,666`（8:55，与客户端一致） |
| 服务端编码 | ffmpeg `-ss 00:08:06.500 ... -start_number 81 -hls_time 6`，即“请求 segment 81 才起编码”的实证 |
| 桥计数 | 19:05:00.364 `转码会话结束（user-exited）：桥请求 22 次 / 10957481 字节` |
| 停止与清理 | 19:04:58 服务端 `Deleting partial stream file(s) ...3898e92f....m3u8`；19:05:00 `VideoPlaybackStopped`；`ffmpeg=0` |
| 默认恢复 | 19:05:34.681 从详情页再次起播：`static=true`（DirectPlay）、徽章不存在、`player.transcode` 的 `ToggleState=Off` |
| 截图 | `C:\Users\55160\Pictures\MediaHub 截图\round4\force-transcode-position.png` |


## 约束

0 警告 0 错误、既有测试全绿、不新增第三方依赖、凭据不入库、不动美化成果。

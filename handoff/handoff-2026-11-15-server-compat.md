# handoff — 服务器兼容与登录批次（2026-11-15）

## 1. 状态

- 分支：`fix/server-compat-batch-20261115`（worktree `D:\deepseek_test\mh-server-compat-20261115`）
- base：`origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`
- head：`22e9fd1a`（代码三批）＋ 文档 commit（本文件/RUNLOG/README/DECISIONS）
- 三批 commit：`9087e43e`（服务器识别与连接）、`1759bae5`（进度与时长）、`22e9fd1a`（图片字幕与兜底）
- G7：`DO_NOT_MERGE`。未 merge / close / mark-ready，未写 main，未 force push。

## 2. 目标与落地

用户条目 → 审计表条目：

| 用户 | 审计 | 内容 | 落地 |
|---|---|---|---|
| 10 | 75 | 添加服务器自动识别协议/端口 | `ServerAddressDetector`（core:common，纯逻辑）+ `ServerAddressProber`（HttpURLConnection，2s）+ AddServer 页「自动识别」；候选 https/http × 8096/8920/443/80，上限 6，失败列出真实尝试 |
| 11 | 83 | 文件源错误信息准确 | `provider:api` 的 `ProviderErrorText` 按 ErrorCode/HTTP 映射；403 明确「不是密码错误」；接入媒体库错误态与源卡片 |
| 22 | 149 | 图片鉴权 | Emby 反代子路径额外登记 baseUrl scope（仅 path 非空）；裸 origin 不扩散，跨源/同前缀仍隔离 |
| 38 | 10 | fnOS 继续观看 | Resume/UserData 读取；Resume 404/405 → 空列表；退出前补发最终 Progress；缺 PlaySessionId → `mh-<uuid>` 兜底（ADR-040 补充） |
| 79 | 11 | fnOS 总时长 | `EmbyDuration` 标准/毫秒/秒三尺度归一；详情回退 MediaSources，播放时长回退 item |
| 88 | 22 | 部分 Emby 无法播放 | `ERROR_CODE_IO_BAD_HTTP_STATUS` → 403/404/429/5xx 结构化错误（此前 UNKNOWN）；403/404 不降级 mpv（同凭据/网络栈） |
| 89 | 79 | 首页闪退 | 重复/缺失 id 去重并转可见错误态（媒体库/首页续播） |
| 107 | 81 | 外挂字幕 | `S_TEXT/*`、SUBRIP 别名；`IsExternal=false` 但有 DeliveryUrl 也产轨；地址仍用官方端点，DeliveryUrl 不使用 |
| 149 | 62 | 数据访问失败 | SAF 访问统一捕获 Security/IllegalState/IllegalArgument/FileNotFound → DirectoryAccessRequired；不吞 CancellationException/ProviderException |

## 3. 真实门禁（`--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`）

- 批次 1：provider:api 36、core:common 692、feature:server 71、feature:library 71、core:network 233、player:engine 198 → exit 0，0 失败。
- 批次 2：provider:emby 244 → exit 0，0 失败。
- 批次 3：app 35、provider:emby 249、provider:jellyfin 124、provider:local 32、feature:home 17、feature:library 75、player:engine 199 → exit 0，0 失败。

## 4. 证据边界 / NOT_RUN

- 所有服务端行为只用 MockWebServer / 假实现验证，**未访问任何真实服务器**。
- NOT_RUN：真实 fnOS 的 Resume 落库与 Stopped 位置行为；真实反代子路径图片 401；fnOS 真机跨设备续播；外挂字幕在真实服务器上的加载；厂商 SAF 撤权设备；缺 PSID 兼容服的接受性；`:app:assembleDebug` / `lintDebug` 未跑（本批次只跑单测门禁）；CI 未出结果（`CI_PENDING`）。
- 旧断言变更（唯一一处，已标注）：`EmbyPlaybackProviderTest.missing play session id throws parse` → `...falls back to a client session id`，因需求反转（ADR-040 补充）。
- 与并行分支 `fix/av-gesture-device-batch-20261115`（PR #125）都改了 `provider/local`，接入时注意 SAF 相关冲突，取其并集。

## 5. 下一步

1. 需真实 fnOS / 反代 / 厂商 SAF 样本复核 NOT_RUN 项（先确认服务器与凭据由用户提供，禁止探测真实账号）。
2. CI：观察本分支 Draft PR 的 exact-head 结果；红则按日志修，绿也不得当作真机背书。
3. 合入需用户显式授权（G7 恒为 DO_NOT_MERGE）。

# 任务 0：PR #10 / #16 审查来源与后续复核队列（2026-09-09）

本文件把本轮已分页取得的审查意见逐条登记，供任务 2B、任务 3 和任务 5 接续。当前实施切片限于 PR #18 本地备份还原；本文件没有批准 PR #10 / #16，没有修改其生产实现，也没有重新执行其设备验收。`resolved`、`outdated` 和 review 状态只是来源元数据，不能决定缺陷是否仍存在。除下面明确列出的文档及原始日志事实外，现状统一为 **仅登记，等待后续独立复核**。

## 1. 本轮取得的基线与证据身份

| 项目 | PR #10 | PR #16 |
|---|---|---|
| 实际 head | `3febdf8b77a6a1d63d54b9d0c409de708ed978bf` | `f40363ce5199e05a4cd197b456a851c2b984492b` |
| GitHub API base SHA / ref | `0e08fadbb398d0d35ab960f0e73ad8ee6ae2bba7` / `main` | `0e08fadbb398d0d35ab960f0e73ad8ee6ae2bba7` / `main` |
| 抓取时状态 | OPEN，未合并 | OPEN，未合并 |
| 已查询的 CI run | [33971603657](https://github.com/fwzm/MediaHub/actions/runs/33971603657)：build、API 32、API 36 均 success | [33967890069](https://github.com/fwzm/MediaHub/actions/runs/33967890069)：build success |
| check-run head SHA | 与该 PR 实际 head 相同 | 与该 PR 实际 head 相同 |
| 从 job 日志核实的实际 checkout | 三个 job 均 checkout literal head `3febdf8b77a6a1d63d54b9d0c409de708ed978bf` | synthetic PR merge `b3800d2bb1dab2297700a56c0542e1944798dbbf`，合入 API base `0e08fad...` |

本轮 `git fetch origin` 后的 `origin/main` 是 `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`，与以上两个 PR 返回的 API base SHA 不同。后续集成需重新核对 main、head、CI check SHA 和实际 checkout；此表不能证明两分支已集成最新 main。PR #10 正文中的 `f81b5dc`、run `33381123711`，以及作者评论引用的更早提交和 run，均是历史证据。CI success 也不自动关闭下面任何一条意见，更不能替代正式入口、真实采样及设备验收。

原始材料位于本轮本机证据目录 `D:\deepseek_test\pr18-agent-b-evidence`，没有在此文档复制可能含隐私的原文：

- `task0-baseline-review.md`、`ci-identity-summary.json`：基线及 CI checkout 身份。
- `pr10-review-comments-full.md`、`pr16-review-comments-full.md`：评论全文索引。
- 对每个 PR：`prN-detail.json`、`prN-commits-pages.json`、`prN-reviews-pages.json`、`prN-comments-pages.json`、`prN-issue-comments-pages.json`、`prN-threads-pages.json`、`prN-check-runs-pages.json`、`prN-runs-pages.json`，以及对应 job / run 日志。

### 分页与去重边界

REST 列表使用 `gh api --paginate --slurp`，下面每个资源实际返回 1 页；GraphQL `reviewThreads(first:100)` 的外层分页已到 `hasNextPage=false`，每个 thread 的 `comments(first:100)` 也逐一核对为 `hasNextPage=false`。REST inline IDs 与 GraphQL thread 内 `databaseId` 集合完全一致；两个 PR 均没有额外 `in_reply_to_id` 回复。已检查回复连接，不能把“无额外回复”误读为未抓取回复。

| PR | REST inline 页 / 条 | REST reviews 页 / 条 | REST issue comments 页 / 条 | GraphQL 外层页 / threads / 内部 comments | 内部待续页 | 独立 API 来源条数 |
|---|---|---|---|---|---|---|
| #10 | 1 / 8 | 1 / 3 | 1 / 1 | 1 / 8 / 8 | 0 | 12 |
| #16 | 1 / 7 | 1 / 2 | 1 / 1 | 1 / 7 / 7 | 0 | 10 |
| 合计 | 2 / 15 | 2 / 5 | 2 / 2 | 2 / 15 / 15 | 0 | 22 |

PR #16 review `5121011716` 的 1 条 suppressed comment 单列为子项，它没有独立 API 评论 ID，不加到 22 条来源中。PR #10 作者评论包含两个主题，也保留在同一个来源 ID 下。

## 2. PR #10：视觉系统后续独立复核

下列定位已对 **本节实际 head** 执行只读 `git show <head>:<path>`，确认文件存在。定位符只是减少表格重复；确认文件存在及符号定位不等于确认原问题仍存在或已经修复。旧评论行号不当作当前行号。

| 定位符 | 实际 head 中的路径 / 关注位置 |
|---|---|
| V | `core/ui/src/main/kotlin/com/mediahub/core/ui/effects/VisualPalette.kt`；`extract`、`packRgb`、`scaleRgb`、`mixTowards` |
| G | `core/ui/src/main/kotlin/com/mediahub/core/ui/effects/FlowGlowShader.kt`；AGSL、`apply`、`uTreble` |
| A | `core/ui/src/main/kotlin/com/mediahub/core/ui/effects/ArtworkPalette.kt`；`fromBitmap`、`sampleDimensions` |
| F | `core/ui/src/main/kotlin/com/mediahub/core/ui/effects/FlowGlowSurface.kt`；`FlowGlowClock`、`FlowGlowSurface`、`SpectrumBars` |
| D | `app/src/debug/kotlin/com/mediahub/app/debug/EffectsDemoActivity.kt` |
| R | `app/src/debug/res/values/strings.xml`、`app/src/debug/res/values-en/strings.xml`、`app/src/main/res/values/strings.xml` |
| P | `feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerVisualEffectsEntry.kt`；正式播放器视觉入口定位点 |

本表每项不在备份 slice 修改的共同理由是：它属于任务 3 的视觉实现、集成或产品验收，已有 Agent A 负责的 PR #10；需要从实际 head 和更新后的集成基线独立复核，不能混入 PR #18，也不能通过覆盖文件丢失已有修改。

| 来源 / 评论 ID | 原问题或原始主张 | 当前定位 | 现状判断 | 本轮不修理由 | 后续回归证据 |
|---|---|---|---|---|---|
| [inline 3887879282](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879282) | `packRgb` 产生零 alpha，缩放/混色继承透明值，破坏不透明调色板约定，影响 fallback 和 shader 背景。 | V；F 的 fallback 消费端 | 仅登记；需对当前颜色生成与消费链逐一核验。 | 任务 3 视觉契约。 | 待补：所有生成颜色 alpha 反向断言、实际 API 32 fallback 与 API 36 渲染验证。 |
| [inline 3887879284](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879284) | `uTreble` 上传后未参与最终输出，只改变 treble 的输入帧没有相应视觉差异。 | G 的 uniform 与最终 shader 输出 | 仅登记；当前存在该 uniform 不足以判定其效果已验收。 | 任务 3 音频响应。 | 待补：受控 treble 差异对最终输出的断言；正式受支持音频会话的采样证据；拒绝权限等降级路径。 |
| [inline 3887879286](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879286) | 极端宽高比 bitmap 缩小时某维取整为 0，导致创建 bitmap 失败。 | A 的采样尺寸计算 | 仅登记；不依据 outdated 状态认定修复。 | 任务 3 海报取色。 | 待补或重跑：`1×128`、`128×1` 等边界，两个维度始终至少为 1，提取不抛异常。 |
| [inline 3887879290](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879290) | smoother 使用 `1/fps`，没有使用实际 clock 间隔；120 fps 设置运行于 60 Hz 或掉帧时，attack/release 时间偏离约定；意见也涉及 `SpectrumBars`。 | F 的时钟、频谱处理及 `SpectrumBars` | 仅登记；当前时间处理需独立读取并执行受控测试。 | 任务 3 时间与采样生命周期。 | 待补：60/120 Hz、丢帧/延迟的受控时间断言，attack/release 按真实经过时间收敛。 |
| [inline 3887879299](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879299) | API 26–32 fallback 忽略 opacity，配置为 0 仍绘制完整渐变，与 RuntimeShader 路径不一致。 | F 的 fallback 绘制 | 仅登记；当前 alpha 链路与实际像素待核验。 | 任务 3 fallback 一致性。 | 待补：opacity 为 0/中间值/1 的 API 32 与 API 36 对照，包含零效果的反向断言。 |
| [inline 3887879302](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879302) | 不需要缩放的 `Bitmap.Config.HARDWARE` 直接 `getPixels` 会抛异常，应转成可读软件 bitmap。 | A 的原图/采样图读取及释放 | 仅登记；当前硬件 bitmap 路径待独立验证。 | 任务 3 海报读取。 | 待补：真实 Android 硬件 bitmap 的小图输入、不崩溃且不错误回收调用方 bitmap。 |
| [inline 3887879304](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879304) | 每次重置 `lastNanos` 丢失不足一帧的余量，60 Hz 下 24 fps 可能变为 20，40/50 可能变为 30。 | F 的 `FlowGlowClock` 节流 | 仅登记；需检验当前累计余量/截止时间算法。 | 任务 3 性能偏好。 | 待补：可控 vsync 序列下完整时间窗计数与停止无后续帧断言；不能由单张截图推断 FPS。 |
| [inline 3887879308](https://github.com/fwzm/MediaHub/pull/10#discussion_r3887879308) | 取色忽略透明度，透明 padding 的 RGB 可能主导调色板；全透明图需要明确 fallback。 | V 的像素候选与计分 | 仅登记；当前 alpha 筛选/权重与 fallback 待复核。 | 任务 3 取色契约。 | 待补：透明 padding、半透明像素、全透明图；透明背景不得压倒可见前景的反向断言。 |
| [review 5059361431](https://github.com/fwzm/MediaHub/pull/10#pullrequestreview-5059361431) | Copilot 因发起者配额耗尽，明确表示无法完成 review。 | GitHub review 元数据；对应历史提交 | 仅登记为审查未执行；没有独立缺陷文本，不能记为审查通过。 | 任务 3 审查证据维护。 | 待补：最终 head 的实际独立 review；无可归属本条的测试通过结果。 |
| [review 5059373565](https://github.com/fwzm/MediaHub/pull/10#pullrequestreview-5059373565) | Codex 自动 review 汇总，标注 reviewed commit `3644de76c8`；未增加表内 inline 之外的独立问题。 | GitHub review；上列 8 条 inline 的来源上下文 | 仅登记历史 review，不据此裁定实际 head。 | 任务 3 审查证据维护。 | 待补：逐条当前实现与回归映射、最终 head 的独立复审。 |
| [review 5064371484](https://github.com/fwzm/MediaHub/pull/10#pullrequestreview-5064371484) | Copilot 再次因配额耗尽无法 review。 | GitHub review 元数据；对应历史提交 | 仅登记为未执行；不能作为第二次独立通过。 | 任务 3 审查证据维护。 | 待补：实际执行的独立复审；该状态消息本身没有测试证据。 |
| [issue comment 5465439507](https://github.com/fwzm/MediaHub/pull/10#issuecomment-5465439507) | 作者声称历史提交 `b208778` / `51fdc4f` / `a9f69ad` 完成色散、亮度、热点等视觉调整，并引用设备画面与 run `33280316042`；另声称 demo 改为资源本地化、英文资源补齐、`app_name` 不翻译及 lint 问题已处理。 | G、F、D、R；正式产品接续检查从 P 开始 | 仅登记两个主张；作者自述、历史 demo 与旧 run 不能替代当前正式产品验收。 | 任务 3 产品与本地化验收；不新增重复功能 PR。 | 待补：实际 head 的正式入口与共享持久化、Off/Aurora/Liquid/Spectrum、局部主题及字幕安全区；资源/语言/lint 验证；性能和真实采样另取证。 |

PR #10 后续还必须复核与最新 main 的差异，保留已有 sessionId、finality 和 mpv 生命周期修复。活动字幕选轨依赖任务 2A 独立修复后再做集成验收。没有设备独占条件时仅继续隔离模拟器与自动化，不操作共享手机或用户私密媒体。以上是接续约束，不是本轮已执行结果。

### PR #10 thread 与评论 ID 对照

| GraphQL thread ID | 已覆盖的 comment databaseId |
|---|---|
| `PRRT_kwDOT1y4586ddMqY` | `3887879282` |
| `PRRT_kwDOT1y4586ddMqa` | `3887879284` |
| `PRRT_kwDOT1y4586ddMqb` | `3887879286` |
| `PRRT_kwDOT1y4586ddMqe` | `3887879290` |
| `PRRT_kwDOT1y4586ddMql` | `3887879299` |
| `PRRT_kwDOT1y4586ddMqo` | `3887879302` |
| `PRRT_kwDOT1y4586ddMqq` | `3887879304` |
| `PRRT_kwDOT1y4586ddMqt` | `3887879308` |

## 3. PR #16：证据归档与 1H 风险接续

本节所有 inline 均指向 `docs/device-evidence/1h-emby-progress-verification.md`。已对实际 head `f40363ce5199e05a4cd197b456a851c2b984492b` 执行 `git show`，文件存在，共 164 行。原评论主要针对 `128353de0414e0f17edbe3e6b3299389eca4e443`；下表用当前章节定位，旧行号不冒充当前行号。

| 来源 / 评论 ID | 原问题或原始主张 | 当前定位 | 现状判断 | 本轮不修理由 | 后续回归证据 |
|---|---|---|---|---|---|
| [inline 3940426889](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940426889) | 脱敏声明下仍在别名旁保留真实账号名称。 | 归档开头身份说明、§2 服务器信息 | 仅登记后续复核；本轮文档核验确认实际 head 已移除原真实账号名称；不能因此认定全部隐私问题已关闭。 | 任务 5 证据文档维护；保留已完成更正，不混入备份实现。 | 待补：发布前全档及新增摘录的脱敏复核；本条已有 head 文本比对事实，非设备验收。 |
| [inline 3940426902](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940426902) | 表格保留完整服务器 base URL，暴露基础设施主机名。 | §2 服务器表格 | 仅登记后续处置；本轮确认实际 head 仍有未脱敏主机名，原问题未满足。 | 任务 5 在 PR #16 做定点脱敏；本轮新文档不复制这些地址。 | 待补：替换为稳定代号后全文搜索、路径/时间/协议诊断信息仍可追溯的检查。 |
| [inline 3940426913](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940426913) | wire 摘录保留完整 hostname，应保留请求路径和时间、隐藏主机名。 | §4 cold-final 原始摘录 | 仅登记后续处置；本轮确认实际 head 摘录仍含未脱敏主机名。 | 任务 5 归档隐私；不改动原始受限材料来伪造历史。 | 待补：所有发布摘录的主机名脱敏检查，核实请求路径、时序未被改写。 |
| [inline 3940438639](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940438639) | 原 review 认为慢 Playing 与最终上报预算冲突，导致缺失 Stopped；成功重试不能关闭失败场景。 | §5 SLOW-FINAL COMPLETION；后续任务 2B 追踪 stop/flushFinal/网络/取消/release | 仅登记生产复核；已观察的慢样本与无 Stopped 可以确认，当前档案也保留 OPEN；精确取消来源及生产因果仍待可控复现。 | 任务 2B 须独立 repair slice；PR #16 保持证据归档。 | 待补：barrier/deferred 慢响应、停止和 release 时序、底层请求真正取消、迟到结果与本地最终进度反向断言。不能只增 timeout/sleep。 |
| [inline 3940438640](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940438640) | 部分 ticks 除以 10,000,000 后的秒数被写大 10 倍。 | §3.3 ticks 表，当前第 66–76 行 | 仅登记后续复核；本轮已确认当前表的 `8.050`、`18.060`、`98.223`、`99.683` 等换算已更正，保留该更正。 | 任务 5 文档事实更正已存在，无需重复改写；不构成新的生产修复。 | 已有：读取实际 head 并对原始 `diag_wire.txt` 逐项换算；后续修改时重核表格与原始值。 |
| [inline 3940438641](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940438641) | 真实账号与精确服务器 origin 仍可关联，不能称为已完整脱敏。 | 开头身份说明、§2、§4 | 仅登记后续处置；账号名称已移除，主机名未全部脱敏；与上述隐私意见有重叠但保留独立来源 ID。 | 任务 5 归档维护。 | 待补：账号及地址两个维度同时复查；不能只用账号删除作为整条关闭证据。 |
| [inline 3940438643](https://github.com/fwzm/MediaHub/pull/16#discussion_r3940438643) | 非零续播后在尚未推进时退出，归档称服务端续播变为零；原 review 提出冷退出可能损失续播位置。 | §4 cold-final #1 与 §6 短播放语义对照 | 仅登记生产风险线索；归档中的查询结论与原因解释必须分开，六份日志缺少完整 UserData 查询响应，不能升级为已证明的客户端数据丢失根因。 | 任务 2B / 后续协议验收；需取得可重复证据再决定生产补丁。 | 待补：服务端 before/after UserData 原始响应、引擎实际位置、独立退出时间、同会话请求时序；使用任务专用媒体。 |
| [review 5121011716](https://github.com/fwzm/MediaHub/pull/16#pullrequestreview-5121011716) | Copilot 汇总建议修改脱敏问题；其摘要曾把归档描述为 closeout artifact。 | GitHub review；归档开头状态及 §7 汇总 | 仅登记历史 review；实际档案为证据归档，`SLOW-FINAL COMPLETION` 仍 OPEN，不能沿用摘要当作封板结论。 | 任务 5 文档事实和隐私复核。 | 待补：针对最终 head 的文档独立复审；汇总不增加新的设备证据。 |
| review `5121011716` / suppressed-1 | review 正文内另有 1 条隐藏意见：旧第 81 行 wire 摘录应隐藏完整 hostname。 | §4 cold-final #2 原始摘录；与 `3940426913` 同类 | 仅登记后续处置；该意见没有独立 API ID，不能因未出现在 inline 列表而遗漏。 | 任务 5 发布摘录脱敏。 | 待补：与全部 wire 摘录一起核验；原路径与时间保留、hostname 不再出现。 |
| [review 5121024833](https://github.com/fwzm/MediaHub/pull/16#pullrequestreview-5121024833) | Codex 自动 review 汇总，标注 reviewed commit `128353de04`；没有额外独立问题文本。 | GitHub review；上列 Codex inline | 仅登记历史审查入口，不当成对 f403 head 的批准。 | 任务 2B / 5 接续证据维护。 | 待补：逐条当前状态与最终独立 review；本条无独立执行测试。 |
| [issue comment 5551448887](https://github.com/fwzm/MediaHub/pull/16#issuecomment-5551448887) | 自动化消息记录 review 已完成，指向历史提交 `128353d`。 | GitHub issue comment，时间 `2026-09-05T11:27:44.131540Z` | 仅登记活动记录；“review completed”不表示审查通过，也不证明当前 head。 | 任务 5 审查来源维护。 | 待补：实际问题处置和最终独立复审；该状态消息不提供回归通过证据。 |

### PR #16 thread 与评论 ID 对照

| GraphQL thread ID | 已覆盖的 comment databaseId |
|---|---|
| `PRRT_kwDOT1y4586fiuVi` | `3940426889` |
| `PRRT_kwDOT1y4586fiuVp` | `3940426902` |
| `PRRT_kwDOT1y4586fiuVy` | `3940426913` |
| `PRRT_kwDOT1y4586fiwQT` | `3940438639` |
| `PRRT_kwDOT1y4586fiwQU` | `3940438640` |
| `PRRT_kwDOT1y4586fiwQV` | `3940438641` |
| `PRRT_kwDOT1y4586fiwQX` | `3940438643` |

## 4. PR #16 已核原始材料、索引缺陷与边界

六份索引材料实际存在于原执行工作区 `D:\deepseek_test\MediaHub\.smoke`，文件未进入该 PR。本轮只读检查了内容，并重新计算尺寸及 SHA256；尺寸和 SHA256 前 16 位均与实际 head 的归档索引一致，下表保留本轮计算的完整哈希。下表仅记文件身份，不公开原始账号、服务器地址或媒体内容。其他协作者若拿不到这些本机材料，须明确原始材料不可用；本表不能代替独立读取。

| 原始文件 | 字节数 | SHA256 |
|---|---|---|
| `sceneA_wire.txt` | 2341 | `b65b29a3971a077042761329d882fb273bd1fc2965b826254bee60382126d5ed` |
| `sceneA3_wire.txt` | 6183 | `dca8f0c835614960b04823410fd0ef2b88a71562f2901bc357efca8400475ef0` |
| `diag_wire.txt` | 7251 | `5e32492ea06b23f1cb59c361b5d50976a1a186c52120a433c9e6b872aa5e947d` |
| `coldfinal_wire.txt` | 578 | `bb020cde193e786150794625d2958fc4f375592c2f031c1e6592a7b2bb28b8ad` |
| `coldfinal2_wire.txt` | 278 | `9a4d1cd9fe0c5e2f5ac1a44eb367e1ca2f9fcaa3ead510adaeeb5c1c70c30242` |
| `coldfinal3_full.txt` | 1946 | `746b8d26edd70445ecc55cc20df4d0cb8725540c13382109de9bc646f7568646` |

已核事实及应接续的文档更正：

1. `diag_wire.txt` 有 1 次 Playing、11 次 Progress（含初始零）和 1 次 Stopped，序列使用同一 PlaySessionId。当前 §3.3 的 ticks 换算已正确；最终 `996830000 / 10000000 = 99.683` 秒。这证明所记录请求序列与数值换算，不独立证明服务端 UserData 查询及 app UI 已更新。
2. **确认存在索引错误**：归档当前第 114 行把 `coldfinal2_wire.txt` 标为成功 cold-final #2 的 wire 摘录，但文件仅有 `18:59:38.474` 的 Playing 请求及 `18:59:42.262` 的 HTTP 204 响应，耗时 3787 ms，没有 Stopped；它对应 §5 的慢最终上报失败样本。应在任务 5 定点更正场景索引，保留原始文件与哈希。
3. `coldfinal3_full.txt` 才包含 19:01 的 Playing → Stopped 成功样本，且同份材料还有媒体 GET HTTP 502。它可以支持该请求序列，不能证明媒体当时持续正常推进；更快的成功请求不能关闭较慢失败场景。
4. 六份材料没有完整 UserData 查询响应，也没有独立记录的点击/BACK 时间。相应服务端位置、退出预算和精确取消源结论需要补证；不能用缺失材料补造因果关系。
5. 当前档案已移除原真实账号名称，服务器主机名仍未完整脱敏。该隐私问题与日志索引问题均进入任务 5；本文件刻意只用来源 ID、代称及协议路径描述。
6. `SLOW-FINAL COMPLETION ISSUE OPEN / NOT SEALED` 必须保留。任务 2B 后续需验证真实底层网络取消、本地最终进度、停止周期上报后的最终上报、旧会话迟到结果及有界释放；不承诺强杀进程后远端请求必然送达。

## 5. 后续最小交接面

| 接续任务 | 最小独立复核范围 | 所需交付与不能省略的边界 |
|---|---|---|
| 任务 3 / PR #10 | 上列 12 条来源；实际 head 到最新 main 的正常集成；正式播放页与设置页共享视觉状态、保存、重新进入；时钟/采样消费生命周期 | Agent A 接回任何补丁后，Agent B/C 以新 SHA 做独立复审；提供对应 XML、API 32 fallback / API 36 RuntimeShader 仪器证据；真实受支持会话采样、活动字幕安全区域和设备项目分别验收。截图不能证明 FPS、功耗、HDR/DRM 或 FFT 成功。 |
| 任务 2B / 独立生产修复 PR | `3940438639`、`3940438643` 及 §5 慢失败原始样本；engine/coordinator/final flush/网络/release 真实时序 | 先可控失败复现，再最小修复与反向回归；保留取消语义、本地保存和有界退出。未复现的原因仍为风险线索；成功重试不能自动关闭旧失败项。 |
| 任务 5 / PR #16 证据维护 | 上列 10 条来源及 suppressed 子项；主机名脱敏、`coldfinal2` 场景索引、时间与 ticks、事实与推断措辞 | 逐文件定点修订并保留历史原始证据；获取不到原始日志和 UserData 响应时明确缺失。PR #16 继续为证据归档，不承载生产修复或阶段封板。 |

本文件只完成任务 0 来源覆盖与有限证据核对。后续执行必须重新取得远端状态；任何待补项都不算本轮测试通过。未在此轮重新验收的 PR #10 / #16 设备项目保持 **DEVICE UNVERIFIED（本轮）**，协议结论缺少可重复证据时保持 **PROTOCOL UNVERIFIED**。

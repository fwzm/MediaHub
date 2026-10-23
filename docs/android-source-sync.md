# Android 侧跨设备来源同步契约（ADR-S001）

状态：**契约库已落地并通过 Windows 真实产物互操作验证**（task-29 / W6-A）。
范围：只做纯 JVM 契约（解析 / 规范化 / 规划 / 摘要），**没有**存储接线、没有 UI、没有把来源真正导入本机库。

## 1. 结论先行

- ADR-S001 的同步信封**不在本仓文档里**：权威文本是 `D:\mh-w6\docs\cross-device\adr\ADR-S001-source-sync-contract.md`，
  本仓 `docs/cross-device/` 目录不存在。本文的每条规则都以该 ADR 与 `D:\mh-w6\windows\src\MediaHub.Sync\` 的实现为准。
- Android 侧实现为 `core/common/src/main/kotlin/com/mediahub/core/common/sync/` 下 14 个文件；线格式与 Windows **逐字段、逐顺序、逐 reason 码**对齐。
- 互操作已实测三件事：(a) 解析 Windows 真实信封不丢字段；(b) 同一信封在本机规划出的三桶计数与 Windows CLI 完全一致；
  (c) Kotlin 重新编码出的信封喂回 Windows CLI，CLI 给出与自产信封**相同**的 plan 结果（exit 3、counts 2/1/1）。

## 2. 放置决策

放在 **:core:common 的 com.mediahub.core.common.sync**，不新建模块。理由：

| 候选 | 判断 |
| --- | --- |
| 新建 :core:sync 模块 | 否决：只为 14 个文件增加一个模块，构建图与装配成本大于收益 |
| :provider:api | 否决：它是唯一纯 JVM 模块但没有 kotlinx.serialization 依赖；同步是"设备间"契约，与 provider 能力无关 |
| :core:common（选中） | 已有 kotlinx.serialization.json、与 backup/ 同族（同属"跨端数据搬移"）、单测直接跑 JVM（`testDebugUnitTest`） |

唯一构建改动：`core/common/build.gradle.kts` 增加 `api(project(":core:model"))` —— 契约要引用 `ServerType`
（`core:model` 是纯 kotlin-jvm，反向不依赖 `core:common`，无环）。**没有引入任何新外部依赖。**

## 3. 契约映射表

### 3.1 类型与字段

| Windows（D:\mh-w6\windows\src\MediaHub.Sync\） | Android（core/common/.../sync/） | 说明 |
| --- | --- | --- |
| SyncSourceTypes.cs（12 个 wire 串） | SyncSourceTypes.kt | 12 个常量的字面值与顺序逐字相同 |
| SyncSourceTypeValue.cs | SyncSourceTypeValue.kt | raw 原样保留、wire = trim+小写；equals 按 raw（Emby 与 emby 同类但不同值） |
| SourceSyncEnvelope.cs | SourceSyncModels.kt 的 SourceSyncEnvelope | schemaVersion/minimumReaderVersion/deviceId/snapshotId/createdAtEpochMs/capabilities/sources/deletions |
| SyncCapabilities | SyncCapabilities | canSendSecrets 缺省 false、sourceTypes 缺省空 |
| SourceRecord.cs | SourceRecord | syncSourceId/sourceType/displayName/accountScope/endpoints/recordRevision/fieldVersion/secrets |
| SourceEndpoint | SourceEndpoint | url/isPrimary(缺省 false)/enabled(缺省 true)/sortOrder(缺省 0) |
| Tombstone.cs | SourceTombstone | 类名加前缀以示归属，字段逐一相同 |
| WindowsSourceTypeMap.cs（6 行） | AndroidSourceTypeMap.kt（12 行） | **有意不同**：Android 的 ServerType 覆盖全部 12 个 wire 值 |
| SyncUrlNormalizer.cs | SyncUrlNormalizer.kt | 规则见 3.3 |
| SourceSyncCodec.cs | SourceSyncCodec.kt | 手写编解码，见 3.4 |
| SourceSyncPlanner.cs | SourceSyncPlanner.kt | 规则顺序见 3.5 |
| SourceSyncCandidate.cs / LocalSourceRecord.cs | SourceSyncCandidate.kt / LocalSourceRecord.kt | 建议项字段一一对应 |
| SourceSyncPreviewSummary.cs | SourceSyncSummary.kt | apply=create+update、ignored=unchanged、ambiguous 只数 ambiguous-legacy-identity |
| SyncContractException | SourceSyncContractException.kt | reason 形如 missing-field:x / invalid-field:x |

Android 的 ServerType 没有 Unknown 项，因此 LocalSourceRecord.type 用 ServerType?（null = 无类型），
规划时的 AndroidSourceTypeResolution.sourceType 仍是完整的 SyncSourceTypeValue（raw 不丢）。

### 3.2 8 个动作与 13 个 reason 码（逐字）

SourceSyncAction：CREATE / UPDATE / NO_CHANGE / CONFLICT / IGNORE_STALE / DELETE / ADOPT / UNSUPPORTED（与 Windows 同名同序）。

SourceSyncReasons：new-source、higher-revision、up-to-date、same-revision-different-content、stale-revision、tombstone、
no-local-source、unknown-source-type、not-supported-on-windows、legacy-identity-match、ambiguous-legacy-identity、
duplicate-local-sync-id、no-usable-endpoint。

not-supported-on-windows 在 Android 上**保留原名**而不改成 not-supported-on-android：reason 是跨端 machine 码，
两端对同一条输入必须给出同一个字符串，否则日志与验收脚本要按端分支。该分支在 Android 生产数据上不可达
（12 个 wire 全有类型），仅通过可注入解析器的重载测试。

### 3.3 地址规范化（与 Windows SyncUrlNormalizer 同规则）

trim → 空白即不可拨号（null）；`//` 开头前置 `http:`；无显式 scheme 时含 `@` 直接拒绝，否则前置 `http://`；
scheme 只允许 http/https/smb/file；非 file 必须有 host；http/https 端口始终显式（默认端口补 443/80，port<=0 拒绝）；
smb/file 只有显式端口才写；path 去尾斜杠（`/` 归一为空）；**userinfo 被剥离**；query/fragment 天然不进输出。
输出形如 `scheme://host:port/path`。

### 3.4 线格式

写出字段顺序：schemaVersion, minimumReaderVersion, deviceId, snapshotId, createdAtEpochMs,
capabilities{canSendSecrets, sourceTypes[]}, sources[], deletions[]；
record：syncSourceId, sourceType(raw), displayName, [accountScope 仅非 null], endpoints[], recordRevision,
fieldVersion, [secrets 原样]；endpoint：url, isPrimary, enabled, sortOrder；tombstone：syncSourceId,
deletedAtEpochMs, recordRevision。

读取：未知属性忽略（前向兼容）；schemaVersion != 1 → unsupported-schema-version:N；
minimumReaderVersion 缺失 → missing-field:minimumReaderVersion、<1 → invalid-field:minimumReaderVersion、
>1 → unsupported-minimum-reader-version:N；整体不是 JSON 对象/解析失败 → malformed-json；
类型不符 → invalid-field:<名字>（capabilities.sourceTypes 报完整路径）。secrets 不解释、不校验、不记录。

### 3.5 规划规则（顺序不可调换）

1. wire 值本端不认识 → UNSUPPORTED / unknown-source-type（raw 保留，将来版本可接管）
2. 认识但本端无对应类型 → UNSUPPORTED / not-supported-on-windows
3. 两个本地来源声称同一 syncSourceId（trim + 忽略大小写）→ CONFLICT / duplicate-local-sync-id + 候选列表
4. 唯一匹配：remote>local → UPDATE / higher-revision；相等 → 内容一致则 NO_CHANGE / up-to-date，否则
   CONFLICT / same-revision-different-content；remote<local → IGNORE_STALE / stale-revision
5. 无 id 匹配 → pre-W6 遗留候选（本地无 syncSourceId 且 类型/规范化地址/账号 三者一致）：1 个 → ADOPT / legacy-identity-match；
   多个 → CONFLICT / ambiguous-legacy-identity；否则 CREATE（无可用地址 → no-usable-endpoint，否则 new-source）
6. 墓碑：无匹配 → NO_CHANGE / no-local-source；重复 → 冲突；墓碑修订号 < 本地修订号 → IGNORE_STALE；
   否则 DELETE / tombstone + 需确认

主地址选择：优先 isPrimary 中 sortOrder 最小（稳定排序，平手保持发送端顺序），否则全体按 sortOrder 最小；**不过滤 enabled**。

**类里不出现任何墙上时间比较**：createdAtEpochMs 与 deletedAtEpochMs 有意不读（D5 禁止 LWW）。
次序只来自 recordRevision，真正的平手交用户裁定。

## 4. 黄金样本（由 Windows CLI 真跑生成）

生成器：`D:\mh-w6\windows\src\MediaHub.SyncCli\bin\Debug\net10.0\MediaHub.SyncCli.exe`（dotnet 10.0.113）。

Android 测试资源（**字节原样**复制，不要手改）：

| 文件 | 大小 | 内容 |
| --- | --- | --- |
| core/common/src/test/resources/sync/windows-envelope-device-a.json | 1093 B | device-a 的 export 信封：3 源（emby / jellyfin / 未知 quantum-nas）+ 1 墓碑 |
| core/common/src/test/resources/sync/windows-plan-device-b-empty.json | 1089 B | device-b 空目录时对该信封的 plan（counts 2/1/1） |
| core/common/src/test/resources/sync/windows-plan-device-b-applied.json | 1083 B | device-b import --apply 之后再次 plan（counts 0/1/3） |

复跑命令（PowerShell；`$root` 换成一个空临时目录）：

```powershell
$cli = 'D:\mh-w6\windows\src\MediaHub.SyncCli\bin\Debug\net10.0\MediaHub.SyncCli.exe'
$root = Join-Path $env:TEMP 'mh-sync-golden\device-a'
New-Item -ItemType Directory -Path $root -Force | Out-Null
# 1) 写 sync-sources.json：4 条来源（UTF-8 无 BOM），字段见 D:\mh-w6\docs\cross-device\W6-7-SYNC-CLI.md
# 2) 第一次 export 会派生 syncSourceId 并回写 store
& $cli export --root $root --device device-a --json
# 3) 从 store 删掉 srv-dav，再 export 带墓碑
& $cli export --root $root --out (Join-Path $root 'envelope.json') --device device-a --deletions '[{"syncSourceId":"<dav-id>","recordRevision":5,"deletedAtEpochMs":1760000000000}]' --json
# 4) 对空 device-b 做 plan（应 exit 3、counts apply=2 requiresApproval=1 ignored=1）
& $cli plan --root (Join-Path $env:TEMP 'mh-sync-golden\device-b') --in (Join-Path $root 'envelope.json') --device device-b --json
```

注意：CLI 把非 ASCII 写成 \uXXXX 转义，Android 编码器输出原始 UTF-8。**两者不能比字节**，只能比结构化 JSON
（Json.parseToJsonElement(...) 相等即视为一致，见 WindowsGoldenInteropTest）。

## 5. 互操作实测（本轮原始结果）

(a) 解析：SourceSyncCodec.decode(golden) 得到 schemaVersion=1、minimumReaderVersion=1、deviceId=device-a、
snapshotId=1b96e7eb417e4caeb27eb60f3d1acfc3、createdAtEpochMs=1792784739843、
capabilities.sourceTypes=[emby, jellyfin, quantum-nas]；3 条来源的 syncSourceId / displayName / accountScope /
endpoints / recordRevision **逐字段断言**；未知 sourceType:"quantum-nas" 原样保留且 isKnown=false；
墓碑 local-f7ebca3dbf01eb17 / 1760000000000 / 5。

(b) 三桶对照（本机 Planner vs Windows CLI）：

| 场景 | Android Planner | Windows CLI | 一致 |
| --- | --- | --- | --- |
| device-b 空 | apply=2, ignored=1, unsupported=1 | counts{apply:2, requiresApproval:1, ignored:1} | 是（CLI requiresApproval 对应 Planner unsupported） |
| device-b 已 apply | apply=0, ignored=3, unsupported=1 | counts{apply:0, requiresApproval:1, ignored:3} | 是 |

**口径差异（不是 bug）**：CLI 的 reason 是"轮级"码（apply-remote-change / unknown-source-type /
tombstone-unknown / already-applied），Planner 的 reason 是"来源级"契约码。二者只能比桶计数。
测试里把 CLI 的 reason 字符串也断言下来，避免将来误以为"同一套码"。

(c) Kotlin → Windows：WindowsGoldenInteropTest 把 SourceSyncCodec.encode(decode(golden)) 写到
`core/common/build/sync-interop/kotlin-envelope.json`（1081 B，UTF-8，中文不转义）。随后手工执行：

```text
MediaHub.SyncCli.exe plan --root %TEMP%\mh-sync-golden\device-b-kotlin --in <repo>\core\common\build\sync-interop\kotlin-envelope.json --device device-b --json
```

结果：CLI_EXIT=3、counts{"apply":2,"requiresApproval":1,"ignored":1}；apply 两条 reason=apply-remote-change、
approval=unknown-source-type、ignored=tombstone-unknown；store_created=False（plan 不落盘）——
与 Windows 自产信封的 plan 输出**完全相同**。即 Android 编码器产出的信封是 Windows 可直接消费的。

## 6. 测试

命令（Windows，必须显式给 JDK 21，否则落到 JDK 8 直接失败）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME='C:\Users\55160\AppData\Local\Android\Sdk'; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
cmd /c "cd /d D:\deepseek_test\MediaHub_Win && .\gradlew.bat :core:common:testDebugUnitTest --console=plain > %TEMP%\android-sync-task29.log 2>&1"
```

结果：BUILD SUCCESSFUL in 11s；:core:common 12 suites / **165 tests / 0 failures / 0 errors / 0 skipped**。

本轮新增 87 例（全绿）：

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| SyncSourceTypesTest.kt | 4 | 12 个 wire 字面值与顺序、isKnown 大小写/空白 |
| SyncSourceTypeValueTest.kt | 6 | raw 逐字、wire 规范化、两种拼写同类不同值 |
| AndroidSourceTypeMapTest.kt | 6 | 12 行对照、双向、未知值保留 |
| SyncUrlNormalizerTest.kt | 15 | 端口/路径/query/userinfo/协议白名单/幂等 |
| SourceSyncCodecTest.kt | 20 | 字段顺序钉死、可选省略、secrets 原样、错误码逐条、黄金信封、tryDecode |
| SourceSyncPlannerTest.kt | 26 | 8 个动作与 13 个 reason 的每个分支、主地址选择、墙上时间不参与、summary 桶 |
| WindowsGoldenInteropTest.kt | 10 | (a) 逐字段、(b) 两场景三桶对照、(c) 结构化相等 + 产物落盘 |

踩坑记录：bundleLibCompileToJarDebug 报 classes.jar「另一个程序正在使用此文件」时，是上一轮被中断的构建
残留句柄；gradlew --stop 后再跑即可（必要时重启 Kotlin 编译 daemon）。

## 7. 未做项与已知差异

- **没有存储接线**：本机没有 syncSourceId 列（Room v4 的 servers 表无该字段），因此 LocalSourceRecord 目前只能由调用方
  手工构造；plan 之后没有任何"应用建议"的实现（不写库、不建服务器、不落墓碑）。
- **没有 UI**：预览页、确认流程、冲突逐条裁定都只停留在数据层。
- **没有真实导入验证**：ADR-S001 D7 要求导入后逐源验证认证并三态呈现（可用/需重登/暂不支持），本轮未做。
- **secrets 只是搬运**：SourceRecord.secrets 原样保留不解析；接 SecretStorage 重新封装属下一步。
- **Windows 的 not-supported-on-windows 分支在 Android 不可达**：Android 的 ServerType 覆盖全部 12 个 wire 值。
- **没有与 .mhbackup 互通**：按 D1，同步信封与备份文件是两套格式，本库不复用 BackupDtos。
- CLI 的 plan/import 与本库的 reason 码**不是同一套**（见 5.2 段），跨端日志不要按字符串互认。
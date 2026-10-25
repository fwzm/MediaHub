# Android 侧配对契约（W6-E / ADR-S003）

本文件记录 Android 端为「手机与 Windows 配对」建立的**纯契约层**：载荷、帧、错误码、challenge、
指纹、配对码/链接、候选地址排序。它不含网络传输、二维码与 UI——这些是后续任务。

权威来源：Windows 侧实现 D:/mh-w6/windows/src/MediaHub.Sync（PairingPayload.cs、PairingFrame.cs、
PairingChallenge.cs、PairingFingerprint.cs、PairingCode.cs、PairingLink.cs、PairingAddressCandidates.cs、
PairingTransportReasons.cs）与合同 D:/mh-w6/docs/cross-device/adr/ADR-S003（该 ADR **不在本仓库**）。

## 1. 放置决策

| 候选 | 结论 |
| --- | --- |
| 新建 :core:pairing 模块 | 否决。为一个纯计算包新增模块要付 gradle 配置与依赖成本，且会再复制一份 serialization 依赖。 |
| 放 :provider:api | 否决。配对本机能力（Wi-Fi/扫码/密钥）不属于 provider 抽象；且该模块无 serialization。 |
| **放 :core:common 的 com.mediahub.core.common.pairing** | **采用**。该模块已有 kotlinx-serialization-json，单元测试跑 JVM（testDebugUnitTest），与现有 backup/sync 同族。 |

本轮**没有**新增任何依赖、没有改 build.gradle.kts、没有新建模块。

## 2. 落地文件

| 文件 | 职责 |
| --- | --- |
| core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingError.kt | 五组稳定原因码（载荷 / 配对码 / 链接 / 传输 / 地址）+ PairingContractException |
| .../PairingChallenge.kt | 32 字节 challenge，规范 base64，常量时间比较，toString 脱敏 |
| .../PairingFingerprint.kt | 32 字节指纹，规范小写 hex，展示形式，常量时间比较 |
| .../PairingFrame.kt | 4 字节大端长度前缀帧，读端不为超长长度分配内存 |
| .../PairingPayload.kt | 邀请载荷 + 结构/时效校验 + PairingPayloadCodec（线格式） |
| .../PairingLink.kt | PairingCode（base64url 文本）+ PairingLink（mediahub://pair?...） |
| .../PairingAddressCandidates.kt | 局域网候选地址排序/拒绝原因（纯计算） |

测试：core/common/src/test/kotlin/com/mediahub/core/common/pairing/ 下 7 个文件（见第 6 节）。

## 3. 载荷字段（线格式顺序，逐字节对齐 Windows）

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| schemaVersion | int | 只能是 1，其它值 unsupported-version |
| deviceId | string | 非空白，缺失/空白 missing-field:deviceId |
| ephemeralUrl | string | 绝对 http/https + 必须有 host；不得带 user-info / query / fragment |
| challenge | string | 32 字节的**规范** base64（含 = 填充） |
| publicKeyFingerprint | string | 64 位 hex；读入时接受大写，输出恒小写 |
| expiresAtEpochMs | int64 | 必须 > 0；存活 120000ms；expires <= now 即 expired（等于也算） |

键顺序固定；未知属性读取时忽略且**不回写**（已用黄金向量钉住：加 futureField 后重编码等于原载荷）。

## 4. 帧格式（与任务书的差异，必须记录）

- 帧 = 4 字节**大端** int32 长度 + 载荷字节；上限 8192 字节；下限 1 字节。
- **合同里没有 magic、也没有帧内版本字段**：版本只在载荷的 schemaVersion 里。
- Windows 代码里**不存在 wrong-magic 这个错误码**（全仓 0 命中；最接近的 magic-mismatch 属于备份归档，
  与配对无关）。帧错误统一映射为传输码 payload-invalid。
- 读端行为：长度 <= 0、长度 > 8192、字节不足，一律返回 null，且**在读 body 之前就拒绝**，不按声明长度分配内存。

## 5. 原因码表（逐字冻结）

载荷码（PairingReasons）：unsupported-version、bad-fingerprint、bad-challenge、bad-url、expired、
challenge-replayed、challenge-mismatch，另有 malformed-json 与两个前缀 missing-field:<name> / invalid-field:<name>。

配对码（PairingCodeReasons）：code-empty、not-base64url、code-too-long、payload-invalid，外加透传的
unsupported-version / bad-challenge / bad-fingerprint / bad-url / expired。映射规则与 Windows
MapPayloadReason 一致：五个业务码原样透传，malformed-json 与 missing-field:/invalid-field: 一律折成 payload-invalid。

链接（PairingLinkReasons）：pairing-link-empty、not-a-pairing-link、pairing-link-too-long。

传输（PairingTransportReasons，本轮只冻结码表）：listener-failed、tls-version、fingerprint-mismatch、
payload-invalid、lifetime-exceeded、already-paired、peer-timeout。

地址（PairingAddressReasons）：wired-interface、wireless-interface、other-interface、loopback-address、
apipa-address、interface-down、not-ipv4、no-usable-address、address-enumeration-failed。

## 6. 黄金向量与互操作证据

向量文件：core/common/src/test/resources/pairing/windows-pairing-vectors.json（11100 字节，仓库 blob 尺寸）。
仓库尺寸一律取 git blob（`git cat-file -s HEAD:<path>`），不用本机检出尺寸：本仓库 core.autocrlf=true，
该文件 161 行在本机检出为 11260 字节（多 160 字节即行尾 CRLF），GH 上看到的是 11100 字节。
它由 Windows 侧**真实 API** 生成，不是手写：临时生成器工程 D:/mh-verify/pairing-vectors
（net10.0 console，ProjectReference 到 D:/mh-w6/windows/src/MediaHub.Sync/MediaHub.Sync.csproj），
**生成器不入库**。

复跑命令（Windows 侧，需 dotnet 10）：

    dotnet run --project D:/mh-verify/pairing-vectors -- D:/mh-verify/pairing-vectors/vectors.json
    Copy-Item D:/mh-verify/pairing-vectors/vectors.json D:/deepseek_test/MediaHub_Win/core/common/src/test/resources/pairing/windows-pairing-vectors.json

向量冻结的输入：nowEpochMs=1792800000000、deviceId=device-windows、
ephemeralUrl=https://192.168.1.20:8942/pairing、challenge=字节 0x00..0x1F、指纹=字节 0xA0..0xBF、
expires=1792800120000。生成器实测：载荷 JSON 精确 280 字节，帧 284 字节（长度头 00000118）。

测试清单（7 个文件，共 73 例；口径：`core/common/build/test-results/testDebugUnitTest` 的 JUnit XML，HEAD 11ed12e 实测，与第 8 节同源）：

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| PairingVectorsTest | 21 | 向量驱动：载荷逐字段、重编码逐字节、帧十六进制、帧结构拒绝、剩余毫秒、toString 脱敏、18 条错误码、过期边界、码/链接往返与拒绝、大小写规范化、未知属性、非规范 challenge、候选与拒绝地址表、重复地址丢弃 |
| PairingPayloadTest | 12 | create 生存期、deviceId/URL 校验、编码字段顺序、编码前校验、往返、未知属性、空白/非对象根、tryDecode、剩余毫秒下限、边界过期、http/https 白名单 |
| PairingChallengeTest | 7 | 规范 base64、长度校验、非规范拼写拒绝、parse 原因码、常量时间比较、随机生成、toString 脱敏 |
| PairingFingerprintTest | 9 | 小写 hex、长度校验、大写读入规范化、非法 hex 拒绝、展示形式、比较、相等性、hex 白名单 |
| PairingFrameTest | 6 | 大端长度、上限/空载荷、忽略尾随字节、六类结构拒绝、超长不分配、UTF-8 文本往返 |
| PairingLinkTest | 9 | 生成/提取往返、code= 与大写前缀容错、链接原因码、过期边界、规范化、填充/非法字符、UTF-8 与原因映射、码白名单 |
| PairingAddressCandidatesTest | 9 | 优先级排序、按字节排序、loopback/tunnel、not-ipv4 先于 down、down/apipa/loopback、重复丢弃、空集与枚举失败、IPv4 严格解析、展示 URL |

实测结果见第 8 节。

## 7. 逐字对齐点（容易写错的地方）

1. challenge 只认规范拼写：解码后重新编码必须与原串相等，插一个空格也要拒绝（bad-challenge）。
2. 指纹读入接受大写，但规范形式是小写；写回恒小写。
3. 过期判定是 expires <= now，**等于就过期**。
4. toString 必须脱敏 challenge（打印 <redacted>），且向量里钉住了整串输出。
5. 配对码不接受 = 填充：字母表只允许字母数字与 - _；长度 % 4 == 1 也是 not-base64url。
6. 链接 create 生成 mediahub://pair?<code>（不写 code=），解析时两种都接受且前缀大小写不敏感。
7. 地址排序：优先级 → IP 字节逐字节 → 接口名忽略大小写；重复地址**直接丢弃**、不进 rejected 列表。
8. 候选地址只做纯计算：真实网卡枚举（Android 侧需 WifiManager/ConnectivityManager）不在本包内。

## 8. 实测记录

执行环境：JAVA_HOME=C:/Program Files/Java/jdk-21、ANDROID_HOME=ANDROID_SDK_ROOT=C:/Users/55160/AppData/Local/Android/Sdk，
工作目录 D:/deepseek_test/MediaHub_Win，跑前 Get-Process MediaHub* 计数 0。

    .\gradlew.bat :core:common:testDebugUnitTest --console=plain --rerun-tasks

原始结果：

    BUILD SUCCESSFUL in 18s

:core:common JUnit XML 汇总（core/common/build/test-results/testDebugUnitTest）：

    SUITES=19 TESTS=238 FAILURES=0 ERRORS=0 SKIPPED=0

基线（W6-E 起跑前快照，非当前值）：12 suites / 165 tests。本轮 +7 suites / +73 tests，全部新增在 pairing 包：

| 测试类 | 例数 |
| --- | --- |
| PairingVectorsTest | 21 |
| PairingPayloadTest | 12 |
| PairingChallengeTest | 7 |
| PairingFingerprintTest | 9 |
| PairingFrameTest | 6 |
| PairingLinkTest | 9 |
| PairingAddressCandidatesTest | 9 |
| 合计 | 73 |

互操作结论（向量驱动，全部与 Windows 生成值逐字相等）：载荷重编码逐字节相同、帧 284 字节十六进制相同、
剩余毫秒 120000/0 相同、toString 脱敏文本相同、18 条错误码全部相同、候选/拒绝地址表与优先级相同。

两轮红例复盘（均未放宽断言）：

1. 编译错 PairingVectorsTest.kt:9 Unresolved reference 'content' —— kotlinx.serialization 的 JsonPrimitive.content 是成员属性，
   不存在同名包级扩展，删掉该 import 即可（int/long 等才是扩展）。
2. 断言错 PairingAddressCandidatesTest 的 priority 期望值写错：接口名为 "Ethernet 2" 且类型 WIRED 时优先级是 0，
   不是 1（Windows 只按接口类型分档；名字里带 2 不改变档位）。改为 0。
3. 阶段踩坑（非代码）：:core:common:bundleLibCompileToJarDebug 报 classes.jar「另一个程序正在使用此文件」，
   属 Kotlin/Gradle 编译 daemon 残留句柄，执行 gradlew --stop 并结束残留 java 进程后重跑即恢复。

## 9. 未做项（明确不在本轮范围）

- 真实 TLS 1.3 传输与指纹钉扎握手（W6-G 已补回环 TCP 帧收发，TLS 与钉扎仍未做，见第 10 节）。
- 二维码生成与相机扫码（CameraX 接入、权限流）。
- challenge 一次性台账的持久化（Windows 有内存 PairingChallengeLedger；Android 未实现，跨进程重启会丢）。
- PeerCard（对端名片）解析与 PairedDeviceObservation。
- UI：配对页、候选地址选择、进度与失败提示。
- 与 Windows 的端到端配对联调（本轮只做契约级互操作：同一份向量、同一批原因码与字节）。
- 地址候选联调：Windows 用 NetworkInterface 枚举；Android 侧枚举待接入后再比同一份输出。

## 10. 传输客户端（W6-G）

### 10.1 能力边界（刻意收窄）

- **只连回环**：127.0.0.1 / ::1 / localhost。其它目标在拨号前就被拒绝，码为 non-loopback-peer（Android 本地策略码）。
- **不做 DNS**：只解析字面量地址。主机名的解析结果不受本端控制，放行等于把「只连回环」交给解析器决定。
- **无 TLS 1.3、无证书指纹钉扎**：Windows 的 PairingTransportClient 有这两层；Android 本轮只对齐帧与失败码，没有握手。
- **单次交换**：可选先写一帧请求再读一帧响应，读完立即关 socket，调用方不持有连接（也就没有长连接状态要管）。
- **超时可配**：连接超时与读取超时分别可设；两类超时统一映射为合同码 peer-timeout。
- **不串 UI**：客户端不依赖 Compose，也不读写任何界面状态。

### 10.2 落点

| 文件 | 内容 |
| --- | --- |
| core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportClient.kt | 客户端：exchange（可选写请求 + 读响应）、fetchInvitation（帧 -> 载荷 -> 时效 -> challenge -> 指纹） |
| core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingError.kt | 新增 object PairingClientReasons（NON_LOOPBACK_PEER）；合同码表未改 |
| core/common/src/test/kotlin/com/mediahub/core/common/pairing/FakeWindowsPairingHost.kt | 测试替身：只绑 127.0.0.1:0，按 Windows 帧格式收发，可在断言线程检查工作线程异常 |
| core/common/src/test/kotlin/com/mediahub/core/common/pairing/PairingTransportClientTest.kt | 21 例，覆盖收发与全部失败码 |
| core/common/src/test/kotlin/com/mediahub/core/common/pairing/PairingTransportEndToEndTest.kt | 3 例，黄金信封 -> 规划三桶 |

### 10.3 帧规则（与 PairingFrame 完全一致）

4 字节大端长度 + 载荷；没有 magic、没有帧内版本（同第 4 节）；载荷长度 1..8192。
读端拿到 4 字节头后先判长度再决定是否读体：长度 <= 0 或 > 8192 直接判 payload-invalid，**绝不为非法长度分配内存**；
声明 8193 而主机一个体字节都不发时，客户端仍在毫秒级返回 payload-invalid（有用例钉住这一点，若实现读了体就会退化成 peer-timeout）。

### 10.4 失败码表

| 情形 | 码 | 归属 |
| --- | --- | --- |
| 连接被拒 / 连不上 | listener-failed | 合同码（PairingTransportReasons） |
| 连接或读取超时 | peer-timeout | 合同码 |
| 帧头不足 4 字节、长度 0、长度 > 8192、帧体不足、对端在读帧前关闭 | payload-invalid | 合同码 |
| 帧内容不是合法配对载荷（malformed-json / missing-field:* / unsupported-version 等一律折叠） | payload-invalid | 合同码 |
| 载荷已过期（expires <= now，等于即过期） | lifetime-exceeded | 合同码 |
| 载荷 challenge 与本端期望不一致 | challenge-mismatch | 合同码（PairingReasons） |
| 载荷公钥指纹与期望不符（仅当调用方传了期望指纹） | fingerprint-mismatch | 合同码 |
| 目标地址不是回环 | non-loopback-peer | **Android 本地码**，不属于 ADR-S003 词汇，不可跨端比对 |

校验顺序是刻意的：帧结构 -> 载荷解码 -> 时效 -> challenge -> 指纹。先证明字节是帧、再证明内容是载荷、再证明没过期、
再证明是同一个邀请、最后才证明公钥；期望 challenge 为空时直接 bad-challenge 且根本不拨号。

### 10.5 端到端：黄金信封 -> 规划三桶

假主机发送的是 W6-A 冻结的黄金信封（core/common/src/test/resources/sync/windows-envelope-device-a.json，不是合成数据），
客户端收帧后交给 SourceSyncCodec.decode 与 SourceSyncPlanner.plan，得到与 Windows CLI 相同的三桶：

| 桶 | 本端规划值 | Windows CLI counts |
| --- | --- | --- |
| apply（create + update） | 2 | 2 |
| requiresApproval（requiresUserApproval 或 unsupported） | 1 | 1 |
| ignored（no-change + ignore-stale） | 1 | 1 |

CLI 的 counts.requiresApproval 与规划器语义不同名：CLI 把 unknown-source-type 计入该桶，所以对照口径固定为
`requiresUserApproval || action == UNSUPPORTED`（同 docs/android-source-sync.md 第 9 节）。字段层面另有断言：deviceId device-a、
snapshotId 1b96e7eb417e4caeb27eb60f3d1acfc3、3 条来源（含 quantum-nas 未知类型逐字保留）、1 条墓碑（1760000000000）、
capabilities.sourceTypes = [emby, jellyfin, quantum-nas]——即帧搬运没有丢字节。

### 10.6 socket 已关闭 / 零残留怎么证明

1. **客户端侧**：假主机在工作线程里读对端直到 EOF 才 countDown（FakeWindowsPairingHost.awaitEndOfStream -> awaitPeerClosed）；
   用例在断言线程上要求 awaitPeerClosed(2000) 为真——说明客户端读完帧就自己关了 socket，不是等 GC。
2. **服务端侧**：host.close() 关闭监听与在途 socket 并 join 工作线程；用例随后断言 isClosed、workerAlive == false，
   并用新的 Socket 连同一端口必须失败（端口已释放，而不是「只是不再 accept」）。
3. **不拨号**：非回环目标、空 challenge、空请求、超长请求四类用例都断言假主机的 acceptedCount == 0，证明拒绝发生在拨号之前。

### 10.7 测试清单（24 例）

| 测试类 | 例数 | 覆盖 |
| --- | --- | --- |
| PairingTransportClientTest | 21 | fetchInvitation 往返、先写请求后读响应、只读交换、8192 上限内可收、超长/空请求拒绝、空 challenge 拒绝、非回环三种目标、连接被拒、读取超时、长度 0、声明 8193 不读体、截断体、对端提前关闭、非法载荷、过期、challenge 不符、指纹不符、客户端关 socket、host 释放端口、非法参数 |
| PairingTransportEndToEndTest | 3 | 黄金信封三桶 2/1/1 与 CLI 相等、信封字段逐条保真、假主机零残留（端口/线程/连接） |

### 10.8 实测记录

执行环境：JAVA_HOME=C:/Program Files/Java/jdk-21、ANDROID_HOME=ANDROID_SDK_ROOT=C:/Users/55160/AppData/Local/Android/Sdk，
工作目录 D:/deepseek_test/MediaHub_Win，跑前 Get-Process MediaHub* 计数 0。

    .\gradlew.bat :core:common:testDebugUnitTest --console=plain --rerun-tasks

原始结果：

    BUILD SUCCESSFUL in 1m
    EXIT=0

:core:common JUnit XML 汇总（core/common/build/test-results/testDebugUnitTest）：

    SUITES=21 TESTS=262 FAILURES=0 ERRORS=0 SKIPPED=0

基线（W6-E 收尾快照）：19 suites / 238 tests。本轮 +2 suites / +24 tests：

| 测试类 | 例数 | 耗时 |
| --- | --- | --- |
| PairingTransportClientTest | 21 | 2.254 s（真实回环 socket，含一处 1.5 s 超时用例） |
| PairingTransportEndToEndTest | 3 | 0.017 s |
| 合计 | 24 | |

一次设施故障（非代码）：首跑 :core:common:bundleLibCompileToJarDebug 失败，原因是 classes.jar 被残留编译 daemon 占用；
执行 gradlew --stop 并结束残留 java 进程后重跑即绿（同第 8 节第 3 条）。

E2E 结论：黄金信封经回环帧搬运后，规划三桶 2 / 1 / 1 与 Windows CLI 的 counts 完全相等；
信封字段（deviceId / snapshotId / 3 条来源含未知 quantum-nas / 1 条墓碑 / capabilities）逐条保真。

### 10.9 未做项

- 真实 TLS 1.3 与证书指纹钉扎（本轮只做回环 TCP 帧；Windows 的 tls-version / fingerprint-mismatch 仍只存在于码表与载荷指纹校验里）。
- 二维码生成与扫码、配对页 UI、候选地址选择。
- challenge 一次性台账（跨进程重放判定仍缺）。
- 与真机 / Windows 服务端的联调：本轮对端是 127.0.0.1 上的测试替身，验证的是帧与码，不是网络与握手。
- 把客户端接进导入流程（拿到信封后自动预览/应用）——W6-C 的 AndroidSourceSyncService 已能导入，但两者尚未串成一条命令。

## 11. 配对入口（W6-59：只核对链接）

### 11.1 动机（缺口是真的）

第 1-10 节把 Android 侧配对**契约**做齐了：载荷、帧、码、挑战、指纹、链接、候选地址、回环传输客户端，
共 10 个测试套件。但 `feature/` 层对 `PairingLink` / `PairingPayload` / `PairingTransportClient` /
`PairingAddressCandidates` **零引用**（在 app / core / feature / provider 的非测试代码里搜这四类名只命中 core:common 自身）。
也就是说：契约能解析一段链接，可用户没有地方贴这段链接——目标书 ③「二维码安全配对」在 Android 侧
等于没有入口。本轮补的就是这个入口的第一段。

### 11.2 为什么本版只核对、不连接

| 项 | Windows | Android 本版 | 结论 |
| --- | --- | --- | --- |
| 回环 TCP 帧交换 | 有（PairingTransportClient.cs） | 有（PairingTransportClient.kt，W6-G） | 可做 |
| TLS 1.3 + 证书指纹钉扎 | 有 | **没有** | 连接邀请这一步得等 |
| 二维码生成/扫码 | 有（PairingCode） | 无相机依赖、无扫码 | 本版只接受粘贴的文本 |
| 凭据写入 | 有（配对后落库） | 无 | 本版不写任何凭据 |

所以本版的边界写进界面常量（`PairingLinkCopy.NOT_YET`）：**只核对链接里写明的设备与公钥指纹，不会连接对端**。
把「已实现」和「没做」分开写，是为了避免用户把「核对成功」当成「已经配好」。

### 11.3 三条外部可观察语义

| 输入 | 结果 | 稳定码来源 |
| --- | --- | --- |
| 合法链接（`mediahub://pair?...`） | 状态 `Parsed`，显示设备标识 / 公钥指纹（`displayString` 16 组 4 字符大写）/ 剩余秒数 / 对端 host:port | 无码（`codeOf` 为 null） |
| 空白输入 | 状态 `Failed(pairing-link-empty)`，配一句人话 | 契约码 `PairingLinkReasons.EMPTY` |
| 非配对链接前缀 | `Failed(not-a-pairing-link)` | `PairingLinkReasons.NOT_A_LINK` |
| 超过 8192 字符 | `Failed(pairing-link-too-long)` | `PairingLinkReasons.TOO_LONG` |
| 非法 base64url / 长度模 4 余 1 | `Failed(not-base64url)` | `PairingCodeReasons.NOT_BASE64URL` |
| 不是合法配对载荷 / 版本不符 / 字段缺失 | `Failed(payload-invalid)` 等 | `PairingCode` 的映射（五个业务码透传） |
| 已过期（expires <= now） | `Failed(expired)` | `PairingReasons.EXPIRED` |

**没有新增任何 Android 专用码**：界面只显示 core:common 已有的码，本层唯一的补充是给 `pairing-link-empty`
配一句人话（`EMPTY_HINT`），码本身仍是契约值。

### 11.4 不泄漏挑战

`challenge` 是这次邀请的一次性凭据。状态 `Parsed` 里**没有**它，结果行没有它，`PairingLinkState` 的任何字段也没有它；
校验用 JVM 用例钉住（`theSuccessLineLeaksNeitherTheChallengeNorTheRawLink`）：结果行不含 `challenge.base64`、不含 `mediahub://`、
不含 `PairingChallenge(<redacted>)`。链接原文也不进状态（只进输入框，输入框内容不随状态保存）。

### 11.5 落点（文件与行号）

| 文件 | 行号 | 内容 |
| --- | --- | --- |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/pairing/PairingLinkController.kt` | `:19` / `:37` / `:62` / `:71` / `:90` / `:107` / `:133` / `:138` | `PairingLinkState`（Idle / Parsed / Failed）、`PairingLinkCopy`（含 `:62` `lineOf` 与 `:71` `codeOf`）、`:90` `PairingLinkController(now)`、`:107` `verify`、`:133` `clear`、`:138` `endpointOf`（https 缺省 443、其它 80） |
| `.../pairing/PairingLinkScreen.kt` | `:39` / `:58` / `:78` | `PairingLinkTags`（`:41` `pairing.screen`、`:43` `pairing.link`、`:45` `pairing.action`、`:47` `pairing.state`、`:49` `pairing.clear`）、`:58` `PairingLinkRoute`（remember 控制器，无 Hilt）、`:78` 无状态 `PairingLinkScreen` |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/SettingsScreen.kt` | `:61` / `:78` / `:89` / `:130` | `:61` import、`:78` `var showPairing`、`:89` `if (showPairing) { PairingLinkRoute(...); return }`（与 W6-F 的导入页同一种页内托管，不改导航图）、`:130` 同步区块的「配对设备」按钮（testTag `pairing.entry` 在 `:133`） |

### 11.6 用例清单

JVM `feature/settings/src/test/kotlin/com/mediahub/feature/settings/pairing/PairingLinkControllerTest.kt`（13 例，全部用 `PairingPayload.create` + `PairingLink.create` 现场造链接、注入固定时钟，不写死 base64）：

| 用例 | 钉住什么 |
| --- | --- |
| `aRealLinkYieldsTheDeviceFingerprintEndpointAndRemainingSeconds` | Parsed 三字段 + 剩余 90 秒 + 结果行含三项 + `codeOf` 为 null |
| `anHttpsLinkWithoutAPortFallsBackToFourFortyThree` | 地址规范化（缺省端口 443） |
| `aBlankLinkIsRefusedWithTheContractEmptyCode` | 空链接 = 契约 `pairing-link-empty`，不是自造码 |
| `aBareCodeWithoutTheLinkPrefixIsNotAPairingLink` | 只有码没有前缀 → `not-a-pairing-link` |
| `anExpiredLinkIsRefused` | 过期 → `expired` |
| `anOverLongLinkIsRefusedByLength` | 超 8192 → `pairing-link-too-long` |
| `textThatIsNotBase64UrlIsRefused` | 非 base64url → `not-base64url` |
| `aPayloadThatIsNotAPairingPayloadIsPayloadInvalid` | 合法 base64 但不是载荷 → `payload-invalid` |
| `aFailedVerifyAfterASuccessDropsThePreviousConclusion` | 不留半成品：失败后上一次成功结论被作废 |
| `clearReturnsToIdle` | 清空回 Idle，结果行与码都为 null |
| `theSuccessLineLeaksNeitherTheChallengeNorTheRawLink` | 不泄漏挑战/链接原文 |
| `theFailureLineCarriesTheStableCode` | 失败行含稳定码与那句人话 |
| `theCopyIsStableAndSaysTheVersionDoesNotConnect` | 固定文案（标题/按钮/tag）与「不会连接对端」 |

instrumentation `feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/pairing/PairingLinkScreenTest.kt`（7 例）：
空态按钮不可点、贴链接后按钮可点并把原文交给回调、Parsed 行显示设备/指纹/剩余秒、Failed 行显示稳定码、
返回回调只调一次、「清空」把输入框与按钮一起复位、整页不打印挑战。

### 11.7 实测记录

本机串行实跑（跑前 `gradlew --stop`、跑完再 `--stop` 并确认 `Get-Process java` 为 0）：

| 项 | 结果 |
| --- | --- |
| 命令 | `gradlew.bat --max-workers=2 --rerun-tasks :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest`（`JAVA_HOME`=Android Studio JBR、`ANDROID_HOME`=Sdk） |
| 构建 | `BUILD SUCCESSFUL in 5m 17s`、`212 actionable tasks: 212 executed`、EXIT=0 |
| `feature/settings` JVM | **18 套件 / 304 例 / 0 失败 / 0 错误 / 0 跳过**（291 + 13）；`PairingLinkControllerTest` **13/13** |
| androidTest APK | `settings-debug-androidTest.apk` = **20476121 B**（2026-10-25 13:09 +08:00 重打） |
| APK 解包核对 | 19 个 dex；7 个新用例名与 `PairingLinkScreenTest` 全在 `classes16.dex`（14248 B）；`pairing.link` / `pairing.action` / `pairing.state` / `pairing.clear` 在 `classes16.dex` 与 `classes8.dex`；入口 tag `pairing.entry` 在 `classes9.dex` ⇒ 不是陈旧包 |
| 收尾 | `gradlew --stop`、`java-left=0`、仓库无 `hs_err_pid*.log` / `replay_pid*.log` |
| 设备执行 | **DEVICE_UNVERIFIED**：7 例 instrumentation 本机无设备可跑，结论待 CI 的 visual-instrumentation 作业 |


### 11.7.1 模拟器首红与修复（`c5f2d08` → `3400796`）

本机 JVM 与 `assembleDebugAndroidTest` 都是绿的，但第一次真跑 instrumentation 时 `PairingLinkScreenTest` 红了 3 例：

```
PairingLinkScreenTest > theBackButtonCallsBackExactlyOnce FAILED
  java.lang.AssertionError: Assert failed: The component is not displayed!
PairingLinkScreenTest > aFailedStateShowsTheStableCode FAILED
  java.lang.AssertionError: Failed to assert the following: (Text + EditableText contains 'not-base64url' (ignoreCase: false))
PairingLinkScreenTest > aParsedStateShowsTheDeviceIdTheFingerprintAndTheRemainingSeconds FAILED
  java.lang.AssertionError: Failed to assert the following: (Text + EditableText contains 'windows-a' (ignoreCase: false))
```

两条根因都不是产品代码的问题，是**测试自己的匹配器用错了**：

1. `assertTextContains(text)` 的第二个参数 `substring` **缺省是 false**，即要求整段相等。结果行是
   「已核对：windows-a，指纹 …，剩余 90 秒（本版本不连接对端）。」这一整句，所以三处断言都必须写成
   `assertTextContains(..., substring = true)`。这一条本机永远发现不了——JVM 侧只断言 `lineOf()` 返回的字符串，
   不经过 Compose 语义树。
2. 返回按钮是 `IconButton { Icon(contentDescription = "返回") }`，它是**内容描述**不是文本：
   `onNodeWithText("返回")` 匹配不到任何节点 ⇒ `The component is not displayed!`。改用 `onNodeWithContentDescription("返回")`。

修完只重编 androidTest（`gradlew.bat --max-workers=2 :feature:settings:assembleDebugAndroidTest`）→
`BUILD SUCCESSFUL in 2m 9s`、`155 actionable tasks: 6 executed, 149 up-to-date`、`java-left=0`，
最终以 CI 的 visual-instrumentation 结论为准。

### 11.8 未做项（本版明确不做）

- ~~不连接对端~~：**已在第 12 节实现**（W6-59b：TLS 1.3 + 证书指纹钉扎，按链接里那一枚指纹钉）。第 11 节描述的版本确实只核对不连接。
- **不扫码**：没有相机依赖，也不引入；本版只接受粘贴的链接文本。
- **不写任何凭据/Token**：契约层也没有这类字段，配对成功后落库属于后续任务。
- **不做候选地址枚举接入**：`PairingAddressCandidates` 仍是纯计算（W6-60）。
- **真机未跑**：instrumentation 只在模拟器/CI 上跑，真机状态 DEVICE_UNVERIFIED。
- **未做视觉回归**：只断言标签、文案与语义树，不比对像素。

## 12. 配对入口第二段：真正拉一次邀请（W6-59b）

### 12.1 为什么这一步必须现在就做

第 11 节把「贴链接 → 核对」做完了，但两边能力表里那一行仍然空着：**Android 侧没有 TLS 1.3、没有证书指纹钉扎**，
所以核对完只能显示「还没连对端」。而拉邀请是配对的第二个必需动作——不验证对端真的是链接里那一台，
后面「写凭据 / 建同步」就无从谈起（谁都能冒充一个写着别人的链接）。本轮补齐的就是这一段：
按链接里写明的地址与**那一枚**公钥指纹去拨一次，TLS 1.3 握手时钉扎它，再逐项校验对端回的邀请。

### 12.2 两步刻意分开（第 11 节的边界规则不变）

| 动作 | 发字节 | 失败码来源 | 结果态 |
| --- | --- | --- | --- |
| 核对链接（`verify`） | **一个字节都不发** | core:common 的载荷 / 码 / 链接三组码 | `Parsed` |
| 拉取邀请（`fetchInvitation`） | 一次 TLS 1.3 连接 | core:common 的传输组码（四个）+ 本层两个前置条件码 | `Fetched` / `Failed` |

也就是说：**核对成功仍然不代表配好了**。`PairingLinkCopy.NOT_YET` 仍在页面上，写的是
「本页只做『核对链接 + 拉一次邀请』：不写任何凭据、不建立同步」，本轮没有放宽这句。

### 12.3 钉扎的信任根只有一处

`PairingTransportFetchPort` 把链接里的指纹同时当作两样东西：

1. `expectedFingerprint`——用于校验对端回传载荷里写的指纹（core 的 `validate`：不符即 `fingerprint-mismatch`）；
2. `PairingTlsOptions.Pinned(expectedFingerprint.hex)`——TLS 1.3 握手时钉住对端证书 SPKI。

出口接口 `PairingFetchPort` 收的是 `(host, port, expectedChallenge, expectedFingerprint)` 四个值，
**没有**第五个「另给的信任根」参数：链接就是唯一的根，多一个来源只多一个可被替换的地方。

### 12.4 两个新增的本地前置条件码（不进 core 词汇表）

| 码 | 什么时候 | 会不会拨号 |
| --- | --- | --- |
| `pairing-not-verified` | 没先核对链接（或核对失败后）就点「拉取邀请」 | 不会 |
| `pairing-fetch-unavailable` | 本次构建没有注入拉取出口（预览态 / 未接线） | 不会 |

两条都是**本层前置条件**，不进 `PairingError.kt` 的任何码集合，也不与跨机五套码冲突：
它们描述的是「本页还没具备拨号的前提」，不是「对端怎么了」。跨机失败码仍然只有 core 的传输组那四个。

### 12.5 外部可观察语义

| 输入 / 时机 | 结果 |
| --- | --- |
| 核对后点「拉取邀请」，对端回的邀请逐项通过 | `Fetched`：显示对端设备标识、指纹、`host:port`、剩余秒数，结果行以「邀请已校验」开头 |
| 指纹对不上 | `Failed(fingerprint-mismatch)`，界面原样显示这个码 |
| 对端回传的挑战与本端不一致 | `Failed(challenge-mismatch)` |
| 邀请已过期 | `Failed(lifetime-exceeded)` |
| 握手超时 / 连接不上 | `Failed(peer-timeout)` 等传输组码 |
| 拉取进行中再点一次 | 直接忽略（单飞），不拨第二次，状态仍是 `Fetching` |
| 拉取进行中 | 输入框、核对、拉取、清空四个控件全部禁用，另显示「正在拉邀请」 |

### 12.6 落点

| 文件 | 内容 |
| --- | --- |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/pairing/PairingFetchPort.kt` | `fun interface PairingFetchPort`（四参数的 `suspend fun fetch`）+ 生产实现 `PairingTransportFetchPort`（`Dispatchers.IO` 里调 `PairingTransportClient.fetchInvitation(..., tls = Pinned(...))`） |
| `.../pairing/PairingLinkController.kt` | 状态加 `Fetching` / `Fetched`；`suspend fun fetchInvitation()`；`endpointOf` / `endpointText`（https 缺省 443、其它 80）；两个前置条件码常量 |
| `.../pairing/PairingLinkScreen.kt` | 新 tag `pairing.fetch`；拉取按钮（只在 `Parsed` / `Fetched` 且不忙时可点）；`FETCH_HINT` / `FETCHING_HINT` / `FETCHED_HINT` 三段解释；`NOT_YET` 补回页面底部 |

### 12.7 用例清单

JVM `PairingLinkControllerTest`（**22 例**，原 13 例 + 新增 9 例；拨号一律走替身 `FakeFetchPort`，本类不建 socket）：

| 新增用例 | 钉住什么 |
| --- | --- |
| `fetchingWithoutAVerifiedLinkIsRefusedBeforeAnyPort` | 没核对 → `pairing-not-verified`，替身调用 0 次 |
| `fetchingWithoutAPortReportsTheUnavailableCode` | 没注入口 → `pairing-fetch-unavailable` |
| `aSuccessfulFetchShowsTheValidatedInvitation` | `Fetched` 四字段 + 剩余秒数按固定时钟算 |
| `thePortGetsTheHostPortChallengeAndFingerprintFromTheLink` | 交给出口的 host / port / challenge / fingerprint 逐项等于链接里那四项 |
| `aFetchFailureKeepsTheCoreCodeVerbatim` | 出口的失败码与句子原样透出，不自造码 |
| `aSecondFetchWhileOneIsInFlightIsIgnored` | 单飞：在飞时再点不拨第二次（替身调用数恒为 1） |
| `theFetchedLineLeaksNeitherTheChallengeNorTheRawLink` | `Fetched` 结果行不含挑战、不含 `mediahub://`、不含 `PairingChallenge(...)` |
| `clearAfterAFetchReturnsToIdle` | 拉成功后清空仍回 `Idle` |
| `aFetchAfterAFailedVerifyIsRefusedBecauseNothingIsHeld` | 核对失败后拉取被拒且不拨号（不留半成品） |

另把 `theCopyIsStableAndSaysTheVersionDoesNotConnect` 改成 `theCopyIsStableAndSaysTheVersionOnlyVerifiesAndFetches`：
旧断言「不会连接对端」在本轮已经不成立（现在会连），改为断言「不写任何凭据」「不建立同步」，并追加
`FETCH_HINT` 里三个失败码（`fingerprint-mismatch` / `challenge-mismatch` / `lifetime-exceeded`）。
**这是一次故意的断言转向**：文案变了，钉的仍然是同一条边界——「连过」不等于「配好」。

instrumentation `PairingLinkScreenTest`（**12 例**，原 7 例 + 新增 5 例）：

| 新增用例 | 钉住什么 |
| --- | --- |
| `theFetchButtonIsDisabledUntilTheLinkIsVerified` | `Idle` 时拉取按钮在但不可点 |
| `aParsedStateEnablesTheFetchButtonAndTheClickIsReported` | `Parsed` 时可点且回调被调一次 |
| `aFetchingStateDisablesEverythingAndSaysItIsConnecting` | 忙时四个控件全禁 + 状态行含「正在连接」+ 显示 `FETCHING_HINT` |
| `aFetchedStateShowsTheValidatedInvitationAndSaysSyncIsStillSeparate` | 结果行含「邀请已校验」+ `FETCHED_HINT` 在页面上 |
| `aFetchFailureShowsTheCoreCodeInTheStateLine` | 失败行含 `fingerprint-mismatch`，且拉取按钮不可点 |

### 12.8 实测记录

本机串行实跑（每轮跑前 `gradlew --stop`、跑完再 `--stop`，两轮之间不并发；`--max-workers=1`、`-Xmx1536m`）：

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `:feature:settings:testDebugUnitTest` | **BUILD SUCCESSFUL in 5m 27s**、EXIT=0、`127 actionable tasks: 18 executed, 109 up-to-date`；**18 套件 / 313 例 / 0 失败 / 0 错误 / 0 跳过**（304 + 9），`PairingLinkControllerTest` **22/22** |
| 2 | `:core:common:testDebugUnitTest :feature:settings:assembleDebugAndroidTest` | **BUILD SUCCESSFUL in 3m 44s**、EXIT=0、`160 actionable tasks: 11 executed, 149 up-to-date`；`core/common` **38 套件 / 542 例 / 0 失败 / 0 错误 / 11 跳过**（跳过的是两个环境门控套件 `SourceSyncTlsInteropTest` 与 `SourceSyncListenerInteropTest`，无 Windows CLI 时按 assumption 跳过，与既有口径一致）；`settings-debug-androidTest.apk` = **20587433 B**（2026-10-25 15:16:21 重打） |
| 收尾 | — | `gradlew --stop` 两次、`java-left=0`、仓库根 `hs_err_pid*.log` 计数 0 |

androidTest APK 解包核对（不是看大小，是看内容）：20 个 dex；`PairingLinkScreenTest` 与五个新用例名
（`theFetchButtonIsDisabledUntilTheLinkIsVerified` / `aParsedStateEnablesTheFetchButtonAndTheClickIsReported` /
`aFetchingStateDisablesEverythingAndSaysItIsConnecting` / `aFetchedStateShowsTheValidatedInvitationAndSaysSyncIsStillSeparate` /
`aFetchFailureShowsTheCoreCodeInTheStateLine`）以及 tag `pairing.fetch` **全部命中 `classes16.dex`**（17628 B）。

| 项 | 状态 |
| --- | --- |
| `feature/settings` JVM（本机实跑） | **EXECUTED_PASS**：313 例 / 0 失败 |
| `core/common` JVM（本机实跑） | **EXECUTED_PASS**：542 例 / 0 失败（11 skip 全部是环境门控） |
| androidTest APK 构建 + 内容核对 | **EXECUTED_PASS** |
| 12 例 instrumentation 真跑 | **DEVICE_UNVERIFIED**（本机无设备；以 CI visual-instrumentation 为准） |
| 与真实 Windows 对端的跨端拉取 | **NOT_RUN**（需要 Windows 侧起 TLS 1.3 监听 + 第二台机器/同机跨进程，目前只有回环 e2e） |

### 12.9 未做项

- **跨机拉取仍 NOT_RUN**：本轮 TLS 证据只到「core 层 13 例 `PairingTransportTlsTest` + 回环 e2e」，真实两台机器之间没跑。
- **不写凭据**：`Fetched` 之后没有落库动作，配对完成态属于后续任务。
- **不做候选地址枚举**：`PairingAddressCandidates` 仍未接 Android 网卡枚举（W6-60）。
- **不扫码**：仍只接受粘贴文本。

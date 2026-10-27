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

## 13. 配对入口第三段：把网卡枚举接进来（W6-60）

### 13.1 缺的是什么

`PairingAddressCandidates` 从 W6-59 起就在仓库里，但它**没有任何生产调用方**：类注释写得很清楚——「本类不监听端口、不发包、不枚举真实网卡：真实枚举由调用方完成，随后把结果交给这里排序」。第 12 节把「核对链接 + 拉一次邀请」做完了，但页面从来没告诉过用户**本机有哪些地址可以被对端连**。这一节补的正是这一段：谁去读网卡。

### 13.2 与 Windows 侧同义：只产文本，绝不产 socket

Windows 权威实现 `D:/mh-w6/windows/src/MediaHub.Sync/PairingAddressCandidates.cs` 的类注释是这一节的决策依据，逐字要点：

> This is a display-only helper. The pairing listener always binds loopback: binding a real interface would make Windows show a firewall prompt, and a pairing listener that survives on a public interface is a liability, not a feature. Candidates therefore never become sockets here — they only become text in a link a person hands over.

Android 侧照抄这个立场：本轮的产物是**一段只读文本**（「本机有 2 个可用地址，首选 192.168.1.20（Ethernet，有线）」+ 每地址一行），页面上**没有任何「开始监听」按钮**，也没有任何 bind / dial。理由是同一个：真正要开监听就得开防火墙、就得让一个服务活在外网口上，那是产品决策不是展示细节。所以这里只回答「我有哪些地址」，不回答「我准备好被连了」。

这条边界有专门的证据：instrumentation `thePageOffersNoListenButtonAtAll` 直接断言页面上不存在「监听」与「开始监听」两个文本节点。

### 13.3 落点：适配层与展示层分开

| 文件 | 职责 |
| --- | --- |
| `core/common/.../pairing/PairingAddressEnumerator.kt`（新） | `fun interface PairingNetworkInterfaceSource { fun read(): List<PairingAddressInput> }`；`object PairingInterfaceKinds`（纯分类）；`class PairingAddressEnumerator`（异常 ⇒ 既有码）；`class JvmNetworkInterfaceSource`（真读 `java.net.NetworkInterface`） |
| `feature/settings/.../pairing/PairingAddressSummary.kt`（新） | 纯展示：`lineOf` / `linesOf` / `codeOf` + 五条文案常量 |
| `feature/settings/.../pairing/PairingLinkController.kt`（改） | 新增 `PairingAddressState{NotRead, Ready(report), Unavailable}` 与 `refreshAddresses()`，与既有五态**互不影响** |
| `feature/settings/.../pairing/PairingLinkScreen.kt`（改） | 页尾新增「本机可被连的地址」区块，四个新 test tag |

**为什么不改 `PairingAddressCandidates` 一个字节**：那一层有 9 例单测加 `PairingVectorsTest` 里的跨端黄金向量（6 个 Windows 形状网卡），钉的是「排序与拒绝原因」。这一轮要的是「怎么把网卡数据搬进来」，搬错和排错是两件事，分开才能各测各的。

### 13.4 分类规则（顺序即优先级）

`PairingInterfaceKinds.of(interfaceName, loopback, virtual, pointToPoint)`：

| 序 | 条件 | 结果 |
| --- | --- | --- |
| 1 | `loopback` | `LOOPBACK`（回环最先判：哪怕它同时是虚拟接口、名字还像隧道） |
| 2 | `virtual` 或 `pointToPoint` 或名字前缀命中 `tun/tap/ppp/ipsec/pptp/l2tp/wg` | `TUNNEL` |
| 3 | 名字前缀命中 `wlan/wl/wifi/swlan` | `WIRELESS` |
| 4 | 其余（含空名字） | `WIRED` |

名字先 `trim().lowercase()`，所以 `WLAN0` 与 `Wlan1` 都落 `WIRELESS`。

**地址只收 IPv4**（`Inet4Address`），与 Windows 的 `AddressFamily.InterNetwork` 过滤一致。IPv6 地址不进 `PairingAddressInput`——如果哪天要收，那两边必须同时收，否则同一份 link 在两端会给出不同的候选集合。

### 13.5 失败语义：只用一个既有码

| 情形 | 码 | 说明 |
| --- | --- | --- |
| `source.read()` 抛任何 `Exception` | `address-enumeration-failed` | 走 `PairingAddressCandidates.failed()`，**适配层从不抛出** |
| 读到空表 / 全是不可用地址 | `no-usable-address` | 与上一行是**两个不同的码**：读成功但没地址 ≠ 读不到 |
| 正常有候选 | `null` | `codeOf` 返回 null |

单张网卡读属性失败（`inputOf` 内 try/catch）只让**那张网卡**贡献空，其余照算——与 Windows 对 `NetworkInformationException` 的逐网卡处理同义。不自造任何 Android 专用码：码表扩容要走 W6-47 结论 C 的三条门槛（两端同名同序 + 交集为空 + 两端各有用例），本轮够不着。

### 13.6 外部可观察语义

| 动作 | 效果 |
| --- | --- |
| 进入页面 | `LaunchedEffect` 读一次网卡属性（不发包、不需要用户点任何按钮）；读完置 `Ready(report)` |
| 点「重新读取本机地址」 | 再读一次并整体替换 report（会重新读，不是缓存） |
| 没有地址读取出口（预览态 / 未接线） | `Unavailable` + 明说「没有读网卡」，**不假装读到空** |
| 拉邀请正在进行（`Fetching`） | 刷新按钮禁用（整页唯一的忙态仍然只由拉邀请决定） |
| 地址流与链接流 | 两条独立 `StateFlow`：读地址不动 `PairingLinkState`，清空链接也不动地址 |

### 13.7 用例清单

JVM `core/common` `PairingAddressEnumeratorTest`（**8 例**）：

| 用例 | 钉住什么 |
| --- | --- |
| `loopbackWinsBeforeEveryOtherFlag` | 回环优先级最高（含「回环 + 虚拟 + 点对点」同时成立） |
| `virtualPointToPointAndTunnelPrefixesAllBecomeTunnel` | 虚拟标志、点对点标志、七个隧道前缀逐个断言 |
| `wirelessPrefixesBecomeWirelessInAnyCase` | 四个无线前缀 + 大小写变体 |
| `everythingElseIsWiredIncludingAnEmptyName` | 兜底成有线，空名字也不例外 |
| `aReadFailureBecomesTheExistingEnumerationFailedCodeAndNeverThrows` | 异常 ⇒ 既有码，且只读了一次 |
| `anEmptyReadIsNoUsableAddressNotAnEnumerationFailure` | 空表与读不到是两个码 |
| `theAdapterPassesInterfacesThroughAndLetsTheCandidateLayerSortThem` | 适配层只搬运：排序、拒绝原因、`primary` 全由候选层给 |
| `theProductionSourceIsTheDefaultSoTheCallerCannotForgetIt` | 默认构造即生产实现；**不假设 CI runner 有网卡**，只要求从不抛出且码在集合内 |

JVM `feature/settings` `PairingAddressSummaryTest`（**5 例**）+ `PairingLinkAddressesTest`（**6 例**）：

| 用例 | 钉住什么 |
| --- | --- |
| `theHeadlineSaysHowManyAndWhichIsPrimary` | 标题说清「几个」与「首选是谁」 |
| `eachCandidateGetsOneLineWithItsInterfaceAndKind` | 逐字断言 `192.168.1.20（Ethernet，有线）` 与 `10.0.0.5（Wi-Fi，无线）` |
| `theLinesCarryNeitherAPortNorAUrl` | 地址行不含端口、不含 URL（端口由发起同步那一页决定，这里不替它编） |
| `noUsableAddressAndAnEnumerationFailureReadDifferently` | 两种失败读起来不一样，码也各自可查 |
| `theBlockTextSaysItIsDisplayOnlyAndNeverSaysListen` | 文案含「只做展示」「不监听」；标题与按钮**不得**出现「监听」 |
| `withoutAnEnumeratorTheBlockIsUnavailableAndNothingIsPretended` | 没接出口 ⇒ `Unavailable`，且刷新返回 false |
| `aRefreshReadsTheInterfacesOnceAndShowsTheCandidates` | 读一次、候选按候选层顺序出现 |
| `aReadFailureLandsOnTheExistingEnumerationFailedCode` | 读失败落既有码 |
| `anEmptyReadIsNoUsableAddress` | 空读落 `no-usable-address` |
| `addressesAndTheLinkStateAreTwoIndependentStreams` | 两条状态流互不覆盖（读地址后链接仍 `Idle`；清空链接后地址仍在） |
| `aSecondRefreshReReadsAndReplacesTheReport` | 第二次刷新真的再读一遍并替换（不是缓存） |

instrumentation `PairingLinkAddressScreenTest`（**7 例**）：

| 用例 | 钉住什么 |
| --- | --- |
| `theAddressBlockIsShownWithItsDisplayOnlyHint` | 区块、标题、「只做展示」提示、未读态文案都在 |
| `anUnavailableStateSaysThereIsNoReaderInsteadOfPretendingItIsEmpty` | 无出口时说明「没有出口」，不说「没有地址」 |
| `aReadyStateShowsTheHeadlineAndOneLinePerCandidate` | 一行标题 + 每候选一行（按 tag 与逐字文本双查） |
| `anEmptyReportShowsTheNoUsableAddressCode` | 空报告行的码是 `no-usable-address` |
| `aFailedReportShowsTheEnumerationFailedCode` | 读不到的行码是 `address-enumeration-failed` |
| `theRefreshButtonIsEnabledAndTheClickIsReported` | 刷新按钮可点且回调被调一次 |
| `thePageOffersNoListenButtonAtAll` | **页面上不存在「监听」「开始监听」** |

### 13.8 实测记录

本机串行实跑（跑前 `gradlew --stop`、跑完再 `--stop`；`--max-workers=1`、`-Xmx1536m`、`-XX:MaxMetaspaceSize=768m`、`-Dkotlin.daemon.jvm.options=-Xmx1024m`；跑完 `java-left=0`、仓库根 `hs_err_pid*.log` 计数 0）：

| 项 | 结果 |
| --- | --- |
| 命令 | `:core:common:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest` |
| 构建 | **BUILD SUCCESSFUL in 6m 56s**、EXIT=0、`217 actionable tasks: 32 executed, 185 up-to-date` |
| `core/common` JVM | **39 套件 / 550 例 / 0 失败 / 0 错误 / 11 跳过**（542 + 8；11 skip 仍是两个环境门控套件） |
| `feature/settings` JVM | **20 套件 / 324 例 / 0 失败 / 0 错误 / 0 跳过**（313 + 11） |
| `PairingAddressEnumeratorTest` | **8/8**、0.108 s |
| `PairingAddressSummaryTest` | **5/5**、0.028 s |
| `PairingLinkAddressesTest` | **6/6**、0.018 s |
| `PairingLinkControllerTest` | **22/22**（回归未破） |
| `PairingAddressCandidatesTest` | **9/9**（候选层一字未动） |
| `settings-debug-androidTest.apk` | **20594218 B**（2026-10-25 16:32:36 重打） |

androidTest APK 解包核对（不是看大小，是看内容）：20 个 dex，`PairingLinkAddressScreenTest`、`thePageOffersNoListenButtonAtAll`、`aReadyStateShowsTheHeadlineAndOneLinePerCandidate` 与 tag `pairing.addresses.refresh` / `pairing.addresses.line` **全部命中 `classes16.dex`** ⇒ 不是陈旧包。

| 项 | 状态 |
| --- | --- |
| `core/common` JVM（本机实跑） | **EXECUTED_PASS**：550 例 / 0 失败 |
| `feature/settings` JVM（本机实跑） | **EXECUTED_PASS**：324 例 / 0 失败 |
| androidTest APK 构建 + 内容核对 | **EXECUTED_PASS** |
| 7 例 instrumentation 真跑 | **DEVICE_UNVERIFIED**（本机无设备；以 CI visual-instrumentation 为准） |
| 真机读到真实网卡后的展示 | **DEVICE_UNVERIFIED**（JVM 用假 source，真网卡路径只在 CI 模拟器上跑过） |

### 13.9 未做项

- **不做地址选择后的动作**：这一页只展示，选哪个地址、什么时候开监听仍是后续任务。
- **不读 IPv6**：与 Windows 对齐，只收 IPv4；要收必须两端同时收。
- **不接二维码**：候选地址还没有变成二维码内容，仍只接受粘贴文本。
- **真机网卡组合 NOT_RUN**：模拟器通常只有 `eth0`/`wlan0` 这一类；多网卡（Wi-Fi + 蜂窝 + VPN 同时在线）的真实排序只有真机才能验。

## 14. 配对侧文本边界统一到共享 `WireText`（W6-69）

### 14.1 缺的是什么

W6-67 把 **归档** 侧（口令、kdf 名、头部、信封）的文本边界写成了跨语言契约，并给两端各加了一个 `WireText`
（Kotlin `com.mediahub.core.common.sync.WireText` / C# `MediaHub.Sync.WireText`）。但 **配对** 侧是另一套代码：
Kotlin 在 `core/common/.../pairing/*.kt`，C# 在 `windows/src/MediaHub.Sync/Pairing*.cs`，两边都在用各自语言自带的
`isBlank()` / `trim()` / `ignoreCase` / `uppercase()` / `CASE_INSENSITIVE_ORDER`。这些「语言自带」的语义在两端并不一致：

| 语言设施 | JVM 语义 | .NET 语义 | 后果 |
| --- | --- | --- | --- |
| `String.isBlank()` / `char.isWhitespace` | `Character.isWhitespace \|\| isSpaceChar`：**含 U+001C–U+001F、不含 U+0085** | `char.IsWhiteSpace` = Unicode White_Space 的 25 个 BMP 码点：**不含 U+001C–U+001F、含 U+0085** | 同一个 `deviceId` 两端一个判空一个不判空 |
| `String.trim()` | 只剥 **≤ U+0020** 的字符（JVM 早期语义） | `string.Trim()` 剥 Unicode White_Space | 与上一行再差一层 |
| `startsWith(..., ignoreCase)` / `equalsIgnoreCase` / `CASE_INSENSITIVE_ORDER` | 会把 **U+017F**（长 s）折成 `s`、**U+212A**（开尔文）折成 `k`、**U+0131**（无点 i）折成 `i` | `OrdinalIgnoreCase` 只折 ASCII A–Z | 前缀/主机名/接口名比较会在非 ASCII 上分叉 |
| `uppercase()` / `lowercase()` | 全 Unicode 大小写映射 | `ToUpperInvariant()` / `ToLowerInvariant()` 全 Unicode | 指纹展示与规范化可能一多一少 |

本轮把这套边界统一到 **同一个 `WireText`**：两端各扩展 4 个助手（`startsWithAsciiIgnoreCase` / `lowercaseAscii` /
`uppercaseAscii` / `compareAsciiIgnoreCase`，加上 W6-67 已有的 `isBlank` / `trim` / `equalsAsciiIgnoreCase` /
`hasLoneSurrogate` / `containsLoneSurrogate`），然后把配对侧所有**对外契约相关**的调用点换掉。
判据不是「代码风格统一」，是：**同一份输入两端必须给出同一个 reason**。

### 14.2 口径（三条，全部有向量钉住）

1. **空白 = Unicode White_Space 的 25 个 BMP 码点**（显式表，不依赖语言自带属性）。
   U+001C–U+001F 与 U+200B、U+FEFF、U+00AD **不是**空白；U+0085、U+00A0、U+3000 **是**。
2. **大小写只折 ASCII A–Z**。长 s、开尔文、无点 i、带点大写 i **一律不折**。
3. **孤立代理项一律拒**：口令走 `passphrase-too-weak`（W6-67 已定），
   配对载荷里的孤立代理项走 `malformed-json`。

### 14.3 真实缺陷（本轮修掉的两个 + 一个真分歧）

**① .NET 读端对转义孤立代理项抛非契约异常（真缺陷）。**
生成器先跑一遍探针：对 7 个含孤立代理项的载荷 JSON，Kotlin 侧 `Json.parseToJsonElement` 接受、
`PairingPayloadCodec.decode` 一路读到 `deviceId` 也接受；C# 侧 `JsonDocument.Parse` 同样接受，
但 `ReadRequiredString` 里的 `element.GetString()` 抛 **`InvalidOperationException`**（不是 `JsonException`），
穿透 `Deserialize` 的 `catch (JsonException)` ⇒ 两端一个「成功」一个「未捕获异常」。
修法：两端 `decode` 在 `root is JsonObject` 检查之后、读第一个字段之前，各加一次
`WireText.containsLoneSurrogate(root)`（C# 为 `WireText.ContainsLoneSurrogate`），命中即 `malformed-json`。
这条检查是**文档级**的：包括未知字段里的孤立代理项也拒（重序列化时会被静默改写成 U+FFFD）。

**② URL 校验的两端分歧（真分歧）。**
Kotlin 的 `validateEphemeralUrl` 先 `trim()` 再 `URI()`；C# 直接把**未 trim 的原串**交给 `Uri.TryCreate`。
实测 `.NET Uri.TryCreate` 容忍 **U+0020 / U+0009** 前后缀（两端都能过），但**不容忍 U+0085 / U+00A0**；
而 Kotlin 先 trim 会把它们剥掉 ⇒ 同一份 `ephemeralUrl` 在 Android 是 `ok`、在 Windows 是 `bad-url`。
修法：C# 也先 `WireText.Trim` 再 `Uri.TryCreate`，并把主机判空从 `string.IsNullOrEmpty` 改成 `WireText.IsBlank`。
现在两端对 ASCII 空格/制表、U+0085、U+00A0、U+3000 前后缀的结论完全一致（`ephemeral-url-ascii-padded` /
`next-line-padded` / `ideographic-padded` = `ok`，`record-separator-padded` = `bad-url`、`zero-width-padded` = `ok`）。

**③ 向量自身的缺陷：`expired` 用例从未真的过期。**
C# 孪生用例首跑 9/10，红的正是 `expired`：向量里那条链接是按 `Now + LifetimeMilliseconds` 构造的
（`expiresAtEpochMs = 1792800120000`），而测试用冻结的 `frozenNowEpochMs = 1792800000000` 去解析 ⇒ 根本没到期。
修法不是把测试里的时间硬编码成另一个值（那样只是把缺陷藏进测试），而是 **让每条 link 向量自带 `nowEpochMs`**：
生成器 `AddAt(name, link, nowEpochMs)` 为每条候选写一个 `nowEpochMs` 字段，两端用例都读它。
教训：向量只存「输入 + 期望输出」不够，凡是依赖**外部时钟**的用例，时钟本身也必须进向量。

### 14.4 落点

| 端 | 文件 | 改动 |
| --- | --- | --- |
| Kotlin | `core/common/src/main/kotlin/com/mediahub/core/common/sync/WireText.kt` | 3360 → **6172 B**：新增 `startsWithAsciiIgnoreCase` / `lowercaseAscii` / `uppercaseAscii` / `compareAsciiIgnoreCase` + 两个私有折叠器 |
| Kotlin | `core/common/.../pairing/PairingLink.kt` | `extractCode` 与 `parse` 的 trim / 前缀判定全改 `WireText` |
| Kotlin | `core/common/.../pairing/PairingPayload.kt` | `deviceId` / `ephemeralUrl` / 载荷空文档 / `readRequiredString` 的判空；scheme 小写；**新增文档级孤立代理项检查** |
| Kotlin | `core/common/.../pairing/PairingFingerprint.kt` | 判空、`lowercaseAscii` 规范化、`uppercaseAscii` 展示 |
| Kotlin | `core/common/.../pairing/PairingChallenge.kt` | 判空 |
| Kotlin | `core/common/.../pairing/PairingTls.kt` | 空 pin 判定 |
| Kotlin | `core/common/.../pairing/PairingTransportSupport.kt` | `LoopbackOnly.addressOrNull` 的 trim 与 `localhost` 判定 |
| Kotlin | `core/common/.../pairing/PairingAddressCandidates.kt` | 三处 `raw.trim()`、排序末级 `CASE_INSENSITIVE_ORDER` → `compareAsciiIgnoreCase` |
| Kotlin | `core/common/.../pairing/PairingAddressEnumerator.kt` | 接口名 `trim().lowercase()` → `lowercaseAscii(trim(...))`（Android 独有，不进跨端向量） |
| C# | `windows/src/MediaHub.Sync/WireText.cs` | 5749 → **8278 B**：同样四个助手 |
| C# | `windows/src/MediaHub.Sync/PairingLink.cs` | `ExtractCode` / `Parse` 的 Trim 与前缀 |
| C# | `windows/src/MediaHub.Sync/PairingPayload.cs` | `DeviceId` 判空、**URL 先 trim**、主机判空、空文档、`ReadRequiredString`；**新增文档级孤立代理项检查** |
| C# | `windows/src/MediaHub.Sync/PairingFingerprint.cs` | 判空 |
| C# | `windows/src/MediaHub.Sync/PairingChallenge.cs` | 判空 |
| C# | `windows/src/MediaHub.Sync/PairingAddressCandidates.cs` | 接口名 trim、地址 trim、排序末级 `CompareAsciiIgnoreCase` |

**刻意没改的地方**（写下来避免下一轮「顺手统一」把面铺大）：

- `PairingCode.normalize` / `PairingCode.Normalize` **仍然只剥空格、Tab、CR、LF 四类**。配对码是 base64url，
  非 ASCII 空白在逐字符校验时本来就会落 `not-base64url`，改成 `WireText.trim` 会让「粘贴带 U+00A0 的配对码」
  从 `not-base64url` 变成 `ok`——那是行为变更，不是契约统一，本轮不做。
- C# 侧 `PairingPresenter` / `PairingSelfIdentity` / `PairingSession` / `PairingPeerCard` / `PairingTransportServer` /
  `PairingTransportResult` / `PairingCode`（reason 参数校验）/ `PairingCertificate` 里的 `IsNullOrWhiteSpace` 与 `Trim`
  是 **Windows 内部输入校验**，Kotlin 侧没有对位代码，不在跨端契约里，本轮不动。
- Kotlin `PairingAddressEnumerator`（网卡枚举）是 Android 独有，不进跨端向量。

### 14.5 黄金向量新增 `textEdges` 段（老 11 段逐字节不变）

生成器 `D:/mh-verify/pairing-vectors/Program.cs` 302 → ~640 行，在 `addressCandidates` 之后追加第 12 个根键
`textEdges`，共 10 个子段：

| 子段 | 条数 | 钉住什么 |
| --- | --- | --- |
| `whitespaceCodePoints` | 25 | 显式码点表本身 |
| `blankCandidates` | 14 | `isBlank` / `trim` 的逐候选结论（含 U+001C、U+001F、U+200B、U+FEFF、U+00AD） |
| `caseFoldPairs` | 8 | `equalsAsciiIgnoreCase`（长 s / 开尔文 / 无点 i 不折） |
| `asciiCaseTransforms` | 9 | `uppercaseAscii` / `lowercaseAscii` 只动 ASCII |
| `prefixPairs` | 8 | `startsWithAsciiIgnoreCase` |
| `comparePairs` | 8 | `compareAsciiIgnoreCase` 是全序（`Math.signum` 语义） |
| `loneSurrogateCandidates` | 7 | 孤立代理项判定 |
| `linkCandidates` | 16 | `PairingLink.parse` 的 reason；每条自带 `nowEpochMs` |
| `payloadCandidates` | 18 | `PairingPayloadCodec.decode` 的 reason；`ok` 时比 `afterRead` 值 |
| `loneSurrogateDocuments` | 7 | 文档级孤立代理项 ⇒ `malformed-json` |

产物 `vectors.json` 11260 → **128430 B**（sha256 `b9134675…642fa`），逐字节复制到两端资源：
`core/common/src/test/resources/pairing/windows-pairing-vectors.json` 与
`windows/tests/assets/windows-pairing-vectors.json`。生成器照惯例**不入库**。

### 14.6 用例清单（两端各 10 例，逐条同名同义）

| Kotlin（`PairingEdgeContractTest`） | C#（`PairingEdgeContractTests`） | 钉住什么 |
| --- | --- | --- |
| `whitespace_table_matches_the_vectors_and_excludes_the_jvm_extras` | `TheWhitespaceTableMatchesTheVectorsAndExcludesTheRuntimeExtras` | 25 个码点逐点相等，且 U+001C/U+001F/U+200B/U+FEFF/U+00AD 不在表里 |
| `is_blank_and_trim_agree_with_every_candidate` | `IsBlankAndTrimAgreeWithEveryVectorCandidate` | 14 条空白候选 |
| `ascii_case_folding_never_folds_a_non_ascii_lookalike` | `AsciiCaseFoldingNeverFoldsANonAsciiLookalike` | 8 组折叠对 |
| `ascii_case_transforms_only_touch_ascii_letters` | `AsciiCaseTransformsOnlyTouchAsciiLetters` | 9 条大小写变换 |
| `prefix_test_folds_ascii_only` | `PrefixTestFoldsAsciiOnly` | 8 组前缀 |
| `comparison_is_a_total_order_that_folds_ascii_only` | `ComparisonIsATotalOrderThatFoldsAsciiOnly` | 8 组比较 + 反对称性 |
| `lone_surrogate_detection_matches_every_candidate` | `LoneSurrogateDetectionMatchesEveryCandidate` | 7 条代理项候选 |
| `link_parsing_reports_the_windows_reason_for_every_candidate` | `LinkParsingReportsTheVectorReasonForEveryCandidate` | 16 条链接 reason |
| `payload_decoding_reports_the_windows_reason_and_reads_the_same_value` | `PayloadDecodingReportsTheVectorReasonAndReadsTheSameValue` | 18 条载荷 reason + `afterRead` |
| `documents_with_a_lone_surrogate_are_refused_as_malformed_json` | `DocumentsWithALoneSurrogateAreRefusedAsMalformedJson` | 7 条文档级判定 |

两端断言风格一致：**先比 reason 字符串，再比值**；`ok` 的条目还要比 `afterRead`（把「读到了但读错」也钉住）。
C# 侧没有 `Assert.Equal(expected, actual, message)` 三参重载（xUnit v3），统一用 `Assert.True(expected == actual, …)`。

### 14.7 实测记录

本机串行实跑（**先 gradle 后 dotnet，不并发**；gradle 跑前 `gradlew --stop`、跑完再 `--stop`，
`--max-workers=1`、`-Xmx1536m`、`-XX:MaxMetaspaceSize=768m`、`-Dkotlin.daemon.jvm.options=-Xmx1024m`；
跑完 `hs_err_pid*.log` / `replay_pid*.log` 计数 0）：

| 项 | 结果 |
| --- | --- |
| 命令 | `:core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest` |
| 构建 | **BUILD SUCCESSFUL in 2m 29s**、EXIT=0、`234 actionable tasks: 33 executed, 201 up-to-date` |
| `core/common` JVM | **45 套件 / 622 例 / 0 失败 / 0 错误 / 11 跳过**（612 + 10；11 skip 仍是两个环境门控套件） |
| `core/database` JVM | **20 套件 / 192 例 / 0 失败 / 0 错误 / 12 跳过**（未变；12 skip 是 `SourceSyncListenerInteropTest`） |
| `feature/settings` JVM | **23 套件 / 354 例 / 0 失败 / 0 错误 / 0 跳过**（未变） |
| `PairingEdgeContractTest` | **10/10** |
| `PairingVectorsTest` | **21/21**（老向量段回归未破） |
| `settings-debug-androidTest.apk` | **20785281 B**（2026-10-26 22:44 重打） |
| C# `MediaHub.Tests`（W6 树） | **1614 通过 / 0 失败 / 0 跳过**（1604 + 10），`dotnet test … -p:Platform=x64` |

第一轮 gradle 是红的：`core/common` 622 例里 1 例失败，`PairingEdgeContractTest` 的
`documents_with_a_lone_surrogate_are_refused_as_malformed_json` 在 `device-id-valid-pair` 上期望 `malformed-json`、实得 `ok`。
根因是**测试自己写错了**：`valid-pair` 与 `embedded-pair` 两条向量的期望就是 `ok`，Kotlin 侧那条附加断言却要求「所有条目都等于
`malformed-json`」。C# 孪生用例写的是「仅当 expected 是 `malformed-json` 时才钉稳定码」，所以它一直是对的。
修法是把 Kotlin 断言改成与 C# 同形（分支断言），重跑 622 例全绿。

| 项 | 状态 |
| --- | --- |
| Kotlin 配对文本边界（本机实跑） | **EXECUTED_PASS**：622 例 / 0 失败 |
| C# 配对文本边界（本机实跑） | **EXECUTED_PASS**：1614 例 / 0 失败 |
| androidTest APK 构建 | **EXECUTED_PASS**：20785281 B |
| 10 例 C# / 10 例 Kotlin 之外的真机行为 | **DEVICE_UNVERIFIED**（本机无设备；配对页 instrumentation 走 CI 模拟器） |
| 真实两机配对 | **NOT_RUN**（需要第二台机器 + 用户同意开防火墙） |

### 14.8 未做项

- **不改配对码的空白集**：`PairingCode.normalize` 仍是「空格/Tab/CR/LF」四类，理由见 14.4。
- **不统一 Windows 内部校验**：`PairingPresenter` 等九处 `IsNullOrWhiteSpace` 不与 Kotlin 对位，不进契约。
- **不做大小写不敏感的接口名排序之外的本地化**：`compareAsciiIgnoreCase` 只折 ASCII，中文接口名按码元序，与既有一致。
- **真实两机配对仍 NOT_RUN**：本轮所有证据都是同机两端各自实跑 + 同一份向量对照，没有第二台机器。
- **真机 instrumentation DEVICE_UNVERIFIED**：本轮没有新增 instrumentation 用例（文本边界是纯 JVM/C# 层）。

## 15. 配对设备登记与解绑（W6-70）

### 15.1 背景与范围

W6-59 / W6-59b / W6-60 把 Android 侧「核对配对链接 → 拉一次邀请 → 列出本机可被连的地址」做完了，但**配对结果没有落点**：
Windows 从 ADR-S005 起就有完整的配对设备登记（`PairedDevice` / `PairedDeviceRegistry` / `FilePairedDeviceStore` / `PairedDeviceListView`，12 条原因码 + 5 条存储码），
Android 侧 `core/` 与 `feature/` 的生产代码里 `PairedDevice` / `pairedDevice` / `PairingStore` 零命中 —— 配对一次，App 重启后什么都不记得。

本轮补齐两件事，且**只补这两件**：①本机登记档案（读写同一份磁盘格式，与 Windows 逐字节相同）；②「忘记一台」解绑（两段式确认 + 回执）。
**不做**「登记新设备」：那需要真的走完一次配对握手并拿到对端指纹，属后续里程碑；本页的写入路径只由「忘记」触发删除，不新增登记入口。

### 15.2 交付物

| 层 | 文件 | 说明 |
| --- | --- | --- |
| core/common | `pairing/PairedDevice.kt` | 六字段 record + `fingerprintDisplay` / `displayNameOrId` / `describe` / `create` / `formatEpoch` |
| core/common | `pairing/PairedDeviceStore.kt` | 4 成员接口 + 5 条存储码 + `PairedDeviceStoreException` |
| core/common | `pairing/InMemoryPairedDeviceStore.kt` | 测试替身（`writeCount` / `knownDeviceIds`，相等不写盘，满容量抛 `LIMIT_REACHED`） |
| core/common | `pairing/PairedDeviceDocument.kt` | `internal object`：复刻 `Utf8JsonWriter{Indented=true}` 的字节（2 空格缩进、CRLF、无尾随换行、ASCII 转义表） |
| core/common | `pairing/FilePairedDeviceStore.kt` | 原子写（`.tmp` → 复制 `.bak` → `Files.move`）、坏档隔离、读永不抛 |
| core/common | `pairing/PairedDeviceRegistry.kt` | 12 条原因码、`verify` / `register` / `forget` / `describeState` / `describeReason` |
| core/common | `pairing/PairedDeviceListPresenter.kt` | 纯展示：行文案、空态、豆腐块、容量提示 |
| feature/settings | `paired/PairedDeviceController.kt` | 状态机 + 忘记两段式 + 存储入口 `PairedDeviceStorage` |
| feature/settings | `paired/PairedDeviceScreen.kt` | 无状态 Compose 页 + `PairedDeviceRoute` + tags |
| feature/settings | `SettingsScreen.kt` | 页内托管分支 + `paired.entry` 入口（在 `pairing.entry` 之后） |
| 测试 | `core/common/.../PairedDeviceContractTest.kt` | 14 例，逐条消费黄金向量 |
| 测试 | `feature/settings/.../PairedDeviceControllerTest.kt` | 13 例（含生产路径真写盘） |
| 测试 | `feature/settings/src/androidTest/.../PairedDeviceScreenTest.kt` | 9 例 Compose 断言 |
| 向量 | `core/common/src/test/resources/pairing/paired-device-vectors.json` | 36670 B / 17 根键 / sha256 `4afe2032`…`55db8b` |

### 15.3 数据模型与规范序

六字段与 Windows `PairedDevice` 逐字对应：`deviceId` / `displayName`（缺省空串）/ `fingerprint`（64 位小写 hex）/ `pairedAtEpochMs` / `lastSeenEpochMs` / `sourceCount`。

规范序是 `deviceId` 先按 `WireText.compareAsciiIgnoreCase` 再按 `compareTo`（等价于 Windows 的 `StringComparer.OrdinalIgnoreCase` → `StringComparer.Ordinal`）。
排序只在 `getAll()` 出口做一次：磁盘里的 `devices` 数组保持**写入顺序**，读回来再排序 —— 这一点在契约用例里被专门钉住（首版用例拿写入顺序去比 `GetAll()` 结果，红了一次）。

`create()` 的归一化与 Windows 一致：`deviceId` / `displayName` 先 `WireText.trim`，指纹 `lowercaseAscii`，`displayName` 超 128 字符截断，`sourceCount` 负数归 0，`pairedAtEpochMs` 缺省取 `nowEpochMs`。

### 15.4 12 条原因码 + 5 条存储码

原因码顺序与 Windows `PairedDeviceReasons.All` 完全相同（声明顺序与 `ALL` 顺序不同这件事也照抄，因为 UI 只消费 `ALL`）：

| # | 码 | 语义 |
| --- | --- | --- |
| 1 | `device-fingerprint-new` | 本机第一次见到这个设备 |
| 2 | `device-fingerprint-match` | 指纹与本机记录一致 |
| 3 | `device-fingerprint-mismatch` | 指纹不一致，**绝不覆盖已钉指纹** |
| 4 | `device-identity-missing` | 设备标识为空 |
| 5 | `device-fingerprint-missing` | 指纹为空或不是 64 位 hex |
| 6 | `paired-device-registered` | 已记住该设备 |
| 7 | `paired-device-updated` | 已更新已有登记 |
| 8 | `paired-device-unchanged` | 没有任何变化，不写盘 |
| 9 | `paired-device-limit-reached` | 容量（缺省 32）已满 |
| 10 | `paired-store-unreadable` | 档案读不出来 |
| 11 | `paired-device-forgotten` | 已移除该设备 |
| 12 | `paired-device-unknown` | 本机没有这个设备的记录 |

存储码 5 条：`paired-malformed-json` / `paired-unsupported-version` / `paired-store-unreadable` / `paired-device-limit-reached` / `paired-store-write-failed`。
注意 `paired-device-limit-reached` 同时出现在两套里（原因码与存储码同名同义），`PairedDeviceRegistry.describeStoreFailure` 负责把存储异常折回原因码：`LIMIT_REACHED` 原样，其余一律 `STORE_UNREADABLE`。

### 15.5 磁盘格式（逐字节复刻）

文件名逐字同 Windows：`paired-devices.json`；Android 目录是 `filesDir/paired_devices`（下划线是 Android 惯例），Windows 是 `%LOCALAPPDATA%/MediaHub`。
坏档隔离名也逐字同：`paired-devices.corrupt-yyyyMMddHHmmss[-n].json`。

字节格式由 `PairedDeviceDocument` 手工复刻 `Utf8JsonWriter{Indented=true}` 的行为，与 Windows 逐字节相等：

- 换行 CRLF、2 空格缩进、**无尾随换行**；
- 根键序 `version` → `devices`，每 device 键序 `deviceId` / `displayName` / `fingerprint` / `pairedAtEpochMs` / `lastSeenEpochMs` / `sourceCount`；
- ASCII 里只有 8 个字符被转义：双引号 → `\u0022`、`&` → `\u0026`、单引号 → `\u0027`、`+` → `\u002B`、`<` → `\u003C`、`>` → `\u003E`、反斜杠 → `\\`、反引号 → `\u0060`；
- 控制字符 08/09/0A/0C/0D 用短转义，其余 < 0x20 与全部 ≥ 0x7F 用大写 `\uXXXX`；SMP 字符写代理对两段；
- 空清单写 `"devices": []`（38 B）。

写盘是原子三拍：写 `.tmp` → 目标已存在时先复制 `.bak` → `Files.move(REPLACE_EXISTING)`，`finally` 删 tmp；任何一步失败都把内存清单回滚到写前状态并重新抛出（与 Windows 的「写失败不留半份」同义）。

### 15.6 读永不抛：隔离策略与一个 Windows 真缺陷

读路径的契约是「永不抛」：文件不存在 → 空清单；解码拒绝 → 把坏文件改名隔离（`paired-devices.corrupt-*`）并降级空清单；IO 不可读 → `loadError = paired-store-unreadable` 且 `writable = false`，此后的 `save` / `forget` 直接抛 `IOException(loadError)` 而不是悄悄写坏。

**本轮修掉的 Windows 真缺陷**：`FilePairedDeviceStore.LoadLocked()` 原本直接对 `version` 元素调 `JsonElement.TryGetInt32`，当 `value.ValueKind` 不是 `Number` 时该方法抛 `InvalidOperationException`（不是返回 false）—— 这违反「读永不抛」，一个 `"version": "1"` 的手改档案就能让整页崩掉。
修法是先判 `versionElement.ValueKind != JsonValueKind.Number` 再走 `Quarantine(MalformedJson)`，与其它畸形分支同路。文件 14244 B。

### 15.7 展示层文案

展示层是纯函数 `PairedDeviceListPresenter`，与 Windows `PairedDeviceListView` 同形（行十个字段、视图六个字段 + `isEmpty` / `isFull` / `count`）：

| 常量 / 方法 | 值 |
| --- | --- |
| `EMPTY_TEXT` | 还没有配对过的设备。 |
| `TOFU_TEXT` | 首次配对时无法验证对方身份：指纹只用来识别「上次那台是不是同一台」，不代表对方可信。 |
| `countText(n)` | 已配对 N 台 |
| `capacityText(c)` | 已登记 C 台，已达上限：新的设备不会被登记，请先忘记一台不再使用的设备。 |
| 行 `summaryLine` | name · 指纹 …尾8 · 首次配对 t · 最后相见 t · N 个来源 |
| `forgetLabel` | 忘记 + name |

行里的 `displayName` 是**解析后**名字（无名字退回 `deviceId` 前 8 字符）—— 这一点也是用例逼出来的：首版直接透传原始值，
`two-devices` 的第 2 行在 Windows 是 `abc`、在 Kotlin 是空串，红在 `expected:<[abc]> but was:<[]>`。

### 15.8 页面与「忘记」两段式

页面结构照 `PairingLinkScreen` 的范式：`PairedDeviceRoute` 在 `remember` 里构造控制器（存储出口用 `LocalContext` 的 `filesDir`），无状态 `PairedDeviceScreen` 只吃 state + 回调。
入口是设置页「同步与备份」区第 4 个按钮 `paired.entry`（「已配对设备」），页内托管、返回即回设置列表 —— 与 `pairing.entry` 同形。

「忘记」是两段式，语义逐字对齐 Windows `SyncPairingPresenter`：

1. `askForget(deviceId)` → 只**组提示**，一个字节都不删；提示文案 = `将忘记「name」（指纹 …尾8）。` + 不删除对端的说明；
2. `confirmForget()` → 先清 pending 再删；删成功 → 「已忘记该设备」，删失败/不存在 → 「本机没有这台设备的登记」+ 原因码 `paired-device-unknown`；
3. `cancelForget()` → **取消本身就是回执**（「取消」/「没有删除任何登记。」），不是静默关闭。

控制器把「读不到存储」折成本层唯一一条前置码 `paired-device-storage-unavailable`（不是跨机五集码，也不进 core 的十二码），
用于「没有存储出口（预览态/未接线）」与「读档案抛 IOException」两种情形，页面显示 `UNAVAILABLE` 区块而不是空白列表。

### 15.9 跨端契约证据

黄金向量 `paired-device-vectors.json`（36670 B / 17 根键）由临时 net10.0 生成器 `D:/mh-verify/paired-device-vectors/Program.cs`（520 行，`ProjectReference` 指向权威 `MediaHub.Sync.csproj`）产出，两边逐字节同一份：

`generator` / `windowsSource` / `frozenNowEpochMs`（1792800000000）/ `reasonCodes` / `storeReasonCodes` / `stateLabels` / `describeReasonCases` / `createCases` / `verifyCases` / `registerCases` / `capacityCases` / `forgetCases` / `describeCases` / `presenterCases` / `documentCases` / `loadCases` / `writeRejects`。

两端各 14 例孪生用例逐条消费它（Kotlin `PairedDeviceContractTest` 14 `@Test`、C# `PairedDeviceContractTests` 14 `[Fact]`，同名同义）：

- 12 条原因码与 5 条存储码同名同序；12 条中文文案逐字；
- `createCases` 的归一化（trim / lowercase / 截断 / 负数 / 缺省时间戳）；
- `verifyCases` / `registerCases` / `capacityCases` / `forgetCases` 的判定与副作用（各自断言写盘次数）；
- `documentCases`：同一份设备清单在两端写出的字节**长度、sha256、base64 全等**；
- `loadCases`：畸形 / 版本不符 / 容量满 / 只读各自的降级路径；
- `writeRejects`：空 deviceId、容量满的写入拒绝。

三条用例暴露的真缺陷：

1. Windows `LoadLocked()` 的 `TryGetInt32` 非 Number 抛异常（见 15.6，已修）；
2. Kotlin `PairedDeviceListPresenter.buildRow` 的 `displayName` 用原始值而非解析后名字（见 15.7，已修）；
3. 用例自身的两处断言错：
   - `describeCases` 里 `describe` 与 `describeSuffix` **不同时存在**（`peer-clock-ahead` 只有 suffix），首版把它们当同一字段取 ⇒ `KeyNotFoundException`；
   - suffix 必须用 `Contains` 而不是 `EndsWith`（时钟偏移提示位于尾部「 · 来源 N 个」之前）；
   - `DocumentCasesAreWrittenByteIdentically` 拿写入顺序比 `GetAll()` 的规范序 ⇒ 重开比较前必须先排序。

### 15.10 本机实跑与门禁

| 项 | 结果 |
| --- | --- |
| `core/common` JVM | **46 套件 / 636 例 / 0 失败 / 11 跳过**（622 → 636，+14 契约用例） |
| `core/database` JVM | **20 套件 / 192 例 / 0 失败 / 12 跳过**（未变） |
| `feature/settings` JVM | **24 套件 / 367 例 / 0 失败 / 0 跳过**（354 → 367，+13 控制器用例） |
| `:feature:settings:assembleDebugAndroidTest` | **BUILD SUCCESSFUL**（+9 例 androidTest） |
| C# `MediaHub.Tests`（W6 树） | **1628 通过 / 0 失败 / 0 跳过**（1614 → 1628，+14 契约用例），`dotnet test … -p:Platform=x64` |
| `--required-suite` | **51 → 53**（+`PairedDeviceContractTest`、+`PairedDeviceControllerTest`）；`.github/scripts/tests` 54 例 OK |

三模块是一条 gradle 命令串行跑的（BUILD SUCCESSFUL in 1m46s），全程没有并发 dotnet —— 这是 W6-49 那次 HotSpot `arena.cpp:168` 崩溃留下的硬纪律。

### 15.11 未做项

- **登记新设备**：本页只读清单 + 解绑；真要登记得先有「走完握手拿到对端指纹」的路径（W6-59b 只拉邀请，没有配对完成态）。
- **配对页与本页没有联动**：`pairing.entry` 拉回来的邀请不会自动落成登记，两页之间不共享状态。
- **真机 instrumentation DEVICE_UNVERIFIED**：9 例 Compose 断言只到编译 + CI 模拟器（API 32 / 36）。
- **真实两机配对仍 NOT_RUN**：需要第二台机器 + 用户同意开防火墙。
- **Windows 侧 GUI 未见**：`SyncPage` 的已配对清单在本轮没有新的 GUI 证据，仍是 `GUI_UNVERIFIED`。


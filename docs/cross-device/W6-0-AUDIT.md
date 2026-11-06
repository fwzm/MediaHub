# W6-0 双端审计与字段映射

- 分支：`feature/cross-device-source-sync`（base `feature/windows-client` @ `3bcd896`）
- 审计日期：2026-10-14
- 事实来源：实际仓库文件与 `git`/`gh` 输出。**本文件中的每条结论都标了出处路径**；无法确认的写 UNKNOWN。

## 1. 基线核验（实际执行，未沿用交接记录）

| 项 | 实测值 |
| --- | --- |
| Windows HEAD | `3bcd8961d76a3784289781252c4219b7d8c93e16` == `origin/feature/windows-client` |
| `origin/main` | `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`（未改动） |
| PR #33 | OPEN / Draft / MERGEABLE / base=`main` |
| 工作区 | clean（0 项） |
| Android 代码位置 | **仓库根**（`app/ core/ feature/ provider/ metadata/ player/`），**不是** `android/` 子目录 |
| 新增远端分支 | `feature/m9-mpv-local-bridge`、`integrate/m0a-joint`、`feature/m0a2-*`、`feature/m0a3-*`、`docs/a4-convergence-closeout-2026-10-14` 等（**本任务不触碰**） |

## 2. Android 侧现状

### 2.1 模块结构
- `core/database`、`core/security`、`core/common`、`core/model`、`core/network`
- `provider/`：aliyun、api、base、baidu、china-mobile、emby、jellyfin、local、quark、smb、tianyi、webdav
- `feature/`：detail、home、library、player、search、server、settings

### 2.2 Room 三张核心表（`core/database/.../entity/`）

**`ServerEntity` → `servers`**（注释明确：*敏感信息（Token 等）绝不在此存储*）
`id`(PK)、`name`、`type`、`username?`、`note?`、`icon?`、`isDefault`、`sortOrder`、`createdAtEpochMs`、`lastConnectedAtEpochMs?`、`lastError?`

**`ServerEndpointEntity` → `server_endpoints`**
可迁移：`id`(PK)、`serverId`(FK)、`name`、`url`、`isPrimary`、`enabled`、`sortOrder`
**设备本地（不同步）**：`lastLatencyMs`、`lastError`、`lastTestedAtEpochMs`、`lastApiLatencyMs`、`lastMediaFirstByteMs`、`lastMediaThroughputMbps`、`lastProtocol`、`lastSupportsRange`、`lastHttpCode`

**`AccountEntity` → `accounts`**：`id`(PK)、`serverId`(FK, CASCADE)、`userId?`、`displayName`、`authState`、`authenticatedAtEpochMs?`

### 2.3 秘密与凭据（`core/security/`）
- `SecretStorage`：唯一抽象（put/get/remove/contains），**注释规定业务代码不得绕过它明文落盘**
- `KeystoreSecretStorage`：唯一实现，Android Keystore
- `CredentialVault`（ADR-016）：`keyFor = "cred:$serverId:${kind.name}"`，kind ∈ PASSWORD / WEBDAV_CREDENTIAL / API_KEY / REFRESH_TOKEN / COOKIE / CLIENT_SECRET
  - **策略原文**：Emby/Jellyfin 登录后**仅存 Token，不保存密码**；WebDAV/SMB 经保险库存密码；云盘存 OAuth refresh / Cookie
- `TokenStore`：会话令牌

### 2.4 既有备份体系（`core/common/backup/`）—— **W6 的重要复用基础**
- `BackupFileFormat`：外层 `Envelope`(magic, formatVersion, kdfAlgorithm, kdfSaltB64, kdfIterations, kdfKeyLengthBits, nonceB64, ciphertextB64) + 内层 `Manifest`(formatVersion, minimumReaderVersion, appVersion, createdAtEpochMs, includedSections, recordCounts)
  - 设计约定：**服务器地址不裸露在外层；内层不携带凭据**
- `BackupDtos`：**`ServerDto` 已含 `backupId`** —— 即跨设备稳定标识，并带 `name/type/username/note/isDefault/sortOrder/endpoints`
- `EndpointDto`：只含 `name/url/isPrimary/enabled/sortOrder` —— **天然排除了上述全部遥测字段**
- `BackupRepository.kt:658`：恢复时 `id = UUID.randomUUID().toString(), serverId = vs.backupId` —— 本地 id 重新生成，**`backupId` 才是持久身份**
- 相关测试：`BackupCryptoTimingTest`、`BackupUserFlowTest`、`RestoreProcessDeathTest`（androidTest）
- `BackupUrlGuard.kt`、`BackupSerializer.kt`、`BackupCrypto.kt`

> **结论：Android 的既有备份 DTO 已覆盖 W6 迁移字段的绝大部分**，且已经解决了"稳定跨设备 ID"和"设备本地字段剥离"两个最容易做错的点。W6 应**复用其字段划分与安全规则**，但不复用其文件格式（见 ADR-S001）。

### 2.5 地址规范化
`core/common/ServerAddressIdentity.kt`：scheme 小写、host 小写、**显式写出端口**（`-1` 才省略）、path 去尾 `/`、**保留 query**。

## 3. Windows 侧现状（已有，需接线）

- `ServerManager` / `SourceRegistry`：来源增删改、探测、恢复；Provider 工厂注册 Local/Emby/Jellyfin/WebDav
- `DpapiCredentialStore`（`MediaHub.Net/Security/`）：CurrentUser 作用域，版本化 + 原子写 + 损坏隔离 + 异账户识别
- `ServerUrlNormalizer`：scheme/host 小写、**仅显式非默认端口保留**、含 Emby `/emby` 后缀去重
- `NetworkPolicy` 每源隔离 + `OriginScopedCredentialHandler` 逐跳凭据作用域
- 已有 GUI 入口：媒体源页（添加/测试/重新登录/删除）、设置页

## 4. 双端字段映射

### 4.1 可跨设备迁移

| 语义 | Android | Windows | 备注 |
| --- | --- | --- | --- |
| 跨设备稳定 ID | `ServerDto.backupId`（已存在） | **缺失，需新增** | 映射为 `syncSourceId` |
| 显示名 | `ServerEntity.name` | `ServerEntry.DisplayName` | 直接 |
| 类型 | `ServerEntity.type` = `ServerType.name` | `ServerEntry.Type` | **枚举不一致，见 §5.1** |
| 账号身份 | `ServerEntity.username` + `AccountEntity.userId/displayName` | `ServerEntry.UserName` + 凭据 `UserId` | `userId` 需按来源类型决定是否迁移 |
| 端点 | `EndpointDto(name,url,isPrimary,enabled,sortOrder)` | `ServerEntry.Url`（**单端点**） | **Windows 目前只存一个 URL，见 §5.4** |

### 4.2 设备本地（禁止同步）
Android endpoint 遥测九项（上表 §2.2）、Windows 的 `WindowPlacement`/`LocalFolders`/`AutoHideControls` 等、两端全部代理设置、GPU/解码器/音频设备/缓存目录/局域网端口。

### 4.3 禁止同步
DPAPI 密文、Android Keystore 密文、`CredentialVault` 的 `cred:*` 键值原文、数据库文件、设备唯一 ID、任何设备主密钥。

### 4.4 凭据的现实边界（源自 ADR-016，直接决定验收口径）
| 来源类型 | Android 实际持有什么 | 能否"免重复登录" |
| --- | --- | --- |
| Emby / Jellyfin | **仅 Token，无密码** | 取决于 Token 是否设备绑定 —— **必须实测**，不可假定 |
| WebDAV / SMB | 密码（CredentialVault） | 可迁移密码，导入后仍须真实认证验证 |
| 云盘（阿里/百度/夸克/移动/天翼） | OAuth refresh / Cookie | **Windows 无对应 Provider → 导入即标记"暂不支持"** |
| Plex | 需查（UNKNOWN） | UNKNOWN |
| LOCAL | 本地路径 | **不同步**（设备本地） |

## 5. 已识别的硬问题（必须在 W6-1 解决，否则会做错）

### 5.1 两端 `ServerType` 枚举不一致 —— 头号风险
- Android 13 值：`EMBY JELLYFIN PLEX FN_NAS WEBDAV SMB LOCAL ALIYUN_DRIVE BAIDU_DRIVE QUARK_DRIVE CHINA_MOBILE_CLOUD TIANYI_CLOUD`
- Windows 7 值：`Unknown Emby Jellyfin Plex WebDav Local Smb`
- **交集仅 6 个，且大小写不同**（`WEBDAV`↔`WebDav`、`LOCAL`↔`Local`、`SMB`↔`Smb`）
- → **禁止用枚举名直接匹配**。必须定义版本化、显式、可测的映射表；Android 独有 5 个 + `FN_NAS` 在 Windows 侧应导入为"已保存但暂不支持"并明确展示，**而不是丢弃或伪装成支持**。

### 5.2 两端地址规范化规则不同 → 幂等判定会误判
- Android：显式写出端口（含默认端口）
- Windows：省略默认端口
- 同一台 `http://host:80/` 在两端得到不同规范串，会被判成两个来源
- → **同步层需要自己的、与两端 normalizer 都不同的"同步用规范化"**，并对其写测试；不能直接复用一个端的实现。

### 5.3 Windows 侧缺少跨设备稳定 ID
`ServerEntry.ServerId` 是本地派生（`type:remoteIdOrHost`），会随地址变化而变。W6 需要在 Windows 侧新增独立的 `syncSourceId`（UUID），与本地 `ServerId` 解耦。

### 5.4 Windows 端点是单 URL
Android 有多端点与主/备语义，Windows `ServerEntry` 只有一个 `Url`。
→ 首版策略：导入时取 `isPrimary == true` 的端点（无则取 `sortOrder` 最小者），**其余端点记录为"未导入"并在预览中显式列出**，不静默丢弃。

## 6. 未确认项（UNKNOWN，禁止当成已知）

- Android `ServerEntity.id` 的生成规则：**未在审计中定位到创建点**（只见恢复路径用 `UUID.randomUUID()`）。W6-1 必须先确认它是否已稳定，还是也依赖 `backupId`。
- Plex 在 Android 侧的凭据形态（Token 还是密码）—— 决定 Plex 能否免登录。
- Android `AuthState` 的全部取值与语义。
- Windows 侧是否已有任何形式的多端点概念（审计未见）。
- 两端 `NetworkPolicy` 的字段是否完全同构（影响"代理策略不同步"这一条的边界）。

## 7. 状态

| 项 | 状态 |
| --- | --- |
| W6-0 基线核验 | **EXECUTED_PASS** |
| W6-0 双端审计与字段映射 | **EXECUTED_PASS**（本文件） |
| W6-1 schema 与双端 Adapter | **NOT_STARTED** |
| W6-2 QR 配对与首次迁移 | **NOT_STARTED** |
| W6-3 双向增量与冲突 | **NOT_STARTED** |
| W6-4 离线归档与真机验收 | **NOT_STARTED** |
| W6-5 PR/CI/交接 | **NOT_STARTED** |

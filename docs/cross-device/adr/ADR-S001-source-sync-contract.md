# ADR-S001 —— 跨设备来源同步的数据契约与安全边界

- 状态：**已提议**（W6-0 产出，待实现验证后转"已接受"）
- 日期：2026-10-14
- 前置：[W6-0-AUDIT.md](../W6-0-AUDIT.md)
- 相关：[ADR-W006](../../../windows/docs/adr/ADR-W006-media-relay.md)、Android ADR-016（凭据保险库）

## 问题

用户的手机里已经配置好多个媒体源，换到电脑要逐个手输。W6 要让这件事变成"扫一次码"。
难点不在传输，在**身份、幂等与凭据的现实边界**。

## 决策

### D1：定义独立的 `SourceSyncEnvelope`，**不复用** `.mhbackup`

Android 已有 `.mhbackup`（`core/common/backup/`），字段划分与安全规则**应当复用**，
但**文件格式不复用**：

| 维度 | `.mhbackup` | `SourceSyncEnvelope` |
| --- | --- | --- |
| 目的 | 单机灾备恢复 | 跨设备迁移 + 增量同步 |
| 身份 | `backupId`（一次性） | `syncSourceId`（**持久、跨设备、双向**） |
| 变更语义 | 全量快照 | 需要 deletions/墓碑、recordRevision、冲突 |
| 方向 | 单向恢复 | **双向** |

复用 `.mhbackup` 会让"恢复"和"同步"两种语义挤在一个版本号里，之后任何一方演进都会破坏另一方。
**安全规则（白名单字段、内层不含凭据、地址不裸奔）全部继承。**

### D2：schema v1 的字段

```
SourceSyncEnvelope {
  schemaVersion: int                  // 1
  minimumReaderVersion: int           // 前向兼容的下限
  deviceId: string                    // 发送方设备标识（非秘密）
  snapshotId: string                  // UUID，幂等去重键
  createdAtEpochMs: long
  capabilities: { canSendSecrets: bool, sourceTypes: [string] }
  sources: [ SourceRecord ]
  deletions: [ Tombstone ]
}

SourceRecord {
  syncSourceId: string                // UUID，跨设备持久身份 —— 幂等的唯一依据
  sourceType: string                  // 见 D3，不用枚举名
  displayName: string
  accountScope: string?               // 用户名/账号域，参与"同一服务器不同用户必须分开"
  endpoints: [ { url, isPrimary, enabled, sortOrder } ]
  recordRevision: long                // 记录级版本，冲突判定用
  fieldVersion: int
  secrets: SecretsBlock?              // 仅当用户显式选择迁移，且经加密通道
}

Tombstone { syncSourceId, deletedAtEpochMs, recordRevision }
```

### D3：`sourceType` 用**同步层自有**的稳定字符串，且显式映射

两端枚举不一致（Android 13 值 / Windows 7 值，命名大小写还不同，见审计 §5.1）。
因此**禁止**传输两端的枚举名。同步层定义自己的标识：

`emby | jellyfin | plex | webdav | smb | local | fn-nas | aliyun-drive | baidu-drive | quark-drive | china-mobile-cloud | tianyi-cloud`

双端各自的 Adapter 负责 本地枚举 ↔ 该字符串。映射表**必须带测试**，且**未知值不得丢弃**：
导入端遇到不认识的 `sourceType` 时，保存为"已保存但暂不支持"并在 UI 明示 —— 静默丢弃会让用户在
另一端看到"同步成功"却少了东西，这是本任务最不能犯的错。

### D4：同步层需要**自己的**地址规范化，不依赖任一端的实现

两端 normalizer 对默认端口的处理不同（审计 §5.2），直接复用会导致同一服务器被判成两个来源。
同步层定义 `syncNormalize(url)`：scheme 小写、host 小写、**端口始终显式写出**（含默认端口）、
path 去尾斜杠、**丢弃 query**（query 常含会话参数）。对其单独写测试，并用两端真实样例做交叉验证。

### D5：幂等与冲突

- 同一 `syncSourceId` → 同一来源，直接更新。
- 无 `syncSourceId` 的历史记录 → 一次性映射表迁移。
- 仅当 **sourceType + syncNormalize(endpoint) + accountScope** 三者一致且**无歧义**时才建议合并；
  **同服务器不同用户必须分开**。
- 冲突按来源逐条呈现，由用户选择，**默认禁止静默覆盖用户凭据与已有设置**。
- **禁止用不同设备的本地墙上时间做 last-write-wins** —— 用 `recordRevision` + 显式冲突。

### D6：安全边界（不可协商）

1. **二维码只携带**：临时地址、一次性配对挑战、公钥指纹。**禁止**携带密码、Token、长期密钥。
2. 传输用 TLS 1.3 + 证书公钥指纹钉扎，或经审计的成熟握手 —— **不自创加密协议**。
3. 配对：短时有效、**一次性消耗**、两端显示设备身份并需用户确认。
4. Windows 导入后**立即用当前用户的 DPAPI 重新封存**；不落明文、不入日志、不入 UI 诊断。
5. Android 只经 `SecretStorage` 读秘密，不绕过 Keystore。
6. 不因同步关闭证书校验、不放宽重定向凭据作用域、不绕过每源 `NetworkPolicy`。
7. 离线归档：AES-256-GCM + Argon2id，随机盐与 nonce，**默认只含非敏感配置**，
   含秘密必须用户显式选择并输入强口令。
8. 临时配对监听：仅用户许可的私有接口、限来源与范围、**首次配对结束即关闭**，
   不常驻端口、不装后台服务。

### D7：迁移后的认证必须**逐源真实验证**

Android 的 Emby/Jellyfin **只存 Token 不存密码**（ADR-016）。Token 可能设备绑定。
因此导入后必须对每个来源独立发起真实认证，结果是三种之一：
**可直接用 / 需要重新登录 / 暂不支持**。
**严禁**把"同步成功"直接显示成"已登录"。

## 后果

- 正向：两端各自演进不影响对方；身份与幂等有唯一依据；凭据边界写死在契约里。
- 代价：多一层 Adapter 与映射测试；Windows 需新增 `syncSourceId` 存储。
- 未决：Windows 单端点 vs Android 多端点的首版策略（审计 §5.4）需在 W6-1 定稿。

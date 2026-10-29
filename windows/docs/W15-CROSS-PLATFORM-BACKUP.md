# W15 跨平台备份容器（Windows ↔ Android `.mhbackup` 互通）

> 状态：容器层 **EXECUTED_PASS**（Windows 1754/0、Android core/common 666/0、双向跨语言向量全绿）；
> 备份 CLI、设置页入口、WebDAV 上传仍 **NOT_IMPLEMENTED**；端到端「手机备份 → 电脑还原仍可用」尚未接线 **NOT_RUN**。

## 1 动机与范围

用户口径（2026-10-30）：两台机器联调**暂时不做**；同步功能改为**本地备份 / 还原，或 WebDAV 备份 / 还原**，并且
**电脑与手机导出的文件必须互通** —— 手机备份出来的文件还原到电脑仍可正常使用，反之亦然。

本轮只做**容器层**：让 Windows 写出 Android 读得懂、Android 写出 Windows 读得懂的 `.mhbackup`。
备份的**内容层**（还原计划器、来源/进度落库、设置页入口、CLI）与**传输层**（WebDAV 上传）不在本轮。

## 2 格式契约（逐字段对齐 Android 权威实现）

| 项 | 值 | 备注 |
| --- | --- | --- |
| 外层容器 | JSON `Envelope`：`magic` / `formatVersion` / `kdfAlgorithm` / `kdfSaltB64` / `kdfIterations` / `kdfKeyLengthBits` / `nonceB64` / `ciphertextB64` | 字段名与顺序均与 Android 一致 |
| 魔数 / 版本 | `MHBK` / `FORMAT_VERSION=2`、`MINIMUM_READER_VERSION=2` | 版本 > 2 ⇒ `VersionTooNew` |
| 密钥派生 | PBKDF2-HMAC-SHA256，600 000 次，salt 32 B，密钥 256 bit | 迭代数允许 10 000–2 000 000（读取时） |
| 加密 | AES-256-GCM，nonce 12 B，tag 128 bit | **密文 = ciphertext‖tag** |
| AAD | UTF-8 的 `magic│formatVersion│kdfAlgorithm│kdfIterations` | 头部参数被改 ⇒ 解密失败 |
| 明文 | `Payload`：`manifest` + `servers` / `progress` / `favorites` / `preferences` | 白名单，**不含密码 / Token / Cookie / DeviceId / 临时 URL** |
| 身份 | `(type, 规范化主地址, username)` | 地址规范化保留显式写出的默认端口 |
| 上限 | 文件 10 MiB、源 1000、进度 20000、端点 64/源、收藏 5000、收藏标题 512 字符 | 导出与导入两侧都校验 |

两个**必须**留意：

1. Java `Cipher.doFinal` 的输出布局是 `ciphertext‖tag`（tag 追加在末尾）；.NET `AesGcm` 把密文与 tag **分开输出**，
   所以 Windows 侧在 `Encrypt` 里显式缝合、在 `Decrypt` 里按末 16 字节切出 tag —— 这是跨语言能否互解的第一道坎。
2. AAD 只绑**头参数**，不绑明文：所以「改 formatVersion 再解密」必然失败（`AuthenticationFailed`），而不是「用新版本读出旧明文」。

导入失败分类（与 Android 同名）：`NotABackupFile` / `VersionTooNew` / `Corrupted` / `AuthenticationFailed`。
错口令与文件被篡改**同一分类**，不区分 —— 否则容器就成了口令预言机。

## 3 Windows 实现（`windows/src/MediaHub.Backup`，零 NuGet 依赖）

| 文件 | 职责 |
| --- | --- |
| `MhBackupCrypto.cs` | 常量、`DeriveKey`（`Rfc2898DeriveBytes.Pbkdf2`，口令字节在 finally 清零）、`AadFor`、`Encrypt`（缝合 tag）、`Decrypt`（切 tag） |
| `MhBackupEnvelope.cs` | `Envelope` / `Manifest` record 与 JSON 选项（不缩进、`UnsafeRelaxedJsonEscaping` 保证中文不转义） |
| `MhBackupDto.cs` | 白名单 DTO 与默认值逐项对齐 Android（`0xFFFFFFFF`、`1f`、`0.08f`、`0.5f/5f/2f` …）+ `PayloadCodec` |
| `MhBackupUrlGuard.cs` | 凭据 query 键黑名单（token/api_key/… 37 项，键做 URL 解码后小写比较）、userinfo 拒绝、scheme/host/port 校验、身份规范化 |
| `MhBackupSerializer.cs` | `Export` / `Import` 的完整校验顺序与中文原因串（体积 → 魔数 → 外层 → 派生 → 解密 → 明文 → 内层 → 版本相等） |

`MediaHub.Backup` 通过 `ProjectReference` 被 `MediaHub.Tests` 引用，因此 CI 不需要把它加进 `.sln` 也会构建并测试。

## 4 跨语言黄金向量

- 生成器在仓库外：`D:/mh-verify/backup-vectors-gen`（net10.0 console，`ProjectReference` 指向 `MediaHub.Backup`），
  产物 `D:/mh-verify/backup-vectors.json`。
- **两份副本必须同哈希**（漏拷一份会让问 Windows 套件 `KeyNotFound`）：
  - `core/common/src/test/resources/backup/backup-vectors.json`
  - `windows/tests/assets/backup-vectors.json`
  - 当前 sha256 `d581fe287c8c747391db6a75024892296f0ef459c1eeafc421da9e29e9d48cc9`，31 617 B。
- `cryptoCase`：固定 salt=0..31、nonce=0..11、口令 `跨平台备份-测试口令-2026`、600 000 次迭代 —— 两端必须算出**同一条密文**（2 076 字符）。
- `cases`（8 条）：`windows-export-ok` / `fixed-salt-ok` / `wrong-password` / `downgraded-header` / `newer-version` /
  `salt-too-short` / `nonce-not-base64` / `not-a-backup`，每条钉期望分类（必需时钉版本号与原因片段）。
- `androidExport`：由 Kotlin 侧写出的反向容器（Android 编码 + 加密），Windows 侧解出同字段并**重算密文**比对。
  生成器重跑时会把已有 `androidExport` 原样带回，不会丢反向证据。

## 5 验证与证据

复跑：

- Windows：`dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj --no-restore --filter FullyQualifiedName~Backup`
- Android：`gradlew.bat :core:common:testDebugUnitTest --tests "*BackupWindowsContractTest*"`

实测计数：

- `MediaHub.Tests` **1754 / 0**（备份容器 23 例 + 跨语言向量 5 例；App 之外无改动）
- `core:common` **666 / 0**（11 skipped 为环境门控套件），其中 `BackupWindowsContractTest` 6 例

三条负向对照（改坏点 → 红 → 逐字节还原并复核 sha256）：

| 对照 | 改坏点 | 结果 | 还原 sha256 |
| --- | --- | --- | --- |
| A | Windows `AadFor` 多拼一段 | 向量 5 例红（自洽回环仍绿 —— 只改一端时，同端加解密仍一致） | `56a2bf4d…ce2a25` |
| B | Kotlin `aadFor` 多拼一段 | 对照 6 例中 5 红（写入器自检仍 Ok） | `5830efd2…388e14` |
| C | Windows 跳过「外层版本 > 2」检查 | 向量分类 1 例 + 容器版本用例 1 例红 | `789cc4e8…8b1777` |

**B 的教训**：单端自洽（自己写、自己读、自检 Ok）**不能**证明互通；只有跨语言向量能抓出「两端各改一处、各自都自洽」的分叉。

## 6 仍未做（如实）

- 备份 CLI（`export` / `import` dry-run、`--json`、退出码）—— NOT_IMPLEMENTED
- Windows 设置页「备份与还原」入口（口令框 → 预览 → 确认）—— NOT_IMPLEMENTED
- Windows 侧真正读写本机来源/进度/收藏（还原计划器：`new` / `identical` / `conflict` / `orphan` 预览零写入）—— NOT_IMPLEMENTED
- WebDAV 上传（当前 WebDAV 只**读**）—— NOT_IMPLEMENTED
- Android 恢复计划器与 Windows 反向对照、真机与真 GUI —— DEVICE_UNVERIFIED / GUI_UNVERIFIED
- 口令强度无最小长度约束（沿用 Android 口径）；容器未做外部安全审计

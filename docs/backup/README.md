# 本地备份与还原（PR #18）

正式入口为“设置 → 同步与备份”。文件经 SAF 创建或打开；取消创建不会显示导出成功，空输出流、读写或关闭失败均为失败。密码输入仅短暂保留内存，提交后清空页面文本，协程完成、拒绝重入及启动前取消也擦除传入的 CharArray。

## 文件契约

- v1 envelope：MHBK、PBKDF2-HMAC-SHA256（生产 600,000 次，读取范围 10,000–2,000,000）、32 字节 salt、12 字节 nonce、AES-256-GCM/128 位 tag。头部版本/KDF 参数绑定 AAD；错误密码和认证失败统一提示，不能区分为“确定密码错误”。
- 最大文件为 10 MiB；SAF 每次请求的字节数也受剩余上限约束，最多读到上限加 1 字节即拒绝。序列化/密码派生/加解密和文件读写均在 IO dispatcher。
- 导出也执行导入适用校验。section 与 recordCounts 必须完整相符；实际存在的 section 必须声明；重复源 ID、重复 `(serverId,itemId)`、非法字段/枚举均在预览前拒绝。最多 1,000 个源、每源 64 条线路、20,000 条进度，且仍受总字节上限约束。字幕缩放遵守现正式设置范围 0.6–2.0。
- 本地源可无 HTTP endpoint；导出不携带设备本地目录/SAF 授权，恢复描述符后需在本机重新选择/授权目录。网络源必须有合法 HTTP(S) 地址。地址的 user-info、解码后的敏感 query 参数及已知签名凭据字段被拒绝，错误不回显 URL 或载荷标识。
- 白名单包含源描述、非敏感账号标识、进度和播放偏好。Token、密码、Cookie、Authorization、DeviceId、PlaySessionId、临时播放/图片 URL、缓存和日志不进入导出；未包含真实来源的孤立进度在预览披露并不写入。

## 恢复语义与失败边界

MERGE 保留本机已有同 ID、同来源的源描述和偏好；进度按更新时间 newer-wins。同 ID 但类型、规范化主地址或原始账号不同属于冲突，本机源及其进度保留。新增导入默认源不能使本机出现多个默认源。既有相同来源的替换不强制全量登出。

有效地址按 Room 的线路 `sortOrder` 顺序和现有 enabled/primary 规则判定，不能使用备份数组的偶然顺序。有效候选在最前优先级并列且指向不同规范化地址时，导入和导出都拒绝；唯一有效主线路和同一规范化地址的并列仍允许。预览及未完成保护计划还会核对持久化后的身份是否稳定；旧保护计划若存在顺序不一致或歧义则保留日志、阻止自动重放，需人工处理，不重新解释原确认。

REPLACE_SELECTED 以备份中列出的源 ID 为所选范围，替换这些源及其关联进度；不删除未选择的本机源。UI 与 repository 都要求明确确认。策略变化重新生成预览并撤销旧确认。执行使用用户确认过的冻结计划；本机数据或偏好在预览后变化时拒绝执行，Room 写入事务内还会再次核对数据基线。

身份改变及新恢复 ID 的登录信息先失效，避免旧凭据流向恢复后的地址；结果中的清理数量表示处理过的媒体源数量，不声称这些源此前一定有有效登录。凭据删除需要同步持久化成功。TokenStore 的身份代际与认证提交互斥贯穿恢复/回滚；旧登录响应不能重新写入会话，旧恢复/登出结果不能清掉新会话。两 ProviderFactory 的独立 API/media client 在请求进入时检查所属身份，失效 handle 的后续请求不出网，恢复后创建的新 handle 可用；已进入网络的请求不承诺撤销。首页同时按身份和操作代际丢弃旧登录展示。

Room 事务只保证 Room 原子性。恢复前将完整本机 before/after image 和冻结决策加密到应用 noBackup 私有目录，使用设备 Keystore 密钥、GCM 认证、同步文件写与发布后读回校验。它是内部恢复保护材料，不是可导出的备份：保留完整本地字段用于回滚，不复制 TokenStore、CredentialVault 或 Provider 会话存储。原始地址和图片等本机字段完整保留且只写入加密保护文件；这些原始字符串可能自身含敏感参数，不能将保护 image 描述为已脱敏的可导出材料。

保护文件可靠保存后才能登记 PREPARING。随后执行凭据失效、Room 写入、DB_WRITTEN、偏好写入、PREFERENCES_APPLIED、COMPLETED。完成清理失败不将已完成恢复倒退为回滚。普通失败先持久化 ROLLING_BACK 再反向应用完整 image；已失效的登录信息不会恢复，用户需重新登录。

重启后仅在本机状态与冻结 before 或 after 一致时自动续作/回滚，重复恢复幂等。存在其他普通写入、损坏日志、失效密钥、损坏保护 image 或旧版日志缺完整保护材料时，保留现场并返回需要处理，禁止叠加新恢复。该保守策略不承诺在任意并发业务写入后自动合并回滚。`commit(false)` 使当前进程的日志状态不可信，需新进程重新读取磁盘。Android 可能将损坏 XML 加载为空 map，因此日志返回缺失之前还核对专属磁盘 XML，只有不存在的文件或有效空 map 才允许新恢复。

## 验证与协作

详细评论处置和证据见 [Agent B 复审记录](../reviews/pr18-agent-b-2026-09-09.md)。JVM/Robolectric、真实模拟器 SAF、重建持久化对象、实际 OS 进程终止、真机是不同证据类型。

`BackupUserFlowTest` 和 `RestoreProcessDeathTest` 只能对明确的任务专用模拟器按类/方法执行，并传 `backupAcceptance=isolated`。进程终止探针还要求 checkpoint：SNAPSHOT、PREPARING、INVALIDATED、DB_BEFORE_MARK、DB_WRITTEN、PREFERENCES_APPLIED、ROLLING_BACK 或 COMPLETED。先执行 `seedAndTerminate`，预期 instrumentation 因进程被杀而失败；随后新一次 `am instrument` 执行 `recoverInNewProcess`，核对 PID 确已改变。不能把前一次预期崩溃计作通过，不能无参数扫跑这两个显式探针。

在**新建的任务专用模拟器**安装本分支 Debug APK 和 androidTest APK 后，可用 `scripts/verify-backup-restore.ps1 -Serial emulator-5556 -OutputDirectory <证据目录> -IsolatedEmulator` 复跑正式 SAF 链路、8 个进程终止点和物理日志损坏分支。序列号仅是本次例子，复审者须传自己的专用模拟器；不得传正在使用的媒体/账号环境。脚本没有失败自动重试，首个非预期失败会停止并保留证据。

不对用户手机清数据，不访问用户影片。真机未执行就保持 DEVICE UNVERIFIED；模拟器结果不代表存储提供方兼容性、断电持久性或所有 Android/OEM 设备验收。

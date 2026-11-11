# Android 侧安全与鲁棒性回归（W6-J）

本轮以测试与文档为主；唯一的生产代码改动是修掉本轮新发现的 **W6J-1**（同一信封内重复 `syncSourceId` 会写两条，详见 §5）。三个被测面：配对契约（core:common/.../pairing）、配对传输客户端（同包 PairingTransportClient）、来源同步导入服务（core:database/.../sync/AndroidSourceSyncService）。

## 1. 结论先行

- 新增 **61 例**（配对契约 19 / 传输客户端 14 / 凭据面 6 / 导入服务 22），实跑 **61 绿、0 skipped**。
- 被钉住的四条安全边界：
  1. **坏信封零写入**：解码失败的路径连 `listLocalSources` 都不调（`listCalls == 0`），比"没写库"更强的判据。
  2. **未知 wire 类型永不动已有记录**：规划阶段先判类型再看本地匹配，因此未知类型既不会 CREATE 也不会 UPDATE 已绑定来源。
  3. **帧长度声明不触发分配**：`0x7FFFFFFF` 这类声明在读体之前就被拒（用例带 <1500 ms 的耗时上限）。
  4. **凭据不进异常/结果面**：口令样式串不出现在异常 message、`toString()`、结果对象里。
- 本轮查到 **1 个真缺陷 W6J-1**（同一信封内重复 `syncSourceId` 会写两条，第二条撞唯一索引后异常整封逃逸），已在同一轮修好并加了 5 条回归用例（真 Room 上也有 1 条），详见 §5。

## 2. 覆盖矩阵

### 2.1 配对契约恶意输入

文件：`core/common/src/test/kotlin/com/mediahub/core/common/pairing/PairingMaliciousInputTest.kt`（19 例）

| 威胁 | 用例 | 观测点 |
| --- | --- | --- |
| 非规范 base64 伪装成 challenge | non_canonical_base64_challenges_are_rejected | 去填充/插空格/非字母 → `bad-challenge` |
| challenge 长度错 | challenges_of_the_wrong_byte_length_are_rejected | 16 B、33 B 与 32 B 正向对照 |
| 指纹长度/字符集错 | fingerprint_length_and_alphabet_are_enforced | 62/66/非 hex 全拒 |
| 大写指纹 | uppercase_fingerprint_is_accepted_and_canonicalized_to_lowercase | 接受并规范成小写 |
| 缺字段 | every_required_string_field_missing_reports_missing_field | 4 个字段逐一 → `missing-field:<名>` |
| 字段类型错 | wrong_json_types_are_rejected_as_invalid_field | `invalid-field:<名>` |
| 版本/时效字段缺失 | missing_version_and_expiry_report_invalid_field | `invalid-field:schemaVersion` / `invalid-field:expiresAtEpochMs` |
| ephemeralUrl 带凭据/query/fragment | ephemeral_url_rejects_userinfo_query_fragment_and_non_http_schemes | 5 例坏 URL → `bad-url`，正向 1 例通过 |
| expires ≤ 0 / 已过期 | expires_must_be_positive | `invalid-field:expiresAtEpochMs` 与 `expired` 分开验 |
| 未来 schemaVersion | future_schema_versions_are_rejected | `unsupported-version` |
| 未知字段注入 | unknown_properties_are_ignored_and_never_write_back | 不报错、编码后不出现 |
| 帧长度 0/负数/溢出 | frame_rejects_zero_negative_and_overflowing_lengths | 0、`ffffffff`、`0x80000000`、3 字节头、null |
| 超大帧声明 | frame_rejects_oversized_declarations_without_allocating_them | 8193 / `0x7FFFFFFF` 拒；8192 往返成功 |
| 截断帧体 / 帧后多余字节 | frame_ignores_trailing_bytes_but_rejects_truncated_bodies | 与 `decodeOrNull` 的既定语义一致 |
| 配对链接前缀/凭据/长度 | pairing_link_requires_the_mediahub_pair_prefix、pairing_link_rejects_credentials_and_padding_in_the_code、pairing_link_and_code_length_caps_are_enforced | `not-a-pairing-link` / `not-base64url` / `code-too-long` |
| 端到端形状 | payload_code_link_roundtrip_survives_the_golden_shape | payload → code → link → payload |

### 2.2 传输客户端（拨号 / 帧 / 超时 / socket 归还）

文件：`.../pairing/PairingTransportSecurityTest.kt`（14 例）

| 威胁 | 用例 | 观测点 |
| --- | --- | --- |
| 非回环地址被当成本地 | only_loopback_literals_reach_the_socket | 192.168.1.20 / 10.0.0.5 / 0.0.0.0 / example.com / nonexistent.invalid → `non-loopback-peer`，且 fake host 的 `acceptedCount` 停在正向那一次（证明**没拨号**） |
| IPv6 回环被误判为远端 | ipv6_loopback_literal_is_not_rejected_as_remote | reason ≠ `non-loopback-peer` |
| 帧长 0 / 负 / 溢出 | zero_length_header_is_payload_invalid、negative_length_header_is_payload_invalid、overflowing_length_header_is_payload_invalid | `payload-invalid` |
| 超大声明导致空等 | oversized_declaration_is_rejected_without_waiting_for_the_body | 不读体即拒 + 耗时上限 1500 ms |
| 响应上限 | declaration_beyond_response_limit_is_rejected_while_fitting_one_is_accepted、response_exactly_at_the_limit_is_accepted | limit 64 拒 100 B、limit 128 收；64/64 边界收 |
| 半帧后关闭 / 只有帧头 | half_frame_then_close_is_payload_invalid_and_the_socket_is_released、header_only_then_close_is_payload_invalid | `payload-invalid` + `awaitPeerClosed` 证明客户端关了 socket（见第 3 节） |
| 对端沉默 | silent_peer_times_out_and_the_socket_is_released | readTimeout 150 ms vs 对端睡 800 ms → `peer-timeout`，socket 同样归还 |
| 端口无人监听 | closed_listener_reports_listener_failed | `listener-failed` |
| 超长/空请求 | oversized_request_is_rejected_before_dialing | 8193 B 与空请求 → 不拨号 |
| 请求确实先写出 | valid_request_is_written_before_the_response_is_read | fake host 回显 `requestPayload` 与请求字节相等 |

### 2.3 导入服务（恶意 / 残缺信封）

文件：`core/database/src/test/kotlin/com/mediahub/core/database/sync/AndroidSourceSyncSecurityTest.kt`（22 例）

9 例解码失败共用 `assertRejectedWithoutStoreAccess(json, reason)`：同时断言 `isRejected`、四个桶计数为 0、以及 `listCalls/createCalls/updateCalls` 全为 0。

| 威胁 | 用例 | 观测点 |
| --- | --- | --- |
| 截断 JSON | truncated_json_is_rejected_without_touching_the_store | `malformed-json` |
| 缺/未来 schemaVersion | missing_schema_version…、future_schema_version… | `missing-field:schemaVersion` / `unsupported-schema-version:2` |
| 未来 minimumReaderVersion | future_minimum_reader_version… | `unsupported-minimum-reader-version:2` |
| 空 deviceId | blank_device_id… | `missing-field:deviceId` |
| sources 非数组 / 元素非对象 | sources_that_are_not_an_array_are_rejected、source_elements_that_are_not_objects_are_rejected | `invalid-field:sources` |
| 空 syncSourceId / 空 url | blank_sync_source_id…、blank_endpoint_url… | `missing-field:syncSourceId` / `missing-field:url` |
| 端点缺失或不可拨号 | all_blank_endpoints_require_approval_and_write_nothing、unusable_scheme_endpoints_require_approval_and_write_nothing | 落 `requiresApproval` + `no-usable-endpoint`，零写入 |
| 墓碑指向不存在的来源 | tombstone_for_an_unknown_local_source_is_ignored_and_writes_nothing | 落 `ignored` + `no-local-source`，**不是错误**，零写入 |
| 未知 wire 类型 | unknown_wire_type_does_not_touch_an_existing_bound_source | `unsupported` 1 条、零写入、已有快照 name/revision/sourceTypeRaw 逐字段不变 |
| 重复 syncSourceId | duplicate_sync_ids_never_write_even_once、duplicate_sync_ids_differing_only_by_case_or_padding_are_rejected_too（W6J-1 回归） | 整封拒绝 `duplicate-sync-source-id`，连 `listLocalSources` 都不调；大小写/首尾空格也算重复 |
| 逐条写入抛异常 | write_failure_is_reported_as_a_failed_entry_instead_of_escaping、a_failed_write_does_not_hide_the_ones_that_did_get_written | 单条落 `failed` + `local-source-write-failed:<异常类名>`，异常不逃出 `importEnvelope`，已写入的照常汇报 |
| 去重不误杀 | duplicate_check_does_not_reject_a_healthy_envelope | 两条不同 id 仍 applied 2 / create 2 |
| 凭据回显 | rejected_envelope_does_not_echo_credentials、secrets_are_not_copied_into_results_or_storage | `rejectedMessage` 恒为固定文案；`secrets` 字段不进结果/存储 |
| 正向对照 | golden_envelope_still_applies_two_sources、mixed_envelope_writes_only_the_known_sources | 黄金样本 applied 2 / requiresApproval 1 / ignored 1 / create 2；混合信封只写已知那一条 |

### 2.4 凭据面

文件：`.../pairing/PairingCredentialLeakTest.kt`（6 例）：payload `toString` 的 challenge 打码、被拒 challenge 不回显原值、URL 凭据在 `tryDecode`/`decode` 两条路径都不回显、配对码失败只带 reason、配对链接失败不回显 code、指纹失败信息只含长度。

## 3. 一个容易误判的语义边界：半帧 ≠ 沉默

首轮实跑这两例是红的：`half_frame_then_close…` 与 `header_only_then_close…` 实测返回 **`peer-timeout`** 而不是 `payload-invalid`。

原因是测试对端只写了半帧、然后**等客户端关闭**——对端既不关写端也不发数据，客户端在 `readFully` 上只能等到读超时，这确实是超时语义。改为对端写完半帧后 `socket.shutdownOutput()`（发 FIN）后，`readFully` 拿到 EOF → `FrameRead.Rejected` → `payload-invalid`，两例转绿。

结论：**客户端没有缺陷**，是"对端关闭"与"对端沉默"必须有区分；写这类用例时对端一定要半关闭写端（只 `close()` 整个 socket 也行，但会失去"客户端是否也关 socket"的观测点，故用 `shutdownOutput` + `awaitEndOfStream`）。

## 4. 实跑记录

环境：Windows / JDK 21 / Gradle 8.14。

```
gradlew.bat :core:common:testDebugUnitTest :core:database:testDebugUnitTest --console=plain --rerun-tasks
```

JUnit XML 实测（`build/test-results/testDebugUnitTest/`）：

| 模块 | 基线 | 本轮 | 说明 |
| --- | --- | --- | --- |
| core:common | 21 suites / 262 tests | **24 suites / 301 tests / 0 failures / 0 errors** | +3 套件 +39 例 |
| core:database | 13 suites / 103 tests | **14 suites / 126 tests / 0 failures / 0 errors / 0 skipped** | +1 套件 +22 例（含 W6J-1 的 4 条新回归）+ 1 例 Room 上的重复 id 用例；skipped 0 |

单类计数：PairingMaliciousInputTest 19、PairingTransportSecurityTest 14、PairingCredentialLeakTest 6、AndroidSourceSyncSecurityTest 22、AndroidSourceSyncServiceRoomTest 7（含 W6J-1 的真 Room 用例）。

构建结果 `BUILD SUCCESSFUL in 1m 31s`，75 tasks executed；修完 W6J-1 并补 4 例回归后的复跑是 `BUILD SUCCESSFUL in 1m 59s`、75 tasks executed、`EXIT=0`。

环境坑（可复用）：`:core:common:bundleLibCompileToJarDebug` 报 `classes.jar: 另一个程序正在使用此文件` → 先 `gradlew.bat --stop`，再用 `Get-Process java | Stop-Process -Force` 清干净（`JAVA_LEFT=0`）后重跑。另外 `cmd /c "… set "JAVA_HOME=C:\Program Files\Java\jdk-21" …"` 的嵌套引号会被截断成 `C:\Program`（`JAVA_HOME is set to an invalid directory`），本轮改为把命令写进 `%TEMP%\w6j-run.bat` 再 `cmd /c` 执行才稳定。
根治配方（复跑时用，一次到位）：`gradlew.bat --stop` → `Get-Process java | Stop-Process -Force` → 删 `core\common\build\intermediates\compile_library_classes_jar` → 重跑时加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`（不再有独立 Kotlin 守护进程持 `classes.jar` 句柄）。注意 pwsh 里必须用单引号包整条 `cmd /c '…'`，内部写 `set "JAVA_HOME=…"`。

## 5. 已修缺陷 W6J-1：同信封内重复 `syncSourceId` 会写两条（2026-10-24 修）

- 原缺陷位置：`core/database/src/main/kotlin/com/mediahub/core/database/sync/AndroidSourceSyncService.kt:109-113`（CREATE 分支逐条 `store.createLocalSource(snapshot)`，没有信封内 id 去重、没有 try/catch、整封无事务）；`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncPlanner.kt:146`（判定 `CREATE`/`new-source` 时只与 `locals` 比对，同信封两条相同 id 都判 CREATE）。
- 原后果：第一条先提交，第二条在 Room 上撞唯一索引 `index_servers_sync_source_id` → `SQLiteConstraintException` 逃出 `importEnvelope`，调用方拿到异常而不是稳定失败码，且**已部分写入**。
- 修法（两层，都在服务层，planner 契约不变）：
  1. **整封先去重**：`apply()` 最前面按 `syncSourceId.trim().lowercase()` 统计信封内出现次数，出现 >1 即整封拒绝，稳定码 `duplicate-sync-source-id`（`AndroidSourceSyncService.kt:338`），`rejectedMessage` 回显首次拼写；此时**连 `listLocalSources` 都不调**，不可能写入。
  2. **逐条写入兜底**：写入循环包进 `catch (CancellationException) { throw }` + `catch (Exception)`，单条异常落 `failed` 且 reason 为 `local-source-write-failed:<异常类名>`（`:345`），异常不再逃出 `importEnvelope`；已写入的条目照常汇报。
- 回归用例（5 条）：`duplicate_sync_ids_never_write_even_once`（严格断言 rejectedReason / `listCalls==0` / 四桶 0 / snapshots 空）、`duplicate_sync_ids_differing_only_by_case_or_padding_are_rejected_too`、`duplicate_check_does_not_reject_a_healthy_envelope`、`write_failure_is_reported_as_a_failed_entry_instead_of_escaping`（`local-source-write-failed:IllegalStateException`）、`a_failed_write_does_not_hide_the_ones_that_did_get_written`；另加真 Room 用例 `AndroidSourceSyncServiceRoomTest.a_duplicate_sync_source_id_envelope_is_rejected_before_any_row_is_written`（断言 `serverDao().count() == 0`）。
- 未做：整封仍**不是事务**——逐条写入中途失败会留下已写入的行（这是有意的：部分成功要如实汇报而不是静默回滚）。要全有或全无得在仓库层加事务 API，属后续工作。

## 6. 未覆盖项

1. **TLS / 指纹钉扎**：`PairingTransportClient` 目前是明文 TCP（帧内 `ephemeralUrl` 可为 https 但客户端不建 TLS），因此没有证书校验、指纹钉扎、降级攻击相关的用例；这部分要等传输层换成 TLS 才谈得上。
2. **日志面**：两个包都没有接日志器，本轮钉的是异常 message 与结果对象；一旦接入 logging，需要补"日志不打凭据"的用例（包括 `Throwable.stackTrace` 与字段打印）。
3. **并发与资源压力**：没有并发 import、并发 exchange、长连接复用、大量帧连发的用例；socket 归还只验证了单连接路径。
4. **时间与时钟**：只验了 `expires <= 0` 与已过期，没有验时钟回拨、超长有效期、`createdAtEpochMs` 为负等组合。
5. **Room 层恶意输入**：绝大多数导入服务用例跑在 fake store 上；真 Room（in-memory）只覆盖了 1 条坏信封（重复 `syncSourceId` 整封拒绝、`serverDao().count() == 0`）。其余畸形信封在真 Room 上的行为仍是推断。
6. **真机 / instrumentation**：全部为 JVM 单测，未跑 `connectedAndroidTest`，未在真机网络栈上验 `shutdownOutput` 与超时行为。
7. **其他面**：备份（`core/common/.../backup`）、收藏（`feature/library`）、来源同步 UI（`docs/android-sync-ui.md` 所述界面）未纳入本轮安全回归。
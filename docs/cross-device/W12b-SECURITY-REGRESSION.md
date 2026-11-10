# W12b 安全与鲁棒性回归（第二轮）

> 状态：只新增测试与本文档，未改动 `windows/src/**`。结论是「每条拒绝路径都有成对用例钉住」，
> 不等于发布就绪，也不改变既有的发布阻断结论。

## 1 范围与边界

- 新增：`windows/tests/MediaHub.Tests/W12bSecurityRegressionTests.cs`、本文档。产品代码零改动。
- 不连真实服务器、不启 GUI、不写 `%LOCALAPPDATA%`：每个临时根目录都是 `%TEMP%\mh-*\<guid>`，由测试
  自己建、自己在 `Dispose` 里删（只读属性先复位再删）。
- 不新增 NuGet 依赖，不 commit，不 push。
- 与第一轮 `windows/docs/W12-SECURITY-REGRESSION.md` 的关系：那一轮钉的是「凭据不进 URL/日志/明文落盘」，
  这一轮钉的是 W6/W13 新增面上的**拒绝行为**——配对链接、配对设备文档、传输帧、CLI 路径、失败路径的口令泄漏。

## 2 实测结果

命令（在仓库根执行）：

    dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug

原始结论行：

    已通过! - 失败:     0，通过:  1153，已跳过:     0，总计:  1153，持续时间: 4 s - MediaHub.Tests.dll (net10.0)

基线 1087 → 1153（+66 个用例）。新增部分是 49 个测试方法：44 个 `[Fact]` + 5 个 `[Theory]`
（22 行 `[InlineData]`），展开后 66 个用例。按类的分布：

| 测试类 | 方法数 | 用例数 | 覆盖面 |
| --- | ---: | ---: | --- |
| `PairingLinkAdversarialTests` | 11 | 24 | 配对链接与载荷解析 |
| `PairedDeviceStoreAdversarialTests` | 9 | 9 | 配对设备文档持久化 |
| `SourceSyncTransferAdversarialTests` | 16 | 19 | 传输帧与挑战状态机 |
| `SyncCliRootAdversarialTests` | 8 | 9 | CLI 根目录与机器可读输出 |
| `SyncCliSecretLeakAdversarialTests` | 5 | 5 | 失败路径的口令泄漏 |

每个用例都成对：先证明主路径成立（同一批数据在合法输入下成功），再证明负路径是**显式拒绝**
（稳定理由码 + 磁盘/内存字节不变），而不是「没崩就算过」。

## 3 每条用例钉住的真实行为

### 3.1 配对链接与载荷（`windows/src/MediaHub.Sync/PairingLink.cs`、`PairingCode.cs`、`PairingPayload.cs`）

| 用例 | 钉住的行为 |
| --- | --- |
| `A_valid_link_round_trips_the_whole_payload` | `PairingLink.Create` → `Parse` 往返保留六个字段（正路径基准） |
| `A_malformed_code_is_refused_with_a_stable_reason`（4 行） | 空码 `code-empty`；`!!!!` 与长度 %4==1 的 `A` 为 `not-base64url`；可解码但内容非载荷的 `AAAA` 为 `payload-invalid`；理由码都属于 `PairingCodeReasons.All` |
| `An_oversized_link_is_refused_before_any_decoding` | 超过 `PairingLink.MaximumLength`(8192) 先判长度再解码，理由 `pairing-link-too-long`，提示文本不回显链接内容 |
| `A_link_with_the_wrong_scheme_is_refused` | 非 `mediahub://pair?` 前缀为 `not-a-pairing-link` |
| `A_payload_whose_address_carries_user_info_is_refused` | `http://user:hunter2@host/` 被拒为 `bad-url`，提示里不出现口令 |
| `A_payload_whose_address_is_not_a_bare_http_url_is_refused`（6 行） | `ftp`/`ws`/无 scheme/无 host/带 query/带 fragment 全部 `bad-url`；query 夹带的 `token=abc` 不回显 |
| `A_payload_missing_a_required_field_is_refused`（6 行） | 六个必填字段逐个移除都折成 `payload-invalid`（`PairingCode` 不透出 `missing-field:*` 给用户） |
| `The_same_document_with_every_field_present_is_accepted` | 同一份文档补全字段后成功，证明上一行不是解析器坏了 |
| `Unknown_extra_fields_are_ignored_and_never_written_back` | 载荷里的 `password`/`token` 被忽略，重新编码后只剩六个契约字段，且明文里不含这些键与值 |
| `An_expired_link_is_refused_at_the_boundary_and_accepted_one_millisecond_earlier` | 119999 ms 处通过，120000 ms 处 `expired`，提示明确写 120 秒 |
| `A_truncated_code_is_refused_rather_than_half_decoded` | 截短 4 个字符后拒绝，`Payload` 为 null，理由码在稳定集合内 |

### 3.2 配对设备文档（`windows/src/MediaHub.Sync/FilePairedDeviceStore.cs`）

| 用例 | 钉住的行为 |
| --- | --- |
| `A_valid_document_round_trips_a_device` | 保存后重开能读回同一设备（正路径基准） |
| `Corrupt_json_is_quarantined_and_never_silently_emptied` | 坏 JSON → `GetAll` 空、`LoadError` = `paired-malformed-json`、原文件被改名隔离且**隔离副本字节与原文件逐字节相同** |
| `A_top_level_array_is_refused_as_malformed` | 顶层不是对象 → 同上，不是「当成空列表」 |
| `An_unsupported_version_is_refused_and_names_the_version_it_saw` | `version:2` → `LoadError` 以 `paired-unsupported-version` 开头并含 `:2`；隔离后仍可写（新设备能落盘并被重新读到） |
| `Exceeding_the_capacity_is_refused_and_the_document_is_unchanged` | `Capacity=2` 时第三个设备抛 `IOException`，消息恰为 `paired-device-limit-reached`，文件字节不变，重开仍是 2 个 |
| `An_exclusively_locked_document_refuses_writes_and_survives_the_attempt` | 另一句柄 `FileShare.None` 打开时：读降级为空 + `LoadError` = `paired-store-unreadable`，写抛同码 `IOException`，释放后原字节完好、设备还在 |
| `A_read_only_document_refuses_writes_without_losing_the_device` | 文件置只读后写被拒（真实异常类型见 `4 发现 1），字节不变，重开仍是旧显示名 |
| `A_missing_document_reads_as_empty_and_the_first_save_creates_it` | 缺文件 = 空列表 + `LoadError` 为 null；首次保存才创建文档 |
| `A_single_corrupt_row_is_skipped_while_the_healthy_rows_survive` | 单行坏（缺 `deviceId`、指纹非法）被跳过，健康行保留，`LoadError` 仍为 null（局部损坏不等于整档损坏） |

### 3.3 传输帧与挑战状态机（`windows/src/MediaHub.Sync/SourceSyncTransfer.cs`）

| 用例 | 钉住的行为 |
| --- | --- |
| `A_healthy_transfer_round_trips_the_envelope_and_its_challenge` | 4000 字节信封分帧发送后逐字节读回，挑战值随结果返回（正路径基准） |
| `An_empty_envelope_is_refused_before_a_single_byte_is_written` | 0 字节 → `payload-too-large`，流长度仍为 0 |
| `An_envelope_over_the_ceiling_is_refused_at_send_time` | 8 MiB + 1 → `payload-too-large`，流长度仍为 0 |
| `A_frame_that_declares_an_unusable_length_is_refused_without_buffering`（4 行） | 帧长 0 / 8193 / `int.MaxValue` / -1 一律 `frame-truncated`，耗时 < 1 s，`Envelope` 为空（**不按声明长度分配**） |
| `A_chunk_that_announces_zero_bytes_is_refused` | 分块总长为 0 → `frame-invalid` |
| `A_chunk_that_announces_more_than_the_ceiling_is_refused_before_allocating` | 分块声明 8 MiB + 1 → `payload-too-large`，耗时 < 1 s，未缓冲 |
| `A_negative_offset_is_refused` | 偏移 -1 → `frame-invalid` |
| `A_challenge_frame_that_arrives_second_is_refused` | 多帧信封中途插入挑战帧 → `frame-invalid`（"must come before the first chunk"） |
| `A_chunk_that_skips_bytes_is_refused` | 第二块跳到偏移 6 而只到了 5 字节 → `frame-invalid`（"starts at 6"） |
| `Chunks_that_disagree_about_the_total_are_refused` | 第二块声明 11 而总量是 10 → `frame-invalid`（"announced 11"） |
| `A_chunk_that_runs_past_the_announced_total_is_refused` | 7 字节越过声明总量 4 → `frame-invalid`（"runs past"），`Envelope` 为空 |
| `A_peer_that_closes_mid_envelope_is_reported_as_truncated` | 只发挑战帧 + 一块就 EOF → `frame-truncated`，不返回半截信封 |
| `A_peer_that_skips_the_expected_challenge_is_refused` | 期望挑战但首帧就是数据块 → `challenge-mismatch` |
| `A_peer_that_announces_the_wrong_challenge_is_refused` | 挑战值不符 → `challenge-mismatch` |
| `An_unknown_frame_kind_is_refused_and_quotes_only_the_kind` | 未知帧类型 0x7f → `frame-invalid`，提示只引用 `0x7f`，不回显帧里的载荷（含口令样本） |
| `The_refusal_codes_are_the_documented_stable_set` | 10 个理由码无重复、匹配 `^[a-z]+(-[a-z]+)*$`、`Failed(code)` 原样保留 |

### 3.4 CLI 根目录与机器可读输出（`windows/src/MediaHub.SyncCli/`）

| 用例 | 钉住的行为 |
| --- | --- |
| `Export_creates_the_missing_destination_directory_and_reports_the_real_path` | `--out` 的父目录不存在时导出会自建目录并成功，JSON 里的 `path` 是实际写入位置 |
| `A_root_that_is_a_file_is_tolerated_but_never_overwritten` | `--root` 指向普通文件时不报错、返回 `count=0`，且该文件字节不变（写入会在后续失败，见 `4 发现 3） |
| `A_root_with_a_parent_traversal_is_resolved_by_the_operating_system` | `a/../escaped/env.json` 由操作系统解析：字节落到父目录，JSON 里的 `path` **原样回显参数**（见 `4 发现 2） |
| `Every_read_command_is_happy_with_a_root_that_does_not_exist`（2 行） | `pair list`、`baseline show` 对不存在的 root 返回 0、空状态，且**不创建**目录 |
| `Json_mode_writes_exactly_one_document_to_stdout_for_success_and_failure` | 成功与失败都只写一个 JSON 文档（单行），失败文档为 `{"ok":false,"error":{code,message}}`，stderr 一行 `error <code>: …` |
| `Text_mode_keeps_stdout_empty_and_sends_the_failure_to_stderr` | 文本模式失败时 stdout 为空、stderr 带错误行与设备 id，退出码 2 |
| `A_plan_without_apply_leaves_every_byte_on_disk_alone` | `import` 不带 `--apply` 时输出 `dryRun:true`，整个 root 的文件哈希快照不变 |
| `Pair_forget_previews_without_writing_and_only_apply_removes_the_device` | `pair forget` 预览：`dryRun:true`、`applied:false`、文档字节不变、设备仍在；`--apply` 后才真正删除 |

### 3.5 失败路径的口令泄漏（`CliOutput` / `SyncCliRunner.Fail`）

| 用例 | 钉住的行为 |
| --- | --- |
| `A_missing_input_file_never_echoes_the_passphrase` | `import --passphrase <marker> --in <不存在>` → `input-file-missing`，stdout 与 stderr 都不含 marker |
| `A_damaged_envelope_never_echoes_the_passphrase` | 损坏信封 → 非零退出，marker 不出现在任何输出，也不写回输入文件 |
| `A_damaged_baseline_never_echoes_the_device_or_the_passphrase` | 损坏的 `sync-baseline.json` → 非零退出（`store-unreadable` 类），坏文档被隔离且隔离副本字节与原文件相同，输出不回显文档内容 |
| `A_rejected_pairing_link_never_echoes_its_code` | 被拒的配对码（含伪口令串）不进 stdout/stderr，JSON 仍是 `ok:false` 结构 |
| `An_oversized_pairing_link_is_refused_without_being_quoted` | 超长链接按 `pairing-link-too-long` 拒绝，输出不含链接内容前 200 个字符 |

## 4 实测发现（都不是阻塞缺陷，但需要记录）

1. **`IPairedDeviceStore.Save` 的异常注释低估了异常面。** `windows/src/MediaHub.Sync/IPairedDeviceStore.cs:45`
   只声明 `IOException`，但目标文档带只读属性时，`File.Replace`（`FilePairedDeviceStore.cs:400`）抛的是
   `UnauthorizedAccessException`。CLI 的写路径同时捕获两者（`SyncCliRunner.cs:753`、`:262` 等），所以用户看到
   的仍是 `store-write-failed`，不会崩。用例按真实类型断言，并在注释里写明差异。
2. **`--out`/`--root` 不做规范化。** `SyncCliRunner.cs:1021` 的 `ResolveExportPath` 直接拼路径，JSON 的 `path`
   原样回显参数（含 `..`），而字节落在操作系统解析后的位置。脚本若拿 `path` 做存在性判断，在含 `..` 的输入上会
   对不上。属文档/可用性问题，不影响数据安全。
3. **`--root` 指向普通文件时静默当空根。** 设备文档路径是 `<root>/paired-devices.json`，root 是文件时「读不到」
   等同「还没有设备」，CLI 返回 `count=0` 且退出 0；后续写入会在建目录/替换时失败。宽容但静默，值得在
   `--help` 或文档里点明。
4. **「挑战帧必须第一」只在信封未完成时可观测。** `SourceSyncTransfer.ReceiveAsync` 在 `received == total` 时
   立即返回成功，后续帧不再读取，因此一个完整数据块后面的挑战帧永远不会被看到。用例用「声明 10 字节只发 5 字节」
   构造，把这个限制如实钉住，而不是假设接收端会读到帧尾。

以上四条都没有改动产品代码；是否修注释/规范化路径属于维护者决定。

## 5 未覆盖

- `SourceSyncTransferEndpoint`（真实 socket 监听/连接/超时）：本轮只覆盖帧编解码与挑战状态机，端点行为由既有
  `SourceSyncTransferTests` 负责。
- 加密归档（`.mhsync`）被篡改的对抗性用例：既有归档测试覆盖了部分，本轮未新增。（W6-66 后归档规范后缀为 `.mharchive`，本条原文保留为当时记录。）
- 目录级 ACL 受限（本轮只测了文件只读属性）与「保险库被另一进程独占」的写路径。
- 两个 CLI 进程并发写同一 root：`File.Replace` 的并发语义不在本轮范围。
- 模糊测试/随机语料：所有输入都是手工构造的最小样本，没有覆盖长度与字符集的组合空间。
- 时钟回拨与跨时区：只覆盖了 120 s 过期边界（±1 ms）。

## 6 复现

    cd D:\mh-w6
    dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug

计数口径：`dotnet test … --list-tests` 共 1153 行；其中五个 `*AdversarialTests` 类合计 66 行
（24 + 9 + 19 + 9 + 5）。基线 1087 是本轮开工前同一命令的通过数。

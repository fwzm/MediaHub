# W6-30 `transfer send` 送达确认（`--confirm-delivery`）

> **状态：IMPLEMENTED_UNVERIFIED。** 代码已实现、19 个新用例全绿、全套 1388 例通过、`MediaHub.App` 工程 0 警告 0 错误、W6-28 验收脚本 11/11 PASS。本轮没有 GUI 证据、没有与 Android 端联调的证据；「送达已确认」只表示**对端关闭了连接**，不表示对端接受并写入了信封（第 6 节）。
>
> **后续：本文件的「关闭只是必要条件」盲区已由 W6-37 修（真回执帧 `0x04`，默认关闭，开 `transfer serve --send-receipt` / `transfer send --await-receipt`）。** 本文件的 `--confirm-delivery` 行为一字未改；两者同时给出时以回执为准。见 `docs/cross-device/W6-37-RECEIPT.md`。

- 日期：2026-10-24
- 分支：`feature/cross-device-source-sync`（工作树 `D:\mh-w6`，基线 `a47b530`）
- 前置：W6-16（帧协议）、W6-24（tag 认证）、W6-26（TLS 1.3 + 指纹钉扎）、W6-28（两进程验收脚本）
- 范围：`windows/src/MediaHub.Sync`、`windows/src/MediaHub.SyncCli`、`windows/tests/MediaHub.Tests`、`windows/tools/acceptance/w6`、`docs/cross-device`。未动 `MediaHub.App` / `MediaHub.Domain` / `MediaHub.Providers` / `MediaHub.Storage` / `LICENSE`；改动已随本分支入库（提交信息见 `git log` 的 W6-30 一条；交付当时未推送）。

## 1 问题：send 成功不是送达证据

W6-28 的 F 用例（明文 client 打 TLS 监听端）实测：

```text
F-plaintext-client-tls-listener :: send=0 serve=2 frames=1 records=0 envelopes=0 codes=[/tls-handshake-failed] 602 ms
```

监听端在 `AuthenticateAsServerAsync` 里当场拒绝了第一个记录，一个信封字节都没读，接收端零写入；而**发送端 exit 0 且没有任何稳定码**。根因在 `SourceSyncTransfer.SendAsync`：它写挑战帧 → 各 chunk →（认证时）tag 帧 → `FlushAsync` 然后返回，**从不读取对端任何响应**。帧进了本机 socket 缓冲区，内核确认了 TCP 段，于是「写成功」被当成了「送达」。E/G/H 三个用例同理：send 侧 0，只有 serve 侧报错。

这与用户纪律「步骤变绿不等于测到了」是同一类缺陷：流程报成功，但真实路径根本没走到。

## 2 确认的语义

一个单向帧协议能观测到的唯一回执，是**对端把连接关掉**（读到 EOF）。因此 `--confirm-delivery` 的语义是：

1. 写完全部帧并 flush；
2. 明文路径做 `Shutdown(SocketShutdown.Send)` 半关闭（只发 FIN，不产生任何载荷字节），TLS 路径**不做**（见第 5 节）；
3. 读同一个流直到对端关闭，预算 `DefaultBudgetMilliseconds = 2000 ms`；
4. 读到 EOF（0 字节）→ 送达已确认；预算到期 → 未确认；读过程中抛 `IOException` / `SocketException` / `ObjectDisposedException` 等（RST、对端中途消失）→ 未确认；对端在信封之后继续发超过 64 KiB 而不关闭 → 未确认（它在回话，不是在确认收件）。

预算为什么是 2000 ms：环回接收端读完即关，实测在 1~10 ms 量级；2 s 足够容纳一台忙碌机器，又不至于让操作员对着卡住的对端干等。

## 3 新稳定码

| 码 | 含义 | 触发 |
| --- | --- | --- |
| `delivery-unconfirmed` | 帧写出去了，但没有拿到回执 | 预算内对端未关闭；或等待期间读失败（RST 等）；或对端持续发数据不关闭；或调用方取消 |

该码独立成集 `SourceSyncDeliveryReasons`（`All` 固定顺序、当前单元素），与三个既有封闭集**并列而不合并**：

| 集合 | 码数 | 内容 |
| --- | --- | --- |
| `SourceSyncTransferReasons` | 10 | 帧/地址/解码层 |
| `SourceSyncTransferAuthReasons` | 5 | W6-24 tag |
| `SourceSyncTlsReasons` | 4 | W6-26 TLS |
| `SourceSyncDeliveryReasons` | 1 | 本任务 |

四个集两两不重叠，且既有十码集的封闭性不变（`The_delivery_reason_set_is_closed_and_disjoint_from_the_other_three` 逐条钉住）。**不折成 `io-failed`**：那是接收侧读帧失败的码，语义是「对面给的东西读不了」，而这里是「我写完了，但对面没给回执」，排障方向相反。

## 4 线上字节一个都没变

- 默认路径（不带 `--confirm-delivery`）走的是**同一个** `SourceSyncTransfer.SendAsync`，写入序列、`FrameCount`、`Bytes` 全都不变；W6-16 的 95 字节冻结向量用例（`With_authentication_off_the_bytes_match_the_frozen_w6_16_hex_vector`）仍然逐字节相等。
- 开启确认时也不多写一个字节：明文半关闭只发 FIN（FIN 不携带载荷），TLS 路径不发 FIN 也不写任何东西。`Confirmation_on_still_writes_the_frozen_w6_16_bytes` 在对端侧数到**恰好 95 字节**并与冻结 hex 逐字节比对。
- 关闭时 `DeliveryConfirmed = null`（没有人问，就没有回执），开启但失败时 `DeliveryConfirmed = false`——两者在 JSON 里分别是 `null` 与 `false`，调用方不会把「没问」误读成「问了且没确认」。

## 5 明文半关闭与 TLS 只读的不对称

| 路径 | 写完后 | 为什么 |
| --- | --- | --- |
| 明文 | `Shutdown(SocketShutdown.Send)`（FIN） | 让对端「读到 EOF 就收尾」的实现能结束；FIN 不携带载荷，字节流不变；socket 仍可读，确认才看得到对端的关闭 |
| TLS 1.3 | 只读，不半关闭 | TLS 的收尾是 `close_notify`；在 TLS 流上发裸 FIN 会被对端当成记录序列被截断（truncation attack 的形态），所以这里宁可让对端自己关 |

`Half_closing_adds_no_byte_and_leaves_the_read_side_open` 用一个环回 socket 直接验证了这条不对称：发送端半关闭后，对端仍能写回 1 字节并被发送端读到（`BytesRead == 1`），而对端数到的发送字节恰好 95。

## 6 已知残留盲区：关闭是必要证据，不是充分证据

TCP 上「对端拒绝后关闭」与「对端接收后关闭」**不可区分**。因此：

- W6-28 的 F 用例重跑**仍然 send 0**：TLS 监听端拒绝记录后关闭 socket，发送端读到 EOF，于是报告 `deliveryConfirmed=true`，而接收端 `tls-handshake-failed`、零写入。这是本任务**没有**修好的部分，验收脚本把它作为 `F2-plaintext-confirm-vs-tls` 用例**固定在表里**，标题写明「blind spot」，避免下次有人以为这条已经闭环。
- 真正可观测的失败形态是：对端接受连接后**永不关闭**（脚本用例 I：2.3 s 后 `delivery-unconfirmed`）、对端 RST（单元用例 C）、对端持续发数据（单元用例 7）。
- 要拿到真正的回执，需要一个**反向确认帧**（接收端导入成功后回一个字节/一帧），那会改线格式并需要 Android 侧同步实现；W6-30 明确不做，留在未覆盖清单。

## 7 CLI

```text
transfer send --to 127.0.0.1:<port> --root <dir> --device <id> [--confirm-delivery] [--json]
              [--tls --expect-fingerprint HEX] [--authenticate --secret-env NAME]
```

| 规则 | 结果 |
| --- | --- |
| `--confirm-delivery` 用在 `transfer serve` / `transfer roundtrip` | exit 1 `option-not-allowed-for-command:--confirm-delivery:<命令>` |
| `--confirm-delivery=1` | exit 1 `flag-takes-no-value:--confirm-delivery` |
| 确认成功 | exit 0；JSON `confirmDelivery=true`、`deliveryConfirmed=true`；文本多一行 `delivery: confirmed (peer closed after N ms)` |
| 确认失败 | exit 2 `delivery-unconfirmed`，message 形如「The envelope was written (64 bytes) but the peer did not close the connection within 2000 ms, so the send is not evidence of delivery.」 |
| 不开开关 | exit 0；JSON `confirmDelivery=false`、`deliveryConfirmed=null` |

退出码语义仍是 0/1/2/3/4；`delivery-unconfirmed` 属于输入/环境问题，走 2。`--help` 的 transfer 段新增三行说明（含「off by default, so a send never waits and never adds a byte」）。

JSON 新增两个键（`transfer send` 结果文档，在 `certificateFingerprint` 之后）：

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `confirmDelivery` | bool | 本次是否请求了回执（= 是否给了开关） |
| `deliveryConfirmed` | bool / null | `null` 没问；`true` 等到关闭；`false` 问了但没等到（此时进程 exit 2，文档是 error 形状） |

## 8 `transfer roundtrip` 内部确认

roundtrip 两个方向的监听端都由本进程创建，收完信封即关（`ReceiveOnceAsync` 的 `finally` 里 `client.Dispose()` 先于方法返回），所以内部**总是**开启确认：

- 不改既有 JSON 形状（不新增字段）；
- 不改变既有 16 个 CLI 用例的断言（已全绿）；
- 唯一的行为变化是：如果将来某个方向的接收端在 2 s 内不关连接，roundtrip 会以 `delivery-unconfirmed` 失败，而不是静默成功。

## 9 验收脚本

`windows/tools/acceptance/w6/transfer-tls-auth.ps1`（W6-28 的脚本）保留 A..H 八例原样，新增三例：

| 用例 | 场景 | 预期 |
| --- | --- | --- |
| A2 | 明文 + `--confirm-delivery` | send 0、`deliveryConfirmed=true`、1 个信封（2 条记录） |
| F2 | 明文 client + `--confirm-delivery` 打 TLS 监听端 | **已知盲区**：send 0 + `deliveryConfirmed=true`、serve 2 `tls-handshake-failed`、零写入 |
| I | 对端接受连接后永不关闭（PowerShell 作业持 socket） | send 2 `delivery-unconfirmed`、耗时 ≥ 1900 ms、零写入 |

实测（2026-10-24，`D:\mh-w6`）：**11/11 PASS，15326 ms，exit 0**，收尾断言残留 `MediaHub.SyncCli` 进程 0，用例日志里不含任何口令明文。摘要：`D:\mh-verify\w6-30\w6-28-summary.json`。

## 10 测试

`windows/tests/MediaHub.Tests/SourceSyncDeliveryConfirmTests.cs`：19 个 `[Fact]`（12 个传输层 + 7 个 CLI），全套由 1369 → **1388 通过 / 0 失败 / 0 跳过**。

传输层：

1. `A_peer_that_reads_the_frames_and_closes_confirms_the_send`
2. `A_peer_that_keeps_the_connection_open_is_unconfirmed_after_the_budget`（实测等待 ≥ 400 ms）
3. `A_peer_that_resets_the_connection_is_unconfirmed_and_not_io_failed`
4. `Without_the_flag_a_send_returns_at_once_and_reports_no_confirmation`（对端不关也不读，send 仍立即返回，`DeliveryConfirmed == null`）
5. `Confirmation_on_still_writes_the_frozen_w6_16_bytes`（对端数到 95 字节且等于冻结 hex）
6. `Half_closing_adds_no_byte_and_leaves_the_read_side_open`
7. `A_peer_that_keeps_sending_instead_of_closing_is_unconfirmed`（> 64 KiB 上限）
8. `A_stream_that_is_already_at_its_end_confirms_at_once`
9. `Tls_with_confirmation_succeeds`
10. `Tls_with_a_peer_that_handshakes_and_never_closes_is_unconfirmed`
11. `The_tag_frame_and_confirmation_work_together`（3 帧：95 + tag 帧 37 = 132 字节）
12. `The_delivery_reason_set_is_closed_and_disjoint_from_the_other_three`

CLI：

13. `Confirm_delivery_only_parses_on_transfer_send`
14. `Confirm_delivery_takes_no_value`
15. `Help_names_the_new_flag_and_its_reason`
16. `Json_mode_reports_confirm_delivery_and_the_confirmation`
17. `Text_mode_prints_the_confirmation_line`
18. `A_peer_that_never_closes_is_reported_as_delivery_unconfirmed`（真进程内 CLI + 真 socket，耗时 ≥ 1.5 s）
19. `Authentication_and_confirmation_together_keep_the_secret_out_of_the_logs`（口令不出现在 stdout/stderr）

## 11 给 Android 侧的联调口径

- 线格式**没有变**：仍是 W6-16 的「4 字节大端长度 + kind + 载荷」帧，kind 0x01 挑战 / 0x02 chunk / 0x03 tag（W6-24）。送达确认**不新增帧**。
- 若 Android 侧要参与确认：接收端在**导入完成后立即关闭连接**（当前 Windows 监听端就是这个行为）；发送端写完最后一个帧并 flush 后读流直到 EOF，预算 2000 ms。明文可半关闭（`shutdown(SHUT_WR)`），TLS 不可（必须走 `close_notify`）。
- Windows 侧默认**不发**确认请求，也不等待；Android 侧收到的是同样的字节流，**无需改动**即可互通。
- 若将来做真正的回执帧，需要双方同时改线格式，届时另开任务。

## 12 未覆盖

1. **回执不等于接受**：对端「拒绝后关闭」看起来与「接收后关闭」完全一样（第 6 节，F2 用例固定该盲区）。
2. Android 接收端是否在导入后立即关闭连接**未验证**，所以确认只能由操作员显式开启。
3. 跨进程/跨机器的确认预算没有标定：2000 ms 是本机环回实测值加余量的估计，不是跨网络测量结果。
4. 没有 GUI 证据：`MediaHub.App` 目前没有「确认送达」的界面，CLI 是该能力的唯一入口。
5. 没有覆盖「对端关闭但发送端还有未读入站记录」的 TLS 变体（那属于 W6-26 已修的 graceful-close 路径）。

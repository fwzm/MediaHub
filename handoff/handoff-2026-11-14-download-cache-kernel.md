# Handoff — 网盘下载与本地缓存（条目 18 / 61 / 68 / 94 / 95）

- 日期：2026-11-14
- 分支：`feature/download-cache-kernel`（worktree `D:\deepseek_test\mh-download-kernel`）
- 基线：`origin/main` @ `77ef1f2f`

## 状态

已完成并本地验证：

| 条目 | 修复内容 | 证据 |
|---|---|---|
| 18 | `DownloadManager.delete` 清理最终文件 + `.part` + 同 key 前缀残留 + 索引；重复删除返回 false；`cleanupOrphans` 回收无主文件 | `DownloadCacheCleanupTest` 4/4 |
| 61 | `resolveLocalPlayback` 返回 `file://` DIRECT_PLAY；索引缺失但文件已存在时补写索引；播放器解析链优先本地命中 | `DownloadedPlaybackAssociationTest` 6/6；`PlayerViewModelTest` 7/7 |
| 68 | 下载不做直连/网络类型预检、不发 HEAD；VPN 只暴露代理时照常下载（`.invalid` 主机 + HTTP 代理验证） | `DownloadVpnProxyTest` 3/3 |
| 94/95 | 状态机 QUEUED/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED 持久化；401/403 强制重解析一次，失败为 AUTH_REQUIRED；签名 URL 过期前主动刷新 | `DownloadStateMachineTest` 5/5；`DownloadAuthRefreshTest` 4/4 |
| 94/95 | `.part` + Range 断点续传；206 追加、200 回退整包、416 完成；字节级 progress 回调 | `DownloadResumeTest` 5/5 |

执行命令（`--rerun-tasks --no-build-cache`）：

- `:core:download:test` → **27 tests / 0 failures / exit 0**
- `:feature:player:testDebugUnitTest --tests PlayerViewModelTest` → **7 tests / exit 0**
- `:app:compileDebugKotlin`（验证 Hilt 图）→ **exit 0**

RED 证据：移除 `core/download/src/main` 后运行 `:core:download:test`，编译失败 exit 1；恢复实现后 exit 0。

## 目标

为五个仅有 README 的网盘模块提供共享下载内核：状态机、断点续传、缓存清理、本地播放关联、
VPN/代理可用。Provider 只需实现 `DownloadSourceResolver`，不重复实现传输与索引。

## 失败方案（不要再试）

- 用 `MockWebServer.requestUrl` 断言代理目标：OkHttp 4.12 + MockWebServer 下会返回代理自身地址；
  应断言 `RecordedRequest.requestLine`（absolute-form）与 `Host` 头。
- 把“直连可达性”做成 `ConnectivityManager` 预检：会让 VPN-only 环境直接失败，已明确禁止。

## 下一步

1. 五家网盘的真实 OAuth / 列表 / 下载地址解析：需要真实账号与开发者凭据，当前 `NOT_RUN`。
2. 下载任务的通知 / 前台服务与设置页入口尚未接线（内核已具备 `records: StateFlow` 与 progress 回调）。
3. PR 门禁：G7 `DO_NOT_MERGE`，只能保持 Draft，不得合并。

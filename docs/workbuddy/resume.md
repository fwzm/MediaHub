# WorkBuddy 执行线 — 恢复现场（resume）

> 更新于 2026-09-20（第三轮）。下一轮接手先读本文件，再核对实际 Git 与远端，避免重复计划、重复跑测试。
> 配套：`docs/workbuddy/current-state.md`（状态快照）、`docs/workbuddy/task-state.json`（机器可读）。

## 1. 一句话现状

W0 状态核对、W4 只读核查、W8 只读 WebDAV 功能包、W1 PR #18 代码级复验**均已完成并推送**
（Draft PR #19 / #20，远端 CI 均 success）。
W1 的**设备/模拟器验收层**在本会话不可执行（`adb` 不在 PATH）
→ PR #18 保持 `C REVIEW PENDING` / `DEVICE UNVERIFIED`，本线未改变其状态。

## 2. 已交付

| 包 | 分支 | commit | Draft PR | 验证 |
| --- | --- | --- | --- | --- |
| W0 状态 + W4 核查（文档） | `workbuddy/w0-state` | 见远端 head | #20 | 远端 CI `build` **success**（run 35487447042） |
| W8 只读 WebDAV（代码） | `workbuddy/feat-webdav-readonly` | `01c825e` | #19 | 本地 `:provider:webdav:testDebugUnitTest` 55 tests / 0 fail（5 XML）；远端 CI `build` **success**（run 35487390680，覆盖 assembleDebug + 全仓 testDebugUnitTest + lintDebug） |
| W1 PR #18 复验（证据） | `workbuddy/w0-state` | 见远端 head | #20 | 6 模块 **48 XML / 926 tests / 0 failures / 0 errors / 0 skipped**；emby/jellyfin/common 以 `--rerun-tasks --no-build-cache` 取得 EXECUTED（104/104 tasks executed） |

W8 未执行：真实 WebDAV 服务验收、真机验收。证据等级 = 受控 HTTP fixture（MockWebServer）。
W1 未执行：模拟器 SAF 路径、八个进程终止点、损坏 journal、Room/DataStore/凭据/文件边界分项验证。

## 3. 工作区与分支

| 路径 | 模式 | 基线 SHA | 用途 |
| --- | --- | --- | --- |
| `D:/deepseek_test/mh-wb-state` | detached（已推送） | `8e516e4` | W0/W4/W9 文档 |
| `D:/deepseek_test/mh-wb-webdav` | detached（已推送） | `8e516e4` | W8 代码 |
| `D:/deepseek_test/mh-wb-pr18` | detached | `21231ee` | W1 复验（已备好 `local.properties`，未跑） |

两个 worktree 均已写入 `local.properties`（`sdk.dir=C:/Users/55160/AppData/Local/Android/Sdk`，已被 `.gitignore:7` 忽略，不入库）。

### 工具链缺陷（必读，已复现两次）

本机 git `2.55.0.windows.5`：`git worktree add -b` 与 `git switch -c` 会创建**不可解析的分支 ref**
（`rev-parse HEAD` 报 ambiguous；新 worktree 内 `git status` 把全部文件显示为 `A`）。
规避：`git worktree add --detach <path> <sha>`，提交后 `git push origin HEAD:refs/heads/<name>`。
`main` 与既有 13 个 worktree 未受影响。

### 构建命令（本机可用模板）

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME="C:\Users\55160\AppData\Local\Android\Sdk"
& .\gradlew.bat :provider:webdav:testDebugUnitTest --no-daemon --console=plain *> D:\log.txt
```

注意：ambient `JAVA_HOME` 指向失效的 `jdk1.8.0_381`，必须在当前进程覆盖。
单模块测试实测约 40s（配置阶段 ~40-60s）；`--no-daemon` 下配置阶段偏慢，勿误判为卡死。

## 4. 关键代码事实（避免重复踩坑）

1. **`TokenStore` 的 identity lease API 只在 PR #18，不在 `main`**。main 上只有
   `saveTokens` / `readTokens` / `clear`。基于 main 的代码不得使用 lease API；
   评审 F-C1-1 时按分支区分（main 上"无守卫"是预期状态）。
2. `main` 上 WebDAV 曾是骨架：`ProviderHandle` 全 null，除 `OPTIONS` 探测外全部 `NotYetImplemented`。
   W8 已将其推进为 AUTH + BROWSE + PLAYBACK，并**移除** `declaredCapabilities` 里的 SEARCH。
3. `AddServerViewModel` 登录固定传 `Credentials.UsernamePassword`（`Credentials.WebDav` 全仓零引用），
   因此 WebDAV 的 `authenticate` 必须同时接受 `UsernamePassword` 与 `WebDav` 两种形态。
4. 播放栈的跨 origin 剥离由 ADR-030 的 `OriginScopedCredentialInterceptor` 负责（`PlayerFactory` / `MpvHttpBridge`）；
   WebDAV 自身的 PROPFIND 客户端**不跟随重定向**，凭据不发第二跳。

## 5. 下一条具体动作（按优先级）

1. **W1 设备层收尾**（唯一剩下的 W1 缺口）：`adb` 入 PATH + 专用 AVD，然后跑
   `scripts/verify-backup-restore.ps1 -Serial <专用模拟器> -OutputPath ... -IsolatedEmulator`，
   覆盖正式 SAF / 八个进程终止点 / 损坏 journal。在此之前 PR #18 不得宣布 SEALED。
2. **W4 实现**：按 `w4-endpointtestservice-findings.md` 的 W4-a/b/c 三个补丁**分别**交付
   （取消穿透 / 结果脱敏 / IO dispatcher + `Call.cancel()`），每项配回归。
3. **W9**：修正 README/TASKS 中已过时的阶段与能力描述（如"WebDAV 待实现""Jellyfin 完整实现"），
   并按 wrapper / version catalog / 构建脚本 / 实际 SDK 读取版本号。
4. **W8 跟进**：PR #18 合并后评估是否为 WebDAV 补身份守卫（复用 PR #18 的 lease API）；
   在本地真实 WebDAV 服务上补目录 / Range / seek 验收。
5. **W6 / W3 / W5 / W7**：见 `task-state.json`；W7 需真实 Jellyfin 实例，W2 归 Agent B。

## 6. 外部队列（不得在本线宣布通过）

- **设备/模拟器验收**（W1 SAF/八终止点/损坏 journal；W5 API 32/36 视觉门禁；W6 截图）：
  `adb` 不在 PATH。最小条件 = platform-tools 入 PATH + 专用 AVD。
- **W7 Jellyfin 真实验收**：需明确授权的测试实例（`BLOCKED_BY_SERVER`）。
- **W2 Media3 选轨**：`codex/fix-media3-track-selection` 仍在 `8e516e4`（零提交），归 Agent B。

## 7. 红线（本轮遵守）

未改 `main`；未触碰其他 Agent 的 worktree 与分支；未 merge / auto-merge / Release / 发行签名 / 个人设备安装；
未放宽 TLS、未吞异常、未删除安全检查；`local.properties` 未入库。

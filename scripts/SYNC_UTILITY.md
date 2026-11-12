# 手动同步最近更新

`github-nightly-sync.ps1` 从旧维护分支移植，当前以手动工具交付；仓库不安装计划任务。
需要 PowerShell 7、Git；GitHub 远端创建 Draft PR 时还需要已登录的 `gh`。

## 唯一入口

README 的自动摘要只由一个入口维护：本目录的 `github-nightly-sync.ps1`。

2026-11-11 收敛前，仓库还并行存在一份每日同步脚本（约 16 KB，带 `WINDOWS-SYNC` 标记）。
两者都会改写 README、提交、推送并开 PR，README 因此同时出现两个「最近更新」区块；
更麻烦的是两份锁分别落在 `%LOCALAPPDATA%` 与本仓库 `.git` 下，互不排斥，
在同一 worktree 并行运行会争抢 `README.md` 与索引。该脚本已删除，其区块一并移除。

保留本脚本、不反向收敛的判定与代价：

- 触发：本脚本按成功检查点起算，覆盖跨午夜的前一日工作，不依赖自然日边界。
- 范围：`MEDIAHUB:RECENT` 覆盖全仓库，已包含原脚本单独统计的 `windows/` 路径。
- 摘要：只写日期与提交 ID 的边界不变；原脚本把原始提交主题写进 README，
  与本脚本「主题可能含敏感信息」的边界冲突，故不吸收。
- 未吸收：推送被拒后的 `pull --rebase` 重试。本脚本推送的是以 main 为 base 的新分支，
  该重试没有适用场景。

### PowerShell 5.1：能解析 ≠ 能运行

- 版本门在第 1 行：`#requires -Version 7.0`。5.1 下执行会在加载阶段拒绝，
  报 `ScriptRequiresUnmatchedPSVersion` 并 exit 1。这是版本门，不是语法错误——
  同一文件在 5.1 的 `Language.Parser::ParseFile` 仍是 errors=0。
- 去掉该行后实测：默认预览 exit 0；`-Apply` 仍 exit 1。原因是
  `$ErrorActionPreference = 'Stop'` 配合 `2>&1` 会把 git 写到 stderr 的进度/警告
  转成 `NativeCommandError`，而 `git push` 默认就把进度写到 stderr。
  实测中推送已落到远端（远端分支确实前进），脚本却以 exit 1 结束——写入路径在 5.1 下
  既不可靠也不幂等。因此本脚本的 5.1 支持是**未证实**，不要按「能解析」推断「可运行」。
- 被删除的每日同步脚本同样不具备 5.1 能力：它以无 BOM 的 UTF-8 保存，5.1 按 ANSI
  解码，第 123 行的中文字面量直接触发 `Unexpected token`，连解析都过不去。
  本次删除因此**没有减少任何** PS5.1 可用路径。

默认预览只读取本地状态，不 fetch、不改 README、Git 引用、索引、检查点或锁文件：

```powershell
pwsh -NoProfile -File scripts/github-nightly-sync.ps1 -RepoRoot <仓库目录>
```

在独占操作的工作目录确认预览后，可显式执行：

```powershell
pwsh -NoProfile -File scripts/github-nightly-sync.ps1 -RepoRoot <仓库目录> -Apply
```

Apply 会先拒绝已有暂存或已跟踪改动，再 fetch；无更新时仅记录检查点。
有更新时刷新 README 的 `MEDIAHUB:RECENT` 区块，仅提交 README 并普通推送当前分支。
若当前为 main/master，会在写 README 前新建 `codex/nightly-*` 分支。
本工具从不合并 PR；有 GitHub 远端时复用已有开放 PR，或创建以 main 为 base 的 Draft PR。
推送成功而 PR 创建失败时，保留已推送分支并报错，检查点不推进。

首次运行回看过去 24 小时，后续从成功检查点起算，覆盖跨午夜的工作。
脚本排除自身生成的提交，重复执行不生成循环更新。README 仅含日期和提交 ID，
不复制原始提交主题。每个 worktree 使用其实际 Git 目录存放检查点和独占文件锁；
锁句柄一直持有至退出，不因文件时间戳较旧而抢占。

验证命令使用一次性本地 Git 仓库和本地 bare 远端，不连接 GitHub，不访问媒体账号：

```powershell
python -X utf8 -m unittest discover -s scripts/tests -p test_github_sync_safety.py -v
python -X utf8 -m unittest discover -s scripts/tests -p test_github_sync_convergence.py -v
```

新增的 `Sync utility safety` 工作流执行 safety 那组测试并保存 checkout SHA 和原始日志。
`test_github_sync_convergence.py` 已按工作流用的 `-p "test_github_sync*.py"` 约定命名，
剩余只差它的 paths 条目；那属于 CI 接入方的改动，本分支不动 `.github/`。
在 paths 补上之前，本 PR 的绿色检查**不包含**这个用例的远端执行结果。
工具的使用不代替项目测试或分支合并门禁。

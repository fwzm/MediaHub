# 手动同步最近更新

`github-nightly-sync.ps1` 从旧维护分支移植，当前以手动工具交付；仓库不安装计划任务。
需要 PowerShell 7、Git；GitHub 远端创建 Draft PR 时还需要已登录的 `gh`。

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
```

新增的 `Sync utility safety` 工作流执行同一组测试并保存 checkout SHA 和原始日志。
工具的使用不代替项目测试或分支合并门禁。

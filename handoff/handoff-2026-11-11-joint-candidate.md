# 2026-11-11 main-facing 联合候选

## 状态

工作区 `D:\deepseek_test\mh-g4-obs`，feature 分支 `codex/integrate-all-sources-20261111`。main 为 `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`。本轮发布前已重新 fetch，三个来源分支保持同一输入版本。

- 修复基线 `f5e1f69505dde679eb7b52c7726bdb1e317ac31e` 的 Android `42858324379` 与 Windows `42858324596` 全 success，原始单元 2822 tests、0 failures/errors、23 environment skips，两个 API 各 138 passed、1 个既有 skip。
- 同步与 Windows 备份的完整整合提交为 `8826d9a135e2672ace83a875dd5de85c4d7a98d4`，来源为 `b937e39ceb44171bb13c79cf090dbc6b22828a1c`。
- Windows 剩余 4 路径整合提交为 `c22f85a91bd9f98350eaed82c5ee8446c994e1e6`，来源为 `09ea1972e9478b20dab45509baa2a246564e16ab`。工作流引用漂移修正为 `da84867d`。
- 本次合并接入影院分支 `01de07d425fce32c4bee2286ba04ef2f02632f1d`，保留本地目录重新授权、当前播放器和表单所有权行为。README 保留 Windows/同步的状态区块，并加入四页影院截图。

## 目标与范围审计

形成以 main 为 base 的单一联合 Draft。当前 38 个开放 PR 中，37 个来源头由本分支与影院合并的祖先关系覆盖。#16 的关键证据已通过现有脱敏归档保留，包含单位修正、SLOW-FINAL OPEN、日志哈希与样本索引。当前文档还保留后续独立复核的证据限定。按仓库隐私规则采用该脱敏内容，原 SHA 保留为来源说明。

原来按两棵分支树的直接差异推断整合会删除文件，依据不足。共同祖先差异和三方预演显示，Windows/同步传入路径没有删除项，实际冲突各 21 个，影院 4 个。这些冲突已按当前安全边界与传入功能解决。

删除链同时保留 UA 清理与分步失败诊断。导入会话保留 generation/取消保护并接入完整信封及允许/跳过名单。同步日志用 JSON 字符串边界表示内部 ID，使 secret/token/password 后缀与稳定原因码正确分离，实际敏感值仍由统一脱敏器遮盖。

## 最终本地验证与失败记录

冻结的代码输入树为 `131c80479857e6385a023b9fbd96694eb34339ce`。本交接为验证后的文档增量。

**全仓单元的有效最终结果**

`testDebugUnitTest :core:model:test :provider:api:test :metadata:test --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1 --continue` 真实 exit 0，572 tasks 全部 executed，耗时 56m59s。24 个模块、328 个套件、3054 tests、0 failures、0 errors、0 skips。已配置本轮构建的 Windows CLI DLL，两个互操作套件实际执行。metadata 明确为 NO-SOURCE。

外部 fresh-run 校验同时要求开跑前没有旧 XML、每个应执行模块均有新报告、报告时间属于本轮、代码树与工作文件保持一致。该校验 exit 0、problems 为空。

**构建与 lint**

`assembleDebug lintDebug :app:assembleDebugAndroidTest` 真实 exit 0，1028 tasks 中 181 executed、847 up-to-date，耗时 12m11s。22 份本轮 lint XML，0 errors、127 warnings。两个 APK 的生成时间和代码输入树均通过校验。

- app APK SHA-256 `d69da863cd3668164313df6993d35a5fead6ffb773c282e6818c99eb63db5654`。
- test APK SHA-256 `e6f6bbbb042b4b741366e68b21ad18dbb93b31c67f2f6fcaa82801b7a4cdc568`。
- 包名 com.mediahub.app，versionCode 1。候选和设备已装 APK 的签名均为 `ca92e12753d1e55868c8fb5a9e942d67a407032ad8d1ce949aa94ad53c953a87`。

**其他门禁**

Windows 主套件 1959/0、WebDAV 104/0，App 构建 0 warnings/errors。两个实际 TRX 的逐类、逐方法、逐数据行门禁通过，最终 suite 数为 2。许可/文档静态门禁 57 checks、0 failed/warnings/skipped。影院脚本完整回归 66/0，Bash 语法通过。脚本回归实际 RED 1 证明影院合并前会遗漏 SAF 类，修复后执行列表同时保留备份、SAF 和影院用例。

**必须保留的失败与撤回**

第一次联合门在 AddServerScreen 自动合并位置产生悬空 else，server 编译失败。只看日志尾部漏掉了该失败，随后目录统计混入旧报告，曾错误报告 3054 全仓通过。该结论已撤回，相关 JSON 标注 RETRACTED_MIXED_STALE_REPORT_INVENTORY。确认失败后只停止了身份核实的本机 Gradle daemon，后续 daemon disappeared 是主动停止结果。

90 项旧生成报告按已验证的绝对路径白名单移到本机证据目录。修正 LOCAL/远端表单分支后，主应用与 AndroidTest 预检编译 exit 0，再从空报告目录取得上述有效最终结果。所有原始失败和旧报告均保留。

证据根为本机 `D:\deepseek_test\mh-main-readiness-evidence-20261111`。重点读取 `fresh-unit-run-result.json`、`fresh-build-run-result.json`、`active-pr-ancestry-audit.json`、`doc16-disposition.json` 与各阶段原始日志。真实设备 123e243f 当前在线，已完成安装身份的只读核验，连续 5 分钟复测窗口的异步问题仍待回复。

## 下一步

提交并推送影院合并，创建 base=main 的 Draft，附候选 SHA、输入树和上述证据分级。最终提交的 GitHub Android API32/API36、完整 build 和 Windows/同步工具门禁需要单独核验，不能用 f5e1 基线的 CI 代替。用户确认手机空闲后按同签名 install-r 规则执行受控真机复测并恢复设置。G7 保持 DO_NOT_MERGE，主分支合入需要用户对具体候选显式授权。

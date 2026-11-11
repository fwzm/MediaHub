# 2026-11-11 详情页验收文案适配

## 状态

联合候选 Draft #74 的父提交为 `7156533ac796766c9427138695d2edee9d8595e2`，base main。Windows run `42901352012` 两个 job success，原始报告为 1959 + 104 个单测与 23 个实际 Windows/Kotlin 互操作用例，全部通过且未跳过。同步工具 run `42901352384` success。

Android run `42901352025` 的两个 API 均实际执行 168 个仪器用例，各有 1 failure、1 capability skip。唯一失败为 LocalSafProductionUiFlowTest 第 157 行，详情页精确匹配旧文字“播放”超时。新版生产入口和 CinematicVisualTest 均使用“立即播放”。失败发生在进入播放器之前。当前 head 的 Android 原始单测 XML 为 328 suites、3054 tests、0 failures/errors、23 个专用 Windows peer 环境 skip，572 tasks 全部执行。构建和 lint 步骤 success，总 build 因 visual matrix failure 被正确拒绝。

## 修复

仅更新 LocalSafProductionUiFlowTest 的 2 处、WebDavProductionUiFlowTest 的 7 处详情页入口文字匹配。字幕、授权、回访和播放断言完整保留。产品代码保持父提交内容。

## 实际验证与失败保留

测试 APK 强制重编译使用 JDK21、--rerun-tasks、--no-build-cache、--no-daemon、--no-parallel、--max-workers=1、2GB heap、in-process Kotlin。exit 0，496 tasks 全部执行，27m38s。

自建 API36 emulator-5556 使用既有受控 AVD MediaHub_Owned_UI36_20261027。包名、versionCode、已装 APK 与测试 APK 的签名已比对，同签名 install-r。生产 APK SHA-256 为 `d69da863cd3668164313df6993d35a5fead6ffb773c282e6818c99eb63db5654`。

首轮本地执行在选择目录时失败，截图显示 System UI 无响应弹窗。该失败日志和截图完整保留。恢复模拟器并确认 HOME 就绪后重新完整执行，LocalSafProductionUiFlowTest 实际 OK (1 test)，47.041s，包装器 exit 0。WebDavProductionUiFlowTest 实际 OK (1 test)，32.013s，包装器 exit 0。后者使用进程内 MockWebServer 和合成视频，覆盖外挂字幕导入、记忆及撤销授权后的提示。没有实际服务器账号访问。

本机证据目录为 D:/deepseek_test/mh-main-readiness-evidence-20261111，汇总见 saf-cinematic-label-red-evidence.json 与 saf-cinematic-label-green-evidence.json。两组 CI 原始 XML、执行 manifest 与日志均已下载。

## 下一步

提交并普通推送当前 codex 集成分支，更新 Draft #74，等待新 exact head 的完整 CI。新提交的真机验收尚待独占设备窗口回复。G7 保持 DO_NOT_MERGE。

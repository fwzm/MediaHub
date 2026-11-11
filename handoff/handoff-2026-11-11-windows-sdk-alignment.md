# 2026-11-11 Windows 互操作 SDK 对齐

## 状态

main-facing Draft #74 的基线为 `fb81a87b7e2711dee2bf03525aed96008ab34289`。该版本本地 fresh 单元 3054/0/0/0、APK/lint/testAPK、Windows 2063 个用例与静态门禁的结果见上一交接。

Windows run `42899012392` 的构建、测试、播放探针和打包 job success。互操作 job 在构建 peer 之前失败，尚未执行 23 个互操作用例。完整失败日志为本机证据 `joint74-windows-failed.log`。同步工具 run `42899012427` success，Android run `42899012379` 当时仍在运行两个 emulator job。

## 根因与修复

同步分支引入了 global.json，固定 SDK 10.0.113，rollForward disable。主 Windows job 已安装精确版本，互操作 job 继承的 setup-dotnet 仍为 10.0.x。该 runner 实际只有 10.0.114、10.0.204、10.0.303、10.0.403、10.0.404 等版本，因此 dotnet build 报 compatible SDK not found。

互操作 job 改为安装 10.0.113，与仓库和主 job 一致。新增跨文件回归，要求两个 Windows job 的 dotnet-version 都等于 global.json 的固定值。

## 验证与边界

新回归实际 RED，1 test、1 failure、exit 1，旧值 10.0.x 与 10.0.113 不一致。修复后完整 Python 脚本 67 tests、0 failures，exit 0。许可/文档静态门禁 57 checks、0 failed/warnings/skipped，exit 0。workflow YAML 保持合法。

本增量只修改 CI 配置、CI 回归测试与本交接。产品代码和两个本地 APK 的字节保持前一轮验证范围。此前撤回的混合旧报告统计继续保持撤回状态，当前有效单元结果来自空报告目录的 fresh run。

## 下一步

普通 commit/push 到 `codex/integrate-all-sources-20261111`，更新 Draft #74 的实际 SHA 与 CI run。读取新提交对应的 Windows peer 23 用例、Android API32/API36 和总 build、同步工具检查。真机窗口的异步问题仍待用户回复。G7 保持 DO_NOT_MERGE。

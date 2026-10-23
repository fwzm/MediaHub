# 交接账本 · 2026-10-23 · M0-A3 设备阻塞与 exact-head 门禁

## 状态

- 当前代码 head：`a0f69607d073944842861046422ab4b32c0092d0`，分支 `feature/m0a3-ua-closure`。
- PR #36 的 head 与该 SHA 一致；PR #45 `integrate/m0a-m16-joint` head 为 `a9db37e`，不包含 `a0f6960`。
- `adb devices -l` 返回空设备列表，M0-A3 真机验证标记 `BLOCKED_ENV`。
- 未执行安装、覆盖升级、系统设置修改或真实服务器访问。

## 目标

- 在设备离线时保留原始阻塞证据，继续完成可执行的 exact-head JVM、APK 和 lint 门禁。
- 区分工作区未跟踪资产导致的失败与 Git 提交自身的结果。

## 证据

- 工作区 full gate 失败：`player/mpv/src/test/kotlin/com/mediahub/player/mpv/MpvTogglePolarityTest.kt` 为已有未跟踪文件，编译报 `startLocalFile overrides nothing`；该文件未修改、未删除、未提交。
- 干净 Git 来源副本 `D:\deepseek_test\MediaHub-clean-a0f6960` full gate 通过：559 tasks，160 XML，1311 tests，0 failures / 0 errors / 0 skipped。
- 工作区 exact head `assembleDebug lintDebug` 通过：998 tasks，APK SHA-256 `1430B2FEB6D064E9D87BA3D87A0E4FAEABD8F0D1D30D9FD20AC4D3970FF04A3E`。
- APK 身份：`com.mediahub.app`，versionCode `1`，versionName `0.1.0-alpha.1`，debug certificate SHA-256 `ca92e12753d1e55868c8fb5a9e942d67a407032ad8d1ce949aa94ad53c953a87`。

## 下一步

- 设备上线后，先核对上述 APK 身份，再使用受控 MockWebServer + `adb reverse` 执行 M0-A3：自定义 UA、重启恢复、同源重定向和跨 origin 隔离。
- 真机证据完成前，不把 M0-A3 标为 PASS，也不把 `a0f6960` 视为 PR #45 的组成部分。
- G7 继续 `DO_NOT_MERGE`，等待用户显式授权后再讨论主线合并。

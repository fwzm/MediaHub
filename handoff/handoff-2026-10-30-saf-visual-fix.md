# Handoff 2026-10-30 — SAF visual flow hardening

## 状态

- 分支 `codex/integrate-archive-ui-20261026`，SAF UI 测试修复提交 `ad697ec7266a93bb4f1dac924f0710da6af33aff` 已推送；Draft PR #72 仍 OPEN，G7 `DO_NOT_MERGE`。
- exact-head `99ffec77` Android 运行的原始 API32/API36 XML 已下载保存到 `D:\deepseek_test\mh-main-readiness-evidence-20261025\ci-40976715120`。
- API36 失败点是 DocumentsUI 二次授权的 `android:id/button1` 旧 `UiObject2` 点击抛 `StaleObjectException`；API32 失败点是保存目录后输入法仍可见，播放器信息入口在 IME 覆盖下 15 秒超时。
- 修复只改 instrumentation：DocumentsUI 行/动作/确认按钮每次重新查找并用当前 bounds 坐标点击，保存目录后只在检测到输入法时收起；无生产播放器逻辑改动。
- 本地 `:app:compileDebugAndroidTestKotlin --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`：exit 0，347 tasks executed。

## CI 与边界

- 上一 head `99ffec77` 的 Windows CI 成功；Android visual 结果分别保留 API32 ComposeTimeout 和 API36 StaleObject 原始证据，随后因新 push 被 workflow 取消，不升格为 PASS。
- `ad697ec7` 新 head 的 Android/Windows exact-head CI 尚未完成触发核验；必须读取新 run 的 XML 和日志，不能用旧 run 背书。
- Android 诊断导出仍仅有 JVM/Hilt/编译证据，设置页真实点击、系统分享和物理设备导出未验收。

## 下一步

1. 等待 `ad697ec7` exact-head CI，优先核对 API32/API36 `LocalSafProductionUiFlowTest` 是否通过以及其余报告是否完整。
2. 若 SAF 仍失败，保留新的截图/XML，按具体失败行继续修测试时序；不修改生产播放器以掩盖测试故障。
3. 保持 Draft、不得 merge/close/mark-ready；继续做最终 main-facing SHA/base/CI/产物一致性审计。

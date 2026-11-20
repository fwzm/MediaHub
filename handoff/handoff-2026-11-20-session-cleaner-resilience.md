## 目标
type:goal 让删除媒体源的级联清理"尽量全清"：任一 Provider 的会话清理失败不得让其余 Provider 跳过，且首个失败原样抛给调用方（不破坏删除媒体源的日志与失败步骤契约）。

## 任务状态
type:state provider/api 的 CompositeSessionStoreCleaner 已从"裸 forEach 遇错即停"改为逐 Provider 隔离清理 + 首个失败原样重抛 + suppressed 点名失败 Provider；协程取消原样透传并立即停止扇出。RED 6 tests/3 failed、GREEN 35 tests/0 failed、NC 3/3 各恰好 1 红并逐字节还原。

## 已试方案与失败原因
type:dead-end 首版设想把失败包装成新的清理异常类型抛出——会破坏删除媒体源日志行对"真实失败原因类与消息"的点名契约（同类事故在 Round 36 已发生一次），故放弃包装，改为保留原始异常为顶层、诊断挂 suppressed。

## 进度与下一步
type:progress 代码与测试已落盘并通过聚焦门禁；待全量 testDebugUnitTest assembleDebug 结果 → commit/push/Draft PR → queue.json 追加条目 → 复查 CI。下一步：全量绿后提交，并复核 feature/server 的 SESSION_STORE 步骤契约未被改动。

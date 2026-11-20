# Round 31 交接：Provider 注册表重复注册的失败信息可诊断化

## 目标
type:goal provider/base 的 DefaultProviderRegistry 在 @IntoSet 多绑定重复贡献同一 ServerType 时，
基线虽然会在装配期失败，但错误信息只有固定串 "Duplicate provider server type"，
既不说是哪个 ServerType，也不说冲突双方是谁 —— 装配方只能逐个模块翻绑定代码去猜。
本切片把失败信息变成可诊断：点名冲突类型 + 双方 provider id，并按类型名稳定排序。

## 任务状态
type:state 已交付 Draft PR（分支 feature/registry-duplicate-type-20261120，worktree D:/deepseek_test/mh-registry-dup-20261120，
base origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7）。
实现只改 provider/base/src/main/kotlin/com/mediahub/provider/base/DefaultProviderRegistry.kt 的 init 校验块
（+13/−4）：duplicateTypes / duplicateIds 两个 groupBy 收集冲突，require 的消息里列出 "EMBY <- emby-a, emby-b"，
重复 id 消息列出全部冲突 id；索引与 descriptors 逻辑未动（保持 registrations 单次读取 + unmodifiableMap）。
新增 provider/base/src/test/kotlin/com/mediahub/provider/base/DefaultProviderRegistryDuplicateTypeTest.kt（5 tests）：
重复 serverType 必须失败且消息含 EMBY 与双方 id；检测与声明顺序无关（LOCAL）；重复 provider id 必须失败且点名 id；
不同 serverType 仍可正常装配；空集合仍允许。
RED EXECUTED_FAIL：11 tests / 3 failed（基线实现下三个新增断言全红）。
聚焦 GREEN EXECUTED_PASS：11 tests / 0 failed / 0 skipped（DuplicateTypeTest 5 + 既有 DefaultProviderRegistryTest 6）。
NC 3/3 各恰好 1 红并逐字节还原复验（sha256=1802b8578c308cdb23c2b4a175ba4ed6039ea5b4872c49f2aca5509158b72a00）。

## 已试方案与失败原因
type:dead-end 首次 RED 用“静默覆盖”叙述（以为基线是 associateBy 无校验）：实际当前 main 已有 require 校验，
  首版测试断言 fail() 在基线实现下不成立，RED 只有 2 红且红在错误位置；改为锁定“消息可诊断”后 RED 3 红成立。
type:dead-end NC-C 最初用 require(registrations.isNotEmpty()) 变异 → 与既有 empty factory set 用例同向，1 红但属重复防线；
  改为把类型消息里的 id 换成 displayName（唯一防线）后成为有效对照。
type:dead-end 用空字符串 newText 删行后无法用同一 oldText 还原（oldText must not be empty），必须成对锚点编辑才可逆。

## 进度与下一步
type:progress 全量门禁 testDebugUnitTest assembleDebug 已启动（后台 job），结果待回填；APK 与测试总数以日志为准。
下一步：commit + push + Draft PR；queue.json 追加 Round 31 条目（PARTIAL，真机 NOT_RUN）；
用 40 位完整 SHA 复查本 PR 的 exact-head CI run，success 才回填 CI_SUCCESS。


# 当前候选真机复测与首页契约核验

受测源码为 `4c649768128a01e350e7111826487d0ffd06f430`，沿用 Draft PR72。
本文仅记录实际执行范围，不能据此宣布所有分支可合并。G7 仍为 `DO_NOT_MERGE`。

## 真机证据

设备 `123e243f`，Xiaomi 14 Ultra，API36。APK SHA-256：
`5059939acf7dc677868de32588e364939200310a3c2ea4e74c23ba66fd2c565b`。
安装前后核验包名、versionCode1及同签名，仅使用 `adb install -r`。
证书 SHA-256 为 `ca92e12753d1e55868c8fb5a9e942d67a407032ad8d1ce949aa94ad53c953a87`。

使用已有受控本地 WebDAV 源、唯一 itemId 和600秒彩条夹具，在中段操作。
真实账号界面、原始 UI dump、媒体会话及音频服务 dump 均仅保存在本机；本文不包含账号或服务器信息。

| 范围 | 真实观察 | 结论与限制 |
|---|---|---|
| MPV 暂停/重复暂停/toggle | 成对截图中的时间码冻结，Play/重复Play后推进 | 单轮 `EXECUTED_PHYSICAL_DEVICE_PASS` |
| 暂停后 HOME/返回 | 后台会话 inactive，返回 active且PAUSED；返回2秒、6秒均非黑且保持同一帧 | 单轮通过画面恢复范围；初始帧38与返回帧46不同，会话位置差334ms，不宣称零漂移 |
| 内封字幕 | 两条SubRip可枚举，中文和英文实际显示，Off后无遮罩画面没有字幕 | 受控内封字幕通过，未覆盖真实Emby与外挂发现 |
| AC3 | 菜单确认选中ENG AC3，运行信息为LPCM，应用48kHz输出音轨活动 | 解码/输出元数据通过 |
| AAC切换 | 切换后重新打开菜单，ZHO AAC为选中、AC3未选中；恢复播放 | 单次切换通过，未覆盖所有切换序列 |
| 扬声器可听性 | 用户授权临时低音量；AC3与AAC分别在系统10/150与20/150、非静音时播放，用户现场确认“两次都听到了” | 受控两轨现场确认通过；每次结束均核验恢复MUSIC静音、扬声器0音量 |
| 冷启动自定义UA | 3次新进程，首个受控PROPFIND均带预期自定义UA | 3轮受控WebDAV通过，未扩大到所有协议/重定向 |
| Media3/真实Emby/外挂字幕 | 本轮未执行 | `NOT_RUN` |

最初音量为0且静音时，应用活动音轨的系统增益为负无穷；该观察不能解释成AC3不支持。
临时音量测试保留了原先静音结果及最终还原状态。测试过程中未卸载、清数据、降级或修改旋转设置。

## 首页和服务器回归

在同一源码及干净工作区执行：

```text
:feature:home:testDebugUnitTest :feature:server:testDebugUnitTest
--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
--console=plain --continue
```

使用JDK21、独立Gradle daemon目录、1536MiB堆、in-process Kotlin编译。
109个任务实际执行，7分2秒，exit0；49项测试，0失败、0错误、0跳过。
开始/结束的源码SHA、输入哈希及干净工作区均核验一致，原始XML另存本机。

| 测试类 | 通过数 |
|---|---:|
| AuthNavigationPolicyTest | 4 |
| HomeViewModelTest | 12 |
| AddressTestResultPolicyTest | 3 |
| AddServerLoginErrorTextTest | 5 |
| AddServerViewModelWebDavTest | 3 |
| ExistingServerEditPolicyTest | 5 |
| ServerEditorViewModelTest | 17 |

## 旧分支逐子契约处理

对照旧架构分支 `47eca35054acb3718282344786ef341b990462f0`，共同基线为
`208d9a3c046d3705f6847bd780c4372ee9fbc0bb`。类名消失不等于功能缺失，旧测试存在也不等于实际端到端能力成立。

| 旧契约 | 当前实现及证据 | 处理 |
|---|---|---|
| 删除ExistingServerEditPolicy及其测试 | 当前保留该策略；5项真实通过，按ServerType匹配descriptor、已有记录同id/完整元数据、锁定类型、新建不继承 | 保留当前实现，拒绝删除有用约束；ADR015继续保留持久化ServerType |
| ServerSavePlanner的同id、完整元数据和新增/更新标志 | 当前buildDraft等价复制候选并覆盖已有身份/元数据，另锁定原类型；AddServerViewModel实际保存分支选择updateServer或addServer | 保留当前策略，避免重复planner；原测试的局部整数计数不算数据库证明 |
| Planner的Local content URI复制 | 旧用例只测试纯复制，当前泛型策略不检查或改写候选baseUrl | 仅源码层契约对应；不宣称SAF选择、持久授权或目录浏览已实现 |
| forceRestore的5项旧测试 | 当前12项中实际覆盖最新DB、失效/未登录覆写、已有id强制恢复、非认证清理，另有身份世代和挂起DB竞态约束 | 由当前实现/测试替代，不引入旧中央认证协调器 |
| 点击认证源时的重登录决策 | 当前AuthNavigationPolicy及4项测试覆盖SignedOut、Expired、Mismatch；网络/权限/服务异常不强迫重登，非认证源不重登 | 认证子契约保留；SAF重授权子契约仍未覆盖 |
| ExistingServerMode/ServerEditModePolicy的Local重授权 | 当前仅有既有源重登录状态与reauthorizeId，未接入完整SAF模式 | `DEFERRED`，ADR020明确只预留接口；不能记作功能等价 |
| HomeScreen/HomeViewModel的旧Local重授权入口和提示 | 当前保留认证路由及世代保护；Local重授权入口尚未接入 | `PARTIAL`，其余SAF工作仍需完成或另作明确产品决策 |
| ServerCard改用providerId/中央AuthenticationState | 当前ServerType标签与AuthSessionState支持恢复中、过期/身份变更及暂时异常；ADR015/016保留现有身份/存储模型 | 明确架构替代，不是旧开放providerId架构已并入；本次49项不提供卡片视觉证明 |
| AddServerScreen/AddServerViewModel的旧大范围差异 | 本批只核验既有源加载、buildDraft和持久化调用点 | 整个文件仍未完成逐项处理，不标完成 |

## 同一受测源码的完整CI

Android运行40294040788已完成，build/API32/API36三个任务均success。
已下载原始报告，headSha与checkoutSha均逐位匹配4c649768，base为11ab25a4；
产物名称中的8c5e24fb是事件merge SHA，不能与实际checkout混淆。
冻结该源码的验证器复核manifest、报告哈希、模块来源及91个必需测试类的完整方法集，exit0。
2700项唯一JVM测试中2677通过、23项预期跨端环境跳过，0失败、0错误。
两个新增WebDAV suite分别18项和15项实际通过。所有已下载lint XML均无Error/Fatal。
API32和API36各138项唯一测试，137通过、1预期能力跳过；两个新增解析器方法均实际通过，冻结验证器无问题。

Windows运行40294040795也逐位匹配4c649768。原始TRX为1592+104项通过，
含27项TLS测试；两个实际JVM互操作XML分别11和12项通过、无跳过，
因此Android环境跳过的23项另有该源码的实际跨端执行证据。
原生探针记录文件加载、播放推进至3秒、0错误日志、exit0；这是CI无窗口探针，不代表Windows GUI或物理扬声器验收。

这些结论是4c生产源码与本批证据。追加本文后的文档提交须另查自身CI，不冒用4c的绿灯。
旧架构另13条路径已有明确处理记录，尚66条未分类；其中本批的SAF延期及部分覆盖仍不是合并就绪。
Media3真机、真实Emby、外挂字幕、完整SAF以及其他旧架构差异仍各有独立门禁。
本次未修改生产代码，因此没有新增生产修复的RED/GREEN声明。

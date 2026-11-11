# 凭据诊断脱敏与旧认证契约核验

基于c789f00d81d8f9afdf152fb7f0de455673254d3d，在既有feature分支和Draft72完成本批工作。
对照旧47eca350架构分支与208d9a3c共同基线；不复制旧分支的凭据存储格式、开放providerId模型或历史交接结论。

## 生产改动

当前四种Credentials数据类原先会把用户名、密码、Bearer令牌或API Key放入默认toString输出。
移植旧分支的有用子契约：保留凭据类型名称，字段统一脱敏。
覆盖直接toString、字符串插值、List/Map诊断容器以及copy后的实例。
字段读取、构造参数、数据类copy/equality、认证请求及持久化格式均未修改。
这不表示所有DTO或所有任意日志均已通过隐私审计。

## 真实执行证据

第一次执行在测试进程启动时发生Windows JVM内存提交失败（errno1455），没有测试XML或断言执行。
原始日志和JVM错误报告保留在本机，单独记为环境失败，不能作为RED。

确认该进程终止后，只对验证进程使用JDK21、Gradle768MiB/Test256MiB、SerialGC、2个可用处理器、
单worker/单fork和in-process Kotlin编译；未改系统配置、停止其他进程或放宽任何断言。
`--rerun-tasks --no-build-cache`重跑4项，得到4个真实AssertionError、0错误/跳过，6任务、2分11秒、exit1。
原生产文件哈希在该次RED前后保持一致。

随后给四种既有类型增加脱敏toString，并在CI完整方法集门禁中固定CredentialsRedactionTest。
完整模块GREEN：507项，0失败、0错误、0跳过，155个任务全部实际执行，10分36秒，exit0。
模块分别为API29、security17、base6、Emby200、Jellyfin111、WebDAV144。
213个输入文件的执行前后哈希一致；4个新增用例都实际通过，原有认证和存储测试保留。
最终提交与CI状态以Git和外部执行台账为准；不把未来提交CI写成已通过。
原始失败和环境失败均保留，不因最终通过而删除。

## 旧契约处理

| 范围 | 当前保留或移植 | 明确没有声称的能力 |
|---|---|---|
| 四种已实现Credentials | 新增脱敏输出；实际RED/GREEN验证诊断容器和copy | 旧OAuth、设备码、Cookie、QR等未来输入类型未接入 |
| AuthResult/MediaAuthProvider | 保留Success(user)、AuthSessionState及provider负责认证/恢复/退出 | 未引入旧中央AuthenticationCoordinator与SessionRestoreResult模型 |
| TokenStore/CredentialVault | 保留既有格式、命名空间、加密SecretStorage、世代与lease保护，保留被旧分支删除的原测试 | 旧pending/session新codec未迁移；不宣称现有Token与非敏感会话元数据是一个加密原子记录 |
| 旧中央协调器测试 | 当前真实provider用例覆盖登录、不存密码、401清理、403/网络/解析异常保留、退出本地清理 | 不是旧6项全部语义等价；未采用未实现provider的长期暂存输入流程 |
| 能力接口/ProviderHandle | 保留DETAIL独立能力及query/favorite/identityLookup、当前final progress和能力子集约束 | 不删除现有能力来匹配旧接口搬迁；旧进度策略另有既有处理记录 |
| Registry/Descriptor | 保留ServerType持久化与真实注册工厂，避免无迁移地重解释旧数据 | 未加入旧8项COMING_SOON占位目录；占位目录本身也不实现这些协议 |
| ConnectionTestRequest | 保留公开协议嗅探，认证验证由authenticate/restore完成；保留结构化错误和操作指引 | 不提供泛化的携带凭据/任意parameters探测接口 |
| MainActivity | 当前已具备旧分支添加的AndroidEntryPoint | 没有额外UI生产改动；旧真机结果仍绑定其受测APK |
| Emby登录DTO/UserMapper | Pw wire名和映射保留；旧本地属性重命名及注释改动不重复移植 | mapper本身不能证明恢复用户身份已校验 |
| AddServer表单 | 保留真实WebDAV登录/加密密码说明、HTTP403指引、密码显隐、地址规范化及原id更新 | SAF仍未接入；切换provider清理凭据的旧行为仍待移植 |

## 已识别、尚待完成的具体缺口

这些是本批源码对照结果，不能因为记录了处理意见就算合并就绪：

1. 旧EmbyApiRoot会清除根地址外部空白并处理endpoint分隔符；当前EmbyEndpointResolver未覆盖同一边界。
   应针对当前生产resolver移植测试和最小修复，不能只复制另一个未使用的helper。
2. 旧Emby身份头会转义字段中的引号和反斜线；当前直接插值。
   保留当前X-Emby-Authorization头名及独立X-Emby-Token，只移植有用的字段转义。
3. 旧EmbySessionValidator检查恢复响应用户id非空且与已存用户一致；当前恢复在世代校验后直接映射为Authenticated。
   需对当前AuthProvider增加受控空id/错id/正确id用例，同时保留取消、401/403和身份世代约束。
4. 旧Emby/Jellyfin探测使用ProductName区分服务器产品；当前只校验Id/Version。
   移植前须核对既有缺少ProductName的兼容夹具与协议依据，不能机械套用更严格条件而回归已有服务器。
5. 旧AddServer切换provider时清空用户名和密码；当前保留这些字段。需在当前ViewModel做RED/GREEN，
   并保护已有服务器模式的类型锁定、地址状态及认证任务所有权。

另有完整SAF目录选择、持久授权、浏览/播放及重新授权尚待实现或明确产品决策，不能以ADR020接口预留代替。
本批30条路径都有明确处理记录，剩36条未分类。
处理数量包括架构替代、部分覆盖及待移植项，不等于解决了同样数量的功能。
G7仍为DO_NOT_MERGE，所有候选保持Draft，不写main、不合并/关闭/mark-ready任何PR。

# Jellyfin 静态播放协商移植

父提交01e165d7ee03d6dbe4153cbfcba2e9163b7a5ce8；沿用独立feature/Draft72，G7 DO_NOT_MERGE。
这是本地统一整合分支f7af7699的已确认功能缺口移植，不导入其旧历史，也不回退当前收藏、认证或根地址修复。

## 结果行为

PlaybackInfo 按调用方 EnableDirectPlay 传值，始终 EnableDirectStream=true / EnableTranscoding=false。
没有可直接流来源、调用方允许DirectPlay且没有显式字幕语言偏好时，最多再协商一次：
绑定首个有ID的非ISO来源，并设 SubtitleStreamIndex=-1 让服务器不处理字幕。
重试只接受同一个来源ID，仍必须 SupportsDirectStream=true；不能凭 DirectPlay 标志绕过能力检查，
不会循环、不会申请转码。401、取消及损坏响应保持原错误语义。

最终仍交付配置服务器同源的静态URL，Token只进鉴权头；当前收藏与其他API方法保持不变。
ISO 的路径、来源容器和条目回退容器均受保护，不能生成stream.iso，也不对ISO进行二次协商。
原媒体的内嵌字幕字节不删除；服务器外挂字幕处理被抑制后，不宣称已实现Jellyfin外挂字幕发现/交付。
显式字幕语言偏好会阻止这项隐式抑制。

## 执行证据

移植九项回归并更新两项既有请求/重试断言；新增selector入口先为未接线占位，原provider/API/DTO三个生产文件在RED时未改。
RED完整播放测试17项：8失败、0错误/跳过；78任务全部强制执行，77.521秒，exit1。
原始报告保留，失败类型不统一冒称AssertionError。

最小接线后，Jellyfin123、Emby216、provider-base6、provider-api29，共374项全部通过，0失败/错误/跳过；
`--rerun-tasks --no-build-cache`，129任务全部执行，127.921秒，exit0；829输入hash前后一致。
目录适配与诊断调整后，最终同四模块再次强制回归374/0无跳过，129任务/172.061秒/exit0，829输入仍稳定；首次结果保留。
APK+Jellyfin lint：761tasks/19executed/742up-to-date，63.877秒，exit0；0错误/4既有警告。
Python门禁自测首次63项43.936秒通过，最终63项57.143秒通过。

APK SHA-256：54a3f42ecdbc5e99548335f6a6ae58ae63e9ed561727e2b9be3a946779fac27e，未安装。
没有本轮真机或新的真实Jellyfin服务器验收，不使用之前字幕外观真机结果为本APK背书。

## 回放输入的来源边界

两个JSON沿用旧分支的合成回环服务器捕获记录，旧版本标签为jellyfin12.1；本轮没有独立验证该服务器版本。
只回放HTTP请求/响应，资源中的媒体路径均指向旧受控夹具，不访问这些路径或真实账号。
首次核对发现旧resource_sha256对应CRLF字节，Git blob为LF；已验证只转换换行即可重现旧hash，
没有改动JSON字段值。原provenance记录保留，并增加repository_normalization，分别列捕获hash和Git-LF资源hash。
不能把旧记录中的“真实响应”名称或此回放测试的通过写成当前在线服务器通过。

## 当前CI与后续

父01e的Windows40357948872服务已通过，原始报告需单独核对。
Android40357948654的API36在下载Google系统镜像时报“Error on ZipFile unknown archive”，
模拟器未启动、没有TEST XML；记BLOCKED_ENV，不能当UI回归失败或通过。
API32为137pass/1skip/1fail；备份用例实际通过，目录用例已越过类型按钮，打开选择器后在roots抽屉定位处失败。
新适配复用备份用例的可见目录/入口逻辑，不再要求已经位于Downloads时仍有抽屉按钮；尚需新CI验证。
旧诊断文件实际是“run-as: unknown package”文本：UTP在任务结束后已卸载目标，后置采集不可行。
现改测试中写additionalTestOutputDir并上传UTP附加输出目录，原错误文本及job日志保留。
新提交仍须跑完整CI和实际SAF撤权重授权/备份预览，不能用本轮Provider单测替代。
hardening/unified两组本地差异及远端Windows/同步增量仍未全部完成，整体目标保持active。

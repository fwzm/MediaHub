# Jellyfin静态协商交接

## 状态

独立worktree D:/deepseek_test/mh-g4-obs；codex/integrate-archive-ui-20261026；父01e165d7/Draft72。
从f7af7699移植Jellyfin源绑定静态协商与ISO容器保护，保留当前收藏/认证/根地址API。
真实commit/push/CI状态以Git及外部active-closure.json为准，G7DO_NOT_MERGE。

## 目标

默认字幕策略阻止静态播放时最多一次同来源ID重试；不转码、不接受另一来源、显式字幕语言偏好不隐式抑制；
路径/容器/条目容器均拒绝ISO。整体所有分支main准备仍active，不把这一部分完成当统一分支全部完成。

## 失败与通过

九新增+两既有断言移植，播放类RED17项8失败/0错误跳过/78forcedtasks77.521s/exit1，原provider/API/DTO三文件未改，
仅新增selector未接线占位；保留原始失败类型与日志。首次fixturehash断言失败由CRLF捕获字节与Git-LF差异造成，
已验证换行转换可重现原hash，原provenance保留并补归一化说明，未更改JSON字段值。
GREEN四模块374/0无跳过（Jellyfin123、Emby216、base6、api29），129forcedtasks127.921s/exit0/829输入hash稳定。
CI目录适配/采集修正后最终GREEN2仍374/0无跳过，129forced172.061s/exit0，829输入稳定。
APK/lint761tasks19executed/63.877s/exit0/0错误4既有警告；APK54a3f42ecdbc5e99548335f6a6ae58ae63e9ed561727e2b9be3a946779fac27e未安装。
Python首次63项43.936s、最终63项57.143s通过；本轮仅历史合成回放与MockWebServer，不新增真机或真实服务器结论。

## 下一步

父01e Android40357948654：API36系统镜像下载“Error on ZipFile unknown archive”，未启动模拟器/无TEST XML，
原始job日志和不完整artifact已保存，应记BLOCKED_ENV，不把它认成客户端测试失败。
API32实际137pass1skip1fail，备份用例PASS，目录测试在打开选择器后找roots抽屉失败，已改可见目录导航，须新CI实测。
后置run-as诊断只有unknown package文本，已改测试内写UTP additionalTestOutputDir并上传对应目录；原失败保留。
不要因观察超时重启；父build仍按实际job查，Windows40357948872原始1592+104/实际peer23已核对通过。
本轮所有本机Gradle/Python句柄2474/46671/61999/25894已终止，不重复启动旧任务。
新head需完整CI，特别是SAF撤权重授权与备份正向等待；不能把Jellyfin单测替代UI门禁。
剩hardening/unified组，继续核对native音轨归属、字体策略和隐私语义；远端Windows9622c1a7、sync74f64301仍未整合。
保护其他worktree/用户资产；不写main、不merge/close/mark-ready，不对真实服务器扫描或清手机数据。

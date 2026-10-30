# 字幕外观接线与本地历史分支核对

父提交9a0edee374578d6838ea2327bfe16f3ae3f7f15b；独立feature worktree/Draft72；G7 DO_NOT_MERGE。

## 修复的实际缺口

本地统一整合分支f7af7699包含原生字幕外观接线，但当前主候选没有对应接口和调用。
现将既有SubtitleStyle/字幕字号从真实偏好流传到播放门面，再传给mpv。
起播前设置原生选项；播放中用属性命令更新；停止后重进、Media3降级到mpv时重放最新偏好。
初始化期间到达的偏好在资源发布后补齐；每条原生命令前校验会话和偏好版本，释放或新版本可中止旧写入。

映射包括字号、文字色、描边色、背景色、边框类型、描边宽度、阴影偏移、整数底边距和ASS覆盖开关。
颜色是r/g/b/a且alpha正向，数值有限并按既有范围钳制；默认不强制覆盖作者ASS样式，不改变当前Roboto字体策略。
可见背景与独立描边色阴影不能用这些mpv选项同时表达；保留背景色，阴影偏移在背景框模式中成为内边距，
与移植来源的明确边界一致，不宣称逐像素等同Media3。

参数类型按固定版本的[mpv0.41选项文档](https://github.com/mpv-player/mpv/blob/v0.41.0/DOCS/man/options.rst)
与[原生选项表](https://github.com/mpv-player/mpv/blob/v0.41.0/sub/osd.c)核对。
[client.h](https://github.com/mpv-player/mpv/blob/v0.41.0/include/mpv/client.h)区分初始化选项和运行期属性，
所以播放中的写入使用set属性命令，未照搬旧分支在运行期调用初始化选项API的做法。

## 真实执行结果

- RED13项全部为真实断言失败，0错误/跳过；184任务全部执行，147.439秒，exit1。
  三个待修生产文件在RED时未改；新增接口为默认空实现、映射为明确空表，用预期参数表断言验证缺口。
- 第一次完整GREEN423/0无跳过，184强制任务/178.291秒/exit0；报告保留。
- CI测试适配调整后，最终三个模块再次强制rerun/no-cache：mpv126、engine168、feature-player129，
  合计423项全部通过，0失败/错误/跳过；184任务全部执行，172.726秒，826输入hash前后一致，exit0。
- 第一次APK/testAPK/相关lint exit0，831tasks/71executed/128.824秒；engine0警告、mpv1警告、feature-player6警告，0错误。
  最终补做修正后的测试APK和app lint：974tasks/46executed/928up-to-date，220.033秒，exit0；
  app lint0错误/86既有警告。应用APK仍与真机实测8dadd1c53f0b逐字一致；修正后的测试APK为
  63a0213746099e1a6806a6632eb3c7293386739a6059ad4a34481d50d1c15136，尚未执行。
- Python门禁自测63项通过，40.186秒；没有把CI实测失败当作门禁自测通过。

## 真机范围

123e243f/Xiaomi14Ultra/API36，包名/versionCode1/签名一致后install-r，安装后hash核对。
实测APK SHA-256为8dadd1c53f0b9d0b845d1e3be33ecda38fdd869fe7da84898a37206752d5bbe3。
使用之前独立受控SAF目录中的600秒合成片与中文SubRip，一轮原生mpv验收：

1. 原18sp白色字幕实际可见。
2. 暂停画面170.533秒，真实字幕面板白→黄，暂停画面的字幕同步变黄。
3. 设置页18→24sp后退出重进，184.733秒仍为黄色且字形明显增大。
4. 面板恢复白色，设置页恢复18sp，mpv偏好不改、媒体音量0未调整，返回首页。

初次在首帧解码前立刻暂停的黑画面也保存；明确播放后正常推进和暂停，不作为外观失败或通过的替代测量。
本轮不作可听音、真实Emby、所有ASS/PGS效果或全设备验收声明；alpha、非有限数值、阴影边界及释放竞争为JVM证据。
原始UI、截图、日志只在本机私有目录，不入仓库/PR。

## 本地历史分支内容核对

| 分支组 | 路径数 | 保留且方法正文匹配的旧测试数 | 差异处理 |
|---|---:|---:|---|
| a4-b-gates/32dbda26 | 18 | 31 | 旧必需套件仍是当前集合子集；迁移测试扩展到schema5；可见设置开关定位已加强 |
| a4-b-network/838df259 | 18 | 67 | 三项生命周期测试用真实阻塞体/可控时钟加强；DAV新增成功状态与根结构证据；UTF8策略不变 |
| a4-b-subtitle/d693d9bb | 9 | 76 | 缓存及三份测试文件逐字保留；mpv由selected-only加强为主sid确认，会话归属继续保留 |
| canonical-detail/7bf570a2 | 24 | 56 | 图、resolver、selector、Emby身份实现逐字相同；factory测试扩展PROGRESS/FAVORITE并更名 |

以上关联方法在父35b原始CI实际通过，改变的实现也对照了后续修复；本轮相关mpv/engine测试再次通过。
这是内容核对，不是合并旧历史或新head全CI通过。另两组hardening/unified仍待完成，统一分支的音轨、字体策略及
Jellyfin协商差异尚未全部核对；本次只补其中已确认的字幕外观缺口。

## 父9a0 CI失败与修正边界

Android40347272292：JVM2777=2754pass+23环境skip，103必需类/329份冻结Git源码校验0问题，lint无错误；
API32为137pass/1skip/1fail，API36为136pass/1skip/2fail，整体FAIL。
两个API都在目录UI测试组合hasText与hasClickAction的未合并语义查找处超时；改查合并语义，仍要求点击能力。
API36另在备份正确密码后未找到恢复预览；等待函数曾在1秒内没看到Working就直接继续，
现要求先观察到解密进行中或预期终态，再继续原有预览/数据不变断言。
这项等待修正尚需新CI验证，不把原因推断写成已修复。保留原XML和日志，并补失败UI现场收集。

Windows40347272278原始1592+104与实际JVM peer23通过；这些只属于父9a0，不能给本轮新提交背书。
新head的完整UI、特别是系统撤权→目录错误→原记录重授权仍待实际执行；不得用编译或JVM通过替代。

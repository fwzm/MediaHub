# W5.1-R3 Gate A 实测记录

- 日期：2026-10-14
- HEAD：`9739cfff9b6f794e9e070a6877b7a212e3aa9faf`（== origin/feature/windows-client）
- 被测程序：`D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe`
  构建于 18:33:31，sha256 前缀 `741cfef62788b5a0`（与本次 HEAD 对应，非旧包）
- 服务端：WsgiDAV 4.3.3 + cheroot 11.1.2，`http://127.0.0.1:8181/`，Basic 认证
- 脚本：`windows/tools/acceptance/gateA-webdav-browse.ps1`（A1）、`lib/uia-id.ps1`（驱动）

## 逐项结果

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| A-入口 | **EXECUTED_PASS** | 精确 id `browse\|webdav%3A127.0.0.1%3A8181\|root` → `INVOKED(depth=1) Cheroot/11.1.2`；页面来源标注 `· WebDav` |
| A-PROPFIND | **EXECUTED_PASS** | `PROPFIND / depth=1 auth=Basic` = 1 |
| A-媒体 | **EXECUTED_PASS** | 见下 |
| A-Seek | **NOT_RUN** | |
| A-退出释放 | **NOT_RUN** | |
| A-边界 | **NOT_RUN** | 目录递进/返回/空目录/离线/重启/generation guard |

## A-媒体：三条独立证据

**1. 引擎状态**（播放器页实测读数）

```
Chinese 中文 视频 空格.mp4
0:22 | 1:30
640×360 · H.264 / AVC · MPEG-4 part 10 · hwdec=d3d11va · bt.1886 · aac · 25fps
```

时间轴在 1:30 的片长上推进到 0:22 —— 是**持续变化的播放位置**，不是静态的已加载状态。
解码路径为 `hwdec=d3d11va`（真实硬解，非软件回退）。

**2. 服务端媒体读取**（WsgiDAV 抓包，脱敏）

```
PROPFIND /                                        depth=1  auth=Basic
GET /Chinese 中文 视频 空格.mp4                    range=bytes=0-0
GET /Chinese 中文 视频 空格.mp4                    range=bytes=0-
```

`GET` 计数从历轮的 **0 变为 2**，且两条都带 `Range`。`bytes=0-0` 是长度探测，`bytes=0-` 是真实读取。
`range_stripped=false` 说明 WsgiDAV 支持 Range，未被夹具剥离。

**3. 客户端播放链路**（持久 startup trace）

```
playback:prepare-start item=http://127.0.0.1:8181/Chinese%20...mp4  server=webdav:127.0.0.1:8181
playback:resolved    url=http://127.0.0.1:8181/Chinese%20...mp4
```

## 观测到但不属于客户端缺陷的一项

WsgiDAV 抓包与 Python 日志里文件名显示为乱码（`Chinese ä¸æ è§é¢ ç©ºæ ¼.mp4`）。
这是 **WsgiDAV/Python 侧把 UTF-8 路径按 Latin-1 解码**的显示问题：同一请求的 PROPFIND href
是正确百分号编码的，客户端据此正确定位并成功播放了该文件。
记录在此以免下一轮误判为客户端编码缺陷。

## 仍未通过

**A-Seek、A-退出释放、A-边界（目录递进/返回上级/空目录/错误态/离线/重启/generation guard）全部 NOT_RUN**，
因此 **Gate A 不得宣告完成**。Gate B1 未开工。

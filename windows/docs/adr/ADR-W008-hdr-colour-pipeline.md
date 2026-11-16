# ADR-W008 — HDR 色彩管线：固定 gpu-next 参数并按片源选择动态元数据策略

- 状态：**已接受**（2026-11-14，第一版；SDR 端显示验证待补）
- 相关：ADR-W002（mpv 嵌入）

## 问题

播放器把所有 HDR 相关的取舍都交给了引擎默认值：

- `tone-mapping`、`target-colorspace-hint`、`hdr-compute-peak` 一个都没有显式设置，全都取决于
  libmpv/libplacebo 的构建默认值。这些默认值在引擎升级时会变，而变化的后果就是画面发灰
  （动态元数据失效）、偏色或过曝。
- 杜比视界（profile 5 的 IPTPQc2、profile 8 的 HDR10 基线 + RPU）虽然由 libplacebo 正常处理，
  但用户既看不到引擎是否识别为 DV，也无从选择"逐帧元数据"与"实测峰值"这两种做法。
- 失败时会静默黑屏：`end-file` 的错误文本是 `loading failed` 这种通用句，真正的理由
  （显卡上下文创建失败、解码器不支持、HTTP 401）只存在于引擎日志里，用户看不到。

## 决策

### 1. 显式固定色彩管线，绝不依赖默认值

`MpvColorPipeline.BuildOptions` 在 `hwdec` 之后、用户自定义 `ExtraOptions` 之前写入：

| 选项 | 值 | 理由 |
| --- | --- | --- |
| `target-colorspace-hint` | `auto` | HDR 桌面直通、SDR 桌面由 libplacebo 色调映射。不引入 UI 开关去猜显示器状态 |
| `tone-mapping` | `auto` | 保持已验证的曲线；DV 重塑发生在曲线之前，改曲线只会连带改变 HDR10 的 SDR 输出 |
| `hdr-compute-peak` | 由用户设置决定 | 三个取值恰好就是本 ADR 的可配置轴 |

三个值与当前引擎（libmpv 0.41.0 / libplacebo 7.360）的默认值一致，所以"固定"不改变今天的
播放结果，只是阻止引擎升级时悄悄改变它。用户显式写的 `ExtraOptions` 排在后面，仍可覆盖。

### 2. 可配置轴就是 `hdr-compute-peak`

| 设置值 | 引擎值 | 语义 |
| --- | --- | --- |
| `auto`（默认） | `auto` | 跟随片源：需要色调映射时才实测画面 |
| `metadata` | `no` | 只信流内元数据（HDR10+ ST 2094-40、DV RPU L1-L11） |
| `compute` | `yes` | 忽略逐帧元数据，逐帧实测峰值 |

设置在 `MediaHubSettings.HdrDynamicMetadata`，经 `PlayerHostService` 解析为
`MpvPlayerOptions.HdrDynamicMetadata`，在下次创建引擎时生效（与 `hwdec` 等高级项一致）。
默认取 `auto`，即改造前的既有行为——这是"最稳的一档"。

### 3. DV 识别与展示

引擎在本构建下不提供 `dovi-profile` 属性，可靠信号是 `video-params/colormatrix == "dolbyvision"`。
`TechnicalInfo.IsDolbyVision` 由此派生，播放页信息面板与引擎启动日志（`dv=`）都会显示。
逐帧 RPU 的 L1 峰值可从 `video-params/max-pq-y`、`avg-pq-y` 读出，用于日志和验证。

### 4. 失败必须给出可读理由

`MpvPlayer` 在初始化后 `mpv_request_log_messages(ctx, "warn")`，事件线程按等级筛出
error/fatal/warn，只保留最后 8 行（`LogTailSnapshot`）；`load`/`playlist-next` 时清空，避免把上一条
目的失败原因安到下一条目上。`MpvPlaybackErrors.Describe` 把引擎错误文本 + 日志尾翻译成中文：

- 视频输出 / 显卡上下文初始化失败 → 提示驱动与 HDR/独显直连；
- 解码器失败 / 不支持 → 提示硬件解码与该片源编码；
- 401/403、404、超时/拒绝连接、证书/TLS → 分别给出登录、地址、网络、证书的原因；
- 其余情况附上引擎原文与最后一条有用日志，不做静默吞掉。

## 验证（2026-11-14，本机 RTX 4070 Laptop，Win11 HDR 开启）

用 ffmpeg 生成受控素材（x265 10bit + dovi_tool 注入 RPU），隐藏窗口嵌入 libmpv 取证，
不置顶、不抢焦点、不注入输入：

- `dv81_dv3.mkv`（HDR10 基线 + DV 8.1 RPU，两段镜头）：引擎自报
  `colormatrix=dolbyvision`、`bt.2020/pq`，`max-pq-y` 0.508（t=1s）/ 0.752（t=4.5s）、
  `avg-pq-y` 0.220 / 0.464 —— 逐帧 RPU 元数据确实被解析并参与映射；`hwdec=d3d11va`、
  `vo=gpu-next/d3d11`、`out=bt.2020/pq`（HDR 直通）。
- 同一基线去掉 RPU 后同刻对比，PSNR ≈ 17.3 dB —— DV 重塑确实作用在画面上，而不是被忽略。
- `dv5_plain.mkv`（profile 5 RPU + 合成 PQ 基线）：引擎识别为 DV 并以 HDR 输出；
  与无 RPU 的同源文件同刻 PSNR ≈ 6.2 dB。
- 三种 `hdr-compute-peak` 取值都在引擎侧读回（`auto`/`no`/`yes`），截图逐像素相同（PSNR ∞）：
  HDR 桌面下是 PQ 直通，不做色调映射，故三者无差异；这也证明固定参数没有改变今天的输出。

## 已知限制

- **profile 5 的色彩准确度无法用合成素材验证**：本地 ffmpeg/x265 不能编码 IPTPQc2，
  合成文件是"PQ 基线 + 合法 profile 5 RPU"，只能证明管线被激活，不能证明颜色正确。
  需要一份真实 IPTPQc2 母版（或 DV 播放器导出帧）才能比对。
- **SDR 色调映射路径不可验证**：本机桌面处于 HDR 模式，引擎日志
  `D3D11: suboptimal colorspace RGB_FULL_G2084_NONE_P2020`，输出恒为 PQ 直通，
  强行设 `target-peak=100` 也不改变 `video-out-params`。要验证需关闭 Windows HDR 或使用 SDR 显示器，
  属于改变用户环境，未执行。
- 未使用显示端测量设备；结论来自引擎自报参数与像素级差分，不是色度计读数。
- `MaxCLL` 等静态元数据来自片源，合成素材的取值只代表测试文件本身。

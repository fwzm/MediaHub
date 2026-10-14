# ADR-W004 — 构建与发行形式

- 状态：**已接受**（2026-10-14）
- 相关：ADR-W001

## 问题

Windows 上有两种主流发行形式（MSIX 与传统 unpackaged），且客户机上是否已装
Windows App Runtime 完全不可控。必须在不要求用户预装运行时的前提下分发。

## 实测依据

| 观察 | 结果 |
| --- | --- |
| 默认（framework-dependent + framework-dependent Windows App SDK）运行 | **失败**：`Required components of the Windows App Runtime are missing. Version 1.8 (MSIX package version >= 8000.994.2142.0)` |
| 设置 `WindowsAppSDKSelfContained=true` + `SelfContained=true` 后 | **成功**：exe 从目录直接启动并渲染窗口 |

本机 Windows App Runtime 未安装，说明"要求用户先装运行时"的方案在真实环境里极易失败。

## 决策

**默认发行形式：unpackaged + 自包含（portable）。** 同时保留 MSIX 作为后续可选项。

```xml
<WindowsPackageType>None</WindowsPackageType>        <!-- unpackaged -->
<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>
<SelfContained>true</SelfContained>                   <!-- 含 .NET 运行时 -->
<RuntimeIdentifier>win-x64</RuntimeIdentifier>
```

### 为什么先做 portable

- **零前置依赖**：不要求 Windows App Runtime，也不要求特定 .NET 版本。
- **可验证**：解压即运行，测试与用户看到的是同一个产物。
- **便于 CI 归档**：`dotnet publish` 输出即交付物，无需签名证书。

### MSIX 何时更合适（后续）

- 需要自动更新、干净卸载、文件类型关联的系统级注册、企业分发时。
- 代价：需要代码签名证书；用户侧需要信任证书；自包含 MSIX 体积同样约 200 MB。
- **文件类型关联**在 unpackaged 模式下并非不可行（写 `HKCU\Software\Classes`），
  但需要显式安装步骤，因此排在 MSIX 之前单独实现（见 ROADMAP-WINDOWS W3）。

## 产物体积

| 组成 | 约 |
| --- | --- |
| .NET 10 自包含运行时 | ~70 MB |
| Windows App SDK 自包含 | ~40 MB |
| `libmpv-2.dll` | ~115 MB |
| 应用自身 | < 1 MB |

`libmpv-2.dll` **不入库**（`.gitignore` 已排除），由 `windows/tools/fetch-libmpv.ps1` 拉取，
脚本记录钉住的 release tag 与资产名，并预留 SHA-256 校验位（当前为 `PENDING`，
首次正式发布前必须回填——见"未完成项"）。

## 未完成项（如实登记）

- `fetch-libmpv.ps1` 的 `$AssetSha256` 仍为 `PENDING`：**尚未固定校验和**。
  在补齐之前，该脚本不能被视为完整供应链控制。
- 未生成 MSIX 包；未做代码签名；未做安装/卸载实测。
- 未在干净机器上验证 portable 包（本机已具备构建期依赖）。

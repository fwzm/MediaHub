# SBOM — what the shipped Windows portable package actually contains

- Date: 2026-10-14 (W3-H). Author: license-dev.
- Scope: the **published artifact**, not the repository. This is an inventory of what leaves the build,
  because that is what a licence obligation attaches to.
- Related: [LICENCE-DECISION.md](LICENCE-DECISION.md) (the GPL analysis and the recommendation).

> **Update 2026-10-24 (W11b).** The inventory below is the **GPL single-flavour** snapshot taken in
> W3-H (522 files / 347,972,263 bytes, engine 120,989,696 bytes). Since W13 this repository can also
> be published with `-p:LibMpvFlavour=lgpl`, which swaps the engine for the 3,327,274-byte LGPL
> candidate **plus 33 runtime DLLs** that the default flavour never ships. The two release trees are
> measured in §7; nothing in §0–§6 was re-measured for the LGPL flavour, so read those tables as
> "GPL flavour, W3-H" and not as "the package".

## 0. How this inventory was produced

```powershell
# publish exactly as CI does (windows-ci.yml lines 114-119)
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o <scratch>/portable
```

Then: (a) enumerate every file and size in the output; (b) parse `MediaHub.deps.json` — the .NET
SDK's own resolved dependency graph, which names each library and its version; (c) read the licence
file/nuspec **inside each NuGet package on this machine**; (d) raw-scan `libmpv-2.dll` for the
component banners it embeds. Nothing below is inferred from a package name alone.

Measured result (this session):

| Metric | Value |
| --- | --- |
| Files | **522** |
| Bytes on disk | **347,972,263** |
| Size | **331.9 MB** |
| `Compress-Archive -CompressionLevel Optimal` of that tree | 139,338,968 bytes = 132.9 MB, SHA-256 `002daf3559f5bdd37d172fe725ce38b6be203a56192a6fcbdf3ff7e75f92618e` |
| Licence/notice files included in the package | **0** (see §4.1) |

Size attribution (measured; grouping by `MediaHub.deps.json` ownership plus file-name patterns for the
Windows App SDK natives, which `deps.json` does not enumerate):

| Component family | Size | Files |
| --- | --- | --- |
| `libmpv-2.dll` | 115.4 MB | 1 |
| Windows App SDK 1.8 native, incl. Windows ML runtime | 92.7 MB | 261 |
| .NET 10 runtime (runtimepack) | 76.7 MB | 187 |
| Windows SDK projections (`Microsoft.Windows.SDK.NET.dll` + `WinRT.Runtime.dll`) | 25.6 MB | 2 |
| Windows App SDK 1.8 managed assemblies | 10.6 MB | 32 |
| MediaHub application assemblies + `.exe` + `.pdb` | 2.5 MB | 16 |
| WebView2 SDK | 0.8 MB | 2 |
| `System.Numerics.Tensors` | 0.4 MB | 1 |
| `System.Security.Cryptography.ProtectedData` | 39 KB | 1 |
| Windows App SDK side assets (`.winmd`, `.pri`, `workloads*.json`, app `input.conf`) | ~1.2 MB | 20 |
| **Total** | **331.9 MB** | **522** |

## 1. Direct components (identified by `MediaHub.deps.json`)

`deps.json` resolves **19 libraries**: 6 project, 10 package, 2 runtimepack, 1 reference.
Everything redistributed by the package is one of these, plus `libmpv-2.dll` which is copied by
`MediaHub.Player.Mpv.csproj` (lines 21–23).

| # | Component | Version | Licence | How obtained | Redistributed? | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | MediaHub (own code): `MediaHub.exe`, `MediaHub.dll`, `MediaHub.Domain`, `.Net`, `.Player`, `.Player.Mpv`, `.Providers` | 1.0.0 + revision `b07b9214…` (ProductVersion `1.0.0+b07b92146a6c2f7d967e1d10901e9821d2b49ee0`) | **UNKNOWN** — the repository contains **no `LICENSE` file** (`git ls-files`), although `README.md` links one and `ADR-W001` asserts MIT | built from source | **yes** (2.5 MB, 16 files) | `MediaHub.deps.json`; `(Get-Item MediaHub.dll).VersionInfo`; §4.3 |
| 2 | Microsoft .NET runtime — `runtimepack.Microsoft.NETCore.App.Runtime.win-x64` | **10.0.13** | **MIT** (`LICENSE.TXT` = "The MIT License (MIT), Copyright (c) .NET Foundation and Contributors"; nuspec `<license type="expression">MIT`) | NuGet runtimepack (self-contained publish) | **yes** (76.7 MB, 187 files incl. `coreclr.dll`, `System.Private.CoreLib.dll`, `msquic.dll`, `createdump.exe`) | licence files in `%USERPROFILE%\.nuget\packages\microsoft.netcore.app.runtime.win-x64\10.0.13\` |
| 3 | Windows SDK projections for .NET — `runtimepack.Microsoft.Windows.SDK.NET.Ref` | **10.0.26100.56** | Microsoft Windows SDK licence (`<licenseUrl>https://aka.ms/WinSDKLicenseURL`), `requireLicenseAcceptance=true`; exact terms **UNKNOWN** (the package ships no licence file) | NuGet targeting pack | **yes** (`Microsoft.Windows.SDK.NET.dll` 25.1 MB + `WinRT.Runtime.dll` 0.5 MB) | nuspec in `microsoft.windows.sdk.net.ref\10.0.26100.56\` |
| 4 | Microsoft Windows App SDK — meta package and its parts: `.WinUI` 1.8.260803003, `.Foundation` 1.8.260803002, `.InteractiveExperiences` 1.8.260708001, `.Widgets` 1.8.251231004, `.AI` 1.8.79, `.ML` 1.8.2223 | meta **1.8.260921001** | **Microsoft Software License Terms** (proprietary; `license.txt`, 12,637/14,033 bytes, "MICROSOFT WINDOWS APP SDK"; the ML package adds section "2. WINDOWS MACHINE LEARNING (ML)"). Bundled OSS attributed in `NOTICE.txt` (e.g. Newtonsoft.Json 13.0.1 MIT) and `ThirdPartyNotices.txt` (333,022 bytes) | NuGet (declared in `windows/Directory.Packages.props` line 7) | **yes** (~103 MB incl. natives) | licence/notice files in each package folder; `MediaHub.deps.json` |
| 5 | Microsoft.ML.OnnxRuntime / ONNX Runtime — `Microsoft.ML.OnnxRuntime.dll` (0.2 MB), `onnxruntime.dll` (20.7 MB), `onnxruntime_providers_shared.dll` (42 KB) | **UNKNOWN** (no version resource read; shipped inside the ML package) | package-level: Microsoft Software License Terms (via `Microsoft.WindowsAppSDK.ML`); upstream ONNX Runtime is MIT — **the two are not the same statement**; UNKNOWN which applies | NuGet `microsoft.windowsappsdk.ml\1.8.2223\runtimes-framework\win-x64\native\` | **yes** (≈21 MB) | file present in that package directory and in the publish output |
| 6 | DirectML — `DirectML.dll` (18.7 MB) | **UNKNOWN** | Microsoft Software License Terms (via `Microsoft.WindowsAppSDK.ML`) | same package, `runtimes-framework\win-x64\native\` | **yes** | same |
| 7 | Microsoft.Web.WebView2 (SDK) — `WebView2Loader.dll`, `Microsoft.Web.WebView2.Core.dll` | **1.0.3179.45** | Microsoft BSD-style licence ("Redistribution and use in source and binary forms…") + `NOTICE.txt` | NuGet (transitive via Windows App SDK) | **yes** (0.8 MB) | `LICENSE.txt` in the package folder; `deps.json` |
| 8 | System.Security.Cryptography.ProtectedData | **10.0.13** | **MIT** (nuspec `<license type="expression">MIT`) | NuGet | **yes** (39 KB) | nuspec; `deps.json` |
| 9 | System.Numerics.Tensors | **9.0.0** | **MIT** (nuspec expression; `LICENSE.TXT` "The MIT License (MIT)") | NuGet (transitive) | **yes** (0.4 MB) | nuspec + `LICENSE.TXT` |
| 10 | **libmpv / mpv** — `libmpv-2.dll` | **v0.41.0-1154-gec38361c5** (banner "Oct 14 2026 00:36:32") | **GPL-3.0-or-later** — see [LICENCE-DECISION.md](LICENCE-DECISION.md) §1.5. The binary's own version resource says "GNU General Public License Version 2 or later"; the builder's `--enable-version3` makes it v3+ | `windows/tools/fetch-libmpv.ps1` → `shinchiro/mpv-winbuild-cmake` release `20261014`, asset `mpv-dev-x86_64-20261014-git-ec38361c56.7z`, archive SHA-256 `325555b4…f939`, DLL SHA-256 `6a48faec…63d9`; both verified before install | **yes** (115.4 MB, 1 file) | `Get-FileHash` = the pin; `VersionInfo`; feature-list scan |
| 11 | FFmpeg (libav*), statically linked inside `libmpv-2.dll` | **N-127521-g2ec8998c3** | GPL-3.0-or-later (built `--enable-gpl --enable-version3`) | part of the same asset | **yes**, inside `libmpv-2.dll` | banner at DLL offset 98590936 |
| 12 | `msquic.dll` | ships with .NET 10 runtime | MIT (part of the MIT-licensed runtimepack); upstream QUIC library is MIT | NuGet runtimepack `runtimes/win-x64/native/msquic.dll` | **yes** | file path inside the runtimepack |

**Not redistributed (correctly):** `xunit` 2.9.3, `xunit.runner.visualstudio` 3.0.2,
`Microsoft.NET.Test.Sdk` 17.13.0 (test-only; absent from the publish output and from `deps.json`).
`Microsoft.Extensions.DependencyInjection` and `Microsoft.Extensions.Http` are pinned in
`windows/Directory.Packages.props` lines 8–9 but are **referenced by no project** — they appear in
neither `deps.json` nor the NuGet cache. Dead pins; they carry no obligation but should be removed.

## 2. Components statically linked inside `libmpv-2.dll`

The engine is one 115 MB DLL: every dependency is linked in, so every one of them is redistributed
with it. Two independent evidence streams:

1. **What the binary claims.** The feature list (§1 of [LICENCE-DECISION.md](LICENCE-DECISION.md))
   and the codec descriptor strings in `libavcodec`.
2. **What the builder's recipe links.** `shinchiro/mpv-winbuild-cmake` `packages/ffmpeg.cmake` and
   `packages/mpv.cmake` `DEPENDS` lists (the embedded meson line in our DLL matches that recipe).

Banner scan performed on the shipped DLL (offset of first match, where found):

| Component | Found in DLL | Licence (upstream file read) | GPL? |
| --- | --- | --- | --- |
| FFmpeg `libavcodec/libavformat/libavutil/libswresample/libswscale/libavfilter/libavdevice` | yes — "FFmpeg version: %s … N-127521-g2ec8998c3" @98590936 | LGPL-2.1+ normally; **GPLv3+ here** (built with `--enable-gpl --enable-version3`) | **yes** |
| x264 | yes — "libx264 H.264 / AVC / MPEG-4 AVC…" @101758592; "x264 [%s]:" @98110785 | GPL-2.0 (`mirror/x264` `COPYING`) | **yes** |
| x265 | yes — "libx265 H.265 / HEVC" @101768976 | GPL-2.0 (`videolan/x265` `COPYING`) | **yes** |
| libdvdnav | yes — "libdvdnav" @99115230 | GPL-2.0 (`mirror/libdvdnav` `COPYING`) | **yes** |
| libdvdread | yes — "libdvdread" @99113459 | GPL-2.0 (`mirror/libdvdread` `COPYING`) | **yes** |
| rubberband | yes — meson line `-Drubberband=enabled` @98793590 | GPL-2.0 (`breakfastquay/rubberband` `COPYING`) | **yes** |
| davs2 | yes — "libdavs2 AVS2-P2/IEEE1857.4" @103382587 | GPL-2.0 (listed as GPL-only in FFmpeg `LICENSE.md`) | **yes** |
| libzvbi (teletext) | yes — "libzvbi_teletextdec" @103388195 | **UNKNOWN** (suspected GPL-2.0+) | **UNKNOWN** |
| libplacebo | yes — `-Dgl=enabled`, property `libplacebo-version` @98783723 | LGPL-2.1 (`haasn/libplacebo` `LICENSE`) | no |
| libass | yes — "libass-version" property @98783723 | ISC (`libass/libass` `COPYING`) | no |
| dav1d | yes — "libdav1d. AV1 decoder by VideoLAN" @103381475 | BSD-2 (`videolan/dav1d` `COPYING`) | no |
| libcurl | yes — `-Dlibcurl=enabled`; TLS error strings @109072196 | MIT-style (`curl/curl` `COPYING`) | no |
| OpenSSL | yes | Apache-2.0 (not read here) | no |
| libarchive | yes — `-Dlibarchive=enabled` | BSD-2 (`libarchive/libarchive` `COPYING`) | no |
| libbluray | yes — `-Dlibbluray=enabled` | **UNKNOWN** (upstream `COPYING` fetch failed) | UNKNOWN |
| libssh | yes | LGPL-2.1 (`libssh/libssh-mirror` `COPYING`) | no |
| libsrt | yes | MPL-2.0 (`Haivision/srt` `LICENSE`) | no |
| uchardet | yes — "libuchardet detected charset as %s" @98691373 | MPL-1.1 (`BYVoid/uchardet` `COPYING`) | no |
| VapourSynth bridge | yes — "VapourSynth bridge" @99608444 | LGPL-2.1 (`vapoursynth/vapoursynth` `COPYING.LESSER`) | no |
| subrandr | yes — `-Dsubrandr=enabled`; property `subrandr-version` | **UNKNOWN** (fetch failed) | UNKNOWN |
| zimg | yes — `-Dzimg`/`zimg-st428` in features | WTFPL (`sekrit-twc/zimg` `COPYING`) | no |
| libjpeg-turbo | yes — "Copyright (C) 1991-2026 The libjpeg-turbo Project" @101448034 | BSD-3/IJG/zlib (not read here) | no |
| libpng | yes | libpng licence (not read here) | no |
| lcms2, harfbuzz, fontconfig, FreeType, libiconv, bzip2, zlib, LuaJIT, libmodplug, libopenmpt, libjxl, aribcaption, libmysofa, OpenAL, libvpx, libwebp, libaom, SVT-AV1, speex, opus, vorbis, LAME, bs2b, shaderc, spirv-cross, Vulkan loader, ANGLE | banners/feature strings found for most (`libmodplug` public domain, LuaJIT MIT-style, libsixel MIT, libjxl BSD-3 verified; remainder **not verified here**) | mixed; **not audited** | mostly no, **unverified** |

**Consequence:** the GPL obligation is not limited to x264/x265. Any LGPL re-build must remove or
replace every row marked GPL and finish the audit of the UNKNOWN rows — see
[LICENCE-DECISION.md](LICENCE-DECISION.md) §2.3.

## 3. What the package does *not* contain (verified)

| Expected | Status |
| --- | --- |
| `libav*.dll` (FFmpeg as shared libraries) | **absent** — FFmpeg is static inside `libmpv-2.dll`; this is why the LGPL linkage question in LICENCE-DECISION §2.3(e) exists |
| Any `LICENSE`/`NOTICE`/`COPYING` file | **absent — 0 files of 522** (§4.1) |
| Installer, MSIX, `Package.appxmanifest` | **absent** — `windows/**/*.appxmanifest` matches nothing |
| Code signature | **absent** — `Get-AuthenticodeSignature` returns `NotSigned` for `MediaHub.exe` and `libmpv-2.dll` |
| Version metadata for the app | present but default: `FileVersion 1.0.0.0` (no `<Version>` property anywhere in `windows/`); only `ProductVersion` carries the revision `1.0.0+b07b9214…` |
| Debug symbols | **present and shipped**: 6 `.pdb` files (0.2 MB) — see §4.4 |

## 4. Findings

### 4.1 No licence or notice text ships with the package (compliance gap, independent of the GPL)

Zero of the 522 files match `LICEN|NOTICE|COPYING|EULA|THIRD`. That is already non-compliant for
components whose terms require the notice to accompany the binary — MIT (runtimepack, ProtectedData,
System.Numerics.Tensors, Newtonsoft.Json bundled in the Windows App SDK), the Microsoft Software
License Terms for the Windows App SDK, and the WebView2 licence — and it makes any GPL compliance
impossible in principle: the licence texts are not there.

**Required:** a `THIRD-PARTY-NOTICES` (or `licenses/`) directory inside the package containing the
full text of every licence in §1–§2, plus the GPLv3 text if OPTION 2 is ever taken.
`windows/THIRD-PARTY-NOTICES.md` exists in the repository but is (a) not copied into the publish
output and (b) for the engine, states the wrong GPL version (see LICENCE-DECISION §1.5).

### 4.2 ~40 MB of the package is an ML runtime the app never calls

`Microsoft.WindowsAppSDK` is referenced as a meta-package
(`MediaHub.App.csproj` line 25), which pulls `Microsoft.WindowsAppSDK.AI` and `.ML`, and those pull
`onnxruntime.dll` (20.7 MB), `DirectML.dll` (18.7 MB) and `Microsoft.ML.OnnxRuntime.dll`.
MediaHub has no inference code path. Evidence: the app's source contains no reference to
`Microsoft.Windows.AI` / `Microsoft.ML` (grep over `windows/src`). Referencing the specific
Windows App SDK components (`.WinUI`, `.Foundation`) instead of the meta-package is the candidate
fix; it must be validated by a publish + launch test, and it changes the SBOM, so it belongs to the
owner of `windows/src`.

### 4.3 The product's own licence is undocumented

No `LICENSE` file exists anywhere in the repository (`git ls-files`, `git log -- LICENSE`).
This is an input to LICENCE-DECISION §3.3 and must be fixed before any distribution.

### 4.4 Debug symbols are published

`MediaHub*.pdb` (6 files, 0.2 MB) are in the publish output. Not a licence problem; a release-hygiene
item (and it is what makes the two in-repo publishes differ — see REPRODUCIBLE-BUILD.md §3).

### 4.5 Localisation payload without localisation

`System.Globalization.Invariant=true` (`windows/Directory.Build.props` line 6), yet the package ships
83 locale directories with 172 `.mui` files (3.4 MB) from the Windows App SDK / .NET resources. Not a
licence issue; a size issue worth measuring before the next release.

## 5. UNKNOWN — not established

| # | Unknown | What would resolve it |
| --- | --- | --- |
| U1 | Licences not verified here: `libzvbi`, `libbluray`, `subrandr`, `uavs3d`, `aom`, `SVT-AV1`, `libopenmpt`, OpenSSL, libjpeg-turbo, libpng and the remainder of §2's last row | read each upstream `COPYING`/`LICENSE` (several fetches failed here: `mujs`, `libbluray`, `subrandr`, `zvbi`, `aom`, `SVT-AV1`) |
| U2 | Exact version of ONNX Runtime / DirectML | read their version resources (not done) |
| U3 | Which licence governs the redistributed `Microsoft.ML.OnnxRuntime.dll` — the Microsoft EULA that ships with the package, or upstream MIT | Microsoft's attribution files (`ThirdPartyNotices.txt`, 333 KB — present, not fully read) |
| U4 | Whether `Microsoft.Windows.SDK.NET.Ref` may be redistributed under `https://aka.ms/WinSDKLicenseURL` | read that licence (not fetched) |
| U5 | Whether the shipped `aom`/`SVT-AV1`/`libvpx` patent-licence terms add redistribution conditions | upstream patent grant files |
| U6 | MediaHub's own licence | a human decision |

## 6. Reproducing this inventory

```powershell
$out = '<scratch>/portable'
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o $out
(Get-ChildItem -Recurse -File $out | Measure-Object Length -Sum)   # 522 files / 347972263 bytes
Get-Content "$out/MediaHub.deps.json" | Select-String 'Microsoft.WindowsAppSDK|runtimepack|WebView2'
Get-ChildItem -Recurse -File $out | Where-Object { $_.Name -match 'LICEN|NOTICE|COPYING' }  # -> nothing
Get-AuthenticodeSignature "$out/MediaHub.exe"                       # -> NotSigned
```

## 7. Two flavours after W13 (2026-10-24, W11b)

### 7.1 Measured `dotnet publish` trees

Both commands are the CI shape (`dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o <dir>`),
measured on this machine; evidence file `windows/engine/lgpl/dist/flavour-builds.txt`.

| | GPL flavour (default) | LGPL flavour (`-p:LibMpvFlavour=lgpl`) |
| --- | --- | --- |
| Files | 526 | 559 (+33) |
| Bytes on disk | 348,275,148 (332.14 MiB) | 289,439,302 (276.03 MiB) |
| Engine `libmpv-2.dll` | 120,989,696 B, SHA-256 `6a48faec…63d9` | 3,327,274 B, SHA-256 `20035902…3c0a` |
| Engine directory | `windows/native/libmpv/` | `windows/engine/lgpl/dist/` |
| Sibling DLLs | none | 33 (see §7.2) |

The whole size difference is the engine: 120,989,696 − 3,327,274 = 117,662,422 B smaller, plus
58,826,576 B of sibling DLLs, net 289,439,302 B. File-name diff is exactly the 33 dependencies —
no file appears only in the GPL tree, and the only same-name file with a different size is
`libmpv-2.dll` itself.

### 7.2 The 33 DLLs the LGPL flavour adds

All 33 come from the same two build roots the engine probe uses (`D:\mh-servers\lgpl-build\prefix\bin` and
`C:\msys64\ucrt64\bin`), collected by `windows/tools/collect-lgpl-runtime.ps1` into
`windows/engine/lgpl/dist/` and recorded (name, size, sha256, source) in
`runtime-dependencies-collected.txt`. Row-level licence table:
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) §3.3; per-file evidence line numbers come from
`windows/engine/lgpl/component-licences.txt`.

| Licence family | Files | Bytes | Examples |
| --- | --- | --- | --- |
| LGPL-2.1-or-later / LGPL2.1 (incl. the LGPL FFmpeg build) | 11 | 33,273,735 | `avcodec-62`, `avfilter-11`, `libplacebo-360`, `glib-2.0-0` |
| GPL family (GPL-3.0-or-later / GPL-2.0-or-later / MIT AND GPL) | 5 | 4,949,153 | `libiconv-2`, `libstdc++-6`, `lcms2`, `libfreetype-6` |
| Apache-2.0 | 3 | 14,815,073 | `libshaderc_shared`, `libspirv-cross-c-shared`, `vulkan-1` |
| MIT / ISC / Zlib | 7 | 3,777,412 | `libharfbuzz-0`, `libdovi`, `libbrotli*`, `libass-9`, `zlib1`, `libunibreak-7` |
| custom upstream text | 2 | 760,610 | `libpng16-16`, `fontconfig` |
| **未记录** (no declaration line in `component-licences.txt`) | 5 | 1,250,593 | `libwinpthread-1`, `libpcre2-8-0`, `libintl-8`, `libexpat-1`, `libbz2-1` |
| **Total** | **33** | **58,826,576** | |

### 7.3 What that means for compliance

- The default flavour is unchanged: it ships one GPL-3.0-or-later engine and none of these DLLs.
- Switching to the LGPL flavour **does not** mean "no GPL obligations": `libiconv`, `lcms2`,
  `libfreetype`, `libgcc_s_seh` and `libstdc++` carry GPL-family terms (see
  `windows/docs/licensing/W5-LGPL-BUILD.md:222-252` for the outstanding obligations).
- Five DLLs have no licence declaration in the packager's own list; they are printed as 未记录 in the
  notices rather than guessed. `windows/tools/check-licence-surface.ps1 -FailOnLicenceGaps` turns that gap
  into a hard failure, which is the gate to use before publishing the LGPL flavour.
- `RELEASE BLOCKER` in [LICENCE-DECISION.md](LICENCE-DECISION.md) and in
  [windows/THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) is **not** cleared by having a second flavour.

### 7.4 Refreshing this section

```powershell
pwsh -NoProfile -File windows/tools/collect-lgpl-runtime.ps1      # refresh the 33-DLL manifest from the build roots
dotnet restore windows/MediaHub.Windows.sln                       # keep project.assets.json current
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir windows/artifacts/license-report -Sbom
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath windows/artifacts/license-report/license-report.json
```

Then re-measure the two publish trees (the commands in §0 and `windows/docs/LGPL-ENGINE-SWITCH.md` §10.1)
and update the table above; the machine-readable copy lives in
`windows/engine/lgpl/dist/flavour-builds.txt`. How the scripts work:
[LICENSE-SURFACE-CHECK.md](LICENSE-SURFACE-CHECK.md).

## 8. Keeping this inventory honest (W32 + W33, 2026-10-30)

This document is hand-maintained, but the machine-readable copy (`windows/docs/sbom-windows.cdx.json`) is
generated and **can go stale without anyone noticing**: bump or add a NuGet package, regenerate only the
report, and the SBOM keeps describing the old set while every existing gate stays green - those gates
check the document shape, not whether it still describes the graph that was resolved.

Four checks now compare the committed SBOM against a freshly resolved report
(`windows/tools/check-licence-surface.ps1`, see [LICENSE-SURFACE-CHECK.md](LICENSE-SURFACE-CHECK.md) §3.1):

| Check | Compares | Fails when |
| --- | --- | --- |
| `sbom-inventory` | report `packages[].purl` vs SBOM `components[].purl` (`pkg:nuget/` only) | a package was added, removed, or upgraded and the SBOM was not regenerated |
| `sbom-inventory-licences` | report `license` vs SBOM `licenses[0].expression` | a licence expression changed for a package that is still recorded |
| `sbom-source-revision` | `mediahub:source-revision` (`<branch>@<sha>`) vs `git merge-base --is-ancestor` | the recorded commit is not on the history of this branch; in a shallow clone, where the object was never fetched, this is a WARN with a `fetch-depth: 0` hint instead |
| `sdk-pin-matches-report` | report `tooling.dotnet` vs `sdk.version` in the repository `global.json` | the report was produced by a different SDK than the pin - SDK feature bands prune framework-provided packages differently, so the resolved graph would differ |

Measured 2026-10-30 (report from `license-report.ps1 -OutDir <dir> -SkipDocs`):
`checks=24 failed=0 warnings=1 skipped=0`, with `29 resolved package(s) recorded one for one in the SBOM
(29 pkg:nuget components)` and `every recorded licence expression still matches the resolved graph
(29 rows)`. The committed SBOM was **already fresh** (its recorded revision `feature/windows-client@1c30471`
is an ancestor of HEAD `b586b001bb8f`, and the resolving report reproduces the same 29 purls and licence
expressions), so nothing had to be regenerated: what W32 adds is that a future drift fails CI instead of
shipping silently. Six negative controls (temporary SBOM copies passed through `-SbomPath`, repository
files untouched) each produced **exactly one** failure; deleting the report leaves the three checks at
`SKIP` and the script still exits 0.

W33 + W34 (2026-10-30) closed the reproducibility hole around this. The first CI run of the checks above went
red for a reason that was not a stale SBOM: CI resolved 28 packages against the 29 recorded here, the
difference being the framework-provided `System.Reflection.Metadata@1.6.0` (transitive from
`Microsoft.TestPlatform.ObjectModel@17.13.0`). Whether that package appears depended on **package-prune
data**: the CI image ships it and prunes framework-provided packages, while the machine that generated this
SBOM has no `packs/` directory, resolves framework references through NuGet and therefore keeps them -
pinning the same SDK 10.0.113 on both sides did not change that. The repository now pins the SDK in
`global.json` (`10.0.113`, `rollForward: disable`), `windows-ci.yml` installs that exact version and checks
out with `fetch-depth: 0`, and `windows/Directory.Build.props` sets `RestoreEnablePackagePruning=false`, so
the resolved graph is the same everywhere. The guard grew `sdk-pin-matches-report`, which fails when the
report and the pin disagree, and its revision check warns in a shallow clone instead of failing. Measured
2026-10-30 with the pinned toolchain: `checks=25 failed=0 warnings=1 skipped=0`.

Two details worth knowing before you use `-SbomPath`: it is joined onto the repository root, so pass a
repository-relative path (an absolute one is concatenated and silently not found), and running locally
needs the two probe projects restored one by one - the solution-level `dotnet restore` does not write
`MediaHub.EngineProbe` / `MediaHub.ResumeProbe` assets, and without them the report is INCOMPLETE
(`projects=11` instead of 13):

```powershell
dotnet restore src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj
dotnet restore src/MediaHub.ResumeProbe/MediaHub.ResumeProbe.csproj
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir windows/artifacts/license-report -SkipDocs
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath windows/artifacts/license-report/license-report.json
```

If `sbom-inventory` fails, regenerate the SBOM (`license-report.ps1 -Sbom`) and commit it together with
the dependency change that moved the graph.

# LICENCE-DECISION — libmpv licence blocker and distribution options

- Status: **OPEN RELEASE BLOCKER — not resolved. Do not ship the portable package.**
- Date: 2026-10-14 (W3-H). Author: license-dev.
- Scope: engineering record for a human decision. **Not legal advice.** This file does not change
  any licence, and it does not authorise a change to the repository licence.
- Related: `windows/THIRD-PARTY-NOTICES.md`, `windows/docs/ROADMAP-WINDOWS.md` (W5),
  `windows/docs/adr/ADR-W001-technology-selection.md`, `windows/docs/adr/ADR-W004-packaging-and-distribution.md`.

**摘要（中文）**：当前 `libmpv-2.dll` 是 GPL 构建，且按构建参数应为 **GPL-3.0-or-later**。
（2026-10-31 更正：仓库文档曾写 GPL-2.0-or-later，W36/W38 已把两份 notices 改成
GPL-3.0-or-later；§0 的 status block 与核对器 `licence-decision-claims` 现在钉住这个标签。）
不解决该义务就**不得对外分发**便携包。
本记录给出两条可执行路径并推荐 **OPTION 1（自建 LGPL 配置的 libmpv）**；
但 `-Dgpl=false` **本身不构成 LGPL 授权**（mpv 自己的 Copyright 文件即如此声明），
还必须处理 FFmpeg 与全部静态链接库，并解决 LGPL 对"可重新链接"的要求。
OPTION 2 的代价不是加一个 NOTICE 文件，而是改变已分发整体的许可证 —— **不在此工作流内决定**。

---

## 0. Summary

| Question | Answer | Evidence |
| --- | --- | --- |
| Is the shipped engine GPL? | **Yes.** | §1 |
| Which GPL? | **GPL-3.0-or-later**. The repository documents said GPL-2.0-or-later until 2026-10-31; both notice files now say GPL-3.0-or-later (W36/W38), and `licence-decision-claims` keeps them honest. | §1.5 |
| Does `-Dgpl=false` fix it? | **No, not by itself.** mpv's own `Copyright` file says so in as many words. | §2.2 |
| Can we fix it in this environment? | **No.** LGPL build not attempted and not attemptable here (no meson/ninja; task forbids installing). | §2.4 |
| Recommendation | **OPTION 1**, with the FFmpeg-linkage question escalated for legal review. Until it lands: no release. | §4 |
| Change the repository licence? | **No.** Out of scope for this workstream; requires human legal review. | §3.3 |

<!-- BEGIN licence-decision-status (checked by check-licence-surface.ps1) -->
status: open-release-blocker
shipped-engine-licence: GPL-3.0-or-later
repo-label-mismatch: resolved-2026-10-31
<!-- END licence-decision-status -->

---

## 1. What is actually shipped, and under which licence

### 1.1 The binary in the tree

| Fact | Value | How obtained |
| --- | --- | --- |
| Path | `windows/native/libmpv/libmpv-2.dll` | — |
| Size | 120,989,696 bytes | `Get-Item` |
| SHA-256 | `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` | `Get-FileHash -Algorithm SHA256` |
| Matches the pin in `windows/tools/fetch-libmpv.ps1` line 36? | **Yes, byte-identical** | comparison of the two hashes |

So every statement below is about the exact binary the build fetches and the package ships.

### 1.2 Statements the binary makes about itself

`(Get-Item libmpv-2.dll).VersionInfo` (this session):

```
FileVersion    : v0.41.0-1154-gec38361c5
ProductVersion : v0.41.0-1154-gec38361c5
CompanyName    : mpv
LegalCopyright : Copyright © 2000-2026 mpv/MPlayer/mplayer2 projects
Comments       : mpv is distributed under the terms of the GNU General Public License Version 2 or later.
```

The same file contains the LGPL notice text as well (offset 99195122: *"under the terms of the GNU
Lesser General Public License … version 2.1 of the License, or (at your option) any later version"*) —
that is mpv's **source** licence, and it is exactly the trap: mpv's own code is LGPL2.1+, the
**build** is GPL.

mpv's run-time feature list baked into this DLL (offset 98929076, raw byte scan):

```
List of enabled features: amf build-date cplugins cuda-hwaccel cuda-interop d3d-hwaccel d3d11
d3d9-hwaccel debug direct3d dos-paths dvda dvdnav dxgi-debug-d3d11 egl-angle egl-angle-win32 ffmpeg
ffnvcodec gl gl-dxinterop gl-dxinterop-d3d9 gl-win32 glob glob-win32 gpl iconv javascript jpeg lcms2
libarchive libass libavdevice libbluray libcurl libplacebo luajit openal rubberband rubberband-3
sdl2-gamepad shaderc sixel spirv-cross subrandr uchardet vaapi vaapi-win32 vapoursynth vector
vk-khr-display vulkan wasapi win32 win32-desktop win32-executable win32-smtc win32-threads zimg
zimg-st428 zlib
```

`gpl` is present. That token is emitted by mpv itself from the `-Dgpl` build option — see §1.3.

### 1.3 The build command is inside the binary

The DLL embeds its own meson command line (offset ~98793200):

```
-Dprefix=/__w/mpv-winbuild-cmake/mpv-winbuild-cmake/build_x86_64/x86_64-w64-mingw32
-Dlibdir=…/x86_64-w64-mingw32/lib -Ddefault_library=shared -Dprefer_static=true -Ddebug=true
-Db_ndebug=true -Doptimization=3 -Db_lto=true -Db_lto_mode=thin -Dlibmpv=true -Dpdf-build=enabled
-Dlua=enabled -Djavascript=enabled -Dsdl2-gamepad=enabled -Dlibarchive=enabled -Dlibbluray=enabled
-Ddvdnav=enabled -Duchardet=enabled -Drubberband=enabled -Dlcms2=enabled -Dopenal=enabled
-Dspirv-cross=enabled -Dvulkan=enabled -Dvapoursynth=enabled -Dsubrandr=enabled -Dsixel=enabled
-Dgl=enabled -Degl-angle=enabled -Dlibcurl=enabled -Dc_args=-Wno-error=int-conversion
--cross-file=/__w/mpv-winbuild-cmake/mpv-winbuild-cmake/build_x86_64/meson_cross.txt
```

Two facts follow directly:

1. **`-Dgpl` is not passed.** mpv's `meson.options` declares
   `option('gpl', type: 'boolean', value: true, description: 'GPL (version 2 or later) build')`,
   so the meson **default `true`** applied. (Upstream file:
   `https://github.com/mpv-player/mpv/blob/master/meson.options`, read via the GitHub contents API.)
2. The build ran in the `shinchiro/mpv-winbuild-cmake` GitHub Actions workspace
   (`/__w/mpv-winbuild-cmake/mpv-winbuild-cmake/`), which is the pinned source of our asset
   (`windows/tools/fetch-libmpv.ps1` line 20).

### 1.4 The builder's recipe confirms it — and shows the FFmpeg flags

`shinchiro/mpv-winbuild-cmake` at `master`, file `packages/ffmpeg.cmake` (read via the GitHub
contents API) configures FFmpeg with, among others:

```
--enable-gpl  --enable-version3  --enable-avisynth  --enable-libx264  --enable-libx265
--enable-libdvdnav  --enable-libdvdread  --enable-librubberband  --enable-libdavs2
--pkg-config-flags=--static  --enable-cross-compile  --target-os=mingw32
DEPENDS … x264  x265-8+10bit  libdvdnav  libdvdread  rubberband  avisynth-headers …
```

`packages/mpv.cmake` runs `meson setup` with `-Dlibmpv=true` and **no `-Dgpl`** — i.e. GPL mode.
The command line embedded in our DLL (§1.3) matches that recipe flag for flag, which is the
cross-check that our asset really is that recipe's output.

**What is *not* recoverable from the binary:** FFmpeg's own configure line. A raw scan of the DLL for
`--enable-gpl`, `--enable-libx264`, `--enable-libx265`, `--target-os=mingw32` and
`--enable-cross-compile` returns **not found**. The FFmpeg flags are therefore evidenced by
(a) the builder's recipe and (b) the x264/x265 codec descriptors present in the binary (§1.5) —
not by a self-report. A binary that self-reports its FFmpeg configuration would be a stronger control;
this one does not.

FFmpeg version inside the DLL (offset 98590936): `N-127521-g2ec8998c3`, matching
`fetch-libmpv.ps1` line 22.

x264/x265 are statically linked into `libavcodec` inside this one DLL:

```
offset 101758592: libx264 \0 libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10
offset 101768976: libx265 \0 libx265 H.265 / HEVC
offset  98110785: x264 [%s]:
```

### 1.5 Conclusion: the shipped engine is GPL-3.0-or-later

- FFmpeg: `--enable-gpl` means, per FFmpeg's own configure help,
  `--enable-gpl allow use of GPL code, the resulting libs and binaries will be under GPL [no]`,
  and `--enable-version3 upgrade (L)GPL to version 3 [no]` (FFmpeg `configure`, help text).
  FFmpeg's `LICENSE.md` states the same: with `--enable-version3` the applicable text is
  `COPYING.GPLv3` when GPL parts are enabled
  (`https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md`).
  FFmpeg's `legal.html` adds: *"FFmpeg incorporates several optional parts and optimizations that are
  covered by the GNU General Public License (GPL) version 2 or later. If those parts get used the GPL
  applies to all of FFmpeg."* (`https://ffmpeg.org/legal.html`.)
- mpv: `Copyright` → *"mpv as a whole is licensed under the GNU General Public License GPL version 2
  or later … by default"* (`https://github.com/mpv-player/mpv/blob/master/Copyright`).
- The combination can only be distributed under **GPL-3.0-or-later**: the FFmpeg parts are GPLv3+
  because of `--enable-version3`, and mpv's GPLv2+ permits taking the work under GPLv3. A
  GPLv2-only reading is not available for this binary.

**Correction applied outside this directory (reported to the Lead on 2026-10-14; done on 2026-10-31):**
`windows/THIRD-PARTY-NOTICES.md` lines 10–11 used to state `GPL-2.0-or-later`. The shipped binary's
accurate SPDX expression is `GPL-3.0-or-later`; W36/W38 rewrote both notice files, and the
`licence-decision-claims` check now fails if either one stops saying GPL-3.0-or-later for libmpv.
This matters: GPLv3 adds the Installation Information condition (GPLv3 §6) and the patent grant (§11).

### 1.6 GPL-family components statically linked into `libmpv-2.dll`

The blocker is **not only x264/x265**. Every statically linked component inherits into the combined
work. Verified from each upstream repository's own licence file:

| Component | Licence (upstream file read) | Used for |
| --- | --- | --- |
| x264 (`mirror/x264` `COPYING`) | GPL-2.0 | H.264 encoder |
| x265 (`videolan/x265` `COPYING`) | GPL-2.0 | HEVC encoder |
| rubberband (`breakfastquay/rubberband` `COPYING`) | GPL-2.0 | `scaletempo` variants |
| libdvdnav (`mirror/libdvdnav` `COPYING`) | GPL-2.0 | DVD navigation |
| libdvdread (`mirror/libdvdread` `COPYING`) | GPL-2.0 | DVD reading |
| libdavs2 | GPL-2.0 (listed as GPL in FFmpeg `LICENSE.md`'s compatible-libraries list) | AVS2 decoder |
| libzvbi | **UNKNOWN** — upstream `COPYING` not retrievable with the tooling used here; upstream project is widely described as GPL-2.0-or-later | DVB teletext |

Non-GPL (spot-verified, same method): libplacebo LGPL-2.1, libass ISC, dav1d BSD-2, curl MIT-style,
libarchive BSD-2, libssh LGPL-2.1, SRTP/SRT MPL-2.0, uchardet MPL-1.1, VapourSynth LGPL-2.1,
libjxl BSD-3, libmodplug public domain, libsixel MIT, zimg WTFPL, LuaJIT MIT-style, FreeType
FTL-or-GPLv2. libbluray and subrandr were **not** verified (their `COPYING`/`LICENSE` paths
returned HTTP 404 with the method used).
A complete per-component audit is required work — the SBOM in this directory is its starting point,
not its replacement.

### 1.7 The repository's own licence is undocumented

`git ls-files | Select-String 'LICENSE|COPYING|NOTICE'` returns exactly one file:
`windows/THIRD-PARTY-NOTICES.md`. There is **no `LICENSE` file** in the repository, and
`git log -- LICENSE` produces no output, i.e. no such path ever existed in this clone's history.
Meanwhile `README.md` line 8 links the licence badge to `LICENSE` (a dead link), and
`ADR-W001` line 40 asserts *"与仓库现有 MIT 许可一致"*.

Consequence: for OPTION 2 (§3) there is currently no in-tree statement of what licence MediaHub's own
code is offered under, so "comply with the GPL for the combined work" cannot even be executed until a
human states the project's own licensing intent. This is a second, independent documentation defect.

---

## 2. OPTION 1 — build an LGPL-compatible libmpv

### 2.1 What the switch actually does

mpv's `meson.options` defines `-Dgpl`; mpv's `meson.build` gates these components on it (line
numbers from `https://github.com/mpv-player/mpv/blob/master/meson.build`, read via the API):

| mpv component | meson.build line | We use it? |
| --- | --- | --- |
| `cdda` (audio CD) | 642–645 | no |
| `dvbin` (DVB input) | 654–657 | no |
| `dvda` (DVD-Audio) | 665–668 | no |
| `dvdnav` (DVD navigation) | 676–679 | no |
| `jack` (audio output) | 885–888 | no (WASAPI) |
| `oss-audio` | 923–926 | no (WASAPI) |
| `caca` (video output) | 976–979 | no |
| `direct3d` — **d3d9** video output, requires `gpl and win32-desktop` | 987–990 | no (`vo=gpu-next`) |
| `x11` | 1123–1126 | no (Windows) |

mpv's `Copyright` file states the same convergence from the other direction: *"mpv can be built as
LGPLv2.1+ without using any GPL only files … LGPL mode disables the following features, some of them
quite central: Linux X11 video output, BSD audio output via OSS, NVIDIA/Linux hardware decoding
(vdpau), minor features: jack, DVD, CDDA, DVB, CACA, legacy direct3d VO. … The intended use for LGPL
mode is with libmpv."*

### 2.2 Why `-Dgpl=false` is **not** sufficient — mpv says so itself

Verbatim from mpv's `Copyright` (`https://github.com/mpv-player/mpv/blob/master/Copyright`):

> However, do note that the build system is provided "as is" and using the `-Dgpl=false` configure
> switch does not in itself create a LGPLv2.1+ license grant.

and, closing the same section:

> None of the cases listed above affect the final binary if it's built as LGPL. **Linked libraries
> still can affect the final license (for example if FFmpeg was built as GPL).**

That last sentence is precisely our case: our FFmpeg is built `--enable-gpl --enable-version3`.
Turning off mpv's own GPL modules without rebuilding FFmpeg and the other GPL libraries would produce
a binary that still must be distributed under the GPL.

### 2.3 What an LGPL-compatible build concretely requires

This is the checklist; each item is verifiable and must be evidenced before anyone claims LGPL.

**(a) mpv** — `meson setup … -Dgpl=false` (plus the rest of the current recipe).

**(b) FFmpeg** — reconfigure **without** `--enable-gpl`, `--enable-version3`, `--enable-nonfree`,
`--enable-avisynth`, `--enable-libx264`, `--enable-libx265`, `--enable-librubberband`,
`--enable-libdvdnav`, `--enable-libdvdread`, `--enable-libdavs2` (all present in
`packages/ffmpeg.cmake`, §1.4). FFmpeg's `LICENSE.md` lists the libraries that force GPL:
*avisynth, frei0r, libcdio, libdavs2, librubberband, libvidstab, libx264, libx265, libxavs,
libxavs2, libxvid*. FFmpeg's `legal.html` compliance checklist item 1 is exactly
*"Compile FFmpeg **without** `--enable-gpl` and **without** `--enable-nonfree`."*

**(c) Every other statically linked library** — drop or replace the GPL rows in §1.6
(rubberband, libdvdnav, libdvdread, x264/x265, libdavs2), and finish the audit for the rows still
marked UNKNOWN.

**(d) Does the app lose anything it uses?** No, on current evidence:

| Feature MediaHub relies on | GPL-gated? | Evidence |
| --- | --- | --- |
| `vo=gpu-next` (libplacebo renderer) | no — `gpu-next` uses libplacebo (LGPL-2.1); not in the gated list | mpv `meson.build` gate list §2.1; `MpvPlayerOptions.VideoOutput` |
| `gpu-context=d3d11` | no — `d3d11` is `win32-desktop`-gated, not GPL-gated; the GPL gate is on `direct3d` (d3d9) | mpv `meson.build` 987–990 |
| `hwdec=d3d11va` (D3D11 hw decode) | no — `d3d-hwaccel`/`d3d11va` are FFmpeg/ANGLE side, not GPL-only | enabled-features list §1.2 |
| subtitles (libass, subrandr) | no — libass ISC | §1.6 |
| network/HTTP(S) (libcurl, TLS) | no — curl MIT-style, OpenSSL | §1.6 |
| audio out (WASAPI) | no — WASAPI is not GPL-gated | §2.1 |
| H.264/HEVC/AV1/VP9 **decoding** | no — FFmpeg's native decoders are LGPL; x264/x265 are **encoders** | FFmpeg `legal.html`; §1.5 |
| DVD-Video navigation, audio CD, DVB, d3d9 output | **yes — lost** | §2.1 |

So Option 1 costs capabilities the product does not use, and no capability the product does use.

**(e) The harder half: LGPL and linking.** FFmpeg's LGPL checklist item 2 is
*"Use dynamic linking (on windows, this means linking to dlls) for linking with FFmpeg libraries."*
Our current artifact does the opposite: FFmpeg is **statically** linked into the single
`libmpv-2.dll` (evidence: the package contains no `libav*.dll`; the builder passes
`--pkg-config-flags=--static` and `--enable-static`). An LGPL build must therefore either
  - ship FFmpeg as shared `avcodec/avformat/avutil/…` DLLs (more files, more import surface,
    another round of verification), **or**
  - rely on LGPLv2.1 §6's relinking provisions (provide object files or an equivalent mechanism so
    the recipient can relink the work with a modified FFmpeg).
  **Whether a given arrangement satisfies LGPL §6 is a legal question, not an engineering one —
  UNKNOWN here, escalate.** Note also that the LGPL keeps source-distribution obligations for the
  LGPL libraries themselves.

**(f) Acceptance evidence required before anyone writes "LGPL" anywhere.** All of:
1. The built `libmpv-2.dll` self-reports its feature list **without** `gpl` (the same raw-byte scan
   used in §1.2, recorded as build evidence).
2. A scan of the built DLL for `libx264`, `libx265`, `dvdnav`, `rubberband`, `davs2` descriptors
   returns nothing.
3. The exact meson command line and the FFmpeg `configure` line used, archived with the artifact's
   hashes (the current asset does not contain its FFmpeg line — see §1.4).
4. FFmpeg's runtime configuration string (`avutil_configuration()`) contains neither
   `--enable-gpl` nor `--enable-version3`.
5. A per-component licence audit (SBOM) with **no** GPL-family rows.
6. LGPL notices + source-availability and relink mechanism for the LGPL libraries.
7. Re-run of the W1/W2 playback evidence on the new engine — this is a **different binary**; the
   existing GUI_E2E PASS does not transfer.

### 2.4 Could this be verified in this environment? No.

- The task forbids installing anything, and the toolchain is absent: `meson` and `ninja` are not on
  `PATH`; `cmake` exists only inside the Visual Studio 2022 Community installation; MSYS2 provides
  `gcc` and `pkg-config`; there is no mingw-w64 cross toolchain. The builder's recipe compiles ~40
  dependencies for a `x86_64-w64-mingw32` target.
- What *was* verified here: the licence status of the **current** binary (§1) and the upstream
  claims about `-Dgpl` and FFmpeg (§2.1–2.3), all from primary sources.
- What would resolve it: run the build (fork of the builder recipe with the GPL packages removed) in a
  disposable Linux CI job or a dedicated Windows+MSYS2 machine, then apply the acceptance list (f).

### 2.5 Effort (engineering estimate, not measured)

Days, not hours: forking the builder recipe, removing GPL packages while keeping the dependency graph
consistent, rebuilding and re-validating the engine, and — if the shared-FFmpeg route is chosen —
changing what the package ships. A second-sourcing option (an already-published LGPL libmpv build for
Windows) was **not verified** and must not be relied on without the same acceptance evidence.

---

## 3. OPTION 2 — comply with the GPL for the current build

### 3.1 Exactly what must ship

1. **Corresponding Source** for the whole work, machine-readable, on the same server as the binary:
   mpv at `ec38361c5`, FFmpeg at `g2ec8998c3` (N-127521), **and** every library in the SBOM that is
   linked into `libmpv-2.dll`, plus the MediaHub sources for the Windows client.
2. **The build scripts that generate the shipped binary**, at the revision actually used. The builder
   is `shinchiro/mpv-winbuild-cmake`; the binary proves the release tag (`20261014`) but **not the
   builder commit** — see §5. UNKNOWN until resolved.
3. **Written offer** valid at least three years (GPLv3 §6(c)), or ship the source alongside the
   binary.
4. **Notices**: the GPLv3 text, mpv's `Copyright` + `LICENSE.GPL`, FFmpeg's `COPYING.GPLv3`, the
   copyright notices of the other components, and an explicit statement of MediaHub's own licence for
   this distribution.
5. **The complete dependency list** — this directory's SBOM plus the static-linkage audit inside the
   DLL.
6. **The user's rights**, stated in the product: to obtain, modify and redistribute the complete
   corresponding source of the work under GPLv3; an "Appropriate Legal Notices" surface; **no
   additional restrictions** (GPLv3 §10) — any EULA clause that forbids what the GPL permits, or any
   reverse-engineering prohibition, has to go.
7. **Installation Information** (GPLv3 §6) for the extent that applies, and the §11 patent grant.

### 3.2 Effect on the licence of the combined work

The portable package links the app's assemblies against a GPLv3+ library into one distributed work.
Conveying that work requires the **whole work** to be conveyed under GPLv3+ (GPLv3 §5); the app's own
DLLs in that package can no longer be offered under permissive terms *as distributed*. The GPL does
not reach into unrelated artifacts (for example the Android app built from the same repository is a
different work), but the licensing boundary between "the Windows distribution" and "everything else
in the repository" becomes a legal construction that has to be written down.

### 3.3 Why we must not switch the repository licence here

- The repository currently has **no licence file at all** (§1.7). Changing "the repository licence"
  is therefore not a correction of a stated licence — it is a first-ever decision, and it would bind
  the Android deliverables, every past contributor's expectations, and upstream PR #33.
- GPL compliance does **not** require the repository to be GPL: it requires the *conveyed work* to
  comply. The minimal compliant posture is a per-artifact licensing statement plus the source
  offer — still a human, informed decision.
- Once the Windows client ships under GPLv3+, that grant is irrevocable for those recipients. It is a
  one-way door.
- Therefore: **no licence change is proposed by this record.**

---

## 4. Recommendation

**Build an LGPL-compatible libmpv (OPTION 1). Keep the release blocked until it is done and evidenced.**

Reasoning:

1. The current build's GPL status is not a formality — it is a correctable supply-chain choice
   (§1.4: the builder enables GPL components by default and passes `--enable-gpl --enable-version3`).
2. Nothing MediaHub uses is GPL-gated (§2.3(d)): we are paying a licence obligation for DVD/CD/DVB/
   d3d9/x264/x265 features the product does not have.
3. OPTION 2 is not "attach a NOTICE file": it re-licenses the shipped product and imposes a
   permanent, 3-year, source-mirroring obligation for a binary we do not build ourselves (§3.1–3.3).
4. OPTION 1 is also not free, and this record will not pretend otherwise: the LGPL **linking**
   question (§2.3(e)) is unresolved and needs legal review, and the engine must be re-verified.

Actions that follow, in order:

1. **No release.** Keep CI output as a build artifact; create no GitHub Release and publish no
   package from the current asset. (The CI step at `.github/workflows/windows-ci.yml` 131–140
   uploads workflow artifacts only — that is not a distribution, and it must stay that way.)
2. Escalate the two legal questions: (i) does the planned FFmpeg linkage (shared DLLs vs relink
   provisions) satisfy LGPLv2.1 §6; (ii) what licence does MediaHub's own code carry, per artifact.
3. Execute Option 1 with the acceptance list §2.3(f).
4. Land the corrective edits listed in §6 (outside this directory) once approved.

---

## 5. UNKNOWN — facts not established here

| # | Unknown | Why it matters | What would resolve it |
| --- | --- | --- | --- |
| U1 | The `shinchiro/mpv-winbuild-cmake` **commit** that produced release `20261014` | Corresponding Source must be the source *actually used* (GPL) and an LGPL rebuild must start from it | the release page / build log for tag `20261014`, or the builder's CI run URL |
| U2 | FFmpeg's `configure` line as actually used for this asset | The binary does not contain it; the recipe is the pinned builder's current `master`, not a commit-pinned copy | the builder's build log, or a rebuild from U1 |
| U3 | Licence of `libzvbi` (and of `libbluray`, `subrandr`, `uavs3d`, `davs2`, `aom`, `SVT-AV1`, `libjxl`, `libopenmpt`) | Any GPL-family component forces GPL even after x264/x265 are removed | per-component licence audit against each upstream `COPYING`/`LICENSE` |
| U4 | Whether a prebuilt **LGPL** libmpv for Windows exists that we may redistribute | Could shortcut Option 1 | verify a candidate build against §2.3(f) — do not assume |
| U5 | Legal sufficiency of any particular LGPL linking arrangement (§2.3(e)) | Decides whether Option 1 is even achievable in the intended form | legal review |
| U6 | MediaHub's own intended licence (no `LICENSE` file exists) | Required for any distribution option | a human decision + a `LICENSE` file |
| U7 | Whether `libmpv-2.dll` is itself subject to additional terms from the asset host | Distribution chain | the asset's release notes (none read here) |

---

## 6. Corrections to existing files (this task wrote none of them; the status of each is recorded below)

| File | Current text | Correction | Basis |
| --- | --- | --- | --- |
| `windows/THIRD-PARTY-NOTICES.md` L10–11 | `GPL-2.0-or-later` | `GPL-3.0-or-later` | **done, W36/W38 (2026-10-31)** — both notice files carry the GPL-3.0-or-later label and `licence-decision-claims` pins it |
| `windows/docs/adr/ADR-W004-packaging-and-distribution.md` L54–61 | "`$AssetSha256` 仍为 `PENDING`" | Stale: `fetch-libmpv.ps1` now pins both digests (lines 35–36) and verifies both before install | read of both files |
| `README.md` L8 + `ADR-W001` L40 | licence badge links to `LICENSE`; asserts MIT | No `LICENSE` file exists in the repository | §1.7 |
| `windows/docs/ROADMAP-WINDOWS.md` W5 | "解决 libmpv 的 GPL 义务（自建 LGPL 配置，或按 GPL 提供 Corresponding Source）" | Accurate as a plan; W5 should point at this record and must not start before §4 actions 1–2 | this record |

---

## 7. Method

Every claim above is reproducible with the commands below (run from the repository root, PowerShell 7).

```powershell
# 1. identity of the shipped binary
Get-FileHash -Algorithm SHA256 windows/native/libmpv/libmpv-2.dll
(Get-Item windows/native/libmpv/libmpv-2.dll).VersionInfo | Format-List

# 2. self-reported feature list + embedded build command (raw byte scan, no external tools)
$b = [System.IO.File]::ReadAllBytes('windows/native/libmpv/libmpv-2.dll')
$s = [System.Text.Encoding]::GetEncoding(28591).GetString($b)
$i = $s.IndexOf('List of enabled features'); $s.Substring($i, 700)
$j = $s.IndexOf('-Dprefix=/__w/mpv-winbuild-cmake'); $s.Substring($j, 900)

# 3. repository licence facts
git ls-files | Select-String 'LICENSE|COPYING|NOTICE'
git log --oneline -- LICENSE

# 4. builder recipe (primary sources)
#    https://github.com/shinchiro/mpv-winbuild-cmake/blob/master/packages/ffmpeg.cmake
#    https://github.com/shinchiro/mpv-winbuild-cmake/blob/master/packages/mpv.cmake
#    https://github.com/mpv-player/mpv/blob/master/Copyright
#    https://github.com/mpv-player/mpv/blob/master/meson.options
#    https://github.com/mpv-player/mpv/blob/master/meson.build
#    https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md
#    https://ffmpeg.org/legal.html
```

Web-sourced facts are marked inline with their URL; all of them come from the component's own
repository or the component's own website. Nothing in this record is based on a third-party summary.

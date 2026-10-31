# W5-LGPL-BUILD — an actual LGPL-compatible libmpv, built and verified

- Status: **BUILT AND VERIFIED — engineering result, not a legal opinion.**
- Date: 2026-10-14 (W5-H). Author: engine-dev.
- Scope: proves what was *run*. It does not change any licence, does not authorise a release, and
  does not by itself make MediaHub distributable. The W3 record (LICENCE-DECISION.md) remains the
  decision document; this file is the evidence that its OPTION 1 is now executed rather than planned.
- Supersedes the §2.4 claim in LICENCE-DECISION.md that the LGPL build 'was not attempted and not
  attemptable here'. It **was** attempted, and it succeeded. The blocker moves from 'can we build it?'
  to 'do we ship it, and on what terms?'.

---

## 0. Headline

| Question | Answer |
| --- | --- |
| Is there now a real LGPL-compatible libmpv? | **Yes.** windows/engine/lgpl/dist/libmpv-2.dll, 3,327,274 bytes, sha256 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a |
| Does it still do what MediaHub needs? | **Yes, measured**: hwdec=d3d11va, vo=gpu-next, ctx=d3d11, libass subtitles, WASAPI audio. MpvProbe reports **PASS**. |
| Is 'gpl' in the engine's feature list? | **No.** 28 features, verified from three independent sources. |
| Is the shipped GPL engine changed? | **No.** windows/native/libmpv/libmpv-2.dll is still 6a48faec…63d9. |
| Is the whole distribution now clean? | **No — see §6.** Two components carry a GPL option in the packager's declaration and need review; the repo's own licence is still undocumented; nothing was switched over. |

---

## 1. What was built, and with exactly which commands

All scripts are in windows/engine/lgpl/. They are the reproducible record; the commands below are
what was executed on this machine.

### 1.1 Toolchain (all per-user, nothing installed system-wide)

The environment already had MSYS2 at C:\msys64 (ucrt64: gcc 14.2.0, binutils 2.44, pkgconf, make).
Missing pieces were added **per user**:

    python -m pip install --user meson ninja     # -> meson 1.12.1, ninja 1.13.2
    pacman -Sp ... nasm                          # -> nasm 3.02, extracted into C:\msys64\ucrt64

Docker/Podman/WSL were not used and are still absent. Nothing was installed system-wide.

### 1.2 Dependency set

MSYS2's **own ffmpeg package is GPL-3.0-or-later** (mingw-w64-ffmpeg/PKGBUILD declares
license=('spdx:GPL-3.0-or-later') and passes --enable-gpl / --enable-version3), so pacman cannot
supply an LGPL FFmpeg — that is why FFmpeg is built from source here.

The non-FFmpeg dependencies were taken from MSYS2 as **prebuilt packages**, hand-extracted into
C:\msys64\ucrt64 by install-msys2-deps.sh (pacman's install transaction was unusable: the installed
gcc-14 gcc-libs conflicts with the newer libgcc-16.2.0, and pacman -Su refuses the transaction;
pacman -Sp resolution still works, which is what was used). 32 packages, 31,338,987 bytes,
individually hashed in windows/engine/lgpl/msys2-package-hashes.txt.

    bash -l windows/engine/lgpl/install-msys2-deps.sh
    gcc sanity after extraction: gcc-ok (gcc still 14.2.0)
    libplacebo 7.360.1   libass 0.17.5   freetype 26.7.20   fribidi 1.0.17
    harfbuzz 14.6.0      vulkan 1.4.363  shaderc 2026.4.1   lcms2 2.19

### 1.3 FFmpeg 8.1.1 — LGPL, shared

    bash -l windows/engine/lgpl/build-ffmpeg-lgpl.sh

The configure line is the licence claim, and the binary now reports it itself:

    --prefix=/d/mh-servers/lgpl-build/prefix --arch=x86_64 --target-os=mingw32
    --disable-gpl --disable-version3 --disable-nonfree
    --enable-shared --disable-static --disable-autodetect --enable-zlib
    --enable-d3d11va --enable-d3d12va --enable-schannel --enable-network
    --enable-w32threads --enable-runtime-cpudetect --disable-debug --disable-doc ...

FFmpeg's own configure prints, verbatim: **License: LGPL version 2.1 or later**.

ffmpeg -version (i.e. avutil_configuration()) — acceptance item 4 — contains
--disable-gpl --disable-version3 --disable-nonfree and **zero** --enable-(gpl|version3|nonfree)
matches. ffmpeg -hwaccels on this build:

    Hardware acceleration methods:
    d3d11va
    d3d12va

GPL-only code, tested properly (by enumerating the codec lists, not by '-h encoder=', which returns 0
even for absent codecs): libx264, libx265, libxvid, libdavs2 are **0 encoders / 0 decoders**, while
the native LGPL decoders the product relies on are present (h264, hevc, av1, vp9). A raw descriptor
scan of every shipped DLL returns **0 hits** for libx264|libx265|libdavs2|librubberband|libdvdnav|libdvdread.

**Dynamic, not static:** --enable-shared produces avcodec-62.dll, avformat-62.dll, avutil-60.dll,
avfilter-11.dll, swscale-9.dll, swresample-6.dll, avdevice-62.dll. This is what FFmpeg's own LGPL
compliance checklist asks for on Windows, and it is the opposite of the shipped GPL artifact, where
FFmpeg is statically baked into the single libmpv-2.dll.

### 1.4 mpv at the shipped commit, -Dgpl=false

    bash -l windows/engine/lgpl/build-mpv-lgpl.sh

Source: git clone + checkout ec38361c5 — **byte-for-byte the same source revision as the shipped
DLL's own embedded version string v0.41.0-1154-gec38361c5** (git describe on this clone says
git-release-435-gec38361c5; the commit id is what matters and it matches).

meson was given -Dgpl=false plus the capabilities MediaHub uses, and explicit 'disabled' for every
GPL-adjacent or unused feature (dvdnav, dvda, rubberband, direct3d, lua, javascript, vapoursynth,
sixel, subrandr, zimg, …). The full line is embedded in the binary and retrievable at runtime via the
mpv-configuration property — acceptance item 3.

---

## 2. Acceptance evidence (the checklist from LICENCE-DECISION.md §2.3(f))

**1. The built DLL self-reports its feature list without 'gpl'.** Three independent sources agree
(meson build message, the FULLCONFIG constant inside libmpv-2.dll, and mpv.exe -v at runtime):

    List of enabled features: build-date cplugins d3d-hwaccel d3d11 d3d9-hwaccel dos-paths
    dxgi-debug-d3d11 ffmpeg gl gl-dxinterop gl-dxinterop-d3d9 gl-win32 glob glob-win32 iconv
    lcms2 libass libavdevice libplacebo shaderc spirv-cross vector wasapi win32 win32-desktop
    win32-executable win32-threads zlib

'gpl' is absent; direct3d, dvda, dvdnav, javascript, lua are absent. Recorded in
windows/engine/lgpl/mpv-lgpl-features.txt.

**2. No GPL-only descriptors in the built DLL.** 0 hits for x264/x265/davs2/rubberband/dvdnav/dvdread.

**3. The exact build command lines** are archived: ffmpeg-config-evidence.txt (configure line +
ffbuild/config.mak facts), mpv-meson-setup.log, and the runtime mpv-configuration string.

**4. FFmpeg's runtime configuration string** — no --enable-gpl, no --enable-version3 (see §1.3).

**5. Per-component licence audit** — windows/engine/lgpl/component-licences.txt (packager
declarations) and dist/runtime-bundle-manifest.txt (every shipped DLL with its sha256). The
definitive runtime closure is 33 DLLs; see §6 for the two that need review.

**6. LGPL notices + source availability + relink mechanism** — the mechanism is now *dynamic linking*:
every LGPL component ships as its own replaceable DLL. Notices and written offer are still **not**
produced; that is outstanding packaging work, not a build problem.

**7. Re-run of the playback evidence on the new engine** — done, below.

### 2.1 MpvProbe against the LGPL engine

The GPL baseline was not touched: the probe's bin directory was copied to a scratch path and the new
DLL swapped in there.

    libmpv-2.dll        : D:\mh-servers\lgpl-build\probe-lgpl\libmpv-2.dll
    libmpv-2.dll sha256 : 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a
    mpv-version         : mpv v0.41.0-UNKNOWN
    ffmpeg-version      : 8.1.1
    libass-version      : 24137728
    gpl mentioned in banner: False

Playback (sample_av.mp4, vo=gpu-next hwdec=auto-safe ctx=d3d11):

    RESULT duration=12.000s reached=4.280s seeked=3.800s audioTracks=1 subTracks=1 snapshots=84
    MPV_PROBE PASS

The engine's own log shows the full path being exercised — not a fallback:

    [vd] Using hardware decoding (d3d11va).
    [vo/gpu-next/d3d11] Initializing GPU context 'd3d11'
    [vo/gpu-next/d3d11] Using Direct3D 11 feature level 12_1
    [vo/gpu-next/d3d11] Device Name: NVIDIA GeForce RTX 4070 Laptop GPU
    [ad] Selected decoder: aac - AAC (Advanced Audio Coding)
    [ao] Trying audio driver 'wasapi'
    [ao/wasapi] Selecting device '{d936278c-…}' (Realtek(R) Audio)

So D3D11VA hardware decoding, the gpu-next/libplacebo renderer over the D3D11 GPU context, libass
subtitles, and WASAPI output to a real device are all present and running. libplacebo compiled its
shaders through SPIRV-Cross for D3D11 (visible in the log as generated HLSL).

---

## 3. What the LGPL build loses

| Capability | Shipped GPL build | LGPL build | Note |
| --- | --- | --- | --- |
| H.264/HEVC/AV1/VP9 **decode** | yes | yes | native FFmpeg decoders are LGPL |
| D3D11VA hw decode | yes | **yes** | measured |
| gpu-next (libplacebo) | yes | **yes** | measured, v7.360.1 |
| WASAPI | yes | **yes** | measured |
| libass subtitles | yes | **yes** | measured |
| Network/HTTPS | libcurl + ffmpeg | ffmpeg (--enable-network --enable-schannel) | libcurl disabled; mpv's stream layer falls back to lavf |
| x264/x265 **encoders** | yes | **no** | GPL-only; not used by MediaHub |
| DVD-Video/DVD-Audio, audio CD, DVB | yes | **no** | GPL-only; not used |
| d3d9 (vo=direct3d) | yes | **no** | GPL-gated; MediaHub uses gpu-next |
| Vulkan context | yes | **no** | *not* a licence issue — see below and [VULKAN-NECESSITY.md](VULKAN-NECESSITY.md) |
| lua/js scripting, rubberband, libarchive, bluray, sixel, vapoursynth, zimg | yes | no | unused |

**Vulkan is missing for a mundane build reason, not a licence one:** meson found the loader
(vulkan 1.4.363) but the header probe 'symbol VK_VERSION_1_3 in vulkan/vulkan_core.h' returned
**NO**, so mpv compiled the feature out. MediaHub drives gpu-context=d3d11, which is verified
working, so this is not currently a product regression — but 'vulkan' is a capability the GPL build
had and the LGPL build does not, and it should be closed before this engine replaces the baseline. That assessment now exists (W37, 2026-10-31): MediaHub hard-wires gpu-api=d3d11 and defaults gpu-context=d3d11, so Vulkan is on no shipped path and the candidate is not degraded there; whether to close the delta before a switch is decision B in VULKAN-NECESSITY.md section 5, and the default flavour stays unchanged.

---

## 4. Two environment traps worth recording

1. **The extracted libgcc-16.2.0 runtime breaks gcc-14-built binaries.** After hand-extracting the
   package closure, ffmpeg.exe died with 0xC0000139 (STATUS_ENTRYPOINT_NOT_FOUND) / shell rc=127
   while ldd reported every dependency resolved. Restoring the backed-up gcc-14 libgcc_s_seh-1.dll
   and libstdc++-6.dll fixed it immediately. install-msys2-deps.sh therefore backs them up and
   restores them; the shipped manifest hashes are the gcc-14 ones.
   Consequence: glslang.dll (a gcc-16 C++ build) cannot load here (error 127). It is **not** used —
   mpv's shader path is shaderc + spirv-cross, both of which load and work.
2. **MpvProbe's log path is not unique.** The probe writes %TEMP%\mediahub-mpvprobe.log, and MSYS2
   shells set TEMP=/tmp, so two agents running probes concurrently write to *different* files that
   look like the same evidence. A stale log from a baseline run initially appeared to show the LGPL
   build with a 'gpl' feature list. It did not: the file was another process's. Every feature-list
   claim in §2 therefore comes from the DLL itself and from mpv.exe -v, not from a temp file.

---

## 5. Reproducing it

    bash -l windows/engine/lgpl/install-msys2-deps.sh      # hand-install the MSYS2 closure
    bash -l windows/engine/lgpl/build-ffmpeg-lgpl.sh       # LGPL FFmpeg 8.1.1 (shared)
    bash -l windows/engine/lgpl/build-mpv-lgpl.sh          # libmpv + mpv.exe, -Dgpl=false
    bash -l windows/engine/lgpl/capture-ffmpeg-evidence.sh # licence evidence
    bash -l windows/engine/lgpl/verify-mpv-lgpl.sh         # feature-list checks
    bash -l windows/engine/lgpl/run-probe-lgpl.sh          # MpvProbe against the new engine

Outputs land in windows/engine/lgpl/; the artifact is windows/engine/lgpl/dist/libmpv-2.dll.

---

## 6. Residual obligations — what is still NOT done

**This section is the honest one. Nothing here is satisfied by the build above.**

1. **One component still carries a GPL option in the packager's own declaration and needs review before
   distribution:**
   - liblcms2-2.dll — MSYS2 declares 'MIT AND GPL-3.0-or-later'. lcms2 upstream is MIT, but the declared
     combination still needs review before distribution.
   libiconv-2.dll used to head this list. W38 (2026-10-31) settled the open question by re-reading the
   packager's own metadata instead of reasoning about upstream: MSYS2 now declares `LGPL-2.1-or-later`
   for the package, with `documentation:GPL-3.0-or-later` scoped to the package's documentation, so the
   shipped *library* is LGPL and the GPL terms ride on documentation that is not distributed (recorded in
   `windows/engine/lgpl/component-licences.txt`). Re-collecting that table also turned three rows that
   were previously inferred into explicit records: `libintl-8` (`GPL-3.0-or-later AND LGPL-2.1-or-later`,
   from gettext-runtime), and `libstdc++-6` / `libgcc_s_seh-1` (`GPL-3.0-or-later WITH
   GCC-exception-3.1 AND GFDL-1.3-or-later`). Everything else in the 33-DLL closure is LGPL, MIT, ISC,
   BSD, Apache-2.0, Zlib, or GPL-with-GCC-exception. Notably jbigkit (GPL-2.0) is in the pacman closure
   but **not** in the runtime closure, so it does not ship. freetype is 'GPL-2.0-or-later OR FTL' — the
   FTL option is the permissive one and is what is relied on here.
2. **-Dgpl=false is still not a licence grant.** mpv's own Copyright says so. The build is now
   *configured* to be LGPL-compatible and no GPL-family component is linked — but whether the
   assembled distribution satisfies LGPL §6 (and what MediaHub's own licence is) remains a
   human/legal decision. The relink mechanism is now genuinely available (dynamic DLLs), which
   materially improves the position versus the shipped static build.
3. **LGPL notices, source availability and a written offer are not produced.** Notices for the LGPL
   libraries, their source tarballs/URLs, and the offer must ship with any release.
4. **Nothing has been switched over.** windows/src still loads windows/native/libmpv/libmpv-2.dll,
   the GPL baseline. Replacing the engine is a product decision and requires a full re-run of the GUI
   and playback acceptance suite — the MpvProbe PASS in §2.1 is necessary, not sufficient.
5. **The repository still has no LICENSE file** (W3 finding, unchanged).
6. **The engine's version string is now v0.41.0-UNKNOWN** instead of v0.41.0-1154-gec38361c5: the
   blob-filtered clone could not satisfy mpv's git describe pattern. Cosmetic here, but anything
   that parses the engine version must not be broken by it.
7. **MSYS2's dependency set is not a pinned, reproducible feed.** The build resolves against whatever
   the rolling MSYS2 repos served on 2026-10-14; msys2-package-hashes.txt records exactly what was
   used, but a reproducible build needs those artifacts archived.

---

## 8. Machine-readable status (checked by CI)

The block below is read by `windows/tools/check-licence-surface.ps1` (check `lgpl-build-record-claims`,
W55) and compared against the build record in `windows/engine/lgpl/`. It is appended at the **end** of
this file on purpose: the line-number citations other documents make into this record
(`LGPL-COMPLIANCE.md`, `SBOM.md`, `THIRD-PARTY-NOTICES.md`, `LICENCE-DECISION.md`, `LGPL-ENGINE-SWITCH.md`)
stay valid only as long as this section adds nothing above them.

<!-- BEGIN lgpl-build-status -->
```text
candidate-bytes: 3327274
candidate-sha256: 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a
gpl-baseline-sha256: 6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9
msys2-package-count: 32
msys2-package-bytes: 31338987
msys2-package-bytes-source: session-cache-measurement-not-archived
feature-count: 28
gpl-feature-absent: true
mpv-version-reports: v0.41.0-UNKNOWN
ffmpeg-version: 8.1.1
ffmpeg-hwaccels: d3d11va, d3d12va
runtime-dll-count: 33
runtime-collected: dist/runtime-dependencies-collected.txt
runtime-manifest: dist/runtime-bundle-manifest.txt
build-scripts: install-msys2-deps.sh, build-ffmpeg-lgpl.sh, build-mpv-lgpl.sh, capture-ffmpeg-evidence.sh, verify-mpv-lgpl.sh, run-probe-lgpl.sh
probe-files: probe-lgpl-hwdec.txt, probe-lgpl-audio.txt, probe-lgpl-playback.txt, probe-lgpl-info.txt
probe-result: MPV_PROBE PASS
```
<!-- END lgpl-build-status -->

How each line is checked (all paths relative to `windows/engine/lgpl/`):

| Line | Checked against |
| --- | --- |
| `candidate-bytes` / `candidate-sha256` | `dist/libmpv-2.dll` on disk, `dist/dist-hashes.txt`, `probe-lgpl-info.txt` |
| `gpl-baseline-sha256` | `windows/tools/fetch-libmpv.ps1` `$DllSha256` |
| `msys2-package-count` | number of hashed rows in `msys2-package-hashes.txt`, and the count quoted in section 1.2 |
| `msys2-package-bytes` | **not** re-checkable from the tree: the 32 tarballs live in the W5-H cache `D:\mh-servers\lgpl-build\pkgs` (32 files, 31,338,987 bytes, measured 2026-10-31), which the repository does not archive |
| `feature-count` / `gpl-feature-absent` | first feature line in `mpv-lgpl-features.txt` |
| `mpv-version-reports` / `ffmpeg-version` | `mpv-lgpl-version.txt` |
| `ffmpeg-hwaccels` | `ffmpeg-hwaccels.txt`, plus `--disable-gpl / --disable-version3 / --disable-nonfree` in `ffmpeg-version.txt` |
| `runtime-dll-count` / `runtime-collected` / `runtime-manifest` | the dependency rows in `dist/runtime-dependencies-collected.txt`; every `*.dll` actually present in `dist/` next to the candidate must agree too |
| `build-scripts` / `probe-files` | each named file exists |
| `probe-result` | `MPV_PROBE PASS` in `probe-lgpl-playback.txt`, plus the hwdec and WASAPI tokens in their own files |

The 33 runtime DLLs are build products and stay out of the repository by design
(`windows/engine/lgpl/dist/.gitignore` commits only the candidate): CI therefore checks their committed
identity record - `dist/runtime-dependencies-collected.txt`, one line per shipped dependency - rather
than bytes that are not in the tree.

Two prose claims are held at the same time: section 6 must still say this build is **not** a legal
opinion and that **nothing has been switched over**, and this document must not start claiming the
distribution is clean or release-ready.

# windows/engine — rebuilt engine artifacts and the Emby deployment harness (W5)

Owner: engine-dev (W5-H, W5-F). Nothing here is wired into the product yet.

    lgpl/     the LGPL libmpv build: scripts, evidence, and the built DLL under lgpl/dist/
    emby/     the official Emby 4.10.1.0 deployment + API-shape harness

## lgpl/ — LGPL-compatible libmpv

Result: **windows/engine/lgpl/dist/libmpv-2.dll**, 3,327,274 bytes,
sha256 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a — a real build with
-Dgpl=false against an FFmpeg 8.1.1 built --disable-gpl --disable-version3 --disable-nonfree.
It plays H.264 with d3d11va hardware decoding through gpu-next/libplacebo on a d3d11 context, with
libass subtitles and WASAPI audio: MpvProbe reports PASS.

**The GPL baseline at windows/native/libmpv/libmpv-2.dll was NOT touched** and must stay exactly
6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9. dist/ is a separate artifact.

Read windows/docs/licensing/W5-LGPL-BUILD.md before making any licence claim: the build is
LGPL-compatible, but -Dgpl=false is not by itself a licence grant, two DLLs still need a licence
review, and notices/source-offer are not produced.

Scripts, in execution order:

    install-msys2-deps.sh       hand-install the MSYS2 UCRT64 closure (pacman's transaction is broken)
    build-ffmpeg-lgpl.sh        LGPL FFmpeg 8.1.1, shared libraries
    build-mpv-lgpl.sh           libmpv-2.dll + mpv.exe with -Dgpl=false
    capture-ffmpeg-evidence.sh  ffmpeg -version/-buildconf/hwaccels + hashes
    verify-mpv-lgpl.sh          feature-list, gpl-token and descriptor checks
    collect-licences.sh         per-component licence declarations
    make-bundle-manifest.sh     sha256 of every redistributable DLL
    run-probe-lgpl.sh           MpvProbe (--info and playback) against the new engine

Evidence files: ffmpeg-version.txt, ffmpeg-buildconf.txt, ffmpeg-hwaccels.txt,
ffmpeg-config-evidence.txt, ffmpeg-artifact-hashes.txt, mpv-lgpl-version.txt,
mpv-lgpl-features.txt, mpv-meson-setup.log, probe-lgpl-info.txt, probe-lgpl-playback.txt,
probe-lgpl-audio.txt, probe-lgpl-render.txt, probe-lgpl-hwdec.txt, component-licences.txt,
msys2-package-hashes.txt, msys2-files-added.txt.

Build tree (not in the repo): D:\mh-servers\lgpl-build — sources, prefixes, the probe staging copy.

## emby/ — official Emby 4.10.1.0

    start-emby.ps1   start the server out of an isolated data dir
    setup-emby.ps1   stop, force the port in system.xml, restart, drive the startup wizard
    emby-api.sh      startup wizard + auth + /Users/Me probes, full transcript
    emby-playbackinfo.sh  /Users/Me variants, library creation, PlaybackInfo, PlaySessionId
    emby-verify.sh   item-id shape checks

Server state: running on **8097** (8096 belongs to the Jellyfin instance), data in
D:\mh-servers\data\emby, install in D:\mh-servers\emby-install\app, user mh / mh-test-pass.
Stop it with: Get-Process EmbyServer | Stop-Process -Force

Read emby/EMBY-4.10-SHAPE.md for the Jellyfin-vs-Emby comparison. The headline: Emby returns
**short numeric item ids**, not GUIDs, and **/Users/Me returns 500** — use /Users/{id}. PlaybackInfo's
**PlaySessionId is top-level on both servers**, so the W4 Jellyfin fix for that one holds.

#!/usr/bin/env bash
# W5-H step 3: build libmpv + mpv.exe with -Dgpl=false against the LGPL FFmpeg built by
# build-ffmpeg-lgpl.sh.  Run:  bash -l windows/engine/lgpl/build-mpv-lgpl.sh
set -euo pipefail

export MSYSTEM=UCRT64
export PATH=/ucrt64/bin:/usr/bin:$PATH

SRC_DIR=${SRC_DIR:-/d/mh-servers/lgpl-build}
FFPREFIX=${FFPREFIX:-$SRC_DIR/prefix}
MPVPREFIX=${MPVPREFIX:-$SRC_DIR/prefix-mpv}
OUT=${OUT:-/d/deepseek_test/MediaHub_Win/windows/engine/lgpl}
MESON=${MESON:-/c/Python/Python313/python.exe -m mesonbuild.mesonmain}
NINJA_DIR=/c/Users/55160/AppData/Roaming/Python/Python313/Scripts
export PATH="$NINJA_DIR:$PATH"

# Only these two pkg-config roots are visible: our LGPL FFmpeg first, then MSYS2 UCRT64.
# MSYS2's own (GPL) ffmpeg is deliberately NOT installed, so it cannot be picked up here.
export PKG_CONFIG_PATH="$FFPREFIX/lib/pkgconfig:/ucrt64/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$FFPREFIX/lib/pkgconfig:/ucrt64/lib/pkgconfig"

cd "$SRC_DIR/mpv"

$MESON setup build-lgpl \
  --buildtype=release \
  --prefix="$MPVPREFIX" \
  -Dgpl=false \
  -Dlibmpv=true \
  -Dcplayer=true \
  -Dwin32-subsystem=console \
  -Dwin32-threads=enabled \
  -Ddefault_library=shared \
  -Dprefer_static=false \
  -Dpdf-build=disabled \
  -Dtests=false \
  -Dd3d11=enabled \
  -Dd3d-hwaccel=enabled \
  -Dwasapi=enabled \
  -Dvulkan=enabled \
  -Dshaderc=enabled \
  -Dspirv-cross=enabled \
  -Dlcms2=enabled \
  -Diconv=enabled \
  -Dgl=enabled \
  -Degl-angle=disabled \
  -Ddirect3d=disabled \
  -Dx11=disabled \
  -Dlua=disabled \
  -Djavascript=disabled \
  -Dlibcurl=disabled \
  -Dlibarchive=disabled \
  -Dlibbluray=disabled \
  -Ddvdnav=disabled \
  -Ddvda=disabled \
  -Duchardet=disabled \
  -Drubberband=disabled \
  -Dsixel=disabled \
  -Dsubrandr=disabled \
  -Dzimg=disabled \
  -Djpeg=disabled \
  -Dvapoursynth=disabled \
  -Dopenal=disabled \
  -Dsdl2-gamepad=disabled \
  -Dsdl2-video=disabled \
  -Dsdl2-audio=disabled \
  -Dvaapi-win32=disabled \
  2>&1 | tee "$OUT/mpv-meson-setup.log"

echo "=== MESON SETUP OK $(date -Is) ==="
grep -E '^(Message|Run-time dependency|Library|C compiler|Program)' -A0 "$OUT/mpv-meson-setup.log" | head -0 || true

$MESON compile -C build-lgpl -j 32 2>&1 | tail -25
$MESON install -C build-lgpl 2>&1 | tail -10
echo "=== MPV LGPL BUILD DONE $(date -Is) ==="
ls -la "$MPVPREFIX/bin/" 2>/dev/null || true

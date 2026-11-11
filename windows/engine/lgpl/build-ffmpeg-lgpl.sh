#!/usr/bin/env bash
# W5-H step 2: build an LGPL-compatible FFmpeg (shared libraries) for the LGPL libmpv rebuild.
# Run via an MSYS2 UCRT64 shell:  bash -l windows/engine/lgpl/build-ffmpeg-lgpl.sh
# The configure line below IS the licence claim; it is archived verbatim with the artifact hashes.
set -euo pipefail

export MSYSTEM=UCRT64
export PATH=/ucrt64/bin:/usr/bin:$PATH

SRC_DIR=${SRC_DIR:-/d/mh-servers/lgpl-build}
PREFIX=${PREFIX:-$SRC_DIR/prefix}
FFVER=${FFVER:-8.1.1}
OUT=${OUT:-/d/deepseek_test/MediaHub_Win/windows/engine/lgpl}

cd "$SRC_DIR/ffmpeg-$FFVER"

if [ -f ffbuild/config.mak ] && grep -q '^CONFIG_GPL=no' ffbuild/config.mak; then
  echo "=== reusing existing configure (CONFIG_GPL=no) ==="
else
  # --disable-gpl --disable-version3 --disable-nonfree  =>  the result is LGPL-2.1-or-later.
  # --disable-autodetect keeps every external (possibly GPL) system library out of the build.
  # --enable-shared makes FFmpeg DLLs, which is also what LGPL asks for (dynamic linking).
  ./configure \
    --prefix="$PREFIX" \
    --arch=x86_64 --target-os=mingw32 \
    --disable-gpl --disable-version3 --disable-nonfree \
    --enable-shared --disable-static \
    --disable-autodetect \
    --enable-zlib \
    --enable-d3d11va --enable-d3d12va \
    --enable-schannel \
    --enable-network --enable-w32threads \
    --enable-runtime-cpudetect \
    --disable-debug \
    --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
    --extra-cflags="-O2" \
    2>&1 | tee "$OUT/ffmpeg-configure.log"
fi

echo "=== CONFIGURE OK $(date -Is) ==="
{
  echo "# FFmpeg build configuration facts, read from ffbuild/config.mak"
  grep -E '^(CONFIG_GPL|CONFIG_VERSION3|CONFIG_NONFREE|CONFIG_SHARED|CONFIG_STATIC|CONFIG_D3D11VA|CONFIG_D3D12VA|CONFIG_SCHANNEL|CONFIG_NETWORK|CONFIG_W32THREADS|CONFIG_ZLIB|CONFIG_X86ASM)=' ffbuild/config.mak
  echo
  echo "# first lines of ffbuild/config.log (the configure invocation, verbatim)"
  head -25 ffbuild/config.log
} > "$OUT/ffmpeg-config-evidence.txt"
cat "$OUT/ffmpeg-config-evidence.txt"

echo "=== make -j$(nproc) $(date -Is) ==="
make -j"$(nproc)" 2>&1 | tail -25
make install 2>&1 | tail -15

echo "=== FFmpeg LGPL build DONE $(date -Is) ==="
"$PREFIX/bin/ffmpeg.exe" -hide_banner -buildconf 2>&1 | tee "$OUT/ffmpeg-buildconf.txt"
echo "--- GPL-flag hits in buildconf (expect 0) ---"
grep -c -E -- '--enable-(gpl|version3|nonfree)' "$OUT/ffmpeg-buildconf.txt" || true
echo "--- sha256 of built ffmpeg.exe / libavcodec ---"
sha256sum "$PREFIX/bin/ffmpeg.exe"
sha256sum "$PREFIX/bin/libavcodec-"*.dll

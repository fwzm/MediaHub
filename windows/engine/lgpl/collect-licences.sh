#!/usr/bin/env bash
# Licence of every redistributable component, as declared by the packager (MSYS2 pacman metadata).
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
PKGS="mingw-w64-ucrt-x86_64-libass mingw-w64-ucrt-x86_64-libplacebo mingw-w64-ucrt-x86_64-shaderc
mingw-w64-ucrt-x86_64-spirv-cross mingw-w64-ucrt-x86_64-vulkan-loader mingw-w64-ucrt-x86_64-glslang
mingw-w64-ucrt-x86_64-freetype mingw-w64-ucrt-x86_64-harfbuzz mingw-w64-ucrt-x86_64-fribidi
mingw-w64-ucrt-x86_64-fontconfig mingw-w64-ucrt-x86_64-lcms2 mingw-w64-ucrt-x86_64-libdovi
mingw-w64-ucrt-x86_64-libunibreak mingw-w64-ucrt-x86_64-glib2 mingw-w64-ucrt-x86_64-libpng
mingw-w64-ucrt-x86_64-brotli mingw-w64-ucrt-x86_64-zlib mingw-w64-ucrt-x86_64-libiconv
mingw-w64-ucrt-x86_64-graphite2 mingw-w64-ucrt-x86_64-libgcc mingw-w64-ucrt-x86_64-libstdc++
mingw-w64-ucrt-x86_64-libwinpthread-git mingw-w64-ucrt-x86_64-libjpeg-turbo
mingw-w64-ucrt-x86_64-libtiff mingw-w64-ucrt-x86_64-libwebp mingw-w64-ucrt-x86_64-giflib
mingw-w64-ucrt-x86_64-lerc mingw-w64-ucrt-x86_64-libdeflate mingw-w64-ucrt-x86_64-jbigkit"

{
  echo "# Licence of each redistributable component, as declared by the MSYS2 packager"
  echo "# (pacman -Si <pkg> -> 'License'), collected $(date -Is)"
  echo "# FFmpeg itself is not here: it is built from source by build-ffmpeg-lgpl.sh and reports"
  echo "# 'License: LGPL version 2.1 or later' from its own configure output."
  printf '%-46s %s\n' "PACKAGE" "LICENSE"
  for p in $PKGS; do
    lic=$(pacman -Si "$p" 2>/dev/null | grep -E '^软件许可|^License' | head -1 | sed 's/.*: *//')
    ver=$(pacman -Si "$p" 2>/dev/null | grep -E '^版本|^Version' | head -1 | sed 's/.*: *//')
    [ -n "$lic" ] && printf '%-46s %s  (%s)\n' "${p#mingw-w64-ucrt-x86_64-}" "$lic" "$ver"
  done
} | tee "$O/component-licences.txt"
echo
echo "=== any GPL-family declarations? ==="
grep -iE 'GPL' "$O/component-licences.txt" | grep -viE 'LGPL|exception' || echo "  (none: every GPL mention is LGPL or a GCC exception)"

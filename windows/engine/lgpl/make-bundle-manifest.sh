#!/usr/bin/env bash
# Emit the redistributable runtime bundle of the LGPL engine: every non-system DLL the new
# libmpv-2.dll needs, with its sha256, so the licence record can name exactly what ships.
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
MP=/d/mh-servers/lgpl-build/prefix-mpv/bin
FFP=/d/mh-servers/lgpl-build/prefix/bin
OUT="$O/dist/runtime-bundle-manifest.txt"

{
  echo "# Runtime bundle of the LGPL libmpv-2.dll (W5-H)"
  echo "# generated $(date -Is)"
  echo "#"
  echo "# Core artifact:"
  sha256sum "$MP/libmpv-2.dll"
  echo
  echo "# FFmpeg shared libraries - LGPL-2.1-or-later, built by windows/engine/lgpl/build-ffmpeg-lgpl.sh"
  sha256sum "$FFP"/av*.dll "$FFP"/sw*.dll "$FFP"/ffmpeg.exe "$FFP"/ffprobe.exe
  echo
  echo "# Third-party dependencies from MSYS2 UCRT64 (all LGPL / permissive; see msys2-package-hashes.txt)"
  for f in libass-9.dll libplacebo-360.dll libshaderc_shared.dll libspirv-cross-c-shared.dll \
           libfreetype-6.dll libharfbuzz-0.dll libfribidi-0.dll libunibreak-5.dll liblcms2-2.dll \
           libdovi.dll libpng16-16.dll libbrotlidec.dll libbrotlicommon.dll libgraphite2.dll \
           libglib-2.0-0.dll libintl-8.dll libiconv-2.dll zlib1.dll libgcc_s_seh-1.dll \
           libwinpthread-1.dll libstdc++-6.dll libvulkan-1.dll vulkan-1.dll glslang.dll; do
    [ -f "/ucrt64/bin/$f" ] && sha256sum "/ucrt64/bin/$f"
  done | sort -u
  echo
  echo "# NOTE: glslang.dll fails to load against the gcc-14 runtime shipped here (missing export);"
  echo "#       it is not used by this build - mpv's shader path is shaderc + spirv-cross."
} > "$OUT"
cat "$OUT"

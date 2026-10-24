#!/usr/bin/env bash
# W5-H step 1: hand-install the (non-GPL) MSYS2 UCRT64 dependency closure into the existing
# C:\msys64\ucrt64 root, bypassing pacman's broken install transaction (see REPORT.md).
# Rationale: MSYS2 packages hard-code prefix=/ucrt64 in their .pc files, so extracting into that
# root keeps pkg-config correct without rewriting anything.
# SAFETY: files that would overwrite the working gcc-14 runtime are backed up first, and the
# script re-verifies that gcc still compiles and runs afterwards.
set -euo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH

CACHE=/d/mh-servers/lgpl-build/pkgs
OUT=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
BK=/d/mh-servers/lgpl-build/backup-ucrt64
mkdir -p "$BK" "$OUT"

echo "=== package hashes (SBOM input) ==="
( cd "$CACHE" && sha256sum *.pkg.tar.zst ) | tee "$OUT/msys2-package-hashes.txt"

echo "=== back up gcc-14 runtime DLLs that newer packages would overwrite ==="
for f in libgcc_s_seh-1.dll libstdc++-6.dll libwinpthread-1.dll; do
  if [ -f "/ucrt64/bin/$f" ]; then cp -p "/ucrt64/bin/$f" "$BK/$f"; echo "backed up $f"; fi
done

echo "=== extract ==="
: > "$OUT/msys2-files-added.txt"
cd "$CACHE"
for p in *.pkg.tar.zst; do
  [ -f "$p" ] || continue
  printf '%s ' "$p"
  tar --zstd -tf "$p" | sed 's|^|/|' >> "$OUT/msys2-files-added.txt"
  tar --zstd -xf "$p" -C / --warning=no-unknown-keyword ucrt64/ || echo "  (no ucrt64/ payload)"
  echo "extracted"
done
echo "=== files added: $(wc -l < "$OUT/msys2-files-added.txt") ==="

echo "=== gcc sanity after extraction ==="
cd /tmp
printf '#include <stdio.h>\nint main(void){printf("gcc-ok\\n");return 0;}\n' > _hello.c
/ucrt64/bin/gcc.exe _hello.c -o _hello.exe && ./_hello.exe
echo "gcc version now: $(/ucrt64/bin/gcc.exe -dumpfullversion 2>&1)"
echo "libgcc_s_seh-1.dll now: $(sha256sum /ucrt64/bin/libgcc_s_seh-1.dll 2>/dev/null | cut -c1-16)"

echo "=== key pkg-config modules ==="
for m in libplacebo libass freetype2 fribidi harfbuzz vulkan shaderc spirv-cross lcms2 libdovi; do
  printf '%-14s %s\n' "$m" "$(pkg-config --modversion $m 2>/dev/null || echo MISSING)"
done

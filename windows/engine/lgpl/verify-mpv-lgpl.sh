#!/usr/bin/env bash
# W5-H acceptance checks 1 and 2 for the rebuilt engine.
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
MP=/d/mh-servers/lgpl-build/prefix-mpv/bin
export PATH="$MP:/d/mh-servers/lgpl-build/prefix/bin:$PATH"
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl

if [ ! -x "$MP/mpv.exe" ]; then echo "FATAL: $MP/mpv.exe not found - aborting instead of printing vacuous PASSes"; exit 3; fi

echo "=== mpv --version (this is the feature list baked into the binary) ==="
"$MP/mpv.exe" --version 2>&1 | tee "$O/mpv-lgpl-version.txt"

echo
echo "=== acceptance check 1: is 'gpl' in the enabled-features list? (expect NO) ==="
feats=$(grep -m1 'List of enabled features' "$O/mpv-lgpl-version.txt" | sed 's/.*features: //')
for t in gpl; do
  if printf '%s' "$feats" | tr ' ' '\n' | grep -qx "$t"; then echo "  '$t' PRESENT  <-- FAIL"; else echo "  '$t' absent   <-- PASS"; fi
done

echo
echo "=== acceptance: the capabilities MediaHub actually needs ==="
for t in d3d-hwaccel d3d11 wasapi libass libplacebo vulkan shaderc spirv-cross ffmpeg win32 win32-desktop; do
  if printf '%s' "$feats" | tr ' ' '\n' | grep -qx "$t"; then echo "  present  $t"; else echo "  ABSENT   $t"; fi
done

echo
echo "=== acceptance: GPL-only features that must be gone ==="
for t in direct3d dvda dvdnav javascript lua; do
  if printf '%s' "$feats" | tr ' ' '\n' | grep -qx "$t"; then echo "  present  $t"; else echo "  absent   $t"; fi
done

echo
echo "=== acceptance check 2: GPL descriptors inside libmpv-2.dll (expect 0 for each) ==="
for s in libx264 libx265 libdavs2 librubberband libdvdnav libdvdread; do
  printf '  %-14s %s\n' "$s" "$(grep -c -a "$s" "$MP/libmpv-2.dll" || true)"
done

echo
echo "=== the meson command line embedded in the new DLL (mpv-configuration) ==="
grep -a -o -m1 -- '-Dgpl=[a-z]*' "$MP/libmpv-2.dll" | head -3
grep -a -o -m1 -- '--disable-gpl' "$MP/libmpv-2.dll" || echo "  (no ffmpeg configure line inside mpv's own DLL, as expected)"

echo
echo "=== hashes ==="
sha256sum "$MP/libmpv-2.dll" "$MP/mpv.exe"

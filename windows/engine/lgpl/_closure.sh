#!/usr/bin/env bash
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
MP=/d/mh-servers/lgpl-build/prefix-mpv/bin
FFP=/d/mh-servers/lgpl-build/prefix/bin
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
export PATH="$MP:$FFP:$PATH"

echo "=== full ldd of the new libmpv-2.dll: every non-Windows entry ==="
ldd "$MP/libmpv-2.dll" 2>&1 | grep -viE 'c:/windows|/c/windows' | sed 's/^/  /' | tee "$O/dist/runtime-dependencies-full.txt"

echo
echo "=== are the GPL-questionable packages actually loaded? ==="
for lib in jbigkit libtiff libjpeg libwebp giflib lerc libdeflate iconv lcms2; do
  hit=$(ldd "$MP/libmpv-2.dll" 2>&1 | grep -ci "$lib" || true)
  printf '  %-10s referenced=%s\n' "$lib" "$hit"
done

#!/usr/bin/env bash
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
L=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
cd "$L"
/ucrt64/bin/gcc.exe _diag-load.c -o _diag-load.exe
echo "=== load test (corrected names) with the gcc-14 runtime DLLs ==="
./_diag-load.exe \
  /ucrt64/bin/libplacebo-360.dll \
  /ucrt64/bin/libass-9.dll \
  /ucrt64/bin/glslang.dll \
  /ucrt64/bin/libshaderc_shared.dll \
  /ucrt64/bin/libspirv-cross-c-shared.dll \
  /ucrt64/bin/vulkan-1.dll \
  /ucrt64/bin/libfreetype-6.dll \
  /ucrt64/bin/libharfbuzz-0.dll \
  /ucrt64/bin/libfribidi-0.dll \
  /ucrt64/bin/libdovi.dll \
  /ucrt64/bin/liblcms2-2.dll 2>&1 | sed 's/^/  /'
echo "exit=$?"

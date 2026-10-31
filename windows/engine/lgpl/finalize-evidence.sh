#!/usr/bin/env bash
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
MP=/d/mh-servers/lgpl-build/prefix-mpv/bin
FFP=/d/mh-servers/lgpl-build/prefix/bin
LOG=/c/msys64/tmp/mediahub-mpvprobe.log

echo "=== WASAPI / audio path in OUR engine's log ==="
grep -a -E "Trying audio driver|Selected decoder: aac|ao/wasapi|AO:|Using audio|audio device" "$LOG" 2>/dev/null | head -10 | tee "$O/probe-lgpl-audio.txt"

echo
echo "=== D3D11 / libplacebo render path in OUR engine's log ==="
grep -a -E "vo/gpu-next/d3d11|libplacebo|Direct3D 11 feature level|Device Name|Using GPU|spirv-cross|shaderc" "$LOG" 2>/dev/null | head -12 | tee "$O/probe-lgpl-render.txt"

echo
echo "=== hardware decode in OUR engine's log ==="
grep -a -iE "d3d11va|Using hardware decoding|hwdec" "$LOG" 2>/dev/null | head -8 | tee "$O/probe-lgpl-hwdec.txt"

echo
echo "=== stage the deliverable DLL under windows/engine/lgpl/dist ==="
mkdir -p "$O/dist"
cp -f "$MP/libmpv-2.dll" "$O/dist/libmpv-2.dll"
cp -f "$MP/mpv.exe"      "$O/dist/mpv.exe"
sha256sum "$O/dist/libmpv-2.dll" "$O/dist/mpv.exe" | tee "$O/dist/dist-hashes.txt"
ls -la "$O/dist/"

echo
echo "=== full runtime dependency closure of the new libmpv-2.dll ==="
ldd "$MP/libmpv-2.dll" 2>/dev/null | grep -vE 'ntdll|KERNEL32|KERNELBASE|ucrtbase|SHELL32|msvcp_win|USER32|win32u|GDI32|gdi32full|ole32|combase|RPCRT4|CRYPT32|OLEAUT32|WS2_32|SHLWAPI|bcrypt|sechost|Secur32|ncrypt|ADVAPI32|api-ms|USERENV|IMM32|MSCTF|dwmapi|dxgi|d3d11|SETUPAPI|CFGMGRY|POWRPROF|VERSION|WINMM|AVRT|MF|propsys|IPHLPAPI|dnsapi|normaliz|wldap32|netapi32|bdapi' | sed 's/^/  /' | tee "$O/dist/runtime-dependencies.txt"
echo "  (system DLLs filtered out; the lines above are the redistributable set)"
echo
echo "=== baseline GPL engine must be untouched ==="
sha256sum /d/deepseek_test/MediaHub_Win/windows/native/libmpv/libmpv-2.dll

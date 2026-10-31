#!/usr/bin/env bash
# W5-H acceptance item 7: run the existing MpvProbe against the LGPL engine.
# The GPL baseline DLL in windows/native is NOT touched: the probe's own bin directory is copied
# to a scratch location and the new DLL is swapped in there.
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH

SRC=/d/deepseek_test/MediaHub_Win/windows/tools/MpvProbe/bin/x64/Debug/net10.0-windows/win-x64
NEW=/d/mh-servers/lgpl-build/prefix-mpv/bin
FFP=/d/mh-servers/lgpl-build/prefix/bin
RUN=/d/mh-servers/lgpl-build/probe-lgpl

echo "=== stage a probe copy with the LGPL engine ==="
rm -rf "$RUN"; mkdir -p "$RUN"
cp -r "$SRC"/. "$RUN"/
cp -f "$NEW/libmpv-2.dll" "$RUN/libmpv-2.dll"
# FFmpeg shared DLLs the new engine links against
cp -f "$FFP"/*.dll "$RUN"/ 2>/dev/null

echo "  staged engine sha256: $(sha256sum "$RUN/libmpv-2.dll" | cut -c1-24)"
echo "  baseline engine is untouched at windows/native (verified separately)"

echo
echo "=== copy every DLL libmpv-2.dll needs at run time ==="
ldd "$NEW/libmpv-2.dll" 2>/dev/null | grep -oE '/ucrt64/bin/[^ ]+' | sort -u | while read -r d; do
  cp -f "$d" "$RUN"/ 2>/dev/null && echo "  + $(basename "$d")"
done

echo
echo "=== dependency tree check ==="
cd "$RUN"
for f in libmpv-2.dll; do
  miss=$(ldd "$f" 2>/dev/null | grep -ci 'not found' || true)
  echo "  $f unresolved deps: $miss"
done

echo
echo "############ MpvProbe --info (LGPL engine) ############"
MEDIAHUB_MPV_HWDEC=d3d11va ./MpvProbe.exe --info 2>&1 | tee /d/deepseek_test/MediaHub_Win/windows/engine/lgpl/probe-lgpl-info.txt
echo "exit=${PIPESTATUS[0]}"

echo
echo "############ MpvProbe playback (LGPL engine) ############"
MEDIA=/d/deepseek_test/MediaHub_Win/.tools/media/bbb720.mp4
ls -la "$MEDIA" 2>/dev/null || MEDIA=/d/deepseek_test/MediaHub_Win/windows/tests/assets/sample-av.mp4
echo "media: $MEDIA"
./MpvProbe.exe "$MEDIA" 8 2>&1 | tee /d/deepseek_test/MediaHub_Win/windows/engine/lgpl/probe-lgpl-playback.txt
echo "exit=${PIPESTATUS[0]}"
echo
echo "=== engine log tail (mediahub-mpvprobe.log) ==="
tail -20 "$TEMP/mediahub-mpvprobe.log" 2>/dev/null || tail -20 /c/Users/55160/AppData/Local/Temp/mediahub-mpvprobe.log 2>/dev/null || echo "(no log)"

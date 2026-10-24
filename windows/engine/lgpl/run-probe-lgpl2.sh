#!/usr/bin/env bash
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
RUN=/d/mh-servers/lgpl-build/probe-lgpl
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
cd "$RUN"

echo "############ A. MpvProbe --info ############"
MEDIAHUB_MPV_HWDEC=d3d11va ./MpvProbe.exe --info 2>&1 | tee "$O/probe-lgpl-info.txt"
echo "exit=${PIPESTATUS[0]}"
echo

echo "############ B. playback, media with an audio track ############"
for m in sample_av.mp4 jelly1080.mp4; do
  MEDIA="/d/deepseek_test/MediaHub_Win/.tools/media/$m"
  [ -f "$MEDIA" ] || MEDIA="/d/deepseek_test/MediaHub_Win/windows/tests/assets/sample-av.mp4"
  echo "=== $m  ($MEDIA) ==="
  ./MpvProbe.exe "$MEDIA" 6 > "$O/probe-lgpl-playback.txt" 2>&1
  rc=$?
  echo "exit=$rc"
  grep -E '^(engine|media|vo=|RESULT|MPV_PROBE|FAIL|PASS)' "$O/probe-lgpl-playback.txt" | head -20
  grep -E 'state=Playing' "$O/probe-lgpl-playback.txt" | tail -1
  echo
  [ $rc -eq 0 ] && break
done
echo "############ C. audio path evidence from the engine log ############"
LOG=/c/Users/55160/AppData/Local/Temp/mediahub-mpvprobe.log
grep -a -iE 'ao=|wasapi|audio|Selecting audio' "$LOG" 2>/dev/null | head -12

#!/usr/bin/env bash
# W5-H evidence capture for the LGPL FFmpeg.
# acceptance item 4: the runtime configuration string must contain neither --enable-gpl nor
# --enable-version3.  acceptance item 2: no x264/x265/davs2 descriptors in the artifact.
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
P=/d/mh-servers/lgpl-build/prefix/bin
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl

echo "=== ffmpeg -version (this is avutil_configuration(), the authoritative configuration) ==="
"$P/ffmpeg.exe" -hide_banner -version | tee "$O/ffmpeg-version.txt"

echo
echo "=== GPL-flag hits in the configuration string (expect 0) ==="
grep -c -o -E -- '--enable-(gpl|version3|nonfree)' "$O/ffmpeg-version.txt" || true

echo
echo "=== ffmpeg -buildconf (raw file) ==="
"$P/ffmpeg.exe" -hide_banner -buildconf > "$O/ffmpeg-buildconf.txt" 2>&1
wc -l < "$O/ffmpeg-buildconf.txt"
cat "$O/ffmpeg-buildconf.txt" | head -8

echo
echo "=== ffmpeg -hwaccels ==="
"$P/ffmpeg.exe" -hide_banner -hwaccels | tee "$O/ffmpeg-hwaccels.txt"

echo
echo "=== GPL-only encoder/decoder presence, tested properly ==="
ENC=$("$P/ffmpeg.exe" -hide_banner -encoders 2>/dev/null)
DEC=$("$P/ffmpeg.exe" -hide_banner -decoders 2>/dev/null)
for c in libx264 libx265 libxvid libdavs2; do
  e=$(printf '%s' "$ENC" | grep -c " $c " || true)
  d=$(printf '%s' "$DEC" | grep -c " $c " || true)
  printf '  %-9s encoder=%s decoder=%s\n' "$c" "$e" "$d"
done
echo "  total encoders: $(printf '%s' "$ENC" | grep -cE '^ [A-Z.]{6} ' || true)"
echo
echo "=== native LGPL decoders we depend on ==="
for c in h264 hevc av1 vp9; do
  printf '  %-6s decoder=%s\n' "$c" "$(printf '%s' "$DEC" | grep -cE "^ [A-Z.]{6} $c " || true)"
done

echo
echo "=== artifact hashes ==="
sha256sum "$P/ffmpeg.exe" "$P/ffprobe.exe" "$P"/av*.dll "$P"/sw*.dll | tee "$O/ffmpeg-artifact-hashes.txt"

echo
echo "=== GPL component descriptor scan inside the shipped DLLs (expect no hits) ==="
for dll in "$P"/av*.dll; do
  hits=$(grep -c -a -E 'libx264|libx265|libdavs2|librubberband|libdvdnav|libdvdread' "$dll" || true)
  printf '  %-22s hits=%s\n' "$(basename "$dll")" "$hits"
done

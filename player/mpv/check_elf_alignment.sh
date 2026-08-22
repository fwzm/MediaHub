#!/usr/bin/env bash
# 16KB page-size ELF alignment check（arm64-v8a）
# 用法: check_elf_alignment.sh <dir-with-so-files>
set -euo pipefail

DIR="${1:?用法: check_elf_alignment.sh <dir>}"
fail=0

for so in "$DIR"/*.so; do
  [ -f "$so" ] || continue
  min=0
  while read -r align; do
    dec=$((align))
    if [ "$min" -eq 0 ] || [ "$dec" -lt "$min" ]; then min=$dec; fi
  done < <(readelf -lW "$so" 2>/dev/null | awk '/LOAD/{print $NF}')
  if [ "${min:-0}" -lt 16384 ]; then
    echo "16KB-FAIL: $so (min LOAD align=$min)"
    fail=1
  else
    echo "16KB-OK: $so (align=$min)"
  fi
done

exit $fail

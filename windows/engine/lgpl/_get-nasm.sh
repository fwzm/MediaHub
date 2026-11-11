#!/usr/bin/env bash
set -euo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
CACHE=/d/mh-servers/lgpl-build/pkgs
mkdir -p "$CACHE"
cd "$CACHE"
for pkg in $(pacman -Sp --needed mingw-w64-ucrt-x86_64-nasm 2>/dev/null); do
  name=$(basename "$pkg")
  [ -f "$name" ] || curl -sS -L --retry 10 --retry-all-errors -o "$name" "$pkg"
  echo "GOT $name $(stat -c %s "$name")"
  # extract straight into the MSYS2 UCRT64 root
  tar --zstd -xf "$name" -C / --warning=no-unknown-keyword ucrt64/
done
echo "=== nasm version ==="
/ucrt64/bin/nasm.exe -v

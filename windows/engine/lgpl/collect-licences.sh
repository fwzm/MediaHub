#!/usr/bin/env bash
# Licence of every redistributable component, as declared by the packager (MSYS2 pacman metadata).
#
# Source of truth: `pacman -Si <pkg> -> Licenses` for every package in PKGS, queried in one call.
# A package whose repo entry no longer exists (renamed or dropped from the mirror) falls back to
# the installed package's own metadata (`pacman -Qi <pkg>`), which is still the packager's
# declaration. A package that yields no licence from either query is named on stderr and the run
# exits non-zero: the table must never silently drop a component again. libwinpthread-git was
# dropped exactly that way on 2026-10-14 (its `pacman -Si` already returned nothing), which is
# why that collection was short of five of the 33 shipped DLLs.
#
# Normalisation: the current metadata reports the field as `Licenses` and prefixes SPDX values
# with an `spdx:` namespace marker. That marker is stripped so expressions stay comparable with
# the 2026-10-14 collection; everything else is verbatim, including scope prefixes such as
# libjpeg-turbo's `custom:BSD-like`.
set -uo pipefail
export PATH=/ucrt64/bin:/usr/bin:$PATH
O=/d/deepseek_test/MediaHub_Win/windows/engine/lgpl
PKGS="mingw-w64-ucrt-x86_64-libass mingw-w64-ucrt-x86_64-libplacebo mingw-w64-ucrt-x86_64-shaderc
mingw-w64-ucrt-x86_64-spirv-cross mingw-w64-ucrt-x86_64-vulkan-loader mingw-w64-ucrt-x86_64-glslang
mingw-w64-ucrt-x86_64-freetype mingw-w64-ucrt-x86_64-harfbuzz mingw-w64-ucrt-x86_64-fribidi
mingw-w64-ucrt-x86_64-fontconfig mingw-w64-ucrt-x86_64-lcms2 mingw-w64-ucrt-x86_64-libdovi
mingw-w64-ucrt-x86_64-libunibreak mingw-w64-ucrt-x86_64-glib2 mingw-w64-ucrt-x86_64-libpng
mingw-w64-ucrt-x86_64-brotli mingw-w64-ucrt-x86_64-zlib mingw-w64-ucrt-x86_64-libiconv
mingw-w64-ucrt-x86_64-graphite2 mingw-w64-ucrt-x86_64-libgcc mingw-w64-ucrt-x86_64-libstdc++
mingw-w64-ucrt-x86_64-libwinpthread-git mingw-w64-ucrt-x86_64-libjpeg-turbo
mingw-w64-ucrt-x86_64-libtiff mingw-w64-ucrt-x86_64-libwebp mingw-w64-ucrt-x86_64-giflib
mingw-w64-ucrt-x86_64-lerc mingw-w64-ucrt-x86_64-libdeflate mingw-w64-ucrt-x86_64-jbigkit
mingw-w64-ucrt-x86_64-bzip2 mingw-w64-ucrt-x86_64-expat
mingw-w64-ucrt-x86_64-gettext-runtime mingw-w64-ucrt-x86_64-pcre2"

parse() {
  awk '
    /^Name[[:space:]]*:/      { name=$0; sub(/^[^:]*:[[:space:]]*/,"",name); ver=""; lic="" }
    /^Version[[:space:]]*:/   { ver=$0;  sub(/^[^:]*:[[:space:]]*/,"",ver) }
    /^Licenses?[[:space:]]*:/ { lic=$0;  sub(/^[^:]*:[[:space:]]*/,"",lic) }
    /^[[:space:]]*$/          { if (name!="") print name "\t" ver "\t" lic; name="" }
    END                       { if (name!="") print name "\t" ver "\t" lic }
  '
}

WORK=$(mktemp -d)
pacman -Si $PKGS 2>/dev/null | parse > "$WORK/si.tsv"

lookup() { awk -F "\t" -v n="$1" '$1==n { print; found=1 } END { exit found?0:1 }' "$2"; }

missing=""
{
  echo "# Licence of each redistributable component, as declared by the MSYS2 packager"
  echo "# (pacman -Si <pkg> -> 'Licenses'), collected $(date -Is)"
  echo "# Packages with no repo entry fall back to the installed package metadata (pacman -Qi <pkg>)."
  echo "# 'spdx:' namespace prefixes are stripped; everything else is verbatim. One row per package."
  echo "# FFmpeg itself is not here: it is built from source by build-ffmpeg-lgpl.sh and reports"
  echo "# 'License: LGPL version 2.1 or later' from its own configure output."
  printf '%-46s %s\n' "PACKAGE" "LICENSE"
  for p in $PKGS; do
    row=$(lookup "$p" "$WORK/si.tsv" || true)
    if [ -z "$row" ]; then
      row=$(pacman -Qi "$p" 2>/dev/null | parse | awk -F "\t" -v n="$p" '$1==n' || true)
    fi
    lic=$(printf "%s" "$row" | cut -f3)
    ver=$(printf "%s" "$row" | cut -f2)
    lic=${lic//spdx:/}
    if [ -z "$lic" ]; then
      echo "NO LICENCE: $p yielded nothing from pacman -Si or -Qi" >&2
      missing="$missing $p"
      continue
    fi
    printf '%-46s %s  (%s)\n' "${p#mingw-w64-ucrt-x86_64-}" "$lic" "$ver"
  done
} | tee "$O/component-licences.txt"
rm -rf "$WORK"
echo
echo "=== any GPL-family declarations? ==="
grep -iE "GPL" "$O/component-licences.txt" | grep -viE "LGPL|exception" || echo "  (none: every GPL mention is LGPL or a GCC exception)"
if [ -n "$missing" ]; then
  echo "component-licences.txt is INCOMPLETE:$missing" >&2
  exit 1
fi

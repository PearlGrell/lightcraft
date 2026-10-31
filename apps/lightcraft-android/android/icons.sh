#!/usr/bin/env bash
# Render the Android launcher icon, splash icon and brand mark from assets/app-icon/lightcraft.svg.
#
# Needs resvg (cargo install resvg) and python3. The outputs are committed, so building never needs them.
#
#   apps/lightcraft-android/android/icons.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
SVG="$ROOT/assets/app-icon/lightcraft.svg"
RES="$HERE/app/src/main/res"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
command -v resvg >/dev/null || { echo "error: resvg not found (cargo install resvg)" >&2; exit 1; }

python3 - "$SVG" "$TMP" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
out = sys.argv[2]
noclip = s.replace(' clip-path="url(#t)"', '')
assert noclip != s, "unexpected clip in the app icon"
# adaptive foreground: no amber field or rounded clip; the art spans 84 of the 108 dp canvas
fg = re.sub(r'<rect width="512" height="512" fill="#f2a516"/>', '', noclip).replace('viewBox="0 0 512 512"', 'viewBox="-73 -73 658 658"')
open(f"{out}/fg.svg", "w").write(fg)
# themed (monochrome) icon: the ink line work alone
mono = re.sub(r'<path fill="#efe9dc"[^>]*/>', '', fg)
assert mono != fg, "no paper path in the app icon"
open(f"{out}/mono.svg", "w").write(mono)
# splash: the rounded tile at 150 of the 240 dp splash icon
open(f"{out}/splash.svg", "w").write(s.replace('viewBox="0 0 512 512"', 'viewBox="-154 -154 820 820"'))
PY

render() { resvg -w "$2" -h "$2" "$1" "$3" </dev/null; }
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  mkdir -p "$RES/mipmap-${d%%:*}"
  render "$TMP/fg.svg" "${d##*:}" "$RES/mipmap-${d%%:*}/ic_launcher_foreground.png"
  render "$TMP/mono.svg" "${d##*:}" "$RES/mipmap-${d%%:*}/ic_launcher_monochrome.png"
done
mkdir -p "$RES/drawable-xhdpi" "$RES/drawable-xxxhdpi"
render "$TMP/splash.svg" 480 "$RES/drawable-xhdpi/ic_splash.png"
render "$TMP/splash.svg" 960 "$RES/drawable-xxxhdpi/ic_splash.png"
render "$SVG" 64 "$RES/drawable-xhdpi/ic_brand_mark.png"
render "$SVG" 128 "$RES/drawable-xxxhdpi/ic_brand_mark.png"

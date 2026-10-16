#!/usr/bin/env bash
# Install the APK on the connected device or emulator, open the library and the editor, and save
# screenshots plus the app's log into $2. Fails when the app crashes.
set -euo pipefail
apk=$1
out=${2:-screenshots}
pkg=ai.storyteller.lightcraft.android
mkdir -p "$out"
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity"
sleep 20 # first start seeds the demo library
adb exec-out screencap -p > "$out/1-library.png"
# open the first photo of the grid (top-left cell), then the Light panel from the tool rail
read -r w h < <(adb shell wm size | sed -E 's/.* ([0-9]+)x([0-9]+).*/\1 \2/')
adb shell input tap $((w / 6)) $((h / 4))
sleep 15
adb exec-out screencap -p > "$out/2-editor.png"
# the Light tool: second item of the rail (64 dp items after 8 dp padding), above the gesture bar
dpi=$(adb shell wm density | sed -E 's/.* ([0-9]+)$/\1/' | tail -1)
dp() { echo $(($1 * dpi / 160)); }
adb shell input tap "$(dp 108)" $((h - $(dp 80)))
sleep 5
adb exec-out screencap -p > "$out/3-light.png"
adb logcat -d > "$out/logcat.txt"
if ! adb shell pidof "$pkg" > /dev/null; then
  echo "LightCraft is not running any more: see $out/logcat.txt" >&2
  grep -E "FATAL|AndroidRuntime|lightcraft" "$out/logcat.txt" | tail -40 >&2 || true
  exit 1
fi

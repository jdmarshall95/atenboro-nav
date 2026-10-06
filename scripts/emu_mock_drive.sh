#!/usr/bin/env bash
# Drive Atenboro against host mock_board from emulator (no SoftAP / no LTE fight).
#
# Prerequisites:
#   - AVD running (e.g. Pixel_7_virtual)
#   - ./scripts/mock_board.py already listening on :8765
#   - debug APK installed
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=com.atenboro.nav
MOCK_URL="${MOCK_URL:-http://10.0.2.2:18765}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

"$ADB" wait-for-device
"$ADB" shell setprop debug.atenboro.esp_url "$MOCK_URL"
log "setprop debug.atenboro.esp_url=$MOCK_URL"

# Force-stop so EspClient picks up URL on next process start
"$ADB" shell am force-stop "$PKG" || true
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 1

inject() {
  local turn="$1" dist="$2" camera="${3:-false}" cam_kmh="${4:--1}"
  local args=(
    -n "$PKG/.service.InjectNavReceiver"
    -a "$PKG.INJECT_NAV"
    --es turn "$turn"
    --ei dist_m "$dist"
  )
  [[ "$camera" == "true" ]] && args+=(--ez camera true)
  [[ "$cam_kmh" != "-1" ]] && args+=(--ei cam_kmh "$cam_kmh")
  "$ADB" shell am broadcast "${args[@]}" >/dev/null
  log "inject $turn $dist cam=$camera/$cam_kmh"
}

log "scripted hud against mock board"
inject slight_right 180 true 60
sleep 1
inject u_turn 80 false -1
sleep 1
inject left 400 false -1
sleep 1
inject straight 3800 false -1
sleep 1
inject right 12000 false -1
sleep 1
inject arrive 0 false -1

log "curl mock /debug from host"
curl -sS "${MOCK_URL/10.0.2.2/127.0.0.1}/debug" | head -c 800 || curl -sS "http://127.0.0.1:18765/debug" | head -c 800
echo
log "done — check mock_board console + app OLED preview"

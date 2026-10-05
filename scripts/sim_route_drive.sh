#!/usr/bin/env bash
# Realistic drive simulation for Atenboro Nav.
# Modes:
#   hud   — inject nav frames along a scripted route (phone → SoftAP → OLED)
#   geo   — step mock GPS along a polyline so 2GIS recalculates (needs mock-location app)
#   full  — geo + live session/OLED monitoring
#
# Usage:
#   ./scripts/sim_route_drive.sh hud
#   ./scripts/sim_route_drive.sh geo
#   ./scripts/sim_route_drive.sh full
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
MODE="${1:-full}"
OUT_DIR="${OUT_DIR:-/tmp/atenboro-drive-$(date +%Y%m%d-%H%M%S)}"
PKG=com.atenboro.nav
GIS=ru.dublgis.dgismobile
MOCK_PKG="${MOCK_PKG:-com.lexa.fakegps}"

mkdir -p "$OUT_DIR"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT_DIR/run.log"; }

need_device() {
  "$ADB" wait-for-device
  "$ADB" devices -l | tee "$OUT_DIR/devices.txt"
}

inject() {
  local turn="$1" dist="$2" street="${3:-}" camera="${4:-false}" cam_kmh="${5:--1}"
  local args=(
    -n "$PKG/.service.InjectNavReceiver"
    -a "$PKG.INJECT_NAV"
    --es turn "$turn"
    --ei dist_m "$dist"
  )
  [[ -n "$street" ]] && args+=(--es street "$street")
  [[ "$camera" == "true" ]] && args+=(--ez camera true)
  [[ "$cam_kmh" != "-1" ]] && args+=(--ei cam_kmh "$cam_kmh")
  "$ADB" shell am broadcast "${args[@]}" >/dev/null
  log "inject turn=$turn d=$dist street=$street cam=$camera/$cam_kmh"
}

# --- Route: approach Bolshoy Strochenovskiy → right → straight → slight left → arrive ---
# Distances tick down as if driving ~30–40 km/h in city.
hud_drive() {
  log "HUD scripted drive begin"
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null || true
  sleep 1

  # Approach (straight, long)
  for d in 1200 900 700 550 400; do
    inject straight "$d" "Zatsepskiy Val"
    sleep 2
  done

  # Camera banner while still straight
  inject straight 320 "Zatsepskiy Val" true 60
  sleep 2
  inject straight 250 "Zatsepskiy Val" true 60
  sleep 2

  # Prepare right onto Bolshoy Strochenovskiy (matches yesterday pocket banner)
  for d in 400 320 240 160 100 60 40; do
    inject right "$d" "Bolshoy Strochenovskiy"
    sleep 1.5
  done

  # After turn — straight along the lane
  for d in 500 380 260 180 120; do
    inject straight "$d" "Bolshoy Strochenovskiy"
    sleep 2
  done

  # Slight left / next street
  for d in 200 140 90 50; do
    inject slight_left "$d" "Novokuznetskaya"
    sleep 1.5
  done

  inject left 35 "Novokuznetskaya"
  sleep 2
  inject arrive 0 "Destination"
  sleep 2
  log "HUD scripted drive done"
}

# Polyline near Paveletskaya / Bolshoy Strochenovskiy (lat,lon), ~city pace
# Steps move ~25–40 m each tick.
GEO_POINTS=(
  "55.73180,37.63980"  # approach Zatsepskiy
  "55.73140,37.63920"
  "55.73100,37.63860"
  "55.73060,37.63810"
  "55.73020,37.63760"
  "55.72985,37.63720"  # near right turn
  "55.72960,37.63690"
  "55.72940,37.63650"
  "55.72925,37.63610"  # turn pocket
  "55.72915,37.63570"
  "55.72910,37.63520"  # after right
  "55.72905,37.63470"
  "55.72900,37.63420"
  "55.72890,37.63370"
  "55.72870,37.63330"
  "55.72845,37.63300"  # toward next turn
  "55.72820,37.63280"
  "55.72795,37.63270"
  "55.72770,37.63260"
  "55.72745,37.63255"
)

set_mock_location() {
  local lat="$1" lon="$2"
  if "$ADB" shell cmd location providers set-test-provider-location atenboro \
      --location "$lat,$lon" --accuracy 5 >/dev/null 2>&1; then
    return 0
  fi
  "$ADB" shell am start -a android.intent.action.VIEW -d "geo:$lat,$lon" >/dev/null 2>&1 || true
}

enable_mock() {
  "$ADB" shell cmd location set-location-enabled true >/dev/null 2>&1 || true
  "$ADB" shell appops set com.android.shell android:mock_location allow >/dev/null 2>&1 || true
  "$ADB" shell cmd location providers remove-test-provider atenboro >/dev/null 2>&1 || true
  "$ADB" shell cmd location providers add-test-provider atenboro \
    --supportsSpeed --supportsBearing --supportsAltitude >/dev/null 2>&1 || true
  "$ADB" shell cmd location providers set-test-provider-enabled atenboro true >/dev/null 2>&1 || true
  log "mock provider 'atenboro' enabled (cmd location)"
}

geo_drive() {
  log "GEO polyline drive begin (${#GEO_POINTS[@]} points)"
  enable_mock
  "$ADB" shell am start -n "$GIS/.GrymMobileActivity" >/dev/null || \
    "$ADB" shell monkey -p "$GIS" -c android.intent.category.LAUNCHER 1 >/dev/null || true
  sleep 2
  local i=0
  for pt in "${GEO_POINTS[@]}"; do
    i=$((i + 1))
    local lat="${pt%,*}" lon="${pt#*,}"
    set_mock_location "$lat" "$lon"
    log "geo [$i/${#GEO_POINTS[@]}] $lat,$lon"
    sleep 3
  done
  log "GEO polyline drive done"
}

pull_session() {
  log "pull session + board dumps"
  "$ADB" shell "run-as $PKG sh -c 'ls -lt files/debug | head -30'" | tee "$OUT_DIR/debug-ls.txt"
  local day
  day=$("$ADB" shell date +%Y%m%d | tr -d '\r')
  for f in "session-$day.ndjson" "session-20260929.ndjson" "session-20260930.ndjson"; do
    "$ADB" shell "run-as $PKG cat files/debug/$f" >"$OUT_DIR/$f" 2>/dev/null || true
  done
  "$ADB" shell "run-as $PKG sh -c 'ls files/debug/board-*.json 2>/dev/null | tail -3'" \
    | tr -d '\r' | while read -r bf; do
      [[ -z "$bf" ]] && continue
      base=$(basename "$bf")
      "$ADB" shell "run-as $PKG cat $bf" >"$OUT_DIR/$base" 2>/dev/null || true
    done
  # public dumps
  "$ADB" shell "ls -lt /sdcard/Android/data/$PKG/files/dumps 2>/dev/null | head -20" \
    | tee "$OUT_DIR/dumps-ls.txt" || true
}

monitor_snip() {
  "$ADB" logcat -d -t 200 -s AtenboroNotif:* AtenboroNav:* AtenboroNavFeed:* 2>/dev/null \
    | rg -i 'turn=|icon candidates|sent |send fail|deferred|inject' \
    | tee "$OUT_DIR/logcat-snip.txt" || true
  "$ADB" shell "run-as $PKG sh -c 'ls -t files/debug/session-*.ndjson | head -1 | xargs tail -n 40'" \
    | tee "$OUT_DIR/session-tail.txt" || true
}

analyze_stutters() {
  local f
  f=$(ls "$OUT_DIR"/session-*.ndjson 2>/dev/null | head -1 || true)
  [[ -z "$f" || ! -s "$f" ]] && { log "no session file to analyze"; return 0; }
  python3 - <<PY | tee "$OUT_DIR/stutter-report.txt"
from pathlib import Path
import json, re
p = Path("$f")
rows = []
for line in p.read_text(errors="replace").splitlines():
    line=line.strip()
    if not line: continue
    try:
        o=json.loads(line)
    except Exception:
        # DebugStore may write compact JSON without outer keys sometimes
        m=re.search(r'"msg"\s*:\s*"(.*?)"', line)
        t=re.search(r'"t"\s*:\s*(\d+)', line)
        if not m: continue
        o={"t": int(t.group(1)) if t else 0, "msg": m.group(1).encode('utf-8').decode('unicode_escape'), "lvl":"?"}
    rows.append(o)

print(f"events={len(rows)} file={p.name}")
fails=[r for r in rows if 'send fail' in r.get('msg','') or r.get('lvl')=='e']
deferred=[r for r in rows if 'deferred' in r.get('msg','')]
turns=[]
for r in rows:
    m=re.search(r'turn=([a-z_]+)\s+d=(-?\d+)', r.get('msg',''))
    if m:
        turns.append((r.get('t',0), m.group(1), int(m.group(2)), r.get('msg','')[:120]))

print(f"send_fail/errors={len(fails)} deferred={len(deferred)} turn_events={len(turns)}")
# flip-flops: consecutive different turns within 2s
flips=0
for a,b in zip(turns, turns[1:]):
    if a[1]!=b[1] and abs(b[0]-a[0]) < 2000 and a[1]!='none' and b[1]!='none':
        flips+=1
        if flips<=12:
            print(f"FLIP {a[1]}/{a[2]} -> {b[1]}/{b[2]} dt={b[0]-a[0]}ms")
print(f"rapid_turn_flips(<2s)={flips}")
print("--- last 15 turn samples ---")
for t,turn,d,msg in turns[-15:]:
    print(f"{t} {turn} {d}m | {msg}")
print("--- last 10 errors ---")
for r in fails[-10:]:
    print(r.get('t'), r.get('msg','')[:160])
PY
}

ensure_services() {
  log "ensure Atenboro + listeners"
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null || true
  sleep 1
  local nls a11y
  nls=$("$ADB" shell settings get secure enabled_notification_listeners | tr -d '\r')
  a11y=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
  echo "NLS=$nls" | tee -a "$OUT_DIR/run.log"
  echo "A11Y=$a11y" | tee -a "$OUT_DIR/run.log"
  echo "$nls" | rg -q "$PKG" || log "WARN: notification listener disabled — enable in settings"
  echo "$a11y" | rg -q "$PKG" || log "WARN: accessibility disabled — enable in settings"
}

main() {
  need_device
  ensure_services
  pull_session
  analyze_stutters || true
  case "$MODE" in
    hud) hud_drive ;;
    geo) geo_drive ;;
    full)
      # Prefer live 2GIS via geo; also run HUD inject if SoftAP works for OLED baseline
      geo_drive
      monitor_snip
      log "optional HUD overlay for OLED if SoftAP bound"
      hud_drive
      ;;
    *)
      echo "usage: $0 [hud|geo|full]" >&2
      exit 2
      ;;
  esac
  monitor_snip
  pull_session
  analyze_stutters || true
  log "artifacts → $OUT_DIR"
  ls -la "$OUT_DIR"
}

main

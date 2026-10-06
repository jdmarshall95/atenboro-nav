#!/usr/bin/env bash
# Long synthetic pocket route while display stays Asleep/Dozing.
# Hard-fails on any Awake / interactive=true. Leaves screen locked at end
# (set UNLOCK_AFTER=1 to wake for interactive use).
set -uo pipefail
ADB=${ADB:-adb}
PKG=com.atenboro.nav
ACT=$PKG/.MainActivity
RCV=$PKG/.service.InjectNavReceiver
OUT=${OUT:-/tmp/atenboro-nav-test/locked-long}
MOCK=${MOCK:-http://127.0.0.1:18765}
ESP_URL=${ESP_URL:-http://10.0.2.2:18765}
UNLOCK_AFTER=${UNLOCK_AFTER:-0}
mkdir -p "$OUT"
REPORT="$OUT/REPORT.md"
: > "$REPORT"

wakefulness() {
  $ADB shell dumpsys power 2>/dev/null | tr -d '\r' | awk -F= '/mWakefulness=/{print $2; exit}'
}

is_asleep() {
  case "$(wakefulness)" in
    Asleep|Dozing|Dreaming) return 0 ;;
    *) return 1 ;;
  esac
}

disable_stay_on() {
  $ADB shell settings put global stay_on_while_plugged_in 0 >/dev/null
  $ADB shell svc power stayon false >/dev/null 2>&1 || true
}

force_sleep() {
  disable_stay_on
  $ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
  sleep 0.9
  if is_asleep; then return 0; fi
  $ADB shell input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
  sleep 1.0
  is_asleep
}

ensure_asleep() {
  disable_stay_on
  local w
  w=$(wakefulness)
  if [ "$w" = "Asleep" ] || [ "$w" = "Dozing" ] || [ "$w" = "Dreaming" ]; then
    echo "$w"
    return 0
  fi
  force_sleep || true
  w=$(wakefulness)
  echo "$w"
  case "$w" in
    Asleep|Dozing|Dreaming) return 0 ;;
    *) return 1 ;;
  esac
}

wake_unlock() {
  $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  $ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true
  sleep 0.4
}

inject() {
  local turn="$1" dist="$2" cam="$3" camk="$4" street="$5"
  local ARGS=(-a com.atenboro.nav.INJECT_NAV -n "$RCV"
    --es turn "$turn" --ei dist_m "$dist" --es street "$street")
  if [ "$cam" = "1" ]; then
    ARGS+=(--ez camera true --ei cam_m 80 --ei cam_kmh "$camk")
  else
    ARGS+=(--ez camera false --ei cam_m -1 --ei cam_kmh -1)
  fi
  $ADB shell am broadcast "${ARGS[@]}" >/dev/null
  sleep 1.5
}

mock_nav() {
  curl -sS "$MOCK/debug" 2>/dev/null | python3 -c '
import sys, json
d = json.load(sys.stdin)
n = d.get("nav") or {}
print("%s/%s cam=%s/%s" % (n.get("turn"), n.get("dist_m"), n.get("camera"), n.get("cam_kmh")))
' 2>/dev/null || echo "mock-unavailable"
}

last_interactive() {
  $ADB shell "run-as $PKG sh -c 'grep locked-inject.begin files/debug/session-*.ndjson | tail -1'" \
    | tr -d '\r' | grep -oE 'interactive=(true|false)' | tail -1 || echo "interactive=?"
}

echo "# Synthetic locked long-route test" | tee -a "$REPORT"
echo "" >> "$REPORT"
echo "- Path: InjectNavReceiver → mock SoftAP ($ESP_URL)" | tee -a "$REPORT"
echo "- Hard requirement: mWakefulness Asleep/Dozing + interactive=false on every step" | tee -a "$REPORT"
echo "" >> "$REPORT"

PREV_STAY=$($ADB shell settings get global stay_on_while_plugged_in | tr -d '\r')
PREV_TIMEOUT=$($ADB shell settings get system screen_off_timeout | tr -d '\r')

# --- prep (briefly awake) ---
wake_unlock
$ADB shell settings put global stay_on_while_plugged_in 7 >/dev/null
$ADB shell setprop debug.atenboro.esp_url "$ESP_URL"
$ADB shell am force-stop ru.dublgis.dgismobile >/dev/null 2>&1 || true
$ADB shell am start -n "$ACT" >/dev/null
sleep 1.2
# start FGS proxy (UI tap fallback + explicit service)
for _ in 1 2; do $ADB shell input swipe 540 1800 540 600 200 >/dev/null; sleep 0.2; done
$ADB shell input tap 540 1436 >/dev/null 2>&1 || true
$ADB shell am start-foreground-service -n $PKG/.service.NavProxyService >/dev/null 2>&1 \
  || $ADB shell am startservice -n $PKG/.service.NavProxyService >/dev/null 2>&1 || true
sleep 0.6
curl -sS -X DELETE "$MOCK/debug" >/dev/null || true

# --- lock and keep locked ---
disable_stay_on
$ADB shell settings put system screen_off_timeout 15000 >/dev/null
if ! force_sleep; then
  echo "ABORT: cannot put display to sleep (wakefulness=$(wakefulness))" | tee -a "$REPORT"
  exit 2
fi
LOCK0=$(wakefulness)
echo "locked wakefulness=$LOCK0 stay_on=$($ADB shell settings get global stay_on_while_plugged_in | tr -d '\r')" | tee -a "$REPORT"
if [ "$LOCK0" = "Awake" ]; then
  echo "ABORT: still Awake after lock" | tee -a "$REPORT"
  exit 2
fi

# long pocket-like countdown with maneuvers + camera segment
# turn dist cam cam_kmh street
mapfile -t STEPS <<'EOF'
straight 4500 0 -1 LongA
straight 3800 0 -1 LongA
straight 2800 1 60 LongCam
straight 1900 1 60 LongCam
straight 1200 0 -1 LongB
right 800 0 -1 TurnR
right 500 0 -1 TurnR
right 320 0 -1 TurnR
right 180 0 -1 TurnR
right 90 0 -1 TurnR
right 40 0 -1 TurnR
right 18 0 -1 TurnR
straight 600 0 -1 Mid
straight 400 0 -1 Mid
slight_left 250 0 -1 SlightL
slight_left 120 0 -1 SlightL
slight_left 55 0 -1 SlightL
slight_left 22 0 -1 SlightL
left 80 0 -1 TurnL
left 35 0 -1 TurnL
left 12 0 -1 TurnL
arrive 0 0 -1 Done
EOF

PASS=0
FAIL=0
N=0
echo "" >> "$REPORT"
echo "## Results" >> "$REPORT"
echo '```' >> "$REPORT"

for line in "${STEPS[@]}"; do
  # shellcheck disable=SC2086
  set -- $line
  turn=$1; dist=$2; cam=$3; camk=$4; street=$5
  N=$((N + 1))

  before=$(ensure_asleep) || true
  stay=$($ADB shell settings get global stay_on_while_plugged_in | tr -d '\r')
  if [ "$stay" != "0" ]; then
    disable_stay_on
  fi

  inject "$turn" "$dist" "$cam" "$camk" "$street"
  after=$(wakefulness)
  inter=$(last_interactive)
  got=$(mock_nav)

  reasons=()
  case "$before" in
    Asleep|Dozing|Dreaming) ;;
    *) reasons+=("was $before before inject") ;;
  esac
  case "$after" in
    Asleep|Dozing|Dreaming) ;;
    *) reasons+=("screen woke ($after)") ;;
  esac
  if [ "$inter" != "interactive=false" ]; then
    reasons+=("expected interactive=false got $inter")
  fi
  # distance must match inject (sticky distance fix)
  if ! echo "$got" | grep -Eq "^[^/]+/${dist} "; then
    reasons+=("dist mismatch mock=$got expect=$dist")
  fi
  # turn must match
  if ! echo "$got" | grep -Eq "^${turn}/"; then
    reasons+=("turn mismatch mock=$got expect=$turn")
  fi

  if [ ${#reasons[@]} -eq 0 ]; then
    status=PASS
    PASS=$((PASS + 1))
  else
    status=FAIL
    FAIL=$((FAIL + 1))
  fi

  row="$N	$status	inj=$turn/$dist cam=$cam	mock=$got	wake=${before}→${after}	$inter	${reasons[*]}"
  echo "$row" | tee -a "$REPORT"

  # If screen woke, try to re-lock for subsequent steps but keep FAIL counted
  if ! is_asleep; then
    force_sleep || true
  fi
done

echo '```' >> "$REPORT"
echo "" | tee -a "$REPORT"
echo "**PASS=$PASS FAIL=$FAIL total=$N**" | tee -a "$REPORT"
FINAL=$(wakefulness)
echo "final wakefulness=$FINAL" | tee -a "$REPORT"

# interactive summary from device logs
echo "" >> "$REPORT"
echo "## locked-inject interactive flags (last 30)" >> "$REPORT"
$ADB shell "run-as $PKG sh -c 'grep \"locked-inject begin\" files/debug/session-*.ndjson | tail -30'" \
  | tr -d '\r' >> "$REPORT" || true

# Leave display asleep for visual confirmation unless UNLOCK_AFTER=1.
# Restoring stay_on_while_plugged_in while USB is connected would wake the screen.
if [ "$UNLOCK_AFTER" = "1" ]; then
  $ADB shell settings put global stay_on_while_plugged_in "${PREV_STAY:-7}" >/dev/null
  $ADB shell settings put system screen_off_timeout "${PREV_TIMEOUT:-2147483647}" >/dev/null
  wake_unlock
  $ADB shell svc power stayon true >/dev/null 2>&1 || true
else
  disable_stay_on
  $ADB shell settings put system screen_off_timeout 15000 >/dev/null
  force_sleep || true
  echo "left locked: wakefulness=$(wakefulness) stay_on=$($ADB shell settings get global stay_on_while_plugged_in | tr -d '\r') (UNLOCK_AFTER=0; prev_stay=$PREV_STAY)" | tee -a "$REPORT"
fi

if [ "$FAIL" -eq 0 ] && is_asleep; then
  echo "Verdict: **PASS** (all steps locked)" | tee -a "$REPORT"
  echo "Report: $REPORT"
  exit 0
fi

if [ "$FAIL" -eq 0 ] && [ "$UNLOCK_AFTER" = "1" ]; then
  echo "Verdict: **PASS** (steps locked; unlocked after by request)" | tee -a "$REPORT"
  echo "Report: $REPORT"
  exit 0
fi

echo "Verdict: **FAIL**" | tee -a "$REPORT"
echo "Report: $REPORT"
exit 1

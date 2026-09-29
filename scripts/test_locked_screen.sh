#!/usr/bin/env bash
# Locked-screen pocket scenario: SoftAP + FGS proxy + NavFeed inject while display off.
set -uo pipefail
ADB=${ADB:-/home/i-drozdov/Android/Sdk/platform-tools/adb}
PKG=com.atenboro.nav
ACT=$PKG/.MainActivity
RCV=$PKG/.service.InjectNavReceiver
OUT=${OUT:-/tmp/atenboro-nav-test/locked}
mkdir -p "$OUT"
REPORT="$OUT/REPORT.md"
: > "$REPORT"

wake() { $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true; sleep 0.3; }
unlock_swipe() {
  wake
  $ADB shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
  $ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true
  sleep 0.4
}
sleep_display() {
  # USB usually keeps screen on — disable stay-on for the test
  $ADB shell settings put global stay_on_while_plugged_in 0 >/dev/null
  $ADB shell svc power stayon false >/dev/null 2>&1 || true
  $ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
  sleep 1.2
}
wakefulness() {
  $ADB shell dumpsys power 2>/dev/null | tr -d '\r' | awk -F= '/mWakefulness=/{print $2; exit}'
}
is_interactive() {
  local w
  w=$(wakefulness)
  case "$w" in
    Awake|true) echo "interactive=$w"; return 0 ;;
    *) echo "asleep=$w"; return 1 ;;
  esac
}

broadcast_inject() {
  local turn="$1" dist="$2" cam="$3" camm="$4" camk="$5" street="$6"
  local ARGS=(-a com.atenboro.nav.INJECT_NAV -n "$RCV" --es turn "$turn" --ei dist_m "$dist")
  if [ "$cam" = "1" ]; then
    ARGS+=(--ez camera true --ei cam_m "$camm" --ei cam_kmh "$camk")
  else
    ARGS+=(--ez camera false --ei cam_m -1 --ei cam_kmh -1)
  fi
  [ -n "$street" ] && ARGS+=(--es street "$street")
  $ADB shell am broadcast "${ARGS[@]}"
  sleep 1.4
}

echo "# Locked-screen pocket test" | tee -a "$REPORT"
echo "" >> "$REPORT"

# --- prep (screen on) ---
unlock_swipe
# remember stay_on to restore
PREV_STAY=$($ADB shell settings get global stay_on_while_plugged_in | tr -d '\r')
$ADB shell am force-stop ru.dublgis.dgismobile >/dev/null 2>&1 || true

$ADB shell am start -n "$ACT" >/dev/null
sleep 1.5
# Bind SoftAP + start FGS proxy via UI buttons if needed
$ADB shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1
$ADB shell cat /sdcard/uidump.xml 2>/dev/null | tr -d '\r' > /tmp/uidump.xml
# tap connect / proxy by resource id if present
python3 - <<'PY'
import re,subprocess
xml=open('/tmp/uidump.xml').read()
def tap(needle):
  m=re.search(rf'resource-id="[^"]*{needle}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
  if not m: return False
  x1,y1,x2,y2=map(int,m.groups())
  subprocess.check_call(['/home/i-drozdov/Android/Sdk/platform-tools/adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
  return True
# try common buttons
for n in ['btnConnect','btnBind','btnStartProxy','btnProxy']:
  if tap(n):
    print('tapped', n)
PY
# Explicit FGS start
$ADB shell am start-foreground-service -n $PKG/.service.NavProxyService >/dev/null 2>&1 \
  || $ADB shell am startservice -n $PKG/.service.NavProxyService >/dev/null 2>&1 || true
sleep 1.0

# Seed bind via one Activity inject (screen still on)
$ADB shell am start -n "$ACT" --es turn straight --ei dist_m 999 --ez camera false >/dev/null
sleep 2.5
echo "prep wake: $(is_interactive || true)" | tee -a "$REPORT"
$ADB shell "run-as $PKG sh -c 'grep inject files/debug/session-*.ndjson | tail -2'" | tr -d '\r' | tee -a "$REPORT"

# --- lock ---
sleep_display
LOCK1=$(wakefulness)
echo "after sleep: mWakefulness=$LOCK1" | tee -a "$REPORT"
if [ "$LOCK1" = "Awake" ]; then
  echo "WARN: still Awake after SLEEP — retry" | tee -a "$REPORT"
  $ADB shell input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
  sleep 1.5
  LOCK1=$(wakefulness)
  echo "retry: mWakefulness=$LOCK1" | tee -a "$REPORT"
fi

PASS=0
FAIL=0

run_step() {
  local name="$1" turn="$2" dist="$3" cam="$4" camm="$5" camk="$6" street="$7" expect="$8"
  local before after meta
  before=$(wakefulness)
  echo "" | tee -a "$REPORT"
  echo "=== $name (locked=$before) ===" | tee -a "$REPORT"
  broadcast_inject "$turn" "$dist" "$cam" "$camm" "$camk" "$street"
  after=$(wakefulness)
  echo "wakefulness: $before -> $after" | tee -a "$REPORT"
  sleep 0.6
  meta=$($ADB shell "run-as $PKG sh -c 'grep \"locked-screen turn=\" files/debug/session-*.ndjson | tail -1'" | tr -d '\r')
  echo "$meta" | tee -a "$REPORT"
  local ok=1
  if ! echo "$meta" | grep -q "$expect"; then
    echo "FAIL meta expect *$expect*" | tee -a "$REPORT"
    ok=0
  fi
  if ! echo "$meta" | grep -q 'interactive=false'; then
    echo "FAIL expected interactive=false (screen should stay off)" | tee -a "$REPORT"
    ok=0
  fi
  if [ "$after" = "Awake" ]; then
    echo "FAIL screen woke (mWakefulness=Awake)" | tee -a "$REPORT"
    ok=0
  fi
  if [ "$before" = "Awake" ]; then
    echo "WARN was Awake before step (USB stay-on?)" | tee -a "$REPORT"
  fi
  if [ "$ok" = 1 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  # pull latest locked png
  local PNG
  PNG=$($ADB shell "run-as $PKG sh -c 'ls -t files/debug/oled-locked-*.png 2>/dev/null | head -1'" | tr -d '\r')
  if [ -n "$PNG" ]; then
    $ADB exec-out run-as $PKG cat "$PNG" > "$OUT/${name}.png" || true
  fi
  echo "$meta" | python3 -c 'import sys,re; s=sys.stdin.read(); m=re.search(r"turn=[^\" ]+", s); print(m.group(0) if m else s[:120])' > "$OUT/${name}.meta"
}

# Realistic pocket sequence while display off
run_step 01_left left 180 0 -1 -1 PocketL "LEFT;dist=180"
run_step 02_right_cam right 220 1 90 60 PocketR "RIGHT;dist=220;cam=1;cam_kmh=60"
run_step 03_cam_blink right 70 1 30 60 PocketCam "RIGHT;dist=70;cam=1;cam_kmh=60"
# second cam capture ~0.5s later without waking — same broadcast path
sleep 0.5
run_step 04_slight_l slight_left 140 0 -1 -1 PocketSL "SLIGHT L;dist=140"
run_step 05_uturn u_turn 60 0 -1 -1 PocketU "U-TURN;dist=60"

FINAL_LOCK=$(wakefulness)
echo "" | tee -a "$REPORT"
echo "final wakefulness=$FINAL_LOCK" | tee -a "$REPORT"
echo "PASS=$PASS FAIL=$FAIL" | tee -a "$REPORT"

# restore stay-on + wake for user
$ADB shell settings put global stay_on_while_plugged_in "${PREV_STAY:-7}" >/dev/null
unlock_swipe
$ADB shell svc power stayon true >/dev/null 2>&1 || true

# summarize interactive flags from logs
echo "" >> "$REPORT"
echo "## interactive flags during locked-screen lines" >> "$REPORT"
$ADB shell "run-as $PKG sh -c 'grep locked-screen files/debug/session-*.ndjson | tail -20'" | tr -d '\r' >> "$REPORT"

echo "Report: $REPORT"
exit $([ "$FAIL" = 0 ] && echo 0 || echo 1)

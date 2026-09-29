#!/usr/bin/env bash
# Integration smoke-test against ESP SoftAP (atenboro-nav / 192.168.4.1)
set -euo pipefail

BASE="${ESP_URL:-http://192.168.4.1}"

echo "== health =="
curl -sf "$BASE/health"
echo

echo "== left + camera =="
curl -sf -X POST "$BASE/nav" \
  -H 'Content-Type: application/json' \
  -d '{"turn":"left","dist_m":250,"camera":true,"cam_m":120,"ts":1}'
echo

sleep 2
echo "== right =="
curl -sf -X POST "$BASE/nav" \
  -H 'Content-Type: application/json' \
  -d '{"turn":"right","dist_m":80,"camera":false,"cam_m":-1,"ts":2}'
echo

sleep 2
echo "== straight km =="
curl -sf -X POST "$BASE/nav" \
  -H 'Content-Type: application/json' \
  -d '{"turn":"straight","dist_m":1500,"camera":false,"cam_m":-1,"ts":3}'
echo

sleep 2
echo "== roundabout =="
curl -sf -X POST "$BASE/nav" \
  -H 'Content-Type: application/json' \
  -d '{"turn":"roundabout","dist_m":60,"camera":true,"cam_m":40,"ts":4}'
echo

echo "OK — check OLED for turn / distance / CAM"

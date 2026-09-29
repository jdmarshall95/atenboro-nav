# Changelog

## [0.1.0] — 2026-09-29

### Added
- ESP8266 firmware for HW-364A: SoftAP, OLED HUD, splash (logo + C-130 + version)
- HTTP API: `/health`, `/nav`, `/debug`
- Android proxy: Accessibility + Notification Listener for 2GIS
- Debug sync between phone and board
- Docs: wiring, testing, curl scripts

### Notes
- 2GIS Qt UI exposes limited accessibility text; pocket mode relies on notification RemoteViews
- SoftAP has no internet — Android must bind process to the ESP Wi‑Fi network

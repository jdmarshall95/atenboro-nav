# Changelog

## [0.1.4] — 2026-09-29

### Changed
- OLED HUD redesign for dual-color panel:
  - yellow (y0–15): camera only — blink + speed km/h (empty if no camera)
  - blue (y16–63): left half = redesigned direction arrow, right half = distance + `m`
- Built-in 48×48 arrows replace 2GIS mono icons on the board

### Added
- `cam_kmh` in POST `/nav` and Android parser (limit near camera banners)

## [0.1.3] — 2026-09-29

### Fixed
- OLED white block instead of turn arrow: mono icon now uses background-relative ink; dense fills fall back to built-in glyphs
- Bottom HUD label shows street (ASCII) when available instead of LEFT/RIGHT
- Reject over-dense icon payloads on firmware
- Phone sends firmware glyph hex when 2GIS largeIcon mono is unusable (works even before board reflash)
- Prefer largest notification bitmap for turn classification
- adb inject: `am start … --es turn …` for board stage tests without fighting live 2GIS notifs

## [0.1.2] — 2026-09-29

### Fixed
- OLED Cyrillic garbage: transliterate street to Latin on phone; firmware keeps ASCII-only text

### Added
- `GET /screen` — raw SSD1306 framebuffer + `X-OLED-META` headers for debug previews
- Debug UI: button «Снимок OLED с платы» + PNG save under app files/debug
- `scripts/fetch_oled_screen.sh` for curl → PNG/ASCII from SoftAP

## [0.1.1] — 2026-09-29

### Added
- Maneuver from 2GIS notification `largeIcon` (classify + 32×32 mono to OLED)
- Sticky nav HUD while navigating; partial OLED redraws (dirty regions only)
- Accessibility screenshot crop of maneuver card; `canTakeScreenshot`
- `GisLogcatWatcher` / `GisLogParser` for DomainSynthesizer voice clips (`…Over400`)
- Street name on OLED when distance-to-maneuver is missing

### Fixed
- Do not fall back to splash/waiting while phone is on SoftAP and nav data exists
- Prefer notification icon over noisy HUD scan; filter RemoteViews reflection junk

### Notes
- Near a turn, 2GIS notification text becomes `350 m — Street` (usable distance)
- On-disk 2GIS logs are under `Android/data/ru.dublgis…/files/logs/` (app cannot read; use logcat + READ_LOGS)

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

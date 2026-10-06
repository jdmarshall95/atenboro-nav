# Changelog

## [1.0.8] — 2026-10-06

### Fixed
- Pocket icon classifier: slight / u-turn больше не схлопываются в hard left/right (угол 2ГИС + высота острия)
- Текст notif: `keep left/right`, `развернитесь`, `плавно левее` до hard left/right
- Камера: короткий sticky (~3.5 с), затем явный `camera=false` снимает алерт на OLED
- Карманная дистанция на locked/Doze: countdown в т.ч. <30 м; poll нотификаций ~1 с

### Added
- `scripts/test_locked_long_route.sh` — длинный синтетический locked-прогон (экран остаётся Asleep)
- `scripts/gis_moto_drive.py` — зеркало полилинии 2ГИС + GPS на эмуляторе

### Changed
- Версия линейки: **1.0.8** (Android `versionCode` / FW `FW_VERSION_CODE` = 108)

### Known
- Камера/лимит из ongoing-notif 2ГИС на EN 7.9.x в лабе по-прежнему не приходит (см. README)

## [0.1.7] — 2026-10-06

### Changed
- OLED distance: under 1 km meters; 1–9.9 km as `3.8` + `km`; 10 km+ as whole kilometers

### Fixed
- Pocket banners beyond 8 km were dropped (HUD showed junk ~20–30 m); prefer distance on the turn line over camera/ETA noise

### Added
- Host mock SoftAP (`scripts/mock_board.py`) + emulator inject via `debug.atenboro.esp_url`

## [0.1.6] — 2026-09-29

### Fixed
- Pocket HUD mismatch (2GIS right 400m vs OLED straight 1000): filter 2GIS chrome (`Update` / `Step by Step` / ETA), prefer notif banner `N m — street`, hold notif over noisy a11y/hud for 10s
- Icon classifier: transparent-corner largeIcon no longer inverted; corner-turn (⌊→) detected; horizontal strips ignored; chevron mass vs tip sign fixed
- Drop “navigating → straight” fallback; OLED always uses built-in glyphs for the resolved turn

## [0.1.5] — 2026-09-29

### Fixed
- OLED arrows: replace chunky “plunger” glyphs with classic chevrons (40×40, padded) so they are not cropped in the left blue half

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

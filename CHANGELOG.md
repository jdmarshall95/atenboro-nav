# Changelog

## [1.0.9] — 2026-10-07

### Added
- **2GIS Dashboard AIDL API** — официальный структурированный канал данных навигации:
  bind `ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE`,
  push-колбэк `IUpdateCallback.onDataUpdated()` + `getDashboardInformationJSON()`.
- `GisDashboardClient` — bind/reconnect (5 с, `onBindingDied`/`onNullBinding`), выбор пакета
  (`dgismobile` → `dgismobile4preview` → `urbi`), первый снапшот сразу после коннекта.
- `GisDashboardInfo` — разбор JSON с учётом границ версий API: 7.16 манёвр/прогресс,
  7.18 `speedLimit` (**м/с** → км/ч) и камеры, 7.21.7 пробки, 7.24.95 светофор.
- `GisManeuverCodenames` — каталог кодовых имён манёвров 2ГИС → токены прошивки
  (`left/right/slight_*/u_turn/straight/roundabout/arrive`);
  неизвестный кодоным угадывается по подстроке и логируется как «new codename».
- POST `/nav`: `cam_pct`, `nav_mode`, `progress`, `tl`, `tl_s`, `jam_min`.
- Прошивка: жёлтая полоса показывает `CAM 45%`, когда из API пришёл только процент
  приближения к камере (метр 2ГИС не отдаёт); перерисовка по шагу 5%.
- Статус-строка **«2GIS API»** на главном экране (подключено / нет связи).
- Юнит-тесты `GisManeuverCodenamesTest` (8) и `GisDashboardInfoTest` (14, Robolectric ради `org.json`).

### Changed
- Приоритет источников в `NavFeed`: **aidl > notif (карман) > a11y/hud** — AIDL не проигрывает
  залипшему notif; ветка `fromAidl` идёт своим ранним выходом, arbitration notif↔a11y не тронут.
- `buildFeatures { aidl = true }`, `<queries>` для package visibility (Android 11+).
- Превью и Parser debug показывают режим навигации, прогресс, светофор и процент камеры.

### Verified
- Pixel 7 (`35051FDH20048G`) + 2ГИС `7.29.1.632.4`: сервис
  `ru.dublgis.api.DashboardInformationService` реально экспортируется под action bind,
  версия сборки выше всех границ API (камера и светофор доступны).
- Прогон на живом устройстве + `scripts/mock_board.py` как принимающая плата (порт 18765,
  `adb reverse` + `debug.atenboro.esp_url=http://127.0.0.1:18765`): bind подтверждён на уровне
  системы (`ServiceRecord{… DashboardInformationService c:com.atenboro.nav}`), статус-строка
  показывает «2GIS API: подключено (Dashboard AIDL)», данные 2ГИС доходят до экрана и до платы —
  в `/debug` прилетают `nav_mode`, `progress`, `cam_kmh` (уже конвертированный из м/с).
- Инжектом проверены новые поля: `--ei cam_pct 45 --es nav_mode motorcycle --ei progress 37
  --es tl green --ei tl_s 7` → плата приняла `cam_pct: 45, progress: 37, tl: green, tl_s: 7`.

### Known
- AIDL не отдаёт дистанцию камеры в метрах — только `trafficCameraDistancePercent`
  (0→100 по мере приближения), поэтому `cam_m` из этого источника всегда `-1`.
- Каталог кодовых имён манёвров — алфавитная выборка: базовые `left/right/straight` в нём не
  перечислены, таблица дополнена вручную + есть fallback-эвристика.

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

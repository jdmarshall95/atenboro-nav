# Changelog

## [1.2] — 2026-10-12

### Added
- **iOS-приложение** (`ios/`, Swift, UIKit, deployment target iOS 16.1): второй
  прокси 2ГИС → плата. Кадр `POST /nav`, кодоимена манёвров и API платы общие с
  Android — дублировать протокол не пришлось.
- Источник №1 — **Live Activity** 2ГИС через ActivityKit
  (`Sources/LiveActivitySource.swift`): манёвр, дистанция, прогресс, время в пути,
  лимит скорости, камера, светофор. Опрос раз в 2 с, «живость» — активность,
  обновлённая менее чем за 6 с.
- Запасные каналы: URL-схема `atenboro://left?maneuverDistance=250&progress=41…`
  (ручная проверка и «Команды») и CarPlay как сигнал «навигация жива», когда
  приложение ушло в фон.
- Экран один, как в Android: крупный манёвр и дистанция, ниже прогресс / время в
  пути / источник кадра, строки статуса (плата, сеть, активность 2ГИС),
  шестерёнка с настройками. Палитра чёрный / серый / золото, светлая и тёмная тема.
- Проект собирается XcodeGen (`ios/project.yml`), тесты — XCTest.
- Реверс-инжиниринг 2ГИС на iOS: [`docs/IOS-2GIS-REVERSE.md`](docs/IOS-2GIS-REVERSE.md).

### Fixed
- Live Activity сначала не читалась («нет активности 2ГИС» при живой навигации):
  ActivityKit фильтрует активности по **имени Swift-типа** атрибутов, а хука вроде
  `activityAttributesName` в SDK нет (`AnyActivityAttributes` — только iOS 18).
  Тип переименован из `NavActivityAttributes` в `NavigatorLiveAttributes` — ровно
  как в бинаре 2ГИС (`Activity<NavigatorLiveAttributes>`).

### Verified
- `xcodebuild test` — **86/86** зелёные на симуляторе и на iPhone 15 Pro Max
  (iOS 26.6.2, беспроводная отладка).
- Живое чтение с 2ГИС на телефоне: манёвр и дистанция на экране приложения меняются
  синхронно с навигацией 2ГИС.
- URL-схема на устройстве: `atenboro://left?maneuverDistance=250&progress=41&
  remainingTime=780&speedLimit=60` → «Налево · 250 м · 41 % · источник: urlScheme».
- App Group для чтения активности не требуется: у 2ГИС нет activity-группы в
  entitlements, но активность видна.
- `./gradlew assembleDebug test` на 1.2 — без регрессий.

## [1.1] — 2026-10-10

### Changed
- Полное переписывание интерфейса на Material Design 3 (Views + ViewBinding):
  палитра «чёрный / серый / металлическое золото», градиенты вместо плоских фонов.
- Главный экран — ровно один экран без прокрутки: шапка с логотипом, три статуса
  (плата ESP8266, 2ГИС AIDL, интернет) и две кнопки. OLED-превью убрано —
  оно было нужно только для отладки.
- Всё остальное уехало в «шестерёнку» (`SettingsActivity`): источники данных
  (AIDL — основной, уведомления / Accessibility / лог 2ГИС — по желанию)
  и вторичные статусы.
- Фон окна — «аура»: радиальное золотое свечение поверх градиента
  (`bg_window_glow`), карточки полупрозрачные (`surface_glass`), чтобы свечение
  читалось сквозь них. Днём аура почти незаметна, ночью — ярче.
- Переключатель день/ночь — в стиле sun/moon-логотипа Валентино Росси
  (`ic_daynight_split`), цикл авто → день → ночь; режим сохраняется между запусками.

### Removed
- Отладковые элементы с главного экрана: Dump, «Копировать», «Тест OLED»,
  OLED-превью и строка «Быстрый старт».

### Verified
- `./gradlew assembleDebug test` — 58/58 тестов зелёные.
- Эмулятор `Medium_Phone_API_35`: иерархия главного экрана без прокрутки и без
  превью, шестерёнка открывает экран настроек с тремя тумблерами источников;
  дневной и ночной режимы проверены скриншотами и замером яркости.
- Pixel 7 (adb over Wi-Fi): APK установлен, приложение запускается.

## [1.0.11] — 2026-10-10

### Fixed
- Дистанция перестала «прыгать» на прежнее значение на подъездах дальше 10 км.
  Пока 2ГИС молит по AIDL, уведомление отдаёт дистанцию целыми километрами
  («17 km» → 17000 м) вместо честных метров API (16000 м) — на OLED это был
  откат цифры назад на несколько секунд.
- `AIDL_HOLD_MS` поднят 10 с → 30 с: по логу двухчасовой поездки p90 паузы AIDL
  6,7 с, но 158 пауз длиннее 10 с — держалка обрывалась посреди маршрута.

### Added
- `NavFeed.clampToAidlDistance()` — coarse-источник (notif/a11y/gislog) не может
  увеличить дистанцию относительно последнего точного AIDL-значения (окно 60 с);
  countdown в меньшую сторону проходит как есть.
- Диагностическая строка `dist clamped to aidl 17000->16000 (aidl age …ms)`.
- Юнит-тесты `NavFeedTest` на clamp: округлённые километры, countdown, отсутствующий
  и протухший AIDL.

## [1.0.10] — 2026-10-09

### Added
- SoftAP **secondary** без `bindProcessToNetwork`: HTTP на плату через SocketFactory,
  LTE/интернет процесса не гасится; в UI строки «ESP» и «Интернет: LTE/Wi‑Fi».
- OLED: PROGMEM-глифы по PDF-кодоимени `maneuver_icon` (`firmware/src/gis_maneuvers.h`,
  51 иконка 62×46); регенерация [`scripts/gen_gis_maneuvers.py`](scripts/gen_gis_maneuvers.py).
- POST `/nav` поле `maneuver_icon`; inject `--es maneuver_icon …`.

### Fixed
- Пока свежий Dashboard AIDL (~10 с), notif/a11y/gislog **не** перебивают `turn` на OLED
  (полевая ошибка «плавно направо → налево»).
- Парсер «плавно поверните» / slight_right и каталог кодоимён согласованы с PDF 2ГИС.
- Fallback-стрелки и GIS-глифы максимизированы под левую половину OLED (62×46).

### Verified
- Pixel 7 + SoftAP `atenboro-nav`: ESP online при живом LTE; inject `crossroad_slightly_right`
  и камера `cam_kmh=60` на OLED; живой прогон Павелецкая→Гарибальди с HUD-камерой 60.

## [1.0.9] — 2026-10-07

### Added
- **2GIS Dashboard AIDL API** — официальный структурированный канал данных навигации:
  bind `ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE`,
  push-колбэк `IUpdateCallback.onDataUpdated()` + `getDashboardInformationJSON()`.
- `GisDashboardClient` — bind/reconnect (5 с, `onBindingDied`/`onNullBinding`), выбор пакета
  (`dgismobile` → `dgismobile4preview` → `urbi`), первый снапшот сразу после коннекта.
- `GisDashboardInfo` — разбор JSON по группам полей API: навигация и прогресс,
  `speedLimit` (**м/с** → км/ч) и камеры, пробки, светофор.
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
  версия сборки покрывает все поля API (камера и светофор доступны).
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

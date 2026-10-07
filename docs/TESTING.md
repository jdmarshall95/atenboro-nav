# Тестирование Atenboro Nav

## 1. Прошивка и OLED (без Android)

1. Подключите HW-364A по USB.
2. Залейте прошивку:

```bash
cd firmware
../.venv/bin/pio run -t upload
```

3. На OLED: `Hello Atenboro` → `waiting...` / `AP: atenboro-nav`.
4. Подключите ПК или телефон к Wi‑Fi `atenboro-nav` / `atenboro1`.
5. Запустите:

```bash
./scripts/test_oled.sh
```

Ожидание на HUD (v0.1.4+): жёлтая полоса — камера/лимит; синяя — стрелка слева, метры справа.

Снимок framebuffer:

```bash
./scripts/fetch_oled_screen.sh
```

6. Подождите > 5 с без запросов — должно появиться `NO LINK`.

## 2. Android → OLED

1. Откройте `android/` в Android Studio, Sync, Run на телефон.
2. Телефон в сети `atenboro-nav`.
3. В приложении статус **ESP: онлайн**.
4. Кнопка **Тест OLED** → на дисплее LEFT / 250 м / CAM 120 м.

## 3. Accessibility + 2ГИС

1. Включите службу доступности Atenboro Nav.
2. **Запустить прокси**.
3. В 2ГИС постройте маршрут и начните навигацию.
4. В приложении смотрите превью поворота/дистанции/камеры.
5. Если пусто — **Dump узлов 2ГИС**, откройте файл в `Android/data/com.atenboro.nav/files/dumps/` и подправьте `NavParser`.

## 3b. 2GIS Dashboard AIDL API

Основной структурированный источник (1.0.9+). Требуется 2ГИС 7.16+; на текущих сборках
доступны все поля API, включая камеры и светофор.

1. Проверить, что установленный 2ГИС реально экспортирует сервис:

```bash
adb shell dumpsys package ru.dublgis.dgismobile | grep -A4 ACTION_BIND_DASHBOARD
```

2. Установить APK, **Запустить прокси** — на главном экране строка **«2GIS API: подключено (Dashboard AIDL)»**.
3. Начать навигацию в 2ГИС; в `Debug: лог ↔ плата` должны появиться записи
   `aidl turn=… d=… cam=… kmh=… pct=… nav=…`.
4. Если вместо этого «нет связи» — 2ГИС не запущен, либо пакет не тот (проверяются
   `ru.dublgis.dgismobile`, `…4preview`, `ru.dublgis.urbi`).
5. Запись `aidl new codename: <имя>` в логе = 2ГИС расширил каталог манёвров —
   добавьте кодоным в `GisManeuverCodenames.TABLE`.

Юнит-тесты парсера (нужен `ANDROID_HOME`):

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*GisDashboardInfoTest' --tests '*GisManeuverCodenamesTest'
```

### Прогон на реальном телефоне + виртуальной платой

Проверено на Pixel 7 (2ГИС 7.29.1.632.4) с `scripts/mock_board.py` в качестве принимающей платы.
Удобнее гонять через `adb reverse`, а не по LAN: на физическом устройстве трафик
к `192.168.x.x` уходит в LTE и `EspNetwork.bindIfReachable()` его не ловит
(`failed to connect … from /172.25.x.x`), а `127.0.0.1` попадает в `isMockBoard()`
и привязка к сети вообще не нужна.

```bash
python3 scripts/mock_board.py --port 18765 &
adb reverse tcp:18765 tcp:18765
adb shell setprop debug.atenboro.esp_url http://127.0.0.1:18765
adb shell am force-stop com.atenboro.nav
adb shell am start -n com.atenboro.nav/.MainActivity
# затем на экране «ЗАПУСТИТЬ ПРОКСИ-СЕРВИС» (am startservice не подойдёт — service exported=false)
```

Ожидание:
- `ESP: онлайн (http://127.0.0.1:18765)` и `2GIS API: подключено (Dashboard AIDL)`;
- в `dumpsys activity services ru.dublgis.dgismobile` появляется
  `ru.dublgis.api.DashboardInformationService c:com.atenboro.nav`;
- `curl http://127.0.0.1:18765/debug` отдаёт `nav_mode`, `progress`, `cam_pct` из 2ГИС
  (в том числе `speedLimit` уже конвертированным в км/ч).

Проверка `cam_pct` без реальной камеры — через inject (поля AIDL добавлены в receiver в 1.0.9):

```bash
adb shell am broadcast -a com.atenboro.nav.INJECT_NAV \
  -n com.atenboro.nav/.service.InjectNavReceiver \
  --es turn left --ei dist_m 150 --ez camera true --ei cam_pct 45 \
  --es nav_mode motorcycle --ei progress 37 --es tl green --ei tl_s 7
```

## 4. Заблокированный экран (карман)

Ближе всего к реальной езде: SoftAP + FGS-прокси, экран `Asleep`/`Dozing`, апдейты без Activity.

Короткий smoke:

```bash
./scripts/test_locked_screen.sh
```

Длинный синтетический маршрут (жёстко требует `interactive=false` на каждом шаге, в конце **оставляет экран погашенным**):

```bash
./scripts/test_locked_long_route.sh
# разбудить после прогона: UNLOCK_AFTER=1 ./scripts/test_locked_long_route.sh
```

Ожидание: `mWakefulness=Asleep|Dozing`, в логах `locked-inject … interactive=false`, дистанция на mock/OLED тикает до 0 м.

## 5. Эмулятор + mock SoftAP (без реальной платы)

На эмуляторе нет Wi‑Fi SoftAP; плюс на телефоне SoftAP часто рвёт интернет у 2ГИС. Для стенда поднимите mock HTTP-плату на хосте:

```bash
./scripts/mock_board.py          # слушает 0.0.0.0:18765
./scripts/emu_mock_drive.sh      # setprop + inject сценарий
```

Эмулятор ходит на хост как `http://10.0.2.2:18765` (это задаёт `debug.atenboro.esp_url`). Сброс: `adb shell setprop debug.atenboro.esp_url ''` и force-stop приложения.

На реальном телефоне с SoftAP: держите LTE, плату как secondary local-only («Подключить к плате» / `bindProcessToNetwork`) — иначе 2ГИС без интернета и HUD «молчит».

## 6. Симуляция заезда (`sim_route_drive`)

Скрипт [`scripts/sim_route_drive.sh`](../scripts/sim_route_drive.sh) гоняет сценарий без реальной поездки:

| Режим | Что делает |
|-------|------------|
| `hud` | inject манёвров на OLED через SoftAP (нужна плата / bind) |
| `geo` | шагает mock GPS по полилинии (нужен fake-GPS app), 2ГИС пересчитывает |
| `full` | geo + мониторинг сессии / OLED, затем hud-оверлей |

```bash
./scripts/sim_route_drive.sh hud
./scripts/sim_route_drive.sh geo
./scripts/sim_route_drive.sh full
```

Артефакты пишутся в `/tmp/atenboro-drive-…` (лог, session pull, разбор stutter/flip).

## 7. Мото-прогон по маршруту 2ГИС (`gis_moto_drive`)

[`scripts/gis_moto_drive.py`](../scripts/gis_moto_drive.py) строит A→B, **дублирует полилинию** локально и кормит эмулятор GPS как мотоцикл (~48 км/ч, торможение в поворотах).

1. Геометрия: `DGIS_API_KEY` → Routing API `transport=motorcycle`; иначе OSRM по тем же A/B (fallback).
2. Deep link открывает маршрут в приложении 2ГИС.
3. Автотап: чип **motorcycle** + зелёная **Go!** (поиск по скриншоту).
4. Езда: `adb emu geo nmea` + `geo fix` + test-provider.

```bash
# Красноя площадь → Белорусская (дефолт)
./scripts/gis_moto_drive.py

# Точная геометрия 2ГИС
DGIS_API_KEY=xxx ./scripts/gis_moto_drive.py --engine dgis

# Уже сохранённый маршрут, без повторного open
./scripts/gis_moto_drive.py --route-json /tmp/atenboro-moto-run2/route.json --skip-open --no-tap-go
```

Артефакты: `/tmp/atenboro-moto-…/route.json`, `route.csv`, скрины, `session-tail.txt`, `mock-final.json`.

На экране «выбор маршрута» без автотапа: чип мото → **Go!** (примерно `851 1873` на Pixel 1080×2400).

Быстрый прогон (камеры / lab):

```bash
./scripts/gis_moto_drive.py --step-m 80 --cruise-kmh 90
```

**Важно:** ускорение GPS не вытаскивает камеру из notif — в карманном баннере 2ГИС её сейчас нет (см. README → «Карманный режим и камеры»).

## 8. Юнит-тесты парсера

В Android Studio: правый клик по `NavParserTest` → Run.

Или:

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

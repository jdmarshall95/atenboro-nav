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

## 4. Заблокированный экран (карман)

Ближе всего к реальной езде: SoftAP + FGS-прокси, экран `Dozing`, апдейты без Activity.

```bash
./scripts/test_locked_screen.sh
```

Ожидание: `mWakefulness=Dozing`, в логах `locked-screen turn=… interactive=false`, OLED обновляется (в т.ч. мигание камеры).

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

## 8. Юнит-тесты парсера

В Android Studio: правый клик по `NavParserTest` → Run.

Или:

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

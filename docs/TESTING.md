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

## 5. Симуляция заезда (`sim_route_drive`)

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

## 6. Юнит-тесты парсера

В Android Studio: правый клик по `NavParserTest` → Run.

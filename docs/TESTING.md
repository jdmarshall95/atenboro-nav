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

## 4. Юнит-тесты парсера

В Android Studio: правый клик по `NavParserTest` → Run.

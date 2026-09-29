# Atenboro Nav

**2ГИС → OLED HUD** для платы HW-364A (ESP8266 + SSD1306 128×64).

[![Version](https://img.shields.io/badge/version-0.1.2-3DDC97)](VERSION)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Приложение на Android читает подсказки навигации 2ГИС (Accessibility и уведомления) и шлёт на дисплей следующий поворот, дистанцию и камеры. Плата поднимает SoftAP — телефон подключается напрямую.

```
2ГИС ──a11y / notifications──► Atenboro Android ──Wi‑Fi SoftAP──► ESP8266 OLED
```

## Состав

| Путь | Что внутри |
|------|------------|
| [`firmware/`](firmware/) | Прошивка PlatformIO (SoftAP + HTTP + OLED) |
| [`android/`](android/) | Kotlin-приложение-прокси |
| [`docs/WIRING.md`](docs/WIRING.md) | Пины HW-364A, SoftAP, API |
| [`docs/TESTING.md`](docs/TESTING.md) | Чеклист проверки |
| [`scripts/test_oled.sh`](scripts/test_oled.sh) | curl-сценарии для OLED |
| [`VERSION`](VERSION) | Semver проекта (`0.1.0`) |

## Быстрый старт

### 1. Прошивка

```bash
cd firmware
../.venv/bin/pio run -t upload --upload-port /dev/ttyUSB0
```

SoftAP: **`atenboro-nav`** / **`atenboro1`** → `http://192.168.4.1`  
На splash: логотип, C-130 и **`v0.1.0`**.

```bash
./scripts/test_oled.sh
```

### 2. Android

1. Откройте `android/` в Android Studio, соберите APK.
2. **Подключить к плате** (привязка трафика к SoftAP без интернета).
3. Включите **Accessibility** и **доступ к уведомлениям** (нужно для кармана).
4. Запустите прокси → навигация в 2ГИС.
5. **Тест OLED** / **Debug: лог ↔ плата** при отладке.

## HTTP API (плата)

| Метод | Путь | Назначение |
|-------|------|------------|
| GET | `/health` | `{"ok":true,"version":"0.1.0"}` |
| POST | `/nav` | HUD: turn, dist_m, camera, cam_m |
| GET/POST/DELETE | `/debug` | Кольцевой лог платы ↔ телефон |

Пример:

```bash
curl -s -X POST http://192.168.4.1/nav \
  -H 'Content-Type: application/json' \
  -d '{"turn":"left","dist_m":250,"camera":true,"cam_m":120}'
```

## Версионирование

- Корень: [`VERSION`](VERSION)
- Прошивка: [`firmware/src/version.h`](firmware/src/version.h) (`FW_VERSION`)
- Android: `versionName` / `versionCode` в `android/app/build.gradle.kts`
- История: [`CHANGELOG.md`](CHANGELOG.md)

При релизе поднимайте все три места синхронно и ставьте git-тег `vX.Y.Z`.

## Железо

Плата **HW-364A**: OLED I2C часто на **SDA=GPIO4 / SCL=GPIO5** или swap D5/D6 — прошивка сканирует пары сама. Подробности: [docs/WIRING.md](docs/WIRING.md).

## Ограничения

- 2ГИС на Qt отдаёт мало accessibility-текста; в фоне опираемся на notification RemoteViews.
- SoftAP без интернета: Android иначе шлёт HTTP через LTE — кнопка «Подключить к плате» делает `bindProcessToNetwork`.

## Лицензия

[MIT](LICENSE)

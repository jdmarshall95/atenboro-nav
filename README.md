# Atenboro Nav

**2ГИС → OLED** на плате HW-364A (ESP8266 + SSD1306 128×64).

[![Version](https://img.shields.io/badge/version-0.1.5-FFCC00?style=flat-square&labelColor=16181f)](VERSION)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square&labelColor=16181f)](LICENSE)

Телефон читает манёвры 2ГИС и рисует их на OLED: стрелка, метры, мигающий лимит камеры.

<p align="center">
  <img src="docs/screens/hero.png" alt="OLED: налево, направо, разворот" width="820" />
</p>

```
2ГИС ──► Atenboro (Android) ── SoftAP ──► ESP8266 OLED
```

## HUD

| Полоса | Цвет | Содержание |
|--------|------|------------|
| сверху (`y0–15`) | жёлтый | камера: мигание + км/ч |
| снизу слева | синий | шеврон манёвра |
| снизу справа | синий | дистанция + `m` |

<p align="center">
  <img src="docs/screens/gallery.png" alt="Манёвры OLED" width="820" />
</p>

## Быстрый старт

**Прошивка**

```bash
cd firmware && ../.venv/bin/pio run -t upload --upload-port /dev/ttyUSB0
```

SoftAP: `atenboro-nav` / `atenboro1` → `http://192.168.4.1`  
Готовый `.bin` и APK — в [Releases](https://github.com/jdmarshall95/atenboro-nav/releases).

**Android**

1. Установить APK, подключиться к SoftAP.
2. «Подключить к плате» → Accessibility + уведомления → прокси.
3. Навигация в 2ГИС.

## API платы

| | | |
|--|--|--|
| `GET` | `/health` | версия |
| `POST` | `/nav` | `turn`, `dist_m`, `camera`, `cam_m`, `cam_kmh` |
| `GET` | `/screen` | framebuffer + meta |
| `*` | `/debug` | лог телефон ↔ плата |

```bash
curl -s -X POST http://192.168.4.1/nav \
  -H 'Content-Type: application/json' \
  -d '{"turn":"left","dist_m":80,"camera":true,"cam_kmh":60}'
```

## Репозиторий

| | |
|--|--|
| [`firmware/`](firmware/) | SoftAP + OLED |
| [`android/`](android/) | прокси 2ГИС |
| [`docs/WIRING.md`](docs/WIRING.md) | пины |
| [`docs/TESTING.md`](docs/TESTING.md) | чеклист |
| [`CHANGELOG.md`](CHANGELOG.md) | история |

Версии синхронно: [`VERSION`](VERSION), `firmware/src/version.h`, `versionName` в Gradle.

## Заметки

- 2ГИС на Qt почти не отдаёт a11y-текст — в фоне работают notification icon и logcat.
- SoftAP без интернета: без «Подключить к плате» HTTP уйдёт в LTE.

## Лицензия

[MIT](LICENSE)

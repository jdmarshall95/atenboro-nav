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

## OLED HUD

| Полоса | Цвет | Содержание |
|--------|------|------------|
| сверху (`y0–15`) | жёлтый | камера: мигание + км/ч |
| снизу слева | синий | шеврон манёвра |
| снизу справа | синий | дистанция + `m` |

<p align="center">
  <img src="docs/screens/gallery.png" alt="Манёвры OLED" width="820" />
</p>

## Приложение Android

### Главный экран

<p align="center">
  <img src="docs/screens/app-main-annotated.png" alt="Главный экран Atenboro Nav с пояснениями" width="920" />
</p>

| # | Элемент | Зачем |
|---|---------|--------|
| 1 | Шапка | Бренд и схема «2ГИС → OLED» |
| 2 | Статус ESP | Зелёный — SoftAP/`/health` ок; красный — нет связи |
| 3 | **Подключить к плате** | `bindProcessToNetwork` на `atenboro-nav` (иначе HTTP уйдёт в LTE) |
| 4 | Статус Accessibility | Служба читает окна/HUD 2ГИС |
| 5 | Превью OLED | Что ушло на плату: поворот, метры, камера, HTTP |
| 6 | Parser debug | Сырой разбор 2ГИС (тексты, дистанции, манёвр) |
| 7 | Копировать / Dump | Буфер обмена или снимок a11y-дерева |
| 8 | **Включить Accessibility** | Системные настройки службы |
| 9 | **Уведомления 2ГИС** | Notification Listener — основной канал **в кармане** |
| 10 | **Прокси-сервис** | FGS: процесс, SoftAP-bind, logcat-watcher |
| 11 | Тест OLED | Образец left/250 м/камера без 2ГИС |
| 12 | **Debug: лог ↔ плата** | Синхрон логов и снимок framebuffer |

Типичный порядок: **3 → 8 → 9 → 10 → навигация в 2ГИС**.

### Debug ↔ ESP

<p align="center">
  <img src="docs/screens/app-debug-annotated.png" alt="Экран Debug с пояснениями" width="900" />
</p>

| # | Элемент | Зачем |
|---|---------|--------|
| 1 | Заголовок | Экран отладки телефон ↔ плата |
| 2 | Путь лога | Локальный NDJSON `files/debug/session-….ndjson` |
| 3 | Синхрон | Тянет `GET /debug` с ESP и мержит в лог |
| 4 | Очистить плату | `DELETE /debug` на ESP |
| 5 | Снимок OLED | `GET /screen` + meta-заголовки |
| 6 | Превью | Декод буфера SSD1306 |
| 7 | Обновить лог | Перечитать локальные события |
| 8 | Лента | inject / notif / locked-screen / board |

## Быстрый старт

**Прошивка**

```bash
cd firmware && ../.venv/bin/pio run -t upload --upload-port /dev/ttyUSB0
```

SoftAP: `atenboro-nav` / `atenboro1` → `http://192.168.4.1`  
Готовый `.bin` и APK — в [Releases](https://github.com/jdmarshall95/atenboro-nav/releases).

**Android**

1. Установить APK, Wi‑Fi → SoftAP платы.
2. В приложении: «Подключить к плате» → Accessibility → уведомления → прокси.
3. Навигация в 2ГИС (экран можно блокировать).

Подробный чеклист и тест с заблокированным экраном: [`docs/TESTING.md`](docs/TESTING.md).

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
| [`docs/screens/`](docs/screens/) | OLED + UI |
| [`docs/WIRING.md`](docs/WIRING.md) | пины |
| [`docs/TESTING.md`](docs/TESTING.md) | чеклист |
| [`CHANGELOG.md`](CHANGELOG.md) | история |

## Заметки

- 2ГИС на Qt почти не отдаёт a11y-текст — в фоне работают notification icon и logcat.
- SoftAP без интернета: без «Подключить к плате» HTTP уйдёт в LTE.

## Лицензия

[MIT](LICENSE)

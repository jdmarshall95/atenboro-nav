# Atenboro Nav — DIY мотонавигатор / bike OLED HUD

Телефон в кармане с привычным **2ГИС** (Яндекс — в планах), на руле — дешёвый **OLED**: куда свернуть и через сколько метров. Без держалки смартфона и без отдельного приложения с картами.

**2ГИС → OLED** на плате HW-364A (**ESP8266** + **SSD1306** 128×64 dual-color). Сейчас Wi‑Fi SoftAP; дальше — **ESP32** + BLE. Open source, MIT.

[![Version](https://img.shields.io/badge/version-0.1.6-FFCC00?style=flat-square&labelColor=16181f)](VERSION)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square&labelColor=16181f)](LICENSE)

Android-прокси читает манёвры 2ГИС (notification / Accessibility) и рисует turn-by-turn на OLED: шеврон, дистанция, мигающий лимит камеры.

<p align="center">
  <img src="docs/screens/boot.gif" alt="Atenboro Nav — OLED splash на ESP8266" width="520" />
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
  <img src="docs/screens/hero.png" alt="OLED: налево, направо, разворот" width="820" />
</p>

<p align="center">
  <img src="docs/screens/gallery.png" alt="Манёвры OLED" width="820" />
</p>

## Плата вживую

Снято с телефона на столе (HW-364A + SSD1306), кадр обрезан вокруг платы. Исходные MOV → GIF/MP4: [`scripts/pack_field_gifs.py`](scripts/pack_field_gifs.py).

**SoftAP / splash** — `waiting…` и `AP: atenboro-nav`, затем сплэш:

<p align="center">
  <img src="docs/screens/field/board-softap.gif" alt="Плата: SoftAP waiting и splash" width="360" />
</p>

**Живой HUD** — смена манёвра и метров на OLED (сэмпл по ролику; полный клип — MP4):

<p align="center">
  <img src="docs/screens/field/board-live-nav.gif" alt="Плата: live turn-by-turn на OLED" width="360" />
</p>


Полный прогон без прореживания кадров: [`docs/screens/field/board-live-nav.mp4`](docs/screens/field/board-live-nav.mp4).

## Приложение Android

Реальные скриншоты с устройства.

### Главный экран

<p align="center">
  <img src="docs/screens/app-main-annotated.png" alt="Главный экран — верх" width="900" />
</p>

| # | Элемент | Зачем |
|---|---------|--------|
| 1 | Шапка | Бренд и схема 2ГИС → OLED |
| 2 | Статус ESP | SoftAP / HTTP до платы |
| 3 | **Подключить к плате** | `bindProcessToNetwork` на `atenboro-nav` |
| 4 | Accessibility | Чтение окон/HUD 2ГИС |
| 5 | Превью OLED | Поворот, метры, камера, HTTP |
| 6 | Parser debug | Сырой разбор 2ГИС |
| 7 | Копировать / Dump | Буфер обмена или dump a11y |
| 8 | **Включить Accessibility** | Системные настройки службы |

<p align="center">
  <img src="docs/screens/app-main-actions-annotated.png" alt="Главный экран — кнопки" width="900" />
</p>

| # | Элемент | Зачем |
|---|---------|--------|
| 4 | **Включить Accessibility** | Системные настройки a11y |
| 5 | **Уведомления 2ГИС** | Карманный режим (экран off) |
| 6 | **Прокси-сервис** | FGS: процесс, SoftAP-bind, logcat |
| 7 | Тест OLED | Образец на плату без 2ГИС |
| 8 | **Debug: лог ↔ плата** | Синхрон логов и `/screen` |

Типичный порядок: **Подключить к плате → Accessibility → уведомления → прокси → навигация в 2ГИС**.

<p align="center">
  <img src="docs/screens/app-main-top.png" width="260" alt="верх" />
  <img src="docs/screens/app-main-mid.png" width="260" alt="кнопки" />
  <img src="docs/screens/app-main-bot.png" width="260" alt="низ" />
</p>

### Debug ↔ ESP

<p align="center">
  <img src="docs/screens/app-debug-annotated.png" alt="Debug экран" width="900" />
</p>

| # | Элемент | Зачем |
|---|---------|--------|
| 1 | Заголовок / путь | NDJSON-сессия на телефоне |
| 2 | Синхрон | `GET /debug` с платы |
| 3 | Очистить плату | `DELETE /debug` |
| 4 | Снимок OLED | `GET /screen` + meta |
| 5 | Превью | Декод SSD1306 |
| 6 | Обновить лог | Локальные события |
| 7 | Лента | inject / notif / screen / board |

## Быстрый старт

**Прошивка**

```bash
cd firmware && ../.venv/bin/pio run -t upload --upload-port /dev/ttyUSB0
```

SoftAP: `atenboro-nav` / `atenboro1` → `http://192.168.4.1`  
Готовый `.bin` и APK — в [Releases](https://github.com/jdmarshall95/atenboro-nav/releases).

**Android**

1. Установить APK, Wi‑Fi → SoftAP платы.
2. «Подключить к плате» → Accessibility → уведомления → прокси.
3. Навигация в 2ГИС (экран можно блокировать).

Чеклист и locked-screen тест: [`docs/TESTING.md`](docs/TESTING.md).  
Симуляция заезда (inject HUD / mock GPS): [`scripts/sim_route_drive.sh`](scripts/sim_route_drive.sh) — режимы `hud`, `geo`, `full`.

## API платы

| Метод | Путь | Описание |
|-------|------|----------|
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
| [`docs/screens/field/`](docs/screens/field/) | живые GIF/MP4 платы |
| [`docs/WIRING.md`](docs/WIRING.md) | пины |
| [`docs/TESTING.md`](docs/TESTING.md) | чеклист |
| [`scripts/sim_route_drive.sh`](scripts/sim_route_drive.sh) | симуляция заезда (hud/geo/full) |
| [`scripts/render_boot_gif.py`](scripts/render_boot_gif.py) | GIF splash для README |
| [`scripts/pack_field_gifs.py`](scripts/pack_field_gifs.py) | MOV → field GIF/MP4 |
| [`CHANGELOG.md`](CHANGELOG.md) | история |
| Roadmap | [железо и BLE](#roadmap--железо) |

## Заметки

- 2ГИС на Qt почти не отдаёт a11y-текст — в фоне работают notification icon и logcat.
- SoftAP без интернета: без «Подключить к плате» HTTP уйдёт в LTE.
- На Android SoftAP часто отваливается, когда телефон видит знакомую сеть — это главный стимул уйти на BLE.

## Roadmap / железо

### Сейчас

| | |
|--|--|
| Плата | **HW-364A** — ESP8266 + SSD1306 128×64 (жёлтый/синий) |
| Связь | Wi‑Fi SoftAP `atenboro-nav` → HTTP `192.168.4.1` |
| Питание | Li‑ion ~650 mAh — SoftAP прожорлив, автономность ограничена |
| Минусы | конфликт с известными Wi‑Fi на телефоне, расход, размер модуля |

### Дальше

Цель: **BLE GATT** вместо SoftAP; наушники (A2DP/HFP) работают параллельно по Classic BT.

| Приоритет | Железо | Зачем |
|-----------|--------|--------|
| 1 | **ESP32‑C3 SuperMini** / Seeed XIAO ESP32‑C3 | BLE, USB‑C, быстрый порт firmware |
| 1 | OLED **0.96″ SSD1306 I2C 128×64** dual‑color | тот же HUD |
| 1 | Li‑ion **1000–2000 mAh** + **TP4056** | запас ёмкости без SoftAP |
| 2 | **Seeed XIAO nRF52840** + тот же OLED | максимум автономности (опционально) |

Не для HUD: IPS/TFT (подсветка), e‑ink (медленно), Classic SPP (конфликт с наушниками).

Протокол (план): плата = BLE peripheral, телефон пишет короткий кадр манёвра и держит FGS‑reconnect.

## Лицензия

[MIT](LICENSE)

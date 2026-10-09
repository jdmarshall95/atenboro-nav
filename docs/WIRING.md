# Wiring / HW-364A

Плата: **HW-364A** (NodeMCU ESP8266 + OLED 0.96").

## OLED (встроенный)

| Сигнал | NodeMCU | GPIO |
|--------|---------|------|
| SDA    | D6      | 14   |
| SCL    | D5      | 12   |
| Питание | 3.3 V | — |
| Контроллер | SSD1306 | I2C `0x3C` |
| Разрешение | 128×64 | dual-color (жёлтая полоса сверху) |

Дополнительной пайки OLED не требуется.

## SoftAP

| Параметр | Значение |
|----------|----------|
| SSID | `atenboro-nav` |
| Пароль | `atenboro1` |
| IP платы | `192.168.4.1` |
| HTTP | порт `80` |

## API

### `GET /health`

```json
{"ok":true}
```

### `GET /`

Статус SoftAP и возраст последнего апдейта.

### `POST /nav`

```json
{
  "turn": "left",
  "dist_m": 250,
  "camera": true,
  "cam_m": 120,
  "ts": 1710000000
}
```

`turn`: `left` | `right` | `slight_left` | `slight_right` | `u_turn` | `straight` | `roundabout` | `arrive` | `none`

Опционально `maneuver_icon` — сырое кодоимя иконки 2ГИС из PDF-каталога
(напр. `crossroad_slightly_right`). OLED рисует PROGMEM-глиф по этому имени;
если неизвестно — fallback на `turn`.

Ответ: `{"ok":true}`

Если более 5 с нет апдейта — на OLED «нет связи».

## Debug API

### `GET /debug`

Снимок состояния платы + кольцевой лог (до 32 событий).

```json
{
  "ok": true,
  "uptime_ms": 12345,
  "stations": 1,
  "oled": true,
  "events": [{"t":1000,"src":"esp","lvl":"i","msg":"boot"}]
}
```

### `POST /debug`

Запись события(й) с телефона в лог платы:

```json
{"lvl":"i","msg":"phone bind ok"}
```

или

```json
{"events":[{"lvl":"w","msg":"..."},{"lvl":"i","msg":"..."}]}
```

### `DELETE /debug`

Очистить кольцевой буфер на плате.

На телефоне лог хранится в `files/debug/session-YYYYMMDD.ndjson`, снимки платы — `board-*.json`. Экран **Debug: лог ↔ плата**.

## Прошивка

```bash
cd firmware
../.venv/bin/pio run -t upload
../.venv/bin/pio device monitor -b 115200
```

## Тест с curl

```bash
curl -s http://192.168.4.1/health
curl -s http://192.168.4.1/debug
curl -s -X POST http://192.168.4.1/debug -H 'Content-Type: application/json' -d '{"lvl":"i","msg":"hello from curl"}'
curl -s -X POST http://192.168.4.1/nav -H 'Content-Type: application/json' -d '{"turn":"left","dist_m":250,"camera":true,"cam_m":120}'
```

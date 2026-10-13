# Contributing to Atenboro Nav

Краткий DX-гайд: скрипты, симуляция заезда, сборка прошивки.

## Окружение для `scripts/`

Нужны Python 3.11+ и `adb` (для симуляции на телефоне/эмуляторе).

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements.txt
pre-commit install
```

Проверки локально (те же, что в CI `lint-scripts`):

```bash
ruff check scripts/
pre-commit run --all-files
```

Хуки режут trailing whitespace, гоняют ruff по `scripts/` и не дают закоммитить случайные `.bin` / `.apk` (кроме `releases/`). Артефакты — в [GitHub Releases](https://github.com/jdmarshall95/atenboro-nav/releases).

## Симуляция заезда

Телефон/эмулятор с установленным APK `com.atenboro.nav`, `adb` в `PATH` (или `ADB=~/Android/Sdk/platform-tools/adb`).

```bash
./scripts/sim_route_drive.sh hud    # inject HUD-кадров по скрипту
./scripts/sim_route_drive.sh geo    # шаг mock GPS по полилинии (нужен mock-location app)
./scripts/sim_route_drive.sh full   # geo + мониторинг сессии/OLED (по умолчанию)
```

Без платы — mock SoftAP на хосте:

```bash
./scripts/mock_board.py                 # http://0.0.0.0:18765
adb shell setprop debug.atenboro.esp_url http://10.0.2.2:18765
./scripts/emu_mock_drive.sh
```

Чеклист: [`docs/TESTING.md`](docs/TESTING.md).

## Сборка прошивки

Нужен [PlatformIO](https://platformio.org/) (в CI: `.github/requirements-ci.txt`).

```bash
pip install platformio   # или: pip install -r .github/requirements-ci.txt
cd firmware
pio run                  # → .pio/build/nodemcuv2/firmware.bin
pio run -t upload --upload-port /dev/ttyUSB0
```

Плата: SoftAP `atenboro-nav` / `atenboro1` → `http://192.168.4.1`.

## Android

```bash
cd android
./gradlew assembleDebug
```

APK: `android/app/build/outputs/apk/debug/`.

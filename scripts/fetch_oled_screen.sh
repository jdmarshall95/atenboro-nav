#!/usr/bin/env bash
# Fetch OLED framebuffer from SoftAP and save PNG (needs python3 + pillow optional).
# Usage: ./scripts/fetch_oled_screen.sh [out.png]
set -euo pipefail
OUT="${1:-/tmp/atenboro-oled.png}"
HOST="${ESP_HOST:-192.168.4.1}"
RAW="/tmp/atenboro-oled.bin"
META=$(curl -sS -D - -o "$RAW" "http://$HOST/screen" | tr -d '\r')
echo "$META" | grep -i 'X-OLED' || true
python3 - <<PY
from pathlib import Path
raw = Path("$RAW").read_bytes()
w, h = 128, 64
assert len(raw) >= w * (h // 8), len(raw)
try:
    from PIL import Image
except ImportError:
    # ASCII fallback
    pages = h // 8
    for by in range(0, h, 2):
        row = []
        for x in range(w):
            on = 0
            for dy in range(2):
                y = by + dy
                b = raw[(y // 8) * w + x]
                if (b >> (y % 8)) & 1:
                    on += 1
            row.append('#' if on else ' ')
        print(''.join(row))
    raise SystemExit(0)
img = Image.new('1', (w, h), 0)
px = img.load()
for page in range(h // 8):
    for x in range(w):
        b = raw[page * w + x]
        for bit in range(8):
            y = page * 8 + bit
            px[x, y] = 1 if (b >> bit) & 1 else 0
img = img.convert('L').point(lambda v: 255 if v else 0)
img = img.resize((w * 4, h * 4), Image.NEAREST)
img.save("$OUT")
print("saved", "$OUT", "bytes", len(raw))
PY

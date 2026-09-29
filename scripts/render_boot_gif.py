#!/usr/bin/env python3
"""Render firmware playSplash() as dual-color OLED GIF for docs/screens/boot.gif."""
from __future__ import annotations

import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
HDR = (ROOT / "firmware/src/splash_bmp.h").read_text()
OUT = ROOT / "docs/screens/boot.gif"
VER = (ROOT / "VERSION").read_text().strip()

YELLOW = (255, 204, 0)
BLUE = (90, 180, 255)
BG = (8, 10, 14)
BEZEL = (22, 26, 34)
INK_Y = YELLOW
INK_B = BLUE

W, H = 128, 64
SCALE = 4
PAD = 16
LABEL_H = 26


def parse_bmp(name: str) -> tuple[int, int, bytes]:
    w = int(re.search(rf"{name}_W\s*=\s*(\d+)", HDR).group(1))
    h = int(re.search(rf"{name}_H\s*=\s*(\d+)", HDR).group(1))
    block = re.search(rf"{name}_BMP\[\][^=]*=\s*\{{(.*?)\}};", HDR, re.S).group(1)
    data = bytes(int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", block))
    return w, h, data


def blit(dst: Image.Image, bmp: tuple[int, int, bytes], ox: int, oy: int) -> None:
    w, h, data = bmp
    px = dst.load()
    row_bytes = (w + 7) // 8
    for y in range(h):
        for xb in range(row_bytes):
            b = data[y * row_bytes + xb]
            for bit in range(8):
                x = xb * 8 + bit
                if x >= w:
                    break
                if not (b & (0x80 >> bit)):
                    continue
                xx, yy = ox + x, oy + y
                if 0 <= xx < dst.width and 0 <= yy < dst.height:
                    px[xx, yy] = INK_Y if yy < 16 else INK_B


def clear(img: Image.Image) -> None:
    img.paste(BG, (0, 0, img.width, img.height))


def fill_rect(img: Image.Image, x: int, y: int, w: int, h: int, color) -> None:
    ImageDraw.Draw(img).rectangle([x, y, x + w - 1, y + h - 1], fill=color)


def text_w(draw: ImageDraw.ImageDraw, text: str, font) -> int:
    bbox = draw.textbbox((0, 0), text, font=font)
    return bbox[2] - bbox[0]


def main() -> None:
    logo = parse_bmp("LOGO")
    plane = parse_bmp("PLANE")
    font = ImageFont.load_default()
    try:
        cap_font = ImageFont.truetype(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13
        )
    except OSError:
        cap_font = font

    plane_ms, logo_ms, ver_ms, hold_ms = 500, 1700, 500, 500
    frames: list[Image.Image] = []
    durations: list[int] = []
    fb = Image.new("RGB", (W, H), BG)

    def push(ms: int) -> None:
        up = fb.resize((W * SCALE, H * SCALE), Image.NEAREST)
        pw, ph = up.width + PAD * 2, up.height + PAD * 2 + LABEL_H
        canvas = Image.new("RGB", (pw, ph), (12, 14, 20))
        d = ImageDraw.Draw(canvas)
        d.rounded_rectangle([2, 2, pw - 3, ph - 3], radius=10, outline=BEZEL, width=2)
        d.rounded_rectangle(
            [PAD - 4, PAD - 4, PAD + up.width + 3, PAD + up.height + 3],
            radius=4,
            fill=(0, 0, 0),
            outline=(40, 48, 60),
            width=1,
        )
        canvas.paste(up, (PAD, PAD))
        cap = "Atenboro Nav · boot"
        tw = text_w(d, cap, cap_font)
        d.text(((pw - tw) // 2, PAD + up.height + 7), cap, fill=(160, 170, 185), font=cap_font)
        frames.append(canvas.convert("P", palette=Image.ADAPTIVE, colors=48))
        durations.append(max(30, ms))

    # Plane rises into blue zone
    n_plane = max(2, plane_ms // 50)
    for i in range(n_plane + 1):
        p = i / n_plane
        y = 16 + int((1.0 - p) * 48)
        clear(fb)
        blit(fb, plane, 0, y)
        fill_rect(fb, 0, 0, W, 16, BG)
        push(plane_ms // n_plane)

    clear(fb)
    blit(fb, plane, 0, 16)
    push(60)

    # Logo wipe left→right (ease-out)
    n_logo = max(2, logo_ms // 45)
    for i in range(n_logo + 1):
        p = i / n_logo
        p = 1.0 - (1.0 - p) * (1.0 - p)
        reveal = int(p * W)
        clear(fb)
        blit(fb, plane, 0, 16)
        blit(fb, logo, 0, 0)
        if reveal < W:
            fill_rect(fb, reveal, 0, W - reveal, 16, BG)
        push(logo_ms // n_logo)

    # Version types in
    ver = f"v{VER}"
    n_ver = max(len(ver), ver_ms // 55)
    for i in range(n_ver + 1):
        p = i / n_ver
        chars = min(len(ver), int(p * len(ver) + 0.5))
        clear(fb)
        blit(fb, logo, 0, 0)
        blit(fb, plane, 0, 16)
        fill_rect(fb, 0, 54, W, 10, BG)
        d = ImageDraw.Draw(fb)
        full = d.textbbox((0, 0), ver, font=font)
        fw = full[2] - full[0]
        d.text(((W - fw) // 2, 55), ver[:chars], fill=INK_B, font=font)
        push(ver_ms // n_ver)

    clear(fb)
    blit(fb, logo, 0, 0)
    blit(fb, plane, 0, 16)
    fill_rect(fb, 0, 54, W, 10, BG)
    d = ImageDraw.Draw(fb)
    full = d.textbbox((0, 0), ver, font=font)
    fw = full[2] - full[0]
    d.text(((W - fw) // 2, 55), ver, fill=INK_B, font=font)
    push(hold_ms + 700)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        OUT,
        save_all=True,
        append_images=frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
        disposal=2,
    )
    print(f"saved {OUT} frames={len(frames)} bytes={OUT.stat().st_size}")


if __name__ == "__main__":
    main()

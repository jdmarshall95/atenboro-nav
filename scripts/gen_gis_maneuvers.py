#!/usr/bin/env python3
"""Extract 2GIS maneuver icons from the PDF catalog → PROGMEM header.

Source: private/2GIS Navigation Maneuver Images (2).pdf
Output:
  firmware/assets/gis/<codename>.png
  firmware/src/gis_maneuvers.h

Pipeline:
  1. Render PDF pages, locate gray table + label bboxes
  2. Crop largest white-ink connected component inside gray cell
     (ignores page-margin white bars and dark-green stubs)
  3. Mono 40×40 via NEAREST + keep largest CC only (no LANCZOS fringe)

Requires: poppler-utils (pdftoppm, pdftotext), Pillow.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PDF = ROOT / "private" / "2GIS Navigation Maneuver Images (2).pdf"
ASSETS = ROOT / "firmware" / "assets" / "gis"
HEADER = ROOT / "firmware" / "src" / "gis_maneuvers.h"


def is_gray(r: int, g: int, b: int) -> bool:
    return abs(r - g) < 20 and abs(g - b) < 20 and 100 <= r <= 160


def is_white(r: int, g: int, b: int) -> bool:
    return r >= 220 and g >= 220 and b >= 220


def page_labels(pdf: Path) -> list[list[tuple[float, float, float, float, str]]]:
    html = subprocess.check_output(["pdftotext", "-bbox", str(pdf), "-"], text=True)
    pages: list[list[tuple[float, float, float, float, str]]] = []
    for page_m in re.finditer(
        r'<page width="([^"]+)" height="([^"]+)">(.*?)</page>', html, re.S
    ):
        words: list[tuple[float, float, float, float, str]] = []
        for wm in re.finditer(
            r'<word xMin="([^"]+)" yMin="([^"]+)" xMax="([^"]+)" yMax="([^"]+)">([^<]*)</word>',
            page_m.group(3),
        ):
            t = wm.group(5)
            if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", t) and t not in (
                "Navigation",
                "Maneuver",
                "Images",
                "2GIS",
                "GIS",
            ):
                words.append(
                    (
                        float(wm.group(1)),
                        float(wm.group(2)),
                        float(wm.group(3)),
                        float(wm.group(4)),
                        t,
                    )
                )
        pages.append(words)
    return pages


def gray_region(im: Image.Image) -> tuple[int, int, int, int]:
    w, h = im.size
    px = im.load()
    xs: list[int] = []
    ys: list[int] = []
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            if is_gray(*px[x, y]):
                xs.append(x)
                ys.append(y)
    if not xs:
        return (0, 0, w, h)
    return (min(xs), min(ys), max(xs), max(ys))


def crop_arrow(
    im: Image.Image,
    gx0: int,
    gy0: int,
    gx1: int,
    gy1: int,
    lx0: int,
    ly0: int,
    ly1: int,
) -> Image.Image | None:
    px = im.load()
    row_cy = (ly0 + ly1) // 2
    y0 = max(gy0 + 5, row_cy - 160)
    y1 = min(gy1 - 5, row_cy + 160)
    x0 = gx0 + 8
    x1 = min(gx1 - 5, max(x0 + 40, lx0 - 25))
    pts: list[tuple[int, int]] = []
    for y in range(y0, y1 + 1):
        for x in range(x0, x1):
            if is_white(*px[x, y]):
                pts.append((x, y))
    if len(pts) < 30:
        return None
    visited: set[tuple[int, int]] = set()
    pts_set = set(pts)
    comps: list[tuple[int, int, int, int, int]] = []
    for p in pts:
        if p in visited:
            continue
        stack = [p]
        comp: list[tuple[int, int]] = []
        while stack:
            x, y = stack.pop()
            if (x, y) in visited or (x, y) not in pts_set:
                continue
            visited.add((x, y))
            comp.append((x, y))
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                stack.append((x + dx, y + dy))
        if len(comp) < 30:
            continue
        xs = [c[0] for c in comp]
        ys = [c[1] for c in comp]
        bw = max(xs) - min(xs) + 1
        bh = max(ys) - min(ys) + 1
        if bh > bw * 3 and bw < 25:
            continue
        if bw > bh * 4 and bh < 12:
            continue
        comps.append((len(comp), min(xs), min(ys), max(xs), max(ys)))
    if not comps:
        return None
    comps.sort(reverse=True)
    _, minx, miny, maxx, maxy = comps[0]
    pad = 6
    box = (
        max(gx0, minx - pad),
        max(gy0, miny - pad),
        min(gx1 + 1, maxx + pad + 1),
        min(gy1 + 1, maxy + pad + 1),
    )
    return im.crop(box)


def remove_specks(im: Image.Image, min_size: int = 8) -> Image.Image:
    px = im.load()
    w, h = im.size
    visited = [[False] * w for _ in range(h)]
    comps: list[list[tuple[int, int]]] = []
    for y in range(h):
        for x in range(w):
            if visited[y][x] or px[x, y] == 0:
                continue
            stack = [(x, y)]
            cells: list[tuple[int, int]] = []
            while stack:
                cx, cy = stack.pop()
                if cx < 0 or cy < 0 or cx >= w or cy >= h or visited[cy][cx]:
                    continue
                if px[cx, cy] == 0:
                    visited[cy][cx] = True
                    continue
                visited[cy][cx] = True
                cells.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    stack.append((cx + dx, cy + dy))
            comps.append(cells)
    if not comps:
        return im
    comps.sort(key=len, reverse=True)
    keep = set(comps[0])
    main = len(comps[0])
    for c in comps[1:]:
        if len(c) >= min_size and len(c) >= main * 0.15:
            keep.update(c)
    out = Image.new("1", (w, h), 0)
    op = out.load()
    for x, y in keep:
        op[x, y] = 1
    return out


def to_mono40(im: Image.Image) -> Image.Image:
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    bw = Image.new("1", (w, h), 0)
    bp = bw.load()
    for y in range(h):
        for x in range(w):
            if is_white(*px[x, y]):
                bp[x, y] = 1
    bbox = bw.getbbox()
    if not bbox:
        return Image.new("1", (40, 40), 0)
    bw = bw.crop(bbox)
    w, h = bw.size
    side = max(w, h)
    margin = max(2, side // 12)
    canvas = Image.new("1", (side + 2 * margin, side + 2 * margin), 0)
    canvas.paste(bw, ((side - w) // 2 + margin, (side - h) // 2 + margin))
    out = canvas.resize((40, 40), Image.Resampling.NEAREST)
    return remove_specks(out)


def bitmap_bytes(im40: Image.Image) -> bytes:
    px = im40.load()
    out = bytearray()
    for y in range(40):
        for b in range(5):
            byte = 0
            for bit in range(8):
                x = b * 8 + bit
                if x < 40 and px[x, y]:
                    byte |= 0x80 >> bit
            out.append(byte)
    return bytes(out)


def render_pages(pdf: Path, out_dir: Path, dpi: int) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(["pdftoppm", "-png", "-r", str(dpi), str(pdf), str(out_dir / "page")])
    return sorted(out_dir.glob("page-*.png"))


def extract_assets(pdf: Path, assets: Path, dpi: int) -> list[str]:
    scale = dpi / 72.0
    assets.mkdir(parents=True, exist_ok=True)
    names: list[str] = []
    with tempfile.TemporaryDirectory(prefix="gis_maneuvers_") as tmp:
        pages = render_pages(pdf, Path(tmp), dpi)
        labels = page_labels(pdf)
        for pi, words in enumerate(labels):
            if not words:
                continue
            im = Image.open(pages[pi]).convert("RGB")
            gx0, gy0, gx1, gy1 = gray_region(im)
            for x0, y0, x1, y1, name in words:
                lx0 = int(x0 * scale)
                ly0 = int(y0 * scale)
                ly1 = int(y1 * scale)
                crop = crop_arrow(im, gx0, gy0, gx1, gy1, lx0, ly0, ly1)
                if crop is None:
                    print(f"FAIL crop {name}", file=sys.stderr)
                    continue
                crop.save(assets / f"{name}.png")
                names.append(name)
    return names


def write_header(assets: Path, header: Path) -> int:
    pngs = sorted(assets.glob("*.png"))
    arrays: list[str] = []
    entries: list[tuple[str, str]] = []
    for path in pngs:
        name = path.stem
        data = bitmap_bytes(to_mono40(Image.open(path)))
        assert len(data) == 200
        ident = "BMP_" + name.upper()
        lines = [f"static const uint8_t {ident}[] PROGMEM = {{"]
        row: list[str] = []
        for v in data:
            row.append(f"0x{v:02x}")
            if len(row) == 10:
                lines.append("  " + ",".join(row) + ",")
                row = []
        if row:
            lines.append("  " + ",".join(row) + ",")
        lines.append("};")
        arrays.append("\n".join(lines))
        entries.append((name, ident))

    with header.open("w") as f:
        f.write("#pragma once\n")
        f.write("// Auto-generated by scripts/gen_gis_maneuvers.py\n")
        f.write("// white ink in gray cell, NEAREST, largest CC only\n")
        f.write("#include <Arduino.h>\n#include <string.h>\n\n")
        f.write("static const int GIS_ARROW_W = 40;\n")
        f.write("static const int GIS_ARROW_H = 40;\n\n")
        for arr in arrays:
            f.write(arr + "\n\n")
        f.write("struct GisManeuverBmp {\n  const char *codename;\n  const uint8_t *bmp;\n};\n\n")
        f.write("static const GisManeuverBmp GIS_MANEUVER_BMPS[] = {\n")
        for name, ident in entries:
            f.write(f'  {{"{name}", {ident}}},\n')
        f.write("};\n\n")
        f.write(f"static const size_t GIS_MANEUVER_BMPS_COUNT = {len(entries)};\n\n")
        f.write("inline const uint8_t *gisManeuverBitmap(const char *codename) {\n")
        f.write("  if (!codename || !*codename) return nullptr;\n")
        f.write("  for (size_t i = 0; i < GIS_MANEUVER_BMPS_COUNT; i++)\n")
        f.write(
            "    if (!strcmp(codename, GIS_MANEUVER_BMPS[i].codename)) "
            "return GIS_MANEUVER_BMPS[i].bmp;\n"
        )
        f.write("  return nullptr;\n}\n")
    return len(entries)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pdf", type=Path, default=DEFAULT_PDF)
    ap.add_argument("--dpi", type=int, default=150)
    ap.add_argument("--assets-only", action="store_true")
    args = ap.parse_args()

    if not args.assets_only:
        if not args.pdf.is_file():
            print(f"PDF not found: {args.pdf}", file=sys.stderr)
            return 1
        names = extract_assets(args.pdf, ASSETS, args.dpi)
        print(f"cropped {len(names)} icons → {ASSETS}")

    n = write_header(ASSETS, HEADER)
    print(f"wrote {HEADER} ({n} icons)")
    return 0 if n else 1


if __name__ == "__main__":
    raise SystemExit(main())

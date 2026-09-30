#!/usr/bin/env python3
"""Pack phone MOV clips of the HW-364A board into docs/screens/field/.

Default sources (override with env):
  ATENBORO_MOV_SOFTAP  — SoftAP / waiting / splash (short)
  ATENBORO_MOV_LIVE    — live nav HUD (longer)

Produces:
  board-softap.gif (+ still.jpg)
  board-live-nav.gif  — sparse highlight GIF for README
  board-live-nav.mp4  — full-rate cropped clip
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/screens/field"
WORK = Path(os.environ.get("ATENBORO_MOV_WORK", "/tmp/atenboro-mov/pack"))

MOV_SOFTAP = Path(
    os.environ.get("ATENBORO_MOV_SOFTAP", str(Path.home() / "Desktop/IMG_7120.MOV"))
)
MOV_LIVE = Path(
    os.environ.get("ATENBORO_MOV_LIVE", str(Path.home() / "Desktop/IMG_7145.MOV"))
)

# Portrait phone frames 720×1280 — board-centric crops (tuned on desk clips).
CROP_SOFTAP = (120, 520, 600, 940)
CROP_LIVE = (200, 450, 540, 960)

GIF_SIZE = (300, 250)
MP4_SIZE = (480, 400)


def _ffmpeg(args: list[str]) -> None:
    subprocess.check_call(
        ["ffmpeg", "-y", *args],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def extract(mov: Path, dest: Path, fps: float) -> list[Path]:
    dest.mkdir(parents=True, exist_ok=True)
    for p in dest.glob("f_*.png"):
        p.unlink()
    _ffmpeg(["-i", str(mov), "-vf", f"fps={fps}", str(dest / "f_%04d.png")])
    return sorted(dest.glob("f_*.png"))


def crop_resize(paths: list[Path], box: tuple[int, int, int, int], size: tuple[int, int]) -> list[Image.Image]:
    out: list[Image.Image] = []
    for p in paths:
        im = Image.open(p).convert("RGB").crop(box).resize(size, Image.Resampling.LANCZOS)
        out.append(ImageOps.autocontrast(im, cutoff=0.3))
    return out


def write_seq(frames: list[Image.Image], dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    for p in dest.glob("f_*.png"):
        p.unlink()
    for i, im in enumerate(frames, 1):
        im.save(dest / f"f_{i:04d}.png")


def to_gif(seq: Path, out: Path, fps: float, colors: int = 28) -> None:
    pal = seq / "palette.png"
    _ffmpeg(
        [
            "-framerate",
            str(fps),
            "-i",
            str(seq / "f_%04d.png"),
            "-vf",
            f"palettegen=max_colors={colors}:stats_mode=diff",
            str(pal),
        ]
    )
    _ffmpeg(
        [
            "-framerate",
            str(fps),
            "-i",
            str(seq / "f_%04d.png"),
            "-i",
            str(pal),
            "-lavfi",
            "paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
            "-loop",
            "0",
            str(out),
        ]
    )


def to_mp4(seq: Path, out: Path, fps: float) -> None:
    _ffmpeg(
        [
            "-framerate",
            str(fps),
            "-i",
            str(seq / "f_%04d.png"),
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-crf",
            "28",
            "-movflags",
            "+faststart",
            str(out),
        ]
    )


def sparse(frames: list[Image.Image], n: int = 40) -> list[Image.Image]:
    if len(frames) <= n:
        return frames
    idxs = [round(i * (len(frames) - 1) / (n - 1)) for i in range(n)]
    return [frames[i] for i in idxs]


def main() -> None:
    if not MOV_SOFTAP.is_file():
        raise SystemExit(f"missing SoftAP MOV: {MOV_SOFTAP}")
    if not MOV_LIVE.is_file():
        raise SystemExit(f"missing live MOV: {MOV_LIVE}")

    OUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)

    # SoftAP / splash — short looping GIF
    soft_png = extract(MOV_SOFTAP, WORK / "softap", fps=6)
    soft = crop_resize(soft_png, CROP_SOFTAP, GIF_SIZE)
    write_seq(soft, WORK / "seq_softap")
    to_gif(WORK / "seq_softap", OUT / "board-softap.gif", fps=6, colors=28)
    soft[len(soft) // 2].save(OUT / "board-softap-still.jpg", quality=85)

    # Live nav — MP4 full clip + sparse GIF for README
    live_png = extract(MOV_LIVE, WORK / "live", fps=8)
    live_mp4_frames = crop_resize(live_png, CROP_LIVE, MP4_SIZE)
    write_seq(live_mp4_frames, WORK / "seq_live_mp4")
    to_mp4(WORK / "seq_live_mp4", OUT / "board-live-nav.mp4", fps=8)

    live_gif_src = crop_resize(live_png, CROP_LIVE, GIF_SIZE)
    live_gif = sparse(live_gif_src, n=40)
    write_seq(live_gif, WORK / "seq_live_gif")
    to_gif(WORK / "seq_live_gif", OUT / "board-live-nav.gif", fps=6, colors=28)
    live_gif_src[len(live_gif_src) // 3].save(OUT / "board-live-nav-still.jpg", quality=85)

    for p in sorted(OUT.iterdir()):
        print(f"{p.name:28s} {p.stat().st_size / 1024:8.1f} KB")


if __name__ == "__main__":
    main()

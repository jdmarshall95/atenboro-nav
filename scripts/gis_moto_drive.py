#!/usr/bin/env python3
"""Build a 2GIS route (A→B), mirror the polyline locally, ride it like a motorcycle.

Flow:
  1. Fetch road geometry:
       - if DGIS_API_KEY is set → 2GIS Routing API (transport=motorcycle)
       - else → public OSRM (same A/B; logged as fallback)
  2. Save polyline JSON for reuse.
  3. Open the same A→B in the 2GIS Android app via deep link.
  4. Feed emulator GPS along densified points with motorcycle kinematics
     (speed ~40–55 km/h, bearing, NMEA + emu geo fix).

Usage:
  ./scripts/gis_moto_drive.py
  ./scripts/gis_moto_drive.py --from 55.75393,37.62079 --to 55.77310,37.58160
  DGIS_API_KEY=xxx ./scripts/gis_moto_drive.py --engine dgis
  ./scripts/gis_moto_drive.py --route-json /tmp/route.json --skip-open
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
GIS_PKG = "ru.dublgis.dgismobile"
DEFAULT_FROM = (55.75393, 37.62079)  # Red Square
DEFAULT_TO = (55.77310, 37.58160)  # Belorusskaya / Tverskaya corridor


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def adb(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], check=check, text=True, capture_output=True)


def screencap(path: Path) -> None:
    with open(path, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], check=False, stdout=f)


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6378137.0 * math.asin(math.sqrt(h))


def bearing_deg(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlon = lon2 - lon1
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def densify(points: list[tuple[float, float]], step_m: float) -> list[tuple[float, float]]:
    """Resample polyline to ~step_m spacing (subsample long chains; interpolate gaps)."""
    if len(points) < 2:
        return points
    out: list[tuple[float, float]] = [points[0]]
    carry = 0.0
    for i in range(1, len(points)):
        a, b = points[i - 1], points[i]
        seg = haversine_m(a, b)
        if seg < 1e-6:
            continue
        # walk along segment emitting every step_m
        dist_along = step_m - carry
        while dist_along <= seg:
            t = dist_along / seg
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            dist_along += step_m
        carry = seg - (dist_along - step_m)
    if haversine_m(out[-1], points[-1]) > 1.0:
        out.append(points[-1])
    return out


def parse_latlon(s: str) -> tuple[float, float]:
    lat_s, lon_s = s.split(",", 1)
    return float(lat_s.strip()), float(lon_s.strip())


def http_json(url: str, data: dict | None = None, timeout: float = 30.0) -> dict:
    body = None if data is None else json.dumps(data).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "atenboro-nav"},
        method="GET" if body is None else "POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_osrm(frm: tuple[float, float], to: tuple[float, float]) -> list[tuple[float, float]]:
    # OSRM wants lon,lat
    url = (
        "https://router.project-osrm.org/route/v1/driving/"
        f"{frm[1]},{frm[0]};{to[1]},{to[0]}?overview=full&geometries=geojson"
    )
    doc = http_json(url)
    if doc.get("code") != "Ok" or not doc.get("routes"):
        raise RuntimeError(f"OSRM failed: {doc.get('code')}")
    coords = doc["routes"][0]["geometry"]["coordinates"]  # [lon, lat]
    return [(lat, lon) for lon, lat in coords]


def parse_linestring(sel: str) -> list[tuple[float, float]]:
    # LINESTRING(lon lat, lon lat, ...)
    inner = sel[sel.find("(") + 1 : sel.rfind(")")]
    pts: list[tuple[float, float]] = []
    for part in inner.split(","):
        part = part.strip()
        if not part:
            continue
        lon_s, lat_s = part.split()
        pts.append((float(lat_s), float(lon_s)))
    return pts


def fetch_dgis(
    frm: tuple[float, float], to: tuple[float, float], api_key: str
) -> list[tuple[float, float]]:
    url = f"https://routing.api.2gis.com/routing/7.0.0/global?key={urllib.parse.quote(api_key)}"
    payload = {
        "points": [
            {"type": "stop", "lat": frm[0], "lon": frm[1]},
            {"type": "stop", "lat": to[0], "lon": to[1]},
        ],
        "transport": "motorcycle",
        "route_mode": "fastest",
        "traffic_mode": "jam",
        "output": "detailed",
        "locale": "ru",
        "filters": ["dirt_road", "toll_road", "ferry"],
    }
    doc = http_json(url, payload)
    result = doc.get("result") or []
    if not result:
        raise RuntimeError(f"2GIS routing empty: {doc}")
    route = result[0]
    pts: list[tuple[float, float]] = []
    for man in route.get("maneuvers") or []:
        geom = ((man.get("outcoming_path") or {}).get("geometry") or [])
        for g in geom:
            sel = g.get("selection") or ""
            if "LINESTRING" in sel.upper():
                pts.extend(parse_linestring(sel))
    if len(pts) < 2:
        # fallback: some payloads nest differently
        raw = json.dumps(route)
        for m in re.finditer(r"LINESTRING\(([^)]+)\)", raw, re.I):
            pts.extend(parse_linestring(f"LINESTRING({m.group(1)})"))
    if len(pts) < 2:
        raise RuntimeError("2GIS route has no LINESTRING geometry")
    # drop near-duplicates
    cleaned = [pts[0]]
    for p in pts[1:]:
        if haversine_m(cleaned[-1], p) >= 1.0:
            cleaned.append(p)
    return cleaned


def open_in_2gis(frm: tuple[float, float], to: tuple[float, float], transport: str = "motorcycle") -> None:
    # Deep link: lon,lat order. motorcycle may fall back to car in older clients.
    uri = (
        f"dgis://2gis.ru/routeSearch/rsType/{transport}/"
        f"from/{frm[1]},{frm[0]}/to/{to[1]},{to[0]}"
    )
    log(f"open 2GIS: {uri}")
    # dismiss stubborn 16kb dialog if present
    adb("shell", "input", "tap", "804", "2213")
    time.sleep(0.3)
    r = adb(
        "shell",
        "am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        uri,
        "-n",
        f"{GIS_PKG}/.UriReceiver",
    )
    if r.returncode != 0:
        # fallback car deep link
        uri2 = uri.replace(f"rsType/{transport}/", "rsType/car/")
        log(f"retry car deep link ({r.stderr.strip()})")
        adb(
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            uri2,
            "-n",
            f"{GIS_PKG}/.UriReceiver",
        )


def find_go_button(png: Path) -> tuple[int, int] | None:
    """Locate the green Go! pill on the 2GIS route sheet (lower half, right side)."""
    try:
        from PIL import Image
    except ImportError:
        return None
    im = Image.open(png).convert("RGB")
    w, h = im.size
    xs: list[int] = []
    ys: list[int] = []
    # Empirically Go! sits ~y 0.75–0.82 on Pixel 1080×2400; mid-map greens are noise.
    for y in range(int(h * 0.72), int(h * 0.86)):
        for x in range(int(w * 0.55), int(w * 0.95)):
            r, g, b = im.getpixel((x, y))
            if g > 150 and r < 160 and b < 140 and g > r + 20 and g > b + 30:
                xs.append(x)
                ys.append(y)
    if len(xs) < 200:
        return None
    best = None
    for y0 in range(min(ys), max(ys) - 40, 5):
        for x0 in range(min(xs), max(xs) - 80, 5):
            cnt = sum(1 for x, y in zip(xs, ys) if x0 <= x < x0 + 160 and y0 <= y < y0 + 70)
            if best is None or cnt > best[0]:
                best = (cnt, x0 + 80, y0 + 35)
    return (best[1], best[2]) if best else None


def tap_transport_chip(index: int, count: int = 6) -> None:
    """Tap a chip in the bottom transport row (0=car … 4=motorcycle on typical sheet)."""
    # Physical size assumed 1080×2400; chips evenly spaced above gesture bar.
    w, h = 1080, 2400
    y = int(h * 0.95)
    x = int(w * (index + 0.5) / count)
    log(f"tap transport chip #{index} @ {x},{y}")
    adb("shell", "input", "tap", str(x), str(y))


def tap_go(out_dir: Path, prefer_motorcycle: bool = True) -> bool:
    """Select motorcycle (optional) and press Go! via screencap pixel hunt."""
    if prefer_motorcycle:
        tap_transport_chip(4)  # motorcycle on 6-chip row
        time.sleep(1.2)
    shot = out_dir / "pre-go.png"
    screencap(shot)
    pos = find_go_button(shot)
    if not pos:
        # fallback known Pixel_7_virtual coords
        pos = (851, 1873)
        log(f"Go! detector miss — fallback tap {pos}")
    else:
        log(f"Go! @ {pos[0]},{pos[1]}")
    for _ in range(3):
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        time.sleep(0.2)
    time.sleep(1.5)
    after = out_dir / "post-go.png"
    screencap(after)
    # Success if Go pill mostly gone
    again = find_go_button(after)
    ok = again is None
    log("nav started" if ok else "Go! may still be visible — check post-go.png")
    return ok


def nmea_rmc(lat: float, lon: float, speed_kmh: float, bearing: float) -> str:
    ns, ew = ("N", "E")
    if lat < 0:
        ns, lat = "S", -lat
    if lon < 0:
        ew, lon = "W", -lon
    lat_d, lon_d = int(lat), int(lon)
    lat_m = (lat - lat_d) * 60.0
    lon_m = (lon - lon_d) * 60.0
    now = datetime.now(timezone.utc)
    t = now.strftime("%H%M%S.00")
    d = now.strftime("%d%m%y")
    spd = speed_kmh / 1.852
    body = (
        f"GPRMC,{t},A,{lat_d:02d}{lat_m:07.4f},{ns},"
        f"{lon_d:03d}{lon_m:07.4f},{ew},{spd:.1f},{bearing:.1f},{d},,,A"
    )
    cs = 0
    for ch in body:
        cs ^= ord(ch)
    return f"${body}*{cs:02X}"


def push_gps(lat: float, lon: float, speed_kmh: float, bearing: float) -> None:
    sentence = nmea_rmc(lat, lon, speed_kmh, bearing)
    adb("emu", "geo", "nmea", sentence)
    adb("emu", "geo", "fix", f"{lon}", f"{lat}")
    # backup test provider
    adb(
        "shell",
        "cmd",
        "location",
        "providers",
        "set-test-provider-location",
        "atenboro",
        "--location",
        f"{lat},{lon}",
        "--accuracy",
        "4",
        "--speed",
        f"{speed_kmh / 3.6:.2f}",
        "--bearing",
        f"{bearing:.1f}",
    )


def enable_mock_provider() -> None:
    adb("shell", "cmd", "location", "set-location-enabled", "true")
    adb("shell", "appops", "set", "com.android.shell", "android:mock_location", "allow")
    adb("shell", "cmd", "location", "providers", "remove-test-provider", "atenboro")
    adb(
        "shell",
        "cmd",
        "location",
        "providers",
        "add-test-provider",
        "atenboro",
        "--supportsSpeed",
        "--supportsBearing",
        "--supportsAltitude",
    )
    adb("shell", "cmd", "location", "providers", "set-test-provider-enabled", "atenboro", "true")


def moto_speed_profile(i: int, n: int, turn_deg: float, cruise: float) -> float:
    """City motorcycle; cruise is typically 70–90 so cameras arm sooner."""
    if i < 4:
        return max(25.0, cruise * 0.4 + i * 8.0)
    if i > n - 6:
        return max(15.0, cruise - (i - (n - 6)) * 10.0)
    if turn_deg > 50:
        return max(35.0, cruise * 0.55)
    if turn_deg > 25:
        return max(45.0, cruise * 0.7)
    return cruise


def ride(
    points: list[tuple[float, float]],
    out_dir: Path,
    wait_start_s: float,
    cruise_kmh: float = 85.0,
    step_m: float = 35.0,
) -> None:
    enable_mock_provider()
    if wait_start_s > 0:
        log(f"wait {wait_start_s:.0f}s — start navigation in 2GIS (Поехали / Finish)")
        time.sleep(wait_start_s)

    # snap to start briefly so app locks onto route
    lat0, lon0 = points[0]
    push_gps(lat0, lon0, cruise_kmh * 0.5, 0.0)
    time.sleep(0.6)

    n = len(points)
    log(f"ride begin points={n} cruise={cruise_kmh:.0f}km/h step≈{step_m:.0f}m")
    for i in range(n):
        lat, lon = points[i]
        if i + 1 < n:
            brg = bearing_deg(points[i], points[i + 1])
            turn = 0.0
            if i > 0:
                prev = bearing_deg(points[i - 1], points[i])
                turn = abs((brg - prev + 180) % 360 - 180)
        else:
            brg = bearing_deg(points[i - 1], points[i]) if i else 0.0
            turn = 0.0
        spd = moto_speed_profile(i, n, turn, cruise_kmh)
        push_gps(lat, lon, spd, brg)
        if i % 20 == 0 or i == n - 1:
            log(f"gps [{i+1}/{n}] {lat:.5f},{lon:.5f} {spd:.0f}km/h brg={brg:.0f}")
            screencap(out_dir / f"screen-{i+1:04d}.png")
        # Physics dt for step at current speed, floored for fast lab runs
        dt = max(0.12, min(0.55, (step_m / max(spd, 20.0)) * 3.6))
        time.sleep(dt)
    log("ride done")


def save_route(
    out_dir: Path,
    engine: str,
    frm: tuple[float, float],
    to: tuple[float, float],
    raw: list[tuple[float, float]],
    dense: list[tuple[float, float]],
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "route.json"
    length = sum(haversine_m(dense[i], dense[i + 1]) for i in range(len(dense) - 1))
    doc = {
        "engine": engine,
        "from": {"lat": frm[0], "lon": frm[1]},
        "to": {"lat": to[0], "lon": to[1]},
        "length_m": round(length, 1),
        "raw_points": [{"lat": a, "lon": b} for a, b in raw],
        "points": [{"lat": a, "lon": b} for a, b in dense],
    }
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
    # also CSV for quick inspect
    csv = out_dir / "route.csv"
    csv.write_text(
        "lat,lon\n" + "\n".join(f"{a},{b}" for a, b in dense) + "\n", encoding="utf-8"
    )
    log(f"saved {path} ({len(dense)} pts, {length/1000:.2f} km, engine={engine})")
    return path


def load_route(path: Path) -> tuple[tuple[float, float], tuple[float, float], list[tuple[float, float]], list[tuple[float, float]], str]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    frm = (doc["from"]["lat"], doc["from"]["lon"])
    to = (doc["to"]["lat"], doc["to"]["lon"])
    pts = [(p["lat"], p["lon"]) for p in doc["points"]]
    raw = [(p["lat"], p["lon"]) for p in doc.get("raw_points") or doc["points"]]
    return frm, to, pts, raw, doc.get("engine", "file")


def main(argv: Iterable[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from", dest="frm", default=f"{DEFAULT_FROM[0]},{DEFAULT_FROM[1]}")
    ap.add_argument("--to", dest="to", default=f"{DEFAULT_TO[0]},{DEFAULT_TO[1]}")
    ap.add_argument("--engine", choices=("auto", "dgis", "osrm"), default="auto")
    ap.add_argument("--step-m", type=float, default=40.0, help="densify step along route (larger = fewer/faster ticks)")
    ap.add_argument("--cruise-kmh", type=float, default=85.0, help="motorcycle cruise speed for GPS feed")
    ap.add_argument("--out", default="", help="artifact dir (default /tmp/atenboro-moto-...)")
    ap.add_argument("--route-json", default="", help="reuse saved route.json")
    ap.add_argument("--skip-open", action="store_true", help="do not open deep link in 2GIS")
    ap.add_argument("--wait-start", type=float, default=3.0, help="seconds after open before auto Go / ride")
    ap.add_argument("--no-tap-go", action="store_true", help="do not auto-tap motorcycle + Go!")
    ap.add_argument("--no-ride", action="store_true", help="only build/save/open route")
    args = ap.parse_args(list(argv) if argv is not None else None)

    out_dir = Path(args.out) if args.out else Path(f"/tmp/atenboro-moto-{time.strftime('%Y%m%d-%H%M%S')}")
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.route_json:
        frm, to, _dense_old, raw, engine = load_route(Path(args.route_json))
        dense = densify(raw, args.step_m)
        log(f"loaded route engine={engine} raw={len(raw)} → dense={len(dense)} step={args.step_m}m")
    else:
        frm = parse_latlon(args.frm)
        to = parse_latlon(args.to)
        key = os.environ.get("DGIS_API_KEY", "").strip()
        engine = args.engine
        if engine == "auto":
            engine = "dgis" if key else "osrm"
        if engine == "dgis":
            if not key:
                log("ERROR: --engine dgis needs DGIS_API_KEY")
                return 2
            log("fetch 2GIS motorcycle route")
            raw = fetch_dgis(frm, to, key)
        else:
            log("fetch OSRM driving route (fallback — set DGIS_API_KEY for real 2GIS geometry)")
            raw = fetch_osrm(frm, to)
        dense = densify(raw, args.step_m)
        save_route(out_dir, engine, frm, to, raw, dense)

    if not args.skip_open:
        open_in_2gis(frm, to, transport="motorcycle")
        time.sleep(2.0)
        open_in_2gis(frm, to, transport="car")  # ensure older APK shows route sheet
        adb("shell", "input", "tap", "804", "2213")  # Don't Show Again if dialog
        time.sleep(max(0.0, args.wait_start))
        if not args.no_tap_go:
            tap_go(out_dir, prefer_motorcycle=True)

    if args.no_ride:
        log(f"artifacts → {out_dir}")
        return 0

    # GPS feed starts immediately after Go (or after wait if --no-tap-go)
    ride(
        dense,
        out_dir,
        wait_start_s=0.0 if not args.no_tap_go else args.wait_start,
        cruise_kmh=args.cruise_kmh,
        step_m=args.step_m,
    )

    # dump atenboro + mock snapshots
    sess = adb(
        "shell",
        "run-as",
        "com.atenboro.nav",
        "sh",
        "-c",
        "ls -t files/debug/session-*.ndjson 2>/dev/null | head -1 | xargs tail -n 40",
    )
    (out_dir / "session-tail.txt").write_text(sess.stdout or sess.stderr or "", encoding="utf-8")
    try:
        mock = http_json("http://127.0.0.1:18765/debug")
        (out_dir / "mock-final.json").write_text(json.dumps(mock, ensure_ascii=False, indent=2), encoding="utf-8")
        nav = mock.get("nav") or {}
        log(f"mock nav={json.dumps(nav, ensure_ascii=False)}")
    except Exception as e:
        log(f"mock debug skip: {e}")
    log(f"artifacts → {out_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except urllib.error.HTTPError as e:
        log(f"HTTP {e.code}: {e.read()[:300]!r}")
        raise SystemExit(1)
    except Exception as e:
        log(f"FAIL: {e}")
        raise SystemExit(1)

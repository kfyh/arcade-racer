#!/usr/bin/env python3
"""
Generates 4 new top-down test tracks (track.png + track_meta.json) under
app/src/main/assets/tracks/:
  1. hourglass_sharp  - Sharper hourglass curves (harder to straight-line)
  2. square_right     - Square circuit with 90-degree right-hand turns (CW)
  3. d_left           - D-shaped circuit with left-hand turns (CCW)
  4. horseshoe        - Classic Ironman Super Off Road style horseshoe loop
"""
import json
import math
import os
import random
import struct
import zlib
from typing import Dict, List, Tuple

SURFACE_PALETTES = {
    "ASPHALT": {
        "bg_rgb": (106, 154, 88),
        "road_rgb": (70, 72, 78),
        "shoulder_rgb": (184, 60, 50),
    },
    "DIRT": {
        "bg_rgb": (204, 162, 104),
        "road_rgb": (138, 86, 48),
        "shoulder_rgb": (92, 54, 28),
    },
    "SAND": {
        "bg_rgb": (236, 212, 150),
        "road_rgb": (196, 154, 92),
        "shoulder_rgb": (150, 114, 64),
    },
    "MUD": {
        "bg_rgb": (82, 92, 54),
        "road_rgb": (72, 50, 34),
        "shoulder_rgb": (46, 32, 20),
    },
    "ICE": {
        "bg_rgb": (212, 234, 248),
        "road_rgb": (152, 202, 234),
        "shoulder_rgb": (92, 146, 188),
    },
}

WIDTH = 512
HEIGHT = 512
ASSETS_TRACKS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app",
    "src",
    "main",
    "assets",
    "tracks",
)


def write_png(path: str, width: int, height: int, rgb_bytes: bytearray) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw_rows = bytearray()
    stride = width * 3
    for y in range(height):
        raw_rows.append(0)
        start = y * stride
        raw_rows.extend(rgb_bytes[start : start + stride])

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(raw_rows), level=6)
    png_data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png_data)


def dist_point_to_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    abx, aby = bx - ax, by - ay
    len_sq = abx * abx + aby * aby
    if len_sq < 1e-10:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * abx + (py - ay) * aby) / len_sq))
    return math.hypot(px - (ax + t * abx), py - (ay + t * aby))


def dist_to_closed_polyline(nx: float, ny: float, pts: List[Tuple[float, float]]) -> float:
    n = len(pts)
    best = 1e9
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        d = dist_point_to_segment(nx, ny, ax, ay, bx, by)
        if d < best:
            best = d
    return best


def resample_closed_polyline(pts: List[Tuple[float, float]], count: int) -> List[Tuple[float, float]]:
    """Uniformly resamples a closed polyline into `count` evenly spaced waypoints."""
    n = len(pts)
    cum = [0.0]
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        cum.append(cum[-1] + math.hypot(bx - ax, by - ay))
    total = cum[-1]
    out = []
    for k in range(count):
        target = (k / float(count)) * total
        for i in range(n):
            if cum[i] <= target <= cum[i + 1]:
                seg_len = cum[i + 1] - cum[i]
                t = (target - cum[i]) / seg_len if seg_len > 1e-9 else 0.0
                ax, ay = pts[i]
                bx, by = pts[(i + 1) % n]
                out.append((ax + t * (bx - ax), ay + t * (by - ay)))
                break
    return out


def catmull_rom_closed(ctrl: List[Tuple[float, float]], steps_per_seg: int = 16) -> List[Tuple[float, float]]:
    n = len(ctrl)
    pts = []
    for i in range(n):
        p0 = ctrl[(i - 1) % n]
        p1 = ctrl[i]
        p2 = ctrl[(i + 1) % n]
        p3 = ctrl[(i + 2) % n]
        for s in range(steps_per_seg):
            t = s / float(steps_per_seg)
            t2 = t * t
            t3 = t2 * t
            x = 0.5 * (
                (2 * p1[0])
                + (-p0[0] + p2[0]) * t
                + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3
            )
            y = 0.5 * (
                (2 * p1[1])
                + (-p0[1] + p2[1]) * t
                + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3
            )
            pts.append((x, y))
    return pts


def build_hourglass_sharp(surface: str = "ICE") -> Dict:
    # Deeper pinch at waist + sharper end bulbs so you cannot straight-line it
    ctrl = [
        (0.50, 0.59),  # bottom waist (start)
        (0.64, 0.72),
        (0.84, 0.80),  # bottom-right sharp bulb
        (0.87, 0.50),  # right end
        (0.84, 0.20),  # top-right sharp bulb
        (0.64, 0.28),
        (0.50, 0.41),  # top waist
        (0.36, 0.28),
        (0.16, 0.20),  # top-left sharp bulb
        (0.13, 0.50),  # left end
        (0.16, 0.80),  # bottom-left sharp bulb
        (0.36, 0.72),
    ]
    dense = catmull_rom_closed(ctrl, steps_per_seg=14)
    waypoints = resample_closed_polyline(dense, 20)
    # Start at (0.50, 0.59) heading right (0.0); waypoint 0 is just ahead
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES[surface]
    return {
        "id": "hourglass_sharp",
        "name": f"Sharp Hourglass ({surface.title()})",
        "laps": 6,
        "defaultSurface": surface,
        "road_half": 0.056,
        "trackWidthNorm": 0.12,
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.50, 0.59),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [(0.27, 0.50, 0.032), (0.73, 0.50, 0.032)],
    }


def build_square_right(surface: str = "MUD") -> Dict:
    # Clockwise square with crisp 90-degree right-hand turns
    # Start on top straight at (0.50, 0.19) heading right (0.0)
    pts = []
    # Top straight -> top-right arc -> right straight -> bottom-right arc -> bottom -> bottom-left -> left -> top-left
    r = 0.08
    x0, x1 = 0.20, 0.80
    y0, y1 = 0.20, 0.80

    def add_line(ax, ay, bx, by, steps=16):
        for i in range(steps):
            t = i / float(steps)
            pts.append((ax + t * (bx - ax), ay + t * (by - ay)))

    def add_arc(cx, cy, a0, a1, steps=10):
        for i in range(steps):
            a = a0 + (i / float(steps)) * (a1 - a0)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))

    # Top straight (start at x=0.50 -> x1-r)
    add_line(0.50, y0, x1 - r, y0, 10)
    add_arc(x1 - r, y0 + r, -math.pi / 2, 0.0, 10)  # right turn down
    add_line(x1, y0 + r, x1, y1 - r, 16)
    add_arc(x1 - r, y1 - r, 0.0, math.pi / 2, 10)  # right turn left
    add_line(x1 - r, y1, x0 + r, y1, 16)
    add_arc(x0 + r, y1 - r, math.pi / 2, math.pi, 10)  # right turn up
    add_line(x0, y1 - r, x0, y0 + r, 16)
    add_arc(x0 + r, y0 + r, math.pi, 1.5 * math.pi, 10)  # right turn right
    add_line(x0 + r, y0, 0.50, y0, 10)

    waypoints = resample_closed_polyline(pts, 16)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES[surface]
    return {
        "id": "square_right",
        "name": f"Square Circuit ({surface.title()})",
        "laps": 6,
        "defaultSurface": surface,
        "road_half": 0.064,
        "trackWidthNorm": 0.13,
        "dense": pts,
        "waypoints": waypoints,
        "start_center": (0.50, y0),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [(0.50, 0.50, 0.045)],
    }


def build_d_left() -> Dict:
    # Michigan International Speedway style D-shaped tri-oval (CCW, all left-hand turns):
    # Straight horizontal backstretch at top; curved D-oval frontstretch at bottom with start/finish at apex
    ctrl = [
        (0.50, 0.82),  # start/finish at bottom tri-oval apex (heading 0.0 -> right)
        (0.72, 0.77),  # frontstretch curve into Turn 1
        (0.86, 0.62),  # Turn 1
        (0.86, 0.36),  # Turn 2 exit
        (0.70, 0.22),  # backstretch entry
        (0.50, 0.22),  # straight backstretch
        (0.30, 0.22),  # backstretch exit
        (0.14, 0.36),  # Turn 3 entry
        (0.14, 0.62),  # Turn 4
        (0.28, 0.77),  # frontstretch dogleg back to start/finish
    ]
    dense = catmull_rom_closed(ctrl, steps_per_seg=14)
    waypoints = resample_closed_polyline(dense, 16)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["ASPHALT"]
    return {
        "id": "d_left",
        "name": "Michigan D-Oval (Road)",
        "laps": 6,
        "defaultSurface": "ASPHALT",
        "road_half": 0.064,
        "trackWidthNorm": 0.13,
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.50, 0.82),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": (180, 180, 175),
        "obstacles": [(0.50, 0.50, 0.042)],
    }


def build_horseshoe() -> Dict:
    # Classic Ironman Super Off Road style horseshoe:
    # Wide bottom & side sweepers, two tight top hairpins, and a deep center U-bay
    ctrl = [
        (0.50, 0.83),  # bottom straight (start)
        (0.72, 0.83),
        (0.85, 0.68),  # right sweeper
        (0.86, 0.32),
        (0.76, 0.17),  # top-right hairpin
        (0.62, 0.24),
        (0.60, 0.45),  # dive into center bay
        (0.50, 0.57),  # bottom of center horseshoe bay
        (0.40, 0.45),  # climb out of center bay
        (0.38, 0.24),
        (0.24, 0.17),  # top-left hairpin
        (0.14, 0.32),
        (0.15, 0.68),  # left sweeper
        (0.28, 0.83),
    ]
    dense = catmull_rom_closed(ctrl, steps_per_seg=14)
    waypoints = resample_closed_polyline(dense, 20)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["DIRT"]
    return {
        "id": "horseshoe",
        "name": "Ironman Horseshoe (Dirt)",
        "laps": 6,
        "defaultSurface": "DIRT",
        "road_half": 0.058,
        "trackWidthNorm": 0.125,
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.50, 0.83),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [(0.50, 0.27, 0.036)],
    }


def build_phillip_island() -> Dict:
    # Phillip Island Grand Prix Circuit traced directly from Phillip_Island_Grand_Prix_Circuit_v2022.svg.webp
    # Counter-clockwise: Gardner Straight (heading 0.0 -> right) -> T1 Doohan -> T2 Southern Loop ->
    # T3 Stoner -> T4 Miller hairpin -> T5-6 Siberia -> T7-8 Back Straight -> T9 Lukey Heights ->
    # T10 MG hairpin -> T11-12 long sweeping turn back onto Gardner Straight.
    ctrl = [
        (0.42, 0.62),  # Start/finish on Gardner Straight (heading 0.0 -> right)
        (0.70, 0.62),  # End of Gardner Straight
        (0.79, 0.66),  # T1 Doohan Corner
        (0.84, 0.78),  # Down-leg into Southern Loop
        (0.87, 0.91),  # T2 Southern Loop entry
        (0.94, 0.93),  # T2 Southern Loop apex
        (0.96, 0.80),  # Up-leg out of Southern Loop (wide separation from down-leg)
        (0.91, 0.64),
        (0.82, 0.52),  # T3 Stoner Corner
        (0.71, 0.48),
        (0.61, 0.46),  # T4 Miller Corner entry
        (0.58, 0.41),  # T4 Miller Corner apex
        (0.63, 0.36),  # T4 Miller Corner exit
        (0.74, 0.36),  # T5
        (0.79, 0.31),  # T6 Siberia apex
        (0.76, 0.25),  # T6 Siberia exit
        (0.65, 0.21),  # T7 Back Straight
        (0.52, 0.22),  # T8 Back Straight
        (0.43, 0.17),
        (0.34, 0.08),  # T9 Lukey Heights
        (0.26, 0.09),
        (0.21, 0.19),  # T10 MG entry
        (0.19, 0.28),  # T10 MG apex
        (0.14, 0.28),
        (0.10, 0.22),  # T11
        (0.06, 0.26),  # T11-12 long sweeping turn
        (0.04, 0.42),
        (0.05, 0.55),  # T12 onto main straight
        (0.12, 0.61),
        (0.24, 0.62),  # Gardner Straight entry
    ]
    dense = catmull_rom_closed(ctrl, steps_per_seg=10)
    waypoints = resample_closed_polyline(dense, 36)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["ASPHALT"]
    return {
        "id": "phillip_island",
        "name": "Phillip Island (Road)",
        "laps": 4,
        "size": 1024,
        "worldScale": 2.5,
        "defaultSurface": "ASPHALT",
        "road_half": 0.026,
        "trackWidthNorm": 0.056,
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.42, 0.62),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [],
    }


def build_c_horseshoe() -> Dict:
    # 90-degree rotated horseshoe ("C" shape), 4x area (worldScale = 2.0, 1024x1024), DIRT
    # Long straights, 3 wide hairpins (bottom-right tip, inner bay, top-right tip),
    # and 2 crisp 90-degree turns (top-left & bottom-left). Wide separation prevents cutting.
    ctrl = [
        (0.48, 0.88),  # Start/finish on bottom long straight (heading 0.0 -> right)
        (0.74, 0.88),
        (0.87, 0.85),  # Wide hairpin 1 (bottom-right tip)
        (0.90, 0.75),
        (0.84, 0.65),
        (0.66, 0.63),  # Lower-inner straight
        (0.48, 0.61),
        (0.37, 0.56),  # Wide hairpin 2 (inner C bay)
        (0.36, 0.44),
        (0.48, 0.39),
        (0.66, 0.37),  # Upper-inner straight
        (0.84, 0.35),
        (0.90, 0.25),  # Wide hairpin 3 (top-right tip)
        (0.87, 0.15),
        (0.74, 0.12),
        (0.48, 0.12),  # Top long straight
        (0.22, 0.12),
        (0.12, 0.14),  # 90-degree turn 1 (top-left)
        (0.10, 0.24),
        (0.10, 0.50),  # Left long straight
        (0.10, 0.76),
        (0.12, 0.86),  # 90-degree turn 2 (bottom-left)
        (0.22, 0.88),
    ]
    dense = catmull_rom_closed(ctrl, steps_per_seg=10)
    waypoints = resample_closed_polyline(dense, 32)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["DIRT"]
    return {
        "id": "c_horseshoe",
        "name": "Grand C-Horseshoe (Dirt)",
        "laps": 4,
        "size": 1024,
        "worldScale": 2.0,
        "defaultSurface": "DIRT",
        "road_half": 0.030,
        "trackWidthNorm": 0.066,
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.48, 0.88),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [],
    }


def render_and_save(spec: Dict) -> None:
    track_dir = os.path.join(ASSETS_TRACKS_DIR, spec["id"])
    png_path = os.path.join(track_dir, "track.png")
    meta_path = os.path.join(track_dir, "track_meta.json")

    img_size = spec.get("size", WIDTH)
    world_scale = spec.get("worldScale", 1.0)
    dense = spec["dense"]
    road_half = spec["road_half"]
    shoulder_half = road_half + (0.010 / world_scale)
    scx, scy = spec["start_center"]
    sh = spec["start_heading"]
    # Perpendicular to start heading for start-line strip
    px, py = -math.sin(sh), math.cos(sh)
    fx, fy = math.cos(sh), math.sin(sh)

    bg_r, bg_g, bg_b = spec["bg_rgb"]
    rd_r, rd_g, rd_b = spec["road_rgb"]
    sh_r, sh_g, sh_b = spec["shoulder_rgb"]

    # Two small finish-line gate posts on either side of the checkered line
    post_offset = road_half + (0.014 / world_scale)
    post_radius = round(0.015 / world_scale, 4)
    gate_posts = [
        (round(scx - px * post_offset, 4), round(scy - py * post_offset, 4), post_radius),
        (round(scx + px * post_offset, 4), round(scy + py * post_offset, 4), post_radius),
    ]
    all_obstacles = list(spec["obstacles"]) + gate_posts

    buf = bytearray(img_size * img_size * 3)
    for y in range(img_size):
        ny = y / float(img_size)
        for x in range(img_size):
            nx = x / float(img_size)
            noise = ((x * 73856093 ^ y * 19349663) & 0xFF) % 13 - 6
            r, g, b = bg_r + noise, bg_g + noise, bg_b + noise

            d = dist_to_closed_polyline(nx, ny, dense)
            if d < shoulder_half:
                r, g, b = sh_r + noise // 2, sh_g + noise // 2, sh_b + noise // 2
            if d < road_half:
                r, g, b = rd_r + noise // 2, rd_g + noise // 2, rd_b + noise // 2

            # Start/Finish checkered line across road at start_center
            dx, dy = nx - scx, ny - scy
            along_fwd = abs(dx * fx + dy * fy)
            along_perp = abs(dx * px + dy * py)
            if along_fwd <= (0.014 / world_scale) and along_perp <= road_half:
                checker = ((x // 8) + (y // 8)) % 2 == 0
                if checker:
                    r, g, b = 245, 245, 245
                else:
                    r, g, b = 30, 30, 30

            for ox, oy, orad in spec["obstacles"]:
                if math.hypot(nx - ox, ny - oy) <= orad:
                    r, g, b = 78, 74, 70

            for ox, oy, orad in gate_posts:
                d_post = math.hypot(nx - ox, ny - oy)
                if d_post <= orad:
                    if d_post <= orad * 0.65:
                        r, g, b = 240, 95, 35
                    else:
                        r, g, b = 245, 245, 245

            idx = (y * img_size + x) * 3
            buf[idx] = max(0, min(255, r))
            buf[idx + 1] = max(0, min(255, g))
            buf[idx + 2] = max(0, min(255, b))

    write_png(png_path, img_size, img_size, buf)

    meta = {
        "name": spec["name"],
        "laps": spec["laps"],
        "trackWidthNorm": spec["trackWidthNorm"],
        "worldScale": world_scale,
        "defaultSurface": spec.get("defaultSurface", "DIRT"),
        "waypoints": [{"x": round(wx, 4), "y": round(wy, 4)} for (wx, wy) in spec["waypoints"]],
        "startLine": {
            "a": {"x": round(scx - px * road_half, 4), "y": round(scy - py * road_half, 4)},
            "b": {"x": round(scx + px * road_half, 4), "y": round(scy + py * road_half, 4)},
            "heading": round(sh, 4),
        },
        "obstacles": [
            {"pos": {"x": ox, "y": oy}, "radius": orad} for (ox, oy, orad) in all_obstacles
        ],
        "ramps": [],
        "gaps": [],
        "surfaces": [],
    }
    os.makedirs(track_dir, exist_ok=True)
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print(f"Generated {spec['id']}: {len(meta['waypoints'])} waypoints -> {track_dir}")


if __name__ == "__main__":
    extra_surfaces = random.sample(["SAND", "MUD", "ICE"], 2)
    builders = [
        build_hourglass_sharp(extra_surfaces[0]),
        build_square_right(extra_surfaces[1]),
        build_d_left(),
        build_horseshoe(),
        build_c_horseshoe(),
        build_phillip_island(),
    ]
    for spec in builders:
        render_and_save(spec)

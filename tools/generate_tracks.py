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
BASE_MAP_PX = 512
ROAD_HALF_PX = 30.0
SHOULDER_PAD_PX = 5.0
TRACK_WIDTH_PX = 64.0
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
    # Michigan International Speedway (CCW, all left-hand turns), 3x scale (worldScale = 3.0, 1024x1024):
    # Flipped vertically from references/Michigan_International_Speedway_2024.svg.webp (longer curved outer loop):
    # - Completely straight bottom spine (y = 0.71, start/finish at center)
    # - True semicircular end turns (r = 0.21)
    # - Gentle large-radius (R = 0.67) curved top stretch (peaking at y = 0.228), G1-tangent to both turns.
    pts: List[Tuple[float, float]] = []
    y_bot = 0.71
    x_left = 0.27
    x_right = 0.73
    r_end = 0.21
    cy_end = y_bot - r_end  # 0.50

    # Top gentle arc joins the end turns at angle a_join = -pi/3 (-60 deg)
    a_join = -math.pi / 3.0
    jx_r = x_right + r_end * math.cos(a_join)  # 0.835
    jy = cy_end + r_end * math.sin(a_join)     # ~0.3181
    R_top = (jx_r - 0.50) / math.cos(a_join)   # 0.670
    cy_top = jy - R_top * math.sin(a_join)     # ~0.8983 (peak at y = cy_top - R_top = ~0.2283)

    def add_line(ax: float, ay: float, bx: float, by: float, steps: int = 18) -> None:
        for i in range(steps):
            t = i / float(steps)
            pts.append((ax + t * (bx - ax), ay + t * (by - ay)))

    def add_arc(cx: float, cy: float, r: float, a0: float, a1: float, steps: int = 36) -> None:
        for i in range(steps):
            a = a0 + (i / float(steps)) * (a1 - a0)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))

    # 1. Right half of straight bottom spine: (0.50, 0.71) -> (0.73, 0.71)
    add_line(0.50, y_bot, x_right, y_bot, steps=16)
    # 2. Right turn (Turn 3-4 flipped): +pi/2 -> -pi/3
    add_arc(x_right, cy_end, r_end, math.pi / 2.0, a_join, steps=40)
    # 3. Gentle curved top stretch (longer curved frontstretch flipped): -pi/3 -> -2*pi/3
    add_arc(0.50, cy_top, R_top, a_join, -math.pi - a_join, steps=44)
    # 4. Left turn (Turn 1-2 flipped): -2*pi/3 -> -3*pi/2 (i.e. +pi/2)
    add_arc(x_left, cy_end, r_end, -math.pi - a_join, -1.5 * math.pi, steps=40)
    # 5. Left half of straight bottom spine: (0.27, 0.71) -> (0.50, 0.71)
    add_line(x_left, y_bot, 0.50, y_bot, steps=16)

    waypoints = resample_closed_polyline(pts, 32)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["ASPHALT"]
    return {
        "id": "d_left",
        "name": "Michigan D-Oval (Road)",
        "laps": 4,
        "size": 1536,
        "defaultSurface": "ASPHALT",
        "dense": pts,
        "waypoints": waypoints,
        "start_center": (0.50, y_bot),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": (180, 180, 175),
        "obstacles": [],
    }


def build_pill_small() -> Dict:
    # Compact horizontal pill oval (CCW, worldScale = 1.0, 512x512) scaled to 0.85x around (0.50, 0.50)
    # so it visibly sits inside the Pixel 9 Pro Fold inner screen with clear margin.
    pts: List[Tuple[float, float]] = []
    scale = 0.85
    x_left = 0.50 - 0.18 * scale   # 0.347
    x_right = 0.50 + 0.18 * scale  # 0.653
    r_end = 0.22 * scale           # 0.187
    cy = 0.50
    y_bot = cy + r_end             # 0.687
    y_top = cy - r_end             # 0.313

    def add_line(ax: float, ay: float, bx: float, by: float, steps: int = 16) -> None:
        for i in range(steps):
            t = i / float(steps)
            pts.append((ax + t * (bx - ax), ay + t * (by - ay)))

    def add_arc(cx: float, cy_a: float, r: float, a0: float, a1: float, steps: int = 32) -> None:
        for i in range(steps):
            a = a0 + (i / float(steps)) * (a1 - a0)
            pts.append((cx + r * math.cos(a), cy_a + r * math.sin(a)))

    # Bottom straight -> right semicircle -> top straight -> left semicircle -> bottom straight
    add_line(0.50, y_bot, x_right, y_bot, steps=12)
    add_arc(x_right, cy, r_end, math.pi / 2.0, -math.pi / 2.0, steps=36)
    add_line(x_right, y_top, x_left, y_top, steps=24)
    add_arc(x_left, cy, r_end, -math.pi / 2.0, -1.5 * math.pi, steps=36)
    add_line(x_left, y_bot, 0.50, y_bot, steps=12)

    waypoints = resample_closed_polyline(pts, 20)
    waypoints = waypoints[1:] + waypoints[:1]
    pal = SURFACE_PALETTES["DIRT"]
    return {
        "id": "pill_small",
        "name": "Small Pill (Dirt)",
        "laps": 6,
        "size": 436,
        "defaultSurface": "DIRT",
        "dense": pts,
        "waypoints": waypoints,
        "start_center": (0.50, y_bot),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [],
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
        "ramps": [
            # Left straight jump (outer half-width, car travels downward)
            {"x": 0.089, "y": 0.46, "width": 0.056, "height": 0.08, "impulse": 0.45, "dir": "down"},
            # Right straight jump (outer half-width, car travels upward)
            {"x": 0.855, "y": 0.46, "width": 0.056, "height": 0.08, "impulse": 0.45, "dir": "up"},
        ],
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
        "size": 1280,
        "defaultSurface": "ASPHALT",
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
        "defaultSurface": "DIRT",
        "dense": dense,
        "waypoints": waypoints,
        "start_center": (0.48, 0.88),
        "start_heading": 0.0,
        "bg_rgb": pal["bg_rgb"],
        "road_rgb": pal["road_rgb"],
        "shoulder_rgb": pal["shoulder_rgb"],
        "obstacles": [],
    }


def build_segment_grid(pts: List[Tuple[float, float]], margin: float, grid_n: int = 32):
    """Spatial grid of polyline segments for fast per-pixel distance queries."""
    cells: List[List[Tuple[float, float, float, float, float]]] = [[] for _ in range(grid_n * grid_n)]
    n = len(pts)
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        abx, aby = bx - ax, by - ay
        len_sq = abx * abx + aby * aby
        seg = (ax, ay, bx, by, len_sq)
        min_gx = max(0, min(grid_n - 1, int((min(ax, bx) - margin) * grid_n)))
        max_gx = max(0, min(grid_n - 1, int((max(ax, bx) + margin) * grid_n)))
        min_gy = max(0, min(grid_n - 1, int((min(ay, by) - margin) * grid_n)))
        max_gy = max(0, min(grid_n - 1, int((max(ay, by) + margin) * grid_n)))
        for gy in range(min_gy, max_gy + 1):
            row_off = gy * grid_n
            for gx in range(min_gx, max_gx + 1):
                cells[row_off + gx].append(seg)
    return cells, grid_n


def render_and_save(spec: Dict) -> None:
    track_dir = os.path.join(ASSETS_TRACKS_DIR, spec["id"])
    png_path = os.path.join(track_dir, "track.png")
    meta_path = os.path.join(track_dir, "track_meta.json")

    img_size = spec.get("size", WIDTH)
    dense = spec["dense"]
    # Uniform pixel-based road thickness (~3-lane highway) across all tracks
    road_half = ROAD_HALF_PX / float(img_size)
    shoulder_half = (ROAD_HALF_PX + SHOULDER_PAD_PX) / float(img_size)
    track_width_norm = round(TRACK_WIDTH_PX / float(img_size), 4)
    scx, scy = spec["start_center"]
    sh = spec["start_heading"]

    bg_r, bg_g, bg_b = spec["bg_rgb"]
    rd_r, rd_g, rd_b = spec["road_rgb"]
    sh_r, sh_g, sh_b = spec["shoulder_rgb"]

    all_obstacles = list(spec["obstacles"])
    ramps = list(spec.get("ramps", []))
    cells, grid_n = build_segment_grid(dense, shoulder_half + 0.002)

    buf = bytearray(img_size * img_size * 3)
    for y in range(img_size):
        ny = y / float(img_size)
        gy = min(grid_n - 1, int(ny * grid_n))
        row_off = gy * grid_n
        for x in range(img_size):
            nx = x / float(img_size)
            noise = ((x * 73856093 ^ y * 19349663) & 0xFF) % 13 - 6
            r, g, b = bg_r + noise, bg_g + noise, bg_b + noise

            gx = min(grid_n - 1, int(nx * grid_n))
            segs = cells[row_off + gx]
            if segs:
                best_sq = 1e9
                for ax, ay, bx, by, len_sq in segs:
                    if len_sq < 1e-10:
                        dx, dy = nx - ax, ny - ay
                    else:
                        t = ((nx - ax) * (bx - ax) + (ny - ay) * (by - ay)) / len_sq
                        if t < 0.0:
                            t = 0.0
                        elif t > 1.0:
                            t = 1.0
                        dx = nx - (ax + t * (bx - ax))
                        dy = ny - (ay + t * (by - ay))
                    d_sq = dx * dx + dy * dy
                    if d_sq < best_sq:
                        best_sq = d_sq
                d = math.sqrt(best_sq)
                if d < shoulder_half:
                    r, g, b = sh_r + noise // 2, sh_g + noise // 2, sh_b + noise // 2
                if d < road_half:
                    r, g, b = rd_r + noise // 2, rd_g + noise // 2, rd_b + noise // 2

            for ramp in ramps:
                rx, ry, rw, rh = ramp["x"], ramp["y"], ramp["width"], ramp["height"]
                rdir = ramp.get("dir", "up")
                lip_shadow = 0.016
                side_shadow = 0.008

                # Progress along ramp slope: 0.0 at ground entry -> 1.0 at high launch lip
                if rdir == "down":
                    in_ramp = rx <= nx <= rx + rw and ry <= ny <= ry + rh
                    slope_t = (ny - ry) / max(rh, 1e-6)
                    in_lip_shadow = (rx + 0.002 <= nx <= rx + rw + side_shadow) and (ry + rh < ny <= ry + rh + lip_shadow)
                    in_side_shadow = (rx + rw < nx <= rx + rw + side_shadow) and (ry + 0.006 <= ny <= ry + rh)
                    shadow_dist = (ny - (ry + rh)) / lip_shadow if in_lip_shadow else (nx - (rx + rw)) / side_shadow
                else:
                    in_ramp = rx <= nx <= rx + rw and ry <= ny <= ry + rh
                    slope_t = (ry + rh - ny) / max(rh, 1e-6)
                    in_lip_shadow = (rx + 0.002 <= nx <= rx + rw + side_shadow) and (ry - lip_shadow <= ny < ry)
                    in_side_shadow = (rx + rw < nx <= rx + rw + side_shadow) and (ry <= ny <= ry + rh - 0.006)
                    shadow_dist = (ry - ny) / lip_shadow if in_lip_shadow else (nx - (rx + rw)) / side_shadow

                if in_ramp:
                    tx = (nx - rx) / max(rw, 1e-6)
                    # Side rails for crisp ramp edges
                    if tx < 0.08 or tx > 0.92:
                        r, g, b = 96, 62, 34
                    elif slope_t > 0.93:
                        # Bright launch lip edge
                        r, g, b = 235, 195, 130
                    else:
                        # Slope gradient (darker at base, brighter near lip) + subtle plank texture
                        plank = int(slope_t * 8) % 2 == 0
                        plank_delta = 10 if plank else -6
                        lift = int(slope_t * 58)
                        r = 136 + lift + plank_delta + noise // 2
                        g = 92 + int(lift * 0.85) + plank_delta + noise // 2
                        b = 48 + int(lift * 0.65) + noise // 2

                        # Directional chevron pointing toward launch lip
                        chevron_y = 0.68 - abs(tx - 0.5) * 0.45
                        if abs(slope_t - chevron_y) < 0.07 and 0.22 < tx < 0.78:
                            r, g, b = 242, 206, 92
                elif in_lip_shadow or in_side_shadow:
                    # Drop shadow cast onto the road beneath the raised lip/edge
                    atten = max(0.35, min(0.75, 0.38 + 0.38 * shadow_dist))
                    r = int(r * atten)
                    g = int(g * atten)
                    b = int(b * atten)

            for ox, oy, orad in spec["obstacles"]:
                if math.hypot(nx - ox, ny - oy) <= orad:
                    r, g, b = 78, 74, 70

            idx = (y * img_size + x) * 3
            buf[idx] = max(0, min(255, r))
            buf[idx + 1] = max(0, min(255, g))
            buf[idx + 2] = max(0, min(255, b))

    write_png(png_path, img_size, img_size, buf)

    meta = {
        "name": spec["name"],
        "laps": spec["laps"],
        "trackWidthNorm": track_width_norm,
        "defaultSurface": spec.get("defaultSurface", "DIRT"),
        "waypoints": [{"x": round(wx, 4), "y": round(wy, 4)} for (wx, wy) in spec["waypoints"]],
        "finishLine": {
            "pos": {"x": round(scx, 4), "y": round(scy, 4)},
            "heading": round(sh, 4),
        },
        "obstacles": [
            {"pos": {"x": ox, "y": oy}, "radius": orad} for (ox, oy, orad) in all_obstacles
        ],
        "ramps": [
            {k: v for k, v in ramp.items() if k != "dir"} for ramp in ramps
        ],
        "gaps": [],
        "surfaces": [],
    }
    os.makedirs(track_dir, exist_ok=True)
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print(f"Generated {spec['id']}: {len(meta['waypoints'])} waypoints -> {track_dir}")


if __name__ == "__main__":
    builders = [
        build_hourglass_sharp("SAND"),
        build_square_right("ICE"),
        build_d_left(),
        build_pill_small(),
        build_horseshoe(),
        build_c_horseshoe(),
        build_phillip_island(),
    ]
    for spec in builders:
        render_and_save(spec)

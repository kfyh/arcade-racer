#!/usr/bin/env python3
"""
Build-time AI Map Analyzer for Offroad Prototype.
Inspects a top-down track PNG and outputs track_meta.json in normalized [0.0..1.0] coordinates.

Usage:
  python3 tools/analyze_map.py --image <path/to/track.png> --out <path/to/track_meta.json> [--provider gemini|openai|heuristic]
"""
import argparse
import base64
import json
import math
import os
import struct
import urllib.request
import zlib
from typing import Dict, List, Tuple


SCHEMA_PROMPT = """Analyze this top-down 2D racing track image and return ONLY valid JSON matching this exact schema (all coordinates normalized 0.0 to 1.0, top-left is 0,0):
{
  "name": "Track Name",
  "laps": 3,
  "trackWidthNorm": 0.14,
  "waypoints": [{"x": 0.65, "y": 0.78}, ...],
  "startLine": {"a": {"x": 0.50, "y": 0.71}, "b": {"x": 0.50, "y": 0.85}, "heading": 0.0},
  "obstacles": [{"pos": {"x": 0.84, "y": 0.30}, "radius": 0.028}],
  "ramps": [{"x": 0.56, "y": 0.15, "width": 0.08, "height": 0.14, "impulse": 0.52}],
  "gaps": [{"x": 0.42, "y": 0.15, "width": 0.11, "height": 0.14}],
  "surfaces": []
}
Return 12 to 16 ordered waypoints following the driving loop starting just after the startLine."""


def read_png_rgb(path: str) -> Tuple[int, int, List[Tuple[int, int, int]]]:
    """Minimal pure-Python PNG reader for 8-bit RGB/RGBA PNGs (filter 0..4)."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Not a valid PNG file: {path}")

    pos = 8
    width = height = bit_depth = color_type = 0
    idat_chunks: List[bytes] = []

    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        ctype = data[pos + 4 : pos + 8]
        chunk_data = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", chunk_data)
        elif ctype == b"IDAT":
            idat_chunks.append(chunk_data)
        elif ctype == b"IEND":
            break

    if bit_depth != 8 or color_type not in (2, 6):
        raise ValueError(f"Unsupported PNG format (bit_depth={bit_depth}, color_type={color_type})")

    bpp = 3 if color_type == 2 else 4
    raw = zlib.decompress(b"".join(idat_chunks))
    stride = width * bpp
    pixels: List[Tuple[int, int, int]] = []
    prev_row = bytearray(stride)

    def paeth(a: int, b: int, c: int) -> int:
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        if pa <= pb and pa <= pc:
            return a
        return b if pb <= pc else c

    idx = 0
    for _ in range(height):
        flt = raw[idx]
        idx += 1
        row = bytearray(raw[idx : idx + stride])
        idx += stride
        if flt == 1:
            for i in range(bpp, stride):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif flt == 2:
            for i in range(stride):
                row[i] = (row[i] + prev_row[i]) & 0xFF
        elif flt == 3:
            for i in range(stride):
                left = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((left + prev_row[i]) >> 1)) & 0xFF
        elif flt == 4:
            for i in range(stride):
                left = row[i - bpp] if i >= bpp else 0
                up = prev_row[i]
                up_left = prev_row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + paeth(left, up, up_left)) & 0xFF
        prev_row = row
        for x in range(0, stride, bpp):
            pixels.append((row[x], row[x + 1], row[x + 2]))

    return width, height, pixels


def connected_components_bbox(
    coords: List[Tuple[int, int]], width: int, height: int, min_pixels: int = 25
) -> List[Tuple[float, float, float, float, int]]:
    """Groups nearby pixel coordinates into bounding boxes in normalized [0..1] space."""
    if not coords:
        return []
    point_set = set(coords)
    visited = set()
    boxes = []
    for pt in coords:
        if pt in visited:
            continue
        stack = [pt]
        visited.add(pt)
        min_x = max_x = pt[0]
        min_y = max_y = pt[1]
        count = 0
        while stack:
            cx, cy = stack.pop()
            count += 1
            if cx < min_x:
                min_x = cx
            if cx > max_x:
                max_x = cx
            if cy < min_y:
                min_y = cy
            if cy > max_y:
                max_y = cy
            for dx in (-2, -1, 0, 1, 2):
                for dy in (-2, -1, 0, 1, 2):
                    nb = (cx + dx, cy + dy)
                    if nb in point_set and nb not in visited:
                        visited.add(nb)
                        stack.append(nb)
        if count >= min_pixels:
            boxes.append(
                (
                    min_x / float(width),
                    min_y / float(height),
                    (max_x - min_x + 1) / float(width),
                    (max_y - min_y + 1) / float(height),
                    count,
                )
            )
    return boxes


def analyze_heuristic(image_path: str) -> Dict:
    width, height, pixels = read_png_rgb(image_path)

    road_pts: List[Tuple[int, int]] = []
    ramp_pts: List[Tuple[int, int]] = []
    gap_pts: List[Tuple[int, int]] = []
    obs_pts: List[Tuple[int, int]] = []

    # Sample every 2 pixels for fast pure-Python analysis
    step = 2
    for y in range(0, height, step):
        for x in range(0, width, step):
            r, g, b = pixels[y * width + x]
            # Dirt road: warm medium-dark brown (r > g > b, r in 115..170)
            if 115 <= r <= 170 and 75 <= g <= 125 and 40 <= b <= 85 and (r - b) > 45:
                road_pts.append((x, y))
            # Ramp: bright yellow or orange hazard stripe
            elif (r > 200 and g > 150 and b < 60) or (r > 170 and 90 <= g <= 145 and b < 45):
                ramp_pts.append((x, y))
            # Gap: deep blue water/canyon (b > 110, r < 60)
            elif b > 110 and r < 60 and g < 120:
                gap_pts.append((x, y))
            # Rock obstacles: neutral dark grey (60..95, low saturation)
            elif 60 <= r <= 95 and 60 <= g <= 95 and 55 <= b <= 90 and max(r, g, b) - min(r, g, b) < 16:
                obs_pts.append((x, y))

    # Extract 16 ordered CCW waypoints around road centroid (0.5, 0.5)
    num_sectors = 16
    sector_sums = [[0.0, 0.0, 0] for _ in range(num_sectors)]
    for x, y in road_pts:
        nx = x / float(width)
        ny = y / float(height)
        angle = math.atan2(ny - 0.5, nx - 0.5)  # [-pi, pi]
        # Order starting from bottom (pi/2) going counter-clockwise:
        # bottom (pi/2) -> right (0) -> top (-pi/2) -> left (pi)
        rel = (0.5 * math.pi - angle) % (2.0 * math.pi)
        sec = int((rel / (2.0 * math.pi)) * num_sectors) % num_sectors
        sector_sums[sec][0] += nx
        sector_sums[sec][1] += ny
        sector_sums[sec][2] += 1

    waypoints = []
    for sx, sy, cnt in sector_sums:
        if cnt > 0:
            waypoints.append({"x": round(sx / cnt, 4), "y": round(sy / cnt, 4)})

    # Rotate waypoints so index 0 is just ahead of the bottom-center start line (heading = 0, moving right)
    if waypoints:
        start_idx = min(
            range(len(waypoints)),
            key=lambda i: (waypoints[i]["x"] - 0.62) ** 2 + (waypoints[i]["y"] - 0.68) ** 2,
        )
        waypoints = waypoints[start_idx:] + waypoints[:start_idx]

    ramps = [
        {"x": round(x, 4), "y": round(y, 4), "width": round(w, 4), "height": round(h, 4), "impulse": 0.52}
        for (x, y, w, h, _) in connected_components_bbox(ramp_pts, width, height, min_pixels=15)
    ]
    gaps = [
        {"x": round(x, 4), "y": round(y, 4), "width": round(w, 4), "height": round(h, 4)}
        for (x, y, w, h, _) in connected_components_bbox(gap_pts, width, height, min_pixels=15)
    ]
    obstacles = []
    for x, y, w, h, _ in connected_components_bbox(obs_pts, width, height, min_pixels=10):
        obstacles.append(
            {
                "pos": {"x": round(x + w * 0.5, 4), "y": round(y + h * 0.5, 4)},
                "radius": round(max(w, h) * 0.5, 4),
            }
        )

    track_name = os.path.basename(os.path.dirname(os.path.abspath(image_path))).replace("_", " ").title()
    return {
        "name": track_name or "Desert Loop",
        "laps": 3,
        "trackWidthNorm": 0.14,
        "waypoints": waypoints,
        "startLine": {
            "a": {"x": 0.50, "y": 0.58},
            "b": {"x": 0.50, "y": 0.72},
            "heading": 0.0,
        },
        "obstacles": obstacles,
        "ramps": ramps,
        "gaps": gaps,
        "surfaces": [],
    }


def analyze_gemini(image_path: str) -> Dict:
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY not set")
    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")

    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}"
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": SCHEMA_PROMPT},
                    {"inline_data": {"mime_type": "image/png", "data": b64}},
                ]
            }
        ],
        "generationConfig": {"response_mime_type": "application/json"},
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    return json.loads(text)


def analyze_openai(image_path: str) -> Dict:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY not set")
    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")

    url = "https://api.openai.com/v1/chat/completions"
    payload = {
        "model": "gpt-4o-mini",
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": SCHEMA_PROMPT},
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
                ],
            }
        ],
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"])


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze a top-down track PNG into track_meta.json")
    parser.add_argument("--image", required=True, help="Path to track.png")
    parser.add_argument("--out", required=True, help="Output path for track_meta.json")
    parser.add_argument(
        "--provider",
        choices=["gemini", "openai", "heuristic"],
        default="heuristic",
        help="Vision provider (defaults to heuristic)",
    )
    args = parser.parse_args()

    if args.provider == "gemini" and os.environ.get("GEMINI_API_KEY"):
        meta = analyze_gemini(args.image)
    elif args.provider == "openai" and os.environ.get("OPENAI_API_KEY"):
        meta = analyze_openai(args.image)
    else:
        meta = analyze_heuristic(args.image)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print(f"Wrote track metadata ({len(meta.get('waypoints', []))} waypoints, "
          f"{len(meta.get('obstacles', []))} obstacles, "
          f"{len(meta.get('ramps', []))} ramps, "
          f"{len(meta.get('gaps', []))} gaps) -> {args.out}")


if __name__ == "__main__":
    main()

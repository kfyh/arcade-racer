#!/usr/bin/env python3
"""
Generates a clean 512x512 top-down flat hourglass-shaped test track PNG
(desert_loop/track.png) to test both left and right turns, with no jumps.
"""
import math
import os
import struct
import zlib

WIDTH = 512
HEIGHT = 512


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


def hourglass_samples(n: int = 256):
    pts = []
    for i in range(n):
        t = (2.0 * math.pi * i) / n
        c = math.cos(t)
        s = math.sin(t)
        x = 0.50 + 0.36 * c
        y = 0.50 + 0.58 * s * (0.26 + 0.74 * (c * c))
        pts.append((x, y))
    return pts


def road_center_distance(nx: float, ny: float, samples) -> float:
    best_d2 = 1e9
    for sx, sy in samples:
        dx = nx - sx
        dy = ny - sy
        d2 = dx * dx + dy * dy
        if d2 < best_d2:
            best_d2 = d2
    return math.sqrt(best_d2)


def generate_track(out_path: str) -> None:
    buf = bytearray(WIDTH * HEIGHT * 3)
    samples = hourglass_samples(300)

    # Decorative rock obstacles inside the left and right bulb centers (off the road)
    obstacles = [
        (0.26, 0.50, 0.032),
        (0.74, 0.50, 0.032),
    ]

    for y in range(HEIGHT):
        ny = y / float(HEIGHT)
        for x in range(WIDTH):
            nx = x / float(WIDTH)

            noise = ((x * 73856093 ^ y * 19349663) & 0xFF) % 13 - 6
            r, g, b = 216 + noise, 184 + noise, 128 + noise

            d_road = road_center_distance(nx, ny, samples)
            if d_road < 0.078:
                r, g, b = 98 + noise // 2, 66 + noise // 2, 38 + noise // 2
            if d_road < 0.068:
                r, g, b = 142 + noise // 2, 98 + noise // 2, 60 + noise // 2

            for ox, oy, orad in obstacles:
                if math.hypot(nx - ox, ny - oy) <= orad:
                    r, g, b = 78, 74, 70

            idx = (y * WIDTH + x) * 3
            buf[idx] = max(0, min(255, r))
            buf[idx + 1] = max(0, min(255, g))
            buf[idx + 2] = max(0, min(255, b))

    write_png(out_path, WIDTH, HEIGHT, buf)
    print(f"Generated flat hourglass track image: {out_path}")


if __name__ == "__main__":
    default_out = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app",
        "src",
        "main",
        "assets",
        "tracks",
        "desert_loop",
        "track.png",
    )
    generate_track(default_out)

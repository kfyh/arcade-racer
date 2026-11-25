# Track Design & Data Structure Guide

Tracks are loaded from `app/src/main/assets/tracks/<track_id>/` by [`TrackRepository`](../app/src/main/java/com/offroad/data/TrackRepository.kt) and defined in [`Models.kt`](../app/src/main/java/com/offroad/engine/Models.kt).

## 1. Directory Layout

Each track folder under `app/src/main/assets/tracks/<track_id>/` contains two files:

- **`track.png`**: Square top-down bitmap built to a uniform pixel scale (`512 px = 1.0` base screen unit). For example, `436x436` = `0.85x`, `512x512` = `1.0x`, `1024x1024` = `2.0x`, `1280x1280` = `2.5x`, `1536x1536` = `3.0x`.
- **`track_meta.json`**: JSON serialized as `TrackMetadata` (`ignoreUnknownKeys = true`).

Tracks are discovered automatically in alphabetical order of `<track_id>`.

---

## 2. Coordinate System & Pixel-Derived Scale

- All coordinates in `track_meta.json` are normalized to a **square `[0.0 .. 1.0] × [0.0 .. 1.0]`** space, where `(0, 0)` is top-left and `(1, 1)` is bottom-right.
- **`worldScale`** is **not** stored in `track_meta.json` (`@Transient` in `TrackMetadata`). Instead, `TrackRepository.loadTrack()` derives it automatically from `track.png` width (`bitmap.width / 512f`, clamped to `>= 0.1f`):
  - **Camera**: `GameCanvas` renders the world at `worldSize = maxOf(w, h) * worldScale`.
  - **Car & Physics Scaling**: `invScale = 1.0 / worldScale` scales car dimensions, spawn spacing, `maxSpeed` (`CarStats().maxSpeed / worldScale`), and finish-line pole radius (`0.015 * invScale`) so cars keep constant visual size and speed across all map sizes.
  - **Uniform 3-Lane Road Thickness**: In `tools/generate_tracks.py`, all tracks are painted with `ROAD_HALF_PX = 30` (`60px` road width), `SHOULDER_PAD_PX = 5`, and `trackWidthNorm = 64.0 / img_size`.

---

## 3. `track_meta.json` Schema (`TrackMetadata`)

```json
{
  "name": "Track Display Name",
  "laps": 4,
  "trackWidthNorm": 0.125,
  "defaultSurface": "DIRT",
  "waypoints": [ { "x": 0.65, "y": 0.83 } ],
  "finishLine": {
    "pos": { "x": 0.5, "y": 0.83 },
    "heading": 0.0
  },
  "obstacles": [
    { "pos": { "x": 0.5, "y": 0.27 }, "radius": 0.036 }
  ],
  "ramps": [
    { "x": 0.089, "y": 0.46, "width": 0.056, "height": 0.08, "impulse": 0.45 }
  ],
  "gaps": [
    { "x": 0.45, "y": 0.45, "width": 0.10, "height": 0.06 }
  ],
  "surfaces": [
    { "x": 0.20, "y": 0.20, "width": 0.15, "height": 0.10, "surface": "MUD" }
  ]
}
```

### Top-Level Fields
| Field | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | *(required)* | Display name (e.g., `"Michigan D-Oval (Road)"`). |
| `laps` | `Int` | `3` | Total laps in a race. |
| `trackWidthNorm` | `Float` | `0.14` | Normalized road width (`64.0 / img_size`); used for off-track detection, AI road margin, and finish-line gate width. |
| `defaultSurface` | `SurfaceType` | `"DIRT"` | Base surface used anywhere outside explicit `surfaces` zones. |

### `waypoints` (`List<Vec2>`)
- Ordered closed loop of `{ "x": Float, "y": Float }` points along the track centerline.
- Used for **AI navigation**, **lap validation** (checkpoint progression), and **menu topo thumbnails** (`MenuScreen.kt`).
- **Ordering convention**: `waypoints[0]` should be the **first waypoint ahead of `finishLine.pos`** in the race direction, and the final waypoint should be near/at `finishLine.pos`.

### `finishLine` (`FinishLineNode`)
- `pos`: `{ "x": Float, "y": Float }` center of the start/finish line.
- `heading`: `Float` in radians (`0.0` = pointing right `+x`, `pi/2` = down `+y`, `-pi/2` = up `-y`).
- The engine automatically derives:
  - **Gate half-width**: `trackWidthNorm * 0.75`.
  - **Finish-line poles**: Two `CircleObstacle`s placed at the ends of the gate (`finishLinePoles()`), included in `allObstacles()`.

### `surfaces` (`List<SurfaceZone>`) & `SurfaceType`
Each `SurfaceZone` is an axis-aligned rectangle `{ "x", "y", "width", "height", "surface" }` that overrides `defaultSurface` inside its bounds:

| `SurfaceType` | `grip` | `drag` | Feel |
|---|---|---|---|
| `ASPHALT` | `1.0` | `0.982` | Highest grip, fast roll |
| `DIRT` | `0.78` | `0.973` | Balanced off-road slide |
| `SAND` | `0.55` | `0.945` | Loose grip, heavier drag |
| `MUD` | `0.45` | `0.925` | Low grip, heavy slowdown |
| `ICE` | `0.25` | `0.992` | Very low grip, long slide |

### `obstacles` (`List<CircleObstacle>`)
- Circular solid barriers `{ "pos": { "x", "y" }, "radius": Float }` (e.g., infield boulders).
- Grounded cars (`z <= 0.02`) collide and bounce off `obstacles` plus the auto-generated `finishLinePoles()`.

### `ramps` (`List<RampZone>`)
- Axis-aligned jump triggers `{ "x", "y", "width", "height", "impulse": 0.45 }`.
- When a grounded car (`z <= 0`) enters a ramp with speed `> 0.12`, it launches with `vz = impulse` under `GRAVITY = 1.4`.
- Paint corresponding ramp visuals onto `track.png` so jumps are visible in-game.

### `gaps` (`List<GapZone>`)
- Axis-aligned hazard zones `{ "x", "y", "width", "height" }` (e.g., water/chasms).
- If a car is grounded (`z <= 0.02`) inside a gap, it resets to `lastSafePos` with zero velocity; airborne cars (`z > 0.02`) clear the gap.

---

## 4. Generating Tracks with `tools/generate_tracks.py`

1. Add or edit a `build_<track_id>()` function in [`tools/generate_tracks.py`](generate_tracks.py) returning a spec dict (`id`, `name`, `laps`, `size`, `worldScale`, `defaultSurface`, `road_half`, `trackWidthNorm`, `dense`, `waypoints`, `start_center`, `start_heading`, `bg_rgb`, `road_rgb`, `shoulder_rgb`, `obstacles`, and optional `ramps`).
2. `render_and_save(spec)` renders `track.png` (background, shoulders, road, ramps, obstacles) and writes `track_meta.json` (stripping render-only helper keys like `ramp["dir"]`).
3. Run a single track or all tracks with:
   ```bash
   python3 -c 'import tools.generate_tracks as gt; gt.render_and_save(gt.build_<track_id>())'
   ```
   or regenerate all built-in tracks:
   ```bash
   python3 tools/generate_tracks.py
   ```

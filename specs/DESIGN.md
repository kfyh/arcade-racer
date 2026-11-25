# Super Offroad / Micro Machines Clone — Design Document

## 1. Overview & Core Pillars
A top-down 2D arcade racer for **Android** (with first-class **Waydroid** compatibility), inspired by *Super Offroad* and *Micro Machines*.
- **4-Car Arcade Chaos**: 1 human player vs. 3 AI racers on compact, readable top-down tracks.
- **Accessible Touch-First Driving**: Auto-accelerate with **Left**, **Right**, **Boost**, and **Brake/Reverse** virtual buttons, backed by subtle **assisted driving** so touch controls feel crisp rather than punishing.
- **Pseudo-Verticality**: Controlled 2D jumps over ramps and gaps using height (`z`) and vertical velocity (`vz`) rather than full 3D physics.
- **Build-Time AI Map Pipeline**: Tracks are authored as 2D images. During the build phase, an AI vision/analysis script generates a metadata overlay JSON (track centerline, waypoints, obstacles, ramps, gaps) that the game loads alongside the image.
- **Adaptive Form-Factor UI**: Automatically switches between **Overlay Controls** (tablets/foldables with square or 4:3 aspect ratios) and **Handheld Side-Controls** (tall mobile screens).

---

## 2. Platform & Waydroid Advice

### Recommended Tech Stack
- **Language**: Kotlin
- **UI & Layout**: Jetpack Compose (handles adaptive screen sizes, menus, HUD, and virtual buttons cleanly)
- **Game Loop & Rendering**: Fixed-timestep 60 Hz coroutine/frame loop rendering onto Compose `Canvas` (or `SurfaceView` if needed later). Pure Kotlin simulation layer (`engine/`) with **zero `android.*` imports** so all physics and AI can be unit-tested on JVM.
- **Why this stack for Qwen 7B + Waydroid**:
  - Standard Gradle + Kotlin + Compose has zero native (NDK/C++) dependencies, avoiding `arm64-v8a` vs `x86_64` ABI mismatches on Waydroid.
  - No third-party game engine boilerplate (e.g., LibGDX/Godot export pipelines) that small coding models often misconfigure.

### Waydroid-Specific Considerations
Waydroid runs Android in a Linux container (typically `x86_64` on desktop/laptops) using Wayland and Mesa. To ensure smooth development and playtesting on Waydroid:
1. **Multi-Touch Limitation**: A desktop mouse in Waydroid only simulates **single-touch**. You cannot hold *Left* and *Boost* simultaneously with a mouse.
   - **Solution**: Implement `KeyEvent` handling alongside touch buttons from Day 1:
     - `A` / `D` or `Left` / `Right` Arrow = Steer Left / Right
     - `Space` or `W` / `Up` Arrow = Boost
     - `S` or `Down` Arrow = Brake / Reverse
   - This also automatically supports physical keyboards, Bluetooth controllers, and Chromebook/Android Desktop mode.
2. **No NDK / Pure JVM**: Keep everything in Kotlin/Java so the APK runs identically on `x86_64` Waydroid and `arm64-v8a` phones without ABI splits.
3. **Window Resizing**: Waydroid allows arbitrary window resizing, making it ideal for testing both **4:3 / 1:1** (tablet/foldable) and **20:9** (tall mobile) layouts on one machine.
4. **Graphics Backend**: Stick to standard Android `Canvas` / Compose drawing rather than custom Vulkan or complex OpenGL ES shaders, which can hit Mesa/virgl driver quirks in Waydroid.

---

## 3. Adaptive Screen & Control Layouts

The app locks to **Landscape** for racing (or adapts dynamically if unfolded) and classifies the viewport by `aspectRatio = max(width, height) / min(width, height)`:

### Mode A: Tablets & Foldables (`aspectRatio < 1.55`, e.g. 4:3, 1:1, 5:4)
- **Layout**: Game viewport fills **100% of the screen**.
- **Controls**: Semi-transparent **overlay** virtual buttons positioned in the bottom corners:
  - **Bottom-Left**: `[ < LEFT ]` `[ RIGHT > ]`
  - **Bottom-Right**: `[ BRAKE ]` `[ BOOST ]`
- **HUD**: Compact top bar (Lap `1/3`, Position `1st`, Boost meter).

### Mode B: Tall Mobile Screens (`aspectRatio >= 1.55`, e.g. 16:9, 19.5:9, 20:9)
- **Layout**: **Handheld console style** (like a GBA / Switch / Backbone).
  - **Center**: Square or 4:3 game viewport (`aspectRatio`-clamped), unobstructed by fingers.
  - **Left Pillar**: `[ < LEFT ]` and `[ RIGHT > ]` stacked or side-by-side in the left bezel area, plus race position/lap indicator above.
  - **Right Pillar**: `[ BOOST ]` (large primary button) and `[ BRAKE ]` in the right bezel area, plus boost bar above.
- **Why**: On tall 20:9 phones, putting the game in the center keeps the entire track visible and prevents thumbs from covering cars or upcoming turns.

---

## 4. Core Gameplay & Driving Model

### Controls & Auto-Accelerate
- **Auto-Accelerate**: Cars always apply forward throttle unless **Brake/Reverse** is held.
- **Left / Right**: Rotates the car's `heading` angle (radians) at `turnRate`.
- **Boost**: Consumes `boostFuel` (regenerates slowly over time or via track pickups later) to multiply target speed and acceleration.
- **Brake / Reverse**: Decelerates the car; if speed drops near `0`, transitions into reverse.

### 2D Arcade Drift & Momentum
To capture the *Super Offroad* / *Micro Machines* slide:
- Each car tracks both a `heading` (where the nose points) and a `velocity` vector `(vx, vy)`.
- Every tick, `velocity` blends toward the `heading` direction based on `grip` (lateral traction):
  - High grip = snappy turns.
  - Low grip (or sharp turning at speed) = tail slides out into an arcade drift.

### Assisted Driving
Touch buttons are binary (pressed/not pressed), so pure simulation can feel twitchy. Assisted driving adds three subtle helpers:
1. **Soft Wall Deflection**: Hitting a track boundary slides/bounces the car along the tangent instead of stopping it dead.
2. **Gentle Waypoint Nudge**: When no steering button is pressed and the car is moving forward, apply a very light angular nudge toward the next waypoint's direction so straightaways don't require constant micro-taps.
3. **Anti-Stuck Recovery**: If a car's speed stays near zero while off-track for >1.5s, gently nudge/respawn it onto the nearest valid waypoint.

### Pseudo-Verticality (Controlled 2D Jumps)
No 3D physics engine is used. Instead, each car has:
- `z: Float` (height above ground, `0f` = grounded)
- `vz: Float` (vertical velocity)
- `gravity: Float` (constant downward pull while `z > 0f`)

**Behavior**:
1. **Ramps**: Track metadata defines ramp zones (`RampZone`). Entering a ramp while moving above a minimum speed sets `vz = ramp.impulse`.
2. **Airborne State (`z > 0f`)**:
   - **Visuals**: Car sprite scales up by `1f + z * 0.015f`. A dark oval shadow is drawn at the car's ground `(x, y)` position, slightly shrunk and offset.
   - **Gameplay**: Car ignores ground obstacles, surface friction, and gap hazards while `z > 0f`. Steering rate is reduced to 30% mid-air.
3. **Gaps / Pits**: Track metadata defines gap zones (`GapZone`). If a car enters a gap with `z <= 0f`, it triggers a brief fall animation and respawns at the last passed waypoint. Clearing the gap with `z > 0f` lands safely on the other side.

---

## 5. Build-Time AI Map Pipeline

Maps are authored as top-down 2D images (e.g., `assets/tracks/desert_loop/track.png`).
During the **build phase** (before compiling the APK, or when adding a new map), a Python CLI tool (`tools/analyze_map.py`) processes the image:
1. **AI / Vision Detection**: Sends the image to a vision-capable model (or runs a local OpenCV/PIL color-contour fallback) with a strict JSON schema prompt to identify:
   - `trackBounds` / drivable road mask or polygon
   - `waypoints`: Ordered `(x, y)` centerline points in normalized `[0.0, 1.0]` coordinates
   - `startLine`: Segment `(x1, y1, x2, y2)` and start heading
   - `obstacles`: List of `{ x, y, radius }`
   - `ramps`: List of `{ x, y, width, height, angle, impulse }`
   - `gaps`: List of `{ x, y, width, height }`
2. **Metadata Overlay (`track_meta.json`)**: Saved right next to `track.png`. Because coordinates are normalized (`0.0` to `1.0`), the metadata scales cleanly to any resolution.
3. **Human Verification**: An in-game **Debug Overlay** toggle draws the `track_meta.json` waypoints, obstacles, ramps, and gaps directly on top of `track.png` so any AI detection errors can be spotted and tweaked in JSON immediately.

---

## 6. Architecture & Future-Proofing

### Package Layout
```text
app/src/main/java/com/offroad/
├── engine/          # Pure Kotlin (NO android.* imports)
│   ├── Math2D.kt
│   ├── Models.kt
│   ├── PhysicsStep.kt
│   ├── AiDriver.kt
│   └── RaceManager.kt
├── data/            # Track & asset loading (reads track_meta.json)
│   └── TrackRepository.kt
└── ui/              # Jetpack Compose UI, Canvas renderer, Adaptive Controls
    ├── GameLoop.kt
    ├── GameCanvas.kt
    ├── AdaptiveControls.kt
    ├── GameScreen.kt
    └── MenuScreen.kt
tools/
└── analyze_map.py   # Build-phase AI track/obstacle detector
```

### Designed Extension Points (Future Plans)
Out of scope for the initial prototype, but explicitly modeled in `Models.kt` and `TrackMetadata` so they plug in without rewrites:
1. **Upgradeable Cars**: `CarStats(maxSpeed, acceleration, turnRate, grip, boostCapacity, boostPower)` is passed into `PhysicsStep` rather than hardcoded constants. Garage/upgrade screens will simply modify `CarStats`.
2. **Car Service / Wear & Tear**: `CarCondition(tireWear, engineHealth, suspensionHealth)` can be attached to `CarState` as a multiplier on `CarStats`, with a post-race service screen to restore values to `1.0f`.
3. **Different Road Surfaces**: `SurfaceType` enum (`ASPHALT`, `DIRT`, `MUD`, `SAND`, `ICE`) with `gripMultiplier` and `dragMultiplier` tables, paired with `SurfaceZone` polygons in `track_meta.json`. The prototype defaults to `DIRT` everywhere and supports `SurfaceZone` lookups in `PhysicsStep`.

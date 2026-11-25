# Implementation Plan (for Qwen 7B in Goose)

> **Instructions for the Coding Agent (Goose / Qwen 7B):**
> Execute milestones **one at a time in order (M1 → M8)**. Do not skip ahead.
> After each milestone, run its **Verification** command and fix any errors before moving to the next milestone.
> Keep all code under `com.offroad.engine` **pure Kotlin** (zero `android.*` or `androidx.*` imports) so physics and AI run in fast JVM unit tests.

---

## Milestone 1: Android Project Skeleton & Gradle Setup
**Goal:** Create a minimal, compiling Android Jetpack Compose project with JVM unit testing enabled.

**Files to create:**
- `settings.gradle.kts`
- `build.gradle.kts` (root)
- `app/build.gradle.kts`
- `gradle.properties`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/offroad/MainActivity.kt`

**Requirements:**
- `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`.
- Dependencies: Jetpack Compose BOM, `androidx.compose.ui`, `androidx.compose.material3`, `androidx.activity:activity-compose`, `org.jetbrains.kotlinx:kotlinx-serialization-json` (for `track_meta.json`), `junit:junit` for unit tests.
- `MainActivity.kt`: Locks orientation or supports landscape (`android:screenOrientation="sensorLandscape"` in `AndroidManifest.xml`), sets `enableEdgeToEdge()`, and renders a placeholder `Text("Offroad Prototype")`.
- Pure Kotlin/JVM only (no NDK/C++).

**Verification:**
```bash
./gradlew test assembleDebug
```

---

## Milestone 2: Core Data Models & Track Schema
**Goal:** Define all shared data types for cars, surfaces, inputs, and track metadata in pure Kotlin.

**Files to create:**
- `app/src/main/java/com/offroad/engine/Math2D.kt`
- `app/src/main/java/com/offroad/engine/Models.kt`

**Exact Types to Implement:**
1. `Vec2(val x: Float, val y: Float)` with helper operators (`+`, `-`, `*`, `length()`, `normalized()`, `distanceTo(other)`, `dot(other)`).
2. `SurfaceType` enum: `ASPHALT(grip = 1.0f, drag = 0.98f)`, `DIRT(grip = 0.78f, drag = 0.96f)`, `MUD(grip = 0.45f, drag = 0.90f)`, `SAND(grip = 0.55f, drag = 0.92f)`, `ICE(grip = 0.25f, drag = 0.99f)`.
3. `CarStats` (future-proofed for upgrades):
   `data class CarStats(val maxSpeed: Float = 0.35f, val acceleration: Float = 0.45f, val turnRate: Float = 3.4f, val grip: Float = 6.0f, val boostCapacity: Float = 100f, val boostPower: Float = 1.55f)`
   *(Note: world coordinates are normalized `0f..1f` across track width/height, so speeds like `0.35f` = 35% of track width per second).*
4. `CarCondition` (future-proofed for car service):
   `data class CarCondition(val tireWear: Float = 1.0f, val engineHealth: Float = 1.0f)`
5. `CarState`:
   `id: Int`, `name: String`, `isHuman: Boolean`, `colorHex: Long`, `pos: Vec2`, `vel: Vec2`, `heading: Float`, `z: Float = 0f`, `vz: Float = 0f`, `boostFuel: Float = 100f`, `lap: Int = 1`, `nextWaypointIndex: Int = 0`, `lastSafePos: Vec2`, `lastSafeHeading: Float`, `stuckTimer: Float = 0f`, `finished: Boolean = false`, `finishOrder: Int = 0`, `stats: CarStats = CarStats()`, `condition: CarCondition = CarCondition()`.
6. `InputState(val left: Boolean = false, val right: Boolean = false, val boost: Boolean = false, val brake: Boolean = false)`.
7. `@Serializable TrackMetadata`:
   - `name: String`
   - `laps: Int = 3`
   - `trackWidthNorm: Float = 0.14f` (half-width of road around waypoints for off-track detection)
   - `waypoints: List<Vec2>`
   - `startLine: LineSegment(val a: Vec2, val b: Vec2, val heading: Float)`
   - `obstacles: List<CircleObstacle(val pos: Vec2, val radius: Float)>`
   - `ramps: List<RampZone(val x: Float, val y: Float, val width: Float, val height: Float, val impulse: Float = 0.45f)>`
   - `gaps: List<GapZone(val x: Float, val y: Float, val width: Float, val height: Float)>`
   - `surfaces: List<SurfaceZone(val x: Float, val y: Float, val width: Float, val height: Float, val surface: SurfaceType)>` (optional, defaults to empty = `DIRT`).

**Verification:**
```bash
./gradlew test
```

---

## Milestone 3: 2D Arcade Physics, Assisted Driving & Pseudo-Verticality
**Goal:** Implement the deterministic 60 Hz physics step for a car and verify all edge cases with unit tests.

**Files to create:**
- `app/src/main/java/com/offroad/engine/PhysicsStep.kt`
- `app/src/test/java/com/offroad/engine/PhysicsStepTest.kt`

**Implementation Rules for `PhysicsStep.stepCar(car: CarState, input: InputState, track: TrackMetadata, dt: Float): CarState`:**
1. **Auto-Accelerate & Brake/Reverse**:
   - Forward unit vector `fwd = Vec2(cos(heading), sin(heading))`.
   - If `input.brake`: decelerate; if speed along `fwd` <= `0.02f`, accelerate backward up to `-0.4f * stats.maxSpeed`.
   - Else: automatically apply `stats.acceleration * condition.engineHealth`.
2. **Boost**:
   - If `input.boost && boostFuel > 0f && !input.brake`: multiply acceleration and maxSpeed by `stats.boostPower`, drain `boostFuel` (`35f * dt`).
   - Else: regenerate `boostFuel` slowly (`8f * dt`) up to `stats.boostCapacity`.
3. **Steering + Assisted Driving**:
   - Steer delta: `(if (input.right) 1f else 0f) - (if (input.left) 1f else 0f)`.
   - If airborne (`z > 0f`): multiply `turnRate` by `0.3f`.
   - **Gentle Waypoint Assist**: If neither `left` nor `right` is pressed and `z <= 0f` and car is moving forward, compute angle to `track.waypoints[car.nextWaypointIndex]` and apply a subtle `0.6f * dt` angular nudge (clamped) toward it.
4. **Drift / Momentum Blending**:
   - Look up `SurfaceType` at `car.pos` (default `DIRT`).
   - Blend `car.vel` toward `fwd * currentSpeed` using `lerpFactor = (stats.grip * surface.grip * condition.tireWear * dt).coerceIn(0f, 1f)`.
   - Clamp total speed to `effectiveMaxSpeed` and apply `surface.drag`.
5. **Pseudo-Verticality (`z`, `vz`)**:
   - Gravity constant `G = 1.4f`.
   - If `z <= 0f` and `car.pos` is inside a `RampZone` and `vel.length() > 0.12f`: set `vz = ramp.impulse`.
   - Update `z += vz * dt`, `vz -= G * dt`. If `z < 0f`, clamp `z = 0f`, `vz = 0f`.
6. **Collisions & Gaps (only when `z <= 0.02f`)**:
   - **Gaps**: If inside a `GapZone` while grounded, respawn `pos = lastSafePos`, `heading = lastSafeHeading`, `vel = Vec2(0f, 0f)`.
   - **Obstacles**: Circle-circle push-out against `CircleObstacle` (radius `0.022f` for car) + reflect/dampen velocity (`* 0.5f`).
   - **Track Bounds / Anti-Stuck**: Clamp `pos` within `[0.02f, 0.98f]`. If speed `< 0.03f` for `> 1.5f` seconds while not braking, respawn at `lastSafePos`.

**Unit Tests (`PhysicsStepTest.kt`):**
- Auto-accelerate increases speed from 0 with empty `InputState`.
- Boost increases speed beyond normal `maxSpeed` and drains `boostFuel`.
- Entering a `RampZone` sets `z > 0` and allows crossing a `GapZone` without respawning.
- Entering a `GapZone` with `z == 0` respawns car to `lastSafePos`.

**Verification:**
```bash
./gradlew test --tests "com.offroad.engine.PhysicsStepTest"
```

---

## Milestone 4: AI Drivers & 4-Car Race Manager
**Goal:** Implement 3 AI racers and lap/position tracking.

**Files to create:**
- `app/src/main/java/com/offroad/engine/AiDriver.kt`
- `app/src/main/java/com/offroad/engine/RaceManager.kt`
- `app/src/test/java/com/offroad/engine/RaceManagerTest.kt`

**Implementation Rules:**
1. **`AiDriver.computeInput(car: CarState, track: TrackMetadata): InputState`**:
   - Target `track.waypoints[car.nextWaypointIndex]` plus a small deterministic lateral offset per `car.id` so AI cars don't drive in a single-file train.
   - Compute signed angle difference between `car.heading` and vector to target.
   - Set `left = angleDiff < -0.12f`, `right = angleDiff > 0.12f`.
   - Set `boost = abs(angleDiff) < 0.25f && car.boostFuel > 40f`.
2. **`RaceManager`**:
   - Holds `cars: List<CarState>` (1 human + 3 AI), `track: TrackMetadata`, `countdownTimer: Float = 3.0f`, `raceFinished: Boolean`.
   - **Waypoint & Lap Progression**: When a car comes within `track.trackWidthNorm` of `waypoints[nextWaypointIndex]`, update `lastSafePos`/`lastSafeHeading` and advance `nextWaypointIndex = (nextWaypointIndex + 1) % waypoints.size`. Wrapping back to `0` increments `lap`; when `lap > track.laps`, mark `finished = true` and assign `finishOrder`.
   - **Car-to-Car Collisions**: Simple circle-circle separation between the 4 cars (`carRadius = 0.022f`) when both are at similar `z`.
   - **Standings**: Sort cars by `(finished, finishOrder, -lap, -nextWaypointIndex, distanceToNextWaypoint)`.

**Verification:**
```bash
./gradlew test --tests "com.offroad.engine.RaceManagerTest"
```

---

## Milestone 5: Build-Time AI Map Analyzer & Sample Track
**Goal:** Create the build-time Python script that inspects a map PNG and generates `track_meta.json`, plus one playable sample track bundled in `app/src/main/assets/tracks/desert_loop/`.

**Files to create:**
- `tools/analyze_map.py`
- `tools/generate_sample_track.py` (creates a clean top-down `track.png` with road, obstacles, a ramp, and a gap for testing)
- `app/src/main/assets/tracks/desert_loop/track.png`
- `app/src/main/assets/tracks/desert_loop/track_meta.json`

**Implementation Rules for `tools/analyze_map.py`:**
- CLI: `python3 tools/analyze_map.py --image <path/to/track.png> --out <path/to/track_meta.json> [--provider gemini|openai|heuristic]`
- **AI Mode (`gemini` / `openai`)**: Sends the image to the configured vision API with a prompt requesting the exact `TrackMetadata` JSON schema (normalized `0.0..1.0` coordinates), validates the JSON, and writes `track_meta.json`.
- **Heuristic Fallback (`heuristic`, default if no API key is set)**: Uses `Pillow` / `numpy` color thresholding to extract the road loop centerline waypoints, obstacles, ramps, and gaps so the build pipeline works offline out-of-the-box.
- Run both scripts to populate `desert_loop/track.png` and `desert_loop/track_meta.json`.

**Verification:**
```bash
python3 tools/generate_sample_track.py
python3 tools/analyze_map.py --image app/src/main/assets/tracks/desert_loop/track.png --out app/src/main/assets/tracks/desert_loop/track_meta.json --provider heuristic
```

---

## Milestone 6: Track Repository, 60 Hz Game Loop & 2D Renderer
**Goal:** Load `track.png` + `track_meta.json` from assets and render the 4-car race at 60 FPS on a Compose `Canvas`.

**Files to create:**
- `app/src/main/java/com/offroad/data/TrackRepository.kt`
- `app/src/main/java/com/offroad/ui/GameCanvas.kt`

**Implementation Rules:**
- `TrackRepository`: Loads `ImageBitmap` from `assets/tracks/<id>/track.png` and parses `TrackMetadata` from `track_meta.json`.
- `GameCanvas`:
  - Draws `track.png` scaled to fill the square/4:3 game viewport.
  - Draws each car's **ground shadow** at `pos` (oval, alpha `0.35f`).
  - Draws each car at `(pos.x * width, pos.y * height - z * height * 0.15f)` rotated by `heading`, scaled by `1f + z * 0.8f`.
  - Draws a simple boost flame behind the car when `input.boost && boostFuel > 0`.
  - Includes a `showDebugOverlay: Boolean` parameter that draws `waypoints`, `obstacles`, `ramps`, and `gaps` over the image so `track_meta.json` alignment can be verified visually.

**Verification:**
```bash
./gradlew assembleDebug
```

---

## Milestone 7: Adaptive Controls (Tablet/Foldable Overlay vs. Tall Mobile Handheld) + Waydroid Keyboard Support
**Goal:** Build the adaptive screen layout and multi-touch + keyboard input system.

**Files to create:**
- `app/src/main/java/com/offroad/ui/AdaptiveControls.kt`
- `app/src/main/java/com/offroad/ui/GameScreen.kt`

**Implementation Rules:**
1. **Aspect Ratio Detection**:
   - Use `BoxWithConstraints` to compute `aspectRatio = maxWidth / maxHeight`.
   - `isHandheldMode = aspectRatio >= 1.55f`.
2. **Handheld Mode (`isHandheldMode == true`, tall phones)**:
   - `Row(Modifier.fillMaxSize())`:
     - Left Pillar (`weight(0.22f)`): HUD (Pos/Lap) + `[ < LEFT ]` and `[ RIGHT > ]` buttons.
     - Center (`weight(0.56f)`): `GameCanvas` (unobstructed).
     - Right Pillar (`weight(0.22f)`): Boost gauge + `[ BOOST ]` and `[ BRAKE ]` buttons.
3. **Overlay Mode (`isHandheldMode == false`, tablets & foldables 4:3 / 1:1)**:
   - `Box(Modifier.fillMaxSize())`:
     - `GameCanvas` fills 100% of the screen.
     - Top HUD bar (Pos, Lap, Boost bar, Debug toggle).
     - Bottom-Left semi-transparent `[ < LEFT ]` `[ RIGHT > ]`.
     - Bottom-Right semi-transparent `[ BRAKE ]` `[ BOOST ]`.
4. **Multi-Touch & Waydroid Keyboard Support**:
   - Each virtual button uses `pointerInput(Unit)` with `awaitPointerEventScope` (`PointerEventType.Press` / `Release` / `Exit`) so holding **Left + Boost** simultaneously works on touchscreens.
   - `onPreviewKeyEvent` on the root `Box` maps `A`/`DirectionLeft`, `D`/`DirectionRight`, `Space`/`W`/`DirectionUp` (Boost), `S`/`DirectionDown` (Brake) into `InputState` for Waydroid and desktop testing.

**Verification:**
```bash
./gradlew assembleDebug
```

---

## Milestone 8: Menu, Countdown, HUD & Results Flow
**Goal:** Wire up the complete playable prototype loop in `MainActivity.kt`.

**Files to create/modify:**
- `app/src/main/java/com/offroad/ui/MenuScreen.kt`
- `app/src/main/java/com/offroad/MainActivity.kt`

**Implementation Rules:**
- **Menu Screen**: Title, track selector (lists folders in `assets/tracks/`), "Start Race" button.
- **Countdown**: 3-2-1-GO overlay before cars start moving.
- **Results Overlay**: Shown when the human finishes or all 4 cars finish; displays 1st–4th standings and **Race Again** / **Back to Menu** buttons.

**Verification:**
```bash
./gradlew test assembleDebug
```
Install on Android device or Waydroid:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

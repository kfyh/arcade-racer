# Offroad Prototype — Revised Design Document (Current State)

## 1. Overview & Core Pillars
A top-down 2D arcade racer for **Android** (with emulator/keyboard support), built around **momentum-based, limited-control driving** inspired by *Super Offroad* and *Asteroids*:
- **4-Car Arcade Racing**: 1 human player (`YOU`) vs. 3 AI racers (`VIPER`, `BLAZE`, `DUSTY`) on a compact, single-screen top-down track.
- **Skill-Based Momentum Driving**: Pure linear pivot steering paired with Asteroids-style thrust and low-to-moderate surface friction. There is **no** lateral grip blending or steering auto-assist—controlling slides through corners relies on early rotation, throttle/brake timing, and boost management.
- **Single-Press Latched Boost**: Pressing Boost once latches it **ON** until the tank is completely depleted (no recharge).
- **Off-Track Momentum Penalty**: Leaving the road scrubs acceleration inversely to current speed and increases drag, punishing wide slides while still allowing slow cars to pull back onto the track.
- **Adaptive Form-Factor UI**: Automatically switches between **Handheld Side-Controls** (tall screens) and **Overlay Controls** (tablets/foldables).

---

## 2. Current Driving & Physics Model (`PhysicsStep.kt`)

All physics run at a fixed **60 Hz** (`dt = 1/60f`) in pure Kotlin (`com.offroad.engine`, zero `android.*` imports):

### Steering & Thrust
- **Linear Pivot Steering**: Holding Left or Right rotates `heading` linearly at `turnRate = 4.0f` rad/s (`heading += steerDelta * turnRate * dt`). Pressing one direction clears the opposite direction. No deadzones, ramping, or waypoint-assist nudges are applied.
- **Auto-Accelerate & Asteroids Thrust**: Unless `BRAKE` is held, cars continuously apply acceleration (`0.495f`) along the **current** `heading` vector (`newFwd`). Holding `BRAKE` decelerates, then reverses up to `0.4 * maxSpeed`.
- **Friction & Slide**: Velocity is damped only by surface drag (`SurfaceType.DIRT` = `0.973f` per 60 Hz frame, `ASPHALT` = `0.982f`, `SAND` = `0.945f`, `MUD` = `0.925f`, `ICE` = `0.992f`) and clamped to `maxSpeed = 0.385f` (normalized track-width/sec). No lateral velocity blending is applied, so turning at speed produces natural sideways drift.

### Single-Press Boost
- Triggered on a single press of `BOOST` (latches `isBoosting = true`), staying active until `boostFuel` (`100f`) drains at `boostDrainRate = 80f`/sec (~1.25s burst) or `BRAKE` is pressed.
- **No Recharge**: Boost fuel does not regenerate during a race.
- **Power**: Multiplies `maxSpeed` and `acceleration` by `boostPower = 2.15f`.

### Off-Track Zone
- `PhysicsStep.isOffTrack` computes the minimum distance from the car to the closed waypoint polyline (`distanceToSegment`).
- Outside `trackWidthNorm * 0.55f` (`~0.077f`), acceleration is scaled down inversely to current speed (`1.0f` at standstill down to `0.15f` at `maxSpeed`) and drag increases to `0.94f`.

### Collisions, Bounds & Pseudo-Verticality
- **Obstacles & Walls**: Circle-circle push-out (`CAR_RADIUS = 0.022f`) with velocity reflection (`* 0.5f`), plus `[0.02f, 0.98f]` screen-bound deflection.
- **Anti-Stuck Recovery**: If speed stays `< 0.03f` for `> 1.5s` while not braking, respawns at `lastSafePos` / `lastSafeHeading`.
- **Pseudo-Verticality (`z`, `vz`)**: Supported in `PhysicsStep` (`RampZone`, `GapZone`, `GRAVITY = 1.4f`), currently inactive on the flat test track.

---

## 3. AI Drivers & Race Manager (`AiDriver.kt`, `RaceManager.kt`)
- **Center-of-Road AI**: Targets the next waypoint along the road centerline with small deterministic per-car/per-waypoint lateral jitter (`±0.014f`) and sideways-drift compensation (`toTarget.normalized() - car.vel * 0.45f`), with no steering deadzone.
- **AI Boost**: Triggers latched boost on straightaways (`abs(angleDiff) < 0.35f`).
- **Race Flow**: 3.0-second countdown, car-to-car circle separation, waypoint/lap progression (`3` laps default), and standings sorted by finish order, lap, waypoint index, and distance to next waypoint.

---

## 4. Controls & Adaptive UI (`MainActivity.kt`, `AdaptiveControls.kt`, `GameScreen.kt`)

### Input Handling
- **Touch Controls**: `VirtualHoldButton` uses `awaitEachGesture { awaitFirstDown(requireUnconsumed = false) ... waitForUpOrCancellation() }` with `DisposableEffect` cleanup so multi-touch holds never stick or queue. Pressing Left clears Right and vice versa.
- **Keyboard / Emulator**: Handled at the `Activity` level in `MainActivity.dispatchKeyEvent` (`A`/`DPAD_LEFT`, `D`/`DPAD_RIGHT`, `Space`/`W`/`DPAD_UP` for Boost, `S`/`DPAD_DOWN` for Brake) so D-pad/arrow keys are never intercepted by Compose focus traversal.

### Adaptive Layout (`aspectRatio = maxWidth / maxHeight`)
- **Handheld Mode (`aspectRatio >= 1.55f`, tall phones)**: 3-column `Row` — Left Pillar (`22%`: Pos/Lap HUD, `DBG`/`EXIT`, `<` / `>` buttons), Center (`56%`: unobstructed square `GameCanvas`), Right Pillar (`22%`: Boost gauge, `BOOST`, `BRAKE`).
- **Overlay Mode (`aspectRatio < 1.55f`, tablets/foldables)**: Full-screen `GameCanvas` with top HUD bar and semi-transparent bottom-corner controls.

---

## 5. Build-Time Track Pipeline & Current Test Track
- **Tools**: `tools/generate_sample_track.py` (pure-Python PNG generator) and `tools/analyze_map.py` (supports `--provider heuristic|gemini|openai`).
- **Current Sample Track (`assets/tracks/desert_loop/`)**: A **flat hourglass-shaped dirt loop** designed to test both left and right turns at speed, with 16 ordered waypoints, start/finish line at the bottom-center waist, 2 decorative off-road rocks, and **no ramps or gaps** (`ramps: []`, `gaps: []`).
- **In-Game Debug Overlay (`DBG` toggle)**: Draws waypoints, start line, obstacles, ramps, and gaps directly over `track.png` for visual verification.

---

## 6. Package Layout & Tuning Rules
```text
app/src/main/java/com/offroad/
├── MainActivity.kt          # Entry point, Menu/Game routing, dispatchKeyEvent
├── data/
│   └── TrackRepository.kt   # Loads track.png + track_meta.json from assets
├── engine/                  # Pure Kotlin (NO android.* or androidx.* imports)
│   ├── Math2D.kt
│   ├── Models.kt
│   ├── PhysicsStep.kt
│   ├── AiDriver.kt
│   └── RaceManager.kt
└── ui/                      # Jetpack Compose UI, Canvas renderer, Adaptive Controls
    ├── AdaptiveControls.kt
    ├── GameCanvas.kt
    ├── GameScreen.kt
    └── MenuScreen.kt
```
- **Tuning Discipline (`AGENTS.md`)**: Adjust one physics variable at a time and do not introduce unrequested assists or blending layers.
- **Future Extension Points**: `CarStats`, `CarCondition`, and `SurfaceZone` remain modeled in `Models.kt` for upgrades, wear/service, and multi-surface tracks.

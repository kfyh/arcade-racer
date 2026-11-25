# Agent Guidelines (`AGENTS.md`)

## 1. Physics & Gameplay Tuning Rules
- **Tune one variable at a time**: When the user asks to adjust gameplay feel (e.g., friction/drag, turn rate, acceleration, top speed, boost duration, off-track penalty), change **only** that single requested parameter.
- **No unrequested features or hidden helpers**: Do **not** add extra physics behaviors (such as lateral grip blending, steering deadzones, waypoint-assist nudges, or extra dampers) unless explicitly requested or approved by the user.
- **Propose before bundling**: If you think a secondary adjustment would help, mention it as a suggestion instead of sneaking it into the same change.
- **Preserve the core feel**: The driving model intentionally relies on limited control (momentum, slide, and manual braking/steering) to make the game challenging and fun.

## 2. Architecture & Code Constraints
- **Pure Kotlin Engine**: Keep all files under `app/src/main/java/com/offroad/engine/` free of `android.*` and `androidx.*` imports so physics and AI remain testable on the JVM.
- **Input Handling**:
  - Keep keyboard input in `MainActivity.dispatchKeyEvent` so D-pad/arrow keys are not intercepted by Compose focus traversal.
  - Keep touch hold buttons using `awaitEachGesture` + `waitForUpOrCancellation()` in `AdaptiveControls.kt`.

## 3. Verification
Run unit tests and build the debug APK after changes (requires Android SDK + JDK on host):
```bash
./gradlew test assembleDebug
```

package com.offroad.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object PhysicsStep {
    const val GRAVITY: Float = 1.4f
    const val CAR_RADIUS: Float = 0.022f

    fun stepCar(
        car: CarState,
        input: InputState,
        track: TrackMetadata,
        dt: Float,
        turnRateScale: Float = 1f,
        maxSpeedScale: Float = 1f
    ): CarState {
        if (dt <= 0f) return car

        // 1. Single-press Latched Boost (disabled after finishing)
        val boosting = !car.finished && (car.isBoosting || input.boost) && car.boostFuel > 0f && !input.brake
        val newBoostFuel = if (boosting) {
            (car.boostFuel - car.stats.boostDrainRate * dt).coerceAtLeast(0f)
        } else {
            car.boostFuel
        }

        val invScale = 1f / track.worldScale.coerceAtLeast(0.1f)
        val boostMult = if (boosting) car.stats.boostPower else 1f
        val effectiveMaxSpeed = car.stats.maxSpeed * invScale * car.condition.engineHealth * boostMult * maxSpeedScale
        val offTrack = isOffTrack(car.pos, track)
        val speedRatio = (car.vel.length() / effectiveMaxSpeed.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        // Off-track scrubs acceleration inversely to current speed (1.0 at standstill -> 0.15 at maxSpeed)
        val offTrackAccelScale = if (offTrack) (1f - speedRatio * 0.85f).coerceIn(0.15f, 1f) else 1f
        val effectiveAccel = car.stats.acceleration * invScale * car.condition.engineHealth * boostMult * offTrackAccelScale

        // 2. Pure Linear Steering (+/- turnRate * dt), scaled by viewport zoom
        val steerDelta = (if (input.right) 1f else 0f) - (if (input.left) 1f else 0f)
        val newHeading = normalizeAngle(car.heading + steerDelta * car.stats.turnRate * turnRateScale * dt)

        // 3. Auto-Accelerate & Softer Brake (no reverse)
        val newFwd = Vec2(cos(newHeading), sin(newHeading))
        var vel = car.vel
        val brakingToTargetSpeed = car.finished && car.finishedTimer >= 2.0f && vel.length() > effectiveMaxSpeed
        if (input.brake || brakingToTargetSpeed) {
            val spd = vel.length()
            if (spd > 0f) {
                val brakeScale = if (brakingToTargetSpeed) 0.45f else 0.9f
                val decel = car.stats.acceleration * invScale * brakeScale * dt
                val minSpd = if (brakingToTargetSpeed) effectiveMaxSpeed else 0f
                val nextSpd = (spd - decel).coerceAtLeast(minSpd)
                vel = if (nextSpd > 0f) vel * (nextSpd / spd) else Vec2(0f, 0f)
            }
        } else {
            vel += newFwd * (effectiveAccel * dt)
        }

        // 4. Surface drag (+ off-track sand drag) & speed clamp
        val surface = surfaceAt(car.pos, track)
        val baseDrag = if (offTrack) 0.94f else surface.drag
        val dragFactor = baseDrag.pow(dt * 60f)
        vel *= dragFactor

        val speed = vel.length()
        if (!brakingToTargetSpeed && speed > effectiveMaxSpeed) {
            vel = vel.normalized() * effectiveMaxSpeed
        }

        // 5. Pseudo-Verticality (z, vz)
        var z = car.z
        var vz = car.vz
        if (z <= 0f && vel.length() > 0.12f) {
            val ramp = track.ramps.firstOrNull { it.contains(car.pos) }
            if (ramp != null) {
                vz = ramp.impulse
            }
        }
        z += vz * dt
        vz -= GRAVITY * dt
        if (z < 0f) {
            z = 0f
            vz = 0f
        }

        // Advance 2D position
        var pos = car.pos + vel * dt
        var headingOut = newHeading
        var stuckTimer = car.stuckTimer

        // 6. Collisions & Gaps (only when grounded: z <= 0.02f)
        if (z <= 0.02f) {
            val inGap = track.gaps.any { it.contains(pos) }
            if (inGap) {
                return car.copy(
                    pos = car.lastSafePos,
                    vel = Vec2(0f, 0f),
                    heading = car.lastSafeHeading,
                    z = 0f,
                    vz = 0f,
                    boostFuel = newBoostFuel,
                    stuckTimer = 0f,
                    isBoosting = false
                )
            }

            for (obs in track.allObstacles()) {
                val delta = pos - obs.pos
                val dist = delta.length()
                val minDist = (CAR_RADIUS * invScale) + obs.radius
                if (dist < minDist) {
                    val normal = if (dist > 1e-5f) delta / dist else Vec2(1f, 0f)
                    pos = obs.pos + normal * minDist
                    val vn = vel.dot(normal)
                    if (vn < 0f) {
                        vel = (vel - normal * (2f * vn)) * 0.5f
                    }
                }
            }
        }

        // Track bounds clamping with soft wall deflection
        val clampedX = pos.x.coerceIn(0.02f, 0.98f)
        val clampedY = pos.y.coerceIn(0.02f, 0.98f)
        if (clampedX != pos.x) {
            vel = Vec2(-vel.x * 0.4f, vel.y)
        }
        if (clampedY != pos.y) {
            vel = Vec2(vel.x, -vel.y * 0.4f)
        }
        pos = Vec2(clampedX, clampedY)

        // Anti-stuck recovery
        if (vel.length() < 0.03f * invScale && !input.brake) {
            stuckTimer += dt
            if (stuckTimer > 1.5f) {
                pos = car.lastSafePos
                headingOut = car.lastSafeHeading
                vel = Vec2(0f, 0f)
                stuckTimer = 0f
            }
        } else {
            stuckTimer = 0f
        }

        return car.copy(
            pos = pos,
            vel = vel,
            heading = headingOut,
            z = z,
            vz = vz,
            boostFuel = newBoostFuel,
            stuckTimer = stuckTimer,
            finishedTimer = if (car.finished) car.finishedTimer + dt else 0f,
            isBoosting = boosting
        )
    }

    fun isOffTrack(pos: Vec2, track: TrackMetadata): Boolean {
        val wps = track.waypoints
        if (wps.size < 2) return false
        var minDist = Float.MAX_VALUE
        for (i in wps.indices) {
            val a = wps[i]
            val b = wps[(i + 1) % wps.size]
            val d = distanceToSegment(pos, a, b)
            if (d < minDist) minDist = d
        }
        // Road half-width + ~5% track width margin
        val roadMargin = (track.trackWidthNorm * 0.55f).coerceAtLeast(0.05f / track.worldScale.coerceAtLeast(0.1f))
        return minDist > roadMargin
    }

    fun surfaceAt(pos: Vec2, track: TrackMetadata): SurfaceType {
        return track.surfaces.firstOrNull { it.contains(pos) }?.surface ?: track.defaultSurface
    }
}

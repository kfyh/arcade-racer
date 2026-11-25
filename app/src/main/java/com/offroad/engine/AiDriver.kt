package com.offroad.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object AiDriver {
    fun computeInput(car: CarState, track: TrackMetadata): InputState {
        if (track.waypoints.isEmpty()) return InputState()

        val wpIdx = car.nextWaypointIndex.coerceIn(track.waypoints.indices)
        val nextWpIdx = (wpIdx + 1) % track.waypoints.size
        val wp = track.waypoints[wpIdx]
        val nextWp = track.waypoints[nextWpIdx]

        // Center-of-road target with small deterministic per-waypoint randomness
        val segDir = (nextWp - wp).normalized()
        val perp = Vec2(-segDir.y, segDir.x)
        val hash = ((car.id * 73856093) xor (wpIdx * 19349663)) and 0xFF
        val jitter = ((hash / 255f) * 2f - 1f) * (0.014f / track.worldScale.coerceAtLeast(1f))
        val target = wp + perp * jitter

        val toTarget = target - car.pos
        if (toTarget.lengthSquared() < 1e-6f) return InputState()

        // Compensate slightly for sideways drift so AI stays near the middle of the road
        val steerVec = toTarget.normalized() - car.vel * (0.45f * track.worldScale.coerceAtLeast(1f))
        val desiredAngle = atan2(steerVec.y, steerVec.x)
        val angleDiff = normalizeAngle(desiredAngle - car.heading)

        val left = angleDiff < 0f
        val right = angleDiff > 0f
        val boost = abs(angleDiff) < 0.35f && car.boostFuel > 0f && !car.isBoosting

        return InputState(
            left = left,
            right = right,
            boost = boost,
            brake = false
        )
    }
}

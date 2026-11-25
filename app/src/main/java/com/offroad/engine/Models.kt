package com.offroad.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.cos
import kotlin.math.sin

@Serializable
enum class SurfaceType(val grip: Float, val drag: Float) {
    ASPHALT(grip = 1.0f, drag = 0.982f),
    DIRT(grip = 0.78f, drag = 0.973f),
    MUD(grip = 0.45f, drag = 0.925f),
    SAND(grip = 0.55f, drag = 0.945f),
    ICE(grip = 0.25f, drag = 0.992f)
}

data class CarStats(
    val maxSpeed: Float = 0.385f,
    val acceleration: Float = 0.495f,
    val turnRate: Float = 4.0f,
    val grip: Float = 12.0f,
    val boostCapacity: Float = 100f,
    val boostPower: Float = 2.15f,
    val boostDrainRate: Float = 80f
)

data class CarCondition(
    val tireWear: Float = 1.0f,
    val engineHealth: Float = 1.0f
)

data class CarState(
    val id: Int,
    val name: String,
    val isHuman: Boolean,
    val colorHex: Long,
    val pos: Vec2,
    val vel: Vec2 = Vec2(0f, 0f),
    val heading: Float = 0f,
    val z: Float = 0f,
    val vz: Float = 0f,
    val boostFuel: Float = 100f,
    val lap: Int = 1,
    val nextWaypointIndex: Int = 0,
    val lastSafePos: Vec2 = pos,
    val lastSafeHeading: Float = heading,
    val stuckTimer: Float = 0f,
    val invalidLapTimer: Float = 0f,
    val missedWaypointsThisLap: Int = 0,
    val consecutiveMissedWaypoints: Int = 0,
    val lapInvalid: Boolean = false,
    val reachedWaypointsThisLap: Set<Int> = emptySet(),
    val currentLapTime: Float = 0f,
    val lastLapTime: Float? = null,
    val bestLapTime: Float? = null,
    val lapTimes: List<Float> = emptyList(),
    val awaitingFinishLine: Boolean = false,
    val invalidLapReason: String = "",
    val finished: Boolean = false,
    val finishedTimer: Float = 0f,
    val finishOrder: Int = 0,
    val isBoosting: Boolean = false,
    val stats: CarStats = CarStats(),
    val condition: CarCondition = CarCondition()
)

data class InputState(
    val left: Boolean = false,
    val right: Boolean = false,
    val boost: Boolean = false,
    val brake: Boolean = false
)

@Serializable
data class FinishLineNode(
    val pos: Vec2,
    val heading: Float = 0f
)

@Serializable
data class CircleObstacle(
    val pos: Vec2,
    val radius: Float
)

@Serializable
data class RampZone(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val impulse: Float = 0.45f
) {
    fun contains(p: Vec2): Boolean =
        p.x >= x && p.x <= x + width && p.y >= y && p.y <= y + height
}

@Serializable
data class GapZone(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun contains(p: Vec2): Boolean =
        p.x >= x && p.x <= x + width && p.y >= y && p.y <= y + height
}

@Serializable
data class SurfaceZone(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val surface: SurfaceType
) {
    fun contains(p: Vec2): Boolean =
        p.x >= x && p.x <= x + width && p.y >= y && p.y <= y + height
}

@Serializable
data class TrackMetadata(
    val name: String,
    val laps: Int = 3,
    val trackWidthNorm: Float = 0.14f,
    @Transient
    val worldScale: Float = 1.0f,
    val defaultSurface: SurfaceType = SurfaceType.DIRT,
    val waypoints: List<Vec2> = emptyList(),
    val finishLine: FinishLineNode = FinishLineNode(Vec2(0.5f, 0.775f), 0f),
    val obstacles: List<CircleObstacle> = emptyList(),
    val ramps: List<RampZone> = emptyList(),
    val gaps: List<GapZone> = emptyList(),
    val surfaces: List<SurfaceZone> = emptyList()
) {
    fun finishLineHalfWidth(): Float = trackWidthNorm * 0.75f

    fun finishLineSegment(): Pair<Vec2, Vec2> {
        val perp = Vec2(-sin(finishLine.heading), cos(finishLine.heading))
        val hw = finishLineHalfWidth()
        return (finishLine.pos - perp * hw) to (finishLine.pos + perp * hw)
    }

    fun finishLinePoles(): List<CircleObstacle> {
        val invScale = 1f / worldScale.coerceAtLeast(0.1f)
        val postRadius = 0.015f * invScale
        val poleOffset = finishLineHalfWidth() + postRadius * 0.5f
        val perp = Vec2(-sin(finishLine.heading), cos(finishLine.heading))
        return listOf(
            CircleObstacle(finishLine.pos - perp * poleOffset, postRadius),
            CircleObstacle(finishLine.pos + perp * poleOffset, postRadius)
        )
    }

    fun allObstacles(): List<CircleObstacle> = obstacles + finishLinePoles()
}

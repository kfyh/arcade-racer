package com.offroad.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class RaceManager(
    val track: TrackMetadata,
    initialCars: List<CarState> = createDefaultCars(track),
    var countdownTimer: Float = 3.0f
) {
    var cars: List<CarState> = initialCars
        private set

    private var nextFinishOrder: Int = 1

    val estimatedLapTime: Float = computeEstimatedLapTime(track)
    var finishTimeoutTimer: Float? = null
        private set

    val raceFinished: Boolean
        get() = cars.all { it.finished }

    val humanFinished: Boolean
        get() = cars.firstOrNull { it.isHuman }?.finished == true

    fun step(humanInput: InputState, dt: Float, humanTurnRateScale: Float = 1f) {
        if (dt <= 0f) return

        if (countdownTimer > 0f) {
            countdownTimer = (countdownTimer - dt).coerceAtLeast(0f)
            return
        }

        // 1. Step physics for each car (finished cars keep driving at a slow cooldown speed)
        val prevPositions = cars.map { it.pos }
        val updated = cars.map { car ->
            val input = if (car.isHuman) humanInput else AiDriver.computeInput(car, track)
            val scale = if (car.isHuman) humanTurnRateScale else 1f
            val speedCap = if (car.finished) 0.35f else 1f
            PhysicsStep.stepCar(car, input, track, dt, turnRateScale = scale, maxSpeedScale = speedCap)
        }.toMutableList()

        // 2. Car-to-car circle-circle separation
        val minSep = (PhysicsStep.CAR_RADIUS * 2f) / track.worldScale.coerceAtLeast(1f)
        for (i in 0 until updated.size) {
            for (j in i + 1 until updated.size) {
                val a = updated[i]
                val b = updated[j]
                if (abs(a.z - b.z) > 0.05f) continue

                val delta = b.pos - a.pos
                val dist = delta.length()
                if (dist < minSep) {
                    val normal = if (dist > 1e-5f) delta / dist else Vec2(1f, 0f)
                    val overlap = (minSep - dist) * 0.5f
                    updated[i] = a.copy(pos = a.pos - normal * overlap)
                    updated[j] = b.copy(pos = b.pos + normal * overlap)
                }
            }
        }

        // 3. Waypoint & lap progression + invalid lap detection (must physically cross finish line)
        val n = track.waypoints.size
        if (n > 0) {
            val startMid = (track.startLine.a + track.startLine.b) * 0.5f
            // Extend finish-line gate across shoulder/off-track margin so wide slides still count
            val lineVec = (track.startLine.b - track.startLine.a).normalized()
            val gateHalfWidth = track.trackWidthNorm * 1.5f
            val gateA = startMid - lineVec * gateHalfWidth
            val gateB = startMid + lineVec * gateHalfWidth
            val finishThreshold = track.trackWidthNorm * 0.65f

            for (i in updated.indices) {
                var car = updated[i]
                val prevPos = prevPositions[i]

                if (!car.finished) {
                    car = car.copy(currentLapTime = car.currentLapTime + dt)
                }

                if (car.invalidLapTimer > 0f) {
                    car = car.copy(invalidLapTimer = (car.invalidLapTimer - dt).coerceAtLeast(0f))
                }

                val wpIdx = car.nextWaypointIndex.coerceIn(track.waypoints.indices)
                var matchedOffset = -1
                val maxLookahead = minOf(n - 1, maxOf(3, n / 2))
                for (offset in 0 until maxLookahead) {
                    val checkIdx = (wpIdx + offset) % n
                    if (car.pos.distanceTo(track.waypoints[checkIdx]) < track.trackWidthNorm) {
                        matchedOffset = offset
                        break
                    }
                }

                if (matchedOffset >= 0) {
                    val matchedIdx = (wpIdx + matchedOffset) % n
                    val newMissed = car.missedWaypointsThisLap + matchedOffset
                    val newConsec = if (matchedOffset == 0) 0 else car.consecutiveMissedWaypoints + matchedOffset
                    val maxAllowedMissed = (n * 0.30f)
                    val isNowInvalid = car.lapInvalid || newConsec >= 2 || newMissed > maxAllowedMissed
                    val newlyInvalidated = !car.lapInvalid && isNowInvalid
                    val nextInvalidTimer = if (newlyInvalidated) 2.0f else car.invalidLapTimer
                    val nextReason = if (newlyInvalidated) "MISSED CHECKPOINT" else car.invalidLapReason

                    val rawNext = wpIdx + matchedOffset + 1
                    val wrappedWaypoints = rawNext >= n
                    val nextIdx = rawNext % n

                    val wpCenter = track.waypoints[matchedIdx]
                    val wpAfter = track.waypoints[nextIdx]
                    val safeHeading = atan2(wpAfter.y - wpCenter.y, wpAfter.x - wpCenter.x)

                    car = car.copy(
                        nextWaypointIndex = nextIdx,
                        missedWaypointsThisLap = newMissed,
                        consecutiveMissedWaypoints = newConsec,
                        lapInvalid = isNowInvalid,
                        awaitingFinishLine = car.awaitingFinishLine || wrappedWaypoints,
                        reachedWaypointsThisLap = car.reachedWaypointsThisLap + matchedIdx,
                        invalidLapTimer = nextInvalidTimer,
                        invalidLapReason = nextReason,
                        lastSafePos = wpCenter,
                        lastSafeHeading = safeHeading
                    )
                }

                val crossedFinishLine = segmentsIntersect(prevPos, car.pos, gateA, gateB) ||
                    car.pos.distanceTo(startMid) < finishThreshold

                if (car.awaitingFinishLine && crossedFinishLine) {
                    if (car.lapInvalid) {
                        car = car.copy(
                            awaitingFinishLine = false,
                            missedWaypointsThisLap = 0,
                            consecutiveMissedWaypoints = 0,
                            lapInvalid = false,
                            reachedWaypointsThisLap = emptySet(),
                            currentLapTime = 0f,
                            invalidLapTimer = 2.0f,
                            invalidLapReason = car.invalidLapReason.ifEmpty { "MISSED CHECKPOINT" }
                        )
                    } else if (!car.finished) {
                        val completedTime = car.currentLapTime
                        val newBest = if (car.bestLapTime == null || completedTime < car.bestLapTime) {
                            completedTime
                        } else {
                            car.bestLapTime
                        }
                        val newLap = car.lap + 1
                        val finished = newLap > track.laps
                        val finishOrder = if (finished) nextFinishOrder++ else car.finishOrder
                        car = car.copy(
                            awaitingFinishLine = false,
                            lap = newLap,
                            missedWaypointsThisLap = 0,
                            consecutiveMissedWaypoints = 0,
                            lapInvalid = false,
                            reachedWaypointsThisLap = emptySet(),
                            currentLapTime = 0f,
                            lastLapTime = completedTime,
                            bestLapTime = newBest,
                            finished = finished,
                            finishOrder = finishOrder
                        )
                    } else {
                        car = car.copy(awaitingFinishLine = false)
                    }
                } else if (car.awaitingFinishLine && !car.finished && car.nextWaypointIndex >= 3) {
                    // Drove past the finish area without crossing the finish line gate
                    car = car.copy(
                        awaitingFinishLine = false,
                        lapInvalid = true,
                        invalidLapTimer = 2.0f,
                        invalidLapReason = "MISSED FINISH LINE"
                    )
                } else if (
                    !car.awaitingFinishLine &&
                    !car.finished &&
                    n >= 6 &&
                    car.nextWaypointIndex in 3 until (n - 2) &&
                    car.vel.length() > 0.05f &&
                    crossedFinishLine
                ) {
                    // Crossed start/finish line after cutting across the infield
                    car = car.copy(
                        invalidLapTimer = 2.0f,
                        invalidLapReason = "MISSED CHECKPOINT",
                        nextWaypointIndex = 0,
                        missedWaypointsThisLap = 0,
                        consecutiveMissedWaypoints = 0,
                        lapInvalid = false,
                        reachedWaypointsThisLap = emptySet(),
                        currentLapTime = 0f
                    )
                }

                updated[i] = car
            }
        }

        cars = updated

        // 4. End-of-race timeout (2x estimated lap time once the first car finishes)
        if (!raceFinished && cars.any { it.finished }) {
            val currentTimeout = finishTimeoutTimer ?: (estimatedLapTime * 2f)
            val nextTimeout = (currentTimeout - dt).coerceAtLeast(0f)
            finishTimeoutTimer = nextTimeout
            if (nextTimeout <= 0f) {
                val sorted = standings()
                val orderMap = sorted.mapIndexed { idx, c -> c.id to (idx + 1) }.toMap()
                cars = cars.map { c ->
                    if (c.finished) c
                    else c.copy(finished = true, finishOrder = orderMap[c.id] ?: nextFinishOrder++)
                }
            }
        }
    }

    fun standings(): List<CarState> {
        return cars.sortedWith(
            compareBy<CarState>(
                { if (it.finished) 0 else 1 },
                { if (it.finished) it.finishOrder else Int.MAX_VALUE },
                { -it.lap },
                { -it.nextWaypointIndex },
                { car ->
                    if (track.waypoints.isEmpty()) 0f
                    else car.pos.distanceTo(track.waypoints[car.nextWaypointIndex.coerceIn(track.waypoints.indices)])
                }
            )
        )
    }

    fun humanPosition(): Int {
        val sorted = standings()
        val idx = sorted.indexOfFirst { it.isHuman }
        return if (idx >= 0) idx + 1 else 1
    }

    private fun segmentsIntersect(p1: Vec2, p2: Vec2, q1: Vec2, q2: Vec2): Boolean {
        fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val d1 = cross(q1, q2, p1)
        val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1)
        val d4 = cross(p1, p2, q2)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    companion object {
        fun computeEstimatedLapTime(track: TrackMetadata): Float {
            val wps = track.waypoints
            if (wps.size < 2) return 10f
            var perimeter = 0f
            for (i in wps.indices) {
                perimeter += wps[i].distanceTo(wps[(i + 1) % wps.size])
            }
            val effectiveSpeed = CarStats().maxSpeed / track.worldScale.coerceAtLeast(1f)
            return ((perimeter / effectiveSpeed) * 1.25f).coerceAtLeast(8f)
        }

        fun createDefaultCars(track: TrackMetadata): List<CarState> {
            val mid = (track.startLine.a + track.startLine.b) * 0.5f
            val h = track.startLine.heading
            val fwd = Vec2(cos(h), sin(h))
            val right = Vec2(-fwd.y, fwd.x)
            val s = 1f / track.worldScale.coerceAtLeast(1f)

            val offsets = listOf(
                fwd * (-0.025f * s) + right * (-0.025f * s),
                fwd * (-0.025f * s) + right * (0.025f * s),
                fwd * (-0.065f * s) + right * (-0.025f * s),
                fwd * (-0.065f * s) + right * (0.025f * s)
            )

            val specs = listOf(
                Triple(0, "YOU", 0xFFE53935L),
                Triple(1, "VIPER", 0xFF1E88E5L),
                Triple(2, "BLAZE", 0xFF43A047L),
                Triple(3, "DUSTY", 0xFFFDD835L)
            )

            return specs.mapIndexed { idx, (id, name, color) ->
                val startPos = mid + offsets[idx]
                CarState(
                    id = id,
                    name = name,
                    isHuman = idx == 0,
                    colorHex = color,
                    pos = startPos,
                    heading = h,
                    lastSafePos = startPos,
                    lastSafeHeading = h
                )
            }
        }
    }
}

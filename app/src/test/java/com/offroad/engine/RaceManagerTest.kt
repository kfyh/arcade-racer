package com.offroad.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceManagerTest {

    private fun loopTrack(laps: Int = 2): TrackMetadata {
        return TrackMetadata(
            name = "Loop",
            laps = laps,
            trackWidthNorm = 0.14f,
            waypoints = listOf(
                Vec2(0.75f, 0.75f),
                Vec2(0.75f, 0.25f),
                Vec2(0.25f, 0.25f),
                Vec2(0.25f, 0.75f)
            ),
            finishLine = FinishLineNode(Vec2(0.45f, 0.75f), heading = 0f)
        )
    }

    @Test
    fun countdownBlocksMovementUntilZero() {
        val track = loopTrack()
        val manager = RaceManager(track = track, countdownTimer = 1.0f)
        val initialPositions = manager.cars.map { it.pos }

        manager.step(InputState(), 0.5f)
        assertEquals(0.5f, manager.countdownTimer, 1e-4f)
        assertEquals(initialPositions, manager.cars.map { it.pos })

        manager.step(InputState(), 0.5f)
        assertEquals(0f, manager.countdownTimer, 1e-4f)

        manager.step(InputState(), 1f / 60f)
        assertTrue(manager.cars.first().pos != initialPositions.first())
    }

    @Test
    fun aiCarsCompleteLapsAndFinishRace() {
        val track = loopTrack(laps = 1)
        val manager = RaceManager(track = track, countdownTimer = 0f)

        var ticks = 0
        while (!manager.raceFinished && ticks < 60 * 40) {
            val human = manager.cars.first { it.isHuman }
            val humanAiInput = AiDriver.computeInput(human, track)
            manager.step(humanAiInput, 1f / 60f)
            ticks++
        }

        assertTrue("All 4 cars should finish the 1-lap race", manager.raceFinished)
        val standings = manager.standings()
        assertEquals(listOf(1, 2, 3, 4), standings.map { it.finishOrder })
    }

    @Test
    fun carToCarCollisionSeparatesOverlappingCars() {
        val track = loopTrack()
        val carA = CarState(
            id = 0, name = "A", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.50f, 0.50f), heading = 0f
        )
        val carB = CarState(
            id = 1, name = "B", isHuman = false, colorHex = 0xFF1E88E5L,
            pos = Vec2(0.51f, 0.50f), heading = 0f
        )
        val manager = RaceManager(track = track, initialCars = listOf(carA, carB), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val dist = manager.cars[0].pos.distanceTo(manager.cars[1].pos)
        assertTrue("Overlapping cars should be separated to >= 2*CAR_RADIUS", dist >= PhysicsStep.CAR_RADIUS * 1.95f)
    }

    @Test
    fun missingOneWaypointStillCompletesLap() {
        val track = loopTrack(laps = 2)
        // Start at wp 3 (final waypoint) after missing only 1 waypoint out of 4?
        // Use a 6-waypoint track so 1 missed = 16.7% (< 30%) and consecutive = 1 (< 2)
        val sixWpTrack = TrackMetadata(
            name = "Six",
            laps = 2,
            trackWidthNorm = 0.14f,
            waypoints = listOf(
                Vec2(0.7f, 0.8f), Vec2(0.8f, 0.5f), Vec2(0.7f, 0.2f),
                Vec2(0.3f, 0.2f), Vec2(0.2f, 0.5f), Vec2(0.3f, 0.8f)
            ),
            finishLine = FinishLineNode(Vec2(0.5f, 0.80f), 0f)
        )
        val car = CarState(
            id = 0, name = "A", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.50f, 0.80f), // Crossing finish line with awaitingFinishLine
            heading = 0f, lap = 1, nextWaypointIndex = 0, awaitingFinishLine = true
        )
        val manager = RaceManager(track = sixWpTrack, initialCars = listOf(car), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val updated = manager.cars.first()
        assertEquals(2, updated.lap)
        assertEquals(0f, updated.invalidLapTimer, 1e-4f)
    }

    @Test
    fun missingTwoWaypointsInARowInvalidatesLapImmediately() {
        val sixWpTrack = TrackMetadata(
            name = "Six",
            laps = 2,
            trackWidthNorm = 0.14f,
            waypoints = listOf(
                Vec2(0.7f, 0.8f), Vec2(0.8f, 0.5f), Vec2(0.7f, 0.2f),
                Vec2(0.3f, 0.2f), Vec2(0.2f, 0.5f), Vec2(0.3f, 0.8f)
            ),
            finishLine = FinishLineNode(Vec2(0.5f, 0.80f), 0f)
        )
        val car = CarState(
            id = 0, name = "A", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.2f, 0.5f), // Mid-lap at wp 4, while nextWaypointIndex is 2 (missed wp 2 & 3 in a row)
            heading = 0f, lap = 1, nextWaypointIndex = 2
        )
        val manager = RaceManager(track = sixWpTrack, initialCars = listOf(car), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val updated = manager.cars.first()
        assertTrue("Should mark lapInvalid immediately mid-lap", updated.lapInvalid)
        assertTrue("Should trigger invalidLapTimer immediately mid-lap", updated.invalidLapTimer > 0f)
    }

    @Test
    fun crossingStartLineWithMissedCheckpointsTriggersInvalidLapWarning() {
        val sixWpTrack = TrackMetadata(
            name = "Six",
            laps = 2,
            trackWidthNorm = 0.14f,
            waypoints = listOf(
                Vec2(0.7f, 0.8f), Vec2(0.8f, 0.5f), Vec2(0.7f, 0.2f),
                Vec2(0.3f, 0.2f), Vec2(0.2f, 0.5f), Vec2(0.3f, 0.8f)
            ),
            finishLine = FinishLineNode(Vec2(0.5f, 0.80f), 0f)
        )
        val car = CarState(
            id = 0,
            name = "Cutter",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.50f, 0.80f),
            vel = Vec2(0.2f, 0f),
            heading = 0f,
            lap = 1,
            nextWaypointIndex = 3
        )
        val manager = RaceManager(track = sixWpTrack, initialCars = listOf(car), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val updated = manager.cars.first()
        assertEquals(1, updated.lap)
        assertTrue("Should trigger invalidLapTimer > 0", updated.invalidLapTimer > 0f)
    }

    @Test
    fun validLapRecordsBestLapTimeWhileInvalidLapDoesNot() {
        val sixWpTrack = TrackMetadata(
            name = "Six",
            laps = 3,
            trackWidthNorm = 0.14f,
            waypoints = listOf(
                Vec2(0.7f, 0.8f), Vec2(0.8f, 0.5f), Vec2(0.7f, 0.2f),
                Vec2(0.3f, 0.2f), Vec2(0.2f, 0.5f), Vec2(0.3f, 0.8f)
            ),
            finishLine = FinishLineNode(Vec2(0.5f, 0.80f), 0f)
        )
        val validCar = CarState(
            id = 0, name = "Valid", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.50f, 0.80f), heading = 0f, lap = 1, nextWaypointIndex = 0,
            awaitingFinishLine = true, currentLapTime = 12.5f
        )
        val manager = RaceManager(track = sixWpTrack, initialCars = listOf(validCar), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val afterValid = manager.cars.first()
        assertEquals(2, afterValid.lap)
        assertEquals(12.5f + 1f / 60f, afterValid.bestLapTime!!, 1e-3f)
        assertEquals(1, afterValid.lapTimes.size)
        assertEquals(afterValid.bestLapTime!!, afterValid.lapTimes.first(), 1e-3f)
        assertEquals(0f, afterValid.currentLapTime, 1e-4f)

        // Now complete an invalid lap faster (5.0s) -> must NOT overwrite bestLapTime
        val invalidCar = afterValid.copy(
            pos = Vec2(0.50f, 0.80f),
            nextWaypointIndex = 0,
            awaitingFinishLine = true,
            lapInvalid = true,
            currentLapTime = 5.0f
        )
        val manager2 = RaceManager(track = sixWpTrack, initialCars = listOf(invalidCar), countdownTimer = 0f)
        manager2.step(InputState(), 1f / 60f)

        val afterInvalid = manager2.cars.first()
        assertEquals(2, afterInvalid.lap)
        assertEquals(afterValid.bestLapTime!!, afterInvalid.bestLapTime!!, 1e-3f)
    }

    @Test
    fun finishTimeoutEndsRaceAfterTwoEstimatedLapTimes() {
        val track = loopTrack(laps = 1)
        val finishedCar = CarState(
            id = 0, name = "Leader", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.50f, 0.75f), heading = 0f, finished = true, finishOrder = 1
        )
        val slowCar = CarState(
            id = 1, name = "Straggler", isHuman = false, colorHex = 0xFF1E88E5L,
            pos = Vec2(0.25f, 0.25f), heading = 0f, finished = false
        )
        val manager = RaceManager(
            track = track,
            initialCars = listOf(finishedCar, slowCar),
            countdownTimer = 0f
        )

        val timeoutTotal = manager.estimatedLapTime * 2f
        val steps = ((timeoutTotal + 0.5f) * 60f).toInt()
        repeat(steps) {
            manager.step(InputState(), 1f / 60f)
        }

        assertTrue("Race should finish after 2x estimated lap time timeout", manager.raceFinished)
    }

    @Test
    fun carDoesNotFinishBeforeVisibleBoundingBoxReachesFinishLine() {
        val track = loopTrack(laps = 1)
        // Finish line is at x = 0.45, y = 0.75. Car half-length = 0.022.
        // Place car approaching at x = 0.40 (front bumper at 0.422, clearly before x = 0.45)
        val approachingCar = CarState(
            id = 0, name = "A", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.40f, 0.75f), vel = Vec2(0.2f, 0f),
            heading = 0f, lap = 1, nextWaypointIndex = 0, awaitingFinishLine = true
        )
        val manager = RaceManager(track = track, initialCars = listOf(approachingCar), countdownTimer = 0f)
        manager.step(InputState(), 1f / 60f)

        val afterApproach = manager.cars.first()
        assertTrue("Car should NOT finish before its bounding box reaches the finish line", !afterApproach.finished)
        assertEquals(1, afterApproach.lap)

        // Now place car so its front bumper crosses x = 0.45 (center at 0.435 -> front at 0.457)
        val crossingCar = afterApproach.copy(pos = Vec2(0.435f, 0.75f))
        val manager2 = RaceManager(track = track, initialCars = listOf(crossingCar), countdownTimer = 0f)
        manager2.step(InputState(), 1f / 60f)

        val afterCross = manager2.cars.first()
        assertTrue("Car SHOULD finish once its visible bounding box crosses the finish line", afterCross.finished)
    }

    @Test
    fun finishLineExtendsBeyondTrackAndPolesAreOutsideTrack() {
        val track = loopTrack(laps = 1)
        val (a, b) = track.finishLineSegment()
        val lineLength = a.distanceTo(b)
        assertTrue("Finish line should extend beyond trackWidthNorm", lineLength > track.trackWidthNorm)

        val poles = track.finishLinePoles()
        assertEquals(2, poles.size)
        for (pole in poles) {
            val distFromCenter = pole.pos.distanceTo(track.finishLine.pos)
            assertTrue(
                "Pole inner edge should be outside track half-width",
                distFromCenter - pole.radius > track.trackWidthNorm * 0.5f
            )
        }
    }

    @Test
    fun aiTakesOverDrivingAfterHumanFinishes() {
        val track = loopTrack(laps = 1)
        // Human car has finished, facing straight (+x) but next waypoint is up-right (0.75, 0.25)
        val finishedHuman = CarState(
            id = 0, name = "YOU", isHuman = true, colorHex = 0xFFE53935L,
            pos = Vec2(0.75f, 0.75f), heading = 0f,
            nextWaypointIndex = 1, finished = true, finishOrder = 1
        )
        val manager = RaceManager(track = track, initialCars = listOf(finishedHuman), countdownTimer = 0f)
        // Pass empty humanInput; AI should steer toward waypoint 1 (negative y -> negative heading)
        repeat(10) {
            manager.step(InputState(), 1f / 60f)
        }
        val after = manager.cars.first()
        assertTrue("AI should steer finished human car toward next waypoint", after.heading < -0.05f)
    }
}

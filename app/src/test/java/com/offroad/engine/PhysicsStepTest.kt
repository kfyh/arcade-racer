package com.offroad.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsStepTest {

    private fun simpleTrack(
        ramps: List<RampZone> = emptyList(),
        gaps: List<GapZone> = emptyList()
    ): TrackMetadata {
        return TrackMetadata(
            name = "Test Track",
            laps = 3,
            waypoints = listOf(Vec2(0.8f, 0.5f), Vec2(0.2f, 0.5f)),
            ramps = ramps,
            gaps = gaps
        )
    }

    @Test
    fun autoAccelerateIncreasesSpeedWithEmptyInput() {
        val track = simpleTrack()
        var car = CarState(
            id = 0,
            name = "Test",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.2f, 0.5f),
            heading = 0f
        )

        repeat(15) {
            car = PhysicsStep.stepCar(car, InputState(), track, 1f / 60f)
        }

        assertTrue("Car should accelerate forward", car.vel.length() > 0.05f)
        assertTrue("Car should move right along +x", car.pos.x > 0.2f)
    }

    @Test
    fun singlePressBoostLatchesOnUntilDepletedAndDoesNotRecharge() {
        val track = simpleTrack()
        var boostCar = CarState(
            id = 1,
            name = "Boost",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.1f, 0.5f),
            heading = 0f
        )

        // Single 1-frame tap of boost
        boostCar = PhysicsStep.stepCar(boostCar, InputState(boost = true), track, 1f / 60f)
        assertTrue("Boost should latch ON after single press", boostCar.isBoosting)

        // Release boost button: it should stay ON and keep draining until depleted
        repeat(150) {
            boostCar = PhysicsStep.stepCar(boostCar, InputState(boost = false), track, 1f / 60f)
        }

        assertEquals(0f, boostCar.boostFuel, 1e-4f)
        assertTrue("Boost should turn OFF once depleted", !boostCar.isBoosting)

        // Wait another second: fuel must NOT recharge
        repeat(60) {
            boostCar = PhysicsStep.stepCar(boostCar, InputState(boost = false), track, 1f / 60f)
        }
        assertEquals(0f, boostCar.boostFuel, 1e-4f)
    }

    @Test
    fun offTrackScrubsAccelerationMoreAtHighSpeed() {
        val track = TrackMetadata(
            name = "Road",
            trackWidthNorm = 0.14f,
            waypoints = listOf(Vec2(0.1f, 0.5f), Vec2(0.9f, 0.5f))
        )
        // Fast car off-track at y=0.2 (far from road at y=0.5)
        val offTrackFast = CarState(
            id = 0,
            name = "FastOff",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.3f, 0.2f),
            vel = Vec2(0.35f, 0f),
            heading = 0f
        )
        val onTrackFast = offTrackFast.copy(pos = Vec2(0.3f, 0.5f))

        val steppedOff = PhysicsStep.stepCar(offTrackFast, InputState(), track, 1f / 60f)
        val steppedOn = PhysicsStep.stepCar(onTrackFast, InputState(), track, 1f / 60f)

        assertTrue(
            "Off-track fast car (${steppedOff.vel.length()}) should be slower than on-track (${steppedOn.vel.length()})",
            steppedOff.vel.length() < steppedOn.vel.length()
        )
    }

    @Test
    fun rampLaunchAllowsCrossingGapWithoutRespawn() {
        val ramp = RampZone(x = 0.25f, y = 0.4f, width = 0.10f, height = 0.2f, impulse = 0.55f)
        val gap = GapZone(x = 0.36f, y = 0.4f, width = 0.12f, height = 0.2f)
        val track = simpleTrack(ramps = listOf(ramp), gaps = listOf(gap))

        val safePos = Vec2(0.15f, 0.5f)
        var car = CarState(
            id = 0,
            name = "Jumper",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.28f, 0.5f),
            vel = Vec2(0.35f, 0f),
            heading = 0f,
            lastSafePos = safePos,
            lastSafeHeading = 0f
        )

        var maxZ = 0f
        repeat(60) {
            car = PhysicsStep.stepCar(car, InputState(), track, 1f / 60f)
            if (car.z > maxZ) maxZ = car.z
        }

        assertTrue("Car should become airborne from ramp", maxZ > 0.05f)
        assertTrue("Car should clear the gap and not respawn back to x=0.15", car.pos.x > 0.48f)
    }

    @Test
    fun enteringGapGroundedRespawnsCarToLastSafePos() {
        val gap = GapZone(x = 0.30f, y = 0.4f, width = 0.15f, height = 0.2f)
        val track = simpleTrack(gaps = listOf(gap))
        val safePos = Vec2(0.15f, 0.5f)

        val car = CarState(
            id = 0,
            name = "Faller",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.32f, 0.5f),
            vel = Vec2(0.2f, 0f),
            heading = 0.5f,
            z = 0f,
            lastSafePos = safePos,
            lastSafeHeading = 0f
        )

        val stepped = PhysicsStep.stepCar(car, InputState(), track, 1f / 60f)

        assertEquals(safePos.x, stepped.pos.x, 1e-4f)
        assertEquals(safePos.y, stepped.pos.y, 1e-4f)
        assertEquals(0f, stepped.vel.length(), 1e-4f)
        assertEquals(0f, stepped.heading, 1e-4f)
    }

    @Test
    fun steeringRotatesLinearlyAndRedirectsThrust() {
        val track = simpleTrack()
        var car = CarState(
            id = 0,
            name = "Turner",
            isHuman = true,
            colorHex = 0xFFE53935L,
            pos = Vec2(0.5f, 0.5f),
            heading = 0f
        )

        repeat(15) {
            car = PhysicsStep.stepCar(car, InputState(right = true), track, 1f / 60f)
        }

        assertTrue("Car heading should turn > 0.8 rad in 0.25s (got ${car.heading})", car.heading > 0.8f)
        assertTrue("Car velocity should redirect toward +y after turning right (vy=${car.vel.y})", car.vel.y > 0.02f)
    }
}

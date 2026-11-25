package com.offroad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import com.offroad.engine.CarState
import com.offroad.engine.TrackMetadata
import com.offroad.engine.Vec2
import kotlin.math.PI

@Composable
fun GameCanvas(
    trackBitmap: ImageBitmap,
    track: TrackMetadata,
    cars: List<CarState>,
    showDebugOverlay: Boolean,
    cameraPos: Vec2 = Vec2(0.5f, 0.5f),
    cameraZoom: Float = 1f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val invScale = 1f / track.worldScale.coerceAtLeast(1f)

        if (cameraZoom <= 1.01f) {
            // Unfolded / Tablet overview: whole track fills the canvas
            drawImage(
                image = trackBitmap,
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            )
            if (showDebugOverlay) {
                val humanCar = cars.firstOrNull { it.isHuman }
                drawDebugOverlay(track, humanCar, w, h)
            }
            val sortedCars = cars.sortedBy { it.z }
            for (car in sortedCars) {
                drawCar(car, w, h, invScale)
            }
        } else {
            // Zoomed camera mode: preserve square world aspect ratio
            val worldW = w * cameraZoom
            val worldH = w * cameraZoom
            val offsetX = (w * 0.5f) - (cameraPos.x * worldW)
            val offsetY = (h * 0.5f) - (cameraPos.y * worldH)

            withTransform({
                translate(left = offsetX, top = offsetY)
            }) {
                drawImage(
                    image = trackBitmap,
                    dstSize = IntSize(worldW.toInt().coerceAtLeast(1), worldH.toInt().coerceAtLeast(1))
                )
                if (showDebugOverlay) {
                    val humanCar = cars.firstOrNull { it.isHuman }
                    drawDebugOverlay(track, humanCar, worldW, worldH)
                }
                val sortedCars = cars.sortedBy { it.z }
                for (car in sortedCars) {
                    drawCar(car, worldW, worldH, invScale)
                }
            }
        }
    }
}

private fun DrawScope.drawDebugOverlay(
    track: TrackMetadata,
    humanCar: CarState?,
    w: Float,
    h: Float
) {
    // Start line
    drawLine(
        color = Color.White,
        start = Offset(track.startLine.a.x * w, track.startLine.a.y * h),
        end = Offset(track.startLine.b.x * w, track.startLine.b.y * h),
        strokeWidth = 4f
    )

    // Ramps (yellow)
    for (ramp in track.ramps) {
        drawRect(
            color = Color.Yellow.copy(alpha = 0.85f),
            topLeft = Offset(ramp.x * w, ramp.y * h),
            size = Size(ramp.width * w, ramp.height * h),
            style = Stroke(width = 3f)
        )
    }

    // Gaps (blue)
    for (gap in track.gaps) {
        drawRect(
            color = Color.Cyan.copy(alpha = 0.85f),
            topLeft = Offset(gap.x * w, gap.y * h),
            size = Size(gap.width * w, gap.height * h),
            style = Stroke(width = 3f)
        )
    }

    // Obstacles (red)
    for (obs in track.obstacles) {
        drawCircle(
            color = Color.Red.copy(alpha = 0.85f),
            radius = obs.radius * w,
            center = Offset(obs.pos.x * w, obs.pos.y * h),
            style = Stroke(width = 3f)
        )
    }

    // Waypoints and reached state
    val wps = track.waypoints
    val reached = humanCar?.reachedWaypointsThisLap.orEmpty()
    val nextIdx = humanCar?.nextWaypointIndex ?: 0

    for (i in wps.indices) {
        val a = wps[i]
        val b = wps[(i + 1) % wps.size]
        val center = Offset(a.x * w, a.y * h)
        val isReached = i in reached
        val isNext = i == nextIdx

        drawLine(
            color = Color.Green.copy(alpha = 0.45f),
            start = center,
            end = Offset(b.x * w, b.y * h),
            strokeWidth = 2f
        )

        // Waypoint dot (lights up bright green with white core when reached)
        if (isReached) {
            drawCircle(color = Color(0xFF00E676), radius = 10f, center = center)
            drawCircle(color = Color.White, radius = 4.5f, center = center)
        } else if (isNext) {
            drawCircle(color = Color(0xFFFFD54F), radius = 8f, center = center)
        } else {
            drawCircle(color = Color.Green.copy(alpha = 0.65f), radius = 5f, center = center)
        }
    }
}

private fun DrawScope.drawCar(car: CarState, w: Float, h: Float, invScale: Float = 1f) {
    val groundX = car.pos.x * w
    val groundY = car.pos.y * h
    val carLength = w * 0.044f * invScale
    val carWidth = w * 0.028f * invScale

    // Ground shadow
    val shadowScale = (1f - car.z * 0.4f).coerceAtLeast(0.5f)
    withTransform({
        translate(left = groundX, top = groundY)
        rotate(degrees = (car.heading * 180f / PI.toFloat()), pivot = Offset.Zero)
    }) {
        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(-carLength * 0.5f * shadowScale, -carWidth * 0.5f * shadowScale),
            size = Size(carLength * shadowScale, carWidth * shadowScale)
        )
    }

    // Airborne car offset and scale
    val drawY = groundY - car.z * h * 0.15f
    val scale = 1f + car.z * 0.8f
    val bodyColor = Color(car.colorHex)

    withTransform({
        translate(left = groundX, top = drawY)
        rotate(degrees = (car.heading * 180f / PI.toFloat()), pivot = Offset.Zero)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        // Boost flame behind car
        if (car.isBoosting && car.boostFuel > 0f) {
            val flamePath = Path().apply {
                moveTo(-carLength * 0.5f, -carWidth * 0.25f)
                lineTo(-carLength * 0.95f, 0f)
                lineTo(-carLength * 0.5f, carWidth * 0.25f)
                close()
            }
            drawPath(flamePath, color = Color(0xFFFF9800))
        }

        // Wheels
        val wheelW = carLength * 0.24f
        val wheelH = carWidth * 0.22f
        val wheelOffsets = listOf(
            Offset(-carLength * 0.34f, -carWidth * 0.58f),
            Offset(carLength * 0.14f, -carWidth * 0.58f),
            Offset(-carLength * 0.34f, carWidth * 0.36f),
            Offset(carLength * 0.14f, carWidth * 0.36f)
        )
        for (wo in wheelOffsets) {
            drawRect(color = Color(0xFF1A1A1A), topLeft = wo, size = Size(wheelW, wheelH))
        }

        // Car body
        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(-carLength * 0.5f, -carWidth * 0.5f),
            size = Size(carLength, carWidth),
            cornerRadius = CornerRadius(carWidth * 0.25f, carWidth * 0.25f)
        )

        // Windshield (front)
        drawRoundRect(
            color = Color(0xFF263238),
            topLeft = Offset(carLength * 0.05f, -carWidth * 0.34f),
            size = Size(carLength * 0.22f, carWidth * 0.68f),
            cornerRadius = CornerRadius(3f, 3f)
        )

        // Human player highlight ring
        if (car.isHuman) {
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = carLength * 0.68f,
                center = Offset.Zero,
                style = Stroke(width = 2.5f)
            )
        }
    }
}

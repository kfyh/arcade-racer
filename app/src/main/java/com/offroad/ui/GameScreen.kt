package com.offroad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offroad.data.LoadedTrack
import com.offroad.engine.CarState
import com.offroad.engine.InputState
import com.offroad.engine.RaceManager
import com.offroad.engine.Vec2
import com.offroad.engine.lerp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GameScreen(
    loadedTrack: LoadedTrack,
    externalKeyInput: InputState = InputState(),
    sessionBestLap: Float? = null,
    onNewSessionBestLap: (Float) -> Unit = {},
    onBackToMenu: () -> Unit
) {
    var raceSeed by remember { mutableIntStateOf(0) }
    val raceManager = remember(loadedTrack.id, raceSeed) {
        RaceManager(track = loadedTrack.metadata)
    }

    var cars by remember(raceManager) { mutableStateOf(raceManager.cars) }
    var countdown by remember(raceManager) { mutableStateOf(raceManager.countdownTimer) }
    var position by remember(raceManager) { mutableIntStateOf(1) }
    var showResults by remember(raceManager) { mutableStateOf(false) }
    var showDebugOverlay by remember { mutableStateOf(false) }
    var cameraPos by remember(raceManager) {
        val startCar = raceManager.cars.firstOrNull { it.isHuman }
        mutableStateOf(startCar?.pos ?: Vec2(0.5f, 0.5f))
    }

    var touchLeft by remember { mutableStateOf(false) }
    var touchRight by remember { mutableStateOf(false) }
    var pendingBoostSwipe by remember { mutableStateOf(false) }

    val latestKeyInput = remember { mutableStateOf(externalKeyInput) }
    latestKeyInput.value = externalKeyInput

    val humanCar = cars.firstOrNull { it.isHuman }
    val currentLap = (humanCar?.lap ?: 1).coerceAtMost(loadedTrack.metadata.laps)
    val totalLaps = loadedTrack.metadata.laps
    val currentLapTime = humanCar?.currentLapTime ?: 0f
    val displayBestLap = sessionBestLap ?: humanCar?.bestLapTime
    val boostFrac = ((humanCar?.boostFuel ?: 0f) / (humanCar?.stats?.boostCapacity ?: 100f)).coerceIn(0f, 1f)
    val showInvalidLap = humanCar?.lapInvalid == true || (humanCar?.invalidLapTimer ?: 0f) > 0f
    val invalidLapReason = humanCar?.invalidLapReason?.ifEmpty { "MISSED CHECKPOINT" } ?: "MISSED CHECKPOINT"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12151A))
            .safeDrawingPadding()
    ) {
        val aspectRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1.77f
        val isHandheldMode = aspectRatio >= 1.55f
        val baseZoom = if (!isHandheldMode) {
            1.0f
        } else {
            val viewportHeightDp = minOf(maxHeight.value, maxWidth.value / 1.5f)
                .coerceAtLeast(180f)
            (720f / viewportHeightDp).coerceIn(1.25f, 2.25f)
        }
        val activeZoom = baseZoom * loadedTrack.metadata.worldScale.coerceAtLeast(1f)
        val viewportAspect = if (isHandheldMode) 1.5f else aspectRatio.coerceAtLeast(0.5f)
        val latestActiveZoom = remember { mutableStateOf(activeZoom) }
        latestActiveZoom.value = activeZoom
        val latestViewportAspect = remember { mutableStateOf(viewportAspect) }
        latestViewportAspect.value = viewportAspect

        // Fixed-timestep 60 Hz simulation loop
        LaunchedEffect(raceManager) {
            val fixedDt = 1f / 60f
            var accumulator = 0f
            var lastNanos = 0L

            while (true) {
                withFrameNanos { frameNanos ->
                    if (lastNanos == 0L) {
                        lastNanos = frameNanos
                        return@withFrameNanos
                    }
                    val frameDt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    lastNanos = frameNanos
                    accumulator += frameDt

                    val keyIn = latestKeyInput.value
                    val input = InputState(
                        left = touchLeft || keyIn.left,
                        right = touchRight || keyIn.right,
                        boost = pendingBoostSwipe || keyIn.boost,
                        brake = false
                    )

                    var stepped = false
                    while (accumulator >= fixedDt) {
                        raceManager.step(input, fixedDt)
                        accumulator -= fixedDt
                        stepped = true
                    }

                    if (stepped) {
                        pendingBoostSwipe = false
                        cars = raceManager.cars
                        countdown = raceManager.countdownTimer
                        position = raceManager.humanPosition()

                        val human = raceManager.cars.firstOrNull { it.isHuman }
                        if (human != null) {
                            val best = human.bestLapTime
                            if (best != null && (sessionBestLap == null || best < sessionBestLap)) {
                                onNewSessionBestLap(best)
                            }
                            val zoom = latestActiveZoom.value
                            val viewSpanX = 1f / zoom
                            val viewSpanY = viewSpanX / latestViewportAspect.value
                            val fwd = Vec2(cos(human.heading), sin(human.heading))
                            val target = Vec2(
                                x = (human.pos.x + fwd.x * (viewSpanX / 6f))
                                    .coerceIn(viewSpanX * 0.5f, 1f - viewSpanX * 0.5f),
                                y = (human.pos.y + fwd.y * (viewSpanY / 6f))
                                    .coerceIn(viewSpanY * 0.5f, 1f - viewSpanY * 0.5f)
                            )
                            val camLerp = (6.5f * fixedDt).coerceIn(0f, 1f)
                            cameraPos = Vec2(
                                x = lerp(cameraPos.x, target.x, camLerp),
                                y = lerp(cameraPos.y, target.y, camLerp)
                            )
                        }

                        if (raceManager.humanFinished || raceManager.raceFinished) {
                            showResults = true
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Stats & Action Bar (kept completely clear of steering touch zone)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1E24))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "POS ${ordinal(position)}  LAP $currentLap/$totalLaps  ${formatLapTime(currentLapTime)}  BEST ${formatLapTime(displayBestLap)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BOOST ", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = { boostFrac },
                            color = Color(0xFFFF9800),
                            trackColor = Color(0xFF37474F),
                            modifier = Modifier
                                .width(72.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }

                    if (showResults) {
                        Button(
                            onClick = {
                                raceSeed++
                                showResults = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text("Again", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    OutlinedButton(onClick = { showDebugOverlay = !showDebugOverlay }) {
                        Text(if (showDebugOverlay) "DBG*" else "DBG", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onBackToMenu) {
                        Text("MENU", fontSize = 11.sp)
                    }
                }
            }

            // Playable Area + Full-Area Left/Deadzone/Right + Swipe-Up Boost
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val canvasModifier = if (isHandheldMode) {
                    if (maxWidth / maxHeight > 1.5f) {
                        Modifier.fillMaxHeight().aspectRatio(1.5f)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(1.5f)
                    }
                } else {
                    Modifier.fillMaxSize()
                }

                GameCanvas(
                    trackBitmap = loadedTrack.bitmap,
                    track = loadedTrack.metadata,
                    cars = cars,
                    showDebugOverlay = showDebugOverlay,
                    cameraPos = cameraPos,
                    cameraZoom = activeZoom,
                    modifier = canvasModifier
                )

                TouchSteerAndBoostOverlay(
                    onSteerChange = { left, right ->
                        touchLeft = left
                        touchRight = right
                    },
                    onBoostSwipe = {
                        pendingBoostSwipe = true
                    },
                    modifier = Modifier.fillMaxSize()
                )

                InvalidLapFlag(
                    visible = showInvalidLap,
                    reason = invalidLapReason,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )

                CountdownOverlay(
                    countdown = countdown,
                    modifier = Modifier.align(Alignment.Center)
                )

                if (showResults) {
                    val drivesRightAcrossLine = cos(loadedTrack.metadata.startLine.heading) >= 0f
                    ResultsOverlay(
                        standings = raceManager.standings(),
                        sessionBestLap = sessionBestLap,
                        finishTimeout = if (!raceManager.raceFinished) raceManager.finishTimeoutTimer else null,
                        modifier = Modifier
                            .align(if (drivesRightAcrossLine) Alignment.TopStart else Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InvalidLapFlag(
    visible: Boolean,
    reason: String = "MISSED CHECKPOINT",
    modifier: Modifier = Modifier
) {
    if (!visible) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6C62828))
            .border(2.dp, Color(0xFFFF8A80), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(14.dp)
                .background(Color(0xFFFF1744), RoundedCornerShape(2.dp))
                .border(1.dp, Color.White, RoundedCornerShape(2.dp))
        )
        Text(
            text = "INVALID LAP — $reason",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CountdownOverlay(countdown: Float, modifier: Modifier = Modifier) {
    if (countdown <= 0f) return
    val text = ceil(countdown.toDouble()).toInt().coerceIn(1, 3).toString()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xBB000000))
            .padding(horizontal = 32.dp, vertical = 16.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFFFFD54F),
            fontSize = 54.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun ResultsOverlay(
    standings: List<CarState>,
    sessionBestLap: Float?,
    finishTimeout: Float? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(210.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC1E242B))
            .border(1.5.dp, Color(0xFFFFB74D), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "STANDINGS",
            color = Color(0xFFFFD54F),
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
        )
        if (finishTimeout != null) {
            Text(
                text = "ENDS IN ${ceil(finishTimeout.toDouble()).toInt()}s",
                color = Color(0xFFFF8A80),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (sessionBestLap != null) {
            Text(
                text = "SESSION BEST: ${formatLapTime(sessionBestLap)}",
                color = Color(0xFF80CBC4),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        standings.forEachIndexed { idx, car ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (car.isHuman) Color(0x44E53935) else Color(0x22FFFFFF))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${ordinal(idx + 1)} ${car.name}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = if (car.isHuman) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = if (car.bestLapTime != null) {
                        formatLapTime(car.bestLapTime)
                    } else if (car.finished) {
                        "FIN"
                    } else {
                        "L${car.lap}"
                    },
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun formatLapTime(seconds: Float?): String {
    if (seconds == null) return "--.--"
    val mins = (seconds / 60f).toInt()
    val secs = seconds - mins * 60f
    return if (mins > 0) {
        String.format(Locale.US, "%d:%05.2f", mins, secs)
    } else {
        String.format(Locale.US, "%.2fs", secs)
    }
}

private fun ordinal(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}

package com.offroad.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offroad.audio.SoundEvent
import com.offroad.audio.SoundManager
import com.offroad.data.LoadedTrack
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
    onBackToMenu: () -> Unit,
    onNextTrack: () -> Unit = {}
) {
    var raceSeed by remember { mutableIntStateOf(0) }
    val raceManager = remember(loadedTrack.id, raceSeed) {
        RaceManager(track = loadedTrack.metadata)
    }

    var cars by remember(raceManager) { mutableStateOf(raceManager.cars) }
    var countdown by remember(raceManager) { mutableStateOf(raceManager.countdownTimer) }
    var position by remember(raceManager) { mutableIntStateOf(1) }
    var finishElapsed by remember(raceManager) { mutableStateOf<Float?>(null) }
    var showDebugOverlay by remember(raceManager) { mutableStateOf(false) }
    var showInGameMenu by remember(raceManager) { mutableStateOf(false) }
    var cameraPos by remember(raceManager) {
        val startCar = raceManager.cars.firstOrNull { it.isHuman }
        mutableStateOf(startCar?.pos ?: Vec2(0.5f, 0.5f))
    }

    var touchLeft by remember { mutableStateOf(false) }
    var touchRight by remember { mutableStateOf(false) }
    var pendingBoostSwipe by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val soundManager = remember { SoundManager(context) }
    DisposableEffect(Unit) {
        onDispose { soundManager.release() }
    }

    val latestKeyInput = remember { mutableStateOf(externalKeyInput) }
    latestKeyInput.value = externalKeyInput

    val humanCar = cars.firstOrNull { it.isHuman }
    val currentLap = (humanCar?.lap ?: 1).coerceAtMost(loadedTrack.metadata.laps)
    val totalLaps = loadedTrack.metadata.laps
    val currentLapTime = humanCar?.currentLapTime ?: 0f
    val displayBestLap = sessionBestLap ?: humanCar?.bestLapTime
    val showInvalidLap = humanCar?.lapInvalid == true || (humanCar?.invalidLapTimer ?: 0f) > 0f
    val invalidLapReason = humanCar?.invalidLapReason?.ifEmpty { "MISSED CHECKPOINT" } ?: "MISSED CHECKPOINT"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12151A))
            .safeDrawingPadding()
    ) {
        val aspectRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1.77f
        val activeZoom = loadedTrack.metadata.worldScale.coerceAtLeast(0.1f)
        val latestActiveZoom = remember { mutableStateOf(activeZoom) }
        latestActiveZoom.value = activeZoom
        val latestViewportAspect = remember { mutableStateOf(aspectRatio.coerceAtLeast(0.5f)) }

        // Fixed-timestep 60 Hz simulation loop
        LaunchedEffect(raceManager) {
            val fixedDt = 1f / 60f
            var accumulator = 0f
            var lastNanos = 0L
            var lastBeepDigit = -1
            var prevCountdown = raceManager.countdownTimer
            var wasAirborne = false

            while (true) {
                withFrameNanos { frameNanos ->
                    if (lastNanos == 0L) {
                        lastNanos = frameNanos
                        return@withFrameNanos
                    }
                    val frameDt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    lastNanos = frameNanos
                    if (showInGameMenu) {
                        accumulator = 0f
                        soundManager.dispatch(SoundEvent.EngineUpdate(0f, false, false, isMuted = true))
                        return@withFrameNanos
                    }
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

                        if (countdown > 0f) {
                            val digit = ceil(countdown.toDouble()).toInt().coerceIn(1, 3)
                            if (digit != lastBeepDigit) {
                                lastBeepDigit = digit
                                soundManager.dispatch(SoundEvent.CountdownBeep)
                            }
                        } else if (prevCountdown > 0f) {
                            soundManager.dispatch(SoundEvent.CountdownGo)
                        }
                        prevCountdown = countdown

                        val human = raceManager.cars.firstOrNull { it.isHuman }
                        if (human != null) {
                            val invScale = 1f / activeZoom
                            val effectiveMax = (human.stats.maxSpeed * invScale).coerceAtLeast(1e-4f)
                            val speed = human.vel.length()
                            val speedRatio = (speed / effectiveMax).coerceIn(0f, 1.5f)
                            val airborne = human.z > 0.01f
                            if (!wasAirborne && airborne && human.vz > 0.05f) {
                                soundManager.dispatch(SoundEvent.Jump)
                            }
                            wasAirborne = airborne

                            soundManager.dispatch(
                                SoundEvent.EngineUpdate(
                                    speedRatio = speedRatio,
                                    isBoosting = human.isBoosting,
                                    isAirborne = airborne,
                                    isMuted = raceManager.humanFinished
                                )
                            )
                            val best = human.bestLapTime
                            if (best != null && (sessionBestLap == null || best < sessionBestLap)) {
                                onNewSessionBestLap(best)
                            }
                            val zoom = latestActiveZoom.value
                            val vpAspect = latestViewportAspect.value
                            val viewSpanX = if (vpAspect >= 1f) 1f / zoom else (1f / zoom) * vpAspect
                            val viewSpanY = if (vpAspect >= 1f) (1f / zoom) / vpAspect else 1f / zoom
                            val fwd = Vec2(cos(human.heading), sin(human.heading))
                            val target = Vec2(
                                x = if (viewSpanX >= 1f) {
                                    0.5f
                                } else {
                                    (human.pos.x + fwd.x * (viewSpanX / 6f))
                                        .coerceIn(viewSpanX * 0.5f, 1f - viewSpanX * 0.5f)
                                },
                                y = if (viewSpanY >= 1f) {
                                    0.5f
                                } else {
                                    (human.pos.y + fwd.y * (viewSpanY / 6f))
                                        .coerceIn(viewSpanY * 0.5f, 1f - viewSpanY * 0.5f)
                                }
                            )
                            val camLerp = (6.5f * fixedDt).coerceIn(0f, 1f)
                            cameraPos = Vec2(
                                x = lerp(cameraPos.x, target.x, camLerp),
                                y = lerp(cameraPos.y, target.y, camLerp)
                            )
                        }

                        if (raceManager.humanFinished || raceManager.raceFinished) {
                            finishElapsed = (finishElapsed ?: 0f) + fixedDt
                        }
                    }
                }
            }
        }

        val textMeasurer = rememberTextMeasurer()
        val hudTextStyle = TextStyle(
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Stats & Hamburger Menu Bar (kept completely clear of steering touch zone)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1E24))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    val lapPart = "LAP $currentLap/$totalLaps"
                    val posPart = "POS ${ordinal(position)}"
                    val timePart = formatLapTime(currentLapTime)
                    val bestPart = "BEST ${formatLapTime(displayBestLap)}"
                    val candidates = listOf(
                        "$lapPart  $posPart  $timePart  $bestPart",
                        "$lapPart  $posPart  $timePart",
                        "$lapPart  $posPart",
                        lapPart
                    )
                    val maxPx = constraints.maxWidth
                    val displayStats = candidates.firstOrNull { candidate ->
                        textMeasurer.measure(candidate, style = hudTextStyle).size.width <= maxPx
                    } ?: lapPart

                    Text(
                        text = displayStats,
                        style = hudTextStyle,
                        maxLines = 1
                    )
                }

                HamburgerButton(onClick = { showInGameMenu = !showInGameMenu })
            }

            // Playable Area + Full-Area Left/Deadzone/Right + Swipe-Up Boost
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (maxHeight > 0.dp) {
                    latestViewportAspect.value = (maxWidth / maxHeight).coerceAtLeast(0.5f)
                }

                GameCanvas(
                    trackBitmap = loadedTrack.bitmap,
                    track = loadedTrack.metadata,
                    cars = cars,
                    showDebugOverlay = showDebugOverlay,
                    cameraPos = cameraPos,
                    cameraZoom = activeZoom,
                    modifier = Modifier.fillMaxSize()
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

                val elapsed = finishElapsed
                val bannerVisible = elapsed != null && elapsed >= 0.5f && elapsed < 2.5f
                val statsVisible = elapsed != null && elapsed >= 2.7f
                val bannerAlpha by animateFloatAsState(
                    targetValue = if (bannerVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = 400),
                    label = "bannerAlpha"
                )
                val statsAlpha by animateFloatAsState(
                    targetValue = if (statsVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = 350),
                    label = "statsAlpha"
                )
                val finalPlace = humanCar?.finishOrder?.takeIf { it > 0 } ?: position

                if (bannerAlpha > 0.01f) {
                    FinishPlacementBanner(
                        place = finalPlace,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer { alpha = bannerAlpha }
                    )
                }

                if (statsVisible && statsAlpha > 0.01f) {
                    PlayerStatsOverlay(
                        place = finalPlace,
                        bestLap = humanCar?.bestLapTime,
                        lapTimes = humanCar?.lapTimes.orEmpty(),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer { alpha = statsAlpha }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .graphicsLayer { alpha = statsAlpha }
                    ) {
                        OutlinedButton(
                            onClick = onBackToMenu,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xCC1E242B),
                                contentColor = Color.White
                            )
                        ) {
                            Text("MENU", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Button(
                            onClick = onNextTrack,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text("NEXT", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }
                }

                if (showInGameMenu) {
                    InGameMenuOverlay(
                        trackName = loadedTrack.metadata.name,
                        showDebugOverlay = showDebugOverlay,
                        onResume = { showInGameMenu = false },
                        onRestart = {
                            raceSeed++
                            finishElapsed = null
                            showInGameMenu = false
                        },
                        onToggleDebug = { showDebugOverlay = !showDebugOverlay },
                        onMainMenu = {
                            showInGameMenu = false
                            onBackToMenu()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HamburgerButton(onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF263238))
            .border(1.dp, Color(0xFF455A64), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .background(Color.White, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun InGameMenuOverlay(
    trackName: String,
    showDebugOverlay: Boolean,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onToggleDebug: () -> Unit,
    onMainMenu: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onResume)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE1E242B))
                .border(1.5.dp, Color(0xFFFFB74D), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Text(
                text = "PAUSED",
                color = Color(0xFFFFD54F),
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = trackName,
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RESUME", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RESTART", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onToggleDebug,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showDebugOverlay) "DEBUG: ON" else "DEBUG: OFF",
                    color = if (showDebugOverlay) Color(0xFF80CBC4) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            OutlinedButton(
                onClick = onMainMenu,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("MAIN MENU", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
private fun FinishPlacementBanner(place: Int, modifier: Modifier = Modifier) {
    val text = if (place == 1) "You Won!" else "${ordinal(place)} Place"
    val accent = if (place == 1) Color(0xFFFFD54F) else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC12151A))
            .border(2.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
            .padding(horizontal = 36.dp, vertical = 18.dp)
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun PlayerStatsOverlay(
    place: Int,
    bestLap: Float?,
    lapTimes: List<Float>,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xDD1E242B))
            .border(1.5.dp, Color(0xFFFFB74D), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = if (place == 1) "YOU WON! — 1ST PLACE" else "${ordinal(place).uppercase()} PLACE",
            color = Color(0xFFFFD54F),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x33FF9800))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "FASTEST LAP",
                color = Color(0xFFFFCC80),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formatLapTime(bestLap),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        if (lapTimes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            lapTimes.forEachIndexed { idx, time ->
                val isFastest = bestLap != null && kotlin.math.abs(time - bestLap) < 1e-4f
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isFastest) Color(0x2280CBC4) else Color(0x18FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Lap ${idx + 1}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatLapTime(time),
                        color = if (isFastest) Color(0xFF80CBC4) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isFastest) FontWeight.Bold else FontWeight.Medium
                    )
                }
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

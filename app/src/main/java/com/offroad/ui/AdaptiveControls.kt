package com.offroad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-area touch control surface (placed below the top HUD bar):
 * - Left 40%: Hold to steer LEFT
 * - Middle 20%: Deadzone (no steering)
 * - Right 40%: Hold to steer RIGHT
 * - Swipe up (registered on finger lift): Trigger BOOST
 */
@Composable
fun TouchSteerAndBoostOverlay(
    onSteerChange: (left: Boolean, right: Boolean) -> Unit,
    onBoostSwipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose {
            onSteerChange(false, false)
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val swipeThresholdPx = 28.dp.toPx()
                awaitPointerEventScope {
                    val pointerX = mutableMapOf<Long, Float>()
                    val pointerStartY = mutableMapOf<Long, Float>()
                    val swipingUp = mutableSetOf<Long>()

                    while (true) {
                        val event = awaitPointerEvent()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        var swipeUpTriggered = false

                        for (change in event.changes) {
                            val id = change.id.value
                            val active = change.pressed &&
                                event.type != PointerEventType.Release &&
                                event.type != PointerEventType.Exit
                            if (active) {
                                val startY = pointerStartY.getOrPut(id) { change.position.y }
                                if (change.position.y - startY < -swipeThresholdPx) {
                                    swipingUp.add(id)
                                    pointerX.remove(id)
                                } else if (id !in swipingUp) {
                                    pointerX[id] = change.position.x
                                }
                            } else {
                                val startY = pointerStartY.remove(id)
                                val wasSwipingUp = swipingUp.remove(id)
                                pointerX.remove(id)
                                if (wasSwipingUp || (startY != null && change.position.y - startY < -swipeThresholdPx)) {
                                    swipeUpTriggered = true
                                }
                            }
                        }

                        var left = false
                        var right = false
                        for (x in pointerX.values) {
                            val nx = x / w
                            if (nx < 0.40f) left = true
                            else if (nx > 0.60f) right = true
                        }
                        if (left && right) {
                            left = false
                            right = false
                        }

                        onSteerChange(left, right)
                        if (swipeUpTriggered) {
                            onBoostSwipe()
                        }
                    }
                }
            }
    ) {
        // Subtle bottom hint labels
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "< STEER LEFT",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "SWIPE UP: BOOST",
                color = Color(0xFFFFB74D).copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "STEER RIGHT >",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

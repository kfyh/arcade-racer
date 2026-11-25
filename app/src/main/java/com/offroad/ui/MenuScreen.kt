package com.offroad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offroad.data.TrackSummary
import com.offroad.engine.SurfaceType

@Composable
fun MenuScreen(
    tracks: List<TrackSummary>,
    selectedTrackId: String?,
    onSelectTrack: (String) -> Unit,
    onStartRace: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12151A))
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1E242B))
                .border(2.dp, Color(0xFF37474F), RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = "OFFROAD PROTOTYPE",
                color = Color(0xFFFFD54F),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "4-Car Top-Down Arcade Racer",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (tracks.isEmpty()) {
                Text("No tracks found in assets/tracks/", color = Color(0xFFEF5350))
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tracks, key = { it.id }) { track ->
                        TrackTopoCard(
                            track = track,
                            selected = track.id == selectedTrackId,
                            onClick = { onSelectTrack(track.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartRace,
                enabled = selectedTrackId != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "START RACE",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Touch: Left/Right sides + Swipe Up Boost   |   Keyboard: A/D, Space/W",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TrackTopoCard(
    track: TrackSummary,
    selected: Boolean,
    onClick: () -> Unit
) {
    val surfaceColor = when (track.defaultSurface) {
        SurfaceType.ASPHALT -> Color(0xFF90A4AE)
        SurfaceType.DIRT -> Color(0xFFD7A066)
        SurfaceType.SAND -> Color(0xFFE6C87A)
        SurfaceType.MUD -> Color(0xFFA1887F)
        SurfaceType.ICE -> Color(0xFF81D4FA)
    }
    val strokeColor = if (selected) Color(0xFFFFB74D) else surfaceColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x33FF9800) else Color(0xFF263238))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) Color(0xFFFF9800) else Color(0xFF37474F),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pts = track.waypoints
            if (pts.size < 2) return@Canvas

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }

            val span = maxOf(maxX - minX, maxY - minY, 0.01f)
            val pad = size.minDimension * 0.08f
            val drawSize = size.minDimension - pad * 2f
            val scale = drawSize / span
            val offsetX = (size.width - (maxX - minX) * scale) * 0.5f - minX * scale
            val offsetY = (size.height - (maxY - minY) * scale) * 0.5f - minY * scale

            val path = Path().apply {
                moveTo(pts[0].x * scale + offsetX, pts[0].y * scale + offsetY)
                for (i in 1 until pts.size) {
                    lineTo(pts[i].x * scale + offsetX, pts[i].y * scale + offsetY)
                }
                close()
            }

            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.45f),
                style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            track.finishLinePos?.let { fl ->
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = Offset(fl.x * scale + offsetX, fl.y * scale + offsetY)
                )
            }
        }
    }
}

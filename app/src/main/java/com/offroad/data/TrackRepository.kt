package com.offroad.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.offroad.engine.TrackMetadata
import kotlinx.serialization.json.Json

data class TrackSummary(
    val id: String,
    val name: String,
    val laps: Int
)

data class LoadedTrack(
    val id: String,
    val metadata: TrackMetadata,
    val bitmap: ImageBitmap
)

object TrackRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun listTracks(context: Context): List<TrackSummary> {
        val assetManager = context.assets
        val dirs = assetManager.list("tracks")?.sorted().orEmpty()
        return dirs.mapNotNull { id ->
            runCatching {
                val metaText = assetManager.open("tracks/$id/track_meta.json")
                    .bufferedReader()
                    .use { it.readText() }
                val meta = json.decodeFromString<TrackMetadata>(metaText)
                TrackSummary(id = id, name = meta.name, laps = meta.laps)
            }.getOrNull()
        }
    }

    fun loadTrack(context: Context, trackId: String): LoadedTrack {
        val assetManager = context.assets
        val metaText = assetManager.open("tracks/$trackId/track_meta.json")
            .bufferedReader()
            .use { it.readText() }
        val metadata = json.decodeFromString<TrackMetadata>(metaText)

        val bitmap = assetManager.open("tracks/$trackId/track.png").use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                ?: error("Failed to decode tracks/$trackId/track.png")
        }

        return LoadedTrack(id = trackId, metadata = metadata, bitmap = bitmap)
    }
}

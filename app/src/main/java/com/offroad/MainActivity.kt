package com.offroad

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.offroad.data.LoadedTrack
import com.offroad.data.TrackRepository
import com.offroad.engine.InputState
import com.offroad.ui.GameScreen
import com.offroad.ui.MenuScreen

class MainActivity : ComponentActivity() {
    private var keyInput by mutableStateOf(InputState())
    private var inGame by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val context = LocalContext.current
                val tracks = remember { TrackRepository.listTracks(context) }
                var selectedTrackId by remember { mutableStateOf(tracks.firstOrNull()?.id) }
                var activeTrack by remember { mutableStateOf<LoadedTrack?>(null) }
                var sessionBestLaps by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

                val current = activeTrack
                if (current == null) {
                    inGame = false
                    MenuScreen(
                        tracks = tracks,
                        selectedTrackId = selectedTrackId,
                        onSelectTrack = { selectedTrackId = it },
                        onStartRace = {
                            val id = selectedTrackId ?: return@MenuScreen
                            keyInput = InputState()
                            activeTrack = TrackRepository.loadTrack(context, id)
                            inGame = true
                        }
                    )
                } else {
                    inGame = true
                    GameScreen(
                        loadedTrack = current,
                        externalKeyInput = keyInput,
                        sessionBestLap = sessionBestLaps[current.id],
                        onNewSessionBestLap = { lapTime ->
                            val prev = sessionBestLaps[current.id]
                            if (prev == null || lapTime < prev) {
                                sessionBestLaps = sessionBestLaps + (current.id to lapTime)
                            }
                        },
                        onBackToMenu = {
                            inGame = false
                            keyInput = InputState()
                            activeTrack = null
                        }
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!inGame) return super.dispatchKeyEvent(event)

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        if (!isDown && !isUp) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> {
                keyInput = keyInput.copy(
                    left = isDown,
                    right = if (isDown) false else keyInput.right
                )
                return true
            }
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                keyInput = keyInput.copy(
                    right = isDown,
                    left = if (isDown) false else keyInput.left
                )
                return true
            }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP -> {
                keyInput = keyInput.copy(boost = isDown)
                return true
            }
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN -> {
                keyInput = keyInput.copy(brake = isDown)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

package com.offroad.audio

/**
 * Discrete and continuous sound events emitted by gameplay.
 */
sealed interface SoundEvent {
    data object CountdownBeep : SoundEvent
    data object CountdownGo : SoundEvent
    data object Jump : SoundEvent

    data class EngineUpdate(
        val speedRatio: Float,
        val isBoosting: Boolean,
        val isAirborne: Boolean,
        val isMuted: Boolean
    ) : SoundEvent
}

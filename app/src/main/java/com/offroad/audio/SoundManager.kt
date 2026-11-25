package com.offroad.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Asset-backed sound event dispatcher using Android's SoundPool.
 * Loads WAV files from app/src/main/assets/sounds/ and handles both
 * one-shot effects (countdown, jump) and pitch/volume-modulated engine loop.
 */
class SoundManager(context: Context) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedIds = mutableSetOf<Int>()
    private val countdownBeepId: Int
    private val countdownGoId: Int
    private val jumpId: Int
    private val engineLoopId: Int

    private var engineStreamId: Int = 0
    private var released = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && !released) {
                loadedIds.add(sampleId)
                if (sampleId == engineLoopId && engineStreamId == 0) {
                    engineStreamId = soundPool.play(engineLoopId, 0f, 0f, 1, -1, 0.75f)
                }
            }
        }

        countdownBeepId = loadAsset(context, "sounds/countdown_beep.wav")
        countdownGoId = loadAsset(context, "sounds/countdown_go.wav")
        jumpId = loadAsset(context, "sounds/jump.wav")
        engineLoopId = loadAsset(context, "sounds/engine_loop.wav")
    }

    private fun loadAsset(context: Context, assetPath: String): Int {
        return runCatching {
            context.assets.openFd(assetPath).use { afd ->
                soundPool.load(afd, 1)
            }
        }.getOrDefault(0)
    }

    fun dispatch(event: SoundEvent) {
        if (released) return
        when (event) {
            SoundEvent.CountdownBeep -> playOneShot(countdownBeepId, volume = 0.75f)
            SoundEvent.CountdownGo -> playOneShot(countdownGoId, volume = 0.85f)
            SoundEvent.Jump -> playOneShot(jumpId, volume = 0.85f)
            is SoundEvent.EngineUpdate -> updateEngine(event)
        }
    }

    private fun playOneShot(soundId: Int, volume: Float, rate: Float = 1f) {
        if (soundId != 0 && soundId in loadedIds) {
            soundPool.play(soundId, volume, volume, 2, 0, rate)
        }
    }

    private fun updateEngine(event: SoundEvent.EngineUpdate) {
        if (engineStreamId == 0) return
        if (event.isMuted) {
            soundPool.setVolume(engineStreamId, 0f, 0f)
            return
        }
        val spd = event.speedRatio.coerceIn(0f, 1.5f)
        val airBoost = if (event.isAirborne) 0.18f else 0f
        val turboBoost = if (event.isBoosting) 0.24f else 0f
        // SoundPool playback rate range is [0.5, 2.0]
        val rate = (0.72f + spd * 0.75f + airBoost + turboBoost).coerceIn(0.5f, 2.0f)
        val vol = ((0.22f + spd * 0.28f + (if (event.isBoosting) 0.10f else 0f)) *
            (if (event.isAirborne) 0.75f else 1f)).coerceIn(0f, 0.75f)
        soundPool.setRate(engineStreamId, rate)
        soundPool.setVolume(engineStreamId, vol, vol)
    }

    fun release() {
        released = true
        runCatching { soundPool.release() }
    }
}

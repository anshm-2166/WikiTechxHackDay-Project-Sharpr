package com.vytros.projects.sharpr.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.io.File

/**
 * One or more tracks of the same length that can be swapped mid-playback (A/B listening):
 * switching keeps the position, so you hear the same moment before and after.
 */
class ABPlayer(files: List<File>) {
    private val players: List<MediaPlayer?> = files.map { f ->
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.absolutePath)
                prepare()
            }
        }.getOrNull()
    }

    var active by mutableStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    val ready: Boolean get() = players.getOrNull(active) != null
    val durationMs: Int get() = players.getOrNull(active)?.duration ?: 0
    val positionMs: Int get() = players.getOrNull(active)?.currentPosition ?: 0

    init {
        players.forEach { p -> p?.setOnCompletionListener { playing = false; progress = 1f } }
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val p = players.getOrNull(active) ?: return
        if (progress >= 0.999f) p.seekTo(0)
        p.start()
        playing = true
    }

    fun pause() {
        players.getOrNull(active)?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun seek(fraction: Float) {
        val p = players.getOrNull(active) ?: return
        p.seekTo((fraction * p.duration).toInt())
        progress = fraction
    }

    fun select(index: Int) {
        if (index == active || players.getOrNull(index) == null) return
        val pos = positionMs
        val wasPlaying = playing
        pause()
        active = index
        // Durations can differ by a few ms after resampling; seek by time, not fraction.
        players[index]!!.seekTo(pos.coerceAtMost(players[index]!!.duration))
        if (wasPlaying) play()
    }

    fun tick() {
        val p = players.getOrNull(active) ?: return
        if (playing && p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
    }

    fun release() = players.forEach { it?.release() }
}

@Composable
fun rememberABPlayer(vararg files: File): ABPlayer {
    val key = files.joinToString { it.absolutePath }
    val player = remember(key) { ABPlayer(files.toList()) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        while (true) {
            player.tick()
            delay(50)
        }
    }
    return player
}

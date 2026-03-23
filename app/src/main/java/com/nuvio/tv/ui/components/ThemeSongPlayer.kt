package com.nuvio.tv.ui.components

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "ThemeSongPlayer"
private const val FADE_IN_MS = 2000
private const val TARGET_VOLUME = 0.3f

/**
 * Invisible composable that plays a theme song audio URL in the background.
 * Fades in volume, loops, and releases the player on disposal.
 * Pauses when [shouldPause] is true (e.g. trailer playing).
 * Keeps playing across navigation (e.g. detail → stream selection)
 * and only stops when the composable is disposed (leaving detail screen).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ThemeSongPlayer(
    audioUrl: String?,
    shouldPause: Boolean = false
) {
    if (audioUrl.isNullOrBlank()) return

    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }

    val targetVolume = when {
        shouldPause -> 0f
        isReady -> TARGET_VOLUME
        else -> 0f
    }
    val animatedVolume by animateFloatAsState(
        targetValue = targetVolume,
        animationSpec = tween(durationMillis = FADE_IN_MS),
        label = "themeSongVolume"
    )

    val player = remember(audioUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(audioUrl))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    // Track readiness
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReady = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    // Apply animated volume
    LaunchedEffect(animatedVolume) {
        player.volume = animatedVolume
    }

    // Pause/resume based on shouldPause flag
    LaunchedEffect(shouldPause, isReady) {
        if (shouldPause) {
            player.pause()
        } else if (isReady) {
            player.play()
        }
    }

    // Release player on disposal
    DisposableEffect(player) {
        onDispose {
            Log.d(TAG, "Releasing theme song player")
            player.release()
            isReady = false
        }
    }
}

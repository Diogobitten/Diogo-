@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.TrailerPlayer

@Composable
fun TrailerPlayerScreen(
    videoUrl: String,
    audioUrl: String?,
    title: String,
    onBackPress: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(true) }

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TrailerPlayer(
            trailerUrl = videoUrl,
            trailerAudioUrl = audioUrl,
            isPlaying = isPlaying,
            onEnded = { onBackPress() },
            muted = false,
            cropToFill = true,
            modifier = Modifier.fillMaxSize()
        )

        // Title overlay at top
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
        )
    }
}

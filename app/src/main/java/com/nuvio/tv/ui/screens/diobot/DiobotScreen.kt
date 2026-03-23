package com.nuvio.tv.ui.screens.diobot

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.repository.DiobotSuggestion

@Composable
fun DiobotScreen(
    onNavigateToDetail: (itemId: String, itemType: String) -> Unit,
    onNavigateToStream: (videoId: String, contentType: String, title: String) -> Unit,
    viewModel: DiobotViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val navEvent by viewModel.navEvents.collectAsState()
    val listState = rememberLazyListState()

    // Handle navigation events from phone commands and AI actions
    LaunchedEffect(navEvent) {
        navEvent?.let { event ->
            when (event) {
                is DiobotNavEvent.NavigateToDetail -> {
                    onNavigateToDetail(event.itemId, event.itemType)
                }
                is DiobotNavEvent.NavigateToStream -> {
                    onNavigateToStream(event.videoId, event.contentType, event.title)
                }
            }
            viewModel.consumeNavEvent()
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left side: QR Code + connection info
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF111111))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🤖",
                    fontSize = 40.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Diobot",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF4FC3F7)
                )
                Text(
                    text = "IA Concierge",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(24.dp))

                // QR Code
                uiState.qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code para Diobot",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Escaneie com o celular",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))

                uiState.serverUrl?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4FC3F7).copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }

                if (!uiState.serverRunning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.errorMessage ?: "Iniciando servidor...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFA726),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Fale ou digite no celular\ne o Diobot encontra pra você",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }

            // Right side: Chat messages mirror
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 24.dp)
            ) {
                // Chat header
                Text(
                    text = "Conversa",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isProcessing) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "As mensagens do celular aparecerão aqui",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }

                    items(uiState.messages, key = { it.id }) { entry ->
                        ChatBubble(entry = entry)
                    }

                    if (uiState.isProcessing) {
                        item { TypingIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (entry.isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (entry.isUser) 16.dp else 4.dp,
                        bottomEnd = if (entry.isUser) 4.dp else 16.dp
                    )
                )
                .background(if (entry.isUser) Color(0xFF1A5276) else Color(0xFF1E1E1E))
                .padding(12.dp)
        ) {
            Text(
                text = entry.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        if (entry.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 24.dp)
            ) {
                items(entry.suggestions) { suggestion ->
                    SuggestionChip(suggestion)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(suggestion: DiobotSuggestion) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2E))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = suggestion.title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (suggestion.type == "movie") "Filme" else "Série",
                color = Color(0xFF4FC3F7),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "typingAlpha"
    )
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4FC3F7).copy(alpha = alpha))
            )
        }
    }
}

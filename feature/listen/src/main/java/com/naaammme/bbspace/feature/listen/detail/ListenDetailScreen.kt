package com.naaammme.bbspace.feature.listen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.icon.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenDetailScreen(
    oid: Long,
    itemType: Int,
    subId: Long,
    title: String,
    author: String,
    cover: String,
    onBack: () -> Unit,
    viewModel: ListenDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(oid, itemType, subId) {
        viewModel.load(oid, itemType, subId, title, author)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听视频") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        val errorMsg = state.errorMessage
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMsg != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            val contentWidth = Modifier.widthIn(max = 320.dp).fillMaxWidth()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                CoverImage(
                    url = cover,
                    contentDescription = title,
                    modifier = contentWidth.aspectRatio(1f),
                    shape = MaterialTheme.shapes.large
                )

                Spacer(modifier = Modifier.height(24.dp))

                Column(modifier = contentWidth) {
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (author.isNotEmpty()) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                ListenProgress(
                    durationMs = state.durationMs,
                    positionMs = state.positionMs,
                    onSeek = viewModel::seekTo,
                    modifier = contentWidth
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = viewModel::togglePlayPause,
                    enabled = state.audioUrl != null && !state.isPreparing,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    if (state.isPreparing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isPlaying) "暂停" else "播放",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * 拖动进度条时的目标位置（BACKLOG ST-09）。
 *
 * `dragFraction` 是可空的拖动状态，而 `Slider` 的 `onValueChangeFinished` 在无障碍 / IME
 * 路径下可以在**没有任何 onValueChange** 的情况下到达 —— 那时它还是 null。
 * 原先两处都写 `dragFraction!!`，其中 finished 那处没有判空，点一下就是崩溃。
 * 抽成纯函数以后，null 与"时长未知"都走同一条不 seek 的路径，也才能在 JVM 上测。
 *
 * 顺带把 fraction 夹回 0f..1f：它来自 `Slider`，理论上不会越界，
 * 但越界一次就会 seek 到负数或超过总时长，代价只是这一行。
 */
internal fun listenSeekTargetMs(
    dragFraction: Float?,
    durationMs: Long
): Long? {
    if (dragFraction == null || durationMs <= 0L) return null
    return (dragFraction.coerceIn(0f, 1f) * durationMs).toLong()
}

@Composable
private fun ListenProgress(
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val activeFraction = if (durationMs > 0) {
        dragFraction ?: (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayMs = listenSeekTargetMs(dragFraction, durationMs) ?: positionMs
    val positionText = remember(displayMs) { formatTime(displayMs) }
    val durationText = remember(durationMs) { formatTime(durationMs) }

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = activeFraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                // 没有 onValueChange 就没有拖动目标（无障碍/IME 路径下 finished 会单独到达），
                // 原先这里是 dragFraction!! —— 直接 KotlinNullPointerException。
                listenSeekTargetMs(dragFraction, durationMs)?.let(onSeek)
                dragFraction = null
            },
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = positionText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

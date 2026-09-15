package com.naaammme.bbspace.feature.video.player

/**
 * 播放器画质 / 音质 / 倍速三个选择弹层。
 * 由 VideoPlayerPane.kt 拆分而来（纯搬运，未改行为）。
 * 三者都是自包含的 AlertDialog，不引用播放器浮层 token 组。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.model.PlaybackAudio
import com.naaammme.bbspace.core.model.QualityOption
import com.naaammme.bbspace.feature.video.detail.QualityOptionItem
import com.naaammme.bbspace.feature.video.formatSpeed
import com.naaammme.bbspace.feature.video.getAudioName
import com.naaammme.bbspace.feature.video.speedOps

@Composable
internal fun QualitySelectionDialog(
    options: List<QualityOption>,
    curQuality: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择画质") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options, key = { it.quality }) { option ->
                    QualityOptionItem(
                        option = option,
                        isSelected = option.quality == curQuality,
                        onClick = { onSelect(option.quality) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
@Composable
internal fun AudioSelectionDialog(
    audios: List<PlaybackAudio>,
    curAudioId: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音频") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(audios, key = { it.id }) { audio ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(audio.id) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getAudioName(audio.id),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (audio.id == curAudioId) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
@Composable
internal fun SpeedSelectionDialog(
    curSpeed: Float,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(speedOps, key = { it }) { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(speed) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (speed == curSpeed) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

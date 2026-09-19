package com.naaammme.bbspace.playback.capsule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassHazeState
import com.naaammme.bbspace.core.designsystem.glass.glassEffect
import com.naaammme.bbspace.core.designsystem.glass.isGlassActive
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.StreamPlaybackSessionState
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.designsystem.icon.AppIcons

private val CapsuleHeight = 56.dp
private val CapsuleRadius = CapsuleHeight / 2

/**
 * 悬浮播放胶囊指示器（方案 2.1 / 2.6）：不显示视频画面，仅标题 / UP 名 /
 * 播放态 / 进度。挂在主 Tab 内容层之后、底栏之前（z 序低于底栏），隐藏动画 =
 * 下沉融入底栏：单个 [Animatable] 驱动 progress，插值全部在 graphicsLayer 块内
 * 读取，动画期间调用方零重组。
 *
 * 交互：单击回详情；水平滑动超阈值 dismiss（停播）；播放/暂停按钮只切播放态；
 * 播放自然结束（PlaybackState.Ended）自动停播收起。
 */
@Composable
fun CapsuleIndicator(
    capsuleState: CapsuleIndicatorState,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by capsuleState.sessionState.collectAsStateWithLifecycle()
    val progress by capsuleState.progress.collectAsStateWithLifecycle()
    val active = capsuleState.active
    val anim = LocalAnimations.current

    // 1 = 完全下沉隐藏，0 = 常态。出现（激活）：1→0 带 spring 回弹；
    // 隐藏（dismiss/结束）：0→1 先实体下沉、后 30% 才淡出。
    val sinkProgress = remember { Animatable(1f) }
    var mounted by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) {
            mounted = true
            sinkProgress.animateTo(
                0f,
                spring(dampingRatio = 0.9f, stiffness = 380f)
            )
        } else {
            if (sinkProgress.value < 1f) {
                sinkProgress.animateTo(
                    1f,
                    tween(durationMillis = anim.long, easing = anim.emphasized)
                )
            }
            mounted = false
        }
    }

    // 自然结束 = 同 × 关闭（方案 2.3 状态机）
    LaunchedEffect(sessionState.playbackState) {
        if (active && sessionState.playbackState == PlaybackState.Ended) {
            capsuleState.dismissAndStop()
        }
    }

    if (!mounted) return

    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val capsuleGlassState = LocalGlassHazeState.current
    val capsuleGlassOn = isGlassActive(capsuleGlassState)
    val capsuleShape = RoundedCornerShape(CapsuleRadius)
    val containerColor = when {
        // 玻璃生效：Haze 染色接管，容器底透明
        capsuleGlassOn -> Color.Transparent
        isIOS -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    // 底栏隐藏与否不影响胶囊位置：固定悬浮于主 Tab 底部（Dock 常驻位上方）
    val sinkDistancePx = with(LocalDensity.current) { 88.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    val dragDismissThresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 0.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = capsuleShape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (capsuleGlassOn) 0.dp else if (isIOS) 4.dp else 2.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .height(CapsuleHeight)
                .then(
                    if (capsuleGlassOn) {
                        Modifier.glassEffect(
                            state = capsuleGlassState,
                            surfaceType = GlassSurfaceType.OVERLAY,
                            shape = capsuleShape
                        )
                    } else {
                        Modifier
                    }
                )
                .graphicsLayer {
                    // 单 Animatable 驱动：translationY 全程插值，alpha 仅后 30% 衰减
                    //（先实体下沉融入底栏，后淡出）；水平位移来自滑动跟手值。
                    translationY = sinkProgress.value * sinkDistancePx
                    translationX = dragX
                    alpha = ((1f - sinkProgress.value) / 0.3f).coerceIn(0f, 1f) *
                        (1f - (kotlin.math.abs(dragX) / (dragDismissThresholdPx * 1.6f)).coerceIn(0f, 0.7f))
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX += amount
                        },
                        onDragEnd = {
                            if (kotlin.math.abs(dragX) > dragDismissThresholdPx) {
                                capsuleState.dismissAndStop()
                                dragX = 0f
                            } else {
                                dragX = 0f
                            }
                        },
                        onDragCancel = { dragX = 0f }
                    )
                }
                .clickable(onClick = onOpen)
        ) {
            val progressColor = MaterialTheme.colorScheme.primary
            val progressFraction = if (progress.durationMs > 0) {
                (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, end = 4.dp)
                    .drawBehind {
                        // BiliPai 式细进度线：画在胶囊底边，2dp、tint 色。
                        // draw 阶段读 State，进度刷新只重绘不重组。
                        if (progressFraction > 0f) {
                            val lineH = 2.dp.toPx()
                            drawRect(
                                color = progressColor,
                                topLeft = Offset(0f, size.height - lineH),
                                size = androidx.compose.ui.geometry.Size(size.width * progressFraction, lineH)
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                CapsuleCover(sessionState, modifier = Modifier.padding(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = sessionState.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!sessionState.subtitle.isNullOrBlank()) {
                        Text(
                            text = sessionState.subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (sessionState.isPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (sessionState.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { capsuleState.dismissAndStop() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsuleCover(
    sessionState: StreamPlaybackSessionState,
    modifier: Modifier = Modifier
) {
    val cover = sessionState.cover
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
    ) {
        if (cover != null) {
            BiliAsyncImage(
                url = cover,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}

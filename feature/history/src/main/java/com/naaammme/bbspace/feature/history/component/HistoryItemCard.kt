package com.naaammme.bbspace.feature.history.component

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardCoverAnchor
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessVideoCardState
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.rememberHighlightedTitle
import com.naaammme.bbspace.core.model.HistoryItem
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryItemCard(
    item: HistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 无缝打开（仅视频类历史项）：注册成功由系统接管过渡，失败走 onClick 普通打开
    val seamless = rememberSeamlessVideoCardState()
    val seamlessRadiusPx = rememberSeamlessCardSpec(shape = MaterialTheme.shapes.medium).radiusPx
    val videoTarget = (item.target as? com.naaammme.bbspace.core.model.HistoryTarget.Video)?.target
    val titleText = rememberHighlightedTitle(
        text = item.title,
        highlightColor = MaterialTheme.colorScheme.primary
    )
    val infoLine = remember(item) { buildInfoLine(item) }
    val metaLine = remember(item) { buildMetaLine(item) }
    val progress = remember(item) { progressText(item) }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.smPlus),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
        ) {
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                modifier = Modifier
                    .weight(0.38f)
                    .aspectRatio(16f / 10f),
                fallbackContent = {
                    Text(
                        text = item.typeLabel,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            ) {
                SeamlessCardCoverAnchor(seamless, videoTarget, Modifier.matchParentSize())
                Text(
                    text = item.typeLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(BiliSpacing.xsPlus)
                        .background(Color.Black.copy(alpha = 0.56f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = infoLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                progress?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (item.isOpenable) {
        Card(
            onClick = {
                if (!seamless.tryOpen(videoTarget, item.cover, true, seamlessRadiusPx)) {
                    onClick()
                }
            },
            // ## 为什么这里**不能**挂 appElasticPress（第 34 轮 OPPO 动画修复）
            //
            // 本卡内放了 `SeamlessCardCoverAnchor`（见上方 `content()`），而
            // `appElasticPress` 的形变是 `Modifier.graphicsLayer` —— **绘制期**变换：
            // 它改变像素落点，但不改布局。OPPO 无缝框架用 `View.getLocationInWindow`
            // 取锚点矩形（布局期坐标），于是「按住卡片（卡片正被放大/拖偏）→ 点开视频」
            // 时框架按**屏幕上已不存在的矩形**起飞，动画从错误的位置/大小开始。
            //
            // 判据是「本卡是否是系统过渡的锚点宿主」：`seamless.active` 为真时锚点
            // 真的组合了，此时**整卡形变必须让位**给系统过渡；只有不支持无缝的设备
            //（`active=false`，锚点不组合、走普通打开）才保留按压反馈 —— 那种情况下
            // 没有框架取矩形，形变不会和任何东西打架。
            modifier = modifier.fillMaxWidth()
                .appElasticPress(enabled = !seamless.active),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            content()
        }
    }
}

private fun progressText(item: HistoryItem): String? {
    val progress = item.progressSec ?: return null
    if (progress < 0L) return "已看完"
    val duration = item.durationSec
    if (duration == null || duration <= 0L) return null
    return if (progress >= duration) {
        "已看完"
    } else {
        "进度 ${formatVideoDuration(progress)} / ${formatVideoDuration(duration)}"
    }
}

private fun buildInfoLine(item: HistoryItem): String {
    return listOfNotNull(item.ownerName, item.badge, item.subtitle)
        .joinToString(" · ")
        .ifBlank { item.typeLabel }
}

private fun buildMetaLine(item: HistoryItem): String {
    return listOfNotNull(
        item.deviceLabel,
        DateFormat.format("MM-dd HH:mm", item.viewedAtSec * 1000).toString()
    ).joinToString(" · ")
}

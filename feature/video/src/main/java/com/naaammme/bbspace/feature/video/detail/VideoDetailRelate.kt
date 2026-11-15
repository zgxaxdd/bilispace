package com.naaammme.bbspace.feature.video.detail

/**
 * 关联视频卡片（相关推荐）与「当前播放」角标。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.model.VideoRelate
import com.naaammme.bbspace.core.model.VideoTarget

// 形状全部提到文件级：内联 `shape = RoundedCornerShape(...)` 会被
// ListAllocationStructureTest 的具名实参棘轮计数，且每次组合重造一份。
/** 关联卡：与全站视频卡同一圆角语言（BiliRadius.card）。 */
private val RelateCardShape = RoundedCornerShape(BiliRadius.card)

/** 卡内小封面：比整卡小一号的同族圆角。 */
private val RelateCoverShape = RoundedCornerShape(BiliRadius.groupedList)

/** 推荐理由/「当前播放」角标：8dp 徽章圆角。 */
private val RelateBadgeShape = RoundedCornerShape(BiliRadius.badge)

@Composable
internal fun RelateRow(
    relate: VideoRelate,
    onOpenVideo: (VideoTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val meta = listOfNotNull(
        relate.viewText?.let { "$it 播放" },
        relate.danmakuText?.let { "$it 弹幕" },
        relate.durationText
    ).joinToString(" · ")

    Card(
        onClick = { onOpenVideo(relate.target) },
        // 全局拖拽反馈（第 34 轮 T2）：相关推荐卡。
        modifier = modifier.fillMaxWidth().appElasticPress(),
        // 与首页/排行/详情简介卡同一圆角（此前是 shapes.medium，随 CornerStyle 漂移）
        shape = RelateCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.Top
        ) {
            BiliAsyncImage(
                url = relate.cover,
                contentDescription = relate.title,
                modifier = Modifier
                    .weight(0.38f)
                    .aspectRatio(16f / 10f)
                    .clip(RelateCoverShape),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.weight(0.62f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
            ) {
                Text(
                    text = relate.title,
                    // 标题 SemiBold（titleSmall，14/20）压过作者与 meta 两级：
                    // 此前 bodyLarge（Regular）与小一号的 author 都只有字号差、没有字重差。
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                relate.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                relate.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RelateBadgeShape
                            )
                            .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
@Composable
internal fun CurBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RelateBadgeShape
    ) {
        Text(
            text = "当前播放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp)
        )
    }
}

package com.naaammme.bbspace.feature.im.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.ImSessionItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImSessionCard(
    item: ImSessionItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        // 全局拖拽反馈（第 34 轮 T2）：会话卡可点时才挂（`enabled` 同步透传）。
        modifier = modifier.fillMaxWidth().appElasticPress(enabled = onClick != null),
        // 圆角走 token（此前是 Card 默认 shapes.medium，会随 CornerStyle 在 12~16 漂移）：
        // 与全站卡片同一套 20dp 大圆角语言。
        shape = ImSessionCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        val summaryText = remember(
            item.summary,
            item.isPinned,
            item.isMuted,
            item.sessionTypeLabel
        ) {
            buildSummary(item)
        }
        val timeText = remember(item.timeMicros) {
            formatImTime(item.timeMicros)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                url = item.avatar,
                contentDescription = item.name,
                modifier = Modifier.size(48.dp),
                fallbackText = item.name.take(1)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            item.unreadText?.let { unread ->
                Text(
                    text = unread,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = ImUnreadBadgeShape
                        )
                        .padding(horizontal = BiliSpacing.sm, vertical = BiliSpacing.xsPlus),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1
                )
            }
        }
    }
}

private fun buildSummary(item: ImSessionItem): String {
    val prefix = buildList {
        if (item.isPinned) add("置顶")
        if (item.isMuted) add("免打扰")
        item.sessionTypeLabel?.let(::add)
    }.joinToString(" · ")
    return if (prefix.isBlank()) item.summary else "$prefix · ${item.summary}"
}

private fun formatImTime(micros: Long): String {
    if (micros <= 0L) return ""
    val instant = Instant.ofEpochMilli(micros / 1000L)
    return IM_TIME_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
}

private val IM_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 会话卡圆角：全站卡片 token（20dp），不再随 CornerStyle 漂移。 */
private val ImSessionCardShape = RoundedCornerShape(BiliRadius.card)

/** 未读角标圆角：badge token（8dp）。 */
private val ImUnreadBadgeShape = RoundedCornerShape(BiliRadius.badge)

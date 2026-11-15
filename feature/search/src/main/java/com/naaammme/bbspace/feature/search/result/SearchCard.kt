package com.naaammme.bbspace.feature.search.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardCoverAnchor
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessVideoCardState
import com.naaammme.bbspace.core.designsystem.component.rememberHighlightedTitle
import com.naaammme.bbspace.core.model.SearchAuthor
import com.naaammme.bbspace.core.model.SearchFeedbackSec
import com.naaammme.bbspace.core.model.SearchVideo
import androidx.compose.foundation.shape.RoundedCornerShape
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

/**
 * 搜索结果卡与其中的封面圆角（**文件级常量**）。
 *
 * ## 为什么从 `MaterialTheme.shapes.large` 换成 `BiliRadius.card`
 * 1. `BiliRadius.card` 的 KDoc 写着"一个角色只有一个来源：卡片容器、卡片封面、无缝打开的
 *    锚点与动画半径，全部取这个值" —— 搜索卡此前用 `shapes.large`（M3 = 16dp）是这条纪律的例外；
 * 2. 更实际的问题：无缝展开用的 `rememberSeamlessCardSpec(...)` 此前传的是 `shapes.medium`（M3 = 12dp），
 *    而卡片实际半径是 16dp —— **本来就差 4dp**，展开动画第一帧会"圆角跳一下"。
 *    统一到 `BiliRadius.card`(20dp) 后两者同源，这个隐患随之消失。
 * 3. 写成**文件级常量**而不是内联 `RoundedCornerShape(`：`ListAllocationStructureTest` 把
 *    `shape = RoundedCornerShape(` 这种具名实参形态钉在上限里（每次重组都会分配一份）。
 */
private val SearchResultCardShape = RoundedCornerShape(BiliRadius.card)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchAuthorCard(
    author: SearchAuthor,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().appElasticPress(),
        shape = SearchResultCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = author.avatar,
                contentDescription = author.name,
                modifier = Modifier.size(64.dp),
                shape = SearchResultCardShape
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = author.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                author.sign?.let { sign ->
                    Text(
                        text = sign,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "粉丝 ${author.fansText} · 稿件 ${author.archivesText} · Lv${author.level}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchCard(
    video: SearchVideo,
    onClick: () -> Unit
) {
    // 无缝打开：注册成功由系统接管过渡，失败走 onClick 普通打开
    val seamless = rememberSeamlessVideoCardState()
    val seamlessRadiusPx = rememberSeamlessCardSpec(shape = SearchResultCardShape).radiusPx
    val titleText = rememberHighlightedTitle(
        text = video.title,
        highlightColor = MaterialTheme.colorScheme.primary
    )
    val metaText = remember(video.danmakuText, video.publishTimeText) {
        listOfNotNull("${video.danmakuText} 弹幕", video.publishTimeText).joinToString(" · ")
    }
    Card(
        onClick = {
            if (!seamless.tryOpen(video.target, video.cover, true, seamlessRadiusPx)) {
                onClick()
            }
        },
        // ## 为什么这里**不能无条件**挂 appElasticPress（第 34 轮 OPPO 动画修复）
        //
        // 本卡内放了 `SeamlessCardCoverAnchor`（见下方 `CoverImage` 内），而
        // `appElasticPress` 的形变是 `Modifier.graphicsLayer` —— **绘制期**变换：
        // 它改变像素落点，但不改布局。OPPO 无缝框架用 `View.getLocationInWindow`
        // 取锚点矩形（布局期坐标），于是「按住卡片（卡片正被放大/拖偏）→ 点开视频」
        // 时框架按**屏幕上已不存在的矩形**起飞，动画从错误的位置/大小开始。
        //
        // 判据是「本卡是否是系统过渡的锚点宿主」：`seamless.active` 为真时锚点
        // 真的组合了，此时**整卡形变必须让位**给系统过渡；只有不支持无缝的设备
        //（`active=false`，锚点不组合、走普通打开）才保留按压反馈。
        // 与 `HistoryItemCard.kt` 同一口径（那里有完整推导）。
        modifier = Modifier.fillMaxWidth().appElasticPress(enabled = !seamless.active),
        shape = SearchResultCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CoverImage(
                url = video.cover,
                contentDescription = video.title,
                modifier = Modifier
                    .weight(0.38f)
                    .aspectRatio(16f / 10f)
            ) {
                SeamlessCardCoverAnchor(seamless, video.target, Modifier.matchParentSize())
                Text(
                    text = video.viewText,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
                Text(
                    text = video.duration,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            Column(
                modifier = Modifier.weight(0.62f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = titleText, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Text(
                    text = video.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (video.feedbacks.isNotEmpty()) {
                        SearchFeedbackMenu(video.feedbacks)
                    }
                }

                video.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchFeedbackMenu(feedbacks: List<SearchFeedbackSec>) {
    var show by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable { show = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "反馈"
        )
    }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(modifier = Modifier.appElasticPress(), onClick = { show = false }) {
                    Text("关闭")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    feedbacks.forEachIndexed { secIndex, sec ->
                        Text(
                            text = sec.title.ifBlank { sec.type.ifBlank { "反馈" } },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        sec.items.forEachIndexed { itemIndex, item ->
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            if (itemIndex != sec.items.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                        if (secIndex != feedbacks.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        )
    }
}

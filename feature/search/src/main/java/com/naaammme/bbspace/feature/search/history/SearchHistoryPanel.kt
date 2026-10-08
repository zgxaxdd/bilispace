package com.naaammme.bbspace.feature.search.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.MaybeDissolvableVideoCard
import com.naaammme.bbspace.core.model.SearchHistoryOrder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchHistoryPanel(
    histories: List<String>,
    order: SearchHistoryOrder,
    onToggleOrder: () -> Unit,
    onSearch: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var displayCap by remember { mutableStateOf(DISPLAY_STEP) }
    val visible = remember(histories, displayCap) {
        histories.take(displayCap)
    }
    val hasMore = histories.size > displayCap

    // 已经碎完、等数据刷新摘掉的词（见下方长按删除处的注释）。
    val dissolved = remember { mutableStateListOf<String>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(
            key = "header",
            contentType = "header"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onToggleOrder) {
                    Text(
                        text = when (order) {
                            SearchHistoryOrder.TIME -> "最热"
                            SearchHistoryOrder.HOT -> "最新"
                        }
                    )
                }
            }
        }

        item(
            key = "chips",
            contentType = "chips"
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visible.forEachIndexed { index, item ->
                    // 每个词一个独立的消散状态：`key(item)` 给它稳定身份 ——
                    // 否则删掉中间一个词后，后面的 chip 会按位置继承前一个的状态
                    //（Compose 的 remember 是位置槽），表现为"删了 A，B 突然自己碎掉"。
                    //
                    // 注意这里用 `if (!in)` 包裹而不是 `return@key`：`key` 是 inline 函数，
                    // 非局部返回会让 Kotlin 生成 `$$$$$NON_LOCAL_RETURN$$$$$` 合成类，
                    // 而 R8 9.4 **无法把它写成 dex**（实测报
                    // `Method name '<anonymous>' in class '$$$$$NON_LOCAL_RETURN$$$$$'
                    //  cannot be represented in dex format`，release 直接构建失败）。
                    key(item) {
                        if (item !in dissolved) {
                            var chipDissolving by remember { mutableStateOf(false) }
                            // 粒子消散（移植 BiliPai 的 GL Thanos 实现）：长按删除时
                            // **先碎成粒子再真删**。`dissolved` 记"已经碎完但数据还没刷新掉"的词
                            // —— 不记的话，回调到列表刷新之间那一帧会把 chip 重新画回来（闪一下）。
                            MaybeDissolvableVideoCard(
                                isDissolving = chipDissolving,
                                onDissolveComplete = {
                                    dissolved += item
                                    onDelete(item)
                                },
                                cardId = "search-history-$item"
                            ) {
                                SearchHistoryChip(
                                    text = item,
                                    featured = index == 0,
                                    onClick = { onSearch(item) },
                                    onLongClick = { chipDissolving = true }
                                )
                            }
                        }
                    }
                }

                if (hasMore) {
                    val remaining = histories.size - displayCap
                    Surface(
                        onClick = { displayCap += DISPLAY_STEP },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(
                            text = "展开更多 ($remaining)",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryChip(
    text: String,
    featured: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .widthIn(max = 160.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.large,
        color = if (featured) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (featured) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val DISPLAY_STEP = 100

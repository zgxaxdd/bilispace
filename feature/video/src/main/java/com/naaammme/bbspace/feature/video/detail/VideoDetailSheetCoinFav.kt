package com.naaammme.bbspace.feature.video.detail

/**
 * 投币与收藏夹两个底部弹层内容。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.feature.video.FavFolderSheetState
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@Composable
internal fun CoinSheetContent(
    coinBalance: Double?,
    copyright: Int?,
    userCoinCount: Int,
    busy: Boolean,
    onSubmit: (Int, Boolean) -> Unit
) {
    var multiply by remember { mutableStateOf(1) }
    var withLike by remember { mutableStateOf(true) }
    // 转载视频与已投过币的视频最多再投 1 枚；投满 2 枚后不能再投
    val reachedLimit = userCoinCount >= 2 || (copyright == 2 && userCoinCount >= 1)
    val maxCoin = if (copyright == 2 || userCoinCount >= 1) 1 else 2

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("投币", style = MaterialTheme.typography.titleLarge)
        if (reachedLimit) {
            Text(
                text = "达到投币上限啦~",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                // 全局拖拽反馈（第 34 轮 T2）：投币枚数档位（弹层内互斥小芯片）。
                // `FilterChip` 自身没有形变，挂外层不会双抖。
                FilterChip(
                    selected = multiply == 1,
                    onClick = { multiply = 1 },
                    modifier = Modifier.appElasticPress(),
                    label = { Text("1 枚") }
                )
                FilterChip(
                    selected = multiply == 2,
                    onClick = { multiply = 2 },
                    enabled = maxCoin == 2,
                    // `enabled` 透传：2 枚不可选时连形变一起关掉。
                    modifier = Modifier.appElasticPress(enabled = maxCoin == 2),
                    label = { Text("2 枚") }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                // Checkbox 自身有形变（勾选动画），按铁律**外层不挂**。
                Checkbox(checked = withLike, onCheckedChange = { withLike = it })
                Text("同时点赞", style = MaterialTheme.typography.bodyMedium)
            }
        }
        coinBalance?.let { balance ->
            Text(
                text = "硬币余额：$balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!reachedLimit) {
            Button(
                onClick = { onSubmit(multiply, withLike) },
                enabled = !busy,
                // 全局拖拽反馈（第 34 轮 T2）：投币确认主按钮（弹层内）。
                // `enabled` 透传 —— 投币中按钮禁用，形变同步关掉。
                modifier = Modifier
                    .fillMaxWidth()
                    .appElasticPress(enabled = !busy)
            ) {
                Text(if (busy) "投币中…" else "确认投币")
            }
        }
    }
}
@Composable
internal fun FavFolderSheetContent(
    state: FavFolderSheetState,
    onToggle: (Long) -> Unit,
    onCreateFolder: (String, Int) -> Unit,
    onSubmit: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var privateFolder by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        Text("收藏", style = MaterialTheme.typography.titleLarge)
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.folders.isEmpty() -> {
                StateMessageCard(text = "暂无收藏夹，新建一个吧")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(
                        items = state.folders,
                        key = { it.fid }
                    ) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(folder.fid) }
                                .padding(vertical = BiliSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = folder.fid in state.selectedIds,
                                onCheckedChange = { onToggle(folder.fid) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "${folder.mediaCount} 个内容",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = { showCreate = true },
            // 全局拖拽反馈（第 34 轮 T2）：收藏弹层里"新建收藏夹"（浮层小件）。
            modifier = Modifier.appElasticPress()
        ) {
            Text("＋ 新建收藏夹")
        }
        Button(
            onClick = onSubmit,
            enabled = !state.submitting && !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .appElasticPress(enabled = !state.submitting && !state.loading)
        ) {
            Text(if (state.submitting) "保存中…" else "完成")
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建收藏夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text("收藏夹标题") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                        FilterChip(
                            selected = !privateFolder,
                            onClick = { privateFolder = false },
                            modifier = Modifier.appElasticPress(),
                            label = { Text("公开") }
                        )
                        FilterChip(
                            selected = privateFolder,
                            onClick = { privateFolder = true },
                            modifier = Modifier.appElasticPress(),
                            label = { Text("私有") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = newTitle.trim()
                        if (title.isNotEmpty()) {
                            onCreateFolder(title, if (privateFolder) 1 else 0)
                            newTitle = ""
                            privateFolder = false
                            showCreate = false
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    // 全局拖拽反馈 + `enabled` 透传（标题为空时按钮禁用，形变同步关掉）。
                    modifier = Modifier.appElasticPress(enabled = newTitle.isNotBlank())
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreate = false },
                    modifier = Modifier.appElasticPress()
                ) {
                    Text("取消")
                }
            }
        )
    }
}

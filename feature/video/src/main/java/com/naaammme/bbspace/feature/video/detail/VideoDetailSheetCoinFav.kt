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
                FilterChip(
                    selected = multiply == 1,
                    onClick = { multiply = 1 },
                    label = { Text("1 枚") }
                )
                FilterChip(
                    selected = multiply == 2,
                    onClick = { multiply = 2 },
                    enabled = maxCoin == 2,
                    label = { Text("2 枚") }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
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
                modifier = Modifier.fillMaxWidth()
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
        TextButton(onClick = { showCreate = true }) {
            Text("＋ 新建收藏夹")
        }
        Button(
            onClick = onSubmit,
            enabled = !state.submitting && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.submitting) "保存中…" else "完成")
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建收藏夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            label = { Text("公开") }
                        )
                        FilterChip(
                            selected = privateFolder,
                            onClick = { privateFolder = true },
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
                    enabled = newTitle.isNotBlank()
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text("取消")
                }
            }
        )
    }
}

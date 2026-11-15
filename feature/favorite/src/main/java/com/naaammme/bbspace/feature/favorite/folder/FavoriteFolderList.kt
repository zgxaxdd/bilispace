package com.naaammme.bbspace.feature.favorite.folder

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.FavoriteFolder
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@Composable
fun FavoriteFolderList(
    folders: List<FavoriteFolder>,
    listState: LazyListState,
    onOpenFolder: (Long) -> Unit,
    onRenameFolder: (FavoriteFolder) -> Unit,
    onDeleteFolder: (FavoriteFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = BiliSpacing.md,
            vertical = BiliSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        items(
            items = folders,
            key = { it.fid },
            contentType = { "folder" }
        ) { folder ->
            FavoriteFolderCard(
                folder = folder,
                onClick = { onOpenFolder(folder.fid) },
                onRename = { onRenameFolder(folder) },
                onDelete = { onDeleteFolder(folder) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FavoriteFolderCard(
    folder: FavoriteFolder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true }
            )
            // 全局拖拽反馈：本行**有长按手势**（长按开菜单）。
            // `dragEnabled = false` ⇒ 只保留按压缩放/光斑，不做跟手位移 ——
            // 否则"按住不动等菜单"的过程中整卡会跟着手指走，长按意图读起来是拖拽。
            // 与 `BiliCardSurface` 对 onLongClick 的判据同一条（task-9 A 的
            // "长按/滑动的行不要挂跟手" 在这里落成"挂按压、不挂跟手"）。
            .appElasticPress(dragEnabled = false),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.smPlus),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
        ) {
            CoverImage(
                url = folder.cover,
                contentDescription = null,
                modifier = Modifier
                    .weight(0.28f)
                    .aspectRatio(16f / 10f),
                fallbackContent = {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = remember(folder.attrDesc, folder.mediaCount, folder.isTop, folder.createdAtSec) {
                        buildFolderSubtitle(folder)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(modifier = Modifier.appElasticPress(), onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "收藏夹管理"
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除收藏夹") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun buildFolderSubtitle(folder: FavoriteFolder): String {
    return buildList {
        if (folder.isTop) add("置顶")
        val desc = folder.attrDesc?.trim()
        if (!desc.isNullOrBlank()) {
            add(desc)
        } else {
            add("${folder.mediaCount}个内容")
        }
        folder.createdAtSec.takeIf { it > 0L }?.let {
            add("创建于 ${DateFormat.format("yyyy-MM-dd", it * 1000).toString()}")
        }
    }.joinToString(" · ")
}

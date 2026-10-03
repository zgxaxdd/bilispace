package com.naaammme.bbspace.feature.settings.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.settings.blocked.BlockedUpEntry

/**
 * NX-08「拉黑名单」：把 PT-06 建立的**本地名单**做成可见、可撤销。
 *
 * 为什么在设置里而不是刷视频页：名单是"账号级"状态（拉黑对 B 站账号实际生效），
 * 属于设置语义；刷视频页只保留"发起拉黑"的入口（PT-06）。
 *
 * 三态（`UI-04` 规范，**错误 > 空 > 加载**）：
 * - 错误：`StateMessageCard(isError = true)` + 重试；
 * - 空：`StateMessageCard` 说明"怎么才会有条目"（而不是一个空白页）；
 * - 加载：居中指示器。
 * 因为没有头像 URL（本地名单只存 mid/名/时间，避免逐条网络请求），条目用首字圆形底 +
 * 名字，避免"假头像位"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUpScreen(
    onBack: () -> Unit,
    viewModel: BlockedUpViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (!message.isNullOrEmpty()) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
        CollapsingTopBarScaffold(
            modifier = Modifier.padding(scaffoldPadding),
            topBar = { scrollBehavior ->
                TopAppBar(
                    title = { Text("拉黑名单") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { padding ->
            when {
                state.errorMessage != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(BiliSpacing.lg),
                    contentAlignment = Alignment.TopCenter
                ) {
                    StateMessageCard(
                        title = "读取失败",
                        text = state.errorMessage.orEmpty(),
                        isError = true,
                        actionText = "重试",
                        onAction = viewModel::load
                    )
                }

                state.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                state.entries.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(BiliSpacing.lg),
                    contentAlignment = Alignment.TopCenter
                ) {
                    StateMessageCard(
                        title = "名单为空",
                        text = "在刷视频页的「更多」里可以拉黑 UP 主；拉黑后对方会出现在这里，可随时撤销。"
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(BiliSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                ) {
                    item {
                        Text(
                            "拉黑对 B 站账号实际生效；撤销会先在本机名单移除，再同步到服务端。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(state.entries, key = { it.mid }) { entry ->
                        BlockedUpRow(
                            entry = entry,
                            onUnblock = { viewModel.unblock(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedUpRow(
    entry: BlockedUpEntry,
    onUnblock: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blockedUpDisplayName(entry),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(BiliSpacing.xs))
                Text(
                    text = "mid ${entry.mid} · ${formatBlockedAt(entry.blockedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(BiliSpacing.xs))
                Text(
                    text = blockedUpSyncLabel(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.synced) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            TextButton(onClick = onUnblock) {
                Text("撤销")
            }
        }
    }
}

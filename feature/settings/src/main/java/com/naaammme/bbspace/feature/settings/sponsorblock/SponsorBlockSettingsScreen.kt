package com.naaammme.bbspace.feature.settings.sponsorblock

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.sponsorblock.SbCategory
import com.naaammme.bbspace.core.sponsorblock.SbSkipType
import com.naaammme.bbspace.core.sponsorblock.SponsorBlockClient
import com.naaammme.bbspace.feature.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorBlockSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val enabled by viewModel.sponsorBlockEnabled.collectAsStateWithLifecycle()
    val toastEnabled by viewModel.sponsorBlockToast.collectAsStateWithLifecycle()
    val markersEnabled by viewModel.sponsorBlockMarkers.collectAsStateWithLifecycle()
    val serverUrl by viewModel.sponsorBlockServerUrl.collectAsStateWithLifecycle()
    val userId by viewModel.sponsorBlockUserId.collectAsStateWithLifecycle()
    val serverTestResult by viewModel.sponsorBlockServerTestResult.collectAsStateWithLifecycle()

    var showEnableConfirm by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<SbCategory?>(null) }
    var showServerEditor by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("空降助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用空降助手", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "播放时自动或手动跳过恰饭 片头片尾等片段",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { next ->
                                if (next && !enabled) {
                                    showEnableConfirm = true
                                } else {
                                    viewModel.updateSponsorBlockEnabled(next)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("数据说明", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "片段数据来自小电视空降助手（BilibiliSponsorBlock）社区的公开标注。" +
                                "开启后，播放 UGC 视频时会向社区服务器查询该视频的片段信息。" +
                                "当前服务器：" + serverUrl.ifBlank { SponsorBlockClient.DEFAULT_SERVER },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    "分类管理",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            items(SbCategory.entries.size) { index ->
                val category = SbCategory.entries[index]
                val current = viewModel.sponsorBlockSkipTypeFor(category)
                Card(
                    onClick = { editCategory = category },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(category.colorArgb), CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(category.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                category.shortTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            current.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingSwitchRow(
                            title = "跳过提示",
                            subtitle = "跳过后显示提示信息",
                            enabled = enabled,
                            checked = toastEnabled,
                            onCheckedChange = viewModel::updateSponsorBlockToast
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingSwitchRow(
                            title = "进度条标记",
                            subtitle = "在进度条上以颜色标注片段区间",
                            enabled = enabled,
                            checked = markersEnabled,
                            onCheckedChange = viewModel::updateSponsorBlockMarkers
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("服务器", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            serverUrl.ifBlank { SponsorBlockClient.DEFAULT_SERVER },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showServerEditor = true }, enabled = enabled) {
                                Text("修改地址")
                            }
                            TextButton(
                                onClick = viewModel::testSponsorBlockServer,
                                enabled = enabled
                            ) {
                                Text("测试连接")
                            }
                            serverTestResult?.let { result ->
                                Text(
                                    result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("我的身份", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            userId?.let { "已生成（${it.take(8)}…）" } ?: "尚未生成，开启功能后自动创建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "身份 ID 仅保存在本机，用于提交片段与投票，请勿分享。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showRegenerateConfirm = true },
                            enabled = userId != null
                        ) {
                            Text("重置身份 ID")
                        }
                    }
                }
            }
        }

        if (showEnableConfirm) {
            AlertDialog(
                onDismissRequest = { showEnableConfirm = false },
                title = { Text("启用空降助手") },
                text = {
                    Text(
                        "开启后，播放视频时会向小电视空降助手社区服务器（bsbsb.top）" +
                            "查询当前视频的社区标注片段。是否继续？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSponsorBlockEnabled(true)
                        showEnableConfirm = false
                    }) { Text("开启") }
                },
                dismissButton = {
                    TextButton(onClick = { showEnableConfirm = false }) { Text("取消") }
                }
            )
        }

        editCategory?.let { category ->
            val current = viewModel.sponsorBlockSkipTypeFor(category)
            AlertDialog(
                onDismissRequest = { editCategory = null },
                title = { Text(category.title) },
                text = {
                    Column {
                        SbSkipType.entries.forEach { type ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = current == type,
                                    onClick = {
                                        viewModel.updateSponsorBlockCategoryType(category, type)
                                        editCategory = null
                                    }
                                )
                                Text(type.label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { editCategory = null }) { Text("取消") }
                }
            )
        }

        if (showServerEditor) {
            var draft by remember(showServerEditor) {
                mutableStateOf(serverUrl.ifBlank { SponsorBlockClient.DEFAULT_SERVER })
            }
            AlertDialog(
                onDismissRequest = { showServerEditor = false },
                title = { Text("服务器地址") },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSponsorBlockServerUrl(draft)
                        showServerEditor = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showServerEditor = false }) { Text("取消") }
                }
            )
        }

        if (showRegenerateConfirm) {
            AlertDialog(
                onDismissRequest = { showRegenerateConfirm = false },
                title = { Text("重置身份 ID") },
                text = {
                    Text("重置后将生成新的身份 ID，旧的社区贡献记录将无法关联。确定继续？")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.regenerateSponsorBlockUserId()
                        showRegenerateConfirm = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showRegenerateConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked && enabled, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

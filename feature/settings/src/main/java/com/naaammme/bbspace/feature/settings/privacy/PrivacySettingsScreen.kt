package com.naaammme.bbspace.feature.settings.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixActionTone
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixButton
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixIconButton
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSwitch
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onNavigateToBlockedUp: () -> Unit = {},
    viewModel: PrivacyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val blockGaia by viewModel.blockGaia.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.importResult) {
        state.importResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearImportResult()
        }
    }

    // PB-03：播放缓存清理的结果（含"播放中被拒绝"）如实提示
    LaunchedEffect(state.playbackCacheToast) {
        state.playbackCacheToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumePlaybackCacheToast()
        }
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("隐私安全") },
                navigationIcon = {
                    AppMiuixIconButton(onClick = onBack) {
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
            contentPadding = PaddingValues(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
        ) {
            // Gaia 风控上报
            item {
                SectionTitle("风控上报")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("禁止 Gaia 上报", style = MaterialTheme.typography.bodyLarge)
                            Text("阻止应用列表风控数据上报", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppMiuixSwitch(checked = blockGaia, onCheckedChange = { viewModel.setBlockGaia(it) })
                    }
                }
            }

            // NX-08：本地拉黑名单（可见 / 可撤销）——PT-06 只建了名单与写入路径，
            // 查看与撤销入口在本项补齐。间距走 token，不给 dp 棘轮添字面量。
            item {
                SectionTitle("拉黑与屏蔽")
            }
            item {
                Card(
                    onClick = onNavigateToBlockedUp,
                    modifier = Modifier.fillMaxWidth().appElasticPress()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = BiliSpacing.lg,
                                vertical = BiliSpacing.md
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("拉黑名单", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "查看并撤销在刷视频页拉黑的 UP 主",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 设备信息
            item {
                SectionTitle("设备信息")
            }
            item {
                InfoCard(state.deviceInfo.map { (k, v) -> k to v })
            }

            // 会话信息
            item {
                SectionTitle("会话信息")
            }
            item {
                InfoCard(state.sessionInfo.map { (k, v) -> k to v })
            }

            // Ticket 缓存
            item {
                SectionTitle("Ticket 缓存")
            }
            item {
                val ticketEntries = state.ticketInfo.map { (k, v) ->
                    if (k == "expireAt" && v != "0") {
                        k to formatTimestamp(v.toLongOrNull() ?: 0)
                    } else {
                        k to v
                    }
                }
                InfoCard(ticketEntries)
            }

            // Guest 缓存
            item {
                SectionTitle("Guest 缓存")
            }
            item {
                val guestEntries = state.guestInfo.map { (k, v) ->
                    if (k == "cacheTime" && v != "0") {
                        k to formatTimestamp(v.toLongOrNull() ?: 0)
                    } else {
                        k to v
                    }
                }
                InfoCard(guestEntries)
            }

            // 地区码
            item {
                SectionTitle("地区码 ")
            }
            item {
                InfoCard(listOf("regionCode" to state.regionCode))
            }

            // 缓存管理
            item {
                SectionTitle("缓存管理")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(BiliSpacing.md)) {
                        // PB-03：播放字节缓存**必须**给出占用与清理入口 ——
                        // 128MiB 的磁盘占用不能是静默的。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("播放缓存", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatBytes(state.playbackCacheBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "回看/拖动回退时复用的媒体字节（上限 128 MB，播放中不能清理）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(BiliSpacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                        ) {
                            // 设置页批 3：缓存清理三个动作改用 Miuix 动作按钮原语。
                            // 语义/回调/Toast 文案逐字未变：OutlinedButton→Secondary、
                            // Button→Primary 仅换渲染层；Danger 档在此无自然落点（清缓存
                            // 不是用户数据破坏），故不硬套。
                            AppMiuixButton(
                                text = "清除图片缓存",
                                onClick = {
                                    viewModel.clearImageCache()
                                    Toast.makeText(context, "已清除图片缓存", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                tone = AppMiuixActionTone.Secondary
                            )
                            AppMiuixButton(
                                text = "清除所有缓存",
                                onClick = {
                                    viewModel.clearAllCache()
                                    // PB-03：措辞必须准确 —— 播放缓存有独立按钮且播放中拒绝清理，
                                    // 说"已清除所有缓存"会在播放中变成假话。
                                    Toast.makeText(context, "已清除应用缓存（播放缓存除外）", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                modifier = Modifier.weight(1f),
                                tone = AppMiuixActionTone.Primary
                            )
                        }
                        Spacer(Modifier.height(BiliSpacing.sm))
                        AppMiuixButton(
                            text = "清除播放缓存",
                            onClick = { viewModel.clearPlaybackCache() },
                            modifier = Modifier.fillMaxWidth(),
                            tone = AppMiuixActionTone.Secondary
                        )
                    }
                }
            }

            // HD 鉴权
            item {
                SectionTitle("HD 鉴权")
            }
            item {
                val hdKey = state.hdInfo["hdAccessKey"].orEmpty()
                val hdMasked = if (hdKey.isNotEmpty()) hdKey.take(20) + "..." else "--"
                InfoCard(
                    entries = listOf(
                        "currentMid" to (state.hdInfo["currentMid"] ?: "0"),
                        "hasHdAccessKey" to (state.hdInfo["hasHdAccessKey"] ?: "false"),
                        "hdAccessKey" to hdMasked
                    )
                )
            }

            // 账号凭证
            item {
                SectionTitle("账号凭证 (${state.accounts.size})")
            }
            state.accounts.forEach { account ->
                item {
                    val isCurrent = account.mid == state.currentMid
                    InfoCard(
                        entries = listOf(
                            "mid" to "${account.mid}${if (isCurrent) " (当前)" else ""}",
                            "accessToken" to account.accessToken.take(20) + "...",
                            "refreshToken" to account.refreshToken.take(20) + "...",
                            "cookies" to "${account.cookies.size} 个"
                        )
                    )
                }
            }

            // 导出/导入按钮
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                ) {
                    Button(
                        onClick = {
                            val json = viewModel.exportAllAccounts()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            // S-5：这串 JSON 含**全部已保存账号**的 access_token 与 cookies
                            // （SESSDATA / bili_jct = 登录态）。必须标 EXTRA_IS_SENSITIVE
                            // （API 33+，本项目 minSdk=33）：系统剪贴板预览浮层不再显示内容，
                            // 输入法/剪贴板历史类应用也不会把它留存在自己的历史库里。
                            // 更彻底的方案（分块 + Keystore 加密 + 扫码，不过剪贴板）见
                            // `_ref/BiliPai` 的 BiliPaiTransfer*，属后续排期。
                            val clip = ClipData.newPlainText("accounts", json).apply {
                                description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(
                                context,
                                "已复制到剪贴板（含登录凭证，请勿转发）",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.weight(1f).appElasticPress()
                    ) {
                        Text("导出到剪切板")
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            val text = clip?.getItemAt(0)?.text?.toString()
                            if (text.isNullOrBlank()) {
                                Toast.makeText(context, "剪切板为空", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.importAccounts(text)
                            }
                        },
                        modifier = Modifier.weight(1f).appElasticPress()
                    ) {
                        Text("从剪切板导入")
                    }
                }
            }

            // 底部留白
            item { Spacer(modifier = Modifier.height(BiliSpacing.lg)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun InfoCard(entries: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BiliSpacing.md)) {
            entries.forEach { (key, value) ->
                InfoRow(key, value)
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 微间距 2dp：BiliSpacing 最小是 xs=4dp，硬套 token 会改变布局，故保留字面量。
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value.ifEmpty { "--" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    if (ms == 0L) return "--"
    return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

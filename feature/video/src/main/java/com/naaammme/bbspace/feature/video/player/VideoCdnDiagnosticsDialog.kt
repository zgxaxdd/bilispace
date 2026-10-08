package com.naaammme.bbspace.feature.video.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.infra.player.cdn.CdnNodeTransfer
import com.naaammme.bbspace.infra.player.cdn.CdnTransferRuntime

/**
 * CDN 线路诊断（移植自 BiliPai `CdnTransferPanel` + `PlaybackCdnDiagnostics` 的**信息与操作核心**）。
 *
 * 为什么需要它：`CdnTransferRuntime` 把节点速率/首字节/冷却/失败计数都算出来了，但移植时
 * **没有搬面板** —— `preferHost` / `restoreRoutes` / `recentMediaUrls` 三个 API 零调用方，
 * 用户看不到"现在用的是哪条线、哪条线在掉分"，也没法像源仓库那样手动钉住一条线。
 *
 * 与源面板的差异（有意）：源面板还带"实验性 URL 改写"与"区域/ISP"开关，那是默认关闭的
 * 实验功能，需要 IP 定位与 URL 改写，本仓不移植（见 08 报告 G5）。这里只保留**可观测 +
 * 钉线路/恢复自动**这两件对排障真正有用的事。
 */
@Composable
internal fun VideoCdnDiagnosticsDialog(onDismiss: () -> Unit) {
    val snapshot by CdnTransferRuntime.state.collectAsStateWithLifecycle()
    // 冷却时间是 `System.nanoTime()` 基准（不是墙上时钟），所以"现在"必须用同一基准的
    // `updatedAtMs` 近似比较 —— 用 `System.currentTimeMillis()` 会得到无意义的差。
    val now = snapshot.updatedAtMs
    // 小窗/音频播放器用的媒体 URL 是**诊断输入**（源仓库 `PlaybackCdnDiagnostics` 也拿它做参考）。
    // 这里只显示 host：URL 带签名，不进 UI、更不进日志。
    val otherTrackHosts = remember(snapshot.updatedAtMs) {
        CdnTransferRuntime.recentMediaUrls()
            .mapNotNull { url -> runCatching { java.net.URI(url).host }.getOrNull() }
            .distinct()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CDN 线路诊断") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = BiliSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
                    ) {
                        Text(
                            text = "并行分片：" + if (snapshot.parallelEnabled) "开" else "关" +
                                "　并发预算：" + snapshot.connections + " 连接",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "剩余缓冲：" + (snapshot.bufferMs / 1000) + "s" +
                                "　已交付：" + cdnBytes(snapshot.deliveredBytes),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "换线 " + snapshot.fallbackCount +
                                " · 续传 " + snapshot.resumedCount +
                                " · 救援 " + snapshot.rescueCount,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (otherTrackHosts.isNotEmpty()) {
                            Text(
                                text = "其它媒体线路：" + otherTrackHosts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 同样不能写成 `Row(SpaceBetween){ Text(长 host); TextButton }`：
                        // 主机名一长就把「恢复自动」挤出可视区（真机 instrumented 测试抓到）。
                        Text(
                            text = "手动选定：" + (snapshot.preferredHost ?: "自动选线"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (snapshot.preferredHost != null) {
                            TextButton(
                                onClick = { CdnTransferRuntime.restoreRoutes() },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("恢复自动")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (snapshot.nodes.isEmpty()) {
                    item {
                        Text(
                            text = "还没有线路样本：播放几秒后再打开（样本只在真正发出请求后才有）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(
                    items = snapshot.nodes.sortedByDescending { it.bytes },
                    key = { it.host }
                ) { node ->
                    CdnNodeRow(
                        node = node,
                        now = now,
                        pinned = node.host == snapshot.preferredHost,
                        onPin = { CdnTransferRuntime.preferHost(node.host) },
                        // 源仓库里冷却只由失败自动触发（`finish()` 里 412/429 → 30s）。
                        // 这里把它显式给到用户：某条线在本机一直慢/掉分时，"先别用它"比等它自己掉分有用。
                        onCooldown = { CdnTransferRuntime.avoid(node.host) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun CdnNodeRow(
    node: CdnNodeTransfer,
    now: Long,
    pinned: Boolean,
    onPin: () -> Unit,
    onCooldown: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        // ⚠️ 一行放不下「主机名 + 两个按钮」：AlertDialog 的正文区很窄，行内 SpaceBetween
        // 会把最右边的按钮**挤出可视区**（真机 instrumented 测试实测：`钉住` 不可见、
        // 点了也不生效）。所以主机名单独一行（超长省略），按钮另起一行右对齐。
        Text(
            text = node.host + if (pinned) "（已钉住）" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (pinned) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCooldown, enabled = node.cooldownUntilMs <= now) {
                Text(if (node.cooldownUntilMs > now) "冷却中" else "冷却 60s")
            }
            TextButton(onClick = onPin, enabled = !pinned) {
                Text(if (pinned) "使用中" else "钉住")
            }
        }
        Text(
            text = buildString {
                append("速率 ").append(cdnRate(node.speedBps))
                append("　首字节 ").append(node.firstByteMs?.let { it.toString() + "ms" } ?: "—")
                append("　进行中 ").append(node.active)
                append("　成功 ").append(node.completed)
                append("　失败 ").append(node.failures)
                if (node.cooldownUntilMs > now) append("　冷却中")
                node.lastStatus?.let { append("　HTTP ").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun cdnBytes(value: Long): String {
    val mb = 1024L * 1024L
    return if (value >= mb) (value / mb).toString() + "MB" else (value / 1024L).toString() + "KB"
}

private fun cdnRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "—" else (bytesPerSecond / 1024L).toString() + "KB/s"

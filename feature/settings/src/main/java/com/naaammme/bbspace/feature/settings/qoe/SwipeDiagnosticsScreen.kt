package com.naaammme.bbspace.feature.settings.qoe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeEventType
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeHub
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeMath
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeSnapshot

/**
 * 「刷视频诊断」页（任务书 §4.8 / SW-06）：本机 QoE 指标的只读出口。
 *
 * - 数据来自 [SwipeQoeHub]（内存环形缓冲，进程级）：**不联网、无第三方 SDK、
 *   不含标题/UP/账号/设备标识**——只有事件类型、计数、毫秒与来源标签；
 * - "导出文本" = 指标汇总复制到剪贴板（与错误日志页同一交互），供真机排查时
 *   贴到 issue 里，字段顺序固定可脚本解析。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDiagnosticsScreen(
    onBack: () -> Unit,
) {
    // 订阅版本号：每条事件 +1，页面轻量重算快照（不订阅大对象）
    val version by SwipeQoeHub.version.collectAsStateWithLifecycle()
    val snapshot = remember(version) { SwipeQoeHub.snapshot() }
    val context = LocalContext.current

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("刷视频诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("swipe_qoe", SwipeQoeMath.exportText(snapshot))
                        )
                        Toast.makeText(context, "指标已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "导出文本")
                    }
                    IconButton(onClick = { SwipeQoeHub.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空诊断数据")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        if (snapshot.total == 0) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "还没有刷视频会话的数据\n进入一次刷视频模式后回来看这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { MetricCard("概览", overviewLines(snapshot)) }
                item { MetricCard("事件计数", counterLines(snapshot)) }
            }
        }
    }
}

private fun overviewLines(snapshot: SwipeQoeSnapshot): List<Pair<String, String>> {
    val deltas = SwipeQoeMath.firstFrameDeltas(snapshot.events)
    val pages = snapshot.counters[SwipeQoeEventType.Page] ?: 0
    val frames = snapshot.counters[SwipeQoeEventType.FirstFrame] ?: 0
    return listOf(
        "事件总数" to "${snapshot.total}",
        "首帧样本" to "${deltas.size}",
        "首帧 P50" to "${SwipeQoeMath.percentile(deltas, 50.0) ?: "样本不足"}",
        "首帧 P90" to "${SwipeQoeMath.percentile(deltas, 90.0) ?: "样本不足"}",
        "首帧 P99" to "${SwipeQoeMath.percentile(deltas, 99.0) ?: "样本不足"}",
        "翻页成功率" to (SwipeQoeMath.pageSuccessRate(pages, frames)
            ?.let { "%.1f%%".format(it * 100) } ?: "样本不足")
    )
}

private fun counterLines(snapshot: SwipeQoeSnapshot): List<Pair<String, String>> =
    SwipeQoeEventType.entries.map { type ->
        type.name.lowercase() to "${snapshot.counters[type] ?: 0}"
    }

@Composable
private fun MetricCard(title: String, rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            rows.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 8.dp else 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

package com.naaammme.bbspace.feature.settings.audioVideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.PlayerPlaybackPrefs
import com.naaammme.bbspace.core.model.player.CdnRegionPolicy
import com.naaammme.bbspace.feature.settings.components.SettingSwitch
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

/**
 * 「CDN 智能选线（实验）」设置分区（W4 数据链路的 **UI 入口**）。
 *
 * ## 这一区改的是什么、不改什么
 * 数据侧（`CdnRegionPolicy` + `CdnRegionPlugin`）只把服务端候选列表**重排/改写 host**：
 * 自定义规则 → 属地优选 → 原始，其中严格模式在"确有自定义候选"时只用自定义。
 * **传输层预算是另一层**（并行分片槽位、300ms 有界等待、降级/重升、卡顿阶梯恢复都在
 * `CdnPlaybackDataSource` / `Media3PlayerEngine`），本区任何开关都碰不到它 ——
 * 文案里必须写清楚，否则用户会以为"开了智能选线就能提速"。
 *
 * ## 默认关 = 行为不变
 * 四项默认值全部来自 `PlayerPlaybackPrefs` 出厂默认（关/空），因此不配置时与既有
 * 播放行为逐字节一致；设置页只负责把这四个值写回 `AppSettings`（单一真值源）。
 *
 * ## 控件层级（沿用本页既有语言）
 * 开关走 [SettingSwitch]（iOS/Material 双方言），文本编辑走"卡片行 → 弹窗多行输入"：
 * 与 SponsorBlock 的服务器地址编辑同款，避免在设置页里放两个常驻多行输入框（会把页面
 * 撑得很长，且 DataStore 每敲一个字都会写一次盘）。
 */
@Composable
internal fun CdnRegionSettingsSection(
    playback: PlayerPlaybackPrefs,
    onRegionEnabledChange: (Boolean) -> Unit,
    onStrictChange: (Boolean) -> Unit,
    onHostsChange: (List<String>) -> Unit,
    onRulesChange: (List<CdnRegionPolicy.CustomRule>) -> Unit
) {
    var showHostsEditor by remember { mutableStateOf(false) }
    var showRulesEditor by remember { mutableStateOf(false) }

    SettingGroupTitle(title = "CDN 智能选线（实验）")

    SettingSwitch(
        title = "属地优选",
        subtitle = "按归属地把候选线路改写为下面配置的 host；默认关。" +
            "只改变候选线路的顺序与来源，不改变传输层预算（并行槽位/降级仍按原策略）。",
        checked = playback.cdnRegionEnabled,
        onCheckedChange = onRegionEnabledChange
    )

    SettingSwitch(
        title = "严格只用自定义线路",
        subtitle = if (playback.cdnStrictCustomCdn) {
            "⚠️ 可能播不了：有自定义候选时只走自定义线路；规则无效或无命中时自动回退到属地/原始线路。"
        } else {
            "有自定义候选时只走自定义线路；需先开启属地优选，默认关。"
        },
        checked = playback.cdnStrictCustomCdn,
        onCheckedChange = onStrictChange,
        enabled = playback.cdnRegionEnabled
    )

    CdnEditorCard(
        title = "目标 host",
        summary = when {
            playback.cdnRegionHosts.isEmpty() -> "未配置（候选保持服务端原序）"
            else -> playback.cdnRegionHosts.size.let { count ->
                "$count 个：" + playback.cdnRegionHosts.take(2).joinToString(" · ") +
                    if (count > 2) " …" else ""
            }
        },
        enabled = playback.cdnRegionEnabled,
        onClick = { showHostsEditor = true }
    )

    CdnEditorCard(
        title = "自定义改写规则",
        summary = if (playback.cdnCustomRules.isEmpty()) {
            "未配置（每行 pattern => replacement）"
        } else {
            "${playback.cdnCustomRules.size} 条规则（首条命中即停）"
        },
        enabled = playback.cdnRegionEnabled,
        onClick = { showRulesEditor = true }
    )

    if (showHostsEditor) {
        CdnHostsEditorDialog(
            initialHosts = playback.cdnRegionHosts,
            onDismiss = { showHostsEditor = false },
            onConfirm = { hosts ->
                showHostsEditor = false
                onHostsChange(hosts)
            }
        )
    }

    if (showRulesEditor) {
        CdnRulesEditorDialog(
            initialRules = playback.cdnCustomRules,
            onDismiss = { showRulesEditor = false },
            onConfirm = { rules ->
                showRulesEditor = false
                onRulesChange(rules)
            }
        )
    }
}

/** 设置行样式与 [SettingSwitch] 一致（标题 + 摘要 + 可点卡片），只是点击打开编辑器。 */
@Composable
private fun CdnEditorCard(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().appElasticPress()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CdnHostsEditorDialog(
    initialHosts: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var draft by remember(initialHosts) {
        mutableStateOf(CdnRuleTextFormat.formatHosts(initialHosts))
    }
    val parsed = remember(draft) { CdnRuleTextFormat.parseHosts(draft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目标 host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    label = { Text("一行一个；也接受逗号分隔") },
                    supportingText = {
                        Text(
                            text = "解析后 ${parsed.size} 个 host（空行/重复自动忽略）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = { onConfirm(parsed) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CdnRulesEditorDialog(
    initialRules: List<CdnRegionPolicy.CustomRule>,
    onDismiss: () -> Unit,
    onConfirm: (List<CdnRegionPolicy.CustomRule>) -> Unit
) {
    var draft by remember(initialRules) {
        mutableStateOf(CdnRuleTextFormat.formatRules(initialRules))
    }
    val parsed = remember(draft) { CdnRuleTextFormat.parseRules(draft) }
    val invalidHint = if (parsed.invalidLineCount > 0) {
        "已忽略 ${parsed.invalidLineCount} 行（第 " +
            parsed.invalidLineNumbers.joinToString("、") + " 行）：格式应为 pattern => replacement，" +
            "且正则必须合法"
    } else {
        "每行 pattern => replacement；首条命中即停，替换结果必须仍指向 B 站域"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义改写规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    label = { Text("pattern => replacement") },
                    supportingText = {
                        Text(
                            text = "有效 ${parsed.rules.size} 条" +
                                if (parsed.invalidLineCount > 0) {
                                    " · 忽略 ${parsed.invalidLineCount} 行"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
                Text(
                    text = invalidHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed.invalidLineCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = { onConfirm(parsed.rules) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) { Text("取消") }
        }
    )
}

package com.naaammme.bbspace.feature.settings.audioVideo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixIconButton
import com.naaammme.bbspace.feature.settings.SettingsViewModel
import com.naaammme.bbspace.feature.settings.components.SettingSwitch
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVideoSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val enableHdrAnd8k by viewModel.enableHdrAnd8k.collectAsStateWithLifecycle()
    val defaultVideoQuality by viewModel.defaultVideoQuality.collectAsStateWithLifecycle()
    val defaultAudioQuality by viewModel.defaultAudioQuality.collectAsStateWithLifecycle()
    val forceHost by viewModel.forceHost.collectAsStateWithLifecycle()
    val needTrial by viewModel.needTrial.collectAsStateWithLifecycle()
    val preferredCodec by viewModel.preferredCodec.collectAsStateWithLifecycle()
    val enableWebPlayback by viewModel.enableWebPlayback.collectAsStateWithLifecycle()
    // CDN 智能选线四项与上面的开关同屏：偏好快照一次收集，避免四个 Flow 各自晚一帧
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle()

    var showVideoQualityDialog by remember { mutableStateOf(false) }
    var showAudioQualityDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = {
                    Text("音视频设置")
                },
                navigationIcon = {
                    AppMiuixIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = BiliSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
        ) {
            SettingGroupTitle(title = "画质")

            SettingSwitch(
                title = "启用 HDR 和 8K",
                subtitle = "允许请求 HDR 和 8K 视频流（如果视频支持）",
                checked = enableHdrAnd8k,
                onCheckedChange = viewModel::updateEnableHdrAnd8k
            )

            SettingGroupTitle(title = "本地选择")

            QualityItem(
                title = "默认视频画质",
                quality = defaultVideoQuality,
                onClick = { showVideoQualityDialog = true }
            )

            QualityItem(
                title = "默认音频质量",
                quality = defaultAudioQuality,
                onClick = { showAudioQualityDialog = true }
            )

            SettingGroupTitle(title = "其他")

            QualityItem(
                title = "优先编码格式",
                quality = preferredCodec,
                label = getCodecName(preferredCodec),
                onClick = { showCodecDialog = true }
            )

            // ── 文案与真实语义对齐（第 35 轮静态复查）─────────────────────────
            // 这两条此前写的是「使用https播放」与「需要4k」，而请求里它们是
            // `VideoVod.setForceHost` / `VideoVod.setIsNeedTrial`：
            // 前者是"强制走指定线路"（与协议无关），后者是"付费内容试看标记"
            // （与 4K 无关 —— 4K 由上面的「启用 HDR 和 8K」经 fnval=4048 打开）。
            // 文案错了比没有开关更糟：用户按文案理解去关/开，得到的是另一种行为。
            SettingSwitch(
                title = "强制指定线路",
                subtitle = "请求播放地址时固定要求服务端下发指定 CDN 线路（force_host）；" +
                    "线路异常时可关闭退回自动选线",
                checked = forceHost > 0,
                onCheckedChange = { viewModel.updateForceHost(if (it) 1 else 0) }
            )

            SettingSwitch(
                title = "试看标记",
                subtitle = "请求播放地址时带上「需要试看」标记（is_need_trial）；" +
                    "仅对需要付费/大会员的视频生效，普通视频无影响",
                checked = needTrial,
                onCheckedChange = viewModel::updateNeedTrial
            )

            SettingSwitch(
                title = "免登录看1080p",
                subtitle = "打开就算不登录也能看1080p喵",
                checked = enableWebPlayback,
                onCheckedChange = viewModel::updateEnableWebPlayback
            )

            // ── CDN 智能选线（实验，W4 数据链路的 UI 入口）─────────────────
            // 只重排/改写候选线路，不碰传输层预算；默认关，不配置时行为与现状一致。
            CdnRegionSettingsSection(
                playback = playerSettings.playback,
                onRegionEnabledChange = viewModel::updateCdnRegionEnabled,
                onStrictChange = viewModel::updateCdnStrictCustomCdn,
                onHostsChange = viewModel::updateCdnRegionHosts,
                onRulesChange = viewModel::updateCdnCustomRules
            )
        }

        if (showVideoQualityDialog) {
            VideoQualityDialog(
                currentQuality = defaultVideoQuality,
                onSelect = viewModel::updateDefaultVideoQuality,
                onDismiss = { showVideoQualityDialog = false }
            )
        }

        if (showAudioQualityDialog) {
            AudioQualityDialog(
                currentQuality = defaultAudioQuality,
                onSelect = viewModel::updateDefaultAudioQuality,
                onDismiss = { showAudioQualityDialog = false }
            )
        }

        if (showCodecDialog) {
            CodecDialog(
                currentCodec = preferredCodec,
                onSelect = viewModel::updatePreferredCodec,
                onDismiss = { showCodecDialog = false }
            )
        }
    }
}

/**
 * 分组**小节标题**（"画质 / 本地选择 / 其他 / CDN 智能选线"这类纯文字分隔）。
 *
 * ⚠️ 名字刻意**不**叫 `SettingCategory`：同包 `components/SettingItem.kt` 里已有一个
 * `SettingCategory`，但那是**可点的整行卡片**（图标 + 标题 + 副标题 + 箭头）。
 * 两个同名、不同用途的 composer 同时存在时，调用点长得一模一样、渲染却完全不同 ——
 * 这正是本仓"反复改版留下的冗余"最容易被误用的一种（第 35 轮静态复查发现）。
 * 纯标题件统一叫 `SettingGroupTitle`，一眼能看出它不带交互。
 */
@Composable
fun SettingGroupTitle(title: String) {
    Text(
        text = title,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.sm)
    )
}

@Composable
fun QualityItem(
    title: String,
    quality: Int,
    onClick: () -> Unit,
    label: String? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().appElasticPress()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg)
        ) {
            Text(title)
            Text(
                text = label ?: when {
                    quality == 0 -> "自动"
                    quality < 100 -> getVideoQualityName(quality)
                    else -> getAudioQualityName(quality)
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun VideoQualityDialog(
    currentQuality: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val qualities = listOf(16, 32, 64, 80, 112, 116, 120, 125, 126, 127)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认视频画质") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                qualities.forEach { quality ->
                    Text(
                        text = if (quality == currentQuality) "✓ ${getVideoQualityName(quality)}" else getVideoQualityName(quality),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality); onDismiss() }
                            .padding(vertical = BiliSpacing.sm)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun AudioQualityDialog(
    currentQuality: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val qualities = listOf(0, 30216, 30232, 30280, 30250, 30251)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认音频质量") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                qualities.forEach { quality ->
                    Text(
                        text = if (quality == currentQuality) "✓ ${getAudioQualityName(quality)}" else getAudioQualityName(quality),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality); onDismiss() }
                            .padding(vertical = BiliSpacing.sm)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun getVideoQualityName(quality: Int): String {
    return when (quality) {
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P 60fps"
        120 -> "4K"
        125 -> "HDR"
        126 -> "杜比视界"
        127 -> "8K"
        else -> "未知"
    }
}

private fun getAudioQualityName(quality: Int): String {
    return when (quality) {
        0 -> "自动"
        30216 -> "64K"
        30232 -> "132K"
        30280 -> "192K"
        30250 -> "杜比全景声"
        30251 -> "Hi-Res 无损"
        else -> "未知"
    }
}

@Composable
fun CodecDialog(
    currentCodec: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val codecs = listOf(1, 2, 3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择优先编码格式") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                codecs.forEach { codec ->
                    Text(
                        text = if (codec == currentCodec) "✓ ${getCodecName(codec)}" else getCodecName(codec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(codec); onDismiss() }
                            .padding(vertical = BiliSpacing.sm)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun getCodecName(codec: Int): String {
    return when (codec) {
        1 -> "AVC/H.264"
        2 -> "HEVC/H.265"
        3 -> "AV1"
        else -> "未知"
    }
}

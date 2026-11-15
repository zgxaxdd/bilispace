package com.naaammme.bbspace.feature.settings.performance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.theme.FrameRateMode
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixIconButton
import com.naaammme.bbspace.feature.settings.SettingsViewModel
import com.naaammme.bbspace.feature.settings.components.SettingSwitch
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.themeConfig.collectAsStateWithLifecycle()

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("性能设置") },
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
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
        ) {
            item {
                SettingSwitch(
                    title = "使用系统 DNS",
                    subtitle = "重启后生效，关闭后使用 HTTPDNS",
                    checked = viewModel.useSystemDns.collectAsStateWithLifecycle().value,
                    onCheckedChange = viewModel::updateUseSystemDns
                )
            }
            item {
                // Q-3：29 处"release 也要可见"的取证日志（MERGED 合并计数、刷视频补货游标、
                // 无缝转场代数、弹幕窗口加载…）此前在量产包里一律常开。现在统一由
                // `DiagnosticLog` 收口，默认关闭；需要现场取证时在这里打开（立即生效，无需重启）。
                // debug 包不受本开关影响 —— 始终可见。
                SettingSwitch(
                    title = "现场诊断日志",
                    subtitle = "打开后按 logcat 的 PortraitSwipe / FeedRepository 等标签取证；" +
                        "量产包默认关闭，debug 包始终打开",
                    checked = viewModel.diagnosticLogEnabled.collectAsStateWithLifecycle().value,
                    onCheckedChange = viewModel::updateDiagnosticLogEnabled
                )
            }
            item {
                // 「省电模式」：视频画面从 TextureView 换 SurfaceView。
                // 真机线程级实测（2026-10-07，`BV1vEa36rE4J`，45s 窗口）：播放全程 ≈82% 单核，
                // 其中 RenderThread 19.2%（TextureView 每帧都要过一遍应用渲染管线合成）；
                // 换 SurfaceView 后整机 105.5% → 75.2%（−28%）。
                // 代价如实写进 subtitle：四角变直角（SurfaceView 不能被圆角裁剪层包含，否则画面全黑）、
                // 控制条真模糊失效。**默认开**（与 AppSettings 的 `?: true` 一致），重进播放页生效。
                SettingSwitch(
                    title = "省电模式（视频用 SurfaceView）",
                    // 数字必须与上面的实测记录一致（105.5% → 75.2% 是 −28%，不是 −40%）
                    subtitle = "播放中少一次画面合成，明显降低 CPU/耗电（真机实测 −28%）。代价：控制条真模糊" +
                        "失效（改用纯色兜底）、进全屏的淡入动效取消；刷视频页维持原样；重进播放页生效",
                    checked = viewModel.playerSurfaceView.collectAsStateWithLifecycle().value,
                    onCheckedChange = viewModel::updatePlayerSurfaceView
                )
            }
            item {
                FrameRateSelector(
                    selected = config.preferredFrameRate,
                    onSelect = viewModel::updateFrameRateMode
                )
            }
        }
    }
}

@Composable
private fun FrameRateSelector(
    selected: FrameRateMode,
    onSelect: (FrameRateMode) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("屏幕刷新率", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.sm))
            Text(
                "高刷新率可提升滑动流畅度，但会增加耗电",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(BiliSpacing.md))
            FrameRateMode.entries.forEach { mode ->
                FilterChip(
                    modifier = Modifier.fillMaxWidth().appElasticPress(),
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                when (mode) {
                                    FrameRateMode.AUTO -> "自动"
                                    FrameRateMode.RATE_60 -> "60Hz"
                                    FrameRateMode.RATE_90 -> "90Hz"
                                    FrameRateMode.RATE_120 -> "120Hz"
                                    FrameRateMode.RATE_144 -> "144Hz"
                                }
                            )
                            if (mode == FrameRateMode.AUTO) {
                                Text(
                                    "默认",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
                if (mode != FrameRateMode.entries.last()) {
                    Spacer(Modifier.height(BiliSpacing.sm))
                }
            }
        }
    }
}

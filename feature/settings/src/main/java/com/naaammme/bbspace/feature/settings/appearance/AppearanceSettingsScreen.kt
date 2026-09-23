package com.naaammme.bbspace.feature.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.naaammme.bbspace.feature.settings.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.theme.AnimationSpeed
import com.naaammme.bbspace.core.designsystem.theme.CornerStyle
import com.naaammme.bbspace.core.designsystem.theme.DEFAULT_PULL_REFRESH_DISTANCE_DP
import com.naaammme.bbspace.core.designsystem.theme.PresetColors
import com.naaammme.bbspace.core.designsystem.theme.PaletteStyle
import com.naaammme.bbspace.core.designsystem.theme.ThemeMode
import com.naaammme.bbspace.core.designsystem.theme.TransitionStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.component.BiliTopBar
import com.naaammme.bbspace.core.designsystem.component.rememberBiliLargeTitleScrollState
import com.naaammme.bbspace.feature.settings.components.SettingSwitch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.themeConfig.collectAsStateWithLifecycle()
    val fixBottomBar by viewModel.fixBottomBar.collectAsStateWithLifecycle()
    val isIOS = config.uiStyle == UiStyle.IOS
    val topBarState = rememberBiliLargeTitleScrollState()

    Scaffold(
        modifier = if (isIOS) Modifier.nestedScroll(topBarState.connection) else Modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BiliTopBar(title = "外观设计", state = topBarState, onBack = onBack)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                UiStyleSelector(
                    selected = config.uiStyle,
                    onSelect = viewModel::updateUiStyle
                )
            }

            item {
                ThemeModeSelector(
                    selected = config.themeMode,
                    onSelect = viewModel::updateThemeMode
                )
            }

            item {
                // iOS 无动态取色概念：置灰保留值（方案 3.4 第 4 条）
                SettingSwitch(
                    title = "动态取色",
                    subtitle = "Android 12+ 使用系统动态配色",
                    checked = config.useDynamicColor,
                    onCheckedChange = viewModel::updateUseDynamicColor,
                    enabled = !isIOS
                )
            }

            item {
                PaletteStyleSelector(
                    selected = config.paletteStyle,
                    onSelect = viewModel::updatePaletteStyle,
                    enabled = !isIOS
                )
            }

            item {
                SettingSwitch(
                    title = "反转颜色",
                    subtitle = "交换页面背景和低饱和容器色，强调色保持不变",
                    checked = config.swapBaseColors,
                    onCheckedChange = viewModel::updateSwapBaseColors
                )
            }

            item {
                ColorPaletteSelector(
                    selected = config.seedColor,
                    onSelect = viewModel::updateSeedColor
                )
            }

            item {
                SettingSwitch(
                    title = "纯色背景",
                    subtitle = "深色用纯黑，浅色用纯白，会参与背景层级反转",
                    checked = config.isPureBlack,
                    onCheckedChange = viewModel::updateIsPureBlack
                )
            }

            item {
                SettingSwitch(
                    title = "卡片跟随壁纸取色",
                    subtitle = "推荐页卡片信息区按系统壁纸主色染色；关掉则只用封面代表色",
                    checked = config.wallpaperCardTint,
                    onCheckedChange = viewModel::updateWallpaperCardTint
                )
            }

            item {
                GlassSection(
                    isIOS = isIOS,
                    material = config.glassMaterial,
                    strength = config.glassStrength,
                    onMaterialChange = viewModel::updateGlassMaterial,
                    onStrengthChange = viewModel::updateGlassStrength
                )
            }

            item {
                FontScaleSelector(
                    scale = config.fontScale,
                    onScaleChange = viewModel::updateFontScale
                )
            }

            item {
                UiScaleSelector(
                    scale = config.uiScale,
                    onScaleChange = viewModel::updateUiScale
                )
            }

            item {
                RoundScreenSafePaddingSelector(
                    scale = config.roundScreenSafePaddingScale,
                    onScaleChange = viewModel::updateRoundScreenSafePaddingScale
                )
            }

            item {
                PullRefreshDistanceSelector(
                    distanceDp = config.pullRefreshDistanceDp,
                    onDistanceChange = viewModel::updatePullRefreshDistance
                )
            }

            item {
                SettingSwitch(
                    title = "固定底栏",
                    subtitle = "主页底栏不再随滚动自动收起",
                    checked = fixBottomBar,
                    onCheckedChange = viewModel::updateFixBottomBar
                )
            }

            item {
                val topFade by viewModel.topProgressiveFade.collectAsStateWithLifecycle()
                SettingSwitch(
                    title = "顶部渐进消隐",
                    subtitle = "内容滚入顶栏时非线性融进底色（Telegram 5 阶算法，防马赫带）",
                    checked = topFade,
                    onCheckedChange = viewModel::updateTopProgressiveFade
                )
            }

            item {
                val playerBlur by viewModel.playerControlsBlur.collectAsStateWithLifecycle()
                SettingSwitch(
                    title = "播放页控件模糊",
                    subtitle = "进度条 / 播放按钮 / 控制条采样实时画面做玻璃；关闭则回落纯色遮罩",
                    checked = playerBlur,
                    onCheckedChange = viewModel::updatePlayerControlsBlur
                )
            }

            item {
                val playerBlur by viewModel.playerControlsBlur.collectAsStateWithLifecycle()
                val playerMaterial by viewModel.playerControlsMaterial.collectAsStateWithLifecycle()
                PlayerControlsMaterialSelector(
                    selected = playerMaterial,
                    onSelect = viewModel::updatePlayerControlsMaterial,
                    // 模糊关掉时材质无从谈起，置灰避免"选了没反应"
                    enabled = playerBlur
                )
            }

            item {
                AnimationSpeedSelector(
                    speed = config.animationSpeed,
                    onSelect = viewModel::updateAnimationSpeed
                )
            }

            item {
                CornerStyleSelector(
                    selected = config.cornerStyle,
                    onSelect = viewModel::updateCornerStyle,
                    enabled = !isIOS
                )
            }

            item {
                TransitionStyleSelector(
                    selected = config.transitionStyle,
                    onSelect = viewModel::updateTransitionStyle
                )
            }
        }
    }
}

@Composable
private fun UiStyleSelector(
    selected: UiStyle,
    onSelect: (UiStyle) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("界面风格", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selected == UiStyle.MATERIAL,
                    onClick = { onSelect(UiStyle.MATERIAL) },
                    label = { Text("Material", maxLines = 1) }
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selected == UiStyle.IOS,
                    onClick = { onSelect(UiStyle.IOS) },
                    label = { Text("iOS", maxLines = 1) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (selected) {
                    UiStyle.IOS ->
                        "iOS：灰底白卡分组、大标题导航、固定圆角档；主题色作为强调色。" +
                            "动态取色 / 调色板 / 圆角风格不参与。"
                    UiStyle.MATERIAL ->
                        "Material：Material You 动态取色、调色板风格与圆角风格全部生效。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("主题模式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                    ThemeMode.SYSTEM -> "跟随系统"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPaletteSelector(
    selected: Color,
    onSelect: (Color) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("主题色", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PresetColors.forEach { (color, _) ->
                    ColorItem(
                        color = color,
                        selected = color == selected,
                        onClick = { onSelect(color) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorItem(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PaletteStyleSelector(
    selected: PaletteStyle,
    onSelect: (PaletteStyle) -> Unit,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "调色盘风格",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(12.dp))
            PaletteStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSelect(style) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = paletteStyleLabel(style),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (style == selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

private val FONT_SCALES = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f)
private val UI_SCALES = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f)
private val ROUND_SCREEN_SAFE_PADDING_SCALES = listOf(
    0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f,
    0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f
)
private val PULL_REFRESH_DISTANCES = listOf(
    16f, 24f, 32f, 40f, 48f, 56f, 64f, 72f, 80f,
    88f, 96f, 104f, 112f, 120f, 128f, 136f, 144f, 152f, 160f
)

private fun sliderIndex(value: Float, lastIndex: Int): Int {
    return value.roundToInt().coerceIn(0, lastIndex)
}

@Composable
private fun FontScaleSelector(
    scale: Float,
    onScaleChange: (Float) -> Unit
) {
    val idx = remember(scale) {
        FONT_SCALES.indexOfFirst { kotlin.math.abs(it - scale) < 0.01f }
            .coerceAtLeast(0)
    }
    // 拖动只更新本地刻度，松手才提交：字体缩放会写 DataStore 并触发全局重组
    var sliderIdx by remember(scale) { mutableIntStateOf(idx) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("字体大小", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${(FONT_SCALES[sliderIdx] * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderIdx.toFloat(),
                onValueChange = { sliderIdx = sliderIndex(it, FONT_SCALES.lastIndex) },
                onValueChangeFinished = {
                    val newScale = FONT_SCALES[sliderIdx]
                    if (kotlin.math.abs(newScale - scale) < 0.01f) return@Slider
                    onScaleChange(newScale)
                },
                valueRange = 0f..(FONT_SCALES.size - 1).toFloat(),
                steps = FONT_SCALES.size - 2
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("小", style = MaterialTheme.typography.bodySmall)
                Text("标准", style = MaterialTheme.typography.bodySmall)
                Text("大", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UiScaleSelector(
    scale: Float,
    onScaleChange: (Float) -> Unit
) {
    val idx = remember(scale) {
        UI_SCALES.indexOfFirst { kotlin.math.abs(it - scale) < 0.01f }
            .takeIf { it >= 0 }
            ?: UI_SCALES.indexOf(1.0f).coerceAtLeast(0)
    }
    var sliderIdx by remember(scale) { mutableIntStateOf(idx) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("界面缩放", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "调整界面元素的整体大小",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${(UI_SCALES[sliderIdx] * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderIdx.toFloat(),
                onValueChange = { sliderIdx = sliderIndex(it, UI_SCALES.lastIndex) },
                onValueChangeFinished = {
                    val newScale = UI_SCALES[sliderIdx]
                    if (kotlin.math.abs(newScale - scale) < 0.01f) return@Slider
                    onScaleChange(newScale)
                },
                valueRange = 0f..(UI_SCALES.size - 1).toFloat(),
                steps = UI_SCALES.size - 2
            )
        }
    }
}

@Composable
private fun RoundScreenSafePaddingSelector(
    scale: Float,
    onScaleChange: (Float) -> Unit
) {
    val idx = remember(scale) {
        ROUND_SCREEN_SAFE_PADDING_SCALES.indexOfFirst { kotlin.math.abs(it - scale) < 0.01f }
            .takeIf { it >= 0 }
            ?: ROUND_SCREEN_SAFE_PADDING_SCALES.indexOf(1.0f).coerceAtLeast(0)
    }
    var sliderIdx by remember(scale) { mutableIntStateOf(idx) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("圆屏安全边距", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅圆形屏幕生效，0% 为关闭，100% 为默认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${(ROUND_SCREEN_SAFE_PADDING_SCALES[sliderIdx] * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderIdx.toFloat(),
                onValueChange = {
                    sliderIdx = sliderIndex(it, ROUND_SCREEN_SAFE_PADDING_SCALES.lastIndex)
                },
                onValueChangeFinished = {
                    val newScale = ROUND_SCREEN_SAFE_PADDING_SCALES[sliderIdx]
                    if (kotlin.math.abs(newScale - scale) < 0.01f) return@Slider
                    onScaleChange(newScale)
                },
                valueRange = 0f..(ROUND_SCREEN_SAFE_PADDING_SCALES.size - 1).toFloat(),
                steps = ROUND_SCREEN_SAFE_PADDING_SCALES.size - 2
            )
        }
    }
}

@Composable
private fun PullRefreshDistanceSelector(
    distanceDp: Float,
    onDistanceChange: (Float) -> Unit
) {
    val idx = remember(distanceDp) {
        PULL_REFRESH_DISTANCES.indexOfFirst { kotlin.math.abs(it - distanceDp) < 0.01f }
            .takeIf { it >= 0 }
            ?: PULL_REFRESH_DISTANCES.indexOf(DEFAULT_PULL_REFRESH_DISTANCE_DP).coerceAtLeast(0)
    }
    var sliderIdx by remember(distanceDp) { mutableIntStateOf(idx) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("下拉刷新行程", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "数值越大，下拉越长才会触发刷新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${PULL_REFRESH_DISTANCES[sliderIdx].toInt()}dp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderIdx.toFloat(),
                onValueChange = {
                    sliderIdx = sliderIndex(it, PULL_REFRESH_DISTANCES.lastIndex)
                },
                onValueChangeFinished = {
                    val newDistance = PULL_REFRESH_DISTANCES[sliderIdx]
                    if (kotlin.math.abs(newDistance - distanceDp) < 0.01f) return@Slider
                    onDistanceChange(newDistance)
                },
                valueRange = 0f..(PULL_REFRESH_DISTANCES.size - 1).toFloat(),
                steps = PULL_REFRESH_DISTANCES.size - 2
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("短", style = MaterialTheme.typography.bodySmall)
                Text("标准", style = MaterialTheme.typography.bodySmall)
                Text("长", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransitionStyleSelector(
    selected: TransitionStyle,
    onSelect: (TransitionStyle) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("过渡动画", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TransitionStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSelect(style) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = transitionStyleLabel(style),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (style == selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

private fun transitionStyleLabel(style: TransitionStyle): String = when (style) {
    TransitionStyle.NONE -> "无动画"
    TransitionStyle.SHARED_AXIS_X -> "水平滑动"
    TransitionStyle.SHARED_AXIS_Y -> "垂直滑动"
    TransitionStyle.SHARED_AXIS_Z -> "缩放"
    TransitionStyle.FADE_THROUGH -> "淡入淡出"
    TransitionStyle.SLIDE -> "滑动"
}

private fun paletteStyleLabel(style: PaletteStyle): String = when (style) {
    PaletteStyle.TONAL_SPOT -> "特调"
    PaletteStyle.EXPRESSIVE -> "表现力"
    PaletteStyle.NEUTRAL -> "中性"
    PaletteStyle.VIBRANT -> "鲜艳"
    PaletteStyle.MONOCHROME -> "黑白"
}

@Composable
private fun AnimationSpeedSelector(
    speed: AnimationSpeed,
    onSelect: (AnimationSpeed) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("动画速度", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimationSpeed.entries.forEach { s ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = speed == s,
                        onClick = { onSelect(s) },
                        label = {
                            Text(
                                when (s) {
                                    AnimationSpeed.OFF -> "关闭"
                                    AnimationSpeed.FAST -> "快速"
                                    AnimationSpeed.NORMAL -> "标准"
                                    AnimationSpeed.SLOW -> "慢速"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CornerStyleSelector(
    selected: CornerStyle,
    onSelect: (CornerStyle) -> Unit,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "圆角风格",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CornerStyle.entries.forEach { s ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = selected == s,
                        onClick = { onSelect(s) },
                        enabled = enabled,
                        label = {
                            Text(
                                when (s) {
                                    CornerStyle.SQUARE -> "直角"
                                    CornerStyle.STANDARD -> "标准"
                                    CornerStyle.ROUNDED -> "圆润"
                                    CornerStyle.CIRCULAR -> "圆形"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsMaterialSelector(
    selected: GlassMaterial,
    onSelect: (GlassMaterial) -> Unit,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "播放页控件质感",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "磨砂 = 均匀雾面，画面上最稳；液态 = 强折射 + 高光边，质感更重",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 只给这两档：OFF 由上面的「播放页控件模糊」开关表达，避免两处开关语义重叠
                listOf(GlassMaterial.FROSTED, GlassMaterial.LIQUID).forEach { m ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = selected == m,
                        onClick = { onSelect(m) },
                        enabled = enabled,
                        label = {
                            Text(
                                if (m == GlassMaterial.FROSTED) "磨砂" else "液态",
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassSection(
    isIOS: Boolean,
    material: GlassMaterial,
    strength: Float,
    onMaterialChange: (GlassMaterial) -> Unit,
    onStrengthChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "液态玻璃",
                style = MaterialTheme.typography.titleMedium,
                color = if (isIOS) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "仅 iOS 风格下生效：Dock、首页顶栏与播放胶囊使用背板模糊材质（minSdk 33，全设备完整生效）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassMaterial.entries.forEach { m ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = material == m,
                        onClick = { onMaterialChange(m) },
                        enabled = isIOS,
                        label = {
                            Text(
                                when (m) {
                                    GlassMaterial.OFF -> "关闭"
                                    GlassMaterial.FROSTED -> "磨砂"
                                    GlassMaterial.LIQUID -> "液态"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
            if (material == GlassMaterial.LIQUID) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "统一强度（Dock / 顶栏 / 胶囊 / 角标 / 播放控件）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = strength,
                    onValueChange = onStrengthChange,
                    valueRange = 0.5f..2f,
                    steps = 5
                )
                Text(
                    String.format(java.util.Locale.ROOT, "%.1f×", strength),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

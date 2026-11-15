package com.naaammme.bbspace.feature.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedColorSpec
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedContrastLevel
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedPaletteStyle
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedSegmentOption
import com.naaammme.bbspace.core.designsystem.theme.scheme.resolveColorContrastOptions
import com.naaammme.bbspace.core.designsystem.theme.scheme.resolveColorContrastSubtitle
import com.naaammme.bbspace.core.designsystem.theme.scheme.resolveColorSpecDescription
import com.naaammme.bbspace.core.designsystem.theme.scheme.resolveColorSpecSubtitle
import com.naaammme.bbspace.core.designsystem.theme.scheme.resolveSeedPaletteStyleOptions
import com.naaammme.bbspace.core.designsystem.theme.scheme.supportsSeedSpec2025
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.component.BiliTopBar
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixListItem
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSlider
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSwitch
import com.naaammme.bbspace.core.designsystem.component.rememberBiliLargeTitleScrollState
import com.naaammme.bbspace.core.designsystem.component.BiliGroupHeader
import com.naaammme.bbspace.feature.settings.components.SettingSwitch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlin.math.roundToInt
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState

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
            contentPadding = PaddingValues(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
        ) {
            item(key = "hdr_风格") {
                if (isIOS) {
                    BiliGroupHeader("风格")
                }
            }

            item {
                UiStyleSelector(
                    selected = config.uiStyle,
                    onSelect = viewModel::updateUiStyle
                )
            }

            item(key = "hdr_颜色") {
                if (isIOS) {
                    BiliGroupHeader("颜色")
                }
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

            // ── 22 风格 HCT 体系（种子配色维度）──────────────────────────────
            // 三组只在"关掉动态取色 + MATERIAL + 非 MONOCHROME"时才真正参与出色；
            // 其它情况保留设置值（与上游"置灰保留值"同款语义），并给一行提示。
            item {
                SeedPaletteStyleSelector(
                    selected = config.seedPaletteStyle,
                    onSelect = viewModel::updateSeedPaletteStyle,
                    enabled = !isIOS
                )
            }

            item {
                SeedContrastSelector(
                    selected = config.seedContrastLevel,
                    onSelect = viewModel::updateSeedContrastLevel,
                    enabled = !isIOS
                )
            }

            item {
                SeedColorSpecSelector(
                    selected = config.seedColorSpec,
                    seedStyle = config.seedPaletteStyle,
                    onSelect = viewModel::updateSeedColorSpec,
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

            item(key = "hdr_材质") {
                if (isIOS) {
                    BiliGroupHeader("材质")
                }
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

            item(key = "hdr_排版与显示") {
                if (isIOS) {
                    BiliGroupHeader("排版与显示")
                }
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

            item(key = "hdr_列表与手势") {
                if (isIOS) {
                    BiliGroupHeader("列表与手势")
                }
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

            item(key = "hdr_播放页") {
                if (isIOS) {
                    BiliGroupHeader("播放页")
                }
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

            item(key = "hdr_动效") {
                if (isIOS) {
                    BiliGroupHeader("动效")
                }
            }

            item {
                AnimationSpeedSelector(
                    speed = config.animationSpeed,
                    onSelect = viewModel::updateAnimationSpeed
                )
            }

            // 全局拖拽反馈（上游 BiliPai 同名功能，2026-10-17）。
            //
            // 位置刻意紧跟「动画速度」：两者都是"动效手感"旋钮，且本开关**只影响
            // 拖拽形变、不影响按压反馈**，与"动画速度"（整体时长）不冲突。
            item {
                GlobalDragFeedbackToggle(
                    enabled = config.globalDragFeedbackEnabled,
                    onToggle = viewModel::updateGlobalDragFeedback,
                    // 复用本函数已经算好的那一处判定（:66），**不在子 composable 里再判一次**：
                    // `UiStyleDecisionRatchetTest.scatterIsNotGrowing` 按文件登记 UiStyle 判定数，
                    // 多一处就会红 —— 而那条棘轮的意图正是"不要在别处再散一份风格判定"。
                    isIOS = isIOS
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("界面风格", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f).appElasticPress(),
                    selected = selected == UiStyle.MATERIAL,
                    onClick = { onSelect(UiStyle.MATERIAL) },
                    label = { Text("Material", maxLines = 1) }
                )
                FilterChip(
                    modifier = Modifier.weight(1f).appElasticPress(),
                    selected = selected == UiStyle.IOS,
                    onClick = { onSelect(UiStyle.IOS) },
                    label = { Text("iOS", maxLines = 1) }
                )
            }
            Spacer(Modifier.height(BiliSpacing.sm))
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("主题模式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        modifier = Modifier.weight(1f).appElasticPress(),
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("主题色", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.md))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            ) {
                // P1-6：颜色名**本来就在** `PresetColors` 里（"哔哩粉"/"蓝色"…），
                // 这里此前用 `_` 把它丢掉了，于是色板块是个没有名字的按钮。
                PresetColors.forEach { (color, name) ->
                    ColorItem(
                        color = color,
                        name = name,
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
    name: String,
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
            // 全局拖拽反馈：色板是纯色圆盘可点件，按压手感必须与其余控件一致。
            // `highlightShape = CircleShape` 与上面 `clip(CircleShape)` 同源，
            // 光斑才裁在圆内（默认 RoundedCornerShape(50) 对 48dp 正方恰好等价，
            // 但显式绑同一个形状，日后改尺寸不会静默错位）。
            .appElasticPress(highlightShape = CircleShape)
            .clickable(onClick = onClick)
            // P1-6（无障碍）：这块只有纯色 + 选中对勾，**没有任何文字**，
            // 不给名字的话读屏用户听到的是一个无名按钮，分不清"哔哩粉"和"蓝色"。
            .semantics {
                contentDescription = name
                this.selected = selected
            },
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "调色盘风格",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.md))
            PaletteStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSelect(style) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).appElasticPress()
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

// ── 22 风格 HCT 体系（种子配色维度）──────────────────────────────────────────
//
// 三组设置的真实生效条件是：MATERIAL + 关闭动态取色 + paletteStyle != MONOCHROME。
// 其它情况保留用户选择（与上游"置灰保留值"同款语义），避免"切回来发现设置丢了"。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeedPaletteStyleSelector(
    selected: SeedPaletteStyle,
    onSelect: (SeedPaletteStyle) -> Unit,
    enabled: Boolean = true
) {
    val options = remember { resolveSeedPaletteStyleOptions() }
    var showSheet by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 全局拖拽反馈：整张"色彩风格"卡是可点件（点开 22 档弹层），
            // 位置在 `clickable` 之前 ⇒ 形变与按压光斑覆盖整卡。
            .appElasticPress(enabled = enabled)
            .clickable(enabled = enabled) { showSheet = true }
    ) {
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "色彩风格",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.xs))
            Text(
                text = seedPaletteStyleDisplayLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                }
            )
            val description = current?.description
            if (description != null) {
                // 微间距 2dp：BiliSpacing 最小是 xs=4dp，硬套 token 会改变布局，故保留字面量。
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 微间距 2dp：BiliSpacing 最小是 xs=4dp，硬套 token 会改变布局，故保留字面量。
            Spacer(Modifier.height(2.dp))
            Text(
                "共 ${options.size} 档 · 点按展开列表；Standard = 沿用现有配色（默认）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
    if (showSheet) {
        // 22 档用**弹层列表**而不是横排按钮：横排会挤成两行小方块、也塞不下描述。
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            dragHandle = { BiliSheetGrabber() }
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options) { option ->
                    AppMiuixListItem(
                        title = seedPaletteStyleDisplayLabel(option.value),
                        subtitle = option.description,
                        trailing = if (option.value == selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            onSelect(option.value)
                            showSheet = false
                        }
                    )
                }
            }
        }
    }
}

/** 列表里的显示名：Standard 明确标"默认"，其余用上游文案。 */
private fun seedPaletteStyleDisplayLabel(style: SeedPaletteStyle): String =
    if (style == SeedPaletteStyle.Standard) "Standard（默认 · 沿用现有配色）" else style.label

/** 单选项一行（对比度 4 档 / 色彩标准 2 档共用；支持置灰）。 */
@Composable
private fun <T> SeedRadioRow(
    option: SeedSegmentOption<T>,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 全局拖拽反馈：单选项整行可点（对比度 4 档 / 色彩标准 2 档共用）。
            // 用 0.6 克制档：这是**整行宽**控件，满强度按下时整行会明显横移。
            .appElasticPress(
                enabled = enabled,
                state = rememberAppElasticPressState(feedbackStrength = 0.6f)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = BiliSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            val description = option.description
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f
                    )
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
            )
        }
    }
}

@Composable
private fun SeedContrastSelector(
    selected: SeedContrastLevel,
    onSelect: (SeedContrastLevel) -> Unit,
    enabled: Boolean = true
) {
    val options = remember { resolveColorContrastOptions() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "对比度",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.xs))
            options.forEach { option ->
                SeedRadioRow(
                    option = option,
                    selected = option.value == selected,
                    enabled = enabled,
                    onClick = { onSelect(option.value) }
                )
            }
            // 当前值回显 + Reduced 的无障碍提示（只有这一档需要额外说明）
            Text(
                resolveColorContrastSubtitle(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            if (selected == SeedContrastLevel.Reduced) {
                // 微间距 2dp：BiliSpacing 最小是 xs=4dp，硬套 token 会改变布局，故保留字面量。
                Spacer(Modifier.height(2.dp))
                Text(
                    "低于无障碍建议：正文对比度可能不足，建议仅在个人偏好下使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SeedColorSpecSelector(
    selected: SeedColorSpec,
    seedStyle: SeedPaletteStyle,
    onSelect: (SeedColorSpec) -> Unit,
    enabled: Boolean = true
) {
    // 两档都列出来：2025 在不支持的风格上**置灰并说明已回落**，而不是直接消失
    //（用户偏好保留在设置里，切到兼容风格后自动恢复 —— 上游同款语义）。
    val options = remember {
        SeedColorSpec.entries.map { spec ->
            SeedSegmentOption(spec, spec.name, resolveColorSpecDescription(spec))
        }
    }
    val supports2025 = remember(seedStyle) { supportsSeedSpec2025(seedStyle) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "色彩标准",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.xs))
            options.forEach { option ->
                val is2025 = option.value == SeedColorSpec.SPEC_2025
                SeedRadioRow(
                    option = option,
                    selected = option.value == selected,
                    enabled = enabled && (!is2025 || supports2025),
                    onClick = { onSelect(option.value) }
                )
            }
            if (!supports2025) {
                // 微间距 2dp：BiliSpacing 最小是 xs=4dp，硬套 token 会改变布局，故保留字面量。
                Spacer(Modifier.height(2.dp))
                Text(
                    "当前风格不支持 2025，已按 2021 生效（偏好已保留）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                resolveColorSpecSubtitle(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
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
            Spacer(Modifier.height(BiliSpacing.sm))
            // 设置页批 3：字体大小改用 Miuix Slider 原语。拖动只改本地 sliderIdx、
            // 松手才 onScaleChange 的既有语义完全保留；valueRange/steps 逐字一致。
            AppMiuixSlider(
                value = sliderIdx.toFloat(),
                onValueChange = { sliderIdx = sliderIndex(it, FONT_SCALES.lastIndex) },
                onValueChangeFinished = {
                    val newScale = FONT_SCALES[sliderIdx]
                    if (kotlin.math.abs(newScale - scale) < 0.01f) return@AppMiuixSlider
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
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
            Spacer(Modifier.height(BiliSpacing.sm))
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
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
            Spacer(Modifier.height(BiliSpacing.sm))
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
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
            Spacer(Modifier.height(BiliSpacing.sm))
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("过渡动画", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.xs))
            Text(
                "作用于二级页面的推入/返回（设置、空间、搜索、历史等）；" +
                    "视频页开合走无缝过渡（见播放设置）、底栏 Tab 切换为固定淡入淡出，均不受此项影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(BiliSpacing.md))
            TransitionStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSelect(style) },
                        modifier = Modifier.weight(1f).appElasticPress()
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

/**
 * 全局拖拽反馈开关（上游 BiliPai 同名功能）。
 *
 * ## 副标题为什么用上游原话
 * 「统一控制按钮与胶囊的拖动形变，关闭后保留按压反馈」—— 这句话把**两种反馈的
 * 边界**说清楚了，而这正是最容易误解的地方：用户会以为关掉之后按钮就没反应了。
 * 实测行为是"仍会鼓起、只是不再跟手拉伸"，副标题必须把这个承诺写出来。
 */
@Composable
private fun GlobalDragFeedbackToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    /** 由调用方传入（调用方已经算过一次，见 `UiStyleDecisionRatchetTest` 的散点棘轮）。 */
    isIOS: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("全局拖拽反馈", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(BiliSpacing.xs))
                Text(
                    "统一控制按钮与胶囊的拖动形变，关闭后保留按压反馈",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(BiliSpacing.md))
            // 与全站其它开关同一出口：iOS 档走 `AppMiuixSwitch`（原语内部已挂
            // `appElasticPress()`，外层**不要**再挂 —— 叠加会双抖）。
            // 这里此前是裸 M3 `Switch`：用户档位是 IOS，于是整页只有这一颗开关
            // 是 Material 观感（第 34 轮 D8「弹层/控件语言并存」的同类问题）。
            if (isIOS) {
                AppMiuixSwitch(checked = enabled, onCheckedChange = onToggle)
            } else {
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text("动画速度", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                AnimationSpeed.entries.forEach { s ->
                    FilterChip(
                        modifier = Modifier.weight(1f).appElasticPress(),
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "圆角风格",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                CornerStyle.entries.forEach { s ->
                    FilterChip(
                        modifier = Modifier.weight(1f).appElasticPress(),
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "播放页控件质感",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.xs))
            Text(
                "磨砂 = 均匀雾面，画面上最稳；液态 = 强折射 + 高光边，质感更重",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                // 只给这两档：OFF 由上面的「播放页控件模糊」开关表达，避免两处开关语义重叠
                listOf(GlassMaterial.FROSTED, GlassMaterial.LIQUID).forEach { m ->
                    FilterChip(
                        modifier = Modifier.weight(1f).appElasticPress(),
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
        Column(modifier = Modifier.padding(BiliSpacing.lg)) {
            Text(
                "液态玻璃",
                style = MaterialTheme.typography.titleMedium,
                color = if (isIOS) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Spacer(Modifier.height(BiliSpacing.sm))
            Text(
                "仅 iOS 风格下生效：Dock、首页顶栏与播放胶囊使用背板模糊材质（minSdk 33，全设备完整生效）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(BiliSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                GlassMaterial.entries.forEach { m ->
                    FilterChip(
                        modifier = Modifier.weight(1f).appElasticPress(),
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
                Spacer(Modifier.height(BiliSpacing.md))
                Text(
                    "统一强度（Dock / 顶栏 / 胶囊 / 角标 / 播放控件）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // AP-30：与同文件另 4 个滑杆同款"本地刻度 + 松手提交" —— 强度进的是
                // LocalGlassConfig，原实现 onValueChange 直连 viewModel::updateGlassStrength
                // 会**每拖动帧写一次设置并触发全站玻璃重组**（5 个滑杆里唯一漏掉的一个）。
                var strengthDraft by remember(strength) { mutableFloatStateOf(strength) }
                Slider(
                    value = strengthDraft,
                    onValueChange = { strengthDraft = it },
                    onValueChangeFinished = {
                        if (kotlin.math.abs(strengthDraft - strength) < 0.01f) return@Slider
                        onStrengthChange(strengthDraft)
                    },
                    valueRange = 0.5f..2f,
                    steps = 5
                )
                Text(
                    String.format(java.util.Locale.ROOT, "%.1f×", strengthDraft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

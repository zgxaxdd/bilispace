package com.naaammme.bbspace.playback.capsule

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassHazeState
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassRefresher
import com.naaammme.bbspace.core.designsystem.glass.glassEffect
import com.naaammme.bbspace.core.designsystem.glass.glassFrameRefresh
import com.naaammme.bbspace.core.designsystem.glass.isGlassActive
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/**
 * 联动底栏几何常量（照搬 BiliPai `LinkedBottomDock.kt:251-279` 的骨架，
 * 现代大圆角语言下重校准）。BBSpace 的分段导航定宽 48dp/项，
 * 故导航簇宽度由 [resolveLinkedDockNavigationWidth] 算出。
 *
 * **高度关系（同心圆角的推导基础）**：
 * `barHeight(64) = controlHeight(48) + 内边距(8) × 2`
 * 于是 `dock(32) − 8 = dockChip(24)`，内外弧线曲率中心重合。
 * 改任一值都必须同步复查 [BiliRadius.dockChip]。
 */
private val DockButtonSize = 56.dp
private val DockBarHeight = 64.dp
private val DockControlHeight = 48.dp
private val DockGap = 8.dp
private val DockVerticalGap = 4.dp
private const val DOCK_MAX_WIDTH_DP = 600

/**
 * 导航分段格尺寸 / 内边距。**必须与调用方（AppNavHost 的 iOS 分段导航）一致**：
 * 布局期按它算导航簇宽度，而分段内容自己也要按同一组值摆放滑块与图标；
 * 两边不一致就会出现「滑块与图标不同心」——这正是用户反馈的"圈圈没有居中"。
 */
internal val DockNavItemWidth = 48.dp
internal val DockNavInnerPadding = 4.dp

/** 官方边距（`LinkedBottomDock.kt:157`）：horizontal 12dp / vertical 8dp。调用方算遮挡区也用它。 */
internal val DockHorizontalPadding = 12.dp
internal val DockVerticalPadding = 8.dp

/**
 * 联动底栏状态宿主（与 [LinkedBottomDock] 配套）。
 *
 * `phase` 收在 `@Stable` 对象里而不是调用方的 `mutableStateOf`：切态只重组 Dock
 * 子树，不会连带重组整个 MainTabsScaffold（含 Crossfade 与各 Tab 参数）。
 */
@Stable
class LinkedDockState(thresholdPx: Float) {
    private val accumulator = LinkedDockScrollAccumulator(thresholdPx)

    var phase: LinkedDockPhase by mutableStateOf(LinkedDockPhase.Expanded)
        private set

    fun updatePhase(next: LinkedDockPhase) {
        if (next != phase) phase = next
    }

    /** 合并态点击 / 返回键：回到展开态，并丢弃未结算的滚动累积。 */
    fun expand() {
        accumulator.reset()
        updatePhase(LinkedDockPhase.Expanded)
    }

    fun onScrollStarted() {
        accumulator.scrolling = true
    }

    /** 滚动结束（惯性完成或拖拽停止）：清零累积，避免残留进度在下次手势误触发。 */
    fun onScrollStopped() {
        accumulator.scrolling = false
        accumulator.reset()
    }

    /** 非首页 Tab：只清零，不改态（改态由 [LinkedBottomDock] 的回落逻辑负责）。 */
    fun clearAccumulation() {
        accumulator.reset()
    }

    fun onScrollDelta(delta: Float, hasAudio: Boolean, atTopPullDown: Boolean) {
        accumulator.onScrollDelta(
            delta = delta,
            hasAudio = hasAudio,
            phase = phase,
            atTopPullDown = atTopPullDown,
        )?.let { updatePhase(it) }
    }
}

/**
 * 联动底栏（任务 1 规格实现，逐段对照 BiliPai `LinkedBottomDock.kt`）。
 *
 * 与旧「高度收缩 + 两套内容交叉淡化」的区别：这里是**同一个横条子节点**被
 * [resolveLinkedDockGeometry] 在行间移动（`audioX/audioY/audioWidth/top` 全部
 * 由 `merge` 插值），播放内容与外壳全程保持组合，因此合并过程是一次连续的
 * 几何形变而不是两个组件的换脸。
 *
 * `merge` 由 [LinkedDockPhase] 经 `updateTransition` + [LocalAnimations.morph]
 * （280ms Apple easeInOut）驱动：阈值触发、一次成型，不随手指逐帧插值。
 *
 * 四个子节点（顺序即 `Layout` 测量顺序）：导航簇 / 合并态首页图标 / 小横条 / 搜索钮。
 * 四块胶囊表面（[DockGlassSurface]）统一由本组件绘制，内容槽位只画内容，
 * 保证同一帧材质一致（旧的「双行单表面」形变方案正是因为在同一块玻璃里压扁才留下残影）。
 *
 * @param nowPlayingContent 内容槽位；`null` = 无播放会话，回落单行导航。
 *   第二参数为合并进度，供内容自行收窄次级元素。
 */
@Composable
internal fun LinkedBottomDock(
    dockState: LinkedDockState,
    isHomeTab: Boolean,
    searchEnabled: Boolean,
    firstItemIcon: ImageVector,
    firstItemLabel: String,
    navigationItemCount: Int,
    onSearchClick: () -> Unit,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
    navigationContent: @Composable () -> Unit,
    nowPlayingContent: (@Composable (Modifier, Float, Boolean) -> Unit)?,
    /**
     * 小横条内容是否可见。**false 时只隐藏、不卸载**：小横条是无缝返回动画的
     * 锚点宿主，卸载会让锚点 View detach，系统返回时取不到锚点即退回 alpha
     * 交叉淡出（实测 `view not attach to window or error, do alpha anim`）。
     */
    nowPlayingVisible: Boolean = true,
      /** 小横条是否正被粒子消散接管：true 时本槽位的玻璃壳一起淡掉（与粒子同步消失）。 */
      nowPlayingDissolving: Boolean = false,
) {
    val hasAudio = nowPlayingContent != null
    val anim = LocalAnimations.current
    val phase = dockState.phase

    // 会话结束 / 离开首页 → 回落展开态（官方 :87-91 与 :118-122 的合并等价）。
    LaunchedEffect(hasAudio, isHomeTab, phase) {
        val resting = resolveLinkedDockPhaseForTab(
            isHomeTab = isHomeTab,
            currentPhase = resolveLinkedDockPhaseOnAudioChange(phase, hasAudio),
        )
        dockState.updatePhase(resting)
    }

    val transition = updateTransition(targetState = phase, label = "linkedBottomDock")
    val merge = transition.animateFloat(
        transitionSpec = {
            if (anim.reduceMotion) snap() else tween(anim.morph, easing = anim.morphEasing)
        },
        label = "dockMerge",
    ) { if (it == LinkedDockPhase.Expanded) 0f else 1f }
    // Search 态未接线（BBSpace 搜索走独立路由），进度恒为 0：搜索位保持圆形。
    val searchProgress = 0f
    val dockShape = dockCapsuleShape()
    // 小横条内容布局是否已稳定：合并动画进行中或 IME 未落定时为 false。
    //
    // 上游新增（「消除垂直运动非线性停顿」的配套）：动画期间小横条的行高/位置每帧都在变，
    // 若内容此时再做自己的布局决策（换行、跑马灯重测、次级元素消隐），会与几何插值打架，
    // 表现为落位前"顿一下"。把这个布尔下发后，内容可以在不稳定期跳过昂贵的自测量。
    // BBSpace 无 Search 态，故只需 transition.isRunning 与 IME 两个判据。
    val imeSettled = WindowInsets.ime.getBottom(LocalDensity.current) == 0
    val nowPlayingLayoutStable = !transition.isRunning && imeSettled

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = DockHorizontalPadding, vertical = DockVerticalPadding),
        content = {
            // 0 导航簇：合并初期即快速淡出（1 - merge*3），完全合并后不再组合；
            // 非展开态在 Initial pass 吞掉全部指针事件，避免点到被盖住的导航项。
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = (1f - merge.value * 3f).coerceIn(0f, 1f) }
                    .pointerInput(phase) {
                        if (phase != LinkedDockPhase.Expanded) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .then(
                        if (phase != LinkedDockPhase.Expanded) {
                            Modifier.clearAndSetSemantics {}
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DockGlassSurface(shape = dockShape, isScrolling = isScrolling, modifier = Modifier.fillMaxSize())
                if (merge.value < 0.999f) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        navigationContent()
                    }
                }
            }
            // 1 合并态最左格：首页图标即「展开底栏」按钮。
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = (merge.value * 2f).coerceIn(0f, 1f) }
                    .then(
                        if (phase != LinkedDockPhase.Expanded) {
                            Modifier.clickable(role = Role.Button) { dockState.expand() }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DockGlassSurface(shape = dockShape, isScrolling = isScrolling, modifier = Modifier.fillMaxSize())
                if (merge.value > 0.001f) {
                    Icon(
                        imageVector = firstItemIcon,
                        contentDescription = "$firstItemLabel，展开底栏",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 2 小横条：全程保持组合，靠几何插值在行间移动。外壳由 Dock 统一绘制，
            // 与导航/合并格/搜索位共用同一套玻璃材质，避免四块表面各自为政。
            Box {
                DockGlassSurface(shape = dockShape, isScrolling = isScrolling, modifier = Modifier.graphicsLayer { alpha = if (nowPlayingDissolving) 0f else 1f }.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 详情页存活期间整块淡出（alpha 到 0）但**保持组合**：
                        // 锚点 View 留在 window 上，系统返回动画才有终点可 morph。
                        //
                        // 返回动画**一开始**就要把内容显示回来：系统 morph 的是锚点这块
                        // View 的实时像素，若内容仍处于 alpha 0，返回途中看到的是一块
                        // 空玻璃壳滑回去，直到动画结束 `detailAlive` 才翻转、内容才"啪"
                        // 地出现——用户感知就是"退回到一半内容才冒出来，很奇怪"。
                        // 内容在动画起点就位，整段缩回过程才是"横条本体在收拢"。
                        .graphicsLayer { alpha = if (nowPlayingVisible) 1f else 0f }
                ) {
                    nowPlayingContent?.invoke(
                        Modifier.fillMaxSize(),
                        merge.value.coerceIn(0f, 1f),
                        nowPlayingLayoutStable
                    )
                }
            }
            // 3 搜索位：展开态贴导航簇右侧，合并态滑向容器右缘。
            Box(contentAlignment = Alignment.Center) {
                if (searchEnabled) {
                    Box(Modifier.fillMaxSize()) {
                        DockGlassSurface(shape = dockShape, isScrolling = isScrolling, modifier = Modifier.fillMaxSize())
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(dockShape)
                                .clickable(role = Role.Button, onClick = onSearchClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
    ) { children, constraints ->
        val maximumWidth = constraints.maxWidth.coerceAtMost(DOCK_MAX_WIDTH_DP.dp.roundToPx())
        val button = DockButtonSize.roundToPx()
        val barHeight = DockBarHeight.roundToPx()
        val controlHeight = DockControlHeight.roundToPx()
        val gap = DockGap.roundToPx()
        val progress = merge.value.coerceIn(0f, 1f)
        val navWidth = resolveLinkedDockNavigationWidth(
            maximumWidth = maximumWidth,
            itemCount = navigationItemCount,
            itemWidth = DockNavItemWidth.roundToPx(),
            innerPadding = DockNavInnerPadding.roundToPx(),
            button = button,
            gap = gap,
            searchEnabled = searchEnabled,
        )
        val geometry = resolveLinkedDockGeometry(
            width = maximumWidth,
            button = button,
            barHeight = barHeight,
            gap = gap,
            hasAudio = hasAudio,
            searchEnabled = searchEnabled,
            mergeProgress = progress,
            searchProgress = searchProgress,
            verticalGap = DockVerticalGap.roundToPx(),
        )
        val navigationX = resolveLinkedDockNavigationX(
            maximumWidth = maximumWidth,
            navigationWidth = navWidth,
            button = button,
            gap = gap,
            searchEnabled = searchEnabled,
        )
        val searchX = resolveLinkedDockSearchX(
            maximumWidth = maximumWidth,
            navigationWidth = navWidth,
            searchWidth = geometry.searchWidth,
            button = button,
            gap = gap,
            mergeProgress = progress,
            searchProgress = searchProgress,
        )
        val nav = children[0].measure(Constraints.fixed(navWidth, barHeight))
        val first = children[1].measure(Constraints.fixed(button, controlHeight))
        val audio = children[2].measure(
            Constraints.fixed(if (hasAudio) geometry.audioWidth else 0, if (hasAudio) controlHeight else 0)
        )
        val searchBox = children[3].measure(Constraints.fixed(geometry.searchWidth, button))
        layout(constraints.maxWidth, geometry.height) {
            val left = (constraints.maxWidth - maximumWidth) / 2
            if (progress < 0.999f) nav.placeRelative(left + navigationX, geometry.top)
            if (progress > 0.001f) first.placeRelative(left, geometry.top + (barHeight - controlHeight) / 2)
            if (hasAudio) {
                audio.placeRelative(left + geometry.audioX, geometry.audioY + (barHeight - controlHeight) / 2)
            }
            searchBox.placeRelative(left + searchX, geometry.top)
        }
    }
}

/**
 * Dock 各表面的圆角：现代大圆角语言下**两种 [UiStyle] 共用** [BiliRadius.dock]。
 *
 * 此前 Material 档取 `MaterialTheme.shapes.extraLarge`——该档位随 [CornerStyle] 漂移
 * （STANDARD 28 / ROUNDED 32 / CIRCULAR 40），同一底栏在不同主题设置下圆角不一致，
 * 且与内部子控件的 24dp 无法保证同心。Dock 是唯一的主导航 chrome，圆角是组件约定。
 */
@Composable
internal fun dockCapsuleShape(): Shape = remember { RoundedCornerShape(BiliRadius.dock) }

/**
 * Dock 单个胶囊表面：玻璃链路（消费端逐帧重采样 + haze/miuix 背板）与
 * 非玻璃降级（iOS 描边 + 阴影）都在这一处收口，导航簇 / 合并格 / 搜索位共用。
 */
@Composable
internal fun DockGlassSurface(
    shape: Shape,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val glassState = LocalGlassHazeState.current
    val glassOn = isGlassActive(glassState)
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val containerColor = when {
        // 玻璃生效：Haze / 背板染色接管，容器底透明
        glassOn -> Color.Transparent
        isIOS -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (dark) 0.90f else 0.92f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    // 非玻璃档的悬浮壳（描边 + 投影）。此前只有 iOS 档挂壳，Material 档是**平的**
    // 一块色板——同一个 Dock 在两种界面风格下判若两物。现代大圆角语言的悬浮感
    // 必须两种风格一致，只有描边色/底色按各自色彩体系取。
    val borderColor = if (dark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)
    }
    val shellModifier = when {
        glassOn -> Modifier
            // 消费端重采样：Dock 滚动隐藏期间玻璃光栅会冻结，滑回时带着旧模糊出现。
            // FL-06：消费端谓词显式化 —— Dock 是常驻导航 chrome，本设计里**没有**
            // 滚动隐藏（无 translationY 机制；GlassRecordChainStructureTest 反向断言
            // 了根源必须常采样，理由正是 Dock/胶囊仍可见），故谓词恒 true 是事实而非省略。
            // 未来若给 Dock 加滚动隐藏，必须把"移动即停止绘制"的可见性判断接到这里。
            .glassFrameRefresh(LocalGlassRefresher.current, visible = { true })
            .glassEffect(
                state = glassState,
                surfaceType = GlassSurfaceType.BOTTOM_BAR,
                shape = shape,
                isScrolling = isScrolling,
            )
        else -> Modifier
            .shadow(
                elevation = 3.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.30f),
            )
            .border(1.dp, borderColor, shape)
    }
    Surface(
        modifier = modifier.then(shellModifier),
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        content = content,
    )
}

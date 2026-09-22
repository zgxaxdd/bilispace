package com.naaammme.bbspace.playback.capsule

import kotlin.math.roundToInt

/**
 * 联动底栏三态（移植自 BiliPai `LinkedDockPolicy.kt`，逐函数对照）。
 *
 * - [Expanded]：完整导航底栏，小横条独占一行浮在导航上方；
 * - [Playback]：小横条收窄并左移，合并进导航行（导航收敛为「首页图标 + 横条 + 搜索」）；
 * - [Search]：底栏内展开搜索框。BBSpace 的搜索是独立路由，该态暂未接线，
 *   保留枚举值以对齐官方滚动/指针判定（`phase == Search` 时清零累积）。
 */
enum class LinkedDockPhase { Expanded, Playback, Search }

/** 单一方向的滚动增量累积：方向反转即重置，防止来回抖动导致状态闪烁。 */
fun accumulateDockScroll(previous: Float, delta: Float): Float =
    if (previous * delta < 0f) delta else previous + delta

/** 无播放内容时不可能停在 [Playback]，回落 [Expanded]。 */
fun resolveLinkedDockPhaseOnAudioChange(
    currentPhase: LinkedDockPhase,
    hasAudio: Boolean,
): LinkedDockPhase = if (!hasAudio && currentPhase == LinkedDockPhase.Playback) {
    LinkedDockPhase.Expanded
} else {
    currentPhase
}

/** 非首页 Tab 的静置态：横条不合并，只保留完整导航。 */
fun resolveLinkedDockPhaseForTab(isHomeTab: Boolean, currentPhase: LinkedDockPhase): LinkedDockPhase =
    if (isHomeTab) currentPhase else LinkedDockPhase.Expanded

data class LinkedDockGeometry(
    val searchWidth: Int,
    val audioWidth: Int,
    val audioX: Int,
    val audioY: Int,
    val top: Int,
    val height: Int,
)

/**
 * 合并几何（官方公式，小横条在行间被**移动**而非两个组件交叉淡入）：
 * `merge=0` 横条满宽独占上一行；`merge=1` 收窄到「首页图标之后」与导航同行；
 * 底栏总高随 `top` 收缩。
 */
fun resolveLinkedDockGeometry(
    width: Int,
    button: Int,
    barHeight: Int,
    gap: Int,
    hasAudio: Boolean,
    searchEnabled: Boolean,
    mergeProgress: Float,
    searchProgress: Float,
    verticalGap: Int = gap,
): LinkedDockGeometry {
    val merge = mergeProgress.coerceIn(0f, 1f)
    val search = searchProgress.coerceIn(0f, 1f)
    val top = ((if (hasAudio) barHeight + verticalGap else 0) * (1f - merge)).roundToInt()
    val searchWidth = if (!searchEnabled) 0 else (
        button + (width - button * (if (hasAudio) 3 else 2) - gap * (if (hasAudio) 2 else 1)) * search
    ).roundToInt().coerceAtLeast(button).coerceAtMost((width - button).coerceAtLeast(0))
    // 播放行与搜索钮各自持有独立胶囊表面。
    val playbackGap = gap
    val compactAudioWidth = (width - button - searchWidth -
        playbackGap * (if (searchEnabled) 2 else 1)).coerceAtLeast(0)
    return LinkedDockGeometry(
        searchWidth = searchWidth,
        audioWidth = if (hasAudio) (width + (compactAudioWidth - width) * merge).roundToInt() else 0,
        audioX = ((button + playbackGap) * merge).roundToInt(),
        // audioY 恒为 0 —— **上游修正，勿改回 `(top * merge)`**。
        //
        // 摆放处是 `audio.placeRelative(..., geometry.audioY + (barHeight - controlHeight) / 2)`，
        // 而 audio 行的 y 已经由布局高度 `top` 的收缩带动了一次；再叠加 `top * merge`
        // 等于**同一段位移走了两遍**，合并过程中小横条会先慢后快地"滑"过去，
        // 读作垂直运动里的非线性停顿（上游 changelog：「调优视频小横条联动动效：
        // 消除垂直运动非线性停顿，实现平滑匀称落位」）。
        // 归零后小横条的垂直位移完全由 `top` 驱动，一次成型、匀称。
        audioY = 0,
        top = top,
        height = top + barHeight,
    )
}

/**
 * 导航簇宽度：BBSpace 的导航是内容定宽的分段胶囊（`itemCount × itemWidth + 两侧内边距`），
 * 替代官方 `resolveBiliPaiFloatingBottomBarWidth`，并为搜索钮预留空间。
 */
fun resolveLinkedDockNavigationWidth(
    maximumWidth: Int,
    itemCount: Int,
    itemWidth: Int,
    innerPadding: Int,
    button: Int,
    gap: Int,
    searchEnabled: Boolean,
): Int {
    val preferred = (itemCount.coerceAtLeast(0) * itemWidth + innerPadding * 2).coerceAtLeast(0)
    val reserved = if (searchEnabled) button.coerceAtLeast(0) + gap.coerceAtLeast(0) else 0
    return preferred.coerceAtMost((maximumWidth - reserved).coerceAtLeast(0)).coerceAtMost(maximumWidth)
}

/** 导航簇（含搜索预留）整体水平居中。 */
fun resolveLinkedDockNavigationX(
    maximumWidth: Int,
    navigationWidth: Int,
    button: Int,
    gap: Int,
    searchEnabled: Boolean,
): Int {
    val safeMaximumWidth = maximumWidth.coerceAtLeast(0)
    val searchReservation = if (searchEnabled) button.coerceAtLeast(0) + gap.coerceAtLeast(0) else 0
    val clusterWidth = (navigationWidth.coerceAtLeast(0) + searchReservation)
        .coerceAtMost(safeMaximumWidth)
    return ((safeMaximumWidth - clusterWidth) / 2).coerceAtLeast(0)
}

/** 搜索钮：展开态紧贴导航簇右侧，合并/搜索态滑向容器右缘。 */
fun resolveLinkedDockSearchX(
    maximumWidth: Int,
    navigationWidth: Int,
    searchWidth: Int,
    button: Int,
    gap: Int,
    mergeProgress: Float,
    searchProgress: Float,
): Int {
    val startX = resolveLinkedDockNavigationX(
        maximumWidth = maximumWidth,
        navigationWidth = navigationWidth,
        button = button,
        gap = gap,
        searchEnabled = true,
    ) + navigationWidth.coerceAtLeast(0) + gap.coerceAtLeast(0)
    val endX = (maximumWidth - searchWidth).coerceAtLeast(0)
    val expansionProgress = maxOf(mergeProgress, searchProgress).coerceIn(0f, 1f)
    return (startX + (endX - startX) * expansionProgress).roundToInt()
}

/**
 * 阈值触发策略（官方 `LinkedBottomDock.kt:98-117` 的等价实现）：累积到 ±24dp 才切态，
 * 切态后清零；滚动停止或处于 Search 时清零。合并动画由阶段变化驱动，
 * **不随手指逐帧插值**——这是官方「丝滑」的来源。
 */
class LinkedDockScrollAccumulator(private val thresholdPx: Float) {
    private var accumulated = 0f

    /** 是否处于滚动中（拖拽或惯性）；停止时累积清零，避免残留进度误触发。 */
    var scrolling: Boolean = false

    fun reset() {
        accumulated = 0f
    }

    /**
     * @param delta 本帧**滚动偏移增量**（与官方 `scroll.floatValue` 同符号：下滑为正、
     *   上滑为负）。注意 nestedScroll 的 `consumed/available` 是**滚轮增量**，符号与此
     *   相反，调用方必须先取负（见 `AppNavHost` 的 `chromeScrollConnection`）。
     * @param atTopPullDown 已到顶仍继续下拉（官方 `offset <= 0f && delta < 0f`）
     * @return 需要切换到的态，`null` 表示不变
     */
    fun onScrollDelta(
        delta: Float,
        hasAudio: Boolean,
        phase: LinkedDockPhase,
        atTopPullDown: Boolean,
    ): LinkedDockPhase? {
        if (!scrolling || phase == LinkedDockPhase.Search) {
            accumulated = 0f
            return null
        }
        accumulated = accumulateDockScroll(accumulated, delta)
        if (atTopPullDown || accumulated <= -thresholdPx) {
            accumulated = 0f
            return LinkedDockPhase.Expanded
        }
        if (hasAudio && accumulated >= thresholdPx) {
            accumulated = 0f
            return LinkedDockPhase.Playback
        }
        return null
    }
}

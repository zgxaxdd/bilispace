package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.model.VideoTarget

/**
 * 页面视频卡片的无缝打开状态（锚点 + [LocalSeamlessCardOpener] 共享执行器）。
 *
 * 用法（三行接入任意「卡片点开视频」页面）：
 * 1. `val seamless = rememberSeamlessVideoCardState()`
 * 2. 卡片 onClick 内先 `seamless.tryOpen(target, coverUrl, coverLarge, radiusPx)`，
 *    返回 false 再走页面自身的普通打开；
 * 3. 封面 [CoverImage] 的 content lambda 内放
 *    `SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())`。
 */
@Stable
class SeamlessVideoCardState internal constructor(
    val anchorState: SeamlessAnchorState,
    private val opener: SeamlessCardOpener?
) {
    /**
     * 无缝打开已接线（处于支持无缝的环境）。false 时锚点不组合、tryOpen 恒 false。
     *
     * **2026-10-12 审查补：判据从 `opener != null` 收紧为「执行器存在 **且** 设备支持」。**
     *
     * `LocalSeamlessCardOpener` 在 `AppNavHost` 里是**无条件** provide 的（只受设置开关
     * 影响），而 `opener != null` 并不代表这台机器有 ColorOS 的无缝能力。于是走本组件
     * 的四个列表页（历史 / 稍后再看 / 空间 / 搜索）在**非 OPPO 机型**上，每张可见卡片
     * 都会多组合一个真实的 `AndroidView` 锚点节点 —— 它永远不会被用到
     * （`tryOpen` 内部那道 `isVersionSupported()` 必然拦下），纯属白付布局与内存。
     *
     * 手写调用点（`HomeVideoPage` / `CapsuleIndicator` / `RankScreen` / `HomeScreen`）
     * 都在组合期做了这道门禁，只有共享路径漏了；收紧在这里，四个页面一并受益。
     * `isVersionSupported()` 读的是进程内缓存的 `systemVersion`，组合期调用零成本。
     */
    val active: Boolean
        get() = opener != null && OplusViewSeamlessCompat.isVersionSupported()

    fun tryOpen(
        target: VideoTarget?,
        coverUrl: String?,
        coverLarge: Boolean,
        anchorRadiusPx: Float,
        listCover: Pair<Float, Float>? = null,
        /**
         * 官方 `BUNDLE_COLOR`：动画中 view 之外区域的填充色。
         *
         * **不传时由框架从 view 内容取色，取错就是"打开/返回瞬间闪一下白"**
         * （`SeamlessVideoCardOpener.tryOpen` 的注释记录了这次实测）。
         * 2026-10-12 审查补：本方法此前**没有暴露这个参数**，于是走共享路径的
         * 历史 / 稍后再看 / 空间 / 搜索四个页面全都拿不到兜底色，只能任由框架取色。
         * 默认值 `null` 保持既有调用点的行为不变（改为显式传入才算修复）。
         */
        fallbackColor: Int? = null,
        /**
         * 源卡片标识：过渡期间只有这张卡会淡出自身内容。
         * 不传（`null`）= 不淡出任何卡片，见 [SeamlessTransitionState.shouldFadeCard]。
         */
        sourceCardKey: String? = null
    ): Boolean = opener?.tryOpen(
        anchor = anchorState.anchor,
        target = target,
        coverUrl = coverUrl,
        coverLarge = coverLarge,
        anchorRadiusPx = anchorRadiusPx,
        listCover = listCover,
        fallbackColor = fallbackColor,
        sourceCardKey = sourceCardKey,
        // 把锚点状态传下去：执行器据此用**真实显示矩形**（含全局拖拽反馈的
        // graphicsLayer 形变）作为 BUNDLE_RECT，而不是 View 的布局矩形。
        // 这是用户反馈「oppo 无缝动画出现 bug，尤其排行界面」的机制性修法，
        // 详见 SeamlessVideoCardOpener.tryOpen 的 anchorState 参数注释。
        anchorState = anchorState
    ) == true
}

@Composable
fun rememberSeamlessVideoCardState(): SeamlessVideoCardState {
    val opener = LocalSeamlessCardOpener.current
    val anchorState = rememberSeamlessAnchorState()
    return remember(opener) { SeamlessVideoCardState(anchorState, opener) }
}

/**
 * 与封面同边界的零绘制锚点。放在封面 [com.naaammme.bbspace.core.designsystem.component.CoverImage]
 * 的 content lambda 内（其内部是 BoxScope，matchParentSize 即封面边界）。
 */
@Composable
fun SeamlessCardCoverAnchor(
    state: SeamlessVideoCardState,
    target: VideoTarget?,
    modifier: Modifier = Modifier
) {
    if (state.active && target != null) {
        SeamlessAnchor(state.anchorState, modifier)
    }
}

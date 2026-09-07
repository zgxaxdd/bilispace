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
    /** 无缝打开已接线（处于支持无缝的环境）。false 时锚点不组合、tryOpen 恒 false。 */
    val active: Boolean
        get() = opener != null

    fun tryOpen(
        target: VideoTarget?,
        coverUrl: String?,
        coverLarge: Boolean,
        anchorRadiusPx: Float,
        listCover: Pair<Float, Float>? = null
    ): Boolean = opener?.tryOpen(
        anchor = anchorState.anchor,
        target = target,
        coverUrl = coverUrl,
        coverLarge = coverLarge,
        anchorRadiusPx = anchorRadiusPx,
        listCover = listCover
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
